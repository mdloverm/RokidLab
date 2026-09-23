package com.rokidlab.phone.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [KnowledgeBase.Chunker] v4「Markdown 结构保留」契约测试。
 *
 * 旧分块器把所有连续空白（含换行）压成单空格 —— md 的标题、列表、段落进库后全被压平，
 * 编辑/预览拼回来是一行文本。这里钉住新规则：换行保留、段落空行最多一个、行内空格压缩、
 * CRLF 归一为 LF，且块仍优先在换行/句界断开。
 */
class KnowledgeChunkerTest {

    private fun chunkOf(text: String): List<String> {
        val c = KnowledgeBase.Chunker()
        c.feed(text)
        val out = mutableListOf<String>()
        while (true) {
            out += c.nextChunk(eof = true) ?: break
        }
        return out
    }

    @Test
    fun `短文档单块保留换行结构`() {
        val doc = "# 标题\n\n- 第一项\n- 第二项\n\n正文段落。"
        val chunks = chunkOf(doc)
        assertEquals(1, chunks.size)
        // 换行必须活过切块（旧实现会压成空格）
        assertEquals(doc, chunks[0])
    }

    @Test
    fun `CRLF 归一为 LF`() {
        val chunks = chunkOf("# 标题\r\n\r\n正文\r\n第二段")
        assertEquals("# 标题\n\n正文\n第二段", chunks.single())
    }

    @Test
    fun `三个以上连续换行压成段落分隔`() {
        val chunks = chunkOf("段一\n\n\n\n\n段二")
        assertEquals("段一\n\n段二", chunks.single())
    }

    @Test
    fun `行内连续空格压缩但跨行不影响`() {
        val chunks = chunkOf("行一    还有字\n行二\t\t制表")
        assertEquals("行一 还有字\n行二 制表", chunks.single())
    }

    @Test
    fun `长文档切块后换行仍保留且块长在合理范围`() {
        // 每段约 40 字、段落间空行，总长足够切出多块
        val doc = (1..40).joinToString("\n\n") { "第 $it 段：这是一段用于占满切块窗口的中文正文内容。" }
        val chunks = chunkOf(doc)
        assertTrue("应当切出多块，实际 ${chunks.size}", chunks.size > 1)
        // 标题/列表式短行存在时，块边界应落在换行处：每块首尾都不该残留半截空行
        chunks.forEach { c ->
            assertFalse("块不应以换行开头: ${c.take(10)}", c.startsWith("\n"))
            assertFalse("块不应以换行结尾: ${c.takeLast(10)}", c.endsWith("\n"))
            assertTrue("块内必须保留段落换行", c.contains("\n"))
        }
        // 去重叠拼接后应还原出与规范化后一致的正文
        val stitched = KnowledgeBase.stitch(chunks)
        val expected = doc.replace("\r", "").replace(Regex("\n{3,}"), "\n\n").trim()
        assertEquals(expected, stitched)
    }

    @Test
    fun `markdown 列表跨块后拼回不丢行`() {
        val doc = buildString {
            (1..60).forEach { append("- 第 ").append(it).append(" 条：").append("内容".repeat(8)).append('\n') }
        }
        val chunks = chunkOf(doc)
        assertTrue(chunks.size > 1)
        val stitched = KnowledgeBase.stitch(chunks).trim()
        // 每一条列表项都必须还在（旧压平逻辑下条目会黏成一行，换行丢失）
        (1..60).forEach { i ->
            assertTrue("第 $i 条丢失", stitched.contains("- 第 $i 条："))
        }
        assertEquals(60, stitched.lineSequence().filter { it.startsWith("- 第") }.count())
    }
}
