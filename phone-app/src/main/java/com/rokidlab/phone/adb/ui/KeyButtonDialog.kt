package com.rokidlab.phone.adb.ui

import com.rokidlab.phone.design.*
import com.rokidlab.phone.R
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
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
    appPackages: List<String> = emptyList(),
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences(PREFS_NAME, 0)

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

/** 第三方应用包名列表（可选中） */
@Composable
private fun SelectablePackageList(
    packages: List<String>,
    selectedPkg: String,
    onSelect: (String) -> Unit,
    color: Color,
) {
    if (packages.isEmpty()) {
        Text(
            when {
                selectedPkg.isNotBlank() -> "（$selectedPkg）"
                else -> "（暂无第三方应用，可手动输入包名）"
            },
            color = BrewMuted,
            fontSize = 12.sp,
        )
        return
    }
    Column {
        Text("第三方应用：", color = BrewMuted, fontSize = 11.sp)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            packages.forEach { pkg ->
                val selected = pkg == selectedPkg
                Box(
                    modifier = Modifier
                        .height(34.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (selected) color.copy(alpha = 0.2f) else color.copy(alpha = 0.05f))
                        .border(
                            width = if (selected) 1.5.dp else 1.dp,
                            color = if (selected) color else color.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(6.dp),
                        )
                        .clickable { onSelect(pkg) }
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = pkg,
                        color = if (selected) color else BrewMuted,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
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
