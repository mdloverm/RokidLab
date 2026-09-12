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

        val ok = clientFor(script).pullFile("/sdcard/a.txt", target.absolutePath)

        assertTrue("DONE 收尾应判定成功", ok)
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
    fun `pullFile 数据循环中收到 CLSE 提前结束`() {
        // 行为记录（非契约）：对端不发 DONE / FAIL 直接 CLSE 时，当前按「正常结束」返回 true，
        // 字节数为已到达的部分。与 AdbFileManagerClient.downloadFile 的「CLSE → false」不一致，
        // 已登记为待评估项（见 ENGINEERING_ASSESSMENT §P1-11）。
        val script = syncOpen(packet(AdbTestProto.CMD_CLSE, REMOTE_ID, LOCAL_ID))
        val targetPath = newTarget()

        assertTrue(clientFor(script).pullFile("/sdcard/a.txt", targetPath))
        assertEquals(0L, File(targetPath).length())
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
