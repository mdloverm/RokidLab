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
     *
     * @return true 表示成功拉起候选链里的某一支；false 表示连兜底的应用详情页都没能拉起
     */
    fun openAutoStartSettings(context: Context): Boolean =
        launchFirstAvailable(context, autoStartCandidates(context))

    /**
     * 自启动管理页候选链，按「越贴近当前 ROM 越靠前」排序，末尾固定兜底到应用详情页。
     *
     * 为什么必须是链而不是单支：厂商 Action / 组件名在 ROM 版本之间**不是稳定契约** ——
     * 同一个 Action 可能在新版本被改名或裁掉，同一个页面可能换包名（ColorOS 的
     * `com.coloros.safecenter` 与 realme 上的 `com.oppo.safe`）。原实现「一个厂商一支
     * Intent」一旦不匹配，抛出的 ActivityNotFoundException 被静默吞掉，用户看到的就是
     * 「点了没反应、以为已经开好了」，而服务实际仍在被后台清理。
     */
    private fun autoStartCandidates(context: Context): List<Intent> {
        val pkg = context.packageName
        val vendor: List<Intent> = when (detect()) {
            Manufacturer.XIAOMI -> listOf(
                // 「自启动」开关在**应用信息页**里 —— 真机实测（HyperOS 2）跳 APP_PERM_EDITOR
                // 落到的是权限编辑页，那一页**没有**自启动开关，用户白跑一趟。应用信息页 MIUI/HyperOS 必有，故排第一。
                appDetailsIntent(context),
                // 安全中心的自启动专用页（小米开发者文档给的 action，实测在 HyperOS 上仍可用），
                // 但它是**全局列表页**（允许/禁止各一大串应用，要自己找本应用），只作兜底。
                Intent("miui.intent.action.OP_AUTO_START").putExtra("extra_package_name", pkg),
                Intent().setClassName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity",
                ),
            )
            Manufacturer.HUAWEI, Manufacturer.HONOR -> listOf(
                // EMUI / MagicOS 启动管理
                Intent("huawei.intent.action.HSM_PROTECTED_APPS").putExtra("pkg_name", pkg),
                Intent("huawei.intent.action.HSM_PROTECTED_APPS"),
                Intent().setClassName(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
                ),
                Intent().setClassName(
                    "com.hihonor.systemmanager",
                    "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
                ),
                Intent().setClassName(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.optimize.process.ProtectActivity",
                ),
            )
            Manufacturer.OPPO -> listOf(
                // ColorOS / realme 自启动
                Intent("com.coloros.safecenter.action.SAFECENTER"),
                Intent().setClassName(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.permission.startup.StartupAppListActivity",
                ),
                Intent().setClassName(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.startupapp.StartupAppListActivity",
                ),
                Intent().setClassName(
                    "com.oppo.safe",
                    "com.oppo.safe.permission.startup.StartupAppListActivity",
                ),
            )
            Manufacturer.VIVO -> listOf(
                // OriginOS / Funtouch 自启动
                Intent("com.iqoo.powersave.ui.PowerSaveActivity"),
                Intent().setClassName(
                    "com.vivo.permissionmanager",
                    "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
                ),
                Intent().setClassName(
                    "com.iqoo.secure",
                    "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager",
                ),
            )
            Manufacturer.MEIZU -> listOf(
                Intent("com.meizu.safe.action.SAFE_CENTER"),
                Intent().setClassName(
                    "com.meizu.safe",
                    "com.meizu.safe.security.SafeMainActivity",
                ),
            )
            Manufacturer.SAMSUNG -> listOf(
                Intent().setClassName(
                    "com.samsung.android.lool",
                    "com.samsung.android.sm.ui.battery.BatteryActivity",
                ),
            )
            else -> emptyList()
        }
        return vendor + appDetailsIntent(context)
    }

    /**
     * 打开厂商 ROM 的省电策略/后台高耗电设置页
     * 解决华为/小米等后台服务被强杀问题
     *
     * @return true 表示成功拉起候选链里的某一支；false 表示连兜底的应用详情页都没能拉起
     */
    fun openPowerSavingSettings(context: Context): Boolean =
        launchFirstAvailable(context, powerSavingCandidates(context))

    /** 省电/后台策略页候选链，末尾固定兜底到应用详情页（见 [autoStartCandidates] 的说明） */
    private fun powerSavingCandidates(context: Context): List<Intent> {
        val pkg = context.packageName
        val vendor: List<Intent> = when (detect()) {
            Manufacturer.XIAOMI -> listOf(
                Intent("miui.intent.action.APP_PERM_EDITOR").putExtra("extra_pkgname", pkg),
                Intent("miui.intent.action.POWER_HIDE_MODE_APP_LIST").putExtra("extra_pkgname", pkg),
                Intent().setClassName(
                    "com.miui.powerkeeper",
                    "com.miui.powerkeeper.ui.HiddenAppsContainerManagementActivity",
                ),
            )
            Manufacturer.HUAWEI, Manufacturer.HONOR -> listOf(
                Intent().setClassName(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.power.ui.HwPowerManagerActivity",
                ),
                Intent().setClassName(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity",
                ),
                Intent().setClassName(
                    "com.hihonor.systemmanager",
                    "com.hihonor.systemmanager.power.ui.HwPowerManagerActivity",
                ),
            )
            Manufacturer.OPPO -> listOf(
                Intent("com.coloros.safecenter.action.SAFECENTER"),
                Intent().setClassName(
                    "com.coloros.oppoguardelf",
                    "com.coloros.powermanager.fuelgaue.PowerUsageModelActivity",
                ),
                Intent().setClassName(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.permission.startup.StartupAppListActivity",
                ),
            )
            Manufacturer.VIVO -> listOf(
                // vivo/iQOO 的 10 分钟后台硬限制需要在「后台高耗电」白名单里放行
                Intent("com.iqoo.powersave.ui.PowerSaveActivity"),
                Intent().setClassName(
                    "com.iqoo.secure",
                    "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
                ),
                Intent().setClassName(
                    "com.vivo.permissionmanager",
                    "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
                ),
            )
            Manufacturer.MEIZU -> listOf(
                Intent("com.meizu.safe.action.SAFE_CENTER"),
            )
            else -> emptyList()
        }
        return vendor + appDetailsIntent(context)
    }

    // ── 悬浮窗权限 ──

    /** 是否可以绘制悬浮窗（用于 OPPO/vivo 投屏兼容） */
    fun canDrawOverlays(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else true
    }

    /** 打开悬浮窗权限设置页（候选链：系统标准页 → 厂商私有页 → 应用详情页） */
    fun openOverlaySettings(context: Context): Boolean =
        launchFirstAvailable(context, overlaySettingsCandidates(context))

    /**
     * 悬浮窗权限页候选链。
     *
     * 顺序与自启动相反 —— **系统标准页排第一**：`ACTION_MANAGE_OVERLAY_PERMISSION`
     * 是 AOSP 契约，所有 ROM 都必须实现，且带 `package:` 时能直接定位到本应用那一行开关；
     * 厂商私有页（如 ColorOS 的 SAFECENTER）往往只是权限总表，用户还得自己找。
     * 所以先走能精确落位的标准页，私有页只作为标准页缺失时的备选。
     */
    private fun overlaySettingsCandidates(context: Context): List<Intent> {
        val pkg = context.packageName
        val standard = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
            data = Uri.parse("package:$pkg")
        }
        val vendor: List<Intent> = when (detect()) {
            Manufacturer.OPPO -> listOf(
                Intent().setClassName(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.permission.floatwindow.FloatWindowListActivity",
                ),
                Intent("com.coloros.safecenter.action.SAFECENTER"),
            )
            Manufacturer.VIVO -> listOf(
                Intent().setClassName(
                    "com.vivo.permissionmanager",
                    "com.vivo.permissionmanager.activity.FloatWindowManagerActivity",
                ),
                Intent("com.iqoo.powersave.ui.PowerSaveActivity"),
            )
            Manufacturer.XIAOMI -> listOf(
                Intent("miui.intent.action.APP_PERM_EDITOR").putExtra("extra_pkgname", pkg),
            )
            else -> emptyList()
        }
        return listOf(standard) + vendor + appDetailsIntent(context)
    }

    /**
     * 获取单支悬浮窗权限页 Intent（供 `ActivityResultLauncher` 等无法逐支降级的调用方使用）。
     * 取候选链首支，即系统标准页。
     */
    fun getOverlaySettingsIntent(context: Context): Intent? =
        overlaySettingsCandidates(context).firstOrNull()

    // ── 应用权限设置页（补齐某个具体运行时权限时用） ──

    /**
     * 打开「本应用的权限管理」设置页，用于运行时权限被**永久拒绝**后的兜底引导。
     *
     * 与 [openOverlaySettings] 的取舍正好相反，原因值得写下来：
     * - 悬浮窗有 AOSP 标准页（`ACTION_MANAGE_OVERLAY_PERMISSION` + `package:`）能**直接落到那一行开关**，
     *   所以那边是"标准页优先"；
     * - 而"某个运行时权限的开关"**没有** AOSP 标准 Action（Android 11 的
     *   `MANAGE_APP_PERMISSIONS` 要系统权限，普通应用发出去也解不开），厂商权限页反而是更精确的落点，
     *   所以这里是"厂商页优先"；
     * - 两者都以 [appDetailsIntent] 收尾 —— 应用详情页是 AOSP 契约、**所有 ROM 必有**，
     *   于是"厂商页猜错了"最差也只是让用户多点一次「权限」，不会出现"点了没反应"。
     *
     * @param permission 清单权限名，仅用于日志（跳转按 ROM 分派，不按权限名）
     * @return true = 候选链里某一支成功拉起
     */
    fun openAppPermissionSettings(context: Context, permission: String): Boolean {
        Log.i(TAG, "open app permission settings: $permission (${detect().displayName})")
        return launchFirstAvailable(context, appPermissionCandidates(context))
    }

    /** 应用权限管理页候选链：厂商权限页 → 应用详情页（见 [openAppPermissionSettings] 的顺序说明） */
    private fun appPermissionCandidates(context: Context): List<Intent> {
        val pkg = context.packageName
        val vendor: List<Intent> = when (detect()) {
            Manufacturer.XIAOMI -> listOf(
                // MIUI 的应用权限编辑页（与自启动页同一个 Action，MIUI 内部按 extra 定位）
                Intent("miui.intent.action.APP_PERM_EDITOR").putExtra("extra_pkgname", pkg),
            )
            Manufacturer.HUAWEI, Manufacturer.HONOR -> listOf(
                Intent().setClassName(
                    "com.huawei.systemmanager",
                    "com.huawei.permissionmanager.ui.MainActivity",
                ),
                Intent().setClassName(
                    "com.hihonor.systemmanager",
                    "com.hihonor.permissionmanager.ui.MainActivity",
                ),
            )
            Manufacturer.OPPO -> listOf(
                Intent().setClassName(
                    "com.coloros.safecenter",
                    "com.coloros.safecenter.permission.PermissionManagerActivity",
                ),
                Intent().setClassName(
                    "com.oppo.safe",
                    "com.oppo.safe.permission.PermissionAppListActivity",
                ),
            )
            Manufacturer.VIVO -> listOf(
                Intent().setClassName(
                    "com.vivo.permissionmanager",
                    "com.vivo.permissionmanager.activity.PurviewTabActivity",
                ),
            )
            Manufacturer.MEIZU -> listOf(
                Intent().setClassName("com.meizu.safe", "com.meizu.safe.security.SafeMainActivity"),
            )
            // 三星/Google/其它：直接走应用详情页（AOSP 必有），不猜私有组件
            else -> emptyList()
        }
        return vendor + appDetailsIntent(context)
    }

    // ── 通知权限（厂商特殊处理） ──

    /**
     * 打开厂商 ROM 的通知权限设置页
     * 部分 ROM（如 MIUI）中标准 POST_NOTIFICATIONS 弹窗可能被静默拒绝
     */
    fun openNotificationSettings(context: Context) {
        launchFirstAvailable(context, notificationCandidates(context))
    }

    /** 通知权限页候选链：厂商权限总表 → 系统通知页 → 应用详情页 */
    private fun notificationCandidates(context: Context): List<Intent> {
        val pkg = context.packageName
        val vendor: List<Intent> = when (detect()) {
            Manufacturer.XIAOMI -> listOf(
                // MIUI: 跳转应用详情 → 通知管理
                Intent("miui.intent.action.APP_PERM_EDITOR").putExtra("extra_pkgname", pkg),
            )
            else -> emptyList()
        }
        val systemNotification = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, pkg)
        }
        return vendor + systemNotification + appDetailsIntent(context)
    }

    // ── 跳转执行器 ──

    /** 应用系统详情页（AOSP 必有），候选链的最后一档兜底 */
    private fun appDetailsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
        }

    /**
     * 按顺序逐支尝试拉起设置页，第一支成功即停；全失败返回 false。
     *
     * 为什么**不做 resolveActivity 预检**：Android 11+ 的软件包可见性（`<queries>` 声明）
     * 会让 `resolveActivity` 对未声明的系统组件返回 null，但 `startActivity` 本身不受该限制 ——
     * 拿预检结果决定跳不跳，会把本来能用的厂商入口误杀成「无入口」。所以以真的启动一次为准，
     * 把 ActivityNotFoundException / SecurityException 当作「这支在当前 ROM 上不可用」的信号。
     *
     * 失败只记日志、不抛给调用方：用户点「去设置」时最差也应落到应用详情页手动改，
     * 不该因为厂商页缺失就整条引导链路崩掉。
     */
    private fun launchFirstAvailable(context: Context, candidates: List<Intent>): Boolean {
        for ((idx, intent) in candidates.withIndex()) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val label = intent.action ?: intent.component?.flattenToShortString() ?: "?"
            try {
                context.startActivity(intent)
                Log.i(TAG, "settings jump ok (#$idx, $label)")
                return true
            } catch (e: Exception) {
                Log.i(TAG, "settings jump #$idx ($label) unavailable: ${e.javaClass.simpleName}")
            }
        }
        Log.w(TAG, "all settings jump candidates failed")
        return false
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

    private fun getSystemProperty(name: String): String? =
        com.rokidlab.phone.platform.RomAdapter.systemProperty(name)

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
