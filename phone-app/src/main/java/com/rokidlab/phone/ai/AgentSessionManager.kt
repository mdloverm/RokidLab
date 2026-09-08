package com.rokidlab.phone.ai

import android.content.Context
import android.util.Log

/**
 * AI Agent 会话记忆管理器。
 *
 * 维护跨请求的多轮对话历史（user/assistant 文本），使 AI 具备上下文记忆：
 *   - 每轮对话（sendAiTextViaLink）开始时取历史注入 messages，结束后记录本轮
 *   - 超时自动清空（语音场景 10 分钟无活动视为新会话）
 *   - 按消息条数 + 字符数双重预算裁剪，防止上下文无限膨胀（省 token + 防溢出）
 *
 * 工具调用轮次的中间消息（assistant.tool_calls / tool 结果）只在当次请求内有效，
 * 不跨请求保留——工具结果已反映在最终 assistant 回复文本中。
 *
 * 不记录的场景：拍照答题（startPhotoAsk，一次性问答且注入答题指令），
 * 通过 sendAiTextMessage(recordHistory = false) 控制。
 *
 * 历史裁剪/过期等纯逻辑在 [AgentSessionHistory]（无 Android 依赖，可单测），
 * 本对象只负责开关持久化与日志包装。
 */
object AgentSessionManager {
    private const val TAG = "AgentSession"
    private const val SESSION_PREFS = "agent_session_prefs"
    private const val KEY_ENABLED = "session_memory_enabled"

    /** 会话记忆纯逻辑（注入 trim 日志回调保持与原实现一致的 Log.d 输出） */
    private val store = AgentSessionHistory(
        onTrim = { Log.d(TAG, it) },
    )

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

    /** 取当前历史快照（每轮开始前先调 [maybeExpire]） */
    fun getHistory(): List<ChatMessage> = store.getHistory()

    /** 记录一轮对话（用户输入 + AI 最终回复 + 工具调用轨迹），并裁剪超预算的旧消息 */
    fun recordTurn(userText: String, assistantReply: String, toolTrace: List<String> = emptyList()) {
        val stats = store.recordTurn(userText, assistantReply, toolTrace)
        Log.i(TAG, "recordTurn: history=${stats.size} msgs, chars=${stats.chars}" +
            if (toolTrace.isNotEmpty()) ", tools=${toolTrace.size}" else "")
    }

    /** 超时自动清空（每轮对话开始时调用） */
    fun maybeExpire() {
        if (store.maybeExpire()) {
            Log.i(TAG, "session expired (10min idle), cleared")
        }
    }

    /** 手动清空会话 */
    fun clear() {
        store.clear()
        Log.i(TAG, "cleared")
    }
}

/**
 * 会话历史纯逻辑（不依赖 Android，时钟与裁剪日志均可注入，便于 JVM 单测）。
 *
 * 语义（与生产约束一致）：
 *   - 每轮记录 user + assistant 两条消息，assistant 可带工具轨迹 [ChatMessage.toolTrace]
 *   - 10 分钟无活动自动清空（[maybeExpire]）
 *   - 条数上限 12 / 字符上限 6000，超限时把最旧一轮压缩成 system 摘要，收敛性：每轮净减至少 1 条
 */
internal class AgentSessionHistory(
    private val clock: () -> Long = System::currentTimeMillis,
    private val onTrim: (String) -> Unit = {},
) {
    /** 会话过期时间：10 分钟无活动自动清空 */
    private val expireMs = 10 * 60 * 1000L

    /** 历史消息条数上限（user+assistant 合计，约 6 轮） */
    private val maxMessages = 12

    /** 历史总字符数上限 */
    private val maxChars = 6000

    private val history = mutableListOf<ChatMessage>()
    private var lastActivityMs = 0L

    /** 记录后的快照统计（供上层日志展示） */
    data class Stats(val size: Int, val chars: Int)

    /** 取当前历史快照 */
    fun getHistory(): List<ChatMessage> = synchronized(this) { history.toList() }

    /**
     * 记录一轮对话（用户输入 + AI 最终回复 + 工具调用轨迹），并裁剪超预算的旧消息。
     * @param nowMs 当前时间戳（默认取注入时钟；单测传入确定值验证过期/裁剪）
     */
    fun recordTurn(
        userText: String,
        assistantReply: String,
        toolTrace: List<String> = emptyList(),
        nowMs: Long = clock(),
    ): Stats {
        synchronized(this) {
            maybeExpireLocked(nowMs)
            history.add(ChatMessage(role = "user", content = userText))
            history.add(ChatMessage(role = "assistant", content = assistantReply, toolTrace = toolTrace))
            trimLocked()
            lastActivityMs = nowMs
            return Stats(history.size, totalCharsLocked())
        }
    }

    /**
     * 超时自动清空（每轮对话开始时调用）。
     * @return 本次是否触发了过期清空
     */
    fun maybeExpire(nowMs: Long = clock()): Boolean = synchronized(this) { maybeExpireLocked(nowMs) }

    /** 手动清空会话 */
    fun clear() {
        synchronized(this) {
            history.clear()
            lastActivityMs = 0
        }
    }

    private fun maybeExpireLocked(nowMs: Long): Boolean {
        if (lastActivityMs > 0 && nowMs - lastActivityMs > expireMs) {
            history.clear()
            lastActivityMs = 0
            return true
        }
        return false
    }

    /**
     * 双重预算裁剪：条数超上限或总字符超上限时丢弃最旧消息。
     * 丢弃前把最旧一轮（user+assistant）压缩成一条 system 摘要保留要点（各取前 40 字），
     * 避免纯 FIFO 丢失关键早期信息；摘要本身也是一条消息，后续若仍超预算会继续压缩更早轮。
     * 已是摘要的最旧消息直接丢弃，防止摘要无限堆积。收敛性：每轮净减至少 1 条。
     */
    private fun trimLocked() {
        while (history.size > maxMessages || totalCharsLocked() > maxChars) {
            if (history.size <= 2) break
            when (history[0].role) {
                "user" -> {
                    val u = history.removeAt(0)
                    val a = if (history.isNotEmpty() && history[0].role == "assistant") history.removeAt(0) else null
                    val summary = ChatMessage(
                        role = "system",
                        content = "[更早对话] 用户问\"${u.content.take(40)}\"" +
                            (a?.let { "，AI答\"${it.content.take(40)}\"" } ?: ""),
                    )
                    history.add(0, summary)
                    onTrim("trim: compressed oldest turn into summary")
                }
                // 已是摘要或孤立的 assistant：直接丢弃，避免摘要堆叠/无限循环
                else -> {
                    val dropped = history.removeAt(0)
                    onTrim("trim: dropped oldest ${dropped.role} (${dropped.content.length} chars)")
                }
            }
        }
    }

    private fun totalCharsLocked() = history.sumOf { it.content.length }
}
