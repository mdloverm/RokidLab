package com.rokidlab.phone.adb

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer

class ScreenStreamDecoder(
    private val outputSurface: Surface,
    var onVideoSizeChanged: ((width: Int, height: Int) -> Unit)? = null,
) {
    companion object {
        private const val TAG = "ScreenStreamDecoder"
        private const val TIMEOUT_US = 10_000L
        private const val PACKET_FLAG_CONFIG = 1L shl 63
        private const val DEVICE_META_SIZE = 65
        private const val VIDEO_HEADER_SIZE = 12
        private const val FRAME_META_SIZE = 12
    }

    private var mediaCodec: MediaCodec? = null
    @Volatile private var isRunning = false
    @Volatile private var codecReady = false

    private val frameBuffer = ByteArrayOutputStream()
    private var skipRemaining = DEVICE_META_SIZE
    private var videoHeaderParsed = false
    private var videoWidth = 480
    private var videoHeight = 640
    private var expectedPacketSize = -1
    private var currentPts = 0L
    private var currentConfig = false
    private var frameCount = 0
    private var lastLogTime = 0L
    private var configPackets = 0
    private var lastConfigData: ByteArray? = null

    fun start() {
        isRunning = true
        codecReady = false
        mediaCodec = null
        frameBuffer.reset()
        skipRemaining = DEVICE_META_SIZE
        videoHeaderParsed = false
        expectedPacketSize = -1
        frameCount = 0
        lastLogTime = 0L
        configPackets = 0
        videoWidth = 480
        videoHeight = 640
        Log.i(TAG, "decoder started")
    }

    fun feedData(data: ByteArray) {
        if (!isRunning) return
        try {
            if (skipRemaining > 0) {
                if (data.size <= skipRemaining) {
                    skipRemaining -= data.size
                    return
                }
                val skip = skipRemaining
                skipRemaining = 0
                val remaining = data.copyOfRange(skip, data.size)
                Log.i(TAG, "device meta skipped, remaining ${remaining.size} bytes")
                frameBuffer.write(remaining)
                processBuffer()
                return
            }
            frameBuffer.write(data)
            processBuffer()
        } catch (e: Exception) {
            Log.e(TAG, "feedData error: ${e.message}")
        }
    }

    private fun processBuffer() {
        val buf = frameBuffer.toByteArray()
        var offset = 0
        var framesInThisCall = 0

        while (true) {
            if (!videoHeaderParsed) {
                if (buf.size - offset < VIDEO_HEADER_SIZE) break
                val codecId = readBe32(buf, offset)
                videoWidth = readBe32(buf, offset + 4)
                videoHeight = readBe32(buf, offset + 8)
                Log.i(TAG, "video header: codec_id=0x${codecId.toString(16)}, ${videoWidth}x${videoHeight}")
                offset += VIDEO_HEADER_SIZE
                videoHeaderParsed = true
                onVideoSizeChanged?.invoke(videoWidth, videoHeight)
                framesInThisCall++
                continue
            }

            if (expectedPacketSize < 0) {
                if (buf.size - offset < FRAME_META_SIZE) break
                val ptsAndFlags = readBe64(buf, offset)
                expectedPacketSize = readBe32(buf, offset + 8)
                currentPts = ptsAndFlags and 0x3FFFFFFFFFFFFFFF
                currentConfig = (ptsAndFlags and PACKET_FLAG_CONFIG) != 0L
                if (expectedPacketSize <= 0 || expectedPacketSize > 3 * 1024 * 1024) {
                    Log.w(TAG, "bad packet size: $expectedPacketSize")
                    offset += FRAME_META_SIZE
                    expectedPacketSize = -1
                    continue
                }
                offset += FRAME_META_SIZE
            }

            val available = buf.size - offset
            if (available >= expectedPacketSize) {
                val payload = buf.copyOfRange(offset, offset + expectedPacketSize)
                offset += expectedPacketSize
                expectedPacketSize = -1

                if (currentConfig) {
                    configPackets++
                    lastConfigData = payload
                    if (!codecReady) {
                        tryInitCodec(payload)
                    } else {
                        feedConfigToCodec(payload)
                    }
                } else {
                    frameCount++
                    framesInThisCall++
                    if (!codecReady) {
                        Log.i(TAG, "first frame, try init codec, size=${payload.size}")
                        tryInitCodec(payload)
                    }
                    if (codecReady) {
                        feedToCodec(payload, currentPts)
                    }
                }
            } else {
                break
            }
        }

        if (offset > 0) {
            val remaining = frameBuffer.toByteArray()
            if (offset < remaining.size) {
                frameBuffer.reset()
                frameBuffer.write(remaining, offset, remaining.size - offset)
            } else {
                frameBuffer.reset()
            }
        }

        val now = System.currentTimeMillis()
        if (now - lastLogTime > 2000 && framesInThisCall > 0) {
            lastLogTime = now
            Log.i(TAG, "processed $frameCount video frames, bufferRemaining=${frameBuffer.size()}")
        }
    }

    private fun tryInitCodec(data: ByteArray) {
        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, videoWidth, videoHeight)
            format.setByteBuffer("csd-0", ByteBuffer.wrap(data))
            Log.i(TAG, "init codec with full data, size=${data.size}")
            val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec.configure(format, outputSurface, null, 0)
            codec.start()
            mediaCodec = codec
            codecReady = true
            Log.i(TAG, "MediaCodec started: ${videoWidth}x${videoHeight}")
        } catch (e: Exception) {
            Log.e(TAG, "tryInitCodec: ${e.message}")
        }
    }

    private fun feedConfigToCodec(data: ByteArray) {
        val codec = mediaCodec ?: return
        try {
            val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
            if (inIndex >= 0) {
                val buf = codec.getInputBuffer(inIndex) ?: return
                buf.clear()
                buf.put(data)
                codec.queueInputBuffer(inIndex, 0, data.size, 0, MediaCodec.BUFFER_FLAG_CODEC_CONFIG)
            }
            val bufferInfo = MediaCodec.BufferInfo()
            var outIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
            while (outIndex >= 0) {
                codec.releaseOutputBuffer(outIndex, true)
                outIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
            }
        } catch (e: Exception) {
            Log.e(TAG, "feedConfigToCodec: ${e.message}")
        }
    }

    private fun feedToCodec(data: ByteArray, pts: Long) {
        val codec = mediaCodec ?: return
        try {
            val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
            if (inIndex >= 0) {
                val buf = codec.getInputBuffer(inIndex) ?: return
                buf.clear()
                buf.put(data)
                codec.queueInputBuffer(inIndex, 0, data.size, pts, 0)
            }
            val bufferInfo = MediaCodec.BufferInfo()
            var outIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
            while (outIndex >= 0) {
                codec.releaseOutputBuffer(outIndex, true)
                outIndex = codec.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
            }
        } catch (e: Exception) {
            Log.e(TAG, "feedToCodec: ${e.message}")
        }
    }

    private fun readBe32(buf: ByteArray, off: Int): Int {
        return ((buf[off].toInt() and 0xFF) shl 24) or
                ((buf[off + 1].toInt() and 0xFF) shl 16) or
                ((buf[off + 2].toInt() and 0xFF) shl 8) or
                (buf[off + 3].toInt() and 0xFF)
    }

    private fun readBe64(buf: ByteArray, off: Int): Long {
        return ((buf[off].toLong() and 0xFF) shl 56) or
                ((buf[off + 1].toLong() and 0xFF) shl 48) or
                ((buf[off + 2].toLong() and 0xFF) shl 40) or
                ((buf[off + 3].toLong() and 0xFF) shl 32) or
                ((buf[off + 4].toLong() and 0xFF) shl 24) or
                ((buf[off + 5].toLong() and 0xFF) shl 16) or
                ((buf[off + 6].toLong() and 0xFF) shl 8) or
                (buf[off + 7].toLong() and 0xFF)
    }

    private class ByteArrayOutputStream : java.io.ByteArrayOutputStream() {
        override fun size(): Int = count
    }

    fun stop() {
        isRunning = false
        try { mediaCodec?.stop() } catch (_: Exception) {}
        try { mediaCodec?.release() } catch (_: Exception) {}
        mediaCodec = null
        codecReady = false
        Log.i(TAG, "decoder released, total $frameCount frames")
    }
}
