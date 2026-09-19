package com.rokidlab.phone.ai.compaction

import com.rokidlab.phone.ai.ChatMessage

/**
 * 压缩的**触发入口**（对齐 deepseek-harness 的 `ctx.compaction` 三入口）。
 *
 * 为什么要把"谁触发的"当成一等信息传进来：三种触发的**可接受代价完全不同** ——
 * 压力裁剪只是顺手收一收（用户无感），溢出恢复是在"整轮已经失败"之后的补救
 * （必须一次到位、可以牺牲上下文），手动压缩是用户明确要求腾地方（必须真的腾出来）。
 * 改造前它们全挤在 `recordTurn` 里一个 `while` 循环里，只有一个隐式的"超了就压"。
 */
internal enum class CompactionTrigger {
    /** 压力：记录新一轮后发现超出预算，顺手把最旧轮压进滚动摘要（改造前的既有行为） */
    PRESSURE,

    /** 溢出：模型返回「上下文超长」被拒后的补救（改造前**没有**这个入口，只能让用户换个短点的问题） */
    CONTEXT_OVERFLOW,

    /** 手动：用户在上下文面板里点「立即压缩」，低于阈值也压 */
    MANUAL,
}

/**
 * 一次压缩的**结果**。
 *
 * 存在的理由：压缩是"静默丢信息"的操作，改造前唯一的痕迹是一行 `Log.d("trim: ...")`。
 * 于是用户问「它怎么忘了前面的」时，没有任何可归因的事实。把结果量化成
 * 「压了几轮 / 释放了多少字符 / 压缩前后规模」之后，日志、UI、以及将来的用量统计
 * 都能引用同一份事实。
 */
internal data class CompactionResult(
    val trigger: CompactionTrigger,
    /** 本次被压进摘要的轮数（user+assistant 一对算一轮） */
    val turnsCompressed: Int,
    /**
     * 被**直接丢弃**（没进摘要）的消息条数。
     *
     * 正常路径恒为 0；>0 说明历史里出现了异常形态（如置顶摘要后跟一条孤立 assistant）。
     * 单独计量而不是并进 [turnsCompressed]：丢消息是**信息净损失**，
     * 与"折进摘要（要点还在）"是两件不同性质的事，混在一起会让日志看起来比实际更健康。
     */
    val messagesDropped: Int = 0,
    /** 压缩后摘要里的要点行数 */
    val digestLines: Int,
    val messagesBefore: Int,
    val messagesAfter: Int,
    val charsBefore: Int,
    val charsAfter: Int,
) {
    /** 释放的字符数（可能为负 —— 压进去的要点比原文长，理论上不会，但不要假设） */
    val freedChars: Int get() = charsBefore - charsAfter

    /** 是否真的改变了历史（未超预算时 [CompactionEngine.compactIfNeeded] 返回 null 而不是空结果） */
    val changed: Boolean get() = turnsCompressed > 0

    override fun toString(): String =
        "trigger=$trigger turns=$turnsCompressed ${charsBefore}->${charsAfter} chars " +
            "(${messagesBefore}->${messagesAfter} msgs, digest=${digestLines} lines)"
}

/**
 * 会话压缩引擎的**接口**（Service Definition）。
 *
 * 默认实现是 [BasicCompactionEngine]（= 改造前的"滚动摘要"，行为逐字保持）。
 * 抽成接口不是为了将来换实现那么简单 —— 而是为了让**压缩策略成为可测的、可替换的纯逻辑**：
 * 改造前它长在 `AgentSessionHistory` 内部，和过期清理、条数统计、UI 快照混在一个类里，
 * 想验证"压缩会不会破坏消息配对"只能把整个类跑起来。
 *
 * ⚠️ **刻意不照搬 DSH 的两点**（写在这里，免得后来者以为漏了）：
 *  1. DSH 的锁要把「摘要可能是一次模型调用」这件事圈进去，中途崩溃留**孤儿锁**可检测。
 *     我们这里用 `AgentSessionHistory` 自带的 `synchronized` + 摘要纯本地提取（不发请求），
 *     进程内并发已经够；孤儿锁需要**持久化**的会话日志才有意义，等阶段一事件流落地再补。
 *  2. DSH 校验 `toolPairingBalancedBefore/After`（tool-call/result 配对）。但我们的**持久历史里
 *     没有 tool 消息** —— 中间态只在单次请求内有效（见 `AgentSessionManager` 类注释），
 *     所以这里校验的等价物是**轮完整性** [isTurnBalanced]（user/assistant 严格交替、无孤立消息）。
 *     真正需要 tool 配对平衡的是**请求内**的 messages 数组，那件事在
 *     `AiConversationService` 的溢出恢复里以"整体重建"的方式规避（不拆半对）。
 */
internal interface CompactionEngine {

    /** 当前策略（阈值、摘要上限、保留轮数） */
    val policy: CompactionPolicy

    /**
     * 当前是否已经超出预算（**不改变历史**，可在任意线程调用）。
     *
     * 与 [compactIfNeeded] 分开是为了让"快满了"这件事可被观察（UI 进度条、日志），
     * 而不是只有"已经被压了"才知道。
     */
    fun pressure(history: List<ChatMessage>): Boolean

    /**
     * 压力触发：**超出预算才压**，未超出返回 `null`。
     *
     * @param history 就地修改（压缩会移除被压的轮、追加/更新置顶摘要）
     */
    fun compactIfNeeded(
        history: MutableList<ChatMessage>,
        trigger: CompactionTrigger = CompactionTrigger.PRESSURE,
    ): CompactionResult?

    /**
     * 强制压缩：无论是否超预算，把历史收敛到「滚动摘要 + 最近 [CompactionPolicy.keepRecentTurns] 轮」。
     *
     * 用于两个场景：用户手动点「立即压缩」；以及上下文溢出被服务端拒绝后的补救
     * （这时"少留一点上下文"和"整轮失败"相比，前者几乎总是更好的选择）。
     *
     * @return 未发生任何压缩（已经足够短）时返回 `null`
     */
    fun compactNow(
        history: MutableList<ChatMessage>,
        trigger: CompactionTrigger = CompactionTrigger.MANUAL,
    ): CompactionResult?

    /**
     * 轮完整性校验：除置顶摘要（system）外，历史必须是 user/assistant 严格交替且以 assistant 结尾。
     *
     * 破坏它意味着出现了"孤立消息"—— 压缩算法把一轮拆成了半轮。历史本身不会因此报错，
     * 但它会以**两条连续 user 消息**的形式进到请求里，部分服务端会直接 400
     * （同样的问题在 `AiConversationService` 的收尾阶段踩过）。
     * 所以压缩后必须校验，且校验要能被单测钉住。
     */
    fun isTurnBalanced(history: List<ChatMessage>): Boolean

    /** 摘要要点行数（0 = 还没压缩过） */
    fun digestLines(history: List<ChatMessage>): Int
}
