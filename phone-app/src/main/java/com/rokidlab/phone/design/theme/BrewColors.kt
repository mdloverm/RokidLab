package com.rokidlab.phone.design.theme

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Brew 配色方案接口
 *
 * 新增主题只需实现此接口。
 */
interface BrewColors {
    // ═══════════════════════════════════════════════════
    // 底色系统
    // ═══════════════════════════════════════════════════
    val bg: Color                    // 主背景
    val panel: Color                 // 暗灰板
    val panelAlt: Color              // 亮灰板
    val panelHi: Color               // 高亮面板

    // ═══════════════════════════════════════════════════
    // 文字系统
    // ═══════════════════════════════════════════════════
    val textBright: Color            // 暖白 — 正文
    val text: Color                  // 沙石灰 — 次要文字
    val muted: Color                 // 风化石 — 辅助文字
    val dim: Color                   // 深石色 — 禁用/淡出

    // ═══════════════════════════════════════════════════
    // 边框
    // ═══════════════════════════════════════════════════
    val border: Color                // 边框

    // ═══════════════════════════════════════════════════
    // 五模块五色（固定用途）
    // ═══════════════════════════════════════════════════
    val store: Color                 // 商店
    val mirror: Color                // 屏幕镜像
    val projection: Color            // 手机投屏
    val fileManager: Color           // 文件管理
    val settings: Color              // 设置
}

/**
 * CompositionLocal：运行时可通过 Provider 切换主题
 */
val LocalBrewColors = compositionLocalOf<BrewColors> { VelvetDark }
