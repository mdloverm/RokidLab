package com.rokidlab.phone.store

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rokidlab.phone.R
import com.rokidlab.phone.design.BrewBorder
import com.rokidlab.phone.design.BrewChat
import com.rokidlab.phone.design.BrewMuted
import com.rokidlab.phone.design.BrewPanel
import com.rokidlab.phone.design.BrewPanelAlt
import com.rokidlab.phone.design.BrewTextBright
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 聊天文件卡（只读）—— 与 [ChatImageCard] / [CodeBlockCard] 同一套卡片语言。
 *
 * 存在的理由：在此之前，用户上传的文件或 AI 用 `run_shell` 写出的文件，在对话列表里
 * **完全看不出来** —— 前者只剩一句文字，后者只在「过程」卡里留一行命令。
 * 用户想核对"到底发了/生成了什么"必须离开聊天页去文件管理器翻。
 *
 * 设计取舍（都是刻意的）：
 *  - **只读**：不做就地编辑。正文在磁盘上，就地编辑要处理落盘/原子写/冲突，
 *    而用户真要改内容时用文件管理器更顺手；
 *  - **懒读 + 有上限**：收起态只显示一行摘要 + 一行内容预览，展开才整篇读
 *    （且封顶 [MAX_RENDER_LINES] 行），否则点开一个几万行的日志会把列表卡死；
 *  - **失败如实说**：文件被删/被移走时显示"文件已不在"，而不是空白卡片。
 */
@Composable
internal fun ChatFileCard(path: String, name: String, chars: Int?) {
    // 音视频分流：文本预览对媒体文件毫无意义（解出来是乱码），走播放卡
    if (isMediaFile(name)) {
        ChatMediaCard(path = path, name = name)
        return
    }
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var expanded by remember(path) { mutableStateOf(false) }
    var content by remember(path) { mutableStateOf<String?>(null) }
    var missing by remember(path) { mutableStateOf(false) }

    LaunchedEffect(path) {
        val text = withContext(Dispatchers.IO) {
            runCatching {
                val f = File(path)
                if (!f.exists()) null else f.readText()
            }.getOrNull()
        }
        missing = text == null
        content = text
    }

    val ext = ChatAttachments.extOf(name).uppercase().ifBlank {
        stringResource(R.string.chat_file_kind_text)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(BrewPanel)
            .border(1.dp, BrewBorder, RoundedCornerShape(10.dp)),
    ) {
        // ── 标题栏（与图片卡/代码卡同一形态：类型标签 + 右侧图标动作）──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(34.dp)
                .background(BrewChat.copy(alpha = 0.12f))
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Description,
                contentDescription = null,
                tint = BrewChat,
                modifier = Modifier.size(15.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = ext,
                color = BrewChat,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.8.sp,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = name,
                color = BrewMuted,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            // 复制：只在内容读到了才可用
            Icon(
                imageVector = Icons.Outlined.ContentCopy,
                contentDescription = stringResource(R.string.chat_file_copy),
                tint = if (content != null) BrewMuted else BrewMuted.copy(alpha = 0.35f),
                modifier = Modifier
                    .size(16.dp)
                    .clickable(enabled = content != null) {
                        content?.let {
                            clipboard.setText(AnnotatedString(it))
                            Toast.makeText(ctx, R.string.chat_file_copied, Toast.LENGTH_SHORT).show()
                        }
                    },
            )
            Spacer(Modifier.width(10.dp))
            // 展开/收起：内容为空（二进制/空文件）时不给这个入口
            Icon(
                imageVector = if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = stringResource(
                    if (expanded) R.string.chat_file_collapse else R.string.chat_file_expand,
                ),
                tint = if (content != null) BrewMuted else BrewMuted.copy(alpha = 0.35f),
                modifier = Modifier
                    .size(16.dp)
                    .clickable(enabled = content != null) { expanded = !expanded },
            )
        }

        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(BrewBorder),
        )

        // ── 摘要行（始终可见）：字数 / 状态 ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(BrewPanelAlt)
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = when {
                    missing -> stringResource(R.string.chat_file_missing)
                    chars != null && chars > 0 -> stringResource(R.string.chat_attach_chars, chars)
                    content != null -> stringResource(R.string.chat_attach_chars, content!!.length)
                    else -> stringResource(R.string.chat_file_reading)
                },
                color = if (missing) BrewMuted else BrewTextBright,
                fontSize = 11.sp,
            )
            Spacer(Modifier.weight(1f))
            if (content != null && !expanded) {
                Text(
                    // 收起态给一行内容预览，用户不必展开就知道这是不是他要的那份东西
                    text = content!!.lineSequence().firstOrNull().orEmpty().trim(),
                    color = BrewMuted,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(3f),
                )
            }
        }

        // ── 正文（展开态）：限高 + 内部滚动，避免长文件把对话列表撑开几千行 ──
        val shown = content
        if (expanded && shown != null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                Text(
                    text = shown.lineSequence().take(MAX_RENDER_LINES).joinToString("\n"),
                    color = BrewTextBright,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                )
                if (shown.lineSequence().count() > MAX_RENDER_LINES) {
                    Text(
                        text = stringResource(R.string.chat_file_truncated_view),
                        color = BrewMuted,
                        fontSize = 10.sp,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
        }
    }
}

/** 收起态预览行数无关；展开态最多渲染这么多行（再多就让用户去文件管理器看） */
private const val MAX_RENDER_LINES = 400
