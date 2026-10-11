package com.rokidlab.phone.ai.tools

import com.rokidlab.phone.R
import com.rokidlab.phone.ai.ToolRisk

import android.content.Context
import android.util.Log
import com.rokidlab.phone.ai.AiuiAppRegistry
import com.rokidlab.phone.ai.AiuiProject
import com.rokidlab.phone.ai.Calculator
import com.rokidlab.phone.ai.KnowledgeBase
import com.rokidlab.phone.ai.KuwoMusicApi
import com.rokidlab.phone.ai.LocationTools
import com.rokidlab.phone.ai.MusicPlayerController
import com.rokidlab.phone.ai.PhoneTools
import com.rokidlab.phone.ai.ToolRegistry
import com.rokidlab.phone.ai.WeatherTools
import com.rokidlab.phone.ai.WebTools
import com.rokidlab.phone.app.LabApplication
import com.rokidlab.phone.adb.ui.TimerAction
import com.rokidlab.phone.adb.ui.TimerSchedule
import com.rokidlab.phone.adb.ui.TimerTask
import com.rokidlab.phone.permission.AppPermission
import com.rokidlab.phone.permission.PermissionBridge
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * TimerToolProvider —— 定时任务域（设置/列表/取消）。
 * Phase 4 从 `ToolRegistry.execute` 迁出的该域工具执行分支（逐字搬运，行为不变）。
 */
internal object TimerToolProvider : ToolProvider {
    private const val TAG = "TimerToolProvider"

    override val toolNames = setOf(
        "manage_timer",
        "schedule_agent_task",
    )

    override fun tools(): List<ToolEntry> = listOf(
        ToolEntry(
            name = "manage_timer",
            group = ToolRegistry.DOMAIN_TIMER,
            displayNameRes = R.string.ai_tool_manage_timer_name,
            descriptionRes = R.string.ai_tool_manage_timer_desc,
            risk = ToolRisk.LOCAL_SIDE_EFFECT,
            sideEffect = true,
            statusText = "正在处理定时任务…",
            schema = toolSchema(
                name = "manage_timer",
                description = "管理**眼镜端**的定时提醒 / 定时任务，一个工具管三种意图。action=\"create\"：创建（「5分钟后提醒我喝水」「明早8点叫我」「17点打开小智」）。action=\"list\"：列出全部（「我有哪些提醒」「几点有提醒」）。action=\"cancel\"：取消（「取消刚才的提醒」「删掉那个闹钟」；全部取消传 all=true）。⚠️ 让**手机响铃**（手机闹钟/倒计时）请用 set_phone_alarm；用户**没说哪一端时先问一句**「让手机响还是眼镜提醒你？」，不要默认任何一端。注意：到点要我自己查资料再汇报，或到点要**播放某首歌**的需求，请改用 schedule_agent_task —— 本工具到点只会念一句固定内容（或在眼镜上打开指定应用），放不了音乐。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "action" to mapOf("type" to "string", "enum" to listOf("create", "list", "cancel"), "description" to "create=创建；list=列出全部；cancel=取消"),
                        "schedule_type" to mapOf("type" to "string", "enum" to listOf("fixed", "countdown"), "description" to "action=create 时的时间口径：fixed=某个固定时刻（默认，需给 time）；countdown=相对时长（需给 seconds，用户说「5分钟后/半小时后」时用这个）"),
                        "time" to mapOf("type" to "string", "description" to "schedule_type=fixed（默认）时的时间，24 小时制 HH:mm，如 17:00"),
                        "seconds" to mapOf("type" to "integer", "description" to "schedule_type=countdown 时，距现在多少秒后触发（须 > 0，如「5分钟后」传 300）"),
                        "content" to mapOf("type" to "string", "description" to "action=create 时的提醒内容 / 任务名，如「喝水」「开会」"),
                        "repeatDaily" to mapOf("type" to "boolean", "description" to "action=create 时是否每天重复，默认 false；用户说「每天」时传 true"),
                        "timerAction" to mapOf("type" to "string", "enum" to listOf("notify", "launch"), "description" to "action=create 时到点做什么：notify=只提醒（默认）；launch=提醒并在眼镜上打开某应用（需同时给 appName）"),
                        "appName" to mapOf("type" to "string", "description" to "timerAction=launch 时要打开的眼镜应用名，如「小智」"),
                        "timerName" to mapOf("type" to "string", "description" to "action=cancel 时要取消的任务名称或提醒内容；不知道就先 action=list 查看"),
                        "all" to mapOf("type" to "boolean", "description" to "action=cancel 时用户要求全部取消传 true，默认 false"),
                    ),
                    "required" to listOf("action"),
                ),
            ),
        ),
        ToolEntry(
            name = "schedule_agent_task",
            group = ToolRegistry.DOMAIN_TIMER,
            displayNameRes = R.string.ai_tool_schedule_agent_task_name,
            descriptionRes = R.string.ai_tool_schedule_agent_task_desc,
            risk = ToolRisk.LOCAL_SIDE_EFFECT,
            sideEffect = true,
            statusText = "正在创建自主任务…",
            schema = toolSchema(
                name = "schedule_agent_task",
                description = "创建一个「自主任务」：到点后由我自己去查资料、再主动播报结果 —— 区别于 manage_timer 的 create 只会念一句固定文案。当用户说「每天早上帮我播报天气和日程」「晚上 10 点提醒我总结今天」「每周一提醒我看待办」，以及「X 点播放某首歌」「半小时后放首歌」这类**到点要真的放音乐**的需求时调用。注意：出于安全，自主任务执行时我只有查询类只读工具，外加一项手机上播放/停止音乐的能力（control_music），不会自动拨号、装机或改任何设置；其他写操作请在任务内容里让我先提醒你确认。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "name" to mapOf("type" to "string", "description" to "任务简短名称，如「早安简报」「睡前总结」"),
                        "prompt" to mapOf("type" to "string", "description" to "到点要执行的完整指令，写成一句自包含的中文，如「查一下杭州今天的天气和今天的日程，合成一段 80 字以内的早安播报」"),
                        "schedule_type" to mapOf("type" to "string", "enum" to listOf("fixed", "countdown", "interval"), "description" to "fixed=每天/某天的固定时刻（默认）；countdown=多少秒后执行一次；interval=每 N 秒执行一次，共 M 次"),
                        "hour" to mapOf("type" to "integer", "description" to "schedule_type=fixed 时的小时，24 小时制 0~23，如 8 表示早上 8 点"),
                        "minute" to mapOf("type" to "integer", "description" to "schedule_type=fixed 时的分钟 0~59，默认 0"),
                        "date" to mapOf("type" to "string", "description" to "schedule_type=fixed 且**只执行一次**时的指定日期，格式 YYYY-MM-DD（如 \"2026-10-16\" 表示 10 月 16 日那天触发一次）。⚠️ 用户提到**具体日期**的约定（「下周五交报告」「周五要面试」「下周二是我妈生日」）时必须填，否则任务会落到明天；只说「每天/每天早上」时才不填、走 repeatDaily"),
                        "repeat_daily" to mapOf("type" to "boolean", "description" to "schedule_type=fixed 时是否每天重复，默认 false（只执行一次）；用户说「每天」时传 true"),
                        "seconds" to mapOf("type" to "integer", "description" to "schedule_type=countdown/interval 时的间隔秒数（须 > 0）"),
                        "count" to mapOf("type" to "integer", "description" to "schedule_type=interval 时的重复次数，默认 1"),
                    ),
                    "required" to listOf("name", "prompt", "schedule_type"),
                ),
            ),
        ),
    )

    /**
     * 定时任务三合一（原 set_timer / list_timers / cancel_timer）。
     *
     * 合并动机：三者是同一批 `TimerTask` 的增/查/删，同一能力域；拆开后模型在
     * 「取消刚才那个提醒」时容易漏调 list 就直接 cancel 失败。
     *
     * 参数语义**逐字沿用旧实现**（`timerAction` = 旧 `set_timer` 的 `action`），
     * 只把外层意图换成 `action`：旧行为不是被重新设计，而是被换个入口调用。
     */
    override fun execute(context: Context, name: String, args: JSONObject): String {
        return when (name) {
            "manage_timer" -> when (args.optString("action").trim().lowercase()) {
                "list" -> {
                    val app = context.applicationContext as? LabApplication ?: return "应用上下文异常"
                    val tasks = app.timerScheduler.tasks
                    if (tasks.isEmpty()) {
                        "当前没有任何定时任务。需要设置时说「X分钟后提醒我…」即可"
                    } else {
                        "你共有 ${tasks.size} 个定时任务：\n" + tasks.mapIndexed { i, t ->
                            val sched = when (val s = t.schedule) {
                                is TimerSchedule.FixedTime -> {
                                    String.format(Locale.CHINA, "%02d:%02d", s.hour, s.minute) +
                                        if (s.repeatDaily) "（每天）" else ""
                                }
                                is TimerSchedule.Interval -> "每 ${s.seconds / 60} 分钟"
                                is TimerSchedule.Countdown -> "倒计时 ${s.seconds / 60} 分钟"
                            }
                            "[${i + 1}] ${t.name}｜$sched｜${if (t.running) "运行中" else "已暂停"}" +
                                // 自主任务（到点让 Agent 自己跑一轮）与普通提醒在列表里区分开，
                                // 否则模型会说"这是普通提醒"，用户也会以为到点只念一句话
                                if (t.actions.any { it is TimerAction.AgentPrompt }) "｜自主任务" else ""
                        }.joinToString("\n") + "\n如需取消，告诉我任务名称即可"
                    }
                }

                "cancel" -> {
                    val app = context.applicationContext as? LabApplication ?: return "应用上下文异常"
                    if (args.optBoolean("all", false)) {
                        val count = app.timerScheduler.tasks.size
                        app.timerScheduler.stopAll()
                        app.timerScheduler.tasks.toList().forEach { app.timerScheduler.deleteTask(it.id) }
                        return if (count > 0) "已取消全部 $count 个定时任务" else "当前没有可取消的定时任务"
                    }
                    val name = args.optString("timerName").trim()
                    if (name.isEmpty()) return "请提供要取消的任务名称（可先用 action=list 查看）"
                    val tasks = app.timerScheduler.tasks
                    // 名称精确 → 包含（双向）匹配
                    val target = tasks.firstOrNull { it.name == name }
                        ?: tasks.firstOrNull { it.name.contains(name) || name.contains(it.name) }
                        ?: return "没有找到名为「$name」的定时任务。当前任务：${
                            if (tasks.isEmpty()) "（无）" else tasks.joinToString("、") { it.name }
                        }"
                    app.timerScheduler.stopTask(target.id)
                    app.timerScheduler.deleteTask(target.id)
                    "已取消定时任务「${target.name}」"
                }

                else -> {
                    val content = args.optString("content").trim().ifBlank { "定时提醒" }
                    // 旧 set_timer 的 action 参数改名为 timerAction（外层 action 已被意图占用），
                    // 取值与语义不变：notify=只提醒 / launch=额外在眼镜上打开某应用
                    val timerAction = args.optString("timerAction", "notify")
                    val appName = args.optString("appName").trim()
                    val repeatDaily = args.optBoolean("repeatDaily", false)
                    val app = context.applicationContext as? LabApplication ?: return "应用上下文异常"

                    // ★ 调度口径（2026-10-06 审计修）：原先**只**认 HH:mm 且硬编码
                    // FixedTime，于是「5 分钟后提醒我」「半小时后叫我」这类高频句式
                    // 直接返回"时间格式不正确"—— 而本工具自己的 description 第一条
                    // 就举了这个例子。底层 [TimerSchedule.Countdown] 早就存在，
                    // 同文件的 schedule_agent_task 也已用 schedule_type 暴露过 countdown，
                    // 只有这里漏了。现在按 schedule_type 分派（默认 fixed，兼容旧调用）。
                    val scheduleType = args.optString("schedule_type", "fixed").ifBlank { "fixed" }
                    val schedule: TimerSchedule
                    val dayLabel: String
                    val timeLabel: String
                    when (scheduleType) {
                        "countdown" -> {
                            val seconds = args.optLong("seconds", 0L)
                            if (seconds <= 0L) {
                                return "相对时长必须大于 0 秒（如「5 分钟后」传 seconds=300）"
                            }
                            schedule = TimerSchedule.Countdown(seconds)
                            dayLabel = ""
                            timeLabel = "${formatSeconds(seconds)}后"
                        }

                        "fixed" -> {
                            val time = args.optString("time").trim()
                            val m = Regex("^(\\d{1,2}):(\\d{1,2})$").find(time)
                                ?: return "时间格式不正确：固定时刻请用 24 小时制 HH:mm（如 17:00）；" +
                                    "相对时长（如「5 分钟后」）请用 schedule_type=\"countdown\" 并传 seconds"
                            val hour = m.groupValues[1].toInt()
                            val minute = m.groupValues[2].toInt()
                            if (hour !in 0..23 || minute !in 0..59) return "时间超出范围（00:00 ~ 23:59）"
                            schedule = TimerSchedule.FixedTime(hour, minute, repeatDaily)
                            val now = Calendar.getInstance()
                            val todayTarget = Calendar.getInstance().apply {
                                set(Calendar.HOUR_OF_DAY, hour)
                                set(Calendar.MINUTE, minute)
                                set(Calendar.SECOND, 0)
                            }
                            dayLabel = when {
                                repeatDaily -> "每天"
                                todayTarget.timeInMillis > now.timeInMillis -> "今天"
                                else -> "明天"
                            }
                            timeLabel = String.format(Locale.CHINA, "%02d:%02d", hour, minute)
                        }

                        else -> return "schedule_type 只支持 fixed 或 countdown，收到「$scheduleType」"
                    }

                    val actions = mutableListOf<TimerAction>()
                    if (timerAction == "launch") synchronized(ToolRegistry.adbLock) {
                        val client = ToolRegistry.adbClient(context)
                            ?: return "眼镜 ADB 连接失败，无法创建打开应用的定时任务"
                        try {
                            val pkg = ToolRegistry.matchPackage(appName, client.listPackages(false), context)
                                ?: return "没有找到应用“$appName”，无法创建定时打开任务"
                            actions += TimerAction.LaunchApp(pkg)
                        } finally {
                            // 常驻复用共享 ADB 连接，不用后即断（原因见 get_glasses_status）
                        }
                    }
                    actions += TimerAction.TtsSpeak(content)
                    actions += TimerAction.SendNotification("Rokid 定时提醒", content)

                    val task = TimerTask(
                        id = UUID.randomUUID().toString(),
                        name = content,
                        schedule = schedule,
                        actions = actions,
                        running = true,
                    )
                    app.timerScheduler.addTask(task)
                    app.timerScheduler.startTask(task)
                    val actionLabel = if (timerAction == "launch") "并打开 $appName" else ""
                    // countdown 的 dayLabel 是空串，拼接时前面留一个空格
                    val when0 = (dayLabel + " " + timeLabel).trim()
                    "已设置定时任务：$when0 $actionLabel，提醒：$content" + exactAlarmHint(context)
                }
            }

            "schedule_agent_task" -> {
                val name = args.optString("name").trim().ifBlank { "自主任务" }
                val prompt = args.optString("prompt").trim()
                if (prompt.isEmpty()) return "请说明到点后要我做什么（prompt 不能为空）"
                val app = context.applicationContext as? LabApplication ?: return "应用上下文异常"

                val schedule: TimerSchedule
                val label: String
                when (args.optString("schedule_type", "fixed").trim().lowercase()) {
                    "countdown" -> {
                        val seconds = args.optLong("seconds", 0L)
                        if (seconds <= 0L) return "倒计时秒数必须大于 0"
                        schedule = TimerSchedule.Countdown(seconds)
                        label = "${formatSeconds(seconds)}后执行一次"
                    }
                    "interval" -> {
                        val seconds = args.optLong("seconds", 0L)
                        if (seconds <= 0L) return "间隔秒数必须大于 0"
                        val count = args.optInt("count", 1).coerceIn(1, 100)
                        schedule = TimerSchedule.Interval(seconds, count)
                        label = "每 ${formatSeconds(seconds)}执行一次，共 $count 次"
                    }
                    else -> {
                        val hour = args.optInt("hour", -1)
                        val minute = args.optInt("minute", 0)
                        if (hour !in 0..23) return "请提供 0~23 的小时（hour）"
                        if (minute !in 0..59) return "分钟需在 0~59 之间"
                        val repeatDaily = args.optBoolean("repeat_daily", false)
                        // 日期先行校验：宁可让模型改对，也不要把回访排在错的那天
                        val dayEpochDay = when (val r = parseOnceDate(args.optString("date"), repeatDaily)) {
                            is ParseResult.Ok -> r.day
                            is ParseResult.BadFormat ->
                                return "date 格式应为 YYYY-MM-DD（如 2026-10-16），收到的「${r.raw}」无法识别"
                            is ParseResult.Past ->
                                return "date「${r.raw}」已经过去了。请确认这个约定的真实日期" +
                                    "（若用户指的是「下周」，请换算成下周的具体日期再重试）"
                            ParseResult.Conflict ->
                                return "date 与 repeat_daily 冲突：要么指定某一天，要么每天重复"
                        }
                        schedule = TimerSchedule.FixedTime(hour, minute, repeatDaily, dayEpochDay)
                        val now = Calendar.getInstance()
                        val today = LocalDate.now()
                        val dayLabel = when {
                            repeatDaily -> "每天"
                            dayEpochDay != null -> when (dayEpochDay - today.toEpochDay()) {
                                0L -> "今天"
                                1L -> "明天"
                                else -> {
                                    val wanted = today.plusDays(dayEpochDay - today.toEpochDay())
                                    "${wanted.monthValue} 月 ${wanted.dayOfMonth} 日"
                                }
                            }
                            else -> {
                                val target = Calendar.getInstance().apply {
                                    set(Calendar.HOUR_OF_DAY, hour)
                                    set(Calendar.MINUTE, minute)
                                    set(Calendar.SECOND, 0)
                                }
                                if (target.timeInMillis > now.timeInMillis) "今天" else "明天"
                            }
                        }
                        label = "$dayLabel " + String.format(Locale.CHINA, "%02d:%02d", hour, minute)
                    }
                }

                val task = TimerTask(
                    id = UUID.randomUUID().toString(),
                    name = name,
                    schedule = schedule,
                    actions = listOf(TimerAction.AgentPrompt(prompt)),
                    running = true,
                )
                app.timerScheduler.addTask(task)
                app.timerScheduler.startTask(task)
                "已创建自主任务「$name」：$label。到时我会先自己去查资料，再把结果告诉你。" +
                    "为安全起见那一轮只有查询类只读能力（查时间/天气/网页/知识库/设备状态等），" +
                    "外加在手机上播放/停止音乐；不会自动拨号、安装或修改任何设置。" +
                    exactAlarmHint(context)
            }

        else -> throw IllegalArgumentException("未知工具: $name")
        }
    }

    /** 秒数的人类可读文案（自主任务的倒计时/间隔播报用） */
    private fun formatSeconds(seconds: Long): String = when {
        seconds % 3600L == 0L -> "${seconds / 3600} 小时"
        seconds % 60L == 0L -> "${seconds / 60} 分钟"
        else -> "$seconds 秒"
    }

    /**
     * 精确闹钟权限引导：创建定时任务后若系统不允许精确闹钟（Android 12~32 被撤销授权），
     * 经统一权限桥拉起系统授权框；已授权/不支持的版本返回空串。
     *
     * 注意这里不能用 [PermissionBridge.ensure]：它的返回文案是「放弃本次动作、再说一次」，
     * 而任务**已经建成**（只是可能不精确），所以用 [PermissionBridge.request] 只发起申请，
     * 再拼一句与本场景相符的提示。
     */
    private fun exactAlarmHint(context: Context): String {
        if (AppPermission.isGranted(context, AppPermission.EXACT_ALARM)) return ""
        val launched = PermissionBridge.request(
            context,
            listOf(AppPermission.EXACT_ALARM),
            context.getString(R.string.permission_reason_exact_alarm),
        )
        return if (launched) {
            " " + context.getString(R.string.timer_exact_alarm_requested)
        } else {
            " " + context.getString(R.string.timer_exact_alarm_notify)
        }
    }

    /**
     * 解析 `schedule_agent_task` 的 `date` 参数（`YYYY-MM-DD`）→ epochDay。
     *
     * ⚠️ 直接挂在 `internal object` 上（**不能**用 companion —— object 内声明 companion
     * 会报 "Modifier 'companion' is not applicable inside 'standalone object'"）。
     * 单测从 `TimerToolProvider.parseOnceDate(...)` 直接调，无需实例。
     *
         * ## 为什么必须是「拒绝」而不是「容错」
     * 日期算错的后果不是报错，而是**在错误的那天**弹出回访 —— 用户看到的是
     * 「周一问我周五的事」，比不提醒更糟（说明它不可信）。所以：
     *  - 格式非法 → [ParseResult.BadFormat]，让模型改对；
     *  - 日期已过 → [ParseResult.Past]，明确告诉它"这个约定已经过期"，
     *    模型据此改口问用户「你是指下周吗」而不是默默排到明天；
     *  - `repeatDaily = true` 却给了 date → 忽略多余的 date（见实现注释）。
     *
     * ⚠️ 用 `LocalDate.parse` 而不是 `SimpleDateFormat`：后者默认**宽松**，
     * `2026-13-45` 会被自动滚到下一年 2 月，模型写错日期反而"成功"了 ——
     * 正是本方法要防的那种静默错误。
     *
     * @param today 今天的 epochDay（抽出参数便于单测固定"今天"）
     */
    internal fun parseOnceDate(
        raw: String?,
        repeatDaily: Boolean,
        today: Long = LocalDate.now().toEpochDay(),
    ): ParseResult {
        if (repeatDaily) {
            // 「每天都执行」与「某一天」互斥，但这里选**忽略多余的 date** 而不是拒绝：
            // 模型顺手填了 date 的概率不低，为这点摩擦让它整个建不成反而更糟。
            return ParseResult.Ok(null)
        }
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return ParseResult.Ok(null)
        val date = runCatching { LocalDate.parse(text) }.getOrElse { return ParseResult.BadFormat(text) }
        val epochDay = date.toEpochDay()
        // 今天该点还没到的话，模型写今天日期是合法的 → 只挡**早于今天**的
        if (epochDay < today) return ParseResult.Past(text)
        return ParseResult.Ok(epochDay)
    }

    /** [parseOnceDate] 的结果 */
    internal sealed class ParseResult {
        /** [day] 为 null = 不限日期（按旧「今天/明天」语义） */
        data class Ok(val day: Long?) : ParseResult()

        /** 格式非法（非 YYYY-MM-DD 或不是真实存在的日期） */
        data class BadFormat(val raw: String) : ParseResult()

        /** 日期已过 */
        data class Past(val raw: String) : ParseResult()

        /** 与 repeat_daily 冲突（保留给将来真要做严格校验时用） */
        object Conflict : ParseResult()
    }
}
