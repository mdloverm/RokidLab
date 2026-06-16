package com.rokidlab.rokidlink

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * 屏幕镜像启动中转 Activity
 * 由 CXR-L 启动此 Activity，将眼镜带到前台后自动关闭
 * 手机端通过 ADB 协议直接连接眼镜执行 screencap 获取画面
 */
class ScreenMirrorIntentActivity : Activity() {

    companion object {
        private const val TAG = "ScreenMirrorIntent"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "ScreenMirrorIntentActivity 启动，2秒后自动关闭")

        // 延时关闭，确保眼镜画面切换到前台
        Handler(Looper.getMainLooper()).postDelayed({
            finish()
        }, 2000)
    }
}
