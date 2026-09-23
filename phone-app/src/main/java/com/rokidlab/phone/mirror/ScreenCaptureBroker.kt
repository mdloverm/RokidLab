package com.rokidlab.phone.mirror

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 「截屏」系统授权的交接点 —— 工具线程 ↔ `MainActivity` 之间唯一的通道。
 *
 * ## 为什么需要这么一个东西
 *
 * MediaProjection 的授权框只能由 **Activity** 弹出（`createScreenCaptureIntent` +
 * `registerForActivityResult`），而工具是在 `ai-turn-worker` 线程上跑的、手上只有
 * `Context`。于是把"弹框"这件事按方向拆开：
 *
 * ```
 * 工具线程 ──requestConsent()──▶ 主线程 launch 系统授权框 ──▶ 用户点按
 *         ◀────结果（resultCode + Intent）──────────────── ActivityResult 回调
 * ```
 *
 * `MainActivity` 在 `onStart` 挂上发起器、`onStop` 摘掉 —— 挂载窗口就是"界面能弹框"的
 * 窗口，因此 [canRequestConsent] 为 false 时工具可以直接如实回报"请先打开 App 界面"，
 * 而不是干等 25 秒超时。
 */
object ScreenCaptureBroker {
    private const val TAG = "ScreenCaptureBroker"

    /** `MainActivity` 挂上来的授权发起器（只在 STARTED..STOPPED 之间有效） */
    @Volatile
    private var consentLauncher: (() -> Unit)? = null

    private val lock = Any()
    private var consentLatch: CountDownLatch? = null
    private var consentResult: Pair<Int, Intent>? = null

    /** 现在弹得出系统授权框吗（App 界面不在前台时为 false） */
    fun canRequestConsent(): Boolean = consentLauncher != null

    fun attach(launch: () -> Unit) {
        consentLauncher = launch
    }

    fun detach() {
        consentLauncher = null
    }

    /**
     * 请求一次系统截屏授权并等用户点完（工具线程调用，最多阻塞 [timeoutMs]）。
     *
     * @return `resultCode to data`；null = 界面不可用 / 用户拒绝 / 超时 / 已有一次在进行
     */
    fun requestConsent(timeoutMs: Long): Pair<Int, Intent>? {
        val launcher = consentLauncher ?: return null
        val latch = CountDownLatch(1)
        synchronized(lock) {
            // 已有一次在等：不叠加（否则两个工具线程会各自等到错的结果）
            if (consentLatch != null) return null
            consentLatch = latch
            consentResult = null
        }
        Handler(Looper.getMainLooper()).post {
            // 界面不在 STARTED 状态时 launch 会抛 IllegalStateException：
            // 当作"没同意"处理并立刻放行，不能让它把工具线程打崩
            runCatching { launcher() }.onFailure {
                Log.w(TAG, "launch consent failed: ${it.message}")
                synchronized(lock) { consentLatch?.countDown() }
            }
        }
        val delivered = runCatching { latch.await(timeoutMs, TimeUnit.MILLISECONDS) }.getOrDefault(false)
        synchronized(lock) {
            val result = if (delivered) consentResult else null
            consentLatch = null
            consentResult = null
            return result
        }
    }

    /** `MainActivity` 的 ActivityResult 回调入口（主线程） */
    fun deliverConsent(resultCode: Int, data: Intent?) {
        synchronized(lock) {
            consentResult = if (data != null) resultCode to data else null
            consentLatch?.countDown()
        }
    }
}
