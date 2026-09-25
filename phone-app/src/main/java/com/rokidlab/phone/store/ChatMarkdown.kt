package com.rokidlab.phone.store

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rokidlab.phone.design.BrewChat
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
    data class Bullet(val ordered: Boolean, val index: Int, val raw: String) : MdBlock
    data class Code(val lang: String, val code: String) : MdBlock

    /** Markdown 管道表格：首行为表头，[aligns] 与列一一对应（l/c/r，来自分隔行的 `:---:`） */
    data class Table(val header: List<String>, val aligns: List<Char>, val rows: List<List<String>>) : MdBlock

    /** 块级公式（`$$…$$` 或 `\[…\]`）：交给本地 KaTeX 排版 */
    data class Formula(val tex: String) : MdBlock

    /** Mermaid 图（```` ```mermaid ````）：交给本地 mermaid.js 渲染 */
    data class Diagram(val code: String) : MdBlock
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
            if (i < lines.size) i++
            // ```mermaid 走图表卡；其余语言仍走代码卡（含复制/运行）
            val body = code.joinToString("\n").trim('\n')
            if (lang == "mermaid") {
                blocks += MdBlock.Diagram(body)
            } else {
                blocks += MdBlock.Code(lang, body)
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

        // 管道表格：当前行以 | 起头、且**下一行是分隔行**（`|---|:--:|`）才认定是表格 ——
        // 只认「有 | 的行」会把普通句子里的竖线误判成表格
        if (line.startsWith("|")) {
            val table = parseTable(lines, i)
            if (table != null) {
                flushParagraph()
                blocks += table.first
                i = table.second
                continue
            }
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
            blocks += MdBlock.Bullet(true, ordered.groupValues[1].toIntOrNull() ?: 1, ordered.groupValues[2].trim())
            i++
            continue
        }

        val bullet = Regex("^[-*+]\\s+(.+)$").matchEntire(line)
        if (bullet != null) {
            flushParagraph()
            blocks += MdBlock.Bullet(false, 0, bullet.groupValues[1].trim())
            i++
            continue
        }

        // 水平分隔线：不渲染
        if (Regex("^-{3,}$").matches(line)) {
            flushParagraph()
            i++
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
 * 触发条件收紧到「本行以 | 起头 **且** 下一行是分隔行」——只看"这行有竖线"会把
 * 普通句子里的一根竖线判成表格，然后整段文字会以表格形态渲染出来。
 */
private fun parseTable(lines: List<String>, start: Int): Pair<MdBlock.Table, Int>? {
    if (start + 1 >= lines.size) return null
    if (!lines[start].trim().startsWith("|")) return null
    if (!isTableDivider(lines[start + 1])) return null

    val header = splitRow(lines[start])
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
        if (l.isBlank() || !l.startsWith("|")) break
        rows += splitRow(l)
        i++
    }
    // 列数对齐：短行补空、长行截断（Markdown 表格的常识行为，否则渲染时越界）
    val cols = header.size
    val normalized = rows.map { r -> List(cols) { idx -> r.getOrNull(idx).orEmpty() } }
    return MdBlock.Table(header, aligns, normalized) to i
}

/** 行内标记：`code`、**bold**、*italic*、[link](url)。图片标记在正文里不出现（走 imageUrl 卡片）。 */
private val INLINE_REGEX = Regex(
    "(`[^`\\n]+`)" +
        "|(\\*\\*[^*\\n]+\\*\\*|__[^_\\n]+__)" +
        "|(\\*[^*\\n]+\\*|_[^_\\n]+_)" +
        "|(\\[[^]]+]\\([^)\\s]+\\))",
)

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

            token.startsWith("[") -> {
                val label = token.substringAfter('[').substringBefore(']')
                withStyle(SpanStyle(color = BrewChat)) { append(label) }
            }
        }
        last = m.range.last + 1
    }
    if (last < raw.length) append(raw.substring(last))
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

                    is MdBlock.Paragraph -> Text(
                        text = inlineAnnotated(block.raw, textColor),
                        color = textColor,
                        fontSize = 15.sp,
                        lineHeight = 22.sp,
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
                }
            }
        }
    }
}

@Composable
private fun BulletLine(block: MdBlock.Bullet, textColor: Color) {
    androidx.compose.foundation.layout.Row(modifier = Modifier.fillMaxWidth()) {
        if (block.ordered) {
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
                    .background(BrewChat),
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = inlineAnnotated(block.raw, textColor),
            color = textColor,
            fontSize = 15.sp,
            lineHeight = 22.sp,
            modifier = Modifier.weight(1f),
        )
    }
}
