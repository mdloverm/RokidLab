package com.rokidlab.rokidlink

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.util.Log
import java.util.UUID

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

    @Volatile
    private var running = false

    @Volatile
    private var clientSocket: BluetoothSocket? = null

    private var serverSocket: BluetoothServerSocket? = null
    private var acceptThread: Thread? = null

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
                        clientSocket = s
                        // 阻塞读直到连接断开（手机端断线/蓝牙断开时 read 抛异常返回）
                        try {
                            val buf = ByteArray(256)
                            while (running && s.inputStream.read(buf) != -1) {
                                // 手机端不应向此通道写数据，读到即丢弃
                            }
                        } catch (_: Exception) {
                        }
                        if (clientSocket === s) clientSocket = null
                        try { s.close() } catch (_: Exception) {}
                        Log.i(TAG, "ASR push client disconnected, awaiting reconnect")
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
     * 推送一条 ASR 文字。返回 true 表示已发送（客户端连接存在）。
     * 连接未建立/已断开时返回 false，调用方应兜底走文件通道。
     */
    fun push(text: String): Boolean {
        val s = clientSocket ?: return false
        return try {
            val bytes = text.toByteArray(Charsets.UTF_8)
            val out = s.outputStream
            out.write(bytes.size shr 24)
            out.write(bytes.size shr 16 and 0xFF)
            out.write(bytes.size shr 8 and 0xFF)
            out.write(bytes.size and 0xFF)
            out.write(bytes)
            out.flush()
            true
        } catch (e: Exception) {
            Log.e(TAG, "ASR push write failed: ${e.message}")
            false
        }
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
