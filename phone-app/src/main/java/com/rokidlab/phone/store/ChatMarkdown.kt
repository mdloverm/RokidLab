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
            blocks += MdBlock.Code(lang, code.joinToString("\n").trim('\n'))
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
