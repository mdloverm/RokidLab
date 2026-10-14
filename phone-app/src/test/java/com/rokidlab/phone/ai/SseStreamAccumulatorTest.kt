package com.rokidlab.phone.ai

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * OpenAiService.SseStreamAccumulator（SSE 流式累积状态机）单测。
 *
 * 锁定历史上出过错的行为：content 增量拼接、tool_calls 按 index 跨 chunk
 * 分片拼接（name/arguments 增量式下发）、[DONE] 终止、非法行跳过。
 */
class SseStreamAccumulatorTest {

    // ── content 纯文本流 ──

    @Test
    fun `content 增量逐片拼接`() {
        val acc = SseStreamAccumulator()
        acc.onSseLine("data: {\"choices\":[{\"delta\":{\"content\":\"你好\"}}]}")
        acc.onSseLine("data: {\"choices\":[{\"delta\":{\"content\":\"，乐奇\"}}]}")
        val turn = acc.build()
        assertEquals("你好，乐奇", turn.content)
        assertTrue(turn.toolCalls.isEmpty())
    }

    @Test
    fun `onDelta 每次增量回调`() {
        val deltas = mutableListOf<String>()
        val acc = SseStreamAccumulator(onDelta = { deltas.add(it) })
        acc.onSseLine("data: {\"choices\":[{\"delta\":{\"content\":\"A\"}}]}")
        acc.onSseLine("data: {\"choices\":[{\"delta\":{\"content\":\"B\"}}]}")
        assertEquals(listOf("A", "B"), deltas)
    }

    @Test
    fun `DONE 终止后不再解析`() {
        val acc = SseStreamAccumulator()
        acc.onSseLine("data: {\"choices\":[{\"delta\":{\"content\":\"前半\"}}]}")
        val cont = acc.onSseLine("data: [DONE]")
        assertFalse("收到 [DONE] 后应停止读取", cont)
        assertTrue(acc.hasFinished())
        // 终止后喂入的新行被忽略
        acc.onSseLine("data: {\"choices\":[{\"delta\":{\"content\":\"不该进来\"}}]}")
        assertEquals("前半", acc.build().content)
    }

    @Test
    fun `非 data 行与非法 JSON 跳过不崩溃`() {
        val acc = SseStreamAccumulator()
        assertEquals(true, acc.onSseLine(": keep-alive"))
        assertEquals(true, acc.onSseLine(""))
        assertEquals(true, acc.onSseLine("data: not-json{{{"))
        assertEquals(true, acc.onSseLine("data: {\"choices\":[]}"))
        assertEquals(true, acc.onSseLine("data: {\"no\":\"choices\"}"))
        assertNull(acc.build().content)
        assertTrue(acc.build().toolCalls.isEmpty())
    }

    // ── tool_calls 增量拼接 ──

    @Test
    fun `tool_calls name arguments 跨 chunk 拼接`() {
        val acc = SseStreamAccumulator()
        // chunk1：只有 id + name 前缀
        acc.onSseLine(
            "data: {\"choices\":[{\"delta\":{\"tool_calls\":" +
                "[{\"index\":0,\"id\":\"call_1\",\"function\":{\"name\":\"get_current_\",\"arguments\":\"{\\\"scope\\\":\\\"time\"}}]}}]}",
        )
        // chunk2：name 后缀 + arguments 增量
        acc.onSseLine(
            "data: {\"choices\":[{\"delta\":{\"tool_calls\":" +
                "[{\"index\":0,\"function\":{\"name\":\"time\",\"arguments\":\"\\\"}\"}}]}}]}",
        )
        val turn = acc.build()
        assertNull(turn.content)
        assertEquals(1, turn.toolCalls.size)
        val tc = turn.toolCalls[0]
        assertEquals("call_1", tc.id)
        assertEquals("get_current_time", tc.name)
        assertEquals("{\"scope\":\"time\"}", tc.arguments)
    }

    @Test
    fun `多个 tool_call 按 index 升序合并`() {
        val acc = SseStreamAccumulator()
        // index 1 先到，index 0 后到 → build 后按 index 升序
        acc.onSseLine(
            "data: {\"choices\":[{\"delta\":{\"tool_calls\":" +
                "[{\"index\":1,\"id\":\"call_b\",\"function\":{\"name\":\"tool_b\",\"arguments\":\"{}\"}}]}}]}",
        )
        acc.onSseLine(
            "data: {\"choices\":[{\"delta\":{\"tool_calls\":" +
                "[{\"index\":0,\"id\":\"call_a\",\"function\":{\"name\":\"tool_a\",\"arguments\":\"{}\"}}]}}]}",
        )
        val calls = acc.build().toolCalls
        assertEquals(listOf("tool_a", "tool_b"), calls.map { it.name })
        assertEquals(listOf("call_a", "call_b"), calls.map { it.id })
    }

    @Test
    fun `content 与 tool_calls 混流互不干扰`() {
        val acc = SseStreamAccumulator()
        acc.onSseLine("data: {\"choices\":[{\"delta\":{\"content\":\"我先查一下\"}}]}")
        acc.onSseLine(
            "data: {\"choices\":[{\"delta\":{\"tool_calls\":" +
                "[{\"index\":0,\"id\":\"c1\",\"function\":{\"name\":\"get_time\",\"arguments\":\"{}\"}}]}}]}",
        )
        val turn = acc.build()
        assertEquals("我先查一下", turn.content)
        assertEquals(1, turn.toolCalls.size)
    }

    // ── onToolArgs（工具参数流式进度）──

    /** 用 org.json 拼一条 tool_calls 的 SSE 行：手写转义极易出错，这里交给库 */
    private fun toolCallLine(
        index: Int,
        id: String? = null,
        name: String? = null,
        args: String? = null,
    ): String {
        val fn = JSONObject()
        if (name != null) fn.put("name", name)
        if (args != null) fn.put("arguments", args)
        val tc = JSONObject().put("index", index).put("function", fn)
        if (id != null) tc.put("id", id)
        val delta = JSONObject().put("tool_calls", JSONArray().put(tc))
        return "data: " + JSONObject()
            .put("choices", JSONArray().put(JSONObject().put("delta", delta)))
            .toString()
    }

    @Test
    fun `onToolArgs 报工具名与累计参数长度`() {
        // 上游拿「累计长度」直接展示("写到多大了")，因此契约是**累积**而不是本帧增量
        val events = mutableListOf<Pair<String, Int>>()
        val acc = SseStreamAccumulator(onToolArgs = { n, len -> events.add(n to len) })
        acc.onSseLine(toolCallLine(0, id = "c1", name = "save_code_file", args = "{\"file\":"))
        acc.onSseLine(toolCallLine(0, args = "\"app.js\","))
        assertEquals(listOf("save_code_file", "save_code_file"), events.map { it.first })
        assertEquals("{\"file\":".length, events[0].second)
        assertEquals("{\"file\":".length + "\"app.js\",".length, events[1].second)
    }

    @Test
    fun `工具名未到时不回调 onToolArgs`() {
        // name 与 arguments 可能分在不同 chunk（本帧只有 arguments）：上游要靠名字选文案，
        // 无名回调只会给它一个无从展示的数字
        val events = mutableListOf<Pair<String, Int>>()
        val acc = SseStreamAccumulator(onToolArgs = { n, len -> events.add(n to len) })
        acc.onSseLine(toolCallLine(0, args = "{\"a\":1}"))
        assertTrue("工具名未知时不该回调", events.isEmpty())
    }
}
