package com.rokidlab.phone.adb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.rokidlab.phone.app.LabApplication
import com.rokidlab.phone.keepalive.LabKeepAliveService

/**
 * 定时任务精确闹钟入口（[android.app.AlarmManager] 广播）。
 *
 * 为什么需要它：[TimerScheduler] 旧实现把等待挂在内存协程 `delay()` 上 —— Doze/国产 ROM
 * 省电冻结下到点不准，进程被杀后 countdown 已等待时间也全部作废。改为系统闹钟后，
 * 到点由系统**唤醒进程**并派发本接收器。
 *
 * Receiver 自身生命周期只有约 10s，而「自主任务」（AgentPrompt）一轮推理最长 90s，
 * 因此这里**不干活**，只把任务 id 投递给常驻的 [LabKeepAliveService]（前台服务 +
 * PARTIAL_WAKE_LOCK 保证执行体跑完）：
 *  1. 服务在运行（主路径：保活默认开启、WakeLock 常驻）→ `startService` 直接投递，
 *     对**已运行**服务投递 intent 不受后台启动 FGS 限制；
 *  2. 服务没在跑（保活被关、进程刚被闹钟拉起）→ 尝试 `startForegroundService`，
 *     闹钟广播授予短暂后台启动豁免；
 *  3. 两者都失败 → 退化为本进程内直接触发（通知/TTS 等秒级动作仍有机会完成，
 *     长耗时 Agent 任务可能随 receiver 结束被回收，下次打开 App 时由 missed 补跑兜底）。
 */
class TimerAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FIRE) return
        val taskId = intent.getStringExtra(EXTRA_TASK_ID) ?: run {
            Log.w(TAG, "fire alarm without task id")
            return
        }
        val appContext = context.applicationContext
        val serviceIntent = Intent(appContext, LabKeepAliveService::class.java).apply {
            action = LabKeepAliveService.ACTION_FIRE_TIMER
            putExtra(LabKeepAliveService.EXTRA_TASK_ID, taskId)
        }
        try {
            context.startService(serviceIntent)
            Log.i(TAG, "alarm delivered to running service: $taskId")
            return
        } catch (e: Exception) {
            Log.i(TAG, "startService failed, try foreground: ${e.message}")
        }
        try {
            ContextCompat.startForegroundService(context, serviceIntent)
            Log.i(TAG, "foreground service started for alarm: $taskId")
        } catch (e: Exception) {
            Log.w(TAG, "foreground start denied, run in-process fallback: ${e.message}")
            (appContext as? LabApplication)?.timerScheduler?.fireTask(taskId)
        }
    }

    companion object {
        private const val TAG = "TimerAlarmReceiver"
        const val ACTION_FIRE = "com.rokidlab.phone.action.TIMER_FIRE"
        const val EXTRA_TASK_ID = "task_id"
    }
}
