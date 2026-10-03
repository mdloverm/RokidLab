package com.rokidlab.phone.store

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteOrder

/**
 * 云萌 TTS 手机端播报：合成 mp3 → 解码 → 重采样 16k/mono/16bit → 手机 AudioTrack 播放。
 *
 * 只播手机（不做眼镜 PCM 流）：手机外放眼镜自然听得到，双声源反而不同步（实测有重音）。
 * 默认音色（"" ）不走这里——那条链路是眼镜端离线 TTS（tts_play），见 ChatScreen / AiConversationService。
 *
 * 线程语义：[broadcast] 阻塞到播放完毕，须在后台线程调用。
 * 新的 broadcast 调用或 [cancel] 会作废上一轮（代际号机制），旧流自动停掉。
 */
object VoiceBroadcast {
    private const val TAG = "VoiceBroadcast"

    /** 播放采样率：16k / mono / 16bit（与眼镜端能力对齐，保证音色管理页试听与播报一致） */
    const val TARGET_SR = 16000

    /** 本地写入块大小：128ms @16k/16bit/mono */
    private const val CHUNK_BYTES = 4096

    @Volatile
    private var generation = 0L

    /** 打断当前播报（若有） */
    fun cancel() {
        generation++
    }

    /**
     * 手机端播报一段文本（阻塞到播完，后台线程调用）。
     *
     * @return 成功 = 播放时长 ms（被打断返回 0）；失败 = 异常
     */
    fun broadcast(
        text: String,
        voice: String,
        rate: Int = 5,
    ): Result<Long> {
        val gen = ++generation
        return runCatching {
            val pcm = prepareInternal(text, voice, rate, gen).getOrThrow()
            if (pcm.size < 2) error("decoded pcm empty")
            playPcmInternal(pcm, gen)
        }.onFailure {
            Log.w(TAG, "broadcast failed: ${it.javaClass.simpleName}: ${it.message}")
        }
    }

    /**
     * 预合成：文本 → PCM 16k/mono/16bit（阻塞 1-2s，后台线程调用）。
     * 对话链路用它先把语音备好，文字显示与出声就能同步发生。
     *
     * @return 解码后的 PCM（可能为空段）
     */
    fun prepare(text: String, voice: String, rate: Int = 5): Result<ByteArray> {
        val gen = ++generation
        return prepareInternal(text, voice, rate, gen)
    }

    /**
     * 播放预合成的 PCM（阻塞到播完，后台线程调用）。
     * 与 [prepare] 配对：prepare 之后只要没有新的 prepare/broadcast（代际号被打断）即可播。
     */
    fun playPcm(pcm: ByteArray): Result<Long> {
        val gen = generation
        return runCatching { playPcmInternal(pcm, gen) }
    }

    /** 合成 → 下载 → 解码（[broadcast] 与 [prepare] 共用） */
    private fun prepareInternal(text: String, voice: String, rate: Int, gen: Long): Result<ByteArray> =
        runCatching {
            // 1. 合成 + 下载（mp3 直链 5 分钟有效，拿到即用不缓存）
            val mp3Url = VoiceCatalog.synthesize(voice, rate, text).getOrThrow()
            val mp3 = download(mp3Url).getOrThrow()
            if (gen != generation) return@runCatching ByteArray(0)

            // 2. 解码 + 重采样
            val pcm = decodeToMono16k(mp3).getOrThrow()
            if (gen != generation) return@runCatching ByteArray(0)
            pcm
        }

    /** AudioTrack 流式播放 + 排空收尾（[broadcast] 与 [playPcm] 共用） */
    private fun playPcmInternal(pcm: ByteArray, gen: Long): Long {
        val track = buildTrack()
        track.play()
        var offset = 0
        while (offset < pcm.size) {
            if (gen != generation) {
                runCatching { track.stop(); track.release() }
                Log.i(TAG, "broadcast aborted (gen=$gen)")
                return 0L
            }
            val len = minOf(CHUNK_BYTES, pcm.size - offset)
            track.write(pcm, offset, len, AudioTrack.WRITE_BLOCKING)
            offset += len
        }

        // 排空收尾：write 返回 ≠ 播完 —— 缓冲里还压着最多 ~1s 数据，
        // 直接 stop() 会把尾部截掉（实测「没播完就停」的根因）。
        // 轮询播放头直到读满总帧数（3s 兜底，防异常挂死）。
        val totalFrames = (pcm.size / 2).toLong()
        val deadline = System.currentTimeMillis() + 3000
        while (System.currentTimeMillis() < deadline) {
            val head = track.playbackHeadPosition.toLong() and 0xFFFFFFFFL
            if (head >= totalFrames) break
            Thread.sleep(30)
        }
        val elapsedMs = pcm.size.toLong() * 1000 / (TARGET_SR * 2)
        runCatching { track.stop(); track.release() }
        Log.i(TAG, "broadcast done: ${elapsedMs}ms (${pcm.size / 2} samples)")
        return elapsedMs
    }

    /** 手机端本地 AudioTrack（16k / mono / 16bit，流模式） */
    private fun buildTrack(): AudioTrack {
        val minBuf = AudioTrack.getMinBufferSize(
            TARGET_SR, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(TARGET_SR)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(minBuf, TARGET_SR * 2)) // ≥1s 缓冲，降低下链抖动
            .build()
    }

    /** 下载合成结果（小文件，整块读入内存） */
    private fun download(url: String): Result<ByteArray> = runCatching {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 6000
        conn.readTimeout = 15000
        conn.requestMethod = "GET"
        conn.connect()
        if (conn.responseCode !in 200..299) error("download http=${conn.responseCode}")
        conn.inputStream.use { input -> input.readBytes() }.also {
            Log.i(TAG, "mp3 downloaded: ${it.size}B")
        }
    }

    /**
     * mp3 → PCM 16k/mono/16bit（MediaCodec 硬解 → 下混单声道 → 线性插值重采样）。
     * 输出为小端字节序，与 AudioTrack/PcmStreamPlayer 对齐。
     */
    private fun decodeToMono16k(mp3: ByteArray): Result<ByteArray> = runCatching {
        val extractor = MediaExtractor()
        val ds = object : MediaDataSource() {
            override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
                if (position >= mp3.size) return -1
                val n = minOf(size.toLong(), mp3.size - position).toInt()
                System.arraycopy(mp3, position.toInt(), buffer, offset, n)
                return n
            }
            override fun getSize(): Long = mp3.size.toLong()
            override fun close() {}
        }
        extractor.setDataSource(ds)
        var trackIdx = -1
        var srcFormat: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                trackIdx = i
                srcFormat = f
                break
            }
        }
        if (trackIdx < 0) error("no audio track in mp3")
        extractor.selectTrack(trackIdx)
        var format = srcFormat!!
        var srcSr = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
            format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2
        val mime = format.getString(MediaFormat.KEY_MIME)!!

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()

        // 采集解码输出（交错 16bit，原始声道数）
        val raw = java.io.ByteArrayOutputStream()
        val info = MediaCodec.BufferInfo()
        var sawInputEos = false
        var sawOutputEos = false
        while (!sawOutputEos) {
            if (!sawInputEos) {
                val inIdx = codec.dequeueInputBuffer(10_000)
                if (inIdx >= 0) {
                    val ib = codec.getInputBuffer(inIdx)!!
                    val n = extractor.readSampleData(ib, 0)
                    if (n < 0) {
                        codec.queueInputBuffer(inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        sawInputEos = true
                    } else {
                        codec.queueInputBuffer(inIdx, 0, n, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val outIdx = codec.dequeueOutputBuffer(info, 10_000)
            if (outIdx >= 0) {
                val ob = codec.getOutputBuffer(outIdx)!!
                if (info.size > 0) {
                    // ⚠️ 不能用 ob.array()：Codec2 输出缓冲是 direct buffer（native 内存），
                    // array() 抛异常（真机复现：c2.android.mp3.decoder 必炸）。走 position/limit 读。
                    ob.position(info.offset)
                    ob.limit(info.offset + info.size)
                    val chunk = ByteArray(info.size)
                    ob.get(chunk)
                    raw.write(chunk, 0, chunk.size)
                }
                codec.releaseOutputBuffer(outIdx, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) sawOutputEos = true
            } else if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                format = codec.outputFormat
                srcSr = format.getInteger(MediaFormat.KEY_SAMPLE_RATE) // 解码器可能改写实际采样率
                if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT))
                    channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            }
        }
        codec.stop()
        codec.release()
        extractor.release()
        Log.i(TAG, "decoded: sr=$srcSr ch=$channels bytes=${raw.size()}")

        // 下混单声道 + 重采样 16k（线性插值；输出小端 16bit）
        val mono = downmixToShort(raw.toByteArray(), channels)
        val dst = resample(mono, srcSr, TARGET_SR)
        shortsToBytesLE(dst)
    }

    /** 16bit 交错 PCM → 下混单声道 ShortArray（小端读；声道数由解码输出格式决定） */
    private fun downmixToShort(raw: ByteArray, channels: Int): ShortArray {
        val bb = java.nio.ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        if (channels <= 1) {
            val mono = ShortArray(raw.size / 2)
            for (i in mono.indices) mono[i] = bb.short
            return mono
        }
        val frames = raw.size / (2 * channels)
        val mono = ShortArray(frames)
        for (i in 0 until frames) {
            var sum = 0
            for (c in 0 until channels) sum += bb.short
            mono[i] = (sum / channels).toShort()
        }
        return mono
    }

    /** 线性插值重采样 */
    private fun resample(src: ShortArray, fromSr: Int, toSr: Int): ShortArray {
        if (fromSr == toSr || src.isEmpty()) return src
        val dstLen = (src.size.toLong() * toSr / fromSr).toInt()
        val dst = ShortArray(dstLen)
        for (i in 0 until dstLen) {
            val pos = i.toDouble() * (fromSr - 1) / (toSr - 1).coerceAtLeast(1)
            val i0 = pos.toInt()
            val frac = pos - i0
            val s0 = src[i0].toDouble()
            val s1 = src[(i0 + 1).coerceAtMost(src.size - 1)].toDouble()
            dst[i] = (s0 + (s1 - s0) * frac).toInt().toShort()
        }
        return dst
    }

    /** ShortArray → 16bit 小端字节流 */
    private fun shortsToBytesLE(src: ShortArray): ByteArray {
        val out = ByteArray(src.size * 2)
        for (i in src.indices) {
            out[i * 2] = (src[i].toInt() and 0xFF).toByte()
            out[i * 2 + 1] = ((src[i].toInt() shr 8) and 0xFF).toByte()
        }
        return out
    }
}
