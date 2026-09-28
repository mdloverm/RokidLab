package com.rokidlab.phone.ai.session

import com.rokidlab.phone.ai.ChatMessage
import com.rokidlab.phone.ai.compaction.BasicCompactionEngine
import com.rokidlab.phone.ai.compaction.CompactionEngine
import com.rokidlab.phone.ai.compaction.CompactionPolicy

/**
 * 事件流的**投影**（对齐 deepseek-harness 的 session projection）。
 *
 * 事件流是唯一权威；"模型看到的 messages""上下文占用""哪几轮被摘要替代了"全是它的**派生**。
 * 改造前这三件事各有一份独立的可变状态（`AgentSessionHistory.history` + 一个 `chars` 计数 +
 * 一句 `hasDigest()` 反查 system 消息），于是它们之间会不会不一致，没有任何东西能保证。
 *
 * ★ 投影是**纯函数**：给定同一批 [SessionRecord]，永远得到同样的历史。
 *   类被刻意设计成"只会读"（构造函数收一个不可变列表），所以"投影改变了事件流"这种事
 *   在类型上就不可能发生 —— 这正是它能替掉原来那个既持有状态又改状态的实现的前提。
 *
 * ★ 压缩在这里是**不销毁信息**的：被摘要替代的事件由 [shadowedSeqs] 标记并排除出
 *   [history]，但它们**仍在文件里**（见 [CompactionSummary]）。所以
 *   "AI 忘了前面的" 与 "查不到前面聊了什么" 第一次变成了两件可以分开回答的事。
 */
internal class SessionProjection(private val records: List<SessionRecord>) {

    private companion object {
        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"
        const val ROLE_SYSTEM = "system"

        /**
         * 轮完整性判据的**唯一产地**在压缩引擎里（`CompactionEngine.isTurnBalanced`）。
         *
         * 这里刻意复用而不是抄一遍：同一条不变式在两处实现，早晚会分叉，
         * 而它一旦失效的表现是"请求里出现两条连续 user 消息 → 部分服务端直接 400"
         * —— 静默、且要到真机上才暴露。
         */
        val BALANCE: CompactionEngine = BasicCompactionEngine(CompactionPolicy.DEFAULT)
    }

    /** 全部事件（按文件顺序） */
    private val events: List<SessionEvent> = records.map { it.event }

    /**
     * 已被摘要替代的事件 seq（历次压缩的**并集**）。
     *
     * 取并集而不是最后一批：压缩是累积的，第一次压掉的轮不会因为第二次压缩而"回来"。
     *
     * ⚠️ 语义是「信息还在，只是换了形态（进了摘要）」—— 与 [hiddenSeqs] 里另一部分
     * （[VisibilityCut] 声明"不算数"的）性质不同，所以两者**分开暴露**。
     */
    val shadowedSeqs: Set<Long> = events.filterIsInstance<CompactionSummary>()
        .flatMap { it.shadowedSeqs }
        .toSet()

    /** 被 [VisibilityCut] 声明为**不算数**的事件 seq（过期清空 / 丢弃最后一轮 / 手动清空） */
    private val cutSeqs: Set<Long> = events.filterIsInstance<VisibilityCut>()
        .flatMap { it.seqs }
        .toSet()

    /**
     * 退出模型可见历史的**全部** seq（摘要覆盖 ∪ 显式作废）。投影过滤用这个。
     *
     * 单列一个名字而不是直接用 [shadowedSeqs]：将来做「这轮为什么不在上下文里」的
     * 诊断时，要能区分"被压了"和"作废了"——这两件事对用户的解释完全不同。
     */
    val hiddenSeqs: Set<Long> = shadowedSeqs + cutSeqs

    /**
     * 最后一次「连滚动摘要一起清」的 seq（过期清空 / 手动清空）。-1 = 没发生过。
     *
     * 过期清空必须把摘要也清掉：摘要代表的是**上一段对话**的记忆，只清消息不清摘要，
     * 模型会带着"更早对话摘要"继续聊，用户会看到"清了跟没清一样"。
     * 而「丢弃最后一轮」不清摘要 —— 它只否定末尾那一轮，更早的记忆仍然有效。
     */
    private val digestClearedAfterSeq: Long = records
        .filter { it.event.let { e -> e is VisibilityCut && e.clearDigest } }
        .maxOfOrNull { it.seq } ?: -1L

    /**
     * 当前滚动摘要全文（**最后一次**压缩的产物），未压缩过时为 null。
     *
     * 取最后一份而不是拼接：每次压缩写的是全量摘要（含标题行），
     * 拼接会让摘要随压缩次数线性增长 —— 那是它要防的事。
     * 最后一次清空之后的压缩事件才算数（见 [digestClearedAfterSeq]）。
     */
    val digest: String? = records.asReversed()
        .firstOrNull { it.seq > digestClearedAfterSeq && it.event is CompactionSummary }
        ?.event
        ?.let { (it as CompactionSummary).text }
        ?.takeIf { it.isNotBlank() }

    /** 进入模型历史的事件（已按 `modelVisible` + 摘要覆盖 + 作废声明过滤） */
    private val visible: List<SessionRecord> =
        records.filter { it.seq !in hiddenSeqs && it.event.modelVisible }

    /**
     * 工具轨迹（按轮聚合），由 [ToolCall] 事件**派生**而非另存一份。
     *
     * 格式 `name(args)` 与改造前 `ChatMessage.toolTrace` 逐字一致 —— 它只用于本地回溯展示，
     * 不注入模型上下文。
     */
    private val traceByTurn: Map<Int, List<String>> = events.filterIsInstance<ToolCall>()
        .groupBy({ it.turn }, { "${it.name}(${it.args})" })

    /** 模型可见历史（= 下一轮请求注入的 messages）。首次访问时计算一次，之后复用 */
    private val cachedHistory: List<ChatMessage> by lazy { buildHistory() }

    // ═══════════════════════ 模型视角 ═══════════════════════

    /**
     * 模型可见的完整历史：置顶摘要（若有）+ 未被摘要覆盖的 user/assistant 消息。
     *
     * 跳过 [AssistantAttempt]（失败尝试不该污染上下文）与全部 `Compaction*`（它们是
     * 关于历史的元事件，不是对话本身）—— 这两条由 `modelVisible` 在事件定义处声明。
     */
    fun history(): List<ChatMessage> = cachedHistory

    /**
     * 进模型历史的**记录**（时间序），与 [history] 的消息**一一对应**（置顶摘要除外）。
     *
     * 暴露它是为了压缩接线：引擎按"压缩后还剩几条消息"告诉我们收缩了多少，
     * 得有人把它翻译回"哪些 seq 被折进摘要了"才能写成 [CompactionSummary]。
     * 这条一一对应是**契约**，由 `SessionProjectionTest` 钉住 ——
     * 将来若加了新的 `modelVisible` 事件却不产生消息（或产生多条），
     * 那个测试会立刻红，而不是等到某天发现摘要声明覆盖错了 seq。
     */
    fun visibleRecords(): List<SessionRecord> = visible

    /** 已记录的最大轮号（0 = 还没有任何一轮）。下一轮的编号 = 它 + 1 */
    fun maxTurn(): Int = records.mapNotNull { it.event.turn }.maxOrNull() ?: 0

    private fun buildHistory(): List<ChatMessage> {
        val out = ArrayList<ChatMessage>(visible.size + 1)
        digest?.let { out.add(ChatMessage(role = ROLE_SYSTEM, content = it)) }
        for (r in visible) {
            when (val e = r.event) {
                is UserMessage -> out.add(ChatMessage(role = ROLE_USER, content = e.text))

                is AssistantMessage -> out.add(
                    ChatMessage(
                        role = ROLE_ASSISTANT,
                        content = e.text,
                        toolTrace = traceByTurn[e.turn].orEmpty(),
                    )
                )

                // 其余类型不进模型历史。**逐个列出**而不是 `else -> Unit`：
                // 将来新增事件类型时，编译器会在这里提醒回答"它要不要进模型上下文"。
                is TurnStart,
                is TurnEnd,
                is AssistantAttempt,
                is ToolCall,
                is ToolResult,
                is ContextInject,
                is CompactionStart,
                is CompactionSummary,
                is CompactionEnd,
                is VisibilityCut,
                -> Unit
            }
        }
        return out
    }

    // ═══════════════════════ 用量与诊断 ═══════════════════════

    /** 模型可见的消息条数（**不含**置顶摘要，与改造前 `AgentSessionHistory.snapshot().size` 口径一致） */
    fun messageCount(): Int = visible.size

    /** 模型可见的总字符数（含置顶摘要 —— 它确实占上下文，口径与改造前一致） */
    fun chars(): Int = cachedHistory.sumOf { it.content.length }

    /** 是否已产生滚动摘要（= 早期对话已被压缩） */
    fun hasDigest(): Boolean = digest != null

    /** 最后一条事件的时间戳（诊断用；原先的"过期判定"已随空闲过期一起移除）。为空时返回 0 */
    fun lastActivityTs(): Long = records.maxOfOrNull { it.ts } ?: 0L

    /** 轮完整性（user/assistant 严格交替、偶数长度、以 assistant 结尾） */
    fun isTurnBalanced(): Boolean = BALANCE.isTurnBalanced(cachedHistory)

    /**
     * 有调用、无结果的工具调用 —— **崩溃留下的痕迹**。
     *
     * 这是事件流给的新的可观测性：改造前工具调用只是一条 `"name(args)"` 字符串，
     * "调了但没回来"（进程被杀 / 工具卡死）与"调了并成功"在记录上完全一样。
     * 接线后可用于"上次中断在哪一步"的续做提示。
     */
    fun orphanToolCalls(): List<ToolCall> {
        val answered = events.filterIsInstance<ToolResult>().map { it.callId }.toSet()
        return events.filterIsInstance<ToolCall>().filter { it.callId.isNotBlank() && it.callId !in answered }
    }

    // ═══════════════════════ 用量与耗时（成本可观测）═══════════════════════

    /**
     * 每轮的用量与耗时（按轮号升序）。
     *
     * 耗时**不落盘、现算**：`TurnStart.ts` 与 `TurnEnd.ts` 已经在事件流里，
     * 再存一个字段就是同一件事的第二份记录（而且必然有一份会写漏）。
     *
     * 被打断 / 失败的轮同样有 [TurnEnd]，所以它们的成本也在 —— 那是**最重要的**一部分：
     * 长任务被抢占恰恰最烧 token，按"有没有回复"统计会把它们整轮漏掉。
     */
    fun turnStats(): List<TurnStat> {
        val startTs = HashMap<Int, Long>()
        val replyChars = HashMap<Int, Int>()
        for (r in records) {
            val e = r.event
            when (e) {
                is TurnStart -> startTs[e.turn] = r.ts
                // 回复字符数：服务端没给 usage 时，UI 拿它说"约 N 字"（而不是编一个 token 数）
                is AssistantMessage -> replyChars[e.turn] = e.text.length
                else -> Unit
            }
        }
        val out = ArrayList<TurnStat>()
        for (r in records) {
            val e = r.event
            if (e !is TurnEnd) continue
            out.add(
                TurnStat(
                    turn = e.turn,
                    promptTokens = e.promptTokens,
                    completionTokens = e.completionTokens,
                    promptCacheHitTokens = e.promptCacheHitTokens,
                    modelCalls = e.modelCalls,
                    elapsedMs = startTs[e.turn]?.let { r.ts - it },
                    reason = e.reason,
                    replyChars = replyChars[e.turn] ?: 0,
                )
            )
        }
        return out
    }

    /** 最近一轮（按轮号最大）的用量与耗时；还没有任何轮次时返回 null */
    fun lastTurnStat(): TurnStat? = turnStats().maxByOrNull { it.turn }

    /**
     * 全会话累计用量。
     *
     * ⚠️ 只累加**服务端真给过 usage 的轮次**，并且显式回报 [UsageTotals.turnsWithUsage] ——
     *    否则"累计 1.2 万"可能来自 20 轮里的 2 轮，用户会以为整段对话只花了这么多。
     *    一个轮次都没给过时返回 null（不是 0）。
     */
    fun usageTotals(): UsageTotals? {
        var prompt = 0
        var completion = 0
        var withUsage = 0
        for (s in turnStats()) {
            val p = s.promptTokens ?: continue
            prompt += p
            completion += s.completionTokens ?: 0
            withUsage++
        }
        if (withUsage == 0) return null
        return UsageTotals(prompt, completion, withUsage)
    }
}

/**
 * 一轮的用量与耗时（事件流的派生量）。
 *
 * 三个用量字段全可空 = 服务端没给（见 [TurnEnd]）；耗时为 null = 这一轮还没写完
 * （只有 `TurnStart` 没有 `TurnEnd`，即进程在轮中死掉 —— 这也正是它可被观察到的价值）。
 */
internal data class TurnStat(
    val turn: Int,
    val promptTokens: Int?,
    val completionTokens: Int?,
    /** 输入中命中服务端前缀缓存的部分（null = 服务端没给；连续为 0 = 前缀稳定性退化） */
    val promptCacheHitTokens: Int?,
    /** 本轮的模型调用次数（>1 = 走了工具循环） */
    val modelCalls: Int?,
    val elapsedMs: Long?,
    val reason: TurnEndReason?,
    /** 这一轮的回复字符数（服务端没给 usage 时，UI 拿它说"约 N 字"而不是编 token 数） */
    val replyChars: Int,
)

/**
 * 会话累计用量。
 *
 * @param turnsWithUsage 贡献了数字的轮数 —— 必须一并展示，否则累计值会被当成"全部轮次的总和"
 */
internal data class UsageTotals(
    val promptTokens: Int,
    val completionTokens: Int,
    val turnsWithUsage: Int,
) {
    val total: Int get() = promptTokens + completionTokens
}
