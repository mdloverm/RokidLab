package com.rokidlab.phone.adb.ui

import com.rokidlab.phone.design.*
import com.rokidlab.phone.R
import com.rokidlab.phone.adb.AdbShellClient
import com.rokidlab.phone.store.BrutalButton
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * ADB 工具按钮组（供「乐奇工具」页内嵌）。
 *
 * 已移除 ModuleHeader 与 IP 输入框：
 *  - 页面标题由外层 LeqiToolsModule 统一提供，避免重复；
 *  - 眼镜 IP 由 Lab 自动获取（RokidLink 经 CXR 上报到单一数据源），无需手动输入。
 * 各工具弹窗内部通过 getOrConnect 自动建立 ADB 连接。
 */
@Composable
fun AdbToolsScreen(
    client: AdbShellClient?,
    connected: Boolean,
    scope: kotlinx.coroutines.CoroutineScope,
    getOrConnect: ((AdbShellClient?) -> Unit) -> Unit,
    onDisconnect: () -> Unit = {},
    // 通过 CXR-L SDK 启动眼镜端应用（替代 ADB shell）
    onLaunchAppViaSdk: ((String, String) -> Unit)? = null,
    // 通过 SDK 发送按键配置到眼镜端
    onSendKeyButtonConfig: ((String, String, String, String, (Boolean) -> Unit) -> Unit)? = null,
) {
    val ctx = LocalContext.current

    var showSysInfo by remember { mutableStateOf(false) }
    var showAppMgr by remember { mutableStateOf(false) }
    var showTimer by remember { mutableStateOf(false) }
    var showKeyBtn by remember { mutableStateOf(false) }
    var showShell by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {
        BrutalButton(
            label = ctx.getString(R.string.system_info),
            color = BrewCoral,
            onClick = { showSysInfo = true },
        )
        Spacer(modifier = Modifier.height(8.dp))

        BrutalButton(
            label = ctx.getString(R.string.app_manager),
            color = BrewCyan,
            onClick = { showAppMgr = true },
        )
        Spacer(modifier = Modifier.height(8.dp))

        BrutalButton(
            label = ctx.getString(R.string.timer_func),
            color = BrewPurple,
            onClick = { showTimer = true },
        )
        Spacer(modifier = Modifier.height(8.dp))

        BrutalButton(
            label = ctx.getString(R.string.key_btn_title),
            color = BrewMagenta,
            onClick = { showKeyBtn = true },
        )
        Spacer(modifier = Modifier.height(8.dp))

        BrutalButton(
            label = ctx.getString(R.string.shell_command),
            color = BrewAmber,
            onClick = { showShell = true },
        )
    }

    // 工具弹窗 — 各 dialog 内部通过 AdbDialogContent 自动处理连接
    if (showSysInfo) SysInfoDialog(client, connected, scope, { cb -> getOrConnect(cb) }) { showSysInfo = false }
    if (showAppMgr) AppMgrDialog(client, connected, scope, { cb -> getOrConnect(cb) }) { showAppMgr = false }
    if (showTimer) TimerDialog(client, connected, scope, { cb -> getOrConnect(cb) }) { showTimer = false }
    if (showKeyBtn) {
        KeyButtonDialog(
            onLaunchAppViaSdk = onLaunchAppViaSdk,
            onSendKeyButtonConfig = onSendKeyButtonConfig,
            client = client,
            connected = connected,
            scope = scope,
            getOrConnect = { cb -> getOrConnect(cb) },
            onDismiss = { showKeyBtn = false },
        )
    }
    if (showShell) ShellDialog(client, connected, scope, { cb -> getOrConnect(cb) }) { showShell = false }
}
