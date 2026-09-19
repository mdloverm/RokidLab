package com.rokidlab.phone.ai.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SessionTrace]（事件流 → 界面用的结构化轨迹）单测。
 *
 * 轨迹视图是"模型看到的每一字节都可回溯"这句话对**用户**成立的地方，
 * 所以这里钉住的是它能不能被信任地读：
 *  1. 分类正确（用户扫一眼要能分出"它自己查的"和"喂给它的"）；
 *  2. 原文在详情里（标题截断只是摘要，查到细节不能比事件流还少）；
 *  3. 规模统计不虚报；
 *  4. 只保留最近 N 条（长会话不能从第一天开始列）。
 */
class SessionTraceTest {

    private fun records(vararg events: SessionEvent): List<SessionRecord> {
        var seq = 0L
        return events.map { SessionRecord(seq = ++seq, ts = 1_000L + seq, event = it) }
    }

    @Test
    fun `按来源分类并保持时间正序`() {
        val items = SessionTrace.items(
            records(
                TurnStart(1),
                UserMessage(1, text = "帮我查天气"),
                ContextInject(1, origin = "memory", content = "1. 喜欢周杰伦"),
                ToolCall(1, callId = "c1", name = "get_weather", args = "{\"city\":\"西安\"}"),
                ToolResult(1, callId = "c1", name = "get_weather", content = "晴 31℃"),
                AssistantMessage(1, text = "明天晴"),
                TurnEnd(1, reason = TurnEndReason.COMPLETED),
            )
        )
        assertEquals(
            listOf(
                SessionTrace.Category.LIFECYCLE, // TurnStart
                SessionTrace.Category.MESSAGE,
                SessionTrace.Category.INJECT,
                SessionTrace.Category.TOOL,
                SessionTrace.Category.TOOL,
                SessionTrace.Category.MESSAGE,
                SessionTrace.Category.LIFECYCLE, // TurnEnd
            ),
            items.map { it.category },
        )
        // 时间正序 = seq 递增
        assertEquals(items.map { it.seq }, items.map { it.seq }.sorted())
    }

    @Test
    fun `注入条目标出来源与字数 详情是原文`() {
        val text = "1. 用户喜欢周杰伦\n2. 用户是学生"
        val item = SessionTrace.items(records(ContextInject(1, origin = "memory", content = text))).single()
        assertEquals(SessionTrace.Category.INJECT, item.category)
        assertTrue("标题要能看出注入了什么来源：${item.title}", item.title.contains("memory"))
        assertTrue("标题要给出规模：${item.title}", item.title.contains("${text.length} 字"))
        assertEquals("详情必须是原文（不能比事件流还少）", text, item.detail)
    }

    @Test
    fun `工具调用的参数与返回原文都能展开`() {
        val items = SessionTrace.items(
            records(
                ToolCall(1, callId = "c1", name = "get_weather", args = "{\"city\":\"西安\"}"),
                ToolResult(1, callId = "c1", name = "get_weather", content = "晴 31℃", truncated = true),
            )
        )
        assertEquals("调用 get_weather", items[0].title)
        assertEquals("{\"city\":\"西安\"}", items[0].detail)
        assertEquals("返回 get_weather（原文已截断）", items[1].title)
        assertEquals("晴 31℃", items[1].detail)
    }

    @Test
    fun `无参数的工具调用没有可展开的详情`() {
        val item = SessionTrace.items(records(ToolCall(1, callId = "c", name = "get_current_time", args = "{}"))).single()
        assertNull("空参数不值得展开", item.detail)
    }

    @Test
    fun `压缩与作废各自成类且说明影响范围`() {
        val items = SessionTrace.items(
            records(
                CompactionSummary(turn = null, shadowedSeqs = listOf(1L, 2L, 3L, 4L), text = "[更早对话摘要]\n· 要点"),
                VisibilityCut(turn = null, seqs = listOf(5L, 6L), reason = "drop_last_turn"),
            )
        )
        assertEquals(SessionTrace.Category.COMPACTION, items[0].category)
        assertTrue(items[0].title.contains("4 条"))
        assertEquals("[更早对话摘要]\n· 要点", items[0].detail)

        assertEquals(SessionTrace.Category.CUT, items[1].category)
        assertTrue("作废要说清影响：${items[1].title}", items[1].title.contains("2 条"))
        assertTrue("也要说清原文还在：${items[1].title}", items[1].title.contains("原文仍在"))
    }

    @Test
    fun `结束条目带出本轮代价`() {
        val items = SessionTrace.items(
            listOf(
                SessionRecord(1, 1_000L, TurnStart(1)),
                SessionRecord(
                    2, 6_000L,
                    TurnEnd(
                        turn = 1,
                        reason = TurnEndReason.COMPLETED,
                        promptTokens = 100,
                        completionTokens = 5,
                        modelCalls = 2,
                    ),
                ),
            )
        )
        val detail = items[1].detail.orEmpty()
        assertTrue("要摊开调用次数：$detail", detail.contains("模型调用 2 次"))
        assertTrue("要摊开 token：$detail", detail.contains("输入 100 / 输出 5 token"))
        assertTrue("耗时由事件流现算：$detail", detail.contains("耗时 5.0 秒"))
    }

    @Test
    fun `只保留最后 N 条`() {
        val many = (1..10).map { UserMessage(1, text = "第 $it 句") }.toTypedArray()
        val all = records(*many)
        val items = SessionTrace.items(all, limit = 3)
        assertEquals(3, items.size)
        assertEquals("应保留最近的三条", listOf(8L, 9L, 10L), items.map { it.seq })
    }

    @Test
    fun `超长详情被截断但标题仍可读`() {
        val huge = "x".repeat(9000)
        val item = SessionTrace.items(records(AssistantMessage(1, text = huge))).single()
        assertTrue("详情必须截断（否则一条超长返回会卡住界面）", item.detail!!.length < 9000)
        assertTrue(item.detail!!.length <= 4000 + 8)
        assertTrue("标题本身也是截断的摘要：${item.title}", item.title.length < 100)
    }

    @Test
    fun `摘要统计轮数 工具次数与注入次数`() {
        val s = SessionTrace.summary(
            records(
                TurnStart(1),
                ContextInject(1, origin = "memory", content = "m"),
                ToolCall(1, callId = "a", name = "x", args = "{}"),
                ToolCall(1, callId = "b", name = "y", args = "{}"),
                TurnEnd(1, reason = TurnEndReason.COMPLETED),
                TurnStart(2),
                ContextInject(2, origin = "knowledge", content = "k"),
                CompactionSummary(turn = null, shadowedSeqs = listOf(1L), text = "摘要"),
                TurnEnd(2, reason = TurnEndReason.COMPLETED),
            )
        )
        assertEquals(2, s.turns)
        assertEquals(2, s.toolCalls)
        assertEquals(2, s.injects)
        assertEquals(1, s.compactions)
    }

    @Test
    fun `空事件流得到空轨迹`() {
        assertTrue(SessionTrace.items(emptyList()).isEmpty())
    }
}
