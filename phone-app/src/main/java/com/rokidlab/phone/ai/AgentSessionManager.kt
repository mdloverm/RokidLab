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
 */
object AgentSessionManager {
    private const val TAG = "AgentSession"
    private const val SESSION_PREFS = "agent_session_prefs"
    private const val KEY_ENABLED = "session_memory_enabled"

    /** 会话过期时间：10 分钟无活动自动清空 */
    private const val EXPIRE_MS = 10 * 60 * 1000L

    /** 历史消息条数上限（user+assistant 合计，约 6 轮） */
    private const val MAX_MESSAGES = 12

    /** 历史总字符数上限 */
    private const val MAX_CHARS = 6000

    @Volatile
    private var history = mutableListOf<ChatMessage>()

    @Volatile
    private var lastActivityMs = 0L

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
    fun getHistory(): List<ChatMessage> = synchronized(this) { history.toList() }

    /** 记录一轮对话（用户输入 + AI 最终回复 + 工具调用轨迹），并裁剪超预算的旧消息 */
    fun recordTurn(userText: String, assistantReply: String, toolTrace: List<String> = emptyList()) {
        synchronized(this) {
            maybeExpireLocked()
            history.add(ChatMessage(role = "user", content = userText))
            history.add(ChatMessage(role = "assistant", content = assistantReply, toolTrace = toolTrace))
            trimLocked()
            lastActivityMs = System.currentTimeMillis()
            Log.i(TAG, "recordTurn: history=${history.size} msgs, chars=${totalCharsLocked()}" +
                if (toolTrace.isNotEmpty()) ", tools=${toolTrace.size}" else "")
        }
    }

    /** 超时自动清空（每轮对话开始时调用） */
    fun maybeExpire() {
        synchronized(this) { maybeExpireLocked() }
    }

    /** 手动清空会话 */
    fun clear() {
        synchronized(this) {
            history.clear()
            lastActivityMs = 0
            Log.i(TAG, "cleared")
        }
    }

    private fun maybeExpireLocked() {
        if (lastActivityMs > 0 && System.currentTimeMillis() - lastActivityMs > EXPIRE_MS) {
            Log.i(TAG, "session expired (${EXPIRE_MS / 60000}min idle), cleared")
            history.clear()
            lastActivityMs = 0
        }
    }

    /**
     * 双重预算裁剪：条数超上限或总字符超上限时丢弃最旧消息。
     * 丢弃前把最旧一轮（user+assistant）压缩成一条 system 摘要保留要点（各取前 40 字），
     * 避免纯 FIFO 丢失关键早期信息；摘要本身也是一条消息，后续若仍超预算会继续压缩更早轮。
     * 已是摘要的最旧消息直接丢弃，防止摘要无限堆积。收敛性：每轮净减至少 1 条。
     */
    private fun trimLocked() {
        while (history.size > MAX_MESSAGES || totalCharsLocked() > MAX_CHARS) {
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
                    Log.d(TAG, "trim: compressed oldest turn into summary")
                }
                // 已是摘要或孤立的 assistant：直接丢弃，避免摘要堆叠/无限循环
                else -> {
                    val dropped = history.removeAt(0)
                    Log.d(TAG, "trim: dropped oldest ${dropped.role} (${dropped.content.length} chars)")
                }
            }
        }
    }

    private fun totalCharsLocked() = history.sumOf { it.content.length }
}
