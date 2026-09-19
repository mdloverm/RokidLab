package com.rokidlab.phone.ai.session

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * [SessionLog]（追加式事件流落盘，纯 JVM）单测。
 *
 * 锁定语义：
 *  1. seq 由日志分配、单调递增、跨实例（模拟重启）不重复；
 *  2. **只追加**，回放顺序 = 写入顺序，`read(from)` 按 seq 增量；
 *  3. 鲁棒性：任何一行看不懂就**只跳过那一行** —— 写坏最后一行只丢最后一条事件；
 *  4. 全部事件类型编解码往返一致（新增类型时这条会立刻抓到漏写的字段）。
 */
class SessionLogTest {

    private lateinit var dir: File
    private lateinit var file: File
    private var now = 1_000L

    @Before
    fun setUp() {
        dir = File(System.getProperty("java.io.tmpdir"), "session-log-test-${System.nanoTime()}")
        dir.mkdirs()
        file = File(dir, "s.jsonl")
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    /** 注入固定时钟，便于断言 ts */
    private fun newLog(): SessionLog = SessionLog(file) { now }

    private fun appendTurn(log: SessionLog, turn: Int, user: String, reply: String) {
        log.append(TurnStart(turn))
        log.append(UserMessage(turn, text = user))
        log.append(AssistantMessage(turn, text = reply))
        log.append(TurnEnd(turn, reason = TurnEndReason.COMPLETED))
    }

    // ═══════════════════ 基本写入与回放 ═══════════════════

    @Test
    fun `append 分配递增 seq 且 ts 取自注入时钟`() {
        val log = newLog()
        now = 111L
        val a = log.append(UserMessage(1, text = "你好"))
        now = 222L
        val b = log.append(AssistantMessage(1, text = "你好呀"))

        assertEquals(1L, a.seq)
        assertEquals(2L, b.seq)
        assertEquals(111L, a.ts)
        assertEquals(222L, b.ts)
        assertEquals(2L, log.lastSeq())
    }

    @Test
    fun `回放顺序等于写入顺序`() {
        val log = newLog()
        appendTurn(log, 1, "甲", "壹")
        appendTurn(log, 2, "乙", "贰")

        val events = log.read().map { it.event }
        assertEquals(8, events.size)
        assertTrue(events[0] is TurnStart)
        assertTrue(events[1] is UserMessage)
        assertEquals("甲", (events[1] as UserMessage).text)
        assertEquals("贰", (events[6] as AssistantMessage).text)
    }

    @Test
    fun `read from 只返回指定 seq 之后的记录`() {
        val log = newLog()
        appendTurn(log, 1, "甲", "壹")
        appendTurn(log, 2, "乙", "贰")

        val tail = log.read(from = 5L)
        assertEquals(listOf(5L, 6L, 7L, 8L), tail.map { it.seq })
    }

    /**
     * 模拟重启：新建一个 [SessionLog] 指向同一个文件，seq 必须接着分配。
     *
     * 这是"追加式日志"最容易出错的地方 —— 新实例若从 0 开始分配，
     * 会写出**重复 seq**，而重复 seq 会让压缩的 shadowedSeqs 指错事件。
     */
    @Test
    fun `新实例接续已有文件的 seq 不重复`() {
        val first = newLog()
        appendTurn(first, 1, "甲", "壹")

        val reopened = newLog()
        assertEquals(4L, reopened.lastSeq())
        val next = reopened.append(UserMessage(2, text = "乙"))
        assertEquals(5L, next.seq)
        assertEquals(5, reopened.read().size)
    }

    @Test
    fun `clear 后 seq 从 1 重新开始`() {
        val log = newLog()
        appendTurn(log, 1, "甲", "壹")
        log.clear()

        assertEquals(0L, log.lastSeq())
        assertTrue(log.read().isEmpty())
        assertEquals(1L, log.append(UserMessage(1, text = "重来")).seq)
    }

    @Test
    fun `父目录不存在时 append 自动创建`() {
        val nested = File(File(dir, "a"), "b/s.jsonl")
        val log = SessionLog(nested) { now }
        log.append(UserMessage(1, text = "x"))
        assertTrue(nested.exists())
    }

    // ═══════════════════ 鲁棒性 ═══════════════════

    /** 验收标准 3：人为写坏最后一行 JSON → 其余事件照常回放，只丢最后一条 */
    @Test
    fun `写坏最后一行只丢那一条`() {
        val log = newLog()
        appendTurn(log, 1, "甲", "壹")
        file.appendText("{\"v\":1,\"seq\":5,\"type\":\"user\",\"turn\":2,\"te") // 半截写入

        val reopened = newLog()
        assertEquals(4, reopened.read().size)
        assertEquals(4L, reopened.lastSeq())
        // 关键：seq 不能跳号（否则后续记录会与"曾经写过但损坏的 seq"冲突）
        assertEquals(5L, reopened.append(UserMessage(2, text = "乙")).seq)
    }

    @Test
    fun `缺失版本号或版本过新时跳过该行`() {
        val log = newLog()
        file.appendText("{\"seq\":1,\"type\":\"user\",\"turn\":1,\"text\":\"无版本\"}\n")
        file.appendText("{\"v\":99,\"seq\":2,\"type\":\"user\",\"turn\":1,\"text\":\"未来版本\"}\n")
        log.append(UserMessage(1, text = "正常"))

        val events = log.read()
        assertEquals(1, events.size)
        assertEquals("正常", (events[0].event as UserMessage).text)
    }

    @Test
    fun `未知事件类型跳过`() {
        val log = newLog()
        file.appendText("{\"v\":1,\"seq\":1,\"ts\":1,\"type\":\"hologram\",\"turn\":1}\n")
        log.append(UserMessage(1, text = "正常"))

        assertEquals(1, log.read().size)
    }

    /** 必填字段缺失 = 这一行不可用，而不是"用默认值凑一条假事件" */
    @Test
    fun `缺必填字段的行作废`() {
        val log = newLog()
        // turn_end 缺 reason
        file.appendText("{\"v\":1,\"seq\":1,\"ts\":1,\"type\":\"turn_end\",\"turn\":1}\n")
        // user 缺 turn
        file.appendText("{\"v\":1,\"seq\":2,\"ts\":1,\"type\":\"user\",\"text\":\"没轮次\"}\n")
        log.append(UserMessage(1, text = "正常"))

        assertEquals(1, log.read().size)
    }

    /**
     * 用量字段缺失 = **服务端没给**，绝不能兜成 0。
     *
     * 这是"面板显示 0 token"这类**错误信息**的防线：0 是一个看起来像事实的数，
     * 用户会拿它当账单读；`null` 至少诚实。
     */
    @Test
    fun `缺少用量字段的行解出 null 而不是 0`() {
        val log = newLog()
        file.appendText("{\"v\":1,\"seq\":1,\"ts\":1,\"type\":\"turn_end\",\"turn\":1,\"reason\":\"completed\"}\n")
        val end = log.read().single().event as TurnEnd
        assertEquals(null, end.promptTokens)
        assertEquals(null, end.completionTokens)
        assertEquals(null, end.modelCalls)
    }

    @Test
    fun `空文件与不存在的文件都读出空`() {
        val empty = newLog()
        assertTrue(empty.read().isEmpty())
        assertTrue(empty.isEmpty())
        assertEquals(0L, empty.lastSeq())

        val missing = SessionLog(File(dir, "不存在.jsonl")) { now }
        assertTrue(missing.read().isEmpty())
        assertTrue(missing.isEmpty())
    }

    // ═══════════════════ 编解码往返 ═══════════════════

    /**
     * 全部事件类型编解码往返一致。
     *
     * 这条是**防"加字段忘改编解码"** 的锁：新增事件类型或给已有类型加字段后，
     * 如果 encode/decode 漏了一处，这里会立刻失败。
     */
    @Test
    fun `全部事件类型编解码往返一致`() {
        val samples: List<SessionEvent> = listOf(
            TurnStart(turn = 1, source = MessageSource.VOICE),
            // 用量三字段一并往返（null 不落字段，所以这里必须给满，否则漏改 encode/decode 不会被发现）
            TurnEnd(
                turn = 1,
                reason = TurnEndReason.BUDGET_EXHAUSTED,
                detail = "轮次用尽",
                promptTokens = 1234,
                completionTokens = 56,
                modelCalls = 3,
            ),
            UserMessage(turn = 2, text = "你好", source = MessageSource.IMAGE),
            AssistantAttempt(turn = 2, outcome = AttemptOutcome.OVERFLOW, detail = "400"),
            AssistantMessage(turn = 2, text = "回复"),
            ToolCall(turn = 3, step = 1, callId = "call_1", name = "get_weather", args = "{\"city\":\"北京\"}"),
            ToolResult(turn = 3, step = 1, callId = "call_1", name = "get_weather", content = "晴", truncated = true),
            ContextInject(turn = 3, origin = "knowledge", content = "《手册》第1块：…", truncated = true),
            CompactionStart(turn = null, trigger = "MANUAL"),
            CompactionSummary(turn = 4, shadowedSeqs = listOf(1L, 2L, 7L), text = "[更早对话摘要]\n· 要点", turnsCompressed = 2),
            CompactionEnd(turn = 4, error = "boom"),
        )

        val log = newLog()
        samples.forEach { log.append(it) }

        val decoded = log.read().map { it.event }
        assertEquals(samples.size, decoded.size)
        samples.forEachIndexed { i, expected ->
            assertEquals("第 $i 个事件往返不一致", expected, decoded[i])
        }
    }

    @Test
    fun `落盘每行都自带版本号（单行自洽）`() {
        val log = newLog()
        appendTurn(log, 1, "甲", "壹")

        val lines = file.readText().trim().lineSequence().filter { it.isNotBlank() }.toList()
        assertEquals(4, lines.size)
        lines.forEach { line ->
            assertTrue("每行都应带 v 字段：$line", line.contains("\"v\":${SessionLog.FORMAT_VERSION}"))
            // 每行都能**独立**解析成一条事件（不依赖上下文）
            assertNotNull(log.decode(line))
        }
    }
}
