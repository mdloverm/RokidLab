package com.rokidlab.phone.ai.session

import com.rokidlab.phone.ai.ChatMessage
import com.rokidlab.phone.ai.TokenUsage
import com.rokidlab.phone.ai.compaction.CompactionPolicy
import com.rokidlab.phone.ai.compaction.CompactionTrigger
import com.rokidlab.phone.ai.compaction.TokenMeter
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
 *  2. 条数上限 12（约 6 轮）/ token 上限 4000（真实计量见 `TokenMeter`，2026-10-02 起替代
 *     字符口径）：超出时把最旧一轮压进置顶滚动摘要；
 *  3. **清空**：只由用户显式触发（`wipe` 真删文件）—— 原先的"10 分钟无活动自动过期"已于
 *     2026-09-20 移除（与"记忆随对话保存"相抵，且静默发生）；
 *  4. **重启重建**：同一个文件重新打开，历史逐条一致（这是整个接线的目的）；
 *  5. 工具调用/结果成对、被打断的轮不留半轮（否则请求会出现连续两条 user → 400）。
 */
/**
 * 预算阈值的**单一取值处**。
 *
 * ⚠️ 2026-10-06：本文件原先到处写死 `12 条` / `4000 token`。
 * 那次把 [CompactionPolicy] 的默认档从 4000/12 放宽到 8000/25 之后，
 * 这些用例集体失效 —— 而且**失效方式很隐蔽**：不是断言数字对不上，
 * 是"造 7 轮本来想触发压缩，现在不触发了"，于是报"前置条件：有摘要"这种
 * 看起来毫不相关的错。阈值型测试必须跟着阈值走。
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

    private fun tokens(h: List<ChatMessage>) = h.sumOf { TokenMeter.count(it.content) }

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
        // ⚠️ 轮数按上限**加过量**：原来写死 7 轮（对上旧上限 12 条刚好超 2 条），
        // 阈值一放宽就只超一半 ⇒ 压缩只折掉几轮，末尾索引对不上（expected r6 but was r13）。
        // 这里造「上限轮数 + 5」，保证压缩真的发生且末轮仍是最后造的那一轮。
        val turns = CompactionPolicy.DEFAULT.maxMessages / 2 + 5
        repeat(turns) { i -> recordTurn(store, "u$i", "r$i") }
        val h = store.history()
        assertTrue("应裁剪到 ≤ ${CompactionPolicy.DEFAULT.maxMessages} 条，实际 ${h.size}",
            h.size <= CompactionPolicy.DEFAULT.maxMessages)
        assertEquals("置顶应为滚动摘要", "system", h.first().role)
        assertTrue("摘要应包含被压缩轮的要点", h.first().content.contains("u0"))
        assertEquals("最新一轮保留在末尾", "r${turns - 1}", h.last().content)
    }

    @Test
    fun `token超上限时压缩最旧轮并保证总量回落`() {
        val store = newStore()
        // ⚠️ 轮数按预算推导：每轮约 3000 token，要累计超过 maxTokens 才触发压缩
        val perRound = 3000
        val turns = CompactionPolicy.DEFAULT.maxTokens / perRound + 1
        repeat(turns) { recordTurn(store, "Q", "很".repeat(perRound)) }
        val h = store.history()
        val t = tokens(h)
        assertTrue(
            "总 token 应 ≤ ${CompactionPolicy.DEFAULT.maxTokens}，实际 $t",
            t <= CompactionPolicy.DEFAULT.maxTokens,
        )
        assertTrue("条数应 ≤ ${CompactionPolicy.DEFAULT.maxMessages}，实际 ${h.size}",
            h.size <= CompactionPolicy.DEFAULT.maxMessages)
        assertEquals("压缩后最旧应成为 system 摘要", "system", h.first().role)
        assertTrue(h.first().content.startsWith("[更早对话摘要]"))
    }

    @Test
    fun `连续大量长对话后预算始终收敛且有上下文保留`() {
        val store = newStore()
        repeat(20) { i -> recordTurn(store, "问题$i", "答案" + "很".repeat(800)) }
        val h = store.history()
        assertTrue(
            "总 token 应 ≤ 默认预算 ${CompactionPolicy.DEFAULT.maxTokens}，实际 ${tokens(h)}",
            tokens(h) <= CompactionPolicy.DEFAULT.maxTokens,
        )
        assertTrue(h.size <= CompactionPolicy.DEFAULT.maxMessages)
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
        repeat(CompactionPolicy.DEFAULT.maxMessages / 2 + 2) { i -> recordTurn(store, "u$i", "r$i") }
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
        // 轮数必须**远超**条数上限才有摘要（原来 10 轮对上旧上限 12 条刚好够，
        // 上限一放宽就退化成"没有摘要"，后面每条断言都跟着崩）
        repeat(CompactionPolicy.DEFAULT.maxMessages) { i -> recordTurn(store, "u$i", "r$i") }
        val before = store.history()
        assertTrue("前置条件：有摘要（轮数 ${CompactionPolicy.DEFAULT.maxMessages} 应远超上限）", store.hasDigest())
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
        // 轮数远超上限才有摘要（这条断言本身就是"有摘要时"，所以前提必须成立）
        repeat(CompactionPolicy.DEFAULT.maxMessages) { i -> recordTurn(store, "u$i", "r$i") }
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
        // 小窗口模型：把 token 预算压到下限 380
        store.applyPolicy(com.rokidlab.phone.ai.compaction.CompactionPolicy.DEFAULT.copy(maxTokens = 380))
        assertTrue("收紧后应立刻落到新预算内", tokens(store.history()) <= 380)
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

    // ═══════════════════ 6. 摘要 LLM 精炼（digestRefiner，2026-10-02）═══════════════════

    /** 带精炼器的 store（其余与 [newStore] 相同） */
    private fun newStoreWithRefiner(refined: String?): AgentSessionStore =
        AgentSessionStore(
            SessionLog(file) { now },
            onTrim = { trims.add(it) },
            digestRefiner = { _, _ -> refined },
        )

    /** 记一轮超长对话，触发压力压缩 */
    private fun recordLongTurn(store: AgentSessionStore, user: String, reply: String) {
        now += 1
        val t = store.beginTurn(MessageSource.TEXT)
        store.appendUserMessage(t, user, MessageSource.TEXT)
        store.finishTurn(t, reply, TurnEndReason.COMPLETED)
    }

    @Test
    fun `精炼成功后摘要文本被替换且仍以标题行开头`() {
        val store = newStoreWithRefiner("· 用户要求把首页按钮改成红色，已完成")
        // ⚠️ 轮数按预算推导（原来只有 2 轮，对上旧上限刚好触发；
        // 放宽到 8000 token 后不再触发压缩，摘要不会出现 ⇒ 报 expected system but was user）
        val turns = CompactionPolicy.DEFAULT.maxMessages / 2 + 2
        repeat(turns) { i ->
            recordLongTurn(store, "帮我把首页按钮改成红色", "好的，已改为红色，重启后生效。".repeat(300))
            if (i == turns - 1) recordLongTurn(store, "再看看电量页", "电量页正常，无需处理。".repeat(300))
        }
        // 超长轮次触发压缩；本地摘要被精炼结果替换
        val h = store.history()
        assertEquals("system", h[0].role)
        assertTrue(
            "摘要必须是精炼后的文本（含标题行）",
            h[0].content.startsWith("[更早对话摘要]") && h[0].content.contains("改成红色"),
        )
        // 压缩确实发生了：历史被压到条数上限附近。
        // ⚠️ 不要再断言"摘要行数 == 折掉的轮数" —— 摘要是**增量滚动**的，
        // 超过 [CompactionPolicy.digestMaxChars]（默认 800 字）就丢最旧的行。
        // 我造了十几轮同内容的轮次，全部折进摘要会超预算 ⇒ 旧行被丢 ⇒ 那个等式不成立。
        // 这条用例要验的是「精炼结果替换了本地摘要」，压缩的量由上面的断言覆盖。
        assertTrue(
            "应至少折掉一轮（造了 $turns 轮 = ${turns * 2} 条，剩 ${h.size} 条）",
            h.size < turns * 2,
        )
        // 摘要自身受字符预算约束（不验证具体行数，只验证它有内容且没超预算）
        assertTrue("摘要应有要点行", digestLineCount(h) >= 1)

        // 重启后精炼结果仍在（它是事件流的一部分，不只是内存态）
        assertEquals(h[0].content, newStore().history()[0].content)
    }

    @Test
    fun `精炼失败静默保留本地摘要`() {
        val store = newStoreWithRefiner(null)
        // ⚠️ 同上：轮数按预算推导，2 轮在放宽后的预算下不触发压缩
        val turns = CompactionPolicy.DEFAULT.maxMessages / 2 + 2
        repeat(turns) { i ->
            recordLongTurn(store, "第一个问题", "第一个回答。".repeat(600))
            if (i == turns - 1) recordLongTurn(store, "第二个问题", "第二个回答。".repeat(600))
        }
        val h = store.history()
        assertEquals("system", h[0].role)
        assertTrue(
            "精炼失败时必须是本地提取的摘要行",
            h[0].content.startsWith("[更早对话摘要]") && h[0].content.contains("· 用户问"),
        )
    }

    /** 折叠摘要里的要点行数（标题行不算）—— 用它断言"折了几轮就有几行" */
    private fun digestLineCount(history: List<ChatMessage>): Int {
        if (history.isEmpty() || !history[0].content.startsWith("[更早对话摘要]")) return 0
        return history[0].content.lines().count { it.trimStart().startsWith("·") }
    }

    // ═══════════════ 6. 内存有界性（2026-10-07 审计新增）═══════════════

    /**
     * 锁「内存中已加载的记录有上限」。
     *
     * 旧实现**没有 trim**：全仓只有 `wipe()` 会`records.clear()`，压缩只裁**投影**不裁 records
     * ⇒ 一个长会话的完整事件流（含每轮工具参数与返回值）在内存里单调增长。
     * 落盘 jsonl 也只有单字段 `MAX_FIELD_CHARS`，**没有条数/体积上限**。
     *
     * 📌 判据：这类缺陷在代码里就一行`size > max`，review 时肉眼极难注意到，
     * 而且只在用户反馈"用久了就崩"时才显形。所以必须写成断言。
     *
     * ⚠️ 断言指向**生产代码本身**（[AgentSessionStore.MAX_LOADED_RECORDS]），
     * 不是在测试里复制一份上限。
     */
    @Test
    fun `loaded records stay bounded under long sessions`() {
        val cap = AgentSessionStore.MAX_LOADED_RECORDS
        val store = newStore()
        // 每轮 3 条（TurnStart + user + assistant），远超上限
        val turns = (cap / 3) + 40
        repeat(turns) { i -> recordTurn(store, "问题$i", "回答$i") }

        assertTrue(
            "内存记录数不得超过上限（实际 ${store.loadedRecordCount()} > $cap）",
            store.loadedRecordCount() <= cap,
        )
        // ⚠️ 关键：trim 只丢**最旧**的，轮号必须继续单调推进 ——
        // nextTurn() 依赖 maxTurn()，若 trim 误删最新记录会导致轮号回退、
        // 历史上出现两条相同 user 消息 ⇒ 请求直接 400。
        // （nextTurn 返回的是「下一轮号」，故为 turns + 1）
        assertEquals("轮号必须在 trim 后继续递增，不得回退", turns + 1, store.nextTurn())
    }

    /**
     * 锁「重启后仍能读回完整历史」—— trim 只作用于**内存**，磁盘 jsonl 不受影响。
     *
     * ⚠️ 这是本修复的安全前提：不能为了有界性牺牲持久化（否则"过程"回放、
     * 跨会话检索全部失效）。如果哪天有人把 trim 也做进 [SessionLog]，
     * 这例会红。
     */
    @Test
    fun `trimming loaded records does not lose persisted history`() {
        val store = newStore()
        val turns = 40
        repeat(turns) { i -> recordTurn(store, "问题$i", "回答$i") }

        // 重新打开同一文件 = 模拟重启，走allRecords() 的加载路径
        val reopened = newStore()
        assertEquals(
            "磁盘 jsonl 必须保留全部轮次（trim 只作用于内存）",
            turns,
            reopened.maxTurn(),
        )
    }

    /**
     * 锁「加载路径也裁」—— 即重开一个超长会话时同样有界。
     *
     * 📌 这条是最容易被漏掉的一半：**只在 append 时裁**看着有界，
     * 但重启一次 `log.read()` 又把全部历史读回内存，等于没上限
     *（这正是无界缓存的典型形状：写入点有上限、恢复点没上限）。
     */
    @Test
    fun `loading a long persisted session is bounded too`() {
        val cap = AgentSessionStore.MAX_LOADED_RECORDS
        // 先造一个远超上限的磁盘文件
        val store = newStore()
        repeat((cap / 3) + 40) { i -> recordTurn(store, "问题$i", "回答$i") }

        val reopened = newStore()
        assertTrue(
            "重新打开超长会话时，加载路径同样必须裁剪",
            reopened.loadedRecordCount() <= cap,
        )
    }
}
