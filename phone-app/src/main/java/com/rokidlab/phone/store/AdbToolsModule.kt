package com.rokidlab.phone.store

import com.rokidlab.phone.adb.AdbShellClient
import com.rokidlab.phone.app.LabApplication
import com.rokidlab.phone.connection.ConnectionRoute
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ===== ADB 工具模块 =====
@Composable
internal fun AdbToolsModule(
    state: StoreUiState,
    actions: StoreActions,
    app: LabApplication,
) {
    val ctx = LocalContext.current
    var connected by remember { mutableStateOf(false) }
    var client by remember { mutableStateOf<AdbShellClient?>(null) }
    val scope = rememberCoroutineScope()
    
    fun disconnectClient() {
        client?.disconnect()
        client = null
        connected = false
    }
    
    fun getOrConnect(onConnected: (AdbShellClient?) -> Unit) {
        val existing = client
        if (existing != null && connected) {
            onConnected(existing)
            return
        }
        // ADB 工具使用自己的 IP 配置（adb_prefs），与投屏/文件管理器独立
        val adbPrefs = ctx.getSharedPreferences("adb_prefs", 0)
        val wifiIp = adbPrefs.getString("ip", "192.168.1.168") ?: "192.168.1.168"
        scope.launch(Dispatchers.IO) {
            try {
                val route = app.routeManager.resolve(wifiIp, 5555)
                val (targetIp, targetPort) = when (route) {
                    is ConnectionRoute.Wifi -> route.ip to route.port
                    is ConnectionRoute.Bluetooth -> route.ip to route.localPort
                    is ConnectionRoute.None -> {
                        withContext(Dispatchers.Main) { onConnected(null) }
                        return@launch
                    }
                }
                val c = AdbShellClient(app, targetIp, targetPort)
                val ok = c.connect()
                withContext(Dispatchers.Main) {
                    if (ok) {
                        client = c
                        connected = true
                        onConnected(c)
                    } else {
                        onConnected(null)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onConnected(null) }
            }
        }
    }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState()),
    ) {
        com.rokidlab.phone.adb.ui.AdbToolsScreen(
            client = client,
            connected = connected,
            scope = scope,
            getOrConnect = { cb -> getOrConnect(cb) },
            onDisconnect = { disconnectClient() },
            // RokidLink 状态 — 复用 FileManagerModule 的同一套状态
            rokidLinkInstalled = state.fileManagerState.rokidLinkInstalled == true,
            rokidLinkInstalling = state.fileManagerState.isInstallingRokidLink,
            rokidLinkRunning = state.fileManagerState.rokidLinkRunning,
            onInstallRokidLink = actions.onFileManagerInstallRokidLink,
            onOpenRokidLink = actions.onFileManagerOpenRokidLink,
            onStopRokidLink = actions.onFileManagerStop,
            onLaunchAppViaSdk = actions.onLaunchGlassAppViaSdk,
            onSendKeyButtonConfig = actions.onSendKeyButtonConfig,
        )
    }
    
    // cleanup connection when leaving ADB module
    DisposableEffect(Unit) {
        onDispose { disconnectClient() }
    }
}
