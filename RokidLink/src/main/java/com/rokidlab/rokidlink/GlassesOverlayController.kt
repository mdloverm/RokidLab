package com.rokidlab.rokidlink

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.util.Base64
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.rokid.cxr.Caps

/**
 * KeyButtonService 的悬浮层控制器（v3.9 拆分自 KeyButtonService）。
 *
 * 统一管理眼镜端两类悬浮层（复用同一套 SYSTEM_ALERT_WINDOW 授权，
 * 由手机端经 ADB appops 下发）：
 *  1. 歌词/工具确认文本悬浮层（底部半透明黑条，重复调用仅更新文本）
 *  2. 图片悬浮层（居中图片 + 说明文字，12s 后自动隐藏）
 */
internal class GlassesOverlayController(
    private val service: KeyButtonService,
    private val core: KeyServiceCore,
) {
    companion object {
        private const val TAG = KeyButtonService.TAG
        /** 图片悬浮层自动隐藏时间 */
        private const val IMAGE_OVERLAY_MS = 12_000L
    }

    // ──────────────────────────────────────────────
    //  歌词/确认文本悬浮层
    // ──────────────────────────────────────────────

    private var lyricView: TextView? = null
    private var lyricWm: WindowManager? = null

    /**
     * 悬浮层可用性自检：`TYPE_APPLICATION_OVERLAY` 必须已授予 `SYSTEM_ALERT_WINDOW`，
     * 否则 `addView` 抛异常。
     *
     * ⚠️ 历史教训：原实现把 `addView` 包在 `runCatching {}` 里且**不打日志**，
     * 未授权时悬浮层静默加不上，表现为「歌词/对话文字不显示」但日志里查不到任何错误。
     * 现在改为显式检查 + 失败高声告警（授权由手机端经 ADB appops 下发）。
     */
    private fun canShowOverlay(): Boolean {
        val ok = runCatching { android.provider.Settings.canDrawOverlays(service) }.getOrDefault(false)
        if (!ok) {
            Log.e(TAG, "overlay NOT available: SYSTEM_ALERT_WINDOW not granted to ${service.packageName} " +
                "(手机端应经 adb appops set ${service.packageName} android:system_alert_window allow)")
        }
        return ok
    }

    /** 在眼镜端显示一个半透明悬浮歌词层，文本居中偏下。重复调用仅更新文本。 */
    fun showLyricOverlay(text: String) {
        val view = lyricView ?: run {
            if (!canShowOverlay()) return
            val wmSafe = lyricWm ?: (service.getSystemService(android.content.Context.WINDOW_SERVICE) as? WindowManager)
                .also { lyricWm = it } ?: return
            val tv = TextView(service).apply {
                textSize = 22f
                setTextColor(0xFFFFFFFF.toInt())
                setShadowLayer(4f, 2f, 2f, 0xFF000000.toInt())
                setPadding(28, 18, 28, 18)
                setBackgroundColor(0xCC000000.toInt())
                gravity = Gravity.CENTER
            }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                y = 90
            }
            val added = runCatching { wmSafe.addView(tv, params) }
            added.onFailure { Log.e(TAG, "addView(lyric overlay) failed", it) }
            if (added.isFailure) return
            tv.also { lyricView = it }
        }
        view.text = text
        Log.i(TAG, "lyric overlay: ${text.take(40)}")
    }

    /** 移除悬浮歌词层（文本为空/停止播放时调用）。 */
    fun hideLyricOverlay() {
        lyricView?.let { tv ->
            runCatching { lyricWm?.removeView(tv) }
            lyricView = null
            lyricWm = null
        }
        Log.i(TAG, "lyric overlay hidden")
    }

    // ──────────────────────────────────────────────
    //  图片悬浮层（手机端对话里的图片 → 眼镜端）
    // ──────────────────────────────────────────────

    private var imageContainer: LinearLayout? = null
    private var imageView: ImageView? = null
    private var imageCaption: TextView? = null
    private var imageWm: WindowManager? = null
    private val hideImageRunnable = Runnable { hideImageOverlay() }

    /**
     * 手机端下发图片（[AiChannel.TOPIC_SHOW_IMAGE]）：Base64 JPEG → Bitmap → 居中悬浮图片层，
     * 12s 后自动隐藏；重复下发只换图不重建视图。
     *
     * 复用歌词/工具确认同一套悬浮层授权（`SYSTEM_ALERT_WINDOW`，由手机端经 ADB appops 下发）。
     */
    fun handleShowImage(args: Caps?) {
        try {
            val decoded = AiChannel.decodeShowImage(capsToStrings(args))
            if (decoded == null) {
                Log.w(TAG, "handleShowImage: rejected invalid payload (size=${args?.size()})")
                return
            }
            val (b64, caption) = decoded
            Log.i(TAG, "Received show_image: base64Len=${b64.length} caption='${caption.take(30)}'")
            core.mainHandler.post {
                val bytes = runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrNull()
                if (bytes == null) {
                    Log.e(TAG, "handleShowImage: base64 decode failed (len=${b64.length})")
                    return@post
                }
                val bmp = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
                if (bmp == null) {
                    Log.e(TAG, "handleShowImage: bitmap decode failed (bytes=${bytes.size})")
                    return@post
                }
                showImageOverlay(bmp, caption)
            }
        } catch (e: Exception) {
            Log.e(TAG, "handleShowImage error", e)
        }
    }

    /** 显示/更新悬浮图片层（同一实例复用）。 */
    private fun showImageOverlay(bmp: Bitmap, caption: String) {
        val container = imageContainer ?: run {
            if (!canShowOverlay()) return
            val wmSafe = imageWm ?: (service.getSystemService(android.content.Context.WINDOW_SERVICE) as? WindowManager)
                .also { imageWm = it } ?: return
            val iv = ImageView(service).apply {
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            val tv = TextView(service).apply {
                textSize = 18f
                setTextColor(0xFFFFFFFF.toInt())
                setShadowLayer(4f, 2f, 2f, 0xFF000000.toInt())
                gravity = Gravity.CENTER
                setPadding(0, 14, 0, 0)
            }
            val box = LinearLayout(service).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(24, 20, 24, 20)
                setBackgroundColor(0xE6000000.toInt())
                addView(iv)
                addView(tv)
            }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.CENTER }
            val added = runCatching { wmSafe.addView(box, params) }
            added.onFailure { Log.e(TAG, "addView(image overlay) failed", it) }
            if (added.isFailure) return
            imageView = iv
            imageCaption = tv
            box.also { imageContainer = it }
        }
        // 限宽 520px：眼镜屏宽有限，超宽图片会被裁切
        val maxW = 520
        val scaled = if (bmp.width > maxW) {
            val h = (bmp.height * (maxW.toFloat() / bmp.width)).toInt().coerceAtLeast(1)
            runCatching { Bitmap.createScaledBitmap(bmp, maxW, h, true) }.getOrDefault(bmp)
        } else {
            bmp
        }
        imageView?.setImageBitmap(scaled)
        imageCaption?.apply {
            text = caption
            visibility = if (caption.isBlank()) View.GONE else View.VISIBLE
        }
        container.requestLayout()
        core.mainHandler.removeCallbacks(hideImageRunnable)
        core.mainHandler.postDelayed(hideImageRunnable, IMAGE_OVERLAY_MS)
        Log.i(TAG, "image overlay: ${scaled.width}x${scaled.height} caption='${caption.take(30)}'")
    }

    /** 移除悬浮图片层。 */
    private fun hideImageOverlay() {
        core.mainHandler.removeCallbacks(hideImageRunnable)
        imageContainer?.let { box ->
            runCatching { imageWm?.removeView(box) }
            imageContainer = null
            imageView = null
            imageCaption = null
            imageWm = null
            Log.i(TAG, "image overlay hidden")
        }
    }

    /** 服务销毁时移除全部悬浮层 */
    fun release() {
        hideLyricOverlay()
        hideImageOverlay()
    }
}
