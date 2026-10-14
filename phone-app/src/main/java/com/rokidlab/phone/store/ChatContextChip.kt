package com.rokidlab.phone.store

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DonutLarge
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rokidlab.phone.R
import com.rokidlab.phone.ai.ContextUsage
import com.rokidlab.phone.design.BrewAmber
import com.rokidlab.phone.design.BrewBg
import com.rokidlab.phone.design.BrewBorder
import com.rokidlab.phone.design.BrewChat
import com.rokidlab.phone.design.BrewMuted
import com.rokidlab.phone.design.BrewPanel
import com.rokidlab.phone.design.BrewRed
import com.rokidlab.phone.design.BrewText
import com.rokidlab.phone.design.BrewTextBright
import kotlin.math.roundToInt

/** 上下文占用的告警阈值：超过它才开始用琥珀/红色抢占注意力 */
private const val WARN_RATIO = 0.6f
private const val DANGER_RATIO = 0.85f

/**
 * 上下文占用**图标**（输入框上方那一行，与「任务计划」图标并列）。
 *
 * 存在的理由：会话记忆的裁剪（默认档 25 条 / 8000 token）原本是**完全静默**的 ——
 * 用户只会感到「它怎么忘了」，却无法把这件事归因到任何东西上。这里把预算做成一个常驻图标，
 * 平时不抢注意力（常态弱色），逼近上限时变成琥珀/红色；具体数字点开 [ContextUsageSheet] 看。
 *
 * ★ 2026-10-12 用户要求：**细长条不要了，改成图标与任务计划图标并列**。
 *   取舍：原先贴在条下面的那行提示文字（"上下文已用 68%" / "已压缩"）随之取消 ——
 *   "快要不够了"由图标颜色承担，"为什么/还剩多少"在详情弹窗里答。这是刻意的：常驻一行说明
 *   会让聊天页多一条文本噪声，而图标能扫一眼就发现异常。
 *
 * @param usage 上下文占用快照；[ContextUsage.enabled] 为 false（会话记忆已关闭）时不渲染
 */
@Composable
internal fun ContextChip(
    usage: ContextUsage,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!usage.enabled) return
    val ratio = usage.ratio
    val tint = when {
        ratio >= DANGER_RATIO -> BrewRed
        ratio >= WARN_RATIO -> BrewAmber
        else -> BrewMuted
    }
    Icon(
        imageVector = Icons.Outlined.DonutLarge,
        contentDescription = stringResource(R.string.chat_context_title),
        tint = tint,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .clickable { onClick() }
            .padding(horizontal = 6.dp, vertical = 5.dp)
            .size(16.dp),
    )
}

/**
 * 上下文详情**底部弹层**：把两个预算的具体数字摊开，并说明压缩行为。
 *
 * ★ 2026-10-13 用户要求：由居中 Dialog 改为与 [PlanSheet] 一致的下弹出式 ——
 *   聊天页功能入口打开的浮层统一底部弹层形态（规则见 project_rules.md「聊天页功能浮层一律下弹出式」）。
 *
 * 用户看到进度条变红后，第一个问题是「多少算满」「满了会怎样」—— 这里一次答完，
 * 顺带给出「开新对话」这个唯一能立刻清空上下文的操作。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ContextUsageSheet(
    usage: ContextUsage,
    /** 点「立即压缩」：返回值 = 是否真的压了（false = 历史已经够短，没什么可压的） */
    onCompact: () -> Boolean,
    onNewSession: () -> Unit,
    /** 打开执行轨迹（方案 §4.3.5）：从"用了多少"自然过渡到"这些是怎么用掉的" */
    onOpenTrace: () -> Unit,
    onDismiss: () -> Unit,
) {
    // null = 还没点过；false = 点了但无可压缩 → 必须给一句反馈，
    // 否则这个按钮在"已经足够短"的历史上点起来像坏的
    var compacted by remember { mutableStateOf<Boolean?>(null) }
    val ratio = usage.ratio
    val barColor = when {
        ratio >= DANGER_RATIO -> BrewRed
        ratio >= WARN_RATIO -> BrewAmber
        else -> BrewChat
    }
    val sheetState = rememberModalBottomSheetState()
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
            Text(
                text = stringResource(R.string.chat_context_title),
                color = BrewTextBright,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(BrewBorder.copy(alpha = 0.3f)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(ratio.coerceIn(0.02f, 1f))
                            .fillMaxHeight()
                            .background(barColor),
                    )
                }
                Text(
                    text = "${(ratio * 100).roundToInt()}%",
                    color = barColor,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(start = 10.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(
                    R.string.chat_context_messages,
                    usage.messages,
                    usage.messageLimit,
                ),
                color = BrewMuted,
                fontSize = 12.sp,
            )
            Text(
                text = stringResource(R.string.chat_context_history_tokens, usage.tokens, usage.tokenLimit),
                color = BrewMuted,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
            // 成本可观测（§4.3.4）：真实用量来自服务端返回的 usage，经事件流落到 TurnEnd 上。
            // ⚠️ 拿不到时必须换成"约 N 字"的口径并写明原因 —— 用字符数编一个 token 数，
            //    用户会把它当账单读。
            usage.lastTurn?.let { last ->
                Text(
                    // 合计口径（2026-09-25）：不再拆输入/输出。走 estimate 分支的轮次
                    // 服务端根本没回 token，不能拿 0 凑数（见 TurnSnapshot.estimated）
                    text = if (last.estimated) {
                        stringResource(R.string.chat_context_tokens_est, last.outputChars)
                    } else {
                        stringResource(
                            R.string.chat_context_tokens,
                            (last.inputTokens ?: 0) + (last.outputTokens ?: 0),
                        )
                    },
                    color = BrewMuted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
                val meta = buildList {
                    last.elapsedMs?.let { add(stringResource(R.string.chat_context_elapsed, it / 1000.0)) }
                    // 只报 >1：1 次是常态，写出来只是噪音
                    last.modelCalls?.takeIf { it > 1 }?.let {
                        add(stringResource(R.string.chat_context_model_calls, it))
                    }
                }.joinToString(" · ")
                if (meta.isNotEmpty()) {
                    Text(
                        text = meta,
                        color = BrewMuted,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            usage.sessionTotals?.let { t ->
                Text(
                    // 合计口径（2026-09-25）：SessionTotals.total = input + output
                    text = stringResource(
                        R.string.chat_context_session_total,
                        t.total,
                        t.turnsWithUsage,
                    ),
                    color = BrewMuted,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (usage.windowLimited) {
                Text(
                    text = stringResource(R.string.chat_context_window_limited),
                    color = BrewMuted,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (usage.compressed) {
                Text(
                    text = stringResource(R.string.chat_context_compressed_hint),
                    color = BrewAmber,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.chat_context_explain),
                color = BrewMuted,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
            // 立即压缩（compaction 接缝的手动入口）：用户唯一的"腾地方但不清空"选择。
            // 放在「开新对话」旁边是刻意的 —— 那才是更彻底的选项，这里给的是"舍不得清空"的中间态。
            Text(
                text = stringResource(R.string.chat_context_compact),
                color = BrewChat,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .border(1.dp, BrewBorder, RoundedCornerShape(8.dp))
                    .clickable { compacted = onCompact() }
                    .padding(vertical = 9.dp),
                textAlign = TextAlign.Center,
            )
            if (compacted == false) {
                Text(
                    text = stringResource(R.string.chat_context_compact_nothing),
                    color = BrewMuted,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 5.dp),
                )
            }
            // 执行轨迹入口（§4.3.5）：这个面板回答"用了多少"，轨迹视图回答"这些是怎么用掉的"。
            // 放在一起是因为它们是同一个疑问的两半 —— 用户看到进度条变红后，
            // 想知道的不只是数字，还有"到底什么东西占了这么多"。
            Text(
                text = stringResource(R.string.chat_events_open),
                color = BrewChat,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .border(1.dp, BrewBorder, RoundedCornerShape(8.dp))
                    .clickable { onOpenTrace() }
                    .padding(vertical = 9.dp),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = onNewSession,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = BrewChat,
                        contentColor = BrewBg,
                    ),
                ) {
                    Text(stringResource(R.string.chat_context_new_session), fontSize = 13.sp)
                }
                Button(
                    onClick = onDismiss,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 8.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = BrewPanel,
                        contentColor = BrewTextBright,
                    ),
                ) {
                    Text(stringResource(R.string.chat_common_confirm), fontSize = 13.sp)
                }
            }
        }
    }
}
