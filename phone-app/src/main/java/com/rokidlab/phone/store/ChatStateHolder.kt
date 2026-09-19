package com.rokidlab.phone.store

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.rokidlab.phone.ai.AgentStep
import com.rokidlab.phone.util.LogCollector
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "ChatStateHolder"
private const val HISTORY_FILE = "chat_history.json"

/**
 * 聊天消息状态全局持有器。
 *
 * 解决切到其他功能页面后聊天对话被清空的问题：原实现用 [ChatModule] 内的
 * `remember { mutableStateOf(listOf<ChatMsg>()) }`，离开组合即销毁。
 * 改为单例持有 [SnapshotStateList]，Compose 跨页面共享，状态持久。
 *
 * 另支持落盘持久化（App 重启不丢失）与流式增量更新（AI 回复边生成边显示）：
 *   - [init] 在 Application.onCreate 调用，**后台**加载历史记录
 *   - [add]/[addImage]/[finalizeLastAi] 写入后追加一行 JSONL（增量，不重写整份文件）
 *   - [clear] 清空内存并删除落盘文件
 *   - [appendAiDelta] 流式增量累积（不落盘，高频），[finalizeLastAi] 完成后统一持久化
 *
 * **线程模型**（格式与回放逻辑见 [ChatHistoryStore]）：
 *   - `SnapshotStateList` 只在调用线程（Compose 主线程）读写，**绝不**在后台线程触碰；
 *   - 所有文件 I/O 交给单线程 [writer]，按提交顺序串行执行 —— 既避免阻塞主线程，
 *     也避免并发写坏文件；
 *   - 序列化在调用线程完成后再把字符串交给 [writer]，因此后台任务只碰普通字符串。
 *   - 例外：[upsertTrace] / [finishTrace] / [finalizeTraceReply] 可**从任意线程**调用 ——
 *     它们由 AI 生成线程 / ASR 轮询线程直接触发，内部用 [runOnMain] 自行切主线程。
 *
 * 仍由 [ChatModule] 负责所有写入（appendMsg/clear），仅状态本身常驻。
 */
internal object ChatStateHolder {
    /** 当前聊天消息列表（Compose 可观察） */
    val messages: SnapshotStateList<ChatMsg> = mutableStateListOf()

    /** 自增消息 id，避免 LazyColumn key 冲突（原子操作，防并发重复 key） */
    private val msgIdCounter = AtomicLong(0L)

    @Volatile
    private var appContext: Context? = null

    /** 单线程落盘器：daemon 线程，不阻止 JVM 退出 */
    private val writer: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "chat-history-writer").apply { isDaemon = true }
    }

    /** 后台读取完成后把结果投回主线程（SnapshotStateList 只能在主线程写） */
    private val mainHandler: Handler by lazy { Handler(Looper.getMainLooper()) }

    /** 应用启动时初始化上下文，并在后台加载落盘历史 */
    fun init(context: Context) {
        appContext = context.applicationContext
        load()
    }

    private fun historyFile(ctx: Context): File = File(ctx.filesDir, HISTORY_FILE)

    /**
     * 供「跨会话检索」Agent 工具读取落盘历史（内部实现见 [ChatHistoryStore]）。
     *
     * 只读文件，**不触碰**主线程 [messages]（线程模型见类注释：`SnapshotStateList` 只能在
     * Compose 主线程读写）。可在任意后台线程调用；文件不存在/损坏时返回空列表。
     */
    internal fun readPersistedHistory(context: Context): List<ChatMsg> =
        ChatHistoryStore.readHistory(historyFile(context), MAX_HISTORY)

    /** 在落盘线程上执行任务，失败落日志面板（聊天历史丢失是用户可见问题，不能只写 logcat） */
    private fun submit(action: String, task: () -> Unit) {
        writer.execute {
            runCatching(task).onFailure { LogCollector.w(TAG, "$action failed", it) }
        }
    }

    /** 追加一条消息到落盘文件（JSONL 一行）。序列化在调用线程完成，文件写在后台 */
    private fun persist(msg: ChatMsg) {
        val ctx = appContext ?: return
        val line = ChatHistoryStore.toLine(msg)
        submit("persist") { ChatHistoryStore.appendLine(historyFile(ctx), line) }
    }

    /**
     * 从落盘文件恢复历史消息（App 启动时调用）。
     *
     * 读取、旧格式迁移与压实都在 [writer] 上做；只有"塞进 [messages]"这一步回主线程。
     * 顺带做一次压实：把 JSONL 里的覆盖行收敛为每 id 一行，并把旧版"整份 JSON 数组"
     * 文件迁移为 JSONL（否则每行都是一个 JSON 对象、无法增量追加）。
     */
    private fun load() {
        val ctx = appContext ?: return
        val file = historyFile(ctx)
        submit("load") {
            val loaded = ChatHistoryStore.readHistory(file)
            if (loaded.isNotEmpty()) {
                ChatHistoryStore.rewrite(file, loaded.map { ChatHistoryStore.toLine(it) })
            }
            mainHandler.post { applyLoaded(loaded) }
        }
    }

    /** 主线程：把后台读到的历史并入 [messages]，并把 id 计数器抬到最大值之上 */
    private fun applyLoaded(loaded: List<ChatMsg>) {
        if (loaded.isEmpty()) return
        // 极端竞态：加载期间用户已发消息 → 历史消息比它们旧，插到前面
        if (messages.isEmpty()) messages.addAll(loaded) else messages.addAll(0, loaded)
        loaded.forEach { if (it.id > msgIdCounter.get()) msgIdCounter.set(it.id) }
        LogCollector.i(TAG, "loaded ${loaded.size} messages from disk")
    }

    /** 清空落盘文件（内存已 clear，文件必须同步删除，否则重启后历史复活） */
    private fun persistClear() {
        val ctx = appContext ?: return
        submit("persist clear") { ChatHistoryStore.rewrite(historyFile(ctx), emptyList()) }
    }

    /** 添加一条消息并返回它 */
    fun add(isUser: Boolean, content: String, isStatus: Boolean = false): ChatMsg {
        val id = msgIdCounter.incrementAndGet()
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        val msg = ChatMsg(id, isUser, content, time, isStatus)
        messages.add(msg)
        trimIfNeeded()
        persist(msg)
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
        return msg
    }

    /**
     * 超出历史上限时裁掉最旧的消息。
     * 落盘文件不必同步裁剪：加载时 [ChatHistoryStore.parse] 同样只保留最新 [MAX_HISTORY] 条，
     * 且冷启动会做一次压实（[load]），因此被裁掉的消息不会复活、文件也不会无限增长。
     */
    private fun trimIfNeeded() {
        while (messages.size > MAX_HISTORY) {
            messages.removeAt(0)
        }
    }

    /** 清空全部聊天消息（内存 + 落盘文件，否则重启后历史复活） */
    fun clear() {
        messages.clear()
        msgIdCounter.set(0L)
        persistClear()
    }

    /**
     * Agent 过程步骤合并（思考 / 工具调用），按 [AgentStep.key] 覆盖。
     *
     * 合并进**当前这轮** AI 消息（列表末尾那条非用户、非状态消息）的 [ChatMsg.trace]：
     * 工具调用发生在正文流式生成之前，若单独发一条消息会插在正文前，把「问题」和「回答」隔开。
     *
     * 末尾不是 AI 消息时（本轮第一次过程事件，此时正文还没开始流）新建一条 content 为空的
     * AI 消息承载 —— 随后的 [appendAiDelta] 会自然地把正文追加到这一条上（它同样只看末尾那条）。
     *
     * **不落盘**：过程事件频率高（工具轮多、思考增量多），统一由 [finishTrace] 或
     * [finalizeTraceReply] 收尾时持久化，与 [appendAiDelta] 的处理口径一致。
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
     *
     * 眼镜语音走的是 `glassesAiReplyCb`（与打字的 `onReply` 不同），若直接 `add`，
     * 过程卡片会变成独立一条、回答另起一条 —— 同一轮被拆成两个气泡，和打字路径不一致。
     *
     * 仅当**末尾那条就是本轮的过程消息**（非用户、非状态、trace 非空）才合并：否则会把
     * 上一轮的回答正文覆盖掉（语音入口没有「先插一条用户消息」的保证）。
     * 合并顺带把残留的 [AgentStep.State.RUNNING] 置 OK 并落盘 —— 正常成功路径上
     * 步骤已是终态，此时只差正文这一次持久化。
     */
    fun finalizeTraceReply(reply: String) = runOnMain { finalizeTraceReplyOnMain(reply) }

    private fun finalizeTraceReplyOnMain(reply: String) {
        val last = messages.lastOrNull()
        if (last != null && !last.isUser && !last.isStatus && last.trace.isNotEmpty()) {
            val merged = last.copy(
                content = reply,
                trace = last.trace.map {
                    if (it.state == AgentStep.State.RUNNING) it.copy(state = AgentStep.State.OK) else it
                },
            )
            messages[messages.size - 1] = merged
            persist(merged)
            return
        }
        add(false, reply)
    }

    /**
     * 把动作切到主线程（[SnapshotStateList] 只能在 Compose 快照线程写）。
     *
     * 过程事件来自 AI 生成线程 / ASR 轮询线程等任意后台线程，让每个调用方自己记得切线程
     * 等于把不变量散到各处 —— 漏一处就是 `IllegalStateException` 或静默不刷新。
     */
    private inline fun runOnMain(crossinline block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post { block() }
    }

    /**
     * 收尾本轮过程：把仍处于 [AgentStep.State.RUNNING] 的步骤置为终态并落盘。
     *
     * 为什么需要它：`onTrace` 的每条步骤都是「先 RUNNING、完成后同 key 覆盖」两段式，
     * 一旦请求被用户打断、链路断掉或直接抛错，后一段就不会来了 —— 不兜底的话气泡里会永远
     * 挂着一个转圈的「进行中」步骤，看起来像卡死。
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
     * 调用方需切主线程（SnapshotStateList 写入需在 Compose 快照线程）。
     */
    fun appendAiDelta(delta: String) {
        if (delta.isEmpty()) return
        val last = messages.lastOrNull()
        // 仅追加到"正文" AI 消息：状态气泡（如"正在识别…"）不参与流式合并
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
     * 调用方需切主线程。
     *
     * 落盘只**追加一行同 id 的覆盖行**（[ChatHistoryStore.parseJsonLines] 后写覆盖先写），
     * 不再重写整份历史。
     */
    fun finalizeLastAi(fullContent: String) {
        for (i in messages.size - 1 downTo 0) {
            // 跳过状态气泡，只修正最后一条"正文" AI 消息，防止覆盖拍照流程的进度提示
            val m = messages[i]
            if (!m.isUser && !m.isStatus) {
                val updated = m.copy(content = fullContent)
                messages[i] = updated
                persist(updated)
                return
            }
        }
        add(false, fullContent)
    }
}
