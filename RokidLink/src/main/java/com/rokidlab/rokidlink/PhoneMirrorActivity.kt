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
 * 全屏显示投屏画面，无标题/状态栏，不留任何边框。
 *
 * 显示链路说明（本机屏 640x480 横屏 buffer 经系统旋转 90° 上竖屏）：
 * - 收到的帧已由 [PhoneMirrorServer] 按手机方向旋转为正立 Bitmap，此处直接显示。
 * - 旋转双线性采样会让窗口最外缘 1~2px 半透明，漏出下层 Launcher 形成一圈亮边
 *   （screencap 可实测到边缘恒定亮度像素），故对画面做约 2% overscan 覆盖边缘。
 * - 画面 FIT_CENTER 保比例，手机超长屏 / 横屏时上下或左右的纯黑 letterbox 属正常。
 *   ⚠️ 旧注释曾把「顶部左右镜像淡影」定性为光机棱镜二次反射的物理鬼影、与本应用无关，
 *   据此未做处理 —— 该结论已被证伪：同一条AIUI 链路在 [AiuiLinkActivity] 上实测到
 *   两条**横贯整屏、颜色恒定、位置精确等于位图边界**的亮线，经像素取证确认是
 *   overscan(1.02) 重采样放大位图边缘暖色行所致（手机端同页 0 暖色像素），
 *   去掉 overscan 即根除。故此处若再见到类似亮线，先查 overscan，别归因硬件。
 *   投屏显示的是手机截图位图、非引擎 canvas，边缘不会被重采样出暖色行，
 *   故本Activity 的 OVERSCAN 保留（它服务于旋转采样漏底，与上述问题不同源）。
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

        /** 画面放大系数：覆盖旋转采样边缘的半透明漏底，数值过大会裁切可见内容 */
        private const val OVERSCAN = 1.02f
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

        // 全屏 ImageView，仅显示投屏画面（不留任何边框，用户明确要求任何情况下都无黑边）。
        // 约 2% overscan 覆盖旋转采样导致的边缘半透明漏底（亮绿边）。
        imageView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            scaleX = OVERSCAN
            scaleY = OVERSCAN
            pivotX = 0.5f * resources.displayMetrics.widthPixels
            pivotY = 0.5f * resources.displayMetrics.heightPixels
            setBackgroundColor(Color.BLACK)
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

    override fun onFrame(bitmap: Bitmap) {
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
        // 断连时不退后台：手机端 PhoneMirrorService 会自动重连，
        // 若退后台则重连成功后画面更新在后台不可见（"投屏中但眼镜无画面"）。
        // 保持前台等待重连恢复；但当手机端主动「停止投屏」时，会经 CXR 下发
        // rokidlab_stop_phone_mirror → KeyButtonService 广播 ACTION_FINISH_MIRROR
        // 关闭本页面（不再用 stopApp 整包杀 RokidLink），避免最后一帧画面残留。
        Log.i(TAG, "Phone disconnected, staying foreground to await reconnect")
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
