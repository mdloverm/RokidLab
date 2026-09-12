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
    )

    override fun execute(context: Context, name: String, args: JSONObject): String {
        return when (name) {
            "open_aiui_app" -> {
                val appName = args.optString("appName").trim()
                if (appName.isEmpty()) return "请告诉我要打开哪个智能体应用"
                val agent = ToolRegistry.matchAiuiAgent(context, appName)
                    ?: return "没有找到智能体应用“$appName”。当前可打开：${ToolRegistry.aiuiAgents(context).joinToString("、") { it.name }}"
                val app = context.applicationContext as? LabApplication ?: return "应用上下文异常"
                // 本机有 .aix 包（对话生成 / 本地上传）→ 优先走自托管宿主演示链路：
                // 推送 .aix 到 RokidLink 的 AiuiLinkActivity（官方 ink web 宿主），
                // 眼镜系统按键 / Lab 蓝牙手柄（HID→KeyEvent）可直接操控页面（伪交互已内置）。
                val localAix = AiuiProject.packageFile(context, agent.agentId)
                if (localAix.isFile) {
                    ToolRegistry.lastStartedAiuiAgentId = agent.agentId
                    // 启动参数：模型从用户话里抽取（如“用 AIUI 播放西厢” → {"songName":"西厢"}）。
                    // 非法 JSON 直接丢弃，绝不把脏串下发给页面（页面侧无法容错）。
                    val launchParams = ToolRegistry.parseLaunchParams(args.optString("params"))
                    if (launchParams != null) {
                        Log.i(TAG, "open_aiui_app(${agent.name}) with launch params: $launchParams")
                    }
                    val ack = app.cxrL.pushAixToRokidLinkHost(localAix, launchParams = launchParams)
                    return if (ack?.trim() == "OK") {
                        "好的，正在眼镜上演示「${agent.name}」（手柄可控宿主），可用 Lab 蓝牙手柄操作"
                    } else {
                        "打开「${agent.name}」失败：.aix 推送到眼镜失败${ack?.let { "（$it）" } ?: ""}。请确认眼镜已连接（蓝牙或同一 WiFi）后重试"
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
                val agent = if (appName.isEmpty()) null else ToolRegistry.matchAiuiAgent(context, appName)
                // 眼镜端 stopAiui() 忽略包名直接 AiuiActivity.finishIfRunning()（逆向 AssistServer 确认），
                // 故即使 App 重启、内存无最近记录、或眼镜上跑的是别的 AIUI，也下发占位包名关闭“当前正在显示”的那个，
                // 不再因“不知道是哪个”而放弃（修复“只能退出启动过的”）。
                val targetId = agent?.agentId ?: ToolRegistry.lastStartedAiuiAgentId ?: "current"
                // 本机有 .aix（宿主演示链路打开过/可打开）→ 关闭 AiuiLinkActivity 宿主；
                // 无本地包（官方链路启动的内置/直传应用）→ Sys_AIUI_Stop 关官方渲染层。
                val localAix = AiuiProject.packageFile(context, targetId)
                val result = if (localAix.isFile) {
                    app.cxrL.closeAiuiHost()
                } else {
                    app.cxrL.stopAiuiPackage(targetId)
                }
                if (result == 0) {
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
                val keyword = args.optString("keyword").trim()
                var records = AiuiAppRegistry.list(context)
                if (keyword.isNotEmpty()) {
                    val k = keyword.lowercase()
                    records = records.filter {
                        it.appName.lowercase().contains(k) || (it.project?.lowercase()?.contains(k) == true)
                    }
                }
                if (records.isEmpty()) {
                    if (keyword.isNotEmpty()) "没有找到与“$keyword”相关的 AIUI 智能体应用"
                    else "你还没有生成或上传过 AIUI 智能体应用。可以告诉我想做什么应用，我来帮你生成并装到眼镜上"
                } else {
                    val df = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                    val lines = records.mapIndexed { i, r ->
                        val src = when (r.origin) {
                            AiuiAppRegistry.ORIGIN_UPLOADED -> "本地上传"
                            AiuiAppRegistry.ORIGIN_GENERATED -> "对话生成"
                            else -> "内置/历史"
                        }
                        val proj = r.project?.let { "，项目名:$it" } ?: ""
                        val status = if (r.aixOnGlasses) "已在眼镜" else "未送眼镜"
                        val time = if (r.updatedAt > 0) df.format(Date(r.updatedAt)) else "—"
                        "[${i + 1}] ${r.appName}（$src，$status，更新于 $time$proj）"
                    }
                    "你共有 ${records.size} 个 AIUI 智能体应用：\n" + lines.joinToString("\n") +
                        "\n如需修改某个“对话生成”的应用，告诉我应用名或项目名，我会先读取它现有的代码，改好后再重新安装到眼镜；也可以让我直接打开或关闭某个应用。"
                }
            }

        else -> throw IllegalArgumentException("未知工具: $name")
        }
    }
}
