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
 *    并把**会改变历史的动作**（压缩 / 丢弃一轮 / 清空）统一翻译成"追加一条声明"。
 *
 * ★ 改造前这些动作全都直接改内存里的 `MutableList<ChatMessage>`：`history.clear()`、
 *   `removeAt(size-1)`、就地折进摘要。**删了就永远回不来**，于是"AI 忘了前面的"
 *   与"查不到前面聊了什么"是同一件事，用户和我们都只能猜。
 *   现在只有两种动作：追加事件（正常记录）与追加**声明**（[CompactionSummary] / [VisibilityCut]）。
 *   原文永远留在文件里，只有"模型当前看不看得见"会变。
 *
 * ★ 只有一处动作是**真删**：用户显式清空（[wipe]，以及 `AgentSessionManager.clearAll` 的全量清空）
 *   与会话被删除。那是用户的明确意图，不该被"审计留痕"绑架；而**局部**裁剪（丢弃最后一轮）
 *   走声明式，轨迹保留。
 *   （原先还有"10 分钟无活动自动过期"，2026-09-20 已移除 —— 它与"记忆随对话持久保存"的用户
 *   心智正面相抵，而且是静默发生的：满屏对话还在、AI 却不认了。）
 *
 * 纯 JVM（无 Android 依赖），可被 JVM 单测直接驱动。
 */
internal class AgentSessionStore(
    private val log: SessionLog,
    private val onTrim: (String) -> Unit = {},
    /**
     * 滚动摘要的 **LLM 精炼器**（可空 = 用本地摘要，行为与改造前逐字一致）。
     *
     * 背景：本地摘要是"各取首句、截断 60 字"，保得住话题名，保不住**结论**——
     * "用户问了天气，AI 答…（首句）"这种摘要回填给模型后，它只能知道自己聊过天气，
     * 不知道聊出了什么。精炼器在压缩发生后用被压缩轮的**原文**重写一版高密度摘要。
     *
     * 契约：
     *  - 输入 [DigestRefiner.refine] 收到（role, text）顺序对与摘要字符预算；
     *  - 返回 null / 抛异常 / 空串 = 放弃，**保留本地摘要**（网络失败是常态，绝不能让
     *    "精炼摘要"变成"压缩失败"—— 压缩本身必须无条件成功，那是防 400 的底线）；
     *  - 返回文本时**不带标题行**（标题由 store 统一加，精炼器只出要点）。
     *
     * ⚠️ 实现方应在**回复已送达之后**的路径上被调用（见 [compact]），单次耗时建议
     *    有界（15s 级）；它是同步调用，调用方线程会被占住。
     */
    private val digestRefiner: DigestRefiner? = null,
) {

    // ⚠️ companion **不能是 private**（2026-10-07）：`private companion object` 会让
// internal 成员对测试也不可见，而有界性断言必须能读到 [MAX_LOADED_RECORDS] 这个真值。
// 其余常量一律标 `private`，不因此扩大可见面。
internal companion object {
        private const val ROLE_SYSTEM = "system"

        // 摘要精炼（refineDigest）要用到的角色名（与投影 buildHistory 的口径一致）
        private const val ROLE_USER = "user"
        private const val ROLE_ASSISTANT = "assistant"

        /**
         * 内存中已加载记录条数的上限（2026-10-07 有界性修复）。
         *
         * ⚠️ **故意 `internal` 而非 `private`**：单测要断言「记录数 ≤ 上限」，
         * 而测试若自己写一份数字，就等于给"测试通过"上了假保险 ——
         * 上限被改大时测试不会红。断言必须指向**生产代码本身**这个值。
         * 📌 经验：这类"上限/常量型不变量"若不给测试可读的入口，下一个人
         * 会选择在测试里复刻一份，然后两边悄悄漂开。
         */
        internal const val MAX_LOADED_RECORDS = 2000

        /** 单条注入上下文的落盘上限（比 [SessionLog.MAX_FIELD_CHARS] 更紧：注入是每轮都可能写的） */
        const val CONTEXT_INJECT_MAX_CHARS = 2000

        /**
         * 送精炼器的输入上限（字符）。
         *
         * 溢出恢复（[CompactionTrigger.CONTEXT_OVERFLOW]）可能一次压掉几万字符 —— 全文发过去
         * 既慢又可能打爆小窗口模型。取**尾部**：摘要的价值在"最近聊出了什么结论"，
         * 最旧的部分本地摘要行已经在兜底了。
         */
        const val REFINE_INPUT_MAX_CHARS = 12000

        /**
         * 被打断/失败且**一字未出**时补的占位回复（2026-10-09）。
         *
         * 它的作用是**占位**而不是传递信息：模型上下文里必须 user/assistant 成对
         * （见 `CompactionEngine.isTurnBalanced`），而这一轮没有真实回复可落。
         *
         * ⚠️ 措辞刻意只说"被中断"、**不解释成失败或错误**：失败轮（网络/服务端）同样走这里。
         * 若写成"生成失败"，模型下一轮会以为自己上一轮说错了什么而道歉 —— 那是凭空捏造的上下文。
         * 保持中性，模型只会理解为"那轮没说完"。
         */
        const val INTERRUPTED_PLACEHOLDER =
            "（这一轮被中断了，没有完整回复。用户若要求继续，请基于上面那条请求重新作答。）"

        /** 工具轨迹作废声明的 reason 值（历史值不可改，见 `VisibilityCut`） */
        private const val TURN_NOISE_CUT = "turn_tool_noise"
    }

    /** 压缩后的滚动摘要精炼器（见构造参数 [digestRefiner] 的契约说明） */
    fun interface DigestRefiner {
        /** @return 精炼后的摘要要点（**不含标题行**）；null = 放弃，保留本地摘要 */
        fun refine(turns: List<Pair<String, String>>, budgetChars: Int): String?
    }

    /** 已加载的记录（懒加载；本实例是唯一写者，所以只需在 append 时增量维护） */
    private val records = ArrayList<SessionRecord>()

    /**
     * 内存中已加载记录的上限（2026-10-07 安全修复）—— 见 [MAX_LOADED_RECORDS] 的判据说明。
     *
     * ⚠️ 旧实现**没有 trim**，全仓只有 `wipe()` 会 `records.clear()` ⇒ 一个长会话的
     * 完整事件流（含每轮工具参数与返回值）在内存里**单调增长**；而落盘 jsonl 也只有
     * 单字段 `MAX_FIELD_CHARS` 的上限，**没有条数/体积上限** ⇒ 长时间使用后占用无界。
     */
    private val maxLoadedRecords = MAX_LOADED_RECORDS

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
            // 重开一个超长会话时会一次读入全部历史 ⇒ 加载路径同样要裁，
            // 否则「有界」只在追加时生效，重启一次就又满了（这正是无界缓存的典型形状：
            // 写入点有上限、恢复点没上限，等于没有上限）。
            if (records.size > maxLoadedRecords) {
                records.subList(0, records.size - maxLoadedRecords).clear()
            }
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

    /**
     * 当前**内存里**已加载的记录条数（仅供测试断言有界性与日志诊断）。
     *
     * 走`allRecords()` 而不直接读 `records` ⇒ 语义统一（懒加载也计入），
     * 避免出现"测试断言的是另一个数"的情况。
     */
    internal fun loadedRecordCount(): Int = allRecords().size

    /**
     * 全部记录（含**非**模型可见的那些）——测试可见入口。
     *
     * ⚠️ 存在的理由：审计类事件（如 [SideEffectIntent]）刻意 `modelVisible = false`，
     *   所以 [visibleRecords] / [history] 里**必然找不到它们**。
     *   写这类事件的测试时误用 visible 入口会得到"记录丢了"的假象 ——
     *   本轮就踩过一次（两条测试先红，原因是查询口径错而不是实现错）。
     */
    internal fun allRecordsForTest(): List<SessionRecord> = allRecords().toList()

    // ═══════════════════════ 写 ═══════════════════════

    /** 追加一个事件（唯一写入口径；分配 seq/ts 由 [SessionLog] 负责） */
    private fun append(event: SessionEvent): SessionRecord {
        allRecords() // 先确保历史已加载，否则会把"新记录"当成全部
        val r = log.append(event)
        records.add(r)
        // 内存有界：只在**新增时**裁一次最旧，且不碰投影缓存之外的任何状态。
        // 落盘 jsonl 不受影响（log.append 已先写盘），所以数据不会丢，只是内存里不再留。
        if (records.size > maxLoadedRecords) {
            records.subList(0, records.size - maxLoadedRecords).clear()
        }
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

    /**
     * 记录一次**已授权的副作用**（写前日志，见 [SideEffectIntent]）。
     *
     * ★ **调用点必须在副作用真正发生之前**。这不是建议而是这条不变式存在的全部意义：
     *   授权与意图在效果对外可见之前落盘，崩溃恢复才可能知道"哪些已经在路上"。
     *   若在事后调用，它退化成一条普通日志，比不写更糟 —— 因为它看起来像有审计，
     *   实际恰好在最需要它的那段空白里为空。
     *
     * ★ **拒绝路径不要调它**。未授权的副作用**不产生这条事件**（反向不变式）：
     *   日志里查不到"差点发生什么"，"不存在"本身就是证据。
     *
     * @param stageEvidence 分阶段审批的阶段判定（见 [SideEffectIntent.stageEvidence]）
     * @return 落盘记录的 seq（供上层断言"授权 seq < 结果 seq"）
     */
    fun appendSideEffectIntent(
        turn: Int?,
        name: String,
        source: AuthorizationSource,
        callId: String? = null,
        summary: String? = null,
        stageEvidence: List<String> = emptyList(),
    ): Long = append(
        SideEffectIntent(
            turn = turn,
            name = name,
            callId = callId,
            source = source,
            // 摘要同样走 clip：事件流只追加，超长文本会永久留在文件里
            summary = summary?.let { SessionLog.clip(it).first }?.takeIf { it.isNotBlank() },
            stageEvidence = stageEvidence,
        )
    ).seq

    /** 记录一次**失败/被打断的尝试**（不进模型历史，只让"这轮为什么慢/为什么报错"可归因） */
    fun appendAttempt(turn: Int, outcome: AttemptOutcome, detail: String? = null) {
        append(AssistantAttempt(turn, outcome = outcome, detail = detail))
    }

    /**
     * 记录一次**框架驱动的自我纠偏**（2026-10-09，P0-7）。
     *
     * 三个字段都走 `clip`：事件流只追加，超长文本会永久留在文件里（同 [appendSideEffectIntent]）。
     * ⚠️ `turn` 可为 null（无主线的后台动作也会被纠偏），与 [SideEffectIntent] 同口径。
     */
    fun appendReflect(
        turn: Int?,
        trigger: ReflectTrigger,
        detail: String,
        action: String,
    ): Long = append(
        Reflect(
            turn = turn,
            trigger = trigger.wire,
            detail = SessionLog.clip(detail).first,
            action = SessionLog.clip(action).first,
        ),
    ).seq

    /**
     * 结束一轮。
     *
     * 两条完全不同的收尾路径，**这个分支不能省**：
     *  - 有结论（[TurnEndReason.COMPLETED] / [TurnEndReason.BUDGET_EXHAUSTED]）：落
     *    [AssistantMessage] + [TurnEnd]，然后做压力裁剪（= 改造前 `recordTurn` 里的 trim）。
     *  - 没结论（被打断 / 失败）：落半截正文（若有）+ [TurnEnd]，并用一条 [VisibilityCut]
     *    只作废**工具轨迹**，**保留用户原话**（见下方"为什么不再整轮作废"）。
     *
     * ## 为什么不再整轮作废（2026-10-09，行为变更）
     *
     * 改造前这里 `cutTurn(turn)` 把整轮事件（**含轮次开头就落盘的 [UserMessage]**）全部
     * 声明为"不算数"。动机是对的：不剪就会在请求里留下"有 user 没有 assistant"的半轮，
     * 下一轮拼接后变成**两条连续 user** —— 部分服务端直接 400。
     *
     * 但代价是**信息真空**：用户说「继续」时，模型上下文里既没有被打断的那句原话，
     * 也没有任何痕迹表明"刚才那轮发生了什么"。症状是"上下文有点问题" ——
     * 屏幕上有整段对话，模型却像第一次听说这件事。若那轮没落过 plan/文件
     * （见 `AgentTaskStore.markStatus` 改造前的早退），连续做提示词也补不回来。
     *
     * 现在改为**保留 [UserMessage] + 补一条占位 [AssistantMessage]**：
     *  - "两条连续 user"的隐患被占位 assistant 消掉（轮完整性不变式仍然成立）；
     *  - 用户原话回到上下文，「继续」有了指代对象。
     *
     * ⚠️ 占位文本刻意**不解释成"我失败了"**而是说"被中断了"：失败的轮同样走这条路径，
     * 而失败（网络/服务端）不该让模型在下一轮以为自己"上一轮说错了什么"而道歉。
     *
     * 用量参数（可空）落在 [TurnEnd] 上，**两条路径都会记**：被打断的长任务恰恰最烧 token，
     * 把成本挂在"有没有回复"上会让它整轮不计费。
     *
     * @param partialReply 被打断时**已经产出**的正文（流式推给 UI 的那部分）。
     *   非空时作为 [AssistantMessage]（`partial = true`）落盘，模型才知道"我刚才说到哪"。
     * @return 压力裁剪的结果（未发生裁剪时为 null）
     */
    fun finishTurn(
        turn: Int,
        reply: String?,
        reason: TurnEndReason,
        detail: String? = null,
        promptTokens: Int? = null,
        completionTokens: Int? = null,
        promptCacheHitTokens: Int? = null,
        modelCalls: Int? = null,
        partialReply: String? = null,
    ): CompactionResult? {
        val end = TurnEnd(
            turn = turn,
            reason = reason,
            detail = detail,
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            promptCacheHitTokens = promptCacheHitTokens,
            modelCalls = modelCalls,
            partialReply = partialReply?.takeIf { it.isNotEmpty() },
        )
        return when (reason) {
            TurnEndReason.COMPLETED, TurnEndReason.BUDGET_EXHAUSTED -> {
                if (!reply.isNullOrEmpty()) append(AssistantMessage(turn, text = reply))
                append(end)
                trimIfNeeded()
            }

            TurnEndReason.INTERRUPTED, TurnEndReason.FAILED -> {
                append(end)
                // 半截正文先落：它模型可见，是"我刚才说到哪"的唯一载体
                val partial = partialReply?.takeIf { it.isNotEmpty() }
                if (partial != null) {
                    append(AssistantMessage(turn, text = partial, partial = true))
                } else if (!hasAssistantMessage(turn)) {
                    // 一字未出也不能留半轮：补占位，保证 user/assistant 仍然成对
                    append(AssistantMessage(turn, text = INTERRUPTED_PLACEHOLDER, partial = true))
                }
                // 只作废工具轨迹（ToolCall/ToolResult/ContextInject/attempt）。
                // ★ 刻意**不**动 [UserMessage] —— 用户原话必须留在上下文里，
                //   否则「继续」失去指代对象（见 KDoc"为什么不再整轮作废"）。
                cutToolNoise(turn)
                null
            }
        }
    }

    /** 本轮是否已有模型可见的 assistant 消息（避免占位与真实回复重复） */
    private fun hasAssistantMessage(turn: Int): Boolean =
        allRecords().any { it.event.turn == turn && it.event is AssistantMessage }

    /**
     * 作废某一轮的**工具轨迹类**事件（保留 [UserMessage] 与 [AssistantMessage]）。
     *
     * ⚠️ 这类事件本来就不进模型历史（`modelVisible = false`），作废它们不是为了上下文，
     * 而是为了让「这一轮的工具调用不算数」这件事在事件流里有明确声明 ——
     * 否则被打断的工具调用会与下一轮的工具调用混在同一条轨迹里，
     * 回溯"这个调用是哪一轮发起的"会得到错误答案。
     */
    private fun cutToolNoise(turn: Int) {
        val seqs = allRecords().filter { r ->
            r.event.turn == turn && r.event !is UserMessage && r.event !is AssistantMessage
        }.map { it.seq }
        if (seqs.isEmpty()) return
        append(VisibilityCut(turn = null, seqs = seqs, clearDigest = false, reason = TURN_NOISE_CUT))
    }

    // ═══════════════════════ 改变可见性（全部是"追加声明"）═══════════════════════

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

    /**
     * 丢弃 **[fromTurn] 及其之后的全部轮**（2026-10-09）。
     *
     * ★ 为什么要它：UI 的 [com.rokidlab.phone.store.ChatStateHolder.truncateFrom] 是
     * "从某条消息起砍掉后面全部"，而 [dropLastTurn] 只能切**最后一轮** —— 两者口径不一致，
     * 于是「编辑靠前的一条消息」时，界面砍掉了后续好几轮，而事件流里那些轮**仍然可见**：
     * 用户看到消息没了，模型却还在引用它们（症状＝"删了它还记得"）。
     *
     * [fromTurn] 用 1 起算（与事件流的轮号一致）；传 0 或负数 = 无效入参，**不做任何事**
     * （返回 false 让调用方知道没生效，而不是默默切掉一切）。
     *
     * @return 是否真的作废了至少一轮（false = 入参无效或该轮不存在）
     */
    fun dropFromTurn(fromTurn: Int): Boolean {
        if (fromTurn <= 0) return false
        val hit = allRecords().any { (it.event.turn ?: 0) >= fromTurn }
        if (!hit) return false
        cutTurnFrom(fromTurn, "drop_from_turn")
        return true
    }

    /** 把 [fromTurn] 及之后所有轮的事件声明为"不算数"（保留摘要） */
    private fun cutTurnFrom(fromTurn: Int, reason: String) {
        val seqs = allRecords().filter { (it.event.turn ?: 0) >= fromTurn }.map { it.seq }
        if (seqs.isEmpty()) return
        append(VisibilityCut(turn = null, seqs = seqs, clearDigest = false, reason = reason))
    }

    /** 真正删除事件流文件（用户显式清空 / 会话被删除）。调用后 seq 从 1 重新开始 */
    fun wipe() {
        log.clear()
        records.clear()
        loaded = true
        cachedProjection = null
    }

    /** 把某一轮的全部事件声明为"不算数"（保留摘要）。
     *
     * ⚠️ **只用于 `dropLastTurn`**（用户显式「重新生成 / 编辑重发」—— 那时用户明确要求
     * 丢掉这一轮的全部痕迹，包括自己说过的话）。
     *
     * 被打断/失败**不走这里**（2026-10-09）：那种情况下必须保留 [UserMessage]，
     * 否则用户说「继续」时模型看不到任何指代对象。分工见 [finishTurn] 的 KDoc。
     */
    private fun cutTurn(turn: Int, reason: String) {
        val seqs = allRecords().filter { it.event.turn == turn }.map { it.seq }
        if (seqs.isEmpty()) return
        append(VisibilityCut(turn = null, seqs = seqs, clearDigest = false, reason = reason))
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

        // ── 摘要精炼（LLM 摘要化）：本地摘要先用（压缩必须无条件成功），精炼是**追加的升级** ──
        // 成功 = 追加一条 shadowedSeqs 为空的 CompactionSummary（投影取最后一次的 text，
        // 恰好是事件层预留的"只重建摘要文本"语义）；失败 = 本地摘要原样保留，什么都不追加。
        // 溢出恢复路径（CONTEXT_OVERFLOW）不精炼：那是用户正在等回复的轮中恢复，不该再加一跳网络。
        if (digestRefiner != null && trigger != CompactionTrigger.CONTEXT_OVERFLOW && removed > 0) {
            // ⚠️ 传**压缩前**的摘要（`before` 的置顶 system 行），不是 `newDigest`：
            // newDigest 是引擎刚追加过新要点的产物，而 LLM 要重写的是"既有结论 + 新增内容"
            // 这两者的**并集**。用 newDigest 会把刚追加的本地首句摘要与即将被替换的旧摘要
            // 混在一起当"旧结论"，而真正的旧结论恰恰在 `before` 里。
            val priorDigest = before.firstOrNull { it.role == ROLE_SYSTEM }?.content
            refineDigest(bodyBefore.take(removed), priorDigest)
        }
        return result
    }

    /**
     * 用 LLM 重写当前滚动摘要（成功后**追加**一条新的 [CompactionSummary] 声明）。
     *
     * 输入 = [priorDigest]（**旧摘要正文**，无则空）+ 刚被折进摘要的那批消息原文
     * （尾部截到 [REFINE_INPUT_MAX_CHARS]）；任何失败（异常 / 空结果 / 超预算空内容）
     * 都静默保留本地摘要 —— 打点留痕即可。
     *
     * ★★ 2026-10-09 修复「累积性遗忘」（这个 bug 让模型**越压缩越忘事**）：
     *
     * 改造前只喂 `shadowed`（**本次**压掉的原文），却用精炼结果**整体替换**摘要文本。
     * 于是第 2 次压缩时，第 1 次压掉的内容**既被 shadowedSeqs 隐藏、也不在新摘要里** ——
     * 永久消失，且摘要行数永远只有一轮。
     *
     * 修法是让精炼器**看得到旧的结论**：旧摘要正文与本次新增原文一起进 prompts，
     * 输出的仍然是**全量摘要**（受 [CompactionPolicy.digestMaxChars] 约束）。
     * 📌 为什么不是"把新要点追加到旧摘要后面"：摘要有行数/字符上限，追加会让它随压缩
     * 次数线性增长 —— 而"摘要越来越长"正是压缩要防的事。让模型在**预算内重写全量**
     * 才能同时满足"不忘事"与"不膨胀"。
     *
     * ⚠️ 旧文件/无摘要时 [priorDigest] 为空，退化成改造前行为（只精炼本次）—— 合法，
     * 因为那时确实没有"旧结论"可丢。
     */
    private fun refineDigest(shadowed: List<SessionRecord>, priorDigest: String?) {
        val ref = digestRefiner ?: return
        // 只取对话本体（UserMessage/AssistantMessage），按时间序拼成 (role, text) 对。
        // assistant 附上本轮工具轨迹（与 SessionProjection 的 traceByTurn 同口径）——
        // 否则 LLM 重写的摘要会丢掉「查了天气/设了闹钟」这类动作证据（2026-10-02 补）。
        val traceByTurn = shadowed.filterIsInstance<ToolCall>()
            .groupBy({ it.turn }, { "${it.name}(${it.args})" })
        val turns = ArrayList<Pair<String, String>>(shadowed.size)
        for (r in shadowed) {
            when (val e = r.event) {
                is UserMessage -> turns.add(ROLE_USER to e.text)
                is AssistantMessage -> {
                    val trace = traceByTurn[e.turn].orEmpty()
                    turns.add(
                        ROLE_ASSISTANT to
                            if (trace.isEmpty()) e.text
                            else e.text + "（调用 " + trace.joinToString("、") + "）",
                    )
                }
                else -> Unit
            }
        }
        if (turns.isEmpty()) return
        // ★ 旧摘要正文（标题行之后的部分）作为**第一条**材料：它代表"更早那批已被压掉的
        //   对话结论"，是本次新增原文里没有的信息。缺了它就是那次累积性遗忘的成因。
        val prior = priorDigest
            ?.lineSequence()
            ?.drop(1)
            ?.filter { it.isNotBlank() }
            ?.toList()
            .orEmpty()
        val items = ArrayList<Pair<String, String>>(turns.size + 1)
        if (prior.isNotEmpty()) {
            items.add("system" to "[更早对话的既有摘要，必须保留其要点]\n" + prior.joinToString("\n"))
        }
        items.addAll(turns)
        // 输入超限时从尾部截：保留最近语境（截点切在消息边界上，不把一条消息劈成两半）。
        // ⚠️ 旧摘要放在**最前**并与尾部截断同权 —— 若它被截掉，就退回改造前的遗忘行为。
        // 它通常只有几百到一千余字符（受 digestMaxChars 约束），而截断阈值 12000，
        // 实际几乎不会被截；真被截时保留"较新"的对话正文是合理的取舍。
        var from = 0
        var total = 0
        for (i in items.indices.reversed()) {
            val next = total + items[i].second.length
            if (next > REFINE_INPUT_MAX_CHARS && i < items.indices.last) break
            total = next
            from = i
        }
        val input = items.subList(from, items.size)
        val budget = (policy.digestMaxChars - BasicCompactionEngine.DIGEST_HEADER.length - 1).coerceAtLeast(60)
        val refined = runCatching { ref.refine(input, budget) }
            .onFailure { onTrim("digest refine failed: ${it.message}（保留本地摘要）") }
            .getOrNull()
            ?.trim()
            ?.take(budget)
            .orEmpty()
        if (refined.isBlank()) return
        append(CompactionStart(turn = null, trigger = "digest_refine"))
        append(
            CompactionSummary(
                turn = null,
                // 空 seq 集 = 不再隐藏任何事件，**只换摘要文本**（投影取最后一次的 text）
                shadowedSeqs = emptyList(),
                text = BasicCompactionEngine.DIGEST_HEADER + "\n" + refined,
                turnsCompressed = 0,
            ),
        )
        append(CompactionEnd(turn = null))
        onTrim("digest refined by LLM (${refined.length} chars, was local extraction)")
    }
}
