package com.rokidlab.phone.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [parseChatMarkdown] 的块级解析回归测试（2026-10-06 审计修）。
 *
 *## 锁的是哪些 bug（都是"用户看到坏内容"级别）
 * 1. **Markdown 图片被吞**：`![alt](src)` 里的 `[alt](src)` 被 [INLINE_REGEX] 的链接分支
 *    匹配掉，`!` 落在匹配区间外被原样保留 ⇒ 用户看到 `!架构图`，**图片完全不存在**。
 * 2. **表格漏判**：GFM 明确允许省略首尾竖线，原实现要求"行以 | 起头"⇒ 漏判后落
 *    [flushParagraph]，而它会折叠换行 ⇒ **整张表变成一长条散文**，结构全丢。
 *
 * 📌 为什么这些必须单测：它们**不会报错、不会崩**，只是渲染结果不对 ——
 * 只有断言能钉住。测试锁的是**生产解析器本身**（`parseChatMarkdown` 是 internal 纯函数，
 * 无 Android 依赖），不是它的复制品。
 */
class ChatMarkdownParseTest {

    // ===== 图片 =====

    @Test
    fun `独占一行的图片被识别为 Image 块`() {
        val blocks = parseChatMarkdown("![架构图](https://a.example/p.png)")
        assertEquals("应产出 1 个块", 1, blocks.size)
        val img = blocks.first()
        assertTrue("应是 Image 块，实际是 ${img::class.simpleName}", img is MdBlock.Image)
        img as MdBlock.Image
        assertEquals("架构图", img.alt)
        assertEquals("https://a.example/p.png", img.src)
    }

    @Test
    fun `data URL 的内联图片也能被识别`() {
        // ⚠️ 正则的 src 部分必须允许 `,`，否则 base64 内联图收不下来（审计里踩过这个坑）
        val src = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=="
        val blocks = parseChatMarkdown("![img]($src)")
        assertEquals("应产出 1 个块", 1, blocks.size)
        assertTrue(blocks.first() is MdBlock.Image)
        assertEquals(src, (blocks.first() as MdBlock.Image).src)
    }

    @Test
    fun `图片前后有文字时按段落处理 不吞掉整行`() {
        // 行内混排不做图片卡（保守：避免破坏段落结构），
        // 但至少不能变成"只剩一个感叹号"那种坏内容。
        val blocks = parseChatMarkdown("这是 ![架构图](https://a.png) 这样的结构")
        val para = blocks.filterIsInstance<MdBlock.Paragraph>().joinToString(" ") { it.raw }
        assertTrue(
            "正文应保留可读内容，实际：$para",
            para.contains("架构图"),
        )
    }

    @Test
    fun `多张图片各自成块`() {
        val blocks = parseChatMarkdown(
            """
            ![一](https://a/1.png)

            正文段落

            ![二](https://a/2.png)
            """.trimIndent(),
        )
        val images = blocks.filterIsInstance<MdBlock.Image>()
        assertEquals("两张图都该被识别", 2, images.size)
        assertEquals("https://a/1.png", images[0].src)
        assertEquals("https://a/2.png", images[1].src)
    }

    @Test
    fun `空 alt 的图片也能识别`() {
        val blocks = parseChatMarkdown("![](https://a/x.png)")
        assertEquals(1, blocks.size)
        assertTrue(blocks.first() is MdBlock.Image)
        assertEquals("", (blocks.first() as MdBlock.Image).alt)
    }

    // ===== 表格：省略首尾竖线的 GFM 写法 =====

    @Test
    fun `省略首尾竖线的表格被识别`() {
        // ★ 修复前会漏判 ⇒ 落Paragraph ⇒ flushParagraph 折叠换行 ⇒ 表变散文
        val md = """
            名称 | 类型 | 说明
            --- | --- | ---
            a.txt | txt | 文本
            b.md | md | 文档
        """.trimIndent()
        val blocks = parseChatMarkdown(md)
        val table = blocks.filterIsInstance<MdBlock.Table>().firstOrNull()
        assertNotNull("省略首尾竖线也该被识别为表格，实际块类型=${blocks.map { it::class.simpleName }}", table)
        table!!
        assertEquals(listOf("名称", "类型", "说明"), table.header)
        assertEquals(2, table.rows.size)
        assertEquals(listOf("a.txt", "txt", "文本"), table.rows[0])
    }

    @Test
    fun `带首尾竖线的标准表格仍然被识别`() {
        val md = """
            | 名称 | 类型 |
            | --- | --- |
            | a | txt |
        """.trimIndent()
        val blocks = parseChatMarkdown(md)
        val table = blocks.filterIsInstance<MdBlock.Table>().firstOrNull()
        assertNotNull("标准写法必须仍然工作（别为了放宽把正常的弄坏）", table)
        assertEquals(listOf("名称", "类型"), table!!.header)
    }

    @Test
    fun `普通句子里的竖线不被误判为表格`() {
        // ★ 放宽判定后最该验的回归：判据是"含 | 且下一行是分隔行"，
        // 少了"下一行必须是分隔行"这个硬条件，普通文字就会被当成表格。
        val md = """
            请选择 A | B 或者其他
            这是一段普通说明文字，不该变表格。
        """.trimIndent()
        val blocks = parseChatMarkdown(md)
        assertTrue(
            "不该产出表格，实际：${blocks.map { it::class.simpleName }}",
            blocks.none { it is MdBlock.Table },
        )
    }

    @Test
    fun `水平分隔线不会被误判为表格`() {
        val md = """
            第一段

            ---

            第二段
        """.trimIndent()
        val blocks = parseChatMarkdown(md)
        assertTrue(
            "--- 不该是表格（分隔行本身不含 |）",
            blocks.none { it is MdBlock.Table },
        )
    }

    @Test
    fun `表头只有一列时不认表格`() {
        // ⚠️ 单列不成表。一列表格在 GFM 里必须写成 `| only |`（带首尾竖线），
        // 省略竖线写法（`only` 独占一行）根本没有分隔行 ⇒自然不会被误收。
        // 这里用"整行只有一个 | "的退化输入来验列数下限这层保护。
        val md = """
            only |
            --- |
        """.trimIndent()
        val blocks = parseChatMarkdown(md)
        assertTrue(
            "单列不该成表，实际：${blocks.map { it::class.simpleName }}",
            blocks.none { it is MdBlock.Table },
        )
    }

    // ===== 既有行为不许被破坏 =====

    @Test
    fun `围栏代码块仍优先于图片识别`() {
        val md = """
            ```
            ![这不是图片](https://a/x.png)
            ```
        """.trimIndent()
        val blocks = parseChatMarkdown(md)
        assertEquals("代码块内不该产出图片卡", 1, blocks.size)
        assertTrue("应是 Code 块", blocks.first() is MdBlock.Code)
        assertEquals("![这不是图片](https://a/x.png)", (blocks.first() as MdBlock.Code).code)
    }

    @Test
    fun `省略竖线的表格数据行也能收全`() {
        // 只放宽表头判据、不放宽数据行的话 ⇒ 表头有、数据行丢了（半张表更难懂）
        val md = """
            A | B
            --- | ---
            1 | 2
            3 | 4
        """.trimIndent()
        val blocks = parseChatMarkdown(md)
        val table = blocks.filterIsInstance<MdBlock.Table>().firstOrNull()
        assertNotNull(table)
        assertEquals("数据行必须收全", 2, table!!.rows.size)
    }

    @Test
    fun `对齐行支持省略竖线的居中写法`() {
        val md = """
            A | B | C
            :--- | :---: | ---:
            1 | 2 | 3
        """.trimIndent()
        val table = parseChatMarkdown(md).filterIsInstance<MdBlock.Table>().firstOrNull()
        assertNotNull(table)
        assertEquals(listOf('l', 'c', 'r'), table!!.aligns)
    }

    // ===== 嵌套列表（2026-10-06 审计修）=====

    @Test
    fun `缩进子项与父项层级不同`() {
        // ★ 修复前缩进在 trim 时被丢 ⇒父子必然同级，用户看不出从属关系
        val md = """
            1. 准备
                - 安装依赖
                - 配置
            2. 构建
        """.trimIndent()
        val bullets = parseChatMarkdown(md).filterIsInstance<MdBlock.Bullet>()
        assertEquals(4, bullets.size)
        assertEquals("准备", bullets[0].raw)
        assertEquals(0, bullets[0].level)
        assertEquals("安装依赖", bullets[1].raw)
        assertTrue("4 空格缩进应算 2 级，实际 ${bullets[1].level}", bullets[1].level >= 1)
        assertTrue("子项层级必须深于父项", bullets[1].level > bullets[0].level)
        assertEquals(0, bullets[3].level)
    }

    @Test
    fun `制表符缩进也算一级`() {
        val md = "- 父\n\t- 子"
        val bullets = parseChatMarkdown(md).filterIsInstance<MdBlock.Bullet>()
        assertEquals(2, bullets.size)
        assertEquals(0, bullets[0].level)
        assertEquals(1, bullets[1].level)
    }

    @Test
    fun `畸形超深缩进被封顶`() {
        // 有人用 20 空格缩进时不该把缩进撑成屏幕宽
        val md = "- 父\n" + " ".repeat(40) + "- 子"
        val bullets = parseChatMarkdown(md).filterIsInstance<MdBlock.Bullet>()
        assertTrue("层级必须封顶，实际 ${bullets[1].level}", bullets[1].level <= 5)
    }

    // ===== 任务列表 =====

    @Test
    fun `任务列表被识别且标记被剥掉`() {
        // 修复前 `- [x] 装依赖` 原样显示 `[x]`
        val md = """
            - [x] 已完成的事
            - [ ] 待办的事
            - [X] 大写 X 也认
        """.trimIndent()
        val bullets = parseChatMarkdown(md).filterIsInstance<MdBlock.Bullet>()
        assertEquals(3, bullets.size)
        assertEquals("已完成的事", bullets[0].raw)
        assertEquals(true, bullets[0].taskDone)
        assertEquals("待办的事", bullets[1].raw)
        assertEquals(false, bullets[1].taskDone)
        assertEquals("大写 X 也认", bullets[2].raw)
        assertEquals(true, bullets[2].taskDone)
    }

    @Test
    fun `普通列表的 taskDone 为 null`() {
        // ★ 分支要区分"不是任务列表"与"未完成的任务" —— 前者不该画勾选框
        val bullets = parseChatMarkdown("- 普通项").filterIsInstance<MdBlock.Bullet>()
        assertEquals(1, bullets.size)
        assertNull("普通列表项不该被当成任务", bullets[0].taskDone)
    }

    // ===== 引用块 =====

    @Test
    fun `单行引用被识别且前缀被剥掉`() {
        // 修复前 `>` 符号原样显示，且多行被 flushParagraph 折成一行
        val blocks = parseChatMarkdown("> 注意：此操作不可逆")
        val quote = blocks.filterIsInstance<MdBlock.Quote>().firstOrNull()
        assertNotNull("应产出 Quote 块，实际 ${blocks.map { it::class.simpleName }}", quote)
        assertEquals(listOf("注意：此操作不可逆"), quote!!.lines)
    }

    @Test
    fun `多行连续引用合并成一块`() {
        val md = """
            > 第一行
            > 第二行
            > 第三行

            正文段落
        """.trimIndent()
        val blocks = parseChatMarkdown(md)
        val quotes = blocks.filterIsInstance<MdBlock.Quote>()
        assertEquals("连续引用应合并为 1 块", 1, quotes.size)
        assertEquals(3, quotes[0].lines.size)
        assertEquals("正文段落", blocks.filterIsInstance<MdBlock.Paragraph>().first().raw)
    }

    @Test
    fun `引用块内不再显示大于号`() {
        val quote = parseChatMarkdown("> x").filterIsInstance<MdBlock.Quote>().first()
        assertTrue("引用块内容不应再带 `>`", quote.lines.none { it.startsWith(">") })
    }

    // ===== 水平分隔线 =====

    @Test
    fun `三种写法都识别为分隔线`() {
        // 修复前只匹配 ^-{3,}$ ⇒ *** / ___ 会落Paragraph 显示成原文
        for (hr in listOf("---", "***", "___", "- - -")) {
            val blocks = parseChatMarkdown("前\n\n$hr\n\n后")
            assertTrue(
                "`$hr` 应被识别为分隔线，实际 ${blocks.map { it::class.simpleName }}",
                blocks.any { it is MdBlock.Divider },
            )
        }
    }

    @Test
    fun `分隔线前后段落各自成块`() {
        val blocks = parseChatMarkdown("前段\n\n---\n\n后段")
        assertEquals(2, blocks.filterIsInstance<MdBlock.Paragraph>().size)
        assertEquals(1, blocks.filterIsInstance<MdBlock.Divider>().size)
    }

    // ===== echarts 围栏提取/替换（眼镜显示帧用，见 AiConversationService 图表回合）=====

    @Test
    fun `extractEChartsSources 提取闭合块的 option 源码`() {
        val md = "前文\n```echarts\n{\"series\":[1]}\n```\n中文\n```echarts\n{\"series\":[2]}\n```\n后文"
        assertEquals(listOf("{\"series\":[1]}", "{\"series\":[2]}"), extractEChartsSources(md))
    }

    @Test
    fun `extractEChartsSources 未闭合块不算（与图表卡分流同口径）`() {
        val md = "前文\n```echarts\n{\"series\": [1]}"
        assertTrue(extractEChartsSources(md).isEmpty())
    }

    @Test
    fun `extractEChartsSources 无关代码块不误收`() {
        val md = "```python\nprint(1)\n```\n```json\n{\"a\":1}\n```"
        assertTrue(extractEChartsSources(md).isEmpty())
    }

    @Test
    fun `replaceEChartsBlocks 整段替换为一行占位且保留其余内容`() {
        val md = "前文\n```echarts\n{\"series\":[1]}\n```\n后文"
        val out = replaceEChartsBlocks(md, "占位")
        assertEquals("前文\n占位\n后文", out)
    }

    @Test
    fun `replaceEChartsBlocks 无块时原样返回`() {
        val md = "普通回复，无图表"
        assertEquals(md, replaceEChartsBlocks(md, "占位"))
    }

    @Test
    fun `提取与替换口径一致——替换后不再有可提取源码`() {
        val md = "```echarts\n{\"series\":[1]}\n```"
        val out = replaceEChartsBlocks(md, "已推送")
        assertTrue(extractEChartsSources(out).isEmpty())
    }

    // ===== ECharts 图表 =====

    @Test
    fun `闭合的 echarts 围栏被识别为 Chart 块`() {
        val md = """
            ```echarts
            {"xAxis":{"type":"category","data":["一","二","三"]},"yAxis":{},"series":[{"type":"bar","data":[1,2,3]}]}
            ```
        """.trimIndent()
        val blocks = parseChatMarkdown(md)
        val chart = blocks.filterIsInstance<MdBlock.Chart>().firstOrNull()
        assertNotNull("应产出 Chart 块，实际 ${blocks.map { it::class.simpleName }}", chart)
        assertTrue("JSON 体应原样保留", chart!!.code.startsWith("{\"xAxis\""))
    }

    @Test
    fun `未闭合的 echarts 围栏按代码卡处理`() {
        // 流式生成到一半：半截 JSON 渲染必失败，应先按代码卡显示源码，闭合后才切图表卡
        val md = "```echarts\n{\"xAxis\":{\"data\":[\"一\",\"二\"]}"
        val blocks = parseChatMarkdown(md)
        assertTrue(
            "未闭合应落 Code 块，实际 ${blocks.map { it::class.simpleName }}",
            blocks.first() is MdBlock.Code,
        )
    }

    @Test
    fun `mermaid 仍走 Diagram 不受 echarts 影响`() {
        val blocks = parseChatMarkdown("```mermaid\ngraph TD; A-->B;\n```")
        assertEquals(1, blocks.filterIsInstance<MdBlock.Diagram>().size)
    }

    @Test
    fun `普通语言的围栏仍走代码卡`() {
        val blocks = parseChatMarkdown("```python\nprint(1)\n```")
        val code = blocks.filterIsInstance<MdBlock.Code>().firstOrNull()
        assertNotNull(code)
        assertEquals("python", code!!.lang)
    }
}