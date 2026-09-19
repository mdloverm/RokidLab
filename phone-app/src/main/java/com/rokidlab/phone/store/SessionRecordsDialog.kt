package com.rokidlab.phone.store

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.rokidlab.phone.R
import com.rokidlab.phone.ai.AgentSessionManager
import com.rokidlab.phone.ai.session.SessionGraph
import com.rokidlab.phone.design.BrewAmber
import com.rokidlab.phone.design.BrewBg
import com.rokidlab.phone.design.BrewBorder
import com.rokidlab.phone.design.BrewChat
import com.rokidlab.phone.design.BrewDim
import com.rokidlab.phone.design.BrewMuted
import com.rokidlab.phone.design.BrewPanel
import com.rokidlab.phone.design.BrewPanelHi
import com.rokidlab.phone.design.BrewRed
import com.rokidlab.phone.design.BrewSuccess
import com.rokidlab.phone.design.BrewTextBright
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 对话记录视图 —— 会话事件流的**节点连接图**（用户要求的「显示对话的节点连接图」）。
 *
 * ★ 它和「执行轨迹」（`SessionTraceDialog`）的区别，是**同一份数据的两种切法**，
 *   刻意不合并：
 *   - 轨迹视图回答"这一轮**做**了什么"（按来源分类、面向用户复盘）；
 *   - 本视图回答"这个会话**记录**了什么"（按 seq 逐条、面向核账与导出）——
 *     它要把压缩、作废、孤儿工具调用这些**维护动作**也摆出来，
 *     还要能导出成文件、能逐条复制/编辑/删除。硬合成一个界面两边都不好用。
 *
 * ★ 布局口径：**竖向节点连接图**（主脊 + 工具分支），不是力导向二维布局 ——
 *   事件流本身是一条线性路径，力导向只会把一条直线随机摊开、还丢掉"先来后到"这个
 *   最重要的信息；手机是窄屏竖屏。配对关系除画分支外还标 `→#seq`，
 *   因为**跨节点的长连线在窄屏上多数情况会糊成一团**，标注反而更清楚。
 *
 * ⚠️ **编辑/删除只对当前会话开放**：它们作用在 UI 消息层（[ChatStateHolder.messages]），
 *   而"切到另一个会话"涉及异步加载与正在进行的对话，从一个浏览视图里发起风险远大于收益。
 *   非当前会话只给复制，并在动作条里说明原因（而不是给一个点了没反应的按钮）。
 */
@Composable
internal fun SessionRecordsDialog(
    meta: ChatSessionMeta,
    isCurrent: Boolean,
    onCopy: (String) -> Unit,
    /** 编辑重发：把这条消息交给聊天页的**输入框**（与点气泡同一条路径） */
    onEditMessage: (ChatMsg) -> Unit,
    onDeleteMessage: (ChatMsg) -> Unit,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    val listState = rememberLazyListState()
    var keyword by remember { mutableStateOf("") }
    var selectedSeq by remember { mutableStateOf<Long?>(null) }
    var expandedSeq by remember { mutableStateOf<Long?>(null) }

    val fullGraph = remember(meta.id) {
        runCatching { AgentSessionManager.graphOf(ctx, meta.id) }
            .getOrElse { SessionGraph.Graph(emptyList(), emptyList(), 0, 0) }
    }
    val graph = remember(fullGraph, keyword) { fullGraph.filter(keyword) }

    // 配对标注：call → result、result → call（图上除分支外还要能读出"这条结果属于哪次调用"）
    val pairLabel = remember(fullGraph) {
        val callToResult = HashMap<Long, Long>()
        val resultToCall = HashMap<Long, Long>()
        fullGraph.edges.filter { it.kind == SessionGraph.EdgeKind.TOOL_PAIR }.forEach { e ->
            callToResult[e.from] = e.to
            resultToCall[e.to] = e.from
        }
        callToResult to resultToCall
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(BrewBg)
                .systemBarsPadding(),
        ) {
            // ── 顶栏：标题 + 导出 + 关闭 ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(BrewPanel)
                    .padding(start = 16.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.chat_records_title),
                        color = BrewChat,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = meta.title,
                        color = BrewMuted,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                TextButton(onClick = {
                    val r = runCatching {
                        exportMarkdown(
                            ctx = ctx,
                            title = meta.title,
                            content = AgentSessionManager.markdownOf(ctx, meta.id, meta.title),
                        )
                    }
                    val msg = r.fold(
                        onSuccess = { ctx.getString(R.string.chat_records_export_done, it) },
                        onFailure = { ctx.getString(R.string.chat_records_export_failed, it.message ?: "?") },
                    )
                    Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
                }) {
                    Text(
                        text = stringResource(R.string.chat_records_export),
                        color = BrewChat,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.chat_records_close),
                        tint = BrewMuted,
                    )
                }
            }

            // ── 摘要 + 搜索 ──
            Column(modifier = Modifier.fillMaxWidth().background(BrewPanel).padding(
                start = 16.dp, end = 16.dp, bottom = 10.dp,
            )) {
                Text(
                    text = stringResource(
                        R.string.chat_records_summary,
                        fullGraph.nodes.mapNotNull { it.turn }.distinct().size,
                        fullGraph.nodes.count { it.kind == SessionGraph.Kind.TOOL_CALL },
                        fullGraph.nodes.size,
                    ),
                    color = BrewDim,
                    fontSize = 11.sp,
                )
                if (fullGraph.shadowedCount > 0 || fullGraph.cutCount > 0) {
                    Text(
                        text = stringResource(
                            R.string.chat_records_hidden_note,
                            fullGraph.shadowedCount,
                            fullGraph.cutCount,
                        ),
                        color = BrewDim,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                if (fullGraph.orphanCalls.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.chat_records_orphan_note, fullGraph.orphanCalls.size),
                        color = BrewRed,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                BasicSearchField(
                    value = keyword,
                    onValueChange = { keyword = it },
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            when {
                fullGraph.nodes.isEmpty() -> CenterHint(
                    stringResource(R.string.chat_records_empty),
                    Modifier.weight(1f),
                )

                graph.nodes.isEmpty() -> CenterHint(
                    stringResource(R.string.chat_records_no_match, keyword),
                    Modifier.weight(1f),
                )
                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(graph.nodes, key = { it.seq }) { node ->
                        val (callToResult, resultToCall) = pairLabel
                        RecordNodeRow(
                            node = node,
                            linkedSeq = callToResult[node.seq] ?: resultToCall[node.seq],
                            linkedIsResult = resultToCall.containsKey(node.seq),
                            expanded = expandedSeq == node.seq,
                            selected = selectedSeq == node.seq,
                            onToggle = { expandedSeq = if (expandedSeq == node.seq) null else node.seq },
                            onSelect = { selectedSeq = if (selectedSeq == node.seq) null else node.seq },
                        )
                    }
                }
            }

            // ── 动作条：只在选中某个节点时出现 ──
            selectedSeq?.let { seq ->
                val node = graph.nodes.firstOrNull { it.seq == seq }
                if (node != null) {
                    NodeActionBar(
                        node = node,
                        isCurrent = isCurrent,
                        onCopy = {
                            onCopy(node.detail.ifBlank { node.title })
                            selectedSeq = null
                        },
                        onEditMessage = {
                            val msg = node.turn?.let { ChatStateHolder.messageOfTurn(it, node.isUserMessage) }
                            if (msg != null) {
                                onEditMessage(msg)
                                selectedSeq = null
                            } else {
                                Toast.makeText(
                                    ctx,
                                    ctx.getString(R.string.chat_records_no_edit),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        },
                        onDeleteMessage = {
                            val msg = node.turn?.let { ChatStateHolder.messageOfTurn(it, node.isUserMessage) }
                            if (msg != null) {
                                onDeleteMessage(msg)
                                selectedSeq = null
                            } else {
                                Toast.makeText(
                                    ctx,
                                    ctx.getString(R.string.chat_records_no_edit),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun CenterHint(text: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Text(text = text, color = BrewMuted, fontSize = 13.sp, modifier = Modifier.padding(28.dp))
    }
}

@Composable
private fun BasicSearchField(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier) {
    androidx.compose.foundation.text.BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(BrewBg)
            .border(1.dp, BrewBorder, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 7.dp),
        textStyle = TextStyle(color = BrewTextBright, fontSize = 13.sp),
        cursorBrush = androidx.compose.ui.graphics.SolidColor(BrewChat),
        singleLine = true,
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) {
                    Text(
                        text = stringResource(R.string.chat_records_search_hint),
                        color = BrewMuted,
                        fontSize = 13.sp,
                    )
                }
                inner()
            }
        },
    )
}

/**
 * 一个节点：**左侧图栏**（主脊竖线 + 节点圆点；工具类节点用肘形分支挂在主脊上）+ 右侧内容。
 *
 * 用 `IntrinsicSize.Min` + `fillMaxHeight` 让图栏跟着内容高度走 ——
 * 固定高度会让长内容（工具返回可能十几行）的连线断开，看起来就不像"连着"了。
 */
@Composable
private fun RecordNodeRow(
    node: SessionGraph.Node,
    linkedSeq: Long?,
    linkedIsResult: Boolean,
    expanded: Boolean,
    selected: Boolean,
    onToggle: () -> Unit,
    onSelect: () -> Unit,
) {
    val color = kindColor(node.kind)
    val branch = node.kind == SessionGraph.Kind.TOOL_CALL || node.kind == SessionGraph.Kind.TOOL_RESULT
    val alpha = if (node.excludedFromContext) 0.45f else 1f

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) BrewPanelHi else Color.Transparent)
            .clickable { onSelect() },
    ) {
        // ── 图栏 ──
        Box(
            modifier = Modifier
                .width(if (branch) 34.dp else 20.dp)
                .fillMaxHeight(),
        ) {
            // 主脊竖线（工具节点的肘形分支也从它引出）
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 8.dp)
                    .width(2.dp)
                    .fillMaxHeight()
                    .background(BrewBorder.copy(alpha = alpha)),
            )
            if (branch) {
                // 肘形：从主脊水平引到节点
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 8.dp)
                        .width(16.dp)
                        .height(2.dp)
                        .background(color.copy(alpha = alpha)),
                )
            }
            Box(
                modifier = Modifier
                    .align(if (branch) Alignment.CenterEnd else Alignment.Center)
                    .size(if (branch) 9.dp else 7.dp)
                    .clip(CircleShape)
                    .background(color.copy(alpha = alpha)),
            )
        }

        // ── 内容 ──
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 2.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "#" + node.seq,
                    color = BrewDim,
                    fontSize = 10.sp,
                )
                node.turn?.let {
                    Spacer(Modifier.width(5.dp))
                    Text(text = "第 $it 轮", color = BrewDim, fontSize = 10.sp)
                }
                if (node.excludedFromContext) {
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = "不进上下文",
                        color = BrewRed.copy(alpha = 0.8f),
                        fontSize = 10.sp,
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = hhmmss(node.ts),
                    color = BrewDim,
                    fontSize = 10.sp,
                )
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = stringResource(
                        if (expanded) R.string.chat_records_detail_hide else R.string.chat_records_detail_show
                    ),
                    tint = BrewMuted,
                    modifier = Modifier
                        .padding(start = 4.dp)
                        .size(14.dp)
                        .clickable { onToggle() },
                )
            }
            Text(
                text = node.title,
                color = if (node.excludedFromContext) BrewMuted else BrewTextBright,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                maxLines = if (expanded) 40 else 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 3.dp),
            )
            // 配对标注：窄屏上跨节点画长连线会糊成一团，用标注说清"这条属于哪次调用"
            linkedSeq?.let {
                Text(
                    text = if (linkedIsResult) "← 调用于 #$it" else "→ 结果在 #$it",
                    color = color.copy(alpha = 0.85f),
                    fontSize = 10.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            } ?: run {
                if (node.kind == SessionGraph.Kind.TOOL_CALL) {
                    Text(
                        text = "→ 没有结果（可能崩溃或被中断）",
                        color = BrewRed.copy(alpha = 0.85f),
                        fontSize = 10.sp,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            if (expanded && node.detail.isNotBlank()) {
                Text(
                    text = node.detail,
                    color = BrewMuted,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    modifier = Modifier
                        .padding(top = 5.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(BrewPanel)
                        .padding(8.dp),
                )
            }
        }
    }
}

@Composable
private fun NodeActionBar(
    node: SessionGraph.Node,
    isCurrent: Boolean,
    onCopy: () -> Unit,
    onEditMessage: () -> Unit,
    onDeleteMessage: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(BrewPanel)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            text = "#" + node.seq + " " + node.title,
            color = BrewMuted,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onCopy) {
                Icon(Icons.Filled.ContentCopy, contentDescription = null, tint = BrewChat, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(4.dp))
                Text(stringResource(R.string.chat_records_copy), color = BrewChat, fontSize = 13.sp)
            }
            // 编辑/删除只对**消息类**节点且**当前会话**开放 —— 理由见文件头
            if (node.isMessage && isCurrent) {
                if (node.isUserMessage) {
                    TextButton(onClick = onEditMessage) {
                        Icon(Icons.Filled.Edit, contentDescription = null, tint = BrewAmber, modifier = Modifier.size(15.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.chat_records_edit), color = BrewAmber, fontSize = 13.sp)
                    }
                }
                TextButton(onClick = onDeleteMessage) {
                    Icon(Icons.Filled.Delete, contentDescription = null, tint = BrewRed, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.chat_records_delete), color = BrewRed, fontSize = 13.sp)
                }
            } else if (node.isMessage) {
                Text(
                    text = stringResource(R.string.chat_records_no_edit),
                    color = BrewDim,
                    fontSize = 11.sp,
                    maxLines = 2,
                )
            }
        }
    }
}

private fun kindColor(kind: SessionGraph.Kind): Color = when (kind) {
    SessionGraph.Kind.USER -> BrewChat
    SessionGraph.Kind.ASSISTANT -> BrewSuccess
    SessionGraph.Kind.TOOL_CALL -> BrewAmber
    SessionGraph.Kind.TOOL_RESULT -> BrewAmber
    SessionGraph.Kind.CONTEXT -> BrewMuted
    SessionGraph.Kind.ATTEMPT -> BrewRed
    SessionGraph.Kind.COMPACTION -> BrewDim
    SessionGraph.Kind.CUT -> BrewRed
    SessionGraph.Kind.LIFECYCLE -> BrewBorder
}

private fun hhmmss(ts: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(ts))

/**
 * 导出 Markdown 到系统**下载目录**。
 *
 * 用 `MediaStore.Downloads`（minSdk 29）而不是 `File(Environment.getExternalStoragePublicDirectory…)`：
 * 后者在 Android 10+ 的分区存储下要么需要 legacy 开关、要么直接写不进去，
 * 而 MediaStore 是**无需权限**、用户在"文件/下载"里能直接看到的正路。
 *
 * @return 相对路径（用于提示文案）
 */
private fun exportMarkdown(ctx: Context, title: String, content: String): String {
    val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.getDefault()).format(Date())
    val safe = title.replace(Regex("[\\\\/:*?\"<>|\\s]+"), "-").take(24).ifBlank { "chat" }
    val fileName = "leqi-$safe-$stamp.md"
    val values = ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, fileName)
        put(MediaStore.Downloads.MIME_TYPE, "text/markdown")
        put(MediaStore.Downloads.IS_PENDING, 1)
    }
    val resolver = ctx.contentResolver
    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        ?: throw IllegalStateException("无法在下载目录创建文件")
    resolver.openOutputStream(uri)?.use { it.write(content.toByteArray(Charsets.UTF_8)) }
        ?: throw IllegalStateException("无法写入下载目录")
    values.clear()
    values.put(MediaStore.Downloads.IS_PENDING, 0)
    resolver.update(uri, values, null, null)
    Log.i("SessionRecords", "exported $fileName (${content.length} chars)")
    val dir = Environment.DIRECTORY_DOWNLOADS
    return "$dir/$fileName"
}
