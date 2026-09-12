package com.rokidlab.rokidlink

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.util.Log
import java.util.UUID
import java.util.concurrent.Executors

/**
 * ASR 文字推送服务端（眼镜端，单例）
 *
 * 独立于 ADB 隧道（BtTunnelServer）的第二条 RFCOMM 长连接通道：
 * - 通道 A（SPP 隧道）：手机端短连接走 ADB，工具/投屏等照常
 * - 通道 B（本服务）：眼镜端 hook 到 ASR_End 文字后毫秒级实时推送给手机端
 *
 * 协议：4 字节大端长度 + UTF-8 数据（与手机端 AsrPushClient 保持一致）。
 * 单客户端长连接：手机端断开后继续 accept 等待重连。
 */
object AsrPushServer {
    private const val TAG = "AsrPushServer"

    /** 第二 RFCOMM 通道 UUID（与手机端 AsrPushClient 一致） */
    val PUSH_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34F9")

    /** 协议帧上限（与手机端 AsrPushClient.MAX_FRAME 一致） */
    private const val MAX_FRAME = 65536

    /** 拍照答题控制指令：经本 RFCOMM 通道上行到手机端（独立于 AI App 网关，可区分按键意图） */
    const val CTRL_PHOTO_ASK = LinkProtocol.MARKER_PHOTO_ASK

    /**
     * AIUI 页面工具调用上行：本标记 + JSON 载荷（{cbId,name,args}）。
     * 复用本 RFCOMM 通道而非新建 —— 眼镜端同一时刻只允许一条 RFCOMM
     * （adb 常驻隧道已占满），新开通道必然 "BT RFCOMM connect failed"。
     */
    const val CTRL_TOOL_CALL = LinkProtocol.MARKER_TOOL_CALL

    /** ASR 识别完成指令：官方 AI 识别完成下发 ASR_End 时，先于文字推送此信号，
     *  手机端收到后才打断官方 AI 会话（保证官方先完成识别、ASR_End 必然产生，
     *  消除「识别前打断导致无文字」竞态） */
    const val CTRL_ASR_READY = LinkProtocol.MARKER_ASR_READY

    @Volatile
    private var running = false

    @Volatile
    private var clientSocket: BluetoothSocket? = null

    private var serverSocket: BluetoothServerSocket? = null
    private var acceptThread: Thread? = null

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

    /** 启动推送服务端（长连接 accept 循环） */
    fun start(adapter: BluetoothAdapter): Boolean {
        if (running) return true
        return try {
            serverSocket = adapter.listenUsingRfcommWithServiceRecord("RokidLink AsrPush", PUSH_UUID)
            running = true
            Log.i(TAG, "ASR push server listening")

            acceptThread = Thread {
                while (running) {
                    try {
                        val s = serverSocket?.accept() ?: break
                        Log.i(TAG, "ASR push client connected")
                        // 替换式单客户端：新连接到来时立即关闭旧连接并替换，
                        // 避免手机端进程被杀后残留连接阻塞 accept 读循环，
                        // 导致新连接在蓝牙协议栈排队超时（手机端 3s 读超时）。
                        synchronized(pushLock) {
                            clientSocket?.takeIf { it !== s }?.let { old ->
                                try { old.close() } catch (_: Exception) {}
                            }
                            clientSocket = s
                        }
                        // 连接读循环放独立线程，accept 循环立即回到 accept，不被死连接阻塞
                        Thread {
                            try {
                                val buf = ByteArray(256)
                                while (running && s.inputStream.read(buf) != -1) {
                                    // 手机端不应向此通道写数据，读到即丢弃
                                }
                            } catch (_: Exception) {
                            }
                            synchronized(pushLock) {
                                if (clientSocket === s) clientSocket = null
                            }
                            try { s.close() } catch (_: Exception) {}
                            Log.i(TAG, "ASR push client disconnected, awaiting reconnect")
                        }.apply { name = "asr-push-client-handler"; isDaemon = true; start() }
                    } catch (e: Exception) {
                        if (running) {
                            Log.e(TAG, "ASR push accept error: ${e.message}")
                            try { Thread.sleep(1000) } catch (_: InterruptedException) { break }
                        }
                    }
                }
            }.apply { name = "asr-push-server"; start() }
            true
        } catch (e: Exception) {
            Log.e(TAG, "ASR push server start failed: ${e.message}")
            false
        }
    }

    /**
     * 推送一条控制指令（如拍照答题意图），与 ASR 文字共用同一 RFCOMM 帧协议。
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
        val s = clientSocket ?: return false
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
            if (clientSocket !== s) return false
            pendingFrames++
        }
        // 提交到独立写线程：绝不在 CXR 回调线程同步 write（半开 socket 无超时会永久卡死回调线程）
        writerExecutor.execute {
            try {
                synchronized(pushLock) {
                    if (clientSocket !== s) return@execute
                    // 长度头 + payload 拼装为单帧一次写入：分段 write 在并发下帧会交错破坏协议
                    val frame = ByteArray(4 + bytes.size)
                    frame[0] = (bytes.size shr 24).toByte()
                    frame[1] = (bytes.size shr 16 and 0xFF).toByte()
                    frame[2] = (bytes.size shr 8 and 0xFF).toByte()
                    frame[3] = (bytes.size and 0xFF).toByte()
                    System.arraycopy(bytes, 0, frame, 4, bytes.size)
                    s.outputStream.write(frame)
                    s.outputStream.flush()
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
        try { clientSocket?.close() } catch (_: Exception) {}
        clientSocket = null
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        acceptThread?.interrupt()
        acceptThread = null
        Log.i(TAG, "ASR push server stopped")
    }
}
