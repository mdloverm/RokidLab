package com.rokidlab.phone.adb

import com.rokidlab.phone.util.LogCollector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * `AdbShellClient.pullFile` 的 sync 子流协议回归测试。
 *
 * 这段代码历史上出过 A4 事故：远端回 `FAIL`（文件不存在 / 无权限）时被**静默当成成功**，
 * 调用方拿到一个 0 字节文件还以为是拉取成功。当时没有任何测试覆盖 —— 只能真机复现。
 * 现在生产代码提供 `attachStreamsForTest` 注入口，这里用脚本化对端把每条分支钉死。
 *
 * 对端脚本必须**整条流写进同一个 segment**：`ScriptedPeer` 在段与段之间抛
 * `SocketTimeoutException`，而 pullFile 的数据循环不吞读超时（直接冒泡到 catch），
 * 只有整段连续喂字节才能模拟真实的「一次连上、连续到达」。
 */
class AdbSyncProtocolTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var written: ByteArrayOutputStream

    @Before
    fun setUp() {
        LogCollector.clear()
        written = ByteArrayOutputStream()
    }

    private fun clientFor(script: ByteArray): AdbShellClient =
        AdbShellClient(null, "127.0.0.1").also {
            it.attachStreamsForTest(ScriptedPeer(listOf(script)), written)
        }

    private fun newTarget(): String = File(tmp.newFolder(), "out.txt").absolutePath

    private fun packet(command: Int, arg0: Int, arg1: Int, payload: ByteArray = ByteArray(0)) =
        AdbTestProto.packet(command, arg0, arg1, payload)

    /** 对端方向的数据帧 */
    private fun deviceFrame(id: String, data: ByteArray = ByteArray(0)) =
        packet(AdbTestProto.CMD_WRTE, REMOTE_ID, LOCAL_ID, AdbTestProto.syncFrame(id, data))

    private fun sent(): List<AdbTestProto.Written> = AdbTestProto.parseWritten(written.toByteArray())

    /** sync OPEN 应答 + RECV 应答（两次 OKAY），再拼上后续帧 —— 整段连续喂给客户端 */
    private fun syncOpen(vararg frames: ByteArray): ByteArray {
        val okays = packet(AdbTestProto.CMD_OKAY, REMOTE_ID, LOCAL_ID) +
            packet(AdbTestProto.CMD_OKAY, REMOTE_ID, LOCAL_ID)
        return frames.fold(okays) { acc, f -> acc + f }
    }

    // ── 成功路径 ──

    @Test
    fun `pullFile 成功路径：DATA 累加写入并回 OKAY，DONE 结束返回 true`() {
        val script = syncOpen(
            deviceFrame("DATA", "hello".toByteArray()),
            deviceFrame("DATA", " world".toByteArray()),
            deviceFrame("DONE"),
        )
        val target = File(newTarget())

        val ok = clientFor(script).pullFile("/sdcard/a.txt", target.absolutePath, 11L)

        assertTrue("DONE 收尾且字节数对得上应判定成功", ok)
        assertEquals("hello world", target.readText())

        val msgs = sent()
        assertEquals("首个报文必须是 OPEN", AdbTestProto.CMD_OPEN, msgs.first().command)
        assertEquals(
            "OPEN 负载必须是 sync 服务名",
            "sync:\u0000",
            String(msgs.first().payload, Charsets.UTF_8),
        )
        assertEquals("RECV 帧必须是路径拉取命令", "RECV", AdbTestProto.syncFrameId(msgs[1].payload))
        // 2 个 DATA + 1 个 DONE，逐帧回 OKAY
        assertEquals("每个数据帧都必须回 OKAY 流控", 3, msgs.count { it.command == AdbTestProto.CMD_OKAY })
        assertEquals("收尾必须发 CLSE", AdbTestProto.CMD_CLSE, msgs.last().command)
    }

    @Test
    fun `pullFile 大文件多分片：逐片写入直到 DONE`() {
        // 真实形态：adbd 每片 64KB，靠 DONE 收尾。防「只写了第一片就退出」这类回归
        val chunk = ByteArray(64 * 1024) { (it % 251).toByte() }
        val script = syncOpen(
            deviceFrame("DATA", chunk),
            deviceFrame("DATA", chunk),
            deviceFrame("DONE"),
        )
        val target = File(newTarget())

        val ok = clientFor(script).pullFile("/sdcard/big.apk", target.absolutePath, chunk.size * 2L)

        assertTrue(ok)
        assertEquals(chunk.size * 2L, target.length())
    }

    @Test
    fun `pullFile 收满预期字节后对端直接收流（无 DONE）：按字节数对账判成功`() {
        // 分片拉取的常态：adbd 送完这一片就 CLSE，不再发 DONE（见 pullFileSharded 注释）。
        // 只要收到的字节数正好等于该片应有字节数，就必须判成功，否则分片拉取永远无法通过。
        val chunk = ByteArray(64 * 1024) { (it % 251).toByte() }
        val script = syncOpen(
            deviceFrame("DATA", chunk),
            packet(AdbTestProto.CMD_CLSE, REMOTE_ID, LOCAL_ID),
        )
        val target = File(newTarget())

        val ok = clientFor(script).pullFile("/sdcard/shard_aa", target.absolutePath, chunk.size.toLong())

        assertTrue("字节数对得上就该判成功（对端可能省掉 DONE）", ok)
        assertTrue("内容必须逐字节完整", target.readBytes().contentEquals(chunk))
    }

    @Test
    fun `pullFileSharded 小文件（不超过单片）直接走普通 RECV`() {
        val script = syncOpen(deviceFrame("DATA", "small".toByteArray()), deviceFrame("DONE"))
        val target = File(newTarget())

        assertTrue(clientFor(script).pullFileSharded("/sdcard/a.txt", target.absolutePath, 5L))
        assertEquals("small", target.readText())
    }

    @Test
    fun `pullFileSharded 远端大小未知：直接判失败，不做无对账的拉取`() {
        val target = File(newTarget())

        assertFalse(clientFor(ByteArray(0)).pullFileSharded("/sdcard/a.txt", target.absolutePath, -1L))
        assertFalse("不得产出任何文件", target.exists())
    }

    // ── 残包回归锁（2026-09-13：提取功能产出 64KB 残包却报成功）──

    @Test
    fun `pullFile 未收到 DONE 就断流：判失败并删除残包`() {
        // 对端送回一个 64KB 分片后直接 CLSE —— 蓝牙隧道下的真实形态
        val chunk = ByteArray(64 * 1024)
        val script = syncOpen(
            deviceFrame("DATA", chunk),
            packet(AdbTestProto.CMD_CLSE, REMOTE_ID, LOCAL_ID),
        )
        val target = File(newTarget())

        val ok = clientFor(script).pullFile("/sdcard/big.apk", target.absolutePath, 8_753_886L)

        assertFalse("没有 DONE 就结束了，绝不能当成功", ok)
        assertFalse("半成品必须删除，否则下游会当成有效产物", target.exists())
    }

    @Test
    fun `pullFile 收到 DONE 但字节数少于远端：判失败并删除残包`() {
        val script = syncOpen(deviceFrame("DATA", "only-64kb".toByteArray()), deviceFrame("DONE"))
        val target = File(newTarget())

        val ok = clientFor(script).pullFile("/sdcard/big.apk", target.absolutePath, 8_753_886L)

        assertFalse("字节数对不上必须判失败（DONE 也可能是对端提前放弃）", ok)
        assertFalse(target.exists())
    }

    @Test
    fun `pullFile 收到畸形帧：判失败并删除残包`() {
        val script = syncOpen(
            deviceFrame("DATA", "abc".toByteArray()),
            packet(AdbTestProto.CMD_WRTE, REMOTE_ID, LOCAL_ID, ByteArray(3)),
        )
        val target = File(newTarget())

        assertFalse(clientFor(script).pullFile("/sdcard/a.txt", target.absolutePath, 3L))
        assertFalse("畸形帧同样不能留下半成品", target.exists())
    }

    // ── FAIL 分支（A4 事故回归锁）──

    @Test
    fun `pullFile 收到 FAIL：删除半成品文件并返回 false`() {
        val script = syncOpen(deviceFrame("FAIL", "permission denied".toByteArray()))
        val target = File(newTarget())

        val ok = clientFor(script).pullFile("/data/secret.txt", target.absolutePath)

        assertFalse("FAIL 必须如实返回失败，绝不能当成功", ok)
        assertFalse("失败时必须删除已创建的空文件，避免被下游当成 0 字节的‘成功产物’", target.exists())
    }

    @Test
    fun `pullFile FAIL 前已收到的数据同样被丢弃`() {
        // 远端先发了一段数据再报错（读权限中途失效等）：半成品绝不能留在磁盘上
        val script = syncOpen(
            deviceFrame("DATA", "partial-".toByteArray()),
            deviceFrame("FAIL", "read error".toByteArray()),
        )
        val target = File(newTarget())

        assertFalse(clientFor(script).pullFile("/sdcard/a.txt", target.absolutePath))
        assertFalse(target.exists())
    }

    @Test
    fun `pullFile 收到 FAIL 后仍回 OKAY 并关闭流`() {
        val script = syncOpen(deviceFrame("FAIL", "no such file".toByteArray()))

        clientFor(script).pullFile("/sdcard/missing.txt", newTarget())

        val msgs = sent()
        assertEquals("FAIL 帧也要回 OKAY（不阻塞对端）", 1, msgs.count { it.command == AdbTestProto.CMD_OKAY })
        assertEquals("必须关闭 sync 流", AdbTestProto.CMD_CLSE, msgs.last().command)
        assertEquals(LOCAL_ID, msgs.last().arg0)
        assertEquals(REMOTE_ID, msgs.last().arg1)
    }

    // ── 建流失败分支 ──

    @Test
    fun `pullFile OPEN 被对端 CLSE 拒绝：直接失败且不发多余报文`() {
        val script = packet(AdbTestProto.CMD_CLSE, 0, LOCAL_ID)
        val targetPath = newTarget()

        assertFalse(clientFor(script).pullFile("/sdcard/a.txt", targetPath))
        assertEquals("建流被拒后不应继续发 RECV/CLSE", 1, sent().size)
        assertFalse(File(targetPath).exists())
    }

    @Test
    fun `pullFile RECV 后未收到 OKAY：关闭流并返回 false`() {
        val script = packet(AdbTestProto.CMD_OKAY, REMOTE_ID, LOCAL_ID) +
            packet(AdbTestProto.CMD_CLSE, REMOTE_ID, LOCAL_ID)
        val targetPath = newTarget()

        assertFalse(clientFor(script).pullFile("/sdcard/a.txt", targetPath))
        val msgs = sent()
        assertEquals(AdbTestProto.CMD_OPEN, msgs[0].command)
        assertEquals("RECV", AdbTestProto.syncFrameId(msgs[1].payload))
        assertEquals("RECV 被拒时必须关闭流", AdbTestProto.CMD_CLSE, msgs.last().command)
    }

    @Test
    fun `pullFile 数据循环中零字节就收到 CLSE：判失败且不留 0 字节文件`() {
        // 契约（2026-09-13 起）：对端不送 DONE / FAIL 直接 CLSE 一律视为失败。
        // 旧行为是「按正常结束返回 true」，与 AdbFileManagerClient.downloadFile 的 false 不一致，
        // 且正是提取功能产出 64KB 残包还报成功的根源（原登记待评估项，现已按 false 收敛）。
        val script = syncOpen(packet(AdbTestProto.CMD_CLSE, REMOTE_ID, LOCAL_ID))
        val targetPath = newTarget()

        assertFalse(clientFor(script).pullFile("/sdcard/a.txt", targetPath))
        assertFalse("半成品（此处为 0 字节文件）必须删除", File(targetPath).exists())
    }

    // ── 异常链路：必须落 LogCollector（RULES §12.14）──

    @Test
    fun `pullFile 读流异常：返回 false 且落 LogCollector（不再静默）`() {
        // 半个报文头后流就断了：readMessage 抛 EOFException，必须进 catch 并被记录
        val targetPath = newTarget()

        val ok = clientFor(ByteArray(5)).pullFile("/sdcard/a.txt", targetPath)

        assertFalse(ok)
        assertTrue(
            "关键链路异常必须落 LogCollector，否则 App 内日志面板什么都看不到",
            LogCollector.getErrorLogText().contains(TAG),
        )
    }

    private companion object {
        const val LOCAL_ID = 1
        const val REMOTE_ID = 42
        const val TAG = "AdbShellClient"
    }
}
