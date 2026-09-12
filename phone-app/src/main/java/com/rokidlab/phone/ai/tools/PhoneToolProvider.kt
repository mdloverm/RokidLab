package com.rokidlab.phone.ai.tools

import android.content.Context
import android.util.Log
import com.rokidlab.phone.ai.AiuiAppRegistry
import com.rokidlab.phone.ai.AiuiProject
import com.rokidlab.phone.ai.Calculator
import com.rokidlab.phone.ai.KnowledgeBase
import com.rokidlab.phone.ai.KuwoMusicApi
import com.rokidlab.phone.ai.LocationTools
import com.rokidlab.phone.ai.MusicPlayerController
import com.rokidlab.phone.ai.PhoneTools
import com.rokidlab.phone.ai.ToolRegistry
import com.rokidlab.phone.ai.WeatherTools
import com.rokidlab.phone.ai.WebTools
import com.rokidlab.phone.app.LabApplication
import com.rokidlab.phone.adb.ui.TimerAction
import com.rokidlab.phone.adb.ui.TimerSchedule
import com.rokidlab.phone.adb.ui.TimerTask
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * PhoneToolProvider —— 手机域（通讯录/拨号/闹钟/应用/状态/音量/日历）。
 * Phase 4 从 `ToolRegistry.execute` 迁出的该域工具执行分支（逐字搬运，行为不变）。
 */
internal object PhoneToolProvider : ToolProvider {
    private const val TAG = "PhoneToolProvider"

    override val toolNames = setOf(
        "search_contacts",
        "call_phone",
        "set_phone_alarm",
        "open_phone_app",
        "get_phone_status",
        "set_phone_volume",
        "query_calendar",
        "add_calendar_event",
    )

    override fun execute(context: Context, name: String, args: JSONObject): String {
        return when (name) {
            "search_contacts" -> {
                val name = args.optString("name").trim()
                if (name.isEmpty()) return "请提供要查找的联系人姓名"
                if (androidx.core.content.ContextCompat.checkSelfPermission(
                        context, android.Manifest.permission.READ_CONTACTS
                    ) != android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    return "需要通讯录权限：请打开手机「设置 → 应用 → RokidLab → 权限」，开启「通讯录」后重试"
                }
                val hits = PhoneTools.searchContacts(context, name)
                if (hits.isEmpty()) {
                    "通讯录中没有找到「$name」"
                } else {
                    hits.mapIndexed { i, c -> "[${i + 1}] ${c.name}：${c.number}" }.joinToString("\n")
                }
            }

            "call_phone" -> PhoneTools.dialPhone(context, args.optString("contact"))

            "set_phone_alarm" -> PhoneTools.setPhoneAlarm(
                context,
                message = args.optString("message", "闹钟"),
                hour = if (args.has("hour") && !args.isNull("hour")) args.optInt("hour") else null,
                minute = if (args.has("minute") && !args.isNull("minute")) args.optInt("minute") else null,
                minutesFromNow = if (args.has("minutesFromNow") && !args.isNull("minutesFromNow")) args.optLong("minutesFromNow") else null,
            )

            "open_phone_app" -> PhoneTools.openPhoneApp(context, args.optString("appName"))

            "get_phone_status" -> PhoneTools.getPhoneStatus(context)

            "set_phone_volume" -> PhoneTools.setPhoneVolume(context, args.optInt("volume", 50))

            "query_calendar" -> PhoneTools.queryCalendar(context, args.optString("date"))

            "add_calendar_event" -> PhoneTools.addCalendarEvent(
                context,
                title = args.optString("title"),
                date = args.optString("date"),
                startTime = args.optString("startTime"),
                durationMinutes = args.optInt("durationMinutes", 60),
                note = args.optString("note").trim().ifBlank { null },
            )

        else -> throw IllegalArgumentException("未知工具: $name")
        }
    }
}
