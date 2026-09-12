package com.rokidlab.phone.platform

import android.app.Notification
import android.app.NotificationChannel
import android.app.PendingIntent
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaDescription
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent

/**
 * L0 platform/AvrcpLyricBridge —— 蓝牙 AVRCP 歌词推送边界（Phase 2 收尾）。
 *
 * **定位（唯一歌词通道）**：眼镜端**自带歌词**由蓝牙 AVRCP 元数据驱动 ——
 * 手机侧把**当前歌词行写到 `METADATA_KEY_TITLE`**（眼镜官方音乐页读取的字段，见下「★ 字段真相」），
 * 歌名/歌手经 `ARTIST` 等携带；系统蓝牙 AVRCP 自动推送给眼镜，用 `PlaybackState.position` 做逐行同步。
 *
 * ★ 字段真相（2026-09-12 抓包+基线核对纠正）：
 *  - v3.2 能显示歌词的基线、以及汽水音乐，都是把**歌词行写进 `METADATA_KEY_TITLE`**（首行），
 *    后续行写进 `DISPLAY_SUBTITLE` / `DISPLAY_DESCRIPTION`。
 *  - `dumpsys media_session` 里那行 `description=歌词行1, 歌词行2, 歌词行3` 实际是 `MediaDescription`
 *    的 `title, subtitle, description` 三段（不是单个 description 字段）——正是 TITLE/SUBTITLE/DESCRIPTION
 *    各放一行歌词。眼镜读的就是这三个字段来渲染逐行歌词。
 *  - 因此歌词 MUST 走 TITLE（及 SUBTITLE/DESCRIPTION），**绝不能**把歌名塞进 TITLE、歌词塞进
 *    DISPLAY_DESCRIPTION（那样眼镜只读 TITLE=歌名，歌词体整段消失 → 只剩歌名+歌手）。
 *  - 单行歌词文本要短（当前行+少量后续行纯文本即可），不要塞整首 LRC。
 *
 * ⚠️ 历史教训：曾一度在本类加「眼镜端支持悬浮层就关闭 AVRCP 元数据推送」的闸门，
 * 结果**直接导致眼镜自带歌词收不到任何歌词行**（两个路径互相否决）。已移除该闸门：
 * 眼镜自带歌词（AVRCP）是**唯一正确路径**，不得被任何能力位关闭。
 *
 * **三条不可改的实测约束**（改动前务必先读）：
 * 1. MediaSession 复用同一实例、**永不 release**：`release()` 触发系统销毁 session，
 *    蓝牙 AVRCP 层随之丢失 MediaController 监听，此后所有歌词行都不再推送。
 * 2. PlaybackState 必须随播放进度**实时更新 position**：小米 ROM 的 AVRCP 仅在
 *    "session 持续活跃"时转发 metadata 更新；position 固定不动会被判定无活动，逐行歌词不推送。
 * 3. `setCallback` 必须**显式传入主线程 Handler**：工具执行线程无 Looper，
 *    内部 `Looper.myLooper()` 为 null 会导致 NPE。
 *
 * 媒体按键（MEDIA_PAUSE/MEDIA_STOP）回调与歌词元数据推送解耦：即使关闭元数据推送，
 * 媒体按键仍需经本 session 接管（否则官方 AI 语音"停止播放"失效）。
 */
object AvrcpLyricBridge {
    private const val TAG = "AvrcpLyricBridge"
    /** 与 v3.2 既有实现保持一致的 session tag（ROM 侧可能按 tag 关联媒体会话，勿改） */
    private const val MEDIA_SESSION_TAG = "RokidLabMusic"

    private const val CHANNEL_ID = "rokidlab_music"
    private const val CHANNEL_NAME = "音乐播放"
    private const val NOTIFICATION_ID = 0xA1

    /**
     * 是否把封面 Bitmap 写进元数据（`METADATA_KEY_ART`）——**手机端播放器/媒体卡片封面靠它**。
     *
     * ⚠️ 历史误判纠正（2026-09-12）：曾把 `Metadata currently out of sync` + `media update timeout`
     * 归因于「Bitmap 塞进元数据」。**实际根因是队列项缺 artist/album**（见 [setLyricsQueue] 注释），
     * 与封面无关 —— androidx/media 的同类 issue 里应用带封面也照样只报队列项字段不匹配。
     * 故此处恢复开启，手机通知栏/媒体卡片封面才能显示；眼镜端取不取封面无所谓。
     *
     * ⚠️ 唯一真实约束：Bitmap 会随 MediaMetadata 走 Binder 传参（SystemUI / Bluetooth 进程），
     * Binder 单次事务上限约 1MB。ARGB_8888 下 600×600 ≈ 1.44MB **超限**，
     * 故 [MusicPlayerController.loadCoverAsync] 的缩放上限收紧到 320px（≈410KB）。
     * 若将来又出现 out of sync，第一个可回退项就是本开关。
     */
    private const val SEND_ART_IN_METADATA = true

    /** 供通知/续期使用的应用上下文（[ensure] 时注入） */
    @Volatile
    private var appContext: Context? = null

    /** 媒体按键回调（[ensure] 注入；由 [dispatchMediaButton] 转发） */
    @Volatile
    private var onPlayAction: (() -> Unit)? = null

    @Volatile
    private var onPauseAction: (() -> Unit)? = null

    @Volatile
    private var onStopAction: (() -> Unit)? = null

    @Volatile
    private var mediaSession: MediaSession? = null

    /** 是否已创建 MediaSession（诊断用）。 */
    val isSessionCreated: Boolean get() = mediaSession != null

    /** 当前会话是否活跃（诊断用）。 */
    fun isActive(): Boolean = mediaSession?.isActive == true

    /** 专辑封面 Bitmap（[setArt] 注入；经 `METADATA_KEY_ART` 随元数据发给眼镜端音乐页） */
    @Volatile
    private var artBitmap: Bitmap? = null

    /** 设置/清除专辑封面（异步下载完成后调用；传 null 即清除）。 */
    fun setArt(bitmap: Bitmap?) {
        artBitmap = bitmap
    }

    /**
     * 确保 MediaSession 存在并接管媒体按键。**不释放**（见类注释约束 1）。
     *
     * @param onPlay/onPause/onStop 蓝牙 AVRCP 媒体按键回调（由播放器实现，必须幂等）
     */
    @Suppress("DEPRECATION")
    fun ensure(
        context: Context,
        onPlay: () -> Unit,
        onPause: () -> Unit,
        onStop: () -> Unit,
    ) {
        if (mediaSession != null) return
        appContext = context.applicationContext
        onPlayAction = onPlay
        onPauseAction = onPause
        onStopAction = onStop
        mediaSession = MediaSession(context.applicationContext, MEDIA_SESSION_TAG).apply {
            setActive(true)
            setFlags(
                MediaSession.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            // 约束 3：显式主线程 Handler
            setCallback(
                object : MediaSession.Callback() {
                    override fun onPlay() = onPlay()
                    override fun onPause() = onPause()
                    override fun onStop() = onStop()
                },
                Handler(Looper.getMainLooper()),
            )
            setPlaybackState(playingState(0L))
            // 关键：会话建立即写入一条带 MEDIA_ID 的初始元数据，确保 AVRCP Target 在创建
            // MediaController 那一刻 getMetadata() 能取到非空内容。否则其 MediaPlayerWrapper
            // 初始 track 为 null、之后 onMetadataChanged 又没刷进 Current Data → 眼镜端收到
            // "Not Provided" 全空白（实测 dumpsys bluetooth_manager 的 AvrcpTargetService.Current Data）。
            setMetadata(
                MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, "乐奇音乐")
                    .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, "lab-init")
                    .build()
            )
        }
        // 关联媒体按键接收器：AOSP 的 AVRCP MediaPlayerList 用
        // `getActiveSessions(mediaButtonReceiver 组件)` 挑选会话，未关联者会被漏掉
        // （实测 dumpsys media_session 里本会话 mediaButtonReceiver=null，AVRCP 查不到播放器）。
        runCatching {
            val mi = Intent(Intent.ACTION_MEDIA_BUTTON)
                .setClass(context.applicationContext, com.rokidlab.phone.music.LabMediaButtonReceiver::class.java)
            val pi = PendingIntent.getBroadcast(
                context.applicationContext, 0, mi,
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            mediaSession?.setMediaButtonReceiver(pi)
            Log.i(TAG, "media button receiver attached (for AVRCP active-session matching)")
        }.onFailure { Log.e(TAG, "setMediaButtonReceiver failed (AVRCP 可能仍查不到本会话)", it) }
        Log.i(TAG, "media session created (avrcp media buttons taken over)")
        // 会话晚于队列建立时（先 play 后说「显示歌词」）也要把队列挂上
        if (lyricQueue.isNotEmpty()) {
            runCatching { mediaSession?.setQueue(lyricQueue) }
            Log.i(TAG, "lyric queue re-applied on session create: ${lyricQueue.size} items")
        }
        // 关键：必须为会话配一条「媒体通知」。
        // Android 12+ 起，**没有媒体通知的 MediaSession 不会被系统视为活跃会话**，
        // 系统的 AVRCP Target 因此查不到任何播放器 —— 实测 `AvrcpTargetJni.getCurrentPlayStatus`
        // 恒返回 position=0 duration=0 state=0，眼镜端拿到 `title: Not Provided`，
        // 于是「系统歌词页拉起来了但没有内容」。v3.2 时代（旧 targetSdk）无此限制，故当时可用。
        postMediaNotification(null)
    }

    /** 通知 session 活跃（歌词模式开启）。 */
    fun activate() {
        runCatching { mediaSession?.setActive(true) }
    }

    /**
     * 推送一行歌词：更新 PlaybackState 进度（约束 2）+ title 元数据。
     * @return true = 实际写入了元数据（内容变化或首次）；false = 未推送（无 session / 内容未变化）
     */
    /** 每次推送递增的「媒体 id」序号 —— 见 [pushLine] 注释。 */
    private val pushSeq = java.util.concurrent.atomic.AtomicLong(0)

    /**
     * 歌词播放队列（一行 = 一个队列项）。
     *
     * ★ 为什么必须有队列：实测 ROM 判定「换曲」的依据是**播放队列的当前项**（日志
     * `MediaPlayerList: sendMediaUpdate: Creating a one item queue for a player with no queue`），
     * 会话没有队列时它只合成一个恒定项 → 换 title 不被视为曲目变化 →
     * 只在会话建立那一瞬推过一次 `metadata=true`，之后每行歌词**零推送**，
     * 眼镜端于是停在第一行（或干脆不刷新）。
     */
    private var lyricQueue: List<MediaSession.QueueItem> = emptyList()

    /**
     * 设置歌词队列（一行一项，queueId = 行号）；会话已存在时立即下发。
     *
     * ★★ 队列项必须带 artist（→ `MediaDescription.subtitle`）与 album（→ `.description`）。
     *
     * AOSP/ROM 的 `MediaPlayerWrapper.getCurrentQueue()` 明确按**「title + artist + album」**
     * 比对「播放队列当前项」与「当前元数据」，源码注释：
     *   `// MediaDescription is usually compared via its title, artist and album.`
     *   `// if one of the informations is missing we can't assume it is the same media.`
     *
     * 实测 logcat 实证（本类曾只设 title，故 artist/album 为空）：
     *   `Current queueItem: { title="名诗读了几多遍" artist=""    album="" }`
     *   `Current metadata : { title="名诗读了几多遍" artist="后弦" album="古·玩" }`
     * → 每 tick 判 `Metadata currently out of sync` + `trySendMediaUpdate(): Starting media update timeout`
     * → 媒体更新**永远不完成**，眼镜端只能拿到最开始那一条 now-playing，**一条歌词都收不到**。
     *
     * 对照 androidx/media 官方修同一 bug 的提交标题即：
     * 「Use the artist as the subtitle of the legacy media description」——给队列项补 artist。
     *
     * @param artist 歌手（写入队列项 subtitle，必须与元数据 `METADATA_KEY_ARTIST` 一致）
     * @param album  专辑（写入队列项 description，必须与元数据 `METADATA_KEY_ALBUM` 一致）
     */
    fun setLyricsQueue(lines: List<String>, artist: String = "", album: String = "") {
        lyricQueue = lines.mapIndexed { i, text ->
            val desc = MediaDescription.Builder()
                .setMediaId("lab-line-$i")
                .setTitle(text)
            if (artist.isNotBlank()) desc.setSubtitle(artist)
            if (album.isNotBlank()) desc.setDescription(album)
            MediaSession.QueueItem(desc.build(), i.toLong())
        }
        val ms = mediaSession ?: return
        runCatching { ms.setQueue(lyricQueue) }
        Log.i(TAG, "lyric queue set: ${lyricQueue.size} items")
    }

    /**
     * 为「无歌词的 now-playing」阶段下发一条**单项队列**，其 mediaId 与元数据 mediaId 相同。
     *
     * ★ 为什么必须这么做（实测 logcat 实证）：
     * `pushNowPlaying` 发生在 `setLyricsQueue` 之前（甚至整首歌无歌词时永远不会有歌词队列）。
     * 会话没有队列时，ROM 的 `MediaPlayerWrapper` 会**自己合成**一个单项队列
     * （日志：`queueItem: { mediaId="NowPlayingId23" ... }`，而我们的元数据 mediaId 是 `lab-now-N`），
     * 两者 mediaId 不一致 → 每 tick 打印
     * `Metadata currently out of sync for com.rokidlab.phone` + `trySendMediaUpdate(): Starting media update timeout`
     * → 整条 media update 卡在超时重试、**永远不完成** → 眼镜端收到 `title: Not Provided` 全空白。
     * 因此这里把队列项 mediaId 显式对齐元数据 mediaId，让 wrapper 判定 in sync 并真正下发。
     *
     * 有歌词时该单项队列随后会被 [setLyricsQueue] 的整首队列覆盖，无副作用。
     */
    private fun ensureNowPlayingQueue(mediaId: String, title: String, artist: String, album: String = "") {
        val ms = mediaSession ?: return
        val desc = MediaDescription.Builder()
            .setMediaId(mediaId)
            .setTitle(title.ifBlank { "乐奇音乐" })
        // subtitle/description 必须与元数据的 ARTIST/ALBUM 一致，否则 wrapper 判 out of sync
        if (artist.isNotBlank()) desc.setSubtitle(artist)
        if (album.isNotBlank()) desc.setDescription(album)
        val item = MediaSession.QueueItem(desc.build(), 0L)
        lyricQueue = listOf(item)
        runCatching { ms.setQueue(lyricQueue) }
    }

    /**
     * 推送一行播放进度：更新 PlaybackState 进度（约束 2）+ 元数据。
     *
     * ★ 歌词走 `METADATA_KEY_TITLE`（首行）+ `DISPLAY_SUBTITLE`（次行）+ `DISPLAY_DESCRIPTION`（再次行）——
     * 眼镜官方音乐页读取的就是这三个字段渲染逐行歌词（汽水音乐实测：其 metadata 的
     * `title, subtitle, description` 三段即为逐行歌词；v3.2 基线仅用 TITLE 即能让眼镜显示歌词）。
     * 不要把歌词行塞进别的字段：眼镜只读 TITLE 显示歌词，若 TITLE 放了歌名，歌词体就整段消失。
     *
     * ★ 每行写唯一 MEDIA_ID + 以「当前歌词行变化」为去重判据：ROM 只在会话激活 / 曲目（或当前行）变化
     * 时向眼镜下发 MEDIA_METADATA；当前歌词行一变就重推，眼镜端才能逐行刷新。
     *
     * @param songTitle 歌名（无歌词行时兜底写入 TITLE，避免眼镜显示空）
     * @param artist    歌手
     * @param lastLine  上一次写入的首行歌词文本（去重用；仅当前行变化时重设元数据）
     * @param lineIndex 当前歌词行号 —— 同时作为**队列当前项 id**，让 ROM 认作「换曲」而重发元数据
     * @param line1     当前歌词行（写入 TITLE）
     * @param line2     下一行歌词（写入 DISPLAY_SUBTITLE，可空）
     * @param line3     下下行歌词（写入 DISPLAY_DESCRIPTION，可空）
     * @param force     true = 即使歌词行未变也强制重设元数据（说「显示歌词」/ 播放后补推）
     * @return true = 实际写入了元数据
     */
    fun pushLine(
        songTitle: String,
        artist: String,
        album: String = "",
        durationMs: Long,
        positionMs: Long,
        lastLine: String,
        lineIndex: Int = 0,
        line1: String = "",
        line2: String = "",
        line3: String = "",
        force: Boolean = false,
    ): Boolean {
        val ms = mediaSession ?: return false
        // 约束 2：每 tick 都要更新 PlaybackState 的 position（否则 ROM 判定 session 无活动、不转发 metadata）；
        // 并把「队列当前项」推到本行 → ROM 认为是换曲 → 重新下发 MEDIA_METADATA
        runCatching { ms.setPlaybackState(playingState(positionMs, lineIndex.toLong())) }
        // 仅当前歌词行（首行）变化或强制时才重设元数据（避免每 tick 重复 setMetadata）；位置仍每 tick 更新
        if (line1 == lastLine && !force) return false
        val shownTitle = line1.ifBlank { songTitle }
        runCatching {
            val b = MediaMetadata.Builder()
                // 歌词首行 → TITLE（眼镜据此渲染歌词；无歌词时退化为歌名）
                .putString(MediaMetadata.METADATA_KEY_TITLE, shownTitle)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, durationMs)
                .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, "lab-line-$lineIndex")
            // 专辑：眼镜端音乐页/AvrCP 属性里 ALBUM_NAME 之前恒为 Unavailable（实测 21:52:55）
            if (album.isNotBlank()) b.putString(MediaMetadata.METADATA_KEY_ALBUM, album)
            // ★ 绝不写 DISPLAY_SUBTITLE / DISPLAY_DESCRIPTION：
            //   `MediaMetadata.getDescription()` 会用它们顶掉 subtitle/description（进而顶掉 artist/album），
            //   而 AVRCP 的 MediaPlayerWrapper 正是按 MediaDescription 的 title+artist+album 与队列项比对，
            //   写了歌词行就会让 artist/album 变成歌词 → 又判 out of sync（与队列项永远对不上）。
            //   眼镜歌词只需 TITLE（v3.2 基线实测有效），多行上下文不做。
            // 专辑封面 → ART（手机端通知栏/媒体卡片封面；见 SEND_ART_IN_METADATA 说明）
            if (SEND_ART_IN_METADATA) artBitmap?.let { b.putBitmap(MediaMetadata.METADATA_KEY_ART, it) }
            ms.setMetadata(b.build())
        }
        if (force) runCatching { ms.setActive(true) }
        postMediaNotification(shownTitle)
        return true
    }

    /**
     * 推送「正在播放」基础信息（歌曲名/歌手/专辑），不含歌词行。
     *
     * 在播放真正开始、但歌词尚未逐行刷新时调用，使 AVRCP 立即上报当前曲目 ——
     * 眼镜端据此认到「正在播放」（无歌词的歌曲也至少能显示歌名）。
     * 语义上等价于汽水音乐「车载蓝牙歌词」模式开启时、尚未滚到歌词行之前的 now-playing 状态。
     */
    fun pushNowPlaying(title: String, artist: String, album: String, durationMs: Long) {
        val ms = mediaSession ?: return
        // ★ 元数据 mediaId 必须与「队列当前项」mediaId 完全一致，否则 MediaPlayerWrapper 判
        // out of sync（见 [ensureNowPlayingQueue]）。二者共用同一个 mediaId 变量，杜绝漂移。
        val mediaId = "lab-now-${pushSeq.incrementAndGet()}"
        ensureNowPlayingQueue(mediaId, title, artist, album)
        runCatching {
            val b = MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, artist)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, durationMs)
                // 必须带 MEDIA_ID，AVRCP Target 才认作「换曲」并把 MediaController 的元数据
                // 刷进 Current Data（否则其 MediaPlayerWrapper 停在 null，眼镜端拿到 "Not Provided"）。
                .putString(MediaMetadata.METADATA_KEY_MEDIA_ID, mediaId)
            if (album.isNotBlank()) b.putString(MediaMetadata.METADATA_KEY_ALBUM, album)
            if (SEND_ART_IN_METADATA) artBitmap?.let { b.putBitmap(MediaMetadata.METADATA_KEY_ART, it) }
            ms.setMetadata(b.build())
        }
        postMediaNotification(null)
        Log.i(TAG, "now playing pushed: $title - $artist (session active=${isActive()})")
    }

    /** 推送停止状态：清空 title/artist，眼镜端据此关闭系统歌词页。 */
    fun pushStopped() {
        val ms = mediaSession ?: return
        runCatching {
            ms.setPlaybackState(
                PlaybackState.Builder()
                    .setActions(TRANSPORT_ACTIONS)
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
        // 停止后撤掉媒体通知（避免常驻残留）
        runCatching {
            (appContext?.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager)
                ?.cancel(NOTIFICATION_ID)
        }
        Log.i(TAG, "stopped state pushed to avrcp")
    }

    /** 媒体按键转发（来自 [com.rokidlab.phone.music.LabMediaButtonReceiver]）。 */
    fun dispatchMediaButton(event: KeyEvent) {
        if (event.action != KeyEvent.ACTION_DOWN) return
        when (event.keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY -> onPlayAction?.invoke()
            KeyEvent.KEYCODE_MEDIA_PAUSE -> onPauseAction?.invoke()
            KeyEvent.KEYCODE_MEDIA_STOP -> onStopAction?.invoke()
            else -> Log.d(TAG, "unhandled media key: ${event.keyCode}")
        }
    }

    /**
     * 发布/更新「媒体通知」——让系统把本会话当作**活跃媒体会话**（AVRCP Target 才会绑定）。
     *
     * 说明：Android 12+ 对后台媒体会话收紧后，**没有媒体通知的会话不算活跃**；
     * 这里用低优先级、静音、常驻的通知，只作为「系统可见性凭据」，不做交互。
     *
     * @param lyricLine 当前歌词行（作为通知副标题，可为 null）
     */
    private fun postMediaNotification(lyricLine: String?) {
        val ctx = appContext ?: return
        val ms = mediaSession ?: return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        runCatching {
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW).apply {
                        setShowBadge(false)
                        enableVibration(false)
                        setSound(null, null)
                    }
                )
            }
            val n = Notification.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("乐奇实验室 · 音乐")
                .setContentText(lyricLine ?: "正在播放")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setStyle(Notification.MediaStyle().setMediaSession(ms.sessionToken))
            // 手机端封面：通知大图标（与元数据 `METADATA_KEY_ART` 双保险，眼镜端不受影响）
            artBitmap?.let { runCatching { n.setLargeIcon(it) } }
            nm.notify(NOTIFICATION_ID, n.build())
        }.onFailure { Log.e(TAG, "postMediaNotification failed (会话将不被系统视为活跃，AVRCP 元数据可能不上报)", it) }
    }

    private val TRANSPORT_ACTIONS: Long =
        PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP

    /** 约束 2：position 必须随进度变化，否则 ROM 判定 session 无活动、不转发 metadata。 */
    /**
     * @param queueItemId >=0 时同时把「当前播放队列项」指到该行（ROM 判换曲的依据）；
     *        -1 表示不带队列信息（停止态等）。
     */
    private fun playingState(positionMs: Long, queueItemId: Long = -1L): PlaybackState {
        val b = PlaybackState.Builder()
            .setActions(TRANSPORT_ACTIONS)
            .setState(PlaybackState.STATE_PLAYING, positionMs, 1.0f)
        if (queueItemId >= 0L) b.setActiveQueueItemId(queueItemId)
        return b.build()
    }
}
