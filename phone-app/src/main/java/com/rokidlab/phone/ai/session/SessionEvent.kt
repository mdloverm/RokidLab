package com.rokidlab.phone.ai.session

/**
 * 会话事件流的**事件类型**（对齐 deepseek-harness 的 `SessionEventMap → derived-union`）。
 *
 * ★ 为什么要有这个包：改造前"这个会话发生过什么"在**两条互不相干的线**上各存一份 ——
 *   UI 的聊天记录（`store/ChatHistoryStore`，`ChatMsg` 行，可编辑所以会整份重写）与
 *   Agent 的记忆（`ai/AgentSessionHistory`，**纯内存**、全局单例、重启即失忆、不绑定会话）。
 *   两条线的症状是真机的：切到另一个会话 AI 还记得上一个会话的内容；重启之后历史消息都
 *   还在、AI 却对它一无所知；工具调用只在压缩预算里当字符串用，无法回答"上一轮到底调了
 *   什么、返回了什么"。根因是**没有一个权威的、只追加的时间线**。
 *
 * 本包就是那条时间线：事件只追加、不改写，UI 与 Agent 都是它的**投影**。
 * 于是"模型可见即可回溯"从口号变成数据模型 —— 模型看到的每一条消息都能追到具体事件与 seq。
 *
 * ★ 为什么用 sealed interface 而不是 enum：DSH 用 TS 的可扩展联合类型（声明合并）表达
 *   事件，好处是新增事件时所有消费点会被类型系统提醒。Kotlin 的 `sealed interface` 是
 *   原生等价物，而且更强 —— `when` 分支**必须穷尽**，漏一个直接编译不过（TS 那边靠约定）。
 *
 * ★ 枚举不留退路：[SessionEventKind] / [TurnEndReason] / [MessageSource] / [AttemptOutcome]
 *   都带一个 **wire 字符串**。落盘用的是它，所以**一经发布不可改名**（改了 = 读不懂旧文件，
 *   等于把用户的历史弄丢）。新增取值只能往后加。
 *
 * ⚠️ **刻意不做**（免得后来者以为漏了）：
 *  1. 事件**不带** `seq` / `ts` —— 那是日志的属性（[SessionRecord]），不是事件的属性。
 *     DSH 把它们放在事件上，是因为 TS 那边事件即记录；Kotlin 里拆开后，
 *     "同一个事件被记录两次会得到不同 seq"这件事在类型上就不可能搞错，append 也不必回填字段。
 *  2. `step` 只做记录不做语义 —— 改造前没有任何地方按 step 聚合，先留着占位，
 *     等真需要"轮内第几步"时再赋予含义，避免现在编一套没人用的规则。
 */
internal sealed interface SessionEvent {

    /** 事件类型（落盘标签 + 投影分派依据） */
    val kind: SessionEventKind

    /** 归属轮次；`null` = 非轮次事件（如手动压缩） */
    val turn: Int?

    /** 轮内步骤序号（预留，当前写多写少都不影响语义） */
    val step: Int?

    /**
     * 是否进入**模型可见历史**。
     *
     * 声明在类型上而不是散在投影的 `when` 里：新增事件类型时，
     * "它要不要进模型上下文"这个问题会在**定义处**被回答一次，
     * 而不是等某天发现模型莫名其妙看到了不该看的东西才回头查。
     */
    val modelVisible: Boolean get() = false
}

// ═══════════════════════ 类型标签（wire 一经发布不可改名）═══════════════════════

internal enum class SessionEventKind(val wire: String) {
    TURN_START("turn_start"),
    TURN_END("turn_end"),
    USER_MESSAGE("user"),
    ASSISTANT_ATTEMPT("attempt"),
    ASSISTANT_MESSAGE("assistant"),
    TOOL_CALL("tool_call"),
    TOOL_RESULT("tool_result"),
    CONTEXT_INJECT("context"),
    COMPACTION_START("compaction_start"),
    COMPACTION_SUMMARY("compaction_summary"),
    COMPACTION_END("compaction_end"),

    /** 若干事件退出模型可见历史（见 [VisibilityCut]） */
    VISIBILITY_CUT("cut"),

    /**
     * 一次**已授权、即将产生副作用**的声明（2026-10-09，对齐 Muse/MSP 的 `side_effect_intent`）。
     *
     * ★ 为什么要有它（这是本项目最缺的一条不变式）：
     *   改造前事件流只记 `ToolCall`（意图）与 `ToolResult`（结果），**中间的授权是空的**。
     *   于是"这次副作用是谁点头放行的"无法回答 —— 审计日志能证明"做了什么"，
     *   却证明不了"凭什么允许"。排查误操作时缺的那一环正在这里。
     *
     * ★ **不变式（可钉成单测）**：授权与意图必须**先于**副作用对外可见落盘。
     *   即 `SideEffectIntent` 的 seq 必须小于同一次调用的 `ToolResult` 的 seq。
     *   —— 这就是 write-ahead log：崩溃恢复时能知道"哪些效果已经在路上"。
     *   log-after-execution 不行 —— 动作与日志之间崩了，恰好在最需要信息的地方留洞。
     *
     * ★ **反向不变式同样重要**：未授权的副作用**根本不产生这条事件**。
     *   拒绝 ⇒ 日志里查不到"差点发生什么"。这比"记一条 denied"更好审计：
     *   后者需要在读的时候正确区分"记了但没做"，前者让"不存在"本身就是证据。
     *
     * ⚠️ **不进模型可见历史**（`modelVisible` 默认 false）：它是审计事实，
     *   不是给模型看的上下文。把授权历史塞进 messages 会让每轮白烧 token，
     *   且模型不需要知道"上次是谁点头的"。
     */
    SIDE_EFFECT_INTENT("effect_intent"),
    ;

    companion object {
        /** 未知标签返回 null（未来版本写入的新类型，当前版本跳过而不是崩） */
        fun fromWire(wire: String): SessionEventKind? = entries.firstOrNull { it.wire == wire }
    }
}

/** 一轮为什么结束（对齐 DSH 的 `TurnEndReasonMap`） */
internal enum class TurnEndReason(val wire: String) {
    /** 正常给出回复 */
    COMPLETED("completed"),

    /** 被新消息 / 用户打断抢占（[com.rokidlab.phone.domain.AiConversationService] 的 isSuperseded 路径） */
    INTERRUPTED("interrupted"),

    /** 模型或网络失败 */
    FAILED("failed"),

    /** 工具轮次预算用尽（用户说「继续」可接着做） */
    BUDGET_EXHAUSTED("budget_exhausted"),
    ;

    companion object {
        fun fromWire(wire: String): TurnEndReason? = entries.firstOrNull { it.wire == wire }
    }
}

/** 用户消息的来源（对齐 DSH 的 `MessageSourceMap`） */
internal enum class MessageSource(val wire: String) {
    /** 手机输入框 */
    TEXT("text"),

    /** 眼镜语音 */
    VOICE("voice"),

    /** 拍照答题（一次性问答，`recordHistory = false` 时不落事件流） */
    IMAGE("image"),

    /** 来源未知（旧数据 / 程序化注入） */
    UNKNOWN("unknown"),
    ;

    companion object {
        fun fromWire(wire: String): MessageSource? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * 一次**失败尝试**的结局。
 *
 * 存在的理由：改造前请求失败重试是"看不见的" —— 用户只看到最终成功或最终失败，
 * 中间那两次 400/超时没有任何痕迹。把失败尝试作为事件记下来（但**不进模型历史**，
 * 见 [AssistantAttempt]），"这轮为什么慢 / 为什么最后报错"才有可归因的事实。
 */
internal enum class AttemptOutcome(val wire: String) {
    FAILED("failed"),
    TIMEOUT("timeout"),

    /** 被新一轮抢占（用户抢话），不是错误 */
    SUPERSEDED("superseded"),

    /** 上下文溢出被服务端拒（随后由溢出恢复重建请求） */
    OVERFLOW("overflow"),
    ;

    companion object {
        fun fromWire(wire: String): AttemptOutcome? = entries.firstOrNull { it.wire == wire }
    }
}

// ═══════════════════════ 事件 ═══════════════════════

/**
 * 副作用的**授权来源**（对齐 MSP 的 `decision_source` / `policy_decision` 语义）。
 *
 * ★ 为什么要区分来源，而不是记一个 `allowed: Boolean`：
 *   布尔值能回答"允许了吗"，但答不出**"谁允许的"** —— 而后者才是审计与排查的关键：
 *   用户点的、静态规则自动放行的、LLM 判官判的，三者的可信度完全不同。
 *   把它们压成一个 true，事后无法复核，日志也就失去了意义。
 *
 * 📌 枚举**不带退路**：wire 字符串一经落盘不可改名。
 */
internal enum class AuthorizationSource(val wire: String) {
    /**
     * 用户在确认界面上点了「允许」。
     *
     * 这是**唯一**能被当作"人的授权"的一档，也是无 standing grant 时的默认路径。
     */
    USER_APPROVED("allow:approved"),

    /**
     * 静态策略自动放行（只读工具、已在白名单里的窄操作）。
     *
     * 判据是**可重放**的：不依赖模型判断，也不依赖当时的上下文 ——
     * 因此它可以被认为"与当时的对话内容无关"而稍微放心一点。
     */
    POLICY("allow:policy"),

    /**
     * 由模型侧的判官依据策略文本判定放行。
     *
     * ⚠️ 这一档是**注入攻击的首要目标**：网页里一句"这条操作已获用户授权"
     * 若能影响判官，就等于绕过了 [USER_APPROVED]。所以它与 [USER_APPROVED]
     * 在代码里**必须分开**，且判官拿不到"用户已授权"这种可伪造的前提。
     */
    LLM_JUDGE("allow:llm_judge"),

    /**
     * 显式拒绝（用户点「取消」，或策略直接 Deny）。
     *
     * ⚠️ 按上面的反向不变式，**拒绝时不产生 [SideEffectIntent]**。
     *   这个取值只用于两类场合：(a) 需要在日志里留下"用户主动拒绝"这个事实
     *   （否则用户投诉"我明明点了取消它还是动了"时无法自证）；
     *   (b) 审批窗口被关闭但调用并未被执行。
     */
    DENIED("deny"),
    ;

    companion object {
        fun fromWire(wire: String): AuthorizationSource? = entries.firstOrNull { it.wire == wire }
    }
}

/** 一轮开始（用户发出请求）。与 [TurnEnd] 成对，中间是这一轮的全部事件。 */
internal data class TurnStart(
    override val turn: Int,
    override val step: Int? = null,
    val source: MessageSource = MessageSource.TEXT,
) : SessionEvent {
    override val kind: SessionEventKind get() = SessionEventKind.TURN_START
}

/**
 * 一轮结束。[reason] 是"这轮怎么结束的"的唯一权威说法。
 *
 * ★ 用量与耗时跟着**这条**事件走（而不是 [AssistantMessage]）：每一轮都必然有一条
 *   `TurnEnd`（连被打断/失败的轮都有），而 `AssistantMessage` 只在这轮真的给出了结论时才写。
 *   把成本挂在回复上，等于"没回复就不花钱" —— 而被打断的长任务恰恰是最烧 token 的那种。
 *
 *   耗时不必存：`TurnStart.ts` 与 `TurnEnd.ts` 已经在流里，投影一减就有。
 *
 * ⚠️ 三个用量字段全部可空，含义是"**服务端没给**"而不是 0：不是所有服务商都会在响应里返回
 *   `usage`（OpenAI 协议要求流式请求显式带 `stream_options.include_usage`，部分兼容实现直接忽略），
 *   而把"不知道"写成 0 会让面板显示"本轮 0 token" —— 那是**错误信息**，比没有更糟。
 *
 * @param promptTokens 本轮**所有模型调用**的输入 token 合计（多轮工具循环 = 多次请求，成本要累加）
 * @param completionTokens 同上，输出 token 合计
 * @param promptCacheHitTokens 输入中命中服务端前缀缓存的部分（DeepSeek 自动缓存；null = 服务端没给）。
 *        与 [promptTokens] 分开记的原因：命中率是「请求前缀逐字节稳定」优化的直接度量，
 *        连续为 0 = 前缀稳定性退化，要看警报而不是翻账单
 * @param modelCalls 本轮的模型调用次数（>1 说明走了工具循环，用量偏高时这是第一解释）
 * @param partialReply 被打断/失败时**已经产出**的那部分正文（null = 一字未出）。
 *        存在的理由见 [AssistantMessage.partial]：半截内容此前只推给了 UI，事件流里没有，
 *        于是模型看不见自己说过什么。空串按 null 处理（"空字符串"不是有效正文）。
 */
internal data class TurnEnd(
    override val turn: Int,
    override val step: Int? = null,
    val reason: TurnEndReason,
    /** 失败/中断时的补充说明（异常消息、预算原因），正常完成时为 null */
    val detail: String? = null,
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val promptCacheHitTokens: Int? = null,
    val modelCalls: Int? = null,
    val partialReply: String? = null,
) : SessionEvent {
    override val kind: SessionEventKind get() = SessionEventKind.TURN_END
}

/**
 * 用户消息 —— **模型可见**。
 *
 * 与 [TurnStart] 的分工：TurnStart 是"发生了一轮"这个事实（无论用户说了什么，
 * 甚至用户一个字都没说、只是触发了一次刷新），UserMessage 是"用户说了什么"。
 * 投影只消费后者，所以"空输入的轮"不会往模型上下文里塞一条空消息。
 */
internal data class UserMessage(
    override val turn: Int,
    override val step: Int? = null,
    val text: String,
    val source: MessageSource = MessageSource.TEXT,
) : SessionEvent {
    override val kind: SessionEventKind get() = SessionEventKind.USER_MESSAGE
    override val modelVisible: Boolean get() = true
}

/**
 * 一次**失败尝试** —— **不进模型历史**。
 *
 * 这是事件流最直接的一处收益：改造前"失败尝试"根本没有位置可放，
 * 于是它要么污染上下文（把失败的半截回复记进去），要么彻底消失（连日志都没有）。
 */
internal data class AssistantAttempt(
    override val turn: Int,
    override val step: Int? = null,
    val outcome: AttemptOutcome,
    /** 失败详情（异常类型 / HTTP 状态 / 溢出提示），仅日志用 */
    val detail: String? = null,
) : SessionEvent {
    override val kind: SessionEventKind get() = SessionEventKind.ASSISTANT_ATTEMPT
}

/**
 * AI 回复 —— **模型可见**。
 *
 * 工具轨迹**不在这里存**：它由同一轮的 [ToolCall] 事件派生（见 `SessionProjection`）。
 * 改造前 `ChatMessage.toolTrace` 是一份 `"name(args)"` 字符串列表，与工具调用本身
 * 各存一份，于是"轨迹里有、结构化记录里没有"这种不一致没有东西能发现。
 *
 * ⚠️ [partial] = 这条回复是**被打断时的半截输出**，不是完整结论（2026-10-09）。
 *
 * ★ 为什么半截也要落盘：改造前被打断轮的回复**只存在于 UI 气泡**（流式 delta 已推给界面），
 *   事件流里一条 [AssistantMessage] 都没有。症状是"用户看得见 AI 说到一半，说「继续」，
 *   模型却完全不记得自己说过什么" —— 屏幕上白纸黑字写着，模型上下文里空无一物。
 *   半截落盘后模型至少知道"我刚才停在哪、已经说了什么"，续做才有起点。
 *
 *   刻意**不**因此改变可见性：半截仍然是模型可见的（那正是它的用途）。
 *   判据上它与完整回复的区别只有"未完成"这一点，靠 [partial] 显式承载 ——
 *   不靠文本猜测，也不靠"这条 assistant 恰好很短"。
 */
internal data class AssistantMessage(
    override val turn: Int,
    override val step: Int? = null,
    val text: String,
    /** true = 被打断/失败时的半截输出，非完整结论（见类注释） */
    val partial: Boolean = false,
) : SessionEvent {
    override val kind: SessionEventKind get() = SessionEventKind.ASSISTANT_MESSAGE
    override val modelVisible: Boolean get() = true
}

/**
 * 模型请求了一次工具调用。
 *
 * [args] 保留**原始 JSON 文本**而不是解析成对象：模型给非法 JSON 是常态，
 * 解析失败就丢字段会让"它到底传了什么"变成永远查不清的问题（真机上排查过多次）。
 */
internal data class ToolCall(
    override val turn: Int,
    override val step: Int? = null,
    val callId: String,
    val name: String,
    val args: String,
) : SessionEvent {
    override val kind: SessionEventKind get() = SessionEventKind.TOOL_CALL
}

/**
 * 工具执行结果（与 [ToolCall] 通过 [callId] 配对）。
 *
 * [truncated] = 内容因超长被截断（落盘与下发都有上限），**必须显式标记** ——
 * 否则"模型看到的和文件里存的不一样"这件事无从判断。
 */
internal data class ToolResult(
    override val turn: Int,
    override val step: Int? = null,
    val callId: String,
    val name: String,
    val content: String,
    val truncated: Boolean = false,
) : SessionEvent {
    override val kind: SessionEventKind get() = SessionEventKind.TOOL_RESULT
}

/**
 * 注入到本轮提示词的上下文（长期记忆 / 知识库检索 / 技能清单 / 续做任务）。
 *
 * 记下来是为了回答"模型当时到底看到了什么" —— 改造前这些注入是**一次性**的：
 * 拼进提示词、发出去、消失，用户问"它怎么知道这个"时无从回溯。
 *
 * [truncated] 同 [ToolResult]：知识库检索块可能很长，超上限截断并标记。
 */
internal data class ContextInject(
    override val turn: Int,
    override val step: Int? = null,
    /**
     * 注入来源分类：memory / knowledge / skill / task / plan。
     *
     * ⚠️ 刻意**不叫** `kind` —— `SessionEvent.kind` 已经用来表示"事件类型"了，
     * 同名会让 `SessionEventKind` 被这个 `String` 遮蔽（编译器直接报
     * "hides member of supertype"），是踩过的坑。
     */
    val origin: String,
    val content: String,
    val truncated: Boolean = false,
) : SessionEvent {
    override val kind: SessionEventKind get() = SessionEventKind.CONTEXT_INJECT
}

/** 压缩开始（[turn] 可为 null —— 手动压缩不归属任何一轮） */
internal data class CompactionStart(
    override val turn: Int?,
    override val step: Int? = null,
    /** 触发的策略：值取自 `ai.compaction.CompactionTrigger`（避免 AI 包反向依赖，这里存字符串） */
    val trigger: String,
) : SessionEvent {
    override val kind: SessionEventKind get() = SessionEventKind.COMPACTION_START
}

/**
 * 压缩产物：**滚动摘要的新版本** + 它替代掉了哪些事件。
 *
 * ★ 这是"压缩不再销毁历史"的关键：改造前压缩把旧消息从内存里**删掉**，
 * 删了就永远回不来了；现在压缩只是**追加一条摘要事件**，声明"这些 seq 已被摘要覆盖"。
 * 投影时把它们排除在模型上下文之外，但它们**仍在文件里** —— 所以
 * 「上一轮到底聊了什么」永远查得到，而模型上下文该省还是省了。
 *
 * [text] 是**全量摘要**（含标题行），不是增量：投影取最后一条的这份，
 * 于是"当前摘要是什么"不需要回放所有压缩事件去重算。
 */
internal data class CompactionSummary(
    override val turn: Int?,
    override val step: Int? = null,
    /** 被本摘要替代的事件 seq（[UserMessage] / [AssistantMessage]） */
    val shadowedSeqs: List<Long>,
    /** 滚动摘要全文（`[更早对话摘要]` + 要点行） */
    val text: String,
    /** 本次折进摘要的轮数（0 = 只是重建摘要文本，没有新增要点） */
    val turnsCompressed: Int = 0,
) : SessionEvent {
    override val kind: SessionEventKind get() = SessionEventKind.COMPACTION_SUMMARY
}

/** 压缩结束。[error] 非 null = 压缩失败（历史保持原样，不半途而废） */
internal data class CompactionEnd(
    override val turn: Int?,
    override val step: Int? = null,
    val error: String? = null,
) : SessionEvent {
    override val kind: SessionEventKind get() = SessionEventKind.COMPACTION_END
}

/**
 * 若干事件**退出模型可见历史** —— 不是删除，它们仍在文件里。
 *
 * ★ 为什么需要它：改造前有两个操作会**直接改内存历史**，而事件流是只追加的：
 *  1. **整段清空**（v3.9 及更早版本的「10 分钟无活动自动清空」，以及手动清空）—— 清掉全部历史；
 *  2. **丢弃最后一轮**（`dropLastTurn`，「重新生成 / 编辑重发」）—— 只清末尾那一轮，
 *     否则模型会在上下文里看到自己上一版答案，要么照抄要么刻意绕开。
 *  两者都不能表达成"删行"（那会破坏只追加这条核心不变式），所以改为**追加一条声明**：
 *  "这些 seq 从此不进模型上下文"。
 *
 * ★ [clearDigest] 区分两种语义，这个区别不能省：
 *  - 整段清空 = 真的从头开始，**滚动摘要也是上一段对话的记忆**，必须一起清
 *    （只清消息不清摘要，模型会带着"更早对话摘要"继续聊，看起来像没清干净）；
 *  - 丢弃最后一轮 = 只是这一轮不算数，**摘要要保留**（它代表更早的、仍然有效的记忆）。
 *
 * ⚠️ 2026-09-20：清空改走**真删文件**（`AgentSessionStore.wipe`），生产代码不再构造
 *    `clearDigest = true` 的声明；但这个字段**必须留着** —— v3.9 及更早版本的安装包里落过
 *    这类事件，读旧文件时仍要按原语义把摘要一起遮掉（[SessionProjection] 仍在读它）。
 *
 * ⚠️ 与 [CompactionSummary] 的分工：那个说"这些被**摘要覆盖**了"（信息还在，只是换了形态），
 * 这个说"这些**不算数**了"。投影里 [SessionProjection.shadowedSeqs] 与
 * [SessionProjection.hiddenSeqs] 分开暴露，正是为了让这个区别在 UI/诊断上看得见 ——
 * 否则"上下文变小了"到底是压掉了还是清掉了，用户和我们都分不出来。
 */
internal data class VisibilityCut(
    override val turn: Int?,
    override val step: Int? = null,
    /** 退出模型可见历史的记录 seq */
    val seqs: List<Long>,
    /** 连滚动摘要一起清掉（过期清空 / 手动清空 = true；丢弃最后一轮 = false） */
    val clearDigest: Boolean = false,
    /** 原因（历史值：`expire` / `drop_last_turn` / `manual_clear`），落盘供人回溯"这轮为什么不算数" */
    val reason: String,
) : SessionEvent {
    override val kind: SessionEventKind get() = SessionEventKind.VISIBILITY_CUT
}

/**
 * 一次**已授权的副作用**声明 —— 写前日志的那一"写前"（对齐 MSP `side_effect_intent`）。
 *
 * ## 存在的理由
 *
 * 改造前事件流记 `ToolCall`（模型想做什么）与 `ToolResult`（实际发生了什么），
 * **中间的授权是空的**。于是审计只能证明"做了什么"，证明不了"凭什么允许"——
 * 而后者才是"用户说它乱动我的东西"时唯一能回答的问题。
 *
 * ## 不变式（单测直接盯住，别指望别的手段顺带保证）
 *
 * 1. **顺序**：本事件的 seq 必须**小于**同一次调用的 [ToolResult] 的 seq。
 *    即授权在效果对外可见之前已经落盘 —— 这才是 write-ahead log 的意义。
 * 2. **反向**：未授权的副作用**不产生本事件**。拒绝 ⇒ 日志里查不到"差点发生"。
 *    "不存在"本身就是证据，比"记一条然后靠读的人正确区分"强得多。
 *
 * ## 为什么 [turn] 可为 null
 *
 * 无主线的后台动作（定时自主任务）也要有授权记录。这类调用不属于任何一轮，
 * 落成 [turn] = null 与 [CompactionStart] 的做法一致。
 */
internal data class SideEffectIntent(
    override val turn: Int?,
    override val step: Int? = null,
    /** 工具名（与 [ToolCall.name] 同一套命名） */
    val name: String,
    /** 触发这次调用的模型 callId；后台动作无模型调用时为 null */
    val callId: String? = null,
    /** 授权来自哪一档（见 [AuthorizationSource]） */
    val source: AuthorizationSource,
    /** 给用户看的操作摘要（留痕用；不含模型可控文本） */
    val summary: String? = null,
    /**
     * 分阶段审批时的阶段判定摘要（对齐 MSP 的 `stage_evidence`）。
     *
     * ⚠️ 刻意**只记各阶段自身的结果、不记 argv 明细**：argv 可能很长，
     *   且这里的目的只是回答"这一串命令里哪几步需要人点头"，
     *   完整 argv 已经由 [ToolCall.args] 记着了（保留原始 JSON）。
     */
    val stageEvidence: List<String> = emptyList(),
) : SessionEvent {
    override val kind: SessionEventKind get() = SessionEventKind.SIDE_EFFECT_INTENT
}
