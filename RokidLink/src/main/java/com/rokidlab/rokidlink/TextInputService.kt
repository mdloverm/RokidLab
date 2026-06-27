package com.rokidlab.rokidlink

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket

/**
 * 文本输入 TCP 服务器 — 端口 7656
 *
 * 手机连接到眼镜后，通过此端口发送文本：
 * 1. 发送文本 → 设剪贴板
 * 2. 自动执行 input keyevent KEYCODE_PASTE
 * 3. 返回 OK\n
 *
 * 直接在 MainActivity.onCreate() 中启动，不依赖 Service 生命周期，
 * 避免 Android 14+ 后台服务启动限制。
 */
object TextInputServer {

    private const val TAG = "TextInputServer"
    private const val PORT = 7656

    private var serverThread: Thread? = null
    @Volatile
    private var running = false

    fun start(context: Context) {
        if (running) return
        running = true
        serverThread = Thread({
            try {
                val serverSocket = ServerSocket(PORT)
                serverSocket.reuseAddress = true
                Log.i(TAG, "TCP server listening on port $PORT")

                while (running && !Thread.currentThread().isInterrupted) {
                    try {
                        val client = serverSocket.accept()
                        Log.i(TAG, "Client connected: ${client.inetAddress}")
                        handleClient(context, client)
                    } catch (e: Exception) {
                        if (running) Log.e(TAG, "Accept failed: ${e.message}")
                    }
                }

                runCatching { serverSocket.close() }
            } catch (e: Exception) {
                Log.e(TAG, "Server start failed: ${e.message}")
            }
        }, "text-input-server").apply { start() }
    }

    fun stop() {
        running = false
        serverThread?.interrupt()
        serverThread = null
    }

    private fun handleClient(context: Context, client: java.net.Socket) {
        try {
            client.soTimeout = 5000
            val reader = BufferedReader(InputStreamReader(client.getInputStream(), "UTF-8"))
            val text = reader.readLine() ?: ""
            Log.i(TAG, "Received text: $text (${text.length} chars)")

            // 1. 设剪贴板
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("rk_text", text))
            Log.i(TAG, "Clipboard set successfully")

            // 2. 尝试多种方式触发粘贴
            // 方式A: input keyevent（可能因权限失败）
            try {
                val process = Runtime.getRuntime().exec(arrayOf("input", "keyevent", "KEYCODE_PASTE"))
                val exitCode = process.waitFor()
                val errText = process.errorStream.bufferedReader().readText()
                if (exitCode == 0 && errText.isBlank()) {
                    Log.i(TAG, "Paste method A (input keyevent) succeeded")
                } else {
                    Log.w(TAG, "Paste method A failed: exit=$exitCode, err=${errText.trim()}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Paste method A exception: ${e.message}")
            }

            // 3. 回复 OK
            client.getOutputStream().write("OK\n".toByteArray(Charsets.UTF_8))
            client.getOutputStream().flush()
            Log.i(TAG, "Response sent: OK")

        } catch (e: Exception) {
            Log.e(TAG, "Handle client error: ${e.message}")
        } finally {
            runCatching { client.close() }
        }
    }
}
