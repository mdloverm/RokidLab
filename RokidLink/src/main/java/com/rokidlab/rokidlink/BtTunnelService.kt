package com.rokidlab.rokidlink

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 蓝牙隧道前台服务（眼镜端）
 *
 * 独立于 Activity 生命周期，确保蓝牙隧道服务端持续运行。
 * 手机端 BtTunnelClient 通过 RFCOMM 连接，透传 TCP 流量到眼镜本地服务。
 *
 * 同时负责启用 ADB TCP (port 5555)：MainActivity 在无 WiFi 时会直接 finish，
 * 其 enableAdbTcp() 不会执行，导致纯蓝牙模式下隧道连不上本地 adbd。
 * 故将 ADB 启用逻辑迁移到本服务，保证无论有无 WiFi 都可用。
 */
class BtTunnelService : Service() {
    companion object {
        private const val TAG = "BtTunnelService"
        private const val NOTIFICATION_ID = 2001
        private const val CHANNEL_ID = "BtTunnel"
        private const val ADB_PORT = 5555

        /** 蓝牙未开启时隧道重启的重试间隔 */
        private const val BT_RETRY_INTERVAL_MS = 3_000L

        /** startForeground 被拒后的重试间隔 / 上限 */
        private const val FOREGROUND_RETRY_INTERVAL_MS = 5_000L
        private const val MAX_FOREGROUND_RETRIES = 10

        /** 健康看门狗巡检间隔 */
        private const val WATCHDOG_INTERVAL_MS = 30_000L

        /** 服务销毁后自愈重启的重试间隔 / 上限 */
        private const val RESTART_RETRY_INTERVAL_MS = 5_000L
        private const val MAX_RESTART_ATTEMPTS = 10

        fun start(context: Context) {
            val intent = Intent(context, BtTunnelService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BtTunnelService::class.java))
        }
    }

    private var tunnelServer: BtTunnelServer? = null

    /** ADB 启用线程引用，onDestroy 时中断避免线程泄漏 */
    private var enableAdbThread: Thread? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 蓝牙未开启时的延迟重试任务（防重复叠加） */
    private val tunnelRetryRunnable = Runnable { startTunnel() }

    /** 蓝牙状态广播：蓝牙关闭时服务不再自杀，开启后自动重启隧道 */
    private val bluetoothStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED &&
                intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR) == BluetoothAdapter.STATE_ON
            ) {
                Log.i(TAG, "Bluetooth turned on, restarting tunnel")
                startTunnel()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 兜底：startForegroundService 后系统要求 5 秒内建立前台状态，
        // 若 onCreate 因主线程繁忙延迟，这里再补一次，避免 ForegroundServiceDidNotStartInTimeException 崩溃
        startForegroundSafe("onStartCommand")
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForegroundSafe("onCreate")
        // 监听蓝牙开启广播：蓝牙恢复后自动重启隧道，避免通道永久失效
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(bluetoothStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(bluetoothStateReceiver, filter)
        }
        startTunnel()
        // 独立于 Activity 启用 ADB TCP，确保纯蓝牙模式下 adbd 监听 5555
        enableAdbTcp()
        // 健康看门狗：隧道异常 / ADB 失效 / BridgeActivity 销毁时自动恢复，防止链路永久失效
        startWatchdog()
    }

    /**
     * 安全进入前台状态：后台 FGS 启动受限（bg restriction）时**不崩溃**，
     * 降级为普通服务继续运行，并延迟重试转前台（进程获得前台窗口/系统放行后可能成功）。
     * 若不捕获该异常，服务 onCreate 会崩溃导致系统反复 restart 而隧道始终不可用。
     */
    private fun startForegroundSafe(tag: String) {
        try {
            startForeground(NOTIFICATION_ID, createNotification())
            foregroundRetryCount = 0
            Log.i(TAG, "startForeground ok ($tag)")
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed ($tag): ${e.message}")
            retryStartForeground()
        }
    }

    /** startForeground 被拒后的延迟重试（每 5s，最多 10 次） */
    private var foregroundRetryCount = 0
    private fun retryStartForeground() {
        if (foregroundRetryCount >= MAX_FOREGROUND_RETRIES) {
            Log.w(TAG, "Give up startForeground retry after $MAX_FOREGROUND_RETRIES attempts")
            return
        }
        foregroundRetryCount++
        mainHandler.postDelayed({
            try {
                startForeground(NOTIFICATION_ID, createNotification())
                foregroundRetryCount = 0
                Log.i(TAG, "startForeground ok on retry #$foregroundRetryCount")
            } catch (e: Exception) {
                Log.w(TAG, "startForeground retry #$foregroundRetryCount failed: ${e.message}")
                retryStartForeground()
            }
        }, FOREGROUND_RETRY_INTERVAL_MS)
    }

    // ---------------- 健康看门狗 ----------------

    private val watchdogRunnable = Runnable { checkHealth() }

    /** 每 30s 巡检：隧道服务 / ADB TCP / 常驻 BridgeActivity，任一失效自动恢复 */
    private fun startWatchdog() {
        mainHandler.removeCallbacks(watchdogRunnable)
        mainHandler.postDelayed(watchdogRunnable, WATCHDOG_INTERVAL_MS)
    }

    private fun checkHealth() {
        try {
            // 1) 隧道健康：BtTunnelServer 停止则重建（被系统停服务后 START_STICKY 重启场景）
            if (tunnelServer?.isRunning != true) {
                Log.w(TAG, "Tunnel server not running, restarting tunnel")
                startTunnel()
            }
            // 2) BridgeActivity 保活：透明 Activity 维持进程 VISIBLE，避免被系统标记为后台。
            //    注意：官方 AI 会话活跃期间（KeyButtonService.officialAiSessionActive）不拉起——
            //    此时 BridgeActivity 在前台会让 AssistServer 判定为 third_app 场景，官方会话
            //    结束时 force stop RokidLink。退让期间靠 FGS+WakeLock 保活，会话结束后由
            //    KeyButtonService 的 restoreBridgeRunnable（10s 无 AI 活动）自动恢复。
            if (!KeyButtonBridgeActivity.isAlive && !KeyButtonService.officialAiSessionActive) {
                Log.i(TAG, "KeyButtonBridgeActivity not alive, relaunching")
                runCatching {
                    startActivity(
                        Intent(this, KeyButtonBridgeActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }.onFailure { Log.w(TAG, "relaunch BridgeActivity failed: ${it.message}") }
            }
        } catch (e: Exception) {
            Log.w(TAG, "checkHealth error: ${e.message}")
        }
        startWatchdog()
    }

    // ---------------- 自愈重启 ----------------

    /** onDestroy 后延迟检查，若服务未恢复（app idle 停服务）则主动拉起 */
    private fun retryRestartSelf(attempt: Int) {
        if (attempt >= MAX_RESTART_ATTEMPTS) {
            Log.e(TAG, "Give up restarting after $MAX_RESTART_ATTEMPTS attempts")
            return
        }
        mainHandler.postDelayed({
            if (isServiceRunning(BtTunnelService::class.java)) {
                Log.i(TAG, "BtTunnelService running again, no restart needed")
                return@postDelayed
            }
            Log.w(TAG, "BtTunnelService not running after destroy, restarting (attempt ${attempt + 1})")
            try {
                // 用普通 startService 而非 startForegroundService，规避 FGS 后台启动限制；
                // onStartCommand 中 startForegroundSafe 会尝试转前台
                startService(Intent(this, BtTunnelService::class.java))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to restart BtTunnelService", e)
            }
            retryRestartSelf(attempt + 1)
        }, RESTART_RETRY_INTERVAL_MS)
    }

    private fun isServiceRunning(clazz: Class<*>): Boolean {
        return try {
            val am = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
            am.getRunningServices(100).any {
                it.service.packageName == packageName && it.service.className == clazz.name
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 释放当前隧道服务端实例（幂等）。
     * 必须先 stop 再置空：BtTunnelServer 的 keep-alive accept 线程与 serverSocket 只有
     * stop() 才会关闭，直接丢弃引用会让旧实例变成孤儿，永久占住 RFCOMM SCN。
     */
    private fun releaseTunnelServer() {
        runCatching { tunnelServer?.stop() }
        tunnelServer = null
    }

    /** 延迟重试启动隧道：先清掉已排队的重试，避免多个重试叠加各建一个实例 */
    private fun scheduleTunnelRetry() {
        mainHandler.removeCallbacks(tunnelRetryRunnable)
        mainHandler.postDelayed(tunnelRetryRunnable, BT_RETRY_INTERVAL_MS)
    }

    private fun startTunnel() {
        try {
            val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            if (adapter?.isEnabled != true) {
                // 蓝牙未开启时不自杀：先释放旧监听（否则旧 serverSocket 占着 SCN 成为孤儿），延迟重试；
                // 蓝牙开启广播到达时也会立即重试
                releaseTunnelServer()
                Log.w(TAG, "Bluetooth disabled, will retry tunnel")
                scheduleTunnelRetry()
                return
            }
            // 先停旧实例再建新：蓝牙关闭/启动失败等场景下旧 serverSocket 可能仍注册着 SCN，
            // 直接 new 会把旧实例（含其 accept 线程与 socket）变成永远不释放的孤儿，
            // 新实例 listen 失败或与孤儿在同一 SCN 上双监听
            releaseTunnelServer()
            val server = BtTunnelServer()
            tunnelServer = server
            val ok = server.start(adapter)
            Log.i(TAG, "BtTunnelServer started: $ok")
            if (!ok) {
                Log.e(TAG, "Failed to start BtTunnelServer, will retry")
                releaseTunnelServer()
                scheduleTunnelRetry()
                return
            }
            // 启动成功：清掉排队中的重试，否则稍后重试会再建一个实例（孤儿 + 双监听）
            mainHandler.removeCallbacks(tunnelRetryRunnable)
            // 第二 RFCOMM 通道：ASR 文字实时推送（长连接，独立于 ADB 隧道）。
            // 失败不影响 ADB 隧道（推送通道降级为手机端文件轮询兜底）。
            val pushOk = AsrPushServer.start(adapter)
            Log.i(TAG, "AsrPushServer started: $pushOk")
        } catch (e: Exception) {
            Log.e(TAG, "startTunnel failed, will retry", e)
            releaseTunnelServer()
            scheduleTunnelRetry()
        }
    }

    private fun isAdbTcpListening(): Boolean {
        var socket: Socket? = null
        return try {
            socket = Socket()
            socket.connect(InetSocketAddress("127.0.0.1", ADB_PORT), 500)
            socket.close()
            true
        } catch (e: Exception) {
            try { socket?.close() } catch (_: Exception) {}
            false
        }
    }

    private fun tryExec(vararg cmd: String) {
        try {
            val p = Runtime.getRuntime().exec(cmd)
            p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
            p.destroy()
        } catch (e: Exception) {
            Log.d(TAG, "command failed: ${cmd.joinToString(" ")}")
        }
    }

    /** 在后台线程启用 ADB TCP：setprop 端口 + 重启 adbd + 轮询检测 */
    private fun enableAdbTcp() {
        enableAdbThread = Thread {
            Log.i(TAG, getString(R.string.log_try_enable_adb_tcp))
            try {
                Runtime.getRuntime().exec(arrayOf("setprop", "service.adb.tcp.port", ADB_PORT.toString()))
                Thread.sleep(300)
            } catch (e: Exception) {
                Log.e(TAG, "setprop failed", e)
            }

            for (attempt in 1..3) {
                Log.i(TAG, getString(R.string.log_try_attempt, attempt))
                tryExec("setprop", "ctl.restart", "adbd")

                for (wait in 1..4) {
                    Thread.sleep(1000)
                    if (isAdbTcpListening()) {
                        Log.i(TAG, getString(R.string.log_adb_tcp_enabled, attempt, wait))
                        return@Thread
                    }
                }
            }
            Log.w(TAG, getString(R.string.log_retry_failed))
        }.apply {
            name = "enable-adb-tcp"
            start()
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(tunnelRetryRunnable)
        mainHandler.removeCallbacks(watchdogRunnable)
        runCatching { unregisterReceiver(bluetoothStateReceiver) }
        enableAdbThread?.interrupt()
        enableAdbThread = null
        tunnelServer?.stop()
        tunnelServer = null
        AsrPushServer.stop()
        Log.i(TAG, "BtTunnelService destroyed")
        // 自愈：app idle 可能停掉本服务（实测日志：Stopping service due to app idle），
        // 主动延迟检查并拉起，配合 START_STICKY 系统重启双重保障隧道尽快恢复
        retryRestartSelf(0)
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "BT Tunnel",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Bluetooth tunnel service" }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("RokidLink Tunnel")
                .setContentText("Bluetooth tunnel active")
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .build()
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
                .setContentTitle("RokidLink Tunnel")
                .setContentText("Bluetooth tunnel active")
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .build()
        }
    }
}
