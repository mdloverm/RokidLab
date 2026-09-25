package com.rokidlab.phone.permission

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import com.rokidlab.phone.R

/**
 * 手机端「功能 ↔ 系统权限」总表（唯一事实来源）。
 *
 * 为什么必须集中成一张表：此前每处功能各写一段 `checkSelfPermission` + 一句"请去设置里开启"的
 * 文案（`PhoneTools.searchContacts` / `queryCalendar` / `LocationTools.getLocation` /
 * `PhoneToolProvider` 的重复分支），结果是——
 *  1. 文案各写各的，有的写「设置 → 应用 → RokidLab」有的写「乐奇实验室」；
 *  2. **缺权限时只会打印一段文本，从不拉起系统授权界面**，用户听到"请去设置里开启"之后
 *     得自己在设置里翻半天，实际表现为"这功能坏了"；
 *  3. 新增/漏改一处就漏一处（启动期那份自检就漏掉了定位与悬浮窗）。
 * 收敛到这里之后，"缺什么、怎么申请、申请不到怎么办"只有一份实现；引导流程的「开启全部权限」步
 * 直接按本表逐项渲染 —— **新增权限只需登记本表，权限页自动多出一行**（见 project_rules.md「权限登记」）。
 *
 * ⚠️ 两条容易踩的坑（改表前先读）：
 *  - [OVERLAY] 是 **AppOps 而非运行时权限**：`requestPermissions` 对它**不会弹任何窗**，
 *    只能在系统设置页里开，所以它走「跳到设置页」这条独立路径（见 [runtimeRequestable]）。
 *    它也是 Android 10+ 后台启动 Activity（BAL）的**唯一**普通应用可用豁免 ——
 *    "眼镜说打电话、手机毫无反应"的头号根因就是它丢了（重装/换签名后 AppOps 会清零）。
 *  - [LOCATION] 在 Android 12+ 请求 `ACCESS_FINE_LOCATION` 时**必须同时请求 COARSE**，
 *    否则系统直接返回拒绝且不弹窗，所以用 [companions] 表达"必须一起申请"。
 */
enum class AppPermission(
    /** 清单 `uses-permission` 里声明的权限名 */
    val manifestName: String,
    /** 展示名（向用户/模型说明"缺的是哪个权限"） */
    val labelRes: Int,
    /** 必须一并申请的伴随权限（Android 12+ 的 FINE 必须带 COARSE） */
    val companions: List<String> = emptyList(),
    /** 低于该 API 等级时该权限不存在、自动视为已授予 */
    val minSdk: Int = 0,
    /**
     * 是否进首装引导「开启全部权限」步并作为连接前置条件。
     * 只有**核心链路硬前提**才为 true；可选能力的重权限（如 [ALL_FILES]）必须显式置 false，
     * 在对应功能页单独引导 —— 否则每个新用户都会被一个 28 MB 可选功能挡在眼镜连接之前。
     */
    val onboardingCritical: Boolean = true,
) {
    /**
     * 蓝牙：连眼镜的**硬前提**（`BLUETOOTH_CONNECT` 读已配对设备、`SCAN` 发现设备）。
     *
     * 缺它不会弹任何提示，只在日志里留一行 `SecurityException: getBondedDevices()`，
     * 表现为"眼镜明明配对过却连不上 / 语音推送时通时断"。
     * Android 12 才把它拆成运行时权限；12 以下 `BLUETOOTH`/`BLUETOOTH_ADMIN` 是 normal 级别
     * 安装即授予，所以 [minSdk] 设成 S —— 低版本自动视为已授予，不多弹一个框。
     */
    BLUETOOTH(
        Manifest.permission.BLUETOOTH_CONNECT,
        R.string.permission_label_bluetooth,
        companions = listOf(Manifest.permission.BLUETOOTH_SCAN),
        minSdk = Build.VERSION_CODES.S,
    ),

    /** 通讯录：`search_contacts`、按姓名拨号 */
    CONTACTS(Manifest.permission.READ_CONTACTS, R.string.permission_label_contacts),

    /** 电话：`call_phone` 用 `ACTION_CALL` 直接拨出（未授予则只能打开拨号盘） */
    PHONE_CALL(Manifest.permission.CALL_PHONE, R.string.permission_label_phone),

    /**
     * 短信：`send_sms` 用 `SmsManager` 直接发出。
     *
     * [onboardingCritical] = false：短信不是"连上眼镜"的硬前提，把它放进引导会把每个新用户
     * 多挡一步；缺权限时由 `send_sms` 现场走 [PermissionBridge.ensure] 拉起系统授权框。
     */
    SMS(
        Manifest.permission.SEND_SMS,
        R.string.permission_label_sms,
        onboardingCritical = false,
    ),

    /** 日历读：`query_calendar` */
    CALENDAR_READ(Manifest.permission.READ_CALENDAR, R.string.permission_label_calendar_read),

    /** 日历写：`add_calendar_event` */
    CALENDAR_WRITE(Manifest.permission.WRITE_CALENDAR, R.string.permission_label_calendar_write),

    /** 位置：`get_location`（WIFI 扫描同样依赖它） */
    LOCATION(
        Manifest.permission.ACCESS_FINE_LOCATION,
        R.string.permission_label_location,
        companions = listOf(Manifest.permission.ACCESS_COARSE_LOCATION),
    ),

    /** 通知：Android 13+ 的 `POST_NOTIFICATIONS`，状态栏播放器/定时推送/权限兜底提醒依赖它 */
    NOTIFICATION(
        Manifest.permission.POST_NOTIFICATIONS,
        R.string.permission_label_notification,
        minSdk = Build.VERSION_CODES.TIRAMISU,
    ),

    /**
     * 悬浮窗 / 后台弹出界面（`SYSTEM_ALERT_WINDOW`）：Android 10+ 后台启动 Activity（BAL）的豁免。
     *
     * 缺它的后果是**静默失败**：后台 `startActivity` 被系统直接丢弃，不抛异常、不打 error 日志，
     * 代码以为成功了 —— 表现为"AI 说「正在拨打：X」但手机屏幕毫无反应"。
     * 因此所有"从后台拉起界面"的功能（拨号 / 闹钟 / 打开应用）都必须先确认它。
     */
    OVERLAY(Manifest.permission.SYSTEM_ALERT_WINDOW, R.string.permission_label_overlay),

    /**
     * 所有文件访问（`MANAGE_EXTERNAL_STORAGE`，Android 11+）：**只**给本机执行环境的
     * `/mnt/lab ↔ 下载/Lab` 直通使用 —— proot 子进程只能走内核文件路径，
     * 分区存储下没有这条 AppOps，公共下载目录直接 File 读写会被 FUSE 拒绝。
     *
     * 与 [OVERLAY] 同属「设置页开关」型：`requestPermissions` 不弹窗，
     * 只能跳 `ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION`。
     * 未授权时容器自动降级挂 App 私有目录，功能不中断（只是文件管理器里看不到）。
     */
    ALL_FILES(
        Manifest.permission.MANAGE_EXTERNAL_STORAGE,
        R.string.permission_label_all_files,
        minSdk = Build.VERSION_CODES.R,
        onboardingCritical = false,
    ),

    /**
     * 精确闹钟（`SCHEDULE_EXACT_ALARM`，Android 12+）：定时提醒/自主任务到点准时触发的前提。
     *
     * 属于 AppOps 特殊权限（与 [OVERLAY] 同类）：`requestPermissions` 对它无效，
     * 只能用 `ACTION_REQUEST_SCHEDULE_EXACT_ALARM` 拉起系统授权框。
     * Android 13+ 清单同时声明了 `USE_EXACT_ALARM`（安装即授予、不可撤销），
     * 此时 [android.app.AlarmManager.canScheduleExactAlarms] 恒为 true，本项自动视为已授予。
     * 非连眼镜硬前提（[onboardingCritical] = false）：缺权限时定时任务降级为不精确闹钟，
     * 在定时功能页/工具执行时按需引导。
     */
    EXACT_ALARM(
        Manifest.permission.SCHEDULE_EXACT_ALARM,
        R.string.permission_label_exact_alarm,
        minSdk = Build.VERSION_CODES.S,
        onboardingCritical = false,
    ),

    /**
     * 无障碍服务（`BIND_ACCESSIBILITY_SERVICE`）：屏幕操作域（`read_screen` / `tap_screen` /
     * `swipe_screen` / `press_key` / `type_text`）与**零弹窗截屏**的前提。
     *
     * ⚠️ 这一项与其它项**形状不同**，改它之前必须知道：
     *  1. [manifestName] 不是 `uses-permission` 里那类权限 —— 它是**服务级权限**，写在
     *     `AndroidManifest` 的 `<service android:permission=…>` 上（保证只有系统能 bind 我们）。
     *     这里写它只是为了"缺什么"时有个人能读的标识，`runtimeNames` 永远不会用到它。
     *  2. 它**没有 `requestPermissions` 这条路**：只能在系统「设置 → 无障碍」里由用户手动开，
     *     所以与 [OVERLAY] / [ALL_FILES] 同属"设置页开关"型（[runtimeRequestable] = false），
     *     由 [PermissionRequestActivity] 跳 `ACTION_ACCESSIBILITY_SETTINGS`。
     *  3. [minSdk] = R：`AccessibilityService.takeScreenshot()` 是 Android 11 起的能力。
     *     低版本上"开服务"本身没意义（截不了也点不了），直接视为已授予，不再引导。
     *
     * [onboardingCritical] = false：它不是"连上眼镜"的硬前提，而且这个开关在系统设置里
     * 属于**高信任授权**（开了之后能读屏、能代点），不该在首装引导里糊里糊涂地点过去。
     * 缺它时由屏幕操作工具现场引导，且引导文案走 [PermissionBridge]（不自己拼"请去设置开"）。
     */
    ACCESSIBILITY(
        "android.permission.BIND_ACCESSIBILITY_SERVICE",
        R.string.permission_label_accessibility,
        minSdk = Build.VERSION_CODES.R,
        onboardingCritical = false,
    );

    /**
     * 能否用 `requestPermissions` 弹系统授权框。
     * false = 只能跳系统设置页/专用授权框（[OVERLAY] / [ALL_FILES] / [EXACT_ALARM] / [ACCESSIBILITY]）。
     */
    val runtimeRequestable: Boolean
        get() = this != OVERLAY && this != ALL_FILES && this != EXACT_ALARM && this != ACCESSIBILITY

    companion object {
        /** 是否已授予。[OVERLAY] 走 `Settings.canDrawOverlays`（AOSP 契约，全 ROM 语义一致） */
        fun isGranted(context: Context, permission: AppPermission): Boolean {
            if (permission.minSdk > 0 && Build.VERSION.SDK_INT < permission.minSdk) return true
            if (permission == OVERLAY) return canDrawOverlays(context)
            if (permission == ALL_FILES) return canManageAllFiles()
            if (permission == EXACT_ALARM) return canScheduleExactAlarms(context)
            if (permission == ACCESSIBILITY) return com.rokidlab.phone.access.LabAccessibility.isEnabled(context)
            return ContextCompat.checkSelfPermission(
                context, permission.manifestName,
            ) == PackageManager.PERMISSION_GRANTED
        }

        /**
         * 精确闹钟权限是否已开（Android 12+）。
         *
         * 33+ 清单声明了 `USE_EXACT_ALARM` 时系统安装即授予、用户不可撤销，本值恒 true；
         * 31/32 或用户撤销过授权时为 false，需走 `ACTION_REQUEST_SCHEDULE_EXACT_ALARM` 引导。
         */
        fun canScheduleExactAlarms(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                runCatching {
                    context.getSystemService(android.app.AlarmManager::class.java)
                        ?.canScheduleExactAlarms() ?: false
                }.getOrDefault(false)

        /**
         * 悬浮窗（BAL 豁免）是否已开。
         *
         * 用 AOSP 的 [Settings.canDrawOverlays] 而不是厂商私有判断：它在所有 ROM 上都必须实现，
         * 且读取的就是 `android:system_alert_window` 这条 AppOps（`appops get` 的同一份状态）。
         * 历史上这里被误当成"OPPO/vivo 投屏兼容"专用（还附带 `isChineseRom()` 前置判断），
         * 实际上 BAL 限制**不区分品牌** —— Pixel / 三星同样需要它。
         */
        fun canDrawOverlays(context: Context): Boolean =
            runCatching { Settings.canDrawOverlays(context) }.getOrDefault(false)

        /** 所有文件访问 AppOps（Android 11+；低版本在 [isGranted] 已提前放行） */
        fun canManageAllFiles(): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.R ||
                runCatching { android.os.Environment.isExternalStorageManager() }.getOrDefault(false)

        /** 过滤出仍然缺失的权限（已授予的不再打扰用户） */
        fun missing(context: Context, permissions: Collection<AppPermission>): List<AppPermission> =
            permissions.filter { !isGranted(context, it) }.distinct()

        /**
         * 首装引导与连接前置检查用的清单（[onboardingCritical] = true 的核心权限）。
         * 可选能力的重权限不在这里，由功能页自己引导。
         */
        fun onboardingPermissions(): List<AppPermission> =
            values().filter { it.onboardingCritical }

        /**
         * 展开成 `requestPermissions` 需要的权限名数组（含 [companions]）。
         *
         * 两道过滤，缺一个都会让整套请求在部分设备上被整批拒绝：
         *  - 只取 [runtimeRequestable]：把 OVERLAY 混进去会让整套请求被拒；
         *  - 只取 `minSdk <= 当前版本`：[BLUETOOTH] 的权限名在 Android 12 以下**不存在**，
         *    申请一个不存在的权限同样会被系统整批拒绝。
         */
        fun runtimeNames(permissions: Collection<AppPermission>): Array<String> =
            permissions.filter { it.runtimeRequestable && it.minSdk <= Build.VERSION.SDK_INT }
                .flatMap { listOf(it.manifestName) + it.companions }
                .distinct()
                .toTypedArray()

        /** 权限名数组 → 展示文本（"通讯录、位置信息"），用于弹窗文案与模型回复 */
        fun labels(context: Context, permissions: Collection<AppPermission>): String =
            permissions.distinct().joinToString("、") { context.getString(it.labelRes) }
    }
}
