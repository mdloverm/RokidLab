package com.rokidlab.phone.ai.embedding

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 一批文本的向量结果。[vectors] 与请求文本**同序**，每个向量已归一化为单位长度
 * （之后余弦相似度 = 点积，省掉检索时反复求模）。
 */
data class EmbeddingBatch(
    val model: String,
    val vectors: List<FloatArray>,
) {
    val dim: Int get() = vectors.firstOrNull()?.size ?: 0
}

/**
 * OpenAI 兼容 `/embeddings` 客户端（混合检索的语义召回通道）。
 *
 * 刻意保持与 [com.rokidlab.phone.ai.OpenAiService] 同级别的「裸 HttpURLConnection + 无新依赖」实现：
 * 主流服务商（OpenAI / 硅基流动 / DashScope 兼容模式 / 智谱 / 本地 Ollama / vLLM）
 * 都提供 `POST {base}/embeddings`，入参 `{"model","input":[...]}`、
 * 出参 `{"data":[{"index":0,"embedding":[...]}]}`，协议高度统一。
 *
 * 失败语义：一切网络/协议问题抛异常，由调用方决定回退（检索侧回退纯 BM25，
 * 索引侧保留未嵌入状态等下次重建）——**绝不因为向量通道故障让知识库检索整体不可用**。
 */
object EmbeddingClient {
    private const val TAG = "EmbeddingClient"
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 30_000

    /** 单次请求最多带多少段（chunks 约 500 字，16 段请求体 <20KB，各家都能接） */
    const val BATCH_SIZE = 16

    /**
     * 自动探测候选模型（按在国内 OpenAI 兼容服务上的可得性排序）。
     *
     * - `BAAI/bge-m3`：硅基流动等，中英双语，事实标准；
     * - `text-embedding-v3/v2`：DashScope 兼容模式；
     * - `text-embedding-3-small`：OpenAI 及大量中转站；
     * - `embedding-3`：智谱；
     * - `nomic-embed-text` / `bge-m3`：本地 Ollama（走 /v1/embeddings）。
     */
    val CANDIDATE_MODELS: List<String> = listOf(
        "BAAI/bge-m3",
        "text-embedding-v3",
        "text-embedding-3-small",
        "text-embedding-v2",
        "embedding-3",
        "bge-m3",
        "nomic-embed-text",
        "mxbai-embed-large",
    )

    /**
     * 探测端点支持哪个嵌入模型：依次用候选名发一次最小请求，第一个 200 的胜出。
     * 全部失败返回 null（调用方提示「当前服务商不支持 embeddings」，语义检索保持关闭）。
     *
     * 阻塞方法，仅在 IO 线程由用户显式动作触发（开启/检测按钮）。
     */
    fun probeModel(baseUrl: String, apiKey: String, timeoutMs: Int = 12_000): String? {
        for (model in CANDIDATE_MODELS) {
            try {
                val batch = embed(baseUrl, apiKey, model, listOf("ok"), timeoutMs)
                if (batch.dim > 0) {
                    Log.i(TAG, "probeModel: $model accepted (dim=${batch.dim})")
                    return model
                }
            } catch (e: Exception) {
                Log.i(TAG, "probeModel: $model not available (${e.message})")
            }
        }
        return null
    }

    /** 批量嵌入（阻塞）。[texts] 不得为空，单段建议 ≤2000 字符。 */
    fun embed(
        baseUrl: String,
        apiKey: String,
        model: String,
        texts: List<String>,
        readTimeoutMs: Int = READ_TIMEOUT_MS,
    ): EmbeddingBatch {
        require(texts.isNotEmpty()) { "embedding input is empty" }
        val endpoint = embeddingsEndpoint(baseUrl)
        val payload = JSONObject()
            .put("model", model)
            .put("input", JSONArray().apply { texts.forEach { put(it) } })
            .toString()

        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = readTimeoutMs
            doOutput = true
            instanceFollowRedirects = false
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            if (apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer $apiKey")
        }
        try {
            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val body = readBody(if (code in 200..299) conn.inputStream else conn.errorStream)
            if (code !in 200..299) {
                throw IllegalStateException("embeddings HTTP $code: ${body.take(300)}")
            }
            val root = JSONObject(body)
            val data = root.optJSONArray("data")
                ?: throw IllegalStateException("embeddings response missing data[]")
            // 服务端不保证按请求顺序返回：按 index 归位
            val byIndex = HashMap<Int, FloatArray>(data.length())
            var dim = 0
            for (i in 0 until data.length()) {
                val item = data.getJSONObject(i)
                val vec = parseFloatArray(item.optJSONArray("embedding"))
                if (vec.isEmpty()) continue
                dim = vec.size
                normalize(vec)
                byIndex[item.optInt("index", i)] = vec
            }
            val ordered = texts.indices.map { idx ->
                byIndex[idx]
                    ?: throw IllegalStateException("embeddings response missing index=$idx")
            }
            val resolvedModel = root.optString("model", model).ifBlank { model }
            return EmbeddingBatch(resolvedModel, ordered)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 拼 embeddings 端点：复用 OpenAiService 对 baseUrl 的宽容规则——
     * 去掉尾斜杠；若已填到 `/chat/completions` 也能纠正；补 `/embeddings`。
     */
    private fun embeddingsEndpoint(baseUrl: String): String {
        var base = baseUrl.trim().trimEnd('/')
        if (base.endsWith("/chat/completions")) base = base.removeSuffix("/chat/completions")
        return "$base/embeddings"
    }

    private fun parseFloatArray(arr: JSONArray?): FloatArray {
        if (arr == null || arr.length() == 0) return FloatArray(0)
        val out = FloatArray(arr.length())
        for (i in 0 until arr.length()) out[i] = arr.getDouble(i).toFloat()
        return out
    }

    /** 原地 L2 归一化；零向量保持零向量（点积结果自然为 0，不会 NaN） */
    private fun normalize(v: FloatArray) {
        var sum = 0.0
        for (x in v) sum += x.toDouble() * x
        val norm = Math.sqrt(sum)
        if (norm > 1e-12) for (i in v.indices) v[i] = (v[i] / norm).toFloat()
    }

    private fun readBody(stream: java.io.InputStream?): String {
        if (stream == null) return ""
        val out = ByteArrayOutputStream()
        stream.use { it.copyTo(out) }
        return out.toString(Charsets.UTF_8.name())
    }
}
