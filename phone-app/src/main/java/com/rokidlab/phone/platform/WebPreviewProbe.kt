package com.rokidlab.phone.platform

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * WebPreviewProbe —— start_web_preview 的**无头加载校验**（生成质量关键招）。
 *
 * ## 为什么必须有这一步
 * 之前 [WebPreviewManager] 的健康检查只确认「端口通了」（TCP 层），确认不了「页面真的渲染」：
 * 白屏、JS 报错、CSS/JS 资源 404 这些**最常见的小毛病**模型全都看不见 —— 它只能
 * capture_screen 目视或 curl 抓 HTML 猜，根因是模型缺一条「渲染反馈」通道。
 * 这里用一台**即抛即毁的无头 WebView** 实际加载一次页面，把运行时结果（加载成败 /
 * JS console 错误 / 资源加载失败）拼进工具结果回传 —— 模型第一次能看到自己写的页面
 * 的运行时错误，修完再交付。
 *
 * ## 线程模型（⚠️ WebView 只能在主线程创建与销毁）
 * 工具跑在生成协程的后台线程上：这里 post 到主线程创建 WebView 并加载，调用方用
 * [CountDownLatch] 阻塞等待；页面加载完成后再多等一拍（晚到的 console 错误要落进报告），
 * 总超时兜底后照样返回 —— 宁可报「加载超时」也不卡死工具循环。
 *
 * ## 副作用与隐私
 * 只访问 127.0.0.1 的自起服务（与用户点开预览等价的 GET）；WebView 加载完立即
 * destroy，不留常驻 UI，cookie 不做持久化。
 */
internal object WebPreviewProbe {

    /** 页面加载的最长等待 */
    private const val PAGE_TIMEOUT_MS = 8_000L

    /** onPageFinished 后再多等一拍，收晚到的 console 错误 */
    private const val SETTLE_MS = 1_200L

    /** 调用方（IO 线程）的总等待上限：略大于内部所有定时之和 */
    private const val AWAIT_MS = PAGE_TIMEOUT_MS + SETTLE_MS + 3_000L

    /** 每类错误最多收录条数（防框架刷屏把工具结果撑爆） */
    private const val MAX_ITEMS = 10

    private const val MAX_ITEM_CHARS = 300

    /** 一份探测报告 */
    data class Report(
        /** onPageFinished 正常到达且主框无错误 */
        val loaded: Boolean,
        /** 主框加载失败的原因（null = 主框没有报错） */
        val mainFrameError: String?,
        val jsErrors: List<String>,
        val resourceErrors: List<String>,
        /** 到了总超时页面还没加载完 */
        val timeout: Boolean,
    ) {
        /** 给模型看的校验报告（拼进 start_web_preview 的返回；无错误时不加空段） */
        fun format(): String = buildString {
            append("[自动加载校验]")
            when {
                mainFrameError != null -> append("\n页面加载：失败（$mainFrameError）。请先修复再交付。")
                timeout -> append("\n页面加载：超时（${PAGE_TIMEOUT_MS / 1000}s 内未完成）。可能页面仍在加载，请自行判断。")
                loaded -> append("\n页面加载：成功")
                else -> append("\n页面加载：未完成")
            }
            if (jsErrors.isNotEmpty()) {
                append("\nJS 错误 ${jsErrors.size} 条：")
                jsErrors.forEachIndexed { i, e -> append("\n  ${i + 1}. $e") }
            }
            if (resourceErrors.isNotEmpty()) {
                append("\n资源加载失败 ${resourceErrors.size} 条：")
                resourceErrors.forEachIndexed { i, e -> append("\n  ${i + 1}. $e") }
            }
        }
    }

    /**
     * 实际加载一次 [url] 并收集运行时错误。**阻塞调用**（最长约 [AWAIT_MS]），须在后台线程调。
     */
    fun probe(context: Context, url: String): Report {
        val latch = CountDownLatch(1)
        val result = AtomicReference<Report?>(null)
        val webRef = AtomicReference<WebView?>(null)
        Handler(Looper.getMainLooper()).post {
            runCatching { probeOnMain(context, url, latch, result, webRef) }
                .onFailure {
                    result.set(Report(false, "探测异常：${it.message}", emptyList(), emptyList(), false))
                    latch.countDown()
                }
        }
        val done = latch.await(AWAIT_MS, TimeUnit.MILLISECONDS)
        // 兜底清理：万一主线程回调链没走完（如 latch 恰好超时），也要销毁 WebView
        webRef.getAndSet(null)?.let { w ->
            Handler(Looper.getMainLooper()).post { runCatching { w.destroy() } }
        }
        return result.get() ?: Report(false, null, emptyList(), emptyList(), timeout = !done)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun probeOnMain(
        context: Context,
        url: String,
        latch: CountDownLatch,
        result: AtomicReference<Report?>,
        webRef: AtomicReference<WebView?>,
    ) {
        val jsErrors = mutableListOf<String>()
        val resourceErrors = mutableListOf<String>()
        var mainFrameError: String? = null
        var finished = false

        val main = Handler(Looper.getMainLooper())
        val webView = WebView(context.applicationContext)
        webRef.set(webView)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            // ⚠️ 本机服务永远该拿新鲜内容：缓存会留下条目，之后用户点开预览若命中
            // （http.server 带 Last-Modified ⇒ 启发式新鲜期内不发一个网络包），
            // 既掩盖改动又引入"零网络请求却黑屏"这类难排查路径。见 2026-10-13 排查。
            cacheMode = WebSettings.LOAD_NO_CACHE
        }

        fun finish() {
            if (result.get() != null) return
            webRef.getAndSet(null)?.destroy()
            result.set(
                Report(
                    loaded = finished && mainFrameError == null,
                    mainFrameError = mainFrameError,
                    jsErrors = jsErrors.toList(),
                    resourceErrors = resourceErrors.toList(),
                    timeout = !finished,
                ),
            )
            latch.countDown()
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, u: String) {
                if (finished) return
                finished = true
                // 多等一拍：onPageFinished 时脚本可能刚抛错还没打到 console
                main.postDelayed({ finish() }, SETTLE_MS)
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    if (mainFrameError == null) {
                        mainFrameError = "${error.errorCode} ${error.description}"
                        finish() // 主框失败不必再等 settle
                    }
                } else if (resourceErrors.size < MAX_ITEMS) {
                    resourceErrors.add("${request.url} → ${error.errorCode}")
                }
            }

            override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                if (response.statusCode < 400) return
                if (request.isForMainFrame) {
                    if (mainFrameError == null) {
                        mainFrameError = "HTTP ${response.statusCode}"
                        finish()
                    }
                } else if (resourceErrors.size < MAX_ITEMS) {
                    resourceErrors.add("${request.url} → HTTP ${response.statusCode}")
                }
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                if (consoleMessage.messageLevel() == ConsoleMessage.MessageLevel.ERROR && jsErrors.size < MAX_ITEMS) {
                    val src = consoleMessage.sourceId()?.substringAfterLast('/')
                    val loc = if (src.isNullOrBlank()) "" else "（$src:${consoleMessage.lineNumber()}）"
                    jsErrors.add((consoleMessage.message() + loc).take(MAX_ITEM_CHARS))
                }
                return true
            }
        }
        webView.loadUrl(url)
        // 总超时：页面迟迟不完成也照样返回
        main.postDelayed({ finish() }, PAGE_TIMEOUT_MS)
    }
}
