package com.rokidlab.phone.adb.ui

import com.rokidlab.phone.design.*
import com.rokidlab.phone.R
import com.rokidlab.phone.adb.AdbShellClient
import com.rokidlab.phone.store.ModuleHeader
import com.rokidlab.phone.store.RokidLinkStatusCard
import com.rokidlab.phone.store.IpAddressInputCard
import com.rokidlab.phone.store.BrutalButton
import com.rokidlab.phone.store.UsageInstructionsCard
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

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
