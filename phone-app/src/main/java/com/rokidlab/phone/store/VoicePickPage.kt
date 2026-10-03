package com.rokidlab.phone.store

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.rokidlab.phone.R
import com.rokidlab.phone.app.LabApplication
import com.rokidlab.phone.design.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 音色管理页：对话播报音色选择（ChatSettingsDialog → 会话记忆入口上方进入）。
 *
 * - 第一项「默认」= 眼镜端离线 TTS（现状链路，零改动）；
 * - 其余 19 项 = 云萌合成音色，选中后眼镜与手机双端同步发音（VoiceBroadcast）。
 * - 无输入框：试听固定文本（VoiceCatalog.PREVIEW_TEXT），选中云萌音色即可试听，
 *   眼镜在线时同步出声 —— 所见即所得。
 */
@Composable
internal fun VoicePickPage(
    app: LabApplication,
    onBack: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }

    // 与其他设置子页（AgentSectionPage 等）同款：独立全屏 Dialog 盖在设置弹窗上。
    // ⚠️ 不能用普通全屏 Column —— 会被 ChatSettingsDialog 的 Dialog 窗口挡住（点开没反应）。
    Dialog(
        onDismissRequest = onBack,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(BrewBg)
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            ModuleHeader(
                title = ctx.getString(R.string.voice_manage_title),
                subtitle = ctx.getString(R.string.voice_manage_page_desc),
                color = BrewPurple,
            )
            Spacer(Modifier.height(16.dp))

            // 音色列表：第一格 = 默认（眼镜端离线 TTS），其余 = 云萌音色，同级可随时切回
            val allVoices = listOf("" to ctx.getString(R.string.voice_pick_default)) + VoiceCatalog.VOICES
            allVoices.chunked(2).forEach { rowItems ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    rowItems.forEach { (id, label) ->
                        val on = VoicePrefs.voiceId == id
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (on) BrewChat.copy(alpha = 0.18f) else BrewPanelHi.copy(alpha = 0.4f))
                                .border(1.dp, if (on) BrewChat else BrewBorder, RoundedCornerShape(10.dp))
                                .clickable {
                                    VoicePrefs.updateVoice(id)
                                    status = ""
                                }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                        ) {
                            Text(
                                text = label,
                                color = if (on) BrewTextBright else BrewMuted,
                                fontSize = 13.sp,
                                maxLines = 1,
                                modifier = Modifier.weight(1f),
                            )
                            if (on) {
                                Text(text = "✓", color = BrewChat, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    if (rowItems.size == 1) Spacer(Modifier.weight(1f))
                }
                Spacer(Modifier.height(8.dp))
            }

            Spacer(Modifier.height(8.dp))

            // 语速 1-9（云萌合成参数；默认音色不使用）
            Text(
                text = ctx.getString(R.string.voice_pick_rate) + "：${VoicePrefs.rate}",
                color = BrewMuted,
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (1..9).forEach { r ->
                    val on = VoicePrefs.rate == r
                    Text(
                        text = "$r",
                        color = if (on) BrewTextBright else BrewMuted,
                        fontSize = 13.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (on) BrewChat.copy(alpha = 0.18f) else BrewPanelHi.copy(alpha = 0.4f))
                            .border(1.dp, if (on) BrewChat else BrewBorder, RoundedCornerShape(8.dp))
                            .clickable { VoicePrefs.updateRate(r) }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // 试听（固定文本，始终可用）：默认音色 = 眼镜端离线 TTS（手机本就无声，所见即所得）；
            // 云萌音色 = 仅手机出声（外放眼镜自然听得到，不做双声源避免重音）
            Text(
                text = if (busy) ctx.getString(R.string.voice_pick_auditioning)
                else ctx.getString(R.string.voice_pick_audition),
                color = if (!busy) BrewTextBright else BrewMuted,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (!busy) BrewChat else BrewMuted.copy(alpha = 0.2f))
                    .clickable(enabled = !busy) {
                        busy = true
                        status = ""
                        scope.launch {
                            val voiceId = VoicePrefs.voiceId
                            val result = withContext(Dispatchers.IO) {
                                if (voiceId.isBlank()) {
                                    // 默认音色：走眼镜端离线 TTS（tts_play），与对话播报链路同款
                                    if (glassTtsSpeak(app, VoiceCatalog.PREVIEW_TEXT)) Result.success(0L)
                                    else Result.failure(Exception(ctx.getString(R.string.voice_pick_glass_offline)))
                                } else {
                                    VoiceBroadcast.broadcast(
                                        VoiceCatalog.PREVIEW_TEXT, voiceId, rate = VoicePrefs.rate,
                                    )
                                }
                            }
                            busy = false
                            result.fold(
                                onSuccess = { status = "" },
                                onFailure = { status = it.message ?: "failed" },
                            )
                        }
                    }
                    .padding(vertical = 12.dp),
            )

            if (status.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(text = status, color = BrewMuted, fontSize = 12.sp, maxLines = 3)
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

/**
 * 眼镜端离线 TTS 试听（默认音色）：与对话播报链路同款 tts_play 指令。
 * 手机端本就无声（这是眼镜端能力），所见即所得。
 */
private fun glassTtsSpeak(app: LabApplication, text: String): Boolean {
    val session = try {
        app.cxrL
    } catch (_: Exception) {
        return false
    }
    val link = session.cxrLink ?: return false
    return runCatching {
        val caps = com.rokid.cxr.Caps()
        com.rokidlab.phone.glasses.AiChannel.encodeTtsPlay(text).forEach { caps.write(it) }
        link.sendCustomCmd(com.rokidlab.phone.glasses.AiChannel.TOPIC_TTS_PLAY, caps) == 0
    }.getOrDefault(false)
}
