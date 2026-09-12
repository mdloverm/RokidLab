package com.rokidlab.phone.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.rokidlab.phone.platform.AvrcpLyricBridge
import java.net.HttpURLConnection
import java.net.URL

/**
 * 手机端音乐播放器（单例）。
 *
 * 供 AI「播放歌曲」工具使用：直接从酷我 API 返回的 mp3 直链流式播放。
 * 停止时机：
 *  - AI 工具「停止播放」显式调用 [stop]
 *  - 眼镜端双击退出对话窗口：眼镜推送停止标记 → CxrLHiRokidSession 收到后调用 [stop]
 *
 * 播放/停止均可在后台线程安全调用（prepareAsync 异步准备，不阻塞调用线程）。
 *
 * 歌词显示（**对齐汽水音乐「车载蓝牙歌词」模式：播放一开始即自动推送，无需用户再说「显示歌词」**）：
 *  - [play] 在播放真正开始后调用 [onPlaybackStarted]：建立 AVRCP 媒体会话、上报 now-playing，
 *    并**自动**把整首 LRC 与逐行歌词交给 [AvrcpLyricBridge]（歌名写入 title、歌词体写入独立 lyric 字段），
 *    眼镜端官方音乐页随之自动显示歌词 —— 与汽水音乐行为一致（用户在眼镜上已实测：汽水音乐放歌即自动出歌词）。
 *  - AI 工具 `show_lyrics` 仍可用作**手动补推/重推**：部分机型首推时机易错过，
 *    说一次「显示歌词」会 [startLyrics](force=true) 强制再推一次，确保歌词页拉起。
 *  - [startLyrics] 每次都会**强制重推**一次元数据 —— 实测 ROM 只在「会话激活 / 曲目变化」时才
 *    向眼镜下发 MEDIA_METADATA，不重推就有可能出现「说了也不显示」。
 *  - 会话常驻且每 tick 刷新，也能降低被其它 App 抢走 AVRCP「当前播放器」的概率。
 *  MediaSession 生命周期与三条实测约束见 [AvrcpLyricBridge]。
 */
object MusicPlayerController {
    private const val TAG = "MusicPlayerController"

    /** 歌词行刷新周期（毫秒）：检查播放进度并定位当前歌词行 */
    private const val LYRIC_TICK_MS = 400L

    @Volatile
    private var player: MediaPlayer? = null
    @Volatile
    private var audioManager: AudioManager? = null
    @Volatile
    private var focusRequest: AudioFocusRequest? = null

    /** 当前播放歌曲名（停止后清空） */
    @Volatile
    var currentTitle: String = ""
        private set

    /** 当前播放歌手名（停止后清空） */
    @Volatile
    var currentArtist: String = ""
        private set

    /** 当前播放歌曲所属专辑（停止后清空）；眼镜端音乐页需要它 */
    @Volatile
    var currentAlbum: String = ""
        private set

    /** 当前播放歌曲封面图直链（停止后清空） */
    @Volatile
    var currentCoverUrl: String = ""
        private set

    /** 当前封面 Bitmap（异步下载完成后写入；写入 [AvrcpLyricBridge.setArt] 后随元数据发给眼镜） */
    @Volatile
    var currentCoverBitmap: Bitmap? = null
        private set

    /** 正在异步准备/播放中（用于 UI 展示加载状态） */
    @Volatile
    var isLoading: Boolean = false
        private set

    // ── 歌词模式（仅「显示歌词」工具开启） ──

    /** 当前歌曲的带时间戳歌词（停止后清空） */
    @Volatile
    var currentLyrics: List<KuwoMusicApi.LyricLine> = emptyList()
        private set

    /** 歌词模式是否开启（开启后眼镜端可显示逐行歌词） */
    @Volatile
    var isLyricsMode: Boolean = false
        private set

    private var lyricHandler: Handler? = null
    private var currentLineText: String = ""

    /** 串行化 play/stop 的操作锁：工具并发执行时防止双音轨叠加与 MediaPlayer 泄漏（B2） */
    private val opLock = Any()

    fun isPlaying(): Boolean = player?.isPlaying == true

    /**
     * 播放指定 mp3 直链。已有播放时自动先停止（换歌场景）。
     * 异步准备完成后自动开始播放；失败自动释放并清理状态。
     * [lyrics] 为该歌曲的带时间戳歌词，仅缓存供「显示歌词」工具使用，不在播放时推送。
     */
    fun play(
        context: Context,
        url: String,
        title: String,
        artist: String,
        lyrics: List<KuwoMusicApi.LyricLine> = emptyList(),
        album: String = "",
        cover: String = "",
    ) {
        stop()
        currentTitle = title
        currentArtist = artist
        currentAlbum = album
        currentLyrics = lyrics
        // 异步下载封面（不阻塞播放启动；下载完成后 setArt + 强制重推元数据让眼镜端音乐页刷新封面）
        currentCoverUrl = cover
        currentCoverBitmap = null
        AvrcpLyricBridge.setArt(null)
        if (cover.isNotBlank()) loadCoverAsync(cover)
        isLoading = true
        requestAudioFocus(context.applicationContext)
        val mp = MediaPlayer()
        player = mp
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            mp.setDataSource(url)
            mp.setOnPreparedListener { p ->
                if (player !== p) return@setOnPreparedListener
                isLoading = false
                runCatching { p.start() }
                Log.i(TAG, "music playing: $title - $artist")
                // 对齐汽水音乐「车载蓝牙歌词」：播放一开始即建立 AVRCP 会话并自动推送歌词，
                // 眼镜端无需用户再说「显示歌词」即可自动获取歌词。
                onPlaybackStarted(context.applicationContext)
            }
            mp.setOnErrorListener { p, what, extra ->
                Log.e(TAG, "music error: what=$what extra=$extra")
                if (player === p) {
                    stopLyricsInternal()
                    player = null
                    isLoading = false
                    runCatching { p.release() }
                    abandonAudioFocus()
                }
                true
            }
            mp.setOnCompletionListener { p ->
                Log.i(TAG, "music completed")
                if (player === p) {
                    stopLyricsInternal()
                    player = null
                    isLoading = false
                    runCatching { p.release() }
                    abandonAudioFocus()
                }
            }
            mp.prepareAsync()
        } catch (e: Exception) {
            Log.e(TAG, "music prepare failed", e)
            isLoading = false
            if (player === mp) player = null
            runCatching { mp.release() }
            abandonAudioFocus()
        }
    }

    /**
     * 播放真正开始后触发：建立 AVRCP 媒体会话（让眼镜端认到「正在播放」），
     * 并像汽水音乐「车载蓝牙歌词」模式那样，随播放进度自动把当前歌词行写入元数据 ——
     * 眼镜端官方音乐页即可自动逐行显示歌词，无需用户再触发「显示歌词」。
     */
    private fun onPlaybackStarted(context: Context) {
        ensureMediaSession(context)
        val dur: Long = runCatching { player?.duration?.toLong() }.getOrDefault(0L) ?: 0L
        AvrcpLyricBridge.pushNowPlaying(currentTitle, currentArtist, currentAlbum, dur)
        // 有歌词就自动进入歌词模式（逐行推送），无歌词则仅上报歌名/歌手
        if (currentLyrics.isNotEmpty()) {
            startLyrics(context, currentLyrics, force = true)
        }
    }

    /** 停止并释放当前播放器，清空歌曲信息与音频焦点；同时退出歌词模式（MediaSession 复用不释放） */
    fun stop() {
        synchronized(opLock) {
            stopLyricsInternal()
            pushStoppedState()
            val p = player ?: run {
                abandonAudioFocus()
                return@synchronized
            }
            player = null
            currentTitle = ""
            currentArtist = ""
            currentAlbum = ""
            currentCoverUrl = ""
            currentCoverBitmap = null
            AvrcpLyricBridge.setArt(null)
            isLoading = false
            runCatching { p.stop() }
            runCatching { p.release() }
            abandonAudioFocus()
            Log.i(TAG, "music stopped")
        }
    }

    /**
     * 异步下载当前歌曲封面 → 注入 [AvrcpLyricBridge] 并强制重推元数据。
     *
     * 下载在子线程；完成后切到主线程写 Bitmap、调 setArt、并按当前状态重推一次元数据
     * （歌词模式则 [updateLyricLine](force=true)，否则 [AvrcpLyricBridge.pushNowPlaying]），
     * 让眼镜端音乐页 / AVRCP Current Data 刷新封面。
     */
    private fun loadCoverAsync(coverUrl: String) {
        Thread {
            val raw = runCatching {
                val conn = (URL(coverUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 6000
                    readTimeout = 10000
                }
                conn.inputStream.use { BitmapFactory.decodeStream(it) }
            }.onFailure { Log.w(TAG, "cover download failed: ${it.message}") }.getOrNull() ?: return@Thread
            // 限到 320px：Bitmap 会随 MediaMetadata 走 Binder 传给 SystemUI/Bluetooth 进程，
            // ARGB_8888 下 600px ≈ 1.44MB 已超 Binder 单事务 ~1MB 上限（会导致元数据/封面双双失效），
            // 320px ≈ 410KB 安全，且足以撑起通知栏/媒体卡片的封面显示。
            val scaled = runCatching {
                val max = 320
                if (raw.width <= max && raw.height <= max) raw
                else {
                    val ratio = max.toDouble() / maxOf(raw.width, raw.height)
                    val nw = (raw.width * ratio).toInt().coerceAtLeast(1)
                    val nh = (raw.height * ratio).toInt().coerceAtLeast(1)
                    Bitmap.createScaledBitmap(raw, nw, nh, true)
                }
            }.getOrDefault(raw)
            Handler(Looper.getMainLooper()).post {
                if (currentCoverUrl != coverUrl) return@post  // 已切歌，丢弃
                currentCoverBitmap = scaled
                AvrcpLyricBridge.setArt(scaled)
                val p = player
                if (p != null && p.isPlaying) {
                    if (isLyricsMode) updateLyricLine(p, force = true)
                    else {
                        val dur = runCatching { p.duration.toLong() }.getOrDefault(0L)
                        AvrcpLyricBridge.pushNowPlaying(currentTitle, currentArtist, currentAlbum, dur)
                    }
                }
                Log.i(TAG, "cover loaded & re-pushed: ${scaled.width}x${scaled.height}")
            }
        }.start()
    }

    // ── 歌词模式 ──

    /**
     * 开启歌词模式：注册 MediaSession 并随播放进度把当前歌词行（窗口）写入元数据，
     * 经蓝牙 AVRCP 推送到眼镜端显示。
     * @return 是否成功开启（未在播放或歌词为空时返回 false）
     */
    fun startLyrics(context: Context, lines: List<KuwoMusicApi.LyricLine>, force: Boolean = false): Boolean {
        val mp = player
        if (mp == null || !isPlaying()) {
            Log.w(TAG, "startLyrics skipped: not playing")
            return false
        }
        if (lines.isEmpty()) {
            Log.w(TAG, "startLyrics skipped: empty lyrics")
            return false
        }
        // 已在歌词模式：force（说「显示歌词」/ 播放后补推）时**强制重发一次元数据**，
        // 眼镜端靠这一次下发才会拉起/刷新歌词页；只回「已经在显示了」会出现"说了也不显示"。
        if (isLyricsMode) {
            if (force) {
                updateLyricLine(mp, force = true)
                Log.i(TAG, "lyrics force re-push (already in lyrics mode)")
            }
            return true
        }
        stopLyricsInternal()
        currentLyrics = lines
        isLyricsMode = true
        currentLineText = ""
        ensureMediaSession(context.applicationContext)
        // 整首歌词作为「播放队列」下发：ROM 只按队列当前项变化判定换曲（否则每行都不推送）
        // ★ 必须带 artist/album：AVRCP 的 MediaPlayerWrapper 按 title+artist+album 比对
        //   队列当前项与当前元数据，缺字段即判 out of sync、媒体更新永不完成（眼镜收不到歌词）。
        AvrcpLyricBridge.setLyricsQueue(
            lines.map { it.text },
            artist = currentArtist,
            album = currentAlbum,
        )
        AvrcpLyricBridge.activate()
        updateLyricLine(mp)
        val h = Handler(Looper.getMainLooper())
        lyricHandler = h
        h.post(object : Runnable {
            override fun run() {
                if (!isLyricsMode) return
                val p = player
                if (p != null && p.isPlaying) {
                    updateLyricLine(p)
                }
                if (lyricHandler === h) h.postDelayed(this, LYRIC_TICK_MS)
            }
        })
        // 播放后补推两次：眼镜端音乐页/歌词通常要等「AI 对话窗口关闭」后才可见，
        // 首次下发容易错过时机 → 3s / 9s 再各强制推一次（唯一媒体 id 会触发 ROM 重发）
        listOf(700L, 1_800L, 4_000L, 10_000L).forEach { delayMs ->
            h.postDelayed({
                if (isLyricsMode) player?.let { p2 -> if (p2.isPlaying) updateLyricLine(p2, force = true) }
            }, delayMs)
        }
        Log.i(TAG, "lyrics mode started, lines=${lines.size}")
        return true
    }

    /**
     * 退出歌词模式：仅停止定时刷新并复位状态。
     * 注意：绝不能释放 MediaSession——release() 会触发系统销毁 session
     * （日志中 "The session was destroyed"），蓝牙 AVRCP 层随之丢失 MediaController
     * 监听，此后所有歌词行更新都不再推送到眼镜。复用同一实例即可。
     */
    private fun stopLyricsInternal() {
        lyricHandler?.removeCallbacksAndMessages(null)
        lyricHandler = null
        isLyricsMode = false
        currentLyrics = emptyList()
        currentLineText = ""
        Log.i(TAG, "lyrics mode stopped (session kept)")
    }

    /** 推送停止状态：经 AVRCP 告知眼镜音乐已停止并清空标题（收口至 L0 [AvrcpLyricBridge]） */
    private fun pushStoppedState() = AvrcpLyricBridge.pushStopped()

    /**
     * 确保 AVRCP 媒体按键接管（MediaSession 生命周期与三条实测约束见 [AvrcpLyricBridge]）。
     *
     * 媒体按键语义：官方 AI 接管"停止播放"语音时经蓝牙下发 MEDIA_PAUSE/MEDIA_STOP，
     * 若不处理则音乐继续播放（实测语音停止失效）→ onPause/onStop 均停止并释放播放器。
     */
    private fun ensureMediaSession(context: Context) {
        AvrcpLyricBridge.ensure(
            context,
            onPlay = {
                val p = player
                if (p != null && !p.isPlaying) runCatching { p.start() }
            },
            onPause = { if (player?.isPlaying == true) stop() },
            onStop = { if (player != null) stop() },
        )
    }

    /**
     * 按播放进度定位当前歌词行号；当前歌词行（纯文本）由 [AvrcpLyricBridge] 写入 `TITLE`、
     * 后续行写入 `DISPLAY_SUBTITLE`/`DISPLAY_DESCRIPTION` —— 这是眼镜官方音乐页渲染逐行歌词的字段
     * （对齐汽水音乐与 v3.2 基线实测；TITLE 必须放歌词首行，放歌名则歌词体整段消失）。
     */
    private fun updateLyricLine(mp: MediaPlayer, force: Boolean = false) {
        val pos = runCatching { mp.currentPosition }.getOrDefault(0)
        var lineIndex = 0
        currentLyrics.forEachIndexed { i, l ->
            if (pos >= l.timeMs) lineIndex = i
        }
        if (!AvrcpLyricBridge.isSessionCreated) return
        val duration = runCatching { mp.duration.toLong() }.getOrDefault(0L)
        // 当前行 + 后续两行，分别写入 TITLE / DISPLAY_SUBTITLE / DISPLAY_DESCRIPTION
        val line1 = currentLyrics.getOrNull(lineIndex)?.text ?: ""
        val line2 = currentLyrics.getOrNull(lineIndex + 1)?.text ?: ""
        val line3 = currentLyrics.getOrNull(lineIndex + 2)?.text ?: ""
        val pushed = AvrcpLyricBridge.pushLine(
            songTitle = currentTitle,
            artist = currentArtist,
            album = currentAlbum,
            durationMs = duration,
            positionMs = pos.toLong(),
            lastLine = currentLineText,
            lineIndex = lineIndex,
            line1 = line1,
            line2 = line2,
            line3 = line3,
            force = force,
        )
        if (pushed) {
            currentLineText = line1
            Log.i(TAG, if (force) "lyric meta push (force): ${line1.take(30)}" else "lyric meta push: ${line1.take(30)}")
        }
    }

    private fun requestAudioFocus(context: Context) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        audioManager = am
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attrs)
                .build()
            focusRequest = request
            am.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        }
    }

    private fun abandonAudioFocus() {
        val am = audioManager ?: return
        audioManager = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { am.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            am.abandonAudioFocus(null)
        }
        focusRequest = null
    }
}
