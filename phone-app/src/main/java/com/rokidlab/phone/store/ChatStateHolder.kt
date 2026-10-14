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
import com.rokidlab.phone.ai.writeTextFileAtomically
import com.rokidlab.phone.util.LogCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

    /**
     * 本轮「过程」（思考 / 工具调用）落在**哪一条消息**上（[ChatMsg.id]；0 = 本轮还没有过程）。
     *
     * ★ 为什么必须是显式锚点，而不能像原先那样「每次取列表末尾那条非用户非状态消息」：
     *   **位置不是身份**。一轮对话在进行中，任何**别的**消息被追加，都会让"末尾"指向另一条
     *   消息 —— 于是同一个 `tool:<call_id>` 的 RUNNING 写进旧气泡、OK/FAILED 写进新气泡，
     *   [AgentStep] 约定的「同 key 覆盖」失效（覆盖只在单条消息内生效），旧气泡永远转圈。
     *   2026-09-20 真机复现两条**互相独立**的插入路径（都不是 show_image 独有）：
     *     1. `show_image` 的图片气泡（[addImage]）；
     *     2. 拍照答题流程的状态气泡（`onStage` → [add] / `onStageText` → [updateLastStatus]
     *        在末尾不是状态气泡时会**新增**一条）—— 所以它跟"调用了哪个工具"无关，
     *        任何工具只要落在这条时间线里就会中招。
     *   ⇒ 一轮的过程必须**认出自己的那条消息**，与它在列表里的位置无关。
     *
     * 生命周期：本轮第一次 [upsertTrace] 时锚定 → [finishTrace] 收尾时清除 →
     * 会话被换掉/清空时由 [forgetTraceAnchor] 作废。
     * 只在主线程读写（与 [messages] 同一约束）。
     */
    private var traceAnchorId: Long = 0L

    /** 本轮过程消息在 [messages] 里的下标；无锚点、或锚点已被删除/裁掉时返回 -1 */
    private fun traceAnchorIndex(): Int =
        if (traceAnchorId > 0L) messages.indexOfFirst { it.id == traceAnchorId } else -1

    /**
     * ★ **本轮已计费 token 的实时计数**（2026-10-12 对标 Codex 的常驻计量缺口）。
     *
     * 此前 token 只在**整轮结束**后由 [MsgUsage] 一次性给出 —— 长任务跑到第 8 轮时用户
     * 完全不知道已经花了多少。这里由主循环每轮 [TokenBudget.chargeRound] 后更新，
     * 聊天窗口的「过程」卡头部在**生成中**显示「≈N tokens」，跑得越久数字越大。
     *
     * ⚠️ 是**本轮**（一次回答）的累计，不是会话累计；[null] = 非生成中（无数字可显示）。
     * 用 StateFlow 而不是塞进 ChatMsg：它不属于任何一条消息，是生成期的瞬时全局状态。
     */
    private val _liveTokens = MutableStateFlow<Int?>(null)

    /** 本轮实时 token 计数（生成中才有值）；过程卡在 running 时订阅它显示轻量计数 */
    val liveTokens: StateFlow<Int?> get() = _liveTokens

    /** 主循环每轮计费后更新；传 null 清除（生成结束/新一轮开始） */
    fun setLiveTokens(value: Int?) {
        _liveTokens.value = value
    }

    /**
     * 生成中标志（2026-10-12 用户反馈「聊天记录很乱」的 H1 修复）。
     *
     * 在途那一轮会把过程 / 正文写进**当前会话**（[traceAnchorId] 只认消息 id，不认会话）。
     * 中途切会话 / 建会话，这一轮就会把内容落进**别人的**记录里 —— 表现为历史对话里冒出
     * 不属于它的步骤与回复。⇒ 生成期间禁掉会话结构变更。
     *
     * 由 [ChatScreen] 在 `sending` 变化时同步（[setGenerating]）；只在主线程读写。
     */
    private var generating = false

    /** 生成中标志同步入口（[ChatScreen] 是唯一调用点，见 [generating] 说明） */
    fun setGenerating(value: Boolean) {
        generating = value
    }

    /** 生成期间禁止任何会话结构变更（新建 / 切换）；返回 true 表示已被拦下 */
    private fun blockedByGenerating(): Boolean = generating

    /**
     * 把一组过程步骤里仍处于「进行中」的置为终态。
     *
     * 判据含 [AgentStep.State.AWAITING_CONFIRM]（2026-10-06）：它同样表示
     * "这一轮还没结束"，若不收尾，UI 上那颗点会停在"等你确认…"再也不会动
     * —— 而闸门返回后本来是会有 OK/CANCELLED 覆盖的，
     * 没覆盖到说明这一轮中途异常退出了。
     *
     * @return 有改动时返回新列表；**本来就没有进行中步骤时返回 null**（调用方据此跳过落盘）
     */
    private fun settledSteps(steps: List<AgentStep>, failed: Boolean): List<AgentStep>? {
        fun isPending(state: AgentStep.State) =
            state == AgentStep.State.RUNNING || state == AgentStep.State.AWAITING_CONFIRM

        if (steps.none { isPending(it.state) }) return null
        return steps.map {
            if (!isPending(it.state)) it
            else it.copy(state = if (failed) AgentStep.State.FAILED else AgentStep.State.OK)
        }
    }

    /** 同 key 覆盖（[AgentStep] 的覆盖约定：同 key 的后发步骤替换先前步骤） */
    private fun mergeStep(msg: ChatMsg, step: AgentStep): ChatMsg {
        val pos = msg.trace.indexOfFirst { it.key == step.key }
        val merged = if (pos >= 0) msg.trace.toMutableList().also { it[pos] = step } else msg.trace + step
        return msg.copy(trace = merged)
    }

    /**
     * 是否是「本轮**正文**气泡」——纯 AI 文本，不带过程 / 文件 / 图片。
     *
     * ## 为什么需要它（2026-10-10 用户反馈）
     * 期望的渲染顺序是「过程 → 文件气泡 → 正文」：正文必须排在**文件气泡之后**。
     * 而过程消息（带 trace）在文件气泡之前，正文若继续长在过程消息里，就会渲染在文件
     * 上方 —— 更糟的是正文还在流式增长，每长一行都把下面的文件气泡往下推，
     * 表现为**文件气泡上下跳动**（用户反馈的第二个问题）。
     * ⇒ 正文单独成一条，且恒为列表末条；AI 产出的文件气泡插在它之前（见 [addFile]）。
     */
    private fun ChatMsg.isReplyText(): Boolean =
        !isUser && !isStatus && filePath == null && imageUrl == null && trace.isEmpty()

    /**
     * 把本轮正文放到它该在的位置（用户 2026-10-10：「过程 → 文件气泡 → 正文」）。
     *
     * 三种落点，按序判定：
     *  ① 过程消息之后已有本轮流式正文气泡 ⇒ **就地更新**（它本来就落在文件之后）；
     *  ② 过程消息之后有**文件 / 图片气泡** ⇒ **末尾另起一条** —— 直接写进过程消息会让
     *     正文渲染在文件上方（顺序错），且正文继续增长会把文件气泡往下推（跳动）；
     *  ③ 其余情况 ⇒ 并进过程消息（历史形态：过程在上、正文在下同一个气泡）。
     *
     * ⚠️ ② 只认**文件 / 图片**气泡，不认状态气泡（`isStatus`）：状态气泡是过程性的、
     * 会随轮次消失，为它把正文另起一条只会多出一个空气泡（既有回归测试锁住了这一点）。
     *
     * @param anchorAt 本轮过程消息下标；&lt;0 表示本轮没有过程消息（兜底另起一条）
     */
    private fun placeReply(reply: String, usage: MsgUsage?, turn: Int?, anchorAt: Int) {
        val anchorId = messages.getOrNull(anchorAt)?.id
        // ① 本轮已有的流式正文气泡（必须在过程消息之后，否则会撞到上一轮的回复）
        val replyIdx = if (anchorAt >= 0) {
            messages.indices.lastOrNull { i -> i > anchorAt && messages[i].isReplyText() } ?: -1
        } else {
            val li = messages.size - 1
            if (li >= 0 && messages[li].isReplyText()) li else -1
        }
        if (replyIdx >= 0) {
            // 成本行挂「过程」而不是正文（2026-10-10 用户反馈："token 显示跑到输出气泡里了"）：
            // 流式正文气泡是**先**出现的，收尾补 usage 时若直接写它，过程卡就永远拿不到成本行
            // —— 而 ChatBubble 的气泡内成本行只是"没有过程卡的纯闲聊"的兜底出口，
            // 一写就变成正文气泡里凭空多出一行 token。⇒ 与分支②同一口径：成本留过程消息。
            if (anchorAt >= 0 && usage != null) {
                val anchorOld = messages[anchorAt]
                val costed = anchorOld.copy(usage = usage, turn = turn ?: anchorOld.turn)
                if (costed != anchorOld) {
                    messages[anchorAt] = costed
                    persist(costed)
                }
            }
            val old = messages[replyIdx]
            val merged = old.copy(
                content = preserveOnReplace(old.content, reply),
                usage = if (anchorAt >= 0) null else (usage ?: old.usage),
                turn = turn ?: old.turn,
            )
            messages[replyIdx] = merged
            persist(merged)
            touchSession()
            return
        }
        // ② 过程消息之后已有文件 / 图片气泡 ⇒ 正文排在它们后面
        val durableAfter = anchorId != null && messages.indices.any { i ->
            i > anchorAt && (messages[i].filePath != null || messages[i].imageUrl != null)
        }
        if (durableAfter) {
            val old = messages[anchorAt]
            // 成本行属于「过程」（见 ChatBubble.TraceCostLine 注释），留在过程消息上；
            // 正文消息只拿内容 + 轮号（轮号是会话记录图里编辑/删除的定位依据）
            val costed = old.copy(usage = usage ?: old.usage, turn = turn ?: old.turn)
            if (costed != old) {
                messages[anchorAt] = costed
                persist(costed)
            }
            add(false, reply, usage = null, turn = turn)
            return
        }
        // ③ 无文件：正文并进过程气泡（历史形态不变）
        if (anchorId != null) {
            val old = messages[anchorAt]
            val merged = old.copy(
                content = preserveOnReplace(old.content, reply),
                usage = usage ?: old.usage,
                turn = turn ?: old.turn,
            )
            messages[anchorAt] = merged
            persist(merged)
            touchSession()
            return
        }
        add(false, reply, usage = usage, turn = turn)
    }

    /**
     * 作废本轮的过程锚点（换会话 / 清空 / 删除会话时调用）。
     *
     * 为什么只清锚点、不做"结清残留"：这三个调用点后面**紧跟** `messages.clear()`，
     * 被结清的那条消息马上就没了，结清它没有任何可观察效果。
     * 真正需要的是**别留着旧锚点** —— 新会话消息 id 从 `resetIdCounter` 重新开始，
     * 一旦撞上旧锚点的 id，[traceAnchorIndex] 就会把新会话里某条同 id 的消息
     * 误认成"本轮过程消息"，让在途的那一轮把过程写进别人的气泡。
     */
    private fun forgetTraceAnchor() {
        traceAnchorId = 0L
    }

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

    /**
     * 索引损坏时，从**会话消息文件**重建列表（2026-10-06 审计修）。
     *
     * ## 为什么消息文件才是真相源
     * 索引（`chat_sessions.json`）是「读旧 + 拼新 + 写回」的产物，只存列表页要的展示字段；
     * 而每个 `chat_sessions/<id>.jsonl` 才是消息本体。索引坏了时**消息一条都没丢**，
     * 所以能从文件重建列表，而不是让用户面对一个空的历史。
     *
     * 📌 恢复的精度：标题/预览/条数从消息重新派生（与 [ChatSessionStore.titleFrom] /
     * [previewFrom] 同一套口径，恢复后与原索引基本一致）；只有 `systemPrompt` 恢复不了
     * （它只存在索引里）—— 那是个别会话的附加人设，缺失不影响历史可见性。
     */
    private fun rebuildFromSessionFiles(ctx: Context): List<ChatSessionMeta> {
        val dir = File(ctx.filesDir, ChatSessionStore.SESSION_DIR)
        return ChatSessionStore.rebuildIndexFrom(
            sessionDir = dir,
            readCount = { f -> runCatching { ChatHistoryStore.readHistory(f) }.getOrDefault(emptyList()).size },
            titleOf = { f ->
                runCatching { ChatSessionStore.titleFrom(ChatHistoryStore.readHistory(f)) }
                    .getOrDefault(ChatSessionStore.DEFAULT_TITLE)
            },
            previewOf = { f ->
                runCatching { ChatSessionStore.previewFrom(ChatHistoryStore.readHistory(f)) }.getOrDefault("")
            },
        )
    }

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
            val raw = if (idx.exists()) idx.readText() else ""
            // ★ 关键区分（2026-10-06 审计修）：`null` = 索引**损坏**，不是"没有会话"。
            // 旧实现对半截/ 0 字节索引与"首次启动"一视同仁地返回空列表 ⇒ 走新建分支 ⇒
            // **磁盘上的会话文件全都还在，但整个历史列表消失**（用户以为数据被清空）。
            val parsed = ChatSessionStore.parseIndexOrNull(raw)
            var list = parsed.orEmpty()
            var indexWasCorrupt = parsed == null
            var active = ctx.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)
                .getString(KEY_ACTIVE_SESSION, "").orEmpty()

            // 索引坏了但消息文件还在 ⇒ 从消息文件（真相源）重建，而不是当作没有会话。
            if (indexWasCorrupt && list.isEmpty()) {
                val rebuilt = rebuildFromSessionFiles(ctx)
                if (rebuilt.isNotEmpty()) {
                    LogCollector.w(TAG, "session index corrupted; rebuilt ${rebuilt.size} sessions from files")
                    list = rebuilt
                    indexWasCorrupt = false
                }
            }

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
                writeTextFileAtomically(idx, ChatSessionStore.toIndexJson(list))
                setActiveIdPref(ctx, id)
            } else if (indexWasCorrupt) {
                // 走到这里说明重建也没成功（消息文件确实不存在）⇒ 保持"没有会话"的原语义，
                // 但把索引**重新写一份合法的**，否则损坏的原文会一直留着。
                writeTextFileAtomically(idx, ChatSessionStore.toIndexJson(list))
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
        // ★ H1（2026-10-12）：生成期间禁止新建 —— 在途那一轮会把过程/正文写进新会话。
        //   UI 侧已置灰入口，这里是防绕过（如 deleteSession 的内部补位路径）。
        if (blockedByGenerating()) return currentSessionId
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
        // 换会话 = 放弃本轮：旧锚点必须作废（新会话消息 id 重排，撞上就会被误认成本轮过程）
        forgetTraceAnchor()
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
        // ★ H1（2026-10-12）：生成期间禁止切换 —— 在途那一轮会落进新切到的会话。
        if (blockedByGenerating()) return
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
        forgetTraceAnchor()
        messages.clear()
        msgIdCounter.set(0L)
        submit("clear session") { ChatHistoryStore.rewrite(sessionFile(ctx, id), emptyList()) }
        touchSession()
    }

    /** 后台加载指定会话的消息（带代际校验，快速连点切会话时旧结果作废） */
    private fun loadSessionMessages(id: String) {
        val ctx = appContext ?: return
        val gen = ++loadGen
        // 切会话 = 放弃本轮：旧锚点作废（见 forgetTraceAnchor），再换掉整个列表
        forgetTraceAnchor()
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
        // ★ 2026-10-09：删消息要**同步作废事件流里对应的那一轮**，否则"删了它还记得" ——
        //   界面少了这条，模型上下文里还留着，下一轮照着它回答。
        //   判据取被删消息自己的轮号（ChatMsg.turn 就是事件流的轮号，写入时一一对应），
        //   并作废该轮及其之后（删中间一条 ⇒ 后续轮次建立在这个上下文上，一并失效）。
        val deadTurn = messages[idx].turn
        messages.removeAt(idx)
        if (deadTurn != null && deadTurn > 0) {
            com.rokidlab.phone.ai.AgentSessionManager.dropFromTurn(deadTurn)
        }
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
        // ⚠️ 2026-10-09：与 [deleteMessage] 同款同步。这里砍掉的是"从该条起**全部**"，
        //   而事件流侧的 `dropLastTurn` 只切**最后一轮** —— 口径不一致会让被砍掉的那些轮
        //   仍然进模型上下文（编辑靠前的一条消息时，后面若干轮模型照旧引用）。
        val deadTurn = messages[idx].turn
        while (messages.size > idx) messages.removeAt(messages.size - 1)
        if (deadTurn != null && deadTurn > 0) {
            com.rokidlab.phone.ai.AgentSessionManager.dropFromTurn(deadTurn)
        }
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
        failed: Boolean = false,
    ): ChatMsg {
        val id = msgIdCounter.incrementAndGet()
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        val msg = ChatMsg(id, isUser, content, time, isStatus, usage = usage, turn = turn, failed = failed)
        messages.add(msg)
        trimIfNeeded()
        persist(msg)
        touchSession()
        return msg
    }

    /**
     * 标记/清除用户对一条消息的反馈（1=有用 -1=没用 null=取消）。
     * 与 [setMessageTurn] 同款更新方式：copy 原消息后按 id 原位替换，persist 追加新行
     * （JSONL 读取端按 id 去重、后行覆盖前行，见 ChatHistoryStore）。
     *
     * **当前无调用方**（2026-10 移除了长按菜单的点赞/点踩入口，理由见
     * ChatMessageActionsDialog 内注释）；刻意保留，作为重新启用时的现成写入 API。
     */
    fun setFeedback(id: Long, value: Int?) = runOnMain {
        val idx = messages.indexOfFirst { it.id == id }
        if (idx < 0) return@runOnMain
        val updated = messages[idx].copy(feedback = value)
        messages[idx] = updated
        persist(updated)
    }

    /**
     * 添加一条**文件消息**（用户上传的文本文件 / AI 用 `run_shell` 写出的文件）。
     *
     * 与 [addImage] 的区别（刻意不同）：
     *  - **不往本轮 AI 气泡上挂**：文件是"一份独立的东西"，可能一轮产出好几个 ——
     *    挂到同一条气泡上只能显示一个，且会把正文和文件混在一起；
     *  - 路径/名字/字数落在消息上，正文留在磁盘（见 [ChatMsg.filePath] 的说明）。
     *
     * @param turn 事件流轮号（AI 产出文件时传当前轮，用户上传时传 turnBefore + 1）
     */
    fun addFile(
        isUser: Boolean,
        path: String,
        name: String,
        chars: Int?,
        caption: String = "",
        turn: Int? = null,
    ): ChatMsg {
        val id = msgIdCounter.incrementAndGet()
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        val msg = ChatMsg(
            id = id,
            isUser = isUser,
            content = caption,
            time = time,
            isStatus = false,
            filePath = path,
            fileName = name,
            fileChars = chars,
            turn = turn,
        )
        // ★ 顺序（2026-10-10 用户要求「过程 → 文件气泡 → 正文」）：AI 产出的文件排在
        //   **末尾那条正文气泡之前** —— 正文恒为末条，文件插到它前面才能保证文件在正文之上。
        //   用户上传的文件（isUser）没有这个约束，直接追加（它就是用户这一问的一部分）。
        val lastIdx = messages.size - 1
        val last = messages.getOrNull(lastIdx)
        val insertAt = if (!isUser && last != null && last.isReplyText() && last.content.isNotEmpty()) {
            lastIdx
        } else {
            messages.size
        }
        messages.add(insertAt, msg)
        trimIfNeeded()
        persist(msg)
        touchSession()
        return msg
    }

    /**
     * 原地更新末尾状态消息（用于 OCR 模型下载百分比等高频进度）。
     * 末尾是状态消息就覆盖内容（不新增气泡、不落盘，避免每 1% 刷一条 + 高频写文件）；
     * 末尾不是状态消息时退化为新增一条状态消息。必须在主线程调用。
     *
     * ⚠️ **不落盘是有意的**（高频），但它有个后果必须配套处理（2026-10-06 审计修）：
     * 首次 [add] 时落的是**最初那条**文案（如"正在识别文字…"），后续覆盖只在内存里。
     * 于是进程被杀 / 用户强退后，盘上留下的是那条**永远不会再更新**的进度文案 ⇒
     * 重启后聊天里立着一行"正在识别文字…"，永远转不完。
     *
     * ⇒ 配套：[clearStatusLines] 在**一轮结束时**把状态行删掉并落盘一次。
     * 高频期间不写盘、结束时一次写，既不刷屏也不会残留。
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
     * 清掉末尾连续的状态消息，并在有改动时落盘一次（2026-10-06 审计修）。
     *
     * ## 为什么必须清而不是留着
     * 状态行的语义是"**进行中**"。一轮结束后它就变成了一句**过期的话**——
     * 尤其"正在识别文字…"这种，重启后永远停在那里，用户以为 App 卡住了。
     * 项目里已有同类纪律：会话标题/摘要都排除状态行（[ChatSessionStore]）、
     * 代码卡的"运行最近代码"也跳过状态行 —— 状态行**本来就不该进入历史**。
     *
     * 📌 只删**末尾连续**的那些：中间的状态行（若有）可能是另一轮的，
     * 删掉会丢掉"当时正在做什么"的历史信息，而末尾这些是**当前这轮**的残留。
     *
     * @return 有改动时返回 true（调用方据此决定要不要播报）
     */
    fun clearStatusLines(): Boolean {
        var removed = 0
        while (messages.lastOrNull()?.isStatus == true) {
            messages.removeAt(messages.size - 1)
            removed++
        }
        if (removed == 0) return false
        // 一次性落盘：把"残留的进行中文案"从历史里抹掉（高频期间不写盘的代价在这里还清）
        persistAll()
        return true
    }

    // ═══════════ 状态行 → 过程面板（2026-10-10） ═══════════

    /**
     * 流程状态（拍照答题的"正在识别文字…"／OCR 下载百分比）**进过程面板**，不再是对话气泡。
     *
     * ## 为什么归位
     * 改造前它们是 `isStatus = true` 的独立消息（居中灰字）。这有两处不对：
     * 1. **分流错了**：它回答的是"App 正在做什么"（= 过程），却占了对话流的位置；
     * 2. **位置靠不住**：`clearStatusLines` 只挂在拍照答题的两个 `onReply` 上
     *    （打字路径与眼镜语音路径都没有对等调用），所以它靠"必须排在 finalizeTraceReply 之前"
     *    这个**调用顺序**才能被清掉 —— 顺序一变就留下永久残留。
     *    既然现在只落在过程面板（而过程面板**每轮必被收尾**），这条脆弱的配套就不需要了。
     *
     * 📌 高频进度的代价照旧由 `upsertTrace` 承担（不落盘、由 `finishTrace` 统一收尾）。
     */
    fun updateStatusTrace(text: String) = upsertTrace(
        AgentStep.statusProgress(text),
    )

    /**
     * 流程状态的**终态**（失败 / 没识别出文字）。
     *
     * 为什么要单独一个入口：进行中的那颗转圈点必须被**显式结清**，
     * 否则它会一直转到本轮 `finishTrace` 才消失 —— 而失败轮往往不经过 finishTrace，
     * 界面上就留着一颗永远转的点，看起来像"还在识别"。
     *
     * @param ok false ⇒ 画成 FAILED。**不能一律画成功**：拍照失败显示绿勾会让用户
     *   以为照片已处理完（这是**撒谎**，比不显示更糟）。
     */
    fun finishStatusTrace(text: String, ok: Boolean = true) = upsertTrace(
        AgentStep.statusDone(text, ok),
    )

    // ═══════════ 运行卡片（本地交互式程序，见 InteractiveRunManager） ═══════════

    /**
     * 插入一条**运行卡片**消息（程序启动时调用）。必须在主线程调用。
     *
     * ⚠️ **刻意不走 [persist]**：输出高频原地更新（每行一次），落盘会把聊天记录文件写成
     * 灾难；且程序/输出在会话重放里没有意义 —— 会话重载后运行卡自然消失。
     *
     * ⚠️ 定位**只认 [ChatMsg.runId]**，不按"列表末尾" —— 教训来自 upsertTrace 时代：
     * 按位置定位的消息，被中途插入的新消息一顶就劈开（永久转圈 bug）。
     */
    fun addRunCard(runId: String, label: String): ChatMsg {
        val id = msgIdCounter.incrementAndGet()
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        val msg = ChatMsg(
            id = id,
            isUser = false,
            content = "",
            time = time,
            isStatus = false,
            runId = runId,
        )
        // ★ 过程面板也记一笔（2026-10-10）：运行卡本身**留在对话区**
        //   （它是用户主动触发的产物，输出要占地方看），但"启动了它"这个动作属于过程。
        //   没有它，程序跑 30 秒期间过程面板是空的 —— 用户点了"运行"然后界面毫无反应。
        //
        // ⚠️ 必须用 [upsertDetachedTrace]（**不占本轮锚点**），不能用 [upsertTrace]：
        //   手动运行程序**不属于任何一轮**，若它占了 traceAnchorId，紧接着的那一轮 AI
        //   的思考/工具步骤会全部合并进这张运行过程卡（用户看到"我手动跑的程序"里
        //   混着"AI 刚才想了什么"）。
        // ⚠️ 也必须在 messages.add 之前发：落在末尾那条运行卡上会被
        //   ChatBubble 的 `runId != null` 早退分支吃掉（运行卡与过程卡互斥）。
        upsertDetachedTrace(AgentStep.runStarted(label, runId))
        messages.add(msg)
        runMeta[runId] = label
        return msg
    }

    /** 运行卡片的语言标签（runId → label；程序从 runs 移除后渲染层还要用它显示标题） */
    private val runMeta = mutableMapOf<String, String>()
    fun runLabel(runId: String): String = runMeta[runId] ?: ""

    /** 程序输出到达：按 runId 原地更新对应消息（高频、不落盘）。必须在主线程调用 */
    fun upsertRunOutput(runId: String, output: String) {
        val idx = messages.indexOfFirst { it.runId == runId }
        if (idx >= 0) messages[idx] = messages[idx].copy(content = output)
    }

    /**
     * 程序结束：把最终输出定格进运行卡片并追加收尾行（退出码/超时原因）。
     * Run 已从管理器移除 ⇒ 渲染层此后读 content。必须在主线程调用。
     */
    fun finishRunCard(runId: String, output: String, tail: String) {
        val idx = messages.indexOfFirst { it.runId == runId }
        if (idx >= 0) {
            val body = listOf(output.trim(), tail).filter { it.isNotBlank() }.joinToString("\n")
            messages[idx] = messages[idx].copy(content = body)
        }
        // 结清过程面板里那条「正在运行」（同 key 覆盖）。⚠️ 无论卡片是否还在
        // （idx < 0）都要结清 —— 那颗转圈点转下去比"过程里多一行"糟糕得多。
        upsertDetachedTrace(AgentStep.runFinished(runId, tail))
    }

    /**
     * 写入一条**不属于本轮**的过程步骤（按 [AgentStep.key] 定位它自己那条消息）。
     *
     * ## 为什么不能复用 [upsertTrace]
     * [upsertTrace] 是**锚点制**的：第一个步骤会认领本轮的过程消息
     * （`traceAnchorId`），后续步骤全部并进那一条。这对"一轮对话"是对的，
     * 但手动运行程序、拍照答题这类**跨轮**动作会把它占住 ——
     * 于是**下一轮** AI 的思考/工具步骤被合并进上一张卡里
     * （表现：用户手动跑的程序，过程里混着 AI 后面想了什么）。
     *
     * 这里改用"key 自认领"：同一个 key（如 `run:<runId>`）永远落在同一条消息，
     * 与轮次锚点完全解耦。
     */
    private fun upsertDetachedTrace(step: AgentStep) = runOnMain {
        val at = messages.indexOfFirst { m -> m.trace.any { it.key == step.key } }
        if (at >= 0) {
            messages[at] = mergeStep(messages[at], step)
            return@runOnMain
        }
        val id = msgIdCounter.incrementAndGet()
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        messages.add(ChatMsg(id, false, "", time, false, trace = listOf(step)))
        trimIfNeeded()
    }

    /**
     * 添加一条图片消息：content = 图片说明（caption），[imageUrl] = 远端图片直链。
     * 由 `show_image` 工具调用，必须在主线程调用（Compose 快照线程）。
     *
     * ⚠️ **本轮还在进行时就地挂到本轮那条 AI 气泡上，不要另起一条**。
     *
     * 理由不是"少一条气泡"这么表面：图片是本轮回答的一部分，另起一条会把它和
     * 过程 / 正文拆开；更要紧的是[upsertTrace]以前按"末尾"定位目标，新气泡会把后续
     * 过程步骤从旧气泡上顶走（那条 bug 的根因已在 [upsertTraceOnMain] 用锚点解决，
     * 这里保持"就地挂"是为了让图片、过程、正文落在同一个气泡里）。
     *
     * 落点顺序：① 本轮锚点（[traceAnchorIndex]）→ ② 末尾那条"看起来属于本轮的 AI 气泡"
     * （非用户/非状态，且**带过程**或**正文还空**）。都不满足说明这是历史回复之外的一次
     * 独立展示 ⇒ 另起一条（保留旧行为，如"再给我看张图"）。
     *
     * @param turn 这一轮在事件流里的轮号（见 [ChatMsg.turn]）。用户从相册/拍照发的图也要带 ——
     *   不带的话它在会话记录图里找不到对应轮次，只能"复制"，**不能编辑重发/删除**
     *   （图片恰恰是最需要重发的一类消息：发错图、图不清楚）。
     */
    fun addImage(isUser: Boolean, imageUrl: String, caption: String, turn: Int? = null): ChatMsg {
        val anchorAt = if (isUser) -1 else traceAnchorIndex()
        val idx = if (anchorAt >= 0) {
            anchorAt
        } else {
            val lastIdx = messages.size - 1
            val last = messages.getOrNull(lastIdx)
            if (!isUser && last != null && !last.isUser && !last.isStatus &&
                (last.trace.isNotEmpty() || last.content.isEmpty())
            ) {
                lastIdx
            } else {
                -1
            }
        }
        if (idx >= 0) {
            val old = messages[idx]
            // 正文已有内容（本轮已流出一段）就保留，不拿 caption 覆盖
            val merged = old.copy(
                content = old.content.ifEmpty { caption },
                imageUrl = imageUrl,
            )
            messages[idx] = merged
            persist(merged)
            touchSession()
            return merged
        }
        val id = msgIdCounter.incrementAndGet()
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        val msg = ChatMsg(id, isUser, caption, time, isStatus = false, imageUrl = imageUrl, turn = turn)
        messages.add(msg)
        trimIfNeeded()
        persist(msg)
        touchSession()
        return msg
    }

    /**
     * 超出历史上限时裁掉最旧的消息（落盘文件由冷启动压实收敛）。
     *
     * ★ M3（2026-10-12 修）：**本轮过程锚点那条不能被裁**。旧实现无脑 `removeAt(0)`，
     * 若锚点恰好是最旧那条（一轮产出极多时会发生），它被裁掉后 [traceAnchorIndex] 变成 -1，
     * 后续步骤就会**另起一条**过程气泡 —— 表现为"一轮里出现两个过程卡"，记录看起来很乱。
     * ⇒ 锚点在最旧位置时改裁它的下一条，保证它在收尾前一直存活。
     */
    private fun trimIfNeeded() {
        while (messages.size > MAX_HISTORY) {
            val at0 = messages[0].id
            val idx = if (traceAnchorId > 0L && at0 == traceAnchorId && messages.size > 1) 1 else 0
            messages.removeAt(idx)
        }
    }

    /**
     * Agent 过程步骤合并（思考 / 工具调用），按 [AgentStep.key] 覆盖。
     *
     * 落盘策略：**新建过程消息时立刻落盘一次**（保证它早于本轮文件气泡入库，顺序见
     * [addFile] 的注释），后续步骤的合并**不落盘**（频率高），统一由 [finishTrace] /
     * [finalizeTraceReply] 收尾时持久化。
     * 可在任意线程调用（内部自动切主线程，见 [runOnMain]）。
     */
    fun upsertTrace(step: AgentStep) = runOnMain { upsertTraceOnMain(step) }

    private fun upsertTraceOnMain(step: AgentStep) {
        // ① 锚点优先：本轮的过程只认这一条消息 —— 中途插入的图片 / 状态气泡不再把它劈开
        val at = traceAnchorIndex()
        if (at >= 0) {
            messages[at] = mergeStep(messages[at], step)
            return
        }
        // ② 本轮还没有锚点（第一条步骤）：末尾若是一条**空的** AI 占位（无正文、无文件/图片）
        //    就复用它，否则新建一条并锚定它。
        //    ⚠️ H2（2026-10-12 修）：判据**必须要求 content 为空**。旧判据含 `trace.isNotEmpty()`，
        //    为真时会把**上一轮**已有的「过程 + 正文」气泡当成"本轮占位"复用 —— 本轮的第一条
        //    步骤就直接写进历史消息里（用户反馈：步骤跑到别的回复上、记录很乱）。
        val lastIdx = messages.size - 1
        val last = messages.getOrNull(lastIdx)
        if (last != null && !last.isUser && !last.isStatus &&
            last.filePath == null && last.imageUrl == null && last.content.isEmpty()
        ) {
            messages[lastIdx] = mergeStep(last, step)
            traceAnchorId = last.id
            return
        }
        val id = msgIdCounter.incrementAndGet()
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        val msg = ChatMsg(id, false, "", time, false, trace = listOf(step))
        messages.add(msg)
        traceAnchorId = id
        trimIfNeeded()
        // ★ 立刻落盘（2026-10-10）：过程消息是**本轮第一条** AI 消息，必须早于本轮的
        //   文件气泡入库。否则文件气泡（[addFile] 是即时落盘）会先写进 JSONL，
        //   重启后按文件顺序恢复就变成「文件气泡 → 过程」——用户看到的顺序错乱
        //   （用户反馈：产出的文件气泡跑到了过程前面）。
        persist(msg)
    }

    /**
     * 眼镜语音 / 拍照答题一轮的回复落地：**先结清本轮过程**，再按 [placeReply] 决定正文落点
     * （没有文件时仍是「过程 + 正文」同一个气泡，历史形态不变）。
     * 过程消息认 [traceAnchorId] 锚点；锚点缺失时退回旧判据
     * （末尾那条非用户、非状态、trace 非空的）。
     */
    fun finalizeTraceReply(reply: String, usage: MsgUsage? = null, turn: Int? = null) =
        runOnMain { finalizeTraceReplyOnMain(reply, usage, turn) }

    private fun finalizeTraceReplyOnMain(reply: String, usage: MsgUsage?, turn: Int?) {
        val anchorAt = traceAnchorIndex()
        val lastIdx = messages.size - 1
        val at = if (anchorAt >= 0) {
            anchorAt
        } else if (lastIdx >= 0) {
            val last = messages[lastIdx]
            // ⚠️ 只认"末尾那条"，**不要**整表倒序找"最近一条带过程的" —— 那会捞到上一轮的
            //    回复，把这一轮的答案写进历史消息里（用户改一条、动的是别的）。
            if (!last.isUser && !last.isStatus && last.trace.isNotEmpty()) lastIdx else -1
        } else {
            -1
        }
        // ① 先结清过程残留（正常收尾：残留的 RUNNING 视为已完成 —— 不知道那步失败没，
        //    标 FAILED 等于编造错误）。正文落点与过程分离后，这一步只动 trace。
        //    ★ 无论有没有残留都必须落盘（2026-10-10 用户反馈：重启后过程只剩 1 步）：
        //    [upsertTraceOnMain] 只在"新建过程消息"那一刻落盘，后续步骤全攒在内存里等收尾；
        //    若这里沿用 `settledSteps(...)?.let{...}`，则**没有 RUNNING 残留时那次 persist 被跳过**
        //    —— 而工具都正常拿到结果时正是这种情况，于是盘上永远只有第一条步骤。
        if (at >= 0) {
            val old = messages[at]
            val settled = settledSteps(old.trace, failed = false)?.let { old.copy(trace = it) } ?: old
            messages[at] = settled
            persist(settled)
        }
        // ② 正文落点（2026-10-10）：见 [placeReply] —— 「过程 → 文件气泡 → 正文」
        placeReply(reply, usage, turn, at)
        // ★ H3（2026-10-12 修）：本轮到此结束，锚点必须作废 —— 与 [finishTraceOnMain] 同一口径。
        //   漏清会让下一轮（无锚点时）仍指向这一条，把新过程写进上一轮的回复气泡里。
        traceAnchorId = 0L
    }

    /**
     * 把动作切到主线程（[SnapshotStateList] 只能在 Compose 快照线程写）。
     *
     * `internal`（2026-10-12）：代码生成落盘后由 [com.rokidlab.phone.domain.AiConversationService]
     * 的工具线程调用（补文件卡），需要从这里切主线程 —— 它是写 [messages] 的**唯一**入口。
     */
    internal inline fun runOnMain(crossinline block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post { block() }
    }

    /**
     * 收尾本轮过程：把**本轮过程消息**里仍处于 [AgentStep.State.RUNNING] 的步骤置为终态并落盘。
     *
     * @param failed true = 本轮整体失败/被打断（失败气泡场景），残留步骤标 FAILED；false = 正常收尾标 OK
     */
    fun finishTrace(failed: Boolean = false) = runOnMain { finishTraceOnMain(failed) }

    private fun finishTraceOnMain(failed: Boolean) {
        // 只结清**本轮锚定的那一条**消息（[traceAnchorId]）。一轮的过程只落在一条消息上
        // （由 [upsertTraceOnMain] 的锚点保证），所以"这一条"就是"本轮全部过程"。
        //
        // 为什么刻意**不**扫全表、见到 RUNNING 就统统标 OK：那是拿兜底盖住症状 —— 它会把
        // "这里为什么会有残留"一起抹掉。历史消息里若真有残留，它应该被看见、被查，
        // 而不是被顺手改成绿色的。锚点制让"残留"在结构上不再产生，收尾就不需要兜底。
        val at = traceAnchorIndex()
        if (at >= 0) {
            val old = messages[at]
            // ★ 同 [finalizeTraceReplyOnMain]：没有 RUNNING 残留也要落盘，否则这一轮攒下的
            //    步骤（[upsertTraceOnMain] 合并不落盘）会随进程一起丢掉。
            val updated = settledSteps(old.trace, failed)?.let { old.copy(trace = it) } ?: old
            messages[at] = updated
            persist(updated)
        }
        // 本轮到此结束：锚点失效，下一轮的过程必须落到它自己那条消息上
        traceAnchorId = 0L
    }

    /**
     * 流式增量累积：AI 回复边生成边显示。若尚无正文气泡则先创建一条。
     * 不落盘（高频调用），由 [finalizeLastAi] 完成后统一持久化。
     *
     * ⚠️ 只认**正文气泡**（[isReplyText]），不认过程消息 / 文件气泡：
     * 过程消息带 trace、文件气泡带 filePath，正文若流进去就会解析错位置 ——
     * 前者让正文渲染在文件上方（顺序错 + 文件跳动），后者把正文塞进文件卡的 caption。
     */
    fun appendAiDelta(delta: String) {
        if (delta.isEmpty()) return
        val last = messages.lastOrNull()
        if (last != null && last.isReplyText()) {
            messages[messages.size - 1] = last.copy(content = last.content + delta)
        } else {
            val id = msgIdCounter.incrementAndGet()
            val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
            messages.add(ChatMsg(id, false, delta, time, false))
        }
    }

    /**
     * 流式结束（或失败兜底）后，用完整回复修正**本轮那条 AI 消息**内容并落盘。
     * 锚点优先（提问/回复/过程必须落在同一条）；锚点缺失时退回"从末尾找第一条非用户非状态
     * 消息" —— 该判据会跳过末尾的状态气泡，所以仍是安全的。
     * 一条 AI 消息都没有时直接新增（兜底路径，如流式未触发直接 onReply）。
     *
     * ⚠️ 2026-10-09：**已有正文时不整段覆盖**（[preserveOnReplace]）。
     * 失败兜底会把一句"服务不可用"当 fullContent 传进来，而此时屏幕上可能已经有
     * 模型流式打出的半截（"好的，我在帮你查…"）—— 整段覆盖等于**把用户正在读的字抹掉**，
     * 且事件流侧同时补了 partial 半截，两边对不上。
     * 改为追加在原文之后，失败原因仍然可见，但不销毁已有内容。
     */
    fun finalizeLastAi(fullContent: String, usage: MsgUsage? = null, turn: Int? = null) {
        val anchorAt = traceAnchorIndex()
        if (anchorAt >= 0) {
            // 正文落点统一走 placeReply（2026-10-10）：本轮有文件气泡时正文排到它们之后，
            // 否则仍并进过程气泡 —— 这里不再直接写锚点。
            placeReply(fullContent, usage, turn, anchorAt)
            return
        }
        for (i in messages.size - 1 downTo 0) {
            val m = messages[i]
            // ⚠️ H4（2026-10-12 修）：必须排除**文件 / 图片气泡** —— 旧判据只有「非用户、非状态」，
            //    末尾那几条正好是文件/图片卡时会把正文写进它们的 caption 里（文件卡凭空多出一段话）。
            if (!m.isUser && !m.isStatus && m.filePath == null && m.imageUrl == null) {
                // 已经有了的就别抹掉：新值缺失只说明"这次没拿到"，不代表之前那条是错的
                val updated = m.copy(
                    content = preserveOnReplace(m.content, fullContent),
                    usage = usage ?: m.usage,
                    turn = turn ?: m.turn,
                )
                messages[i] = updated
                persist(updated)
                touchSession()
                return
            }
        }
        add(false, fullContent, usage = usage, turn = turn)
    }

    /**
     * 收尾时的内容取值：已有正文就**追加**，否则直接用新值（见 [finalizeLastAi]）。
     *
     * @param existing 屏幕上已有的正文（可能是模型流式打出的半截）
     * @param incoming 本次收尾拿到的内容（完整回复，或失败兜底的一句提示）
     */
    private fun preserveOnReplace(existing: String, incoming: String): String {
        val old = existing.trim()
        if (old.isEmpty()) return incoming
        val newText = incoming.trim()
        if (newText.isEmpty()) return existing
        // 代码生成回合的收尾状态句（"已生成 N 个文件，保存在手机下载目录的"xxx"项目。"）
        // 是框架/模型产出的**机器口吻状态复述** —— 过程卡片的产物芯片 + 文件卡已经讲清楚了。
        // 正文气泡已有内容（模型流式打出的自然表述）时不再追加，否则气泡尾部就多出一句
        // 系统状态（2026-10-10 用户反馈："最后生成的气泡里有部分内容不该在回复的气泡里"）。
        // 无正文时仍原样采用它（见上方 `old.isEmpty()` 分支）—— 它是"模型没给结论"时的兜底。
        if (com.rokidlab.phone.ai.isCodeGenStatusLine(newText)) return existing
        // 同一段（正常收尾把已显示的内容又下发一次）⇒ 不重复追加
        if (newText == old || old.contains(newText)) return incoming
        return "$existing\n\n$newText"
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

    /**
     * 写会话索引。
     *
     * ⚠️ 走**原子写**（2026-10-06 审计修）：这里也是「读旧 + 拼新 + 写回」序列
     * （[sessions] 是内存全量，重算后整体覆盖）。
     * 裸写时提交后台线程后被 LMK 杀掉 ⇒ 索引成半截/ 0 字节，而
     * [ChatSessionStore.parseIndex] 对残缺 JSON **静默返回空列表** ⇒
     * `bootstrap()` 判定"索引为空"并走新建会话分支 ⇒
     * **磁盘上的会话文件全都还在，但整个历史列表消失**（用户以为数据被清空），
     * 且后续持久化会用新索引覆盖，原始索引彻底不可恢复。
     */
    private fun persistIndex() {
        val ctx = appContext ?: return
        val snapshot = ChatSessionStore.toIndexJson(sessions.toList())
        submit("persist index") { writeTextFileAtomically(indexFile(ctx), snapshot) }
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
