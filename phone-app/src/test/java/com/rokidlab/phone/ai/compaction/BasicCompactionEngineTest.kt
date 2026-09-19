package com.rokidlab.phone.ai.compaction

import com.rokidlab.phone.ai.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [BasicCompactionEngine] 单测。
 *
 * 除了锁住"滚动摘要"的既有语义（与 [com.rokidlab.phone.ai.AgentSessionHistoryTest] 同源），
 * 这里补的是**接缝化新增的三件事**：
 *  1. [BasicCompactionEngine.compactNow] 的强制收敛（手动 / 溢出恢复入口）；
 *  2. [BasicCompactionEngine.isTurnBalanced] —— 压缩不许把一轮拆成半轮
 *     （出现两条连续 user 消息，部分服务端直接 400）；这是 DSH 的
 *     `toolPairingBalancedBefore/After` 在"持久历史没有 tool 消息"前提下的等价物；
 *  3. [CompactionResult] 的量化口径（压了几轮 / 释放多少字符），
 *     让"它怎么忘了"变成可归因的事实，而不是一行日志。
 */
class BasicCompactionEngineTest {

    private val policy = CompactionPolicy.DEFAULT
    private fun engine(p: CompactionPolicy = policy) = BasicCompactionEngine(p)

    /** 模拟 N 轮 user/assistant 历史（与 recordTurn 的记录形态一致） */
    private fun historyOf(turns: Int, answer: String = "答案"): MutableList<ChatMessage> {
        val h = mutableListOf<ChatMessage>()
        repeat(turns) { i ->
            h.add(ChatMessage(role = "user", content = "问题$i"))
            h.add(ChatMessage(role = "assistant", content = answer))
        }
        return h
    }

    // ═══════════════════ 压力裁剪（既有语义）═══════════════════

    @Test
    fun `A1 未超预算时不压缩且返回 null`() {
        val h = historyOf(2)
        assertFalse(engine().pressure(h))
        assertNull("没超预算就不该有压缩动作", engine().compactIfNeeded(h))
        assertEquals(4, h.size)
    }

    @Test
    fun `A2 条数超上限时压最旧轮进置顶摘要并收敛`() {
        val h = historyOf(7)
        // requireNotNull 而不是 assertNotNull + 「!!」：JUnit 的 assertNotNull 是否能把
        // 可空量智能转换成非空，取决于依赖里有没有 @Contract 注解（这里没有），
        // 用它换来的可读性不值得让测试编译依赖于依赖版本
        val r = requireNotNull(engine().compactIfNeeded(h)) { "7 轮（14 条）必须触发压缩" }
        assertTrue("应裁剪到 ≤ 12 条，实际 ${h.size}", h.size <= 12)
        assertEquals("置顶应为滚动摘要", "system", h[0].role)
        assertTrue("摘要应包含被压缩轮的要点", h[0].content.contains("问题0"))
        assertEquals("最新一轮保留在末尾", "答案", h.last().content)
        assertTrue("应是压缩而不是丢弃", r.turnsCompressed >= 1)
        assertEquals("正常路径不该丢消息", 0, r.messagesDropped)
        assertEquals(CompactionTrigger.PRESSURE, r.trigger)
        assertTrue("条数应减少", r.messagesAfter < r.messagesBefore)
    }

    @Test
    fun `A3 字符超上限时压缩并保证总量回落`() {
        val h = historyOf(4, answer = "R".repeat(1500))
        val r = requireNotNull(engine().compactIfNeeded(h)) { "字符超上限必须触发压缩" }
        val chars = h.sumOf { it.content.length }
        assertTrue("总字符应 ≤ ${policy.maxChars}，实际 $chars", chars <= policy.maxChars)
        assertTrue(h.size <= policy.maxMessages)
        assertEquals("压缩后最旧应成为 system 摘要", "system", h[0].role)
        assertTrue(h[0].content.startsWith("[更早对话摘要]"))
        assertTrue("必须报告释放了多少字符，实际 $r", r.freedChars > 0)
    }

    @Test
    fun `A4 连续大量长对话后预算始终收敛且保留最新上下文`() {
        val h = mutableListOf<ChatMessage>()
        repeat(20) { i ->
            h.add(ChatMessage(role = "user", content = "问题$i"))
            h.add(ChatMessage(role = "assistant", content = "答案" + "很".repeat(800)))
        }
        engine().compactIfNeeded(h)
        assertTrue(h.sumOf { it.content.length } <= policy.maxChars)
        assertTrue(h.size <= policy.maxMessages)
        assertTrue("不能把上下文清空", h.isNotEmpty())
        assertEquals("最新回复应保留", "答案" + "很".repeat(800), h.last().content)
    }

    @Test
    fun `A5 摘要累积多点要点但不超过自身上限`() {
        val h = mutableListOf<ChatMessage>()
        repeat(20) { i ->
            h.add(ChatMessage(role = "user", content = "问题$i，这是一段比较长的提问用于测试摘要累积。"))
            h.add(ChatMessage(role = "assistant", content = "答案$i。"))
        }
        engine().compactIfNeeded(h)
        assertEquals("system", h[0].role)
        assertTrue(h[0].content.startsWith("[更早对话摘要]"))
        assertTrue(
            "摘要要点行数应 ≥ 2（累积了不止一轮），实际 ${h[0].content.lineSequence().count()}",
            h[0].content.lineSequence().count() >= 3,
        )
        assertTrue("摘要自身应 ≤ ${policy.digestMaxChars}", h[0].content.length <= policy.digestMaxChars)
    }

    @Test
    fun `A6 小窗口策略下摘要同步缩小且仍然收敛`() {
        // 本地 4096 窗口：历史预算被压到 983 字符，摘要上限随之缩到 245
        val p = CompactionPolicy.forWindow(4096)
        val h = mutableListOf<ChatMessage>()
        repeat(10) { i ->
            h.add(ChatMessage(role = "user", content = "问题$i" + "问".repeat(300)))
            h.add(ChatMessage(role = "assistant", content = "答案" + "答".repeat(300)))
        }
        engine(p).compactIfNeeded(h)
        assertTrue(
            "收紧后的总字符必须落在收紧后的预算内，实际 ${h.sumOf { it.content.length }} / ${p.maxChars}",
            h.sumOf { it.content.length } <= p.maxChars,
        )
        assertTrue(
            "摘要必须跟着缩（小窗口下 800 字符摘要是窗口的 1/5），实际 ${h[0].content.length} / ${p.digestMaxChars}",
            h[0].content.length <= p.digestMaxChars,
        )
    }

    // ═══════════════════ compactNow（接缝新增）═══════════════════

    @Test
    fun `B1 compactNow 强制收敛到摘要加最近 keepRecentTurns 轮`() {
        val h = historyOf(6)
        val r = engine().compactIfNeeded(h)
        assertNull("6 轮没超 12 条 / 6000 字符，压力入口不该动它", r)

        val forced = requireNotNull(engine().compactNow(h, CompactionTrigger.MANUAL)) {
            "强制压缩必须生效（用户点了「立即压缩」）"
        }
        assertEquals("除摘要外只剩 2 轮 = 4 条 + 1 条摘要", 5, h.size)
        assertEquals("system", h[0].role)
        assertEquals("剩下的 user 消息数 = keepRecentTurns", 2, h.count { it.role == "user" })
        assertEquals("被压掉 4 轮", 4, forced.turnsCompressed)
        assertEquals(CompactionTrigger.MANUAL, forced.trigger)
        assertEquals(0, forced.messagesDropped)
    }

    @Test
    fun `B2 compactNow 在历史已经足够短时返回 null`() {
        val h = historyOf(2)
        assertNull("只有 keepRecentTurns 轮，没有可压的", engine().compactNow(h))
        assertEquals(4, h.size)

        assertNull("空历史也不能崩", engine().compactNow(mutableListOf()))
    }

    @Test
    fun `B3 溢出恢复语义：compactNow 能显著缩小一个远未触顶的历史`() {
        // 溢出是被**请求内**累积的工具输出撑出来的，历史本身可能只有 3 轮；
        // 此时 compactNow 的价值是"总规模再小一截"，不是"必须压到阈值以下"
        val h = historyOf(3, answer = "很长的答案".repeat(200))
        val before = h.sumOf { it.content.length }
        val r = requireNotNull(engine().compactNow(h, CompactionTrigger.CONTEXT_OVERFLOW)) {
            "3 轮也该能压掉最旧一轮"
        }
        assertTrue("必须真的变小：$before -> ${h.sumOf { it.content.length }}", h.sumOf { it.content.length } < before)
        assertEquals(CompactionTrigger.CONTEXT_OVERFLOW, r.trigger)
    }

    // ═══════════════════ 轮完整性（配对平衡的等价物）═══════════════════

    @Test
    fun `C1 轮完整性判定的四种形态`() {
        val e = engine()
        assertTrue("空历史算平衡", e.isTurnBalanced(emptyList()))
        assertTrue(
            "正常一轮",
            e.isTurnBalanced(
                listOf(ChatMessage("user", "u"), ChatMessage("assistant", "a")),
            ),
        )
        assertTrue(
            "置顶摘要 + 两轮",
            e.isTurnBalanced(
                listOf(
                    ChatMessage("system", "[更早对话摘要]\n· x"),
                    ChatMessage("user", "u1"), ChatMessage("assistant", "a1"),
                    ChatMessage("user", "u2"), ChatMessage("assistant", "a2"),
                ),
            ),
        )
        assertFalse(
            "孤立 user（一轮被拆成半轮）",
            e.isTurnBalanced(listOf(ChatMessage("user", "u"))),
        )
        assertFalse(
            "两条连续 user —— 正是服务端会 400 的形态",
            e.isTurnBalanced(
                listOf(ChatMessage("user", "u1"), ChatMessage("user", "u2")),
            ),
        )
        assertFalse(
            "摘要后跟孤立 assistant",
            e.isTurnBalanced(
                listOf(ChatMessage("system", "[更早对话摘要]"), ChatMessage("assistant", "a")),
            ),
        )
    }

    @Test
    fun `C2 多次压缩后历史仍然保持轮完整性`() {
        val h = historyOf(9)
        val e = engine()
        repeat(3) {
            e.compactIfNeeded(h)
            assertTrue("压力压缩后必须仍然平衡，实际 ${h.map { m -> m.role }}", e.isTurnBalanced(h))
        }
        e.compactNow(h)
        assertTrue("强制压缩后必须仍然平衡，实际 ${h.map { m -> m.role }}", e.isTurnBalanced(h))
    }

    @Test
    fun `C3 摘要不计入要点行数`() {
        val e = engine()
        assertEquals("没有摘要时 0 行", 0, e.digestLines(historyOf(2)))
        val h = historyOf(7)
        e.compactIfNeeded(h)
        assertTrue("压过之后应有要点行", e.digestLines(h) >= 1)
        assertEquals(
            "要点行数 = 摘要行数 - 1（标题行不算要点）",
            h[0].content.lineSequence().count() - 1,
            e.digestLines(h),
        )
    }
}
