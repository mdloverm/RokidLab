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
 * **两类记忆，按 [kind] 分开注入（2026-09-22 加）**：
 *   - [KIND_FACT]：用户的事实与偏好（原有语义，默认值）。
 *   - [KIND_LESSON]：**Agent 自己的经验教训** —— 在这台设备上踩过的坑、试出来的有效做法。
 *     这是「同一个错误不要犯第二次」的落点：教训跨会话留存，下轮遇到同类任务先被注入提示词。
 *     两者共表但**分别取 top-K 注入**，否则教训多了会把用户偏好挤出注入窗口。
 */
object LongTermMemoryManager {
    private const val TAG = "LongTermMemory"
    private const val PREFS = "long_term_memory_prefs"
    private const val KEY_ENABLED = "long_term_memory_enabled"
    private const val KEY_ITEMS = "long_term_memory_items" // 旧版存储键（迁移后清除）

    /** 记忆工具名（OpenAI function calling） */
    const val TOOL_NAME = "manage_memory"

    /** 用户事实/偏好（默认种类） */
    const val KIND_FACT = "fact"

    /**
     * 经验教训：Agent 在这台设备上踩过的坑。
     *
     * 与用户事实**分开**的理由见类注释；也因为它可以（且鼓励）由 AI 自动写入，
     * 而用户事实不该被自动写。
     */
    const val KIND_LESSON = "lesson"

    /** 记忆条数上限（检索式注入后可支撑更大容量，超出按 FIFO 淘汰最旧） */
    private const val MAX_ITEMS = 200

    /**
     * 经验教训条数上限（独立于 [MAX_ITEMS]）。
     *
     * 给教训单独设上限，是为了让"自动积累的经验"永远挤不掉用户亲口说过的事实：
     * 教训再多也只在自己这 60 条里 FIFO，用户偏好那 200 条不受影响。
     */
    private const val MAX_LESSONS = 60

    /** 单条记忆字符上限（超出截断） */
    private const val MAX_ITEM_CHARS = 300

    /** 记忆有效期（天）：过期条目在读取时惰性清理（用户偏好/事实很少超过此跨度仍有效） */
    private const val EXPIRE_DAYS = 90

    /** 注入 system prompt 的记忆条数上限（检索命中按分排序取前 K） */
    private const val INJECT_TOP_K = 12

    /** 注入的**经验教训**条数上限（比事实更少：教训是"注意事项"，不需要一次看全） */
    private const val LESSON_INJECT_TOP_K = 6

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
        context.applicationContext, "long_term_memory.db", null, 2,
    ) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS memories(" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "content TEXT UNIQUE NOT NULL," +
                    "created_at INTEGER NOT NULL," +
                    "kind TEXT NOT NULL DEFAULT 'fact')",
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // v1 → v2：加 kind 区分「用户事实」与「经验教训」。老数据全部落在默认值 fact 上，
            // 即"加这一列之前记的都是用户事实"——语义正好，不需要数据回填。
            if (oldVersion < 2) {
                runCatching { db.execSQL("ALTER TABLE memories ADD COLUMN kind TEXT NOT NULL DEFAULT 'fact'") }
                    .onFailure { Log.w(TAG, "add kind column failed: ${it.message}") }
            }
        }
    }

    private fun db(context: Context): SQLiteDatabase {
        if (dbHelper == null) {
            synchronized(lock) {
                if (dbHelper == null) {
                    val helper = MemoryDb(context)
                    dbHelper = helper
                    migrateFromPrefs(context, helper)
                }
            }
        }
        return requireNotNull(dbHelper) { "memory db helper missing" }.writableDatabase
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
        return rows(d).map { it.content }
    }

    /**
     * **用户事实**及其全局编号（编号 1 起，与 [items] 的顺序一致）。
     *
     * 设置页的记忆管理列表用它而不是 [items]：教训是 AI 自己攒的（"工具 X 失败过"这类），
     * 混进"乐奇替用户记住的事"里既误导用户、又让这份列表被自动内容污染；
     * 但编辑/删除仍要按**全局编号**定位，所以这里把编号一并带出来。
     */
    fun factItems(context: Context): List<Pair<Int, String>> {
        val d = db(context)
        pruneExpired(d)
        return rows(d).withIndex()
            .filter { it.value.kind == KIND_FACT }
            .map { (it.index + 1) to it.value.content }
    }

    /** 记忆行（顺序 = id 升序，即 [items] 的顺序；type 用于按种类分流注入） */
    private class Row(val kind: String, val content: String)

    /**
     * 读取记忆行。
     *
     * ⚠️ **不要给 [kind] 过滤加上"顺便改变顺序"**：全局顺序是全量列表的编号基准，
     * AI 的 `manage_memory(delete, content="3")` 就是按它定位的（见 [items] 的 KDoc）。
     * 过滤只用于"取哪几行来注入"，编号仍取该行在全量列表里的位置。
     */
    private fun rows(d: SQLiteDatabase, kind: String? = null): List<Row> {
        val sql = if (kind == null) {
            "SELECT kind, content FROM memories ORDER BY id ASC"
        } else {
            "SELECT kind, content FROM memories WHERE kind = ? ORDER BY id ASC"
        }
        return try {
            d.rawQuery(sql, kind?.let { arrayOf(it) }).use { c ->
                ArrayList<Row>(c.count).also {
                    while (c.moveToNext()) it.add(Row(c.getString(0) ?: KIND_FACT, c.getString(1)))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "rows read failed: ${e.message}")
            emptyList()
        }
    }

    /** 记忆条数 */
    fun count(context: Context): Int = items(context).size

    /** 经验教训条数（自检/展示用：让用户看得见"它自己攒了多少条教训"） */
    fun lessonCount(context: Context): Int = itemsOfKind(context, KIND_LESSON).size

    /** 只取某一 [kind] 的记忆正文（按 id 升序） */
    private fun itemsOfKind(context: Context, kind: String): List<String> {
        val d = db(context)
        pruneExpired(d)
        return rows(d, kind).map { it.content }
    }

    /** 新增一条记忆：与已有完全相同则忽略（幂等），成功返回新条数 */
    fun add(context: Context, content: String): Int = add(context, content, KIND_FACT)

    /**
     * 新增一条记忆（指定种类）。
     *
     * 幂等靠 `content` 的 UNIQUE 约束：**同一条教训重复出现不会堆积成多条**，
     * 这正是自动写入敢往这里丢内容的前提（否则同样的错误记十遍会挤爆 60 条上限）。
     *
     * @param kind [KIND_FACT] / [KIND_LESSON]
     */
    fun add(context: Context, content: String, kind: String): Int = synchronized(lock) {
        val text = content.trim().take(MAX_ITEM_CHARS)
        val d = db(context)
        val k = if (kind == KIND_LESSON) KIND_LESSON else KIND_FACT
        if (text.isEmpty()) return@synchronized countRows(d)
        try {
            d.execSQL(
                "INSERT OR IGNORE INTO memories(content, created_at, kind) VALUES(?, ?, ?)",
                arrayOf(text, System.currentTimeMillis(), k),
            )
            enforceCap(d)
            countRows(d)
        } catch (e: Exception) {
            Log.w(TAG, "add failed: ${e.message}")
            countRows(d)
        }
    }

    /**
     * 记一条**经验教训**（[KIND_LESSON]）：AI 在这台设备上踩过的坑、或验证有效的做法。
     *
     * 与用户事实走同一个库与同一套检索注入，只是分成两个注入窗口。
     */
    fun addLesson(context: Context, content: String) {
        if (content.isBlank()) return
        add(context, content, KIND_LESSON)
    }

    /**
     * 记一条经验教训，但**按 [dedupKey] 去重**：库里已有含该 key 的教训时直接跳过。
     *
     * ★ 为什么需要它：自动沉淀（工具执行失败后由代码写入）的内容里必然带着**每次都不一样的
     *   错误串**，而 UNIQUE 约束只认整条正文 —— 同一个工具失败十次就是十条近似重复的教训，
     *   60 条上限很快被这类"同一个坑"塞满，真正有价值的经验反而被 FIFO 挤掉。
     *   按 key（如工具名）去重后，**同一个坑只留第一条**，这才是"记到规则里"该有的样子。
     *
     * @param dedupKey 去重标记（按包含匹配；建议用工具名这类稳定标识）
     * @return 真的写入了返回 true；已存在同 key 教训或写入失败返回 false
     */
    fun addLessonOnce(context: Context, dedupKey: String, content: String): Boolean {
        val key = dedupKey.trim()
        if (key.isEmpty() || content.isBlank()) return false
        return synchronized(lock) {
            val d = db(context)
            val exists = try {
                d.rawQuery(
                    "SELECT 1 FROM memories WHERE kind = ? AND content LIKE ? ESCAPE '!' LIMIT 1",
                    arrayOf(KIND_LESSON, "%${escapeLike(key)}%"),
                ).use { it.moveToFirst() }
            } catch (e: Exception) {
                Log.w(TAG, "addLessonOnce probe failed: ${e.message}")
                false
            }
            if (exists) {
                false
            } else {
                add(context, content, KIND_LESSON)
                true
            }
        }
    }

    /** 转义 LIKE 通配符（`!` 为转义符，见 SQL 里的 `ESCAPE '!'`） */
    private fun escapeLike(s: String): String =
        s.replace("!", "!!").replace("%", "!%").replace("_", "!_")

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
            val escaped = escapeLike(q)
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

    /**
     * 按编号（1 起，与 [items] 的顺序一致）改掉一条记忆的正文。
     *
     * ★ 为什么必须有这个 API：用户是**唯一**能判断"这条记忆已经不对了"的人
     *   （"我早就不喜欢周杰伦了"），而在此之前他只能**删掉再让 AI 重新记一遍** ——
     *   而设置页里根本没有"重新说一遍"的入口。于是记忆一旦记错，用户唯一的救济手段是
     *   在对话里引导模型调用 `manage_memory`，那是隐私功能最不该有的体验。
     *
     * created_at 一并刷新：用户刚亲手确认过这条内容，90 天过期计时理应从这一刻重新算，
     * 否则改完一条两年没动过的旧记忆，它下次读取就被惰性清理掉了。
     *
     * @return 改到了返回 true；编号越界 / 内容为空 / 与另一条重复（`content` 有 UNIQUE 约束）返回 false
     */
    fun update(context: Context, index: Int, content: String): Boolean = synchronized(lock) {
        val text = content.trim().take(MAX_ITEM_CHARS)
        if (index < 1 || text.isEmpty()) return@synchronized false
        val d = db(context)
        try {
            d.rawQuery("SELECT id FROM memories ORDER BY id ASC LIMIT 1 OFFSET ${index - 1}", null).use { c ->
                if (!c.moveToFirst()) return@synchronized false
                d.execSQL(
                    "UPDATE memories SET content = ?, created_at = ? WHERE id = ?",
                    arrayOf<String>(text, System.currentTimeMillis().toString(), c.getLong(0).toString()),
                )
                true
            }
        } catch (e: Exception) {
            // 重复内容会命中 UNIQUE 约束抛异常 —— 视作"没改成"，由 UI 如实告知
            Log.w(TAG, "update failed: ${e.message}")
            false
        }
    }

    // ──────────────────────────────────────────────
    //  检索式注入
    // ──────────────────────────────────────────────

    /**
     * 注入用文本（**用户事实**）：按 [query] 与记忆的相关性评分取 top-K 拼成带编号的列表。
     * query 为空时回退「最近 K 条」（按写入时间倒序）；无事实记忆返回 null。
     *
     * 编号取该条在**全量列表** `items()` 里的位置（1 起），
     * 因此 AI 用 `manage_memory(delete, content="3")` 删除时不会错位 —— 教训混在列表里也不影响。
     */
    fun memoriesContext(context: Context, query: String? = null): String? {
        val d = db(context)
        pruneExpired(d)
        // 事实：带上全局位置作为编号
        val facts = rows(d).withIndex()
            .filter { it.value.kind == KIND_FACT }
            .map { it.index to it.value.content }
        val picked = pickRelevant(facts, query, INJECT_TOP_K) ?: return null
        return buildString {
            append("以下是与当前话题可能相关的长期记忆（跨会话积累，可据此个性化回答）：\n")
            picked.forEach { (i, s) -> append("${i + 1}. $s\n") }
        }.trimEnd()
    }

    /**
     * 注入用文本（**经验教训**）：AI 自己攒下的坑与做法，取 top-K。
     *
     * ⚠️ 这里刻意**不编号**（用 `·` 圆点）：编号在 [memoriesContext] 里是删除用的定位符，
     * 教训在自己窗口里另起一套编号只会诱导模型拿它去 `manage_memory(delete, "1")`
     * —— 那删掉的是第一条**用户事实**。要删教训就让模型传内容片段。
     */
    fun lessonsContext(context: Context, query: String? = null): String? {
        val d = db(context)
        pruneExpired(d)
        val lessons = rows(d, KIND_LESSON).mapIndexed { i, r -> i to r.content }
        val picked = pickRelevant(lessons, query, LESSON_INJECT_TOP_K) ?: return null
        return buildString {
            append("以下是你过去在这台设备上踩过的坑或验证过的做法（跨会话积累）。做同类事情时先照它来，不要重复试错：\n")
            picked.forEach { (_, s) -> append("· ").append(s).append('\n') }
        }.trimEnd()
    }

    /**
     * 相关性取 top-K（纯函数，可单测）：按 query 与内容的词元重叠数排序，
     * 平分时新条目优先（列表尾部），query 为空 / 全不命中时回退「最近 K 条」。
     *
     * @param items (编号, 正文) 列表；编号的语义由调用方定义（事实=全局位置，教训=占位）
     * @return 命中的 (编号, 正文)（保持原顺序）；入参为空返回 null
     */
    private fun pickRelevant(
        items: List<Pair<Int, String>>,
        query: String?,
        topK: Int,
    ): List<Pair<Int, String>>? {
        if (items.isEmpty()) return null
        val q = query?.trim().orEmpty()
        if (q.isEmpty()) return items.takeLast(topK)
        val qTokens = tokenize(q)
        val hits = items
            .map { (idx, s) -> Triple(idx, s, tokenize(s).count { it in qTokens }) }
            .filter { it.third > 0 }
            .sortedWith(compareByDescending<Triple<Int, String, Int>> { it.third }.thenByDescending { it.first })
            .take(topK)
            .sortedBy { it.first }
            .map { it.first to it.second }
        return hits.ifEmpty { items.takeLast(topK) }
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

    private fun countRows(d: SQLiteDatabase, kind: String? = null): Int {
        val sql = if (kind == null) "SELECT COUNT(*) FROM memories" else "SELECT COUNT(*) FROM memories WHERE kind = ?"
        return d.rawQuery(sql, kind?.let { arrayOf(it) }).use { c ->
            c.moveToFirst()
            c.getInt(0)
        }
    }

    /**
     * 两类记忆**各自** FIFO 淘汰超出上限的最旧条目。
     *
     * ⚠️ 两个上限必须在自己的 kind 内淘汰，不能合并成一个总数上限：
     * 教训是可以自动积累的，而事实只能由用户/AI 显式写入 —— 合起来算的话，
     * 攒够教训就会把用户亲口说过的事实挤掉，那是这里最不该丢的数据。
     */
    private fun enforceCap(d: SQLiteDatabase) {
        trimKind(d, KIND_FACT, MAX_ITEMS)
        trimKind(d, KIND_LESSON, MAX_LESSONS)
    }

    private fun trimKind(d: SQLiteDatabase, kind: String, max: Int) {
        val n = countRows(d, kind)
        if (n > max) {
            d.execSQL(
                "DELETE FROM memories WHERE id IN (" +
                    "SELECT id FROM memories WHERE kind = ? ORDER BY id ASC LIMIT ${n - max})",
                arrayOf(kind),
            )
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
     * description 里明确约束「写什么/不写什么」，防止 AI 误存敏感信息；
     * 同时把 `kind=lesson` 的用法写清楚 —— 这是**教训自动积累**的入口，
     * 不写明文模型不会自己想到用（它默认把 manage_memory 理解为"记用户的事"）。
     */
    fun schema(): JSONObject {
        return JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", TOOL_NAME)
                put("description",
                    "管理长期记忆（跨会话持久生效）：两种用途 —— " +
                        "①kind=fact（默认）记住关于**用户本人**的事实与偏好，" +
                        "如「我喜欢周杰伦」「我是学生」「我每天 9 点上班」「叫我小乐」，用户说「忘掉/删除…」时删除；" +
                        "②kind=lesson 记下**你自己**在这台设备上踩过的坑或试出来的有效做法（如「没装执行环境时 run_shell 会失败，要先让用户在设置里装」「某接口必须带 Content-Type 才能用」），" +
                        "下次遇到同类任务会先提示你，避免重复试错；同一坑只记一次。 " +
                        "严禁记忆密码、账号、地址、支付等敏感信息。")
                put("parameters", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("action", JSONObject().apply {
                            put("type", "string")
                            put("enum", JSONArray(listOf("create", "delete", "list", "clear")))
                            put("description", "操作类型：create=新增记忆；delete=删除（content 传编号如 1，或内容片段）；list=列出全部；clear=清空")
                        })
                        put("kind", JSONObject().apply {
                            put("type", "string")
                            put("enum", JSONArray(listOf(KIND_FACT, KIND_LESSON)))
                            put("description", "记忆种类：fact=用户的事实偏好（默认）；lesson=你自己的经验教训（踩过的坑/有效做法）")
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
        val kind = if (args.optString("kind") == KIND_LESSON) KIND_LESSON else KIND_FACT
        return when (args.optString("action")) {
            "create" -> {
                val content = args.optString("content").trim()
                if (content.isEmpty()) return "create 需要提供 content 内容"
                val total = add(context, content, kind)
                if (kind == KIND_LESSON) "已记下这条经验教训（共 $total 条记忆）"
                else "已记住：$content（共 $total 条长期记忆）"
            }

            "delete" -> {
                val q = args.optString("content").trim()
                if (q.isEmpty()) return "delete 需要提供编号或内容片段"
                if (remove(context, q)) "已删除相关长期记忆" else "没有找到可删除的记忆条目"
            }

            "list" -> {
                // 两类分开列：教训不进编号序列（编号是事实的删除定位符，见 lessonsContext 的 KDoc）
                val all = items(context)
                val lessons = itemsOfKind(context, KIND_LESSON).toSet()
                val facts = all.withIndex().filter { it.value !in lessons }
                val sb = StringBuilder()
                if (facts.isEmpty()) sb.append("暂无长期记忆")
                else facts.forEach { sb.append("[${it.index + 1}] ${it.value}\n") }
                if (lessons.isNotEmpty()) {
                    if (sb.isNotEmpty()) sb.append('\n')
                    sb.append("经验教训：\n")
                    lessons.forEach { sb.append("· ").append(it).append('\n') }
                }
                sb.toString().trimEnd()
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
