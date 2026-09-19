package com.rokidlab.phone.ai.llm

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Base64
import android.util.Log
import com.rokidlab.phone.domain.AiConfig
import com.rokidlab.phone.util.HttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * 模型能力探测（`llm` 接缝里把"猜"变成"实测"的那一步）。
 *
 * 目前只探测**图像输入**。理由：它是唯一一个"猜错就要用户白等一整轮"的能力 ——
 * 开关开着但模型不吃图时，请求会被服务端拒绝，我们只能回退 OCR 重来，
 * 用户看到的是「识别中…」卡十几秒再出结果。其余能力（工具/流式/关思考字段）
 * 猜错的表现要么是立刻报错、要么是本来就没开的开关，收益远不如它。
 * 真需要时再加，[Outcome] 与缓存结构都是按"可扩展成多项探测"设计的。
 *
 * ⚠️ 探测一次 = 发一次**真实计费请求**（极小：1×1 图片 + 8 个输出 token）。
 * 因此调用纪律是：**只由用户显式触发，或保存新模型时后台做一次**，
 * 结果按 `baseUrl|model` 缓存（见 [CapabilityCache]），绝不每次拍照都探。
 */
internal object ModelCapabilityProbe {
    private const val TAG = "ModelCapProbe"

    /**
     * 探测结论。
     *
     * [Inconclusive] 是刻意保留的第三种结果，而不是把"没探出结论"塞进 Rejected：
     * 网络超时、密钥失效、429 限流都跟"支不支持看图"毫无关系，
     * 把它们当成"不支持"会让用户在换了个 Wi-Fi 之后就平白失去一个能用的能力。
     * **只有服务端明确说图画不了，才允许下否定结论。**
     */
    sealed interface Outcome {
        /** 服务端接受了图像分片 → 确认支持 */
        data class Accepted(val latencyMs: Long) : Outcome

        /** 服务端明确拒绝图像输入（4xx 且错误正文指向图像/内容类型/多模态） */
        data class Rejected(val httpCode: Int, val detail: String) : Outcome

        /** 得不出结论（超时/网络/鉴权/限流/无法解读的报错）—— 保持原有判断不变 */
        data class Inconclusive(val reason: String) : Outcome
    }

    /** 错误正文里出现这些词，才把它读成"这个模型不吃图" */
    private val IMAGE_REJECT_MARKERS = listOf(
        "image", "vision", "visual", "multimodal", "multi-modal",
        "content type", "content_type", "image_url",
        "不支持图", "图像", "图片",
    )

    /**
     * 实测一次「这个端点+模型收不收 image_url」。
     *
     * 阻塞方法，须在 IO 线程调用。
     *
     * @param timeoutMs 读超时。默认 20s：探测请求本身极小，但**首次调用本地模型可能要先加载**
     *   （几 GB 权重进内存要几十秒），本地端点调用方应放宽到 60s 以上。
     */
    fun probeImage(cfg: AiConfig, timeoutMs: Int = 20_000): Outcome {
        if (cfg.baseUrl.isBlank() || cfg.model.isBlank()) {
            return Outcome.Inconclusive("baseUrl/model 为空")
        }
        val base = cfg.baseUrl.trimEnd('/')
        val endpoint = if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
        val body = buildProbeBody(cfg.model) ?: return Outcome.Inconclusive("本地图片编码失败")

        val headers = buildMap {
            if (cfg.apiKey.isNotBlank()) put("Authorization", "Bearer ${cfg.apiKey}")
            put("Content-Type", "application/json; charset=utf-8")
        }
        val started = System.currentTimeMillis()
        return try {
            val res = HttpClient.postStringRaw(
                url = endpoint,
                body = body.toString(),
                readTimeout = timeoutMs,
                headers = headers,
            )
            val ms = System.currentTimeMillis() - started
            when {
                res.ok -> {
                    Log.i(TAG, "probeImage: accepted (HTTP ${res.code}) in ${ms}ms model=${cfg.model}")
                    Outcome.Accepted(ms)
                }
                res.code in 400..499 -> {
                    if (looksLikeImageRejection(res.body)) {
                        Log.i(TAG, "probeImage: rejected (HTTP ${res.code}) model=${cfg.model} body=${res.body.take(200)}")
                        Outcome.Rejected(res.code, res.body.take(200))
                    } else {
                        // 4xx 但不是"图画不了"：多半是密钥/余额/模型名/参数问题，与图像能力无关
                        Log.w(TAG, "probeImage: HTTP ${res.code} but not image-related, inconclusive: ${res.body.take(200)}")
                        Outcome.Inconclusive("HTTP ${res.code}（与图像能力无关）")
                    }
                }
                else -> Outcome.Inconclusive("HTTP ${res.code}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "probeImage: failed with ${e.javaClass.simpleName}: ${e.message}")
            Outcome.Inconclusive(e.javaClass.simpleName)
        }
    }

    /** 探测请求体：文本在前、图片在后（与真实拍照路径的消息形态一致，才探得准） */
    private fun buildProbeBody(model: String): JSONObject? {
        val b64 = tinyJpegBase64() ?: return null
        val content = JSONArray()
            .put(
                JSONObject().apply {
                    put("type", "text")
                    put("text", "这张图是什么颜色？只回一个词。")
                }
            )
            .put(
                JSONObject().apply {
                    put("type", "image_url")
                    put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$b64"))
                }
            )
        return JSONObject().apply {
            put("model", model)
            put("stream", false)
            // 只要一个词：探测成本压到最低（图片本身也只有几百字节）
            put("max_tokens", 8)
            put("temperature", 0)
            put("messages", JSONArray().put(JSONObject().apply {
                put("role", "user")
                put("content", content)
            }))
        }
    }

    /**
     * 现编一张 1×1 的 JPEG。
     *
     * **刻意不在代码里写 base64 常量**：手抄的常量一旦不是合法 JPEG，服务端会以
     * "invalid image data" 400 —— 那会被 [looksLikeImageRejection] 读成"不支持图像"，
     * 于是探测本身把结论带偏，还极难发现（探出来永远是"不支持"）。
     * 由系统编码器现场产出，合法性由平台保证。
     */
    private fun tinyJpegBase64(): String? = runCatching {
        val bmp = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        bmp.setPixel(0, 0, Color.RED)
        val out = ByteArrayOutputStream()
        val ok = bmp.compress(Bitmap.CompressFormat.JPEG, 60, out)
        bmp.recycle()
        if (!ok || out.size() == 0) null
        else Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }.getOrElse {
        Log.w(TAG, "tinyJpegBase64 failed: ${it.message}")
        null
    }

    /** 错误正文是否指向"图像输入不被接受" */
    private fun looksLikeImageRejection(body: String): Boolean {
        if (body.isBlank()) return false
        val b = body.lowercase()
        return IMAGE_REJECT_MARKERS.any { b.contains(it) }
    }
}
