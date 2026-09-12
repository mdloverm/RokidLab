package com.rokidlab.phone.store

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
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
            // 图片消息：先渲染图片，再附 caption 文本（仅手机端；眼镜端 TTS/显示不受影响）。
            msg.imageUrl?.let { url ->
                BubbleImage(url)
                if (msg.content.isNotBlank()) {
                    Text(
                        text = msg.content,
                        color = if (isUser) BrewBg else BrewTextBright,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            } ?: run {
                // 普通文字消息：双方都用 SelectionContainer 包裹，支持长按选字 / 复制
                SelectionContainer {
                    Text(
                        text = msg.content,
                        color = if (isUser) BrewBg else BrewTextBright,
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

/**
 * 气泡内的图片：异步下载（[ChatImageCache]），加载中显示转圈占位。
 * 长边按 [ChatImageCache] 默认 600px 限制，渲染时 Fit 自适应宽度。
 */
@Composable
private fun BubbleImage(url: String) {
    var bitmap by remember(url) { mutableStateOf<Bitmap?>(null) }
    var failed by remember(url) { mutableStateOf(false) }
    LaunchedEffect(url) {
        ChatImageCache.load(url) { bmp ->
            bitmap = bmp
            if (bmp == null) failed = true
        }
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 80.dp, max = 260.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(BrewPanelAlt),
        contentAlignment = Alignment.Center,
    ) {
        val bmp = bitmap
        when {
            bmp != null -> Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Fit,
            )
            failed -> Text(
                text = "图片加载失败",
                color = BrewMuted,
                fontSize = 12.sp,
            )
            else -> CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                strokeWidth = 2.dp,
                color = BrewMuted,
            )
        }
    }
}
