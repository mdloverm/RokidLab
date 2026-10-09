package com.rokidlab.phone.demo

import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.util.Log
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 双端录像拼屏合成器：左 = 眼镜（旋转 90°），右 = 手机；音频取手机轨。
 *
 * ## 架构（时钟驱动 —— 为什么不能由源帧驱动）
 *
 * 双端录屏素材都是「画面动了才有帧」：静止时 screenrecord / MediaCodec surface
 * 输入零帧，60s 素材可能只有几十帧。早期实现按「源来一帧渲一帧」驱动渲染循环，
 * 手机画面静止时循环干等 —— 真机 2 分钟只渲了不到 100 帧（观感"合成极慢"）。
 *
 * 现架构：
 * - **解码泵线程**（每流一个）：解出一帧后**持有输出缓冲不释放**，直到渲染同步点
 *   追上该帧 pts 才 `release(render=true)` —— 用「持有」而非「按 pts 限流」，
 *   每流在途帧恒为 1，画面严格按自身时间戳推进（见 [Player] 的加速成因说明）；
 * - **渲染主循环**：固定 30fps 时钟步进，有新帧才 `updateTexImage`，没有就重画
 *   上一纹理 —— SurfaceTexture 单缓冲特性天然实现「定格补齐」（静止画面/素材耗尽
 *   均显示最后一帧，不缺时长）；
 * - **音频**：手机 AAC 轨经 MediaExtractor 直拷进 Muxer（不重编码），
 *   pts 平移 phoneDelayUs 与手机画面起点对齐。
 *
 * 多段：段 i 窗口起点 = segValidStart[i]（裁预卷重叠），泵线程惰性按序启动；
 * 窗口未到的段的帧被自然丢弃（不显示）。
 *
 * ## 为什么不用 ffmpeg（用户提过，最终决定自研）
 *
 * 容器（[com.rokidlab.phone.platform.ProotShell]）确实能 apt 装 ffmpeg，但没走这条路：
 * 1. 当时「合成极慢」的根因是渲染被源帧驱动（见上），换 ffmpeg 治不了 —— 改时钟驱动即解决；
 * 2. 装包实测十几分钟起（[com.rokidlab.phone.ai.tools.ShellToolProvider] 为此把装包超时
 *    放宽到 900s），首次成本落在录制流程里不可接受，且 rootfs/装包失败会让录像功能整体不可用；
 * 3. MediaCodec + GLES 是系统自带能力，零外部依赖、零体积增量。
 *
 * **重开条件**：需要转场、多路混音、字幕烧录、变速等 ffmpeg 才划算的合成形态时，改走容器。
 */
object SideBySideCompositor {
    private const val TAG = "SideBySideCompositor"

    /** 拼屏画布高：取眼镜原生高，两侧均不放大（眼镜 480x640 原样，手机等比缩小） */
    private const val CANVAS_H = 640

    /** 输出码率：演示视频足够（175s ≈ 66MB） */
    private const val BIT_RATE = 3_000_000

    /** 输出帧率（渲染时钟步进 1/30s） */
    private const val FPS = 30

    /**
     * 眼镜源旋转角度（0/90/180/270）。
     *
     * **真机实测 = 0**：解析眼镜端 screenrecord 产物的 tkhd —— width=480 height=640
     * 且旋转矩阵为单位阵，即输出**已经是竖直画面**（HWC 已把 640x480 横缓冲旋成视觉竖屏）。
     * 早前按"横缓冲需旋转"加 90°/270° 反而把画面转倒（用户反馈"方向不对"）。
     */
    private const val GLASSES_ROTATION_DEG = 0

    class Probe internal constructor(
        val durationUs: Long,
        val width: Int,
        val height: Int,
    )

    /**
     * 探测视频的时长与分辨率。**时长取各轨样本跨度的最大值**，分辨率取视频轨。
     *
     * ## 为什么不能只看视频轨（真机教训）
     * 屏录素材是「画面动了才有帧」：手机端录 App 界面时整段几乎静止，
     * 真机实测 5s 的手机源视频轨只有 **2 帧**（跨度 30ms），而音频轨有 216 帧（5.01s）。
     * 早期实现只扫视频轨，得到 30ms → 被判定为「元数据损坏」并退化成 60s 常量，
     * 用户录 5s 却得到 59s 成片。音频轨采样连续、pts 由样本数积分而来（不依赖墙钟），
     * 是最可靠的时长基准，因此按各轨最大值取。
     *
     * ## 为什么不用容器声明的 duration
     * 手机视频帧由 VirtualDisplay 直推编码器 Surface，pts 是系统墙钟，
     * MediaMuxer 会在 mvhd/mdhd 写饱和值 0x7FFFFFFF（≈59.6 小时）。
     * 遍历样本取「末 pts - 首 pts」对任何时间基准都成立。
     */
    fun probe(path: String): Probe {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(path)
            var w = 0
            var h = 0
            var dur = 0L
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                // 样本扫描（仅走 sample table，不读数据体）：首末 pts 差即该轨时长
                ex.selectTrack(i)
                var first = ex.sampleTime
                var last = first
                var count = 0
                while (ex.advance()) {
                    last = ex.sampleTime
                    count++
                }
                ex.unselectTrack(i)
                val scanned = if (count > 0) last - first else 0L
                if (scanned > dur) dur = scanned
                if (mime.startsWith("video/") && w <= 0) {
                    w = if (f.containsKey(MediaFormat.KEY_WIDTH)) f.getInteger(MediaFormat.KEY_WIDTH) else 0
                    h = if (f.containsKey(MediaFormat.KEY_HEIGHT)) f.getInteger(MediaFormat.KEY_HEIGHT) else 0
                }
                Log.i(TAG, "probe $path track$i $mime samples=$count span=${scanned / 1000}ms")
            }
            Log.i(TAG, "probe $path: dur=${dur / 1000}ms ${w}x$h")
            return Probe(dur.coerceAtLeast(0L), w, h)
        } catch (e: Exception) {
            Log.e(TAG, "probe $path failed: ${e.message}")
        } finally {
            runCatching { ex.release() }
        }
        return Probe(0, 0, 0)
    }

    /**
     * 拼屏合成主入口。
     *
     * @param glassesSegments (本地路径, 文件出现时刻 epoch ms) 按录制顺序
     * @param phoneDelayMs 手机起录相对眼镜 seg0 文件出现的延迟（眼镜先起，输出前段手机侧为首帧定格）
     * @return true = 输出文件已生成
     */
    fun compose(
        glassesSegments: List<Pair<String, Long>>,
        phoneSegment: String,
        phoneDelayMs: Long,
        output: String,
        onProgress: (Float) -> Unit,
    ): Boolean {
        require(glassesSegments.isNotEmpty()) { "no glasses segments" }
        val segInfos = glassesSegments.map { probe(it.first) }
        val phone = probe(phoneSegment)
        // 手机源是时间基准，必须有效。眼镜段允许时长≈0：静止画面 screenrecord 不产帧，
        // 渲染循环的定格特性会补齐。
        if (phone.durationUs <= 0 || phone.width <= 0 || phone.height <= 0 ||
            segInfos.any { it.width <= 0 || it.height <= 0 }
        ) {
            Log.e(TAG, "probe failed: segs=${segInfos.map { it.durationUs }} phone=${phone.durationUs}")
            return false
        }
        if (segInfos.any { it.durationUs <= 0 }) {
            Log.i(TAG, "glasses segment(s) near-static: durs=${segInfos.map { it.durationUs }}ms, freeze fills")
        }

        val appear0 = glassesSegments[0].second
        val segStartG = glassesSegments.map { (it.second - appear0).coerceAtLeast(0L) }
        // 各段窗口起点（全局 ms）：seg0 = phoneDelayMs（双端对齐）；segN = 上一段窗口终点（裁预卷重叠）
        val segValidStart = LongArray(segInfos.size)
        segValidStart[0] = phoneDelayMs
        for (i in 1 until segInfos.size) {
            segValidStart[i] = segStartG[i - 1] + segInfos[i - 1].durationUs / 1000
        }
        // 输出时长 = 手机源时长（probe 取各轨样本跨度最大值，音频轨保证静止画面下仍准确）。
        // 不做「疑似过短就兜底成 60s」的猜测：真机上那会把 5s 的录制撑成 59s 成片。
        val outDurUs = phone.durationUs

        // 布局：两侧各自等高 CANVAS_H，宽按「旋转后视觉」宽高比；canvas 宽取偶（编码器要求）
        val swap = GLASSES_ROTATION_DEG == 90 || GLASSES_ROTATION_DEG == 270
        val gVisW = if (swap) segInfos[0].height else segInfos[0].width
        val gVisH = if (swap) segInfos[0].width else segInfos[0].height
        val gW = (CANVAS_H.toFloat() * gVisW / gVisH).toInt().coerceAtLeast(2)
        val pW = (CANVAS_H.toFloat() * phone.width / phone.height).toInt().coerceAtLeast(2)
        val canvasW = (gW + pW + 1) / 2 * 2

        val out = File(output)
        out.parentFile?.mkdirs()
        out.delete()

        var muxer: MediaMuxer? = null
        var encoder: MediaCodec? = null
        var egl: EglCore? = null
        var audioThread: Thread? = null
        val players = ArrayList<Player>()
        try {
            val mx = MediaMuxer(output, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = mx
            val encFmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, canvasW, CANVAS_H).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                // Baseline 无 B 帧：surface 输出严格按 pts 单调（MediaMuxer 按写入序建 stts，
                // 有 B 帧时输出序 pts 乱跳 → mp4 时间轴损坏，真机表现"播放 9 秒后卡死"）
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
                setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31)
                setInteger(MediaFormat.KEY_LATENCY, 0)
            }
            val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            encoder = enc
            enc.configure(encFmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val encSurface = enc.createInputSurface()
            enc.start()

            val gl = EglCore(encSurface)
            egl = gl
            val tracks = Tracks(mx)

            // 播放器（解码泵 + GL 纹理）：纹理必须在 EGL 当前线程创建
            val segPlayers = glassesSegments.map { Player(it.first) }
            val phonePlayer = Player(phoneSegment)
            (segPlayers + phonePlayer).forEach { it.initGl() }
            players += segPlayers
            players += phonePlayer

            // 音频直拷线程（pts 平移与手机画面起点对齐）
            audioThread = Thread { copyAudio(phoneSegment, phoneDelayMs * 1000, outDurUs, mx, tracks) }.apply {
                name = "demo-audio-copy"
                start()
            }

            // ── 渲染主循环：时钟驱动 30fps（不受源帧率影响）──
            val renderer = GlRenderer(canvasW, gW)
            val frameMs = 1000L / FPS
            val frameUs = 1_000_000L / FPS
            var outUs = 0L
            val startedAt = System.currentTimeMillis()
            while (outUs < outDurUs) {
                val stepStart = System.currentTimeMillis()
                // 当前眼镜段：最后一个窗口已开的段（段素材耗尽后定格其末帧）
                var cur = 0
                for (i in segPlayers.indices) {
                    if (segValidStart[i] * 1000 <= outUs) cur = i
                }
                val segP = segPlayers[cur]
                segP.pump(outUs - segValidStart[cur] * 1000)
                if (segP.newFrameReady()) segP.updateTexImage()
                phonePlayer.pump(outUs - phoneDelayMs * 1000)
                if (phonePlayer.newFrameReady()) phonePlayer.updateTexImage()
                renderer.draw(
                    segTexId = segP.tex, segMatrix = segP.matrix, segLive = segP.hasFrame,
                    phoneTexId = phonePlayer.tex, phoneMatrix = phonePlayer.matrix, phoneLive = phonePlayer.hasFrame,
                )
                // 显式设置帧时间戳：Surface 编码默认取系统墙钟（开机纳秒），pts 会是
                // 千亿 µs 级 → 播放器时长爆炸/卡死（真机：进度条显示 3579:08 且暂停在 0:00）。
                // 必须绑定到渲染时间轴：outUs(µs) → ns。
                gl.swap(outUs * 1000)
                drainEncoder(enc, tracks)
                outUs += frameUs
                onProgress((outUs.toFloat() / outDurUs).coerceIn(0f, 1f))
                val spentMs = System.currentTimeMillis() - stepStart
                if (spentMs < frameMs) Thread.sleep(frameMs - spentMs)
            }
            Log.i(TAG, "render loop done in ${System.currentTimeMillis() - startedAt}ms")

            enc.signalEndOfInputStream()
            drainEncoder(enc, tracks, blocking = true)
            audioThread.join(5_000)
            runCatching { mx.stop() }
            val ok = out.exists() && out.length() > 0 && tracks.video >= 0
            Log.i(TAG, "compose done ok=$ok ${out.length() / 1024}KB videoSamples=${tracks.videoSamples} dropped=${tracks.droppedPts} lastPts=${tracks.lastVideoPts / 1000}ms")
            return ok
        } catch (e: Exception) {
            Log.e(TAG, "compose failed: ${e.message}", e)
            return false
        } finally {
            players.forEach { runCatching { it.close() } }
            runCatching { egl?.release() }
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            runCatching { muxer?.release() }
        }
    }

    // ───────────────────────── 解码泵 ─────────────────────────

    /**
     * 单流播放器：MediaExtractor + MediaCodec(surface) + SurfaceTexture。
     *
     * ## 为什么必须「持有输出缓冲」而不是「按 pts 限流」
     *
     * 双端素材都是稀疏变帧率（静止时不出帧）：真机手机源 59s 仅 221 帧。
     * 早期实现让泵全速 `release(render=true)`、渲染循环有帧就 `updateTexImage`，
     * 结果帧只受 SurfaceTexture 队列深度（约 3）约束 —— 每 33ms 消费 1 帧，
     * 3 帧只覆盖 100ms 却含 800ms 的内容 → **内容被加速约 8 倍**
     * （真机表现：显示到 1s 时手机画面已到 35s，整片 10s 播完）。
     *
     * 现改为：泵解出一帧后**不释放**，直到渲染同步点 `syncUs` 追上该帧 pts 才放行；
     * 因此在途帧恒为 1，帧的呈现时刻严格等于其自身时间戳，与队列深度、帧率都无关。
     */
    private class Player(private val path: String) {
        val texId = IntArray(1)
        val tex: Int get() = texId[0]
        val matrix = FloatArray(16)
        @Volatile var hasFrame = false
            private set

        private val frameCounter = AtomicInteger(0)
        private var lastSeen = 0
        private var st: SurfaceTexture? = null
        private var codec: MediaCodec? = null
        private var extractor: MediaExtractor? = null
        private var thread: Thread? = null
        private val stop = AtomicBoolean(false)

        /** 渲染循环每帧写入的流内同步点（us） */
        private val syncUs = AtomicLong(0)
        /** 泵线程最新解出的流内 pts（us） */
        private val lastPtsUs = AtomicLong(0)
        /** 首个解出帧的 pts（us）：源 pts 可能是系统墙钟，节流判据必须减去该基准 */
        private val basePtsUs = AtomicLong(-1)

        /** 在 EGL 当前线程调用：建纹理 + 启动解码泵 */
        fun initGl() {
            GLES20.glGenTextures(1, texId, 0)
            val s = SurfaceTexture(texId[0])
            s.setOnFrameAvailableListener { frameCounter.incrementAndGet() }
            st = s

            val ex = MediaExtractor()
            ex.setDataSource(path)
            var idx = -1
            for (i in 0 until ex.trackCount) {
                if (ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) { idx = i; break }
            }
            check(idx >= 0) { "no video track: $path" }
            ex.selectTrack(idx)
            extractor = ex
            val fmt = ex.getTrackFormat(idx)
            val c = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            c.configure(fmt, Surface(s), null, 0)
            c.start()
            codec = c
            thread = Thread {
                val info = MediaCodec.BufferInfo()
                var heldIdx = -1
                var heldPtsUs = 0L
                var eosSent = false
                while (!stop.get()) {
                    // ── 喂输入（非阻塞）──
                    if (!eosSent) {
                        val inIdx = c.dequeueInputBuffer(0)
                        if (inIdx >= 0) {
                            val buf = c.getInputBuffer(inIdx)!!
                            val size = ex.readSampleData(buf, 0)
                            if (size < 0) {
                                c.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                eosSent = true
                            } else {
                                c.queueInputBuffer(inIdx, 0, size, ex.sampleTime, 0)
                                ex.advance()
                            }
                        }
                    }
                    // ── 取一帧输出并「持有」（不释放 = 硬背压，在途帧恒为 1）──
                    if (heldIdx < 0) {
                        val outIdx = c.dequeueOutputBuffer(info, 0)
                        if (outIdx >= 0) {
                            val isEos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            if (info.size > 0) {
                                if (basePtsUs.get() < 0) basePtsUs.set(info.presentationTimeUs)
                                if (isEos) {
                                    c.releaseOutputBuffer(outIdx, true)
                                    lastPtsUs.set(info.presentationTimeUs)
                                    return@Thread
                                }
                                heldIdx = outIdx
                                heldPtsUs = info.presentationTimeUs
                            } else {
                                c.releaseOutputBuffer(outIdx, false)
                                if (isEos) return@Thread
                            }
                        }
                    }
                    // ── 渲染同步点追上该帧 pts 才放行；否则继续持有 ──
                    // 判据用「相对首帧的流内位置」：源 pts 可能是系统墙钟（眼镜 screenrecord、
                    // 手机 VirtualDisplay），直接与 syncUs 比会恒为「领先过多」而永不放行。
                    if (heldIdx >= 0) {
                        val base = basePtsUs.get()
                        val streamUs = if (base < 0) 0L else heldPtsUs - base
                        if (streamUs <= syncUs.get()) {
                            c.releaseOutputBuffer(heldIdx, true)
                            lastPtsUs.set(heldPtsUs)
                            heldIdx = -1
                        } else {
                            runCatching { Thread.sleep(4) }
                        }
                    }
                }
            }.apply {
                name = "demo-pump"
                isDaemon = true
                start()
            }
        }

        /** 渲染循环每帧调用：同步本流的应显示时刻（us，流内时间） */
        fun pump(streamUs: Long) { syncUs.set(streamUs) }

        /** 有新帧则消费计数（渲染线程随后 updateTexImage） */
        fun newFrameReady(): Boolean {
            val now = frameCounter.get()
            if (now == lastSeen) return false
            lastSeen = now
            return true
        }

        /** 渲染线程调用：把最新帧刷进纹理（不调用 = 定格上一帧） */
        fun updateTexImage() {
            val s = st ?: return
            s.updateTexImage()
            s.getTransformMatrix(matrix)
            hasFrame = true
        }

        fun close() {
            stop.set(true)
            runCatching { thread?.join(2_000) }
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor?.release() }
            runCatching { st?.release() }
        }
    }

    // ───────────────────────── 音频直拷 ─────────────────────────

    /**
     * 手机 AAC 轨直拷进 Muxer：pts 按帧数积分重建（起点 delayUs）。
     *
     * **为什么不能用源 pts**：自管录制的音频 pts 基准是墙钟、且中途有跳变
     * （缓冲/编码器停顿），直拷后播放器以音频为主时钟 → 在跳变处卡死、音画错位
     * （真机：14s 卡死）。AAC 每帧固定样本数，按帧序积分即得平滑 pts。
     */
    private fun copyAudio(phonePath: String, delayUs: Long, maxSrcUs: Long, muxer: MediaMuxer, tracks: Tracks) {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(phonePath)
            var idx = -1
            for (i in 0 until ex.trackCount) {
                if (ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { idx = i; break }
            }
            if (idx < 0) { Log.i(TAG, "no audio track in phone video"); return }
            ex.selectTrack(idx)
            val fmt = ex.getTrackFormat(idx)
            val sampleRate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val frameDurUs = 1_000_000L * 1024 / sampleRate // AAC 每帧 1024 样本
            val aid: Int = synchronized(tracks) {
                if (tracks.started) {
                    Log.w(TAG, "muxer already started, audio track skipped")
                    return
                }
                tracks.audio = muxer.addTrack(fmt)
                tracks.audio
            }
            var waited = 0L
            while (!synchronized(tracks) { tracks.started } && waited < 8_000) {
                Thread.sleep(20); waited += 20
            }
            val info = MediaCodec.BufferInfo()
            val buf = ByteBuffer.allocateDirect(1 shl 16).order(ByteOrder.nativeOrder())
            var frameIdx = 0L
            while (true) {
                val size = ex.readSampleData(buf, 0)
                if (size < 0) break
                if (frameIdx * frameDurUs > maxSrcUs) break
                info.set(0, size, delayUs + frameIdx * frameDurUs, ex.sampleFlags)
                synchronized(tracks) {
                    if (tracks.started) muxer.writeSampleData(aid, buf, info)
                }
                frameIdx++
                ex.advance()
            }
            Log.i(TAG, "audio copied: frames=$frameIdx dur=${frameIdx * frameDurUs / 1000}ms rate=$sampleRate")
        } catch (e: Exception) {
            Log.e(TAG, "audio copy failed: ${e.message}")
        } finally {
            runCatching { ex.release() }
        }
    }

    // ───────────────────────── 编码输出 ─────────────────────────

    private class Tracks(val muxer: MediaMuxer) {
        var video = -1
        var audio = -1
        var started = false
        var videoSamples = 0
        var lastVideoPts = -1L
        var droppedPts = 0
    }

    private fun drainEncoder(enc: MediaCodec, tracks: Tracks, blocking: Boolean = false) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val idx = enc.dequeueOutputBuffer(info, if (blocking) 10_000L else 0)
            when {
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> synchronized(tracks) {
                    if (tracks.video < 0) tracks.video = tracks.muxer.addTrack(enc.outputFormat)
                }
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                idx >= 0 -> {
                    val buf = enc.getOutputBuffer(idx)!!
                    synchronized(tracks) {
                        if (!tracks.started && tracks.video >= 0) {
                            tracks.muxer.start()
                            tracks.started = true
                        }
                        if (tracks.started && info.size > 0) {
                            // 乱序护栏：正常（Baseline 无 B 帧）pts 严格单调；出现回跳
                            // 说明该 ROM 编码器无视 profile —— 丢弃回跳样本保护 mp4 时间轴
                            if (info.presentationTimeUs <= tracks.lastVideoPts) {
                                tracks.droppedPts++
                            } else {
                                tracks.muxer.writeSampleData(tracks.video, buf, info)
                                tracks.lastVideoPts = info.presentationTimeUs
                                tracks.videoSamples++
                            }
                        }
                    }
                    enc.releaseOutputBuffer(idx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
                else -> return
            }
        }
    }

    // ───────────────────────── GL 基建 ─────────────────────────

    /** EGL 上下文（绑定编码器输入 Surface；本对象在 compose 线程创建并使用） */
    private class EglCore(encSurface: Surface) {
        private val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        private val context: EGLContext
        private val surface: EGLSurface

        init {
            val ver = IntArray(2)
            check(EGL14.eglInitialize(display, ver, 0, ver, 1)) { "eglInitialize failed" }
            val cfgAttribs = intArrayOf(
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val num = IntArray(1)
            EGL14.eglChooseConfig(display, cfgAttribs, 0, configs, 0, 1, num, 0)
            check(num[0] > 0) { "no EGL config" }
            val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
            context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
            surface = EGL14.eglCreateWindowSurface(display, configs[0], encSurface, intArrayOf(EGL14.EGL_NONE), 0)
            check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "eglMakeCurrent failed" }
        }

        /** 交换缓冲；presentationTimeNs 显式指定（编码器 pts 源，详见渲染循环注释） */
        fun swap(presentationTimeNs: Long): Boolean {
            EGLExt.eglPresentationTimeANDROID(display, surface, presentationTimeNs)
            return EGL14.eglSwapBuffers(display, surface)
        }

        fun release() {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            runCatching { EGL14.eglDestroySurface(display, surface) }
            runCatching { EGL14.eglDestroyContext(display, context) }
            runCatching { EGL14.eglTerminate(display) }
        }
    }

    /** 两区域渲染：左眼镜（UV 按 GLASSES_ROTATION_DEG 旋转）、右手机。 */
    private class GlRenderer(private val canvasW: Int, gW: Int) {
        private val program: Int = buildProgram()
        private val aPos: Int = GLES20.glGetAttribLocation(program, "aPos")
        private val aUV: Int = GLES20.glGetAttribLocation(program, "aUV")
        private val uM: Int = GLES20.glGetUniformLocation(program, "uM")
        private val uTex: Int = GLES20.glGetUniformLocation(program, "uTex")
        private val uRot: Int = GLES20.glGetUniformLocation(program, "uRot")

        private val gQuad: FloatBuffer
        private val pQuad: FloatBuffer

        init {
            // NDC 分界：眼镜占左侧 gW 像素，手机占右侧
            val gx = (gW.toFloat() / canvasW) * 2f - 1f
            gQuad = quad(-1f, gx)
            pQuad = quad(gx, 1f)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
        }

        /** 两三角形条带：pos(x,y)+uv(u,v)，uv 交给 shader 与 ST 矩阵组合 */
        private fun quad(x0: Float, x1: Float): FloatBuffer {
            val v = floatArrayOf(
                x0, -1f, 0f, 0f,
                x1, -1f, 1f, 0f,
                x0, 1f, 0f, 1f,
                x1, 1f, 1f, 1f,
            )
            return ByteBuffer.allocateDirect(v.size * 4).order(ByteOrder.nativeOrder())
                .asFloatBuffer().apply { put(v); position(0) }
        }

        fun draw(
            segTexId: Int, segMatrix: FloatArray, segLive: Boolean,
            phoneTexId: Int, phoneMatrix: FloatArray, phoneLive: Boolean,
        ) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glUseProgram(program)
            GLES20.glUniform1i(uTex, 0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            if (segLive) drawOne(segTexId, segMatrix, GLASSES_ROTATION_DEG, gQuad)
            if (phoneLive) drawOne(phoneTexId, phoneMatrix, 0, pQuad)
        }

        private fun drawOne(texId: Int, m: FloatArray, rot: Int, quad: FloatBuffer) {
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
            GLES20.glUniformMatrix4fv(uM, 1, false, m, 0)
            GLES20.glUniform1i(uRot, rot)
            quad.position(0)
            GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, quad)
            quad.position(2)
            GLES20.glVertexAttribPointer(aUV, 2, GLES20.GL_FLOAT, false, 16, quad)
            GLES20.glEnableVertexAttribArray(aPos)
            GLES20.glEnableVertexAttribArray(aUV)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
        }

        private fun buildProgram(): Int {
            val vs = """
                attribute vec2 aPos;
                attribute vec2 aUV;
                uniform mat4 uM;
                uniform int uRot;
                varying vec2 vUV;
                void main() {
                    gl_Position = vec4(aPos, 0.0, 1.0);
                    vec2 t = (uM * vec4(aUV, 0.0, 1.0)).xy;
                    // 眼镜源自带方向处理（真机实测 480x640 已竖直，uRot=0 即直通）
                    if (uRot == 90) t = vec2(t.y, 1.0 - t.x);
                    else if (uRot == 180) t = vec2(1.0 - t.x, 1.0 - t.y);
                    else if (uRot == 270) t = vec2(1.0 - t.y, t.x);
                    vUV = t;
                }
            """.trimIndent()
            val fs = """
                #extension GL_OES_EGL_image_external : require
                precision mediump float;
                varying vec2 vUV;
                uniform samplerExternalOES uTex;
                void main() { gl_FragColor = texture2D(uTex, vUV); }
            """.trimIndent()
            return linkProgram(vs, fs)
        }

        private fun linkProgram(vs: String, fs: String): Int {
            fun compile(type: Int, src: String): Int {
                val sh = GLES20.glCreateShader(type)
                GLES20.glShaderSource(sh, src)
                GLES20.glCompileShader(sh)
                return sh
            }
            val p = GLES20.glCreateProgram()
            GLES20.glAttachShader(p, compile(GLES20.GL_VERTEX_SHADER, vs))
            GLES20.glAttachShader(p, compile(GLES20.GL_FRAGMENT_SHADER, fs))
            GLES20.glLinkProgram(p)
            val status = IntArray(1)
            GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, status, 0)
            check(status[0] == GLES20.GL_TRUE) { "program link failed: ${GLES20.glGetProgramInfoLog(p)}" }
            return p
        }
    }
}
