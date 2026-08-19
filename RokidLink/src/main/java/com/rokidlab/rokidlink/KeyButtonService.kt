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
import android.provider.Settings
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.net.NetworkCapabilities
import android.net.wifi.WifiNetworkSpecifier
import com.rokid.cxr.CXRServiceBridge
import com.rokid.cxr.Caps
import java.io.File

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

    /** 显示状态页指令防抖：手机端指令可能广播式重复到达，500ms 内只响应一次 */
    private var lastShowMainMs = 0L

    /** 唤醒词+语音的 ASR 流式文字（覆盖式累积，ASR_End 时取最后一条） */
    private var pendingAiText: String? = null

    /** 本地接管执行器：ASR_End 后的 openAiSession/showAiUserText 含多次 sleep，
     *  连续提问时若每次都 new Thread 会并发执行导致指令交错，必须串行化 */
    private val takeoverExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

    /**
     * 下行过滤：手机端 Lab 回复时下行序列为 Exit→KeyDown_Client→open→ASR_Result→ASR_End→TTS_Result。
     * 若把下行 ASR_End 误当官方 ASR 处理（写文件+打断），会形成
     * 「写文件→手机读到→下行→误拦截→再写文件」的自反馈死循环。
     * 收到下行标志（KeyDown_Client/open）后，窗口内到达的 ASR 一律视为 Lab 重发，忽略。
     */
    @Volatile
    private var downlinkUntilMs = 0L

    /**
     * 打断次数限制：滑动窗口内打断/写文件次数达到上限后暂停拦截，让官方自然完成回复
     * （打破任何异常循环），窗口滚动后自动恢复。比固定冷却时间更可控。
     */
    @Volatile
    private var lastInterruptMs = 0L
    @Volatile
    private var interruptCount = 0

    /** 用于定期校验按键 receiver 仍存活 */
    private val handler = Handler(Looper.getMainLooper())
    private val mainHandler get() = handler
    private var receiverRegistered = false

    /** 拍照答题去重：一次短按会产生 UP/CLICK 两条广播，1 秒内只上行一次 */
    private var lastPhotoAskMs = 0L

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
            // 短按且「按键答题」开启 → 触发拍照问AI（覆盖原短按启动应用）
            if (elapsedMs < 500L && isKeyQuizEnabled(context)) {
                Log.i(TAG, "Quiz mode: SHORT(DOWN/UP) → photo ask")
                downTimeMs = 0L
                abortBroadcast()
                sendPhotoAskToPhone()
                return
            }
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
            // 短按且「按键答题」开启 → 触发拍照问AI（覆盖原短按启动应用，长按不受影响）
            if (!isLong && isKeyQuizEnabled(context)) {
                Log.i(TAG, "Quiz mode: SHORT → photo ask")
                downTimeMs = 0L
                abortBroadcast()
                sendPhotoAskToPhone()
                return
            }
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

        /** 「按键答题」开关是否开启（从手机端下发并持久化） */
        private fun isKeyQuizEnabled(context: Context): Boolean =
            KeyButtonService.isKeyQuizEnabled(context)
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
        /** 下行过滤窗口：收到 Lab 下行标志后，窗口内 ASR 视为重发忽略。
         *  仅 KeyDown_Client 触发（Lab 完整下行序列 KeyDown_Client→open→ASR_Result→ASR_End 约 1s）。
         *  手机端打断官方用的「Ai/open」（interruptOfficialAi）不再触发过滤，避免误吞用户真实提问。 */
        private const val DOWNLINK_FILTER_MS = 2_000L
        /** 打断次数限制滑动窗口与上限 */
        private const val INTERRUPT_WINDOW_MS = 20_000L
        private const val INTERRUPT_MAX = 3
        /** WakeLock 持有超时：到期自动释放，由心跳续期（避免永久持有阻止 CPU 深度休眠） */
        private const val WAKE_LOCK_TIMEOUT_MS = 10 * 60_000L
        /** ASR 文字 logcat 输出 tag：手机端经 ADB（蓝牙隧道）读取（CXR 上行被 AI App 过滤，改用 logcat） */
        internal const val AI_ASR_BRIDGE_TAG = "AiAsrBridge"
        internal const val PREFS_NAME = "key_button_config"
        internal const val KEY_SHORT_PKG = "short_pkg"
        internal const val KEY_SHORT_ACT = "short_act"
        internal const val KEY_LONG_PKG = "long_pkg"
        internal const val KEY_LONG_ACT = "long_act"
        internal const val TOPIC = "rokidlab_key_config"
        internal const val WIFI_TOPIC = "wifi_config"
        internal const val TTS_TOPIC = "tts_play"
        /** 「按键答题」开关下发通道（手机端 → 眼镜端） */
        internal const val QUIZ_TOPIC = "rokidlab_key_quiz"
        /** 拍照问AI 指令上行通道（眼镜端 → 手机端） */
        internal const val PHOTO_ASK_TOPIC = "rokidlab_photo_ask"
        /** 语音转文字结果上行通道（眼镜端 → 手机端）：唤醒词+语音的 ASR 文字转给 Lab 回复 */
        internal const val AI_ASR_TOPIC = "rokidlab_ai_asr"
        /**
         * AI 文字轮询通道（手机端 → 眼镜端）：RokidLab 定时 sendCustomCmd 轮询，
         * 眼镜端可回复订阅返回 ASR 文字。采用请求-响应机制以绕过 AI App 对未知上行指令的过滤。
         */
        internal const val AI_ASR_POLL_TOPIC = "rokidlab_ai_asr_poll"
        /** AI 频道（手机端 → 眼镜端，AssistServer 全局订阅） */
        internal const val AI_TOPIC = "Ai"
        /** 显示状态页指令通道（手机端 → 眼镜端）：用户点「打开 RokidLink」后，MainActivity 带 EXTRA_SHOW_UI 显示 IP 状态页 */
        internal const val SHOW_MAIN_TOPIC = "rokidlab_show_main"
        /** AI 配置下发通道（手机端 → 眼镜端）：baseUrl/apiKey/model，供眼镜端直接调用模型 */
        internal const val AI_CONFIG_TOPIC = "rokidlab_ai_config"
        /** AI 配置持久化 key */
        internal const val KEY_AI_BASE_URL = "ai_base_url"
        internal const val KEY_AI_API_KEY = "ai_api_key"
        internal const val KEY_AI_MODEL = "ai_model"
        /** 对话模型模式持久化 key：custom = Lab 拦截回复；official = 官方乐奇 */
        internal const val KEY_AI_MODE = "ai_mode"
        internal const val AI_MODE_OFFICIAL = "official"
        internal const val AI_MODE_CUSTOM = "custom"
        /** 「按键答题」开关存储 key */
        internal const val KEY_QUIZ_ENABLED = "key_quiz_enabled"
        /** KeyButtonBridgeActivity 触发拍照答题时通知 Service 的 action */
        internal const val ACTION_QUIZ_PHOTO_ASK = "rokidlab.action.QUIZ_PHOTO_ASK"

        /** 按键按下时间戳 */
        @Volatile
        internal var downTimeMs: Long = 0L

        /** 「按键答题」开关是否开启（供 BridgeActivity 与 Service 共用） */
        @JvmStatic
        fun isKeyQuizEnabled(ctx: Context): Boolean =
            ctx.getSharedPreferences(PREFS_NAME, 0).getBoolean(KEY_QUIZ_ENABLED, false)

        /** 启动此服务 */
        fun start(ctx: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(Intent(ctx, KeyButtonService::class.java))
            } else {
                ctx.startService(Intent(ctx, KeyButtonService::class.java))
            }
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
        // 预热绑定系统 TTS 服务，避免首次「拍照问 AI」回复要等异步绑定
        TtsPlaybackHelper.ensureBound(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // KeyButtonBridgeActivity 在 quiz 模式短按时通过 startService 通知本 Service 触发拍照答题
        if (intent?.action == ACTION_QUIZ_PHOTO_ASK) {
            Log.i(TAG, "Quiz photo ask triggered by BridgeActivity")
            sendPhotoAskToPhone()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.i(TAG, "Service destroying")
        handler.removeCallbacksAndMessages(null)
        // 停止本地接管执行器（openAiSession 等含 sleep 的任务不再继续）
        takeoverExecutor.shutdownNow()
        runCatching { unregisterReceiver(keyReceiver) }
        receiverRegistered = false
        runCatching { unregisterReceiver(screenOnReceiver) }
        runCatching {
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
        }
        runCatching { bridge?.disconnectCXRDevice() }
        bridge = null
        // 崩溃/异常销毁自愈：延迟检查，若服务未恢复则重新拉起。
        // START_STICKY 在 startRequested=false（服务被 stop）时不生效，需要主动重启。
        // 眼镜 ROM 在 app idle 时可能停服务，且后台 FGS 启动受限，因此多次重试直到成功。
        retryRestartSelf(0)
        super.onDestroy()
    }

    /** 自愈重试：最多尝试 10 次，每次间隔 5 秒（后台 FGS 启动受限时等待系统放行） */
    private fun retryRestartSelf(attempt: Int) {
        if (attempt >= 10) {
            Log.e(TAG, "Give up restarting after $attempt attempts")
            return
        }
        handler.postDelayed({
            if (isServiceRunning(KeyButtonService::class.java)) {
                Log.i(TAG, "Service running again, no restart needed")
                return@postDelayed
            }
            Log.w(TAG, "KeyButtonService not running after destroy, restarting (attempt ${attempt + 1})")
            try {
                startForegroundService(Intent(this, KeyButtonService::class.java))
                // 同时确保蓝牙隧道服务（ADB 通道依赖它）也恢复
                runCatching { BtTunnelService.start(this) }
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
            val am = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
            am.getRunningServices(100).any {
                it.service.packageName == packageName && it.service.className == clazz.name
            }
        } catch (e: Exception) {
            Log.w(TAG, "isServiceRunning failed", e)
            false
        }
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
                // 带超时获取：避免永久持有阻止 CPU 深度休眠，由心跳每 30s 续期
                acquire(WAKE_LOCK_TIMEOUT_MS)
            }
            Log.i(TAG, "WakeLock acquired (${WAKE_LOCK_TIMEOUT_MS / 1000}s)")
        } catch (e: Exception) {
            Log.e(TAG, "acquireWakeLock failed", e)
        }
    }

    /** 每 30 秒自检一次：key receiver 丢了就重新注册，TTS 绑定丢了就重绑，WakeLock 超时了就续期 */
    private fun startHeartbeat() {
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (!receiverRegistered) {
                    Log.w(TAG, "Heartbeat: key receiver lost, re-registering")
                    registerKeyReceiver()
                }
                // WakeLock 超时自动释放后重新获取（仅当 Service 仍持有引用时）
                if (wakeLock?.isHeld != true) {
                    Log.w(TAG, "Heartbeat: WakeLock released, re-acquiring")
                    acquireWakeLock()
                }
                // TTS 服务掉线自愈（ensureBound 内部判断已绑定则跳过）
                TtsPlaybackHelper.ensureBound(this@KeyButtonService)
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
                    Log.i(TAG, "CXR disconnected, will re-init in 3s")
                    // 断线自愈：cxr-service 重启/蓝牙闪断后重建桥接并重订阅，避免永久失联
                    mainHandler.postDelayed({
                        runCatching {
                            bridge = null
                            initCxrBridge()
                        }.onFailure { Log.e(TAG, "re-init CXR bridge failed", it) }
                    }, 3000)
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

            val wifiResult = bridge?.subscribe(WIFI_TOPIC, CXRServiceBridge.MsgCallback { _, args, _ ->
                handleWifiConfig(args)
            })
            Log.i(TAG, "subscribe($WIFI_TOPIC) -> $wifiResult")

            val ttsResult = bridge?.subscribe(TTS_TOPIC, CXRServiceBridge.MsgCallback { _, args, _ ->
                handleTtsPlay(args)
            })
            Log.i(TAG, "subscribe($TTS_TOPIC) -> $ttsResult")

            val quizResult = bridge?.subscribe(QUIZ_TOPIC, CXRServiceBridge.MsgCallback { _, args, _ ->
                handleQuizConfig(args)
            })
            Log.i(TAG, "subscribe($QUIZ_TOPIC) -> $quizResult")

            // 接收 Lab 下发的 AI 配置（baseUrl/apiKey/model），供眼镜端直接调用模型
            val aiCfgResult = bridge?.subscribe(AI_CONFIG_TOPIC, CXRServiceBridge.MsgCallback { _, args, _ ->
                handleAiConfig(args)
            })
            Log.i(TAG, "subscribe($AI_CONFIG_TOPIC) -> $aiCfgResult")

            // 订阅 "Ai" 频道：拦截官方 AI 链路的 ASR 文字，改用 Lab 模型回复。
            // 广播式路由，与 AssistServer 的订阅不冲突（已真机验证）。
            val aiResult = bridge?.subscribe(AI_TOPIC, CXRServiceBridge.MsgCallback { _, args, _ ->
                handleAiChannel(args)
            })
            Log.i(TAG, "subscribe($AI_TOPIC) -> $aiResult")
            // 订阅手机端轮询拉取通道（请求-响应，绕过 AI App 上行过滤）
            subscribeAiAsrPoll()
            // 订阅显示状态页指令：手机端「打开 RokidLink」时带 EXTRA_SHOW_UI 显示 IP 状态页
            val showMainResult = bridge?.subscribe(SHOW_MAIN_TOPIC, CXRServiceBridge.MsgCallback { _, _, _ ->
                showMainActivity()
            })
            Log.i(TAG, "subscribe($SHOW_MAIN_TOPIC) -> $showMainResult")
        } catch (e: Exception) {
            Log.e(TAG, "initCxrBridge failed", e)
        }
    }

    /**
     * 处理 "Ai" 频道消息（官方 AI 链路，手机端 → 眼镜端）。
     *
     * 流程：
     *   ASR_Result（流式文字）→ 覆盖式累积
     *   ASR_End → 上行 Exit 关闭官方会话（停止乐奇显示/播报）+ 上行文字给手机 Lab 回复
     */
    private fun handleAiChannel(args: Caps) {
        try {
            if (args == null || args.size() < 1 || args.at(0) == null) return
            val cmd = args.at(0).getString() ?: return
            when (cmd) {
                "ASR_Result" -> {
                    // 下行过滤：Lab 回复下行序列中的 ASR_Result 视为重发，忽略
                    if (System.currentTimeMillis() < downlinkUntilMs) {
                        Log.d(TAG, "AI ASR_Result ignored (downlink)")
                        return
                    }
                    if (args.size() > 1 && args.at(1) != null &&
                        args.at(1).type() == Caps.Value.TYPE_STRING
                    ) {
                        pendingAiText = args.at(1).getString()
                        Log.i(TAG, "AI ASR stream: ${pendingAiText?.take(40)}")
                    }
                }
                "ASR_End" -> {
                    // 下行过滤：Lab 回复下行序列中的 ASR_End 视为重发，忽略
                    if (System.currentTimeMillis() < downlinkUntilMs) {
                        Log.d(TAG, "AI ASR_End ignored (downlink)")
                        return
                    }
                    // 官方模式：放行官方乐奇，不拦截不写文件
                    if (!isCustomAiMode()) {
                        Log.d(TAG, "AI ASR_End pass-through (official mode)")
                        return
                    }
                    val finalText = pendingAiText?.trim().orEmpty()
                    pendingAiText = null
                    // 空文本不消耗打断次数（先把提取/判空前置）
                    if (finalText.isBlank()) {
                        Log.w(TAG, "AI ASR_End with empty text")
                        return
                    }
                    // 打断次数限制：窗口内达到上限则暂停拦截，让官方自然完成回复（打破循环）
                    if (!allowInterrupt()) {
                        Log.w(TAG, "AI ASR_End ignored (interrupt limit reached)")
                        return
                    }
                    Log.i(TAG, "AI ASR complete: $finalText")
                    // 官方 ASR_End 后约 1 秒内即开始 TTS 播报（离线问候语），
                    // 手机端 ADB 极速轮询也需 ~0.5s+。先尝试眼镜端本地立即打断（实测返回码）。
                    interruptOfficialLocally()
                    // 本地接管显示：官方会话被打断后界面会残留"思考中"等待（约 3s 直到手机端轮询+下行重开会话）。
                    // 这里立即本地重开会话并显示提问，官方界面立刻切到 Lab 会话等待（第二次思考中，可接受），
                    // 手机端读到文字后只需下行 DeepSeek 回复（TTS_Result），不再重发会话序列。
                    // 串行执行器：连续提问时避免多个接管任务并发导致指令交错
                    takeoverExecutor.execute {
                        try {
                            openAiSession()
                            showAiUserText(finalText)
                        } catch (e: Exception) {
                            Log.e(TAG, "local takeover error", e)
                        }
                    }
                    // ASR 文字双通道暴露：
                    //   1) logcat（AiAsrBridge tag）——诊断用
                    //   2) RFCOMM 推送通道（AsrPushServer 长连接，毫秒级）——主通道
                    //   3) 文件（app 私有外部目录）——推送失败时的兜底（手机端轮询读取）
                    Log.i(AI_ASR_BRIDGE_TAG, "ASR_TEXT:$finalText")
                    if (!AsrPushServer.push(finalText)) {
                        Log.w(TAG, "ASR push channel unavailable, fallback to file")
                        appendAiAsrToFile(finalText)
                    }
                }
                "TTS_Result", "TTS_AudioFinished", "Ai_Heartbeat" -> {
                    // 官方乐奇回复/心跳：忽略（界面即将被 Exit 关闭）
                }
                "KeyDown_Client" -> {
                    // Lab 回复完整下行序列（KeyDown_Client→open→ASR_Result→ASR_End）的标志：
                    // 开启下行过滤窗口，过滤其中的重发 ASR_Result/ASR_End。
                    // 注意：单独的「Ai/open」（手机端 interruptOfficialAi 打断官方）不触发过滤，
                    // 否则会误吞用户紧随其后的真实提问（open 后 1~2s 官方 ASR_End 到达）。
                    downlinkUntilMs = System.currentTimeMillis() + DOWNLINK_FILTER_MS
                    Log.d(TAG, "Downlink flag: $cmd, filter until ${downlinkUntilMs}")
                }
                else -> Log.d(TAG, "AI channel ignored: $cmd")
            }
        } catch (e: Exception) {
            Log.e(TAG, "handleAiChannel error", e)
        }
    }

    /**
     * 打断次数控制：滑动窗口（INTERRUPT_WINDOW_MS）内拦截/打断次数达到 INTERRUPT_MAX 后
     * 拒绝继续拦截（让官方自然完成回复，打破任何异常循环）；窗口滚动后自动清零恢复。
     */
    private fun allowInterrupt(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastInterruptMs > INTERRUPT_WINDOW_MS) {
            interruptCount = 0
        }
        lastInterruptMs = now
        interruptCount++
        return interruptCount <= INTERRUPT_MAX
    }

    /**
     * 将 ASR 文字追加写入 app 私有外部目录文件（ai_asr.log），
     * 供手机端经 ADB（蓝牙隧道）轮询读取。logcat 缓冲会被高频系统日志数秒内冲掉，
     * 必须落盘才能保证手机端可靠读到。每行格式：[epochMs] text
     * 在后台线程执行：该函数由 CXR 订阅回调线程触发，全量读写文件会阻塞回调链路。
     */
    private fun appendAiAsrToFile(text: String) {
        Thread {
            try {
                val dir = getExternalFilesDir(null) ?: return@Thread
                val f = File(dir, "ai_asr.log")
                // 截断防膨胀：超过 64KB 时只保留最近 5 行（蓝牙传输带宽有限，避免每次 cat 过慢）
                if (f.exists() && f.length() > 64 * 1024) {
                    val last = f.readLines().takeLast(5)
                    f.writeText("")
                    if (last.isNotEmpty()) f.appendText(last.joinToString("\n") + "\n")
                }
                f.appendText("[${System.currentTimeMillis()}] $text\n")
            } catch (e: Exception) {
                Log.e(TAG, "appendAiAsrToFile error", e)
            }
        }.apply { name = "ai-asr-file"; start() }
    }

    /**
     * 眼镜端本地立即打断官方 AI（实测诊断，尽量抢在官方 TTS 播报前）：
     * 1) bridge.sendMessage(Ai/Exit)：CXR 上行到手机，cxr-service 可能拒绝（-1）
     * 2) Runtime.exec 双击广播：protected 广播，第三方 uid 大概率被拒
     * 即使两条都被拒，手机端 ADB 极速轮询也会在官方 TTS 前补刀。
     */
    private fun interruptOfficialLocally() {
        // 1) 尝试 CXR 上行 Ai/Exit（0ms 起，300ms 重试一次）
        val r1 = sendAi("Exit")
        Log.i(TAG, "interruptOfficialLocally: sendAi(Exit) -> $r1")
        if (r1 != 0) {
            mainHandler.postDelayed({
                val r2 = sendAi("Exit")
                Log.i(TAG, "interruptOfficialLocally: sendAi(Exit) retry -> $r2")
            }, 300)
        }
        // 2) 尝试系统双击广播（应用进程内执行，同 adb shell 权限受限）
        try {
            // shell 内用 2>&1 合并 stderr 到 stdout，只读单流：先读 stdout 再读 stderr 可能因管道缓冲占满而死锁
            val p = Runtime.getRuntime().exec(arrayOf(
                "sh", "-c",
                "am broadcast -a com.android.action.ACTION_SPRITE_BUTTON_DOUBLE_CLICK 2>&1"
            ))
            val out = p.inputStream.bufferedReader().use { it.readText() }
            Log.i(TAG, "interruptOfficialLocally: am broadcast -> ${out.trim().take(120)}")
        } catch (e: Exception) {
            Log.e(TAG, "interruptOfficialLocally am error", e)
        }
    }

    /** 发送 Ai 频道指令（caps[0] = 命令，后续为参数） */
    private fun sendAi(cmd: String, vararg values: String): Int {
        val b = bridge ?: return -1
        return try {
            val caps = Caps()
            caps.write(cmd)
            values.forEach { caps.write(it) }
            b.sendMessage(AI_TOPIC, caps)
        } catch (e: Exception) {
            Log.e(TAG, "sendAi($cmd) error", e)
            -1
        }
    }

    /** 打开 AI 对话界面：KeyDown_Client(privacy_level=2) → open（手机端已验证该序列可打开 ai_assist 场景） */
    private fun openAiSession() {
        val r1 = sendAi("KeyDown_Client", "{\"privacy_level\":2}")
        Log.i(TAG, "openAiSession KeyDown_Client -> $r1")
        Thread.sleep(1200)
        val r2 = sendAi("open")
        Log.i(TAG, "openAiSession open -> $r2")
        Thread.sleep(800)
    }

    /** 在官方聊天界面显示用户识别出的问题（ASR_Result + ASR_End） */
    private fun showAiUserText(text: String) {
        val r1 = sendAi("ASR_Result", text)
        val r2 = sendAi("ASR_End")
        Log.i(TAG, "showAiUserText: ASR_Result=$r1 ASR_End=$r2 text=$text")
    }

    /** 当前对话模型模式是否为自定义（Lab 拦截并回复）；official 模式放行官方乐奇 */
    private fun isCustomAiMode(): Boolean =
        getSharedPreferences(PREFS_NAME, 0)
            .getString(KEY_AI_MODE, AI_MODE_CUSTOM)
            .orEmpty()
            .let { if (it.isBlank()) AI_MODE_CUSTOM else it } == AI_MODE_CUSTOM

    /** 接收手机端下发的 AI 配置（baseUrl/apiKey/model/mode）并持久化 */
    private fun handleAiConfig(args: Caps) {
        try {
            if (args.size() < 4) {
                Log.w(TAG, "Invalid ai_config size: ${args.size()}")
                return
            }
            val action = args.at(0).getString()
            if (action != "ai_config") return
            val baseUrl = args.at(1).getString().orEmpty()
            val apiKey = args.at(2).getString().orEmpty()
            val model = args.at(3).getString().orEmpty()
            // 第 5 个字段为对话模式（official/custom），旧版本下发无该字段时保持默认 custom
            val mode = if (args.size() >= 5) {
                args.at(4).getString()?.takeIf { it.isNotBlank() } ?: AI_MODE_CUSTOM
            } else AI_MODE_CUSTOM
            getSharedPreferences(PREFS_NAME, 0).edit()
                .putString(KEY_AI_BASE_URL, baseUrl)
                .putString(KEY_AI_API_KEY, apiKey)
                .putString(KEY_AI_MODEL, model)
                .putString(KEY_AI_MODE, mode)
                .apply()
            Log.i(TAG, "AI config saved: baseUrl=$baseUrl model=$model keyLen=${apiKey.length} mode=$mode")
        } catch (e: Exception) {
            Log.e(TAG, "handleAiConfig error", e)
        }
    }

    /** 手机端轮询拉取 ASR 文字（可回复订阅，请求-响应机制）。
     *  文字主通道为 RFCOMM 长连接（AsrPushServer），本通道保留空响应占位。 */
    private fun subscribeAiAsrPoll() {
        val b = bridge ?: return
        try {
            val r = b.subscribe(AI_ASR_POLL_TOPIC, CXRServiceBridge.MsgReplyCallback { _, _, _, reply ->
                reply.end(Caps())
                Log.d(TAG, "AI poll reply: empty")
            })
            Log.i(TAG, "subscribe($AI_ASR_POLL_TOPIC) -> $r")
        } catch (e: Exception) {
            Log.e(TAG, "subscribeAiAsrPoll error", e)
        }
    }

    /** 手机端「打开 RokidLink」触发：带 EXTRA_SHOW_UI 显示状态页（含 WiFi IP），供用户查看连接信息 */
    private fun showMainActivity() {
        // 防抖：手机端 appStart 后发的指令可能广播式重复到达，500ms 内只响应一次
        val now = System.currentTimeMillis()
        if (now - lastShowMainMs < 500L) {
            Log.i(TAG, "showMainActivity: debounced (repeat)")
            return
        }
        lastShowMainMs = now
        try {
            val intent = Intent(this, MainActivity::class.java).apply {
                // 必须用 CLEAR_TOP 而非 SINGLE_TOP：appStart 刚启动的隐形实例（无 EXTRA_SHOW_UI）
                // 在任务栈顶，SINGLE_TOP 会复用该实例，onCreate 不重跑 → showUi 仍为 false，
                // 导致隐形实例获得焦点后 300ms 执行 finish() 把刚显示的 IP 状态页关掉。
                // CLEAR_TOP 清掉旧实例并新建 showUi=true 实例，保证 onCreate 重新读取标志。
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(MainActivity.EXTRA_SHOW_UI, true)
            }
            startActivity(intent)
            Log.i(TAG, "showMainActivity: showing status page (WiFi IP)")
        } catch (e: Exception) {
            Log.e(TAG, "showMainActivity failed: ${e.message}")
        }
    }

    /** 收到手机端文字消息后，调用眼镜本地 TTS 播放语音 */
    private fun handleTtsPlay(args: Caps) {
        try {
            if (args.size() < 2 || args.at(1) == null ||
                args.at(1).type() != Caps.Value.TYPE_STRING
            ) {
                Log.w(TAG, "Invalid tts_play payload: size=${args.size()}")
                return
            }
            val text = args.at(1).getString()
            Log.i(TAG, "Received tts_play: ${text?.take(40)}...")
            if (!text.isNullOrBlank()) {
                TtsPlaybackHelper.play(this, text)
            }
        } catch (e: Exception) {
            Log.e(TAG, "handleTtsPlay error", e)
        }
    }

    /** 接收手机端下发的「按键答题」开关状态并持久化 */
    private fun handleQuizConfig(args: Caps) {
        try {
            if (args.size() < 2) {
                Log.w(TAG, "Invalid quiz config size: ${args.size()}")
                return
            }
            val action = args.at(0).getString()
            if (action != "quiz_enabled") return
            val enabled = args.at(1).getString() == "true"
            getSharedPreferences(PREFS_NAME, 0).edit()
                .putBoolean(KEY_QUIZ_ENABLED, enabled)
                .apply()
            Log.i(TAG, "Quiz config saved: enabled=$enabled")
        } catch (e: Exception) {
            Log.e(TAG, "handleQuizConfig error", e)
        }
    }

    /** 通知手机端执行「拍照问 AI」：经 CXR-S 通道上行到 RokidLab */
    private fun sendPhotoAskToPhone() {
        val now = System.currentTimeMillis()
        if (now - lastPhotoAskMs < 1000L) {
            Log.i(TAG, "sendPhotoAskToPhone deduped (${now - lastPhotoAskMs}ms since last)")
            return
        }
        lastPhotoAskMs = now
        val b = bridge
        if (b == null) {
            Log.w(TAG, "No CXR bridge, cannot send photo_ask")
            return
        }
        try {
            // 1. 原 photo_ask 上行（自定义频道，AI App 的 activeUid=null 时不转发，保留诊断）
            val caps = Caps()
            caps.write("photo_ask")
            val result = b.sendMessage(PHOTO_ASK_TOPIC, caps)
            Log.i(TAG, "sendMessage($PHOTO_ASK_TOPIC) -> $result")

            // 2. Sys 频道上行：模拟系统级 Sys_App_Resume_Change 事件，
            //    AI App 对 Sys 事件无条件转发（IAiEventCallback.onGlassAppResumeChange），
            //    手机端 SDK 据此回调 onGlassAppResume(true) 触发拍照答题。
            val sysCaps = Caps()
            sysCaps.write("Sys_App_Resume_Change")
            sysCaps.write("com.rokidlab.rokidlink")
            val sysResult = b.sendMessage("Sys", sysCaps)
            Log.i(TAG, "sendMessage(Sys/Sys_App_Resume_Change) -> $sysResult")
        } catch (e: Exception) {
            Log.e(TAG, "sendPhotoAskToPhone error", e)
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

    private fun handleWifiConfig(args: Caps) {
        try {
            if (args.size() < 3) {
                Log.w(TAG, "Invalid wifi config size: ${args.size()}")
                return
            }
            val action = args.at(0).getString()
            if (action != "wifi_connect") return

            val ssid = args.at(1).getString()
            val password = args.at(2).getString()
            Log.i(TAG, "Received wifi config: ssid=$ssid, password_length=${password?.length ?: 0}, caps_size=${args.size()}")

            connectToWifi(ssid, password)
        } catch (e: Exception) {
            Log.e(TAG, "handleWifiConfig error", e)
        }
    }

    private fun connectToWifi(ssid: String, password: String) {
        try {
            val wifiManager = getSystemService(WIFI_SERVICE) as? WifiManager ?: run {
                Log.e(TAG, "WifiManager not available")
                return
            }

            val currentSsid = wifiManager.connectionInfo?.ssid?.trim('"')
            if (currentSsid == ssid) {
                Log.i(TAG, "Already connected to $ssid, skipping")
                return
            }

            connectToWifiLegacy(wifiManager, ssid, password)
        } catch (e: Exception) {
            Log.e(TAG, "connectToWifi error", e)
        }
    }

    @Suppress("DEPRECATION")
    private fun connectToWifiLegacy(wifiManager: WifiManager, ssid: String, password: String) {
        if (!wifiManager.isWifiEnabled) {
            Log.i(TAG, "WiFi is disabled, enabling...")
            val enabled = wifiManager.setWifiEnabled(true)
            Log.i(TAG, "setWifiEnabled(true) -> $enabled")
            if (!enabled) {
                Log.e(TAG, "Failed to enable WiFi")
                return
            }
            var waitCount = 0
            while (!wifiManager.isWifiEnabled && waitCount < 30) {
                Thread.sleep(100)
                waitCount++
            }
            if (!wifiManager.isWifiEnabled) {
                Log.e(TAG, "WiFi enable timeout")
                return
            }
            Log.i(TAG, "WiFi enabled successfully")
        }

        val config = WifiConfiguration().apply {
            SSID = "\"$ssid\""
            preSharedKey = "\"$password\""
            status = WifiConfiguration.Status.ENABLED
            allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
        }

        val netId = wifiManager.addNetwork(config)
        if (netId == -1) {
            Log.e(TAG, "Failed to add wifi network $ssid")
            return
        }

        wifiManager.disconnect()
        Thread.sleep(500)

        val enabled = wifiManager.enableNetwork(netId, true)
        Log.i(TAG, "enableNetwork($netId) -> $enabled")
        wifiManager.reconnect()

        Log.i(TAG, "WiFi config applied (legacy): ssid=$ssid")
    }

    private fun connectToWifiApi29(wifiManager: WifiManager, ssid: String, password: String) {
        val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as? ConnectivityManager ?: run {
            Log.e(TAG, "ConnectivityManager not available")
            return
        }

        if (!wifiManager.isWifiEnabled) {
            Log.i(TAG, "WiFi is disabled, trying multiple methods to enable...")
            
            val methods = listOf(
                { enableWifiViaCXRBridge() },
                { enableWifiViaShellCommand() },
                { enableWifiViaReflection(wifiManager) },
                { enableWifiViaSettingsApi(); true }
            )
            
            var success = false
            for ((index, method) in methods.withIndex()) {
                try {
                    Log.i(TAG, "Trying method ${index + 1}...")
                    success = method.invoke()
                    if (success) {
                        Log.i(TAG, "WiFi enabled via method ${index + 1}")
                        break
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Method ${index + 1} failed: ${e.message}")
                }
            }
            
            if (!success) {
                Log.e(TAG, "All methods failed to enable WiFi")
                return
            }
            
            var waitCount = 0
            while (!wifiManager.isWifiEnabled && waitCount < 30) {
                Thread.sleep(100)
                waitCount++
            }
            if (!wifiManager.isWifiEnabled) {
                Log.e(TAG, "WiFi enable timeout")
                return
            }
        }

        Log.i(TAG, "Building WiFi network specifier for $ssid, password_len=${password.length}...")
        val specifier = WifiNetworkSpecifier.Builder()
            .setSsid(ssid)
            .setWpa2Passphrase(password)
            .build()
        Log.i(TAG, "Network specifier created: $specifier")

        val networkRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifier)
            .build()

        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.i(TAG, "WiFi connected to $ssid, network=${network.networkHandle}")
                connectivityManager.unregisterNetworkCallback(this)
            }

            override fun onUnavailable() {
                Log.e(TAG, "WiFi connection to $ssid via ConnectivityManager failed, trying legacy method...")
                connectivityManager.unregisterNetworkCallback(this)
                connectToWifiLegacy(wifiManager, ssid, password)
            }

            override fun onLost(network: Network) {
                Log.w(TAG, "WiFi connection lost: $ssid")
                connectivityManager.unregisterNetworkCallback(this)
            }
        }

        Log.i(TAG, "Requesting network for $ssid via ConnectivityManager (API 29+)...")
        connectivityManager.requestNetwork(networkRequest, networkCallback)

        Log.i(TAG, "WiFi connection requested: ssid=$ssid")
    }

    private fun enableWifiViaReflection(wifiManager: WifiManager): Boolean {
        return try {
            val method = wifiManager.javaClass.getMethod("setWifiEnabled", Boolean::class.javaPrimitiveType)
            method.isAccessible = true
            method.invoke(wifiManager, true) as Boolean
        } catch (e: Exception) {
            Log.e(TAG, "enableWifiViaReflection failed: ${e::class.simpleName}: ${e.message}")
            false
        }
    }

    private fun enableWifiViaSettingsApi() {
        try {
            val contentResolver = contentResolver
            val wifiOnKey = "wifi_on"
            val result = Settings.System.putInt(contentResolver, wifiOnKey, 1)
            Log.i(TAG, "Settings.System.putInt(wifi_on, 1) -> $result")
        } catch (e: Exception) {
            Log.e(TAG, "enableWifiViaSettingsApi failed: ${e::class.simpleName}: ${e.message}")
        }
    }

    private fun enableWifiViaCXRBridge(): Boolean {
        return try {
            val args = Caps()
            args.write("wifi_enable")
            args.write(true)
            val result = bridge?.sendMessage("system.wifi", args)
            Log.i(TAG, "enableWifiViaCXRBridge: sendMessage(system.wifi) -> $result")
            result == 0
        } catch (e: Exception) {
            Log.e(TAG, "enableWifiViaCXRBridge failed: ${e::class.simpleName}: ${e.message}")
            false
        }
    }

    private fun enableWifiViaShellCommand(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("svc", "wifi", "enable"))
            val exitCode = process.waitFor()
            Log.i(TAG, "enableWifiViaShellCommand: svc wifi enable -> exitCode=$exitCode")
            exitCode == 0
        } catch (e: Exception) {
            Log.e(TAG, "enableWifiViaShellCommand failed: ${e::class.simpleName}: ${e.message}")
            false
        }
    }
}
