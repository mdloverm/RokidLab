package com.rokidlab.phone.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 导入文本编码判定单测（纯 JVM，无 Android 依赖）。
 *
 * 锁定的是 2026-09-20 那个真机 bug 的根因：知识库导入原先**无条件按 UTF-8 解码**，
 * 中文 Windows 的 `ANSI(GBK)` / `Unicode(UTF-16LE)` txt 会被整篇解成替换符，
 * 而文件名与字节数照旧正确 ⇒ 用户看到"导入成功"，却永远问不出里面的内容。
 *
 * 这里只断言**判定结果与还原能力**，不碰 SQLite（KnowledgeBase 的分块/检索另有其责）。
 */
class TextEncodingTest {

    private val zh = "键盘使用说明书。本产品支持蓝牙5.0连接，保修期为一年。"

    @Test
    fun `UTF-8 无 BOM 判为 UTF-8 且原文可还原`() {
        val bytes = zh.toByteArray(Charsets.UTF_8)
        val d = TextEncoding.decide(bytes)
        assertEquals("UTF-8", d.charset.name())
        assertEquals(0, d.bomBytes)
        assertEquals(zh, TextEncoding.decode(bytes))
    }

    @Test
    fun `UTF-8 带 BOM 跳过 3 字节且正文不含 BOM 字符`() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + zh.toByteArray(Charsets.UTF_8)
        val d = TextEncoding.decide(bytes)
        assertEquals("UTF-8", d.charset.name())
        assertEquals(3, d.bomBytes)
        val text = TextEncoding.decode(bytes)
        assertEquals(zh, text)
        assertFalse("BOM 不能被当成正文首字符（否则会被切进第一块并被检索到）", text.startsWith("\uFEFF"))
    }

    @Test
    fun `UTF-16LE 带 BOM 判为 UTF-16LE 且原文可还原`() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + zh.toByteArray(Charsets.UTF_16LE)
        val d = TextEncoding.decide(bytes)
        assertEquals("UTF-16LE", d.charset.name())
        assertEquals(2, d.bomBytes)
        assertEquals(zh, TextEncoding.decode(bytes))
    }

    @Test
    fun `UTF-16BE 带 BOM 判为 UTF-16BE 且原文可还原`() {
        val bytes = byteArrayOf(0xFE.toByte(), 0xFF.toByte()) + zh.toByteArray(Charsets.UTF_16BE)
        val d = TextEncoding.decide(bytes)
        assertEquals("UTF-16BE", d.charset.name())
        assertEquals(2, d.bomBytes)
        assertEquals(zh, TextEncoding.decode(bytes))
    }

    @Test
    fun `GBK 中文判为 GB 系且原文可还原`() {
        val bytes = zh.toByteArray(charset("GBK"))
        val d = TextEncoding.decide(bytes)
        assertTrue("应落到 GB18030/GBK，实际 ${d.charset.name()}", d.charset.name().contains("GB"))
        assertEquals("GBK 文本必须能原样还原，否则检索永远命中不了", zh, TextEncoding.decode(bytes))
    }

    @Test
    fun `回归 GBK 文本不再被解成替换符`() {
        // 改造前的行为：按 UTF-8 硬解 ⇒ 满篇 U+FFFD，且任何中文关键词都命中不了
        val naive = String(zh.toByteArray(charset("GBK")), Charsets.UTF_8)
        assertTrue("前提：旧行为确实会产生替换符", naive.contains('\uFFFD'))

        val fixed = TextEncoding.decode(zh.toByteArray(charset("GBK")))
        assertFalse("修好之后正文里不该再有替换符", fixed.contains('\uFFFD'))
        assertTrue("关键词必须能作为子串命中（检索侧靠的就是子串匹配）", fixed.contains("蓝牙"))
    }

    @Test
    fun `无 BOM 的 UTF-16LE 靠 NUL 密度识别而不是误判成 UTF-8`() {
        // 纯 ASCII 内容的 UTF-16LE 没有 BOM：NUL 是合法 UTF-8 单字节，
        // 若不做 NUL 密度判断，它会被"成功"解成隔字带空洞的乱码
        val text = "Hello World test 1234567890"
        val bytes = text.toByteArray(Charsets.UTF_16LE)
        val d = TextEncoding.decide(bytes)
        assertEquals("UTF-16LE", d.charset.name())
        assertEquals(text, TextEncoding.decode(bytes))
    }

    @Test
    fun `纯 ASCII 判为 UTF-8`() {
        val bytes = "hello world 12345".toByteArray(Charsets.US_ASCII)
        assertEquals("UTF-8", TextEncoding.decide(bytes).charset.name())
        assertEquals("hello world 12345", TextEncoding.decode(bytes))
    }

    @Test
    fun `空字节判为 UTF-8 且解出空串`() {
        val d = TextEncoding.decide(ByteArray(0))
        assertEquals("UTF-8", d.charset.name())
        assertEquals(0, d.bomBytes)
        assertEquals("", TextEncoding.decode(ByteArray(0)))
    }

    @Test
    fun `探测样本尾部正好切断一个多字节字符时仍判为 UTF-8`() {
        // 关键反向保护：若不容忍"样本尾部被切断"，正常的 UTF-8 文件会被误判成 GB，
        // 那是比不判定更糟的破坏（把本来好的文档弄成乱码）。
        // 构造：3 字节汉字重复填充，使第 PROBE_BYTES 字节正好落在字符中间。
        val unit = "中".toByteArray(Charsets.UTF_8) // 3 字节
        val count = TextEncoding.PROBE_BYTES / unit.size + 2
        val bytes = ByteArray(count * unit.size) { unit[it % unit.size] }
        assertTrue("文件必须比探测样本长，否则根本走不到切断场景", bytes.size > TextEncoding.PROBE_BYTES)
        assertTrue(
            "探测边界必须落在字符中间，否则这条用例没测到点子上",
            TextEncoding.PROBE_BYTES % unit.size != 0,
        )
        assertEquals("UTF-8", TextEncoding.decide(bytes).charset.name())
    }
}
