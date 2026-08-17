package com.rokidlab.phone.ai

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.util.Log
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper

/** 知识库文档信息 */
data class KbDocInfo(
    val id: Long,
    val name: String,
    val size: Int,
    val importedAt: Long,
)

/**
 * 本地知识库：文档导入（txt/pdf）→ 分块 → SQLite 存储 → 关键词检索。
 *
 * 用于「拍照问 AI」的 RAG 场景：
 *   眼镜拍照 → 本地 OCR 得到题目文本 → 知识库检索相关知识块 → 注入 DeepSeek 生成答案。
 */
object KnowledgeBase {
    private const val TAG = "KnowledgeBase"
    const val DB_NAME = "knowledge_base.db"
    const val DB_VERSION = 1
    private const val CHUNK_SIZE = 500

    private var helper: KbDbHelper? = null

    private fun db(context: Context): SQLiteDatabase =
        helper?.writableDatabase
            ?: KbDbHelper(context.applicationContext).also { helper = it }.writableDatabase

    // ═══════════════════════════════════════════════════
    // 导入
    // ═══════════════════════════════════════════════════

    /** 从 Uri 导入文档（.txt 直接读文本，.pdf 用 pdfbox 解析），返回文档信息 */
    fun importUri(context: Context, uri: Uri, displayName: String? = null): KbDocInfo? {
        return try {
            val name = displayName ?: uri.lastPathSegment ?: "doc_${System.currentTimeMillis()}"
            val text = if (name.endsWith(".pdf", ignoreCase = true)) {
                readPdf(context, uri)
            } else {
                readText(context, uri)
            }
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

    /** 用查询文本检索最相关的知识块（topK 个），按相关度降序 */
    fun search(context: Context, query: String, topK: Int = 3): List<String> {
        val tokens = tokenize(query)
        if (tokens.isEmpty()) return emptyList()
        val database = db(context)
        val scored = mutableListOf<Pair<Double, String>>()
        database.rawQuery("SELECT text FROM chunks", null).use { c ->
            while (c.moveToNext()) {
                val text = c.getString(0)
                val score = scoreText(text, tokens)
                if (score > 0) scored.add(score to text)
            }
        }
        scored.sortByDescending { it.first }
        return scored.take(topK).map { it.second }
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
        database.delete("chunks", "doc_id=?", arrayOf(docId.toString()))
        database.delete("docs", "id=?", arrayOf(docId.toString()))
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

    /** 块得分：每个命中的关键词贡献 1 + 出现次数 */
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

    // ═══════════════════════════════════════════════════
    // 文件解析
    // ═══════════════════════════════════════════════════

    private fun readText(context: Context, uri: Uri): String {
        context.contentResolver.openInputStream(uri)?.use { input ->
            return input.bufferedReader(Charsets.UTF_8).use { it.readText() }
        }
        return ""
    }

    private fun readPdf(context: Context, uri: Uri): String {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return ""
        PDFBoxResourceLoader.init(context.applicationContext)
        PDDocument.load(bytes).use { doc ->
            val stripper = PDFTextStripper()
            return stripper.getText(doc)
        }
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
