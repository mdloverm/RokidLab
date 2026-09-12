package com.rokidlab.phone.store

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 聊天历史上限：超过则裁掉最旧的消息，防止长会话把内存/落盘文件撑爆 */
internal const val MAX_HISTORY = 500

/**
 * 聊天历史的落盘格式与回放逻辑。
 *
 * 纯 JVM 实现（除 `org.json` 外无 Android 依赖），因此可被 JVM 单测直接驱动
 * （见 `ChatHistoryStoreTest`）——[ChatStateHolder] 只负责"何时写、在哪个线程写"。
 *
 * **格式：JSONL** —— 每行一条完整消息。相比旧实现"每次追加都重写整份 JSON 数组"，
 * 追加只写一行，把 O(n²) 的写放大降为 O(n)。
 *
 * **同一 id 多行时后写覆盖先写**（位置沿用首次出现的位置）：[ChatStateHolder.finalizeLastAi]
 * 用完整回复修正流式消息时只需追加一行覆盖行，不必重写整份文件。
 *
 * **兼容旧格式**：首字符为 `[` 的文件按"整份 JSON 数组"解析（旧版 `chat_history.json`），
 * 调用方在冷启动时用 [rewrite] 把它迁移成 JSONL。
 *
 * **崩溃容错**：无法解析的行（例如写到一半被杀进程留下的半行）被跳过，其余历史照常恢复。
 */
internal object ChatHistoryStore {

    /** 序列化为一行 JSONL（不含换行符） */
    fun toLine(msg: ChatMsg): String = JSONObject().apply {
        put("id", msg.id)
        put("isUser", msg.isUser)
        put("content", msg.content)
        put("time", msg.time)
        put("isStatus", msg.isStatus)
        msg.imageUrl?.let { put("imageUrl", it) }
    }.toString()

    /** 是否为旧的"整份 JSON 数组"格式 */
    fun isLegacyFormat(text: String): Boolean = text.trimStart().startsWith("[")

    /**
     * 解析落盘文本（自动识别 JSONL / 旧版 JSON 数组），按 id 去重后保留最新 [maxHistory] 条。
     * 非法行与非法条目被跳过。
     */
    fun parse(text: String, maxHistory: Int = MAX_HISTORY): List<ChatMsg> {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return emptyList()
        val all = if (isLegacyFormat(trimmed)) parseLegacyArray(trimmed) else parseJsonLines(trimmed)
        return if (all.size > maxHistory) all.subList(all.size - maxHistory, all.size).toList() else all
    }

    /** 读取历史文件；文件不存在或不可读时返回空列表 */
    fun readHistory(file: File, maxHistory: Int = MAX_HISTORY): List<ChatMsg> {
        if (!file.exists()) return emptyList()
        return parse(file.readText(), maxHistory)
    }

    /** 追加一行（JSONL 增量写）。父目录不存在时自动创建 */
    fun appendLine(file: File, line: String) {
        file.parentFile?.mkdirs()
        file.appendText(line + "\n")
    }

    /** 用给定行整体重写文件（迁移旧格式 / 压实重复行 / 清空）。[lines] 为空时删除文件 */
    fun rewrite(file: File, lines: List<String>) {
        if (lines.isEmpty()) {
            file.delete()
            return
        }
        file.parentFile?.mkdirs()
        file.writeText(lines.joinToString(separator = "\n", postfix = "\n"))
    }

    private fun parseLegacyArray(text: String): List<ChatMsg> {
        val arr = runCatching { JSONArray(text) }.getOrNull() ?: return emptyList()
        val out = ArrayList<ChatMsg>(arr.length())
        for (i in 0 until arr.length()) {
            val o = runCatching { arr.getJSONObject(i) }.getOrNull() ?: continue
            fromJson(o)?.let { out.add(it) }
        }
        return out
    }

    /** 回放 JSONL：后写覆盖先写（位置不变），无 id 的行按独立条目保留 */
    private fun parseJsonLines(text: String): List<ChatMsg> {
        val out = ArrayList<ChatMsg>()
        val indexById = HashMap<Long, Int>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            val o = runCatching { JSONObject(line) }.getOrNull() ?: continue
            val msg = fromJson(o) ?: continue
            val at = if (msg.id > 0L) indexById[msg.id] else null
            if (at != null) {
                out[at] = msg
            } else {
                if (msg.id > 0L) indexById[msg.id] = out.size
                out.add(msg)
            }
        }
        return out
    }

    private fun fromJson(o: JSONObject): ChatMsg? {
        // 既无 id 又无 content 视为脏数据（如 `{}` 或被截断的残行）
        if (!o.has("id") && !o.has("content")) return null
        return ChatMsg(
            id = o.optLong("id", 0L),
            isUser = o.optBoolean("isUser", false),
            content = o.optString("content", ""),
            time = o.optString("time", ""),
            isStatus = o.optBoolean("isStatus", false),
            imageUrl = o.optString("imageUrl", "").ifBlank { null },
        )
    }
}
