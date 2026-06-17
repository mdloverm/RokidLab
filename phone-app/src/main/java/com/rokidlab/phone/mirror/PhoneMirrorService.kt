package com.rokidlab.phone.mirror

import com.rokidlab.phone.R
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import androidx.core.app.NotificationCompat
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.OrientationEventListener
import android.view.Surface
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 手机投屏前台服务
 * 动态适配屏幕方向，参照 Scrcpy 方案：
 * - 方向变化时重建 VirtualDisplay（交换宽高）
 * - 每帧头携带宽高信息，眼镜端动态适配
 */
class PhoneMirrorService : Service() {
    companion object {
        private const val TAG = "PhoneMirrorService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "PhoneMirror"
        private const val DEFAULT_PORT = 7654
        /** 基准分辨率（短边） */
        private const val BASE_SIZE = 480

        fun startService(context: Context, glassesIp: String, port: Int, resultCode: Int, data: Intent) {
            val intent = Intent(context, PhoneMirrorService::class.java).apply {
                putExtra("glassesIp", glassesIp)
                putExtra("port", port)
                putExtra("resultCode", resultCode)
                putExtra("data", data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private var socket: Socket? = null
    private var outputStream: OutputStream? = null
    private var imageReader: ImageReader? = null
    private var surface: Surface? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaProjection: MediaProjection? = null
    private var glassesIp: String = ""
    private var port: Int = DEFAULT_PORT
    private var resultCode: Int = -1
    private var projectionData: Intent? = null
    @Volatile
    private var isMirrorRunning = false
    private var orientationListener: OrientationEventListener? = null
    private var imageHandler: Handler? = null
    private var imageHandlerThread: HandlerThread? = null

    /** 当前虚拟显示器宽高（屏幕短边缩放到 BASE_SIZE） */
    private var mirrorWidth = BASE_SIZE
    private var mirrorHeight = BASE_SIZE
    /** 当前屏幕物理尺寸和 DPI（用于计算缩放比例） */
    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = DisplayMetrics.DENSITY_DEFAULT

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand 被调用")
        startForeground(NOTIFICATION_ID, createNotification())
        Log.i(TAG, "startForeground 已调用")

        if (intent != null) {
            glassesIp = intent.getStringExtra("glassesIp") ?: ""
            port = intent.getIntExtra("port", DEFAULT_PORT)
            resultCode = intent.getIntExtra("resultCode", -1)
            projectionData = intent.getParcelableExtra("data")

            Log.i(TAG, "接收到参数: glassesIp=$glassesIp, port=$port, resultCode=$resultCode, data=${projectionData != null}")

            if (resultCode == android.app.Activity.RESULT_OK && projectionData != null && glassesIp.isNotEmpty()) {
                Log.i(TAG, "参数完整，开始启动投屏")
                startMirror()
            } else {
                Log.e(TAG, "参数不完整，无法启动投屏")
                stopSelf()
            }
        } else {
            Log.e(TAG, "Intent 为空")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        stopMirror()
        super.onDestroy()
    }

    // ── Notification ──

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.phone_mirror_channel),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.phone_mirror_channel_desc)
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.phone_mirror_channel))
            .setContentText(getString(R.string.phone_mirror_projecting))
            .setSmallIcon(R.mipmap.ic_launcher)
            .build()

    // ── 核心投屏逻辑（Scrcpy 动态方向方案）──

    private fun startMirror() {
        if (isMirrorRunning) return
        isMirrorRunning = true
        reconnectAttempts = 0

        Thread {
            try {
                try {
                    // 1. 获取屏幕真实尺寸，按 BASE_SIZE 等比缩放
                    val metrics = DisplayMetrics()
                    val displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
                    val display = displayManager.displays[0]
                    display?.getRealMetrics(metrics) ?: metrics.setToDefaults()
                    screenWidth = metrics.widthPixels
                    screenHeight = metrics.heightPixels
                    screenDensity = metrics.densityDpi
                    updateMirrorSize(isLandscapeNow())
                    Log.i(TAG, "屏幕物理尺寸: ${screenWidth}x${screenHeight}, 镜像尺寸: ${mirrorWidth}x${mirrorHeight}")

                    // 2. 连接眼镜（3秒超时）
                    Log.i(TAG, "连接眼镜: $glassesIp:$port")
                    socket = Socket()
                    socket?.connect(InetSocketAddress(glassesIp, port), 3000)
                    socket?.tcpNoDelay = true
                    socket?.keepAlive = true
                    outputStream = socket?.getOutputStream()
                    Log.i(TAG, "连接成功")

                    // 3. 获取 MediaProjection
                    val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                    mediaProjection = projectionManager.getMediaProjection(resultCode, projectionData!!)

                    mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                        override fun onStop() {
                            super.onStop()
                            Log.i(TAG, "MediaProjection 已停止")
                            stopMirror()
                        }
                    }, null)

                    // 4. 启动图像监听线程（必须在 createMirrorSession 之前初始化 Handler）
                    imageHandlerThread = HandlerThread("ImageHandlerThread")
                    imageHandlerThread?.start()
                    imageHandler = Handler(imageHandlerThread!!.looper)

                    // 5. 创建初始 VirtualDisplay（内部自动注册 ImageReader 监听器）
                    createMirrorSession()

                    // 6. 注册方向监听（Scrcpy 方案：方向变化时重建 VirtualDisplay，带 1 秒防抖）
                    var lastOrientationChangeMs = 0L
                    orientationListener = object : OrientationEventListener(this) {
                        override fun onOrientationChanged(orientation: Int) {
                            if (!isMirrorRunning) return
                            val now = System.currentTimeMillis()
                            if (now - lastOrientationChangeMs < 1000) return
                            val wasLandscape = mirrorWidth > mirrorHeight
                            val isLandscape = orientation in 60..300
                            if (wasLandscape != isLandscape) {
                                lastOrientationChangeMs = now
                                Log.i(TAG, "方向变化: ${if (isLandscape) "横屏" else "竖屏"} → 重建 VirtualDisplay")
                                recreateMirrorSession()
                            }
                        }
                    }
                    orientationListener?.enable()

                    Log.i(TAG, "投屏服务已启动")
                } catch (e: Exception) {
                    Log.e(TAG, "启动投屏失败: ${e.message}", e)
                    throw e
                }
            } catch (e: Exception) {
                stopMirror()
            }
        }.apply {
            setUncaughtExceptionHandler { _, e ->
                Log.e(TAG, "投屏线程意外崩溃: ${e.message}")
                runCatching { stopMirror() } // 确保资源完全回收
            }
        }.start()
    }

    private fun isLandscapeNow(): Boolean {
        val displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val displays = displayManager.displays
        if (displays.isNotEmpty()) {
            val rotation = displays[0].rotation
            return rotation == 1 || rotation == 3
        }
        return false
    }

    /**
     * 根据方向计算镜像尺寸（短边缩放到 BASE_SIZE，长边等比）
     */
    private fun updateMirrorSize(isLandscape: Boolean) {
        if (isLandscape) {
            mirrorWidth = BASE_SIZE
            mirrorHeight = (BASE_SIZE * screenHeight.toFloat() / screenWidth).toInt()
        } else {
            mirrorHeight = BASE_SIZE
            mirrorWidth = (BASE_SIZE * screenWidth.toFloat() / screenHeight).toInt()
        }
    }

    /**
     * 创建 ImageReader + VirtualDisplay（使用当前 mirrorWidth/mirrorHeight 和真实 DPI）
     * 创建后自动注册图像监听器
     */
    private fun createMirrorSession() {
        try {
            imageReader?.close()
            surface?.release()
            virtualDisplay?.release()
        } catch (_: Exception) {}

        imageReader = ImageReader.newInstance(mirrorWidth, mirrorHeight, PixelFormat.RGBA_8888, 2)
        surface = imageReader?.surface
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "PhoneMirror", mirrorWidth, mirrorHeight, screenDensity,
            0, surface, null, null
        )
        Log.i(TAG, "VirtualDisplay 创建: ${mirrorWidth}x${mirrorHeight} @ ${screenDensity}dpi")
        registerImageListener()
    }

    /**
     * 在当前 ImageReader 上注册帧监听器
     */
    private fun registerImageListener() {
        imageReader?.setOnImageAvailableListener({ reader ->
            if (!isMirrorRunning) return@setOnImageAvailableListener
            val image = reader.acquireLatestImage()
            if (image == null) return@setOnImageAvailableListener
            try {
                processImage(image)
            } catch (e: IllegalStateException) {
                Log.w(TAG, "跳过失效帧: ${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "处理图像失败: ${e.message}", e)
            } finally {
                image.close()
            }
        }, imageHandler)
    }

    /**
     * 方向变化时重建（Scrcpy/DeskDock 方案）
     */
    private fun recreateMirrorSession() {
        updateMirrorSize(isLandscapeNow())
        createMirrorSession()
    }

    /**
     * 从 RGBA_8888 图像提取灰度数据（动态尺寸）
     */
    private fun processImage(image: Image) {
        val w = image.width
        val h = image.height
        val plane = image.planes[0]
        val buffer = plane.buffer.slice()
        val stride = plane.rowStride
        val pixelStride = plane.pixelStride

        // 校验 buffer 大小防止越界
        val minRequired = stride * (h - 1) + w * pixelStride
        if (buffer.remaining() < minRequired) {
            Log.w(TAG, "跳过帧: buffer(${buffer.remaining()}) < 所需($minRequired)")
            return
        }

        val grayData = ByteArray(w * h)
        var bufferIndex = 0
        var dataIndex = 0

        for (row in 0 until h) {
            for (col in 0 until w) {
                val r = buffer.get(bufferIndex).toInt() and 0xFF
                val g = buffer.get(bufferIndex + 1).toInt() and 0xFF
                val b = buffer.get(bufferIndex + 2).toInt() and 0xFF
                val gray = (0.299 * r + 0.587 * g + 0.114 * b).toInt().toByte()
                grayData[dataIndex++] = gray
                bufferIndex += pixelStride
            }
            if (stride > w * pixelStride) {
                bufferIndex += stride - w * pixelStride
            }
        }
        sendFrame(grayData, w, h)
    }

    /**
     * 发送方向 + 宽高 + 灰度数据
     * 协议: [1字节方向][2字节宽(little-endian)][2字节高(little-endian)][N字节灰度]
     * 注意：header 和 data 合并为一次 write，防止部分写入导致眼镜端协议偏移
     */
    private fun sendFrame(data: ByteArray, w: Int, h: Int) {
        try {
            if (socket == null || !socket!!.isConnected) {
                reconnectSocket()
            }
            val orientation = if (isLandscapeNow()) 1 else 0
            val header = byteArrayOf(
                orientation.toByte(),
                (w and 0xFF).toByte(),
                ((w shr 8) and 0xFF).toByte(),
                (h and 0xFF).toByte(),
                ((h shr 8) and 0xFF).toByte()
            )
            // 合并 header + data 为一次 write，确保原子写入
            val combined = ByteArray(header.size + data.size)
            System.arraycopy(header, 0, combined, 0, header.size)
            System.arraycopy(data, 0, combined, header.size, data.size)
            outputStream?.write(combined)
            outputStream?.flush()
        } catch (e: Exception) {
            Log.w(TAG, "发送帧失败: ${e.message}")
            try { outputStream?.close() } catch (_: Exception) {}
            try { socket?.close() } catch (_: Exception) {}
            socket = null
            outputStream = null
            if (isMirrorRunning) reconnectSocket()
        }
    }

    /**
     * 重连眼镜 Socket（最多重试 3 次后放弃）
     */
    private var reconnectAttempts = 0
    private fun reconnectSocket() {
        if (reconnectAttempts >= 3) {
            Log.w(TAG, "Socket 重连已达最大次数，停止投屏")
            stopMirror()
            return
        }
        reconnectAttempts++
        if (!isMirrorRunning) return
        try { Thread.sleep(200) } catch (_: InterruptedException) { return }
        if (!isMirrorRunning) return
        try {
            socket = Socket()
            socket?.connect(InetSocketAddress(glassesIp, port), 3000)
            socket?.tcpNoDelay = true
            socket?.keepAlive = true
            outputStream = socket?.getOutputStream()
            reconnectAttempts = 0
            Log.i(TAG, "Socket 重连成功")
        } catch (e: Exception) {
            Log.w(TAG, "Socket 重连失败 ($reconnectAttempts/3): ${e.message}")
        }
    }

    private fun stopMirror() {
        if (!isMirrorRunning) return
        isMirrorRunning = false
        runCatching {
            orientationListener?.disable()
            orientationListener = null
            imageHandler?.removeCallbacksAndMessages(null)
            imageHandler = null
            imageHandlerThread?.quitSafely()
            imageHandlerThread = null
            imageReader?.close()
            surface?.release()
            virtualDisplay?.release()
            mediaProjection?.stop()
            outputStream?.close()
            socket?.close()
        }
        socket = null
        outputStream = null
        stopSelf()
    }
}
