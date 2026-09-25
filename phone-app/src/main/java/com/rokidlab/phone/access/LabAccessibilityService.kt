package com.rokidlab.phone.access

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * 乐奇实验室的无障碍服务本体 —— 只做三件事：**截屏、手势、读 UI 树**。
 *
 * ## 为什么需要它（不弹授权框的截屏）
 *
 * 此前的取图只有 MediaProjection 一条路：`ScreenCaptureService` 每抓一帧都要一次
 * 系统「开始截屏」授权（授权不可持久化，抓完必须 `stop()`，否则状态栏常驻截屏标记）。
 * 结果是「截屏 → 看画面 → 点一下 → 再截一张」这种闭环每步都被系统弹框打断，
 * 顺带把「替用户操作手机」这条路一起堵死了。
 *
 * 无障碍通道把这两件事一次解决：`takeScreenshot()` 零弹窗、原生分辨率，
 * `dispatchGesture()` 直接注入点击/滑动，`rootInActiveWindow` 给出控件文案与坐标。
 * 代价是**用户要在系统设置里手动开一次**（一次性，且可随时关掉）——
 * 比每次弹授权框好得多，也是所有手机自动化 App 的通用做法。
 *
 * ## 本类刻意「薄」
 *
 * 这里只维护**实例引用**（谁在跑）与生命周期，全部能力实现放在 [LabAccessibility]
 * 与 [ScreenTree]。理由：`AccessibilityService` 的方法必须在主线程/特定回调里跑，
 * 而工具是在 `ai-turn-worker` 线程上同步调用的 —— 把「跨线程 + 超时 + 结果交接」
 * 收在一处，服务类就不会长成一坨又难测又难查的胶水。
 *
 * ## 两个生命周期细节
 *
 * 1. `instance` 必须在 [onUnbind] **和** [onDestroy] 都清掉：用户在系统设置里关掉服务时
 *    走的是 `onUnbind`，进程被杀走的是 `onDestroy`。只清一处的话，关掉服务后
 *    [LabAccessibility] 仍认为「服务在」，于是调用会一直等到超时才失败 ——
 *    表现为「关掉无障碍之后 AI 反应变慢」，且日志上看不出来。
 * 2. [onServiceConnected] 里**再设一次** `serviceInfo` 的 flag：XML 声明是权威来源，
 *    但 `FLAG_RETRIEVE_INTERACTIVE_WINDOWS` 若因清单被改漏而丢，多窗口下的读屏会
 *    只拿到当前窗口的一部分，且**不报错**。程序化补齐是这里唯一情愿的"重复"。
 */
class LabAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "LabAccessibilitySvc"

        /**
         * 当前活着的服务实例（null = 用户没开、或刚被关掉/杀掉）。
         *
         * 依赖它的都是**同步阻塞**调用（工具线程等结果），因此这里必须是"能立刻回答"的
         * 引用，而不是去查系统设置 —— 查设置只能回答"用户开着开关吗"，
         * 答不出"现在这个进程里有没有一个能干活的服务实例"。
         */
        @Volatile
        internal var instance: LabAccessibilityService? = null
            private set

        /** 服务此刻是否真的可用（截屏/手势/读屏都要求它为 true） */
        internal fun isConnected(): Boolean = instance != null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        // XML 里已声明同样的 flag；这里补齐是防"清单被改漏后读屏静默变残"（见类注释 2）
        runCatching {
            val info = serviceInfo ?: return@runCatching
            info.flags = info.flags or
                android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                android.accessibilityservice.AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            serviceInfo = info
        }.onFailure { Log.w(TAG, "set serviceInfo flags failed: ${it.message}") }
        Log.i(TAG, "accessibility service connected")
    }

    /**
     * 事件回调：**刻意什么都不做**。
     *
     * 所有能力都是工具触发的同步查询（读 UI 树走 `rootInActiveWindow`，不靠事件累积），
     * 所以这里既不缓存节点、也不做防抖 —— 一旦这里开始处理事件，就会出现"读屏结果依赖
     * 事件到达时序"的偶发问题，而事件时序在低端机上完全不可控。
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) instance = null
        Log.i(TAG, "accessibility service unbound (user turned it off?)")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        Log.i(TAG, "accessibility service destroyed")
        super.onDestroy()
    }
}
