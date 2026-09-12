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
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

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
    /** 本次启动的启动参数（JSON 对象字符串），页面 boot 完成后作为首条 hostMessage 下发 */
    private var launchParamsJson: String? = null
    /** boot 后双击 BACK 逃生计时（页面若不响应 Backspace 可强制退出宿主） */
    private var lastBackMs = 0L

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 工具调用结果缓存：页面 realm 的 Lab 桥经 fetch 轮询 __lab/tool_result 拉取（兼容旧桥）。
     *  键 cbId → 结果 JSON 文本。带 TTL，避免长期驻留。 */
    private val toolResults = mutableMapOf<String, Pair<Long, String>>()
    private val toolResultLock = Any()

    /** 同步工具调用阻塞队列：__lab/tool_call_sync 端点会等待此队列，结果到达后立即返回。 */
    private val syncToolQueues = ConcurrentHashMap<String, ArrayBlockingQueue<String>>()

    /** 同步工具调用最长阻塞时间（与 ToolGateway 的 15s 对齐）。 */
    private val SYNC_TOOL_TIMEOUT_MS = 15_000L

    /** 自动注入到每个 AIUI 页面的 Lab 桥（页面 realm 可见，解决 host.js 主 realm Lab 隔离问题）。
     *  从 assets 读取一次缓存，避免每次装配都 IO。 */
    private var pageBridgeJs: String? = null

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

        /**
         * 宿主 JS 调试日志 → logcat。
         *
         * WebView 未开启远程调试，页面/宿主的 console.log 在 logcat 里看不到，
         * 排查 AIUI 链路（boot 时序、launch 参数投递、工具调用）全靠此通道。
         * 仅写日志，无副作用；release 版同样保留（AIUI 调试成本高，值得留）。
         */
        @JavascriptInterface
        fun log(msg: String) {
            Log.d(TAG, "js: $msg")
        }

        /**
         * 页面调用手机端工具（window.Lab.callTool 的底层）。
         *
         * 上行复用 ASR 推送的 RFCOMM 通道（带 CTRL_TOOL_CALL 前缀以区别于 ASR 文字），
         * 不新开通道：眼镜端同一时刻只允许一条 RFCOMM（adb 隧道已占满）。
         *
         * 本方法运行在 JS 线程，WebView 相关操作须切主线程。
         */
        @JavascriptInterface
        fun callTool(name: String, argsJson: String, cbId: String) {
            invokeToolCall(name, argsJson, cbId)
        }
    }

    /**
     * 真正执行工具调用上行的逻辑。供 JS bridge 与 fetch 备用通道共用。
     * 复用 ASR 推送的 RFCOMM 通道（带 CTRL_TOOL_CALL 前缀），不新建 RFCOMM。
     */
    private fun invokeToolCall(name: String, argsJson: String, cbId: String) {
        Log.i(TAG, "page callTool: name=$name cbId=$cbId")
        val payload = try {
            JSONObject()
                .put("cbId", cbId)
                .put("name", name)
                .put("args", if (argsJson.isBlank()) "{}" else argsJson)
                .toString()
        } catch (e: Exception) {
            Log.e(TAG, "callTool payload build failed", e)
            mainHandler.post { deliverToolResult(cbId, false, null, "bad arguments") }
            return
        }
        val sent = AsrPushServer.pushControl(AsrPushServer.CTRL_TOOL_CALL + payload)
        if (!sent) {
            // 手机端未连接：立刻回传失败，否则页面要干等到超时才知道
            Log.w(TAG, "callTool push failed: no phone connection (cbId=$cbId)")
            mainHandler.post { deliverToolResult(cbId, false, null, "phone not connected") }
        }
    }

    /**
     * .aix 路径白名单：只接受本应用 `filesDir/aiui_host/` 目录下的真实文件，
     * 该目录的唯一写入方是 [AiuiPackageServer]（手机端推送落盘）。
     *
     * 为什么必须校验：路径来自手机端 `open` 命令的文件名，KeyButtonService 侧
     * 只做了 `File(filesDir, "aiui_host/$name")` 拼接，带 `../` 的名字可以逃逸到应用
     * 私有目录之外 —— 宿主会把任意文件当 .aix 解包渲染，等于给页面一个读任意文件的入口。
     * canonicalFile 比对可同时挡掉 `../` 穿越与指向别处的绝对路径。
     */
    private fun isAllowedAixPath(path: String): Boolean = try {
        val base = File(filesDir, AiuiPackageServer.DIR_NAME).canonicalFile
        val target = File(path).canonicalFile
        target.isFile && target.parentFile?.canonicalPath == base.canonicalPath
    } catch (e: Exception) {
        Log.w(TAG, "aix path check failed: ${e.message}")
        false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val aixPath = intent?.getStringExtra(EXTRA_AIX_PATH)
        if (aixPath == null || !isAllowedAixPath(aixPath)) {
            Log.e(TAG, "aix path rejected: $aixPath")
            finish()
            return
        }
        currentAixPath = aixPath
        launchParamsJson = intent?.getStringExtra(EXTRA_LAUNCH_PARAMS)?.takeIf { it.isNotBlank() }
        if (launchParamsJson != null) {
            Log.i(TAG, "launch params: $launchParamsJson")
        }

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
                Log.i(TAG, "page finished: $url")
                retryBoot()
            }

            /**
             * 只允许宿主自己的 `https://ink.local/` 顶层导航。
             *
             * [addJavascriptInterface] 会把 `Android` 桥注入 WebView 的**所有 frame**，
             * 页面一旦被导航到外部站点（链接跳转 / 脚本重定向），对端 JS 即可直接调用
             * `Android.callTool` 触达手机端工具，绕开 `exported=false` 的隔离。
             * 子资源（图片 / 字体等）不经过本回调，仍可正常加载。
             *
             * 残留面（已知，暂未处理）：外部 `<iframe>` 属于子框架加载，本回调拦不到，
             * 其脚本仍可触达桥；彻底关闭需要改成"仅向页面注入桥"而非全局注入。
             */
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
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
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val url = request?.url?.toString() ?: return null
                if (!url.startsWith(BASE_URL)) return null
                val assetPath = url.removePrefix(BASE_URL)
                if (assetPath.isEmpty()) return null

                // 连通性探测端点：验证「页面 fetch → 宿主拦截」这条通道是否可用。
                // 页面运行在 quickjs+wasm 沙箱内，未必能访问 window（JS bridge 可能不可达），
                // 此时 https://ink.local/__lab/... 是页面上行的唯一可行通道。
                if (assetPath.startsWith("__lab/")) {
                    Log.i(TAG, "lab endpoint hit: $assetPath")
                    return when {
                        assetPath.startsWith("__lab/ping") -> textResponse("pong")
                        assetPath.startsWith("__lab/tool_result") -> {
                            // 页面 realm 的 Lab 桥轮询结果：缓存里有就返回，否则返回 pending。
                            val cb = request?.url?.getQueryParameter("cbId")?.takeIf { it.isNotBlank() }
                            val cached = synchronized(toolResultLock) {
                                cb?.let {
                                    toolResults[it]?.also { p -> toolResults.remove(it) }?.second
                                }
                            }
                            if (cached != null) {
                                Log.i(TAG, "tool_result hit cbId=$cb")
                                textResponse(cached)
                            } else {
                                textResponse("{\"status\":\"pending\",\"cbId\":\"${cb ?: ""}\"}")
                            }
                        }
                        assetPath.startsWith("__lab/tool_call_sync") -> {
                            // 同步工具调用：页面一发 fetch，宿主在此后台线程阻塞等结果。
                            val q = request?.url
                            val name = q?.getQueryParameter("name")?.takeIf { it.isNotBlank() }
                            val args = q?.getQueryParameter("args")?.takeIf { it.isNotBlank() } ?: "{}"
                            val cbId = q?.getQueryParameter("cbId")?.takeIf { it.isNotBlank() }
                                ?: ("sync_" + System.currentTimeMillis())
                            if (name == null) return textResponse("missing name")

                            val queue = ArrayBlockingQueue<String>(1)
                            syncToolQueues[cbId] = queue
                            try {
                                invokeToolCall(name, args, cbId)
                                val result = queue.poll(SYNC_TOOL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                                if (result == null) {
                                    Log.w(TAG, "tool_call_sync timeout cbId=$cbId")
                                    textResponse(syncErrorJson(cbId, "tool timed out"))
                                } else {
                                    Log.i(TAG, "tool_call_sync return cbId=$cbId")
                                    textResponse(result)
                                }
                            } finally {
                                syncToolQueues.remove(cbId)
                            }
                        }
                        assetPath.startsWith("__lab/tool_call") -> {
                            // fetch 备用通道：页面无法访问 window.Android 时，用 fetch 触发工具调用。
                            // 因 WebResourceResponse 必须同步返回，此处立即返回 202 Accepted，
                            // 真实结果通过手机端 CMD_AIUI_MSG 经 onMessage 回传。
                            val q = request?.url
                            val name = q?.getQueryParameter("name")?.takeIf { it.isNotBlank() }
                            val args = q?.getQueryParameter("args")?.takeIf { it.isNotBlank() } ?: "{}"
                            val cbId = q?.getQueryParameter("cbId")?.takeIf { it.isNotBlank() }
                                ?: ("fetch_" + System.currentTimeMillis())
                            if (name == null) {
                                textResponse("missing name")
                            } else {
                                invokeToolCall(name, args, cbId)
                                textResponse("accepted:$cbId")
                            }
                        }
                        assetPath.startsWith("__lab/list_tools") -> {
                            val cbId = "fetch_" + System.currentTimeMillis()
                            invokeToolCall("list_tools", "{}", cbId)
                            textResponse("accepted:$cbId")
                        }
                        else -> textResponse("unknown endpoint: $assetPath")
                    }
                }

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
        // launchParams 以 **JSON 字符串**（而非对象字面量）传入：JSONObject.quote 会转义，
        // 页面侧 JSON.parse 还原。避免把内容直接拼进 JS 造成注入/语法破坏。
        val js = "window.__aiuiHost && window.__aiuiHost.boot({" +
            "width:$w,height:$h,bundleUrl:'bundle.json'," +
            "appId:${jsonStr(currentAppId)}," +
            "launchParams:${jsonStr(launchParamsJson)}," +
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
        // 工具结果下行：同时写入轮询缓存，供页面 realm 的 Lab 桥 fetch 拉取
        if (json.contains("\"type\":\"toolResult\"") || json.contains("\"type\": \"toolResult\"")) {
            try {
                val obj = JSONObject(json)
                if (obj.optString("type") == "toolResult") {
                    cacheToolResult(obj.optString("cbId"), json)
                }
            } catch (_: Exception) { /* 不是合法 JSON 则忽略缓存 */ }
        }
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
        /** 启动参数（JSON 对象字符串）：由手机端 open 命令携带，页面 boot 后作为首条 hostMessage 下发 */
        const val EXTRA_LAUNCH_PARAMS = "launch_params"

        private val lock = Any()
        private var activeActivity: AiuiLinkActivity? = null

        /** 打开 AIUI 宿主；context 可为 Service（自动加 NEW_TASK） */
        @JvmStatic
        fun open(context: Context, aixPath: String, launchParams: String? = null) {
            val i = Intent(context, AiuiLinkActivity::class.java)
                .putExtra(EXTRA_AIX_PATH, aixPath)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (!launchParams.isNullOrBlank()) i.putExtra(EXTRA_LAUNCH_PARAMS, launchParams)
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
        val p = intent?.getStringExtra(EXTRA_AIX_PATH) ?: return
        val params = intent?.getStringExtra(EXTRA_LAUNCH_PARAMS)?.takeIf { it.isNotBlank() }
        // 同一个包再次打开（只换参数，如"再用 AIUI 放一首别的"）：不能重启宿主，
        // 否则会打断页面当前播放/动画。直接更新参数并下发给已运行的页面。
        // 注意：未 boot 时不能下发（host.js 会在 view 就绪前丢弃），此时只更新字段，
        // 由 bootJs 把最新参数带进 boot —— 两条路径共用 launchParamsJson，天然自洽。
        if (p == currentAixPath) {
            if (params != null && params != launchParamsJson) {
                launchParamsJson = params
                Log.i(TAG, "re-launch same package with new params: $params")
                if (booted) deliverLaunchParams()
            }
            return
        }
        val app = applicationContext
        finish()
        mainHandler.postDelayed({ open(app, p, params) }, 150L)
    }

    /**
     * 工具调用结果回传页面。手机端执行完后经 CMD_AIUI_MSG 把完整 toolResult 送回来，
     * 直接走 dispatchHostMessage；本方法只用于眼镜端本地就能判定失败的场合
     * （如手机端未连接）。
     */
    private fun deliverToolResult(cbId: String, ok: Boolean, result: String?, error: String?) {
        if (isFinishing) return
        try {
            val payload = JSONObject()
                .put("type", "toolResult")
                .put("cbId", cbId)
                .put("ok", ok)
            if (result != null) payload.put("result", result)
            if (error != null) payload.put("error", error)
            // 缓存一份供页面 realm 的 Lab 桥轮询拉取（与 onMessage 下行互补，互不冲突）
            cacheToolResult(cbId, payload.toString())
            dispatchHostMessage(payload.toString())
        } catch (e: Exception) {
            Log.w(TAG, "deliverToolResult failed: ${e.message}")
        }
    }

    /** 把工具结果写入轮询缓存，并唤醒同步等待队列。 */
    private fun cacheToolResult(cbId: String, json: String) {
        if (cbId.isBlank()) return
        synchronized(toolResultLock) {
            toolResults[cbId] = Pair(System.currentTimeMillis() + 30_000L, json)
            val it = toolResults.entries.iterator()
            val now = System.currentTimeMillis()
            while (it.hasNext()) {
                if (it.next().value.first < now) it.remove()
            }
        }
        // 唤醒同步工具调用等待者（如果有）
        syncToolQueues[cbId]?.offer(json)
    }

    private fun syncErrorJson(cbId: String, error: String): String =
        JSONObject().apply {
            put("type", "toolResult")
            put("cbId", cbId)
            put("ok", false)
            put("error", error)
        }.toString()

    /**
     * 把启动参数作为 hostMessage 下发给页面。
     * 仅在页面已 boot 后调用；未 boot 时由 [bootJs] 通过 config 带过去。
     */
    private fun deliverLaunchParams() {
        val raw = launchParamsJson ?: return
        try {
            val payload = JSONObject()
                .put("type", "launch")
                .put("params", JSONObject(raw))
            dispatchHostMessage(payload.toString())
        } catch (e: Exception) {
            Log.w(TAG, "invalid launch params json, dropped: ${e.message}")
        }
    }

    private fun buildBundleJson(bundle: AixBundleReader.Bundle, w: Int, h: Int): String {
        val files = JSONObject()
        val bridge = loadPageBridge()
        for ((path, v) in bundle.files) {
            val obj = JSONObject()
            if (v.text != null) {
                // 把 Lab 桥前置注入 app.js（与页面同 realm，解决 host.js 主 realm 隔离问题）。
                // 仅注入含 app.js 的入口文件一次，避免重复。
                val injected = if (path == "app.js" && bridge != null) bridge + "\n" + v.text else v.text
                obj.put("text", injected)
            } else {
                obj.put("b64", v.b64)
            }
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

    /** 纯文本响应（__lab 探测端点用），必须带 Content-Length，否则 ink fetch text() 会挂起 */
    private fun textResponse(body: String): WebResourceResponse {
        val bytes = body.toByteArray(Charsets.UTF_8)
        return WebResourceResponse(
            "text/plain", "UTF-8", 200, "OK",
            mutableMapOf("Content-Length" to bytes.size.toString()),
            ByteArrayInputStream(bytes)
        )
    }

    private fun jsonStr(s: String?): String {
        if (s == null) return "null"
        return JSONObject.quote(s)
    }

    /** 读取页面 realm 的 Lab 桥（assets/ink/lab-page-bridge.js），缓存复用 */
    private fun loadPageBridge(): String? {
        if (pageBridgeJs != null) return pageBridgeJs
        pageBridgeJs = runCatching {
            assets.open("ink/lab-page-bridge.js").bufferedReader().use { it.readText() }
        }.onFailure { Log.w(TAG, "load lab-page-bridge.js failed: ${it.message}") }
            .getOrNull()
        return pageBridgeJs
    }
}
