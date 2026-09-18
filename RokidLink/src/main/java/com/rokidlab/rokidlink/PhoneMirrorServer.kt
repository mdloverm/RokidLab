package com.rokidlab.rokidlink

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
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

    /** 旋转后的输出缓冲（横屏帧），与 [reusableBitmaps] 同索引配对，避免每帧分配 1.2MB */
    private val rotatedBitmaps = arrayOfNulls<Bitmap>(2)
    private val rotationMatrix = Matrix()

    /** Reuse pixel array to avoid allocating IntArray each frame, preventing native OOM */
    private var reusablePixels: IntArray? = null

    /** 坏帧跳转时复用缓冲区，避免每帧分配 */
    private val skipBuf = ByteArray(8192)

    companion object {
        private const val TAG = "RokidLink-Server"
        private const val HEADER_SIZE = 5 // 1 rotation angle(0/90/180/270) + 2 width + 2 height
        private const val MAX_FRAME_DIMENSION = 2048
        /** Socket read timeout (ms): 15s balances faster disconnect detection
         *  vs tolerating brief pauses during orientation changes. When frames resume
         *  after timeout, the next read succeeds and streaming continues normally. */
        private const val SOCKET_TIMEOUT_MS = 15_000
    }

    interface OnFrameListener {
        /** 收到一帧已按手机方向转正的 Bitmap（竖屏 w<h / 横屏 w>h），直接显示即可 */
        fun onFrame(bitmap: Bitmap)
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

            receiveThread = namedThread("mirror-receive") {
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

                // 帧头第 1 字节：手机屏幕旋转角度（0/90/180/270，竖屏=0）。
                // 兼容旧协议：旧版只发 0/1（1=横屏），按 90° 处理。
                val rawOrientation = header[0].toInt() and 0xFF
                val frameAngle = when (rawOrientation) {
                    90, 180, 270 -> rawOrientation
                    1 -> 90
                    else -> 0
                }
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
                val bitmap = createGrayscaleBitmap(buffer, frameWidth, frameHeight, frameAngle)
                if (bitmap != null) {
                    frameListener?.onFrame(bitmap)
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
     * Write grayscale data into the current write buffer bitmap, rotate by [angle] when the phone
     * is landscape, then advance to the next buffer.
     *
     * The returned Bitmap reference is safe to pass to UI thread because the background thread
     * will immediately switch to the other buffer pair for the next frame.
     *
     * @param angle 手机屏幕旋转角度（0/90/180/270）。手机端 VirtualDisplay 固定竖屏，
     *              横屏时帧内容是侧躺的，必须在此旋转回正立方向，否则眼镜上画面压扁/侧躺。
     */
    private fun createGrayscaleBitmap(data: ByteArray, w: Int, h: Int, angle: Int): Bitmap? {
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
                // 分辨率变化：recycle 旧 Bitmap，创建新的；配对的旋转缓冲尺寸也必然失效
                writeBuffer?.recycle()
                rotatedBitmaps[currentWriteBufferIndex]?.recycle()
                rotatedBitmaps[currentWriteBufferIndex] = null
                writeBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                reusableBitmaps[currentWriteBufferIndex] = writeBitmap
            } else {
                writeBitmap = writeBuffer
            }
            writeBitmap.setPixels(pixels, 0, w, 0, 0, w, h)

            val out = if (angle == 0) {
                writeBitmap
            } else {
                // 旋转后宽高交换（90/270）或不变（180）；输出缓冲与源缓冲同索引配对，
                // 与源缓冲一起参与双缓冲轮换，UI 线程持有的上一帧不会被下一帧覆盖。
                val outW = if (angle == 180) w else h
                val outH = if (angle == 180) h else w
                var rotated = rotatedBitmaps[currentWriteBufferIndex]
                if (rotated == null || rotated.width != outW || rotated.height != outH || rotated.isRecycled) {
                    rotated?.recycle()
                    rotated = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
                    rotatedBitmaps[currentWriteBufferIndex] = rotated
                }
                rotationMatrix.setRotate(angle.toFloat())
                val canvas = Canvas(rotated)
                canvas.drawColor(Color.BLACK)
                canvas.drawBitmap(writeBitmap, rotationMatrix, null)
                rotated
            }

            // Swap to other buffer for next frame (UI thread now owns this frame's buffer reference)
            currentWriteBufferIndex = (currentWriteBufferIndex + 1) % reusableBitmaps.size

            out
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
        // Recycle both buffer pairs (source + rotated)
        for (i in reusableBitmaps.indices) {
            reusableBitmaps[i]?.recycle()
            reusableBitmaps[i] = null
            rotatedBitmaps[i]?.recycle()
            rotatedBitmaps[i] = null
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
