package com.rokidlab.phone.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SKILL.md 解析/序列化/命名校验/静态引用检查（纯 Kotlin，无 Android 依赖）单测。
 *
 * 锁定语义：
 *  1. frontmatter 必须为 --- 包裹、含 name（description 可缺省为空串）；
 *  2. 容忍 BOM、CRLF 与首尾空行；
 *  3. name 白名单正则：小写开头 + 小写/数字/连字符，2~32 位；
 *  4. build → parse 往返一致；
 *  5. checkUnsafeRefs 只对「调用 xxx / xxx()」形式做静态提示，不阻断。
 */
class SkillMarkdownTest {

    private val sample = """
        |---
        |name: weather-run-advice
        |description: 当用户问今天适合跑步吗、出门穿什么时使用
        |---
        |
        |## 操作步骤
        |1. 调用 get_current_time 确认今天是星期几
        |2. 调用 search_web 查询今天天气
        |3. 结合体感温度给出穿衣建议
    """.trimMargin()

    @Test
    fun `解析合法技能 frontmatter 与正文`() {
        val skill = SkillMarkdown.parse(sample)
        assertNotNull(skill)
        skill!!
        assertEquals("weather-run-advice", skill.name)
        assertEquals("当用户问今天适合跑步吗、出门穿什么时使用", skill.description)
        assertTrue(skill.body.startsWith("## 操作步骤"))
        assertTrue(skill.body.contains("search_web"))
    }

    @Test
    fun `容忍 BOM CRLF 与首尾空行`() {
        val messy = "\uFEFF\r\n\r\n---\r\nname: a-b\r\ndescription: 测试\r\n---\r\n\r\n# 正文\r\n\r\n"
        val skill = SkillMarkdown.parse(messy)
        assertNotNull(skill)
        assertEquals("a-b", skill!!.name)
        assertEquals("测试", skill.description)
        assertEquals("# 正文", skill.body)
    }

    @Test
    fun `description 缺省时解析成功但为空串`() {
        val text = """
            |---
            |name: foo-bar
            |---
            |
            |# Steps
        """.trimMargin()
        val skill = SkillMarkdown.parse(text)
        assertNotNull(skill)
        assertEquals("", skill!!.description)
        assertEquals("# Steps", skill.body)
    }

    @Test
    fun `支持 what_it_does 别名与引号取值`() {
        val text = """
            |---
            |name: brew-tips
            |what_it_does: "当用户问咖啡怎么做好喝时使用"
            |---
            |
            |# 步骤
        """.trimMargin()
        val skill = SkillMarkdown.parse(text)
        assertNotNull(skill)
        assertEquals("当用户问咖啡怎么做好喝时使用", skill!!.description)
    }

    @Test
    fun `无 frontmatter 或缺少 name 时返回 null`() {
        assertNull(SkillMarkdown.parse(""))
        assertNull(SkillMarkdown.parse("只读没有 frontmatter"))
        assertNull(SkillMarkdown.parse("---\ndescription: 只有描述\n---\n正文"))
        assertNull(SkillMarkdown.parse("---\nname: x\n")) // 未闭合 frontmatter
    }

    @Test
    fun `parse 宽松：正文为空仍返回骨架技能`() {
        // parse 只负责拆出 frontmatter 字段，空正文等业务校验由安装管线（installFromMarkdown）把关
        val skill = SkillMarkdown.parse("---\nname: x-y\n---\n")
        assertNotNull(skill)
        assertEquals("x-y", skill!!.name)
        assertEquals("", skill.description)
        assertEquals("", skill.body)
    }

    @Test
    fun `validateName 接受合法名`() {
        assertNull(SkillMarkdown.validateName("weather-run-advice"))
        assertNull(SkillMarkdown.validateName("a1"))
        assertNull(SkillMarkdown.validateName("n" + "x".repeat(31))) // 恰好 32 位
    }

    @Test
    fun `validateName 拒绝非法名并给出原因`() {
        assertNotNull(SkillMarkdown.validateName(""))
        assertNotNull(SkillMarkdown.validateName("Xxx"))       // 大写开头
        assertNotNull(SkillMarkdown.validateName("1abc"))      // 数字开头
        assertNotNull(SkillMarkdown.validateName("has space"))
        assertNotNull(SkillMarkdown.validateName("a"))         // 过短
        assertNotNull(SkillMarkdown.validateName("a".repeat(33))) // 过长
        assertNotNull(SkillMarkdown.validateName("a/../b"))    // 目录穿越字符
        assertNotNull(SkillMarkdown.validateName("中文名"))
    }

    @Test
    fun `build 后 parse 往返一致`() {
        val md = SkillMarkdown.build("run-advice", "跑步建议", "# Steps\n1. get_current_time")
        val skill = SkillMarkdown.parse(md)
        assertNotNull(skill)
        assertEquals("run-advice", skill!!.name)
        assertEquals("跑步建议", skill.description)
        assertEquals("# Steps\n1. get_current_time", skill.body)
    }

    @Test
    fun `build 会把 description 中的换行拍平为单行`() {
        val md = SkillMarkdown.build("x-y", "第一行\n第二行", "body")
        assertEquals("---\nname: x-y\ndescription: 第一行 第二行\n---\n\nbody", md)
    }

    @Test
    fun `checkUnsafeRefs 识别未注册动作并过滤工具白名单`() {
        val known = setOf("get_current_time", "search_web", "load_skill")
        val body = """
            |1. 调用 get_current_time 确认时间
            |2. 调用 launch_missile 打开舱门
            |3. 调用 do_something_weird() 收尾
            |4. 参考 https 文档，time 与 date 属于常见词
        """.trimMargin()
        val unsafe = SkillMarkdown.checkUnsafeRefs(body, known)
        assertEquals(listOf("launch_missile", "do_something_weird"), unsafe)
    }

    @Test
    fun `checkUnsafeRefs 对全部合法引用返回空`() {
        val body = """
            |调用 search_web 查天气，再调用 load_skill 取步骤。
            |最后用 get_current_time 确认星期。
        """.trimMargin()
        assertTrue(SkillMarkdown.checkUnsafeRefs(body, setOf("search_web", "load_skill", "get_current_time")).isEmpty())
    }

    @Test
    fun `checkUnsafeRefs 去重且不区分大小写`() {
        val body = "调用 Get_Current_Time() 后调用 get_current_time 对比"
        val unsafe = SkillMarkdown.checkUnsafeRefs(body, setOf("get_current_time"))
        assertTrue(unsafe.isEmpty())
    }

    // ═══════════════════ 大技能分片（## 章节）═══════════════════

    private val bigBody = """
        |# 概览
        |介绍语
        |
        |## 1. 准备
        |先检查电量
        |
        |## 2. 操作
        |按顺序执行：
        |1. 调用 get_current_time
        |2. 调用 search_web
        |
        |## 3. 收尾
        |汇总结果
    """.trimMargin()

    @Test
    fun `parseChapters 按顶层 ## 切分并忽略前面正文`() {
        val chapters = SkillMarkdown.parseChapters(bigBody)
        assertEquals(3, chapters.size)
        assertEquals("1. 准备", chapters[0].heading)
        assertTrue(chapters[0].content.contains("先检查电量"))
        assertTrue(chapters[1].content.contains("search_web"))
        assertEquals("3. 收尾", chapters[2].heading)
        // 每个 Section 的 content 不含标题行
        assertTrue(!chapters[1].content.contains("## 2"))
    }

    @Test
    fun `parseChapters 忽略代码块 fence 内的井号行`() {
        val body = """
            |## 真实章节
            |正文一行
            |```kotlin
            |## 这是代码里的假标题，不应切分
            |val x = 1
            |```
            |## 后一章
            |结尾
        """.trimMargin()
        val chapters = SkillMarkdown.parseChapters(body)
        assertEquals(2, chapters.size)
        assertEquals("真实章节", chapters[0].heading)
        assertEquals("后一章", chapters[1].heading)
        assertTrue(chapters[0].content.contains("val x = 1"))
    }

    @Test
    fun `parseChapters 无章节或空正文返回空`() {
        assertTrue(SkillMarkdown.parseChapters("").isEmpty())
        assertTrue(SkillMarkdown.parseChapters("只有一段话，没有 ## 标题").isEmpty())
    }

    @Test
    fun `locateSection 支持数字序号定位`() {
        val s2 = SkillMarkdown.locateSection(bigBody, "2")
        assertEquals("2. 操作", s2!!.heading)
        assertNull(SkillMarkdown.locateSection(bigBody, "9"))
        assertNull(SkillMarkdown.locateSection(bigBody, "0"))
    }

    @Test
    fun `locateSection 支持标题与忽略编号前缀及大小写`() {
        assertEquals("1. 准备", SkillMarkdown.locateSection(bigBody, "1. 准备")!!.heading)
        assertEquals("1. 准备", SkillMarkdown.locateSection(bigBody, "准备")!!.heading)
        // 英文标题忽略大小写 + 近似包含
        val en = "## Project Structure\n内容A\n\n## Event Handling\n内容B"
        assertEquals("Event Handling", SkillMarkdown.locateSection(en, "event handling")!!.heading)
        assertEquals("Project Structure", SkillMarkdown.locateSection(en, "structure")!!.heading)
        assertNull(SkillMarkdown.locateSection(en, "nothing-here"))
    }

    @Test
    fun `buildChaptersIndex 生成带序号字数预览的目录`() {
        val idx = SkillMarkdown.buildChaptersIndex(bigBody)
        assertTrue(idx.startsWith("第 1 章 1. 准备"))
        assertTrue(idx.contains("第 2 章 2. 操作"))
        assertTrue(idx.contains("第 3 章 3. 收尾"))
        assertTrue(idx.contains("字）"))
        assertTrue(idx.contains("search_web")) // 章节预览带出正文开头
    }
}
