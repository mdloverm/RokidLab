package com.rokidlab.phone.store

import android.content.Context
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Compress
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.MenuBook
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Report
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rokidlab.phone.R
import com.rokidlab.phone.ai.AgentStep
import com.rokidlab.phone.ai.ToolRegistry
import com.rokidlab.phone.design.BrewAmber
import com.rokidlab.phone.design.BrewBg
import com.rokidlab.phone.design.BrewBorder
import com.rokidlab.phone.design.BrewChat
import com.rokidlab.phone.design.BrewDim
import com.rokidlab.phone.design.BrewMuted
import com.rokidlab.phone.design.BrewPanel
import com.rokidlab.phone.design.BrewPanelAlt
import com.rokidlab.phone.design.BrewRadiusLarge
import com.rokidlab.phone.design.BrewRadiusSmall
import com.rokidlab.phone.design.BrewRed
import com.rokidlab.phone.design.BrewSuccess
import com.rokidlab.phone.design.BrewText
import com.rokidlab.phone.design.BrewTextBright
import com.rokidlab.phone.platform.InteractiveRunManager
import kotlinx.coroutines.delay

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
     * 点喇叭 = 用当前音色播报本条 AI 回复（云萌 = 手机出声；默认 = 眼镜端离线 TTS）。
     * 传 null 表示不显示播放按钮（状态气泡 / 用户消息 / 旧调用点）。
     */
    onSpeak: (() -> Unit)? = null,
    /** 本条是否正在播报中（喇叭高亮；再点一次 = 停止） */
    speaking: Boolean = false,
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
     * 语义＝**默认值**，不是"初始值快照"：用户在这张卡标题上点过之后以他的手动操作为准，
     * 没动过的卡片则实时跟随本值（两层状态见 [TraceBlock]）—— 否则设置页改了聊天窗口不响应。
     */
    expandTrace: Boolean = true,
    /**
     * 本条是失败提示条时的一键重试回调（复用「重新生成」链路）。
     * 传 null 不显示重试入口。专业 Agent 的失败气泡都带内联重试 —— 补救不该藏在菜单里。
     */
    onRetry: (() -> Unit)? = null,
    /**
     * 纯闲聊（无过程步骤）且正文尚未开始流式时显示三点打字指示。
     * 有过程步骤时不为 true —— TraceBlock 的「正在思考」行是更具体的反馈。
     */
    typing: Boolean = false,
) {
    val isUser = msg.isUser
    val haptic = LocalHapticFeedback.current
    fun hapticTick() = haptic.performHapticFeedback(HapticFeedbackType.LongPress)

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

    // 运行卡片（本地交互式程序输出流）：等宽块 + 语言标签，滚动跟随最新行
    if (msg.runId != null) {
        RunOutputCard(msg)
        return
    }

    // 圆角接**全局刻度**（UI-DESIGN.md §1.4）：大角 = BrewShapeLarge(16dp)、
    // 指向说话人的那个小角 = BrewShapeSmall(4dp)。原先硬编码数字，刻度一改就掉队。
    val bubbleShape = if (isUser) {
        RoundedCornerShape(BrewRadiusLarge, BrewRadiusLarge, BrewRadiusSmall, BrewRadiusLarge)
    } else {
        RoundedCornerShape(BrewRadiusLarge, BrewRadiusLarge, BrewRadiusLarge, BrewRadiusSmall)
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Column(modifier = Modifier.widthIn(max = 300.dp)) {
            // AI 过程卡片：放在气泡**上方**、气泡之外 —— 它描述的是"回答是怎么来的"，
            // 混进气泡正文里会和回答本身抢注意力（且气泡内嵌套卡片视觉上是双层容器）。
            //
            // ★ 2026-10-12：`update_plan` 步骤**不进时间线** —— 计划入口已移到输入框上方那一行
            //   （ChatScreen 的 [PlanChip] + [PlanSheet]），这里剔掉它，避免同一条计划出现两份、
            //   也避免「计划步骤」在"过程 · N 步"里被当成一次工具调用计数。
            val traceSteps = if (isUser) emptyList() else msg.trace.filterNot { it.kind == AgentStep.Kind.PLAN }
            if (traceSteps.isNotEmpty()) {
                TraceBlock(msg.id, traceSteps, msg.usage, expandTrace)
                Spacer(Modifier.height(6.dp))
            }
            // 工具执行阶段正文还是空的：此时只显示过程卡片，等正文开始流式生成再出现气泡，
            // 避免留下一个「只有时间戳的空气泡」。
            // （本轮过程里只有计划步骤时两条都不渲染 —— 那条计划已经在上方入口里可见，
            //   此时再补一个空气泡反而多一块空白。）
            val showBubble = msg.content.isNotBlank() || msg.imageUrl != null ||
                msg.filePath != null || msg.trace.isEmpty()
            if (showBubble) {
                // 用户气泡整体可点 = 编辑重发。只挂**单击**（长按仍归 SelectionContainer 的选字）；
                // clickable 放在 background 之后、padding 之前：水波纹被裁在圆角内，且整个内边距都可点。
                val bubbleModifier = Modifier
                    .clip(bubbleShape)
                    .background(if (isUser) BrewChat else BrewPanelAlt)
                    .let { m -> if (isUser && onClickUser != null) m.clickable { onClickUser?.invoke() } else m }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                Column(modifier = bubbleModifier) {
                    // ★ 图片与文件**可以同时挂**在同一条消息上（2026-10-12 审计修）：原先用
                    //   `imageUrl?.let{…} ?: filePath?.let{…}` 串联 ⇒ 有图时文件卡被整个吞掉
                    //   （用户上传「图片 + 说明文件」时文件凭空消失）。改为各自独立渲染，caption 只出一次。
                    val hasAttachment = msg.imageUrl != null || msg.filePath != null
                    // 首 token 前的等待期：三点打字指示（只有纯闲聊会走到这里，Agent 轮次有过程行）
                    if (typing) {
                        TypingDots()
                    }
                    // 图片消息：先渲染图片卡片（仅手机端；眼镜端 TTS/显示不受影响）
                    msg.imageUrl?.let { url -> ChatImageCard(url) }
                    // 文件消息（用户上传 / AI 产出）：文件卡，形态与图片一致
                    msg.filePath?.let { path ->
                        ChatFileCard(
                            path = path,
                            name = msg.fileName ?: path.substringAfterLast('/'),
                            chars = msg.fileChars,
                        )
                    }
                    if (msg.content.isNotBlank()) {
                        // caption 一律走 markdown（2026-10-06 审计修）：caption 由模型自由填写，
                        // 常写成「这是 **架构图**：\n- 第一层」这种带格式的内容，用 Text 会把
                        // 星号/连字符**原样显示**；「先流正文、再挂附件」时纯文本还会让整条回复掉格式。
                        ChatMarkdownBody(
                            text = msg.content,
                            textColor = if (isUser) BrewBg else BrewTextBright,
                            modifier = if (hasAttachment) Modifier.padding(top = 6.dp) else Modifier,
                        )
                    }
                    // 失败提示条的内联重试（专业 Agent 标配）：一 tap 重问，不用进菜单找
                    if (msg.failed && onRetry != null) {
                        Spacer(Modifier.height(6.dp))
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(BrewRadiusSmall))
                                .border(1.dp, BrewRed.copy(alpha = 0.45f), RoundedCornerShape(BrewRadiusSmall))
                                .clickable {
                                    hapticTick()
                                    onRetry()
                                }
                                .padding(horizontal = 10.dp, vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = null,
                                tint = BrewRed,
                                modifier = Modifier.size(13.dp),
                            )
                            Spacer(Modifier.width(5.dp))
                            Text(
                                text = stringResource(R.string.chat_msg_retry),
                                color = BrewRed,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                    // ★ Token 成本的**兜底出口**（2026-10-06 审计修）：
                    // [TraceCostLine] 原本只在过程卡 `if (expanded)` 分支里，而**纯闲聊消息
                    // 的 `trace` 为空 ⇒ 过程卡根本不渲染** ⇒ 那条消息的 usage 落盘了却
                    // 在UI 上没有任何出口（用户问一句"你好"，服务端回了 usage，看不到）。
                    // 这里在气泡内补一行，只有"过程卡不存在"时才显示，避免与卡内那份重复。
                    if (msg.trace.isEmpty() && msg.usage != null) {
                        InlineCostText(msg.usage)
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
                                    .clickable {
                                        hapticTick()
                                        onActions()
                                    },
                            )
                        }
                        // 播放按钮（仅 AI 回复）：点一下用当前音色播报本条内容。
                        // 云萌音色 = 手机出声；默认音色 = 眼镜端离线 TTS。再点一次 = 停止。
                        if (!isUser && onSpeak != null) {
                            Spacer(Modifier.width(4.dp))
                            Icon(
                                imageVector = Icons.Filled.VolumeUp,
                                contentDescription = stringResource(R.string.chat_speaker_play),
                                tint = if (speaking) BrewChat else BrewMuted,
                                modifier = Modifier
                                    .size(18.dp)
                                    .clickable {
                                        hapticTick()
                                        onSpeak()
                                    },
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
 * 三点打字指示（首 token 前的等待反馈，对标专业产品的 typing dots）。
 *
 * 一个无限转动的相位 [0,1) 驱动三个点：点 i 的亮度 = 1 - |相位*3 - i 折返| 的距离，
 * 波峰依次扫过三点 —— 与"逐点各自跑动画"相比只创建一个 InfiniteTransition，开销最小。
 */
@Composable
private fun TypingDots() {
    val transition = rememberInfiniteTransition(label = "typingDots")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 900, easing = LinearEasing)),
        label = "typingPhase",
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i ->
            val local = ((phase * 3f - i) % 3f).let { if (it < 0f) it + 3f else it }
            val alpha = (1f - kotlin.math.abs(local - 1f)).coerceIn(0.25f, 1f)
            Box(
                modifier = Modifier
                    .padding(horizontal = 2.5.dp)
                    .graphicsLayer { this.alpha = alpha }
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(BrewMuted),
            )
        }
    }
}

/**
 * AI 过程区块（思考 / 工具调用时间线 + 本轮成本）。
 *
 * 默认展开：用户提问的正是"到底调了什么工具"，默认收起等于没解决。可点击标题折叠。
 *
 * 展开状态分**两层**，各管各的（这正是"设置页开关"与"卡片箭头"不再打架的机制）：
 *  - **默认层** `expandByDefault`（设置页「展开过程」）：每帧实时读，改了就跟着变；
 *  - **手动层** `manual`（用户在这张卡上点过箭头）：非空即优先，覆盖默认层。
 *  ⇒ 改设置页时**没手动动过的卡片**会一起变；他单独折过的那张保持原样，不会被弹开。
 *
 * @param msgId 用作 remember 的 key：不同消息各自记住自己的**手动**操作
 * @param steps 该轮全部过程步骤（按发生顺序；同 key 的更新已在 [com.rokidlab.phone.store.ChatStateHolder.upsertTrace] 覆盖合并）
 * @param usage 本轮 token 成本；**展开时**在区块右下角显示（收起时只有一行标题，塞进去会把标题挤乱）
 * @param expandByDefault **默认**展开状态（来自设置页「展开过程」）。
 *   ⚠️ 它**不是"初始值快照"**：快照会让用户在设置里改完之后聊天窗口毫无反应 ——
 *   设置页说"展开"、卡片还是折着，两个控件各说各的（用户报的冲突）。
 */
@Composable
private fun TraceBlock(
    msgId: Long,
    steps: List<AgentStep>,
    usage: MsgUsage?,
    expandByDefault: Boolean,
) {
    // null = 用户没单独动过这张卡 ⇒ 跟随设置页默认值；非 null = 用户点过 ⇒ 以他为准
    var manual by remember(msgId) { mutableStateOf<Boolean?>(null) }
    val expanded = manual ?: expandByDefault
    // 是否已点开"看全部步骤"（默认只显示"最近若干步 + 文件操作"，见 [visibleTraceSteps]）
    var showAll by remember(msgId) { mutableStateOf(false) }
    // AWAITING_CONFIRM 也算"进行中" —— 它同样在等一个外部回应（用户点头），
    // 若不算，卡片会提前收起那颗转圈点，用户反而看不到"正在等你确认"。
    val running = steps.any {
        it.state == AgentStep.State.RUNNING || it.state == AgentStep.State.AWAITING_CONFIRM
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            // ⚠️ 保持 10dp（**不要**改成刻度里的 8/12）：全项目 11 个文件都拿 10dp 当"卡片圆角"
            // （文件卡/图片卡/媒体卡/表格卡/代码卡/各对话框），过程卡也是卡片，
            // 单独"合规"会让它跟所有兄弟卡片不一致 —— 这才是更严重的不一致。
            // 真正的问题在规范侧：UI-DESIGN.md 的刻度（4/8/12/16/20）里**没有 10**，
            // 而实现里 10dp 是事实标准 ⇒ 要么把它补进刻度，要么全站统一迁移，
            // 属于独立一轮的事，别在改单个组件时顺手"修正"。
            .clip(RoundedCornerShape(10.dp))
            .background(BrewPanel)
            .border(1.dp, BrewBorder, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp)
            // 展开/收起平滑过渡（瞬变会让"过程"卡一惊一乍；时长与气泡入场一致）
            .animateContentSize(tween(durationMillis = 220, easing = FastOutSlowInEasing)),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { manual = !expanded },
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
            // ★ 实时 token 计量（2026-10-12 对标 Codex）：生成中显示本轮已计费累计
            //   （`≈1.2k tokens`）。与最终 [TraceCostLine] 的关系：这是**进行中**的
            //   下界估计（标 ≈），收尾后由成本行给出权威数字。仅 running 时显示。
            if (running) {
                ChatStateHolder.liveTokens.collectAsState().value?.takeIf { it > 0 }?.let { live ->
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.chat_trace_live_tokens, "≈" + fmtTokens(live)),
                        color = BrewDim,
                        fontSize = 11.sp,
                    )
                }
            }
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
            val shown = visibleTraceSteps(steps, showAll)
            val folded = steps.size - shown.size
            // ★ 被折叠的步骤必须有**出口**（2026-10-10 用户反馈："折叠的过程现在不能再展开"）：
            //   只写一行"另有 N 步已折叠"却不给点击入口，等于把过程的一部分永久藏起来 ——
            //   而用户想查"它到底做了什么"时，缺的往往正是那几步。
            if (folded > 0 || (showAll && steps.size > MAX_VISIBLE_STEPS)) {
                // 缩进到与步骤正文同列、且**不再加第二个箭头**：卡片标题已经有一枚箭头，
                // 同一列里并排两枚箭头（一枚管整卡、一枚管折叠步骤）用户分不清谁管谁。
                // 可点性改用颜色表达 —— 与产物芯片的链接色同一套（BrewChat）。
                Text(
                    text = if (folded > 0) {
                        stringResource(R.string.chat_trace_more, folded)
                    } else {
                        stringResource(R.string.chat_trace_less)
                    },
                    color = BrewChat,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .padding(top = 6.dp)
                        .clip(RoundedCornerShape(BrewRadiusSmall))
                        .clickable { showAll = !showAll }
                        .padding(start = 22.dp, top = 2.dp, bottom = 2.dp),
                )
            }
            shown.forEach { step ->
                when {
                    // 计划步骤在 [ChatBubble] 里已被剔除（入口在输入框上方），这里只是 when 穷尽
                    step.kind == AgentStep.Kind.PLAN -> Unit
                    // ★ 命令类工具走专用命令卡（shell 图标 + monospace 命令 + 状态徽标 + 可展开输出），
                    //   判据是 command 非空 —— 旧历史（本字段落地前）为 null ⇒ 自动退回通用行。
                    step.command != null -> CommandStepCard(step)
                    else -> TraceStepRow(step)
                }
            }
            TraceCostLine(usage)
        }
    }
}

/**
 * 过程卡片展开时渲染哪些步骤。
 *
 * ## 为什么不是简单取「最近 N 步」（2026-10-10 用户反馈）
 * 文件操作（新建 / 修改 / 删除，即带 [AgentStep.artifacts] 的步骤）是用户最关心的
 * "它到底动了什么"，但它们通常集中在一轮的**前段**；收尾阶段的命令 / 思考会把它们挤出
 * `takeLast(N)` 的窗口 —— 用户看到的最后 6 步全在跑命令，文件操作凭空消失
 *（用户原话："文件操作的过程看不到了"）。
 * ⇒ **文件操作步骤恒可见**，其余步骤只保留最近 [MAX_VISIBLE_STEPS] 条，
 *   合并后仍按原始时间顺序（不重排，过程必须读得出先后）。
 *
 * @return 可见步骤（保持发生顺序）；被略过的条数由调用方按「总步数 − 可见数」如实显示
 * @param showAll 用户已点开"看全部"⇒ 原样返回，交给对面那行折叠入口去收起
 */
internal fun visibleTraceSteps(steps: List<AgentStep>, showAll: Boolean = false): List<AgentStep> {
    if (showAll || steps.size <= MAX_VISIBLE_STEPS) return steps
    val keepFrom = steps.size - MAX_VISIBLE_STEPS
    return steps.filterIndexed { i, step -> i >= keepFrom || step.artifacts.isNotEmpty() }
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
/**
 * 气泡内的紧凑成本行（无过程卡时的兜底出口，见调用处注释）。
 *
 * 复用 [usageCostText] —— 与 [TraceCostLine] 同一份文案生成逻辑，
 * 不另写一套格式化（否则两处口径迟早漂移）。
 */
@Composable
private fun InlineCostText(usage: MsgUsage) {
    val ctx = LocalContext.current
    val text = remember(usage) { usageCostText(ctx, usage) } ?: return
    Text(
        text = text,
        color = BrewDim,
        fontSize = 10.sp,
        // 📌 右对齐靠 Row 的 Arrangement.End 更稳；这里用 fillMaxWidth + 文本右对齐，
        //不依赖 ColumnScope.align（那要求调用点必须处在 ColumnScope 内，脆）。
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        textAlign = TextAlign.End,
    )
}

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
 * Token 展示口径（2026-09-25 用户要求）：不再拆"输入 / 输出"，只报**合计用量** ——
 * 用户关心的是"这轮花了多少"，输入输出拆分是账单视角不是对话视角。
 * ⚠️ 服务端只给了一半（通常只回 prompt）时是**部分未知**：合计后加 ≥ 前缀表达下界，
 * 不兜 0 —— 那是错误信息，比不显示更糟。
 *
 * @return null = 没有任何可展示的信息（USAGE 全空）；否则返回要显示的一行
 */
internal fun usageCostText(context: Context, usage: MsgUsage): String? {
    val parts = ArrayList<String>(4)
    val input = usage.inputTokens
    val output = usage.outputTokens
    if (input != null || output != null) {
        val total = (input ?: 0) + (output ?: 0)
        val partial = input == null || output == null
        parts.add(
            context.getString(
                R.string.chat_trace_cost_tokens,
                (if (partial) "≥" else "") + fmtTokens(total),
            )
        )
    }
    // 前缀缓存命中（2026-09-26 P0）：只在服务端给过该字段时显示 —— 它是「system 消息
    // 逐字节稳定」优化的直接度量，连续为 0 说明前缀稳定性退化了（轮变内容又混回了头部）
    usage.promptCacheHitTokens?.let {
        parts.add(context.getString(R.string.chat_trace_cost_cache_hit, fmtTokens(it)))
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

/**
 * ★ 等待秒数（2026-10-12）：进行中的步骤显示「已等待 Ns」。
 *
 * 为什么要有：真机实例（贪吃蛇白屏修复）里有多处 23~38s 的长间隙——模型整段生成
 * save_code_file 参数、用户在审批弹窗犹豫——期间过程行只有转圈，用户不知道是
 * "卡死了"还是"在干活"。跳动的秒数给等待一个**刻度**，是最低成本的"生命感"。
 *
 * [key] 是步骤 key：remember 依它记首帧时间，同 key 的状态覆盖（RUNNING→终态）不重置。
 * ≥5s 才显示（快工具不闪数字）；仅 [AgentStep.State.RUNNING] 与
 * [AgentStep.State.AWAITING_CONFIRM]（等你确认也是等待，且秒数能暴露"弹窗挂了多久"）。
 */
@Composable
private fun rememberRunningSeconds(state: AgentStep.State, key: String): Int? {
    val running = state == AgentStep.State.RUNNING || state == AgentStep.State.AWAITING_CONFIRM
    val startedAt = remember(key) { System.currentTimeMillis() }
    var elapsed by remember(key) { mutableIntStateOf(0) }
    if (!running) return null
    LaunchedEffect(key) {
        while (true) {
            elapsed = ((System.currentTimeMillis() - startedAt) / 1000L).toInt()
            delay(1000)
        }
    }
    return elapsed.takeIf { it >= 5 }
}

/** 过程时间线中的一步：状态色圆点 + 图标 + 标题（工具名/思考文案）+ 摘要 */
@Composable
private fun TraceStepRow(step: AgentStep) {
    val color = when (step.state) {
        AgentStep.State.RUNNING -> BrewAmber
        // ★ 与 RUNNING 同色（进行中），但文案不同 —— 关键区别是"现在轮到你"而不是"我在忙"
        AgentStep.State.AWAITING_CONFIRM -> BrewAmber
        AgentStep.State.OK -> BrewSuccess
        AgentStep.State.FAILED -> BrewRed
        // 中性灰：用户自己点的取消是**他的决定**，不是错误，不该显示成红色失败
        AgentStep.State.CANCELLED -> BrewMuted
    }
    // 思考全文展开/收起：detail 只压一行预览，fullText 是整段推理（落盘限长 8000）
    var thinkExpanded by remember(step.key) { mutableStateOf(false) }
    val expandable = step.kind == AgentStep.Kind.THINKING && step.fullText.isNotBlank()
    // RUNNING 态的圆点做呼吸动画（"它在忙"）；AWAITING_CONFIRM 刻意不呼吸 ——
    // 静止 = "在等你"，动态 = "在干活"，两种等待肉眼可分
    val dotAlpha = if (step.state == AgentStep.State.RUNNING) {
        val transition = rememberInfiniteTransition(label = "stepDot")
        transition.animateFloat(
            initialValue = 0.3f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                tween(durationMillis = 650, easing = LinearEasing),
                RepeatMode.Reverse,
            ),
            label = "stepDotAlpha",
        ).value
    } else {
        1f
    }
    Row(modifier = Modifier
        .fillMaxWidth()
        .padding(top = 7.dp)
    ) {
        Box(
            modifier = Modifier
                .padding(top = 5.dp)
                .graphicsLayer { alpha = dotAlpha }
                .size(6.dp)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            // ★ 等待秒数：进行中步骤右端显示「已等待 Ns」（≥5s 才出现，见 helper KDoc）
            val waitSec = rememberRunningSeconds(step.state, step.key)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Icon(
                    imageVector = when (step.kind) {
                        AgentStep.Kind.THINKING -> Icons.Outlined.Psychology
                        // 知识库检索：书 —— 一眼区分"模型调了工具"和"App 自己先查了你的文档"
                        AgentStep.Kind.KNOWLEDGE -> Icons.Outlined.MenuBook
                        AgentStep.Kind.TOOL -> Icons.Outlined.Build
                        // PLAN 不走进时间线（入口在输入框上方，见 PlanChip），这里仅为 when 穷尽
                        // 压缩历史：用"压缩"图标（与「思考」「工具」不同，
                        // 用户需要区分"它在想" vs"框架改了我的上下文"）
                        AgentStep.Kind.COMPRESS -> Icons.Outlined.Compress
                        AgentStep.Kind.PLAN -> Icons.Outlined.Checklist
                        // 收尾：旗子 —— "框架在补跑落盘"，不是模型在思考
                        AgentStep.Kind.SUMMARY -> Icons.Outlined.Flag
                        // 框架行为：设置齿轮 —— 是**框架**在重试/降载，不是模型决定的
                        AgentStep.Kind.FRAMEWORK -> Icons.Outlined.Settings
                        // 上下文注入：数据库 —— 回答"它凭什么知道这些"
                        AgentStep.Kind.MEMORY -> Icons.Outlined.Storage
                        // 自省：报表图标 —— "框架发现它自己有问题"，必须显眼
                        AgentStep.Kind.REFLECT -> Icons.Outlined.Report
                    },
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.size(13.dp),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = stepText(step),
                    color = BrewText,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (waitSec != null) {
                    Text(
                        text = stringResource(R.string.chat_trace_waiting_secs, waitSec),
                        color = BrewDim,
                        fontSize = 10.sp,
                        maxLines = 1,
                    )
                } else {
                    // ★ 终态耗时（2026-10-12 对标 Codex）：这步实际花了多久。
                    //   与等待秒数互斥（一个在跑一个已结束）；null = 旧历史没这数据，不占位。
                    step.durationMs?.let { dur ->
                        Text(
                            text = fmtElapsed(dur),
                            color = BrewDim,
                            fontSize = 10.sp,
                            maxLines = 1,
                        )
                    }
                }
            }
            if (expandable) {
                Text(
                    text = if (thinkExpanded) step.fullText else step.detail,
                    color = BrewMuted,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    maxLines = if (thinkExpanded) 30 else 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 2.dp)
                        .clickable { thinkExpanded = !thinkExpanded },
                )
                Text(
                    text = stringResource(
                        if (thinkExpanded) R.string.chat_think_collapse else R.string.chat_think_expand
                    ),
                    color = BrewDim,
                    fontSize = 10.sp,
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .clickable { thinkExpanded = !thinkExpanded },
                )
            } else if (step.detail.isNotBlank()) {
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
            // ★ 文件产物（2026-10-10）：可点开预览的文件芯片。
            //   放在 detail **之后**而不是之前：detail 是"这一步发生了什么"，
            //   芯片是"它动了哪些文件" —— 后者是对前者的展开，不该抢主位。
            //   非 TOOL 类步骤（思考/计划/压缩）不会有 artifacts，这里天然短路。
            ArtifactChips(step.artifacts)
            // ★ apply_patch 的着色 diff 卡（2026-10-12 对标 Codex）：改动内容可见
            DiffBlock(step)
        }
    }
}

/**
 * 命令类工具（[AgentStep.command] 非空）的**专用命令卡**。
 *
 * ## 为什么单独一张卡（对齐 Claude Code / Cursor / Warp 的「命令块」）
 * 通用工具行把「命令」和「退出码」拍平进一句 [AgentStep.detail]：没有 monospace 区分、
 * 没有独立状态位 —— 跟"读取文件"长得一模一样，用户读不出"它在跑一条命令"。
 * 这张卡把三者分开：shell 图标 + 命令（monospace 内嵌块）+ 状态徽标 + 可展开的输出。
 *
 * ## 输出默认收起（2026-10-10 用户拍板）
 * 命令回显可能是长日志（落盘已按 [AgentStep.MAX_OUTPUT_CHARS] 截断），直接铺开会把对话
 * 撑成瀑布。收起时只留**首行**（通常是「退出码 0」），点"展开输出"看其余行。
 *
 * ⚠️ [AgentStep.output] 是**展示副本**，只在本机渲染 —— 不进模型上下文，不消耗 token。
 */
@Composable
private fun CommandStepCard(step: AgentStep) {
    val color = when (step.state) {
        AgentStep.State.RUNNING -> BrewAmber
        // 与 RUNNING 同色，但文案是"等你确认"（见 AgentStep.State.AWAITING_CONFIRM）
        AgentStep.State.AWAITING_CONFIRM -> BrewAmber
        AgentStep.State.OK -> BrewSuccess
        AgentStep.State.FAILED -> BrewRed
        // 中性：用户自己点的取消不是错误，不该显示成红色失败
        AgentStep.State.CANCELLED -> BrewMuted
    }
    var expanded by remember(step.key) { mutableStateOf(false) }
    // 首行 = 摘要（「退出码 0」这类，收起时也显示）；其余行 = 点开才看的部分
    val lines = step.output.orEmpty().lines()
    val headIdx = lines.indexOfFirst { it.isNotBlank() }
    val summary = if (headIdx >= 0) lines[headIdx].trim() else ""
    val rest = if (headIdx >= 0) lines.drop(headIdx + 1).joinToString("\n").trim() else ""
    val expandable = rest.isNotBlank()
    // 是否被落盘截断：与 AgentStep.clipKeepLines 的阈值同源（见 MAX_OUTPUT_CHARS）
    val truncated = (step.output?.length ?: 0) >= AgentStep.MAX_OUTPUT_CHARS

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 7.dp)
            .clip(RoundedCornerShape(BrewRadiusSmall))
            .background(BrewPanelAlt)
            .border(1.dp, BrewBorder, RoundedCornerShape(BrewRadiusSmall))
            .padding(horizontal = 9.dp, vertical = 7.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Outlined.Terminal,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(13.dp),
            )
            Spacer(Modifier.width(5.dp))
            Text(
                text = stepText(step),
                color = BrewText,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(6.dp))
            // ★ 等待秒数：与通用工具行同一 helper（RUNNING/等你确认 ≥5s 才显示）
            val waitSec = rememberRunningSeconds(step.state, step.key)
            if (waitSec != null) {
                Text(
                    text = stringResource(R.string.chat_trace_waiting_secs, waitSec),
                    color = BrewDim,
                    fontSize = 10.sp,
                    maxLines = 1,
                )
                Spacer(Modifier.width(6.dp))
            }
            Text(
                text = cmdStateLabel(step.state),
                color = color,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
            // ★ 终态耗时（2026-10-12 对标 Codex）：与通用工具行同一口径（fmtElapsed）
            step.durationMs?.let { dur ->
                Spacer(Modifier.width(6.dp))
                Text(
                    text = fmtElapsed(dur),
                    color = BrewDim,
                    fontSize = 10.sp,
                    maxLines = 1,
                )
            }
        }
        // 命令本体：monospace + 内嵌底色块 —— "这是一条命令"的形状语言
        step.command?.takeIf { it.isNotBlank() }?.let { cmd ->
            Spacer(Modifier.height(5.dp))
            Text(
                text = cmd,
                color = BrewTextBright,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(BrewRadiusSmall))
                    .background(BrewBg)
                    .padding(horizontal = 7.dp, vertical = 5.dp),
            )
        }
        // 「等你确认」这类说明（[AgentStep.awaitingConfirm] 的 detail）照实显示
        if (step.detail.isNotBlank()) {
            Text(
                text = step.detail,
                color = BrewMuted,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        if (summary.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = summary,
                color = if (step.state == AgentStep.State.OK) BrewMuted else color,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (expandable && expanded) {
            Text(
                text = rest,
                color = BrewMuted,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 30,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 3.dp),
            )
            if (truncated) {
                Text(
                    text = stringResource(R.string.chat_cmd_output_truncated),
                    color = BrewDim,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        if (expandable) {
            Text(
                text = stringResource(
                    if (expanded) R.string.chat_cmd_output_collapse else R.string.chat_cmd_output_expand,
                ),
                color = BrewDim,
                fontSize = 10.sp,
                modifier = Modifier
                    .padding(top = 3.dp)
                    .clickable { expanded = !expanded },
            )
        }
    }
}

/** 命令卡右上角的状态徽标文案（配色与 [CommandStepCard] 同源） */
@Composable
private fun cmdStateLabel(state: AgentStep.State): String = when (state) {
    AgentStep.State.RUNNING -> stringResource(R.string.chat_trace_tool_running)
    AgentStep.State.AWAITING_CONFIRM -> stringResource(R.string.chat_trace_tool_awaiting_confirm)
    AgentStep.State.OK -> stringResource(R.string.chat_cmd_state_ok)
    AgentStep.State.FAILED -> stringResource(R.string.chat_cmd_state_failed)
    AgentStep.State.CANCELLED -> stringResource(R.string.chat_trace_tool_cancelled)
}

/**
 * apply_patch 的**着色 diff 卡**（2026-10-12 对标 Codex 的「改动可见」缺口）。
 *
 * ## 收起时只给统计行
 * `+12/−4` 一行就说清改动量；点开才看着色 diff（+绿 / −红 / 上下文灰，monospace）。
 * 补丁全文落盘已按 [AgentStep.MAX_DIFF_CHARS] 截断，展开超限时如实话注"已截断"。
 *
 * ⚠️ [AgentStep.diff] 是**展示副本**（与 output 同一条纪律）：只进本地 UI 与聊天历史，
 * 不进模型上下文 —— 展示多长都不花 token。旧历史没有该字段 → null 短路，自动退回旧行。
 */
@Composable
private fun DiffBlock(step: AgentStep) {
    val diff = step.diff?.takeIf { it.isNotBlank() } ?: return
    var expanded by remember(step.key) { mutableStateOf(false) }
    // 统计行直接从展示文本数前缀 —— 与渲染同源，保证数字和颜色行永远对得上
    var added = 0
    var removed = 0
    diff.lines().forEach { l ->
        when {
            l.startsWith("+") -> added++
            l.startsWith("-") -> removed++
        }
    }
    // 逐行着色一次构建（remember：同 diff 不重复算；落盘已截断 ⇒ 最多几千字符）
    val styled = remember(diff) {
        buildAnnotatedString {
            diff.lines().forEachIndexed { i, line ->
                if (i > 0) append('\n')
                val c = when {
                    line.startsWith("+") -> BrewSuccess
                    line.startsWith("-") -> BrewRed
                    else -> BrewMuted
                }
                withStyle(SpanStyle(color = c)) { append(line) }
            }
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 5.dp)
            .clip(RoundedCornerShape(BrewRadiusSmall))
            .background(BrewPanelAlt)
            .border(1.dp, BrewBorder, RoundedCornerShape(BrewRadiusSmall))
            .padding(horizontal = 9.dp, vertical = 6.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded },
        ) {
            Text(
                // 统计行：改动量的最低成本摘要（Codex 的 apply_patch 卡同款形状）
                text = "+$added/−$removed",
                color = BrewText,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.weight(1f))
            Icon(
                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null,
                tint = BrewMuted,
                modifier = Modifier.size(14.dp),
            )
        }
        if (expanded) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = styled,
                fontSize = 10.sp,
                lineHeight = 14.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 24,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
            if (diff.length >= AgentStep.MAX_DIFF_CHARS) {
                Text(
                    text = stringResource(R.string.chat_cmd_output_truncated),
                    color = BrewDim,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

/**
 * 「任务计划」**图标**：常驻在输入框上方那一行（与 [ContextChip] 并列），点开独立计划浮层（[PlanSheet]）。
 *
 * ## 形态由来（2026-10-12 用户要求）
 * ①图标要在这一行（不在聊天流里）；②与上下文图标**并列**显示。⇒ 只画一个图标：
 * 计划状态全由**颜色**表达（全部完成=绿 / 本轮进行中=琥珀 / 无计划或已收尾未完成=弱色），
 * 文字（"任务计划"、`2/5`）都收进弹层，避免底栏堆字、也避免两个入口一高一低。
 *
 * ★ 恒常可见（`step` 可为 null）：用户找不到入口会以为"这功能没了"；没有计划时点开
 * [PlanSheet] 会给一句"当前没有任务计划"。
 */
@Composable
internal fun PlanChip(step: AgentStep?, onClick: () -> Unit) {
    val steps = step?.planSteps.orEmpty()
    val allDone = steps.isNotEmpty() && steps.all { it.status == "done" }
    val live = step != null && isPlanLive(step)
    Icon(
        imageVector = Icons.Outlined.Checklist,
        contentDescription = stringResource(R.string.chat_plan_title),
        tint = when {
            allDone -> BrewSuccess
            live -> BrewAmber
            else -> BrewMuted
        },
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable { onClick() }
            .padding(horizontal = 6.dp, vertical = 5.dp)
            .size(16.dp),
    )
}

/**
 * 任务计划浮层（底部弹层）：计划的**唯一完整视图**。
 *
 * - 头部：图标（最左）+ 标题 + 完成度 + 收起/展开箭头；
 * - 点头部 = 在「整份清单」与「只有标题」之间切换（用户要的"手动缩小展开"）；
 * - 下划 / 点遮罩 = 关闭（[onDismiss]）；入口在输入框上方那一行的 [PlanChip]，可再次点开。
 *
 * ★ `step` 可为 null（2026-10-12）：入口图标**恒常可见**，而此时可能一条计划都还没有 ——
 *   这种情况给一句"当前没有任务计划"，而不是弹一个空壳（空白弹层会被当成"坏了"）。
 *
 * ⚠️ 内容取自**当前最新的那条计划步骤**（ChatScreen 侧实时取，见调用处）——
 * 生成过程中每一步的状态更新会直接反映在这里，不需要本组件自己做状态机。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlanSheet(step: AgentStep?, onDismiss: () -> Unit) {
    val steps = step?.planSteps.orEmpty()
    val doneCount = steps.count { it.status == "done" }
    val live = step != null && isPlanLive(step)
    val sheetState = rememberModalBottomSheetState()
    var expanded by remember { mutableStateOf(true) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = BrewPanel,
        contentColor = BrewText,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 18.dp, end = 18.dp, bottom = 24.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    // 没有清单可展开时头部不该可点（点了没反应像坏了）
                    .let { m -> if (steps.isEmpty()) m else m.clickable { expanded = !expanded } }
                    .padding(vertical = 4.dp),
            ) {
                Icon(
                    imageVector = Icons.Outlined.Checklist,
                    contentDescription = null,
                    tint = when {
                        steps.isEmpty() -> BrewMuted
                        doneCount == steps.size -> BrewSuccess
                        live -> BrewAmber
                        else -> BrewDim
                    },
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.chat_plan_title),
                    color = BrewTextBright,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.weight(1f))
                if (steps.isNotEmpty()) {
                    Text(
                        text = "$doneCount/${steps.size}",
                        color = BrewDim,
                        fontSize = 12.sp,
                    )
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                        contentDescription = null,
                        tint = BrewMuted,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            if (steps.isEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.chat_plan_empty),
                    color = BrewDim,
                    fontSize = 12.sp,
                )
            } else if (expanded) {
                Spacer(Modifier.height(8.dp))
                steps.forEach { ps -> PlanStepRow(ps, live = live) }
            }
        }
    }
}

/**
 * 这份计划是否**还在跑**（决定要不要转圈 / 用琥珀色）。
 *
 * ★ 2026-10-12 修「任务清单还在转」：转圈的判据原先只看 `planSteps[].status == "in_progress"`。
 * 可那个字段只在 `update_plan` 被再次调用时才会被改写 —— 计划中途结束时它**没人去翻**，
 * 于是收尾后（甚至重启回放后）那一步会永远转圈。这里把判据换成**外层步骤的状态**：
 * 收尾时 `finishTrace` / `settleStaleTrace` 一定会把外层 RUNNING 写成终态，转圈自然停。
 */
private fun isPlanLive(step: AgentStep): Boolean =
    step.state == AgentStep.State.RUNNING || step.state == AgentStep.State.AWAITING_CONFIRM

@Composable
private fun PlanStepRow(ps: com.rokidlab.phone.ai.AgentPlan.PlanStep, live: Boolean) {
    val (tint, labelColor) = when (ps.status) {
        "done" -> BrewSuccess to BrewMuted
        "in_progress" -> BrewAmber to BrewText
        else -> BrewDim to BrewText
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // 只有"本轮还在跑"才转圈；已收尾的 in_progress 用静态 ▸ 表示（见 [isPlanLive]）
        if (ps.status == "in_progress" && live) {
            CircularProgressIndicator(
                modifier = Modifier
                    .padding(top = 2.dp)
                    .size(10.dp),
                strokeWidth = 1.3.dp,
                color = BrewAmber,
            )
        } else {
            Text(
                text = when (ps.status) {
                    "done" -> "✓"
                    "in_progress" -> "▸"
                    else -> "·"
                },
                color = tint,
                fontSize = 11.sp,
                lineHeight = 14.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(12.dp),
            )
        }
        Spacer(Modifier.width(5.dp))
        Text(
            text = ps.title,
            color = labelColor,
            fontSize = 11.sp,
            lineHeight = 14.sp,
        )
    }
}

/**
 * 一步过程的标题。
 *
 * - **工具行**：把 `AgentStep.title`（模型发来的 wire name）换成**工具设置页里同一套名字** ——
 *   `get_current_time` → "当前时间"、MCP 工具 → "服务器名 · 工具名"。映射只在
 *   [ToolRegistry.displayNameOf] 一处做；查不到（伪工具、已被删除的 MCP server 的历史消息）
 *   **自动回退成原名**，不会变成空串。
 * - **思考行**：`title` 按约定是空串，文案由 [stepLabel] 本地化。
 *
 * ⚠️ 这是**纯展示层替换**：落盘/事件流/日志里仍是 wire name，所以旧历史消息也会一起变成友好名
 * （不需要数据迁移）。代价是界面名字与日志名字不一致 —— 要拿原始标识符排查请走
 * `SessionTraceDialog`。
 */
@Composable
private fun stepText(step: AgentStep): String {
    val raw = step.title
    if (step.kind == AgentStep.Kind.THINKING || raw.isBlank()) return stepLabel(step)
    return ToolRegistry.displayNameOf(raw, LocalContext.current)
}

/**
 * 一步过程的文案（工具类步骤的标题就是工具名，不走这里）。
 *
 * 服务层只发语义（kind + state），文案在这里本地化 —— 见 [AgentStep.title] 的说明。
 * - 思考：`RUNNING`「正在思考」/ `OK`「思考完毕」
 * - 知识库检索：`RUNNING`「正在检索知识库」/ `OK`「已检索知识库」
 */
@Composable
private fun stepLabel(step: AgentStep): String = when {
    // —— 知识库检索（自动 RAG）：标题恒为空，文案在这里本地化。——
    // 两态刻意都只是"检索了/在检索"，**不**在标题里说"命中/没命中" ——
    // 命中与否是数据，放在 detail 里（用户核对来源用），标题只描述动作。
    step.kind == AgentStep.Kind.KNOWLEDGE && step.state == AgentStep.State.RUNNING ->
        stringResource(R.string.chat_trace_kb_running)

    step.kind == AgentStep.Kind.KNOWLEDGE -> stringResource(R.string.chat_trace_kb_done)

    // 压缩历史：标题说动作，细节（压了多少/失败）在 detail 里
    step.kind == AgentStep.Kind.COMPRESS -> stringResource(R.string.chat_trace_compress_done)

    // ★ 收尾阶段（2026-10-10）：标题恒为空（AgentStep.SUMMARY_STEP_TITLE），
    //   文案在 UI 本地化。与"正在思考"**刻意区分**：这一段是框架在补跑文件落盘，
    //   用户误以为"它还在想"就会以为早该结束了。
    step.kind == AgentStep.Kind.SUMMARY && step.state == AgentStep.State.RUNNING ->
        stringResource(R.string.chat_trace_summary_running)

    step.kind == AgentStep.Kind.SUMMARY -> stringResource(R.string.chat_trace_summary_done)

    // ★ 框架行为（重试 / 路由降载 / 预算追加）：标题恒为空，detail 是人话短语
    step.kind == AgentStep.Kind.FRAMEWORK -> stringResource(R.string.chat_trace_framework)

    // ★ 上下文注入：标题恒为空，detail 只含**来源摘要**（不含注入内容，见 AgentStep.contextInjected）
    step.kind == AgentStep.Kind.MEMORY -> stringResource(R.string.chat_trace_memory)

    // ★ 自省：框架发现了自己的问题（重复调用 / 标记泄漏 / 诉求与动作不匹配）
    step.kind == AgentStep.Kind.REFLECT -> stringResource(R.string.chat_trace_reflect)

    // ★ 「等你点头」与「已取消」独立成文案（2026-10-06 审计修）：
    // 确认等待是 18~40s 的同步阻塞，若沿用"执行中…"，用户看到的是一个
    // 无任何说明的转圈点 —— 分不出"正在拨号"和"正在等我点头"，会以为卡死。
    step.kind == AgentStep.Kind.TOOL && step.state == AgentStep.State.AWAITING_CONFIRM ->
        stringResource(R.string.chat_trace_tool_awaiting_confirm)

    // 用户自己点的取消：用"已取消"而不是红色失败 —— 那是他的决定，不是错误
    step.state == AgentStep.State.CANCELLED -> stringResource(R.string.chat_trace_tool_cancelled)

    // 防御性兜底，不是常规路径：工具步骤的 title 按约定恒非空（见 AgentStep.tool），
    // 所以正常永远走不到这一行。之所以保留而不是删掉 —— 一旦服务层漏填了工具名，
    // 删掉它会掉进下面的思考文案里，工具步骤显示"思考完毕"比显示"执行中…"误导得多。
    step.kind == AgentStep.Kind.TOOL -> stringResource(R.string.chat_trace_tool_running)
    step.state == AgentStep.State.RUNNING -> stringResource(R.string.chat_trace_thinking)
    else -> stringResource(R.string.chat_trace_thought)
}

// ===== 运行卡片：本地交互式程序的输出流（见 InteractiveRunManager） =====
@Composable
private fun RunOutputCard(msg: ChatMsg) {
    val label = ChatStateHolder.runLabel(msg.runId!!)
    // 还在跑：实时从管理器读输出流；已结束：content 已定格（finishRunCard 收尾）
    val run = InteractiveRunManager.runs.collectAsState().value.firstOrNull { it.id == msg.runId }
    val output = run?.output?.collectAsState()?.value ?: msg.content
    val running = run != null && run.alive

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 300.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(BrewPanel)
            .border(1.dp, BrewBorder, RoundedCornerShape(10.dp)),
    ) {
        // 标题条：语言标签 + 运行状态
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(BrewChat.copy(alpha = 0.12f))
                .padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                color = BrewChat,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.width(8.dp))
            if (running) {
                CircularProgressIndicator(modifier = Modifier.size(10.dp), strokeWidth = 1.5.dp, color = BrewChat)
                Spacer(Modifier.width(5.dp))
                Text(stringResource(R.string.chat_run_card_running), color = BrewMuted, fontSize = 10.sp)
            } else {
                Text(stringResource(R.string.chat_run_card_done), color = BrewDim, fontSize = 10.sp)
            }
        }
        // 输出区：限高滚动，新输出自动跟随到最新行
        val outScroll = rememberScrollState()
        androidx.compose.runtime.LaunchedEffect(output) {
            outScroll.animateScrollTo(outScroll.maxValue)
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 180.dp)
                .verticalScroll(outScroll)
                .padding(horizontal = 10.dp, vertical = 7.dp),
        ) {
            Text(
                text = output.ifBlank { stringResource(R.string.chat_code_no_output) },
                color = BrewTextBright,
                fontSize = 11.5.sp,
                lineHeight = 16.sp,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                softWrap = true,
            )
        }
    }
}
