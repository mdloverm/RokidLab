package com.rokidlab.phone.adb.ui

import com.rokidlab.phone.adb.AdbShellClient
import com.rokidlab.phone.design.*
import com.rokidlab.phone.R
import android.util.Log
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 按键映射配置 */
data class KeyButtonConfig(
    val shortPressPkg: String = "",
    val shortPressActivity: String = ".MainActivity",
    val longPressPkg: String = "",
    val longPressActivity: String = ".MainActivity",
) {
    fun isShortPressValid() = shortPressPkg.isNotBlank()
    fun isLongPressValid() = longPressPkg.isNotBlank()
    fun isAnyValid() = isShortPressValid() || isLongPressValid()
}

private const val PREFS_NAME = "key_button_prefs"
private const val KEY_SHORT_PKG = "short_press_pkg"
private const val KEY_SHORT_ACT = "short_press_activity"
private const val KEY_LONG_PKG = "long_press_pkg"
private const val KEY_LONG_ACT = "long_press_activity"

private val PRIMARY_COLOR = BrewMagenta

@Composable
fun KeyButtonDialog(
    onLaunchAppViaSdk: ((String, String) -> Unit)?,
    onSendKeyButtonConfig: ((String, String, String, String, (Boolean) -> Unit) -> Unit)? = null,
    client: AdbShellClient? = null,
    connected: Boolean = false,
    scope: kotlinx.coroutines.CoroutineScope? = null,
    getOrConnect: (((AdbShellClient?) -> Unit) -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences(PREFS_NAME, 0)

    // 自动连接 ADB（和其他弹窗一致）
    var isConnecting by remember { mutableStateOf(!connected || client == null) }
    LaunchedEffect(Unit) {
        if (connected && client != null) {
            isConnecting = false
        } else {
            isConnecting = true
            getOrConnect?.invoke { c ->
                isConnecting = c == null
            }
        }
    }

    // 异步加载应用列表
    var appPackages by remember { mutableStateOf(emptyList<String>()) }
    var loading by remember { mutableStateOf(true) }
    LaunchedEffect(isConnecting) {
        if (isConnecting) return@LaunchedEffect
        loading = true
        Log.i("KeyBtn", "Loading packages, client=${client != null}")
        if (client != null) {
            val pkgs = withContext(Dispatchers.IO) { client.listPackages(false) }
            Log.i("KeyBtn", "Loaded ${pkgs.size} packages")
            appPackages = pkgs
        } else {
            Log.w("KeyBtn", "Client is null, showing empty list")
        }
        loading = false
    }

    var config by remember {
        mutableStateOf(
            KeyButtonConfig(
                shortPressPkg = prefs.getString(KEY_SHORT_PKG, "") ?: "",
                shortPressActivity = prefs.getString(KEY_SHORT_ACT, ".MainActivity") ?: ".MainActivity",
                longPressPkg = prefs.getString(KEY_LONG_PKG, "") ?: "",
                longPressActivity = prefs.getString(KEY_LONG_ACT, ".MainActivity") ?: ".MainActivity",
            )
        )
    }

    var statusText by remember { mutableStateOf("") }
    var statusColor by remember { mutableStateOf(PRIMARY_COLOR) }

    fun saveConfig(cfg: KeyButtonConfig) {
        prefs.edit()
            .putString(KEY_SHORT_PKG, cfg.shortPressPkg)
            .putString(KEY_SHORT_ACT, cfg.shortPressActivity)
            .putString(KEY_LONG_PKG, cfg.longPressPkg)
            .putString(KEY_LONG_ACT, cfg.longPressActivity)
            .apply()
    }

    /** 保存配置并发送到眼镜端 */
    fun saveAndSendToGlasses(cfg: KeyButtonConfig) {
        saveConfig(cfg)
        if (onSendKeyButtonConfig == null) {
            statusText = context.getString(R.string.key_btn_no_sdk)
            statusColor = BrewWarning
            return
        }
        statusText = context.getString(R.string.key_btn_sending, cfg.shortPressPkg, cfg.longPressPkg)
        statusColor = BrewInfo
        onSendKeyButtonConfig(
            cfg.shortPressPkg,
            cfg.shortPressActivity,
            cfg.longPressPkg,
            cfg.longPressActivity,
        ) { success ->
            statusText = if (success) {
                context.getString(R.string.key_btn_sent, cfg.shortPressPkg, cfg.longPressPkg)
            } else {
                context.getString(R.string.key_btn_send_failed)
            }
            statusColor = if (success) BrewSuccess else BrewWarning
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 500.dp)
                .heightIn(max = 640.dp)
                .padding(16.dp)
                .clip(BrewShapeSmall)
                .background(BrewBg)
                .border(1.dp, PRIMARY_COLOR.copy(alpha = 0.4f), BrewShapeSmall)
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // ── 标题栏 ──
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column {
                            Text(
                                context.getString(R.string.key_btn_title),
                                color = BrewText,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                context.getString(R.string.key_btn_subtitle),
                                color = BrewMuted,
                                fontSize = 12.sp,
                            )
                        }
                        Spacer(modifier = Modifier.size(32.dp))
                    }
                }

                item { Spacer(modifier = Modifier.height(4.dp)) }

                // 连接中 / 加载中提示
                if (isConnecting) {
                    item {
                        Text(
                            "正在连接眼镜...",
                            color = BrewMuted,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    }
                } else if (loading) {
                    item {
                        Text(
                            "正在获取应用列表...",
                            color = BrewMuted,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    }
                }

                // 连接失败提示
                if (!isConnecting && !loading && appPackages.isEmpty() && client == null) {
                    item {
                        Text(
                            "（未连接眼镜，请先连接）",
                            color = BrewWarning,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    }
                }

                // ══════════ 短按配置 ══════════
                item { SectionHeader(context.getString(R.string.key_btn_short_press), PRIMARY_COLOR) }
                item {
                    SelectablePackageList(
                        packages = appPackages,
                        selectedPkg = config.shortPressPkg,
                        onSelect = { pkg ->
                            config = config.copy(shortPressPkg = pkg)
                            saveConfig(config)
                        },
                        color = PRIMARY_COLOR,
                    )
                }
                item {
                    BrutalTextField(
                        value = config.shortPressActivity,
                        onValueChange = {
                            config = config.copy(shortPressActivity = it)
                            saveConfig(config)
                        },
                        placeholder = context.getString(R.string.key_btn_activity_hint),
                        color = PRIMARY_COLOR,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }

                // ══════════ 分隔线 ══════════
                item {
                    Spacer(modifier = Modifier.height(4.dp))
                    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(BrewBorder))
                    Spacer(modifier = Modifier.height(4.dp))
                }

                // ══════════ 长按配置 ══════════
                item { SectionHeader(context.getString(R.string.key_btn_long_press), PRIMARY_COLOR) }
                item {
                    SelectablePackageList(
                        packages = appPackages,
                        selectedPkg = config.longPressPkg,
                        onSelect = { pkg ->
                            config = config.copy(longPressPkg = pkg)
                            saveConfig(config)
                        },
                        color = PRIMARY_COLOR,
                    )
                }
                item {
                    BrutalTextField(
                        value = config.longPressActivity,
                        onValueChange = {
                            config = config.copy(longPressActivity = it)
                            saveConfig(config)
                        },
                        placeholder = context.getString(R.string.key_btn_activity_hint),
                        color = PRIMARY_COLOR,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }

                // ══════════ 保存到眼镜 ══════════
                item {
                    Spacer(modifier = Modifier.height(6.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .clip(BrewShapeSmall)
                            .background(if (config.isAnyValid()) PRIMARY_COLOR.copy(alpha = 0.2f) else BrewBg)
                            .border(
                                1.dp,
                                if (config.isAnyValid()) PRIMARY_COLOR else BrewBorder,
                                BrewShapeSmall,
                            )
                            .clickable(enabled = config.isAnyValid()) {
                                saveAndSendToGlasses(config)
                            }
                            .padding(horizontal = 24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            context.getString(R.string.key_btn_save_to_glasses),
                            color = if (config.isAnyValid()) PRIMARY_COLOR else BrewMuted,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }

                // ── 状态提示 ──
                if (statusText.isNotBlank()) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(BrewShapeSmall)
                                .background(statusColor.copy(alpha = 0.08f))
                                .border(1.dp, statusColor.copy(alpha = 0.3f), BrewShapeSmall)
                                .padding(12.dp),
                        ) {
                            Text(statusText, color = statusColor, fontSize = 12.sp)
                        }
                    }
                }

                // ── SDK 未就绪提示 ──
                if (onSendKeyButtonConfig == null) {
                    item {
                        WarningBox(context.getString(R.string.key_btn_no_sdk), BrewWarning)
                    }
                }

                // ── 底部留白 ──
                item { Spacer(modifier = Modifier.height(8.dp)) }
            }
        }
    }
}

// ═══════════════════════════════════════
//  子组件
// ═══════════════════════════════════════

/** 第三方应用包名下拉列表 */
@Composable
private fun SelectablePackageList(
    packages: List<String>,
    selectedPkg: String,
    onSelect: (String) -> Unit,
    color: Color,
) {
    var expanded by remember { mutableStateOf(false) }
    
    val displayText = selectedPkg.ifBlank {
        if (packages.isEmpty()) "暂无第三方应用，可手动输入包名" else "请选择第三方应用"
    }
    val hasSelection = selectedPkg.isNotBlank()
    val canExpand = packages.isNotEmpty()

    Box {
        // 触发器
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(if (hasSelection) color.copy(alpha = 0.15f) else color.copy(alpha = 0.05f))
                .border(
                    width = if (hasSelection) 1.5.dp else 1.dp,
                    color = if (hasSelection) color else color.copy(alpha = 0.2f),
                    shape = RoundedCornerShape(6.dp),
                )
                .clickable(enabled = canExpand) { expanded = canExpand }
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = displayText,
                    color = if (hasSelection) color else BrewMuted,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (canExpand) {
                    Text(
                        text = if (expanded) "\u25B2" else "\u25BC",
                        color = color.copy(alpha = 0.6f),
                        fontSize = 10.sp,
                    )
                }
            }
        }

        // 下拉菜单
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .heightIn(max = 280.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(BrewBg)
                .border(1.dp, color.copy(alpha = 0.2f), RoundedCornerShape(8.dp)),
        ) {
            packages.forEach { pkg ->
                val isSelected = pkg == selectedPkg
                DropdownMenuItem(
                    text = {
                        Text(
                            text = pkg,
                            color = if (isSelected) color else BrewText,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    onClick = {
                        onSelect(pkg)
                        expanded = false
                    },
                    modifier = Modifier
                        .background(
                            if (isSelected) color.copy(alpha = 0.12f) else Color.Transparent,
                            RoundedCornerShape(4.dp),
                        )
                        .padding(vertical = 2.dp),
                )
            }
        }
    }
}

/** 带图标的警告/提示框 */
@Composable
private fun WarningBox(message: String, color: Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(BrewShapeSmall)
            .background(color.copy(alpha = 0.08f))
            .border(1.dp, color.copy(alpha = 0.2f), BrewShapeSmall)
            .padding(12.dp),
    ) {
        Text(message, color = color, fontSize = 11.sp, lineHeight = 16.sp)
    }
}

@Composable
private fun SectionHeader(title: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.width(3.dp).height(16.dp).background(color, BrewShapeSmall))
        Spacer(modifier = Modifier.width(8.dp))
        Text(title, color = color, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}
