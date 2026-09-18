package com.rokidlab.rokidlink

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.net.InetSocketAddress
import java.net.Socket

/**
 * 屏幕镜像启动中转 Activity
 * 由 CXR-L 启动此 Activity，将眼镜带到前台后自动关闭
 * 手机端通过 ADB 协议直接连接眼镜执行 screencap 获取画面
 *
 * 等待 ADB TCP (port 5555) 连接建立后自动关闭，
 * 确保手机端 ADB 连接成功前眼镜保持在前台。
 */
class ScreenMirrorIntentActivity : Activity() {

    companion object {
        private const val TAG = "ScreenMirrorIntent"
        /** 最长等待时间 (ms)：手机端 scrcpy 启动有 15s deadline，这里取 10s 留有余量 */
        private const val MAX_WAIT_MS = 10_000L
        /** ADB TCP 默认端口 */
        private const val ADB_PORT = 5555
        /** 连接检测轮询间隔 */
        private const val POLL_INTERVAL_MS = 500L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "ScreenMirrorIntentActivity started")

        // 发送广播关闭可能残留的 PhoneMirrorActivity（投屏页面）
        sendBroadcast(Intent(PhoneMirrorActivity.ACTION_FINISH_MIRROR))

        // 在后台线程中等待 ADB 连接建立，或超时后自动关闭
        namedThread("mirror-intent-io", start = true) {
            val connected = waitForAdbConnection()
            Log.i(TAG, "Adb connection check result: $connected, auto closing")
            Handler(Looper.getMainLooper()).postDelayed({
                finish()
            }, 500) // 延迟 500ms 确保画面稳定
        }
    }

    /**
     * 轮询检测 ADB TCP (127.0.0.1:5555) 是否已有来自手机的连接。
     * 当连接到达时立即返回 true，否则等待 MAX_WAIT_MS 后返回 false。
     */
    private fun waitForAdbConnection(): Boolean {
        val deadline = System.currentTimeMillis() + MAX_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            try {
                val socket = Socket()
                socket.connect(InetSocketAddress("127.0.0.1", ADB_PORT), 200)
                // 连接成功 — 说明 ADB 正在监听（即 adbd 已启动）
                socket.close()
                Log.i(TAG, "ADB port $ADB_PORT is listening, device ready")
                return true
            } catch (_: Exception) {
                // adbd 尚未就绪，继续等待
            }
            try {
                Thread.sleep(POLL_INTERVAL_MS)
            } catch (_: InterruptedException) {
                break
            }
        }
        Log.w(TAG, "ADB port $ADB_PORT not ready within ${MAX_WAIT_MS}ms, closing anyway")
        return false
    }
}
