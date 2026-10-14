package com.rokidlab.phone.ai

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import com.rokidlab.phone.ai.embedding.EmbeddingClient
import com.rokidlab.phone.ai.embedding.EmbeddingSnapshot
import com.rokidlab.phone.ai.embedding.EmbeddingSettings
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * AI Agent 长期记忆管理器（跨会话持久，SQLite 存储 + 检索式注入）。
 *
 * 与 [AgentSessionManager]（短期会话内多轮记忆，超时自动清空）互补：
 * 短期记忆管「本轮对话上下文」，长期记忆管「跨会话记住用户的事实与偏好」。
 *
 * 设计（参考 ChatGPT-like memory 思路，按眼镜语音场景简化）：
 *   - 写入：注册 memory 工具给 AI，AI 在用户表达持久偏好/个人背景/习惯时自主调用
 *     `manage_memory`（action=create/delete/list/clear），不依赖用户手动整理。
 *   - 读取：**检索式注入**——按当前提问与记忆的相关性取 top-K 注入 system prompt，
 *     混合检索 = 词法（中文 2-gram + ASCII 词元重叠）+ 语义向量（RRF 名次融合，
 *     照 KnowledgeBase v3 模式；向量懒回填、任何故障自动回退纯词法），
 *     无 query 时回退「最近的 K 条」；条数多时不再全量灌入，
 *     省 token 且降低无关记忆对回答的干扰。注入的编号与 items() 全量列表一致，
 *     保证 AI delete-by-index 语义稳定。
 *   - 存储：SQLite（无 Room 依赖），带时间戳；容量上限 + **价值评分淘汰**（mem0 风格，
 *     见 [MemoryScoring]：重要性 + 新近度衰减 + 访问频率，替代旧的纯 FIFO）；
 *     90 天「没人碰」自动过期（锚点是最近一次被用到，常用记忆自然免过期）。
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

    /** 记忆条数上限（超出按 [MemoryScoring] 价值分淘汰最低者，同为最低分时按 FIFO） */
    private const val MAX_ITEMS = 200

    /**
     * 经验教训条数上限（独立于 [MAX_ITEMS]）。
     *
     * 给教训单独设上限，是为了让"自动积累的经验"永远挤不掉用户亲口说过的事实：
     * 教训再多也只在自己这 60 条里按价值分淘汰，用户偏好那 200 条不受影响。
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

    // ── 混合检索（语义向量 + 词法，RRF 融合；照 KnowledgeBase v3 模式）──
    /** RRF 常数：越大两路排名差异越平缓，60 是论文经验值（与 KnowledgeBase 一致） */
    private const val RRF_K = 60

    /** 融合时每路最多取多少候选（最终只输出 topK，池子大一点保证交叉覆盖） */
    private const val FUSION_POOL = 40

    /**
     * 查询嵌入的读超时。检索在对话 prompt 组装路径上**同步执行**，语义通道出了问题
     * 也不能把每轮对话拖住：向量已归一化、查询只有一句话，正常服务 <1s 返回，5s 足够宽裕，
     * 超时即整体回退纯词法路径。
     */
    private const val QUERY_EMBED_READ_TIMEOUT_MS = 5_000

    /**
     * 库版本。**提升前先确认 [MemoryDb.onUpgrade] 有对应的增量迁移**——记忆是用户/Agent
     * 跨会话积累的数据，升级清库属于不可恢复的数据丢失。
     * v3：memories 增加 embedding / embedding_model 列（语义向量检索）。
     * v4：memories 增加 importance / access_count / last_accessed 列（mem0 风格价值评分淘汰，
     * 见 [MemoryScoring]）——老数据全部落默认值（重要度 0.5 / 从未访问），语义正好。
     */
    internal const val DB_VERSION = 4

    /**
     * 写锁：AI 工具循环会并发执行多个 toolCalls（如 delete+create 同时到达），
     * 若多个 manage_memory 并发做「读-改-写」，后写会覆盖先写导致记忆丢失。
     * 所有 mutator 必须串行且基于最新数据操作。
     */
    private val lock = Any()

    /**
     * 懒回填单飞标志：同一时刻最多一个后台线程在补算向量。
     * 检索路径每轮都可能触发 [maybeBackfill]，没有单飞的话会为同一批缺口反复起线程。
     */
    private val backfillRunning = AtomicBoolean(false)

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
        context.applicationContext, "long_term_memory.db", null, DB_VERSION,
    ) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS memories(" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "content TEXT UNIQUE NOT NULL," +
                    "created_at INTEGER NOT NULL," +
                    "kind TEXT NOT NULL DEFAULT 'fact'," +
                    "importance REAL NOT NULL DEFAULT 0.5," +
                    "access_count INTEGER NOT NULL DEFAULT 0," +
                    "last_accessed INTEGER," +
                    "embedding BLOB," +
                    "embedding_model TEXT)",
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // v1 → v2：加 kind 区分「用户事实」与「经验教训」。老数据全部落在默认值 fact 上，
            // 即"加这一列之前记的都是用户事实"——语义正好，不需要数据回填。
            if (oldVersion < 2) {
                runCatching { db.execSQL("ALTER TABLE memories ADD COLUMN kind TEXT NOT NULL DEFAULT 'fact'") }
                    .onFailure { Log.w(TAG, "add kind column failed: ${it.message}") }
            }
            // v2 → v3：加语义向量列（照 KnowledgeBase v3 模式）。老数据两列为 NULL，
            // 检索时按「未嵌入」跳过、由 [maybeBackfill] 在后台懒补，绝不 drop 重建丢数据。
            if (oldVersion < 3) {
                runCatching { db.execSQL("ALTER TABLE memories ADD COLUMN embedding BLOB") }
                    .onFailure { Log.w(TAG, "add embedding column failed: ${it.message}") }
                runCatching { db.execSQL("ALTER TABLE memories ADD COLUMN embedding_model TEXT") }
                    .onFailure { Log.w(TAG, "add embedding_model column failed: ${it.message}") }
            }
            // v3 → v4：加价值评分三列（mem0 风格淘汰，见 [MemoryScoring]）。默认值即老数据语义：
            // 「重要度中性、从未被注入命中」—— 它们会按新近度参与排序，不会因为缺列被误杀。
            if (oldVersion < 4) {
                runCatching { db.execSQL("ALTER TABLE memories ADD COLUMN importance REAL NOT NULL DEFAULT 0.5") }
                    .onFailure { Log.w(TAG, "add importance column failed: ${it.message}") }
                runCatching { db.execSQL("ALTER TABLE memories ADD COLUMN access_count INTEGER NOT NULL DEFAULT 0") }
                    .onFailure { Log.w(TAG, "add access_count column failed: ${it.message}") }
                runCatching { db.execSQL("ALTER TABLE memories ADD COLUMN last_accessed INTEGER") }
                    .onFailure { Log.w(TAG, "add last_accessed column failed: ${it.message}") }
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
     * 检索用记忆行：在 [Row] 基础上多带主键与已存的语义向量。
     * [id] 用于注入命中后的「保鲜」回写（[touch]：计数 + 刷新时间锚点）；
     * [embedding] 为 null 表示尚未嵌入（等 [maybeBackfill] 懒补）；模型名不匹配当前配置
     * 视为过期向量，检索时跳过。
     */
    private class RetrievalRow(
        val id: Long,
        val kind: String,
        val content: String,
        val embedding: ByteArray?,
        val embeddingModel: String?,
    )

    /**
     * 读取带向量列的记忆行（仅检索路径用；[rows] 的轻量读取不带 BLOB）。
     *
     * ⚠️ 与 [rows] 同一条铁律：**必须 id 升序、不过滤改变顺序** —— 全局顺序是注入编号
     * 与 `manage_memory(delete)` 定位的基准。
     */
    private fun retrievalRows(d: SQLiteDatabase, kind: String? = null): List<RetrievalRow> {
        val sql = if (kind == null) {
            "SELECT id, kind, content, embedding, embedding_model FROM memories ORDER BY id ASC"
        } else {
            "SELECT id, kind, content, embedding, embedding_model FROM memories WHERE kind = ? ORDER BY id ASC"
        }
        return try {
            d.rawQuery(sql, kind?.let { arrayOf(it) }).use { c ->
                ArrayList<RetrievalRow>(c.count).also { out ->
                    while (c.moveToNext()) {
                        out.add(
                            RetrievalRow(
                                c.getLong(0),
                                c.getString(1) ?: KIND_FACT,
                                c.getString(2),
                                c.getBlob(3),
                                c.getString(4),
                            ),
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "retrieval rows read failed: ${e.message}")
            emptyList()
        }
    }

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

    /** 新增一条记忆（指定种类），重要度取默认档 5 */
    fun add(context: Context, content: String, kind: String): Int = add(context, content, kind, 5)

    /**
     * 新增一条记忆（指定种类与重要度）。
     *
     * 幂等有**两层**：`content` 的 UNIQUE 约束拦逐字重复；[addOrMerge] 的冲突消解
     * 把「同一事实的演进/翻转」（改述、否定更新）合并成一条 —— 同一条教训重复出现
     * 不会堆积成多条，这正是自动写入敢往这里丢内容的前提
     * （否则同样的错误记十遍会挤爆 60 条上限）。
     *
     * @param kind [KIND_FACT] / [KIND_LESSON]
     * @param importance AI 给出的重要度 1-10（mem0 的 LLM 评级，见 [MemoryScoring]）；
     *   落库前归一化为 0.1~1.0
     */
    fun add(context: Context, content: String, kind: String, importance: Int): Int =
        addOrMerge(context, content, kind, importance).total

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
     *   60 条上限很快被这类"同一个坑"塞满，真正有价值的经验反而被挤掉。
     *   按 key（如工具名）去重后，**同一个坑只留第一条**，这才是"记到规则里"该有的样子。
     *
     * ★ 已存在时会**刷新**既有教训的时间锚点（last_accessed）：同一个坑再次出现，
     *   恰恰证明这条教训仍然有效 —— mem0 的「复现即保鲜」，让它别在价值分里被 newer 内容压死。
     *
     * @param dedupKey 去重标记（按包含匹配；建议用工具名这类稳定标识）
     * @return 真的写入了返回 true；已存在同 key 教训（含刷新）或写入失败返回 false
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
                // 复现即保鲜：教训内容不堆叠，但"还在踩这个坑"这件事要计入淘汰评分
                runCatching {
                    d.execSQL(
                        "UPDATE memories SET last_accessed = ? WHERE kind = ? AND content LIKE ? ESCAPE '!'",
                        arrayOf(System.currentTimeMillis(), KIND_LESSON, "%${escapeLike(key)}%"),
                    )
                }
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

    // ──────────────────────────────────────────────
    //  冲突消解（mem0：同一事实的演进/翻转以新覆旧，而非多条并存）
    // ──────────────────────────────────────────────

    /** 词法合并阈值：中文 2-gram Jaccard 达到它即视为「同一句话的改述」（近似重复） */
    internal const val MERGE_JACCARD = 0.70

    /** 注入去重的嵌入余弦阈值：同模型双向量达到它即视为「同一事实的不同版本」 */
    internal const val DEDUP_COSINE = 0.90

    /** 否定词字符：短句 gram 几乎全包含于长句、多出的 gram 带这些字 ⇒ 语义翻转（喜欢→不喜欢） */
    private const val NEGATORS = "不没别非无勿未"

    /** 中文 2-gram Jaccard 相似度（纯函数，可单测）：两个 [tokenize] 集合的交并比 */
    internal fun lexicalSimilarity(a: String, b: String): Double {
        val ta = tokenize(a)
        val tb = tokenize(b)
        if (ta.isEmpty() || tb.isEmpty()) return 0.0
        val inter = ta.count { it in tb }
        return inter.toDouble() / (ta.size + tb.size - inter)
    }

    /**
     * 两条记忆是否「同一事实的演进/翻转」，应合并为一条（纯函数，可单测）。
     *
     * 两类典型场景：
     *  - **改述**：「每天9点上班」vs「我每天9点才上班」—— 2-gram Jaccard 很高；
     *  - **否定翻转**：「我喜欢猫」vs「我不喜欢猫」—— Jaccard 只有 0.6 抓不住，
     *    但结构上是「短句的 gram 几乎都在长句里、多出来的 gram 带否定字」，
     *    单独一条判据捕捉它（这正是冲突消解最想抓的形态）。
     *
     * 刻意**只判「高相似」不判「语义相反」**：嵌入区分不了「喜欢猫/喜欢狗」与
     * 「喜欢猫/不喜欢猫」（两组都高度相似），词法侧的否定判据是唯一可靠信号；
     * 判不出就让两条共存 —— 宁可库里有冗余，不可把两条不同的事实合并丢信息。
     */
    internal fun conflictMerge(a: String, b: String): Boolean {
        val ta = tokenize(a)
        val tb = tokenize(b)
        if (ta.isEmpty() || tb.isEmpty()) return false
        val inter = ta.count { it in tb }
        val jaccard = inter.toDouble() / (ta.size + tb.size - inter)
        if (jaccard >= MERGE_JACCARD) return true
        // 否定翻转：短句 gram 大部分被长句包含，且长句多出的 gram 含否定字
        val shorter = if (ta.size <= tb.size) ta else tb
        val longer = if (ta.size <= tb.size) tb else ta
        if (longer.size > shorter.size * 2) return false // 长度差太大不是同一句话的改述
        val contained = shorter.count { it in longer }.toDouble() / shorter.size
        if (contained < 0.65) return false
        return (longer - shorter).any { g -> g.any { it in NEGATORS } }
    }

    /** [addOrMerge] 的结果：[total] 为记忆总条数；[mergedFrom] 非 null 表示替换了这条旧记忆 */
    internal class AddResult(val total: Int, val mergedFrom: String?)

    /**
     * 新增记忆，**先做冲突消解**：与既有记忆「同一事实的演进/翻转」（见 [conflictMerge]）时，
     * 用新内容**替换**旧条而不是追加 —— mem0 的 update 语义。
     *
     * ★ 为什么必须做：`content UNIQUE` 只拦逐字重复，拦不住「用户喜欢猫」之后又来
     *   「用户不喜欢猫」—— 两条都进检索窗口，模型每轮都被矛盾信息干扰；
     *   容量也在为同一个事实的多个版本付费。
     *
     * 替换语义（与用户手动 [update] 对齐）：正文/created_at 换新、旧向量作废（等懒回填）、
     * importance 取新旧较大值（重新确认过的事实不应降级）、access_count 保留
     * （这个事实被用过的热度属于事实本身，不随表述更新清零）。
     */
    internal fun addOrMerge(context: Context, content: String, kind: String, importance: Int): AddResult =
        synchronized(lock) {
            val text = content.trim().take(MAX_ITEM_CHARS)
            val d = db(context)
            val k = if (kind == KIND_LESSON) KIND_LESSON else KIND_FACT
            if (text.isEmpty()) return@synchronized AddResult(countRows(d), null)
            try {
                // 写入前先在同 kind 内找「演进/翻转」的旧条：取词法分最高且过判据的那条
                var bestId = -1L
                var bestFrom: String? = null
                var best = 0.0
                d.rawQuery("SELECT id, content FROM memories WHERE kind = ?", arrayOf(k)).use { c ->
                    while (c.moveToNext()) {
                        val old = c.getString(1)
                        val sim = lexicalSimilarity(text, old)
                        if (sim > best && conflictMerge(text, old)) {
                            best = sim
                            bestId = c.getLong(0)
                            bestFrom = old
                        }
                    }
                }
                if (bestFrom != null) {
                    d.execSQL(
                        "UPDATE memories SET content = ?, created_at = ?, " +
                            "importance = MAX(importance, ?), embedding = NULL, embedding_model = NULL " +
                            "WHERE id = ?",
                        arrayOf<Any?>(
                            text,
                            System.currentTimeMillis(),
                            importance.coerceIn(1, 10) / 10.0,
                            bestId,
                        ),
                    )
                    Log.i(TAG, "merged new memory into #$bestId (conflict resolution)")
                    return@synchronized AddResult(countRows(d), bestFrom)
                }
                d.execSQL(
                    "INSERT OR IGNORE INTO memories(content, created_at, kind, importance) VALUES(?, ?, ?, ?)",
                    arrayOf(text, System.currentTimeMillis(), k, importance.coerceIn(1, 10) / 10.0),
                )
                enforceCap(d)
                AddResult(countRows(d), null)
            } catch (e: Exception) {
                Log.w(TAG, "addOrMerge failed: ${e.message}")
                AddResult(countRows(d), null)
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
     * last_accessed 同步刷新：用户确认 = 最强的一次「被用到」（价值评分的时间锚点随之归零）。
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
                    // 正文变了，旧向量描述的是改前内容 —— 一并作废，等懒回填按新正文重算
                    d.execSQL(
                        "UPDATE memories SET content = ?, created_at = ?, last_accessed = ?, " +
                            "embedding = NULL, embedding_model = NULL WHERE id = ?",
                        arrayOf<String>(
                            text,
                            System.currentTimeMillis().toString(),
                            System.currentTimeMillis().toString(),
                            c.getLong(0).toString(),
                        ),
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
     * 注入用文本（**用户事实**）：按 [query] 与记忆的相关性取 top-K 拼成带编号的列表。
     * query 为空时回退「最近 K 条」（按写入时间倒序）；无事实记忆返回 null。
     *
     * 编号取该条在**全量列表** `items()` 里的位置（1 起），
     * 因此 AI 用 `manage_memory(delete, content="3")` 删除时不会错位 —— 教训混在列表里也不影响。
     *
     * ⚠️ 输出经 [UntrustedContent.wrapInjected] 包成隔离段（2026-10-07 安全修复）。
     * 理由：条目内容**不一定**来自用户亲口陈述 —— `manage_memory` 允许模型写入，
     * 而模型可能刚读过一段含注入的网页/文档。包在这一层（数据源头）而不是
     * 提示词装配层，是因为本函数有多个调用方，只改一处会漏。
     */
    fun memoriesContext(
        context: Context,
        query: String? = null,
        // top-K 降档（2026-10-12 P2）：闲聊快路/菜单探测轮传小值（如 4）——
        // 那两类轮次要么不干活、要么只做意图盘点，12 条记忆（峰值 ~3.6k 字符）纯属陪跑。
        // 缺省保持原值，工具轮/正常轮行为不变。
        topK: Int = INJECT_TOP_K,
    ): String? {
        val d = db(context)
        pruneExpired(d)
        val all = retrievalRows(d)
        // 事实：带上全局位置作为编号
        val facts = all.withIndex()
            .filter { it.value.kind == KIND_FACT }
            .map { it.index to it.value }
        val picked = pickRelevant(context, d, facts, query, topK) ?: return null
        val body = buildString {
            append("以下是与当前话题可能相关的长期记忆（跨会话积累，可据此个性化回答）：\n")
            picked.forEach { (i, r) -> append("${i + 1}. ${r.content}\n") }
        }.trimEnd()
        return com.rokidlab.phone.ai.UntrustedContent.wrapInjected(
            toolName = "memories",
            content = body,
            sourceNote = "你过去在这台设备上积累的长期记忆（用户事实）",
        )
    }

    /**
     * 注入用文本（**经验教训**）：AI 自己攒下的坑与做法，取 top-K。
     *
     * ⚠️ 这里刻意**不编号**（用 `·` 圆点）：编号在 [memoriesContext] 里是删除用的定位符，
     * 教训在自己窗口里另起一套编号只会诱导模型拿它去 `manage_memory(delete, "1")`
     * —— 那删掉的是第一条**用户事实**。要删教训就让模型传内容片段。
     *
     * ⚠️⚠️ 输出必须经 [UntrustedContent.wrapInjected] 包装，且**这一段是全项目
     * 注入风险最高的地方**（2026-10-07 安全修复）：
     *  1. **教训会被框架自动播种**（工具失败时由 `AiConversationService` 写入），
     *     而错误串可能整段来自网页正文 ⇒ 攻击者只需构造一个让工具失败的网页，
     *     注入内容就进了长期库。
     *  2. 提示词里这一段的尾句是**指令性**的「做同类事情时按它来」
     *     （`OpenAiService.buildSystemMessage`）—— 模型被显式要求服从整段内容。
     *
     * 不加隔离标记 = 让模型分不清「该服从的框架指令」与「框内数据里的伪装指令」。
     */
    fun lessonsContext(
        context: Context,
        query: String? = null,
        // 同 memoriesContext 的降档参数（2026-10-12 P2）：闲聊快路/菜单探测轮传小值。
        lessonTopK: Int = LESSON_INJECT_TOP_K,
    ): String? {
        val d = db(context)
        pruneExpired(d)
        val lessons = retrievalRows(d, KIND_LESSON).mapIndexed { i, r -> i to r }
        val picked = pickRelevant(context, d, lessons, query, lessonTopK) ?: return null
        val body = buildString {
            append("以下是你过去在这台设备上踩过的坑或验证过的做法（跨会话积累）。做同类事情时先照它来，不要重复试错：\n")
            picked.forEach { (_, r) -> append("· ").append(r.content).append('\n') }
        }.trimEnd()
        return com.rokidlab.phone.ai.UntrustedContent.wrapInjected(
            toolName = "lessons",
            content = body,
            sourceNote = "你过去在这台设备上积累的经验教训",
        )
    }

    /**
     * 相关性取 top-K（照 KnowledgeBase v3 思路：**词法 2-gram + 语义向量，RRF 名次融合**）。
     *
     * 两路召回互补：
     *  - 词法（中文 2-gram + ASCII 词元重叠）擅长精确关键词（型号、人名、报错串）；
     *  - 向量擅长「换个说法」（用户问「我啥时候上班」、记忆写「每天 9 点打卡」）——
     *    纯词法对这类改述只能空手而归。
     *
     * 融合用 RRF（`1/(k+rank)` 名次倒数相加）：两路分数尺度完全不同（重叠数无界、
     * 余弦 0~1），直接线性加权要手调权重；RRF 只看名次，免调参、对单路噪声稳健。
     *
     * 降级语义（重要）：语义通道在「开关关 / 未配置嵌入模型 / 无已嵌入条目 / 网络失败」
     * 任一情况下自动缺席，结果与旧版纯词法**完全一致**——绝不因向量通道故障让检索不可用。
     * query 为空 / 两路全不命中时回退「最近 K 条」（同旧版）。
     *
     * ★ 所有被注入的记忆都会 [touch] 一次（mem0 的「访问即保鲜」）：注入本身就是
     *   这条记忆"还在被使用"的最直接证据，是淘汰评分里频率/新近度两个信号的数据来源。
     *
     * @param items (编号, 行) 列表；编号的语义由调用方定义（事实=全局位置，教训=占位）
     * @return 命中的 (编号, 行)（按编号升序，注入编号稳定）；入参为空返回 null
     */
    private fun pickRelevant(
        context: Context,
        d: SQLiteDatabase,
        items: List<Pair<Int, RetrievalRow>>,
        query: String?,
        topK: Int,
    ): List<Pair<Int, RetrievalRow>>? {
        if (items.isEmpty()) return null
        // snap 提前加载：注入去重（[dedupSimilar]）在 fallback 路径也需要它（prefs 读，代价极小）
        val snap = EmbeddingSettings.load(context)
        val q = query?.trim().orEmpty()
        if (q.isEmpty()) {
            val fallback = dedupSimilar(items.takeLast(topK), snap)
            touch(d, fallback.map { it.second.id })
            return fallback
        }
        val qTokens = tokenize(q)

        // ── 词法路：词元重叠数排序，平分时新条目优先（与旧版一致）──
        val lexRanked = items
            .withIndex()
            .map { (pos, pair) -> Triple(pos, pair.first, tokenize(pair.second.content).count { it in qTokens }) }
            .filter { it.third > 0 }
            .sortedWith(compareByDescending<Triple<Int, Int, Int>> { it.third }.thenByDescending { it.second })
            .take(FUSION_POOL)
            .map { it.first }

        // ── 语义路：未启用直接缺席；运行期失败绝不拖垮整条检索 ──
        val vecRanked = if (snap.ready) {
            maybeBackfill(context, snap)
            runCatching { vectorRanked(context, snap, q, items) }
                .getOrElse { e ->
                    Log.w(TAG, "memory vector recall skipped: ${e.message}")
                    emptyList()
                }
        } else {
            emptyList()
        }

        // 融合：语义路缺席 ⇒ 与旧版纯词法行为完全一致
        val fused = if (vecRanked.isEmpty()) lexRanked else rrfFuse(lexRanked, vecRanked)
        val picked = fused.take(topK)
            .map { pos -> items[pos] }
            .sortedBy { it.first } // 恢复编号升序，注入编号与全量列表一致
        val result = dedupSimilar(picked.ifEmpty { items.takeLast(topK) }, snap)
        touch(d, result.map { it.second.id })
        return result
    }

    /**
     * 注入窗口内的**冲突消解**：同一事实的多个版本只留最新的（id 大者，mem0 update 语义），
     * 防止「喜欢猫」与「不喜欢猫」同时出现在注入列表里互相打架。
     *
     * 判据见 [conflictSimilar]（词法改述/否定翻转 + 嵌入余弦，向量检索路现成、零额外网络）。
     * 编号是全局位置，去重只影响「哪些进来」，编号本身不变 —— delete 定位不受影响。
     */
    private fun dedupSimilar(
        picked: List<Pair<Int, RetrievalRow>>,
        snap: EmbeddingSnapshot,
    ): List<Pair<Int, RetrievalRow>> =
        picked.sortedByDescending { it.second.id }
            .fold(mutableListOf<Pair<Int, RetrievalRow>>()) { kept, item ->
                if (kept.none { conflictSimilar(it.second, item.second, snap) }) kept.add(item)
                kept
            }
            .sortedBy { it.first }

    /** 两条记忆是否同一事实的版本：词法判据（[conflictMerge]）或同模型嵌入余弦 ≥ [DEDUP_COSINE] */
    private fun conflictSimilar(a: RetrievalRow, b: RetrievalRow, snap: EmbeddingSnapshot): Boolean {
        if (conflictMerge(a.content, b.content)) return true
        val va = a.vectorFor(snap) ?: return false
        val vb = b.vectorFor(snap) ?: return false
        var dot = 0.0
        for (i in va.indices) dot += va[i].toDouble() * vb[i].toDouble()
        return dot >= DEDUP_COSINE
    }

    /** 行的当前模型向量（未嵌入/模型不匹配返回 null —— 与 [vectorRanked] 的过滤口径一致） */
    private fun RetrievalRow.vectorFor(snap: EmbeddingSnapshot): FloatArray? {
        val blob = embedding ?: return null
        if (embeddingModel != snap.model) return null
        return KnowledgeBase.decodeVector(blob)
    }

    /**
     * 注入命中 = 「被用到」：命中次数 +1 并刷新新近锚点（[MemoryScoring] 的两个活信号）。
     *
     * ⚠️ 刻意**不走 [lock]**：这是单条原子 UPDATE（自增 + 覆盖时间），没有「读-改-写」竞态窗口；
     * 而本函数在 prompt 组装的同步路径上，去抢工具 mutator 的锁只会白等。
     */
    private fun touch(d: SQLiteDatabase, ids: List<Long>) {
        if (ids.isEmpty()) return
        try {
            val placeholders = ids.joinToString(",") { "?" }
            val args = arrayOfNulls<Any?>(ids.size + 1)
            args[0] = System.currentTimeMillis()
            ids.forEachIndexed { i, id -> args[i + 1] = id }
            d.execSQL(
                "UPDATE memories SET access_count = access_count + 1, last_accessed = ? WHERE id IN ($placeholders)",
                args,
            )
        } catch (e: Exception) {
            // 保鲜回写失败只丢一次计数，检索结果不受影响 —— 静默降级
            Log.w(TAG, "memory touch failed: ${e.message}")
        }
    }

    /**
     * RRF（Reciprocal Rank Fusion）名次融合（纯函数，可单测）。
     * [lexRanked] / [vecRanked] 分别是两路候选在列表里的**位置**（已按各自名次排序），
     * 每路第 rank 名贡献 `1/(k+rank+1)`，同位置相加后按融合分降序输出位置。
     * 同分时保持插入序（词法路先入），输出确定可测。
     */
    internal fun rrfFuse(lexRanked: List<Int>, vecRanked: List<Int>): List<Int> {
        val scores = LinkedHashMap<Int, Double>()
        lexRanked.forEachIndexed { rank, pos ->
            scores[pos] = (scores[pos] ?: 0.0) + 1.0 / (RRF_K + rank + 1)
        }
        vecRanked.forEachIndexed { rank, pos ->
            scores[pos] = (scores[pos] ?: 0.0) + 1.0 / (RRF_K + rank + 1)
        }
        return scores.entries.sortedByDescending { it.value }.map { it.key }
    }

    /**
     * 语义召回：查询 1 次远程嵌入 → 与已嵌入条目做余弦（= 点积，向量已归一化）
     * → 返回条目**在 [items] 里的位置**按相关度降序。
     * 任一前置不满足（没有当前模型的已嵌入条目）返回空，调用方按「语义路缺席」处理；
     * 网络失败抛异常，由 [pickRelevant] 统一兜底为纯词法。
     */
    private fun vectorRanked(
        context: Context,
        snap: EmbeddingSnapshot,
        query: String,
        items: List<Pair<Int, RetrievalRow>>,
    ): List<Int> {
        // 一个当前模型的已嵌入条目都没有：不打这趟网络（回填会随后台线程补上，下轮生效）
        if (items.none { it.second.embedding != null && it.second.embeddingModel == snap.model }) {
            return emptyList()
        }
        val qv = EmbeddingClient.embed(
            snap.baseUrl,
            EmbeddingSettings.apiKey(context),
            snap.model,
            listOf(query),
            QUERY_EMBED_READ_TIMEOUT_MS,
        ).vectors.firstOrNull() ?: return emptyList()
        if (qv.isEmpty()) return emptyList()

        data class Cand(val pos: Int, val cos: Double)
        val cands = ArrayList<Cand>()
        items.forEach { (pos, row) ->
            val blob = row.embedding ?: return@forEach
            if (row.embeddingModel != snap.model) return@forEach // 换模型后未重建的旧向量：跳过，等回填
            val v = KnowledgeBase.decodeVector(blob)
            if (v.size != qv.size) return@forEach // 维度不一致（防御）：等重建
            var dot = 0.0
            for (i in v.indices) dot += v[i].toDouble() * qv[i].toDouble()
            if (dot > 0.0) cands.add(Cand(pos, dot))
        }
        return cands.sortedByDescending { it.cos }.take(FUSION_POOL).map { it.pos }
    }

    /**
     * 懒回填触发点：语义开关开着且存在「缺向量 / 模型过期」的条目时，起**单飞后台线程**
     * 分批补算（照 KnowledgeBase backfillEmbeddings 模式，一批 [EmbeddingClient.BATCH_SIZE] 条、
     * 一个小事务落盘，中途失败只丢当前批，下次触发从未嵌入条目继续）。
     *
     * ⚠️ 检索在 prompt 组装路径上同步执行，**绝不等待回填**——本轮未嵌入条目直接跳过
     * 向量打分，等下一轮对话生效。数据库小（≤260 条），派发线程上的缺口探测是一次廉价查询。
     */
    private fun maybeBackfill(context: Context, snap: EmbeddingSnapshot) {
        if (!backfillRunning.compareAndSet(false, true)) return
        val needs = try {
            db(context).rawQuery(
                // ⚠️ embedding_model 是 NULL 时 `!= ?` 结果为 NULL（不是 true），必须显式 IS NULL
                "SELECT 1 FROM memories WHERE embedding IS NULL OR embedding_model IS NULL " +
                    "OR embedding_model != ? LIMIT 1",
                arrayOf(snap.model),
            ).use { it.moveToFirst() }
        } catch (e: Exception) {
            Log.w(TAG, "backfill probe failed: ${e.message}")
            false
        }
        if (!needs) {
            backfillRunning.set(false)
            return
        }
        Thread({
            try {
                val d = db(context)
                var done = 0
                while (true) {
                    data class Pending(val id: Long, val content: String)
                    val pending = ArrayList<Pending>(EmbeddingClient.BATCH_SIZE)
                    d.rawQuery(
                        "SELECT id, content FROM memories WHERE embedding IS NULL OR embedding_model IS NULL " +
                            "OR embedding_model != ? ORDER BY id LIMIT ${EmbeddingClient.BATCH_SIZE}",
                        arrayOf(snap.model),
                    ).use { c ->
                        while (c.moveToNext()) pending.add(Pending(c.getLong(0), c.getString(1)))
                    }
                    if (pending.isEmpty()) break

                    val batch = EmbeddingClient.embed(
                        snap.baseUrl, EmbeddingSettings.apiKey(context), snap.model, pending.map { it.content },
                    )
                    if (batch.vectors.size != pending.size) {
                        throw IllegalStateException(
                            "embedding count mismatch: requested ${pending.size}, got ${batch.vectors.size}",
                        )
                    }
                    val resolved = batch.model.ifBlank { snap.model }
                    d.beginTransaction()
                    try {
                        pending.forEachIndexed { i, p ->
                            d.update(
                                "memories",
                                ContentValues().apply {
                                    put("embedding", KnowledgeBase.encodeVector(batch.vectors[i]))
                                    put("embedding_model", resolved)
                                },
                                "id=?", arrayOf(p.id.toString()),
                            )
                        }
                        d.setTransactionSuccessful()
                    } finally {
                        d.endTransaction()
                    }
                    done += pending.size
                    // 服务端返回的模型名与请求不一致：写入后仍匹配不上检索过滤条件，
                    // 继续循环会变成死循环打网络 —— 记日志收工，让用户侧看到语义通道不生效
                    if (resolved != snap.model) {
                        Log.w(TAG, "backfill: server model '$resolved' != requested '${snap.model}', stop")
                        break
                    }
                }
                Log.i(TAG, "memory backfill: $done items embedded with ${snap.model}")
            } catch (e: Exception) {
                Log.w(TAG, "memory backfill aborted: ${e.message}")
            } finally {
                backfillRunning.set(false)
            }
        }, "memory-embed-backfill").apply {
            isDaemon = true
            start()
        }
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
     * 两类记忆**各自**淘汰超出上限的部分。
     *
     * ⚠️ 两个上限必须在自己的 kind 内淘汰，不能合并成一个总数上限：
     * 教训是可以自动积累的，而事实只能由用户/AI 显式写入 —— 合起来算的话，
     * 攒够教训就会把用户亲口说过的事实挤掉，那是这里最不该丢的数据。
     */
    private fun enforceCap(d: SQLiteDatabase) {
        trimKind(d, KIND_FACT, MAX_ITEMS)
        trimKind(d, KIND_LESSON, MAX_LESSONS)
    }

    /**
     * 按 [MemoryScoring] 价值分淘汰超限条目（mem0 风格，2026-10-02 起替代 FIFO）。
     *
     * ★ 为什么不再按 id 删最旧：FIFO 里「用户亲口说的身份信息」和「AI 顺手记的备注」
     *   地位相同，谁先写谁先走。现在淘汰的是**分最低**的 —— 不重要的、久未命中的
     *   先走；重要/常用的老记忆即使写得早也能留。
     *
     * ⚠️ 平分时仍按 id 升序（旧 FIFO 语义兜底）：同分的两条总得有确定性的走法，
     *   留新去旧是更合理的默认。
     *
     * 评分查询失败时**回退旧 FIFO**：淘汰是容量底线，宁可退化为粗糙也不能不淘汰。
     */
    private fun trimKind(d: SQLiteDatabase, kind: String, max: Int) {
        val n = countRows(d, kind)
        if (n <= max) return
        val overflow = n - max
        try {
            data class Scored(val id: Long, val score: Double)
            val scored = ArrayList<Scored>(n)
            d.rawQuery(
                "SELECT id, importance, access_count, last_accessed, created_at FROM memories WHERE kind = ?",
                arrayOf(kind),
            ).use { c ->
                val now = System.currentTimeMillis()
                while (c.moveToNext()) {
                    val score = MemoryScoring.evictionScore(
                        importance = c.getDouble(1),
                        accessCount = c.getInt(2),
                        lastAccessed = if (c.isNull(3)) null else c.getLong(3),
                        createdAt = c.getLong(4),
                        now = now,
                    )
                    scored.add(Scored(c.getLong(0), score))
                }
            }
            scored.sortedWith(compareBy<Scored> { it.score }.thenBy { it.id })
                .take(overflow)
                .forEach { v -> d.delete("memories", "id=?", arrayOf(v.id.toString())) }
            Log.i(TAG, "trim $kind: evicted $overflow lowest-score of $n (cap=$max)")
        } catch (e: Exception) {
            // 旧语义兜底：删最旧。新列读到脏值/缺失都不该让容量失控
            Log.w(TAG, "score-based trim failed, fall back to FIFO: ${e.message}")
            d.execSQL(
                "DELETE FROM memories WHERE id IN (" +
                    "SELECT id FROM memories WHERE kind = ? ORDER BY id ASC LIMIT $overflow)",
                arrayOf(kind),
            )
        }
    }

    /**
     * 惰性清理过期记忆（读取路径顺带执行，代价极小）。
     *
     * ★ 90 天的计时锚点从「写入时间」换成「**最近一次被用到**」（[MemoryScoring] 的锚点口径）：
     * `COALESCE(last_accessed, created_at)` —— 注入命中、用户编辑、教训复现都会刷新。
     * 旧的一刀切会误杀「每季度必用」的偏好（春节回家路线）；现在只要还在被用就不会过期，
     * 真正 90 天没人碰的（含从未被检索命中的长尾）才清出去。
     */
    private fun pruneExpired(d: SQLiteDatabase) {
        try {
            val cutoff = System.currentTimeMillis() - EXPIRE_DAYS * 24L * 3600 * 1000
            d.delete("memories", "COALESCE(last_accessed, created_at) < ?", arrayOf(cutoff.toString()))
        } catch (e: Exception) {
            // 过期清理失败只影响容量回收（不会误删记忆），但不能静默 —— 否则永久失效后无从排查
            Log.w(TAG, "pruneExpired failed: ${e.message}")
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
    // ⚠️ `by lazy` 缓存（2026-10-05）：无参纯常量（KIND_FACT/KIND_LESSON 是编译期常量），
    // 缓存理由与共享前提见 [SkillRegistry.schema] 的 KDoc。
    fun schema(): JSONObject = SCHEMA

    private val SCHEMA: JSONObject = JSONObject().apply {
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
                        put("importance", JSONObject().apply {
                            put("type", "integer")
                            put("minimum", 1)
                            put("maximum", 10)
                            put("description", "create 时的重要度 1-10（默认 5，可省略）：身份/长期偏好 8-10，习惯与常用信息 5-7，临时细节 1-4")
                        })
                    })
                    put("required", JSONArray(listOf("action")))
                })
            })
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
                // 重要度 1-10（缺省 5）：落库前 coerce 兜底，模型传出界值不会污染评分
                val importance = args.optInt("importance", 5).coerceIn(1, 10)
                val r = addOrMerge(context, content, kind, importance)
                // 合并发生时要让模型知道：它可能需要向用户改口（"已更新"而不是"已记住"）
                when {
                    kind == KIND_LESSON -> "已记下这条经验教训（共 ${r.total} 条记忆）"
                    r.mergedFrom != null -> "已更新相关的既有记忆（共 ${r.total} 条长期记忆）"
                    else -> "已记住：$content（共 ${r.total} 条长期记忆）"
                }
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
