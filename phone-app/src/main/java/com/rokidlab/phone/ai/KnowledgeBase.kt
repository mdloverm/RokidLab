package com.rokidlab.phone.ai

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.rokidlab.phone.ai.embedding.EmbeddingClient
import com.rokidlab.phone.ai.embedding.EmbeddingSettings
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ln

/** 知识库文档信息 */
data class KbDocInfo(
    val id: Long,
    val name: String,
    val size: Int,
    val importedAt: Long,
    /**
     * 该文档切出了多少块。
     *
     * 单独暴露这个数字是为了**让"导入成功但内容不可用"看得见**：改造前列表只显示
     * 文件名与字节数，而这两项在乱码导入时同样正确 ⇒ 用户完全无法判断导入有没有真的
     * 生效（这正是 2026-09-20「传了 txt 却问不出内容」那个 bug 能潜伏到现在的原因）。
     */
    val chunkCount: Int = 0,
    /** 该文档的编码判定结果（如 UTF-8 / GB18030 / UTF-16LE），用于界面上如实说明 */
    val charset: String = "",
)

/** 一次检索命中：块文本 + 来源标注（文档名 + 块序号）+ 相关度得分 */
data class KbHit(
    val text: String,
    val docName: String,
    val chunkIdx: Int,
    val score: Double,
)

/**
 * 向量索引状态（给管理界面展示「已索引 a / 共 b 块」用）。
 * [model] 为空表示当前没有任何块建立过向量索引。
 */
data class KbVectorStatus(
    val totalChunks: Int,
    val embeddedChunks: Int,
    val model: String,
)

/**
 * 一份文档的完整正文。
 *
 * [truncated] = 因超过读取上限被截断，调用方**必须据此禁用编辑**：
 * 拿截断后的文本保存会把后半篇直接删掉，属于不可恢复的数据丢失。
 */
data class KbDocText(val text: String, val truncated: Boolean)

/** 日志 TAG。提到文件级是因为同文件的 KbDbHelper 也要用，而 object 的 private 成员它看不见。 */
private const val TAG = "KnowledgeBase"

/**
 * 本地知识库：文档导入（txt）→ 编码判定 → 分块 → SQLite 存储 → 关键词检索。
 *
 * 用于 RAG 场景：眼镜拍照或对话提问 → 检索相关知识块 → 注入 system 提示词作为参考资料。
 * （「拍照问 AI」链路会先过本地 OCR，把识别出的文字当作查询。）
 *
 * ⚠️ 导入必须经 [TextEncoding] 判定编码（2026-09-20 修复）：先前无条件按 UTF-8 解码，
 * 中文 Windows 的 `ANSI(GBK)` / `Unicode(UTF-16)` txt 会被整篇解成替换符，而
 * **文件名与字节数照旧正确** ⇒ 用户以为导入成功，库里却永远检索不到 —— 症状就是
 * "传了文档却问不出里面的内容"。
 *
 * 容量设计（2026-09-15 升级）：
 *  - 导入走流式（8KB 窗口读字符、边读边分块入库），常驻内存 O(块)，单文档上限从
 *    旧版 20MB（整文档读成 String 的 OOM 保护线）提到 100MB；
 *  - 相邻块保留 CHUNK_OVERLAP 字符重叠，答案跨块边界时两块都能独立命中；
 *  - 检索的 df 统计与候选筛选下推 SQLite 原生 LIKE（C 层扫描），Java 层只加载
 *    命中块 —— 总库容量不再受「每查询全库拉进内存分词」制约。
 */
object KnowledgeBase {
    const val DB_NAME = "knowledge_base.db"

    /**
     * 库版本。**提升前先确认 [KbDbHelper.onUpgrade] 有对应的增量迁移** ——
     * 知识库装的是用户自己导入的资料，升级把库清空属于不可恢复的数据丢失。
     * v2：docs 增加 charset 列（记录导入时判定的编码，见 [TextEncoding]）。
     * v3：chunks 增加 embedding / embedding_model 列（语义向量索引，见 [backfillEmbeddings]）。
     */
    const val DB_VERSION = 3

    /** 块长目标（字符） */
    private const val CHUNK_SIZE = 500

    /** 相邻块重叠字符数：答案跨块边界时保证至少一块含完整答案 */
    private const val CHUNK_OVERLAP = 64

    /** 流式导入的读缓冲（字符） */
    private const val STREAM_READ_CHARS = 8 * 1024

    /** BM25 tf 饱和系数（k1） */
    private const val BM25_K1 = 1.2

    // ── 混合检索（v3：词法 BM25 + 语义向量，RRF 融合）──
    /** RRF（Reciprocal Rank Fusion）常数：越大两路排名差异越平缓，60 是论文经验值 */
    private const val RRF_K = 60

    /** 融合时每一路最多取多少候选（最终只输出 topK，池子大一点保证交叉覆盖） */
    private const val FUSION_POOL = 40

    /**
     * 全量向量暴力扫描的块数上限。
     *
     * 没有 ANN 扩展（Android 自带 SQLite 无向量索引），语义通道是「全量嵌入点积」。
     * 6000 块 × 1024 维 ≈ 6M 次乘加（几十 ms 级）、约 24MB 临时内存，可接受；
     * 超过则本路自动跳过、只走 BM25（在 [vectorStatus] 里用户能看到索引规模）。
     * 现实里手机端个人知识库几乎不可能达到这个量级。
     */
    private const val VECTOR_SCAN_MAX_CHUNKS = 6000

    /**
     * 单次检索最多使用多少个查询 token。
     *
     * 每个 token 都要单独做一次全表 `LIKE` 统计 df，候选查询又是一条 OR 链 ——
     * 而调用侧允许最长 500 字的提问（≈499 个中文 2-gram），那等于几百次全表扫描 + 一条
     * 超长 OR 链，长粘贴场景会把整轮对话卡住（拍照答题链路还没有超时兜底）。
     * 正常提问（十余字）远在 32 以内 ⇒ 对现实输入行为不变，只砍掉病态长输入。
     */
    private const val MAX_QUERY_TOKENS = 32

    /**
     * 单文档导入字节上限：流式导入下内存不再随文档体积增长，
     * 上限只受导入耗时与 SQLite 库体积约束（100MB ≈ 3 万+ 块，检索仍在秒级）。
     */
    private const val MAX_DOC_BYTES = 100L * 1024 * 1024

    /**
     * 管理界面**查看/编辑**时最多取多少字符。
     *
     * 单文档上限 100MB，全量读进内存会 OOM，UI 也显示不了那么多字 ⇒ 超出即截断，
     * 并由调用方据此**禁用编辑**（见 [KbDocText.truncated]）。
     */
    const val MAX_VIEW_CHARS = 200_000

    @Volatile
    private var helper: KbDbHelper? = null

    /** 向量召回的内部候选（融合用；score = 与查询的余弦） */
    private data class VecCandidate(
        val id: Long,
        val text: String,
        val docName: String,
        val chunkIdx: Int,
        val cos: Double,
    )

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
     * 流式导入主路径：编码判定 → 字节计数限流 → 解码 → 滚动窗口分块 → 逐块入库。
     *
     * 整个导入包在单个事务里：中途任何失败（超限/IO/空文本）endTransaction 自动回滚，
     * docs/chunks 不残留半份文档（doc 行也在事务内插入，回滚即消失）。
     */
    fun importStream(context: Context, name: String, input: java.io.InputStream): KbDocInfo {
        val database = db(context)
        val counter = CountingInputStream(input)
        // 编码判定先于一切：读一小段样本（≤ PROBE_BYTES）决定字符集，再把它拼回流头继续**流式**读。
        // 不判定就按 UTF-8 硬解 ⇒ 中文 Windows 的 GBK/UTF-16 txt 会整篇变替换符，
        // 而文件名/字节数照旧正确，用户看不出导入已经毁了（见 TextEncoding 的头注释）。
        val head = readHead(counter, TextEncoding.PROBE_BYTES)
        val decision = TextEncoding.decide(head)
        database.beginTransaction()
        try {
            val docId = database.insert("docs", null, ContentValues().apply {
                put("name", name)
                put("size", 0)
                put("imported_at", System.currentTimeMillis())
                put("charset", decision.charset.name())
            })
            if (docId <= 0) throw IllegalStateException("insert doc failed")

            // 样本已经过 counter 计过数，这里拼回去不会重复计入 size；
            // 同时按 bomBytes 跳过 BOM —— BOM 是编码标记，不该混进正文被检索
            val body = java.io.SequenceInputStream(
                java.io.ByteArrayInputStream(head, decision.bomBytes, head.size - decision.bomBytes),
                counter,
            )
            val reader = java.io.InputStreamReader(body, decision.charset)
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
            Log.i(TAG, "importStream: $name -> ${counter.count} bytes, $chunkIdx chunks, charset=${decision.charset.name()}")
            return KbDocInfo(
                docId,
                name,
                counter.count.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
                System.currentTimeMillis(),
                chunkIdx,
                decision.charset.name(),
            )
        } finally {
            // 未 setSuccessful 的异常路径在这里回滚：库中不留半份文档
            database.endTransaction()
        }
    }

    /** 读取至多 [max] 字节作为编码探测样本（不足则返回实际读到的长度）。 */
    private fun readHead(input: java.io.InputStream, max: Int): ByteArray {
        val buf = ByteArray(max)
        var off = 0
        while (off < max) {
            val n = input.read(buf, off, max - off)
            if (n < 0) break
            off += n
        }
        return if (off == max) buf else buf.copyOf(off)
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
     * 混合检索：**词法 BM25 + 语义向量，RRF 排名融合**（v3 升级）。
     *
     * 两路召回互补：
     *  - BM25 擅长精确关键词（型号、专有名词、报错码）；
     *  - 向量擅长「换个说法」（用户问「这东西怎么连」、文档写「配对方法」）——
     *    这正是纯词法 2-gram/LIKE 方案最常失手、空结果只能让模型换关键词重试的场景。
     *
     * 融合用 RRF（`1/(k+rank)` 相加）：两路分数尺度完全不同（BM25 是无界 idf 加权、
     * 余弦在 -1~1），直接线性加权需要手调权重且随库漂移；RRF 只看名次，免调参、
     * 对单路噪声稳健，是检索系统的工业惯例。
     *
     * 降级语义（重要）：语义通道在「开关关 / 未配置嵌入模型 / 网络失败 / 库超过
     * [VECTOR_SCAN_MAX_CHUNKS]」任一情况下自动缺席，结果与 v2 纯 BM25 **完全一致**；
     * 查询连一个词法 token 都切不出来时（如纯标点提问），只要语义通道可用仍能召回。
     *
     * BM25 性能设计（2026-09-15）：df 统计与候选筛选下推 SQLite 原生 LIKE（C 层扫描），
     * Java 层只加载命中块；相邻重叠块在最终 topK 池子做 2-gram 相似去重。
     */
    fun searchHits(context: Context, query: String, topK: Int = 3): List<KbHit> {
        val database = db(context)

        // ── 语义通道（失败自动缺席，绝不拖垮整条检索）──
        val vecRanked: List<VecCandidate> = runCatching { vectorCandidates(context, database, query) }
            .getOrElse { e ->
                Log.w(TAG, "vector recall skipped: ${e.message}")
                emptyList()
            }

        // 截断到 MAX_QUERY_TOKENS（见该常量注释）；tokenize 返回 Set，截完仍需是 Set
        val tokens = tokenize(query).take(MAX_QUERY_TOKENS).toSet()
        if (tokens.isEmpty()) {
            // 纯标点/无词法 token：词法通道无信号，语义可用则直接用语义排序
            if (vecRanked.isEmpty()) return emptyList()
            return finalize(vecRanked.map { KbHit(it.text, it.docName, it.chunkIdx, it.cos) }, topK)
        }

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
        data class Row(val id: Long, val text: String, val docName: String, val chunkIdx: Int)
        val where = tokens.joinToString(" OR ") { "c.text LIKE ? ESCAPE '\\'" }
        val rows = mutableListOf<Row>()
        database.rawQuery(
            "SELECT c.id, c.text, d.name, c.idx FROM chunks c JOIN docs d ON d.id = c.doc_id WHERE $where",
            tokens.map { likeArg(it) }.toTypedArray(),
        ).use { c ->
            while (c.moveToNext()) {
                rows.add(Row(c.getLong(0), c.getString(1), c.getString(2), c.getInt(3)))
            }
        }

        // 3) IDF 混合评分（N = 全库块数，与旧口径一致）
        val total = database.rawQuery("SELECT COUNT(*) FROM chunks", null).use { c ->
            if (c.moveToFirst()) c.getInt(0) else rows.size
        }
        data class Bm(val id: Long, val hit: KbHit)
        val bmRanked = rows.mapNotNull { row ->
            val score = scoreTextHybrid(row.text, tokens, df, total)
            if (score > 0) Bm(row.id, KbHit(row.text, row.docName, row.chunkIdx, score)) else null
        }.sortedByDescending { it.hit.score }

        // 语义通道缺席 ⇒ 完全保持 v2 行为
        if (vecRanked.isEmpty()) return finalize(bmRanked.map { it.hit }, topK)

        // 4) RRF 融合：同一块（chunk id）在两路的名次倒数相加
        data class Fused(val id: Long, val hit: KbHit, var rrf: Double)
        val byId = LinkedHashMap<Long, Fused>()
        bmRanked.take(FUSION_POOL).forEachIndexed { rank, b ->
            byId.getOrPut(b.id) { Fused(b.id, b.hit, 0.0) }.rrf += 1.0 / (RRF_K + rank + 1)
        }
        vecRanked.take(FUSION_POOL).forEachIndexed { rank, v ->
            val existing = byId.getOrPut(v.id) {
                Fused(v.id, KbHit(v.text, v.docName, v.chunkIdx, 0.0), 0.0)
            }
            existing.rrf += 1.0 / (RRF_K + rank + 1)
        }
        return finalize(byId.values.sortedByDescending { it.rrf }.map { it.hit }, topK)
    }

    /** 相关度降序 + 重叠近重复块去重，截到 topK（两路/融合共用的最终收口） */
    private fun finalize(scored: List<KbHit>, topK: Int): List<KbHit> {
        val kept = mutableListOf<KbHit>()
        for (hit in scored) {
            if (kept.none { tooSimilar(it.text, hit.text) }) kept.add(hit)
            if (kept.size >= topK) break
        }
        return kept
    }

    /**
     * 语义召回：嵌入查询 → 全量已索引块做归一化点积（= 余弦）→ 取 top [FUSION_POOL]。
     * 任一前置条件不满足（开关/模型/规模）返回空列表，调用方按「语义通道缺席」处理。
     */
    private fun vectorCandidates(context: Context, database: SQLiteDatabase, query: String): List<VecCandidate> {
        val snap = EmbeddingSettings.load(context)
        if (!snap.ready) return emptyList()
        val embedded = database.rawQuery(
            "SELECT COUNT(*) FROM chunks WHERE embedding IS NOT NULL", null,
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
        if (embedded == 0) return emptyList()
        if (embedded > VECTOR_SCAN_MAX_CHUNKS) {
            Log.w(TAG, "vector recall skipped: $embedded chunks > $VECTOR_SCAN_MAX_CHUNKS scan guard")
            return emptyList()
        }
        // 每查询一次远程嵌入调用；失败向上抛，由 searchHits 统一兜底为纯 BM25
        val qv = EmbeddingClient.embed(
            snap.baseUrl,
            EmbeddingSettings.apiKey(context),
            snap.model,
            listOf(query),
        ).vectors.firstOrNull() ?: return emptyList()

        val all = ArrayList<VecCandidate>(embedded)
        database.rawQuery(
            "SELECT c.id, c.text, d.name, c.idx, c.embedding FROM chunks c " +
                "JOIN docs d ON d.id = c.doc_id WHERE c.embedding IS NOT NULL",
            null,
        ).use { c ->
            while (c.moveToNext()) {
                val blob = c.getBlob(4) ?: continue
                val v = decodeVector(blob)
                if (v.size != qv.size) continue // 换过维度不同的模型且未重建：该块不参与，等重建
                var dot = 0.0
                for (i in v.indices) dot += v[i].toDouble() * qv[i].toDouble()
                if (dot > 0.0) {
                    all.add(VecCandidate(c.getLong(0), c.getString(1), c.getString(2), c.getInt(3), dot))
                }
            }
        }
        return all.sortedByDescending { it.cos }.take(FUSION_POOL)
    }

    // ═══════════════════════════════════════════════════
    // 文档管理
    // ═══════════════════════════════════════════════════

    fun listDocs(context: Context): List<KbDocInfo> {
        val database = db(context)
        // 块数一次 GROUP BY 查完（不按文档逐个 COUNT，避免 N 份文档查 N 次）
        val counts = HashMap<Long, Int>()
        database.rawQuery("SELECT doc_id, COUNT(*) FROM chunks GROUP BY doc_id", null).use { c ->
            while (c.moveToNext()) counts[c.getLong(0)] = c.getInt(1)
        }
        val result = mutableListOf<KbDocInfo>()
        database.rawQuery(
            "SELECT id, name, size, imported_at, charset FROM docs ORDER BY imported_at DESC",
            null,
        ).use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                result.add(
                    KbDocInfo(
                        id,
                        c.getString(1),
                        c.getInt(2),
                        c.getLong(3),
                        counts[id] ?: 0,
                        c.getString(4) ?: "",
                    ),
                )
            }
        }
        return result
    }

    /**
     * 取某文档的**完整正文**（按块序拼接并消掉相邻块的重叠），供管理界面查看/编辑。
     *
     * 为什么要有它：之前管理界面只能显示首块的前 160 字，用户点开既看不全、也改不了 ——
     * "导进去的东西到底长什么样"本该是最基本的可核对项。
     *
     * ⚠️ **读取有上限**（[limit]，默认 [MAX_VIEW_CHARS]）：见该常量的说明。
     * 返回 null = 该文档没有任何块。
     */
    fun fullText(context: Context, docId: Long, limit: Int = MAX_VIEW_CHARS): KbDocText? {
        val database = db(context)
        val chunks = mutableListOf<String>()
        var total = 0
        var truncated = false
        database.rawQuery(
            "SELECT text FROM chunks WHERE doc_id=? ORDER BY idx",
            arrayOf(docId.toString()),
        ).use { c ->
            while (c.moveToNext()) {
                if (total >= limit) {
                    truncated = true
                    break
                }
                val t = c.getString(0) ?: continue
                chunks.add(t)
                total += t.length
            }
        }
        if (chunks.isEmpty()) return null
        val stitched = stitch(chunks)
        // 拼接只会因去重叠而变短，但保险起见仍按 limit 收口
        return if (stitched.length > limit) {
            KbDocText(stitched.take(limit), true)
        } else {
            KbDocText(stitched, truncated)
        }
    }

    /**
     * 拼接相邻块并**消掉重叠**。
     *
     * 相邻块刻意保留 [CHUNK_OVERLAP] 字符重叠（答案跨块边界时两块都能独立命中），
     * 直接 concat 会让每个交界处重复一小段。这里按「已累积文本的后缀 == 下一块的前缀」
     * 取最长匹配并跳过 —— 两块出自同一段原文，空白折叠/trim 只会让匹配变短，不影响正确性。
     *
     * `internal`（而非 private）是为了让单测能直接钉住它：这是**唯一会改动用户正文**的逻辑，
     * 一旦多舍/少舍都会让保存后的文档缺字或重字。见 `KnowledgeBaseStitchTest`。
     */
    internal fun stitch(chunks: List<String>): String {
        if (chunks.size == 1) return chunks[0]
        val sb = StringBuilder(chunks[0])
        for (i in 1 until chunks.size) {
            val next = chunks[i]
            val max = minOf(sb.length, next.length, CHUNK_OVERLAP * 4)
            var skip = 0
            var k = max
            while (k > 0) {
                if (sb.regionMatches(sb.length - k, next, 0, k)) {
                    skip = k
                    break
                }
                k--
            }
            sb.append(next, skip, next.length)
        }
        return sb.toString()
    }

    /**
     * 用编辑后的文本**替换**某文档的全部内容（重新分块入库，doc 行与 id 不变）。
     *
     * ⚠️ 只重写**知识库里的这份副本**：导入时的原始 Uri 没有落盘，改不回源文件。
     * 重写后 charset 记为 UTF-8 —— 内容是 UI 传进来的 String，与源文件编码已无关。
     *
     * 整个重写包在一个事务里：中途失败（含空文本）回滚，**旧内容不会被删掉半截**。
     *
     * @return 是否成功（空文本直接判失败，语义上"把文档改空"应由删除来解释）
     */
    fun updateText(context: Context, docId: Long, text: String): Boolean {
        val body = text.trim()
        if (body.isEmpty()) return false
        val database = db(context)
        var ok = false
        database.beginTransaction()
        try {
            database.delete("chunks", "doc_id=?", arrayOf(docId.toString()))
            val chunker = Chunker()
            chunker.feed(body)
            var idx = 0
            while (true) {
                val chunk = chunker.nextChunk(eof = true) ?: break
                insertChunk(database, docId, idx++, chunk)
            }
            if (idx == 0) throw IllegalStateException("empty text after edit")
            database.update(
                "docs",
                ContentValues().apply {
                    put("size", body.toByteArray(Charsets.UTF_8).size)
                    put("charset", "UTF-8")
                },
                "id=?", arrayOf(docId.toString()),
            )
            database.setTransactionSuccessful()
            ok = true
        } catch (e: Exception) {
            Log.e(TAG, "updateText failed: doc=$docId", e)
        } finally {
            database.endTransaction()
        }
        if (ok) Log.i(TAG, "updateText: doc=$docId -> ${body.length} chars")
        return ok
    }

    /**
     * 知识库里的文档名（最多 [limit] 份）。
     *
     * 给「检索没命中」时的可操作提示用：与其只说"没找到"，不如把库里有哪些文档告诉模型，
     * 它就能换成文档里更可能出现的关键词再试一次。
     */
    fun docNames(context: Context, limit: Int = 12): List<String> =
        listDocs(context).take(limit).map { it.name }

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
    // 语义向量索引（v3）
    // ═══════════════════════════════════════════════════

    /** 向量索引状态：总块数 / 已嵌入块数 / 当前索引所用模型 */
    fun vectorStatus(context: Context): KbVectorStatus {
        val database = db(context)
        var total = 0
        var embedded = 0
        database.rawQuery("SELECT COUNT(*), COUNT(embedding) FROM chunks", null).use { c ->
            if (c.moveToFirst()) {
                total = c.getInt(0)
                embedded = c.getInt(1)
            }
        }
        val model = database.rawQuery(
            "SELECT embedding_model FROM chunks WHERE embedding IS NOT NULL LIMIT 1", null,
        ).use { c -> if (c.moveToFirst()) c.getString(0) ?: "" else "" }
        return KbVectorStatus(total, embedded, model)
    }

    /** 清空全部向量（关闭语义检索 / 换模型重建前调用）。正文块一行不动。 */
    fun clearEmbeddings(context: Context) {
        val database = db(context)
        database.beginTransaction()
        try {
            database.execSQL("UPDATE chunks SET embedding = NULL, embedding_model = NULL")
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
        Log.i(TAG, "clearEmbeddings: all chunk vectors removed (text kept)")
    }

    /**
     * 增量建立/重建向量索引（**阻塞，须在 IO 线程调用**）。
     *
     * - [reembedAll] = false：只嵌入 `embedding IS NULL` 的块（新导入文档/中断续建）；
     * - true：先清空再全量重建（换模型/换维度必须走这条，旧维度向量会被检索跳过）。
     *
     * 一批 [EmbeddingClient.BATCH_SIZE] 块一次网络请求、一个小事务落盘，
     * 中途失败只丢当前批，下次调用从未嵌入块继续。[progress] 回传（已完成, 总数）。
     *
     * @return 本次新嵌入的块数
     */
    fun backfillEmbeddings(
        context: Context,
        baseUrl: String,
        apiKey: String,
        model: String,
        reembedAll: Boolean = false,
        progress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Int {
        require(model.isNotBlank()) { "embedding model is blank" }
        val database = db(context)
        if (reembedAll) clearEmbeddings(context)

        val total = database.rawQuery("SELECT COUNT(*) FROM chunks", null).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }
        var done = 0
        while (true) {
            // 取一批待嵌入块（id 升序，天然可续）
            data class Pending(val id: Long, val text: String)
            val pending = ArrayList<Pending>(EmbeddingClient.BATCH_SIZE)
            database.rawQuery(
                "SELECT id, text FROM chunks WHERE embedding IS NULL " +
                    "ORDER BY id LIMIT ${EmbeddingClient.BATCH_SIZE}",
                null,
            ).use { c ->
                while (c.moveToNext()) pending.add(Pending(c.getLong(0), c.getString(1)))
            }
            if (pending.isEmpty()) break

            val batch = EmbeddingClient.embed(
                baseUrl, apiKey, model, pending.map { it.text },
            )
            if (batch.vectors.size != pending.size) {
                throw IllegalStateException(
                    "embedding count mismatch: requested ${pending.size}, got ${batch.vectors.size}",
                )
            }
            val resolvedModel = batch.model.ifBlank { model }
            database.beginTransaction()
            try {
                pending.forEachIndexed { i, p ->
                    database.update(
                        "chunks",
                        ContentValues().apply {
                            put("embedding", encodeVector(batch.vectors[i]))
                            put("embedding_model", resolvedModel)
                        },
                        "id=?", arrayOf(p.id.toString()),
                    )
                }
                database.setTransactionSuccessful()
            } finally {
                database.endTransaction()
            }
            done += pending.size
            progress(done, total)
        }
        Log.i(TAG, "backfillEmbeddings: $done/$total chunks embedded with $model")
        return done
    }

    /**
     * 向量 ⇄ SQLite BLOB 编解码（little-endian float32，无头部 ——
     * 维度由字节长度推导：`blob.size / 4`；换模型维度不一致的旧向量在检索时跳过）。
     * internal 供单测钉住往返一致性。
     */
    internal fun encodeVector(v: FloatArray): ByteArray =
        ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN).apply {
            v.forEach { putFloat(it) }
        }.array()

    internal fun decodeVector(b: ByteArray): FloatArray {
        val fb = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(b.size / 4) { fb.float }
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
        // ⚠️ 计数必须**大小写不敏感**：df 与候选块都来自 SQLite 的 LIKE（对 ASCII 不区分大小写），
        //    若这里用区分大小写的 indexOf，英文查询就会出现"候选块查得到、得分却全是 0"
        //    ⇒ 最终 0 命中，用户看到的是"文档里明明写着却说没有"（如查 "HOW TO CONNECT"、
        //    文档里是 "How to connect"）。两段评分口径必须一致。
        val lower = text.lowercase()
        for (token in tokens) {
            val dfv = df[token] ?: continue
            if (dfv <= 0) continue
            val t = token.lowercase()
            if (t.isEmpty()) continue // 空 token 会让下面 indexOf 原地踏步成死循环
            val idf = ln(1.0 + (totalDocs - dfv + 0.5) / (dfv + 0.5))
            var tf = 0
            var idx = lower.indexOf(t)
            while (idx >= 0) {
                tf++
                idx = lower.indexOf(t, idx + t.length)
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
                "imported_at INTEGER NOT NULL, " +
                "charset TEXT NOT NULL DEFAULT '')",
        )
        db.execSQL(
            "CREATE TABLE chunks (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "doc_id INTEGER NOT NULL, " +
                "idx INTEGER NOT NULL, " +
                "text TEXT NOT NULL, " +
                // v3：语义向量（float32 little-endian BLOB）+ 该向量所用模型名
                "embedding BLOB, " +
                "embedding_model TEXT)",
        )
        db.execSQL("CREATE INDEX idx_chunks_doc ON chunks(doc_id)")
    }

    /**
     * 增量迁移。
     *
     * ⚠️ **绝不 drop 重建**：库里装的是用户自己导入的资料，任何一次 DB_VERSION 提升都会
     * 静默清空它 —— 属于不可恢复的数据丢失，而且用户完全不会预期"升级 App 要重新导入资料"。
     * （改造前这里正是 `DROP TABLE chunks/docs` + `onCreate`，等于埋了一颗升级即清库的雷。）
     * 新增列一律用 `ALTER TABLE ... ADD COLUMN`，旧数据原样保留。
     */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            // 理论上只会在 v1 库上执行一次；重复执行会抛 duplicate column，吞掉即可（幂等）
            runCatching {
                db.execSQL("ALTER TABLE docs ADD COLUMN charset TEXT NOT NULL DEFAULT ''")
            }.onFailure { Log.w(TAG, "onUpgrade: add charset column skipped: ${it.message}") }
        }
        if (oldVersion < 3) {
            // v3：语义向量列。nullable —— 旧块导入时没有向量，等用户开启语义检索后增量回填，
            // 迁移本身不发任何网络请求（不能在 onUpgrade 里阻塞用户启动）
            runCatching {
                db.execSQL("ALTER TABLE chunks ADD COLUMN embedding BLOB")
            }.onFailure { Log.w(TAG, "onUpgrade: add embedding column skipped: ${it.message}") }
            runCatching {
                db.execSQL("ALTER TABLE chunks ADD COLUMN embedding_model TEXT")
            }.onFailure { Log.w(TAG, "onUpgrade: add embedding_model column skipped: ${it.message}") }
        }
    }
}
