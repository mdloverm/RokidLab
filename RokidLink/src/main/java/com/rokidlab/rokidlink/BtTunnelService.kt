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
        try {
            startForeground(NOTIFICATION_ID, createNotification())
        } catch (e: Exception) {
            Log.e(TAG, "startForeground (onStartCommand) failed", e)
        }
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
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
    }

    private fun startTunnel() {
        try {
            val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            if (adapter?.isEnabled != true) {
                // 蓝牙未开启时不自杀：延迟重试，蓝牙开启广播到达时也会立即重试
                Log.w(TAG, "Bluetooth disabled, will retry tunnel")
                mainHandler.removeCallbacks(tunnelRetryRunnable)
                mainHandler.postDelayed(tunnelRetryRunnable, BT_RETRY_INTERVAL_MS)
                return
            }
            tunnelServer = BtTunnelServer()
            val ok = tunnelServer?.start(adapter) == true
            Log.i(TAG, "BtTunnelServer started: $ok")
            if (!ok) {
                Log.e(TAG, "Failed to start BtTunnelServer, will retry")
                mainHandler.removeCallbacks(tunnelRetryRunnable)
                mainHandler.postDelayed(tunnelRetryRunnable, BT_RETRY_INTERVAL_MS)
                return
            }
            // 第二 RFCOMM 通道：ASR 文字实时推送（长连接，独立于 ADB 隧道）。
            // 失败不影响 ADB 隧道（推送通道降级为手机端文件轮询兜底）。
            val pushOk = AsrPushServer.start(adapter)
            Log.i(TAG, "AsrPushServer started: $pushOk")
        } catch (e: Exception) {
            Log.e(TAG, "startTunnel failed, will retry", e)
            mainHandler.removeCallbacks(tunnelRetryRunnable)
            mainHandler.postDelayed(tunnelRetryRunnable, BT_RETRY_INTERVAL_MS)
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
        runCatching { unregisterReceiver(bluetoothStateReceiver) }
        enableAdbThread?.interrupt()
        enableAdbThread = null
        tunnelServer?.stop()
        tunnelServer = null
        AsrPushServer.stop()
        Log.i(TAG, "BtTunnelService destroyed")
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
