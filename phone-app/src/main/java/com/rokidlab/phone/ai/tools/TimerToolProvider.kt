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
import org.json.JSONObject
import java.text.SimpleDateFormat
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
                description = "管理定时提醒 / 定时任务，一个工具管三种意图。action=\"create\"：创建（「5分钟后提醒我喝水」「明早8点叫我」「17点打开小智」）。action=\"list\"：列出全部（「我有哪些提醒」「几点有提醒」）。action=\"cancel\"：取消（「取消刚才的提醒」「删掉那个闹钟」；全部取消传 all=true）。注意：到点要我自己查资料再汇报，或到点要**播放某首歌**的需求，请改用 schedule_agent_task —— 本工具到点只会念一句固定内容（或在眼镜上打开指定应用），放不了音乐。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "action" to mapOf("type" to "string", "enum" to listOf("create", "list", "cancel"), "description" to "create=创建；list=列出全部；cancel=取消"),
                        "time" to mapOf("type" to "string", "description" to "action=create 时的时间，24 小时制 HH:mm，如 17:00"),
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
                    val time = args.optString("time").trim()
                    val content = args.optString("content").trim().ifBlank { "定时提醒" }
                    // 旧 set_timer 的 action 参数改名为 timerAction（外层 action 已被意图占用），
                    // 取值与语义不变：notify=只提醒 / launch=额外在眼镜上打开某应用
                    val timerAction = args.optString("timerAction", "notify")
                    val appName = args.optString("appName").trim()
                    val repeatDaily = args.optBoolean("repeatDaily", false)
                    val m = Regex("^(\\d{1,2}):(\\d{1,2})$").find(time)
                        ?: return "时间格式不正确，请用 24 小时制 HH:mm，例如 17:00"
                    val hour = m.groupValues[1].toInt()
                    val minute = m.groupValues[2].toInt()
                    if (hour !in 0..23 || minute !in 0..59) return "时间超出范围（00:00 ~ 23:59）"
                    val app = context.applicationContext as? LabApplication ?: return "应用上下文异常"

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
                        schedule = TimerSchedule.FixedTime(hour, minute, repeatDaily),
                        actions = actions,
                        running = true,
                    )
                    app.timerScheduler.addTask(task)
                    app.timerScheduler.startTask(task)
                    val now = Calendar.getInstance()
                    val todayTarget = Calendar.getInstance().apply {
                        set(Calendar.HOUR_OF_DAY, hour)
                        set(Calendar.MINUTE, minute)
                        set(Calendar.SECOND, 0)
                    }
                    val dayLabel = when {
                        repeatDaily -> "每天"
                        todayTarget.timeInMillis > now.timeInMillis -> "今天"
                        else -> "明天"
                    }
                    val timeLabel = String.format(Locale.CHINA, "%02d:%02d", hour, minute)
                    val actionLabel = if (timerAction == "launch") "并打开 $appName" else ""
                    "已设置定时任务：$dayLabel $timeLabel $actionLabel，提醒：$content"
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
                        schedule = TimerSchedule.FixedTime(hour, minute, repeatDaily)
                        val now = Calendar.getInstance()
                        val target = Calendar.getInstance().apply {
                            set(Calendar.HOUR_OF_DAY, hour)
                            set(Calendar.MINUTE, minute)
                            set(Calendar.SECOND, 0)
                        }
                        val dayLabel = when {
                            repeatDaily -> "每天"
                            target.timeInMillis > now.timeInMillis -> "今天"
                            else -> "明天"
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
                    "外加在手机上播放/停止音乐；不会自动拨号、安装或修改任何设置。"
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
}
