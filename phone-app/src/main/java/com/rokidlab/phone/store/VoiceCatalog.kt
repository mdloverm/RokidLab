package com.rokidlab.phone.store

import android.util.Log
import com.rokidlab.phone.util.AppConfig
import com.rokidlab.phone.util.HttpClient
import org.json.JSONObject
import java.net.URLEncoder

/**
 * 云萌语音合成（voicehc 接口）共享目录：音色表 + 合成请求。
 *
 * 由对话播报链路（VoiceBroadcast）与音色管理页（VoicePickPage）共用。
 * token 与酷我听歌共用（[AppConfig.KUWO_API_TOKEN]）。
 *
 * 接口要点（https://api.yunmge.com/docx-voicehc.html）：
 * - GET api/voicehc?token&mode=json&voice&rate&text → data.filelink（OSS mp3，5 分钟有效）
 * - 错误码：351 合成失败；504-535 服务器/token 类错误
 * - filelink 为 http 直链 → 必须升级 https（release 全局禁明文，酷我音乐同款教训）
 */
object VoiceCatalog {
    private const val TAG = "VoiceCatalog"

    const val API_URL = "https://api.yunmge.com/api/voicehc"

    /** 音色管理页固定试听文本（无输入框，见 VoicePickPage） */
    const val PREVIEW_TEXT = "欢迎使用rokid lab。"

    /** 全部语音角色（id, 名称；取自接口文档 voice 参数可选值，maoxiaomei 与猫小美重复已去重） */
    val VOICES: List<Pair<String, String>> = listOf(
        "maoxiaomei" to "猫小美",
        "xiaoxian" to "小仙",
        "zhixiaobai" to "知小白",
        "ruoxi" to "若兮",
        "siqi" to "思琪",
        "laomei" to "老妹",
        "aida" to "艾达",
        "zhifeng_emo" to "知峰",
        "laotie" to "老铁",
        "zhiwei" to "知薇·萝莉",
        "zhiru" to "知茹·新闻女声",
        "zhiqian" to "知倩·资讯女声",
        "zhijia" to "知佳·标准女声",
        "zhiqi" to "知琪·温柔女声",
        "zhilun" to "知伦·悬疑解说",
        "zhifei" to "知飞·激昂解说",
        "zhide" to "知德·新闻男声",
        "zhinan" to "知楠·广告男声",
        "zhixiang" to "知祥·磁性男声",
    )

    /** OSS mp3 直链 http → https（v3.6 起禁明文 http，酷我音乐同款处理） */
    fun toHttps(url: String): String =
        if (url.startsWith("http://")) "https://" + url.removePrefix("http://") else url

    /**
     * 进 API 前净化文本：云萌 TTS 引擎遇到部分字符会**提前终止合成**（2026-10-03 真机+curl
     * 双向实测：60 字带 em dash「——」的文本只出 1.7s mp3，同样的纯中文文本 15s 完整），
     * 省略号/emoji/特殊符号同理。白名单放行汉字/字母数字/常用标点，其余一律替换成逗号
     * （TTS 读作停顿），保证合成完整。
     */
    internal fun sanitizeForTts(raw: String): String {
        val sb = StringBuilder(raw.length)
        for (ch in raw) {
            val keep = when {
                ch in '一'..'鿿' -> true   // CJK 统一汉字
                ch.isLetterOrDigit() -> true
                ch in "。，、？！：；,.?!:;'\"()《》（） \t" -> true
                else -> false
            }
            sb.append(if (keep) ch else '，')
        }
        return sb.toString().replace(Regex("[，,]{2,}"), "，").trim().trim('，', ',')
    }

    /** 调 voicehc 合成，成功返回 mp3 直链（已升级 https）。网络阻塞，须后台线程调用。 */
    fun synthesize(voice: String, rate: Int, rawText: String): Result<String> {
        val text = sanitizeForTts(rawText)
        val query = mapOf(
            "token" to AppConfig.KUWO_API_TOKEN,
            "mode" to "json",
            "voice" to voice,
            "rate" to "$rate",
            "text" to text,
        ).entries.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
        return runCatching {
            val body = HttpClient.getString("$API_URL?$query", connectTimeout = 6000, readTimeout = 20000)
            val root = JSONObject(body)
            val code = root.optInt("code")
            if (code != 200) error("code=$code ${root.optString("msg")}")
            val link = root.getJSONObject("data").optString("filelink")
            if (link.isEmpty()) error("empty filelink")
            Log.i(TAG, "synth ok voice=$voice rate=$rate chars=${text.length}")
            toHttps(link)
        }.onFailure { Log.w(TAG, "synthesize failed: ${it.message}") }
    }
}
