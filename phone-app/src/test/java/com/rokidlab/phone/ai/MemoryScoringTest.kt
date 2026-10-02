package com.rokidlab.phone.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.exp
import kotlin.math.ln

/**
 * [MemoryScoring] 单测 —— 锁的是三条**淘汰秩序**，不是公式系数本身：
 *  1. 重要性高的记忆不能被重要性低的"新人"挤掉（旧 FIFO 的核心缺陷）；
 *  2. 新近度衰减必须以「最近一次被用到」为锚（访问保鲜的前提）；
 *  3. 频率信号单调、饱和、有界（常被命中的记忆稳定存活，但不会无限膨胀）。
 */
class MemoryScoringTest {

    private val now = 1_800_000_000_000L
    private val day = 24L * 3600 * 1000

    @Test
    fun `A1 重要的旧记忆得分高于琐碎的新记忆（FIFO 缺陷的回归锁）`() {
        // 80 天前写入的重要事实（importance 10）从未被注入 —— 仍在 90 天过期窗内，
        // 正是它要跟一堆"新人"抢名额的场景
        val oldImportant = MemoryScoring.evictionScore(
            importance = 1.0, accessCount = 0, lastAccessed = null,
            createdAt = now - 80 * day, now = now,
        )
        // 昨天写入的琐碎备注（importance 1）
        val newTrivial = MemoryScoring.evictionScore(
            importance = 0.1, accessCount = 0, lastAccessed = null,
            createdAt = now - day, now = now,
        )
        assertTrue(
            "重要的旧记忆($oldImportant) 必须高于琐碎的新记忆($newTrivial)，否则 FIFO 缺陷换汤不换药",
            oldImportant > newTrivial,
        )
    }

    @Test
    fun `A2 新近度以最近一次被用到为锚，而非写入时间`() {
        val importance = 0.5
        // 同一条记忆：写于 100 天前，昨天刚被注入命中
        val touched = MemoryScoring.evictionScore(
            importance = importance, accessCount = 5, lastAccessed = now - day,
            createdAt = now - 100 * day, now = now,
        )
        // 对照组：同样内容但从未被注入（锚点回落到 created_at）
        val untouched = MemoryScoring.evictionScore(
            importance = importance, accessCount = 0, lastAccessed = null,
            createdAt = now - 100 * day, now = now,
        )
        assertTrue(
            "被访问过的必须比没被访问过的得分高：$touched vs $untouched",
            touched > untouched,
        )
        // 30 天（τ）前的锚点应恰好衰减到 1/e
        val recencyOnly = MemoryScoring.evictionScore(
            importance = 0.0, accessCount = 0, lastAccessed = now - 30 * day,
            createdAt = now - 30 * day, now = now,
        )
        assertEquals(exp(-1.0), recencyOnly, 1e-9)
    }

    @Test
    fun `A3 频率信号单调上升且边际递减（等距步长）`() {
        fun scoreOf(n: Int) = MemoryScoring.evictionScore(
            importance = 0.5, accessCount = n, lastAccessed = now,
            createdAt = now, now = now,
        )
        val s0 = scoreOf(0)
        val s10 = scoreOf(10)
        val s20 = scoreOf(20)
        val s30 = scoreOf(30)
        assertTrue(s10 > s0)
        assertTrue(s20 > s10)
        assertTrue(s30 > s20)
        // 对数边际递减必须用**等距**步长验证：等比步长（×6）下 ln 的增益几乎不变
        val gain1 = s10 - s0
        val gain2 = s20 - s10
        val gain3 = s30 - s20
        assertTrue("第一段增益应最大", gain1 > gain2)
        assertTrue("增益必须递减：$gain2 -> $gain3", gain2 > gain3)
        // 30 次 ≈ 频率分量满分（W_FREQUENCY）
        assertEquals(MemoryScoring.W_FREQUENCY, s30 - s0, 1e-9)
    }

    @Test
    fun `A4 无访问时锚点回落到写入时间，且非法输入不产生负天数`() {
        val baseline = MemoryScoring.evictionScore(
            importance = 0.5, accessCount = 0, lastAccessed = null,
            createdAt = now, now = now,
        )
        // now < createdAt（时钟回拨）不得让分数超过满分基线
        val clockSkew = MemoryScoring.evictionScore(
            importance = 0.5, accessCount = 0, lastAccessed = null,
            createdAt = now + day, now = now,
        )
        assertEquals("时钟回拨应被钳到 0 天", baseline, clockSkew, 1e-9)
        // 负 accessCount 视同 0
        assertEquals(
            baseline,
            MemoryScoring.evictionScore(0.5, -3, null, now, now),
            1e-9,
        )
    }

    @Test
    fun `A5 三个信号联合决定排序（mem0 场景端到端）`() {
        // 场景：200 条记忆塞满后，谁该被淘汰？
        // 最该留：用户身份（重要 10、天天命中）
        val identity = MemoryScoring.evictionScore(1.0, 30, now - day, now - 200 * day, now)
        // 该走：AI 顺手记的低价值备注（重要 2、从未命中、写了很久）
        val noise = MemoryScoring.evictionScore(0.2, 0, null, now - 150 * day, now)
        // 中间：一般重要的偏好，偶尔命中
        val mid = MemoryScoring.evictionScore(0.5, 3, now - 10 * day, now - 60 * day, now)
        assertTrue(identity > mid)
        assertTrue(mid > noise)
        assertTrue(identity > 1.0 + MemoryScoring.W_RECENCY)
    }
}
