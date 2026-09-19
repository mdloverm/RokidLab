package com.rokidlab.phone.store

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.rokidlab.phone.R
import com.rokidlab.phone.design.BrewBorder
import com.rokidlab.phone.design.BrewMuted
import com.rokidlab.phone.design.BrewPanel
import com.rokidlab.phone.design.BrewRed
import com.rokidlab.phone.design.BrewTextBright

/**
 * 消息操作菜单。
 *
 * Agent 场景比闲聊更易出错（工具选错、参数错、多步跑偏），而此前用户唯一的补救
 * 是把问题重打一遍。这里给出四个动作：复制 / 编辑重发 / 重新生成 / 删除。
 *
 * 选项按消息类型收敛 —— 对用户消息显示「编辑重发」，对 AI 消息显示「重新生成」，
 * 两者不共存；这样每屏最多 3 项，不需要二级菜单。
 *
 * @param canRegenerate 仅当这条是**最后一条 AI 消息**时为 true：重新生成依赖"删掉它再问一次
 *   上一条用户消息"，中间的消息会让这个语义不成立
 */
@Composable
internal fun ChatMessageActionsDialog(
    msg: ChatMsg,
    canRegenerate: Boolean,
    onCopy: () -> Unit,
    onEdit: () -> Unit,
    onRegenerate: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(BrewPanel)
                .border(1.dp, BrewBorder, RoundedCornerShape(16.dp))
                .padding(vertical = 8.dp),
        ) {
            Text(
                text = stringResource(R.string.chat_msg_actions),
                color = BrewMuted,
                fontSize = 11.sp,
                modifier = Modifier.padding(start = 16.dp, top = 6.dp, bottom = 6.dp),
            )
            ActionItem(
                icon = Icons.Filled.ContentCopy,
                label = stringResource(R.string.chat_msg_copy),
                onClick = onCopy,
            )
            if (msg.isUser) {
                ActionItem(
                    icon = Icons.Filled.Edit,
                    label = stringResource(R.string.chat_msg_edit),
                    onClick = onEdit,
                )
            } else if (canRegenerate) {
                ActionItem(
                    icon = Icons.Filled.Refresh,
                    label = stringResource(R.string.chat_msg_regenerate),
                    onClick = onRegenerate,
                )
            }
            ActionItem(
                icon = Icons.Filled.Delete,
                label = stringResource(R.string.chat_msg_delete),
                danger = true,
                onClick = onDelete,
            )
        }
    }
}

/** 菜单项（图标 + 文字，整行可点） */
@Composable
private fun ActionItem(
    icon: ImageVector,
    label: String,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    val tint = if (danger) BrewRed else BrewTextBright
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = label,
            color = tint,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

// 注：原先这里有一个 `EditMessageDialog`（独立弹窗里改用户消息）。**已删除** ——
// 编辑重发现在**就地发生在底部输入框**（`ChatScreen.startEdit` / `cancelEdit` + 输入框上方的
// 编辑横幅），理由见 `startEdit` 的注释：编辑与"重新说一遍"是同一次输入动作，
// 另开窗口还要在两个文本框之间切，且弹窗会盖住用户正看着的上下文。
// 这里刻意不留一个"万一以后还要弹窗"的实现 —— 留着就会有人用回它。
