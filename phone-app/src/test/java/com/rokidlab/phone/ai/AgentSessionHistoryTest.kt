package com.rokidlab.phone.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * AgentSessionHistory（会话记忆纯逻辑，无 Android 依赖）单测。
 *
 * 锁定语义：
 *  1. 每轮记录 user+assistant 两条，assistant 可携带工具轨迹；
 *  2. 条数上限 12（约 6 轮）：超出时把最旧一轮压缩成 system 摘要后继续丢弃；
 *  3. 字符上限 6000：超出时压缩/丢弃最旧轮直到回落；
 *  4. 10 分钟（600000ms）无活动自动清空（maybeExpire 返回是否触发）。
 */
class AgentSessionHistoryTest {

    private val t0 = 1_000_000L
    private lateinit var store: AgentSessionHistory

    @Before
    fun setUp() {
        store = AgentSessionHistory()
    }

    @Test
    fun `记录一轮生成 user 与 assistant 两条消息`() {
        store.recordTurn("你好", "你好，有什么可以帮你", nowMs = t0)
        val h = store.getHistory()
        assertEquals(2, h.size)
        assertEquals("user", h[0].role)
        assertEquals("你好", h[0].content)
        assertEquals("assistant", h[1].role)
        assertEquals("你好，有什么可以帮你", h[1].content)
        assertTrue(h[1].toolTrace.isEmpty())
    }

    @Test
    fun `工具轨迹随 assistant 消息保留`() {
        store.recordTurn("看下电量", "当前电量 80%", listOf("get_glasses_status"), nowMs = t0)
        assertEquals(listOf("get_glasses_status"), store.getHistory().last().toolTrace)
    }

    @Test
    fun `条数超上限时压缩最旧一轮进滚动摘要并保持预算`() {
        // 7 轮 → 14 条；第 7 轮触发：最旧轮压缩进置顶滚动摘要，条数回落到 ≤ 12
        repeat(7) { i ->
            store.recordTurn("u$i", "r$i", nowMs = t0 + i)
        }
        val h = store.getHistory()
        assertTrue("应裁剪到 ≤ 12 条，实际 ${h.size}", h.size <= 12)
        assertEquals("置顶应为滚动摘要", "system", h.first().role)
        assertTrue("摘要应包含被压缩轮的要点", h.first().content.contains("u0"))
        assertEquals("最新一轮保留在末尾", "r6", h.last().content)
    }

    @Test
    fun `字符超上限时压缩最旧轮并保证总量回落`() {
        // 每轮约 1501 字符；第 4 轮累计超过 6000 触发压缩，最旧轮被合并成滚动摘要
        repeat(4) { i ->
            store.recordTurn("Q", "R".repeat(1500), nowMs = t0 + i)
        }
        val h = store.getHistory()
        val chars = h.sumOf { it.content.length }
        assertTrue("总字符应 ≤ 6000，实际 $chars", chars <= 6000)
        assertTrue("条数应 ≤ 12，实际 ${h.size}", h.size <= 12)
        assertEquals("压缩后最旧应成为 system 摘要", "system", h.first().role)
        assertTrue(h.first().content.startsWith("[更早对话摘要]"))
    }

    @Test
    fun `连续大量长对话后预算始终收敛且有上下文保留`() {
        repeat(20) { i ->
            store.recordTurn("问题$i", "答案" + "很".repeat(800), nowMs = t0 + i)
        }
        val h = store.getHistory()
        val chars = h.sumOf { it.content.length }
        assertTrue(chars <= 6000)
        assertTrue(h.size <= 12)
        assertTrue("不能把上下文清空", h.isNotEmpty())
        assertEquals("最新回复应保留", "答案" + "很".repeat(800), h.last().content)
    }

    @Test
    fun `滚动摘要累积多轮要点且不超自身上限`() {
        // 20 轮长对话：摘要应累积多条要点行，但始终 ≤ 800 字符
        repeat(20) { i ->
            store.recordTurn("问题$i，这是一段比较长的提问用于测试摘要累积。", "答案$i。", nowMs = t0 + i)
        }
        val h = store.getHistory()
        val digest = h.first()
        assertEquals("system", digest.role)
        assertTrue(digest.content.startsWith("[更早对话摘要]"))
        assertTrue(
            "摘要行数应 ≥ 2（累积了不止一轮），实际 ${digest.content.lineSequence().count()}",
            digest.content.lineSequence().count() >= 3,
        )
        assertTrue("摘要自身应 ≤ 800 字符", digest.content.length <= 800)
    }

    @Test
    fun `10 分钟内无活动不清理 超过则清空`() {
        store.recordTurn("hi", "yo", nowMs = t0)
        // 9:59 内：未过期
        assertFalse(store.maybeExpire(t0 + 599_999))
        assertEquals(2, store.getHistory().size)
        // 恰好 10:00：未超过阈值，不清理
        assertFalse(store.maybeExpire(t0 + 600_000))
        assertEquals(2, store.getHistory().size)
        // 超过 10:00：清理，且返回 true
        assertTrue(store.maybeExpire(t0 + 600_001))
        assertTrue(store.getHistory().isEmpty())
    }

    @Test
    fun `注入时钟驱动过期语义`() {
        var now = t0
        val clocked = AgentSessionHistory(clock = { now })
        clocked.recordTurn("a", "b")
        now += 9 * 60_000L
        clocked.recordTurn("c", "d") // 距上次 9 分钟，重置计时
        now += 600_001L
        assertTrue("距最后一次活动超过 10 分钟应过期", clocked.maybeExpire())
        assertTrue(clocked.getHistory().isEmpty())
    }

    @Test
    fun `clear 手动清空`() {
        store.recordTurn("a", "b", nowMs = t0)
        store.clear()
        assertTrue(store.getHistory().isEmpty())
    }

    @Test
    fun `裁剪日志回调可观察`() {
        val trims = mutableListOf<String>()
        val s = AgentSessionHistory(onTrim = { trims.add(it) })
        repeat(7) { i -> s.recordTurn("u$i", "r$i", nowMs = t0 + i) }
        assertTrue("发生条数裁剪时应回调", trims.isNotEmpty())
    }
}
