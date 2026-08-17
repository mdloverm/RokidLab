package com.rokidlab.phone.mirror

import com.rokidlab.phone.R
import com.rokidlab.phone.util.AppConfig
import com.rokidlab.phone.util.ManufacturerUtils
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
import android.os.Looper
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

        fun startService(context: Context, glassesIp: String, port: Int, resultCode: Int, data: Intent, isBluetooth: Boolean = false) {
            val intent = Intent(context, PhoneMirrorService::class.java).apply {
                putExtra("glassesIp", glassesIp)
                putExtra("port", port)
                putExtra("resultCode", resultCode)
                putExtra("data", data)
                putExtra("isBluetooth", isBluetooth)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    /**
     * 尝试应用 HWC 禁用属性以解决国产 ROM 投屏黑屏问题。
     * 仅在 debuggable 或 root 设备上生效，非侵入式。
     */
    private fun tryApplyHwcFix() {
        val props = ManufacturerUtils.getHwcDisableProps()
        if (props.isEmpty()) return
        for ((key, value) in props) {
            try {
                val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", "setprop $key $value"))
                process.waitFor()
                Log.i(TAG, "Applied HWC fix: $key=$value (exit=${process.exitValue()})")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to apply HWC fix $key=$value: ${e.message}")
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
    private var port: Int = AppConfig.DEFAULT_MIRROR_PORT
    private var resultCode: Int = -1
    private var projectionData: Intent? = null
    /** 是否蓝牙通道（影响投屏分辨率/帧率参数） */
    private var isBluetoothRoute: Boolean = false
    @Volatile
    private var isMirrorRunning = false
    private var orientationListener: OrientationEventListener? = null
    private var imageHandler: Handler? = null
    private var imageHandlerThread: HandlerThread? = null

    /** 当前虚拟显示器宽高（初始方向，不因方向变化重建） */
    private var mirrorWidth = 480
    private var mirrorHeight = 640
    /** 当前屏幕物理尺寸和 DPI（用于计算缩放比例） */
    private var screenWidth = 0
    private var screenHeight = 0
    private var screenDensity = DisplayMetrics.DENSITY_DEFAULT
    /** 复用灰度数据缓冲区，避免每帧创建新数组 */
    private var reusableGrayData: ByteArray? = null
    /** 复用发送缓冲区，避免每帧创建新数组 */
    private var reusableSendBuffer: ByteArray? = null
    /** 帧率控制：上次发送时间 */
    private var lastFrameTime = 0L
    /** 目标帧率（fps，根据连接类型在运行时选择） */
    private var TARGET_FPS_RUNTIME = 30
    /** 跳帧机制：标记上一帧是否正在发送，避免数据积压 */
    @Volatile
    private var isSendingFrame = false
    /** 帧健康检查：记录上次收到帧的时间，超时未收到则重启 VirtualDisplay */
    private var lastImageTime = 0L
    /** 帧健康检查定时器 */
    private var frameWatchdog: Thread? = null
    /** 独立的重连线程池，避免阻塞图像处理线程 */
    private val reconnectExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "mirror-reconnect")
    }
    /** 同步锁，保护 imageReader/虚拟显示器切换 */
    private val mirrorLock = Any()
    /** 当前取向（缓存，避免每帧查询） */
    private var currentOrientation = 0
    /** 防止重连循环：标记正在重连中 */
    @Volatile
    private var isReconnecting = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand called")

        if (intent == null) {
            Log.e(TAG, "Intent is null")
            stopSelf()
            return START_NOT_STICKY
        }

        glassesIp = intent.getStringExtra("glassesIp") ?: ""
        port = intent.getIntExtra("port", AppConfig.DEFAULT_MIRROR_PORT)
        resultCode = intent.getIntExtra("resultCode", -1)
        projectionData = intent.getParcelableExtra("data")
        isBluetoothRoute = intent.getBooleanExtra("isBluetooth", false)

        Log.i(TAG, "Received params: glassesIp=$glassesIp, port=$port, resultCode=$resultCode, BT=$isBluetoothRoute, data=${projectionData != null}")

        if (resultCode != android.app.Activity.RESULT_OK || projectionData == null || glassesIp.isNotEmpty() == false) {
            Log.e(TAG, "Incomplete params, cannot start mirror")
            stopSelf()
            return START_NOT_STICKY
        }

        // Android 14（API 34）时序要求：必须先启动 mediaProjection 类型的
        // 前台服务，再调用 getMediaProjection()，否则 MediaProjectionManagerService
        // 会抛 SecurityException（"Media projections require a foreground service
        // of type FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION"）。
        // MediaProjection token 由 MainActivity 的 createScreenCaptureIntent 授权获得。
        try {
            startForeground(NOTIFICATION_ID, createNotification())
            Log.i(TAG, "startForeground called (before projection token)")
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed: ${e.message}", e)
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = projectionManager.getMediaProjection(resultCode, projectionData!!)
            if (mediaProjection == null) {
                Log.e(TAG, "getMediaProjection returned null")
                stopSelf()
                return START_NOT_STICKY
            }
        } catch (e: Exception) {
            Log.e(TAG, "getMediaProjection failed: ${e.message}", e)
            stopSelf()
            return START_NOT_STICKY
        }

        Log.i(TAG, "Params complete, starting mirror")
        startMirror()
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
                    // 1. 获取屏幕真实尺寸，按 MIRROR_BASE_SIZE 等比缩放
                    val metrics = DisplayMetrics()
                    val displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
                    val display = displayManager.displays[0]
                    display?.getRealMetrics(metrics) ?: metrics.setToDefaults()
                    screenWidth = metrics.widthPixels
                    screenHeight = metrics.heightPixels
                    screenDensity = metrics.densityDpi

                    // ── 根据连接类型选择投屏参数（WiFi vs 蓝牙） ──
                    if (isBluetoothRoute) {
                        mirrorWidth = AppConfig.MIRROR_BT_WIDTH
                        mirrorHeight = AppConfig.MIRROR_BT_HEIGHT
                        TARGET_FPS_RUNTIME = AppConfig.MIRROR_BT_FPS
                        Log.i(TAG, "BT route: mirror=${mirrorWidth}x${mirrorHeight} @ ${TARGET_FPS_RUNTIME}fps")
                    } else {
                        mirrorWidth = AppConfig.MIRROR_WIFI_WIDTH
                        mirrorHeight = AppConfig.MIRROR_WIFI_HEIGHT
                        TARGET_FPS_RUNTIME = AppConfig.MIRROR_WIFI_FPS
                        Log.i(TAG, "WiFi route: mirror=${mirrorWidth}x${mirrorHeight} @ ${TARGET_FPS_RUNTIME}fps")
                    }

                    // ── 兼容性适配：检测设备并应用修复 ──
                    if (ManufacturerUtils.isMediaProjectionBlacklisted()) {
                        Log.w(TAG, "Device is in MediaProjection blacklist, applying compatibility fixes")
                        // 尝试通过 ADB setprop 禁用 HWC（仅当有 root 或 debuggable 时生效）
                        tryApplyHwcFix()
                    }
                    if (ManufacturerUtils.needsReducedMirrorResolution()) {
                        // 低端/联发科设备降低投屏分辨率，减少黑屏概率
                        mirrorWidth = 320
                        mirrorHeight = 426
                        Log.w(TAG, "Reducing mirror resolution to ${mirrorWidth}x${mirrorHeight} for compatibility")
                    }
                    Log.i(TAG, "Screen size: ${screenWidth}x${screenHeight}, Mirror size: ${mirrorWidth}x${mirrorHeight}")

                    // 2. 连接眼镜（使用配置的超时时间）
                    // 首连失败不致命：CXR-L 启动眼镜端 Activity 是异步的，Server 可能尚未监听。
                    // 失败后置空 socket，继续创建 MediaProjection/VirtualDisplay，
                    // 由 sendFrame 触发 reconnectSocket 在眼镜端就绪后自动恢复。
                    Log.i(TAG, "Connecting to glasses: $glassesIp:$port")
                    try {
                        socket = Socket()
                        socket?.connect(InetSocketAddress(glassesIp, port), AppConfig.MIRROR_CONNECT_TIMEOUT_MS)
                        socket?.tcpNoDelay = true
                        socket?.keepAlive = true
                        // 根据连接类型调整 Socket 缓冲区
                        val bufSize = if (isBluetoothRoute) AppConfig.MIRROR_BT_BUFFER_SIZE else AppConfig.MIRROR_WIFI_BUFFER_SIZE
                        try {
                            socket?.setSendBufferSize(bufSize)
                            socket?.setReceiveBufferSize(bufSize)
                        } catch (_: Exception) {}
                        outputStream = socket?.getOutputStream()
                        Log.i(TAG, "Connection successful (buffer=$bufSize)")
                    } catch (e: Exception) {
                        Log.w(TAG, "Initial connect failed, will retry on frame send: ${e.message}")
                        socket = null
                        outputStream = null
                    }

                    // 3. 使用已在 onStartCommand 创建的 MediaProjection（Android 14 时序要求）
                    mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                        override fun onStop() {
                            super.onStop()
                            Log.i(TAG, "MediaProjection stopped")
                            stopMirror()
                        }
                    }, null)

                    // 4. 启动图像监听线程（必须在 createMirrorSession 之前初始化 Handler）
                    imageHandlerThread = HandlerThread("ImageHandlerThread")
                    imageHandlerThread?.start()
                    imageHandler = Handler(imageHandlerThread!!.looper)

                    // 5. 创建 VirtualDisplay（固定 480x640，不因方向变化重建）
                    createMirrorSession()

                    // 6. 注册方向监听（仅用于更新发送帧的方向标志）
                    orientationListener = object : OrientationEventListener(this) {
                        private var lastOrientation = -1
                        private var lastChangeTime = 0L
                        private val DEBOUNCE_MS = 500L
                        override fun onOrientationChanged(orientation: Int) {
                            val normalized = when {
                                orientation >= 315 || orientation < 45 -> 0
                                orientation in 45..135 -> 90
                                orientation in 135..225 -> 180
                                else -> 270
                            }
                            if (lastOrientation == -1) {
                                lastOrientation = normalized
                                lastChangeTime = System.currentTimeMillis()
                                return
                            }
                            val now = System.currentTimeMillis()
                            if (lastOrientation != normalized && now - lastChangeTime > DEBOUNCE_MS) {
                                lastOrientation = normalized
                                lastChangeTime = now
                                currentOrientation = normalized
                                Log.i(TAG, "Orientation changed to $normalized")
                            }
                        }
                    }
                    orientationListener?.enable()

                    Log.i(TAG, "Mirror service started")
                    startFrameWatchdog()
                } catch (e: Exception) {
                    Log.e(TAG, "Mirror start failed: ${e.message}", e)
                    throw e
                }
            } catch (e: Exception) {
                stopMirror()
            }
        }.apply {
            setUncaughtExceptionHandler { _, e ->
                Log.e(TAG, "Mirror thread crashed unexpectedly: ${e.message}")
                runCatching { stopMirror() } // 确保资源完全回收
            }
        }.start()
    }

    private fun isLandscapeNow(): Boolean = currentOrientation == 90 || currentOrientation == 270

    /**
     * 创建 ImageReader + VirtualDisplay（固定 480x640，仅创建一次）
     * 创建后自动注册图像监听器
     */
    private fun createMirrorSession() {
        synchronized(mirrorLock) {
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
            Log.i(TAG, "VirtualDisplay created: ${mirrorWidth}x${mirrorHeight} @ ${screenDensity}dpi")
            registerImageListener()
        }
    }

    /**
     * 在当前 ImageReader 上注册帧监听器
     */
    private fun registerImageListener() {
        val reader = imageReader ?: return
        reader.setOnImageAvailableListener({ r ->
            if (!isMirrorRunning) return@setOnImageAvailableListener
            val image = try {
                r.acquireLatestImage()
            } catch (e: IllegalStateException) {
                Log.w(TAG, "acquireLatestImage failed (reader may be closed): ${e.message}")
                null
            } catch (e: Exception) {
                Log.e(TAG, "acquireLatestImage unexpected error: ${e.message}", e)
                null
            }
            if (image == null) return@setOnImageAvailableListener
            try {
                lastImageTime = System.currentTimeMillis()
                processImage(image)
            } catch (e: Exception) {
                Log.e(TAG, "Image processing failed: ${e.message}", e)
            } finally {
                image.close()
            }
        }, imageHandler)
    }

    /**
     * 从 RGBA_8888 图像提取灰度数据
     * 使用整数运算优化性能，复用缓冲区减少 GC
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
            Log.w(TAG, "Skipping frame: buffer(${buffer.remaining()}) < required($minRequired)")
            return
        }

        // 复用或创建灰度数据缓冲区
        val grayData = reusableGrayData?.takeIf { it.size == w * h } ?: ByteArray(w * h).also { reusableGrayData = it }

        // 提取灰度数据
        var dataIndex = 0
        for (row in 0 until h) {
            val rowStart = row * stride
            for (col in 0 until w) {
                val pixelOffset = rowStart + col * pixelStride
                val r = buffer.get(pixelOffset).toInt() and 0xFF
                val g = buffer.get(pixelOffset + 1).toInt() and 0xFF
                val b = buffer.get(pixelOffset + 2).toInt() and 0xFF
                val gray = ((299 * r + 587 * g + 114 * b + 500) / 1000).toByte()
                grayData[dataIndex++] = gray
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
            // 跳帧机制：如果上一帧还在发送（蓝牙带宽不足），跳过当前帧避免积压
            if (isSendingFrame) return
            // 帧率控制：限制发送频率，避免网络拥塞
            val now = System.currentTimeMillis()
            val minInterval = 1000L / TARGET_FPS_RUNTIME
            if (now - lastFrameTime < minInterval) {
                return
            }
            lastFrameTime = now
            isSendingFrame = true

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
            // 复用发送缓冲区，避免每帧创建新数组
            val totalSize = header.size + data.size
            val sendBuffer = reusableSendBuffer?.takeIf { it.size >= totalSize } ?: ByteArray(totalSize).also { reusableSendBuffer = it }
            System.arraycopy(header, 0, sendBuffer, 0, header.size)
            System.arraycopy(data, 0, sendBuffer, header.size, data.size)
            // 不调用 flush()，让 TCP 自动合并发送，减少网络开销
            outputStream?.write(sendBuffer, 0, totalSize)
            isSendingFrame = false
        } catch (e: Exception) {
            isSendingFrame = false
            Log.w(TAG, "Send frame failed: ${e.message}")
            try { outputStream?.close() } catch (_: Exception) {}
            try { socket?.close() } catch (_: Exception) {}
            socket = null
            outputStream = null
            if (isMirrorRunning) reconnectSocket()
        }
    }

    /**
     * 重连眼镜 Socket（异步执行，不阻塞图像处理线程）
     * 使用 isReconnecting 标记防止帧循环触发大量重连任务
     */
    private var reconnectAttempts = 0
    private fun reconnectSocket() {
        if (!isMirrorRunning || isReconnecting) return
        isReconnecting = true
        reconnectExecutor.submit {
            reconnectAttempts++
            try { Thread.sleep(200) } catch (_: InterruptedException) { isReconnecting = false; return@submit }
            if (!isMirrorRunning) { isReconnecting = false; return@submit }
            try {
                val newSocket = java.net.Socket()
                newSocket.connect(java.net.InetSocketAddress(glassesIp, port), AppConfig.MIRROR_CONNECT_TIMEOUT_MS)
                newSocket.tcpNoDelay = true
                newSocket.keepAlive = true
                synchronized(mirrorLock) {
                    socket = newSocket
                    outputStream = newSocket.getOutputStream()
                }
                reconnectAttempts = 0
                isReconnecting = false
                Log.i(TAG, "Socket reconnected successfully")
            } catch (e: Exception) {
                Log.w(TAG, "Socket reconnect failed ($reconnectAttempts): ${e.message}")
                isReconnecting = false
            }
        }
    }

    /**
     * 帧健康检查看门狗
     * 方向切换时帧可能暂停，等待自然恢复，不干预
     * 如果超过 30 秒仍无帧，说明真有问题，才尝试恢复或放弃
     */
    private fun startFrameWatchdog() {
        stopFrameWatchdog()
        lastImageTime = System.currentTimeMillis()

        frameWatchdog = Thread {
            while (isMirrorRunning && !Thread.currentThread().isInterrupted) {
                try {
                    Thread.sleep(5000)
                } catch (_: InterruptedException) {
                    break
                }
                if (!isMirrorRunning) break
                val sinceLastFrame = System.currentTimeMillis() - lastImageTime
                if (sinceLastFrame > 30000) {
                    Log.w(TAG, "No frames for ${sinceLastFrame}ms, attempting recovery")
                    lastImageTime = System.currentTimeMillis()
                    Handler(Looper.getMainLooper()).post {
                        swapImageReaderSurface()
                    }
                }
            }
        }.apply {
            name = "mirror-watchdog"
            isDaemon = true
            start()
        }
    }

    /**
     * 更换 ImageReader 并更新 VirtualDisplay 输出 Surface
     * 不重建 VirtualDisplay（MediaProjection 不允许重复 createVirtualDisplay）
     * 仅切换输出目标，触发帧流恢复
     */
    private fun swapImageReaderSurface() {
        if (!isMirrorRunning) return
        synchronized(mirrorLock) {
            if (!isMirrorRunning) return@synchronized
            try {
                val newReader = ImageReader.newInstance(mirrorWidth, mirrorHeight, PixelFormat.RGBA_8888, 2)
                val newSurface = newReader.surface
                // 仅切换 VirtualDisplay 的输出 Surface，不重建
                virtualDisplay?.surface = newSurface
                // 关闭旧的 ImageReader（此时图像线程已切换），再赋新值
                imageReader?.close()
                imageReader = newReader
                surface = newSurface
                registerImageListener()
                Log.i(TAG, "ImageReader surface swapped successfully")
            } catch (e: Exception) {
                Log.e(TAG, "ImageReader surface swap failed: ${e.message}", e)
            }
        }
    }

    private fun stopFrameWatchdog() {
        frameWatchdog?.interrupt()
        frameWatchdog = null
    }
 
    private fun stopMirror() {
        if (!isMirrorRunning) return
        synchronized(this) {
            if (!isMirrorRunning) return
            isMirrorRunning = false
        }
        stopFrameWatchdog()
        runCatching {
            // 先取消注册监听器，防止回调
            orientationListener?.disable()
            orientationListener = null
            // 停止并等待 HandlerThread 完全退出
            synchronized(mirrorLock) {
                imageHandler?.removeCallbacksAndMessages(null)
                imageHandler = null
                imageHandlerThread?.quit()
                imageHandlerThread = null
            }
            // 释放资源
            synchronized(mirrorLock) {
                imageReader?.close()
                imageReader = null
                surface?.release()
                surface = null
                virtualDisplay?.release()
                virtualDisplay = null
            }
            mediaProjection?.stop()
            mediaProjection = null
            outputStream?.close()
            outputStream = null
            socket?.close()
            socket = null
            reconnectExecutor.shutdownNow()
        }
        stopSelf()
    }
}
