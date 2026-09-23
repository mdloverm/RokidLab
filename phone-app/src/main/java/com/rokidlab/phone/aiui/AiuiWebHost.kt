package com.rokidlab.phone.aiui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.rokidlab.phone.ai.ToolGateway
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 手机端 AIUI 演示宿主：把 Lab 生成的 .aix 用官方 @yodaos-pkg/ink（browser-integration
 * + wasm）渲染在手机屏上，用于「先在手机上看看它长什么样 / 拿手指操作一遍」。
 *
 * 为什么要有它：.aix 的目标运行环境是眼镜，但生成一个候选版本要反复调；每次都推眼镜、
 * 戴上看、再回来改，链路长且打断。手机上跑同一个 ink 引擎 == 同一份渲染结果，
 * 让迭代发生在手机上，确认后再推眼镜。
 *
 * 与眼镜端 [AiuiLinkActivity] 的关系：**同一套宿主协议、同一套资产**（`assets/ink/` 下的
 * 全部文件，内容逐字节相同）。差别只有一处 —— 工具上行：
 *  - 眼镜端：RFCOMM 推给手机（`AsrPushServer.pushControl`），手机执行后回传；
 *  - 手机端：页面与工具同进程，直接调 [ToolGateway.call]。
 * 其余（`__lab/` 各端点、导航锁、boot 时序、bundle 装配、lab-page-bridge 注入）刻意保持一致，
 * 否则「手机上演示」就不再等于「眼镜上看到的样子」。
 *
 * ⚠️ 设计尺寸不等于控件尺寸：[designWidth] × [designHeight]（默认 480×640，AIUI 页面的
 * 设计基准）决定**页面视口**，也就是 ink 渲染的画布逻辑尺寸；控件本身可以任意大小
 * （对话内卡片预览很小、全屏 Activity 很大），Canvas 由 CSS 铺满控件。所以预览与全屏
 * 看到的是同一份布局，只是缩放比不同。
 *
 * 调用方须在 View 销毁时调 [destroy]。
 */
@SuppressLint("SetJavaScriptEnabled")
internal class AiuiWebHost(
    private val ctx: Context,
    private val aixFile: File,
    launchParams: String?,
    private val designWidth: Int = DESIGN_WIDTH,
    private val designHeight: Int = DESIGN_HEIGHT,
    private val onReady: () -> Unit = {},
    private val onLoadError: (String) -> Unit = {},
    private val onCloseRequested: () -> Unit = {},
) {
    companion object {
        private const val TAG = "AiuiWebHost"

        /** AIUI 页面的设计基准（见 aiui-dev 技能：页面按 480×640 全屏设计） */
        const val DESIGN_WIDTH = 480
        const val DESIGN_HEIGHT = 640

        /**
         * 宿主自己的虚拟 origin。页面所有同源资源（index.html / host.js / index.js /
         * bundle.json、`__lab/` 各端点）都由 [shouldInterceptRequest] 凭空供给，**不进网络栈**
         * （见 AndroidManifest 的 networkSecurityConfig —— 不需要为它开口子）。
         */
        private const val BASE_URL = "https://ink.local/"

        /**
         * 同步工具调用最长阻塞时间。
         *
         * **必须显著大于 [ToolGateway] 自身的超时**：两侧窗口相等时宿主几乎总是先超时
         * （它的计时从页面发起算起，而网关从收到后 join 算起），于是真正有用的错误
         * （限流、参数非法、执行异常）永远回不到页面，用户只看到笼统的 "tool timed out"。
         */
        private const val SYNC_TOOL_TIMEOUT_MS = 25_000L

        /** 工具结果缓存 TTL：页面轮询窗口内有效即可 */
        private const val TOOL_RESULT_TTL_MS = 30_000L
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private var bundleJson: String? = null
    private var booted = false

    /** host.js 模块已执行完毕（`__aiuiHost` 就绪），避免 bundle 先就绪时漏 boot */
    private var jsHostReady = false

    private var currentAppId: String? = null

    /** 本次启动的启动参数（JSON 对象字符串），页面 boot 完成后作为首条 hostMessage 下发 */
    @Volatile
    private var launchParamsJson: String? = launchParams?.takeIf { it.isNotBlank() }

    /** 页面 realm 的 Lab 桥（assets/ink/lab-page-bridge.js），读一次缓存 */
    private var pageBridgeJs: String? = null

    private var destroyed = false

    /** 供外部（全屏切换、对话框关闭）判定是否已完成首帧 */
    val isBooted: Boolean get() = booted

    private val bridge = object {
        @JavascriptInterface
        fun hostReady() {
            jsHostReady = true
            mainHandler.post { maybeBoot() }
        }

        @JavascriptInterface
        fun ready() {
            Log.i(TAG, "ink view ready")
            // host.js 在 startRendering() 之后回传，这是"已出首帧"的唯一信号：
            // 卡片靠它把"渲染中…"占位换成真正的画面。
            runOnMain { onReady() }
        }

        @JavascriptInterface
        fun closeRequested() {
            runOnMain { onCloseRequested() }
        }

        @JavascriptInterface
        fun closed() {
            runOnMain { onCloseRequested() }
        }

        @JavascriptInterface
        fun error(msg: String) {
            Log.e(TAG, "js error: $msg")
            runOnMain { onLoadError(msg) }
        }

        @JavascriptInterface
        fun log(msg: String) {
            Log.d(TAG, "js: $msg")
        }

        /** 页面调用工具（`window.Lab.callTool` 的底层） */
        @JavascriptInterface
        fun callTool(name: String, argsJson: String, cbId: String) {
            invokeToolCall(name, argsJson, cbId)
        }
    }

    val view: WebView = buildWebView()

    private fun buildWebView(): WebView {
        val wv = WebView(ctx)
        wv.setBackgroundColor(Color.BLACK)

        val ws = wv.settings
        ws.javaScriptEnabled = true
        ws.domStorageEnabled = true
        ws.loadWithOverviewMode = true
        ws.useWideViewPort = true
        ws.cacheMode = WebSettings.LOAD_NO_CACHE
        ws.mediaPlaybackRequiresUserGesture = false
        ws.setSupportZoom(false)
        ws.builtInZoomControls = false
        ws.allowFileAccess = false
        ws.allowContentAccess = false
        ws.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW

        wv.addJavascriptInterface(bridge, "Android")

        wv.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(msg: ConsoleMessage?): Boolean {
                Log.d(TAG, "js[${msg?.messageLevel()}]: ${msg?.message()}")
                return true
            }
        }

        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                maybeBoot()
            }

            /**
             * 只允许宿主自己的 [BASE_URL] 顶层导航。
             *
             * [addJavascriptInterface] 把 `Android` 桥注入了 WebView 的**所有 frame**，
             * 页面一旦被导航到外部站点，对端 JS 即可直接调 `Android.callTool` 触达
             * 本机工具。子资源（图片/字体）不经过本回调，仍可正常加载。
             */
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?,
            ): Boolean {
                val url = request?.url?.toString() ?: return true
                if (!url.startsWith(BASE_URL)) {
                    Log.w(TAG, "block external navigation: $url")
                    return true
                }
                return false
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?,
            ): WebResourceResponse? = intercept(request)
        }

        val html = runCatching {
            ctx.assets.open("ink/index.html").bufferedReader().use { it.readText() }
        }.getOrElse {
            Log.e(TAG, "index.html missing", it)
            ""
        }
        if (html.isNotEmpty()) {
            wv.loadDataWithBaseURL(BASE_URL, html, "text/html", "UTF-8", null)
        }

        // 后台解包 .aix；完成后通知 JS boot
        Thread {
            val bundle = AixBundleReader.read(aixFile)
            currentAppId = bundle.appId
            bundleJson = buildBundleJson(bundle)
            mainHandler.post { maybeBoot() }
        }.apply { name = "aix-unpack"; isDaemon = true }.start()

        return wv
    }

    // ── 资源拦截：宿主资产 + bundle.json + __lab/* 工具通道 ──

    private fun intercept(request: WebResourceRequest?): WebResourceResponse? {
        val url = request?.url?.toString() ?: return null
        if (!url.startsWith(BASE_URL)) {
            // 远程图片代理：ink 的 <image> 由 wasm 侧 fetch 取图，页面 origin 是
            // https://ink.local，拉第三方图站属跨域、对方又不带 CORS 头 ⇒ 直接失败
            // （封面/图片永远空白）。这里用宿主 HTTP 栈代取并补 CORS 头。
            return if (looksLikeRemoteImage(url)) proxyRemoteImage(url) else null
        }
        val assetPath = url.removePrefix(BASE_URL)
        if (assetPath.isEmpty()) return null

        if (assetPath.startsWith("__lab/")) return interceptLab(assetPath, request)

        if (assetPath == "bundle.json") {
            val json = bundleJson ?: "{}"
            return WebResourceResponse(
                "application/json", "UTF-8",
                ByteArrayInputStream(json.toByteArray(Charsets.UTF_8)),
            )
        }
        return try {
            val stream = ctx.assets.open("ink/$assetPath")
            WebResourceResponse(mimeFor(assetPath), null, stream)
        } catch (e: Exception) {
            Log.w(TAG, "asset miss: $assetPath (${e.message})")
            null
        }
    }

    private fun interceptLab(
        assetPath: String,
        request: WebResourceRequest?,
    ): WebResourceResponse {
        val q = request?.url
        return when {
            assetPath.startsWith("__lab/ping") -> textResponse("pong")

            // 对照端点：不挂任何自定义 header 的最小响应体，用于判定响应头是否让 Chromium 挂起
            assetPath.startsWith("__lab/echo") -> WebResourceResponse(
                "text/plain", "UTF-8",
                ByteArrayInputStream("e".toByteArray(Charsets.UTF_8)),
            )

            // 页面 realm 的 Lab 桥轮询结果：缓存里有就返回，否则 pending
            assetPath.startsWith("__lab/tool_result") -> {
                val cb = q?.getQueryParameter("cbId")?.takeIf { it.isNotBlank() }
                val cached = cb?.let { takeCachedToolResult(it) }
                if (cached != null) textResponse(cached)
                else textResponse("""{"status":"pending","cbId":"${cb ?: ""}"}""")
            }

            // 同步工具调用：页面一发 fetch，宿主在此 IO 线程阻塞等结果
            assetPath.startsWith("__lab/tool_call_sync") -> {
                val name = q?.getQueryParameter("name")?.takeIf { it.isNotBlank() }
                    ?: return textResponse("missing name")
                val args = q?.getQueryParameter("args")?.takeIf { it.isNotBlank() } ?: "{}"
                val cbId = q?.getQueryParameter("cbId")?.takeIf { it.isNotBlank() }
                    ?: ("sync_" + System.currentTimeMillis())
                val queue = ArrayBlockingQueue<String>(1)
                syncToolQueues[cbId] = queue
                try {
                    invokeToolCall(name, args, cbId)
                    val result = queue.poll(SYNC_TOOL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    if (result == null) {
                        Log.w(TAG, "tool_call_sync timeout cbId=$cbId")
                        textResponse(toolResultJson(cbId, false, null, "tool timed out"))
                    } else {
                        textResponse(result)
                    }
                } finally {
                    syncToolQueues.remove(cbId)
                }
            }

            // fetch 备用通道：WebResourceResponse 必须同步返回，故立即回 accepted，
            // 真实结果经 onMessage 回传页面。
            assetPath.startsWith("__lab/tool_call") -> {
                val name = q?.getQueryParameter("name")?.takeIf { it.isNotBlank() }
                    ?: return textResponse("missing name")
                val args = q?.getQueryParameter("args")?.takeIf { it.isNotBlank() } ?: "{}"
                val cbId = q?.getQueryParameter("cbId")?.takeIf { it.isNotBlank() }
                    ?: ("fetch_" + System.currentTimeMillis())
                invokeToolCall(name, args, cbId)
                textResponse("accepted:$cbId")
            }

            assetPath.startsWith("__lab/list_tools") -> {
                val cbId = "fetch_" + System.currentTimeMillis()
                invokeToolCall(ToolGateway.LIST_TOOLS, "{}", cbId)
                textResponse("accepted:$cbId")
            }

            else -> textResponse("unknown endpoint: $assetPath")
        }
    }

    // ── 工具上行：直接走本进程的 ToolGateway（不经过蓝牙隧道） ──

    private fun invokeToolCall(name: String, argsJson: String, cbId: String) {
        val args = if (argsJson.isBlank()) "{}" else argsJson
        Thread {
            val res = try {
                ToolGateway.call(ctx.applicationContext, name, args, cbId)
            } catch (t: Throwable) {
                Log.e(TAG, "tool call failed: $name", t)
                ToolGateway.CallResult(false, name, error = t.message ?: t.javaClass.simpleName)
            }
            mainHandler.post { deliverToolResult(cbId, res.ok, res.result, res.error) }
        }.apply { this.name = "aiui-demo-tool"; isDaemon = true }.start()
    }

    /**
     * 工具结果回传页面：缓存一份供轮询端点 / 唤醒同步等待队列，再作为 hostMessage
     * 派发给页面（页面 `onMessage` → `Lab.onHostMessage` 兑现回调）。
     */
    private fun deliverToolResult(cbId: String, ok: Boolean, result: String?, error: String?) {
        if (destroyed) return
        val json = toolResultJson(cbId, ok, result, error)
        cacheToolResult(cbId, json)
        dispatchHostMessage(json)
    }

    private fun toolResultJson(cbId: String, ok: Boolean, result: String?, error: String?): String =
        JSONObject().apply {
            put("type", "toolResult")
            put("cbId", cbId)
            put("ok", ok)
            if (result != null) put("result", result)
            if (error != null) put("error", error)
        }.toString()

    /** 手机侧下发的 host 消息 → 页面 onMessage */
    fun dispatchHostMessage(json: String) {
        runOnMain {
            if (destroyed) return@runOnMain
            val js = "window.__aiuiHost && window.__aiuiHost.hostMessage(${JSONObject.quote(json)})"
            runCatching { view.evaluateJavascript(js, null) }
        }
    }

    /**
     * 合成一个按键（眼镜端的输入模型就是键盘：滑动=方向键、单击=回车）。
     *
     * @param code ink/官方 AiuiKeyMapper 的键名（`ArrowUp` / `Enter` / `Backspace` …）
     * @param action `"down"` / `"up"`
     */
    fun injectKey(code: String, action: String) {
        runOnMain {
            if (destroyed) return@runOnMain
            val js = "window.__aiuiHost && window.__aiuiHost.key(" +
                "${JSONObject.quote(code)},'$action')"
            runCatching { view.evaluateJavascript(js, null) }
        }
    }

    /** 同一包换启动参数（不重启宿主）：页面已 boot 则立刻下发，未 boot 则并入 boot */
    fun updateLaunchParams(json: String?) {
        val next = json?.takeIf { it.isNotBlank() } ?: return
        launchParamsJson = next
        if (booted) deliverLaunchParams()
    }

    fun onResume() {
        runCatching { view.onResume() }
    }

    fun onPause() {
        runCatching { view.onPause() }
    }

    fun destroy() {
        destroyed = true
        mainHandler.removeCallbacksAndMessages(null)
        runCatching {
            view.loadUrl("about:blank")
            view.stopLoading()
        }
        runCatching { view.destroy() }
        syncToolQueues.clear()
        synchronized(toolResultLock) { toolResults.clear() }
    }

    // ── boot 时序：bundle 解包完成 + host.js 就绪，两者齐了才 boot ──

    private fun maybeBoot() {
        if (booted || destroyed || bundleJson == null || !jsHostReady) return
        booted = true
        // launchParams 以 **JSON 字符串**（而非对象字面量）传入：JSONObject.quote 会转义，
        // 页面侧 JSON.parse 还原。避免把内容直接拼进 JS 造成注入/语法破坏。
        val js = "window.__aiuiHost && window.__aiuiHost.boot({" +
            "width:$designWidth,height:$designHeight,bundleUrl:'bundle.json'," +
            "appId:${jsonStr(currentAppId)}," +
            "launchParams:${jsonStr(launchParamsJson)}," +
            "initialPage:null})"
        runOnMain { runCatching { view.evaluateJavascript(js, null) } }
    }

    /** 启动参数作为首条 hostMessage 下发。仅在页面 boot 后调用（host.js 会丢弃未就绪时的消息）。 */
    private fun deliverLaunchParams() {
        val raw = launchParamsJson ?: return
        val payload = runCatching {
            JSONObject().put("type", "launch").put("params", JSONObject(raw))
        }.getOrElse {
            Log.w(TAG, "invalid launch params json, dropped: ${it.message}")
            return
        }
        dispatchHostMessage(payload.toString())
    }

    private fun buildBundleJson(bundle: AixBundleReader.Bundle): String {
        val files = JSONObject()
        val bridgeJs = loadPageBridge()
        for ((path, v) in bundle.files) {
            val obj = JSONObject()
            if (v.text != null) {
                // 把 Lab 桥前置注入 app.js（与页面同 realm —— 页面运行在 quickjs-wasm 沙箱里，
                // host.js 在主 realm 定义的 window.Lab 页面完全看不到）。
                val injected = if (path == "app.js" && bridgeJs != null) bridgeJs + "\n" + v.text else v.text
                obj.put("text", injected)
            } else {
                obj.put("b64", v.b64)
            }
            files.put(path, obj)
        }
        return JSONObject().apply {
            put("appId", bundle.appId)
            bundle.initialPage?.let { put("initialPage", it) }
            put("files", files)
        }.toString()
    }

    private fun loadPageBridge(): String? {
        if (pageBridgeJs != null) return pageBridgeJs
        pageBridgeJs = runCatching {
            ctx.assets.open("ink/lab-page-bridge.js").bufferedReader().use { it.readText() }
        }.onFailure { Log.w(TAG, "load lab-page-bridge.js failed: ${it.message}") }.getOrNull()
        return pageBridgeJs
    }

    private fun mimeFor(path: String): String = when {
        path.endsWith(".wasm") -> "application/wasm"
        path.endsWith(".js") || path.endsWith(".mjs") -> "application/javascript"
        path.endsWith(".html") -> "text/html"
        path.endsWith(".css") -> "text/css"
        path.endsWith(".json") -> "application/json"
        path.endsWith(".png") -> "image/png"
        path.endsWith(".jpg") || path.endsWith(".jpeg") -> "image/jpeg"
        path.endsWith(".gif") -> "image/gif"
        path.endsWith(".svg") -> "image/svg+xml"
        path.endsWith(".ttf") -> "font/ttf"
        path.endsWith(".woff") -> "font/woff"
        path.endsWith(".woff2") -> "font/woff2"
        path.endsWith(".otf") -> "font/otf"
        else -> "application/octet-stream"
    }

    /** 纯文本响应（`__lab` 端点用），必须带 Content-Length，否则 ink 侧 fetch text() 会挂起 */
    private fun textResponse(body: String): WebResourceResponse {
        val bytes = body.toByteArray(Charsets.UTF_8)
        return WebResourceResponse(
            "text/plain", "UTF-8", 200, "OK",
            mutableMapOf(
                "Content-Length" to bytes.size.toString(),
                // 页面脚本运行在 WebView 内部的虚拟路径下，origin 与 BASE_URL 不一定同源；
                // 缺 CORS 头时 Chromium 会直接拒收拦截响应（页面侧表现为 fetch 永远 pending）。
                // 三个头都要给：跨域 fetch 会先发 OPTIONS preflight，只回 ACAO 会让 preflight 失败。
                "Access-Control-Allow-Origin" to "*",
                "Access-Control-Allow-Methods" to "GET, POST, OPTIONS",
                "Access-Control-Allow-Headers" to "*",
                "Cache-Control" to "no-store",
            ),
            ByteArrayInputStream(bytes),
        )
    }

    private fun jsonStr(s: String?): String = if (s == null) "null" else JSONObject.quote(s)

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    // ── 远程图片代理（跨域取图，见 intercept） ──

    private fun looksLikeRemoteImage(url: String): Boolean {
        val u = url.lowercase()
        if (!u.startsWith("http://") && !u.startsWith("https://")) return false
        val path = u.substringBefore('?').substringBefore('#')
        return path.endsWith(".jpg") || path.endsWith(".jpeg") || path.endsWith(".png") ||
            path.endsWith(".webp") || path.endsWith(".gif") || path.endsWith(".bmp") ||
            path.endsWith(".avif")
    }

    /**
     * 代取远程图片并补 CORS 头，解决 ink `<image>` 的跨域取图失败。
     *
     * 只代理图片，**不**代理任意外网请求：页面与外网交互的唯一合法通道是 `Lab.callTool`，
     * 若把普通 fetch 也代理掉，会顺带绕过 CORS 让「页面自己拉外网 API」这种违规写法也能跑通。
     * 本方法运行在 WebView 的 IO 线程，可以同步做网络请求；失败返回 null 交回默认逻辑。
     */
    private fun proxyRemoteImage(url: String): WebResourceResponse? {
        var conn: java.net.HttpURLConnection? = null
        return try {
            conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 6000
                readTimeout = 12000
                instanceFollowRedirects = true
                setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Version/4.0 Chrome/95.0.4638.74 Mobile Safari/537.36",
                )
                // 部分图站校验 Referer，缺了会 403
                setRequestProperty("Referer", BASE_URL)
                setRequestProperty("Accept", "image/avif,image/webp,image/*,*/*;q=0.8")
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "proxyImage: HTTP $code $url")
                conn.disconnect()
                null
            } else {
                val bytes = conn.inputStream.readBytes()
                val mime = conn.contentType?.substringBefore(';')?.trim()
                    ?.takeIf { it.startsWith("image/") } ?: "image/jpeg"
                WebResourceResponse(
                    mime, null, 200, "OK",
                    mutableMapOf(
                        "Access-Control-Allow-Origin" to "*",
                        "Access-Control-Allow-Methods" to "GET, OPTIONS",
                        "Access-Control-Allow-Headers" to "*",
                        "Cache-Control" to "max-age=600",
                    ),
                    ByteArrayInputStream(bytes),
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "proxyImage failed: ${e.message} $url")
            null
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    // ── 工具结果缓存 / 同步等待队列（静态：工具线程与拦截 IO 线程都会碰） ──

    private val toolResults = mutableMapOf<String, Pair<Long, String>>()
    private val toolResultLock = Any()

    private fun cacheToolResult(cbId: String, json: String) {
        if (cbId.isBlank()) return
        synchronized(toolResultLock) {
            val now = System.currentTimeMillis()
            toolResults[cbId] = Pair(now + TOOL_RESULT_TTL_MS, json)
            val it = toolResults.entries.iterator()
            while (it.hasNext()) {
                if (it.next().value.first < now) it.remove()
            }
        }
        val q = syncToolQueues[cbId] ?: return
        if (!q.offer(json)) {
            Log.w(TAG, "tool result queue already filled for cbId=$cbId (1-slot), dropped")
        }
    }

    private fun takeCachedToolResult(cbId: String): String? = synchronized(toolResultLock) {
        toolResults.remove(cbId)?.second
    }

    private val syncToolQueues = ConcurrentHashMap<String, ArrayBlockingQueue<String>>()
}
