package com.rokidlab.phone.feature

import android.os.Bundle
import android.util.Log
import android.view.Surface
import com.rokidlab.phone.R
import com.rokidlab.phone.adb.AdbScreenMirrorClient
import com.rokidlab.phone.adb.ScreenStreamDecoder
import com.rokidlab.phone.app.LabApplication
import com.rokidlab.phone.connection.ConnectionRoute
import com.rokidlab.phone.mirror.ScreenMirrorActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 屏幕镜像会话状态机（Phase 5：从 ScreenMirrorActivity 逐字迁出，activity-handle 模式）。
 *
 * 负责：连接状态快照（Compose 可观察）、启动宽限 + 路由解析、蓝牙租约获取/释放、
 * H.264 解码与 screencap 降级两种串流路径、Surface 兜底补画、销毁清理。
 * Activity 只保留 Compose 页面编排；Compose 直接读本类快照状态。
 */
internal class ScreenMirrorStateHolder(private val activity: ScreenMirrorActivity) {
    private companion object {
        const val TAG = "ScreenMirror"
        const val GLASSES_ADB_PORT = 5555

        /**
         * 眼镜端接收页由 CXR-L 异步拉起，需要一点宽限时间才可用；同时避开 CXR-L
         * 正在占用蓝牙链路的那一小段（原为 MainActivity 里 postDelayed 的 2 秒）。
         * 现在在镜像页内等待：界面会显示「正在启动眼镜端...」，且与路由解析并行。
         */
        const val GLASSES_LAUNCH_GRACE_MS = 2000L
    }

    internal var ipAddress by mutableStateOf("192.168.1.168")
    internal var isStreaming by mutableStateOf(false)

    /**
     * 启动阶段（打开页面 → 启动宽限 → 路由解析 → 建链）：
     * 期间画面还没有内容，必须把状态文案显示出来 —— 否则提前打开页面只会看到黑屏。
     * 连接成功（或失败）后置 false。
     */
    internal var startupPhase by mutableStateOf(true)
    internal var connectionStatus by mutableStateOf("")
    internal var connectionFailed by mutableStateOf(false)
    internal var scale by mutableStateOf(1f)
    internal var offsetX by mutableStateOf(0f)
    internal var offsetY by mutableStateOf(0f)
    internal var adbClient by mutableStateOf<AdbScreenMirrorClient?>(null)
    internal var streamDecoder: ScreenStreamDecoder? = null
    internal var surface: Surface? = null
    // screencap 降级模式的显示兜底：最新一帧 + 可用 Surface（surface 晚于连接就绪时也能出画面）
    @Volatile private var fallbackFrame: android.graphics.Bitmap? = null
    @Volatile private var fallbackSurface: Surface? = null
    internal var glassesWidth by mutableIntStateOf(480)
    internal var glassesHeight by mutableIntStateOf(640)
    /** 当前连接是否蓝牙隧道线路 */
    internal var isBluetoothRoute = false
    @Volatile
    internal var isDestroyed = false

    internal var isConnecting = false

    /** 把 screencap 降级模式的最新一帧绘制到可用 Surface（无解码器路径的显示兜底） */
    internal fun drawFallbackFrame() {
        val bmp = fallbackFrame ?: return
        val s = fallbackSurface ?: return
        runCatching {
            val canvas = s.lockCanvas(null)
            try {
                canvas.drawBitmap(bmp, null, android.graphics.RectF(0f, 0f, canvas.width.toFloat(), canvas.height.toFloat()), null)
            } finally {
                s.unlockCanvasAndPost(canvas)
            }
        }
    }

    /**
     * 解析到眼镜的线路（WiFi 直连或蓝牙隧道），并在「眼镜端接收页启动宽限」结束后返回。
     *
     * 原实现把这段宽限放在 MainActivity 里 `postDelayed(2000)`，那 2 秒本 Activity 还没打开、
     * 界面没有任何反馈，用户体感就是「卡住」。
     *
     * 现在：Activity 已打开并显示「正在启动眼镜端...」；
     * 宽限与路由解析（WiFi 探测最长 PROBE_TIMEOUT_MS=2s）**并行**，谁慢等谁，
     * 避免原先「MainActivity 2s + 这里 2s」的串行叠加。
     *
     * 注意：resolve() 只启动隧道本地监听，RFCOMM 要等真正有客户端连上才建立，
     * 所以提前解析不会与 CXR-L 正在进行的蓝牙通信抢通道。
     *
     * @return 线路；被中断时返回 null（调用方按连接失败处理）
     */
    internal fun resolveRouteWithLaunchGrace(app: LabApplication, ip: String): ConnectionRoute? =
        activity.mirrorCoordinator.resolveRouteWithGrace(ip, GLASSES_ADB_PORT, GLASSES_LAUNCH_GRACE_MS, "screen-mirror-route")

    internal fun connectToGlasses(surfaceOverride: Surface? = null) {
        if (isConnecting) return
        isConnecting = true
        val useSurface = surfaceOverride ?: surface
        connectionStatus = activity.getString(R.string.mirror_starting)
        isStreaming = true

        Thread {
            val app = activity.application as LabApplication
            // 首启时眼镜端 TOPIC_GLASSES_IP 上行常晚于本页创建：若此刻就拿占位默认 IP
            // 去探测 WiFi 必然失败 → 回落到蓝牙隧道（「第一次启动没有切到 WiFi」）。
            // 这里等 IP 就绪（最多一个启动宽限），已确认时零等待。
            val latestIp = app.awaitGlassesIp(GLASSES_LAUNCH_GRACE_MS)
            connectionStatus = activity.getString(R.string.starting_glasses)
            // 启动宽限（等 CXR-L 把眼镜端接收页拉起来）与路由解析（WiFi 探测最长 2s）并行，
            // 谁慢等谁 —— 原先这两段是串行的（MainActivity 2s + 这里 2s）。
            val route = resolveRouteWithLaunchGrace(app, latestIp)
            if (route == null) {
                activity.runOnUiThread {
                    startupPhase = false
                    connectionStatus = activity.getString(R.string.mirror_connection_failed)
                    connectionFailed = true
                    isStreaming = false
                }
                isConnecting = false
                return@Thread
            }
            // 宽限期间用户可能已退出本页：不要再继续建链
            if (isDestroyed) { isConnecting = false; return@Thread }
            val (targetIp, targetPort) = when (route) {
                is ConnectionRoute.Wifi -> {
                    isBluetoothRoute = false
                    route.ip to route.port
                }
                is ConnectionRoute.Bluetooth -> {
                    isBluetoothRoute = true
                    route.ip to route.localPort
                }
                is ConnectionRoute.None -> {
                    activity.runOnUiThread {
                        startupPhase = false
                        connectionStatus = activity.getString(R.string.mirror_connection_failed)
                        connectionFailed = true
                        isStreaming = false
                    }
                    isConnecting = false
                    return@Thread
                }
            }
            // 长连接上场：先让全 App 共享 ADB 会话腾出 RFCOMM 通道，再按 LONG_LIVED 优先级
            // 占用蓝牙通道。手机侧蓝牙栈对「同一设备 + 同一 SCN」只允许一条客户端 RFCOMM 通道，
            // 共享会话（ADB 工具/AI 工具/ASR 兜底轮询）还占着的话，这里建链会被栈拒绝
            // → 界面直接「连接失败」。占租约后，BACKGROUND 兜底轮询会按优先级自动让路。
            // WiFi 线路各走各的 TCP，不涉及 SCN 争抢，无需占用/让路。
            if (isBluetoothRoute) {
                activity.mirrorCoordinator.acquireBluetoothLease("screen-mirror")
            }
            val client = AdbScreenMirrorClient(activity, targetIp, targetPort)
            // H264 解码器不可用时的降级帧也走统一的 screencap 显示兜底
            client.onFallbackFrame = { bitmap ->
                fallbackFrame = bitmap
                activity.runOnUiThread {
                    if (glassesWidth != bitmap.width || glassesHeight != bitmap.height) {
                        glassesWidth = bitmap.width
                        glassesHeight = bitmap.height
                    }
                }
                drawFallbackFrame()
            }
            adbClient = client
            if (isDestroyed) { isConnecting = false; return@Thread }

            val connected = client.connect { status ->
                activity.runOnUiThread { connectionStatus = status }
            }
            if (isDestroyed || !connected) {
                if (!connected) {
                    activity.runOnUiThread {
                        startupPhase = false
                        connectionStatus = activity.getString(R.string.mirror_connection_failed)
                        connectionFailed = true
                        isStreaming = false
                    }
                }
                isConnecting = false
                return@Thread
            }

            activity.runOnUiThread {
                startupPhase = false
                connectionStatus = activity.getString(R.string.mirror_connected)
            }

            if (useSurface != null) {
                // H.264 硬件解码模式 - 使用 scrcpy-server
                val decoder = ScreenStreamDecoder(useSurface).also {
                    it.onVideoSizeChanged = { w, h ->
                        activity.runOnUiThread {
                            glassesWidth = w
                            glassesHeight = h
                            Log.i(TAG, "video size: ${w}x${h}")
                        }
                    }
                }
                streamDecoder = decoder
                decoder.start()
                Log.i(TAG, "H.264 decoder created")

                // 连接后的 ADB 端 shell 命令错误等重置
                connectionStatus = activity.getString(R.string.mirror_waiting_stream)

                client.startH264Streaming(decoder, onStatus = { status ->
                    activity.runOnUiThread {
                        connectionStatus = status
                        if (status.contains("fail") || status.contains("interrupt")) {
                            connectionFailed = true
                            isStreaming = false
                        }
                    }
                }, isBluetooth = isBluetoothRoute)
            } else {
                // 降级：screencap 原始像素模式
                client.startStreaming(
                    onFrame = { bitmap ->
                        // screencap 帧直接绘制到 TextureView 的 Surface（连接早于 surface 就绪时，
                        // 待 surface 到位后由 onSurfaceReady 补画最新帧）
                        fallbackFrame = bitmap
                        activity.runOnUiThread {
                            if (glassesWidth != bitmap.width || glassesHeight != bitmap.height) {
                                glassesWidth = bitmap.width
                                glassesHeight = bitmap.height
                            }
                        }
                        drawFallbackFrame()
                    },
                    onStatus = { status ->
                        activity.runOnUiThread {
                            connectionStatus = status
                            if (status.contains("fail") || status.contains("interrupt")) {
                                connectionFailed = true
                                isStreaming = false
                            }
                        }
                    }
                )
            }
            isConnecting = false
        }.apply {
            name = "mirror-connector"
            start()
        }
    }

    /** 解除蓝牙通道占用（幂等）：让 BACKGROUND 兜底轮询恢复使用共享 ADB 会话 */
    internal fun releaseChannelLease() = activity.mirrorCoordinator.releaseLease()

    internal fun disconnectAndFinish() {
        isStreaming = false
        streamDecoder?.stop()
        streamDecoder = null
        adbClient?.disconnect()
        adbClient = null
        releaseChannelLease()
        activity.finish()
    }

    // ── 生命周期入口（由 Activity 调用） ──

    /** onCreate 初始化：进入启动态（页面显示「正在启动眼镜端...」） */
    internal fun init() {
        isStreaming = true
        connectionStatus = activity.getString(R.string.mirror_starting)
    }

    /** 失败页重试：复位状态并重新连接 */
    internal fun retry() {
        connectionFailed = false
        startupPhase = true
        connectionStatus = activity.getString(R.string.mirror_starting)
        isStreaming = true
        connectToGlasses()
    }

    /** 缩放手势：写回缩放/位移（缩放钳制在 0.5x~4x） */
    internal fun applyScale(newScale: Float, x: Float, y: Float) {
        scale = newScale.coerceIn(0.5f, 4f)
        offsetX = x
        offsetY = y
    }

    /**
     * TextureView Surface 就绪：记录引用、补画最新帧（screencap 降级模式），
     * 并以该 Surface 发起（或重发）连接。
     */
    internal fun onSurfaceReady(s: Surface) {
        surface = s
        fallbackSurface = s
        drawFallbackFrame()
        connectToGlasses(s)
    }

    /** Activity 销毁：停解码、断链、释放蓝牙租约（幂等）。 */
    internal fun onActivityDestroy() {
        isDestroyed = true
        isStreaming = false
        streamDecoder?.stop()
        streamDecoder = null
        adbClient?.disconnect()
        adbClient = null
        releaseChannelLease()
    }
}
