package com.rokidlab.phone.ai

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import kotlin.math.ln

/** 知识库文档信息 */
data class KbDocInfo(
    val id: Long,
    val name: String,
    val size: Int,
    val importedAt: Long,
)

/** 一次检索命中：块文本 + 来源标注（文档名 + 块序号）+ 相关度得分 */
data class KbHit(
    val text: String,
    val docName: String,
    val chunkIdx: Int,
    val score: Double,
)

/**
 * 本地知识库：文档导入（txt）→ 分块 → SQLite 存储 → 关键词检索。
 *
 * 用于「拍照问 AI」的 RAG 场景：
 *   眼镜拍照 → 本地 OCR 得到题目文本 → 知识库检索相关知识块 → 注入 DeepSeek 生成答案。
 *
 * 容量设计（2026-09-15 升级）：
 *  - 导入走流式（8KB 窗口读字符、边读边分块入库），常驻内存 O(块)，单文档上限从
 *    旧版 20MB（整文档读成 String 的 OOM 保护线）提到 100MB；
 *  - 相邻块保留 CHUNK_OVERLAP 字符重叠，答案跨块边界时两块都能独立命中；
 *  - 检索的 df 统计与候选筛选下推 SQLite 原生 LIKE（C 层扫描），Java 层只加载
 *    命中块 —— 总库容量不再受「每查询全库拉进内存分词」制约。
 */
object KnowledgeBase {
    private const val TAG = "KnowledgeBase"
    const val DB_NAME = "knowledge_base.db"
    const val DB_VERSION = 1

    /** 块长目标（字符） */
    private const val CHUNK_SIZE = 500

    /** 相邻块重叠字符数：答案跨块边界时保证至少一块含完整答案 */
    private const val CHUNK_OVERLAP = 64

    /** 流式导入的读缓冲（字符） */
    private const val STREAM_READ_CHARS = 8 * 1024

    /** BM25 tf 饱和系数（k1） */
    private const val BM25_K1 = 1.2

    /**
     * 单文档导入字节上限：流式导入下内存不再随文档体积增长，
     * 上限只受导入耗时与 SQLite 库体积约束（100MB ≈ 3 万+ 块，检索仍在秒级）。
     */
    private const val MAX_DOC_BYTES = 100L * 1024 * 1024

    @Volatile
    private var helper: KbDbHelper? = null

    /** 懒初始化加锁，避免多线程并发首次调用时创建多个 helper 实例 */
    private fun db(context: Context): SQLiteDatabase =
        helper?.writableDatabase
            ?: synchronized(this) {
                helper ?: KbDbHelper(context.applicationContext).also { helper = it }
            }.writableDatabase

    // ═══════════════════════════════════════════════════
    // 导入
    // ═══════════════════════════════════════════════════

    /**
     * 从 Uri 导入文档（.txt 读文本），返回文档信息。
     *
     * 流式导入：8KB 窗口逐段读字符、边读边分块入库，常驻内存 O(块)（数百字符级），
     * 不随文档体积增长 —— 这是单文档上限能提到 100MB 的前提
     * （旧实现整文档读成单个 String，UTF-16 堆占用约为文件的 2~6 倍）。
     */
    fun importUri(context: Context, uri: Uri, displayName: String? = null): KbDocInfo? {
        val name = displayName ?: uri.lastPathSegment ?: "doc_${System.currentTimeMillis()}"
        // 元数据大小只做提前拦截；查不到（-1）时由流式读取的字节计数兜底限制
        val size = querySize(context, uri)
        if (size > MAX_DOC_BYTES) {
            Log.w(TAG, "importUri: file too large (${size / 1024 / 1024}MB > ${MAX_DOC_BYTES / 1024 / 1024}MB), skip $name")
            return null
        }
        val input = try {
            context.contentResolver.openInputStream(uri)
        } catch (e: Exception) {
            Log.e(TAG, "importUri: open stream failed: $name", e)
            null
        } ?: return null
        return try {
            input.use { importStream(context, name, it) }
        } catch (e: Exception) {
            Log.e(TAG, "importUri failed: $name", e)
            null
        }
    }

    /** 查询 Uri 对应文件大小（查不到返回 -1，由读取端做字节数兜底限制） */
    private fun querySize(context: Context, uri: Uri): Long {
        return try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L
            } ?: -1L
        } catch (e: Exception) {
            -1L
        }
    }

    /**
     * 把内存文本导入知识库（网页总结等小文本落库），内部同样走流式分块路径。
     */
    fun importText(context: Context, name: String, text: String): KbDocInfo =
        importStream(context, name, java.io.ByteArrayInputStream(text.toByteArray(Charsets.UTF_8)))

    /**
     * 流式导入主路径：字节计数限流 → UTF-8 解码 → 滚动窗口分块 → 逐块入库。
     *
     * 整个导入包在单个事务里：中途任何失败（超限/IO/空文本）endTransaction 自动回滚，
     * docs/chunks 不残留半份文档（doc 行也在事务内插入，回滚即消失）。
     */
    fun importStream(context: Context, name: String, input: java.io.InputStream): KbDocInfo {
        val database = db(context)
        val counter = CountingInputStream(input)
        database.beginTransaction()
        try {
            val docId = database.insert("docs", null, ContentValues().apply {
                put("name", name)
                put("size", 0)
                put("imported_at", System.currentTimeMillis())
            })
            if (docId <= 0) throw IllegalStateException("insert doc failed")

            val reader = java.io.InputStreamReader(counter, Charsets.UTF_8)
            val buf = CharArray(STREAM_READ_CHARS)
            val chunker = Chunker()
            var chunkIdx = 0
            var totalChars = 0
            while (true) {
                val n = reader.read(buf)
                if (n < 0) break
                totalChars += n
                chunker.feed(String(buf, 0, n))
                // 缓冲攒够一块就出块入库，窗口始终只有 O(块) 常驻
                while (true) {
                    val chunk = chunker.nextChunk(eof = false) ?: break
                    insertChunk(database, docId, chunkIdx++, chunk)
                }
            }
            // 流尾：把剩余不足一块的尾部也出完
            while (true) {
                val chunk = chunker.nextChunk(eof = true) ?: break
                insertChunk(database, docId, chunkIdx++, chunk)
            }
            if (totalChars == 0 || chunkIdx == 0) throw IllegalStateException("empty text")
            database.update(
                "docs",
                ContentValues().apply { put("size", counter.count) },
                "id=?", arrayOf(docId.toString()),
            )
            database.setTransactionSuccessful()
            Log.i(TAG, "importStream: $name -> ${counter.count} bytes, $chunkIdx chunks")
            return KbDocInfo(docId, name, counter.count.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(), System.currentTimeMillis())
        } finally {
            // 未 setSuccessful 的异常路径在这里回滚：库中不留半份文档
            database.endTransaction()
        }
    }

    private fun insertChunk(database: SQLiteDatabase, docId: Long, idx: Int, text: String) {
        database.insert("chunks", null, ContentValues().apply {
            put("doc_id", docId)
            put("idx", idx)
            put("text", text)
        })
    }

    /** 字节计数 + 硬上限流：超限抛异常，由 importStream 的事务回滚兜住 */
    private class CountingInputStream(
        private val upstream: java.io.InputStream,
    ) : java.io.InputStream() {
        var count: Long = 0
            private set

        override fun read(): Int {
            val r = upstream.read()
            if (r >= 0) advance(1)
            return r
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = upstream.read(b, off, len)
            if (n > 0) advance(n)
            return n
        }

        override fun close() {
            upstream.close()
        }

        private fun advance(n: Int) {
            count += n
            if (count > MAX_DOC_BYTES) {
                throw IllegalStateException("document exceeds ${MAX_DOC_BYTES / 1024 / 1024}MB limit")
            }
        }
    }

    /**
     * 滚动窗口分块器（流式导入用）。
     *
     * 块长约 CHUNK_SIZE 字符，优先在句子边界断开（找不到合适句界按 CHUNK_SIZE 硬切，
     * 与旧逻辑一致）；相邻块保留 CHUNK_OVERLAP 字符重叠，答案恰好跨块边界时
     * 两块都能独立命中完整上下文。流未结束时窗口内不足一块则等待下一次 feed，不硬凑尾块。
     */
    private class Chunker {
        private companion object {
            /** 句子边界字符（块切分优先在句末断开） */
            val BREAK_CHARS = charArrayOf('。', '！', '？', '；', '\n', '.', '!', '?', ';')

            /** 空白折叠（出块前把连续空白压成单空格，与旧全量清洗行为一致） */
            val WHITESPACE_RUN = Regex("\\s+")
        }

        private val buf = StringBuilder()

        fun feed(text: String) {
            buf.append(text)
        }

        /** 取出下一个块；缓冲不足一块且未到流尾时返回 null */
        fun nextChunk(eof: Boolean): String? {
            while (true) {
                val len = buf.length
                if (len == 0) return null
                if (len < CHUNK_SIZE && !eof) return null
                var end = minOf(CHUNK_SIZE, len)
                if (end < len) {
                    val boundary = buf.lastIndexOfAny(BREAK_CHARS, end)
                    if (boundary > CHUNK_SIZE / 2) end = boundary + 1
                }
                val text = buf.substring(0, end).replace(WHITESPACE_RUN, " ").trim()
                // 前进量：流未结束时保留 CHUNK_OVERLAP 字符作下一块开头；流尾全部消费
                val consumed = if (eof) end else (end - CHUNK_OVERLAP).coerceAtLeast(1)
                buf.delete(0, consumed)
                if (text.isNotEmpty()) return text
            }
        }
    }

    // ═══════════════════════════════════════════════════
    // 检索
    // ═══════════════════════════════════════════════════

    /**
     * 用查询文本检索最相关的知识块（topK 个），按相关度降序（兼容旧接口，无来源标注）。
     * 新代码请用 [searchHits]（带文档名/块序号来源标注 + IDF 混合评分）。
     */
    fun search(context: Context, query: String, topK: Int = 3): List<String> =
        searchHits(context, query, topK).map { it.text }

    /**
     * 混合检索（词法 + 统计）：BM25 式 IDF 加权得分，返回带来源（文档名 + 块序号）的命中。
     *
     * 相比旧的「命中即 +1」平铺计分，IDF 让「只在少数块出现的关键词」权重远高于
     * 「到处都出现的常见词」；tf 项带饱和（k1），防止单块反复堆同一个词刷分。
     *
     * 性能设计（支撑大知识库，2026-09-15 升级）：
     *  - df 统计与候选块筛选都下推为 SQLite 原生 LIKE 查询（C 层扫描），Java 层只加载
     *    「命中至少一个查询词」的候选块 —— 旧实现每查询把全库文本拉进内存逐块分词，
     *    库一大（数十 MB+）查询耗时与内存峰值随库容量线性恶化；
     *  - LIKE 是子串匹配，与「token 是块文本子串」的定义严格等价，df/评分语义不变
     *    （ASCII 侧由区分大小写变为不区分，属顺带改善）；
     *  - 相邻块因 CHUNK_OVERLAP 重叠会双双命中，topK 池子里做 2-gram 相似去重，
     *    避免近重复块占满名额。
     *
     * 说明：纯本地、无 embedding 的混合（词法 + 统计）方案；向量召回（bge-small-zh + ONNX）
     * 作为后续升级路径，与该接口兼容（新增 embed 列后评分项再加余弦相似度即可）。
     */
    fun searchHits(context: Context, query: String, topK: Int = 3): List<KbHit> {
        val tokens = tokenize(query)
        if (tokens.isEmpty()) return emptyList()
        val database = db(context)

        // 1) 每个查询 token 的块级文档频率 df：原生 COUNT（等价旧版逐块分词统计，省掉全库分词）
        val df = HashMap<String, Int>(tokens.size)
        for (t in tokens) {
            database.rawQuery(
                "SELECT COUNT(*) FROM chunks WHERE text LIKE ? ESCAPE '\\'",
                arrayOf(likeArg(t)),
            ).use { c ->
                df[t] = if (c.moveToFirst()) c.getInt(0) else 0
            }
        }

        // 2) 候选块：只取命中任一 token 的块（原生 OR-LIKE 过滤，命中集通常远小于全库）
        data class Row(val text: String, val docName: String, val chunkIdx: Int)
        val where = tokens.joinToString(" OR ") { "c.text LIKE ? ESCAPE '\\'" }
        val rows = mutableListOf<Row>()
        database.rawQuery(
            "SELECT c.text, d.name, c.idx FROM chunks c JOIN docs d ON d.id = c.doc_id WHERE $where",
            tokens.map { likeArg(it) }.toTypedArray(),
        ).use { c ->
            while (c.moveToNext()) {
                rows.add(Row(c.getString(0), c.getString(1), c.getInt(2)))
            }
        }
        if (rows.isEmpty()) return emptyList()

        // 3) IDF 混合评分（N = 全库块数，与旧口径一致）
        val total = database.rawQuery("SELECT COUNT(*) FROM chunks", null).use { c ->
            if (c.moveToFirst()) c.getInt(0) else rows.size
        }
        val scored = rows.mapNotNull { row ->
            val score = scoreTextHybrid(row.text, tokens, df, total)
            if (score > 0) KbHit(row.text, row.docName, row.chunkIdx, score) else null
        }

        // 4) 相关度降序遍历，重叠近重复块去重后截到 topK
        val kept = mutableListOf<KbHit>()
        for (hit in scored.sortedByDescending { it.score }) {
            if (kept.none { tooSimilar(it.text, hit.text) }) kept.add(hit)
            if (kept.size >= topK) break
        }
        return kept
    }

    // ═══════════════════════════════════════════════════
    // 文档管理
    // ═══════════════════════════════════════════════════

    fun listDocs(context: Context): List<KbDocInfo> {
        val database = db(context)
        val result = mutableListOf<KbDocInfo>()
        database.rawQuery("SELECT id, name, size, imported_at FROM docs ORDER BY imported_at DESC", null)
            .use { c ->
                while (c.moveToNext()) {
                    result.add(KbDocInfo(c.getLong(0), c.getString(1), c.getInt(2), c.getLong(3)))
                }
            }
        return result
    }

    fun deleteDoc(context: Context, docId: Long) {
        val database = db(context)
        // chunks 与 docs 两删包进事务：避免删到一半崩溃残留孤儿 chunk
        database.beginTransaction()
        try {
            database.delete("chunks", "doc_id=?", arrayOf(docId.toString()))
            database.delete("docs", "id=?", arrayOf(docId.toString()))
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
    }

    fun docCount(context: Context): Int {
        val database = db(context)
        database.rawQuery("SELECT COUNT(*) FROM docs", null).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    // ═══════════════════════════════════════════════════
    // 内部工具
    // ═══════════════════════════════════════════════════

    /** 关键词提取：中文 2-gram + 英文/数字整词，过滤标点 */
    private fun tokenize(text: String): Set<String> {
        val tokens = mutableSetOf<String>()
        var i = 0
        val raw = text
        while (i < raw.length) {
            val ch = raw[i]
            if (ch.isChineseChar()) {
                if (i + 1 < raw.length && raw[i + 1].isChineseChar()) {
                    tokens.add(raw.substring(i, i + 2))
                }
                i++
            } else if (ch.isLetterOrDigit()) {
                val sb = StringBuilder()
                while (i < raw.length && !raw[i].isChineseChar() && raw[i].isLetterOrDigit()) {
                    sb.append(raw[i]); i++
                }
                if (sb.length >= 2) tokens.add(sb.toString())
            } else {
                i++
            }
        }
        return tokens
    }

    private fun Char.isChineseChar(): Boolean = this in '\u4e00'..'\u9fff'

    /**
     * 相邻重叠块去重：两块共享 2-gram 占较小块的 60% 以上视为重复内容。
     * 重叠块（CHUNK_OVERLAP=64/500 ≈ 13% 重叠）一般达不到 60%，
     * 只有内容确实大量重复（如同一答案在相邻块重现）才会被折叠，保留得分高的那块。
     */
    private fun tooSimilar(a: String, b: String): Boolean {
        val sa = tokenize(a)
        val sb = tokenize(b)
        if (sa.isEmpty() || sb.isEmpty()) return false
        val shared = sa.count { it in sb }
        return shared.toDouble() / minOf(sa.size, sb.size) > 0.6
    }

    /** LIKE 参数：通配符转义 + 前后通配（子串匹配语义，与 contains 等价） */
    private fun likeArg(token: String): String {
        val escaped = token
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_")
        return "%$escaped%"
    }

    /** 块得分（旧平铺计分，保留给潜在调用方）：每个命中的关键词贡献 1 + 出现次数 */
    private fun scoreText(text: String, tokens: Set<String>): Double {
        var score = 0.0
        for (token in tokens) {
            var count = 0
            var idx = text.indexOf(token)
            while (idx >= 0) {
                count++
                idx = text.indexOf(token, idx + token.length)
            }
            if (count > 0) score += 1.0 + count
        }
        return score
    }

    /**
     * BM25 式混合评分：Σ idf(t) × tf·(k1+1)/(tf+k1)。
     * idf = ln(1 + (N-df+0.5)/(df+0.5))，稀有词（df 小）得分高、常见词得分低；
     * tf 项 k1 饱和（k1=1.2），重复出现增益递减，防止单块堆词刷分。
     */
    private fun scoreTextHybrid(text: String, tokens: Set<String>, df: Map<String, Int>, totalDocs: Int): Double {
        var score = 0.0
        for (token in tokens) {
            val dfv = df[token] ?: continue
            if (dfv <= 0) continue
            val idf = ln(1.0 + (totalDocs - dfv + 0.5) / (dfv + 0.5))
            var tf = 0
            var idx = text.indexOf(token)
            while (idx >= 0) {
                tf++
                idx = text.indexOf(token, idx + token.length)
            }
            if (tf > 0) score += idf * (tf * (BM25_K1 + 1)) / (tf + BM25_K1)
        }
        return score
    }
}

/** SQLite 帮助类 */
private class KbDbHelper(context: Context) : SQLiteOpenHelper(context, KnowledgeBase.DB_NAME, null, KnowledgeBase.DB_VERSION) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE docs (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "name TEXT NOT NULL, " +
                "size INTEGER NOT NULL, " +
                "imported_at INTEGER NOT NULL)",
        )
        db.execSQL(
            "CREATE TABLE chunks (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "doc_id INTEGER NOT NULL, " +
                "idx INTEGER NOT NULL, " +
                "text TEXT NOT NULL)",
        )
        db.execSQL("CREATE INDEX idx_chunks_doc ON chunks(doc_id)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS chunks")
        db.execSQL("DROP TABLE IF EXISTS docs")
        onCreate(db)
    }
}
