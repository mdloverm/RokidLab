package com.rokidlab.phone.design.theme

import androidx.compose.ui.graphics.Color

/**
 * 冰蓝（Cool Blue）— 第二套主题
 * 灵感：北极冰川 × 浅蓝天光 × 清新明亮
 * 配色逻辑：浅蓝底色为主，各模块用高饱和度撞色，形成"浅蓝基底 × 鲜艳点缀"
 *
 * 撞色方案：
 *   底色 → 浅蓝白
 *   商店 → 珊瑚红 (暖色撞冷底)
 *   镜像 → 翡翠绿
 *   投屏 → 明媚紫
 *   文件 → 琥珀金
 *   设置 → 钢灰蓝
 */
object CoolBlueColors : BrewColors {
    // ═══════════════════════════════════════════════════
    // 底色系统 — 浅蓝层次
    // ═══════════════════════════════════════════════════
    override val bg: Color = Color(0xFFF0F5FF)         // 天光白蓝 (主底色)
    override val panel: Color = Color(0xFFE6EEFA)       // 浅蓝灰板
    override val panelAlt: Color = Color(0xFFDCE5F5)    // 中蓝灰板
    override val panelHi: Color = Color(0xFFCCD8EE)     // 冰蓝高亮

    // ═══════════════════════════════════════════════════
    // 文字系统 — 深色文字 (浅底深字)
    // ═══════════════════════════════════════════════════
    override val textBright: Color = Color(0xFF1A2332)   // 深蓝黑 — 正文
    override val text: Color = Color(0xFF3D4F6A)         // 靛蓝灰 — 次要文字
    override val muted: Color = Color(0xFF6B7BA0)        // 雾蓝灰 — 辅助文字
    override val dim: Color = Color(0xFF9AABCA)          // 淡蓝灰 — 禁用文字

    // ═══════════════════════════════════════════════════
    // 边框
    // ═══════════════════════════════════════════════════
    override val border: Color = Color(0xFFC8D4E8)      // 浅蓝边框

    // ═══════════════════════════════════════════════════
    // 七模块七色 — 浅蓝底 × 撞色
    // ═══════════════════════════════════════════════════
    override val store: Color = Color(0xFFE85D3F)       // 商店 — 珊瑚红 (暖色撞浅蓝)
    override val mirror: Color = Color(0xFF00B894)       // 屏幕镜像 — 翡翠绿
    override val projection: Color = Color(0xFF6C5CE7)  // 手机投屏 — 明媚紫
    override val fileManager: Color = Color(0xFFF39C12) // 文件管理 — 琥珀金
    override val adbTools: Color = Color(0xFF0984E3)    // ADB工具 — 深海蓝
    override val hidGamepad: Color = Color(0xFFE17055)  // HID手柄 — 珊瑚橙
    override val settings: Color = Color(0xFF5A7BA0)    // 设置 — 钢灰蓝
}

// 冰蓝主题静态引用
val CoolBlue: CoolBlueColors = CoolBlueColors
