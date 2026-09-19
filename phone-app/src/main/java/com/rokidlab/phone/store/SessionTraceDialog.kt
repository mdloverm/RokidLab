package com.rokidlab.phone.store

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.rokidlab.phone.R
import com.rokidlab.phone.ai.AgentSessionManager
import com.rokidlab.phone.ai.session.SessionTrace
import com.rokidlab.phone.design.BrewAmber
import com.rokidlab.phone.design.BrewBg
import com.rokidlab.phone.design.BrewBorder
import com.rokidlab.phone.design.BrewChat
import com.rokidlab.phone.design.BrewMuted
import com.rokidlab.phone.design.BrewPanel
import com.rokidlab.phone.design.BrewRed
import com.rokidlab.phone.design.BrewTextBright
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 执行轨迹视图（方案 §4.3.5 —— 整个方案的收口）。
 *
 * ★ 它解决的是**信任**，不是体验：改造前用户能看到的只有"我发了什么 / 它回了什么"，
 *   中间的**依据与动作**（注入了哪些记忆和资料、调了哪个工具、参数与返回是什么、
 *   哪几轮被压缩掉了）全部不可见。于是"它怎么知道这个""它到底做了什么"只能靠猜。
 *
 * 数据来自事件流（`AgentSessionManager.trajectory()`），所以这里的每一条都能追到
 * 一个具体的 `seq` —— "模型看到的每一字节都可回溯"这句话到这里才对用户成立。
 *
 * 两条交互设计上的取舍：
 *  - **按来源分类着色**（对话 / 工具 / 注入 / 压缩 / 作废 / 过程）：用户扫一眼就能看出
 *    "这段是它自己查的"还是"这段是喂给它的"；
 *  - **默认只显示一行标题，点开才看原文**：注入内容与工具返回动辄上千字，
 *    全部铺开等于让人自己去找重点。
 */
@Composable
internal fun SessionTraceDialog(onDismiss: () -> Unit) {
    var trace by remember { mutableStateOf<List<SessionTrace.Item>>(emptyList()) }
    var summary by remember { mutableStateOf<SessionTrace.Summary?>(null) }
    var loading by remember { mutableStateOf(true) }
    /** 分类筛选；null = 全部 */
    var filter by remember { mutableStateOf<SessionTrace.Category?>(null) }
    /** 已展开的条目 seq；null = 都收起 */
    var expanded by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(Unit) {
        loading = true
        // 事件流读盘是阻塞 IO，不能挂在组合里（会卡住首帧）
        trace = withContext(Dispatchers.IO) {
            runCatching { AgentSessionManager.trajectory() }.getOrDefault(emptyList())
        }
        summary = withContext(Dispatchers.IO) {
            runCatching { AgentSessionManager.trajectorySummary() }.getOrNull()
        }
        loading = false
    }

    val shown = remember(trace, filter) {
        if (filter == null) trace else trace.filter { it.category == filter }
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
                text = stringResource(R.string.chat_events_title),
                color = BrewTextBright,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.chat_events_hint),
                color = BrewMuted,
                fontSize = 11.sp,
                lineHeight = 16.sp,
            )
            summary?.let {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.chat_events_summary, it.turns, it.toolCalls, it.injects),
                    color = BrewMuted,
                    fontSize = 11.sp,
                )
            }

            Spacer(Modifier.height(10.dp))
            // 分类筛选：7 个胶囊在中文下会超出宽度，所以横向可滚
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(
                    label = stringResource(R.string.chat_events_filter_all),
                    selected = filter == null,
                    onClick = { filter = null },
                )
                SessionTrace.Category.entries.forEach { cat ->
                    Spacer(Modifier.width(6.dp))
                    FilterChip(
                        label = stringResource(categoryLabelRes(cat)),
                        selected = filter == cat,
                        onClick = { filter = cat },
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            when {
                loading -> Text(
                    text = stringResource(R.string.agent_longterm_manage_loading),
                    color = BrewMuted,
                    fontSize = 12.sp,
                )

                shown.isEmpty() -> Text(
                    text = stringResource(R.string.chat_events_empty),
                    color = BrewMuted,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                )

                else -> LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    items(shown, key = { it.seq }) { item ->
                        TraceRow(
                            item = item,
                            expanded = expanded == item.seq,
                            onToggle = { expanded = if (expanded == item.seq) null else item.seq },
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) {
                    Text(
                        text = stringResource(R.string.chat_events_close),
                        color = BrewChat,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}

@Composable
private fun TraceRow(
    item: SessionTrace.Item,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val accent = categoryColor(item.category)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = item.detail != null, onClick = onToggle)
            .padding(top = 7.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "#${item.seq}",
                color = BrewMuted,
                fontSize = 10.sp,
            )
            item.turn?.let {
                Spacer(Modifier.width(6.dp))
                Text(text = "轮$it", color = BrewMuted, fontSize = 10.sp)
            }
            Spacer(Modifier.width(6.dp))
            Text(
                text = stringResource(categoryLabelRes(item.category)),
                color = accent,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
            )
        }
        Text(
            text = item.title,
            color = BrewTextBright,
            fontSize = 12.5.sp,
            lineHeight = 17.sp,
            modifier = Modifier.padding(top = 2.dp),
        )
        if (expanded && item.detail != null) {
            Text(
                text = item.detail,
                color = BrewMuted,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 5.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(BrewBg.copy(alpha = 0.5f))
                    .padding(8.dp),
            )
        }
        HorizontalDivider(
            color = BrewBorder.copy(alpha = 0.4f),
            modifier = Modifier.padding(top = 7.dp),
        )
    }
}

@Composable
private fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        color = if (selected) BrewBg else BrewMuted,
        fontSize = 11.sp,
        fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (selected) BrewChat else BrewBg.copy(alpha = 0.5f))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

private fun categoryLabelRes(category: SessionTrace.Category): Int = when (category) {
    SessionTrace.Category.MESSAGE -> R.string.chat_events_cat_message
    SessionTrace.Category.TOOL -> R.string.chat_events_cat_tool
    SessionTrace.Category.INJECT -> R.string.chat_events_cat_inject
    SessionTrace.Category.COMPACTION -> R.string.chat_events_cat_compaction
    SessionTrace.Category.CUT -> R.string.chat_events_cat_cut
    SessionTrace.Category.LIFECYCLE -> R.string.chat_events_cat_lifecycle
}

private fun categoryColor(category: SessionTrace.Category) = when (category) {
    SessionTrace.Category.MESSAGE -> BrewTextBright
    SessionTrace.Category.TOOL -> BrewChat
    SessionTrace.Category.INJECT -> BrewAmber
    SessionTrace.Category.COMPACTION -> BrewAmber
    SessionTrace.Category.CUT -> BrewRed
    SessionTrace.Category.LIFECYCLE -> BrewMuted
}
