package com.rokidlab.phone.ai.session

import com.rokidlab.phone.ai.ChatMessage
import com.rokidlab.phone.ai.TokenUsage
import com.rokidlab.phone.ai.compaction.CompactionResult
import com.rokidlab.phone.ai.compaction.CompactionTrigger

/**
 * 一轮对话的**记录句柄**（由 [AgentSessionStore.beginTurn] 产出）。
 *
 * ★ 为什么是一个句柄而不是"在全局单例上按轮号记"：句柄在创建的瞬间就**抓住了那个会话说**，
 *   所以"生成到一半用户切到别的会话"不会把这一轮的事件写进新会话的时间线。
 *   改造前 `AgentSessionManager` 是一个全局单例，切会话不改变它的记忆 ——
 *   真机症状就是「切到另一个会话，AI 还记得上一个会话聊了什么」。
 *
 * ★ 为什么把 `historyForRequest()` 也挂在这里：请求装配必须读**同一份**会话，
 *   而"读哪个会话"与"写哪个会话"必须是同一个答案，否则事件流与请求各说各话。
 *
 * 生命周期：`beginTurn` → 若干 `userMessage`/`toolCall`/`toolResult`/`contextInject`/`attempt`
 * → 恰好一次 `finish`。`finish` 之后的调用不会报错，但也不会再产生新的可见历史。
 */
internal class AgentTurn internal constructor(
    private val store: AgentSessionStore,
    /** 本轮轮号（由 [AgentSessionStore.beginTurn] 唯一分配） */
    val turn: Int,
    /** 本轮用户输入的来源（落进 [UserMessage]，供回溯"这句是说的还是打的"） */
    val source: MessageSource,
) {

    // ===== 本轮的模型调用成本（累加，收尾时落到 [TurnEnd] 上）=====
    private var promptTotal = 0
    private var completionTotal = 0

    /** 服务端**至少给过一次** usage 才报数字；一次都没给就保持 null（不知道，不是 0） */
    private var usageSeen = false
    private var modelCalls = 0

    /** 本轮用户说了什么 —— **轮次一开始就落盘**，这样进程被杀也不会丢掉"用户问了什么" */
    fun userMessage(text: String) = store.appendUserMessage(turn, text, source)

    /** 本轮注入到提示词的上下文（长期记忆 / 知识库 / 技能清单 / 续做任务 / 预算说明） */
    fun contextInject(origin: String, content: String?) =
        store.appendContextInject(turn, origin, content.orEmpty())

    fun toolCall(callId: String, name: String, args: String) =
        store.appendToolCall(turn, callId, name, args)

    fun toolResult(callId: String, name: String, content: String) =
        store.appendToolResult(turn, callId, name, content)

    /** 一次失败 / 被抢占的尝试（不进模型历史，但让"这轮为什么慢/为什么报错"可归因） */
    fun attempt(outcome: AttemptOutcome, detail: String? = null) =
        store.appendAttempt(turn, outcome, detail)

    /**
     * 记一次模型调用（**每次成功的 `chatTurn*` 之后都调**，含工具循环里的中间轮）。
     *
     * ★ 为什么要累加而不是取最后一次：一轮带工具循环 = **多次请求**，而输入 token 每轮都要重发
     *   （整段 system + 历史），它才是成本大头。只报最后一次会把 6 次请求算成 1 次，
     *   用户看到"这么便宜"就再也解释不了账单。
     *
     * ⚠️ 未成功的调用不记（拿不到它的 usage）—— 编一个数字比不报更糟。
     */
    fun recordModelCall(usage: TokenUsage?) {
        modelCalls++
        if (usage == null) return
        usageSeen = true
        promptTotal += usage.promptTokens
        completionTotal += usage.completionTokens
    }

    /** 请求装配用的历史（**不含**本轮 —— 本轮用户消息要单独构造，多模态时是分片数组） */
    fun historyForRequest(): List<ChatMessage> = store.historyForRequest(turn)

    /** 溢出恢复入口：历史过长被服务端拒后强压一次（`compaction` 接缝的 `CONTEXT_OVERFLOW`） */
    fun compactNow(trigger: CompactionTrigger): CompactionResult? = store.compactNow(trigger)

    /**
     * 收尾本轮，**恰好调用一次**。
     *
     * @param reply 最终回复；被打断 / 失败时传 null（本轮会被声明为"不算数"，见
     *   [AgentSessionStore.finishTurn]）
     */
    fun finish(reason: TurnEndReason, reply: String? = null, detail: String? = null): CompactionResult? =
        store.finishTurn(
            turn = turn,
            reply = reply,
            reason = reason,
            detail = detail,
            promptTokens = if (usageSeen) promptTotal else null,
            completionTokens = if (usageSeen) completionTotal else null,
            // 调用次数是**我们自己的事实**（不依赖服务端），所以哪怕拿不到 usage 也报
            modelCalls = if (modelCalls > 0) modelCalls else null,
        )
}
