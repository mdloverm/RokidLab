package com.rokidlab.phone.ai

import android.util.Log
import com.rokidlab.phone.util.HttpClient
import org.json.JSONObject

/**
 * 天气查询（Open-Meteo 免费公开 API，无需 API Key）：
 * 城市名 → 地理编码（经纬度）→ 实况 + 3 日预报。
 *
 * 作为 AI 工具（get_weather）的执行体：模型从用户话中提取城市名传入。
 */
object WeatherTools {
    private const val TAG = "WeatherTools"

    /** WMO weather code → 中文描述 */
    private fun wmoText(code: Int): String = when (code) {
        0 -> "晴"
        1 -> "基本晴"
        2 -> "多云"
        3 -> "阴"
        45, 48 -> "雾"
        51, 53, 55 -> "毛毛雨"
        56, 57 -> "冻毛毛雨"
        61, 63, 65 -> "雨（小/中/大）"
        66, 67 -> "冻雨"
        71, 73, 75 -> "雪（小/中/大）"
        77 -> "雪粒"
        80, 81, 82 -> "阵雨（小/中/大）"
        85, 86 -> "阵雪"
        95 -> "雷暴"
        96, 99 -> "雷暴伴冰雹"
        else -> "未知天气($code)"
    }

    /**
     * 查询指定城市天气；城市为空时自动定位兜底（自主任务/无人值守场景模型没有位置上下文）。
     *
     * @param city 城市名（中文/拼音/英文均可，如「杭州」「Shanghai」）；空串 = 自动定位
     * @param date 可选日期：today（默认）/ tomorrow / 后天；不传返回「实况 + 3 日预报」
     * @return 给模型的中文天气文本；失败时返回可如实转告的失败原因
     */
    fun getWeather(context: android.content.Context, city: String, date: String? = null): String {
        val c = city.trim()
        if (c.isNotEmpty()) return queryByCity(c, date)
        // 自动定位：系统定位缓存 → 实时定位（LocationTools 内 6s 预算）
        val loc = LocationTools.currentLocation(context)
            ?: return "请提供要查询的城市名称（自动定位不可用，请确认手机已开启定位并授权）"
        Log.i(TAG, "auto-locate for weather: ${loc.latitude},${loc.longitude}")
        return queryByCoords(loc.latitude, loc.longitude, "当前位置", date)
    }

    private fun queryByCity(c: String, date: String?): String = try {
        // 1) 地理编码：城市名 → 经纬度（language=zh 优先中文地名）
        val geoUrl = "https://geocoding-api.open-meteo.com/v1/search?name=" +
            java.net.URLEncoder.encode(c, "UTF-8") + "&count=1&language=zh&format=json"
        val geo = JSONObject(HttpClient.getString(geoUrl, readTimeout = 10000))
        val results = geo.optJSONArray("results")
        if (results == null || results.length() == 0) {
            "没有找到城市“$c”，请确认城市名称（可用全名，如「杭州市」）"
        } else {
            val hit = results.getJSONObject(0)
            queryByCoords(hit.optDouble("latitude"), hit.optDouble("longitude"), hit.optString("name", c), date)
        }
    } catch (e: Exception) {
        Log.w(TAG, "geocode failed: ${e.message}")
        "天气查询失败：${e.message ?: "网络异常"}"
    }

    private fun queryByCoords(lat: Double, lon: Double, name: String, date: String?): String {
        return try {
        // 2) 预报：实况 + 4 日（today 起 daily 数组含今天，多取一天覆盖「后天」）
        val forecastUrl = "https://api.open-meteo.com/v1/forecast" +
            "?latitude=$lat&longitude=$lon" +
            "&current=temperature_2m,relative_humidity_2m,apparent_temperature,weather_code,wind_speed_10m" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
            "&timezone=auto&forecast_days=4"
        val fc = JSONObject(HttpClient.getString(forecastUrl, readTimeout = 15000))
        val current = fc.optJSONObject("current") ?: return "天气服务返回异常，请稍后再试"
        val daily = fc.optJSONObject("daily") ?: return "天气服务返回异常，请稍后再试"

        val dayLabel = date?.trim()?.lowercase()
        if (dayLabel == "today" || dayLabel == "今天") {
            return buildToday(name, current, daily, 0)
        }
        if (dayLabel == "tomorrow" || dayLabel == "明天") {
            return buildDailyLine(name, daily, 1, "明天")
        }
        if (dayLabel == "后天") {
            return buildDailyLine(name, daily, 2, "后天")
        }
        // 默认：实况 + 3 日预报
        val sb = StringBuilder()
        sb.append(buildToday(name, current, daily, 0))
        sb.append("\n").append(buildDailyLine(name, daily, 1, "明天"))
        sb.append("\n").append(buildDailyLine(name, daily, 2, "后天"))
        sb.toString()
    } catch (e: Exception) {
        Log.w(TAG, "getWeather failed: ${e.message}")
        "天气查询失败：${e.message ?: "网络异常"}"
    }
    }

    private fun buildToday(name: String, current: JSONObject, daily: JSONObject, idx: Int): String {
        val temp = current.optDouble("temperature_2m")
        val feels = current.optDouble("apparent_temperature")
        val hum = current.optInt("relative_humidity_2m")
        val wind = current.optDouble("wind_speed_10m")
        val code = current.optInt("weather_code")
        val hi = daily.optJSONArray("temperature_2m_max")?.optDouble(idx)
        val lo = daily.optJSONArray("temperature_2m_min")?.optDouble(idx)
        val rain = daily.optJSONArray("precipitation_probability_max")?.optInt(idx)
        return buildString {
            append("「$name」实况：${wmoText(code)}，${temp}℃（体感 ${feels}℃）")
            if (hi != null && lo != null) append("，今日 ${lo}~${hi}℃")
            append("，湿度 ${hum}%，风速 ${wind}km/h")
            if (rain != null && rain > 30) append("，降水概率 ${rain}%（建议带伞）")
        }
    }

    private fun buildDailyLine(name: String, daily: JSONObject, idx: Int, label: String): String {
        val codes = daily.optJSONArray("weather_code") ?: return "$label 天气数据缺失"
        val hi = daily.optJSONArray("temperature_2m_max")?.optDouble(idx)
        val lo = daily.optJSONArray("temperature_2m_min")?.optDouble(idx)
        val rain = daily.optJSONArray("precipitation_probability_max")?.optInt(idx)
        return buildString {
            append("「$name」$label：${wmoText(codes.optInt(idx))}")
            if (hi != null && lo != null) append("，${lo}~${hi}℃")
            if (rain != null) append("，降水概率 ${rain}%")
        }
    }
}
