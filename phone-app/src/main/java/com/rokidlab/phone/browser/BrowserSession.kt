package com.rokidlab.phone.browser

import android.content.Context
import android.content.Intent
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 手机端浏览器的**唯一进程内入口**（WebView 的持有者与线程桥）。
 *
 * ## 为什么需要这一层
 * 工具跑在 worker 线程，而 WebView 只能在主线程碰。两者之间需要一个"发指令 → 等结果"的桥，
 * 并且这个桥必须知道**当前是哪一个 Activity**（用户可能手动关掉它）。
 * 所以状态放在这里（弱引用持 Activity），而不是让每个工具自己去 `findViewById`。
 *
 * ## 线程纪律
 * [evalBlocking] / [waitForSettled] / [open] 会**阻塞**调用线程（最多十几秒），只允许从
 * worker 线程调用；[WebView] 侧的动作全部经 `runOnUiThread` 投递。在主线程调用会直接抛错，
 * 而不是把界面冻住十几秒后由用户发现。
 */
internal object BrowserSession {

    private const val TAG = "BrowserSession"

    /** 拉起 Activity 并等 WebView 建好的上限 */
    private const val ACTIVITY_START_TIMEOUT_MS = 8000L

    /** 单次 JS 求值超时（页面卡死/主线程繁忙时的兜底） */
    private const val EVAL_TIMEOUT_MS = 6000L

    /** 页面加载完成后的确认轮询上限 */
    private const val SETTLE_TIMEOUT_MS = 15000L

    private var activityRef: WeakReference<BrowserActivity>? = null

    @Volatile
    private var webViewReady = false

    internal fun attach(activity: BrowserActivity) {
        activityRef = WeakReference(activity)
        webViewReady = false
    }

    internal fun markWebViewReady() {
        webViewReady = true
    }

    internal fun detach(activity: BrowserActivity) {
        if (activityRef?.get() === activity) {
            activityRef = null
            webViewReady = false
        }
    }

    /** 浏览器页面是否在屏 */
    fun isOpen(): Boolean = activityRef?.get()?.let { !it.isFinishing } == true

    /** 当前页面 URL；没有浏览器时 null */
    fun currentUrl(): String? = activityRef?.get()?.currentUrl()

    /**
     * 打开一个网址：浏览器已在则就地导航，不在则拉起页面再导航。
     *
     * @return true = 导航已发起（不代表页面已加载完，加载完成由 [waitForSettled] 判定）
     */
    fun open(ctx: Context, url: String): Boolean {
        val live = activityRef?.get()
        if (live != null && !live.isFinishing) {
            live.runOnUiThread { live.loadUrl(url) }
            return true
        }
        val app = ctx.applicationContext
        try {
            app.startActivity(
                BrowserActivity.intent(app, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (e: Exception) {
            Log.e(TAG, "启动浏览器失败: $url", e)
            return false
        }
        val deadline = System.currentTimeMillis() + ACTIVITY_START_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (webViewReady) return true
            Thread.sleep(120)
        }
        Log.w(TAG, "浏览器在 ${ACTIVITY_START_TIMEOUT_MS}ms 内未就绪")
        return false
    }

    /** 关闭浏览器页面（无页面时静默返回） */
    fun close() {
        activityRef?.get()?.let { a -> a.runOnUiThread { if (!a.isFinishing) a.finish() } }
    }

    /** 后退一页；返回 false = 没有上一页可退 */
    fun goBack(): Boolean {
        val a = activityRef?.get() ?: return false
        if (!a.canGoBack()) return false
        a.runOnUiThread { a.goBack() }
        return true
    }

    /**
     * 阻塞求值一段 JS，返回它的**字符串结果**（`runtime.evaluate` 语义）。
     *
     * JS 侧请用 `JSON.stringify(...)` 把结构化结果变成字符串：evaluateJavascript 的
     * 回调值是 JSON 字面量，[decodeJsString] 负责把引号与转义还原成原字符串。
     *
     * @return null = 没有浏览器 / 超时 / 求值抛异常（调用方按"读不到"处理，不要当成空页面）
     */
    fun evalBlocking(js: String, timeoutMs: Long = EVAL_TIMEOUT_MS): String? {
        requireNotMainThread("evalBlocking")
        val a = activityRef?.get() ?: return null
        val latch = CountDownLatch(1)
        val out = AtomicReference<String?>(null)
        a.runOnUiThread {
            try {
                a.webView.evaluateJavascript(js) { r ->
                    out.set(r)
                    latch.countDown()
                }
            } catch (e: Exception) {
                Log.w(TAG, "evaluateJavascript 失败: ${e.message}")
                latch.countDown()
            }
        }
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            Log.w(TAG, "evaluateJavascript 超时（${timeoutMs}ms）")
            return null
        }
        return decodeJsString(out.get())
    }

    /**
     * 等页面稳定：先给动作一点起步时间（点击触发的跳转不是同步发生的），
     * 再连续两次读到 `document.readyState === 'complete'` 才算稳定。
     *
     * ⚠️ 等不到也不代表失败：SPA 常常"没导航但内容已换"，此时快照照样能拿到新内容；
     * 反过来若服务器慢，可能读到的仍是旧页面 —— 所以模型侧允许再调一次 browser_snapshot。
     */
    fun waitForSettled(settleMs: Long = 900L, timeoutMs: Long = SETTLE_TIMEOUT_MS): Boolean {
        requireNotMainThread("waitForSettled")
        Thread.sleep(settleMs)
        val deadline = System.currentTimeMillis() + timeoutMs
        var completeStreak = 0
        while (System.currentTimeMillis() < deadline) {
            val state = evalBlocking("document.readyState", 2000L)
            if (state == "complete") {
                completeStreak++
                if (completeStreak >= 2) return true
            } else {
                completeStreak = 0
            }
            Thread.sleep(250)
        }
        return false
    }

    private fun requireNotMainThread(what: String) {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "$what 会阻塞调用线程，不能在主线程调用"
        }
    }

    /**
     * 把 evaluateJavascript 的原始回调值还原成字符串。
     *
     * 回调值是 **JSON 字面量**：`"complete"` / `"{\"a\":1}"` / `null`。
     * 借助 `JSONArray("[...]")` 借用 org.json 的字符串反转义，避免手写转义表
     * （手写很容易漏掉 `\uXXXX` 与代理对）。
     */
    internal fun decodeJsString(raw: String?): String? {
        if (raw == null || raw == "null") return null
        return runCatching { JSONArray("[$raw]").optString(0) }.getOrNull()
    }
}
