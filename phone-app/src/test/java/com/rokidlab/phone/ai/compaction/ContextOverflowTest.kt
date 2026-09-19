package com.rokidlab.phone.ai.compaction

import com.rokidlab.phone.util.HttpStatusException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ContextOverflow] 单测 —— 这是**唯一**决定"要不要把整轮重发一次"的判定，误判有代价：
 *  - 误判为真：白烧一次请求（压缩对输出上限毫无帮助），用户多等十几秒；
 *  - 误判为假：退回改造前的"请换个短一点的问题"，也就是少了一次自动恢复。
 *
 * 所以这里逐条钉住"哪些算、哪些不算"，特别是**裸 max_tokens 不算**：
 * 那是"输出预算不够"，压缩历史解决不了任何问题。
 */
class ContextOverflowTest {

    private fun http(code: Int, body: String) = HttpStatusException(code, body)

    @Test
    fun `A1 DeepSeek 的输入超长原文必须命中`() {
        // 真机/文档原文：「This model's maximum context length is 65536 tokens.
        // However, you requested 70000 tokens (69000 in the messages, 1000 in the completion).
        // Please reduce the length of the messages or completion.」
        val e = http(
            400,
            "{\"error\":{\"message\":\"This model's maximum context length is 65536 tokens. " +
                "However, you requested 70000 tokens (69000 in the messages, 1000 in the completion). " +
                "Please reduce the length of the messages or completion.\",\"type\":\"invalid_request_error\"}}",
        )
        assertTrue(ContextOverflow.isOverflow(e))
        assertNotNull(ContextOverflow.matchedMarker(e))
    }

    @Test
    fun `A2 各家服务端的输入超长措辞都命中`() {
        listOf(
            "This model's maximum context length is 8192 tokens",
            "context_length_exceeded",
            "Your input exceeds the context window of this model",
            "Please reduce the length of the messages",
            "too many tokens in the request",
            "the input is too long for this model",
            "prompt is too long: 250000 tokens > 200000 maximum",
        ).forEach { body ->
            assertTrue("应命中：$body", ContextOverflow.isOverflow(http(400, body)))
        }
    }

    @Test
    fun `A3 裸 max_tokens 是输出预算问题不算输入超长`() {
        // OpenAI 原文：「max_tokens is too large: 8192. This model supports at most 4096 completion tokens」
        // 压缩历史对这条毫无帮助 —— 重试只会白烧一次请求，用户多等一轮。
        listOf(
            "max_tokens is too large: 8192. This model supports at most 4096 completion tokens",
            "{\"error\":{\"message\":\"Invalid max_tokens value\"}}",
        ).forEach { body ->
            assertFalse("不该命中：$body", ContextOverflow.isOverflow(http(400, body)))
        }
    }

    @Test
    fun `A4 其它 400 与网络异常都不算`() {
        // 模型名不存在
        assertFalse(ContextOverflow.isOverflow(http(400, "{\"error\":\"Model Not Exist\"}")))
        // 工具 schema 非法（本机自己的 bug，压缩无关）
        assertFalse(
            ContextOverflow.isOverflow(
                http(400, "Invalid schema for function 'update_plan': [{\"type\":\"object\"}]"),
            ),
        )
        // 鉴权 / 余额 / 限流
        assertFalse(ContextOverflow.isOverflow(http(401, "invalid api key")))
        assertFalse(ContextOverflow.isOverflow(http(402, "Insufficient Balance")))
        assertFalse(ContextOverflow.isOverflow(http(429, "rate limit")))
        // 5xx 是服务端问题，重试由 chatTurnStream 的退避负责
        assertFalse(ContextOverflow.isOverflow(http(500, "context length")))
        // 非 HTTP 异常（超时/断网）：压缩解决不了，重试也没有意义
        assertFalse(ContextOverflow.isOverflow(java.net.SocketTimeoutException("timeout")))
        assertFalse(ContextOverflow.isOverflow(IllegalStateException("boom")))
    }

    @Test
    fun `A5 未命中时 matchedMarker 返回 null 便于日志区分`() {
        assertNull(ContextOverflow.matchedMarker(http(400, "Model Not Exist")))
        assertNull(ContextOverflow.matchedMarker(java.net.SocketTimeoutException("t")))
        assertEqualsMarkerForKnownBody()
    }

    private fun assertEqualsMarkerForKnownBody() {
        val m = ContextOverflow.matchedMarker(http(400, "maximum context length is 65536"))
        assertTrue("应报出具体命中的关键词，实际 $m", m == "maximum context length")
    }
}
