package com.rokidlab.phone.store

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.rokidlab.phone.R
import com.rokidlab.phone.app.LabApplication
import com.rokidlab.phone.design.BrewAmber
import com.rokidlab.phone.design.BrewBg
import com.rokidlab.phone.design.BrewBorder
import com.rokidlab.phone.design.BrewChat
import com.rokidlab.phone.design.BrewMuted
import com.rokidlab.phone.design.BrewPanel
import com.rokidlab.phone.design.BrewPanelHi
import com.rokidlab.phone.design.BrewRed
import com.rokidlab.phone.design.BrewTextBright
import com.rokidlab.phone.glasses.CxrLHiRokidSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val TAG = "ChatScreen"
private const val CHAT_PREFS = "chat_prefs"
private const val KEY_DEEPSEEK = "deepseek_key"
/** 与 CxrLHiRokidSession.KEY_AI_THINKING 同键（shared 存储 chat_prefs），UI 直接持久化，无需等会话初始化 */
private const val KEY_AI_THINKING = "ai_thinking"
/** 是否已在乐奇页就通知权限做过首次提示（只主动弹一次，拒绝后不再打扰） */
private const val KEY_NOTIF_PERM_PROMPTED = "notif_perm_prompted_for_lab"

/** 聊天消息 */
internal data class ChatMsg(
    val id: Long,
    val isUser: Boolean,
    val content: String,
    val time: String,
    val isStatus: Boolean = false,
    /** 非空表示这是一条图片消息：content 作 caption 渲染，imageUrl 由 [ChatBubble] 异步加载并显示 */
    val imageUrl: String? = null,
)

/**
 * 乐奇聊天 — 文字对话界面
 * 上方对话列表 + 下方输入框/发送按钮；进入时先弹出 DeepSeek API Key 设置对话框。
 */
@Composable
internal fun ChatModule(app: LabApplication) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences(CHAT_PREFS, Context.MODE_PRIVATE) }
    val savedKey = remember { prefs.getString(KEY_DEEPSEEK, "").orEmpty() }

    // 聊天消息状态由全局 ChatStateHolder 持有，切到其他页面再切回不会清空
    val messages = ChatStateHolder.messages
    var input by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    // 「思考」开关状态：点亮=开启在线模型长思考（DeepSeek V4/V3.2 系生效）。
    // 默认关闭：实测开启思考时单轮 reasoning ~2.5 万字符会吃光输出预算 → 工具调用空轮卡死。
    var thinking by remember { mutableStateOf(prefs.getBoolean(KEY_AI_THINKING, false)) }
    // 「拍照问 AI」流程进行中
    var photoAsking by remember { mutableStateOf(false) }
    // 知识库管理弹窗
    var showKbDialog by remember { mutableStateOf(false) }
    // AI 设置弹窗（AI 服务地址/密钥/模型 + 按键答题开关）
    var showSettings by remember { mutableStateOf(false) }
    // 清空对话确认弹窗
    var showClearConfirm by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // 同步 AI 配置到会话（兼容旧版 deepseek_key 迁移：只回填在线槽位 Key，不影响本地模型模式）
    LaunchedEffect(Unit) {
        try {
            val session = app.cxrL
            val online = session.getOnlineAiConfig()
            if (online.apiKey.isBlank() && savedKey.isNotBlank()) {
                session.backfillOnlineApiKey(savedKey)
            }
        } catch (e: Exception) {
            Log.e(TAG, "cxrL not ready", e)
        }
    }

    // ── 首次进入乐奇：检测通知权限（状态栏音乐播放器 / 眼镜歌词 / 定时提醒依赖它）──
    // App 启动时已统一请求过一次（无上下文，易被拒）；这里是带用途说明的二次机会，只主动弹一次。
    var showNotifPermDialog by remember { mutableStateOf(false) }
    // 系统权限弹窗已拒过一次：确认按钮从「去开启」切换为「去系统设置开启」
    // （POST_NOTIFICATIONS 被拒过后再次请求，多数 ROM 直接回调拒绝不再弹系统框）
    var notifPermNeedSettings by remember { mutableStateOf(false) }
    fun hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    fun openAppNotificationSettings() {
        runCatching {
            ctx.startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure { Log.w(TAG, "open notification settings failed", it) }
    }

    val notifPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            showNotifPermDialog = false
            prefs.edit().putBoolean(KEY_NOTIF_PERM_PROMPTED, true).apply()
        } else {
            // 留在说明框，确认按钮切换为跳系统设置
            notifPermNeedSettings = true
        }
    }

    LaunchedEffect(Unit) {
        if (!hasNotificationPermission() && !prefs.getBoolean(KEY_NOTIF_PERM_PROMPTED, false)) {
            showNotifPermDialog = true
        }
    }

    fun appendMsg(isUser: Boolean, content: String, isStatus: Boolean = false) {
        ChatStateHolder.add(isUser, content, isStatus)
        scope.launch {
            try {
                listState.animateScrollToItem((ChatStateHolder.messages.size - 1).coerceAtLeast(0))
            } catch (_: Exception) {
            }
        }
    }

    fun toggleThinking() {
        thinking = !thinking
        prefs.edit().putBoolean(KEY_AI_THINKING, thinking).apply()
        Log.i(TAG, "AI thinking mode = $thinking")
    }

    fun send() {
        val text = input.trim()
        if (text.isEmpty() || sending) return
        appendMsg(true, text)
        input = ""
        sending = true
        val session = try {
            app.cxrL
        } catch (e: Exception) {
            Log.e(TAG, "cxrL not initialized", e)
            null
        }
        if (session == null) {
            sending = false
            appendMsg(false, ctx.getString(R.string.chat_send_failed))
            return
        }
        // sendAiTextMessage 内含多次 Thread.sleep + join（最长可阻塞 30s），
        // 必须在后台线程执行，否则阻塞主线程导致 ANR/闪退
        scope.launch(Dispatchers.IO) {
            try {
                session.sendAiTextMessage(
                    text,
                    // 保留回复文字在眼镜上的显示：不发 TTS_AudioFinished（避免官方会话
                    // 在长语音播完前被 AudioFinishedHandler→startNewTalk 重置清屏）
                    skipTtsAudioFinished = true,
                    onResult = { success, err ->
                        // onResult 由会话层 runOnUiThread 回调，已在主线程
                        sending = false
                        // 用户停止/被新请求抢占时 success=false 且 err=null：静默，不显示失败气泡
                        if (!success && !err.isNullOrBlank()) {
                            appendMsg(false, ctx.getString(R.string.chat_reply_failed) + ": $err")
                        }
                    },
                    onReply = { reply ->
                        // 流式 onDelta 已边生成边显示，此处用完整回复修正最后一条 AI 消息并落盘；
                        // 若流式未触发（如兜底路径）则 finalizeLastAi 内部会新增一条
                        scope.launch { ChatStateHolder.finalizeLastAi(reply) }
                    },
                    onDelta = { delta ->
                        // 流式增量：边生成边显示（切主线程，SnapshotStateList 写入需 Compose 快照线程）
                        scope.launch { ChatStateHolder.appendAiDelta(delta) }
                    },
                )
            } catch (e: Exception) {
                // 异常路径必须复位 sending，否则发送按钮永久卡死
                Log.e(TAG, "sendAiTextMessage crashed", e)
                scope.launch {
                    sending = false
                    appendMsg(false, ctx.getString(R.string.chat_reply_failed) + ": ${e.message}")
                }
            }
        }
    }

    /**
     * 拍照问 AI：眼镜拍照 → 本地 OCR 识别题目文字 → 知识库检索（RAG）→ AI 生成答案。
     * 复用会话层 startPhotoAsk 公共流程（手机按钮 / 镜腿按键共用入口）。
     */
    fun askPhotoAi() {
        if (photoAsking) return
        val session = try {
            app.cxrL
        } catch (e: Exception) {
            Log.e(TAG, "cxrL not initialized", e)
            null
        }
        if (session == null) {
            appendMsg(false, ctx.getString(R.string.chat_photo_failed), isStatus = true)
            return
        }
        photoAsking = true
        session.startPhotoAsk(
            onStage = { resId ->
                appendMsg(false, ctx.getString(resId), isStatus = true)
                // 失败 / 未识别出文字等终态时复位
                if (resId == R.string.chat_photo_failed || resId == R.string.chat_ocr_empty) {
                    photoAsking = false
                }
            },
            onText = { text -> appendMsg(true, text) },
            onReply = { reply ->
                // startPhotoAsk 内部在后台线程回调，需切回主线程更新 Compose 状态
                scope.launch {
                    photoAsking = false
                    appendMsg(false, reply)
                }
            },
        )
    }

    // 注册「拍照问 AI」默认 UI 回调：镜腿按键触发的 startPhotoAsk（无显式回调）
    // 也展示流程气泡、识别文字与最终答案到聊天界面。
    LaunchedEffect(Unit) {
        try {
            app.cxrL.setPhotoAskUiCallbacks(
                onStage = { resId ->
                    appendMsg(false, ctx.getString(resId), isStatus = true)
                    if (resId == R.string.chat_photo_failed || resId == R.string.chat_ocr_empty) {
                        photoAsking = false
                    }
                },
                onText = { text -> appendMsg(true, text) },
                onReply = { reply ->
                    scope.launch {
                        photoAsking = false
                        appendMsg(false, reply)
                    }
                },
            )
        } catch (e: Exception) {
            Log.e(TAG, "cxrL not ready", e)
        }
    }

    // 注册眼镜端语音对话（唤醒词 ASR）默认 UI 回调：把眼镜上的提问与 Lab 回复同步到聊天窗口。
    LaunchedEffect(Unit) {
        try {
            app.cxrL.setGlassesAiUiCallbacks(
                onText = { text -> appendMsg(true, text) },
                onReply = { reply -> appendMsg(false, reply) },
            )
        } catch (e: Exception) {
            Log.e(TAG, "cxrL not ready", e)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BrewBg)
            .imePadding()
            .navigationBarsPadding(),
    ) {
        ChatHeader(
            onPhotoAsk = { askPhotoAi() },
            onOpenKb = { showKbDialog = true },
            onOpenSettings = { showSettings = true },
            onClearChat = {
                if (ChatStateHolder.messages.isNotEmpty()) {
                    showClearConfirm = true
                }
            },
        )

        if (messages.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.chat_empty_hint),
                    color = BrewMuted,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(36.dp),
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(messages, key = { it.id }) { msg ->
                    ChatBubble(msg)
                }
            }
        }

        // 底部输入行
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp)
                // 顺序很重要：clip 必须在 background/border **之前**。
                // 旧顺序先画了方形背景再 clip，背景不被裁剪 → 圆角外露出灰色方角。
                .clip(RoundedCornerShape(26.dp))
                .background(BrewPanel)
                .border(1.dp, BrewBorder, RoundedCornerShape(26.dp))
                .padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                textStyle = TextStyle(color = BrewTextBright, fontSize = 15.sp),
                cursorBrush = SolidColor(BrewChat),
                singleLine = false,
                maxLines = 4,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
                decorationBox = { inner ->
                    Box {
                        if (input.isEmpty()) {
                            Text(
                                text = stringResource(R.string.chat_input_hint),
                                color = BrewMuted,
                                fontSize = 15.sp,
                            )
                        }
                        inner()
                    }
                },
            )
            Spacer(Modifier.width(8.dp))
            // 「思考」开关：纯图标切换，点亮=开启在线模型长思考（DeepSeek V4/V3.2，多步复杂任务建议开启，
            // 常规问答/生成代码默认关闭，避免推理吞预算导致工具调用空轮）；状态与 AI 设置页共用槽位
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(19.dp))
                    .background(if (thinking) BrewAmber.copy(alpha = 0.18f) else BrewPanelHi)
                    .border(
                        width = 1.dp,
                        color = if (thinking) BrewAmber.copy(alpha = 0.6f) else BrewBorder,
                        shape = RoundedCornerShape(19.dp),
                    )
                    .clickable { toggleThinking() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (thinking) Icons.Filled.Psychology else Icons.Outlined.Psychology,
                    contentDescription = stringResource(if (thinking) R.string.chat_think_on else R.string.chat_think_off),
                    tint = if (thinking) BrewAmber else BrewMuted,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = {
                    if (sending) {
                        // 停止当前回复：取消模型生成 + 停眼镜端播报（abortCurrentAi 内含
                        // tts_stop 下行，放后台线程避免阻塞主线程）
                        sending = false
                        val sess = try { app.cxrL } catch (e: Exception) { null }
                        if (sess != null) {
                            scope.launch(Dispatchers.IO) {
                                sess.abortCurrentAi()
                            }
                        }
                    } else {
                        send()
                    }
                },
                enabled = if (sending) true else input.isNotBlank(),
                modifier = Modifier.height(42.dp),
                shape = RoundedCornerShape(21.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (sending) BrewRed else BrewChat,
                    contentColor = BrewBg,
                    disabledContainerColor = BrewPanelHi,
                    disabledContentColor = BrewMuted,
                ),
            ) {
                if (sending) {
                    Icon(
                        imageVector = Icons.Filled.Clear,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.chat_stop),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                    )
                } else {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.chat_send),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }

    if (showSettings) {
        ChatSettingsDialog(
            app = app,
            onDismiss = { showSettings = false },
        )
    }

    if (showClearConfirm) {
        ConfirmClearChatDialog(
            onConfirm = {
                ChatStateHolder.clear()
                try {
                    app.cxrL.clearAgentHistory()
                } catch (_: Exception) {
                }
                showClearConfirm = false
            },
            onDismiss = { showClearConfirm = false },
        )
    }

    if (showKbDialog) {
        KbManageDialog(onDismiss = { showKbDialog = false })
    }

    // 首次进入乐奇的通知权限说明框
    if (showNotifPermDialog) {
        AlertDialog(
            onDismissRequest = {
                showNotifPermDialog = false
                prefs.edit().putBoolean(KEY_NOTIF_PERM_PROMPTED, true).apply()
            },
            title = { Text(stringResource(R.string.notif_perm_title)) },
            text = { Text(stringResource(R.string.notif_perm_message)) },
            confirmButton = {
                Button(onClick = {
                    if (notifPermNeedSettings) {
                        openAppNotificationSettings()
                        showNotifPermDialog = false
                        prefs.edit().putBoolean(KEY_NOTIF_PERM_PROMPTED, true).apply()
                    } else {
                        notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }) {
                    Text(
                        stringResource(
                            if (notifPermNeedSettings) R.string.notif_perm_open_settings
                            else R.string.notif_perm_enable
                        )
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showNotifPermDialog = false
                    prefs.edit().putBoolean(KEY_NOTIF_PERM_PROMPTED, true).apply()
                }) {
                    Text(stringResource(R.string.notif_perm_later))
                }
            },
        )
    }
}
