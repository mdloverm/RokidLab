package com.rokidlab.phone.permission

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper

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

    /**
     * 「进入后台」的确认延时（2026-10-13 审计加）。
     *
     * ⚠️ **必须有这道防抖**：透明 Activity（工具确认框 / 选项卡 / 授权页）拉起时，
     * 被它盖住的界面会先 `onStopped`（计数掉到 0）再 `onStarted`（回到 1）——
     * 若不防抖就回调，会把**刚刚登记、正要等用户点选**的那个等待当场撤掉（严重误伤）。
     * 延时后复核仍为后台才认，等价于"用户确实离开了 App"。
     */
    private const val BACKGROUND_CONFIRM_MS = 1_200L

    @Volatile
    var startedActivities: Int = 0
        private set

    /** 当前是否有界面处于前台（发起方据此决定"能不能直接拉起授权页"） */
    val isForeground: Boolean get() = startedActivities > 0

    /**
     * **持续**进入后台时的回调（防抖见 [BACKGROUND_CONFIRM_MS]）。
     *
     * 用途（2026-10-13 审计 M4）：把"等待中的工具确认 / ask_user 作答"收掉，
     * 不让对话线程为一个人已经离开的界面白挂满 40s / 120s。
     * 语义是**"问不到了"**（不是"用户叫停"）⇒ 回调实现走 `abortAll(aborted = false)`，
     * 由工具自己声明的 `ToolConfirmPolicy` 分流，行为与该次等待**自然超时**完全一致。
     */
    @Volatile
    var onEnterBackground: (() -> Unit)? = null

    /**
     * 主线程 Handler。惰性 + 容错：纯 JVM 单测环境里 `Looper.getMainLooper()` 不可用，
     * 此处置 null ⇒ 防抖退化为"不触发回调"（单测不关心前后台），**不影响生产路径**。
     */
    private val handler: Handler? by lazy {
        runCatching { Handler(Looper.getMainLooper()) }.getOrNull()
    }
    private var pendingBackgroundCheck: Runnable? = null

    /** 计数归零 → 排一次延时复核；期间若回前台则取消（见 [BACKGROUND_CONFIRM_MS]） */
    private fun scheduleBackgroundCheck() {
        val h = handler ?: return
        pendingBackgroundCheck?.let { h.removeCallbacks(it) }
        val r = Runnable {
            pendingBackgroundCheck = null
            if (!isForeground) onEnterBackground?.invoke()
        }
        pendingBackgroundCheck = r
        h.postDelayed(r, BACKGROUND_CONFIRM_MS)
    }

    private fun cancelBackgroundCheck() {
        val h = handler ?: return
        pendingBackgroundCheck?.let { h.removeCallbacks(it) }
        pendingBackgroundCheck = null
    }

    fun install(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                startedActivities++
                cancelBackgroundCheck()
            }

            override fun onActivityStopped(activity: Activity) {
                if (startedActivities > 0) startedActivities--
                if (startedActivities == 0) scheduleBackgroundCheck()
            }

            // 其余回调本类不关心：注册全部方法是接口要求，空实现无信息量
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }

    /** 仅测试用：清掉防抖任务与回调（避免用例之间互串） */
    internal fun resetForTest() {
        cancelBackgroundCheck()
        onEnterBackground = null
    }
}
