package com.rokidlab.phone.domain

import com.rokidlab.phone.app.LabApplication
import com.rokidlab.phone.connection.ChannelLease
import com.rokidlab.phone.connection.ChannelPriority
import com.rokidlab.phone.connection.ConnectionRoute

/**
 * L3 domain/MirrorCoordinator —— 投屏/长连接共享的通道协调器（Phase 3）。
 *
 * 从 ScreenMirrorActivity / PhoneMirrorService / FileManagerActivity 三处上收的
 * 共享策略，单一实现避免漂移：
 *  1. **路由解析（带启动宽限）**：宽限等待与 WiFi/BT 探测（最长 2s）并行，谁慢等谁，
 *     避免串行叠加（原先 MainActivity 2s + 各页 2s）。resolve() 只启动隧道本地监听，
 *     RFCOMM 要等真正有客户端连上才建立，提前解析不会与 CXR-L 蓝牙通信抢通道。
 *  2. **蓝牙长连接租约**：长连接上场前先让全 App 共享 ADB 会话腾出 RFCOMM 通道，
 *     再按 LONG_LIVED 优先级占用。手机侧蓝牙栈对「同一设备 + 同一 SCN」只允许一条
 *     客户端 RFCOMM 通道，共享会话还占着的话建链会被栈直接拒绝（表现为「连接失败」）。
 *     占租约后，BACKGROUND 兜底轮询会按优先级自动让路。WiFi 线路各走各的 TCP，
 *     不涉及 SCN 争抢，无需占用/让路（由调用方按线路类型决定是否调用）。
 *
 * 幂等：重复 acquire / release 均安全。
 */
class MirrorCoordinator(private val app: LabApplication) {

    private var channelLease: ChannelLease? = null

    /** 当前是否已持有蓝牙通道租约 */
    val hasLease: Boolean get() = channelLease != null

    /**
     * 解析眼镜连接线路（启动宽限与探测并行）。
     *
     * @param graceMs 启动宽限期（等 CXR-L 把眼镜端接收页拉起来）
     * @param threadName 探测线程名（便于日志排查）
     * @return 线路；被中断时返回 null（调用方按连接失败处理）
     */
    fun resolveRouteWithGrace(ip: String, port: Int, graceMs: Long, threadName: String): ConnectionRoute? {
        val holder = java.util.concurrent.atomic.AtomicReference<ConnectionRoute?>()
        val resolver = Thread {
            holder.set(
                runCatching {
                    kotlinx.coroutines.runBlocking { app.routeManager.resolve(ip, port) }
                }.getOrNull()
            )
        }.apply {
            name = threadName
            start()
        }
        return try {
            // 先按宽限等；若解析比宽限还慢，再多给一点，避免重复解析
            resolver.join(graceMs)
            if (holder.get() == null) resolver.join(4_000L)
            holder.get()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        }
    }

    /**
     * 占用蓝牙长连接租约（幂等）：让共享 ADB 会话腾出 RFCOMM 通道 + LONG_LIVED 占用。
     * 仅蓝牙线路需要调用；WiFi 线路各走各的 TCP，无 SCN 争抢。
     */
    fun acquireBluetoothLease(tag: String) {
        if (channelLease != null) return
        channelLease = app.routeManager.channelArbiter.acquire(tag, ChannelPriority.LONG_LIVED)
        // 让全 App 共享 ADB 会话腾出通道（会话可能尚未初始化，须先检查）
        if (app.hasCxrL()) {
            runCatching { app.cxrL.releaseAdbShellClient() }
        }
    }

    /** 释放蓝牙通道租约（幂等）：让共享 ADB 会话 / BACKGROUND 兜底轮询恢复使用通道 */
    fun releaseLease() {
        channelLease?.close()
        channelLease = null
    }
}
