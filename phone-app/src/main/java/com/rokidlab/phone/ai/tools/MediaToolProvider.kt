package com.rokidlab.phone.ai.tools

import android.content.Context
import android.util.Log
import com.rokid.cxr.Caps
import com.rokidlab.phone.ai.AiuiAppRegistry
import com.rokidlab.phone.ai.AiuiProject
import com.rokidlab.phone.ai.Calculator
import com.rokidlab.phone.ai.GlassToolConfirmChannel
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
import com.rokidlab.phone.glasses.AiChannel
import com.rokidlab.phone.glasses.CxrLHiRokidSession
import com.rokidlab.phone.glasses.GlassesHandshake
import com.rokidlab.phone.glasses.LinkProtocol
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * MediaToolProvider —— 媒体域（音乐播放/停止/歌词显示/读取当前播放信息）。
 * Phase 4 从 `ToolRegistry.execute` 迁出的该域工具执行分支（逐字搬运，行为不变）。
 */
internal object MediaToolProvider : ToolProvider {
    private const val TAG = "MediaToolProvider"

    override val toolNames = setOf(
        "play_song",
        "stop_music",
        "show_lyrics",
        "get_now_playing",
    )

    override fun execute(context: Context, name: String, args: JSONObject): String {
        return when (name) {
            "play_song" -> {
                val songName = args.optString("songName").trim()
                if (songName.isEmpty()) return "请告诉我要播放哪首歌"
                val artist = args.optString("artist").trim()
                val song = KuwoMusicApi.search(songName, artist.ifBlank { null })
                    ?: return "没有找到歌曲《$songName》${
                        if (artist.isNotBlank()) "（歌手：$artist）" else ""
                    }，请换个歌名试试"
                // play() 现在**阻塞到就绪或失败**并返回如实结果。
                // 旧实现只回「已开始播放」而不看真实播放状态，MediaPlayer 建源失败（what=-38）
                // 时仍然报成功 —— 用户听到的现象是"歌没播"，真机踩过。
                MusicPlayerController.play(
                    context, song.playUrl, song.name, song.artist, song.lyrics,
                    album = song.album, cover = song.cover,
                )
            }

            "stop_music" -> {
                MusicPlayerController.stop()
                "已停止播放音乐"
            }

            "show_lyrics" -> {
                if (!MusicPlayerController.isPlaying()) {
                    return "当前没有正在播放的音乐，请先说“播放某某歌”，再让我显示歌词"
                }
                val title = MusicPlayerController.currentTitle
                val lyrics = MusicPlayerController.currentLyrics
                if (lyrics.isEmpty()) {
                    return "没有获取到《$title》的歌词，请换个歌曲试试"
                }
                // 无论是否已在歌词模式都**强制重新推送**：眼镜端要靠一次元数据下发才会刷新
                // 歌词页；旧实现这里直接回「已经在显示了」而不重推 → 用户看到"说了也不显示"。
                val pushed = MusicPlayerController.startLyrics(context, lyrics, force = true)
                // 光推元数据眼镜端不会自己弹页（实测「说显示歌词，界面没拉出来」）：
                // 必须显式让眼镜端 startActivity 拉起系统音乐页（该页随 AVRCP 元数据逐行显示歌词）。
                val opened = openGlassesMusicPage(context)
                when {
                    pushed && opened == OpenResult.OK -> "正在为你显示《$title》的歌词"
                    pushed && opened == OpenResult.NOT_CONNECTED ->
                        "已推送《$title》的歌词，但眼镜端当前未连接，没能自动打开眼镜上的音乐页；请先连接眼镜后再说一次"
                    pushed ->
                        "已推送《$title》的歌词，但没能自动打开眼镜上的音乐页；请在眼镜上手动打开音乐页查看"
                    else -> "歌词显示开启失败，请稍后重试"
                }
            }

            "get_now_playing" -> currentSongJson()

        else -> throw IllegalArgumentException("未知工具: $name")
        }
    }

    /**
     * 当前播放信息（JSON 字符串）。
     *
     * **为什么需要这个工具**：AIUI 页面取外部数据的唯一通道是 `globalThis.Lab.callTool`，
     * 而本域原先是 `play_song` / `stop_music` / `show_lyrics` 三个**只回纯文本**的工具。
     * 于是"做一个播放器，有歌词有封面"这类需求生成出来的页面**拿不到任何素材**，
     * 只能摆空壳（用户实测：放歌正常、歌词与封面全空）。
     *
     * 歌词与封面早在搜索阶段就已解析（[KuwoMusicApi.Song.lyrics] / [KuwoMusicApi.Song.cover]），
     * 但此前只经 AVRCP 蓝牙元数据推给**眼镜端系统音乐页**（Rokid Launcher 的 MusicPageActivity，
     * 由 `show_lyrics` 的 `am start` 拉起），页面本身读不到。本工具把这份数据直接交给页面。
     *
     * 返回结构（页面 `JSON.parse` 后用）：
     * ```json
     * {"playing":true,"preparing":false,"title":"西厢","artist":"后弦","album":"九公主",
     *  "durationMs":240000,"positionMs":12345,"lineIndex":5,
     *  "cover":"https://...","lyrics":[{"timeMs":0,"text":"..."}]}
     * ```
     * 无歌曲时只回 `{"playing":false,"message":"..."}`。
     *
     * 页面同步歌词的方式：拿到本结果后用本地时钟从 `positionMs` 起推进，**不要反复轮询本工具**
     * —— 蓝牙通道串行且单次 1~3 秒，轮询会让歌词行严重滞后。
     *
     * ★ 为什么 `playing` 要把「准备中」也算进来（2026-09-16 定案，勿改回去）：
     * 页面在 `play_song` 之后**立刻**取数才有素材。但 [MusicPlayerController.play] 走
     * `prepareAsync()` 拉网络流，真正出声要 1~10 秒，期间 `isPlaying()` 恒为 false。
     * 若这里只报 `isPlaying()`，页面第一次取数就拿到 `playing:false`（尽管本响应里
     * `cover`/`lyrics` 其实**已经齐全**），于是把这份有效载荷判死、退化成「等起播」轮询
     * （实测生成的页面是每 1.5~2 秒一次、最多 40 次）→ 一分钟内必然撞满
     * [com.rokidlab.phone.ai.ToolPolicy] 给 AIUI 页面的 30 次/分钟限流 → 此后**每次取数
     * 都被拒绝**。用户看到的现象就是：歌正常在放（音频走手机端 MediaPlayer，与页面无关），
     * 但页面的歌词与封面**永远是空的**。
     * 把「已选好曲、正在准备/播放」统一算作 `playing=true`，页面第一次取数即拿到素材，
     * 从根上消掉轮询动机；`preparing` 单独给出，页面要区分可自行判断。
     */
    private fun currentSongJson(): String {
        val title = MusicPlayerController.currentTitle
        if (title.isBlank()) {
            return JSONObject()
                .put("playing", false)
                .put("message", "当前没有正在播放的音乐：先调用 play_song，再读取播放信息。")
                .toString()
        }
        val lyrics = MusicPlayerController.currentLyrics
        val positionMs = MusicPlayerController.currentPositionMs
        val lyricArray = JSONArray()
        lyrics.forEach { line ->
            lyricArray.put(JSONObject().put("timeMs", line.timeMs).put("text", line.text))
        }
        return JSONObject()
            // 「有当前曲目」而非「音频已出声」——理由见上面 currentSongJson 的注释：
            // prepareAsync 期间 isPlaying() 为 false，只报它会逼页面轮询并撞限流。
            .put("playing", MusicPlayerController.isPlaying() || MusicPlayerController.isLoading)
            .put("preparing", MusicPlayerController.isLoading)
            .put("title", title)
            .put("artist", MusicPlayerController.currentArtist)
            .put("album", MusicPlayerController.currentAlbum)
            .put("durationMs", MusicPlayerController.currentDurationMs)
            .put("positionMs", positionMs)
            .put("lineIndex", currentLineIndex(lyrics, positionMs))
            .put("cover", MusicPlayerController.currentCoverUrl)
            .put("lyrics", lyricArray)
            .toString()
    }

    /**
     * 播放进度当前落在第几行歌词（歌词按 timeMs 升序；进度早于首行时为 0）。
     * 无歌词返回 -1 —— 页面据此隐藏歌词区，而不是显示一个空行。
     */
    private fun currentLineIndex(lyrics: List<KuwoMusicApi.LyricLine>, positionMs: Long): Int {
        if (lyrics.isEmpty()) return -1
        var index = 0
        lyrics.forEachIndexed { i, line -> if (positionMs >= line.timeMs) index = i }
        return index
    }

    /** 眼镜端系统音乐页（Rokid Launcher 内，manifest 中 exported=true）：随 AVRCP 元数据逐行显示歌词。 */
    private const val GLASSES_LAUNCHER_PKG = "com.rokid.os.sprite.launcher"
    private const val GLASSES_MUSIC_ACTIVITY = "com.rokid.os.sprite.launcher.page.music.MusicPageActivity"

    /** 拉页结果：区分「成功」「眼镜未连接」「连了但没拉起来」，好如实回报给用户。 */
    private enum class OpenResult { OK, NOT_CONNECTED, FAILED }

    /**
     * 主动把眼镜端系统音乐页拉到前台（[AiChannel.TOPIC_OPEN_APP]）。
     *
     * 背景：眼镜端的「音乐/歌词页」不是独立 App，而是 Rokid Launcher 的 MusicPageActivity；
     * 仅靠手机端下发 AVRCP 元数据，眼镜端**不会主动弹出该页**（只会在页已打开时更新歌词），
     * 所以「显示歌词」必须显式下发一条 startActivity 指令。
     *
     * 两条路径，ADB 优先：
     *  1. ADB `am start`（shell UID）：只依赖 Lab 的共享 ADB 会话（WiFi/蓝牙隧道），
     *     **不依赖 CXR 会话登记、也不依赖眼镜端 RokidLink 进程存活**，最稳。
     *     ★ 实测踩坑：旧实现一上来就 `liveSession() ?: return false`，手机端 CXR 会话登记
     *     抖动（为空）时直接放弃 → 日志 `glasses not connected, skip open music page`，
     *     但此刻 ADB 通道其实是可用的。
     *  2. CXR 自定义指令 → 眼镜端 RokidLink 收到后 startActivity（需会话在线 + 眼镜端新版）。
     */
    private fun openGlassesMusicPage(context: Context): OpenResult {
        if (openMusicPageViaAdb(context)) return OpenResult.OK
        val session = GlassToolConfirmChannel.global.liveSession() ?: return OpenResult.NOT_CONNECTED
        return if (openMusicPageViaCxr(session)) OpenResult.OK else OpenResult.FAILED
    }

    /** 路径 1：经常驻 ADB shell 直启（shell UID 不受后台启动限制，且不依赖 RokidLink 运行）。 */
    private fun openMusicPageViaAdb(context: Context): Boolean {
        val client = runCatching { ToolRegistry.adbClient(context) }.getOrNull() ?: return false
        return runCatching {
            val out = client.executeShellCommand(
                "am start -n $GLASSES_LAUNCHER_PKG/$GLASSES_MUSIC_ACTIVITY",
                10_000,
            )
            val ok = !out.contains("Error", ignoreCase = true) &&
                !out.contains("Exception", ignoreCase = true) &&
                !out.contains("does not exist", ignoreCase = true) &&
                !out.contains("Permission Denial", ignoreCase = true)
            Log.i(TAG, "open music page via adb (ok=$ok): ${out.trim().take(120)}")
            ok
        }.onFailure { Log.w(TAG, "open music page via adb failed: ${it.message}") }.getOrDefault(false)
    }

    /** 路径 2：CXR 自定义指令（眼镜端 RokidLink 收令后 startActivity；旧版不支持则跳过）。 */
    private fun openMusicPageViaCxr(session: CxrLHiRokidSession): Boolean {
        if (GlassesHandshake.supports(LinkProtocol.Cap.OPEN_APP) == false) {
            Log.i(TAG, "glasses build does not support open_app, skip cxr path")
            return false
        }
        val link = session.cxrLink ?: return false
        return runCatching {
            val caps = Caps()
            caps.write(AiChannel.CMD_OPEN_APP)
            caps.write(AiChannel.SCHEMA_VERSION.toString())
            caps.write(GLASSES_LAUNCHER_PKG)
            caps.write(GLASSES_MUSIC_ACTIVITY)
            val r = session.rawSendCustomCmd(link, AiChannel.TOPIC_OPEN_APP, caps)
            Log.i(TAG, "open music page via cxr -> $r")
            r == 0
        }.onFailure { Log.w(TAG, "open music page via cxr failed: ${it.message}") }.getOrDefault(false)
    }
}
