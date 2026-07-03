package com.rokidlab.rokidlink

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import com.rokid.cxr.CXRServiceBridge
import com.rokid.cxr.Caps

/**
 * 眼镜端按键映射常驻后台服务。
 *
 * 职责：
 * 1. 作为 Foreground Service（START_STICKY）保持进程常驻
 * 2. 动态注册按键广播接收器（priority=100 + abortBroadcast）
 * 3. 收到按键事件后，直接启动目标应用
 *    - 如果 KeyButtonBridgeActivity 存活 → 它也会处理（双重保障）
 *    - 如果 BridgeActivity 被销毁 → Service 直接处理
 * 4. 通过 CXR-S SDK 订阅手机端下发的按键配置
 * 5. 启动常驻 KeyButtonBridgeActivity（透明，用于保持进程 VISIBLE 状态）
 * 6. 持有 PARTIAL_WAKE_LOCK 防止休眠后按键失效
 * 7. 监听 SCREEN_ON 广播，屏幕亮起时校验 receiver 存活状态
 */
class KeyButtonService : Service() {
    private var bridge: CXRServiceBridge? = null
    private var bridgeActivityRunning = false

    /** 用于定期校验按键 receiver 仍存活 */
    private val handler = Handler(Looper.getMainLooper())
    private var receiverRegistered = false

    /** PARTIAL_WAKE_LOCK — 防止 CPU 深度休眠导致广播投递失败 */
    private var wakeLock: PowerManager.WakeLock? = null

    /** 屏幕亮起广播 — 发现 receiver 失活时重新注册 */
    private val screenOnReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.i(TAG, "SCREEN_ON — verifying key receiver")
            if (!receiverRegistered) {
                Log.w(TAG, "Key receiver lost, re-registering")
                registerKeyReceiver()
            }
        }
    }

    /** 按键广播接收器 */
    private val keyReceiver = object : BroadcastReceiver() {
        private var lastLaunchMs = 0L

        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action ?: return

            when (action) {
                "com.android.action.ACTION_SPRITE_BUTTON_DOWN" -> {
                    downTimeMs = System.currentTimeMillis()
                }
                "com.android.action.ACTION_SPRITE_BUTTON_UP" -> {
                    val down = downTimeMs
                    downTimeMs = 0L
                    if (down <= 0) return
                    val elapsed = System.currentTimeMillis() - down
                    Log.i(TAG, "UP elapsed=${elapsed}ms")
                    launchTargetByDuration(context, elapsed)
                }
                "com.android.action.ACTION_SPRITE_BUTTON_CLICK" -> {
                    Log.i(TAG, "CLICK → SHORT")
                    launchConfiguredTarget(context, isLong = false)
                }
                "com.android.action.ACTION_SPRITE_BUTTON_LONG_PRESS" -> {
                    Log.i(TAG, "LONG_PRESS → LONG")
                    downTimeMs = 0L  // clear pending DOWN so UP won't double-trigger
                    launchConfiguredTarget(context, isLong = true)
                }
            }
        }

        private fun isDuplicateLaunch(): Boolean {
            val now = System.currentTimeMillis()
            if ((now - lastLaunchMs) < 800L) return true
            lastLaunchMs = now
            return false
        }

        /** 根据 UP 事件时长判断短按/长按并启动目标 */
        private fun launchTargetByDuration(context: Context, elapsedMs: Long) {
            val prefs = context.getSharedPreferences(PREFS_NAME, 0)
            val pkg = prefs.getString(
                if (elapsedMs >= 500L) KEY_LONG_PKG else KEY_SHORT_PKG, ""
            ) ?: ""
            val act = prefs.getString(
                if (elapsedMs >= 500L) KEY_LONG_ACT else KEY_SHORT_ACT, ".MainActivity"
            ) ?: ".MainActivity"

            if (pkg.isBlank()) {
                Log.w(TAG, "No target for ${if (elapsedMs >= 500L) "LONG" else "SHORT"} (elapsed=${elapsedMs}ms)")
                return
            }
            if (isDuplicateLaunch()) return
            abortBroadcast()
            launchTarget(context, pkg, act)
        }

        /** 直接启动配置的短按/长按目标（用于 CLICK / LONG_PRESS 广播） */
        private fun launchConfiguredTarget(context: Context, isLong: Boolean) {
            val prefs = context.getSharedPreferences(PREFS_NAME, 0)
            val pkg = prefs.getString(
                if (isLong) KEY_LONG_PKG else KEY_SHORT_PKG, ""
            ) ?: ""
            val act = prefs.getString(
                if (isLong) KEY_LONG_ACT else KEY_SHORT_ACT, ".MainActivity"
            ) ?: ".MainActivity"

            downTimeMs = 0L
            if (pkg.isBlank()) {
                Log.w(TAG, "No configured target for ${if (isLong) "LONG" else "SHORT"}")
                return
            }
            if (isDuplicateLaunch()) return
            abortBroadcast()
            launchTarget(context, pkg, act)
        }
    }

    /** 从 Service 直接启动目标应用 */
    private fun launchTarget(context: Context, pkg: String, activity: String) {
        val fullAct = if (activity.startsWith(".")) "$pkg$activity" else activity
        try {
            // 1. 优先使用系统 launch intent
            val launchIntent = context.packageManager.getLaunchIntentForPackage(pkg)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                context.startActivity(launchIntent)
                Log.i(TAG, "Service launched: $pkg (launchIntent)")
                return
            }

            // 2. 尝试显式 activity
            try {
                Intent(Intent.ACTION_MAIN).apply {
                    component = android.content.ComponentName(pkg, fullAct)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }.also { context.startActivity(it) }
                Log.i(TAG, "Service launched: $pkg/$fullAct (explicit)")
                return
            } catch (_: ActivityNotFoundException) {
                // 3. 显式失败 → 自动查询包的实际 launcher activity
            }

            // 4. 用 getPackageInfo 查包的所有 activity，找第一个可导出的
            try {
                val pkgInfo = context.packageManager.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES)
                val firstActivity = pkgInfo.activities?.firstOrNull { it.exported }?.name
                if (firstActivity != null) {
                    Intent(Intent.ACTION_MAIN).apply {
                        component = android.content.ComponentName(pkg, firstActivity)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(this)
                    }
                    Log.i(TAG, "Service launched: $pkg/$firstActivity (auto)")
                    return
                } else {
                    Log.w(TAG, "No exported activity in manifest for $pkg")
                }
            } catch (e: Exception) {
                Log.w(TAG, "getPackageInfo failed for $pkg: ${e::class.simpleName}: ${e.message}")
            }

            // 5. 最终兜底：用 pm resolve-activity 命令行
            try {
                val cmd = "pm resolve-activity --brief $pkg"
                val process = Runtime.getRuntime().exec(cmd)
                val reader = java.io.BufferedReader(java.io.InputStreamReader(process.inputStream))
                val output = reader.readText().trim()
                process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
                if (output.isNotBlank() && !output.contains("Error") && !output.contains("No activity")) {
                    val lines = output.lines()
                    val actLine = lines.firstOrNull { it.startsWith(pkg) }
                    if (actLine != null) {
                        val activityName = actLine.removePrefix("$pkg/")
                        Intent(Intent.ACTION_MAIN).apply {
                            component = android.content.ComponentName(pkg, activityName)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(this)
                        }
                        Log.i(TAG, "Service launched: $pkg/$activityName (pm)")
                        return
                    }
                }
                Log.e(TAG, "pm resolve-activity gave no result for $pkg: $output")
            } catch (e: Exception) {
                Log.w(TAG, "pm resolve-activity failed for $pkg: ${e.message}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Service launch failed: ${e::class.simpleName}: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "KeyButtonService"
        internal const val PREFS_NAME = "key_button_config"
        internal const val KEY_SHORT_PKG = "short_pkg"
        internal const val KEY_SHORT_ACT = "short_act"
        internal const val KEY_LONG_PKG = "long_pkg"
        internal const val KEY_LONG_ACT = "long_act"
        internal const val TOPIC = "rokidlab_key_config"

        /** 按键按下时间戳 */
        @Volatile
        internal var downTimeMs: Long = 0L

        /** 启动此服务 */
        fun start(ctx: Context) {
            ctx.startService(Intent(ctx, KeyButtonService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Service creating")
        startForegroundService()
        // 同时启动常驻透明 Activity 和 Service 接收器（双重保障）
        startBridgeActivity()
        registerKeyReceiver()
        registerScreenOnReceiver()
        acquireWakeLock()
        startHeartbeat()
        initCxrBridge()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.i(TAG, "Service destroying")
        handler.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(keyReceiver) }
        receiverRegistered = false
        runCatching { unregisterReceiver(screenOnReceiver) }
        runCatching {
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
        }
        runCatching { bridge?.disconnectCXRDevice() }
        bridge = null
        super.onDestroy()
    }

    // ──────────────────────────────────────────────
    //  前台服务
    // ──────────────────────────────────────────────

    private fun startForegroundService() {
        val channelId = "key_button_service"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "按键映射服务",
                NotificationManager.IMPORTANCE_MIN
            ).apply { setShowBadge(false) }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }
        val notification = Notification.Builder(this,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) channelId else null
        ).apply {
            setContentTitle("按键映射服务")
            setContentText("监听眼镜功能键以启动应用")
            setSmallIcon(android.R.drawable.ic_menu_manage)
            setPriority(Notification.PRIORITY_MIN)
        }.build()
        startForeground(1, notification)
        Log.i(TAG, "Foreground service started")
    }

    // ──────────────────────────────────────────────
    //  启动常驻透明 Activity
    // ──────────────────────────────────────────────

    private fun startBridgeActivity() {
        try {
            val intent = Intent(this, KeyButtonBridgeActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            Log.i(TAG, "BridgeActivity started")
            bridgeActivityRunning = true
        } catch (e: Exception) {
            Log.w(TAG, "startBridgeActivity: ${e::class.simpleName}: ${e.message}")
            bridgeActivityRunning = false
        }
    }

    // ──────────────────────────────────────────────
    //  按键广播接收器
    // ──────────────────────────────────────────────

    private fun registerKeyReceiver() {
        try {
            val filter = IntentFilter().apply {
                addAction("com.android.action.ACTION_SPRITE_BUTTON_DOWN")
                addAction("com.android.action.ACTION_SPRITE_BUTTON_UP")
                addAction("com.android.action.ACTION_SPRITE_BUTTON_CLICK")
                addAction("com.android.action.ACTION_SPRITE_BUTTON_LONG_PRESS")
                priority = 100
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(keyReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(keyReceiver, filter)
            }
            Log.i(TAG, "Key receiver registered in Service")
            receiverRegistered = true
        } catch (e: Exception) {
            Log.e(TAG, "registerKeyReceiver failed", e)
            receiverRegistered = false
        }
    }

    // ──────────────────────────────────────────────
    //  SCREEN_ON 接收器 + WakeLock + 心跳
    // ──────────────────────────────────────────────

    private fun registerScreenOnReceiver() {
        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
            }
            registerReceiver(screenOnReceiver, filter)
            Log.i(TAG, "Screen-on receiver registered")
        } catch (e: Exception) {
            Log.e(TAG, "registerScreenOnReceiver failed", e)
        }
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "KeyButtonService::WakeLock"
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
            Log.i(TAG, "WakeLock acquired")
        } catch (e: Exception) {
            Log.e(TAG, "acquireWakeLock failed", e)
        }
    }

    /** 每 30 秒自检一次：key receiver 丢了就重新注册 */
    private fun startHeartbeat() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (!receiverRegistered) {
                    Log.w(TAG, "Heartbeat: key receiver lost, re-registering")
                    registerKeyReceiver()
                }
                handler.postDelayed(this, 30_000L)
            }
        }, 30_000L)
    }

    // ──────────────────────────────────────────────
    //  CXR-S SDK
    // ──────────────────────────────────────────────

    private fun initCxrBridge() {
        try {
            bridge = CXRServiceBridge()
            bridge?.setStatusListener(object : CXRServiceBridge.StatusListener {
                override fun onConnected(name: String, address: String, type: Int) {
                    Log.i(TAG, "CXR connected: name=$name, address=$address, type=$type")
                }
                override fun onDisconnected() {
                    Log.i(TAG, "CXR disconnected")
                }
                override fun onConnecting(name: String, address: String, type: Int) {
                    Log.i(TAG, "CXR connecting: name=$name, address=$address, type=$type")
                }
                override fun onARTCStatus(health: Float, reset: Boolean) {}
                override fun onRokidAccountChanged(account: String) {
                    Log.i("CXRServiceBridge", "Rokid account changed: $account")
                }
            })

            val result = bridge?.subscribe(TOPIC, CXRServiceBridge.MsgCallback { _, args, _ ->
                handleConfig(args)
            })
            Log.i(TAG, "subscribe($TOPIC) -> $result")
        } catch (e: Exception) {
            Log.e(TAG, "initCxrBridge failed", e)
        }
    }

    private fun handleConfig(args: Caps) {
        try {
            if (args.size() < 5) {
                Log.w(TAG, "Invalid config size: ${args.size()}")
                return
            }
            val action = args.at(0).getString()
            if (action != "key_config") return

            getSharedPreferences(PREFS_NAME, 0).edit()
                .putString(KEY_SHORT_PKG, args.at(1).getString())
                .putString(KEY_SHORT_ACT, args.at(2).getString())
                .putString(KEY_LONG_PKG, args.at(3).getString())
                .putString(KEY_LONG_ACT, args.at(4).getString())
                .apply()

            Log.i(TAG, "Config saved: short=${args.at(1).getString()}/${args.at(2).getString()}, " +
                  "long=${args.at(3).getString()}/${args.at(4).getString()}")
        } catch (e: Exception) {
            Log.e(TAG, "handleConfig error", e)
        }
    }
}
