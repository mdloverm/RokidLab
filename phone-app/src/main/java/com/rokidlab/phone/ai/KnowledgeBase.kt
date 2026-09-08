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
 */
object KnowledgeBase {
    private const val TAG = "KnowledgeBase"
    const val DB_NAME = "knowledge_base.db"
    const val DB_VERSION = 1
    private const val CHUNK_SIZE = 500

    /** BM25 tf 饱和系数（k1） */
    private const val BM25_K1 = 1.2

    /** 单文档导入大小上限：超过即拒绝，避免大文件全量读入导致 OOM */
    private const val MAX_DOC_BYTES = 20L * 1024 * 1024

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

    /** 从 Uri 导入文档（.txt 读文本），返回文档信息 */
    fun importUri(context: Context, uri: Uri, displayName: String? = null): KbDocInfo? {
        return try {
            val name = displayName ?: uri.lastPathSegment ?: "doc_${System.currentTimeMillis()}"
            // 大文件全量读入会触发 OutOfMemoryError（OOM 无法被 catch(Exception) 捕获），先按元数据限制
            val size = querySize(context, uri)
            if (size > MAX_DOC_BYTES) {
                Log.w(TAG, "importUri: file too large (${size / 1024 / 1024}MB > ${MAX_DOC_BYTES / 1024 / 1024}MB), skip $name")
                return null
            }
            val text = readText(context, uri)
            if (text.isBlank()) {
                Log.w(TAG, "importUri: empty text for $name")
                return null
            }
            importText(context, name, text)
        } catch (e: Exception) {
            Log.e(TAG, "importUri failed: $displayName", e)
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

    /** 把纯文本分块存入知识库，返回文档信息 */
    fun importText(context: Context, name: String, text: String): KbDocInfo {
        val database = db(context)
        val docId = database.insert("docs", null, ContentValues().apply {
            put("name", name)
            put("size", text.length)
            put("imported_at", System.currentTimeMillis())
        })
        if (docId <= 0) throw IllegalStateException("insert doc failed")

        val chunks = splitChunks(text)
        database.beginTransaction()
        try {
            chunks.forEachIndexed { idx, chunk ->
                database.insert("chunks", null, ContentValues().apply {
                    put("doc_id", docId)
                    put("idx", idx)
                    put("text", chunk)
                })
            }
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
        Log.i(TAG, "importText: $name -> ${chunks.size} chunks")
        return KbDocInfo(docId, name, text.length, System.currentTimeMillis())
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
     * 混合检索（词法 + 统计）：一次全表扫描同时计算 BM25 式 IDF 加权得分。
     *
     * 相比旧的「命中即 +1、重复再加」平铺计分，IDF 让「只在少数块出现的关键词」权重远高于
     * 「到处都出现的常见词」，显著提升长文档/多文档下的区分度；tf 项带饱和（k1），
     * 防止单块反复堆同一个词刷分。返回命中带来源（文档名 + 块序号），供 RAG 注入时标注引用。
     *
     * 说明：纯本地、无 embedding 的混合（词法 + 统计）方案；向量召回（bge-small-zh + ONNX）
     * 作为后续升级路径，与该接口兼容（新增 embed 列后评分项再加余弦相似度即可）。
     */
    fun searchHits(context: Context, query: String, topK: Int = 3): List<KbHit> {
        val tokens = tokenize(query)
        if (tokens.isEmpty()) return emptyList()
        val database = db(context)

        // 单次 JOIN 扫描：块文本 + 文档名 + 块序号，同时统计各查询 token 的文档频率 df
        data class Row(val text: String, val docName: String, val chunkIdx: Int)
        val rows = mutableListOf<Row>()
        val df = HashMap<String, Int>()
        database.rawQuery(
            "SELECT c.text, d.name, c.idx FROM chunks c JOIN docs d ON d.id = c.doc_id",
            null,
        ).use { c ->
            while (c.moveToNext()) {
                val row = Row(c.getString(0), c.getString(1), c.getInt(2))
                rows.add(row)
                // 只统计查询 token 的 df（而非全量建立倒排），避免无关分词开销
                val rowTokens = tokenize(row.text)
                for (t in tokens) {
                    if (t in rowTokens) df[t] = (df[t] ?: 0) + 1
                }
            }
        }
        if (rows.isEmpty()) return emptyList()

        val total = rows.size
        val scored = rows.mapNotNull { row ->
            val score = scoreTextHybrid(row.text, tokens, df, total)
            if (score > 0) KbHit(row.text, row.docName, row.chunkIdx, score) else null
        }
        return scored.sortedByDescending { it.score }.take(topK)
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

    /** 文本分块：尽量在句子边界断开，每块约 CHUNK_SIZE 字符 */
    private fun splitChunks(text: String): List<String> {
        val clean = text.replace(Regex("\\s+"), " ")
        if (clean.length <= CHUNK_SIZE) return listOf(clean)
        val chunks = mutableListOf<String>()
        var start = 0
        while (start < clean.length) {
            var end = (start + CHUNK_SIZE).coerceAtMost(clean.length)
            if (end < clean.length) {
                val boundary = clean.lastIndexOfAny(
                    charArrayOf('。', '！', '？', '；', '\n', '.', '!', '?', ';'),
                    end,
                )
                if (boundary > start + CHUNK_SIZE / 2) end = boundary + 1
            }
            chunks.add(clean.substring(start, end).trim())
            start = end
        }
        return chunks.filter { it.isNotBlank() }
    }

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

    // ═══════════════════════════════════════════════════
    // 文件解析
    // ═══════════════════════════════════════════════════

    private fun readText(context: Context, uri: Uri): String {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(8192)
            var total = 0
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                // 按字节计数，与 MAX_DOC_BYTES 单位一致；元数据查不到大小时兜底限制，防止超大文本 OOM
                if (total > MAX_DOC_BYTES) {
                    Log.w(TAG, "readText: exceeds size limit, aborted")
                    return ""
                }
                out.write(buf, 0, n)
            }
            return String(out.toByteArray(), Charsets.UTF_8)
        }
        return ""
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
