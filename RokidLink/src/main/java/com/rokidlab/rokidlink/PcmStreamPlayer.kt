package com.rokidlab.rokidlink

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import java.util.concurrent.Executors

/**
 * 离线 TTS PCM 流式播放器（B 方案 PoC）：手机端合成 PCM 经 CXR 自定义指令下发，
 * 眼镜端 AudioTrack 直接播放（16k / mono / 16bit）。
 *
 * 生命周期：ctrl 通道 "start"（携带采样率）→ PCM 通道喂流 → "end"（排空收尾）/
 * "stop"（打断立即静音）。
 *
 * 写入集中在单线程 executor：CXR 回调线程只入队（非阻塞），AudioTrack 缓冲写满时
 * WRITE_BLOCKING 的阻塞发生在自有线程，不会拖死 cxr-service 分发。
 * epoch 代际号作废 executor 里排队的旧任务（与 TtsPlaybackHelper 同款模式）。
 */
internal object PcmStreamPlayer {
    private const val TAG = "PcmStreamPlayer"

    /** ctrl 未带合法采样率时的兜底 */
    private const val FALLBACK_SAMPLE_RATE = 16000

    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "tts-pcm") }

    @Volatile
    private var track: AudioTrack? = null

    /** 播放代际号：start/stop 递增，作废 executor 中排队的旧流任务 */
    @Volatile
    private var epoch = 0L

    /** 开始新流（ctrl "start"）。自动打断上一个未结束的流。 */
    fun start(sampleRate: Int) {
        val sr = if (sampleRate in 8000..48000) sampleRate else FALLBACK_SAMPLE_RATE
        val e = ++epoch
        executor.execute {
            releaseTrack()
            val minBuf = AudioTrack.getMinBufferSize(sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minBuf <= 0) {
                Log.e(TAG, "getMinBufferSize($sr) -> $minBuf, cannot play")
                return@execute
            }
            val t = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sr)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(minBuf * 4)
                .setSessionId(AudioManager.AUDIO_SESSION_ID_GENERATE)
                .build()
            val playOk = runCatching { t.play() }
                .onFailure { Log.e(TAG, "AudioTrack.play failed", it) }
                .isSuccess
            if (!playOk) {
                runCatching { t.release() }
                return@execute
            }
            track = t
            Log.i(TAG, "stream started: sr=$sr, buf=${minBuf * 4}B (epoch=$e)")
        }
    }

    /** 喂一段 PCM（16bit LE）。流未就绪 / 已被打断则丢弃。 */
    fun feed(pcm: ByteArray) {
        if (pcm.isEmpty()) return
        val e = epoch
        executor.execute {
            if (e != epoch) return@execute
            val t = track ?: return@execute
            val written = t.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
            if (written < 0) Log.w(TAG, "AudioTrack.write -> $written")
        }
    }

    /** 正常收尾（ctrl "end"）：MODE_STREAM 下 stop() 把剩余缓冲播完再停，随后释放。 */
    fun end() {
        val e = epoch
        executor.execute {
            if (e != epoch) return@execute
            val t = track ?: return@execute
            runCatching { t.stop() }
            try {
                Thread.sleep(300)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            releaseTrack()
            Log.i(TAG, "stream ended (epoch=$e)")
        }
    }

    /** 打断（ctrl "stop"）：立即静音并释放，作废排队中的喂流任务。 */
    fun stop() {
        val e = ++epoch
        executor.execute {
            releaseTrack()
            Log.i(TAG, "stream stopped (epoch=$e)")
        }
    }

    /** 仅 executor 线程调用 */
    private fun releaseTrack() {
        track?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        track = null
    }
}
