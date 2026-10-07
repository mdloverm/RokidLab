package com.rokidlab.phone.network

import com.rokidlab.phone.util.HttpClient
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import java.io.File
import java.io.IOException
import java.security.MessageDigest

private const val MEDIA_MAX_DIMENSION_PX = 1080

/**
 * 媒体缓存的条目上限。
 *
 * ## 为什么必须有上限（2026-10-05 审计修）
 * 本类原先三个容器全是**无界** `ConcurrentHashMap`：
 * `cache`（key→[Drawable]，Drawable 持 ≤1080px 的 Bitmap）随商店浏览单调增长且**永不淘汰**
 * ⇒ 长时间浏览商店必然吃到 OOM。旁边 [com.rokidlab.phone.store.PainterBitmapCache]
 * 早就用 `LinkedHashMap` LRU 兜住了，**同一职责两套策略、只一套有上限** —— 现在统一。
 *
 * 取 64：商店一个应用要占「图标 + 若干截图」，64 条够铺满当前屏与相邻滚动，
 * 又远低于 OOM 阈值（单条 1080px ARGB_8888 最坏 4.5MB，64 条最坏 ≈288MB，
 * 实际截图走 [MEDIA_MAX_DIMENSION_PX] 降采样后远小于此；**真到极端也不至于崩**）。
 */
private const val MEDIA_CACHE_ENTRIES = 64

/** 失败 URL 的记忆上限：只用于 5 分钟内不重试，条数无须多，但要**有界**（URL 来自远端清单，不可信）。 */
private const val MEDIA_FAILED_ENTRIES = 128

/** 下载失败后的冷却时长：5 分钟内同一 URL 不再重试（避免弱网下每帧都打一次远端） */
private const val FAILED_RETRY_MS = 300_000L

class MediaLoader(private val context: Context) {
    private val lock = Any()

    /**
     * 命中缓存（key→[Drawable]）。**LRU + 访问序**，上限 [MEDIA_CACHE_ENTRIES]，
     * 与 [com.rokidlab.phone.store.PainterBitmapCache] 同一套策略（都走 [lruCache]）。
     *
     * ⚠️ 淘汰只**丢引用**，不 `recycle()` 那个 Bitmap：它可能正被某个 Compose painter
     * 持有并绘制中，recycle 会让那次绘制抛 "Canvas: trying to use a recycled bitmap"。
     * 📌 读写都在 [lock] 内（`LinkedHashMap` 非线程安全，淘汰需要写路径）。
     */
    private val cache = lruCache<String, Drawable>(MEDIA_CACHE_ENTRIES)

    /** assets 里读不到的 media 名（打进包的静态资源，读不到就是永远读不到） */
    private val missing = LinkedHashMap<String, Boolean>()

    /** 近期下载失败的 URL → 时间戳（[FAILED_RETRY_MS] 内不重试） */
    private val failedUrlTimestamps = lruCache<String, Long>(MEDIA_FAILED_ENTRIES)

    fun load(assetName: String?, url: String? = null): Drawable? {
        return loadLocal(assetName) ?: loadRemote(url)
    }

    fun clearCache() {
        synchronized(lock) {
            cache.clear()
            missing.clear()
            // ⚠️ 原先只清 failedUrls 派生集合、漏了这个时间戳表 —— 那张表自己也会无界增长
            failedUrlTimestamps.clear()
        }
        // 清除磁盘缓存
        File(context.cacheDir, "registry-media").deleteRecursively()
    }

    private fun loadLocal(assetName: String?): Drawable? {
        if (assetName.isNullOrBlank()) return null
        val key = "asset:$assetName"
        synchronized(lock) {
            cache[key]?.let { return it }
            if (missing.containsKey(key)) return null
        }
        return runCatching {
            context.assets.open("media/$assetName").use { input ->
                decodeSampledBitmap(input.readBytes(), MEDIA_MAX_DIMENSION_PX)?.let { bitmap ->
                    BitmapDrawable(context.resources, bitmap)
                }
            }
        }.getOrNull().also { drawable ->
            synchronized(lock) {
                if (drawable == null) missing[key] = true else cache[key] = drawable
            }
        }
    }

    private fun loadRemote(url: String?): Drawable? {
        if (url.isNullOrBlank()) return null
        val key = "remote:$url"

        // 检查是否有缓存
        synchronized(lock) { cache[key]?.let { return it } }

        // 检查是否最近失败过（5分钟内不重试）
        synchronized(lock) {
            failedUrlTimestamps[url]?.let { lastFailed ->
                if (System.currentTimeMillis() - lastFailed < FAILED_RETRY_MS) return null
                failedUrlTimestamps.remove(url)
            }
        }

        return runCatching {
            val file = cachedImageFile(url)
            if (!file.exists()) {
                // 尝试多种 URL 格式
                downloadWithFallback(url, file)
            }
            decodeSampledBitmap(file, MEDIA_MAX_DIMENSION_PX)?.let { bitmap ->
                BitmapDrawable(context.resources, bitmap)
            }
        }.getOrNull().also { drawable ->
            synchronized(lock) {
                if (drawable == null) {
                    failedUrlTimestamps[url] = System.currentTimeMillis()
                } else {
                    cache[key] = drawable
                    failedUrlTimestamps.remove(url)
                }
            }
        }
    }
    
    /**
     * 尝试多种 URL 格式下载图片
     * 主要针对 GITEE 的 URL 兼容性问题
     */
    private fun downloadWithFallback(originalUrl: String, output: File) {
        val urlsToTry = generateAlternativeUrls(originalUrl)
        
        for ((index, url) in urlsToTry.withIndex()) {
            try {
                download(url, output)
                return // 下载成功，返回
            } catch (e: IOException) {
                // 尝试下一个 URL
                if (index == urlsToTry.size - 1) {
                    // 所有 URL 都失败，抛出最后一个异常
                    throw e
                }
            }
        }
    }
    
    /**
     * 生成替代 URL 列表，用于处理不同的文件托管服务
     */
    private fun generateAlternativeUrls(originalUrl: String): List<String> {
        val urls = mutableListOf(originalUrl)
        
        // GITEE URL 处理
        if (originalUrl.contains("gitee.com")) {
            // 尝试不同的 GITEE raw URL 格式
            // 格式1: https://gitee.com/user/repo/raw/branch/path
            // 格式2: https://gitee.com/user/repo/blob/branch/path?raw=true
            // 格式3: 添加 access_token 参数（如果有）
            
            // 尝试 blob URL + raw=true 参数
            val blobUrl = originalUrl.replace("/raw/", "/blob/") + "?raw=true"
            if (blobUrl != originalUrl) {
                urls.add(blobUrl)
            }
            
            // 尝试不同的分支名称
            if (originalUrl.contains("/main/")) {
                urls.add(originalUrl.replace("/main/", "/master/"))
                urls.add(originalUrl.replace("/raw/main/", "/blob/master/") + "?raw=true")
            } else if (originalUrl.contains("/master/")) {
                urls.add(originalUrl.replace("/master/", "/main/"))
                urls.add(originalUrl.replace("/raw/master/", "/blob/main/") + "?raw=true")
            }
        }
        
        return urls
    }

    fun hero(appId: String): Drawable? {
        val fileName = when (appId) {
            "rokid-connect-hud" -> "rokid-connect-hud.jpg"
            "dew-browser" -> "dew-browser.jpg"
            "ek-reader" -> "ek-reader.jpg"
            "ek-trans" -> "ek-trans.jpg"
            else -> null
        } ?: return null

        return load(fileName)
    }

    private fun cachedImageFile(url: String): File {
        val dir = File(context.cacheDir, "registry-media").apply { mkdirs() }
        return File(dir, url.sha256())
    }

    private fun download(url: String, output: File) {
        HttpClient.download(
            url = url,
            output = output,
            connectTimeout = 10000,
            readTimeout = 30000,
        )
        // 验证文件大小
        if (output.length() < 10) {
            output.delete()
            throw IOException("Empty or too small image file")
        }
    }

    private fun decodeSampledBitmap(data: ByteArray, maxDimension: Int): Bitmap? {
        return try {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeByteArray(data, 0, data.size, options)
            
            options.inSampleSize = calculateInSampleSize(options, maxDimension, maxDimension)
            options.inJustDecodeBounds = false
            
            BitmapFactory.decodeByteArray(data, 0, data.size, options)
        } catch (e: Exception) {
            null
        }
    }

    private fun decodeSampledBitmap(file: File, maxDimension: Int): Bitmap? {
        return try {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(file.absolutePath, options)
            
            if (options.outWidth == -1 || options.outHeight == -1) {
                return null
            }
            
            options.inSampleSize = calculateInSampleSize(options, maxDimension, maxDimension)
            options.inJustDecodeBounds = false
            
            BitmapFactory.decodeFile(file.absolutePath, options)
        } catch (e: Exception) {
            null
        }
    }

    private fun calculateInSampleSize(options: BitmapFactory.Options, reqWidth: Int, reqHeight: Int): Int {
        val height = options.outHeight
        val width = options.outWidth
        var inSampleSize = 1

        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    private fun String.sha256(): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
