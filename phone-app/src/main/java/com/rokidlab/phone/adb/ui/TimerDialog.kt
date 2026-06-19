package com.rokidlab.phone.adb.ui

import com.rokidlab.phone.adb.AdbShellClient
import com.rokidlab.phone.design.*
import com.rokidlab.phone.R
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import java.util.*

sealed class TimerAction {
    data class SendNotification(val title: String, val content: String) : TimerAction()
    data class LaunchApp(val packageName: String) : TimerAction()
    data class ExecuteShell(val command: String) : TimerAction()
    data class Tap(val x: Int, val y: Int) : TimerAction()
    data class SendKeyEvent(val keyCode: Int) : TimerAction()
}

sealed class TimerSchedule {
    data class Interval(val seconds: Long, val count: Int) : TimerSchedule()
    data class FixedTime(val hour: Int, val minute: Int, val repeatDaily: Boolean) : TimerSchedule()
    data class Countdown(val seconds: Long) : TimerSchedule()
}

data class TimerTask(
    val id: String,
    val name: String,
    val schedule: TimerSchedule,
    val actions: List<TimerAction>,
    var running: Boolean = false,
    var executedCount: Int = 0,
)

@Composable
fun TimerDialog(
    client: AdbShellClient?,
    connected: Boolean,
    scope: kotlinx.coroutines.CoroutineScope,
    getOrConnect: ((AdbShellClient?) -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    AdbDialogContent(
        context.getString(R.string.timer_func), BrewWarning, client, connected, scope, getOrConnect, onDismiss,
        icon = null,
        subtitle = context.getString(R.string.adb_tools_timer_subtitle),
    ) { c ->
        var tasks by remember { mutableStateOf(emptyList<TimerTask>()) }
        var selectedTaskId by remember { mutableStateOf<String?>(null) }
        var showTaskEditor by remember { mutableStateOf(false) }
        var editingTask by remember { mutableStateOf<TimerTask?>(null) }

        var newTaskName by remember { mutableStateOf("") }
        var scheduleType by remember { mutableStateOf("interval") }
        var intervalSeconds by remember { mutableStateOf("5") }
        var intervalCount by remember { mutableStateOf("5") }
        var fixedHour by remember { mutableStateOf("8") }
        var fixedMinute by remember { mutableStateOf("0") }
        var repeatDaily by remember { mutableStateOf(false) }
        var countdownSeconds by remember { mutableStateOf("60") }
        var editorActions by remember { mutableStateOf(emptyList<TimerAction>()) }

        var appPackages by remember { mutableStateOf(emptyList<String>()) }
        var appLoading by remember { mutableStateOf(true) }
        val activeJobs by remember { mutableStateOf(mutableStateMapOf<String, Job>()) }
        var selectedActionType by remember { mutableStateOf("notify") }

        LaunchedEffect(Unit) {
            appLoading = true; val pkgs = withContext(Dispatchers.IO) { c.listPackages(false) }; appPackages = pkgs; appLoading = false
        }

        fun postLocalNotification(text: String) {
            try {
                val nm = context.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    val channel = android.app.NotificationChannel("timer_notify", context.getString(R.string.timer_notification_channel), android.app.NotificationManager.IMPORTANCE_HIGH).apply { description = context.getString(R.string.timer_notification_channel_desc) }
                    nm.createNotificationChannel(channel)
                }
                val notification = android.app.Notification.Builder(context, "timer_notify").setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("Rokid").setContentText(text).setAutoCancel(true).setPriority(android.app.Notification.PRIORITY_HIGH).apply { if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) setChannelId("timer_notify") }
                @Suppress("DEPRECATION") if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) notification.setPriority(android.app.Notification.PRIORITY_HIGH)
                nm.notify(System.currentTimeMillis().toInt(), notification.build())
            } catch (e: Exception) { Log.w("Timer", "Local notification failed: ${e.message}") }
        }

        suspend fun executeAction(action: TimerAction) {
            try { when (action) { is TimerAction.SendNotification -> { postLocalNotification(action.content); withContext(Dispatchers.IO) { c.sendNotification(action.title, action.content) } }; is TimerAction.LaunchApp -> withContext(Dispatchers.IO) { c.launchApp(action.packageName) }; is TimerAction.ExecuteShell -> withContext(Dispatchers.IO) { c.executeShellCommand(action.command) }; is TimerAction.Tap -> withContext(Dispatchers.IO) { c.tap(action.x, action.y) }; is TimerAction.SendKeyEvent -> withContext(Dispatchers.IO) { c.sendKeyEvent(action.keyCode) } } } catch (e: Exception) { Log.w("Timer", "executeAction ${action.javaClass.simpleName} failed: ${e.message}") }
        }

        fun runTask(task: TimerTask) {
            val taskId = task.id; if (activeJobs.containsKey(taskId)) return; tasks = tasks.map { t -> if (t.id == taskId) t.copy(running = true) else t }
            val job = scope.launch {
                try { when (task.schedule) { is TimerSchedule.Interval -> { for (i in 0 until task.schedule.count) { if (!activeJobs.containsKey(taskId)) break; task.actions.forEach { executeAction(it) }; tasks = tasks.map { t -> if (t.id == taskId) t.copy(executedCount = t.executedCount + 1) else t }; if (i < task.schedule.count - 1) delay(task.schedule.seconds * 1000L) } }; is TimerSchedule.FixedTime -> { val now = Calendar.getInstance(); val target = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, task.schedule.hour); set(Calendar.MINUTE, task.schedule.minute); set(Calendar.SECOND, 0) }; var delayMs = target.timeInMillis - now.timeInMillis; if (delayMs < 0) delayMs += if (task.schedule.repeatDaily) 24 * 3600 * 1000L else 0L; if (delayMs > 0) delay(delayMs); if (activeJobs.containsKey(taskId)) { task.actions.forEach { executeAction(it) }; tasks = tasks.map { t -> if (t.id == taskId) t.copy(executedCount = t.executedCount + 1) else t }; if (task.schedule.repeatDaily) runTask(task) } }; is TimerSchedule.Countdown -> { delay(task.schedule.seconds * 1000L); if (activeJobs.containsKey(taskId)) { task.actions.forEach { executeAction(it) }; tasks = tasks.map { t -> if (t.id == taskId) t.copy(executedCount = t.executedCount + 1) else t } } } } } finally { activeJobs.remove(taskId); tasks = tasks.map { t -> if (t.id == taskId) t.copy(running = false) else t } }
            }
            activeJobs[taskId] = job
        }

        fun stopTask(taskId: String) { activeJobs[taskId]?.cancel(); activeJobs.remove(taskId); tasks = tasks.map { t -> if (t.id == taskId) t.copy(running = false) else t } }
        fun deleteTask(taskId: String) { stopTask(taskId); tasks = tasks.filter { it.id != taskId }; if (selectedTaskId == taskId) selectedTaskId = null }
        fun resetEditor() { editingTask = null; newTaskName = ""; scheduleType = "interval"; intervalSeconds = "5"; intervalCount = "5"; fixedHour = "8"; fixedMinute = "0"; repeatDaily = false; countdownSeconds = "60"; editorActions = emptyList(); selectedActionType = "notify" }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // ── 新建任务按钮 ──
            Box(Modifier.fillMaxWidth().clip(BrewShapeSmall).background(BrewWarning.copy(alpha = 0.1f)).border(1.dp, BrewWarning.copy(alpha = 0.3f), BrewShapeSmall).clickable { showTaskEditor = true; resetEditor() }.padding(horizontal = 16.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
                Row(verticalAlignment = Alignment.CenterVertically) { Text(context.getString(R.string.timer_new_task), color = BrewWarning, fontSize = 14.sp, fontWeight = FontWeight.Bold) }
            }

            // ── 任务列表 ──
            if (tasks.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { Text(context.getString(R.string.timer_no_tasks), color = BrewMuted, fontSize = 13.sp) }
            } else {
                Column(Modifier.heightIn(max = 430.dp).verticalScroll(rememberScrollState())) {
                    tasks.forEach { task ->
                        val isSelected = selectedTaskId == task.id
                        val scheduleDesc = when (task.schedule) { is TimerSchedule.Interval -> context.getString(R.string.timer_desc_interval, task.schedule.seconds, task.schedule.count); is TimerSchedule.FixedTime -> { val daily = if (task.schedule.repeatDaily) " ${context.getString(R.string.timer_desc_daily)}" else ""; "${String.format("%02d", task.schedule.hour)}:${String.format("%02d", task.schedule.minute)}$daily" }; is TimerSchedule.Countdown -> context.getString(R.string.timer_desc_countdown, task.schedule.seconds) }
                        Row(Modifier.fillMaxWidth().clip(BrewShapeSmall).background(if (isSelected) BrewWarning.copy(alpha = 0.08f) else BrewBg).border(1.dp, if (isSelected) BrewWarning else BrewBorder, BrewShapeSmall).clickable { selectedTaskId = if (isSelected) null else task.id }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) { if (task.running) { Box(Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(BrewRed)); Spacer(Modifier.width(6.dp)) }; Text(task.name, color = if (isSelected) BrewWarning else BrewText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
                                Spacer(Modifier.height(3.dp)); Text("$scheduleDesc · ${context.getString(R.string.timer_actions_count, task.actions.size)} · ${context.getString(R.string.timer_executed_count, task.executedCount)}", color = BrewMuted, fontSize = 12.sp)
                            }
                            Row { if (!task.running) { Box(Modifier.height(36.dp).clip(BrewShapeSmall).background(BrewSuccess.copy(alpha = 0.15f)).border(1.dp, BrewSuccess, BrewShapeSmall).clickable { runTask(task) }.padding(horizontal = 12.dp), contentAlignment = Alignment.Center) { Text("▶", color = BrewSuccess, fontSize = 14.sp) }; Spacer(Modifier.width(6.dp)) }; Box(Modifier.height(36.dp).clip(BrewShapeSmall).background(BrewRed.copy(alpha = 0.15f)).border(1.dp, BrewRed, BrewShapeSmall).clickable { deleteTask(task.id) }.padding(horizontal = 12.dp), contentAlignment = Alignment.Center) { Text("✕", color = BrewRed, fontSize = 14.sp) } }
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                }
            }
        }

        // ── 任务编辑器（弹窗） ──
        if (showTaskEditor) {
            BrewDialog(
                onDismiss = { showTaskEditor = false; resetEditor() },
                title = if (editingTask != null) context.getString(R.string.timer_edit_task) else context.getString(R.string.timer_new_task_title),
                color = BrewWarning,
                properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
            ) {
                Column(modifier = Modifier.fillMaxWidth().heightIn(max = 580.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column { Text(context.getString(R.string.timer_task_name), color = BrewMuted, fontSize = 12.sp); BrutalTextField(value = newTaskName, onValueChange = { newTaskName = it }, placeholder = context.getString(R.string.timer_task_name_hint), color = BrewWarning, modifier = Modifier.fillMaxWidth(), singleLine = true) }
                    Column {
                        Text(context.getString(R.string.timer_schedule_type), color = BrewMuted, fontSize = 12.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("interval" to context.getString(R.string.timer_schedule_interval), "fixed" to context.getString(R.string.timer_schedule_fixed), "countdown" to context.getString(R.string.timer_schedule_countdown)).forEach { (type, label) -> Box(Modifier.height(36.dp).clip(BrewShapeSmall).background(if (scheduleType == type) BrewWarning.copy(alpha = 0.2f) else Color.Transparent).border(1.dp, if (scheduleType == type) BrewWarning else BrewBorder, BrewShapeSmall).clickable { scheduleType = type }.padding(horizontal = 10.dp), contentAlignment = Alignment.Center) { Text(label, color = if (scheduleType == type) BrewWarning else BrewText, fontSize = 12.sp) } } }
                    }
                    when (scheduleType) {
                        "interval" -> Row(verticalAlignment = Alignment.CenterVertically) { Text(context.getString(R.string.timer_interval_label), color = BrewMuted, fontSize = 12.sp); Spacer(Modifier.width(6.dp)); BrutalTextField(value = intervalSeconds, onValueChange = { intervalSeconds = it }, placeholder = "5", color = BrewWarning, modifier = Modifier.width(60.dp), singleLine = true); Spacer(Modifier.width(4.dp)); Text(context.getString(R.string.timer_seconds), color = BrewMuted, fontSize = 12.sp); Spacer(Modifier.width(12.dp)); Text(context.getString(R.string.timer_count_label), color = BrewMuted, fontSize = 12.sp); Spacer(Modifier.width(6.dp)); BrutalTextField(value = intervalCount, onValueChange = { intervalCount = it }, placeholder = "5", color = BrewWarning, modifier = Modifier.width(60.dp), singleLine = true); Spacer(Modifier.width(4.dp)); Text(context.getString(R.string.timer_times), color = BrewMuted, fontSize = 12.sp) }
                        "fixed" -> Row(verticalAlignment = Alignment.CenterVertically) { Text(context.getString(R.string.timer_time_label), color = BrewMuted, fontSize = 12.sp); Spacer(Modifier.width(6.dp)); BrutalTextField(value = fixedHour, onValueChange = { fixedHour = it }, placeholder = "8", color = BrewWarning, modifier = Modifier.width(50.dp), singleLine = true); Spacer(Modifier.width(4.dp)); Text(":", color = BrewText, fontSize = 14.sp); Spacer(Modifier.width(4.dp)); BrutalTextField(value = fixedMinute, onValueChange = { fixedMinute = it }, placeholder = "00", color = BrewWarning, modifier = Modifier.width(50.dp), singleLine = true); Spacer(Modifier.width(12.dp)); Row(verticalAlignment = Alignment.CenterVertically) { androidx.compose.material3.Checkbox(checked = repeatDaily, onCheckedChange = { repeatDaily = it }, colors = androidx.compose.material3.CheckboxDefaults.colors(BrewWarning)); Text(context.getString(R.string.timer_repeat_daily), color = BrewText, fontSize = 12.sp) } }
                        "countdown" -> Row(verticalAlignment = Alignment.CenterVertically) { Text(context.getString(R.string.timer_countdown_label), color = BrewMuted, fontSize = 12.sp); Spacer(Modifier.width(6.dp)); BrutalTextField(value = countdownSeconds, onValueChange = { countdownSeconds = it }, placeholder = "60", color = BrewWarning, modifier = Modifier.width(80.dp), singleLine = true); Spacer(Modifier.width(4.dp)); Text(context.getString(R.string.timer_after_seconds), color = BrewMuted, fontSize = 12.sp) }
                    }
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) { Text(context.getString(R.string.timer_actions_label), color = BrewMuted, fontSize = 12.sp); Spacer(Modifier.weight(1f)); if (editorActions.isNotEmpty()) Text(context.getString(R.string.timer_click_to_delete), color = BrewRed, fontSize = 10.sp) }
                        if (editorActions.isEmpty()) { Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) { Text(context.getString(R.string.timer_no_actions), color = BrewMuted, fontSize = 13.sp) } } else { Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { editorActions.forEachIndexed { i, action -> val label = when (action) { is TimerAction.SendNotification -> "${context.getString(R.string.timer_action_notify)}: ${action.content.take(30)}"; is TimerAction.LaunchApp -> "${context.getString(R.string.timer_action_launch)}: ${action.packageName}"; is TimerAction.ExecuteShell -> "${context.getString(R.string.timer_action_shell)}: ${action.command.take(30)}"; is TimerAction.Tap -> "${context.getString(R.string.timer_action_tap)}: (${action.x}, ${action.y})"; is TimerAction.SendKeyEvent -> "${context.getString(R.string.timer_action_key)}: ${action.keyCode}" }; Row(Modifier.fillMaxWidth().clip(BrewShapeSmall).background(BrewWarning.copy(alpha = 0.05f)).border(1.dp, BrewWarning.copy(alpha = 0.15f), BrewShapeSmall).clickable { editorActions = editorActions.toMutableList().apply { removeAt(i) } }.padding(8.dp), verticalAlignment = Alignment.CenterVertically) { Text("${i + 1}.", color = BrewWarning, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 6.dp)); Text(label, color = BrewText, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)); Text("✕", color = BrewRed.copy(alpha = 0.6f), fontSize = 12.sp) } } } }
                    }
                    Text(context.getString(R.string.timer_action_type), color = BrewMuted, fontSize = 12.sp)
                    Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("notify" to context.getString(R.string.timer_action_notify), "launch" to context.getString(R.string.timer_action_launch), "shell" to context.getString(R.string.timer_action_shell), "tap" to context.getString(R.string.timer_action_tap), "key" to context.getString(R.string.timer_action_key)).forEach { (type, label) -> Box(Modifier.height(36.dp).clip(BrewShapeSmall).background(if (selectedActionType == type) BrewWarning.copy(alpha = 0.2f) else Color.Transparent).border(1.dp, if (selectedActionType == type) BrewWarning else BrewBorder, BrewShapeSmall).clickable { selectedActionType = type }.padding(horizontal = 10.dp), contentAlignment = Alignment.Center) { Text(label, color = if (selectedActionType == type) BrewWarning else BrewText, fontSize = 12.sp) } } }
                    when (selectedActionType) {
                        "notify" -> { var notiTitle by remember { mutableStateOf("") }; var notiContent by remember { mutableStateOf("") }; Row(verticalAlignment = Alignment.CenterVertically) { BrutalTextField(value = notiTitle, onValueChange = { notiTitle = it }, placeholder = context.getString(R.string.timer_notify_title_hint), color = BrewWarning, modifier = Modifier.weight(1f), singleLine = true); Spacer(Modifier.width(6.dp)); BrutalTextField(value = notiContent, onValueChange = { notiContent = it }, placeholder = context.getString(R.string.timer_notify_content_hint), color = BrewWarning, modifier = Modifier.weight(1f), singleLine = true); Spacer(Modifier.width(6.dp)); Box(Modifier.height(36.dp).clip(BrewShapeSmall).background(BrewWarning.copy(alpha = 0.2f)).border(1.dp, BrewWarning, BrewShapeSmall).clickable(enabled = notiContent.isNotBlank()) { editorActions = editorActions + TimerAction.SendNotification(notiTitle.ifBlank { "Rokid" }, notiContent) }.padding(horizontal = 12.dp), contentAlignment = Alignment.Center) { Text("+", color = BrewWarning, fontSize = 18.sp, fontWeight = FontWeight.Bold) } } }
                        "launch" -> { var launchPkg by remember { mutableStateOf("") }; Row(verticalAlignment = Alignment.CenterVertically) { BrutalTextField(value = launchPkg, onValueChange = { launchPkg = it }, placeholder = context.getString(R.string.timer_package_hint), color = BrewWarning, modifier = Modifier.weight(1f), singleLine = true); Spacer(Modifier.width(6.dp)); Box(Modifier.height(36.dp).clip(BrewShapeSmall).background(BrewWarning.copy(alpha = 0.2f)).border(1.dp, BrewWarning, BrewShapeSmall).clickable(enabled = launchPkg.isNotBlank()) { editorActions = editorActions + TimerAction.LaunchApp(launchPkg) }.padding(horizontal = 12.dp), contentAlignment = Alignment.Center) { Text("+", color = BrewWarning, fontSize = 18.sp, fontWeight = FontWeight.Bold) } }; if (appPackages.isNotEmpty()) { Spacer(Modifier.height(4.dp)); Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) { appPackages.take(8).forEach { pkg -> Box(Modifier.height(32.dp).clip(BrewShapeSmall).background(BrewWarning.copy(alpha = 0.05f)).border(1.dp, BrewWarning.copy(alpha = 0.2f), BrewShapeSmall).clickable { launchPkg = pkg }.padding(horizontal = 8.dp), contentAlignment = Alignment.Center) { Text(pkg.substringAfterLast("."), color = BrewMuted, fontSize = 10.sp, maxLines = 1) } } } } }
                        "shell" -> { var shellCmd by remember { mutableStateOf("") }; Row(verticalAlignment = Alignment.CenterVertically) { BrutalTextField(value = shellCmd, onValueChange = { shellCmd = it }, placeholder = context.getString(R.string.timer_command_hint), color = BrewWarning, modifier = Modifier.weight(1f), singleLine = true); Spacer(Modifier.width(6.dp)); Box(Modifier.height(36.dp).clip(BrewShapeSmall).background(BrewWarning.copy(alpha = 0.2f)).border(1.dp, BrewWarning, BrewShapeSmall).clickable(enabled = shellCmd.isNotBlank()) { editorActions = editorActions + TimerAction.ExecuteShell(shellCmd) }.padding(horizontal = 12.dp), contentAlignment = Alignment.Center) { Text("+", color = BrewWarning, fontSize = 18.sp, fontWeight = FontWeight.Bold) } } }
                        "tap" -> { var tapX by remember { mutableStateOf("") }; var tapY by remember { mutableStateOf("") }; Row(verticalAlignment = Alignment.CenterVertically) { Text(context.getString(R.string.timer_coord_x), color = BrewMuted, fontSize = 12.sp); Spacer(Modifier.width(4.dp)); BrutalTextField(value = tapX, onValueChange = { tapX = it }, placeholder = "0", color = BrewWarning, modifier = Modifier.width(60.dp), singleLine = true); Spacer(Modifier.width(8.dp)); Text(context.getString(R.string.timer_coord_y), color = BrewMuted, fontSize = 12.sp); Spacer(Modifier.width(4.dp)); BrutalTextField(value = tapY, onValueChange = { tapY = it }, placeholder = "0", color = BrewWarning, modifier = Modifier.width(60.dp), singleLine = true); Spacer(Modifier.width(6.dp)); Box(Modifier.height(36.dp).clip(BrewShapeSmall).background(BrewWarning.copy(alpha = 0.2f)).border(1.dp, BrewWarning, BrewShapeSmall).clickable(enabled = tapX.isNotBlank() && tapY.isNotBlank()) { editorActions = editorActions + TimerAction.Tap(tapX.toIntOrNull() ?: 0, tapY.toIntOrNull() ?: 0) }.padding(horizontal = 12.dp), contentAlignment = Alignment.Center) { Text("+", color = BrewWarning, fontSize = 18.sp, fontWeight = FontWeight.Bold) } } }
                        "key" -> { var keyCode by remember { mutableStateOf("") }; Row(verticalAlignment = Alignment.CenterVertically) { BrutalTextField(value = keyCode, onValueChange = { keyCode = it }, placeholder = context.getString(R.string.timer_keycode_hint), color = BrewWarning, modifier = Modifier.weight(1f), singleLine = true); Spacer(Modifier.width(6.dp)); Box(Modifier.height(36.dp).clip(BrewShapeSmall).background(BrewWarning.copy(alpha = 0.2f)).border(1.dp, BrewWarning, BrewShapeSmall).clickable(enabled = keyCode.isNotBlank()) { editorActions = editorActions + TimerAction.SendKeyEvent(keyCode.toIntOrNull() ?: 0) }.padding(horizontal = 12.dp), contentAlignment = Alignment.Center) { Text("+", color = BrewWarning, fontSize = 18.sp, fontWeight = FontWeight.Bold) } } }
                    }
                    Box(Modifier.fillMaxWidth().height(44.dp).clip(BrewShapeSmall).background(BrewWarning).clickable(enabled = newTaskName.isNotBlank() && editorActions.isNotEmpty()) { val schedule = when (scheduleType) { "interval" -> TimerSchedule.Interval(intervalSeconds.toLongOrNull() ?: 5L, intervalCount.toIntOrNull() ?: 5); "fixed" -> TimerSchedule.FixedTime(fixedHour.toIntOrNull() ?: 8, fixedMinute.toIntOrNull() ?: 0, repeatDaily); "countdown" -> TimerSchedule.Countdown(countdownSeconds.toLongOrNull() ?: 60L); else -> TimerSchedule.Interval(5L, 5) }; if (editingTask != null) { tasks = tasks.map { t -> if (t.id == editingTask!!.id) t.copy(name = newTaskName, schedule = schedule, actions = editorActions) else t } } else { tasks = tasks + TimerTask(UUID.randomUUID().toString(), newTaskName, schedule, editorActions) }; showTaskEditor = false; resetEditor() }.padding(horizontal = 24.dp), contentAlignment = Alignment.Center) { Text(if (editingTask != null) context.getString(R.string.timer_update_task) else context.getString(R.string.timer_create_task), color = BrewBg, fontSize = 15.sp, fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
}
