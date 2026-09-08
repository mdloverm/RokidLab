package com.rokidlab.rokidlink

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 断线自愈重启接收器（AlarmManager 触发）。
 *
 * cxr-service bridge 断线重连后，其分发路由可能进入 stale 状态：订阅 API 返回 0，
 * 但实际消息不再投递到本 App（实测 ai_config/tts/show_main 全部静默丢失），
 * 进程内重建 bridge 无法恢复，仅进程重启有效。KeyButtonService 检测到该状态后
 * 经 Alarm 调度本接收器执行自杀重启。
 *
 * 双动作：
 * - ACTION_SELF_HEAL_RESTART：先显式启动常驻服务（保证有新的 START 记录），再杀进程，
 *   由 START_STICKY 在新进程重建 KeyButtonService/BtTunnelService 及 CXR 订阅。
 * - ACTION_SELF_HEAL_BOOTSTRAP：兜底拉起。自杀后若粘性重启被 ROM 延迟/拦截，
 *   该闹钟会唤醒新进程并显式启动两个常驻服务。
 */
class SelfRestartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            KeyButtonService.ACTION_SELF_HEAL_RESTART -> {
                Log.w(TAG, "SELF_HEAL_RESTART: restarting services then killing process")
                // 自杀前显式补发启动请求：进程重启后即便粘性重启丢失，仍有启动记录兜底
                runCatching { KeyButtonService.start(context) }
                    .onFailure { Log.e(TAG, "pre-kill start KeyButtonService failed", it) }
                runCatching { BtTunnelService.start(context) }
                    .onFailure { Log.e(TAG, "pre-kill start BtTunnelService failed", it) }
                // 自杀：cxr-service 侧需要看到本进程断开并以全新注册重建订阅路由
                android.os.Process.killProcess(android.os.Process.myPid())
            }
            KeyButtonService.ACTION_SELF_HEAL_BOOTSTRAP -> {
                Log.w(TAG, "SELF_HEAL_BOOTSTRAP: ensuring services up in fresh process")
                runCatching { KeyButtonService.start(context) }
                    .onFailure { Log.e(TAG, "bootstrap start KeyButtonService failed", it) }
                runCatching { BtTunnelService.start(context) }
                    .onFailure { Log.e(TAG, "bootstrap start BtTunnelService failed", it) }
            }
        }
    }

    companion object {
        private const val TAG = "SelfRestartReceiver"
    }
}
