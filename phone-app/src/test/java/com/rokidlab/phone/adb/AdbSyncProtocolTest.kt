package com.rokidlab.phone.adb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * ADB sync 协议纯逻辑（[AdbProtocol]）的回归测试。
 *
 * 为什么值得锁：下载路径的「sync 消息重组」曾有历史 bug —— 旧实现假设
 * 「一个传输层 WRTE = 一条完整 sync 消息」，adbd 实际把 DATA 包头与负载分两次 write，
 * 假设不成立时整块负载被静默丢弃，产出文件恒为 64KB 整数倍截断且不报错。
 * 组帧/帧构造的语义一旦漂移，用户看到的是文件损坏或传输失败而无任何提示。
 */
class AdbSyncProtocolTest {

    private fun le32(v: Int) = byteArrayOf(
        (v and 0xFF).toByte(),
        ((v shr 8) and 0xFF).toByte(),
        ((v shr 16) and 0xFF).toByte(),
        ((v shr 24) and 0xFF).toByte(),
    )

    private fun frame(tag: String, body: ByteArray) =
        tag.toByteArray(Charsets.UTF_8) + le32(body.size) + body

    // ── 帧构造 ──

    @Test
    fun `RECV 请求为标签加LE路径长度加路径`() {
        val path = "/sdcard/a.png"
        val expect = "RECV".toByteArray(Charsets.UTF_8) + le32(path.length) + path.toByteArray(Charsets.UTF_8)
        assertArrayEquals(expect, AdbProtocol.buildRecvRequest(path))
    }

    @Test
    fun `SEND 请求带 33188 权限后缀`() {
        val expect = "SEND".toByteArray(Charsets.UTF_8) +
            le32("/f.bin,33188".length) + "/f.bin,33188".toByteArray(Charsets.UTF_8)
        assertArrayEquals(expect, AdbProtocol.buildSendRequest("/f.bin"))
    }

    @Test
    fun `DATA 帧按指定长度截取缓冲区而非全量`() {
        val buffer = byteArrayOf(9, 8, 7, 6, 5)
        val expect = "DATA".toByteArray(Charsets.UTF_8) + le32(2) + byteArrayOf(9, 8)
        assertArrayEquals(expect, AdbProtocol.buildDataChunk(buffer, 2))
    }

    @Test
    fun `DATA 帧默认取全量负载`() {
        val payload = byteArrayOf(1, 2, 3)
        assertArrayEquals(frame("DATA", payload), AdbProtocol.buildDataChunk(payload))
    }

    @Test
    fun `DONE 帧负载为 LE 时间戳`() {
        val expect = "DONE".toByteArray(Charsets.UTF_8) + le32(0x11223344)
        assertArrayEquals(expect, AdbProtocol.buildDoneCommand(0x11223344))
    }

    // ── 组帧器：完整帧 ──

    @Test
    fun `单条完整 DATA 帧一次 feed 即产出`() {
        val a = AdbProtocol.SyncFrameAssembler()
        val frames = a.feed(frame("DATA", byteArrayOf(1, 2, 3)))
        assertEquals(1, frames.size)
        assertArrayEquals(byteArrayOf(1, 2, 3), (frames[0] as AdbProtocol.SyncFrame.Data).bytes)
    }

    @Test
    fun `一条 feed 内多条 DATA 帧按序产出`() {
        val a = AdbProtocol.SyncFrameAssembler()
        val frames = a.feed(frame("DATA", byteArrayOf(1)) + frame("DATA", byteArrayOf(2, 3)))
        assertEquals(2, frames.size)
        assertArrayEquals(byteArrayOf(1), (frames[0] as AdbProtocol.SyncFrame.Data).bytes)
        assertArrayEquals(byteArrayOf(2, 3), (frames[1] as AdbProtocol.SyncFrame.Data).bytes)
    }

    // ── 组帧器：跨 feed 半帧（历史 bug 回归） ──

    @Test
    fun `包头与负载分两次到达时不丢负载`() {
        // adbd 实际行为：DATA 包头与文件负载分两次 write，各成一个传输层分片
        val whole = frame("DATA", byteArrayOf(1, 2, 3, 4, 5))
        val a = AdbProtocol.SyncFrameAssembler()
        assertTrue(a.feed(whole.copyOfRange(0, 12)).isEmpty()) // "DATA" + LE(5) + 前 3 字节负载
        val frames = a.feed(whole.copyOfRange(12, whole.size))
        assertEquals(1, frames.size)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), (frames[0] as AdbProtocol.SyncFrame.Data).bytes)
    }

    @Test
    fun `负载拆三次到达时拼齐后才产出`() {
        val payload = ByteArray(100) { it.toByte() }
        val whole = frame("DATA", payload)
        val a = AdbProtocol.SyncFrameAssembler()
        assertTrue(a.feed(whole.copyOfRange(0, 10)).isEmpty())
        assertTrue(a.feed(whole.copyOfRange(10, 50)).isEmpty())
        val frames = a.feed(whole.copyOfRange(50, whole.size))
        assertArrayEquals(payload, (frames.single() as AdbProtocol.SyncFrame.Data).bytes)
    }

    @Test
    fun `FAIL 文案未到齐时不产出到齐后带完整消息`() {
        val a = AdbProtocol.SyncFrameAssembler()
        val partial = "FAIL".toByteArray(Charsets.UTF_8) + le32(6) + "abc".toByteArray(Charsets.UTF_8)
        assertTrue(a.feed(partial).isEmpty())
        val frames = a.feed("def".toByteArray(Charsets.UTF_8))
        assertEquals("abcdef", (frames.single() as AdbProtocol.SyncFrame.Fail).message)
    }

    @Test
    fun `零长度 FAIL 降级为 Unknown error`() {
        val a = AdbProtocol.SyncFrameAssembler()
        val frames = a.feed("FAIL".toByteArray(Charsets.UTF_8) + le32(0))
        assertEquals("Unknown error", (frames.single() as AdbProtocol.SyncFrame.Fail).message)
    }

    @Test
    fun `零长度 DATA 被消费不产出帧`() {
        val a = AdbProtocol.SyncFrameAssembler()
        val frames = a.feed("DATA".toByteArray(Charsets.UTF_8) + le32(0) + frame("DATA", byteArrayOf(7)))
        assertEquals(1, frames.size)
        assertArrayEquals(byteArrayOf(7), (frames[0] as AdbProtocol.SyncFrame.Data).bytes)
    }

    @Test
    fun `DONE 帧终止组帧`() {
        val a = AdbProtocol.SyncFrameAssembler()
        val frames = a.feed(frame("DATA", byteArrayOf(1)) + "DONE".toByteArray(Charsets.UTF_8) + le32(0))
        assertEquals(2, frames.size)
        assertTrue(frames[1] is AdbProtocol.SyncFrame.Done)
    }

    @Test
    fun `未知帧名抛出与原实现逐字一致的异常`() {
        val a = AdbProtocol.SyncFrameAssembler()
        try {
            a.feed("XYZW".toByteArray(Charsets.UTF_8) + le32(0))
            fail("应当抛出 IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertEquals("Unexpected sync frame: XYZW", e.message)
        }
    }

    @Test
    fun `大负载跨多次 feed 触发缓冲扩容不丢字节`() {
        // > 初始缓冲（64KB+64）的负载，拆成多个传输层分片
        val payload = ByteArray(200 * 1024) { (it % 251).toByte() }
        val whole = frame("DATA", payload)
        val a = AdbProtocol.SyncFrameAssembler()
        var collected = emptyList<AdbProtocol.SyncFrame>()
        var off = 0
        while (off < whole.size) {
            val end = minOf(off + 7777, whole.size) // 非整除的分片大小
            collected += a.feed(whole.copyOfRange(off, end))
            off = end
        }
        val data = collected.filterIsInstance<AdbProtocol.SyncFrame.Data>().single()
        assertArrayEquals(payload, data.bytes)
    }

    @Test
    fun `组帧后残留半帧留待下一次 feed 不互相干扰`() {
        val a = AdbProtocol.SyncFrameAssembler()
        val d1 = frame("DATA", byteArrayOf(1))
        val d2 = frame("DATA", byteArrayOf(2))
        val mixed = d1 + d2.copyOfRange(0, 5) // 第一条完整 + 第二条半截
        val first = a.feed(mixed)
        assertEquals(1, first.size)
        val second = a.feed(d2.copyOfRange(5, d2.size))
        assertArrayEquals(byteArrayOf(2), (second.single() as AdbProtocol.SyncFrame.Data).bytes)
    }

    // ── 存储大小解析 ──

    @Test
    fun `纯数字按 1K-blocks 换算`() {
        assertEquals(123L * 1024, AdbProtocol.parseStorageSize("123"))
    }

    @Test
    fun `带单位的大小按二进制乘数换算`() {
        assertEquals(50L * 1024, AdbProtocol.parseStorageSize("50K"))
        assertEquals(100L * 1024 * 1024, AdbProtocol.parseStorageSize("100M"))
        assertEquals((2.3 * 1024 * 1024 * 1024).toLong(), AdbProtocol.parseStorageSize("2.3G"))
        assertEquals(15L * 1024 * 1024 * 1024, AdbProtocol.parseStorageSize("15G"))
    }

    @Test
    fun `无单位的小数默认按 KB 处理`() {
        assertEquals((1.5 * 1024).toLong(), AdbProtocol.parseStorageSize("1.5"))
    }

    @Test
    fun `空白与无法解析的输入返回 0`() {
        assertEquals(0L, AdbProtocol.parseStorageSize(""))
        assertEquals(0L, AdbProtocol.parseStorageSize("   "))
        assertEquals(0L, AdbProtocol.parseStorageSize("abc"))
    }

    // ── 校验和 ──

    @Test
    fun `校验和为逐字节无符号求和`() {
        assertEquals(0, AdbProtocol.adbChecksum(null))
        assertEquals(0, AdbProtocol.adbChecksum(ByteArray(0)))
        assertEquals(255, AdbProtocol.adbChecksum(byteArrayOf(0xFF.toByte())))
        assertEquals(1 + 2 + 255, AdbProtocol.adbChecksum(byteArrayOf(1, 2, 0xFF.toByte())))
    }
}
