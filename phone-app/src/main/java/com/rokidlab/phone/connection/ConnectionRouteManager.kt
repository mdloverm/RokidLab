package com.rokidlab.phone.connection

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
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

            // 1. 缓存命中：直接复用上次线路（隧道持续运行，start 幂等）。
            //    BT 线路额外校验：缓存生效期间隧道发生过 RFCOMM 建连失败（蓝牙断开），
            //    缓存立即失效强制重新探测，避免向死隧道继续引流最长 60s。
            routeCache[key]?.let { cached ->
                val btDiedAfterCache = cached.route is ConnectionRoute.Bluetooth &&
                        tunnel.lastConnectFailureAt > cached.time
                if (now - cached.time < ROUTE_CACHE_TTL_MS && !btDiedAfterCache) {
                    Log.i(TAG, "Route cache hit: ${cached.route}")
                    return@withContext cached.route
                }
                Log.i(TAG, "Route cache invalidated (expired=${now - cached.time >= ROUTE_CACHE_TTL_MS}, btFailed=$btDiedAfterCache)")
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

    /**
     * 建立/复用到眼镜端指定端口（targetPort）的蓝牙隧道，返回手机侧本地端口。
     *
     * 适用：眼镜端本地服务（如 RokidLink 的 AiuiPackageServer:7658）不经过 adbd ——
     * Rokid 眼镜的 adbd 拒绝任意 tcp 转发（"adbd does not support arbitrary tcp
     * connections"），adb smart-socket 无法直连；BT 隧道（BtTunnelServer 先收目标
     * 端口再连 127.0.0.1:targetPort）无此限制，是可靠传输通道。
     *
     * @param targetPort 眼镜端目标端口（5555=ADB、7658=AIUI .aix 接收）
     * @return 手机侧本地 TCP 端口；无已配对眼镜/隧道启动失败时返回 null
     */
    fun tunnelTo(targetPort: Int): Int? {
        val localPort = BT_LOCAL_PORT_BASE + targetPort - 5555
        return if (tunnel.start(localPort, targetPort)) localPort else null
    }

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

    /** 是否有已配对的眼镜设备（优先当前 A2DP 活跃连接的眼镜） */
    fun findBondedGlasses(): BluetoothDevice? = selectActiveGlasses(context)

    /** 停止蓝牙隧道 */
    fun stopTunnel() = tunnel.stop()

    /** 隧道是否运行中 */
    val isTunnelRunning: Boolean get() = tunnel.isRunning
}

/**
 * 从已配对设备中挑选当前实际使用的眼镜。
 *
 * 手机可能残留绑定多台眼镜（如旧眼镜 Glasses_5091），按名字 firstOrNull
 * 会选错目标导致 RFCOMM 永远连不上。优先选 A2DP 当前已连接的眼镜
 * （即正在与手机关联的活跃眼镜，实测 mActiveDevice 即当前眼镜 07:2F），
 * 兜底才按绑定顺序取第一台。
 */
internal fun selectActiveGlasses(context: Context): BluetoothDevice? {
    val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: return null
    val adapter = bm.adapter ?: return null
    if (!adapter.isEnabled) return null

    val candidates = adapter.bondedDevices.filter { d ->
        d.name?.let { it.contains("Glasses", true) || it.startsWith("RG", true) } == true
    }
    if (candidates.isEmpty()) return null

    // 1. 优先：A2DP 当前已连接的眼镜。
    //    小米 ROM 的 getConnectedDevices(A2DP) 实测抛 "Profile not supported: 2"，
    //    必须捕获回退，否则隧道/ASR 重连全部失败。
    try {
        bm.getConnectedDevices(BluetoothProfile.A2DP)
            .firstOrNull { connected -> candidates.any { it.address == connected.address } }
            ?.let { connected ->
                val match = candidates.first { it.address == connected.address }
                Log.i("ConnRoute", "Selecting active glasses: ${match.name} (${match.address})")
                return match
            }
    } catch (e: Exception) {
        Log.w("ConnRoute", "getConnectedDevices(A2DP) failed: ${e.message}")
    }

    // 2. 回退：逐设备查询 A2DP 连接状态
    candidates.firstOrNull { d ->
        try {
            bm.getConnectionState(d, BluetoothProfile.A2DP) == BluetoothProfile.STATE_CONNECTED
        } catch (e: Exception) {
            Log.w("ConnRoute", "getConnectionState(${d.address}) failed: ${e.message}")
            false
        }
    }?.let { d ->
        Log.i("ConnRoute", "Selecting A2DP-state-connected glasses: ${d.name} (${d.address})")
        return d
    }

    // 3. 兜底：按绑定顺序取第一台
    val fallback = candidates.first()
    Log.w("ConnRoute", "No A2DP-connected glasses, fallback to ${fallback.name} (${fallback.address})")
    return fallback
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

        /**
         * RFCOMM connect 超时兜底：BluetoothSocket.connect() 无超时 API，
         * 眼镜端隧道忙（串行 accept 被占用）时 connect 会卡 10-30s，
         * 导致本隧道 accept 线程被永久阻塞、后续 ADB 连接全部读超时。
         * 用独立线程 + join 超时，超时即关闭 socket 释放隧道。
         */
        private const val BT_CONNECT_TIMEOUT_MS = 8_000L

        /**
         * 隧道转发空闲超时：客户端 TCP 半开/被杀（进程无 finally 清理）时，
         * t1 读 tcpIn 永久阻塞会把串行隧道占用到底。
         * 大于客户端最慢操作（pullFile 30s / 心跳 8s），正常会话不会触发。
         */
        private const val TUNNEL_IDLE_TIMEOUT_MS = 60_000
    }

    @Volatile
    var isRunning = false
        private set

    /**
     * 最近一次 RFCOMM 建连失败的时间戳（蓝牙链路断开的信号）。
     * ConnectionRouteManager 据此让缓存中的 BT 线路立即失效，
     * 避免蓝牙断线后 60s 内继续向死隧道发请求。
     */
    @Volatile
    var lastConnectFailureAt = 0L
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
                        // 每 TCP 连接独立线程处理，与眼镜端 BtTunnelServer（v3.2 起并发 accept）对齐：
                        // 串行处理时同端口任一条长连接（如 AI 对话常驻的共享 ADB client）
                        // 会阻塞其他模块的短连接，导致 TCP 排队超时（握手失败/命令卡死）。
                        // 每条 handleConnection 自带空闲超时（TUNNEL_IDLE_TIMEOUT_MS），
                        // 线程不会无限堆积；RFCOMM 并发由眼镜端 maxActive 上限约束。
                        Thread { handleConnection(tcpSocket, glasses, targetPort) }
                            .apply { name = "bt-tunnel-$localPort-conn"; isDaemon = true; start() }
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
            connectWithTimeout(btSocket, BT_CONNECT_TIMEOUT_MS)
            Log.i(TAG, "BT RFCOMM connected to ${glasses.name}")

            // 隧道握手：发送 4 字节目标端口号
            val btOut = DataOutputStream(btSocket.outputStream)
            btOut.writeInt(targetPort)
            btOut.flush()

            // 转发阶段 TCP 读加空闲超时：客户端半开/进程被杀时 t1 不再永久阻塞串行隧道
            tcpSocket.soTimeout = TUNNEL_IDLE_TIMEOUT_MS
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
            // t1 受 soTimeout 兜底，最多 TUNNEL_IDLE_TIMEOUT_MS 必然退出
            t1.join(TUNNEL_IDLE_TIMEOUT_MS + 5000L)
            t2.join(2000)
        } catch (e: Exception) {
            // 记录失败时间：路由缓存据此失效（BT 断线后不再向死隧道引流 60s）
            lastConnectFailureAt = System.currentTimeMillis()
            Log.e(TAG, "Tunnel connection failed: ${e.message}")
        } finally {
            try { tcpSocket.close() } catch (_: Exception) {}
            try { btSocket?.close() } catch (_: Exception) {}
            Log.i(TAG, "Tunnel connection closed")
        }
    }

    /**
     * 带超时的 RFCOMM connect：BluetoothSocket.connect() 无超时 API，
     * 用独立线程 + join 超时兜底，超时后关闭 socket 解除阻塞并抛出，
     * 避免一次卡住的 connect 永久占用串行隧道（后续 ADB 连接全部超时）。
     */
    private fun connectWithTimeout(btSocket: BluetoothSocket, timeoutMs: Long) {
        val done = AtomicBoolean(false)
        val connectThread = Thread {
            try { btSocket.connect() } catch (_: Exception) {} finally { done.set(true) }
        }.apply { name = "bt-tunnel-connect"; isDaemon = true; start() }
        if (!done.get()) {
            connectThread.join(timeoutMs)
            if (!done.get()) {
                Log.w(TAG, "BT RFCOMM connect timeout after ${timeoutMs}ms, closing socket")
                // 关闭以解除阻塞中的 connect（Android 蓝牙栈 close 可中断 connect）
                runCatching { btSocket.close() }
                connectThread.join(1000)
                throw IOException("BT RFCOMM connect timeout")
            }
        }
        if (!btSocket.isConnected) throw IOException("BT RFCOMM connect failed")
    }

    private fun findGlasses(): BluetoothDevice? = selectActiveGlasses(context)

    fun stop() {
        isRunning = false
        servers.values.forEach { try { it.close() } catch (_: Exception) {} }
        servers.clear()
        acceptThreads.values.forEach { it.interrupt() }
        acceptThreads.clear()
    }
}
