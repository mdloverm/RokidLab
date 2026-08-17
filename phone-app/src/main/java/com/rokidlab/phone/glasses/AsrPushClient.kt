package com.rokidlab.phone.glasses

import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Log
import java.io.DataInputStream
import java.util.UUID

/**
 * ASR 文字推送客户端（手机端，长连接）
 *
 * 连接眼镜端 AsrPushServer 的第二条 RFCOMM 通道（独立于 ADB 隧道），
 * 实时接收眼镜 hook 到的 ASR 文字。断线自动重连。
 *
 * 协议：4 字节大端长度 + UTF-8 数据（与眼镜端 AsrPushServer 保持一致）。
 */
class AsrPushClient(
    private val context: Context,
    private val onText: (String) -> Unit
) {
    companion object {
        private const val TAG = "AsrPushClient"

        /** 第二 RFCOMM 通道 UUID（与眼镜端 AsrPushServer 一致） */
        val PUSH_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34F9")

        private const val RECONNECT_DELAY_MS = 3000L
        private const val MAX_FRAME = 65536
    }

    @Volatile
    private var running = false

    private var thread: Thread? = null
    private var lastErrorLogAt = 0L

    fun start() {
        if (running) return
        running = true
        thread = Thread {
            while (running) {
                var socket: android.bluetooth.BluetoothSocket? = null
                try {
                    val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
                    val adapter = manager.adapter ?: throw Exception("bluetooth off")
                    val glasses = adapter.bondedDevices.firstOrNull { d ->
                        d.name?.contains("RG") == true ||
                            d.name?.contains("glasses") == true ||
                            d.name?.contains("Glass") == true
                    } ?: throw Exception("no glasses device")
                    socket = glasses.createRfcommSocketToServiceRecord(PUSH_UUID)
                    socket.connect()
                    Log.i(TAG, "ASR push connected")
                    val input = DataInputStream(socket.inputStream)
                    while (running) {
                        val len = input.readInt()
                        if (len <= 0 || len > MAX_FRAME) continue
                        val buf = ByteArray(len)
                        input.readFully(buf)
                        val text = String(buf, Charsets.UTF_8)
                        Log.i(TAG, "ASR push received: $text")
                        try {
                            onText(text)
                        } catch (e: Exception) {
                            Log.e(TAG, "ASR push onText error", e)
                        }
                    }
                } catch (e: Exception) {
                    if (running) logError(e)
                } finally {
                    try { socket?.close() } catch (_: Exception) {}
                }
                if (!running) break
                // 断线重连
                try {
                    Thread.sleep(RECONNECT_DELAY_MS)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }.apply { name = "asr-push-client"; start() }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
    }

    private fun logError(e: Exception) {
        val now = System.currentTimeMillis()
        if (now - lastErrorLogAt > 5000) {
            Log.e(TAG, "ASR push error: ${e.message}")
            lastErrorLogAt = now
        }
    }
}
