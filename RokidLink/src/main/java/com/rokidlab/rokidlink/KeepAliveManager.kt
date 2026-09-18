package com.rokidlab.rokidlink

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import android.util.Log

/**
 * KeyButtonService 的保活管家（v3.9 拆分自 KeyButtonService）。
 *
 * 职责：
 *  1. 前台服务：startForeground + 被拒后延迟重试（与 BtTunnelService 同策略）
 *  2. PARTIAL_WAKE_LOCK 长期持有 + 答题屏幕保亮锁（quiz 屏幕锁在 KeyRouteCoordinator）
 *  3. 30s 心跳：按键 receiver 丢失重注册、WakeLock 掉了重取、TTS 绑定掉了重绑
 *  4. SCREEN_ON 广播：发现 receiver 失活时重新注册
 *  5. 蓝牙运行时权限缺失时一次性拉起 MainActivity 申请
 *  6. 崩溃/异常销毁自愈：短命销毁计数持久化 + 延迟重试拉起
 */
internal class KeepAliveManager(
    private val service: KeyButtonService,
    private val core: KeyServiceCore,
    private val keyRoutes: KeyRouteCoordinator,
) {
    companion object {
        private const val TAG = KeyButtonService.TAG
        /** 短命销毁计数持久化键：服务启动后短时间内反复被销毁时累加 */
        private const val KEY_SHORT_LIVED_DESTROY_COUNT = "short_lived_destroy_count"
        /** 判定「短命」的存活时长阈值：低于该值被销毁视为启动即失败 */
        private const val SHORT_LIVED_THRESHOLD_MS = 60_000L
        /** 连续短命销毁达此次数后放弃自愈，避免无限重启循环耗尽系统资源 */
        private const val MAX_SHORT_LIVED_DESTROY = 5
        /** startForeground 被拒后的重试上限与间隔（与 BtTunnelService 同策略） */
        private const val MAX_FOREGROUND_RETRIES = 5
        private const val FOREGROUND_RETRY_INTERVAL_MS = 5_000L
    }

    /** PARTIAL_WAKE_LOCK — 防止 CPU 深度休眠导致广播投递失败 */
    private var wakeLock: PowerManager.WakeLock? = null

    /** 前台状态重试计数（转前台被拒后延迟重试） */
    private var foregroundRetryCount = 0

    /** 本进程存活期内是否已为蓝牙权限弹过页（拒绝后不反复打断） */
    @Volatile
    private var btPermissionPrompted = false

    /** SCREEN_ON 接收器注册态（SCREEN_ON receiver 的生命周期归本类） */
    private var screenOnReceiverRegistered = false

    /** 本实例启动时刻，用于判定「启动后短命被销毁」（自愈重启循环防护） */
    var serviceStartMs = 0L
        private set

    // ──────────────────────────────────────────────
    //  前台服务
    // ──────────────────────────────────────────────

    // setPriority/PRIORITY_MIN 仅对 Android 8 以下生效（O 起优先级由 NotificationChannel 决定），
    // 但 O 以下设备仍需这两个已弃用 API 才能把通知降到最低，无法用新 API 替代。
    @Suppress("DEPRECATION")
    fun startForegroundService() {
        val channelId = "key_button_service"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "按键映射服务",
                NotificationManager.IMPORTANCE_MIN
            ).apply { setShowBadge(false) }
            (service.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
        val notification = Notification.Builder(service,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) channelId else null
        ).apply {
            setContentTitle("按键映射服务")
            setContentText("监听眼镜功能键以启动应用")
            setSmallIcon(android.R.drawable.ic_menu_manage)
            setPriority(Notification.PRIORITY_MIN)
        }.build()
        // ★ startForeground 必须兜异常：它在 onCreate 主线程裸调用，一旦抛异常就是
        //   未捕获异常 → 进程立刻 FATAL EXCEPTION 崩溃。已知会抛的场景：
        //   ① Android 14+ 对 foregroundServiceType="connectedDevice" 强制校验
        //      BLUETOOTH_CONNECT 运行时权限，未授权 → SecurityException；
        //   ② 后台启动受限 → ForegroundServiceStartNotAllowedException；
        //   ③ 通知渠道/类型不合法 → InvalidForegroundServiceTypeException。
        //   BtTunnelService.startForegroundSafe 早已是这套写法，此处对齐。
        //   降级策略：捕获后延迟重试转前台；重试期间服务仍是普通后台服务，
        //   按键/隧道/ASR 照常工作，绝不会因「转前台失败」把整个进程带走。
        try {
            service.startForeground(1, notification)
            foregroundRetryCount = 0
            Log.i(TAG, "Foreground service started")
        } catch (e: Throwable) {
            Log.e(TAG, "startForeground failed: ${e::class.simpleName}: ${e.message}")
            retryStartForeground(notification)
        }
    }

    /**
     * startForeground 被拒后的延迟重试（每 5s，最多 5 次）。
     * 与 BtTunnelService 同策略：不因为「暂时转不了前台」而崩进程或反复重启。
     */
    private fun retryStartForeground(notification: android.app.Notification) {
        if (foregroundRetryCount >= MAX_FOREGROUND_RETRIES) {
            Log.w(TAG, "Give up startForeground retry after $MAX_FOREGROUND_RETRIES attempts")
            return
        }
        foregroundRetryCount++
        core.mainHandler.postDelayed({
            try {
                service.startForeground(1, notification)
                foregroundRetryCount = 0
                Log.i(TAG, "Foreground service started on retry #$foregroundRetryCount")
            } catch (e: Throwable) {
                Log.w(TAG, "startForeground retry #$foregroundRetryCount failed: ${e.message}")
                retryStartForeground(notification)
            }
        }, FOREGROUND_RETRY_INTERVAL_MS)
    }

    // ──────────────────────────────────────────────
    //  运行时权限兜底
    // ──────────────────────────────────────────────

    /**
     * BLUETOOTH_CONNECT 缺失时（典型：CXR-L「重装眼镜端」覆盖安装后运行时授权被清空）
     * 一次性拉起 [MainActivity] 发起系统权限请求。
     *
     * 不使用常驻透明 Activity 承载：它会抢占眼镜顶层 resumed 身份导致官方控制失灵。
     * MainActivity 申请完权限后用户自行返回即可；正常情况下手机端会在授权流程中
     * 经 ADB `pm grant` 直接下发，根本走不到这里。
     */
    fun ensureBluetoothPermissionOrPrompt() {
        if (btPermissionPrompted) return
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) return
        val granted = service.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) return
        btPermissionPrompted = true
        Log.w(TAG, "BLUETOOTH_CONNECT missing, launching MainActivity for runtime permission")
        runCatching {
            service.startActivity(
                Intent(service, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure { Log.e(TAG, "launch MainActivity for BT permission failed: ${it.message}") }
    }

    // ──────────────────────────────────────────────
    //  SCREEN_ON 接收器 + WakeLock + 心跳
    // ──────────────────────────────────────────────

    /** 屏幕亮起广播 — 发现 receiver 失活时重新注册 */
    private val screenOnReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.i(TAG, "SCREEN_ON — verifying key receiver")
            if (!keyRoutes.receiverRegistered) {
                Log.w(TAG, "Key receiver lost, re-registering")
                keyRoutes.registerKeyReceiver()
            }
        }
    }

    fun registerScreenOnReceiver() {
        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
            }
            // Android 13+ 动态注册必须显式指定接收标志。此处只监听系统广播
            // ACTION_SCREEN_ON，无需接收其他应用发来的 Intent，故声明 NOT_EXPORTED。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                service.registerReceiver(screenOnReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                service.registerReceiver(screenOnReceiver, filter)
            }
            screenOnReceiverRegistered = true
            Log.i(TAG, "Screen-on receiver registered")
        } catch (e: Exception) {
            Log.e(TAG, "registerScreenOnReceiver failed", e)
        }
    }

    fun unregisterScreenOnReceiver() {
        if (screenOnReceiverRegistered) {
            runCatching { service.unregisterReceiver(screenOnReceiver) }
            screenOnReceiverRegistered = false
        }
    }

    /**
     * 获取 PARTIAL_WAKE_LOCK 长期持有（无超时）：只要服务存活就保持 CPU 唤醒，
     * 确保蓝牙隧道/ASR/按键随时可达。
     * 之前用超时获取 + 心跳续期，存在"到期释放→心跳补锁"的空窗：设备在空窗内
     * 休眠后蓝牙 RFCOMM 全断、手机端所有功能连接失败（实测 16:02 眼镜 suspend 后全断）。
     * onDestroy 时释放。
     */
    fun acquireWakeLock() {
        try {
            val pm = service.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "KeyButtonService::WakeLock"
            ).apply {
                setReferenceCounted(false)
                // 长期持有：防止 CPU 深度休眠导致广播投递失败、蓝牙通道挂起
                acquire()
            }
            Log.i(TAG, "WakeLock acquired (held indefinitely)")
        } catch (e: Exception) {
            Log.e(TAG, "acquireWakeLock failed", e)
        }
    }

    /** 每 30 秒自检一次：key receiver 丢了就重新注册，TTS 绑定丢了就重绑，WakeLock 超时了就续期 */
    fun startHeartbeat() {
        core.mainHandler.postDelayed(object : Runnable {
            override fun run() {
                if (!keyRoutes.receiverRegistered) {
                    Log.w(TAG, "Heartbeat: key receiver lost, re-registering")
                    keyRoutes.registerKeyReceiver()
                }
                // WakeLock 超时自动释放后重新获取（仅当 Service 仍持有引用时）
                if (wakeLock?.isHeld != true) {
                    Log.w(TAG, "Heartbeat: WakeLock released, re-acquiring")
                    acquireWakeLock()
                }
                // TTS 服务掉线自愈（ensureBound 内部判断已绑定则跳过）
                TtsPlaybackHelper.ensureBound(service)
                core.mainHandler.postDelayed(this, 30_000L)
            }
        }, 30_000L)
    }

    // ──────────────────────────────────────────────
    //  销毁与自愈重启
    // ──────────────────────────────────────────────

    fun releaseWakeLock() {
        runCatching {
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
        }
    }

    /**
     * 服务销毁时的自愈判定：短命销毁计数（持久化）+ 延迟重试拉起。
     *
     * ⚠️ 短命销毁防护：若服务启动后 <60s 就被销毁，说明是「启动即失败」（典型如 bridge
     * JNI 初始化崩溃），此时反复重启只会形成无限循环、持续空转耗尽电量与系统资源。
     * 计数必须用 SharedPreferences 持久化——重试计数是方法局部的，每轮 onDestroy 都会
     * 从 0 重新开始，永远触发不到上限。连续 5 次短命销毁后放弃自愈，等待用户手动拉起。
     */
    fun handleDestroyed() {
        val aliveMs = System.currentTimeMillis() - serviceStartMs
        val prefs = service.getSharedPreferences(KeyButtonService.PREFS_NAME, 0)
        val shortLivedCount = if (aliveMs < SHORT_LIVED_THRESHOLD_MS) {
            val next = prefs.getInt(KEY_SHORT_LIVED_DESTROY_COUNT, 0) + 1
            prefs.edit().putInt(KEY_SHORT_LIVED_DESTROY_COUNT, next).apply()
            next
        } else {
            // 存活超过阈值视为一次正常运行，重新开始计数
            prefs.edit().putInt(KEY_SHORT_LIVED_DESTROY_COUNT, 0).apply()
            0
        }
        if (shortLivedCount >= MAX_SHORT_LIVED_DESTROY) {
            Log.e(
                TAG,
                "Give up self-healing: destroyed $shortLivedCount times with each alive " +
                    "<${SHORT_LIVED_THRESHOLD_MS}ms (last ${aliveMs}ms)"
            )
        } else {
            retryRestartSelf(0)
        }
    }

    /** 自愈重试：最多尝试 10 次，每次间隔 5 秒（后台 FGS 启动受限时等待系统放行） */
    private fun retryRestartSelf(attempt: Int) {
        if (attempt >= 10) {
            Log.e(TAG, "Give up restarting after $attempt attempts")
            return
        }
        core.mainHandler.postDelayed({
            if (isServiceRunning(KeyButtonService::class.java)) {
                Log.i(TAG, "Service running again, no restart needed")
                return@postDelayed
            }
            Log.w(TAG, "KeyButtonService not running after destroy, restarting (attempt ${attempt + 1})")
            try {
                KeyButtonService.start(service)
                // 同时确保蓝牙隧道服务（ADB 通道依赖它）也恢复
                runCatching { BtTunnelService.start(service) }
                    .onFailure { Log.e(TAG, "Failed to restart BtTunnelService", it) }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to restart KeyButtonService after destroy", e)
            }
            retryRestartSelf(attempt + 1)
        }, 5_000L)
    }

    /** 检查本应用服务是否在运行 */
    private fun isServiceRunning(clazz: Class<*>): Boolean {
        return try {
            val am = service.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            am.getRunningServices(100).any {
                it.service.packageName == service.packageName && it.service.className == clazz.name
            }
        } catch (e: Exception) {
            Log.w(TAG, "isServiceRunning failed", e)
            false
        }
    }

    fun markStart() {
        serviceStartMs = System.currentTimeMillis()
    }
}
