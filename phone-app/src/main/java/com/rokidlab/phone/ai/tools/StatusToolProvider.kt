package com.rokidlab.phone.ai.tools

import com.rokidlab.phone.R
import com.rokidlab.phone.ai.ToolRisk

import android.content.Context
import com.rokidlab.phone.ai.AgentSessionManager
import com.rokidlab.phone.ai.AgentTaskStore
import com.rokidlab.phone.ai.KnowledgeBase
import com.rokidlab.phone.ai.LongTermMemoryManager
import com.rokidlab.phone.ai.SkillRegistry
import com.rokidlab.phone.ai.ToolGateway
import com.rokidlab.phone.ai.ToolRegistry
import com.rokidlab.phone.ai.session.SessionDump
import com.rokidlab.phone.ai.session.SessionLog
import com.rokidlab.phone.app.LabApplication
import com.rokidlab.phone.store.ChatMsg
import com.rokidlab.phone.store.ChatSessionStore
import com.rokidlab.phone.store.ChatStateHolder
import com.rokidlab.phone.store.MAX_HISTORY
import com.rokidlab.phone.util.LogCollector
import org.json.JSONObject

/**
 * StatusToolProvider —— Agent 自我认知域（自诊断 + 日志 + 自身历史检索）。
 *
 * 为什么需要：Agent 此前对自己的运行时状态一无所知 —— 不知道当前用哪个模型、
 * 眼镜连没连、哪些工具被用户关掉了、知识库里有没有资料、记忆里存了什么。
 * 用户问「你为什么打不开应用」「你能做什么」时，模型只能猜或反问。
 * 本域把「关于自己」的事实查询能力交给模型，让它先自检再回答。
 *
 * 其中 [searchPastConversations] 属于「自身历史」：聊天记录此前只有 UI 能看，
 * 模型看不到 —— 用户问「我们上次聊的那个定时任务叫什么」时只能反问。
 */
internal object StatusToolProvider : ToolProvider {
    private const val TAG = "StatusToolProvider"

    override val toolNames = setOf(
        "get_agent_status",
        "read_recent_logs",
        "clear_agent_task",
        "search_past_conversations",
        "list_sessions",
        "read_session",
        "session_trace",
    )

    override fun tools(): List<ToolEntry> = listOf(
        ToolEntry(
            name = "get_agent_status",
            group = ToolRegistry.DOMAIN_INFO,
            displayNameRes = R.string.ai_tool_get_agent_status_name,
            descriptionRes = R.string.ai_tool_get_agent_status_desc,
            hidden = true,
            risk = ToolRisk.READ_ONLY,
            statusText = "正在自检运行状态…",
            schema = toolSchema(
                name = "get_agent_status",
                description = "查询我自己当前的运行状态：所用模型与接口、眼镜是否已连接（ADB 通道是否可用）、知识库有几篇文档、长期记忆几条、装了哪些技能、哪些工具被用户关闭、AIUI 页面工具网关是否开启。当用户问「你能做什么/你怎么不能XX」「眼镜连上了吗」「知识库里有东西吗」，或在某次设备操作失败后需要判断是不是连接/开关/权限问题时调用本工具自检后再回答，不要凭空猜测自己的状态。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf<String, Any>(),
                ),
            ),
        ),
        ToolEntry(
            name = "read_recent_logs",
            group = ToolRegistry.DOMAIN_INFO,
            displayNameRes = R.string.ai_tool_read_recent_logs_name,
            descriptionRes = R.string.ai_tool_read_recent_logs_desc,
            hidden = true,
            risk = ToolRisk.READ_ONLY,
            statusText = "正在读取运行日志…",
            schema = toolSchema(
                name = "read_recent_logs",
                description = "读取本机 App 最近的运行日志，用于排查功能异常（如「刚才为什么没成功」「是不是报错了」「打开应用失败了」）。默认只返回错误级日志；需要看完整流程时把 errorsOnly 设为 false。返回的是尾部最新日志片段，可据此向用户如实说明失败原因。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "errorsOnly" to mapOf("type" to "boolean", "description" to "是否只看错误日志，默认 true（只看 ERROR/FATAL）；排查流程细节时传 false 看全部"),
                    ),
                ),
            ),
        ),
        ToolEntry(
            name = "clear_agent_task",
            group = ToolRegistry.DOMAIN_INFO,
            displayNameRes = R.string.ai_tool_clear_agent_task_name,
            descriptionRes = R.string.ai_tool_clear_agent_task_desc,
            hidden = true,
            risk = ToolRisk.LOCAL_SIDE_EFFECT,
            statusText = "正在清除任务记录…",
            schema = toolSchema(
                name = "clear_agent_task",
                description = "放弃当前未完成的任务记录（不删除任何已生成的文件）。当用户说「算了不做了」「不用继续了」「放弃这个任务」「重新开始」时调用；放弃后下次对话不会再自动带上该任务的续做提示。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf<String, Any>(),
                ),
            ),
        ),
        ToolEntry(
            name = "search_past_conversations",
            group = ToolRegistry.DOMAIN_INFO,
            displayNameRes = R.string.ai_tool_search_past_conversations_name,
            descriptionRes = R.string.ai_tool_search_past_conversations_desc,
            risk = ToolRisk.READ_ONLY,
            statusText = "正在检索历史对话…",
            schema = toolSchema(
                name = "search_past_conversations",
                description = "在历史聊天记录里做关键词检索。结果**按会话分组**，每条都标明它属于哪个会话。当用户提到以前聊过、但当前上下文里没有的事时调用，例如「我们上次聊的那个定时任务叫什么」「我之前问过你什么」「我上周让你记的那个事」。⚠️ 返回里标为「其他会话」的内容**不是**当前这次对话发生的 —— 用户问「刚才聊了什么」时只能用当前会话（或你自己的上下文）回答，不能把其他会话的内容说成\"刚才\"。不要用它查长期记忆（那用 manage_memory）或知识库（那用 search_knowledge_base）。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "query" to mapOf("type" to "string", "description" to "检索关键词（2~6 个字最有效，如「定时任务」「天气」）；传空或 * 表示不筛选，直接返回最近几轮对话"),
                        "limit" to mapOf("type" to "integer", "description" to "返回轮数，默认 5，最多 20", "minimum" to 1, "maximum" to 20),
                    ),
                    "required" to listOf("query"),
                ),
            ),
        ),
        // ═══ 会话查询工具族（方案 §4.3.1，前提是阶段一事件流）═══
        // 与 search_past_conversations 的分工：那个是**词法检索**（给我关键词，我帮你找相关轮次）；
        // 这三个是**结构化读取**（列出会话 / 按 seq 读事件 / 看某轮的工具调用链）。
        // 前者回答"哪一轮提到过这个"，后者回答"那次到底发生了什么、调了什么、返回了什么"。
        ToolEntry(
            name = "list_sessions",
            group = ToolRegistry.DOMAIN_INFO,
            displayNameRes = R.string.ai_tool_list_sessions_name,
            descriptionRes = R.string.ai_tool_list_sessions_desc,
            risk = ToolRisk.READ_ONLY,
            statusText = "正在列出历史对话…",
            schema = toolSchema(
                name = "list_sessions",
                description = "列出本机保存的历史会话（标题、最后更新时间、消息条数），并标出当前正在用的那个。当用户问「我们一共有几个对话」「上次那个聊天在哪」或你想找某个以前聊过的话题时先调用它拿到 sessionId，再用 read_session / session_trace 深入查看。只返回元数据，不含对话内容。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "limit" to mapOf("type" to "integer", "description" to "返回会话数，默认 10，最多 30", "minimum" to 1, "maximum" to 30),
                    ),
                ),
            ),
        ),
        ToolEntry(
            name = "read_session",
            group = ToolRegistry.DOMAIN_INFO,
            displayNameRes = R.string.ai_tool_read_session_name,
            descriptionRes = R.string.ai_tool_read_session_desc,
            risk = ToolRisk.READ_ONLY,
            statusText = "正在读取历史对话…",
            schema = toolSchema(
                name = "read_session",
                description = "按**发生顺序**读某个会话的完整事件流：用户说了什么、AI回了什么、调用了哪些工具（含参数与返回）、注入了哪些上下文（长期记忆/知识库/技能）、哪几轮被压缩过。用于回答「上次那个报错最后怎么解决的」「我当时让你做的那件事后来怎么样了」——这类问题需要**过程**，不是结论，search_past_conversations 给不了。sessionId 从 list_sessions 取；不传则读当前会话。内容较长时只返回开头一段，末尾会告诉你可以用 fromSeq 接着读。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "sessionId" to mapOf("type" to "string", "description" to "会话 id（从 list_sessions 获取）；留空 = 当前会话"),
                        "fromSeq" to mapOf("type" to "integer", "description" to "从哪个事件序号开始读（用于接着上次的位置继续）；默认从头"),
                        "limit" to mapOf("type" to "integer", "description" to "最多返回多少个事件，默认 30，最多 100", "minimum" to 1, "maximum" to 100),
                    ),
                ),
            ),
        ),
        ToolEntry(
            name = "session_trace",
            group = ToolRegistry.DOMAIN_INFO,
            displayNameRes = R.string.ai_tool_session_trace_name,
            descriptionRes = R.string.ai_tool_session_trace_desc,
            risk = ToolRisk.READ_ONLY,
            statusText = "正在查看工具调用链…",
            schema = toolSchema(
                name = "session_trace",
                description = "查看某一轮的工具调用链（血缘）：调了哪些工具、参数是什么、返回了什么、有没有**调了没回来**的（那说明当时进程被打断/工具卡死）。用于回答「你上一轮到底做了什么」「那个任务卡在哪一步」「你刚才是不是失败了」。查最近一轮时把 turn 留空即可。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "sessionId" to mapOf("type" to "string", "description" to "会话 id（从 list_sessions 获取）；留空 = 当前会话"),
                        "turn" to mapOf("type" to "integer", "description" to "第几轮；留空 = 最近一个调过工具的轮次"),
                    ),
                ),
            ),
        ),
    )

    /** 日志回填上限：日志可能上千行，全量回填浪费 token（真实结论通常在尾部） */
    private const val LOG_TAIL_CHARS = 3000

    /** 跨会话检索：单条提问/回答回填上限，防长回答把 token 吃光 */
    private const val TURN_TEXT_MAX_CHARS = 220

    override fun execute(context: Context, name: String, args: JSONObject): String {
        return when (name) {
            "get_agent_status" -> getAgentStatus(context)
            "read_recent_logs" -> readRecentLogs(args)
            "clear_agent_task" -> {
                AgentTaskStore.clear(context)
                "好的，已放弃未完成的任务记录。需要重新开始时告诉我要做什么就行"
            }
            "search_past_conversations" -> searchPastConversations(context, args)
            "list_sessions" -> listSessions(context, args)
            "read_session" -> readSession(context, args)
            "session_trace" -> sessionTrace(context, args)
            else -> throw IllegalArgumentException("未知工具: $name")
        }
    }

    /**
     * 汇总 Agent 运行时状态。
     * 每项都只报「可验证的事实」，不猜测：拿不到就如实说未知（如会话未初始化）。
     */
    private fun getAgentStatus(context: Context): String {
        val sb = StringBuilder("我的运行状态：")

        // 1) 模型与接口
        val app = context.applicationContext as? LabApplication
        val cfg = runCatching {
            if (app?.hasCxrL() == true) app.cxrL.getAiConfig() else null
        }.getOrNull()
        if (cfg != null) {
            val host = runCatching { java.net.URI(cfg.baseUrl).host }.getOrNull() ?: cfg.baseUrl
            sb.append("\n· 模型：${cfg.model}（接口 $host，${if (cfg.apiKey.isBlank()) "未配置密钥" else "密钥已配置"}）")
        } else {
            sb.append("\n· 模型：会话未初始化，暂时读不到配置")
        }

        // 2) 眼镜连接（ADB 通道共享会话是否可用）
        val adb = ToolRegistry.adbClient(context)
        val ip = app?.glassesIp ?: "未知"
        sb.append("\n· 眼镜：").append(
            when {
                adb != null -> "已连接（ADB 通道可用，IP $ip）"
                app?.hasCxrL() == true -> "会话已建立但 ADB 通道当前不可用（IP $ip），涉及眼镜的操作可能失败"
                else -> "未连接（尚未初始化会话）"
            },
        )

        // 3) 知识与记忆
        val kbDocs = runCatching { KnowledgeBase.docCount(context) }.getOrDefault(0)
        sb.append("\n· 知识库：$kbDocs 篇文档")
        sb.append("；长期记忆：${runCatching { LongTermMemoryManager.count(context) }.getOrDefault(0)} 条")
        sb.append("；会话记忆：${if (AgentSessionManager.isEnabled(context)) "开启" else "关闭"}")

        // 4) 技能
        val skills = runCatching { SkillRegistry.listSkills(context) }.getOrDefault(emptyList())
        sb.append("\n· 技能：${skills.size} 个")
        if (skills.isNotEmpty()) {
            sb.append("（").append(skills.joinToString("、") { it.name }).append("）")
        }

        // 5) 工具开关：如实报告被关闭的工具，避免模型反复调用已禁用能力
        val disabled = ToolRegistry.toolList.filter { !ToolRegistry.isEnabled(context, it.name) }
        sb.append("\n· 工具：共 ${ToolRegistry.toolList.size} 个，其中 ${disabled.size} 个被用户关闭")
        if (disabled.isNotEmpty()) {
            sb.append("（已关闭：").append(disabled.joinToString("、") { it.name }).append("）")
        }
        sb.append("；计划工具 update_plan：可用")

        // 6) AIUI 页面工具网关（页面 Lab.callTool 的总闸）
        sb.append("\n· AIUI 页面工具网关：${if (ToolGateway.isEnabled(context)) "开启" else "关闭（页面内 callTool 全部不可用）"}")

        // 7) 未完成的长任务（流程级 checkpoint）：让模型知道"上次做到哪了"，
        //    用户问「上次那个做完了吗」时能如实回答，也能据此接着做
        val pendingTask = runCatching { AgentTaskStore.summary(context) }.getOrNull()
        if (!pendingTask.isNullOrBlank()) {
            sb.append("\n· 有未完成的任务（用户说「继续」即可接着做）：\n").append(pendingTask)
        }

        return sb.toString()
    }

    /**
     * 读取最近的运行日志。
     * 默认只取错误级（排查"为什么没成功"时最关键），可按需要全量；统一截尾回填
     * （日志按时间升序，最新在最尾部，全量回填既浪费 token 又会让真实结论落在中间）。
     */
    private fun readRecentLogs(args: JSONObject): String {
        val errorsOnly = args.optBoolean("errorsOnly", true)
        val raw = runCatching {
            if (errorsOnly) LogCollector.getErrorLogText() else LogCollector.getLogText()
        }.getOrElse { e ->
            return "读取日志失败：${e.message}"
        }
        if (raw.isBlank()) return "日志为空"
        val tail = if (raw.length > LOG_TAIL_CHARS) raw.takeLast(LOG_TAIL_CHARS) else raw
        val prefix = if (raw.length > LOG_TAIL_CHARS) {
            "（日志较长，仅显示最近 $LOG_TAIL_CHARS 字符，共 ${raw.length} 字符）\n"
        } else {
            ""
        }
        return prefix + tail
    }

    // ═══════════════════════════════════════════════════
    // 跨会话检索（Agent 缺口「跨会话检索」）
    // ═══════════════════════════════════════════════════

    /**
     * 检索单元：一「轮」= 用户提问 + 紧随其后的 AI 回答。
     *
     * `internal` 而非 `private`：归属渲染（[renderGrouped]）必须可被单测驱动 ——
     * "每条结果都带会话身份"这条不变式没有编译期/运行期检查，只能靠测试钉住。
     */
    internal class Turn(val time: String, val question: String, val answer: String)

    /** 每个会话最多取多少轮参与检索（防止一个超长会话把别的会话挤出结果） */
    private const val MAX_TURNS_PER_SESSION = 200

    /** 无关键词时当前会话最多返回几轮（它的更早内容模型自己上下文里有，这里只是补充） */
    private const val MIN_TURNS_CURRENT = 3

    /**
     * 归属声明：**每一条检索结果都必须带着它说**。
     *
     * 这不是客套话，是修一个真机 bug 的唯一手段 —— 见 [searchPastConversations] 的 ★ 注释：
     * 光有分组标题还不够，模型完全可能把「其他会话」那几行也读成"刚才聊的"，
     * `internal`（不是 private）：测试要断言"任何一次检索结果里都必须带它"。
     */
    internal const val ATTRIBUTION_NOTICE =
        "⚠️ 上面标为「其他会话」的内容发生在**别的对话**里，不是当前这次对话 —— " +
            "用户问「刚才聊了什么」「我们刚才说到哪了」时，只能用当前会话（含你自己上下文里已有的）回答，" +
            "绝不能把其他会话的内容说成\"刚才\"；引用它们时必须说明\"在另一个对话里\"。"

    /**
     * 在乐奇聊天的落盘历史里按关键字检索相关轮次（**跨会话，结果按会话分组**）。
     *
     * ★ 为什么必须分组渲染（2026-09-19 真机 bug 的修复点）：
     *   本函数原先读的是 `ChatStateHolder.readPersistedHistory()` —— 一个把**全部会话拼成扁平列表**的
     *   接口，会话身份在那一步就丢了。渲染又写成「最近的 N 轮对话」，于是模型看到一条连贯的"最近"，
     *   而它其实是**两个会话**的内容交错。真机复现：新建对话问「刚才聊什么了」，模型答
     *   "刚才咱聊了两件事：一是您让我记住叫您周哥，二是您问附近有什么好吃的" ——
     *   第二件事发生在**另一个会话**里。用户侧的观感就是"新建了对话它还记得上一个"，
     *   而实际坏掉的是**归属**，不是会话隔离（事件流与会话记忆本身是正确的）。
     *   ⇒ 检索跨会话是对的，**但不能不给归属**。改归属只能改这里（数据层已提供分组读法）。
     *
     * **容量口径**（已与用户确认）：单会话最多取 [MAX_TURNS_PER_SESSION] 轮，不做按月归档 ——
     * 落盘文件本身由 [ChatStateHolder] 的加载逻辑裁剪到 [MAX_HISTORY] 条，更早的对话已经不在盘上。
     *
     * **打分**（纯词法，够用的召回启发式）：查询词元与轮次文本的词元重合率，
     * 再对「整串原话命中」给一个拉满的加成（用户复述自己原话时不该被长回答稀释）。
     * 词元 = 英文/数字词 + 中文 2-gram（中文无空格，2-gram 是成熟的词法口径）。
     *
     * @param args `query` 检索关键词；传空或 `*` 时返回最近若干轮（回应用户「我们最近聊了什么」）
     */
    private fun searchPastConversations(context: Context, args: JSONObject): String {
        val query = args.optString("query").trim()
        val limit = args.optInt("limit", 5).coerceIn(1, 20)
        val sessions = runCatching { ChatStateHolder.readPersistedSessions(context) }
            .getOrElse { e -> return "读取聊天历史失败：${e.message}" }
        if (sessions.isEmpty()) return "还没有可检索的历史对话。"

        val current = AgentSessionManager.currentSessionId()
        // ★ 归组在前、渲染在后：先把"这一句属于哪个会话"固定到每个结果上，
        //   渲染层就再也没有机会把它拍平成"一条对话的最近几轮" —— 那正是本次修的 bug
        //   （扁平列表 → 模型把别的会话说成"刚才"）。
        val located = sessions.flatMap { s ->
            s.messages.toTurns().takeLast(MAX_TURNS_PER_SESSION).map {
                LocatedTurn(s.id, s.title, s.updatedAt, it)
            }
        }
        if (located.isEmpty()) return "历史里没有可检索的对话内容（共 ${sessions.size} 个会话）。"

        val picked: List<Pair<LocatedTurn, Double>>
        val header: String
        if (query.isEmpty() || query == "*") {
            // 当前会话优先（用户问「我们最近聊了什么」指的就是它）；
            // 其它会话各取最近一轮 —— 那才是用户在这句里真正问不到的、需要"想起来"的东西。
            val mine = located.filter { it.sessionId == current }.takeLast(MIN_TURNS_CURRENT)
            val otherPick = ArrayList<LocatedTurn>()
            val seenSessions = HashSet<String>()
            for (t in located.asReversed()) {
                if (t.sessionId == current || t.sessionId in seenSessions) continue
                seenSessions.add(t.sessionId)
                otherPick.add(t)
                if (mine.size + otherPick.size >= limit) break
            }
            picked = (mine + otherPick).map { it to 0.0 }
            header = "最近 ${picked.size} 轮对话（共 ${sessions.size} 个会话 ${located.size} 轮可检索）："
        } else {
            val qTokens = tokens(query)
            if (qTokens.isEmpty()) return "请提供更有意义的检索关键词（至少 2 个字符）。"
            val scored = located
                .map { it to score(qTokens, query, it.turn) }
                .filter { it.second > 0.0 }
                .sortedByDescending { it.second }
                .take(limit)
            if (scored.isEmpty()) {
                return "没找到和「$query」相关的历史对话（已检索 ${sessions.size} 个会话共 ${located.size} 轮）。" +
                    "可以换更短的关键词再试，或用 list_sessions 看看有哪些会话。"
            }
            picked = scored
            header = "找到 ${picked.size} 条相关历史对话（按相关度排序，来自 " +
                "${picked.map { it.first.sessionId }.toSet().size} 个会话）："
        }

        return renderSearchResult(header, picked, current)
    }

    /** 检索结果的最小单元：一轮 + 它在哪个会话里（归属必须跟着结果走，不能只在渲染时才想起来） */
    internal data class LocatedTurn(
        val sessionId: String,
        val sessionTitle: String,
        val sessionUpdatedAt: Long,
        val turn: Turn,
    )

    /**
     * 检索结果的**唯一出口**：表头 + 归属声明 + 按会话分组的正文。
     *
     * 为什么要收成一个出口：归属声明是这次 bug 修复的**核心那一句**，而它有两条分支
     * （关键词检索 / 最近若干轮）。两条分支各自拼一遍字符串，迟早会有一条漏掉声明 ——
     * 收成一个出口，测试只要断言"这个出口的输出里一定有声明"就够了。
     */
    internal fun renderSearchResult(
        header: String,
        picked: List<Pair<LocatedTurn, Double>>,
        currentSessionId: String,
    ): String = header + "\n" + ATTRIBUTION_NOTICE + "\n\n" + renderGrouped(picked, currentSessionId)

    /**
     * 按会话分组的渲染。
     *
     * 分组顺序沿用 [picked] 的顺序（"最近"＝当前会话在前，关键词＝最相关在前），
     * `groupBy` 保持首次出现顺序，所以不需要再排一次。
     *
     * `internal` 而非 `private`：这是那个真机 bug 的直接修复点，必须可被单测驱动。
     */
    internal fun renderGrouped(
        picked: List<Pair<LocatedTurn, Double>>,
        currentSessionId: String,
    ): String {
        val sb = StringBuilder()
        var index = 0
        picked.groupBy { it.first.sessionId }.forEach { (sid, items) ->
            val head = items.first().first
            val isCurrent = sid == currentSessionId
            sb.append("【").append(if (isCurrent) "当前会话" else "其他会话").append("】")
                .append(head.sessionTitle)
            if (!isCurrent) sb.append("（").append(relTime(head.sessionUpdatedAt)).append("）")
            items.forEach { (lt, s) ->
                index++
                sb.append('\n').append(renderTurn(lt.turn, index, s))
            }
            sb.append('\n')
        }
        return sb.toString().trimEnd()
    }

    /** 相对时间：刚过去用分钟，几小时内用小时，再久就报日期（模型只据此判断"多久以前"） */
    private fun relTime(ts: Long): String {
        if (ts <= 0L) return "时间未知"
        val diff = System.currentTimeMillis() - ts
        return when {
            diff < 60_000L -> "刚刚"
            diff < 3_600_000L -> "${diff / 60_000L} 分钟前"
            diff < 86_400_000L -> "${diff / 3_600_000L} 小时前"
            else -> java.text.SimpleDateFormat("MM-dd", java.util.Locale.getDefault())
                .format(java.util.Date(ts))
        }
    }

    /**
     * 把扁平的聊天记录合并成「轮」：
     * 一条用户消息 + 紧随其后的一条 AI 回复算一轮；孤立的 AI 消息（主动播报/开场白）
     * 与末尾未获回复的用户消息各自成一「半轮」。`isStatus` 的进度提示不参与检索。
     */
    private fun List<ChatMsg>.toTurns(): List<Turn> {
        val out = ArrayList<Turn>()
        var pendingQuestion: ChatMsg? = null
        for (msg in this) {
            if (msg.isStatus) continue
            val body = msg.content.trim()
            if (body.isEmpty()) continue
            if (msg.isUser) {
                // 连续两条用户消息时丢弃前一条（没有回答可配对，任务提示/纠错常见）
                pendingQuestion = msg
            } else {
                val q = pendingQuestion
                out.add(Turn(q?.time ?: msg.time, q?.content?.trim().orEmpty(), body))
                pendingQuestion = null
            }
        }
        pendingQuestion?.let { out.add(Turn(it.time, it.content.trim(), "")) }
        return out
    }

    /** 词元集合：英文/数字词（≥2 字符）+ 中文 2-gram（单字中文另计） */
    private fun tokens(text: String): Set<String> {
        val out = HashSet<String>()
        val lower = text.lowercase()
        Regex("[a-z0-9]+").findAll(lower).forEach { m ->
            if (m.value.length >= 2) out.add(m.value)
        }
        val cjk = lower.filter { it.code in 0x4E00..0x9FFF }
        if (cjk.length == 1) out.add(cjk)
        for (i in 0 until cjk.length - 1) out.add(cjk.substring(i, i + 2))
        return out
    }

    /** 相关度 ∈ [0,1]：词元重合率；查询整串出现在轮次文本里时至少 0.95 */
    private fun score(qTokens: Set<String>, rawQuery: String, turn: Turn): Double {
        val text = turn.question + "\n" + turn.answer
        if (text.isBlank()) return 0.0
        val turnTokens = tokens(text)
        if (turnTokens.isEmpty()) return 0.0
        val hit = qTokens.count { it in turnTokens }
        var s = hit.toDouble() / qTokens.size
        val q = rawQuery.trim()
        if (q.length >= 2 && text.contains(q, ignoreCase = true)) s = maxOf(s, 0.95)
        return s
    }

    private fun renderTurn(turn: Turn, index: Int, score: Double): String {
        val sb = StringBuilder("[$index]")
        if (turn.time.isNotBlank()) sb.append(" ").append(turn.time)
        if (score > 0.0) sb.append("（相关度 ").append((score * 100).toInt()).append("%）")
        if (turn.question.isNotBlank()) sb.append("\n  你：").append(clipTurn(turn.question))
        if (turn.answer.isNotBlank()) sb.append("\n  乐奇：").append(clipTurn(turn.answer))
        return sb.toString()
    }

    private fun clipTurn(s: String): String {
        val oneLine = s.replace('\n', ' ').replace(Regex("\\s+"), " ").trim()
        return if (oneLine.length > TURN_TEXT_MAX_CHARS) oneLine.take(TURN_TEXT_MAX_CHARS) + "…" else oneLine
    }

    // ═══════════════════════════════════════════════════
    // 会话查询工具族（方案 §4.3.1；前提＝阶段一事件流）
    //
    // 与上面 searchPastConversations 的分工：
    //   · 那个是**词法检索** —— 给我关键词，我帮你找相关轮次（回答"哪一轮提到过这个"）；
    //   · 这三个是**结构化读取** —— 列出会话 / 按 seq 读事件 / 看某轮的工具调用链
    //     （回答"那次到底发生了什么、调了什么、返回了什么"）。
    //   前者只能给"结论"，后者的价值恰恰在**过程**（工具参数、返回、压缩、失败）——
    //   那是纯文本聊天记录里根本没有的东西。
    // ═══════════════════════════════════════════════════

    /**
     * 列出保存的历史会话。
     *
     * 数据来源＝UI 侧的会话索引（`chat_sessions.json`）：它本来就为列表页维护了标题 /
     * 更新时间 / 条数，模型要知道的也正是这些。**刻意不去逐个打开事件文件数事件** ——
     * 那会把一次"列个清单"变成 N 次文件读。
     */
    private fun listSessions(context: Context, args: JSONObject): String {
        val limit = args.optInt("limit", 10).coerceIn(1, 30)
        val indexFile = java.io.File(context.filesDir, ChatSessionStore.INDEX_FILE)
        val all = runCatching {
            ChatSessionStore.parseIndex(if (indexFile.exists()) indexFile.readText() else "")
        }.getOrElse { e -> return "读取会话列表失败：${e.message}" }
        if (all.isEmpty()) return "本机还没有保存过任何对话。"

        val current = AgentSessionManager.currentSessionId()
        val ordered = ChatSessionStore.sorted(all)
        val shown = ordered.take(limit)
        val fmt = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
        val lines = shown.mapIndexed { i, s ->
            // 标出"当前会话"很重要：否则模型会把自己正待着的这个也当成"过去的会话"去读一遍
            val mark = if (s.id == current) "（当前会话）" else ""
            "[${i + 1}] ${s.title}$mark\n" +
                "    id=${s.id} · ${s.messageCount} 条消息 · 最后更新 ${fmt.format(java.util.Date(s.updatedAt))}"
        }
        val tail = if (ordered.size > shown.size) {
            "\n（共 ${ordered.size} 个会话，这里只列最近 $limit 个；要读某个会话的内容，用 read_session 带上它的 id）"
        } else {
            "\n（共 ${ordered.size} 个会话）"
        }
        return lines.joinToString("\n") + tail
    }

    /** `sessionId` 参数：显式传的优先，留空＝当前会话 */
    private fun sessionIdOf(args: JSONObject): String =
        args.optString("sessionId").trim().ifBlank { AgentSessionManager.currentSessionId() }

    /** 会话标题（读索引拿；拿不到就回落成 id —— 不因一个展示字段读不到就让工具失败） */
    private fun sessionTitle(context: Context, id: String): String = runCatching {
        val indexFile = java.io.File(context.filesDir, ChatSessionStore.INDEX_FILE)
        ChatSessionStore.parseIndex(if (indexFile.exists()) indexFile.readText() else "")
            .firstOrNull { it.id == id }
            ?.title
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: id

    private fun readSession(context: Context, args: JSONObject): String {
        val id = sessionIdOf(args)
        if (id.isBlank()) return "本机还没有正在使用的会话，读不到历史。"
        val file = SessionLog.fileFor(context.filesDir, id)
        if (!file.exists()) {
            // 如实说清两种可能，而不是含糊地"没有记录" —— 用户会据此判断
            // "是升级前的老会话"还是"这个会话根本没聊过"
            return "会话「${sessionTitle(context, id)}」没有可读的事件记录：" +
                "它可能创建于这次升级之前（那时会话记忆只存在内存里、重启即失），也可能这个会话还没聊过。"
        }
        val records = runCatching { SessionLog(file).read() }.getOrElse { e ->
            return "读取会话事件失败：${e.message}"
        }
        if (records.isEmpty()) return "会话「${sessionTitle(context, id)}」的事件记录是空的。"
        val from = args.optInt("fromSeq", 0).coerceAtLeast(0).toLong()
        val limit = args.optInt("limit", 30).coerceIn(1, 100)
        return "会话「${sessionTitle(context, id)}」（共 ${records.size} 条事件，按发生顺序）：\n" +
            SessionDump.dumpSession(records, fromSeq = from, limit = limit)
    }

    private fun sessionTrace(context: Context, args: JSONObject): String {
        val id = sessionIdOf(args)
        if (id.isBlank()) return "本机还没有正在使用的会话，看不到工具调用链。"
        val file = SessionLog.fileFor(context.filesDir, id)
        if (!file.exists()) return "会话「${sessionTitle(context, id)}」没有事件记录，看不到工具调用链。"
        val records = runCatching { SessionLog(file).read() }.getOrElse { e ->
            return "读取会话事件失败：${e.message}"
        }
        // turn 留空 = 最近一个调过工具的轮次（"你刚才到底做了什么"是最常见的问法）
        val turn = args.optInt("turn", 0).takeIf { it > 0 }
            ?: SessionDump.latestToolTurn(records)
            ?: return "这个会话里还没有调用过任何工具。"
        return SessionDump.dumpTrace(records, turn)
    }
}
