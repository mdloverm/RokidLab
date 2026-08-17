package com.rokidlab.phone.connection

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

// ════════════════════════════════════════════════════════════════
//  连接线路类型
// ════════════════════════════════════════════════════════════════

sealed class ConnectionRoute {
    /** WiFi 直连线路 */
    data class Wifi(val ip: String, val port: Int) : ConnectionRoute() {
        override fun toString() = "WiFi($ip:$port)"
        override val isBluetooth: Boolean = false
        override val isWifi: Boolean = true
    }

    /** 蓝牙隧道线路：本地 127.0.0.1:localPort → RFCOMM → 眼镜 */
    data class Bluetooth(val localPort: Int, val targetPort: Int) : ConnectionRoute() {
        val ip: String get() = "127.0.0.1"
        override fun toString() = "BT(127.0.0.1:$localPort→$targetPort)"
        override val isBluetooth: Boolean = true
        override val isWifi: Boolean = false
    }

    /** 无可用线路 */
    object None : ConnectionRoute() {
        override fun toString() = "None"
        override val isBluetooth: Boolean = false
        override val isWifi: Boolean = false
    }

    /** 是否蓝牙隧道线路 */
    abstract val isBluetooth: Boolean
    /** 是否 WiFi 直连线路 */
    abstract val isWifi: Boolean
}

// ════════════════════════════════════════════════════════════════
//  连接线路管理器
// ════════════════════════════════════════════════════════════════

/**
 * 自动判断使用 WiFi 或蓝牙隧道线路。
 *
 * 优先级：WiFi（用户 IP 可达）> 蓝牙隧道（已配对眼镜）
 */
class ConnectionRouteManager(private val context: Context) {
    companion object {
        private const val TAG = "ConnRoute"

        /** 蓝牙隧道 RFCOMM 服务 UUID（SPP 串口标准） */
        val TUNNEL_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        /** WiFi 探测超时（毫秒） */
        private const val PROBE_TIMEOUT_MS = 2000

        /** 蓝牙隧道本地端口起始号（每个目标端口分配一个本地端口） */
        private const val BT_LOCAL_PORT_BASE = 5556

        /** 线路缓存有效期：命中缓存直接复用上次线路，避免每轮重复 WiFi 探测（2s 超时） */
        private const val ROUTE_CACHE_TTL_MS = 60_000L
    }

    private val tunnel = BtTunnelClient(context)

    /** 最近一次 resolve 的线路选择（key = "ip:port"），60s 内复用 */
    private data class CachedRoute(val route: ConnectionRoute, val time: Long)
    private val routeCache = ConcurrentHashMap<String, CachedRoute>()

    /**
     * 自动判断最佳线路。
     *
     * 优先复用缓存线路（60s 有效），避免频繁 WiFi 探测：
     * 蓝牙隧道场景下 WiFi 不可达，每次 probeTcp 都要等 2s 超时——
     * 高频短连接（AI 轮询每 1s 一次）会被拖到 3s+。
     *
     * @param wifiIp  用户配置的眼镜 WiFi IP（如 192.168.1.100）
     * @param wifiPort 目标端口（ADB=5555, 投屏=7654）
     * @return 可用线路，WiFi 优先于蓝牙
     */
    suspend fun resolve(wifiIp: String, wifiPort: Int): ConnectionRoute =
        withContext(Dispatchers.IO) {
            val key = "$wifiIp:$wifiPort"
            val now = System.currentTimeMillis()

            // 1. 缓存命中：直接复用上次线路（隧道持续运行，start 幂等）
            routeCache[key]?.let { cached ->
                if (now - cached.time < ROUTE_CACHE_TTL_MS) {
                    Log.i(TAG, "Route cache hit: ${cached.route}")
                    return@withContext cached.route
                }
                routeCache.remove(key)
            }

            // 2. WiFi 探测
            if (wifiIp.isNotBlank() && probeTcp(wifiIp, wifiPort)) {
                Log.i(TAG, "Route: WiFi $wifiIp:$wifiPort")
                val route = ConnectionRoute.Wifi(wifiIp, wifiPort)
                routeCache[key] = CachedRoute(route, now)
                return@withContext route
            }
            Log.i(TAG, "WiFi unreachable ($wifiIp:$wifiPort), trying BT...")

            // 3. 蓝牙隧道（支持任意端口）
            val localPort = BT_LOCAL_PORT_BASE + wifiPort - 5555
            if (tunnel.start(localPort, wifiPort)) {
                Log.i(TAG, "Route: BT tunnel :$localPort → :$wifiPort")
                val route = ConnectionRoute.Bluetooth(localPort, wifiPort)
                routeCache[key] = CachedRoute(route, now)
                return@withContext route
            }

            Log.w(TAG, "No route available")
            routeCache.remove(key)
            ConnectionRoute.None
        }

    /** 清除线路缓存（隧道断线等异常后调用，强制重新探测） */
    fun clearRouteCache() = routeCache.clear()

    /** TCP 可达性探测 */
    private fun probeTcp(ip: String, port: Int): Boolean = try {
        Socket().apply {
            connect(InetSocketAddress(ip, port), PROBE_TIMEOUT_MS)
            close()
        }
        true
    } catch (_: Exception) {
        false
    }

    /** 是否有已配对的眼镜设备 */
    fun findBondedGlasses(): BluetoothDevice? {
        val adapter = btAdapter() ?: return null
        if (!adapter.isEnabled) return null
        return adapter.bondedDevices.firstOrNull { d ->
            d.name?.let {
                it.contains("Glasses", true) || it.startsWith("RG", true)
            } == true
        }
    }

    private fun btAdapter(): BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    /** 停止蓝牙隧道 */
    fun stopTunnel() = tunnel.stop()

    /** 隧道是否运行中 */
    val isTunnelRunning: Boolean get() = tunnel.isRunning
}

// ════════════════════════════════════════════════════════════════
//  蓝牙隧道客户端（手机端）
// ════════════════════════════════════════════════════════════════

/**
 * 在本地开 TCP ServerSocket，将客户端的 TCP 流量
 * 通过蓝牙 RFCOMM 透传到眼镜端。
 *
 * 多端口架构：每个目标端口对应一个本地端口。
 * RFCOMM 连接后先发 4 字节目标端口号，眼镜端据此连接对应服务。
 *
 * 数据流：
 *   Client → TCP 127.0.0.1:localPort → RFCOMM(先发targetPort) → 眼镜 → 127.0.0.1:targetPort
 */
class BtTunnelClient(private val context: Context) {
    companion object {
        private const val TAG = "BtTunnel"
        private const val LOCAL_HOST = "127.0.0.1"
        private const val IO_BUF = 8192
    }

    @Volatile
    var isRunning = false
        private set

    /** 每个本地端口一个 ServerSocket */
    private val servers = ConcurrentHashMap<Int, ServerSocket>()
    private val acceptThreads = ConcurrentHashMap<Int, Thread>()

    /**
     * 启动指定本地端口的隧道监听。
     *
     * @param localPort  本地 TCP 端口（如 5556）
     * @param targetPort 眼镜端目标端口（如 5555=ADB, 7654=投屏）
     */
    fun start(localPort: Int, targetPort: Int): Boolean {
        if (servers.containsKey(localPort)) return true

        val glasses = findGlasses() ?: run {
            Log.w(TAG, "No bonded glasses device")
            return false
        }

        return try {
            val server = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(LOCAL_HOST, localPort))
            }
            servers[localPort] = server
            isRunning = true
            Log.i(TAG, "Tunnel :$localPort → :$targetPort listening")

            val thread = Thread {
                while (servers.containsKey(localPort)) {
                    try {
                        val tcpSocket = server.accept()
                        Log.i(TAG, "Client connected on :$localPort")
                        // 注意：RFCOMM 蓝牙连接仅支持单连接，必须串行处理。
                        // 后台轮询等长连接会独占隧道，其它 ADB 功能请走短连接或独立隧道。
                        handleConnection(tcpSocket, glasses, targetPort)
                    } catch (e: IOException) {
                        if (servers.containsKey(localPort)) Log.e(TAG, "Accept error: ${e.message}")
                    }
                }
            }.apply { name = "bt-tunnel-$localPort"; start() }
            acceptThreads[localPort] = thread

            true
        } catch (e: Exception) {
            Log.e(TAG, "Start :$localPort failed: ${e.message}")
            false
        }
    }

    /**
     * 处理一个隧道连接。
     * 新建蓝牙 RFCOMM Socket，先发目标端口，再双向透传 TCP ↔ RFCOMM。
     */
    private fun handleConnection(tcpSocket: Socket, glasses: BluetoothDevice, targetPort: Int) {
        var btSocket: BluetoothSocket? = null
        try {
            btSocket = glasses.createRfcommSocketToServiceRecord(ConnectionRouteManager.TUNNEL_UUID)
            btSocket.connect()
            Log.i(TAG, "BT RFCOMM connected to ${glasses.name}")

            // 隧道握手：发送 4 字节目标端口号
            val btOut = DataOutputStream(btSocket.outputStream)
            btOut.writeInt(targetPort)
            btOut.flush()

            val btIn = btSocket.inputStream
            val tcpIn = tcpSocket.getInputStream()
            val tcpOut = tcpSocket.getOutputStream()

            val done = AtomicBoolean(false)

            // TCP → 蓝牙
            val t1 = Thread {
                try {
                    val buf = ByteArray(IO_BUF)
                    while (!done.get()) {
                        val n = tcpIn.read(buf)
                        if (n == -1) break
                        btOut.write(buf, 0, n)
                        btOut.flush()
                    }
                } catch (_: IOException) {}
                done.set(true)
                try { btSocket.close() } catch (_: Exception) {}
            }

            // 蓝牙 → TCP
            val t2 = Thread {
                try {
                    val buf = ByteArray(IO_BUF)
                    while (!done.get()) {
                        val n = btIn.read(buf)
                        if (n == -1) break
                        tcpOut.write(buf, 0, n)
                        tcpOut.flush()
                    }
                } catch (_: IOException) {}
                done.set(true)
                try { tcpSocket.close() } catch (_: Exception) {}
            }

            t1.start()
            t2.start()
            t1.join()
            t2.join(2000)
        } catch (e: Exception) {
            Log.e(TAG, "Tunnel connection failed: ${e.message}")
        } finally {
            try { tcpSocket.close() } catch (_: Exception) {}
            try { btSocket?.close() } catch (_: Exception) {}
            Log.i(TAG, "Tunnel connection closed")
        }
    }

    private fun findGlasses(): BluetoothDevice? {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        if (adapter?.isEnabled != true) return null
        return adapter.bondedDevices.firstOrNull { d ->
            d.name?.let {
                it.contains("Glasses", true) || it.startsWith("RG", true)
            } == true
        }
    }

    fun stop() {
        isRunning = false
        servers.values.forEach { try { it.close() } catch (_: Exception) {} }
        servers.clear()
        acceptThreads.values.forEach { it.interrupt() }
        acceptThreads.clear()
    }
}
