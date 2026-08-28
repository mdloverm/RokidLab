package com.rokidlab.rokidlink

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager

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

        /** 是否存活（onCreate=true / onDestroy=false），供 BtTunnelService 看门狗检查并保活 */
        @Volatile
        var isAlive = false

        /** KeyButtonService 在按键答题且屏幕已熄时传入：加 FLAG_TURN_SCREEN_ON 点亮屏幕 */
        const val EXTRA_WAKE_SCREEN = "wake_screen"

        /**
         * KeyButtonService 广播：官方 AI 会话活跃期间要求本 Activity 退让（finish）。
         * 常驻透明 Activity 会让 AssistServer 把本进程判定为 third_app 场景，官方 AI 会话
         * 退出时（ai_assist=false → 清理 third_app）会 force stop RokidLink（实测 15:47/16:01/16:10/16:19
         * 反复强杀）。官方 AI 会话期间退让到后台（FGS+WakeLock 保活），会话结束再恢复。
         */
        const val ACTION_YIELD = "rokidlab.action.BRIDGE_YIELD"
    }

    /** 退让广播接收器：收到 ACTION_YIELD 立即 finish，让本进程退出 third_app 场景 */
    private val yieldReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.i(TAG, "yield requested by service, finishing")
            finish()
        }
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
                    KeyButtonService.downTimeMs = 0L
                    if (down <= 0) return
                    val elapsed = System.currentTimeMillis() - down
                    Log.i(TAG, "UP elapsed=${elapsed}ms")
                    launchTargetByDuration(context, prefs, elapsed)
                }
                "com.android.action.ACTION_SPRITE_BUTTON_CLICK" -> {
                    Log.i(TAG, "CLICK → SHORT")
                    launchConfiguredTarget(context, prefs, isLong = false)
                }
                "com.android.action.ACTION_SPRITE_BUTTON_LONG_PRESS" -> {
                    Log.i(TAG, "LONG_PRESS → LONG")
                    KeyButtonService.downTimeMs = 0L
                    launchConfiguredTarget(context, prefs, isLong = true)
                }
            }
        }

        private fun isDuplicateLaunch(): Boolean {
            val now = System.currentTimeMillis()
            if ((now - lastLaunchMs) < 800L) return true
            lastLaunchMs = now
            return false
        }

        private fun launchTargetByDuration(context: Context, prefs: android.content.SharedPreferences, elapsedMs: Long) {
            // 短按且「按键答题」开启 → 触发拍照问AI（覆盖原短按启动应用），并通知 Service 上行
            if (elapsedMs < 500L && KeyButtonService.isKeyQuizEnabled(context)) {
                Log.i(TAG, "Quiz mode: SHORT(DOWN/UP) → photo ask (via Service)")
                KeyButtonService.downTimeMs = 0L
                abortBroadcast()
                notifyServiceQuizPhotoAsk(context)
                return
            }
            val pkg = prefs.getString(
                if (elapsedMs >= 500L) KeyButtonService.KEY_LONG_PKG
                else KeyButtonService.KEY_SHORT_PKG, ""
            ) ?: ""
            val act = prefs.getString(
                if (elapsedMs >= 500L) KeyButtonService.KEY_LONG_ACT
                else KeyButtonService.KEY_SHORT_ACT, ".MainActivity"
            ) ?: ".MainActivity"
            if (pkg.isBlank()) {
                Log.w(TAG, "No target for ${if (elapsedMs >= 500L) "LONG" else "SHORT"} (elapsed=${elapsedMs}ms)")
                return
            }
            if (isDuplicateLaunch()) return
            abortBroadcast()
            launchTargetAndLog(pkg, act, "duration=${if (elapsedMs >= 500L) "LONG" else "SHORT"}")
        }

        private fun launchConfiguredTarget(context: Context, prefs: android.content.SharedPreferences, isLong: Boolean) {
            // 短按且「按键答题」开启 → 触发拍照问AI（覆盖原短按启动应用，长按不受影响）
            if (!isLong && KeyButtonService.isKeyQuizEnabled(context)) {
                Log.i(TAG, "Quiz mode: SHORT → photo ask (via Service)")
                KeyButtonService.downTimeMs = 0L
                abortBroadcast()
                notifyServiceQuizPhotoAsk(context)
                return
            }
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
                Log.w(TAG, "No configured target for ${if (isLong) "LONG" else "SHORT"}")
                return
            }
            if (isDuplicateLaunch()) return
            abortBroadcast()
            launchTargetAndLog(pkg, act, if (isLong) "LONG" else "SHORT")
        }

        /** quiz 短按：通过 startService 通知常驻 KeyButtonService 发送拍照答题上行（bridge 在 Service 内） */
        private fun notifyServiceQuizPhotoAsk(context: Context) {
            try {
                val intent = Intent(context, KeyButtonService::class.java).apply {
                    action = KeyButtonService.ACTION_QUIZ_PHOTO_ASK
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                Log.i(TAG, "Quiz photo ask notified to Service")
            } catch (e: Exception) {
                Log.e(TAG, "notifyServiceQuizPhotoAsk failed: ${e.message}")
            }
        }

        private fun launchTargetAndLog(pkg: String, activity: String, mode: String) {
            val fullAct = if (activity.startsWith(".")) "$pkg$activity" else activity
            try {
                // 1. 优先使用系统 launch intent
                val launchIntent = packageManager.getLaunchIntentForPackage(pkg)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    startActivity(launchIntent)
                    Log.i(TAG, "Launched ($mode): $pkg (launchIntent)")
                    return
                }

                // 2. 尝试显式 activity
                try {
                    Intent(Intent.ACTION_MAIN).apply {
                        component = ComponentName(pkg, fullAct)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }.also { startActivity(it) }
                    Log.i(TAG, "Launched ($mode): $pkg/$fullAct (explicit)")
                    return
                } catch (_: ActivityNotFoundException) {
                    // 3. 显式失败 → 自动查包的实际 launcher
                }

                // 4. 用 getPackageInfo 查包的所有 activity，找第一个可导出的
                try {
                    val pkgInfo = packageManager.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES)
                    val firstActivity = pkgInfo.activities?.firstOrNull { it.exported }?.name
                    if (firstActivity != null) {
                        Intent(Intent.ACTION_MAIN).apply {
                            component = ComponentName(pkg, firstActivity)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            startActivity(this)
                        }
                        Log.i(TAG, "Launched ($mode): $pkg/$firstActivity (auto)")
                        return
                    } else {
                        Log.w(TAG, "No exported activity in manifest for $pkg ($mode)")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "getPackageInfo failed for $pkg ($mode): ${e::class.simpleName}: ${e.message}")
                }

                // 5. 最终兜底：用 pm resolve-activity 命令行
                try {
                    val process = Runtime.getRuntime().exec("pm resolve-activity --brief $pkg")
                    val output = java.io.BufferedReader(java.io.InputStreamReader(process.inputStream)).readText().trim()
                    process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
                    if (output.isNotBlank() && !output.contains("Error") && !output.contains("No activity")) {
                        val actLine = output.lines().firstOrNull { it.startsWith(pkg) }
                        if (actLine != null) {
                            val activityName = actLine.removePrefix("$pkg/")
                            Intent(Intent.ACTION_MAIN).apply {
                                component = ComponentName(pkg, activityName)
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                startActivity(this)
                            }
                            Log.i(TAG, "Launched ($mode): $pkg/$activityName (pm)")
                            return
                        }
                    }
                    Log.e(TAG, "pm resolve-activity gave no result for $pkg ($mode): $output")
                } catch (e: Exception) {
                    Log.w(TAG, "pm resolve-activity failed for $pkg ($mode): ${e.message}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "launchTarget failed ($mode): ${e::class.simpleName}: ${e.message}")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isAlive = true
        Log.i(TAG, "onCreate — alive")

        // 按键答题时屏幕已熄的场景：KeyButtonService 以 wake_screen=true 拉起本 Activity，
        // 窗口显示时点亮屏幕（否则 AI 会话处于退出态、相机不可用，拍照会超时）。
        if (intent?.getBooleanExtra(EXTRA_WAKE_SCREEN, false) == true) {
            window.addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
            Log.i(TAG, "FLAG_TURN_SCREEN_ON applied (wake_screen=true)")
        }

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

        // 退让广播（官方 AI 会话期间被 KeyButtonService 要求退出前台）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(yieldReceiver, IntentFilter(ACTION_YIELD), Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(yieldReceiver, IntentFilter(ACTION_YIELD))
        }
    }

    override fun onDestroy() {
        isAlive = false
        Log.i(TAG, "onDestroy")
        runCatching { unregisterReceiver(keyReceiver) }
        runCatching { unregisterReceiver(yieldReceiver) }
        super.onDestroy()
    }
}
