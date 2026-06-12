package com.rokidlab.phone.mirror

import com.rokidlab.phone.app.*
import com.rokidlab.phone.adb.*
import com.rokidlab.phone.design.*
import com.rokidlab.phone.filemanager.*
import com.rokidlab.phone.glasses.*
import com.rokidlab.phone.mirror.*
import com.rokidlab.phone.model.*
import com.rokidlab.phone.network.*
import com.rokidlab.phone.settings.*
import com.rokidlab.phone.store.*
import com.rokidlab.phone.util.*
import android.graphics.ImageFormat
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.util.Log
import android.view.Surface
import java.io.OutputStream
import java.net.Socket

/**
 * 手机端 Socket 客户端
 * 捕获屏幕画面，提取Y平面(灰度)并发送到眼镜端
 */
class PhoneMirrorClient(
    private val glassesIp: String,
    private val port: Int = 7654,
    private val width: Int = WIDTH,
    private val height: Int = HEIGHT,
    private val mediaProjection: MediaProjection
) {
    private var socket: Socket? = null
    private var outputStream: OutputStream? = null
    private var isRunning = false
    private var imageReader: ImageReader? = null
    private var surface: Surface? = null
    private var virtualDisplay: android.hardware.display.VirtualDisplay? = null

    companion object {
        private const val TAG = "PhoneMirrorClient"
        private const val WIDTH = 480
        private const val HEIGHT = 640
    }

    interface OnStatusListener {
        fun onStatus(status: String)
        fun onConnected()
        fun onDisconnected()
        fun onError(error: String)
    }

    private var statusListener: OnStatusListener? = null

    fun setStatusListener(listener: OnStatusListener) {
        this.statusListener = listener
    }

    fun connect(): Boolean {
        return try {
            statusListener?.onStatus("正在连接眼镜...")
            Log.i(TAG, "正在连接眼镜 $glassesIp:$port")

            socket = Socket(glassesIp, port)
            socket?.tcpNoDelay = true
            socket?.sendBufferSize = 1024 * 1024 * 2 // 2MB buffer
            outputStream = socket?.getOutputStream()

            Log.i(TAG, "连接成功")
            statusListener?.onStatus("已连接")
            statusListener?.onConnected()
            true
        } catch (e: Exception) {
            Log.e(TAG, "连接失败: ${e.message}", e)
            statusListener?.onStatus("连接失败: ${e.message}")
            statusListener?.onError(e.message ?: "连接失败")
            disconnect()
            false
        }
    }

    fun start() {
        if (isRunning) return
        
        Thread {
            try {
                // 先连接眼镜
                Log.i(TAG, "正在连接眼镜 $glassesIp:$port")
                socket = Socket(glassesIp, port)
                socket?.tcpNoDelay = true
                socket?.sendBufferSize = 1024 * 1024 * 2
                outputStream = socket?.getOutputStream()
                Log.i(TAG, "连接成功")
                statusListener?.onConnected()
                
                // 然后开始屏幕捕获
                isRunning = true
                
                // 创建 ImageReader 获取 YUV 数据
                imageReader = ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, 2)
                surface = imageReader?.surface

                // 创建虚拟显示器
                virtualDisplay = mediaProjection.createVirtualDisplay(
                    "PhoneMirror",
                    width,
                    height,
                    1,  // density
                    android.view.Display.FLAG_SECURE,
                    surface,
                    null,
                    null
                )

                // 设置 ImageReader 的监听器
                imageReader?.setOnImageAvailableListener({ reader ->
                    if (!isRunning) return@setOnImageAvailableListener
                    
                    val image = reader.acquireLatestImage()
                    if (image != null) {
                        try {
                            processImage(image)
                        } catch (e: Exception) {
                            Log.e(TAG, "处理图像异常: ${e.message}", e)
                        } finally {
                            image.close()
                        }
                    }
                }, null)

                Log.i(TAG, "开始屏幕捕获")
                statusListener?.onStatus("正在捕获屏幕...")
            } catch (e: Exception) {
                Log.e(TAG, "启动失败: ${e.message}", e)
                statusListener?.onError(e.message ?: "启动失败")
                disconnect()
            }
        }.start()
    }

    private fun processImage(image: Image) {
        try {
            // 提取 Y 平面 (灰度数据)
            val yPlane = image.planes[0]
            val yBuffer = yPlane.buffer
            val yStride = yPlane.rowStride
            val yPixelStride = yPlane.pixelStride

            // 创建目标数据数组
            val yData = ByteArray(width * height)
            var bufferIndex = 0
            var dataIndex = 0

            // 逐行复制数据（处理 stride）
            for (row in 0 until height) {
                for (col in 0 until width) {
                    yData[dataIndex] = yBuffer.get(bufferIndex + col * yPixelStride)
                    dataIndex++
                }
                bufferIndex += yStride
            }

            sendFrame(yData)
        } catch (e: Exception) {
            Log.e(TAG, "处理图像失败: ${e.message}", e)
        }
    }

    private fun sendFrame(data: ByteArray) {
        try {
            // 发送帧长度 (4字节大端序)
            val b0 = ((data.size shr 24) and 0xFF).toByte()
            val b1 = ((data.size shr 16) and 0xFF).toByte()
            val b2 = ((data.size shr 8) and 0xFF).toByte()
            val b3 = (data.size and 0xFF).toByte()
            outputStream?.write(byteArrayOf(b0, b1, b2, b3))
            outputStream?.write(data)
            outputStream?.flush()
        } catch (e: Exception) {
            if (isRunning) {
                Log.e(TAG, "发送帧失败: ${e.message}", e)
                disconnect()
            }
        }
    }

    fun stop() {
        Log.i(TAG, "停止投屏")
        isRunning = false
        
        try {
            imageReader?.close()
            surface?.release()
            virtualDisplay?.release()
            mediaProjection?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "释放资源失败: ${e.message}", e)
        }
        
        disconnect()
    }

    private fun disconnect() {
        try {
            outputStream?.close()
            socket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "断开连接失败: ${e.message}", e)
        }
        outputStream = null
        socket = null
        statusListener?.onDisconnected()
    }
}