package com.rokidlab.phone.proactive

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * 对话内陪伴（主动性 #A）——「主动」的大头在对话内，不在推送：
 *
 * 1. **话题枯竭检测**：用户连续敷衍短回复（嗯/哈哈/不知道…）达到阈值 → 给下一轮生成注入
 *    「换个有趣话题/追问细节」的临时指令（prompt 增强，零额外消息，最自然）。
 * 2. **沉默追击**：对话进行中用户突然没声（2.5~10 分钟窗口）→ 像朋友一样追一句。
 *    每轮会话最多追 [MAX_NUDGES_PER_SESSION] 次，用户再开口即重置。
 *
 * 检查点设计：追击窗口只有 2.5~10 分钟，挂在 30 分钟空闲自检上基本永远错过（命中靠相位
 * 运气）——改为**用户每次开口后挂一次性精确闹钟（T+3min，[CHECK_DELAY_MS]）**：到点时
 * 沉默恰为 3 分钟、落在窗口内；用户中途再开口则由 [ProactiveGate.onUserText] 重新调度
 * （同 requestCode 自动覆盖），追满 2 次/开关关/OFF 档/冷却中则不再挂。
 *
 * 对话内主动性**不走**无人值守决策门（用户本来就在聊，多说不算骚扰），
 * 但「别烦我」冷却与档位 OFF 总闸仍然生效。
 */
object CompanionNudge {

    private const val TAG = "CompanionNudge"

    internal const val ACTION_NUDGE_CHECK = "com.rokidlab.phone.proactive.NUDGE_CHECK"
    private const val PI_RC = 2002

    /** 敷衍判定：去空白后不超过 4 个字符 */
    private val DRY_WORDS = setOf(
        "嗯", "嗯嗯", "哦", "哦哦", "噢", "噢噢", "额", "诶", "唉",
        "好", "好的", "好吧", "行", "行吧", "嗯好", "是的", "是吗", "对", "对啊", "对的",
        "哈", "哈哈", "哈哈哈", "哈哈哈哈", "呵", "呵呵", "嘿嘿", "嘻嘻",
        "不知道", "没", "没有", "没呀", "随便", "算了", "没事", "没什么", "不晓得",
        "ok", "OK", "嗯呐", "昂", "哦呦", "666", "6",
    )

    /** 连续敷衍回复达到该条数 → 视为话题枯竭 */
    const val DRY_STREAK_THRESHOLD = 3

    /** 沉默追击窗口：沉默低于下限算还在聊，高于上限已经冷了（转归推送问候体系） */
    const val NUDGE_MIN_SILENCE_MS = 150_000L   // 2.5 分钟
    const val NUDGE_MAX_SILENCE_MS = 600_000L   // 10 分钟

    /** 每轮会话最多追击次数基准值；实际取当前场景名额 [ProactiveGate.maxNudges]——连追两次还不回就识趣闭嘴 */
    const val MAX_NUDGES_PER_SESSION = 2

    /**
     * 追击检查点延迟：用户最后一次开口后 3 分钟到点检查。
     * silence=3min 落在追击窗口 [2.5min, 10min] 内，且给用户留出继续说话的重排余量。
     */
    const val CHECK_DELAY_MS = 180_000L

    /** 敷衍短回复判定（纯函数）：null = 正常回复（枯竭计数清零），true = 敷衍（计数+1） */
    fun dryReply(text: String?): Boolean? {
        val t = text?.trim() ?: return null
        if (t.isEmpty() || t.length > 4) return false
        return t.lowercase() in DRY_WORDS || t.all { it == '。' || it == '…' || it == '.' }
    }

    /** 沉默追击判定（纯函数，手机端实时状态传入，便于单测）；maxNudges<=0 表示档位不追 */
    fun shouldNudge(
        nowMs: Long,
        lastInteractionAt: Long?,
        nudgeCount: Int,
        enabled: Boolean,
        levelOff: Boolean,
        cooldownActive: Boolean,
        maxNudges: Int = MAX_NUDGES_PER_SESSION,
    ): Boolean {
        if (!enabled || levelOff || cooldownActive) return false
        if (maxNudges <= 0 || nudgeCount >= maxNudges) return false
        val last = lastInteractionAt ?: return false
        val silence = nowMs - last
        return silence in NUDGE_MIN_SILENCE_MS..NUDGE_MAX_SILENCE_MS
    }

    /** 追击 prompt：像朋友一样追一句，不慌不贫 */
    fun nudgePrompt(silenceMin: Long): String =
        "（系统检测，非用户输入）用户刚才跟你聊着聊着突然没声了（大约 ${silenceMin} 分钟没回复）。" +
            "像朋友一样自然地追一句：可以接着刚才的话题问一句、可以轻轻调侃、也可以随口说点别的。" +
            "一次只说一句，简短，别连环夺命。"

    /** 自检 tick 入口：会话活跃窗口内用户沉默 → 追一句 */
    fun onSelfCheckTick(appContext: Context) {
        val gate = ProactiveGate.get(appContext)
        val now = System.currentTimeMillis()
        val last = gate.lastInteraction()
        val count = gate.nudgeCount()
        val decision = shouldNudge(
            nowMs = now,
            lastInteractionAt = last,
            nudgeCount = count,
            enabled = gate.isChatCompanionEnabled(),
            levelOff = gate.isMuted(),
            cooldownActive = gate.cooldownRemaining(now) > 0,
            maxNudges = gate.maxNudges(),
        )
        if (!decision) return
        dispatchNudge(appContext, now, last)
    }

    /**
     * 追击检查点到点（由 [NudgeCheckReceiver] 投递，用户开口后 T+3min）：
     * 用户真的沉默着（silence 仍在窗口内、没追满、没冷却）→ 追一句；
     * 用户中途又开了口 → onUserText 已刷新活跃时刻并重排检查点，此处判定自然为 false。
     */
    fun onNudgeCheck(appContext: Context) {
        onSelfCheckTick(appContext)
    }

    /** 追击派发（占用名额 + 无人值守轮次，进会话历史保持上下文连贯） */
    private fun dispatchNudge(appContext: Context, now: Long, lastInteractionAt: Long) {
        val gate = ProactiveGate.get(appContext)
        val silenceMin = (now - lastInteractionAt) / 60_000L
        val slotUsed = gate.nudgeCount()
        gate.consumeNudgeSlot(now)
        Log.i(TAG, "companion nudge #$slotUsed triggered (silence=${silenceMin}min)")
        val app = appContext as? com.rokidlab.phone.app.LabApplication ?: return
        app.timerScheduler.runProactiveAgentTask(
            prompt = nudgePrompt(silenceMin),
            recordHistory = true, // 追击是真实对话的一部分，必须进会话历史
        )
    }

    /**
     * 用户开口后挂一次性精确检查点（T+[CHECK_DELAY_MS]）。
     * 预判到点时是否可能追击（开关/档位/冷却/名额），不可能就不挂闹钟；
     * 同 requestCode FLAG_UPDATE_CURRENT——用户连续开口时自动覆盖旧检查点（等价重排）。
     */
    fun scheduleCheck(appContext: Context, userTextAtMs: Long = System.currentTimeMillis()) {
        val gate = ProactiveGate.get(appContext)
        val fireAt = userTextAtMs + CHECK_DELAY_MS
        val due = shouldNudge(
            nowMs = fireAt,
            lastInteractionAt = userTextAtMs, // 假设用户到点前不再开口
            nudgeCount = gate.nudgeCount(),
            enabled = gate.isChatCompanionEnabled(),
            levelOff = gate.isMuted(),
            cooldownActive = gate.cooldownRemaining(fireAt) > 0,
            maxNudges = gate.maxNudges(),
        )
        if (!due) return
        try {
            val am = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = PendingIntent.getBroadcast(
                appContext, PI_RC,
                Intent(ACTION_NUDGE_CHECK).setPackage(appContext.packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            // 缺精确闹钟权限时降级非精确（Doze 下可能推迟，错过窗口则本轮不追，下轮开口重挂）
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAt, pi)
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAt, pi)
            }
            Log.i(TAG, "nudge check armed at +${CHECK_DELAY_MS / 1000}s (nudges used=${gate.nudgeCount()})")
        } catch (e: Exception) {
            Log.w(TAG, "arm nudge check failed: ${e.message}")
        }
    }
}

/**
 * 追击检查点闹钟入口：自身不干活，把检查请求投递给常驻保活服务（WakeLock 托底），
 * 与 [IdleCheckReceiver] 同一三级投递模式。
 */
class NudgeCheckReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != CompanionNudge.ACTION_NUDGE_CHECK) return
        val appContext = context.applicationContext
        val serviceIntent = Intent(appContext, com.rokidlab.phone.keepalive.LabKeepAliveService::class.java).apply {
            action = com.rokidlab.phone.keepalive.LabKeepAliveService.ACTION_NUDGE_CHECK
        }
        try {
            context.startService(serviceIntent)
            return
        } catch (e: Exception) {
            Log.i(TAG, "startService failed, try foreground: ${e.message}")
        }
        try {
            ContextCompat.startForegroundService(context, serviceIntent)
        } catch (e: Exception) {
            Log.w(TAG, "foreground start denied, run in-process fallback: ${e.message}")
            CompanionNudge.onNudgeCheck(appContext)
        }
    }

    companion object {
        private const val TAG = "NudgeCheckReceiver"
    }
}
