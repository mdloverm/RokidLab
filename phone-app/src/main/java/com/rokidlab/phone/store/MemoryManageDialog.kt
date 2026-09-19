package com.rokidlab.phone.store

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.rokidlab.phone.R
import com.rokidlab.phone.ai.LongTermMemoryManager
import com.rokidlab.phone.design.BrewBorder
import com.rokidlab.phone.design.BrewChat
import com.rokidlab.phone.design.BrewMuted
import com.rokidlab.phone.design.BrewPanel
import com.rokidlab.phone.design.BrewRed
import com.rokidlab.phone.design.BrewTextBright
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 长期记忆管理（方案 §4.3.3a）。
 *
 * ★ 为什么这是一个**信任基建**而不是一个便利功能：长期记忆是"乐奇替用户记住的事"，
 *   而在此之前用户**看不见它记了什么、改不了记错的内容、也删不掉单条**。
 *   一个无法核对与纠正的记忆系统，用户只敢关掉它 —— 那等于这个能力白做。
 *
 * 三条能力对应三种用户诉求：
 *  - **看**（列表）："它到底记了我什么？"
 *  - **改**（编辑单条）："我不喜欢周杰伦了" —— 这是用户唯一能纠正错话的方式，
 *    在此之前只能引导模型调用 `manage_memory`，而模型未必听；
 *  - **删**（单条删除 + 清空）："这条不该被记住"（清空在 `AgentSectionPage` 上）。
 *
 * ⚠️ 列表读取走 IO 线程：SQLite 读是阻塞调用，直接在组合里读会把主线程卡在
 *    "打开数据库 + 惰性清理过期条目"上（`AgentSectionPage` 现有那两处同步读是历史写法，
 *    新代码不再沿用）。
 */
@Composable
internal fun MemoryManageDialog(
    onDismiss: () -> Unit,
    /** 记忆被增删改后回调，让调用方刷新"已记住 N 条" */
    onChanged: () -> Unit,
) {
    val ctx = LocalContext.current
    var memories by remember { mutableStateOf<List<String>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    /** 正在编辑的编号（1 起）；0 = 不在编辑态 */
    var editingIndex by remember { mutableIntStateOf(0) }
    var draft by remember { mutableStateOf("") }
    /** 自增即触发重新读库（增删改之后） */
    var refresh by remember { mutableIntStateOf(0) }

    LaunchedEffect(refresh) {
        loading = true
        memories = withContext(Dispatchers.IO) {
            runCatching { LongTermMemoryManager.items(ctx) }.getOrDefault(emptyList())
        }
        loading = false
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(BrewPanel)
                .border(1.dp, BrewBorder, RoundedCornerShape(16.dp))
                .padding(18.dp),
        ) {
            Text(
                text = stringResource(R.string.agent_longterm_manage_title),
                color = BrewTextBright,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.agent_longterm_manage_hint),
                color = BrewMuted,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
            Spacer(Modifier.height(12.dp))

            when {
                loading -> Text(
                    text = stringResource(R.string.agent_longterm_manage_loading),
                    color = BrewMuted,
                    fontSize = 12.sp,
                )

                memories.isEmpty() -> Text(
                    text = stringResource(R.string.agent_longterm_manage_empty),
                    color = BrewMuted,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                )

                else -> LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                    itemsIndexed(memories) { i, text ->
                        val index = i + 1
                        Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                            if (editingIndex == index) {
                                OutlinedTextField(
                                    value = draft,
                                    onValueChange = { draft = it },
                                    modifier = Modifier.fillMaxWidth(),
                                    textStyle = TextStyle(fontSize = 13.sp),
                                    maxLines = 4,
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End,
                                ) {
                                    TextButton(onClick = { editingIndex = 0 }) {
                                        Text(
                                            text = stringResource(R.string.chat_key_dialog_cancel),
                                            color = BrewMuted,
                                            fontSize = 12.sp,
                                        )
                                    }
                                    TextButton(onClick = {
                                        // 改不动时（空内容 / 与另一条完全重复）如实说，不假装成功
                                        if (LongTermMemoryManager.update(ctx, index, draft)) {
                                            Toast.makeText(
                                                ctx,
                                                ctx.getString(R.string.agent_longterm_updated),
                                                Toast.LENGTH_SHORT,
                                            ).show()
                                            editingIndex = 0
                                            refresh++
                                            onChanged()
                                        } else {
                                            Toast.makeText(
                                                ctx,
                                                ctx.getString(R.string.agent_longterm_update_failed),
                                                Toast.LENGTH_LONG,
                                            ).show()
                                        }
                                    }) {
                                        Text(
                                            text = stringResource(R.string.agent_longterm_save),
                                            color = BrewChat,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Medium,
                                        )
                                    }
                                }
                            } else {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "$index. $text",
                                        color = BrewTextBright,
                                        fontSize = 13.sp,
                                        lineHeight = 18.sp,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Icon(
                                        imageVector = Icons.Filled.Edit,
                                        contentDescription = stringResource(R.string.agent_longterm_item_edit),
                                        tint = BrewChat,
                                        modifier = Modifier
                                            .size(18.dp)
                                            .clickable {
                                                editingIndex = index
                                                draft = text
                                            },
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Icon(
                                        imageVector = Icons.Filled.Delete,
                                        contentDescription = stringResource(R.string.agent_longterm_item_delete),
                                        tint = BrewRed,
                                        modifier = Modifier
                                            .size(18.dp)
                                            .clickable {
                                                // 按编号删（LongTermMemoryManager.remove 的编号语义与上面列表一致）
                                                if (LongTermMemoryManager.remove(ctx, index.toString())) {
                                                    Toast.makeText(
                                                        ctx,
                                                        ctx.getString(R.string.agent_longterm_deleted),
                                                        Toast.LENGTH_SHORT,
                                                    ).show()
                                                    if (editingIndex == index) editingIndex = 0
                                                    refresh++
                                                    onChanged()
                                                }
                                            },
                                    )
                                }
                            }
                            HorizontalDivider(
                                color = BrewBorder.copy(alpha = 0.4f),
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) {
                    Text(
                        text = stringResource(R.string.agent_longterm_close),
                        color = BrewChat,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}
