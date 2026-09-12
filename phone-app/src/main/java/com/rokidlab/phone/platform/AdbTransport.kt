package com.rokidlab.phone.platform

import android.content.Context
import android.util.Log
import com.rokidlab.phone.adb.AdbShellClient
import com.rokidlab.phone.util.LogCollector

/**
 * ADB 端点（IP + 端口）。用简单数据类而非 L1 的 ConnectionRoute，
 * 让 L0 不反向依赖连接层——路由决策由调用方在 provider lambda 里完成。
 */
data class AdbEndpoint(val ip: String, val port: Int)

/**
 * L0 platform/AdbTransport —— 全 App 共享 ADB shell 会话的**唯一所有者**（Phase 2 收尾）。
 *
 * **为什么必须唯一**：手机侧蓝牙栈对「同一设备 + 同一 SCN」只允许一条客户端 RFCOMM 通道。
 * 若各处自行建链，第二条会话会把正在服务的那条挤断（表现为"用着用着突然不行了"）。
 *
 * **为什么要能主动释放**：屏幕镜像 / 手机投屏 / 文件浏览需要长时间独占隧道；
 * 共享会话若还占着通道，它们的建链会被栈直接拒绝。长连接消费者上场前必须 [release]，
 * 之后 [get] 会在下次调用时自动重建，调用方无感知。
 *
 * **线程安全**：多个后台消费者（AI 工具 + ASR 兜底轮询 + 定时任务）可能同时来取，
 * 不加锁会并发建链并触发通道争抢 —— 所有入口 `@Synchronized`。
 *
 * **与 L1 `connection/ChannelArbiter` 的分工**：本类只负责「会话所有权 + 串行化」——
 * 同一条 socket 谁在用、何时重建/释放；「优先级让路」（长连接占用 RFCOMM 时常规消费者退避）
 * 由 `ChannelArbiter` 负责。二者互补且不重叠：本类不感知优先级，仲裁器不感知 socket 生命周期。
 * 长连接上场前的完整动作是：取 `LONG_LIVED` 租约 **并** 调用 [release] 腾出通道
 * （现由 `domain/MirrorCoordinator` 统一执行）。
 *
 * @param contextProvider 应用 Context（传 Application，勿持 Activity）
 * @param endpointProvider 解析当前可用 ADB 端点（WiFi 直连优先，失败回落蓝牙隧道）；
 *   返回 null 表示当前无可用路径。由调用方注入（内部可含路由缓存/阻塞解析）。
 * @param onRouteFailure 建链失败时的回调（调用方据此清理线路缓存，下次强制重新探测）
 */
class AdbTransport(
    private val contextProvider: () -> Context,
    private val endpointProvider: () -> AdbEndpoint?,
    private val onRouteFailure: () -> Unit = {},
) {
    private companion object {
        const val TAG = "AdbTransport"
    }

    @Volatile
    private var client: AdbShellClient? = null

    /** 当前共享客户端（可能为 null）；仅用于诊断/日志。 */
    val currentOrNull: AdbShellClient? get() = client

    /**
     * 取共享 ADB shell 会话：已连接则直接复用；断开/缺失则按当前端点重建。
     * @return null = 无可用路径或建链失败（调用方应降级，**不要**自行新建会话）
     */
    @Synchronized
    fun get(): AdbShellClient? {
        client?.let {
            if (it.isConnected()) return it
            runCatching { it.disconnect() }
            client = null
        }
        val endpoint = endpointProvider() ?: return null
        return try {
            val c = AdbShellClient(contextProvider(), endpoint.ip, endpoint.port)
            if (c.connect()) {
                client = c
                Log.i(TAG, "shared adb session established -> ${endpoint.ip}:${endpoint.port}")
                c
            } else {
                runCatching { c.disconnect() }
                // 连接失败（含蓝牙隧道 RFCOMM 卡顿/半开）时通知调用方清理线路缓存
                onRouteFailure()
                Log.w(TAG, "shared adb session connect failed -> ${endpoint.ip}:${endpoint.port}")
                null
            }
        } catch (e: Exception) {
            // 原先 runCatching{...}.getOrNull() 把异常整个吞掉：connect() 抛出的真实原因
            // （RFCOMM 被栈拒绝 / 握手超时 / 协议不匹配）在 App 内日志里完全看不到，
            // 上层只看到「无可用路径」。此处补落面板；行为不变（仍返回 null，不额外清路由缓存）。
            LogCollector.e(TAG, "shared adb session 建链异常 -> ${endpoint.ip}:${endpoint.port}", e)
            null
        }
    }

    /** 主动释放共享会话（长连接消费者上场前调用），下次 [get] 自动重建。 */
    @Synchronized
    fun release() {
        client?.let { runCatching { it.disconnect() } }
        client = null
        Log.i(TAG, "shared adb session released")
    }

    /** 彻底关闭（Session 销毁时调用；语义同 [release]，仅便于日志区分）。 */
    @Synchronized
    fun shutdown() {
        client?.let { runCatching { it.disconnect() } }
        client = null
    }
}
