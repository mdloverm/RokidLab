package com.rokidlab.phone.store

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import com.rokidlab.phone.util.namedThread
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 聊天气泡图片缓存与加载器（轻量自研：项目原本未引入 Coil/Glide）。
 *
 * - 内存级 ConcurrentHashMap 缓存，按 URL 去重；同一张图只下载一次。
 * - 异步在 worker 线程下载（OkHttp）+ 解码（BitmapFactory），完成后切回主线程回调。
 * - 解码后按 [maxSize] 长边缩放（默认 600px），避免大图撑爆气泡 / 触发 Bitmap 大小限制。
 *
 * Compose 侧用法见 [ChatBubble]。
 */
internal object ChatImageCache {
    private const val TAG = "ChatImageCache"

    private val memCache = ConcurrentHashMap<String, Bitmap>()
    private val inflight = ConcurrentHashMap<String, Unit>()

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 异步加载图片；命中缓存则立即在主线程回调 [onResult]。
     * @param url 图片直链（http/https）
     * @param maxSize 长边像素上限（默认 600）
     * @param onResult 主线程回调，参数为加载到的 Bitmap（失败或为空时为 null）
     */
    fun load(url: String, maxSize: Int = 600, onResult: (Bitmap?) -> Unit) {
        if (url.isBlank()) {
            onResult(null)
            return
        }
        memCache[url]?.let { cached ->
            onResult(cached)
            return
        }
        // 去重：同一 URL 的并发请求只发一次网络
        if (inflight.putIfAbsent(url, Unit) != null) {
            // 简单轮询等待缓存命中（最长 10s）
            namedThread("chat-image-io", start = true) {
                val deadline = System.currentTimeMillis() + 10_000
                while (System.currentTimeMillis() < deadline) {
                    memCache[url]?.let {
                        mainHandler.post { onResult(it) }
                        return@namedThread
                    }
                    Thread.sleep(120)
                }
                mainHandler.post { onResult(null) }
            }
            return
        }
        namedThread("chat-image-clean", start = true) {
            val bmp = runCatching {
                val bytes = fetchBytesLocked(url) ?: return@runCatching null
                decodeSampled(bytes, maxSize)
            }.onFailure { Log.w(TAG, "load $url failed: ${it.message}") }.getOrNull()
            if (bmp != null) memCache[url] = bmp
            inflight.remove(url)
            mainHandler.post { onResult(bmp) }
        }
    }

    /**
     * 拉取图片**原始字节**（不采样、不缩放），供「保存到手机」保存原图。
     * 支持 http(s) 直链与 `data:image/...;base64,` 内联图。调用方需在 worker 线程。
     */
    fun fetchBytes(url: String): ByteArray? = fetchBytesLocked(url)

    private fun fetchBytesLocked(url: String): ByteArray? {
        if (url.startsWith("data:", ignoreCase = true)) {
            val raw = url.substringAfter(",", "")
            if (raw.isBlank()) return null
            return runCatching { Base64.decode(raw, Base64.DEFAULT) }
                .onFailure { Log.w(TAG, "bad data url: ${it.message}") }
                .getOrNull()
        }
        val req = Request.Builder().url(url).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.w(TAG, "fetch $url: HTTP ${resp.code}")
                return null
            }
            return resp.body?.bytes()
        }
    }

    /**
     * 按 [maxSize] 采样解码：**先读尺寸算 inSampleSize 再解码**，而不是解码原图再缩放。
     *
     * 背景：聊天历史里的图片消息持久化后，每次进聊天界面 [ChatBubble] 都会重新解码所有历史图。
     * 旧实现 `decodeByteArray(body)` 直接按原图尺寸分配（4000×3000 ≈ 48MB/张），
     * 华为 P20 这类 4GB 老机型上多张并发解码即 OOM，进程被 LMK 直接杀掉 →「进聊天界面过几秒闪退」
     * （runCatching 只能接住 Java 异常，救不了进程被杀）。采样后单张峰值内存降到 ~2×maxSize 级别。
     */
    private fun decodeSampled(body: ByteArray, maxSize: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(body, 0, body.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxSize || bounds.outHeight / (sample * 2) >= maxSize) {
            sample *= 2
        }
        val raw = BitmapFactory.decodeByteArray(body, 0, body.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        return scale(raw, maxSize)
    }

    private fun scale(bmp: Bitmap, maxSize: Int): Bitmap {
        val w = bmp.width
        val h = bmp.height
        if (w <= maxSize && h <= maxSize) return bmp
        val ratio = maxSize.toDouble() / maxOf(w, h)
        val nw = (w * ratio).toInt().coerceAtLeast(1)
        val nh = (h * ratio).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(bmp, nw, nh, true)
        // 释放中间大图：否则原图对象滞留到 GC，多张历史图累积同样拖垮老设备
        if (scaled !== bmp) bmp.recycle()
        return scaled
    }
}