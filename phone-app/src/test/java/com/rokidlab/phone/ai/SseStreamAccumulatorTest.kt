package com.rokidlab.phone.ai

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
}
