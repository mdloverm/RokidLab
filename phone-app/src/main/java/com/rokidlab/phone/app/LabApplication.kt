package com.rokidlab.phone.app

import com.rokidlab.phone.connection.ConnectionRoute
import com.rokidlab.phone.connection.ConnectionRouteManager
import com.rokidlab.phone.adb.TimerScheduler
import com.rokidlab.phone.design.RokidHostApp
import com.rokidlab.phone.glasses.CxrLHiRokidSession
import com.rokidlab.phone.hid.BluetoothHidManager
import com.rokidlab.phone.permission.AppForegroundTracker
import com.rokidlab.phone.permission.PermissionBridge
import com.rokidlab.phone.util.LocalizationManager
import com.rokidlab.phone.util.LogCollector
import com.rokidlab.phone.R
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.security.Provider
import java.security.Security
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf

class LabApplication : Application() {
    companion object {
        private const val TAG = "LabApplication"
        private const val PREFS_KEEP_ALIVE = "keep_alive_enabled"

        /** 乐奇聊天「本机模式」持久化键（见 [chatLocalOnlyEnabled]） */
        private const val PREFS_CHAT_LOCAL_ONLY = "chat_local_only"

        /** 乐奇聊天「图像理解」持久化键（见 [chatImageInputEnabled]） */
        private const val PREFS_CHAT_IMAGE_INPUT = "chat_image_input"

        /** 乐奇聊天「过程区块默认展开」持久化键（见 [chatExpandTraceEnabled]） */
        private const val PREFS_CHAT_EXPAND_TRACE = "chat_expand_trace"
        private const val CHANNEL_KEEP_ALIVE_FGS = "keep_alive_fgs"
    }
    lateinit var cxrL: CxrLHiRokidSession
        private set

    lateinit var hidManager: BluetoothHidManager
        private set

    lateinit var routeManager: ConnectionRouteManager
        private set

    /** 定时任务调度器：任务持久化 + appScope 常驻触发（保活服务启动时恢复 running 任务） */
    lateinit var timerScheduler: TimerScheduler
        private set

    /** 全局协程作用域：长驻任务（ASR 推送/轮询等）的宿主，不随 Activity 销毁取消。
     *  配合保活前台服务，保证 Activity 退后台/销毁后 Lab 后台能力仍持续运行。 */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** L5 手动 DI 容器（Phase 5）：跨 feature 共享对象的集中装配点，feature 层按需注入 */
    val container: AppContainer by lazy { AppContainer(this) }

    // 运行时 RokidLink 安装状态缓存
    var rokidLinkInstalled: Boolean? = null

    // 持久化安装标记：一经安装成功永不清除
    var rokidLinkEverInstalled: Boolean = false
        private set

    // ===== 全局 IP 地址管理 =====
    // 单一数据源：投屏 / 手机镜像 / 文件管理 / ADB 工具 共用同一个眼镜 IP，
    // 任一界面修改都会同步到所有界面（修复「四个输 IP 的地方互不连通」问题）。
    private val GLASSES_IP_KEY = "glasses_ip"
    var glassesIp: String = "192.168.1.168"
        private set
    /** 可观察眼镜 IP：Compose 输入框读此 state，眼镜端 WiFi 接通后自动推送新 IP 触发重组回填（免手动输入）。
     *  所有非 Compose 引用处仍按 [glassesIp] 的 String 使用，二者在 [setGlassesIp] 中同步。 */
    val glassesIpState: MutableState<String> = mutableStateOf("192.168.1.168")

    /**
     * 眼镜 IP 是否已确认（来自眼镜端上报 / 用户输入 / 持久化恢复）。
     *
     * `false` 表示当前只是占位默认值 `192.168.1.168`：**不要**据此探测 WiFi 直连 ——
     * 首启投屏/投屏/文件管理时眼镜端 `TOPIC_GLASSES_IP` 上行常晚于页面创建，
     * 用占位 IP 探测必然失败并回落到蓝牙隧道（线路还会被缓存一段时间），
     * 表现为「第一次启动镜像没有自动切到 WiFi 通道」。
     * 需要 IP 就绪再探测的调用方，请用 [awaitGlassesIp] 代替裸读 [glassesIpState]。
     */
    @Volatile
    var glassesIpConfirmed: Boolean = false
        private set

    /**
     * 等待眼镜 IP 就绪（最多 [timeoutMs]，已确认时立即返回）。
     *
     * **必须在后台线程调用**（内部 sleep 轮询）。
     */
    fun awaitGlassesIp(timeoutMs: Long): String {
        if (glassesIpConfirmed) return glassesIpState.value
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline && !glassesIpConfirmed) {
            try {
                Thread.sleep(100L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
        return glassesIpState.value
    }
    // 历史兼容别名：读取返回 glassesIp，公开 setter 即外部设置入口（委托到 setGlassesIp）
    var screenMirrorIp: String
        get() = glassesIp
        set(value) { setGlassesIp(value) }
    var phoneMirrorIp: String
        get() = glassesIp
        set(value) { setGlassesIp(value) }
    var phoneMirrorPort: String = "7654"
        private set
    var fileManagerIp: String
        get() = glassesIp
        set(value) { setGlassesIp(value) }

    private lateinit var prefs: SharedPreferences

    /** 后台保活开关（默认开启）：开启时 LabKeepAliveService 常驻通知栏，保证后台能力不被系统回收 */
    var keepAliveEnabled: Boolean = true
        private set

    /**
     * 乐奇聊天「本机模式」开关（默认**关闭**）。
     *
     * 开启后：聊天不再尝试连接眼镜（原来没眼镜时要白等 15s 连接超时才报错），
     * 直接把文字交给自己这一侧的模型与工具链处理；
     * 工具清单里也会摘掉 [com.rokidlab.phone.ai.ToolRegistry.GLASSES_REQUIRED_TOOLS]
     * （眼镜电量/存储/App 列表/AIUI 应用/歌词），模型看不到就不会去调。
     *
     * 为什么做成**手动开关**而不是自动探测：自动判定（比如「CXR 没连上就本机」）在
     * 「眼镜在、只是链路刚断」这类中间态上会让回复悄悄不下发到眼镜，
     * 用户无法预期；手动切换语义明确、行为可预测。
     *
     * 由「乐奇聊天 → 工具」页切换（项目约定：新增开关默认放该页）。
     */
    var chatLocalOnlyEnabled: Boolean = false
        private set

    /**
     * 乐奇聊天「图像理解」开关（**默认关**）。
     *
     * - 关：拍照问 AI 走本地 OCR（PP-OCRv4）把图转成文字再交给模型。不依赖模型能力，
     *   但只留下文字 —— 版式、图形、颜色、空间关系全部丢失（图表/手写/乐谱会失真）。
     * - 开：照片以 `image_url`（data URL）直接进模型，保留视觉信息。
     *   **能否生效取决于当前配置的模型是否支持图像输入** —— 不支持时服务端会拒绝，
     *   我们会自动回退到 OCR 并在气泡里说明，不会让用户卡在失败状态。
     *
     * 由「乐奇聊天 AI 设置」页切换（连续对话开关的上方）。
     */
    var chatImageInputEnabled: Boolean = false
        private set

    /**
     * 乐奇聊天「过程区块默认展开」开关（**默认开**）。
     *
     * - 开：AI 回复上方的「过程」卡片（思考 / 工具调用时间线 + 本轮 token 成本）展开显示。
     * - 关：只显示一行标题，点一下才展开。
     *
     * 默认开是有依据的：这张卡片存在的理由就是回答"它到底做了什么"，
     * 默认收起等于把功能藏起来（`TraceBlock` 的注释里写着这条）。关掉它的场景是
     * **一轮里工具调用很多**——例如连着放几首歌，过程卡片会把对话列表撑得很长。
     *
     * 由「乐奇聊天 AI 设置」页切换（图像理解开关的下方）。
     */
    var chatExpandTraceEnabled: Boolean = true
        private set

    override fun onCreate() {
        super.onCreate()

        // 提前注册 BouncyCastle Provider（鸿蒙 4.2 等系统可能移除默认 RSA Provider）
        initBouncyCastle()

        // 初始化本地化管理器并应用已保存的语言设置
        LocalizationManager.init(this)
        LocalizationManager.applyLocale(LocalizationManager.getCurrentLocaleCode())

        // 启动期一次性能力探测（L0 platform/ 的 ROM/SAW/SDK 能力快照，供各处 hook 降级判断）
        com.rokidlab.phone.platform.CapabilityProbe.refresh(this)

        // 手机端工具确认通道：需要用户点头的工具（发短信/删文件/装包/第三方 MCP 工具）在
        // **眼镜不可用时**（本机模式、未连接）由它问一次；眼镜在线时仍走眼镜通道（眼镜优先）。
        // 必须在启动期硬接线：不注册时本机模式下的确认能力**整体失效且不报错**
        // （闸门会走"问不到用户"分支，按工具声明的 ToolConfirmPolicy 分流）。
        com.rokidlab.phone.ai.PhoneToolConfirmChannel.init(this)

        // 启动自检：工具风险表是否漏登记（漏登记会让该工具被误判为外部副作用而被拦下，
        // 历史事故：save_code_file 漏登记 → AIUI 生成整链路失败）。应为空。
        runCatching {
            val missingRisk = com.rokidlab.phone.ai.ToolRiskMap.unregisteredTools()
            if (missingRisk.isNotEmpty()) {
                Log.e(TAG, "!! 以下工具未登记风险档位（将按 LOCAL_SIDE_EFFECT 放行，请尽快补登记）: $missingRisk")
            } else {
                Log.i(TAG, "tool risk table OK: ${com.rokidlab.phone.ai.ToolRegistry.toolList.size} tools all registered")
            }
        }

        // 自持 proot 环境自检（POC-2）：验证 app 域能否 execve `nativeLibraryDir` 里的随包二进制。
        // 这是「本机 Linux 用户态执行环境」唯一未验证的前提（技能 android-proot-selfhost §9.1）。
        // 走 logcat（tag ProotShell）而不是 LogCollector —— 后者只进内存环形缓冲、不写 logcat，
        // 而这正是需要从 adb 侧读到的东西。失败信息自带 errno，可直接区分
        // 「SELinux 域不许 exec（EACCES）」与「二进制不在位（ENOENT）」。放后台线程，不拖冷启动。
        com.rokidlab.phone.util.namedThread("proot-selftest", daemon = true, start = true) {
            runCatching { com.rokidlab.phone.platform.ProotShell.startupCheck(this) }
                .onFailure { Log.w(TAG, "proot selftest crashed: ${it.message}") }
        }

        // 网页预览孤儿清扫：App 被杀时常驻的 proot/服务进程会被遗留（野进程占着端口耗 CPU）。
        // 每个预览的 proot cmdline 里都有唯一脚本标记 rl-preview-，冷启动按标记清一次
        // （见 WebPreviewManager.sweepOrphans）。放后台线程，不拖冷启动。
        com.rokidlab.phone.util.namedThread("preview-sweep", daemon = true, start = true) {
            runCatching { com.rokidlab.phone.platform.WebPreviewManager.sweepOrphans(this) }
                .onFailure { Log.w(TAG, "preview sweep failed: ${it.message}") }
        }

        // Agent 会话记忆（事件流落盘）：必须先于 ChatStateHolder —— 后者的 bootstrap
        // 读完会话索引后会调 AgentSessionManager.bindSession()，那时需要 appContext 已就位
        com.rokidlab.phone.ai.AgentSessionManager.init(this)

        // 加载聊天历史落盘记录（App 重启后恢复对话）
        com.rokidlab.phone.store.ChatStateHolder.init(this)

        // 首次启动落盘内置技能（aiui-dev 等，仅当本地不存在；不覆盖用户编辑）
        com.rokidlab.phone.ai.SkillRegistry.seedBundledSkills(this)

        // 清洗测试期遗留的废弃 AIUI agent（"我是黑客"）：先触发注册表过滤落盘，
        // 再异步清理本地/眼镜端 .aix（眼镜未连接时静默跳过）
        runCatching {
            com.rokidlab.phone.ai.AiuiAppRegistry.list(this)
            com.rokidlab.phone.ai.AiuiAppRegistry.purgeObsoleteFiles(this)
        }

        // MCP 服务器自动重连：syncAll 是**同步阻塞**的（每个 server 2 个 HTTP 请求），
        // 因此必须离开主线程；放在启动末尾异步跑，不拖慢冷启动。
        //
        // ★ 不在这里做的话，MCP 工具**只有用户打开「MCP 服务器」设置页那一刻**才会连接 ——
        // App 重启后直接进对话，模型拿到的 MCP 工具清单是空的，用户侧表现为
        // 「昨天配好的 MCP 今天不生效，去设置页点一下又好了」。装配侧（DOMAIN_MCP 的
        // 活跃性过滤）依赖的正是这里建立的连接状态。
        // 单个 server 失败不影响其它（各自把错误记进自己的 ServerState），故不抛出。
        com.rokidlab.phone.util.namedThread("mcp-autosync", daemon = true, start = true) {
            runCatching { com.rokidlab.phone.ai.mcp.McpRegistry.syncAll(this) }
                .onFailure { Log.w(TAG, "MCP auto-sync failed: ${it.message}") }
        }

        hidManager = BluetoothHidManager(this)

        routeManager = ConnectionRouteManager(this)

        // WiFi 接入/断开即时失效线路缓存：接入时清掉仍指向蓝牙的旧线路，断开时改走蓝牙隧道。
        // （眼镜侧「离开 WiFi」不影响手机侧网络，此回调不触发，由 noteWifiFailure 失败驱动兜底。）
        registerWifiRouteCacheInvalidator()

        timerScheduler = TimerScheduler(this)

        prefs = getSharedPreferences("rokidbrew", MODE_PRIVATE)
        
        keepAliveEnabled = prefs.getBoolean(PREFS_KEEP_ALIVE, true)

        chatLocalOnlyEnabled = prefs.getBoolean(PREFS_CHAT_LOCAL_ONLY, false)

        chatImageInputEnabled = prefs.getBoolean(PREFS_CHAT_IMAGE_INPUT, false)

        chatExpandTraceEnabled = prefs.getBoolean(PREFS_CHAT_EXPAND_TRACE, true)
        
        // 从 SharedPreferences 恢复眼镜 IP（单一数据源 glasses_ip，兼容历史多键与 adb_prefs）
        val adbLegacyIp = getSharedPreferences("adb_prefs", MODE_PRIVATE).getString("ip", "") ?: ""
        val restoredIp = prefs.getString(GLASSES_IP_KEY, "")?.takeIf { it.isNotBlank() }
            ?: prefs.getString("screen_mirror_ip", "")?.takeIf { it.isNotBlank() }
            ?: prefs.getString("phone_mirror_ip", "")?.takeIf { it.isNotBlank() }
            ?: prefs.getString("file_manager_ip", "")?.takeIf { it.isNotBlank() }
            ?: adbLegacyIp.takeIf { it.isNotBlank() }
        glassesIp = restoredIp ?: "192.168.1.168"
        // 固化到单一数据源，并同步历史键，保证升级后所有界面一致
        setGlassesIp(glassesIp)
        // 仅当来自持久化（真实来源）时才算「已确认」；落到占位默认值则保持未确认，
        // 让长连接入口先等眼镜端上报 IP 再探测 WiFi（修复首启不切 WiFi）
        glassesIpConfirmed = restoredIp != null
        phoneMirrorPort = prefs.getString("phone_mirror_port", "7654") ?: "7654"

        // 恢复持久化安装标记（仅保留标记，不等同于当前已安装）
        rokidLinkEverInstalled = prefs.getBoolean("rokidlink_ever_installed", false)

        // 创建通知渠道（必须提前创建，否则手机系统设置中通知开关不可用）
        createNotificationChannels()

        // 进程级前台判定：权限/界面拉起相关的逻辑（BAL 豁免判断）依赖"本应用当前有没有界面在前台"，
        // 而 AOSP 没有公开 API（getRunningAppProcesses 在 Android 11+ 已受限）→ 自建计数。
        AppForegroundTracker.install(this)
    }

    /**
     * 监听 WiFi 接入/断开，即时清除线路缓存。
     *
     * **断开**与 [ConnectionRouteManager.noteWifiFailure] 互补：本回调覆盖「手机侧」WiFi 断开（即时），
     * 失败驱动失效覆盖「眼镜侧」离网（下一次操作时）。两者都失败时最多退化到 60s 缓存 TTL。
     *
     * **接入**此前漏了 —— 于是 WiFi 刚连上时缓存里仍是蓝牙线路（最长 60s 有效），
     * 窗口期内的操作会全部继续走蓝牙隧道（「WiFi 都连上了为什么还是走蓝牙」正是这个缺口）。
     * 这里对称处理：WiFi 一接入就清缓存，并让共享 ADB 会话立刻重探首选线路。
     */
    private fun registerWifiRouteCacheInvalidator() {
        runCatching {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            // 按 WiFi 传输类型匹配，而不是只看「默认网络」：眼镜所在的 WiFi 常常是无外网的
            // 局域网（手机热点 / 眼镜直连），此时手机可能仍把蜂窝当默认网络，
            // 只盯默认网络会漏掉「眼镜 WiFi 接入 / 断开」这两个关键事件。
            val wifiRequest = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            cm.registerNetworkCallback(wifiRequest, object : ConnectivityManager.NetworkCallback() {
                override fun onLost(network: Network) {
                    routeManager.clearRouteCache()
                    LogCollector.i(TAG, "WiFi 已断开，已清除线路缓存（下次操作重新探测线路）")
                }

                override fun onUnavailable() {
                    routeManager.clearRouteCache()
                    LogCollector.i(TAG, "WiFi 不可用，已清除线路缓存（下次操作重新探测线路）")
                }

                override fun onAvailable(network: Network) {
                    routeManager.clearRouteCache()
                    LogCollector.i(TAG, "WiFi 已接入，已清除线路缓存（下次操作优先走 WiFi）")
                    // 共享 ADB 会话可能正挂在蓝牙隧道上，且可能已跑了很多轮轮询而不会被重新 resolve：
                    // 立刻重探一次首选线路，探通后下一次取用即切换到 WiFi。
                    if (::cxrL.isInitialized) cxrL.onWifiMaybeAvailable()
                }
            })
        }.onFailure {
            Log.w(TAG, "注册网络回调失败（WiFi 断线将退化为失败驱动失效）: ${it.message}")
        }
    }

    private fun createNotificationChannels() {
        try {
            val nm = getSystemService(NotificationManager::class.java)

            // 1. 定时消息通知渠道
            val timerChannel = NotificationChannel(
                "timer_notify", getString(R.string.timer_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.timer_channel_desc)
            }
            nm.createNotificationChannel(timerChannel)

            // 2. 手机投屏前台服务通知渠道（Android 14+ 要求 FGS 有对应渠道）
            val phoneMirrorChannel = NotificationChannel(
                "phone_mirror_fgs", getString(R.string.phone_mirror_notification_channel),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.phone_mirror_notification_channel_desc)
            }
            nm.createNotificationChannel(phoneMirrorChannel)

            // 3. 后台保活前台服务通知渠道（常驻通知栏）
            val keepAliveChannel = NotificationChannel(
                CHANNEL_KEEP_ALIVE_FGS, getString(R.string.keep_alive_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.keep_alive_channel_desc)
            }
            nm.createNotificationChannel(keepAliveChannel)

            // 4. 权限提醒渠道：后台且无悬浮窗时，授权入口拉不起界面 —— 只能退通知栏让用户点一下。
            //    这条渠道是"自举死锁"的破解通道（点通知 = 用户发起 = BAL 放行），必须提前建好。
            val permissionChannel = NotificationChannel(
                PermissionBridge.CHANNEL_ID, getString(R.string.permission_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.permission_channel_desc)
            }
            nm.createNotificationChannel(permissionChannel)
        } catch (_: Exception) { }
    }

    /** 切换后台保活开关：开启时启动前台保活服务，关闭时停止 */
    fun setKeepAliveEnabled(enabled: Boolean) {
        keepAliveEnabled = enabled
        prefs.edit().putBoolean(PREFS_KEEP_ALIVE, enabled).apply()
        if (enabled) {
            startKeepAliveService()
        } else {
            stopKeepAliveService()
        }
    }

    /**
     * 切换乐奇聊天「本机模式」（见 [chatLocalOnlyEnabled]）。
     *
     * 只落盘 + 更新内存标志：该开关在**每次发送时**读取，因此下一个消息立即生效，
     * 不需要重连/重启任何东西，也不会打断正在进行的对话。
     */
    fun setChatLocalOnlyEnabled(enabled: Boolean) {
        chatLocalOnlyEnabled = enabled
        prefs.edit().putBoolean(PREFS_CHAT_LOCAL_ONLY, enabled).apply()
        Log.i(TAG, "chat local-only mode -> $enabled")
    }

    /**
     * 切换乐奇聊天「图像理解」（见 [chatImageInputEnabled]）。
     *
     * 与 [setChatLocalOnlyEnabled] 同样是"只落盘 + 改内存标志"，在**每次拍照**时读取，
     * 因此下一步操作就生效，不需要重连或重启。
     */
    fun setChatImageInputEnabled(enabled: Boolean) {
        chatImageInputEnabled = enabled
        prefs.edit().putBoolean(PREFS_CHAT_IMAGE_INPUT, enabled).apply()
        Log.i(TAG, "chat image input -> $enabled")
    }

    /**
     * 切换乐奇聊天「过程区块默认展开」（见 [chatExpandTraceEnabled]）。
     *
     * 只落盘 + 改内存标志，聊天页每次进入时读取 —— 不需要重启，也不会打断正在进行的对话。
     */
    fun setChatExpandTraceEnabled(enabled: Boolean) {
        chatExpandTraceEnabled = enabled
        prefs.edit().putBoolean(PREFS_CHAT_EXPAND_TRACE, enabled).apply()
        Log.i(TAG, "chat expand trace -> $enabled")
    }

    /** 启动后台保活前台服务（App 打开时调用；保活开启时后台能力常驻） */
    fun startKeepAliveService() {
        if (!keepAliveEnabled) return
        try {
            val intent = Intent(this, com.rokidlab.phone.keepalive.LabKeepAliveService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            Log.i(TAG, "LabKeepAliveService start requested")
        } catch (e: Exception) {
            Log.w(TAG, "startKeepAliveService failed: ${e.message}")
        }
    }

    /** 停止后台保活前台服务 */
    fun stopKeepAliveService() {
        try {
            stopService(Intent(this, com.rokidlab.phone.keepalive.LabKeepAliveService::class.java))
            Log.i(TAG, "LabKeepAliveService stop requested")
        } catch (e: Exception) {
            Log.w(TAG, "stopKeepAliveService failed: ${e.message}")
        }
    }

    /** 注册 BouncyCastle Security Provider（直接 import，不依赖反射） */
    private fun initBouncyCastle() {
        try {
            val bcProvider = org.bouncycastle.jce.provider.BouncyCastleProvider()
            Security.insertProviderAt(bcProvider, 1)
            Log.i(TAG, "BouncyCastle Provider registered at position 1")
        } catch (e: Exception) {
            Log.w(TAG, "BouncyCastle registration failed: ${e.message}")
        }
    }

    fun setCxrL(session: CxrLHiRokidSession) {
        cxrL = session
        // 把「应用单例持有的当前会话」注入工具确认通道做兜底：
        // 该通道的 liveSessions 登记会在 cleanup()（重连/重授权每轮都调）后被清空且不再回填，
        // 只靠登记表会把"眼镜在线"误判成"未连接"（实测 capSupport=true 但 session=false）。
        com.rokidlab.phone.ai.GlassToolConfirmChannel.appSessionProvider = { if (::cxrL.isInitialized) cxrL else null }

        // 「过程」（思考 / 工具调用）全局汇聚点：落进聊天记录，由聊天窗口渲染成过程卡片。
        //
        // 注册在 App 层而不是聊天界面，是因为 AI 有 4 条入口 —— 打字 / 眼镜语音 ASR /
        // 拍照答题 / 定时自主任务。界面级注册只能覆盖"界面自己发起"的那条路，
        // **实测就漏掉了眼镜语音**（用户反馈"眼镜上说，手机上没显示思考链路，但打字可以"）。
        // ChatStateHolder 是 App 级单例，与当前停留在哪个页面无关，因此停在别的页面时
        // 用眼镜提问，过程照样记录、回到聊天页即可看到。
        session.setAgentTraceSink(
            onStep = { step -> com.rokidlab.phone.store.ChatStateHolder.upsertTrace(step) },
            onFinish = { failed -> com.rokidlab.phone.store.ChatStateHolder.finishTrace(failed) },
        )
    }

    /** 会话是否已创建（跨类访问 lateinit 的 isInitialized） */
    fun hasCxrL(): Boolean = ::cxrL.isInitialized

    /**
     * 无界面会话自愈：保活服务被 START_STICKY 重建 / 开机自启时，进程内没有 Activity，
     * 此方法用 Application Context 重建 CXR-L 会话并启动 ASR 双通道（RFCOMM 推送 + ADB 兜底轮询）。
     *
     * 背景：SwipeUpClean（最近任务上滑）会绕过 FGS 直接杀进程，系统虽在 ~1s 内重建了保活服务，
     * 但旧实现只重建了空壳服务 —— CXR-L 会话/ASR 链路只在 MainActivity 创建，导致状态栏图标还在、
     * 眼镜语音却无响应（"假活"）。
     *
     * 策略：
     *  - token 从 SharedPreferences 自动恢复，无需界面；
     *  - ASR 文字上行后由对话链路按需自建 CXR CUSTOMAPP 连接，此处不主动 launchApp，
     *    避免在眼镜上无谓拉起界面；
     *  - 未授权（无 token）时仅登记会话、不启动 ASR 桥（没有可恢复的语音链路，也省隧道电耗）；
     *  - 会话已存在（同进程内服务重启）时仅幂等确保 ASR 桥运行。
     */
    fun ensureHeadlessSession() {
        if (!keepAliveEnabled) return
        try {
            if (::cxrL.isInitialized) {
                runCatching { cxrL.asrBridge.start() }
                return
            }
            val hostApp = runCatching {
                val p = getSharedPreferences("rokidbrew_preferences", MODE_PRIVATE)
                RokidHostApp.fromId(p.getString("rokid_host_app", null))
            }.getOrDefault(RokidHostApp.DEFAULT)
            val session = CxrLHiRokidSession(
                context = this,
                onStatus = { msg -> LogCollector.i(TAG, msg) },
                onBusyChanged = { },
                onConnectionChanged = { },
                initialHostApp = hostApp,
                authLauncher = null,
                appScope = appScope,
            )
            setCxrL(session)
            if (!session.hasAuthorization()) {
                Log.i(TAG, "ensureHeadlessSession: no saved token, ASR bridge not started (not authorized yet)")
                return
            }
            runCatching { session.asrBridge.start() }
            Log.i(TAG, "headless CXR-L session restored, ASR bridge started")
        } catch (e: Exception) {
            Log.w(TAG, "ensureHeadlessSession failed: ${e.message}")
        }
    }

    /** 统一设置眼镜 IP：所有模块共用，写入单一数据源并同步历史键 */
    fun setGlassesIp(ip: String) {
        glassesIp = ip
        // 眼镜端上报 / 用户输入均为可信来源 → 标记已确认
        if (ip.isNotBlank()) glassesIpConfirmed = true
        // 同步可观察 state：眼镜端推送新 IP 时四个已打开的输入框自动重组回填
        glassesIpState.value = ip
        prefs.edit().apply {
            putString(GLASSES_IP_KEY, ip)
            putString("screen_mirror_ip", ip)
            putString("phone_mirror_ip", ip)
            putString("file_manager_ip", ip)
        }.apply()
        // 同步到 adb_prefs，兼容仍直接读该文件的历史逻辑
        getSharedPreferences("adb_prefs", MODE_PRIVATE).edit().putString("ip", ip).apply()
    }

    fun setPhoneMirrorPort(port: String) {
        phoneMirrorPort = port
        prefs.edit().putString("phone_mirror_port", port).apply()
    }

    fun setRokidLinkInstalled(installed: Boolean) {
        rokidLinkInstalled = installed
        // 持久化标记只增不减：安装成功则永久记录
        if (installed && !rokidLinkEverInstalled) {
            rokidLinkEverInstalled = true
            prefs.edit().putBoolean("rokidlink_ever_installed", true).apply()
        }
    }

    fun cleanup() {
        timerScheduler.shutdown()
        hidManager.destroy()
        routeManager.stopTunnel()
        if (::cxrL.isInitialized) {
            cxrL.cleanup()
        }
    }
}
