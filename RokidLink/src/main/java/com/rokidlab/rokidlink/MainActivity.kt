package com.rokidlab.rokidlink

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.pm.PackageManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.TextView
import com.rokid.cxr.CXRServiceBridge
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

class MainActivity : Activity() {
    private lateinit var statusText: TextView
    private lateinit var ipText: TextView
    private lateinit var dot: View

    /** ADB 启用线程引用，用于 onDestroy 时中断 */
    private var enableAdbThread: Thread? = null

    /** 是否已获得过窗口焦点（首次聚焦后启动后台服务，避免 FGS 被 ROM 拒绝） */
    private var hadWindowFocus = false

    /**
     * 显示模式：手机端「打开 RokidLink」时经 CXR 指令带 EXTRA_SHOW_UI 拉起本页，
     * 显示状态界面（WiFi IP/ADB 状态）供用户查看。平时被动自启（appStart/SDK 拉起）
     * 不带该标志 → 服务启动后自动退后台，不影响视线。
     */
    private var showUi = false

    /**
     * BLUETOOTH_CONNECT 授权请求在途：为 true 时禁止「隐形自退后台」。
     *
     * 隐形实例（!showUi）聚焦后约 2.3s 就 finish()，会把系统权限弹框一并取消 —— 真机实证
     * （2026-09-28 重装眼镜端）：13:00:28 弹出申请 → 13:00:33 finish() → 授权/拒绝回调
     * 均未触发，权限始终未授予；随之 BT 隧道 listen 抛 SecurityException、ASR 推送通道
     * 整条起不来（环境音/按键答题文字全推不上手机）。等用户答复后再退后台。
     */
    private var btPermissionPending = false

    // 网络状态实时监听（WiFi/以太网）
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
                // WiFi 断开时不强制跳转设置，更新状态显示即可
                val ip = getIPAddress()
                ipText.text = if (ip != "0.0.0.0") ip else getString(R.string.status_bt_ready)
                statusText.text = if (ip != "0.0.0.0") {
                    getString(R.string.status_wifi_connected, ip)
                } else {
                    getString(R.string.status_bt_ready_desc)
                }
                setDotColor(DOT_CHECKING)
            }
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            val hasWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                          caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            if (!hasWifi) {
                Log.i(TAG, getString(R.string.log_network_switched))
                Handler(Looper.getMainLooper()).post {
                    // 网络切换时不强制跳转设置，更新状态显示即可
                    val ip = getIPAddress()
                    ipText.text = getString(R.string.status_bt_ready)
                    statusText.text = getString(R.string.status_bt_ready_desc)
                    setDotColor(DOT_CHECKING)
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

        /** 蓝牙运行时权限（Android 12+ BLUETOOTH_CONNECT）申请请求码 */
        private const val REQ_BLUETOOTH_CONNECT = 200

        /**
         * 进程级标记：BLUETOOTH_CONNECT 已授予（授权成功置 true，避免同进程反复弹框）。
         * 拒绝后保持 false，下次冷启动可再次申请（系统层会抑制频繁弹框）。
         */
        @Volatile
        var btConnectGrantedOnce = false

        /**
         * 检查并申请蓝牙运行时权限 BLUETOOTH_CONNECT（Android 12+）。
         *
         * 蓝牙隧道（BtTunnelServer.listenUsingRfcommWithServiceRecord → BluetoothAdapter.getAddress）
         * 依赖该权限：缺失时眼镜端抛 SecurityException，隧道 / ASR push / WiFi adb(5555) 全部不可用。
         * CXR-L「重装眼镜端」/ 卸载重装会清空运行时授权，故启动时必须重新检查并按需申请。
         * Service 无法发起运行时权限请求，只能在 Activity 内申请；KeyButtonService 检测到
         * 权限缺失时会一次性拉起本页（不再使用常驻透明 Activity 承载）。
         *
         * @return 是否发起了权限申请（弹框）
         */
        @JvmStatic
        fun requestBluetoothConnectPermissionIfNeeded(activity: Activity): Boolean {
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) return false
            if (btConnectGrantedOnce) return false
            val granted = activity.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED
            if (granted) {
                btConnectGrantedOnce = true
                return false
            }
            android.util.Log.i(TAG, "BLUETOOTH_CONNECT not granted, requesting runtime permission")
            activity.requestPermissions(
                arrayOf(Manifest.permission.BLUETOOTH_CONNECT),
                REQ_BLUETOOTH_CONNECT
            )
            return true
        }

        /** 处理蓝牙权限申请结果（供 MainActivity 使用） */
        @JvmStatic
        fun handleBluetoothPermissionResult(activity: Activity, requestCode: Int, grantResults: IntArray) {
            if (requestCode != REQ_BLUETOOTH_CONNECT) return
            val granted = grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            if (granted) {
                btConnectGrantedOnce = true
                android.util.Log.i(TAG, "BLUETOOTH_CONNECT granted — BT tunnel auto-recovers via service retry")
            } else {
                android.util.Log.w(TAG, "BLUETOOTH_CONNECT denied — BT tunnel & WiFi adb unavailable")
            }
        }

        /** PhoneMirrorActivity 启动时发出的广播 Action，用于关闭本页面 */
        const val ACTION_FINISH_MAIN = "com.rokidlab.rokidlink.FINISH_MAIN"

        /**
         * 显示模式标志：手机端「打开 RokidLink」经 CXR 指令（rokidlab_show_main）
         * 拉起本页时置 true，显示状态界面（WiFi IP/ADB 状态）；平时自启不携带 → 隐形退后台。
         */
        const val EXTRA_SHOW_UI = "rokidlab_show_ui"

        // 状态灯颜色（Mondrian Noir）
        private const val DOT_IDLE    = 0xFF666666.toInt()  // 灰 — 初始
        private const val DOT_CHECKING= 0xFFFFD200.toInt()  // 黄 — 检查中
        private const val DOT_READY   = 0xFF00CC66.toInt()  // 绿 — 已就绪
        private const val DOT_ERROR   = 0xFFFF3333.toInt()  // 红 — 异常
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 不根据 WiFi 状态 finish：App 需要保持前台可见，
        // 否则会被系统标记为后台，导致服务重启触发 BackgroundServiceStartNotAllowedException。
        // 无 WiFi 时 startSetup() 会显示蓝牙通道就绪状态。
        // 注意：后台服务不在 onCreate 启动，等 onWindowFocusChanged 获得窗口焦点后再启动，
        // 否则眼镜 ROM 的 FGS 启动限制会拒绝 startForegroundService（Background start not allowed）。
        setContentView(R.layout.activity_main)

        // 显示模式：手机端「打开 RokidLink」拉起时展示状态页（含 WiFi IP），
        // 平时被动自启保持隐形（布局 invisible + 透明窗口）
        showUi = intent?.getBooleanExtra(EXTRA_SHOW_UI, false) == true
        if (showUi) {
            findViewById<View>(R.id.root)?.apply {
                visibility = View.VISIBLE
                setBackgroundColor(0xFF000000.toInt())
            }
            // 显示后 60 秒无操作自动退后台，避免状态页常驻挡视线
            Handler(Looper.getMainLooper()).postDelayed({
                if (showUi) runCatching { moveTaskToBack(true) }
            }, 60_000L)
        }

        // 申请电池优化豁免：RokidLink 是常驻服务（按键映射 + 蓝牙隧道 + AI ASR 拦截），
        // 若被系统 app idle 停服务，唤醒词链路会失效（实测 3 分钟无操作被 am_stop_idle_service 停掉）。
        requestBatteryOptimizationExemption()

        // 启动文本输入 TCP 服务器（端口 7656）
        TextInputServer.start(this)

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

        // 蓝牙运行时权限（重装后会被清空）：缺失时蓝牙隧道/ASR push/WiFi adb 全部不可用，
        // 需在 Activity 内申请。授权后 BtTunnelService 的 3s 重试会自动恢复隧道，无需重启。
        // 申请在途时锁定本页不自动 finish，否则弹框会被跟着取消（见 btPermissionPending）
        btPermissionPending = requestBluetoothConnectPermissionIfNeeded(this)

        // 兜底：若 3 秒内未收到窗口焦点（眼镜 ROM 可能不回调 onWindowFocusChanged），直接启动服务
        Handler(Looper.getMainLooper()).postDelayed({
            if (!hadWindowFocus && !isServiceRunning(BtTunnelService::class.java)) {
                Log.i(TAG, "startup fallback: starting background services")
                startBackgroundServices()
                // 延迟 finish：给服务 onCreate/startForeground 留出时间，
                // 避免 app 立即退后台导致 startForeground 被系统拒绝
                // （实测日志：Service.startForeground() not allowed due to bg restriction）
                if (!showUi && !btPermissionPending) Handler(Looper.getMainLooper()).postDelayed({
                    runCatching { finish() }
                }, 2000)
            }
        }, 3000)

        // 注册关闭广播接收器，当 PhoneMirrorActivity 启动时自动结束本页面。
        // Android 14+（targetSdk 34）动态注册必须指定 flags，否则抛 SecurityException
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(finishMainReceiver, IntentFilter(ACTION_FINISH_MAIN), Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(finishMainReceiver, IntentFilter(ACTION_FINISH_MAIN))
        }
    }

    /** 启动后台服务：先 BtTunnelService（前台服务需 5 秒内 startForeground，先启避免被 KeyButtonService 的主线程初始化拖慢崩溃），再 KeyButtonService */
    private fun startBackgroundServices() {
        runCatching { BtTunnelService.start(this) }
            .onFailure { Log.e(TAG, "Failed to start BtTunnelService", it) }
        try {
            // 必须用 startForegroundService（前台服务），Android 8+ 用 startService 会被后台限制拦截
            KeyButtonService.start(this)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start KeyButtonService", e)
        }
    }

    override fun onResume() {
        super.onResume()
        // 保活检查：核心服务不在运行则补启（崩溃/被系统清理后自愈）。
        // 仅在已获得过窗口焦点后才保活（避免 onCreate 阶段误判导致重复 start 被 FGS 限制拒绝）。
        if (!hadWindowFocus) return
        runCatching {
            if (!isServiceRunning(KeyButtonService::class.java)) {
                Log.i(TAG, "KeyButtonService not running, restarting")
                KeyButtonService.start(this)
            }
        }
        runCatching {
            if (!isServiceRunning(BtTunnelService::class.java)) {
                Log.i(TAG, "BtTunnelService not running, restarting")
                BtTunnelService.start(this)
            }
        }
    }

    /** 获得窗口焦点后启动后台服务（确保系统授予 FGS 启动权限，避免 Background start not allowed） */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !hadWindowFocus) {
            hadWindowFocus = true
            Handler(Looper.getMainLooper()).postDelayed({
                if (!isServiceRunning(BtTunnelService::class.java)) {
                    Log.i(TAG, "startBackgroundServices: BtTunnelService")
                    BtTunnelService.start(this)
                }
                if (!isServiceRunning(KeyButtonService::class.java)) {
                    Log.i(TAG, "startBackgroundServices: KeyButtonService")
                    KeyButtonService.start(this)
                }
                // 服务启动完成：隐形实例延迟结束本页，避免状态页影响视线。
                // 注意：不能用 moveTaskToBack —— 手机端「打开」时同一任务栈里已有显示模式实例，
                // moveTaskToBack 会把整个任务（含正在显示的 IP 状态页）一起退到后台。
                // finish() 只移除本（隐形）实例，显示实例不受影响。
                // 延迟 2s 再 finish：给服务 onCreate/startForeground 留出时间，
                // 否则 app 立即退后台导致 startForeground 被 bg restriction 拒绝。
                // btPermissionPending 时先不退：退后台会把权限弹框一起取消（见字段注释）
                if (!showUi && !btPermissionPending) Handler(Looper.getMainLooper()).postDelayed({
                    runCatching { finish() }
                }, 2000)
            }, 300)
        }
    }

    /** 检查本应用服务是否在运行 */
    private fun isServiceRunning(clazz: Class<*>): Boolean {
        return try {
            val am = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
            am.getRunningServices(100).any {
                it.service.packageName == packageName && it.service.className == clazz.name
            }
        } catch (e: Exception) {
            Log.w(TAG, "isServiceRunning failed", e)
            false
        }
    }

    /** 申请电池优化豁免：进入白名单后系统 app idle 不再停掉 RokidLink 的常驻服务 */
    private fun requestBatteryOptimizationExemption() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            if (pm.isIgnoringBatteryOptimizations(packageName)) {
                Log.i(TAG, "Battery optimization exemption already granted")
                return
            }
            // 直接发起请求（Android 8+ 会弹系统对话框，用户确认后豁免）
            val intent = android.content.Intent(
                android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                android.net.Uri.parse("package:$packageName")
            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            Log.i(TAG, "Requesting battery optimization exemption")
        } catch (e: Exception) {
            Log.e(TAG, "requestBatteryOptimizationExemption failed: ${e.message}")
        }
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
        // BtTunnelService 是前台服务，不随 Activity 销毁
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        handleBluetoothPermissionResult(this, requestCode, grantResults)
        if (requestCode == REQ_BLUETOOTH_CONNECT) {
            // 用户已答复：解除「不自动退后台」锁定。隐形实例此时才退后台，
            // 否则它会一直占着顶层（本页正是为申请权限才留在前台）。
            btPermissionPending = false
            if (!showUi) Handler(Looper.getMainLooper()).postDelayed({
                runCatching { finish() }
            }, 500)
            // 授权成功但服务尚未运行时补启；已在运行时由 3s 重试自动恢复隧道
            if (btConnectGrantedOnce && !isServiceRunning(BtTunnelService::class.java)) {
                Log.i(TAG, "Starting BtTunnelService after permission granted")
                runCatching { BtTunnelService.start(this) }
            }
        }
    }

    private fun startSetup() {
        // WiFi 状态仅用于显示，不再强制要求连接
        val wifiEnabled = isWifiEnabled()
        val wifiConnected = isWifiConnected()
        val ip = getIPAddress()
        
        // 更新 IP 显示
        ipText.text = if (wifiConnected && ip != "0.0.0.0") ip else getString(R.string.status_bt_ready)
        
        // 更新状态文本：WiFi 可用则显示 IP，否则显示蓝牙通道就绪
        statusText.text = if (wifiConnected && ip != "0.0.0.0") {
            getString(R.string.status_wifi_connected, ip)
        } else {
            getString(R.string.status_bt_ready_desc)
        }

        // 检查 ADB 是否就绪
        statusText.append(getString(R.string.status_checking_adb))
        if (isAdbTcpListening()) {
            setDotColor(DOT_READY)
            statusText.text = getString(R.string.status_ready)
            return
        }

        statusText.append(getString(R.string.status_enabling_adb))
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
        // 只检查 WiFi，不检查 ETHERNET（蓝牙网络共享可能映射为 ETHERNET）
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
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
        // ADB 启用已由 BtTunnelService 统一负责（Service 保留），运行中不再重复启动，
        // 避免并发线程反复 ctl.restart adbd。
        if (isServiceRunning(BtTunnelService::class.java)) return
        // 防重入：网络回调/onResume 可能多次触发 startSetup，线程尚存活时不再新建
        if (enableAdbThread?.isAlive == true) return
        val thread = Thread {
            Log.i(TAG, getString(R.string.log_try_enable_adb_tcp))

            setAdbProperty()

            for (attempt in 1..3) {
                if (Thread.currentThread().isInterrupted) return@Thread
                Log.i(TAG, getString(R.string.log_try_attempt, attempt))
                tryExec("setprop", "ctl.restart", "adbd")

                for (wait in 1..4) {
                    if (Thread.currentThread().isInterrupted) return@Thread
                    try {
                        Thread.sleep(1000)
                    } catch (e: InterruptedException) {
                        // onDestroy 中断本线程时优雅退出：未捕获的 InterruptedException
                        // 会把整个进程打崩（FATAL EXCEPTION: enable-adb-tcp），
                        // 导致 BtTunnelService/KeyButtonService/AsrPushServer 全部随进程死亡。
                        Thread.currentThread().interrupt()
                        Log.i(TAG, getString(R.string.log_interrupted))
                        return@Thread
                    }
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

    // CXR-S bridge 由 KeyButtonService 统一持有（单 bridge 原则）：
    // 双 CXRServiceBridge 实例会导致 native 订阅注册表互相覆盖，
    // 手机端下发的 rokidlab_key_config 无法路由到本应用。
    // wifi_config 的订阅与处理已由 KeyButtonService 承担。
}
