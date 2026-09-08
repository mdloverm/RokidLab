package com.rokidlab.rokidlink

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File

/**
 * AIUI Web 宿主：把 Lab 生成的 .aix 在 RokidLink 内用官方 @yodaos-pkg/ink
 * （browser-integration + wasm）渲染，摆脱 AssistServer AiuiActivity 的
 * interactive/enableTouchEvent 门控。眼镜系统按键（含 Lab 蓝牙手柄 HID 转成的
 * KeyEvent）经 [injectKey] 合成 DOM 键盘事件喂给 ink，页面直接响应；
 * 手机侧消息可经 [dispatchHostMessage] 以 onMessage 协议驱动页面（伪交互双通道）。
 */
@SuppressLint("SetJavaScriptEnabled")
class AiuiLinkActivity : Activity() {

    private var webView: WebView? = null
    private var bundleJson: String? = null
    private var booted = false
    /** host.js 模块已执行完毕（__aiuiHost 就绪），避免 bundle 先就绪时漏 boot */
    private var jsHostReady = false
    private var currentAppId: String? = null
    private var currentAixPath: String? = null
    /** boot 后双击 BACK 逃生计时（页面若不响应 Backspace 可强制退出宿主） */
    private var lastBackMs = 0L

    private val mainHandler = Handler(Looper.getMainLooper())

    private val bridge = object {
        @JavascriptInterface
        fun hostReady() {
            Log.i(TAG, "js host ready")
            jsHostReady = true
            mainHandler.post { maybeBoot() }
        }

        @JavascriptInterface
        fun ready() {
            Log.i(TAG, "ink view ready")
        }

        @JavascriptInterface
        fun closeRequested() {
            Log.i(TAG, "ink closeRequested -> finish")
            runOnUiThread { finish() }
        }

        @JavascriptInterface
        fun closed() {
            Log.i(TAG, "ink closed -> finish")
            runOnUiThread { finish() }
        }

        @JavascriptInterface
        fun error(msg: String) {
            Log.e(TAG, "js error: $msg")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val aixPath = intent?.getStringExtra(EXTRA_AIX_PATH)
        if (aixPath == null || !File(aixPath).exists()) {
            Log.e(TAG, "no aix file: $aixPath")
            finish()
            return
        }
        currentAixPath = aixPath

        val dm = resources.displayMetrics
        val screenW = dm.widthPixels
        val screenH = dm.heightPixels
        Log.i(TAG, "screen ${screenW}x${screenH} density=${dm.density}")

        // 后台解包 .aix；完成后通知 JS boot
        Thread {
            val bundle = AixBundleReader.read(File(aixPath))
            currentAppId = bundle.appId
            bundleJson = buildBundleJson(bundle, screenW, screenH)
            Log.i(TAG, "bundleJson ready: ${bundle.files.size} files")
            mainHandler.post {
                if (!isFinishing) maybeBoot()
            }
        }.apply { name = "aix-unpack"; start() }

        createWebView()
    }

    private fun createWebView() {
        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)
        // 窗口与 WebView 强制不透明纯黑底：本 Activity 是 windowIsTranslucent 窗口，
        // 页面若有透明像素会直接穿透露出下层（Launcher/KeyButtonBridge）造成背景虚影。
        window.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        val wv = WebView(this)
        wv.setBackgroundColor(Color.BLACK)
        wv.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        root.addView(wv)
        setContentView(root)
        webView = wv

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
        ws.allowContentAccess = true
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
                Log.i(TAG, "page finished: $url")
                retryBoot()
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val url = request?.url?.toString() ?: return null
                if (!url.startsWith(BASE_URL)) return null
                val assetPath = url.removePrefix(BASE_URL)
                if (assetPath.isEmpty()) return null

                // 解包结果 JSON：由宿主 JS 以 fetch('bundle.json') 拉取（同源 https://ink.local）
                if (assetPath == "bundle.json") {
                    val json = bundleJson ?: return WebResourceResponse(
                        "application/json", "UTF-8", ByteArrayInputStream("{}".toByteArray())
                    )
                    return WebResourceResponse(
                        "application/json", "UTF-8",
                        ByteArrayInputStream(json.toByteArray(Charsets.UTF_8))
                    )
                }
                return try {
                    val mime = mimeFor(assetPath)
                    val stream = assets.open("ink/$assetPath")
                    WebResourceResponse(mime, null, stream)
                } catch (e: Exception) {
                    Log.w(TAG, "asset miss: $assetPath")
                    null
                }
            }
        }

        val html = runCatching {
            assets.open("ink/index.html").bufferedReader().use { it.readText() }
        }.getOrElse { Log.e(TAG, "index.html missing", it); "" }
        if (html.isNotEmpty()) {
            wv.loadDataWithBaseURL(BASE_URL, html, "text/html", "UTF-8", null)
        }
    }

    /** 页面 module 加载完后调用 __aiuiHost.boot；带重试防竞态 */
    private fun retryBoot() {
        maybeBoot()
    }

    /**
     * boot 前提：bundle 解包完成 + host.js 已就绪（hostReady 回传）。
     * 任一条件未满足就静默等待，由解包线程 / onPageFinished / hostReady 各自触发补调。
     */
    private fun maybeBoot() {
        if (booted || bundleJson == null || !jsHostReady || isFinishing) return
        booted = true
        val dm = resources.displayMetrics
        bootJs(dm.widthPixels, dm.heightPixels)
    }

    private fun bootJs(w: Int, h: Int) {
        val js = "window.__aiuiHost && window.__aiuiHost.boot({" +
            "width:$w,height:$h,bundleUrl:'bundle.json'," +
            "appId:${jsonStr(currentAppId)}," +
            "initialPage:null})"
        webView?.evaluateJavascript(js, null)
    }

    // ── 眼镜系统按键 / Lab 蓝牙手柄(HID→KeyEvent) → ink ──
    // 在 dispatchKeyEvent 层拦截：WebView 是窗口焦点 View，方向键/回车会被
    // WebView 内部消费，Activity.onKeyDown 永远不会被回调（实测无任何 keyDown
    // 日志）。dispatchKeyEvent 先于整个 View 层级执行，可确保按键必然进入宿主。
    override fun dispatchKeyEvent(event: KeyEvent?): Boolean {
        if (event == null) return super.dispatchKeyEvent(null)
        if (!booted) {
            Log.d(TAG, "key(not booted) kc=${event.keyCode}")
            return super.dispatchKeyEvent(event)
        }
        val code = keyCodeToInk(event)
        Log.i(TAG, "key ${if (event.action == KeyEvent.ACTION_UP) "up" else "down"} kc=${event.keyCode} code=$code")
        if (code != null) {
            // BACK → Backspace 已注入页面；若页面不消费，双击 BACK 逃生退出宿主
            if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_DOWN) {
                val now = System.currentTimeMillis()
                if (now - lastBackMs < 1_500L) {
                    finish()
                    return true
                }
                lastBackMs = now
            }
            if (event.action == KeyEvent.ACTION_UP) {
                injectKey(code, "up")
            } else {
                injectKey(code, "down")
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun keyCodeToInk(event: KeyEvent?): String? {
        val kc = event?.keyCode ?: return null
        return when (kc) {
            KeyEvent.KEYCODE_DPAD_UP -> "ArrowUp"
            KeyEvent.KEYCODE_DPAD_DOWN -> "ArrowDown"
            KeyEvent.KEYCODE_DPAD_LEFT -> "ArrowLeft"
            KeyEvent.KEYCODE_DPAD_RIGHT -> "ArrowRight"
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER -> "Enter"
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_DEL -> "Backspace"
            KeyEvent.KEYCODE_ESCAPE -> "Escape"
            KeyEvent.KEYCODE_TAB -> "Tab"
            KeyEvent.KEYCODE_SPACE -> "Space"
            // Rokid 镜腿物理键（官方 AiuiKeyMapper: keyCode 83 -> GlobalHook）
            83 -> "GlobalHook"
            else -> when (kc) {
                in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z ->
                    "Key" + ('A' + (kc - KeyEvent.KEYCODE_A))
                in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 ->
                    "Digit" + (kc - KeyEvent.KEYCODE_0)
                else -> null
            }
        }
    }

    private fun injectKey(code: String, action: String) {
        val js = "window.__aiuiHost && window.__aiuiHost.key(${jsonStr(code)},'$action')"
        runOnUiThread { webView?.evaluateJavascript(js, null) }
    }

    // ── 手机侧 CXR 消息 → 页面 onMessage（预留；由 KeyButtonService 转发） ──
    fun dispatchHostMessage(json: String) {
        val js = "window.__aiuiHost && window.__aiuiHost.hostMessage($json)"
        runOnUiThread { webView?.evaluateJavascript(js, null) }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        runCatching {
            webView?.loadUrl("about:blank")
            webView?.stopLoading()
        }
        webView?.destroy()
        webView = null
        super.onDestroy()
    }

    override fun onBackPressed() {
        // 已交给 onKeyDown(Backspace) 注入 ink；未 boot 前直接退出
        if (!booted) {
            super.onBackPressed()
        }
    }

    companion object {
        private const val TAG = "AiuiLink"
        private const val BASE_URL = "https://ink.local/"
        const val EXTRA_AIX_PATH = "aix_path"

        private val lock = Any()
        private var activeActivity: AiuiLinkActivity? = null

        /** 打开 AIUI 宿主；context 可为 Service（自动加 NEW_TASK） */
        @JvmStatic
        fun open(context: Context, aixPath: String) {
            val i = Intent(context, AiuiLinkActivity::class.java)
                .putExtra(EXTRA_AIX_PATH, aixPath)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(i)
        }

        /** 若宿主在前台，向其转发手机侧 host 消息（页面 onMessage 协议） */
        @JvmStatic
        fun dispatchMessageToActive(json: String) {
            synchronized(lock) {
                activeActivity?.dispatchHostMessage(json)
            }
        }

        /** 若宿主在前台，直接关闭（手机侧 "close" 命令 / 切换包前清理） */
        @JvmStatic
        fun closeActive() {
            synchronized(lock) {
                val a = activeActivity
                a?.runOnUiThread { a.finish() }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        synchronized(lock) { activeActivity = this }
    }

    override fun onStop() {
        super.onStop()
        synchronized(lock) { if (activeActivity === this) activeActivity = null }
    }

    /** singleTask 复用实例时换包：旧的直接退，新的立即接管渲染 */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        val p = intent?.getStringExtra(EXTRA_AIX_PATH)
        if (p == null || p == currentAixPath) return
        val app = applicationContext
        finish()
        mainHandler.postDelayed({ open(app, p) }, 150L)
    }

    private fun buildBundleJson(bundle: AixBundleReader.Bundle, w: Int, h: Int): String {
        val files = JSONObject()
        for ((path, v) in bundle.files) {
            val obj = JSONObject()
            if (v.text != null) obj.put("text", v.text) else obj.put("b64", v.b64)
            files.put(path, obj)
        }
        val root = JSONObject()
        root.put("appId", bundle.appId)
        bundle.initialPage?.let { root.put("initialPage", it) }
        root.put("files", files)
        root.put("width", w)
        root.put("height", h)
        return root.toString()
    }

    private fun mimeFor(path: String): String = when {
        path.endsWith(".wasm") -> "application/wasm"
        path.endsWith(".js") -> "application/javascript"
        path.endsWith(".mjs") -> "application/javascript"
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

    private fun jsonStr(s: String?): String {
        if (s == null) return "null"
        return JSONObject.quote(s)
    }
}
