package com.rokidlab.phone.store

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.ClickableText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rokidlab.phone.design.BrewBg
import com.rokidlab.phone.design.BrewBorder
import com.rokidlab.phone.design.BrewChat
import com.rokidlab.phone.design.BrewMuted
import com.rokidlab.phone.design.BrewTextBright

/**
 * 聊天消息的**轻量 Markdown 渲染**（零依赖，专为流式回复设计）。
 *
 * ## 为什么不引第三方 Markdown 库
 * 回复是逐 token 流式追加的，第三方富文本库在整段重解析时容易闪/跳；本解析器只认
 * 聊天里真正会出现的几种结构——围栏代码块（[CodeBlockCard]）、标题、无序/有序列表、
 * 段落，以及行内的加粗、斜体、`行内代码`、链接——可控、增量成本极低。
 *
 * ## 与眼镜端的边界
 * 这里只改**手机气泡的渲染**；发给眼镜 TTS / 显示的纯文本在会话层另有清洗通道，
 * 本文件不触碰消息原文（[ChatMsg.content] 落盘的仍是原始 Markdown）。
 *
 * ## 未闭合围栏（流式进行中）
 * ```` ``` ```` 开了但还没收到收尾围栏时，后续内容**整体当作代码**渲染——
 * 这正是用户盯着一段代码逐行生成时该看到的样子，而不是满屏反引号。
 */
internal sealed interface MdBlock {
    data class Heading(val level: Int, val raw: String) : MdBlock
    data class Paragraph(val raw: String) : MdBlock
    /**
     * 列表项。[level] 是**层级**（2026-10-06 审计加）。
     *
     * ★ 原来**没有**层级字段，而主循环在匹配前先 `rawLine.trim()` ——
     * 缩进信息当场被丢掉，`  - 子项` 与 `- 父项` 必然同级。
     * 多级列表是模型输出的常见形态（"按顺序：1. 准备 / - 安装依赖 / 2. 构建"），
     * 层级塌陷后用户完全看不出从属关系。
     */
    data class Bullet(
        val ordered: Boolean,
        val index: Int,
        val raw: String,
        /** 0 = 顶层；每 2 个前导空格（或 1 个 tab）进一级，封顶 [MAX_BULLET_LEVEL] 防畸形缩进撑爆布局 */
        val level: Int = 0,
        /** 任务列表 `- [x] / - [ ]`：非 null 时渲染勾选框。[raw] 里已去掉 `[x]` 标记 */
        val taskDone: Boolean? = null,
    ) : MdBlock

    data class Code(val lang: String, val code: String) : MdBlock

    /**
     * 引用块（`> …`，连续多行合并成一块）。
     *
     * 修复前**没有**它，`> 注意：此操作不可逆` 会原样落进 Paragraph 并显示 `>` 符号，
     * 而且被 [flushParagraph] 折叠换行后多行引用会挤成一行 —— 引用语义完全丢失。
     */
    data class Quote(val lines: List<String>) : MdBlock

    /** 水平分隔线（`---` / `***` / `___`）。修复前被静默丢弃，导致长回答"糊成一片" */
    data object Divider : MdBlock

    /** Markdown 管道表格：首行为表头，[aligns] 与列一一对应（l/c/r，来自分隔行的 `:---:`） */
    data class Table(val header: List<String>, val aligns: List<Char>, val rows: List<List<String>>) : MdBlock

    /** 块级公式（`$$…$$` 或 `\[…\]`）：交给本地 KaTeX 排版 */
    data class Formula(val tex: String) : MdBlock

    /** Mermaid 图（```` ```mermaid ````）：交给本地 mermaid.js 渲染 */
    data class Diagram(val code: String) : MdBlock

    /**
     * ECharts 图表（```` ```echarts ````）：option JSON，交给本地 echarts.js 渲染。
     *
     * 只在围栏**闭合**后才进这里（见 [parseChatMarkdown] 的分流注释）：
     * 流式生成到一半的 JSON 必然解析失败，那段时间先按普通代码卡显示源码。
     */
    data class Chart(val code: String) : MdBlock

    /**
     * Markdown 图片（`![alt](src)`，独占一行或行内）。
     *
     * ## 为什么必须有这个分支（2026-10-06 审计修）
     * 原来**没有**它，而 [INLINE_REGEX] 的链接分支 `(\\[[^]]+]\\([^)\\s]+\\))`
     * 会把 `![架构图](https://a.png)` 里的 `[架构图](https://a.png)` 整段匹配掉 ——
     * `!` 落在匹配区间**之外**被原样保留，于是用户看到的是 `!架构图`：
     * **一个孤零零的感叹号加几个字，图片完全不存在**，也看不出原意是图片。
     *
     * 连带中招的还有 `data:` URL（`[^)\\s]+` 允许 `,`）⇒ base64 内联图同样只剩 `!img`。
     *
     * ⚠️ 旧注释断言"图片标记在正文里不出现（走 imageUrl 卡片）"——**该前提不成立**：
     * 小窗口、无工具、或 `show_image` 调用失败回退时，模型会直接吐 `![...](...)`。
     *
     * [src] 为 http(s) 时复用 [ChatImageCard]；`data:` 与其它 scheme 交由卡片自己判。
     */
    data class Image(val alt: String, val src: String) : MdBlock
}

/**
 * 闭合 echarts 围栏匹配（开启行 + 懒惰正文 + 与开启同 marker 的收尾行）。
 * 围栏语义与 [parseChatMarkdown] 同口径：``` 与 ~~~ 都算 marker，语言取首个 token。
 */
private val ECHARTS_FENCE_REGEX =
    Regex("""(?m)^[ \t]*(```|~~~)[ \t]*echarts[^\n]*\n([\s\S]*?)^[ \t]*\1""", RegexOption.IGNORE_CASE)

/** 提取正文中全部**闭合** echarts 块的 option 源码（按出现顺序；未闭合不算，与图表卡分流同口径） */
internal fun extractEChartsSources(markdown: String): List<String> =
    ECHARTS_FENCE_REGEX.findAll(markdown).mapNotNull { m ->
        m.groupValues[2].trim().takeIf { it.isNotBlank() }
    }.toList()

/** 把闭合 echarts 块整体替换为一行占位文本（眼镜显示帧用——官方气泡渲染器不渲染 echarts，原样显示就是一屏代码） */
internal fun replaceEChartsBlocks(markdown: String, replacement: String): String =
    if (ECHARTS_FENCE_REGEX.containsMatchIn(markdown)) {
        ECHARTS_FENCE_REGEX.replace(markdown) { replacement }
    } else {
        markdown
    }

internal fun parseChatMarkdown(src: String): List<MdBlock> {
    val text = src.replace("\r\n", "\n").replace('\r', '\n')
    if (text.isBlank()) return emptyList()

    val blocks = mutableListOf<MdBlock>()
    val paragraph = mutableListOf<String>()

    fun flushParagraph() {
        val joined = paragraph.joinToString(" ").replace(Regex("\\s+"), " ").trim()
        if (joined.isNotBlank()) blocks += MdBlock.Paragraph(joined)
        paragraph.clear()
    }

    val lines = text.lines()
    var i = 0
    while (i < lines.size) {
        val rawLine = lines[i]
        val line = rawLine.trim()

        // 围栏代码块：``` 或 ~~~，语言标签取首个 token（```python title="x" → python）
        val fence = Regex("^\\s*(```|~~~)(.*)$").find(rawLine)
        if (fence != null) {
            flushParagraph()
            val marker = fence.groupValues[1]
            val lang = fence.groupValues[2].trim().substringBefore(' ').lowercase()
            val code = mutableListOf<String>()
            i++
            while (i < lines.size && !lines[i].trim().startsWith(marker)) {
                code += lines[i]
                i++
            }
            // i == size = 流式中围栏尚未闭合；i < size = 收尾行，跳过它
            val closed = i < lines.size
            if (i < lines.size) i++
            // ```mermaid 走图表卡；```echarts 只在**闭合后**走图表卡
            //（半截 JSON 解析必失败 —— 那段时间按代码卡显示源码，闭合瞬间才切渲染，
            // 避免「失败 → 失败 → 突然变成图」的闪烁；mermaid 半截可渲染，维持原行为不动）。
            // 其余语言仍走代码卡（含复制/运行）
            val body = code.joinToString("\n").trim('\n')
            when {
                lang == "mermaid" -> blocks += MdBlock.Diagram(body)
                lang == "echarts" && closed -> blocks += MdBlock.Chart(body)
                else -> blocks += MdBlock.Code(lang, body)
            }
            continue
        }

        // 块级公式：$$…$$ 或 \[…\]，单行与多行两种写法都收（模型两种都会用）
        val formula = matchBlockFormula(lines, i)
        if (formula != null) {
            flushParagraph()
            blocks += MdBlock.Formula(formula.first)
            i = formula.second
            continue
        }

        // 管道表格：当前行**含** | 、且**下一行是分隔行**（`|---|:--:|`）才认定是表格 ——
        // 只认「有 | 的行」会把普通句子里的竖线误判成表格；
        // 而「下一行必须是分隔行」这个硬条件正是防误伤的关键，别为了兼容
        // 省略首尾竖线的写法把它也放宽（详见 [parseTable]）
        if (line.contains('|')) {
            val table = parseTable(lines, i)
            if (table != null) {
                flushParagraph()
                blocks += table.first
                i = table.second
                continue
            }
        }

        // Markdown 图片：`![alt](src)` —— 必须在 Paragraph 之前判定，
        // 否则会被 flushParagraph 折进段落、再被 INLINE_REGEX 的链接分支吃掉 label（见 [MdBlock.Image]）
        val image = matchImageLine(line)
        if (image != null) {
            flushParagraph()
            blocks += image
            i++
            continue
        }

        if (line.isBlank()) {
            flushParagraph()
            i++
            continue
        }

        val heading = Regex("^(#{1,6})\\s+(.+)$").matchEntire(line)
        if (heading != null) {
            flushParagraph()
            blocks += MdBlock.Heading(heading.groupValues[1].length, heading.groupValues[2].trim())
            i++
            continue
        }

        val ordered = Regex("^(\\d+)[.)]\\s+(.+)$").matchEntire(line)
        if (ordered != null) {
            flushParagraph()
            blocks += bulletOf(rawLine, line, ordered = true, index = ordered.groupValues[1].toIntOrNull() ?: 1,
                rest = ordered.groupValues[2])
            i++
            continue
        }

        // 水平分隔线：渲染一条细线（2026-10-06 审计加）。
        // 修复前是 `if (Regex("^-{3,}$")) { i++; continue }` —— 静默丢弃，
        // 模型用 `---` 分节的长回答会"糊成一片"，且 `***` / `___` 两种写法不匹配该正则，
        // 同一个"分隔线"概念两种写法两种结果（后者落 Paragraph 显示成 `***`）。
        // 现在三种写法统一识别并渲染。
        //
        // ★ 必须在列表分支**之前**：`- - -`（带空格的分隔线）同时符合
        //   `^[-*+]\s+(.+)$`，而 Bullet 分支先匹配就会把它当成一个列表项。
        //   单测已锁住这个顺序（会落成 [Paragraph, Bullet, Paragraph]）。
        if (HR_REGEX.matches(line)) {
            flushParagraph()
            blocks += MdBlock.Divider
            i++
            continue
        }

        val bullet = Regex("^[-*+]\\s+(.+)$").matchEntire(line)
        if (bullet != null) {
            flushParagraph()
            blocks += bulletOf(rawLine, line, ordered = false, index = 0, rest = bullet.groupValues[1])
            i++
            continue
        }

        // 引用块：`> …`，连续多行合并（2026-10-06 审计加）。
        // 修复前无此分支 ⇒ `>` 符号原样显示 + 被 flushParagraph 折成一行。
        if (line.startsWith(">")) {
            flushParagraph()
            val quoted = mutableListOf<String>()
            while (i < lines.size) {
                val l = lines[i].trim()
                if (!l.startsWith(">")) break
                quoted += l.removePrefix(">").trim()
                i++
            }
            if (quoted.isNotEmpty()) blocks += MdBlock.Quote(quoted)
            continue
        }

        paragraph += line
        i++
    }
    flushParagraph()
    return blocks
}

/**
 * 块级公式匹配（返回 tex 与下一行下标）。
 *
 * 收两种写法：`$$ … $$`（可跨行）与 `\[ … \]`。**行内** `$x$` 刻意不处理 ——
 * 行内公式要排版就必须把一段文本切成多个可排版片段，而 KaTeX 只能整块渲染
 * （一个 WebView 一个公式，塞进一行文字里既不现实也慢）；行内的 `$…$` 会原样显示，
 * 至少不破坏句子可读性。
 */
private fun matchBlockFormula(lines: List<String>, start: Int): Pair<String, Int>? {
    val first = lines[start].trim()
    val (open, close) = when {
        first.startsWith("$$") -> "$$" to "$$"
        first.startsWith("\\[") -> "\\[" to "\\]"
        else -> return null
    }
    // 单行形式：$$x=1$$
    if (first.length > open.length + close.length && first.endsWith(close)) {
        val tex = first.removePrefix(open).removeSuffix(close).trim()
        return if (tex.isEmpty()) null else tex to start + 1
    }
    val body = mutableListOf<String>()
    first.removePrefix(open).trim().takeIf { it.isNotEmpty() }?.let { body += it }
    var i = start + 1
    while (i < lines.size) {
        val l = lines[i].trim()
        if (l.endsWith(close)) {
            l.removeSuffix(close).trim().takeIf { it.isNotEmpty() }?.let { body += it }
            return body.joinToString("\n") to i + 1
        }
        body += lines[i]
        i++
    }
    // 流式中还没闭合：当作公式渲染（半截公式比满屏 `$$` 好），下次重组会刷新
    return body.joinToString("\n").takeIf { it.isNotBlank() }?.let { it to lines.size }
}

/** 单元格切分：去掉首尾竖线后按 | 切；转义竖线 `\|` 还原成普通竖线 */
private fun splitRow(line: String): List<String> =
    line.trim().trim('|').split("|").map { it.trim().replace("\\|", "|") }

/** 分隔行判定：`|---|:--:|` 之类，至少一列、且只含 - : 与空白 */
private fun isTableDivider(line: String): Boolean {
    val t = line.trim()
    if (!t.startsWith("|") && !t.contains('-')) return false
    val cells = splitRow(t)
    return cells.isNotEmpty() && cells.all { c -> c.isNotEmpty() && c.all { it == '-' || it == ':' } }
}

/**
 * 管道表格解析（返回块与下一行下标）。
 *
 * ## 触发条件（2026-10-06 审计修：从"必须以 | 起头"放宽）
 * 原来要求本行以 `|` 起头，**漏掉了 GFM 明确允许的省略首尾竖线写法**：
 * ```
 * 名称 | 类型 | 说明
 * --- | --- | ---
 * a.txt | txt | 文本
 * ```
 * 这是模型（尤其表格内容含中文时）会真实产出的写法。
 *
 * 漏判的代价不只是"没渲染成表"：表格会落到 [flushParagraph]，而它用
 * `" ".joinToString` + `Regex("\\s+")` 折叠换行 ⇒ **整张表变成一长条散文**，
 * 结构完全丢失（这比渲染成普通表格糟得多）。
 *
 * 现在判据是「本行**含** `|`（而非必须以它起头）+ 下一行是分隔行」。
 * 两点安全性说明：
 * -仍要求**下一行必须是分隔行** ⇒ 普通句子里的竖线不会被误判（这才是原注释担心的误伤）；
 * - 分隔行含 `-` 是必要条件，`---` 这类水平分隔线不含 `|` ⇒ 不会被当成表格。
 */
private fun parseTable(lines: List<String>, start: Int): Pair<MdBlock.Table, Int>? {
    if (start + 1 >= lines.size) return null
    if (!lines[start].contains('|')) return null
    if (!isTableDivider(lines[start + 1])) return null
    // 表头列数需 ≥2（单列不成表，否则「a | b」这种正文会被误收）
    val header = splitRow(lines[start])
    if (header.size < 2) return null

    val aligns = splitRow(lines[start + 1]).map { cell ->
        when {
            cell.startsWith(":") && cell.endsWith(":") -> 'c'
            cell.endsWith(":") -> 'r'
            else -> 'l'
        }
    }
    val rows = mutableListOf<List<String>>()
    var i = start + 2
    while (i < lines.size) {
        val l = lines[i].trim()
        // 数据行同样放宽为"含 |"（与表头一致），否则省略首尾竖线的表格只能显示表头
        if (l.isBlank() || !l.contains('|')) break
        rows += splitRow(l)
        i++
    }
    // 列数对齐：短行补空、长行截断（Markdown 表格的常识行为，否则渲染时越界）
    val cols = header.size
    val normalized = rows.map { r -> List(cols) { idx -> r.getOrNull(idx).orEmpty() } }
    return MdBlock.Table(header, aligns, normalized) to i
}

/**
 * 水平分隔线：`---` / `***` / `___`（≥3 个）。
 *
 * 三种写法都要认：模型分节时随手写的是其中任一种，
 * 而修复前的正则只匹配 `-{3,}` ⇒ `***` / `___` 会落到 Paragraph 显示成 `***`。
 */
private val HR_REGEX = Regex("^([-*_])(?:\\s*\\1){2,}$")

/** 列表层级封顶：畸形缩进（有人用 8 空格）不该把缩进撑成屏幕宽 */
private const val MAX_BULLET_LEVEL = 5

/** 每级缩进 14dp —— 够看出层级又不至于让深层列表只剩半行文字 */
private val BULLET_INDENT_DP = 14

/**
 * 构造列表项：算层级、剥任务列表标记。
 *
 * ## 为什么要按 [rawLine]（未 trim）算层级
 * 主循环为了匹配统一做了 `line = rawLine.trim()`，缩进在**进到这里之前**就丢了。
 * 所以层级必须回original 行去数前导空格/制表符 —— 这是唯一还留着缩进信息的地方。
 *
 * @param rest `Ordered`/`Bullet` 捕获的内容（可能带 `- [x] ` 前缀）
 */
private fun bulletOf(
    rawLine: String,
    line: String,
    ordered: Boolean,
    index: Int,
    rest: String,
): MdBlock.Bullet {
    // 层级：制表符按 2 倍算（与主流编辑器一致），2 空格一级，封顶 MAX_BULLET_LEVEL
    val ws = rawLine.takeWhile { it == ' ' || it == '\t' }
    var spaces = 0
    for (c in ws) spaces += if (c == '\t') 2 else 1
    val level = (spaces / 2).coerceIn(0, MAX_BULLET_LEVEL)

    // 任务列表：`- [x]` / `- [ ]`（大小写都收）。剥掉标记，raw 只留正文。
    val task = Regex("^\\[([ xX])]\\s+(.*)$").matchEntire(rest.trim())
    return MdBlock.Bullet(
        ordered = ordered,
        index = index,
        raw = if (task != null) task.groupValues[2].trim() else rest.trim(),
        level = level,
        taskDone = task?.groupValues[1]?.lowercase()?.let { it == "x" },
    )
}

/**
 * 匹配一行独占的 Markdown 图片：`![alt](src)`。
 *
 * 只认**整行就是一张图**的形态（前面没有其他文字）—— 这是模型最常产出的写法
 * （图片独占一行），也是能安全渲染而不破坏段落结构的形态。
 * 行内混排的 `文字 ![图](url) 更多` 交给段落路径：那里 [INLINE_REGEX] 的负向断言
 * 会让它原样显示 `![图](url)`，至少用户认得出那是张图（而不是像修复前只剩 `!图`）。
 *
 * ⚠️ src 允许含 `,`（`[^)\s]+`）—— `data:image/png;base64,AAA` 这类内联图必须能收下。
 * 括号内的 URL（如 wiki 链接 `https://a/Foo_(bar)`）不匹配，会原样显示，不影响可读性。
 */
private fun matchImageLine(line: String): MdBlock.Image? {
    val m = Regex("^!\\[([^\\]]*)]\\(([^)\\s]+)\\)$").matchEntire(line) ?: return null
    val src = m.groupValues[2]
    // 明显不是图片地址的（空/纯锚点）不建卡，落回段落按文本处理
    if (src.isBlank() || src == "#") return null
    return MdBlock.Image(alt = m.groupValues[1].trim(), src = src)
}

/**
 * 行内标记：`code`、**bold**、*italic*、~~delete~~、[link](url)。
 *
 * ⚠️ 图片标记 `![alt](src)` **不在这里处理**（走块级 [MdBlock.Image]）——
 * 但仍要给链接分支加 `(?<!!)` 负向断言做**兜底**：一旦某张图片因流式未闭合等原因
 * 没能被块级分支接住（落到段落里），没有负向断言它就会被链接分支吃掉 label，
 * 退化成 `!alt`（与被修的 bug 同一个症状）。加上断言后至少原样显示 `![alt](url)`，
 * 用户认得出来那是张图片。
 *
 * 📌 负向断言放在分支**开头**（`(?<!!)`），而不是用 `(?<=\\!)` 之类——
 * 前者只需看前一个字符为空/非 `!`，不依赖匹配器已经消费过 `!`。
 */
private val INLINE_REGEX = Regex(
    "(`[^`\\n]+`)" +
        "|(\\*\\*[^*\\n]+\\*\\*|__[^_\\n]+__)" +
        "|(\\*[^*\\n]+\\*|_[^_\\n]+_)" +
        // 删除线 `~~text~~`：模型用来表示"作废/不再适用"，原样显示 `~~` 用户读不出语义
        "|~~([^~\\n]+)~~" +
        "|(?<!!)(\\[[^]]+]\\([^)\\s]+\\))",
)

/**
 * 行内标记 → 带标注的富文本。
 *
 * ## 链接为什么要挂 [URL_TAG] 标注（2026-10-06 审计修）
 * 原来 `token.startsWith("[")` 分支只取label 染色，**URL 直接丢弃**：
 * 用户看到的是一段蓝色但点不动的文字，地址也没了 ⇒ 连选字复制都拿不到。
 * 现在把真实地址挂成 [StringAnnotation]，由 [ClickableMarkdown] 在点击时取出并打开。
 *
 * 📌 为什么不在这里直接开浏览器：[inlineAnnotated] 是**纯文本构建**（无 Composable 上下文），
 * 打开 URI 需要 `LocalUriHandler`（只能在 Composable 里读）。职责分开更好测。
 */
internal fun inlineAnnotated(raw: String, textColor: Color): AnnotatedString = buildAnnotatedString {
    var last = 0
    INLINE_REGEX.findAll(raw).forEach { m ->
        if (m.range.first > last) append(raw.substring(last, m.range.first))
        val token = m.value
        when {
            token.startsWith("`") -> withStyle(
                SpanStyle(
                    color = BrewChat,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    background = BrewChat.copy(alpha = 0.14f),
                ),
            ) { append(token.removeSurrounding("`")) }

            token.startsWith("**") || token.startsWith("__") -> withStyle(
                SpanStyle(color = textColor, fontWeight = FontWeight.SemiBold),
            ) { append(token.drop(2).dropLast(2)) }

            token.startsWith("*") || token.startsWith("_") -> withStyle(
                SpanStyle(color = textColor, fontWeight = FontWeight.Medium),
            ) { append(token.drop(1).dropLast(1)) }

            // 删除线：降低不透明度而非加删除线 —— 单色绿光波导上画横线会糊成一片，
            // "看起来淡一点"在这块屏上比"画一道横线"更可读（同眼镜屏的配色纪律）
            token.startsWith("~~") -> withStyle(
                SpanStyle(color = textColor.copy(alpha = 0.55f)),
            ) { append(token.drop(2).dropLast(2)) }

            token.startsWith("[") -> {
                // ★ 取出真实 URL 挂标注，而不是只留 label（原先 URL 直接丢失）
                val label = token.substringAfter('[').substringBefore(']')
                val url = Regex("\\(([^)\\s]+)\\)$").find(token)?.groupValues?.get(1).orEmpty()
                pushStringAnnotation(URL_TAG, url)
                withStyle(SpanStyle(color = BrewChat, textDecoration = TextDecoration.Underline)) {
                    append(if (label.isBlank()) url else label)
                }
            }
        }
        last = m.range.last + 1
    }
    if (last < raw.length) append(raw.substring(last))
}

/** 链接 URL 在 [AnnotatedString] 里的标注 tag（配合 [ClickableMarkdown] 使用） */
internal const val URL_TAG = "url"

/**
 * 裸 URL 自动链接化：模型很爱直接输出 `https://…`（不带 markdown 语法），
 * 而它原先完全不被识别、只是一串灰色不可点文本。
 *
 * 📌 尾随标点要剥离：中文句子里常写成 `见 https://x.com。`，
 * 直接把 `。` 吃进 URL 会导致打开失败。
 */
private val BARE_URL_REGEX = Regex("https?://[^\\s，。！？；、）】」』]+")

/**
 * 带点击能力的富文本渲染：URL 挂标注 ⇒ 可点。
 *
 * 用 [ClickableText] 而不是纯 Text：它会消费 `pushStringAnnotation` 的标注并回调
 * `onClick`。没有 URL 标注时不额外包一层（省一次布局）。
 */
@Composable
internal fun ClickableMarkdown(
    text: String,
    textColor: Color,
    modifier: Modifier = Modifier,
) {
    val annotated = remember(text, textColor) {
        // ① 裸 URL 先包成 markdown 链接语法（顺序不能反：反过来 markdown 语法会被 URL 正则吃掉）
        val linked = BARE_URL_REGEX.replace(text) { m -> "[${m.value}](${m.value})" }
        // ② 再走行内标记解析（此时裸 URL 已变成标准链接，会被正确挂上标注）
        inlineAnnotated(linked, textColor)
    }
    // 📌 判"有没有链接"必须用公开的 [AnnotatedString.getStringAnnotations]：
    // `annotations` 属性是 **internal**（Compose 库内部可见），本模块访问不到。
    val hasLink = remember(annotated) {
        annotated.getStringAnnotations(URL_TAG, 0, annotated.length).isNotEmpty()
    }
    if (!hasLink) {
        Text(text = annotated, color = textColor, fontSize = 15.sp, lineHeight = 22.sp, modifier = modifier)
        return
    }
    val uriHandler = LocalUriHandler.current
    ClickableText(
        text = annotated,
        modifier = modifier,
        style = TextStyle(color = textColor, fontSize = 15.sp, lineHeight = 22.sp),
        onClick = { offset ->
            annotated.getStringAnnotations(URL_TAG, offset, offset).firstOrNull()?.let { anno ->
                runCatching { uriHandler.openUri(anno.item) }
            }
            null
        },
    )
}

/**
 * 聊天气泡正文：段落 / 标题 / 列表走 [AnnotatedString]，围栏代码块走 [CodeBlockCard]。
 * 整体包在 [SelectionContainer] 里，长按可选字；代码块自带复制按钮，不套选择容器。
 */
@Composable
internal fun ChatMarkdownBody(
    text: String,
    textColor: Color,
    modifier: Modifier = Modifier,
) {
    val blocks = remember(text) { parseChatMarkdown(text) }
    SelectionContainer {
        Column(modifier = modifier.fillMaxWidth()) {
            blocks.forEachIndexed { index, block ->
                val topGap = when {
                    index == 0 -> 0.dp
                    block is MdBlock.Heading -> 10.dp
                    block is MdBlock.Code -> 6.dp
                    block is MdBlock.Table -> 8.dp
                    block is MdBlock.Formula -> 8.dp
                    block is MdBlock.Diagram -> 8.dp
                    block is MdBlock.Chart -> 8.dp
                    block is MdBlock.Image -> 8.dp
                    block is MdBlock.Divider -> 10.dp
                    block is MdBlock.Quote -> 8.dp
                    block is MdBlock.Bullet -> 3.dp
                    else -> 6.dp
                }
                Spacer(Modifier.height(topGap))
                when (block) {
                    is MdBlock.Heading -> Text(
                        text = inlineAnnotated(block.raw, BrewTextBright),
                        color = BrewTextBright,
                        fontSize = when (block.level) {
                            1 -> 17.sp
                            2 -> 16.sp
                            else -> 15.sp
                        },
                        lineHeight = 22.sp,
                        fontWeight = FontWeight.SemiBold,
                    )

                    // ★ 可点击版（2026-10-06 审计修）：链接 URL 不再丢失，裸 URL 也能点。
                    //没有 URL 时 [ClickableMarkdown] 内部退化为普通 Text，零额外开销。
                    is MdBlock.Paragraph -> ClickableMarkdown(
                        text = block.raw,
                        textColor = textColor,
                    )

                    is MdBlock.Bullet -> BulletLine(block, textColor)
                    is MdBlock.Code -> CodeBlockCard(langRaw = block.lang, code = block.code)
                    is MdBlock.Table -> MarkdownTableCard(block)
                    is MdBlock.Formula -> WebRenderCard(
                        kind = WebRenderKind.FORMULA,
                        source = block.tex,
                    )

                    is MdBlock.Diagram -> WebRenderCard(
                        kind = WebRenderKind.MERMAID,
                        source = block.code,
                    )

                    is MdBlock.Chart -> WebRenderCard(
                        kind = WebRenderKind.CHART,
                        source = block.code,
                    )

                    // 引用块：左侧一条竖线 + 淡色斜体，引用语义一眼可辨
                    //（修复前无此分支：`>` 符号原样显示，且多行引用被 flushParagraph 折成一行）
                    is MdBlock.Quote -> Row(modifier = Modifier.fillMaxWidth()) {
                        Box(
                            modifier = Modifier
                                .width(2.dp)
                                .heightIn(min = 16.dp)
                                .clip(RoundedCornerShape(1.dp))
                                .background(BrewMuted.copy(alpha = 0.5f)),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = inlineAnnotated(
                                block.lines.joinToString("\n"),
                                textColor.copy(alpha = 0.85f),
                            ),
                            color = textColor.copy(alpha = 0.85f),
                            fontSize = 14.sp,
                            lineHeight = 20.sp,
                            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                        )
                    }

                    // 水平分隔线：一条细线（修复前是静默丢弃，长回答会"糊成一片"）
                    is MdBlock.Divider -> Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                            .height(1.dp)
                            .background(BrewBorder),
                    )

                    // Markdown 图片：复用与"图片消息"完全相同的卡片（下载/预览/保存一套逻辑），
                    // 免得同一个 URL 在两条路径下表现不一致。
                    // alt 渲染成一行小字说明 —— 模型给的 alt 常常就是图注。
                    is MdBlock.Image -> {
                        ChatImageCard(block.src)
                        if (block.alt.isNotBlank()) {
                            Text(
                                text = inlineAnnotated(block.alt, BrewMuted),
                                color = BrewMuted,
                                fontSize = 12.sp,
                                lineHeight = 16.sp,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 列表项一行：缩进 + 项目符号 + 正文。
 *
 * ★ [MdBlock.Bullet.level] 与 [taskDone] 是 2026-10-06 审计加的：
 * 修复前既没有层级（缩进在 trim 时被丢）也没有任务列表（`- [x]` 的 `[x]` 原样显示），
 * 多级列表渲染成平铺的一串，用户看不出从属关系。
 */
@Composable
private fun BulletLine(block: MdBlock.Bullet, textColor: Color) {
    // ★ 层级缩进：每级 [BULLET_INDENT_DP]，封顶由解析侧（MAX_BULLET_LEVEL）保证
    val indent = (block.level * BULLET_INDENT_DP).dp
    androidx.compose.foundation.layout.Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = indent),
    ) {
        // 任务列表：勾选框替代圆点（只做视觉呈现，不可点 —— 模型没给勾选态的交互语义）
        val done = block.taskDone
        if (done != null) {
            Box(
                modifier = Modifier
                    .padding(top = 3.dp)
                    .size(12.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(if (done) BrewChat else Color.Transparent)
                    .border(
                        width = 1.dp,
                        color = if (done) BrewChat else BrewMuted,
                        shape = RoundedCornerShape(3.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (done) {
                    Text(
                        text = "\u2713",
                        color = BrewBg,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
        } else if (block.ordered) {
            Text(
                text = "${block.index}.",
                color = BrewChat,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.width(20.dp),
            )
            Spacer(Modifier.width(2.dp))
        } else {
            Box(
                modifier = Modifier
                    .padding(top = 9.dp)
                    .size(5.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (block.level > 0) BrewMuted else BrewChat),
            )
            Spacer(Modifier.width(8.dp))
        }
        // 列表项里也可能有链接（"详见 [文档](url)"），同样走可点击版
        ClickableMarkdown(
            text = block.raw,
            textColor = textColor,
            modifier = Modifier.weight(1f),
        )
    }
}
