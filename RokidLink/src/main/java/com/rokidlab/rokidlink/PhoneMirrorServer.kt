package com.rokidlab.rokidlink

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import java.io.BufferedInputStream
import java.net.ServerSocket
import java.net.Socket

/**
 * 眼镜端 Socket 服务端
 * 接收手机端发送的灰度图像数据并显示
 */
class PhoneMirrorServer(
    private val port: Int = 7654
) {
    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var inputStream: BufferedInputStream? = null
    private var isRunning = false
    private var receiveThread: Thread? = null

    /** 当前帧宽高（每帧从 header 读取，动态变化） */
    private var frameWidth = 480
    private var frameHeight = 640

    companion object {
        private const val TAG = "RokidLink-Server"
        private const val HEADER_SIZE = 5 // 1方向 + 2宽 + 2高
    }

    interface OnFrameListener {
        fun onFrame(bitmap: Bitmap, isLandscape: Boolean)
        fun onStatus(status: String)
        fun onConnected()
        fun onDisconnected()
    }

    private var frameListener: OnFrameListener? = null

    fun setFrameListener(listener: OnFrameListener) {
        this.frameListener = listener
    }

    fun start(): Boolean {
        return try {
            serverSocket = ServerSocket(port)
            serverSocket?.soTimeout = 0  // 无限等待
            isRunning = true
            Log.i(TAG, "Socket 服务端已启动，端口: $port")

            receiveThread = Thread {
                waitForClient()
            }
            receiveThread?.start()
            true
        } catch (e: Exception) {
            Log.e(TAG, "启动失败: ${e.message}", e)
            false
        }
    }

    private fun waitForClient() {
        while (isRunning) {
            try {
                frameListener?.onStatus("等待手机连接...")
                Log.i(TAG, "等待客户端连接...")

                clientSocket = serverSocket?.accept()
                clientSocket?.tcpNoDelay = true
                inputStream = BufferedInputStream(clientSocket?.getInputStream())

                Log.i(TAG, "手机已连接")
                frameListener?.onStatus("已连接")
                frameListener?.onConnected()

                receiveFrames()
            } catch (e: Exception) {
                if (isRunning) {
                    Log.e(TAG, "接收连接失败: ${e.message}", e)
                }
            }
        }
    }

    private fun receiveFrames() {
        while (isRunning) {
            try {
                // 读取 5 字节 header: [方向(1)][宽(2)][高(2)]
                val header = ByteArray(HEADER_SIZE)
                var headerRead = 0
                while (headerRead < HEADER_SIZE) {
                    val r = inputStream?.read(header, headerRead, HEADER_SIZE - headerRead) ?: -1
                    if (r == -1) throw Exception("连接已断开")
                    headerRead += r
                }

                val orientationValue = header[0].toInt() and 0xFF
                val isLandscape = orientationValue == 1
                frameWidth = (header[1].toInt() and 0xFF) or ((header[2].toInt() and 0xFF) shl 8)
                frameHeight = (header[3].toInt() and 0xFF) or ((header[4].toInt() and 0xFF) shl 8)

                val frameSize = frameWidth * frameHeight
                Log.i(TAG, "接收到帧: ${frameWidth}x${frameHeight}, 方向=${if (isLandscape) "横屏" else "竖屏"}")

                // 读取灰度数据
                val buffer = ByteArray(frameSize)
                var read = 0
                while (read < frameSize) {
                    val r = inputStream?.read(buffer, read, frameSize - read) ?: -1
                    if (r == -1) throw Exception("连接已断开")
                    read += r
                }

                // 创建 Bitmap 并显示
                val bitmap = createGrayscaleBitmap(buffer, frameWidth, frameHeight)
                bitmap?.let {
                    frameListener?.onFrame(it, isLandscape)
                }

            } catch (e: Exception) {
                if (isRunning) {
                    Log.e(TAG, "接收帧失败: ${e.message}", e)
                    break
                }
            }
        }

        disconnect()
    }

    private fun createGrayscaleBitmap(data: ByteArray, w: Int, h: Int): Bitmap? {
        return try {
            val pixels = IntArray(w * h)
            for (i in 0 until minOf(data.size, w * h)) {
                val gray = data[i].toInt() and 0xFF
                pixels[i] = Color.rgb(0, gray, 0)
            }
            Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
        } catch (e: Exception) {
            Log.e(TAG, "创建Bitmap失败: ${e.message}", e)
            null
        }
    }

    fun stop() {
        isRunning = false
        disconnect()
        receiveThread?.interrupt()
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "关闭ServerSocket失败: ${e.message}", e)
        }
        serverSocket = null
        Log.i(TAG, "服务已停止")
    }

    private fun disconnect() {
        try {
            inputStream?.close()
            clientSocket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "断开连接失败: ${e.message}", e)
        }
        inputStream = null
        clientSocket = null
        Log.i(TAG, "客户端已断开，等待重连...")
    }
}
