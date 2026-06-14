package com.rokidlab.rokidlink

import android.app.Activity
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.TextView
import android.widget.Toast
import com.rokid.cxr.CXRServiceBridge
import com.rokid.cxr.Caps
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

class MainActivity : Activity() {
    private lateinit var statusText: TextView
    private lateinit var ipText: TextView
    private lateinit var dot: View
    private val cxrBridge = CXRServiceBridge()

    companion object {
        private const val TAG = "RokidLink"
        private const val REQUEST_WIFI = 100

        // 状态灯颜色（Mondrian Noir）
        private const val DOT_IDLE    = 0xFF666666.toInt()  // 灰 — 初始
        private const val DOT_CHECKING= 0xFFFFD200.toInt()  // 黄 — 检查中
        private const val DOT_READY   = 0xFF00CC66.toInt()  // 绿 — 已就绪
        private const val DOT_ERROR   = 0xFFFF3333.toInt()  // 红 — 异常
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        ipText = findViewById(R.id.ipText)
        dot = findViewById(R.id.dot)

        // 订阅 CXR-L 手机投屏自动启动命令
        val subResult = cxrBridge.subscribe("phone_mirror_launch",
            object : CXRServiceBridge.MsgCallback {
                override fun onReceive(from: String, caps: Caps, data: ByteArray?) {
                    Log.i(TAG, "收到手机投屏启动命令，自动跳转投屏页面")
                    runOnUiThread {
                        startActivity(Intent(this@MainActivity, PhoneMirrorActivity::class.java))
                    }
                }
            })
        Log.i(TAG, "订阅 phone_mirror_launch 结果: $subResult")

        setDotColor(DOT_IDLE)
        startSetup()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_WIFI) {
            Handler(Looper.getMainLooper()).postDelayed({
                startSetup()
            }, 1000)
        }
    }

    private fun startSetup() {
        statusText.text = "检查 WiFi..."
        
        // 1. 检查 WiFi 硬件开关是否开启
        if (!isWifiEnabled()) {
            statusText.text = "WiFi 未开启"
            setDotColor(DOT_ERROR)
            ipText.text = "0.0.0.0"
            openWifiSettings()
            return
        }
        
        // 2. 显示 IP（无论是否已连接网络，获取真实 IP 地址）
        val ip = getIPAddress()
        ipText.text = ip
        
        // 3. 检查是否已连接到 WiFi 网络
        if (!isWifiConnected()) {
            statusText.text = "未连接 WiFi"
            setDotColor(DOT_ERROR)
            openWifiSettings()
            return
        }

        statusText.text = "检查 ADB 状态..."
        if (isAdbTcpListening()) {
            setDotColor(DOT_READY)
            statusText.text = "已就绪"
            return
        }

        statusText.text = "正在开启 ADB..."
        setDotColor(DOT_CHECKING)
        enableAdbTcp()
    }

    private fun isWifiEnabled(): Boolean {
        val wifiManager = getSystemService(WIFI_SERVICE) as? WifiManager ?: return false
        return wifiManager.isWifiEnabled
    }

    private fun isWifiConnected(): Boolean {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
               caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    private fun openWifiSettings() {
        Toast.makeText(this, "请在设置中开启并连接 WiFi", Toast.LENGTH_LONG).show()
        val intent = Intent(Settings.ACTION_WIFI_SETTINGS)
        startActivityForResult(intent, REQUEST_WIFI)
    }

    private fun setDotColor(color: Int) {
        val drawable = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setSize(10, 10)
            setColor(color)
        }
        dot.background = drawable
    }

    private fun isAdbTcpListening(): Boolean {
        var socket: Socket? = null
        return try {
            socket = Socket()
            socket.connect(InetSocketAddress("127.0.0.1", 5555), 500)
            socket.close()
            true
        } catch (e: Exception) {
            try { socket?.close() } catch (_: Exception) {}
            false
        }
    }

    private fun setAdbProperty() {
        try {
            Runtime.getRuntime().exec(arrayOf("setprop", "service.adb.tcp.port", "5555"))
            Thread.sleep(300)
        } catch (e: Exception) {
            Log.e(TAG, "setprop 失败: ${e.message}")
        }
    }

    private fun enableAdbTcp() {
        Thread {
            Log.i(TAG, "尝试开启 ADB TCP 模式...")

            setAdbProperty()

            for (attempt in 1..3) {
                Log.i(TAG, "尝试 #$attempt: ctl.restart adbd")
                tryExec("setprop", "ctl.restart", "adbd")

                for (wait in 1..4) {
                    Thread.sleep(1000)
                    if (isAdbTcpListening()) {
                        Handler(Looper.getMainLooper()).post {
                            setDotColor(0xFF4CAF50.toInt())
                            statusText.text = "已就绪"
                        }
                        Log.i(TAG, "ADB TCP 已开启（尝试 #$attempt 后 ${wait}s）")
                        return@Thread
                    }
                }
            }

            Handler(Looper.getMainLooper()).post {
                setDotColor(0xFFFF5722.toInt())
                statusText.text = "ADB 开启失败，请重启 App"
                Log.w(TAG, "重试 3 次后仍无法开启 ADB TCP")
            }
        }.start()
    }

    private fun tryExec(vararg cmd: String) {
        try {
            val p = Runtime.getRuntime().exec(cmd)
            p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
        } catch (e: Exception) {
            Log.d(TAG, "命令失败: ${cmd.joinToString(" ")}: ${e.message}")
        }
    }

    private fun getIPAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (networkInterface.isLoopback || !networkInterface.isUp) continue
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (!address.isLoopbackAddress && address is java.net.Inet4Address) {
                        return address.hostAddress ?: "0.0.0.0"
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "获取 IP 失败", e)
        }
        return "0.0.0.0"
    }
}
