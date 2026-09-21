package com.rokidlab.phone.glasses

import android.util.Log
import com.rokid.cxr.link.callbacks.IAudioStreamCbk
import kotlin.math.sqrt

/**
 * M0 音频流真机验证 spike（「聆听模式」前置调研）。
 *
 * 目的：验证 CXR-L `startAudioStream(codecType=1)` 在真机上是否回吐 PCM、
 * 实际格式（预期 16kHz/mono/16bit ≈ 32000 B/s）、块大小分布与语音 RMS 起伏，
 * 为 M1 AmbientListenController 的 VAD/分段参数提供实测依据。
 *
 * 触发入口在 debug 源集（[com.rokidlab.phone.debug.AudioSpikeReceiver]，release 不含）：
 * ```
 * adb shell am broadcast -a com.rokidlab.phone.debug.AUDIO_SPIKE --es action start
 * # ... 对眼镜说话 ...
 * adb shell am broadcast -a com.rokidlab.phone.debug.AUDIO_SPIKE --es action stop
 * ```
 * 输出：`adb logcat -s GlassAudioSpike`
 *
 * 统计均在回调线程更新（SDK binder 线程），只在 stop 时汇总，不做跨线程锁竞争热路径。
 */
object GlassAudioSpike {
    private const val TAG = "GlassAudioSpike"
    private const val CODEC_PCM = 1

    /** 每 N 块打印一次节拍日志，避免 log 洪泛 */
    private const val LOG_EVERY_N_CHUNKS = 25

    /** 预期码率：16kHz × 2 byte（mono/16bit）= 32000 B/s */
    private const val EXPECTED_BYTES_PER_SEC = 32_000

    /** 码率判定窗口 ±15%：窗口内判定为 16kHz/mono/16bit */
    private const val RATE_TOLERANCE = 0.15

    @Volatile private var running = false
    private var chunkCount = 0L
    private var totalBytes = 0L
    private var startMs = 0L
    private var lastArriveMs = 0L
    private var maxGapMs = 0L
    private var rmsMin = Int.MAX_VALUE
    private var rmsMax = 0
    private var rmsSum = 0.0
    private var chunkBytesMin = Int.MAX_VALUE
    private var chunkBytesMax = 0

    fun start(session: CxrLHiRokidSession) {
        val link = session.cxrLink
        if (link == null) {
            Log.w(TAG, "start: no active CXR link (cxrLink is null) - connect glasses first")
            return
        }
        if (running) {
            Log.w(TAG, "start: already running")
            return
        }
        reset()
        running = true
        startMs = System.currentTimeMillis()
        link.setCXRAudioCbk(object : IAudioStreamCbk {
            override fun onAudioReceived(data: ByteArray, offset: Int, length: Int) {
                if (!running) return
                val now = System.currentTimeMillis()
                if (lastArriveMs > 0) {
                    val gap = now - lastArriveMs
                    if (gap > maxGapMs) maxGapMs = gap
                }
                lastArriveMs = now
                chunkCount++
                totalBytes += length
                if (length < chunkBytesMin) chunkBytesMin = length
                if (length > chunkBytesMax) chunkBytesMax = length
                val rms = rmsOf(data, offset, length)
                if (rms < rmsMin) rmsMin = rms
                if (rms > rmsMax) rmsMax = rms
                rmsSum += rms.toDouble()
                if (chunkCount % LOG_EVERY_N_CHUNKS == 0L) {
                    val secs = (now - startMs) / 1000.0
                    val rate = if (secs > 0) (totalBytes / secs).toInt() else 0
                    Log.i(
                        TAG,
                        "tick #$chunkCount bytes=$totalBytes rate=${rate}B/s rms=$rms " +
                            "avgRms=${(rmsSum / chunkCount).toInt()} chunkMin=$chunkBytesMin " +
                            "chunkMax=$chunkBytesMax maxGap=${maxGapMs}ms"
                    )
                }
            }

            override fun onAudioError(code: Int, message: String) {
                Log.e(TAG, "onAudioError($code): $message")
            }

            override fun onAudioStreamStateChanged(streaming: Boolean) {
                Log.i(TAG, "onAudioStreamStateChanged: streaming=$streaming")
            }
        })
        val ok = link.startAudioStream(CODEC_PCM)
        Log.i(TAG, "startAudioStream(codec=$CODEC_PCM) -> $ok")
        if (!ok) {
            running = false
            Log.w(TAG, "verdict: startAudioStream returned false - SDK rejected (session/permission?)")
        }
    }

    fun stop(session: CxrLHiRokidSession) {
        if (!running) {
            Log.w(TAG, "stop: not running (call start first)")
            return
        }
        running = false
        val link = session.cxrLink
        if (link == null) {
            Log.w(TAG, "stop: link already gone, skip stopAudioStream")
        } else {
            runCatching { link.stopAudioStream() }
                .onFailure { Log.e(TAG, "stopAudioStream threw: ${it.message}") }
        }
        report()
    }

    private fun report() {
        val secs = (System.currentTimeMillis() - startMs) / 1000.0
        val rate = if (secs > 0.2) (totalBytes / secs).toInt() else 0
        val avgRms = if (chunkCount > 0) (rmsSum / chunkCount).toInt() else 0
        Log.i(TAG, "=== SPIKE REPORT ===")
        Log.i(TAG, "duration=${"%.1f".format(secs)}s chunks=$chunkCount bytes=$totalBytes")
        Log.i(TAG, "avgRate=${rate}B/s (expected ~$EXPECTED_BYTES_PER_SEC for 16kHz/mono/16bit)")
        Log.i(TAG, "chunkSize min=$chunkBytesMin max=$chunkBytesMax maxArrivalGap=${maxGapMs}ms")
        Log.i(TAG, "rms min=$rmsMin avg=$avgRms max=$rmsMax (near-0 = silence; >1000 = real speech)")
        val verdict = when {
            chunkCount == 0L ->
                "NO AUDIO RECEIVED: stream dead / SDK rejected / glasses session not active"
            rate >= EXPECTED_BYTES_PER_SEC * (1 - RATE_TOLERANCE) &&
                rate <= EXPECTED_BYTES_PER_SEC * (1 + RATE_TOLERANCE) ->
                "FORMAT MATCH: 16kHz/mono/16bit PCM confirmed"
            else ->
                "RATE MISMATCH: got ${rate}B/s - different format or arrival gaps, inspect chunkSize"
        }
        Log.i(TAG, "verdict: $verdict")
    }

    private fun reset() {
        chunkCount = 0
        totalBytes = 0
        startMs = 0
        lastArriveMs = 0
        maxGapMs = 0
        rmsMin = Int.MAX_VALUE
        rmsMax = 0
        rmsSum = 0.0
        chunkBytesMin = Int.MAX_VALUE
        chunkBytesMax = 0
    }

    /** 16bit little-endian PCM 的 RMS（0..32767 量级） */
    private fun rmsOf(data: ByteArray, offset: Int, length: Int): Int {
        if (length < 2) return 0
        var sum = 0.0
        var n = 0
        var i = offset
        val end = offset + length - 1
        while (i < end) {
            val lo = data[i].toInt() and 0xFF
            val hi = data[i + 1].toInt() shl 8
            val s = (hi or lo).toShort().toInt()
            sum += (s * s).toDouble()
            n++
            i += 2
        }
        return if (n == 0) 0 else sqrt(sum / n).toInt()
    }
}
