package com.rokidlab.phone.ai

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.MediaPlayer
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log

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
 * 歌词显示：仅当 AI 工具「显示歌词」调用 [startLyrics] 后才注册 MediaSession，
 * 并随播放进度把当前歌词行写入 MediaSession 元数据的 title 字段，
 * 手机系统蓝牙 AVRCP 会自动将其推送给眼镜，触发眼镜端系统歌词显示（复刻音乐 App 行为）。
 */
object MusicPlayerController {
    private const val TAG = "MusicPlayerController"
    private const val MEDIA_SESSION_TAG = "RokidLabMusic"
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

    private var mediaSession: MediaSession? = null
    private var lyricHandler: Handler? = null
    private var currentLineText: String = ""

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
    ) {
        stop()
        currentTitle = title
        currentArtist = artist
        currentLyrics = lyrics
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

    /** 停止并释放当前播放器，清空歌曲信息与音频焦点；同时退出歌词模式（MediaSession 复用不释放） */
    fun stop() {
        stopLyricsInternal()
        pushStoppedState()
        val p = player ?: run {
            abandonAudioFocus()
            return
        }
        player = null
        currentTitle = ""
        currentArtist = ""
        isLoading = false
        runCatching { p.stop() }
        runCatching { p.release() }
        abandonAudioFocus()
        Log.i(TAG, "music stopped")
    }

    // ── 歌词模式 ──

    /**
     * 开启歌词模式：注册 MediaSession 并随播放进度把当前歌词行写入 title 字段，
     * 经蓝牙 AVRCP 推送到眼镜端显示。
     * @return 是否成功开启（未在播放或歌词为空时返回 false）
     */
    fun startLyrics(context: Context, lines: List<KuwoMusicApi.LyricLine>): Boolean {
        val mp = player
        if (mp == null || !isPlaying()) {
            Log.w(TAG, "startLyrics skipped: not playing")
            return false
        }
        if (lines.isEmpty()) {
            Log.w(TAG, "startLyrics skipped: empty lyrics")
            return false
        }
        stopLyricsInternal()
        currentLyrics = lines
        isLyricsMode = true
        currentLineText = ""
        ensureMediaSession(context.applicationContext)
        // 复用同一 session 实例（首次创建后永不释放/永不销毁），确保蓝牙 AVRCP
        // 层的 MediaController 监听不中断，歌词行更新才能持续推送到眼镜。
        mediaSession?.setActive(true)
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

    /** 推送停止状态：经 AVRCP 告知眼镜音乐已停止并清空标题，眼镜据此关闭歌词页 */
    private fun pushStoppedState() {
        val ms = mediaSession ?: return
        runCatching {
            ms.setPlaybackState(
                PlaybackState.Builder()
                    .setActions(
                        PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                            PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP
                    )
                    .setState(PlaybackState.STATE_STOPPED, 0L, 0f)
                    .build()
            )
            ms.setMetadata(
                MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, "")
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, "")
                    .build()
            )
        }
        Log.i(TAG, "stopped state pushed to avrcp")
    }

    private fun ensureMediaSession(context: Context) {
        if (mediaSession != null) return
        mediaSession = MediaSession(context, MEDIA_SESSION_TAG).apply {
            setActive(true)
            setFlags(
                MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            // 处理蓝牙 AVRCP 媒体按键：官方 AI 接管"停止播放"语音时经蓝牙下发
            // MEDIA_PAUSE/MEDIA_STOP，若不处理则音乐继续播放（实测语音停止失效）。
            // onPause/onStop → 停止并释放播放器。
            // 注意：必须显式传入主线程 Handler——setCallback 在无 Looper 的工具执行线程
            // 调用时，内部用 Looper.myLooper() 构造回调 Handler 会得到 null 导致 NPE。
            setCallback(
                object : MediaSession.Callback() {
                    override fun onPlay() {
                        val p = player
                        if (p != null && !p.isPlaying) runCatching { p.start() }
                    }

                    override fun onPause() {
                        if (player?.isPlaying == true) stop()
                    }

                    override fun onStop() {
                        if (player != null) stop()
                    }
                },
                Handler(Looper.getMainLooper())
            )
            setPlaybackState(
                PlaybackState.Builder()
                    .setActions(
                        PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                            PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP
                    )
                    .setState(PlaybackState.STATE_PLAYING, 0L, 1.0f)
                    .build()
            )
        }
    }

    /** 按播放进度定位当前歌词行；歌词行变化时更新 MediaSession 元数据（title = 歌词行） */
    private fun updateLyricLine(mp: MediaPlayer) {
        val pos = runCatching { mp.currentPosition }.getOrDefault(0)
        var line = currentTitle
        for (l in currentLyrics) {
            if (pos >= l.timeMs) line = l.text else break
        }
        val ms = mediaSession ?: return
        // 关键：PlaybackState 必须随播放进度实时更新（position 变化）。
        // 小米 ROM 的 AVRCP 只在"session 持续活跃"时转发 metadata 更新，
        // 若 PlaybackState 固定不变（实测 position=0 不动），系统判定 session
        // 无活动，逐行 title 更新（歌词）不会被推送到蓝牙眼镜端。
        ms.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP
                )
                .setState(PlaybackState.STATE_PLAYING, pos.toLong(), 1.0f)
                .build()
        )
        if (line == currentLineText) return
        currentLineText = line
        val duration = runCatching { mp.duration.toLong() }.getOrDefault(0L)
        ms.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, line)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, currentArtist)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, duration)
                .build()
        )
        Log.i(TAG, "lyric line: $line")
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
