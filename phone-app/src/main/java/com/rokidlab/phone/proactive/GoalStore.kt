package com.rokidlab.phone.proactive

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** 目标状态：进行中 / 已达成 / 已放弃 */
const val GOAL_STATUS_ACTIVE = "active"
const val GOAL_STATUS_DONE = "done"
const val GOAL_STATUS_DROPPED = "dropped"

/** 目标的一个步骤（[id] 生成后不再变，序号只用于展示与 `complete_step` 的匹配） */
data class GoalStep(
    val id: String,
    val title: String,
    val done: Boolean = false,
)

/**
 * 一个目标（Muse 式「长期目标 + 检查点」的实体）：
 * 用户经 `create_goal` 工具立下、由 [GoalCheck] 定期回访、经 `update_goal` 推进或了结。
 */
data class Goal(
    val id: String,
    val title: String,
    val detail: String = "",
    val steps: List<GoalStep> = emptyList(),
    val deadlineMs: Long? = null,
    val nextCheckMs: Long? = null,
    val status: String = GOAL_STATUS_ACTIVE,
    val createdAtMs: Long,
    val updatedAtMs: Long,
) {
    fun isActive(): Boolean = status == GOAL_STATUS_ACTIVE
}

/**
 * 目标存储（Application 级单例）：`filesDir/goals/goals.json` 一个 JSON 数组存全部目标。
 *
 * 为什么用文件而不是 prefs：目标是**结构化的可变列表**（步骤增删、状态翻转），
 * 整表读写一个 JSON 数组最直接（同 [com.rokidlab.phone.ai.AgentTaskStore] 的取径）；
 * prefs 只适合零散键值。写入走**原子写**（进程被杀不留半截 JSON），读端全程容错 ——
 * 文件损坏按「无目标」处理，绝不让存储拖垮自检链。
 *
 * ⚠️ 所有读写都在实例锁内：`create_goal`/`update_goal`（对话轮）与 [GoalCheck]（自检链）
 * 可能并发访问。
 */
class GoalStore private constructor(private val appContext: Context) {
    companion object {
        private const val TAG = "GoalStore"
        private const val DIR = "goals"
        private const val FILE = "goals.json"

        @Volatile
        private var instance: GoalStore? = null

        fun get(context: Context): GoalStore =
            instance ?: synchronized(this) {
                instance ?: GoalStore(context.applicationContext).also { instance = it }
            }
    }

    private val lock = Any()

    private fun file(): File = File(File(appContext.filesDir, DIR), FILE)

    // ═══════════════════ 读 ═══════════════════

    /** 全部目标（含已完成/已放弃，落盘顺序）；文件缺失/损坏返回空表 */
    fun all(): List<Goal> = synchronized(lock) { load() }

    /** 进行中的目标（回访扫描与 list_goals 的默认口径） */
    fun active(): List<Goal> = all().filter { it.isActive() }

    /** 按 id 取单个目标 */
    fun get(id: String): Goal? = synchronized(lock) { load().firstOrNull { it.id == id } }

    // ═══════════════════ 写 ═══════════════════

    /**
     * 新建目标（标题必填；步骤/截止/检查点可选）。
     * 进行中目标超过 [GoalPolicy.MAX_ACTIVE_GOALS] 时返回 null（调用方回报模型）。
     */
    fun create(
        title: String,
        detail: String = "",
        stepTitles: List<String> = emptyList(),
        deadlineMs: Long? = null,
        nextCheckMs: Long? = null,
        nowMs: Long = System.currentTimeMillis(),
    ): Goal? = synchronized(lock) {
        val goals = load()
        if (goals.count { it.isActive() } >= GoalPolicy.MAX_ACTIVE_GOALS) {
            Log.i(TAG, "create rejected: active goals at cap ${GoalPolicy.MAX_ACTIVE_GOALS}")
            return null
        }
        val goal = Goal(
            id = UUID.randomUUID().toString(),
            title = GoalPolicy.sanitizeText(title, GoalPolicy.MAX_TITLE_CHARS),
            detail = GoalPolicy.sanitizeText(detail, GoalPolicy.MAX_DETAIL_CHARS),
            steps = stepTitles.take(GoalPolicy.MAX_STEPS).map {
                GoalStep(UUID.randomUUID().toString(), GoalPolicy.sanitizeText(it, GoalPolicy.MAX_STEP_TITLE_CHARS))
            }.filter { it.title.isNotEmpty() },
            deadlineMs = deadlineMs,
            nextCheckMs = nextCheckMs,
            status = GOAL_STATUS_ACTIVE,
            createdAtMs = nowMs,
            updatedAtMs = nowMs,
        )
        save(goals + goal)
        goal
    }

    /** 整体替换保存（步骤/状态/检查点的统一写入口；[goal.updatedAtMs] 由这里刷新） */
    fun update(goal: Goal, nowMs: Long = System.currentTimeMillis()): Boolean = synchronized(lock) {
        val goals = load()
        val idx = goals.indexOfFirst { it.id == goal.id }
        if (idx < 0) return false
        save(goals.toMutableList().apply { set(idx, goal.copy(updatedAtMs = nowMs)) })
        true
    }

    /** 追加一步（步骤数超 [GoalPolicy.MAX_STEPS] 或目标不存在/已结束返回 null） */
    fun addStep(goalId: String, title: String, nowMs: Long = System.currentTimeMillis()): Goal? =
        synchronized(lock) {
            val goals = load()
            val idx = goals.indexOfFirst { it.id == goalId && it.isActive() }
            if (idx < 0) return null
            if (goals[idx].steps.size >= GoalPolicy.MAX_STEPS) {
                Log.i(TAG, "addStep rejected: steps at cap ${GoalPolicy.MAX_STEPS}")
                return null
            }
            val updated = goals[idx].copy(
                steps = goals[idx].steps + GoalStep(
                    UUID.randomUUID().toString(),
                    GoalPolicy.sanitizeText(title, GoalPolicy.MAX_STEP_TITLE_CHARS),
                ),
                updatedAtMs = nowMs,
            )
            save(goals.toMutableList().apply { set(idx, updated) })
            updated
        }

    /** 完成一步（按 [GoalPolicy.resolveStepKey] 匹配）；全部完成时目标自动转 DONE */
    fun completeStep(goalId: String, stepKey: String, nowMs: Long = System.currentTimeMillis()): Pair<Goal, Boolean>? =
        synchronized(lock) {
            val goals = load()
            val idx = goals.indexOfFirst { it.id == goalId && it.isActive() }
            if (idx < 0) return null
            val goal = goals[idx]
            val stepIdx = GoalPolicy.resolveStepKey(goal.steps, stepKey) ?: return null
            if (goal.steps[stepIdx].done) return goal to false
            val steps = goal.steps.toMutableList().apply { set(stepIdx, this[stepIdx].copy(done = true)) }
            val allDone = steps.all { it.done }
            val updated = goal.copy(
                steps = steps,
                status = if (allDone) GOAL_STATUS_DONE else goal.status,
                updatedAtMs = nowMs,
            )
            save(goals.toMutableList().apply { set(idx, updated) })
            if (allDone) Log.i(TAG, "goal auto-done (all steps completed): ${goal.title.take(30)}")
            updated to true
        }

    /** 设置下次回访时刻（[whenMs] 传 null = 清除检查点，供 GoalCheck 消费显式检查点） */
    fun setNextCheck(goalId: String, whenMs: Long?, nowMs: Long = System.currentTimeMillis()): Goal? =
        synchronized(lock) { mutate(goalId, nowMs) { it.copy(nextCheckMs = whenMs) } }

    /** 标记达成（action=complete） */
    fun markDone(goalId: String, nowMs: Long = System.currentTimeMillis()): Goal? =
        synchronized(lock) { mutate(goalId, nowMs) { it.copy(status = GOAL_STATUS_DONE) } }

    /** 标记放弃（action=drop） */
    fun markDropped(goalId: String, nowMs: Long = System.currentTimeMillis()): Goal? =
        synchronized(lock) { mutate(goalId, nowMs) { it.copy(status = GOAL_STATUS_DROPPED) } }

    /** [mutate] = load → transform → save 的统一封装；目标不存在/非进行中返回 null */
    private inline fun mutate(goalId: String, nowMs: Long, transform: (Goal) -> Goal): Goal? {
        val goals = load()
        val idx = goals.indexOfFirst { it.id == goalId }
        if (idx < 0) return null
        val updated = transform(goals[idx]).copy(updatedAtMs = nowMs)
        save(goals.toMutableList().apply { set(idx, updated) })
        return updated
    }

    // ═══════════════════ 落盘 ═══════════════════

    private fun load(): List<Goal> = runCatching {
        val f = file()
        if (!f.isFile) return emptyList()
        val arr = JSONArray(f.readText())
        (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { fromJson(it) }
        }
    }.onFailure { Log.w(TAG, "load goals failed: ${it.message}") }.getOrDefault(emptyList())

    private fun save(goals: List<Goal>) {
        runCatching {
            val arr = JSONArray()
            goals.forEach { arr.put(toJson(it)) }
            File(appContext.filesDir, DIR).mkdirs()
            com.rokidlab.phone.ai.writeTextFileAtomically(file(), arr.toString())
        }.onFailure { Log.w(TAG, "save goals failed: ${it.message}") }
    }

    private fun toJson(g: Goal): JSONObject = JSONObject().apply {
        put("id", g.id)
        put("title", g.title)
        if (g.detail.isNotEmpty()) put("detail", g.detail)
        put("steps", JSONArray().apply {
            g.steps.forEach { s ->
                put(JSONObject().put("id", s.id).put("title", s.title).put("done", s.done))
            }
        })
        g.deadlineMs?.let { put("deadlineMs", it) }
        g.nextCheckMs?.let { put("nextCheckMs", it) }
        put("status", g.status)
        put("createdAtMs", g.createdAtMs)
        put("updatedAtMs", g.updatedAtMs)
    }

    private fun fromJson(j: JSONObject): Goal? {
        val id = j.optString("id")
        val title = j.optString("title")
        if (id.isEmpty() || title.isEmpty()) return null
        val steps = mutableListOf<GoalStep>()
        j.optJSONArray("steps")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val t = o.optString("title")
                if (t.isEmpty()) continue
                steps.add(
                    GoalStep(
                        id = o.optString("id").ifEmpty { UUID.randomUUID().toString() },
                        title = t,
                        done = o.optBoolean("done", false),
                    ),
                )
            }
        }
        return Goal(
            id = id,
            title = title,
            detail = j.optString("detail"),
            steps = steps,
            deadlineMs = j.optLong("deadlineMs", 0L).takeIf { it > 0L },
            nextCheckMs = j.optLong("nextCheckMs", 0L).takeIf { it > 0L },
            status = j.optString("status", GOAL_STATUS_ACTIVE),
            createdAtMs = j.optLong("createdAtMs"),
            updatedAtMs = j.optLong("updatedAtMs"),
        )
    }
}
