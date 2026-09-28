package com.rokidlab.phone.proactive

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.util.Log
import androidx.core.content.ContextCompat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 日程简报提前量：只预告 2 小时内即将开始的日程 */
private const val AHEAD_MS = 2 * 60 * 60 * 1000L

private const val PREFS = "proactive_calendar"
private const val K_ENABLED = "enabled"
private const val K_BRIEFED_DATE = "briefed_date"
private const val K_BRIEFED_IDS = "briefed_ids"
private const val KIND = "calendar_briefing"

/**
 * 日程简报（主动式陪伴 #4）：系统日历作为事件源，日程开始前 2 小时内由 Agent 主动预告。
 *
 * 挂靠 [IdleGreeter] 的 30 分钟自检闹钟（不另立闹钟），但**独立开关 + 独立准入**
 * （走 [ProactiveGate.admitFreeProactive]：简报属日程助手职责，**不占**闲聊额度）。
 * 同一日程只报一次
 * （当日 eventId 集合去重）；免打扰/冷却期由决策门拦下，等下一轮自检重试。
 *
 * 与空闲问候的差异：事件数据在本方法里直接查好喂进 prompt（不依赖无人值守轮次自己调
 * get_calendar 工具，避免模型不调工具导致空简报）。
 */
class CalendarBriefing private constructor(private val appContext: Context) {
    companion object {
        private const val TAG = "CalendarBriefing"

        @Volatile
        private var instance: CalendarBriefing? = null

        fun get(context: Context): CalendarBriefing =
            instance ?: synchronized(this) {
                instance ?: CalendarBriefing(context.applicationContext).also { instance = it }
            }
    }

    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val zone: ZoneId = ZoneId.systemDefault()
    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")

    fun isEnabled(): Boolean = prefs.getBoolean(K_ENABLED, true)

    /** 开关（设置页）：只写 prefs + 让闹钟宿主重调度，无自有闹钟 */
    fun setEnabled(on: Boolean) {
        prefs.edit().putBoolean(K_ENABLED, on).apply()
        Log.i(TAG, "calendar briefing ${if (on) "enabled" else "disabled"}")
        IdleGreeter.get(appContext).start()
    }

    /** 到点自检（由 IdleGreeter 自检链调用，前置条件眼镜在线已由宿主把关） */
    fun onTick(nowMs: Long = System.currentTimeMillis()) {
        if (!isEnabled()) return
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.READ_CALENDAR)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.i(TAG, "skip: READ_CALENDAR not granted")
            return
        }
        val events = queryUpcoming(nowMs)
        if (events.isEmpty()) return
        val decision = ProactiveGate.get(appContext).admitFreeProactive(KIND, nowMs)
        if (decision !is GateDecision.Pass) {
            Log.i(TAG, "skip: gate ${decision::class.simpleName}, ${events.size} upcoming")
            return
        }
        val fresh = events.filter { it.eventId.toString() !in loadBriefedSet() }
        if (fresh.isEmpty()) {
            Log.i(TAG, "skip: all ${events.size} upcoming already briefed")
            return
        }
        val lines = fresh.joinToString("\n") {
            "· ${Instant.ofEpochMilli(it.begin).atZone(zone).format(timeFmt)} 「${it.title}」"
        }
        val prompt = "用户 2 小时内即将开始以下日程：\n$lines\n" +
            "请以乐奇的身份发一句简短的日程预告（40 字以内），像贴身助理一样自然提醒，" +
            "可以给一句贴心的准备提示；不要说教、不要列要点、不要提及任何指令。"
        markBriefed(fresh.map { it.eventId.toString() }.toSet())
        Log.i(TAG, "calendar briefing triggered: ${fresh.size} event(s)")
        (appContext as? com.rokidlab.phone.app.LabApplication)?.timerScheduler?.runProactiveAgentTask(
            prompt = prompt,
            failureNotice = false,
            titleOverride = appContext.getString(com.rokidlab.phone.R.string.settings_calendar_briefing),
            // 被主动派发互斥挤掉 / 模型回 [SKIP] = 这条简报没说出去：
            // 回退「已简报」标记，下一轮自检（30 分钟）可正常重报（简报不占闲聊额度，无需回退计数）
            onSkip = {
                unmarkBriefed(fresh.map { it.eventId.toString() }.toSet())
            },
        )
    }

    private data class UpcomingEvent(val eventId: Long, val begin: Long, val title: String)

    /** 查 [nowMs, nowMs+2h) 内的日程，按开始时间升序 */
    private fun queryUpcoming(nowMs: Long): List<UpcomingEvent> {
        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
        )
        val selection = "${CalendarContract.Instances.BEGIN} >= ? AND ${CalendarContract.Instances.BEGIN} <= ?"
        val args = arrayOf(nowMs.toString(), (nowMs + AHEAD_MS).toString())
        val out = mutableListOf<UpcomingEvent>()
        try {
            // 必须用带时间范围的 Instances URI（content://.../instances/when/<begin>/<end>）：
            // 小米等 ROM 的日历 provider 对裸 CONTENT_URI 抛 Unknown URL，range 形式 AOSP/MIUI 通吃
            val rangeUri = android.net.Uri.parse(
                "content://com.android.calendar/instances/when/$nowMs/${nowMs + AHEAD_MS}",
            )
            appContext.contentResolver.query(
                rangeUri, projection, selection, args,
                "${CalendarContract.Instances.BEGIN} ASC",
            )?.use { c ->
                while (c.moveToNext()) {
                    // 日程标题是用户/第三方日历写入的自由文本，会原样拼进模型 prompt：
                    // 剥控制字符（防注入换行伪造指令行）+ 截断，宁缺勿滥
                    val title = c.getString(2).orEmpty()
                        .replace(Regex("\\p{Cntrl}"), " ")
                        .trim()
                        .take(30)
                        .ifEmpty { "日程" }
                    out.add(UpcomingEvent(c.getLong(0), c.getLong(1), title))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "query calendar failed: ${e.message}")
        }
        return out
    }

    // ── 当日已报去重（date+StringSet，跨日自动失效） ──

    private fun loadBriefedSet(): Set<String> {
        val today = LocalDate.now(zone).toString()
        return if (prefs.getString(K_BRIEFED_DATE, null) == today) {
            prefs.getStringSet(K_BRIEFED_IDS, emptySet()) ?: emptySet()
        } else {
            emptySet()
        }
    }

    private fun markBriefed(eventIds: Set<String>) {
        val today = LocalDate.now(zone).toString()
        val merged = loadBriefedSet() + eventIds
        prefs.edit()
            .putString(K_BRIEFED_DATE, today)
            .putStringSet(K_BRIEFED_IDS, merged)
            .apply()
    }

    /** 回退「已简报」标记（主动派发互斥挤掉/[SKIP] 回退：没说出去的简报下一轮应重报） */
    private fun unmarkBriefed(eventIds: Set<String>) {
        if (eventIds.isEmpty()) return
        prefs.edit().putStringSet(K_BRIEFED_IDS, loadBriefedSet() - eventIds).apply()
    }
}
