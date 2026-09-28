package com.rokidlab.phone.proactive

import android.content.Context
import android.util.Log
import com.rokidlab.phone.R
import com.rokidlab.phone.app.LabApplication
import com.rokidlab.phone.glasses.AiChannel
import com.rokidlab.phone.glasses.MotionBuffer
import kotlin.math.asin
import kotlin.math.sqrt

private const val PREFS = "proactive_care"
private const val K_ENABLED = "enabled"
private const val K_STATE = "state"
private const val K_STATE_SINCE = "state_since"

/** IMU 判定窗口（移动/低头共用缓冲） */
private const val MOTION_WINDOW_MS = 60_000L

/** 事件阈值：连续行进 / 保持静止 / 低头，超过即产生「关怀事件」（internal：主动性面板要按档位缩放实时展示） */
internal const val MOVE_LIMIT_MS = 45 * 60 * 1000L
internal const val STILL_LIMIT_MS = 60 * 60 * 1000L
internal const val HEAD_DOWN_LIMIT_MS = 30 * 60 * 1000L
private const val HEAD_DOWN_ANGLE_DEG = 20f

/**
 * 关怀提醒（主动式陪伴，IMU 统一入口）：
 *
 * **一个开关，多种情境，AI 判断**——手机端只做特征检测（产生事件），
 * 选哪种关心方式、怎么说，全部交给模型：prompt 里描述检测到的事件
 * （久走/久坐/低头…），由模型生成贴合情境的一句关怀。
 * 未来新增事件（跌倒/瞌睡/跑步…）只需往 [collectEvents] 加检测分支，不改 UI 不改开关。
 *
 * 挂靠 [IdleGreeter] 的 30 分钟自检闹钟第②段；准入走 [ProactiveGate.ambientVisionAllowed]
 * （档位 OFF / 冷却期 / 免打扰 全拦）。IMU 数据不足时静默跳过（宁可不提醒）。
 */
class CareReminder private constructor(private val appContext: Context) {
    companion object {
        private const val TAG = "CareReminder"

        @Volatile
        private var instance: CareReminder? = null

        fun get(context: Context): CareReminder =
            instance ?: synchronized(this) {
                instance ?: CareReminder(context.applicationContext).also { instance = it }
            }
    }

    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(): Boolean = prefs.getBoolean(K_ENABLED, false)

    fun setEnabled(on: Boolean) {
        prefs.edit().putBoolean(K_ENABLED, on).apply()
        if (!on) prefs.edit().remove(K_STATE).remove(K_STATE_SINCE).apply()
        Log.i(TAG, "care reminder ${if (on) "enabled" else "disabled"}")
    }

    /** 俯仰角（度）：四元数 → 欧拉角。低头时为负（符号约定若与固件不符，真机校准取反即可） */
    private fun pitchDeg(s: AiChannel.ImuSample): Float =
        (Math.toDegrees(
            asin((-2.0 * (s.qx * s.qz - s.qw * s.qy)).coerceIn(-1.0, 1.0)),
        )).toFloat()

    /** 到点自检（由 IdleGreeter 自检链调用）：检测事件 → 决策门 → AI 生成关怀话术 */
    fun onTick(nowMs: Long = System.currentTimeMillis()) {
        if (!isEnabled()) return
        val app = appContext as? LabApplication
        if (app == null || !app.hasCxrL()) {
            Log.i(TAG, "skip: cxrL not ready")
            return
        }
        MobileMotionTracker.ensureStarted(appContext) // 手机加速度计兜底采样（幂等）
        val samples = MotionBuffer.recent(MOTION_WINDOW_MS)
        // 兜底：眼镜 IMU 流未开启时用手机加速度计（仅 moving/still 可判；avgPitch 无四元数自然降级）
        val mags: List<Float>
        var avgPitch = Float.NaN
        if (samples.size >= 5) {
            mags = samples.map { s -> sqrt(s.ax * s.ax + s.ay * s.ay + s.az * s.az) }
            avgPitch = samples.map { pitchDeg(it) }.average().toFloat()
        } else {
            mags = MobileMotionTracker.recent(MOTION_WINDOW_MS)
            if (mags.size < 5) {
                Log.i(TAG, "skip: no imu data (glasses=${samples.size}, phone=${mags.size})")
                return
            }
            Log.i(TAG, "using phone accelerometer fallback")
        }
        val moving = ProactiveGatePolicy.isMoving(mags)

        val events = collectEvents(moving, avgPitch, nowMs)
        if (events.isEmpty()) return
        if (!ProactiveGate.get(appContext).ambientVisionAllowed(nowMs)) {
            Log.i(TAG, "skip: gate suppress (level/cooldown/quiet), events=$events")
            return
        }
        resetTimers(nowMs) // 提醒后重置累计，防连环
        val prompt = "用户眼镜的 IMU 传感器检测到：${events.joinToString("；")}。" +
            "请以乐奇的身份发一句简短贴心的提醒（30 字以内），针对这种情况自然地关心一句，" +
            "像同行朋友随口说的；不要说教、不要列要点。"
        Log.i(TAG, "care reminder triggered: $events")
        app.timerScheduler.runProactiveAgentTask(
            prompt = prompt,
            failureNotice = false,
            titleOverride = appContext.getString(R.string.proactive_care),
        )
    }

    /**
     * 特征检测：返回人类可读的事件描述列表（喂给模型的素材）。
     * 状态机按「事件键」分轨计时：同一键持续期间累计时长，状态翻转即重置。
     * 阈值是固定的常识值（久走 45 分 / 久坐 60 分 / 低头 30 分），场景只决定这一层开不开。
     */
    private fun collectEvents(moving: Boolean, avgPitchDeg: Float, nowMs: Long): List<String> {
        val events = mutableListOf<String>()
        // 关怀层阈值固定：场景只决定「开不开」，阈值本身是常识值，不随频率档缩放
        val moveLimit = MOVE_LIMIT_MS
        val stillLimit = STILL_LIMIT_MS
        val headDownLimit = HEAD_DOWN_LIMIT_MS
        val heldMs = nowMs - prefs.getLong(K_STATE_SINCE, 0L)
        val stateKey = when {
            moving -> "moving"
            avgPitchDeg <= -HEAD_DOWN_ANGLE_DEG -> "head-down"
            else -> "still"
        }
        if (prefs.getString(K_STATE, null) != stateKey || heldMs <= 0L) {
            prefs.edit().putString(K_STATE, stateKey).putLong(K_STATE_SINCE, nowMs).apply()
            return events
        }
        when (stateKey) {
            "moving" -> if (heldMs >= moveLimit) {
                events.add("已经连续走动约 ${heldMs / 60000} 分钟")
            }
            "head-down" -> if (heldMs >= headDownLimit) {
                events.add("已经低着头约 ${heldMs / 60000} 分钟")
            }
            else -> if (heldMs >= stillLimit) {
                events.add("已经保持同一姿势约 ${heldMs / 60000} 分钟")
            }
        }
        return events
    }

    private fun resetTimers(nowMs: Long) {
        prefs.edit().putLong(K_STATE_SINCE, nowMs).apply()
    }
}
