package com.rokidlab.phone.app

import com.rokidlab.phone.connection.ConnectionRoute
import com.rokidlab.phone.connection.ConnectionRouteManager
import com.rokidlab.phone.adb.TimerScheduler
import com.rokidlab.phone.glasses.CxrLHiRokidSession
import com.rokidlab.phone.hid.BluetoothHidManager
import com.rokidlab.phone.util.LocalizationManager
import com.rokidlab.phone.R
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
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

    override fun onCreate() {
        super.onCreate()

        // 提前注册 BouncyCastle Provider（鸿蒙 4.2 等系统可能移除默认 RSA Provider）
        initBouncyCastle()

        // 初始化本地化管理器并应用已保存的语言设置
        LocalizationManager.init(this)
        LocalizationManager.applyLocale(LocalizationManager.getCurrentLocaleCode())

        // 启动期一次性能力探测（L0 platform/ 的 ROM/SAW/SDK 能力快照，供各处 hook 降级判断）
        com.rokidlab.phone.platform.CapabilityProbe.refresh(this)

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

        // 加载聊天历史落盘记录（App 重启后恢复对话）
        com.rokidlab.phone.store.ChatStateHolder.init(this)

        // 首次启动落盘内置技能（aiui-dev 等，仅当本地不存在；不覆盖用户编辑）
        com.rokidlab.phone.ai.SkillRegistry.seedBundledSkills(this)

        hidManager = BluetoothHidManager(this)

        routeManager = ConnectionRouteManager(this)

        timerScheduler = TimerScheduler(this)

        prefs = getSharedPreferences("rokidbrew", MODE_PRIVATE)
        
        keepAliveEnabled = prefs.getBoolean(PREFS_KEEP_ALIVE, true)
        
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
    }

    /** 会话是否已创建（跨类访问 lateinit 的 isInitialized） */
    fun hasCxrL(): Boolean = ::cxrL.isInitialized

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
