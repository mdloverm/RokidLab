package com.rokidlab.phone.store

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Log
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
            Thread {
                val deadline = System.currentTimeMillis() + 10_000
                while (System.currentTimeMillis() < deadline) {
                    memCache[url]?.let {
                        mainHandler.post { onResult(it) }
                        return@Thread
                    }
                    Thread.sleep(120)
                }
                mainHandler.post { onResult(null) }
            }.start()
            return
        }
        Thread {
            val bmp = runCatching {
                val req = Request.Builder().url(url).build()
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        Log.w(TAG, "load $url: HTTP ${resp.code}")
                        return@use null
                    }
                    val body = resp.body?.bytes() ?: return@use null
                    val raw = BitmapFactory.decodeByteArray(body, 0, body.size) ?: return@use null
                    scale(raw, maxSize)
                }
            }.onFailure { Log.w(TAG, "load $url failed: ${it.message}") }.getOrNull()
            if (bmp != null) memCache[url] = bmp
            inflight.remove(url)
            mainHandler.post { onResult(bmp) }
        }.start()
    }

    private fun scale(bmp: Bitmap, maxSize: Int): Bitmap {
        val w = bmp.width
        val h = bmp.height
        if (w <= maxSize && h <= maxSize) return bmp
        val ratio = maxSize.toDouble() / maxOf(w, h)
        val nw = (w * ratio).toInt().coerceAtLeast(1)
        val nh = (h * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bmp, nw, nh, true)
    }
}