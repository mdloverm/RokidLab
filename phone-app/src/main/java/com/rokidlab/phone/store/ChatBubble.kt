package com.rokidlab.phone.store

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rokidlab.phone.R
import com.rokidlab.phone.ai.AgentStep
import com.rokidlab.phone.design.BrewAmber
import com.rokidlab.phone.design.BrewBg
import com.rokidlab.phone.design.BrewBorder
import com.rokidlab.phone.design.BrewChat
import com.rokidlab.phone.design.BrewDim
import com.rokidlab.phone.design.BrewMuted
import com.rokidlab.phone.design.BrewPanel
import com.rokidlab.phone.design.BrewPanelAlt
import com.rokidlab.phone.design.BrewRed
import com.rokidlab.phone.design.BrewSuccess
import com.rokidlab.phone.design.BrewText
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
        Column(modifier = Modifier.widthIn(max = 300.dp)) {
            // AI 过程卡片：放在气泡**上方**、气泡之外 —— 它描述的是"回答是怎么来的"，
            // 混进气泡正文里会和回答本身抢注意力（且气泡内嵌套卡片视觉上是双层容器）。
            if (!isUser && msg.trace.isNotEmpty()) {
                TraceBlock(msg.id, msg.trace)
                Spacer(Modifier.height(6.dp))
            }
            // 工具执行阶段正文还是空的：此时只显示过程卡片，等正文开始流式生成再出现气泡，
            // 避免留下一个「只有时间戳的空气泡」。
            val showBubble = msg.content.isNotBlank() || msg.imageUrl != null || msg.trace.isEmpty()
            if (showBubble) {
                Column(
                    modifier = Modifier
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
    }
}

/** 过程卡片展开时最多显示的步骤数：超出只留最近几步，避免长任务把对话列表撑成瀑布 */
private const val MAX_VISIBLE_STEPS = 6

/**
 * AI 过程区块（思考 / 工具调用时间线）。
 *
 * 默认展开：用户提问的正是"到底调了什么工具"，默认收起等于没解决。可点击标题折叠。
 *
 * @param msgId 用作 remember 的 key：不同消息各自记住自己的展开状态
 * @param steps 该轮全部过程步骤（按发生顺序；同 key 的更新已在 [com.rokidlab.phone.store.ChatStateHolder.upsertTrace] 覆盖合并）
 */
@Composable
private fun TraceBlock(msgId: Long, steps: List<AgentStep>) {
    var expanded by remember(msgId) { mutableStateOf(true) }
    val running = steps.any { it.state == AgentStep.State.RUNNING }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(BrewPanel)
            .border(1.dp, BrewBorder, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
        ) {
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = stringResource(
                    if (expanded) R.string.chat_trace_collapse else R.string.chat_trace_expand
                ),
                tint = BrewMuted,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = stringResource(R.string.chat_trace_title),
                color = BrewMuted,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = stringResource(R.string.chat_trace_steps, steps.size),
                color = BrewDim,
                fontSize = 11.sp,
            )
            Spacer(Modifier.weight(1f))
            if (running) {
                CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    strokeWidth = 1.5.dp,
                    color = BrewAmber,
                )
            }
        }
        if (expanded) {
            val hidden = steps.size - MAX_VISIBLE_STEPS
            if (hidden > 0) {
                Text(
                    text = stringResource(R.string.chat_trace_more, hidden),
                    color = BrewDim,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            steps.takeLast(MAX_VISIBLE_STEPS).forEach { TraceStepRow(it) }
        }
    }
}

/** 过程时间线中的一步：状态色圆点 + 图标 + 标题（工具名/思考文案）+ 摘要 */
@Composable
private fun TraceStepRow(step: AgentStep) {
    val color = when (step.state) {
        AgentStep.State.RUNNING -> BrewAmber
        AgentStep.State.OK -> BrewSuccess
        AgentStep.State.FAILED -> BrewRed
    }
    Row(modifier = Modifier
        .fillMaxWidth()
        .padding(top = 7.dp)
    ) {
        Box(
            modifier = Modifier
                .padding(top = 5.dp)
                .size(6.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (step.kind == AgentStep.Kind.THINKING) {
                        Icons.Outlined.Psychology
                    } else {
                        Icons.Outlined.Build
                    },
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(13.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = step.title.ifBlank { stepLabel(step) },
                    color = BrewText,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (step.detail.isNotBlank()) {
                Text(
                    text = step.detail,
                    color = BrewMuted,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

/**
 * 思考类步骤的文案（工具类步骤的标题就是工具名，不走这里）。
 *
 * 服务层只发语义（kind + state），文案在这里本地化 —— 见 [AgentStep.title] 的说明。
 */
@Composable
private fun stepLabel(step: AgentStep): String = when {
    step.kind == AgentStep.Kind.TOOL -> stringResource(R.string.chat_trace_tool_running)
    step.state == AgentStep.State.RUNNING -> stringResource(R.string.chat_trace_thinking)
    else -> stringResource(R.string.chat_trace_thought)
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
