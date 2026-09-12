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
