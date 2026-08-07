package com.rokidlab.phone.app

import com.rokidlab.phone.connection.ConnectionRoute
import com.rokidlab.phone.connection.ConnectionRouteManager
import com.rokidlab.phone.glasses.CxrLHiRokidSession
import com.rokidlab.phone.hid.BluetoothHidManager
import com.rokidlab.phone.util.LocalizationManager
import com.rokidlab.phone.R
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.runBlocking
import java.security.Provider
import java.security.Security

class LabApplication : Application() {
    companion object {
        private const val TAG = "LabApplication"
    }
    lateinit var cxrL: CxrLHiRokidSession
        private set

    lateinit var hidManager: BluetoothHidManager
        private set

    lateinit var routeManager: ConnectionRouteManager
        private set

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

    override fun onCreate() {
        super.onCreate()

        // 提前注册 BouncyCastle Provider（鸿蒙 4.2 等系统可能移除默认 RSA Provider）
        initBouncyCastle()

        // 初始化本地化管理器并应用已保存的语言设置
        LocalizationManager.init(this)
        LocalizationManager.applyLocale(LocalizationManager.getCurrentLocaleCode())

        hidManager = BluetoothHidManager(this)

        routeManager = ConnectionRouteManager(this)

        prefs = getSharedPreferences("rokidbrew", MODE_PRIVATE)
        
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
        } catch (_: Exception) { }
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
        hidManager.destroy()
        routeManager.stopTunnel()
        if (::cxrL.isInitialized) {
            cxrL.cleanup()
        }
    }
}
