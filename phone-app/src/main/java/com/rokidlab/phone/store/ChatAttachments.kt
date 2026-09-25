package com.rokidlab.phone.store

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import android.util.Log
import com.rokidlab.phone.ai.TextEncoding
import java.io.ByteArrayOutputStream
import java.io.File

/** 选中的图片：气泡用的本地 URL + 发给模型的 base64（JPEG） */
internal data class PickedImage(
    /** `file://` 绝对路径 —— 落盘在 files/chat_attachments，历史记录里只存这一行，不塞 base64 */
    val fileUrl: String,
    /** 给模型的多模态载荷（**JPEG**，本体不含 `data:` 前缀，见 AiConversationService 的拼装） */
    val base64Jpeg: String,
)

/** 选中的文本文件：显示名 + 解码后的正文 */
internal data class PickedText(
    val name: String,
    val text: String,
    /** 原文超长被截断（UI 要如实提示，别让用户以为模型读了全文） */
    val truncated: Boolean,
    /**
     * 存档路径（`files/chat_attachments/…`）。
     *
     * 为什么要把用户选的文件**再存一份**：SAF 的 content:// Uri 只在本次授权范围内可用，
     * 聊天历史里存那个 Uri、过一段时间（或重启后）就打不开了 —— 文件卡会变成永久"读取失败"。
     * 存一份纯文本副本，卡片才能长期打开。代价是一点磁盘（正文上限 4MB）。
     */
    val savedPath: String? = null,
)

/**
 * 聊天附件读取（「发送给大模型」的两个来源：文件 / 图片）。
 *
 * 为什么自己写而不是直接丢给模型：
 *  - **图片**：模型走的是 `data:image/jpeg;base64,…` 单分片（见 AiConversationService），
 *    所以必须压成 JPEG 并降采样 —— 相册原图动辄 5~10MB，base64 再涨 33%，直接把上下文预算烧掉。
 *    同时把压缩后的图落盘（files/chat_attachments），气泡只记一行 `file://` 路径：
 *    历史记录 JSONL 里塞 base64 会让聊天记录文件膨胀到几十 MB。
 *  - **文件**：模型只能读文本，所以按 [TextEncoding] 的解码链（BOM → UTF-16 → UTF-8 → GB18030）
 *    读成字符串再注入上下文；二进制/超大文件如实拒绝或截断并提示。
 *
 * 全部方法为**阻塞调用，必须在 IO 线程执行**。
 */
internal object ChatAttachments {
    private const val TAG = "ChatAttachments"

    /**
     * 文本类扩展名白名单与判定：口径已抽到 [com.rokidlab.phone.ai.TextFileKind]
     * （ai 层的容器产出筛选也要用同一份，而 ai 不能依赖本 UI 层）。
     *
     * 为什么必须挡：模型只能读文本，而**用户不知道这件事** —— 他选了一个 PDF/Word，
     * 我们按字节解出满屏乱码发过去，模型的回答就会离谱（"你在说什么"），
     * 而用户看到的现象是"AI 变傻了"，根本联想不到是文件格式的问题。
     * 宁可当场说"这类文件我读不了"，也不要制造一次注定失败的回答。
     */
    fun extOf(name: String): String = com.rokidlab.phone.ai.TextFileKind.extOf(name)

    /** 见 [com.rokidlab.phone.ai.TextFileKind.isTextLike] */
    fun isTextLike(name: String, mime: String?): Boolean =
        com.rokidlab.phone.ai.TextFileKind.isTextLike(name, mime)

    /** 文件选择器的 MIME 过滤（与白名单同源，见 [com.rokidlab.phone.ai.TextFileKind.PICKER_MIMES]） */
    val pickerMimes: Array<String> get() = com.rokidlab.phone.ai.TextFileKind.PICKER_MIMES

    /** 图片选择器的 MIME 过滤（只列能解码的，见 [com.rokidlab.phone.ai.TextFileKind.PICKER_IMAGE_MIMES]） */
    val pickerImageMimes: Array<String> get() = com.rokidlab.phone.ai.TextFileKind.PICKER_IMAGE_MIMES

    /**
     * 生成本次拍照的输出文件 Uri（走已声明的 FileProvider）。
     *
     * 为什么不用 MediaStore/Intent 返回的缩略图：`EXTRA_OUTPUT` 缺省时相机只回一张 100px 级的
     * 缩略图，发给模型等于没给。所以必须自己给一个可写 Uri 让相机把原图写进来。
     */
    fun newCameraOutput(ctx: Context): Uri? = runCatching {
        val dir = File(ctx.cacheDir, "chat_camera").apply { mkdirs() }
        val file = File(dir, "shot-${System.currentTimeMillis()}.jpg")
        file.createNewFile()
        androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
    }.onFailure { Log.e(TAG, "newCameraOutput failed", it) }.getOrNull()

    /** 送给模型的图片长边上限（够识别细节，又不会把上下文吃光） */
    private const val MAX_IMAGE_EDGE = 1280

    /** JPEG 质量：85 是"肉眼无损"与体积的常用平衡点 */
    private const val JPEG_QUALITY = 85

    /** 单个文本文件读取上限（字节）：再大多半不是"给模型看的材料" */
    private const val MAX_TEXT_BYTES = 4 * 1024 * 1024

    /** 注入上下文的正文上限（字符）：超出截断并提示，避免一轮就把窗口撑爆 */
    private const val MAX_TEXT_CHARS = 120_000

    /** 附件落盘目录（应用私有，不需要任何存储权限） */
    private fun dir(ctx: Context): File = File(ctx.filesDir, "chat_attachments")

    /** 取出 SAF 给出的原始文件名（拿不到就退回 uri 末段） */
    fun displayName(ctx: Context, uri: Uri): String {
        runCatching {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) {
                    val name = c.getString(idx)
                    if (!name.isNullOrBlank()) return name
                }
            }
        }.onFailure { Log.w(TAG, "displayName failed: ${it.message}") }
        return uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "文件"
    }

    /**
     * 读取选中的图片：降采样 → 按 EXIF 校正方向 → JPEG → 落盘 + base64。
     *
     * EXIF 方向必须处理：`BitmapFactory` **不会**读它，竖拍的照片直接压缩会变成横躺
     * （用户在相册里看是正的、发出去歪 90°，这类 bug 极难归因）。
     */
    fun readImage(ctx: Context, uri: Uri): PickedImage? = runCatching {
        val raw = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        // 先量尺寸再采样解码：直接 decode 大图会 OOM
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (longest / sample > MAX_IMAGE_EDGE * 2) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(
            raw,
            0,
            raw.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return null

        val oriented = applyExifOrientation(raw, decoded)
        val scaled = scaleToMaxEdge(oriented, MAX_IMAGE_EDGE)
        val jpeg = ByteArrayOutputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            out.toByteArray()
        }
        if (scaled !== oriented) oriented.recycle()
        if (oriented !== decoded) decoded.recycle()

        val target = dir(ctx).apply { mkdirs() }
        val file = File(target, "img-${System.currentTimeMillis()}.jpg")
        file.writeBytes(jpeg)
        Log.i(TAG, "readImage: ${file.name} ${jpeg.size} B (${scaled.width}x${scaled.height})")
        PickedImage(
            fileUrl = "file://" + file.absolutePath,
            base64Jpeg = Base64.encodeToString(jpeg, Base64.NO_WRAP),
        )
    }.onFailure { Log.e(TAG, "readImage failed", it) }.getOrNull()

    private fun applyExifOrientation(raw: ByteArray, bmp: Bitmap): Bitmap = runCatching {
        val orientation = ExifInterface(raw.inputStream()).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            else -> return bmp
        }
        Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
    }.getOrDefault(bmp)

    private fun scaleToMaxEdge(bmp: Bitmap, maxEdge: Int): Bitmap {
        val longest = maxOf(bmp.width, bmp.height)
        if (longest <= maxEdge) return bmp
        val ratio = maxEdge.toFloat() / longest
        return Bitmap.createScaledBitmap(
            bmp,
            (bmp.width * ratio).toInt().coerceAtLeast(1),
            (bmp.height * ratio).toInt().coerceAtLeast(1),
            true,
        )
    }

    /**
     * 读取选中的文件正文。
     *
     * 走 [TextEncoding.decode]（项目里知识库导入用的同一条解码链）——**不能**直接 `String(bytes)`：
     * 中文用户的 txt 多为 Windows 记事本存的 GB18030，按 UTF-8 解会得到满屏乱码，
     * 模型也会把乱码当正文。解码链已在 kb-rag 那条线上验证过。
     */
    fun readText(ctx: Context, uri: Uri): PickedText? = runCatching {
        val name = displayName(ctx, uri)
        val bytes = ctx.contentResolver.openInputStream(uri)?.use { input ->
            // 边读边截断：**不能**先 readBytes() 再截 —— 用户完全可能误选一个几百 MB 的视频，
            // 一次性读进来直接 OOM（崩溃点还离"选文件"很远，极难归因）。
            val out = ByteArrayOutputStream()
            val chunk = ByteArray(64 * 1024)
            var total = 0
            while (total < MAX_TEXT_BYTES) {
                val n = input.read(chunk, 0, minOf(chunk.size, MAX_TEXT_BYTES - total))
                if (n <= 0) break
                out.write(chunk, 0, n)
                total += n
            }
            out.toByteArray()
        } ?: return null
        val decoded = TextEncoding.decode(bytes)
        val truncated = decoded.length > MAX_TEXT_CHARS
        val text = if (truncated) decoded.take(MAX_TEXT_CHARS) else decoded
        // 存一份纯文本副本供文件卡长期打开（见 PickedText.savedPath）
        val saved = runCatching {
            val dir = dir(ctx).apply { mkdirs() }
            val stamp = System.currentTimeMillis()
            val safe = name.replace(Regex("[^\\w.\\-\\u4e00-\\u9fa5]"), "_").take(60)
            val f = File(dir, "attach-$stamp-$safe.txt")
            f.writeText(text)
            f.absolutePath
        }.onFailure { Log.w(TAG, "cache picked file failed: ${it.message}") }.getOrNull()
        Log.i(TAG, "readText: $name ${bytes.size} B chars=${text.length} truncated=$truncated")
        PickedText(
            name = name,
            text = text,
            truncated = truncated,
            savedPath = saved,
        )
    }.onFailure { Log.e(TAG, "readText failed", it) }.getOrNull()
}
