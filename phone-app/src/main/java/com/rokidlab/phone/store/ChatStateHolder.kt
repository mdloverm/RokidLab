package com.rokidlab.phone.store

import android.content.Context
import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "ChatStateHolder"
private const val HISTORY_FILE = "chat_history.json"

/** 聊天历史上限：超过则裁掉最旧的消息，防止长会话把内存/落盘 JSON 撑爆 */
private const val MAX_HISTORY = 500

/**
 * 聊天消息状态全局持有器。
 *
 * 解决切到其他功能页面后聊天对话被清空的问题：原实现用 [ChatModule] 内的
 * `remember { mutableStateOf(listOf<ChatMsg>()) }`，离开组合即销毁。
 * 改为单例持有 [SnapshotStateList]，Compose 跨页面共享，状态持久。
 *
 * 另支持落盘持久化（App 重启不丢失）与流式增量更新（AI 回复边生成边显示）：
 *   - [init] 在 Application.onCreate 调用，加载历史记录
 *   - [add]/[clear] 写入后自动落盘
 *   - [appendAiDelta] 流式增量累积（不落盘，高频），[finalizeLastAi] 完成后统一持久化
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

    /** 应用启动时初始化上下文并加载落盘历史 */
    fun init(context: Context) {
        appContext = context.applicationContext
        load()
    }

    /** 落盘当前消息列表到应用私有文件（失败静默，不阻塞 UI） */
    private fun persist() {
        val ctx = appContext ?: return
        runCatching {
            val arr = JSONArray()
            messages.forEach { m ->
                arr.put(JSONObject().apply {
                    put("id", m.id)
                    put("isUser", m.isUser)
                    put("content", m.content)
                    put("time", m.time)
                    put("isStatus", m.isStatus)
                })
            }
            File(ctx.filesDir, HISTORY_FILE).writeText(arr.toString())
        }.onFailure { Log.w(TAG, "persist failed: ${it.message}") }
    }

    /** 从落盘文件恢复历史消息（App 启动时调用） */
    private fun load() {
        val ctx = appContext ?: return
        val file = File(ctx.filesDir, HISTORY_FILE)
        if (!file.exists()) return
        runCatching {
            val arr = JSONArray(file.readText())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val msg = ChatMsg(
                    id = o.optLong("id", 0),
                    isUser = o.optBoolean("isUser", false),
                    content = o.optString("content"),
                    time = o.optString("time"),
                    isStatus = o.optBoolean("isStatus", false),
                )
                messages.add(msg)
                if (msg.id > msgIdCounter.get()) msgIdCounter.set(msg.id)
            }
            Log.i(TAG, "loaded ${messages.size} messages from disk")
        }.onFailure { Log.w(TAG, "load failed: ${it.message}") }
    }

    /** 添加一条消息并返回它 */
    fun add(isUser: Boolean, content: String, isStatus: Boolean = false): ChatMsg {
        val id = msgIdCounter.incrementAndGet()
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        val msg = ChatMsg(id, isUser, content, time, isStatus)
        messages.add(msg)
        trimIfNeeded()
        persist()
        return msg
    }

    /** 超出历史上限时裁掉最旧的消息 */
    private fun trimIfNeeded() {
        while (messages.size > MAX_HISTORY) {
            messages.removeAt(0)
        }
    }

    /** 清空全部聊天消息 */
    fun clear() {
        messages.clear()
        msgIdCounter.set(0L)
        persist()
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
     */
    fun finalizeLastAi(fullContent: String) {
        for (i in messages.size - 1 downTo 0) {
            // 跳过状态气泡，只修正最后一条"正文" AI 消息，防止覆盖拍照流程的进度提示
            val m = messages[i]
            if (!m.isUser && !m.isStatus) {
                messages[i] = m.copy(content = fullContent)
                persist()
                return
            }
        }
        add(false, fullContent)
    }
}
