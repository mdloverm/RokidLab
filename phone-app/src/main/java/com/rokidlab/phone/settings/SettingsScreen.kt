package com.rokidlab.phone.settings

import com.rokidlab.phone.design.*
import com.rokidlab.phone.store.*
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun SettingsScreen(
    state: StoreUiState,
    actions: StoreActions,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        ModuleHeader(title = "设置", subtitle = "应用配置", color = BrewMagenta)
        Spacer(modifier = Modifier.height(24.dp))

        SettingCard(title = "应用版本", content = state.selfUpdateState.currentVersion, color = BrewGreen)
        Spacer(modifier = Modifier.height(16.dp))
        SettingCard(
            title = "主机应用",
            content = state.selectedHostApp.displayName,
            color = BrewCyan,
            onClick = { actions.onGoToGuideStep1() },
        )
        Spacer(modifier = Modifier.height(16.dp))

        if (state.selfUpdateState.available) {
            BrutalButton(label = "有更新可用", color = BrewGreen, onClick = actions.onSelfUpdate)
        } else {
            SettingCard(title = "更新状态", content = "暂无更新", color = BrewMuted)
        }
        Spacer(modifier = Modifier.height(16.dp))
        BrutalButton(label = "切换商店源", color = BrewCyan, onClick = actions.onSwitchMirror)
        Spacer(modifier = Modifier.height(24.dp))

        // ── 眼镜端服务 ──
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
        ) {
            Text(text = "眼镜端服务", color = BrewMagenta.copy(alpha = 0.8f), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
            Spacer(modifier = Modifier.height(12.dp))
            SettingCard(
                title = "ScreenStream",
                content = if (state.screenMirrorState.screenStreamInstalled == true) "已安装" else "未安装",
                color = if (state.screenMirrorState.screenStreamInstalled == true) BrewGreen else BrewWarning,
            )
            Spacer(modifier = Modifier.height(12.dp))
            BrutalButton(
                label = "重装眼镜端",
                color = BrewWarning,
                onClick = actions.onSettingsReinstallScreenStream,
            )
            if (state.screenMirrorState.isInstallingScreenStream) {
                Spacer(modifier = Modifier.height(8.dp))
                Text("正在安装中...", color = BrewCyan, fontSize = 12.sp)
            }
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
                Text(text = "开发者", color = BrewDim, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
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
private fun BrutalButton(label: String, color: Color, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.98f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "btn-press",
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .graphicsLayer { scaleX = pressScale; scaleY = pressScale; alpha = if (isPressed) 0.85f else 1f }
            .clip(RoundedCornerShape(12.dp))
            .background(color.copy(alpha = 0.12f))
            .border(width = 1.dp, color = color.copy(alpha = 0.5f), shape = RoundedCornerShape(12.dp))
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = color,
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
