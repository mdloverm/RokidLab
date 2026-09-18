package com.rokidlab.rokidlink

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 断线自愈 / 常驻服务看护接收器（AlarmManager 触发，**仅本应用内部广播**）。
 *
 * 三个动作：
 *
 * - [KeyButtonService.ACTION_SELF_HEAL_RESTART]：先显式补发启动请求（保证有新的 START 记录），
 *   再自杀。用于 cxr-service bridge 重连后**分发路由 stale** 的场景 —— 此时订阅 API 返回 0、
 *   消息却不再投递到本 App（实测 ai_config/tts/show_main 全部静默丢失），
 *   进程内重建 bridge 无法恢复，只有进程重启有效。
 *
 * - [KeyButtonService.ACTION_SELF_HEAL_BOOTSTRAP]：拉起常驻服务。两个来源共用它：
 *   ① 自杀后的兜底拉起（防粘性重启被 ROM 延迟/拦截）；
 *   ② [ResidentWatchdog.scheduleRetry] 的自愈重试链（每次带 attempt+1 再排下一次）。
 *
 * - [ResidentWatchdog.ACTION_HEARTBEAT]：周期心跳（5min）。收到即补齐缺失的常驻服务，
 *   **并重挂下一次心跳** —— 重挂发生在本接收器（可能是新拉起的进程）内，
 *   因此这条链能在进程被回收后自我延续。
 *
 * ## 为什么必须用 Receiver + Alarm，而不是 Handler.postDelayed
 *
 * Handler 的延时任务活在被杀的进程里：进程一死队列即蒸发，回调永不执行。
 * 只有系统级的 Alarm 能把「进程已经不在了」这件事投递到一个新拉起的进程里。
 *
 * ## ⚠️ 本接收器为什么保持 exported=false
 *
 * [KeyButtonService.ACTION_SELF_HEAL_RESTART] 会 `killProcess(myPid())`。
 * 一旦导出，同机任意第三方应用发一条同名广播就能让 RokidLink 自杀。
 * 需要接系统广播（开机/覆盖安装）的部分由 [BootReceiver] 单独承担（那里 exported=true 且
 * 只做「拉起服务」这一件无害的事）。**新增动作前请先确认是否会让本类具备被外部触发的破坏性。**
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
                val attempt = intent.getIntExtra(ResidentWatchdog.EXTRA_RETRY_ATTEMPT, 0)
                val healthy = ResidentWatchdog.ensureResidentServices(context)
                Log.w(TAG, "SELF_HEAL_BOOTSTRAP (attempt=$attempt): healthy=$healthy")
                // 仍未补齐 → 继续排下一次（attempt+1），直到上限。
                // 排在这里而不是原进程里，是这条链能在进程死亡后继续的唯一原因。
                if (!healthy) {
                    // 服务没起来时顺手把周期心跳也续上：短周期重试有上限（10 次 ≈ 50s），
                    // 用尽后不能连「长期兜底」也一起断掉。
                    ResidentWatchdog.armHeartbeat(context)
                    ResidentWatchdog.scheduleRetry(context, attempt)
                }
            }

            ResidentWatchdog.ACTION_HEARTBEAT -> {
                val healthy = ResidentWatchdog.ensureResidentServices(context)
                Log.i(TAG, "WATCHDOG_HEARTBEAT: resident services healthy=$healthy")
                // 无论健康与否都重挂：健康时重挂是「续期」，不健康时重挂是「继续尝试」
                ResidentWatchdog.armHeartbeat(context)
            }

            else -> Log.d(TAG, "ignored action: ${intent.action}")
        }
    }

    companion object {
        private const val TAG = "SelfRestartReceiver"
    }
}
