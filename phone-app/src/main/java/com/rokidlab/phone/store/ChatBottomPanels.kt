package com.rokidlab.phone.store

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.PsychologyAlt
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rokidlab.phone.R
import com.rokidlab.phone.ai.llm.LlmRegistry
import com.rokidlab.phone.ai.llm.ProviderCatalog
import com.rokidlab.phone.ai.llm.ProviderConfig
import com.rokidlab.phone.ai.llm.ProviderPreset
import com.rokidlab.phone.ai.llm.ProviderStore
import com.rokidlab.phone.app.LabApplication
import com.rokidlab.phone.design.BrewAmber
import com.rokidlab.phone.design.BrewBorder
import com.rokidlab.phone.design.BrewChat
import com.rokidlab.phone.design.BrewCyan
import com.rokidlab.phone.design.BrewMuted
import com.rokidlab.phone.design.BrewPanel
import com.rokidlab.phone.design.BrewPanelHi
import com.rokidlab.phone.design.BrewPurple
import com.rokidlab.phone.design.BrewShapeStandard
import com.rokidlab.phone.design.BrewTextBright
import com.rokidlab.phone.domain.AiConfig
import com.rokidlab.phone.glasses.AiChannel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

// ===== 聊天输入栏的弹出层 =====
//
// 输入栏改版（2026-09-24）后，底部工具行只留两个图标：供应商（选模型）与思考（深度）。
// 2026-09-25 起两者统一为 ModalBottomSheet 上拉层（与「发送给大模型」附件菜单同款交互），
// 不再以普通卡片插在输入栏上方 —— 三块弹层一套形态，输入区上方的纵向空间不再被临时面板顶走。

/** 头像底色与 ProviderManagePage 共用 providerAccentOf（Lab 色板，按 PRESETS 顺序轮换） */

/** 弹出面板里每家供应商最多直出的模型行数；更多模型引导去管理页（那里上限 60） */
private const val PICK_MODEL_ROWS = 10

/**
 * 供应商 / 模型选择**上拉层**：列出**配置好的**供应商及其支持的模型，行尾是账户余额。
 * 点某条模型 = 切换对话到「该供应商 + 该模型」（写入在线槽位并下发眼镜端）。
 *
 * 2026-09-25 由"插在输入栏上方的卡片"升级为 ModalBottomSheet（与附件菜单同款）：
 * 点遮罩关闭、从底部滑入，输入区不再被临时面板纵向顶走。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun ProviderPickPanel(
    app: LabApplication,
    /** (供应商名, 模型名)：调用方负责 toast / 关面板 / 刷新输入栏标签 */
    onSwitched: (String, String) -> Unit,
    onManage: () -> Unit,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val session = try {
        app.cxrL
    } catch (e: Exception) {
        null
    }

    // 已配置供应商（钥匙串；在线槽位在用但没进钥匙串的旧配置会就地收编）
    var entries by remember { mutableStateOf(listOf<Pair<ProviderPreset, ProviderConfig>>()) }
    // 在线槽位快照（判断"哪条模型是当前在用"）
    var activeCfg by remember { mutableStateOf(session?.getOnlineAiConfig()) }
    // id → 余额展示串（不支持查余额的品牌不进这个 map，行尾显示 "--"）
    var balances by remember { mutableStateOf(mapOf<String, String>()) }
    // id → 模型列表（初始为空 = 用预设推荐清单；拉到 /models 后整组替换）
    var models by remember { mutableStateOf(mapOf<String, List<String>>()) }

    LaunchedEffect(Unit) {
        activeCfg = session?.getOnlineAiConfig()
        val merged = ProviderStore.configured(ctx).toMutableList()
        activeCfg?.let { a ->
            val id = ProviderCatalog.matchBaseUrl(a.baseUrl)?.id
            if (a.apiKey.isNotBlank() && id != null && merged.none { it.id == id }) {
                ProviderStore.save(ctx, ProviderConfig(id, a.baseUrl, a.apiKey, a.model))
                merged.add(ProviderConfig(id, a.baseUrl, a.apiKey, a.model))
            }
        }
        entries = merged.mapNotNull { c -> ProviderCatalog.byId(c.id)?.let { it to c } }
        // 并行拉：余额（仅支持的品牌）+ 实时模型列表（失败静默回落预设清单）
        merged.forEach { c ->
            val preset = ProviderCatalog.byId(c.id) ?: return@forEach
            if (preset.supportsBalance) {
                scope.launch(Dispatchers.IO) {
                    val b = ProviderCatalog.queryBalance(c.baseUrl, c.apiKey) ?: "--"
                    balances = balances + (c.id to b)
                }
            }
            scope.launch(Dispatchers.IO) {
                val fetched = runCatching {
                    LlmRegistry.listModels(c.baseUrl, c.apiKey, c.model)
                }.getOrNull()
                if (!fetched.isNullOrEmpty()) {
                    models = models + (c.id to fetched.take(100))
                }
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = BrewPanel,
        contentColor = BrewTextBright,
    ) {
        Column(modifier = Modifier.padding(bottom = 12.dp)) {
            Text(
                text = stringResource(R.string.chat_provider_pick_title),
                color = BrewTextBright,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 20.dp, top = 4.dp, bottom = 10.dp),
            )
            if (entries.isEmpty()) {
                Text(
                    text = stringResource(R.string.chat_provider_pick_empty),
                    color = BrewMuted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .heightIn(max = 340.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    entries.forEach { (preset, cfg) ->
                        val accent = providerAccentOf(preset.id)
                        // 供应商行：头像 + 名字 + 右侧余额
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // 品牌头像：内置矢量品牌图标（无图标回落首字母，同 rikkahub 的 AutoAIIcon 策略）
                            ProviderAvatar(
                                id = preset.id,
                                name = preset.name,
                                accent = accent,
                                circleSize = 26.dp,
                                iconSize = 15.dp,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = preset.name,
                                color = BrewTextBright,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (preset.supportsBalance) {
                                Text(
                                    text = balances[cfg.id] ?: "--",
                                    color = BrewMuted,
                                    fontSize = 11.sp,
                                )
                            }
                        }
                        // 模型行：当前在用的高亮，点击即切换。
                        // ⚠️ 先把**当前配置里的模型**并进列表：手输的模型名（自定义端点常见）可能不在
                        //   预设清单也不在 /models 返回里，不并进来就会出现"在用但列表里看不到"。
                        val list = (
                            listOf(cfg.model) + (models[cfg.id] ?: preset.models)
                            ).filter { it.isNotBlank() }.distinct()
                        list.take(PICK_MODEL_ROWS).forEach { m ->
                            val isActive = activeCfg?.let {
                                it.model == m && ProviderCatalog.matchBaseUrl(it.baseUrl)?.id == preset.id
                            } == true
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 34.dp, top = 3.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isActive) BrewChat.copy(alpha = 0.12f) else BrewPanelHi.copy(alpha = 0.35f))
                                    .clickable {
                                        if (!isActive && session != null) {
                                            // 保留拍照答题指令等其余字段，只换「哪家 + 哪个模型」
                                            session.setAiConfig(
                                                AiConfig(
                                                    baseUrl = cfg.baseUrl,
                                                    apiKey = cfg.apiKey,
                                                    model = m,
                                                    mode = AiChannel.AI_MODE_CUSTOM,
                                                    quizInstruction = activeCfg?.quizInstruction.orEmpty(),
                                                ),
                                            )
                                            activeCfg = session.getOnlineAiConfig()
                                            onSwitched(preset.name, m)
                                        }
                                    }
                                    .padding(horizontal = 10.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = m,
                                    color = if (isActive) BrewChat else BrewTextBright,
                                    fontSize = 12.sp,
                                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                if (isActive) {
                                    Text(text = "✓", color = BrewChat, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                        if (list.size > PICK_MODEL_ROWS) {
                            Text(
                                text = stringResource(R.string.chat_provider_pick_more, list.size - PICK_MODEL_ROWS),
                                color = BrewMuted,
                                fontSize = 10.sp,
                                modifier = Modifier.padding(start = 34.dp, top = 2.dp),
                            )
                        }
                    }
                }
            }
            // 页脚：管理入口（新增品牌 / 换密钥 / 看全部模型）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End,
            ) {
                TextButton(onClick = onManage) {
                    Text(
                        text = stringResource(R.string.chat_provider_pick_manage),
                        color = BrewChat,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

/**
 * 输入栏工具行的一枚按钮（供应商 / 思考 / 拍照 / 知识库统一用它）。
 *
 * 尺寸口径（2026-09-24 两轮收敛后的结论）：
 *  - 第一轮按用户要求整体放到 1.5x（45dp）—— 实测**过大**，用户当即反馈"又大了"；
 *  - 现在收到 **38dp**：仍比最初的 30dp 大一档（更易点中），但底行宽度回到
 *    6×38dp + 5×8dp = 268dp（可用宽约 331dp）⇒ **留出 63dp 余量**，
 *    以后再加一个按钮也不会顶出屏幕（45dp 那版只剩 3dp，等于封死）。
 * ⚠️ 附件键与发送键**不用本组件**：它们是与发送同尺寸的圆形钮（见 ChatScreen 输入区）。
 */
internal val ToolBarIconSize = 38.dp

@Composable
internal fun ToolBarIcon(
    selected: Boolean,
    accent: androidx.compose.ui.graphics.Color,
    description: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    icon: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(ToolBarIconSize)
            .clip(BrewShapeStandard)
            .background(if (selected) accent.copy(alpha = 0.18f) else BrewPanelHi)
            .border(
                width = 1.dp,
                color = if (selected) accent.copy(alpha = 0.6f) else BrewBorder,
                shape = BrewShapeStandard,
            )
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        icon()
    }
}

/**
 * 输入栏里与「发送键」同尺寸同形状的**圆形**按钮（目前只有附件键用它）。
 *
 * 为什么不给 [ToolBarIcon] 加个 shape 参数就完事：这两组的**设计语义本来就不同** ——
 *  - 方形小圆角组＝"设置类动作"（供应商 / 思考 / 拍照 / 知识库）；
 *  - 圆形组＝"对这条消息的动作"（附件 / 发送），且尺寸必须与发送键一致，
 *    否则一行的重心会飘（用户 2026-09-24 明确要求"上传按键跟发送按键大小一致"）。
 * 形状差异是**有意保留的信息**，不是风格不统一。
 */
@Composable
internal fun ToolBarCircleButton(
    selected: Boolean,
    accent: androidx.compose.ui.graphics.Color,
    description: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    icon: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(ToolBarIconSize)
            .clip(CircleShape)
            .background(if (selected) accent.copy(alpha = 0.18f) else BrewPanelHi)
            .border(
                width = 1.dp,
                color = if (selected) accent.copy(alpha = 0.6f) else BrewBorder,
                shape = CircleShape,
            )
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        icon()
    }
}

/**
 * 「发送给大模型」上拉菜单（ModalBottomSheet）：两个来源 —— 文件 / 图片。
 *
 * 为什么用上拉菜单而不是再挂两个图标：输入栏那一排已经挤了 6 个图标 + 发送，
 * 继续加图标会把每个按钮压到 30dp 以下（误触）；而"给模型材料"是一个**有子选择**的动作，
 * 上拉菜单天然承载「图标 + 名称 + 一句说明」，也让将来加"拍照/粘贴"这类来源有位置放。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun ChatAttachSheet(
    onPickFile: () -> Unit,
    onPickImage: () -> Unit,
    /** 拍照（走系统相机，自己给可写 Uri 拿原图，不是缩略图） */
    onTakePhoto: () -> Unit,
    /** 重发上次附件：null = 没有可重发的（菜单项隐藏，不给一个点了没用的入口） */
    onRetryLast: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = BrewPanel,
        contentColor = BrewTextBright,
    ) {
        Column(modifier = Modifier.padding(bottom = 12.dp)) {
            Text(
                text = stringResource(R.string.chat_attach_title),
                color = BrewTextBright,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 20.dp, top = 4.dp, bottom = 10.dp),
            )
            AttachOption(
                icon = Icons.Filled.Description,
                title = stringResource(R.string.chat_attach_file),
                subtitle = stringResource(R.string.chat_attach_file_hint),
                accent = BrewChat,
                onClick = onPickFile,
            )
            AttachOption(
                icon = Icons.Filled.Image,
                title = stringResource(R.string.chat_attach_image),
                subtitle = stringResource(R.string.chat_attach_image_hint),
                accent = BrewPurple,
                onClick = onPickImage,
            )
            AttachOption(
                icon = Icons.Filled.PhotoCamera,
                title = stringResource(R.string.chat_attach_camera),
                subtitle = stringResource(R.string.chat_attach_camera_hint),
                accent = BrewAmber,
                onClick = onTakePhoto,
            )
            if (onRetryLast != null) {
                AttachOption(
                    icon = Icons.Filled.Refresh,
                    title = stringResource(R.string.chat_attach_retry),
                    subtitle = stringResource(R.string.chat_attach_retry_hint),
                    accent = BrewCyan,
                    onClick = onRetryLast,
                )
            }
        }
    }
}

/** 上拉菜单里的一行（图标 + 标题 + 说明），整体可点 */
@Composable
private fun AttachOption(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    accent: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(BrewPanelHi.copy(alpha = 0.5f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                // 12dp = BrewShapeStandard：这个 38dp 图标底框与输入栏工具按钮同尺寸同性质，
                // 原先写的 11dp 是"全项目只此一处"的野值（卡片约定是 10、中控件是 12）
                .clip(BrewShapeStandard)
                .background(accent.copy(alpha = 0.16f))
                .border(1.dp, accent.copy(alpha = 0.45f), BrewShapeStandard),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, color = BrewTextBright, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(text = subtitle, color = BrewMuted, fontSize = 11.sp)
        }
    }
}

/**
 * 输入栏上方的「待发送附件」提示条：文件显示名字与字数、图片显示缩略图，右侧 × 移除。
 *
 * 为什么附件要"先挂上再发送"而不是选完立刻发：用户给模型的往往是「材料 + 一句问题」，
 * 选完就发会把问题丢在后面变成第二轮；挂成待发送项，才能"选材料 → 写问题 → 一起发"。
 */
@Composable
internal fun PendingAttachBar(
    fileName: String?,
    fileTruncated: Boolean,
    imageThumb: android.graphics.Bitmap?,
    imageName: String?,
    onRemoveFile: () -> Unit,
    onRemoveImage: () -> Unit,
    /** 文件的字符数（null = 未知/不显示）；与 [contextWindow] 一起给出「占上下文 x%」 */
    fileChars: Int? = null,
    contextWindow: Int = 0,
    /** 引用消息的正文（非空则多渲染一条引用条） */
    quoteText: String? = null,
    quoteFromUser: Boolean = false,
    onRemoveQuote: () -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        quoteText?.let { text ->
            AttachChip(
                icon = {
                    Icon(
                        imageVector = Icons.Filled.FormatQuote,
                        contentDescription = null,
                        tint = BrewAmber,
                        modifier = Modifier.size(20.dp),
                    )
                },
                title = stringResource(
                    if (quoteFromUser) R.string.chat_quote_from_me else R.string.chat_quote_from_ai,
                ),
                subtitle = text.replace('\n', ' ').take(60),
                accent = BrewAmber,
                onRemove = onRemoveQuote,
            )
            Spacer(Modifier.height(6.dp))
        }
        imageThumb?.let { bmp ->
            AttachChip(
                icon = {
                    androidx.compose.foundation.Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(6.dp)),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    )
                },
                title = imageName ?: stringResource(R.string.chat_attach_image),
                subtitle = stringResource(R.string.chat_attach_image_ready),
                accent = BrewPurple,
                onRemove = onRemoveImage,
            )
            Spacer(Modifier.height(6.dp))
        }
        fileName?.let { name ->
            AttachChip(
                icon = {
                    Icon(
                        imageVector = Icons.Filled.Description,
                        contentDescription = null,
                        tint = BrewChat,
                        modifier = Modifier.size(20.dp),
                    )
                },
                title = name,
                subtitle = buildFileSubtitle(fileTruncated, fileChars, contextWindow),
                accent = BrewChat,
                onRemove = onRemoveFile,
            )
        }
    }
}

/**
 * 文件条副标题：截断状态 + 约多少字 + 占当前模型上下文窗口的百分比。
 *
 * 为什么要把占比摆到界面上：附件正文是**悄悄**进上下文的，用户看不到它挤掉多少空间；
 * 等压缩触发（或服务端报 context length）时，用户已经完全不知道是哪一步导致的。
 */
@Composable
private fun buildFileSubtitle(truncated: Boolean, chars: Int?, contextWindow: Int): String {
    val base = stringResource(
        if (truncated) R.string.chat_attach_truncated else R.string.chat_attach_file_ready,
    )
    if (chars == null || chars <= 0) return base
    val charsPart = stringResource(R.string.chat_attach_chars, chars)
    // contextWindow 是 token 数，字符→token 按中文约 1.5 字/token 粗估（只用于"心里有数"）
    if (contextWindow <= 0) return "$base · $charsPart"
    val approxTokens = (chars / 1.5f).toInt()
    val percent = (approxTokens * 100 / contextWindow).coerceIn(0, 100)
    return "$base · $charsPart · " + stringResource(R.string.chat_attach_ctx_percent, percent)
}

/** 附件条里的一条（圆角卡：图标/缩略图 + 名字 + 状态 + ×） */
@Composable
private fun AttachChip(
    icon: @Composable () -> Unit,
    title: String,
    subtitle: String,
    accent: androidx.compose.ui.graphics.Color,
    onRemove: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(BrewPanel)
            .border(1.dp, accent.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(28.dp),
            contentAlignment = Alignment.Center,
        ) { icon() }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = BrewTextBright,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(text = subtitle, color = BrewMuted, fontSize = 10.sp)
        }
        Icon(
            imageVector = Icons.Filled.Close,
            contentDescription = stringResource(R.string.chat_attach_remove),
            tint = BrewMuted,
            modifier = Modifier
                .size(18.dp)
                .clickable(onClick = onRemove),
        )
    }
}

/**
 * 思考深度**上拉层**：三档 —— 自动（默认，跟随既有安全行为）/ 深度思考 / 关闭。
 * 持久化口径见 ChatScreen.setThinkMode：同键写 mode 串 + 兼容旧布尔（ai_thinking）。
 *
 * 2026-09-25 由"插在输入栏上方的卡片"升级为 ModalBottomSheet，行式样与附件菜单（AttachOption）
 * 同款：彩色图标底框 + 标题 + 一句说明，当前档位整行高亮 + 行尾 ✓。
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun ThinkPickPanel(
    current: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = BrewPanel,
        contentColor = BrewTextBright,
    ) {
        Column(modifier = Modifier.padding(bottom = 12.dp)) {
            Text(
                text = stringResource(R.string.chat_think_mode_title),
                color = BrewTextBright,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 20.dp, top = 4.dp, bottom = 10.dp),
            )
            ThinkOption(
                icon = Icons.Outlined.Psychology,
                title = stringResource(R.string.chat_think_mode_auto),
                subtitle = stringResource(R.string.chat_think_desc_auto),
                accent = BrewChat,
                selected = current == "auto",
                onClick = { onSelect("auto") },
            )
            ThinkOption(
                icon = Icons.Filled.Psychology,
                title = stringResource(R.string.chat_think_mode_deep),
                subtitle = stringResource(R.string.chat_think_desc_deep),
                accent = BrewAmber,
                selected = current == "deep",
                onClick = { onSelect("deep") },
            )
            ThinkOption(
                icon = Icons.Filled.PsychologyAlt,
                title = stringResource(R.string.chat_think_mode_off),
                subtitle = stringResource(R.string.chat_think_desc_off),
                accent = BrewMuted,
                selected = current == "off",
                onClick = { onSelect("off") },
            )
        }
    }
}

/** 思考深度上拉层的一行：图标底框 + 标题 + 说明，选中时整行按 accent 高亮、行尾 ✓ */
@Composable
private fun ThinkOption(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    accent: Color,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) accent.copy(alpha = 0.16f) else BrewPanelHi.copy(alpha = 0.5f))
            .border(
                width = 1.dp,
                color = if (selected) accent.copy(alpha = 0.6f) else Color.Transparent,
                shape = RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(BrewShapeStandard)
                .background(accent.copy(alpha = 0.16f))
                .border(1.dp, accent.copy(alpha = 0.45f), BrewShapeStandard),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, color = BrewTextBright, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(text = subtitle, color = BrewMuted, fontSize = 11.sp)
        }
        if (selected) {
            Text(text = "✓", color = accent, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
    }
}
