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
            return response.body!!.string()
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
            val text = response.body!!.string()
            if (response.code !in 200..299) {
                throw java.io.IOException("HTTP ${response.code}: $text")
            }
            return text
        }
    }

    /**
     * 发送 POST 并逐行读取 SSE 流式响应（AI 聊天流式链路）。
     * [onLine] 返回 false 时提前停止读取（用户打断）。
     * 兼容旧实现：非 2xx 时同样把错误 body 当作行流读取（由调用方解析）。
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
            stream.bufferedReader().use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (!onLine(line)) break
                }
            }
        }
    }

    fun download(url: String, output: File, connectTimeout: Int = 10000, readTimeout: Int = 30000) {
        val temp = File(output.parentFile, "${output.name}.tmp")
        val request = buildRequest(url, "GET")
        try {
            clientFor(connectTimeout, readTimeout).newCall(request).execute().use { response ->
                checkResponse(response)
                response.body!!.byteStream().use { input ->
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
                val body = response.body!!
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
        return response.body!!.byteStream()
    }

    // -- private helpers --

    /** 与旧实现一致：仅 HTTP 200 视为成功 */
    private fun checkResponse(response: okhttp3.Response) {
        if (response.code != 200) {
            throw java.io.IOException("HTTP ${response.code}: ${response.message}")
        }
    }
}
