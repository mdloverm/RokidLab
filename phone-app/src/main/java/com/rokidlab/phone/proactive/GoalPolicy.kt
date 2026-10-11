package com.rokidlab.phone.proactive

/**
 * 目标对象的**纯判定层**（无 Android 依赖，可 JVM 单测）：
 * 哪个目标这轮自检该回访、更新动作的合法性、截止日期解析与步骤匹配。
 * 说什么（回访话术）一律交给模型 —— 与 [CareReminder]/[NotificationDigestPolicy] 同一分工。
 */
object GoalPolicy {

    /**
     * 截止日「临近窗口」：deadline 落在 24h 内（或已过）且目标未完成 → 纳入回访扫描。
     * 与 [NotificationDigestPolicy.MAX_AGE_MS] 同一取值思路：一天一次的节奏足够，
     * 更频繁就成催债了（真正的节奏由决策门同类最小间隔再兜一层）。
     */
    const val DEADLINE_SOON_MS = 24 * 60 * 60 * 1000L

    /** 进行中目标数量上限：目标多了回访轮不过来，且「什么都想盯」等于什么都不盯 */
    const val MAX_ACTIVE_GOALS = 10

    /** 单个目标的步骤数上限（防模型一次拆 50 步把 prompt 撑爆） */
    const val MAX_STEPS = 20

    /** 目标标题 / 步骤标题 / 详情的长度上限（截断，不拒绝 —— 模型给的文本偶尔偏长） */
    const val MAX_TITLE_CHARS = 60
    const val MAX_STEP_TITLE_CHARS = 60
    const val MAX_DETAIL_CHARS = 300

    /** 下次回访的最小间隔（毫秒）：0 或更小视为「不设检查点」，由截止日窗口接管 */
    const val MIN_NEXT_CHECK_MS = 5 * 60 * 1000L

    /** 下次回访的最大间隔（毫秒）：7 天 —— 超过没有「检查点」的意义 */
    const val MAX_NEXT_CHECK_MS = 7 * 24 * 60 * 60 * 1000L

    /**
     * 截止窗口回访后的**限频间隔**（毫秒）：播报一次就推进，防止「截止日临近」的目标
     * 只靠同类最小间隔（2h）兜底 —— 那样一天最多被催 7 次「进展如何」。
     * 4h ≈ 一天至多 3 次，配合免打扰是可接受的催问密度。
     */
    const val DEADLINE_RECHECK_MS = 4 * 60 * 60 * 1000L

    /**
     * 回访播报发出后的检查点处置（纯函数，[GoalCheck] 调用）：
     *  - 显式检查点触发（nextCheckMs 已到）→ **消费**（返回 null）：用户约的那次问完了；
     *  - 截止窗口触发 → 限频推进（now + [DEADLINE_RECHECK_MS]），到期或下次窗口再问。
     *
     * 注意返回 null ≠ 永不再问：无 deadline 的目标消费检查点后静默（没有约定就不打扰），
     * 有 deadline 的仍会随窗口再次进入扫描。
     */
    fun nextCheckAfterSpeaking(
        nextCheckMs: Long?,
        deadlineMs: Long?,
        nowMs: Long,
        recheckMs: Long = DEADLINE_RECHECK_MS,
    ): Long? {
        if (nextCheckMs != null && nextCheckMs <= nowMs) return null
        // 未来检查点原样保留：deadline 窗口先触发回访时，不许冲掉用户约的「今晚 8 点再问我」
        if (nextCheckMs != null) return nextCheckMs
        if (deadlineMs == null) return null // 无截止也无到期检查点：不该出现在 due 里，兜底清掉
        return nowMs + recheckMs
    }

    /** 这一个目标此刻是否需要回访：显式检查点到期，或截止日在 24h 内（含已过期）且未完成 */
    fun needsCheck(
        status: String,
        nextCheckMs: Long?,
        deadlineMs: Long?,
        nowMs: Long,
        deadlineWindowMs: Long = DEADLINE_SOON_MS,
    ): Boolean {
        if (status != GOAL_STATUS_ACTIVE) return false
        if (nextCheckMs != null && nextCheckMs <= nowMs) return true
        if (deadlineMs != null && nowMs >= deadlineMs - deadlineWindowMs) return true
        return false
    }

    /** 批量版：按创建时间升序取本轮该回访的目标 */
    fun dueGoals(
        goals: List<Goal>,
        nowMs: Long,
        deadlineWindowMs: Long = DEADLINE_SOON_MS,
    ): List<Goal> = goals
        .filter { needsCheck(it.status, it.nextCheckMs, it.deadlineMs, nowMs, deadlineWindowMs) }
        .sortedBy { it.createdAtMs }

    /** 已完成步骤数 */
    fun doneSteps(goal: Goal): Int = goal.steps.count { it.done }

    /** 进度短句（回访 prompt 与 list_goals 工具结果共用）：「2/5 步」「5/5 步」「无步骤」 */
    fun progressText(goal: Goal): String {
        if (goal.steps.isEmpty()) return "未拆解步骤"
        return "${doneSteps(goal)}/${goal.steps.size} 步"
    }

    /** 下一步待办（null = 全部完成或未拆步骤），回访 prompt 用 */
    fun nextPendingStep(goal: Goal): String? =
        goal.steps.firstOrNull { !it.done }?.title

    /** 更新动作（`update_goal` 工具的 action 参数） */
    enum class UpdateAction(val id: String) {
        ADD_STEP("add_step"),
        COMPLETE_STEP("complete_step"),
        SET_NEXT_CHECK("set_next_check"),
        COMPLETE("complete"),
        DROP("drop");

        companion object {
            fun of(id: String?): UpdateAction? = entries.firstOrNull { it.id == id?.trim()?.lowercase() }
        }
    }

    /**
     * 步骤匹配（`complete_step` 的 step 参数）：整键形如序号（「1」「第 2 步」「3.」）时按序号（1 基），
     * 其次精确标题、再次包含匹配（双向）。返回 0 基下标；null = 匹配不到。
     *
     * ⚠️ 序号判定必须**整体匹配**而不是「剥数字」：`"2 公里跑步"` 这类含数字的标题
     * 若按剥数字解析会被误判成第 2 步 —— 序号只在整键形如序号时生效，其余走标题匹配。
     */
    fun resolveStepKey(steps: List<GoalStep>, rawKey: String): Int? {
        val key = rawKey.trim()
        if (key.isEmpty()) return null
        val indexMatch = Regex("^(?:第\\s*)?(\\d{1,2})(?:\\s*步|\\s*[.、,:：])?$").find(key)
        if (indexMatch != null) {
            // 整键形如序号：范围内按序号取，**越界直接 null** —— 不能落入标题模糊匹配，
            // 否则「5」会因某个标题里恰好含「5」而被错误命中（单测钉住此行为）
            val idx = indexMatch.groupValues[1].toInt() - 1
            return idx.takeIf { it in steps.indices }
        }
        steps.indexOfFirst { it.title == key }.takeIf { it >= 0 }?.let { return it }
        return steps.indexOfFirst { it.title.contains(key) || key.contains(it.title) }
            .takeIf { it >= 0 }
    }

    /** 下次回访时刻（毫秒）：夹在 [MIN_NEXT_CHECK_MS, MAX_NEXT_CHECK_MS] 之间；非法输入返回 null */
    fun nextCheckFromMinutes(minutes: Long, nowMs: Long): Long? {
        if (minutes <= 0) return null
        val ms = minutes * 60_000L
        if (ms < MIN_NEXT_CHECK_MS) return nowMs + MIN_NEXT_CHECK_MS
        if (ms > MAX_NEXT_CHECK_MS) return nowMs + MAX_NEXT_CHECK_MS
        return nowMs + ms
    }

    /** `deadline` 参数（YYYY-MM-DD）的解析结果 */
    sealed class DeadlineResult {
        /** [epochDay] 为目标日（当天 23:59:59 视为到期，由调用方换算毫秒） */
        data class Ok(val epochDay: Long) : DeadlineResult()

        /** 格式非法（非 YYYY-MM-DD 或不存在的日期） */
        data class BadFormat(val raw: String) : DeadlineResult()

        /** 日期已经过去 —— 宁可让模型改对，也不要把回访排在错误的那天 */
        data class Past(val raw: String) : DeadlineResult()
    }

    /**
     * 解析截止日期（⚠️ 调用方须在 `deadline` 参数为空串时跳过调用，本方法不处理空串）。
     * 与 `TimerToolProvider.parseOnceDate` 同一取向：
     * 用 `LocalDate.parse`（严格模式，`2026-13-45` 会失败而不是滚到下一年），
     * 已过的日期返回 [DeadlineResult.Past] 让模型向用户确认。
     */
    fun parseDeadline(raw: String, todayEpochDay: Long): DeadlineResult {
        val text = raw.trim()
        val date = runCatching { java.time.LocalDate.parse(text) }
            .getOrElse { return DeadlineResult.BadFormat(text) }
        val epochDay = date.toEpochDay()
        if (epochDay < todayEpochDay) return DeadlineResult.Past(text)
        return DeadlineResult.Ok(epochDay)
    }

    /** 清洗文本（剥控制字符 + 压空白 + 截断）：模型给的自由文本不破坏存储与 prompt 结构 */
    fun sanitizeText(raw: String, maxChars: Int): String =
        raw.replace(Regex("\\p{Cntrl}"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(maxChars)
}
