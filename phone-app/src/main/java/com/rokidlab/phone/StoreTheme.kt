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
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
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

/** 等宽数字样式：JetBrains Mono 的 tabular numbers 特性，确保数字列对齐 */
internal val TabularNumbersStyle = TextStyle(
    fontFamily = BrewFont,
    fontFeatureSettings = "tnum",
    platformStyle = PlatformTextStyle(includeFontPadding = false),
)

// ═══════════════════════════════════════════════════
// 配色方案：「Velvet Dark」丝绒暗调
// 灵感：画廊暗室 × 油画颜料 × 温暖暗色空间
// 关键：不用纯原色/纯黑白 — 每色都带温度和深度
// ═══════════════════════════════════════════════════

// ── 底色系统：丝绒暗调（温暖暗底，非纯黑）──
internal val BrewBg = Color(0xFF0B0B0E)         // 丝绒炭黑 — 微微偏暖
internal val BrewPanel = Color(0xFF151518)       // 暗灰板
internal val BrewPanelAlt = Color(0xFF1C1C21)    // 亮灰板
internal val BrewPanelHi = Color(0xFF24242A)     // 高亮面板

// ── 文字系统：暖白至冷灰（画廊标牌）──
internal val BrewTextBright = Color(0xFFF2EFEA)  // 暖羊皮白 — 正文
internal val BrewText = Color(0xFFD4D0CA)        // 沙石灰 — 次要文字
internal val BrewMuted = Color(0xFF8A8780)       // 风化石 — 辅助文字
internal val BrewDim = Color(0xFF5C5952)         // 深石色 — 禁/淡出

// ── 强调色：油画颜料走色（饱和度克制，明度有层次）──
internal val BrewCoral = Color(0xFFE85D3F)       // 朱砂红 — 温暖的强调
internal val BrewCoralDim = Color(0xFFC14A2F)    // 暗朱砂

// ── 边框：几乎融入背景 ──
internal val BrewBorder = Color(0xFF2C2C33)
internal val BrewBorderHi = Color(0xFF3F3F49)

// ── 内部兼容 ──
internal val BrewMint = BrewCoral

// ═══════════════════════════════════════════════════
// 五模块五色：取自油画色板
// ═══════════════════════════════════════════════════
internal val BrewGreen   = Color(0xFFE85D3F) // 商店 — 朱砂红（温暖主导）
internal val BrewCyan    = Color(0xFF5B8FB9) // 屏幕镜像 — 静谧蓝（冷调克制）
internal val BrewPurple  = Color(0xFFD4A85C) // 手机投屏 — 画廊金（暖而有质感）
internal val BrewAmber   = Color(0xFFA78BFA) // 文件管理 — 雾紫（柔和区分）
internal val BrewMagenta = Color(0xFF8A8780) // 设置 — 石灰色（最低调）

// ── 功能色 ──
internal val BrewRed     = Color(0xFFE85D3F) // 错误/停止 — 朱砂红
internal val BrewGreenDim = Color(0xFF3A8070) // 次要 — 暗青绿
internal val BrewSuccess = Color(0xFF4ADE80) // 成功 — 翡翠绿
internal val BrewError   = Color(0xFFE85D3F) // 错误
internal val BrewWarning = Color(0xFFF0A050) // 警告 — 暖琥珀
internal val BrewInfo    = Color(0xFF5B8FB9) // 信息 — 静谧蓝
internal val BrewOrange  = Color(0xFFF0A050) // 兼容别名
