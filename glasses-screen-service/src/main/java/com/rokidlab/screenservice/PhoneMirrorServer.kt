package com.rokidlab.screenservice

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
    private val port: Int = 7654,
    private val width: Int = 480,
    private val height: Int = 640
) {
    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var inputStream: BufferedInputStream? = null
    private var isRunning = false
    private var receiveThread: Thread? = null

    companion object {
        private const val TAG = "PhoneMirrorServer"
        private const val HEADER_SIZE = 12
        private const val FRAME_BUFFER_SIZE = 480 * 640
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
        val buffer = ByteArray(FRAME_BUFFER_SIZE)

        while (isRunning) {
            try {
                // 先读取方向信息（1字节）
                val orientationBuf = ByteArray(1)
                var read = inputStream?.read(orientationBuf) ?: -1
                if (read == -1) throw Exception("连接已断开")
                
                val orientationValue = orientationBuf[0].toInt() and 0xFF
                val isLandscape = orientationValue == 1
                Log.i(TAG, "接收到方向信息: value=$orientationValue, isLandscape=$isLandscape")

                // 读取固定长度的灰度数据 (480*640 = 307200 字节)
                read = 0
                while (read < FRAME_BUFFER_SIZE) {
                    val r = inputStream?.read(buffer, read, FRAME_BUFFER_SIZE - read) ?: -1
                    if (r == -1) throw Exception("连接已断开")
                    read += r
                }

                // 创建 Bitmap 并显示
                val bitmap = createGrayscaleBitmap(buffer, FRAME_BUFFER_SIZE)
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

    private fun createGrayscaleBitmap(data: ByteArray, size: Int): Bitmap? {
        return try {
            val pixels = IntArray(width * height)
            for (i in 0 until minOf(size, width * height)) {
                val gray = data[i].toInt() and 0xFF
                // 单绿显示：绿色通道为灰度值，红色和蓝色为0
                pixels[i] = Color.rgb(0, gray, 0)
            }
            Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
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
        frameListener?.onDisconnected()
    }
}