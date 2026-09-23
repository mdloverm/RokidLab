package com.rokidlab.phone.adb

import com.rokidlab.phone.adb.ui.TimerAction
import com.rokidlab.phone.adb.ui.TimerSchedule
import com.rokidlab.phone.adb.ui.TimerTask
import com.rokidlab.phone.app.LabApplication
import com.rokidlab.phone.connection.ConnectionRoute
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import android.util.Log
import com.rokidlab.phone.permission.AppPermission
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 定时任务调度器（Application 级单例，由 LabApplication 持有）。
 * 任务持久化到 SharedPreferences，调度挂载在 appScope（不随 Activity 销毁取消）。
 * 配合保活前台服务，App 退后台/销毁后定时任务仍能常驻触发。
 *
 * 后台触发时按需建立短连接 ADB 会话（使用 adb_prefs 中保存的眼镜 IP），
 * 执行完立即断开，避免长时间占用链路。
 */
class TimerScheduler(private val appContext: Context) {
    companion object {
        private const val TAG = "TimerScheduler"
        private const val PREFS_TIMER = "timer_scheduler"
        private const val KEY_TASKS = "tasks"
        private const val PREFS_ADB = "adb_prefs"
        private const val KEY_ADB_IP = "ip"
        private const val ADB_PORT = 5555

        /**
         * 自主任务等待 Agent 回复的上限（毫秒）。多轮只读工具 + 联网查询的正常量级是
         * 十几秒到几十秒；超过此值按「无回复」处理并如实通知，绝不无限等待卡住调度协程。
         */
        private const val AGENT_TASK_TIMEOUT_MS = 90_000L

        /**
         * 一次性/每日提醒错过触发后的补跑宽限（毫秒）：
         * 设备关机/进程被杀导致到点时没跑成，恢复后错过不超过此时长才补跑一次，
         * 超过就跳过（避免「早上 8 点的提醒晚上 10 点开机才响」）。
         */
        private const val FIXED_MISSED_GRACE_MS = 2 * 60 * 60 * 1000L

        /** 倒计时任务错过后的补跑宽限（毫秒）。 */
        private const val COUNTDOWN_MISSED_GRACE_MS = 60 * 60 * 1000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    // 任务**执行中**的协程表（fireTask 期间占用）：stopTask/shutdown 时可中断执行体。
    // 等待触发不再靠协程 delay，而由系统 AlarmManager 承载。
    private val jobs = ConcurrentHashMap<String, Job>()
    /** 正在执行的任务 id（防同一闹钟重复投递导致动作跑两遍） */
    private val inflight = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    /** tasks 读-改-写锁：UI 线程与 Default 调度协程并发修改任务列表，需串行化避免丢失更新 */
    private val tasksLock = Any()
    private val tasksPrefs = appContext.getSharedPreferences(PREFS_TIMER, Context.MODE_PRIVATE)

    /** 当前任务列表（含运行时 running / executedCount 状态） */
    @Volatile
    var tasks: List<TimerTask> = loadTasks()
        private set

    // ── 任务管理 ──

    /** 新增任务（running=false，由 UI 显式启动） */
    fun addTask(task: TimerTask) {
        synchronized(tasksLock) {
            tasks = tasks + task.copy(running = false)
            saveTasks()
        }
        Log.i(TAG, "addTask: ${task.name}")
    }

    /** 更新任务：若在运行则先停止再以新配置重启，保持用户预期 */
    fun updateTask(task: TimerTask) {
        val old = synchronized(tasksLock) { tasks.firstOrNull { it.id == task.id } }
        stopTask(task.id)
        val updated = task.copy(running = false, executedCount = old?.executedCount ?: task.executedCount)
        synchronized(tasksLock) {
            tasks = tasks.map { if (it.id == task.id) updated else it }
            saveTasks()
        }
        Log.i(TAG, "updateTask: ${task.name}")
        // 原任务运行中：编辑后以新配置继续运行
        if (old?.running == true) startTask(updated)
    }

    fun deleteTask(id: String) {
        stopTask(id)
        synchronized(tasksLock) {
            tasks = tasks.filter { it.id != id }
            saveTasks()
        }
        Log.i(TAG, "deleteTask: $id")
    }

    /**
     * 启动任务（标记 running 并向系统 AlarmManager 登记下一次触发）。
     *
     * 触发等待由系统闹钟承载（而非内存协程 `delay`）：Doze/应用待机下仍由
     * `setExactAndAllowWhileIdle` 准时唤醒；缺精确闹钟权限时降级为非精确闹钟（不阻断功能）。
     */
    fun startTask(task: TimerTask) {
        if (jobs.containsKey(task.id)) return
        synchronized(tasksLock) {
            val cur = tasks.firstOrNull { it.id == task.id }
            if (cur?.running == true && cur.nextTriggerAt > 0L) return
            tasks = tasks.map {
                if (it.id == task.id) it.copy(running = true) else it
            }
            saveTasks()
        }
        scheduleNext(task, recovery = false)
    }

    /**
     * 计算并登记下一次触发闹钟。
     *
     * @param recovery 进程重建/开机恢复路径：true 时优先复用任务持久化的 [TimerTask.nextTriggerAt]，
     *   错过的触发按宽限规则补跑；false（新建/编辑/上一次执行完排下一次）时按当前时刻重算。
     */
    private fun scheduleNext(task: TimerTask, recovery: Boolean) {
        val now = System.currentTimeMillis()
        val sched = task.schedule

        // ── 次数用尽（interval）：直接收尾，不再排闹钟 ──
        if (sched is TimerSchedule.Interval && task.executedCount >= sched.count) {
            finishTask(task.id)
            return
        }

        // ── 恢复路径：先看持久化的计划触发点是否已错过 ──
        if (recovery && task.nextTriggerAt > 0L) {
            val missedAt = task.nextTriggerAt
            if (missedAt <= now) {
                val grace = when (sched) {
                    is TimerSchedule.Countdown -> COUNTDOWN_MISSED_GRACE_MS
                    is TimerSchedule.Interval -> Long.MAX_VALUE // interval 错过只补一次后续接着跑
                    is TimerSchedule.FixedTime -> FIXED_MISSED_GRACE_MS
                }
                // 一次性 fixed 任务若已经成功执行过，错过的只是"重排闹钟"，不补跑
                val alreadyDoneOnce = task.executedCount > 0
                val withinGrace = now - missedAt <= grace
                if (withinGrace && !(alreadyDoneOnce && sched is TimerSchedule.FixedTime && !sched.repeatDaily)) {
                    Log.i(TAG, "missed trigger recovered, fire now: ${task.name}")
                    fireTask(task.id)
                    return
                }
                // 超出宽限：daily 顺延到下一个今天/明天时刻；一次性 countdown/fixed 收尾
                if (sched is TimerSchedule.FixedTime && sched.repeatDaily) {
                    arm(task.id, nextFixedTime(sched, now))
                    return
                }
                finishTask(task.id)
                return
            }
            // 未到点：按原计划时刻重新登记（countdown 不会因重启而重新倒计时）
            arm(task.id, missedAt)
            return
        }

        // ── 正常排程 ──
        val triggerAt = when (sched) {
            is TimerSchedule.Countdown -> now + sched.seconds * 1000L
            is TimerSchedule.Interval -> now + sched.seconds * 1000L
            is TimerSchedule.FixedTime -> nextFixedTime(sched, now)
        }
        arm(task.id, triggerAt)
    }

    /** 计算下一个未来的固定时刻（今天该点未过→今天，否则明天） */
    private fun nextFixedTime(s: TimerSchedule.FixedTime, nowMs: Long): Long {
        val target = Calendar.getInstance().apply {
            timeInMillis = nowMs
            set(Calendar.HOUR_OF_DAY, s.hour)
            set(Calendar.MINUTE, s.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (target.timeInMillis <= nowMs) target.add(Calendar.DAY_OF_YEAR, 1)
        return target.timeInMillis
    }

    /** 登记一个（精确）闹钟，并把触发时刻持久化（恢复补跑的判定依据） */
    private fun arm(taskId: String, triggerAt: Long) {
        val pi = alarmPendingIntent(taskId)
        val am = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        try {
            if (AppPermission.canScheduleExactAlarms(appContext)) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            } else {
                // 缺精确闹钟权限（31/32 被用户撤销且无 USE_EXACT_ALARM）：降级不精确闹钟，
                // Doze 下会并入维护窗口延迟触发 —— 功能不丢，到点时间不保证
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                Log.w(TAG, "exact alarm permission missing, inexact alarm armed: $taskId")
            }
            synchronized(tasksLock) {
                tasks = tasks.map { if (it.id == taskId) it.copy(nextTriggerAt = triggerAt) else it }
                saveTasks()
            }
            Log.i(TAG, "alarm armed: $taskId at $triggerAt")
        } catch (e: SecurityException) {
            // 个别 ROM 在权限被撤销时对 set*AndAllowWhileIdle 也抛 SecurityException：
            // 记日志并放弃（下一轮定时任务执行时的权限引导会让用户重新授权）
            Log.w(TAG, "arm failed (SecurityException): ${e.message}")
        }
    }

    private fun alarmPendingIntent(taskId: String): PendingIntent {
        val intent = android.content.Intent(appContext, TimerAlarmReceiver::class.java).apply {
            action = TimerAlarmReceiver.ACTION_FIRE
            putExtra(TimerAlarmReceiver.EXTRA_TASK_ID, taskId)
        }
        // String.hashCode 确定性，同一任务每次拿到同一 requestCode（取消/覆盖才找得到原闹钟）
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        return PendingIntent.getBroadcast(appContext, taskId.hashCode(), intent, flags)
    }

    /**
     * 闹钟触发入口（由 [TimerAlarmReceiver] → 保活服务在主线程投递）。
     *
     * 动作执行放在 [scope] 协程（长任务可达 90s，由保活服务 WakeLock 托底）。
     * 执行采用 **at-least-once + 幂等工具**：先跑动作再推进计数/排下一次，
     * 进程死在执行中途时恢复路径会据 nextTriggerAt 补跑一次（工具网关按 cbId 幂等去重）。
     */
    fun fireTask(taskId: String) {
        val task = synchronized(tasksLock) { tasks.firstOrNull { it.id == taskId } } ?: return
        if (!task.running) return
        if (!inflight.add(taskId)) {
            Log.i(TAG, "fireTask ignored, already inflight: ${task.name}")
            return
        }
        val job = scope.launch {
            try {
                executeActions(task.actions)
                // 推进计数并安排下一次（动作跑完后再做，崩溃在中途 ⇒ 恢复时补跑）
                afterFired(taskId)
            } catch (e: Exception) {
                Log.w(TAG, "fireTask failed: ${task.name}: ${e.message}")
                afterFired(taskId)
            } finally {
                inflight.remove(taskId)
            }
        }
        jobs[taskId] = job
        job.invokeOnCompletion { jobs.remove(taskId, job) }
    }

    /** 一次触发完成后的推进：interval 排下一次 / daily 排明天 / 其余收尾 */
    private fun afterFired(taskId: String) {
        val task = synchronized(tasksLock) { tasks.firstOrNull { it.id == taskId } } ?: return
        synchronized(tasksLock) {
            tasks = tasks.map {
                if (it.id == taskId) it.copy(executedCount = it.executedCount + 1) else it
            }
            saveTasks()
        }
        val updated = synchronized(tasksLock) { tasks.firstOrNull { it.id == taskId } } ?: return
        when (val s = task.schedule) {
            is TimerSchedule.Interval -> {
                if (updated.executedCount >= s.count) {
                    finishTask(taskId)
                } else {
                    arm(taskId, System.currentTimeMillis() + s.seconds * 1000L)
                }
            }
            is TimerSchedule.FixedTime -> {
                if (s.repeatDaily) {
                    arm(taskId, nextFixedTime(s, System.currentTimeMillis()))
                } else {
                    finishTask(taskId)
                }
            }
            is TimerSchedule.Countdown -> finishTask(taskId)
        }
    }

    /** 任务自然结束（次数用尽/一次性任务已执行）：清闹钟、标记停止 */
    private fun finishTask(taskId: String) {
        cancelAlarm(taskId)
        synchronized(tasksLock) {
            tasks = tasks.map {
                if (it.id == taskId) it.copy(running = false, nextTriggerAt = 0L) else it
            }
            saveTasks()
        }
        Log.i(TAG, "task finished: $taskId")
    }

    private fun cancelAlarm(taskId: String) {
        runCatching {
            val am = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.cancel(alarmPendingIntent(taskId))
        }
    }

    fun stopTask(id: String) {
        jobs[id]?.cancel()
        jobs.remove(id)
        cancelAlarm(id)
        synchronized(tasksLock) {
            tasks = tasks.map {
                if (it.id == id) it.copy(running = false, nextTriggerAt = 0L) else it
            }
            saveTasks()
        }
    }

    fun stopAll() {
        synchronized(tasksLock) { tasks.forEach { cancelAlarm(it.id) } }
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        synchronized(tasksLock) {
            tasks = tasks.map { it.copy(running = false, nextTriggerAt = 0L) }
            saveTasks()
        }
    }

    /**
     * 恢复运行中的任务（保活服务启动/开机自启/进程自愈重建时调用）。
     * 走 recovery 路径：未到点的闹钟重新登记，错过且在宽限内的立即补跑。
     */
    fun resumeRunningTasks() {
        val running = synchronized(tasksLock) { tasks.filter { it.running } }
        running.forEach { scheduleNext(it, recovery = true) }
        Log.i(TAG, "resumeRunningTasks: ${running.size} running")
    }

    /** App 退出时停止全部任务并撤销闹钟 */
    fun shutdown() {
        stopAll()
    }

    // ── 动作执行 ──

    private suspend fun executeActions(actions: List<TimerAction>) {
        for (action in actions) {
            try {
                when (action) {
                    is TimerAction.SendNotification -> {
                        // 本地通知保持原文（离线可见、绝对可靠）；推给眼镜的播报文本经 AI 润色
                        postLocalNotification(action.title, action.content)
                        val spoken = polishAnnouncement(action.content)
                        withAdbClient { it.sendNotification(action.title, spoken) }
                    }
                    is TimerAction.LaunchApp -> withAdbClient { it.launchApp(action.packageName) }
                    is TimerAction.ExecuteShell -> withAdbClient { it.executeShellCommand(action.command) }
                    is TimerAction.Tap -> withAdbClient { it.tap(action.x, action.y) }
                    is TimerAction.SendKeyEvent -> withAdbClient { it.sendKeyEvent(action.keyCode) }
                    is TimerAction.TtsSpeak -> speakOnGlass(polishAnnouncement(action.text))
                    is TimerAction.AgentPrompt -> runAgentTask(action.prompt)
                }
            } catch (e: Exception) {
                Log.w(TAG, "executeActions ${action.javaClass.simpleName} failed: ${e.message}")
            }
        }
    }

    /**
     * 到点播报文本 AI 润色 —— 事件驱动唤醒的最小闭环（Agent 缺口 #6）。
     *
     * 提醒到点时把模型当初生成的简短 content 润色成一句更自然、带「此刻该做什么」语境的
     * 播报（如「该喝水了」→「到喝水时间啦，记得起身接杯水」）。任何失败（无配置/网络/
     * 超时/输出不合规）一律降级原文 —— 提醒链路是已验证的核心路径，润色只是增益，
     * 绝不能因润色失败而不提醒；单次尝试 + 8s 读超时保证到点响应延迟有界。
     */
    private suspend fun polishAnnouncement(raw: String): String {
        if (raw.isBlank()) return raw
        return withContext(Dispatchers.IO) {
            try {
                val app = appContext as? LabApplication ?: return@withContext raw
                if (!app.hasCxrL()) return@withContext raw
                val cfg = app.cxrL.getAiConfig()
                // 后台档位：读超时 8s / 不附加本地调参 / 不开思考 —— 规则收口在 llm 接缝
                val svc = com.rokidlab.phone.ai.llm.LlmRegistry.newService(
                    cfg,
                    com.rokidlab.phone.ai.llm.LlmRegistry.Profile.BACKGROUND,
                )
                val messages = org.json.JSONArray().put(
                    org.json.JSONObject().apply {
                        put("role", "user")
                        put(
                            "content",
                            "定时提醒到点了，请把这条提醒润色成一句自然的中文播报，15 字以内，" +
                                "直接说要做什么，不要解释、不要引号。提醒内容：$raw",
                        )
                    },
                )
                val turn = svc.chatTurn(messages, tools = null, readTimeout = 8_000, attempts = 1)
                val polished = turn.content?.trim()?.trim('"', '「', '」', '“', '”')
                // 输出不合规（空/过长/疑似复读原文之外的整段话）一律回原文
                if (!polished.isNullOrBlank() && polished.length <= 40) polished else raw
            } catch (e: Exception) {
                Log.w(TAG, "polishAnnouncement fallback to raw: ${e.message}")
                raw
            }
        }
    }

    // ── 自主任务（真主动性）──

    /**
     * 执行「自主任务」（[TimerAction.AgentPrompt]）—— 真主动性的落地动作。
     *
     * 链路：到点 → 让 Agent 带着**只读工具 + 本地媒体白名单**跑一轮推理（现查现算）→ 把结果播报出来。
     * 与 [TimerAction.TtsSpeak] 的本质区别：念的是**此刻的真实结果**（今天天气、今天的日程），
     * 而不是创建任务时写死的文案。
     *
     * 无人值守的安全边界（已与用户确认）：
     *  - **只读 + 本地媒体**：本轮工具集 = [com.rokidlab.phone.ai.ToolRegistry.schemasUnattended]，
     *    模型物理上拿不到拨号/装机/写文件/打开应用等副作用工具；唯一例外是白名单里
     *    「本机可撤销」的媒体工具（`control_music` 放歌/停止），见
     *    [com.rokidlab.phone.ai.ToolRegistry.UNATTENDED_MEDIA_ALLOWLIST]。
     *    （原先只给纯只读档，导致「到点放首歌」的任务没有工具可用、只能把歌名念给眼镜听。）
     *  - **不写会话记忆**：`recordHistory=false`，不挤占用户主对话的上下文；
     *  - **失败绝不静默**：拿不到回复时发一条本地通知说明，而不是到点什么都不发生
     *    （"以为设了提醒其实根本没响"最伤信任）。
     *
     * 已知局限（接受）：本方法经 sendAiTextMessage 发起，会顺带 bump AI 代际号 ——
     * 若恰好与用户正在进行的对话撞车，用户那条请求会被打断。定时任务通常落在整点/早晚
     * 固定时刻，撞车概率低；彻底解耦需要独立的后台生成通道，属另一档工程。
     */
    private suspend fun runAgentTask(prompt: String) {
        if (prompt.isBlank()) return
        val reply = withContext(Dispatchers.IO) { requestAgentReply(prompt) }
        if (reply.isNullOrBlank()) {
            Log.w(TAG, "runAgentTask: no reply from agent, notify failure")
            postLocalNotification(
                appContext.getString(com.rokidlab.phone.R.string.timer_agent_task_failed_title),
                prompt.take(60),
            )
            return
        }
        Log.i(TAG, "runAgentTask: reply ${reply.length} chars")
        // 本地通知保证「离线可见、绝对可靠」；眼镜播报走 tts_play（RokidLink 本地合成）
        postLocalNotification(appContext.getString(com.rokidlab.phone.R.string.timer_agent_task_title), reply)
        speakOnGlass(reply)
    }

    /**
     * 让 Agent 跑一轮**只读**推理并返回回复文本。
     *
     * 用 [CountDownLatch] 把 sendAiTextMessage 的异步回调转成同步结果；超时上限 90s
     * （多轮只读工具 + 联网查询的正常量级），超时按「无回复」处理。
     *
     * 显示口径与用户在聊天框发消息一致（默认下行链路：眼镜打开助手页并显示问答），
     * 但 `skipTtsAudioFinished=true` 保留回复留在屏上；`showAsrResult=true` 让眼镜端
     * 能看到「问了什么」—— 主动播报若只出声不显示，用户会莫名听到一段话无从对照。
     */
    private fun requestAgentReply(prompt: String): String? {
        val app = appContext as? LabApplication ?: return null
        if (!app.hasCxrL()) {
            Log.w(TAG, "requestAgentReply: cxrL not ready")
            return null
        }
        val latch = CountDownLatch(1)
        val out = java.util.concurrent.atomic.AtomicReference<String?>(null)
        try {
            app.cxrL.sendAiTextMessage(
                text = prompt,
                onResult = { _, _ -> latch.countDown() },
                onReply = { r -> out.set(r) },
                interruptOfficialFirst = false,
                skipTtsAudioFinished = true,
                showAsrResult = true,
                localTakeover = false,
                recordHistory = false,
                readOnlyTools = true,
            )
        } catch (e: Exception) {
            Log.w(TAG, "requestAgentReply: send failed: ${e.message}")
            return null
        }
        return try {
            if (!latch.await(AGENT_TASK_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                Log.w(TAG, "requestAgentReply: timeout after ${AGENT_TASK_TIMEOUT_MS}ms")
            }
            out.get()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        }
    }

    /** 按需执行 ADB 操作。
     *
     *  优先复用全 App 共享 ADB 会话（[com.rokidlab.phone.glasses.CxrLHiRokidSession.getAdbShellClient]）：
     *  手机侧蓝牙栈对「同一设备 + 同一 SCN」只允许一条客户端 RFCOMM 通道，
     *  定时任务自建第二条会话会把用户正在用的 ADB 工具 / 屏幕镜像 / 投屏会话挤断；
     *  且共享会话闲置时本就常驻，复用无额外开销。
     *
     *  仅当共享会话不可用时，才退回「按需短连接」老路径（WiFi 直连或蓝牙隧道，
     *  蓝牙场景下不再直连 IP+5555，隧道关闭时直连必然失败）。
     */
    private suspend fun withAdbClient(block: (AdbShellClient) -> Unit) {
        val app = appContext as? LabApplication ?: return

        // ① 共享会话
        if (app.hasCxrL()) {
            val shared = withContext(Dispatchers.IO) {
                runCatching { app.cxrL.getAdbShellClient() }.getOrNull()
            }
            if (shared != null) {
                runCatching { block(shared) }
                    .onFailure { Log.w(TAG, "withAdbClient(shared) failed: ${it.message}") }
                return
            }
        }

        // ② 兜底：短连接
        val ip = app.glassesIp
        if (ip.isBlank()) {
            Log.w(TAG, "withAdbClient: no ADB IP configured")
            return
        }
        val route = try {
            app.routeManager.resolve(ip, ADB_PORT)
        } catch (e: Exception) {
            Log.w(TAG, "withAdbClient: route resolve failed: ${e.message}")
            return
        }
        val primary: Pair<String, Int>? = when (route) {
            is ConnectionRoute.Wifi -> route.ip to route.port
            is ConnectionRoute.Bluetooth -> route.ip to route.localPort
            is ConnectionRoute.None -> null
        }
        if (primary == null) {
            Log.w(TAG, "withAdbClient: no route to glasses")
            return
        }
        if (withShortLivedClient(primary, block)) return
        if (route !is ConnectionRoute.Wifi) return
        // WiFi 首选建链失败：记账（清缓存 + 记失败时间，使线路缓存里的死 WiFi 立即失效）后
        // 降级蓝牙隧道重试一次。旧实现只写一行 Log.w —— 眼镜离网而手机仍在 WiFi 时，
        // 60s 线路缓存会把每个定时任务都导向同一个死 WiFi，且不记账导致缓存永不自愈。
        app.routeManager.noteWifiFailure()
        val localPort = runCatching { app.routeManager.tunnelTo(ADB_PORT) }.getOrNull()
        if (localPort == null) {
            Log.w(TAG, "withAdbClient: BT tunnel unavailable, giving up")
            return
        }
        if (!withShortLivedClient("127.0.0.1" to localPort, block)) {
            Log.w(TAG, "withAdbClient: BT tunnel connect failed too")
        }
    }

    /**
     * 按给定端点建一条短连接执行 [block]，用完即断。
     *
     * @return true = 建链成功且 [block] 执行过（block 自身抛错只记日志，不视为建链失败）
     */
    private fun withShortLivedClient(endpoint: Pair<String, Int>, block: (AdbShellClient) -> Unit): Boolean {
        val client = try {
            AdbShellClient(appContext, endpoint.first, endpoint.second)
        } catch (e: Exception) {
            Log.w(TAG, "withAdbClient: client create failed: ${e.message}")
            return false
        }
        return try {
            if (!client.connect()) {
                Log.w(TAG, "withAdbClient: connect failed -> ${endpoint.first}:${endpoint.second}")
                false
            } else {
                runCatching { block(client) }
                    .onFailure { Log.w(TAG, "withAdbClient(block) failed: ${it.message}") }
                true
            }
        } catch (e: Exception) {
            Log.w(TAG, "withAdbClient failed: ${e.message}")
            false
        } finally {
            client.disconnect()
        }
    }

    /** 通过 CxrL 链路发送文本到眼镜端 TTS 播报（App 退后台后链路仍可用）；链路未就绪时降级为本地通知 */
    private fun speakOnGlass(text: String) {
        val app = appContext as? LabApplication ?: return
        try {
            if (app.hasCxrL()) {
                app.cxrL.sendTtsToGlass(text)
            } else {
                Log.w(TAG, "speakOnGlass: cxrL not ready, fallback to notification")
                postLocalNotification("Rokid", text)
            }
        } catch (e: Exception) {
            Log.w(TAG, "speakOnGlass failed: ${e.message}")
        }
    }

    private fun postLocalNotification(title: String, content: String) {
        try {
            val nm = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    "timer_notify", appContext.getString(com.rokidlab.phone.R.string.timer_channel_name),
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = appContext.getString(com.rokidlab.phone.R.string.timer_channel_desc)
                }
                nm.createNotificationChannel(channel)
            }
            val notification: Notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(appContext, "timer_notify")
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle(title)
                    .setContentText(content)
                    .setAutoCancel(true)
                    .build()
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(appContext)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle(title)
                    .setContentText(content)
                    .setAutoCancel(true)
                    .setPriority(Notification.PRIORITY_HIGH)
                    .build()
            }
            nm.notify(System.currentTimeMillis().toInt(), notification)
        } catch (e: Exception) {
            Log.w(TAG, "postLocalNotification failed: ${e.message}")
        }
    }

    // ── 持久化（org.json 手写序列化） ──

    private fun loadTasks(): List<TimerTask> {
        val raw = tasksPrefs.getString(KEY_TASKS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { parseTask(arr.getJSONObject(it)) }
        } catch (e: Exception) {
            Log.w(TAG, "loadTasks failed: ${e.message}")
            emptyList()
        }
    }

    private fun saveTasks() {
        try {
            val arr = JSONArray()
            tasks.forEach { arr.put(serializeTask(it)) }
            tasksPrefs.edit().putString(KEY_TASKS, arr.toString()).apply()
        } catch (e: Exception) {
            Log.w(TAG, "saveTasks failed: ${e.message}")
        }
    }

    private fun serializeTask(task: TimerTask): JSONObject {
        val j = JSONObject()
        j.put("id", task.id)
        j.put("name", task.name)
        j.put("running", task.running)
        j.put("executedCount", task.executedCount)
        j.put("nextTriggerAt", task.nextTriggerAt)
        j.put("schedule", serializeSchedule(task.schedule))
        val actions = JSONArray()
        task.actions.forEach { actions.put(serializeAction(it)) }
        j.put("actions", actions)
        return j
    }

    private fun parseTask(j: JSONObject): TimerTask = TimerTask(
        id = j.getString("id"),
        name = j.getString("name"),
        schedule = parseSchedule(j.getJSONObject("schedule")),
        actions = run {
            val arr = j.getJSONArray("actions")
            (0 until arr.length()).map { parseAction(arr.getJSONObject(it)) }
        },
        running = j.optBoolean("running", false),
        executedCount = j.optInt("executedCount", 0),
        nextTriggerAt = j.optLong("nextTriggerAt", 0L),
    )

    private fun serializeSchedule(s: TimerSchedule): JSONObject {
        val j = JSONObject()
        when (s) {
            is TimerSchedule.Interval -> {
                j.put("type", "interval")
                j.put("seconds", s.seconds)
                j.put("count", s.count)
            }
            is TimerSchedule.FixedTime -> {
                j.put("type", "fixed")
                j.put("hour", s.hour)
                j.put("minute", s.minute)
                j.put("repeatDaily", s.repeatDaily)
            }
            is TimerSchedule.Countdown -> {
                j.put("type", "countdown")
                j.put("seconds", s.seconds)
            }
        }
        return j
    }

    private fun parseSchedule(j: JSONObject): TimerSchedule = when (j.getString("type")) {
        "fixed" -> TimerSchedule.FixedTime(j.optInt("hour", 8), j.optInt("minute", 0), j.optBoolean("repeatDaily", false))
        "countdown" -> TimerSchedule.Countdown(j.optLong("seconds", 60L))
        else -> TimerSchedule.Interval(j.optLong("seconds", 5L), j.optInt("count", 5))
    }

    private fun serializeAction(a: TimerAction): JSONObject {
        val j = JSONObject()
        when (a) {
            is TimerAction.SendNotification -> {
                j.put("type", "notify")
                j.put("title", a.title)
                j.put("content", a.content)
            }
            is TimerAction.LaunchApp -> {
                j.put("type", "launch")
                j.put("packageName", a.packageName)
            }
            is TimerAction.ExecuteShell -> {
                j.put("type", "shell")
                j.put("command", a.command)
            }
            is TimerAction.Tap -> {
                j.put("type", "tap")
                j.put("x", a.x)
                j.put("y", a.y)
            }
            is TimerAction.SendKeyEvent -> {
                j.put("type", "key")
                j.put("keyCode", a.keyCode)
            }
            is TimerAction.TtsSpeak -> {
                j.put("type", "tts")
                j.put("text", a.text)
            }
            is TimerAction.AgentPrompt -> {
                j.put("type", "agent")
                j.put("prompt", a.prompt)
            }
        }
        return j
    }

    private fun parseAction(j: JSONObject): TimerAction = when (j.getString("type")) {
        "notify" -> TimerAction.SendNotification(j.optString("title", "Rokid"), j.getString("content"))
        "launch" -> TimerAction.LaunchApp(j.getString("packageName"))
        "shell" -> TimerAction.ExecuteShell(j.getString("command"))
        "tap" -> TimerAction.Tap(j.optInt("x", 0), j.optInt("y", 0))
        "key" -> TimerAction.SendKeyEvent(j.optInt("keyCode", 0))
        "tts" -> TimerAction.TtsSpeak(j.getString("text"))
        "agent" -> TimerAction.AgentPrompt(j.optString("prompt"))
        else -> TimerAction.ExecuteShell("")
    }
}
