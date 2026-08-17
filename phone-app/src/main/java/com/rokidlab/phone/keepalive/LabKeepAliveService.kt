package com.rokidlab.phone.keepalive

import com.rokidlab.phone.R
import com.rokidlab.phone.app.LabApplication
import com.rokidlab.phone.app.MainActivity
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * 后台保活前台服务（通知栏常驻）
 *
 * 作用：
 *  1. 前台服务使进程优先级拉满，防止系统在内存压力/电池优化下回收 Lab 进程；
 *  2. 配合 LabApplication.appScope，保证 ASR 推送/轮询、蓝牙 HID、ADB 隧道等
 *     后台能力在 Activity 退后台/销毁后仍持续运行；
 *  3. START_STICKY：进程被系统杀掉后自动重建（强制停止除外）。
 *
 * 由 LabApplication.startKeepAliveService() 启动（保活开关开启时）。
 */
class LabKeepAliveService : Service() {
    companion object {
        private const val TAG = "LabKeepAliveService"
        private const val NOTIFICATION_ID = 3001
        private const val CHANNEL_ID = "keep_alive_fgs"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        // 服务启动/自愈重建后，恢复之前运行中的定时任务（调度挂 appScope，进程活着即可触发）
        runCatching {
            (application as LabApplication).timerScheduler.resumeRunningTasks()
        }
        Log.i(TAG, "LabKeepAliveService created (keep-alive active)")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // START_STICKY：系统回收进程后自动重建服务，实现保活自愈
        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "LabKeepAliveService destroyed")
        super.onDestroy()
    }

    private fun startAsForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.keep_alive_notification_title))
            .setContentText(getString(R.string.keep_alive_notification_text))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
