package com.rokidlab.phone.ai.tools

import com.rokidlab.phone.ai.ToolRisk

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
import com.rokidlab.phone.R
import com.rokidlab.phone.app.LabApplication
import com.rokidlab.phone.adb.ui.TimerAction
import com.rokidlab.phone.adb.ui.TimerSchedule
import com.rokidlab.phone.adb.ui.TimerTask
import com.rokidlab.phone.permission.AppPermission
import com.rokidlab.phone.permission.PermissionBridge
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
        "manage_calendar",
    )

    override fun tools(): List<ToolEntry> = listOf(
        ToolEntry(
            name = "search_contacts",
            group = ToolRegistry.DOMAIN_PHONE,
            displayNameRes = R.string.ai_tool_search_contacts_name,
            descriptionRes = R.string.ai_tool_search_contacts_desc,
            risk = ToolRisk.READ_ONLY,
            statusText = "正在查找联系人…",
            schema = toolSchema(
                name = "search_contacts",
                description = "在手机通讯录中按姓名查找联系人及其电话号码。当用户问「XX 的电话是多少」「XX 手机号」「我存的 XX 的号码」时调用。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "name" to mapOf("type" to "string", "description" to "联系人姓名，如「张三」"),
                    ),
                    "required" to listOf("name"),
                ),
            ),
        ),
        ToolEntry(
            name = "call_phone",
            group = ToolRegistry.DOMAIN_PHONE,
            displayNameRes = R.string.ai_tool_call_phone_name,
            descriptionRes = R.string.ai_tool_call_phone_desc,
            risk = ToolRisk.LOCAL_SIDE_EFFECT,
            sideEffect = true,
            statusText = "正在拨号…",
            summarize = { args ->
                            val who = args.optString("name").takeIf { it.isNotBlank() }
                                ?: args.optString("number").takeIf { it.isNotBlank() }
                                ?: args.optString("phone_number").takeIf { it.isNotBlank() }
                            if (!who.isNullOrBlank()) "拨打电话给 $who" else "拨打电话"
            },
            schema = toolSchema(
                name = "call_phone",
                description = "用手机**直接拨出**电话（已授电话权限即刻拨号，无需用户二次确认；未授权时退化为打开拨号盘）。参数可以是联系人姓名（自动查通讯录）或手机号。当用户说「给张三打电话」「拨打 138xxxx」「打电话给妈妈」时调用。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "contact" to mapOf("type" to "string", "description" to "联系人姓名（如「张三」）或完整手机号（如「13800138000」）"),
                    ),
                    "required" to listOf("contact"),
                ),
            ),
        ),
        ToolEntry(
            name = "set_phone_alarm",
            group = ToolRegistry.DOMAIN_PHONE,
            displayNameRes = R.string.ai_tool_set_phone_alarm_name,
            descriptionRes = R.string.ai_tool_set_phone_alarm_desc,
            risk = ToolRisk.LOCAL_SIDE_EFFECT,
            sideEffect = true,
            statusText = "正在设置手机闹钟…",
            summarize = { args -> "设置手机闹钟" },
            schema = toolSchema(
                name = "set_phone_alarm",
                description = "在手机上设置闹钟或倒计时（区别于眼镜端的定时提醒，本工具响铃在手机上）。当用户说「明早 7 点叫我起床」「30 分钟后手机闹我」「设个手机闹钟」时调用。「X分钟后提醒」若未强调手机，优先用眼镜定时任务（manage_timer 的 create，即 set_timer）。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "message" to mapOf("type" to "string", "description" to "闹钟标签/提醒内容，如「起床」「该出发了」"),
                        "hour" to mapOf("type" to "integer", "description" to "24 小时制小时（绝对时间闹钟必填，0-23），如 7"),
                        "minute" to mapOf("type" to "integer", "description" to "分钟（绝对时间闹钟必填，0-59），如 30"),
                        "minutesFromNow" to mapOf("type" to "integer", "description" to "相对分钟数（倒计时用，如「30分钟后」传 30；与 hour/minute 二选一）"),
                    ),
                ),
            ),
        ),
        ToolEntry(
            name = "open_phone_app",
            group = ToolRegistry.DOMAIN_PHONE,
            displayNameRes = R.string.ai_tool_open_phone_app_name,
            descriptionRes = R.string.ai_tool_open_phone_app_desc,
            risk = ToolRisk.LOCAL_SIDE_EFFECT,
            sideEffect = true,
            statusText = "正在打开手机应用…",
            summarize = { args -> "打开手机应用 ${args.optString("app_name").takeIf { it.isNotBlank() } ?: ""}".trim() },
            schema = toolSchema(
                name = "open_phone_app",
                description = "打开手机上安装的应用（微信/支付宝/相机等）。当用户说「打开手机上的微信」「帮我打开支付宝」且对象是手机应用时调用；打开眼镜上的应用请用 launch_glasses_app。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "appName" to mapOf("type" to "string", "description" to "要打开的手机应用名称，原样转述，如「微信」「支付宝」「设置」"),
                    ),
                    "required" to listOf("appName"),
                ),
            ),
        ),
        ToolEntry(
            name = "get_phone_status",
            group = ToolRegistry.DOMAIN_PHONE,
            displayNameRes = R.string.ai_tool_get_phone_status_name,
            descriptionRes = R.string.ai_tool_get_phone_status_desc,
            risk = ToolRisk.READ_ONLY,
            statusText = "正在查询手机状态…",
            schema = toolSchema(
                name = "get_phone_status",
                description = "查询手机当前状态：电量百分比、是否在充电、媒体音量、屏幕亮度。当用户问「手机还有多少电」「在充电吗」「音量多大」「手机什么状态」时调用。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf<String, Any>(),
                ),
            ),
        ),
        ToolEntry(
            name = "set_phone_volume",
            group = ToolRegistry.DOMAIN_PHONE,
            displayNameRes = R.string.ai_tool_set_phone_volume_name,
            descriptionRes = R.string.ai_tool_set_phone_volume_desc,
            risk = ToolRisk.LOCAL_SIDE_EFFECT,
            sideEffect = true,
            statusText = "正在调节手机音量…",
            schema = toolSchema(
                name = "set_phone_volume",
                description = "调节手机媒体音量（百分比）。当用户说「手机音量调到 50」「音量小一点/大一点」（对象是手机时）调用；「大一点/小一点」换算为比当前值高/低 20% 左右的具体数字。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "volume" to mapOf("type" to "integer", "description" to "目标音量百分比 0~100，如 50 表示一半"),
                    ),
                    "required" to listOf("volume"),
                ),
            ),
        ),
        ToolEntry(
            name = "manage_calendar",
            group = ToolRegistry.DOMAIN_PHONE,
            displayNameRes = R.string.ai_tool_manage_calendar_name,
            descriptionRes = R.string.ai_tool_manage_calendar_desc,
            risk = ToolRisk.LOCAL_SIDE_EFFECT,
            sideEffect = true,
            statusText = "正在处理日程…",
            summarize = { args -> if (args.optString("action").equals("create", true)) "添加日历日程" else "查询日历日程" },
            schema = toolSchema(
                name = "manage_calendar",
                description = "管理手机日历，一个工具管两种意图。action=\"query\"：查询某天的日程安排（「我明天有什么安排」「今天有什么日程」）。action=\"create\"：创建日程（「记一下明天下午3点开会」「加个日程：周五19点健身」）——title 与 startTime 必填，开始时间要具体到 HH:mm（用户只说「下午」按 15:00 估算并告知用户）。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "action" to mapOf("type" to "string", "enum" to listOf("query", "create"), "description" to "query=查询日程；create=创建日程"),
                        "date" to mapOf("type" to "string", "description" to "日期：today/tomorrow/今天/明天，或 YYYY-MM-DD；不传默认今天（create 时已过时刻自动顺延到明天）"),
                        "title" to mapOf("type" to "string", "description" to "action=create 时的日程标题，如「团队会议」"),
                        "startTime" to mapOf("type" to "string", "description" to "action=create 时的开始时间，24 小时制 HH:mm，如 14:30"),
                        "durationMinutes" to mapOf("type" to "integer", "description" to "action=create 时的时长（分钟），默认 60"),
                        "note" to mapOf("type" to "string", "description" to "action=create 时的备注（可选），如地点、参会人"),
                    ),
                    "required" to listOf("action"),
                ),
            ),
        ),
    )

    override fun execute(context: Context, name: String, args: JSONObject): String {
        return when (name) {
            "search_contacts" -> {
                val name = args.optString("name").trim()
                if (name.isEmpty()) return "请提供要查找的联系人姓名"
                // 缺权限不再只回一句"请去设置里开"：统一交给 PermissionBridge 自动拉起系统授权界面
                // （前台直接弹窗；后台有悬浮窗也能拉起；都不可用时退通知栏提醒并如实回报）
                PermissionBridge.ensure(
                    context,
                    context.getString(R.string.permission_reason_contacts),
                    AppPermission.CONTACTS,
                )?.let { return it }
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

            // 查询 / 创建二合一（原 query_calendar + add_calendar_event）：同一能力域，
            // 合并后模型在「改到明天吧」这类来回对话里不必两组 schema 反复挑。
            "manage_calendar" -> when (args.optString("action").trim().lowercase()) {
                "create" -> PhoneTools.addCalendarEvent(
                    context,
                    title = args.optString("title"),
                    date = args.optString("date"),
                    startTime = args.optString("startTime"),
                    durationMinutes = args.optInt("durationMinutes", 60),
                    note = args.optString("note").trim().ifBlank { null },
                )
                else -> PhoneTools.queryCalendar(context, args.optString("date"))
            }

        else -> throw IllegalArgumentException("未知工具: $name")
        }
    }
}
