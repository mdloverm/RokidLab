package com.rokidlab.phone

import android.app.Application
import android.content.SharedPreferences

class BrewApplication : Application() {
    lateinit var cxrL: CxrLHiRokidSession
        private set

    // 运行时 ScreenStream 安装状态缓存
    var screenStreamInstalled: Boolean? = null

    // 持久化安装标记：一经安装成功永不清除，防止离线查询覆盖为 false
    var screenStreamEverInstalled: Boolean = false
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
        prefs = getSharedPreferences("rokidbrew", MODE_PRIVATE)
        
        // 从 SharedPreferences 恢复 IP 地址
        screenMirrorIp = prefs.getString("screen_mirror_ip", "192.168.1.168") ?: "192.168.1.168"
        phoneMirrorIp = prefs.getString("phone_mirror_ip", "192.168.1.168") ?: "192.168.1.168"
        phoneMirrorPort = prefs.getString("phone_mirror_port", "7654") ?: "7654"
        fileManagerIp = prefs.getString("file_manager_ip", "192.168.1.168") ?: "192.168.1.168"

        // 恢复持久化安装标记
        screenStreamEverInstalled = prefs.getBoolean("screenstream_ever_installed", false)
        if (screenStreamEverInstalled) {
            screenStreamInstalled = true
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

    fun setScreenStreamInstalled(installed: Boolean) {
        screenStreamInstalled = installed
        prefs.edit().putBoolean("screenstream_installed", installed).apply()
        // 持久化标记只增不减：安装成功则永久记录
        if (installed && !screenStreamEverInstalled) {
            screenStreamEverInstalled = true
            prefs.edit().putBoolean("screenstream_ever_installed", true).apply()
        }
    }
}
