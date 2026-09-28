package com.rokidlab.phone.proactive

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import com.rokidlab.phone.R
import com.rokidlab.phone.app.LabApplication
import com.rokidlab.phone.glasses.MotionBuffer
import kotlin.math.sqrt

private const val ACTION_VISION_TICK = "com.rokidlab.phone.proactive.VISION_TICK"
private const val PI_RC = 2001

/** IMU 移动判定窗口 */
private const val MOTION_WINDOW_MS = 30_000L

/**
 * 走走拍拍（环境视觉，主动式陪伴 #6）：外出/游览时偶尔拍照识别周围场景并随口解说。
 *
 * 隐私设计（与常驻感知划清界限）：
 *  - **显式开关**：设置页手动开启 = 进入会话；关闭即停；绝不默认开
 *  - **移动门控**：IMU 30s 窗口判「在行走/游览」才拍（静止=开会/吃饭，不拍）；
 *    IMU 数据缺失也判静止，宁可不拍
 *  - **系统节奏**：固定 15 分钟一拍（RTC_WAKEUP 一次性闹钟链，拍完排下一拍），
 *    准入只查总闸/静默冷却/免打扰（不占闲聊额度）
 *  - **执行边界**：会话开启期间 [ToolRegistry.unattendedToolNames] 才放行
 *    `look_at_view`（眼镜拍照），轮次走无人值守链路（TTS 播报 + 本地通知，不落历史）
 */
class AmbientVisionController private constructor(private val appContext: Context) {
    companion object {
        private const val TAG = "AmbientVision"
        private const val PREFS = "proactive_vision"
        private const val K_ENABLED = "enabled"

        @Volatile
        private var instance: AmbientVisionController? = null

        fun get(context: Context): AmbientVisionController =
            instance ?: synchronized(this) {
                instance ?: AmbientVisionController(context.applicationContext).also { instance = it }
            }

        /**
         * 会话开关的静态镜像：[ToolRegistry.unattendedToolNames] 据此决定是否放行
         * look_at_view。App 启动即构造本单例（LabApplication.onCreate），时序安全。
         */
        @Volatile
        private var sessionActiveCache: Boolean = false

        fun isSessionActive(): Boolean = sessionActiveCache
    }

    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    init {
        // 进程重启后从 prefs 恢复会话镜像：ToolRegistry 放行 look_at_view 依赖此标志，
        // 不恢复的话重装/进程被杀重启后「开关开着」但模型永远看不到 look_at_view
        sessionActiveCache = prefs.getBoolean(K_ENABLED, false)
        if (sessionActiveCache) Log.i(TAG, "session restored from prefs")
    }

    private val tickReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_VISION_TICK) onTick()
        }
    }

    private fun tickPendingIntent(): PendingIntent = PendingIntent.getBroadcast(
        appContext, PI_RC,
        Intent(ACTION_VISION_TICK).setPackage(appContext.packageName),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun isEnabled(): Boolean = prefs.getBoolean(K_ENABLED, false)

    /** 设置页开关：开 = 进入会话并登记第一拍；关 = 撤闹钟 + 收回 look_at_view 放行 */
    fun setEnabled(on: Boolean) {
        prefs.edit().putBoolean(K_ENABLED, on).apply()
        sessionActiveCache = on
        Log.i(TAG, "walk-and-look session ${if (on) "started" else "stopped"}")
        if (on) start() else stop()
    }

    /**
     * 登记/重排下一拍（幂等，兼作 reschedule）：一次性 RTC_WAKEUP 闹钟（Doze 下也触发），
     * 到点后由 [onTick] 链式重排。拍摄节奏固定 15 分钟一拍（[ProactiveGatePolicy.VISION_INTERVAL_MS]）。
     * 进程被杀后闹钟仍在但动态 receiver 已失联 → 该轮丢失，
     * 由 Application.onCreate 的 [start] 重建链路（与定时任务同一生命周期保证）。
     */
    fun start() {
        registerReceiverIfNeeded()
        try {
            val am = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            if (!isEnabled()) {
                am.cancel(tickPendingIntent())
                Log.i(TAG, "session off, vision alarm cancelled")
                return
            }
            val intervalMs = ProactiveGatePolicy.VISION_INTERVAL_MS
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + intervalMs, tickPendingIntent())
            Log.i(TAG, "vision alarm armed, next in ${intervalMs / 60000} min")
        } catch (e: Exception) {
            Log.w(TAG, "arm failed: ${e.message}")
        }
    }

    fun stop() {
        runCatching {
            (appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(tickPendingIntent())
        }
        Log.i(TAG, "vision alarm cancelled")
    }

    /** 到点：重排下一拍 → 门控（眼镜在线/移动中/冷却免打扰）→ 派发一轮拍照解说 */
    fun onTick(nowMs: Long = System.currentTimeMillis()) {
        start() // 无论本轮拍不拍，先保住下一拍
        MobileMotionTracker.ensureStarted(appContext) // 手机加速度计兜底采样（幂等）
        if (!isEnabled()) return
        val app = appContext as? LabApplication
        if (app == null || !app.hasCxrL()) {
            Log.i(TAG, "skip: cxrL not ready")
            return
        }
        val glassMags = MotionBuffer.recent(MOTION_WINDOW_MS)
            .map { s -> sqrt(s.ax * s.ax + s.ay * s.ay + s.az * s.az) }
        // 兜底：眼镜 IMU 流未开启（HeadImuService 默认关）时 MotionBuffer 为空，
        // 改用手机加速度计判定移动，否则「走了半小时」也永远 skip: not moving
        val mags = if (glassMags.size >= 10) glassMags else MobileMotionTracker.recent(MOTION_WINDOW_MS)
        if (!ProactiveGatePolicy.isMoving(mags)) {
            // 打出波动 RMS 供阈值校准（去均值口径与 isMoving 一致）
            val devRms = if (mags.isEmpty()) 0f else sqrt(
                mags.fold(0f) { s, v -> val d = v - 9.8f; s + d * d } / mags.size,
            )
            Log.i(TAG, "skip: not moving (n=${mags.size}, devRms=$devRms)")
            return
        }
        if (!ProactiveGate.get(appContext).ambientVisionAllowed(nowMs)) {
            Log.i(TAG, "skip: gate suppress (cooldown/quiet)")
            return
        }
        val prompt = "（系统触发，非用户输入）用户开启了「走走拍拍」模式且正在户外/途中。" +
            "第一步必须直接调用 look_at_view 工具看眼前的场景，调用工具前不要输出任何文字；" +
            "拿到画面后用一两句话自然地解说，像同行的朋友随口聊一句；" +
            "发现有意思的东西可以多说一句。不要列要点，不要每次都用同一个开头。"
        app.timerScheduler.runProactiveAgentTask(
            prompt = prompt,
            failureNotice = false,
            titleOverride = appContext.getString(R.string.settings_ambient_vision),
        )
        Log.i(
            TAG,
            "vision round dispatched (lookVisible=" +
                com.rokidlab.phone.ai.ToolRegistry.unattendedToolNames()
                    .contains(com.rokidlab.phone.ai.tools.VisionToolProvider.TOOL_LOOK) +
                ", sessionActive=$sessionActiveCache)",
        )
    }

    private var receiverRegistered = false

    private fun registerReceiverIfNeeded() {
        if (receiverRegistered) return
        if (Build.VERSION.SDK_INT >= 33) {
            appContext.registerReceiver(tickReceiver, IntentFilter(ACTION_VISION_TICK), Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            appContext.registerReceiver(tickReceiver, IntentFilter(ACTION_VISION_TICK))
        }
        receiverRegistered = true
    }
}
