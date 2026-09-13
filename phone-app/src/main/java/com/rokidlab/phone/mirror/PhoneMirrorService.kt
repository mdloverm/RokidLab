package com.rokidlab.phone.mirror

import com.rokidlab.phone.R
import com.rokidlab.phone.app.LabApplication
import com.rokidlab.phone.util.AppConfig
import com.rokidlab.phone.util.LogCollector
import com.rokidlab.phone.util.RomFingerprint
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
import android.view.WindowManager
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

        /**
         * WiFi 线路连续重连失败多少次后降级蓝牙隧道。
         * 取 3：单次失败可能只是眼镜端投屏服务尚未监听（首连窗口），
         * 连续 3 次（约 1s）基本可判定 WiFi 线路真的不可用了。
         */
        private const val BT_DOWNGRADE_AFTER_FAILURES = 3

        /**
         * 蓝牙线路转入后、允许尝试回切 WiFi 的静默时长（迟滞防抖）。
         * 取 60s：刚降级就试探会在 WiFi 抖动时造成「降级→回切→再降级」的来回切，
         * 每次切换都要重建虚拟屏，画面反复卡顿，比不切更糟。
         */
        private const val BT_DWELL_BEFORE_WIFI_UPGRADE_MS = 60_000L

        /** 回切探测间隔（探测是 2s 超时的 TCP connect，不能太密） */
        private const val WIFI_UPGRADE_PROBE_INTERVAL_MS = 20_000L

        /** 连续多少次探测成功才真正回切（要求 2 次，滤掉单次抖动） */
        private const val WIFI_UPGRADE_PROBE_SUCCESSES = 2

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

    // ── 兼容性降级阶梯状态 ──────────────────────────────────────
    //
    // 旧实现在此处调用 ManufacturerUtils.getHwcDisableProps()，通过
    // `Runtime.exec("setprop debug.sf.enable_hwc_vds 0")` 禁用 HWC 来解决黑屏。
    // 该方案在第三方 App 上必定无效：这两个属性属于 SurfaceFlinger 系统属性，
    // 非 root/system 签名进程写入会被 SELinux 静默拒绝（退出码仍是 0，看起来像成功了）；
    // 即使侥幸写成功，也要等 SurfaceFlinger 重启才生效，对本次已建立的虚拟屏毫无影响。
    // 现改为 MirrorCompat 的运行时探测 + 逐档降级，纯应用层，无需任何系统属性。

    /** 当前尝试的兼容档位 */
    private var compatTier: MirrorCompat.Tier = MirrorCompat.Tier.BASELINE

    /** 黑帧连续性探测器 */
    private var blackProbe: MirrorCompat.BlackFrameProbe? = null

    /** 是否已确认出画面（锁定后不再降档，避免来回抖动） */
    @Volatile
    private var frameHealthy = false

    /**
     * 收到持续黑帧时升档重建虚拟屏。
     * 由 ImageHandlerThread 回调，重建涉及 IO 与同步锁，放到独立线程避免阻塞帧回调。
     */
    private fun escalateCompatTier(blackFrames: Int) {
        if (frameHealthy) return
        val next = compatTier.next()
        if (next == null) {
            Log.e(TAG, "All ${MirrorCompat.Tier.entries.size} compat tiers exhausted, staying at ${compatTier.name}")
            if (MirrorCompat.markExhausted(this)) {
                Log.w(TAG, "Mirror never produced content on this device. " +
                    "Ask user to export diagnostics: ${RomFingerprint.signature()}")
            }
            return
        }
        compatTier = next
        Log.i(TAG, "Escalating compat tier ${next.name} after $blackFrames black frames (${next.note})")
        Thread {
            try {
                rebuildMirrorSession()
            } catch (e: Exception) {
                Log.e(TAG, "Rebuild after tier escalation failed: ${e.message}", e)
            }
        }.start()
    }

    /**
     * 关闭现有 ImageReader/VirtualDisplay 并按当前档位参数重建。
     * warmupMs 用于兼容首帧晚到的机型（华为 EMUI 12+、部分 MTK）。
     */
    private fun rebuildMirrorSession() {
        synchronized(mirrorLock) {
            runCatching {
                imageReader?.setOnImageAvailableListener(null, null)
                imageHandler?.removeCallbacksAndMessages(null)
                imageReader?.close()
                surface?.release()
                virtualDisplay?.release()
            }
            imageReader = null
            surface = null
            virtualDisplay = null
        }
        val params = MirrorCompat.paramsFor(compatTier, mirrorBaseWidth, mirrorBaseHeight)
        mirrorWidth = params.width
        mirrorHeight = params.height
        // 解冻灰度/发送缓冲区：尺寸变了，旧缓冲区长度不再匹配
        reusableGrayData = null
        reusableSendBuffer = null
        Log.i(TAG, "Waiting ${params.warmupMs}ms warmup before recreating virtual display")
        Thread.sleep(params.warmupMs)
        blackProbe?.reset()
        createMirrorSession()
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

    /** 是否已从 WiFi 降级到蓝牙隧道（一次性，避免反复降级/重建虚拟屏） */
    @Volatile
    private var routeDowngraded: Boolean = false

    /** WiFi 线路目标（眼镜 WiFi IP + 投屏端口）—— 降级/蓝牙启动后回切用；降级时会把 [glassesIp]/[port] 改写为本地隧道地址 */
    private var wifiTargetIp: String = ""
    private var wifiTargetPort: Int = AppConfig.DEFAULT_MIRROR_PORT

    /** 当前蓝牙线路的起始时刻（回切迟滞基准；0 = 非蓝牙线路） */
    @Volatile
    private var btRouteSinceAt: Long = 0L

    /** WiFi 回切探测的连续成功次数 */
    @Volatile
    private var wifiUpgradeProbeStreak: Int = 0

    /** 上次 WiFi 回切探测时间戳 */
    @Volatile
    private var lastWifiUpgradeProbeAt: Long = 0L

    /** 回切流程进行中标志（防探测/重建叠加） */
    @Volatile
    private var upgradingToWifi: Boolean = false

    /** L3 通道协调器（Phase 3：蓝牙租约上收到 domain/MirrorCoordinator） */
    private val mirrorCoordinator by lazy { com.rokidlab.phone.domain.MirrorCoordinator(application as LabApplication) }
    @Volatile
    private var isMirrorRunning = false
    private var orientationListener: OrientationEventListener? = null
    private var imageHandler: Handler? = null
    private var imageHandlerThread: HandlerThread? = null

    /** 当前虚拟显示器宽高（初始方向，不因方向变化重建） */
    private var mirrorWidth = 480
    private var mirrorHeight = 640
    /**
     * 兼容性降级所依据的**基准**分辨率（按连接类型选定后即固定）。
     * 降档是在它的基础上缩放，而不是反复对 mirrorWidth 做乘法累积缩小。
     */
    private var mirrorBaseWidth = 480
    private var mirrorBaseHeight = 640
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
        projectionData = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra("data", Intent::class.java)
        } else {
            intentLegacyParcelableExtra(intent)
        }
        isBluetoothRoute = intent.getBooleanExtra("isBluetooth", false)

        // 记住 WiFi 目标（回切用）：蓝牙启动时 intent 里是隧道地址，真正的眼镜 WiFi IP 从全局配置取
        val labApp = application as? LabApplication
        if (isBluetoothRoute) {
            wifiTargetIp = labApp?.glassesIp.orEmpty()
            wifiTargetPort = labApp?.phoneMirrorPort?.toIntOrNull() ?: AppConfig.DEFAULT_MIRROR_PORT
            btRouteSinceAt = System.currentTimeMillis()
        } else {
            wifiTargetIp = glassesIp
            wifiTargetPort = port
            btRouteSinceAt = 0L
        }

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
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        // getRealMetrics 自 API 30 起废弃，等价替换为 currentWindowMetrics
                        // （两者都含系统装饰区域，含义一致）
                        val bounds = (getSystemService(Context.WINDOW_SERVICE) as WindowManager)
                            .currentWindowMetrics.bounds
                        screenWidth = bounds.width()
                        screenHeight = bounds.height()
                        screenDensity = resources.configuration.densityDpi
                        metrics.widthPixels = bounds.width()
                        metrics.heightPixels = bounds.height()
                        metrics.densityDpi = screenDensity
                    } else {
                        @Suppress("DEPRECATION") display?.getRealMetrics(metrics) ?: metrics.setToDefaults()
                        screenWidth = metrics.widthPixels
                        screenHeight = metrics.heightPixels
                        screenDensity = metrics.densityDpi
                    }

                    // ── 根据连接类型选择投屏参数（WiFi vs 蓝牙） ──
                    if (isBluetoothRoute) {
                        mirrorBaseWidth = AppConfig.MIRROR_BT_WIDTH
                        mirrorBaseHeight = AppConfig.MIRROR_BT_HEIGHT
                        TARGET_FPS_RUNTIME = AppConfig.MIRROR_BT_FPS
                        Log.i(TAG, "BT route base: ${mirrorBaseWidth}x${mirrorBaseHeight} @ ${TARGET_FPS_RUNTIME}fps")
                    } else {
                        mirrorBaseWidth = AppConfig.MIRROR_WIFI_WIDTH
                        mirrorBaseHeight = AppConfig.MIRROR_WIFI_HEIGHT
                        TARGET_FPS_RUNTIME = AppConfig.MIRROR_WIFI_FPS
                        Log.i(TAG, "WiFi route base: ${mirrorBaseWidth}x${mirrorBaseHeight} @ ${TARGET_FPS_RUNTIME}fps")
                    }

                    // ── 兼容性：从上次学成功的档位开始，否则从最低档逐步探测 ──
                    compatTier = MirrorCompat.learnedTier(this@PhoneMirrorService)
                        ?: MirrorCompat.Tier.BASELINE
                    frameHealthy = false
                    val compatParams = MirrorCompat.paramsFor(compatTier, mirrorBaseWidth, mirrorBaseHeight)
                    mirrorWidth = compatParams.width
                    mirrorHeight = compatParams.height
                    Log.i(TAG, "Start tier=${compatTier.name} (${compatTier.note}) -> ${mirrorWidth}x${mirrorHeight}, " +
                        "flags=${compatParams.flags}, buffers=${compatParams.buffers}")
                    Log.i(TAG, "Screen size: ${screenWidth}x${screenHeight}, Mirror size: ${mirrorWidth}x${mirrorHeight}")

                    // 2. 连接眼镜（使用配置的超时时间）
                    // 首连失败不致命：CXR-L 启动眼镜端 Activity 是异步的，Server 可能尚未监听。
                    // 失败后置空 socket，继续创建 MediaProjection/VirtualDisplay，
                    // 由 sendFrame 触发 reconnectSocket 在眼镜端就绪后自动恢复。
                    // 蓝牙线路：本服务要长期占用隧道，先让共享 ADB 会话腾出通道，再按
                    // LONG_LIVED 优先级占用蓝牙通道。手机侧蓝牙栈对「同一设备 + 同一 SCN」
                    // 只允许一条客户端 RFCOMM 通道，共享会话还占着的话建链会被栈直接拒绝
                    // （表现为投屏连不上）。占租约后 BACKGROUND 兜底轮询会按优先级让路。
                    if (isBluetoothRoute) {
                        runCatching {
                            mirrorCoordinator.acquireBluetoothLease("phone-mirror")
                        }
                    }
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
                imageReader?.setOnImageAvailableListener(null, null)
                imageReader?.close()
                surface?.release()
                virtualDisplay?.release()
            } catch (_: Exception) {}

            // 重建路径可能发生在 stopMirror 之后，兜底保证 Handler 可用
            if (imageHandler == null) {
                imageHandlerThread = HandlerThread("ImageHandlerThread")
                imageHandlerThread?.start()
                imageHandler = Handler(imageHandlerThread!!.looper)
            }

            val params = MirrorCompat.paramsFor(compatTier, mirrorBaseWidth, mirrorBaseHeight)
            mirrorWidth = params.width
            mirrorHeight = params.height

            imageReader = ImageReader.newInstance(
                mirrorWidth, mirrorHeight, PixelFormat.RGBA_8888, params.buffers
            )
            surface = imageReader?.surface
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "PhoneMirror", mirrorWidth, mirrorHeight, screenDensity,
                params.flags, surface, null, null
            )
            Log.i(TAG, "VirtualDisplay created: ${mirrorWidth}x${mirrorHeight} @ ${screenDensity}dpi, " +
                "tier=${compatTier.name}, flags=${params.flags}, buffers=${params.buffers}")

            // 每个新会话重置计数，否则会把上一档的黑帧数累计进来
            blackProbe = MirrorCompat.BlackFrameProbe(
                onPersistentBlack = ::escalateCompatTier,
                onHealthy = {
                    frameHealthy = true
                    MirrorCompat.rememberSuccess(this@PhoneMirrorService, compatTier)
                    Log.i(TAG, "Mirror healthy at tier ${compatTier.name}")
                },
            ).also { it.reset() }

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

        // 兼容性探测：把这一帧是否为「无内容黑帧」喂给探测器，
        // 连续发黑即触发降档。开销极小（每 8 像素采样一次）。
        runCatching { blackProbe?.submit(MirrorCompat.isBlackFrame(grayData, w, h)) }

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
                // WiFi 线路中途失效（手机走出覆盖 / 路由重启）时，重连同一个 WiFi 地址永远不会成功；
                // 连续失败 3 次后一次性降级到蓝牙隧道，否则投屏会一直卡在「已连接但没画面」直到用户手动重开。
                if (reconnectAttempts >= BT_DOWNGRADE_AFTER_FAILURES && !isBluetoothRoute && !routeDowngraded) {
                    downgradeToBluetoothTunnel()
                }
            }
        }
    }

    /**
     * 线路降级：WiFi 直连失败后改走蓝牙隧道（一次性，不回头）。
     *
     * 降级时必须**同步切换投屏档位**（分辨率/帧率）：蓝牙带宽只有 WiFi 的零头，
     * 沿用 WiFi 档位会导致每帧都塞不进隧道、发送缓冲区永远排满，界面表现是
     * 「显示已连接，但画面一帧都不动」—— 比直接报连接失败更难排查。
     */
    private fun downgradeToBluetoothTunnel() {
        val app = application as? LabApplication ?: return
        // 定性为 WiFi 断线：清线路缓存并记账，避免其他消费者（AI 工具/上传）继续命中已死的 WiFi
        app.routeManager.noteWifiFailure()
        // 非蓝牙线路时 `port` 即眼镜侧目标端口（见 onStartCommand 的线路映射）
        val targetPort = port
        val localPort = app.routeManager.tunnelTo(targetPort) ?: run {
            Log.w(TAG, "BT 隧道降级失败：无可用隧道")
            return
        }
        routeDowngraded = true
        isBluetoothRoute = true
        btRouteSinceAt = System.currentTimeMillis()
        wifiUpgradeProbeStreak = 0
        lastWifiUpgradeProbeAt = 0L
        glassesIp = "127.0.0.1"
        port = localPort
        mirrorBaseWidth = AppConfig.MIRROR_BT_WIDTH
        mirrorBaseHeight = AppConfig.MIRROR_BT_HEIGHT
        TARGET_FPS_RUNTIME = AppConfig.MIRROR_BT_FPS
        Log.w(TAG, "WiFi 线路不可用，降级蓝牙隧道 127.0.0.1:$localPort → :$targetPort @ ${TARGET_FPS_RUNTIME}fps")
        LogCollector.w(TAG, "投屏线路已从 WiFi 降级为蓝牙隧道（WiFi 重连连续失败）", null)
        // 蓝牙线路要长期独占通道：先让共享 ADB 会话腾出 RFCOMM，再按 LONG_LIVED 占租约
        runCatching { mirrorCoordinator.acquireBluetoothLease("phone-mirror") }
        // 档位变了必须重建虚拟屏（ImageReader/发送缓冲区尺寸都要跟着变）
        Thread {
            runCatching { rebuildMirrorSession() }
                .onFailure { Log.e(TAG, "Rebuild after BT downgrade failed: ${it.message}", it) }
            reconnectSocket()
        }.apply { name = "mirror-bt-downgrade"; isDaemon = true }.start()
    }

    /**
     * 蓝牙线路 → WiFi 自动回切（带迟滞防抖）。
     *
     * 降级是「WiFi 断了」的被动结果；若 WiFi 恢复（走回覆盖范围 / 路由重启完成）后不回切，
     * 整场投屏就只能停留在低码率蓝牙档位。回切条件刻意保守，避免来回切：
     *   ① 转入蓝牙线路后静默 ≥ [BT_DWELL_BEFORE_WIFI_UPGRADE_MS]
     *   ② 每 [WIFI_UPGRADE_PROBE_INTERVAL_MS] 才探测一次 adbd(5555)
     *   ③ 连续 [WIFI_UPGRADE_PROBE_SUCCESSES] 次探测成功才真正回切
     *
     * 探测放独立线程：单次 TCP connect 最长 2s 超时，不能阻塞 5s 周期的看门狗。
     */
    private fun maybeUpgradeToWifi() {
        if (!isMirrorRunning || !isBluetoothRoute || upgradingToWifi) return
        val app = application as? LabApplication ?: return
        val ip = wifiTargetIp.ifBlank { app.glassesIp }
        if (ip.isBlank() || ip == "127.0.0.1") return
        val now = System.currentTimeMillis()
        if (btRouteSinceAt > 0 && now - btRouteSinceAt < BT_DWELL_BEFORE_WIFI_UPGRADE_MS) return
        if (now - lastWifiUpgradeProbeAt < WIFI_UPGRADE_PROBE_INTERVAL_MS) return
        lastWifiUpgradeProbeAt = now
        Thread {
            val reachable = runCatching { app.routeManager.isWifiReachable(ip) }.getOrDefault(false)
            if (!reachable) {
                wifiUpgradeProbeStreak = 0
                return@Thread
            }
            wifiUpgradeProbeStreak += 1
            Log.i(TAG, "WiFi 回切探测成功 $wifiUpgradeProbeStreak/$WIFI_UPGRADE_PROBE_SUCCESSES ($ip)")
            if (wifiUpgradeProbeStreak >= WIFI_UPGRADE_PROBE_SUCCESSES) upgradeToWifiRoute(ip)
        }.apply { name = "mirror-wifi-upgrade-probe"; isDaemon = true }.start()
    }

    /** 实际执行回切：恢复 WiFi 档位、释放蓝牙租约、重建虚拟屏并重连 */
    private fun upgradeToWifiRoute(ip: String) {
        if (upgradingToWifi) return
        upgradingToWifi = true
        try {
            val app = application as? LabApplication
            val targetPort = wifiTargetPort
            LogCollector.i(TAG, "投屏线路已从蓝牙隧道回切为 WiFi 直连（连续 $WIFI_UPGRADE_PROBE_SUCCESSES 次探测成功）")
            // 1. 释放蓝牙独占租约：RFCOMM 交还，共享 ADB 会话恢复可用
            runCatching { mirrorCoordinator.releaseLease() }
            // 2. 清线路缓存：降级期间缓存里是蓝牙条目，WiFi 已恢复，其他消费者也应重新决策
            runCatching { app?.routeManager?.clearRouteCache() }
            // 3. 切回 WiFi 档位（分辨率/帧率/缓冲随 isBluetoothRoute 在重建时生效）
            isBluetoothRoute = false
            routeDowngraded = false
            wifiUpgradeProbeStreak = 0
            btRouteSinceAt = 0L
            reconnectAttempts = 0
            glassesIp = ip
            port = targetPort
            mirrorBaseWidth = AppConfig.MIRROR_WIFI_WIDTH
            mirrorBaseHeight = AppConfig.MIRROR_WIFI_HEIGHT
            TARGET_FPS_RUNTIME = AppConfig.MIRROR_WIFI_FPS
            Log.i(TAG, "WiFi 回切目标 $ip:$targetPort @ ${TARGET_FPS_RUNTIME}fps")
            // 4. 档位变了必须重建虚拟屏，再重连
            Thread {
                runCatching { rebuildMirrorSession() }
                    .onFailure { Log.e(TAG, "Rebuild after WiFi upgrade failed: ${it.message}", it) }
                reconnectSocket()
            }.apply { name = "mirror-wifi-upgrade"; isDaemon = true }.start()
        } finally {
            upgradingToWifi = false
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
                // 蓝牙线路上周期性探测 WiFi 是否恢复（回切，带迟滞防抖，见 maybeUpgradeToWifi）
                maybeUpgradeToWifi()
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
        // 离场：释放蓝牙通道租约（幂等），让共享 ADB 会话/兜底轮询恢复使用通道
        mirrorCoordinator.releaseLease()
        stopSelf()
    }

    /**
     * API 33 以下取 Intent 类型 extra 的老写法。
     * 单独抽成方法才能在**声明**上挂 @Suppress —— 行内 @Suppress 对表达式不生效。
     */
    @Suppress("DEPRECATION")
    private fun intentLegacyParcelableExtra(intent: Intent): Intent? =
        intent.getParcelableExtra("data") as? Intent
}
