package com.rokidlab.phone.ai.tools

import com.rokidlab.phone.R
import com.rokidlab.phone.ai.ToolRisk

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
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
import java.io.ByteArrayOutputStream
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
        "control_music",
        "show_lyrics",
        "get_now_playing",
        "get_cover_image",
    )

    override fun tools(): List<ToolEntry> = listOf(
        ToolEntry(
            name = "control_music",
            group = ToolRegistry.DOMAIN_MEDIA,
            displayNameRes = R.string.ai_tool_control_music_name,
            descriptionRes = R.string.ai_tool_control_music_desc,
            risk = ToolRisk.LOCAL_SIDE_EFFECT,
            sideEffect = true,
            statusText = "正在处理音乐播放…",
            schema = toolSchema(
                name = "control_music",
                description = "控制手机上的音乐播放，一个工具管两种意图。action=\"play\"：播放指定歌曲（用户说「播放某某歌」「来一首某歌」「放首某某的歌」）——必须给 songName，会联网搜索并直接播放。action=\"stop\"：停止当前播放（用户说「停止播放」「别放了」「停一下」「不听了」）。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "action" to mapOf("type" to "string", "enum" to listOf("play", "stop"), "description" to "play=播放指定歌曲；stop=停止当前播放"),
                        "songName" to mapOf("type" to "string", "description" to "action=play 时的歌曲名称，如「晴天」「海阔天空」"),
                        "artist" to mapOf("type" to "string", "description" to "可选，歌手名，用于同名歌曲消歧，如「周杰伦」"),
                    ),
                    "required" to listOf("action"),
                ),
            ),
        ),
        ToolEntry(
            name = "show_lyrics",
            group = ToolRegistry.DOMAIN_MEDIA,
            displayNameRes = R.string.ai_tool_show_lyrics_name,
            descriptionRes = R.string.ai_tool_show_lyrics_desc,
            risk = ToolRisk.LOCAL_SIDE_EFFECT,
            sideEffect = true,
            requiresGlasses = true,
            statusText = "正在打开歌词…",
            schema = toolSchema(
                name = "show_lyrics",
                description = "在 Rokid 眼镜上显示当前播放歌曲的歌词：会主动打开眼镜上的音乐页并逐行实时刷新歌词。当用户说“显示歌词”“打开歌词”“看歌词”“我要看歌词”时调用，仅在音乐正在播放时有效。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf<String, Any>(),
                ),
            ),
        ),
        ToolEntry(
            name = "get_now_playing",
            group = ToolRegistry.DOMAIN_MEDIA,
            displayNameRes = R.string.ai_tool_get_now_playing_name,
            descriptionRes = R.string.ai_tool_get_now_playing_desc,
            risk = ToolRisk.READ_ONLY,
            statusText = "正在读取播放信息…",
            schema = toolSchema(
                name = "get_now_playing",
                description = "读取当前播放歌曲的完整信息，返回 JSON 文本：歌名 title、歌手 artist、专辑 album、时长 durationMs、当前进度 positionMs、是否仍在准备 preparing、当前歌词行号 lineIndex、封面图地址 cover、逐行歌词 lyrics（每行含 timeMs 与 text）。play_song 之后**立刻**调它就能拿到 cover 与 lyrics（正在准备中 playing 也为 true，无需等待、无需轮询）；AIUI 播放器页面用它取封面与歌词来渲染；语音场景下用户问“现在放的是什么歌”时也可调用。没有正在播放的音乐时返回 playing=false。不要把本工具放进定时器反复调用——AIUI 页面每分钟上限 30 次，轮询会把额度耗尽导致此后每次调用都被拒。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf<String, Any>(),
                ),
            ),
        ),
        ToolEntry(
            name = "get_cover_image",
            group = ToolRegistry.DOMAIN_MEDIA,
            displayNameRes = R.string.ai_tool_get_cover_image_name,
            descriptionRes = R.string.ai_tool_get_cover_image_desc,
            hidden = true,
            risk = ToolRisk.READ_ONLY,
            statusText = "正在获取歌曲封面…",
            schema = toolSchema(
                name = "get_cover_image",
                description = "取当前播放歌曲的封面图，返回可直接放进页面显示的 data URL（image/jpeg + base64 纯文本）。为什么必须有这个工具：眼镜端整机没有网络，AIUI 页面里直接写远程图片地址（https://…）一定加载失败，封面只能由手机侧取好再下发。AIUI 播放器页面拿到 get_now_playing 的曲目信息后可调用本工具拿封面；用户说“显示封面”“看下封面”时也可调用。没有在播歌曲或该曲无封面时返回一句中文说明。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf<String, Any>(),
                ),
            ),
        ),
    )

    override fun execute(context: Context, name: String, args: JSONObject): String {
        return when (name) {
            // 播放 / 停止二合一（原 play_song + stop_music）：同一能力域，
            // 合并后模型不必在「用户说别放了」时去另一个工具里找停止动作。
            "control_music" -> when (args.optString("action").trim().lowercase()) {
                "stop" -> {
                    MusicPlayerController.stop()
                    "已停止播放音乐"
                }
                else -> {
                    val songName = args.optString("songName").trim()
                    if (songName.isEmpty()) return "请告诉我要播放哪首歌（action=play 需要 songName）"
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

            "get_cover_image" -> coverDataUrl()

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
     * [com.rokidlab.phone.ai.approval.ApprovalGate] 给 AIUI 页面的 30 次/分钟限流 → 此后**每次取数
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
                .put("message", "当前没有正在播放的音乐：先调用 control_music，再读取播放信息。")
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
     * 当前歌曲封面 → `data:image/jpeg;base64,…`，供 AIUI 页面的 `<image src>` 直接渲染。
     *
     * **为什么需要这个工具（真机实测，2026-09-17）**：眼镜端**整机没有网络**
     * （`ping 8.8.8.8` → `Network is unreachable`，DNS 也解析不了），页面里
     * `<image src="https://…">` 必然失败（眼镜日志：`web_image_loader.rs: Failed to fetch
     * remote Web image … Unable to resolve host`）。所以封面只能由手机侧取好、编码后
     * 随工具结果下发。页面侧已验证：**data URL 与包内路径都能正常渲染**。
     *
     * 体量：≤256px + JPEG q72 ⇒ base64 约 2~4 万字符，因此 [ToolGateway] 对本工具
     * 单独放宽了结果截断上限（默认 8000 会把图片数据砍断）。
     */
    private fun coverDataUrl(): String {
        val title = MusicPlayerController.currentTitle
        if (title.isBlank()) {
            return "当前没有正在播放的音乐，先用 play_song 播一首，再取封面"
        }
        val bmp = MusicPlayerController.coverForPage() ?: return "《$title》没有可用的封面图"
        val bytes = ByteArrayOutputStream()
        val ok = runCatching { bmp.compress(Bitmap.CompressFormat.JPEG, 72, bytes) }.getOrDefault(false)
        if (!ok || bytes.size() == 0) return "《$title》封面编码失败，请稍后再试"
        val b64 = Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP)
        Log.i(TAG, "cover data url: ${bmp.width}x${bmp.height} jpeg=${bytes.size()}B b64=${b64.length}")
        return "data:image/jpeg;base64,$b64"
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
