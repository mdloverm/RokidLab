package com.rokidlab.phone.ai.compaction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CompactionPolicy] 单测。
 *
 * 这里锁的**不是**公式本身（1.6 字符/token、15% 份额都是经验系数，将来可以调），
 * 而是四条不能破的**规则**：
 *  1. [CompactionPolicy.DEFAULT] 就是改造前的硬编码值（12 / 6000 / 800 / 10 分钟）——
 *     它是"没被窗口调整过"的基准，不能被顺手改掉；
 *  2. 窗口未知（0）时**不许动**："不知道"不能变成"替用户调小"，那会平白损失上下文；
 *  3. 小窗口 → **收紧**（修的是溢出 bug）；
 *  4. 大窗口 → **放宽**，而且 **chars / maxMessages / digest 三条必须一起放**。
 *
 * ★ 规则 4 是 2026-09-19 反转的（原来是"只收紧不放宽"，理由是"放宽是产品决策，晚点再说"）。
 *   真机上那条限制的代价很直观：deepseek 131072 窗口、系统+工具每轮固定吃掉约 1 万 token，
 *   而历史只给 6000 字符（≈6 轮）—— 模型明明装得下，却聊几轮就开始"忘了"。
 *   下面 A3 就是这条规则的锁：**只放字符数不算放**（12 条会把 3 万字符的额度卡成 6 轮）。
 */
class CompactionPolicyTest {

    @Test
    fun `A1 默认策略与改造前的硬编码值逐项一致`() {
        val p = CompactionPolicy.DEFAULT
        assertEquals(12, p.maxMessages)
        assertEquals(6000, p.maxChars)
        assertEquals(800, p.digestMaxChars)
        assertEquals(10 * 60 * 1000L, p.expireMs)
        assertEquals(2, p.keepRecentTurns)
        assertFalse("默认策略不算被窗口调整过", p.windowAdjusted)
        assertFalse(p.tightenedFromDefault)
        assertFalse(p.widenedFromDefault)
    }

    @Test
    fun `A2 窗口未知时不动预算`() {
        listOf(0, -1, -1000).forEach { window ->
            val p = CompactionPolicy.forWindow(window)
            assertEquals(
                "窗口=$window 时必须原样返回 DEFAULT（不能拿「不知道」去惩罚用户）",
                CompactionPolicy.DEFAULT,
                p,
            )
        }
    }

    @Test
    fun `A3 大窗口按份额放宽，且三条一起放`() {
        val p = CompactionPolicy.forWindow(131072)
        assertTrue("大窗口应放宽，实际 maxChars=${p.maxChars}", p.widenedFromDefault)
        assertTrue("放宽后应有明显更多历史，实际 ${p.maxChars}", p.maxChars > 20000)
        assertTrue(
            "条数必须一起放 —— 只放字符数会被 12 条卡死，症状与没改一样：maxMessages=${p.maxMessages}",
            p.maxMessages > 12,
        )
        assertTrue("摘要上限也要跟着涨，否则大窗口下摘要是脱节的：${p.digestMaxChars}", p.digestMaxChars > 800)
        assertFalse("放宽不是收紧", p.tightenedFromDefault)
        assertTrue(p.windowAdjusted)
    }

    @Test
    fun `A4 小窗口模型收紧预算并同步缩小摘要上限`() {
        // 本地 Ollama 默认 num_ctx=4096：改造前照样塞 6000 字符历史 + 摘要，
        // 整轮请求必然超出窗口被服务端拒绝 —— 这条就是那个 bug 的回归锁
        val p4096 = CompactionPolicy.forWindow(4096)
        assertTrue("4096 窗口必须收紧", p4096.tightenedFromDefault)
        assertTrue("收紧后应远小于 6000，实际 ${p4096.maxChars}", p4096.maxChars < 1500)
        assertTrue("摘要上限也要跟着缩，实际 ${p4096.digestMaxChars}", p4096.digestMaxChars < 400)
        assertTrue(
            "摘要上限不得低于下限 120，实际 ${p4096.digestMaxChars}",
            p4096.digestMaxChars >= 120,
        )

        // 8K 窗口（llama3 / mistral 常见档）同样收紧
        val p8192 = CompactionPolicy.forWindow(8192)
        assertTrue(p8192.tightenedFromDefault)
        assertTrue("8K 窗口收紧后应小于 6000，实际 ${p8192.maxChars}", p8192.maxChars < 6000)
        // 收紧档下条数上限不参与（真正的约束是字符数；12 条在 2000 字符下早被压到更少）
        assertEquals(12, p8192.maxMessages)
    }

    @Test
    fun `A5 预算随窗口单调不减，且有下限与绝对天花板`() {
        val windows = listOf(1, 100, 1024, 4096, 8192, 16384, 32768, 131072, 1048576)
        val budgets = windows.map { CompactionPolicy.forWindow(it).maxChars }
        budgets.zipWithNext().forEach { (a, b) ->
            assertTrue("预算必须随窗口单调不减：$budgets", b >= a)
        }
        assertEquals(
            "极端小窗口也不许把预算压到不可用（宁可溢出也不要退化到失忆）",
            600,
            CompactionPolicy.forWindow(1).maxChars,
        )
        assertEquals(
            "超大窗口（1M）必须停在绝对天花板上，否则每轮请求都要重发十几万 token",
            CompactionPolicy.MAX_CHARS_CEIL,
            budgets.last(),
        )
        assertEquals(
            "条数同样有天花板（≈60 轮）",
            CompactionPolicy.MAX_MESSAGES_CEIL,
            CompactionPolicy.forWindow(1048576).maxMessages,
        )
    }

    @Test
    fun `A6 恰好等于默认额度时原样返回默认（不留下"被调整过"的痕迹）`() {
        // raw = window × 1.6 × 0.15，取 6000 需要 window = 25000
        assertEquals(CompactionPolicy.DEFAULT, CompactionPolicy.forWindow(25000))
        assertFalse(CompactionPolicy.forWindow(25000).windowAdjusted)
    }

    @Test
    fun `A7 三个方向标记互斥且与 maxChars 一致`() {
        val tightened = CompactionPolicy.forWindow(4096)
        val same = CompactionPolicy.forWindow(25000)
        val widened = CompactionPolicy.forWindow(131072)
        assertTrue(tightened.tightenedFromDefault && !tightened.widenedFromDefault)
        assertFalse(same.tightenedFromDefault || same.widenedFromDefault)
        assertTrue(widened.widenedFromDefault && !widened.tightenedFromDefault)
        // windowAdjusted 是"改过"，两个方向都为真（UI 的解释文案靠它）
        assertTrue(tightened.windowAdjusted && widened.windowAdjusted)
    }
}
