package com.rokidlab.phone.ai

import kotlin.math.exp
import kotlin.math.ln

/**
 * 长期记忆的**价值评分**（mem0 风格，2026-10-02 起替代「FIFO + 一刀切过期」）。
 *
 * 旧淘汰的缺陷：FIFO 只看写入顺序 —— 一条 AI 自主记下的低价值备注和用户亲口说的
 * 身份信息（「叫我小乐」）地位相同，谁先写谁先死；一刀切 90 天过期更是把
 * 「长期没用但每季度必用」的偏好（春节回家路线）也清掉。
 *
 * mem0 的做法是给每条记忆算一个**综合价值分**，淘汰分最低的：
 *
 * ```
 * score = W_imp × importance          // 写入时定（AI 按 1-10 评级），身份/长期偏好最高
 *       + W_rec × exp(-Δt / τ)        // 新近度：以「最近一次被用到」为锚的指数衰减
 *       + W_freq × ln(1+n) / ln(31)   // 频率：被检索注入的次数，对数尺度（边际递减）
 * ```
 *
 * 三个信号各管一件事：**重要性**管"值得不值得记"，**新近度**管"最近还有没有用"，
 * **频率**管"是不是一直在用"。单看任何一个都会误杀 —— 重要但久未用 vs 琐碎但天天命中，
 * 只有合起来才接近"这条记忆还值不值得占名额"。
 *
 * ⚠️ 纯 JVM 函数、零 Android 依赖：淘汰排序的正确性锁在 [MemoryScoringTest] 里。
 */
internal object MemoryScoring {

    /**
     * 新近度衰减时间常数（天）：Δt = τ 时衰减到 1/e ≈ 37%。
     * 取 30 天 —— 与 90 天过期档对齐（3τ ≈ 5% 的分数残留，接近「该被淘汰了」），
     * 比线性衰减平滑：两周没用过的记忆不该比昨天用的跌一半。
     */
    const val DECAY_TAU_DAYS = 30.0

    /** 频率的归一化分母：ln(1+30)，即 30 次注入 ≈ 频率满分（继续涨只有边际收益） */
    private val LN_FREQ_NORM = ln(31.0)

    /** 重要性权重。必须**压舱**：重要度全跨度（0.1~1.0 → 1.8 分）要盖过新近度全跨度（0~1 分），
     *  否则「刚写入的琐碎备注」总能压过「重要但几天没命中」的记忆 —— FIFO 缺陷换个方向复发 */
    const val W_IMPORTANCE = 2.0

    /** 新近度权重：次级信号 —— 同等重要度时，最近被用到的排前 */
    const val W_RECENCY = 1.0

    /**
     * 频率权重（半权）：access_count 与重要性高度相关（重要的记忆自然常被检索命中），
     * 满权会让"常被命中"一条信号算两遍。
     */
    const val W_FREQUENCY = 0.5

    /**
     * 单条记忆的淘汰分（越大越该留）。
     *
     * @param importance 归一化重要度 0.0~1.0（写入时的 1-10 评级 ÷10）
     * @param accessCount 被检索注入的累计次数（≥0）
     * @param lastAccessed 最近一次被注入的时间戳；null = 从未被注入（锚点回落到写入时间）
     * @param createdAt 写入时间戳（add/update 都会刷新）
     * @param now 当前时间（参数化以便单测）
     */
    fun evictionScore(
        importance: Double,
        accessCount: Int,
        lastAccessed: Long?,
        createdAt: Long,
        now: Long,
    ): Double {
        val anchor = lastAccessed ?: createdAt
        val days = ((now - anchor).coerceAtLeast(0L)) / 86_400_000.0
        val recency = exp(-days / DECAY_TAU_DAYS)
        val frequency = ln(1.0 + accessCount.coerceAtLeast(0)) / LN_FREQ_NORM
        return W_IMPORTANCE * importance + W_RECENCY * recency + W_FREQUENCY * frequency
    }
}
