package com.rokidlab.phone.ai

import com.rokidlab.phone.util.HttpStatusException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AI 链路失败的「说人话」映射回归测试（[OpenAiService.aiFailureHint]）。
 *
 * 背景（2026-09-15 真机事故）：流式请求遇非 2xx 时错误被静默吞成空轮，用户看到的是
 * 「抱歉，我暂时无法处理这个问题」—— 分不清是密钥失效、余额不足、模型名写错还是网络抖动，
 * 日志里也没有任何线索。修复分两层：① [com.rokidlab.phone.util.HttpClient.postSse]
 * 对非 2xx 显式抛 [HttpStatusException]；② 本函数把异常翻成可行动的提示。
 *
 * 本测试锁住第 ② 层的映射，防止以后有人把 4xx 又合并回「服务不可用」这类无信息量文案。
 */
class AiFailureHintTest {

    @Test
    fun `密钥问题提示去检查密钥`() {
        listOf(401, 403).forEach { code ->
            val hint = OpenAiService.aiFailureHint(HttpStatusException(code, "{\"error\":\"invalid key\"}"))
            assertTrue("HTTP $code 应提示检查密钥，实际：$hint", hint.contains("密钥"))
        }
    }

    @Test
    fun `余额不足提示充值`() {
        val hint = OpenAiService.aiFailureHint(
            HttpStatusException(402, "{\"error\":{\"message\":\"Insufficient Balance\"}}"),
        )
        assertTrue("HTTP 402 应提示充值，实际：$hint", hint.contains("余额"))
    }

    @Test
    fun `地址错误与请求被拒给出可行动方向`() {
        assertTrue(OpenAiService.aiFailureHint(HttpStatusException(404, "")).contains("地址"))
        // 400 且正文为空（无任何线索）：只能指向日志，不能瞎猜成配置问题
        val blank = OpenAiService.aiFailureHint(HttpStatusException(400, ""))
        assertTrue("400 无正文时应指向日志，实际：$blank", blank.contains("日志"))
    }

    @Test
    fun `正文能定位原因时优先信正文`() {
        // 模型名不存在（DeepSeek 原文 "Model Not Exist"）
        val model = OpenAiService.aiFailureHint(
            HttpStatusException(400, "{\"error\":{\"message\":\"Model Not Exist\"}}"),
        )
        assertTrue("应提示模型名，实际：$model", model.contains("模型名"))

        // 上下文超长
        val tooLong = OpenAiService.aiFailureHint(
            HttpStatusException(400, "{\"error\":{\"message\":\"This model's maximum context length is 65536 tokens\"}}"),
        )
        assertTrue("应提示内容过长，实际：$tooLong", tooLong.contains("太长"))

        // 余额不足用 400 返回时也不能说成模型名
        val balance = OpenAiService.aiFailureHint(
            HttpStatusException(400, "{\"error\":{\"message\":\"Insufficient Balance\"}}"),
        )
        assertTrue("应提示余额，实际：$balance", balance.contains("余额"))
    }

    @Test
    fun `本机工具声明不合法不得误导为配置问题`() {
        // 2026-09-15 真机事故原文：update_plan 的 steps 被写成 JSON 数组，服务端整体拒绝。
        // 这类错误的正确处置是「导出日志反馈」，而不是让用户去改密钥/模型名/地址。
        val hint = OpenAiService.aiFailureHint(
            HttpStatusException(
                400,
                "{\"error\":{\"message\":\"Invalid schema for function 'update_plan': " +
                    "[{\\\"type\\\":\\\"object\\\"}] is not of types \\\"boolean\\\", \\\"object\\\"\"}}",
            ),
        )
        assertTrue("应指出工具声明不合法，实际：$hint", hint.contains("工具声明"))
        assertFalse("不得误导用户去改模型名，实际：$hint", hint.contains("模型名"))
        assertFalse("不得误导用户去改密钥，实际：$hint", hint.contains("密钥"))
        assertFalse("不得误导用户去改地址，实际：$hint", hint.contains("地址"))
    }

    @Test
    fun `限流与 5xx 都带状态码且不误导为配置问题`() {
        assertTrue(OpenAiService.aiFailureHint(HttpStatusException(429, "")).contains("频繁"))
        val server = OpenAiService.aiFailureHint(HttpStatusException(503, ""))
        assertTrue("5xx 应带状态码便于排查，实际：$server", server.contains("503"))
        assertFalse("5xx 不该误导用户去改配置", server.contains("设置"))
    }

    @Test
    fun `网络类异常仍回落通用文案`() {
        assertEquals(
            "抱歉，AI 服务暂时不可用。",
            OpenAiService.aiFailureHint(java.net.SocketTimeoutException("timeout")),
        )
    }

    @Test
    fun `提示面向眼镜朗读故不含换行且长度有界`() {
        // 眼镜 TTS 直接朗读该文案：出现换行会被念成停顿错乱，过长则播报冗长。
        // 上限取「一句话」的宽松界（45 字），既能拦住误粘贴长文案，又不会因正常措辞微调而误报。
        listOf(400, 401, 402, 404, 429, 500).forEach { code ->
            val hint = OpenAiService.aiFailureHint(HttpStatusException(code, "x".repeat(2000)))
            assertFalse("HTTP $code 的提示不应含换行：$hint", hint.contains("\n"))
            assertTrue("HTTP $code 的提示过长（${hint.length}）：$hint", hint.length <= 45)
        }
    }
}
