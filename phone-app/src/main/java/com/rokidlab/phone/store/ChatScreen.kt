package com.rokidlab.phone.store

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.text.selection.SelectionContainer
import com.rokidlab.phone.R
import com.rokidlab.phone.ai.KbDocInfo
import com.rokidlab.phone.ai.KnowledgeBase
import com.rokidlab.phone.ai.LocalOcr
import com.rokidlab.phone.ai.ToolRegistry
import com.rokidlab.phone.app.LabApplication
import com.rokidlab.phone.glasses.CxrLHiRokidSession
import com.rokidlab.phone.design.BrewBg
import com.rokidlab.phone.design.BrewBorder
import com.rokidlab.phone.design.BrewChat
import com.rokidlab.phone.design.BrewMuted
import com.rokidlab.phone.design.BrewPanel
import com.rokidlab.phone.design.BrewPanelAlt
import com.rokidlab.phone.design.BrewPanelHi
import com.rokidlab.phone.design.BrewTextBright
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "ChatScreen"
private const val CHAT_PREFS = "chat_prefs"
private const val KEY_DEEPSEEK = "deepseek_key"

/** 聊天消息 */
internal data class ChatMsg(
    val id: Long,
    val isUser: Boolean,
    val content: String,
    val time: String,
    val isStatus: Boolean = false,
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

    // 同步 AI 配置到会话（兼容旧版 deepseek_key 迁移）
    LaunchedEffect(Unit) {
        try {
            val session = app.cxrL
            val cfg = session.getAiConfig()
            if (cfg.apiKey.isBlank() && savedKey.isNotBlank()) {
                session.setAiConfig(CxrLHiRokidSession.AiConfig(apiKey = savedKey))
            }
        } catch (e: Exception) {
            Log.e(TAG, "cxrL not ready", e)
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
            session.sendAiTextMessage(
                text,
                onResult = { success, err ->
                    // onResult 由会话层 runOnUiThread 回调，已在主线程
                    sending = false
                    if (!success) {
                        appendMsg(false, ctx.getString(R.string.chat_reply_failed) + if (err.isNullOrBlank()) "" else ": $err")
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
                .border(1.dp, BrewBorder, RoundedCornerShape(26.dp))
                .background(BrewPanel)
                .clip(RoundedCornerShape(26.dp))
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
            Button(
                onClick = { send() },
                enabled = !sending && input.isNotBlank(),
                modifier = Modifier.height(42.dp),
                shape = RoundedCornerShape(21.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = BrewChat,
                    contentColor = BrewBg,
                    disabledContainerColor = BrewPanelHi,
                    disabledContentColor = BrewMuted,
                ),
            ) {
                Icon(
                    imageVector = Icons.Filled.Send,
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
}

// ===== 顶部标题栏 =====
@Composable
private fun ChatHeader(
    onPhotoAsk: () -> Unit,
    onOpenKb: () -> Unit,
    onOpenSettings: () -> Unit,
    onClearChat: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(BrewPanel)
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.chat_title),
                color = BrewChat,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = stringResource(R.string.chat_subtitle),
                color = BrewMuted,
                fontSize = 11.sp,
            )
        }
        IconButton(onClick = onPhotoAsk) {
            Icon(
                imageVector = Icons.Filled.PhotoCamera,
                contentDescription = stringResource(R.string.chat_photo_ask),
                tint = BrewChat,
            )
        }
        IconButton(onClick = onOpenKb) {
            Icon(
                imageVector = Icons.Filled.Folder,
                contentDescription = stringResource(R.string.chat_kb),
                tint = BrewChat,
            )
        }
        IconButton(onClick = onClearChat) {
            Icon(
                imageVector = Icons.Filled.DeleteSweep,
                contentDescription = stringResource(R.string.chat_clear),
                tint = BrewChat,
            )
        }
        IconButton(onClick = onOpenSettings) {
            Icon(
                imageVector = Icons.Filled.Settings,
                contentDescription = stringResource(R.string.chat_settings),
                tint = BrewChat,
            )
        }
    }
}

// ===== 对话气泡 =====
@Composable
private fun ChatBubble(msg: ChatMsg) {
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
            // AI 消息用 SelectionContainer 包裹，支持长按选字 / 复制
            // 用户消息不需要选中
            if (isUser) {
                Text(
                    text = msg.content,
                    color = BrewBg,
                    fontSize = 15.sp,
                    lineHeight = 22.sp,
                )
            } else {
                SelectionContainer {
                    Text(
                        text = msg.content,
                        color = BrewTextBright,
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

// ===== 清空对话确认弹窗 =====
@Composable
private fun ConfirmClearChatDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_clear), color = BrewTextBright, fontWeight = FontWeight.Bold) },
        text = { Text(stringResource(R.string.chat_clear_confirm), color = BrewMuted, fontSize = 14.sp) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.chat_clear), color = BrewChat, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.chat_key_dialog_cancel), color = BrewMuted)
            }
        },
        containerColor = BrewPanel,
    )
}

// ===== AI 服务设置对话框（地址 + 密钥 + 模型 + 按键答题开关）=====
@Composable
private fun ChatSettingsDialog(
    app: LabApplication,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    val session = try {
        app.cxrL
    } catch (e: Exception) {
        Log.e(TAG, "cxrL not ready", e)
        null
    }
    val initialCfg = session?.getAiConfig()
    var baseUrl by remember { mutableStateOf(initialCfg?.baseUrl.orEmpty()) }
    var apiKey by remember { mutableStateOf(initialCfg?.apiKey.orEmpty()) }
    // API Key 焦点状态：未聚焦时掩码（sk- 后星号），点入输入框聚焦后显示明文
    var apiKeyFocused by remember { mutableStateOf(false) }
    var model by remember { mutableStateOf(initialCfg?.model.orEmpty()) }
    var quizEnabled by remember { mutableStateOf(session?.isKeyQuizEnabled() ?: false) }
    // 拍照答题指令：注入 AI 提示词控制回答方式（如「只显示答案」「给出解题步骤」）
    var quizInstruction by remember { mutableStateOf(initialCfg?.quizInstruction.orEmpty()) }
    // 对话模型模式：custom（Lab 自定义模型，拦截官方回复）/ official（官方乐奇）
    var customAiMode by remember {
        mutableStateOf(initialCfg?.mode == CxrLHiRokidSession.AI_MODE_CUSTOM)
    }
    var saving by remember { mutableStateOf(false) }
    // AI 工具管理子页面
    var showToolsManage by remember { mutableStateOf(false) }
    // Agent 会话记忆子页面
    var showAgentSection by remember { mutableStateOf(false) }
    // 模型下拉列表：从 OpenAI 兼容接口 GET /models 拉取
    var models by remember { mutableStateOf(listOf<String>()) }
    var loadingModels by remember { mutableStateOf(false) }
    var modelMenuExpanded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    /** 拉取当前服务商支持的模型列表（失败仍可手动输入模型名） */
    fun loadModels() {
        val url = baseUrl.trim().ifBlank { "https://api.deepseek.com" }
        val key = apiKey.trim()
        if (key.isEmpty()) {
            Toast.makeText(ctx, ctx.getString(R.string.chat_settings_model_fetch_need_key), Toast.LENGTH_SHORT).show()
            return
        }
        loadingModels = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    com.rokidlab.phone.ai.OpenAiService(key, model.ifBlank { "deepseek-chat" }, url).listModels()
                }
            }
            loadingModels = false
            result.onSuccess { list ->
                models = list
                if (list.isEmpty()) {
                    Toast.makeText(ctx, ctx.getString(R.string.chat_settings_model_fetch_empty), Toast.LENGTH_SHORT).show()
                }
            }.onFailure { e ->
                Log.e(TAG, "listModels failed", e)
                Toast.makeText(ctx, ctx.getString(R.string.chat_settings_model_fetch_failed), Toast.LENGTH_SHORT).show()
            }
        }
    }

    // 地址/密钥变化时，清空旧模型列表（下次展开自动重新拉取，避免显示别家服务商的模型）
    LaunchedEffect(baseUrl, apiKey) {
        models = emptyList()
        loadingModels = false
    }

    // 展开下拉且尚未加载时，自动拉取模型列表（无需手动点刷新）
    LaunchedEffect(modelMenuExpanded, models.isEmpty(), baseUrl, apiKey) {
        if (modelMenuExpanded && models.isEmpty() && !loadingModels && apiKey.isNotBlank()) {
            loadModels()
        }
    }

    fun save() {
        if (session == null) {
            Toast.makeText(ctx, ctx.getString(R.string.chat_settings_save_failed), Toast.LENGTH_SHORT).show()
            return
        }
        saving = true
        session.setAiConfig(
            CxrLHiRokidSession.AiConfig(
                baseUrl = baseUrl.trim().ifBlank { "https://api.deepseek.com" },
                apiKey = apiKey.trim(),
                model = model.trim().ifBlank { "deepseek-chat" },
                mode = if (customAiMode) CxrLHiRokidSession.AI_MODE_CUSTOM else CxrLHiRokidSession.AI_MODE_OFFICIAL,
                quizInstruction = quizInstruction.trim(),
            )
        )
        // 下发「按键答题」开关到眼镜端（异步，失败不阻塞保存）
        session.sendKeyQuizConfig(quizEnabled)
        saving = false
        Toast.makeText(ctx, ctx.getString(R.string.chat_settings_saved), Toast.LENGTH_SHORT).show()
        onDismiss()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 28.dp)
                .verticalScroll(rememberScrollState())
                .clip(RoundedCornerShape(20.dp))
                .background(BrewPanel)
                .border(1.dp, BrewBorder, RoundedCornerShape(20.dp))
                .padding(20.dp),
        ) {
            Text(
                text = stringResource(R.string.chat_settings_title),
                color = BrewTextBright,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.chat_settings_hint),
                color = BrewMuted,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(14.dp))

            // 服务地址
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.chat_settings_base_url), color = BrewMuted, fontSize = 13.sp) },
                placeholder = { Text("https://api.deepseek.com", color = BrewMuted, fontSize = 14.sp) },
                singleLine = true,
                textStyle = TextStyle(color = BrewTextBright, fontSize = 14.sp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next, keyboardType = KeyboardType.Uri),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = BrewChat,
                    unfocusedBorderColor = BrewBorder,
                    focusedTextColor = BrewTextBright,
                    unfocusedTextColor = BrewTextBright,
                    cursorColor = BrewChat,
                ),
            )
            Spacer(Modifier.height(10.dp))
            // API 密钥
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { apiKeyFocused = it.isFocused },
                label = { Text(stringResource(R.string.chat_settings_api_key), color = BrewMuted, fontSize = 13.sp) },
                placeholder = { Text("sk-...", color = BrewMuted, fontSize = 14.sp) },
                singleLine = true,
                textStyle = TextStyle(color = BrewTextBright, fontSize = 14.sp),
                // 未聚焦时 sk- 前缀保留、其余星号掩码；聚焦时显示明文
                visualTransformation = SkKeyVisualTransformation(masked = !apiKeyFocused),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next, keyboardType = KeyboardType.Password),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = BrewChat,
                    unfocusedBorderColor = BrewBorder,
                    focusedTextColor = BrewTextBright,
                    unfocusedTextColor = BrewTextBright,
                    cursorColor = BrewChat,
                ),
            )
            Spacer(Modifier.height(10.dp))
            // 模型名（下拉选择 + 支持手动输入）
            ExposedDropdownMenuBox(
                expanded = modelMenuExpanded,
                onExpandedChange = { modelMenuExpanded = it },
            ) {
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(),
                    label = { Text(stringResource(R.string.chat_settings_model), color = BrewMuted, fontSize = 13.sp) },
                    placeholder = { Text("deepseek-chat", color = BrewMuted, fontSize = 14.sp) },
                    singleLine = true,
                    textStyle = TextStyle(color = BrewTextBright, fontSize = 14.sp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, keyboardType = KeyboardType.Text),
                    keyboardActions = KeyboardActions(onDone = { save() }),
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelMenuExpanded) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = BrewChat,
                        unfocusedBorderColor = BrewBorder,
                        focusedTextColor = BrewTextBright,
                        unfocusedTextColor = BrewTextBright,
                        cursorColor = BrewChat,
                    ),
                )
                ExposedDropdownMenu(
                    expanded = modelMenuExpanded,
                    onDismissRequest = { modelMenuExpanded = false },
                ) {
                    if (loadingModels) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.chat_settings_model_fetching), color = BrewMuted, fontSize = 13.sp) },
                            onClick = {},
                            enabled = false,
                        )
                    }
                    // 获取 / 刷新模型列表入口（列表为空时仅显示此项）
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = stringResource(if (models.isEmpty()) R.string.chat_settings_model_fetch else R.string.chat_settings_model_refresh),
                                color = BrewChat,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        },
                        onClick = { loadModels() },
                    )
                    if (!loadingModels) {
                        models.forEach { m ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = m,
                                        color = if (m == model) BrewChat else BrewTextBright,
                                        fontSize = 14.sp,
                                        maxLines = 1,
                                        fontWeight = if (m == model) FontWeight.Bold else FontWeight.Normal,
                                    )
                                },
                                onClick = {
                                    model = m
                                    modelMenuExpanded = false
                                },
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            // 对话模型模式开关：自定义（Lab 拦截回复）/ 官方（乐奇原生）
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.chat_settings_ai_mode),
                        color = BrewTextBright,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = stringResource(R.string.chat_settings_ai_mode_hint),
                        color = BrewMuted,
                        fontSize = 11.sp,
                    )
                }
                Switch(
                    checked = customAiMode,
                    onCheckedChange = { customAiMode = it },
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = BrewChat,
                        uncheckedTrackColor = BrewPanelHi,
                        checkedThumbColor = BrewBg,
                        uncheckedThumbColor = BrewMuted,
                    ),
                )
            }

            Spacer(Modifier.height(16.dp))
            // 按键答题开关
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.chat_settings_quiz),
                        color = BrewTextBright,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = stringResource(R.string.chat_settings_quiz_hint),
                        color = BrewMuted,
                        fontSize = 11.sp,
                    )
                }
                Switch(
                    checked = quizEnabled,
                    onCheckedChange = { quizEnabled = it },
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = BrewChat,
                        uncheckedTrackColor = BrewPanelHi,
                        checkedThumbColor = BrewBg,
                        uncheckedThumbColor = BrewMuted,
                    ),
                )
            }

            // 拍照答题指令：注入 AI 提示词控制回答方式（如「只显示答案」「给出解题步骤」）
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = quizInstruction,
                onValueChange = { quizInstruction = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.chat_settings_quiz_instruction), color = BrewMuted, fontSize = 13.sp) },
                placeholder = { Text(stringResource(R.string.chat_settings_quiz_instruction_placeholder), color = BrewMuted, fontSize = 14.sp) },
                supportingText = { Text(stringResource(R.string.chat_settings_quiz_instruction_hint), color = BrewMuted, fontSize = 11.sp) },
                singleLine = false,
                minLines = 1,
                maxLines = 3,
                textStyle = TextStyle(color = BrewTextBright, fontSize = 14.sp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = BrewChat,
                    unfocusedBorderColor = BrewBorder,
                    focusedTextColor = BrewTextBright,
                    unfocusedTextColor = BrewTextBright,
                    cursorColor = BrewChat,
                ),
            )

            Spacer(Modifier.height(16.dp))
            // Agent 会话记忆入口 → 子页面
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(BrewPanelHi.copy(alpha = 0.5f))
                    .border(1.dp, BrewBorder, RoundedCornerShape(12.dp))
                    .clickable { showAgentSection = true }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.agent_section_title),
                        color = BrewTextBright,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = stringResource(R.string.agent_section_subtitle),
                        color = BrewMuted,
                        fontSize = 11.sp,
                    )
                }
                Text(text = "›", color = BrewMuted, fontSize = 20.sp)
            }

            Spacer(Modifier.height(16.dp))
            // AI 工具管理入口 → 子页面
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(BrewPanelHi.copy(alpha = 0.5f))
                    .border(1.dp, BrewBorder, RoundedCornerShape(12.dp))
                    .clickable { showToolsManage = true }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.chat_settings_tools),
                        color = BrewTextBright,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = stringResource(R.string.chat_settings_tools_hint),
                        color = BrewMuted,
                        fontSize = 11.sp,
                    )
                }
                Text(text = "›", color = BrewMuted, fontSize = 20.sp)
            }

            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.chat_key_dialog_cancel), color = BrewMuted)
                }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = { save() },
                    enabled = !saving,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = BrewChat, contentColor = BrewBg),
                ) {
                    Text(
                        text = stringResource(R.string.chat_settings_save),
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }

    if (showAgentSection) {
        AgentSectionPage(app = app, onBack = { showAgentSection = false })
    }

    if (showToolsManage) {
        ToolsManagePage(app = app, onBack = { showToolsManage = false })
    }
}

// ===== 知识库管理对话框 =====
@Composable
private fun KbManageDialog(
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var docs by remember { mutableStateOf(KnowledgeBase.listDocs(ctx)) }
    var importing by remember { mutableStateOf(false) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            importing = true
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    val displayName = runCatching {
                        ctx.contentResolver.query(
                            uri,
                            arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                            null, null, null,
                        )?.use { c ->
                            if (c.moveToFirst()) c.getString(0) else null
                        }
                    }.getOrNull()
                    KnowledgeBase.importUri(ctx, uri, displayName)
                }
                importing = false
                if (result == null) {
                    Toast.makeText(ctx, ctx.getString(R.string.chat_kb_import_failed), Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(ctx, ctx.getString(R.string.chat_kb_imported, result.name), Toast.LENGTH_SHORT).show()
                    docs = KnowledgeBase.listDocs(ctx)
                }
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 28.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(BrewPanel)
                .border(1.dp, BrewBorder, RoundedCornerShape(20.dp))
                .padding(20.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.chat_kb_title),
                        color = BrewTextBright,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = stringResource(R.string.chat_kb_subtitle),
                        color = BrewMuted,
                        fontSize = 12.sp,
                    )
                }
                TextButton(
                    onClick = {
                        importLauncher.launch(arrayOf("text/plain"))
                    },
                    enabled = !importing,
                ) {
                    Text(
                        text = if (importing) stringResource(R.string.installing) else stringResource(R.string.chat_kb_import),
                        color = BrewChat,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))

            if (docs.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.chat_kb_empty),
                        color = BrewMuted,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                Column(modifier = Modifier.heightIn(max = 320.dp)) {
                    docs.forEach { doc ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = doc.name,
                                    color = BrewTextBright,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                )
                                Text(
                                    text = formatDocSize(doc.size),
                                    color = BrewMuted,
                                    fontSize = 11.sp,
                                )
                            }
                            IconButton(
                                onClick = {
                                    scope.launch {
                                        withContext(Dispatchers.IO) { KnowledgeBase.deleteDoc(ctx, doc.id) }
                                        docs = KnowledgeBase.listDocs(ctx)
                                    }
                                },
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Delete,
                                    contentDescription = stringResource(R.string.chat_kb_delete),
                                    tint = BrewMuted,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.chat_key_dialog_cancel), color = BrewMuted)
                }
            }
        }
    }
}

/**
 * API Key 掩码变换：未聚焦时保留 sk- 前缀，其余字符显示为星号；聚焦时显示明文。
 * 实际编辑值不变，仅影响显示，光标位置一一对应。
 */
private class SkKeyVisualTransformation(private val masked: Boolean) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        if (!masked) return TransformedText(text, OffsetMapping.Identity)
        val raw = text.text
        if (raw.length <= 3) return TransformedText(text, OffsetMapping.Identity)
        // 保留 sk- 前缀（若非 sk- 开头则整体掩码），其余替换为 *
        val head = if (raw.startsWith("sk-", ignoreCase = true)) "sk-" else ""
        val visible = head
        val stars = "*".repeat(raw.length - visible.length)
        return TransformedText(
            AnnotatedString(visible + stars),
            OffsetMapping.Identity,
        )
    }
}

// ===== Agent 会话记忆子页面 =====
@Composable
private fun AgentSectionPage(
    app: LabApplication,
    onBack: () -> Unit,
) {
    val ctx = LocalContext.current
    var memoryEnabled by remember {
        mutableStateOf(com.rokidlab.phone.ai.AgentSessionManager.isEnabled(ctx))
    }
    var longTermEnabled by remember {
        mutableStateOf(com.rokidlab.phone.ai.LongTermMemoryManager.isEnabled(ctx))
    }
    var longTermCount by remember {
        mutableIntStateOf(com.rokidlab.phone.ai.LongTermMemoryManager.count(ctx))
    }
    var showClearConfirm by remember { mutableStateOf(false) }
    var showLongClearConfirm by remember { mutableStateOf(false) }
    Dialog(
        onDismissRequest = onBack,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(BrewBg)
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.agent_section_title),
                    color = BrewTextBright,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onBack) {
                    Text(stringResource(R.string.back_btn), color = BrewChat, fontWeight = FontWeight.Bold)
                }
            }
            Text(
                text = stringResource(R.string.agent_section_subtitle),
                color = BrewMuted,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(16.dp))

            // 会话记忆开关
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(BrewPanel)
                    .border(1.dp, BrewBorder, RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.agent_memory_switch),
                        color = BrewTextBright,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.agent_memory_switch_hint),
                        color = BrewMuted,
                        fontSize = 11.sp,
                    )
                }
                Switch(
                    checked = memoryEnabled,
                    onCheckedChange = {
                        memoryEnabled = it
                        com.rokidlab.phone.ai.AgentSessionManager.setEnabled(ctx, it)
                    },
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = BrewChat,
                        uncheckedTrackColor = BrewPanelHi,
                        checkedThumbColor = BrewBg,
                        uncheckedThumbColor = BrewMuted,
                    ),
                )
            }

            Spacer(Modifier.height(16.dp))
            // 会话信息
            Text(
                text = stringResource(R.string.agent_memory_info),
                color = BrewMuted,
                fontSize = 12.sp,
            )

            Spacer(Modifier.height(20.dp))
            // 清空会话记忆按钮
            Button(
                onClick = { showClearConfirm = true },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = BrewPanel,
                    contentColor = BrewChat,
                ),
            ) {
                Icon(
                    imageVector = Icons.Filled.DeleteSweep,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.agent_memory_clear), fontWeight = FontWeight.Medium)
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = BrewBorder)
            Spacer(Modifier.height(16.dp))

            // 长期记忆开关（跨会话记住用户偏好）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(BrewPanel)
                    .border(1.dp, BrewBorder, RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.agent_longterm_switch),
                        color = BrewTextBright,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.agent_longterm_switch_hint),
                        color = BrewMuted,
                        fontSize = 11.sp,
                    )
                }
                Switch(
                    checked = longTermEnabled,
                    onCheckedChange = {
                        longTermEnabled = it
                        com.rokidlab.phone.ai.LongTermMemoryManager.setEnabled(ctx, it)
                    },
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = BrewChat,
                        uncheckedTrackColor = BrewPanelHi,
                        checkedThumbColor = BrewBg,
                        uncheckedThumbColor = BrewMuted,
                    ),
                )
            }

            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.agent_longterm_info, longTermCount),
                color = BrewMuted,
                fontSize = 12.sp,
            )

            Spacer(Modifier.height(20.dp))
            // 清空长期记忆按钮
            Button(
                onClick = { showLongClearConfirm = true },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = BrewPanel,
                    contentColor = BrewChat,
                ),
            ) {
                Icon(
                    imageVector = Icons.Filled.DeleteSweep,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.agent_longterm_clear), fontWeight = FontWeight.Medium)
            }
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text(stringResource(R.string.agent_memory_clear), color = BrewTextBright, fontWeight = FontWeight.Bold) },
            text = { Text(stringResource(R.string.agent_memory_clear_confirm), color = BrewMuted, fontSize = 14.sp) },
            confirmButton = {
                TextButton(onClick = {
                    com.rokidlab.phone.ai.AgentSessionManager.clear()
                    showClearConfirm = false
                    Toast.makeText(ctx, ctx.getString(R.string.agent_memory_cleared), Toast.LENGTH_SHORT).show()
                }) {
                    Text(stringResource(R.string.confirm), color = BrewChat, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text(stringResource(R.string.chat_key_dialog_cancel), color = BrewMuted)
                }
            },
            containerColor = BrewPanel,
        )
    }

    if (showLongClearConfirm) {
        AlertDialog(
            onDismissRequest = { showLongClearConfirm = false },
            title = { Text(stringResource(R.string.agent_longterm_clear), color = BrewTextBright, fontWeight = FontWeight.Bold) },
            text = { Text(stringResource(R.string.agent_longterm_clear_confirm), color = BrewMuted, fontSize = 14.sp) },
            confirmButton = {
                TextButton(onClick = {
                    com.rokidlab.phone.ai.LongTermMemoryManager.clear(ctx)
                    longTermCount = 0
                    showLongClearConfirm = false
                    Toast.makeText(ctx, ctx.getString(R.string.agent_longterm_cleared), Toast.LENGTH_SHORT).show()
                }) {
                    Text(stringResource(R.string.confirm), color = BrewChat, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showLongClearConfirm = false }) {
                    Text(stringResource(R.string.chat_key_dialog_cancel), color = BrewMuted)
                }
            },
            containerColor = BrewPanel,
        )
    }
}

// ===== AI 工具管理子页面 =====
@Composable
private fun ToolsManagePage(
    app: LabApplication,
    onBack: () -> Unit,
) {
    val ctx = LocalContext.current
    var toolStates by remember {
        mutableStateOf(ToolRegistry.toolList.associate { it.name to ToolRegistry.isEnabled(ctx, it.name) })
    }
    Dialog(
        onDismissRequest = onBack,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(BrewBg)
                .padding(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.chat_settings_tools),
                    color = BrewTextBright,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = {
                        toolStates.forEach { (name, enabled) -> ToolRegistry.setEnabled(ctx, name, enabled) }
                        onBack()
                    },
                ) {
                    Text(stringResource(R.string.done), color = BrewChat, fontWeight = FontWeight.Bold)
                }
            }
            Text(
                text = stringResource(R.string.chat_settings_tools_hint),
                color = BrewMuted,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(12.dp))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            ) {
                ToolRegistry.toolList.forEach { tool ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(BrewPanel)
                            .border(1.dp, BrewBorder, RoundedCornerShape(12.dp))
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(tool.displayNameRes),
                                color = BrewTextBright,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = stringResource(tool.descriptionRes),
                                color = BrewMuted,
                                fontSize = 11.sp,
                            )
                        }
                        Switch(
                            checked = toolStates[tool.name] ?: true,
                            onCheckedChange = { toolStates = toolStates + (tool.name to it) },
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = BrewChat,
                                uncheckedTrackColor = BrewPanelHi,
                                checkedThumbColor = BrewBg,
                                uncheckedThumbColor = BrewMuted,
                            ),
                        )
                    }
                }
            }
        }
    }
}

/** 文档大小格式化显示 */
private fun formatDocSize(bytes: Int): String = when {
    bytes >= 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f MB", bytes / 1024f / 1024f)
    bytes >= 1024 -> String.format(Locale.getDefault(), "%.1f KB", bytes / 1024f)
    else -> "$bytes B"
}
