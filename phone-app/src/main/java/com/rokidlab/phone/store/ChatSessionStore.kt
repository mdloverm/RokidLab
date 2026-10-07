package com.rokidlab.phone.store

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

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

    private const val TAG = "ChatSessionStore"

    /** 会话索引文件名 */
    const val INDEX_FILE = "chat_sessions.json"

    /** 会话消息文件扩展名（`chat_sessions/<id>.jsonl`） */
    const val SESSION_FILE_SUFFIX = ".jsonl"

    /** 会话消息文件所在子目录 */
    const val SESSION_DIR = "chat_sessions"

    /** 旧版单会话历史文件名（迁移来源） */
    const val LEGACY_FILE = "chat_history.json"

    /** 无标题时的占位（也是新建会话的初始标题） */
    const val DEFAULT_TITLE = "新对话"

    /** 标题与预览的截断长度（列表卡片一行放得下） */
    private const val TITLE_MAX = 18
    private const val PREVIEW_MAX = 40

    /**
     * 索引 → 内存列表（非法条目跳过，损坏一条不拖垮整个列表）。
     *
     * ⚠️ 注意"**损坏**"与"**本来就没有**"在这里**不可区分**—— 两者都返回 `emptyList()`。
     * 这就是 2026-10-06 审计里那条最严重的用户可见故障的根因：
     * 索引文件在「读改写」中途被杀 ⇒ 半截/ 0 字节 ⇒ 这里返回空 ⇒
     * `bootstrap` 判定"索引为空（=没有会话）" ⇒ 走新建会话分支 ⇒
     * **磁盘上的会话文件全都还在，但整个历史列表消失**。
     *
     * ⇒ 要区分请用 [parseIndexOrNull]（`null` = 索引**坏**了），据此从
     * [rebuildIndexFrom] 扫 [SESSION_DIR] 重建 —— 见 `ChatStateHolder.bootstrap`。
     */
    fun parseIndex(text: String): List<ChatSessionMeta> = parseIndexOrNull(text).orEmpty()

    /**
     * 同 [parseIndex]，但把"索引坏了"与"本来就没有"区分开：
     * 返回 `null` 表示**文本存在却无法解析**（损坏）；空列表表示确实没有会话。
     *
     * 📌 「索引文件不存在」与「索引文件是空串」都算**没有**（不是损坏）——
     * 首次启动就是前者，那是正常状态。
     */
    fun parseIndexOrNull(text: String): List<ChatSessionMeta>? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()
        if (!trimmed.startsWith("[")) {
            Log.w(TAG, "session index corrupted: not a JSON array (len=${trimmed.length})")
            return null
        }
        val arr = runCatching { JSONArray(trimmed) }.getOrNull() ?: run {
            Log.w(TAG, "session index corrupted: JSONArray parse failed")
            return null
        }
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
     * **从会话消息文件重建索引**（2026-10-06 审计修：索引损坏时的兜底）。
     *
     * ## 为什么需要它
     * 索引是「读旧 + 拼新 + 写回」序列的产物，落盘虽然已改成原子写，但索引**一旦损坏**
     * （早期版本的裸写、手动改文件、磁盘异常等），旧实现会把它当成"本来就没有会话"
     * ⇒ 用户的历史列表整个消失，而磁盘上的 `chat_sessions/<id>.jsonl` **全都还在**。
     * 那种"数据看起来被清空了"的故障是最难排查、也最伤用户信任的一类。
     *
     * 这里从**消息文件本身**（真相源）重建：文件在就意味着会话存在。
     *
     * ## 能恢复什么、不能恢复什么
     * - 能：会话 id、消息条数、首条消息（派生标题）、最后一条消息（预览与更新时间）
     * - 不能：[systemPrompt]（只存在索引里）、精确的 `createdAt`（用文件 mtime 近似）
     *
     * 📌 重建出来的元数据**不如原索引精确**，但远好过"列表空掉"，
     * 且只影响展示字段，不影响消息内容本身（内容始终从 `.jsonl` 读）。
     *
     * @param sessionDir [SESSION_DIR] 所在目录
     * @param readCount 读一个会话文件的消息条数（由调用方注入，避免本对象依赖历史解析）
     * @param previewOf 取预览文本（通常取最后一条非状态消息的摘要）
     * @return 重建出的列表（按 [sorted] 排好）；目录不存在或没有会话文件返回空列表
     */
    fun rebuildIndexFrom(
        sessionDir: File,
        readCount: (File) -> Int,
        titleOf: (File) -> String,
        previewOf: (File) -> String,
    ): List<ChatSessionMeta> {
        val files = sessionDir.listFiles()?.filter { it.isFile && it.name.endsWith(SESSION_FILE_SUFFIX) }
            ?: return emptyList()
        val metas = files.mapNotNull { f ->
            val id = f.name.removeSuffix(SESSION_FILE_SUFFIX)
            if (id.isEmpty()) return@mapNotNull null
            val mtime = f.lastModified().takeIf { it > 0L } ?: System.currentTimeMillis()
            ChatSessionMeta(
                id = id,
                title = titleOf(f).ifBlank { DEFAULT_TITLE },
                // createdAt 无从恢复：用文件 mtime 近似（至少能保证排序稳定）
                createdAt = mtime,
                updatedAt = mtime,
                preview = previewOf(f),
                messageCount = runCatching { readCount(f) }.getOrDefault(0),
                // systemPrompt 只存在于索引，无法重建 —— 留空（旧版本读到也不出错）
                systemPrompt = "",
            )
        }
        return sorted(metas)
    }

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
