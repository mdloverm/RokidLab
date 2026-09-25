package com.rokidlab.phone.access

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * 无障碍能力的**唯一工具侧入口**：状态判定 / 截屏 / 手势 / 输入。
 *
 * ## 分工
 *  - [LabAccessibilityService]：实例与生命周期；
 *  - 本文件：跨线程调用、超时、把系统的回调/错误码翻译成**人话**；
 *  - [ScreenTree]：读 UI 树、编号表、按文案找控件。
 *
 * ## 三个必须知道的平台约束（都在代码里兜住了，别当成 bug 重复修）
 *
 * 1. **无障碍截屏有最小间隔**：AOSP 侧是
 *    `ACCESSIBILITY_TAKE_SCREENSHOT_REQUEST_INTERVAL_TIMES_MS = 333ms`（`@hide` 常量，
 *    但 CTS 用例 `AccessibilityTakeScreenshotTest` 就是按它断言的）。调用太频繁会拿到
 *    [AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT]。
 *    处理方式：本地节流把两次请求拉开到 333ms 以上，并且**只对这个错误码重试一次**
 *    —— 重试其它错误码只是把同一个失败要等两遍。
 * 2. **拿到的画面是 HardwareBuffer 支撑的**：`wrapHardwareBuffer` 返回的 Bitmap 在
 *    buffer 关掉后就无效，所以必须**先 copy 成软件位图**再 `close()`。
 *    顺序写反的表现是"截图是全黑/花屏"，而且只在部分机型上复现。
 * 3. **手势回调在主线程**：`dispatchGesture` 的完成回调走我们传进去的 Handler，
 *    因此工具线程要拿到结果必须靠 latch 等（同 [takeScreenshot]）。
 *
 * ## 为什么所有方法都是"阻塞 + 超时"而不是挂起
 * 调用方是 `ToolRegistry.execute` 的同步分支（跑在 `ai-turn-worker` 上），
 * 而且**同一时刻只允许一个手势/一次截屏**在飞 —— 用挂起会把"串行"这件事交给调用方去保证，
 * 而工具之间是并发执行的（同一轮里模型可能一次调好几个工具）。
 */
internal object LabAccessibility {

    private const val TAG = "LabAccessibility"

    /** 无障碍截屏的最小请求间隔（见类注释 1：AOSP 侧 333ms，这里多给 30ms 余量） */
    private const val MIN_INTERVAL_MS = 363L

    /** 截图最长边：与 MediaProjection 那条路（`ScreenCaptureService.MAX_EDGE`）保持一致，
     *  否则同一个 `capture_screen` 工具会因为走哪条路而给出两种体量的 base64 */
    private const val MAX_EDGE = 1440
    private const val JPEG_QUALITY = 85

    /** 手势完成后等回调的上限（正常 10~300ms） */
    private const val GESTURE_TIMEOUT_MS = 3_000L

    // ═══════════════════ 状态判定 ═══════════════════

    /** 服务实例是否在线（截屏/手势/读屏的实际前提） */
    fun isConnected(): Boolean = LabAccessibilityService.isConnected()

    /**
     * 用户是否**在系统设置里开着**这个无障碍服务。
     *
     * 与 [isConnected] 的区别：开关开着但服务还没连上（刚打开设置页返回、或系统刚拉起进程）
     * 时为 true，此时"再等一下重试"就能成，而不是引导用户去开一遍已经开着的东西。
     * 因此权限登记（[com.rokidlab.phone.permission.AppPermission.ACCESSIBILITY]）读这里，
     * 工具执行前读 [isConnected]。
     *
     * 用 `AccessibilityManager.getEnabledAccessibilityServiceList` 而不是直接读
     * `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`：后者是"读系统设置"，
     * 在新版本上属于受限读取；前者是 AOSP 提供的公开查询接口。
     */
    fun isEnabled(context: Context): Boolean {
        if (isConnected()) return true
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            ?: return false
        val target = ComponentName(context.packageName, LabAccessibilityService::class.java.name)
        return runCatching {
            am.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any { ComponentName.unflattenFromString(it.id.orEmpty()) == target }
        }.getOrDefault(false)
    }

    // ═══════════════════ 截屏 ═══════════════════

    /** 一次取图的结果 */
    sealed interface Shot {
        data class Ok(val jpeg: ByteArray, val width: Int, val height: Int) : Shot

        /**
         * @param retryable true = 只是"问得太勤"，隔一会儿重发就能成（见类注释 1）
         */
        data class Fail(val reason: String, val retryable: Boolean = false) : Shot
    }

    @Volatile
    private var lastShotAt = 0L

    /** 串行 + 节流用的锁（同一时刻只允许一次无障碍截屏在飞） */
    private val shotLock = Any()

    /**
     * 抓一帧当前屏幕（阻塞至多 [timeoutMs]，含节流等待与一次重试）。
     *
     * 失败一律返回**具体原因**（版本不支持 / 服务没开 / 问得太勤 / 系统内部错误），
     * 由 [com.rokidlab.phone.ai.ScreenCaptureTools] 决定是否继续走后面的兜底路径。
     */
    fun takeScreenshot(timeoutMs: Long = 6_000L): Shot {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return Shot.Fail("系统版本低于 Android 11：无障碍截屏需要 Android 11 及以上")
        }
        val service = LabAccessibilityService.instance
            ?: return Shot.Fail("无障碍服务没有连上（用户没开，或刚被关掉）")
        synchronized(shotLock) {
            var last: Shot = Shot.Fail("没有执行")
            // 只在"问得太勤"时补第二次；其它错误重试只是把同一个失败等两遍
            for (attempt in 0 until 2) {
                val wait = MIN_INTERVAL_MS - (System.currentTimeMillis() - lastShotAt)
                if (wait > 0) sleepQuietly(wait)
                // 显式转换而不是 `is` + 智能转换：失败分支要读 retryable，
                // 而这里写 `if (result is Shot.Ok) …` 后编译器不肯把 result 收窄（报 Unresolved reference: retryable）
                val result = takeOnce(service, timeoutMs)
                (result as? Shot.Ok)?.let { return it }
                val fail = result as Shot.Fail
                last = fail
                if (!fail.retryable) return fail
                Log.i(TAG, "screenshot too frequent, retry once (attempt=$attempt)")
            }
            return last
        }
    }

    private fun takeOnce(service: AccessibilityService, timeoutMs: Long): Shot {
        val latch = CountDownLatch(1)
        // CountDownLatch 的 countDown/await 之间有 happens-before 保证，回调线程写、这里读是安全的
        var result: Shot? = null
        val callback = object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                lastShotAt = System.currentTimeMillis()
                result = runCatching { toJpeg(screenshot) }
                    .onFailure { Log.w(TAG, "convert screenshot failed: ${it.message}", it) }
                    .getOrElse { Shot.Fail("画面转换失败：${it.message}") }
                latch.countDown()
            }

            override fun onFailure(errorCode: Int) {
                lastShotAt = System.currentTimeMillis()
                result = Shot.Fail(
                    reason = describeError(errorCode),
                    retryable = errorCode == AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT,
                )
                latch.countDown()
            }
        }
        val executor = Executor { r -> Handler(Looper.getMainLooper()).post(r) }
        try {
            service.takeScreenshot(Display.DEFAULT_DISPLAY, executor, callback)
        } catch (e: Exception) {
            Log.w(TAG, "takeScreenshot call failed: ${e.message}", e)
            return Shot.Fail("调用无障碍截屏失败：${e.message}")
        }
        val done = runCatching { latch.await(timeoutMs, TimeUnit.MILLISECONDS) }.getOrDefault(false)
        if (!done) {
            Log.w(TAG, "accessibility screenshot timeout after ${timeoutMs}ms")
            return Shot.Fail("等了 ${timeoutMs}ms 没等到屏幕画面")
        }
        return result ?: Shot.Fail("系统没有返回截屏结果")
    }

    /**
     * `ScreenshotResult` → JPEG。
     *
     * `hardwareBuffer` 必须在本函数返回前关掉（否则泄漏一块图形内存，多次截图后
     * 系统会开始拒绝分配），但又必须**先**把像素拷出来（见类注释 2）。
     */
    private fun toJpeg(result: AccessibilityService.ScreenshotResult): Shot {
        val buffer = result.hardwareBuffer
        try {
            val wrapped = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                ?: return Shot.Fail("拿到的高清画面无法解析")
            val software = wrapped.copy(Bitmap.Config.ARGB_8888, false)
                ?: return Shot.Fail("画面复制失败（内存不足？）")
            val scaled = scaleDown(software)
            return try {
                val jpeg = ByteArrayOutputStream().use { out ->
                    scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                    out.toByteArray()
                }
                Shot.Ok(jpeg, scaled.width, scaled.height)
            } finally {
                if (scaled !== software) scaled.recycle()
                software.recycle()
            }
        } finally {
            runCatching { buffer.close() }
        }
    }

    /** 等比缩到最长边 [MAX_EDGE] 以内（小于上限时原样返回，不放大） */
    private fun scaleDown(src: Bitmap): Bitmap {
        val maxEdge = maxOf(src.width, src.height)
        if (maxEdge <= MAX_EDGE) return src
        val scale = MAX_EDGE.toFloat() / maxEdge
        val w = (src.width * scale).toInt().coerceAtLeast(1)
        val h = (src.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, w, h, true)
    }

    /** 错误码 → 人话（写给模型看，也写给排查时看日志的人看） */
    private fun describeError(code: Int): String = when (code) {
        AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT ->
            "距上一张截屏太近（系统要求至少间隔约 0.33 秒）"
        AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS ->
            "无障碍服务没有截屏能力：请在系统设置里把「乐奇实验室」无障碍服务关掉再重新打开一次"
        AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY ->
            "系统拒绝了这块屏幕（无效的显示 id）"
        AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERNAL_ERROR ->
            "系统内部错误（ROM 侧截屏实现异常）"
        else -> "系统拒绝了截屏（errorCode=$code）"
    }

    // ═══════════════════ 手势（点击 / 滑动） ═══════════════════

    /** 点一下某个坐标：无障碍手势注入（不进 `input` 命令、不需要 root/ADB） */
    fun tap(x: Int, y: Int): Boolean = gesture(TAP_DURATION_MS) { it.moveTo(x.toFloat(), y.toFloat()) }

    /** 从 (x1,y1) 划到 (x2,y2)（滚动、翻页、抽屉手势都用它） */
    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean =
        gesture(durationMs.coerceIn(60L, 3_000L)) { it.moveTo(x1.toFloat(), y1.toFloat()); it.lineTo(x2.toFloat(), y2.toFloat()) }

    /**
     * 点一个 UI 节点：优先 `ACTION_CLICK`（最接近用户点击，能穿过遮挡/滚动容器），
     * 没有可点祖先时退化为点它的中心坐标。
     *
     * 为什么不是所有情况都直接点坐标：坐标点击会被"半透明浮层""刚好滚出屏幕"骗到，
     * 而 `ACTION_CLICK` 是交给控件自己处理的。反过来，自绘 UI（WebView/游戏/Flutter）
     * 常常整棵树都没有 `clickable=true`，只能退坐标 —— 两条都要有。
     */
    fun clickNode(node: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth < 6) {
            if (current.isClickable && current.isEnabled) {
                if (current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            }
            current = runCatching { current.parent }.getOrNull()
            depth++
        }
        val bounds = Rect().also { node.getBoundsInScreen(it) }
        if (bounds.width() <= 0 || bounds.height() <= 0) return false
        return tap(bounds.centerX(), bounds.centerY())
    }

    private const val TAP_DURATION_MS = 60L

    private fun gesture(durationMs: Long, build: (Path) -> Unit): Boolean {
        val service = LabAccessibilityService.instance ?: return false
        val path = Path().apply(build)
        val description = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs))
            .build()
        val latch = CountDownLatch(1)
        var completed = false
        val callback = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                completed = true
                latch.countDown()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                completed = false
                latch.countDown()
            }
        }
        val dispatched = try {
            service.dispatchGesture(description, callback, Handler(Looper.getMainLooper()))
        } catch (e: Exception) {
            Log.w(TAG, "dispatchGesture failed: ${e.message}", e)
            false
        }
        if (!dispatched) {
            // 常见原因：另一次手势还在飞（`dispatchGesture` 返回 false 而不抛异常）
            Log.w(TAG, "dispatchGesture rejected (another gesture in flight?)")
            return false
        }
        runCatching { latch.await(GESTURE_TIMEOUT_MS, TimeUnit.MILLISECONDS) }
        return completed
    }

    // ═══════════════════ 按键（全局动作） ═══════════════════

    /**
     * 支持的按键（都是 AOSP 全局动作，不依赖厂商 ROM 私有能力）。
     *
     * 刻意**不含**锁屏/关机/重启、也不含 `GLOBAL_ACTION_TAKE_SCREENSHOT`：
     *  - 锁屏之后模型自己解不开（本 App 没有也不是密码保管者），加了它只会造出"把用户手机锁住"；
     *  - 系统截屏会把图存进相册（写用户存储、留隐私痕迹），而我们的 [takeScreenshot] 不落地。
     */
    enum class Key(val action: Int, val label: String) {
        BACK(AccessibilityService.GLOBAL_ACTION_BACK, "返回"),
        HOME(AccessibilityService.GLOBAL_ACTION_HOME, "回桌面"),
        RECENTS(AccessibilityService.GLOBAL_ACTION_RECENTS, "最近任务"),
        NOTIFICATIONS(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS, "下拉通知栏"),
        QUICK_SETTINGS(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS, "下拉快捷开关"),
        ;

        companion object {
            /** `back` / `home` / `recents` / `notifications` / `quick_settings` → 枚举 */
            fun parse(raw: String): Key? = when (raw.trim().lowercase()) {
                "back" -> BACK
                "home" -> HOME
                "recents", "recent", "overview" -> RECENTS
                "notifications", "notification" -> NOTIFICATIONS
                "quick_settings", "settings" -> QUICK_SETTINGS
                else -> null
            }

            val supported: String = values().joinToString(", ") { it.name.lowercase() }
        }
    }

    fun pressKey(key: Key): Boolean {
        val service = LabAccessibilityService.instance ?: return false
        return runCatching { service.performGlobalAction(key.action) }
            .onFailure { Log.w(TAG, "performGlobalAction(${key.name}) failed: ${it.message}") }
            .getOrDefault(false)
    }

    // ═══════════════════ 文本输入 ═══════════════════

    /** 输入文本的结果（密码框拒绝是**能力边界**，不是失败，所以单列一档） */
    sealed interface Typing {
        data object Ok : Typing

        /** 当前没有聚焦的输入框 */
        data object NoFocus : Typing

        /** 聚焦的不是可编辑控件 */
        data object NotEditable : Typing

        /** 聚焦的是密码框 —— 我们**不做**代填密码（见 [typeText] 注释） */
        data object Password : Typing

        data class Fail(val reason: String) : Typing
    }

    /**
     * 往**当前聚焦**的输入框写文本。
     *
     * ## 为什么不代填密码
     * 与「浏览器里让用户自己登录」（`browser_open` 的 schema 注释）同一条口径：
     * 代填密码意味着本 App 要拿到密码明文，而用户对"语音助手"的心理预期不是"记住我的密码"。
     * 因此密码框**无条件拒绝**（连 `confirmed=true` 也不行），只如实告诉模型
     * "让用户自己在手机上输入"。这不是安全策略闸门，是能力边界的自我声明。
     */
    fun typeText(text: String): Typing {
        if (text.isEmpty()) return Typing.Fail("要输入的内容是空的")
        val service = LabAccessibilityService.instance
            ?: return Typing.Fail("无障碍服务没有连上")
        val root = service.rootInActiveWindow ?: return Typing.NoFocus
        val focused = runCatching { root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull()
            ?: return Typing.NoFocus
        if (!focused.isEditable) return Typing.NotEditable
        if (focused.isPassword) return Typing.Password
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        val ok = runCatching { focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args) }
            .onFailure { Log.w(TAG, "ACTION_SET_TEXT failed: ${it.message}") }
            .getOrDefault(false)
        return if (ok) {
            Typing.Ok
        } else {
            // 自绘/网页输入框（WebView、Flutter、部分游戏引擎）不响应 ACTION_SET_TEXT
            Typing.Fail("这个输入框不接受直接写入（可能是网页或自绘控件），请在手机上自己输入")
        }
    }

    private fun sleepQuietly(ms: Long) {
        runCatching { Thread.sleep(ms) }
    }
}
