package com.rokidlab.phone.ai.approval

import com.rokidlab.phone.ai.ToolRegistry
import com.rokidlab.phone.ai.ToolRisk
import com.rokidlab.phone.ai.ToolRiskMap
import org.json.JSONObject

/**
 * 一次工具调用的判定输入。
 *
 * ★ 刻意**不带 Context / 不发网络请求 / 不碰会话**：全部字段都是从注册表或调用方
 * 已经算好的纯数据。这样每个 guard 都能直接单测，不必起 Android 环境，
 * 也不会出现"判定过程自己又触发了一次副作用"这种循环依赖。
 */
internal data class ToolCallContext(
    val source: ToolSource,
    val name: String,
    val args: JSONObject,
    /**
     * 乐奇聊天「本机模式」（`LabApplication.chatLocalOnlyEnabled`）：
     * 用户明确选择不经眼镜，因此需要眼镜的工具一律拒绝（见 [GlassesDependencyGuard]）。
     *
     * ⚠️ 判据是**这个开关**，不是"链路现在通不通"。链路抖动时若按"不通"拒绝，
     * 会重演 2026-09-11「用户已授权却打不出电话，表现为被安全策略挡住」的事故。
     */
    val localOnly: Boolean = false,
    /**
     * 无人值守模式（定时自主任务）：只有 [ToolRegistry.unattendedToolNames] 里的工具准跑。
     *
     * ⚠️ 与 [localOnly] 正交：那是"用户这次就在手机上聊"，这是"没人看着，别乱动"。
     * 装配侧（`schemasUnattended`）已经不下发名单外的工具，本字段让**执行侧**与之一致 ——
     * 否则模型凭历史复述出 `call_phone` 仍会被执行（无人监管下拨号/装机是真实不可逆代价）。
     */
    val unattended: Boolean = false,
    /**
     * 工具开关状态（[ToolRegistry.isEnabled]）：**调用方算好**带进来，null = 不知道。
     *
     * 为什么不让 guard 自己读 SharedPreferences：本类刻意只装纯数据（见类 KDoc），
     * 判定链不该碰 Context —— 否则 [ToolEnabledGuard] 就成了唯一必须起 Android 环境
     * 才能测的策略。调用方 [ApprovalGate.preExecute] 手里本来就有 Context，
     * 读一次开关再传进来，语义完全等价，而整条判定链变成可单测的纯函数。
     */
    val enabled: Boolean? = null,
) {
    /**
     * 风险档：伪工具（[PseudoTools]）→ 真实工具声明 → 未知名保守兜底。
     *
     * ⚠️ 顺序不能反：伪工具不在 `ToolRegistry.toolList` 里，若先问 [ToolRiskMap]
     * 会命中"完全未知的名字 → EXTERNAL_SIDE_EFFECT"的最保守兜底 ——
     * 于是 `load_skill` 这种纯读工具会被要求眼镜端确认，通道不可用就整条技能链失败。
     */
    val risk: ToolRisk
        get() = PseudoTools.riskOf(name) ?: ToolRiskMap.riskOf(name)

    /** 工具所属域（伪工具无域；未知名返回 null） */
    val domain: String?
        get() = ToolRegistry.toolList.firstOrNull { it.name == name }?.group

    /**
     * 「问不到用户就必须拒绝」（[ToolDecision.Ask.failClosed]）—— 第三方远端工具的判据。
     *
     * ★ 为什么只给 MCP 破例：fail-open 的前提是**工具侧自己**有"未获确认时降级为无副作用动作"
     * 的保证（`call_phone` 只打开拨号盘不自动拨出、装机只到安装确认页，见 [ApprovalGate] 的 KDoc）。
     * MCP 工具是**第三方 server** 实现的，我们无从保证这件事 —— 眼镜不在线/用户没响应时
     * 静默放行，就等于「外网第三方在我们不知情时替用户执行了动作」，而且是不可撤销的远端副作用。
     * 与其赌对端实现得克制，不如在问不到人时**明确拒绝**并让用户看到原因
     * （提示里给出两条可操作出路：连上眼镜确认，或在设置页把该 server 标为信任）。
     */
    val failClosedConfirmation: Boolean
        get() = domain == ToolRegistry.DOMAIN_MCP

    /** 是否要求眼镜在线（由 `ToolEntry.requiresGlasses` 派生） */
    val requiresGlasses: Boolean
        get() = name in ToolRegistry.GLASSES_REQUIRED_TOOLS
}

/**
 * 审批策略源（capability seam 的 **Provider** 侧）。
 *
 * 每个 guard 只负责回答一个窄问题，返回 `null` = **本 guard 不表态**
 * （waterfall 语义，对应 DSH 的 `tools/pre-execute` 逐层传递）。合成规则见
 * [ApprovalGate.compose]：任一 Deny 立即短路；全是"不表态/Allow"时，第一个 Ask 胜出。
 *
 * ★ 新增一条策略 = 实现本接口 + 在 [ToolGuards.default] 里挂上，
 * 不再需要在两个调用点各写一遍 `when`。
 */
internal interface ToolGuard {

    /** 审计标识（写进日志，排查"谁拒的"靠它） */
    val id: String

    /**
     * 本 guard 对哪些来源生效。默认全部。
     *
     * 例：页面域白名单只对 [ToolSource.AIUI_PAGE] 有意义 —— 对话路径没有"页面域"这个概念。
     */
    val sources: Set<ToolSource>
        get() = ToolSource.values().toSet()

    /**
     * 判定。[ToolCallContext] 里的字段都是纯数据，实现里**不要**做阻塞 IO ——
     * 需要用户确认的返回 [ToolDecision.Ask] 即可，阻塞由 [ApprovalGate] 统一负责。
     */
    fun evaluate(ctx: ToolCallContext): ToolDecision?
}
