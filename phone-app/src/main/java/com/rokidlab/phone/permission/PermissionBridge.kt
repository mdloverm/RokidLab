package com.rokidlab.phone.permission

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.rokidlab.phone.R
import com.rokidlab.phone.util.LogCollector

/**
 * 权限申请桥（工具侧唯一入口）：**缺权限时自动把系统授权界面拉起来**，而不是只回一句"请去设置里开"。
 *
 * ## 为什么要这一层
 * 语音/眼镜场景下工具是在**后台线程**跑的、且手机 App 通常**不在前台**，
 * 因此"弹出授权框"这件事必须先解决"能不能从后台拉起界面"：
 *
 * | 当前状态 | 能否拉起授权页 | 处理方式 |
 * |---|---|---|
 * | 应用在前台（用户正看着手机） | ✅ 一定能 | 直接拉起 [PermissionRequestActivity] |
 * | 后台 + 已开悬浮窗（BAL 豁免） | ✅ 能 | 直接拉起 |
 * | 后台 + 无悬浮窗 | ❌ 拉不起来（这正是要修的那个权限） | 发一条**高优先级通知**兜底，用户点一下即完成授权 |
 *
 * 这张表就是本类存在的全部理由：悬浮窗（BAL 豁免）缺失时存在"自举死锁" ——
 * 想申请它就需要能拉起界面，而能拉起界面又依赖它。破解办法两条：
 *  1. **启动期**（`MainActivity` 里，天然前台）把包括悬浮窗在内的权限一次性补齐；
 *  2. **运行期**拉不起来时退回通知栏，用户点击通知这个动作本身会给系统"用户已授权本次跳转"的信号。
 *
 * ## 品牌无关性（用户明确要求）
 * 这里**不使用任何厂商判断**来决定走哪条路：申请走 AOSP `requestPermissions`，
 * 悬浮窗走 AOSP `ACTION_MANAGE_OVERLAY_PERMISSION`，兜底走 AOSP `ACTION_APPLICATION_DETAILS_SETTINGS`；
 * 厂商私有页只在"标准页不可用"时由 [com.rokidlab.phone.util.ManufacturerUtils] 的候选链作为备选，
 * 且失败只记日志不中断流程 —— 所以最差也能落到所有 ROM 都有的应用详情页。
 * 也**不按 `isChineseRom()` 做前置判断**：BAL 限制与 AppOps 清零在 Pixel / 三星上一模一样。
 */
object PermissionBridge {
    private const val TAG = "PermissionBridge"

    private const val PREFS = "permission_bridge"
    private const val KEY_LAST_PROMPT_AT = "last_prompt_at"

    /**
     * 同一次诉求的冷却窗口。工具失败后模型常会自动重试（一轮里可能连调几次），
     * 没有冷却会把授权页叠成"反复弹出、用户点不完"。窗口内的重复诉求只回报文案、不再拉起界面。
     */
    private const val PROMPT_COOLDOWN_MS = 15_000L

    /** 通知栏兜底用的渠道（低打扰但可见；Android 8+ 必须有渠道才能发通知） */
    const val CHANNEL_ID = "permission_alert"

    /** 兜底通知的固定 id：同一诉求复用一条，不刷屏 */
    private const val NOTIFICATION_ID = 0x4C4142

    private val mainHandler = Handler(Looper.getMainLooper())

    // ═══════════════════════════ 能力判定 ═══════════════════════════

    /**
     * 此刻能否把界面拉起来。前台一定可以；后台则要求持有悬浮窗（BAL 豁免）。
     *
     * 注意不能用"try 一下看抛不抛异常"来判断：BAL 拦截是**静默丢弃**，
     * `startActivity` 既不抛异常也不返回失败，只能靠这个前置条件判断。
     */
    fun canLaunchUi(context: Context): Boolean =
        AppForegroundTracker.isForeground || AppPermission.canDrawOverlays(context)

    /** 当前缺失的权限（已授予的不重复打扰） */
    fun missing(context: Context, vararg permissions: AppPermission): List<AppPermission> =
        AppPermission.missing(context, permissions.toList())

    // ═══════════════════════════ 工具侧入口 ═══════════════════════════

    /**
     * 确保 [permissions] 全部就绪；缺失则**自动发起申请**。
     *
     * @param reason 为什么要这个权限（写成人话，直接展示给用户，例如"查找联系人并拨号"）
     * @return `null` = 已就绪，调用方照常执行；
     *         非 `null` = 缺权限，**调用方必须立即把该文本返回给模型并放弃本次实际动作**
     *         （否则就是"AI 说做了、其实被系统拦掉"的假成功）。
     */
    fun ensure(context: Context, reason: String, vararg permissions: AppPermission): String? {
        val appContext = context.applicationContext
        val missing = AppPermission.missing(appContext, permissions.toList())
        if (missing.isEmpty()) return null
        val labels = AppPermission.labels(appContext, missing)

        if (isThrottled(appContext)) {
            Log.i(TAG, "permission prompt throttled, missing=$missing")
            return appContext.getString(R.string.permission_prompt_pending, labels)
        }
        markPrompted(appContext)

        val launched = canLaunchUi(appContext) && launchRequestScreen(appContext, missing, reason)
        if (launched) {
            Log.i(TAG, "permission screen launched, missing=$missing")
            return appContext.getString(R.string.permission_auto_requested, labels)
        }

        // 拉不起界面（后台且无悬浮窗）→ 通知栏兜底：用户点一下就完成了授权入口
        notifyFallback(appContext, missing, reason)
        Log.i(TAG, "permission screen unavailable, fallback notification posted, missing=$missing")
        return appContext.getString(R.string.permission_need_manual_open, labels)
    }

    /**
     * 主动拉起授权界面（不做缺失判断、不返回文案）。
     *
     * @return true = 界面/通知已成功发起
     */
    fun request(
        context: Context,
        permissions: List<AppPermission>,
        reason: String,
    ): Boolean {
        val appContext = context.applicationContext
        val missing = AppPermission.missing(appContext, permissions)
        if (missing.isEmpty()) return true
        if (canLaunchUi(appContext) && launchRequestScreen(appContext, missing, reason)) return true
        notifyFallback(appContext, missing, reason)
        return false
    }

    /** 启动期自检用：悬浮窗（BAL 豁免）是否已开 —— 决定"眼镜语音在后台还能不能拨号/开应用" */
    fun hasBackgroundLaunchExemption(context: Context): Boolean =
        AppPermission.canDrawOverlays(context)

    // ═══════════════════════════ 拉起 / 兜底 ═══════════════════════════

    /**
     * 拉起授权页。必须回主线程执行（`startActivity` 在 binder 侧串行，主线程调用可保证
     * 界面顺序与用户预期一致，也避免和随后可能的 Activity 创建竞态）。
     *
     * 先 `getActivityInfo` 确认组件存在：`startActivity` 对**不存在的显式组件**会抛
     * `ActivityNotFoundException`，而 BAL 拦截却什么都不抛 —— 两者后果天差地别
     * （前者是我们写错类名，后者是用户没给权限），必须先分清楚才能给出正确文案。
     */
    private fun launchRequestScreen(
        context: Context,
        missing: List<AppPermission>,
        reason: String,
    ): Boolean = runCatching {
        val intent = PermissionRequestActivity.createIntent(context, missing, reason)
        val component = intent.component ?: error("PermissionRequestActivity intent has no component")
        context.packageManager.getActivityInfo(component, 0)
        if (Looper.myLooper() == Looper.getMainLooper()) {
            context.startActivity(intent)
        } else {
            mainHandler.post {
                runCatching { context.startActivity(intent) }
                    .onFailure { LogCollector.e(TAG, "拉起授权页失败", it) }
            }
        }
        true
    }.getOrElse {
        // 走到这里说明是代码/清单问题（组件不存在），不是用户没授权 —— 必须高声告警
        LogCollector.e(TAG, "授权页组件不可用（AndroidManifest 是否漏注册？）", it)
        false
    }

    /**
     * 通知栏兜底：后台且无悬浮窗时唯一能让用户"一步到位"的入口。
     * 用户点击通知 → 系统把这次跳转算作"用户发起" → BAL 放行 → [PermissionRequestActivity] 正常弹出。
     */
    private fun notifyFallback(context: Context, missing: List<AppPermission>, reason: String) {
        val labels = AppPermission.labels(context, missing)
        runCatching {
            ensureChannel(context)
            val intent = PermissionRequestActivity.createIntent(context, missing, reason)
            val pending = PendingIntent.getActivity(
                context,
                NOTIFICATION_ID,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val notification: Notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(context.getString(R.string.permission_notification_title))
                .setContentText(context.getString(R.string.permission_notification_text, labels))
                .setStyle(NotificationCompat.BigTextStyle().bigText(
                    context.getString(R.string.permission_notification_text, labels),
                ))
                .setContentIntent(pending)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        }.onFailure {
            // 通知被禁（POST_NOTIFICATIONS 未授予）+ 后台无悬浮窗 = 两条路都断了，
            // 此时只能靠模型把"请手动开启"转告用户，日志必须留证，便于事后定位
            LogCollector.w(TAG, "权限兜底通知发送失败，只能依赖文案引导: ${it.message}", it)
        }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.permission_channel_name),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = context.getString(R.string.permission_channel_desc)
            },
        )
    }

    // ═══════════════════════════ 节流 ═══════════════════════════

    private fun isThrottled(context: Context): Boolean =
        System.currentTimeMillis() - lastPromptAt(context) < PROMPT_COOLDOWN_MS

    private fun lastPromptAt(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_LAST_PROMPT_AT, 0L)

    private fun markPrompted(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAST_PROMPT_AT, System.currentTimeMillis())
            .apply()
    }
}
