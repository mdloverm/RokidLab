package com.rokidlab.phone.design

import android.util.Log
import android.view.View
import com.rokidlab.phone.app.*
import com.rokidlab.phone.adb.*
import com.rokidlab.phone.design.*
import com.rokidlab.phone.design.theme.BrewColors
import com.rokidlab.phone.design.theme.LocalBrewColors
import com.rokidlab.phone.design.theme.BrewThemeManager
import com.rokidlab.phone.filemanager.*
import com.rokidlab.phone.glasses.*
import com.rokidlab.phone.mirror.*
import com.rokidlab.phone.model.*
import com.rokidlab.phone.network.*
import com.rokidlab.phone.settings.*
import com.rokidlab.phone.store.*
import com.rokidlab.phone.util.*
import com.rokidlab.phone.R
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
internal const val NEW_CATEGORY = "New"

@Composable
internal fun RokidLabTheme(
    colors: BrewColors? = null,
    content: @Composable () -> Unit
) {
    val density = LocalDensity.current
    // 读取当前主题（BrewThemeManager 使用 mutableStateOf，自动触发重组）
    val currentTheme = BrewThemeManager.currentTheme
    // 获取当前配色
    val activeColors = colors ?: BrewThemeManager.currentColors
    Log.d("BrewThemeManager", "RokidLabTheme: theme=${currentTheme.name}, bg=${Integer.toHexString(activeColors.bg.hashCode())}")

    // 动态更新窗口系统栏颜色
    val context = LocalContext.current
    val window = (context as? android.app.Activity)?.window
    if (window != null) {
        window.statusBarColor = activeColors.bg.toArgb()
        window.navigationBarColor = activeColors.bg.toArgb()

        // 根据背景明暗决定状态栏图标颜色
        val luminance = activeColors.bg.red * 0.299f + activeColors.bg.green * 0.587f + activeColors.bg.blue * 0.114f
        val flags = window.decorView.systemUiVisibility
        window.decorView.systemUiVisibility = if (luminance > 0.5f) {
            flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        } else {
            flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
        }
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            background = activeColors.bg,
            surface = activeColors.panel,
            primary = activeColors.store,
            onPrimary = activeColors.bg,
            onSurface = activeColors.text,
        ),
        content = {
            CompositionLocalProvider(
                LocalBrewColors provides activeColors,
                LocalDensity provides Density(
                    density = density.density,
                    fontScale = density.fontScale.coerceAtMost(1.0f),
                ),
            ) {
                Box(modifier = Modifier.fillMaxSize().background(activeColors.bg)) {
                    content()
                }
            }
        },
    )
}
val BrewFont = FontFamily(
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
// 标准圆角系统（保留，不走主题）
// ═══════════════════════════════════════════════════
val BrewRadiusSmall = 4.dp
val BrewRadiusMedium = 8.dp
val BrewRadiusStandard = 12.dp
val BrewRadiusLarge = 16.dp
val BrewRadiusXLarge = 20.dp

val BrewShapeSmall = RoundedCornerShape(BrewRadiusSmall)
val BrewShapeMedium = RoundedCornerShape(BrewRadiusMedium)
val BrewShapeStandard = RoundedCornerShape(BrewRadiusStandard)
val BrewShapeLarge = RoundedCornerShape(BrewRadiusLarge)
val BrewShapeXLarge = RoundedCornerShape(BrewRadiusXLarge)

// ═══════════════════════════════════════════════════
// 全局颜色快捷访问（兼容旧代码）
// 从 BrewThemeManager.currentColors 读取，在 @Composable 中读取会自动重组
// ═══════════════════════════════════════════════════

// 底色
val BrewBg: Color get() = BrewThemeManager.currentColors.bg
val BrewPanel: Color get() = BrewThemeManager.currentColors.panel
val BrewPanelAlt: Color get() = BrewThemeManager.currentColors.panelAlt
val BrewPanelHi: Color get() = BrewThemeManager.currentColors.panelHi

// 文字
val BrewTextBright: Color get() = BrewThemeManager.currentColors.textBright
val BrewText: Color get() = BrewThemeManager.currentColors.text
val BrewMuted: Color get() = BrewThemeManager.currentColors.muted
val BrewDim: Color get() = BrewThemeManager.currentColors.dim

// 边框
val BrewBorder: Color get() = BrewThemeManager.currentColors.border

// 七模块七色（固定用途）
val BrewCoral: Color get() = BrewThemeManager.currentColors.store      // 商店
val BrewChat: Color get() = BrewThemeManager.currentColors.chat        // 乐奇聊天
val BrewCyan: Color get() = BrewThemeManager.currentColors.mirror      // 屏幕镜像
val BrewPurple: Color get() = BrewThemeManager.currentColors.projection // 手机投屏
val BrewAmber: Color get() = BrewThemeManager.currentColors.fileManager  // 文件管理
val BrewTeal: Color get() = BrewThemeManager.currentColors.adbTools     // ADB工具
val BrewPink: Color get() = BrewThemeManager.currentColors.hidGamepad   // HID手柄
val BrewMagenta: Color get() = BrewThemeManager.currentColors.settings  // 设置

// 功能色（映射到模块色，每套主题只需配 7 个模块色即可）
//   错误 → 商店色 (暖/醒目)
//   成功 → 镜像色 (冷/平静)
//   警告 → 文件管理色 (暖橙/注意)
//   信息 → 设置色 (中性/低调)
val BrewRed: Color get() = BrewThemeManager.currentColors.store         // 错误
val BrewSuccess: Color get() = BrewThemeManager.currentColors.mirror    // 成功
val BrewGreenDim: Color get() = BrewThemeManager.currentColors.panelHi  // 次要成功
val BrewWarning: Color get() = BrewThemeManager.currentColors.fileManager // 警告
val BrewInfo: Color get() = BrewThemeManager.currentColors.settings     // 信息
