package com.rokidlab.rokidlink

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * 蓝牙隧道前台服务（眼镜端）
 *
 * 独立于 Activity 生命周期，确保蓝牙隧道服务端持续运行。
 * 手机端 BtTunnelClient 通过 RFCOMM 连接，透传 TCP 流量到眼镜本地服务。
 */
class BtTunnelService : Service() {
    companion object {
        private const val TAG = "BtTunnelService"
        private const val NOTIFICATION_ID = 2001
        private const val CHANNEL_ID = "BtTunnel"

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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        startTunnel()
    }

    private fun startTunnel() {
        try {
            val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            if (adapter?.isEnabled != true) {
                Log.w(TAG, "Bluetooth disabled, cannot start tunnel")
                stopSelf()
                return
            }
            tunnelServer = BtTunnelServer()
            val ok = tunnelServer?.start(adapter) == true
            Log.i(TAG, "BtTunnelServer started: $ok")
            if (!ok) {
                Log.e(TAG, "Failed to start BtTunnelServer, stopping service")
                stopSelf()
            }
        } catch (e: Exception) {
            Log.e(TAG, "startTunnel failed", e)
            stopSelf()
        }
    }

    override fun onDestroy() {
        tunnelServer?.stop()
        tunnelServer = null
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
