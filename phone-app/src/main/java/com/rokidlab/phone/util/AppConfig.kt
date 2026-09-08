package com.rokidlab.phone.util

/**
 * 应用配置常量
 * 集中管理硬编码的配置项，便于维护和修改
 */
object AppConfig {
    /** 酷我音乐 API（云萌 API 市场）访问 Token，用于 AI「播放歌曲」工具搜索音源 */
    const val KUWO_API_TOKEN = "api-c3e79d8a039f13569921ae4a5b5eb57d5c645d89"

    /** ADB 默认端口 */
    const val DEFAULT_ADB_PORT = 5555

    /** 手机投屏服务默认端口 */
    const val DEFAULT_MIRROR_PORT = 7654

    /** 投屏基准分辨率（短边） */
    const val MIRROR_BASE_SIZE = 640

    // ── 投屏/镜像双套参数（WiFi vs 蓝牙） ──

    /** WiFi 投屏分辨率（宽x高，竖屏） */
    const val MIRROR_WIFI_WIDTH = 480
    const val MIRROR_WIFI_HEIGHT = 640
    /** WiFi 投屏目标帧率 */
    const val MIRROR_WIFI_FPS = 30
    /** WiFi 投屏 Socket 接收缓冲区（字节） */
    const val MIRROR_WIFI_BUFFER_SIZE = 16384

    /** 蓝牙投屏分辨率（牺牲画质换稳定：RFCOMM 实际吞吐约 1-2Mbps，
     *  原 240x320@12fps≈7.2Mbps 远超带宽导致数据积压、断连、投屏失败） */
    const val MIRROR_BT_WIDTH = 160
    const val MIRROR_BT_HEIGHT = 213
    /** 蓝牙投屏目标帧率（160x213@6fps≈1.6Mbps，配合跳帧机制可稳定传输） */
    const val MIRROR_BT_FPS = 6
    /** 蓝牙投屏 Socket 接收缓冲区（更小，加快 flush） */
    const val MIRROR_BT_BUFFER_SIZE = 4096

    /** WiFi 镜像 scrcpy 视频码率 */
    const val SCRCPY_WIFI_BITRATE = 4000000
    /** WiFi 镜像 scrcpy max_size */
    const val SCRCPY_WIFI_MAX_SIZE = 640
    /** WiFi 镜像 Socket 超时（ms） */
    const val SCRCPY_WIFI_TIMEOUT_MS = 80

    /** 蓝牙镜像 scrcpy 视频码率（降低以减少带宽） */
    const val SCRCPY_BT_BITRATE = 800000
    /** 蓝牙镜像 scrcpy max_size */
    const val SCRCPY_BT_MAX_SIZE = 360
    /** 蓝牙镜像 Socket 超时（ms，蓝牙更敏感延迟） */
    const val SCRCPY_BT_TIMEOUT_MS = 120

    /** ADB 连接超时时间（毫秒） */
    const val ADB_CONNECT_TIMEOUT_MS = 10000

    /** ADB Socket 超时时间（毫秒） */
    const val ADB_SOCKET_TIMEOUT_MS = 3000

    /** ADB 握手期读超时（毫秒）：需覆盖蓝牙隧道整条链路建连耗时。
     *  手机端 RFCOMM 建连兜底 8s（BtTunnelClient.BT_CONNECT_TIMEOUT_MS）+ 眼镜端连本地 adbd
     *  （3s）+ 首包回传余量；握手一旦开始（首包到达）即毫秒级完成，故正常路径不受此上限影响，
     *  仅防止隧道建连慢时被命令期 3s 超时误判为"连接失败"。 */
    const val ADB_HANDSHAKE_TIMEOUT_MS = 15_000

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

    /** ADB 心跳间隔（毫秒）- 防止国产手机后台 Socket 超时断开，鸿蒙4.2需更短间隔 */
    const val ADB_HEARTBEAT_INTERVAL_MS = 8_000L

    /** ADB 心跳超时（毫秒）- 超时未响应视为断开 */
    const val ADB_HEARTBEAT_TIMEOUT_MS = 10_000L

    /** VIVO/QTI 蓝牙 HID 报告间最小延迟（毫秒）
     *  QTI 蓝牙栈需要一定的间隔避免报告丢失 */
    const val HID_REPORT_INTERVAL_MS = 8L

    /** VIVO/QTI 蓝牙 HID 通道预热延迟（毫秒）
     *  首次连接后等待通道就绪的时间 */
    const val HID_CHANNEL_WARMUP_MS = 500L
}