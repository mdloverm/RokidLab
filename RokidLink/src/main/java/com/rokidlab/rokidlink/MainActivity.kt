package com.rokidlab.rokidlink

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.TextView
import android.widget.Toast
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

class MainActivity : Activity() {
    private lateinit var statusText: TextView
    private lateinit var ipText: TextView
    private lateinit var dot: View

    /** ADB 启用线程引用，用于 onDestroy 时中断 */
    private var enableAdbThread: Thread? = null

    // WiFi 状态实时监听
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            Log.i(TAG, getString(R.string.log_network_recovered))
            Handler(Looper.getMainLooper()).post {
                startSetup()
            }
        }

        override fun onLost(network: Network) {
            Log.i(TAG, getString(R.string.log_wifi_disconnected))
            Handler(Looper.getMainLooper()).post {
                setDotColor(DOT_ERROR)
                statusText.text = getString(R.string.status_wifi_disconnected)
                ipText.text = "0.0.0.0"
                openWifiSettings()
            }
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                Log.i(TAG, getString(R.string.log_network_switched))
                Handler(Looper.getMainLooper()).post {
                    setDotColor(DOT_ERROR)
                    statusText.text = getString(R.string.status_wifi_disconnected)
                    ipText.text = "0.0.0.0"
                    openWifiSettings()
                }
            }
        }
    }

    // 用于接收 PhoneMirrorActivity 发来的关闭信号
    private val finishMainReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.i(TAG, "收到关闭信号，结束 MainActivity")
            finish()
        }
    }

    companion object {
        private const val TAG = "RokidLink"
        private const val REQUEST_WIFI = 100

        /** PhoneMirrorActivity 启动时发出的广播 Action，用于关闭本页面 */
        const val ACTION_FINISH_MAIN = "com.rokidlab.rokidlink.FINISH_MAIN"

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

        // 注册 WiFi 实时监听
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.registerNetworkCallback(
            NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
                .build(),
            networkCallback
        )

        setDotColor(DOT_IDLE)
        startSetup()

        // 注册关闭广播接收器，当 PhoneMirrorActivity 启动时自动结束本页面
        registerReceiver(finishMainReceiver, IntentFilter(ACTION_FINISH_MAIN))
    }

    override fun onDestroy() {
        super.onDestroy()
        // 中断 ADB 启用线程
        enableAdbThread?.interrupt()
        enableAdbThread = null
        // 注销网络监听
        runCatching {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.unregisterNetworkCallback(networkCallback)
        }
        // 注销关闭广播接收器
        runCatching { unregisterReceiver(finishMainReceiver) }
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
        statusText.text = getString(R.string.status_checking_wifi)
        
        // 1. 检查 WiFi 硬件开关是否开启
        if (!isWifiEnabled()) {
            statusText.text = getString(R.string.status_wifi_disabled)
            setDotColor(DOT_ERROR)
            ipText.text = "0.0.0.0"
            openWifiSettings()
            return
        }
        
        // 3. 检查是否已连接到 WiFi 网络并获取有效 IP
        val ip = getIPAddress()
        ipText.text = ip
        
        if (!isWifiConnected() || ip == "0.0.0.0") {
            statusText.text = getString(R.string.status_not_connected_wifi)
            setDotColor(DOT_ERROR)
            openWifiSettings()
            return
        }

        statusText.text = getString(R.string.status_checking_adb)
        if (isAdbTcpListening()) {
            setDotColor(DOT_READY)
            statusText.text = getString(R.string.status_ready)
            return
        }

        statusText.text = getString(R.string.status_enabling_adb)
        setDotColor(DOT_CHECKING)
        enableAdbTcp()
    }

    private fun isWifiEnabled(): Boolean {
        return try {
            val wifiManager = getSystemService(WIFI_SERVICE) as? WifiManager ?: return false
            wifiManager.isWifiEnabled
        } catch (_: SecurityException) {
            // 缺少 CHANGE_WIFI_STATE 权限时回退到 ConnectivityManager
            val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        }
    }

    private fun isWifiConnected(): Boolean {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
               caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    private fun openWifiSettings() {
        Toast.makeText(this, getString(R.string.toast_enable_wifi), Toast.LENGTH_LONG).show()
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
            Log.e(TAG, getString(R.string.log_setprop_failed, e.message))
        }
    }

    private fun enableAdbTcp() {
        val thread = Thread {
            Log.i(TAG, getString(R.string.log_try_enable_adb_tcp))

            setAdbProperty()

            for (attempt in 1..3) {
                if (Thread.currentThread().isInterrupted) return@Thread
                Log.i(TAG, getString(R.string.log_try_attempt, attempt))
                tryExec("setprop", "ctl.restart", "adbd")

                for (wait in 1..4) {
                    if (Thread.currentThread().isInterrupted) return@Thread
                    Thread.sleep(1000)
                    if (isAdbTcpListening()) {
                        Handler(Looper.getMainLooper()).post {
                            setDotColor(DOT_READY)
                            statusText.text = getString(R.string.status_ready)
                        }
                        Log.i(TAG, getString(R.string.log_adb_tcp_enabled, attempt, wait))
                        return@Thread
                    }
                }
            }

            Handler(Looper.getMainLooper()).post {
                setDotColor(DOT_ERROR)
                statusText.text = getString(R.string.status_adb_failed)
                Log.w(TAG, getString(R.string.log_retry_failed))
            }
        }.apply {
            name = "enable-adb-tcp"
            enableAdbThread = this
            start()
        }
    }

    private fun tryExec(vararg cmd: String) {
        try {
            val p = Runtime.getRuntime().exec(cmd)
            p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
            p.destroy()
        } catch (e: Exception) {
            Log.d(TAG, getString(R.string.log_command_failed, cmd.joinToString(" ")))
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
            Log.e(TAG, getString(R.string.log_get_ip_failed), e)
        }
        return "0.0.0.0"
    }
}
