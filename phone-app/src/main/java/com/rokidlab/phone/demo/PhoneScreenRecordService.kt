package com.rokidlab.phone.demo

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.rokidlab.phone.R
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

/**
 * 双端录像 —— 手机端录屏（MediaCodec + MediaMuxer 自管）。
 *
 * ## 为什么不用 MediaRecorder（真机教训）
 *
 * MediaRecorder 的 MPEG4Writer 在画面接近静止时（演示视频场景：手机搁桌上操作眼镜），
 * finalize 阶段等不到 chunk 数据直接抛 `stop failed`，moov 写一半残缺 —— 968KB mdat 全废。
 * MediaCodec 自管后 EOS 由我们自己 signal：视频 signalEndOfInputStream + 音频
 * queueInputBuffer(EOS)，两轨全部排干后才 muxer.stop()，moov 必然完整。
 *
 * ## FGS 时序铁律（与 mirror/ScreenCaptureService 同源）
 *
 * 1. mediaProjection 类型 FGS 必须先 startForeground 再 getMediaProjection（Android 14）；
 * 2. targetSdk 34 起 createVirtualDisplay 前必须注册 MediaProjection.Callback；
 * 3. 一个 projection 只允许 createVirtualDisplay 一次。
 *
 * ## 音频
 *
 * AudioRecord(MIC) → AAC 编码为第二轨。采集与编码在同一线程，stop 时 queueInputBuffer
 * 带 EOS 标志，drain 到 EOS 为止。
 */
class PhoneScreenRecordService : Service() {
    companion object {
        private const val TAG = "PhoneScreenRecord"
        private const val NOTIFICATION_ID = 1003
        private const val CHANNEL_ID = "DemoRecord"
        private const val ACTION_STOP = "com.rokidlab.phone.demo.STOP"

        /** 视频档位：短边 720 级别，宽高强制偶数（编码器要求） */
        private const val VIDEO_MAX_SHORT_EDGE = 720
        private const val VIDEO_BIT_RATE = 8_000_000
        private const val VIDEO_FPS = 30

        /**
         * 静止画面补帧间隔（us）：屏录是「画面动了才有帧」，手机端录 App 界面时整段几乎静止，
         * 真机实测 5s 只产出 2 帧（视频轨跨度 30ms）—— 时长不可测、成片手机侧整段定格。
         * 让编码器每 100ms 重复上一帧（≥10fps），既补齐时间轴也保证时长可测。
         */
        private const val VIDEO_REPEAT_US = 100_000

        private const val SAMPLE_RATE = 44_100
        private const val CHANNELS = 1
        private const val AUDIO_BIT_RATE = 96_000

        private val lock = Any()
        private var startedLatch: CountDownLatch? = null
        private var startedAtMs = 0L
        private var finishedLatch: CountDownLatch? = null
        private var finishedPath: String? = null

        private var pendingResultCode: Int = Activity.RESULT_CANCELED
        private var pendingData: Intent? = null
        private var pendingTargetMs: Long = 0L

        private val stopRequested = AtomicBoolean(false)

        /** 发起一次录制：缓存授权数据并拉起前台服务。真实起录时刻经 awaitStarted 回报。 */
        fun begin(context: Context, resultCode: Int, data: Intent, targetMs: Long) {
            pendingResultCode = resultCode
            pendingData = Intent(data)
            pendingTargetMs = targetMs
            stopRequested.set(false)
            synchronized(lock) {
                startedLatch = CountDownLatch(1)
                startedAtMs = 0L
                finishedLatch = CountDownLatch(1)
                finishedPath = null
            }
            val app = context.applicationContext ?: context
            val intent = Intent(app, PhoneScreenRecordService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                app.startForegroundService(intent)
            } else {
                app.startService(intent)
            }
        }

        /** 等服务回报真实起录时刻（epoch ms）；超时/失败返回 -1 */
        fun awaitStarted(timeoutMs: Long): Long {
            val l = synchronized(lock) { startedLatch } ?: return -1L
            val ok = runCatching { l.await(timeoutMs, TimeUnit.MILLISECONDS) }.getOrDefault(false)
            return if (ok) synchronized(lock) { startedAtMs } else -1L
        }

        /** 等录制完成并拿到输出路径；超时返回 null */
        fun awaitFinished(timeoutMs: Long): String? {
            val l = synchronized(lock) { finishedLatch } ?: return null
            val ok = runCatching { l.await(timeoutMs, TimeUnit.MILLISECONDS) }.getOrDefault(false)
            return if (ok) synchronized(lock) { finishedPath } else null
        }

        fun requestStop(context: Context) {
            val app = context.applicationContext ?: context
            runCatching {
                app.startService(Intent(app, PhoneScreenRecordService::class.java).setAction(ACTION_STOP))
            }.onFailure { Log.w(TAG, "requestStop ignored: ${it.message}") }
            stopRequested.set(true)
        }
    }

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // ── 轨道登记与 muxer 启动状态（视频/音频两个线程共享）──
    private val trackLock = Any()
    private var vTrackId = -1
    private var aTrackId = -1
    private var muxStarted = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.demo_record_title), NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopRequested.set(true)
            return START_NOT_STICKY
        }
        // 时序要求：先成为 mediaProjection 类型的前台服务，才能 getMediaProjection()
        runCatching { startForeground(NOTIFICATION_ID, createNotification()) }
            .onFailure { Log.e(TAG, "startForeground failed: ${it.message}", it) }

        val resultCode = pendingResultCode
        val data = pendingData
        val targetMs = pendingTargetMs
        pendingData = null

        Thread {
            val path = runCatching { record(resultCode, data, targetMs) }
                .onFailure { Log.e(TAG, "record failed: ${it.message}", it) }
                .getOrNull()
            synchronized(lock) {
                finishedPath = path
                finishedLatch?.countDown()
            }
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            stopSelf()
        }.apply {
            name = "demo-record"
            isDaemon = true
            start()
        }
        return START_NOT_STICKY
    }

    /** 完整录制流程：授权 → 编码 → 到点/请求停 → EOS 排干 → muxer 收尾。返回输出路径或 null。 */
    private fun record(resultCode: Int, data: Intent?, targetMs: Long): String? {
        if (resultCode != Activity.RESULT_OK || data == null) {
            Log.i(TAG, "MediaProjection consent denied")
            synchronized(lock) { startedLatch?.countDown() }
            return null
        }
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val proj = runCatching { manager.getMediaProjection(resultCode, data) }.getOrNull() ?: run {
            synchronized(lock) { startedLatch?.countDown() }
            return null
        }
        projection = proj
        // targetSdk 34：createVirtualDisplay 前必须注册 Callback
        runCatching { proj.registerCallback(object : MediaProjection.Callback() {}, mainHandler) }

        val dir = (getExternalFilesDir("demo") ?: filesDir).apply { mkdirs() }
        val outFile = File(dir, "phone_raw.mp4")

        val target = fitScreen()
        val vFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, target.width, target.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_BIT_RATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, VIDEO_FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            setInteger(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, VIDEO_REPEAT_US)
        }

        var vEncoder: MediaCodec? = null
        var aEncoder: MediaCodec? = null
        var audioRecord: AudioRecord? = null
        var inputSurface: android.view.Surface? = null
        var muxer: MediaMuxer? = null
        var audioThread: Thread? = null
        val eosCount = AtomicInteger(0)
        try {
            vEncoder = runCatching {
                MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                    configure(vFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                }
            }.getOrElse {
                // 个别 ROM 的编码器不支持 repeat-previous-frame-after：去掉该键重配一次
                Log.w(TAG, "configure with repeat-previous-frame failed: ${it.message}, retry without")
                vFormat.removeKey(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER)
                MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                    configure(vFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                }
            }
            inputSurface = vEncoder.createInputSurface()
            vEncoder.start()

            display = proj.createVirtualDisplay(
                "DemoRecord", target.width, target.height, target.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, inputSurface, null, null,
            )

            // ── 音频源 + AAC 编码器 ──
            val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            audioRecord = AudioRecord(
                android.media.MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf * 2, SAMPLE_RATE),
            )
            aEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                configure(
                    MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, CHANNELS).apply {
                        setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BIT_RATE)
                        setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                        setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 65536)
                    },
                    null, null, MediaCodec.CONFIGURE_FLAG_ENCODE,
                )
                start()
            }

            muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            // ── 起录回报：编码器/虚拟屏/音频链路全部就绪（首帧毫秒级即到，不必等）──
            synchronized(lock) {
                startedAtMs = System.currentTimeMillis()
                startedLatch?.countDown()
            }

            // ── 音频采集/编码线程 ──
            val aEnc = aEncoder!!
            val aRec = audioRecord!!
            audioThread = Thread {
                try {
                    val buf = ByteArray(4096)
                    var totalSamples = 0L
                    val rate = SAMPLE_RATE
                    // pts 按样本数积分（从 0 平滑递增）：不能用墙钟 —— 合成器按音频 pts
                    // 对齐音画，墙钟基准（开机 µs）会让输出时间轴错乱、播放中途卡死
                    aRec.startRecording()
                    while (!stopRequested.get()) {
                        val n = aRec.read(buf, 0, buf.size)
                        if (n <= 0) continue
                        val pts = totalSamples * 1_000_000 / rate
                        totalSamples += n / 2 // 16bit 单声道：每样本 2 字节
                        val idx = aEnc.dequeueInputBuffer(10_000)
                        if (idx >= 0) {
                            val ib = aEnc.getInputBuffer(idx)!!
                            ib.clear()
                            ib.put(buf, 0, n)
                            aEnc.queueInputBuffer(idx, 0, n, pts, 0)
                        }
                        drainAudio(aEnc, muxer, eosCount)
                    }
                    // EOS：发一帧空 EOS 输入，排干输出
                    var eosSent = false
                    while (!eosSent) {
                        val idx = aEnc.dequeueInputBuffer(10_000)
                        if (idx >= 0) {
                            aEnc.queueInputBuffer(idx, 0, 0, totalSamples * 1_000_000 / rate, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            eosSent = true
                        }
                    }
                    while (eosCount.get() == 0) drainAudio(aEnc, muxer, eosCount)
                } catch (t: Throwable) {
                    Log.e(TAG, "audio thread: ${t.message}", t)
                    eosCount.incrementAndGet() // 兜底放行，避免视频线程死等
                }
            }.apply {
                name = "demo-record-audio"
                isDaemon = true
                start()
            }

            // ── 视频主循环：到点 / 停止请求 → signalEndOfInputStream → 排干 EOS ──
            val deadline = System.currentTimeMillis() + targetMs
            val info = MediaCodec.BufferInfo()
            while (System.currentTimeMillis() < deadline && !stopRequested.get()) {
                drainVideo(vEncoder, muxer, info)
                maybeStartMuxer(vEncoder, aEncoder, muxer)
                Thread.sleep(10)
            }

            // ── 停止期：EOS + 完整排干（moov 完整性的关键；超时兜底防死循环）──
            runCatching { vEncoder.signalEndOfInputStream() }
            val eosDeadline = System.currentTimeMillis() + 3_000
            while (!vEosSeen && System.currentTimeMillis() < eosDeadline) {
                drainVideo(vEncoder, muxer, info)
            }

            // 等音频 EOS（最长 5s）：两轨齐了才能 muxer.stop
            val t0 = System.currentTimeMillis()
            while (eosCount.get() < 1 && System.currentTimeMillis() - t0 < 5_000) Thread.sleep(50)
            runCatching { aRec.stop() }

            if (muxStarted) {
                runCatching { muxer!!.stop() }.onFailure { Log.e(TAG, "muxer stop: ${it.message}") }
            }
            val ok = outFile.exists() && outFile.length() > 0 && muxStarted
            Log.i(TAG, "phone recording finished ${outFile.length()}B muxStarted=$muxStarted vEos=$vEosSeen aEos=${eosCount.get()}")
            if (ok) return outFile.absolutePath
            synchronized(lock) { startedAtMs = -1L }
            return null
        } finally {
            runCatching { audioThread?.join(1_000) }
            runCatching { vEncoder?.stop() }
            runCatching { vEncoder?.release() }
            runCatching { aEncoder?.stop() }
            runCatching { aEncoder?.release() }
            runCatching { audioRecord?.release() }
            runCatching { inputSurface?.release() }
            runCatching { display?.release() }
            display = null
            runCatching { muxer?.release() }
            runCatching { projection?.stop() }
            projection = null
        }
    }

    @Volatile
    private var vEosSeen = false

    /** 首个视频样本 pts（µs）；-1 = 未记录。用于把墙钟基准的 pts 归零，见 [drainVideo] */
    private var firstVideoPtsUs = -1L

    /** 视频编码输出排干；track format 到位则登记轨道并尝试启动 muxer */
    private fun drainVideo(enc: MediaCodec, muxer: MediaMuxer?, info: MediaCodec.BufferInfo) {
        while (true) {
            val idx = enc.dequeueOutputBuffer(info, 10_000)
            when {
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> maybeStartMuxer(enc, null, muxer)
                idx >= 0 -> {
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    val t = synchronized(trackLock) { vTrackId }
                    if (info.size > 0 && muxer != null && t >= 0 && muxStarted) {
                        // pts 归零：视频帧由 VirtualDisplay 直推编码器 Surface，时间戳是系统
                        // 墙钟（无法设置 presentation time）。不归零则 mvhd duration 写饱和值
                        // 0x7FFFFFFF（≈59.6 小时），下游合成器按此算输出长度 → 渲染循环跑不完。
                        if (firstVideoPtsUs < 0) firstVideoPtsUs = info.presentationTimeUs
                        val rebased = info.presentationTimeUs - firstVideoPtsUs
                        if (rebased >= 0) {
                            info.presentationTimeUs = rebased
                            try {
                                muxer.writeSampleData(t, enc.getOutputBuffer(idx)!!, info)
                            } catch (e: Exception) {
                                Log.w(TAG, "writeSampleData v: ${e.message}")
                            }
                        }
                    }
                    enc.releaseOutputBuffer(idx, false)
                    if (eos) {
                        vEosSeen = true
                        return
                    }
                }
            }
        }
    }

    private fun drainAudio(enc: MediaCodec, muxer: MediaMuxer?, eosCount: AtomicInteger) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val idx = enc.dequeueOutputBuffer(info, 10_000)
            when {
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    synchronized(trackLock) {
                        if (aTrackId < 0 && muxer != null) {
                            aTrackId = muxer.addTrack(enc.outputFormat)
                        }
                    }
                    maybeStartMuxer(null, enc, muxer)
                }
                idx >= 0 -> {
                    val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    val t = synchronized(trackLock) { aTrackId }
                    if (info.size > 0 && muxer != null && t >= 0 && muxStarted) {
                        try {
                            muxer.writeSampleData(t, enc.getOutputBuffer(idx)!!, info)
                        } catch (e: Exception) {
                            Log.w(TAG, "writeSampleData a: ${e.message}")
                        }
                    }
                    enc.releaseOutputBuffer(idx, false)
                    if (eos) {
                        eosCount.incrementAndGet()
                        return
                    }
                }
            }
        }
    }

    /** 两轨 format 都登记后启动 muxer；null 编码器表示该侧由其他线程登记 */
    private fun maybeStartMuxer(vEnc: MediaCodec?, aEnc: MediaCodec?, muxer: MediaMuxer?) {
        if (muxer == null) return
        synchronized(trackLock) {
            if (muxStarted) return
            if (vTrackId < 0 && vEnc != null) {
                runCatching { vTrackId = muxer.addTrack(vEnc.outputFormat) }
                    .onFailure { Log.w(TAG, "addTrack v: ${it.message}") }
            }
            if (aTrackId < 0 && aEnc != null) {
                runCatching { aTrackId = muxer.addTrack(aEnc.outputFormat) }
                    .onFailure { Log.w(TAG, "addTrack a: ${it.message}") }
            }
            if (vTrackId >= 0 && aTrackId >= 0) {
                runCatching { muxer.start() }
                    .onSuccess { muxStarted = true }
                    .onFailure { Log.w(TAG, "muxer start: ${it.message}") }
            }
        }
    }

    /** 目标尺寸：短边 ≤720 且宽高取偶（编码器要求） */
    private fun fitScreen(): Target {
        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            metrics.widthPixels = bounds.width()
            metrics.heightPixels = bounds.height()
            metrics.densityDpi = resources.configuration.densityDpi
        } else {
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
        }
        val w = metrics.widthPixels.coerceAtLeast(1)
        val h = metrics.heightPixels.coerceAtLeast(1)
        val scale = minOf(1f, VIDEO_MAX_SHORT_EDGE.toFloat() / minOf(w, h).toFloat())
        val tw = (w * scale).roundToInt().coerceAtLeast(2).let { it - (it % 2) }
        val th = (h * scale).roundToInt().coerceAtLeast(2).let { it - (it % 2) }
        return Target(tw, th, if (metrics.densityDpi > 0) metrics.densityDpi else DisplayMetrics.DENSITY_DEFAULT)
    }

    private data class Target(val width: Int, val height: Int, val densityDpi: Int)

    private fun createNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.demo_record_title))
            .setContentText(getString(R.string.demo_notification_text))
            .setOngoing(true)
            .build()
}
