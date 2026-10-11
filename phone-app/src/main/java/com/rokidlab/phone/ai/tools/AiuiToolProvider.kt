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
import com.rokidlab.phone.aiui.AiuiDemoController
import com.rokidlab.phone.app.LabApplication
import com.rokidlab.phone.adb.ui.TimerAction
import com.rokidlab.phone.adb.ui.TimerSchedule
import com.rokidlab.phone.adb.ui.TimerTask
import com.rokidlab.phone.glasses.AiuiFrontendController
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * AiuiToolProvider —— AIUI 智能体域（打开/关闭/打包安装/列表）。
 * Phase 4 从 `ToolRegistry.execute` 迁出的该域工具执行分支（逐字搬运，行为不变）。
 */
internal object AiuiToolProvider : ToolProvider {
    private const val TAG = "AiuiToolProvider"

    override val toolNames = setOf(
        "open_aiui_app",
        "stop_aiui_app",
        "install_aiui_project",
        "list_my_aiui_apps",
        "pull_official_aiui",
    )

    override fun tools(): List<ToolEntry> = listOf(
        ToolEntry(
            name = "open_aiui_app",
            group = ToolRegistry.DOMAIN_AIUI,
            displayNameRes = R.string.ai_tool_open_aiui_app_name,
            descriptionRes = R.string.ai_tool_open_aiui_app_desc,
            risk = ToolRisk.LOCAL_SIDE_EFFECT,
            sideEffect = true,
            // 手机上演示为本机 WebView 渲染，不需要眼镜 ⇒ 不登记成"必须有眼镜"，
            // 否则「本机模式」下整个工具被摘掉，用户明明能演示却调不到。
            requiresGlasses = false,
            statusText = "正在打开智能体应用…",
            schema = toolSchema(
                name = "open_aiui_app",
                description = "打开一个 AIUI 智能体应用（.aix 卡片应用，如「音乐播放」「天气查询」）。用户说「在手机上/手机上看/演示」→ target=phone（对话里浮出演示卡片，可全屏用触摸操作）；说「在眼镜上/推眼镜」或没说明 → target=glasses。「没说且看不出意图时，先问一句『在手机上看还是眼镜上看』再调用」，不要替用户决定。刚用 save_code_file 生成、还没安装到眼镜的项目也能直接打开：传它的 project 名即可（手机端就地打包演示，眼镜端推送后打开），不必先跑安装。target=phone 只适用于本机有源码或 .aix 的应用，内置官方智能体没有本地包只能 target=glasses。不要仅仅口头回复「已经打开/已经在播放」，必须实际调用本工具。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "appName" to mapOf("type" to "string", "description" to "用户想要打开或使用的智能体应用名称，原样转述，如“音乐播放”“天气查询”"),
                        "target" to mapOf(
                            "type" to "string",
                            "enum" to listOf("phone", "glasses"),
                            "description" to "演示位置：phone=在手机上演示（对话里浮出卡片，可全屏用触摸操作），glasses=在眼镜上打开。按用户说法选；用户没说就先问一句，不要默认替用户决定",
                        ),
                        "params" to mapOf("type" to "string", "description" to "传给该应用的启动参数，JSON 对象字符串（如 {\"songName\":\"七里香\"}）。只要用户要求“用/通过/拿某个智能体去做某事”并给出了具体对象/参数，就必须填写并调用本工具；只是“打开某应用”时可不传。传参用页面期望的参数名（如 songName / keyword / city），不确定就留空，让页面用自己的默认值处理。"),
                        "color" to mapOf(
                            "type" to "boolean",
                            "description" to "仅 target=phone 时可传：以彩色主题打开手机预览（自动打包注入全彩主题 yodaos-sprite-full）。**只有用户明确说出“彩色/全彩/带颜色”时才传 true**，平时不要传；眼镜是单色绿光屏不支持彩色，target=glasses 时禁止传本参数。",
                        ),
                    ),
                    "required" to listOf("appName"),
                ),
            ),
        ),
        ToolEntry(
            name = "stop_aiui_app",
            group = ToolRegistry.DOMAIN_AIUI,
            displayNameRes = R.string.ai_tool_stop_aiui_app_name,
            descriptionRes = R.string.ai_tool_stop_aiui_app_desc,
            risk = ToolRisk.LOCAL_SIDE_EFFECT,
            sideEffect = true,
            requiresGlasses = false,
            statusText = "正在关闭智能体应用…",
            schema = toolSchema(
                name = "stop_aiui_app",
                description = "关闭正在显示的 AIUI 智能体应用（.aix 卡片界面）。手机上演示的卡片与眼镜上的界面都归本工具关闭：用户说“关掉手机上的演示/收起这个卡片” → target=phone；说“退出/关闭眼镜上的 AI 应用/退出 AIUI/返回” → target=glasses。用户没说位置时不必追问，按最近一次是在手机还是眼镜上展示的关即可。无需知道应用名也能关闭当前正在显示的那个。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "appName" to mapOf("type" to "string", "description" to "要关闭的智能体应用名称（可选）。若用户没提名字，说明是刚打开/生成的那个，可不传本参数"),
                        "target" to mapOf(
                            "type" to "string",
                            "enum" to listOf("phone", "glasses"),
                            "description" to "关闭位置：phone=收掉手机对话里的演示卡片，glasses=关眼镜上的界面。用户没说时留空即可，会按最近一次展示的位置关闭",
                        ),
                    ),
                ),
            ),
        ),
        ToolEntry(
            name = "install_aiui_project",
            group = ToolRegistry.DOMAIN_AIUI,
            displayNameRes = R.string.ai_tool_install_aiui_project_name,
            descriptionRes = R.string.ai_tool_install_aiui_project_desc,
            risk = ToolRisk.LOCAL_SIDE_EFFECT,
            sideEffect = true,
            requiresGlasses = true,
            statusText = "正在打包并安装 AIUI 项目…",
            summarize = { args -> "安装 AIUI 应用" },
            schema = toolSchema(
                name = "install_aiui_project",
                description = "把对话中已通过写文件工具生成的 AIUI 项目打包成 .aix 并推送到 Rokid 眼镜（登记后可语音打开/演示）。当用户说“把这个项目/应用装到眼镜上”“安装我做的 AI 应用”“打包这个 AIUI 项目”时调用；前提是先调用写文件工具生成该项目（须含 app.json 与 pages/index/index.ink）。本工具只负责打包推送，不自动打开（需用户确认后再用 open_aiui_app 演示，避免打断眼镜当前画面）。对已生成的项目做修改后重新安装到眼镜时，同样用本工具（同一 project 覆盖更新，源码有变化眼镜端会自动重新解压加载）。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "project" to mapOf("type" to "string", "description" to "要安装的项目名（与写文件时的 project 参数完全一致），如 aiui-demo"),
                        "appName" to mapOf("type" to "string", "description" to "安装后在眼镜上显示的应用名（用户之后会说“打开这个名字”），简洁可念，如“记单词助手”"),
                    ),
                    "required" to listOf("project", "appName"),
                ),
            ),
        ),
        ToolEntry(
            name = "list_my_aiui_apps",
            group = ToolRegistry.DOMAIN_AIUI,
            displayNameRes = R.string.ai_tool_list_my_aiui_apps_name,
            descriptionRes = R.string.ai_tool_list_my_aiui_apps_desc,
            risk = ToolRisk.READ_ONLY,
            requiresGlasses = true,
            statusText = "正在查看我的 AI 应用…",
            schema = toolSchema(
                name = "list_my_aiui_apps",
                description = "列出用户在本机生成/上传过的 AIUI 智能体应用记录（名称、项目名、最近更新时间、是否已送到眼镜、来源）。当用户问“我生成过哪些 AI 应用/智能体”“我之前做的那个 AIUI”“我有哪些 AI 应用”，或需要再次修改/打开/安装历史 AIUI 项目时先调用本工具拿到项目名（project）；随后要修改代码时先调 read_code_file 读取现网源码，再用 save_code_file 覆盖写回、install_aiui_project 重装。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "keyword" to mapOf("type" to "string", "description" to "可选：按应用名/项目名过滤的关键字，不传则列出全部"),
                    ),
                ),
            ),
        ),
        ToolEntry(
            name = "pull_official_aiui",
            group = ToolRegistry.DOMAIN_AIUI,
            displayNameRes = R.string.ai_tool_pull_official_aiui_name,
            descriptionRes = R.string.ai_tool_pull_official_aiui_desc,
            risk = ToolRisk.LOCAL_SIDE_EFFECT,
            sideEffect = true,
            requiresGlasses = true,
            statusText = "正在从眼镜获取官方 AIUI 应用…",
            summarize = { args ->
                val n = args.optString("appName").trim()
                if (n.isEmpty()) "获取官方 AIUI 应用" else "获取官方 AIUI：$n"
            },
            schema = toolSchema(
                name = "pull_official_aiui",
                description = "把眼镜上已安装的官方 AIUI 智能体应用（商店下载，.aix 落在眼镜 AssistServer 私有 remote_sync 目录）拉取到手机：解包出可编辑源码 + 保留 .aix 本地包，登记进 AIUI 应用管理并标记为「官方」。清单来自眼镜日志中的云端同步记录（syncAgentList），拉包走眼镜 WebServer /server/downloadFile。触发口径：用户说“获取官方aiui/拉取官方智能体/把眼镜上的官方应用弄到手机/导入官方 AI 应用”等时调用。不传 appName = 拉取全部；传 appName（可模糊）= 只拉匹配的那个。拉取是幂等的：已拉过的应用自动跳过。拉取副本用独立 agentId，与眼镜官方包完全隔离 —— 删除记录只删手机端，永不触碰眼镜官方包。拉取后用户可直接“打开 <名字>”（走 Lab ink 宿主）或“修改 <名字>”。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "appName" to mapOf("type" to "string", "description" to "可选：只要拉取名字匹配的这个官方应用（模糊匹配）；不传则拉取眼镜上全部官方应用"),
                    ),
                ),
            ),
        ),
    )

    override fun execute(context: Context, name: String, args: JSONObject): String {
        return when (name) {
            "open_aiui_app" -> {
                val appName = args.optString("appName").trim()
                if (appName.isEmpty()) return "请告诉我要打开哪个智能体应用"
                val targetPhone = args.optString("target").trim().equals("phone", ignoreCase = true)
                // 彩色预览：用户明确要求"彩色/全彩"时才为 true（眼镜单色绿光屏不支持彩色，
                // 手机包打包时注入 yodaos-sprite-full，眼镜包始终走默认绿）
                val colorRequested = args.optBoolean("color", false)
                if (colorRequested && !targetPhone) {
                    return "彩色显示只有手机预览支持（眼镜屏是单色绿光波导，物理上没有彩色通道）。" +
                        "可以先用 target=phone 的彩色版看效果；要看真实眼镜效果请去掉彩色要求"
                }
                var agent = ToolRegistry.matchAiuiAgent(context, appName)
                var colorAix: java.io.File? = null
                // 注册表里没有 → 可能是**刚生成、还没送到眼镜**的项目（注册表只由 install_aiui_project 写入）。
                // 这里就地打包 + 登记：手机上演示本来就不需要眼镜，"送到眼镜"也只是推一次，
                // 都不该逼用户先单独跑一遍安装流程。
                if (agent == null) {
                    val proj = AiuiProject.matchLocalProject(context, appName)
                    if (proj != null) {
                        val display = AiuiProject.projectDisplayName(context, proj)
                        val built = AiuiProject.buildAix(context, proj, display)
                            ?: return "「$proj」还不符合 AIUI 规范（缺 app.json / pages/index/index.ink，或页面写法不合规），打不开。" +
                                "需要我先读一遍源码找问题吗"
                        // 之前装过眼镜的记录沿用原展示名，别因为 AGENTS.md 标题不同把名字改掉
                        val known = AiuiAppRegistry.getByAgentId(context, built.agentId)
                        val name = known?.appName ?: display
                        if (known == null) {
                            AiuiAppRegistry.upsert(
                                context,
                                AiuiAppRegistry.AiuiAppRecord(
                                    appName = name,
                                    agentId = built.agentId,
                                    project = proj,
                                    pageName = built.pageName,
                                    sourceProjectDir = AiuiProject.projectDir(context, proj).absolutePath,
                                    origin = AiuiAppRegistry.ORIGIN_GENERATED,
                                    aixOnGlasses = false,
                                ),
                            )
                        }
                        if (colorRequested) {
                            // 彩色预览：绿包照常登记（后续装眼镜不受影响），另出一份彩色包
                            colorAix = AiuiProject.buildAix(context, proj, display, colorTheme = true)?.aixFile
                                ?: return "「$proj」彩色版打包失败"
                        }
                        agent = ToolRegistry.matchAiuiAgent(context, name)
                    }
                }
                if (agent == null) {
                    return "没有找到智能体应用“$appName”。当前可打开：${ToolRegistry.aiuiAgents(context).joinToString("、") { it.name }}"
                }
                val app = context.applicationContext as? LabApplication ?: return "应用上下文异常"
                // 启动参数：模型从用户话里抽取（如“用 AIUI 播放西厢” → {"songName":"西厢"}）。
                // 非法 JSON 直接丢弃，绝不把脏串下发给页面（页面侧无法容错）。
                val launchParams = ToolRegistry.parseLaunchParams(args.optString("params"))
                if (launchParams != null) {
                    Log.i(TAG, "open_aiui_app(${agent.name}) with launch params: $launchParams")
                }
                // 本机有 .aix 包（对话生成 / 本地上传）；无本地包 = 内置/官方智能体
                val localAix = AiuiProject.packageFile(context, agent.agentId)

                // ── 手机端演示：本机 WebView 用同一套 ink 引擎渲染，浮在对话里 ──
                // 与眼镜那样"推过去再戴上看"相比，这条路省掉了整段投屏链路，改一版看一眼只要几秒。
                if (targetPhone) {
                    var demoAix = localAix
                    if (colorRequested) {
                        if (colorAix == null) {
                            // agent 早已登记（非本轮就地打包）→ 从登记的源码项目现打彩色包
                            val proj = AiuiAppRegistry.getByAgentId(context, agent.agentId)
                                ?.project?.takeIf { it.isNotBlank() }
                                ?: return "「${agent.name}」本地没有源码项目，出不了彩色版（只有对话生成的项目支持彩色预览）"
                            colorAix = AiuiProject.buildAix(context, proj, agent.name, colorTheme = true)?.aixFile
                                ?: return "「${agent.name}」彩色版打包失败（项目可能不符合 AIUI 规范，需要我先读一遍源码找问题吗）"
                        }
                        demoAix = colorAix
                    }
                    if (!demoAix.isFile) {
                        return "「${agent.name}」是内置/官方智能体，本机没有 .aix 包，只能在眼镜上打开；" +
                            "手机上演示目前只支持对话生成或本地上传的应用"
                    }
                    ToolRegistry.lastStartedAiuiAgentId = agent.agentId
                    ToolRegistry.lastStartedAiuiHost = ToolRegistry.AiuiHost.PHONE_HOST
                    // 清掉上一次运行的日志（P2-15）：否则这次打开会把"上一版的报错"当成本次的问题
                    // 读给模型 —— 比没有日志更糟（会把它引向一个已经不存在的 bug）。
                    com.rokidlab.phone.aiui.AiuiPageLog.clear(demoAix.nameWithoutExtension)
                    AiuiDemoController.show(
                        AiuiDemoController.Session(
                            appName = agent.name,
                            agentId = agent.agentId,
                            aix = demoAix,
                            launchParams = launchParams,
                        ),
                    )
                    val colorNote = if (colorRequested) "彩色版，" else ""
                    return "好的，已在手机上演示「${agent.name}」（${colorNote}对话里的卡片，点「全屏操作」可用触摸滑动与点击）" +
                        withPageLogs(demoAix.nameWithoutExtension)
                }

                // ── 眼镜端 ──
                if (localAix.isFile) {
                    // 本机生成的包 → 优先走自托管宿主：推 .aix 到 RokidLink 的 AiuiLinkActivity
                    // （官方 ink web 宿主），眼镜系统按键 / Lab 蓝牙手柄（HID→KeyEvent）可直接操控页面
                    ToolRegistry.lastStartedAiuiAgentId = agent.agentId
                    val ack = app.cxrL.pushAixToRokidLinkHost(localAix, launchParams = launchParams)
                    // 只有真的推成功才算"当前显示的是自托管宿主" —— 否则记成 LOCAL 会让
                    // 随后的 stop 发一条注定无效的 close 命令（关不掉，用户看到的是没反应）
                    if (ack?.trim() == "OK") ToolRegistry.lastStartedAiuiHost = ToolRegistry.AiuiHost.LOCAL_HOST
                    return when (ack?.trim()) {
                        "OK" ->
                            "好的，正在眼镜上演示「${agent.name}」（手柄可控宿主），可用 Lab 蓝牙手柄操作"
                        AiuiFrontendController.PUSHED_OPEN_FAILED ->
                            "「${agent.name}」已推送到眼镜，但宿主未能拉起（可能被系统后台启动限制拦截）。" +
                                "请在眼镜上重试，或检查 RokidLink 的悬浮窗权限"
                        else ->
                            "打开「${agent.name}」失败：.aix 推送到眼镜失败${ack?.let { "（$it）" } ?: ""}。" +
                                "请确认眼镜已连接（蓝牙或同一 WiFi）后重试"
                    }
                }
                // 无本地包（内置/官方智能体）：旧官方链路（直传过 cxr 目录的走 Sys_AIUI_Start，其余走 Ai_RenderPayload）
                val result = if (agent.aixOnGlasses) {
                    app.cxrL.startAiuiPackage(agent.agentId)
                } else {
                    app.cxrL.openAiuiAgent(agent.agentId, agent.name, agent.nativeVersion, agent.pageName)
                }
                if (result == 0) {
                    // 记住最近成功打开的包，供 stop_aiui_app 无名称时兜底关闭
                    ToolRegistry.lastStartedAiuiAgentId = agent.agentId
                    ToolRegistry.lastStartedAiuiHost = ToolRegistry.AiuiHost.OFFICIAL
                    if (agent.aixOnGlasses) {
                        "好的，正在眼镜上打开「${agent.name}」，请看向眼镜"
                    } else {
                        "已为你打开智能体应用「${agent.name}」"
                    }
                } else if (agent.aixOnGlasses) {
                    "打开「${agent.name}」失败（错误码 $result）。请先让我重新生成并送到眼镜，再询问是否打开"
                } else {
                    "打开「${agent.name}」失败（错误码 $result），请确认眼镜已连接且该智能体已安装"
                }
            }

            "stop_aiui_app" -> {
                val app = context.applicationContext as? LabApplication ?: return "应用上下文异常"
                val appName = args.optString("appName").trim()
                val target = args.optString("target").trim().lowercase()
                val agent = if (appName.isEmpty()) null else ToolRegistry.matchAiuiAgent(context, appName)
                // 眼镜端 stopAiui() 忽略包名直接 AiuiActivity.finishIfRunning()（逆向 AssistServer 确认），
                // 故即使 App 重启、内存无最近记录、或眼镜上跑的是别的 AIUI，也下发占位包名关闭“当前正在显示”的那个，
                // 不再因“不知道是哪个”而放弃（修复“只能退出启动过的”）。
                val targetId = agent?.agentId ?: ToolRegistry.lastStartedAiuiAgentId ?: "current"
                // 关哪条链路：优先用**打开时记录的宿主**（唯一可靠依据）。
                // 记录不到（App 重启过）才退回"本地有没有这个 .aix"的启发式 —— 它是旧实现的
                // 全部依据，但只对"最近打开的就是这个包"成立。
                val host = ToolRegistry.lastStartedAiuiHost
                    ?: if (AiuiProject.packageFile(context, targetId).isFile) {
                        ToolRegistry.AiuiHost.LOCAL_HOST
                    } else {
                        ToolRegistry.AiuiHost.OFFICIAL
                    }
                // 手机演示卡片在决定关哪边之前先看掉：它是浮在用户眼前的东西，
                // "关掉"十有八九指的就是它；显式 target=glasses 时才绕开。
                if (target != "glasses") {
                    if (host == ToolRegistry.AiuiHost.PHONE_HOST || target == "phone") {
                        val demo = AiuiDemoController.current
                            ?: return "手机上当前没有在演示的应用"
                        // 用 dismissAll：用户可能正待在全屏操作页里，只清浮层等于"什么都没关"
                        AiuiDemoController.dismissAll()
                        if (host == ToolRegistry.AiuiHost.PHONE_HOST) ToolRegistry.lastStartedAiuiHost = null
                        return "好的，已关闭手机上的「${demo.appName}」演示"
                    }
                }
                val result = when (host) {
                    // 自托管宿主：关 RokidLink 里渲染 .aix 的 AiuiLinkActivity
                    ToolRegistry.AiuiHost.LOCAL_HOST -> app.cxrL.closeAiuiHost()
                    // 官方渲染层（内置 / 直传 cxr 目录的包）：Sys_AIUI_Stop。
                    // PHONE_HOST 落到这里只有一种情形：用户显式 target=glasses（手机演示那支已在上面返回），
                    // 眼镜侧不认包名，照发官方 stop 关"当前正在显示"的那个。
                    ToolRegistry.AiuiHost.OFFICIAL,
                    ToolRegistry.AiuiHost.PHONE_HOST -> app.cxrL.stopAiuiPackage(targetId)
                }
                if (result == 0) {
                    // 已经关掉了，"当前显示的是哪条链路"随之失效 —— 置空，避免下次"关掉"
                    // 照旧发一条发给已关闭页面的命令（无害但会产生误导性的日志）
                    ToolRegistry.lastStartedAiuiHost = null
                    if (agent != null) "好的，已关闭「${agent.name}」的智能体界面"
                    else "好的，已关闭眼镜上正在显示的智能体应用"
                } else {
                    "关闭失败（错误码 $result）。请确认眼镜已连接"
                }
            }

            "install_aiui_project" -> {
                val project = args.optString("project").trim()
                val appName = args.optString("appName").trim()
                if (project.isEmpty()) return "请提供要打包安装的项目名（与生成该项目时相同）"
                if (appName.isEmpty()) return "请提供这个应用在眼镜上显示的名称（之后可以说“打开这个名字”）"
                // 打包前整体校验：app.json/pages 结构 + 每个 .ink 的 <page> 根块规范，
                // 不合规直接把原因回给模型让其重写对应文件（防止 Vue 风格页面打进包 → 眼镜黑屏）
                val projErr = AiuiProject.validateAiuiProject(context, project)
                if (projErr != null) return projErr
                // 打包 .aix（缺 VERSION/app.js/AGENTS.md 自动补齐；buildAix 内部会再次兜底校验）
                val aix = AiuiProject.buildAix(context, project, appName)
                    ?: return "打包失败：项目“$project”不符合 AIUI 规范或缺少必要文件（app.json / pages/index/index.ink）。请先用“保存代码文件”工具生成，再让我安装"
                // 直传眼镜 cxr 目录（同名覆盖；只送不启，避免未经确认就打断用户当前画面）
                val uploadErr = AiuiProject.uploadAixToGlasses(context, aix.aixFile)
                if (uploadErr != null) {
                    return "已生成「$appName」安装包（${aix.aixFile.length() / 1024}KB），但传到眼镜失败：$uploadErr"
                }
                // 登记为「已直传 cxr 目录」（含项目关联，供语音回忆/再编辑与管理页更新重装）
                AiuiAppRegistry.upsert(
                    context,
                    AiuiAppRegistry.AiuiAppRecord(
                        appName = appName,
                        agentId = aix.agentId,
                        project = project,
                        pageName = aix.pageName,
                        sourceProjectDir = AiuiProject.projectDir(context, project).absolutePath,
                        origin = AiuiAppRegistry.ORIGIN_GENERATED,
                        aixOnGlasses = true,
                    ),
                )
                "已把「$appName」打包并送到眼镜（${aix.aixFile.length() / 1024}KB），还没有打开。需要我现在在眼镜上打开它吗？"
            }

            "list_my_aiui_apps" -> {
                val keyword = args.optString("keyword").trim().lowercase()
                var records = AiuiAppRegistry.list(context)
                // 只有源码、还没登记过的本机项目（从未安装、也从未在手机上演示过）：
                // 不列出来的话，用户说“把之前做的那个打开看看”时模型在清单里找不到它 ——
                // 而本机项目本来就**不需要先装到眼镜**就能直接演示。
                var localOnly = AiuiProject.localProjects(context).filter { p -> records.none { it.project == p } }
                if (keyword.isNotEmpty()) {
                    records = records.filter {
                        it.appName.lowercase().contains(keyword) || (it.project?.lowercase()?.contains(keyword) == true)
                    }
                    localOnly = localOnly.filter {
                        it.lowercase().contains(keyword) ||
                            AiuiProject.projectDisplayName(context, it).lowercase().contains(keyword)
                    }
                }
                if (records.isEmpty() && localOnly.isEmpty()) {
                    if (keyword.isNotEmpty()) "没有找到与“$keyword”相关的 AIUI 智能体应用"
                    else "你还没有生成或上传过 AIUI 智能体应用。可以告诉我想做什么应用，我来帮你生成"
                } else {
                    val df = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                    val rows = ArrayList<String>(records.size + localOnly.size)
                    records.forEach { r ->
                        val src = when (r.origin) {
                            AiuiAppRegistry.ORIGIN_UPLOADED -> "本地上传"
                            AiuiAppRegistry.ORIGIN_GENERATED -> "对话生成"
                            AiuiAppRegistry.ORIGIN_OFFICIAL -> "官方拉取"
                            else -> "内置/历史"
                        }
                        val proj = r.project?.let { "，项目名:$it" } ?: ""
                        val status = if (r.aixOnGlasses) "已在眼镜" else "未送眼镜"
                        val time = if (r.updatedAt > 0) df.format(Date(r.updatedAt)) else "—"
                        rows.add("${r.appName}（$src，$status，更新于 $time$proj）")
                    }
                    localOnly.forEach { p ->
                        rows.add("${AiuiProject.projectDisplayName(context, p)}（对话生成，还没送到眼镜，项目名:$p）")
                    }
                    val lines = rows.mapIndexed { i, s -> "[${i + 1}] $s" }
                    "你共有 ${rows.size} 个 AIUI 智能体应用：\n" + lines.joinToString("\n") +
                        "\n如需修改某个“对话生成/官方拉取”的应用，告诉我应用名或项目名，我会先读取它现有的代码，改好后再在手机上演示确认、必要时重新安装到眼镜；也可以让我直接打开或关闭某个应用。"
                }
            }

            "pull_official_aiui" -> {
                val appName = args.optString("appName").trim()
                val app = context.applicationContext as? LabApplication ?: return "应用上下文异常"
                // 清单双来源合并：
                //  1) 眼镜 logcat 的 syncAgentList（增量：新装的官方应用；日志冲掉后拿不到）
                //  2) 手机端持久化清单 aiui_official_list.json（兜底：拉取成功过一次就永久在）
                // 拉包走 WebServer downloadFile（WiFi/蓝牙隧道），不依赖 ADB ——
                // 即使 logcat 抓不到（ADB 断/缓冲冲掉），本地清单依然能全量重建。
                val agentIds: List<String>
                val nameMap: Map<String, String>
                run {
                    val merged = LinkedHashMap<String, String>() // id -> name（本地清单打底）
                    AiuiProject.loadOfficialList(context).forEach { (id, name) -> merged[id] = name }
                    val listed = AiuiProject.listOfficialAgents(app)
                    if (listed != null) {
                        val (ids, names) = listed
                        ids.forEach { id -> merged.putIfAbsent(id, names[id] ?: id) }
                        names.forEach { (id, name) -> merged[id] = name }
                    }
                    agentIds = merged.keys.toList()
                    nameMap = merged
                }
                if (agentIds.isEmpty()) {
                    return "没有找到官方 AIUI 应用清单。" +
                        "请先在官方 App 下载至少一个 AIUI 应用，或在眼镜上打开一次 AI 应用列表（触发与云端同步），再试一次。"
                }
                fun nameMatch(n: String): Boolean {
                    if (appName.isEmpty()) return true
                    val q = appName.lowercase()
                    val l = n.lowercase()
                    return l.contains(q) || q.contains(l)
                }
                val pulled = ArrayList<String>()
                val known = ArrayList<String>()
                val fails = ArrayList<String>()
                val seenNames = LinkedHashMap<String, String>() // 原始 agentId -> 干净名字（拉取后落盘）
                for (id in agentIds) {
                    val newId = AiuiProject.officialAgentId(id)
                    // 幂等：登记过且本地 .aix 还在的官方副本直接跳过（只报名字，不重拉）
                    val cached = AiuiAppRegistry.getByAgentId(context, newId)
                    if (cached != null && AiuiProject.packageFile(context, newId).isFile) {
                        // 名字自动纠正：早期版本正则跨行污染过 appName（"陀螺战士"\nagentId...），
                        // 云端映射的名字更干净时刷新登记
                        var finalName = cached.appName
                        val clean = nameMap[id]
                        if (clean != null && clean != cached.appName) {
                            finalName = clean
                            AiuiAppRegistry.upsert(context, cached.copy(appName = clean))
                        }
                        seenNames[id] = finalName
                        if (nameMatch(finalName)) known += finalName
                        continue
                    }
                    val local = java.io.File(context.cacheDir, "official_pull_$newId.aix")
                    val pullErr = AiuiProject.pullAixFromGlasses(
                        context,
                        "${AiuiProject.OFFICIAL_AIX_DIR}/$id.aix",
                        local,
                    )
                    if (pullErr != null) {
                        fails += "$id：$pullErr"
                        continue
                    }
                    // 名字优先取云端同步映射（agentName），包内解析（AGENTS.md/页面标题）兜底
                    val displayName = nameMap[id]?.takeIf { it.isNotBlank() }
                        ?: AiuiProject.parseAixDisplayName(local)
                        ?: id
                    if (!nameMatch(displayName)) {
                        runCatching { local.delete() }
                        continue
                    }
                    // 项目目录名 = 展示名（清洗在 projectDir 内做）；目录已归属其他 agentId 时加后缀互踩隔离
                    var project = displayName
                    val dir = AiuiProject.projectDir(context, project)
                    val dirAgent = runCatching { java.io.File(dir, ".rokid_agent_id").readText().trim() }.getOrNull()
                    if (dir.isDirectory && dirAgent != newId) project = "${displayName}_${id.take(8)}"
                    val unpackErr = AiuiProject.unpackAixToProject(context, local, project, newId)
                    if (unpackErr != null) {
                        fails += "$displayName：$unpackErr"
                        runCatching { local.delete() }
                        continue
                    }
                    // .aix 保留为本地包副本：open_aiui_app 见本地包即走 Lab ink 宿主（不碰眼镜官方包）
                    runCatching {
                        local.copyTo(AiuiProject.packageFile(context, newId), overwrite = true)
                        local.delete()
                    }
                    val pageName = runCatching {
                        org.json.JSONObject(
                            java.io.File(AiuiProject.projectDir(context, project), "app.json").readText(),
                        ).optJSONArray("pages")?.optString(0)?.takeIf { it.isNotBlank() }
                    }.getOrNull() ?: AiuiAppRegistry.DEFAULT_PAGE_NAME
                    AiuiAppRegistry.upsert(
                        context,
                        AiuiAppRegistry.AiuiAppRecord(
                            appName = displayName,
                            agentId = newId,
                            project = project,
                            pageName = pageName,
                            sourceProjectDir = AiuiProject.projectDir(context, project).absolutePath,
                            origin = AiuiAppRegistry.ORIGIN_OFFICIAL,
                            // 官方原包确实还装在眼镜 remote_sync 目录 —— 如实登记；
                            // 但 official 记录的删除/重装护栏保证永远不会触达它
                            aixOnGlasses = true,
                        ),
                    )
                    pulled += displayName
                    seenNames[id] = displayName
                }
                // 成功确认过的（新拉取 + 已登记）落盘持久化：下次日志冲掉也能凭清单全量重建
                if (seenNames.isNotEmpty()) AiuiProject.saveOfficialList(context, seenNames)
                val parts = ArrayList<String>()
                if (pulled.isNotEmpty()) parts.add("新拉取 ${pulled.size} 个：" + pulled.joinToString("、"))
                if (known.isNotEmpty()) parts.add("之前已拉取 ${known.size} 个：" + known.joinToString("、"))
                if (fails.isNotEmpty()) parts.add("失败 ${fails.size} 个：\n" + fails.joinToString("\n"))
                if (parts.isEmpty()) return "眼镜上没有匹配“$appName”的官方 AIUI 应用"
                "已获取官方 AIUI 应用（${parts.joinToString("；")}）。\n" +
                    "源码已保存在手机上并标记为「官方」：说“打开 <名字>”可在 Lab ink 宿主演示，" +
                    "说“修改 <名字>”我会读取它的源码按要求修改。删除这条记录只会删手机端副本，不会动眼镜上的官方包。"
            }

        else -> throw IllegalArgumentException("未知工具: $name")
        }
    }

    /**
     * 把**页面运行日志**附在打开结果后面（2026-10-09，P2-15）。
     *
     * ## 为什么必须给 agent 看
     *
     * gps-live 案的决定性证据是一句运行时错误
     * （`Host capability geolocation.getCurrentPosition is not configured on Web host`），
     * 它只进了 logcat —— 而 agent **读不到 logcat**（`read_screen` 只能看像素）。
     * 于是它只能照着用户一句"拿不到位置"猜，猜成了"HTTPS/浏览器限制"（错因），
     * 后续 3 轮全在这个错前提上打转。
     *
     * 判据：**凡是"只有用户能看见、agent 看不见"的故障信号，都是必然的误诊来源。**
     *
     * @param logKey 与 [com.rokidlab.phone.aiui.AiuiWebHost] 的分桶键一致（= `.aix` 文件名去扩展名）
     * @return 追加文本；无日志时返回空串（不污染正常结果）
     */
    private fun withPageLogs(logKey: String?): String {
        val logs = com.rokidlab.phone.aiui.AiuiPageLog.recent(logKey)
        if (logs.isBlank()) return ""
        val warn = if (com.rokidlab.phone.aiui.AiuiPageLog.hasError(logKey)) {
            "\n\n⚠️ **页面有报错**（下面 error 行就是原因；据此判断问题出在哪，不要凭猜测回答）："
        } else {
            "\n\n（页面运行日志，供你判断页面是否真的正常跑起来了）"
        }
        return warn + "\n" + logs
    }
}
