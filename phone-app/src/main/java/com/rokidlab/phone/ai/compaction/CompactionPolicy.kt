package com.rokidlab.phone.ai.compaction

/**
 * 会话压缩的**参数面**（`compaction` 接缝的 Service Definition 数据面）。
 *
 * 存在的理由：改造前这些数字全部是 `AgentSessionHistory` 里的 `private val` 硬编码 ——
 * 12 条 / 6000 字符 / 800 摘要字符 / 10 分钟。它们既不表达"为什么是这个数"，
 * 也无法跟着**模型真实的上下文窗口**变：给本地 `num_ctx=4096` 的小模型塞 6000 字符历史，
 * 结果不是"记性好"，而是整轮请求 400、用户直接拿不到回复（`llm` 接缝给出的
 * `ModelCapabilities.contextWindow` 正是为这件事准备的真实数字）。
 *
 * ⚠️ **默认值一个都没改**（[DEFAULT] 与改造前逐字一致）。窗口推导走 [forWindow]，
 * 规则是**双向**的（2026-09-19 修订，原来只有"只收紧不放宽"）：
 *  - 小窗口 → 收紧（修的是溢出 bug，改造前就在做）；
 *  - 大窗口 → **放宽**（原来刻意不做，理由是"产品决策，不在本次范围"）。
 *    真机上这条限制的代价很直观：deepseek 131072 窗口、系统+工具每轮固定吃掉约 1 万 token，
 *    而历史只给 6000 字符（≈6 轮）—— 模型明明装得下，却每聊几轮就开始"忘了"前面。
 *    ⇒ 现在按同一个份额推导放宽，并给绝对天花板（[MAX_CHARS_CEIL]）防止窗口很大时单请求失控。
 *
 * @param maxMessages 历史消息条数上限（user+assistant 合计）
 * @param maxChars 历史总字符数上限
 * @param digestMaxChars 滚动摘要自身的字符上限（防止摘要随对话无限膨胀）
 * @param expireMs 无活动多久算新会话（自动清空）
 * @param keepRecentTurns [CompactionEngine.compactNow] 强制压缩时保留的最近轮数
 */
internal data class CompactionPolicy(
    val maxMessages: Int = DEFAULT_MAX_MESSAGES,
    val maxChars: Int = DEFAULT_MAX_CHARS,
    val digestMaxChars: Int = DEFAULT_DIGEST_MAX_CHARS,
    val expireMs: Long = DEFAULT_EXPIRE_MS,
    val keepRecentTurns: Int = DEFAULT_KEEP_RECENT_TURNS,
) {
    companion object {
        /** 约 6 轮对话 */
        const val DEFAULT_MAX_MESSAGES = 12

        const val DEFAULT_MAX_CHARS = 6000

        const val DEFAULT_DIGEST_MAX_CHARS = 800

        /** 语音场景：10 分钟无人说话即视为新会话 */
        const val DEFAULT_EXPIRE_MS = 10 * 60 * 1000L

        const val DEFAULT_KEEP_RECENT_TURNS = 2

        /**
         * 字符 / token 经验换算系数。
         *
         * 中文为主，1 token ≈ 1.5~2 个汉字。取 **1.6**（偏小）是刻意的：同一个窗口下
         * 算出的字符额度更少 ⇒ 更早触发压缩 ⇒ 宁可多压一轮，也不要溢出。
         * （这里不是精确 tokenizer —— 只是为了给一个**单调、可解释**的上限；
         * 精确账见事件流里的真实 usage。）
         */
        private const val CHARS_PER_TOKEN = 1.6

        /**
         * 历史最多占上下文窗口的比例。
         *
         * 窗口的其余部分要留给：全部工具 schema（39 个）、人设 / 长期记忆 / 知识库注入、
         * 以及单轮输出预算（8192，开思考时 32000）。取 **15%** 是刻意保守 ——
         * 它的作用不是"把窗口用满"，而是给历史一个**可解释、随窗口缩放**的份额。
         */
        private const val HISTORY_SHARE = 0.15

        /** 字符上限的下限：比这还小就完全没有上下文可言了，宁可让它溢出也不要退化到失忆 */
        private const val MIN_CHARS = 600

        /**
         * 放宽侧的**绝对天花板**（约 3.75 万 token 的历史）。
         *
         * 为什么必须有：份额制在超大窗口（1M）下会推算出 24 万字符，
         * 那意味着每轮请求都要重发 15 万 token —— 成本与延迟都不可接受，
         * 而**收益早已递减**（对话历史再长，模型能有效利用的也是最近一段）。
         */
        const val MAX_CHARS_CEIL = 60000

        /** 条数上限的放宽天花板（≈ 60 轮） */
        const val MAX_MESSAGES_CEIL = 120

        /** 摘要上限的放宽天花板 */
        const val MAX_DIGEST_CEIL = 6000

        /**
         * 平均一条消息的字符数（= [DEFAULT_MAX_CHARS] / [DEFAULT_MAX_MESSAGES]）。
         *
         * 用它把字符额度换算成条数：默认档下 6000 / 500 = 12，与改造前的硬编码值**恰好相等**，
         * 所以默认行为一点没变；额度放大时条数同步放大，否则"字符数放开了、却仍被 12 条卡死"。
         */
        private const val CHARS_PER_MESSAGE = 500

        /** 摘要额度 = 历史额度的 1/10（默认档下 600，低于 800 的下限，因此默认仍取 800） */
        private const val DIGEST_DIVISOR = 10

        /** 摘要上限的下限 */
        private const val MIN_DIGEST_CHARS = 120

        /** 默认策略（= 改造前的硬编码值，行为不变） */
        val DEFAULT = CompactionPolicy()

        /**
         * 按模型上下文窗口推导策略 —— **双向**（小窗口收紧、大窗口放宽）。
         *
         * 四条规则：
         *  1. 窗口未知（`contextWindow <= 0`，见 `ModelCapabilities`）→ 原样返回 [DEFAULT]。
         *     "不知道"不能变成"给你调小"，那会平白损失用户的上下文。
         *  2. 推导额度 < 默认值 → **收紧** [maxChars] 与 [digestMaxChars]；[maxMessages] 不动
         *     （这个档位下真正的约束是字符数：600 字符早已把 12 条压到更少）。
         *  3. 推导额度 = 默认值 → 原样返回 [DEFAULT]。
         *  4. 推导额度 > 默认值 → **放宽** [maxChars]、[maxMessages]、[digestMaxChars] 三者，
         *     并各自受天花板约束。三者必须一起放 —— 只放字符数会被 12 条卡死（症状与没改一样）。
         *
         * @param contextWindowTokens 模型上下文窗口（token）；0 = 未知
         */
        fun forWindow(contextWindowTokens: Int): CompactionPolicy {
            if (contextWindowTokens <= 0) return DEFAULT
            val charCap = (contextWindowTokens * CHARS_PER_TOKEN * HISTORY_SHARE)
                .toInt()
                .coerceIn(MIN_CHARS, MAX_CHARS_CEIL)
            if (charCap < DEFAULT_MAX_CHARS) {
                return DEFAULT.copy(
                    maxChars = charCap,
                    // 摘要也要跟着缩：小窗口下 800 字符的摘要是窗口的 20%，留着等于没压
                    digestMaxChars = (charCap / 4).coerceIn(MIN_DIGEST_CHARS, DEFAULT_DIGEST_MAX_CHARS),
                )
            }
            if (charCap == DEFAULT_MAX_CHARS) return DEFAULT
            return DEFAULT.copy(
                maxChars = charCap,
                maxMessages = (charCap / CHARS_PER_MESSAGE).coerceIn(DEFAULT_MAX_MESSAGES, MAX_MESSAGES_CEIL),
                digestMaxChars = (charCap / DIGEST_DIVISOR).coerceIn(DEFAULT_DIGEST_MAX_CHARS, MAX_DIGEST_CEIL),
            )
        }
    }

    /** 是否由窗口推导**收紧**过（`false` = 就是默认值或更宽） */
    val tightenedFromDefault: Boolean
        get() = maxChars < DEFAULT_MAX_CHARS

    /** 是否由窗口推导**放宽**过（大窗口） */
    val widenedFromDefault: Boolean
        get() = maxChars > DEFAULT_MAX_CHARS

    /**
     * 预算是否被窗口改过（无论方向）。
     *
     * UI 用它决定要不要解释"上限怎么不是 6000 了" —— 只有一个方向的解释会让用户
     * 在另一种情况下找不到原因（放宽时说"被收紧了"是错的）。
     */
    val windowAdjusted: Boolean
        get() = maxChars != DEFAULT_MAX_CHARS
}
