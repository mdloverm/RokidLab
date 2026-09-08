package com.rokidlab.phone.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ToolRegistry.truncateToolOutput（工具输出回填截断）纯函数单测。
 */
class TruncateToolOutputTest {

    @Test
    fun `未超长时原样返回`() {
        val raw = "正常工具返回结果"
        val result = truncateToolOutput(raw)
        assertEquals(raw, result)
    }

    @Test
    fun `恰好等于上限时原样返回`() {
        val raw = "x".repeat(4000)
        assertEquals(raw, truncateToolOutput(raw))
    }

    @Test
    fun `超长时保留开头预览并附截断说明`() {
        val raw = "a".repeat(8000)
        val result = truncateToolOutput(raw)
        val expected = "a".repeat(1500) +
            "\n…（工具输出过长已截断，仅保留开头 1500 字符，原始 8000 字符）"
        assertEquals(expected, result)
        assertTrue(result.length < raw.length)
    }

    @Test
    fun `自定义参数生效`() {
        val raw = "b".repeat(100)
        val result = truncateToolOutput(raw, maxChars = 50, previewChars = 20)
        val expected = "b".repeat(20) +
            "\n…（工具输出过长已截断，仅保留开头 20 字符，原始 100 字符）"
        assertEquals(expected, result)
    }

    @Test
    fun `空串不截断`() {
        assertEquals("", truncateToolOutput(""))
    }

    @Test
    fun `previewChars 不超过 maxChars 时仍可截断`() {
        val raw = "c".repeat(100)
        val result = truncateToolOutput(raw, maxChars = 10, previewChars = 10)
        val expected = "c".repeat(10) +
            "\n…（工具输出过长已截断，仅保留开头 10 字符，原始 100 字符）"
        assertEquals(expected, result)
    }
}
