package com.rokidlab.phone.design.theme

import androidx.compose.ui.graphics.Color

/**
 * 丝绒暗调（Velvet Dark）— 默认主题
 * 灵感：画廊暗室 × 油画颜料 × 温暖暗色空间
 * 特点：不用纯原色/纯黑白 — 每色都带温度和深度
 */
object VelvetDarkColors : BrewColors {
    // ═══════════════════════════════════════════════════
    // 底色系统
    // ═══════════════════════════════════════════════════
    override val bg: Color = Color(0xFF0B0B0E)         // 丝绒炭黑
    override val panel: Color = Color(0xFF151518)        // 暗灰板
    override val panelAlt: Color = Color(0xFF1C1C21)     // 亮灰板
    override val panelHi: Color = Color(0xFF24242A)      // 高亮面板

    // ═══════════════════════════════════════════════════
    // 文字系统
    // ═══════════════════════════════════════════════════
    override val textBright: Color = Color(0xFFF2EFEA)   // 暖羊皮白
    override val text: Color = Color(0xFFD4D0CA)        // 沙石灰
    override val muted: Color = Color(0xFF8A8780)       // 风化石
    override val dim: Color = Color(0xFF5C5952)         // 深石色

    // ═══════════════════════════════════════════════════
    // 边框
    // ═══════════════════════════════════════════════════
    override val border: Color = Color(0xFF2C2C33)

    // ═══════════════════════════════════════════════════
    // 七模块七色
    // ═══════════════════════════════════════════════════
    override val store: Color = Color(0xFFE85D3F)       // 商店 — 朱砂红
    override val chat: Color = Color(0xFF6EE7B7)        // 乐奇聊天 — 青翠绿
    override val mirror: Color = Color(0xFF5B8FB9)       // 屏幕镜像 — 静谧蓝
    override val projection: Color = Color(0xFFD4A85C)  // 手机投屏 — 画廊金
    override val fileManager: Color = Color(0xFFA78BFA) // 文件管理 — 雾紫
    override val adbTools: Color = Color(0xFF00CEC9)    // ADB工具 — 薄荷青
    override val hidGamepad: Color = Color(0xFFFD79A8)  // HID手柄 — 玫瑰粉
    override val settings: Color = Color(0xFF7D7A70)   // 设置 — 暖灰褐
}

// 默认静态引用（用于非 @Composable 上下文）
val VelvetDark: VelvetDarkColors = VelvetDarkColors
