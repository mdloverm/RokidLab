package com.rokidlab.phone.ai.subagent

import android.content.Context
import android.util.Log
import com.rokidlab.phone.ai.ToolCallInfo
import com.rokidlab.phone.ai.ToolRegistry
import com.rokidlab.phone.ai.approval.ApprovalGate
import com.rokidlab.phone.ai.approval.ToolDecision
import com.rokidlab.phone.ai.approval.ToolSource
import com.rokidlab.phone.ai.llm.LlmRegistry
import com.rokidlab.phone.app.LabApplication
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 只读子代理（方案 §4.3.2）：把"查资料并给结论"这类多轮调研**派出去做**，
 * 主循环只收结论，把自己的轮次预算留给主线任务。
 *
 * ## 为什么只做只读
 *
 * 子代理是主循环里一次**同步阻塞**的嵌套调用 —— 它的每一次工具调用都不经过用户眼前的
 * 过程时间线（用户只看到"正在派子任务查资料…"）。在这个前提下给它副作用工具，
 * 等于让一个**看不见的**循环去拨号/装机/改设置，而这正是整个审批链要防的事。
 * 因此它的工具集来自 `ToolRegistry.schemasReadOnly()`（风险档 READ_ONLY），
 * 并且**摘掉它自己**（深度恒为 1，不允许子任务再派子任务）。
 *
 * ## 三条硬约束（与 DSH 的差异都写在这里）
 *
 * DSH 在桌面上有 6 种 subagent provider，手机上不成立 —— 每一条都对应一个真实代价：
 *  1. **同一时间只允许 1 个**：并发子代理 = 并发模型请求（token 翻倍 + 限流更容易触顶 +
 *     真机上几乎无法排查"这两个结论分别是谁给的"）；
 *  2. **墙上时钟硬上限**（[MAX_WALL_MS]）：主循环在等它，一次调研把用户晾 5 分钟
 *     比"只查了一半"更糟。超时就带着已有结论返回，并如实说明；
 *  3. **本地模型直接拒绝**：本地轻量会话连主 Agent 的工具都不装配（小模型背不动
 *     Schema、调用不可靠），给子代理装配一套只读工具只会更糟。
 *
 * ⚠️ **刻意不做**：子代理内部的事件流记录。它没有轮次语义（不是用户的一轮），
 * 硬塞进会话事件流会污染 `turn` 编号与投影；而它的**结论**会作为 `research_subtask`
 * 的 ToolResult 被正常记录（含来源），轨迹视图里看得到"派过、拿到了什么"。
 * 内部每一轮查了什么，目前只进 logcat。
 */
internal object ReadOnlySubagent {

    private const val TAG = "ReadOnlySubagent"

    /** 工具名（模型可见的 function name） */
    const val TOOL_NAME = "research_subtask"

    private const val MAX_ROUNDS_DEFAULT = 4
    private const val MAX_ROUNDS_CAP = 6

    /** 墙上时钟硬上限：超过就带着已有结论收工（主循环在等它，不能无限拖） */
    private const val MAX_WALL_MS = 90_000L

    /** 回填给主 Agent 的结论上限（它只是"结论+来源"，不需要把资料原文搬过去） */
    private const val MAX_ANSWER_CHARS = 3000

    /** 工具返回回填进子代理上下文的单条上限（同主循环的截断意图：别让一篇网页吃光窗口） */
    private const val TOOL_OUTPUT_MAX = 6000

    /** 并发闸门：同时只允许 1 个子任务 */
    private val busy = AtomicBoolean(false)

    /**
     * 跑一个只读调研子任务。
     *
     * @param rounds 最多几轮工具调用（≤0 用默认值，上限 [MAX_ROUNDS_CAP]）
     * @return 给主 Agent 的结论文本（**永远返回可读文本，不抛异常** ——
     *   它是工具回填，抛出去会让主循环把整轮判失败）
     */
    fun run(context: Context, question: String, rounds: Int): String {
        val q = question.trim()
        if (q.isEmpty()) return "子任务没说要查什么（question 不能为空）。请写清要回答的问题。"
        if (!busy.compareAndSet(false, true)) {
            return "已经有一个子任务在跑了（同一时间只允许 1 个）。等它返回结论后再派下一个，或者你自己直接查。"
        }
        val maxRounds = (if (rounds > 0) rounds else MAX_ROUNDS_DEFAULT).coerceIn(1, MAX_ROUNDS_CAP)
        return try {
            runInner(context, q, maxRounds)
        } catch (e: Exception) {
            Log.w(TAG, "subtask failed: ${e.message}", e)
            "子任务执行失败（${e.message}）。可以换个更具体的问法重试，或者你自己用现有工具直接查。"
        } finally {
            busy.set(false)
        }
    }

    private fun runInner(context: Context, question: String, maxRounds: Int): String {
        val app = context.applicationContext as? LabApplication
        val cfg = if (app?.hasCxrL() == true) app.cxrL.getAiConfig() else null
            ?: return "读不到当前的模型配置，没法起子任务。请让用户检查「乐奇聊天 → AI 设置」里的模型配置。"

        // 本地小模型不装配工具（与主会话 SESSION_LOCAL_DOMAINS 的取舍一致）；
        // 给子代理一套只读 Schema 只会让它更不可靠
        if (LlmRegistry.isLocalBase(cfg.baseUrl)) {
            return "当前用的是本地模型（轻量模式，不接工具），没法起子任务查资料。请用户切到在线模型，或者他自己直接问、我直接答。"
        }

        val localOnly = app.chatLocalOnlyEnabled
        val service = LlmRegistry.newService(cfg, LlmRegistry.Profile.SUBAGENT)
        // 只读工具 + 摘掉自己（深度恒为 1）
        val tools = ToolRegistry.schemasReadOnly(context, excludeGlassesTools = localOnly)
            .filterNot { functionNameOf(it) == TOOL_NAME }

        val messages = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "system")
                put("content", systemPrompt(maxRounds))
            })
            put(JSONObject().apply {
                put("role", "user")
                // 用户原话不带给它：子代理只需要"要回答什么"，带上原话会让它去处理
                // 跟问题无关的寒暄、指代和情绪（主 Agent 的职责就是把问题问清楚）
                put("content", question)
            })
        }

        val deadline = System.currentTimeMillis() + MAX_WALL_MS
        var best: String? = null
        var roundsUsed = 0
        for (round in 0 until maxRounds) {
            if (System.currentTimeMillis() > deadline) {
                Log.w(TAG, "subtask wall-clock deadline hit at round=$round")
                return withNote(best, "（子任务到达时间上限，只查到这些）")
            }
            val turn = service.chatTurn(messages, tools = tools, attempts = 1)
            roundsUsed = round + 1
            turn.content?.takeIf { it.isNotBlank() }?.let { best = it }
            if (turn.toolCalls.isEmpty()) {
                // 没有工具调用 = 它给出了结论
                return withNote(best, null)
            }
            messages.put(assistantEcho(turn))
            for (tc in turn.toolCalls) {
                messages.put(JSONObject().apply {
                    put("role", "tool")
                    put("tool_call_id", tc.id)
                    put("content", executeReadOnly(context, tc, localOnly))
                })
            }
        }
        Log.i(TAG, "subtask finished after $roundsUsed/$maxRounds rounds")
        return withNote(best, "（子任务用完了 $maxRounds 轮工具预算，以下是已查到的）")
    }

    /**
     * 子代理的工具调用走**同一条审批链**（而不是绕开）。
     *
     * 两个理由：① 限流配额是全局的 —— 绕开就等于给了一条"派子任务刷工具"的旁路；
     * ② 策略只有一处，将来收窄工具集时子代理自动跟着变，不需要另记一笔。
     * 它的工具集本来就是只读的，所以这里不会出现"Ask"。
     */
    private fun executeReadOnly(context: Context, tc: ToolCallInfo, localOnly: Boolean): String {
        val args = runCatching { JSONObject(tc.arguments) }.getOrNull() ?: JSONObject()
        val decision = ApprovalGate.preExecute(ToolSource.CONVERSATION, tc.name, args, localOnly = localOnly)
        if (decision is ToolDecision.Deny) {
            return "工具 ${tc.name} 被策略拦截：${decision.reason}。请换一种查法。"
        }
        val out = runCatching { ToolRegistry.execute(context, tc.name, tc.arguments) }
            .getOrElse { e -> "工具执行失败：${e.message}" }
        return if (out.length > TOOL_OUTPUT_MAX) out.take(TOOL_OUTPUT_MAX) + "…（已截断）" else out
    }

    private fun systemPrompt(maxRounds: Int): String = buildString {
        append("你是乐奇派出的**只读调研子助手**。主助手把一个具体问题交给你，你负责查资料并给出结论。")
        append("\n\n【硬约束】")
        append("\n- 你只有**只读**工具（联网搜索/读网页/查知识库/查历史会话/查设备状态）。")
        append("你**不能**改任何状态：不能拨号、装机、改设置、写文件。也不要尝试。")
        append("\n- 最多 $maxRounds 轮工具调用，请把力气花在最能回答问题的资料上；查够了就给结论，不要为了凑轮次再查。")
        append("\n\n【输出要求】")
        append("\n- 先给结论（1~3 句话直接回答），再列依据。")
        append("\n- 每条依据都要注明来源：网页给链接，知识库给文档名；资料里带了抓取/检索时间的要保留。")
        append("\n- 查不到就明确说「没查到」，不要编。资料之间冲突时，把冲突如实说出来。")
        append("\n- 只输出给主助手看的内容：不要寒暄、不要复述问题、不要写「我这就去查」这类过程话。")
    }

    /** 回填 assistant 消息（OpenAI 协议要求 tool_calls 原样带上，否则下一轮 400） */
    private fun assistantEcho(turn: com.rokidlab.phone.ai.ChatTurn): JSONObject =
        JSONObject().apply {
            put("role", "assistant")
            put("content", JSONObject.NULL)
            put("tool_calls", JSONArray().apply {
                turn.toolCalls.forEach { tc ->
                    put(JSONObject().apply {
                        put("id", tc.id)
                        put("type", "function")
                        put("function", JSONObject().apply {
                            put("name", tc.name)
                            put("arguments", tc.arguments)
                        })
                    })
                }
            })
        }

    /** 结论 + 一句如实说明（超时/预算用尽时） */
    private fun withNote(answer: String?, note: String?): String {
        val body = answer?.trim().orEmpty()
        if (note == null) {
            return if (body.isEmpty()) "（子任务没有给出结论，也没有查到可用资料）" else clip(body)
        }
        return if (body.isEmpty()) "（子任务没有给出结论）$note" else clip(body) + "\n\n$note"
    }

    private fun clip(s: String): String =
        if (s.length > MAX_ANSWER_CHARS) s.take(MAX_ANSWER_CHARS) + "…（已截断）" else s

    /** 从工具 Schema 里取 function name（不依赖 ToolRegistry 内部实现） */
    private fun functionNameOf(schema: JSONObject): String =
        schema.optJSONObject("function")?.optString("name").orEmpty()
}
