package com.rokidlab.phone.ai

import android.util.Log
import com.rokidlab.phone.util.AppConfig
import com.rokidlab.phone.util.HttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * 酷我音乐音源 API（云萌 API 市场）客户端。
 *
 * 供 AI「播放歌曲」工具使用：按歌名（可选歌手）搜索歌曲，
 * 返回可直接流式播放的 mp3 直链（128k/192k/320k，优先 320k）。
 *
 * 接口文档：https://api.yunmge.com/docx-song_kuwo.html
 */
object KuwoMusicApi {
    private const val TAG = "KuwoMusicApi"
    private const val API_URL = "https://api.yunmge.com/api/song_kuwo"

    /** 一句带时间戳的歌词（timeMs 为相对歌曲开始的毫秒偏移） */
    data class LyricLine(val timeMs: Long, val text: String)

    data class Song(
        val name: String,
        val artist: String,
        val playUrl: String,
        /** 带时间戳的逐行歌词（接口未返回时为 [emptyList]） */
        val lyrics: List<LyricLine> = emptyList(),
    )

    /**
     * 搜索歌曲。返回最适合播放的一首（含 mp3 直链）；失败返回 null。
     * [artist] 非空时优先匹配歌手名包含指定歌手的条目。
     */
    fun search(songName: String, artist: String? = null): Song? {
        val query = mapOf(
            "token" to AppConfig.KUWO_API_TOKEN,
            "name" to songName,
            "n" to "无",
            "num" to "10",
        ).entries.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }

        val body = runCatching {
            HttpClient.getString("$API_URL?$query", connectTimeout = 6000, readTimeout = 10000)
        }.getOrElse { e ->
            Log.e(TAG, "search request failed: ${e.message}")
            return null
        }
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
        if (root.optInt("code") != 200) {
            Log.e(TAG, "search failed: code=${root.optInt("code")} msg=${root.optString("msg")}")
            return null
        }
        val data = root.opt("data") ?: return null
        val candidates = when (data) {
            is JSONObject -> listOf(data)
            is JSONArray -> (0 until data.length()).mapNotNull { data.optJSONObject(it) }
            else -> return null
        }
        if (candidates.isEmpty()) return null

        // 歌手匹配：优先返回歌手名与用户指定歌手一致的条目；未指定或匹配不到时取第一条
        val matched = if (artist.isNullOrBlank()) {
            candidates.first()
        } else {
            candidates.firstOrNull { it.optString("artist").contains(artist) }
                ?: candidates.first()
        }
        val name = matched.optString("name")
        val art = matched.optString("artist")
        val playUrl = pickMp3Url(matched.optJSONArray("all_bitrates")) ?: return null
        Log.i(TAG, "search hit: $name - $art ($playUrl)")
        return Song(name, art, playUrl, parseLyrics(matched))
    }

    /**
     * 从返回项的 lyrics.raw[] 解析带时间戳歌词。
     * 结构：lyrics.raw = [{ lineLyric: "歌词行", time: "秒(浮点字符串)" }, ...]
     * 接口未返回歌词或解析失败时返回空列表。
     */
    private fun parseLyrics(item: JSONObject): List<LyricLine> {
        val raw = item.optJSONObject("lyrics")?.optJSONArray("raw") ?: return emptyList()
        val lines = mutableListOf<LyricLine>()
        for (i in 0 until raw.length()) {
            val entry = raw.optJSONObject(i) ?: continue
            val text = entry.optString("lineLyric").trim()
            val timeMs = entry.optDouble("time", -1.0)
            if (text.isEmpty() || timeMs < 0) continue
            lines.add(LyricLine((timeMs * 1000).toLong(), text))
        }
        // 按时间排序，保证二分/顺序查找正确
        return lines.sortedBy { it.timeMs }
    }

    /** 从 all_bitrates 中挑选 mp3 直链：优先 320k，其次 192k/128k（跳过 aac/flac） */
    private fun pickMp3Url(bitrates: JSONArray?): String? {
        if (bitrates == null) return null
        val entries = (0 until bitrates.length()).mapNotNull { bitrates.optJSONObject(it) }
        val mp3 = entries.filter { it.optString("label").contains("mp3") }
        return mp3.maxByOrNull { it.optInt("bitrate", 0) }?.optString("play_url")
            ?: entries.firstOrNull()?.optString("play_url")
    }
}
