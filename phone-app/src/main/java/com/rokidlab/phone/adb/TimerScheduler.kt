package com.rokidlab.phone.adb

import com.rokidlab.phone.adb.ui.TimerAction
import com.rokidlab.phone.adb.ui.TimerSchedule
import com.rokidlab.phone.adb.ui.TimerTask
import com.rokidlab.phone.app.LabApplication
import com.rokidlab.phone.connection.ConnectionRoute
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap

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
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    // 任务运行协程表：startTask/stopTask/协程 finally 在多个协程间并发读写，必须线程安全
    private val jobs = ConcurrentHashMap<String, Job>()
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

    /** 启动任务（标记 running 并挂入 appScope 调度） */
    fun startTask(task: TimerTask) {
        val taskId = task.id
        if (jobs.containsKey(taskId)) return
        synchronized(tasksLock) {
            tasks = tasks.map { if (it.id == taskId) it.copy(running = true) else it }
            saveTasks()
        }
        val job = scope.launch {
            var repeatDaily = false
            try {
                when (task.schedule) {
                    is TimerSchedule.Interval -> {
                        for (i in 0 until task.schedule.count) {
                            if (!isActive) break
                            executeActions(task.actions)
                            updateExecutedCount(taskId)
                            if (i < task.schedule.count - 1) delay(task.schedule.seconds * 1000L)
                        }
                    }
                    is TimerSchedule.FixedTime -> {
                        val now = Calendar.getInstance()
                        val target = Calendar.getInstance().apply {
                            set(Calendar.HOUR_OF_DAY, task.schedule.hour)
                            set(Calendar.MINUTE, task.schedule.minute)
                            set(Calendar.SECOND, 0)
                        }
                        var delayMs = target.timeInMillis - now.timeInMillis
                        // 时间已过时统一顺延 24h（下一次该时间点），与 AI 提示的「明天 HH:mm」语义一致；
                        // 避免「已过时间的一次性任务」被立即触发，造成用户预期不符
                        if (delayMs < 0) delayMs += 24 * 3600 * 1000L
                        if (delayMs > 0) delay(delayMs)
                        if (isActive) {
                            executeActions(task.actions)
                            updateExecutedCount(taskId)
                        }
                        repeatDaily = task.schedule.repeatDaily
                    }
                    is TimerSchedule.Countdown -> {
                        delay(task.schedule.seconds * 1000L)
                        if (isActive) {
                            executeActions(task.actions)
                            updateExecutedCount(taskId)
                        }
                    }
                }
            } finally {
                // 仅当自己仍是该任务的当前执行者时清理，避免误伤并发 startTask 的新协程
                val removed = jobs.remove(taskId, coroutineContext[Job])
                if (removed) {
                    synchronized(tasksLock) {
                        tasks = tasks.map { if (it.id == taskId) it.copy(running = false) else it }
                        saveTasks()
                    }
                }
                // 每日定时任务：本次执行完成后重新调度下一天
                if (repeatDaily) {
                    val latest = synchronized(tasksLock) { tasks.firstOrNull { it.id == taskId } }
                    if (latest != null) startTask(latest)
                }
            }
        }
        val prev = jobs.putIfAbsent(taskId, job)
        if (prev != null) {
            // 并发 startTask 已抢先登记：取消本协程，交由已有任务执行
            job.cancel()
        }
    }

    fun stopTask(id: String) {
        jobs[id]?.cancel()
        jobs.remove(id)
        synchronized(tasksLock) {
            tasks = tasks.map { if (it.id == id) it.copy(running = false) else it }
            saveTasks()
        }
    }

    fun stopAll() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        synchronized(tasksLock) {
            tasks = tasks.map { it.copy(running = false) }
            saveTasks()
        }
    }

    /** 恢复运行中的任务（保活服务启动/自愈重建时调用） */
    fun resumeRunningTasks() {
        val running = synchronized(tasksLock) { tasks.filter { it.running } }
        running.forEach { startTask(it) }
        Log.i(TAG, "resumeRunningTasks: ${running.size} running")
    }

    /** App 退出时停止全部任务 */
    fun shutdown() {
        stopAll()
    }

    private fun updateExecutedCount(taskId: String) {
        synchronized(tasksLock) {
            tasks = tasks.map { if (it.id == taskId) it.copy(executedCount = it.executedCount + 1) else it }
            saveTasks()
        }
    }

    // ── 动作执行 ──

    private suspend fun executeActions(actions: List<TimerAction>) {
        for (action in actions) {
            try {
                when (action) {
                    is TimerAction.SendNotification -> {
                        postLocalNotification(action.title, action.content)
                        withAdbClient { it.sendNotification(action.title, action.content) }
                    }
                    is TimerAction.LaunchApp -> withAdbClient { it.launchApp(action.packageName) }
                    is TimerAction.ExecuteShell -> withAdbClient { it.executeShellCommand(action.command) }
                    is TimerAction.Tap -> withAdbClient { it.tap(action.x, action.y) }
                    is TimerAction.SendKeyEvent -> withAdbClient { it.sendKeyEvent(action.keyCode) }
                    is TimerAction.TtsSpeak -> speakOnGlass(action.text)
                }
            } catch (e: Exception) {
                Log.w(TAG, "executeActions ${action.javaClass.simpleName} failed: ${e.message}")
            }
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
        else -> TimerAction.ExecuteShell("")
    }
}
