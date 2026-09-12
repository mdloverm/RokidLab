package com.rokidlab.phone.store

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
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
