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
