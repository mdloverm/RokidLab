package com.rokidlab.phone.adb

import java.io.InputStream
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * ADB 流协议单测的脚本化对端 + 报文编解码工具。
 *
 * 背景：`AdbShellClient` / `AdbFileManagerClient` 的 sync 流协议（RECV / DATA / DONE / FAIL、
 * 陈旧 CLSE 排空）是纯内存逻辑，但客户端直接持有 Socket，导致这段最容易出错的代码
 * （历史上出过「FAIL 被当成功 → 产出 0 字节文件」「陈旧 CLSE 被读成本次响应」）长期没有测试。
 * 生产代码为此提供了 `attachStreamsForTest` 注入口，本文件提供配套的假对端与报文断言工具。
 */
internal object AdbTestProto {

    const val HEADER_LENGTH = 24

    // 与客户端常量同值（小端 int 编码的 ASCII）
    const val CMD_OPEN = 0x4e45504f // "OPEN"
    const val CMD_OKAY = 0x59414b4f // "OKAY"
    const val CMD_CLSE = 0x45534c43 // "CLSE"
    const val CMD_WRTE = 0x45545257 // "WRTE"

    /** 对端 → 客户端方向的 ADB 报文（小端头 + 负载） */
    fun packet(command: Int, arg0: Int, arg1: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        val buf = ByteBuffer.allocate(HEADER_LENGTH + payload.size).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(command)
        buf.putInt(arg0)
        buf.putInt(arg1)
        buf.putInt(payload.size)
        buf.putInt(checksum(payload))
        buf.putInt(command.inv())
        if (payload.isNotEmpty()) buf.put(payload)
        return buf.array()
    }

    /** ADB sync 帧：[4 字节 id]["id" 之后是 4 字节小端长度][负载] */
    fun syncFrame(id: String, data: ByteArray = ByteArray(0)): ByteArray =
        ByteBuffer.allocate(8 + data.size).order(ByteOrder.LITTLE_ENDIAN)
            .put(id.toByteArray(Charsets.US_ASCII))
            .putInt(data.size)
            .put(data)
            .array()

    fun checksum(data: ByteArray): Int {
        var sum = 0
        for (b in data) sum += b.toInt() and 0xFF
        return sum
    }

    /** 客户端 → 对端方向报文的断言视图 */
    class Written(
        val command: Int,
        val arg0: Int,
        val arg1: Int,
        val payload: ByteArray,
        val checksum: Int,
    )

    /** 把客户端写出的字节流切分为报文列表（用于断言「我们到底发了什么」） */
    fun parseWritten(bytes: ByteArray): List<Written> {
        val out = mutableListOf<Written>()
        var off = 0
        while (off + HEADER_LENGTH <= bytes.size) {
            val buf = ByteBuffer.wrap(bytes, off, HEADER_LENGTH).order(ByteOrder.LITTLE_ENDIAN)
            val command = buf.getInt()
            val arg0 = buf.getInt()
            val arg1 = buf.getInt()
            val len = buf.getInt()
            val ck = buf.getInt()
            buf.getInt() // magic
            require(len >= 0 && off + HEADER_LENGTH + len <= bytes.size) { "报文长度越界: $len" }
            out += Written(
                command,
                arg0,
                arg1,
                bytes.copyOfRange(off + HEADER_LENGTH, off + HEADER_LENGTH + len),
                ck,
            )
            off += HEADER_LENGTH + len
        }
        return out
    }

    /** 从 sync 帧负载里取出帧 id（前 4 字节 ASCII） */
    fun syncFrameId(payload: ByteArray): String =
        if (payload.size < 4) "" else String(payload, 0, 4, Charsets.US_ASCII)
}

/**
 * 脚本化 adbd 对端：按「段」喂字节，**段与段之间抛 [SocketTimeoutException]**。
 *
 * 段边界即模拟 `soTimeout` 到期 —— `drainStalePackets` / `drainStaleMessages` 正是靠
 * 「读超时」结束排空循环的，用普通 `ByteArrayInputStream` 会把整段脚本一次读光，
 * 排空逻辑也就无从验证。段内耗尽后返回 EOF(-1)，用于验证读线程的正常退出路径。
 */
internal class ScriptedPeer(segments: List<ByteArray>) : InputStream() {

    private val pending = segments.toMutableList()
    private var current: ByteArray? = null
    private var pos = 0

    override fun read(): Int {
        val one = ByteArray(1)
        val n = read(one, 0, 1)
        return if (n <= 0) -1 else one[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        while (true) {
            val cur = current
            if (cur != null && pos < cur.size) {
                val n = minOf(len, cur.size - pos)
                System.arraycopy(cur, pos, b, off, n)
                pos += n
                return n
            }
            current = null
            if (pending.isEmpty()) return -1 // 脚本结束 → 真 EOF
            if (cur != null) throw SocketTimeoutException("scripted peer: segment exhausted")
            current = pending.removeAt(0)
            pos = 0
        }
    }
}
