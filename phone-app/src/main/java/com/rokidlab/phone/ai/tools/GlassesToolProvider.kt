package com.rokidlab.phone.ai.tools

import com.rokidlab.phone.R
import com.rokidlab.phone.ai.ToolRisk
import com.rokidlab.phone.ai.ToolConfirmPolicy

import android.content.Context
import android.content.ContentValues
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
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
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * GlassesToolProvider —— 眼镜设备域（ADB 查询与控制：电量/设备信息/存储/应用列表/启动应用/
 * 应用装停卸/文件浏览与下载）。
 * Phase 4 从 `ToolRegistry.execute` 迁出的该域工具执行分支（逐字搬运，行为不变）。
 */
internal object GlassesToolProvider : ToolProvider {
    private const val TAG = "GlassesToolProvider"

    /** 安装超时：installApk 内部可能有约 20s 的 CXR 会话恢复等待 + 传输安装耗时 */
    private const val INSTALL_TIMEOUT_MS = 120_000L

    /** stop/uninstall 超时：无大流量传输，给会话建立与指令往返留余量 */
    private const val SIMPLE_OP_TIMEOUT_MS = 45_000L

    /** 眼镜文件下载上限：蓝牙 ADB 链路速度有限，base64 膨胀 4/3 后仍要秒级~十秒级完成 */
    private const val MAX_DOWNLOAD_BYTES = 8L * 1024 * 1024

    override val toolNames = setOf(
        "get_glasses_status",
        "list_glasses_apps",
        "launch_glasses_app",
        "manage_glasses_app",
        "list_glasses_files",
        "read_glasses_file",
        "download_glasses_file",
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
        ToolEntry(
            name = "manage_glasses_app",
            group = ToolRegistry.DOMAIN_GLASSES,
            displayNameRes = R.string.ai_tool_manage_glasses_app_name,
            descriptionRes = R.string.ai_tool_manage_glasses_app_desc,
            // 定档 EXTERNAL + BLOCK：装/卸应用改变眼镜系统状态、卸载不可恢复 —— 与
            // delete_knowledge_doc 同判据（删除/不可逆必须问用户；确认通道不可用即拒）。
            // AIUI 页面路径因此天然拿不到（BLOCK 问不到用户 = Deny），符合“第三方制品
            // 不该能装卸眼镜应用”的边界。
            risk = ToolRisk.EXTERNAL_SIDE_EFFECT,
            confirmPolicy = ToolConfirmPolicy.BLOCK,
            sideEffect = true,
            requiresGlasses = true,
            statusText = "正在管理眼镜应用…",
            summarize = { args ->
                val action = args.optString("action").trim()
                val target = args.optString("appName").trim().ifEmpty { args.optString("packageName").trim() }
                when (action) {
                    "install" -> "把 APK 安装到眼镜（会先确认）"
                    "stop" -> "停止眼镜应用「$target」"
                    "uninstall" -> "卸载眼镜应用「$target」（不可恢复，会先确认）"
                    else -> "管理眼镜应用"
                }
            },
            schema = toolSchema(
                name = "manage_glasses_app",
                description = "安装、停止或卸载 Rokid 眼镜上的应用。action=install 把手机上的 APK 装进眼镜（apkPath 必填，传 APK 在手机存储里的绝对路径，如 /sdcard/Download/xxx.apk；不确定路径先用 list_files(scope=\"downloads\") 查看，也可让用户把 APK 放进「下载」目录）；action=stop 强停眼镜上正在运行的应用；action=uninstall 卸载应用（不可恢复）。stop/uninstall 靠 appName（用户口中的应用名，原样转述）或 packageName（list_glasses_apps 列出的包名）定位。⚠️ 装与卸都会**先向用户确认**；卸载前先向用户复述应用名，确认后再动手。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "action" to mapOf(
                            "type" to "string",
                            "enum" to listOf("install", "stop", "uninstall"),
                            "description" to "install=安装 APK 到眼镜；stop=停止应用；uninstall=卸载应用",
                        ),
                        "apkPath" to mapOf(
                            "type" to "string",
                            "description" to "action=install 必填：APK 在手机存储里的绝对路径，如 /sdcard/Download/demo.apk",
                        ),
                        "appName" to mapOf(
                            "type" to "string",
                            "description" to "action=stop/uninstall 时优先使用：用户口中的应用名称，原样转述",
                        ),
                        "packageName" to mapOf(
                            "type" to "string",
                            "description" to "可选：确切包名（来自 list_glasses_apps）；传了就不再按 appName 匹配",
                        ),
                    ),
                    "required" to listOf("action"),
                ),
            ),
        ),
        ToolEntry(
            name = "list_glasses_files",
            group = ToolRegistry.DOMAIN_GLASSES,
            displayNameRes = R.string.ai_tool_list_glasses_files_name,
            descriptionRes = R.string.ai_tool_list_glasses_files_desc,
            risk = ToolRisk.READ_ONLY,
            requiresGlasses = true,
            statusText = "正在查看眼镜文件…",
            schema = toolSchema(
                name = "list_glasses_files",
                description = "列出 Rokid 眼镜存储里某个目录的内容（文件名、类型、大小、修改时间）。用户问“眼镜里有什么文件/照片”时先调本工具看路径；要把某个文件拿到手机上用 download_glasses_file，要看文本内容用 read_glasses_file。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "path" to mapOf("type" to "string", "description" to "眼镜存储里的目录路径，如 /sdcard/DCIM/Camera；默认 /sdcard/"),
                    ),
                ),
            ),
        ),
        ToolEntry(
            name = "read_glasses_file",
            group = ToolRegistry.DOMAIN_GLASSES,
            displayNameRes = R.string.ai_tool_read_glasses_file_name,
            descriptionRes = R.string.ai_tool_read_glasses_file_desc,
            risk = ToolRisk.READ_ONLY,
            requiresGlasses = true,
            statusText = "正在读取眼镜文件…",
            schema = toolSchema(
                name = "read_glasses_file",
                description = "读取 Rokid 眼镜存储里一个**文本**文件的内容（日志、配置、txt/json/csv 等）。路径先用 list_glasses_files 查。图片/音频等二进制文件读出来是乱码，拿到手机用 download_glasses_file。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "path" to mapOf("type" to "string", "description" to "眼镜存储里的文件绝对路径，如 /sdcard/log.txt"),
                        "maxChars" to mapOf(
                            "type" to "integer",
                            "description" to "最多返回的字符数，默认 8000，上限 20000；超出会截断并注明",
                            "minimum" to 500,
                            "maximum" to 20000,
                        ),
                    ),
                    "required" to listOf("path"),
                ),
            ),
        ),
        ToolEntry(
            name = "download_glasses_file",
            group = ToolRegistry.DOMAIN_GLASSES,
            displayNameRes = R.string.ai_tool_download_glasses_file_name,
            descriptionRes = R.string.ai_tool_download_glasses_file_desc,
            // 只是把眼镜上的文件拷到手机下载目录（本机可撤销），不是外发 —— LOCAL 档，
            // 与 save_code_file / unzip_file 同判据；不要升 EXTERNAL（每次拷个照片都要确认太烦）。
            risk = ToolRisk.LOCAL_SIDE_EFFECT,
            sideEffect = true,
            requiresGlasses = true,
            statusText = "正在从眼镜下载文件…",
            schema = toolSchema(
                name = "download_glasses_file",
                description = "把 Rokid 眼镜存储里的一个文件（照片/录音/日志等）拷贝到手机「下载」目录，用户在手机文件管理器里就能看到。路径先用 list_glasses_files 查（“眼镜里那张照片传给我”就靠它）。⚠️ 只支持 ≤8MB 的文件（蓝牙链路速度有限），更大的文件如实告知暂不支持。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "path" to mapOf("type" to "string", "description" to "眼镜存储里的文件绝对路径，如 /sdcard/DCIM/Camera/IMG_001.jpg"),
                        "fileName" to mapOf("type" to "string", "description" to "可选：保存到手机时的文件名，默认沿用原文件名"),
                    ),
                    "required" to listOf("path"),
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

            "manage_glasses_app" -> {
                val action = args.optString("action").trim().lowercase()
                val app = context.applicationContext as? LabApplication
                    ?: return "应用上下文异常"
                val dc = app.cxrL.deviceControl
                when (action) {
                    "install" -> {
                        val apkPath = args.optString("apkPath").trim()
                        if (apkPath.isEmpty()) {
                            return "action=install 需要 apkPath：APK 在手机存储里的绝对路径" +
                                "（如 /sdcard/Download/demo.apk；可先用 list_files(scope=\"downloads\") 查看下载目录）"
                        }
                        val apk = File(apkPath)
                        if (!apk.isFile) {
                            return "找不到 APK 文件：$apkPath。请用 list_files(scope=\"downloads\") 确认实际路径，" +
                                "或让用户把 APK 放进手机「下载」目录后再试"
                        }
                        if (!apk.name.endsWith(".apk", ignoreCase = true)) {
                            return "「${apk.name}」不是 .apk 文件，请确认路径指向安装包本身"
                        }
                        // installApk 内部处理 CXR 会话恢复等待（约 20s+），异步回调 → 闩桥接同步
                        val ok = awaitDeviceResult(INSTALL_TIMEOUT_MS) { dc.installApk(apk, it) }
                        if (ok) "已把「${apk.name}」安装到眼镜"
                        else "安装失败：常见原因是眼镜未连接或 CXR 服务未就绪（约需 20 秒重启），请稍后重试"
                    }
                    "stop", "uninstall" -> {
                        val pkg = resolvePackageName(context, args)
                            ?: return "没有找到「${args.optString("appName")}」。请先用 list_glasses_apps " +
                                "确认应用是否存在，并传它列出的包名"
                        val ok = if (action == "stop") {
                            awaitDeviceResult(SIMPLE_OP_TIMEOUT_MS) { dc.stopApp(pkg, it) }
                        } else {
                            awaitDeviceResult(SIMPLE_OP_TIMEOUT_MS) { dc.uninstallApp(pkg, it) }
                        }
                        if (!ok) {
                            "操作失败：请确认眼镜已连接后重试"
                        } else if (action == "stop") {
                            "已停止眼镜应用 $pkg"
                        } else {
                            "已从眼镜卸载 $pkg"
                        }
                    }
                    else -> "action 只支持 install / stop / uninstall，收到的是「$action」"
                }
            }

            "list_glasses_files" -> synchronized(ToolRegistry.adbLock) {
                val client = ToolRegistry.adbClient(context) ?: return "眼镜 ADB 连接失败，无法查看眼镜文件"
                val path = args.optString("path").trim().ifEmpty { "/sdcard/" }
                val raw = runCatching { client.executeShellCommand("ls -la ${shellQuote(path)} 2>/dev/null") }
                    .getOrNull().orEmpty()
                val entries = parseLsLa(raw)
                if (entries.isEmpty()) {
                    "目录为空、路径不存在或 ADB 未连接：$path（可先列 /sdcard/ 确认眼镜存储结构）"
                } else {
                    entries.take(40).joinToString("\n") { e ->
                        val kind = if (e.third) "[目录]" else "[${humanSize(e.second)}]"
                        "$kind ${e.first}"
                    } + if (entries.size > 40) "\n（仅显示前 40 项，共 ${entries.size} 项）" else ""
                }
            }

            "read_glasses_file" -> synchronized(ToolRegistry.adbLock) {
                val client = ToolRegistry.adbClient(context) ?: return "眼镜 ADB 连接失败，无法读取眼镜文件"
                val path = args.optString("path").trim()
                if (path.isEmpty()) return "请提供要读取的眼镜文件路径（先用 list_glasses_files 查）"
                val maxChars = args.optInt("maxChars", 8000).coerceIn(500, 20000)
                val sizeRaw = runCatching { client.executeShellCommand("wc -c < ${shellQuote(path)} 2>/dev/null") }
                    .getOrNull()?.trim()?.toLongOrNull()
                // maxChars 语义是"字符数"：UTF-8 中文每字 3 字节，head -c $maxChars 只能拿到约 1/3，
                // 且截断可能落在多字节序列中间产生残缺字节（被下面的二进制启发式误判成二进制文件）。
                // 先按 4 字节/字符的上限取，再在 Kotlin 侧按字符精确截断
                val headBytes = maxChars * 4L
                val content = runCatching {
                    client.executeShellCommand("head -c $headBytes ${shellQuote(path)} 2>/dev/null")
                }.getOrNull().orEmpty()
                if (content.isBlank() && (sizeRaw == null || sizeRaw == 0L)) {
                    return "读不到内容：文件不存在、为空或无法访问（$path）"
                }
                val truncated = content.length > maxChars || (sizeRaw != null && sizeRaw > headBytes)
                val head = (if (content.length > maxChars) content.take(maxChars) else content) +
                    if (truncated) "\n\n（文件共 $sizeRaw 字节，已截断到前 $maxChars 字符）" else ""
                if (head.any { it.code < 32 && it != '\n' && it != '\r' && it != '\t' }) {
                    head + "\n\n（内容包含不可见/二进制字节 —— 这可能是二进制文件，文本会显示为乱码；图片等请用 download_glasses_file 拿到手机）"
                } else {
                    head
                }
            }

            "download_glasses_file" -> synchronized(ToolRegistry.adbLock) {
                val client = ToolRegistry.adbClient(context) ?: return "眼镜 ADB 连接失败，无法下载眼镜文件"
                val path = args.optString("path").trim()
                if (path.isEmpty()) return "请提供要下载的眼镜文件路径（先用 list_glasses_files 查）"
                val sizeRaw = runCatching { client.executeShellCommand("wc -c < ${shellQuote(path)} 2>/dev/null") }
                    .getOrNull()?.trim()?.toLongOrNull()
                if (sizeRaw != null && sizeRaw > MAX_DOWNLOAD_BYTES) {
                    return "文件 ${humanSize(sizeRaw)} 超过 8MB 上限（蓝牙链路速度有限），暂不支持；" +
                        "可建议用户用眼镜配套的文件管理功能传大文件"
                }
                val b64 = runCatching { client.executeShellCommand("base64 ${shellQuote(path)} 2>/dev/null") }
                    .getOrNull().orEmpty().replace("\\s".toRegex(), "")
                if (b64.isEmpty()) {
                    return "读不到文件内容：路径不存在或无法访问（$path）"
                }
                val bytes = runCatching { android.util.Base64.decode(b64, android.util.Base64.NO_WRAP) }
                    .getOrElse {
                        Log.w(TAG, "download_glasses_file base64 decode failed: ${it.message}")
                        return "文件内容解码失败（base64 损坏），请重试"
                    }
                val fileName = args.optString("fileName").trim()
                    .ifEmpty { path.trimEnd('/').substringAfterLast('/') }
                val saved = runCatching { writeBytesToDownloads(context, fileName, bytes) }
                    .getOrElse {
                        Log.w(TAG, "download_glasses_file save failed: ${it.message}")
                        return "保存到手机下载目录失败（${it.message}），请稍后重试"
                    }
                "已把眼镜上的 ${bytes.size / 1024}KB 文件下载到手机「下载」目录：$saved"
            }

        else -> throw IllegalArgumentException("未知工具: $name")
        }
    }

    /**
     * 把 DeviceControlService 的**异步回调**桥接成工具执行所需的同步返回。
     *
     * 为什么不用 runBlocking 挂协程：底层回调走的是 CXR 会话自己的线程模型，
     * 闩锁 + 超时最小侵入（installApk 内部还有约 20s 的会话恢复等待，超时要给足）。
     * 超时按「未成功」处理并如实回复 —— 绝不把「没等到结果」说成「已完成」。
     */
    private inline fun awaitDeviceResult(timeoutMs: Long, op: (((Boolean) -> Unit)) -> Unit): Boolean {
        val latch = CountDownLatch(1)
        val result = AtomicBoolean(false)
        op { ok ->
            result.set(ok)
            latch.countDown()
        }
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        return result.get()
    }

    /** stop/uninstall 的目标定位：packageName 优先，其次按 appName 在已装列表里模糊匹配 */
    private fun resolvePackageName(context: Context, args: JSONObject): String? {
        args.optString("packageName").trim().takeIf { it.isNotEmpty() }?.let { return it }
        val appName = args.optString("appName").trim()
        if (appName.isEmpty()) return null
        val client = ToolRegistry.adbClient(context) ?: return null
        val installed = synchronized(ToolRegistry.adbLock) {
            runCatching { client.listPackages(false) }.getOrDefault(emptyList())
        }
        return ToolRegistry.matchPackage(appName, installed, context)
    }

    /** 解析眼镜 `ls -la` 输出 → (名字, 大小, 是否目录)，跳过 total 行与 ./.. */
    private fun parseLsLa(raw: String): List<Triple<String, Long, Boolean>> {
        val regex = Regex(
            "^([-dlbc][rwxsStT+\\-.]{9,})\\s+\\S+\\s+\\S+\\s+\\S+\\s+(\\d+)\\s+" +
                "\\d{4}-\\d{2}-\\d{2}\\s+\\d{2}:\\d{2}\\s+(.+)$",
        )
        return raw.lineSequence().mapNotNull { line ->
            val m = regex.find(line.trim()) ?: return@mapNotNull null
            val name = m.groupValues[3].trim()
            if (name == "." || name == "..") return@mapNotNull null
            Triple(name, m.groupValues[2].toLongOrNull() ?: 0L, m.groupValues[1].startsWith("d"))
        }.toList()
    }

    /** 单引号 shell 转义（眼镜 ADB shell 路径含空格/特殊字符时必须） */
    private fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    private fun humanSize(bytes: Long): String = when {
        bytes >= 1 shl 20 -> "%.1fMB".format(Locale.US, bytes / 1048576.0)
        bytes >= 1 shl 10 -> "%.1fKB".format(Locale.US, bytes / 1024.0)
        else -> "${bytes}B"
    }

    /**
     * 二进制落盘到系统下载目录（API29+ MediaStore.Downloads，文件管理器可见；
     * 低版本退化为应用专属下载目录，免存储权限 —— 与 WebTools.writeToDownloads 同口径）。
     * @return 给用户看的展示路径/文件名
     */
    private fun writeBytesToDownloads(context: Context, fileName: String, bytes: ByteArray): String {
        val safeName = fileName.replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), " ")
            .trim().take(80).ifBlank { "glasses_file" }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, safeName)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = context.contentResolver
                .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw java.io.IOException("无法在下载目录创建文件")
            context.contentResolver.openOutputStream(uri)?.use { os -> os.write(bytes) }
                ?: throw java.io.IOException("无法写入文件内容")
            context.contentResolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null,
            )
            safeName
        } else {
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
                ?: context.filesDir
            val file = File(dir, safeName)
            file.writeBytes(bytes)
            file.absolutePath
        }
    }
}
