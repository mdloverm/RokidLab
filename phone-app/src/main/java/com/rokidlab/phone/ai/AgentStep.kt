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
     * - [Kind.THINKING]：**恒为空**。思考类的文案（"思考中…" / "已思考"）由 UI 层按
     *   [state] + [detail] 本地化渲染 —— 服务层不该产出面向用户的文案（i18n 归属 UI）。
     */
    val title: String,
    /** 次要说明：工具参数摘要 / 结果摘要 / 思考预览。可空 */
    val detail: String = "",
    val state: State = State.RUNNING,
) {
    enum class Kind {
        /** 模型思考（含未开启长思考时的"等待模型响应"） */
        THINKING,

        /** 工具调用 */
        TOOL,
    }

    enum class State {
        /** 进行中 */
        RUNNING,

        /** 成功结束 */
        OK,

        /** 失败（工具抛错、被安全策略拦截、重试耗尽） */
        FAILED,
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
         * 构造/更新一条思考步骤。
         *
         * @param round 第几轮工具循环，用作覆盖键（每轮一条）
         * @param detail 已累积的推理文本（思考未开启时为空）。**只作展示预览**，
         *   其长度即 UI 显示的"已思考 N 字"。
         * @param state RUNNING=正在等待模型；OK=本轮已出结果
         */
        fun thinking(
            round: Int,
            detail: String = "",
            state: State = State.RUNNING,
        ): AgentStep = AgentStep(
            key = "think:$round",
            kind = Kind.THINKING,
            title = "",
            detail = compact(detail, MAX_DETAIL_CHARS),
            state = state,
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
