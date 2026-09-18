package com.rokidlab.phone.permission

import android.app.Activity
import android.app.Application
import android.os.Bundle

/**
 * 进程内「本应用此刻有没有界面在前台」的轻量追踪。
 *
 * 为什么必须自己记：Android 10+ 的后台启动 Activity 限制（BAL）下，
 *   - 应用**在前台**时 `startActivity` 一定成功；
 *   - 应用**在后台**时必须持有 `SYSTEM_ALERT_WINDOW` 等豁免，否则请求被系统**静默丢弃**。
 * 而 AOSP 没有提供"本进程是否前台"的公开 API（`ActivityManager.getRunningAppProcesses()`
 * 在 Android 11+ 已对普通应用受限、且时常返回 null），所以只能靠 Activity 生命周期自建。
 *
 * 用**计数**而不是布尔：`MainActivity → ScreenMirrorActivity` 这类叠加场景下，
 * 前一个 Activity 的 `onStopped` 会先到，若一停就置 false，会在页面切换的瞬间误判成"后台"，
 * 从而错误地给一个本来能成功的对话框套上 BAL 结论。
 *
 * 注册一次即可（在 [com.rokidlab.phone.app.LabApplication.onCreate] 调用 [install]），
 * 只做整数增减、无锁竞争风险（生命周期回调都在主线程）。
 */
object AppForegroundTracker {

    @Volatile
    var startedActivities: Int = 0
        private set

    /** 当前是否有界面处于前台（发起方据此决定"能不能直接拉起授权页"） */
    val isForeground: Boolean get() = startedActivities > 0

    fun install(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                startedActivities++
            }

            override fun onActivityStopped(activity: Activity) {
                if (startedActivities > 0) startedActivities--
            }

            // 其余回调本类不关心：注册全部方法是接口要求，空实现无信息量
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }
}
