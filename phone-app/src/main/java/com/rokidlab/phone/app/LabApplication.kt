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
import kotlinx.coroutines.runBlocking
import java.security.Provider
import java.security.Security

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

    // 运行时 RokidLink 安装状态缓存
    var rokidLinkInstalled: Boolean? = null

    // 持久化安装标记：一经安装成功永不清除
    var rokidLinkEverInstalled: Boolean = false
        private set

    // ===== 全局 IP 地址管理 =====
    var screenMirrorIp: String = "192.168.1.168"
        private set
    var phoneMirrorIp: String = "192.168.1.168"
        private set
    var phoneMirrorPort: String = "7654"
        private set
    var fileManagerIp: String = "192.168.1.168"
        private set

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

        // 加载聊天历史落盘记录（App 重启后恢复对话）
        com.rokidlab.phone.store.ChatStateHolder.init(this)

        // 首次启动落盘内置技能（aiui-dev 等，仅当本地不存在；不覆盖用户编辑）
        com.rokidlab.phone.ai.SkillRegistry.seedBundledSkills(this)

        hidManager = BluetoothHidManager(this)

        routeManager = ConnectionRouteManager(this)

        timerScheduler = TimerScheduler(this)

        prefs = getSharedPreferences("rokidbrew", MODE_PRIVATE)
        
        keepAliveEnabled = prefs.getBoolean(PREFS_KEEP_ALIVE, true)
        
        // 从 SharedPreferences 恢复 IP 地址
        screenMirrorIp = prefs.getString("screen_mirror_ip", "192.168.1.168") ?: "192.168.1.168"
        phoneMirrorIp = prefs.getString("phone_mirror_ip", "192.168.1.168") ?: "192.168.1.168"
        phoneMirrorPort = prefs.getString("phone_mirror_port", "7654") ?: "7654"
        fileManagerIp = prefs.getString("file_manager_ip", "192.168.1.168") ?: "192.168.1.168"

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
    }

    /** 会话是否已创建（跨类访问 lateinit 的 isInitialized） */
    fun hasCxrL(): Boolean = ::cxrL.isInitialized

    fun setScreenMirrorIp(ip: String) {
        screenMirrorIp = ip
        prefs.edit().putString("screen_mirror_ip", ip).apply()
    }

    fun setPhoneMirrorIp(ip: String) {
        phoneMirrorIp = ip
        prefs.edit().putString("phone_mirror_ip", ip).apply()
    }

    fun setPhoneMirrorPort(port: String) {
        phoneMirrorPort = port
        prefs.edit().putString("phone_mirror_port", port).apply()
    }

    fun setFileManagerIp(ip: String) {
        fileManagerIp = ip
        prefs.edit().putString("file_manager_ip", ip).apply()
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
