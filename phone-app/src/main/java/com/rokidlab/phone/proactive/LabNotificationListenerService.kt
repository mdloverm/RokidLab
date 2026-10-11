package com.rokidlab.phone.proactive

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * 通知使用权服务（消息摘要的**事件源**）：只负责把「值得摘要的通知」收进内存缓冲，
 * **自己绝不开口**。
 *
 * ## 为什么不直接在回调里播报
 * [NotificationListenerService.onNotificationPosted] 是**每条**通知都会调的高频回调：
 * 直接在里面播报 = 用户连收 5 条消息就被打断 5 次，而且此时多半正在操作手机。
 * 所以本服务只做「收集」，真正的播报时机由 [NotificationDigest.onTick] 挂在
 * 30 分钟自检链上（与 [CalendarBriefing] 同一批闹钟），由决策门统一裁决。
 *
 * ## 生命周期与隐私
 * - 用户未授予通知使用权时，系统根本不会 bind 本服务 —— 即"没授权 = 完全不采集"，
 *   不存在"先采集后判断"的窗口；
 * - 授权后缓冲区**只在内存里**，进程被杀即清空，不落盘（通知正文属于隐私，
 *   落盘就等于复制了一份用户数据到 App 私有目录，超出本功能的必要范围）；
 * - [NotificationDigestPolicy.shouldIgnore] 会挡掉验证码/密码/金融医疗类内容，
 *   这类通知**既不入缓冲也不入 prompt**。
 *
 * ⚠️ 清单里必须带 `android:permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE"`
 * （保证只有系统能 bind）与 `exported=true` + intent-filter（系统要能发现我们）——
 * 见 `AndroidManifest.xml`。
 *
 * 📌 与无障碍不同：**不需要** meta-data 指向 xml 配置。`NotificationListenerService`
 * 没有"能力开关"要声明（截屏/手势那类 meta-data 是无障碍独有的），所以少一个漏配点。
 */
class LabNotificationListenerService : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val s = sbn ?: return
        val app = applicationContext ?: return
        try {
            val digest = NotificationDigest.get(app)
            if (!digest.isEnabled()) return
            val pkg = s.packageName.orEmpty()
            // ⚠️ flag 常量在 `Notification` 上，不在 `StatusBarNotification` 上 ——
 // 写错类名会编出 "Unresolved reference FLAG_ONGOING_EVENT"，且类型推成 BigInteger
            val notification = s.notification ?: return
            val flags = notification.flags
            val ongoing = flags and android.app.Notification.FLAG_ONGOING_EVENT != 0
            val groupSummary = flags and android.app.Notification.FLAG_GROUP_SUMMARY != 0

            val extras = notification.extras ?: return
            val title = NotificationDigestPolicy.sanitize(
                extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty(),
            )
            val body = NotificationDigestPolicy.sanitize(
                extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
                    ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty(),
            )
            val text = listOf(title, body).filter { it.isNotEmpty() }.joinToString("：")

            val reason = NotificationDigestPolicy.shouldIgnore(
                pkg = pkg,
                isOngoing = ongoing,
                isGroupSummary = groupSummary,
                text = text,
                selfPackage = packageName,
            )
            if (reason != null) {
                Log.i(TAG, "ignore ${s.packageName}: $reason")
                return
            }
            // 用通知**自身的发布时间**而不是"现在"：服务重新绑定时（刚授权/系统重启/权限重授）
// 系统会把当前仍在状态栏的通知**重新投递**一遍，那些可能是几小时前的。
// 用 postTime 它们会立刻被判为过期丢弃，而用 now() 会被当成新消息播报出来。
            val postedAt = if (s.postTime > 0L) s.postTime else System.currentTimeMillis()
            digest.collect(pkg, text, postedAt)
        } catch (e: Exception) {
            // 通知回调跑在主线程且系统会跨厂商调它，任何 ROM 异常都不该拖垮服务
            Log.w(TAG, "onNotificationPosted failed: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // 服务被系统回收 = 采集能力消失，已收集的内容随即失去时效，清空避免下次开机播报陈旧消息
        try {
            NotificationDigest.get(applicationContext).clearBuffer()
            Log.i(TAG, "listener destroyed, buffer cleared")
        } catch (e: Exception) {
            Log.w(TAG, "clear buffer on destroy failed: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "LabNotificationListener"
    }
}