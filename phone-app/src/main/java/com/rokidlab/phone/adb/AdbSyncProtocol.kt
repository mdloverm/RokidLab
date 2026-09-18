package com.rokidlab.phone.adb

/**
 * ADB 协议纯逻辑（v3.9 从 [AdbFileManagerClient] 抽出，无 IO/Android 依赖，可直接单测）。
 *
 * 为什么值得锁：
 * 1. 下载路径的「sync 消息重组缓冲」曾有历史 bug —— 旧实现假设「一个传输层 WRTE =
 *    一条完整 sync 消息」，而 adbd 实际把 DATA 的 8 字节包头与文件负载分两次 write
 *    （大负载还会再拆）。假设不成立时整块负载被静默丢弃，产出文件恒为 64KB
 *    整数倍截断且不报错。组帧语义一旦漂移，用户看到的是文件损坏而无任何提示。
 * 2. 下载落盘后的字节数对账是「截断文件被当完整文件使用」的最后一道闸。
 */
internal object AdbProtocol {

    // ── 帧构造（上传路径） ──

    private fun putLe32(dst: ByteArray, off: Int, v: Int) {
        dst[off] = (v and 0xFF).toByte()
        dst[off + 1] = ((v shr 8) and 0xFF).toByte()
        dst[off + 2] = ((v shr 16) and 0xFF).toByte()
        dst[off + 3] = ((v shr 24) and 0xFF).toByte()
    }

    /** sync RECV 请求："RECV" + 4 字节 LE 路径长度 + 路径 */
    internal fun buildRecvRequest(remotePath: String): ByteArray =
        buildPrefixed("RECV", remotePath.toByteArray(Charsets.UTF_8))

    /** sync SEND 请求："SEND" + 4 字节 LE 路径长度 + "路径,mode"（33188 = 0100644） */
    internal fun buildSendRequest(remotePath: String): ByteArray =
        buildPrefixed("SEND", "$remotePath,33188".toByteArray(Charsets.UTF_8))

    /** sync DATA 帧："DATA" + 4 字节 LE 负载长度 + 负载（[:length) 区间） */
    internal fun buildDataChunk(payload: ByteArray, length: Int = payload.size): ByteArray {
        require(length in 0..payload.size) { "bad data chunk length: $length > ${payload.size}" }
        return buildPrefixed("DATA", payload, length)
    }

    /** sync DONE 帧："DONE" + 4 字节 LE 时间戳（秒） */
    internal fun buildDoneCommand(timestampSec: Int): ByteArray =
        buildPrefixed("DONE", ByteArray(0)).also { putLe32(it, 4, timestampSec) }

    private fun buildPrefixed(tag: String, body: ByteArray, bodyLen: Int = body.size): ByteArray {
        val out = ByteArray(8 + bodyLen)
        System.arraycopy(tag.toByteArray(Charsets.UTF_8), 0, out, 0, 4)
        putLe32(out, 4, bodyLen)
        System.arraycopy(body, 0, out, 8, bodyLen)
        return out
    }

    // ── 帧重组（下载路径） ──

    /** 重组后的 sync 帧。[Data.bytes] 为独立副本（内部缓冲会复用）。 */
    internal sealed class SyncFrame {
        internal data class Data(val bytes: ByteArray) : SyncFrame()
        internal data class Fail(val message: String) : SyncFrame()
        internal object Done : SyncFrame()
    }

    /**
     * sync 消息重组器：ADB 流是**字节流**，传输层 WRTE 只是分片，不等于 sync 消息边界。
     * 逐次 [feed] 任意大小的字节，吐出攒齐的完整帧；半帧留在内部缓冲等下一次 feed。
     *
     * 语义与原 AdbFileManagerClient 内联实现逐条一致：
     * - DATA 负载未到齐 → 不产出（等下一片）
     * - FAIL 报错文案未到齐 → 不产出（等下一片）
     * - 空 DATA（size=0）→ 消费掉但不产出
     * - 未知帧名 → 抛 [IllegalArgumentException]，消息与原实现逐字一致
     *   （"Unexpected sync frame: X"），调用方按异常路径删半成品返回失败。
     */
    internal class SyncFrameAssembler(initialCapacity: Int = 64 * 1024 + 64) {
        private var acc = ByteArray(initialCapacity)
        private var accLen = 0

        internal fun feed(data: ByteArray): List<SyncFrame> {
            if (accLen + data.size > acc.size) {
                acc = acc.copyOf(maxOf(acc.size * 2, accLen + data.size))
            }
            System.arraycopy(data, 0, acc, accLen, data.size)
            accLen += data.size

            val frames = ArrayList<SyncFrame>(2)
            var pos = 0
            parse@ while (accLen - pos >= 8) {
                val cmdStr = String(acc, pos, 4, Charsets.UTF_8)
                val size = readLe32(acc, pos + 4)
                when (cmdStr) {
                    "FAIL" -> {
                        if (accLen - pos - 8 < size) break@parse // 报错文案未到齐，等下一片
                        frames += SyncFrame.Fail(
                            if (size > 0) String(acc, pos + 8, size, Charsets.UTF_8) else "Unknown error",
                        )
                        pos += 8 + size
                    }
                    "DATA" -> {
                        if (accLen - pos - 8 < size) break@parse // 负载未到齐，等下一片
                        if (size > 0) frames += SyncFrame.Data(acc.copyOfRange(pos + 8, pos + 8 + size))
                        pos += 8 + size
                    }
                    "DONE" -> {
                        frames += SyncFrame.Done
                        pos += 8
                        break@parse
                    }
                    else -> throw IllegalArgumentException("Unexpected sync frame: $cmdStr")
                }
            }
            if (pos > 0) {
                System.arraycopy(acc, pos, acc, 0, accLen - pos)
                accLen -= pos
            }
            return frames
        }

        private fun readLe32(buf: ByteArray, off: Int): Int =
            (buf[off].toInt() and 0xFF) or
                ((buf[off + 1].toInt() and 0xFF) shl 8) or
                ((buf[off + 2].toInt() and 0xFF) shl 16) or
                ((buf[off + 3].toInt() and 0xFF) shl 24)
    }

    // ── 杂项 ──

    /** ADB 包头校验和：逐字节无符号求和（协议约定，与 a.out.h checksum 同型） */
    internal fun adbChecksum(data: ByteArray?): Int {
        if (data == null) return 0
        var sum = 0
        for (b in data) sum += b.toInt() and 0xFF
        return sum
    }

    /**
     * 解析 df/du 输出的存储大小为字节数（df 默认 1K-blocks）。
     * 无法解析返回 0（原实现语义，调用方按 0 容量容错展示）。
     */
    internal fun parseStorageSize(sizeStr: String): Long {
        val trimmed = sizeStr.trim()
        if (trimmed.isEmpty()) return 0L

        // 尝试直接解析数字（df 输出的是 1K-blocks）
        trimmed.toLongOrNull()?.let { return it * 1024 }

        // 解析带单位的值
        val regex = Regex("([0-9.]+)([KMGTP]?)", RegexOption.IGNORE_CASE)
        val match = regex.find(trimmed)
        if (match != null) {
            val value = match.groupValues[1].toDoubleOrNull() ?: return 0L
            val unit = match.groupValues[2].uppercase()

            val multiplier = when (unit) {
                "K" -> 1024L
                "M" -> 1024L * 1024
                "G" -> 1024L * 1024 * 1024
                "T" -> 1024L * 1024 * 1024 * 1024
                "P" -> 1024L * 1024 * 1024 * 1024 * 1024
                else -> 1024L // 默认按 KB 处理
            }

            return (value * multiplier).toLong()
        }

        return 0L
    }
}
