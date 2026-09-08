package com.rokidlab.phone.network

import com.rokidlab.phone.util.HttpClient
import com.rokidlab.phone.util.decodeSampledBitmap
import android.content.Context
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

private const val ICON_MAX_DIMENSION_PX = 256

/** 远程图标失败后的拉黑时长：网络抖动导致的失败过 5 分钟允许重试（与 MediaLoader 策略一致） */
private const val REMOTE_MISSING_TTL_MS = 5 * 60 * 1000L

class IconLoader(private val context: Context) {
    private val cache = ConcurrentHashMap<String, Drawable>()

    /** 失败拉黑表：key -> 拉黑截止时间戳（本地 asset 失败为确定性结果，用 Long.MAX_VALUE 永久拉黑） */
    private val missing = ConcurrentHashMap<String, Long>()

    /** key 是否仍处于拉黑期 */
    private fun isBlacklisted(key: String): Boolean {
        val until = missing[key] ?: return false
        if (System.currentTimeMillis() < until) return true
        // 过期：移除并放行重试
        missing.remove(key)
        return false
    }

    fun load(appId: String, iconUrl: String? = null): Drawable? {
        return loadLocal(appId) ?: loadRemote(iconUrl)
    }

    private fun loadLocal(appId: String): Drawable? {
        val key = "asset:$appId"
        cache[key]?.let { return it }
        if (isBlacklisted(key)) return null
        return runCatching {
            context.assets.open("icons/$appId.png").use { input ->
                decodeSampledBitmap(input.readBytes(), ICON_MAX_DIMENSION_PX)?.let { bitmap ->
                    BitmapDrawable(context.resources, bitmap)
                }
            }
        }.getOrNull().also { drawable ->
            if (drawable == null) missing[key] = Long.MAX_VALUE else cache[key] = drawable
        }
    }

    private fun loadRemote(url: String?): Drawable? {
        if (url.isNullOrBlank()) return null
        val key = "remote:$url"
        cache[key]?.let { return it }
        if (isBlacklisted(key)) return null
        return runCatching {
            val file = cachedImageFile(url)
            if (!file.exists()) download(url, file)
            decodeSampledBitmap(file, ICON_MAX_DIMENSION_PX)?.let { bitmap ->
                BitmapDrawable(context.resources, bitmap)
            }
        }.getOrNull().also { drawable ->
            if (drawable == null) {
                // 失败只拉黑 5 分钟（非永久）：网络恢复后刷新页面即可重试
                missing[key] = System.currentTimeMillis() + REMOTE_MISSING_TTL_MS
            } else {
                missing.remove(key)
                cache[key] = drawable
            }
        }
    }

    private fun cachedImageFile(url: String): File {
        val dir = File(context.cacheDir, "registry-icons").apply { mkdirs() }
        return File(dir, url.sha256())
    }

    private fun download(url: String, output: File) {
        HttpClient.download(url, output, connectTimeout = 6000, readTimeout = 10000)
    }

    private fun String.sha256(): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
