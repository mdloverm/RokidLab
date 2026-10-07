package com.rokidlab.phone.ai.compaction

import com.rokidlab.phone.ai.TokenUsage

/**
 * **单轮工具循环的 token 预算**（2026-10-06）。
 *
 * ## 为什么要有它：轮次预算管不住"单轮成本"
 *
 * 现有预算是**轮次**（只读 10 / 动作 12 / 总 22）。轮次适合表达"能做多少步"，
 * 但对"要花多少钱"完全无能为力 ——
 * 一轮里可以有 5 个并行工具 + 8K 输出 + 几万 token 的系统提示词，
 * 也可以只有一次轻量查询。**22 轮的成本浮动能差一个数量级**。
 *
 * 后果在真机上表现为两种很不一样的故障：
 *  - **本地小窗口模型**（4k）：第 3 轮就撑爆，`ContextOverflow` 兜一次重建，仍失败；
 *  - **在线大窗口模型**：一路绿灯跑完 22 轮，用户拿到账单才发现钱花了。
 *
 * Codex / claude-code 用的都是 **token 预算 + 窗口占用率**，本文件是把这件事补齐。
 *
 * ## 为什么是「双轨」而不是替换轮次
 * 轮次预算**不能删** —— 它表达的是"多步任务要能做完"这件事，
 * 而 token 预算表达的是"别烧钱/别撑爆"。两者的触发条件不同：
 *  - 一个 30 轮但每轮都很轻的任务，token 预算不会拦，轮次预算也不会拦（30 > 22 会拦）；
 *  - 一个 3 轮但每轮都往死里写的任务，token 预算会拦，轮次预算不会。
 * 所以两个都在，任一触顶都进收尾（[exhausted]）。
 *
 * ## 记账口径：服务端 usage 优先，本地估算兜底
 * `ChatTurn.usage` 是服务端的真实数字，但**并非所有端点都给**
 * （部分实现忽略 `stream_options.include_usage`，见 [TokenUsage] KDoc）。
 * 所以每轮取 `usage?.totalTokens`，拿不到就用 [TokenMeter] 估算本轮新增内容。
 *
 * ⚠️ **两种口径不能混着加**：一轮里如果 prompt 用量算了几次、输出又算了几次，
 * 累计会虚高到提前触顶。这里严格取"每轮一个数"，并在 [charged] 里记来源，
 * 便于排查时判断数字可信度。
 */
internal class TokenBudget(
    /** 本次回答的 token 总预算（输入+输出合计） */
    private val totalLimit: Int,
    /**
     * 单轮 token 上限。
     *
     * 存在的理由：总预算管的是"累计"，但**一轮**也能失控（一次超长输出 + 5 个并行工具）。
     * 单轮触顶比总预算触顶更早介入，能在烧掉总预算之前先收一次。
     */
    private val perRoundLimit: Int,
) {
    /** 本次回答已计费 token 合计 */
    var total: Int = 0
        private set

    /** 单轮最高计费（诊断用：看是哪一轮突然烧起来的） */
    var peakRound: Int = 0
        private set

    /** 是否有任何一轮真的拿到服务端 usage（false = 全是本地估算，数字只能当量级看） */
    var sawServerUsage: Boolean = false
        private set

    /**
     * 记一轮的消耗。
     *
     * @param usage 服务端真实用量；null = 端点没给
     * @param estimatedFallback 本地估算值（usage 为 null 时才用）
     * @return 本轮实际计入的 token
     */
    fun chargeRound(usage: TokenUsage?, estimatedFallback: Int): Int {
        val charged = when {
            usage != null -> {
                sawServerUsage = true
                usage.totalTokens
            }
            estimatedFallback > 0 -> estimatedFallback
            else -> 0
        }
        total += charged
        if (charged > peakRound) peakRound = charged
        return charged
    }

    /** 总预算是否已耗尽 */
    fun totalExhausted(): Boolean = totalLimit > 0 && total >= totalLimit

    /**
     * 本轮之后是否应当收尾。
     *
     * @param justCharged 本轮刚计入的 token
     * @return true = 该进收尾阶段了
     */
    fun shouldStop(justCharged: Int): Boolean {
        if (totalLimit > 0 && total >= totalLimit) return true
        if (perRoundLimit > 0 && justCharged >= perRoundLimit) return true
        return false
    }

    /** 诊断文本（进 logcat，不进提示词） */
    fun summary(): String =
        "token=$total/$totalLimit" +
            (if (peakRound > 0) ", peakRound=$peakRound" else "") +
            (if (sawServerUsage) ", server=real" else ", server=estimated")

    // ── 测试可见的只读访问器 ──────────────────────────────────────
    // 存在理由同 AiToolParamRepair：预算是**决策逻辑**（何时停），
    // 不锁住"按窗口怎么推导"就等于让下一个人随手改推导口径而无人察觉。
    internal fun totalLimitForTest(): Int = totalLimit

    internal fun perRoundLimitForTest(): Int = perRoundLimit

    companion object {
        /**
         * 按模型窗口推导默认预算。
         *
         * ## 总预算 = 窗口 × 4
         * 工具循环里 prompt 每轮都要重发全部历史，所以"一轮的成本"本身随轮数增长
         * （20 轮的累计 prompt 可能是窗口的十几倍）。按 1 个窗口算会**第 2 轮就触顶**，
         * 等于把工具循环关掉。
         *
         * ## 单轮上限 = max(窗口 / 2, 4000)，**不是** 窗口 / 4
         * ⚠️ 这里踩过一次：初版取 `窗口/4`，在 8k 窗口下算出 2k/轮，而
         * **系统提示词 + 105 个工具的 schema 本身就要 8~10k token** ——
         * 也就是"第一轮的输入"就超过 2k，于是每轮都触发单轮触顶，
         * 多步任务被砍到只剩 1 轮。
         *
         * 取窗口/2 的理由：单轮里真正需要管的是**输出**（模型自己写多少），
         * 而输入部分由 `CompactionPolicy` 按窗口 15% 独立管着（历史压缩），
         * 两套机制各管一段。4000 的下限保证小窗口模型也允许一次正常规模的输出。
         *
         * ## 窗口未知（0）时返回 0 = 不限
         * 本项目大量用户用国产端点（不报 contextWindow），拿"猜一个数"当上限
         * 会让预算变成**随机截断** —— 用户看到的现象是"有时候能做有时候不能"，
         * 比多花点钱糟糕得多。
         */
        fun forWindow(contextWindow: Int): TokenBudget {
            if (contextWindow <= 0) return TokenBudget(0, 0)
            val total = (contextWindow.toLong() * 4).coerceAtMost(MAX_TOTAL).toInt()
            val perRound = (contextWindow / 2).coerceAtLeast(MIN_PER_ROUND)
            return TokenBudget(total, perRound)
        }

        /** 天花板：再大的窗口也不该允许单次回答无限烧 token */
        private const val MAX_TOTAL = 400_000L

        /** 单轮下限：窗口再小也要允许一次正常规模的输出（且要大于系统提示词量级） */
        private const val MIN_PER_ROUND = 4_000
    }
}
