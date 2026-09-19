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
 * GlassesToolProvider —— 眼镜设备域（ADB 查询与控制：电量/设备信息/存储/应用列表/启动应用）。
 * Phase 4 从 `ToolRegistry.execute` 迁出的该域工具执行分支（逐字搬运，行为不变）。
 */
internal object GlassesToolProvider : ToolProvider {
    private const val TAG = "GlassesToolProvider"

    override val toolNames = setOf(
        "get_glasses_status",
        "list_glasses_apps",
        "launch_glasses_app",
    )

    override fun tools(): List<ToolEntry> = listOf(
        ToolEntry(
            name = "get_glasses_status",
            group = ToolRegistry.DOMAIN_GLASSES,
            displayNameRes = R.string.ai_tool_get_glasses_status_name,
            descriptionRes = R.string.ai_tool_get_glasses_status_desc,
            risk = ToolRisk.READ_ONLY,
            requiresGlasses = true,
            statusText = "正在查询眼镜状态…",
            schema = toolSchema(
                name = "get_glasses_status",
                description = "查询 Rokid 眼镜的整体状态，一次返回三部分：①电量百分比与充电状态；②存储空间占用（已用/剩余）；③设备系统信息（型号、厂商、Android 版本、SDK 版本、序列号）。凡是问眼镜自身状况的都调用本工具 —— 「眼镜还有多少电」「要充电吗」「存储还剩多少」「内存够不够」「什么型号」「什么版本」「系统信息/设备信息」。数值由设备实时读取，不要凭印象回答。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf<String, Any>(),
                ),
            ),
        ),
        ToolEntry(
            name = "list_glasses_apps",
            group = ToolRegistry.DOMAIN_GLASSES,
            displayNameRes = R.string.ai_tool_list_glasses_apps_name,
            descriptionRes = R.string.ai_tool_list_glasses_apps_desc,
            risk = ToolRisk.READ_ONLY,
            requiresGlasses = true,
            statusText = "正在查询应用列表…",
            schema = toolSchema(
                name = "list_glasses_apps",
                description = "列出 Rokid 眼镜上安装的应用。当用户询问眼镜装了哪些应用、有没有某个应用时调用。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "includeSystem" to mapOf("type" to "boolean", "description" to "是否包含系统应用，默认 false"),
                    ),
                ),
            ),
        ),
        ToolEntry(
            name = "launch_glasses_app",
            group = ToolRegistry.DOMAIN_GLASSES,
            displayNameRes = R.string.ai_tool_launch_glasses_app_name,
            descriptionRes = R.string.ai_tool_launch_glasses_app_desc,
            risk = ToolRisk.LOCAL_SIDE_EFFECT,
            sideEffect = true,
            requiresGlasses = true,
            statusText = "正在打开应用…",
            schema = toolSchema(
                name = "launch_glasses_app",
                description = "打开眼镜上安装的应用。当用户说“打开某应用”“启动某应用”时调用。传入用户口中的应用名称（如“小智”“Via”“小游戏”），不需要包名。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "appName" to mapOf("type" to "string", "description" to "用户想要打开的应用名称，原样转述用户的话，如“小智”“浏览器”“B站”"),
                    ),
                    "required" to listOf("appName"),
                ),
            ),
        ),
    )

    override fun execute(context: Context, name: String, args: JSONObject): String {
        return when (name) {
            // 电量 / 存储 / 设备信息三合一（原 get_glasses_battery + get_glasses_storage +
            // get_glasses_device_info）：三者是同一个数据源（同一台眼镜的 ADB 查询）、同属只读，
            // 拆成三个工具只会让模型漏查用户真正想知道的项。一次全返回，代价是几条多余文本。
            "get_glasses_status" -> synchronized(ToolRegistry.adbLock) {
                val client = ToolRegistry.adbClient(context) ?: return "眼镜 ADB 连接失败，无法查询眼镜状态"
                try {
                    val sb = StringBuilder()

                    // ① 电量
                    val battRaw = runCatching { client.getBatteryInfo() }.getOrNull()
                    val level = battRaw?.let { Regex("level: (\\d+)").find(it)?.groupValues?.get(1) }
                    val status = when (battRaw?.let { Regex("status: (\\d+)").find(it)?.groupValues?.get(1) }) {
                        "2" -> "正在充电"
                        "3" -> "放电中"
                        "4" -> "未充电"
                        "5" -> "已充满"
                        else -> "未知"
                    }
                    val powered = when {
                        battRaw == null -> "未知"
                        battRaw.contains("AC powered: true") || battRaw.contains("USB powered: true") -> "已接电源"
                        else -> "未接电源"
                    }
                    sb.append("【电量】").append(level ?: "未知").append("%，状态：").append(status)
                        .append("（").append(powered).append("）\n")

                    // ② 存储
                    val df = runCatching { client.executeShellCommand("df -h /sdcard /data 2>/dev/null") }.getOrNull()
                    sb.append("【存储】").append(df?.trim()?.ifBlank { "未获取到" } ?: "未获取到").append('\n')

                    // ③ 设备信息（脱敏：ro.serialno 值替换为 [已隐藏]，避免序列号泄露给 AI/日志）
                    val info = runCatching { client.getDeviceInfo() }.getOrNull()
                    val masked = info?.replace(Regex("(ro\\.serialno): .*")) { "${it.groupValues[1]}: [已隐藏]" }
                    sb.append("【设备信息】\n").append(masked?.trim()?.ifBlank { "未获取到" } ?: "未获取到")

                    sb.toString()
                } finally {
                    // 常驻复用共享 ADB 连接（CxrLHiRokidSession 缓存）：不再用后即断，
                    // 避免 AI 工具循环每次执行都重建 TCP+RFCOMM+ADB 鉴权造成隧道风暴。
                    // 连接失败/断线时 getAdbShellClient 检测 isConnected=false 会自动重建。
                }
            }

            "list_glasses_apps" -> synchronized(ToolRegistry.adbLock) {
                val client = ToolRegistry.adbClient(context) ?: return "眼镜 ADB 连接失败，无法列出应用"
                try {
                    val includeSystem = args.optBoolean("includeSystem", false)
                    val packages = client.listPackages(includeSystem)
                    if (packages.isEmpty()) {
                        "眼镜上没有安装第三方应用"
                    } else {
                        packages.take(20).joinToString("\n")
                    }
                } finally {
                    // 常驻复用共享 ADB 连接，不用后即断（原因见 get_glasses_status）
                }
            }

            "launch_glasses_app" -> synchronized(ToolRegistry.adbLock) {
                val appName = args.optString("appName").trim()
                if (appName.isEmpty()) return "请提供要打开的应用名称"
                val client = ToolRegistry.adbClient(context) ?: return "眼镜 ADB 连接失败，无法打开应用"
                try {
                    val installed = client.listPackages(false)
                    val pkg = ToolRegistry.matchPackage(appName, installed, context)
                        ?: return "没有找到“$appName”。眼镜上已安装的应用：${installed.joinToString("、")}"
                    val result = client.launchApp(pkg)
                    // launchApp 失败会返回 "Failed: ..." 前缀，不能照常回复「已打开」
                    if (result.startsWith("Failed")) {
                        "打开 $appName（$pkg）失败，请稍后重试"
                    } else {
                        "已为你打开 $appName（$pkg）"
                    }
                } finally {
                    // 常驻复用共享 ADB 连接，不用后即断（原因见 get_glasses_status）
                }
            }

        else -> throw IllegalArgumentException("未知工具: $name")
        }
    }
}
