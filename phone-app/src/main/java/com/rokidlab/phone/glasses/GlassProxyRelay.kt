package com.rokidlab.phone.glasses

import android.util.Log
import com.rokid.cxr.Caps
import com.rokidlab.phone.util.namedThread
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * 手机端 NetProxy 应答器。
 *
 * 背景：Rokid 眼镜（AssistServer）通过官方链路下载 .aix（对话生成项目安装，
 * 见 CxrLHiRokidSession.installAiuiAgent）时走 NetProxyEngine（OKHTTP_PROXY 模式），
 * 眼镜把 HTTP 流量分包经 BLE 发到手机：cmd="Proxy"，
 * caps0="Proxy_NetRequest"，caps1=ProxyRequestParam JSON，caps2=原始字节。
 * 手机（官方 App 或本类）作为 TCP 中继执行真实网络请求后，以 cmd="Proxy"、
 * caps0="Proxy_NetResponse" 应答（协议细节逆向自 AssistServer smali）：
 *  - caps1 = ProxyResponseParam JSON：{type, fromScene, requestId,
 *    responseData:{status, data}}，其中 data = "<sessionId>|<packetType>"，
 *    status="10001" 表示成功（眼镜端 SceneRuntime 只认 10001）。
 *  - caps2 = 应答数据字节（socket 读到的上游数据）。
 *
 * 会话语义（眼镜端 SceneRuntime 逐条核对 requestId / sessionId）：
 *  - connect：按 packetData.host:port 建连。眼镜下载 URL 为手机本地托管的
 *    http://127.0.0.1:<port>/xxx.aix（AiuiProject.hostAix），host 是回环地址，
 *    手机须把它解析为“本机”（连到手机自己的 AiuxHttpServer 端口），
 *    其余 host 直连其真实地址。
 *  - sendData：把 caps2 字节写入 socket；随后用原 requestId 回 ACK（status 10001，
 *    空字节）——眼镜端若在 ACK 上附带数据（有 pendingSend）会丢弃字节，数据须单独推。
 *  - disconnect：关闭 socket 并回 ACK。
 *  - 读方向：为每个会话启动读取线程，读到数据即以【新 requestId】推
 *    "sessionId|sendData" 数据帧（眼镜端无 pendingSend 时按读数据分发到 socket 流）。
 *    文件下载完整度由 HTTP Content-Length 保证（本机托管 server 带 Content-Length），
 *    EOF 后仅静默关闭，不主动推 disconnect，避免打断眼镜端读取缓冲。
 *
 * 线程模型：会话操作（建连/写/关）在单线程 executor 串行；每个会话的读线程
 * 独立运行并推送数据帧；所有下行 sendCustomCmd 经同一 sendLock + 调用方 aiCmdLock
 * 串行，避免与 AI 主链路并发 sendCustomCmd。
 */
class GlassProxyRelay(private val send: (Caps) -> Int) {

    companion object {
        private const val TAG = "GlassProxyRelay"
        /** 眼镜端 SceneRuntime 判定的成功状态（唯一成功码） */
        private const val STATUS_OK = "10001"
        /** 失败占位码（非 10001 眼镜端均视为失败） */
        private const val STATUS_FAIL = "10000"
        /** 读线程单次读取/单帧推送的最大字节数 */
        private const val CHUNK_SIZE = 32 * 1024
        /** 每推一帧后的节流间隔，避免撑爆 BLE 传输队列 */
        private const val PUSH_PACE_MS = 6L
        /** 建连超时 */
        private const val CONNECT_TIMEOUT_MS = 10_000
        /** socket 读超时（无数据时等待时长，防止会话泄漏） */
        private const val READ_TIMEOUT_MS = 30_000
    }

    private class RelaySession(val socket: Socket, val sessionId: String) {
        @Volatile
        var closed = false
    }

    private val sessions = ConcurrentHashMap<String, RelaySession>()
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "GlassProxyRelay").apply { isDaemon = true }
    }

    /** 会话内建连/写/关 + 读线程推送共用的发送互斥 */
    private val sendLock = Any()

    /** SDK 回调（binder 线程）入口：解析眼镜上行的 Proxy_NetRequest */
    fun onInbound(data: ByteArray?) {
        val caps = try {
            data?.let { Caps.fromBytes(it) }
        } catch (e: Exception) {
            Log.e(TAG, "caps parse failed", e)
            return
        } ?: return
        if (caps.size() < 1) return
        val c0 = runCatching { caps.at(0).getString() }.getOrNull().orEmpty()
        if (c0 != "Proxy_NetRequest") {
            Log.w(TAG, "onInbound unexpected c0=$c0")
            return
        }
        try {
            val root = JSONObject(caps.at(1).getString())
            val type = root.optString("type")
            val fromScene = root.optString("fromScene")
            val requestId = root.optString("requestId")
            val pd = root.optJSONObject("packetData")
            val packetType = pd?.optString("packetType").orEmpty()
            val sessionId = pd?.optString("sessionId").orEmpty()
            val host = pd?.optString("host").orEmpty()
            val port = pd?.optInt("port") ?: 0
            val bytes = binaryOf(caps)
            Log.i(
                TAG,
                "NetRequest type=$type scene=$fromScene pkt=$packetType sid=$sessionId host=$host port=$port bytes=${bytes?.size ?: 0}",
            )
            when (packetType) {
                "connect" -> executor.execute { doConnect(type, fromScene, requestId, sessionId, host, port) }
                "sendData" -> executor.execute { doSendData(type, fromScene, requestId, sessionId, bytes) }
                "disconnect" -> executor.execute { doDisconnect(type, fromScene, requestId, sessionId) }
                else -> Log.w(TAG, "onInbound unknown packetType=$packetType")
            }
        } catch (e: Exception) {
            Log.e(TAG, "onInbound parse failed", e)
        }
    }

    /** 会话全部关闭（连接断开/页面清理时调用） */
    fun closeAll() {
        sessions.values.forEach { closeQuiet(it, pushNotify = false) }
        sessions.clear()
    }

    // ═══════════════════════════════════════════════
    // 会话操作（executor 线程）
    // ═══════════════════════════════════════════════

    private fun doConnect(
        type: String,
        fromScene: String,
        requestId: String,
        sessionId: String,
        host: String,
        port: Int,
    ) {
        if (sessionId.isBlank() || port <= 0) {
            ack(type, fromScene, requestId, sessionId, "connect", STATUS_FAIL)
            return
        }
        // 回环 host 表示“代理方自己”（本机 AixHttpServer 托管在手机），映射到手机本机
        val target = if (host == "127.0.0.1" || host == "localhost" || host == "::1") "127.0.0.1" else host
        try {
            val socket = Socket()
            socket.tcpNoDelay = true
            socket.connect(InetSocketAddress(target, port), CONNECT_TIMEOUT_MS)
            socket.soTimeout = READ_TIMEOUT_MS
            val session = RelaySession(socket, sessionId)
            // 同 sessionId 重连：先关旧的
            sessions.remove(sessionId)?.let { runCatching { it.socket.close() } }
            sessions[sessionId] = session
            Log.i(TAG, "connect ok sid=$sessionId -> $target:$port")
            ack(type, fromScene, requestId, sessionId, "connect", STATUS_OK)
            startReader(session)
        } catch (e: Exception) {
            Log.w(TAG, "connect failed sid=$sessionId -> $target:$port", e)
            ack(type, fromScene, requestId, sessionId, "connect", STATUS_FAIL)
        }
    }

    private fun doSendData(
        type: String,
        fromScene: String,
        requestId: String,
        sessionId: String,
        bytes: ByteArray?,
    ) {
        val session = sessions[sessionId] ?: run {
            // 会话已不存在（下载结束被清）：仍回 ACK，避免眼镜端 pending send 等超时
            ack(type, fromScene, requestId, sessionId, "sendData", STATUS_FAIL)
            return
        }
        try {
            if (bytes != null && bytes.isNotEmpty()) {
                val out = session.socket.getOutputStream()
                out.write(bytes)
                out.flush()
            }
            ack(type, fromScene, requestId, sessionId, "sendData", STATUS_OK)
        } catch (e: Exception) {
            Log.w(TAG, "sendData failed sid=$sessionId bytes=${bytes?.size ?: 0}", e)
            ack(type, fromScene, requestId, sessionId, "sendData", STATUS_FAIL)
            closeQuiet(session, pushNotify = false)
        }
    }

    private fun doDisconnect(
        type: String,
        fromScene: String,
        requestId: String,
        sessionId: String,
    ) {
        val session = sessions.remove(sessionId)
        if (session != null) {
            Log.i(TAG, "disconnect sid=$sessionId")
            closeQuiet(session, pushNotify = false)
        }
        ack(type, fromScene, requestId, sessionId, "disconnect", STATUS_OK)
    }

    // ═══════════════════════════════════════════════
    // 读方向数据推送（每个会话一个读线程）
    // ═══════════════════════════════════════════════

    private fun startReader(session: RelaySession) {
        namedThread("glass-proxy-io", start = true) {
            val buf = ByteArray(CHUNK_SIZE)
            try {
                val ins = session.socket.getInputStream()
                while (!session.closed) {
                    val n = try {
                        ins.read(buf)
                    } catch (e: Exception) {
                        if (session.closed) break
                        -1
                    }
                    if (n < 0) break
                    if (n > 0) {
                        val chunk = ByteArray(n)
                        System.arraycopy(buf, 0, chunk, 0, n)
                        pushDataFrame(session, chunk)
                        if (PUSH_PACE_MS > 0) {
                            try {
                                Thread.sleep(PUSH_PACE_MS)
                            } catch (ignore: InterruptedException) {
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "reader ended sid=${session.sessionId}", e)
            } finally {
                // 下载完整性由 HTTP Content-Length 保证：EOF 后静默关闭，
                // 不主动推 disconnect，避免打断眼镜端读取缓冲
                Log.i(TAG, "reader eof sid=${session.sessionId}")
                closeQuiet(session, pushNotify = false)
            }
        }
    }

    private fun pushDataFrame(session: RelaySession, chunk: ByteArray) {
        if (session.closed) return
        val json = responseJson(
            type = "dataPacket",
            fromScene = "jsai",
            requestId = UUID.randomUUID().toString(),
            status = STATUS_OK,
            data = "${session.sessionId}|sendData",
        )
        val caps = Caps()
        caps.write("Proxy_NetResponse")
        caps.write(json)
        caps.write(chunk)
        doSend(caps)
    }

    // ═══════════════════════════════════════════════
    // 通用应答
    // ═══════════════════════════════════════════════

    private fun ack(type: String, fromScene: String, requestId: String, sessionId: String, packetType: String, status: String) {
        if (requestId.isBlank()) return
        val caps = Caps()
        caps.write("Proxy_NetResponse")
        caps.write(responseJson(type, fromScene, requestId, status, "$sessionId|$packetType"))
        doSend(caps)
    }

    /** 响应 JSON：与眼镜端 Gson(ProxyResponseParam) 字段一一对应 */
    private fun responseJson(type: String, fromScene: String, requestId: String, status: String, data: String): String =
        JSONObject()
            .put("type", type)
            .put("fromScene", fromScene)
            .put("requestId", requestId)
            .put("responseData", JSONObject().put("status", status).put("data", data))
            .toString()

    private fun doSend(caps: Caps) {
        synchronized(sendLock) {
            runCatching { send(caps) }
                .onFailure { Log.e(TAG, "send Proxy_NetResponse failed", it) }
                .onSuccess { if (it != 0) Log.w(TAG, "send Proxy_NetResponse -> $it") }
        }
    }

    private fun closeQuiet(session: RelaySession, pushNotify: Boolean) {
        if (session.closed) return
        session.closed = true
        runCatching { session.socket.close() }
    }

    /** 取 caps2（binary）的独立字节数组副本 */
    private fun binaryOf(caps: Caps): ByteArray? {
        if (caps.size() < 3) return null
        val bin = runCatching { caps.at(2).getBinary() }.getOrNull() ?: return null
        val d = bin.data
        if (d == null || bin.length <= 0) return null
        val from = bin.offset.coerceIn(0, d.size)
        val len = bin.length.coerceAtMost(d.size - from)
        if (len <= 0) return null
        return d.copyOfRange(from, from + len)
    }
}
