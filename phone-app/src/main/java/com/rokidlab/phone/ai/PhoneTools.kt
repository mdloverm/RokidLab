package com.rokidlab.phone.ai

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.Settings
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import android.util.Log
import com.rokidlab.phone.R
import com.rokidlab.phone.permission.AppPermission
import com.rokidlab.phone.permission.PermissionBridge
import java.util.Calendar
import java.util.TimeZone
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 手机端工具集（AI function calling 执行体）：通讯录 / 拨号 / 短信 / 剪贴板 / 闹钟 /
 * 手机应用（打开 + 清单）/ 手机状态 / 音量 / 日历日程。
 *
 * 设计约束：
 * - 拨号：ACTION_CALL 直接拨出（需 CALL_PHONE，启动时申请；语音指令即授权），
 *   未授权/被 ROM 限制时退回 ACTION_DIAL 打开拨号盘
 * - 短信：`SmsManager` 直接发出（需 SEND_SMS），等系统回执再报结果，不把"提交"讲成"发送成功"
 * - 通讯录/日历需要运行时权限，缺失时返回带引导的说明文本（模型如实转告用户去授权）
 * - 所有方法同步阻塞（Agent 工具循环已在后台线程执行），返回给模型的中文结果文本
 */
object PhoneTools {
    private const val TAG = "PhoneTools"

    // ═══════════════════════════ 权限 ═══════════════════════════

    private fun has(context: Context, perm: String): Boolean =
        ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED

    // ⚠️ 原`missingPermTip`（自写「请去设置里开」文案）已于 2026-10-07 删除。
    // 它**违反项目硬规则**（禁止自写"请去设置开"文案，必须走统一桥接层`PermissionBridge`），
    // 且它的4 个使用点全是 `PermissionBridge.ensure(...) ?: missingPermTip(...)` 这种 elvis 形式——
    // 而 `ensure` 返回 null 的**唯一**条件是「权限已就绪」（PermissionBridge.kt:91），
    // 这些点又都在 `if (!has(...))` 里 ⇒ **elvis 分支恒不可达**。
    //
    // 📌 危害不是多占几行：留着它会让下一个人以为"这条路径是活的"而照抄，
    // 于是品牌名两种写法的旧问题又被复制一遍。恒不可达的分支比没有分支更危险。

    // ═══════════════════════════ 权限 / 可拉起性前置 ═══════════════════════════

    /**
     * 「能不能现在把界面拉起来」的前置校验（拨号 / 闹钟 / 打开应用都要过这一关）。
     *
     * 为什么必须校验：Android 10+ 的 BAL 限制下，**后台** `startActivity` 会被系统
     * **静默丢弃**（不抛异常、不打 error 日志），代码以为成功了 —— 历史现象就是
     * "AI 说「正在拨打：X」，手机屏幕毫无反应"。普通应用能拿到的豁免只有一个：
     * 悬浮窗（`SYSTEM_ALERT_WINDOW`）。没有它就如实回报并顺带发起悬浮窗申请
     * （后台拉不起界面时退通知栏提醒），绝不假成功。
     *
     * @return null = 现在拉得起来；非 null = 会被系统丢弃，该文本原样回给模型
     */
    private fun ensureCanLaunchUi(context: Context): String? {
        if (PermissionBridge.canLaunchUi(context)) return null
        return PermissionBridge.ensure(
            context,
            context.getString(R.string.permission_reason_launch),
            AppPermission.OVERLAY,
        ) ?: context.getString(
            R.string.permission_need_manual_open,
            context.getString(R.string.permission_label_overlay),
        )
    }

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
     * 未授权 → **自动拉起系统授权界面**并如实回报（不再静默退回拨号盘制造假成功）。
     */
    fun dialPhone(context: Context, nameOrNumber: String): String {
        val input = nameOrNumber.trim()
        if (input.isEmpty()) return "请提供要拨打的联系人姓名或手机号"
        val isNumber = input.matches(Regex("^[+\\d][\\d\\s-]{2,}$"))
        if (isNumber) {
            return launchDialer(context, input, input)
        }
        if (!has(context, Manifest.permission.READ_CONTACTS)) {
            // 按姓名拨号必须先查通讯录 → 缺权限就自动申请，别让用户自己去设置里翻
            return PermissionBridge.ensure(
                context,
                context.getString(R.string.permission_reason_contacts),
                AppPermission.CONTACTS,
            ) ?: "通讯录权限已在等待系统授权确认；若系统未弹窗，请稍后重试，或直接告诉我手机号码"
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

        // 前置 1：没有「电话」权限 → **自动把系统授权框拉起来**并如实回报。
        // 旧实现在这里静默退回 ACTION_DIAL 并回一句"已打开拨号盘"——那是个假成功：
        // 后台且无悬浮窗时，这个 startActivity 同样会被 BAL 丢弃（用户什么都看不到），
        // 而模型已经拿到"打开成功"的结论去答复用户了。
        if (!has(context, Manifest.permission.CALL_PHONE)) {
            return PermissionBridge.ensure(
                context,
                context.getString(R.string.permission_reason_phone),
                AppPermission.PHONE_CALL,
            ) ?: context.getString(
                R.string.permission_need_manual_open,
                context.getString(R.string.permission_label_phone),
            )
        }

        // 前置 2：有权限，但 ACTION_CALL 要**从后台拉起拨号界面** → 必须持有 BAL 豁免
        ensureCanLaunchUi(context)?.let { return it }

        return try {
            context.startActivity(Intent(Intent.ACTION_CALL, tel).apply { addFlags(newTask) })
            "正在拨打：$number（$label）"
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

    // ═══════════════════════════ 短信 ═══════════════════════════

    /** [sendSms] 正文上限：一条短信能装的内容，防止模型把整篇文档塞进去 */
    private const val MAX_SMS_CHARS = 500

    /** [sendSms] 等系统发送回执的最长时间 */
    private const val SMS_SENT_TIMEOUT_MS = 8000L

    /** [sendSms] 回执 action 序号：并发两次发短信时 action 必须唯一，
     *  否则固定 action + 固定 requestCode 会拿到同一条 one-shot PendingIntent（第二次发送永远等不到回执），
     *  且两个 receiver 注册在同一 action 上会互相收到对方的回执 */
    private val SMS_ACTION_SEQ = AtomicInteger(0)

    /**
     * 发短信：参数是手机号直接发；是姓名则查通讯录（唯一命中发出，多个命中返回候选）。
     *
     * 为什么用 `SmsManager` **直接发出**、而不是"打开短信 App 等用户点发送"：与 [dialPhone] 的
     * ACTION_CALL 同一策略（用户说「发短信给 X」即指令）。退化成"打开短信界面等确认"还有个
     * 隐蔽问题 —— 锁屏/后台时那次 `startActivity` 会被 BAL 静默丢弃，而模型已经拿到
     * "已发送"的结论去答复用户了（本文件反复强调的假成功）。
     *
     * 为什么不"提交完就报成功"：`sendMultipartTextMessage` 是**异步**的，短信中心不可达 /
     * 飞行模式时它同样正常返回。所以这里用 [PendingIntent] 等系统回执（最长
     * [SMS_SENT_TIMEOUT_MS]），拿不到回执就只说"已提交、未收到回执"，绝不把"提交"讲成"发送成功"。
     */
    @Suppress("DEPRECATION") // SmsManager.getDefault() 在 API 31+ 废弃，低版本仍必须用它
    fun sendSms(context: Context, to: String, message: String): String {
        val target = to.trim()
        val text = message.trim()
        if (target.isEmpty()) return "请提供短信接收人（姓名或手机号）"
        if (text.isEmpty()) return "请提供短信内容"
        if (text.length > MAX_SMS_CHARS) {
            return "短信内容过长（${text.length} 字），请精简到 $MAX_SMS_CHARS 字以内"
        }

        // 收件人解析：号码直接用；姓名先查通讯录（缺权限自动拉起系统授权界面）
        val number: String
        val label: String
        if (target.matches(Regex("^[+\\d][\\d\\s-]{2,}$"))) {
            number = target.replace(" ", "")
            label = number
        } else {
            PermissionBridge.ensure(
                context,
                context.getString(R.string.permission_reason_contacts),
                AppPermission.CONTACTS,
            )?.let { return it }
            val hits = searchContacts(context, target)
            when (hits.size) {
                0 -> return "通讯录中没有找到「$target」，请确认姓名或直接告诉我手机号"
                1 -> {
                    number = hits[0].number.replace(" ", "")
                    label = hits[0].name
                }
                else -> return "通讯录里有多个「$target」：\n" + hits.mapIndexed { i, c ->
                    "[${i + 1}] ${c.name}（${c.number}）"
                }.joinToString("\n") + "\n请告诉我要发给哪一个"
            }
        }

        if (!has(context, Manifest.permission.SEND_SMS)) {
            return PermissionBridge.ensure(
                context,
                context.getString(R.string.permission_reason_sms),
                AppPermission.SMS,
            ) ?: context.getString(
                R.string.permission_need_manual_open,
                context.getString(R.string.permission_label_sms),
            )
        }

        return try {
            val sm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
            } else {
                SmsManager.getDefault()
            } ?: return "这台手机没有可用的短信服务"

            val code = AtomicInteger(Int.MIN_VALUE)
            val latch = CountDownLatch(1)
            // 每次调用唯一 action：并发调用互不串台（见 SMS_ACTION_SEQ 注释）
            val action = "com.rokidlab.phone.SMS_SENT.${SMS_ACTION_SEQ.incrementAndGet()}"
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context?, i: Intent?) {
                    code.set(resultCode)
                    latch.countDown()
                }
            }
            // 只接收本 App 自己发出的这条广播（导出会让同机其他应用能伪造发送结果）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(receiver, IntentFilter(action))
            }
            try {
                val parts = sm.divideMessage(text)
                val sent = PendingIntent.getBroadcast(
                    context, 0, Intent(action),
                    PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE,
                )
                // 只等**最后一段**的回执：分段是顺序发出的，最后一段有回执说明前面都已提交
                val sentIntents = ArrayList<PendingIntent?>(parts.size)
                repeat(parts.size - 1) { sentIntents.add(null) }
                sentIntents.add(sent)
                sm.sendMultipartTextMessage(number, null, parts, sentIntents, null)

                when {
                    !latch.await(SMS_SENT_TIMEOUT_MS, TimeUnit.MILLISECONDS) ->
                        "已把短信提交给系统发送给 $label（$number），但 ${SMS_SENT_TIMEOUT_MS / 1000} 秒内没收到回执（可能信号较弱），请在手机上确认"
                    code.get() == Activity.RESULT_OK -> "已发送短信给 $label（$number）：$text"
                    else -> "短信发送给 $label（$number）失败：${smsErrorText(code.get())}"
                }
            } finally {
                runCatching { context.unregisterReceiver(receiver) }
            }
        } catch (e: Exception) {
            "发送短信失败：${e.message}"
        }
    }

    /** 系统发送回执错误码 → 中文（未知码原样带上，方便排查） */
    private fun smsErrorText(code: Int): String = when (code) {
        SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "短信中心拒绝（余额不足或被运营商拦截）"
        SmsManager.RESULT_ERROR_NO_SERVICE -> "当前无信号或未插入 SIM 卡"
        SmsManager.RESULT_ERROR_RADIO_OFF -> "飞行模式已开启"
        SmsManager.RESULT_ERROR_NULL_PDU -> "短信内容无法编码"
        else -> "系统错误码 $code"
    }

    // ═══════════════════════════ 剪贴板 ═══════════════════════════

    /** [readClipboard] 回填给模型的内容上限 */
    private const val MAX_CLIPBOARD_CHARS = 4000

    /**
     * 读剪贴板。
     *
     * ⚠️ **必须诚实**：Android 10（API 29）起，只有**有输入焦点的 App**（或默认输入法）能读剪贴板，
     * 后台读一律返回 null。而乐奇实验室的语音对话跑在前台**服务**里，App 界面往往没在前台 ——
     * 所以"读不到"是常态而不是异常。这里把读不到的原因写清楚交回模型，
     * 是为了让它如实说「需要先打开一下 App」，**而不是**编一段剪贴板内容（这是最容易出现的假成功）。
     */
    fun readClipboard(context: Context): String {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return "读不到剪贴板：这台手机没有剪贴板服务"
        val text = runCatching {
            cm.primaryClip?.takeIf { it.itemCount > 0 }
                ?.let { it.getItemAt(0).coerceToText(context).toString() }
        }.getOrNull()?.trim()

        if (text.isNullOrEmpty()) {
            return "读不到剪贴板内容。可能原因：①App 现在不在前台（Android 10 起只有前台 App 能读剪贴板）；" +
                "②剪贴板是空的或装的是图片不是文字。请如实告诉用户「需要把乐奇实验室切到前台我才能读到剪贴板」，" +
                "**不要**编造剪贴板里有什么"
        }
        val body = text.take(MAX_CLIPBOARD_CHARS) + if (text.length > MAX_CLIPBOARD_CHARS) "\n…（已截断）" else ""
        return "剪贴板内容（共 ${text.length} 字）：\n$body"
    }

    /** 写剪贴板（写不受 Android 10 的后台限制，任何时刻都能用） */
    fun writeClipboard(context: Context, text: String): String {
        val t = text.trim()
        if (t.isEmpty()) return "请提供要复制到剪贴板的内容"
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            ?: return "复制失败：这台手机没有剪贴板服务"
        return try {
            cm.setPrimaryClip(ClipData.newPlainText("乐奇实验室", t))
            "已复制到剪贴板：${t.take(60)}${if (t.length > 60) "…" else ""}（共 ${t.length} 字）"
        } catch (e: Exception) {
            "复制到剪贴板失败：${e.message}"
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
                    // EXTRA_LENGTH 契约是 int：Long 直接 putExtra 会让时钟 App getIntExtra
                    // 拿到默认值 → 倒计时实际没设上却回复成功（假成功）
                    putExtra(AlarmClock.EXTRA_LENGTH, (minutesFromNow * 60).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
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
        // 打开应用同样是"从后台拉起界面"：没有 BAL 豁免时必然被系统丢弃
        ensureCanLaunchUi(context)?.let { return it }
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

    // ═══════════════════════════ 已装应用清单 ═══════════════════════════

    /** [listPhoneApps] 一次返回的条数上限（模型要的是"装了哪些"，不是几百行清单） */
    private const val MAX_APP_LIST = 40

    /**
     * 列出手机上的应用（名称 / 包名 / 版本）。
     *
     * 为什么需要它：[openPhoneApp] 只能**猜**应用名，而模型并不知道这台手机到底装了什么，
     * 「帮我打开 XX」经常直接答"没找到"。有了清单就能先查再开，也能回答"我手机装了哪些应用"。
     *
     * 走 `queryIntentActivities(ACTION_MAIN / CATEGORY_LAUNCHER)` 而不是 `getInstalledPackages`：
     * 前者只列**用户能点开**的应用，与 [openPhoneApp] 的匹配集合完全一致
     * （`getInstalledPackages` 会把几百个系统组件一起倒出来，既不相关又挤爆上下文）。
     * manifest 已声明 `QUERY_ALL_PACKAGES`，因此这里拿得到完整清单。
     *
     * @param query 关键词（匹配应用名或包名；空 = 不过滤）
     * @param includeSystem 是否包含系统预装应用（默认 false，只列用户自己装的那些）
     */
    fun listPhoneApps(context: Context, query: String?, includeSystem: Boolean): String {
        val q = query?.trim().orEmpty()
        return try {
            val pm = context.packageManager
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val matched = LinkedHashMap<String, String>() // pkg -> 展示行（只收前 MAX_APP_LIST 条）
            val seen = HashSet<String>()                  // 已计过数的包名：一个包可能有多个启动入口
            var total = 0
            pm.queryIntentActivities(launcher, 0).forEach { ri ->
                val pkg = ri.activityInfo.packageName
                if (!seen.add(pkg)) return@forEach
                val label = runCatching { ri.loadLabel(pm).toString() }.getOrDefault("").trim()
                if (label.isEmpty()) return@forEach
                val systemApp = (ri.activityInfo.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                if (systemApp && !includeSystem) return@forEach
                if (q.isNotEmpty() && !label.contains(q, true) && !pkg.contains(q, true)) return@forEach
                total++
                if (matched.size < MAX_APP_LIST) {
                    val ver = runCatching { pm.getPackageInfo(pkg, 0).versionName }.getOrNull().orEmpty()
                    matched[pkg] = "[${matched.size + 1}] $label（$pkg${if (ver.isNotBlank()) "，v$ver" else ""}）"
                }
            }
            if (matched.isEmpty()) {
                val scope = if (includeSystem) "" else "第三方"
                if (q.isEmpty()) {
                    "这台手机上没有${scope}应用"
                } else {
                    "没有找到名称或包名包含「$q」的应用${if (includeSystem) "" else "（未包含系统应用，需要时可用 includeSystem 再查一次）"}"
                }
            } else {
                val scope = if (includeSystem) "" else "第三方"
                "手机${scope}应用：$total 个" +
                    (if (total > matched.size) "（列出前 ${matched.size} 个）" else "") +
                    "\n" + matched.values.joinToString("\n")
            }
        } catch (e: Exception) {
            "查询应用清单失败：${e.message}"
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

    // ═══════════════════════════ 导航 / 卸载 / 手电筒 / 计步 ═══════════════════════════

    /**
     * 打开地图 / 发起导航（`geo:` URI 交给系统挑选地图应用）。
     *
     * `geo:0,0?q=<目的地>` 是各地图 App 的通用入口；mode 以附加查询参数带上
     * （不认识该参数的应用会忽略它，不影响 q 的解析），目的地为空则只打开地图。
     */
    fun openNavigation(context: Context, destination: String, mode: String): String {
        ensureCanLaunchUi(context)?.let { return it }
        return try {
            val dest = destination.trim()
            val uri = if (dest.isEmpty()) {
                Uri.parse("geo:0,0")
            } else {
                val flag = when (mode.trim().lowercase()) {
                    "walk" -> "w"
                    "bike" -> "b"
                    "transit" -> "r"
                    else -> "d"
                }
                Uri.parse("geo:0,0?q=" + Uri.encode(dest) + "&mode=" + flag)
            }
            context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            if (dest.isEmpty()) "已打开手机地图" else "已在地图中定位「$dest」，请在地图上确认路线后开始导航"
        } catch (e: ActivityNotFoundException) {
            "手机上没有安装地图应用，无法导航"
        } catch (e: Exception) {
            "打开地图失败：${e.message}"
        }
    }

    /**
     * 请求卸载手机应用：走系统 `ACTION_DELETE`，**真正的卸载由用户在系统确认框里点头**。
     * 包名缺失时按应用名解析（与打开应用同一套 label 匹配）。
     */
    fun uninstallPhoneApp(context: Context, packageName: String, appName: String): String {
        var pkg = packageName.trim()
        if (pkg.isEmpty()) {
            val name = appName.trim()
            if (name.isEmpty()) return "请提供要卸载的应用包名或名称"
            pkg = resolvePackageByLabel(context, name)
                ?: return "手机上没有找到应用「$name」，可先用 list_phone_apps 查一下包名"
        }
        ensureCanLaunchUi(context)?.let { return it }
        return try {
            val intent = Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            "已请求卸载「$pkg」，请在弹出的系统确认框里确认"
        } catch (e: ActivityNotFoundException) {
            "系统不支持应用卸载请求"
        } catch (e: Exception) {
            "请求卸载失败：${e.message}"
        }
    }

    /** 应用名 → 包名（取带启动入口的应用：精确命中优先，其次双向包含） */
    private fun resolvePackageByLabel(context: Context, query: String): String? {
        val pm = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val candidates = LinkedHashMap<String, String>()
        pm.queryIntentActivities(launcherIntent, 0).forEach { ri ->
            val label = runCatching { ri.loadLabel(pm).toString() }.getOrDefault("")
            if (label.isNotBlank()) candidates.putIfAbsent(label, ri.activityInfo.packageName)
        }
        return candidates[query]
            ?: candidates.entries.firstOrNull {
                it.key.contains(query, ignoreCase = true) || query.contains(it.key, ignoreCase = true)
            }?.value
    }

    /** 开关手电筒（闪光灯补光）；无闪光灯机型 / 被相机占用时如实回报 */
    fun setFlashlight(context: Context, enabled: Boolean): String {
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_FLASH)) {
            return "这台手机没有闪光灯，无法使用手电筒"
        }
        return try {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
                ?: return "这台手机没有闪光灯，无法使用手电筒"
            cm.setTorchMode("0", enabled)
            if (enabled) "手电筒已打开" else "手电筒已关闭"
        } catch (e: CameraAccessException) {
            Log.w(TAG, "torch unavailable (camera in use?), enabled=$enabled", e)
            "闪光灯当前不可用（可能正被相机占用），请稍后再试"
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "no flash unit for camera 0", e)
            "这台手机没有闪光灯，无法使用手电筒"
        } catch (e: Exception) {
            Log.w(TAG, "set torch mode failed, enabled=$enabled", e)
            "${if (enabled) "打开" else "关闭"}手电筒失败：${e.message}"
        }
    }

    /**
     * 读取计步传感器（`TYPE_STEP_COUNTER`）：**自开机以来的累计步数**，重启清零 ——
     * 口径必须如实回报，不能谎称"今日步数"。一次性监听：取到首个事件立即注销。
     */
    fun readStepCount(context: Context): String {
        // 缺身体活动权限统一走权限桥：现场拉起系统授权框（后台退通知栏兜底），不自己写"去设置里开"
        PermissionBridge.ensure(
            context,
            context.getString(R.string.permission_reason_activity),
            AppPermission.ACTIVITY_RECOGNITION,
        )?.let { return it }

        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            ?: return "这台手机不支持计步传感器"
        val sensor = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
            ?: return "这台手机不支持计步传感器"
        val latch = CountDownLatch(1)
        var steps = -1f
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.values.isNotEmpty()) steps = event.values[0]
                latch.countDown()
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        // 工具跑在无 Looper 的工作线程上，事件投递必须显式指定主线程 Handler
        val registered = try {
            sm.registerListener(
                listener, sensor, SensorManager.SENSOR_DELAY_NORMAL,
                Handler(Looper.getMainLooper()),
            )
        } catch (e: Exception) {
            Log.w(TAG, "register step counter listener failed", e)
            false
        }
        if (!registered) return "计步传感器不可用，请稍后再试"
        try {
            latch.await(2, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } finally {
            sm.unregisterListener(listener)
        }
        if (steps < 0) return "暂时没有取到计步数据，请稍后再试"
        return "自开机以来累计 ${steps.toLong()} 步（重启清零，可作今日活动量参考）"
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
                if (parts[1] !in 1..12 || parts[2] !in 1..31) return null
                cal.set(parts[0], parts[1] - 1, parts[2])
                // 宽松日历会把不存在的日期静默滚动（2-30 → 3-2），解算后月份对不上即拒绝
                if (cal.get(Calendar.MONTH) != parts[1] - 1 || cal.get(Calendar.DAY_OF_MONTH) != parts[2]) return null
                cal
            }
            d.matches(Regex("^\\d{1,2}-\\d{1,2}$")) -> {
                // 「9-10」默认当年。范围校验 + 解算后核对，防 2-30 之类被宽松日历滚走
                val parts = d.split("-").map { it.toInt() }
                if (parts[0] !in 1..12 || parts[1] !in 1..31) return null
                cal.set(cal.get(Calendar.YEAR), parts[0] - 1, parts[1])
                if (cal.get(Calendar.MONTH) != parts[0] - 1 || cal.get(Calendar.DAY_OF_MONTH) != parts[1]) return null
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
        if (!has(context, Manifest.permission.READ_CALENDAR)) {
            // 缺权限 → 自动拉起系统授权界面（旧实现只回一句"请去设置里开"，用户基本找不到）
            return PermissionBridge.ensure(
                context,
                context.getString(R.string.permission_reason_calendar),
                AppPermission.CALENDAR_READ,
            ) ?: "日历读取权限已在等待系统授权确认；若系统未弹窗，请稍后重试"
        }
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
        if (!has(context, Manifest.permission.WRITE_CALENDAR)) {
            return PermissionBridge.ensure(
                context,
                context.getString(R.string.permission_reason_calendar),
                AppPermission.CALENDAR_WRITE,
            ) ?: "日历写入权限已在等待系统授权确认；若系统未弹窗，请稍后重试"
        }
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

    // ═══════════════════ 日历删 / 改（R4，2026-09-30） ═══════════════════
    // 判据（对齐 delete_knowledge_doc 的先例）：删除不可恢复 ⇒ 独立工具 + EXTERNAL + BLOCK，
    // 不并进 manage_calendar（那会把 query/create 一起拉进确认闸门，「记个日程」也要弹确认）。

    /** 日历事件定位单元：id 是删改的精确句柄，title/time 是确认摘要与候选列表的展示素材 */
    internal data class CalEventRef(
        val id: Long,
        val title: String,
        val start: Long,
        val end: Long,
    )

    /** WRITE_CALENDAR 闸门：缺权限时自动拉起系统授权界面，拿到可读提示（非 null）即应原样返回 */
    private fun ensureCalendarWrite(context: Context): String? =
        if (has(context, Manifest.permission.WRITE_CALENDAR)) null
        else PermissionBridge.ensure(
            context,
            context.getString(R.string.permission_reason_calendar),
            AppPermission.CALENDAR_WRITE,
        ) ?: "日历写入权限已在等待系统授权确认；若系统未弹窗，请稍后重试"

    /**
     * 查某天（或全日历范围）的事件原行，供删改前定位。
     * 用 Events 表直查（不是 Instances）：删改要的是**事件本体**的 id，
     * Instances 是展开后的实例视图（重复日程会展开成多行），拿它改会改错对象。
     */
    internal fun findEventsOnDay(context: Context, date: String?, keyword: String?): List<CalEventRef> {
        val dayStart = parseDayStart(date) ?: return emptyList()
        val dayEnd = (dayStart.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }
        val sel = "${CalendarContract.Events.DTSTART} >= ? AND ${CalendarContract.Events.DTSTART} < ?"
        val args = arrayOf(dayStart.timeInMillis.toString(), dayEnd.timeInMillis.toString())
        val out = mutableListOf<CalEventRef>()
        context.contentResolver.query(
            CalendarContract.Events.CONTENT_URI,
            arrayOf(
                CalendarContract.Events._ID,
                CalendarContract.Events.TITLE,
                CalendarContract.Events.DTSTART,
                CalendarContract.Events.DTEND,
            ),
            sel,
            args,
            "${CalendarContract.Events.DTSTART} ASC",
        )?.use { c ->
            while (c.moveToNext()) {
                val title = c.getString(1) ?: ""
                if (!keyword.isNullOrBlank() && !title.contains(keyword.trim(), ignoreCase = true)) continue
                out.add(CalEventRef(c.getLong(0), title.ifBlank { "(无标题)" }, c.getLong(2), c.getLong(3)))
            }
        }
        return out
    }

    /**
     * 删除一个日历日程（**不可恢复**）。定位方式二选一：
     * `eventId`（queryCalendar 列表拿到的 id，精确）或 `date`+`title` 关键词（模糊）。
     * 关键词命中多个时**不删**，把候选连 id 列出来让模型带 eventId 重调 ——
     * 模糊匹配 + 静默删错，是这类接口最坏的失败形状。
     */
    fun deleteCalendarEvent(context: Context, eventId: Long, date: String?, title: String?): String {
        ensureCalendarWrite(context)?.let { return it }
        return try {
            val target: CalEventRef = when {
                eventId > 0 -> loadEvent(context, eventId)
                    ?: return "没有找到 id=$eventId 的日程（它可能已被删除；用 queryCalendar 确认现状后再试）"
                else -> {
                    if (title.isNullOrBlank()) {
                        return "请指明要删除的日程：传 eventId（最稳妥）或 date+title 关键词；不要不带条件地整日清除"
                    }
                    val hits = findEventsOnDay(context, date, title)
                    when {
                        hits.isEmpty() -> return "「$date」没有找到标题含「$title」的日程；用 queryCalendar 确认日程确实存在"
                        hits.size > 1 -> return "「$title」匹配到 ${hits.size} 个日程，为避免删错请确认后用 eventId 精确删除：\n" +
                            hits.joinToString("\n") { fmtEventRef(it) }
                        else -> hits[0]
                    }
                }
            }
            val uri = android.content.ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, target.id)
            val deleted = context.contentResolver.delete(uri, null, null)
            if (deleted > 0) {
                "已删除日程「${target.title}」（${fmtRange(target.start, target.end)}）"
            } else {
                "删除失败（日程可能来自只读账户或已被删除）"
            }
        } catch (e: Exception) {
            "删除日程失败：${e.message}"
        }
    }

    /**
     * 修改一个日历日程（可再次编辑，属本机可撤销操作 ⇒ LOCAL 档不走确认闸门）。
     * 只更新用户明确给的字段；改时间时未给日期则沿用原日期，未给时刻则沿用原时刻。
     */
    fun updateCalendarEvent(
        context: Context,
        eventId: Long,
        date: String?,
        title: String?,
        newTitle: String?,
        newDate: String?,
        newStartTime: String?,
        newDurationMinutes: Int?,
        newNote: String?,
    ): String {
        ensureCalendarWrite(context)?.let { return it }
        return try {
            val target: CalEventRef = when {
                eventId > 0 -> loadEvent(context, eventId)
                    ?: return "没有找到 id=$eventId 的日程（它可能已被删除；用 queryCalendar 确认现状后再试）"
                else -> {
                    if (title.isNullOrBlank()) {
                        return "请指明要修改的日程：传 eventId（最稳妥）或 date+title 关键词"
                    }
                    val hits = findEventsOnDay(context, date, title)
                    when {
                        hits.isEmpty() -> return "「$date」没有找到标题含「$title」的日程；用 queryCalendar 确认日程确实存在"
                        hits.size > 1 -> return "「$title」匹配到 ${hits.size} 个日程，为避免改错请确认后用 eventId 精确修改：\n" +
                            hits.joinToString("\n") { fmtEventRef(it) }
                        else -> hits[0]
                    }
                }
            }
            // 读取原备注：newNote 未给时保持原值（不覆盖成空串）
            var origNote = ""
            context.contentResolver.query(
                android.content.ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, target.id),
                arrayOf(CalendarContract.Events.DESCRIPTION),
                null, null, null,
            )?.use { c -> if (c.moveToFirst()) origNote = c.getString(0) ?: "" }

            val origBegin = java.util.Calendar.getInstance().apply { timeInMillis = target.start }
            val durationMin = newDurationMinutes
                ?: ((target.end - target.start) / 60_000L).toInt().takeIf { it > 0 }
                ?: 60
            // 日期基座：给了 newDate 用新日期，否则沿用原事件的日期
            val begin = if (newDate.isNullOrBlank()) {
                origBegin.clone() as java.util.Calendar
            } else {
                val d = parseDayStart(newDate) ?: return "新日期格式不正确，请用 today/tomorrow 或 YYYY-MM-DD"
                // 只改日期不给新时刻：parseDayStart 返回当天 00:00，不回填原事件的时分秒
                // 会把日程静默改到凌晨零点（注释承诺"沿用原时刻"仅在不给日期时成立）
                d.set(java.util.Calendar.HOUR_OF_DAY, origBegin.get(java.util.Calendar.HOUR_OF_DAY))
                d.set(java.util.Calendar.MINUTE, origBegin.get(java.util.Calendar.MINUTE))
                d.set(java.util.Calendar.SECOND, origBegin.get(java.util.Calendar.SECOND))
                d
            }
            // 时刻：给了 newStartTime 用新时刻，否则沿用原事件的时刻
            if (!newStartTime.isNullOrBlank()) {
                val m = Regex("^(\\d{1,2}):(\\d{1,2})$").find(newStartTime.trim())
                    ?: return "新开始时间格式不正确，请用 24 小时制 HH:mm，例如 14:30"
                val h = m.groupValues[1].toInt()
                val min = m.groupValues[2].toInt()
                if (h !in 0..23 || min !in 0..59) return "时间超出范围（00:00 ~ 23:59）"
                begin.set(java.util.Calendar.HOUR_OF_DAY, h)
                begin.set(java.util.Calendar.MINUTE, min)
                begin.set(java.util.Calendar.SECOND, 0)
            }
            val end = (begin.clone() as java.util.Calendar).apply {
                add(java.util.Calendar.MINUTE, durationMin.coerceIn(5, 24 * 60))
            }
            val values = android.content.ContentValues().apply {
                if (!newTitle.isNullOrBlank()) put(CalendarContract.Events.TITLE, newTitle.trim())
                if (newNote != null) put(CalendarContract.Events.DESCRIPTION, newNote) else put(CalendarContract.Events.DESCRIPTION, origNote)
                put(CalendarContract.Events.DTSTART, begin.timeInMillis)
                put(CalendarContract.Events.DTEND, end.timeInMillis)
            }
            val uri = android.content.ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, target.id)
            val updated = context.contentResolver.update(uri, values, null, null)
            if (updated > 0) {
                val shownTitle = newTitle?.trim()?.takeIf { it.isNotBlank() } ?: target.title
                "已更新日程「$shownTitle」：现在为 ${fmtRange(begin.timeInMillis, end.timeInMillis)}" +
                    if (newNote != null) "，备注已更新" else ""
            } else {
                "更新失败（日程可能来自只读账户或已被删除）"
            }
        } catch (e: Exception) {
            "修改日程失败：${e.message}"
        }
    }

    /** 按 id 读单条事件（删改共用的精确路径）；不存在返回 null */
    private fun loadEvent(context: Context, eventId: Long): CalEventRef? =
        context.contentResolver.query(
            android.content.ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, eventId),
            arrayOf(
                CalendarContract.Events._ID,
                CalendarContract.Events.TITLE,
                CalendarContract.Events.DTSTART,
                CalendarContract.Events.DTEND,
            ),
            null, null, null,
        )?.use { c ->
            if (c.moveToFirst()) {
                CalEventRef(c.getLong(0), (c.getString(1) ?: "").ifBlank { "(无标题)" }, c.getLong(2), c.getLong(3))
            } else null
        }

    /** 候选列表行：模型据此拿 eventId 回调（id 是唯一稳定句柄） */
    private fun fmtEventRef(e: CalEventRef): String = "· id=${e.id}「${e.title}」${fmtRange(e.start, e.end)}"

    private fun fmtRange(startMs: Long, endMs: Long): String {
        val df = SimpleDateFormat("M月d日 HH:mm", Locale.CHINA)
        val tf = SimpleDateFormat("HH:mm", Locale.CHINA)
        return "${df.format(startMs)} ~ ${tf.format(endMs)}"
    }
}
