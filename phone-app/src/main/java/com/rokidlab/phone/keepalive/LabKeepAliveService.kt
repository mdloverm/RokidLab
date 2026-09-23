package com.rokidlab.phone.keepalive

import com.rokidlab.phone.R
import com.rokidlab.phone.ai.LocalOllamaManager
import com.rokidlab.phone.app.LabApplication
import com.rokidlab.phone.app.MainActivity
import android.app.AlarmManager
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
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
        private const val RESTART_REQUEST_CODE = 3002
        private const val RESTART_DELAY_MS = 2_000L

        /** 定时任务闹钟投递：[com.rokidlab.phone.adb.TimerAlarmReceiver] 到点把任务 id 送进本服务执行 */
        const val ACTION_FIRE_TIMER = "com.rokidlab.phone.action.FIRE_TIMER"
        const val EXTRA_TASK_ID = "task_id"
    }

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        acquireWakeLock()
        startAsForeground()
        // 服务启动/自愈重建后，恢复之前运行中的定时任务（调度挂 appScope，进程活着即可触发）
        runCatching {
            (application as LabApplication).timerScheduler.resumeRunningTasks()
        }
        // 本地模型守护：Termux 与 Lab 是两个 App，本服务的 WakeLock 管不到它（实测 Termux 自身
        // 持锁也照样被冻结），只能由 App 侧"静默探测 + 按需唤醒"来修复。
        // 注意：每次唤醒 Termux 都会让它重发前台通知（MIUI 会弹横幅），所以只在服务真掉线时才唤醒，
        // 平时不触碰 Termux；详见 LocalOllamaManager.startKeepAlive
        runCatching {
            LocalOllamaManager.startKeepAlive(
                this,
                (application as LabApplication).appScope,
            )
        }.onFailure { Log.w(TAG, "startKeepAlive failed: ${it.message}") }
        // 无界面会话自愈：START_STICKY 重建/开机自启时进程内没有 Activity，
        // 用 Application Context 重建 CXR-L 会话 + ASR 双通道，消除"通知在、语音死"的假活状态
        runCatching {
            (application as LabApplication).ensureHeadlessSession()
        }.onFailure { Log.w(TAG, "ensureHeadlessSession failed: ${it.message}") }
        Log.i(TAG, "LabKeepAliveService created (keep-alive active)")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 定时闹钟到点：由 TimerAlarmReceiver 投递（长任务在 TimerScheduler 自己的协程里跑，
        // 本服务前台身份 + WakeLock 为其托底），不阻塞主线程
        if (intent?.action == ACTION_FIRE_TIMER) {
            val taskId = intent.getStringExtra(EXTRA_TASK_ID)
            if (taskId != null) {
                runCatching {
                    (application as LabApplication).timerScheduler.fireTask(taskId)
                }.onFailure { Log.w(TAG, "fire timer task failed: ${it.message}") }
            }
        }
        // START_STICKY：系统回收进程后自动重建服务，实现保活自愈
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // SwipeUpClean（最近任务上滑清掉卡片）会绕过 FGS 直接杀进程（实测 am_kill reason=SwipeUpClean）。
        // START_STICKY 在部分 ROM（MIUI 神隐/一键优化）下重建会被抑制，安排 2s 后由 BootReceiver
        // 经 AlarmManager 重新拉起本服务作为双保险：alarm 触发时系统授予短暂后台启动豁免，可合法启动 FGS。
        try {
            val restartIntent = Intent(applicationContext, BootReceiver::class.java).apply {
                action = BootReceiver.ACTION_RESTART_KEEPALIVE
            }
            val pending = PendingIntent.getBroadcast(
                this,
                RESTART_REQUEST_CODE,
                restartIntent,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE,
            )
            val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.setAndAllowWhileIdle(AlarmManager.RTC, System.currentTimeMillis() + RESTART_DELAY_MS, pending)
            Log.i(TAG, "onTaskRemoved: restart alarm scheduled in ${RESTART_DELAY_MS}ms")
        } catch (e: Exception) {
            Log.w(TAG, "schedule restart alarm failed: ${e.message}")
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        Log.i(TAG, "LabKeepAliveService destroyed")
        LocalOllamaManager.stopKeepAlive()
        releaseWakeLock()
        super.onDestroy()
    }

    /**
     * 持有 PARTIAL_WAKE_LOCK：阻止 CPU 挂起。
     *
     * 这是防 HyperOS / MIUI cgroup v2 冻结的关键手段——前台服务（FGS）只防"被杀"，
     * 不防"被冻结"；而冻结的进程无法处理任何 Binder 调用、网络请求或传感器事件，
     * 表现为蓝牙断连、ASR 丢字、ollama 探测超时。
     *
     * 持有 WakeLock 的进程不会被冻结：冻结 WakeLock 持有者会导致系统无法休眠，
     * ROM 省电策略不会做这种自相矛盾的操作。
     */
    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "RokidLab:keepalive",
        ).apply {
            setReferenceCounted(false)
            acquire() // 永久持有，直到服务销毁时释放
        }
        Log.i(TAG, "WakeLock acquired (PARTIAL_WAKE_LOCK)")
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) it.release()
            Log.i(TAG, "WakeLock released")
        }
        wakeLock = null
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
