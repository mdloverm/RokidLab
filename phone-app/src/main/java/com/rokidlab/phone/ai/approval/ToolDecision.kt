package com.rokidlab.phone.ai.approval

/**
 * L4 agent/approval —— 一次工具调用的来源。
 *
 * 限流按来源分桶（AIUI 页面可能被模型生成的 .ink 死循环刷工具，对话路径是人驱动的），
 * 部分策略也只对特定来源生效（页面域白名单只管 AIUI 页面）。
 */
enum class ToolSource(
    /** 审计日志里的来源标识（沿用改造前的字面值，LogCollector 面板的既有过滤规则不用改） */
    val id: String,
) {
    /** AIUI 页面路径（页面的 `Lab.callTool` → [com.rokidlab.phone.ai.ToolGateway]） */
    AIUI_PAGE("aiui-page"),

    /** 乐奇聊天对话路径（用户驱动的多轮 Agent 工具循环） */
    CONVERSATION("conversation"),

    /**
     * 头动规则触发路径（[com.rokidlab.phone.glasses.MotionRuleEngine] 命中规则后调用工具）。
     *
     * 单列一个来源只为了**审计归因**：日志里看到 `source=motion-rule` 就知道这次拨号/装机
     * 是用户之前设的点头/摇头规则触发的，而不是他自己刚说的话。
     * 策略面向它没有任何特判 —— 外部副作用工具照旧弹眼镜端确认（[RiskApprovalGuard] 不限来源）。
     */
    MOTION_RULE("motion-rule"),
}

/**
 * 拒绝/询问的**来源标识**。
 *
 * 只用于审计与文案归因，**不参与决策逻辑** —— 决策逻辑在各自 guard 里
 * （见 [ToolGuards]）。分开表达是为了让"谁拒的"在日志里一眼可见：
 * 排查「AI 说被安全策略挡住」时，第一件事就是看是哪个 guard 拒的。
 */
enum class DecisionOrigin {
    /** AIUI 页面域白名单 / 黑名单 */
    PAGE_SCOPE,

    /** 名字既不是真实工具也不是伪工具（模型幻觉 / 攻击构造） */
    UNKNOWN_TOOL,

    /**
     * 工具在设置页被用户**显式关闭**（[com.rokidlab.phone.ai.ToolRegistry.isEnabled] 为 false）。
     *
     * 这是 MCP 第三方工具的**准入落点**：它们首次出现时被写成显式 false，
     * 用户逐个开启才算授权。而「不下发 schema」只是省 token，模型仍可能从历史里
     * 复述出一个工具名 —— 没有这道闸门就会真被执行（开关形同虚设）。
     */
    TOOL_DISABLED,

    /**
     * 无人值守（定时自主任务）下调用了不在 [com.rokidlab.phone.ai.ToolRegistry.unattendedToolNames]
     * 白名单里的工具。装配侧本就只下发只读∪媒体白名单，此闸门防的是「模型凭记忆调出没下发的工具」。
     */
    UNATTENDED_SCOPE,

    /** per-source 滑动窗口限流 */
    RATE_LIMIT,

    /** 「本机模式」下要用到眼镜的工具 */
    GLASSES_REQUIRED,

    /** 外部副作用工具的用户确认 */
    RISK_CONFIRMATION,
}

/**
 * 工具调用审批判定（对应 DSH 的 `tools/pre-execute` 返回值）。
 *
 * ★ 为什么要有这个类型：改造前"这次调用能不能跑"这件事在**四个地方**各有各的表达 ——
 * 已删除的 `ToolPolicy.Decision`（Allow/Deny）、
 * `ToolGateway.Precheck`（Ok/Reject）、provider 内部隐式兜底（link 为 null 就自己返回错误）、
 * 以及 `AiConversationService` 里复制的一份只读判定。同一个问题四种表达，
 * 加一个策略就要改四处，且没有任何一处能说清"最终是哪个理由拒的"。
 *
 * ★ 单调性：**所有 [Deny] 都是最终拒绝**（对应 DSH 的 `ctx.tools.guard()`）——
 * 合成器遇到 Deny 立即短路，后续 guard 不再求值，也不会再去问用户确认。
 * `ctx.tools.guard()` 那种"可被后续监听者翻案的软拒绝"在我们的策略集里没有用例，
 * 需要时再引入（现在留空实现比留一个没人用的 `hard` 字段诚实）。
 */
sealed interface ToolDecision {

    /** 放行。 */
    data object Allow : ToolDecision

    /**
     * 拒绝。[reason] 会**原样**回给调用方：
     * - 对话路径 → 包成给模型看的工具结果（让它换方式）；
     * - AIUI 页面路径 → 作为 `CallResult.error` 回给页面。
     *
     * ⚠️ 页面路径的部分文案（域/黑名单/未知名）被页面侧按**字面**分支处理，
     * 改动会让页面表现为无信息的超时 —— 见 [PageScope]，那里是这些文案的唯一产地。
     */
    data class Deny(
        val origin: DecisionOrigin,
        val reason: String,
    ) : ToolDecision

    /**
     * 需要用户确认才能执行。
     *
     * 由 [ApprovalGate.preExecute] 内部用注入的 [ApprovalGate.ConfirmResolver] 解析，
     * **不会**漏给调用方（调用方只会拿到 [Allow] 或 [Deny]）。
     * [prompt] 是给用户看的操作摘要（来自 `ToolEntry.summarize`，与工具定义同源）。
     *
     * @param failClosed true = **问不到用户就拒绝**（而不是 [ApprovalGate.resolveAsk] 默认的
     *   超时/无通道降级放行）。只给「第三方远端工具」用 —— 理由见该字段的产地
     *   [ToolCallContext.failClosedConfirmation]：fail-open 的前提是工具侧自己有
     *   "未获确认时降级为无副作用动作"的保证，MCP 工具没有这个保证。
     */
    data class Ask(
        val origin: DecisionOrigin,
        val prompt: String,
        val failClosed: Boolean = false,
    ) : ToolDecision
}
