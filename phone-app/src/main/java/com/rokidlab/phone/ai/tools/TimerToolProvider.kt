package com.rokidlab.phone.ai.tools

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
        "set_timer",
        "list_timers",
        "cancel_timer",
    )

    override fun execute(context: Context, name: String, args: JSONObject): String {
        return when (name) {
            "set_timer" -> {
                val time = args.optString("time").trim()
                val content = args.optString("content").trim().ifBlank { "定时提醒" }
                val action = args.optString("action", "notify")
                val appName = args.optString("appName").trim()
                val repeatDaily = args.optBoolean("repeatDaily", false)
                val m = Regex("^(\\d{1,2}):(\\d{1,2})$").find(time)
                    ?: return "时间格式不正确，请用 24 小时制 HH:mm，例如 17:00"
                val hour = m.groupValues[1].toInt()
                val minute = m.groupValues[2].toInt()
                if (hour !in 0..23 || minute !in 0..59) return "时间超出范围（00:00 ~ 23:59）"
                val app = context.applicationContext as? LabApplication ?: return "应用上下文异常"

                val actions = mutableListOf<TimerAction>()
                if (action == "launch") synchronized(ToolRegistry.adbLock) {
                    val client = ToolRegistry.adbClient(context)
                        ?: return "眼镜 ADB 连接失败，无法创建打开应用的定时任务"
                    try {
                        val pkg = ToolRegistry.matchPackage(appName, client.listPackages(false), context)
                            ?: return "没有找到应用“$appName”，无法创建定时打开任务"
                        actions += TimerAction.LaunchApp(pkg)
                    } finally {
                        // 常驻复用共享 ADB 连接，不用后即断（原因见 get_glasses_battery）
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
                val actionLabel = if (action == "launch") "并打开 $appName" else ""
                "已设置定时任务：$dayLabel $timeLabel $actionLabel，提醒：$content"
            }

            "list_timers" -> {
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
                        "[${i + 1}] ${t.name}｜$sched｜${if (t.running) "运行中" else "已暂停"}"
                    }.joinToString("\n") + "\n如需取消，告诉我任务名称即可"
                }
            }

            "cancel_timer" -> {
                val app = context.applicationContext as? LabApplication ?: return "应用上下文异常"
                if (args.optBoolean("all", false)) {
                    val count = app.timerScheduler.tasks.size
                    app.timerScheduler.stopAll()
                    app.timerScheduler.tasks.toList().forEach { app.timerScheduler.deleteTask(it.id) }
                    return if (count > 0) "已取消全部 $count 个定时任务" else "当前没有可取消的定时任务"
                }
                val name = args.optString("timerName").trim()
                if (name.isEmpty()) return "请提供要取消的任务名称（可先用 list_timers 查看）"
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

        else -> throw IllegalArgumentException("未知工具: $name")
        }
    }
}
