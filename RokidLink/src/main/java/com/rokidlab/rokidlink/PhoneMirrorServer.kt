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

    /** Current frame width/height (read from header each frame, dynamically changes) */
    private var frameWidth = 480
    private var frameHeight = 640
    /** Reuse Bitmap to avoid GC pressure from creating new one each frame */
    private var reusableBitmap: Bitmap? = null
    /** Reuse pixel array to avoid allocating IntArray each frame, preventing native OOM */
    private var reusablePixels: IntArray? = null

    companion object {
        private const val TAG = "RokidLink-Server"
        private const val HEADER_SIZE = 5 // 1 orientation + 2 width + 2 height
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
            serverSocket?.soTimeout = 0  // Infinite wait
            isRunning = true
            Log.i(TAG, "Socket server started, port: $port")

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
        while (isRunning) {
            try {
                frameListener?.onStatus("Waiting for phone connection...")
                Log.i(TAG, "Waiting for client connection...")

                clientSocket = serverSocket?.accept()
                if (!isRunning) break
                clientSocket?.tcpNoDelay = true
                clientSocket?.soTimeout = 15000  // 15s timeout - 手机切换方向时帧可能会暂停几秒
                inputStream = BufferedInputStream(clientSocket?.getInputStream())

                Log.i(TAG, "Phone connected")
                frameListener?.onStatus("Connected")
                frameListener?.onConnected()

                receiveFrames()
            } catch (e: Exception) {
                if (isRunning) {
                    Log.e(TAG, "Accept connection failed: ${e.message}", e)
                    disconnect()  // Clean up possibly partially initialized clientSocket/inputStream
                    try { Thread.sleep(1000) } catch (_: InterruptedException) { break }
                }
            }
        }
    }

    private fun receiveFrames() {
        while (isRunning) {
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
                    // Try to skip this frame data to align protocol stream (corrupted header makes w/h untrustworthy, skip reasonable limit)
                    try {
                        val skipSize = if (frameWidth > 0 && frameHeight > 0) {
                            // Even if w/h abnormal but both positive, cap to max value to prevent overflow OOM
                            minOf(frameWidth, MAX_FRAME_DIMENSION) * minOf(frameHeight, MAX_FRAME_DIMENSION)
                        } else {
                            // w/h has non-positive value, cannot estimate, skip typical frame size 480*640
                            480 * 640
                        }
                        var skipped = 0
                        val skipBuf = ByteArray(8192)
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
                Log.i(TAG, "Received frame: ${frameWidth}x${frameHeight}, orientation=${if (isLandscape) "landscape" else "portrait"}")

                // Read grayscale data
                val buffer = ByteArray(frameSize)
                var read = 0
                while (read < frameSize) {
                    val r = inputStream?.read(buffer, read, frameSize - read) ?: -1
                    if (r == -1) throw Exception("Connection closed")
                    read += r
                }

                // Create Bitmap and display
                val bitmap = createGrayscaleBitmap(buffer, frameWidth, frameHeight)
                bitmap?.let {
                    frameListener?.onFrame(it, isLandscape)
                }

            } catch (e: Exception) {
                if (isRunning) {
                    Log.e(TAG, "Receive frame failed: ${e.message}", e)
                    break
                }
            }
        }

        disconnect()
        frameListener?.onDisconnected()
        // Clean up state for next waitForClient loop
        inputStream = null
        clientSocket = null
    }

    private fun createGrayscaleBitmap(data: ByteArray, w: Int, h: Int): Bitmap? {
        return try {
            val pixelCount = w * h
            // Reuse pixel array to avoid allocating IntArray each frame (prevents native OOM)
            val pixels = reusablePixels?.takeIf { it.size >= pixelCount } ?: IntArray(pixelCount).also { reusablePixels = it }
            for (i in 0 until minOf(data.size, pixelCount)) {
                val gray = data[i].toInt() and 0xFF
                pixels[i] = Color.rgb(gray, gray, gray)
            }
            // Reuse Bitmap: only recreate when size changes
            if (reusableBitmap?.width != w || reusableBitmap?.height != h) {
                val newBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                reusableBitmap?.recycle()
                reusableBitmap = newBitmap
            }
            reusableBitmap?.setPixels(pixels, 0, w, 0, 0, w, h)
            reusableBitmap
        } catch (e: Exception) {
            Log.e(TAG, "Create Bitmap failed: ${e.message}", e)
            null
        }
    }

    fun stop() {
        isRunning = false
        reusableBitmap?.recycle()
        reusableBitmap = null
        // Must close ServerSocket first, only then can accept() blocking be released (interrupt has no effect on accept())
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Close ServerSocket failed: ${e.message}", e)
        }
        serverSocket = null
        disconnect()
        receiveThread?.interrupt()
        receiveThread = null
        Log.i(TAG, "Server stopped")
    }

    private fun disconnect() {
        try {
            inputStream?.close()
            clientSocket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Disconnect failed: ${e.message}", e)
        }
        inputStream = null
        clientSocket = null
        Log.i(TAG, "Client disconnected")
    }
}
