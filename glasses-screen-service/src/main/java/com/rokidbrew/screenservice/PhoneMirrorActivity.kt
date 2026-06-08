package com.rokidbrew.screenservice

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * 眼镜端接收手机投屏 Activity
 */
class PhoneMirrorActivity : Activity(), PhoneMirrorServer.OnFrameListener {

    private lateinit var server: PhoneMirrorServer
    private lateinit var imageView: ImageView
    private lateinit var statusText: TextView
    private var isConnected = false
    private var lastTapTime = 0L

    companion object {
        private const val TAG = "PhoneMirrorActivity"
        const val PORT = 7654
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 全屏显示，隐藏状态栏和导航栏
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 创建全屏布局
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
        }

        // 状态显示（小字体，左上角）
        statusText = TextView(this).apply {
            text = "等待连接"
            setTextColor(Color.GRAY)
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 10
                leftMargin = 10
            }
        }

        // 图像显示区域（全屏居中，最大显示）
        imageView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            ).apply {
                weight = 1f
            }
            setBackgroundColor(Color.BLACK)
            // 双击退出
            setOnClickListener {
                val now = System.currentTimeMillis()
                if (now - lastTapTime < 300) {
                    // 双击
                    stopServer()
                    finish()
                }
                lastTapTime = now
            }
        }

        layout.addView(statusText)
        layout.addView(imageView)
        setContentView(layout)

        // 启动 Socket 服务
        startServer()
    }

    private fun startServer() {
        server = PhoneMirrorServer(PORT)
        server.setFrameListener(this)
        if (!server.start()) {
            Toast.makeText(this, "启动服务失败", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onFrame(bitmap: Bitmap, isLandscape: Boolean) {
        runOnUiThread {
            // 手机端已经根据屏幕方向调整了捕获尺寸，直接显示即可
            imageView.setImageBitmap(bitmap)
            // 连接成功后隐藏状态文字
            if (!isConnected) {
                isConnected = true
                statusText.visibility = View.INVISIBLE
            }
        }
    }

    private fun rotateBitmap(bitmap: Bitmap, degrees: Float): Bitmap {
        val matrix = Matrix()
        matrix.postRotate(degrees)
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    override fun onStatus(status: String) {
        runOnUiThread {
            statusText.text = status
        }
    }

    override fun onConnected() {
        runOnUiThread {
            statusText.text = "已连接"
        }
    }

    override fun onDisconnected() {
        runOnUiThread {
            isConnected = false
            statusText.visibility = View.VISIBLE
            statusText.text = "已断开，等待重新连接..."
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                stopServer()
                finish()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun stopServer() {
        server.stop()
    }

    override fun onDestroy() {
        stopServer()
        super.onDestroy()
    }
}