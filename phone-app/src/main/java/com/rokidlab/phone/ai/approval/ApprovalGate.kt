package com.rokidlab.phone.ai.approval

import android.util.Log
import com.rokidlab.phone.ai.ToolRegistry
import com.rokidlab.phone.ai.ToolRisk
import com.rokidlab.phone.ai.ToolRiskMap
import org.json.JSONObject

/**
 * L4 agent/approval —— 工具调用的**唯一审批入口**（capability seam 的 Service Definition 侧）。
 *
 * 对应 DSH 的 `tools/pre-execute` + `ctx.approval`：所有工具调用（真实工具与伪工具、
 * AIUI 页面路径与对话路径）都必须先过 [preExecute]，不再有第二条判定路径。
 *
 * ## 三层结构（为什么这样切）
 *
 * 1. [compose] —— **纯合成**：把 [ToolGuard] 列表跑成 Allow / Deny / Ask。无 IO、无 Context，
 *    可直接单测（合成语义全部锁在 `ApprovalGateTest`）。
 * 2. [resolveAsk] —— **解析**：把 Ask 变成最终结论。这是**唯一**会阻塞的地方
 *    （等眼镜端用户确认），也是 fail-open 语义的唯一产地。
 * 3. [preExecute] —— **门面**：合成 → 解析 → 审计。调用方只看到 Allow / Deny。
 *
 * ★ 把"问"和"答"分开的理由：改造前它们揉在一个 `check()` 里，导致
 * ① 合成规则不可单测（必须造一个假确认通道）；② 两个调用点各写一遍 `when` 处理结果；
 * ③ "用户显式取消才拒绝、超时/无通道降级放行"这条**安全关键语义**只写在文档里，
 * 实现散在 `if` 链中，改动时极易把 fail-open 误改成 fail-closed
 * （那会让功能表现为「被安全策略挡住」，见 `GlassToolConfirmChannel` 的历史事故）。
 *
 * ## fail-open 是刻意的
 *
 * 确认通道不可用 / 眼镜端旧版 / 用户没响应（超时）→ **放行**，由工具侧保证
 * 「未获确认时只做无副作用动作」（如 `call_phone` 只打开拨号盘，绝不自动拨出）。
 * 只有用户在眼镜上**显式回 "no"** 才拒绝。硬拒曾造成"用户已授权却打不出电话"的事故。
 */
object ApprovalGate {

    private const val TAG = "ApprovalGate"

    /** 生产策略集（[ToolGuards.default] 只构造一次 —— [SourceRateLimitGuard] 是有状态的） */
    private val defaultGuards: List<ToolGuard> by lazy { ToolGuards.default() }

    /** 仅供测试替换策略集；null = 用生产策略集（见 [resetForTest]） */
    @Volatile
    internal var guardsOverride: List<ToolGuard>? = null

    private fun activeGuards(): List<ToolGuard> = guardsOverride ?: defaultGuards

    /**
     * 副作用工具的用户确认通道（眼镜端阻塞端点，渲染"是否拨打 XXX"确认页）。
     * 常驻单例由 `GlassToolConfirmChannel.global` 注入（见 `CxrLHiRokidSession`）。
     *
     * null / [ConfirmResolver.isAvailable] 为 false 时 → **降级放行**（fail-open）。
     */
    interface ConfirmResolver {
        /** 确认通道当前是否可用（眼镜端已升级且链路在线） */
        fun isAvailable(): Boolean

        /**
         * 阻塞等待用户确认（仅在 [isAvailable] 为 true 时调用）。返回 true = 用户同意执行。
         *
         * @param prompt 给用户看的操作摘要（由闸门传入，与工具定义同源），
         *   通道负责把它渲染到眼镜端悬浮层 + TTS 播报。
         */
        fun confirm(toolName: String, prompt: String): Boolean

        /** 上一次确认是否被用户**显式取消**（true=取消 → 拒绝；false=超时/未响应 → 降级放行） */
        fun wasCancelled(): Boolean = false
    }

    @Volatile
    var confirmationResolver: ConfirmResolver? = null

    // ════════════════════════════════════════════════════════════════════
    // 门面：唯一对外入口
    // ════════════════════════════════════════════════════════════════════

    /**
     * 工具执行前的统一审批（同步；可能阻塞至多 ~35s 等用户确认）。
     *
     * 必须在**非主线程**调用（AIUI 页面网关的 worker 线程 / 对话的工具循环线程都满足）。
     *
     * ⚠️ 调用方**每轮对话**只该调一次：瞬时失败重试是同一个动作的重放，
     * 不该重复扣限流配额、更不该重复弹确认（见 `AiConversationService.runTool`）。
     *
     * @param source    调用来源（决定限流分桶与部分策略是否生效）
     * @param localOnly 乐奇聊天「本机模式」（不经眼镜）；仅 [ToolSource.CONVERSATION] 有意义
     * @param unattended 定时自主任务（无人值守）：只放行 [ToolRegistry.unattendedToolNames]；
     *   仅 [ToolSource.CONVERSATION] 有意义
     * @param context   读工具开关（[ToolRegistry.isEnabled]）用；为 null 则开关闸门不表态
     * @return 只会是 [ToolDecision.Allow] 或 [ToolDecision.Deny]（Ask 已在内部解析）
     */
    fun preExecute(
        source: ToolSource,
        name: String,
        args: JSONObject,
        localOnly: Boolean = false,
        unattended: Boolean = false,
        context: android.content.Context? = null,
    ): ToolDecision {
        val ctx = ToolCallContext(
            source = source,
            name = name,
            args = args,
            localOnly = localOnly,
            unattended = unattended,
            // 开关状态在这里结算（判定链本身只吃纯数据）：context 为 null 时保持 null，
            // 于是开关闸门不表态 —— 与旧行为一致
            enabled = context?.let { ToolRegistry.isEnabled(it, name) },
        )
        val composed = compose(ctx)
        val resolved = if (composed is ToolDecision.Ask) resolveAsk(composed, name) else composed
        audit(ctx, resolved)
        return resolved
    }

    // ════════════════════════════════════════════════════════════════════
    // 第一层：纯合成（无 IO，可单测）
    // ════════════════════════════════════════════════════════════════════

    /**
     * 把多个 guard 的判定合成为一个结论（waterfall / DSH 的 `tools/pre-execute` 语义）。
     *
     * 规则（顺序即优先级）：
     * 1. 任一 guard 返回 [ToolDecision.Deny] → **立即短路返回**，后续 guard 不再求值。
     *    拒绝是单调的：这是 DSH `ctx.tools.guard()` 的语义，也是"用户已明确说本机模式"
     *    这类结论必须能压过"要不要确认一下"的原因。
     * 2. 全部求值完毕、有 guard 返回 [ToolDecision.Ask] → 返回**第一个** Ask
     *    （按 [ToolGuards] 的顺序，即"最先提出的那个问题"）。
     * 3. 其余情况 → [ToolDecision.Allow]。
     *
     * `null` = 该 guard 不表态（不是拒绝，也不是放行），继续往下走。
     */
    internal fun compose(
        ctx: ToolCallContext,
        guards: List<ToolGuard> = activeGuards(),
    ): ToolDecision {
        var pendingAsk: ToolDecision.Ask? = null
        for (guard in guards) {
            if (ctx.source !in guard.sources) continue
            when (val decision = guard.evaluate(ctx) ?: continue) {
                is ToolDecision.Deny -> return decision
                is ToolDecision.Ask -> if (pendingAsk == null) pendingAsk = decision
                ToolDecision.Allow -> Unit
            }
        }
        return pendingAsk ?: ToolDecision.Allow
    }

    // ════════════════════════════════════════════════════════════════════
    // 第二层：Ask 解析（唯一会阻塞的地方 / fail-open 语义唯一产地）
    // ════════════════════════════════════════════════════════════════════

    /**
     * 把 [ToolDecision.Ask] 解析成最终结论。
     *
     * | 情况 | 结论 | 理由 |
     * |---|---|---|
     * | 无通道 / 通道不可用（未连接、眼镜端旧版） | **放行** | 硬拒会让功能表现为「被安全策略挡住」 |
     * | 用户**显式取消** | 拒绝 | 唯一的"用户说不" |
     * | 超时 / 无响应 / 通道抛异常 | **放行** | 同第一行；工具侧只做无副作用动作 |
     *
     * ⚠️ 三个分支的区分依据是 [ConfirmResolver.wasCancelled] ——
     * "取消"和"超时"在眼镜端都是 `allowed=false`，**只能靠这个标志区分**。
     * 早期实现不区分，导致"用户点错一次 / 眼镜没响应"被当成拒绝，
     * 用户侧看到「你已在眼镜上取消」但自己根本没操作。
     *
     * ⚠️ 例外：[ToolDecision.Ask.failClosed] 为 true（第三方远端 MCP 工具）时**不适用上表** ——
     * "问不到"（无通道/超时）也会拒绝。理由：fail-open 的前提是工具侧自己有
     * "未获确认时降级为无副作用动作"的保证，MCP 工具由第三方 server 实现，没有这个保证。
     */
    internal fun resolveAsk(ask: ToolDecision.Ask, toolName: String): ToolDecision {
        val channel = confirmationResolver
        if (channel == null || !channel.isAvailable()) {
            // 问不到人：默认降级放行；第三方远端工具则拒绝（理由见上）
            return if (ask.failClosed) denyUnconfirmed(toolName, "当前无法向你确认") else ToolDecision.Allow
        }
        val confirmed = runCatching { channel.confirm(toolName, ask.prompt) }.getOrDefault(false)
        if (confirmed) return ToolDecision.Allow
        if (channel.wasCancelled()) {
            return ToolDecision.Deny(
                DecisionOrigin.RISK_CONFIRMATION,
                "你已在眼镜上取消，操作未执行：'$toolName'",
            )
        }
        // 超时/未响应：默认放行；第三方远端工具则拒绝
        return if (ask.failClosed) denyUnconfirmed(toolName, "你没有确认") else ToolDecision.Allow
    }

    /**
     * [ToolDecision.Ask.failClosed] 的拒绝文案。
     *
     * 必须给出**可操作的出路**：否则用户只会看到"AI 说被安全策略挡住"而不知道下一步做什么。
     * 两条出路对应两个真实开关 —— 连上眼镜（走确认）或把 server 标为信任（免逐次确认，
     * 见 `McpServersPage` 的「信任此服务器」）。
     */
    private fun denyUnconfirmed(toolName: String, why: String): ToolDecision = ToolDecision.Deny(
        DecisionOrigin.RISK_CONFIRMATION,
        "$why，第三方工具 '$toolName' 未执行。它来自外部服务器（无法保证未确认时不产生副作用），" +
            "请连接眼镜后重试以确认本次调用，或在「设置 → MCP 服务器」里把该服务器标为信任",
    )

    // ════════════════════════════════════════════════════════════════════
    // 风险表的另外两个用途（同一份声明的三个消费者）
    // ════════════════════════════════════════════════════════════════════

    /**
     * 工具是否「只读」—— 用于对话路径的**轮次预算分账**（只读轮 8 / 动作轮 6）。
     *
     * 与审批闸门共用同一张风险表（含 [PseudoTools]），因此
     * "算不算只读"与"要不要确认"不会出现两套互相矛盾的结论。
     *
     * 改造前这 20 行判定复制在 `AiConversationService` 里，还额外硬编码了三个伪工具名 ——
     * 新增伪工具时极易漏改（漏了就把只读工具算成动作轮，白吃预算）。
     */
    fun isReadOnly(name: String): Boolean = riskOf(name) == ToolRisk.READ_ONLY

    /** 工具风险档（伪工具优先，未知名字保持最保守的 EXTERNAL_SIDE_EFFECT） */
    fun riskOf(name: String): ToolRisk =
        PseudoTools.riskOf(name) ?: ToolRiskMap.riskOf(name)

    /** 名字是否指向一个真实存在（或伪工具意义上存在）的工具 */
    fun isKnownTool(name: String): Boolean = name in PseudoTools.knownToolNames()

    // ════════════════════════════════════════════════════════════════════
    // 审计
    // ════════════════════════════════════════════════════════════════════

    /**
     * 每次决策一行日志（LogCollector 的「乐奇聊天 → 工具 → 查看日志」可直接观测）。
     *
     * 格式对齐改造前的 `ToolPolicy: audit: ...`，只多带一个 `[ORIGIN]` ——
     * 排查「AI 说被安全策略挡住」时，第一件事就是看**是哪个 guard 拒的**。
     *
     * ⚠️ TAG 从 `ToolPolicy` 改成了 `ApprovalGate`（类已合并）。日志面板若有按 TAG
     * 过滤的规则，需要一起改。
     */
    private fun audit(ctx: ToolCallContext, decision: ToolDecision) {
        val head = "audit: source=${ctx.source.id} tool=${ctx.name} risk=${ctx.risk}"
        when (decision) {
            is ToolDecision.Allow -> Log.i(TAG, "$head -> ALLOW")
            is ToolDecision.Deny -> Log.i(TAG, "$head -> DENY [${decision.origin}] ${decision.reason}")
            // Ask 不会流到这里（preExecute 内部已解析），保留分支以防未来直接调 compose
            is ToolDecision.Ask -> Log.i(TAG, "$head -> ASK [${decision.origin}] ${decision.prompt}")
        }
    }

    // ════════════════════════════════════════════════════════════════════
    // 测试支撑
    // ════════════════════════════════════════════════════════════════════

    /** 复位注入状态（确认通道 + 策略集）。仅测试调用。 */
    internal fun resetForTest() {
        guardsOverride = null
        confirmationResolver = null
    }
}
