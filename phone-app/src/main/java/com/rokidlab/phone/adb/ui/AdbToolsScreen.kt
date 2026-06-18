package com.rokidlab.phone.adb.ui

import com.rokidlab.phone.design.*
import com.rokidlab.phone.R
import com.rokidlab.phone.adb.AdbShellClient
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun AdbToolsScreen(
    client: AdbShellClient?,
    connected: Boolean,
    scope: kotlinx.coroutines.CoroutineScope,
    getOrConnect: ((AdbShellClient?) -> Unit) -> Unit,
    onDisconnect: () -> Unit = {},
    // RokidLink 状态（与 FileManagerModule 完全一致）
    rokidLinkInstalled: Boolean,
    rokidLinkInstalling: Boolean,
    rokidLinkRunning: Boolean,
    onInstallRokidLink: () -> Unit,
    onOpenRokidLink: () -> Unit,
    onStopRokidLink: () -> Unit,
) {
    val ctx = LocalContext.current
    val prefs = ctx.getSharedPreferences("adb_prefs", 0)
    var ip by remember { mutableStateOf(prefs.getString("ip", "192.168.1.168") ?: "192.168.1.168") }

    var showSysInfo by remember { mutableStateOf(false) }
    var showAppMgr by remember { mutableStateOf(false) }
    var showTimer by remember { mutableStateOf(false) }
    var showShell by remember { mutableStateOf(false) }

    // ADB 工具模块，颜色使用导航栏 ADB 主题色 BrewInfo
    Column(modifier = Modifier.padding(16.dp)) {
        // 1. ModuleHeader
        ModuleHeader(
            title = ctx.getString(R.string.adb_tools_title),
            subtitle = ctx.getString(R.string.adb_tools_subtitle),
            color = BrewInfo,
        )

        Spacer(modifier = Modifier.height(24.dp))

        // 2. RokidLinkStatusCard — 与 FileManagerModule 完全一致
        RokidLinkStatusCard(
            installed = rokidLinkInstalled,
            installing = rokidLinkInstalling,
            running = rokidLinkRunning,
            onInstall = onInstallRokidLink,
            onOpen = onOpenRokidLink,
            onStop = onStopRokidLink,
            ctx = ctx,
        )

        Spacer(modifier = Modifier.height(16.dp))

        // 3. IpAddressInputCard — 与 FileManagerModule 完全一致
        IpAddressInputCard(
            label = ctx.getString(R.string.ip_address_label),
            value = ip,
            onValueChange = { newVal ->
                ip = newVal
                prefs.edit().putString("ip", newVal).apply()
            },
            color = BrewInfo,
        )

        Spacer(modifier = Modifier.height(16.dp))

        // 4. 四个工具按钮 — 各自独立，点击后弹窗自动处理 ADB 连接
        BrutalButton(
            label = ctx.getString(R.string.system_info),
            color = BrewInfo,
            onClick = { showSysInfo = true },
        )
        Spacer(modifier = Modifier.height(8.dp))

        BrutalButton(
            label = ctx.getString(R.string.app_manager),
            color = BrewGreen,
            onClick = { showAppMgr = true },
        )
        Spacer(modifier = Modifier.height(8.dp))

        BrutalButton(
            label = ctx.getString(R.string.timer_func),
            color = BrewWarning,
            onClick = { showTimer = true },
        )
        Spacer(modifier = Modifier.height(8.dp))

        BrutalButton(
            label = ctx.getString(R.string.shell_command),
            color = BrewMagenta,
            onClick = { showShell = true },
        )

        Spacer(modifier = Modifier.height(24.dp))

        // 5. UsageInstructionsCard — 与 FileManagerModule 完全一致
        UsageInstructionsCard(
            color = BrewInfo,
            instructions = listOf(
                "1. ${ctx.getString(R.string.connecting_adb_hint)}（5555）",
                "2. ${ctx.getString(R.string.ip_address_label)}（${ip}）",
                "3. ${ctx.getString(R.string.system_info)} / ${ctx.getString(R.string.app_manager)} / ${ctx.getString(R.string.timer_func)}",
                "4. ${ctx.getString(R.string.shell_command)}",
                "5. ${ctx.getString(R.string.adb_tools_subtitle)}",
            ),
            ctx = ctx,
        )

        Spacer(modifier = Modifier.height(16.dp))
    }

    // 工具弹窗 — 各 dialog 内部通过 AdbDialogContent 自动处理连接
    if (showSysInfo) SysInfoDialog(client, connected, scope, { cb -> getOrConnect(cb) }) { showSysInfo = false }
    if (showAppMgr) AppMgrDialog(client, connected, scope, { cb -> getOrConnect(cb) }) { showAppMgr = false }
    if (showTimer) TimerDialog(client, connected, scope, { cb -> getOrConnect(cb) }) { showTimer = false }
    if (showShell) ShellDialog(client, connected, scope, { cb -> getOrConnect(cb) }) { showShell = false }
}

// ——— 以下组件与 FileManagerModule 中的组件实现完全一致 ———

@Composable
private fun ModuleHeader(title: String, subtitle: String, color: Color) {
    Column {
        Text(text = title, color = color, fontSize = 32.sp, fontWeight = FontWeight.Black, letterSpacing = 4.sp)
        Spacer(modifier = Modifier.height(8.dp))
        Text(text = subtitle, color = BrewMuted, fontSize = 14.sp)
    }
}

@Composable
private fun RokidLinkStatusCard(
    installed: Boolean,
    installing: Boolean,
    running: Boolean = false,
    onInstall: () -> Unit,
    onOpen: () -> Unit,
    onStop: (() -> Unit)? = null,
    ctx: android.content.Context,
) {
    val statusColor = when {
        running -> BrewWarning
        installing -> BrewCyan
        installed -> BrewSuccess
        else -> BrewRed
    }
    val statusBg = when {
        running -> BrewWarning
        installing -> BrewCyan
        installed -> BrewSuccess
        else -> BrewRed
    }
    val statusText = when {
        running -> ctx.getString(R.string.running)
        installing -> ctx.getString(R.string.installing)
        installed -> ctx.getString(R.string.installed)
        else -> ctx.getString(R.string.not_installed)
    }
    val statusIcon = when {
        running -> "▶"
        installing -> "►"
        installed -> "✔"
        else -> "✘"
    }

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseOffset by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 8f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse-offset",
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(BrewShapeStandard)
            .background(BrewPanel)
            .border(width = 1.dp, color = statusColor.copy(alpha = 0.3f), shape = BrewShapeStandard),
    ) {
        // 顶部状态条
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(statusBg)
                .padding(horizontal = 20.dp, vertical = 14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = statusIcon,
                    color = BrewBg,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(end = 12.dp),
                )
                Column {
                    Text(
                        text = "ROKIDLINK $statusText",
                        color = BrewBg,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp,
                    )
                    Text(
                        text = ctx.getString(R.string.glasses_services),
                        color = BrewBg.copy(alpha = 0.7f),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 1.sp,
                    )
                }
            }
        }

        // 偏移装饰线（安装中呼吸脉冲）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .background(BrewBorder)
                .offset(x = if (installing) pulseOffset.dp else 8.dp),
        )

        // 底部操作区
        Box(modifier = Modifier.padding(16.dp)) {
            if (installing) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .background(BrewPanelAlt, BrewShapeStandard)
                        .border(width = 1.dp, color = BrewCyan.copy(alpha = 0.3f), shape = BrewShapeStandard),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = "⟳", color = BrewCyan, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 12.dp))
                        Text(text = ctx.getString(R.string.installing), color = BrewCyan, fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
                    }
                }
            } else if (running) {
                BrutalButton(
                    label = "● ${ctx.getString(R.string.stop_mirror)} RokidLink",
                    color = BrewRed,
                    onClick = onStop ?: {},
                )
            } else if (!installed) {
                BrutalButton(
                    label = "● ${ctx.getString(R.string.install)} RokidLink",
                    color = BrewAmber,
                    onClick = onInstall,
                )
            } else {
                BrutalButton(
                    label = ctx.getString(R.string.launch),
                    color = BrewSuccess,
                    onClick = onOpen,
                )
            }
        }
    }
}

@Composable
private fun IpAddressInputCard(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    color: Color = BrewCyan,
) {
    var text by remember { mutableStateOf(value) }
    Box(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(BrewPanel, BrewShapeStandard)
                .border(width = 1.dp, color = color, shape = BrewShapeStandard)
                .padding(16.dp),
        ) {
            Text(text = label, color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, modifier = Modifier.padding(bottom = 8.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { newVal ->
                    text = newVal
                    onValueChange(newVal)
                },
                placeholder = { Text("192.168.1.168", color = BrewMuted) },
                textStyle = TextStyle(color = BrewTextBright),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = color, unfocusedBorderColor = BrewBorder),
            )
        }
    }
}

@Composable
private fun BrutalButton(
    label: String,
    color: Color,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    val effectiveColor = if (enabled) color else BrewMuted
    val effectiveAlpha = if (enabled) 0.12f else 0.08f
    val effectiveBorderAlpha = if (enabled) 0.5f else 0.3f

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(BrewShapeStandard)
            .background(effectiveColor.copy(alpha = effectiveAlpha))
            .border(width = 1.dp, color = effectiveColor.copy(alpha = effectiveBorderAlpha), shape = BrewShapeStandard)
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = effectiveColor,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(vertical = 16.dp),
        )
    }
}

@Composable
private fun UsageInstructionsCard(
    color: Color = BrewInfo,
    instructions: List<String>,
    ctx: android.content.Context,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(BrewPanel, BrewShapeStandard)
            .border(width = 1.dp, color = BrewBorder, shape = RoundedCornerShape(12.dp))
            .padding(16.dp),
    ) {
        Column {
            Text(text = ctx.getString(R.string.usage_instructions), color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, modifier = Modifier.padding(bottom = 12.dp))
            instructions.forEach { instruction ->
                Text(text = instruction, color = BrewMuted, fontSize = 12.sp, lineHeight = 20.sp, modifier = Modifier.padding(bottom = 4.dp))
            }
        }
    }
}
