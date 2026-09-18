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
import com.rokidlab.phone.ai.tools.buildToolSchema
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
    const val DOMAIN_DISPLAY = "display"    // 屏幕展示（图片等，仅手机端显示）
    const val DOMAIN_WEB = "web"            // 联网搜索/读网页
    const val DOMAIN_FILES = "files"        // 文件产出（总结 txt / 代码落盘）
    const val DOMAIN_AIUI = "aiui"          // AIUI 智能体应用（生成/安装/打开/管理）
    const val DOMAIN_PHONE = "phone"        // 手机端（通讯录/拨号/闹钟/应用/状态/音量/日历）

    /** 全部工具域（主 Agent 默认全量装配，未来可拆出子集） */
    val DOMAIN_ALL: Set<String> = setOf(
        DOMAIN_INFO, DOMAIN_KNOWLEDGE, DOMAIN_GLASSES, DOMAIN_TIMER,
        DOMAIN_MEDIA, DOMAIN_DISPLAY, DOMAIN_WEB, DOMAIN_FILES, DOMAIN_AIUI, DOMAIN_PHONE,
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
    internal val adbLock = Any()

    /**
     * 带副作用的工具（拨号/短信/日历/安装/打开应用/音量/定时等）：失败不重试。
     * 隧道瞬断的典型形态是「命令已送达、回执丢失」，重试此类工具 = 重复拨号/重复建日程等
     * 不可逆副作用。失败如实回报给模型，由 LLM 结合上下文决定是否重来。
     * 只读/查询/文件读写等幂等工具不在此集合，允许瞬时失败重试一次。
     */
    val SIDE_EFFECT_TOOLS: Set<String> = setOf(
        "call_phone", "set_phone_alarm", "manage_calendar",
        "install_aiui_project", "open_aiui_app", "stop_aiui_app",
        "launch_glasses_app", "manage_timer", "schedule_agent_task",
        "set_phone_volume", "open_phone_app", "control_music", "show_lyrics",
    )

    /**
     * 设置页的**用户视角**分类（与内部 [group] 域不是一回事：域是装配用的，分类是给人看的）。
     * 例如 `show_image` 在 display 域、`show_lyrics` 在 media 域，但对用户都属「音乐与图片」。
     */
    enum class ToolCategory(val labelRes: Int) {
        GLASSES(R.string.ai_tool_cat_glasses),
        PHONE(R.string.ai_tool_cat_phone),
        INFO_WEB(R.string.ai_tool_cat_info_web),
        MEDIA(R.string.ai_tool_cat_media),
        TIMER(R.string.ai_tool_cat_timer),
        AIUI(R.string.ai_tool_cat_aiui),
        FILES(R.string.ai_tool_cat_files),
        /** 系统性/内部工具（自检、日志、任务续做记账等），设置页不展示 */
        SYSTEM(R.string.ai_tool_cat_system),
    }

    /** 由内部域推导默认分类；少数需要「按用户意图归类」的工具在条目上显式覆盖 */
    private fun categoryOfGroup(group: String): ToolCategory = when (group) {
        DOMAIN_GLASSES -> ToolCategory.GLASSES
        DOMAIN_PHONE -> ToolCategory.PHONE
        DOMAIN_TIMER -> ToolCategory.TIMER
        DOMAIN_MEDIA, DOMAIN_DISPLAY -> ToolCategory.MEDIA
        DOMAIN_AIUI -> ToolCategory.AIUI
        DOMAIN_FILES -> ToolCategory.FILES
        // info / web / knowledge 对用户都是「查信息」
        else -> ToolCategory.INFO_WEB
    }

    /** 工具元数据（设置页展示用，名称/描述走多语言资源；group 用于按会话装配） */
    data class ToolMeta(
        val name: String,
        val group: String,
        val displayNameRes: Int,
        val descriptionRes: Int,
        /** 设置页分类（用户视角）；默认由 [group] 推导 */
        val category: ToolCategory = categoryOfGroup(group),
        /**
         * 系统性/内部工具：设置页**不展示**，但仍会正常下发给模型、也仍可被调用。
         *
         * 为什么只隐藏 UI 而不摘掉工具：这些能力（自检 / 读日志 / 放弃任务记录 / 给 AIUI 页面取封面）
         * 是模型自己判断该用时才用的**内部机制**，不是让用户逐项开关的「能力」——
         * 摆进设置页只会让用户困惑「这个关了会怎样」，且它默认开启、几乎没人会去关。
         */
        val hidden: Boolean = false,
    )

    /** 设置页要展示的工具（按分类分组前先过滤掉系统性工具） */
    val visibleTools: List<ToolMeta> get() = toolList.filterNot { it.hidden }

    /**
     * **必须有眼镜端在线才能真正完成**的工具（乐奇聊天「本机模式」的排除名单）。
     *
     * 为什么单独列一张表而不是直接按域切：需要眼镜的工具**跨了 3 个域** ——
     * `glasses` 域 5 个（全部要）、`aiui` 域 4 个（生成/装/开/管 AIUI 应用都要落到眼镜上）、
     * 以及 `media` 域的 `show_lyrics`（要把歌词推到眼镜并拉起眼镜系统音乐页）。
     * 按域切要么漏（media 只该摘 1 个，不能整域摘掉 play_song/stop_music）要么多。
     * 与 [SIDE_EFFECT_TOOLS] / [ToolRiskMap] 同一模式：**显式登记、单一出处、可 grep**。
     *
     * ⚠️ 判定口径是「**没有眼镜就做不成**」，不是「和眼镜有关」。以下**故意不在名单里**，
     * 因为它们在手机侧独立完成、且眼镜不可用时已优雅降级（摘掉反而白白损失能力）：
     *  - `show_image`：先把图片放进手机对话气泡，再**尽力**镜像到眼镜悬浮层，
     *    眼镜不在时如实回「已在手机端显示图片；眼镜端未连接…」（见 DisplayToolProvider）。
     *  - `play_song` / `stop_music`：本地 `MusicPlayerController` 播放，不依赖眼镜。
     *  - `get_now_playing` / `get_cover_image`：读手机侧 MediaSession、在手机侧下载封面。
     *  - `save_code_file` / `read_code_file`：读写手机本地项目文件，离线可写代码。
     */
    val GLASSES_REQUIRED_TOOLS: Set<String> = setOf(
        // ── glasses 域：ADB 查询/控制眼镜硬件 ──
        "get_glasses_status",
        "list_glasses_apps",
        "launch_glasses_app",
        // ── aiui 域：生成/安装/打开/管理 AIUI 应用，最终都要落到眼镜渲染 ──
        "open_aiui_app",
        "install_aiui_project",
        "stop_aiui_app",
        "list_my_aiui_apps",
        // ── media 域：只有歌词这一项要把内容推到眼镜并拉起眼镜系统音乐页 ──
        "show_lyrics",
    )

    /** 取 Schema 里的工具名（`{"function":{"name":…}}`）。解析失败返回空串（调用方按「不在名单」处理）。 */
    private fun functionNameOf(schema: JSONObject): String =
        runCatching { schema.optJSONObject("function")?.optString("name").orEmpty() }.getOrDefault("")

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
            name = "get_glasses_status",
            group = DOMAIN_GLASSES,
            displayNameRes = R.string.ai_tool_get_glasses_status_name,
            descriptionRes = R.string.ai_tool_get_glasses_status_desc,
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
            name = "manage_timer",
            group = DOMAIN_TIMER,
            displayNameRes = R.string.ai_tool_manage_timer_name,
            descriptionRes = R.string.ai_tool_manage_timer_desc,
        ),
        // 自主定时任务（真主动性）：到点让 Agent 自己跑一轮推理（仅只读工具）再播报结果，
        // 区别于 set_timer 的「到点念一句固定文案」
        ToolMeta(
            name = "schedule_agent_task",
            group = DOMAIN_TIMER,
            displayNameRes = R.string.ai_tool_schedule_agent_task_name,
            descriptionRes = R.string.ai_tool_schedule_agent_task_desc,
        ),
        ToolMeta(
            name = "control_music",
            group = DOMAIN_MEDIA,
            displayNameRes = R.string.ai_tool_control_music_name,
            descriptionRes = R.string.ai_tool_control_music_desc,
        ),
        ToolMeta(
            name = "show_lyrics",
            group = DOMAIN_MEDIA,
            displayNameRes = R.string.ai_tool_show_lyrics_name,
            descriptionRes = R.string.ai_tool_show_lyrics_desc,
        ),
        // AIUI 页面要渲染「歌名/封面/逐行歌词」时的唯一取数口：上面三个工具只回纯文本摘要，
        // 页面拿不到任何素材（详见 MediaToolProvider.currentSongJson 的说明）。
        ToolMeta(
            name = "get_now_playing",
            group = DOMAIN_MEDIA,
            displayNameRes = R.string.ai_tool_get_now_playing_name,
            descriptionRes = R.string.ai_tool_get_now_playing_desc,
        ),
        ToolMeta(
            name = "get_cover_image",
            group = DOMAIN_MEDIA,
            displayNameRes = R.string.ai_tool_get_cover_image_name,
            descriptionRes = R.string.ai_tool_get_cover_image_desc,
            hidden = true,   // 系统性/内部工具：设置页不展示（仍会下发给模型）
        ),
        ToolMeta(
            name = "show_image",
            group = DOMAIN_DISPLAY,
            displayNameRes = R.string.ai_tool_show_image_name,
            descriptionRes = R.string.ai_tool_show_image_desc,
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
        ToolMeta(
            name = "get_weather",
            group = DOMAIN_WEB,
            displayNameRes = R.string.ai_tool_get_weather_name,
            descriptionRes = R.string.ai_tool_get_weather_desc,
        ),
        ToolMeta(
            name = "calculate",
            group = DOMAIN_INFO,
            displayNameRes = R.string.ai_tool_calculate_name,
            descriptionRes = R.string.ai_tool_calculate_desc,
        ),
        ToolMeta(
            name = "search_contacts",
            group = DOMAIN_PHONE,
            displayNameRes = R.string.ai_tool_search_contacts_name,
            descriptionRes = R.string.ai_tool_search_contacts_desc,
        ),
        ToolMeta(
            name = "call_phone",
            group = DOMAIN_PHONE,
            displayNameRes = R.string.ai_tool_call_phone_name,
            descriptionRes = R.string.ai_tool_call_phone_desc,
        ),
        ToolMeta(
            name = "set_phone_alarm",
            group = DOMAIN_PHONE,
            displayNameRes = R.string.ai_tool_set_phone_alarm_name,
            descriptionRes = R.string.ai_tool_set_phone_alarm_desc,
        ),
        ToolMeta(
            name = "open_phone_app",
            group = DOMAIN_PHONE,
            displayNameRes = R.string.ai_tool_open_phone_app_name,
            descriptionRes = R.string.ai_tool_open_phone_app_desc,
        ),
        ToolMeta(
            name = "get_phone_status",
            group = DOMAIN_PHONE,
            displayNameRes = R.string.ai_tool_get_phone_status_name,
            descriptionRes = R.string.ai_tool_get_phone_status_desc,
        ),
        ToolMeta(
            name = "get_location",
            group = DOMAIN_PHONE,
            displayNameRes = R.string.ai_tool_get_location_name,
            descriptionRes = R.string.ai_tool_get_location_desc,
        ),
        ToolMeta(
            name = "set_phone_volume",
            group = DOMAIN_PHONE,
            displayNameRes = R.string.ai_tool_set_phone_volume_name,
            descriptionRes = R.string.ai_tool_set_phone_volume_desc,
        ),
        ToolMeta(
            name = "manage_calendar",
            group = DOMAIN_PHONE,
            displayNameRes = R.string.ai_tool_manage_calendar_name,
            descriptionRes = R.string.ai_tool_manage_calendar_desc,
        ),
        // 自我认知域：Agent 对「自己」的运行时事实（模型/连接/资料/开关）与运行日志。
        // 归属 info（基础信息）域，随主 Agent 与会话子集一起装配 —— 自检能力应始终可用。
        ToolMeta(
            name = "get_agent_status",
            group = DOMAIN_INFO,
            displayNameRes = R.string.ai_tool_get_agent_status_name,
            descriptionRes = R.string.ai_tool_get_agent_status_desc,
            hidden = true,   // 系统性/内部工具：设置页不展示（仍会下发给模型）
        ),
        ToolMeta(
            name = "read_recent_logs",
            group = DOMAIN_INFO,
            displayNameRes = R.string.ai_tool_read_recent_logs_name,
            descriptionRes = R.string.ai_tool_read_recent_logs_desc,
            hidden = true,   // 系统性/内部工具：设置页不展示（仍会下发给模型）
        ),
        ToolMeta(
            name = "clear_agent_task",
            group = DOMAIN_INFO,
            displayNameRes = R.string.ai_tool_clear_agent_task_name,
            descriptionRes = R.string.ai_tool_clear_agent_task_desc,
            hidden = true,   // 系统性/内部工具：设置页不展示（仍会下发给模型）
        ),
        // 跨会话检索：在落盘聊天历史里按关键字找相关轮次，让模型能回答
        // 「我们上次聊的那个定时任务叫什么」。归 info 域，随各会话子集一起装配。
        ToolMeta(
            name = "search_past_conversations",
            group = DOMAIN_INFO,
            displayNameRes = R.string.ai_tool_search_past_conversations_name,
            descriptionRes = R.string.ai_tool_search_past_conversations_desc,
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
    internal var lastStartedAiuiAgentId: String? = null

    /** 当前可打开的 AIUI agent 列表（统一由 [AiuiAppRegistry] 持久化事实源派生，含迁移记录） */
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
        // 开关变更后旧 Schema 缓存失效（Domains 键 + 全量开关快照双重校验，见 schemasFor）
        schemaCache.clear()
    }

    /** 工具声明列表（仅已开启的工具），直接传给 OpenAI 兼容协议的 tools 参数（默认全量域） */
    fun schemas(context: Context): List<JSONObject> = schemasFor(context, DOMAIN_ALL)

    /**
     * 按会话场景装配工具声明：仅包含「会话启用域 ∩ 已开启开关」的工具。
     * 主 Agent 用 [SESSION_AGENT_DOMAINS]（全量），本地轻量用 [SESSION_LOCAL_DOMAINS]（空集）。
     * 后续新增会话/场景（如商店助手、答题、生图）时在此声明自己的域子集，无需改执行逻辑。
     */
    /**
     * schemasFor 结果缓存：key = domains 集合，value = (全量工具开关快照, Schema 列表)。
     * 每次调用读一遍开关快照（SharedPreferences 首载后为内存读，开销极小）做双重校验，
     * 命中即跳过 ~30 个工具的 JSONObject 重建；[setEnabled] 写入后清空。
     * 调用方仅对返回的 List 做 add/remove，不修改 JSONObject 本身，共享实例安全。
     */
    private val schemaCache = java.util.concurrent.ConcurrentHashMap<Set<String>, Pair<Map<String, Boolean>, List<JSONObject>>>()

    /**
     * @param excludeGlassesTools true = 摘掉 [GLASSES_REQUIRED_TOOLS]（乐奇聊天「本机模式」：
     *   没眼镜时不要让模型去调注定失败的工具）。过滤发生在缓存**之后**，
     *   因此不改变缓存键、也不重建 JSONObject。
     */
    fun schemasFor(context: Context, domains: Set<String>, excludeGlassesTools: Boolean = false): List<JSONObject> {
        val prefs = context.getSharedPreferences(TOOL_PREFS, Context.MODE_PRIVATE)
        val snapshot = HashMap<String, Boolean>(toolList.size)
        toolList.forEach { snapshot[it.name] = prefs.getBoolean(KEY_PREFIX + it.name, true) }
        val cached = schemaCache[domains]?.let { (cachedSnapshot, cachedSchemas) ->
            if (cachedSnapshot == snapshot) cachedSchemas else null
        }
        val result = cached ?: toolList
            .filter { it.group in domains && snapshot[it.name] == true }
            .map { buildSchema(it) }
            .also { schemaCache[domains] = snapshot to it }
        return if (excludeGlassesTools) {
            result.filterNot { functionNameOf(it) in GLASSES_REQUIRED_TOOLS }
        } else {
            result
        }
    }

    /**
     * 只读工具声明：全域 ∩ 已开启 ∩ 风险档 = [ToolRisk.READ_ONLY]。
     *
     * 供**无人值守**场景装配工具（定时触发的自主任务 `TimerAction.AgentPrompt`）。
     * 为什么不复用「按域装配」：多个域里混杂副作用工具 —— `glasses` 域含 `launch_glasses_app`、
     * `phone` 域含 `call_phone`/`set_phone_alarm`、`timer` 域含 `set_timer`、
     * `media` 域含 `play_song`、`files` 域含 `save_code_file`。**按域切不干净，
     * 只能按风险档切**。无人监管时跑错一次（半夜拨号/下单/装机）代价远高于「少做一点」，
     * 因此这里宁可只给查询能力：查时间/天气/网页/知识库/设备状态/自身状态/历史对话。
     *
     * 副作用档判定完全复用 [ToolRiskMap.riskOf]（唯一登记处），新增工具只要正确登记
     * 就会自动被正确纳入/排除，无需改本函数。
     *
     * @param excludeGlassesTools true = 一并摘掉 [GLASSES_REQUIRED_TOOLS]（本机模式下的自主任务：
     *   无人值守 + 没眼镜，更需要把注定失败的工具排除干净）
     */
    fun schemasReadOnly(context: Context, excludeGlassesTools: Boolean = false): List<JSONObject> {
        val allowed = readOnlyToolNames()
        val schemas = schemasFor(context, DOMAIN_ALL)
            .filter { functionNameOf(it) in allowed }
        return if (excludeGlassesTools) {
            schemas.filterNot { functionNameOf(it) in GLASSES_REQUIRED_TOOLS }
        } else {
            schemas
        }
    }

    /**
     * 只读工具名集合（纯函数，可单测）：全域工具中风险档为 [ToolRisk.READ_ONLY] 的那些。
     *
     * 这是无人值守路径（[schemasReadOnly]）的**唯一准入名单**，也是「自主任务绝不改状态」
     * 这条安全承诺的实际落点。抽成不依赖 Context 的纯函数，是为了让回归测试能直接断言
     * 「拨号/装机/写文件/设定时都不在名单里」—— 新增工具若登记错了风险档，测试立刻失败。
     */
    internal fun readOnlyToolNames(): Set<String> = toolList
        .filter { runCatching { ToolRiskMap.riskOf(it.name) == ToolRisk.READ_ONLY }.getOrDefault(false) }
        .map { it.name }
        .toSet()

    /** 工具执行中的人性化进度文案（眼镜端显示 + 手机端状态栏共用） */
    fun statusText(name: String): String = when (name) {
        "search_knowledge_base" -> "正在检索知识库…"
        "get_current_time" -> "正在查看时间…"
        "get_glasses_status" -> "正在查询眼镜状态…"
        "list_glasses_apps" -> "正在查询应用列表…"
        "launch_glasses_app" -> "正在打开应用…"
        "manage_timer" -> "正在处理定时任务…"
        "get_weather" -> "正在查询天气…"
        "calculate" -> "正在精确计算…"
        "search_contacts" -> "正在查找联系人…"
        "call_phone" -> "正在拨号…"
        "set_phone_alarm" -> "正在设置手机闹钟…"
        "open_phone_app" -> "正在打开手机应用…"
        "get_phone_status" -> "正在查询手机状态…"
        "get_location" -> "正在获取位置…"
        "set_phone_volume" -> "正在调节手机音量…"
        "manage_calendar" -> "正在处理日程…"
        "control_music" -> "正在处理音乐播放…"
        "show_lyrics" -> "正在打开歌词…"
        "get_now_playing" -> "正在读取播放信息…"
        "get_cover_image" -> "正在获取歌曲封面…"
        "show_image" -> "正在显示图片…"
        "search_web" -> "正在搜索网页…"
        "fetch_webpage" -> "正在读取网页内容…"
        "save_summary_txt" -> "正在生成并保存总结…"
        TOOL_CODE_FILE -> "正在生成代码文件…"
        TOOL_READ_CODE_FILE -> "正在读取项目源码…"
        "open_aiui_app" -> "正在打开智能体应用…"
        "install_aiui_project" -> "正在打包并安装 AIUI 项目…"
        "stop_aiui_app" -> "正在关闭智能体应用…"
        "list_my_aiui_apps" -> "正在查看我的 AI 应用…"
        "get_agent_status" -> "正在自检运行状态…"
        "read_recent_logs" -> "正在读取运行日志…"
        "clear_agent_task" -> "正在清除任务记录…"
        "search_past_conversations" -> "正在检索历史对话…"
        "schedule_agent_task" -> "正在创建自主任务…"
        else -> "正在执行 $name…"
    }

    /** 组装单个工具的 JSON Schema（internal：金标评测单测直接校验声明内容） */
    internal fun buildSchema(meta: ToolMeta): JSONObject = this.buildToolSchema(meta)

    /** 域提供者（Phase 4：execute 按域拆分到 tools/ 包，见 ToolProvider） */
    private val providers: List<com.rokidlab.phone.ai.tools.ToolProvider> = listOf(
        com.rokidlab.phone.ai.tools.InfoToolProvider,
        com.rokidlab.phone.ai.tools.KnowledgeToolProvider,
        com.rokidlab.phone.ai.tools.GlassesToolProvider,
        com.rokidlab.phone.ai.tools.TimerToolProvider,
        com.rokidlab.phone.ai.tools.MediaToolProvider,
        com.rokidlab.phone.ai.tools.DisplayToolProvider,
        com.rokidlab.phone.ai.tools.WebToolProvider,
        com.rokidlab.phone.ai.tools.FilesToolProvider,
        com.rokidlab.phone.ai.tools.AiuiToolProvider,
        com.rokidlab.phone.ai.tools.PhoneToolProvider,
        com.rokidlab.phone.ai.tools.StatusToolProvider,
    )

    /**
     * 执行工具，返回给 AI 的结果文本（同步方法）。
     * @throws IllegalArgumentException 未知工具名
     */
    fun execute(context: Context, name: String, arguments: String): String {
        // B3 修复：国产/本地模型对无参工具常返回 arguments: ""，JSONObject("") 直接抛异常
        // 使整个工具轮次失败。空串按 {} 处理；非空白但非法 JSON 也兜底为空对象并告警，
        // 避免个别模型的格式瑕疵拖垮整轮对话。
        val args = if (arguments.isBlank()) {
            JSONObject()
        } else {
            try {
                JSONObject(arguments)
            } catch (e: Exception) {
                Log.w(TAG, "tool $name: invalid arguments JSON, treated as empty: ${e.message}")
                JSONObject()
            }
        }
        val provider = providers.firstOrNull { name in it.toolNames }
            ?: throw IllegalArgumentException("未知工具: $name")
        return provider.execute(context, name, args)
    }

    /**
     * 应用名 → 包名匹配。
     * 匹配优先级：包名精确 → 商店注册名精确 → 名称包含 → 包名子串（英文/拼音）。
     * 商店名称来源：手机端内置 apps.json（BrewIndex.loadBundled）的 name ↔ packageName。
     */
    internal fun matchPackage(appName: String, installed: List<String>, context: Context): String? {
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
    internal fun matchAiuiAgent(context: Context, appName: String): AiuiAgentDef? {
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
    internal fun adbClient(context: Context): AdbShellClient? {
        val app = context.applicationContext as? LabApplication ?: return null
        return app.cxrL.getAdbShellClient()
    }

    /** 眼镜端 RokidLink 主服务（manifest `exported=true`、`foregroundServiceType=connectedDevice`） */
    private const val GLASSES_LINK_PKG = "com.rokidlab.rokidlink"
    private const val GLASSES_LINK_SERVICE = "com.rokidlab.rokidlink.KeyButtonService"

    /**
     * 确保眼镜端 RokidLink 的常驻服务在运行。
     *
     * ★ 为什么必须做：所有 CXR 自定义指令（图片下发 / 拉起页面 / 工具确认）**只有 RokidLink 订阅着才能收到**；
     * 而它没有 `BOOT_COMPLETED`，`adb install -r` 之后包状态是 `stopped=true`，不跑 = 指令全部石沉大海
     * （症状极易被误判成"功能没实现"）。实测：眼镜 `ps -A | grep rokidlink` 为空时，
     * 手机端 `show_image` / `show_lyrics` 的 CXR 路径必然失败。
     *
     * ★ 为什么只拉 Service 不拉 Activity：`KeyButtonService` 是 exported 前台服务，
     * `am start-foreground-service` **不会弹任何界面**，不打扰用户当前画面；服务已在跑时是幂等的。
     *
     * @return true = 命令已成功下发（不代表服务一定起来）
     */
    internal fun ensureGlassesLinkRunning(context: Context): Boolean {
        val client = adbClient(context) ?: run {
            Log.i(TAG, "ensureGlassesLinkRunning: no shared adb session, skip")
            return false
        }
        // start-foreground-service 在个别 ROM/版本上不存在时回退 startservice
        val cmds = listOf(
            "am start-foreground-service -n $GLASSES_LINK_PKG/$GLASSES_LINK_SERVICE",
            "am startservice -n $GLASSES_LINK_PKG/$GLASSES_LINK_SERVICE",
        )
        for (cmd in cmds) {
            val out = runCatching { client.executeShellCommand(cmd, 8_000) }
                .onFailure { Log.w(TAG, "ensure glasses link service failed: ${it.message}") }
                .getOrNull() ?: continue
            val bad = out.contains("Error", ignoreCase = true) ||
                out.contains("Exception", ignoreCase = true) ||
                out.contains("does not exist", ignoreCase = true) ||
                out.contains("Unknown command", ignoreCase = true)
            Log.i(TAG, "ensure glasses link service (bad=$bad): ${out.trim().take(140)}")
            if (!bad) return true
        }
        return false
    }

    /** 组装单个工具的 JSON Schema */
    /**
     * 解析 open_aiui_app 的启动参数。
     *
     * 只接受 JSON 对象字符串；非法输入返回 null（不下发），避免把脏串送进眼镜端页面。
     * 空串视为"不带参数"，与"传了但格式错"区分开——后者记警告便于排查模型行为。
     */
    internal fun parseLaunchParams(raw: String): String? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        return try {
            val obj = JSONObject(s)
            obj.toString()
        } catch (e: Exception) {
            Log.w(TAG, "open_aiui_app: invalid params JSON, ignored: ${e.message}")
            null
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
