package com.rokidlab.phone.store

import com.rokidlab.phone.R
import com.rokidlab.phone.app.LabApplication
import com.rokidlab.phone.design.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ===== 手机投屏模块 =====
@Composable
internal fun PhoneMirrorModule(
    state: StoreUiState,
    actions: StoreActions,
    app: LabApplication,
) {
    val ctx = LocalContext.current
    val isMirroring = state.phoneMirrorState.isMirroring
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        ModuleHeader(title = ctx.getString(R.string.phone_mirror_title), subtitle = ctx.getString(R.string.phone_mirror_subtitle), color = BrewPurple)
        Spacer(modifier = Modifier.height(24.dp))
        
        RokidLinkStatusCard(
            installed = state.phoneMirrorState.rokidLinkInstalled == true,
            installing = state.phoneMirrorState.isInstallingRokidLink,
            running = state.phoneMirrorState.rokidLinkRunning,
            onInstall = actions.onPhoneMirrorInstallRokidLink,
            onOpen = actions.onPhoneMirrorOpenRokidLink,
            onStop = actions.onPhoneMirrorStop,
            moduleColor = BrewPurple,
            ctx = ctx,
        )
        Spacer(modifier = Modifier.height(16.dp))
        
        IpAddressInputCard(
            label = ctx.getString(R.string.ip_address_label),
            value = app.phoneMirrorIp,
            onValueChange = actions.onPhoneMirrorIpChange,
            color = BrewPurple,
        )
        Spacer(modifier = Modifier.height(16.dp))
        
        if (isMirroring) {
            Text(
                state.phoneMirrorState.connectionStatus,
                color = BrewMuted,
                fontSize = 14.sp,
                modifier = Modifier.padding(bottom = 16.dp)
            )
            BrutalButton(
                label = "■ ${ctx.getString(R.string.stop_mirror)}",
                color = BrewRed,
                onClick = actions.onPhoneMirrorStart,
            )
        } else {
            BrutalButton(
                label = ctx.getString(R.string.start_cast),
                color = BrewPurple,
                onClick = actions.onPhoneMirrorStart,
            )
        }
        Spacer(modifier = Modifier.height(24.dp))
        
        UsageInstructionsCard(
            color = BrewPurple,
            instructions = listOf(
                "1. ${ctx.getString(R.string.connecting_adb_hint)}",
                "2. ${ctx.getString(R.string.ip_address_label)}${ctx.getString(R.string.rokidlink_ip_hint)}",
                "3. ${ctx.getString(R.string.start_cast)}",
                "4. ${ctx.getString(R.string.phone_mirror_subtitle)}",
            ),
            ctx = ctx,
        )
    }
}
