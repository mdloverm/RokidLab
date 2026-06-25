package com.rokidlab.rokidlink

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import java.io.BufferedInputStream
import java.net.ServerSocket
import java.net.Socket

/**
 * Glasses-side Socket server
 * Receives grayscale image data from phone and displays
 */
class PhoneMirrorServer(
    private val port: Int = 7654
) {
    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var inputStream: BufferedInputStream? = null
    @Volatile
    var isRunning = false
        private set
    private var receiveThread: Thread? = null
    /** 同步锁，保护 stop/disconnect/accept 竞态 */
    private val lock = Any()

    /** Current frame width/height (read from header each frame, dynamically changes) */
    private var frameWidth = 480
    private var frameHeight = 640

    // ── Double buffering for Bitmap thread safety ──
    // Two buffers: background thread writes to one, UI thread reads the other.
    // Prevents screen tearing when both threads access the same Bitmap concurrently.
    private val reusableBitmaps = arrayOfNulls<Bitmap>(2)
    private var currentWriteBufferIndex = 0

    /** Reuse pixel array to avoid allocating IntArray each frame, preventing native OOM */
    private var reusablePixels: IntArray? = null

    /** 坏帧跳转时复用缓冲区，避免每帧分配 */
    private val skipBuf = ByteArray(8192)

    companion object {
        private const val TAG = "RokidLink-Server"
        private const val HEADER_SIZE = 5 // 1 orientation + 2 width + 2 height
        private const val MAX_FRAME_DIMENSION = 2048
        /** Socket read timeout (ms): 15s balances faster disconnect detection
         *  vs tolerating brief pauses during orientation changes. When frames resume
         *  after timeout, the next read succeeds and streaming continues normally. */
        private const val SOCKET_TIMEOUT_MS = 15_000
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
        stop()  // 确保旧线程和 Socket 已清理
        return try {
            val server = ServerSocket()
            server.reuseAddress = true
            server.bind(java.net.InetSocketAddress(port))
            serverSocket = server
            serverSocket?.soTimeout = 0  // Infinite wait for accept()
            isRunning = true
            Log.i(TAG, "Socket server started, port: $port, soTimeout=${SOCKET_TIMEOUT_MS}ms")

            receiveThread = Thread {
                waitForClient()
            }
            receiveThread?.start()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Start failed: ${e.message}", e)
            false
        }
    }

    private fun waitForClient() {
        while (true) {
            synchronized(lock) {
                if (!isRunning) return
            }
            try {
                frameListener?.onStatus("Waiting for phone connection...")
                Log.i(TAG, "Waiting for client connection...")

                clientSocket = serverSocket?.accept()
                synchronized(lock) {
                    if (!isRunning) {
                        closeClientSocket()
                        return
                    }
                }
                clientSocket?.tcpNoDelay = true
                clientSocket?.keepAlive = true
                clientSocket?.soTimeout = SOCKET_TIMEOUT_MS
                inputStream = BufferedInputStream(clientSocket?.getInputStream())

                // 重置双缓冲索引，新连接重新从 buffer[0] 开始
                currentWriteBufferIndex = 0

                Log.i(TAG, "Phone connected")
                frameListener?.onStatus("Connected")
                frameListener?.onConnected()

                receiveFrames()
            } catch (e: Exception) {
                synchronized(lock) {
                    if (isRunning) {
                        Log.e(TAG, "Accept connection failed: ${e.message}", e)
                        closeClientSocket()
                    } else {
                        return
                    }
                }
            }
        }
    }

    private fun receiveFrames() {
        while (true) {
            synchronized(lock) {
                if (!isRunning) return
            }
            try {
                // Read 5 byte header: [orientation(1)][width(2)][height(2)]
                val header = ByteArray(HEADER_SIZE)
                var headerRead = 0
                while (headerRead < HEADER_SIZE) {
                    val r = inputStream?.read(header, headerRead, HEADER_SIZE - headerRead) ?: -1
                    if (r == -1) throw Exception("Connection closed")
                    headerRead += r
                }

                val orientationValue = header[0].toInt() and 0xFF
                val isLandscape = orientationValue == 1
                frameWidth = (header[1].toInt() and 0xFF) or ((header[2].toInt() and 0xFF) shl 8)
                frameHeight = (header[3].toInt() and 0xFF) or ((header[4].toInt() and 0xFF) shl 8)

                // Validate width/height to prevent OOM/NegativeArraySizeException from corrupted data
                if (frameWidth <= 0 || frameHeight <= 0 ||
                    frameWidth > MAX_FRAME_DIMENSION || frameHeight > MAX_FRAME_DIMENSION) {
                    Log.w(TAG, "Discarding abnormal frame size: ${frameWidth}x${frameHeight}")
                    // Try to skip this frame data to align protocol stream
                    try {
                        val skipSize = if (frameWidth > 0 && frameHeight > 0) {
                            minOf(frameWidth, MAX_FRAME_DIMENSION) * minOf(frameHeight, MAX_FRAME_DIMENSION)
                        } else {
                            480 * 640
                        }
                        var skipped = 0
                        while (skipped < skipSize) {
                            val r = inputStream?.read(skipBuf, 0, minOf(skipBuf.size, skipSize - skipped)) ?: -1
                            if (r == -1) throw Exception("Connection closed")
                            skipped += r
                        }
                    } catch (_: Exception) { }
                    continue
                }

                val frameSize = frameWidth * frameHeight
                // Further validate frameSize to prevent Int overflow
                if (frameSize <= 0 || frameSize > MAX_FRAME_DIMENSION * MAX_FRAME_DIMENSION) {
                    Log.w(TAG, "Discarding frame: frameSize=$frameSize abnormal")
                    continue
                }

                // Read grayscale data
                val buffer = ByteArray(frameSize)
                var read = 0
                while (read < frameSize) {
                    val r = inputStream?.read(buffer, read, frameSize - read) ?: -1
                    if (r == -1) throw Exception("Connection closed")
                    read += r
                }

                // Create Bitmap using double buffering: write to current buffer, then send its reference to UI
                val bitmap = createGrayscaleBitmap(buffer, frameWidth, frameHeight)
                if (bitmap != null) {
                    frameListener?.onFrame(bitmap, isLandscape)
                }

            } catch (e: java.net.SocketTimeoutException) {
                synchronized(lock) {
                    if (!isRunning) return
                }
                // 方向切换或暂时卡顿时帧可能暂停，超时后继续等待，不断连
                Log.w(TAG, "Read timeout (${SOCKET_TIMEOUT_MS}ms), frames paused, continuing...")
            } catch (e: Exception) {
                synchronized(lock) {
                    if (isRunning) {
                        Log.e(TAG, "Receive frame failed: ${e.message}", e)
                    } else {
                        return
                    }
                }
                break
            }
        }

        synchronized(lock) {
            closeClientSocket()
        }
        frameListener?.onDisconnected()
        // Clean up state for next waitForClient loop
        inputStream = null
        clientSocket = null
    }

    /**
     * Write grayscale data into the current write buffer bitmap, then advance to the next buffer.
     * The returned Bitmap reference is safe to pass to UI thread because the background thread
     * will immediately switch to the other buffer for the next frame.
     */
    private fun createGrayscaleBitmap(data: ByteArray, w: Int, h: Int): Bitmap? {
        return try {
            val pixelCount = w * h
            // Reuse pixel array to avoid allocating IntArray each frame
            val pixels = reusablePixels?.takeIf { it.size >= pixelCount } ?: IntArray(pixelCount).also { reusablePixels = it }
            for (i in 0 until minOf(data.size, pixelCount)) {
                val gray = data[i].toInt() and 0xFF
                pixels[i] = Color.rgb(gray, gray, gray)
            }

            // Double buffering: write to currentWriteBufferIndex, then swap
            val writeBuffer = reusableBitmaps[currentWriteBufferIndex]
            val writeBitmap: Bitmap
            if (writeBuffer?.width != w || writeBuffer?.height != h) {
                // 分辨率变化：recycle 旧 Bitmap，创建新的
                writeBuffer?.recycle()
                writeBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                reusableBitmaps[currentWriteBufferIndex] = writeBitmap
            } else {
                writeBitmap = writeBuffer
            }
            writeBitmap.setPixels(pixels, 0, w, 0, 0, w, h)

            // Swap to other buffer for next frame (UI thread now owns this frame's buffer reference)
            currentWriteBufferIndex = (currentWriteBufferIndex + 1) % reusableBitmaps.size

            writeBitmap
        } catch (e: Exception) {
            Log.e(TAG, "Create Bitmap failed: ${e.message}", e)
            null
        }
    }

    fun stop() {
        synchronized(lock) {
            if (!isRunning) return
            isRunning = false
        }
        // Recycle both buffers
        for (i in reusableBitmaps.indices) {
            reusableBitmaps[i]?.recycle()
            reusableBitmaps[i] = null
        }
        // Must close ServerSocket first, only then can accept() blocking be released
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Close ServerSocket failed: ${e.message}", e)
        }
        serverSocket = null
        closeClientSocket()
        receiveThread?.interrupt()
        receiveThread = null
        Log.i(TAG, "Server stopped")
    }

    /**
     * 关闭当前客户端连接。调用后 waitForClient 循环会重新 accept() 等待新连接。
     * 由 synchronized(lock) 保护，避免与 stop() 竞态。
     */
    private fun closeClientSocket() {
        try {
            inputStream?.close()
            clientSocket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Close client socket failed: ${e.message}", e)
        }
        inputStream = null
        clientSocket = null
        Log.i(TAG, "Client socket closed")
    }
}
