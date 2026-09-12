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
 * GlassesToolProvider —— 眼镜设备域（ADB 查询与控制：电量/设备信息/存储/应用列表/启动应用）。
 * Phase 4 从 `ToolRegistry.execute` 迁出的该域工具执行分支（逐字搬运，行为不变）。
 */
internal object GlassesToolProvider : ToolProvider {
    private const val TAG = "GlassesToolProvider"

    override val toolNames = setOf(
        "get_glasses_battery",
        "get_glasses_device_info",
        "get_glasses_storage",
        "list_glasses_apps",
        "launch_glasses_app",
    )

    override fun execute(context: Context, name: String, args: JSONObject): String {
        return when (name) {
            "get_glasses_battery" -> synchronized(ToolRegistry.adbLock) {
                val client = ToolRegistry.adbClient(context) ?: return "眼镜 ADB 连接失败，无法查询电量"
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
                    // 常驻复用共享 ADB 连接（CxrLHiRokidSession 缓存）：不再用后即断，
                    // 避免 AI 工具循环每次执行都重建 TCP+RFCOMM+ADB 鉴权造成隧道风暴。
                    // 连接失败/断线时 getAdbShellClient 检测 isConnected=false 会自动重建。
                }
            }

            "get_glasses_device_info" -> synchronized(ToolRegistry.adbLock) {
                val client = ToolRegistry.adbClient(context) ?: return "眼镜 ADB 连接失败，无法查询设备信息"
                try {
                    val raw = client.getDeviceInfo()
                    // 脱敏：ro.serialno 值替换为 [已隐藏]，避免设备序列号泄露给 AI/日志
                    raw.replace(Regex("(ro\\.serialno): .*")) { "${it.groupValues[1]}: [已隐藏]" }
                } finally {
                    // 常驻复用共享 ADB 连接，不用后即断（原因见 get_glasses_battery）
                }
            }

            "get_glasses_storage" -> synchronized(ToolRegistry.adbLock) {
                val client = ToolRegistry.adbClient(context) ?: return "眼镜 ADB 连接失败，无法查询存储"
                try {
                    client.executeShellCommand("df -h /sdcard /data 2>/dev/null")
                } finally {
                    // 常驻复用共享 ADB 连接，不用后即断（原因见 get_glasses_battery）
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
                    // 常驻复用共享 ADB 连接，不用后即断（原因见 get_glasses_battery）
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
                    // 常驻复用共享 ADB 连接，不用后即断（原因见 get_glasses_battery）
                }
            }

        else -> throw IllegalArgumentException("未知工具: $name")
        }
    }
}
