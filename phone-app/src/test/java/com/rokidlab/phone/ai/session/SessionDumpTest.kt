package com.rokidlab.phone.ai.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SessionDump]（事件流 → 给模型看的文本）单测。
 *
 * 这一层是**会话查询工具族的展示口径**，纯 JVM 可测，所以把最容易写错又最不容易发现的
 * 几件事钉在这里：
 *  1. 顺序必须＝发生顺序（模型靠它理解"先做了什么、再做了什么"）；
 *  2. 超长时**必须明确给出续读位置**（少了它，模型会以为"历史就这么多"）；
 *  3. 工具调用链要能标出**调了没回来**的 —— 那是事件流带来的新可观测性；
 *  4. 压缩 / 作废这类"元事件"也要出现在叙述里（否则用户会以为历史凭空变短了）。
 */
class SessionDumpTest {

    private fun records(vararg events: SessionEvent): List<SessionRecord> {
        var seq = 0L
        return events.map { SessionRecord(seq = ++seq, ts = 1_000L + seq, event = it) }
    }

    @Test
    fun `按发生顺序渲染一轮的完整过程`() {
        val dump = SessionDump.dumpSession(
            records(
                TurnStart(1, source = MessageSource.VOICE),
                UserMessage(1, text = "明天天气"),
                ToolCall(1, callId = "c1", name = "get_weather", args = "{\"city\":\"西安\"}"),
                ToolResult(1, callId = "c1", name = "get_weather", content = "晴 31℃"),
                AssistantMessage(1, text = "明天晴，最高 31 度"),
                TurnEnd(1, reason = TurnEndReason.COMPLETED),
            )
        )
        assertTrue(dump.contains("用户：明天天气"))
        assertTrue(dump.contains("调用工具 get_weather"))
        assertTrue(dump.contains("工具返回 get_weather → 晴 31℃"))
        assertTrue(dump.contains("乐奇：明天晴"))

        // 顺序靠 seq 断言（只看 contains 会漏掉"顺序错了但内容都在"这种错）
        val lines = dump.lines()
        assertEquals("#1", lines[0].substringBefore(' '))
        assertEquals("#2", lines[1].substringBefore(' '))
        assertEquals("#6", lines.last().substringBefore(' '))
    }

    @Test
    fun `超出字符上限时给出可续读的位置`() {
        val many = (1..40).map { AssistantMessage(1, text = "x".repeat(200)) }.toTypedArray()
        val dump = SessionDump.dumpSession(records(*many), maxChars = 600)

        assertTrue("必须告诉模型怎么接着读：$dump", dump.contains("接着读请传 fromSeq="))
        assertTrue("应明显小于上限（截断而不是硬塞）", dump.length < 900)
    }

    @Test
    fun `fromSeq 从指定位置开始读`() {
        val all = records(
            UserMessage(1, text = "第一句"),
            AssistantMessage(1, text = "第一答"),
            UserMessage(2, text = "第二句"),
            AssistantMessage(2, text = "第二答"),
        )
        val dump = SessionDump.dumpSession(all, fromSeq = 3L)
        assertTrue(dump.contains("第二句"))
        assertTrue("第一条不该出现", !dump.contains("第一句"))
    }

    @Test
    fun `事件条数超过 limit 时说明还有多少`() {
        val all = records(*((1..10).map { UserMessage(1, text = "q$it") }.toTypedArray()))
        val dump = SessionDump.dumpSession(all, limit = 3)
        assertTrue(dump.contains("本次只显示了 3 条"))
        assertTrue(dump.contains("接着读请传 fromSeq="))
    }

    @Test
    fun `压缩与作废事件也要出现在叙述里`() {
        val dump = SessionDump.dumpSession(
            records(
                UserMessage(1, text = "很早的问题"),
                CompactionSummary(turn = null, shadowedSeqs = listOf(1L), text = "[更早对话摘要]\n· 用户问\"很早\""),
                VisibilityCut(turn = null, seqs = listOf(1L), reason = "drop_last_turn"),
            )
        )
        assertTrue("压缩要让模型知道历史被折过了", dump.contains("压缩：把 1 条早期事件折成摘要"))
        assertTrue("作废也要说清（否则历史凭空变短）", dump.contains("作废 1 条（drop_last_turn）"))
    }

    @Test
    fun `工具链标出调了没回来的调用并带本轮统计`() {
        val dump = SessionDump.dumpTrace(
            records(
                TurnStart(3),
                ToolCall(3, callId = "c1", name = "get_current_time", args = "{}"),
                ToolResult(3, callId = "c1", name = "get_current_time", content = "10:00"),
                ToolCall(3, callId = "c2", name = "save_code_file", args = "{\"file\":\"a.ink\"}"),
                TurnEnd(
                    turn = 3,
                    reason = TurnEndReason.COMPLETED,
                    promptTokens = 1200,
                    completionTokens = 40,
                    modelCalls = 3,
                ),
            ),
            3,
        )
        assertTrue(dump.contains("共 2 次调用"))
        assertTrue(dump.contains("10:00"))
        assertTrue("调了没回来必须显式标出：$dump", dump.contains("**没有返回**"))
        assertTrue(dump.contains("3 次模型调用"))
        assertTrue(dump.contains("输入 1200 token"))
        assertTrue(dump.contains("结束原因 completed"))
    }

    @Test
    fun `最近一个调过工具的轮次`() {
        assertEquals(
            4,
            SessionDump.latestToolTurn(
                records(
                    ToolCall(1, callId = "a", name = "x", args = "{}"),
                    UserMessage(2, text = "只说话没调工具"),
                    AssistantMessage(2, text = "嗯"),
                    ToolCall(4, callId = "b", name = "y", args = "{}"),
                )
            ),
        )
        assertEquals(null, SessionDump.latestToolTurn(records(UserMessage(1, text = "没调过工具"))))
    }

    @Test
    fun `该轮没调工具时如实说明`() {
        val dump = SessionDump.dumpTrace(records(TurnStart(7), UserMessage(7, text = "纯聊天")), 7)
        assertTrue(dump.contains("第 7 轮没有调用任何工具"))
    }
}
