package com.rokidlab.rokidlink

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.ImageView
import android.widget.LinearLayout
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.util.Base64
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkRequest
import android.net.NetworkCapabilities
import com.rokid.cxr.CXRServiceBridge
import com.rokid.cxr.Caps
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 眼镜端按键映射常驻后台服务。
 *
 * 职责：
 * 1. 作为 Foreground Service（START_STICKY）保持进程常驻
 * 2. 动态注册按键广播接收器（priority=100 + abortBroadcast）
 * 3. 收到按键事件后，直接启动目标应用
 * 4. 通过 CXR-S SDK 订阅手机端下发的按键配置
 * 5. 持有 PARTIAL_WAKE_LOCK 防止休眠后按键失效
 * 6. 监听 SCREEN_ON 广播，屏幕亮起时校验 receiver 存活状态
 *
 * ★ 不持有任何常驻可见 Activity（历史上有 KeyButtonBridgeActivity 透明保活层，已移除）：
 * 透明 Activity 会成为眼镜的顶层 resumed/焦点应用，导致官方 Launcher 退居后台，
 * 表现为「双端一连上，眼镜自身的触摸板/官方乐奇就失灵，必须在眼镜上做一次退出操作
 * （HOME）把透明层任务踢到后台才能恢复」（2026-09-15 真机复现：mFocusedApp=KeyButtonBridge）。
 * 后台 startActivity 改由 SYSTEM_ALERT_WINDOW（BAL 法定豁免，手机端经 ADB appops 授予）
 * + FGS 保证；保活靠本服务 + WakeLock + 电池优化白名单。
 */
class KeyButtonService : Service() {
    private var bridge: CXRServiceBridge? = null

    /** AIUI .aix 接收服务（7658）：手机端推送 .aix → 落盘 → 拉起自托管宿主 */
    private var aiuiPkgServer: AiuiPackageServer? = null
    /** 最近成功落盘的 .aix（供宿主打开，可被 AIUI_HOST_TOPIC 命令覆盖打开） */
    private var aiuiLastFile: File? = null

    /** 显示状态页指令防抖：手机端指令可能广播式重复到达，500ms 内只响应一次 */
    private var lastShowMainMs = 0L

    /** 断线自愈：最近一次成功收到下行消息的时间戳（0=从未收到）。
     *  任意经 CXR bridge 订阅到达的消息（ping/config/tts/Ai）都会刷新，证明
     *  cxr-service → 本 App 的分发路由健康（重连后路由可能 stale，见 scheduleSelfHealCheck）。 */
    @Volatile
    private var lastDownlinkMs = 0L
    /** 是否处于「断线武装」状态：onDisconnected 置 true，重连成功后启动观察窗口 */
    private var reconnectArmed = false
    /** 重连成功时刻：观察窗口起点，窗口内无下行则判定路由失效 */
    private var reconnectAtMs = 0L
    /** 自愈检查任务引用（主线程 Handler），cancel 用 */
    private var selfHealCheck: Runnable? = null
    /** CXR bridge 当前是否已连接 */
    @Volatile
    private var bridgeConnected = false
    private var pendingReconnect: Runnable? = null  // B1：去重断连重建任务，避免堆叠导致订阅倍发

    /** 唤醒词+语音的 ASR 流式文字（覆盖式累积，ASR_End 时取最后一条） */
    private var pendingAiText: String? = null

    /** 本地接管执行器：ASR_End 后的 openAiSession/showAiUserText 含多次 sleep，
     *  连续提问时若每次都 new Thread 会并发执行导致指令交错，必须串行化 */
    private val takeoverExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

    /** Ai 频道发送执行器（固定 2 线程）：CXR sendMessage 阻塞时只卡任务线程，
     *  sendAi 通过 future.get(timeout) 保护调用线程不被永久卡死 */
    private val aiSendExecutor = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "ai-send").apply { isDaemon = true }
    }

    /**
     * 眼镜 WiFi IP 上行：监听 WiFi 可用/变化，拿到真实 IPv4 后经 CXR 通道上报手机端，
     * 手机端据此免手动输入自动填充到投屏/手机镜像/文件管理/ADB 共用的单一数据源。
     * 仅在 IP 实际变化时才上行，避免 onCapabilitiesChanged/onLinkPropertiesChanged 高频重复发送。
     */
    private var wifiIpReporter: ConnectivityManager.NetworkCallback? = null
    private var connectivityManager: ConnectivityManager? = null
    /** 最近一次成功上报的 IP，用于去重（同一 IP 不重复上行） */
    @Volatile
    private var lastReportedIp: String? = null

    /**
     * 下行过滤：手机端 Lab 回复时下行序列为 Exit→KeyDown_Client→open→ASR_Result→ASR_End→TTS_Result。
     * 若把下行 ASR_End 误当官方 ASR 处理（写文件+打断），会形成
     * 「写文件→手机读到→下行→误拦截→再写文件」的自反馈死循环。
     * 收到下行标志（KeyDown_Client/open）后，窗口内到达的 ASR 一律视为 Lab 重发，忽略。
     */
    @Volatile
    private var downlinkUntilMs = 0L

    /**
     * 官方「回声」抑制窗口：本地接管时我们会先给官方发 `Exit`（关闭它的会话），
     * 但官方云端**已经生成、正在途中**的 `TTS_Result` 仍会在几十毫秒内回流到同一个 `Ai` 频道。
     *
     * 为什么必须丢弃它：官方收到 `Exit` 时已 `dismissAiDialog + clearData`（清空列表），
     * 此时它自己的回复文字已经**无处渲染**；而我们若把它当成 Lab 回复注入
     * （[showAiReply] = 本机 `sendAi("TTS_Result")`），它就被写进了官方适配器的数据里 ——
     * 我们随后 `openAiSession()` 重开官方界面时，这段**官方的字**就被渲染出来
     * （用户实测反馈：「改连续对话之前官方的字我是看不到的」）。
     *
     * 判据为何安全：Lab 自己的下行（工具进度 / 最终正文）必须等手机端拿到 ASR 文字、
     * 调完 DeepSeek（含工具往返）才会产生，结构上不可能在打断后 800ms 内到达。
     */
    @Volatile
    private var officialEchoUntilMs = 0L

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

    // ── 工具确认窗口（Phase 4：call_phone 等副作用工具的眼镜端用户确认）──
    /** 进行中的确认请求 id（null = 无等待中的确认） */
    @Volatile
    private var pendingToolConfirmId: String? = null
    /** 确认超时任务（30s 无操作视为取消） */
    private var toolConfirmTimeoutRunnable: Runnable? = null
    /** 应答后的按键吞没窗口截止时间（UP/CLICK 连发时避免误触发启动目标） */
    @Volatile
    private var suppressKeyUntilMs = 0L

    /** PARTIAL_WAKE_LOCK — 防止 CPU 深度休眠导致广播投递失败 */
    private var wakeLock: PowerManager.WakeLock? = null

    /** 答题流程屏幕保亮锁 — SCREEN_DIM_WAKE_LOCK，防止答题中熄屏触发 AI 会话退出/相机释放 */
    private var quizScreenWakeLock: PowerManager.WakeLock? = null

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
            // 全链路入口日志：每次按键广播都打印 action + 答题开关状态，便于诊断「按键没反应」
            Log.i(TAG, "Key broadcast: $action quiz=${isKeyQuizEnabled(context)}")

            // Phase 4 确认窗口：有等待中的工具确认时，本次按键先满足确认语义再谈其他。
            // DOWN 只记录时间直接吞掉；UP/CLICK = 允许；双击/长按 = 取消。
            if (System.currentTimeMillis() < suppressKeyUntilMs) {
                Log.i(TAG, "Key suppressed (post-confirm window)")
                return
            }
            if (pendingToolConfirmId != null) {
                when (action) {
                    "com.android.action.ACTION_SPRITE_BUTTON_DOWN" -> {
                        downTimeMs = System.currentTimeMillis()
                        return
                    }
                    "com.android.action.ACTION_SPRITE_BUTTON_UP",
                    "com.android.action.ACTION_SPRITE_BUTTON_CLICK" -> {
                        Log.i(TAG, "Key in confirm window -> ALLOW")
                        respondToolConfirm(true)
                        return
                    }
                    "com.android.action.ACTION_SPRITE_BUTTON_DOUBLE_CLICK",
                    "com.android.action.ACTION_SPRITE_BUTTON_LONG_PRESS" -> {
                        Log.i(TAG, "Key in confirm window -> DENY")
                        downTimeMs = 0L
                        respondToolConfirm(false)
                        return
                    }
                }
            }

            when (action) {
                "com.android.action.ACTION_SPRITE_BUTTON_DOWN" -> {
                    downTimeMs = System.currentTimeMillis()
                    Log.i(TAG, "KEY DOWN → start timing (downTime=$downTimeMs)")
                }
                "com.android.action.ACTION_SPRITE_BUTTON_UP" -> {
                    val down = downTimeMs
                    downTimeMs = 0L
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
                    downTimeMs = 0L  // clear pending DOWN so UP won't double-trigger
                    launchConfiguredTarget(context, isLong = true)
                }
                // 双击 = 退出对话窗口：立即停止本地 TTS 播报（若正在播长回复），
                // 并上行通知手机端「用户已关闭助手」→ 手机停音乐 + 停播报 + 取消模型运行。
                // 不 abortBroadcast，让官方 App 正常关闭 AI 对话界面。
                "com.android.action.ACTION_SPRITE_BUTTON_DOUBLE_CLICK" -> {
                    Log.i(TAG, "DOUBLE_CLICK → conversation exit, stop local TTS + abort AI on phone")
                    TtsPlaybackHelper.stop()
                    if (!AsrPushServer.push(ABORT_AI_MARKER)) {
                        Log.w(TAG, "ASR push channel unavailable, fallback to music-stop marker")
                        if (!AsrPushServer.push(MUSIC_STOP_MARKER)) {
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

        /** 短命销毁计数持久化键：服务启动后短时间内反复被销毁时累加 */
        private const val KEY_SHORT_LIVED_DESTROY_COUNT = "short_lived_destroy_count"
        /** 判定「短命」的存活时长阈值：低于该值被销毁视为启动即失败 */
        private const val SHORT_LIVED_THRESHOLD_MS = 60_000L
        /** 连续短命销毁达此次数后放弃自愈，避免无限重启循环耗尽系统资源 */
        private const val MAX_SHORT_LIVED_DESTROY = 5

        /** 下行过滤窗口：收到 Lab 下行标志后，窗口内 ASR 视为重发忽略。
         *  仅 KeyDown_Client 触发（Lab 完整下行序列 KeyDown_Client→open→ASR_Result→ASR_End 约 1s）。
         *  手机端打断官方用的「Ai/open」（interruptOfficialAi）不再触发过滤，避免误吞用户真实提问。 */
        private const val DOWNLINK_FILTER_MS = 2_000L
        /** 打断次数限制滑动窗口与上限 */
        private const val INTERRUPT_WINDOW_MS = 20_000L
        private const val INTERRUPT_MAX = 3
        /**
         * 连续对话（多轮免唤醒）模式下的打断限流：原 20s / 3 次是「异常自反馈循环」的保险，
         * 但连续对话时每一轮用户说话都要消耗一次额度（这才是正常用法），实测说到第 4 句
         * （间隔均 < 20s）就会被 [allowInterrupt] 拒绝，表现为「前三句正常、之后喊了没反应」。
         * 连续对话开启时改用 60s / 12 次：仍然是硬上限（异常循环最多多跑 9 轮就自停），
         * 但不影响正常的多轮对话节奏。
         */
        private const val INTERRUPT_WINDOW_CONTINUOUS_MS = 60_000L
        private const val INTERRUPT_MAX_CONTINUOUS = 12
        /**
         * 答题流程屏幕保持唤醒时长：拍照+OCR+AI 生成+TTTS 播放全程不让屏幕熄屏。
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
        /** startForeground 被拒后的重试上限与间隔（与 BtTunnelService 同策略） */
        private const val MAX_FOREGROUND_RETRIES = 5
        private const val FOREGROUND_RETRY_INTERVAL_MS = 5_000L
        /** ASR 文字 logcat 输出 tag：手机端经 ADB（蓝牙隧道）读取（CXR 上行被 AI App 过滤，改用 logcat） */
        internal const val AI_ASR_BRIDGE_TAG = "AiAsrBridge"
        internal const val PREFS_NAME = "key_button_config"
        internal const val KEY_SHORT_PKG = "short_pkg"
        internal const val KEY_SHORT_ACT = "short_act"
        internal const val KEY_LONG_PKG = "long_pkg"
        internal const val KEY_LONG_ACT = "long_act"
        internal const val TOPIC = "rokidlab_key_config"
        /**
         * 已废弃：WiFi 配置实际走官方 "Wifi" 频道（Wifi_Connect），手机端已无发送方，
         * 本订阅仅作历史兜底保留，勿再新增发送侧。
         */
        @Deprecated("wifi_config 通道已废弃：WiFi 配置走官方 Wifi 频道（Wifi_Connect）")
        internal const val WIFI_TOPIC = "wifi_config"
        internal const val TTS_TOPIC = "tts_play"
        /** 停止 TTS 播报下行通道（手机端 → 眼镜端）：退出对话/打断播报时手机端主动下发，眼镜端立即停本地 TTS */
        internal const val TTS_STOP_TOPIC = "tts_stop"
        /** 「按键答题」开关下发通道（手机端 → 眼镜端） */
        internal const val QUIZ_TOPIC = "rokidlab_key_quiz"
        /** 「连续对话（多轮免唤醒）」开关下发通道（手机端 → 眼镜端）：见 [KEY_CONTINUE_DIALOG] */
        internal const val CONTINUE_TOPIC = "rokidlab_chat_continue"
        /** 拍照问AI 指令上行通道（眼镜端 → 手机端） */
        internal const val PHOTO_ASK_TOPIC = "rokidlab_photo_ask"
        /** 语音转文字结果上行通道（眼镜端 → 手机端）：唤醒词+语音的 ASR 文字转给 Lab 回复 */
        internal const val AI_ASR_TOPIC = "rokidlab_ai_asr"
        /** 双击退出对话窗口时经 RFCOMM 推送通道上行的音乐停止标记（与手机端保持一致） */
        internal const val MUSIC_STOP_MARKER = LinkProtocol.MARKER_MUSIC_STOP
        /**
         * 用户关闭助手/退出对话时经 RFCOMM 推送通道上行的中止标记（与手机端保持一致）：
         * 手机收到后停音乐 + 下发 tts_stop 停眼镜播报 + 取消运行中的 Lab 模型请求
         */
        internal const val ABORT_AI_MARKER = LinkProtocol.MARKER_ABORT_AI
        /**
         * AI 文字轮询通道（手机端 → 眼镜端）：RokidLab 定时 sendCustomCmd 轮询，
         * 眼镜端可回复订阅返回 ASR 文字。采用请求-响应机制以绕过 AI App 对未知上行指令的过滤。
         */
        internal const val AI_ASR_POLL_TOPIC = "rokidlab_ai_asr_poll"
        /** AI 频道（手机端 → 眼镜端，AssistServer 全局订阅） */
        internal const val AI_TOPIC = LinkProtocol.CXR_CHANNEL_AI
        /** 显示状态页指令通道（手机端 → 眼镜端）：用户点「打开 RokidLink」后，MainActivity 带 EXTRA_SHOW_UI 显示 IP 状态页 */
        internal const val SHOW_MAIN_TOPIC = "rokidlab_show_main"
        /** AI 配置下发通道（手机端 → 眼镜端）：baseUrl/apiKey/model，供眼镜端直接调用模型 */
        internal const val AI_CONFIG_TOPIC = "rokidlab_ai_config"
        /**
         * AIUI 自托管宿主控制通道（手机端 → 眼镜端）：
         * 载荷 [cmd, arg]，cmd ∈ open / close / msg
         *  - open [open, fileName?]：打开 filesDir/aiui_host/<fileName>.aix（缺省用最近推送包）渲染于本宿主
         *  - close：关闭正在渲染的 AIUI 宿主
         *  - msg  [msg, json]：把 JSON 以 onMessage 协议注入页面（伪交互补充通道）
         */
        internal const val AIUI_HOST_TOPIC = "rokidlab_aiui_host"
        private const val CMD_AIUI_OPEN = "open"
        private const val CMD_AIUI_CLOSE = "close"
        private const val CMD_AIUI_MSG = "msg"
        /** AI 配置持久化 key */
        internal const val KEY_AI_BASE_URL = "ai_base_url"
        internal const val KEY_AI_API_KEY = "ai_api_key"
        internal const val KEY_AI_MODEL = "ai_model"
        /** 对话模型模式持久化 key：custom = Lab 拦截回复；official = 官方乐奇 */
        internal const val KEY_AI_MODE = "ai_mode"
        // 模型模式值统一引用 AiChannel.AI_MODE_OFFICIAL / AiChannel.AI_MODE_CUSTOM（协议规范单源）
        /** 「按键答题」开关存储 key */
        internal const val KEY_QUIZ_ENABLED = "key_quiz_enabled"
        /** 「连续对话（多轮免唤醒）」开关存储 key：true = 每轮 Lab 回复的本地 TTS 播完后自动重开官方 AI 会话 */
        internal const val KEY_CONTINUE_DIALOG = "chat_continue_dialog"
        /** 外部（如手机端 ADB am startservice）触发拍照答题时通知 Service 的 action */
        internal const val ACTION_QUIZ_PHOTO_ASK = "rokidlab.action.QUIZ_PHOTO_ASK"
        /** 工具确认窗口时长：超时未应答视为取消 */
        private const val TOOL_CONFIRM_WINDOW_MS = 30_000L
        /** 确认应答后的按键吞没窗口（UP/CLICK 连发去重） */
        private const val KEY_SUPPRESS_AFTER_CONFIRM_MS = 1_500L
        /**
         * 自动续听延时：Lab 回复本地 TTS 播完 → 重开官方 AI 会话的等待时间。
         * 需要缓冲是因为停播瞬间扬声器仍有尾音，立即开麦会把尾音喂成一次误识别；
         * 400ms 与 [TtsPlaybackHelper] 内部的块间稳定延时同量级。
         */
        private const val CONTINUE_DIALOG_DELAY_MS = 400L
        /**
         * 官方 startNewTalk 生效延时：眼镜端推续听标记后，要经
         * 手机端（RFCOMM 上行 → CXR 下行）再交官方 `AudioFinishedHandler →
         * aiAudioFinishWake → startNewTalk` 才真正重开拾音，期间 `showAudioFinishUI`
         * 会更新会话列表（追加一条新气泡）。等它走完再把 Lab 回复补回界面。
         *
         * 比本机直发多一个来回（眼镜→手机→眼镜），故取 700ms：真机 trace 里官方
         * 从收帧到 `startNewTalk` 仅 15ms，余量留给 RFCOMM/CXR 往返与线程调度。
         */
        private const val CONTINUE_LISTEN_SETTLE_MS = 700L
        /**
         * 本地打断官方后，官方**仍在途的回复正文**回流窗口（见 [officialEchoUntilMs]）。
         */
        private const val OFFICIAL_ECHO_WINDOW_MS = 800L

        /** 断线重连后下行路由 stale 的自愈：Alarm 拉活广播 action（SelfRestartReceiver 处理） */
        internal const val ACTION_SELF_HEAL_RESTART = "com.rokidlab.rokidlink.SELF_HEAL_RESTART"
        /** 自愈兜底拉起广播 action：自杀后若粘性重启失败，Alarm 在新进程显式拉起服务 */
        internal const val ACTION_SELF_HEAL_BOOTSTRAP = "com.rokidlab.rokidlink.SELF_HEAL_BOOTSTRAP"
        /** 重连成功后的下行观察窗口：手机端 ping 周期 60s，观察 150s 覆盖 ≥2 个周期，避免正常空闲误判 */
        private const val SELF_HEAL_CHECK_DELAY_MS = 150_000L
        /** Alarm 拉活与自杀之间的延迟：留给系统注册 alarm */
        private const val SELF_HEAL_RESTART_DELAY_MS = 3_000L
        /** 自杀后兜底拉起的延迟：给 START_STICKY 留出重启时间，超时未起则由兜底闹钟显式拉起 */
        private const val SELF_HEAL_BOOTSTRAP_DELAY_MS = 20_000L

        /** 按键按下时间戳 */
        @Volatile
        internal var downTimeMs: Long = 0L

        /** 「按键答题」开关是否开启（供 BridgeActivity 与 Service 共用） */
        @JvmStatic
        fun isKeyQuizEnabled(ctx: Context): Boolean =
            ctx.getSharedPreferences(PREFS_NAME, 0).getBoolean(KEY_QUIZ_ENABLED, false)

        /**
         * 「连续对话（多轮免唤醒）」开关是否开启，默认 **true**。
         *
         * 开启时：每轮 Lab 回复的本地 TTS 播完后，[KeyButtonService] 自动重开一次官方
         * ai_assist 会话（等价于用户再喊一次唤醒词），用户可以直接接着说下一句。
         * 用户可在手机端「乐奇聊天 → 设置 → 连续对话」关闭。
         */
        @JvmStatic
        fun isContinueDialogEnabled(ctx: Context): Boolean =
            ctx.getSharedPreferences(PREFS_NAME, 0).getBoolean(KEY_CONTINUE_DIALOG, true)

        /** 启动此服务 */
        fun start(ctx: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(Intent(ctx, KeyButtonService::class.java))
            } else {
                ctx.startService(Intent(ctx, KeyButtonService::class.java))
            }
        }
    }

    // ──────────────────────────────────────────────
    //  Service 生命周期与自愈
    // ──────────────────────────────────────────────

    /** 本实例启动时刻，用于判定「启动后短命被销毁」（自愈重启循环防护） */
    private var serviceStartMs = 0L

    override fun onCreate() {
        super.onCreate()
        serviceStartMs = System.currentTimeMillis()
        Log.i(TAG, "Service creating")
        startForegroundService()
        // 拉起蓝牙隧道服务（ADB / 文件 / 投屏的服务端宿主）。两者互不依赖，只有 MainActivity 会同时启动，
        // 而眼镜端被官方 AssistServer 以 ThirdAppScene 强杀后，手机端的自愈路径是
        // `am start-foreground-service KeyButtonService`（phone-app ToolRegistry.ensureGlassesLinkRunning），
        // 此处若不补拉起，BtTunnelServer 的 RFCOMM SCN 无人注册，隧道就再也回不来——表现为
        // 提取/ADB 工具全部卡在「建链」（2026-09-13 真机坐实）。BtTunnelService.start 幂等。
        runCatching { BtTunnelService.start(this) }
            .onFailure { Log.e(TAG, "Failed to start BtTunnelService", it) }
        // 不启动任何常驻可见 Activity（透明保活层会抢走眼镜顶层 resumed 身份，导致官方控制
        // 失灵，见类注释）。后台启动页面由 SYSTEM_ALERT_WINDOW 豁免 BAL。
        registerKeyReceiver()
        // 蓝牙运行时权限缺失（典型：CXR-L 重装眼镜端后授权被清空）时拉起 MainActivity 申请。
        // Service 自己不能弹运行时权限框；只在本进程存活期内未提示过时尝试一次，避免反复跳页。
        ensureBluetoothPermissionOrPrompt()
        registerScreenOnReceiver()
        acquireWakeLock()
        startHeartbeat()
        initCxrBridge()
        // AIUI .aix 接收服务常驻：等手机端推包即可拉起自托管宿主渲染
        startAiuiHostServer()
        // 预热绑定系统 TTS 服务，避免首次「拍照问 AI」回复要等异步绑定
        TtsPlaybackHelper.ensureBound(this)
        // 监听 WiFi 变化并上行眼镜 IP，手机端免手动输入自动填充
        registerWifiIpReporter()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 手机端或本应用组件可通过 startService(ACTION_QUIZ_PHOTO_ASK) 触发拍照答题上行
        if (intent?.action == ACTION_QUIZ_PHOTO_ASK) {
            Log.i(TAG, "Quiz photo ask triggered via startService")
            sendPhotoAskToPhone()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.i(TAG, "Service destroying")
        handler.removeCallbacksAndMessages(null)
        // 显式置空待执行的自动续听引用（removeCallbacksAndMessages 已撤掉队列，这里同步清状态）
        pendingContinueDialog = null
        // 停 AIUI 接收服务（自愈重启后会在新实例 onCreate 重新拉起）
        runCatching { aiuiPkgServer?.stop() }
        aiuiPkgServer = null
        // 解绑系统 TtsService：避免 ServiceConnection 泄漏（服务重建时旧连接残留）
        runCatching { TtsPlaybackHelper.unbind(this) }
        // 停止本地接管执行器（openAiSession 等含 sleep 的任务不再继续）
        takeoverExecutor.shutdownNow()
        runCatching { unregisterReceiver(keyReceiver) }
        receiverRegistered = false
        runCatching { unregisterReceiver(screenOnReceiver) }
        runCatching {
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
        }
        runCatching {
            quizScreenWakeLock?.let { if (it.isHeld) it.release() }
            quizScreenWakeLock = null
        }
        runCatching {
            quizTurnOnWakeLock?.let { if (it.isHeld) it.release() }
            quizTurnOnWakeLock = null
        }
        runCatching { unregisterWifiIpReporter() }
        runCatching { bridge?.disconnectCXRDevice() }
        bridge = null
        // 崩溃/异常销毁自愈：延迟检查，若服务未恢复则重新拉起。
        // START_STICKY 在 startRequested=false（服务被 stop）时不生效，需要主动重启。
        // 眼镜 ROM 在 app idle 时可能停服务，且后台 FGS 启动受限，因此多次重试直到成功。
        //
        // ⚠️ 短命销毁防护：若服务启动后 <60s 就被销毁，说明是「启动即失败」（典型如 bridge
        // JNI 初始化崩溃），此时反复重启只会形成无限循环、持续空转耗尽电量与系统资源。
        // 计数必须用 SharedPreferences 持久化——重试计数是方法局部的，每轮 onDestroy 都会
        // 从 0 重新开始，永远触发不到上限。连续 5 次短命销毁后放弃自愈，等待用户手动拉起。
        val aliveMs = System.currentTimeMillis() - serviceStartMs
        val prefs = getSharedPreferences(PREFS_NAME, 0)
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
        handler.post { clearToolConfirm() }
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

    // setPriority/PRIORITY_MIN 仅对 Android 8 以下生效（O 起优先级由 NotificationChannel 决定），
    // 但 O 以下设备仍需这两个已弃用 API 才能把通知降到最低，无法用新 API 替代。
    @Suppress("DEPRECATION")
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
            startForeground(1, notification)
            foregroundRetryCount = 0
            Log.i(TAG, "Foreground service started")
        } catch (e: Throwable) {
            Log.e(TAG, "startForeground failed: ${e::class.simpleName}: ${e.message}")
            retryStartForeground(notification)
        }
    }

    /** 前台状态重试计数（转前台被拒后延迟重试，见 [retryStartForeground]） */
    private var foregroundRetryCount = 0

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
        handler.postDelayed({
            try {
                startForeground(1, notification)
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

    /** 本进程存活期内是否已为蓝牙权限弹过页（拒绝后不反复打断） */
    @Volatile
    private var btPermissionPrompted = false

    /**
     * BLUETOOTH_CONNECT 缺失时（典型：CXR-L「重装眼镜端」覆盖安装后运行时授权被清空）
     * 一次性拉起 [MainActivity] 发起系统权限请求。
     *
     * 不使用常驻透明 Activity 承载：它会抢占眼镜顶层 resumed 身份导致官方控制失灵。
     * MainActivity 申请完权限后用户自行返回即可；正常情况下手机端会在授权流程中
     * 经 ADB `pm grant` 直接下发，根本走不到这里。
     */
    private fun ensureBluetoothPermissionOrPrompt() {
        if (btPermissionPrompted) return
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) return
        val granted = checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (granted) return
        btPermissionPrompted = true
        Log.w(TAG, "BLUETOOTH_CONNECT missing, launching MainActivity for runtime permission")
        runCatching {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure { Log.e(TAG, "launch MainActivity for BT permission failed: ${it.message}") }
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
                addAction("com.android.action.ACTION_SPRITE_BUTTON_DOUBLE_CLICK")
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
            // Android 13+ 动态注册必须显式指定接收标志。此处只监听系统广播
            // ACTION_SCREEN_ON，无需接收其他应用发来的 Intent，故声明 NOT_EXPORTED。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(screenOnReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(screenOnReceiver, filter)
            }
            Log.i(TAG, "Screen-on receiver registered")
        } catch (e: Exception) {
            Log.e(TAG, "registerScreenOnReceiver failed", e)
        }
    }

    /**
     * 获取 PARTIAL_WAKE_LOCK 长期持有（无超时）：只要服务存活就保持 CPU 唤醒，
     * 确保蓝牙隧道/ASR/按键随时可达。
     * 之前用超时获取 + 心跳续期，存在"到期释放→心跳补锁"的空窗：设备在空窗内
     * 休眠后蓝牙 RFCOMM 全断、手机端所有功能连接失败（实测 16:02 眼镜 suspend 后全断）。
     * onDestroy 时释放（见 [onDestroy]）。
     */
    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
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

    /**
     * 答题流程屏幕保亮：持有 SCREEN_DIM_WAKE_LOCK（带超时续期），
     * 防止答题过程中屏幕自动熄屏触发 AI 会话退出与相机释放。
     * 超时自动释放，允许答题结束后再次熄屏省电；每次按键重新续期。
     * 若按键时屏幕已熄（如答题间隔超过保亮时长），由 [wakeScreenForQuiz] 重新点亮屏幕，
     * 否则 AI 会话仍处于退出态、相机不可用，takePhoto 会无图超时。
     *
     * SCREEN_DIM_WAKE_LOCK 已被 Android 标记弃用（官方推荐 FLAG_KEEP_SCREEN_ON），
     * 但后者必须挂在 Activity 的 Window 上 —— 而本服务不能持有任何常驻可见 Activity
     * （会抢占眼镜顶层 resumed 身份导致官方控制失灵，见类注释）。
     * 弃用 ≠ 移除，该常量在现役 ROM 上照常生效，故此处有意保留。
     */
    @Suppress("DEPRECATION")
    private fun keepScreenAwakeForQuiz() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
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

    /** 点屏唤醒用的临时 WakeLock（ACQUIRE_CAUSES_WAKEUP），见 [wakeScreenForQuiz] */
    private var quizTurnOnWakeLock: PowerManager.WakeLock? = null

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
            val pm = getSystemService(POWER_SERVICE) as PowerManager
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
                    bridgeConnected = true
                    // 连接建立后若已连 WiFi，立即上行眼镜 IP（首次/重连后让手机端尽快拿到）
                    sendGlassesIp()
                    // 断线重连成功：cxr-service 分发路由可能在重连后 stale（订阅返回 0 但实际不投递，
                    // 实测 ai_config/tts/show_main 全部静默丢失，仅进程重启可恢复）。
                    // 启动观察窗口：期间收到任何下行（ping/config/tts）即健康，否则判定路由失效自愈。
                    if (reconnectArmed) {
                        reconnectArmed = false
                        reconnectAtMs = System.currentTimeMillis()
                        scheduleSelfHealCheck()
                    }
                }
                override fun onDisconnected() {
                    bridgeConnected = false
                    // 断线期不做路由判定：取消观察并武装，待重连成功后重新启动观察
                    reconnectArmed = true
                    cancelSelfHealCheck()
                    Log.i(TAG, "CXR disconnected, will re-init in 3s")
                    // 断线期间清空下行过滤窗口与累积 ASR，避免重连后误吞用户提问/误拦文本
                    downlinkUntilMs = 0L
                    pendingAiText = null
                    // 断线自愈：cxr-service 重启/蓝牙闪断后重建桥接并重订阅，避免永久失联
                    // B1 修复：先取消旧任务去重；若 3s 内已自动重连成功则跳过，
                    //          避免丢弃刚连好的 bridge 重建、造成同一条 config/tts/ai 被重复下发
                    pendingReconnect?.let { mainHandler.removeCallbacks(it) }
                    pendingReconnect = Runnable {
                        pendingReconnect = null
                        if (bridgeConnected) {
                            Log.i(TAG, "CXR already reconnected before 3s, skip re-init")
                            return@Runnable
                        }
                        runCatching { bridge?.disconnectCXRDevice() }
                        runCatching {
                            bridge = null
                            initCxrBridge()
                        }.onFailure { Log.e(TAG, "re-init CXR bridge failed", it) }
                    }
                    mainHandler.postDelayed(pendingReconnect!!, 3000)
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

            // 历史兜底订阅（wifi_config 已废弃，仅保留兼容旧版手机端；WiFi 配置主通道为官方 Wifi 频道）
            @Suppress("DEPRECATION")
            run {
                val wifiResult = bridge?.subscribe(WIFI_TOPIC, CXRServiceBridge.MsgCallback { _, args, _ ->
                    handleWifiConfig(args)
                })
                Log.i(TAG, "subscribe($WIFI_TOPIC) -> $wifiResult")
            }

            val ttsResult = bridge?.subscribe(TTS_TOPIC, CXRServiceBridge.MsgCallback { _, args, _ ->
                handleTtsPlay(args)
            })
            Log.i(TAG, "subscribe($TTS_TOPIC) -> $ttsResult")

            val ttsStopResult = bridge?.subscribe(TTS_STOP_TOPIC, CXRServiceBridge.MsgCallback { _, _, _ ->
                markDownlink()
                Log.i(TAG, "Received tts_stop, stopping local TTS")
                TtsPlaybackHelper.stop()
            })
            Log.i(TAG, "subscribe($TTS_STOP_TOPIC) -> $ttsStopResult")

            // 工具确认请求（手机端 → 眼镜端）：call_phone 等副作用工具执行前的用户确认
            val toolConfirmResult = bridge?.subscribe(
                LinkProtocol.TOPIC_TOOL_CONFIRM,
                CXRServiceBridge.MsgCallback { _, args, _ ->
                    markDownlink()
                    handleToolConfirm(args)
                }
            )
            Log.i(TAG, "subscribe(${LinkProtocol.TOPIC_TOOL_CONFIRM}) -> $toolConfirmResult")

            val quizResult = bridge?.subscribe(QUIZ_TOPIC, CXRServiceBridge.MsgCallback { _, args, _ ->
                handleQuizConfig(args)
            })
            Log.i(TAG, "subscribe($QUIZ_TOPIC) -> $quizResult")

            // 连续对话（多轮免唤醒）开关：手机端设置页切换时下发，落 prefs 后立即改变
            // isContinueDialogEnabled() 的返回值（无需重连/重启服务）。
            val continueResult = bridge?.subscribe(CONTINUE_TOPIC, CXRServiceBridge.MsgCallback { _, args, _ ->
                markDownlink()
                handleContinueDialogConfig(args)
            })
            Log.i(TAG, "subscribe($CONTINUE_TOPIC) -> $continueResult")

            // 推送通道远程踢活：手机端检测到 RFCOMM 推送监听连续秒断（监听假死）时经 CXR
            // 频道下发（CXR 由系统 cxr-service 托管，推送死了它仍可达），收到后重建 AsrPushServer。
            val pushRestartResult = bridge?.subscribe(AiChannel.TOPIC_PUSH_RESTART, CXRServiceBridge.MsgCallback { _, _, _ ->
                markDownlink()
                Log.w(TAG, "push restart requested by phone, recreating AsrPushServer")
                AsrPushServer.restart()
            })
            Log.i(TAG, "subscribe(${AiChannel.TOPIC_PUSH_RESTART}) -> $pushRestartResult")

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
                markDownlink()
                showMainActivity()
            })
            Log.i(TAG, "subscribe($SHOW_MAIN_TOPIC) -> $showMainResult")

            // AIUI 自托管宿主控制：open / close / msg（onMessage 伪交互通道）
            val aiuiHostResult = bridge?.subscribe(AIUI_HOST_TOPIC, CXRServiceBridge.MsgCallback { _, args, _ ->
                markDownlink()
                handleAiuiHost(args)
            })
            Log.i(TAG, "subscribe($AIUI_HOST_TOPIC) -> $aiuiHostResult")

            // 下行存活探测订阅：手机端连接期间每 60s 下发一条空消息。
            // 断线重连后 cxr-service 分发路由可能 stale（订阅返回 0 但实际不投递，
            // 实测 ai_config/tts/show_main 全部静默丢失，仅进程重启可恢复）。
            // ping 一旦到达即证明路由健康；观察窗口到期仍收不到则 Alarm 重启进程自愈。
            val pingResult = bridge?.subscribe(AiChannel.TOPIC_PING, CXRServiceBridge.MsgCallback { _, _, _ ->
                markDownlink()
                Log.i(TAG, "Received ping — downlink route healthy")
            })
            Log.i(TAG, "subscribe(${AiChannel.TOPIC_PING}) -> $pingResult")

            // 协议握手（插播 B · LinkProtocol v2）：手机端主动询问时回报版本与能力，
            // 覆盖「眼镜端后启动 / 手机端重连」场景。
            val helloResult = bridge?.subscribe(LinkProtocol.TOPIC_HELLO_REQ, CXRServiceBridge.MsgCallback { _, _, _ ->
                markDownlink()
                announceHello()
            })
            Log.i(TAG, "subscribe(${LinkProtocol.TOPIC_HELLO_REQ}) -> $helloResult")

            // 停止手机投屏指令：手机端按「停止投屏」时下发。
            // PhoneMirrorActivity 设计为 socket 断开后保持前台等重连（避免重连后画面更新在
            // 后台不可见），因此必须显式关闭，否则最后一帧画面会残留在眼镜上。
            // 复用页面既有的 ACTION_FINISH_MIRROR 广播（与 ScreenMirrorIntentActivity 同一通道）。
            val stopMirrorResult = bridge?.subscribe(
                AiChannel.TOPIC_STOP_PHONE_MIRROR,
                CXRServiceBridge.MsgCallback { _, _, _ ->
                    markDownlink()
                    Log.i(TAG, "Received stop_phone_mirror — closing PhoneMirrorActivity")
                    handler.post {
                        runCatching {
                            sendBroadcast(
                                Intent(PhoneMirrorActivity.ACTION_FINISH_MIRROR)
                                    .setPackage(packageName)
                            )
                        }
                    }
                }
            )
            Log.i(TAG, "subscribe(${AiChannel.TOPIC_STOP_PHONE_MIRROR}) -> $stopMirrorResult")

            // 图片下发：把手机端对话气泡里的图片显示到眼镜端（悬浮图片层，12s 后自动隐藏）。
            // 与歌词/工具确认复用同一套悬浮层授权（SYSTEM_ALERT_WINDOW）。
            val showImageResult = bridge?.subscribe(
                AiChannel.TOPIC_SHOW_IMAGE,
                CXRServiceBridge.MsgCallback { _, args, _ ->
                    markDownlink()
                    handleShowImage(args)
                }
            )
            Log.i(TAG, "subscribe(${AiChannel.TOPIC_SHOW_IMAGE}) -> $showImageResult")

            // 打开页面：手机端要求把某个眼镜端 Activity 拉到前台（如说「显示歌词」→ 系统音乐页）。
            val openAppResult = bridge?.subscribe(
                AiChannel.TOPIC_OPEN_APP,
                CXRServiceBridge.MsgCallback { _, args, _ ->
                    markDownlink()
                    handleOpenApp(args)
                }
            )
            Log.i(TAG, "subscribe(${AiChannel.TOPIC_OPEN_APP}) -> $openAppResult")

            // 服务就绪：主动握手通告一次（手机端据此免探测获知眼镜端能力）
            announceHello()
        } catch (e: Exception) {
            Log.e(TAG, "initCxrBridge failed", e)
        }
    }

    // ──────────────────────────────────────────────
    //  断线自愈（cxr-service 分发路由 stale 探测）
    // ──────────────────────────────────────────────

    // ──────────────────────────────────────────────
    //  协议握手（插播 B · LinkProtocol v2）
    // ──────────────────────────────────────────────

    /**
     * 上报本端协议版本与能力位（caps = [version, capsBitmask, linkVersion]）。
     *
     * 手机端据此免探测获知眼镜端能力；旧版手机端会忽略未知 topic，无副作用。
     * 服务就绪时主动调用一次，并对 [LinkProtocol.TOPIC_HELLO_REQ] 应答。
     */
    internal fun announceHello() {
        val b = bridge ?: run {
            Log.w(TAG, "announceHello skipped: no bridge")
            return
        }
        runCatching {
            val linkVersion = runCatching {
                packageManager.getPackageInfo(packageName, 0).versionName
            }.getOrNull() ?: "-"
            val caps = Caps()
            caps.write(LinkProtocol.PROTOCOL_VERSION.toString())
            caps.write(LinkProtocol.Cap.ALL.toString())
            caps.write(linkVersion)
            val r = b.sendMessage(LinkProtocol.TOPIC_HELLO, caps)
            Log.i(TAG, "hello sent: version=${LinkProtocol.PROTOCOL_VERSION} caps=0x${LinkProtocol.Cap.ALL.toString(16)} linkVersion=$linkVersion -> $r")
        }.onFailure { Log.e(TAG, "announceHello failed", it) }
    }

    /** 记录一次下行活性：任意经 CXR bridge 订阅收到的消息都证明
     *  cxr-service → 本 App 的分发路由健康（断线重连后可能 stale）。 */
    private fun markDownlink() {
        lastDownlinkMs = System.currentTimeMillis()
    }

    /** 重连成功（此前断线过）后启动观察：SELF_HEAL_CHECK_DELAY_MS 后检查
     *  期间是否收到过下行。路由 stale 时 cxr-service 不再投递任何订阅消息，
     *  进程内重建 bridge 无法恢复（实测仅进程重启有效），故经 Alarm 自杀重启。 */
    private fun scheduleSelfHealCheck() {
        cancelSelfHealCheck()
        selfHealCheck = Runnable {
            selfHealCheck = null
            runSelfHealCheck()
        }
        mainHandler.postDelayed(selfHealCheck!!, SELF_HEAL_CHECK_DELAY_MS)
        Log.i(TAG, "Self-heal check armed in ${SELF_HEAL_CHECK_DELAY_MS}ms (reconnectAt=$reconnectAtMs)")
    }

    private fun cancelSelfHealCheck() {
        selfHealCheck?.let { mainHandler.removeCallbacks(it) }
        selfHealCheck = null
    }

    /** 观察窗口到期判定：bridge 仍连接 且 窗口内无任何下行 → 路由 stale，重启进程 */
    private fun runSelfHealCheck() {
        selfHealCheck = null
        if (!bridgeConnected) {
            Log.i(TAG, "Self-heal check: bridge disconnected, skip (will re-arm on reconnect)")
            return
        }
        if (lastDownlinkMs >= reconnectAtMs) {
            Log.i(TAG, "Self-heal check: downlink healthy (last=$lastDownlinkMs reconnectAt=$reconnectAtMs)")
            return
        }
        Log.w(TAG, "Self-heal check: NO downlink since reconnect — cxr-service route stale, restarting process")
        selfHealRestart()
    }

    /** AlarmManager 延迟 SELF_HEAL_RESTART_DELAY_MS 后触发 SelfRestartReceiver：
     *  Handler.postDelayed 在进程自杀后不存活，必须用系统级 Alarm（RTC_WAKEUP）确保广播可投递。
     *  双闹钟设计：① 自杀闹钟（3s）→ 接收器杀进程，由 START_STICKY/显式启动重建订阅；
     *  ② 兜底拉起闹钟（+20s）→ 若粘性重启失败，Alarm 唤醒新进程显式启动两个常驻服务。 */
    private fun selfHealRestart() {
        try {
            val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val now = System.currentTimeMillis()
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val killPi = PendingIntent.getBroadcast(this, 0,
                Intent(this, SelfRestartReceiver::class.java).setAction(ACTION_SELF_HEAL_RESTART), flags)
            am.set(AlarmManager.RTC_WAKEUP, now + SELF_HEAL_RESTART_DELAY_MS, killPi)
            val bootPi = PendingIntent.getBroadcast(this, 1,
                Intent(this, SelfRestartReceiver::class.java).setAction(ACTION_SELF_HEAL_BOOTSTRAP), flags)
            am.set(AlarmManager.RTC_WAKEUP,
                now + SELF_HEAL_RESTART_DELAY_MS + SELF_HEAL_BOOTSTRAP_DELAY_MS, bootPi)
            Log.w(TAG, "Self-heal: process restart scheduled (kill+${SELF_HEAL_RESTART_DELAY_MS}ms, " +
                "bootstrap+${SELF_HEAL_RESTART_DELAY_MS + SELF_HEAL_BOOTSTRAP_DELAY_MS}ms)")
        } catch (e: Exception) {
            Log.e(TAG, "selfHealRestart failed", e)
        }
    }

    // ──────────────────────────────────────────────
    //  Ai 频道消息分发与本地接管
    // ──────────────────────────────────────────────

    /**
     * 处理 "Ai" 频道消息（官方 AI 链路，手机端 → 眼镜端）。
     *
     * 流程：
     *   ASR_Result（流式文字）→ 覆盖式累积
     *   ASR_End → 上行 Exit 关闭官方会话（停止乐奇显示/播报）+ 上行文字给手机 Lab 回复
     */
    // args 声明为可空：Caps 来自 Java 层，理论上可能被传 null，
    // 写成非空类型时 `args == null` 恒假（编译器已指出），防御分支永远不会走到。
    private fun handleAiChannel(args: Caps?) {
        try {
            if (args == null || args.size() < 1 || args.at(0) == null) return
            val cmd = args.at(0).getString() ?: return
            when (cmd) {
                "ASR_Result" -> {
                    // 下行过滤：Lab 回复下行序列中的 ASR_Result 不做拦截逻辑（避免自反馈），
                    // 但必须**本机重放**给官方界面 —— 手机下行的 Ai 只到我们，官方收不到（见 relayAiToOfficial）
                    if (System.currentTimeMillis() < downlinkUntilMs) {
                        val t = args.at(1)
                            ?.takeIf { it.type() == Caps.Value.TYPE_STRING }?.getString()
                        if (!t.isNullOrBlank()) relayAiToOfficial("ASR_Result", t)
                        Log.d(TAG, "AI ASR_Result (downlink) -> relayed: ${t?.take(30)}")
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
                    // 下行过滤：Lab 回复下行序列中的 ASR_End 不做拦截逻辑，但本机重放给官方界面
                    if (System.currentTimeMillis() < downlinkUntilMs) {
                        relayAiToOfficial("ASR_End")
                        Log.d(TAG, "AI ASR_End (downlink) -> relayed")
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
                    Log.i(TAG, "AI ASR complete: $finalText")
                    // 用户已开口（能走到这里说明不是 Lab 下行重发——那些已被 downlinkUntilMs 拦掉）：
                    // 撤掉排队中的自动续听任务，避免它稍后插进来把这新一轮抢掉
                    cancelPendingContinueDialog()

                    // 所有命令统一接管（含歌词命令）：
                    // 之前对"歌词"命令放行官方（期望官方打开 music_word 歌词场景），
                    // 实测官方 AI 处理"歌词"命令会打开歌词场景并 Force stop RokidLink
                    // （third_app 场景清理），进程死亡后隧道/ASR/按键全断（15:47、16:01
                    // 两次实测均强杀）。歌词显示不依赖官方 music_word 场景——手机端把歌词
                    // 写入 MediaSession 后经蓝牙 AVRCP 直接到眼镜系统 MusicPageActivity。
                    // 因此歌词命令也必须打断官方，让官方 AI 不进入命令处理逻辑，才不会被强杀。
                    // 打断次数限制：窗口内达到上限则暂停拦截，让官方自然完成回复（打破循环）
                    if (!allowInterrupt()) {
                        Log.w(TAG, "AI ASR_End ignored (interrupt limit reached)")
                        return
                    }
                    // 官方 ASR_End 后约 1 秒内即开始 TTS 播报（离线问候语），
                    // 手机端 ADB 极速轮询也需 ~0.5s+。先尝试眼镜端本地立即打断（实测返回码）。
                    interruptOfficialLocally()
                    // 本地接管显示：官方会话被打断后界面会残留"思考中"等待（约 3s 直到手机端轮询+下行重开会话）。
                    // 这里立即本地重开会话并显示提问，官方界面立刻切到 Lab 会话等待（第二次思考中，可接受），
                    // 手机端读到文字后只需下行 DeepSeek 回复（TTS_Result），不再重发会话序列。
                    // 串行执行器：连续提问时避免多个接管任务并发导致指令交错
                    // execute 前必须查 isShutdown：onDestroy 已 shutdownNow，此后 CXR 回调
                    // 仍在途中时 execute 会抛 RejectedExecutionException —— 它在 execute 调用处
                    // （不在传入的 lambda 里）抛出，执行线程是 CXR 回调线程，未捕获即崩进程。
                    if (takeoverExecutor.isShutdown) {
                        Log.w(TAG, "local takeover skipped: executor already shutdown")
                    } else {
                        runCatching {
                            takeoverExecutor.execute {
                                try {
                                    openAiSession()
                                    showAiUserText(finalText)
                                } catch (e: Exception) {
                                    Log.e(TAG, "local takeover error", e)
                                }
                            }
                        }.onFailure { Log.w(TAG, "local takeover rejected: ${it.message}") }
                    }
                    // ASR 文字双通道暴露：
                    //   1) logcat（AiAsrBridge tag）——诊断用
                    //   2) RFCOMM 推送通道（AsrPushServer 长连接，毫秒级）——主通道
                    //   3) 文件（app 私有外部目录）——推送失败时的兜底（手机端轮询读取）
                    Log.i(AI_ASR_BRIDGE_TAG, "ASR_TEXT:$finalText")
                    // 先推送「ASR 识别完成」信号：官方已识别完并下发 ASR_End（此刻打断官方安全），
                    // 手机端收到该信号才打断官方 AI（替代 onGlassAppResume 固定 800ms 的提前打断，
                    // 消除官方识别未完成就被掐断导致 ASR_End 永不产生的竞态）。
                    // 再推文字（主通道）+ 文件兜底。两帧顺序：信号在前、文字在后，手机端按序消费。
                    AsrPushServer.pushControl(AsrPushServer.CTRL_ASR_READY)
                    if (!AsrPushServer.push(finalText)) {
                        Log.w(TAG, "ASR push channel unavailable, fallback to file")
                        appendAiAsrToFile(finalText)
                    }
                }
                "TTS_Result" -> {
                    // Lab/AI 回复正文：本机注入回**官方对话界面**（v3.0 `c2484b2` 的既有做法）。
                    // 手机下行的这帧只到本应用、官方 AssistServer 收不到 —— 这就是「有声音没文字」的根因。
                    //
                    // ⚠️ 该频道是**广播式**的：官方 App 自己的回复正文也从这里经过
                    //（我们订阅 `Ai` 本就是为拦截官方 ASR，见 subscribe(AI_TOPIC)）。
                    // 因此不能无条件注入 —— 官方在 `Exit` 后仍会回流一段它自己的回复，
                    // 注入进去就会在重开界面时被渲染成「官方的字」。见 [officialEchoUntilMs]。
                    val t = args.at(1)
                        ?.takeIf { it.type() == Caps.Value.TYPE_STRING }?.getString()
                    if (t.isNullOrBlank()) {
                        Log.d(TAG, "TTS_Result without text payload")
                    } else if (System.currentTimeMillis() < officialEchoUntilMs) {
                        Log.w(TAG, "TTS_Result dropped (official echo within interrupt window): ${t.take(40)}")
                    } else {
                        showAiReply(t)
                    }
                }
                "TTS_AudioFinished", "Ai_Heartbeat" -> {
                    // 官方乐奇收尾/心跳：忽略（界面即将被 Exit 关闭）
                }
                "KeyDown_Client" -> {
                    // Lab 回复完整下行序列（KeyDown_Client→open→ASR_Result→ASR_End）的标志：
                    // 开启下行过滤窗口，过滤其中的重发 ASR_Result/ASR_End。
                    // 注意：单独的「Ai/open」（手机端 interruptOfficialAi 打断官方）不触发过滤，
                    // 否则会误吞用户紧随其后的真实提问（open 后 1~2s 官方 ASR_End 到达）。
                    downlinkUntilMs = System.currentTimeMillis() + DOWNLINK_FILTER_MS
                    Log.d(TAG, "Downlink flag: $cmd, filter until ${downlinkUntilMs}")
                    // 本机重放：官方对话界面的打开依赖本机注入（手机下行到不了官方）
                    relayAiToOfficial("KeyDown_Client", "{\"privacy_level\":2}")
                }
                // 官方 AI 会话打开：手机端 interruptOfficialAi / 下行序列中的 open。
                // 本机重放 open：官方对话界面由此打开（本应用已无常驻 Activity，不存在 third_app 强杀问题）。
                "open" -> {
                    Log.i(TAG, "AI channel open — relay to official")
                    relayAiToOfficial("open")
                }
                // 官方 AI 会话结束（手机端下行 Exit 关闭官方会话）：无需处理。
                "Exit" -> {
                    Log.d(TAG, "AI channel Exit")
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
        // 连续对话时每一轮用户说话都要消耗一次打断额度（这是正常用法，不是异常循环）：
        // 沿用 20s/3 会让第 4 句起被静默拒绝（现象：「前三句正常，之后喊了没反应」），
        // 故开启时换用更宽的 60s/12。它仍是硬上限 —— 异常自反馈循环最多多跑 9 轮即自停。
        val continuous = isContinueDialogEnabled(this)
        val windowMs = if (continuous) INTERRUPT_WINDOW_CONTINUOUS_MS else INTERRUPT_WINDOW_MS
        val maxCount = if (continuous) INTERRUPT_MAX_CONTINUOUS else INTERRUPT_MAX
        if (now - lastInterruptMs > windowMs) {
            interruptCount = 0
        }
        lastInterruptMs = now
        interruptCount++
        val allowed = interruptCount <= maxCount
        if (!allowed) {
            Log.w(TAG, "allowInterrupt denied: $interruptCount > $maxCount in ${windowMs}ms (continuous=$continuous)")
        }
        return allowed
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
        // 官方此刻已生成、仍在途的回复正文（TTS_Result）必须在本频道被抑制：
        // 它随着 Exit 的 dismissAiDialog/clearData 已无处渲染，若被我们当作 Lab 回复
        // 注入进官方适配器，会在随后 openAiSession() 重开界面时显形为「官方的字」。
        officialEchoUntilMs = System.currentTimeMillis() + OFFICIAL_ECHO_WINDOW_MS
        // 1) 尝试 CXR 上行 Ai/Exit（0ms 起，300ms 重试一次）
        val r1 = sendAi("Exit")
        Log.i(TAG, "interruptOfficialLocally: sendAi(Exit) -> $r1")
        if (r1 != 0) {
            mainHandler.postDelayed({
                val r2 = sendAi("Exit")
                Log.i(TAG, "interruptOfficialLocally: sendAi(Exit) retry -> $r2")
            }, 300)
        }
        // 2) 尝试系统双击广播：独立线程 + 超时销毁。
        //    不能同步执行：am broadcast 在 ASR_End 回调线程偶发挂起（readText 等待子进程 EOF），
        //    会卡死后继的 push/文件兜底，导致 ASR 文字整条丢失（实测"显示歌词"卡死于此）。
        Thread {
            try {
                // shell 内用 2>&1 合并 stderr 到 stdout，只读单流：先读 stdout 再读 stderr 可能因管道缓冲占满而死锁
                val p = Runtime.getRuntime().exec(arrayOf(
                    "sh", "-c",
                    "am broadcast -a com.android.action.ACTION_SPRITE_BUTTON_DOUBLE_CLICK 2>&1"
                ))
                if (p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
                    val out = p.inputStream.bufferedReader().use { it.readText() }
                    Log.i(TAG, "interruptOfficialLocally: am broadcast -> ${out.trim().take(120)}")
                } else {
                    Log.w(TAG, "interruptOfficialLocally: am broadcast timeout, destroying")
                    p.destroy()
                }
            } catch (e: Exception) {
                Log.e(TAG, "interruptOfficialLocally am error", e)
            }
        }.apply { name = "am-broadcast-dblclick"; isDaemon = true; start() }
    }

    /** 发送 Ai 频道指令（caps[0] = 命令，后续为参数）。
     *  带 2s 超时：CXR sendMessage 在蓝牙断/半开时可能无限阻塞（实测 open 卡死导致
     *  takeoverExecutor 与回调线程双双失联、后续 ASR 全走官方），必须超时保护调用线程。 */
    private fun sendAi(cmd: String, vararg values: String): Int {
        val b = bridge ?: return -1
        return try {
            val f = aiSendExecutor.submit<Int> {
                val caps = Caps()
                caps.write(cmd)
                values.forEach { caps.write(it) }
                b.sendMessage(AI_TOPIC, caps)
            }
            f.get(2, TimeUnit.SECONDS)
        } catch (e: java.util.concurrent.TimeoutException) {
            Log.w(TAG, "sendAi($cmd) timeout (CXR channel blocked)")
            -1
        } catch (e: Exception) {
            Log.e(TAG, "sendAi($cmd) error", e)
            -1
        }
    }

    // ──────────────────────────────────────────────
    //  眼镜 WiFi IP 上行（眼镜端 → 手机端，免手动输入）
    // ──────────────────────────────────────────────

    /** 获取当前 WiFi 的 IPv4 地址（仅限 TRANSPORT_WIFI 网络，排除蜂窝/回环/区域后缀）。
     *  未连 WiFi 或尚在获取中返回 null；与 MainActivity.getIPAddress 思路一致但精确限定 WiFi 接口。 */
    private fun getWiFiIpAddress(): String? {
        return try {
            // 复用 MainActivity.getIPAddress 的可靠思路：遍历 NetworkInterface，取首个 IPv4 非回环地址。
            // 直接用 Collections.list 把 Enumeration 转 List，避免 Kotlin for 循环迭代器歧义；
            // 优先取 WiFi 接口（wlan*/wifi*），否则取首个可用 IPv4（眼镜无蜂窝，唯一激活接口即 WiFi）。
            val intfs = java.util.Collections.list(NetworkInterface.getNetworkInterfaces())
            var fallback: String? = null
            for (intf in intfs) {
                if (!intf.isUp || intf.isLoopback) continue
                for (ia in intf.interfaceAddresses) {
                    val addr = ia.address ?: continue
                    if (addr.isLoopbackAddress || addr !is Inet4Address) continue
                    val ip = addr.hostAddress?.substringBefore('%') ?: continue
                    if (intf.name?.startsWith("wlan") == true || intf.name?.contains("wifi", ignoreCase = true) == true) {
                        return ip
                    }
                    if (fallback == null) fallback = ip
                }
            }
            fallback
        } catch (e: Exception) {
            Log.e(TAG, "getWiFiIpAddress failed", e)
            null
        }
    }

    /** 注册 WiFi 网络回调：WiFi 可用/获得 internet 能力/链路属性变化时上行眼镜 IP */
    private fun registerWifiIpReporter() {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        connectivityManager = cm
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.i(TAG, "WIFI available — reporting glasses IP")
                sendGlassesIp()
            }
            override fun onCapabilitiesChanged(network: Network, networkCaps: NetworkCapabilities) {
                if (networkCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                    sendGlassesIp()
                }
            }
            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
                sendGlassesIp()
            }
            override fun onLost(network: Network) {
                Log.i(TAG, "WIFI lost — reset last reported IP")
                lastReportedIp = null
            }
        }
        wifiIpReporter = cb
        try {
            cm.registerNetworkCallback(request, cb)
            Log.i(TAG, "Wifi IP reporter registered")
            // 注册即上报一次：若此刻已连 WiFi 可立即让手机端拿到 IP
            sendGlassesIp()
        } catch (e: Exception) {
            Log.e(TAG, "registerWifiIpReporter failed", e)
        }
    }

    /** 反注册 WiFi 网络回调（onDestroy 调用） */
    private fun unregisterWifiIpReporter() {
        runCatching { wifiIpReporter?.let { connectivityManager?.unregisterNetworkCallback(it) } }
        wifiIpReporter = null
        connectivityManager = null
    }

    /** 上行眼镜 WiFi IP：经 CXR-S 通道发往手机端。带 2s 超时保护 + IP 去重（同 IP 不重复上行）。 */
    private fun sendGlassesIp() {
        val ip = getWiFiIpAddress()
        if (ip.isNullOrEmpty()) {
            Log.d(TAG, "sendGlassesIp: no WiFi IPv4 yet, skip")
            return
        }
        if (ip == lastReportedIp) {
            Log.d(TAG, "sendGlassesIp: IP unchanged ($ip), skip")
            return
        }
        val b = bridge ?: run {
            Log.w(TAG, "sendGlassesIp: bridge not ready, will retry on connect")
            return
        }
        val caps = Caps()
        AiChannel.encodeGlassesIp(ip).forEach { caps.write(it) }
        try {
            val f = aiSendExecutor.submit<Int> { b.sendMessage(AiChannel.TOPIC_GLASSES_IP, caps) }
            val r = f.get(2, TimeUnit.SECONDS)
            if (r == 0) {
                lastReportedIp = ip
                Log.i(TAG, "sendGlassesIp($ip) -> ok")
            } else {
                Log.w(TAG, "sendGlassesIp($ip) -> $r (not cached)")
            }
        } catch (e: java.util.concurrent.TimeoutException) {
            Log.w(TAG, "sendGlassesIp($ip) timeout (CXR channel blocked)")
        } catch (e: Exception) {
            Log.e(TAG, "sendGlassesIp($ip) error", e)
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

    /**
     * 在**官方聊天界面**显示 Lab 回复正文（`TTS_Result` 本机注入）。
     *
     * 这是 v3.0 (`c2484b2`) 的既有做法；v3.1 移除后改成"手机回 `TTS_Result`"，
     * 但手机下行的 `Ai` 消息在眼镜上只投递到本应用（拦截订阅），官方 AssistServer 收不到，
     * 于是退化成「有声音、没文字」。这里恢复本机注入：与 [showAiUserText] 同一机制即可显示。
     *
     * 注意：**不在这里播 TTS** —— 播报由手机端下发的 `tts_play` 负责，避免重复播报。
     */
    private fun showAiReply(reply: String) {
        val r = sendAi("TTS_Result", reply)
        Log.i(TAG, "showAiReply: TTS_Result=$r text=${reply.take(40)}")
    }

    /**
     * 把手机端下行的官方协议指令**本机重放**给官方对话界面。
     *
     * 手机 → 眼镜的 CXR `Ai` 消息只投递到本应用（我们订阅它是为了拦截官方 ASR），
     * 官方 AssistServer 收不到；而眼镜本机 `sendAi()` 能进官方链路（v3.0 已验证）。
     * 所以要显示在官方界面的内容，都必须由我们本机再发一次。
     */
    private fun relayAiToOfficial(cmd: String, vararg values: String): Int {
        val r = sendAi(cmd, *values)
        Log.i(TAG, "relay($cmd) -> $r${values.firstOrNull()?.let { " (${it.take(24)})" } ?: ""}")
        return r
    }

    /** 当前对话模型模式是否为自定义（Lab 拦截并回复）；official 模式放行官方乐奇 */
    private fun isCustomAiMode(): Boolean =
        getSharedPreferences(PREFS_NAME, 0)
            .getString(KEY_AI_MODE, AiChannel.AI_MODE_CUSTOM)
            .orEmpty()
            .let { if (it.isBlank()) AiChannel.AI_MODE_CUSTOM else it } == AiChannel.AI_MODE_CUSTOM

    // ──────────────────────────────────────────────
    //  AiChannel 版本化订阅回调处理
    // ──────────────────────────────────────────────

    /** 接收手机端下发的 AI 配置（baseUrl/apiKey/model/mode）并持久化。
     *  载荷经 AiChannel 版本化编解码：cmd 不符/版本不支持/长度不足时整体丢弃（防错位写入）。 */
    private fun handleAiConfig(args: Caps) {
        try {
            markDownlink()
            val cfg = AiChannel.decodeAiConfig(capsToStrings(args)) ?: run {
                Log.w(TAG, "handleAiConfig: rejected invalid/unsupported payload (size=${args.size()})")
                return
            }
            val prefs = getSharedPreferences(PREFS_NAME, 0)
            prefs.edit()
                .putString(KEY_AI_BASE_URL, cfg.baseUrl)
                .putString(KEY_AI_MODEL, cfg.model)
                .putString(KEY_AI_MODE, cfg.mode)
                .apply()
            // API Key 走 Keystore 加密落盘（prefs 里只有密文，防止眼镜端被读取后拿到明文凭据）
            SecretStore.put(prefs, KEY_AI_API_KEY, cfg.apiKey)
            Log.i(TAG, "AI config saved: baseUrl=${cfg.baseUrl} model=${cfg.model} " +
                "keyLen=${cfg.apiKey.length} mode=${cfg.mode}")
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
                markDownlink()
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

    /**
     * 最近一次下发的 Lab 回复正文。
     * 自动续听时会重开官方会话（界面被重置），用它把刚展示过的回复补回官方界面，
     * 让用户既能接着说下一句、又能看着上一轮的答案。仅内存持有，不落盘。
     */
    @Volatile
    private var lastLabReply: String? = null

    /** 待执行的「自动续听」延时任务；null = 当前没有排队中的续听 */
    @Volatile
    private var pendingContinueDialog: Runnable? = null

    /** 收到手机端文字消息后，调用眼镜本地 TTS 播放语音（AiChannel v1 编解码，兼容 v0） */
    private fun handleTtsPlay(args: Caps) {
        try {
            val text = AiChannel.decodeTtsPlay(capsToStrings(args)) ?: run {
                Log.w(TAG, "handleTtsPlay: rejected invalid/unsupported payload (size=${args.size()})")
                return
            }
            Log.i(TAG, "Received tts_play: ${text.take(40)}...")
            if (text.isNotBlank()) {
                // 新一轮播报覆盖上一轮：先撤掉上一轮遗留的续听任务（本轮播完会重新调度）
                cancelPendingContinueDialog()
                lastLabReply = text
                // 播完回调 = 连续对话（多轮免唤醒）的触发点，见 [onLabReplyPlaybackFinished]
                TtsPlaybackHelper.play(this, text) { onLabReplyPlaybackFinished() }
            }
        } catch (e: Exception) {
            Log.e(TAG, "handleTtsPlay error", e)
        }
    }

    /**
     * Lab 回复的本地 TTS「真正播完」回调（TtsPlaybackHelper 在最后一块收到 ITtsListener.onTtsStop
     * 之后触发，不是按时长估算）。
     *
     * 这是「连续对话（多轮免唤醒）」的触发点：播完即代表本轮双端文字都已显示、语音已播放完毕，
     * 此刻请手机端让官方重开拾音，用户不用再喊唤醒词，直接说下一句即可。
     *
     * 决策放在眼镜端（只有它拿得到真实播放结束时刻；手机端下发 `tts_play` 后没有播放进度），
     * 但**执行**必须由手机端完成 —— 详见 [requestOfficialContinueListening]。
     *
     * 为什么还要延时 [CONTINUE_DIALOG_DELAY_MS]：停播瞬间扬声器仍有尾音，
     * 立刻开麦会被自己的尾音喂进一次误识别。
     */
    private fun onLabReplyPlaybackFinished() {
        if (!isCustomAiMode()) {
            Log.i(TAG, "continue dialog: skipped (official ai mode)")
            return
        }
        if (!isContinueDialogEnabled(this)) {
            Log.i(TAG, "continue dialog: skipped (switch off)")
            return
        }
        cancelPendingContinueDialog()
        val task = Runnable { runContinueDialog() }
        pendingContinueDialog = task
        mainHandler.postDelayed(task, CONTINUE_DIALOG_DELAY_MS)
        Log.i(TAG, "continue dialog: scheduled in ${CONTINUE_DIALOG_DELAY_MS}ms")
    }

    /** 撤销尚未执行的自动续听（用户已开口 / 新一轮播报覆盖 / 开关关闭 / 服务销毁） */
    private fun cancelPendingContinueDialog() {
        pendingContinueDialog?.let { mainHandler.removeCallbacks(it) }
        pendingContinueDialog = null
    }

    /**
     * 让官方重新开始拾音 —— 连续对话能成立的**关键一步**。由**手机端**代发（见下）。
     *
     * ⚠️ 只重开界面是不够的：`openAiSession()`（KeyDown_Client + open）只把 ai_assist
     * 场景/对话界面拉起来，**麦克风并不会开始拾音**。用户实测「等 lab 显示并播放完
     * 我在说话 没反应」，日志里重开之后再没出现过任何 `AI ASR stream` —— 人说了，没人听。
     *
     * 官方自己的续听链路是（`_g_proto_trace.md` 真机实测）：
     *   `TTS_AudioFinished` → AssistServer `AudioFinishedHandler` → `aiAudioFinishWake`
     *   → `AIModeManager.startNewTalk`（重新开始拾音）
     * 而手机端为了让刚显示的 Lab 回复不被清屏，一直传 `skipTtsAudioFinished=true`
     * **主动放弃了**它（见 `AiConversationService.sendAiTextViaLink` 注释）。
     *
     * ⚠️⚠️ **这一帧绝不能在眼镜端本机 `sendAi` 发出**（v1 试过，实测 21:07:32 官方毫无反应）：
     * 真机 trace 显示 `AudioFinishedHandler` 只被 `[wire] recv cmd=Ai`（**入站**：手机→眼镜）
     * 触发；眼镜本机 `sendAi` 是**出站**帧，走 `[wire] cmd=Ai caps=...`，官方自己的分发器
     * 收不到（同理 `KeyDown_Client`/`open` 等本机 sendAi 也不会进官方链路）。
     * 所以本方法改为：**推 RFCOMM 控制标记上行给手机**，由手机经 CXR `Ai` 频道下发
     * `TTS_AudioFinished`（对眼镜而言是入站 → 官方必然走 `AudioFinishedHandler`）。
     *
     * 为什么仍由眼镜端决定「何时」：只有它拿得到 TTS 真实播放结束时刻
     *（`TtsPlaybackHelper` 的 `onFinished` ← `ITtsListener.onTtsStop`），
     * 手机端下发 `tts_play` 后只有「已发出」，没有播放进度。
     */
    private fun requestOfficialContinueListening() {
        val ok = AsrPushServer.pushControl(LinkProtocol.MARKER_CONTINUE_DIALOG)
        Log.i(TAG, "continue dialog: continue-dialog marker -> $ok (phone will send TTS_AudioFinished)")
    }

    /**
     * 执行自动续听：请手机端下发 `TTS_AudioFinished` 让官方重开麦，并把上一轮回复文字补回界面。
     *
     * 执行前重查一遍开关与链路状态——排队期间用户可能已手动唤醒并开始说话
     *（那条路径的 ASR_End 拦截会 cancel 本任务），这里是双保险。
     */
    private fun runContinueDialog() {
        pendingContinueDialog = null
        if (!isCustomAiMode() || !isContinueDialogEnabled(this)) return
        // 手机端下行序列（KeyDown_Client→open→…→TTS_Result）仍在途中时不要抢链路，
        // 让本轮下行自然走完（下一次播完还会再调度）。
        if (System.currentTimeMillis() < downlinkUntilMs) {
            Log.i(TAG, "continue dialog: postponed (downlink in progress)")
            return
        }
        if (takeoverExecutor.isShutdown) {
            Log.w(TAG, "continue dialog skipped: executor already shutdown")
            return
        }
        runCatching {
            // 复用本地接管的串行执行器：下面含 sleep，
            // 与 ASR_End 的本地接管互斥排队，避免两条会话序列指令交错。
            takeoverExecutor.execute {
                try {
                    Log.i(TAG, "continue dialog: asking phone to reopen mic for next turn")
                    // 关键：让**手机端**下发 TTS_AudioFinished，触发官方续听链路（重开拾音）。
                    // 本机 sendAi 发这帧官方收不到（出站帧），实测无效，见方法注释。
                    requestOfficialContinueListening()
                    // 手机端要经 RFCOMM 上行 + CXR 下行一个来回，官方才走完
                    // startNewTalk/showAudioFinishUI，等它落定再补文字（见常量注释）。
                    Thread.sleep(CONTINUE_LISTEN_SETTLE_MS)
                    // 安全：TTS_Result 在官方侧只负责显示，播报由手机端 tts_play 驱动，不会二次发声。
                    lastLabReply?.takeIf { it.isNotBlank() }?.let { showAiReply(it) }
                    Log.i(TAG, "continue dialog: reopen requested via phone, waiting for user speech")
                } catch (e: Exception) {
                    Log.e(TAG, "continue dialog error", e)
                }
            }
        }.onFailure { Log.w(TAG, "continue dialog rejected: ${it.message}") }
    }

    private var lyricView: TextView? = null
    private var lyricWm: WindowManager? = null

    /**
     * 悬浮层可用性自检：`TYPE_APPLICATION_OVERLAY` 必须已授予 `SYSTEM_ALERT_WINDOW`，
     * 否则 `addView` 抛异常。
     *
     * ⚠️ 历史教训：原实现把 `addView` 包在 `runCatching {}` 里且**不打日志**，
     * 未授权时悬浮层静默加不上，表现为「歌词/对话文字不显示」但日志里查不到任何错误。
     * 现在改为显式检查 + 失败高声告警（授权由手机端经 ADB appops 下发）。
     */
    private fun canShowOverlay(): Boolean {
        val ok = runCatching { android.provider.Settings.canDrawOverlays(this) }.getOrDefault(false)
        if (!ok) {
            Log.e(TAG, "overlay NOT available: SYSTEM_ALERT_WINDOW not granted to $packageName " +
                "(手机端应经 adb appops set $packageName android:system_alert_window allow)")
        }
        return ok
    }

    /** 在眼镜端显示一个半透明悬浮歌词层，文本居中偏下。重复调用仅更新文本。 */
    private fun showLyricOverlay(text: String) {
        val view = lyricView ?: run {
            if (!canShowOverlay()) return
            val wmSafe = lyricWm ?: (getSystemService(Context.WINDOW_SERVICE) as? WindowManager)
                .also { lyricWm = it } ?: return
            val tv = TextView(this@KeyButtonService).apply {
                textSize = 22f
                setTextColor(0xFFFFFFFF.toInt())
                setShadowLayer(4f, 2f, 2f, 0xFF000000.toInt())
                setPadding(28, 18, 28, 18)
                setBackgroundColor(0xCC000000.toInt())
                gravity = Gravity.CENTER
            }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                y = 90
            }
            val added = runCatching { wmSafe.addView(tv, params) }
            added.onFailure { Log.e(TAG, "addView(lyric overlay) failed", it) }
            if (added.isFailure) return
            tv.also { lyricView = it }
        }
        view.text = text
        Log.i(TAG, "lyric overlay: ${text.take(40)}")
    }

    /** 移除悬浮歌词层（文本为空/停止播放时调用）。 */
    private fun hideLyricOverlay() {
        lyricView?.let { tv ->
            runCatching { lyricWm?.removeView(tv) }
            lyricView = null
            lyricWm = null
        }
        Log.i(TAG, "lyric overlay hidden")
    }

    // ──────────────────────────────────────────────
    //  图片显示层（手机端对话里的图片 → 眼镜端）
    // ──────────────────────────────────────────────

    /** 图片悬浮层自动隐藏时间 */
    private val IMAGE_OVERLAY_MS = 12_000L

    private var imageContainer: LinearLayout? = null
    private var imageView: ImageView? = null
    private var imageCaption: TextView? = null
    private var imageWm: WindowManager? = null
    private val hideImageRunnable = Runnable { hideImageOverlay() }

    /**
     * 手机端下发图片（[AiChannel.TOPIC_SHOW_IMAGE]）：Base64 JPEG → Bitmap → 居中悬浮图片层，
     * 12s 后自动隐藏；重复下发只换图不重建视图。
     *
     * 复用歌词/工具确认同一套悬浮层授权（`SYSTEM_ALERT_WINDOW`，由手机端经 ADB appops 下发）。
     */
    private fun handleShowImage(args: Caps?) {
        try {
            val decoded = AiChannel.decodeShowImage(capsToStrings(args))
            if (decoded == null) {
                Log.w(TAG, "handleShowImage: rejected invalid payload (size=${args?.size()})")
                return
            }
            val (b64, caption) = decoded
            Log.i(TAG, "Received show_image: base64Len=${b64.length} caption='${caption.take(30)}'")
            handler.post {
                val bytes = runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrNull()
                if (bytes == null) {
                    Log.e(TAG, "handleShowImage: base64 decode failed (len=${b64.length})")
                    return@post
                }
                val bmp = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
                if (bmp == null) {
                    Log.e(TAG, "handleShowImage: bitmap decode failed (bytes=${bytes.size})")
                    return@post
                }
                showImageOverlay(bmp, caption)
            }
        } catch (e: Exception) {
            Log.e(TAG, "handleShowImage error", e)
        }
    }

    /**
     * 手机端要求打开某个眼镜端页面（[AiChannel.TOPIC_OPEN_APP]）：直接 startActivity 拉起目标
     * Activity（须 exported=true）。典型用途：说「显示歌词」→ 拉起系统音乐页
     * `com.rokid.os.sprite.launcher/.page.music.MusicPageActivity`（该页会随 AVRCP 元数据逐行显示歌词）。
     *
     * 注意：**不能复用 [launchTarget]** —— 它优先用 `getLaunchIntentForPackage(pkg)`，对 launcher
     * 这类包会返回 HOME 意图（拉起桌面而非目标页），必须用显式 ComponentName 直启。
     *
     * 本服务持有 SYSTEM_ALERT_WINDOW（BAL 法定豁免，手机端经 ADB appops 授予），
     * 从后台 startActivity 不受 Android 12+ 限制。
     */
    private fun handleOpenApp(args: Caps?) {
        try {
            val decoded = AiChannel.decodeOpenApp(capsToStrings(args))
            if (decoded == null) {
                Log.w(TAG, "handleOpenApp: rejected invalid payload (size=${args?.size()})")
                return
            }
            val (pkg, activity) = decoded
            val fullAct = if (activity.startsWith(".")) "$pkg$activity" else activity
            Log.i(TAG, "Received open_app: $pkg/$fullAct")
            handler.post {
                runCatching {
                    val intent = Intent().apply {
                        component = android.content.ComponentName(pkg, fullAct)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(intent)
                    Log.i(TAG, "open_app launched: $pkg/$fullAct")
                }.onFailure {
                    Log.e(TAG, "open_app launch failed: ${it::class.simpleName}: ${it.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "handleOpenApp error", e)
        }
    }

    /** 显示/更新悬浮图片层（同一实例复用）。 */
    private fun showImageOverlay(bmp: Bitmap, caption: String) {
        val container = imageContainer ?: run {
            if (!canShowOverlay()) return
            val wmSafe = imageWm ?: (getSystemService(Context.WINDOW_SERVICE) as? WindowManager)
                .also { imageWm = it } ?: return
            val iv = ImageView(this@KeyButtonService).apply {
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            val tv = TextView(this@KeyButtonService).apply {
                textSize = 18f
                setTextColor(0xFFFFFFFF.toInt())
                setShadowLayer(4f, 2f, 2f, 0xFF000000.toInt())
                gravity = Gravity.CENTER
                setPadding(0, 14, 0, 0)
            }
            val box = LinearLayout(this@KeyButtonService).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(24, 20, 24, 20)
                setBackgroundColor(0xE6000000.toInt())
                addView(iv)
                addView(tv)
            }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.CENTER }
            val added = runCatching { wmSafe.addView(box, params) }
            added.onFailure { Log.e(TAG, "addView(image overlay) failed", it) }
            if (added.isFailure) return
            imageView = iv
            imageCaption = tv
            box.also { imageContainer = it }
        }
        // 限宽 520px：眼镜屏宽有限，超宽图片会被裁切
        val maxW = 520
        val scaled = if (bmp.width > maxW) {
            val h = (bmp.height * (maxW.toFloat() / bmp.width)).toInt().coerceAtLeast(1)
            runCatching { Bitmap.createScaledBitmap(bmp, maxW, h, true) }.getOrDefault(bmp)
        } else {
            bmp
        }
        imageView?.setImageBitmap(scaled)
        imageCaption?.apply {
            text = caption
            visibility = if (caption.isBlank()) View.GONE else View.VISIBLE
        }
        container.requestLayout()
        handler.removeCallbacks(hideImageRunnable)
        handler.postDelayed(hideImageRunnable, IMAGE_OVERLAY_MS)
        Log.i(TAG, "image overlay: ${scaled.width}x${scaled.height} caption='${caption.take(30)}'")
    }

    /** 移除悬浮图片层。 */
    private fun hideImageOverlay() {
        handler.removeCallbacks(hideImageRunnable)
        imageContainer?.let { box ->
            runCatching { imageWm?.removeView(box) }
            imageContainer = null
            imageView = null
            imageCaption = null
            imageWm = null
            Log.i(TAG, "image overlay hidden")
        }
    }

    // ──────────────────────────────────────────────
    //  工具确认窗口（Phase 4 确认闸门的眼镜端交互）
    // ──────────────────────────────────────────────

    /**
     * 手机端副作用工具的确认请求：悬浮层显示操作摘要 + TTS 播报，
     * 短按 = 允许，双击/长按 = 取消，30s 无操作超时视为取消。
     * 结果经 [LinkProtocol.TOPIC_TOOL_CONFIRM_RESULT] 上行回手机端。
     */
    private fun handleToolConfirm(args: Caps?) {
        try {
            val f = capsToStrings(args)
            val id = f.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return
            val tool = f.getOrNull(1) ?: ""
            val summary = f.getOrNull(2) ?: ""
            handler.post {
                clearToolConfirm()
                pendingToolConfirmId = id
                showLyricOverlay("⚠ $summary\n[短按]允许  [双击]取消")
                if (summary.isNotBlank()) {
                    runCatching { TtsPlaybackHelper.play(this, "是否$summary？短按确认，双击取消") }
                }
                toolConfirmTimeoutRunnable = Runnable {
                    Log.i(TAG, "tool confirm timeout (id=$id) -> deny")
                    respondToolConfirm(false, timeout = true)
                }.also { handler.postDelayed(it, TOOL_CONFIRM_WINDOW_MS) }
                Log.i(TAG, "tool confirm pending: id=$id tool=$tool")
            }
        } catch (e: Exception) {
            Log.e(TAG, "handleToolConfirm error", e)
        }
    }

    /** 应答确认结果并清理窗口（confirm/deny/timeout 共用）。 */
    private fun respondToolConfirm(allowed: Boolean, timeout: Boolean = false) {
        val id = pendingToolConfirmId ?: return
        clearToolConfirm()
        // UP/CLICK 连发吞没窗口：应答后 1.5s 内的按键广播全部忽略，
        // 避免同一次按压的第二条广播落到「启动配置目标」上
        suppressKeyUntilMs = System.currentTimeMillis() + KEY_SUPPRESS_AFTER_CONFIRM_MS
        val b = bridge
        runCatching {
            if (b != null) {
                val caps = Caps()
                caps.write(id)
                caps.write(if (allowed) "yes" else "no")
                val r = b.sendMessage(LinkProtocol.TOPIC_TOOL_CONFIRM_RESULT, caps)
                Log.i(TAG, "toolConfirm respond(id=$id, allowed=$allowed, timeout=$timeout) -> $r")
            } else {
                Log.w(TAG, "toolConfirm respond(id=$id) dropped: no bridge")
            }
        }.onFailure { Log.e(TAG, "toolConfirm respond error", it) }
    }

    /** 仅清理窗口状态与 UI（不清 suppress 窗口）。 */
    private fun clearToolConfirm() {
        toolConfirmTimeoutRunnable?.let { handler.removeCallbacks(it) }
        toolConfirmTimeoutRunnable = null
        pendingToolConfirmId = null
        hideLyricOverlay()
    }

    /** 接收手机端下发的「按键答题」开关状态并持久化（AiChannel 版本化编解码） */
    private fun handleQuizConfig(args: Caps) {
        try {
            markDownlink()
            val enabled = AiChannel.decodeQuizConfig(capsToStrings(args)) ?: run {
                Log.w(TAG, "handleQuizConfig: rejected invalid/unsupported payload (size=${args.size()})")
                return
            }
            getSharedPreferences(PREFS_NAME, 0).edit()
                .putBoolean(KEY_QUIZ_ENABLED, enabled)
                .apply()
            Log.i(TAG, "Quiz config saved: enabled=$enabled")
        } catch (e: Exception) {
            Log.e(TAG, "handleQuizConfig error", e)
        }
    }

    /**
     * 保存手机端下发的「连续对话（多轮免唤醒）」开关。
     *
     * 落 prefs 后 [isContinueDialogEnabled] 立即生效（无需重连/重启服务，行为类开关即时生效）。
     * 关闭时同时撤销排队中的自动续听 —— 用户可能恰好在播报结束的瞬间把开关关掉。
     */
    private fun handleContinueDialogConfig(args: Caps) {
        try {
            val enabled = AiChannel.decodeContinueDialog(capsToStrings(args)) ?: run {
                Log.w(TAG, "handleContinueDialogConfig: rejected invalid/unsupported payload (size=${args.size()})")
                return
            }
            getSharedPreferences(PREFS_NAME, 0).edit()
                .putBoolean(KEY_CONTINUE_DIALOG, enabled)
                .apply()
            if (!enabled) cancelPendingContinueDialog()
            Log.i(TAG, "Continue dialog config saved: enabled=$enabled")
        } catch (e: Exception) {
            Log.e(TAG, "handleContinueDialogConfig error", e)
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
        // 答题全程保持屏幕唤醒：熄屏会触发 AI 会话退出（closeCamera），
        // 导致对话窗口关闭 + 手机端 takePhoto 无图超时（见 QUIZ_SCREEN_AWAKE_MS 注释）。
        keepScreenAwakeForQuiz()
        val b = bridge
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
                val result = sendCxrWithTimeout(b, PHOTO_ASK_TOPIC, caps)
                Log.i(TAG, "sendMessage($PHOTO_ASK_TOPIC) -> $result")

                // 3. Sys 频道上行：模拟系统级 Sys_App_Resume_Change 事件，
                //    AI App 对 Sys 事件无条件转发（IAiEventCallback.onGlassAppResumeChange），
                //    手机端 SDK 据此回调 onGlassAppResume(true) 触发打断官方回复。
                //    注意：手机端已不再用该回调触发拍照（无法区分真实 resume），仅用于打断 AI。
                val sysCaps = Caps()
                sysCaps.write("Sys_App_Resume_Change")
                sysCaps.write("com.rokidlab.rokidlink")
                val sysResult = sendCxrWithTimeout(b, LinkProtocol.CXR_CHANNEL_SYS, sysCaps)
                Log.i(TAG, "sendMessage(Sys/Sys_App_Resume_Change) -> $sysResult")
            } catch (e: Throwable) {
                // 后台线程必须 Throwable 兜底：未捕获异常走默认 handler 会直接崩进程
                Log.e(TAG, "sendPhotoAskToPhone error", e)
            }
        }.apply { name = "photo-ask-send"; isDaemon = true }.start()
    }

    /**
     * CXR sendMessage 的超时保护包装。
     *
     * 与 [sendAi] 同源风险：蓝牙断/半开时 sendMessage 可能无限阻塞，
     * 调用线程会被永久挂住。所有在按键路径上的 sendMessage 都必须走这里，不要裸调。
     */
    private fun sendCxrWithTimeout(b: CXRServiceBridge, topic: String, caps: Caps): Int = try {
        aiSendExecutor.submit<Int> { b.sendMessage(topic, caps) }.get(2, TimeUnit.SECONDS)
    } catch (e: java.util.concurrent.TimeoutException) {
        Log.w(TAG, "sendMessage($topic) timeout (CXR channel blocked)")
        -1
    } catch (e: Throwable) {
        Log.e(TAG, "sendMessage($topic) error", e)
        -1
    }

    // ──────────────────────────────────────────────
    //  AIUI 自托管宿主（.aix 接收 / open / close / msg）
    // ──────────────────────────────────────────────

    /** 启动 .aix 接收服务：收到完整包后自动拉起 AiuiLinkActivity 渲染 */
    private fun startAiuiHostServer() {
        if (aiuiPkgServer?.isRunning == true) return
        val server = AiuiPackageServer(listener = object : AiuiPackageServer.Listener {
            override fun onPackageReceived(file: File) {
                aiuiLastFile = file
                Log.i(TAG, "AIUI package received: ${file.name} -> open host")
                handler.post { openAiuiHost(file) }
            }
        })
        aiuiPkgServer = server
        if (!server.start(this)) {
            Log.e(TAG, "AIUI package server failed to start on 7658")
        }
    }

    /** AIUI_HOST_TOPIC 指令：open [fileName?] / close / msg [json] */
    private fun handleAiuiHost(args: Caps) {
        try {
            val f = capsToStrings(args)
            if (f.isEmpty() || f[0].isNullOrBlank()) return
            when (f[0]) {
                CMD_AIUI_OPEN -> {
                    val name = f.getOrNull(1)?.takeIf { it.isNotBlank() }
                    // caps[2] = 启动参数（JSON 对象字符串），随 open 一起下发：
                    // 必须走 open 而不是 open 之后补一条 msg —— 页面此刻尚未解包渲染，
                    // 任何 hostMessage 都会被 host.js 的 `if (!view) return` 静默丢弃。
                    val launchParams = f.getOrNull(2)?.takeIf { it.isNotBlank() }
                    val file = if (name != null) File(filesDir, "aiui_host/$name") else aiuiLastFile
                    if (file == null || !file.isFile) {
                        Log.w(TAG, "aiui open: no file to open ($name / last=${aiuiLastFile?.name})")
                        return
                    }
                    aiuiLastFile = file
                    handler.post { openAiuiHost(file, launchParams) }
                }
                CMD_AIUI_CLOSE -> handler.post { AiuiLinkActivity.closeActive() }
                CMD_AIUI_MSG -> {
                    val json = f.getOrNull(1)
                    if (!json.isNullOrBlank()) {
                        AiuiLinkActivity.dispatchMessageToActive(json)
                    }
                }
                else -> Log.w(TAG, "aiui host unknown cmd: ${f[0]}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "handleAiuiHost error", e)
        }
    }

    private fun openAiuiHost(file: File, launchParams: String? = null) {
        try {
            AiuiLinkActivity.open(this, file.absolutePath, launchParams)
        } catch (e: Exception) {
            Log.e(TAG, "open AiuiLinkActivity failed", e)
        }
    }

    private fun handleConfig(args: Caps) {
        try {
            val cfg = AiChannel.decodeKeyConfig(capsToStrings(args)) ?: run {
                Log.w(TAG, "handleConfig: rejected invalid/unsupported payload (size=${args.size()})")
                return
            }
            getSharedPreferences(PREFS_NAME, 0).edit()
                .putString(KEY_SHORT_PKG, cfg.shortPkg)
                .putString(KEY_SHORT_ACT, cfg.shortActivity)
                .putString(KEY_LONG_PKG, cfg.longPkg)
                .putString(KEY_LONG_ACT, cfg.longActivity)
                .apply()

            Log.i(TAG, "Config saved: short=${cfg.shortPkg}/${cfg.shortActivity}, " +
                "long=${cfg.longPkg}/${cfg.longActivity}")
        } catch (e: Exception) {
            Log.e(TAG, "handleConfig error", e)
        }
    }

    // ──────────────────────────────────────────────
    //  WiFi 使能 / 连接（wifi_config 历史兜底）
    // ──────────────────────────────────────────────

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
}
