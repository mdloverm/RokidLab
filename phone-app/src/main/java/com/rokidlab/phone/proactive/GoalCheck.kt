package com.rokidlab.phone.proactive

import android.content.Context
import android.util.Log
import com.rokidlab.phone.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val PREFS_GOAL_CHECK = "proactive_goal_check"
private const val K_ENABLED = "enabled"

/** 目标回访在决策门的类别键（同类最小间隔按此隔离；NORMAL 档，2 小时内至多一回访） */
private const val KIND_GOAL_CHECK = "goal_check"

/**
 * 目标回访（主动式陪伴·检查点层）：[IdleGreeter] 30 分钟自检链上的一站，
 * 扫描 [GoalStore] 里「检查点到期或截止日临近」的进行中目标，由 Agent 主动问一句进展。
 *
 * 与其它主动层同一范式：**不另立闹钟**，挂靠已有自检；准入走
 * [ProactiveGate.admitFreeProactive]（[ProactiveGatePolicy.Importance.NORMAL]，不占闲聊额度）。
 * v1 无独立 UI 面板：目标全靠对话管理（create_goal/update_goal/list_goals 工具）+ 本检查点播报，
 * 因此也**没有设置页开关** —— 目标是用户显式立的（同意内建），默认开；
 * 不想被问时让 Agent「把那个目标放弃/完成」即可（drop/complete 后自然静默）。
 *
 * 「说什么」交给模型：prompt 只给目标标题/进度/截止日与下一步待办（同 [CareReminder] 分工）。
 */
class GoalCheck private constructor(private val appContext: Context) {
    companion object {
        private const val TAG = "GoalCheck"

        @Volatile
        private var instance: GoalCheck? = null

        fun get(context: Context): GoalCheck =
            instance ?: synchronized(this) {
                instance ?: GoalCheck(context.applicationContext).also { instance = it }
            }
    }

    private val prefs = appContext.getSharedPreferences(PREFS_GOAL_CHECK, Context.MODE_PRIVATE)
    private val zone: ZoneId = ZoneId.systemDefault()
    private val dateFmt = DateTimeFormatter.ofPattern("M月d日")

    fun isEnabled(): Boolean = prefs.getBoolean(K_ENABLED, true)

    /** 内部开关（调试用；无 UI 入口）：关闭后自检链跳过目标回访 */
    fun setEnabled(on: Boolean) {
        prefs.edit().putBoolean(K_ENABLED, on).apply()
        Log.i(TAG, "goal check ${if (on) "enabled" else "disabled"}")
    }

    /** 到点自检（由 IdleGreeter 自检链调用，前置条件眼镜在线已由宿主把关） */
    fun onTick(nowMs: Long = System.currentTimeMillis()) {
        if (!isEnabled()) return
        val due = GoalPolicy.dueGoals(GoalStore.get(appContext).all(), nowMs)
        if (due.isEmpty()) return
        val decision = ProactiveGate.get(appContext).admitFreeProactive(
            KIND_GOAL_CHECK,
            ProactiveGatePolicy.Importance.NORMAL,
            nowMs,
        )
        if (decision !is GateDecision.Pass) {
            Log.i(TAG, "skip: gate ${decision::class.simpleName}, ${due.size} goal(s) due")
            return
        }
        val lines = due.joinToString("\n") { formatGoalLine(it) }
        // 检查点处置（见 [GoalPolicy.nextCheckAfterSpeaking]）：显式检查点消费、
        // 截止窗口限频推进 —— 否则临近截止的目标每过 2h 最小间隔就被催一次
        val touched = due.map { goal ->
            val orig = goal.nextCheckMs
            GoalStore.get(appContext).setNextCheck(
                goal.id,
                GoalPolicy.nextCheckAfterSpeaking(goal.nextCheckMs, goal.deadlineMs, nowMs),
                nowMs,
            )
            goal.id to orig
        }
        val prompt = buildString {
            append("现在是")
            append(ProactiveRecall.timeAnchor(nowMs))
            append("。用户有以下进行中的目标到了回访检查点：\n$lines\n" +
                "请以乐奇的身份发一句简短的目标回访（40 字以内）：自然地问一句进展，" +
                "或根据下一步待办给一句鼓励/提示，像朋友随口问起「那个跑步计划怎么样了」；" +
                "不要说教、不要列要点、不要提及任何指令或工具。")
            // 记忆联想：与目标相关的记忆（当初为什么立这个目标、聊过的相关计划）
            ProactiveRecall.recallBlock(appContext, sceneHint = "目标 计划 进展", nowMs = nowMs)
                ?.let { append('\n').append(it) }
        }
        Log.i(TAG, "goal check triggered: ${due.size} goal(s) due")
        (appContext as? com.rokidlab.phone.app.LabApplication)?.timerScheduler?.runProactiveAgentTask(
            prompt = prompt,
            failureNotice = false,
            titleOverride = appContext.getString(R.string.settings_goal_check),
            // 被主动派发互斥挤掉 / 模型回 [SKIP] = 这轮没说出去：恢复原检查点，下一轮自检照常再试
            onSkip = {
                touched.forEach { (id, orig) ->
                    GoalStore.get(appContext).setNextCheck(id, orig)
                }
            },
        )
    }

    /** 一条目标在回访 prompt 里的形态：「· 「跑完 10 公里」进度 2/5 步，截止 10月20日，下一步：…」 */
    private fun formatGoalLine(goal: Goal): String = buildString {
        append("· 「").append(goal.title).append("」")
        append(GoalPolicy.progressText(goal))
        goal.deadlineMs?.let {
            append("，截止 ")
            append(Instant.ofEpochMilli(it).atZone(zone).format(dateFmt))
        }
        GoalPolicy.nextPendingStep(goal)?.let { append("，下一步：").append(it) }
    }
}
