package com.rokidlab.phone.glasses

import android.app.Presentation
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.compose.ui.graphics.toArgb
import com.rokid.cxr.Caps
import com.rokidlab.phone.R
import com.rokidlab.phone.ai.GlassToolConfirmChannel
import com.rokidlab.phone.ai.ToolRegistry
import com.rokidlab.phone.design.BrewTextBright
import com.rokidlab.phone.store.htmlFor
import com.rokidlab.phone.store.newRenderWebView
import com.rokidlab.phone.store.WebRenderKind
import com.rokidlab.phone.store.WebRenderKind.CHART
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * 图表推送眼镜（LinkProtocol v2 `Cap.CHART_VIEW`）：把对话里的 ECharts 图表离屏渲染成图片，
 * 经 [AiChannel.TOPIC_CHART_PUSH] 下发到眼镜端全屏查看器（见眼镜端 ChartViewerController）。
 *
 * ## 为什么离屏重渲染，而不是截取卡片里已渲染的 WebView
 * 卡片 WebView 的尺寸受气泡宽度/封顶高度约束（300~420px），直接 getDataURL 只能拿到
 * 小图；而眼镜全屏查看需要一张清晰的成图。离屏 WebView 固定 480×640（眼镜面板
 * 原生分辨率，1:1 像素最清晰）重新起稿，画质可控，
 * 且卡片按钮与 AI 工具（push_chart_to_glasses）共用同一条渲染路径。
 *
 * ## 离屏 WebView 的三个坑（本实现的关键）
 *  1. **未 attach 的 WebView 不泵 vsync → `requestAnimationFrame` 永不回调**。
 *     渲染卡片的 HTML 用 rAF 做 resize 兜底 + 高度回报，直接复用会静默白屏。
 *     快照模式（`htmlFor(snapshot = true)`）改用 `setTimeout`（timer 线程驱动，
 *     与帧管线无关）+ 渲染完直接 `echarts.getDataURL()` 导出 PNG。
 *  2. **未 attach 不自动布局** → 必须手动 `measure(EXACTLY) + layout`，否则
 *     canvas 按 0×0 起稿，同样白屏。
 *  3. **未 attach 画字字形错乱（真机确诊的「乱码」根因）**。JS 侧字符串完全正确
 *     （console 可验证），但 canvas 绘字走了异常字体路径，中文全部变成错误字形。
 *     解法：不给裸离屏 WebView，改用 **VirtualDisplay + Presentation** 给它一个
 *     真实附着窗口（有 surface、完整渲染管线），字体正常。
 *
 * ## 体积与画质
 * 图表 = 纯色块 + 文字，echarts 导出的 PNG（480×640 实测 ~35KB）直接无损直传 ——
 * 有损 JPEG 会在细小文字上产生晕边（真机表现为「文字有描边、发糊」，2026-10-07
 * 确诊后废弃 JPEG 直传主路径）。仅当 PNG 超 [MAX_B64_CHARS]（极复杂图表）才降级
 * JPEG 分级压缩（q85 → q70 → 480 q70），全超限则放弃并如实回报。
 *
 * ## 重发（单击恢复）
 * 每次成功编码的图都进 [cache]（chartId → b64，最近 [CACHE_MAX] 张）。眼镜端查看器里
 * 单击/短按时经 [AiChannel.TOPIC_CHART_RESEND] 上行 chartId，[onResendRequest] 按缓存
 * 重发同一张图 —— 覆盖「解码失败/传输损坏」的自助恢复，未命中则静默忽略。
 */
object ChartGlassPush {
    private const val TAG = "ChartGlassPush"

    /** 快照渲染尺寸 = 眼镜面板原生分辨率（实测 wm size 480x640），1:1 像素最清晰。 */
    private const val SNAPSHOT_W = 480
    private const val SNAPSHOT_H = 640

    /** 快照渲染超时：与渲染卡同因（脚本没跑起来时 JS 永不上报），8s 覆盖 1MB 脚本解析 */
    private const val RENDER_TIMEOUT_MS = 8_000L
    private const val RENDER_WAIT_MS = 10_000L

    /** 眼镜端通道 Base64 体积上限（与 DisplayToolProvider.MAX_B64_CHARS 同源口径） */
    private const val MAX_B64_CHARS = 400_000

    /** 重发缓存容量：最近 6 张 */
    private const val CACHE_MAX = 6

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 同一时刻只允许一次渲染（WebView 离屏渲染不可重入，多图排队串行而非丢弃） */
    private val renderBusy = AtomicBoolean(false)

    /** 在途自动推送计数：awaitIdle 供语音播报前串行化用 */
    private val inFlight = AtomicInteger(0)

    private data class RenderJob(val context: Context, val source: String, val onDone: (String?) -> Unit)

    /** 渲染排队（多图同回合时逐张渲染发送，reject 会静默丢图） */
    private val renderQueue = ArrayDeque<RenderJob>()

    /** chartId → base64 图（PNG 优先，超限降级 JPEG；LRU，重发请求按 id 找回） */
    private val cache = object : LinkedHashMap<String, String>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>): Boolean =
            size > CACHE_MAX
    }

    /**
     * 渲染并推送（异步）。卡片「推眼镜」按钮入口；onResult 在主线程回调一条
     * 用户可读的结果文案（资源串）。
     */
    fun push(context: Context, source: String, caption: String, onResult: (String) -> Unit) {
        inFlight.incrementAndGet()
        val report: (String) -> Unit = { msg ->
            inFlight.decrementAndGet()
            onResult(msg)
        }
        renderSnapshot(context, source) { pngB64 ->
            mainHandler.post {
                if (pngB64 == null) {
                    report(context.getString(R.string.chart_push_render_failed))
                    return@post
                }
                // 压缩 + 发送可能阻塞（网络/握手等待），切后台线程
                Thread {
                    val chartId = System.currentTimeMillis().toString()
                    val msg = encodeAndSend(context, chartId, pngB64, caption)
                    mainHandler.post { report(msg) }
                }.apply { name = "chart-push-send"; isDaemon = true }.start()
            }
        }
    }

    /**
     * 等待所有在途推送完成（阻塞，超时放行）。语音播报前调用：16KB 图表突发与
     * tts_play 挤同一条 BT socket 会把小语音帧挤丢（2026-10-06 真机：图表回合
     * tts_play 无接收记录，眼镜无声且流程卡死）。
     */
    fun awaitIdle(timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (inFlight.get() > 0 && System.currentTimeMillis() < deadline) {
            runCatching { Thread.sleep(50) }
        }
    }

    /**
     * 渲染并推送（阻塞）。AI 工具 push_chart_to_glasses 入口：ToolGateway 在 worker
     * 线程调用，同步等结果以便把真实状态回给模型（与 show_image 同一设计）。
     * @return 给模型的状态文本
     */
    fun pushBlocking(context: Context, source: String, caption: String): String {
        val pngB64 = renderSnapshotBlocking(context, source)
            ?: return context.getString(R.string.chart_push_render_failed)
        val chartId = System.currentTimeMillis().toString()
        return encodeAndSend(context, chartId, pngB64, caption)
    }

    /** 眼镜端 chart_resend 上行（CXR binder 线程进入）：按 chartId 重发缓存帧。 */
    fun onResendRequest(chartId: String) {
        val b64 = synchronized(cache) { cache[chartId] }
        if (b64 == null) {
            Log.i(TAG, "chart_resend: cache miss for $chartId, ignore")
            return
        }
        val session = GlassToolConfirmChannel.global.liveSession() ?: run {
            Log.i(TAG, "chart_resend: no live session, ignore")
            return
        }
        val link = session.cxrLink ?: run {
            Log.i(TAG, "chart_resend: link is null, ignore")
            return
        }
        try {
            val caps = Caps()
            for (f in AiChannel.encodeChartPush(chartId, b64, "")) caps.write(f)
            val r = session.rawSendCustomCmd(link, AiChannel.TOPIC_CHART_PUSH, caps)
            Log.i(TAG, "chart_resend $chartId -> $r")
        } catch (e: Exception) {
            Log.w(TAG, "chart_resend failed: ${e.message}")
        }
    }

    // ──────────────────────────────────────────────
    //  压缩 + 发送
    // ──────────────────────────────────────────────

    /**
     * PNG 快照优先无损直传：图表 = 纯色块 + 文字，PNG 体积 ~35KB 远低于上限，且文字
     * 无 JPEG 晕边（真机确诊的「描边感模糊」根因，2026-10-07）→ 超限才 JPEG 分级降质
     * → 进缓存 → 下发。返回用户/模型可读文案。
     */
    private fun encodeAndSend(context: Context, chartId: String, pngB64: String, caption: String): String {
        val b64: String
        val dumpExt: String
        if (pngB64.length <= MAX_B64_CHARS) {
            b64 = pngB64
            dumpExt = "png"
        } else {
            val bytes = runCatching { Base64.decode(pngB64, Base64.DEFAULT) }.getOrNull()
            val bmp = bytes?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull() }
            b64 = if (bmp == null) {
                Log.w(TAG, "chart png decode failed on oversize fallback")
                return context.getString(R.string.chart_push_render_failed)
            } else {
                encodeLadder(bmp) ?: run {
                    Log.w(TAG, "chart encode exceeded ${MAX_B64_CHARS} chars at all rungs, abort")
                    return context.getString(R.string.chart_push_failed)
                }
            }
            dumpExt = "jpg"
        }
        // 调试留档：眼镜端若显示异常，pull 此文件即可判断「手机渲染的字是否已坏」
        runCatching {
            val dir = context.getExternalFilesDir("debug") ?: return@runCatching
            java.io.File(dir, "chart_snapshot_last.jpg").delete()
            java.io.File(dir, "chart_snapshot_last.png").delete()
            val f = java.io.File(dir, "chart_snapshot_last.$dumpExt")
            f.writeBytes(Base64.decode(b64, Base64.NO_WRAP))
            Log.i(TAG, "debug dump: ${f.absolutePath}")
        }
        synchronized(cache) { cache[chartId] = b64 }
        return when (sendToGlasses(context, chartId, b64, caption)) {
            SendResult.OK -> context.getString(R.string.chart_push_ok)
            SendResult.NOT_CONNECTED -> context.getString(R.string.chart_push_not_connected)
            SendResult.UNSUPPORTED -> context.getString(R.string.chart_push_unsupported)
            SendResult.FAILED -> context.getString(R.string.chart_push_failed)
        }
    }

    private enum class SendResult { OK, NOT_CONNECTED, UNSUPPORTED, FAILED }

    /**
     * 下发到眼镜端。会话/握手逻辑与 DisplayToolProvider.pushImageToGlasses 同一口径：
     * 能力位 [LinkProtocol.Cap.CHART_VIEW] 不支持 → UNSUPPORTED；RokidLink 未跑 → 先
     * ensureGlassesLinkRunning 拉一次再复查（CUSTOMAPP 指令只有订阅者能收到）。
     */
    private fun sendToGlasses(context: Context, chartId: String, b64: String, caption: String): SendResult {
        var capSupport = GlassesHandshake.supports(LinkProtocol.Cap.CHART_VIEW)
        if (capSupport == false) {
            Log.i(TAG, "glasses does not support chart_view (old build), skip push")
            return SendResult.UNSUPPORTED
        }
        var session = GlassToolConfirmChannel.global.liveSession()
        var link = session?.cxrLink
        if (session == null || link == null || capSupport == null) {
            if (ToolRegistry.ensureGlassesLinkRunning(context)) {
                runCatching { Thread.sleep(1500) }
                capSupport = GlassesHandshake.supports(LinkProtocol.Cap.CHART_VIEW)
                session = GlassToolConfirmChannel.global.liveSession()
                link = session?.cxrLink
                Log.i(TAG, "after ensureGlassesLinkRunning: capSupport=$capSupport session=${session != null}")
            }
        }
        if (session == null || link == null) {
            Log.i(TAG, "glasses not connected, skip chart push (capSupport=$capSupport)")
            return SendResult.NOT_CONNECTED
        }
        return try {
            val caps = Caps()
            for (f in AiChannel.encodeChartPush(chartId, b64, caption)) caps.write(f)
            val r = session.rawSendCustomCmd(link, AiChannel.TOPIC_CHART_PUSH, caps)
            Log.i(TAG, "push chart $chartId to glasses: base64Len=${b64.length} -> $r")
            if (r == 0) SendResult.OK else SendResult.FAILED
        } catch (e: Exception) {
            Log.w(TAG, "push chart to glasses failed: ${e.message}")
            SendResult.FAILED
        }
    }

    /**
     * 分级降质：长边 640 q85 → q70 → 480 q70。图表信息密度高，先用高质量；
     * 超限才降，全超限返回 null（调用方放弃）。480×640 q85 远小于上限，正常只走第一档。
     */
    private fun encodeLadder(src: Bitmap): String? {
        val longSide = maxOf(SNAPSHOT_W, SNAPSHOT_H)
        var out = compressToBase64(src, longSide, 85)
        if (out.length > MAX_B64_CHARS) out = compressToBase64(src, longSide, 70)
        if (out.length > MAX_B64_CHARS) out = compressToBase64(src, 480, 70)
        if (out.length > MAX_B64_CHARS) return null
        return out
    }

    /** 长边缩放 → JPEG(quality) → Base64 NO_WRAP（换行会破坏载荷，与 show_image 同因） */
    private fun compressToBase64(src: Bitmap, maxSide: Int, quality: Int): String {
        val scaled = if (src.width <= maxSide && src.height <= maxSide) {
            src
        } else {
            val ratio = maxSide.toFloat() / maxOf(src.width, src.height)
            val w = (src.width * ratio).toInt().coerceAtLeast(1)
            val h = (src.height * ratio).toInt().coerceAtLeast(1)
            runCatching { Bitmap.createScaledBitmap(src, w, h, true) }.getOrDefault(src)
        }
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    // ──────────────────────────────────────────────
    //  离屏快照渲染
    // ──────────────────────────────────────────────

    /** 快照桥：只暴露 postSnapshot / postFail（快照 HTML 不调 postHeight，见 htmlFor） */
    private class SnapshotBridge(
        private val onSnapshot: (String) -> Unit,
        private val onFail: (String) -> Unit,
    ) {
        @JavascriptInterface
        fun postSnapshot(base64Png: String?) {
            val b64 = base64Png?.takeIf { it.isNotBlank() }
            Log.i(TAG, "postSnapshot: len=${b64?.length ?: 0}")
            if (b64 == null) onFail("empty snapshot") else onSnapshot(b64)
        }

        @JavascriptInterface
        fun postFail(reason: String?) {
            Log.w(TAG, "snapshot postFail: ${reason?.take(200)}")
            onFail(reason?.take(120).orEmpty())
        }
    }

    private fun renderSnapshot(context: Context, source: String, onDone: (String?) -> Unit) {
        synchronized(renderQueue) { renderQueue.addLast(RenderJob(context, source, onDone)) }
        drainRenderQueue()
    }

    /** 串行取队首渲染（渲染不可重入：上一张 finish 后才起下一张） */
    private fun drainRenderQueue() {
        if (!renderBusy.compareAndSet(false, true)) return
        val job = synchronized(renderQueue) { renderQueue.removeFirstOrNull() } ?: run {
            renderBusy.set(false)
            return
        }
        mainHandler.post {
            var webView: WebView? = null
            var presentation: Presentation? = null
            var virtualDisplay: VirtualDisplay? = null
            var imageReader: ImageReader? = null
            val finished = AtomicBoolean(false)
            fun finish(pngB64: String?) {
                if (!finished.compareAndSet(false, true)) return
                mainHandler.post {
                    // 释放必须在主线程；JavascriptInterface 回调来自 binder 线程，统一 post 收敛
                    runCatching { presentation?.dismiss() }
                    runCatching { webView?.destroy() }
                    runCatching { virtualDisplay?.release() }
                    runCatching { imageReader?.close() }
                    presentation = null; virtualDisplay = null; imageReader = null; webView = null
                    renderBusy.set(false)
                    drainRenderQueue()
                    job.onDone(pngB64)
                }
            }
            try {
                // VirtualDisplay + Presentation：给 WebView 一个真实附着窗口（有 surface、
                // 完整渲染管线）。纯离屏（不 attach）时 canvas 绘字字形会错乱——JS 侧字符串
                // 正确但中文全部画成错字（真机确诊的乱码根因），详见类注释「第三个坑」。
                val dm = job.context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
                val reader = ImageReader.newInstance(SNAPSHOT_W, SNAPSHOT_H, PixelFormat.RGBA_8888, 2)
                val vd = dm.createVirtualDisplay(
                    "chart_snapshot", SNAPSHOT_W, SNAPSHOT_H,
                    job.context.resources.displayMetrics.densityDpi,
                    reader.surface,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION,
                )
                val pres = Presentation(job.context, vd.display)
                val frame = FrameLayout(job.context)
                val wv = newRenderWebView(job.context)
                // 强制软件栅格化：虚拟屏 GPU 管线上 canvas 字形图集仍可能异常（真机
                // 「圆形两侧乱码、原生文字正常」），软件路径绕开 GPU 字形合成。
                wv.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                webView = wv
                frame.addView(wv, FrameLayout.LayoutParams(SNAPSHOT_W, SNAPSHOT_H))
                pres.setContentView(frame)
                presentation = pres
                virtualDisplay = vd
                imageReader = reader
                pres.show()
                val bridge = SnapshotBridge(
                    onSnapshot = { b64 ->
                        // PNG 原样直传，这里不解码（体积/格式兜底在 encodeAndSend）
                        finish(b64)
                    },
                    onFail = { finish(null) },
                )
                wv.addJavascriptInterface(bridge, "androidBridge")
                // show() 后仍手动 EXACTLY measure + layout，保证首帧前布局就绪（与 300ms
                // 快照时序赛跑，别依赖 traversal 的时机）
                wv.measure(
                    View.MeasureSpec.makeMeasureSpec(SNAPSHOT_W, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(SNAPSHOT_H, View.MeasureSpec.EXACTLY),
                )
                wv.layout(0, 0, SNAPSHOT_W, SNAPSHOT_H)
                wv.loadDataWithBaseURL(
                    "file:///android_asset/chat_render/",
                    htmlFor(
                        kind = CHART,
                        source = job.source,
                        textColorHex = BrewTextBright.toArgb(),
                        snapshot = true,
                    ),
                    "text/html",
                    "utf-8",
                    null,
                )
                mainHandler.postDelayed({ finish(null) }, RENDER_TIMEOUT_MS)
            } catch (e: Exception) {
                Log.e(TAG, "renderSnapshot setup failed", e)
                finish(null)
            }
        }
    }

    /** 阻塞版渲染（worker 线程调用）：latch 等待主线程渲染完成。返回裸 base64 PNG。 */
    private fun renderSnapshotBlocking(context: Context, source: String): String? {
        val latch = CountDownLatch(1)
        var result: String? = null
        renderSnapshot(context, source) { pngB64 ->
            result = pngB64
            latch.countDown()
        }
        if (!latch.await(RENDER_WAIT_MS, TimeUnit.MILLISECONDS)) {
            Log.w(TAG, "renderSnapshotBlocking timed out")
        }
        return result
    }
}
