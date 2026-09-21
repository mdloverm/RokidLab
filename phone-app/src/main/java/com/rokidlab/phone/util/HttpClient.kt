package com.rokidlab.phone.util

import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * HTTP 状态非 2xx（服务端明确拒绝了请求，而非网络抖动）。
 *
 * 为什么单独建类型而不是抛裸 `IOException`：调用方需要按状态码做不同处置 ——
 * 4xx（密钥失效 / 余额不足 / 模型不存在 / 参数非法）**重试多少次都不会成功**，
 * 必须立刻停手并把可行动的提示给用户；5xx 与超时才是值得退避重试的瞬时故障。
 */
class HttpStatusException(
    val code: Int,
    val body: String,
) : java.io.IOException("HTTP $code: ${body.take(400)}")

/**
 * 一次 POST 的原始结果（**非 2xx 不抛异常**），见 [HttpClient.postStringRaw]。
 *
 * 与 [HttpStatusException] 的分工：这里给的是"想自己看状态码和正文"的正常返回值，
 * 那里给的是"服务端拒绝了，我处理不了，往上抛"的异常。
 */
data class HttpRawResult(
    val code: Int,
    val body: String,
    /** 响应头（小写键名，同名头取第一个）。默认空 = 不关心头的调用方行为不变 */
    val headers: Map<String, String> = emptyMap(),
) {
    val ok: Boolean get() = code in 200..299
}

/**
 * 流式 POST 的**头部结果**：正文通过 [HttpClient.postStreamWithHeaders] 的回调逐行交付，
 * 这里只回状态码与响应头。
 *
 * 存在的理由：MCP 的 Streamable HTTP 必须在 `initialize` 的响应里读 `Mcp-Session-Id`
 * 并在后续请求回填 —— 只有头能提供它，正文给不了。
 */
data class HttpStreamResult(val code: Int, val headers: Map<String, String>) {
    val ok: Boolean get() = code in 200..299

    /** 按小写键名取响应头；缺失返回 null */
    fun header(name: String): String? = headers[name.lowercase()]
}

/**
 * OkHttp [okhttp3.Headers] → **小写键名** Map（同名头取第一个）。
 *
 * 为什么统一转小写：HTTP 头本就不区分大小写，而 `Mcp-Session-Id` 在各 server 实现里的
 * 写法并不一致（有写成 `mcp-session-id`、也有 `Mcp-Session-ID`）—— 调用方按小写查即可，
 * 不必关心对端怎么写。
 */
private fun okhttp3.Headers.toHeaderMap(): Map<String, String> =
    names().associateWith { name -> values(name).firstOrNull().orEmpty() }
        .entries.associate { (k, v) -> k.lowercase() to v }

/**
 * 统一 HTTP 网络请求工具：OkHttp 连接池实现（替代 HttpURLConnection）。
 *
 * - 连接池复用：商店批量图标下载 / AI 多轮对话等高频同主机请求免去每次 TCP+TLS 重握手
 * - 每请求独立超时通过 [clientFor] 派生（newBuilder 共享连接池与调度器，开销极小）
 * - 公开 API 与旧 HttpURLConnection 版本保持一致，调用方零改动
 */
object HttpClient {

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    /** 共享连接池：8 个空闲连接保活 5 分钟（够商店/AI 场景并发，不过度占资源） */
    private val baseClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectionPool(ConnectionPool(8, 5, TimeUnit.MINUTES))
            .retryOnConnectionFailure(true)
            .followRedirects(true)
            .build()
    }

    /** 按请求超时派生 client（共享 baseClient 的连接池/调度器） */
    private fun clientFor(connectTimeout: Int, readTimeout: Int): OkHttpClient =
        baseClient.newBuilder()
            .connectTimeout(connectTimeout.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(readTimeout.toLong(), TimeUnit.MILLISECONDS)
            .build()

    private fun buildRequest(
        url: String,
        method: String,
        headers: Map<String, String> = emptyMap(),
        body: String? = null,
    ): Request = Request.Builder()
        .url(url)
        .apply { headers.forEach { (k, v) -> header(k, v) } }
        .apply {
            when {
                body != null -> post(body.toRequestBody(JSON_MEDIA_TYPE))
                method == "GET" -> get()
                else -> method(method, null)
            }
        }
        .build()

    fun getString(
        url: String,
        connectTimeout: Int = 6000,
        readTimeout: Int = 9000,
        headers: Map<String, String> = emptyMap(),
    ): String {
        val request = buildRequest(url, "GET", headers)
        clientFor(connectTimeout, readTimeout).newCall(request).execute().use { response ->
            checkResponse(response)
            val body = response.body ?: throw java.io.IOException("empty response body: $url")
            return body.string()
        }
    }

    /**
     * 发送 POST 请求，返回响应文本。
     * 用于调用 AI API（如 DeepSeek）等需要 JSON body 的接口。
     */
    fun postString(
        url: String,
        body: String,
        connectTimeout: Int = 10000,
        readTimeout: Int = 60000,
        headers: Map<String, String> = emptyMap(),
    ): String {
        val request = buildRequest(url, "POST", headers, body)
        clientFor(connectTimeout, readTimeout).newCall(request).execute().use { response ->
            val body = response.body ?: throw java.io.IOException("empty response body: $url")
            val text = body.string()
            if (response.code !in 200..299) {
                throw java.io.IOException("HTTP ${response.code}: $text")
            }
            return text
        }
    }

    /**
     * 发送 POST 但**不把非 2xx 当异常**：把状态码与响应正文原样交还调用方。
     *
     * 存在的理由（[postString] 做不到的事）：有调用方需要**按状态码 + 错误正文自行判定**，
     * 而不是"失败了"这一个事实。典型是模型能力探测 —— 同为 400，
     * 「服务端说这模型不吃图」和「密钥错了 / 参数不合法」必须区别对待：
     * 前者可以下"不支持图像"的结论，后者不能（否则用户换个 Wi-Fi 就平白丢一个能力）。
     *
     * 为什么不复用 [postString] 再解析 `IOException.message`：那个 message 是
     * `"HTTP 400: <body>"` 的拼接串，正文里若出现换行/冒号就会解析错位，
     * 而且把异常当数据流是脆的。这里直接给结构化结果。
     *
     * @return [HttpRawResult]：`ok` 表示 2xx；网络层异常仍照常抛出（那是真失败，不是"服务端拒绝"）
     */
    fun postStringRaw(
        url: String,
        body: String,
        connectTimeout: Int = 10000,
        readTimeout: Int = 60000,
        headers: Map<String, String> = emptyMap(),
    ): HttpRawResult {
        val request = buildRequest(url, "POST", headers, body)
        clientFor(connectTimeout, readTimeout).newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            return HttpRawResult(response.code, text, response.headers.toHeaderMap())
        }
    }

    /**
     * 发送 POST 并逐行读取 SSE 流式响应（AI 聊天流式链路）。
     * [onLine] 返回 false 时提前停止读取（用户打断）。
     *
     * **非 2xx 必须抛 [HttpStatusException]**：AI 服务端拒绝请求时返回的是 JSON 错误体
     * （不是 SSE），把它当行流喂给累积器只会被逐行忽略 —— 上层拿到
     * 「content=null / toolCalls=0 / finish=none」的空轮，最终向用户回一句
     * 「抱歉，我暂时无法处理这个问题」，**真实的 401/402/400 被彻底掩盖**，
     * 排查时从日志里也看不到任何线索（2026-09-15 真机事故：连续空轮、
     * 日志只有 `chatTurnStream: toolCalls=0 content=null finish=none`）。
     * 因此这里读完 body 后显式抛出，把状态码与错误正文交给上层。
     */
    fun postSse(
        url: String,
        body: String,
        connectTimeout: Int = 15000,
        readTimeout: Int,
        headers: Map<String, String> = emptyMap(),
        onLine: (String) -> Boolean,
    ) {
        val request = buildRequest(url, "POST", headers, body)
        clientFor(connectTimeout, readTimeout).newCall(request).execute().use { response ->
            val stream = response.body?.byteStream()
                ?: throw java.io.IOException("HTTP ${response.code}: ${response.message}")
            if (response.code !in 200..299) {
                // 先把错误体读完再抛：直接 throw 会丢掉 body，而 body 里才是真正的原因
                val text = runCatching { stream.bufferedReader().use { it.readText() } }.getOrDefault("")
                throw HttpStatusException(response.code, text)
            }
            stream.bufferedReader().use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (!onLine(line)) break
                }
            }
        }
    }

    /**
     * POST 并**同时暴露响应头与逐行正文**（MCP Streamable HTTP 专用入口）。
     *
     * 为什么 [postSse] 不够用：
     *  ① `initialize` 的会话 ID 只在**响应头** `Mcp-Session-Id` 里，正文拿不到；
     *  ② MCP 的同一次请求既可能返回 `application/json`（一次性 JSON-RPC），
     *     也可能返回 `text/event-stream`（SSE 分包）—— [postSse] 假定后者，
     *     对前者只会把 JSON 当行流一行行喂下去，而调用方**看不到 Content-Type，无从分流**。
     *
     * 所以这里把「状态码 + 响应头」与「正文行」分开交付：调用方先看头，再决定怎么解析。
     *
     * ⚠️ 与 [postSse] 不同，**这里非 2xx 不抛异常**：MCP 的错误按 JSON-RPC 规范是正常
     * 响应体（`{"error":{…}}`），当成传输层失败会让上层丢掉错误码语义。
     * 真正的网络异常（连不上/超时）仍照常抛出。
     *
     * @param onLine 逐行回调，**含空行** —— SSE 用空行分隔事件，跳过空行会把事件粘在一起解析错；
     *   返回 false 提前停止读取（拿到目标响应即停，避免长连接挂到超时）
     */
    fun postStreamWithHeaders(
        url: String,
        body: String,
        connectTimeout: Int = 10000,
        readTimeout: Int = 20000,
        headers: Map<String, String> = emptyMap(),
        onLine: (String) -> Boolean,
    ): HttpStreamResult {
        val request = buildRequest(url, "POST", headers, body)
        clientFor(connectTimeout, readTimeout).newCall(request).execute().use { response ->
            val result = HttpStreamResult(response.code, response.headers.toHeaderMap())
            val stream = response.body?.byteStream()
                ?: throw java.io.IOException("HTTP ${response.code}: ${response.message}")
            stream.bufferedReader().use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (!onLine(line)) break
                }
            }
            return result
        }
    }

    fun download(url: String, output: File, connectTimeout: Int = 10000, readTimeout: Int = 30000) {
        val temp = File(output.parentFile, "${output.name}.tmp")
        val request = buildRequest(url, "GET")
        try {
            clientFor(connectTimeout, readTimeout).newCall(request).execute().use { response ->
                checkResponse(response)
                val body = response.body ?: throw java.io.IOException("empty response body: $url")
                body.byteStream().use { input ->
                    temp.outputStream().use { out -> input.copyTo(out) }
                }
            }
            if (!temp.renameTo(output)) {
                temp.copyTo(output, overwrite = true)
                temp.delete()
            }
        } finally {
            if (temp.exists() && !output.exists()) temp.delete()
        }
    }

    /**
     * 带进度回调下载。[isCancelled] 在每次缓冲读取时检查，返回 true 立即中止
     * （真实取消：立即停止读流并清理半成品文件，而不是让阻塞读跑完整个 body）。
     */
    fun downloadWithPercent(
        url: String,
        output: File,
        connectTimeout: Int = 20000,
        readTimeout: Int = 120000,
        onPercent: (Int) -> Unit,
        isCancelled: () -> Boolean = { false },
    ) {
        val request = buildRequest(url, "GET").newBuilder()
            .header("User-Agent", "RokidLab/1.0")
            .build()
        try {
            clientFor(connectTimeout, readTimeout).newCall(request).execute().use { response ->
                checkResponse(response)
                val body = response.body ?: throw java.io.IOException("empty response body: $url")
                val total = body.contentLength().takeIf { it > 0L } ?: -1L
                body.byteStream().use { input ->
                    output.outputStream().use { out ->
                        val buffer = ByteArray(8192)
                        var copied = 0L
                        while (true) {
                            if (isCancelled()) throw CancellationException("download cancelled")
                            val read = input.read(buffer)
                            if (read < 0) break
                            out.write(buffer, 0, read)
                            copied += read
                            if (total > 0L) onPercent(((copied * 100L) / total).toInt())
                        }
                    }
                }
            }
        } catch (e: Exception) {
            output.delete()  // 清理部分下载的文件
            throw e
        }
    }

    /**
     * 打开输入流（直接读取响应体）。调用方负责关闭流；
     * 流关闭时连接自动归还连接池（旧 HttpURLConnection 实现无此回收）。
     */
    fun openStream(
        url: String,
        connectTimeout: Int = 10000,
        readTimeout: Int = 30000,
        headers: Map<String, String> = emptyMap(),
    ): InputStream {
        val request = buildRequest(url, "GET", headers)
        val response = clientFor(connectTimeout, readTimeout).newCall(request).execute()
        if (response.code != 200) {
            val message = "HTTP ${response.code}: ${response.message}"
            response.close()
            throw java.io.IOException(message)
        }
        val body = response.body ?: throw java.io.IOException("empty response body: $url")
        return body.byteStream()
    }

    // -- private helpers --

    /** 与旧实现一致：仅 HTTP 200 视为成功 */
    private fun checkResponse(response: okhttp3.Response) {
        if (response.code != 200) {
            throw java.io.IOException("HTTP ${response.code}: ${response.message}")
        }
    }
}
