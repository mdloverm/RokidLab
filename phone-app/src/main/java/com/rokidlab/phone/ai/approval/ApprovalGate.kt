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
 * ## 「问不到用户时怎么办」由工具自己声明，不是猜域
 *
 * 确认通道不可用 / 眼镜端旧版 / 用户没响应（超时）时，闸门按工具声明的
 * [com.rokidlab.phone.ai.ToolConfirmPolicy] 分流：
 *  - [com.rokidlab.phone.ai.ToolConfirmPolicy.PROCEED]（影响不出本机、可重做：删文件、装包）→
 *    **放行**。硬拒会让功能表现为「被安全策略挡住」，正是 2026-09-11 那次
 *    "用户已授权却打不出电话"事故的方向；
 *  - [com.rokidlab.phone.ai.ToolConfirmPolicy.BLOCK]（越出本机边界、不可撤销：发短信、
 *    未信任的第三方 MCP 工具）→ **拒绝**并给出可操作出路。这类动作没有"先照做、错了再改"
 *    的余地，而"问不到"本身是可被解决的（戴上眼镜 / 打开 App）。
 *
 * 只有用户**显式拒绝**才在两个分支上都拒绝 —— 那是唯一真正的"用户说不"。
 *
 * ⚠️ 这条路曾经硬编码 `domain == MCP`（"按工具属于哪个域猜该不该问到底"），于是
 * `send_sms`（真会发出短信）与 `call_phone`（只开拨号盘）共享了同一条静默放行路径。
 * 风险档早就是工具的自声明字段，确认策略却还是域的特例 —— 同一个思路只做了一半。
 * 现在两者都读工具自己的声明，与 [com.rokidlab.phone.ai.tools.ToolEntry] 同源。
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
     * 副作用工具的用户确认通道。**有两个实现**，取值顺序见 [activeChannel]：
     *  1. 眼镜端（`GlassToolConfirmChannel`）—— 用户戴着眼镜时最顺手，也是既有主路径；
     *  2. 手机端（[com.rokidlab.phone.ai.PhoneToolConfirmChannel]）—— 本机模式 / 未连接眼镜时兜底。
     *
     * 两个都不可用时，按工具声明的 [com.rokidlab.phone.ai.ToolConfirmPolicy] 分流
     * （见类注释 —— 改造前这里是一律放行，即"fail-open 的唯一产地"）。
     */
    interface ConfirmResolver {
        /**
         * 审计用的通道标识（日志里一眼看出"这次问的是眼镜还是手机"）。
         *
         * ★ 刻意**不给默认值**：排查确认链路时第一个问题就是"问到谁了"，
         * 漏声明会让日志里出现一个无法归因的空标识 —— 宁可编译错。
         */
        val channelId: String

        /** 确认通道当前是否可用（眼镜端已升级且链路在线 / 手机端能拉起界面） */
        fun isAvailable(): Boolean

        /**
         * 阻塞等待用户确认（仅在 [isAvailable] 为 true 时调用）。返回 true = 用户同意执行。
         *
         * @param prompt 给用户看的操作摘要（由闸门传入，与工具定义同源），
         *   通道负责把它渲染到眼镜端悬浮层 + TTS 播报 / 手机端弹窗。
         */
        fun confirm(toolName: String, prompt: String): Boolean

        /** 上一次确认是否被用户**显式取消**（true=取消 → 拒绝；false=超时/未响应 → 按声明分流） */
        fun wasCancelled(): Boolean = false
    }

    /** 眼镜端通道（`CxrLHiRokidSession.init` 注入；链路在线时可用） */
    @Volatile
    var confirmationResolver: ConfirmResolver? = null

    /**
     * 手机端通道（[com.rokidlab.phone.ai.PhoneToolConfirmChannel] 在 `LabApplication.onCreate` 自注册）。
     *
     * ★ 为什么必须有：眼镜通道要求**眼镜在线**，而「本机模式」的设计目标恰恰是
     * "不连眼镜也能用" —— 两者在定义上互斥。于是本机模式下所有需要确认的工具
     * 都落到了 fail-open 分支（静默执行，连"要不要问"都不产生）。
     * 本通道只在眼镜通道不可用时接管 ⇒ 眼镜在线时行为与改造前**完全一致**。
     */
    @Volatile
    var phoneConfirmationResolver: ConfirmResolver? = null

    // ════════════════════════════════════════════════════════════════════
    // 门面：唯一对外入口
    // ════════════════════════════════════════════════════════════════════

    /**
     * 工具执行前的统一审批（同步；可能阻塞至多 ~40s 等用户确认 —— 眼镜通道 35s、手机通道 40s）。
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
     * | 用户**显式拒绝** | 拒绝 | 唯一的"用户说不"（两条通道、两个分支上都成立） |
     * | 问到了且用户同意 | 放行 | —— |
     * | 问不到 / 超时 / 通道抛异常，且工具声明 `PROCEED` | **放行** | 影响不出本机、可重做；硬拒会变成「被安全策略挡住」 |
     * | 问不到 / 超时，且工具声明 `BLOCK` | 拒绝 | 越出本机边界、不可撤销，没有"先照做、错了再改"的余地 |
     *
     * ⚠️ "用户拒绝"与"超时"的区分依据是 [ConfirmResolver.wasCancelled] ——
     * 通道在两种情况下都返回 `allowed=false`，**只能靠这个标志区分**。
     * 早期实现不区分，导致"用户点错一次 / 眼镜没响应"被当成拒绝，
     * 用户侧看到「你已在眼镜上取消」但自己根本没操作。
     */
    internal fun resolveAsk(ask: ToolDecision.Ask, toolName: String): ToolDecision {
        val channel = activeChannel()
        if (channel == null) {
            // 两条通道都问不到：按工具自己的声明分流（见类注释）
            return if (ask.failClosed) denyUnconfirmed(toolName, "当前无法向你确认") else ToolDecision.Allow
        }
        val confirmed = runCatching { channel.confirm(toolName, ask.prompt) }.getOrDefault(false)
        if (confirmed) {
            Log.i(TAG, "confirmed via ${channel.channelId} (tool=$toolName)")
            return ToolDecision.Allow
        }
        if (channel.wasCancelled()) {
            // 文案不带"在眼镜上"这类通道限定 —— 现在两条通道都可能问到（手机弹窗取消同样走这里）
            return ToolDecision.Deny(
                DecisionOrigin.RISK_CONFIRMATION,
                "你已取消本次操作，未执行：'$toolName'",
            )
        }
        // 超时/未响应/通道抛异常：同样按声明分流
        return if (ask.failClosed) denyUnconfirmed(toolName, "你没有确认") else ToolDecision.Allow
    }

    /**
     * 当前可用的确认通道：**眼镜优先、手机兜底**。
     *
     * 为什么眼镜优先：眼镜语音是主入口，用户说话时戴着眼镜，悬浮层确认 + TTS 是他熟悉的方式；
     * 而"眼镜在线却没戴"是一个我们无法从链路状态判定的状态（需要佩戴检测，不在本轮范围内），
     * 贸然改成手机优先反而会把既有主路径的体验换掉。手机通道的定位是**补上"
     * 眼镜不可用时根本没有确认"这个洞**，不是替换眼镜通道。
     *
     * ⚠️ [ConfirmResolver.isAvailable] 也必须包在 `runCatching` 里：通道实现要读链路状态、
     * 读 SharedPreferences，抛异常时应当按"这条通道不可用"继续往下走，
     * 而不是让一个本该被确认的动作因为异常直接崩掉整轮对话。
     */
    private fun activeChannel(): ConfirmResolver? =
        firstAvailable(confirmationResolver) ?: firstAvailable(phoneConfirmationResolver)

    private fun firstAvailable(resolver: ConfirmResolver?): ConfirmResolver? =
        resolver?.takeIf { runCatching { it.isAvailable() }.getOrDefault(false) }

    /**
     * [ToolDecision.Ask.failClosed] 的拒绝文案。
     *
     * 必须给出**可操作的出路**：否则用户只会看到"AI 说被安全策略挡住"而不知道下一步做什么。
     * 出路按工具来源分两条 ——
     *  - 第三方 MCP：连上眼镜确认，或在「设置 → MCP 服务器」把该 server 标为信任（免逐次确认）；
     *  - 内置工具（如 `send_sms`）：打开手机上的乐奇实验室（让确认弹窗能显示出来）后重试，
     *    或连接眼镜用眼镜确认。**不能只说"被安全策略拦截"** —— 那是没有出路的话。
     *
     * ⚠️ 分流判据是**工具名前缀**（[ToolRegistry.isMcpTool]），**不是**查域
     * （[ToolRegistry.domainOfOrNull] == [ToolRegistry.DOMAIN_MCP]）。原因见
     * [ToolRegistry.MCP_TOOL_PREFIX]：MCP 工具是动态注册的，server 断开或处于单测环境时
     * 查表得到 null，会让这里误落到内置工具分支 —— 用户看到"去打开乐奇实验室"，
     * 而正确的出路其实是"把这个 server 标为信任"。
     */
    private fun denyUnconfirmed(toolName: String, why: String): ToolDecision = ToolDecision.Deny(
        DecisionOrigin.RISK_CONFIRMATION,
        if (ToolRegistry.isMcpTool(toolName)) {
            "$why，第三方工具 '$toolName' 未执行。它来自外部服务器（无法保证未确认时不产生副作用），" +
                "请连接眼镜后重试以确认本次调用，或在「设置 → MCP 服务器」里把该服务器标为信任"
        } else {
            "$why，'$toolName' 未执行。这个操作会产生本机之外的影响（例如把短信真的发出去），" +
                "必须由你点头一次：请在手机上打开乐奇实验室后重试" +
                "（确认弹窗需要 App 在前台，或已授予悬浮窗），也可以连接眼镜后用眼镜确认"
        },
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
     * 格式对齐改造前的 `ToolPolicy: audit: ...`，只多带 `[ORIGIN]` 与 `confirm=` ——
     * 排查「AI 说被安全策略挡住」时，第一件事是看**是哪个 guard 拒的**；
     * 排查「本机模式下这个动作怎么会执行/被拒」时，第一件事是看**它的确认降级策略**。
     *
     * ⚠️ TAG 从 `ToolPolicy` 改成了 `ApprovalGate`（类已合并）。日志面板若有按 TAG
     * 过滤的规则，需要一起改。
     */
    private fun audit(ctx: ToolCallContext, decision: ToolDecision) {
        val head = "audit: source=${ctx.source.id} tool=${ctx.name} risk=${ctx.risk} confirm=${ctx.confirmPolicy}"
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

    /** 复位注入状态（两条确认通道 + 策略集）。仅测试调用。 */
    internal fun resetForTest() {
        guardsOverride = null
        confirmationResolver = null
        phoneConfirmationResolver = null
    }
}
