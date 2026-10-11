package com.rokidlab.phone.ai.tools

import android.content.Context
import com.rokidlab.phone.R
import com.rokidlab.phone.ai.ToolRegistry
import com.rokidlab.phone.ai.ToolRisk
import com.rokidlab.phone.proactive.GOAL_STATUS_DONE
import com.rokidlab.phone.proactive.Goal
import com.rokidlab.phone.proactive.GoalPolicy
import com.rokidlab.phone.proactive.GoalStore
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * GoalToolProvider —— 目标管理域（立目标 / 推进 / 列表）。
 *
 * 「Goal 目标对象」的对话管理入口：Muse 式长期目标的建/改/查全部经这三个工具，
 * 无独立 UI 面板（v1 口径）。定期回访由 proactive 包的 GoalCheck 挂自检链完成 ——
 * 工具只负责把目标写进 [GoalStore]，写入即被回访扫描覆盖，两条链路经存储解耦。
 */
internal object GoalToolProvider : ToolProvider {
    private const val TAG = "GoalToolProvider"

    override val toolNames = setOf(
        "create_goal",
        "update_goal",
        "list_goals",
    )

    private val dateFmt = DateTimeFormatter.ofPattern("M月d日")
    private val zone: ZoneId = ZoneId.systemDefault()

    override fun tools(): List<ToolEntry> = listOf(
        ToolEntry(
            name = "create_goal",
            group = ToolRegistry.DOMAIN_GOAL,
            displayNameRes = R.string.ai_tool_create_goal_name,
            descriptionRes = R.string.ai_tool_create_goal_desc,
            risk = ToolRisk.LOCAL_SIDE_EFFECT,
            sideEffect = true,
            statusText = "正在立目标…",
            schema = toolSchema(
                name = "create_goal",
                description = "帮用户立一个**长期目标**并跟踪进展：用户说「我想坚持每天背单词」「帮我盯着我月底前写完论文」「我要在双十一前减 5 斤」这类**要持续推进的事**时调用（区别于 manage_timer 的单次提醒 —— 那是到点响一次，这是要一直盯着的）。可拆解步骤、设截止日期；创建后到检查点我会主动回访进展。用户当前的目标可先调 list_goals 查看，进行中的目标最多 10 个。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "title" to mapOf("type" to "string", "description" to "目标简短标题，如「一个月内跑完 10 公里」"),
                        "detail" to mapOf("type" to "string", "description" to "目标的背景或为什么想做（可选），如「体检报告建议加强锻炼」"),
                        "steps" to mapOf(
                            "type" to "array",
                            "items" to mapOf("type" to "string"),
                            "description" to "计划拆解成的步骤列表（可选），从第一步到最后一步按顺序给出，如 [「第 1 周隔天慢跑 2 公里」「第 4 周连续跑 5 公里」…]",
                        ),
                        "deadline" to mapOf("type" to "string", "description" to "截止日期 YYYY-MM-DD（可选）。⚠️ 用户说「月底前」「下周五」时必须换算成具体日期再传；没有明确期限就不传"),
                        "next_check_minutes" to mapOf("type" to "integer", "description" to "多少分钟后回访一次进展（可选；用户说「今晚再问我」「一小时后提醒我汇报」时传）。不传则只在截止日临近时回访"),
                    ),
                    "required" to listOf("title"),
                ),
            ),
        ),
        ToolEntry(
            name = "update_goal",
            group = ToolRegistry.DOMAIN_GOAL,
            displayNameRes = R.string.ai_tool_update_goal_name,
            descriptionRes = R.string.ai_tool_update_goal_desc,
            risk = ToolRisk.LOCAL_SIDE_EFFECT,
            sideEffect = true,
            statusText = "正在更新目标…",
            schema = toolSchema(
                name = "update_goal",
                description = "推进一个已有目标：完成某一步（用户说「我今天跑了 3 公里」「第一步做完了」）、追加步骤、设下次回访时间（「今晚 8 点再问我」）、整个目标达成或放弃。goal_id 可先调 list_goals 获取（id 或目标标题都能匹配）。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "goal_id" to mapOf("type" to "string", "description" to "目标 id（list_goals 返回的）或目标标题"),
                        "action" to mapOf(
                            "type" to "string",
                            "enum" to listOf("add_step", "complete_step", "set_next_check", "complete", "drop"),
                            "description" to "add_step=追加一步；complete_step=完成某一步；set_next_check=设下次回访时间；complete=整个目标达成；drop=放弃该目标",
                        ),
                        "step" to mapOf("type" to "string", "description" to "action=add_step 时：新步骤内容；action=complete_step 时：步骤序号（1 起）或步骤标题（list_goals 里可看到）"),
                        "minutes" to mapOf("type" to "integer", "description" to "action=set_next_check 时：多少分钟后回访进展（5 分钟 ~ 7 天之间）"),
                    ),
                    "required" to listOf("goal_id", "action"),
                ),
            ),
        ),
        ToolEntry(
            name = "list_goals",
            group = ToolRegistry.DOMAIN_GOAL,
            displayNameRes = R.string.ai_tool_list_goals_name,
            descriptionRes = R.string.ai_tool_list_goals_desc,
            risk = ToolRisk.READ_ONLY,
            statusText = "正在查看目标…",
            schema = toolSchema(
                name = "list_goals",
                description = "列出用户的所有目标与每个目标的步骤进度（含进行中/已达成/已放弃）。用户问「我的目标进展如何」「我有哪些目标」「减重那个目标还有几步」时调用。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf<String, Any>(),
                ),
            ),
        ),
    )

    override fun execute(context: Context, name: String, args: JSONObject): String {
        return when (name) {
            "create_goal" -> executeCreate(context, args)
            "update_goal" -> executeUpdate(context, args)
            "list_goals" -> executeList(context)
            else -> throw IllegalArgumentException("未知工具: $name")
        }
    }

    // ═══════════════════ create_goal ═══════════════════

    private fun executeCreate(context: Context, args: JSONObject): String {
        val title = args.optString("title").trim()
        if (title.isEmpty()) return "请先说明目标是什么（title 不能为空）"
        val detail = args.optString("detail").trim()

        val steps = mutableListOf<String>()
        args.optJSONArray("steps")?.let { arr ->
            for (i in 0 until arr.length()) {
                val s = arr.optString(i).trim()
                if (s.isNotEmpty()) steps.add(s)
            }
        }

        val deadlineMs: Long?
        val nextCheckMs: Long?
        when (val d = parseDeadlineParam(args)) {
            is DeadlineParse.Err -> return d.msg
            is DeadlineParse.Ok -> deadlineMs = d.ms
        }
        nextCheckMs = parseNextCheckParam(args)

        val created = GoalStore.get(context).create(
            title = title,
            detail = detail,
            stepTitles = steps,
            deadlineMs = deadlineMs,
            nextCheckMs = nextCheckMs,
        ) ?: return "进行中的目标已经有 ${GoalPolicy.MAX_ACTIVE_GOALS} 个了，先专注完成手头的" +
            "（可以说「放弃某某目标」腾位置），或者过一阵再立新目标"

        return buildString {
            append("已立下目标「${created.title}」")
            if (created.steps.isNotEmpty()) {
                append("，拆成了 ${created.steps.size} 步：")
                append(created.steps.mapIndexed { i, s -> "${i + 1}. ${s.title}" }.joinToString("；"))
            }
            created.deadlineMs?.let { append("，截止 ${fmtDate(it)}") }
            append("。到检查点我会主动问你进展")
        }
    }

    /** `deadline` 参数（可选，YYYY-MM-DD）的解析结果 */
    private sealed class DeadlineParse {
        /** [ms] 为截止时刻（当天 23:59:59）；null = 未传 deadline */
        data class Ok(val ms: Long?) : DeadlineParse()
        data class Err(val msg: String) : DeadlineParse()
    }

    /**
     * 截止日 → 当天 23:59:59 的毫秒值：「10月20日前完成」按当天**整天**算；
     * 已过的日期返回 [DeadlineParse.Err] 让模型向用户确认（宁可回炉，不排错天）。
     */
    private fun parseDeadlineParam(args: JSONObject): DeadlineParse {
        val raw = args.optString("deadline").trim()
        if (raw.isEmpty()) return DeadlineParse.Ok(null)
        val today = LocalDate.now(zone).toEpochDay()
        return when (val r = GoalPolicy.parseDeadline(raw, today)) {
            is GoalPolicy.DeadlineResult.Ok -> DeadlineParse.Ok(
                LocalDate.ofEpochDay(r.epochDay).atTime(23, 59, 59).atZone(zone).toInstant().toEpochMilli(),
            )
            is GoalPolicy.DeadlineResult.BadFormat -> DeadlineParse.Err(
                "deadline 格式应为 YYYY-MM-DD，收到的「${r.raw}」无法识别",
            )
            is GoalPolicy.DeadlineResult.Past -> DeadlineParse.Err(
                "deadline「${r.raw}」已经过去了。请和用户确认真实期限（若说的是「下周」，请换算成具体日期）",
            )
        }
    }

    /** `next_check_minutes` 参数（可选）→ 检查点毫秒值（夹取范围后的） */
    private fun parseNextCheckParam(args: JSONObject): Long? {
        val minutes = args.optLong("next_check_minutes", 0L)
        if (minutes <= 0) return null
        return GoalPolicy.nextCheckFromMinutes(minutes, System.currentTimeMillis())
    }

    // ═══════════════════ update_goal ═══════════════════

    private fun executeUpdate(context: Context, args: JSONObject): String {
        val key = args.optString("goal_id").trim()
        if (key.isEmpty()) return "请说明要更新哪个目标（可先调 list_goals 查看）"
        val action = GoalPolicy.UpdateAction.of(args.optString("action"))
            ?: return "action 只支持 add_step / complete_step / set_next_check / complete / drop"
        val store = GoalStore.get(context)
        val goal = resolveGoal(store, key)
            ?: return "没有找到「$key」对应的目标。当前目标：${
                store.active().joinToString("、") { it.title }.ifEmpty { "（无）" }
            }"

        val now = System.currentTimeMillis()
        return when (action) {
            GoalPolicy.UpdateAction.ADD_STEP -> {
                val step = args.optString("step").trim()
                if (step.isEmpty()) return "请说明要追加的步骤内容（step）"
                val updated = store.addStep(goal.id, step, now)
                    ?: return "「${goal.title}」的步骤已经有 ${GoalPolicy.MAX_STEPS} 个了，先专注眼前的几步"
                "已给「${updated.title}」追加第 ${updated.steps.size} 步：$step"
            }

            GoalPolicy.UpdateAction.COMPLETE_STEP -> {
                val stepKey = args.optString("step").trim()
                if (stepKey.isEmpty()) return "请说明完成了哪一步（step 传步骤序号或标题）"
                val result = store.completeStep(goal.id, stepKey, now)
                    ?: return "「${goal.title}」里没找到步骤「$stepKey」。现有步骤：" +
                        goal.steps.mapIndexed { i, s -> "${i + 1}. ${s.title}" }.joinToString("；").ifEmpty { "（未拆解步骤）" }
                val (updated, changed) = result
                if (!changed) return "这一步之前已经完成了"
                if (updated.status == GOAL_STATUS_DONE) {
                    "「${updated.title}」的步骤全部完成，目标达成！"
                } else {
                    "已完成「${updated.title}」的一步，进度 ${GoalPolicy.progressText(updated)}。下一步：${
                        GoalPolicy.nextPendingStep(updated) ?: "（无）"
                    }"
                }
            }

            GoalPolicy.UpdateAction.SET_NEXT_CHECK -> {
                val minutes = args.optLong("minutes", 0L)
                val whenMs = GoalPolicy.nextCheckFromMinutes(minutes, now)
                    ?: return "请说明多少分钟后回访（minutes 需大于 0）"
                store.setNextCheck(goal.id, whenMs, now)
                "好，${fmtWhen(whenMs)}我会主动问你「${goal.title}」的进展"
            }

            GoalPolicy.UpdateAction.COMPLETE -> {
                store.markDone(goal.id, now)
                "已达成目标「${goal.title}」。为你高兴"
            }

            GoalPolicy.UpdateAction.DROP -> {
                store.markDropped(goal.id, now)
                "已放弃目标「${goal.title}」，之后不会再回访它了"
            }
        }
    }

    // ═══════════════════ list_goals ═══════════════════

    private fun executeList(context: Context): String {
        val goals = GoalStore.get(context).all()
        if (goals.isEmpty()) {
            return "还没有立过目标。用户想坚持做什么的时候说一句就行，比如「帮我盯着每天背单词」"
        }
        val active = goals.filter { it.isActive() }
        val closed = goals.filterNot { it.isActive() }
        if (active.isEmpty()) {
            return "当前没有进行中的目标" +
                (if (closed.isNotEmpty()) "（历史：${closed.joinToString("、") { it.title }}）" else "")
        }
        return buildString {
            append("进行中的目标 ${active.size} 个：\n")
            active.forEach { g ->
                append("· 「${g.title}」").append(GoalPolicy.progressText(g))
                g.deadlineMs?.let { append("，截止 ${fmtDate(it)}") }
                if (g.detail.isNotEmpty()) append("（${g.detail}）")
                append('\n')
                g.steps.forEachIndexed { i, s ->
                    append("  ").append(if (s.done) "✓" else "·").append(" ").append(i + 1).append(". ")
                    append(s.title).append('\n')
                }
            }
            if (closed.isNotEmpty()) append("历史目标：${closed.joinToString("、") { it.title }}")
        }.trimEnd()
    }

    // ═══════════════════ 共用 ═══════════════════

    /** goal_id 参数的目标解析：id 精确 → 标题精确 → 标题包含（双向）；已结束目标也可查（能问进展） */
    private fun resolveGoal(store: GoalStore, key: String): Goal? {
        val goals = store.all()
        return goals.firstOrNull { it.id == key }
            ?: goals.firstOrNull { it.title == key }
            ?: goals.firstOrNull { it.title.contains(key) || key.contains(it.title) }
    }

    private fun fmtDate(ms: Long): String =
        Instant.ofEpochMilli(ms).atZone(zone).format(dateFmt)

    /** 检查点时刻的人话：「25 分钟后」「今天 20:30」「10月15日 08:00」 */
    private fun fmtWhen(whenMs: Long): String {
        val diffMin = (whenMs - System.currentTimeMillis()) / 60_000L
        val dt = Instant.ofEpochMilli(whenMs).atZone(zone)
        val hm = String.format(java.util.Locale.CHINA, "%02d:%02d", dt.hour, dt.minute)
        return when {
            diffMin < 60 -> "${diffMin.coerceAtLeast(1)} 分钟后"
            dt.toLocalDate() == java.time.LocalDate.now(zone) -> "今天 $hm"
            else -> "${dt.monthValue}月${dt.dayOfMonth}日 $hm"
        }
    }
}
