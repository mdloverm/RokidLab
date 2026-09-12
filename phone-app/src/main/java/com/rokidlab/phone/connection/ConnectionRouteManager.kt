package com.rokidlab.phone.connection

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.util.Log
import com.rokidlab.phone.util.LogCollector
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

    /**
     * 蓝牙通道仲裁器（长连接 / 控制面 / 兜底轮询的优先级让路）。
     *
     * 消费方按 [ChannelPriority] 获取租约，离场 `close()`（幂等）。
     * 取代旧的 `reserveTunnel()/releaseTunnel()` 裸计数 —— 见 [ChannelArbiter] 类注释。
     */
    val channelArbiter = ChannelArbiter()

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

    /**
     * 隧道最后一次建链失败是否属于「同 SCN 通道被占用」冲突。
     * 调用方（如 ASR 文件轮询兜底）据此决定要不要清线路缓存 —— 冲突时清缓存只会
     * 让下一次 resolve 白等一轮 2s WiFi 探测，反而加剧隧道争抢。
     */
    val isTunnelChannelConflict: Boolean get() = tunnel.lastFailureWasChannelConflict
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

        /**
         * RFCOMM 建链重试参数。
         *
         * 眼镜端 BtTunnelServer 只注册了一个 SCN（TUNNEL_UUID → SCN 5），而 Android 蓝牙栈
         * 对「同一设备 + 同一 SCN」只允许**一条**客户端通道同时存在，第二条会被栈直接拒绝。
         * 本 App 有多个 ADB 消费者（屏幕镜像 / ADB 工具 / ASR 文件轮询兜底）各自开一条隧道
         * TCP，后到者必然被拒；但这类冲突通常在数百毫秒内自解，故短退避重试即可吃掉。
         *
         * 总窗口 ≈ 1.3s（120+240+450+450 退避 + 若干次快速失败），必须明显小于客户端读超时
         * （ADB_SOCKET_TIMEOUT_MS = 3s），否则重试还没走完，调用方已先报 Connection reset。
         */
        private const val RFCOMM_RETRY_ATTEMPTS = 5
        private const val RFCOMM_RETRY_BASE_MS = 120L
        private const val RFCOMM_RETRY_MAX_MS = 450L
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

    /**
     * 最近一次建链失败是否属于「同一 SCN 通道被占用」冲突（而非蓝牙链路断开）。
     * 上层据此决定是否清空线路缓存：冲突说明线路本身没问题，清缓存只会让下一次
     * resolve 白等一轮 2s WiFi 探测，反而加剧隧道争抢。
     */
    @Volatile
    var lastFailureWasChannelConflict = false
        private set

    /** 串行化 RFCOMM 建链（仅建链阶段，不含数据转发），避免同时发起两次必然失败一次 */
    private val rfcommEstablishLock = java.util.concurrent.locks.ReentrantLock()

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
            btSocket = openRfcomm(glasses)
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
                } catch (_: IOException) {} // catch-ok: 读线程正常退出路径（对端关闭/soTimeout），由 done+t2 收尾
                done.set(true)
                try { btSocket.close() } catch (_: Exception) {} // catch-ok: 关闭失败无补救
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
                } catch (_: IOException) {} // catch-ok: 同上，反向读线程退出路径
                done.set(true)
                try { tcpSocket.close() } catch (_: Exception) {} // catch-ok: 关闭失败无补救
            }

            t1.start()
            t2.start()
            // 等转发线程自然结束 —— **绝不能**给 t1 的 join 设上限后就关 socket。
            //
            // 旧写法是 t1.join(TUNNEL_IDLE_TIMEOUT_MS + 5000L)（≈65s），join 超时后代码继续走到
            // finally 把 socket 全关掉，于是「心跳维持着的健康长连接」也会在 65 秒被强制掐断：
            // ADB 会话随之失效（客户端下一次命令报 "Connection closed / session dead"），
            // 界面表现就是「ADB 工具/投屏用着用着就不行了，切走再回来又好了」。
            // 实测旧行为：会话存活时长恒为 67s / 72s，全部卡在这个 join 上限上。
            //
            // 现在 t1 由 tcpSocket.soTimeout（TUNNEL_IDLE_TIMEOUT_MS）兜底：
            // 客户端静默超过该时长 → read 抛 SocketTimeoutException → 退出并关闭 btSocket
            // → t2 的 btIn.read 随之失败退出。只要客户端在正常通信（ADB 心跳每 8s 一次），
            // 连接就会一直保持，不再被隧道自己掐断。
            t1.join()
            t2.join(2000)
        } catch (e: Exception) {
            // 记录失败时间：路由缓存据此失效（BT 断线后不再向死隧道引流 60s）
            lastConnectFailureAt = System.currentTimeMillis()
            // 「通道被占用」不代表蓝牙链路有问题，标记出来让上层不要误清线路缓存
            lastFailureWasChannelConflict = e is RfcommChannelBusyException
            Log.e(TAG, "Tunnel connection failed: ${e.message}")
            // 原日志只有 message，丢掉了堆栈与异常类型 —— 而「栈拒绝建链(RfcommChannelBusy)」
            // 与「链路断开(SocketException)」的处置完全不同，只看 message 无法区分，故补落面板。
            LogCollector.e(TAG, "隧道建链/转发失败: ${e.message}", e)
        } finally {
            try { tcpSocket.close() } catch (_: Exception) {} // catch-ok: 关闭失败无补救
            try { btSocket?.close() } catch (_: Exception) {} // catch-ok: 关闭失败无补救
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
        // 不能吞掉 connect() 抛出的异常：它才是真实失败原因。
        // 此前被 catch (_: Exception) 丢弃，日志里只剩一句笼统的 "BT RFCOMM connect failed"，
        // 蓝牙栈的真实拒绝原因完全看不到（本次排查因此绕了大弯路）。
        var connectError: Exception? = null
        val connectThread = Thread {
            try { btSocket.connect() } catch (e: Exception) { connectError = e } finally { done.set(true) }
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
        if (!btSocket.isConnected) {
            // connect() 已返回但未连通 —— 栈拒绝建链的典型形态：
            //   RFCOMM_CreateConnectionWithSecurity: already at opened state ... scn=5
            //   → bta_jv_rfcomm_connect: RFCOMM_CreateConnection failed
            // 即「同设备同 SCN 已有一条客户端通道」，属可重试的瞬时冲突。
            throw RfcommChannelBusyException(
                "RFCOMM create rejected (channel busy): ${connectError?.message ?: "socket not connected"}",
            )
        }
    }

    /** 眼镜端同一 SCN 的 RFCOMM 客户端通道已被占用（栈拒绝建链），与「蓝牙链路断开」是两回事 */
    private class RfcommChannelBusyException(message: String) : IOException(message)

    /**
     * 建立到眼镜的 RFCOMM 通道：串行建链 + 短退避重试。
     *
     * 为什么要重试 —— 眼镜端 BtTunnelServer 只注册了一个 SCN（TUNNEL_UUID → SCN 5），
     * 而 Android 蓝牙栈对「同一设备 + 同一 SCN」只允许**一条**客户端通道同时存在，第二条
     * 会被 RFCOMM 层直接拒绝。本 App 有多个 ADB 消费者（屏幕镜像 / ADB 工具 / ASR 文件
     * 轮询兜底）各自开一条隧道 TCP，于是后到的那条必然被拒 —— 隧道随即关闭 TCP，调用方
     * 看到 `Connection reset`，界面即「连接失败」。
     *
     * 实测这类冲突是瞬时的（对方会话通常在数百毫秒内结束；上层 2–3s 后重试即成功），
     * 所以在隧道内部吃掉重试，而不是把失败抛给上层 UI。
     *
     * 建链阶段用 [rfcommEstablishLock] 串行：两个 TCP 连接同时到达时并行发起 RFCOMM，
     * 按栈语义必有一条失败（实测 21:01:01 / 21:02:14 均如此），串行后这种自伤消失。
     * 锁只覆盖「建链」，不覆盖数据转发，否则会把并发隧道退化成单路。
     */
    private fun openRfcomm(glasses: BluetoothDevice): BluetoothSocket {
        var lastError: Exception? = null
        var backoff = RFCOMM_RETRY_BASE_MS
        for (attempt in 1..RFCOMM_RETRY_ATTEMPTS) {
            var socket: BluetoothSocket? = null
            try {
                socket = glasses.createRfcommSocketToServiceRecord(ConnectionRouteManager.TUNNEL_UUID)
                rfcommEstablishLock.lock()
                try {
                    connectWithTimeout(socket, BT_CONNECT_TIMEOUT_MS)
                } finally {
                    rfcommEstablishLock.unlock()
                }
                if (attempt > 1) Log.i(TAG, "BT RFCOMM connected on attempt $attempt")
                return socket
            } catch (e: Exception) {
                lastError = e
                runCatching { socket?.close() }
                if (attempt >= RFCOMM_RETRY_ATTEMPTS) break
                if (attempt == 1) Log.w(TAG, "BT RFCOMM attempt 1 failed (${e.message}), retrying")
                try { Thread.sleep(backoff) } catch (_: InterruptedException) { break }
                backoff = (backoff * 2).coerceAtMost(RFCOMM_RETRY_MAX_MS)
            }
        }
        // 保留失败类型：上层据此判断是「通道冲突（可重试、线路没问题）」还是「链路断开」
        if (lastError is RfcommChannelBusyException) {
            throw RfcommChannelBusyException("RFCOMM channel busy, gave up after $RFCOMM_RETRY_ATTEMPTS attempts")
        }
        throw IOException("BT RFCOMM connect failed after $RFCOMM_RETRY_ATTEMPTS attempts: ${lastError?.message}")
    }

    private fun findGlasses(): BluetoothDevice? = selectActiveGlasses(context)

    fun stop() {
        isRunning = false
        servers.values.forEach { try { it.close() } catch (_: Exception) {} } // catch-ok: stop() 收尾，关闭失败无补救
        servers.clear()
        acceptThreads.values.forEach { it.interrupt() }
        acceptThreads.clear()
    }
}
