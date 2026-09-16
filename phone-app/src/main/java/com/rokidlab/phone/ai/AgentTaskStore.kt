package com.rokidlab.phone.ai

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Agent 长任务状态存储 —— 流程级 checkpoint（Agent 缺口「长任务可恢复」）。
 *
 * 为什么需要：一次多步任务（多文件生成、先勘察再动手）的中间状态此前只活在
 * `messages` 数组里 —— 轮次预算触顶 / 链路断 / 用户打断 / 进程被杀，turn 一结束即失忆，
 * 下次只能从头再来。而**产物级** checkpoint 其实一直存在（save_code_file 落的文件在磁盘上），
 * 缺的是「流程级」：走到第几步、还剩什么、上次为什么停。
 *
 * 本对象把任务状态落到 `filesDir/agent_task.json`（单槽位，只保留最近一个任务）：
 *   - 由 `update_plan` 调用点写入/更新计划（步骤 + 状态）
 *   - 由 `save_code_file` 成功点累积产物清单
 *   - 预算触顶 / 被打断 / 正常完成时标记终态与原因
 *   - 新一轮对话开始时，把 TTL 内未完成的任务摘要注入 system 提示词 → 模型可接着做
 *
 * 边界（刻意不做）：这是「状态持久化 + 下轮续做」，**不是**无人值守自动续跑
 * （进程死后自动爬起来接着跑需要后台任务队列 + 幂等保证，属另一档工程）。
 *
 * 读取一律容错：文件损坏/字段缺失按「无任务」处理，绝不让状态存储拖垮主对话链路。
 */
object AgentTaskStore {
    private const val TAG = "AgentTaskStore"
    private const val FILE_NAME = "agent_task.json"

    /** 未完成任务的可续做窗口：超过此时长视为过期（不再注入提示词） */
    private const val TTL_MS = 24 * 60 * 60 * 1000L

    /** 产物清单上限：只保留最近若干条，防长任务把提示词撑爆 */
    private const val MAX_ARTIFACTS = 20

    /** 状态：进行中 */
    const val STATUS_RUNNING = "running"

    /** 状态：工具轮次预算用尽（可续做） */
    const val STATUS_BUDGET_EXHAUSTED = "budget_exhausted"

    /** 状态：被用户打断 / 链路中断（可续做） */
    const val STATUS_INTERRUPTED = "interrupted"

    /** 状态：正常完成 */
    const val STATUS_DONE = "done"

    /** 汇总产物：项目名 → 已落盘文件（相对路径） */
    private class Artifact(val project: String, val file: String)

    private fun file(context: Context): File = File(context.filesDir, FILE_NAME)

    /** 计划步骤的状态文案（提示词与状态查询共用） */
    private fun mark(status: String): String = when (status) {
        "done" -> "✓"
        "in_progress" -> "→"
        else -> "·"
    }

    private fun isUnfinished(status: String): Boolean = status != STATUS_DONE

    // ═══════════════════════════════════════════════════
    // 读写
    // ═══════════════════════════════════════════════════

    private class Snapshot(
        var goal: String,
        var steps: List<AgentPlan.PlanStep>,
        val artifacts: MutableList<Artifact>,
        var lastError: String?,
        var status: String,
        var createdAt: Long,
        var updatedAt: Long,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("goal", goal)
            put("status", status)
            put("createdAt", createdAt)
            put("updatedAt", updatedAt)
            lastError?.let { put("lastError", it) }
            put("steps", JSONArray().apply {
                steps.forEach { s ->
                    put(JSONObject().put("title", s.title).put("status", s.status))
                }
            })
            put("artifacts", JSONArray().apply {
                artifacts.forEach { a -> put(JSONObject().put("project", a.project).put("file", a.file)) }
            })
        }

        companion object {
            fun fromJson(j: JSONObject): Snapshot {
                val steps = mutableListOf<AgentPlan.PlanStep>()
                j.optJSONArray("steps")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val t = o.optString("title").trim()
                        if (t.isNotEmpty()) steps.add(AgentPlan.PlanStep(t, o.optString("status", "pending")))
                    }
                }
                val arts = mutableListOf<Artifact>()
                j.optJSONArray("artifacts")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val p = o.optString("project").trim()
                        val f = o.optString("file").trim()
                        if (p.isNotEmpty() && f.isNotEmpty()) arts.add(Artifact(p, f))
                    }
                }
                return Snapshot(
                    goal = j.optString("goal"),
                    steps = steps,
                    artifacts = arts,
                    lastError = j.optString("lastError").takeIf { it.isNotBlank() },
                    status = j.optString("status", STATUS_RUNNING),
                    createdAt = j.optLong("createdAt"),
                    updatedAt = j.optLong("updatedAt"),
                )
            }
        }
    }

    private fun load(context: Context): Snapshot? = runCatching {
        val f = file(context)
        if (!f.isFile) return null
        Snapshot.fromJson(JSONObject(f.readText()))
    }.onFailure { Log.w(TAG, "load failed: ${it.message}") }.getOrNull()

    private fun save(context: Context, snap: Snapshot) {
        runCatching {
            snap.updatedAt = System.currentTimeMillis()
            file(context).writeText(snap.toJson().toString())
        }.onFailure { Log.w(TAG, "save failed: ${it.message}") }
    }

    // ═══════════════════════════════════════════════════
    // 写入（由对话链路在各关键点调用）
    // ═══════════════════════════════════════════════════

    /**
     * 记录/更新计划（`update_plan` 调用点）。
     *
     * 槽位规则（刻意简单可预期）：TTL 内已有未完成任务时，同一槽位视为**该任务的推进**
     * （只更新 steps，goal 保持首次创建时的用户原话）；任务已完成、过期或无任务时才新建。
     * 这样跨轮续做不会被 goal 覆写成"继续"两个字，也不会静默丢弃未完成任务。
     */
    fun recordPlan(context: Context, goal: String, arguments: String) {
        val steps = AgentPlan.parseSteps(arguments) ?: return
        val now = System.currentTimeMillis()
        val existing = load(context)
        val snap = if (existing != null && isUnfinished(existing.status) && now - existing.updatedAt <= TTL_MS) {
            existing.apply {
                this.steps = steps
                status = STATUS_RUNNING
                lastError = null
            }
        } else {
            Snapshot(
                goal = goal.take(200),
                steps = steps,
                artifacts = mutableListOf(),
                lastError = null,
                status = STATUS_RUNNING,
                createdAt = now,
                updatedAt = now,
            )
        }
        save(context, snap)
        Log.i(TAG, "recordPlan: ${steps.size} steps, goal=${snap.goal.take(40)}")
    }

    /**
     * 累积产物（`save_code_file` 成功点）。无任务时按「代码生成任务」新建，
     * goal 用当前用户诉求 —— 模型可能不调 update_plan 直接开始落盘。
     */
    fun recordArtifact(context: Context, goal: String?, project: String, file: String) {
        if (project.isBlank() || file.isBlank()) return
        val now = System.currentTimeMillis()
        val snap = load(context)
            ?: Snapshot(
                goal = goal?.take(200).orEmpty(),
                steps = mutableListOf(),
                artifacts = mutableListOf(),
                lastError = null,
                status = STATUS_RUNNING,
                createdAt = now,
                updatedAt = now,
            ).also { save(context, it) }
        if (snap.artifacts.none { it.project == project && it.file == file }) {
            snap.artifacts.add(Artifact(project, file))
            while (snap.artifacts.size > MAX_ARTIFACTS) snap.artifacts.removeAt(0)
            save(context, snap)
            Log.i(TAG, "recordArtifact: $project/$file (${snap.artifacts.size} total)")
        } else {
            snap.updatedAt = now
            save(context, snap)
        }
    }

    /** 标记终态（预算触顶 / 被打断 / 完成），可附带原因 */
    fun markStatus(context: Context, status: String, reason: String? = null) {
        val snap = load(context) ?: return
        // 已完成的任务不被后续的"空转"标记覆写成未完成
        if (snap.status == STATUS_DONE && status != STATUS_DONE) return
        snap.status = status
        if (!reason.isNullOrBlank()) snap.lastError = reason.take(200)
        save(context, snap)
        Log.i(TAG, "markStatus: $status ${reason?.take(60).orEmpty()}")
    }

    /**
     * 一轮对话结束时按计划结算状态：
     * 还有 pending/in_progress 步骤 → 视为未完成（可续做）；全部 done 或没有计划 → 完成。
     */
    fun settleAfterTurn(context: Context, reason: String? = null) {
        val snap = load(context) ?: return
        val unfinished = snap.steps.any { it.status != "done" }
        if (snap.steps.isEmpty()) {
            markStatus(context, STATUS_DONE)
        } else if (unfinished) {
            markStatus(context, STATUS_INTERRUPTED, reason ?: "本轮结束仍有未完成步骤")
        } else {
            markStatus(context, STATUS_DONE)
        }
    }

    /** 清除任务（用户放弃 / 显式请求） */
    fun clear(context: Context) {
        runCatching { file(context).delete() }
        Log.i(TAG, "cleared")
    }

    // ═══════════════════════════════════════════════════
    // 读取（提示词注入 / 状态查询）
    // ═══════════════════════════════════════════════════

    /** 当前任务摘要（供 get_agent_status 展示）；无任务或已过期返回 null */
    fun summary(context: Context): String? {
        val snap = load(context) ?: return null
        if (snap.status == STATUS_DONE) return null
        return render(snap)
    }

    /**
     * 注入用提示词：TTL 内未完成的任务才返回。
     * 关键信息给全（原话 / 计划与进度 / 已产出文件 / 中断原因），模型据此决定是否续做。
     */
    fun pendingContext(context: Context): String? {
        val snap = load(context) ?: return null
        if (snap.status == STATUS_DONE) return null
        if (System.currentTimeMillis() - snap.updatedAt > TTL_MS) return null
        val body = render(snap) ?: return null
        return "[未完成的任务（可续做）]\n$body\n" +
            "若用户本轮要求继续/接着做，或明显仍在推进该任务：请从「→」那一步接着做，" +
            "不要重做已完成的步骤（已产出文件可直接用 read_code_file 读取，不必重新生成）；" +
            "若用户要放弃该任务，调用 clear_agent_task。"
    }

    private fun render(snap: Snapshot): String {
        val sb = StringBuilder()
        if (snap.goal.isNotBlank()) sb.append("用户原话：").append(snap.goal).append("\n")
        if (snap.steps.isNotEmpty()) {
            sb.append("计划进度：\n")
            snap.steps.forEachIndexed { i, s ->
                sb.append(mark(s.status)).append(" ").append(i + 1).append(". ").append(s.title).append("\n")
            }
        }
        if (snap.artifacts.isNotEmpty()) {
            val byProject = snap.artifacts.groupBy { it.project }
            sb.append("已产出：")
            sb.append(byProject.entries.joinToString("；") { (p, list) ->
                "$p/${list.joinToString("、") { it.file }}"
            })
            sb.append("\n")
        }
        if (!snap.lastError.isNullOrBlank()) sb.append("上次中断：").append(snap.lastError).append("\n")
        return sb.toString().trimEnd()
    }
}
