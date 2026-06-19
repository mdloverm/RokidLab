package com.rokidlab.phone.settings

import com.rokidlab.phone.design.*
import com.rokidlab.phone.store.*
import com.rokidlab.phone.util.LocalizationManager
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import com.rokidlab.phone.R

@Composable
internal fun SettingsScreen(
    state: StoreUiState,
    actions: StoreActions,
) {
    val ctx = LocalContext.current
    var showLangDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        ModuleHeader(title = ctx.getString(R.string.nav_settings), subtitle = ctx.getString(R.string.settings_subtitle), color = BrewMagenta)
        Spacer(modifier = Modifier.height(24.dp))

        SettingCard(title = ctx.getString(R.string.app_version), content = state.selfUpdateState.currentVersion, color = BrewGreen)
        Spacer(modifier = Modifier.height(16.dp))
        SettingCard(
            title = ctx.getString(R.string.host_app),
            content = state.selectedHostApp.displayName,
            color = BrewCyan,
            onClick = { actions.onGoToGuideStep1() },
        )
        Spacer(modifier = Modifier.height(16.dp))

        // Language selector
        val currentLangName = LocalizationManager.AppLocale.entries
            .find { it.code == state.currentLocale }
            ?.let { "${it.displayName}" } ?: ctx.getString(R.string.language_simple_chinese)
        SettingCard(
            title = ctx.getString(R.string.language),
            content = currentLangName,
            color = BrewPurple,
            onClick = { showLangDialog = true },
        )
        Spacer(modifier = Modifier.height(16.dp))

        if (state.selfUpdateState.available) {
            BrutalButton(label = ctx.getString(R.string.update_available), color = BrewGreen, onClick = actions.onSelfUpdate)
        } else {
            SettingCard(title = ctx.getString(R.string.update_status), content = ctx.getString(R.string.no_update), color = BrewMuted)
        }
        Spacer(modifier = Modifier.height(16.dp))
        BrutalButton(label = ctx.getString(R.string.switch_source), color = BrewCyan, onClick = actions.onSwitchMirror)
        Spacer(modifier = Modifier.height(24.dp))

        // ── 眼镜端服务 ──
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
        ) {
            Text(text = ctx.getString(R.string.glasses_services), color = BrewMagenta.copy(alpha = 0.8f), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
            Spacer(modifier = Modifier.height(12.dp))
            SettingCard(
                title = "RokidLink",
                content = if (state.screenMirrorState.rokidLinkInstalled == true) ctx.getString(R.string.installed) else ctx.getString(R.string.not_installed),
                color = if (state.screenMirrorState.rokidLinkInstalled == true) BrewGreen else BrewWarning,
            )
            Spacer(modifier = Modifier.height(12.dp))
            BrutalButton(
                label = if (state.screenMirrorState.isInstallingRokidLink) ctx.getString(R.string.installing_rokid_link) else ctx.getString(R.string.reinstall_glasses),
                color = BrewWarning,
                enabled = !state.screenMirrorState.isInstallingRokidLink,
                onClick = actions.onSettingsReinstallRokidLink,
            )
        }
        Spacer(modifier = Modifier.height(24.dp))

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(BrewPanel, RoundedCornerShape(12.dp))
                .border(width = 1.dp, color = BrewBorder, shape = RoundedCornerShape(12.dp))
                .padding(16.dp),
        ) {
            Column {
                Text(text = ctx.getString(R.string.developer), color = BrewDim, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                Box(
                    modifier = Modifier
                        .width(32.dp)
                        .height(3.dp)
                        .background(BrewGreen)
                        .padding(bottom = 8.dp),
                )
                Text(text = "DLOVER", color = BrewGreen, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
        }
    }

    // Language selection dialog
    if (showLangDialog) {
        LanguageDialog(
            currentCode = state.currentLocale,
            onSelect = { code ->
                actions.onSwitchLanguage(code)
                showLangDialog = false
            },
            onDismiss = { showLangDialog = false },
        )
    }
}

@Composable
private fun LanguageDialog(
    currentCode: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    BrewDialog(onDismiss = onDismiss, title = ctx.getString(R.string.select_language), color = BrewPurple) {
        BrewDialogContent {
            LocalizationManager.AppLocale.entries.forEach { locale ->
                val selected = locale.code == currentCode
                BrewDialogSelectItem(
                    primaryText = locale.displayName,
                    secondaryText = locale.displayNameEnglish,
                    isSelected = selected,
                    onClick = { onSelect(locale.code) },
                    selectedColor = BrewPurple,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun ModuleHeader(title: String, subtitle: String, color: Color) {
    Column {
        Text(text = title, color = color, fontSize = 32.sp, fontWeight = FontWeight.Black, letterSpacing = 4.sp)
        Spacer(modifier = Modifier.height(8.dp))
        Text(text = subtitle, color = BrewMuted, fontSize = 14.sp)
    }
}

@Composable
private fun BrutalButton(label: String, color: Color, enabled: Boolean = true, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.97f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "btn-press",
    )
    val dimAlpha = if (enabled) 1f else 0.45f
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .graphicsLayer { scaleX = pressScale; scaleY = pressScale; alpha = dimAlpha }
            .clip(BrewShapeStandard)
            .background(color.copy(alpha = if (isPressed) 0.20f else if (enabled) 0.12f else 0.05f))
            .border(width = 1.dp, color = color.copy(alpha = if (enabled) 0.5f else 0.15f), shape = BrewShapeStandard)
            .clickable(interactionSource = interactionSource, indication = null, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = color.copy(alpha = dimAlpha),
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp,
        )
    }
}

@Composable
private fun SettingCard(title: String, content: String, color: Color, onClick: (() -> Unit)? = null) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(BrewPanel, RoundedCornerShape(12.dp))
            .border(width = 1.dp, color = BrewBorder, shape = RoundedCornerShape(12.dp))
            .padding(16.dp)
            .clickable(enabled = onClick != null) { onClick?.invoke() },
    ) {
        Column {
            Text(
                text = title.uppercase(),
                color = BrewMuted,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 3.sp,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            Box(
                modifier = Modifier
                    .width(32.dp)
                    .height(3.dp)
                    .background(color)
                    .padding(bottom = 8.dp),
            )
            Text(text = content, color = color, fontSize = 18.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, style = TabularNumbersStyle)
        }
    }
}
