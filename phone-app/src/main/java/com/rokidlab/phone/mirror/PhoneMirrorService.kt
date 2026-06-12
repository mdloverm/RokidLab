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
import com.rokidlab.phone.R
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import androidx.core.app.NotificationCompat
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.PixelFormat
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import android.view.Surface
import java.io.OutputStream
import java.net.Socket

class PhoneMirrorService : Service() {
    companion object {
        private const val TAG = "PhoneMirrorService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "PhoneMirror"
        private const val WIDTH = 480
        private const val HEIGHT = 640
        private const val DEFAULT_PORT = 7654

        fun startService(context: Context, glassesIp: String, port: Int, resultCode: Int, data: Intent) {
            val intent = Intent(context, PhoneMirrorService::class.java).apply {
                putExtra("glassesIp", glassesIp)
                putExtra("port", port)
                putExtra("resultCode", resultCode)
                putExtra("data", data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private var socket: Socket? = null
    private var outputStream: OutputStream? = null
    private var imageReader: ImageReader? = null
    private var surface: Surface? = null
    private var virtualDisplay: android.hardware.display.VirtualDisplay? = null
    private var mediaProjection: MediaProjection? = null
    private var glassesIp: String = ""
    private var port: Int = DEFAULT_PORT

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand 被调用")
        
        // 必须先调用 startForeground，否则会报 ForegroundServiceDidNotStartInTimeException
        startForeground(NOTIFICATION_ID, createNotification())
        Log.i(TAG, "startForeground 已调用")
        
        if (intent != null) {
            glassesIp = intent.getStringExtra("glassesIp") ?: ""
            port = intent.getIntExtra("port", DEFAULT_PORT)
            val resultCode = intent.getIntExtra("resultCode", -1)
            val data = intent.getParcelableExtra<Intent>("data")
            
            Log.i(TAG, "接收到参数: glassesIp=$glassesIp, port=$port, resultCode=$resultCode, data=${data != null}")

            if (resultCode == android.app.Activity.RESULT_OK && data != null && glassesIp.isNotEmpty()) {
                Log.i(TAG, "参数完整，开始启动投屏")
                startMirror(resultCode, data)
            } else {
                Log.e(TAG, "参数不完整，无法启动投屏")
                stopSelf()
            }
        } else {
            Log.e(TAG, "Intent 为空")
            stopSelf()
        }
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "手机投屏",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "手机屏幕投射到眼镜"
            }
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val builder: NotificationCompat.Builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("手机投屏")
            .setContentText("正在投射屏幕到眼镜")
            .setSmallIcon(R.mipmap.ic_launcher)
        return builder.build()
    }

    private fun startMirror(resultCode: Int, data: Intent) {
        Thread {
            try {
                // 连接眼镜
                Log.i(TAG, "连接眼镜: $glassesIp:$port")
                socket = Socket(glassesIp, port)
                socket?.tcpNoDelay = true
                outputStream = socket?.getOutputStream()
                Log.i(TAG, "连接成功")

                // 获取 MediaProjection
                val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                mediaProjection = projectionManager.getMediaProjection(resultCode, data)

                // Android 12+ 要求必须设置回调
                mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                    override fun onStop() {
                        super.onStop()
                        Log.i(TAG, "MediaProjection 已停止")
                        stopSelf()
                    }
                }, null)

                // 创建 HandlerThread 用于处理图像回调
                val handlerThread = HandlerThread("ImageHandlerThread")
                handlerThread.start()
                val handler = Handler(handlerThread.looper)

                // 创建 ImageReader (使用 PixelFormat.RGBA_8888 格式，与虚拟显示器输出格式匹配)
                imageReader = ImageReader.newInstance(WIDTH, HEIGHT, PixelFormat.RGBA_8888, 2)
                surface = imageReader?.surface

                // 获取当前屏幕方向
                val isLandscape = getScreenOrientation() == 1.toByte()
                // 根据屏幕方向设置虚拟显示器尺寸（横屏时交换宽高）
                val displayWidth = if (isLandscape) HEIGHT else WIDTH
                val displayHeight = if (isLandscape) WIDTH else HEIGHT
                
                // 创建虚拟显示器
                virtualDisplay = mediaProjection?.createVirtualDisplay(
                    "PhoneMirror",
                    displayWidth,
                    displayHeight,
                    1,
                    android.view.Display.FLAG_SECURE,
                    surface,
                    null,
                    null
                )
                
                Log.i(TAG, "虚拟显示器尺寸: ${displayWidth}x${displayHeight}, isLandscape=$isLandscape")

                // 设置图像监听器（使用 HandlerThread 的 Handler）
                imageReader?.setOnImageAvailableListener({ reader ->
                    val image = reader.acquireLatestImage()
                    if (image != null) {
                        try {
                            processImage(image)
                        } catch (e: Exception) {
                            Log.e(TAG, "处理图像失败: ${e.message}", e)
                        } finally {
                            image.close()
                        }
                    }
                }, handler)

                Log.i(TAG, "投屏服务已启动")
            } catch (e: Exception) {
                Log.e(TAG, "启动投屏失败: ${e.message}", e)
                stopSelf()
            }
        }.start()
    }

    private fun processImage(image: Image) {
        try {
            // RGBA_8888 格式处理
            val rgbaPlane = image.planes[0]
            val rgbaBuffer = rgbaPlane.buffer.slice() // 创建切片，不影响原缓冲区
            val rgbaStride = rgbaPlane.rowStride
            val rgbaPixelStride = rgbaPlane.pixelStride // RGBA 每个像素 4 字节

            val grayData = ByteArray(WIDTH * HEIGHT)
            var bufferIndex = 0
            var dataIndex = 0

            for (row in 0 until HEIGHT) {
                for (col in 0 until WIDTH) {
                    // 读取 RGBA 值并计算灰度
                    val r = rgbaBuffer.get(bufferIndex).toInt() and 0xFF
                    val g = rgbaBuffer.get(bufferIndex + 1).toInt() and 0xFF
                    val b = rgbaBuffer.get(bufferIndex + 2).toInt() and 0xFF
                    // 灰度公式: Gray = 0.299*R + 0.587*G + 0.114*B
                    val gray = (0.299 * r + 0.587 * g + 0.114 * b).toInt().toByte()
                    grayData[dataIndex] = gray
                    dataIndex++
                    bufferIndex += rgbaPixelStride
                }
                // 跳过行尾可能的填充字节
                val rowBytes = WIDTH * rgbaPixelStride
                if (rgbaStride > rowBytes) {
                    bufferIndex += rgbaStride - rowBytes
                }
            }

            sendFrame(grayData)
        } catch (e: Exception) {
            Log.e(TAG, "处理图像失败: ${e.message}", e)
        }
    }

    private fun sendFrame(data: ByteArray) {
        try {
            // 获取屏幕方向（0: 竖屏, 1: 横屏）
            val orientation = getScreenOrientation()
            // 发送方向信息（1字节）+ 灰度数据
            outputStream?.write(byteArrayOf(orientation))
            outputStream?.write(data)
            outputStream?.flush()
            Log.i(TAG, "发送帧: orientation=$orientation, dataSize=${data.size}")
        } catch (e: Exception) {
            Log.e(TAG, "发送帧失败: ${e.message}", e)
            stopSelf()
        }
    }

    private fun getScreenOrientation(): Byte {
        // 在 Service 中通过 DisplayManager 获取屏幕方向
        val displayManager = getSystemService(Context.DISPLAY_SERVICE) as android.hardware.display.DisplayManager
        val displays = displayManager.displays
        if (displays.isNotEmpty()) {
            val rotation = displays[0].rotation
            // 0: 竖屏, 1: 横屏（90度）, 2: 竖屏反向, 3: 横屏反向
            val isLandscape = rotation == 1 || rotation == 3
            Log.i(TAG, "屏幕方向检测: rotation=$rotation, isLandscape=$isLandscape")
            return if (isLandscape) 1 else 0
        }
        return 0 // 默认竖屏
    }

    private fun stopMirror() {
        try {
            imageReader?.close()
            surface?.release()
            virtualDisplay?.release()
            mediaProjection?.stop()
            outputStream?.close()
            socket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "停止投屏失败: ${e.message}", e)
        }
    }

    override fun onDestroy() {
        stopMirror()
        super.onDestroy()
    }
}