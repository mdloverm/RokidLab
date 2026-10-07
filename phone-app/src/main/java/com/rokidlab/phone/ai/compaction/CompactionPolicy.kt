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
 * ★ 2026-10-02 预算单位从「字符」换成「token」（[TokenMeter]，cl100k_base）：
 *   字符是跟模型消费脱节的单位 —— 英文约 4 字符/token、中文约 1~1.5 字符/token，
 *   同一个 6000 字符的预算在两种语言下差 4 倍，而"字符/token 经验换算系数"
 *   （原 `CHARS_PER_TOKEN=1.6`）只是给这个错位打补丁。历史预算直接用 token 表达后：
 *  - 中英文同权，不再因对话语言不同而实际预算漂移；
 *  - `forWindow` 的推导从「窗口 → 估算字符 → 反推 token」变成一步直达（份额就是份额）；
 *  - 压缩触发条件与溢出报错（服务端报的都是 token）终于说同一种语言。
 *   摘要预算（[digestMaxChars]）**保留字符单位**：摘要是给人读的要点行，
 *   "截断 60 字"这类提取规则本来就是字符口径，没必要跟着换。
 *
 * ⚠️ **默认值保持同一历史规模**（[DEFAULT_MAX_TOKENS]=4000 ≈ 原 6000 字 × 0.65 token/字，
 * 与 DeepSeek 系中文实测粒度一致）。窗口推导走 [forWindow]，规则是**双向**的
 * （2026-09-19 修订，原来只有"只收紧不放宽"）：
 *  - 小窗口 → 收紧（修的是溢出 bug，改造前就在做）；
 *  - 大窗口 → **放宽**（原来刻意不做）。真机上这条限制的代价很直观：deepseek 131072
 *    窗口、系统+工具每轮固定吃掉约 1 万 token，而历史只给 6000 字符（≈6 轮）——
 *    模型明明装得下，却每聊几轮就开始"忘了"前面。
 *    ⇒ 现在按同一个份额推导放宽，并给绝对天花板（[MAX_TOKENS_CEIL]）防止窗口很大时单请求失控。
 *
 * ⚠️ 2026-09-20 **移除 `expireMs`**：原设计是"10 分钟无活动视为新会话，自动清空上下文"。
 *    多会话落地后这条与用户心智正面相抵 —— 满屏对话还在、AI 却不认了，而且是**静默**发生的
 *    （界面没有任何提示）。记忆现在随对话持久保存，清空只由用户显式操作触发
 *    （聊天页「清空对话」/ 设置页「清空全部会话记忆」）。
 *
 * @param maxMessages 历史消息条数上限（user+assistant 合计）
 * @param maxTokens 历史总 token 数上限（真实计量见 [TokenMeter]）
 * @param digestMaxChars 滚动摘要自身的字符上限（防止摘要随对话无限膨胀；摘要保留字符口径）
 * @param keepRecentTurns [CompactionEngine.compactNow] 强制压缩时保留的最近轮数
 */
internal data class CompactionPolicy(
    val maxMessages: Int = DEFAULT_MAX_MESSAGES,
    val maxTokens: Int = DEFAULT_MAX_TOKENS,
    val digestMaxChars: Int = DEFAULT_DIGEST_MAX_CHARS,
    val keepRecentTurns: Int = DEFAULT_KEEP_RECENT_TURNS,
) {
    companion object {
        /**
         * 默认历史消息**条数**上限。
         *
         * ⚠️ 2026-10-06 从 12 提到 25：与 [DEFAULT_MAX_TOKENS] 4000→8000 配套。
         *
         * 原来 12 是"4000 token ÷ 320 token/条 ≈ 12 条"这个换算的产物，
         * 改成 8000 后若不同步抬条数，**窗口未知档会退化成"token 翻倍、条数不变"** ——
         * 症状是历史条数被 12 卡死，用户仍然觉得"几句话就满"（真因是条数不是 token）。
         * 25 条 ≈ 12 轮，与 8000 token / 320 ≈ 25 条正好自洽。
         */
        const val DEFAULT_MAX_MESSAGES = 25

        /**
         * 默认历史 token 预算。
         *
         * ★ 2026-10-06 从 **4000 提到 8000**：原值是"与改造前硬编码同一历史规模"的锚点
         *   （12 条 / 4000 token ≈ 6 轮），当时的问题是**太紧** —— 用户反馈"随便几句就满"。
         *   真正的量级对照（本项目实测口径）：
         *   - 每轮**固定**开销 ≈ 30k token（105 个工具 schema 本身约 27k + 人设/记忆/知识库约 3k）；
         *   - 给历史 4000 意味着在 32k 窗口下历史只占 12%，而固定开销已占 92% ⇒
         *     历史**永远是最先被挤掉的那一块**，表现正是"几句话就忘"。
         *   提到 8000 让"窗口未知"这个最大用户群（国产端点不报 `contextWindow`）
         *   从约 6 轮提升到约 12 轮，且仍远低于固定开销，不会造成溢出。
         */
        const val DEFAULT_MAX_TOKENS = 8000

        const val DEFAULT_DIGEST_MAX_CHARS = 800

        const val DEFAULT_KEEP_RECENT_TURNS = 2

        /**
         * 历史最多占上下文窗口的比例。
         *
         * ⚠️ 2026-10-06 从 **0.15 提到 0.30**。
         *
         * 原值 15% 是按"窗口要留给工具 schema + 记忆注入 + 单轮输出"这个结构估的，
         * 但它假设**工具 schema 没有单独膨胀**。实测本项目每轮固定开销 ≈ 30k token
         *（105 个工具 × 约 260 token，加人设/记忆/知识库注入约 3k），
         * 于是 15% 在中小窗口下**小于固定开销本身**：
         * | 窗口 | 固定开销占比 | 旧份额(15%) | 新份额(30%) |
         * |---|---|---|---|
         * | 8k | 370% | 1228 | 2457 |
         * | 32k | 92% | 4915 | 9830 |
         * | 131k | 23% | 19660 | 38000（触天花板）|
         *
         * 症状就是用户反馈的"随便几句就满" —— 压缩在**工具开销之外**抢不到空间。
         *
         * 取 30% 的理由：剩下 70% 仍显著大于固定开销（30%）+ 单轮输出预算
         * （8k，开思考 32k）。对超大窗口（≥ 256k）由 [MAX_TOKENS_CEIL] 兜住，
         * 不会因为份额变大而让每轮请求无限膨胀。
         */
        private const val HISTORY_SHARE = 0.30

        /**
         * token 上限的下限：比这还小就完全没有上下文可言了，宁可让它溢出也不要退化到失忆
         * （≈ 旧 [原 MIN_CHARS=600 字符] 的 token 口径）。
         */
        private const val MIN_TOKENS = 380

        /**
         * 放宽侧的**绝对天花板**（≈ 5.5 万字中文的历史）。
         *
         * 为什么必须有：份额制在超大窗口（1M）下会推算出 15 万 token，
         * 那意味着每轮请求都要重发同样量级的历史 —— 成本与延迟都不可接受，
         * 而**收益早已递减**（对话历史再长，模型能有效利用的也是最近一段）。
         */
        const val MAX_TOKENS_CEIL = 38000

        /** 条数上限的放宽天花板（≈ 60 轮） */
        const val MAX_MESSAGES_CEIL = 120

        /** 摘要上限的放宽天花板 */
        const val MAX_DIGEST_CEIL = 6000

        /**
         * 平均一条消息的 token 数。用于把 token 额度换算成条数。
         *
         * ⚠️ 2026-10-06 核对：默认档 8000 / 320 = 25，与 [DEFAULT_MAX_MESSAGES] 自洽。
         * 额度放大时条数同步放大，否则"token 数放开了、却仍被条数卡死" ——
         * 那正是 4000→8000 那次改动差点踩的坑（只改 token 忘了改条数）。
         */
        private const val TOKENS_PER_MESSAGE = 320

        /** 摘要额度 = 历史额度的 1/10（默认档下 400，低于 800 的下限，因此默认仍取 800） */
        private const val DIGEST_DIVISOR = 10

        /** 摘要上限的下限 */
        private const val MIN_DIGEST_CHARS = 120

        /** 默认策略（= 与改造前硬编码值同一历史规模，行为不变） */
        val DEFAULT = CompactionPolicy()

        /**
         * 按模型上下文窗口推导策略 —— **双向**（小窗口收紧、大窗口放宽）。
         *
         * 四条规则：
         *  1. 窗口未知（`contextWindow <= 0`，见 `ModelCapabilities`）→ 原样返回 [DEFAULT]。
         *     "不知道"不能变成"给你调小"，那会平白损失用户的上下文。
         *  2. 推导额度 < 默认值 → **收紧** [maxTokens] 与 [digestMaxChars]；[maxMessages] 不动
         *     （这个档位下真正的约束是 token 数：几百 token 早已把 25 条压到更少）。
         *  3. 推导额度 = 默认值 → 原样返回 [DEFAULT]。
         *  4. 推导额度 > 默认值 → **放宽** [maxTokens]、[maxMessages]、[digestMaxChars] 三者，
         *     并各自受天花板约束。三者必须一起放 —— 只放 token 数会被条数卡死（症状与没改一样）。
         *
         * @param contextWindowTokens 模型上下文窗口（token）；0 = 未知
         */
        fun forWindow(contextWindowTokens: Int): CompactionPolicy {
            if (contextWindowTokens <= 0) return DEFAULT
            val tokenCap = (contextWindowTokens * HISTORY_SHARE).toInt()
                .coerceIn(MIN_TOKENS, MAX_TOKENS_CEIL)
            if (tokenCap < DEFAULT_MAX_TOKENS) {
                return DEFAULT.copy(
                    maxTokens = tokenCap,
                    // 摘要也要跟着缩：小窗口下 800 字符的摘要是窗口的 20%，留着等于没压
                    digestMaxChars = (tokenCap / 4).coerceIn(MIN_DIGEST_CHARS, DEFAULT_DIGEST_MAX_CHARS),
                )
            }
            if (tokenCap == DEFAULT_MAX_TOKENS) return DEFAULT
            return DEFAULT.copy(
                maxTokens = tokenCap,
                maxMessages = (tokenCap / TOKENS_PER_MESSAGE).coerceIn(DEFAULT_MAX_MESSAGES, MAX_MESSAGES_CEIL),
                digestMaxChars = (tokenCap / DIGEST_DIVISOR).coerceIn(DEFAULT_DIGEST_MAX_CHARS, MAX_DIGEST_CEIL),
            )
        }
    }

    /** 是否由窗口推导**收紧**过（`false` = 就是默认值或更宽） */
    val tightenedFromDefault: Boolean
        get() = maxTokens < DEFAULT_MAX_TOKENS

    /** 是否由窗口推导**放宽**过（大窗口） */
    val widenedFromDefault: Boolean
        get() = maxTokens > DEFAULT_MAX_TOKENS

    /**
     * 预算是否被窗口改过（无论方向）。
     *
     * UI 用它决定要不要解释"上限怎么不是默认档了" —— 只有一个方向的解释会让用户
     * 在另一种情况下找不到原因（放宽时说"被收紧了"是错的）。
     */
    val windowAdjusted: Boolean
        get() = maxTokens != DEFAULT_MAX_TOKENS
}
