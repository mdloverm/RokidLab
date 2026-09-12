package com.rokidlab.phone.store

import com.rokidlab.phone.R
import com.rokidlab.phone.design.*
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ===== 模块通用组件 =====
@Composable
internal fun ModuleHeader(title: String, subtitle: String, color: Color) {
    Column {
        Text(text = title, color = color, fontSize = 32.sp, fontWeight = FontWeight.Black, letterSpacing = 4.sp)
        Spacer(modifier = Modifier.height(8.dp))
        Text(text = subtitle, color = BrewMuted, fontSize = 14.sp)
    }
}

@Composable
internal fun IpAddressInputCard(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    color: Color = BrewCyan,
) {
    var text by remember { mutableStateOf(value) }
    // 外部 value 变化（如眼镜端自动推送的新 IP）时同步到本地 text，
    // 否则 remember 只缓存初始值，导致眼镜推送的 IP 填不进已打开的输入框。
    LaunchedEffect(value) {
        if (text != value) text = value
    }

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

// ===== Neo Brutalist 组件 =====

@Composable
internal fun RokidLinkStatusCard(installed: Boolean, installing: Boolean, running: Boolean = false, onInstall: () -> Unit, onOpen: () -> Unit, onStop: (() -> Unit)? = null, moduleColor: Color = BrewCoral, ctx: android.content.Context) {
    // 状态颜色：已安装/运行中 → 各自导航栏色，未安装/安装中 → 商店色
    val statusColor = when {
        running -> moduleColor
        installing -> BrewCoral
        installed -> moduleColor
        else -> BrewCoral
    }
    val statusBg = statusColor
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
    
    // 安装中脉冲动画 — 偏移装饰线呼吸效果
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
                        .border(width = 1.dp, color = BrewCoral.copy(alpha = 0.3f), shape = BrewShapeStandard),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = "⟳", color = BrewCoral, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 12.dp))
                        Text(text = ctx.getString(R.string.installing), color = BrewCoral, fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
                    }
                }
            } else if (running) {
                // 停止按钮 → 商店色
                BrutalButton(
                    label = ctx.getString(R.string.stop_rokid_link),
                    color = BrewCoral,
                    onClick = onStop ?: {},
                )
            } else if (!installed) {
                // 安装按钮 → 商店色
                BrutalButton(
                    label = ctx.getString(R.string.install_rokid_link),
                    color = BrewCoral,
                    onClick = onInstall,
                )
            } else {
                // 启动按钮 → 各自导航栏色
                BrutalButton(
                    label = ctx.getString(R.string.launch_rokid_link),
                    color = moduleColor,
                    onClick = onOpen,
                )
            }
        }
    }
}

@Composable
internal fun BrutalButton(
    label: String, 
    color: Color, 
    onClick: () -> Unit, 
    compact: Boolean = false,
    enabled: Boolean = true
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.97f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "btn-press",
    )
    val height = if (compact) 32.dp else 52.dp
    val btnShape = if (compact) BrewShapeMedium else BrewShapeStandard
    
    val effectiveColor = if (enabled) color else BrewMuted
    val effectiveAlpha = if (enabled) {
        if (isPressed) 0.20f else 0.12f
    } else {
        0.08f
    }
    val effectiveBorderAlpha = if (enabled) {
        if (isPressed) 0.7f else 0.5f
    } else {
        0.3f
    }
    
    Box(
        modifier = Modifier
            .then(if (compact) Modifier.wrapContentWidth() else Modifier.fillMaxWidth())
            .height(height)
            .graphicsLayer { scaleX = pressScale; scaleY = pressScale }
            .clip(btnShape)
            .background(effectiveColor.copy(alpha = effectiveAlpha))
            .border(width = 1.dp, color = effectiveColor.copy(alpha = effectiveBorderAlpha), shape = btnShape)
            .then(if (enabled) {
                Modifier.clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            } else {
                Modifier
            })
            .padding(horizontal = if (compact) 12.dp else 0.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = effectiveColor,
            fontSize = if (compact) 12.sp else 14.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = if (compact) 0.sp else 1.sp,
        )
    }
}

