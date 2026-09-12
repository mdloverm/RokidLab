package com.rokidlab.phone.ai

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.rokidlab.phone.util.HttpClient
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 手机定位（AI 工具 get_location 的执行体）。
 *
 * 解决的问题：此前没有定位工具，用户问「我在哪」模型只能干猜或反复调用不存在的工具名
 * （ToolRegistry.execute 对未知工具抛异常），表现为对话"卡半天才回"。
 *
 * 取位优先级（宁快勿慢，任何一层成功即返回）：
 *   1. 系统定位缓存（GPS/网络/被动）中最新的一条，5 分钟内视为新鲜 → 直接逆地理编码
 *   2. 缓存过旧/没有 → API 30+ 请求一次实时定位（总预算 6s，超时即放弃）
 *   3. 仍无坐标 → IP 定位兜底，至少给出城市
 *
 * 地址解析：优先系统 Geocoder（本地化，中文环境下直接返回中文地址），失败再用在线逆地理编码；
 * 都失败时退化为"经纬度 + 精度 + 来源"，保证任何情况下都有一句可如实转告的答案。
 *
 * 同步阻塞（Agent 工具循环已在后台线程执行），返回给模型的中文结果文本。
 */
object LocationTools {
    private const val TAG = "LocationTools"

    /** 缓存定位的新鲜度上限：5 分钟内直接用，避免每次现等 GPS 冷启动 */
    private const val MAX_CACHE_AGE_MS = 5 * 60 * 1000L

    /** 实时定位等待上限（毫秒；配合 CountDownLatch.await 用 Long），多 provider 共享这份预算 */
    private const val FRESH_FIX_TIMEOUT_MS = 6_000L

    /** 逆地理编码 / IP 兜底的网络读超时（毫秒；HttpClient.readTimeout 形参是 Int） */
    private const val GEOCODE_TIMEOUT_MS = 6_000
    private const val IP_TIMEOUT_MS = 6_000

    /**
     * 获取当前位置的人话描述。永不在权限缺失时静默失败 —— 返回带引导的说明，
     * 由模型如实转告用户去授权（与 PhoneTools 的权限处理保持一致）。
     */
    fun getLocation(context: Context): String {
        if (!hasLocationPermission(context)) {
            return "没有定位权限，无法获取当前位置。请在手机「设置 → 应用 → RokidLab / 乐奇实验室 → 权限」" +
                "中开启「位置信息」后重试"
        }
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return "本机不支持定位服务"

        val loc = bestLocation(lm)
        if (loc != null) {
            val coord = formatCoordinate(loc)
            val addr = reverseGeocode(context, loc.latitude, loc.longitude)
            return if (addr.isNullOrBlank()) "当前位置：$coord" else "当前位置：$addr（$coord）"
        }

        // 系统定位无可用结果（未开定位开关 / 无缓存 / 无卫星信号）→ IP 定位兜底，至少给出城市
        val ipText = ipBasedLocation()
        if (!ipText.isNullOrBlank()) {
            return "定位服务没有返回坐标，按网络出口推断大致位置：$ipText"
        }

        return "暂时无法获取位置：系统定位无可用结果，网络定位也失败。" +
            "请确认手机已打开定位开关（下拉通知栏的「位置信息」）后重试"
    }

    // ═══════════════════════════ 取坐标 ═══════════════════════════

    private fun hasLocationPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * 取一个可用的位置：优先缓存，其次实时定位。
     * 实时定位拿不到时返回旧缓存（旧值也比"没有"有用，由调用方在文案里体现精度）。
     */
    private fun bestLocation(lm: LocationManager): Location? {
        val providers = enabledProviders(lm)

        // 1) 缓存：取时间戳最新的一条
        var cached: Location? = null
        for (p in providers) {
            val l = lastKnown(lm, p) ?: continue
            if (cached == null || l.time > cached.time) cached = l
        }
        if (cached != null && System.currentTimeMillis() - cached.time <= MAX_CACHE_AGE_MS) {
            Log.i(TAG, "use cached fix from ${cached.provider}, age=${System.currentTimeMillis() - cached.time}ms")
            return cached
        }

        // 2) 缓存过旧/没有：请求一次实时定位（API 30+ 才有免回调的同步取位接口）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            requestFreshLocation(lm, providers)?.let {
                Log.i(TAG, "fresh fix from ${it.provider}")
                return it
            }
        }

        if (cached != null) Log.i(TAG, "fallback to stale cached fix (age=${System.currentTimeMillis() - cached.time}ms)")
        return cached
    }

    private fun enabledProviders(lm: LocationManager): List<String> = try {
        // 网络定位通常秒回、室内可用，排在 GPS 前；GPS 更准但冷启动慢
        val all = lm.getProviders(true).orEmpty()
        listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .filter { all.contains(it) }
            .ifEmpty { all }
    } catch (e: Exception) {
        Log.w(TAG, "getProviders failed: ${e.message}")
        listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
    }

    private fun lastKnown(lm: LocationManager, provider: String): Location? = try {
        lm.getLastKnownLocation(provider)
    } catch (e: Exception) {
        Log.w(TAG, "getLastKnownLocation($provider) failed: ${e.message}")
        null
    }

    /**
     * 请求一次实时定位。总预算 [FRESH_FIX_TIMEOUT_MS]（多 provider 共享，不会逐个叠加等待）。
     * 用 getCurrentLocation 而非 requestLocationUpdates：无需实现 LocationListener，
     * 也不会留下需要反注册的持续回调。
     */
    private fun requestFreshLocation(lm: LocationManager, providers: List<String>): Location? {
        val deadline = System.currentTimeMillis() + FRESH_FIX_TIMEOUT_MS
        for (p in providers) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) break
            val holder = AtomicReference<Location?>(null)
            val latch = CountDownLatch(1)
            try {
                lm.getCurrentLocation(p, null, Executor { it.run() }) { l ->
                    holder.set(l)
                    latch.countDown()
                }
                latch.await(remaining, TimeUnit.MILLISECONDS)
            } catch (e: Exception) {
                Log.w(TAG, "getCurrentLocation($p) failed: ${e.message}")
                latch.countDown()
            }
            holder.get()?.let { return it }
        }
        return null
    }

    private fun formatCoordinate(loc: Location): String {
        val acc = if (loc.hasAccuracy()) "，精度 ±${loc.accuracy.toInt()} 米" else ""
        val src = loc.provider?.takeIf { it.isNotBlank() } ?: "未知来源"
        return String.format(Locale.US, "纬度 %.5f，经度 %.5f%s，来源 %s", loc.latitude, loc.longitude, acc, src)
    }

    // ═══════════════════════════ 坐标 → 地址 ═══════════════════════════

    /** 逆地理编码：系统 Geocoder 优先（本地化），失败退回在线服务；都失败返回 null */
    private fun reverseGeocode(context: Context, lat: Double, lon: Double): String? {
        // 1) 系统 Geocoder：中文环境下直接返回中文地址，无需第三方服务
        try {
            if (Geocoder.isPresent()) {
                @Suppress("DEPRECATION")
                val addresses = Geocoder(context, Locale.getDefault()).getFromLocation(lat, lon, 1)
                val a = addresses?.firstOrNull()
                if (a != null) {
                    val parts = listOfNotNull(
                        a.adminArea,                                  // 省/直辖市
                        a.locality ?: a.subAdminArea,                 // 市 / 区
                        a.subLocality,                                // 街道办/街道
                        a.thoroughfare,                               // 道路
                    ).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
                    if (parts.isNotEmpty()) return parts.joinToString(" ")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "system Geocoder failed: ${e.message}")
        }

        // 2) 在线兜底（免费、无 Key、localityLanguage=zh 返回中文）
        return try {
            val url = "https://api.bigdatacloud.net/data/reverse-geocode-client" +
                "?latitude=$lat&longitude=$lon&localityLanguage=zh"
            val o = JSONObject(HttpClient.getString(url, readTimeout = GEOCODE_TIMEOUT_MS))
            val parts = listOfNotNull(
                o.optString("principalSubdivision").takeIf { it.isNotBlank() },
                o.optString("city").takeIf { it.isNotBlank() },
                o.optString("locality").takeIf { it.isNotBlank() },
            ).distinct()
            parts.joinToString(" ").ifBlank { null }
        } catch (e: Exception) {
            Log.w(TAG, "online reverse geocode failed: ${e.message}")
            null
        }
    }

    // ═══════════════════════════ IP 定位兜底 ═══════════════════════════

    /** 按网络出口 IP 推断大致位置（精确到城市，非 GPS）。失败返回 null */
    private fun ipBasedLocation(): String? = try {
        val o = JSONObject(HttpClient.getString("http://ip-api.com/json/?lang=zh-CN", readTimeout = IP_TIMEOUT_MS))
        if (o.optString("status") == "success") {
            val parts = listOfNotNull(
                o.optString("country").takeIf { it.isNotBlank() },
                o.optString("regionName").takeIf { it.isNotBlank() },
                o.optString("city").takeIf { it.isNotBlank() },
            ).distinct()
            parts.joinToString(" ").ifBlank { null }
        } else null
    } catch (e: Exception) {
        Log.w(TAG, "ip location failed: ${e.message}")
        null
    }
}
