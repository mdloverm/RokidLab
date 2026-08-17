package com.rokidlab.phone.util

import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/** 统一 HTTP 网络请求工具，封装 HttpURLConnection 的常见操作 */
object HttpClient {

    fun getString(
        url: String,
        connectTimeout: Int = 6000,
        readTimeout: Int = 9000,
        headers: Map<String, String> = emptyMap(),
    ): String {
        val connection = openConnection(url, connectTimeout, readTimeout, headers)
        try {
            connection.connect()
            checkResponse(connection)
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
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
        val connection = openConnection(url, connectTimeout, readTimeout, headers).apply {
            requestMethod = "POST"
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            useCaches = false
        }
        try {
            connection.connect()
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            return stream?.bufferedReader()?.use { it.readText() }
                ?: throw java.io.IOException("HTTP $code: ${connection.responseMessage}")
        } finally {
            connection.disconnect()
        }
    }

    fun download(url: String, output: File, connectTimeout: Int = 10000, readTimeout: Int = 30000) {
        val temp = File(output.parentFile, "${output.name}.tmp")
        val connection = openConnection(url, connectTimeout, readTimeout)
        try {
            connection.connect()
            checkResponse(connection)
            connection.inputStream.use { input ->
                temp.outputStream().use { out -> input.copyTo(out) }
            }
            if (!temp.renameTo(output)) {
                temp.copyTo(output, overwrite = true)
                temp.delete()
            }
        } finally {
            connection.disconnect()
            if (temp.exists() && !output.exists()) temp.delete()
        }
    }

    fun downloadWithPercent(
        url: String,
        output: File,
        connectTimeout: Int = 20000,
        readTimeout: Int = 120000,
        onPercent: (Int) -> Unit,
    ) {
        val connection = openConnection(url, connectTimeout, readTimeout).apply {
            setRequestProperty("User-Agent", "RokidLab/1.0")
        }
        try {
            connection.connect()
            checkResponse(connection)
            val total = connection.contentLengthLong.takeIf { it > 0L } ?: -1L
            connection.inputStream.use { input ->
                output.outputStream().use { out ->
                    val buffer = ByteArray(8192)
                    var copied = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                        copied += read
                        if (total > 0L) onPercent(((copied * 100L) / total).toInt())
                    }
                }
            }
        } catch (e: Exception) {
            output.delete()  // 清理部分下载的文件
            throw e
        } finally {
            connection.disconnect()
        }
    }

    fun openStream(
        url: String,
        connectTimeout: Int = 10000,
        readTimeout: Int = 30000,
        headers: Map<String, String> = emptyMap(),
    ): InputStream {
        val connection = openConnection(url, connectTimeout, readTimeout, headers)
        connection.connect()
        checkResponse(connection)
        return connection.inputStream
    }

    // -- private helpers --

    private fun openConnection(
        url: String,
        connectTimeout: Int,
        readTimeout: Int,
        headers: Map<String, String> = emptyMap(),
    ): HttpURLConnection {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            this.connectTimeout = connectTimeout
            this.readTimeout = readTimeout
            requestMethod = "GET"
            instanceFollowRedirects = true
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        return connection
    }

    private fun checkResponse(connection: HttpURLConnection) {
        val code = connection.responseCode
        if (code != HttpURLConnection.HTTP_OK) {
            throw java.io.IOException("HTTP $code: ${connection.responseMessage}")
        }
    }
}
