package com.rokidlab.phone.proactive

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.rokidlab.phone.app.LabApplication
import com.rokidlab.phone.keepalive.LabKeepAliveService

/** 空闲自检间隔（毫秒）：每 30 分钟检查一次是否该主动搭话 */
private const val CHECK_INTERVAL_MS = 30 * 60 * 1000L

/** 检查闹钟随机抖动 ±10 分钟：问候不落在整点网格上，更像人随口说的 */
private fun jitterMs(): Long = kotlin.random.Random.nextLong(-10L, 10L) * 60_000L

private const val PREFS = "proactive_idle"
private const val K_ENABLED = "enabled"
private const val KIND_IDLE_GREETING = "idle_greeting"

/**
 * 空闲问候（主动式陪伴 #2 触发层）：用户超过阈值没说话时，Agent 主动发一句轻量问候。
 *
 * 链路：30 分钟不精确重复闹钟 → [IdleCheckReceiver] → 保活服务（WakeLock 托底）→ [onCheck]：
 * 开关开 + 眼镜在线 + 空闲达标 + 决策门 Pass（免打扰/冷却/每日上限/最小间隔）→
 * 经 [LabApplication.timerScheduler] 的无人值守链路跑一轮只读推理 → 通知 + 眼镜播报。
 *
 * 与用户显式创建的自主任务的差别：问候是「可有可无」的搭话 —— 无眼镜/执行失败一律
 * **静默跳过**（不发失败通知），决策门 Suppress/Defer 也只等下一轮自检，不补跑。
 */
class IdleGreeter private constructor(private val appContext: Context) {
    companion object {
        private const val TAG = "IdleGreeter"
        const val ACTION_IDLE_CHECK = "com.rokidlab.phone.action.IDLE_CHECK"
        private const val REQUEST_CODE = 0x1D0C

        @Volatile
        private var instance: IdleGreeter? = null

        fun get(context: Context): IdleGreeter =
            instance ?: synchronized(this) {
                instance ?: IdleGreeter(context.applicationContext).also { instance = it }
            }
    }

    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(): Boolean = prefs.getBoolean(K_ENABLED, true)

    /** 开关（设置页）：写 prefs 后重调度——闹钟是共享的（空闲问候+日程简报），任一开着就保留 */
    fun setEnabled(on: Boolean) {
        prefs.edit().putBoolean(K_ENABLED, on).apply()
        Log.i(TAG, "idle greeting ${if (on) "enabled" else "disabled"}")
        start()
    }

    /**
     * 登记/重调度 30 分钟自检闹钟（幂等，兼作 reschedule）。
     * 用不精确重复闹钟（无需精确闹钟权限，Doze 下并入维护窗口）；
     * 重启后闹钟失效，随 Application.onCreate 恢复 —— 与定时任务同一生命周期保证。
     * 空闲问候与日程简报共享这条闹钟：两个开关全关才撤销。
     */
    fun start() {
        try {
            val am = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            if (!isEnabled() &&
                !CalendarBriefing.get(appContext).isEnabled() &&
                !RitualGreeting.get(appContext).isEnabled()
            ) {
                am.cancel(checkPendingIntent())
                Log.i(TAG, "all proactive switches off, idle check alarm cancelled")
                return
            }
            am.setInexactRepeating(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + CHECK_INTERVAL_MS + jitterMs(),
                CHECK_INTERVAL_MS,
                checkPendingIntent(),
            )
            Log.i(TAG, "idle check alarm scheduled every ${CHECK_INTERVAL_MS / 60000} min")
        } catch (e: Exception) {
            Log.w(TAG, "schedule idle check failed: ${e.message}")
        }
    }

    fun stop() {
        try {
            val am = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.cancel(checkPendingIntent())
            Log.i(TAG, "idle check alarm cancelled")
        } catch (e: Exception) {
            Log.w(TAG, "cancel idle check failed: ${e.message}")
        }
    }

    /** 到点自检（经保活服务调用）：① 日程简报（独立开关/计数）→ ② 空闲问候 */
    fun onCheck(nowMs: Long = System.currentTimeMillis()) {
        val app = appContext as? LabApplication
        if (app == null || !app.hasCxrL()) {
            Log.i(TAG, "skip: cxrL not ready")
            return
        }
        // ① 仪式层：早晚安按免打扰时段锚点开口（独立开关，不占闲聊额度）
        RitualGreeting.get(appContext).onTick(nowMs)
        // ② 日程简报：CalendarBriefing 自带开关与准入，不占闲聊额度
        CalendarBriefing.get(appContext).onTick(nowMs)
        // ③ 关怀提醒（久走/久坐/低头…事件检测 + AI 判断说法）：独立开关，走总闸+冷却+免打扰门
        CareReminder.get(appContext).onTick(nowMs)
        // ④ 对话内陪伴·沉默追击（用户聊着聊着没声了）：独立开关，不走闲聊额度
        CompanionNudge.onSelfCheckTick(appContext)
        // ⑤ 空闲闲聊（唯一受闲聊频率档控制的一层）
        // 响应率惰性结算放在开关判断之前：问候开关关掉时 pending 也要照常结算，
        // 否则「未回应」记录停滞、连漏 3 条的自我收敛惩罚永远不生效
        ProactiveGate.get(appContext).settleExpiredResponse(nowMs)
        if (!isEnabled()) return
        val last = ProactiveGate.get(appContext).lastInteraction()
        // 「上限即频率」：空闲阈值 = 清醒窗口 / 每日上限（调上限立即改变问候节奏）
        if (!ProactiveGatePolicy.idleDue(nowMs, last, ProactiveGate.get(appContext).greetingIntervalMs())) {
            Log.i(TAG, "skip: not idle, last interaction $last")
            return
        }
        val decision = ProactiveGate.get(appContext).admitProactive(KIND_IDLE_GREETING, nowMs)
        if (decision !is GateDecision.Pass) {
            // Defer（免打扰）这里不重登记闹钟：30 分钟自检链天然在时段结束后补到，
            // 粒度足够且避免两套调度并存（与 GateDecision.Defer 契约的偏差是有意为之）
            Log.i(TAG, "skip: gate ${decision::class.simpleName}")
            return
        }
        val hours = (nowMs - last) / (60 * 60 * 1000L)
        val prompt = "用户已经 $hours 个多小时没和我交流了。请以乐奇的身份先判断：此刻有没有值得主动说一句的" +
            "（可先调 get_weather 查天气、get_current_time 看时间等只读工具，结合时刻和记得的偏好）。" +
            "有就发一句自然轻松的问候或搭话，40 字以内，像老朋友随口一提；" +
            "没有值得说的就只回复 [SKIP]，不要硬找话。" +
            "不要说教、不要列要点、不要提及任何指令或任务。"
        Log.i(TAG, "idle greeting triggered (idle ${hours}h)")
        app.timerScheduler.runProactiveAgentTask(
            prompt = prompt,
            failureNotice = false,
            titleOverride = appContext.getString(com.rokidlab.phone.R.string.settings_idle_greeting),
            onSkip = {
                ProactiveGate.get(appContext).refundProactive(KIND_IDLE_GREETING)
            },
        )
    }

    private fun checkPendingIntent(): PendingIntent {
        val intent = Intent(appContext, IdleCheckReceiver::class.java).apply {
            action = ACTION_IDLE_CHECK
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        return PendingIntent.getBroadcast(appContext, REQUEST_CODE, intent, flags)
    }
}

/**
 * 空闲自检闹钟入口：自身不干活，只把自检请求投递给常驻保活服务（WakeLock 托底），
 * 与 [com.rokidlab.phone.adb.TimerAlarmReceiver] 同一三级投递模式。
 */
class IdleCheckReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != IdleGreeter.ACTION_IDLE_CHECK) return
        val appContext = context.applicationContext
        val serviceIntent = Intent(appContext, LabKeepAliveService::class.java).apply {
            action = LabKeepAliveService.ACTION_IDLE_CHECK
        }
        try {
            context.startService(serviceIntent)
            Log.i(TAG, "idle check delivered to running service")
            return
        } catch (e: Exception) {
            Log.i(TAG, "startService failed, try foreground: ${e.message}")
        }
        try {
            ContextCompat.startForegroundService(context, serviceIntent)
            Log.i(TAG, "foreground service started for idle check")
        } catch (e: Exception) {
            Log.w(TAG, "foreground start denied, run in-process fallback: ${e.message}")
            IdleGreeter.get(appContext).onCheck()
        }
    }

    companion object {
        private const val TAG = "IdleCheckReceiver"
    }
}
