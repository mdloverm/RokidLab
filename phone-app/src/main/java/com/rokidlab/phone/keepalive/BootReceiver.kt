package com.rokidlab.phone.keepalive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.rokidlab.phone.app.LabApplication

/**
 * 保活服务的系统事件入口：开机自启 + onTaskRemoved 闹钟重启。
 *
 * 两个入口：
 *  - [Intent.ACTION_BOOT_COMPLETED]：设备开机且用户解锁后自动拉起保活服务
 *    （此前必须用户手动打开过一次 App，眼镜语音才会随开机恢复）；
 *  - [ACTION_RESTART_KEEPALIVE]：[LabKeepAliveService.onTaskRemoved] 安排的 2s 闹钟，
 *    作为 START_STICKY 在 MIUI 等 ROM 上重建被抑制时的双保险。
 *
 * Receiver 生命周期仅约 10s，只做 startForegroundService，重活全部在服务内完成。
 * BOOT_COMPLETED 与 alarm 触发期间系统均授予短暂后台启动豁免，可合法启动 FGS；
 * 极端 ROM 仍拒绝时（ForegroundServiceStartNotAllowedException）静默放弃，
 * 下次用户打开 App 由 MainActivity 重新拉起。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            ACTION_RESTART_KEEPALIVE -> startKeepAlive(context)
        }
    }

    private fun startKeepAlive(context: Context) {
        val app = context.applicationContext as? LabApplication ?: run {
            Log.w(TAG, "applicationContext is not LabApplication, skip")
            return
        }
        if (!app.keepAliveEnabled) return
        try {
            ContextCompat.startForegroundService(
                app,
                Intent(app, LabKeepAliveService::class.java),
            )
            Log.i(TAG, "keep-alive service start requested (action)")
        } catch (e: Exception) {
            Log.w(TAG, "startForegroundService denied: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "BootReceiver"
        const val ACTION_RESTART_KEEPALIVE = "com.rokidlab.phone.action.RESTART_KEEPALIVE"
    }
}
