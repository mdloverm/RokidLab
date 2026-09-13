package com.rokidlab.rokidlink

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.util.Log
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.Executors

/**
 * ASR 文字推送服务端（眼镜端，单例）
 *
 * 独立于 ADB 隧道（BtTunnelServer）的第二条长连接通道：
 * - 通道 A（SPP 隧道）：手机端短连接走 ADB，工具/投屏等照常
 * - 通道 B（本服务）：眼镜端 hook 到 ASR_End 文字后毫秒级实时推送给手机端
 *
 * **双通道（WiFi 优先，蓝牙兜底）**：手机端 [AsrPushClient] 会先尝试 WiFi 直连本服务的
 * TCP 监听（[WIFI_PORT]），连不上才回落 RFCOMM。两条通道共用同一套帧协议，
 * 对本服务而言只是「接入的连接对象不同」，推送逻辑完全一致 —— 因此内部收敛为
 * [attachClient]，避免两套 accept/清理逻辑各自演化出不一致的边界处理。
 *
 * 协议：4 字节大端长度 + UTF-8 数据（与手机端 AsrPushClient 保持一致）。
 * 单客户端长连接：手机端断开后继续 accept 等待重连（新连接替换旧连接）。
 */
object AsrPushServer {
    private const val TAG = "AsrPushServer"

    /** 第二 RFCOMM 通道 UUID（与手机端 AsrPushClient 一致） */
    val PUSH_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34F9")

    /**
     * WiFi 直连监听端口（与手机端 AsrPushClient.WIFI_PORT 一致）。
     *
     * 选 7660 而非复用 7656/7658：那些端口各有归属的本地服务，本通道是独立长连接，
     * 混用会让「谁在监听」变得不可推理。绑 0.0.0.0，手机与眼镜同网段即可直连；
     * 正因绑全网卡，接入前必须校验 [LinkProtocol.ASR_PUSH_HANDSHAKE]（见 [verifyHandshake]）。
     */
    const val WIFI_PORT = 7660

    /**
     * WiFi 接入握手读取超时。令牌长度固定、正常在毫秒级到达；超时即判不可信连接并关闭。
     * 取 1.5s：与手机端 WiFi 建链超时同量级，异常连接最多拖慢 WiFi accept 线程 1.5s，
     * 且 RFCOMM 监听在独立线程，不受影响。
     */
    private const val HANDSHAKE_TIMEOUT_MS = 1_500

    /** 协议帧上限（与手机端 AsrPushClient.MAX_FRAME 一致） */
    private const val MAX_FRAME = 65536

    /** 拍照答题控制指令：经本通道上行到手机端（独立于 AI App 网关，可区分按键意图） */
    const val CTRL_PHOTO_ASK = LinkProtocol.MARKER_PHOTO_ASK

    /**
     * AIUI 页面工具调用上行：本标记 + JSON 载荷（{cbId,name,args}）。
     * 复用本通道而非新建 —— 眼镜端同一时刻只允许一条 RFCOMM
     * （adb 常驻隧道已占满），新开通道必然 "BT RFCOMM connect failed"。
     */
    const val CTRL_TOOL_CALL = LinkProtocol.MARKER_TOOL_CALL

    /** ASR 识别完成指令：官方 AI 识别完成下发 ASR_End 时，先于文字推送此信号，
     *  手机端收到后才打断官方 AI 会话（保证官方先完成识别、ASR_End 必然产生，
     *  消除「识别前打断导致无文字」竞态） */
    const val CTRL_ASR_READY = LinkProtocol.MARKER_ASR_READY

    @Volatile
    private var running = false

    /** 当前活动客户端的输出流（RFCOMM 或 WiFi TCP，二者共用同一帧协议） */
    @Volatile
    private var clientOut: OutputStream? = null

    /** 当前活动客户端连接本体：stop()/替换时需要关闭它以解除对端读阻塞 */
    @Volatile
    private var clientConn: Closeable? = null

    private var rfcommServerSocket: BluetoothServerSocket? = null
    private var tcpServerSocket: ServerSocket? = null
    private var rfcommAcceptThread: Thread? = null
    private var tcpAcceptThread: Thread? = null

    /** 写锁：push 可能被多个 CXR 订阅回调线程并发调用，帧拼接+写入必须串行化 */
    private val pushLock = Any()

    /**
     * 写线程（单线程）：socket 半开时 outputStream.write 无超时，若在调用线程（CXR 订阅回调）
     * 同步执行会永久卡死回调线程，导致后续 ASR 事件全部丢失（实测"停止播放"即因此被官方接管）。
     * 独立线程即使被卡住，也只影响本写线程，回调线程可继续处理。
     */
    private val writerExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "asr-push-writer").apply { isDaemon = true }
    }

    /** 排队中未写完的帧数：写线程被卡死时防止无限堆积 */
    @Volatile
    private var pendingFrames = 0
    private const val MAX_PENDING_FRAMES = 4

    /**
     * 启动推送服务端：同时开 RFCOMM 与 WiFi TCP 两个监听。
     *
     * 两个监听互不依赖 —— WiFi 未连接时 TCP 监听只是没人连（不影响蓝牙通道），
     * 反过来蓝牙异常也不影响 WiFi 直连。任一方能起来即视为可用。
     */
    fun start(adapter: BluetoothAdapter): Boolean {
        if (running) return true
        // 必须先置 running 再起监听线程：accept 循环的退出条件就是 `!running`，
        // 若在之后才置位，线程在置位前的窗口里会立刻判定「已停止」而退出。
        running = true
        val btOk = startRfcommListener(adapter)
        val wifiOk = startTcpListener()
        Log.i(TAG, "ASR push server started (rfcomm=$btOk, wifi=$wifiOk)")
        // 两条监听都起不来才算失败：复位 running（此时并无 accept 线程在跑），留给下次重试
        if (!btOk && !wifiOk) {
            running = false
            return false
        }
        return true
    }

    /** RFCOMM 监听（蓝牙通道） */
    private fun startRfcommListener(adapter: BluetoothAdapter): Boolean = try {
        val server = adapter.listenUsingRfcommWithServiceRecord("RokidLink AsrPush", PUSH_UUID)
        rfcommServerSocket = server
        rfcommAcceptThread = Thread {
            while (running) {
                try {
                    val s = server.accept() ?: break
                    Log.i(TAG, "ASR push client connected (rfcomm)")
                    attachClient(s, s.inputStream, s.outputStream, "rfcomm")
                } catch (e: Exception) {
                    if (running) {
                        Log.e(TAG, "ASR push rfcomm accept error: ${e.message}")
                        try { Thread.sleep(1000) } catch (_: InterruptedException) { break }
                    }
                }
            }
        }.apply { name = "asr-push-server-rfcomm"; isDaemon = true; start() }
        true
    } catch (e: Exception) {
        Log.e(TAG, "ASR push rfcomm listen failed: ${e.message}")
        false
    }

    /** WiFi TCP 监听（同网段直连通道） */
    private fun startTcpListener(): Boolean = try {
        val server = ServerSocket()
        server.reuseAddress = true
        server.bind(InetSocketAddress(WIFI_PORT))
        tcpServerSocket = server
        tcpAcceptThread = Thread {
            while (running) {
                try {
                    val s = server.accept()
                    Log.i(TAG, "ASR push client connected (wifi ${s.inetAddress?.hostAddress ?: "?"})")
                    // 握手校验：监听绑 0.0.0.0，不校验则同网段任意主机连上 7660 即可
                    // 挤掉真手机的推送连接（AI 回答文字停更）并读取下发的 ASR 文本。
                    // 校验在 WiFi accept 线程内完成，RFCOMM 监听在独立线程，互不阻塞。
                    if (!verifyHandshake(s)) {
                        Log.w(
                            TAG,
                            "ASR push wifi client rejected (bad handshake): ${s.inetAddress?.hostAddress ?: "?"}",
                        )
                        try { s.close() } catch (_: Exception) {} // catch-ok: 拒绝连接，关闭失败无补救
                        continue
                    }
                    // 必须复位读超时：下面的读循环依赖阻塞读，带 soTimeout 会被当作断连
                    s.soTimeout = 0
                    s.tcpNoDelay = true
                    attachClient(s, s.getInputStream(), s.getOutputStream(), "wifi")
                } catch (e: Exception) {
                    if (running) {
                        Log.e(TAG, "ASR push wifi accept error: ${e.message}")
                        try { Thread.sleep(1000) } catch (_: InterruptedException) { break }
                    }
                }
            }
        }.apply { name = "asr-push-server-wifi"; isDaemon = true; start() }
        true
    } catch (e: Exception) {
        Log.w(TAG, "ASR push wifi listen failed (fallback to rfcomm only): ${e.message}")
        false
    }

    /**
     * 校验 WiFi 连接是否携带 [LinkProtocol.ASR_PUSH_HANDSHAKE] 令牌。
     *
     * 令牌长度固定，正常在毫秒级到达；读超时/流结束/内容不符一律判失败，
     * 由调用方关闭连接（该连接不会成为「当前客户端」，因而无法挤掉真手机）。
     * 读取使用 [Socket.getInputStream] 的同一实例，[attachClient] 复用后不会丢字节。
     */
    private fun verifyHandshake(s: java.net.Socket): Boolean = try {
        val expected = LinkProtocol.ASR_PUSH_HANDSHAKE
        s.soTimeout = HANDSHAKE_TIMEOUT_MS
        val buf = ByteArray(expected.size)
        java.io.DataInputStream(s.getInputStream()).readFully(buf)
        buf.contentEquals(expected)
    } catch (_: Exception) {
        false // catch-ok: 握手读取失败（超时/EOF）即视为不可信连接
    }

    /**
     * 接入一个客户端（RFCOMM / WiFi 通用），并启动其断连探测线程。
     *
     * 替换式单客户端：新连接到来时立即关闭旧连接并替换，避免手机端进程被杀后
     * 残留连接阻塞读循环，导致新连接排队超时（手机端 3s 读超时）。
     */
    private fun attachClient(conn: Closeable, input: InputStream, output: OutputStream, label: String) {
        synchronized(pushLock) {
            clientConn?.takeIf { it !== conn }?.let { old -> try { old.close() } catch (_: Exception) {} } // catch-ok: 替换旧连接，关闭失败无补救
            clientConn = conn
            clientOut = output
        }
        // 连接读循环放独立线程，accept 循环立即回到 accept，不被死连接阻塞。
        // WiFi 连接的握手令牌已在 verifyHandshake 中消费；此后本通道只下行，
        // 手机端不应再写数据，读到即丢弃（读返回 -1 即对端断开）。
        Thread {
            try {
                val buf = ByteArray(256)
                while (running && input.read(buf) != -1) {
                    // 丢弃
                }
            } catch (_: Exception) {
                // catch-ok: 对端断开/socket 被替换关闭均为正常退出路径
            }
            synchronized(pushLock) {
                if (clientConn === conn) {
                    clientConn = null
                    clientOut = null
                }
            }
            try { conn.close() } catch (_: Exception) {} // catch-ok: 关闭失败无补救
            Log.i(TAG, "ASR push client disconnected ($label), awaiting reconnect")
        }.apply { name = "asr-push-client-handler"; isDaemon = true; start() }
    }

    /**
     * 推送一条控制指令（如拍照答题意图），与 ASR 文字共用同一帧协议。
     * 返回 true 表示已发送（客户端连接存在）；未连接时返回 false，调用方自行处理兜底。
     */
    fun pushControl(cmd: String): Boolean {
        val sent = push(cmd)
        Log.i(TAG, "pushControl($cmd) -> $sent")
        return sent
    }

    /**
     * 推送一条 ASR 文字。返回 true 表示已发送（客户端连接存在）。
     * 连接未建立/已断开时返回 false，调用方应兜底走文件通道。
     */
    fun push(text: String): Boolean {
        val out = clientOut ?: return false
        if (text.isEmpty()) return false
        val bytes = text.toByteArray(Charsets.UTF_8)
        // 超长拒绝：超过协议帧上限会破坏对端解析（截断还会破坏 UTF-8 边界），交由调用方走文件兜底
        if (bytes.size > MAX_FRAME) {
            Log.w(TAG, "ASR push text too long (${bytes.size} > $MAX_FRAME), dropped")
            return false
        }
        // 防堆积：写线程被卡死（socket 半开）时拒绝继续排队，调用方走文件兜底
        if (pendingFrames >= MAX_PENDING_FRAMES) {
            Log.w(TAG, "ASR push writer busy ($pendingFrames queued), fallback requested")
            return false
        }
        synchronized(pushLock) {
            if (clientOut !== out) return false
            pendingFrames++
        }
        // 提交到独立写线程：绝不在 CXR 回调线程同步 write（半开 socket 无超时会永久卡死回调线程）
        writerExecutor.execute {
            try {
                synchronized(pushLock) {
                    if (clientOut !== out) return@execute
                    // 长度头 + payload 拼装为单帧一次写入：分段 write 在并发下帧会交错破坏协议
                    val frame = ByteArray(4 + bytes.size)
                    frame[0] = (bytes.size shr 24).toByte()
                    frame[1] = (bytes.size shr 16 and 0xFF).toByte()
                    frame[2] = (bytes.size shr 8 and 0xFF).toByte()
                    frame[3] = (bytes.size and 0xFF).toByte()
                    System.arraycopy(bytes, 0, frame, 4, bytes.size)
                    out.write(frame)
                    out.flush()
                }
            } catch (e: Exception) {
                Log.e(TAG, "ASR push write failed: ${e.message}")
            } finally {
                pendingFrames--
            }
        }
        return true
    }

    fun stop() {
        running = false
        try { clientConn?.close() } catch (_: Exception) {} // catch-ok: stop 收尾，关闭失败无补救
        clientConn = null
        clientOut = null
        try { rfcommServerSocket?.close() } catch (_: Exception) {} // catch-ok: stop 收尾，关闭失败无补救
        rfcommServerSocket = null
        try { tcpServerSocket?.close() } catch (_: Exception) {} // catch-ok: stop 收尾，关闭失败无补救
        tcpServerSocket = null
        rfcommAcceptThread?.interrupt()
        rfcommAcceptThread = null
        tcpAcceptThread?.interrupt()
        tcpAcceptThread = null
        Log.i(TAG, "ASR push server stopped")
    }
}
