package com.rokidlab.phone.store

import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.focus.onFocusChanged
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.rokidlab.phone.R
import com.rokidlab.phone.app.LabApplication
import com.rokidlab.phone.design.BrewBg
import com.rokidlab.phone.design.BrewBorder
import com.rokidlab.phone.design.BrewChat
import com.rokidlab.phone.design.BrewMuted
import com.rokidlab.phone.design.BrewPanel
import com.rokidlab.phone.design.BrewPanelHi
import com.rokidlab.phone.design.BrewTextBright
import com.rokidlab.phone.glasses.AiChannel
import com.rokidlab.phone.glasses.CxrLHiRokidSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "ChatScreen"

// ===== AI 服务设置对话框（地址 + 密钥 + 模型 + 按键答题开关）=====
@Composable
internal fun ChatSettingsDialog(
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
    // 在线槽位配置（自定义服务字段）：与本地模型开关相互独立，回填时读在线槽位
    val onlineCfg = session?.getOnlineAiConfig()
    val initialCfg = session?.getAiConfig()
    var baseUrl by remember { mutableStateOf(onlineCfg?.baseUrl.orEmpty()) }
    var apiKey by remember { mutableStateOf(onlineCfg?.apiKey.orEmpty()) }
    // API Key 焦点状态：未聚焦时掩码（sk- 后星号），点入输入框聚焦后显示明文
    var apiKeyFocused by remember { mutableStateOf(false) }
    var model by remember { mutableStateOf(onlineCfg?.model.orEmpty()) }
    var quizEnabled by remember { mutableStateOf(session?.isKeyQuizEnabled() ?: false) }
    // 图像理解（多模态）：开启后拍照直接把图发给模型，而不是先用本地 OCR 转文字。
    // 与连续对话同属"行为类开关"——切换即生效、不参与本页「保存」的批量提交。
    var imageInput by remember { mutableStateOf(app.chatImageInputEnabled) }
    // 「过程」区块默认展开：同样是行为类开关（切换即落盘、不参与「保存」批量提交）。
    // 放在「图像理解」下方 —— 两者都是"回答怎么呈现给你"的呈现类开关。
    var expandTrace by remember { mutableStateOf(app.chatExpandTraceEnabled) }
    // 连续对话（多轮免唤醒）：行为类开关 —— 切换即下发眼镜端并落盘，不参与本页「保存」批量提交
    var continueDialog by remember { mutableStateOf(session?.isContinueDialogEnabled() ?: true) }
    // 拍照答题指令：注入 AI 提示词控制回答方式（如「只显示答案」「给出解题步骤」）
    var quizInstruction by remember { mutableStateOf(initialCfg?.quizInstruction.orEmpty()) }
    // 对话模型来源三段选择：
    //   本地模型（useLocal=true）→ 仅走本机 Ollama，不动在线槽位；
    //   自定义服务（useLocal=false && customAiMode=true）→ 在线槽位 baseUrl/apiKey/model；
    //   乐奇官方（useLocal=false && customAiMode=false）→ 官方乐奇作答
    var useLocal by remember { mutableStateOf(session?.isLocalChatActive() ?: false) }
    var customAiMode by remember {
        mutableStateOf(onlineCfg?.mode == AiChannel.AI_MODE_CUSTOM)
    }
    // 供本地面板展示「当前本地对话模型」（本地页里点「设为对话模型」后刷新可见）
    var curLocalName by remember { mutableStateOf(session?.localChatModel().orEmpty()) }
    var saving by remember { mutableStateOf(false) }
    // AI 工具管理子页面
    var showToolsManage by remember { mutableStateOf(false) }
    // 外部 MCP 服务器管理子页面（连第三方 MCP，工具动态注入工具清单）
    var showMcpServers by remember { mutableStateOf(false) }
    // Agent 会话记忆子页面
    var showAgentSection by remember { mutableStateOf(false) }
    // AI 技能管理子页面（用户自定义技能）
    var showSkillsManage by remember { mutableStateOf(false) }
    // AIUI 应用管理子页面（对话生成/本地上传的智能体应用）
    var showAiuiManage by remember { mutableStateOf(false) }
    // 本地模型管理子页面（Termux + Ollama 离线推理）
    var showLocalModel by remember { mutableStateOf(false) }
    // 模型下拉列表：从 OpenAI 兼容接口 GET /models 拉取
    var models by remember { mutableStateOf(listOf<String>()) }
    var loadingModels by remember { mutableStateOf(false) }
    var modelMenuExpanded by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // ── 模型能力（llm 接缝）──────────────────────────────────────────
    // 「这个模型支不支持看图」改造前完全靠用户自己判断，猜错要等拍照失败一轮才知道
    // （服务端拒绝 → 回退 OCR，用户白等十几秒）。现在由 LlmRegistry 给结论：
    // 本机实测 > 本地模型自报 > 内置模型表 > 保守未知。图像理解开关直接读它。
    //
    // ⚠️ 配置按**输入框现值**构造（不是已保存的配置）：用户改完模型名/地址应当立刻看到
    //    新模型的能力，不必先点保存。本地来源用 Ollama 固定端点 + 已选本地模型名。
    val capabilityCfg = if (useLocal) {
        com.rokidlab.phone.domain.AiConfig(
            baseUrl = com.rokidlab.phone.ai.LocalOllamaManager.CHAT_BASE,
            model = curLocalName,
        )
    } else {
        com.rokidlab.phone.domain.AiConfig(baseUrl = baseUrl, apiKey = apiKey, model = model)
    }
    // capabilities() 内部不发网络请求（只读内存缓存 + 内置表），因此可以放在重组链路上直接算
    var caps by remember {
        mutableStateOf(com.rokidlab.phone.ai.llm.LlmRegistry.capabilities(capabilityCfg, ctx))
    }
    var detecting by remember { mutableStateOf(false) }
    LaunchedEffect(capabilityCfg) {
        caps = com.rokidlab.phone.ai.llm.LlmRegistry.capabilities(capabilityCfg, ctx)
    }

    /**
     * 实测一次图像能力 —— 把「猜」变成「事实」的入口。
     *
     * 必要性：内置模型表按命名匹配，遇到别名、聚合站、自建代理就可能不准；
     * 而这里发的是**真实请求**（本地模型还会真的加载一次权重），所以只在用户点击时跑，
     * 绝不放进重组或拍照链路里自动触发。
     */
    fun detectImageCapability() {
        if (detecting) return
        detecting = true
        scope.launch {
            val updated = withContext(Dispatchers.IO) {
                runCatching {
                    com.rokidlab.phone.ai.llm.LlmRegistry.probeImage(ctx, capabilityCfg)
                }.getOrNull()
            }
            detecting = false
            if (updated == null) {
                Toast.makeText(
                    ctx,
                    ctx.getString(R.string.chat_settings_image_detect_unknown),
                    Toast.LENGTH_SHORT,
                ).show()
                return@launch
            }
            caps = updated
            val toast = when (updated.supportsImage) {
                // 检测这个功能的目的就是"到底能不能用"：测通顺手打开；测不通顺手关掉，
                // 免得留下"开着但每次拍照都白跑一轮"的状态。
                true -> {
                    app.setChatImageInputEnabled(true)
                    imageInput = true
                    R.string.chat_settings_image_detect_ok
                }
                false -> {
                    app.setChatImageInputEnabled(false)
                    imageInput = false
                    R.string.chat_settings_image_detect_bad
                }
                // 探不出结论（超时/限流/鉴权）：**不动开关**，只如实说没结论
                null -> R.string.chat_settings_image_detect_unknown
            }
            Toast.makeText(ctx, ctx.getString(toast), Toast.LENGTH_SHORT).show()
        }
    }

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
                    // 构造口径收口在 llm 接缝（这里只是拿模型列表，用后台档位即可）
                    com.rokidlab.phone.ai.llm.LlmRegistry.listModels(url, key, model)
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

    // 从本地模型管理页返回后刷新本地面板：已选模型则自动启用本地模式。
    // 关键修复：只在「刚从本地面板返回」时触发（prevLocalPageOpen 为 true），
    // 不能在设置弹窗首次打开时就触发——否则残留的本地模型名（ai_local_model）
    // 会强制把 useLocal 置 true，覆盖用户已保存的在线/自定义服务选择，
    // 导致每次打开设置都默认回到「本地模型」（#bug 上报）。
    var prevLocalPageOpen by remember { mutableStateOf(false) }
    LaunchedEffect(showLocalModel) {
        val wasOpen = prevLocalPageOpen
        prevLocalPageOpen = showLocalModel
        if (wasOpen && !showLocalModel && session != null) {
            val name = session.localChatModel()
            if (name.isNotBlank()) {
                useLocal = true
                curLocalName = name
            }
        }
    }

    fun save() {
        if (session == null) {
            Toast.makeText(ctx, ctx.getString(R.string.chat_settings_save_failed), Toast.LENGTH_SHORT).show()
            return
        }
        if (useLocal) {
            // 本地模型模式：仅确认本地对话模型 + 保存答题指令，不触碰在线槽位（避免覆盖自定义服务配置）
            if (curLocalName.isBlank()) {
                Toast.makeText(ctx, ctx.getString(R.string.chat_settings_local_save_none), Toast.LENGTH_SHORT).show()
                showLocalModel = true
                return
            }
            session.setLocalChatModel(curLocalName)
            session.setQuizInstructionOnly(quizInstruction)
        } else {
            // 自定义服务 / 乐奇官方：写入在线槽位并关闭本地模式
            session.setAiConfig(
                com.rokidlab.phone.domain.AiConfig(
                    baseUrl = baseUrl.trim().ifBlank { "https://api.deepseek.com" },
                    apiKey = apiKey.trim(),
                    model = model.trim().ifBlank { "deepseek-chat" },
                    mode = if (customAiMode) AiChannel.AI_MODE_CUSTOM else AiChannel.AI_MODE_OFFICIAL,
                    quizInstruction = quizInstruction.trim(),
                )
            )
        }
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

            // ── 对话模型来源：乐奇官方 / 本地模型 / 自定义服务 ──
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SourceChip(
                    label = stringResource(R.string.chat_settings_source_official),
                    selected = !useLocal && !customAiMode,
                    modifier = Modifier.weight(1f),
                ) {
                    useLocal = false
                    customAiMode = false
                }
                SourceChip(
                    label = stringResource(R.string.chat_settings_source_local),
                    selected = useLocal,
                    modifier = Modifier.weight(1f),
                ) {
                    useLocal = true
                    // 尚未选过本地对话模型：直接进管理页引导拉取/「设为对话模型」
                    if (curLocalName.isBlank()) showLocalModel = true
                }
                SourceChip(
                    label = stringResource(R.string.chat_settings_source_custom),
                    selected = !useLocal && customAiMode,
                    modifier = Modifier.weight(1f),
                ) {
                    useLocal = false
                    customAiMode = true
                }
            }
            Spacer(Modifier.height(12.dp))

            // 各来源的说明面板
            when {
                useLocal -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (curLocalName.isNotBlank()) {
                                    stringResource(R.string.chat_settings_local_active_hint, curLocalName)
                                } else {
                                    stringResource(R.string.chat_settings_local_none_hint)
                                },
                                color = if (curLocalName.isNotBlank()) BrewChat else BrewMuted,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = { showLocalModel = true }) {
                            Text(
                                stringResource(R.string.chat_settings_local_manage),
                                color = BrewChat,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
                !useLocal && !customAiMode -> {
                    Text(
                        text = stringResource(R.string.chat_settings_official_hint),
                        color = BrewMuted,
                        fontSize = 13.sp,
                    )
                }
            }

            if (!useLocal && customAiMode) {
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

            // 模型能力摘要（llm 接缝）：把「这个模型支持什么」从用户脑子里的猜测变成事实。
            // 上下文窗口这个数字以前在界面上完全没有出口，而它正是会话压缩阈值该用的依据；
            // 这里先让它可见，压缩接缝落地后两者共用同一个数字。
            val capCtxText = caps.contextWindowText()
            val capContextLabel =
                if (capCtxText != null) stringResource(R.string.chat_settings_cap_context, capCtxText) else null
            val capToolsLabel =
                if (caps.supportsTools) stringResource(R.string.chat_settings_cap_tools) else null
            val capText = listOfNotNull(capContextLabel, capToolsLabel).joinToString(" · ")
            if (capText.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(text = capText, color = BrewMuted, fontSize = 11.sp)
            }

            } // if (!useLocal && customAiMode)：在线服务（地址/密钥/模型）字段结束

            Spacer(Modifier.height(16.dp))
            // 图像理解开关（多模态）：放在连续对话上方 —— 两者都是"输入怎么进模型"的形态开关。
            // 可用性由**模型能力**决定（见上方 capabilityCfg）：只有"已确认不支持"才置灰，
            // "未知"照旧允许（未知就尝试、失败回退 OCR，与改造前行为一致）。
            val imageSupported = caps.supportsImage
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.chat_settings_image_input),
                        color = if (imageSupported == false) BrewMuted else BrewTextBright,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        // 副标题直接说结论：不支持时说明为什么用不了，未知时告诉用户可以去检测
                        text = when (imageSupported) {
                            false -> stringResource(R.string.chat_settings_image_unsupported)
                            null -> stringResource(R.string.chat_settings_image_unknown)
                            else -> stringResource(R.string.chat_settings_image_input_hint)
                        },
                        color = BrewMuted,
                        fontSize = 11.sp,
                    )
                }
                // 只在"没有定论"或"已确认不支持"时给检测入口 —— 已确认支持时它没有可改变的信息
                if (imageSupported != true) {
                    TextButton(onClick = { detectImageCapability() }, enabled = !detecting) {
                        Text(
                            text = stringResource(
                                if (detecting) R.string.chat_settings_image_detecting
                                else R.string.chat_settings_image_detect
                            ),
                            color = if (detecting) BrewMuted else BrewChat,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                Switch(
                    // 已确认不支持时显示为"关"：开关表达的是**实际会不会把图发出去**，
                    // 而不是用户上一次的偏好值（偏好仍留在 imageInput 里，换回支持图像的模型即恢复）
                    checked = if (imageSupported == false) false else imageInput,
                    onCheckedChange = {
                        imageInput = it
                        app.setChatImageInputEnabled(it)
                    },
                    enabled = imageSupported != false,
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = BrewChat,
                        uncheckedTrackColor = BrewPanelHi,
                        checkedThumbColor = BrewBg,
                        uncheckedThumbColor = BrewMuted,
                    ),
                )
            }

            Spacer(Modifier.height(16.dp))
            // 「过程」区块默认展开：AI 回复上方那张卡片（思考 / 工具调用时间线 + 本轮 token 成本）。
            // 默认开的理由写在 LabApplication.chatExpandTraceEnabled 上；关掉它的典型场景是
            // "一轮里工具调用很多"——卡片会把对话列表撑得很长。
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.chat_settings_expand_trace),
                        color = BrewTextBright,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = stringResource(R.string.chat_settings_expand_trace_hint),
                        color = BrewMuted,
                        fontSize = 11.sp,
                    )
                }
                Switch(
                    checked = expandTrace,
                    onCheckedChange = {
                        expandTrace = it
                        app.setChatExpandTraceEnabled(it)
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
            // 连续对话开关（多轮免唤醒）：切换即时生效（下发眼镜端），不必再点「保存」
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.chat_settings_continue),
                        color = BrewTextBright,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = stringResource(R.string.chat_settings_continue_hint),
                        color = BrewMuted,
                        fontSize = 11.sp,
                    )
                }
                Switch(
                    checked = continueDialog,
                    onCheckedChange = {
                        continueDialog = it
                        session?.sendContinueDialogConfig(it)
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

            Spacer(Modifier.height(10.dp))
            // 外部 MCP 入口 → 子页面
            // 放在「AI 工具」正下方：MCP 工具最终也出现在工具清单里（多一个「外部 MCP」分类），
            // 但它多了一层「服务器」粒度（地址/鉴权/连接状态），所以入口分开、名字点明"外部"。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(BrewPanelHi.copy(alpha = 0.5f))
                    .border(1.dp, BrewBorder, RoundedCornerShape(12.dp))
                    .clickable { showMcpServers = true }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.chat_settings_mcp),
                        color = BrewTextBright,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = stringResource(R.string.chat_settings_mcp_hint),
                        color = BrewMuted,
                        fontSize = 11.sp,
                    )
                }
                Text(text = "›", color = BrewMuted, fontSize = 20.sp)
            }

            Spacer(Modifier.height(16.dp))
            // AI 技能管理入口 → 子页面
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(BrewPanelHi.copy(alpha = 0.5f))
                    .border(1.dp, BrewBorder, RoundedCornerShape(12.dp))
                    .clickable { showSkillsManage = true }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.chat_settings_skills),
                        color = BrewTextBright,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = stringResource(R.string.chat_settings_skills_hint),
                        color = BrewMuted,
                        fontSize = 11.sp,
                    )
                }
                Text(text = "›", color = BrewMuted, fontSize = 20.sp)
            }

            Spacer(Modifier.height(16.dp))
            // AIUI 应用管理入口 → 子页面
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(BrewPanelHi.copy(alpha = 0.5f))
                    .border(1.dp, BrewBorder, RoundedCornerShape(12.dp))
                    .clickable { showAiuiManage = true }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.chat_settings_aiui),
                        color = BrewTextBright,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = stringResource(R.string.chat_settings_aiui_hint),
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

    if (showMcpServers) {
        McpServersPage(app = app, onBack = { showMcpServers = false })
    }

    if (showSkillsManage) {
        SkillsManagePage(app = app, onBack = { showSkillsManage = false })
    }

    if (showAiuiManage) {
        AiuiManagePage(app = app, onBack = { showAiuiManage = false })
    }

    if (showLocalModel) {
        LocalModelPage(app = app, onBack = { showLocalModel = false })
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

/** AI 服务来源选择胶囊（乐奇官方 / 本地模型 / 自定义服务），选中高亮。
 *  modifier 由调用方在 Row 内传入（如 Modifier.weight(1f) 均分三列） */
@Composable
private fun SourceChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) BrewChat else BrewPanelHi.copy(alpha = 0.6f))
            .border(1.dp, if (selected) BrewChat else BrewBorder, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 9.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            text = label,
            color = if (selected) BrewBg else BrewTextBright,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
        )
    }
}
