package com.rokidlab.phone.ai

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.Settings
import androidx.core.content.ContextCompat
import android.util.Log
import java.util.Calendar
import java.util.TimeZone
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 手机端工具集（AI function calling 执行体）：通讯录 / 拨号 / 闹钟 / 打开手机应用 /
 * 手机状态 / 音量 / 日历日程。
 *
 * 设计约束：
 * - 拨号：ACTION_CALL 直接拨出（需 CALL_PHONE，启动时申请；语音指令即授权），
 *   未授权/被 ROM 限制时退回 ACTION_DIAL 打开拨号盘
 * - 通讯录/日历需要运行时权限，缺失时返回带引导的说明文本（模型如实转告用户去授权）
 * - 所有方法同步阻塞（Agent 工具循环已在后台线程执行），返回给模型的中文结果文本
 */
object PhoneTools {
    private const val TAG = "PhoneTools"

    // ═══════════════════════════ 权限 ═══════════════════════════

    private fun has(context: Context, perm: String): Boolean =
        ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED

    private fun missingPermTip(what: String): String =
        "需要$what 权限：请打开手机「设置 → 应用 → RokidLab / 乐奇实验室 → 权限」，开启「$what」后重新试一次"

    // ═══════════════════════════ 通讯录 ═══════════════════════════

    data class Contact(val name: String, val number: String)

    /** 按姓名模糊搜索通讯录（名称包含即命中，双向包含），返回去重后的前 5 条 */
    fun searchContacts(context: Context, name: String): List<Contact> {
        if (!has(context, Manifest.permission.READ_CONTACTS)) return emptyList()
        if (name.isBlank()) return emptyList()
        val q = name.trim()
        val result = LinkedHashMap<String, Contact>()
        try {
            context.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                ),
                null, null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC",
            )?.use { c ->
                while (c.moveToNext() && result.size < 5) {
                    val nm = c.getString(0) ?: continue
                    val num = c.getString(1) ?: continue
                    if (nm.contains(q) || q.contains(nm)) {
                        result.putIfAbsent("$nm|$num", Contact(nm, num))
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "searchContacts failed: ${e.message}")
        }
        return result.values.toList()
    }

    /**
     * 拨打电话：参数是手机号直接拨；是姓名则查通讯录（唯一命中拨出，多个命中返回候选）。
     * 已授予 CALL_PHONE → ACTION_CALL **直接拨出**（用户说「给 X 打电话」即授权，无需任何确认层）；
     * 未授权/被 ROM 限制 → 退回 ACTION_DIAL 打开拨号盘。
     */
    fun dialPhone(context: Context, nameOrNumber: String): String {
        val input = nameOrNumber.trim()
        if (input.isEmpty()) return "请提供要拨打的联系人姓名或手机号"
        val isNumber = input.matches(Regex("^[+\\d][\\d\\s-]{2,}$"))
        if (isNumber) {
            return launchDialer(context, input, input)
        }
        if (!has(context, Manifest.permission.READ_CONTACTS)) {
            return missingPermTip("通讯录") + "，或直接告诉我手机号码"
        }
        val hits = searchContacts(context, input)
        return when {
            hits.isEmpty() -> "通讯录中没有找到「$input」，请确认姓名或直接告诉我手机号"
            hits.size == 1 -> launchDialer(context, hits[0].number, hits[0].name)
            else -> "通讯录里有多个「$input」：\n" + hits.mapIndexed { i, c ->
                "[${i + 1}] ${c.name}（${c.number}）"
            }.joinToString("\n") + "\n请告诉我要拨哪一个"
        }
    }

    private fun launchDialer(context: Context, number: String, label: String): String {
        val tel = Uri.parse("tel:${number.replace(" ", "")}")
        val newTask = Intent.FLAG_ACTIVITY_NEW_TASK
        // 语音指令即授权（call_phone 已降为 LOCAL_SIDE_EFFECT，不再走眼镜确认闸门）：
        // 只要已授 CALL_PHONE 就 ACTION_CALL 直接拨出；未授权或被 ROM 限制才退回拨号盘。
        val canCall = has(context, Manifest.permission.CALL_PHONE)
        return try {
            context.startActivity(Intent(if (canCall) Intent.ACTION_CALL else Intent.ACTION_DIAL, tel).apply {
                addFlags(newTask)
            })
            if (canCall) {
                "正在拨打：$number（$label）"
            } else {
                "已打开拨号盘，号码：$number（$label），请按一下拨出（授予「电话」权限后我可直接拨出去）"
            }
        } catch (e: Exception) {
            // 部分国产 ROM 对 ACTION_CALL 有额外限制（要求默认拨号器等）→ 兜底退回拨号盘
            val fallbackOk = runCatching {
                context.startActivity(Intent(Intent.ACTION_DIAL, tel).apply { addFlags(newTask) })
            }.isSuccess
            if (fallbackOk) {
                "已打开拨号盘，号码：$number（$label），请在手机上确认拨出"
            } else {
                "拨号失败：${e.message}"
            }
        }
    }

    // ═══════════════════════════ 闹钟 ═══════════════════════════

    /**
     * 在手机上设置闹钟/倒计时。
     * @param message 闹钟标签/提醒内容
     * @param hour 24 小时制小时（绝对时间闹钟时必传，0-23）
     * @param minute 分钟（绝对时间闹钟时必传，0-59）
     * @param minutesFromNow 相对分钟数（倒计时时传，如「30 分钟后叫我」→ 30；与 hour/minute 二选一）
     */
    fun setPhoneAlarm(
        context: Context,
        message: String,
        hour: Int? = null,
        minute: Int? = null,
        minutesFromNow: Long? = null,
    ): String {
        val label = message.ifBlank { "闹钟" }
        return try {
            if (hour != null && minute != null) {
                if (hour !in 0..23 || minute !in 0..59) return "时间超出范围（00:00 ~ 23:59）"
                val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                    putExtra(AlarmClock.EXTRA_MESSAGE, label)
                    putExtra(AlarmClock.EXTRA_HOUR, hour)
                    putExtra(AlarmClock.EXTRA_MINUTES, minute)
                    putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                "已设置手机闹钟：${String.format(Locale.CHINA, "%02d:%02d", hour, minute)}（$label）"
            } else if (minutesFromNow != null && minutesFromNow > 0) {
                val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                    putExtra(AlarmClock.EXTRA_MESSAGE, label)
                    putExtra(AlarmClock.EXTRA_LENGTH, minutesFromNow * 60)
                    putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                "已设置手机倒计时：$minutesFromNow 分钟后提醒（$label）"
            } else {
                "请提供绝对时间（hour/minute）或相对分钟数（minutesFromNow）之一"
            }
        } catch (e: Exception) {
            "设置闹钟失败：${e.message}（手机可能没有自带时钟应用）"
        }
    }

    // ═══════════════════════════ 手机应用 ═══════════════════════════

    /** 按应用名打开手机上的应用（匹配启动器可见应用的应用标签） */
    fun openPhoneApp(context: Context, appName: String): String {
        val q = appName.trim()
        if (q.isEmpty()) return "请提供要打开的应用名称"
        return try {
            val pm = context.packageManager
            val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            // label → packageName（取带启动入口的第三方+系统入口应用）
            val candidates = LinkedHashMap<String, String>()
            pm.queryIntentActivities(launcherIntent, 0).forEach { ri ->
                val label = runCatching { ri.loadLabel(pm).toString() }.getOrDefault("")
                if (label.isNotBlank()) candidates.putIfAbsent(label, ri.activityInfo.packageName)
            }
            val pkg = candidates[q]
                ?: candidates.keys.firstOrNull { it.contains(q, ignoreCase = true) || q.contains(it, ignoreCase = true) }
            if (pkg == null) {
                val sample = candidates.keys.filter { !it.contains("android") }.take(12).joinToString("、")
                return "手机上没有找到应用「$q」。可打开的应用示例：$sample"
            }
            val launch = pm.getLaunchIntentForPackage(pkg) ?: return "应用「$q」无法启动"
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(launch)
            "已在手机上打开「$q」"
        } catch (e: Exception) {
            "打开应用失败：${e.message}"
        }
    }

    // ═══════════════════════════ 手机状态 / 音量 ═══════════════════════════

    /** 手机状态：电量/充电状态、媒体音量、屏幕亮度 */
    fun getPhoneStatus(context: Context): String {
        return try {
            val ifilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val battery = context.registerReceiver(null, ifilter)
            val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
            val pct = if (level >= 0 && scale > 0) level * 100 / scale else -1

            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val curVol = am.getStreamVolume(AudioManager.STREAM_MUSIC)

            val brightness = try {
                Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
            } catch (_: Exception) {
                -1
            }
            buildString {
                append("手机电量：")
                append(if (pct >= 0) "$pct%（" else "未知（")
                append(if (charging) "充电中" else "未充电")
                append("）\n媒体音量：$curVol/$maxVol")
                append("\n屏幕亮度：")
                append(if (brightness >= 0) "${brightness * 100 / 255}%" else "未知")
            }
        } catch (e: Exception) {
            "查询手机状态失败：${e.message}"
        }
    }

    /** 设置媒体音量（百分比 0~100） */
    fun setPhoneVolume(context: Context, volume: Int): String {
        if (volume !in 0..100) return "音量需在 0~100 之间"
        return try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val target = Math.round(volume / 100.0 * maxVol).toInt().coerceIn(0, maxVol)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
            "已把媒体音量设为 $volume%（$target/$maxVol）"
        } catch (e: Exception) {
            "设置音量失败：${e.message}"
        }
    }

    // ═══════════════════════════ 日历 ═══════════════════════════

    /** 解析日期词 → 当天 00:00 的 Calendar；失败返回 null（统一清零时分秒） */
    private fun parseDayStart(date: String?): Calendar? {
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val d = date?.trim()?.lowercase()
        return when {
            d.isNullOrEmpty() || d == "today" || d == "今天" -> cal
            d == "tomorrow" || d == "明天" -> cal.also { it.add(Calendar.DAY_OF_YEAR, 1) }
            d == "后天" -> cal.also { it.add(Calendar.DAY_OF_YEAR, 2) }
            d.matches(Regex("^\\d{4}-\\d{1,2}-\\d{1,2}$")) -> {
                val parts = d.split("-").map { it.toInt() }
                cal.set(parts[0], parts[1] - 1, parts[2])
                cal
            }
            d.matches(Regex("^\\d{1,2}-\\d{1,2}$")) -> {
                // 「9-10」默认当年
                val parts = d.split("-").map { it.toInt() }
                cal.set(Calendar.MONTH, parts[0] - 1)
                cal.set(Calendar.DAY_OF_MONTH, parts[1])
                cal
            }
            else -> null
        }
    }

    /**
     * 查询某天的日程。
     * @param date today（默认）/ tomorrow / YYYY-MM-DD / MM-DD
     */
    fun queryCalendar(context: Context, date: String?): String {
        if (!has(context, Manifest.permission.READ_CALENDAR)) return missingPermTip("日历")
        val dayStart = parseDayStart(date) ?: return "日期格式不正确，请用 today/tomorrow 或 YYYY-MM-DD"
        val dayEnd = dayStart.clone() as Calendar
        dayEnd.add(Calendar.DAY_OF_YEAR, 1)
        val dayLabel = SimpleDateFormat("M月d日 EEEE", Locale.CHINA).format(dayStart.time)
        return try {
            val builder = CalendarContract.Instances.query(
                context.contentResolver,
                arrayOf(
                    CalendarContract.Instances.TITLE,
                    CalendarContract.Instances.BEGIN,
                    CalendarContract.Instances.END,
                    CalendarContract.Instances.ALL_DAY,
                    CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
                ),
                dayStart.timeInMillis,
                dayEnd.timeInMillis,
            )
            val events = mutableListOf<String>()
            builder.use { c ->
                val tf = SimpleDateFormat("HH:mm", Locale.CHINA)
                while (c.moveToNext()) {
                    val title = c.getString(0) ?: "(无标题)"
                    val allDay = c.getInt(3) == 1
                    val time = if (allDay) "全天" else "${tf.format(c.getLong(1))} ~ ${tf.format(c.getLong(2))}"
                    val calName = c.getString(4) ?: ""
                    events.add("- $title（$time${if (calName.isNotBlank()) "，$calName" else ""}）")
                }
            }
            if (events.isEmpty()) {
                "「$dayLabel」没有任何日程安排"
            } else {
                "「$dayLabel」共有 ${events.size} 个日程：\n" + events.joinToString("\n")
            }
        } catch (e: Exception) {
            "查询日程失败：${e.message}"
        }
    }

    /**
     * 在手机默认日历创建日程。
     * @param title 日程标题（必填）
     * @param date 日期：today（默认）/ tomorrow / YYYY-MM-DD / MM-DD
     * @param startTime 开始时间 HH:mm（24 小时制，必填）
     * @param durationMinutes 时长（分钟，默认 60）
     * @param note 备注（可选）
     */
    fun addCalendarEvent(
        context: Context,
        title: String,
        date: String?,
        startTime: String,
        durationMinutes: Int = 60,
        note: String? = null,
    ): String {
        if (title.isBlank()) return "请提供日程标题"
        if (!has(context, Manifest.permission.WRITE_CALENDAR)) return missingPermTip("日历")
        val dayStart = parseDayStart(date) ?: return "日期格式不正确，请用 today/tomorrow 或 YYYY-MM-DD"
        val m = Regex("^(\\d{1,2}):(\\d{1,2})$").find(startTime.trim())
            ?: return "开始时间格式不正确，请用 24 小时制 HH:mm，例如 14:30"
        val hour = m.groupValues[1].toInt()
        val minute = m.groupValues[2].toInt()
        if (hour !in 0..23 || minute !in 0..59) return "时间超出范围（00:00 ~ 23:59）"
        val begin = dayStart.clone() as Calendar
        begin.set(Calendar.HOUR_OF_DAY, hour)
        begin.set(Calendar.MINUTE, minute)
        // 「今天 14:30」已过时自动顺延到明天（语音场景用户默认指的是下一个未来时刻）
        if (date.isNullOrBlank() || date.trim().equals("today", ignoreCase = true) || date.trim() == "今天") {
            if (begin.timeInMillis <= System.currentTimeMillis()) begin.add(Calendar.DAY_OF_YEAR, 1)
        }
        val end = begin.clone() as Calendar
        end.add(Calendar.MINUTE, durationMinutes.coerceIn(5, 24 * 60))
        return try {
            // 选择第一个可见日历（优先主日历）
            var calId = -1L
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.IS_PRIMARY),
                CalendarContract.Calendars.VISIBLE + "=1",
                null, null,
            )?.use { c ->
                var first = -1L
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    if (first < 0) first = id
                    if (c.getInt(1) == 1) { calId = id; break }
                }
                if (calId < 0 && first >= 0) calId = first
            }
            if (calId < 0) return "手机上没有可用的日历账户，请先在系统日历中登录账户"
            val values = android.content.ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calId)
                put(CalendarContract.Events.TITLE, title)
                put(CalendarContract.Events.DESCRIPTION, note ?: "")
                put(CalendarContract.Events.DTSTART, begin.timeInMillis)
                put(CalendarContract.Events.DTEND, end.timeInMillis)
                put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
            }
            val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            if (uri != null) {
                val df = SimpleDateFormat("M月d日 HH:mm", Locale.CHINA)
                "已创建日程「$title」：${df.format(begin.time)} 开始，时长 $durationMinutes 分钟"
            } else {
                "创建日程失败，请稍后重试"
            }
        } catch (e: Exception) {
            "创建日程失败：${e.message}"
        }
    }
}
