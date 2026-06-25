package com.rokidlab.phone.util

/**
 * 应用配置常量
 * 集中管理硬编码的配置项，便于维护和修改
 */
object AppConfig {
    /** ADB 默认端口 */
    const val DEFAULT_ADB_PORT = 5555

    /** 手机投屏服务默认端口 */
    const val DEFAULT_MIRROR_PORT = 7654

    /** 投屏基准分辨率（短边） */
    const val MIRROR_BASE_SIZE = 640

    /** ADB 连接超时时间（毫秒） */
    const val ADB_CONNECT_TIMEOUT_MS = 10000

    /** ADB Socket 超时时间（毫秒） */
    const val ADB_SOCKET_TIMEOUT_MS = 3000

    /** 投屏 Socket 连接超时时间（毫秒） */
    const val MIRROR_CONNECT_TIMEOUT_MS = 3000

    /** 投屏 Socket 重连最大尝试次数 */
    const val MIRROR_MAX_RECONNECT_ATTEMPTS = 3

    /** 蓝牙 HID 快速断连最大重试次数 */
    const val BLUETOOTH_MAX_QUICK_DISCONNECT_RETRIES = 5

    /** 投屏空闲超时时间（毫秒） */
    const val MIRROR_IDLE_TIMEOUT_MS = 180_000L

    /** scrcpy 视频流超时时间（毫秒） */
    const val SCRCPY_STREAM_TIMEOUT_MS = 80

    /** ADB 单个包最大负载（字节） */
    const val ADB_MAX_PAYLOAD = 1024 * 1024

    /** ADB 流缓冲区最大大小（字节） */
    const val ADB_MAX_STREAM_BUFFER_SIZE = 10 * 1024 * 1024

    /** ADB 心跳间隔（毫秒）- 防止国产手机后台 Socket 超时断开 */
    const val ADB_HEARTBEAT_INTERVAL_MS = 30_000L

    /** ADB 心跳超时（毫秒）- 超时未响应视为断开 */
    const val ADB_HEARTBEAT_TIMEOUT_MS = 10_000L

    /** VIVO/QTI 蓝牙 HID 报告间最小延迟（毫秒）
     *  QTI 蓝牙栈需要一定的间隔避免报告丢失 */
    const val HID_REPORT_INTERVAL_MS = 8L

    /** VIVO/QTI 蓝牙 HID 通道预热延迟（毫秒）
     *  首次连接后等待通道就绪的时间 */
    const val HID_CHANNEL_WARMUP_MS = 500L
}