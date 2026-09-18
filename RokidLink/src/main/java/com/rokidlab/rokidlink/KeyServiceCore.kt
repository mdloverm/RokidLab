package com.rokidlab.rokidlink

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.rokid.cxr.Caps
import com.rokid.cxr.CXRServiceBridge
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * KeyButtonService 的共享上下文（v3.9 拆分自 KeyButtonService）。
 *
 * 持有各协调器共享的桥接状态与执行器，是所有协调器的公共依赖：
 *  - [bridge] / [bridgeConnected]：CXR 桥接实例与连接态
 *  - [downlinkUntilMs] / [lastDownlinkMs] / [officialEchoUntilMs]：下行过滤与活性时间戳
 *  - [takeoverExecutor]：本地接管串行执行器（含 sleep 的会话序列指令）
 *  - [aiSendExecutor]：Ai 频道发送执行器（sendMessage 超时保护）
 *
 * 生命周期与 [KeyButtonService] 一致：onCreate 创建、onDestroy 销毁。
 */
internal class KeyServiceCore(val service: KeyButtonService) {

    val mainHandler = Handler(Looper.getMainLooper())

    /** CXR bridge 当前是否已连接 */
    @Volatile
    var bridgeConnected = false

    /** CXR 桥接实例（CxrBridgeCoordinator 创建/销毁） */
    @Volatile
    var bridge: CXRServiceBridge? = null

    /** 断线自愈：最近一次成功收到下行消息的时间戳（0=从未收到） */
    @Volatile
    var lastDownlinkMs = 0L

    /** 下行过滤窗口截止时间：窗口内到达的 ASR 一律视为 Lab 重发，忽略 */
    @Volatile
    var downlinkUntilMs = 0L

    /** 本地打断官方后的诊断时间戳窗口（已不参与来源判定，仅日志打印） */
    @Volatile
    var officialEchoUntilMs = 0L

    /** 本地接管执行器：ASR_End 后的 openAiSession/showAiUserText 含多次 sleep，
     *  连续提问时若每次都 new Thread 会并发执行导致指令交错，必须串行化 */
    val takeoverExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "ai-takeover") }

    /** Ai 频道发送执行器（固定 2 线程）：CXR sendMessage 阻塞时只卡任务线程，
     *  sendAi 通过 future.get(timeout) 保护调用线程不被永久卡死 */
    val aiSendExecutor = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "ai-send").apply { isDaemon = true }
    }

    /** 记录一次下行活性：任意经 CXR bridge 订阅收到的消息都证明
     *  cxr-service → 本 App 的分发路由健康（断线重连后可能 stale）。 */
    fun markDownlink() {
        lastDownlinkMs = System.currentTimeMillis()
    }

    /** 发送 Ai 频道指令（caps[0] = 命令，后续为参数）。
     *  带 2s 超时：CXR sendMessage 在蓝牙断/半开时可能无限阻塞（实测 open 卡死导致
     *  takeoverExecutor 与回调线程双双失联、后续 ASR 全走官方），必须超时保护调用线程。 */
    fun sendAi(cmd: String, vararg values: String): Int {
        val b = bridge ?: return -1
        return try {
            val f = aiSendExecutor.submit<Int> {
                val caps = Caps()
                caps.write(cmd)
                values.forEach { caps.write(it) }
                b.sendMessage(KeyButtonService.AI_TOPIC, caps)
            }
            f.get(2, TimeUnit.SECONDS)
        } catch (e: java.util.concurrent.TimeoutException) {
            Log.w(KeyButtonService.TAG, "sendAi($cmd) timeout (CXR channel blocked)")
            -1
        } catch (e: Exception) {
            Log.e(KeyButtonService.TAG, "sendAi($cmd) error", e)
            -1
        }
    }

    /** CXR sendMessage 的超时保护包装。
     *  与 [sendAi] 同源风险：蓝牙断/半开时 sendMessage 可能无限阻塞，
     *  调用线程会被永久挂住。所有在按键路径上的 sendMessage 都必须走这里，不要裸调。 */
    fun sendCxrWithTimeout(b: CXRServiceBridge, topic: String, caps: Caps): Int = try {
        aiSendExecutor.submit<Int> { b.sendMessage(topic, caps) }.get(2, TimeUnit.SECONDS)
    } catch (e: java.util.concurrent.TimeoutException) {
        Log.w(KeyButtonService.TAG, "sendMessage($topic) timeout (CXR channel blocked)")
        -1
    } catch (e: Throwable) {
        Log.e(KeyButtonService.TAG, "sendMessage($topic) error", e)
        -1
    }

    /** 当前对话模型模式是否为自定义（Lab 拦截并回复）；official 模式放行官方乐奇 */
    fun isCustomAiMode(): Boolean =
        service.getSharedPreferences(KeyButtonService.PREFS_NAME, 0)
            .getString(KeyButtonService.KEY_AI_MODE, AiChannel.AI_MODE_CUSTOM)
            .orEmpty()
            .let { if (it.isBlank()) AiChannel.AI_MODE_CUSTOM else it } == AiChannel.AI_MODE_CUSTOM

    /** 服务销毁时释放执行器 */
    fun shutdown() {
        takeoverExecutor.shutdownNow()
        aiSendExecutor.shutdownNow()
    }
}
