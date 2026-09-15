package com.rokidlab.phone.glasses

import android.content.Context
import android.util.Log
import com.rokidlab.phone.connection.selectActiveGlasses
import com.rokidlab.phone.util.LogCollector
import java.io.Closeable
import java.io.DataInputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID

/**
 * ASR 文字推送客户端（手机端，长连接）
 *
 * 连接眼镜端 AsrPushServer 的推送通道（独立于 ADB 隧道），
 * 实时接收眼镜 hook 到的 ASR 文字。断线自动重连。
 *
 * **双通道（WiFi 直连优先，蓝牙 RFCOMM 兜底）**：先试 WiFi 直连眼镜的 [WIFI_PORT]，
 * 连不上（眼镜未接同一 WiFi / 端口未监听）才回落第二 RFCOMM 通道。
 * 两条通道共用同一帧协议，上层与 [isConnected] 语义完全无感。
 * 注意这是**裸 TCP**，不经过 HTTP 栈，因此不受 network_security_config 明文策略约束
 * （对比 8848 的 AIUI 上传走 HttpURLConnection，那条才会被明文策略打回蓝牙）。
 *
 * 协议：4 字节大端长度 + UTF-8 数据（与眼镜端 AsrPushServer 保持一致）。
 */
class AsrPushClient(
    private val context: Context,
    private val onText: (String) -> Unit,
    /**
     * 首选 WiFi 直连所需的眼镜 IP；返回空串表示 IP 未知，直接走 RFCOMM。
     * **每次重连都重新取值** —— 眼镜端的 IP 是上线后才上报的，只取一次会永远错过 WiFi。
     *
     * 排在 [onConnected] 之前是刻意的：调用方用尾随 lambda 传 [onConnected]，
     * 尾随 lambda 在 Kotlin 里绑定「最后一个函数型参数」，若本参数放在最后会被它抢走。
     */
    private val wifiIpProvider: () -> String = { "" },
    /**
     * 推送通道【连续秒断】回调：连接建立后不足 [RAPID_FAILURE_MS] 就被对端关闭，
     * 且连续发生 [RAPID_FAILURE_THRESHOLD] 次 —— 典型于眼镜端 RFCOMM 监听假死
     * （SDP 记录残留/SCN 无人 accept，connect 成功后被栈立即关闭，read ret=-1）。
     * 上层据此经仍健康的 CXR 通道远程踢活眼镜端 AsrPushServer。
     */
    private val onChannelDead: (() -> Unit)? = null,
    /**
     * 推送通道【连接建立成功】回调（每次成功建链/重连都回调一次）。
     * 用于通知上层「断连期间可能积压了数据，需要补读一次兜底通道」。
     * 保持构造参数最后一个函数型位置：调用方以尾随 lambda 传入本参数。
     */
    private val onConnected: (() -> Unit)? = null,
) {
    companion object {
        private const val TAG = "AsrPushClient"

        /** 第二 RFCOMM 通道 UUID（与眼镜端 AsrPushServer 一致） */
        val PUSH_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34F9")

        /** 眼镜端 AsrPushServer 的 WiFi TCP 端口（与其 WIFI_PORT 一致） */
        private const val WIFI_PORT = 7660

        /**
         * WiFi 建链超时。同网段正常在毫秒级；这里必须给短路，不能让一次
         * 不可达的 WiFi 探测拖住 RFCOMM 兜底的建立（掉线重连路径直接影响 ASR 实时性）。
         */
        private const val WIFI_CONNECT_TIMEOUT_MS = 1500

        private const val RECONNECT_DELAY_MS = 3000L
        /** 连接建立失败重试最长退避：眼镜不可达时避免每 3s 盲重连冲击蓝牙协议栈 */
        private const val BACKOFF_MAX_MS = 30_000L
        private const val MAX_FRAME = 65536
        /** 建链后存活不足该时长即断开，计为一次「秒断」（眼镜监听假死时 55~440ms 即断，留足裕量） */
        private const val RAPID_FAILURE_MS = 2_500L
        /** 连续秒断多少次判定监听死亡，触发上层远程踢活 */
        private const val RAPID_FAILURE_THRESHOLD = 3
    }

    @Volatile
    private var running = false

    /**
     * 当前活动连接（WiFi Socket 或 BluetoothSocket）：stop() 需关闭它以解除
     * readFully/readInt 阻塞（仅 interrupt 无法中断 IO 读）。
     */
    @Volatile
    private var conn: Closeable? = null

    /** 当前通道描述（仅用于日志/诊断） */
    @Volatile
    private var routeDesc: String = ""

    /** 推送通道是否已连接（用于决定是否跳过 ADB 文件轮询兜底，减少蓝牙隧道占用） */
    val isConnected: Boolean get() = conn != null

    private var thread: Thread? = null
    private var lastErrorLogAt = 0L

    fun start() {
        if (running) return
        running = true
        thread = Thread {
            // 连续「连接建立失败」次数：眼镜不可达/蓝牙栈忙时指数退避，
            // 避免每 3s 盲重连冲击蓝牙协议栈（加重隧道不稳定）。
            var connectFailures = 0
            // 连续「建链后秒断」计数：眼镜端监听假死的指纹（详见 onChannelDead 说明）
            var rapidFailures = 0
            while (running) {
                var established = false
                var connectedAtMs = 0L
                try {
                    val input = openChannel()
                    established = true
                    connectedAtMs = System.currentTimeMillis()
                    connectFailures = 0
                    Log.i(TAG, "ASR push connected via $routeDesc")
                    // 回调失败会直接废掉「推送恢复 → 补读积压」这条兜底链，不能静默
                    runCatching { onConnected?.invoke() }
                        .onFailure { LogCollector.w(TAG, "onConnected 回调异常（补读可能不触发）", it) }
                    val din = DataInputStream(input)
                    while (running) {
                        val len = din.readInt()
                        if (len <= 0 || len > MAX_FRAME) {
                            // 长度头非法 = 流已失步，无法恢复同步；断开走重连
                            throw Exception("invalid frame length: $len")
                        }
                        val buf = ByteArray(len)
                        din.readFully(buf)
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
                    // 秒断指纹判定（仅统计「曾建链成功」且非主动 stop 的断开）：
                    // 监听假死时每次重演都是「connect 成功 → 几百毫秒内 read ret=-1」，
                    // 连续达阈值即通知上层远程踢活；正常长连接中途断开则清零。
                    if (established && running && connectedAtMs > 0) {
                        val lifetime = System.currentTimeMillis() - connectedAtMs
                        if (lifetime < RAPID_FAILURE_MS) {
                            rapidFailures++
                            Log.w(TAG, "ASR push rapid disconnect #$rapidFailures (lived ${lifetime}ms via $routeDesc)")
                            if (rapidFailures >= RAPID_FAILURE_THRESHOLD) {
                                rapidFailures = 0
                                Log.w(TAG, "ASR push channel looks dead, requesting glasses-side restart")
                                runCatching { onChannelDead?.invoke() }
                            }
                        } else {
                            rapidFailures = 0
                        }
                    }
                    try { conn?.close() } catch (_: Exception) {} // catch-ok: 关闭失败无补救，且置空后由重连兜底
                    conn = null
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

    /**
     * 打开推送通道：WiFi 直连优先，失败回落 RFCOMM；返回其输入流。
     *
     * 成功后 [conn] 已置位 —— 读循环依赖它被 [stop] 关闭以解除阻塞。
     */
    private fun openChannel(): InputStream {
        val wifiIp = runCatching { wifiIpProvider() }.getOrDefault("").trim()
        if (wifiIp.isNotEmpty()) {
            val s = Socket()
            try {
                s.connect(InetSocketAddress(wifiIp, WIFI_PORT), WIFI_CONNECT_TIMEOUT_MS)
                s.tcpNoDelay = true
                s.keepAlive = true
                // 握手令牌：眼镜端 7660 绑 0.0.0.0，未携带令牌的连接会被立即关闭
                // （防同网段主机连上后挤掉本机推送连接）。RFCOMM 通道无需握手。
                s.getOutputStream().apply {
                    write(LinkProtocol.ASR_PUSH_HANDSHAKE)
                    flush()
                }
                routeDesc = "WiFi($wifiIp:$WIFI_PORT)"
                conn = s
                return s.getInputStream()
            } catch (e: Exception) {
                runCatching { s.close() }
                Log.i(
                    TAG,
                    "WiFi 直连 $wifiIp:$WIFI_PORT 不可用（${e.javaClass.simpleName}: ${e.message}），回落 RFCOMM",
                )
            }
        }
        val glasses = selectActiveGlasses(context) ?: throw Exception("no glasses device")
        val s = glasses.createRfcommSocketToServiceRecord(PUSH_UUID)
        // 注意：必须在 connect() 成功【之后】才置 conn。
        // 旧实现在 connect() 前赋值，导致 isConnected 在建链握手期间/建链失败后
        // 短暂为 true —— 上层据此认为"主通道健康"而跳过 ADB 文件兜底轮询，
        // 建链失败期间产生的 ASR 文字便永久丢失（眼镜端显示了提问却永远等不到回复）。
        s.connect()
        routeDesc = "RFCOMM"
        conn = s
        return s.inputStream
    }

    fun stop() {
        running = false
        // 关闭连接解除读线程阻塞，避免占住眼镜端单客户端串行 accept
        try { conn?.close() } catch (_: Exception) {} // catch-ok: 同上，关闭失败无补救
        conn = null
        thread?.interrupt()
        thread?.join(2000)
        thread = null
    }

    private fun logError(e: Exception) {
        val now = System.currentTimeMillis()
        if (now - lastErrorLogAt > 5000) {
            Log.e(TAG, "ASR push error ($routeDesc): ${e.message}")
            LogCollector.w(TAG, "ASR 推送通道断开，将按退避重连", e)
            lastErrorLogAt = now
        }
    }
}
