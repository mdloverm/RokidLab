package com.rokidlab.phone.store

import com.rokidlab.phone.R
import com.rokidlab.phone.app.LabApplication
import com.rokidlab.phone.design.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

// ===== 文件管理模块 =====
@Composable
internal fun FileManagerModule(
    state: StoreUiState,
    actions: StoreActions,
    app: LabApplication,
) {
    val ctx = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        ModuleHeader(title = ctx.getString(R.string.file_manager_title), subtitle = ctx.getString(R.string.file_manager_subtitle), color = BrewAmber)
        Spacer(modifier = Modifier.height(24.dp))
        
        RokidLinkStatusCard(
            installed = state.fileManagerState.rokidLinkInstalled == true,
            installing = state.fileManagerState.isInstallingRokidLink,
            running = state.fileManagerState.rokidLinkRunning,
            onInstall = actions.onFileManagerInstallRokidLink,
            onOpen = actions.onFileManagerOpenRokidLink,
            onStop = actions.onFileManagerStop,
            moduleColor = BrewAmber,
            ctx = ctx,
        )
        Spacer(modifier = Modifier.height(16.dp))
        
        IpAddressInputCard(
            label = ctx.getString(R.string.ip_address_label),
            value = app.fileManagerIp,
            onValueChange = actions.onFileManagerIpChange,
            color = BrewAmber,
        )
        Spacer(modifier = Modifier.height(16.dp))
        
        BrutalButton(
            label = ctx.getString(R.string.open_file_manager),
            color = BrewAmber,
            onClick = actions.onFileManagerConnect,
        )
        Spacer(modifier = Modifier.height(12.dp))
        
        BrutalButton(
            label = if (state.isInstallingLocalApk) {
                when {
                    state.localApkInstallProgress > 0 -> ctx.getString(R.string.installing_with_progress, state.localApkInstallProgress)
                    state.localApkInstallStatus.isNotEmpty() -> state.localApkInstallStatus
                    else -> ctx.getString(R.string.installing_apk)
                }
            } else {
                ctx.getString(R.string.install_local_apk)
            },
            color = if (state.isInstallingLocalApk) BrewCoral else BrewInfo,
            onClick = actions.onInstallApk,
            enabled = !state.isInstallingLocalApk
        )
        Spacer(modifier = Modifier.height(24.dp))
        
        UsageInstructionsCard(
            color = BrewAmber,
            instructions = listOf(
                "1. ${ctx.getString(R.string.connecting_adb_hint)}",
                "2. ${ctx.getString(R.string.usage_filemanager_step2)}",
                "3. ${ctx.getString(R.string.ip_address_label)}${ctx.getString(R.string.rokidlink_ip_hint)}",
                "4. ${ctx.getString(R.string.open_file_manager)}",
                "5. ${ctx.getString(R.string.file_manager_subtitle)}",
            ),
            ctx = ctx,
        )
    }
}
