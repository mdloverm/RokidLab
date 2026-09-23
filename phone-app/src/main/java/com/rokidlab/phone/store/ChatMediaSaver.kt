package com.rokidlab.phone.store

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.IOException

/**
 * 聊天卡片的「保存到手机」统一出口：代码/文本 → 下载目录，图片 → 图片目录/RokidLab。
 *
 * - API 29+ 走 MediaStore（用户在系统文件管理器可见，无需存储权限）；
 * - API 28 回退到 App 专属外部目录（免权限，Toast 里回告完整路径）。
 *
 * 与 [com.rokidlab.phone.ai.WebTools] 里的私有保存逻辑刻意不共用：那是给工具层/模型
 * 返回结果用的（还带知识库双写），这里是**用户点按钮**的纯本地动作，语义不同，
 * 抽出共用函数反而要在两边各加参数开关。
 */
internal object ChatMediaSaver {

    /** 保存文本文件到下载目录，返回给用户看的位置描述（文件名或完整路径）。 */
    fun saveTextToDownloads(ctx: Context, fileName: String, mime: String, content: String): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("无法在下载目录创建文件")
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray(Charsets.UTF_8)) }
                ?: throw IOException("无法写入文件内容")
            ctx.contentResolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null,
            )
            return fileName
        }
        val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: ctx.filesDir
        val file = File(dir, fileName)
        file.writeText(content, Charsets.UTF_8)
        return file.absolutePath
    }

    /** 保存图片原始字节到 图片目录/RokidLab，返回给用户看的位置描述。 */
    fun saveImage(ctx: Context, fileName: String, mime: String, bytes: ByteArray): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // 同名先删后写：重复保存同一张图不产生 (1)(2) 垃圾副本
            ctx.contentResolver.delete(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?",
                arrayOf("${Environment.DIRECTORY_PICTURES}/RokidLab/", fileName),
            )
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/RokidLab")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = ctx.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("无法在图片目录创建文件")
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: throw IOException("无法写入图片内容")
            ctx.contentResolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null,
            )
            return "Pictures/RokidLab/$fileName"
        }
        val dir = File(ctx.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: ctx.filesDir, "RokidLab")
        dir.mkdirs()
        val file = File(dir, fileName)
        file.writeBytes(bytes)
        return file.absolutePath
    }
}
