package com.rokidlab.phone.util

import android.content.Context
import android.os.Build
import android.util.Log
import com.rokidlab.phone.BuildConfig
import com.rokidlab.phone.hid.BtHidCompat
import com.rokidlab.phone.mirror.MirrorCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ROM 指纹：读取厂商 ROM 的**真实版本号**，而不是靠 Build.MANUFACTURER 猜品牌。
 *
 * 为什么需要它：
 * 厂商同一个品牌内部的行为差异，远大于品牌之间的差异 —— MIUI 14 与 HyperOS 2 的自启动
 * 设置页完全不同，ColorOS 13 与 ColorOS 14 的悬浮窗管理 Activity 也不同。只看
 * Build.BRAND 做判断，等于把「小米 10 MIUI 12」和「小米 15 HyperOS 2」当成同一台设备。
 *
 * 设计约束：
 * - 全部通过反射读 SystemProperties，**任何一个属性读失败都返回 null，绝不抛异常**。
 *   部分 ROM 会把这些属性标记为 restricted，此时退化到 Build 字段即可。
 * - 本类的结果只用于：①设备兼容性签名（决定是否需要重新探测）②诊断报告展示。
 *   **降级档位本身不依赖这里的结果** —— 那是运行时探测的职责，见 MirrorCompat。
 *   这样即使 ROM 识别失败，整套降级机制依然完整可用。
 */
object RomFingerprint {
    private const val TAG = "RomFingerprint"

    /** ROM 家族 */
    enum class Rom(
        val displayName: String,
        /** 该 ROM 的版本属性名，读不到时为 null */
        val versionProp: String?,
    ) {
        MIUIHyperOS("MIUI/HyperOS", "ro.miui.ui.version.name"),
        COLOR_OS("ColorOS", "ro.build.version.opporom"),
        REALME_UI("realme UI", "ro.build.version.realmeui"),
        ORIGIN_OS("OriginOS/Funtouch", "ro.vivo.os.version"),
        EMUI("EMUI", "ro.build.version.emui"),
        HARMONY_OS("HarmonyOS", "ro.huawei.build.version"),
        MAGIC_OS("MagicOS", "ro.build.version.magic"),
        ONE_UI("One UI", "ro.build.version.oneui"),
        ZUI("ZUI", "ro.build.version.zui"),
        MY_OS("MyOS", "ro.build.version.nubiaui"),
        OXYGEN_OS("OxygenOS", "ro.build.version.oxygen_os"),
        AOSP("AOSP/Stock", null),
        UNKNOWN("Unknown", null),
    }

    private val brand: String get() = Build.BRAND?.lowercase().orEmpty()
    private val manufacturer: String get() = Build.MANUFACTURER?.lowercase().orEmpty()
    private val fingerprint: String get() = Build.FINGERPRINT?.lowercase().orEmpty()
    private val displayId: String get() = Build.DISPLAY?.lowercase().orEmpty()

    /** 反射读取系统属性；失败返回 null（统一走 L0 [RomAdapter]，保留原失败日志） */
    private fun getProp(name: String): String? {
        val r = com.rokidlab.phone.platform.RomAdapter.systemPropertyCapability(name)
        if (r is com.rokidlab.phone.platform.Capability.Unavailable) Log.w(TAG, "getProp($name): ${r.reason}")
        return (r as? com.rokidlab.phone.platform.Capability.Available)?.value?.takeIf { it.isNotBlank() }
    }

    /** 识别当前 ROM 家族 */
    fun detectRom(): Rom = when {
        brand.contains("xiaomi") || brand.contains("redmi") || brand.contains("poco") ||
            manufacturer.contains("xiaomi") -> Rom.MIUIHyperOS
        brand.contains("realme") -> Rom.REALME_UI
        // realme 的 brand 有时是 oppo，但 OPPO 自身不是 realme UI，需先排除
        brand.contains("oppo") || brand.contains("oneplus") -> Rom.COLOR_OS
        brand.contains("vivo") || brand.contains("iqoo") -> Rom.ORIGIN_OS
        brand.contains("honor") -> Rom.MAGIC_OS
        brand.contains("huawei") || manufacturer.contains("huawei") ||
            fingerprint.contains("huawei") || fingerprint.contains("harmony") -> Rom.EMUI
        brand.contains("samsung") -> Rom.ONE_UI
        brand.contains("lenovo") || brand.contains("zuk") -> Rom.ZUI
        brand.contains("nubia") || brand.contains("redmagic") -> Rom.MY_OS
        displayId.contains("miui") -> Rom.MIUIHyperOS
        displayId.contains("coloros") -> Rom.COLOR_OS
        displayId.contains("originos") || displayId.contains("funtouch") -> Rom.ORIGIN_OS
        else -> Rom.UNKNOWN
    }

    /**
     * 读取 ROM 版本号原文。读不到返回 null。
     * 注意：不同 ROM 的格式不统一（"V816"、"V14.0.1"、"14.0.0.501(C00)"…），
     * 因此只用于展示与签名比对，**不要做字符串版本号的大小比较**。
     */
    fun romVersion(): String? {
        val rom = detectRom()
        val v = rom.versionProp?.let { getProp(it) }
        return v?.let { "${rom.displayName} $it" } ?: rom.displayName
    }

    /** 主版本号，用于粗粒度判断（best effort，失败返回 0） */
    fun romMajorVersion(): Int =
        romVersion()?.let { s ->
            Regex("""(\d{1,3})""").find(s)?.groupValues?.get(1)?.toIntOrNull()
        } ?: 0

    /**
     * 设备兼容性签名。用于给已经学会的降级档位做缓存 key：
     * - 同一台同版本 ROM → 直接复用上次成功的档位，秒开
     * - ROM 升级 / 系统大版本更新 → 签名变化 → 自动丢弃缓存重新探测
     *
     * 签名**不含**序列号等用户身份信息，可安全写入日志与导出报告。
     */
    fun signature(): String = buildString {
        append(Build.BOARD ?: "?").append('/')
        append(Build.DEVICE ?: "?").append('/')
        append(Build.MODEL ?: "?").append('/')
        append(Build.VERSION.SDK_INT).append('/')
        append(romVersion() ?: "-").append('/')
        append(Build.VERSION.INCREMENTAL ?: "?")
    }

    /** 诊断报告需要的完整画像字段（键值对），供设置页导出 */
    fun diagnostics(): Map<String, String> = linkedMapOf(
        "brand" to (Build.BRAND ?: "?"),
        "manufacturer" to (Build.MANUFACTURER ?: "?"),
        "model" to (Build.MODEL ?: "?"),
        "device" to (Build.DEVICE ?: "?"),
        "board" to (Build.BOARD ?: "?"),
        "soc_platform" to (getProp("ro.board.platform") ?: "?"),
        "android" to Build.VERSION.RELEASE,
        "sdk" to Build.VERSION.SDK_INT.toString(),
        "rom" to (romVersion() ?: "?"),
        "signature" to signature(),
    )

    /**
     * 兼容性诊断报告（纯文本），供设置页「导出兼容性诊断」一键发给开发者。
     *
     * 为什么需要它：线上反馈几乎都是「我手机投屏黑屏」「手柄按键没反应」，
     * 而这三类问题的根因全在设备侧画像上 ——
     * ① ROM 版本决定权限入口在哪一页；② 蓝牙栈决定 HID 用哪种发送方式；
     * ③ 投屏学到哪一档决定是否已经自动降级成功。
     * 把这三块连同 [signature] 打成一段文本，用户复制/分享即可定位，
     * 省掉「反复来回问机型、问 ROM 版本」的排障往返。
     *
     * 报告**不含**任何用户身份信息：无序列号、无 IMEI、无 API Key、无聊天内容。
     */
    fun compatReport(context: Context): String = buildString {
        appendLine("========================================")
        appendLine("  RokidLab 兼容性诊断报告")
        appendLine("========================================")
        appendLine("导出时间 : ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date())}")
        appendLine("应用版本 : v${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE})")
        appendLine()
        appendLine("--- 设备 / ROM ---")
        diagnostics().forEach { (k, v) -> appendLine("  $k = $v") }
        appendLine("  厂商识别 = ${ManufacturerUtils.getManufacturerDisplayName()}")
        appendLine(
            "  兼容性标记 = " +
                ManufacturerUtils.getCompatibilityIssues().ifEmpty { listOf("none") }.joinToString(",")
        )
        appendLine()
        appendLine("--- 蓝牙 HID 栈 ---")
        BtHidCompat.diagnostics().lineSequence().forEach { appendLine("  $it") }
        appendLine()
        appendLine("--- 投屏降级 ---")
        appendLine("  当前有效档位 = ${MirrorCompat.currentStatus(context)}")
    }
}
