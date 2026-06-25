package com.rokidlab.rokidlink

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.View
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

    // 接收 ScreenMirrorIntentActivity 发来的关闭广播，确保投屏不会残留
    private val finishMirrorReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.i(TAG, "收到关闭广播，结束 PhoneMirrorActivity")
            stopServer()
            finish()
        }
    }

    companion object {
        private const val TAG = "RokidLink-Mirror"
        const val PORT = 7654

        /** ScreenMirrorIntentActivity 启动时发出的广播 Action，用于关闭本页面 */
        const val ACTION_FINISH_MIRROR = "com.rokidlab.rokidlink.FINISH_MIRROR"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 发送广播关闭同应用内的 MainActivity（ADB IP 配置页），确保投屏独立运行
        sendBroadcast(Intent(MainActivity.ACTION_FINISH_MAIN))

        // 注册关闭广播，当 ScreenMirrorIntentActivity 启动时自动结束本页面
        registerReceiver(finishMirrorReceiver, IntentFilter(ACTION_FINISH_MIRROR))

        // 全屏显示，隐藏状态栏和导航栏
        window.setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN,
            WindowManager.LayoutParams.FLAG_FULLSCREEN
        )
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // 全屏 ImageView，仅显示投屏画面
        imageView = ImageView(this).apply {
            // FIT_CENTER 保持画面比例，背景透明，非画面区域透出窗口背景黑色，看不出边框
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.TRANSPARENT)
            // 初始隐藏，收到帧后才显示，避免启动时出现空白方框
            visibility = View.INVISIBLE
            // 双击退出
            setOnClickListener {
                val now = System.currentTimeMillis()
                if (now - lastTapTime < 500) {
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

    override fun onResume() {
        super.onResume()
        // CXR-L 重新拉起已存在的 Activity 时可能没有走 onCreate，需检查 Server 状态
        if (!::server.isInitialized || !server.isRunning) {
            startServer()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // singleTask 模式下 CXR-L 重新拉起时走此路径，不是 onCreate
        // Server 已在 onResume 中检查并启动，此处仅记录日志
        Log.i(TAG, "onNewIntent (singleTask re-launch)")
    }

    private fun startServer() {
        // 如果旧的 Server 还在运行，先停掉避免冲突
        if (::server.isInitialized) {
            server.stop()
        }
        server = PhoneMirrorServer(PORT)
        server.setFrameListener(this)
        if (!server.start()) {
            finish()
        }
    }

    override fun onFrame(bitmap: Bitmap, isLandscape: Boolean) {
        runOnUiThread {
            imageView.visibility = View.VISIBLE
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
        runOnUiThread {
            imageView.setImageBitmap(null)
            // 断连后退到后台（不关闭），RokidLink 保持运行，投屏 Server 继续等待重连
            moveTaskToBack(true)
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
        runCatching { unregisterReceiver(finishMirrorReceiver) }
        super.onDestroy()
    }
}
