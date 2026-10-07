package com.rokidlab.phone.ai.compaction

import com.rokidlab.phone.ai.TokenUsage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TokenBudget] 回归测试 —— 轮次预算之外的**第二道闸门**。
 *
 * ## 锁的是哪个缺口
 * 原来只有轮次预算（只读 10 / 动作 12 / 总 22）。轮次表达"能做多少步"，
 * 但管不住"要花多少钱"：一轮里可以有 5 个并行工具 + 8K 输出，也可以只有一次轻量查询，
 * **22 轮的成本浮动能差一个数量级**。
 *
 * ## 两条不能被"优化"掉的规则
 * 1. **窗口未知时不限**（`forWindow(0)` → 0/0）。本项目大量用户用国产端点不报窗口，
 *    拿猜的数当上限会让预算变成**随机截断** —— 用户看到的现象是"有时候能做有时候不能"，
 *    比多花点钱糟糕得多。
 * 2. **两种口径不混加**。一轮里若 prompt 计一次、输出又计一次，累计会虚高到第 2 轮误触顶。
 *    [TokenBudget.chargeRound] 一轮只取一个数。
 */
class TokenBudgetTest {

    // ── 推导 ────────────────────────────────────────────────────

    @Test
    fun `窗口未知 - 不限 - 绝不猜一个上限`() {
        val b = TokenBudget.forWindow(0)

        assertEquals(0, b.totalLimitForTest())
        assertEquals(0, b.perRoundLimitForTest())
        // 不限 ⇒ 记多少都不停
        repeat(50) { b.chargeRound(TokenUsage(100_000, 100_000), 0) }
        assertFalse(b.shouldStop(100_000))
    }

    @Test
    fun `窗口已知 - 总预算取窗口4倍 - 单轮取窗口二分之一`() {
        val b = TokenBudget.forWindow(8_000)

        assertEquals(32_000, b.totalLimitForTest())
        assertEquals(4_000, b.perRoundLimitForTest())
    }

    @Test
    fun `小窗口 - 单轮上限必须大于系统提示词量级 - 否则第一轮就超`() {
        // 🔴 这条是真实的踩坑：初版单轮上限取「窗口/4」，8k 窗口算出 2k/轮，
        // 而**系统提示词 + 105 个工具的 schema 本身就 8~10k token**
        // ⇒ 每轮都触发单轮触顶，多步任务被砍到只剩 1 轮。
        // 判据：单轮上限不得低于 4000（与 MIN_PER_ROUND 一致）。
        listOf(4_096, 8_192, 16_384).forEach { w ->
            val b = TokenBudget.forWindow(w)
            assertTrue(
                "窗口 $w 的单轮上限 ${b.perRoundLimitForTest()} 低于 4000，会被系统提示词本身撑爆",
                b.perRoundLimitForTest() >= 4_000,
            )
        }
    }

    @Test
    fun `总预算取窗口4倍 - prompt每轮重发历史 - 按1个窗口算会第2轮就触顶`() {
        // 20 轮的工具循环，累计 prompt 可能是窗口的十几倍。
        // 若总预算 = 1 个窗口，第 2 轮就 break —— 等于把工具循环关掉。
        val b = TokenBudget.forWindow(8_000)
        var round = 0
        var stopped = false
        // 每轮 1.5k：贴近**估算口径**下的真实量（输出 + 工具结果）。
        // ⚠️ 别用 4k 那种"含全部 prompt"的数 —— 那是 usage 口径，而主循环的
        // 估算口径**不重复计 prompt**（历史每轮重发，计全额会虚高到第 2 轮误触顶）。
        // ⚠️ 判断顺序照主循环：**先记账，再问是否该停**。
        while (round < 20 && !stopped) {
            val charged = b.chargeRound(null, 1_500)
            stopped = b.shouldStop(charged)
            round++
        }
        assertTrue("4 倍窗口（32k）按 1.5k/轮应能撑满 20 轮，实际 $round 轮", round >= 20)
    }

    @Test
    fun `超大窗口 - 总预算封顶 - 不因为窗口大就无限放行`() {
        val b = TokenBudget.forWindow(2_000_000)

        assertTrue("再大的窗口也该有天花板，实际 ${b.totalLimitForTest()}", b.totalLimitForTest() <= 400_000)
    }

    @Test
    fun `极小窗口 - 单轮下限兜底 - 不允许小到一次正常输出都放不下`() {
        val b = TokenBudget.forWindow(500)

        assertTrue("单轮下限应兜住 500 token 的窗口，实际 ${b.perRoundLimitForTest()}",
            b.perRoundLimitForTest() >= 4_000)
    }

    // ── 记账口径 ────────────────────────────────────────────────

    @Test
    fun `服务端usage - 优先采用 - 并标记数字可信`() {
        val b = TokenBudget.forWindow(8_000)

        val charged = b.chargeRound(TokenUsage(promptTokens = 3_000, completionTokens = 1_000), 999)

        assertEquals(4_000, charged)
        assertEquals(4_000, b.total)
        assertTrue("拿到过服务端 usage，数字应当被标记为可信", b.sawServerUsage)
    }

    @Test
    fun `端点不给usage - 用本地估算兜底 - 且不标记为可信`() {
        val b = TokenBudget.forWindow(8_000)

        val charged = b.chargeRound(null, 1_500)

        assertEquals(1_500, charged)
        assertFalse("估算值不能冒充真实用量", b.sawServerUsage)
    }

    @Test
    fun `两种口径不混加 - 一轮只取一个数`() {
        // ⚠️ 这是最容易被"优化"坏的不变式：若 prompt/输出/工具结果各计一次，
        // 累计会翻两三倍，第 2 轮就误触顶。
        val b = TokenBudget.forWindow(8_000)

        b.chargeRound(TokenUsage(10_000, 10_000), 5_000)   // usage 存在 ⇒ 估算值必须被忽略

        assertEquals(20_000, b.total)
    }

    @Test
    fun `两者都拿不到 - 记0而不是瞎猜`() {
        val b = TokenBudget.forWindow(8_000)

        assertEquals(0, b.chargeRound(null, 0))
        assertEquals(0, b.total)
    }

    @Test
    fun `peakRound - 记录单轮最高消耗 - 用于定位哪一轮突然烧起来`() {
        val b = TokenBudget.forWindow(64_000)

        b.chargeRound(TokenUsage(1_000, 500), 0)
        b.chargeRound(TokenUsage(9_000, 3_000), 0)
        b.chargeRound(TokenUsage(2_000, 800), 0)

        assertEquals(12_000, b.peakRound)
    }

    // ── 触顶判定 ────────────────────────────────────────────────

    @Test
    fun `总预算耗尽 - 停`() {
        val b = TokenBudget.forWindow(8_000)   // 总 32000

        var n = 0
        repeat(7) { b.chargeRound(TokenUsage(5_000, 0), 0); n++ }   // 7*5000 = 35000
        assertTrue("累计超总预算应停", b.shouldStop(0))
        assertEquals(7, n)
    }

    @Test
    fun `单轮超限 - 立刻停 - 不等总预算耗尽`() {
        val b = TokenBudget.forWindow(64_000)   // 单轮 32000

        b.chargeRound(TokenUsage(1_000, 0), 0)

        assertTrue("单轮 40k 已超 32k 上限，应立刻停", b.shouldStop(40_000))
        assertFalse("总预算还很空，不该是总预算触顶", b.totalExhausted())
    }

    @Test
    fun `未触顶 - 继续`() {
        val b = TokenBudget.forWindow(64_000)

        b.chargeRound(TokenUsage(2_000, 1_000), 0)

        assertFalse(b.shouldStop(3_000))
        assertFalse(b.totalExhausted())
    }

    @Test
    fun `summary - 区分真实用量与估算值 - 便于排查时判断数字可信度`() {
        val real = TokenBudget.forWindow(8_000).apply { chargeRound(TokenUsage(1_000, 500), 0) }
        val est = TokenBudget.forWindow(8_000).apply { chargeRound(null, 1_500) }

        assertTrue("真实口径应标 server=real：${real.summary()}", real.summary().contains("server=real"))
        assertTrue("估算口径应标 server=estimated：${est.summary()}", est.summary().contains("server=estimated"))
    }
}
