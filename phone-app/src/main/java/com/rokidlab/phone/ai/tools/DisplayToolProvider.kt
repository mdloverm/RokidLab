package com.rokidlab.phone.ai.tools

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import com.rokid.cxr.Caps
import com.rokidlab.phone.ai.GlassToolConfirmChannel
import com.rokidlab.phone.ai.MusicPlayerController
import com.rokidlab.phone.ai.ToolRegistry
import com.rokidlab.phone.glasses.AiChannel
import com.rokidlab.phone.glasses.GlassesHandshake
import com.rokidlab.phone.glasses.LinkProtocol
import com.rokidlab.phone.store.ChatStateHolder
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 屏幕展示域工具（Phase X）：目前仅含 `show_image` —— 把一张图片渲染到手机端对话气泡里，
 * 并在眼镜已连接时同步到眼镜端悬浮图片层（12s 自动消失）；眼镜端失败不影响手机端。
 *
 * 实现要点：
 * - 不返回 Markdown / 文本给模型：模型拿到的是**如实的状态文本**（含眼镜端是否同步成功）。
 * - 真正产生手机端 UI 副作用的是 ChatStateHolder.addImage —— 把带 imageUrl 的 ChatMsg 加进全局
 *   消息列表，[ChatBubble] 检测到 imageUrl 时通过 [com.rokidlab.phone.store.ChatImageCache] 异步下载并渲染。
 * - ChatStateHolder 是 Compose 快照线程，工具运行在 worker 线程（见 ToolGateway.call），必须切主线程写入。
 */
internal object DisplayToolProvider : ToolProvider {
    private const val TAG = "DisplayToolProvider"

    override val toolNames = setOf("show_image")

    override fun execute(context: Context, name: String, args: JSONObject): String {
        return when (name) {
            "show_image" -> showImage(context, args)
            else -> throw IllegalArgumentException("未知工具: $name")
        }
    }

    private fun showImage(context: Context, args: JSONObject): String {
        var rawUrl = args.optString("imageUrl").trim()
        var caption = args.optString("caption").trim()
        // 未给链接时兜底：显示当前播放歌曲的封面（覆盖“显示封面”“看下封面”这类说法）
        if (rawUrl.isEmpty()) {
            val cover = MusicPlayerController.currentCoverUrl
            if (cover.isNotBlank()) {
                rawUrl = cover
                if (caption.isBlank()) {
                    caption = listOf(MusicPlayerController.currentTitle, MusicPlayerController.currentArtist)
                        .filter { it.isNotBlank() }
                        .joinToString(" - ")
                }
            }
        }
        if (rawUrl.isEmpty()) {
            return "没有可显示的图片：请提供图片链接，或先播放一首歌"
        }
        // 仅放行 http/https，避免加载本地 file:// 等高风险协议
        val normalized = rawUrl.lowercase()
        if (!(normalized.startsWith("http://") || normalized.startsWith("https://"))) {
            return "图片链接不合法（仅支持 http/https）"
        }
        val mainHandler = Handler(Looper.getMainLooper())
        mainHandler.post {
            ChatStateHolder.addImage(
                isUser = false,
                imageUrl = rawUrl,
                caption = caption.ifBlank { "图片" },
            )
        }
        Log.i(TAG, "show_image queued: $rawUrl caption='$caption'")
        // 同步镜像到眼镜端悬浮图片层（手机端显示为主，眼镜端失败不影响手机端）。
        // ★ 必须把眼镜端结果如实回给模型：旧实现无论成败都返回「已显示图片」，
        //   于是眼镜没连/没跑 RokidLink 时模型照样说"已显示"，用户看到的是"说了没反应"。
        return when (val push = pushImageToGlasses(context, rawUrl, caption.ifBlank { "图片" })) {
            GlassesPush.OK ->
                if (caption.isNotBlank()) "已显示图片（手机端 + 眼镜端）：$caption" else "已显示图片（手机端 + 眼镜端）"
            GlassesPush.NOT_CONNECTED -> "已在手机端显示图片；眼镜端未连接，未能同步到眼镜"
            GlassesPush.APP_NOT_READY -> "已在手机端显示图片；眼镜端未就绪（RokidLink 未运行或未握手），未能同步到眼镜"
            GlassesPush.UNSUPPORTED -> "已在手机端显示图片；眼镜端版本不支持图片显示，仅手机端可见"
            GlassesPush.FAILED -> "已在手机端显示图片；同步到眼镜端失败"
        }
    }

    /** 眼镜端下发结果（用于向模型/用户如实回报，不再静默吞掉失败） */
    private enum class GlassesPush { OK, NOT_CONNECTED, APP_NOT_READY, UNSUPPORTED, FAILED }

    /** 眼镜端下发通道的 Base64 体积上限（超过则降规格或放弃，避免自定义指令下发失败） */
    private const val MAX_B64_CHARS = 400_000

    /**
     * 把图片下发到眼镜端悬浮图片层（[AiChannel.TOPIC_SHOW_IMAGE]）。
     *
     * 眼镜端不保证能自己上网，故由手机端下载 → 压到长边 ≤480px 的 JPEG → Base64 后经
     * CXR 自定义指令下发；眼镜端解码成 Bitmap 显示 12s。
     *
     * 眼镜未连接 / 版本不支持该能力（[LinkProtocol.Cap.SHOW_IMAGE]）/ 下发失败：只记日志，
     * 不影响手机端对话气泡的正常显示。
     *
     * ★ 眼镜端 `RokidLink` 没在跑时必须先经 ADB 拉起它（见 [ToolRegistry.ensureGlassesLinkRunning]）：
     *   CXR 自定义指令**只有 RokidLink 订阅着才能收到**，进程不在 = 指令石沉大海；
     *   而它在 `adb install -r` 之后是 `stopped=true`（无 BOOT_COMPLETED），不拉起来本功能必然"说了没反应"。
     */
    private fun pushImageToGlasses(context: Context, imageUrl: String, caption: String): GlassesPush {
        // 注意：本方法**同步**执行（下载 + 压缩 + 下发），以便把真实结果回给模型。
        // 工具本身运行在 worker 线程（见 ToolGateway.call），不会卡主线程。
        var capSupport = GlassesHandshake.supports(LinkProtocol.Cap.SHOW_IMAGE)
        if (capSupport == false) {
            Log.i(TAG, "glasses does not support show_image (old build), skip glasses push")
            return GlassesPush.UNSUPPORTED
        }
        var session = GlassToolConfirmChannel.global.liveSession()
        var link = session?.cxrLink
        // 会话不在 / 未握手（capSupport == null 基本等价于 RokidLink 进程没跑）→ 拉一次服务再重试。
        if (session == null || link == null || capSupport == null) {
            if (ToolRegistry.ensureGlassesLinkRunning(context)) {
                // 无界面启动前台服务通常 <1.5s 完成并回 HELLO 握手，等一小会儿再复查。
                runCatching { Thread.sleep(1500) }
                capSupport = GlassesHandshake.supports(LinkProtocol.Cap.SHOW_IMAGE)
                session = GlassToolConfirmChannel.global.liveSession()
                link = session?.cxrLink
                Log.i(TAG, "after ensureGlassesLinkRunning: capSupport=$capSupport session=${session != null}")
            }
        }
        if (session == null || link == null) {
            Log.i(TAG, "glasses not connected, skip glasses push (capSupport=$capSupport)")
            return GlassesPush.NOT_CONNECTED
        }
        val b64 = encodeForGlasses(imageUrl) ?: return GlassesPush.FAILED
        val target = link
        val targetSession = session
        return runCatching {
            val caps = Caps()
            caps.write(AiChannel.CMD_SHOW_IMAGE)
            caps.write(AiChannel.SCHEMA_VERSION.toString())
            caps.write(b64)
            caps.write(caption)
            val r = targetSession.rawSendCustomCmd(target, AiChannel.TOPIC_SHOW_IMAGE, caps)
            Log.i(TAG, "push image to glasses: base64Len=${b64.length} -> $r")
            when {
                r == 0 -> GlassesPush.OK
                // 未握手（capSupport == null）又下发失败 → 多半是眼镜端 RokidLink 没在运行，
                // 指令没有订阅者。区分出来便于用户知道「要去眼镜上打开一次 RokidLink」。
                capSupport == null -> GlassesPush.APP_NOT_READY
                else -> GlassesPush.FAILED
            }
        }.onFailure { Log.w(TAG, "push image to glasses failed: ${it.message}") }.getOrDefault(GlassesPush.FAILED)
    }

    /** 下载图片并按眼镜端通道规格压成 Base64 JPEG；失败/过大返回 null（静默跳过眼镜端）。 */
    private fun encodeForGlasses(imageUrl: String): String? {
        // 若正是当前播放歌曲的封面，直接复用播放器已下好的 Bitmap（320px），省一次网络往返。
        val cached = MusicPlayerController.currentCoverBitmap
            ?.takeIf { imageUrl == MusicPlayerController.currentCoverUrl }
        val raw = cached ?: runCatching {
            val conn = (URL(imageUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 6000
                readTimeout = 10000
            }
            conn.inputStream.use { BitmapFactory.decodeStream(it) }
        }.onFailure { Log.w(TAG, "glasses image download failed: ${it.message}") }.getOrNull()
            ?: return null
        var out = compressToBase64(raw, 480, 80)
        if (out.length > MAX_B64_CHARS) out = compressToBase64(raw, 320, 65)
        if (out.length > MAX_B64_CHARS) {
            Log.w(TAG, "image too large for glasses channel (${out.length} chars), skip glasses push")
            return null
        }
        return out
    }

    /** 长边缩到 maxSide 以内 → JPEG(quality) → Base64（NO_WRAP，避免换行破坏载荷）。 */
    private fun compressToBase64(bmp: Bitmap, maxSide: Int, quality: Int): String {
        val scaled = if (bmp.width <= maxSide && bmp.height <= maxSide) {
            bmp
        } else {
            val ratio = maxSide.toFloat() / maxOf(bmp.width, bmp.height)
            val w = (bmp.width * ratio).toInt().coerceAtLeast(1)
            val h = (bmp.height * ratio).toInt().coerceAtLeast(1)
            runCatching { Bitmap.createScaledBitmap(bmp, w, h, true) }.getOrDefault(bmp)
        }
        val bos = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, quality, bos)
        return Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
    }
}