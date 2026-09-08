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
import java.io.File
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

    // ═══════════════════ 工具域（domain）════════════════════════════
    // 每个工具归属一个稳定域；会话按场景声明要装配的域集合（assemble 时 = 域 ∩ 已开启开关），
    // 避免把所有工具 Schema 无条件塞进每次请求 —— 工具越多越必须按需装配（业界 Agent 共识）。
    const val DOMAIN_INFO = "info"          // 基础信息（时间等，几乎总是需要）
    const val DOMAIN_KNOWLEDGE = "knowledge" // 本地知识库检索
    const val DOMAIN_GLASSES = "glasses"    // 眼镜设备（ADB 查询/控制）
    const val DOMAIN_TIMER = "timer"        // 定时提醒/任务
    const val DOMAIN_MEDIA = "media"        // 音乐播放/歌词
    const val DOMAIN_WEB = "web"            // 联网搜索/读网页
    const val DOMAIN_FILES = "files"        // 文件产出（总结 txt / 代码落盘）
    const val DOMAIN_AIUI = "aiui"          // AIUI 智能体应用（生成/安装/打开/管理）

    /** 全部工具域（主 Agent 默认全量装配，未来可拆出子集） */
    val DOMAIN_ALL: Set<String> = setOf(
        DOMAIN_INFO, DOMAIN_KNOWLEDGE, DOMAIN_GLASSES, DOMAIN_TIMER,
        DOMAIN_MEDIA, DOMAIN_WEB, DOMAIN_FILES, DOMAIN_AIUI,
    )

    /** 主 Agent 会话（眼镜语音/手机聊天，在线模型）装配的工具域 */
    val SESSION_AGENT_DOMAINS: Set<String> = DOMAIN_ALL

    /** 本地轻量会话装配的工具域：空集 —— 本地小模型背不动数十个工具 Schema，
     * 每轮全量下发只会拖慢 prefill 且小模型调用工具本就不可靠；
     * 设备/联网等能力由用户切回在线 Agent 时提供。 */
    val SESSION_LOCAL_DOMAINS: Set<String> = emptySet()

    /**
     * AIUI/代码生成会话装配的工具域：AIUI + 文件产出 + 基础信息。
     * 模型命中 aiui-dev 技能（或开始写代码）后，主循环把 tools 从全量域切换为本子集，
     * 每轮少发 ~14 个无关工具 Schema，显著降低 input token 与 prefill 耗时；
     * load_skill/load_skill_section 属技能伪工具，切换后由调用方单独保留。
     */
    val SESSION_AIUI_DOMAINS: Set<String> = setOf(DOMAIN_AIUI, DOMAIN_FILES, DOMAIN_INFO)

    /** 代码/项目文件落盘工具名（把生成的文件写入手机下载目录的对应项目文件夹） */
    const val TOOL_CODE_FILE = "save_code_file"

    /** 代码/项目文件读取工具名（读取已生成项目的当前源码，供修改/微调时参考） */
    const val TOOL_READ_CODE_FILE = "read_code_file"

    /**
     * ADB 工具串行锁：ADB 工具共享同一常驻 client（app.cxrL.getAdbShellClient()），
     * 不随单次工具调用断开（避免高频重建隧道），故并发执行必须串行走同一条连接。
     * Agent 工具循环并发执行 toolCalls 时，ADB 类工具通过此锁串行，非 ADB 工具仍真并发。
     */
    private val adbLock = Any()

    /** 工具元数据（设置页展示用，名称/描述走多语言资源；group 用于按会话装配） */
    data class ToolMeta(
        val name: String,
        val group: String,
        val displayNameRes: Int,
        val descriptionRes: Int,
    )

    /** 全部工具（含已禁用），按声明顺序 */
    val toolList: List<ToolMeta> = listOf(
        ToolMeta(
            name = "search_knowledge_base",
            group = DOMAIN_KNOWLEDGE,
            displayNameRes = R.string.ai_tool_search_knowledge_base_name,
            descriptionRes = R.string.ai_tool_search_knowledge_base_desc,
        ),
        ToolMeta(
            name = "get_current_time",
            group = DOMAIN_INFO,
            displayNameRes = R.string.ai_tool_get_current_time_name,
            descriptionRes = R.string.ai_tool_get_current_time_desc,
        ),
        ToolMeta(
            name = "get_glasses_battery",
            group = DOMAIN_GLASSES,
            displayNameRes = R.string.ai_tool_get_glasses_battery_name,
            descriptionRes = R.string.ai_tool_get_glasses_battery_desc,
        ),
        ToolMeta(
            name = "get_glasses_device_info",
            group = DOMAIN_GLASSES,
            displayNameRes = R.string.ai_tool_get_glasses_device_info_name,
            descriptionRes = R.string.ai_tool_get_glasses_device_info_desc,
        ),
        ToolMeta(
            name = "get_glasses_storage",
            group = DOMAIN_GLASSES,
            displayNameRes = R.string.ai_tool_get_glasses_storage_name,
            descriptionRes = R.string.ai_tool_get_glasses_storage_desc,
        ),
        ToolMeta(
            name = "list_glasses_apps",
            group = DOMAIN_GLASSES,
            displayNameRes = R.string.ai_tool_list_glasses_apps_name,
            descriptionRes = R.string.ai_tool_list_glasses_apps_desc,
        ),
        ToolMeta(
            name = "launch_glasses_app",
            group = DOMAIN_GLASSES,
            displayNameRes = R.string.ai_tool_launch_glasses_app_name,
            descriptionRes = R.string.ai_tool_launch_glasses_app_desc,
        ),
        ToolMeta(
            name = "set_timer",
            group = DOMAIN_TIMER,
            displayNameRes = R.string.ai_tool_set_timer_name,
            descriptionRes = R.string.ai_tool_set_timer_desc,
        ),
        ToolMeta(
            name = "play_song",
            group = DOMAIN_MEDIA,
            displayNameRes = R.string.ai_tool_play_song_name,
            descriptionRes = R.string.ai_tool_play_song_desc,
        ),
        ToolMeta(
            name = "stop_music",
            group = DOMAIN_MEDIA,
            displayNameRes = R.string.ai_tool_stop_music_name,
            descriptionRes = R.string.ai_tool_stop_music_desc,
        ),
        ToolMeta(
            name = "show_lyrics",
            group = DOMAIN_MEDIA,
            displayNameRes = R.string.ai_tool_show_lyrics_name,
            descriptionRes = R.string.ai_tool_show_lyrics_desc,
        ),
        ToolMeta(
            name = "search_web",
            group = DOMAIN_WEB,
            displayNameRes = R.string.ai_tool_search_web_name,
            descriptionRes = R.string.ai_tool_search_web_desc,
        ),
        ToolMeta(
            name = "fetch_webpage",
            group = DOMAIN_WEB,
            displayNameRes = R.string.ai_tool_fetch_webpage_name,
            descriptionRes = R.string.ai_tool_fetch_webpage_desc,
        ),
        ToolMeta(
            name = "save_summary_txt",
            group = DOMAIN_FILES,
            displayNameRes = R.string.ai_tool_save_summary_txt_name,
            descriptionRes = R.string.ai_tool_save_summary_txt_desc,
        ),
        ToolMeta(
            name = TOOL_CODE_FILE,
            group = DOMAIN_FILES,
            displayNameRes = R.string.ai_tool_save_code_file_name,
            descriptionRes = R.string.ai_tool_save_code_file_desc,
        ),
        ToolMeta(
            name = TOOL_READ_CODE_FILE,
            group = DOMAIN_FILES,
            displayNameRes = R.string.ai_tool_read_code_file_name,
            descriptionRes = R.string.ai_tool_read_code_file_desc,
        ),
        ToolMeta(
            name = "open_aiui_app",
            group = DOMAIN_AIUI,
            displayNameRes = R.string.ai_tool_open_aiui_app_name,
            descriptionRes = R.string.ai_tool_open_aiui_app_desc,
        ),
        ToolMeta(
            name = "install_aiui_project",
            group = DOMAIN_AIUI,
            displayNameRes = R.string.ai_tool_install_aiui_project_name,
            descriptionRes = R.string.ai_tool_install_aiui_project_desc,
        ),
        ToolMeta(
            name = "stop_aiui_app",
            group = DOMAIN_AIUI,
            displayNameRes = R.string.ai_tool_stop_aiui_app_name,
            descriptionRes = R.string.ai_tool_stop_aiui_app_desc,
        ),
        ToolMeta(
            name = "list_my_aiui_apps",
            group = DOMAIN_AIUI,
            displayNameRes = R.string.ai_tool_list_my_aiui_apps_name,
            descriptionRes = R.string.ai_tool_list_my_aiui_apps_desc,
        ),
    )

    /** 已知 AIUI agent（眼镜端 PACKAGE_INDEX 已安装的 .aix 智能体应用），供 open_aiui_app 匹配 */
    data class AiuiAgentDef(
        val name: String,
        val agentId: String,
        val nativeVersion: String = "0.0.74",
        val pageName: String = "pages/index/index",
        /**
         * 该 agent 的 .aix 已直传眼镜 cxr 目录（device-protected
         * files/aiui/package/cxr/<agentId>.aix）。为 true 时打开走
         * Sys_AIUI_Start 直启（不依赖 AgentStore），为 false 时走旧的
         * Ai_RenderPayload（需眼镜 AgentStore 已安装该包）。
         */
        val aixOnGlasses: Boolean = false,
    )

    /** 最近一次成功下发打开（Sys_AIUI_Start）的 agentId，供 stop_aiui_app 不带名称时作占位/日志 */
    @Volatile
    private var lastStartedAiuiAgentId: String? = null

    /** 当前可打开的 AIUI agent 列表（统一由 [AiuiAppRegistry] 持久化事实源派生，含内置兜底/迁移记录） */
    fun aiuiAgents(context: Context): List<AiuiAgentDef> =
        AiuiAppRegistry.list(context).map { it.toAgentDef() }

    /** 运行时注册一个 agent（委托 [AiuiAppRegistry] 持久化）。生成安装/上传成功后调用 */
    fun registerAiuiAgent(context: Context, agent: AiuiAgentDef) {
        AiuiAppRegistry.upsert(
            context,
            AiuiAppRegistry.AiuiAppRecord(
                appName = agent.name,
                agentId = agent.agentId,
                pageName = agent.pageName,
                nativeVersion = agent.nativeVersion,
                origin = AiuiAppRegistry.ORIGIN_LEGACY,
                aixOnGlasses = agent.aixOnGlasses,
            ),
        )
    }

    /** AiuiAppRecord → open_aiui_app 使用的 AgentDef */
    private fun AiuiAppRegistry.AiuiAppRecord.toAgentDef() = AiuiAgentDef(
        name = appName,
        agentId = agentId,
        nativeVersion = nativeVersion,
        pageName = pageName,
        aixOnGlasses = aixOnGlasses,
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

    /** 工具声明列表（仅已开启的工具），直接传给 OpenAI 兼容协议的 tools 参数（默认全量域） */
    fun schemas(context: Context): List<JSONObject> = schemasFor(context, DOMAIN_ALL)

    /**
     * 按会话场景装配工具声明：仅包含「会话启用域 ∩ 已开启开关」的工具。
     * 主 Agent 用 [SESSION_AGENT_DOMAINS]（全量），本地轻量用 [SESSION_LOCAL_DOMAINS]（空集）。
     * 后续新增会话/场景（如商店助手、答题、生图）时在此声明自己的域子集，无需改执行逻辑。
     */
    fun schemasFor(context: Context, domains: Set<String>): List<JSONObject> {
        return toolList
            .filter { it.group in domains && isEnabled(context, it.name) }
            .map { buildSchema(it) }
    }

    /** 工具执行中的人性化进度文案（眼镜端显示 + 手机端状态栏共用） */
    fun statusText(name: String): String = when (name) {
        "search_knowledge_base" -> "正在检索知识库…"
        "get_current_time" -> "正在查看时间…"
        "get_glasses_battery" -> "正在查询眼镜电量…"
        "get_glasses_device_info" -> "正在查询设备信息…"
        "get_glasses_storage" -> "正在查询存储空间…"
        "list_glasses_apps" -> "正在查询应用列表…"
        "launch_glasses_app" -> "正在打开应用…"
        "set_timer" -> "正在设置定时任务…"
        "play_song" -> "正在搜索歌曲…"
        "stop_music" -> "正在停止播放…"
        "show_lyrics" -> "正在打开歌词…"
        "search_web" -> "正在搜索网页…"
        "fetch_webpage" -> "正在读取网页内容…"
        "save_summary_txt" -> "正在生成并保存总结…"
        TOOL_CODE_FILE -> "正在生成代码文件…"
        TOOL_READ_CODE_FILE -> "正在读取项目源码…"
        "open_aiui_app" -> "正在打开智能体应用…"
        "install_aiui_project" -> "正在打包并安装 AIUI 项目…"
        "stop_aiui_app" -> "正在关闭智能体应用…"
        "list_my_aiui_apps" -> "正在查看我的 AI 应用…"
        else -> "正在执行 $name…"
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

            "play_song" -> toolSchema(
                name = meta.name,
                description = "播放用户点名的歌曲。当用户说“播放某某歌”“来一首某歌”“放首某某的歌”时调用，联网搜索并直接播放该歌曲。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "songName" to mapOf("type" to "string", "description" to "歌曲名称，如“晴天”“海阔天空”"),
                        "artist" to mapOf("type" to "string", "description" to "歌手名（可选），用于更精确地找到歌曲，如“周杰伦”"),
                    ),
                    "required" to listOf("songName"),
                ),
            )

            "stop_music" -> toolSchema(
                name = meta.name,
                description = "停止当前正在播放的音乐。当用户说“停止播放”“别放了”“停一下”“不听了”时调用。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf<String, Any>(),
                ),
            )

            "show_lyrics" -> toolSchema(
                name = meta.name,
                description = "在 Rokid 眼镜上显示当前播放歌曲的歌词（逐行实时显示）。当用户说“显示歌词”“打开歌词”“看歌词”时调用，仅在音乐正在播放时有效。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf<String, Any>(),
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

            "search_web" -> toolSchema(
                name = meta.name,
                description = "联网搜索网页信息并返回候选列表（标题+链接+摘要）。当用户要求搜索某主题的最新内容、资讯、攻略、评测等，或要求“搜一下/查一下/搜索”时先调用本工具（例如“搜一下 Rokid 眼镜的评测”）。拿到结果后，若需要深入了解详情，再对选中的链接调用 fetch_webpage 读取正文。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "query" to mapOf("type" to "string", "description" to "搜索关键词，用能代表主题的 2~6 个词，如“Rokid 眼镜 评测”"),
                    ),
                    "required" to listOf("query"),
                ),
            )

            "fetch_webpage" -> toolSchema(
                name = meta.name,
                description = "读取一个具体网页链接的正文内容并转为纯文本（含网页标题）。配合 search_web 使用：当用户想了解某网页的详细内容、或需要总结某个搜索结果页面时，传入该网页完整链接调用本工具。返回的正文供你阅读后进行总结。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "url" to mapOf("type" to "string", "description" to "要读取的网页完整链接，须以 http:// 或 https:// 开头，如 https://example.com/article"),
                    ),
                    "required" to listOf("url"),
                ),
            )

            "save_summary_txt" -> toolSchema(
                name = meta.name,
                description = "将总结好的内容保存为 txt 文件：写入手机系统下载目录，并同步导入本地知识库（之后可用 search_knowledge_base 检索）。当用户明确要求“把总结存成 txt/文件”“保存总结”“整理成文档/笔记”时，在完成内容总结后调用。内容应完整、条理清晰，尽量包含核心要点。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "title" to mapOf("type" to "string", "description" to "文档标题（会作为文件名与知识库条目名），简洁概括主题，如“Rokid 眼镜评测总结”"),
                        "content" to mapOf("type" to "string", "description" to "要保存的完整总结正文，包含标题下的具体要点内容，供落盘与知识库检索"),
                    ),
                    "required" to listOf("title", "content"),
                ),
            )

            TOOL_CODE_FILE -> toolSchema(
                name = meta.name,
                description = "把生成的一段代码保存为项目文件，写入手机“下载/项目名/”目录。当用户要求“写代码/生成页面或应用/创建项目文件”等需要产出代码、脚本、配置或页面文件时使用；一次调用只写一个文件，一个项目有多个文件时按文件逐个调用（每次用相同的项目名）。必须通过本工具把代码落盘，不要把整段代码直接当作回复内容发给用户；回复只简短告知项目名与文件数量。若目标是“AIUI 智能体应用（.aix）”，至少需要 app.json（页面清单）与 pages/index/index.ink（首页），可再加 VERSION/AGENTS.md/app.js 与更多 pages/*/index.ink；app.json 的页面路径与文件名一致。每个 .ink 页面必须遵循 AIUI SFC 四块结构并按顺序书写：<script def>（页面级 JSON 配置，如导航栏标题）→ <script setup>（export default 逻辑/data/生命周期）→ <page>（WXML 模板，根标签必须是 <page>，禁止用 <template>）→ <style>（样式）。写成 Vue 风格（<template>/<script>）会被拒绝保存，眼镜上也无法渲染。若目标是修改一个已生成的项目，请先调用 read_code_file 读取该文件当前的真实内容，在它基础上改动后覆盖写回同一路径，不要凭印象整文件重编。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "project" to mapOf("type" to "string", "description" to "项目文件夹名（下载目录下的一级文件夹名，同一项目的多个文件必须相同），如 aiui-demo"),
                        "file" to mapOf("type" to "string", "description" to "该文件的相对路径（含文件名，可带子目录），如 app.json、pages/index/index.ink"),
                        "content" to mapOf("type" to "string", "description" to "该文件的完整代码内容"),
                    ),
                    "required" to listOf("project", "file", "content"),
                ),
            )

            TOOL_READ_CODE_FILE -> toolSchema(
                name = meta.name,
                description = "读取对话中通过写文件工具生成的项目源码当前内容（读取手机本地的项目镜像，不改动任何文件）。当用户说“修改/改一下/调整/优化/重做/对之前的 XXX 不满意”而对象是之前生成过的代码项目或 AIUI 智能体应用时必须先调用本工具：不确定要改哪个文件时可只传 project（返回该项目的文件清单），确定后带 file 读取对应文件的完整内容，再基于读到的真实源码用“保存代码文件”工具(save_code_file)覆盖写回同一 project 的同一路径，最后用“安装 AIUI 项目”工具(install_aiui_project)重新安装到眼镜。禁止凭印象或记忆整文件重编——必须先读现网源码再动手，只重写受影响的文件。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "project" to mapOf("type" to "string", "description" to "项目文件夹名（与写文件时的 project 参数完全一致），如 aiui-demo"),
                        "file" to mapOf("type" to "string", "description" to "可选：要读取文件的相对路径（含文件名，可带子目录），如 app.json、pages/index/index.ink；不传则返回整个项目的文件清单"),
                    ),
                    "required" to listOf("project"),
                ),
            )

            "open_aiui_app" -> toolSchema(
                name = meta.name,
                description = "在 Rokid 眼镜上打开一个 AIUI 智能体应用（.aix 卡片应用，如“我是黑客”）。当用户说“打开我是黑客”“打开智能体”“打开某 AI 应用/小游戏”“演示/预览我做的应用”等、且该名字命中智能体应用列表时调用；普通应用（小智/浏览器等）请用 launch_glasses_app。本机生成/上传的应用（本地有 .aix）会自动推送到 RokidLink 自托管宿主（官方 ink web 宿主），支持用 Lab 手机蓝牙手柄直接操控页面；内置官方智能体走 AgentStore 打开。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "appName" to mapOf("type" to "string", "description" to "用户想要打开的智能体应用名称，原样转述，如“我是黑客”"),
                    ),
                    "required" to listOf("appName"),
                ),
            )

            "install_aiui_project" -> toolSchema(
                name = meta.name,
                description = "把对话中已通过写文件工具生成的 AIUI 项目打包成 .aix 并推送到 Rokid 眼镜（登记后可语音打开/演示）。当用户说“把这个项目/应用装到眼镜上”“安装我做的 AI 应用”“打包这个 AIUI 项目”时调用；前提是先调用写文件工具生成该项目（须含 app.json 与 pages/index/index.ink）。本工具只负责打包推送，不自动打开（需用户确认后再用 open_aiui_app 演示，避免打断眼镜当前画面）。对已生成的项目做修改后重新安装到眼镜时，同样用本工具（同一 project 覆盖更新，源码有变化眼镜端会自动重新解压加载）。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "project" to mapOf("type" to "string", "description" to "要安装的项目名（与写文件时的 project 参数完全一致），如 aiui-demo"),
                        "appName" to mapOf("type" to "string", "description" to "安装后在眼镜上显示的应用名（用户之后会说“打开这个名字”），简洁可念，如“记单词助手”"),
                    ),
                    "required" to listOf("project", "appName"),
                ),
            )

            "stop_aiui_app" -> toolSchema(
                name = meta.name,
                description = "关闭 Rokid 眼镜上正在显示的 AIUI 智能体应用（.aix 卡片界面），回到主界面。当用户说“退出/关闭这个 AI 应用”“退出 AIUI”“关掉刚打开的那个应用”“返回”等、画面是 AIUI 智能体卡片时调用；普通应用（小智/浏览器等）请用其他关闭手段而非本工具。无需知道应用名也能关闭当前正在显示的那个。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "appName" to mapOf("type" to "string", "description" to "要关闭的智能体应用名称（可选）。若用户没提名字，说明是刚打开/生成的那个，可不传本参数"),
                    ),
                ),
            )

            "list_my_aiui_apps" -> toolSchema(
                name = meta.name,
                description = "列出用户在本机生成/上传过的 AIUI 智能体应用记录（名称、项目名、最近更新时间、是否已送到眼镜、来源）。当用户问“我生成过哪些 AI 应用/智能体”“我之前做的那个 AIUI”“我有哪些 AI 应用”，或需要再次修改/打开/安装历史 AIUI 项目时先调用本工具拿到项目名（project）；随后要修改代码时先调 read_code_file 读取现网源码，再用 save_code_file 覆盖写回、install_aiui_project 重装。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "keyword" to mapOf("type" to "string", "description" to "可选：按应用名/项目名过滤的关键字，不传则列出全部"),
                    ),
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

            "get_glasses_battery" -> synchronized(adbLock) {
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
                    // 常驻复用共享 ADB 连接（CxrLHiRokidSession 缓存）：不再用后即断，
                    // 避免 AI 工具循环每次执行都重建 TCP+RFCOMM+ADB 鉴权造成隧道风暴。
                    // 连接失败/断线时 getAdbShellClient 检测 isConnected=false 会自动重建。
                }
            }

            "get_glasses_device_info" -> synchronized(adbLock) {
                val client = adbClient(context) ?: return "眼镜 ADB 连接失败，无法查询设备信息"
                try {
                    val raw = client.getDeviceInfo()
                    // 脱敏：ro.serialno 值替换为 [已隐藏]，避免设备序列号泄露给 AI/日志
                    raw.replace(Regex("(ro\\.serialno): .*")) { "${it.groupValues[1]}: [已隐藏]" }
                } finally {
                    // 常驻复用共享 ADB 连接，不用后即断（原因见 get_glasses_battery）
                }
            }

            "get_glasses_storage" -> synchronized(adbLock) {
                val client = adbClient(context) ?: return "眼镜 ADB 连接失败，无法查询存储"
                try {
                    client.executeShellCommand("df -h /sdcard /data 2>/dev/null")
                } finally {
                    // 常驻复用共享 ADB 连接，不用后即断（原因见 get_glasses_battery）
                }
            }

            "list_glasses_apps" -> synchronized(adbLock) {
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
                    // 常驻复用共享 ADB 连接，不用后即断（原因见 get_glasses_battery）
                }
            }

            "launch_glasses_app" -> synchronized(adbLock) {
                val appName = args.optString("appName").trim()
                if (appName.isEmpty()) return "请提供要打开的应用名称"
                val client = adbClient(context) ?: return "眼镜 ADB 连接失败，无法打开应用"
                try {
                    val installed = client.listPackages(false)
                    val pkg = matchPackage(appName, installed, context)
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
                if (action == "launch") synchronized(adbLock) {
                    val client = adbClient(context)
                        ?: return "眼镜 ADB 连接失败，无法创建打开应用的定时任务"
                    try {
                        val pkg = matchPackage(appName, client.listPackages(false), context)
                            ?: return "没有找到应用“$appName”，无法创建定时打开任务"
                        actions += TimerAction.LaunchApp(pkg)
                    } finally {
                        // 常驻复用共享 ADB 连接，不用后即断（原因见 get_glasses_battery）
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

            "play_song" -> {
                val songName = args.optString("songName").trim()
                if (songName.isEmpty()) return "请告诉我要播放哪首歌"
                val artist = args.optString("artist").trim()
                val song = KuwoMusicApi.search(songName, artist.ifBlank { null })
                    ?: return "没有找到歌曲《$songName》${
                        if (artist.isNotBlank()) "（歌手：$artist）" else ""
                    }，请换个歌名试试"
                MusicPlayerController.play(context, song.playUrl, song.name, song.artist, song.lyrics)
                val artistPart = if (song.artist.isNotBlank()) " - ${song.artist}" else ""
                "已开始播放《${song.name}》$artistPart"
            }

            "stop_music" -> {
                MusicPlayerController.stop()
                "已停止播放音乐"
            }

            "show_lyrics" -> {
                if (!MusicPlayerController.isPlaying()) {
                    return "当前没有正在播放的音乐，请先说“播放某某歌”，再让我显示歌词"
                }
                val title = MusicPlayerController.currentTitle
                val lyrics = MusicPlayerController.currentLyrics
                if (lyrics.isEmpty()) {
                    return "没有获取到《$title》的歌词，请换个歌曲试试"
                }
                if (MusicPlayerController.startLyrics(context, lyrics)) {
                    "正在为你显示《$title》的歌词"
                } else {
                    "歌词显示开启失败，请稍后重试"
                }
            }

            "search_web" -> WebTools.search(args.optString("query"))

            "fetch_webpage" -> WebTools.fetchPage(args.optString("url"))

            "save_summary_txt" -> WebTools.saveSummary(
                context,
                title = args.optString("title"),
                content = args.optString("content"),
            )

            TOOL_CODE_FILE -> WebTools.writeProjectFile(
                context,
                project = args.optString("project"),
                filePath = args.optString("file"),
                content = args.optString("content"),
            )

            TOOL_READ_CODE_FILE -> WebTools.readProjectFile(
                context,
                project = args.optString("project"),
                filePath = args.optString("file"),
            )

            "open_aiui_app" -> {
                val appName = args.optString("appName").trim()
                if (appName.isEmpty()) return "请告诉我要打开哪个智能体应用"
                val agent = matchAiuiAgent(context, appName)
                    ?: return "没有找到智能体应用“$appName”。当前可打开：${aiuiAgents(context).joinToString("、") { it.name }}"
                val app = context.applicationContext as? LabApplication ?: return "应用上下文异常"
                // 本机有 .aix 包（对话生成 / 本地上传）→ 优先走自托管宿主演示链路：
                // 推送 .aix 到 RokidLink 的 AiuiLinkActivity（官方 ink web 宿主），
                // 眼镜系统按键 / Lab 蓝牙手柄（HID→KeyEvent）可直接操控页面（伪交互已内置）。
                val localAix = AiuiProject.packageFile(context, agent.agentId)
                if (localAix.isFile) {
                    lastStartedAiuiAgentId = agent.agentId
                    val ack = app.cxrL.pushAixToRokidLinkHost(localAix)
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
                    lastStartedAiuiAgentId = agent.agentId
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
                val agent = if (appName.isEmpty()) null else matchAiuiAgent(context, appName)
                // 眼镜端 stopAiui() 忽略包名直接 AiuiActivity.finishIfRunning()（逆向 AssistServer 确认），
                // 故即使 App 重启、内存无最近记录、或眼镜上跑的是别的 AIUI，也下发占位包名关闭“当前正在显示”的那个，
                // 不再因“不知道是哪个”而放弃（修复“只能退出启动过的”）。
                val targetId = agent?.agentId ?: lastStartedAiuiAgentId ?: "current"
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

    /** 智能体应用名匹配：名称精确 → agentId 精确 → 名称包含（双向） */
    private fun matchAiuiAgent(context: Context, appName: String): AiuiAgentDef? {
        val q = appName.trim()
        val qLower = q.lowercase()
        if (q.isEmpty()) return null
        val agents = aiuiAgents(context)
        agents.firstOrNull { it.name == q }?.let { return it }
        agents.firstOrNull { it.agentId.lowercase() == qLower }?.let { return it }
        agents.firstOrNull { it.name.contains(q) || q.contains(it.name) }?.let { return it }
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

/** 工具输出回填截断上限（防超长 dumpsys 等撑爆模型上下文 / 浪费 token） */
private const val TOOL_OUTPUT_MAX_CHARS = 4000
/** 截断后保留的开头预览字符数 */
private const val TOOL_OUTPUT_PREVIEW_CHARS = 1500

/**
 * 工具执行结果回填截断（纯函数，可单测）：
 * 超长输出（dumpsys/df/应用列表等）全量回填会浪费 token 且易超模型上下文，
 * 超过上限时保留开头预览 + 明确截断说明（模型通常只用开头结论；必要时如实告知已截断）。
 *
 * @param raw 工具原始返回文本
 * @param maxChars 超长阈值（超过才截断）
 * @param previewChars 截断后保留的开头字符数
 * @return 截断后的回填文本（未超长时原样返回）
 */
internal fun truncateToolOutput(
    raw: String,
    maxChars: Int = TOOL_OUTPUT_MAX_CHARS,
    previewChars: Int = TOOL_OUTPUT_PREVIEW_CHARS,
): String {
    if (raw.length <= maxChars) return raw
    return raw.take(previewChars) +
        "\n…（工具输出过长已截断，仅保留开头 $previewChars 字符，原始 ${raw.length} 字符）"
}

// ===== AIUI/代码生成回合的回复收敛 =====

/** AIUI/代码生成回合允许保留的模型回复最大长度：超过视为把源码/全文当成了回复 */
private const val CODE_GEN_REPLY_MAX_CHARS = 120

/** markdown 围栏代码块（``` 与 ~~~） */
private val FENCED_CODE_BLOCK = Regex("```[^`]*```|~~~[^~]*~~~", RegexOption.DOT_MATCHES_ALL)

/** 形似代码的超长行前缀（用于识别模型把源码直接当成回复文本的情况） */
private val CODE_LIKE_LINE_PREFIX = Regex(
    """^(<[a-zA-Z!/]|</[a-zA-Z]|function\s|const\s|let\s|var\s|import\s|export\s|class\s|def\s|return\s|public\s|private\s|protected\s|interface\s|enum\s|struct\s|package\s|#include|\{|\}|"name"\s*:|"pages"\s*:|"window"\s*:)""",
)

/**
 * 判断文本是否含明显代码内容：含 markdown 围栏，或存在形似代码的超长行。
 */
internal fun looksLikeCodeText(text: String): Boolean {
    if (text.contains("```") || text.contains("~~~")) return true
    return text.lines().any { line ->
        val t = line.trim()
        t.length > 40 && CODE_LIKE_LINE_PREFIX.matches(t)
    }
}

/**
 * AIUI/代码生成回合的最终回复收敛（纯函数，可单测）：
 * 此类任务只允许向用户（眼镜显示 + 语音播报）输出简短结论，严禁把生成的源码原文当回复。
 * - 短小且无代码特征的自然文本：原样保留（模型正常给出的结论句）；
 * - 超长 / 含代码围栏 / 形似代码的回复：剥掉围栏后若剩余仍是短小散文则保留，
 *   否则用调用方提供的权威结论（如“已生成 4 个文件，保存在手机下载目录的 xxx 项目”）兜底。
 */
internal fun finalizeCodeGenReply(raw: String, authoritativeSummary: String?): String {
    val text = raw.trim()
    if (text.isEmpty()) return authoritativeSummary ?: "文件已生成完毕，保存在手机下载目录。"
    if (text.length <= CODE_GEN_REPLY_MAX_CHARS && !looksLikeCodeText(text)) {
        return text
    }
    // 剥掉围栏代码块，把剩余散文压成单段
    val prose = text
        .replace(FENCED_CODE_BLOCK, " ")
        .replace("```", " ")
        .replace("~~~", " ")
        .replace('`', ' ')
        .lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
    return when {
        !authoritativeSummary.isNullOrBlank() -> authoritativeSummary
        prose.length in 4..CODE_GEN_REPLY_MAX_CHARS && !looksLikeCodeText(prose) -> prose
        else -> "文件已生成完毕，保存在手机下载目录。"
    }
}
