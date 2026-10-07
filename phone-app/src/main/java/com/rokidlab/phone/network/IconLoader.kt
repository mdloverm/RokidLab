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

/**
 * 图标缓存的条目上限。
 *
 * ## 为什么必须有上限（2026-10-06 审计修）
 * 本类原先两个容器都是**无界** `ConcurrentHashMap`，与同职责的 [MediaLoader] 形状完全一样
 * —— 而 [MediaLoader] 已在 2026-10-05 改成 LRU，**同一职责两套策略、只一套有上限**。
 *
 * 取 128：比 [MediaLoader] 的 64 更宽，因为图标列表比截图多得多（一屏几十个 app）；
 * 单条 ≤256px（[ICON_MAX_DIMENSION_PX]）ARGB_8888 最坏 ≈262KB，
 * 128 条最坏 ≈33MB，**真到极端也不至于崩**。
 */
internal const val ICON_CACHE_ENTRIES = 128

/**
 * 拉黑表的条目上限。
 *
 * ⚠️ 键是**远端 URL**（`remote:$url`），URL 来自远端清单、不可信 ——
 * 无界的话，清单变更/恶意 URL 可持续注入唯一键把这张表撑大。必须**有界**。
 */
internal const val ICON_BLACKLIST_ENTRIES = 256

class IconLoader(private val context: Context) {
    /**
     * 所有读写都在 [lock] 内：`LinkedHashMap` 非线程安全，且淘汰需要写路径。
     * ⚠️ 锁内**不做 IO** —— 网络下载与解码都在锁外，只在锁内做 map 读写。
     */
    private val lock = Any()

    /**
     * 命中缓存（key→[Drawable]）。**LRU + 访问序**，上限 [ICON_CACHE_ENTRIES]，走 [lruCache]
     * —— 与 [MediaLoader.cache] 同一套策略。
     *
     * ⚠️ 淘汰只**丢引用**，绝不 `recycle()` 里面的 Bitmap：它可能正被某个 Compose painter
     * 持有并绘制中，recycle 会让那次绘制抛 "Canvas: trying to use a recycled bitmap"。
     */
    private val cache = lruCache<String, Drawable>(ICON_CACHE_ENTRIES)

    /** 失败拉黑表：key -> 拉黑截止时间戳（本地 asset 失败为确定性结果，用 Long.MAX_VALUE 永久拉黑） */
    private val missing = lruCache<String, Long>(ICON_BLACKLIST_ENTRIES)

    /** 清除内存缓存与磁盘副本（图标副本在 `cacheDir/registry-icons`，原先无人回收） */
    fun clearCache() {
        synchronized(lock) {
            cache.clear()
            missing.clear()
        }
        File(context.cacheDir, "registry-icons").deleteRecursively()
    }

    /** key 是否仍处于拉黑期 */
    private fun isBlacklisted(key: String): Boolean {
        synchronized(lock) {
            val until = missing[key] ?: return false
            if (System.currentTimeMillis() < until) return true
            // 过期：移除并放行重试
            missing.remove(key)
            return false
        }
    }

    fun load(appId: String, iconUrl: String? = null): Drawable? {
        return loadLocal(appId) ?: loadRemote(iconUrl)
    }

    private fun loadLocal(appId: String): Drawable? {
        val key = "asset:$appId"
        synchronized(lock) { cache[key]?.let { return it } }
        if (isBlacklisted(key)) return null
        return runCatching {
            context.assets.open("icons/$appId.png").use { input ->
                decodeSampledBitmap(input.readBytes(), ICON_MAX_DIMENSION_PX)?.let { bitmap ->
                    BitmapDrawable(context.resources, bitmap)
                }
            }
        }.getOrNull().also { drawable ->
            synchronized(lock) {
                if (drawable == null) missing[key] = Long.MAX_VALUE else cache[key] = drawable
            }
        }
    }

    private fun loadRemote(url: String?): Drawable? {
        if (url.isNullOrBlank()) return null
        val key = "remote:$url"
        synchronized(lock) { cache[key]?.let { return it } }
        if (isBlacklisted(key)) return null
        return runCatching {
            val file = cachedImageFile(url)
            if (!file.exists()) download(url, file)
            decodeSampledBitmap(file, ICON_MAX_DIMENSION_PX)?.let { bitmap ->
                BitmapDrawable(context.resources, bitmap)
            }
        }.getOrNull().also { drawable ->
            synchronized(lock) {
                if (drawable == null) {
                    // 失败只拉黑 5 分钟（非永久）：网络恢复后刷新页面即可重试
                    missing[key] = System.currentTimeMillis() + REMOTE_MISSING_TTL_MS
                } else {
                    missing.remove(key)
                    cache[key] = drawable
                }
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
