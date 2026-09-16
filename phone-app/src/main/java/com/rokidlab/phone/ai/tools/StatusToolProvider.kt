package com.rokidlab.phone.ai.tools

import android.content.Context
import com.rokidlab.phone.ai.AgentSessionManager
import com.rokidlab.phone.ai.AgentTaskStore
import com.rokidlab.phone.ai.KnowledgeBase
import com.rokidlab.phone.ai.LongTermMemoryManager
import com.rokidlab.phone.ai.SkillRegistry
import com.rokidlab.phone.ai.ToolGateway
import com.rokidlab.phone.ai.ToolRegistry
import com.rokidlab.phone.app.LabApplication
import com.rokidlab.phone.store.ChatMsg
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

    /** 检索单元：一「轮」= 用户提问 + 紧随其后的 AI 回答 */
    private class Turn(val time: String, val question: String, val answer: String)

    /**
     * 在乐奇聊天的落盘历史里按关键字检索相关轮次。
     *
     * **容量口径**（已与用户确认）：只检索最近 [MAX_HISTORY] 条落盘记录，不做按月归档 ——
     * 落盘文件本身由 [ChatStateHolder] 的加载逻辑裁剪到同样上限，更早的对话已经不在盘上。
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
        val history = runCatching { ChatStateHolder.readPersistedHistory(context) }
            .getOrElse { e -> return "读取聊天历史失败：${e.message}" }
        if (history.isEmpty()) return "还没有可检索的历史对话。"
        val turns = history.toTurns()
        if (turns.isEmpty()) return "历史里没有可检索的对话内容（只检索最近 $MAX_HISTORY 条）。"

        if (query.isEmpty() || query == "*") {
            val recent = turns.takeLast(limit)
            return "最近的 ${recent.size} 轮对话（共 ${turns.size} 轮历史）：\n" +
                recent.reversed().mapIndexed { i, t -> renderTurn(t, i + 1, 0.0) }.joinToString("\n")
        }

        val qTokens = tokens(query)
        if (qTokens.isEmpty()) return "请提供更有意义的检索关键词（至少 2 个字符）。"
        val scored = turns
            .map { it to score(qTokens, query, it) }
            .filter { it.second > 0.0 }
            .sortedByDescending { it.second }
            .take(limit)
        if (scored.isEmpty()) {
            return "没找到和「$query」相关的历史对话（只检索最近 $MAX_HISTORY 条记录）。" +
                "可以换更短的关键词再试，或先用不带关键词的检索看看最近聊过什么。"
        }
        return "找到 ${scored.size} 条相关历史对话（按相关度排序）：\n" +
            scored.mapIndexed { i, (t, s) -> renderTurn(t, i + 1, s) }.joinToString("\n")
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
}
