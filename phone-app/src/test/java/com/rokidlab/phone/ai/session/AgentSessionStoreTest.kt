package com.rokidlab.phone.ai.session

import com.rokidlab.phone.ai.ChatMessage
import com.rokidlab.phone.ai.TokenUsage
import com.rokidlab.phone.ai.compaction.CompactionTrigger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * [AgentSessionStore]（事件流驱动的会话记忆，纯 JVM）单测。
 *
 * 锁定语义（第 1~3 组 = 改造前 `AgentSessionHistoryTest` 的既有语义，必须逐条保持；
 * 第 4~5 组 = 事件流带来的新能力）：
 *
 *  1. 每轮记录 user+assistant 两条，assistant 的工具轨迹由 `ToolCall` 事件**派生**；
 *  2. 条数上限 12（约 6 轮）/ 字符上限 6000：超出时把最旧一轮压进置顶滚动摘要；
 *  3. **清空**：只由用户显式触发（`wipe` 真删文件）—— 原先的"10 分钟无活动自动过期"已于
 *     2026-09-20 移除（与"记忆随对话保存"相抵，且静默发生）；
 *  4. **重启重建**：同一个文件重新打开，历史逐条一致（这是整个接线的目的）；
 *  5. 工具调用/结果成对、被打断的轮不留半轮（否则请求会出现连续两条 user → 400）。
 */
class AgentSessionStoreTest {

    private lateinit var dir: File
    private lateinit var file: File
    private var now = 1_000_000L
    private val trims = mutableListOf<String>()

    @Before
    fun setUp() {
        dir = File(System.getProperty("java.io.tmpdir"), "agent-session-test-${System.nanoTime()}")
        dir.mkdirs()
        file = File(dir, "s.jsonl")
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    /** 新开一个 store（同一文件 = 模拟重启）；时钟取自 [now] */
    private fun newStore(): AgentSessionStore =
        AgentSessionStore(SessionLog(file) { now }, onTrim = { trims.add(it) })

    /** 记一轮：user + （可选的工具调用对）+ assistant */
    private fun recordTurn(
        store: AgentSessionStore,
        user: String,
        reply: String,
        tools: List<String> = emptyList(),
    ) {
        now += 1
        val t = store.beginTurn(MessageSource.TEXT)
        store.appendUserMessage(t, user, MessageSource.TEXT)
        tools.forEachIndexed { i, name ->
            store.appendToolCall(t, "call$i", name, "{}")
            store.appendToolResult(t, "call$i", name, "ok")
        }
        store.finishTurn(t, reply, TurnEndReason.COMPLETED)
    }

    private fun chars(h: List<ChatMessage>) = h.sumOf { it.content.length }

    // ═══════════════════ 1. 基本记录（改造前既有语义）═══════════════════

    @Test
    fun `记录一轮生成 user 与 assistant 两条消息`() {
        val store = newStore()
        recordTurn(store, "你好", "你好，有什么可以帮你")
        val h = store.history()
        assertEquals(2, h.size)
        assertEquals("user", h[0].role)
        assertEquals("你好", h[0].content)
        assertEquals("assistant", h[1].role)
        assertEquals("你好，有什么可以帮你", h[1].content)
        assertTrue(h[1].toolTrace.isEmpty())
    }

    @Test
    fun `工具轨迹由 ToolCall 事件派生到 assistant 消息上`() {
        val store = newStore()
        recordTurn(store, "看下电量", "当前电量 80%", tools = listOf("get_glasses_status"))
        assertEquals(listOf("get_glasses_status({})"), store.history().last().toolTrace)
    }

    @Test
    fun `条数超上限时压缩最旧一轮进滚动摘要并保持预算`() {
        val store = newStore()
        // 7 轮 → 14 条；超 12 条触发：最旧轮压缩进置顶滚动摘要，条数回落到 ≤ 12
        repeat(7) { i -> recordTurn(store, "u$i", "r$i") }
        val h = store.history()
        assertTrue("应裁剪到 ≤ 12 条，实际 ${h.size}", h.size <= 12)
        assertEquals("置顶应为滚动摘要", "system", h.first().role)
        assertTrue("摘要应包含被压缩轮的要点", h.first().content.contains("u0"))
        assertEquals("最新一轮保留在末尾", "r6", h.last().content)
    }

    @Test
    fun `字符超上限时压缩最旧轮并保证总量回落`() {
        val store = newStore()
        // 每轮约 1501 字符；第 4 轮累计超过 6000 触发压缩，最旧轮被合并成滚动摘要
        repeat(4) { i -> recordTurn(store, "Q", "R".repeat(1500)) }
        val h = store.history()
        val c = chars(h)
        assertTrue("总字符应 ≤ 6000，实际 $c", c <= 6000)
        assertTrue("条数应 ≤ 12，实际 ${h.size}", h.size <= 12)
        assertEquals("压缩后最旧应成为 system 摘要", "system", h.first().role)
        assertTrue(h.first().content.startsWith("[更早对话摘要]"))
    }

    @Test
    fun `连续大量长对话后预算始终收敛且有上下文保留`() {
        val store = newStore()
        repeat(20) { i -> recordTurn(store, "问题$i", "答案" + "很".repeat(800)) }
        val h = store.history()
        assertTrue(chars(h) <= 6000)
        assertTrue(h.size <= 12)
        assertTrue("不能把上下文清空", h.isNotEmpty())
        assertEquals("最新回复应保留", "答案" + "很".repeat(800), h.last().content)
    }

    @Test
    fun `滚动摘要累积多轮要点且不超自身上限`() {
        val store = newStore()
        repeat(20) { i -> recordTurn(store, "问题$i，这是一段比较长的提问用于测试摘要累积。", "答案$i。") }
        val digest = store.history().first()
        assertEquals("system", digest.role)
        assertTrue(digest.content.startsWith("[更早对话摘要]"))
        assertTrue(
            "摘要行数应 ≥ 2（累积了不止一轮），实际 ${digest.content.lineSequence().count()}",
            digest.content.lineSequence().count() >= 3,
        )
        assertTrue("摘要自身应 ≤ 800 字符", digest.content.length <= 800)
    }

    @Test
    fun `clear 手动清空（真删文件）`() {
        val store = newStore()
        recordTurn(store, "a", "b")
        val seqBefore = SessionLog(file).lastSeq()
        assertTrue("前置条件：已经写过事件", seqBefore > 0)
        store.wipe()
        assertTrue(store.history().isEmpty())
        assertFalse("文件应被删除", file.exists())
        // 真删之后 seq 从头开始：新文件里 TurnStart/UserMessage/AssistantMessage/TurnEnd 四条
        recordTurn(store, "c", "d")
        assertEquals("清空后 seq 应重新从 1 开始", 4L, SessionLog(file).lastSeq())
    }

    @Test
    fun `裁剪日志回调可观察`() {
        val store = newStore()
        repeat(7) { i -> recordTurn(store, "u$i", "r$i") }
        assertTrue("发生条数裁剪时应回调", trims.isNotEmpty())
    }

    // ═══════════════════ 2. 重启重建（接线的目的）═══════════════════

    @Test
    fun `重启后从同一个文件重建出逐条一致的历史`() {
        val first = newStore()
        recordTurn(first, "甲", "壹", tools = listOf("get_time"))
        recordTurn(first, "乙", "贰")
        recordTurn(first, "丙", "叁")
        val expected = first.history()

        // 模拟进程重启：同一个文件、全新的 store
        val second = newStore()
        assertEquals(expected, second.history())
        assertEquals("工具轨迹也应重建", listOf("get_time({})"), second.history()[1].toolTrace)
    }

    @Test
    fun `重启后压缩过的历史仍然保持一致`() {
        val first = newStore()
        repeat(20) { i -> recordTurn(first, "问题$i", "答案" + "很".repeat(800)) }
        val expected = first.history()
        assertTrue("前置条件：确实压缩过", first.hasDigest())

        val second = newStore()
        assertEquals("压缩是声明式的，重启后重放必须得到同一份历史", expected, second.history())
        assertEquals(expected.size, second.history().size)
    }

    @Test
    fun `轮号在重启后继续递增不会重复`() {
        val first = newStore()
        recordTurn(first, "a", "b")
        recordTurn(first, "c", "d")
        val second = newStore()
        assertEquals(3, second.nextTurn())
        assertEquals(3, second.beginTurn(MessageSource.TEXT))
    }

    // ═══════════════════ 3. 工具调用成对与轮完整性 ═══════════════════

    @Test
    fun `工具调用与结果成对 有调用必有结果`() {
        val store = newStore()
        recordTurn(store, "查一下", "查到了", tools = listOf("search_knowledge_base"))
        assertTrue("成对的调用不应被当成孤儿", store.orphanToolCalls().isEmpty())
    }

    @Test
    fun `有调用无结果 = 崩溃留下的孤儿调用`() {
        val store = newStore()
        val t = store.beginTurn(MessageSource.TEXT)
        store.appendUserMessage(t, "开始吧", MessageSource.TEXT)
        store.appendToolCall(t, "c1", "save_code_file", "{\"file\":\"index.ink\"}")
        // 进程在此刻被杀：没有 ToolResult，也没有 TurnEnd
        val reopened = newStore()
        val orphans = reopened.orphanToolCalls()
        assertEquals(1, orphans.size)
        assertEquals("save_code_file", orphans[0].name)
    }

    @Test
    fun `被打断的轮不留半轮（否则请求会出现连续两条 user）`() {
        val store = newStore()
        recordTurn(store, "第一轮", "好的")
        // 第二轮：用户消息已落盘，但生成被打断
        val t = store.beginTurn(MessageSource.VOICE)
        store.appendUserMessage(t, "第二轮", MessageSource.VOICE)
        store.appendAttempt(t, AttemptOutcome.SUPERSEDED, detail = "被新消息抢占")
        store.finishTurn(t, null, TurnEndReason.INTERRUPTED)

        val h = store.history()
        assertEquals("被打断的那轮必须完全退出可见历史", listOf("第一轮", "好的"), h.map { it.content })
        assertTrue("历史必须保持 user/assistant 平衡（偶数、以 assistant 结尾）", h.size % 2 == 0)
        assertFalse("本轮那条 user 消息不能残留", h.any { it.content == "第二轮" })
    }

    @Test
    fun `失败的轮同样不留半轮`() {
        val store = newStore()
        val t = store.beginTurn(MessageSource.TEXT)
        store.appendUserMessage(t, "会失败的提问", MessageSource.TEXT)
        store.finishTurn(t, null, TurnEndReason.FAILED, detail = "连接超时")
        assertTrue(store.history().isEmpty())
    }

    @Test
    fun `失败的尝试不进模型历史但可在事件流里查到`() {
        val store = newStore()
        recordTurn(store, "问", "答")
        val t = store.beginTurn(MessageSource.TEXT)
        store.appendUserMessage(t, "再问", MessageSource.TEXT)
        store.appendAttempt(t, AttemptOutcome.OVERFLOW, detail = "context length exceeded")
        store.finishTurn(t, "重试后的答案", TurnEndReason.COMPLETED)

        val h = store.history()
        assertTrue("失败尝试不该作为消息出现", h.none { it.content.contains("context length") })
        assertEquals(4, h.size)
        assertEquals("重试后的答案", h.last().content)
    }

    // ═══════════════════ 4. 丢弃最后一轮 ═══════════════════

    @Test
    fun `丢弃最后一轮只否定末尾那轮 摘要保留`() {
        val store = newStore()
        repeat(10) { i -> recordTurn(store, "u$i", "r$i") }
        val before = store.history()
        assertTrue("前置条件：有摘要", store.hasDigest())
        val digest = before.first().content

        store.dropLastTurn()

        val after = store.history()
        assertEquals("末尾一轮被移出可见历史", before.size - 2, after.size)
        assertEquals("摘要必须保留（它代表更早的有效记忆）", digest, after.first().content)
        assertFalse("被丢弃的那轮不该还在", after.any { it.content == "r9" })
    }

    @Test
    fun `丢弃最后一轮不会丢掉正在进行中的那一轮`() {
        val store = newStore()
        recordTurn(store, "完成的轮", "答案")
        // 正在进行的一轮（尚未 finish）
        val t = store.beginTurn(MessageSource.TEXT)
        store.appendUserMessage(t, "进行中", MessageSource.TEXT)

        store.dropLastTurn()

        val h = store.history()
        assertTrue("『完成的轮』应被丢弃", h.none { it.content == "完成的轮" })
        assertEquals("『进行中的轮』不受影响", "进行中", h.last().content)
    }

    @Test
    fun `没有完成过任何一轮时丢弃是空操作`() {
        val store = newStore()
        store.dropLastTurn()
        assertTrue(store.history().isEmpty())
    }

    // ═══════════════════ 5. 投影契约与压缩入口 ═══════════════════

    @Test
    fun `visibleRecords 与 history 的消息一一对应`() {
        val store = newStore()
        recordTurn(store, "甲", "壹", tools = listOf("get_time"))
        recordTurn(store, "乙", "贰")
        val h = store.history()
        val records = store.visibleRecords()
        assertEquals("无摘要时一一对应", h.size, records.size)
        records.forEachIndexed { i, r ->
            val role = when (r.event) {
                is UserMessage -> "user"
                is AssistantMessage -> "assistant"
                else -> error("不该有非消息事件进入可见记录：${r.event}")
            }
            assertEquals(role, h[i].role)
        }
    }

    @Test
    fun `有摘要时 visibleRecords 比 history 少置顶那一条`() {
        val store = newStore()
        repeat(10) { i -> recordTurn(store, "u$i", "r$i") }
        assertEquals(store.history().size - 1, store.visibleRecords().size)
    }

    @Test
    fun `手动压缩把历史收敛到摘要加最近两轮`() {
        val store = newStore()
        repeat(6) { i -> recordTurn(store, "u$i", "r$i") }
        val result = store.compactNow(CompactionTrigger.MANUAL)
        assertNotNull("6 轮远超保留轮数，必须发生压缩", result)
        val h = store.history()
        assertEquals("摘要 + 最近 2 轮", 5, h.size)
        assertEquals("system", h.first().role)
        assertTrue("最近一轮保留", h.any { it.content == "r5" })
    }

    @Test
    fun `历史很短时手动压缩返回 null`() {
        val store = newStore()
        recordTurn(store, "a", "b")
        assertEquals(null, store.compactNow(CompactionTrigger.MANUAL))
    }

    @Test
    fun `压低预算后立刻收紧而不是等下一轮`() {
        val store = newStore()
        repeat(4) { i -> recordTurn(store, "u$i", "r$i") }
        assertEquals(8, store.history().size)
        // 小窗口模型：把字符预算压到 600（下限）
        store.applyPolicy(com.rokidlab.phone.ai.compaction.CompactionPolicy.DEFAULT.copy(maxChars = 600))
        assertTrue("收紧后应立刻落到新预算内", chars(store.history()) <= 600)
    }

    // ═══════════════════ 6. 成本可观测（方案 §4.3.4）═══════════════════

    /** 开一轮并返回句柄（直接用 AgentTurn，绕开"必须起一个真实请求"） */
    private fun beginTurn(store: AgentSessionStore): AgentTurn =
        AgentTurn(store, store.beginTurn(MessageSource.TEXT), MessageSource.TEXT)

    @Test
    fun `多次模型调用的用量累加后落到 TurnEnd`() {
        val store = newStore()
        val turn = beginTurn(store)
        turn.userMessage("查两件事")
        turn.recordModelCall(TokenUsage(100, 10))
        turn.recordModelCall(null) // 服务端没给 usage 的那次：只计次数，不编数字
        turn.recordModelCall(TokenUsage(250, 30))
        turn.finish(TurnEndReason.COMPLETED, "都查好了")

        val stat = store.lastTurnStat()
        assertNotNull(stat)
        assertEquals("输入 token 必须累加（工具循环每轮都要重发历史）", 350, stat!!.promptTokens)
        assertEquals(40, stat.completionTokens)
        assertEquals(3, stat.modelCalls)
        assertEquals(TurnEndReason.COMPLETED, stat.reason)
        assertTrue("耗时由事件流的 ts 现算，不应为负", (stat.elapsedMs ?: -1L) >= 0L)
        assertEquals("回复字符数用于估算口径", "都查好了".length, stat.replyChars)
    }

    @Test
    fun `服务端一次都没给用量时保持 null 而不是 0`() {
        val store = newStore()
        val turn = beginTurn(store)
        turn.userMessage("问")
        turn.recordModelCall(null)
        turn.finish(TurnEndReason.COMPLETED, "答")

        val stat = store.lastTurnStat()!!
        assertEquals("不能用 0 冒充真实用量", null, stat.promptTokens)
        assertEquals(null, stat.completionTokens)
        assertEquals("调用次数是我们自己的事实，仍然要报", 1, stat.modelCalls)
        assertEquals("一个都没给 ⇒ 累计值也是未知", null, store.usageTotals())
    }

    @Test
    fun `被打断的轮同样记录成本`() {
        val store = newStore()
        val turn = beginTurn(store)
        turn.userMessage("一个会跑很久的任务")
        turn.recordModelCall(TokenUsage(999, 99))
        turn.finish(TurnEndReason.INTERRUPTED)

        assertTrue("被抢占的轮不进可见历史", store.history().isEmpty())
        val stat = store.lastTurnStat()!!
        assertEquals("成本必须留下 —— 被打断的长任务恰恰最烧 token", 999, stat.promptTokens)
        assertEquals(TurnEndReason.INTERRUPTED, stat.reason)
    }

    @Test
    fun `会话累计用量回报贡献了数字的轮数`() {
        val store = newStore()
        val a = beginTurn(store)
        a.userMessage("第一轮")
        a.recordModelCall(TokenUsage(100, 10))
        a.finish(TurnEndReason.COMPLETED, "答一")

        // 第二轮服务端没给用量 —— 它不该被算进累计，但也不该让累计消失
        val b = beginTurn(store)
        b.userMessage("第二轮")
        b.recordModelCall(null)
        b.finish(TurnEndReason.COMPLETED, "答二")

        val totals = store.usageTotals()!!
        assertEquals(100, totals.promptTokens)
        assertEquals(10, totals.completionTokens)
        assertEquals("只有 1 轮贡献了真实数字，必须一并回报", 1, totals.turnsWithUsage)
        assertEquals(110, totals.total)
    }

    @Test
    fun `用量与耗时在重启后仍然重建`() {
        val first = newStore()
        val turn = beginTurn(first)
        turn.userMessage("问")
        turn.recordModelCall(TokenUsage(777, 88))
        turn.finish(TurnEndReason.COMPLETED, "答")

        val reopened = newStore().lastTurnStat()!!
        assertEquals(777, reopened.promptTokens)
        assertEquals(88, reopened.completionTokens)
        assertEquals(1, reopened.modelCalls)
    }
}
