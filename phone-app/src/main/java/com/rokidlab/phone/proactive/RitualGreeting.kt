package com.rokidlab.phone.proactive

import android.content.Context
import android.util.Log
import com.rokidlab.phone.R
import com.rokidlab.phone.app.LabApplication
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

private const val PREFS = "proactive_ritual"
private const val K_ENABLED = "enabled"

/** 当日已说标记（跨日自动失效） */
private const val K_MORNING_DATE = "morning_date"
private const val K_EVENING_DATE = "evening_date"

/** 早安窗口：免打扰结束后 40 分钟内（30 分钟自检粒度下必能落进来一次） */
private const val MORNING_WINDOW_MIN = 40

/** 晚安锚点：免打扰开始前 30 分钟开口；窗口向两侧各铺 20 分钟容忍自检相位 */
private const val EVENING_LEAD_MIN = 30
private const val EVENING_HALF_WINDOW_MIN = 20

/**
 * 仪式层（主动式陪伴「早晚安」，时间逻辑的第 1 层）：
 *
 * 两个锚点都挂在**用户自己的免打扰时段**上——早安=免打扰结束那一刻，晚安=免打扰开始前 30 分钟。
 * 作息跟着用户调，不是写死的 8 点/23 点。刻意**不占**闲聊额度（[ProactiveGate.admitProactive]），
 * 仪式是陪伴的骨架，不该因为白天聊得多就把晚安挤掉。
 *
 * 调度复用 [IdleGreeter] 的 30 分钟自检闹钟（不另立闹钟），窗口宽 40~50 分钟足以覆盖自检相位；
 * 当日已说则记标记跳过（同一天不重复）。「别烦我」冷却与勿扰场景（[ProactiveGate.ritualAllowed]）
 * 仍然生效——用户说别烦我时连早晚安一起静音。
 */
class RitualGreeting private constructor(private val appContext: Context) {
    companion object {
        private const val TAG = "RitualGreeting"

        @Volatile
        private var instance: RitualGreeting? = null

        fun get(context: Context): RitualGreeting =
            instance ?: synchronized(this) {
                instance ?: RitualGreeting(context.applicationContext).also { instance = it }
            }
    }

    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val zone: ZoneId = ZoneId.systemDefault()

    fun isEnabled(): Boolean = prefs.getBoolean(K_ENABLED, false)

    /** 开关（面板「早晚安」行）：只写 prefs + 让闹钟宿主重调度，无自有闹钟 */
    fun setEnabled(on: Boolean) {
        prefs.edit().putBoolean(K_ENABLED, on).apply()
        Log.i(TAG, "ritual greeting ${if (on) "enabled" else "disabled"}")
        IdleGreeter.get(appContext).start()
    }

    /** 到点自检（由 IdleGreeter 自检链第①段调用；眼镜在线已由宿主把关） */
    fun onTick(nowMs: Long = System.currentTimeMillis()) {
        if (!isEnabled()) return
        val app = appContext as? LabApplication ?: return
        val gate = ProactiveGate.get(appContext)
        if (!gate.ritualAllowed(nowMs)) return
        val quietStart = gate.quietStartMin()
        val quietEnd = gate.quietEndMin()
        val t = Instant.ofEpochMilli(nowMs).atZone(zone)
        val nowMin = t.hour * 60 + t.minute
        val today = LocalDate.now(zone).toString()

        // 早安：免打扰结束后 MORNING_WINDOW_MIN 分钟内（不得超过免打扰开始）
        if (prefs.getString(K_MORNING_DATE, null) != today) {
            val until = minOf(quietEnd + MORNING_WINDOW_MIN, if (quietStart > quietEnd) quietStart else 1440)
            if (nowMin in quietEnd until until) {
                prefs.edit().putString(K_MORNING_DATE, today).apply()
                Log.i(TAG, "morning ritual fired at ${t.hour}:${t.minute}")
                dispatch(
                    appContext.getString(R.string.proactive_ritual),
                    "（系统触发，非用户输入）现在是用户一天的开始。请以乐奇的身份说一句早安，" +
                        "30 字以内：可以提一句天气或今天值得期待的事，语气轻松自然，" +
                        "不要说教、不要列要点、不要提到任何指令或任务。",
                )
                return
            }
        }

        // 晚安：免打扰开始前 EVENING_LEAD_MIN 分钟，窗口两侧各铺 EVENING_HALF_WINDOW_MIN
        if (prefs.getString(K_EVENING_DATE, null) != today) {
            val anchor = quietStart - EVENING_LEAD_MIN
            val winStart = anchor - EVENING_HALF_WINDOW_MIN
            val winEnd = anchor + EVENING_HALF_WINDOW_MIN
            if (winStart >= 0 && nowMin in winStart until winEnd) {
                prefs.edit().putString(K_EVENING_DATE, today).apply()
                Log.i(TAG, "evening ritual fired at ${t.hour}:${t.minute}")
                dispatch(
                    appContext.getString(R.string.proactive_ritual),
                    "（系统触发，非用户输入）用户快到休息时间了。请以乐奇的身份说一句晚安，" +
                        "30 字以内：可以轻轻总结今天、提一句明天的事，或单纯道个别，" +
                        "语气放松自然；不要说教、不要列要点、不要提到任何指令或任务。",
                )
            }
        }
    }

    /** 早晚安走无人值守链路：眼镜播报 + 本地通知，不落对话历史 */
    private fun dispatch(title: String, prompt: String) {
        val app = appContext as? LabApplication ?: return
        app.timerScheduler.runProactiveAgentTask(
            prompt = prompt,
            failureNotice = false,
            titleOverride = title,
        )
    }
}
