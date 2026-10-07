package com.rokidlab.phone.ai

/**
 * Agent 一轮回答里的**一步过程**（思考 / 工具调用），供手机端聊天窗口的「过程」时间线渲染。
 *
 * 背景：工具调用的进度文案其实一直有算（[ToolRegistry.statusText]），但只经
 * `AiConversationService.sendGlassesProgress` 推给**眼镜**，另外落进 App 内日志面板
 * （`CxrLHiRokidSession.onStatus` 默认只 `LogCollector.i`）——手机端对话窗口因此
 * 完全看不到「调用了什么工具、结果如何」，表现为一段长时间的黑盒等待。
 * 本类就是补上「过程」这条 UI 通道的数据载体。
 *
 * 交付方式是 **App 级全局汇聚点**（`AiConversationService.setAgentTraceSink`，在
 * `LabApplication.setCxrL` 注册），不是逐次调用传参 —— AI 入口有 4 条（打字 / 眼镜语音 ASR /
 * 拍照答题 / 定时自主任务），传参式设计漏一条那条路就完全没有过程显示（实测漏掉了眼镜语音）。
 *
 * **同 [key] 覆盖**：一次工具调用先发 RUNNING（"正在执行…"）、完成后用同一 key 再发一次
 * OK/FAILED 覆盖，UI 侧无需自己做状态机。约定 key：
 *  - 工具：`tool:<tool_call_id>`（每次调用唯一，多轮同名工具互不覆盖）
 *  - 思考：`think:<round>`（第几轮工具循环）
 *  - 知识库检索：`kb:auto`（一轮最多一条，是主循环的自动检索而非模型调用）
 *
 * 纯数据类，**不含任何 Android 依赖**，因此可被 `store/ChatHistoryStore`（纯 JVM，见其类注释）
 * 直接序列化，也可被 JVM 单测覆盖。
 */
data class AgentStep(
    /** 覆盖键：同 key 的后续步骤替换先前步骤（见类注释） */
    val key: String,
    val kind: Kind,
    /**
     * 主标题。
     *
     * - [Kind.TOOL]：工具名**原样**（`play_song` / `save_code_file`），不翻译 —— 它就是模型
     *   实际调用的标识符，用户在排查时需要对得上日志。
     *   ⚠️ **这里存的是标识符，不是显示名**：面向用户的名字由 UI 层用
     *   `ToolRegistry.displayNameOf()` 换成"当前时间" / "服务器名 · 工具名"。
     *   因此**不要把显示名写进来** —— 那会污染历史数据（改名/换语言后旧消息不会跟着变），
     *   也断掉与日志的对应关系。
     * - [Kind.THINKING] / [Kind.PLAN]：**恒为空**。文案由 UI 层按 [state] 本地化渲染。
     */
    val title: String,
    /** 次要说明：工具参数摘要 / 结果摘要 / 思考预览。可空 */
    val detail: String = "",
    val state: State = State.RUNNING,
    /**
     * [Kind.PLAN] 专用：结构化计划步骤（来自 `update_plan` 伪工具）。
     * UI 渲染成勾选清单而非工具时间线；空 = 非计划步骤。
     */
    val planSteps: List<AgentPlan.PlanStep> = emptyList(),
    /**
     * [Kind.THINKING] 专用：推理全文（仅结束态推送一次）。
     * [detail] 只存一行预览；用户在过程卡片上点击展开时看这份。落盘时由序列化层截断。
     */
    val fullText: String = "",
) {
    enum class Kind {
        /** 模型思考（含未开启长思考时的"等待模型响应"） */
        THINKING,

        /** 工具调用 */
        TOOL,

        /**
         * **知识库自动检索** —— 注意它**不是模型调用的工具**。
         *
         * 对话主循环在调模型**之前**会用当前提问去本地知识库自动检索、把命中段落注入
         * system 提示词（`AiConversationService` 的自动 RAG）。这条路径不产生 tool_call，
         * 因此既没有 `tool:<id>` 也没有工具名 —— 早先它就**在「过程」里完全不可见**：
         * 用户只看到"AI 直接回答了"，不知道答案其实是从自己导入的文档里查出来的。
         *
         * 单独一个 Kind 而不是复用 [TOOL]，是为了**如实**：TOOL 行意味着"模型决定调了工具"，
         * 排查"模型为什么没调检索工具"时把两者混在一起会误导。
         */
        KNOWLEDGE,

        /**
         * **任务计划清单**（`update_plan` 伪工具）：模型为多步任务立的步骤计划与进度。
         *
         * 同 `plan:auto` 键覆盖：模型每次更新计划都整体替换上一版（UI 永远只显示最新计划），
         * 渲染成勾选清单（pending/in_progress/done），不进工具时间线。
         */
        /**
         * **压缩历史**（上下文溢出恢复，2026-10-06 审计加）。
         *
         * 服务端以"输入超长"拒绝后，框架会压缩历史并原地重试同一轮。
         * 之前这个过程**只有一行 Log** —— 用户只觉得"这轮怎么这么慢"，
         * 不知道自己之前提过的那些历史被压掉了。
         * 这是**用户应该知道**的事（它改变了后续回答的基础），
         * 与 [TOOL]（模型自己调了什么）、[THINKING]（模型在想）都不同。
         */
        COMPRESS,

        PLAN,
    }

    enum class State {
        /** 进行中 */
        RUNNING,

        /**
         * **正在等用户点头确认**（2026-10-06 审计加）。
         *
         * ## 为什么必须与 [RUNNING] 区分开
         * 副作用工具执行前要过审批闸门，而闸门是**同步阻塞**的：
         * 眼镜 35s / 手机 40s / AIUI 页面 18s（[com.rokidlab.phone.ai.approval.ApprovalGate]）。
         * 原来这条步骤在闸门**之前**就以 [RUNNING] 发出，于是过程卡片上一个点
         * **转圈 40 秒且没有任何"等你确认"的字样** ——
         * 用户无法区分"正在拨号"和"正在等我点头"，会以为卡死，
         * 然后去点别的工具 ⇒ 可能触发第二个审批弹窗。
         *
         * 它等的恰恰是**最危险的副作用动作**（发短信/ 卸应用 / 删文档），
         * 让用户知道"现在轮到你"比让他知道"我在忙"重要得多。
         */
        AWAITING_CONFIRM,

        /** 成功结束 */
        OK,

        /** 失败（工具抛错、被安全策略拦截、重试耗尽） */
        FAILED,

        /**
         * **用户明确取消**（2026-10-06 审计加）。
         *
         * ★ 与 [FAILED] 区分的理由很实际：用户自己点的"取消"，回来看到一圈红点 + "失败"，
         * 等于告诉他"你的操作失败了" —— 而实际发生的是"你说了不做，它就没做"。
         * 后者不是错误，是**用户的决定**。
         */
        CANCELLED,
    }

    /**
     * 工具结果摘要的最大展示长度。
     *
     * 工具回给模型的结果可能很长（`dumpsys`、文件清单、源码全文），全量塞进 UI 会把
     * 对话列表撑成瀑布。这里只留开头一段供用户确认"确实拿到了东西"，详情看 App 内日志面板。
     */
    companion object {
        const val MAX_DETAIL_CHARS = 240

        /** 工具参数摘要长度：够看清 `{songName:"西厢"}` 这类关键入参即可 */
        const val MAX_ARGS_CHARS = 120

        /**
         * 推理全文落盘/内存展示上限。单轮长思考实测可达 ~2.5 万字符，
         * 全量进聊天历史文件会把 JSONL 撑大；超出的尾部对"回顾怎么想的"价值也低。
         */
        const val MAX_FULL_TEXT_CHARS = 8_000

        /**
         * 构造一条工具调用的过程步骤。
         *
         * @param callId 模型的 tool_call id，用作覆盖键
         * @param state 进行中 / 成功 / 失败
         * @param argsRaw 原始参数 JSON（模型可能给出非法 JSON，这里只做字符串截断，不做解析）
         * @param result 工具返回文本（仅结束态传入）
         */
        fun tool(
            callId: String,
            name: String,
            state: State,
            argsRaw: String = "",
            result: String? = null,
        ): AgentStep {
            val args = compact(argsRaw, MAX_ARGS_CHARS)
            val detail = when {
                result != null -> buildString {
                    if (args.isNotEmpty()) append(args).append(' ')
                    append("→ ").append(compact(result, MAX_DETAIL_CHARS))
                }
                args.isNotEmpty() -> args
                else -> ""
            }
            return AgentStep(
                key = "tool:$callId",
                kind = Kind.TOOL,
                title = name,
                detail = detail,
                state = state,
            )
        }

        /**
         * 构造一条「**正在等你确认**」的工具步骤（2026-10-06 审计加）。
         *
         * ## 为什么需要单独一条，而不是把 RUNNING 的 detail 改一下
         * 覆盖键必须与后续终态**相同**（`tool:<callId>`），否则闸门返回后
         * 追加的 OK/FAILED 会变成"新的一行"，用户看到"转圈那条 + 结果那条"两条。
         * 而 [tool] 会把 `result`拼进 detail（形如 `参数 → 结果`），
         * 等待阶段没有结果、也不该显示参数 —— 故单开一个工厂，
         * 只改 state 与 detail，其余（key/kind/title）完全一致 ⇒ 天然被后续终态覆盖。
         *
         * @param reason 给用户看的说明（为什么不问了：没人可问 / 通道不可用）
         */
        fun awaitingConfirm(
            callId: String,
            name: String,
            argsRaw: String = "",
            reason: String = "",
        ): AgentStep = AgentStep(
            key = "tool:$callId",
            kind = Kind.TOOL,
            title = name,
            detail = compact(
                listOf(argsRaw, reason).filter { it.isNotBlank() }.joinToString(" "),
                MAX_ARGS_CHARS,
            ),
            state = State.AWAITING_CONFIRM,
        )

        /**
         * 构造一条「**用户已取消**」的工具步骤（2026-10-06 审计加）。
         *
         * 与 [tool] 同 key ⇒ 覆盖掉那条转圈的步骤；state 区别在于 UI 上呈现为
         * 中性的"已取消"而不是红色失败（用户自己点的取消不是错误）。
         */
        fun cancelled(callId: String, name: String, reason: String = "你已取消本次操作"): AgentStep =
            AgentStep(
                key = "tool:$callId",
                kind = Kind.TOOL,
                title = name,
                detail = reason,
                state = State.CANCELLED,
            )

        /**
         * 构造一条**子代理内层**工具调用的过程步骤（2026-10-06 审计加）。
         *
         * ## 为什么需要它
         * 子代理（[com.rokidlab.phone.ai.subagent.ReadOnlySubagent]）最多跑 [MAX_ROUNDS_CAP] 轮，
         * 每轮内含多个只读工具（联网搜索 / 读网页 / 查知识库），此前**全部只进 logcat** ——
         * 用户在过程时间线上只看到外层一行 `research_subtask` 在转圈，最长 90 秒。
         * 这是唯一一条"用户能明确感到它在骗我"的路径：90 秒的阻塞操作，界面只有 1 行。
         *
         * @param key **必须带调用方作用域前缀**（主循环用 `sub:<callId>:<round>:<tool>`）：
         *   [tool] 用的是 `tool:<callId>`，若沿用同一 key 空间会互相覆盖，
         *   子代理里同名工具调两次会挤掉主循环那一行。
         */
        fun subStep(
            key: String,
            name: String,
            state: State,
            result: String? = null,
        ): AgentStep = AgentStep(
            key = key,
            kind = Kind.TOOL,
            title = name,
            detail = compact(result.orEmpty(), MAX_DETAIL_CHARS),
            state = state,
        )

        /**
         * 构造/更新一条思考步骤。
         *
         * @param round 第几轮工具循环，用作覆盖键（每轮一条）
         * @param detail 已累积的推理文本（思考未开启时为空）。**只作展示预览**：
         *   UI 原样显示在标题下方，不做字数统计、也不据此改文案。
         * @param state RUNNING=正在等待模型；OK=本轮已出结果
         * @param fullText 推理全文（仅 [state] = OK 时由调用方传一次）：UI 点击思考行展开查看。
         *   不落 UI 预览通道，由落盘层按 [MAX_FULL_TEXT_CHARS] 截断。
         */
        fun thinking(
            round: Int,
            detail: String = "",
            state: State = State.RUNNING,
            fullText: String = "",
        ): AgentStep = AgentStep(
            key = "think:$round",
            kind = Kind.THINKING,
            title = "",
            detail = compact(detail, MAX_DETAIL_CHARS),
            state = state,
            fullText = fullText,
        )

        /**
         * 构造一条**任务计划**步骤（`update_plan` 伪工具的结构化呈现）。
         *
         * 同 `plan:auto` 键：模型每更新一版计划就整体覆盖上一版，UI 只渲染最新清单。
         * [detail] 同步压一行进度摘要（如「2/5 · 正在查询天气」），保证不支持新 Kind 的
         * 旧渲染路径/眼镜单行通道也有可读文本。
         */
        fun plan(steps: List<AgentPlan.PlanStep>): AgentStep {
            val done = steps.count { it.status == "done" }
            val current = steps.firstOrNull { it.status == "in_progress" }
                ?: steps.firstOrNull { it.status == "pending" }
            val summary = if (current != null && done < steps.size) {
                "$done/${steps.size} · ${current.title}"
            } else {
                "${steps.size}/${steps.size}"
            }
            // ★ 截断后补占位：让"只显示了前8 步"这件事在 UI 上可见，而不是静默丢一半。
            // 取前 8 步而不是"最后 8 步"：计划是顺序执行的，当前进度在前面的可能性更高。
            val visible = steps.take(MAX_PLAN_STEPS_VISIBLE)
            val hidden = steps.size - visible.size
            val shown = if (hidden > 0) {
                visible + AgentPlan.PlanStep(
                    title = "还有 $hidden 步未显示（计划共 ${steps.size} 步）",
                    status = "pending",
                )
            } else {
                visible
            }
            return AgentStep(
                key = "plan:auto",
                kind = Kind.PLAN,
                title = "",
                detail = compact(summary, MAX_DETAIL_CHARS),
                state = if (done == steps.size) State.OK else State.RUNNING,
                planSteps = shown,
            )
        }

        /** 计划清单最多显示几步（超出补"还有 N 步"占位，见 [plan]） */
        const val MAX_PLAN_STEPS_VISIBLE = 8

        /**
         * 构造一条「压缩了历史」过程步骤（2026-10-06 审计加）。
         *
         * ## 为什么单独一个 Kind，而不混进语义
         * [THINKING] 的含义是"模型在想" —— 压缩不是模型行为，
         * 是**框架改变了后续回答的基础**（历史被压缩了）。
         * 用 THINKING 表达会让用户以为"它在思考"，而事实是"我为你的问题省了地方"。
         * 分开说才如实 —— 用户跟着追问时必须知道自己之前说过的东西被压掉了。
         */
        fun compressed(detail: String): AgentStep = AgentStep(
            key = "compress:auto",
            kind = Kind.COMPRESS,
            title = "",
            detail = compact(detail, MAX_DETAIL_CHARS),
            state = State.OK,
        )

        /**
         * 构造一条**知识库自动检索**步骤（主循环的 RAG，不是模型调的工具，见 [Kind.KNOWLEDGE]）。
         *
         * 文案归属：标题由 UI 按 [state] 本地化（与 [thinking] 同规矩），这里只填**数据**——
         * 命中了哪几块（`《文档名》第N块`）或"库里有多少份却没匹配上"。
         * 这几条来源正是用户核对"答案是从哪来的"的依据，所以**不要**在 UI 侧再截断掉。
         *
         * @param hitCount 命中段数（0 = 库里有内容但没匹配上）
         * @param sources 命中来源标注（如 `《问.txt》第1块`）
         * @param docCount 知识库文档总数（未命中时用于说明"库里确实有东西"）
         */
        fun knowledge(
            hitCount: Int,
            sources: List<String> = emptyList(),
            docCount: Int = 0,
        ): AgentStep = AgentStep(
            key = "kb:auto",
            kind = Kind.KNOWLEDGE,
            title = "",
            detail = compact(
                if (hitCount > 0 && sources.isNotEmpty()) sources.joinToString("、")
                else if (docCount > 0) "库里 $docCount 份文档，未匹配到相关段落"
                else "未匹配到相关段落",
                MAX_DETAIL_CHARS,
            ),
            state = State.OK,
        )

        /**
         * 工具的返回是否应判定为失败。
         *
         * 工具契约是「回一句人话」而不是抛异常，所以失败只能按文案识别。只认几个**由框架
         * 自己产出**的确定性前缀/关键字（重试耗尽、被策略拦截），不按业务文案猜（"没有找到歌曲"
         * 这类属于正常返回，只是结果不理想，仍算 OK）。
         */
        fun isFailureResult(result: String?): Boolean {
            val r = result?.trim().orEmpty()
            if (r.isEmpty()) return true
            return r.startsWith("工具执行失败") ||
                r.contains("被安全策略拦截") ||
                r.startsWith("unknown tool") ||
                r.startsWith("未知工具") ||
                r.startsWith("tool '")
        }

        /** 压成一行并截断（换行会让「过程」卡片高度失控） */
        private fun compact(raw: String, max: Int): String {
            val oneLine = raw.replace('\n', ' ').replace('\r', ' ').replace(Regex("\\s+"), " ").trim()
            if (oneLine.length <= max) return oneLine
            // 不切断 UTF-16 代理对（生僻字/emoji 会产生半个字符，Compose 渲染成方块）
            var end = max
            if (Character.isHighSurrogate(oneLine[end - 1])) end -= 1
            return oneLine.substring(0, end) + "…"
        }
    }
}
