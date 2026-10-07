package com.rokidlab.rokidlink

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.util.Base64
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.rokid.cxr.Caps

/**
 * 图表全屏查看器（手机端对话里的 ECharts 图表 → 眼镜端，LinkProtocol v2 `Cap.CHART_VIEW`）。
 *
 * 与 [GlassesOverlayController] 的小图片悬浮层（12s 自动隐藏、限宽 520、不可交互）不同，
 * 本查看器是**全屏 + 常驻 + 可按键操控**的：
 *  - 手机端经 [AiChannel.TOPIC_CHART_PUSH] 下发图表（离屏渲染好的 JPEG）；
 *  - 查看器把最近 ≤[MAX_SLIDES] 张收成队列，新图自动弹出全屏显示；
 *  - 触摸板左/右滑切换上/下一张、单击（或短按）请求手机端重发当前图、
 *    双击/长按退出（按键拦截见 [KeyRouteCoordinator]，查看器活跃时优先吃掉按键）。
 *
 * ## 为什么用全屏 Overlay 而不是 Activity
 * KeyButtonService 类注释的铁律：本应用不能持有常驻可见 Activity —— 透明/全屏 Activity 会
 * 成为眼镜顶层 resumed/焦点应用，官方 Launcher 退居后台，触摸板与官方按键全部失灵。
 * `TYPE_APPLICATION_OVERLAY` 全屏窗不改变顶层 resumed 身份；但本查看器**刻意可聚焦**——
 * 触摸板手势在本设备不走 com.android.action 广播，而是 DPAD 键直达前台应用，只有窗口
 * 持有按键焦点才能拦截（见 openViewerIfNeed 内注释）。查看器关闭即归还焦点。
 * 物理按键广播族（CLICK/DOUBLE_CLICK/LONG_PRESS）由 KeyRouteCoordinator（priority=9999）
 * 并行兜底。
 *
 * ## 授权与生命周期
 * 复用歌词/工具确认同一套 `SYSTEM_ALERT_WINDOW` 授权（手机端经 ADB appops 下发），
 * 未授权时 addView 失败会高声告警（见 canShowOverlay 的历史教训）。查看器关闭只移除视图，
 * 队列保留 —— 下一张图推来即恢复显示，无需重新传输。
 */
internal class ChartViewerController(
    private val service: KeyButtonService,
    private val core: KeyServiceCore,
) {
    companion object {
        private const val TAG = KeyButtonService.TAG
        /** 查看器队列容量：最近 5 张，超出淘汰最旧（蓝牙通道体积有限，队列再多也翻不过来） */
        private const val MAX_SLIDES = 5
        /** 重发请求去重窗口：一次短按会产生 UP/CLICK 两条广播，只上行一次 */
        private const val RESEND_DEDUP_MS = 800L
    }

    /** 队列里的一张图 */
    private data class Slide(val chartId: String, val bmp: Bitmap, val caption: String)

    private val slides = ArrayList<Slide>()
    private var currentIndex = 0

    private var container: LinearLayout? = null
    private var imageView: ImageView? = null
    private var captionView: TextView? = null
    private var indexView: TextView? = null
    private var wm: WindowManager? = null

    private var lastResendMs = 0L

    /** 查看器当前是否全屏在前台（按键拦截依据） */
    val isActive: Boolean get() = container != null

    /**
     * 手机端下发图表（[AiChannel.TOPIC_CHART_PUSH]）。CXR 回调线程进入，解码切主线程。
     * 同 chartId 重发 = 原位替换（单击重发/恢复场景），新 chartId = 入队并显示。
     */
    fun handleChartPush(args: Caps?) {
        val decoded = AiChannel.decodeChartPush(capsToStrings(args))
        if (decoded == null) {
            Log.w(TAG, "chart_push: rejected invalid payload (size=${args?.size()})")
            return
        }
        val (chartId, b64, caption) = decoded
        Log.i(TAG, "chart_push received: id=$chartId b64Len=${b64.length} caption='${caption.take(30)}'")
        core.mainHandler.post {
            val bytes = runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrNull()
            if (bytes == null) {
                Log.e(TAG, "chart_push: base64 decode failed (len=${b64.length})")
                return@post
            }
            val bmp = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
            if (bmp == null) {
                Log.e(TAG, "chart_push: bitmap decode failed (bytes=${bytes.size})")
                return@post
            }
            show(chartId, bmp, caption)
        }
    }

    /** 入队/替换并显示（主线程）。队列满时淘汰最旧；index 跟随被显示的图。 */
    private fun show(chartId: String, bmp: Bitmap, caption: String) {
        val existing = slides.indexOfFirst { it.chartId == chartId }
        if (existing >= 0) {
            slides[existing] = Slide(chartId, bmp, caption)
            currentIndex = existing
        } else {
            slides.add(Slide(chartId, bmp, caption))
            if (slides.size > MAX_SLIDES) {
                slides.removeAt(0)
            }
            currentIndex = slides.size - 1
        }
        openViewerIfNeed()
        renderCurrent()
    }

    /** 下一张（右滑 = 时间上更新的一张，环绕） */
    fun next() {
        if (slides.isEmpty()) return
        currentIndex = (currentIndex + 1) % slides.size
        renderCurrent()
    }

    /** 上一张（左滑 = 时间上更旧的一张，环绕） */
    fun previous() {
        if (slides.isEmpty()) return
        currentIndex = (currentIndex - 1 + slides.size) % slides.size
        renderCurrent()
    }

    /**
     * 请求手机端重发当前图（单击/短按）。覆盖「解码失败/传输损坏后自助恢复」：
     * 手机端按 chartId 从最近推送缓存里重发；未命中或链路不通时眼镜端无视觉反馈，
     * 只记日志（重发成功时图片会原位刷新，本身就是反馈）。
     */
    fun requestResend() {
        val slide = slides.getOrNull(currentIndex) ?: return
        val now = System.currentTimeMillis()
        if (now - lastResendMs < RESEND_DEDUP_MS) return
        lastResendMs = now
        val b = core.bridge ?: run {
            Log.w(TAG, "chart_resend skipped: no CXR bridge")
            return
        }
        Thread {
            try {
                val caps = Caps()
                for (f in AiChannel.encodeChartResend(slide.chartId)) caps.write(f)
                val r = core.sendCxrWithTimeout(b, AiChannel.TOPIC_CHART_RESEND, caps)
                Log.i(TAG, "chart_resend(id=${slide.chartId}) -> $r")
            } catch (e: Throwable) {
                Log.e(TAG, "chart_resend error", e)
            }
        }.apply { name = "chart-resend"; isDaemon = true }.start()
    }

    /** 退出查看器（双击/长按）。只移除视图，队列保留。 */
    fun close() {
        container?.let { box ->
            runCatching { wm?.removeView(box) }
            container = null
            imageView = null
            captionView = null
            indexView = null
            wm = null
            Log.i(TAG, "chart viewer closed (deck=${slides.size})")
        }
    }

    /** 服务销毁时清理 */
    fun release() = close()

    // ──────────────────────────────────────────────
    //  视图（全屏 Overlay：黑底 + 标题/页码 + 图 + 底部操作提示）
    // ──────────────────────────────────────────────

    private fun openViewerIfNeed() {
        if (container != null) return
        if (!canShowOverlay()) return
        val wmSafe = wm ?: (service.getSystemService(android.content.Context.WINDOW_SERVICE) as? WindowManager)
            .also { wm = it } ?: return
        val iv = ImageView(service).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        val tvCaption = TextView(service).apply {
            textSize = 20f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            setPadding(24, 24, 24, 8)
        }
        val tvIndex = TextView(service).apply {
            textSize = 14f
            setTextColor(0xFF9AA4B2.toInt())
            gravity = Gravity.CENTER
            setPadding(24, 0, 24, 0)
        }
        val tvHint = TextView(service).apply {
            textSize = 13f
            setTextColor(0xFF8A93A0.toInt())
            gravity = Gravity.CENTER
            setPadding(24, 0, 24, 28)
            text = service.getString(R.string.chart_viewer_hint)
        }
        val box = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF000000.toInt())
            // 查看器活跃期间接管按键：触摸板手势在本设备上不产生 com.android.action 广播，
            // 而是以 DPAD 按键事件直达前台应用（2026-10-06 真机日志：滑动→KEYCODE_DPAD_*，
            // 我们（彼时 priority=100）的接收器零记录，官方聊天页被滑动）。窗口可聚焦后这些键进
            // onKey 由查看器消费；关闭窗口焦点自动归还官方界面。
            // 物理按键的广播族（CLICK/DOUBLE_CLICK/LONG_PRESS）仍由 KeyRouteCoordinator
            // priority=9999 的接收器兜底，两条通道互为补充。
            isFocusable = true
            isFocusableInTouchMode = true
            var lastEnterMs = 0L
            setOnKeyListener { _, keyCode, event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP -> { previous(); true }
                    KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN -> { next(); true }
                    KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> {
                        // 触摸板双击 = 两次快速确认键 → 退出；单击 = 重发
                        val now = System.currentTimeMillis()
                        if (now - lastEnterMs < 400L) {
                            lastEnterMs = 0L
                            Log.i(TAG, "chart viewer: double ENTER → close")
                            close()
                        } else {
                            lastEnterMs = now
                            requestResend()
                        }
                        true
                    }
                    KeyEvent.KEYCODE_BACK -> { close(); true }
                    else -> false
                }
            }
            addView(tvCaption)
            addView(tvIndex)
            addView(iv, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(tvHint)
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            0,  // 不设 NOT_FOCUSABLE：必须拿到按键焦点才能拦截触摸板 DPAD（见 box 注释）
            PixelFormat.OPAQUE
        ).apply { gravity = Gravity.CENTER }
        val added = runCatching { wmSafe.addView(box, params) }
        added.onFailure { Log.e(TAG, "addView(chart viewer) failed", it) }
        if (added.isFailure) return
        // 焦点请求要在 attach 之后（onKey 生效的前提是本窗口持有按键焦点）
        box.post { box.requestFocus() }
        // addView 返回时首帧 traversal 还没跑（attach 是异步的），紧跟的 renderCurrent
        // 会被 isAttachedToWindow 守卫静默跳过 → 全屏纯黑（2026-10-06 真机实测）。
        // attach 完成时补一次渲染，消除竞态。
        box.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { renderCurrent() }
            override fun onViewDetachedFromWindow(v: View) {}
        })
        imageView = iv
        captionView = tvCaption
        indexView = tvIndex
        container = box
        Log.i(TAG, "chart viewer opened")
    }

    private fun renderCurrent() {
        val slide = slides.getOrNull(currentIndex) ?: return
        val box = container ?: return
        if (!box.isAttachedToWindow) return
        imageView?.setImageBitmap(slide.bmp)
        captionView?.apply {
            text = slide.caption
            visibility = if (slide.caption.isBlank()) View.GONE else View.VISIBLE
        }
        indexView?.apply {
            visibility = if (slides.size > 1) View.VISIBLE else View.GONE
            text = service.getString(R.string.chart_viewer_index, currentIndex + 1, slides.size)
        }
        Log.i(TAG, "chart viewer render ${currentIndex + 1}/${slides.size}: id=${slide.chartId} " +
            "${slide.bmp.width}x${slide.bmp.height}")
    }

    /** 悬浮层授权自检（与 GlassesOverlayController 同一授权、同一告警口径） */
    private fun canShowOverlay(): Boolean {
        val ok = runCatching { android.provider.Settings.canDrawOverlays(service) }.getOrDefault(false)
        if (!ok) {
            Log.e(TAG, "chart viewer NOT available: SYSTEM_ALERT_WINDOW not granted to ${service.packageName} " +
                "(手机端应经 adb appops set ${service.packageName} android:system_alert_window allow)")
        }
        return ok
    }
}
