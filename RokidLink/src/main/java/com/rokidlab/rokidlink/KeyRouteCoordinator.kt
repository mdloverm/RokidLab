package com.rokidlab.rokidlink

import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import com.rokid.cxr.Caps

/**
 * KeyButtonService 的按键路由协调器（v3.9 拆分自 KeyButtonService）。
 *
 * 职责：
 *  1. 动态注册按键广播接收器（priority=100 + abortBroadcast），按 DOWN/UP 时长或
 *     CLICK/LONG_PRESS 分发到短按/长按目标应用
 *  2. 「按键答题」：短按触发拍照问 AI（三通道上行：RFCOMM 控制 + photo_ask + Sys 事件）
 *  3. 工具确认窗口的按键语义（短按=允许，双击/长按=取消）
 *  4. 双击 = 退出对话（停 TTS + 上行 abort 标记）
 *  5. 答题流程屏幕保亮（SCREEN_DIM）与点屏唤醒（ACQUIRE_CAUSES_WAKEUP）
 */
internal class KeyRouteCoordinator(
    private val service: KeyButtonService,
    private val core: KeyServiceCore,
    private val toolConfirm: ToolConfirmController,
) {
    companion object {
        private const val TAG = KeyButtonService.TAG
        /**
         * 答题流程屏幕保持唤醒时长：拍照+OCR+AI 生成+TTS 播放全程不让屏幕熄屏。
         * 屏幕熄屏会触发 RokidAIController exit → closeCamera，导致对话窗口退出、
         * 手机端 takePhoto 无图超时。每次按键重新续期，超时后允许再次熄屏省电。
         */
        private const val QUIZ_SCREEN_AWAKE_MS = 120_000L
        /**
         * 答题时「点亮屏幕」用 SCREEN_BRIGHT + ACQUIRE_CAUSES_WAKEUP 的持有时长。
         * 只需覆盖从熄屏到官方 AI 会话把屏幕重新用起来的窗口，不需要长期持有
         * （长期持有由 [QUIZ_SCREEN_AWAKE_MS] 的 SCREEN_DIM 锁负责）。
         */
        private const val QUIZ_TURN_ON_MS = 15_000L
    }

    /** 按键接收器注册态（心跳/SCREEN_ON 会检查并重新注册） */
    var receiverRegistered = false
        private set

    /** 拍照答题去重：一次短按会产生 UP/CLICK 两条广播，1 秒内只上行一次 */
    private var lastPhotoAskMs = 0L

    /** 答题流程屏幕保亮锁 — SCREEN_DIM_WAKE_LOCK，防止答题中熄屏触发 AI 会话退出/相机释放 */
    private var quizScreenWakeLock: PowerManager.WakeLock? = null

    /** 点屏唤醒用的临时 WakeLock（ACQUIRE_CAUSES_WAKEUP） */
    private var quizTurnOnWakeLock: PowerManager.WakeLock? = null

    /** 按键广播接收器 */
    private val keyReceiver = object : BroadcastReceiver() {
        private var lastLaunchMs = 0L

        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action ?: return
            // 全链路入口日志：每次按键广播都打印 action + 答题开关状态，便于诊断「按键没反应」
            Log.i(TAG, "Key broadcast: $action quiz=${isKeyQuizEnabled(context)}")

            // Phase 4 确认窗口：有等待中的工具确认时，本次按键先满足确认语义再谈其他。
            // DOWN 只记录时间直接吞掉；UP/CLICK = 允许；双击/长按 = 取消。
            if (System.currentTimeMillis() < toolConfirm.suppressKeyUntilMs) {
                Log.i(TAG, "Key suppressed (post-confirm window)")
                return
            }
            if (toolConfirm.hasPending) {
                when (action) {
                    "com.android.action.ACTION_SPRITE_BUTTON_DOWN" -> {
                        KeyButtonService.downTimeMs = System.currentTimeMillis()
                        return
                    }
                    "com.android.action.ACTION_SPRITE_BUTTON_UP",
                    "com.android.action.ACTION_SPRITE_BUTTON_CLICK" -> {
                        Log.i(TAG, "Key in confirm window -> ALLOW")
                        toolConfirm.respond(true)
                        return
                    }
                    "com.android.action.ACTION_SPRITE_BUTTON_DOUBLE_CLICK",
                    "com.android.action.ACTION_SPRITE_BUTTON_LONG_PRESS" -> {
                        Log.i(TAG, "Key in confirm window -> DENY")
                        KeyButtonService.downTimeMs = 0L
                        toolConfirm.respond(false)
                        return
                    }
                }
            }

            when (action) {
                "com.android.action.ACTION_SPRITE_BUTTON_DOWN" -> {
                    KeyButtonService.downTimeMs = System.currentTimeMillis()
                    Log.i(TAG, "KEY DOWN → start timing (downTime=${KeyButtonService.downTimeMs})")
                }
                "com.android.action.ACTION_SPRITE_BUTTON_UP" -> {
                    val down = KeyButtonService.downTimeMs
                    KeyButtonService.downTimeMs = 0L
                    if (down <= 0) {
                        // 只收到 UP 未收到 DOWN（事件缺失/被截断）时无法按时长区分短按/长按
                        Log.w(TAG, "UP without DOWN, cannot determine duration, ignore")
                        return
                    }
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
                    KeyButtonService.downTimeMs = 0L  // clear pending DOWN so UP won't double-trigger
                    launchConfiguredTarget(context, isLong = true)
                }
                // 双击 = 退出对话窗口：立即停止本地 TTS 播报（若正在播长回复），
                // 并上行通知手机端「用户已关闭助手」→ 手机停音乐 + 停播报 + 取消模型运行。
                // 不 abortBroadcast，让官方 App 正常关闭 AI 对话界面。
                "com.android.action.ACTION_SPRITE_BUTTON_DOUBLE_CLICK" -> {
                    Log.i(TAG, "DOUBLE_CLICK → conversation exit, stop local TTS + abort AI on phone")
                    TtsPlaybackHelper.stop()
                    if (!AsrPushServer.push(KeyButtonService.ABORT_AI_MARKER)) {
                        Log.w(TAG, "ASR push channel unavailable, fallback to music-stop marker")
                        if (!AsrPushServer.push(KeyButtonService.MUSIC_STOP_MARKER)) {
                            Log.w(TAG, "ASR push channel unavailable, abort marker dropped")
                        }
                    }
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
                KeyButtonService.downTimeMs = 0L
                abortBroadcast()
                sendPhotoAskToPhone()
                return
            }
            val prefs = context.getSharedPreferences(KeyButtonService.PREFS_NAME, 0)
            val pkg = prefs.getString(
                if (elapsedMs >= 500L) KeyButtonService.KEY_LONG_PKG else KeyButtonService.KEY_SHORT_PKG, ""
            ) ?: ""
            val act = prefs.getString(
                if (elapsedMs >= 500L) KeyButtonService.KEY_LONG_ACT else KeyButtonService.KEY_SHORT_ACT, ".MainActivity"
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
                KeyButtonService.downTimeMs = 0L
                abortBroadcast()
                sendPhotoAskToPhone()
                return
            }
            val prefs = context.getSharedPreferences(KeyButtonService.PREFS_NAME, 0)
            val pkg = prefs.getString(
                if (isLong) KeyButtonService.KEY_LONG_PKG else KeyButtonService.KEY_SHORT_PKG, ""
            ) ?: ""
            val act = prefs.getString(
                if (isLong) KeyButtonService.KEY_LONG_ACT else KeyButtonService.KEY_SHORT_ACT, ".MainActivity"
            ) ?: ".MainActivity"

            KeyButtonService.downTimeMs = 0L
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

    fun registerKeyReceiver() {
        try {
            val filter = IntentFilter().apply {
                addAction("com.android.action.ACTION_SPRITE_BUTTON_DOWN")
                addAction("com.android.action.ACTION_SPRITE_BUTTON_UP")
                addAction("com.android.action.ACTION_SPRITE_BUTTON_CLICK")
                addAction("com.android.action.ACTION_SPRITE_BUTTON_LONG_PRESS")
                addAction("com.android.action.ACTION_SPRITE_BUTTON_DOUBLE_CLICK")
                priority = 100
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                service.registerReceiver(keyReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                service.registerReceiver(keyReceiver, filter)
            }
            Log.i(TAG, "Key receiver registered in Service")
            receiverRegistered = true
        } catch (e: Exception) {
            Log.e(TAG, "registerKeyReceiver failed", e)
            receiverRegistered = false
        }
    }

    fun unregisterKeyReceiver() {
        runCatching { service.unregisterReceiver(keyReceiver) }
        receiverRegistered = false
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

    /** 通知手机端执行「拍照问 AI」：经 CXR-S 通道上行到 RokidLab */
    fun sendPhotoAskToPhone() {
        val now = System.currentTimeMillis()
        if (now - lastPhotoAskMs < 1000L) {
            Log.i(TAG, "sendPhotoAskToPhone deduped (${now - lastPhotoAskMs}ms since last)")
            return
        }
        lastPhotoAskMs = now
        // 答题全程保持屏幕唤醒：熄屏会触发 AI 会话退出（closeCamera），
        // 导致对话窗口关闭 + 手机端 takePhoto 无图超时（见 QUIZ_SCREEN_AWAKE_MS 注释）。
        keepScreenAwakeForQuiz()
        val b = core.bridge
        if (b == null) {
            Log.w(TAG, "No CXR bridge, cannot send photo_ask")
            return
        }
        // ★ 本方法在主线程被调用（按键广播 onReceive / Service.onStartCommand），
        //   而 CXR sendMessage 在蓝牙断/半开时可能无限阻塞 —— 同文件 sendAi() 早已因此加了
        //   2s 超时保护（其注释原文：实测 open 卡死导致 takeoverExecutor 与回调线程双双失联）。
        //   此前这里漏了同样的保护：两次串行 sendMessage 一旦阻塞，就会吃满前台广播 10s /
        //   Service 20s 的 ANR 预算，被系统判定「应用无响应」后强杀进程 ——
        //   用户视角的「拍照答题时眼镜端崩溃」正是这条路径。
        //   因此整体移到后台线程执行，每次发送都带超时，绝不阻塞主线程。
        Thread {
            try {
                // 1. RFCOMM 主通道：独立于 AI App 网关，可靠区分「按键意图」，
                //    手机端收到控制指令后触发拍照答题（不会误触发）。
                val pushSent = AsrPushServer.pushControl(AsrPushServer.CTRL_PHOTO_ASK)
                Log.i(TAG, "pushControl(${AsrPushServer.CTRL_PHOTO_ASK}) -> $pushSent")

                // 2. 原 photo_ask 上行（自定义频道，AI App 的 activeUid=null 时不转发，保留诊断）
                val caps = Caps()
                caps.write("photo_ask")
                val result = core.sendCxrWithTimeout(b, KeyButtonService.PHOTO_ASK_TOPIC, caps)
                Log.i(TAG, "sendMessage(${KeyButtonService.PHOTO_ASK_TOPIC}) -> $result")

                // 3. Sys 频道上行：模拟系统级 Sys_App_Resume_Change 事件，
                //    AI App 对 Sys 事件无条件转发（IAiEventCallback.onGlassAppResumeChange），
                //    手机端 SDK 据此回调 onGlassAppResume(true) 触发打断官方回复。
                //    注意：手机端已不再用该回调触发拍照（无法区分真实 resume），仅用于打断 AI。
                val sysCaps = Caps()
                sysCaps.write("Sys_App_Resume_Change")
                sysCaps.write("com.rokidlab.rokidlink")
                val sysResult = core.sendCxrWithTimeout(b, LinkProtocol.CXR_CHANNEL_SYS, sysCaps)
                Log.i(TAG, "sendMessage(Sys/Sys_App_Resume_Change) -> $sysResult")
            } catch (e: Throwable) {
                // 后台线程必须 Throwable 兜底：未捕获异常走默认 handler 会直接崩进程
                Log.e(TAG, "sendPhotoAskToPhone error", e)
            }
        }.apply { name = "photo-ask-send"; isDaemon = true }.start()
    }

    /**
     * 答题流程屏幕保亮：持有 SCREEN_DIM_WAKE_LOCK（带超时续期），
     * 防止答题过程中屏幕自动熄屏触发 AI 会话退出与相机释放。
     * 超时自动释放，允许答题结束后再次熄屏省电；每次按键重新续期。
     * 若按键时屏幕已熄（如答题间隔超过保亮时长），由 [wakeScreenForQuiz] 重新点亮屏幕，
     * 否则 AI 会话仍处于退出态、相机不可用，takePhoto 会无图超时。
     *
     * SCREEN_DIM_WAKE_LOCK 已被 Android 标记弃用（官方推荐 FLAG_KEEP_SCREEN_ON），
     * 但后者必须挂在 Activity 的 Window 上 —— 而本服务不能持有任何常驻可见 Activity
     * （会抢占眼镜顶层 resumed 身份导致官方控制失灵，见 KeyButtonService 类注释）。
     * 弃用 ≠ 移除，该常量在现役 ROM 上照常生效，故此处有意保留。
     */
    @Suppress("DEPRECATION")
    private fun keepScreenAwakeForQuiz() {
        try {
            val pm = service.getSystemService(Context.POWER_SERVICE) as PowerManager
            val lock = quizScreenWakeLock ?: pm.newWakeLock(
                PowerManager.SCREEN_DIM_WAKE_LOCK,
                "KeyButtonService::QuizScreenWake"
            ).apply { setReferenceCounted(false) }
            quizScreenWakeLock = lock
            lock.acquire(QUIZ_SCREEN_AWAKE_MS)
            Log.i(TAG, "Quiz screen wake lock held (${QUIZ_SCREEN_AWAKE_MS / 1000}s)")
            if (!pm.isInteractive()) {
                wakeScreenForQuiz()
            }
        } catch (e: Exception) {
            Log.e(TAG, "keepScreenAwakeForQuiz failed", e)
        }
    }

    /**
     * 屏幕已熄时重新点亮屏幕。
     *
     * 只用 ACQUIRE_CAUSES_WAKEUP 的 WakeLock —— 不启动任何 Activity：
     * 本应用已无常驻透明层，且答题期间官方 AI 链路活跃，拉起任何本应用可见页面都可能被
     * AssistServer 判为 third_app 场景而强杀进程（用户视角即「拍照答题时眼镜端崩溃」）。
     *
     * SCREEN_BRIGHT_WAKE_LOCK / ACQUIRE_CAUSES_WAKEUP 均已被 Android 标记弃用
     * （官方替代是 Activity.setTurnScreenOn + setShowWhenLocked），但替代方案必须依赖
     * Activity —— 与上面「不能碰 Activity」的前提直接冲突，故此处有意保留弃用 API。
     */
    @Suppress("DEPRECATION")
    private fun wakeScreenForQuiz() {
        try {
            val pm = service.getSystemService(Context.POWER_SERVICE) as PowerManager
            val lock = quizTurnOnWakeLock ?: pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "KeyButtonService::QuizTurnScreenOn"
            ).apply { setReferenceCounted(false) }
            quizTurnOnWakeLock = lock
            lock.acquire(QUIZ_TURN_ON_MS)
            Log.i(TAG, "Screen wake requested via WakeLock(ACQUIRE_CAUSES_WAKEUP, ${QUIZ_TURN_ON_MS / 1000}s)")
        } catch (e: Exception) {
            Log.w(TAG, "wakeScreenForQuiz failed: ${e.message}")
        }
    }

    /** 服务销毁时释放答题屏幕锁 */
    fun releaseQuizWakeLocks() {
        runCatching {
            quizScreenWakeLock?.let { if (it.isHeld) it.release() }
            quizScreenWakeLock = null
        }
        runCatching {
            quizTurnOnWakeLock?.let { if (it.isHeld) it.release() }
            quizTurnOnWakeLock = null
        }
    }
}
