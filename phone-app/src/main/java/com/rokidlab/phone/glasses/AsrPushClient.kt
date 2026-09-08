package com.rokidlab.phone.glasses

import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import com.rokidlab.phone.connection.selectActiveGlasses
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
        /** 连接建立失败重试最长退避：眼镜不可达时避免每 3s 盲重连冲击蓝牙协议栈 */
        private const val BACKOFF_MAX_MS = 30_000L
        private const val MAX_FRAME = 65536
    }

    @Volatile
    private var running = false

    /** 当前活动连接：stop() 需关闭它以解除 readFully/readInt 阻塞（仅 interrupt 无法中断 IO 读） */
    @Volatile
    private var socket: BluetoothSocket? = null

    /** 推送通道是否已连接（用于决定是否跳过 ADB 文件轮询兜底，减少蓝牙隧道占用） */
    val isConnected: Boolean get() = socket != null

    private var thread: Thread? = null
    private var lastErrorLogAt = 0L

    fun start() {
        if (running) return
        running = true
        thread = Thread {
            // 连续「连接建立失败」次数：眼镜不可达/蓝牙栈忙时指数退避，
            // 避免每 3s 盲重连冲击蓝牙协议栈（加重隧道不稳定）。
            var connectFailures = 0
            while (running) {
                var established = false
                try {
                    // 优先 A2DP 当前活跃连接的眼镜，避免多台绑定（含残留旧眼镜）时选错设备
                    val glasses = selectActiveGlasses(context)
                        ?: throw Exception("no glasses device")
                    val s = glasses.createRfcommSocketToServiceRecord(PUSH_UUID)
                    socket = s
                    s.connect()
                    established = true
                    connectFailures = 0
                    Log.i(TAG, "ASR push connected")
                    val input = DataInputStream(s.inputStream)
                    while (running) {
                        val len = input.readInt()
                        if (len <= 0 || len > MAX_FRAME) {
                            // 长度头非法 = 流已失步，无法恢复同步；断开走重连
                            throw Exception("invalid frame length: $len")
                        }
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
                    socket = null
                }
                if (!running) break
                // 已建立后中途断开（短暂掉线）保持 3s 快速重连，尽早恢复推送；
                // 只有连接一直建立不起来（对端不可达）才指数退避到最长 30s。
                if (!established) connectFailures++
                val backoffSteps = minOf((connectFailures - 1).coerceAtLeast(0), 3)
                val delay = (RECONNECT_DELAY_MS shl backoffSteps).coerceAtMost(BACKOFF_MAX_MS)
                try {
                    Thread.sleep(delay)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }.apply { name = "asr-push-client"; start() }
    }

    fun stop() {
        running = false
        // 关闭 socket 解除读线程阻塞，避免占住眼镜端单客户端串行 accept
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        thread?.interrupt()
        thread?.join(2000)
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
