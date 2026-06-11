package com.rokidlab.phone

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

private const val MEDIA_MAX_DIMENSION_PX = 1080

class MediaLoader(private val context: Context) {
    private val cache = ConcurrentHashMap<String, Drawable>()
    private val missing = ConcurrentHashMap.newKeySet<String>()
    private val failedUrls = ConcurrentHashMap.newKeySet<String>()

    fun load(assetName: String?, url: String? = null): Drawable? {
        return loadLocal(assetName) ?: loadRemote(url)
    }

    fun clearCache() {
        cache.clear()
        missing.clear()
        failedUrls.clear()
        // 清除磁盘缓存
        File(context.cacheDir, "registry-media").deleteRecursively()
    }

    private fun loadLocal(assetName: String?): Drawable? {
        if (assetName.isNullOrBlank()) return null
        val key = "asset:$assetName"
        cache[key]?.let { return it }
        if (missing.contains(key)) return null
        return runCatching {
            context.assets.open("media/$assetName").use { input ->
                decodeSampledBitmap(input.readBytes(), MEDIA_MAX_DIMENSION_PX)?.let { bitmap ->
                    BitmapDrawable(context.resources, bitmap)
                }
            }
        }.getOrNull().also { drawable ->
            if (drawable == null) missing.add(key) else cache[key] = drawable
        }
    }

    private fun loadRemote(url: String?): Drawable? {
        if (url.isNullOrBlank()) return null
        val key = "remote:$url"
        
        // 检查是否有缓存
        cache[key]?.let { return it }
        
        // 检查是否最近失败过
        if (failedUrls.contains(url)) {
            // 5分钟后重试
            return null
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
            if (drawable == null) {
                failedUrls.add(url)
                // 5分钟后自动清除失败记录
                Thread {
                    Thread.sleep(5 * 60 * 1000)
                    failedUrls.remove(url)
                }.start()
            } else {
                cache[key] = drawable
                failedUrls.remove(url)
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
        val temp = File(output.parentFile, "${output.name}.tmp")
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000
            readTimeout = 30000
            requestMethod = "GET"
            setRequestProperty("User-Agent", "RokidLab/1.0.0")
            setRequestProperty("Accept", "image/jpeg,image/png,image/webp,image/*")
        }
        try {
            connection.connect()
            
            // 检查HTTP状态码
            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("HTTP error: $responseCode")
            }
            
            // 检查Content-Type
            val contentType = connection.contentType
            if (contentType != null && !contentType.startsWith("image/")) {
                throw IOException("Invalid content type: $contentType")
            }
            
            connection.inputStream.use { input ->
                temp.outputStream().use { outputStream -> 
                    input.copyTo(outputStream)
                }
            }
            
            // 验证文件大小
            if (temp.length() < 10) {
                throw IOException("Empty or too small image file")
            }
            
            temp.renameTo(output)
        } finally {
            connection.disconnect()
            if (temp.exists()) {
                temp.delete()
            }
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
