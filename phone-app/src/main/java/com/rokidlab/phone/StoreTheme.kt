package com.rokidlab.phone

import androidx.compose.foundation.background
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density

internal const val NEW_CATEGORY = "New"

@Composable
internal fun RokidLabTheme(content: @Composable () -> Unit) {
    val density = LocalDensity.current
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = BrewBg,
            surface = BrewPanel,
            primary = BrewCoral,
            onPrimary = BrewBg,
            onSurface = BrewText,
        ),
        content = {
            CompositionLocalProvider(
                LocalDensity provides Density(
                    density = density.density,
                    fontScale = density.fontScale.coerceAtMost(1.0f),
                ),
            ) {
                Surface(color = BrewBg, content = content)
            }
        },
    )
}
internal val BrewFont = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(R.font.jetbrains_mono_medium, FontWeight.Medium),
    Font(R.font.jetbrains_mono_bold, FontWeight.Bold),
)

// ═══════════════════════════════════════════════════
// 配色方案：60-30-9-1 法则
// ═══════════════════════════════════════════════════

// 60% — 深邃午夜蓝（背景色系，奠定沉稳高级基调）
internal val BrewBg = Color(0xFF0A1420)
internal val BrewPanel = Color(0xFF101D2D)
internal val BrewPanelAlt = Color(0xFF162536)
internal val BrewPanelHi = Color(0xFF1C3048)

// 30% — 柔和燕麦白（文字/内容色系，提供舒适视觉呼吸空间）
internal val BrewTextBright = Color(0xFFF5F0E8)
internal val BrewText = Color(0xFFE8E0D3)
internal val BrewMuted = Color(0xFFB5AD9E)
internal val BrewDim = Color(0xFF7A7366)

// 9% — 跃动珊瑚橘（强调/引导色，激发用户操作行为）
internal val BrewCoral = Color(0xFFFF6B5B)
internal val BrewCoralDim = Color(0xFFD95A4C)

// 1% — 一抹薄荷绿（点缀色，关键位置提供视觉惊喜与活力）
internal val BrewMint = Color(0xFF7BECB8)

// 边框
internal val BrewBorder = Color(0xFF1E3048)
internal val BrewBorderHi = Color(0xFF2A4260)

// ═══════════════════════════════════════════════════
// 语义别名（保持旧名称兼容）
// ═══════════════════════════════════════════════════
internal val BrewGreen = BrewMint       // 成功/积极操作 → 薄荷绿
internal val BrewCyan = BrewCoral       // 模块强调 → 珊瑚橘
internal val BrewPurple = BrewCoral     // 模块强调 → 珊瑚橘
internal val BrewAmber = BrewCoral      // 提示 → 珊瑚橘
internal val BrewRed = Color(0xFFFF4444) // 错误/危险
internal val BrewGreenDim = BrewCoralDim  // 次要强调
internal val BrewMagenta = BrewCoral     // 高亮 → 珊瑚橘

// 状态色
internal val BrewSuccess = BrewMint      // 成功 → 薄荷绿
internal val BrewError = Color(0xFFFF4444)
internal val BrewWarning = Color(0xFFFFB347)
internal val BrewInfo = Color(0xFF64B5F6)
internal val BrewOrange = Color(0xFFFFB347)  // 橙色警告
