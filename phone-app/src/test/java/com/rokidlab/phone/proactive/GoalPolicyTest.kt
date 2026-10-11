package com.rokidlab.phone.proactive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * [GoalPolicy] 单测。
 *
 * 重点判据：
 *  - 回访扫描（needsCheck）不误报也不漏报 —— 漏报=目标被遗忘，误报=没到点也催；
 *  - 步骤匹配（resolveStepKey）不把含数字的标题误判成序号；
 *  - 截止日解析拒绝过去的日子（排错天的回访比不提醒更伤信任）。
 */
class GoalPolicyTest {

    private val now = 1_760_000_000_000L // 固定「现在」，2025-10-09 前后

    private fun goal(
        status: String = GOAL_STATUS_ACTIVE,
        nextCheckMs: Long? = null,
        deadlineMs: Long? = null,
        steps: List<GoalStep> = emptyList(),
    ) = Goal(
        id = "g1",
        title = "跑完 10 公里",
        steps = steps,
        deadlineMs = deadlineMs,
        nextCheckMs = nextCheckMs,
        status = status,
        createdAtMs = now - 1000,
        updatedAtMs = now - 1000,
    )

    // ── needsCheck / dueGoals ──

    @Test
    fun `检查点到期即需回访`() {
        assertTrue(GoalPolicy.needsCheck(GOAL_STATUS_ACTIVE, nextCheckMs = now - 1, deadlineMs = null, nowMs = now))
        assertTrue(GoalPolicy.needsCheck(GOAL_STATUS_ACTIVE, nextCheckMs = now, deadlineMs = null, nowMs = now))
    }

    @Test
    fun `检查点未到不回访`() {
        assertFalse(
            GoalPolicy.needsCheck(GOAL_STATUS_ACTIVE, nextCheckMs = now + 1, deadlineMs = null, nowMs = now),
        )
    }

    @Test
    fun `截止日进入24小时窗口即需回访`() {
        val deadline = now + GoalPolicy.DEADLINE_SOON_MS
        assertTrue(GoalPolicy.needsCheck(GOAL_STATUS_ACTIVE, null, deadline, now))
        // 窗口边缘外 1ms 不报
        assertFalse(
            GoalPolicy.needsCheck(GOAL_STATUS_ACTIVE, null, now + GoalPolicy.DEADLINE_SOON_MS + 1, now),
        )
    }

    @Test
    fun `截止日已过仍需回访直到了结`() {
        // 过期目标持续出现在扫描里：要么补完成、要么放弃，不能静默消失
        assertTrue(GoalPolicy.needsCheck(GOAL_STATUS_ACTIVE, null, now - 1, now))
    }

    @Test
    fun `已完成与已放弃的目标永不回访`() {
        val overdue = now - 10 * GoalPolicy.DEADLINE_SOON_MS
        assertFalse(GoalPolicy.needsCheck(GOAL_STATUS_DONE, null, overdue, now))
        assertFalse(GoalPolicy.needsCheck(GOAL_STATUS_DROPPED, null, overdue, now))
        assertFalse(GoalPolicy.needsCheck(GOAL_STATUS_DONE, now - 1, overdue, now))
    }

    @Test
    fun `dueGoals按创建时间升序且只含活跃目标`() {
        val old = goal(nextCheckMs = now - 1).copy(id = "old", createdAtMs = now - 2000)
        val new = goal(nextCheckMs = now - 1).copy(id = "new", createdAtMs = now - 500)
        val done = goal(status = GOAL_STATUS_DONE, nextCheckMs = now - 1).copy(id = "done")
        val future = goal(nextCheckMs = now + 999_999).copy(id = "future")
        val due = GoalPolicy.dueGoals(listOf(new, done, future, old), now)
        assertEquals(listOf("old", "new"), due.map { it.id })
    }

    // ── progressText / nextPendingStep ──

    @Test
    fun `进度文案`() {
        val steps = listOf(GoalStep("s1", "a", true), GoalStep("s2", "b", false), GoalStep("s3", "c", false))
        assertEquals("1/3 步", GoalPolicy.progressText(goal(steps = steps)))
        assertEquals("未拆解步骤", GoalPolicy.progressText(goal()))
    }

    @Test
    fun `下一步待办取第一个未完成步骤`() {
        val steps = listOf(GoalStep("s1", "a", true), GoalStep("s2", "b", false))
        assertEquals("b", GoalPolicy.nextPendingStep(goal(steps = steps)))
        assertNull(GoalPolicy.nextPendingStep(goal()))
        val allDone = listOf(GoalStep("s1", "a", true))
        assertNull(GoalPolicy.nextPendingStep(goal(steps = allDone)))
    }

    // ── UpdateAction ──

    @Test
    fun `更新动作解析兼容大小写与空白`() {
        assertEquals(GoalPolicy.UpdateAction.ADD_STEP, GoalPolicy.UpdateAction.of(" add_step "))
        assertEquals(GoalPolicy.UpdateAction.COMPLETE, GoalPolicy.UpdateAction.of("COMPLETE"))
        assertNull(GoalPolicy.UpdateAction.of("delete"))
        assertNull(GoalPolicy.UpdateAction.of(null))
    }

    // ── resolveStepKey ──

    private val steps = listOf(
        GoalStep("s1", "第 1 周隔天慢跑 2 公里"),
        GoalStep("s2", "第 4 周连续跑 5 公里"),
        GoalStep("s3", "赛前一周减量"),
    )

    @Test
    fun `步骤匹配_纯序号`() {
        assertEquals(0, GoalPolicy.resolveStepKey(steps, "1"))
        assertEquals(1, GoalPolicy.resolveStepKey(steps, "2"))
        assertEquals(2, GoalPolicy.resolveStepKey(steps, "3"))
    }

    @Test
    fun `步骤匹配_带修饰的序号`() {
        assertEquals(1, GoalPolicy.resolveStepKey(steps, "第 2 步"))
        assertEquals(2, GoalPolicy.resolveStepKey(steps, "3."))
        assertEquals(0, GoalPolicy.resolveStepKey(steps, "第1"))
    }

    @Test
    fun `步骤匹配_标题精确与包含`() {
        assertEquals(2, GoalPolicy.resolveStepKey(steps, "赛前一周减量"))
        assertEquals(2, GoalPolicy.resolveStepKey(steps, "减量"))
        assertEquals(0, GoalPolicy.resolveStepKey(steps, "慢跑"))
    }

    @Test
    fun `含数字的标题不被误判成序号`() {
        // "2 公里跑步" 含数字但整体不是序号 —— 必须走标题匹配而不是落到第 2 步
        assertEquals(0, GoalPolicy.resolveStepKey(steps, "2 公里"))
    }

    @Test
    fun `步骤匹配_越界与未匹配返回null`() {
        assertNull(GoalPolicy.resolveStepKey(steps, "5"))
        assertNull(GoalPolicy.resolveStepKey(steps, "0"))
        assertNull(GoalPolicy.resolveStepKey(steps, "不存在的步骤"))
        assertNull(GoalPolicy.resolveStepKey(steps, ""))
    }

    // ── nextCheckFromMinutes ──

    @Test
    fun `检查点分钟夹取范围`() {
        assertNull(GoalPolicy.nextCheckFromMinutes(0, now))
        assertNull(GoalPolicy.nextCheckFromMinutes(-5, now))
        // 低于下限 5 分钟 → 抬到下限
        assertEquals(now + GoalPolicy.MIN_NEXT_CHECK_MS, GoalPolicy.nextCheckFromMinutes(1, now))
        // 高于上限 7 天 → 压到上限
        assertEquals(now + GoalPolicy.MAX_NEXT_CHECK_MS, GoalPolicy.nextCheckFromMinutes(30L * 24 * 60, now))
        // 正常值原样
        assertEquals(now + 60 * 60_000L, GoalPolicy.nextCheckFromMinutes(60, now))
    }

    // ── parseDeadline ──

    private val zone = ZoneId.systemDefault()
    private val today: Long = LocalDate.of(2026, 10, 11).toEpochDay()

    @Test
    fun `截止日期解析合法日期`() {
        val r = GoalPolicy.parseDeadline("2026-10-20", today)
        assertTrue(r is GoalPolicy.DeadlineResult.Ok)
        assertEquals(LocalDate.of(2026, 10, 20).toEpochDay(), (r as GoalPolicy.DeadlineResult.Ok).epochDay)
    }

    @Test
    fun `截止日期解析拒绝过去的日子`() {
        val r = GoalPolicy.parseDeadline("2026-10-01", today)
        assertTrue(r is GoalPolicy.DeadlineResult.Past)
        assertEquals("2026-10-01", (r as GoalPolicy.DeadlineResult.Past).raw)
    }

    @Test
    fun `截止日期解析拒绝非法格式`() {
        // 严格解析：2026-13-45 不存在，必须报错而不是滚到下一年
        assertTrue(GoalPolicy.parseDeadline("2026-13-45", today) is GoalPolicy.DeadlineResult.BadFormat)
        assertTrue(GoalPolicy.parseDeadline("下周", today) is GoalPolicy.DeadlineResult.BadFormat)
        assertTrue(GoalPolicy.parseDeadline("", today) is GoalPolicy.DeadlineResult.BadFormat)
    }

    // ── nextCheckAfterSpeaking ──

    @Test
    fun `播报后_已到的显式检查点被消费`() {
        assertNull(GoalPolicy.nextCheckAfterSpeaking(now - 1, null, now))
        assertNull(GoalPolicy.nextCheckAfterSpeaking(now, now + GoalPolicy.DEADLINE_SOON_MS, now))
    }

    @Test
    fun `播报后_截止窗口触发限频推进`() {
        val deadline = now + GoalPolicy.DEADLINE_SOON_MS
        assertEquals(
            now + GoalPolicy.DEADLINE_RECHECK_MS,
            GoalPolicy.nextCheckAfterSpeaking(null, deadline, now),
        )
    }

    @Test
    fun `播报后_未来的检查点不被截止窗口覆盖`() {
        // 用户约的「今晚 8 点再问我」优先于截止窗口限频
        val future = now + 60 * 60_000L
        assertEquals(
            future,
            GoalPolicy.nextCheckAfterSpeaking(future, now + GoalPolicy.DEADLINE_SOON_MS, now),
        )
    }

    @Test
    fun `播报后_无截止无检查点的兜底清掉`() {
        assertNull(GoalPolicy.nextCheckAfterSpeaking(null, null, now))
    }

    // ── sanitizeText ──

    @Test
    fun `文本清洗剥控制字符并截断`() {
        assertEquals("背单词 每天", GoalPolicy.sanitizeText("背单词\n每天\t", 60))
        assertEquals("abc", GoalPolicy.sanitizeText("abcdef", 3))
    }
}
