package com.rokidlab.phone.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [KnowledgeBase.stitch] 的纯逻辑回归测试。
 *
 * 为什么必须锁：它是**唯一会改动用户正文**的逻辑。知识库的相邻块刻意保留
 * `CHUNK_OVERLAP=64` 字符重叠（答案跨块边界时两块都能独立命中），所以"把块拼回全文"
 * 必须消掉这段重叠 —— 少舍一点，用户保存后文档里就多出一段重复字；多舍一点，
 * 就直接**吃掉正文**。管理界面的"编辑并保存"走的就是这条路。
 *
 * 这里刻意不碰 SQLite（`fullText` 的另一半职责是分页读取），只钉纯字符串拼接。
 */
class KnowledgeBaseStitchTest {

    @Test
    fun `单块原样返回`() {
        assertEquals("只有一块时不该有任何改动", "正文内容", KnowledgeBase.stitch(listOf("正文内容")))
    }

    @Test
    fun `相邻块的重叠部分只保留一份`() {
        // 模拟分块器：第一块 436 个 A + 64 个 B，第二块同样以那 64 个 B 开头
        val head = "A".repeat(436) + "B".repeat(64)
        val next = "B".repeat(64) + "C".repeat(436)
        val out = KnowledgeBase.stitch(listOf(head, next))
        assertEquals(head + "C".repeat(436), out)
        assertEquals("长度必须是两块之和减去一次重叠", 436 + 64 + 436, out.length)
    }

    @Test
    fun `无重叠时直接相连不丢字`() {
        // 边界情况：两块恰好没有公共前后缀 —— 一个字都不能少
        assertEquals("12345abcde", KnowledgeBase.stitch(listOf("12345", "abcde")))
    }

    @Test
    fun `多块链式拼接每处交界都消重叠`() {
        val a = "AAAAABBBBB"
        val b = "BBBBBCCCCC"
        val c = "CCCCC12345"
        assertEquals("AAAAABBBBBCCCCC12345", KnowledgeBase.stitch(listOf(a, b, c)))
    }

    @Test
    fun `取最长匹配而不是最短`() {
        // 短匹配（里层的 "abc"）与长匹配（"xabc"）都能成立时必须取长的，
        // 否则会把第一块末尾的 x 重复一遍
        assertEquals("xxabcYY", KnowledgeBase.stitch(listOf("xxabc", "xabcYY")))
    }

    @Test
    fun `多块中文正文按分块器的重叠规则切分后能原样拼回`() {
        // 用带唯一序号的合成正文（而非真实散文）：真实文本里可能出现巧合的更长公共前后缀，
        // 那会让断言变成"考运气"。序号递增 ⇒ 只可能存在预期的那一处重叠，结论确定。
        val doc = (1..120).joinToString("") { "字$it" }
        val size = 30
        val overlap = 10
        val chunks = mutableListOf<String>()
        var i = 0
        while (i < doc.length) {
            val end = minOf(i + size, doc.length)
            chunks.add(doc.substring(i, end))
            if (end == doc.length) break
            i = end - overlap
        }
        assertTrue("块数要够多才有意义（至少得跨好几个交界）", chunks.size > 5)
        assertEquals(doc, KnowledgeBase.stitch(chunks))
    }
}
