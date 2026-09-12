package com.rokidlab.phone.ai

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * AI Agent 长期记忆管理器（跨会话持久，SQLite 存储 + 检索式注入）。
 *
 * 与 [AgentSessionManager]（短期会话内多轮记忆，超时自动清空）互补：
 * 短期记忆管「本轮对话上下文」，长期记忆管「跨会话记住用户的事实与偏好」。
 *
 * 设计（参考 ChatGPT-like memory 思路，按眼镜语音场景简化）：
 *   - 写入：注册 memory 工具给 AI，AI 在用户表达持久偏好/个人背景/习惯时自主调用
 *     `manage_memory`（action=create/delete/list/clear），不依赖用户手动整理。
 *   - 读取：**检索式注入**——按当前提问与记忆的相关性评分（中文 2-gram + ASCII 词元重叠）
 *     取 top-K 注入 system prompt，无 query 时回退「最近的 K 条」；条数多时不再全量灌入，
 *     省 token 且降低无关记忆对回答的干扰。注入的编号与 items() 全量列表一致，
 *     保证 AI delete-by-index 语义稳定。
 *   - 存储：SQLite（无 Room 依赖），带时间戳；容量上限（FIFO 淘汰）+ 90 天自动过期。
 *     旧版 SharedPreferences JSON 数组在首次打开时自动迁移。
 *
 * 开关独立于短期会话记忆，持久化在 SharedPreferences。
 */
object LongTermMemoryManager {
    private const val TAG = "LongTermMemory"
    private const val PREFS = "long_term_memory_prefs"
    private const val KEY_ENABLED = "long_term_memory_enabled"
    private const val KEY_ITEMS = "long_term_memory_items" // 旧版存储键（迁移后清除）

    /** 记忆工具名（OpenAI function calling） */
    const val TOOL_NAME = "manage_memory"

    /** 记忆条数上限（检索式注入后可支撑更大容量，超出按 FIFO 淘汰最旧） */
    private const val MAX_ITEMS = 200

    /** 单条记忆字符上限（超出截断） */
    private const val MAX_ITEM_CHARS = 300

    /** 记忆有效期（天）：过期条目在读取时惰性清理（用户偏好/事实很少超过此跨度仍有效） */
    private const val EXPIRE_DAYS = 90

    /** 注入 system prompt 的记忆条数上限（检索命中按分排序取前 K） */
    private const val INJECT_TOP_K = 12

    /**
     * 写锁：AI 工具循环会并发执行多个 toolCalls（如 delete+create 同时到达），
     * 若多个 manage_memory 并发做「读-改-写」，后写会覆盖先写导致记忆丢失。
     * 所有 mutator 必须串行且基于最新数据操作。
     */
    private val lock = Any()

    @Volatile
    private var cacheEnabled: Boolean? = null

    @Volatile
    private var dbHelper: MemoryDb? = null

    // ──────────────────────────────────────────────
    //  开关（SharedPreferences，与旧版兼容）
    // ──────────────────────────────────────────────

    /** 长期记忆总开关 */
    fun isEnabled(context: Context): Boolean {
        val cached = cacheEnabled
        if (cached != null) return cached
        return prefs(context).getBoolean(KEY_ENABLED, true).also { cacheEnabled = it }
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        cacheEnabled = enabled
        Log.i(TAG, "set enabled=$enabled")
    }

    // ──────────────────────────────────────────────
    //  存储层（SQLite）
    // ──────────────────────────────────────────────

    private class MemoryDb(context: Context) : SQLiteOpenHelper(
        context.applicationContext, "long_term_memory.db", null, 1,
    ) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS memories(" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "content TEXT UNIQUE NOT NULL," +
                    "created_at INTEGER NOT NULL)",
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // v1 起步，暂无升级路径
        }
    }

    private fun db(context: Context): SQLiteDatabase {
        if (dbHelper == null) {
            synchronized(lock) {
                if (dbHelper == null) {
                    dbHelper = MemoryDb(context)
                    migrateFromPrefs(context, dbHelper!!)
                }
            }
        }
        return dbHelper!!.writableDatabase
    }

    /** 旧版 SharedPreferences JSON 数组 → SQLite 一次性迁移（保持原顺序） */
    private fun migrateFromPrefs(context: Context, helper: MemoryDb) {
        try {
            val prefs = prefs(context)
            val raw = prefs.getString(KEY_ITEMS, null) ?: return
            val arr = JSONArray(raw)
            if (arr.length() == 0) {
                prefs.edit().remove(KEY_ITEMS).apply()
                return
            }
            val d = helper.writableDatabase
            d.beginTransaction()
            try {
                (0 until arr.length())
                    .map { arr.optString(it) }
                    .filter { it.isNotBlank() }
                    .forEach { content ->
                        d.execSQL(
                            "INSERT OR IGNORE INTO memories(content, created_at) VALUES(?, ?)",
                            arrayOf(content, System.currentTimeMillis()),
                        )
                    }
                d.setTransactionSuccessful()
            } finally {
                d.endTransaction()
            }
            prefs.edit().remove(KEY_ITEMS).apply()
            Log.i(TAG, "migrated ${arr.length()} legacy memories to SQLite")
        } catch (e: Exception) {
            Log.w(TAG, "migrateFromPrefs failed (keep legacy data): ${e.message}")
        }
    }

    /** 读取全部记忆（按 id 升序 = 写入顺序；顺带惰性清理过期条目） */
    fun items(context: Context): List<String> {
        val d = db(context)
        pruneExpired(d)
        return try {
            d.rawQuery("SELECT content FROM memories ORDER BY id ASC", null).use { c ->
                ArrayList<String>(c.count).also {
                    while (c.moveToNext()) it.add(c.getString(0))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "items read failed: ${e.message}")
            emptyList()
        }
    }

    /** 记忆条数 */
    fun count(context: Context): Int = items(context).size

    /** 新增一条记忆：与已有完全相同则忽略（幂等），成功返回新条数 */
    fun add(context: Context, content: String): Int = synchronized(lock) {
        val text = content.trim().take(MAX_ITEM_CHARS)
        val d = db(context)
        if (text.isEmpty()) return@synchronized countRows(d)
        try {
            d.execSQL(
                "INSERT OR IGNORE INTO memories(content, created_at) VALUES(?, ?)",
                arrayOf(text, System.currentTimeMillis()),
            )
            enforceCap(d)
            countRows(d)
        } catch (e: Exception) {
            Log.w(TAG, "add failed: ${e.message}")
            countRows(d)
        }
    }

    /**
     * 删除记忆：入参可为记忆编号（1 起，对应 items() 顺序）或内容片段（包含匹配）。
     * @return 删除成功返回 true；未找到返回 false
     */
    fun remove(context: Context, query: String): Boolean = synchronized(lock) {
        val q = query.trim()
        if (q.isEmpty()) return@synchronized false
        val d = db(context)
        try {
            val idx = q.toIntOrNull()
            if (idx != null && idx >= 1) {
                // 按编号：先按 id 升序取第 idx 条的主键再删
                d.rawQuery("SELECT id FROM memories ORDER BY id ASC LIMIT 1 OFFSET ${idx - 1}", null).use { c ->
                    if (c.moveToFirst()) {
                        d.delete("memories", "id=?", arrayOf(c.getLong(0).toString()))
                        return@synchronized true
                    }
                }
                return@synchronized false
            }
            // 按内容片段：包含匹配（双向）。转义 LIKE 通配符，避免误删/漏删(B4)
            val escaped = q.replace("!", "!!").replace("%", "!%").replace("_", "!_")
            val removed = d.delete("memories", "content LIKE ? ESCAPE '!'", arrayOf("%$escaped%"))
            if (removed == 0) {
                // 兜底：q 是某条记忆的子串以外的方向（记忆含 q 全文）——忽略，
                // 旧版双向 contains 语义在 SQL 层近似为 LIKE
                return@synchronized false
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "remove failed: ${e.message}")
            false
        }
    }

    /** 清空全部记忆 */
    fun clear(context: Context) {
        synchronized(lock) {
            try {
                db(context).delete("memories", null, null)
            } catch (e: Exception) {
                Log.w(TAG, "clear failed: ${e.message}")
            }
        }
        Log.i(TAG, "cleared")
    }

    // ──────────────────────────────────────────────
    //  检索式注入
    // ──────────────────────────────────────────────

    /**
     * 注入用文本：按 [query] 与记忆的相关性评分取 top-K 拼成 <memories> 段。
     * query 为空时回退「最近 K 条」（按写入时间倒序）。
     * 编号与 [items] 全量列表一致（delete-by-index 语义稳定）；无记忆返回 null。
     *
     * 评分：query 与记忆的词元重叠数（中文 2-gram + ASCII 小写词），
     * 平分时新记忆优先（时间倒序）——记忆总量小（≤200），线性打分足够。
     */
    fun memoriesContext(context: Context, query: String? = null): String? {
        val all = items(context)
        if (all.isEmpty()) return null

        // 回退集：最近 INJECT_TOP_K 条（保持全量列表编号）
        val recent = all.takeLast(INJECT_TOP_K)
        val base = all.size - recent.size
        val fallback = recent.mapIndexed { i, s -> base + i to s }

        val q = query?.trim().orEmpty()
        val selected: List<Pair<Int, String>> = if (q.isEmpty()) {
            fallback
        } else {
            val qTokens = tokenize(q)
            val hits = all.mapIndexed { idx, mem -> idx to tokenize(mem).count { it in qTokens } }
                .filter { it.second > 0 }
                .sortedWith(compareByDescending<Pair<Int, Int>> { it.second }.thenByDescending { it.first })
                .take(INJECT_TOP_K)
                .sortedBy { it.first }
                .map { it.first to all[it.first] }
            if (hits.isEmpty()) fallback else hits
        }
        if (selected.isEmpty()) return null
        return buildString {
            append("以下是与当前话题可能相关的长期记忆（跨会话积累，可据此个性化回答）：\n")
            selected.forEach { (i, s) -> append("${i + 1}. $s\n") }
        }.trimEnd()
    }

    /** 中文 2-gram + ASCII 小写词的轻量分词（与 KnowledgeBase 2-gram 思路一致） */
    private fun tokenize(text: String): Set<String> {
        val tokens = HashSet<String>()
        val ascii = StringBuilder()
        val cjk = StringBuilder()
        fun flushAscii() {
            val w = ascii.toString().lowercase()
            if (w.length >= 2) tokens.add(w)
            ascii.setLength(0)
        }
        fun flushCjk() {
            val s = cjk.toString()
            cjk.setLength(0)
            if (s.length == 1) tokens.add(s)
            for (i in 0 until s.length - 1) tokens.add(s.substring(i, i + 2))
        }
        text.forEach { ch ->
            when {
                ch.code in 0x4E00..0x9FFF -> {
                    if (ascii.isNotEmpty()) flushAscii()
                    cjk.append(ch)
                }
                ch.isLetterOrDigit() -> {
                    if (cjk.isNotEmpty()) flushCjk()
                    ascii.append(ch)
                }
                else -> {
                    if (ascii.isNotEmpty()) flushAscii()
                    if (cjk.isNotEmpty()) flushCjk()
                }
            }
        }
        if (ascii.isNotEmpty()) flushAscii()
        if (cjk.isNotEmpty()) flushCjk()
        return tokens
    }

    // ──────────────────────────────────────────────
    //  维护
    // ──────────────────────────────────────────────

    private fun countRows(d: SQLiteDatabase): Int =
        d.rawQuery("SELECT COUNT(*) FROM memories", null).use { c ->
            c.moveToFirst()
            c.getInt(0)
        }

    /** FIFO 淘汰超出上限的最旧记忆 */
    private fun enforceCap(d: SQLiteDatabase) {
        val n = countRows(d)
        if (n > MAX_ITEMS) {
            d.execSQL("DELETE FROM memories WHERE id IN (SELECT id FROM memories ORDER BY id ASC LIMIT ${n - MAX_ITEMS})")
        }
    }

    /** 惰性清理过期记忆（读取路径顺带执行，代价极小） */
    private fun pruneExpired(d: SQLiteDatabase) {
        try {
            val cutoff = System.currentTimeMillis() - EXPIRE_DAYS * 24L * 3600 * 1000
            d.delete("memories", "created_at < ?", arrayOf(cutoff.toString()))
        } catch (_: Exception) {
        }
    }

    // ──────────────────────────────────────────────
    //  工具 Schema / 执行（与旧版接口一致）
    // ──────────────────────────────────────────────

    /**
     * 记忆工具的 JSON Schema（注册给 OpenAI 兼容协议的 tools 参数）。
     * description 里明确约束「写什么/不写什么」，防止 AI 误存敏感信息。
     */
    fun schema(): JSONObject {
        return JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", TOOL_NAME)
                put("description",
                    "管理长期记忆：跨会话记住/删除关于用户本人的事实与偏好。" +
                        "当用户表达持久性信息时应调用，例如「我喜欢周杰伦」「我是学生」「我每天 9 点上班」" +
                        "「叫我小乐」等（会一直记住，下次对话仍生效）；用户说「忘掉/删除…」时删除。" +
                        "严禁记忆密码、账号、地址、支付等敏感信息。")
                put("parameters", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("action", JSONObject().apply {
                            put("type", "string")
                            put("enum", JSONArray(listOf("create", "delete", "list", "clear")))
                            put("description", "操作类型：create=新增记忆；delete=删除（content 传编号如 1，或内容片段）；list=列出全部；clear=清空")
                        })
                        put("content", JSONObject().apply {
                            put("type", "string")
                            put("description", "create 时是要记住的内容；delete 时是要删除的编号或内容片段；list/clear 时省略")
                        })
                    })
                    put("required", JSONArray(listOf("action")))
                })
            })
        }
    }

    /** 执行记忆工具，返回给 AI 的结果文本 */
    fun execute(context: Context, arguments: String): String {
        val args = try {
            JSONObject(arguments)
        } catch (_: Exception) {
            return "工具参数解析失败，请传入合法 JSON"
        }
        return when (args.optString("action")) {
            "create" -> {
                val content = args.optString("content").trim()
                if (content.isEmpty()) return "create 需要提供 content 内容"
                val total = add(context, content)
                "已记住：$content（共 $total 条长期记忆）"
            }

            "delete" -> {
                val q = args.optString("content").trim()
                if (q.isEmpty()) return "delete 需要提供编号或内容片段"
                if (remove(context, q)) "已删除相关长期记忆" else "没有找到可删除的记忆条目"
            }

            "list" -> {
                val list = items(context)
                if (list.isEmpty()) "暂无长期记忆"
                else list.mapIndexed { i, s -> "[${i + 1}] $s" }.joinToString("\n")
            }

            "clear" -> {
                clear(context)
                "已清空全部长期记忆"
            }

            else -> "未知操作，可选：create / delete / list / clear"
        }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
