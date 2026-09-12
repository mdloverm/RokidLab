package com.rokidlab.phone.domain

import com.rokidlab.phone.app.LabApplication
import com.rokidlab.phone.connection.ConnectionRoute

/**
 * L3 domain/FileTransferService —— 文件管理器的连接域服务（Phase 3）。
 *
 * 从 FileManagerActivity 上收的连接策略：眼镜线路解析 + 蓝牙长连接租约
 * （复用 [MirrorCoordinator] 单一实现），Activity 只保留 ADB 文件客户端与 UI 编排。
 *
 * 幂等：重复 acquire / release 均安全。
 */
class FileTransferService(private val app: LabApplication) {

    private val coordinator = MirrorCoordinator(app)

    /** 当前是否已持有蓝牙通道租约 */
    val hasLease: Boolean get() = coordinator.hasLease

    /**
     * 解析眼镜连接线路（同步阻塞，最长约 2s；文件管理器无启动宽限需求，
     * 用户手动点连接时眼镜端一般已就绪）。
     */
    fun resolveRoute(ip: String, port: Int): ConnectionRoute {
        // 首启时眼镜 IP 可能尚未上报：先用占位默认值探测 WiFi 必然失败 → 回落到蓝牙隧道。
        // 调用方均在后台线程，因此这里可以安全等待 IP 就绪（已确认时零等待）。
        val effectiveIp = if (app.glassesIpConfirmed) ip else app.awaitGlassesIp(IP_WAIT_MS)
        return kotlinx.coroutines.runBlocking { app.routeManager.resolve(effectiveIp, port) }
    }

    private companion object {
        /** 等待眼镜 IP 上报的上限（文件管理器是用户手动点连接，眼镜端一般已就绪） */
        const val IP_WAIT_MS = 2_000L
    }

    /** 占用蓝牙长连接租约（幂等）：仅蓝牙线路需要，WiFi 各走各的 TCP */
    fun acquireBluetoothLease() = coordinator.acquireBluetoothLease("file-manager")

    /** 释放蓝牙通道租约（幂等）：让共享 ADB 会话 / 兜底轮询恢复使用通道 */
    fun releaseLease() = coordinator.releaseLease()
}
