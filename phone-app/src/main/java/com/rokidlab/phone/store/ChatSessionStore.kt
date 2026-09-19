package com.rokidlab.phone.store

import org.json.JSONArray
import org.json.JSONObject

/**
 * 会话元数据（存 `chat_sessions.json` 索引，不随消息增长）。
 *
 * 消息本体仍按 [ChatHistoryStore] 的 JSONL 格式存 `chat_sessions/<id>.jsonl`，
 * 索引里只保留列表页需要的展示字段 —— 列表渲染不必打开每个会话文件。
 */
internal data class ChatSessionMeta(
    val id: String,
    /** 会话标题：默认由首条用户消息派生，用户可重命名 */
    val title: String,
    val createdAt: Long,
    /** 最后一条消息时间，列表按它倒序 */
    val updatedAt: Long,
    /** 列表预览：最后一条非状态消息的摘要 */
    val preview: String,
    val messageCount: Int,
    /**
     * **本会话**的附加提示词（用户为这一次对话单独指定；空串 = 没设）。
     *
     * 存进索引而不是单独文件：它随会话走、按会话读，索引本来就是"列表页需要的展示字段"的家；
     * 而且它在**每轮请求**都要被读到（拼进 system 消息），放索引里只需读一个小文件。
     *
     * 语义是**追加**到全局人设之后（见 `OpenAiService.buildSystemMessage`），不是替换 ——
     * 理由写在那个参数上。
     */
    val systemPrompt: String = "",
)

/**
 * 多会话元数据的落盘格式与派生逻辑。
 *
 * 与 [ChatHistoryStore] 同样是**纯 JVM 实现**（除 `org.json` 外无 Android 依赖），
 * 因此可被 JVM 单测直接驱动；[ChatStateHolder] 只负责"何时写、在哪个线程写"。
 *
 * 存储布局：
 * ```
 * filesDir/
 *   chat_sessions.json          ← 索引（本对象负责）
 *   chat_sessions/<id>.jsonl    ← 每个会话的消息（ChatHistoryStore 负责）
 *   chat_history.json           ← 旧版单会话历史，启动时迁移后删除
 * ```
 *
 * **旧数据迁移**：[ChatStateHolder.init] 在索引缺失但 [LEGACY_FILE] 存在时，
 * 把它迁成"一个会话"再删除旧文件，保证升级用户的历史不丢。
 */
internal object ChatSessionStore {

    /** 会话索引文件名 */
    const val INDEX_FILE = "chat_sessions.json"

    /** 会话消息文件所在子目录 */
    const val SESSION_DIR = "chat_sessions"

    /** 旧版单会话历史文件名（迁移来源） */
    const val LEGACY_FILE = "chat_history.json"

    /** 无标题时的占位（也是新建会话的初始标题） */
    const val DEFAULT_TITLE = "新对话"

    /** 标题与预览的截断长度（列表卡片一行放得下） */
    private const val TITLE_MAX = 18
    private const val PREVIEW_MAX = 40

    /** 索引 → 内存列表（非法条目跳过，损坏一条不拖垮整个列表） */
    fun parseIndex(text: String): List<ChatSessionMeta> {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || !trimmed.startsWith("[")) return emptyList()
        val arr = runCatching { JSONArray(trimmed) }.getOrNull() ?: return emptyList()
        val out = ArrayList<ChatSessionMeta>(arr.length())
        for (i in 0 until arr.length()) {
            val o = runCatching { arr.getJSONObject(i) }.getOrNull() ?: continue
            val id = o.optString("id").trim()
            if (id.isEmpty()) continue
            out.add(
                ChatSessionMeta(
                    id = id,
                    title = o.optString("title").ifBlank { DEFAULT_TITLE },
                    createdAt = o.optLong("createdAt", 0L),
                    updatedAt = o.optLong("updatedAt", 0L),
                    preview = o.optString("preview"),
                    messageCount = o.optInt("messageCount", 0),
                    systemPrompt = o.optString("systemPrompt"),
                )
            )
        }
        return out
    }

    /** 内存列表 → 索引文本 */
    fun toIndexJson(sessions: List<ChatSessionMeta>): String = JSONArray().apply {
        sessions.forEach { s ->
            put(
                JSONObject().apply {
                    put("id", s.id)
                    put("title", s.title)
                    put("createdAt", s.createdAt)
                    put("updatedAt", s.updatedAt)
                    put("preview", s.preview)
                    put("messageCount", s.messageCount)
                    // 空串不落字段（绝大多数会话没设，老版本读到也不出错；缺字段 = 空）
                    if (s.systemPrompt.isNotBlank()) put("systemPrompt", s.systemPrompt)
                }
            )
        }
    }.toString()

    /**
     * 会话排序：最近更新的在前。
     *
     * `updatedAt` 相同时（同一毫秒内连建两个会话）用 [ChatSessionMeta.id] 兜底比较，
     * 保证排序稳定 —— 不稳定排序会让列表项在重组时跳动。
     */
    fun sorted(list: List<ChatSessionMeta>): List<ChatSessionMeta> =
        list.sortedWith(compareByDescending<ChatSessionMeta> { it.updatedAt }.thenByDescending { it.id })

    /**
     * 由消息列表派生标题：取首条用户正文（跳过状态气泡与图片-only 消息）的前 [TITLE_MAX] 字。
     * 一条用户消息都没有时返回 [DEFAULT_TITLE]（新建后还没说话的会话）。
     */
    fun titleFrom(messages: List<ChatMsg>): String {
        val first = messages.firstOrNull { it.isUser && !it.isStatus && it.content.isNotBlank() }
            ?: return DEFAULT_TITLE
        return titleFromText(first.content)
    }

    /** 文本 → 标题（压平换行、去首尾空白、截断） */
    fun titleFromText(text: String): String = compact(text, TITLE_MAX).ifBlank { DEFAULT_TITLE }

    /**
     * 由消息列表派生列表预览：取最后一条非状态消息。
     *
     * 图片消息（[ChatMsg.imageUrl] 非空且无 caption）没有正文可显示，用「[图片]」占位 ——
     * 否则这类会话在列表里会呈现为空白行。
     */
    fun previewFrom(messages: List<ChatMsg>): String {
        val last = messages.lastOrNull { !it.isStatus } ?: return ""
        if (last.content.isBlank() && last.imageUrl != null) return "[图片]"
        val who = if (last.isUser) "我：" else ""
        return who + compact(last.content, PREVIEW_MAX)
    }

    /** 压平空白并截断（超出加省略号） */
    private fun compact(text: String, max: Int): String {
        val flat = text.replace(Regex("\\s+"), " ").trim()
        return if (flat.length > max) flat.take(max - 1) + "…" else flat
    }
}
