package com.rokidlab.phone.adb

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer

/**
 * 接收 scrcpy-server (tunnel_forward) 的 H.264 视频流并解码到 Surface。
 *
 * scrcpy-server 数据格式：
 *   1B dummy (0x00) + 64B device name
 *   12B video header: 4B codec_id (int LE) + 4B width (BE u32) + 4B height (BE u32)
 *   然后循环：
 *     12B frame meta: 8B ptsAndFlags (BE u64) + 4B packetSize (BE u32)
 *     packetSize 字节 H.264 NALU 数据
 *
 *   ptsAndFlags:
 *     bit 63: PACKET_FLAG_CONFIG (1) → config packet (CSD)
 *     bit 62: PACKET_FLAG_KEY_FRAME (1) → key frame
 *     bits 0-61: pts (微秒)
 */
class ScreenStreamDecoder(
    private val outputSurface: Surface,
    /** 视频尺寸变化回调（在 feedData 线程中调用，需要 post 到 UI 线程） */
    var onVideoSizeChanged: ((width: Int, height: Int) -> Unit)? = null,
) {
    companion object {
        private const val TAG = "ScreenStreamDecoder"
        private const val TIMEOUT_US = 10_000L

        private const val PACKET_FLAG_CONFIG = 1L shl 63
        private const val PACKET_FLAG_KEY_FRAME = 1L shl 62

        private const val DEVICE_META_SIZE = 65 // 1 dummy + 64 device name
        private const val VIDEO_HEADER_SIZE = 12 // 4 codec + 4 w + 4 h
        private const val FRAME_META_SIZE = 12 // 8 ptsAndFlags + 4 packetSize
    }

    private var mediaCodec: MediaCodec? = null
    @Volatile private var isRunning = false
    @Volatile private var codecReady = false

    /** 缓冲区，处理 ADB WRTE 分包 */
    private val frameBuffer = ByteArrayOutputStream()

    /** 解析状态 */
    private var skipRemaining = DEVICE_META_SIZE
    private var videoHeaderParsed = false
    private var videoWidth = 480
    private var videoHeight = 640

    /** 当前帧解析 */
    private var expectedPacketSize = -1
    private var currentPts = 0L
    private var currentConfig = false

    /** 统计 */
    private var frameCount = 0
    private var lastLogTime = 0L
    private var configPackets = 0

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
        Log.i(TAG, "解码器启动")
    }

    fun feedData(data: ByteArray) {
        if (!isRunning) return
        try {
            // 跳过设备元数据
            if (skipRemaining > 0) {
                if (data.size <= skipRemaining) {
                    skipRemaining -= data.size
                    return
                }
                val skip = skipRemaining
                skipRemaining = 0
                val remaining = data.copyOfRange(skip, data.size)
                Log.i(TAG, "设备元数据跳过完成, 有效数据 ${remaining.size} 字节")
                frameBuffer.write(remaining)
                processBuffer()
                return
            }

            frameBuffer.write(data)
            processBuffer()
        } catch (e: Exception) {
            Log.e(TAG, "feedData 错误: ${e.message}")
        }
    }

    private fun processBuffer() {
        val buf = frameBuffer.toByteArray()
        var offset = 0
        var framesInThisCall = 0

        while (true) {
            if (!videoHeaderParsed) {
                // 需要完整的 12 字节视频头
                if (buf.size - offset < VIDEO_HEADER_SIZE) break

                // 跳过 codec_id (4B)
                val codecId = readBe32(buf, offset) // "h264" = 0x68323634
                videoWidth = readBe32(buf, offset + 4)
                videoHeight = readBe32(buf, offset + 8)
                Log.i(TAG, "视频头: codec_id=0x${codecId.toString(16)}, ${videoWidth}x${videoHeight}")
                offset += VIDEO_HEADER_SIZE
                videoHeaderParsed = true
                onVideoSizeChanged?.invoke(videoWidth, videoHeight)
                framesInThisCall++
                continue
            }

            if (expectedPacketSize < 0) {
                // 需要完整的 12 字节帧元数据
                if (buf.size - offset < FRAME_META_SIZE) break

                val ptsAndFlags = readBe64(buf, offset)
                expectedPacketSize = readBe32(buf, offset + 8)
                currentPts = ptsAndFlags and 0x3FFFFFFFFFFFFFFF
                currentConfig = (ptsAndFlags and PACKET_FLAG_CONFIG) != 0L

                // 数据包大小检查
                if (expectedPacketSize <= 0 || expectedPacketSize > 3 * 1024 * 1024) {
                    Log.w(TAG, "包大小异常: $expectedPacketSize, config=$currentConfig, pts=$currentPts")
                    offset += FRAME_META_SIZE
                    expectedPacketSize = -1
                    continue
                }
                offset += FRAME_META_SIZE
            }

            val available = buf.size - offset
            if (available >= expectedPacketSize) {
                // 完整的数据包
                val payload = buf.copyOfRange(offset, offset + expectedPacketSize)
                offset += expectedPacketSize
                expectedPacketSize = -1

                if (currentConfig) {
                    // Config 包（CSD: SPS/PPS）
                    configPackets++
                    Log.i(TAG, "Config 包 #$configPackets: size=${payload.size}")
                    if (!codecReady) {
                        tryInitCodec(payload)
                    } else {
                        // 重新配置解码器（可能是旋转或分辨率变化）
                        feedConfigToCodec(payload)
                    }
                } else {
                    // 视频帧
                    frameCount++
                    framesInThisCall++
                    if (!codecReady) {
                        // 还没有 CSD，尝试从帧中找 SPS/PPS
                        Log.i(TAG, "首帧尝试初始化解码器, size=${payload.size}")
                        tryInitCodec(payload)
                    }
                    if (codecReady) {
                        feedToCodec(payload, currentPts)
                    }
                }
            } else {
                // 数据不足
                break
            }
        }

        // 更新缓冲区
        if (offset > 0) {
            val remaining = frameBuffer.toByteArray()
            if (offset < remaining.size) {
                frameBuffer.reset()
                frameBuffer.write(remaining, offset, remaining.size - offset)
            } else {
                frameBuffer.reset()
            }
        }

        // 日志统计
        val now = System.currentTimeMillis()
        if (now - lastLogTime > 2000 && framesInThisCall > 0) {
            lastLogTime = now
            Log.i(TAG, "已处理 $frameCount 视频帧, buffer剩余=${frameBuffer.size()}")
        }
    }

    private fun tryInitCodec(data: ByteArray) {
        try {
            // 打印前 20 字节调试
            val preview = data.take(30).joinToString(" ") { b -> (b.toInt() and 0xFF).toString(16).padStart(2, '0') }
            Log.i(TAG, "tryInitCodec: data=${data.size}, head=[$preview]")

            // 从 data 中提取 SPS 和 PPS NALUs
            val spsStart = findNal(data, 0x67)
            val ppsStart = findNal(data, 0x68)

            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, videoWidth, videoHeight)

            if (spsStart >= 0 && ppsStart >= 0) {
                val spsEnd = if (ppsStart > spsStart) ppsStart else data.size
                val sps = data.copyOfRange(spsStart, spsEnd)
                val pps = data.copyOfRange(ppsStart, data.size)
                format.setByteBuffer("csd-0", ByteBuffer.wrap(sps))
                format.setByteBuffer("csd-1", ByteBuffer.wrap(pps))
                Log.i(TAG, "SPS/PPS 提取成功, sps=${sps.size} pps=${pps.size}")
            } else {
                // 没有 start codes，直接把整个 data 作为 csd-0
                // 对于 AVCC 格式（4B 长度前缀），csd 需要提取 SPS/PPS
                // 尝试 AVCC 解析: [1B version][3B profile/level][1B reserved+nal_size][3B reserved+num_sps][2B sps_len][SPS]...
                var avcParsed = false
                if (data.size > 8 && data[0] == 1.toByte()) {
                    val nalSizeLen = (data[4].toInt() and 0x03) + 1
                    val numSps = data[5].toInt() and 0x1F
                    if (numSps > 0 && data.size > 8) {
                        val spsLen = ((data[6].toInt() and 0xFF) shl 8) or (data[7].toInt() and 0xFF)
                        if (spsLen > 0 && 8 + spsLen <= data.size) {
                            val sps = data.copyOfRange(8, 8 + spsLen)
                            // 添加 0x00000001 start code
                            val spsWithStart = ByteArray(4 + spsLen)
                            spsWithStart[3] = 1
                            System.arraycopy(sps, 0, spsWithStart, 4, spsLen)
                            format.setByteBuffer("csd-0", ByteBuffer.wrap(spsWithStart))
                            Log.i(TAG, "AVCC 解析 SPS: len=$spsLen, nalSizeLen=$nalSizeLen")

                            // 检查是否有 PPS
                            val ppsOff = 8 + spsLen
                            if (ppsOff + 2 <= data.size) {
                                val numPps = data[ppsOff].toInt() and 0x1F
                                if (numPps > 0 && ppsOff + 2 <= data.size) {
                                    val ppsLen = ((data[ppsOff + 1].toInt() and 0xFF) shl 8) or (data[ppsOff + 2].toInt() and 0xFF)
                                    if (ppsLen > 0 && ppsOff + 3 + ppsLen <= data.size) {
                                        val pps = data.copyOfRange(ppsOff + 3, ppsOff + 3 + ppsLen)
                                        val ppsWithStart = ByteArray(4 + ppsLen)
                                        ppsWithStart[3] = 1
                                        System.arraycopy(pps, 0, ppsWithStart, 4, ppsLen)
                                        format.setByteBuffer("csd-1", ByteBuffer.wrap(ppsWithStart))
                                        Log.i(TAG, "AVCC 解析 PPS: len=$ppsLen")
                                    }
                                }
                            }
                            avcParsed = true
                        }
                    }
                }
                if (!avcParsed) {
                    format.setByteBuffer("csd-0", ByteBuffer.wrap(data))
                    Log.w(TAG, "直接使用 config 包作为 csd-0")
                }
            }

            val codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            codec.configure(format, outputSurface, null, 0)
            codec.start()
            mediaCodec = codec
            codecReady = true
            Log.i(TAG, "MediaCodec 启动成功: ${videoWidth}x${videoHeight}")
        } catch (e: java.lang.IllegalStateException) {
            Log.e(TAG, "tryInitCodec IllegalStateException: ${e.message}")
        } catch (e: java.lang.IllegalArgumentException) {
            Log.e(TAG, "tryInitCodec IllegalArgumentException: ${e.message}")
        } catch (e: java.io.IOException) {
            Log.e(TAG, "tryInitCodec IOException: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "tryInitCodec: ${e.message}")
        }
    }

    private fun feedConfigToCodec(data: ByteArray) {
        val codec = mediaCodec ?: return
        try {
            // 将 config 数据作为 CSD 重新配置解码器
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

    // ---- 工具方法 ----

    /** 读大端 u32 */
    private fun readBe32(buf: ByteArray, off: Int): Int {
        return ((buf[off].toInt() and 0xFF) shl 24) or
                ((buf[off + 1].toInt() and 0xFF) shl 16) or
                ((buf[off + 2].toInt() and 0xFF) shl 8) or
                (buf[off + 3].toInt() and 0xFF)
    }

    /** 读小端 u32 */
    private fun readLe32(buf: ByteArray, off: Int): Int {
        return (buf[off].toInt() and 0xFF) or
                ((buf[off + 1].toInt() and 0xFF) shl 8) or
                ((buf[off + 2].toInt() and 0xFF) shl 16) or
                ((buf[off + 3].toInt() and 0xFF) shl 24)
    }

    /** 读大端 u64 */
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

    /** 查找指定 NAL 类型的起始码位置（支持 3 字节 0x000001 和 4 字节 0x00000001） */
    private fun findNal(data: ByteArray, targetType: Int): Int {
        var i = 0
        while (i < data.size - 3) {
            // 检查 3 字节起始码 0x00 0x00 0x01
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte()) {
                val nalOff = if (i + 3 < data.size) i + 3 else continue
                val nalType = data[nalOff].toInt() and 0x1F
                if (nalType == targetType) return i
                i += 3
            } else {
                i++
            }
        }
        return -1
    }

    /** 使用标准 ByteArrayOutputStream */
    private class ByteArrayOutputStream : java.io.ByteArrayOutputStream() {
        override fun size(): Int = count
    }

    fun stop() {
        isRunning = false
        try { mediaCodec?.stop() } catch (_: Exception) {}
        try { mediaCodec?.release() } catch (_: Exception) {}
        mediaCodec = null
        codecReady = false
        frameBuffer.reset()
        expectedPacketSize = -1
        Log.i(TAG, "解码器已释放, 共处理 $frameCount 帧")
    }

    fun reset() {
        stop()
        Log.i(TAG, "解码器已重置")
    }
}
