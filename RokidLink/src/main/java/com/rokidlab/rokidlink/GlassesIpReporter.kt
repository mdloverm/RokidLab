package com.rokidlab.rokidlink

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import com.rokid.cxr.Caps
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.TimeUnit

/**
 * KeyButtonService 的眼镜 WiFi IP 上报器（v3.9 拆分自 KeyButtonService）。
 *
 * 监听 WiFi 可用/变化，拿到真实 IPv4 后经 CXR 通道上报手机端，
 * 手机端据此免手动输入自动填充到投屏/手机镜像/文件管理/ADB 共用的单一数据源。
 * 仅在 IP 实际变化时才上行，避免 onCapabilitiesChanged/onLinkPropertiesChanged 高频重复发送。
 */
internal class GlassesIpReporter(
    private val service: KeyButtonService,
    private val core: KeyServiceCore,
) {
    companion object {
        private const val TAG = KeyButtonService.TAG
    }

    private var wifiIpReporter: ConnectivityManager.NetworkCallback? = null
    private var connectivityManager: ConnectivityManager? = null

    /** 最近一次成功上报的 IP，用于去重（同一 IP 不重复上行） */
    @Volatile
    private var lastReportedIp: String? = null

    /** 获取当前 WiFi 的 IPv4 地址（仅限 TRANSPORT_WIFI 网络，排除蜂窝/回环/区域后缀）。
     *  未连 WiFi 或尚在获取中返回 null；与 MainActivity.getIPAddress 思路一致但精确限定 WiFi 接口。 */
    private fun getWiFiIpAddress(): String? {
        return try {
            // 复用 MainActivity.getIPAddress 的可靠思路：遍历 NetworkInterface，取首个 IPv4 非回环地址。
            // 直接用 Collections.list 把 Enumeration 转 List，避免 Kotlin for 循环迭代器歧义；
            // 优先取 WiFi 接口（wlan*/wifi*），否则取首个可用 IPv4（眼镜无蜂窝，唯一激活接口即 WiFi）。
            val intfs = java.util.Collections.list(NetworkInterface.getNetworkInterfaces())
            var fallback: String? = null
            for (intf in intfs) {
                if (!intf.isUp || intf.isLoopback) continue
                for (ia in intf.interfaceAddresses) {
                    val addr = ia.address ?: continue
                    if (addr.isLoopbackAddress || addr !is Inet4Address) continue
                    val ip = addr.hostAddress?.substringBefore('%') ?: continue
                    if (intf.name?.startsWith("wlan") == true || intf.name?.contains("wifi", ignoreCase = true) == true) {
                        return ip
                    }
                    if (fallback == null) fallback = ip
                }
            }
            fallback
        } catch (e: Exception) {
            Log.e(TAG, "getWiFiIpAddress failed", e)
            null
        }
    }

    /** 注册 WiFi 网络回调：WiFi 可用/获得 internet 能力/链路属性变化时上行眼镜 IP */
    fun register() {
        val cm = service.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        connectivityManager = cm
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.i(TAG, "WIFI available — reporting glasses IP")
                sendGlassesIp()
            }
            override fun onCapabilitiesChanged(network: Network, networkCaps: NetworkCapabilities) {
                if (networkCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                    sendGlassesIp()
                }
            }
            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
                sendGlassesIp()
            }
            override fun onLost(network: Network) {
                Log.i(TAG, "WIFI lost — reset last reported IP")
                lastReportedIp = null
            }
        }
        wifiIpReporter = cb
        try {
            cm.registerNetworkCallback(request, cb)
            Log.i(TAG, "Wifi IP reporter registered")
            // 注册即上报一次：若此刻已连 WiFi 可立即让手机端拿到 IP
            sendGlassesIp()
        } catch (e: Exception) {
            Log.e(TAG, "registerWifiIpReporter failed", e)
        }
    }

    /** 反注册 WiFi 网络回调（onDestroy 调用） */
    fun unregister() {
        runCatching { wifiIpReporter?.let { connectivityManager?.unregisterNetworkCallback(it) } }
        wifiIpReporter = null
        connectivityManager = null
    }

    /** 上行眼镜 WiFi IP：经 CXR-S 通道发往手机端。带 2s 超时保护 + IP 去重（同 IP 不重复上行）。 */
    fun sendGlassesIp() {
        val ip = getWiFiIpAddress()
        if (ip.isNullOrEmpty()) {
            Log.d(TAG, "sendGlassesIp: no WiFi IPv4 yet, skip")
            return
        }
        if (ip == lastReportedIp) {
            Log.d(TAG, "sendGlassesIp: IP unchanged ($ip), skip")
            return
        }
        val b = core.bridge ?: run {
            Log.w(TAG, "sendGlassesIp: bridge not ready, will retry on connect")
            return
        }
        val caps = Caps()
        AiChannel.encodeGlassesIp(ip).forEach { caps.write(it) }
        try {
            val f = core.aiSendExecutor.submit<Int> { b.sendMessage(AiChannel.TOPIC_GLASSES_IP, caps) }
            val r = f.get(2, TimeUnit.SECONDS)
            if (r == 0) {
                lastReportedIp = ip
                Log.i(TAG, "sendGlassesIp($ip) -> ok")
            } else {
                Log.w(TAG, "sendGlassesIp($ip) -> $r (not cached)")
            }
        } catch (e: java.util.concurrent.TimeoutException) {
            Log.w(TAG, "sendGlassesIp($ip) timeout (CXR channel blocked)")
        } catch (e: Exception) {
            Log.e(TAG, "sendGlassesIp($ip) error", e)
        }
    }
}
