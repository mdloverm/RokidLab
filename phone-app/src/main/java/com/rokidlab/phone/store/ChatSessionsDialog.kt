package com.rokidlab.phone.store

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.rokidlab.phone.R
import com.rokidlab.phone.design.BrewAmber
import com.rokidlab.phone.design.BrewBg
import com.rokidlab.phone.design.BrewBorder
import com.rokidlab.phone.design.BrewChat
import com.rokidlab.phone.design.BrewDim
import com.rokidlab.phone.design.BrewMuted
import com.rokidlab.phone.design.BrewPanel
import com.rokidlab.phone.design.BrewPanelHi
import com.rokidlab.phone.design.BrewRed
import com.rokidlab.phone.design.BrewTextBright
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 会话列表（全屏对话）。
 *
 * 多会话改造前，乐奇聊天只有一份全局历史：想换话题只能「清空对话」，
 * 且不可逆。这里给出会话的增删改查入口 —— 也是 [ChatStateHolder.sessions] 的唯一 UI。
 *
 * 入口在 [ChatHeader] 的标题区（点击标题打开）：标题栏宽度实测只有 369dp，
 * 已被 5 个 IconButton 占满，再插一个按钮必然让副标题折行。
 */
@Composable
internal fun ChatSessionsDialog(
    onDismiss: () -> Unit,
    /**
     * 打开「对话记录图」（节点连接图 + 搜索 + 导出 + 逐条复制/编辑/删除）。
     *
     * 由调用方（`ChatScreen`）host 那个对话框而不是在这里 host：记录视图里的
     * 「编辑重发」要落到聊天页的**输入框**上，那是 ChatScreen 的状态。
     */
    onOpenRecords: (ChatSessionMeta) -> Unit = {},
    /** 编辑该会话的附加提示词（见 [ChatSessionMeta.systemPrompt]） */
    onEditPrompt: (ChatSessionMeta) -> Unit = {},
) {
    val sessions = ChatStateHolder.sessions
    val currentId = ChatStateHolder.currentSessionId

    var renaming by remember { mutableStateOf<ChatSessionMeta?>(null) }
    var deleting by remember { mutableStateOf<ChatSessionMeta?>(null) }

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
            // 顶栏：标题 + 新建 + 关闭
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(BrewPanel)
                    .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.chat_sessions_title),
                    color = BrewChat,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { ChatStateHolder.newSession() }) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = null,
                        tint = BrewChat,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.chat_sessions_new),
                        color = BrewChat,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.chat_sessions_close),
                        tint = BrewMuted,
                    )
                }
            }

            if (sessions.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.chat_sessions_empty),
                        color = BrewMuted,
                        fontSize = 13.sp,
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 12.dp,
                        vertical = 10.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(sessions, key = { it.id }) { s ->
                        SessionRow(
                            meta = s,
                            active = s.id == currentId,
                            onOpen = {
                                ChatStateHolder.switchTo(s.id)
                                onDismiss()
                            },
                            onRename = { renaming = s },
                            onDelete = { deleting = s },
                            // 按用户要求加在「删除」**后面**（原来删除是最后一个）
                            onOpenRecords = { onOpenRecords(s) },
                            onEditPrompt = { onEditPrompt(s) },
                        )
                    }
                }
            }
        }
    }

    renaming?.let { target ->
        RenameSessionDialog(
            initial = target.title,
            onConfirm = { name ->
                ChatStateHolder.renameSession(target.id, name)
                renaming = null
            },
            onDismiss = { renaming = null },
        )
    }

    deleting?.let { target ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.chat_sessions_delete_title)) },
            text = {
                Text(
                    stringResource(R.string.chat_sessions_delete_message, target.title),
                    fontSize = 13.sp,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        ChatStateHolder.deleteSession(target.id)
                        deleting = null
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = BrewRed,
                        contentColor = BrewBg,
                    ),
                ) {
                    Text(stringResource(R.string.chat_common_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { deleting = null }) {
                    Text(stringResource(R.string.chat_common_cancel))
                }
            },
        )
    }
}

/** 单条会话卡片 */
@Composable
private fun SessionRow(
    meta: ChatSessionMeta,
    active: Boolean,
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onOpenRecords: () -> Unit,
    onEditPrompt: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (active) BrewPanelHi else BrewPanel)
            .border(
                width = 1.dp,
                // 当前会话用聊天主色描边 + 左侧琥珀竖条双重标识：
                // 只靠底色深浅区分，在弱光/低对比屏上几乎看不出
                color = if (active) BrewChat.copy(alpha = 0.55f) else BrewBorder,
                shape = RoundedCornerShape(12.dp),
            )
            .clickable { onOpen() }
            .padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (active) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(BrewAmber),
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    text = meta.title,
                    color = BrewTextBright,
                    fontSize = 14.sp,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            val preview = meta.preview
            if (preview.isNotBlank()) {
                Text(
                    text = preview,
                    color = BrewMuted,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
            Text(
                text = stringResource(R.string.chat_sessions_count, meta.messageCount) +
                    " · " + formatSessionTime(meta.updatedAt),
                color = BrewDim,
                fontSize = 10.sp,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
        IconButton(onClick = onRename, modifier = Modifier.size(38.dp)) {
            Icon(
                imageVector = Icons.Filled.Edit,
                contentDescription = stringResource(R.string.chat_sessions_rename),
                tint = BrewMuted,
                modifier = Modifier.size(18.dp),
            )
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(38.dp)) {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = stringResource(R.string.chat_sessions_delete),
                tint = BrewMuted,
                modifier = Modifier.size(18.dp),
            )
        }
        // 「对话记录图」：把该会话的事件流画成节点连接图（对话内容 / 工具调用 / 执行过程），
        // 并提供搜索、导出 md、逐条复制/编辑/删除。图标用 AccountTree（真正的"节点树"语义）。
        IconButton(onClick = onOpenRecords, modifier = Modifier.size(38.dp)) {
            Icon(
                imageVector = Icons.Filled.AccountTree,
                contentDescription = stringResource(R.string.chat_sessions_records),
                tint = BrewMuted,
                modifier = Modifier.size(18.dp),
            )
        }
        // 「本会话提示词」：为该会话单独指定附加要求（追加在人设之后）
        IconButton(onClick = onEditPrompt, modifier = Modifier.size(38.dp)) {
            Icon(
                imageVector = Icons.Filled.Tune,
                contentDescription = stringResource(R.string.chat_sessions_prompt),
                tint = if (meta.systemPrompt.isNotBlank()) BrewChat else BrewMuted,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/**
 * 本会话提示词（[ChatSessionMeta.systemPrompt]）的编辑对话框。
 *
 * 明确写出"追加而非覆盖"：用户以为自己在改人设时可能写出"忽略以上所有指令"这类内容，
 * 而我们的实现是**追加**（保住工具使用准则），先说清楚比事后解释便宜。
 */
@Composable
internal fun SessionPromptDialog(
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_prompt_title)) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = false,
                    minLines = 3,
                    maxLines = 6,
                    placeholder = {
                        Text(
                            stringResource(R.string.chat_prompt_placeholder),
                            color = BrewMuted,
                            fontSize = 12.sp,
                        )
                    },
                    textStyle = TextStyle(color = BrewTextBright, fontSize = 14.sp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = BrewChat,
                        unfocusedBorderColor = BrewBorder,
                        focusedTextColor = BrewTextBright,
                        unfocusedTextColor = BrewTextBright,
                        cursorColor = BrewChat,
                    ),
                )
                Text(
                    text = stringResource(R.string.chat_prompt_hint),
                    color = BrewMuted,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(text) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = BrewChat,
                    contentColor = BrewBg,
                ),
            ) {
                Text(stringResource(R.string.chat_common_confirm))
            }
        },
        dismissButton = {
            Row {
                if (text.isNotBlank()) {
                    TextButton(onClick = { text = ""; onConfirm("") }) {
                        Text(stringResource(R.string.chat_prompt_clear), color = BrewRed)
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.chat_common_cancel))
                }
            }
        },
    )
}

/** 重命名对话框 */
@Composable
private fun RenameSessionDialog(
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_sessions_rename_title)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                label = {
                    Text(
                        stringResource(R.string.chat_sessions_rename_hint),
                        color = BrewMuted,
                        fontSize = 13.sp,
                    )
                },
                singleLine = true,
                textStyle = TextStyle(color = BrewTextBright, fontSize = 14.sp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onConfirm(text) }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = BrewChat,
                    unfocusedBorderColor = BrewBorder,
                    focusedTextColor = BrewTextBright,
                    unfocusedTextColor = BrewTextBright,
                    cursorColor = BrewChat,
                ),
            )
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(text) },
                colors = ButtonDefaults.buttonColors(
                    containerColor = BrewChat,
                    contentColor = BrewBg,
                ),
            ) {
                Text(stringResource(R.string.chat_common_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.chat_common_cancel))
            }
        },
    )
}

/**
 * 会话时间：今天只显示时分，今年显示月日，跨年才带年份 —— 列表信息密度有限，
 * 完整时间戳对"找上次那个对话"没有帮助。
 */
private fun formatSessionTime(ms: Long): String {
    if (ms <= 0L) return ""
    val target = Calendar.getInstance().apply { timeInMillis = ms }
    val now = Calendar.getInstance()
    val sameDay = target.get(Calendar.YEAR) == now.get(Calendar.YEAR) &&
        target.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)
    val pattern = when {
        sameDay -> "HH:mm"
        target.get(Calendar.YEAR) == now.get(Calendar.YEAR) -> "M月d日"
        else -> "yyyy年M月d日"
    }
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(ms))
}
