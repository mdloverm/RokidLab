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
     */
    data class Ask(
        val origin: DecisionOrigin,
        val prompt: String,
    ) : ToolDecision
}
