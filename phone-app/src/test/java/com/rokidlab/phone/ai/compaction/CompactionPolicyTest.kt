package com.rokidlab.phone.ai.compaction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CompactionPolicy] 单测。
 *
 * 这里锁的**不是**公式本身（15% 份额、每消息 320 token 都是经验系数，将来可以调），
 * 而是四条不能破的**规则**：
 *  1. [CompactionPolicy.DEFAULT] 与改造前**同一历史规模**（12 条 / 4000 token ≈ 6000 字中文）——
 *     它是"没被窗口调整过"的基准，不能被顺手改掉；
 *  2. 窗口未知（0）时**不许动**："不知道"不能变成"替用户调小"，那会平白损失上下文；
 *  3. 小窗口 → **收紧**（修的是溢出 bug）；
 *  4. 大窗口 → **放宽**，而且 **maxTokens / maxMessages / digest 三条必须一起放**。
 *
 * ★ 规则 4 是 2026-09-19 反转的（原来是"只收紧不放宽"）。真机上那条限制的代价很直观：
 *   deepseek 131072 窗口、系统+工具每轮固定吃掉约 1 万 token，而历史只给 6000 字符（≈6 轮）
 *   —— 模型明明装得下，却聊几轮就开始"忘了"。下面 A3 就是这条规则的锁。
 *
 * ★ 2026-10-02 预算单位从字符换成 token（[TokenMeter]，cl100k_base）：字符对中英文不等权
 *   （英文 4 字符/token、中文约 1 字/token），token 才是模型真正消费的单位。
 *   这些测试是换单位时的锚：同样的窗口推导出的**历史规模**不许因换单位而漂移。
 */
class CompactionPolicyTest {

    @Test
    fun `A1 默认策略与改造前同一历史规模`() {
        val p = CompactionPolicy.DEFAULT
        // 2026-10-06 放宽：4000/12 → 8000/25（约 6 轮 → 约 12 轮）
        assertEquals(25, p.maxMessages)
        assertEquals(8000, p.maxTokens)
        assertEquals(800, p.digestMaxChars)
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
        // 131072 × 0.15 = 19660 token（≈ 3 万字中文的历史）
        // 131072 × 0.30 = 39321 → 被 MAX_TOKENS_CEIL 38000 截断
        assertEquals(38000, p.maxTokens)
        assertTrue("大窗口应放宽，实际 maxTokens=${p.maxTokens}", p.widenedFromDefault)
        assertTrue(
            "条数必须一起放 —— 只放 token 数会被条数卡死，症状与没改一样：maxMessages=${p.maxMessages}",
            p.maxMessages > CompactionPolicy.DEFAULT_MAX_MESSAGES,
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
        // 4096 × 0.15 = 614
        // 4096 × 0.30 = 1228
        assertEquals(1228, p4096.maxTokens)
        assertTrue("4096 窗口必须收紧", p4096.tightenedFromDefault)
        assertTrue("摘要上限也要跟着缩，实际 ${p4096.digestMaxChars}", p4096.digestMaxChars < 400)
        assertTrue(
            "摘要上限不得低于下限 120，实际 ${p4096.digestMaxChars}",
            p4096.digestMaxChars >= 120,
        )

        // 8K 窗口（llama3 / mistral 常见档）同样收紧
        val p8192 = CompactionPolicy.forWindow(8192)
        assertTrue(p8192.tightenedFromDefault)
        // 8192 × 0.30 = 2457 < 默认档 8000 ⇒ 走收紧分支
        assertTrue("8K 窗口收紧后应小于默认档，实际 ${p8192.maxTokens}", p8192.maxTokens < 8000)
        // 收紧档下条数上限不参与（真正的约束是 token 数；25 条在 2457 token 下早被压到更少）
        assertEquals(CompactionPolicy.DEFAULT_MAX_MESSAGES, p8192.maxMessages)
    }

    @Test
    fun `A5 预算随窗口单调不减，且有下限与绝对天花板`() {
        val windows = listOf(1, 100, 1024, 4096, 8192, 16384, 32768, 131072, 1048576)
        val budgets = windows.map { CompactionPolicy.forWindow(it).maxTokens }
        budgets.zipWithNext().forEach { (a, b) ->
            assertTrue("预算必须随窗口单调不减：$budgets", b >= a)
        }
        assertEquals(
            "极端小窗口也不许把预算压到不可用（宁可溢出也不要退化到失忆）",
            380,
            CompactionPolicy.forWindow(1).maxTokens,
        )
        assertEquals(
            "超大窗口（1M）必须停在绝对天花板上，否则每轮请求都要重发十几万 token",
            CompactionPolicy.MAX_TOKENS_CEIL,
            budgets.last(),
        )
        assertEquals(
            "条数同样有天花板；1M 档落在 38000/320=118（未触 120 的天花板也算受控）",
            118,
            CompactionPolicy.forWindow(1048576).maxMessages,
        )
    }

    @Test
    fun `A6 恰好等于默认额度时原样返回默认（不留下"被调整过"的痕迹）`() {
        // raw = window × 0.15，取 4000 需要 window = 26667
        // 8000 / 0.30 = 26666.7 ⇒ 26667 是"推导额度恰好等于默认档"的临界窗口
        assertEquals(CompactionPolicy.DEFAULT, CompactionPolicy.forWindow(26667))
        assertFalse(CompactionPolicy.forWindow(26667).windowAdjusted)
    }

    @Test
    fun `A7 三个方向标记互斥且与 maxTokens 一致`() {
        val tightened = CompactionPolicy.forWindow(4096)
        val same = CompactionPolicy.forWindow(26667)
        val widened = CompactionPolicy.forWindow(131072)
        assertTrue(tightened.tightenedFromDefault && !tightened.widenedFromDefault)
        assertFalse(same.tightenedFromDefault || same.widenedFromDefault)
        assertTrue(widened.widenedFromDefault && !widened.tightenedFromDefault)
        // windowAdjusted 是"改过"，两个方向都为真（UI 的解释文案靠它）
        assertTrue(tightened.windowAdjusted && widened.windowAdjusted)
    }

    /**
     * 🔴 2026-10-06 新增：锁住「token 额度与条数上限必须**自洽**」这条不变式。
     *
     * ## 它防的是哪个真实 bug
     * 这次把 [CompactionPolicy.DEFAULT_MAX_TOKENS] 从 4000 提到 8000 时，
     * **差点只改 token 没改条数** —— 那样窗口未知档会变成
     * 「token 翻倍、条数仍卡在 12」，用户体感完全没改善（"还是几句话就满"）。
     * 症状极具迷惑性：看 token 数字是涨了的，看代码也只改了一行。
     *
     * 判据：默认档必须满足 `maxTokens / maxMessages` 落在 [TOKENS_PER_MESSAGE] 的
     * 合理邻域内 —— 偏离说明两个数**不是一起改的**，条数或 token 有一个在拖后腿。
     */
    @Test
    fun `默认档 - token与条数必须自洽 - 不允许只改一个导致另一个拖后腿`() {
        val p = CompactionPolicy.DEFAULT

        // 25 条 × 320 token/条 = 8000，与 maxTokens 恰好自洽
        assertEquals(
            CompactionPolicy.DEFAULT_MAX_MESSAGES,
            p.maxTokens / 320,
        )
        // 换句话说：条数上限绝不能是"按旧 token 预算算出来"的遗留值
        assertTrue(
            "条数上限 ${p.maxMessages} 与 token 预算 ${p.maxTokens} 不自洽 —— " +
                "很可能只改了其中一个（2026-10-06 差点踩这个坑）",
            p.maxMessages >= 20,
        )
    }

    /**
     * 用户 2026-10-06 反馈「随便几句就满」。这条锁住**放宽后的最低档位**，
     * 避免将来有人为了"省成本"把它调回去而没有测试拦住。
     *
     * 判据取自当次核算：窗口未知（大量国产端点不报 `contextWindow`）这一档
     * 必须至少给到约 12 轮，否则等于没有多轮对话能力。
     */
    @Test
    fun `窗口未知档 - 至少约12轮 - 这是用户实测反馈的底线`() {
        val p = CompactionPolicy.forWindow(0)

        assertTrue(
            "窗口未知档只给 ${p.maxMessages} 条（${p.maxMessages / 2} 轮），" +
                "少于用户反馈的底线（2026-10-06 要求 ≥12 轮）",
            p.maxMessages / 2 >= 12,
        )
    }
}
