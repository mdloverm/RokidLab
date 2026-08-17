package com.rokidlab.phone.ai

import android.content.Context
import android.util.Log
import com.rokidlab.phone.R
import com.rokidlab.phone.adb.AdbShellClient
import com.rokidlab.phone.adb.TimerScheduler
import com.rokidlab.phone.adb.ui.TimerAction
import com.rokidlab.phone.adb.ui.TimerSchedule
import com.rokidlab.phone.adb.ui.TimerTask
import com.rokidlab.phone.app.LabApplication
import com.rokidlab.phone.model.BrewIndex
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * 乐奇 AI 工具注册表（function calling）。
 *
 * 每个工具 = 「元数据」（设置页展示 + 开关）+「声明」（给 AI 看的 JSON Schema）
 * +「执行」（调用本地已有能力）。
 * 工具执行结果回填给 AI 后生成最终回复，再通过现有 TTS 链路发送到眼镜显示并语音播报。
 *
 * 开关状态持久化在 SharedPreferences（[TOOL_PREFS]），关闭的工具不会随请求发送给 AI。
 * 查询类工具（电量/设备信息等）通过 CxrLHiRokidSession.getAdbShellClient() 复用 ADB 通道。
 */
object ToolRegistry {
    private const val TAG = "ToolRegistry"
    private const val TOOL_PREFS = "ai_tool_prefs"
    private const val KEY_PREFIX = "tool_enabled_"

    /** 工具元数据（设置页展示用，名称/描述走多语言资源） */
    data class ToolMeta(
        val name: String,
        val displayNameRes: Int,
        val descriptionRes: Int,
    )

    /** 全部工具（含已禁用），按声明顺序 */
    val toolList: List<ToolMeta> = listOf(
        ToolMeta(
            name = "search_knowledge_base",
            displayNameRes = R.string.ai_tool_search_knowledge_base_name,
            descriptionRes = R.string.ai_tool_search_knowledge_base_desc,
        ),
        ToolMeta(
            name = "get_current_time",
            displayNameRes = R.string.ai_tool_get_current_time_name,
            descriptionRes = R.string.ai_tool_get_current_time_desc,
        ),
        ToolMeta(
            name = "get_glasses_battery",
            displayNameRes = R.string.ai_tool_get_glasses_battery_name,
            descriptionRes = R.string.ai_tool_get_glasses_battery_desc,
        ),
        ToolMeta(
            name = "get_glasses_device_info",
            displayNameRes = R.string.ai_tool_get_glasses_device_info_name,
            descriptionRes = R.string.ai_tool_get_glasses_device_info_desc,
        ),
        ToolMeta(
            name = "get_glasses_storage",
            displayNameRes = R.string.ai_tool_get_glasses_storage_name,
            descriptionRes = R.string.ai_tool_get_glasses_storage_desc,
        ),
        ToolMeta(
            name = "list_glasses_apps",
            displayNameRes = R.string.ai_tool_list_glasses_apps_name,
            descriptionRes = R.string.ai_tool_list_glasses_apps_desc,
        ),
        ToolMeta(
            name = "launch_glasses_app",
            displayNameRes = R.string.ai_tool_launch_glasses_app_name,
            descriptionRes = R.string.ai_tool_launch_glasses_app_desc,
        ),
        ToolMeta(
            name = "set_timer",
            displayNameRes = R.string.ai_tool_set_timer_name,
            descriptionRes = R.string.ai_tool_set_timer_desc,
        ),
    )

    /** 工具开关状态（默认开启） */
    fun isEnabled(context: Context, name: String): Boolean {
        val prefs = context.getSharedPreferences(TOOL_PREFS, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_PREFIX + name, true)
    }

    fun setEnabled(context: Context, name: String, enabled: Boolean) {
        context.getSharedPreferences(TOOL_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_PREFIX + name, enabled)
            .apply()
    }

    /** 工具声明列表（仅已开启的工具），直接传给 OpenAI 兼容协议的 tools 参数 */
    fun schemas(context: Context): List<JSONObject> {
        return toolList
            .filter { isEnabled(context, it.name) }
            .map { buildSchema(it) }
    }

    private fun buildSchema(meta: ToolMeta): JSONObject {
        return when (meta.name) {
            "search_knowledge_base" -> toolSchema(
                name = meta.name,
                description = "在用户的本地知识库中检索资料并返回相关内容。当用户询问已导入文档（说明书、资料、笔记等）中的内容时调用，例如“键盘怎么用”、“说明书里怎么说的”。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "query" to mapOf("type" to "string", "description" to "检索关键词，用最核心的 2~4 个词"),
                        "topK" to mapOf("type" to "integer", "description" to "返回的资料块数量，默认 3", "minimum" to 1, "maximum" to 5),
                    ),
                    "required" to listOf("query"),
                ),
            )

            "list_glasses_apps" -> toolSchema(
                name = meta.name,
                description = "列出 Rokid 眼镜上安装的应用。当用户询问眼镜装了哪些应用、有没有某个应用时调用。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "includeSystem" to mapOf("type" to "boolean", "description" to "是否包含系统应用，默认 false"),
                    ),
                ),
            )

            "launch_glasses_app" -> toolSchema(
                name = meta.name,
                description = "打开眼镜上安装的应用。当用户说“打开某应用”“启动某应用”时调用。传入用户口中的应用名称（如“小智”“Via”“小游戏”），不需要包名。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "appName" to mapOf("type" to "string", "description" to "用户想要打开的应用名称，原样转述用户的话，如“小智”“浏览器”“B站”"),
                    ),
                    "required" to listOf("appName"),
                ),
            )

            "set_timer" -> toolSchema(
                name = meta.name,
                description = "创建定时提醒或定时任务，到点后眼镜语音播报（可同时打开应用）。当用户说“X分钟后提醒我”“X点叫我”“X点打开某应用”时调用。绝对时间需转换为 24 小时制 HH:mm。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "time" to mapOf("type" to "string", "description" to "触发时间（24 小时制 HH:mm，如 17:00）。“X分钟后”转换为当前时间加 X 分钟后的时间"),
                        "content" to mapOf("type" to "string", "description" to "提醒内容，到点后语音播报，如“该喝水了”"),
                        "action" to mapOf("type" to "string", "enum" to listOf("notify", "launch"), "description" to "动作类型：notify=仅语音提醒（默认），launch=到点打开应用并提醒"),
                        "appName" to mapOf("type" to "string", "description" to "action=launch 时要打开的应用名称（如“小智”）"),
                        "repeatDaily" to mapOf("type" to "boolean", "description" to "是否每天重复，默认 false"),
                    ),
                    "required" to listOf("time", "content"),
                ),
            )

            "get_glasses_device_info" -> toolSchema(
                name = meta.name,
                description = "查询 Rokid 眼镜的设备/系统信息，包括型号、厂商、Android 系统版本、SDK 版本、序列号。当用户询问眼镜的“系统信息”“设备信息”“是什么型号”“什么版本”“固件版本”时调用。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf<String, Any>(),
                ),
            )

            else -> toolSchema(
                name = meta.name,
                description = "执行 ${meta.name} 工具。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf<String, Any>(),
                ),
            )
        }
    }

    /**
     * 执行工具，返回给 AI 的结果文本（同步方法）。
     * @throws IllegalArgumentException 未知工具名
     */
    fun execute(context: Context, name: String, arguments: String): String {
        val args = JSONObject(arguments)
        return when (name) {
            "search_knowledge_base" -> {
                val query = args.optString("query")
                val topK = args.optInt("topK", 3).coerceIn(1, 5)
                val results = KnowledgeBase.search(context, query, topK)
                if (results.isEmpty()) {
                    "知识库中没有找到与“$query”相关的内容"
                } else {
                    results.mapIndexed { i, s -> "[${i + 1}] $s" }.joinToString("\n")
                }
            }

            "get_current_time" -> {
                val fmt = SimpleDateFormat("yyyy年M月d日 EEEE HH:mm", Locale.CHINA)
                "当前时间：" + fmt.format(Date())
            }

            "get_glasses_battery" -> {
                val client = adbClient(context) ?: return "眼镜 ADB 连接失败，无法查询电量"
                try {
                    val raw = client.getBatteryInfo()
                    // dumpsys battery 精简解析：level / status
                    val level = Regex("level: (\\d+)").find(raw)?.groupValues?.get(1)
                    val status = when (Regex("status: (\\d+)").find(raw)?.groupValues?.get(1)) {
                        "2" -> "正在充电"
                        "3" -> "放电中"
                        "4" -> "未充电"
                        "5" -> "已充满"
                        else -> "未知"
                    }
                    val powered = if (raw.contains("AC powered: true") || raw.contains("USB powered: true")) "已接电源" else "未接电源"
                    "眼镜电量 ${level ?: "未知"}%，状态：$status（$powered）"
                } finally {
                    // 用后即断：蓝牙隧道（RFCOMM）仅支持单连接，长连接会阻塞其他 ADB 功能
                    runCatching { client.disconnect() }
                }
            }

            "get_glasses_device_info" -> {
                val client = adbClient(context) ?: return "眼镜 ADB 连接失败，无法查询设备信息"
                try {
                    client.getDeviceInfo()
                } finally {
                    runCatching { client.disconnect() }
                }
            }

            "get_glasses_storage" -> {
                val client = adbClient(context) ?: return "眼镜 ADB 连接失败，无法查询存储"
                try {
                    client.executeShellCommand("df -h /sdcard /data 2>/dev/null")
                } finally {
                    runCatching { client.disconnect() }
                }
            }

            "list_glasses_apps" -> {
                val client = adbClient(context) ?: return "眼镜 ADB 连接失败，无法列出应用"
                try {
                    val includeSystem = args.optBoolean("includeSystem", false)
                    val packages = client.listPackages(includeSystem)
                    if (packages.isEmpty()) {
                        "眼镜上没有安装第三方应用"
                    } else {
                        packages.take(20).joinToString("\n")
                    }
                } finally {
                    runCatching { client.disconnect() }
                }
            }

            "launch_glasses_app" -> {
                val appName = args.optString("appName").trim()
                if (appName.isEmpty()) return "请提供要打开的应用名称"
                val client = adbClient(context) ?: return "眼镜 ADB 连接失败，无法打开应用"
                try {
                    val installed = client.listPackages(false)
                    val pkg = matchPackage(appName, installed, context)
                        ?: return "没有找到“$appName”。眼镜上已安装的应用：${installed.joinToString("、")}"
                    client.launchApp(pkg)
                    "已为你打开 $appName（$pkg）"
                } finally {
                    runCatching { client.disconnect() }
                }
            }

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
                if (action == "launch") {
                    val client = adbClient(context)
                        ?: return "眼镜 ADB 连接失败，无法创建打开应用的定时任务"
                    try {
                        val pkg = matchPackage(appName, client.listPackages(false), context)
                            ?: return "没有找到应用“$appName”，无法创建定时打开任务"
                        actions += TimerAction.LaunchApp(pkg)
                    } finally {
                        runCatching { client.disconnect() }
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

            else -> throw IllegalArgumentException("未知工具: $name")
        }
    }

    /**
     * 应用名 → 包名匹配。
     * 匹配优先级：包名精确 → 商店注册名精确 → 名称包含 → 包名子串（英文/拼音）。
     * 商店名称来源：手机端内置 apps.json（BrewIndex.loadBundled）的 name ↔ packageName。
     */
    private fun matchPackage(appName: String, installed: List<String>, context: Context): String? {
        if (installed.isEmpty()) return null
        val q = appName.trim()
        val qLower = q.lowercase()
        if (q.isEmpty()) return null

        // 1. 包名精确匹配
        installed.firstOrNull { it.lowercase() == qLower }?.let { return it }

        // 2. 商店注册名匹配（仅匹配眼镜端已安装的包）
        val nameToPkg = mutableMapOf<String, String>()
        runCatching {
            BrewIndex.loadBundled(context).forEach { app ->
                app.artifacts.mapNotNull { it.packageName }
                    .filter { it in installed }
                    .forEach { pkg -> nameToPkg[app.name] = pkg }
            }
        }
        nameToPkg[q]?.let { return it }
        nameToPkg.keys.firstOrNull { it.contains(q) || q.contains(it) }?.let { return nameToPkg[it] }

        // 3. 包名子串匹配（"小智"→xiaozhi、"B站"/"bilibili"→bili）
        val fuzzy = qLower.filter { it.isLetterOrDigit() }
        if (fuzzy.length >= 2) {
            installed.firstOrNull { pkg ->
                val lower = pkg.lowercase()
                lower.contains(fuzzy) || lower.substringAfterLast('.').contains(fuzzy)
            }?.let { return it }
        }

        return null
    }

    /** 通过 LabApplication 获取共享 ADB 客户端（由 CxrLHiRokidSession 懒创建并连接） */
    private fun adbClient(context: Context): AdbShellClient? {
        val app = context.applicationContext as? LabApplication ?: return null
        return app.cxrL.getAdbShellClient()
    }

    /** 组装单个工具的 JSON Schema */
    private fun toolSchema(name: String, description: String, parameters: Map<String, Any>): JSONObject {
        return JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", name)
                put("description", description)
                put("parameters", JSONObject(parameters))
            })
        }
    }
}
