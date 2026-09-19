package com.rokidlab.phone.ai.tools

import com.rokidlab.phone.R
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
 * InfoToolProvider —— 基础信息域（时间/天气/计算/定位）。
 * Phase 4 从 `ToolRegistry.execute` 迁出的该域工具执行分支（逐字搬运，行为不变）。
 */
internal object InfoToolProvider : ToolProvider {
    private const val TAG = "InfoToolProvider"

    override val toolNames = setOf(
        "get_current_time",
        "get_weather",
        "calculate",
        "get_location",
    )

    override fun tools(): List<ToolEntry> = listOf(
        ToolEntry(
            name = "get_current_time",
            group = ToolRegistry.DOMAIN_INFO,
            displayNameRes = R.string.ai_tool_get_current_time_name,
            descriptionRes = R.string.ai_tool_get_current_time_desc,
            risk = ToolRisk.READ_ONLY,
            statusText = "正在查看时间…",
            schema = toolSchema(
                name = "get_current_time",
                description = "获取当前的准确时间（日期、星期与 24 小时制时刻）。凡用户问「现在几点」「今天几号」「星期几」等时间问题时必须调用本工具，严禁自行推算或编造时间。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf<String, Any>(),
                ),
            ),
        ),
        ToolEntry(
            name = "get_weather",
            group = ToolRegistry.DOMAIN_WEB,
            displayNameRes = R.string.ai_tool_get_weather_name,
            descriptionRes = R.string.ai_tool_get_weather_desc,
            risk = ToolRisk.READ_ONLY,
            statusText = "正在查询天气…",
            schema = toolSchema(
                name = "get_weather",
                description = "查询指定城市的天气（实况温度/体感/湿度/风力 + 未来两天预报）。凡用户问「今天天气怎么样」「明天会下雨吗」「杭州冷不冷」等天气问题时必须调用本工具，严禁自行编造天气。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "city" to mapOf("type" to "string", "description" to "城市名称，如「杭州」「上海」；用户没说城市时问一句或按记忆中的常居城市"),
                        "date" to mapOf("type" to "string", "enum" to listOf("today", "tomorrow", "all"), "description" to "today=只报今天（默认），tomorrow=只报明天，all=实况+未来两天"),
                    ),
                    "required" to listOf("city"),
                ),
            ),
        ),
        ToolEntry(
            name = "calculate",
            group = ToolRegistry.DOMAIN_INFO,
            displayNameRes = R.string.ai_tool_calculate_name,
            descriptionRes = R.string.ai_tool_calculate_desc,
            risk = ToolRisk.READ_ONLY,
            statusText = "正在精确计算…",
            schema = toolSchema(
                name = "calculate",
                description = "精确计算算术表达式（大数乘除、百分比、幂、括号均可）。凡涉及精确数值计算（「378×56 等于多少」「(128+64)×12」「2 的 20 次方」「打 85 折多少钱」）时必须调用本工具，不要心算。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "expression" to mapOf("type" to "string", "description" to "标准算术表达式，支持 + - * / % ^ 与括号，如 \"378*56\"、\"(128+64)*12\"、\"599*0.85\""),
                    ),
                    "required" to listOf("expression"),
                ),
            ),
        ),
        ToolEntry(
            name = "get_location",
            group = ToolRegistry.DOMAIN_PHONE,
            displayNameRes = R.string.ai_tool_get_location_name,
            descriptionRes = R.string.ai_tool_get_location_desc,
            risk = ToolRisk.READ_ONLY,
            statusText = "正在获取位置…",
            schema = toolSchema(
                name = "get_location",
                description = "获取手机当前所在位置（城市 / 街区 / 地标）。当用户问「我在哪」「我在什么地方」「这是哪里」「我附近有什么」等需要知道用户当前位置的问题时，必须调用本工具，严禁自行编造位置。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf<String, Any>(),
                ),
            ),
        ),
    )

    override fun execute(context: Context, name: String, args: JSONObject): String {
        return when (name) {
            "get_current_time" -> {
                val fmt = SimpleDateFormat("yyyy年M月d日 EEEE HH:mm", Locale.CHINA)
                "当前时间：" + fmt.format(Date())
            }

            "get_weather" -> {
                val city = args.optString("city").trim()
                val date = args.optString("date", "today").trim()
                WeatherTools.getWeather(city, date)
            }

            "calculate" -> {
                val expr = args.optString("expression").trim()
                if (expr.isEmpty()) return "请提供要计算的表达式"
                try {
                    val value = Calculator.evaluate(expr)
                    // 整数结果去掉小数尾巴（378*56=21216.0 → 21216）
                    val pretty = if (value == Math.floor(value) && !value.isInfinite() &&
                        Math.abs(value) < 1e15
                    ) value.toLong().toString() else value.toString()
                    "$expr = $pretty"
                } catch (e: IllegalArgumentException) {
                    "无法计算「$expr」：${e.message}"
                }
            }

            "get_location" -> LocationTools.getLocation(context)

        else -> throw IllegalArgumentException("未知工具: $name")
        }
    }
}
