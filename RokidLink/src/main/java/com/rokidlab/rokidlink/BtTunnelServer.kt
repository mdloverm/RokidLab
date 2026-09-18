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
        /** 隧道握手（读目标端口）超时：半开连接只 connect 不握手时会永久阻塞单客户端串行 accept */
        private const val HANDSHAKE_TIMEOUT_MS = 10_000
    }

    @Volatile
    var isRunning = false
        private set

    private var serverSocket: BluetoothServerSocket? = null
    private var acceptThread: Thread? = null

    /** 当前活动的透传连接：stop 时需全部关闭以解除读阻塞 */
    private val activeSockets = java.util.concurrent.ConcurrentHashMap.newKeySet<BluetoothSocket>()

    /** 并发透传连接上限：RFCOMM 多通道可共存，限制避免异常客户端无限占满。
     *  手机端同端口并发（共享 ADB 常驻连接 + 文件管理/定时等短连接）与
     *  镜像隧道（7654）叠加时可达 3-5 条，放宽到 6 避免新连接被拒。 */
    private val maxActive = 6

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
                        // 每连接独立线程处理：手机端进程被杀后残留连接不再阻塞 accept，
                        // 避免新连接在蓝牙协议栈排队超时（手机端 connect 8s / 读 3s 超时）。
                        // 手机端 BtTunnelClient 为串行短连接，实际并发度低，RFCOMM 多通道可安全共存。
                        Thread { handleConnection(socket) }.apply { name = "bt-tunnel-handler"; isDaemon = true; start() }
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
        if (activeSockets.size >= maxActive) {
            Log.w(TAG, "Too many active connections (max $maxActive), rejecting")
            try { btSocket.close() } catch (_: Exception) {}
            return
        }
        activeSockets.add(btSocket)
        var targetSocket: Socket? = null
        try {
            // 隧道握手：读取目标端口号。BluetoothSocket 无超时 API，
            // 用独立线程 + join 超时兜底，半开连接不再永久阻塞整个隧道（单客户端串行 accept）。
            val btIn = DataInputStream(btSocket.inputStream)
            val handshakeDone = AtomicBoolean(false)
            var targetPort = 0
            val handshakeThread = namedThread("bt-tunnel-handshake") {
                try {
                    targetPort = btIn.readInt()
                } catch (_: Exception) {
                    // socket 被关闭/中断读，保持 targetPort = 0
                } finally {
                    handshakeDone.set(true)
                }
            }
            handshakeThread.start()
            handshakeThread.join(HANDSHAKE_TIMEOUT_MS.toLong())
            if (!handshakeDone.get()) {
                Log.e(TAG, "Tunnel handshake timeout, closing connection")
                // 关闭 socket 解除 readInt 阻塞后回收握手线程
                runCatching { btSocket.close() }
                handshakeThread.join(500)
                return
            }
            // 端口校验：非法端口（含 0 / 负值 / 越界）直接拒绝，避免连到异常服务
            if (targetPort <= 0 || targetPort > 65535) {
                Log.e(TAG, "Invalid target port: $targetPort")
                return
            }
            Log.i(TAG, "Target port: $targetPort")

            targetSocket = Socket()
            targetSocket.connect(InetSocketAddress(TARGET_HOST, targetPort), 3000)
            Log.i(TAG, "Connected to local :$targetPort")

            val btOut = btSocket.outputStream
            val targetIn = targetSocket.getInputStream()
            val targetOut = targetSocket.getOutputStream()

            val done = AtomicBoolean(false)

            // 蓝牙 → 本地服务
            val t1 = namedThread("bt-tunnel-probe1") {
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
            val t2 = namedThread("bt-tunnel-probe2") {
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
            activeSockets.remove(btSocket)
            try { btSocket.close() } catch (_: Exception) {}
            try { targetSocket?.close() } catch (_: Exception) {}
            Log.i(TAG, "Tunnel connection closed")
        }
    }

    fun stop() {
        isRunning = false
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        // 关闭全部活动连接：stop 后透传线程不再阻塞，立即可退出
        activeSockets.forEach { runCatching { it.close() } }
        activeSockets.clear()
        acceptThread?.interrupt()
        acceptThread = null
        Log.i(TAG, "BT tunnel server stopped")
    }
}
