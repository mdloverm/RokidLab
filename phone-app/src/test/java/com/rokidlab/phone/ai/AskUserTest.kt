package com.rokidlab.phone.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ask_user 伪工具纯函数的钉子（2026-10-12）。
 *
 * 只钉 [AskUser.parse] / [AskUser.formatAnswer] 的**语义**：
 * parse 是"弹窗内容对不对"的唯一入口（坏了会弹空问题或漏选项）；
 * formatAnswer 是"用户选择回填模型"的唯一入口（错了模型会拿不到用户的选择）。
 */
class AskUserTest {

    // ── parse：合法输入 ──

    @Test
    fun `合法参数解析出问题与选项`() {
        val q = AskUser.parse(
            """{"question":"用哪种风格？","options":[
               {"label":"深色极简"},{"label":"亮色卡片","hint":"开发快"}],
               "multi_select":false,"allow_free_text":true}""",
        )
        assertNotNull(q)
        q!!
        assertEquals("用哪种风格？", q.text)
        assertEquals(2, q.options.size)
        assertEquals("深色极简", q.options[0].label)
        assertEquals("开发快", q.options[1].hint)
        assertEquals(false, q.multiSelect)
        assertEquals(true, q.allowFreeText)
    }

    @Test
    fun `缺省时 multi_select 为 false 且 allow_free_text 为 true`() {
        val q = AskUser.parse("""{"question":"选一个"}""")
        assertNotNull(q)
        q!!
        assertEquals(false, q.multiSelect)
        assertEquals(true, q.allowFreeText)
        assertTrue(q.options.isEmpty())
    }

    @Test
    fun `选项超过上限被截断而不是拒绝`() {
        val many = (1..30).joinToString(",") { """{"label":"选项$it"}""" }
        val q = AskUser.parse("""{"question":"选","options":[$many]}""")
        assertNotNull(q)
        assertEquals(8, q!!.options.size)
    }

    // ── parse：非法输入一律 null（调用方回用法说明，绝不弹一个坏问题） ──

    @Test
    fun `非法 JSON 返回 null`() {
        assertNull(AskUser.parse("not json"))
        assertNull(AskUser.parse(""))
    }

    @Test
    fun `question 为空返回 null`() {
        assertNull(AskUser.parse("""{"question":"  "}"""))
        assertNull(AskUser.parse("""{"options":[{"label":"A"}]}"""))
    }

    @Test
    fun `没有选项又不允许自由输入返回 null`() {
        assertNull(
            AskUser.parse("""{"question":"选一个","allow_free_text":false}"""),
        )
    }

    @Test
    fun `label 为空的选项被丢弃`() {
        val q = AskUser.parse(
            """{"question":"选","options":[{"label":" "},{"label":"A"}],"allow_free_text":false}""",
        )
        assertNotNull(q)
        assertEquals(listOf("A"), q!!.options.map { it.label })
    }

    // ── formatAnswer：回填模型的唯一入口 ──

    @Test
    fun `只有勾选`() {
        assertEquals(
            "用户的选择：深色极简、亮色卡片",
            AskUser.formatAnswer(listOf("深色极简", "亮色卡片"), ""),
        )
    }

    @Test
    fun `只有自由输入`() {
        assertEquals("用户的回答：都行", AskUser.formatAnswer(emptyList(), " 都行 "))
    }

    @Test
    fun `勾选与自由输入并存时都带上`() {
        val out = AskUser.formatAnswer(listOf("A"), "再补充一句")
        assertTrue(out.contains("用户的选择：A"))
        assertTrue(out.contains("用户补充：再补充一句"))
    }

    @Test
    fun `空白项被过滤`() {
        assertEquals("用户的选择：A", AskUser.formatAnswer(listOf(" ", "", "A"), ""))
    }
}
