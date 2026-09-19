package com.rokidlab.phone.store

import android.content.Context
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
import androidx.compose.material.icons.filled.MoreHoriz
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
import androidx.compose.ui.platform.LocalContext
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
internal fun ChatBubble(
    msg: ChatMsg,
    /**
     * 点气泡内「···」时的回调（复制 / 编辑重发 / 重新生成 / 删除）。
     * 传 null 表示不显示操作入口（状态气泡与旧调用点）。
     */
    onActions: (() -> Unit)? = null,
    /**
     * 点**用户发言**气泡本体的回调 = 编辑重发（进入输入框就地编辑）。
     *
     * 传 null 表示这条不可编辑（AI 消息 / 状态气泡 / 正在发送中）。
     * 只挂单击，不挂长按：正文在 `SelectionContainer` 里，长按会被"选字"吃掉。
     */
    onClickUser: (() -> Unit)? = null,
    /**
     * 「过程」区块**默认**是否展开（设置页里的「展开过程」，默认 true）。
     *
     * 它只是**初始值**：用户点标题手动折叠/展开后以本人的操作为准（见 [TraceBlock] 的 remember）。
     */
    expandTrace: Boolean = true,
) {
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
                TraceBlock(msg.id, msg.trace, msg.usage, expandTrace)
                Spacer(Modifier.height(6.dp))
            }
            // 工具执行阶段正文还是空的：此时只显示过程卡片，等正文开始流式生成再出现气泡，
            // 避免留下一个「只有时间戳的空气泡」。
            val showBubble = msg.content.isNotBlank() || msg.imageUrl != null || msg.trace.isEmpty()
            if (showBubble) {
                // 用户气泡整体可点 = 编辑重发。只挂**单击**（长按仍归 SelectionContainer 的选字）；
                // clickable 放在 background 之后、padding 之前：水波纹被裁在圆角内，且整个内边距都可点。
                val bubbleModifier = Modifier
                    .clip(bubbleShape)
                    .background(if (isUser) BrewChat else BrewPanelAlt)
                    .let { m -> if (isUser && onClickUser != null) m.clickable { onClickUser?.invoke() } else m }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                Column(modifier = bubbleModifier) {
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
                    Row(
                        modifier = Modifier
                            .align(Alignment.End)
                            .padding(top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = msg.time,
                            color = if (isUser) BrewBg.copy(alpha = 0.7f) else BrewMuted,
                            fontSize = 10.sp,
                        )
                        if (onActions != null) {
                            Spacer(Modifier.width(2.dp))
                            // 操作入口做成「···」而不是长按：气泡正文包在 SelectionContainer 里，
                            // 长按会被「选择文字」消费掉 —— 用户根本摸不到菜单（真机验证的结论）。
                            Icon(
                                imageVector = Icons.Filled.MoreHoriz,
                                contentDescription = stringResource(R.string.chat_msg_actions),
                                tint = if (isUser) BrewBg.copy(alpha = 0.85f) else BrewMuted,
                                modifier = Modifier
                                    .size(18.dp)
                                    .clickable { onActions() },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 过程卡片展开时最多显示的步骤数：超出只留最近几步，避免长任务把对话列表撑成瀑布 */
private const val MAX_VISIBLE_STEPS = 6

/**
 * AI 过程区块（思考 / 工具调用时间线 + 本轮成本）。
 *
 * 默认展开：用户提问的正是"到底调了什么工具"，默认收起等于没解决。可点击标题折叠。
 *
 * @param msgId 用作 remember 的 key：不同消息各自记住自己的展开状态
 * @param steps 该轮全部过程步骤（按发生顺序；同 key 的更新已在 [com.rokidlab.phone.store.ChatStateHolder.upsertTrace] 覆盖合并）
 * @param usage 本轮 token 成本；**展开时**在区块右下角显示（收起时只有一行标题，塞进去会把标题挤乱）
 * @param expandByDefault 初始展开状态（来自设置页「展开过程」）。
 *   ⚠️ 只当**初始值**：之后以用户手动点的为准 —— 所以 remember 的 key 是 `msgId` 而不是它，
 *   否则用户在设置里一改，所有卡片会被一起重置（手动折叠的那张会莫名弹开）。
 */
@Composable
private fun TraceBlock(msgId: Long, steps: List<AgentStep>, usage: MsgUsage?, expandByDefault: Boolean) {
    var expanded by remember(msgId) { mutableStateOf(expandByDefault) }
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
            TraceCostLine(usage)
        }
    }
}

/**
 * 本轮成本（右下角）。
 *
 * ★ 为什么挂在「过程」区块里、而不是气泡正文旁：这一行回答的是"这次**过程**花了多少" ——
 *   它是过程的一部分（跑了几轮工具循环、几次模型调用），和步骤时间线是同一个疑问的两半。
 *   放在气泡上会让人以为在描述那段回复的长度。
 *
 * ⚠️ **拿不到用量就不编数字**：服务端没返回 usage 时显示"用量未知"，而不是"输入 0 / 输出 0" ——
 *   后者是**错误信息**，比不显示更糟（用户会拿它当账单）。
 */
@Composable
private fun TraceCostLine(usage: MsgUsage?) {
    val ctx = LocalContext.current
    if (usage == null) return
    val text = remember(usage) { usageCostText(ctx, usage) } ?: return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 7.dp),
        horizontalArrangement = Arrangement.End,
    ) {
        Text(text = text, color = BrewDim, fontSize = 11.sp)
    }
}

/**
 * 成本文案（纯函数，可单测）。
 *
 * @return null = 没有任何可展示的信息（USAGE 全空）；否则返回要显示的一行
 */
internal fun usageCostText(context: Context, usage: MsgUsage): String? {
    val parts = ArrayList<String>(3)
    val input = usage.inputTokens
    val output = usage.outputTokens
    if (input != null || output != null) {
        parts.add(
            context.getString(
                R.string.chat_trace_cost_tokens,
                input?.let { fmtTokens(it) } ?: "?",
                output?.let { fmtTokens(it) } ?: "?",
            )
        )
    }
    usage.modelCalls?.takeIf { it > 0 }?.let {
        parts.add(context.getString(R.string.chat_trace_cost_calls, it))
    }
    usage.elapsedMs?.takeIf { it >= 0 }?.let {
        parts.add(context.getString(R.string.chat_trace_cost_elapsed, fmtElapsed(it)))
    }
    if (parts.isEmpty()) return context.getString(R.string.chat_trace_cost_unknown)
    return parts.joinToString(" · ")
}

/** token 数紧凑显示：1234 → 1.2k（面板很窄，四位数字会把一行撑开） */
internal fun fmtTokens(n: Int): String = when {
    n < 1000 -> n.toString()
    n < 100_000 -> String.format(java.util.Locale.US, "%.1fk", n / 1000.0)
    else -> String.format(java.util.Locale.US, "%.0fk", n / 1000.0)
}

/** 耗时紧凑显示：842ms / 2.0s / 1m12s */
internal fun fmtElapsed(ms: Long): String = when {
    ms < 1000L -> "${ms}ms"
    ms < 60_000L -> String.format(java.util.Locale.US, "%.1fs", ms / 1000.0)
    else -> "${ms / 60_000L}m${(ms % 60_000L) / 1000L}s"
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
