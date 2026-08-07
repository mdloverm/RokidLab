package com.rokidlab.rokidlink

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.util.Log
import java.io.DataInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 蓝牙隧道服务端（眼镜端）
 *
 * 监听 RFCOMM 连接，读取手机端发来的目标端口号，
 * 将数据透传到眼镜本地对应服务。
 *
 * 数据流：
 *   手机 BtTunnelClient → RFCOMM(先发targetPort) → BtTunnelServer → TCP 127.0.0.1:targetPort
 *
 * 必须与手机端 ConnectionRouteManager.TUNNEL_UUID 保持一致。
 */
class BtTunnelServer {
    companion object {
        private const val TAG = "BtTunnelServer"

        /** SPP 串口标准 UUID，必须与手机端一致 */
        val TUNNEL_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        private const val TARGET_HOST = "127.0.0.1"
        private const val IO_BUF = 8192
    }

    @Volatile
    var isRunning = false
        private set

    private var serverSocket: BluetoothServerSocket? = null
    private var acceptThread: Thread? = null

    /** 启动蓝牙隧道服务端 */
    fun start(adapter: BluetoothAdapter): Boolean {
        if (isRunning) return true
        return try {
            serverSocket = adapter.listenUsingRfcommWithServiceRecord("RokidLink Tunnel", TUNNEL_UUID)
            isRunning = true
            Log.i(TAG, "BT tunnel server listening")

            acceptThread = Thread {
                while (isRunning) {
                    try {
                        val socket = serverSocket?.accept() ?: break
                        Log.i(TAG, "Tunnel client connected")
                        handleConnection(socket)
                    } catch (e: IOException) {
                        if (isRunning) Log.e(TAG, "Accept error: ${e.message}")
                    }
                }
            }.apply { name = "bt-tunnel-server"; start() }

            true
        } catch (e: IOException) {
            Log.e(TAG, "Start failed: ${e.message}")
            false
        }
    }

    /**
     * 处理一个隧道连接。
     * 1. 读取 4 字节目标端口号
     * 2. 连接到本地 127.0.0.1:targetPort
     * 3. 蓝牙 ↔ 本地 TCP 双向透传
     */
    private fun handleConnection(btSocket: BluetoothSocket) {
        var targetSocket: Socket? = null
        try {
            // 隧道握手：读取目标端口号
            val btIn = DataInputStream(btSocket.inputStream)
            val targetPort = btIn.readInt()
            Log.i(TAG, "Target port: $targetPort")

            targetSocket = Socket()
            targetSocket.connect(InetSocketAddress(TARGET_HOST, targetPort), 3000)
            Log.i(TAG, "Connected to local :$targetPort")

            val btOut = btSocket.outputStream
            val targetIn = targetSocket.getInputStream()
            val targetOut = targetSocket.getOutputStream()

            val done = AtomicBoolean(false)

            // 蓝牙 → 本地服务
            val t1 = Thread {
                try {
                    val buf = ByteArray(IO_BUF)
                    while (!done.get()) {
                        val n = btIn.read(buf)
                        if (n == -1) break
                        targetOut.write(buf, 0, n)
                        targetOut.flush()
                    }
                } catch (_: IOException) {}
                done.set(true)
                try { targetSocket.close() } catch (_: Exception) {}
            }

            // 本地服务 → 蓝牙
            val t2 = Thread {
                try {
                    val buf = ByteArray(IO_BUF)
                    while (!done.get()) {
                        val n = targetIn.read(buf)
                        if (n == -1) break
                        btOut.write(buf, 0, n)
                        btOut.flush()
                    }
                } catch (_: IOException) {}
                done.set(true)
                try { btSocket.close() } catch (_: Exception) {}
            }

            t1.start()
            t2.start()
            t1.join()
            t2.join(2000)
        } catch (e: Exception) {
            Log.e(TAG, "Connection failed: ${e.message}")
        } finally {
            try { btSocket.close() } catch (_: Exception) {}
            try { targetSocket?.close() } catch (_: Exception) {}
            Log.i(TAG, "Tunnel connection closed")
        }
    }

    fun stop() {
        isRunning = false
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        acceptThread?.interrupt()
        acceptThread = null
        Log.i(TAG, "BT tunnel server stopped")
    }
}
