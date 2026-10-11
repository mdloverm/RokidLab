package com.rokidlab.phone.store

import android.content.Context
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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
            if (!isUser && msg.trace.isNotEmpty()) {
                TraceBlock(msg.id, msg.trace, msg.usage, expandTrace)
                Spacer(Modifier.height(6.dp))
            }
            // 工具执行阶段正文还是空的：此时只显示过程卡片，等正文开始流式生成再出现气泡，
            // 避免留下一个「只有时间戳的空气泡」。
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
                    // 图片消息：先渲染图片卡片，再附caption 文本（仅手机端；眼镜端 TTS/显示不受影响）。
                    msg.imageUrl?.let { url ->
                        ChatImageCard(url)
                        if (msg.content.isNotBlank()) {
                            // ★ 走 markdown 而不是纯 Text（2026-10-06 审计修）：
                            // caption 由模型自由填写，它常写成
                            //「这是 **架构图**：\n- 第一层\n- 第二层」这种带格式的内容。
                            // 用 Text 的话星号与连字符会**原样显示**。
                            // 更要紧的是：一条消息里"先流出一段正文、再挂上图"时，
                            // 整个气泡会改走这条分支 ⇒ 前面那段已排版的正文
                            // 也会跟着退化成纯文本（整条回复掉格式）。
                            ChatMarkdownBody(
                                text = msg.content,
                                textColor = if (isUser) BrewBg else BrewTextBright,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    } ?: msg.filePath?.let { path ->
                        // 文件消息（用户上传 / AI 产出）：文件卡 + caption，形态与图片一致
                        ChatFileCard(
                            path = path,
                            name = msg.fileName ?: path.substringAfterLast('/'),
                            chars = msg.fileChars,
                        )
                        if (msg.content.isNotBlank()) {
                            // 同上：caption 也要走 markdown（理由见图片分支的注释）
                            ChatMarkdownBody(
                                text = msg.content,
                                textColor = if (isUser) BrewBg else BrewTextBright,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                        }
                    } ?: run {
                        // 普通文字消息：轻量 Markdown 渲染（代码块/标题/列表/加粗），整体支持长按选字
                        ChatMarkdownBody(
                            text = msg.content,
                            textColor = if (isUser) BrewBg else BrewTextBright,
                        )
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
                                    .clickable { onActions() },
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
                                    .clickable { onSpeak() },
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
private fun TraceBlock(msgId: Long, steps: List<AgentStep>, usage: MsgUsage?, expandByDefault: Boolean) {
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
            .padding(horizontal = 10.dp, vertical = 8.dp),
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
                // update_plan 渲染成勾选清单，不进工具时间线（「执行 update_plan…」是黑话）
                when {
                    step.kind == AgentStep.Kind.PLAN -> PlanStepCard(step)
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
                    imageVector = when (step.kind) {
                        AgentStep.Kind.THINKING -> Icons.Outlined.Psychology
                        // 知识库检索：书 —— 一眼区分"模型调了工具"和"App 自己先查了你的文档"
                        AgentStep.Kind.KNOWLEDGE -> Icons.Outlined.MenuBook
                        AgentStep.Kind.TOOL -> Icons.Outlined.Build
                        // PLAN 不走进时间线（由 PlanStepCard 渲染），仅为 when 穷尽
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
                )
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
            Text(
                text = cmdStateLabel(step.state),
                color = color,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
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
 * update_plan 的**勾选清单**卡片：模型为多步任务立的计划，同 key 覆盖所以只渲染最新一版。
 *
 * 状态配色与时间线一致：done=BrewSuccess、in_progress=BrewAmber（转圈）、pending=BrewDim；
 * 完成的标题置弱，进行中的高亮 —— 不展开多行长文，眼镜式扫一眼就知道做到第几步。
 */
@Composable
private fun PlanStepCard(step: AgentStep) {
    val steps = step.planSteps
    if (steps.isEmpty()) {
        // 解析失败的脏数据兜底：退回普通时间线行（至少不空白）
        TraceStepRow(step)
        return
    }
    val doneCount = steps.count { it.status == "done" }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 7.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(BrewPanelAlt)
            .border(1.dp, BrewBorder, RoundedCornerShape(8.dp))
            .padding(horizontal = 9.dp, vertical = 7.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Outlined.Checklist,
                contentDescription = null,
                tint = if (doneCount == steps.size) BrewSuccess else BrewAmber,
                modifier = Modifier.size(13.dp),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                text = stringResource(R.string.chat_plan_title),
                color = BrewText,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "$doneCount/${steps.size}",
                color = BrewDim,
                fontSize = 11.sp,
            )
        }
        Spacer(Modifier.height(5.dp))
        steps.forEach { ps ->
            PlanStepRow(ps)
        }
    }
}

@Composable
private fun PlanStepRow(ps: com.rokidlab.phone.ai.AgentPlan.PlanStep) {
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
        if (ps.status == "in_progress") {
            CircularProgressIndicator(
                modifier = Modifier
                    .padding(top = 2.dp)
                    .size(10.dp),
                strokeWidth = 1.3.dp,
                color = BrewAmber,
            )
        } else {
            Text(
                text = if (ps.status == "done") "✓" else "·",
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
