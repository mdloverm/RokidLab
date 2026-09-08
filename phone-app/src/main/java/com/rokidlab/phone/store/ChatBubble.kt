package com.rokidlab.phone.store

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rokidlab.phone.design.BrewBg
import com.rokidlab.phone.design.BrewChat
import com.rokidlab.phone.design.BrewMuted
import com.rokidlab.phone.design.BrewPanelAlt
import com.rokidlab.phone.design.BrewTextBright

// ===== 对话气泡 =====
@Composable
internal fun ChatBubble(msg: ChatMsg) {
    val isUser = msg.isUser

    // 流程状态消息：居中灰字，无气泡
    if (msg.isStatus) {
        Text(
            text = msg.content,
            color = BrewMuted,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp),
        )
        return
    }

    val bubbleShape = if (isUser) {
        RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp)
    } else {
        RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp)
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(bubbleShape)
                .background(if (isUser) BrewChat else BrewPanelAlt)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            // AI 消息用 SelectionContainer 包裹，支持长按选字 / 复制
            // 用户消息不需要选中
            if (isUser) {
                Text(
                    text = msg.content,
                    color = BrewBg,
                    fontSize = 15.sp,
                    lineHeight = 22.sp,
                )
            } else {
                SelectionContainer {
                    Text(
                        text = msg.content,
                        color = BrewTextBright,
                        fontSize = 15.sp,
                        lineHeight = 22.sp,
                    )
                }
            }
            Text(
                text = msg.time,
                color = if (isUser) BrewBg.copy(alpha = 0.7f) else BrewMuted,
                fontSize = 10.sp,
                modifier = Modifier
                    .align(Alignment.End)
                    .padding(top = 4.dp),
            )
        }
    }
}
