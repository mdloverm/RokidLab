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

// ===== 屏幕镜像模块 =====
@Composable
internal fun ScreenMirrorModule(
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
        ModuleHeader(title = ctx.getString(R.string.screen_mirror_title), subtitle = ctx.getString(R.string.screen_mirror_subtitle), color = BrewCyan)
        Spacer(modifier = Modifier.height(24.dp))
        
        RokidLinkStatusCard(
            installed = state.screenMirrorState.rokidLinkInstalled == true,
            installing = state.screenMirrorState.isInstallingRokidLink,
            running = state.screenMirrorState.rokidLinkRunning,
            onInstall = actions.onScreenMirrorInstallRokidLink,
            onOpen = actions.onScreenMirrorOpenRokidLink,
            onStop = actions.onScreenMirrorStop,
            moduleColor = BrewCyan,
            ctx = ctx,
        )
        Spacer(modifier = Modifier.height(16.dp))
        
        IpAddressInputCard(
            label = ctx.getString(R.string.ip_address_label),
            value = app.screenMirrorIp,
            onValueChange = actions.onScreenMirrorIpChange,
            color = BrewCyan,
        )
        Spacer(modifier = Modifier.height(16.dp))
        
        BrutalButton(
            label = ctx.getString(R.string.start_mirror),
            color = BrewCyan,
            onClick = actions.onScreenMirrorStart,
        )
        Spacer(modifier = Modifier.height(24.dp))
        
        UsageInstructionsCard(
            color = BrewCyan,
            instructions = listOf(
                "1. ${ctx.getString(R.string.connecting_adb_hint)}",
                "2. ${ctx.getString(R.string.usage_mirror_step2)}${ctx.getString(R.string.rokidlink_ip_hint)}",
                "3. ${ctx.getString(R.string.usage_mirror_step3)}",
                "4. ${ctx.getString(R.string.usage_mirror_step4)}",
            ),
            ctx = ctx,
        )
    }
}
