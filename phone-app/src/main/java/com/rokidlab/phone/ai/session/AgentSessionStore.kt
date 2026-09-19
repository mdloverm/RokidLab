package com.rokidlab.phone.ai.session

import com.rokidlab.phone.ai.ChatMessage
import com.rokidlab.phone.ai.compaction.BasicCompactionEngine
import com.rokidlab.phone.ai.compaction.CompactionEngine
import com.rokidlab.phone.ai.compaction.CompactionPolicy
import com.rokidlab.phone.ai.compaction.CompactionResult
import com.rokidlab.phone.ai.compaction.CompactionTrigger

/**
 * 会话事件流的**读写门面**（一个会话一个实例）。
 *
 * 职责边界（三层各管一件事，互不越界）：
 *  - [SessionLog]：字节层 —— 只追加地写、容错地读；
 *  - [SessionProjection]：语义层 —— 纯函数地把事件折叠成"模型看到的历史"；
 *  - **本类**：动作层 —— 把 `recordTurn` 那种"一步到位"的写，拆成与事件一一对应的动作，
 *    并把**会改变历史的动作**（压缩 / 过期 / 丢弃一轮 / 清空）统一翻译成"追加一条声明"。
 *
 * ★ 改造前这些动作全都直接改内存里的 `MutableList<ChatMessage>`：`history.clear()`、
 *   `removeAt(size-1)`、就地折进摘要。**删了就永远回不来**，于是"AI 忘了前面的"
 *   与"查不到前面聊了什么"是同一件事，用户和我们都只能猜。
 *   现在只有两种动作：追加事件（正常记录）与追加**声明**（[CompactionSummary] / [VisibilityCut]）。
 *   原文永远留在文件里，只有"模型当前看不看得见"会变。
 *
 * ★ 只有一处动作是**真删**：用户显式清空（[wipe]）与会话被删除。那是用户的明确意图，
 *   不该被"审计留痕"绑架；而**自动**发生的事（10 分钟过期）一律走声明式裁剪，轨迹保留。
 *
 * 纯 JVM（无 Android 依赖），可被 JVM 单测直接驱动。
 */
internal class AgentSessionStore(
    private val log: SessionLog,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onTrim: (String) -> Unit = {},
) {

    private companion object {
        const val ROLE_SYSTEM = "system"

        /** 单条注入上下文的落盘上限（比 [SessionLog.MAX_FIELD_CHARS] 更紧：注入是每轮都可能写的） */
        const val CONTEXT_INJECT_MAX_CHARS = 2000
    }

    /** 已加载的记录（懒加载；本实例是唯一写者，所以只需在 append 时增量维护） */
    private val records = ArrayList<SessionRecord>()
    private var loaded = false

    /** 投影缓存（任何 append 都会失效） */
    private var cachedProjection: SessionProjection? = null

    /** 压缩引擎：策略变化时整体替换（引擎无状态，替换成本可忽略，见 [BasicCompactionEngine]） */
    private var engine: CompactionEngine = BasicCompactionEngine(CompactionPolicy.DEFAULT, onTrim)

    /** 当前压缩策略 */
    val policy: CompactionPolicy get() = engine.policy

    /** 记录后的快照统计（供上层日志展示） */
    data class Stats(val size: Int, val chars: Int)

    // ═══════════════════════ 读 ═══════════════════════

    private fun allRecords(): List<SessionRecord> {
        if (!loaded) {
            records.addAll(log.read())
            loaded = true
        }
        return records
    }

    private fun projection(): SessionProjection =
        cachedProjection ?: SessionProjection(allRecords().toList()).also { cachedProjection = it }

    /** 模型可见历史（= 下一轮请求注入的 messages） */
    fun history(): List<ChatMessage> = projection().history()

    /**
     * 请求装配用的历史：**排除正在进行的那一轮**（[excludeTurn]）。
     *
     * ★ 为什么必须排除：本轮的用户消息在轮次一开始就落进事件流了（那样进程被杀也不会丢），
     * 但它进请求时要单独构造 —— 多模态时 content 是 `text + image_url` 分片数组，
     * 而事件流里只存文本（base64 不落盘）。若这里把它也带出来，请求里就会出现**两条 user**。
     *
     * 顺带解决溢出恢复：重建请求时读到的正是"压缩之后"的历史，而不是冻结在轮次开始时的快照
     * —— 否则压缩完了重发出的还是原来那条超长请求。
     */
    fun historyForRequest(excludeTurn: Int): List<ChatMessage> {
        if (excludeTurn <= 0) return projection().history()
        return SessionProjection(allRecords().filter { it.event.turn != excludeTurn }).history()
    }

    /** 进模型历史的记录（时间序），与 [history] 的消息一一对应（置顶摘要除外） */
    fun visibleRecords(): List<SessionRecord> = projection().visibleRecords()

    /** 是否已产生滚动摘要 */
    fun hasDigest(): Boolean = projection().hasDigest()

    /** 当前占用快照（口径与改造前 `AgentSessionHistory.snapshot()` 一致：**含**置顶摘要） */
    fun snapshot(): Stats = projection().let { Stats(it.history().size, it.chars()) }

    /** 有调用、无结果的工具调用（崩溃留下的痕迹） */
    fun orphanToolCalls(): List<ToolCall> = projection().orphanToolCalls()

    /** 每轮的用量与耗时（成本可观测；耗时由事件流的 ts 现算，不另存字段） */
    fun turnStats(): List<TurnStat> = projection().turnStats()

    /** 最近一轮的用量与耗时 */
    fun lastTurnStat(): TurnStat? = projection().lastTurnStat()

    /** 全会话累计用量；服务端一次都没给过 usage 时为 null */
    fun usageTotals(): UsageTotals? = projection().usageTotals()

    /** 结构化轨迹（方案 §4.3.5，界面用；与 [SessionDump] 同源不同形状） */
    fun traceItems(limit: Int = 300): List<SessionTrace.Item> =
        SessionTrace.items(allRecords().toList(), limit)

    /** 轨迹规模摘要（轨迹视图顶部一行） */
    fun traceSummary(): SessionTrace.Summary = SessionTrace.summary(allRecords())

    // ═══════════════════════ 写 ═══════════════════════

    /** 追加一个事件（唯一写入口径；分配 seq/ts 由 [SessionLog] 负责） */
    private fun append(event: SessionEvent): SessionRecord {
        allRecords() // 先确保历史已加载，否则会把"新记录"当成全部
        val r = log.append(event)
        records.add(r)
        cachedProjection = null
        return r
    }

    /** 分配下一个轮号（= 已有最大轮号 + 1） */
    fun nextTurn(): Int = projection().maxTurn() + 1

    /**
     * 已分配的最大轮号（0 = 还没有任何一轮）。
     *
     * UI 用它判断"这一轮到底有没有进事件流"：不是每一轮都会记录（拍照答题 / 定时自主任务
     * 传 `recordHistory = false`），所以不能假定"最近一轮的用量"就是刚刚那次回复的用量 ——
     * 那会把**上一轮**的数字贴到这一条消息上（面板显示错误信息，比不显示更糟）。
     */
    fun maxTurn(): Int = projection().maxTurn()

    /** 开始一轮：分配轮号并落 [TurnStart]。轮号由这里唯一分配，调用方无法造出重复轮号 */
    fun beginTurn(source: MessageSource): Int {
        val t = nextTurn()
        append(TurnStart(t, source = source))
        return t
    }

    fun appendUserMessage(turn: Int, text: String, source: MessageSource) {
        append(UserMessage(turn, text = text, source = source))
    }

    /** 记录一条注入上下文（超长截断并标记，见 [SessionLog.clip]） */
    fun appendContextInject(turn: Int, origin: String, content: String) {
        if (content.isBlank()) return
        val (clipped, truncated) = SessionLog.clip(content, CONTEXT_INJECT_MAX_CHARS)
        append(ContextInject(turn, origin = origin, content = clipped, truncated = truncated))
    }

    /** 参数保留**原始 JSON 文本**（模型给非法 JSON 是常态，解析失败就丢字段会让排查无从下手） */
    fun appendToolCall(turn: Int, callId: String, name: String, args: String) {
        val (raw, _) = SessionLog.clip(args)
        append(ToolCall(turn, callId = callId, name = name, args = raw))
    }

    fun appendToolResult(turn: Int, callId: String, name: String, content: String) {
        val (clipped, truncated) = SessionLog.clip(content)
        append(ToolResult(turn, callId = callId, name = name, content = clipped, truncated = truncated))
    }

    /** 记录一次**失败/被打断的尝试**（不进模型历史，只让"这轮为什么慢/为什么报错"可归因） */
    fun appendAttempt(turn: Int, outcome: AttemptOutcome, detail: String? = null) {
        append(AssistantAttempt(turn, outcome = outcome, detail = detail))
    }

    /**
     * 结束一轮。
     *
     * 两条完全不同的收尾路径，**这个分支不能省**：
     *  - 有结论（[TurnEndReason.COMPLETED] / [TurnEndReason.BUDGET_EXHAUSTED]）：落
     *    [AssistantMessage] + [TurnEnd]，然后做压力裁剪（= 改造前 `recordTurn` 里的 trim）。
     *  - 没结论（被打断 / 失败）：追加 [TurnEnd]，再追加一条 [VisibilityCut] 把本轮事件**声明为
     *    不算数**。否则"有 user 没有 assistant"的半个轮会留在可见历史里，下一轮请求就会出现
     *    **两条连续 user 消息** —— 部分服务端直接 400（这正是轮完整性校验要防的形态）。
     *
     * 用量参数（可空）落在 [TurnEnd] 上，**两条路径都会记**：被打断的长任务恰恰最烧 token，
     * 把成本挂在"有没有回复"上会让它整轮不计费。
     *
     * @return 压力裁剪的结果（未发生裁剪时为 null）
     */
    fun finishTurn(
        turn: Int,
        reply: String?,
        reason: TurnEndReason,
        detail: String? = null,
        promptTokens: Int? = null,
        completionTokens: Int? = null,
        modelCalls: Int? = null,
    ): CompactionResult? {
        val end = TurnEnd(
            turn = turn,
            reason = reason,
            detail = detail,
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            modelCalls = modelCalls,
        )
        return when (reason) {
            TurnEndReason.COMPLETED, TurnEndReason.BUDGET_EXHAUSTED -> {
                if (!reply.isNullOrEmpty()) append(AssistantMessage(turn, text = reply))
                append(end)
                trimIfNeeded()
            }

            TurnEndReason.INTERRUPTED, TurnEndReason.FAILED -> {
                append(end)
                cutTurn(turn, reason.wire)
                null
            }
        }
    }

    // ═══════════════════════ 改变可见性（全部是"追加声明"）═══════════════════════

    /**
     * 超时自动清空（每轮开始前调用）。
     *
     * 10 分钟无活动 = 用户开启了新对话：把当前可见的一切（**含滚动摘要**）声明为不算数。
     * 用声明而不是删文件，是因为这只是一种**推断**（用户也许只是离开了一会儿又回来），
     * 而轨迹本身有回溯价值（`session_trace` 要能查到"清空之前聊过什么"）。
     *
     * @return 本次是否触发了过期清空
     */
    fun maybeExpire(nowMs: Long = clock()): Boolean {
        val last = projection().lastActivityTs()
        if (last <= 0L || nowMs - last <= engine.policy.expireMs) return false
        cutVisible(clearDigest = true, reason = "expire")
        return true
    }

    /**
     * 丢弃最后一轮（「重新生成 / 编辑重发」）。
     *
     * 判据取"最后一次真正产出过 assistant 消息的轮"而不是"最大轮号"：用户在生成过程中点
     * 重新生成时，最大轮号可能是一个**还没结束**的轮，按它去丢会丢掉正在进行的那轮。
     * 摘要不受影响 —— 它代表更早的、仍然有效的记忆。
     */
    fun dropLastTurn() {
        val lastDone = allRecords().lastOrNull { it.event is AssistantMessage }?.event?.turn ?: return
        cutTurn(lastDone, "drop_last_turn")
    }

    /** 真正删除事件流文件（用户显式清空 / 会话被删除）。调用后 seq 从 1 重新开始 */
    fun wipe() {
        log.clear()
        records.clear()
        loaded = true
        cachedProjection = null
    }

    /** 把某一轮的全部事件声明为"不算数"（保留摘要） */
    private fun cutTurn(turn: Int, reason: String) {
        val seqs = allRecords().filter { it.event.turn == turn }.map { it.seq }
        if (seqs.isEmpty()) return
        append(VisibilityCut(turn = null, seqs = seqs, clearDigest = false, reason = reason))
    }

    /** 把当前**可见**的一切声明为"不算数"；[clearDigest] = 是否连滚动摘要一起清 */
    private fun cutVisible(clearDigest: Boolean, reason: String) {
        val proj = projection()
        val seqs = proj.visibleRecords().map { it.seq }
        // 不可见的事件本来就不进历史，不必逐个列进声明（声明数组会随会话长度线性膨胀）；
        // 但要清摘要时即便一条可见记录都没有也必须落下这条声明（摘要是独立于消息存在的）
        if (seqs.isEmpty() && !(clearDigest && proj.hasDigest())) return
        append(VisibilityCut(turn = null, seqs = seqs, clearDigest = clearDigest, reason = reason))
    }

    // ═══════════════════════ 压缩 ═══════════════════════

    /**
     * 换一套压缩策略（由模型上下文窗口推导，见 [CompactionPolicy.forWindow]）。
     *
     * @return 是否**真的换了**（策略相同则不动，避免每轮都做无意义的替换与日志）
     */
    fun applyPolicy(next: CompactionPolicy): Boolean {
        if (next == engine.policy) return false
        engine = BasicCompactionEngine(next, onTrim)
        // 收紧后可能**立刻**就超预算了：此时不能等到下一轮结束才压，
        // 否则下一条消息会带着超预算的历史发出去（正是要防的 400）
        trimIfNeeded()
        return true
    }

    /** 压力裁剪：超出预算才压（每轮结束调用，= 改造前 `recordTurn` 的 trim） */
    fun trimIfNeeded(): CompactionResult? = compact(CompactionTrigger.PRESSURE, force = false)

    /**
     * 强制压缩（手动「立即压缩」/ 上下文溢出恢复）。
     *
     * @return 未发生压缩（历史已经足够短）时返回 null
     */
    fun compactNow(trigger: CompactionTrigger = CompactionTrigger.MANUAL): CompactionResult? =
        compact(trigger, force = true)

    /**
     * 压缩 = 跑一遍引擎 + 把差额**翻译成一条声明**。
     *
     * ★ 引擎是就地改 `MutableList<ChatMessage>` 的（它的算法逐字来自改造前，不改它），
     *   所以在**副本**上跑，再回答一个引擎不关心的问题："被移走的到底是哪几条事件？"
     *
     * 这个映射靠一条结构性质：压缩**只从最旧处动手**（`compressOldest` 只碰下标 0/1），
     * 所以压缩后的正文必然是压缩前正文的**后缀**，差额条数就能从头部数出来，
     * 再对齐到 [SessionProjection.visibleRecords]（它与消息一一对应，是钉在测试里的契约）。
     * 最后用"重新投影的结果必须等于引擎给出的历史"做自检 —— 映射一旦错位，
     * 表现是"模型莫名少了几轮上下文"，静默且极难查。
     */
    private fun compact(trigger: CompactionTrigger, force: Boolean): CompactionResult? {
        val proj = projection()
        val before = proj.history()
        // 只留摘要（或空）时无可压缩 —— 引擎也会在 size<=2 时退出，这里提前挡掉省一次投影
        if (before.size <= 1) return null

        val work = ArrayList(before)
        val result: CompactionResult? = if (force) {
            engine.compactNow(work, trigger)
        } else {
            engine.compactIfNeeded(work, trigger)
        }
        if (result == null) return null

        val bodyBefore = proj.visibleRecords()
        val newDigest = work.firstOrNull { it.role == ROLE_SYSTEM }?.content
        val bodyAfterSize = if (newDigest != null) work.size - 1 else work.size
        val removed = (bodyBefore.size - bodyAfterSize).coerceAtLeast(0)
        val shadowed = bodyBefore.take(removed).map { it.seq }

        append(CompactionStart(turn = null, trigger = trigger.name))
        append(
            CompactionSummary(
                turn = null,
                shadowedSeqs = shadowed,
                // 空摘要也是有效语义（引擎丢掉了异常摘要形态）——投影会把它读成"没有摘要"
                text = newDigest.orEmpty(),
                turnsCompressed = result.turnsCompressed,
            ),
        )
        append(CompactionEnd(turn = null))
        onTrim("compaction: $result (shadowed=$removed)")

        val after = projection().history()
        if (after != work) {
            onTrim("WARN: compaction projection mismatch — engine=${work.size} msgs, reprojected=${after.size}")
        } else if (!projection().isTurnBalanced()) {
            onTrim("WARN: turn balance broken after compaction ($result) — 请求可能出现连续同角色消息")
        }
        return result
    }
}
