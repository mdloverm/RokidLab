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
 *  3. 新增/漏改一处就漏一处（`requestAiToolPermissions` 就漏了定位与悬浮窗）。
 * 收敛到这里之后，"缺什么、怎么申请、申请不到怎么办"只有一份实现。
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
) {
    /** 通讯录：`search_contacts`、按姓名拨号 */
    CONTACTS(Manifest.permission.READ_CONTACTS, R.string.permission_label_contacts),

    /** 电话：`call_phone` 用 `ACTION_CALL` 直接拨出（未授予则只能打开拨号盘） */
    PHONE_CALL(Manifest.permission.CALL_PHONE, R.string.permission_label_phone),

    /** 日历读：`query_calendar` */
    CALENDAR_READ(Manifest.permission.READ_CALENDAR, R.string.permission_label_calendar),

    /** 日历写：`add_calendar_event` */
    CALENDAR_WRITE(Manifest.permission.WRITE_CALENDAR, R.string.permission_label_calendar),

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
    OVERLAY(Manifest.permission.SYSTEM_ALERT_WINDOW, R.string.permission_label_overlay);

    /**
     * 能否用 `requestPermissions` 弹系统授权框。
     * false = 只能跳系统设置页手动开（目前仅 [OVERLAY]）。
     */
    val runtimeRequestable: Boolean get() = this != OVERLAY

    companion object {
        /** 是否已授予。[OVERLAY] 走 `Settings.canDrawOverlays`（AOSP 契约，全 ROM 语义一致） */
        fun isGranted(context: Context, permission: AppPermission): Boolean {
            if (permission.minSdk > 0 && Build.VERSION.SDK_INT < permission.minSdk) return true
            if (permission == OVERLAY) return canDrawOverlays(context)
            return ContextCompat.checkSelfPermission(
                context, permission.manifestName,
            ) == PackageManager.PERMISSION_GRANTED
        }

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

        /** 过滤出仍然缺失的权限（已授予的不再打扰用户） */
        fun missing(context: Context, permissions: Collection<AppPermission>): List<AppPermission> =
            permissions.filter { !isGranted(context, it) }.distinct()

        /**
         * 展开成 `requestPermissions` 需要的权限名数组（含 [companions]）。
         * 只取 [runtimeRequestable] 的项 —— 把 OVERLAY 混进去会让整套请求在部分 ROM 上被整批拒绝。
         */
        fun runtimeNames(permissions: Collection<AppPermission>): Array<String> =
            permissions.filter { it.runtimeRequestable }
                .flatMap { listOf(it.manifestName) + it.companions }
                .distinct()
                .toTypedArray()

        /** 权限名数组 → 展示文本（"通讯录、位置信息"），用于弹窗文案与模型回复 */
        fun labels(context: Context, permissions: Collection<AppPermission>): String =
            permissions.distinct().joinToString("、") { context.getString(it.labelRes) }
    }
}
