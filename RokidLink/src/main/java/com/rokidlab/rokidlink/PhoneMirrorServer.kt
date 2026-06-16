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
    @Volatile
    private var isRunning = false
    private var receiveThread: Thread? = null

    /** 当前帧宽高（每帧从 header 读取，动态变化） */
    private var frameWidth = 480
    private var frameHeight = 640
    /** 复用 Bitmap，避免每帧新建导致 GC 压力 */
    private var reusableBitmap: Bitmap? = null

    companion object {
        private const val TAG = "RokidLink-Server"
        private const val HEADER_SIZE = 5 // 1方向 + 2宽 + 2高
        private const val MAX_FRAME_DIMENSION = 2048
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
        if (isRunning) return false
        return try {
            val server = ServerSocket()
            server.reuseAddress = true
            server.bind(java.net.InetSocketAddress(port))
            serverSocket = server
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
                if (!isRunning) break
                clientSocket?.tcpNoDelay = true
                clientSocket?.soTimeout = 5000  // 5秒无数据判定断开
                inputStream = BufferedInputStream(clientSocket?.getInputStream())

                Log.i(TAG, "手机已连接")
                frameListener?.onStatus("已连接")
                frameListener?.onConnected()

                receiveFrames()
            } catch (e: Exception) {
                if (isRunning) {
                    Log.e(TAG, "接收连接失败: ${e.message}", e)
                    disconnect()  // 清理可能部分初始化的 clientSocket/inputStream
                    try { Thread.sleep(1000) } catch (_: InterruptedException) { break }
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

                // 校验宽高，防止损坏数据导致 OOM/NegativeArraySizeException
                if (frameWidth <= 0 || frameHeight <= 0 ||
                    frameWidth > MAX_FRAME_DIMENSION || frameHeight > MAX_FRAME_DIMENSION) {
                    Log.w(TAG, "丢弃异常帧尺寸: ${frameWidth}x${frameHeight}")
                    // 尝试跳过此帧数据以对齐协议流（损坏的 header 导致 w/h 不可信，跳过合理上限）
                    try {
                        val skipSize = if (frameWidth > 0 && frameHeight > 0) {
                            // 即使 w/h 异常但都为正，cap 到最大值防止溢出 OOM
                            minOf(frameWidth, MAX_FRAME_DIMENSION) * minOf(frameHeight, MAX_FRAME_DIMENSION)
                        } else {
                            // w/h 有非正值，无法估算，跳过典型帧大小 480*640
                            480 * 640
                        }
                        var skipped = 0
                        val skipBuf = ByteArray(8192)
                        while (skipped < skipSize) {
                            val r = inputStream?.read(skipBuf, 0, minOf(skipBuf.size, skipSize - skipped)) ?: -1
                            if (r == -1) throw Exception("连接已断开")
                            skipped += r
                        }
                    } catch (_: Exception) { }
                    continue
                }

                val frameSize = frameWidth * frameHeight
                // 进一步校验 frameSize 防止 Int 溢出
                if (frameSize <= 0 || frameSize > MAX_FRAME_DIMENSION * MAX_FRAME_DIMENSION) {
                    Log.w(TAG, "丢弃帧: frameSize=$frameSize 异常")
                    continue
                }
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
        frameListener?.onDisconnected()
        // 为下一次 waitForClient 循环清理状态
        inputStream = null
        clientSocket = null
    }

    private fun createGrayscaleBitmap(data: ByteArray, w: Int, h: Int): Bitmap? {
        return try {
            val pixels = IntArray(w * h)
            for (i in 0 until minOf(data.size, w * h)) {
                val gray = data[i].toInt() and 0xFF
                pixels[i] = Color.rgb(gray, gray, gray)
            }
            // 复用 Bitmap：仅在尺寸变化时重建，重建失败时不释放旧 Bitmap
            if (reusableBitmap?.width != w || reusableBitmap?.height != h) {
                val newBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                reusableBitmap?.recycle()  // 新 Bitmap 创建成功后再释放旧的
                reusableBitmap = newBitmap
            }
            reusableBitmap?.setPixels(pixels, 0, w, 0, 0, w, h)
            reusableBitmap
        } catch (e: Exception) {
            Log.e(TAG, "创建Bitmap失败: ${e.message}", e)
            // 不置 null，保留旧的 reusableBitmap（如果存在）供后续帧使用
            null
        }
    }

    fun stop() {
        isRunning = false
        reusableBitmap?.recycle()
        reusableBitmap = null
        // 必须先关闭 ServerSocket，accept() 阻塞才能被解除（interrupt 对 accept() 无效）
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "关闭ServerSocket失败: ${e.message}", e)
        }
        serverSocket = null
        disconnect()
        receiveThread?.interrupt()
        receiveThread = null
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
        Log.i(TAG, "客户端已断开")
    }
}
