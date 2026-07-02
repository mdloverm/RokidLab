package com.rokidlab.rokidlink

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.util.Log

/**
 * 常驻透明 Activity — 保持进程始终拥有可见窗口。
 *
 * 这是 Android 12 后台 Activity 启动限制的关键解决方案：
 * 一个 Activity（即使透明）能使进程保持 VISIBLE 状态，
 * 从而允许从 onReceive() 中调用 startActivity() 启动目标应用。
 *
 * 由 KeyButtonService.onCreate() 启动，不会 finish()。
 * 如果被系统销毁，Service 的 START_STICKY 会在下一轮重启时重新创建。
 */
class KeyButtonBridgeActivity : Activity() {
    companion object {
        private const val TAG = "KeyButtonBridge"
    }

    /** 按键接收器 */
    private val keyReceiver = object : BroadcastReceiver() {
        private var lastLaunchMs = 0L

        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action ?: return
            val prefs = context.getSharedPreferences(KeyButtonService.PREFS_NAME, 0)

            when (action) {
                "com.android.action.ACTION_SPRITE_BUTTON_DOWN" -> {
                    KeyButtonService.downTimeMs = System.currentTimeMillis()
                }
                "com.android.action.ACTION_SPRITE_BUTTON_UP" -> {
                    val down = KeyButtonService.downTimeMs
                    if (down <= 0) return
                    val elapsed = System.currentTimeMillis() - down
                    val isLong = elapsed >= 500L
                    Log.i(TAG, "UP elapsed=${elapsed}ms -> ${if (isLong) "LONG" else "SHORT"}")

                    val pkg = prefs.getString(
                        if (isLong) KeyButtonService.KEY_LONG_PKG
                        else KeyButtonService.KEY_SHORT_PKG, ""
                    ) ?: ""
                    val act = prefs.getString(
                        if (isLong) KeyButtonService.KEY_LONG_ACT
                        else KeyButtonService.KEY_SHORT_ACT, ".MainActivity"
                    ) ?: ".MainActivity"

                    KeyButtonService.downTimeMs = 0L
                    if (pkg.isBlank()) {
                        Log.w(TAG, "No target configured")
                        return
                    }
                    if (isDuplicateLaunch()) return
                    abortBroadcast()
                    launchTarget(pkg, act)
                }
                "com.android.action.ACTION_SPRITE_BUTTON_CLICK" -> {
                    Log.i(TAG, "CLICK (legacy) — ignored")
                }
                "com.android.action.ACTION_SPRITE_BUTTON_LONG_PRESS" -> {
                    Log.i(TAG, "LONG_PRESS (legacy) — ignored")
                }
            }
        }

        private fun isDuplicateLaunch(): Boolean {
            val now = System.currentTimeMillis()
            if ((now - lastLaunchMs) < 800L) return true
            lastLaunchMs = now
            return false
        }
    }

    private fun launchTarget(pkg: String, activity: String) {
        val fullAct = if (activity.startsWith(".")) "$pkg$activity" else activity
        try {
            val launchIntent = packageManager.getLaunchIntentForPackage(pkg)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                startActivity(launchIntent)
                Log.i(TAG, "Launched: $pkg (launchIntent)")
            } else {
                Intent(Intent.ACTION_MAIN).apply {
                    component = ComponentName(pkg, fullAct)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(this)
                }
                Log.i(TAG, "Launched: $pkg/$fullAct (explicit)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "launchTarget failed: ${e::class.simpleName}: ${e.message}")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "onCreate — alive")

        val filter = IntentFilter().apply {
            addAction("com.android.action.ACTION_SPRITE_BUTTON_DOWN")
            addAction("com.android.action.ACTION_SPRITE_BUTTON_UP")
            addAction("com.android.action.ACTION_SPRITE_BUTTON_CLICK")
            addAction("com.android.action.ACTION_SPRITE_BUTTON_LONG_PRESS")
            priority = 100
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(keyReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(keyReceiver, filter)
        }
        Log.i(TAG, "Key receiver registered")
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy")
        runCatching { unregisterReceiver(keyReceiver) }
        super.onDestroy()
    }
}
