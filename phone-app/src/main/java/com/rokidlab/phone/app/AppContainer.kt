package com.rokidlab.phone.app

/**
 * L5 AppContainer —— 手动 DI 容器（架构文档 §3.6：不引 Hilt，单人项目成本不划算）。
 *
 * 职责：把跨 feature 共享的长生命周期对象集中装配，feature 层按需注入，
 * 结束"30 个顶层 object 单例 + 各 Activity 自建依赖"的现状。
 *
 * 装配原则（与六层架构一致，每一层只依赖下一层）：
 * - L0 能力探测 / hook 边界（CapabilityProbe、SdkBridge、ShellOps、RomAdapter、HidBridge）
 * - L1 通道仲裁（ChannelArbiter）
 * - L3 domain 服务（Phase 3 各服务就位后逐一挂载）
 *
 * 用法：`LabApplication.container.arbiter`（懒加载，首次访问时构造）。
 * feature 层禁止再自建这些对象。
 */
class AppContainer(private val app: LabApplication) {

    /** L0 能力探测：启动期快照（sawGranted 等）+ SDK 私有 API 唯一执行边界 */
    val capability: com.rokidlab.phone.platform.CapabilityProbe =
        com.rokidlab.phone.platform.CapabilityProbe

    /** L1 通道仲裁器：同设备同 SCN 单通道约束的唯一管理者（Phase 1 落地） */
    val arbiter: com.rokidlab.phone.connection.ChannelArbiter
        get() = app.routeManager.channelArbiter

    /** L3 授权服务：眼镜端权限补齐（Phase 3 首个 domain 服务） */
    val authorization = com.rokidlab.phone.domain.AuthorizationService()

    // ── Phase 3 后续挂载点（各服务从 CxrLHiRokidSession 迁出后在此登记）──
    // val aiConfig: AiConfigService
    // val deviceControl: DeviceControlService
    // val aiuiHost: AiuiHostService
    // val connection: ConnectionService
    // val aiConversation: AiConversationService
    // val mirror: MirrorCoordinator
    // val fileTransfer: FileTransferService
}
