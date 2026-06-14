package com.rokidlab.rokidlink

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.ImageView

/**
 * 眼镜端接收手机投屏 Activity
 * 全屏显示投屏画面，无标题/状态栏
 */
class PhoneMirrorActivity : Activity(), PhoneMirrorServer.OnFrameListener {

    private lateinit var server: PhoneMirrorServer
    private lateinit var imageView: ImageView
    private var lastTapTime = 0L

    companion object {
        private const val TAG = "RokidLink-Mirror"
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

        // 全屏 ImageView，仅显示投屏画面
        imageView = ImageView(this).apply {
            // FIT_XY 填满整个屏幕，最大化画面
            scaleType = ImageView.ScaleType.FIT_XY
            setBackgroundColor(Color.BLACK)
            // 双击退出
            setOnClickListener {
                val now = System.currentTimeMillis()
                if (now - lastTapTime < 300) {
                    stopServer()
                    finish()
                }
                lastTapTime = now
            }
        }

        setContentView(imageView)

        // 启动 Socket 服务
        startServer()
    }

    private fun startServer() {
        server = PhoneMirrorServer(PORT)
        server.setFrameListener(this)
        if (!server.start()) {
            finish()
        }
    }

    override fun onFrame(bitmap: Bitmap, isLandscape: Boolean) {
        runOnUiThread {
            imageView.setImageBitmap(bitmap)
        }
    }

    override fun onStatus(status: String) {
        // 不显示状态文字
    }

    override fun onConnected() {
        // 不显示状态文字
    }

    override fun onDisconnected() {
        // 不显示状态文字
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
