package com.rokidlab.phone.store

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.rokidlab.phone.ai.AgentSessionManager
import com.rokidlab.phone.ai.AgentStep
import com.rokidlab.phone.util.LogCollector
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "ChatStateHolder"
private const val STATE_PREFS = "chat_state_prefs"
private const val KEY_ACTIVE_SESSION = "active_session_id"

/**
 * 聊天消息状态全局持有器（多会话）。
 *
 * 解决切到其他功能页面后聊天对话被清空的问题：原实现用 [ChatScreen] 内的
 * `remember { mutableStateOf(listOf<ChatMsg>()) }`，离开组合即销毁。
 * 改为单例持有 [messages]（当前会话），Compose 跨页面共享，状态持久。
 *
 * **多会话**（2026-09-19 新增）：
 *   - [sessions] 为会话索引（按最近更新倒序），[currentSessionId] 指向当前会话
 *   - 每个会话的消息落在 `chat_sessions/<id>.jsonl`（格式见 [ChatHistoryStore]）
 *   - 切会话只换内容、**不换 [messages] 实例** —— Compose 侧持有的是同一个
 *     `SnapshotStateList`，原地 clear + addAll 才能正确触发重组
 *   - 旧版单文件 `chat_history.json` 在 [init] 时迁成第一个会话后删除
 *
 * 另支持落盘持久化（App 重启不丢失）与流式增量更新（AI 回复边生成边显示）：
 *   - [init] 在 Application.onCreate 调用，**后台**加载索引与当前会话
 *   - [add]/[addImage]/[finalizeLastAi] 写入后追加一行 JSONL（增量，不重写整份文件）
 *   - [deleteMessage]/[truncateFrom]/[replaceMessage] 是结构性变更，整体重写该会话文件
 *     （单会话上限 [MAX_HISTORY] 条，重写开销可忽略）
 *   - [appendAiDelta] 流式增量累积（不落盘，高频），[finalizeLastAi] 完成后统一持久化
 *
 * **线程模型**（格式与回放逻辑见 [ChatHistoryStore] / [ChatSessionStore]）：
 *   - `SnapshotStateList` 只在调用线程（Compose 主线程）读写，**绝不**在后台线程触碰；
 *   - 所有文件 I/O 交给单线程 [writer]，按提交顺序串行执行 —— 既避免阻塞主线程，
 *     也避免并发写坏文件；
 *   - 序列化在调用线程完成后再把字符串交给 [writer]，因此后台任务只碰普通字符串。
 *   - 例外：[upsertTrace] / [finishTrace] / [finalizeTraceReply] 可**从任意线程**调用 ——
 *     它们由 AI 生成线程 / ASR 轮询线程直接触发，内部用 [runOnMain] 自行切主线程。
 */
internal object ChatStateHolder {
    /** 当前会话的消息列表（Compose 可观察） */
    val messages: SnapshotStateList<ChatMsg> = mutableStateListOf()

    /** 会话索引（Compose 可观察，按最近更新倒序） */
    val sessions: SnapshotStateList<ChatSessionMeta> = mutableStateListOf()

    /**
     * 当前会话 id；未初始化时为空串。
     *
     * 用 Compose 可变状态而非普通字段：标题栏要显示当前会话名，而会话标题由首条用户消息
     * **异步派生**（见 [touchSession]）—— 普通字段变了不会触发重组，标题会一直停在「新对话」。
     * 只在主线程读写。
     */
    var currentSessionId: String by mutableStateOf("")
        private set

    /** 自增消息 id，避免 LazyColumn key 冲突（原子操作，防并发重复 key） */
    private val msgIdCounter = AtomicLong(0L)

    @Volatile
    private var appContext: Context? = null

    /** 会话切换代际：快速连点切会话时，只让最后一次的异步加载结果落地 */
    private var loadGen = 0

    /** 单线程落盘器：daemon 线程，不阻止 JVM 退出 */
    private val writer: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "chat-history-writer").apply { isDaemon = true }
    }

    /** 后台读取完成后把结果投回主线程（SnapshotStateList 只能在主线程写） */
    private val mainHandler: Handler by lazy { Handler(Looper.getMainLooper()) }

    // ===== 路径 =====

    private fun indexFile(ctx: Context): File = File(ctx.filesDir, ChatSessionStore.INDEX_FILE)

    private fun sessionFile(ctx: Context, id: String): File =
        File(File(ctx.filesDir, ChatSessionStore.SESSION_DIR), "$id.jsonl")

    private fun newSessionId(): String =
        UUID.randomUUID().toString().replace("-", "").take(12)

    // ===== 初始化与迁移 =====

    /** 应用启动时初始化上下文，并在后台加载会话索引与当前会话消息 */
    fun init(context: Context) {
        appContext = context.applicationContext
        bootstrap()
    }

    /**
     * 后台引导：读索引 → 必要时迁移旧单文件历史 → 选定当前会话 → 读该会话消息。
     *
     * 迁移只在「索引为空」时发生，因此正常启动不会重复扫描旧文件；
     * 旧文件在新会话文件写成功之后才删除 —— 中途失败最多留下一个孤儿文件，
     * 不会出现"两边都没有"的历史丢失。
     */
    private fun bootstrap() {
        val ctx = appContext ?: return
        submit("bootstrap") {
            val idx = indexFile(ctx)
            var list = ChatSessionStore.parseIndex(if (idx.exists()) idx.readText() else "")
            var active = ctx.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)
                .getString(KEY_ACTIVE_SESSION, "").orEmpty()

            if (list.isEmpty()) {
                val id = newSessionId()
                val now = System.currentTimeMillis()
                val legacy = File(ctx.filesDir, ChatSessionStore.LEGACY_FILE)
                val migrated = if (legacy.exists()) {
                    runCatching { ChatHistoryStore.readHistory(legacy) }.getOrDefault(emptyList())
                } else {
                    emptyList()
                }
                if (migrated.isNotEmpty()) {
                    ChatHistoryStore.rewrite(
                        sessionFile(ctx, id),
                        migrated.map { ChatHistoryStore.toLine(it) },
                    )
                    legacy.delete()
                    LogCollector.i(TAG, "migrated ${migrated.size} legacy messages into session $id")
                }
                list = listOf(
                    ChatSessionMeta(
                        id = id,
                        title = ChatSessionStore.titleFrom(migrated),
                        createdAt = now,
                        updatedAt = now,
                        preview = ChatSessionStore.previewFrom(migrated),
                        messageCount = migrated.size,
                    )
                )
                active = id
                idx.writeText(ChatSessionStore.toIndexJson(list))
                setActiveIdPref(ctx, id)
            }

            val ordered = ChatSessionStore.sorted(list)
            if (ordered.none { it.id == active }) {
                active = ordered.first().id
                setActiveIdPref(ctx, active)
            }
            val loaded = ChatHistoryStore.readHistory(sessionFile(ctx, active))
            mainHandler.post { applyBootstrap(ordered, active, loaded) }
        }
    }

    /** 主线程：把后台读到的索引与消息并入可观察状态 */
    private fun applyBootstrap(
        ordered: List<ChatSessionMeta>,
        activeId: String,
        loaded: List<ChatMsg>,
    ) {
        currentSessionId = activeId
        sessions.clear()
        sessions.addAll(ordered)
        messages.clear()
        messages.addAll(loaded)
        resetIdCounter(loaded)
        // Agent 会话记忆（事件流）绑定到同一个会话 —— UI 与 AI 看的是同一条时间线
        AgentSessionManager.bindSession(activeId)
        LogCollector.i(TAG, "bootstrap: session=$activeId, sessions=${ordered.size}, messages=${loaded.size}")
    }

    // ===== 会话管理 =====

    /** 新建会话并切过去（标题随后由首条用户消息派生） */
    fun newSession(): String {
        val ctx = appContext
        val id = newSessionId()
        val now = System.currentTimeMillis()
        val meta = ChatSessionMeta(
            id = id,
            title = ChatSessionStore.DEFAULT_TITLE,
            createdAt = now,
            updatedAt = now,
            preview = "",
            messageCount = 0,
        )
        sessions.add(0, meta)
        settleOrder()
        loadGen++ // 作废进行中的加载，防止旧结果覆盖空会话
        currentSessionId = id
        messages.clear()
        msgIdCounter.set(0L)
        ctx?.let { setActiveIdPref(it, id) }
        // 新会话的记忆从零开始（新的空事件流）
        AgentSessionManager.bindSession(id)
        persistIndex()
        return id
    }

    /** 切换当前会话（异步加载消息；同一 id 重复调用无效） */
    fun switchTo(id: String) {
        if (id == currentSessionId) return
        if (sessions.none { it.id == id }) return
        val ctx = appContext ?: return
        currentSessionId = id
        setActiveIdPref(ctx, id)
        // 记忆跟着切 —— 否则「切到新会话，AI 还记得上一个会话」的老问题会原样复现
        AgentSessionManager.bindSession(id)
        loadSessionMessages(id)
    }

    /** 删除会话；删到最后一个会话被删时自动补一个空会话（界面永远有会话可用） */
    fun deleteSession(id: String) {
        val idx = sessions.indexOfFirst { it.id == id }
        if (idx < 0) return
        val ctx = appContext
        sessions.removeAt(idx)
        if (ctx != null) {
            submit("delete session") { sessionFile(ctx, id).delete() }
            // 事件流一并删除：会话都没了，AI 记忆留着没有意义（也不该被"审计留痕"绑架）
            AgentSessionManager.forgetSession(ctx, id)
        }
        when {
            sessions.isEmpty() -> newSession()
            id == currentSessionId -> {
                val next = sessions[0].id
                currentSessionId = next
                ctx?.let { setActiveIdPref(it, next) }
                AgentSessionManager.bindSession(next)
                loadSessionMessages(next)
            }
        }
        persistIndex()
    }

    /** 重命名会话（不改 updatedAt，避免重命名把会话顶到列表最前） */
    fun renameSession(id: String, title: String) {
        val idx = sessions.indexOfFirst { it.id == id }
        if (idx < 0) return
        val clean = title.trim().ifBlank { ChatSessionStore.DEFAULT_TITLE }
        if (sessions[idx].title == clean) return
        sessions[idx] = sessions[idx].copy(title = clean)
        persistIndex()
    }

    /**
     * 设置某个会话的**附加提示词**（空串 = 取消）。见 [ChatSessionMeta.systemPrompt]。
     *
     * 它**不进消息列表**、不落会话消息文件，只改索引 —— 它是会话的"配置"，不是"内容"。
     */
    fun setSessionPrompt(id: String, prompt: String) {
        val idx = sessions.indexOfFirst { it.id == id }
        if (idx < 0) return
        val clean = prompt.trim()
        if (sessions[idx].systemPrompt == clean) return
        sessions[idx] = sessions[idx].copy(systemPrompt = clean)
        persistIndex()
    }

    /** 某个会话的附加提示词（空串 = 没设） */
    fun sessionPrompt(id: String): String =
        sessions.firstOrNull { it.id == id }?.systemPrompt.orEmpty()

    /**
     * **当前会话**的附加提示词；没设或为空时返回 null（调用方据此不往提示词里塞空块）。
     *
     * 取 null 而不是空串：拼提示词的那一侧要区分"没设"与"设了但是空的"，
     * 前者不该在 system 消息里留下一个空的标题行。
     */
    fun currentSessionPrompt(): String? = sessionPrompt(currentSessionId).takeIf { it.isNotBlank() }

    /** 清空当前会话的全部消息（内存 + 落盘，否则重启后历史复活） */
    fun clear() {
        val ctx = appContext ?: return
        val id = currentSessionId
        messages.clear()
        msgIdCounter.set(0L)
        submit("clear session") { ChatHistoryStore.rewrite(sessionFile(ctx, id), emptyList()) }
        touchSession()
    }

    /** 后台加载指定会话的消息（带代际校验，快速连点切会话时旧结果作废） */
    private fun loadSessionMessages(id: String) {
        val ctx = appContext ?: return
        val gen = ++loadGen
        messages.clear()
        msgIdCounter.set(0L)
        submit("load session") {
            val loaded = ChatHistoryStore.readHistory(sessionFile(ctx, id))
            mainHandler.post {
                // 期间用户又切走了：丢弃本次结果，避免把别的会话内容填进来
                if (gen != loadGen || currentSessionId != id) return@post
                messages.clear()
                messages.addAll(loaded)
                resetIdCounter(loaded)
            }
        }
    }

    // ===== 消息级操作 =====

    /**
     * 删除单条消息。
     *
     * JSONL 是追加式的，"删中间一条"没法靠追加表达（追加只能覆盖同 id），
     * 因此这里整体重写当前会话文件 —— 单会话上限 [MAX_HISTORY] 条，重写成本可忽略。
     */
    fun deleteMessage(id: Long) = runOnMain {
        val idx = messages.indexOfFirst { it.id == id }
        if (idx < 0) return@runOnMain
        messages.removeAt(idx)
        persistAll()
        touchSession()
    }

    /**
     * 删除 [id] **及其之后**的全部消息（编辑重发 / 重新生成用）。
     *
     * 语义是"回退到这条之前"：编辑重发时先删掉该用户消息之后的所有内容再重发，
     * 重新生成时删掉末尾那条 AI 回复。
     */
    fun truncateFrom(id: Long) = runOnMain {
        val idx = messages.indexOfFirst { it.id == id }
        if (idx < 0) return@runOnMain
        while (messages.size > idx) messages.removeAt(messages.size - 1)
        persistAll()
        touchSession()
    }

    /** 替换单条消息的正文（编辑重发时更新那条用户消息） */
    fun replaceMessage(id: Long, content: String) = runOnMain {
        val idx = messages.indexOfFirst { it.id == id }
        if (idx < 0) return@runOnMain
        messages[idx] = messages[idx].copy(content = content)
        persistAll()
        touchSession()
    }

    /**
     * 一个**按会话归组**的落盘历史（供跨会话检索工具使用）。
     *
     * 存在的理由就是 [readPersistedSessions] 的 ★ 注释：扁平列表丢掉的那个字段，
     * 恰恰是模型判断"这句是不是当前这次对话说的"的唯一依据。
     */
    internal data class PersistedSession(
        val id: String,
        val title: String,
        val updatedAt: Long,
        val messages: List<ChatMsg>,
    )

    /**
     * 供「跨会话检索」Agent 工具读取落盘历史（**按会话分组**）。
     *
     * ★ 本 API **取代**了原先的 `readPersistedHistory()`（已删除）。原因不是"想要个新接口"，
     *   而是旧接口的形状本身会制造 bug：它把**全部会话拼成一条扁平列表**，会话身份在那一步
     *   就丢了。消费方（`search_past_conversations`）于是把它渲染成"一次对话的最近 N 轮" ——
     *   模型看到的是一条连贯的"刚才"，而它其实是**两个会话**的内容交错。
     *   真机症状（2026-09-19 复现）：新建一个对话问「刚才聊什么了」，模型答
     *   "刚才咱聊了两件事：一是您让我记住叫您周哥，二是您问附近有什么好吃的" ——
     *   第二件事发生**在另一个会话里**，它把别的会话当成了"刚才"。
     *   ⇒ 检索要跨会话是对的，**但不能不给归属**。扁平 API 让"给归属"变成不可能，
     *     所以删的是接口本身，不是改它的调用点。
     *
     * 排序：[PersistedSession.updatedAt] 升序（新的在后）；空会话（一条消息都没有）
     * 直接跳过 —— 它们没有任何可检索内容，只会占位置。
     *
     * 只读文件，**不触碰**主线程 [messages]。可在任意后台线程调用。
     */
    internal fun readPersistedSessions(context: Context): List<PersistedSession> {
        val dir = File(context.filesDir, ChatSessionStore.SESSION_DIR)
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".jsonl") } ?: return emptyList()
        val index = runCatching {
            val f = File(context.filesDir, ChatSessionStore.INDEX_FILE)
            ChatSessionStore.parseIndex(if (f.exists()) f.readText() else "")
        }.getOrDefault(emptyList()).associateBy { it.id }

        return files.mapNotNull { f ->
            val id = f.name.removeSuffix(".jsonl")
            val msgs = runCatching { ChatHistoryStore.readHistory(f) }.getOrDefault(emptyList())
            if (msgs.isEmpty()) return@mapNotNull null
            val meta = index[id]
            PersistedSession(
                id = id,
                // 索引里没有（旧数据/索引损坏）时用首条用户消息兜底，标题不该是空的
                title = meta?.title?.takeIf { it.isNotBlank() } ?: ChatSessionStore.titleFrom(msgs),
                updatedAt = meta?.updatedAt?.takeIf { it > 0L } ?: f.lastModified(),
                messages = msgs,
            )
        }.sortedBy { it.updatedAt }
    }

    // ===== 消息写入（原有语义，落盘目标改为当前会话文件）=====

    /**
     * 添加一条消息并返回它。
     *
     * @param usage 只有 AI 回复会带（本轮成本，见 [ChatMsg.usage]）
     * @param turn 这一轮在事件流里的轮号（见 [ChatMsg.turn]；拍照答题等不进事件流的轮传 null）
     */
    fun add(
        isUser: Boolean,
        content: String,
        isStatus: Boolean = false,
        usage: MsgUsage? = null,
        turn: Int? = null,
    ): ChatMsg {
        val id = msgIdCounter.incrementAndGet()
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        val msg = ChatMsg(id, isUser, content, time, isStatus, usage = usage, turn = turn)
        messages.add(msg)
        trimIfNeeded()
        persist(msg)
        touchSession()
        return msg
    }

    /**
     * 原地更新末尾状态消息（用于 OCR 模型下载百分比等高频进度）。
     * 末尾是状态消息就覆盖内容（不新增气泡、不落盘，避免每 1% 刷一条 + 高频写文件）；
     * 末尾不是状态消息时退化为新增一条状态消息。必须在主线程调用。
     */
    fun updateLastStatus(content: String) {
        val last = messages.lastOrNull()
        if (last != null && last.isStatus) {
            messages[messages.size - 1] = last.copy(content = content)
        } else {
            add(false, content, isStatus = true)
        }
    }

    /**
     * 添加一条图片消息：content = 图片说明（caption），[imageUrl] = 远端图片直链。
     * 由 `show_image` 工具调用，必须在主线程调用（Compose 快照线程）。
     */
    fun addImage(isUser: Boolean, imageUrl: String, caption: String): ChatMsg {
        val id = msgIdCounter.incrementAndGet()
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        val msg = ChatMsg(id, isUser, caption, time, isStatus = false, imageUrl = imageUrl)
        messages.add(msg)
        trimIfNeeded()
        persist(msg)
        touchSession()
        return msg
    }

    /** 超出历史上限时裁掉最旧的消息（落盘文件由冷启动压实收敛） */
    private fun trimIfNeeded() {
        while (messages.size > MAX_HISTORY) {
            messages.removeAt(0)
        }
    }

    /**
     * Agent 过程步骤合并（思考 / 工具调用），按 [AgentStep.key] 覆盖。
     * 合并进**当前这轮** AI 消息（列表末尾那条非用户、非状态消息）的 [ChatMsg.trace]。
     * **不落盘**：过程事件频率高，统一由 [finishTrace] 或 [finalizeTraceReply] 收尾时持久化。
     * 可在任意线程调用（内部自动切主线程，见 [runOnMain]）。
     */
    fun upsertTrace(step: AgentStep) = runOnMain { upsertTraceOnMain(step) }

    private fun upsertTraceOnMain(step: AgentStep) {
        val last = messages.lastOrNull()
        if (last != null && !last.isUser && !last.isStatus) {
            val at = messages.size - 1
            val pos = last.trace.indexOfFirst { it.key == step.key }
            val merged = if (pos >= 0) {
                last.trace.toMutableList().also { it[pos] = step }
            } else {
                last.trace + step
            }
            messages[at] = last.copy(trace = merged)
            return
        }
        val id = msgIdCounter.incrementAndGet()
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        messages.add(ChatMsg(id, false, "", time, false, trace = listOf(step)))
        trimIfNeeded()
    }

    /**
     * 眼镜语音一轮的回复落地：**合并进本轮那条带「过程」的 AI 消息**，而不是另起一条。
     * 仅当**末尾那条就是本轮的过程消息**（非用户、非状态、trace 非空）才合并。
     */
    fun finalizeTraceReply(reply: String, usage: MsgUsage? = null, turn: Int? = null) =
        runOnMain { finalizeTraceReplyOnMain(reply, usage, turn) }

    private fun finalizeTraceReplyOnMain(reply: String, usage: MsgUsage?, turn: Int?) {
        val last = messages.lastOrNull()
        if (last != null && !last.isUser && !last.isStatus && last.trace.isNotEmpty()) {
            val merged = last.copy(
                content = reply,
                usage = usage ?: last.usage,
                turn = turn ?: last.turn,
                trace = last.trace.map {
                    if (it.state == AgentStep.State.RUNNING) it.copy(state = AgentStep.State.OK) else it
                },
            )
            messages[messages.size - 1] = merged
            persist(merged)
            touchSession()
            return
        }
        add(false, reply, usage = usage, turn = turn)
    }

    /**
     * 把动作切到主线程（[SnapshotStateList] 只能在 Compose 快照线程写）。
     */
    private inline fun runOnMain(crossinline block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post { block() }
    }

    /**
     * 收尾本轮过程：把仍处于 [AgentStep.State.RUNNING] 的步骤置为终态并落盘。
     *
     * @param failed true = 本轮整体失败（失败气泡场景），残留步骤标 FAILED；false = 正常收尾标 OK
     */
    fun finishTrace(failed: Boolean = false) = runOnMain { finishTraceOnMain(failed) }

    private fun finishTraceOnMain(failed: Boolean) {
        for (i in messages.size - 1 downTo 0) {
            val m = messages[i]
            if (m.isUser || m.isStatus) continue
            if (m.trace.none { it.state == AgentStep.State.RUNNING }) return
            val updated = m.copy(
                trace = m.trace.map {
                    if (it.state != AgentStep.State.RUNNING) it
                    else it.copy(state = if (failed) AgentStep.State.FAILED else AgentStep.State.OK)
                }
            )
            messages[i] = updated
            persist(updated)
            return
        }
    }

    /**
     * 流式增量累积：AI 回复边生成边显示。若尚无 AI 消息则先创建一条。
     * 不落盘（高频调用），由 [finalizeLastAi] 完成后统一持久化。
     */
    fun appendAiDelta(delta: String) {
        if (delta.isEmpty()) return
        val last = messages.lastOrNull()
        if (last != null && !last.isUser && !last.isStatus) {
            messages[messages.size - 1] = last.copy(content = last.content + delta)
        } else {
            val id = msgIdCounter.incrementAndGet()
            val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            messages.add(ChatMsg(id, false, delta, time, false))
        }
    }

    /**
     * 流式结束（或失败兜底）后，用完整回复修正最后一条 AI 消息内容并落盘。
     * 若没有 AI 消息则直接新增（兜底路径，如流式未触发直接 onReply）。
     */
    fun finalizeLastAi(fullContent: String, usage: MsgUsage? = null, turn: Int? = null) {
        for (i in messages.size - 1 downTo 0) {
            val m = messages[i]
            if (!m.isUser && !m.isStatus) {
                // 已经有了的就别抹掉：新值缺失只说明"这次没拿到"，不代表之前那条是错的
                val updated = m.copy(content = fullContent, usage = usage ?: m.usage, turn = turn ?: m.turn)
                messages[i] = updated
                persist(updated)
                touchSession()
                return
            }
        }
        add(false, fullContent, usage = usage, turn = turn)
    }

    /**
     * 给某条消息补上/改掉轮号（用于「重新生成」：它复用原来那条用户消息，
     * 但那一轮已经换成了新的轮号 —— 不更新的话记录图里这一轮的用户消息永远对不上）。
     */
    fun setMessageTurn(id: Long, turn: Int) = runOnMain {
        val idx = messages.indexOfFirst { it.id == id }
        if (idx < 0) return@runOnMain
        val updated = messages[idx].copy(turn = turn)
        messages[idx] = updated
        persist(updated)
    }

    /**
     * 按轮号找对应的 UI 消息（会话记录图里"点某条消息节点 → 编辑/删除"的映射）。
     *
     * @param isUser 用户消息还是 AI 回复（同一轮两者都有，只靠轮号分不开）
     * @return 找不到时返回 null —— 视图据此**只给复制、不给编辑/删除**，而不是猜一条改错。
     */
    fun messageOfTurn(turn: Int, isUser: Boolean): ChatMsg? =
        messages.firstOrNull { it.turn == turn && it.isUser == isUser && !it.isStatus }

    // ===== 落盘 =====

    /** 在落盘线程上执行任务，失败落日志面板（聊天历史丢失是用户可见问题，不能只写 logcat） */
    private fun submit(action: String, task: () -> Unit) {
        writer.execute {
            runCatching(task).onFailure { LogCollector.w(TAG, "$action failed", it) }
        }
    }

    /** 追加一条消息到当前会话文件（JSONL 一行）。序列化在调用线程完成，文件写在后台 */
    private fun persist(msg: ChatMsg) {
        val ctx = appContext ?: return
        val id = currentSessionId
        val line = ChatHistoryStore.toLine(msg)
        submit("persist") { ChatHistoryStore.appendLine(sessionFile(ctx, id), line) }
    }

    /** 整体重写当前会话文件（结构性变更：删除 / 截断 / 编辑 / 清空） */
    private fun persistAll() {
        val ctx = appContext ?: return
        val id = currentSessionId
        val lines = messages.map { ChatHistoryStore.toLine(it) }
        submit("persist all") { ChatHistoryStore.rewrite(sessionFile(ctx, id), lines) }
    }

    /** 写会话索引 */
    private fun persistIndex() {
        val ctx = appContext ?: return
        val snapshot = ChatSessionStore.toIndexJson(sessions.toList())
        submit("persist index") { indexFile(ctx).writeText(snapshot) }
    }

    /**
     * 当前会话内容变化后同步列表展示字段（预览 / 条数 / 更新时间）。
     *
     * 标题只在会话"从空变有"时派生一次 —— 用 `old.messageCount == 0` 而不是
     * `title == 默认标题` 判断，是为了不把用户手动改成的「新对话」覆盖掉。
     */
    private fun touchSession() {
        val idx = sessions.indexOfFirst { it.id == currentSessionId }
        if (idx < 0) return
        val old = sessions[idx]
        sessions[idx] = old.copy(
            updatedAt = System.currentTimeMillis(),
            preview = ChatSessionStore.previewFrom(messages),
            messageCount = messages.size,
            title = if (old.messageCount == 0 && messages.isNotEmpty()) {
                ChatSessionStore.titleFrom(messages)
            } else {
                old.title
            },
        )
        settleOrder()
        persistIndex()
    }

    /** 列表按最近更新重排；顺序没变时不动列表（避免每轮对话都整表重组） */
    private fun settleOrder() {
        val ordered = ChatSessionStore.sorted(sessions.toList())
        if (ordered.size == sessions.size &&
            ordered.indices.all { ordered[it].id == sessions[it].id }
        ) {
            return
        }
        sessions.clear()
        sessions.addAll(ordered)
    }

    private fun resetIdCounter(loaded: List<ChatMsg>) {
        msgIdCounter.set(0L)
        loaded.forEach { if (it.id > msgIdCounter.get()) msgIdCounter.set(it.id) }
    }

    private fun setActiveIdPref(ctx: Context, id: String) {
        ctx.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_ACTIVE_SESSION, id).apply()
    }
}
