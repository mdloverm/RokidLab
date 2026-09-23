package com.rokidlab.phone.mirror

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.rokidlab.phone.R
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 一次性截屏前台服务 —— 「看用户手机屏幕」用于**投屏没开**时的取图路径。
 *
 * ## 为什么必须另起一条 MediaProjection
 *
 * 投屏（[PhoneMirrorService]）已经在跑时，工具直接复用它的画面（见
 * [PhoneMirrorService.requestSnapshot]），不弹任何框 —— 但它的分辨率只能是投屏档位
 * （WiFi 480x640、蓝牙 160x213），蓝牙档位下小字根本读不出来。
 *
 * 而 targetSdk 34 起**一个 MediaProjection 只允许 createVirtualDisplay 一次**
 * （MediaProjection 文档里"already taken a recording"那条 SecurityException），
 * 所以想在投屏之外拿一块高清虚拟屏，只能再走一次系统授权 —— 这就是本服务存在的理由。
 *
 * ## 三个易踩的时序/平台约束
 *
 * 1. `mediaProjection` 类型的前台服务必须先 `startForeground()` 再 `getMediaProjection()`，
 *    否则 Android 14 上直接抛 SecurityException；
 * 2. targetSdk 34 起 `createVirtualDisplay()` 前**必须**注册 `MediaProjection.Callback`；
 * 3. 抓完立即 `projection.stop()` —— 一次截图不该在状态栏留下常驻的"正在截屏"标记。
 */
class ScreenCaptureService : Service() {
    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val NOTIFICATION_ID = 1002
        private const val CHANNEL_ID = "ScreenCapture"

        /** 抓一帧的等待上限（首帧通常 <500ms，低端机虚拟屏预热可能到几秒） */
        const val FRAME_TIMEOUT_MS = 6_000L

        /** 截图最长边上限：够看清小字，又不会让 base64 体量失控 */
        private const val MAX_EDGE = 1440
        private const val JPEG_QUALITY = 85

        private val lock = Any()
        private var pendingLatch: CountDownLatch? = null
        private var pendingResult: Shot? = null

        /** 抓到的一帧画面 */
        data class Shot(val jpeg: ByteArray, val width: Int, val height: Int)

        /**
         * 走完一次「授权 → 抓一帧」全流程（阻塞至多 timeoutMs）。
         *
         * @return null = 被拒绝 / 超时 / 系统不允许后台起前台服务 / 设备拿不到画面
         */
        fun capture(context: Context, resultCode: Int, data: Intent, timeoutMs: Long): Shot? {
            val latch = CountDownLatch(1)
            synchronized(lock) {
                // 已有一次在跑：不叠加（工具并发执行，同一时刻只允许一次系统截屏会话）
                if (pendingLatch != null) return null
                pendingLatch = latch
                pendingResult = null
            }
            try {
                val started = runCatching { start(context, resultCode, data) }.isSuccess
                if (!started) {
                    // Android 12+ 后台起前台服务受限制（手机息屏只走眼镜时可能命中）
                    Log.w(TAG, "cannot start capture service (background FGS start blocked?)")
                    return null
                }
                val ok = runCatching { latch.await(timeoutMs, TimeUnit.MILLISECONDS) }.getOrDefault(false)
                if (!ok) Log.w(TAG, "capture timed out after ${timeoutMs}ms")
                synchronized(lock) { return if (ok) pendingResult else null }
            } finally {
                synchronized(lock) {
                    pendingLatch = null
                    pendingResult = null
                }
            }
        }

        private fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, ScreenCaptureService::class.java).apply {
                putExtra("resultCode", resultCode)
                putExtra("data", data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        private fun deliver(shot: Shot?) {
            synchronized(lock) {
                pendingResult = shot
                pendingLatch?.countDown()
            }
        }
    }

    /** 虚拟屏尺寸（按最长边上限等比缩放；density 仍用真实值，与投屏同样处理） */
    private data class Target(val width: Int, val height: Int, val densityDpi: Int)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val resultCode = intent?.getIntExtra("resultCode", Activity.RESULT_CANCELED)
            ?: Activity.RESULT_CANCELED
        val data = intent?.let { readProjectionData(it) }

        // 时序要求：先成为 mediaProjection 类型的前台服务，才能 getMediaProjection()
        runCatching { startForeground(NOTIFICATION_ID, createNotification()) }
            .onFailure { Log.e(TAG, "startForeground failed: ${it.message}", it) }

        // 抓帧必须离开主线程：等首帧最长 6s，放在主线程就是 ANR
        Thread {
            val shot = runCatching { grab(resultCode, data) }
                .onFailure { Log.e(TAG, "grab failed: ${it.message}", it) }
                .getOrNull()
            deliver(shot)
            stopSelf()
        }.apply {
            name = "screen-capture"
            isDaemon = true
            start()
        }
        return START_NOT_STICKY
    }

    private fun grab(resultCode: Int, data: Intent?): Shot? {
        if (resultCode != Activity.RESULT_OK || data == null) {
            Log.i(TAG, "consent not granted (resultCode=$resultCode)")
            return null
        }
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = runCatching { manager.getMediaProjection(resultCode, data) }.getOrNull()
            ?: return null

        var reader: ImageReader? = null
        var display: VirtualDisplay? = null
        var bmp: Bitmap? = null
        try {
            // targetSdk 34 起：不注册 Callback 就 createVirtualDisplay 会抛 IllegalStateException
            runCatching {
                projection.registerCallback(object : MediaProjection.Callback() {}, Handler(Looper.getMainLooper()))
            }
            val target = fitScreen()
            reader = ImageReader.newInstance(target.width, target.height, PixelFormat.RGBA_8888, 2)
            // 与投屏一致带 AUTO_MIRROR：部分 ROM 不带这个 flag 会出全黑帧
            display = projection.createVirtualDisplay(
                "ScreenCapture", target.width, target.height, target.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, null,
            )
            val image = awaitImage(reader) ?: run {
                Log.w(TAG, "no frame within ${FRAME_TIMEOUT_MS}ms")
                return null
            }
            bmp = try {
                rgbaImageToBitmap(image)
            } finally {
                image.close()
            } ?: return null
            val jpeg = ByteArrayOutputStream().use { out ->
                bmp.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                out.toByteArray()
            }
            Log.i(TAG, "captured ${bmp.width}x${bmp.height}, ${jpeg.size}B")
            return Shot(jpeg, bmp.width, bmp.height)
        } finally {
            bmp?.recycle()
            runCatching { reader?.close() }
            runCatching { display?.release() }
            // 一次截图会话到此为止：不 stop() 会在状态栏留下常驻的截屏标记
            runCatching { projection.stop() }
        }
    }

    /** 轮询取帧：ImageReader 的监听器回调在别的线程，这里用一个短轮询让调用方同步拿到结果 */
    private fun awaitImage(reader: ImageReader): Image? {
        val deadline = System.currentTimeMillis() + FRAME_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val image = runCatching { reader.acquireLatestImage() }.getOrNull()
            if (image != null) return image
            runCatching { Thread.sleep(40) }
        }
        return null
    }

    private fun fitScreen(): Target {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            metrics.widthPixels = bounds.width()
            metrics.heightPixels = bounds.height()
            metrics.densityDpi = resources.configuration.densityDpi
        } else {
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
        }
        val screenW = metrics.widthPixels.coerceAtLeast(1)
        val screenH = metrics.heightPixels.coerceAtLeast(1)
        val scale = minOf(1f, MAX_EDGE.toFloat() / maxOf(screenW, screenH).toFloat())
        return Target(
            width = (screenW * scale).toInt().coerceAtLeast(2),
            height = (screenH * scale).toInt().coerceAtLeast(2),
            densityDpi = if (metrics.densityDpi > 0) metrics.densityDpi else DisplayMetrics.DENSITY_DEFAULT,
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.screen_capture_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.screen_capture_channel_desc)
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.screen_capture_channel))
            .setContentText(getString(R.string.screen_capture_capturing))
            .setSmallIcon(R.mipmap.ic_launcher)
            .build()

    /** API 33 以下取 Intent 类型 extra 的老写法（行内 @Suppress 对表达式不生效，只能单独抽方法） */
    @Suppress("DEPRECATION")
    private fun readProjectionData(intent: Intent): Intent? =
        if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra("data", Intent::class.java)
        } else {
            intent.getParcelableExtra("data") as? Intent
        }
}

/**
 * RGBA_8888 的 [Image] → [Bitmap]（截屏服务与投屏截屏旁路共用这一份实现）。
 *
 * 关键在 `rowStride` 往往带行尾填充：Buffer 按 `rowStride/pixelStride` 列建图后再裁掉右侧，
 * 直接按 image.width 建图会把填充字节当成像素，画面整体斜切。
 *
 * @return null = 像素格式不是 4 字节（非 RGBA_8888），调用方据此如实回报失败
 */
internal fun rgbaImageToBitmap(image: Image): Bitmap? {
    val plane = image.planes.firstOrNull() ?: return null
    val pixelStride = plane.pixelStride
    if (pixelStride != 4) return null
    val rowStride = plane.rowStride
    val rawWidth = rowStride / pixelStride
    val width = image.width
    val height = image.height
    if (rawWidth < width || width <= 0 || height <= 0) return null
    val buffer = plane.buffer
    buffer.rewind()
    val raw = Bitmap.createBitmap(rawWidth, height, Bitmap.Config.ARGB_8888)
    return try {
        raw.copyPixelsFromBuffer(buffer)
        if (rawWidth == width) raw else Bitmap.createBitmap(raw, 0, 0, width, height).also { raw.recycle() }
    } catch (e: Exception) {
        raw.recycle()
        throw e
    }
}
