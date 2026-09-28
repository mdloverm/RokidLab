package com.rokidlab.phone.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.rokidlab.phone.app.LabApplication
import com.rokidlab.phone.proactive.AmbientVisionController
import com.rokidlab.phone.proactive.CalendarBriefing
import com.rokidlab.phone.proactive.CareReminder
import com.rokidlab.phone.proactive.IdleGreeter
import com.rokidlab.phone.proactive.ProactiveGate
import com.rokidlab.phone.proactive.ProactiveGatePolicy

/**
 * 主动式功能调试触发入口（仅 debug 构建存在，见 src/debug/AndroidManifest.xml）。
 *
 * ```
 * adb shell am broadcast -a com.rokidlab.phone.debug.PROACTIVE_TICK --es target <t>
 * ```
 *
 * target 一览（全部走真实公开链路，不绕过任何门控）：
 *  - `idle`     ：空闲问候自检（与真实闹钟同链路：决策门/派发/播报全走）
 *  - `vision`   ：走走拍拍自检
 *  - `care`     ：关怀提醒自检
 *  - `briefing` ：日程简报自检
 *  - `reply`    ：模拟用户回应（onUserText → pending 主动消息结算为「有回应」）
 *  - `miss`     ：模拟用户忽略（强制把 pending 结算为「无回应」，可触发惩罚冷却）
 *  - `gate`     ：打印决策门状态（响应记录/自适应因子/间隔/冷却/惩罚）
 *  - `reset`    ：清「别烦我」冷却/响应惩罚/响应记录，活跃时刻回拨 8h（联调用）
 *  - `skip`     ：时机内容绑定端到端自检（要求模型只回 [SKIP]，验证静默收回 + 计数回退）
 *
 * release 构建无此 receiver，外部不可触发。
 */
class ProactiveDebugReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val target = intent.getStringExtra(EXTRA_TARGET) ?: run {
            Log.w(TAG, "missing '$EXTRA_TARGET' extra (idle|vision|care|briefing|reply|miss|gate|skip)")
            return
        }
        val app = context.applicationContext as? LabApplication ?: run {
            Log.w(TAG, "application is not LabApplication")
            return
        }
        val now = System.currentTimeMillis()
        Log.i(TAG, "debug tick -> $target")
        when (target) {
            "idle" -> IdleGreeter.get(app).onCheck(now)
            "vision" -> AmbientVisionController.get(app).onTick(now)
            "care" -> CareReminder.get(app).onTick(now)
            "briefing" -> CalendarBriefing.get(app).onTick(now)
            "reply" -> {
                ProactiveGate.get(app).onUserText("（调试）嗯嗯，我在", now)
                Log.i(TAG, "simulated user reply -> pending settled as responded")
            }
            "miss" -> {
                // 传「未来时刻」强制 pending 过期 → 结算为「无回应」
                ProactiveGate.get(app).settleExpiredResponse(
                    now + ProactiveGatePolicy.RESPONSE_WAIT_MS + 60_000L,
                )
                Log.i(TAG, "simulated missed response")
            }
            "gate" -> dumpGate(app, now)
            "reset" -> {
                // 直接打开 ProactiveGate 的同名 prefs（debug 联调专用）：
                // 清「别烦我」冷却 / 响应惩罚 / pending / 响应记录，并把活跃时刻回拨 8h 让空闲判定达标
                app.getSharedPreferences("proactive_gate", Context.MODE_PRIVATE).edit()
                    .remove("cooldown_until")
                    .remove("resp_penalty_until")
                    .remove("resp_pending_at")
                    .remove("resp_recent")
                    .putLong("last_interaction", now - 8 * 60 * 60 * 1000L)
                    .apply()
                Log.i(TAG, "gate reset: cooldown/penalty/responses cleared, lastInteraction -> now-8h")
            }
            "skip" -> app.timerScheduler.runProactiveAgentTask(
                prompt = "链路自检：请只回复 [SKIP] 这五个字符，不要调用任何工具，不要输出任何其他内容。",
                failureNotice = false,
                titleOverride = "调试自检",
                onSkip = { ProactiveGate.get(app).refundProactive(KIND_DEBUG_SKIP) },
            )
            else -> Log.w(TAG, "unknown target: $target")
        }
    }

    private fun dumpGate(app: LabApplication, now: Long) {
        val gate = ProactiveGate.get(app)
        val recent = gate.responseRecent()
        Log.i(
            TAG,
            "gate: recent=${recent.joinToString("") { if (it) "1" else "0" }} " +
                "factor=${"%.2f".format(gate.responseFactor())} " +
                "scene=${gate.scene().id} freq=${gate.chatFreq().id} left=${gate.remainingToday(now)} " +
                "greetingIntervalMin=${gate.greetingIntervalMs() / 60_000} " +
                "penaltyRemainingMin=${gate.responsePenaltyRemaining(now) / 60_000} " +
                "cooldownRemainingMin=${gate.cooldownRemaining(now) / 60_000} " +
                "lastInteract=${gate.lastInteraction()}",
        )
    }

    private companion object {
        const val TAG = "ProactiveDebug"
        const val EXTRA_TARGET = "target"
        const val KIND_DEBUG_SKIP = "debug_skip"
    }
}
