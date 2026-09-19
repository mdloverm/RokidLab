package com.rokidlab.phone.ai

import android.content.Context
import android.util.Log
import com.rokidlab.phone.ai.compaction.CompactionPolicy
import com.rokidlab.phone.ai.compaction.CompactionResult
import com.rokidlab.phone.ai.compaction.CompactionTrigger
import com.rokidlab.phone.ai.llm.ModelCapabilities
import com.rokidlab.phone.ai.session.AgentSessionStore
import com.rokidlab.phone.ai.session.AgentTurn
import com.rokidlab.phone.ai.session.MessageSource
import com.rokidlab.phone.ai.session.SessionLog
import com.rokidlab.phone.ai.session.SessionRecord

/**
 * AI Agent 会话记忆管理器（**会话级**，事件流驱动）。
 *
 * 维护跨请求的多轮对话历史（user/assistant 文本），使 AI 具备上下文记忆。
 *
 * ★ 改造前的三个真机症状与它们的根因（本次改造逐个消掉）：
 *  1. 「切到另一个会话，AI 还记得上一个会话聊了什么」—— 记忆是一个**全局单例**，不绑会话；
 *  2. 「重启之后聊天记录都在，AI 却一无所知」—— 记忆**只在内存里**，UI 那份落盘的是另一条线；
 *  3. 「上一轮到底调了什么工具、返回了什么」—— 工具调用只在压缩预算里当字符串用，查不到。
 *  根因是同一个：**没有一条权威的、只追加的时间线**。
 *
 * ★ 现在：会话记忆 = `agent_sessions/<sessionId>.jsonl` 的事件流（[SessionLog]），
 *   "模型看到的 messages"是它的**投影**（`SessionProjection`），本对象只负责三件事 ——
 *  开关持久化、**当前绑定哪个会话**、以及日志包装。历史裁剪/过期/压缩的语义在
 *   [AgentSessionStore]（纯 JVM，可单测）。
 *
 * 会话绑定由 [com.rokidlab.phone.store.ChatStateHolder] 驱动：bootstrap / 新建 / 切换会话
 * 后各调一次 [bindSession]；删除会话时调 [forgetSession] 把事件流一并删掉。
 *
 * 不记录的场景：拍照答题（`recordHistory = false`，一次性问答且注入答题指令）、
 * 定时自主任务（同样传 false，不挤占用户主对话的上下文）。
 */
internal object AgentSessionManager {
    private const val TAG = "AgentSession"
    private const val SESSION_PREFS = "agent_session_prefs"
    private const val KEY_ENABLED = "session_memory_enabled"

    @Volatile
    private var appContext: Context? = null

    /** 当前绑定会话的事件流读写门面；null = 尚未绑定（启动早期）或记忆被关闭 */
    @Volatile
    private var store: AgentSessionStore? = null

    /** 当前绑定的会话 id（空串 = 未绑定） */
    @Volatile
    private var boundSessionId: String = ""

    /**
     * 应用启动时注入上下文（`LabApplication.onCreate`，**必须在 ChatStateHolder 之前** ——
     * 后者的 bootstrap 会在读盘完成后调 [bindSession]）。
     */
    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * 把会话记忆切到 [sessionId]（由 `ChatStateHolder` 在 bootstrap / 新建 / 切换后调用）。
     *
     * 只换绑定，**不销毁**上一个会话的数据 —— 它还在自己的 jsonl 里，切回去就回来了。
     */
    fun bindSession(sessionId: String) {
        if (sessionId == boundSessionId && store != null) return
        val ctx = appContext
        if (sessionId.isBlank()) {
            store = null
            boundSessionId = ""
            return
        }
        if (ctx == null) {
            // init 还没跑：记下 id，等真正需要写的时候再懒绑定（见 storeOrNull）
            boundSessionId = sessionId
            Log.w(TAG, "bindSession($sessionId) before init: deferred")
            return
        }
        boundSessionId = sessionId
        store = newStore(ctx, sessionId)
        Log.i(TAG, "bind session=$sessionId")
    }

    /**
     * 当前绑定的会话 id（空串 = 未绑定）。
     *
     * 供会话查询工具族回答"哪个是当前会话"—— 模型列出历史会话时得能看出"我们现在在哪个里面"，
     * 否则它会把自己所在的会话也当成"过去的会话"去读一遍。
     */
    fun currentSessionId(): String = boundSessionId

    /**
     * 删除某个会话的事件流（会话被删除时调用）。
     *
     * 这是**真删**（与 [clear] 的区别见 [AgentSessionStore.wipe]）：会话都没了，轨迹留着没有意义，
     * 而且用户删除会话的意图就是"不要了"。
     */
    fun forgetSession(context: Context, sessionId: String) {
        runCatching { SessionLog.fileFor(context.filesDir, sessionId).delete() }
            .onFailure { Log.w(TAG, "delete session log failed: $sessionId", it) }
        if (sessionId == boundSessionId) {
            store = null
            boundSessionId = ""
        }
    }

    private fun newStore(ctx: Context, sessionId: String): AgentSessionStore =
        AgentSessionStore(
            SessionLog(SessionLog.fileFor(ctx.filesDir, sessionId)),
            onTrim = { Log.d(TAG, it) },
        )

    /** 当前门面；`init` 之后才第一次需要写时才懒绑定（覆盖 bindSession 早于 init 的极早期路径） */
    private fun storeOrNull(): AgentSessionStore? {
        val s = store
        if (s != null) return s
        val ctx = appContext ?: return null
        val id = boundSessionId
        if (id.isBlank()) return null
        return newStore(ctx, id).also { store = it }
    }

    /** 会话记忆总开关（关闭后不注入历史也不记录本轮） */
    fun isEnabled(context: Context): Boolean {
        return context.getSharedPreferences(SESSION_PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, true)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(SESSION_PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (!enabled) {
            // 关闭时立即清空已存历史
            clear()
        }
        Log.i(TAG, "set enabled=$enabled")
    }

    /**
     * 开始一轮对话，返回本轮的事件记录句柄；`null` = 不记录（记忆关闭 / 会话未绑定）。
     *
     * 句柄抓住了**此刻绑定的会话**，因此生成途中切会话不会把这一轮写进别的会话。
     */
    fun beginTurn(source: MessageSource): AgentTurn? {
        val s = storeOrNull() ?: return null
        return AgentTurn(s, s.beginTurn(source), source)
    }

    /** 取当前历史快照（诊断用；请求装配请走 [AgentTurn.historyForRequest]） */
    fun getHistory(): List<ChatMessage> = storeOrNull()?.history().orEmpty()

    /**
     * 当前会话已分配的最大轮号（0 = 还没有任何一轮）。
     *
     * UI 侧用它做「这一轮到底有没有进事件流」的判据：不是每一轮都会记录
     * （拍照答题 / 定时自主任务传 `recordHistory = false`），而界面收尾时只拿得到
     * "最近一轮"的用量 —— 不加这道闸门，这类回复会被贴上**上一轮**的数字。
     * 用法：派发前记下轮号，收尾时轮号没涨 ⇒ 显示"用量未知"（别编数字）。
     */
    fun currentTurn(): Int = runCatching { storeOrNull()?.maxTurn() ?: 0 }.getOrDefault(0)

    /** 超时自动清空（每轮对话开始时调用） */
    fun maybeExpire() {
        if (storeOrNull()?.maybeExpire() == true) {
            Log.i(TAG, "session expired (10min idle), cleared")
        }
    }

    /** 手动清空当前会话记忆（用户点「清空对话」→ 真删；见 [AgentSessionStore.wipe]） */
    fun clear() {
        storeOrNull()?.wipe()
        Log.i(TAG, "cleared")
    }

    /** 丢弃最后一轮对话上下文（用户点「重新生成 / 编辑重发」时调用） */
    fun dropLastTurn() {
        storeOrNull()?.dropLastTurn()
        Log.i(TAG, "dropLastTurn")
    }

    /**
     * 按当前模型的能力**修正压缩预算**（每轮对话开始前调用一次）。
     *
     * 这是 `llm` 接缝给 `compaction` 接缝的直接输入：`ModelCapabilities.contextWindow` 是
     * 改造前完全拿不到的真实数字，而"历史预算"正是该由它推导的量。规则见
     * [CompactionPolicy.forWindow] —— **只收紧不放宽**，所以大窗口模型（以及窗口未知时）
     * 行为与改造前逐字一致，只有"历史上限明显超出小窗口"才会主动收紧（修的是溢出 bug）。
     */
    internal fun applyModelCapabilities(caps: ModelCapabilities) {
        val s = storeOrNull() ?: return
        val next = CompactionPolicy.forWindow(caps.contextWindow)
        if (!s.applyPolicy(next)) return
        Log.i(
            TAG,
            "compaction policy adjusted by model window: ctx=${caps.contextWindow} " +
                "(src=${caps.source}) -> maxChars=${next.maxChars} maxMessages=${next.maxMessages} " +
                "digestMax=${next.digestMaxChars}",
        )
    }

    /**
     * 强制压缩当前历史（`compaction` 接缝的 `MANUAL` / `CONTEXT_OVERFLOW` 入口）。
     *
     * @return 未发生压缩（历史已经足够短）时返回 null
     */
    internal fun compactNow(trigger: CompactionTrigger = CompactionTrigger.MANUAL): CompactionResult? {
        val r = storeOrNull()?.compactNow(trigger) ?: return null
        Log.i(TAG, "compactNow: $r")
        return r
    }

    /** 压缩策略快照（日志/诊断用） */
    internal fun policy(): CompactionPolicy = storeOrNull()?.policy ?: CompactionPolicy.DEFAULT

    /**
     * 当前会话的**结构化轨迹**（方案 §4.3.5 的轨迹视图数据源）。
     *
     * 与 [SessionDump]（给模型读的连贯文本）同源不同形状：这里保留条目结构，
     * 界面才能按来源筛选、逐条展开看原文。
     */
    fun trajectory(limit: Int = 300): List<com.rokidlab.phone.ai.session.SessionTrace.Item> =
        storeOrNull()?.traceItems(limit).orEmpty()

    /** 轨迹规模摘要（顶部一行）；未绑定会话时返回 null */
    fun trajectorySummary(): com.rokidlab.phone.ai.session.SessionTrace.Summary? =
        storeOrNull()?.traceSummary()

    /**
     * 某个会话的**节点连接图**（会话记录视图的数据源）。
     *
     * ★ 为什么可以传一个**不是当前绑定**的会话 id：会话列表里每个会话都能查看自己的记录，
     *   而"打开另一个会话看看它发生过什么"不该要求先切过去（切会话会打断正在进行的对话、
     *   也会改变模型的记忆上下文）。所以这里直接按 id 读文件，**不碰绑定状态**。
     */
    fun graphOf(context: Context, sessionId: String): com.rokidlab.phone.ai.session.SessionGraph.Graph =
        com.rokidlab.phone.ai.session.SessionGraph.build(recordsOf(context, sessionId))

    /** 某个会话的 Markdown 导出（「导出记忆为 md」） */
    fun markdownOf(
        context: Context,
        sessionId: String,
        title: String,
        exportedAt: Long = System.currentTimeMillis(),
    ): String = com.rokidlab.phone.ai.session.SessionGraph.toMarkdown(
        records = recordsOf(context, sessionId),
        title = title,
        exportedAt = exportedAt,
    )

    /** 某个会话的全部事件记录（按 seq 升序；读取失败返回空列表，不让视图崩） */
    fun recordsOf(context: Context, sessionId: String): List<SessionRecord> {
        if (sessionId.isBlank()) return emptyList()
        return runCatching {
            SessionLog(SessionLog.fileFor(context.filesDir, sessionId)).read()
        }.getOrElse {
            Log.w(TAG, "read session records failed: $sessionId", it)
            emptyList()
        }
    }

    /**
     * 当前上下文占用快照（乐奇聊天输入框上方进度条的数据源）。
     *
     * 会话记忆关闭时 [ContextUsage.enabled] 为 false，UI 直接不显示 —— 关掉记忆就
     * 没有"上下文会被裁剪"这回事，显示一个永远 0% 的进度条只会造成困惑。
     */
    fun contextUsage(context: Context): ContextUsage {
        val s = storeOrNull()
        val policy = s?.policy ?: CompactionPolicy.DEFAULT
        val history = s?.history().orEmpty()
        val last = s?.lastTurnStat()
        val totals = s?.usageTotals()
        return ContextUsage(
            messages = history.size,
            messageLimit = policy.maxMessages,
            chars = history.sumOf { it.content.length },
            charLimit = policy.maxChars,
            compressed = s?.hasDigest() ?: false,
            enabled = isEnabled(context),
            windowLimited = policy.windowAdjusted,
            lastTurn = last?.let {
                ContextUsage.TurnSnapshot(
                    turn = it.turn,
                    inputTokens = it.promptTokens,
                    outputTokens = it.completionTokens,
                    outputChars = it.replyChars,
                    modelCalls = it.modelCalls,
                    elapsedMs = it.elapsedMs,
                )
            },
            sessionTotals = totals?.let {
                ContextUsage.SessionTotals(it.promptTokens, it.completionTokens, it.turnsWithUsage)
            },
        )
    }
}

/**
 * 上下文占用快照（UI 用）。
 *
 * [ratio] 取「条数占比」与「字符占比」的较大者：两个预算是"或"的关系 ——
 * 任一项触顶都会触发裁剪（见 `BasicCompactionEngine.compactIfNeeded`），
 * 只报其中一项，会在另一项先触顶时让用户毫无预警。
 *
 * @param windowLimited 预算是否已被**模型窗口**调整过（不再是默认的 6000 字符 / 12 条）。
 *   必须让用户看得见：否则他会发现"上限怎么变成 983 字符了"（或"怎么变成 3 万了"）却找不到原因 ——
 *   前者是切到小窗口模型（本地 Ollama `num_ctx`）的正常表现，后者是切到大窗口模型后
 *   **主动放宽**的结果，两种都不是功能坏了。名字保留 `windowLimited`（调用点太多），
 *   语义是"被窗口改过"，方向由 `widenedFromDefault` / `tightenedFromDefault` 区分。
 */
data class ContextUsage(
    val messages: Int,
    val messageLimit: Int,
    val chars: Int,
    val charLimit: Int,
    /** 是否已产生置顶滚动摘要（= 早期对话已被压缩） */
    val compressed: Boolean,
    /** 会话记忆是否开启；false 时 UI 不展示 */
    val enabled: Boolean,
    /** 预算由模型上下文窗口推导收紧 */
    val windowLimited: Boolean = false,
    /** 最近一轮的用量与耗时（成本可观测）；null = 这个会话还没有任何一轮 */
    val lastTurn: TurnSnapshot? = null,
    /** 全会话累计用量；null = 服务端从未返回过 usage（不是 0） */
    val sessionTotals: SessionTotals? = null,
) {
    /**
     * 最近一轮的运行快照。
     *
     * ⚠️ [inputTokens] 为 null 的含义是"**服务端没返回用量**"，不是 0 —— UI 必须据此
     * 换成"约 N 字"的口径并标注估算，不能让用户把估算值当成账单（见 [estimated]）。
     */
    data class TurnSnapshot(
        val turn: Int,
        val inputTokens: Int?,
        val outputTokens: Int?,
        /** 这一轮回复的字符数（拿不到 token 时的估算口径） */
        val outputChars: Int,
        /** 本轮模型调用次数（>1 = 走了工具循环，用量偏高时的第一解释） */
        val modelCalls: Int?,
        val elapsedMs: Long?,
    ) {
        /** 服务端没返回真实用量 ⇒ 展示时必须标注为估算 */
        val estimated: Boolean get() = inputTokens == null
    }

    /**
     * 会话累计用量。
     *
     * @param turnsWithUsage 贡献了数字的轮数 —— 与累计值**必须一起展示**：否则"累计 1.2 万"
     *   会被读成"整段对话只花了这么多"，而它可能只来自 20 轮里的 2 轮
     */
    data class SessionTotals(
        val inputTokens: Int,
        val outputTokens: Int,
        val turnsWithUsage: Int,
    ) {
        val total: Int get() = inputTokens + outputTokens
    }

    /** 占用比例 0f..1f */
    val ratio: Float
        get() {
            if (!enabled) return 0f
            val byCount = if (messageLimit > 0) messages.toFloat() / messageLimit else 0f
            val byChars = if (charLimit > 0) chars.toFloat() / charLimit else 0f
            return maxOf(byCount, byChars).coerceIn(0f, 1f)
        }
}
