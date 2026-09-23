package com.rokidlab.phone.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DocNormalizer] 的纯逻辑测试：格式识别与「统一 Markdown 规范表示」契约。
 *
 * 不碰 Android / SQLite —— 这里钉住的是导入链路最容易回归的三件事：
 * md/txt 必须**逐字不动**（任何"智能改写"都是污染原文）、合法 JSON 必须美化进围栏、
 * 非法 JSON 必须原样兜底（不能因格式不合法丢掉用户内容）。
 */
class DocNormalizerTest {

    @Test
    fun `扩展名识别来源格式`() {
        assertEquals(DocNormalizer.SourceFormat.MD, DocNormalizer.formatFromName("笔记.md"))
        assertEquals(DocNormalizer.SourceFormat.MD, DocNormalizer.formatFromName("a.MARKDOWN"))
        assertEquals(DocNormalizer.SourceFormat.JSON, DocNormalizer.formatFromName("config.json"))
        assertEquals(DocNormalizer.SourceFormat.TXT, DocNormalizer.formatFromName("notes.txt"))
        // 未知/无扩展名按纯文本（与改造前行为一致）
        assertEquals(DocNormalizer.SourceFormat.TXT, DocNormalizer.formatFromName("README"))
        assertEquals(DocNormalizer.SourceFormat.TXT, DocNormalizer.formatFromName("a.log"))
    }

    @Test
    fun `md 与 txt 原样保留不做任何改写`() {
        val md = "# 标题\n\n- 项目一\n- 项目二\n\n正文 **加粗** `code`。"
        assertEquals(md, DocNormalizer.normalize(md, DocNormalizer.SourceFormat.MD))

        val txt = "第一行\r\n第二行\r\n\r\n第四行"
        assertEquals(txt, DocNormalizer.normalize(txt, DocNormalizer.SourceFormat.TXT))
    }

    @Test
    fun `合法 JSON 对象美化并包进 json 围栏`() {
        val out = DocNormalizer.normalize(
            """{"name":"眼镜","version":2}""",
            DocNormalizer.SourceFormat.JSON,
        )
        assertTrue("应以 json 围栏开头", out.startsWith("```json\n"))
        assertTrue("应以围栏收尾", out.trimEnd().endsWith("```"))
        assertTrue("应缩进美化", out.contains("\n  \"name\": \"眼镜\""))
        assertTrue("中文必须原样保留，不能被转义成 unicode 码点", out.contains("眼镜"))
        // 同一份数据规范化后只应有一对围栏
        assertEquals(2, out.lineSequence().count { it.trim() == "```json" || it.trim() == "```" })
    }

    @Test
    fun `JSON 数组同样美化进围栏`() {
        val out = DocNormalizer.normalize("[1, 2, 3]", DocNormalizer.SourceFormat.JSON)
        assertTrue(out.startsWith("```json\n"))
        assertTrue(out.contains("[\n  1,\n  2,\n  3\n]"))
    }

    @Test
    fun `非法 JSON 原样退回不丢内容`() {
        val broken = "{这不是合法 JSON，但其实是用户的笔记"
        assertEquals(broken, DocNormalizer.normalize(broken, DocNormalizer.SourceFormat.JSON))
    }

    @Test
    fun `顶层裸标量不做美化按原文兜底`() {
        // 只接受对象/数组两种主流形态；裸字符串不臆测包装
        val raw = "\"just a string\""
        assertEquals(raw, DocNormalizer.normalize(raw, DocNormalizer.SourceFormat.JSON))
        assertFalse(raw.startsWith("```"))
    }
}
