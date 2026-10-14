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
            // 🚨 必须走原子写（2026-10-07 安全修复）：5 个写入点（recordPlan/recordArtifact/
            // markStatus 等）全是 load() → 改 → save() 的**读改写**序列，进程被杀/磁盘满会留下半截 JSON；
            // 而 `load` 失败返回 null ⇒ 长任务的 goal/计划进度/已产出文件清单**静默丢失**，
            // 症状是「Agent 续做能力归零」且无报错。读端容错把损坏翻译成静默归零，比崩溃更难发现。
            com.rokidlab.phone.ai.writeTextFileAtomically(
                file(context),
                snap.toJson().toString(),
            )
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

    /**
     * 标记终态（预算触顶 / 被打断 / 完成），可附带原因
     *
     * @param userText 本轮用户原话。⚠️ **无槽位时用它建快照**（见下），有槽位时只用于
     *   补全一个空 goal，不覆盖已有 goal（goal 的"首次原话"语义见 [recordPlan]）。
     *
     * ★ 2026-10-09 行为变更：改造前 `load(context) ?: return` —— 没有任务槽位就直接不记。
     *   而槽位只在模型**主动调 update_plan / save_code_file** 时才创建，于是闲聊、问答、
     *   翻译、查资料这类任务被打断时，[markStatus] 是**空转**的：状态标记没落，
     *   下一轮 `pendingContext` 自然返回 null。
     *   症状：用户说「继续」，模型拿不到任何"上次在干什么"的提示（事件流侧的修复能保住
     *   用户原话，但"这轮是被打断的、可以接着做"这条**状态**只有这里能说）。
     *
     *   现在无槽位时建一个**只含中断原因**的快照，让续做能力覆盖全部任务类型。
     *   代价控制：goal 为空 ⇒ [pendingContext] 走退化措辞（见该方法），不会伪装成
     *   "有计划可推进"。
     */
    fun markStatus(
        context: Context,
        status: String,
        reason: String? = null,
        userText: String? = null,
    ) {
        val existing = load(context)
        if (existing == null) {
            // 只为"未完成"状态建槽位：STATUS_DONE 无槽位时本就无处可标，
            // 建一个 done 快照只会污染 pendingContext 的存在性判断（它见到 done 返回 null，
            // 但仍多一次磁盘写 + 一次 load）
            if (status == STATUS_DONE) return
            val now = System.currentTimeMillis()
            save(
                context,
                Snapshot(
                    goal = userText?.take(200).orEmpty(),
                    steps = emptyList(),
                    artifacts = mutableListOf(),
                    lastError = reason?.take(200),
                    status = status,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
            Log.i(TAG, "markStatus: $status (新建槽位) ${reason?.take(60).orEmpty()}")
            return
        }
        val snap = existing
        // 已完成的任务不被后续的"空转"标记覆写成未完成
        if (snap.status == STATUS_DONE && status != STATUS_DONE) return
        snap.status = status
        if (!reason.isNullOrBlank()) snap.lastError = reason.take(200)
        // 槽位存在但 goal 为空（当年由 update_plan 之外的路径建过）⇒ 用本轮原话补上，
        // 让「继续」至少有"用户当时要什么"可依据。已有 goal 则保持不动（见 recordPlan）
        if (snap.goal.isBlank() && !userText.isNullOrBlank()) snap.goal = userText.take(200)
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
     *
     * ★ 2026-10-09：**分两种措辞**，判据是「有没有 plan / 产物」。
     *
     * 改造前只有一套措辞，而它通篇假设存在计划（"从「→」那一步接着做"、"调用 clear_agent_task"）。
     * [markStatus] 改造后无槽位也会建快照，于是**闲聊/问答/翻译类中断**也会走到这里 ——
     * 而这类任务根本没有 plan，"从第几步接着做"是句无意义的话，模型要么忽略、要么
     * 反而去找并不存在的步骤。宁可在没有计划时说人话，也不要给一个指向虚构对象的指令。
     *
     * ★ 2026-10-12（用户实测事故）：原措辞「或明显仍在推进该任务」给了模型**自行接管整个计划**的
     * 许可 —— 用户只说「预览那个魔塔的HTML」（一分钟的活），模型却认定"明显仍在推进该任务"，
     * 转头去补计划里遗留的「修 bug」步骤：读 3 个源文件 + 长篇推理 + 一次被拦的 run_shell，
     * 22 步预算跑光，最终回复是空的。现在把续做收紧成**只有用户明确说「继续/接着做」**才接管；
     * 单点动作（预览/看一眼/导出）只做那一个动作，并用一句话告知计划里的剩余步骤。
     */
    fun pendingContext(context: Context): String? {
        val snap = load(context) ?: return null
        if (snap.status == STATUS_DONE) return null
        if (System.currentTimeMillis() - snap.updatedAt > TTL_MS) return null
        val hasPlan = snap.steps.isNotEmpty() || snap.artifacts.isNotEmpty()
        if (!hasPlan) return plainInterruptNote(snap)
        val body = render(snap)
        return "[未完成的任务（可续做）]\n$body\n" +
            "⚠️ 只有用户**本轮明确要求继续/接着做**时才从「→」那一步接着做；" +
            "若用户本轮只提出一个具体动作（例如「预览一下」「看一眼那个页面」「导出」），" +
            "**只完成这个动作并如实汇报**，不要顺手接管计划里其余未完成步骤 ——" +
            "用一句话告诉用户计划还差哪几步即可。续做时不要重做已完成的步骤" +
            "（已产出文件可直接用 read_code_file 读取，不必重新生成），并顺手把**已核实完成、或" +
            "经核实不需要做**的步骤标成 done（没跟上的状态会一直挂在「还没做的步骤」里反复汇报）；" +
            "若用户要放弃该任务，调用 clear_agent_task。"
    }

    /**
     * 预算/token 触顶时给**用户**看的确定性收尾说明（2026-10-12）。
     *
     * ## 为什么由框架拼、不走模型
     * 触顶后的收尾总结轮依赖模型自觉执行提示词（"如实说明还剩哪些步骤"）——实测不可靠：
     * 3 轮收尾没产出正文时回复落到固定兜底「抱歉，我暂时无法处理这个问题」，
     * 代码场景 `finalizeCodeGenReply` 又会把结论收敛成「已生成 N 个文件」——
     * 两条路都会**吞掉"还剩什么没做"**，用户感知就是"做到中间突然没了"。
     * 这段说明在最终回复定稿后由主循环直接追加（见 AiConversationService 的
     * budgetExhausted/tokenExhausted 分支），不经过模型。
     *
     * @param tokenExhausted true = token 预算触顶；false = 工具轮次预算触顶
     * @return 追加到回复尾部的说明文本（永远非空——即使没有快照也要说清"停在哪"）
     */
    fun budgetStopNote(context: Context, tokenExhausted: Boolean): String {
        val snap = load(context)
        val pending = snap?.steps?.filter { it.status != "done" }.orEmpty()
        return budgetStopNote(
            pendingTitles = pending.map { it.title },
            hasPlan = !snap?.steps.isNullOrEmpty(),
            tokenExhausted = tokenExhausted,
        )
    }

    /**
     * [budgetStopNote] 的**纯函数版**（措辞选择单测可锁，与 [plainInterruptNote] 同一做法）。
     *
     * @param pendingTitles 未完成步骤的标题（快照无计划/无快照时为空表）
     * @param hasPlan 快照里**存在计划**（含已全部完成的）—— 决定"没做完"还是"已完成"的措辞
     */
    internal fun budgetStopNote(
        pendingTitles: List<String>,
        hasPlan: Boolean,
        tokenExhausted: Boolean,
    ): String {
        val reason = if (tokenExhausted) "本轮 token 预算用尽" else "本轮工具轮次预算用尽"
        return buildString {
            append("（").append(reason).append("，任务先做到这里。")
            when {
                // 有未完成步骤：点名前 5 条（宁少勿错，全部清单下轮续做时会注入）
                pendingTitles.isNotEmpty() -> {
                    append("还没做的步骤：")
                    val shown = pendingTitles.take(5)
                    append(shown.joinToString("、"))
                    if (pendingTitles.size > shown.size) append("等共 ${pendingTitles.size} 步")
                    append("。")
                }
                // 有计划但全部完成：如实说完成，别吓用户"没做完"
                hasPlan -> append("计划内的步骤都已标记完成。")
                // 无计划快照：不虚构步骤（与 pendingContext 的退化措辞同一判据）
                else -> append("可能还有内容没有处理完。")
            }
            append("对我说「继续」我就接着做。）")
        }
    }

    /**
     * 无计划/无产物时的续做提示（**退化措辞**，见 [pendingContext]）。
     *
     * 只给两样东西：用户当时说了什么（若有）+ 上一轮为什么停。
     * ⚠️ 刻意**不**提"从第几步接着做"（没有计划）、也**不**提 clear_agent_task
     * （没有任务可清）—— 指向不存在对象的指令比不说更糟。
     * 指令退化为最朴素的一句：用户说继续就重新回答上面那条请求。
     *
     * ★ 参数化而非直接读 [Snapshot]：本类的读写都依赖 `Context.filesDir`，纯 JVM 单测
     * 碰不到（无 Robolectric）。而**措辞选择是这次修复的关键判据**（有/无 plan 说法
     * 不同），不能只靠真机肉眼验证 —— 抽成纯函数后由 `AgentTaskStoreTest` 直接钉住。
     */
    internal fun plainInterruptNote(goal: String?, lastError: String?): String {
        val sb = StringBuilder()
        sb.append("[上一轮被中断]\n")
        if (!goal.isNullOrBlank()) sb.append("用户当时的问题：").append(goal).append("\n")
        if (!lastError.isNullOrBlank()) sb.append("中断原因：").append(lastError).append("\n")
        sb.append(
            "用户现在说「继续」时，请重新回答上面那条请求（那一轮没有产出回复，" +
                "模型侧也没有可续接的中间状态）。若用户改主意问别的，直接答新问题即可。",
        )
        return sb.toString().trimEnd()
    }

    /** [plainInterruptNote] 的实例版（读快照的便捷入口） */
    private fun plainInterruptNote(snap: Snapshot): String =
        plainInterruptNote(snap.goal, snap.lastError)

    /**
     * 计划驱动续做提示（todo 驱动重入，2026-10-02，补评估 C 档 #7）。
     *
     * 与 [pendingContext] 的分工：pendingContext 是「跨轮续做」—— 下一轮对话开始时注入，
     * 模型自行决定是否接着做；本方法是「轮内重入」—— 模型给出收尾回复但计划未完成时，
     * 由主循环拉回来继续干，措辞更直接（点名剩余步骤 + 要求逐步 update_plan）。
     *
     * 返回 null = 无活跃计划 / 已完成 / 过期 / 步骤全部 done，调用方按正常收尾处理。
     */
    fun planContinuationPrompt(context: Context): String? {
        val snap = load(context) ?: return null
        if (snap.status == STATUS_DONE) return null
        if (System.currentTimeMillis() - snap.updatedAt > TTL_MS) return null
        val pending = snap.steps.filter { it.status != "done" }
        if (pending.isEmpty()) return null
        val sb = StringBuilder("你的任务计划还有未完成的步骤，本轮还不能收尾，请继续执行：\n")
        pending.forEach { s ->
            sb.append(mark(s.status)).append(" ").append(s.title).append("\n")
        }
        sb.append(
            "从「→」那一步接着做（已完成的不要重做）；每完成一步就用 update_plan 更新一次状态，" +
                "**经核实不需要做、或已经满足的步骤也要标成 done**（留在 in_progress 会一直出现在" +
                "「还没做的步骤」里），全部完成后把所有步骤标为 done，最后用一两句中文总结。",
        )
        if (snap.artifacts.isNotEmpty()) {
            val byProject = snap.artifacts.groupBy { it.project }
            sb.append("\n已产出（可直接用，不要重新生成）：")
            sb.append(
                byProject.entries.joinToString("；") { (p, list) ->
                    "$p/${list.joinToString("、") { it.file }}"
                },
            )
        }
        if (!snap.lastError.isNullOrBlank()) {
            sb.append("\n上次中断原因：").append(snap.lastError)
        }
        return sb.toString().trimEnd()
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
        // ⚠️ 兜底（2026-10-09）：无计划快照理论上总有 goal 或 lastError 之一，
        // 但两者都空时不能返回空串 —— [render] 的可空返回正是为"没有内容"准备的，
        // 返回 "" 会让 pendingContext/summary 把空串当成有效内容交给模型。
        if (sb.isEmpty()) sb.append("（上一轮被中断，未记录到具体内容）")
        return sb.toString().trimEnd()
    }
}
