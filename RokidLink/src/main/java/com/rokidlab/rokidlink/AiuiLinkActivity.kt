package com.rokidlab.rokidlink

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
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

    /** 传给 ink 的画布宽度 = 眼镜整屏宽；高度在 auto-height 布局下随内容自适应并垂直居中 */
    private var contentW = 0
    private var contentH = 0

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 传感器（IMU 头控）注册表：JS 类型码 → 活动监听器（host.js 经 Android.sensorStart/Stop 驱动） */
    private var sensorManager: SensorManager? = null
    private val activeSensorListeners = mutableMapOf<Int, SensorEventListener>()
    private val sensorLock = Any()

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
         * 页面传感器（IMU 头控）桥：页面用 W3C Generic Sensor API（new Gyroscope() 等），
         * ink 引擎经 hostCapabilities.handleRequest 通知宿主 start/stop。
         * type 码：0 陀螺仪 / 1 加速度计 / 2 旋转向量（绝对朝向） / 3 磁力计。
         * 运行在 JS 线程，SensorManager 注册须线程安全（sensorLock）。
         */
        @JavascriptInterface
        fun sensorStart(type: Int, freq: Int, targetId: String, sessionId: String): Boolean {
            Log.i(TAG, "sensorStart type=$type freq=$freq session=$sessionId")
            return startSensorListener(type, freq)
        }

        @JavascriptInterface
        fun sensorStop(type: Int, targetId: String, sessionId: String) {
            Log.i(TAG, "sensorStop type=$type session=$sessionId")
            stopSensorListener(type)
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
        val outcome = AsrPushServer.pushControlDetailed(AsrPushServer.CTRL_TOOL_CALL + payload)
        if (outcome != AsrPushServer.PushOutcome.SENT) {
            // 上行失败：立刻按原因回传，否则页面要干等到超时才知道（闭环审计 R9 ——
            // 原来所有失败一律报 "phone not connected"，写线程堆积/帧超限时排查方向被带偏）。
            val reason = when (outcome) {
                AsrPushServer.PushOutcome.NO_CLIENT -> "phone not connected"
                AsrPushServer.PushOutcome.TOO_LARGE -> "tool call too large for link frame"
                AsrPushServer.PushOutcome.WRITER_BUSY -> "phone link busy, try again shortly"
                else -> "phone link error"
            }
            Log.w(TAG, "callTool push failed: $outcome (cbId=$cbId)")
            mainHandler.post { deliverToolResult(cbId, false, null, reason) }
        }
    }

    // ── 传感器（IMU 头控）：页面 W3C Generic Sensor ← host.js hostCapabilities ← 本桥 ──

    /** JS 传感器类型码 → Android 传感器类型 */
    private fun androidSensorType(type: Int): Int = when (type) {
        0 -> Sensor.TYPE_GYROSCOPE
        1 -> Sensor.TYPE_ACCELEROMETER
        2 -> Sensor.TYPE_ROTATION_VECTOR // 绝对朝向（页面按 x,y,z,w 消费四元数）
        3 -> Sensor.TYPE_MAGNETIC_FIELD
        else -> -1
    }

    /** JS 类型码 → host.js pushSensor 的能力名（两端约定，勿单侧改动） */
    private fun sensorCapName(type: Int): String = when (type) {
        0 -> "gyroscope"
        1 -> "accelerometer"
        2 -> "absoluteOrientation"
        else -> "magnetometer"
    }

    private fun startSensorListener(type: Int, freq: Int): Boolean {
        val sensorType = androidSensorType(type)
        if (sensorType < 0) return false
        val sm = sensorManager
            ?: (getSystemService(Context.SENSOR_SERVICE) as? SensorManager)
                ?.also { sensorManager = it }
            ?: return false
        val sensor = sm.getDefaultSensor(sensorType) ?: run {
            Log.w(TAG, "sensor type=$type unavailable on device")
            return false
        }
        synchronized(sensorLock) {
            if (activeSensorListeners.containsKey(type)) return true // 重复 start 幂等
            // 频率夹 5~100Hz：官方页面用 60Hz，FASTEST(~200Hz) 对头控毫无必要还费电
            val periodUs = 1_000_000 / freq.coerceIn(5, 100)
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent?) {
                    val e = event ?: return
                    var x = if (e.values.size > 0) e.values[0] else 0f
                    var y = if (e.values.size > 1) e.values[1] else 0f
                    var z = if (e.values.size > 2) e.values[2] else 0f
                    var w = 0f
                    if (sensorType == Sensor.TYPE_ROTATION_VECTOR) {
                        // 旋转向量 → 四元数：SensorManager 返回 [w,x,y,z]，页面要 [x,y,z,w]
                        val q = FloatArray(4)
                        SensorManager.getQuaternionFromVector(q, e.values)
                        w = q[0]; x = q[1]; y = q[2]; z = q[3]
                    }
                    // ns → ms：对齐 DOMHighResTimeStamp（页面只关心数值与差值，基准无关）
                    val ts = e.timestamp / 1_000_000L
                    val js = "window.__aiuiHost && window.__aiuiHost.pushSensor(" +
                        "'${sensorCapName(type)}',$x,$y,$z,$w,$ts)"
                    runOnUiThread {
                        runCatching { webView?.evaluateJavascript(js, null) }
                    }
                }

                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            }
            activeSensorListeners[type] = listener
            sm.registerListener(listener, sensor, periodUs)
            Log.i(TAG, "sensor listening type=$type period=${periodUs}us")
        }
        return true
    }

    private fun stopSensorListener(type: Int) {
        val sm = sensorManager ?: return
        synchronized(sensorLock) {
            val listener = activeSensorListeners.remove(type) ?: return
            runCatching { sm.unregisterListener(listener) }
            Log.i(TAG, "sensor unregistered type=$type")
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
        // 窗口必须**真正**占满整屏：主题里的 Theme.NoTitleBar.Fullscreen 只是去掉标题栏，
        // 并不保证窗口拿到全屏尺寸。与 PhoneMirrorActivity（投屏页，实测无绿边无亮线）对齐。
        //
        // 【2026-10-04 修正】此前这里写的根因说明是**错的**（说绿边/亮线来自「窗口没铺满」）。
        // 真实情况分成两个互不相同的现象，别再混为一谈：
        //   ① 亮线（canvas 上下各一条横贯 480px 的线）＝ **Chromium 给获得焦点的 canvas
        //      画的默认焦点环**。铁证：`getComputedStyle(canvas).outline` 实测
        //      `rgb(229,151,0) auto 1px`，而截图中线的颜色**正是** `rgb(229,151,0)`、位置
        //      **正在盒外 1px**。已由 `assets/ink/index.html` 的 `#ink-root{outline:none}`
        //      消除（outline 不参与布局，不改 AIUI 尺寸）。本层无需处理。
        //   ② 绿边（精确环绕全屏一圈）＝ **窗口 buffer 方向与 HWC 模式相反导致 SF 补旋转**，
        //      最外 1~2px 与下层 Launcher 品牌绿混合。修法在 AndroidManifest：
        //      **删掉 `android:screenOrientation="portrait"`**（不是加它！）。
        //      详见 AndroidManifest 中 AiuiLinkActivity 上方的完整证据链。
        //
        // ⚠️ 下面这段 setFlags 保留不动：窗口确实需要真全屏（主题不保证），
        //    但它**只解决「窗口尺寸」**，与绿框/亮线都无关，别当成后两者的修复。
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )

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
        contentW = dm.widthPixels
        contentH = dm.heightPixels
        Log.i(TAG, "screen ${contentW}x${contentH} density=${dm.density}")

        // 后台解包 .aix；完成后通知 JS boot
        Thread {
            val bundle = AixBundleReader.read(File(aixPath))
            currentAppId = bundle.appId
            bundleJson = buildBundleJson(bundle, contentW, contentH)
            Log.i(TAG, "bundleJson ready: ${bundle.files.size} files")
            mainHandler.post {
                if (!isFinishing) maybeBoot()
            }
        }.apply { name = "aix-unpack"; start() }

        createWebView()
        activeWebView = java.lang.ref.WeakReference(webView)
    }

    private fun createWebView() {
        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)
        // 窗口与 WebView 强制不透明纯黑底：本 Activity 用 Theme.RokidLink.Opaque
        // （windowIsTranslucent=false + 窗口背景纯黑，见 AndroidManifest），
        // 页面若有透明像素会直接穿透露出下层（Launcher）造成背景虚影。
        window.setBackgroundDrawable(ColorDrawable(Color.BLACK))
        val wv = WebView(this)
        wv.setBackgroundColor(Color.BLACK)

        // ⚠️ 这几行**不是**绿框的修复（它无public API 可关，见下方遮罩层注释）。
        // 保留它们只作无害卫生措施（确实能避免滚动条 / 按压高亮进画面）。
        // ⚠️ 别据此重查 overscroll：Chromium 官方文档明确 WebView 的 root glow
        //    走 **Java 层 `EdgeEffect`**、**绕过 `View.setOverScrollMode()`**，
        //    所以「设了没变化」是**预期表现**，不能用来否定假设。
        wv.overScrollMode = View.OVER_SCROLL_NEVER
        wv.isVerticalScrollBarEnabled = false
        wv.isHorizontalScrollBarEnabled = false
        wv.setBackgroundColor(Color.BLACK)
        // ── 画面尺寸：必须严格 1:1，不缩放、不超屏 ──────────────────────
        // 【2026-10-04 回归修正】此前这里设过`物理屏 + 2*BLEED`(488x648)想「窗口超屏」，
        // 是**错的**，两条证据：
        //   ① `dumpsys window` 实测本窗口 `mBounds=Rect(0,0-480,640)`、
        //      `mMaxBounds` 同值 ⇒ **系统把窗口钳在 480x640，超屏压根没生效**，
        //      FLAG_LAYOUT_NO_LIMITS 也拿它没办法。
        //   ② 但 WebView 真的变成了 488 宽，而 `index.html` 的 meta viewport 写死
        //      `width=480` ⇒ 浏览器为把 480 塞进 488 **自动缩放 1.0167**。
        //      像素反推证实：页面 .frame 实测外沿 469x351，CSS 声明 460x344，
        //      比值469/460=1.0196、351/344=1.0203 ⇒ **内容被放大 2%**。
        //      这直接违反「保持 AIUI 原有尺寸」这条硬要求。
        // ⇒结论：窗口已由 FLAG_FULLSCREEN 铺满，WebView 就该是窗口的 MATCH_PARENT，
        //   一个像素都不多不少，让 meta viewport=480 与物理 480 天然 1:1。
        wv.layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        // ── 绿框遮罩层（2026-10-04 定位后落地）────────────────────────────────
        // 【根因】Chromium M95 的 WebView 会在**自身盒边缘**常驻画一圈 3px 半透明绿边。
        // 该绿边无任何可关闭的 API（见下方取证），因此采用**遮盖**方式绕过。
        //
        // 【全部实测铁证，勿再回头重查——共 13 轮探针实验】
        //   probe=1  WebView 不获焦 ............... 框不变 ⇒ 排除焦点高亮
        //   probe=2  **整个移除 WebView**（纯黑 ImageView）
        //       ⇒ 四边 1760 采样点**100% 纯黑** ⇒ 框必属 WebView
        //   probe=3  defaultFocusHighlightEnabled=false ... 框不变 ⇒ 同上
        //   probe=4  **空白 WebView**（不 loadUrl、无任何页面）
        //       ⇒ 框**依然完整存在**，3px 渐变 (127,169,0)→(114,152,0)→(4,6,0)
        //       ⇒ 排除 ink 引擎 / CSS / outline / box-shadow / JS 绘制
        //   probe=5  极简空白 HTML（无 ink/host.js）... 框不变 ⇒ 与 ink 无关
        //   probe=6  LAYER_TYPE_SOFTWARE（**绕开 GPU 合成**）
        //       ⇒ 与硬件渲染**逐像素一致**（仅 (4,6,0)↔(4,5,0) 舍入抖动）
        //       ⇒ 排除整个 GPU / 合成器 / hardware layer 路径
        //   probe=9  WebView **缩到屏幕中心 100x100**
        //       ⇒ 非黑像素包围盒精确等于 100x100 且中心与屏幕中心重合，
        //         同时屏幕四边**100% 纯黑**
        //       ⇒ **决定性**：框由 WebView 自己绘制、**随其几何尺寸变化**，
        //         彻底排除「屏幕边缘 / 窗口边缘 / 刘海」整类假设
        //   辅助：WebView 侧反编译 ⇒ 纯净 AOSP WebView 95.0.4638.74（ROM 零改动，
        //        资源里搜 #9ACD00 为 0 命中）；`setUseWebViewBackgroundForOverscroll
        //        Background` 是**空方法（return-void）＝no-op**；
        //        `AwSettings` 的 44 个 boolean 字段**全被 ProGuard 混淆**，
        //        无法反射定位 ⇒ **无 public API 可关**。
        //   ⚠️ 别再试 `View.setOverScrollMode`：Chromium 官方文档明确 WebView 的
        //      root glow 走**Java 层 EdgeEffect**、**绕过** View 层这个开关，
        //      所以「设了没变化」是预期表现，**不能据此否定假设**。
        //
        // 【遮盖方案的安全余量（已实测确认）】
        //绿框精确占**最外 3px**（第 3 档 (4,6,0) 已近黑，取 4px 保险）。
        // 实测 AIUI 内容（.frame 外沿）在 480x640 屏上**上下左右内边距均 ≥ 200px**
        //（截图：标题栏从 y≈205 才开始，上方 205px 纯黑）
        // ⇒ **盖 4px 纯黑边框不会裁到任何有效内容**，且不改 WebView 尺寸、
        //   不改 meta viewport ⇒ **不违反「保持 AIUI 原有尺寸」这条硬要求**。
        //
        // ⚠️ 遮盖条必须**完全不吃触摸事件**，否则会挡住 AIUI 的按键/ 滑动交互。
        //   FrameLayout 的命中分发是**自上而下、找不到处理者就继续往下传**，
        //   所以只要 4 条薄条自己是「不可点击 + 不消费」的 View，
        //   落在它们上面的触摸会**自动落到下层 WebView**（等价于没被遮），
        //   **不需要任何事件转发代码**（转发反而会引入重复派发/时序坑）。
        val EDGE_PX = 4
        // ⚠️ **添加顺序＝Z 序**：FrameLayout 里后addView 的盖在先加的上面。
        //   所以必须**先加 WebView、再加薄条**，否则薄条会被 WebView 压在下面看不见。
        //   （薄条放在上层只是为遮住 WebView 最外 3px 绿边，不影响中间内容可见性。）
        root.addView(wv)
        // 四条边各铺一个纯黑薄条盖住最外 3px 绿框；中间的 WebView 完整可见、可交互。
        // ⚠️ 薄条**绝对不能**有 `isClickable = true`（否则会吃掉落在它上的触摸）。
        listOf(
            android.view.Gravity.TOP,
            android.view.Gravity.BOTTOM,
            android.view.Gravity.START,
            android.view.Gravity.END,
        ).forEach { g ->
            root.addView(
                View(this).apply {
                    setBackgroundColor(Color.BLACK)
                    isClickable = false
                    isFocusable = false
                    isFocusableInTouchMode = false
                },
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ).apply {
                    gravity = g
                    if (g == android.view.Gravity.TOP || g == android.view.Gravity.BOTTOM) {
                        height = EDGE_PX
                        width = ViewGroup.LayoutParams.MATCH_PARENT
                    } else {
                        width = EDGE_PX
                        height = ViewGroup.LayoutParams.MATCH_PARENT
                    }
                },
            )
        }
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
                // 远程图片代理：ink 的 <image> 由 wasm 侧 fetch 取图（web_image_loader.rs），
                // 页面 origin 是 https://ink.local，拉第三方图站属**跨域**、对方又不带 CORS 头
                // ⇒ fetch 直接失败（真机日志：`Failed to fetch remote Web image … TypeError: Failed to fetch`），
                // 表现为封面/图片永远空白。这里用宿主 HTTP 栈代取并补 CORS 头。
                if (!url.startsWith(BASE_URL)) {
                    return if (looksLikeRemoteImage(url)) proxyRemoteImage(url) else null
                }
                val assetPath = url.removePrefix(BASE_URL)
                if (assetPath.isEmpty()) return null

                // 连通性探测端点：验证「页面 fetch → 宿主拦截」这条通道是否可用。
                // 页面运行在 quickjs+wasm 沙箱内，未必能访问 window（JS bridge 可能不可达），
                // 此时 https://ink.local/__lab/... 是页面上行的唯一可行通道。
                if (assetPath.startsWith("__lab/")) {
                    Log.i(
                        TAG,
                        "lab endpoint hit: $assetPath method=${request?.method} " +
                            "main=${request?.isForMainFrame} redirect=${request?.isRedirect} " +
                            "hdrs=${request?.requestHeaders}"
                    )
                    return when {
                        assetPath.startsWith("__lab/ping") -> {
                            // 诊断日志：确认真机上是「响应没构造」还是「构造了但 Chromium 没收」
                            Log.i(TAG, "ping: building response")
                            val r = textResponse("pong")
                            Log.i(
                                TAG,
                                "ping: response ready status=${r.statusCode} mime=${r.mimeType} " +
                                    "hdrs=${r.responseHeaders} avail=${runCatching { r.data?.available() }.getOrNull()}"
                            )
                            r
                        }
                        // 对照端点：**不挂任何自定义 header** 的最小响应体。
                        // 与 __lab/ping（带 Content-Length）对比，可判定是不是响应头让 Chromium 挂起。
                        assetPath.startsWith("__lab/echo") -> {
                            Log.i(TAG, "echo: building minimal response (no custom headers)")
                            WebResourceResponse(
                                "text/plain", "UTF-8",
                                ByteArrayInputStream("e".toByteArray(Charsets.UTF_8))
                            )
                        }
                        assetPath.startsWith("__lab/tool_result") -> {
                            // 页面 realm 的 Lab 桥轮询结果：缓存里有就返回，否则返回 pending。
                            val cb = request?.url?.getQueryParameter("cbId")?.takeIf { it.isNotBlank() }
                            val cached = cb?.let { takeCachedToolResult(it) }
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
                                    // ⚠️ 本响应在页面 Lab 桥下**没有消费者**（fire-and-forget fetch，
                                    // 绝不 await/.then —— 见 lab-page-bridge.js 头部），只回 textResponse
                                    // 页面永远收不到 ⇒ pending[cbId] 永挂、loading 永转（闭环审计 R8）。
                                    // 错误必须同时经 dispatchHostMessage 派发进 onMessage 通道
                                    // （deliverToolResult 顺带写入轮询缓存，兼容旧轮询桥）。
                                    deliverToolResult(cbId, false, null, "tool timed out")
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
        // 传整屏尺寸；auto-height 布局下高度仅作初值，内容尺寸回调后收缩并垂直居中
        bootJs(contentW, contentH)
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
            // BACK → Backspace 已注入页面；若页面不消费，双击 BACK 逃生退出宿主。
            // 双击判定只认**首次按下**（repeatCount==0）：长按 BACK 时系统约 50ms 连发
            // ACTION_DOWN，不过滤 repeat 的话按住 1.5s 必然被误判成双击 → 强退宿主
            if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_DOWN &&
                event.repeatCount == 0
            ) {
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
        // 工具结果的缓存与唤醒已上移到 [dispatchMessageToActive]（静态），本方法只负责投递页面：
        // 原来在这里做缓存时，缓存逻辑整块被包在「字符串 contains type=toolResult」判断里，
        // 且只在前台宿主实例上执行 —— 两条都可能让结果静默丢失，页面只能等到超时。
        // U+2028/U+2029 是合法 JSON 字符但 JSONObject.toString() 不转义，而它们在 JS 源码里
        // 是行终止符：直接内插会让 evaluateJavascript 语法错误静默失败（页面 Promise 挂到超时）
        val safeJson = json.replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")
        val js = "window.__aiuiHost && window.__aiuiHost.hostMessage($safeJson)"
        runOnUiThread { webView?.evaluateJavascript(js, null) }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        // 注销全部传感器监听（IMU 头控），避免 Activity 销毁后仍持有回调
        synchronized(sensorLock) {
            val sm = sensorManager
            for (listener in activeSensorListeners.values) {
                runCatching { sm?.unregisterListener(listener) }
            }
            activeSensorListeners.clear()
        }
        runCatching {
            webView?.loadUrl("about:blank")
            webView?.stopLoading()
        }
        val destroyed = webView
        webView?.destroy()
        webView = null
        // 只清掉本实例登记的那一个（避免旧实例销毁时误清新实例的引用）
        if (destroyed != null && activeWebView?.get() === destroyed) activeWebView = null
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

        /**
         * 换包重开专用：companion 级 Handler 不随实例 onDestroy 的
         * removeCallbacksAndMessages(null) 被清队（实例 mainHandler 会被全清）。
         * 任务只捕获 applicationContext，无 Activity 泄漏。
         */
        private val reopenHandler = Handler(Looper.getMainLooper())

        init {
            // R9：push 写线程 IOException 的页面侧消费钩子（类加载时注册一次，幂等）。
            // SENT 只代表"已提交写线程"——半开 socket 下真正 write 才失败，此时调用方早已返回；
            // 只有这个回调能把失败按 cbId 送回页面（否则页面只能等到超时，且零真因）。
            // dispatchMessageToActive 自带"缓存 + 唤醒同步等待 + 投递前台宿主"三件事，直接复用。
            AsrPushServer.onWriteFailed = { text ->
                if (text.startsWith(AsrPushServer.CTRL_TOOL_CALL)) {
                    val cbId = runCatching {
                        JSONObject(text.removePrefix(AsrPushServer.CTRL_TOOL_CALL)).optString("cbId")
                    }.getOrDefault("")
                    if (cbId.isNotBlank()) {
                        Log.w(TAG, "tool call frame write failed (cbId=$cbId)")
                        runCatching {
                            dispatchMessageToActive(
                                JSONObject()
                                    .put("type", "toolResult")
                                    .put("cbId", cbId)
                                    .put("ok", false)
                                    .put("error", "phone link write failed")
                                    .toString(),
                            )
                        }
                    }
                }
            }
        }

        private val lock = Any()
        private var activeActivity: AiuiLinkActivity? = null

        /**
         * 工具调用结果缓存：页面 realm 的 Lab 桥经 fetch 轮询 __lab/tool_result 拉取（兼容旧桥）。
         * 键 cbId → 结果 JSON 文本。带 TTL，避免长期驻留。
         *
         * **必须静态**：工具结果下行由 KeyButtonService 经 [dispatchMessageToActive] 送进来，
         * 那一刻宿主 Activity 未必处于 started（`onStop` 会清空 activeActivity）。原先两张表
         * 是实例字段，一旦 activeActivity 为空就整条丢弃 —— 工具其实已执行，页面却必然等到超时。
         */
        private val toolResults = mutableMapOf<String, Pair<Long, String>>()
        private val toolResultLock = Any()

        /** 同步工具调用阻塞队列：__lab/tool_call_sync 端点会等待此队列，结果到达后立即返回（静态理由同上）。 */
        private val syncToolQueues = ConcurrentHashMap<String, ArrayBlockingQueue<String>>()

        /**
         * 同步工具调用最长阻塞时间。
         *
         * **必须显著大于手机端 ToolGateway.CALL_TIMEOUT_MS（15s）**：两侧窗口相等时眼镜端
         * 几乎总是先超时（它的计时从发起上行走起，而手机端从收到后 join 起算，且眼镜端还多承担
         * 推送/线程调度开销），于是手机端真正有用的错误（限流、参数非法、执行异常）永远回不到
         * 页面，用户只看到笼统的 "tool timed out"。留 10s 余量让结果先到。
         */
        private const val SYNC_TOOL_TIMEOUT_MS = 25_000L

        /** 工具结果缓存 TTL：页面轮询窗口内有效即可 */
        private const val TOOL_RESULT_TTL_MS = 30_000L

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
            // 先无条件缓存 + 唤醒同步等待队列，再尝试投递给前台宿主。
            // 顺序与解耦都很关键：宿主不在前台（activeActivity=null）时投递必然失败，
            // 但发起 fetch 的 WebView 拦截线程仍在阻塞等待 —— 不在这里唤醒，页面只能等到超时。
            deliverToolResultIfAny(json)
            val a = synchronized(lock) { activeActivity }
            // 第二通道：直接投给 WebView。
            // ⚠️ 真机实测（2026-09-17）：页面明明在正常渲染（onLoad 已跑、view focused），
            //    但 activeActivity 仍为 null（onStop 被调用而 onStart 未再触发）——
            //    只靠 activeActivity 会让「工具已执行、结果已送达眼镜」的消息静默丢弃，
            //    页面侧表现为调用永远没有回调。WebView 只要没销毁就能 evaluateJavascript，
            //    所以这里以 WebView 引用兜底。
            val wv = activeWebView?.get()
            Log.i(TAG, "dispatchMessageToActive len=${json.length} active=${a != null} wv=${wv != null}")
            when {
                a != null -> a.dispatchHostMessage(json)
                wv != null -> {
                    val js = "window.__aiuiHost && window.__aiuiHost.hostMessage($json)"
                    wv.post { runCatching { wv.evaluateJavascript(js, null) } }
                }
                else -> Log.w(TAG, "dispatchMessageToActive: no host, message cached only")
            }
        }

        /** 当前宿主的 WebView 弱引用（见 [dispatchMessageToActive] 第二通道）。主线程写，任意线程读。 */
        @Volatile
        private var activeWebView: java.lang.ref.WeakReference<WebView>? = null

        /**
         * 若 [json] 是工具结果（type=toolResult），写入轮询缓存并唤醒同步等待队列。
         * 用权威 JSON 解析判定 type，不做字符串 contains —— 后者对空白/字段顺序敏感，
         * 一旦未命中就会静默跳过缓存，让页面等到超时。
         */
        private fun deliverToolResultIfAny(json: String) {
            val obj = try {
                JSONObject(json)
            } catch (e: Exception) {
                Log.w(TAG, "tool result not valid JSON, skip cache: ${e.message}")
                return
            }
            if (obj.optString("type") != "toolResult") return
            cacheToolResult(obj.optString("cbId"), json)
        }

        /** 把工具结果写入轮询缓存，并唤醒同步等待队列。 */
        private fun cacheToolResult(cbId: String, json: String) {
            if (cbId.isBlank()) return
            synchronized(toolResultLock) {
                toolResults[cbId] = Pair(System.currentTimeMillis() + TOOL_RESULT_TTL_MS, json)
                val it = toolResults.entries.iterator()
                val now = System.currentTimeMillis()
                while (it.hasNext()) {
                    if (it.next().value.first < now) it.remove()
                }
            }
            // 唤醒同步工具调用等待者（如果有）
            val q = syncToolQueues[cbId]
            if (q != null && !q.offer(json)) {
                Log.w(TAG, "tool result queue already filled for cbId=$cbId (1-slot), dropped")
            }
        }

        /** 取走并清除某 cbId 的缓存结果（供 __lab/tool_result 轮询端点使用）。 */
        private fun takeCachedToolResult(cbId: String): String? = synchronized(toolResultLock) {
            toolResults.remove(cbId)?.second
        }

        /** 若宿主在前台，直接关闭（手机侧 "close" 命令 / 切换包前清理） */
        @JvmStatic
        fun closeActive() {
            synchronized(lock) {
                val a = activeActivity
                a?.runOnUiThread { a.finish() }
            }
        }

        /**
         * 触摸板手势 → 页面：把一次 ink 键盘注入正在渲染的宿主；无宿主（或页面还没 boot）返回 false。
         *
         * 存在的理由：眼镜端触摸板的 `ACTION_SWIPE_*` / `ACTION_TWO_FINGER_*` 广播此前**零消费**，
         * AIUI 页面根本收不到滑动/双击手势。这里复用与物理键**完全相同**的注入通道
         * （[AiuiLinkActivity.injectKey]），页面侧看到的输入没有任何差别。
         *
         * 返回值是给调用方决定要不要 abortBroadcast 的依据 —— 没人消费时不能把按键吞掉。
         */
        @JvmStatic
        fun injectKeyToActive(code: String, action: String): Boolean {
            val a = synchronized(lock) { activeActivity }
            if (a != null) {
                if (!a.booted) {
                    Log.d(TAG, "touchpad key(not booted) code=$code")
                    return false
                }
                a.injectKey(code, action)
                return true
            }
            val wv = activeWebView?.get() ?: return false
            val js = "window.__aiuiHost && window.__aiuiHost.key(${JSONObject.quote(code)},'$action')"
            wv.post { runCatching { wv.evaluateJavascript(js, null) } }
            return true
        }
    }

    override fun onStart() {
        super.onStart()
        // 🚨【2026-10-04 修复「AIUI 管理→关闭当前」按钮失效】
        // 本项目**全文件只有 onStop 里那一句把 activeActivity 置 null，
        // 却从没有任何一处把它赋成 this** ⇒ `closeActive()` 里
        //   `val a = activeActivity; a?.runOnUiThread { a.finish() }`
        // 永远拿到 null ⇒ 手机侧 `closeAiuiHost()` 下发了 close 命令、
        // 眼镜端 AiuiHostController 也确实执行了 `AiuiLinkActivity.closeActive()`，
        // 但什么都不会发生 —— 表现就是「关闭当前按钮点了没反应」。
        // 同一条链路还有第二个坑：`dispatchMessageToActive` / `injectKeyToActive`
        // 也都读 activeActivity，同一个空引用让「工具消息回不到页面」一并静默失效。
        // ⇒ 在 onStart 登记（与 onStop 的清理成对），而不是 onResume：
        //   closeActive 要在 Activity 只是 started 时也能命中。
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
        // 必须投到 companion 级 Handler：实例的 mainHandler 在 onDestroy 里
        // removeCallbacksAndMessages(null) 全量清队，finish → onDestroy 通常远小于
        // 150ms，重开任务会被一并撤掉 → 换包后旧宿主关了、新包永远不打开（黑屏）
        reopenHandler.postDelayed({ open(app, p, params) }, 150L)
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
            // 缓存一份供页面 realm 的 Lab 桥轮询拉取，并唤醒阻塞中的同步 fetch
            cacheToolResult(cbId, payload.toString())
            dispatchHostMessage(payload.toString())
        } catch (e: Exception) {
            Log.w(TAG, "deliverToolResult failed: ${e.message}")
        }
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
            mutableMapOf(
                "Content-Length" to bytes.size.toString(),
                // 页面脚本运行在 WebView 内部的 `__ink_bundle__` 虚拟路径下，其 origin 与
                // BASE_URL(https://ink.local) 不一定同源；缺 CORS 头时 Chromium 会直接拒收
                // 拦截响应，页面侧表现为 fetch 永远 pending（实测：连 __lab/ping 都收不到）。
                // 三个头都要给：跨域 fetch 会先发 OPTIONS preflight，只回 ACAO 会让 preflight 失败。
                "Access-Control-Allow-Origin" to "*",
                "Access-Control-Allow-Methods" to "GET, POST, OPTIONS",
                "Access-Control-Allow-Headers" to "*",
                "Cache-Control" to "no-store",
            ),
            ByteArrayInputStream(bytes)
        )
    }

    private fun jsonStr(s: String?): String {
        if (s == null) return "null"
        return JSONObject.quote(s)
    }

    /**
     * 是否看起来是远程图片资源。
     *
     * 只代理图片，**不**代理任意外网请求：页面与外网交互的唯一合法通道是 `Lab.callTool`，
     * 若把普通 fetch 也代理掉，会顺带绕过 CORS 让「页面自己拉外网 API」这种违规写法也能跑通。
     */
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
     * `shouldInterceptRequest` 运行在 WebView 的 IO 线程，可以同步做网络请求。
     * 失败时返回 null（交回 WebView 默认逻辑），不影响页面其它行为。
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
                    "Mozilla/5.0 (Linux; Android 12; RG-glasses) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Version/4.0 Chrome/95.0.4638.74 Mobile Safari/537.36"
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
                Log.i(TAG, "proxyImage ok: ${bytes.size}B $mime $url")
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
