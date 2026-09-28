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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment.Companion.CenterVertically
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rokidlab.phone.R
import com.rokidlab.phone.design.BrewBg
import com.rokidlab.phone.design.BrewBorder
import com.rokidlab.phone.design.BrewChat
import com.rokidlab.phone.design.BrewDialog
import com.rokidlab.phone.design.BrewDialogActions
import com.rokidlab.phone.design.BrewDialogButton
import com.rokidlab.phone.design.BrewDialogContent
import com.rokidlab.phone.design.BrewMuted
import com.rokidlab.phone.design.BrewPanel
import com.rokidlab.phone.design.BrewPanelHi
import com.rokidlab.phone.design.BrewRed
import com.rokidlab.phone.design.BrewShapeMedium
import com.rokidlab.phone.design.BrewShapeStandard
import com.rokidlab.phone.design.BrewTextBright
import com.rokidlab.phone.design.BrewWarning
import com.rokidlab.phone.proactive.HEAD_DOWN_LIMIT_MS
import com.rokidlab.phone.proactive.MOVE_LIMIT_MS
import com.rokidlab.phone.proactive.ProactiveGate
import com.rokidlab.phone.proactive.ProactiveGatePolicy
import com.rokidlab.phone.proactive.STILL_LIMIT_MS

/**
 * 「主动性」下弹面板（聊天输入栏 Bolt 图标 → ModalBottomSheet，与知识库面板同款形态）。
 *
 * v3 重构成「陪伴场景 + 闲聊频率」两层，对齐主动式陪伴的四层时间逻辑：
 *
 *  1. **场景格子**（5 格）：每格是一组能力预设，副标题就是体感承诺（「早安、日程、晚安」）。
 *     每场景**独立记住**自己的功能组合 —— 手动微调后切走再切回自动恢复（iOS 专注模式式），
 *     不会被别的场景的调整污染；
 *  2. **额度头卡**：「今天还可发 X 条」= min(闲聊剩余额度, 剩余清醒时间 ÷ 当前闲聊间隔)，
 *     所以它永远是真能发出去的数，而不是宽泛的档位承诺；
 *  3. **闲聊频率行**（安静/适中/勤快/形影不离）：只作用于**闲聊层**（空闲搭话）。
 *     早晚安（仪式层）、日程（记忆层）、关怀（行为层）各有自己的时机，不占这个额度；
 *  4. **功能开关行**：副标题一律写真实触发条件（关怀阈值、闲聊间隔、拍速都是实算值）。
 *
 * 勿扰场景是总闸：整组「频率 + 功能」收起，只留场景网格与静默说明，避免一屏禁用态噪声。
 * 用户自己设的定时提醒不受场景影响（闹钟语义，「设了不响」最伤信任）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProactivePanel(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val gate = ProactiveGate.get(ctx)
    var scene by remember { mutableStateOf(gate.scene()) }
    var freq by remember { mutableStateOf(gate.chatFreq()) }
    var features by remember { mutableStateOf(featureStates(gate)) }
    var quietStart by remember { mutableStateOf(gate.quietStartMin()) }
    var quietEnd by remember { mutableStateOf(gate.quietEndMin()) }
    var pickStart by remember { mutableStateOf(false) }
    var pickEnd by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = BrewPanel,
        contentColor = BrewTextBright,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
        ) {
            Text(
                text = stringResource(R.string.chat_proactive_title),
                color = BrewTextBright,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                // 副标题 = 陪伴默契统计（连续陪伴天数 · 今日互动次数，数据随真实对话自动累积；
                // 亲密度快照不可用时退回通用说明文案）
                text = ProactiveGate.relationSnapshot()?.let {
                    stringResource(R.string.settings_relation_stats, it.streakDays, it.interactionsToday)
                } ?: stringResource(R.string.chat_proactive_subtitle),
                color = BrewMuted,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(12.dp))

            // ── 状态头卡：当前场景 + 今天还能发几条 + 决策门状态 ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(BrewShapeStandard)
                    .background(BrewPanelHi)
                    .border(1.dp, BrewBorder, BrewShapeStandard)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = CenterVertically) {
                        Text(
                            text = sceneLabel(scene),
                            color = if (scene == ProactiveGatePolicy.Scene.MUTE) BrewMuted else BrewChat,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        if (scene != ProactiveGatePolicy.Scene.MUTE) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = quotaText(gate, scene, features, freq),
                                color = BrewMuted,
                                fontSize = 12.sp,
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = gateStatusText(gate, scene, quietStart, quietEnd),
                        color = BrewMuted,
                        fontSize = 11.sp,
                    )
                }
            }
            Spacer(Modifier.height(14.dp))

            // ── 场景格子：2 列 × 3 行（5 格 + 1 占位），选中即铺开该场景记住的能力组合 ──
            SectionLabel(stringResource(R.string.proactive_section_scene))
            Spacer(Modifier.height(8.dp))
            ProactiveGatePolicy.Scene.entries.chunked(2).forEach { rowScenes ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    rowScenes.forEachIndexed { index, s ->
                        if (index > 0) Spacer(Modifier.width(8.dp))
                        LevelCell(
                            modifier = Modifier.weight(1f),
                            title = sceneLabel(s),
                            desc = sceneDesc(s),
                            selected = s == scene,
                            onClick = {
                                if (s != scene) {
                                    gate.applyScene(s)
                                    scene = s
                                    freq = gate.chatFreq()
                                    features = featureStates(gate)
                                }
                            },
                        )
                    }
                    if (rowScenes.size == 1) Spacer(Modifier.weight(1f))
                }
                Spacer(Modifier.height(8.dp))
            }

            if (scene == ProactiveGatePolicy.Scene.MUTE) {
                Text(
                    text = stringResource(R.string.proactive_quota_muted),
                    color = BrewMuted,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            } else {
                // ── 闲聊频率：只作用闲聊层（空闲搭话），其余三层不占额度 ──
                SectionLabel(stringResource(R.string.proactive_section_freq))
                Spacer(Modifier.height(8.dp))
                Text(
                    text = idleChatHint(features, freq, gate),
                    color = BrewMuted,
                    fontSize = 11.sp,
                )
                Spacer(Modifier.height(8.dp))
                ProactiveGatePolicy.ChatFrequency.entries.chunked(2).forEach { row ->
                    Row(modifier = Modifier.fillMaxWidth()) {
                        row.forEachIndexed { index, f ->
                            if (index > 0) Spacer(Modifier.width(8.dp))
                            LevelCell(
                                modifier = Modifier.weight(1f),
                                title = freqLabel(f),
                                desc = freqDesc(f),
                                selected = f == freq,
                                onClick = {
                                    if (f != freq) {
                                        gate.setChatFreq(f.id)
                                        freq = f
                                        features = featureStates(gate)
                                    }
                                },
                            )
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(8.dp))
                }
                Spacer(Modifier.height(6.dp))

                // ── 免打扰时段（早晚安的锚点就挂在这两个时刻上） ──
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(BrewShapeStandard)
                        .background(BrewPanel)
                        .border(1.dp, BrewBorder, BrewShapeStandard)
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.proactive_quiet_title),
                            color = BrewTextBright,
                            fontSize = 14.sp,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = stringResource(R.string.proactive_quiet_hint),
                            color = BrewMuted,
                            fontSize = 11.sp,
                        )
                    }
                    TimeText(value = quietStart) { pickStart = true }
                    Text(text = " – ", color = BrewMuted, fontSize = 13.sp)
                    TimeText(value = quietEnd) { pickEnd = true }
                }
                Spacer(Modifier.height(14.dp))

                // ── 主动功能：副标题一律是「当前设置下的真实触发条件」 ──
                SectionLabel(stringResource(R.string.proactive_section_features))
                Spacer(Modifier.height(8.dp))
                ProactiveFeatureRow(features, ProactiveGatePolicy.ProactiveFeature.RITUAL, gate) { on ->
                    features = features + (ProactiveGatePolicy.ProactiveFeature.RITUAL to on)
                }
                ProactiveFeatureRow(features, ProactiveGatePolicy.ProactiveFeature.BRIEFING, gate) { on ->
                    features = features + (ProactiveGatePolicy.ProactiveFeature.BRIEFING to on)
                }
                ProactiveFeatureRow(features, ProactiveGatePolicy.ProactiveFeature.CARE, gate) { on ->
                    features = features + (ProactiveGatePolicy.ProactiveFeature.CARE to on)
                }
                ProactiveFeatureRow(features, ProactiveGatePolicy.ProactiveFeature.FOLLOWUP, gate) { on ->
                    features = features + (ProactiveGatePolicy.ProactiveFeature.FOLLOWUP to on)
                }
                ProactiveFeatureRow(features, ProactiveGatePolicy.ProactiveFeature.IDLE_CHAT, gate) { on ->
                    features = features + (ProactiveGatePolicy.ProactiveFeature.IDLE_CHAT to on)
                }
                ProactiveFeatureRow(features, ProactiveGatePolicy.ProactiveFeature.VISION, gate) { on ->
                    features = features + (ProactiveGatePolicy.ProactiveFeature.VISION to on)
                }
                // 会话内陪伴（对话内的话题枯竭/沉默追击）不属于场景六格，单独一行
                ProactiveToggleRow(
                    title = stringResource(R.string.proactive_chat_companion),
                    subtitle = if (scene.maxNudges > 0) {
                        stringResource(R.string.proactive_nudge_times_fmt, scene.maxNudges)
                    } else {
                        stringResource(R.string.proactive_nudge_off)
                    },
                    checked = gate.isChatCompanionEnabled(),
                    onChecked = { gate.setChatCompanionEnabled(it) },
                )

                Text(
                    text = stringResource(R.string.proactive_soft_note),
                    color = BrewMuted,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }

    // ── 免打扰起止时间：点击后直接输入 ──
    if (pickStart) {
        QuietTimeInputDialog(
            title = stringResource(R.string.proactive_quiet_start),
            initialMin = quietStart,
            onConfirm = { m ->
                quietStart = m
                gate.setQuietHours(m, quietEnd)
                pickStart = false
            },
            onDismiss = { pickStart = false },
        )
    }
    if (pickEnd) {
        QuietTimeInputDialog(
            title = stringResource(R.string.proactive_quiet_end),
            initialMin = quietEnd,
            onConfirm = { m ->
                quietEnd = m
                gate.setQuietHours(quietStart, m)
                pickEnd = false
            },
            onDismiss = { pickEnd = false },
        )
    }
}

/** 六格能力开关的当前状态快照（读宿主开关，唯一事实来源） */
private fun featureStates(gate: ProactiveGate): Map<ProactiveGatePolicy.ProactiveFeature, Boolean> =
    ProactiveGatePolicy.ProactiveFeature.entries.associateWith { gate.isFeatureOn(it) }

/**
 * 免打扰时间输入弹窗：点时间 → 文本框直接填，回车或「确定」保存。
 * 支持 `23:00` 与 `2300`（免得数字键盘找不到冒号）两种写法。
 */
@Composable
private fun QuietTimeInputDialog(
    title: String,
    initialMin: Int,
    onConfirm: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf("%02d:%02d".format(initialMin / 60, initialMin % 60)) }
    var invalid by remember { mutableStateOf(false) }
    fun submit() {
        val m = parseQuietInput(text)
        if (m == null) {
            invalid = true
        } else {
            onConfirm(m)
        }
    }
    BrewDialog(onDismiss = onDismiss, title = title, icon = "🌙", color = BrewWarning) {
        BrewDialogContent {
            OutlinedTextField(
                value = text,
                onValueChange = {
                    text = it
                    invalid = false
                },
                singleLine = true,
                isError = invalid,
                placeholder = { Text("23:00", color = BrewMuted) },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier.fillMaxWidth(),
            )
            if (invalid) {
                Text(
                    text = stringResource(R.string.proactive_quiet_input_error),
                    color = BrewRed,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        BrewDialogActions {
            BrewDialogButton(stringResource(R.string.cancel), onClick = onDismiss, color = BrewMuted)
            Spacer(Modifier.width(12.dp))
            BrewDialogButton(stringResource(R.string.confirm), onClick = { submit() }, color = BrewWarning)
        }
    }
}

/** 解析输入的时间：`23:00` / `2300` / `9:30` / `930`；非法返回 null */
private fun parseQuietInput(raw: String): Int? {
    val digits = raw.trim().replace("：", ":").replace(":", "")
    if (digits.length !in 3..4) return null
    val h = digits.dropLast(2).toIntOrNull() ?: return null
    val m = digits.takeLast(2).toIntOrNull() ?: return null
    if (h !in 0..23 || m !in 0..59) return null
    return h * 60 + m
}

/**
 * 额度文案：只对**闲聊层**承诺条数（仪式/记忆/关怀三层不占额度，说进来会误导）。
 * 未开闲聊 → 直接说「不闲聊」；开闲聊但今天时间不够/额度用完 → 「今天不再打扰」。
 */
@Composable
private fun quotaText(
    gate: ProactiveGate,
    scene: ProactiveGatePolicy.Scene,
    features: Map<ProactiveGatePolicy.ProactiveFeature, Boolean>,
    freq: ProactiveGatePolicy.ChatFrequency,
): String {
    if (scene == ProactiveGatePolicy.Scene.MUTE) return stringResource(R.string.proactive_quota_muted)
    val chatOn = features[ProactiveGatePolicy.ProactiveFeature.IDLE_CHAT] == true && freq.dailyCap > 0
    if (!chatOn) return stringResource(R.string.proactive_freq_silent_desc)
    val left = gate.remainingToday()
    return if (left > 0) {
        stringResource(R.string.proactive_quota_left, left)
    } else {
        stringResource(R.string.proactive_quota_none)
    }
}

/**
 * 决策门状态行文案（与 [ProactiveGate.admitProactive] 的准入链同序：
 * 静音 → 冷却 → 响应收敛 → 免打扰 → 运行中）。
 * 写成普通函数而非 remember：场景/免打扰时段一变，状态行必须跟着重算。
 */
@Composable
private fun gateStatusText(
    gate: ProactiveGate,
    scene: ProactiveGatePolicy.Scene,
    quietStart: Int,
    quietEnd: Int,
): String {
    if (scene == ProactiveGatePolicy.Scene.MUTE) {
        return stringResource(R.string.proactive_quota_muted)
    }
    val now = System.currentTimeMillis()
    val cooldownMs = gate.cooldownRemaining(now)
    val penaltyMs = gate.responsePenaltyRemaining(now)
    val ceilH: (Long) -> Int = { ((it + 3_599_999L) / 3_600_000L).toInt() }
    val t = java.time.LocalTime.now()
    return when {
        cooldownMs > 0 -> stringResource(R.string.proactive_gate_cooldown, ceilH(cooldownMs))
        penaltyMs > 0 -> {
            val h = ceilH(penaltyMs)
            if (h >= 2) {
                stringResource(R.string.proactive_gate_penalty_h, h)
            } else {
                stringResource(R.string.proactive_gate_penalty_m, (penaltyMs / 60_000L).toInt())
            }
        }
        ProactiveGatePolicy.inQuietHours(t.hour, t.minute, quietStart, quietEnd) ->
            stringResource(R.string.proactive_gate_quiet)
        else -> stringResource(R.string.proactive_gate_running)
    }
}

/** 闲聊层节奏说明：开闲聊时报真实间隔（含响应率因子），否则说明为何不闲聊 */
@Composable
private fun idleChatHint(
    features: Map<ProactiveGatePolicy.ProactiveFeature, Boolean>,
    freq: ProactiveGatePolicy.ChatFrequency,
    gate: ProactiveGate,
): String {
    val on = features[ProactiveGatePolicy.ProactiveFeature.IDLE_CHAT] == true
    if (!on || freq.dailyCap <= 0) return stringResource(R.string.proactive_freq_silent_desc)
    return intervalText(gate.greetingIntervalMs())
}

/** 间隔文案：≥1 小时按小时（四舍五入），否则按分钟 */
@Composable
private fun intervalText(intervalMs: Long): String =
    if (intervalMs >= 3_600_000L) {
        stringResource(R.string.proactive_duration_hours, ((intervalMs + 1_800_000L) / 3_600_000L).toInt())
    } else {
        stringResource(R.string.proactive_duration_minutes, (intervalMs / 60_000L).toInt())
    }

/** 毫秒 → 分钟（关怀阈值展示用） */
private fun minutesOf(ms: Long): Int = (ms / 60_000L).toInt()

@Composable
private fun SectionLabel(text: String) {
    Text(text = text, color = BrewMuted, fontSize = 12.sp)
}

@Composable
private fun sceneLabel(scene: ProactiveGatePolicy.Scene): String = stringResource(
    when (scene) {
        ProactiveGatePolicy.Scene.MUTE -> R.string.proactive_scene_mute
        ProactiveGatePolicy.Scene.SCHEDULE -> R.string.proactive_scene_schedule
        ProactiveGatePolicy.Scene.LIGHT -> R.string.proactive_scene_light
        ProactiveGatePolicy.Scene.FRIEND -> R.string.proactive_scene_friend
        ProactiveGatePolicy.Scene.FULL -> R.string.proactive_scene_full
    },
)

@Composable
private fun sceneDesc(scene: ProactiveGatePolicy.Scene): String = stringResource(
    when (scene) {
        ProactiveGatePolicy.Scene.MUTE -> R.string.proactive_scene_mute_desc
        ProactiveGatePolicy.Scene.SCHEDULE -> R.string.proactive_scene_schedule_desc
        ProactiveGatePolicy.Scene.LIGHT -> R.string.proactive_scene_light_desc
        ProactiveGatePolicy.Scene.FRIEND -> R.string.proactive_scene_friend_desc
        ProactiveGatePolicy.Scene.FULL -> R.string.proactive_scene_full_desc
    },
)

@Composable
private fun freqLabel(freq: ProactiveGatePolicy.ChatFrequency): String = stringResource(
    when (freq) {
        ProactiveGatePolicy.ChatFrequency.SILENT -> R.string.proactive_freq_silent
        ProactiveGatePolicy.ChatFrequency.MODERATE -> R.string.proactive_freq_moderate
        ProactiveGatePolicy.ChatFrequency.OFTEN -> R.string.proactive_freq_often
        ProactiveGatePolicy.ChatFrequency.CONSTANT -> R.string.proactive_freq_constant
    },
)

@Composable
private fun freqDesc(freq: ProactiveGatePolicy.ChatFrequency): String = stringResource(
    when (freq) {
        ProactiveGatePolicy.ChatFrequency.SILENT -> R.string.proactive_freq_silent_desc
        ProactiveGatePolicy.ChatFrequency.MODERATE -> R.string.proactive_freq_moderate_desc
        ProactiveGatePolicy.ChatFrequency.OFTEN -> R.string.proactive_freq_often_desc
        ProactiveGatePolicy.ChatFrequency.CONSTANT -> R.string.proactive_freq_constant_desc
    },
)

@Composable
private fun LevelCell(
    modifier: Modifier,
    title: String,
    desc: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier
            .clip(BrewShapeStandard)
            .background(if (selected) BrewPanelHi else BrewPanel)
            .border(1.dp, if (selected) BrewChat else BrewBorder, BrewShapeStandard)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(
            text = title,
            color = if (selected) BrewChat else BrewTextBright,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(2.dp))
        Text(text = desc, color = BrewMuted, fontSize = 11.sp)
    }
}

@Composable
private fun TimeText(value: Int, onClick: () -> Unit) {
    Text(
        text = "%02d:%02d".format(value / 60, value % 60),
        color = BrewChat,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(BrewShapeMedium)
            .clickable { onClick() }
            .padding(horizontal = 6.dp, vertical = 4.dp),
    )
}

/**
 * 六格之一：开关 + 该能力在当前设置下的真实触发条件副标题。
 * 切换即写宿主开关并回写「当前场景」的记忆（切走再切回自动恢复）。
 */
@Composable
private fun ProactiveFeatureRow(
    features: Map<ProactiveGatePolicy.ProactiveFeature, Boolean>,
    feature: ProactiveGatePolicy.ProactiveFeature,
    gate: ProactiveGate,
    onLocal: (Boolean) -> Unit,
) {
    val subtitle = when (feature) {
        ProactiveGatePolicy.ProactiveFeature.RITUAL ->
            stringResource(R.string.proactive_feat_ritual_desc)

        ProactiveGatePolicy.ProactiveFeature.BRIEFING ->
            stringResource(R.string.settings_calendar_briefing_on)

        ProactiveGatePolicy.ProactiveFeature.CARE -> stringResource(
            R.string.proactive_care_limits_fmt,
            minutesOf(MOVE_LIMIT_MS),
            minutesOf(STILL_LIMIT_MS),
            minutesOf(HEAD_DOWN_LIMIT_MS),
        )

        ProactiveGatePolicy.ProactiveFeature.FOLLOWUP ->
            stringResource(R.string.settings_followup_on)

        ProactiveGatePolicy.ProactiveFeature.IDLE_CHAT ->
            idleChatHint(features, gate.chatFreq(), gate)

        ProactiveGatePolicy.ProactiveFeature.VISION ->
            stringResource(R.string.proactive_vision_every)
    }
    val title = when (feature) {
        ProactiveGatePolicy.ProactiveFeature.RITUAL -> stringResource(R.string.proactive_ritual)
        ProactiveGatePolicy.ProactiveFeature.BRIEFING -> stringResource(R.string.settings_calendar_briefing)
        ProactiveGatePolicy.ProactiveFeature.CARE -> stringResource(R.string.proactive_care)
        ProactiveGatePolicy.ProactiveFeature.FOLLOWUP -> stringResource(R.string.settings_followup)
        ProactiveGatePolicy.ProactiveFeature.IDLE_CHAT -> stringResource(R.string.settings_idle_greeting)
        ProactiveGatePolicy.ProactiveFeature.VISION -> stringResource(R.string.settings_ambient_vision)
    }
    ProactiveToggleRow(
        title = title,
        subtitle = subtitle,
        checked = features[feature] == true,
        onChecked = {
            gate.setFeature(feature, it)
            onLocal(it)
        },
    )
}

@Composable
private fun ProactiveToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(BrewShapeStandard)
            .background(BrewPanel)
            .border(1.dp, BrewBorder, BrewShapeStandard)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = BrewTextBright,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(2.dp))
            Text(text = subtitle, color = BrewMuted, fontSize = 11.sp)
        }
        Spacer(Modifier.width(8.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChecked,
            colors = SwitchDefaults.colors(
                checkedTrackColor = BrewChat,
                uncheckedTrackColor = BrewPanelHi,
                checkedThumbColor = BrewBg,
                uncheckedThumbColor = BrewMuted,
            ),
        )
    }
}

