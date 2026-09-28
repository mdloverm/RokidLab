package com.rokidlab.phone.store

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.rokidlab.phone.R
import com.rokidlab.phone.ai.llm.LlmRegistry
import com.rokidlab.phone.ai.llm.ProviderCatalog
import com.rokidlab.phone.ai.llm.ProviderConfig
import com.rokidlab.phone.ai.llm.ProviderPreset
import com.rokidlab.phone.ai.llm.ProviderStore
import com.rokidlab.phone.app.LabApplication
import com.rokidlab.phone.design.BrewBg
import com.rokidlab.phone.design.BrewBorder
import com.rokidlab.phone.design.BrewChat
import com.rokidlab.phone.design.BrewMuted
import com.rokidlab.phone.design.BrewPanel
import com.rokidlab.phone.design.BrewPanelHi
import com.rokidlab.phone.design.BrewTextBright
import com.rokidlab.phone.domain.AiConfig
import com.rokidlab.phone.glasses.AiChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ===== 模型供应商管理子页面 =====
//
// 「自定义服务」三个手填输入框（地址/密钥/模型）的替代形态：内置主流品牌供应商预设，
// 选品牌 → 填密钥 → 拉模型 → 「保存并使用」，一条路径走完。参考 rikkahub 的多供应商
// 设计（品牌列表 + 每家密钥/模型独立保存），配色与卡片按 Lab 的 Brew 视觉重画。
//
// 职责边界：本页只负责「哪家供应商的钥匙串」——选中「保存并使用」时才写入
// AiConfigService 的在线槽位（含下发眼镜端）；多家的配置由 ProviderStore 分别保管。

@Composable
internal fun ProviderManagePage(
    app: LabApplication,
    onBack: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val session = try {
        app.cxrL
    } catch (e: Exception) {
        null
    }

    // 当前在线槽位（= 正在生效的自定义服务配置；本地模型模式下也保留其最后保存值）
    var activeCfg by remember { mutableStateOf(session?.getOnlineAiConfig()) }
    // 已保存（有密钥）的供应商 id 集合；展开态的编辑字段
    var savedIds by remember { mutableStateOf(ProviderStore.configured(ctx).map { it.id }.toSet()) }
    var expandedId by remember { mutableStateOf<String?>(null) }
    var editKey by remember { mutableStateOf("") }
    var editBase by remember { mutableStateOf("") }
    var editModel by remember { mutableStateOf("") }
    var keyFocused by remember { mutableStateOf(false) }
    // 拉取到的模型列表（仅当前展开的供应商）
    var fetchedModels by remember { mutableStateOf(listOf<String>()) }
    var fetching by remember { mutableStateOf(false) }

    /** 当前正在使用的供应商 id（按在线槽位 baseUrl 匹配品牌） */
    fun activeId(): String? = activeCfg
        ?.let { ProviderCatalog.matchBaseUrl(it.baseUrl)?.id }

    fun openEdit(preset: ProviderPreset) {
        expandedId = preset.id
        fetchedModels = emptyList()
        keyFocused = false
        val saved = ProviderStore.get(ctx, preset.id)
        if (saved != null) {
            editKey = saved.apiKey
            editBase = saved.baseUrl.ifBlank { preset.baseUrl }
            editModel = saved.model
        } else {
            // 没在钥匙串里，但在线槽位正用着这家（旧版三个输入框时代配的）→ 就地收编
            val active = activeCfg?.takeIf { ProviderCatalog.matchBaseUrl(it.baseUrl)?.id == preset.id }
            editKey = active?.apiKey.orEmpty()
            editBase = active?.baseUrl?.ifBlank { preset.baseUrl } ?: preset.baseUrl
            editModel = active?.model.orEmpty()
            if (editKey.isNotBlank()) {
                ProviderStore.save(
                    ctx,
                    ProviderConfig(preset.id, editBase, editKey, editModel),
                )
                savedIds = savedIds + preset.id
            }
        }
    }

    fun collapse() {
        expandedId = null
        keyFocused = false
    }

    fun fetchModels(preset: ProviderPreset) {
        val key = editKey.trim()
        if (key.isEmpty()) {
            Toast.makeText(ctx, ctx.getString(R.string.chat_settings_model_fetch_need_key), Toast.LENGTH_SHORT).show()
            return
        }
        fetching = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { LlmRegistry.listModels(editBase.trim().ifBlank { preset.baseUrl }, key, editModel) }
            }
            fetching = false
            result.onSuccess { list ->
                fetchedModels = list
                if (list.isEmpty()) {
                    Toast.makeText(ctx, ctx.getString(R.string.chat_settings_model_fetch_empty), Toast.LENGTH_SHORT).show()
                }
            }.onFailure {
                Toast.makeText(ctx, ctx.getString(R.string.chat_settings_model_fetch_failed), Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun saveAndUse(preset: ProviderPreset) {
        val key = editKey.trim()
        if (key.isEmpty()) {
            Toast.makeText(ctx, ctx.getString(R.string.chat_settings_model_fetch_need_key), Toast.LENGTH_SHORT).show()
            return
        }
        val base = editBase.trim().ifBlank { preset.baseUrl }
        // ⚠️ 预置推荐清单已移除：模型只能来自手输或实拉 /models，没有兜底值可回退
        val model = editModel.trim()
        if (base.isBlank() || model.isBlank()) {
            // 自定义供应商必须给地址与模型；预设品牌地址有兜底，但模型必须手输或点选拉取结果
            Toast.makeText(ctx, ctx.getString(R.string.chat_settings_provider_need_base_model), Toast.LENGTH_SHORT).show()
            return
        }
        ProviderStore.save(ctx, ProviderConfig(preset.id, base, key, model))
        savedIds = savedIds + preset.id
        // 写入在线槽位并下发眼镜端（答题指令等其余字段保持原值）
        if (session != null) {
            session.setAiConfig(
                AiConfig(
                    baseUrl = base,
                    apiKey = key,
                    model = model,
                    mode = AiChannel.AI_MODE_CUSTOM,
                    quizInstruction = activeCfg?.quizInstruction.orEmpty(),
                ),
            )
            activeCfg = session.getOnlineAiConfig()
        }
        collapse()
        Toast.makeText(ctx, ctx.getString(R.string.chat_settings_provider_saved, preset.name), Toast.LENGTH_SHORT).show()
    }

    fun removeSaved(preset: ProviderPreset) {
        // 在用的那家不允许删钥匙串（否则面板上就没有它的模型了）；先切别家再删
        if (activeId() == preset.id) return
        ProviderStore.remove(ctx, preset.id)
        savedIds = savedIds - preset.id
        collapse()
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
                    text = stringResource(R.string.chat_settings_provider_title),
                    color = BrewTextBright,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onBack) {
                    Text(stringResource(R.string.done), color = BrewChat, fontWeight = FontWeight.Bold)
                }
            }
            Text(
                text = stringResource(R.string.chat_settings_provider_hint),
                color = BrewMuted,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(12.dp))

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            ) {
                ProviderCatalog.PRESETS.forEach { preset ->
                    val isActive = activeId() == preset.id
                    val isExpanded = expandedId == preset.id
                    val accent = providerAccentOf(preset.id)
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 10.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(BrewPanel)
                            .border(
                                1.dp,
                                if (isActive) BrewChat else BrewBorder,
                                RoundedCornerShape(12.dp),
                            )
                            .clickable { if (isExpanded) collapse() else openEdit(preset) }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // 品牌头像：内置矢量品牌图标（无图标回落首字母）
                            ProviderAvatar(
                                id = preset.id,
                                name = preset.name,
                                accent = accent,
                                circleSize = 36.dp,
                                iconSize = 20.dp,
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = preset.name,
                                    color = BrewTextBright,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    text = when {
                                        isActive -> stringResource(R.string.chat_settings_provider_using)
                                        savedIds.contains(preset.id) ->
                                            (ProviderStore.get(ctx, preset.id)?.model ?: "").ifBlank { preset.baseUrl }

                                        else -> preset.baseUrl.ifBlank {
                                            stringResource(R.string.chat_settings_provider_custom_hint)
                                        }
                                    },
                                    color = if (isActive) BrewChat else BrewMuted,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                )
                            }
                            if (isActive) {
                                Text(
                                    text = stringResource(R.string.chat_settings_provider_using_badge),
                                    color = BrewChat,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier
                                        .border(1.dp, BrewChat, RoundedCornerShape(8.dp))
                                        .padding(horizontal = 8.dp, vertical = 3.dp),
                                )
                            }
                        }

                        // 展开态：密钥 / 地址 / 模型 / 保存
                        if (isExpanded) {
                            Spacer(Modifier.height(10.dp))
                            OutlinedTextField(
                                value = editKey,
                                onValueChange = { editKey = it },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .onFocusChanged {
                                        keyFocused = it.isFocused
                                        // 填完 key 移开焦点即自动拉取模型清单（「刷新」按钮仍保留）：
                                        // 预置推荐清单已移除，拉取是模型点选列表的唯一来源
                                        if (!it.isFocused && editKey.isNotBlank() &&
                                            fetchedModels.isEmpty() && !fetching && preset.id != "custom"
                                        ) {
                                            fetchModels(preset)
                                        }
                                    },
                                label = { Text(stringResource(R.string.chat_settings_api_key), color = BrewMuted, fontSize = 12.sp) },
                                placeholder = { Text("sk-...", color = BrewMuted, fontSize = 13.sp) },
                                singleLine = true,
                                textStyle = TextStyle(color = BrewTextBright, fontSize = 13.sp),
                                visualTransformation = SkKeyVisualTransformation(masked = !keyFocused),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next, keyboardType = KeyboardType.Password),
                                colors = providerFieldColors(),
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = editBase,
                                onValueChange = { editBase = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text(stringResource(R.string.chat_settings_base_url), color = BrewMuted, fontSize = 12.sp) },
                                placeholder = { Text("https://...", color = BrewMuted, fontSize = 13.sp) },
                                singleLine = true,
                                textStyle = TextStyle(color = BrewTextBright, fontSize = 13.sp),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next, keyboardType = KeyboardType.Uri),
                                colors = providerFieldColors(),
                            )
                            Spacer(Modifier.height(8.dp))
                            // 模型：**可直接手输模型名**（自定义端点/新模型/别名都靠它），
                            // 下方再给预设推荐 + 在线拉取结果做点选（滚动容器内不用下拉菜单，避免被裁剪）
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = stringResource(R.string.chat_settings_provider_model_manual),
                                    color = BrewMuted,
                                    fontSize = 12.sp,
                                    modifier = Modifier.weight(1f),
                                )
                                if (preset.id != "custom") {
                                    TextButton(onClick = { fetchModels(preset) }, enabled = !fetching) {
                                        Text(
                                            text = stringResource(
                                                if (fetchedModels.isEmpty()) R.string.chat_settings_model_fetch
                                                else R.string.chat_settings_model_refresh,
                                            ),
                                            color = if (fetching) BrewMuted else BrewChat,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                        )
                                    }
                                }
                            }
                            OutlinedTextField(
                                value = editModel,
                                onValueChange = { editModel = it },
                                modifier = Modifier.fillMaxWidth(),
                                placeholder = {
                                    Text(
                                        text = stringResource(R.string.chat_settings_provider_model_manual_hint),
                                        color = BrewMuted,
                                        fontSize = 13.sp,
                                    )
                                },
                                singleLine = true,
                                textStyle = TextStyle(color = BrewTextBright, fontSize = 13.sp),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done, keyboardType = KeyboardType.Text),
                                colors = providerFieldColors(),
                            )
                            // 预置推荐清单已移除：只展示实拉 /models 的结果（最新优先，listModels 已按
                            // created 倒序），截前 8 条 —— "填入 key 后只显示最新的几个模型"
                            val modelChoices = fetchedModels
                                .take(PROVIDER_MAX_MODEL_ROWS)
                                .filter { it != editModel }
                            if (modelChoices.isNotEmpty()) {
                                Spacer(Modifier.height(6.dp))
                            }
                            modelChoices.forEach { m ->
                                val selected = m == editModel
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(if (selected) BrewChat.copy(alpha = 0.12f) else BrewPanelHi.copy(alpha = 0.4f))
                                        .clickable { editModel = m }
                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = m,
                                        color = if (selected) BrewChat else BrewTextBright,
                                        fontSize = 13.sp,
                                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                        maxLines = 1,
                                        modifier = Modifier.weight(1f),
                                    )
                                    if (selected) Text(text = "✓", color = BrewChat, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                }
                                Spacer(Modifier.height(4.dp))
                            }
                            if (fetchedModels.size > PROVIDER_MAX_MODEL_ROWS) {
                                Text(
                                    text = stringResource(R.string.chat_settings_provider_models_more, fetchedModels.size),
                                    color = BrewMuted,
                                    fontSize = 11.sp,
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (savedIds.contains(preset.id) && !isActive) {
                                    TextButton(onClick = { removeSaved(preset) }) {
                                        Text(
                                            stringResource(R.string.chat_settings_provider_remove),
                                            color = BrewMuted,
                                            fontSize = 12.sp,
                                        )
                                    }
                                }
                                Spacer(Modifier.weight(1f))
                                Button(
                                    onClick = { saveAndUse(preset) },
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = BrewChat, contentColor = BrewBg),
                                ) {
                                    Text(
                                        stringResource(R.string.chat_settings_provider_save_use),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 单页模型行上限：只展示最新几个（listModels 已按 created 倒序，这里直接截前 8 条） */
private const val PROVIDER_MAX_MODEL_ROWS = 8

/** 本页输入框统一配色（Lab 视觉口径） */
@Composable
private fun providerFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = BrewChat,
    unfocusedBorderColor = BrewBorder,
    focusedTextColor = BrewTextBright,
    unfocusedTextColor = BrewTextBright,
    cursorColor = BrewChat,
)
