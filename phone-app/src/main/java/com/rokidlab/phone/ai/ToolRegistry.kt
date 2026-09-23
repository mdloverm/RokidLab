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
import com.rokidlab.phone.ai.tools.toolSchema
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
    const val DOMAIN_FILES = "files"        // 文件产出与文件工作区（列/读/搜/改/删/移）
    const val DOMAIN_AIUI = "aiui"          // AIUI 智能体应用（生成/安装/打开/管理）
    const val DOMAIN_PHONE = "phone"        // 手机端（通讯录/拨号/闹钟/应用/状态/音量/日历）
    const val DOMAIN_RESEARCH = "research"  // 只读子代理委派（调研）
    /**
     * 视觉域：用眼镜摄像头看真实世界（`look_at_view`）。
     *
     * 单独成域而不是塞进 [DOMAIN_GLASSES]：眼镜域是"查设备/开应用"这类**管理**能力，
     * 而这一条是"替用户看"，用户在设置页想关掉它时的心智是「别让它拍」而不是「别看眼镜状态」。
     * 也正因为独立成域，未来若要接手机相机（另一条取图源）可以落在同一个域里。
     */
    const val DOMAIN_VISION = "vision"
    /**
     * 本机执行域：在手机自己的 Linux 用户态环境（proot + Ubuntu rootfs）里跑命令。
     *
     * 单独成域而不是塞进 [DOMAIN_FILES]：它是**唯一一条"任意命令执行"**能力
     * （文件域只能列/读/搜/改/删/移），用户想关掉它时的心智是「别在我手机上随便执行命令」，
     * 而不是「别动我的文件」。独立成域也让它可以整域从某些场景里摘掉。
     *
     * ⚠️ 本域**不对 AIUI 页面开放**（见 `ai/approval/PageScope.ALLOWED_DOMAINS`）：
     * 页面是第三方制品，把任意命令执行交给页面作者不是产品意图。
     */
    const val DOMAIN_SHELL = "shell"
    /**
     * 外部 MCP 服务器提供的工具（动态：连上 server 才知道有哪些）。
     *
     * ⚠️ 与其它域的本质区别：**这个域的工具集在运行时会变**。因此它虽然列在 [DOMAIN_ALL] 里，
     * 但 `schemasFor` 对它额外做一道「活跃性过滤」—— server 没连上时该域产出**空集**，
     * 否则模型会去调一个不存在的工具（白耗一轮，且报了错还不能重试）。
     */
    const val DOMAIN_MCP = "mcp"

    /**
     * 第三方 MCP 工具的 **wire name 前缀**：`mcp__<serverId>__<工具名>`。
     *
     * 唯一产地 —— 生成侧（`McpRegistry.wireNameOf`）与判定侧（审批闸门的拒绝文案分流、
     * 日志归因）都读这里，不要再各写一份字面量。
     *
     * ★ 为什么判定侧必须用**名字前缀**而不是查 [domainOfOrNull]：MCP 工具是**动态**注册的，
     * `dynamicProviders` 只在 server 连上时才有内容。查表会得到一个"连接断了就查不到"的结论 ——
     * 后果是拒绝文案误落到内置工具分支（用户看不到「把该 server 标为信任」这条出路），
     * 且单测环境（永远没有 server）里这条分流完全测不到。前缀是工具名的固有形状，与连接状态无关。
     */
    const val MCP_TOOL_PREFIX = "mcp__"

    /** 名字是否指向一个第三方 MCP 工具（只看形状，不依赖连接状态；见 [MCP_TOOL_PREFIX]） */
    internal fun isMcpTool(name: String): Boolean = name.startsWith(MCP_TOOL_PREFIX)

    /** 全部工具域（主 Agent 默认全量装配，未来可拆出子集） */
    val DOMAIN_ALL: Set<String> = setOf(
        DOMAIN_INFO, DOMAIN_KNOWLEDGE, DOMAIN_GLASSES, DOMAIN_TIMER,
        DOMAIN_MEDIA, DOMAIN_DISPLAY, DOMAIN_WEB, DOMAIN_FILES, DOMAIN_AIUI, DOMAIN_PHONE,
        DOMAIN_RESEARCH, DOMAIN_VISION, DOMAIN_SHELL, DOMAIN_MCP,
    )

    /** 主 Agent 会话（眼镜语音/手机聊天，在线模型）装配的工具域 */
    val SESSION_AGENT_DOMAINS: Set<String> = DOMAIN_ALL

    /** 本地轻量会话装配的工具域：空集 —— 本地小模型背不动数十个工具 Schema，
     * 每轮全量下发只会拖慢 prefill 且小模型调用工具本就不可靠；
     * 设备/联网等能力由用户切回在线 Agent 时提供。 */
    val SESSION_LOCAL_DOMAINS: Set<String> = emptySet()

    /**
     * AIUI/代码生成会话装配的工具域：AIUI + 文件产出 + 基础信息 + 联网 + 知识库。
     * 模型命中 aiui-dev 技能（或开始写代码）后，主循环把 tools 从全量域切换为本子集，
     * 每轮少发十来个无关工具 Schema，显著降低 input token 与 prefill 耗时；
     * load_skill/load_skill_section 属技能伪工具，切换后由调用方单独保留。
     *
     * 为什么保留 [DOMAIN_WEB] 与 [DOMAIN_KNOWLEDGE]：页面生成常以「素材」开头 ——
     * 「把知识库里《X》做成卡片页」「照这个链接做一个页面」「页面默认显示某城市的天气」。
     * 这两个域都是**只读输入侧**能力（不写文件、不控设备），砍掉它们会让这类请求
     * 在切进子集后的下一轮突然失去素材来源，模型只能凭印象编数据；保留成本只有 4 个 Schema。
     */
    val SESSION_AIUI_DOMAINS: Set<String> =
        setOf(DOMAIN_AIUI, DOMAIN_FILES, DOMAIN_INFO, DOMAIN_WEB, DOMAIN_KNOWLEDGE)

    /** 代码/项目文件落盘工具名（把生成的文件写入手机下载目录的对应项目文件夹） */
    const val TOOL_CODE_FILE = "save_code_file"

    /** 代码/项目文件读取工具名（读取已生成项目的当前源码，供修改/微调时参考） */
    const val TOOL_READ_CODE_FILE = "read_code_file"

    // ═══════════════════ 工具接缝（capability seam）════════════════════════
    // 每个 provider 自声明自己的工具清单（元数据 + schema + 风险档 + 文案），见 ToolEntry。
    // 下面所有"表"（toolList / SIDE_EFFECT_TOOLS / GLASSES_REQUIRED_TOOLS / 风险档 / 确认策略
    // / statusText / 确认摘要）**全部由 tools() 聚合派生**，不再手工同步。
    //   ★ 新增工具 = 在对应 provider 的 tools() 与 execute() 各加一条，不需要改本文件的任何名单。

    /**
     * 域提供者（顺序 = 工具在设置页/下发给模型时的顺序，不要随意重排）。
     *
     * ⚠️ 声明位置必须**早于** [entries]：Kotlin object 的属性初始化按文本顺序执行，
     * 把 providers 放到后面会在 entries 初始化时读到 null。
     * （2026-09-20 改为惰性派生后该约束**依然成立**：首次访问时机推迟了，
     *  但 `providers` 仍是 [allProviders] 的读取目标，位置不要下移。）
     */
    private val providers: List<com.rokidlab.phone.ai.tools.ToolProvider> = listOf(
        com.rokidlab.phone.ai.tools.InfoToolProvider,
        com.rokidlab.phone.ai.tools.KnowledgeToolProvider,
        com.rokidlab.phone.ai.tools.GlassesToolProvider,
        com.rokidlab.phone.ai.tools.TimerToolProvider,
        com.rokidlab.phone.ai.tools.MediaToolProvider,
        com.rokidlab.phone.ai.tools.DisplayToolProvider,
        com.rokidlab.phone.ai.tools.WebToolProvider,
        com.rokidlab.phone.ai.tools.FilesToolProvider,
        com.rokidlab.phone.ai.tools.ShellToolProvider,
        com.rokidlab.phone.ai.tools.ScriptToolProvider,
        com.rokidlab.phone.ai.tools.AiuiToolProvider,
        com.rokidlab.phone.ai.tools.PhoneToolProvider,
        com.rokidlab.phone.ai.tools.StatusToolProvider,
        com.rokidlab.phone.ai.tools.SubagentToolProvider,
        com.rokidlab.phone.ai.tools.VisionToolProvider,
        com.rokidlab.phone.ai.tools.MotionToolProvider,
    )

    // ═══════════════════ 动态提供者（MCP）════════════════════════
    /**
     * **运行时**才知道工具清单的 provider（目前只有 MCP）。
     *
     * 为什么不直接塞进 [providers]：那个列表是编译期常量，而 MCP 的工具要**连上 server**才存在。
     * 由 `ai/mcp/McpRegistry` 在工具集变化时调用 [setDynamicProviders]，使所有派生表失效重算。
     */
    @Volatile
    private var dynamicProviders: List<com.rokidlab.phone.ai.tools.ToolProvider> = emptyList()

    /** 静态 + 动态 provider 的全集（**唯一的工具来源**） */
    private val allProviders: List<com.rokidlab.phone.ai.tools.ToolProvider>
        get() = providers + dynamicProviders

    /**
     * 工具集版本号：任何一次 [refreshTools] 都 +1，用于使 [derivedCache] 与 [schemaCache] 失效。
     *
     * ⚠️ 这是「动态工具集」能正确工作的**唯一支点**。尤其是 [schemaCache]：它原本只以 `domains`
     * 为 key，若不带版本，MCP 工具连上后 schema 会一直命中旧缓存 —— 表现为
     * 「设置页看得见新工具，模型却永远不调」，且**不报错**
     * （与 `list_glasses_apps` 被正则区间静默删掉是同一类事故）。
     */
    @Volatile
    private var tableVersion: Int = 0

    /**
     * 派生视图缓存（版本相等即复用）。
     *
     * 为什么需要缓存而不是每次现算：[entries] / [entryByName] 被设置页、风险闸门、子代理、
     * AIUI 网关在**每轮对话**里反复读取，现算会让 `flatMap { it.tools() }` 每次重组都跑一遍。
     * 静态工具场景下，版本不变 ⇒ 等价于改造前的「只算一次」。
     */
    private class DerivedTables(
        val version: Int,
        val entries: List<com.rokidlab.phone.ai.tools.ToolEntry>,
        val byName: Map<String, com.rokidlab.phone.ai.tools.ToolEntry>,
    )

    @Volatile
    private var derivedCache: DerivedTables? = null

    private fun derived(): DerivedTables =
        derivedCache?.takeIf { it.version == tableVersion } ?: run {
            val list = allProviders.flatMap { it.tools() }
            DerivedTables(tableVersion, list, list.associateBy { it.name })
                .also { derivedCache = it }
        }

    /**
     * 工具集发生变化后调用（MCP server 连上/断开/工具清单刷新）。
     *
     * 调用方**必须**在改完动态 provider 之后再调，否则派生表与 [schemaCache] 都还是旧的。
     * 刻意收成内部方法：只有本包内的注册表该动它。
     */
    internal fun refreshTools() {
        tableVersion++
    }

    /** 注册/替换动态 provider（MCP）。内部已调用 [refreshTools]，调用方不必再调一次。 */
    internal fun setDynamicProviders(list: List<com.rokidlab.phone.ai.tools.ToolProvider>) {
        dynamicProviders = list
        refreshTools()
    }

    /**
     * 全部工具声明（**唯一登记处的聚合结果**）。
     *
     * 这张表就是原来 `toolList` + 5 张手工名单的合并体：任何一处不一致都**不再可能发生**，
     * 因为六张表都由它派生 —— 结构上排除了「漏登记 → 功能静默失效」这类事故
     * （`list_glasses_apps` 曾被正则区间替换静默删掉，编译通过、单测全绿）。
     *
     * 改造（2026-09-20）：由 `val`（object 初始化时算一次）改为**惰性派生**，
     * 以容纳运行时才知道的 MCP 工具。**对外类型不变，调用方零改动**。
     */
    private val entries: List<com.rokidlab.phone.ai.tools.ToolEntry>
        get() = derived().entries

    /** name → 声明（schema / 风险档 / 文案的唯一检索口） */
    private val entryByName: Map<String, com.rokidlab.phone.ai.tools.ToolEntry>
        get() = derived().byName

    /** 供 [ToolRiskMap] 审计用：全部工具名（内部使用，不暴露给业务） */
    internal fun allToolNames(): List<String> = entries.map { it.name }

    /** 供 [ToolRiskMap] 取风险档：结构上必然有值（除非名字不在表里） */
    internal fun riskOfOrNull(name: String): ToolRisk? = entryByName[name]?.risk

    /**
     * 取工具的**确认降级策略**（问不到用户时放行还是拒绝）。
     *
     * 查不到名字（伪工具 / 模型幻觉 / 已下线的工具）一律 [ToolConfirmPolicy.BLOCK] ——
     * 与 [ToolRiskMap.riskOf] 的"未知即最保守"同一条原则。
     * 伪工具不受影响：它们全是只读或本机档，`RiskApprovalGuard` 根本不会为它们产生 Ask。
     */
    internal fun confirmPolicyOf(name: String): ToolConfirmPolicy =
        entryByName[name]?.confirmPolicy ?: ToolConfirmPolicy.BLOCK

    /**
     * 取工具所属域（未知名返回 null）。
     *
     * ⚠️ 唯一产地：调用方不要再自己 `toolList.firstOrNull { … }?.group` ——
     * 那会每次构造整张 `toolList`（MCP 工具是运行时动态的，构造有成本），
     * 且"域从哪来"会分叉成两份实现。
     */
    internal fun domainOfOrNull(name: String): String? = entryByName[name]?.group

    /**
     * 取工具结果的内容信任级别（注入隔离用）。
     *
     * 查不到名字（伪工具/未知工具）一律按 [ToolContentTrust.TRUSTED]：
     * 伪工具（update_plan / manage_memory / load_skill…）的产出是本地确定性文本，
     * 未知工具根本不会被执行，二者都没有包装必要。
     */
    internal fun contentTrustOf(name: String): ToolContentTrust =
        entryByName[name]?.contentTrust ?: ToolContentTrust.TRUSTED

    /**
     * 伪工具的显示名（唯一产地）。
     *
     * 伪工具没有 [com.rokidlab.phone.ai.tools.ToolEntry]（它们不进 [entries]），
     * 但**会出现在聊天的「过程」时间线**。少了这条兜底，过程卡片会把它们渲染成
     * `install_skill` 这种标识符 —— 与静态工具刚刚统一好的"设置页里那个名字"再次分叉。
     */
    private val PSEUDO_DISPLAY_RES: Map<String, Int> = mapOf(
        com.rokidlab.phone.ai.SkillRegistry.TOOL_NAME to R.string.ai_tool_load_skill_name,
        com.rokidlab.phone.ai.SkillRegistry.TOOL_NAME_SECTION to R.string.ai_tool_load_skill_section_name,
        com.rokidlab.phone.ai.SkillRegistry.TOOL_INSTALL to R.string.ai_tool_install_skill_name,
        com.rokidlab.phone.ai.SkillRegistry.TOOL_LIST to R.string.ai_tool_list_skills_name,
        com.rokidlab.phone.ai.SkillRegistry.TOOL_DELETE to R.string.ai_tool_delete_skill_name,
        com.rokidlab.phone.ai.AgentPlan.TOOL_NAME to R.string.ai_tool_update_plan_name,
        com.rokidlab.phone.ai.LongTermMemoryManager.TOOL_NAME to R.string.ai_tool_manage_memory_name,
    )

    /**
     * 模型实际调用的 wire name → **面向用户的显示名**（就是工具设置页里那一套名字）。
     * 查不到时**原样回退**，绝不返回空串。
     *
     * 为什么需要它：聊天窗口的「过程」卡片原先直接渲染 `AgentStep.title`（= wire name），
     * 于是用户看到的是 `get_current_time`、`mcp__7f3a9c__baidu_image_search` 这种标识符，
     * 与他在设置页里见过（静态工具）或自己填过（MCP 服务器名）的名字**对不上**。
     *
     * 为什么放在这里而不是 UI 层自己查：取值必须**只有一处**，理由同
     * [ToolMeta.displayName] —— 调用点自己写 `dynamicName ?: getString(...)` 的话，
     * 漏一处就会显示成某个无关的资源名，而且**不报错**。
     *
     * ⚠️ 本函数**只用于渲染**：落盘的 `AgentStep.title` 必须继续是 wire name —— 那是
     * 排查时与日志对得上的标识符。也因此，历史消息**无需迁移**就会跟着显示成友好名。
     *
     * @param name 模型发来的工具名（也可传任意字符串：查不到即原样返回）
     */
    internal fun displayNameOf(name: String, ctx: Context): String =
        entryByName[name]?.displayName(ctx)
            ?: PSEUDO_DISPLAY_RES[name]?.let { ctx.getString(it) }
            ?: name

    /** 确认摘要：查 [com.rokidlab.phone.ai.tools.ToolEntry.summarize]，未声明走兜底 */
    internal fun summarizeToolCall(name: String, args: JSONObject): String =
        entryByName[name]?.summarize?.invoke(args) ?: "执行操作 $name"

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
     *
     * 现在由 [ToolEntry.sideEffect] 派生（登记处 = 各 provider 的 tools()）。
     *
     * ⚠️ 必须是 `get()` 而不能是 `val`：MCP 工具会在运行时报到，
     * 用 `val` 会在 object 初始化时就把集合固定住，之后新增的工具永远进不来。
     */
    val SIDE_EFFECT_TOOLS: Set<String>
        get() = entries.filter { it.sideEffect }.map { it.name }.toSet()

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
        /**
         * 本机执行（在手机自己的 Linux 容器里跑命令）。
         *
         * 位置紧跟 [FILES] 之后：两者都作用于"这台机器上的数据"，阅读顺序上连着；
         * 也远离 [SYSTEM]（那是设置页不展示的内部工具），语义上不混。
         */
        SHELL(R.string.ai_tool_cat_shell),
        /**
         * 外部 MCP 服务器提供的工具。
         *
         * 位置在 [FILES] 之后、[SYSTEM] 之前 —— **枚举声明顺序 = 设置页分组顺序**，
         * 放在中间会让用户在「文件」与「系统」之间看到它，符合"由本地能力 → 外部能力"的阅读顺序。
         */
        MCP(R.string.ai_tool_cat_mcp),
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
        DOMAIN_SHELL -> ToolCategory.SHELL
        DOMAIN_MCP -> ToolCategory.MCP
        // info / web / knowledge 对用户都是「查信息」
        else -> ToolCategory.INFO_WEB
    }

    /** 工具元数据（设置页展示用，名称/描述走多语言资源；group 用于按会话装配） */
    data class ToolMeta(
        val name: String,
        val group: String,
        val displayNameRes: Int,
        val descriptionRes: Int,
        /**
         * 动态显示名（MCP 等运行时发现的工具）；null = 走资源 ID [displayNameRes]。
         *
         * 取值一律走 [displayName]，不要在调用点自己写 `dynamicName ?: getString(...)` ——
         * 漏一处就会出现"设置页显示成某个无关资源名"的怪现象，而且不报错。
         */
        val dynamicName: String? = null,
        /** 动态描述；null = 走资源 ID [descriptionRes]。取值走 [description] */
        val dynamicDescription: String? = null,
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
    ) {
        /**
         * 设置页显示名：**动态优先、资源兜底**。
         *
         * 静态工具的 [dynamicName] 恒为 null ⇒ 等价于原来的 `ctx.getString(displayNameRes)`，行为不变。
         */
        fun displayName(ctx: Context): String = dynamicName ?: ctx.getString(displayNameRes)

        /** 设置页描述：动态优先、资源兜底（同 [displayName]） */
        fun description(ctx: Context): String = dynamicDescription ?: ctx.getString(descriptionRes)
    }

    /** 设置页要展示的工具（按分类分组前先过滤掉系统性工具） */
    val visibleTools: List<ToolMeta> get() = toolList.filterNot { it.hidden }

    /**
     * **必须有眼镜端在线才能真正完成**的工具（乐奇聊天「本机模式」的排除名单）。
     *
     * 为什么单独列一张表而不是直接按域切：需要眼镜的工具**跨了 3 个域** ——
     * `glasses` 域 5 个（全部要）、`aiui` 域 4 个（生成/装/开/管 AIUI 应用都要落到眼镜上）、
     * 以及 `media` 域的 `show_lyrics`（要把歌词推到眼镜并拉起眼镜系统音乐页）。
     * 按域切要么漏（media 只该摘 1 个，不能整域摘掉 play_song/stop_music）要么多。
     * 因此**显式登记**在工具自己的声明上（而不是按域推导）。
     *
     * ⚠️ 判定口径是「**没有眼镜就做不成**」，不是「和眼镜有关」。以下**故意不在名单里**，
     * 因为它们在手机侧独立完成、且眼镜不可用时已优雅降级（摘掉反而白白损失能力）：
     *  - `show_image`：先把图片放进手机对话气泡，再**尽力**镜像到眼镜悬浮层，
     *    眼镜不在时如实回「已在手机端显示图片；眼镜端未连接…」（见 DisplayToolProvider）。
     *  - `play_song` / `stop_music`：本地 `MusicPlayerController` 播放，不依赖眼镜。
     *  - `get_now_playing` / `get_cover_image`：读手机侧 MediaSession、在手机侧下载封面。
     *  - `save_code_file` / `read_code_file`：读写手机本地项目文件，离线可写代码。
     *
     * 现在由 [ToolEntry.requiresGlasses] 派生（登记处 = 各 provider 的 tools()），
     * 因此「要眼镜」这件事**跟着工具定义走**，改名/新增时会一起被带上，不会漏。
     * ⚠️ 同样必须是 `get()`（理由见 [SIDE_EFFECT_TOOLS]）。
     */
    val GLASSES_REQUIRED_TOOLS: Set<String>
        get() = entries.filter { it.requiresGlasses }.map { it.name }.toSet()

    /** 取 Schema 里的工具名（`{"function":{"name":…}}`）。解析失败返回空串（调用方按「不在名单」处理）。 */
    private fun functionNameOf(schema: JSONObject): String =
        runCatching { schema.optJSONObject("function")?.optString("name").orEmpty() }.getOrDefault("")

    /**
     * Schema 列表 → 工具名集合。
     *
     * 这是**装配侧的唯一投影**：`AiConversationService` 把它连同工具清单一并交给
     * `OpenAiService.buildSystemMessage`，让系统提示里那些"可选能力"条款
     * （update_plan / manage_memory / 技能三件套 …）按**真实下发的**工具做闸门 ——
     * 静态说明与动态 schema 必须同源，否则模型会照着提示去调一个没下发的工具
     * （无人值守下更糟：白耗一轮 + 拿到一句"被安全策略拦截"）。
     */
    internal fun namesOf(schemas: List<JSONObject>): Set<String> =
        schemas.mapNotNull { functionNameOf(it).takeIf { n -> n.isNotEmpty() } }.toSet()

    /** 全部工具（含已禁用），按声明顺序。⚠️ 必须是 `get()`：MCP 工具会在运行时报到 */
    val toolList: List<ToolMeta>
        get() = entries.map { it.toMeta() }

    /**
     * 声明 → 设置页元数据。
     *
     * [ToolMeta] 这个类型**刻意保留不动**：设置页（ToolsManagePage）、AIUI 工具网关（ToolGateway）、
     * 技能白名单（SkillRegistry）都只认它。接缝化只改"数据从哪来"，不改"长什么样"，
     * 这样调用方零改动、可以分步迁移。
     */
    private fun com.rokidlab.phone.ai.tools.ToolEntry.toMeta(): ToolMeta = ToolMeta(
        name = name,
        group = group,
        displayNameRes = displayNameRes,
        descriptionRes = descriptionRes,
        dynamicName = dynamicName,
        dynamicDescription = dynamicDescription,
        category = category ?: categoryOfGroup(group),
        hidden = hidden,
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

    /**
     * 最近一次打开 AIUI 用的是哪条**宿主链路**。
     *
     * 关闭时必须知道这个：两条链路对应两个完全不同的关闭命令 ——
     * [LOCAL_HOST] → `closeAiuiHost()`（关 RokidLink 里渲染 .aix 的 AiuiLinkActivity），
     * [OFFICIAL] → `Sys_AIUI_Stop`（关眼镜系统渲染层）。
     * ⚠️ 不能靠"本地有没有这个 .aix"反推：对话生成的包**本地一定有** .aix，
     * 但它完全可能是走官方渲染层打开的（比如用户紧接着打开了另一个官方智能体）——
     * 反推会发错命令，表现为"说了关掉，眼镜上还亮着"。
     */
    internal enum class AiuiHost {
        /** 自托管宿主：.aix 推给 RokidLink 的 AiuiLinkActivity 渲染（对话生成 / 本地上传的包） */
        LOCAL_HOST,

        /** 眼镜官方渲染层：Sys_AIUI_Start / Ai_RenderPayload */
        OFFICIAL,
    }

    /** 最近一次成功下发打开（Sys_AIUI_Start）的 agentId，供 stop_aiui_app 不带名称时作占位/日志 */
    @Volatile
    internal var lastStartedAiuiAgentId: String? = null

    /** 最近一次成功打开的宿主链路；null = 本进程还没打开过（App 重启后的兜底见 stop_aiui_app） */
    @Volatile
    internal var lastStartedAiuiHost: AiuiHost? = null

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

    /**
     * 某工具**首次出现**时把开关默认写成关（运行时才有的工具 ⇒ 目前是 MCP —— 的准入控制）。
     *
     * 为什么需要单独一个口：[isEnabled] 的兜底是 `true`，这对静态工具是对的（它们几乎都该默认可用），
     * 但第三方 MCP 工具按「默认不信任」必须默认关。同一个兜底值表达不了两种意图，
     * 于是这里在工具首次报到时落一个**显式的 false**。
     *
     * 只在该 key **不存在**时写入 ⇒ 用户此后的任何显式选择（哪怕又开回来）都不会被覆盖。
     *
     * ⚠️ 不要改成"每次连接都写 false"：那会把用户已经开好的工具在重连后强行关掉，
     * 表现为「我的设置自己变回去了」——这类"设置不生效"的抱怨极难排查。
     */
    internal fun ensureDisabledByDefault(context: Context, name: String) {
        val prefs = context.getSharedPreferences(TOOL_PREFS, Context.MODE_PRIVATE)
        if (!prefs.contains(KEY_PREFIX + name)) {
            prefs.edit().putBoolean(KEY_PREFIX + name, false).apply()
        }
    }

    /** 工具声明列表（仅已开启的工具），直接传给 OpenAI 兼容协议的 tools 参数（默认全量域） */
    fun schemas(context: Context): List<JSONObject> = schemasFor(context, DOMAIN_ALL)

    /**
     * 按会话场景装配工具声明：仅包含「会话启用域 ∩ 已开启开关」的工具。
     * 主 Agent 用 [SESSION_AGENT_DOMAINS]（全量），本地轻量用 [SESSION_LOCAL_DOMAINS]（空集）。
     * 后续新增会话/场景（如商店助手、答题、生图）时在此声明自己的域子集，无需改执行逻辑。
     */
    /**
     * schemasFor 结果缓存：key = (domains 集合, 工具集版本号)，value = (全量工具开关快照, Schema 列表)。
     * 每次调用读一遍开关快照（SharedPreferences 首载后为内存读，开销极小）做双重校验，
     * 命中即跳过 ~30 个工具的 JSONObject 重建；[setEnabled] 写入后清空。
     * 调用方仅对返回的 List 做 add/remove，不修改 JSONObject 本身，共享实例安全。
     *
     * ⚠️ **key 里的版本号 [tableVersion] 不是保险而是必需品**（2026-09-20 接 MCP 时加）：
     *  - 开关快照**能**覆盖"工具名集合变化"（新工具 → snapshot 多一个 key → 不等 → 重算）；
     *  - 但它**覆盖不了**「工具名不变、schema 内容变了」：MCP server 更新了工具定义、
     *    或用户把配置指向了另一个同名不同实现的 server —— 此时 snapshot 完全相等，
     *    缓存会一直命中旧 schema，表现是「模型调用参数始终是老版本的」，且不报错。
     *  - 因此工具集一变就必须让版本号跟着变（[refreshTools]），双方一起参与缓存键。
     */
    private val schemaCache =
        java.util.concurrent.ConcurrentHashMap<Pair<Set<String>, Int>, Pair<Map<String, Boolean>, List<JSONObject>>>()

    /**
     * @param excludeGlassesTools true = 摘掉 [GLASSES_REQUIRED_TOOLS]（乐奇聊天「本机模式」：
     *   没眼镜时不要让模型去调注定失败的工具）。过滤发生在缓存**之后**，
     *   因此不改变缓存键、也不重建 JSONObject。
     */
    fun schemasFor(context: Context, domains: Set<String>, excludeGlassesTools: Boolean = false): List<JSONObject> {
        val prefs = context.getSharedPreferences(TOOL_PREFS, Context.MODE_PRIVATE)
        val tools = toolList
        val snapshot = HashMap<String, Boolean>(tools.size)
        tools.forEach { snapshot[it.name] = prefs.getBoolean(KEY_PREFIX + it.name, true) }
        // 版本号也参与 key：单靠 snapshot 捕获不了"工具名不变但 schema 变了"（见 schemaCache 注释）
        val cacheKey = domains to tableVersion
        val cached = schemaCache[cacheKey]?.let { (cachedSnapshot, cachedSchemas) ->
            if (cachedSnapshot == snapshot) cachedSchemas else null
        }
        val result = cached ?: tools
            .filter { it.group in domains && snapshot[it.name] == true }
            .map { buildSchema(it) }
            .also { schemaCache[cacheKey] = snapshot to it }
        return if (excludeGlassesTools) {
            result.filterNot { functionNameOf(it) in GLASSES_REQUIRED_TOOLS }
        } else {
            result
        }
    }

    /**
     * 只读工具声明：全域 ∩ 已开启 ∩ 风险档 = [ToolRisk.READ_ONLY]。
     *
     * 供**只读**场景装配工具（只读子代理 `ReadOnlySubagent`）。
     * ⚠️ 定时自主任务**不再**用它，改用 [schemasUnattended]（只读 ∪ 本地媒体白名单，原因见那里）。
     *
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
     * 这是「绝不改状态」这条安全承诺的实际落点，也是 [unattendedToolNames] 的基线。
     * 抽成不依赖 Context 的纯函数，是为了让回归测试能直接断言
     * 「拨号/装机/写文件/设定时都不在名单里」—— 新增工具若登记错了风险档，测试立刻失败。
     */
    internal fun readOnlyToolNames(): Set<String> = toolList
        .filter { runCatching { ToolRiskMap.riskOf(it.name) == ToolRisk.READ_ONLY }.getOrDefault(false) }
        .map { it.name }
        .toSet()

    /**
     * 无人值守场景**额外放行**的本地媒体工具（白名单，逐个显式登记）。
     *
     * 为什么必须有这张白名单：`control_music` 的风险档是 [ToolRisk.LOCAL_SIDE_EFFECT]
     * （调用即真的开始播放），因此被只读名单物理排除 —— 于是「16 点帮我放首《断桥残雪》」
     * 这类定时任务到点后，模型在工具列表里**看不到播歌工具**，只能生成一段文字，
     * 那段文字又被播到眼镜上，用户侧的表现就是「明明有播歌工具，却只跑到眼镜上念了一句」。
     *
     * 放行判据是「误触发代价低、且完全可撤销」：放歌只是本机出声，用户一句「停」或按暂停即可中止，
     * 与拨号/装机/改设置这类不可撤销动作不是一档。由此也划出边界：
     *  - 只登记**本机、可撤销**的媒体工具，不做「整个 `media` 域都放行」的推导
     *    （域内 `show_lyrics` 会拉起眼镜系统音乐页，属越界，仍不放行）；
     *  - 拔高工具本身的 [ToolRisk] 档来「放行」是禁止的 —— 那会一并击穿
     *    只读准入、只读子代理等所有按档判定的地方。
     */
    internal val UNATTENDED_MEDIA_ALLOWLIST: Set<String> = setOf("control_music")

    /**
     * 无人值守工具名集合（纯函数，可单测）：[readOnlyToolNames] ∪ [UNATTENDED_MEDIA_ALLOWLIST]。
     *
     * 除白名单外的副作用工具（拨号/装机/写文件/设定时/开眼镜应用）依旧不在名单里，
     * 即「自主任务绝不会自己拨号或改设置」这条承诺不变。
     */
    internal fun unattendedToolNames(): Set<String> = readOnlyToolNames() + UNATTENDED_MEDIA_ALLOWLIST

    /**
     * 无人值守工具声明：全域 ∩ 已开启 ∩ [unattendedToolNames]。
     *
     * 定时触发的自主任务（`TimerAction.AgentPrompt`）的**唯一装配入口**：
     * 既要守住「无人监管时不改状态」，又要让「到点放首歌」这类低风险本地媒体需求真的能执行。
     *
     * @param excludeGlassesTools true = 一并摘掉 [GLASSES_REQUIRED_TOOLS]（本机模式下的自主任务：
     *   无人值守 + 没眼镜，更需要把注定失败的工具排除干净）
     */
    fun schemasUnattended(context: Context, excludeGlassesTools: Boolean = false): List<JSONObject> {
        val allowed = unattendedToolNames()
        val schemas = schemasFor(context, DOMAIN_ALL)
            .filter { functionNameOf(it) in allowed }
        return if (excludeGlassesTools) {
            schemas.filterNot { functionNameOf(it) in GLASSES_REQUIRED_TOOLS }
        } else {
            schemas
        }
    }

    /** 工具执行中的人性化进度文案（眼镜端显示 + 手机端状态栏共用） */
    /** 过程时间线文案：查 [ToolEntry.statusText]；未声明的（伪工具等）走兜底 */
    fun statusText(name: String): String =
        entryByName[name]?.statusText
            // 伪工具的文案归各自的定义者（技能系列的"正在加载/安装技能…"写在 SkillRegistry）
            ?: if (name in com.rokidlab.phone.ai.approval.PseudoTools.names()) {
                com.rokidlab.phone.ai.SkillRegistry.statusText(name)
            } else {
                "正在执行 $name…"
            }

    /** 组装单个工具的 JSON Schema（internal：金标评测单测直接校验声明内容） */
    internal fun buildSchema(meta: ToolMeta): JSONObject =
        entryByName[meta.name]?.schema ?: com.rokidlab.phone.ai.tools.toolSchema(
            name = meta.name,
            description = "执行 ${meta.name} 工具。",
            parameters = mapOf("type" to "object", "properties" to mapOf<String, Any>()),
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
        // 走 allProviders（静态 + 动态）：MCP 这类运行时注册的 provider 也要能命中
        val provider = allProviders.firstOrNull { name in it.toolNames }
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
