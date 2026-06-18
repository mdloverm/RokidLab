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
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import kotlinx.coroutines.Dispatchers
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
    AdbDialogContent(context.getString(R.string.timer_func), BrewWarning, client, connected, scope, getOrConnect, onDismiss) { c ->
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
        
        LaunchedEffect(Unit) {
            appLoading = true
            val pkgs = withContext(Dispatchers.IO) { c.listPackages(false) }
            appPackages = pkgs
            appLoading = false
        }
        
        fun postLocalNotification(text: String) {
            try {
                val nm = context.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    val channel = android.app.NotificationChannel(
                        "timer_notify", context.getString(R.string.timer_notification_channel),
                        android.app.NotificationManager.IMPORTANCE_HIGH
                    ).apply { description = context.getString(R.string.timer_notification_channel_desc) }
                    nm.createNotificationChannel(channel)
                }
                val notification = android.app.Notification.Builder(context, "timer_notify")
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle("Rokid")
                    .setContentText(text)
                    .setAutoCancel(true)
                    .setPriority(android.app.Notification.PRIORITY_HIGH)
                    .apply {
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                            setChannelId("timer_notify")
                        }
                    }
                @Suppress("DEPRECATION")
                if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) {
                    notification.setPriority(android.app.Notification.PRIORITY_HIGH)
                }
                nm.notify(System.currentTimeMillis().toInt(), notification.build())
            } catch (e: Exception) {
                Log.w("Timer", "Local notification failed: ${e.message}")
            }
        }
        
        suspend fun executeAction(action: TimerAction) {
            try {
                when (action) {
                    is TimerAction.SendNotification -> {
                        postLocalNotification(action.content)
                        withContext(Dispatchers.IO) { c.sendNotification(action.title, action.content) }
                    }
                    is TimerAction.LaunchApp -> {
                        withContext(Dispatchers.IO) { c.launchApp(action.packageName) }
                    }
                    is TimerAction.ExecuteShell -> {
                        withContext(Dispatchers.IO) { c.executeShellCommand(action.command) }
                    }
                    is TimerAction.Tap -> {
                        withContext(Dispatchers.IO) { c.tap(action.x, action.y) }
                    }
                    is TimerAction.SendKeyEvent -> {
                        withContext(Dispatchers.IO) { c.sendKeyEvent(action.keyCode) }
                    }
                }
            } catch (e: Exception) {
                Log.w("Timer", "executeAction ${action.javaClass.simpleName} failed: ${e.message}")
            }
        }
        
        fun runTask(task: TimerTask) {
            val taskIndex = tasks.indexOfFirst { it.id == task.id }
            if (taskIndex == -1) return
            
            tasks = tasks.mapIndexed { i, t ->
                if (i == taskIndex) t.copy(running = true, executedCount = 0)
                else t
            }
            
            scope.launch {
                when (task.schedule) {
                    is TimerSchedule.Interval -> {
                        val intervalMs = task.schedule.seconds * 1000
                        val maxCount = task.schedule.count
                        var count = 0
                        
                        while (count < maxCount) {
                            task.actions.forEach { executeAction(it) }
                            count++
                            
                            tasks = tasks.mapIndexed { i, t ->
                                if (i == taskIndex) t.copy(executedCount = count)
                                else t
                            }
                            
                            if (count >= maxCount) break
                            delay(intervalMs)
                        }
                        
                        tasks = tasks.mapIndexed { i, t ->
                            if (i == taskIndex) t.copy(running = false)
                            else t
                        }
                    }
                    
                    is TimerSchedule.FixedTime -> {
                        val calendar = Calendar.getInstance()
                        calendar.set(Calendar.HOUR_OF_DAY, task.schedule.hour)
                        calendar.set(Calendar.MINUTE, task.schedule.minute)
                        calendar.set(Calendar.SECOND, 0)
                        calendar.set(Calendar.MILLISECOND, 0)
                        
                        if (calendar.timeInMillis <= System.currentTimeMillis()) {
                            calendar.add(Calendar.DAY_OF_YEAR, 1)
                        }
                        
                        do {
                            val delayMs = calendar.timeInMillis - System.currentTimeMillis()
                            if (delayMs > 0) delay(delayMs)
                            
                            task.actions.forEach { executeAction(it) }
                            
                            tasks = tasks.mapIndexed { i, t ->
                                if (i == taskIndex) t.copy(executedCount = t.executedCount + 1)
                                else t
                            }
                            
                            if (!task.schedule.repeatDaily) break
                            calendar.add(Calendar.DAY_OF_YEAR, 1)
                        } while (true)
                        
                        tasks = tasks.mapIndexed { i, t ->
                            if (i == taskIndex) t.copy(running = false)
                            else t
                        }
                    }
                    
                    is TimerSchedule.Countdown -> {
                        delay(task.schedule.seconds * 1000)
                        task.actions.forEach { executeAction(it) }
                        
                        tasks = tasks.mapIndexed { i, t ->
                            if (i == taskIndex) t.copy(running = false, executedCount = 1)
                            else t
                        }
                    }
                }
            }
        }
        
        var tempNotifyTitle by remember { mutableStateOf("Rokid") }
        var tempNotifyContent by remember { mutableStateOf("") }
        var tempLaunchApp by remember { mutableStateOf("") }
        var tempShellCommand by remember { mutableStateOf("") }
        var tempTapX by remember { mutableStateOf("500") }
        var tempTapY by remember { mutableStateOf("500") }
        var tempKeyCode by remember { mutableStateOf("26") }
        
        fun resetEditor() {
            newTaskName = ""
            scheduleType = "interval"
            intervalSeconds = "5"
            intervalCount = "5"
            fixedHour = "8"
            fixedMinute = "0"
            repeatDaily = false
            countdownSeconds = "60"
            editorActions = emptyList()
            editingTask = null
            tempNotifyTitle = "Rokid"
            tempNotifyContent = ""
            tempLaunchApp = ""
            tempShellCommand = ""
            tempTapX = "500"
            tempTapY = "500"
            tempKeyCode = "26"
        }
        
        fun createTask() {
            val schedule = when (scheduleType) {
                "interval" -> TimerSchedule.Interval(
                    intervalSeconds.toLongOrNull() ?: 5,
                    intervalCount.toIntOrNull() ?: 5
                )
                "fixed" -> TimerSchedule.FixedTime(
                    fixedHour.toIntOrNull() ?: 8,
                    fixedMinute.toIntOrNull() ?: 0,
                    repeatDaily
                )
                "countdown" -> TimerSchedule.Countdown(countdownSeconds.toLongOrNull() ?: 60)
                else -> TimerSchedule.Interval(5, 5)
            }
            
            val newTask = TimerTask(
                id = UUID.randomUUID().toString(),
                name = newTaskName.ifEmpty { context.getString(R.string.timer_default_name, tasks.size + 1) },
                schedule = schedule,
                actions = editorActions,
                running = false,
                executedCount = 0,
            )
            
            tasks = tasks + newTask
            resetEditor()
            showTaskEditor = false
        }
        
        fun deleteTask(taskId: String) {
            tasks = tasks.filter { it.id != taskId }
        }
        
        fun addAction(type: String) {
            val action = when (type) {
                "notify" -> TimerAction.SendNotification(tempNotifyTitle, tempNotifyContent)
                "launch" -> TimerAction.LaunchApp(tempLaunchApp)
                "shell" -> TimerAction.ExecuteShell(tempShellCommand)
                "tap" -> TimerAction.Tap(tempTapX.toIntOrNull() ?: 500, tempTapY.toIntOrNull() ?: 500)
                "key" -> TimerAction.SendKeyEvent(tempKeyCode.toIntOrNull() ?: 26)
                else -> return
            }
            editorActions = editorActions + action
        }
        
        Column(
            modifier = Modifier.fillMaxWidth()
                .heightIn(max = 650.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier.fillMaxWidth().clip(BrewShapeMedium)
                    .background(BrewWarning.copy(alpha = 0.06f))
                    .border(1.dp, BrewWarning.copy(alpha = 0.2f), BrewShapeMedium)
                    .padding(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(context.getString(R.string.timer_task_list), color = BrewWarning, fontSize = 15.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    Box(
                        Modifier.height(36.dp).clip(BrewShapeSmall)
                            .background(BrewWarning.copy(alpha = 0.15f))
                            .border(1.dp, BrewWarning, BrewShapeSmall)
                            .clickable { showTaskEditor = true; resetEditor() }
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(context.getString(R.string.timer_new_task), color = BrewWarning, fontSize = 13.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                    }
                }

                Spacer(Modifier.height(8.dp))

                if (tasks.isEmpty()) {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        Text(context.getString(R.string.timer_no_tasks), color = BrewMuted, fontSize = 13.sp)
                    }
                } else {
                    Column {
                        tasks.forEach { task ->
                            val isSelected = selectedTaskId == task.id
                            val scheduleDesc = when (task.schedule) {
                                is TimerSchedule.Interval -> context.getString(R.string.timer_desc_interval, task.schedule.seconds, task.schedule.count)
                                is TimerSchedule.FixedTime -> {
                                    val daily = if (task.schedule.repeatDaily) " ${context.getString(R.string.timer_desc_daily)}" else ""
                                    "${task.schedule.hour}:${String.format("%02d", task.schedule.minute)}$daily"
                                }
                                is TimerSchedule.Countdown -> context.getString(R.string.timer_desc_countdown, task.schedule.seconds)
                            }
                            
                            Row(
                                Modifier.fillMaxWidth().clip(BrewShapeSmall)
                                    .background(if (isSelected) BrewWarning.copy(alpha = 0.1f) else BrewBg)
                                    .border(1.dp, if (isSelected) BrewWarning else BrewBorder, BrewShapeSmall)
                                    .clickable { selectedTaskId = if (isSelected) null else task.id }
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (task.running) {
                                            Box(Modifier.size(6.dp).clip(RoundedCornerShape(50)).background(BrewRed))
                                            Spacer(Modifier.width(6.dp))
                                        }
                                        Text(task.name, color = if (isSelected) BrewWarning else BrewText, fontSize = 13.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                                    }
                                    Spacer(Modifier.height(2.dp))
                                    Text("$scheduleDesc · ${context.getString(R.string.timer_actions_count, task.actions.size)} · ${context.getString(R.string.timer_executed_count, task.executedCount)}", color = BrewMuted, fontSize = 11.sp)
                                }
                                
                                Row {
                                    if (!task.running) {
                                        Box(
                                            Modifier.height(30.dp).clip(BrewShapeSmall)
                                                .background(BrewSuccess.copy(alpha = 0.15f))
                                                .border(1.dp, BrewSuccess, BrewShapeSmall)
                                                .clickable { runTask(task) }
                                                .padding(horizontal = 10.dp),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Text("▶", color = BrewSuccess, fontSize = 12.sp)
                                        }
                                        Spacer(Modifier.width(6.dp))
                                    }
                                    Box(
                                        Modifier.height(30.dp).clip(BrewShapeSmall)
                                            .background(BrewRed.copy(alpha = 0.15f))
                                            .border(1.dp, BrewRed, BrewShapeSmall)
                                            .clickable { deleteTask(task.id) }
                                            .padding(horizontal = 10.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text("✕", color = BrewRed, fontSize = 12.sp)
                                    }
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                        }
                    }
                }
            }
            
            if (showTaskEditor) {
                BrewDialog(
                    onDismiss = { showTaskEditor = false; resetEditor() },
                    title = if (editingTask != null) context.getString(R.string.timer_edit_task) else context.getString(R.string.timer_new_task_title),
                    titleColor = BrewWarning,
                    properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth()
                            .heightIn(max = 600.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column {
                            Text(context.getString(R.string.timer_task_name), color = BrewMuted, fontSize = 12.sp)
                            BrutalTextField(
                                value = newTaskName, onValueChange = { newTaskName = it },
                                placeholder = context.getString(R.string.timer_task_name_hint), color = BrewWarning,
                                modifier = Modifier.fillMaxWidth(), singleLine = true,
                            )
                        }
                        
                        Column {
                            Text(context.getString(R.string.timer_schedule_type), color = BrewMuted, fontSize = 12.sp)
                            Row {
                                listOf(
                                    "interval" to context.getString(R.string.timer_schedule_interval),
                                    "fixed" to context.getString(R.string.timer_schedule_fixed),
                                    "countdown" to context.getString(R.string.timer_schedule_countdown)
                                ).forEach { (type, label) ->
                                    Box(
                                        Modifier.height(36.dp).clip(BrewShapeSmall)
                                            .background(if (scheduleType == type) BrewWarning.copy(alpha = 0.2f) else Color.Transparent)
                                            .border(1.dp, if (scheduleType == type) BrewWarning else BrewBorder, BrewShapeSmall)
                                            .clickable { scheduleType = type }
                                            .padding(horizontal = 10.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(label, color = if (scheduleType == type) BrewWarning else BrewText, fontSize = 12.sp)
                                    }
                                    Spacer(Modifier.width(6.dp))
                                }
                            }
                        }
                        
                        when (scheduleType) {
                            "interval" -> {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(context.getString(R.string.timer_interval_label), color = BrewMuted, fontSize = 12.sp)
                                    Spacer(Modifier.width(6.dp))
                                    BrutalTextField(
                                        value = intervalSeconds, onValueChange = { intervalSeconds = it },
                                        placeholder = "5", color = BrewWarning,
                                        modifier = Modifier.width(60.dp), singleLine = true,
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(context.getString(R.string.timer_seconds), color = BrewMuted, fontSize = 12.sp)
                                    Spacer(Modifier.width(12.dp))
                                    Text(context.getString(R.string.timer_count_label), color = BrewMuted, fontSize = 12.sp)
                                    Spacer(Modifier.width(6.dp))
                                    BrutalTextField(
                                        value = intervalCount, onValueChange = { intervalCount = it },
                                        placeholder = "5", color = BrewWarning,
                                        modifier = Modifier.width(60.dp), singleLine = true,
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(context.getString(R.string.timer_times), color = BrewMuted, fontSize = 12.sp)
                                }
                            }
                            "fixed" -> {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(context.getString(R.string.timer_time_label), color = BrewMuted, fontSize = 12.sp)
                                    Spacer(Modifier.width(6.dp))
                                    BrutalTextField(
                                        value = fixedHour, onValueChange = { fixedHour = it },
                                        placeholder = "8", color = BrewWarning,
                                        modifier = Modifier.width(50.dp), singleLine = true,
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(":", color = BrewText, fontSize = 14.sp)
                                    Spacer(Modifier.width(4.dp))
                                    BrutalTextField(
                                        value = fixedMinute, onValueChange = { fixedMinute = it },
                                        placeholder = "00", color = BrewWarning,
                                        modifier = Modifier.width(50.dp), singleLine = true,
                                    )
                                    Spacer(Modifier.width(12.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        androidx.compose.material3.Checkbox(
                                            checked = repeatDaily,
                                            onCheckedChange = { repeatDaily = it },
                                            colors = androidx.compose.material3.CheckboxDefaults.colors(BrewWarning),
                                        )
                                        Text(context.getString(R.string.timer_repeat_daily), color = BrewText, fontSize = 12.sp)
                                    }
                                }
                            }
                            "countdown" -> {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(context.getString(R.string.timer_countdown_label), color = BrewMuted, fontSize = 12.sp)
                                    Spacer(Modifier.width(6.dp))
                                    BrutalTextField(
                                        value = countdownSeconds, onValueChange = { countdownSeconds = it },
                                        placeholder = "60", color = BrewWarning,
                                        modifier = Modifier.width(80.dp), singleLine = true,
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Text(context.getString(R.string.timer_after_seconds), color = BrewMuted, fontSize = 12.sp)
                                }
                            }
                        }
                        
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(context.getString(R.string.timer_actions_label), color = BrewMuted, fontSize = 12.sp)
                                Spacer(Modifier.weight(1f))
                                if (editorActions.isNotEmpty()) {
                                    Text(context.getString(R.string.timer_click_to_delete), color = BrewRed, fontSize = 10.sp)
                                }
                            }
                            
                            if (editorActions.isEmpty()) {
                                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                    Text(context.getString(R.string.timer_no_actions), color = BrewMuted, fontSize = 12.sp)
                                }
                            } else {
                                Column {
                                    editorActions.forEachIndexed { idx, action ->
                                        val actionDesc = when (action) {
                                            is TimerAction.SendNotification -> context.getString(R.string.timer_action_notify, action.title)
                                            is TimerAction.LaunchApp -> context.getString(R.string.timer_action_launch, action.packageName)
                                            is TimerAction.ExecuteShell -> context.getString(R.string.timer_action_shell)
                                            is TimerAction.Tap -> context.getString(R.string.timer_action_tap, action.x, action.y)
                                            is TimerAction.SendKeyEvent -> context.getString(R.string.timer_action_key, action.keyCode)
                                        }
                                        Row(
                                            Modifier.fillMaxWidth().clip(BrewShapeSmall)
                                                .background(BrewBg)
                                                .border(1.dp, BrewBorder, BrewShapeSmall)
                                                .padding(8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text("${idx + 1}.", color = BrewInfo, fontSize = 12.sp)
                                            Spacer(Modifier.width(6.dp))
                                            Text(actionDesc, color = BrewText, fontSize = 12.sp, modifier = Modifier.weight(1f))
                                            Text("✕", color = BrewRed, fontSize = 10.sp, modifier = Modifier.clickable {
                                                editorActions = editorActions.filterIndexed { i, _ -> i != idx }
                                            })
                                        }
                                        Spacer(Modifier.height(4.dp))
                                    }
                                }
                            }
                            
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.SpaceEvenly) {
                                listOf("notify" to context.getString(R.string.timer_action_notify_btn), "launch" to context.getString(R.string.timer_action_launch_btn), "shell" to context.getString(R.string.timer_action_shell_btn), "tap" to context.getString(R.string.timer_action_tap_btn), "key" to context.getString(R.string.timer_action_key_btn)).forEach { (type, label) ->
                                    Box(
                                        Modifier.height(32.dp).clip(BrewShapeSmall)
                                            .background(BrewInfo.copy(alpha = 0.1f))
                                            .border(1.dp, BrewInfo, BrewShapeSmall)
                                            .clickable {
                                                when (type) {
                                                    "notify" -> {
                                                        if (tempNotifyContent.isNotBlank()) addAction(type)
                                                    }
                                                    "launch" -> {
                                                        if (tempLaunchApp.isNotEmpty()) addAction(type)
                                                    }
                                                    "shell" -> {
                                                        if (tempShellCommand.isNotBlank()) addAction(type)
                                                    }
                                                    "tap" -> addAction(type)
                                                    "key" -> addAction(type)
                                                }
                                            }
                                            .padding(horizontal = 8.dp),
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text("+ $label", color = BrewInfo, fontSize = 11.sp)
                                    }
                                }
                            }
                            
                            // action input area - always visible to support adding multiple actions
                            Spacer(Modifier.height(8.dp))
                                Column {
                                    Column {
                                        Row {
                                            BrutalTextField(
                                                        value = tempNotifyTitle, onValueChange = { tempNotifyTitle = it },
                                                        placeholder = context.getString(R.string.timer_notify_title_hint), color = BrewInfo,
                                                        modifier = Modifier.weight(1f), singleLine = true,
                                                    )
                                                    Spacer(Modifier.width(6.dp))
                                                    BrutalTextField(
                                                        value = tempNotifyContent, onValueChange = { tempNotifyContent = it },
                                                        placeholder = context.getString(R.string.timer_notify_content_hint), color = BrewInfo,
                                                        modifier = Modifier.weight(2f), singleLine = true,
                                                    )
                                        }
                                    }
                                    
                                    Spacer(Modifier.height(6.dp))
                                    
                                    var showAppPicker by remember { mutableStateOf(false) }
                                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                                        .background(BrewBg).border(1.dp, BrewBorder, RoundedCornerShape(8.dp))
                                        .clickable { showAppPicker = true }
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text(
                                            if (tempLaunchApp.isEmpty()) context.getString(R.string.timer_select_app) else tempLaunchApp,
                                            color = if (tempLaunchApp.isEmpty()) BrewMuted else BrewText,
                                            fontSize = 12.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        )
                                        Text("▼", color = BrewInfo, fontSize = 10.sp)
                                    }
                                    
                                    if (showAppPicker) {
                                        Box(
                                            Modifier.fillMaxWidth().heightIn(max = 150.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(BrewPanel)
                                                .border(1.dp, BrewBorder, RoundedCornerShape(8.dp))
                                                .verticalScroll(rememberScrollState()),
                                        ) {
                                            Column {
                                                appPackages.forEach { pkg ->
                                                    Row(
                                                        Modifier.fillMaxWidth().clip(BrewShapeSmall)
                                                            .background(if (pkg == tempLaunchApp) BrewInfo.copy(alpha = 0.1f) else Color.Transparent)
                                                            .clickable { tempLaunchApp = pkg; showAppPicker = false }
                                                            .padding(horizontal = 10.dp, vertical = 6.dp),
                                                        verticalAlignment = Alignment.CenterVertically,
                                                    ) {
                                                        Text(pkg, color = if (pkg == tempLaunchApp) BrewInfo else BrewText, fontSize = 12.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                        if (pkg == tempLaunchApp) Text("✓", color = BrewInfo, fontSize = 11.sp)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    
                                    Spacer(Modifier.height(6.dp))
                                    
                                    BrutalTextField(
                                        value = tempShellCommand, onValueChange = { tempShellCommand = it },
                                        placeholder = context.getString(R.string.timer_shell_hint), color = BrewInfo,
                                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                                    )
                                    
                                    Spacer(Modifier.height(6.dp))
                                    
                                    Row {
                                        Text(context.getString(R.string.timer_tap_coords), color = BrewMuted, fontSize = 11.sp)
                                        Spacer(Modifier.width(6.dp))
                                        BrutalTextField(
                                            value = tempTapX, onValueChange = { tempTapX = it },
                                            placeholder = "X", color = BrewInfo,
                                            modifier = Modifier.width(60.dp), singleLine = true,
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text(",", color = BrewMuted, fontSize = 11.sp)
                                        Spacer(Modifier.width(4.dp))
                                        BrutalTextField(
                                            value = tempTapY, onValueChange = { tempTapY = it },
                                            placeholder = "Y", color = BrewInfo,
                                            modifier = Modifier.width(60.dp), singleLine = true,
                                        )
                                        Spacer(Modifier.width(12.dp))
                                        Text(context.getString(R.string.timer_key_code), color = BrewMuted, fontSize = 11.sp)
                                        Spacer(Modifier.width(6.dp))
                                        BrutalTextField(
                                            value = tempKeyCode, onValueChange = { tempKeyCode = it },
                                            placeholder = "26", color = BrewInfo,
                                            modifier = Modifier.width(60.dp), singleLine = true,
                                        )
                                    }
                                }
                        }
                        
                        Spacer(Modifier.height(8.dp))
                        Box(
                            Modifier.fillMaxWidth().height(44.dp).clip(BrewShapeMedium)
                                .background(BrewWarning.copy(alpha = 0.15f))
                                .border(1.dp, BrewWarning, BrewShapeMedium)
                                .clickable {
                                    if (editorActions.isEmpty()) return@clickable
                                    createTask()
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(context.getString(R.string.timer_save), color = BrewWarning, fontSize = 14.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                        }
                        
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
            
            Spacer(Modifier.height(8.dp))
        }
    }
}