package com.rokidlab.screenservice

import android.app.Activity
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import com.rokid.cxr.CXRServiceBridge
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

class MainActivity : Activity() {
    private lateinit var statusText: TextView
    private lateinit var ipText: TextView
    private lateinit var dot: View
    private lateinit var btnReceiveMirror: Button
    private lateinit var mirrorStatusText: TextView
    private val cxrBridge = CXRServiceBridge()

    companion object {
        private const val TAG = "GlassesScreenService"
        private const val REQUEST_WIFI = 100
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        ipText = findViewById(R.id.ipText)
        dot = findViewById(R.id.dot)
        btnReceiveMirror = findViewById(R.id.btnReceiveMirror)
        mirrorStatusText = findViewById(R.id.mirrorStatusText)

        btnReceiveMirror.setOnClickListener {
            startActivity(Intent(this, PhoneMirrorActivity::class.java))
        }

        setDotColor(0xFF555555.toInt())

        // 初始化 CXR-S 桥接，让 CXR-L 能识别本应用并正确查询安装状态
        runCatching {
            cxrBridge
            Log.i(TAG, "CXR-S 桥接已初始化")
        }.onFailure { e ->
            Log.e(TAG, "CXR-S 桥接初始化失败: ${e.message}", e)
        }

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
        if (!isWifiConnected()) {
            statusText.text = "未连接 WiFi"
            setDotColor(0xFFFF5722.toInt())
            ipText.text = "未连接"
            openWifiSettings()
            return
        }

        val ip = getIPAddress()
        ipText.text = ip

        statusText.text = "检查 ADB 状态..."
        if (isAdbTcpListening()) {
            setDotColor(0xFF4CAF50.toInt())
            statusText.text = "已就绪"
            return
        }

        statusText.text = "正在开启 ADB..."
        setDotColor(0xFFFFA500.toInt())
        enableAdbTcp()
    }

    private fun isWifiConnected(): Boolean {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
               caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    private fun openWifiSettings() {
        Toast.makeText(this, "请连接 WiFi", Toast.LENGTH_LONG).show()
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
                if (networkInterface.name == "wlan0" || networkInterface.name.startsWith("ap")) {
                    val addresses = networkInterface.inetAddresses
                    while (addresses.hasMoreElements()) {
                        val address = addresses.nextElement()
                        if (address.isSiteLocalAddress && !address.isLoopbackAddress) {
                            return address.hostAddress ?: "未知"
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "获取 IP 失败", e)
        }
        return "未连接"
    }
}
