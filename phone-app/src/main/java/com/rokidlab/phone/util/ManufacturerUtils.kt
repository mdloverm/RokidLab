package com.rokidlab.phone.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log

/**
 * 国产手机厂商 ROM 检测与权限引导工具类
 *
 * 各厂商 ROM 在权限管理、后台运行、自启动等方面存在显著差异，
 * 此工具类提供统一的检测和引导跳转能力。
 */
object ManufacturerUtils {
    private const val TAG = "ManufacturerUtils"

    /** 厂商枚举 */
    enum class Manufacturer(val displayName: String, val packageNameHint: String) {
        XIAOMI("Xiaomi", "com.miui.securitycenter"),
        HUAWEI("Huawei", "com.huawei.systemmanager"),
        HONOR("Honor", "com.hihonor.systemmanager"),
        OPPO("OPPO", "com.coloros.safecenter"),
        VIVO("vivo", "com.iqoo.powersave"),
        ONEPLUS("OnePlus", "com.oneplus.security"),
        MEIZU("Meizu", "com.meizu.safe"),
        SAMSUNG("Samsung", "com.samsung.android.lool"),
        GOOGLE("Google", ""),
        OTHER("Other", ""),
    }

    /** 检测当前设备厂商 */
    fun detect(): Manufacturer {
        val manufacturer = Build.MANUFACTURER?.lowercase() ?: ""
        val brand = Build.BRAND?.lowercase() ?: ""
        val fingerprint = Build.FINGERPRINT?.lowercase() ?: ""
        val model = Build.MODEL?.lowercase() ?: ""

        return when {
            manufacturer.contains("xiaomi") || brand.contains("xiaomi") || brand.contains("redmi") || brand.contains("poco") ->
                Manufacturer.XIAOMI
            manufacturer.contains("huawei") || brand.contains("huawei") || fingerprint.contains("huawei") ->
                Manufacturer.HUAWEI
            manufacturer.contains("honor") || brand.contains("honor") ->
                Manufacturer.HONOR
            manufacturer.contains("oppo") || brand.contains("oppo") || brand.contains("realme") || brand.contains("oneplus") ->
                Manufacturer.OPPO
            manufacturer.contains("vivo") || brand.contains("vivo") || brand.contains("iqoo") ->
                Manufacturer.VIVO
            manufacturer.contains("meizu") -> Manufacturer.MEIZU
            manufacturer.contains("samsung") -> Manufacturer.SAMSUNG
            manufacturer.contains("google") -> Manufacturer.GOOGLE
            else -> Manufacturer.OTHER
        }
    }

    /** 是否为国产深度定制 ROM（需要额外权限引导） */
    fun isChineseRom(): Boolean = when (detect()) {
        Manufacturer.XIAOMI, Manufacturer.HUAWEI, Manufacturer.OPPO,
        Manufacturer.VIVO, Manufacturer.HONOR, Manufacturer.MEIZU -> true
        else -> false
    }

    // ── 电池优化白名单 ──

    /**
     * 请求忽略电池优化（加入白名单）
     * 解决华为/小米等后台保活问题
     */
    fun requestIgnoreBatteryOptimizations(context: Context) {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
            .onFailure { Log.w(TAG, "Cannot open battery optimization settings: ${it.message}") }
    }

    /** 是否已在电池优化白名单中 */
    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.packageManager
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val powerManager = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
                powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: false
            } else false
        } catch (e: Exception) {
            Log.w(TAG, "isIgnoringBatteryOptimizations check failed: ${e.message}")
            false
        }
    }

    // ── 厂商差异化的设置页跳转 ──

    /**
     * 打开厂商 ROM 的自启动权限设置页
     * 引导用户授权自启动，确保广播接收器和后台服务正常工作
     */
    fun openAutoStartSettings(context: Context): Boolean {
        val intent = getAutoStartIntent(context) ?: return false
        return runCatching {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        }.onFailure { Log.w(TAG, "openAutoStartSettings failed: ${it.message}") }.getOrDefault(false)
    }

    private fun getAutoStartIntent(context: Context): Intent? {
        return when (detect()) {
            Manufacturer.XIAOMI -> {
                // MIUI 自启动管理
                Intent("miui.intent.action.APP_PERM_EDITOR").apply {
                    putExtra("extra_pkgname", context.packageName)
                }
            }
            Manufacturer.HUAWEI -> {
                // EMUI 启动管理
                Intent().apply {
                    action = "huawei.intent.action.HSM_PROTECTED_APPS"
                    putExtra("pkg_name", context.packageName)
                }
            }
            Manufacturer.HONOR -> {
                Intent().apply {
                    action = "huawei.intent.action.HSM_PROTECTED_APPS"
                    putExtra("pkg_name", context.packageName)
                }
            }
            Manufacturer.OPPO -> {
                // ColorOS 自启动
                Intent("com.coloros.safecenter.action.SAFECENTER")
            }
            Manufacturer.VIVO -> {
                // OriginOS 自启动
                Intent("com.iqoo.powersave.ui.PowerSaveActivity")
            }
            Manufacturer.MEIZU -> {
                Intent("com.meizu.safe.action.SAFE_CENTER")
            }
            else -> null
        }
    }

    /**
     * 打开厂商 ROM 的省电策略/后台高耗电设置页
     * 解决华为/小米等后台服务被强杀问题
     */
    fun openPowerSavingSettings(context: Context): Boolean {
        val intent = getPowerSavingIntent(context) ?: return false
        return runCatching {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            true
        }.onFailure { Log.w(TAG, "openPowerSavingSettings failed: ${it.message}") }.getOrDefault(false)
    }

    private fun getPowerSavingIntent(context: Context): Intent? {
        return when (detect()) {
            Manufacturer.XIAOMI -> {
                Intent("miui.intent.action.APP_PERM_EDITOR").apply {
                    putExtra("extra_pkgname", context.packageName)
                }
            }
            Manufacturer.HUAWEI -> {
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS.let {
                    Intent(it).apply { data = Uri.parse("package:${context.packageName}") }
                }
            }
            Manufacturer.OPPO -> {
                Intent("com.coloros.safecenter.action.SAFECENTER")
            }
            Manufacturer.VIVO -> {
                Intent("com.iqoo.powersave.ui.PowerSaveActivity")
            }
            else -> null
        }
    }

    // ── 悬浮窗权限 ──

    /** 是否可以绘制悬浮窗（用于 OPPO/vivo 投屏兼容） */
    fun canDrawOverlays(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else true
    }

    /** 打开悬浮窗权限设置页 */
    fun openOverlaySettings(context: Context) {
        val intent = getOverlaySettingsIntent(context) ?: Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
            .onFailure {
                Log.w(TAG, "Cannot open overlay settings: ${it.message}")
                // fallback to standard intent
                val fallback = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                runCatching { context.startActivity(fallback) }
            }
    }

    /** 获取厂商特定的悬浮窗权限设置页 Intent */
    fun getOverlaySettingsIntent(context: Context): Intent? {
        return when (detect()) {
            Manufacturer.OPPO -> {
                // ColorOS 悬浮窗管理列表页
                Intent("com.coloros.safecenter.action.SAFECENTER").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            Manufacturer.VIVO -> {
                // OriginOS 悬浮窗管理
                Intent("com.iqoo.powersave.ui.PowerSaveActivity").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            Manufacturer.XIAOMI -> {
                // MIUI 应用权限管理
                Intent("miui.intent.action.APP_PERM_EDITOR").apply {
                    putExtra("extra_pkgname", context.packageName)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            else -> null
        }
    }

    // ── 通知权限（厂商特殊处理） ──

    /**
     * 打开厂商 ROM 的通知权限设置页
     * 部分 ROM（如 MIUI）中标准 POST_NOTIFICATIONS 弹窗可能被静默拒绝
     */
    fun openNotificationSettings(context: Context) {
        when (detect()) {
            Manufacturer.XIAOMI -> {
                // MIUI: 跳转应用详情 → 通知管理
                val intent = Intent("miui.intent.action.APP_PERM_EDITOR").apply {
                    putExtra("extra_pkgname", context.packageName)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                runCatching { context.startActivity(intent) }
                    .onFailure { fallbackAppSettings(context) }
            }
            else -> {
                // 其他 ROM: 跳转系统设置 → 通知
                fallbackAppSettings(context)
            }
        }
    }

    private fun fallbackAppSettings(context: Context) {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
    }

    /** 获取厂商显示名称（用于 UI 展示） */
    fun getManufacturerDisplayName(): String = detect().displayName

    /** 当前厂商是否有已知的后台限制问题 */
    fun hasBackgroundRestrictionIssue(): Boolean = isChineseRom()

    // ── 蓝牙栈检测（QTI vs AOSP） ──

    /**
     * 检测是否使用 Qualcomm (QTI) 蓝牙栈。
     * QTI 栈在 HID sendReport 行为上与 AOSP 栈有显著差异：
     * - 部分设备上 sendReport 返回 true 但数据未实际发送
     * - 需要额外延迟和 setReport() 回退
     *
     * 已知使用 QTI 蓝牙栈的厂商：vivo, OnePlus, 部分小米/OPPO
     */
    fun isQtiBluetoothStack(): Boolean {
        val prop = getSystemProperty("bluetooth.host.stacks")
        val vendor = getSystemProperty("vendor.bluetooth.host.stacks")
        val qtiIndicators = listOf("qti", "qualcomm", "qcom")
        return when {
            prop?.lowercase()?.let { s -> qtiIndicators.any { s.contains(it) } } == true -> true
            vendor?.lowercase()?.let { s -> qtiIndicators.any { s.contains(it) } } == true -> true
            else -> false
        }
    }

    /**
     * 是否为 VIVO 设备（含 iQOO）
     * VIVO 手机普遍使用 QTI 蓝牙栈，需要特殊 HID 兼容处理
     */
    fun isVivoOrIqoo(): Boolean = detect() == Manufacturer.VIVO

    /**
     * 是否需要 QTI 蓝牙 HID 兼容处理
     * VIVO/iQOO、部分 OnePlus 设备需要特殊处理
     */
    fun needsQtiHidWorkaround(): Boolean {
        if (isVivoOrIqoo()) return true
        return isQtiBluetoothStack()
    }

    private fun getSystemProperty(name: String): String? {
        return try {
            val clazz = Class.forName("android.os.SystemProperties")
            val method = clazz.getMethod("get", String::class.java)
            method.invoke(null, name) as? String
        } catch (e: Exception) {
            null
        }
    }

    // ── MediaProjection 投屏兼容性检测 ──

    /**
     * 检测当前设备是否属于已知 MediaProjection 投屏黑屏机型。
     * 这些设备的 GPU/HWC 实现存在兼容性问题，投屏时容易出现黑屏。
     *
     * 已知问题机型：
     * - 华为 EMUI 12+：GPU 驱动屏蔽非系统级屏幕捕获
     * - 小米 MIUI 14+：HWC 策略阻止 VSync 同步
     * - OPPO ColorOS 13+：安全沙箱拦截 MediaProjection
     * - vivo Funtouch OS 13+：强制启用私有编码器
     */
    fun isMediaProjectionBlacklisted(): Boolean {
        val model = Build.MODEL?.lowercase() ?: ""
        val manufacturer = detect()
        val sdk = Build.VERSION.SDK_INT

        // 按品牌 + Android 版本匹配
        return when (manufacturer) {
            Manufacturer.HUAWEI -> sdk >= 31 // EMUI 12+ (Android 12+)
            Manufacturer.XIAOMI -> {
                // Redmi K 系列和小米旗舰在 MIUI 14+ 上问题多发
                sdk >= 33 || model.contains("redmi k") || model.contains("xiaomi 13") || model.contains("xiaomi 14")
            }
            Manufacturer.OPPO -> sdk >= 33 // ColorOS 13+
            Manufacturer.VIVO -> sdk >= 33 // Funtouch OS 13+
            else -> false
        }
    }

    /**
     * 是否需要强制软件渲染来避免 MediaProjection 黑屏。
     * 比 isMediaProjectionBlacklisted() 更保守，仅覆盖确认需要
     * 关闭 HWC 才能正常投屏的机型。
     */
    fun needsForceSoftwareRenderer(): Boolean {
        val manufacturer = detect()
        val model = Build.MODEL?.lowercase() ?: ""
        val sdk = Build.VERSION.SDK_INT

        if (manufacturer == Manufacturer.HUAWEI && sdk >= 31) return true
        if (manufacturer == Manufacturer.XIAOMI && sdk >= 33) return true
        if (model.contains("redmi k") && sdk >= 31) return true
        if (manufacturer == Manufacturer.OPPO && sdk >= 34) return true

        return false
    }

    /**
     * 获取适用于当前设备的 HWC 禁用属性设置值（用于 adb shell setprop）。
     * 华为和小米机型在投屏黑屏时，设置这些系统属性可强制绕过 HWC 限制。
     *
     * @return 属性设置命令列表，可在运行时通过 adb shell 或 Process.exec 执行
     */
    fun getHwcDisableProps(): List<Pair<String, String>> {
        return when (detect()) {
            Manufacturer.HUAWEI -> listOf(
                "debug.sf.enable_hwc_vds" to "0",
            )
            Manufacturer.XIAOMI -> listOf(
                "debug.sf.enable_hwc_vds" to "0",
                "debug.sf.hw" to "0",
            )
            Manufacturer.OPPO -> listOf(
                "debug.sf.enable_hwc_vds" to "0",
            )
            else -> emptyList()
        }
    }

    /**
     * 是否需要降低投屏分辨率以避免黑屏或帧率过低。
     * 部分联发科/麒麟芯片设备在 480p 以上投屏时有兼容性问题。
     */
    fun needsReducedMirrorResolution(): Boolean {
        val soc = getSystemProperty("ro.board.platform")?.lowercase() ?: ""
        val hardware = getSystemProperty("ro.hardware")?.lowercase() ?: ""

        // 检测联发科/麒麟等可能有问题的芯片组
        val problematicSocs = listOf("mt6", "mt7", "mt8", "kirin", "hisilicon")
        val isProblematicSoc = problematicSocs.any { soc.contains(it) || hardware.contains(it) }

        if (isProblematicSoc) return true

        // 低端设备降低分辨率
        val totalRam = getSystemProperty("ro.config.low_ram")
        if (totalRam == "true") return true

        return false
    }

    /**
     * 获取推荐的投屏编码器。
     * 部分设备使用默认编码器时可能出现帧率偏低或兼容性问题，
     * 回退到软件编码器(OMX.google.h264.encoder)可提高兼容性。
     *
     * @return 推荐编码器名称，null 表示使用系统默认
     */
    fun getRecommendedEncoder(): String? {
        val manufacturer = detect()
        val soc = getSystemProperty("ro.board.platform")?.lowercase() ?: ""

        // 联发科设备部分硬编实现有问题，推荐使用 Google 软编
        if (soc.contains("mt")) return "OMX.google.h264.encoder"

        // OPPO/vivo 部分机型强制启用私有编码器，回退到软编
        if ((manufacturer == Manufacturer.OPPO || manufacturer == Manufacturer.VIVO)
            && Build.VERSION.SDK_INT >= 33) {
            return "OMX.google.h264.encoder"
        }

        return null
    }

    /**
     * 获取当前设备的蓝牙 HOGP (HID over GATT Profile) 支持状态说明。
     * 国产设备（小米/OPPO/Realme）默认禁用 HOGP 以省电，
     * 需要用户手动在「开发者选项」中开启 "Bluetooth HID Host"。
     *
     * @return 是否需要用户手动启用 HOGP
     */
    fun needsHogpManualEnablement(): Boolean {
        if (!isChineseRom()) return false
        // 检测是否已启用（通过查看蓝牙属性）
        val btHogp = getSystemProperty("persist.bluetooth.hogp.enabled")
        if (btHogp == "true") return false

        val manufacturer = detect()
        return manufacturer == Manufacturer.XIAOMI ||
               manufacturer == Manufacturer.OPPO
    }

    /**
     * 获取引导用户开启 HOGP 的说明文本（用于 UI 展示）。
     */
    fun getHogpGuideText(): String {
        return when (detect()) {
            Manufacturer.XIAOMI -> "请在「设置 → 开发者选项」中找到并开启「蓝牙 HID Host」，否则游戏手柄可能无法正常工作。"
            Manufacturer.OPPO -> "请在「设置 → 开发者选项」中找到并开启「蓝牙 HID Host」，否则游戏手柄可能无法正常工作。"
            else -> "请在「设置 → 开发者选项」中确认已开启蓝牙 HID 相关选项。"
        }
    }

    /**
     * 检测当前设备是否存在 vivo/iQOO 的 10 分钟后台服务硬限制。
     * vivo Funtouch OS / OriginOS 对非白名单应用施加后台存活硬限制，
     * 投屏等长时间后台操作需要用户将应用加入受保护应用列表。
     */
    fun hasVivoBackgroundHardLimit(): Boolean = detect() == Manufacturer.VIVO

    /**
     * 检测当前设备是否存在三星的 Deep Sleep 自动降权问题。
     * 三星 One UI Android 12+ 上，前台服务若无用户交互超 15 分钟，
     * 自动降权为后台，可能导致投屏中断。
     */
    fun hasSamsungDeepSleepIssue(): Boolean =
        detect() == Manufacturer.SAMSUNG && Build.VERSION.SDK_INT >= 31

    /**
     * 检测是否应在应用启动时弹出兼容性设置引导。
     * 仅对已知有后台限制/投屏问题的国产 ROM 首次启动时显示。
     */
    fun shouldShowCompatibilityGuide(): Boolean {
        if (!isChineseRom()) return false
        // 仅对 Android 10+ 设备显示（投屏功能主要在这些版本上使用）
        if (Build.VERSION.SDK_INT < 29) return false
        return true
    }

    /**
     * 获取当前设备所有兼容性问题标签，用于 UI 展示。
     */
    fun getCompatibilityIssues(): List<String> {
        val issues = mutableListOf<String>()
        if (needsForceSoftwareRenderer()) issues.add("media_projection_blacklist")
        if (hasBackgroundRestrictionIssue()) issues.add("background_kill")
        if (needsQtiHidWorkaround()) issues.add("qti_bt_stack")
        if (needsHogpManualEnablement()) issues.add("hogp_disabled")
        if (hasVivoBackgroundHardLimit()) issues.add("vivo_10min_limit")
        if (hasSamsungDeepSleepIssue()) issues.add("samsung_deep_sleep")
        return issues
    }
}
