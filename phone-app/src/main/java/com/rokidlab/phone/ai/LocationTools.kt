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
import com.rokidlab.phone.R
import com.rokidlab.phone.permission.AppPermission
import com.rokidlab.phone.permission.PermissionBridge
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
    fun getLocation(context: Context): String = getLocationText(context)

    /**
     * 取位置，按 [format] 分流（2026-10-09，P0-4）：
     *  - `"text"`（默认）= 一句中文，给模型转述用（**逐字保持改造前的文案**，不改行为）；
     *  - `"json"` = 结构化对象，给 **AIUI 页面**直接消费。
     *
     * 为什么需要 json：改造前只返回一句话（`当前位置：地址（纬度 x，经度 y，精度 ±n 米，来源 gps）`），
     * 而页面要画经纬度/精度/速度/里程就得**正则去抠那句中文** —— 天然不可靠，且文案一改就崩。
     * gps-live 事故里页面正是因为抠不出数值，主界面全空而用户只看到"拿不到位置"。
     */
    fun getLocation(context: Context, format: String): String =
        if (format.equals("json", ignoreCase = true)) getLocationJson(context) else getLocationText(context)

    private fun getLocationText(context: Context): String {
        val fix = resolveFix(context)
        fix.error?.let { return it }
        val loc = fix.loc ?: return "定位服务没有返回坐标，按网络出口推断大致位置：${fix.ipText.orEmpty()}"
        val coord = formatCoordinate(loc)
        val addr = reverseGeocode(context, loc.latitude, loc.longitude)
        return if (addr.isNullOrBlank()) "当前位置：$coord" else "当前位置：$addr（$coord）"
    }

    /**
     * 结构化输出。**字段口径与 AIUI 页面约定一致**（页面直接 `JSON.parse` 取字段即可）。
     *
     * 成功：`{"ok":true,"latitude":…,"longitude":…,"accuracy":…,"altitude":…,"speed":…,
     *        "bearing":…,"provider":"gps","timestamp":…,"address":"…"}`
     * 失败：`{"ok":false,"error":"<人话原因>"}` —— 原因与 text 版**逐字一致**，
     * 页面可原样显示，也可据此分支（如提示开定位开关）。
     *
     * ⚠️ 可选字段（altitude/speed/bearing/accuracy/address）**不保证存在**，页面必须按可空处理；
     * 且**不放 NaN/Infinity**（会产出非法 JSON，`JSON.parse` 直接抛错）。
     */
    private fun getLocationJson(context: Context): String {
        val o = JSONObject()
        val fix = resolveFix(context)
        fix.error?.let {
            o.put("ok", false)
            o.put("error", it)
            return o.toString()
        }
        val loc = fix.loc
        if (loc == null) {
            o.put("ok", false)
            o.put("error", "定位服务没有返回坐标（网络出口推断：${fix.ipText.orEmpty()}）")
            return o.toString()
        }
        o.put("ok", true)
        o.put("latitude", loc.latitude)
        o.put("longitude", loc.longitude)
        o.put("timestamp", loc.time)
        if (loc.hasAccuracy()) o.put("accuracy", loc.accuracy.toDouble())
        if (loc.hasAltitude()) o.put("altitude", loc.altitude)
        if (loc.hasSpeed() && loc.speed.isFinite()) o.put("speed", loc.speed.toDouble())
        if (loc.hasBearing() && loc.bearing.isFinite()) o.put("bearing", loc.bearing.toDouble())
        loc.provider?.takeIf { it.isNotBlank() }?.let { o.put("provider", it) }
        // 逆地理编码可能耗时（网络兜底最长 6s）：页面若只要坐标可用 format=json 且忽略 address
        reverseGeocode(context, loc.latitude, loc.longitude)?.let { o.put("address", it) }
        return o.toString()
    }

    /**
     * 取位置结果的**唯一产地**：把"权限 → 服务 → 缓存/实时 → IP 兜底"这一串收敛成一次调用。
     *
     * 抽出来的动机：text 与 json 两条输出**必须共用同一套取数逻辑**，
     * 否则两条路的降级行为会漂（一个说"没权限"另一个给 IP 结果）。
     *
     * @return [Fix]：三选一 —— `loc` 有坐标 / `ipText` 有兜底城市 / `error` 有人话原因（前两者皆无）
     */
    private fun resolveFix(context: Context): Fix {
        if (!hasLocationPermission(context)) {
            // 缺权限时**自动拉起系统授权框**（旧实现只回一句"请去设置里开"）。
            // 注意 Android 12+ 请求 FINE 必须同时请求 COARSE，否则系统直接判拒绝且不弹窗 ——
            // 这对伴随权限由 AppPermission.LOCATION 统一声明。
            val reason = PermissionBridge.ensure(
                context,
                context.getString(R.string.permission_reason_location),
                AppPermission.LOCATION,
            ) ?: context.getString(
                R.string.permission_need_manual_open,
                context.getString(R.string.permission_label_location),
            )
            return Fix(null, null, reason)
        }
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return Fix(null, null, "本机不支持定位服务")

        bestLocation(lm)?.let { return Fix(it, null, null) }

        // 系统定位无可用结果（未开定位开关 / 无缓存 / 无卫星信号）→ IP 定位兜底，至少给出城市
        val ipText = ipBasedLocation()
        return if (!ipText.isNullOrBlank()) {
            Fix(null, ipText, null)
        } else {
            Fix(
                null,
                null,
                "暂时无法获取位置：系统定位无可用结果，网络定位也失败。" +
                    "请确认手机已打开定位开关（下拉通知栏的「位置信息」）后重试",
            )
        }
    }

    /** [resolveFix] 的三态结果：坐标 / IP 兜底文本 / 人话错误原因（互斥，见各分支） */
    private data class Fix(
        val loc: Location?,
        val ipText: String?,
        val error: String?,
    )

    /**
     * 供天气等工具「自动定位兜底」用：返回系统定位坐标（缓存→实时定位），
     * 权限缺失或无可用定位时返回 null（不打扰用户，调用方自行降级）。
     */
    fun currentLocation(context: Context): android.location.Location? {
        if (!hasLocationPermission(context)) return null
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        return bestLocation(lm)
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
