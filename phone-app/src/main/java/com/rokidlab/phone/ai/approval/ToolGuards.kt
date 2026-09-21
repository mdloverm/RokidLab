package com.rokidlab.phone.ai.approval

import com.rokidlab.phone.ai.ToolRegistry
import com.rokidlab.phone.ai.ToolRisk

/**
 * L4 agent/approval —— 内置策略源集合。
 *
 * ★ 顺序 = 求值顺序（先命中先返回）：
 * 1. [PageScopeGuard] —— 页面域/黑名单（准入面最外层）
 * 2. [UnknownToolGuard] —— 名字压根不存在
 * 3. [ToolEnabledGuard] —— 用户把工具开关关掉了
 * 4. [UnattendedScopeGuard] —— 无人值守下的只读∪媒体白名单
 * 5. [SourceRateLimitGuard] —— 限流（**有状态**，放在纯判定之后，避免被拒的调用白扣配额）
 * 6. [GlassesDependencyGuard] —— 本机模式下的眼镜依赖
 * 7. [RiskApprovalGuard] —— 外部副作用 → 用户确认
 *
 * ⚠️ 顺序是有意的，不要随意重排：限流必须排在所有"纯判定"之后 ——
 * 改造前 AIUI 路径的准入校验在限流之前、对话路径没有准入校验，
 * 于是两个来源的"被拒调用扣不扣配额"行为不一致。现在统一为**不扣**。
 */
internal object ToolGuards {

    fun default(): List<ToolGuard> = listOf(
        PageScopeGuard(),
        UnknownToolGuard(),
        ToolEnabledGuard(),
        UnattendedScopeGuard(),
        SourceRateLimitGuard(),
        GlassesDependencyGuard(),
        RiskApprovalGuard(),
    )
}

/**
 * AIUI 页面准入域（域白名单 + 黑名单）。只对 [ToolSource.AIUI_PAGE] 生效。
 *
 * 判定与文案都在 [PageScope]（与 `ToolGateway` 的页面入口校验**共用同一份**）——
 * 这是"页面安全边界只有一个产地"的保证。
 */
internal class PageScopeGuard(
    private val allowedDomains: Set<String> = PageScope.ALLOWED_DOMAINS,
    private val denyTools: Set<String> = PageScope.DENY_TOOLS,
    private val knownTools: Set<String> = PageScope.pageVisibleTools(),
    private val toolDomain: (String) -> String? = PageScope::domainOf,
) : ToolGuard {

    override val id: String = "page-scope"

    override val sources: Set<ToolSource> = setOf(ToolSource.AIUI_PAGE)

    override fun evaluate(ctx: ToolCallContext): ToolDecision? {
        val reason = PageScope.rejectReason(
            toolName = ctx.name,
            knownTools = knownTools,
            toolDomain = toolDomain,
            allowedDomains = allowedDomains,
            denyTools = denyTools,
        ) ?: return null
        return ToolDecision.Deny(DecisionOrigin.PAGE_SCOPE, reason)
    }
}

/**
 * 名字既不是真实工具、也不是伪工具 → 单调拒绝。
 *
 * ★ 这条修掉一个真实的白等：改造前对话路径**没有**这个判定，模型幻觉出一个工具名时
 * （或历史里残留了已下线的工具名），会因为 `ToolRiskMap.riskOf` 的"未知名 = 最保守档"
 * 被判成 EXTERNAL_SIDE_EFFECT → 触发确认闸门 → 眼镜在线时**白等 35 秒**问用户
 * "是否执行 xxx"（用户一脸懵），超时后才由 `ToolRegistry.execute` 抛出"未知工具名"。
 * 现在直接拒，理由是确定的。
 *
 * ⚠️ 已知工具 = 真实工具 ∪ **伪工具**（[PseudoTools]）。漏掉伪工具会让 `load_skill`
 * 这类工具在接上闸门的瞬间被误拒 —— 单测锁死了这条。
 */
internal class UnknownToolGuard(
    private val knownTools: () -> Set<String> = PseudoTools::knownToolNames,
) : ToolGuard {

    override val id: String = "unknown-tool"

    override fun evaluate(ctx: ToolCallContext): ToolDecision? {
        if (ctx.name in knownTools()) return null
        return ToolDecision.Deny(DecisionOrigin.UNKNOWN_TOOL, "unknown tool: ${ctx.name}")
    }
}

/**
 * 工具在设置页被用户**关掉**（[ToolRegistry.isEnabled] 为 false）→ 单调拒绝。
 *
 * ★ 这是 MCP 第三方工具**准入的实际落点**：它们的开关在首次报到时被
 * [ToolRegistry.ensureDisabledByDefault] 写成显式 false，用户逐个打开才算授权。
 *
 * ⚠️ 为什么光靠"不下发 schema"不够：不下发只是**省 token**，模型仍可能从历史对话里
 * 复述出一个工具名（或用户在页面上把 schema 缓存刷成旧的），此时执行侧不查开关
 * 就真的把它跑了 —— 用户看到的开关形同虚设。装配侧（`schemasFor`）与执行侧
 * （本 guard）必须用**同一份**开关状态判定，这条才算闭环。
 *
 * ⚠️ [ToolCallContext.enabled] 为 null 时**不表态**：那是"调用方没带开关状态"
 * （单测、或没有 Context 的调用路径），不该因此把工具误判为被关掉。
 */
internal class ToolEnabledGuard : ToolGuard {

    override val id: String = "tool-disabled"

    override fun evaluate(ctx: ToolCallContext): ToolDecision? {
        val enabled = ctx.enabled ?: return null
        if (enabled) return null
        return ToolDecision.Deny(
            DecisionOrigin.TOOL_DISABLED,
            "工具 ${ctx.name} 未开启。请到「设置 → 乐奇工具」里打开它，或换用其他工具",
        )
    }
}

/**
 * 无人值守（定时自主任务）下的准入白名单：不在 [ToolRegistry.unattendedToolNames] 里的一律拒绝。
 *
 * ★ 与装配侧「同一份名单」：自主任务装配用的是 `schemasUnattended`（只读 ∪ 媒体白名单），
 * 名单外的工具**压根没下发**。本 guard 防的是"模型凭记忆复述出一个没下发的工具名"——
 * 无人监管时跑错一次（半夜拨号/装机/改设置）是真实不可逆代价，光靠"不下发"防不住。
 *
 * ⚠️ 只对 [ToolSource.CONVERSATION] 生效：自主任务的执行入口就是对话链路，
 * AIUI 页面与头动规则都不存在"无人值守"这个状态。
 */
internal class UnattendedScopeGuard : ToolGuard {

    override val id: String = "unattended-scope"

    override val sources: Set<ToolSource> = setOf(ToolSource.CONVERSATION)

    override fun evaluate(ctx: ToolCallContext): ToolDecision? {
        if (!ctx.unattended) return null
        if (ctx.name in ToolRegistry.unattendedToolNames()) return null
        return ToolDecision.Deny(
            DecisionOrigin.UNATTENDED_SCOPE,
            "自主任务只能用查询类工具（到点放歌除外）；${ctx.name} 需要你本人在场确认，本次不执行",
        )
    }
}

/**
 * per-source 滑动窗口限流。
 *
 * - **AIUI 页面 30/min**：页面是模型生成的 .ink，可能死循环刷工具。
 * - **对话路径 120/min**：人驱动的多轮 Agent 循环（一轮里多次读写文件 + 查资料是常态），
 *   上限只用于兜底失控循环。
 *
 * ⚠️ 历史教训：曾对两个来源统一用 30/min，会打断正常的工具循环 ——
 * 用户看到莫名的「调用过于频繁」。
 *
 * ⚠️ 本 guard **有状态**，必须是单例（[ToolGuards.default] 只构造一次）。
 * 单测要隔离计数时注入新实例，不要复用生产实例。
 */
internal class SourceRateLimitGuard(
    private val aiuiPageLimit: Int = 30,
    private val conversationLimit: Int = 120,
    private val windowMs: Long = 60_000L,
    private val clock: () -> Long = System::currentTimeMillis,
) : ToolGuard {

    override val id: String = "rate-limit"

    private val buckets = HashMap<String, ArrayDeque<Long>>()

    private fun limitFor(source: ToolSource): Int =
        if (source == ToolSource.AIUI_PAGE) aiuiPageLimit else conversationLimit

    override fun evaluate(ctx: ToolCallContext): ToolDecision? {
        val limit = limitFor(ctx.source)
        val accepted = synchronized(buckets) {
            val now = clock()
            val q = buckets.getOrPut(ctx.source.id) { ArrayDeque() }
            while (q.isNotEmpty() && now - q.first() > windowMs) q.removeFirst()
            if (q.size >= limit) {
                false
            } else {
                q.addLast(now)
                true
            }
        }
        if (accepted) return null
        return ToolDecision.Deny(
            DecisionOrigin.RATE_LIMIT,
            "工具调用过于频繁（每分钟上限 $limit 次），请稍后再试",
        )
    }
}

/**
 * 「本机模式」下要求眼镜在线的工具 → 拒绝。
 *
 * ★ 这是 DSH `ctx.tools.guard()` 语义的直译：**单调最终拒绝**，任何后续策略
 * （包括用户确认）都不能翻案 —— 用户已经明确说了"这次就在手机上聊"。
 *
 * ⚠️ 判据是**用户开关**（[ToolCallContext.localOnly]），不是"链路现在通不通"。
 * 按链路状态自动拒绝会在蓝牙抖动时把功能表现为「被安全策略挡住」
 * （2026-09-11 实测事故：用户已授权却打不出电话）。手动态语义明确、行为可预期。
 *
 * ⚠️ 只对 [ToolSource.CONVERSATION] 生效：AIUI 页面的代码就在眼镜上跑，
 * 不存在"本机模式"这个状态。
 */
internal class GlassesDependencyGuard : ToolGuard {

    override val id: String = "glasses-required"

    override val sources: Set<ToolSource> = setOf(ToolSource.CONVERSATION)

    override fun evaluate(ctx: ToolCallContext): ToolDecision? {
        if (!ctx.localOnly || !ctx.requiresGlasses) return null
        return ToolDecision.Deny(
            DecisionOrigin.GLASSES_REQUIRED,
            "现在是「本机模式」（不连接眼镜），${ctx.name} 需要眼镜在线才能用",
        )
    }
}

/**
 * 外部副作用工具 → 需要用户确认（[ToolDecision.Ask]）。
 *
 * 只负责"该问"这个判断；**怎么问、答不上来怎么办**由 [ApprovalGate] 统一处理
 * （fail-open：无确认通道/超时 → 降级放行，只有用户**显式取消**才拒绝）。
 * 把"问"和"答"分开是为了让本 guard 保持无 IO、可单测。
 *
 * [ToolDecision.Ask.prompt] 用 `ToolEntry.summarize` 生成 —— 摘要与工具定义同源，
 * 改参数名时会顺手改到摘要，不会出现"摘要里读的 key 早就改名了，于是永远显示空"。
 */
internal class RiskApprovalGuard : ToolGuard {

    override val id: String = "risk-confirmation"

    override fun evaluate(ctx: ToolCallContext): ToolDecision? {
        if (ctx.risk != ToolRisk.EXTERNAL_SIDE_EFFECT) return null
        return ToolDecision.Ask(
            DecisionOrigin.RISK_CONFIRMATION,
            ToolRegistry.summarizeToolCall(ctx.name, ctx.args),
            // 第三方远端工具（MCP）问不到用户时必须拒绝，不能 fail-open（见该字段的产地）
            failClosed = ctx.failClosedConfirmation,
        )
    }
}
