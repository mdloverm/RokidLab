package com.rokidlab.phone.ai.compaction

import com.rokidlab.phone.util.HttpStatusException

/**
 * 「这一轮失败是因为**上下文超长**」的判定 —— `compaction` 接缝的 `CONTEXT_OVERFLOW` 触发条件。
 *
 * 存在的理由：改造前这件事只有一个**说不清症状**的出口。`OpenAiService.hintForStatus` 把
 * `context length` / `too long` / `max_tokens` 三个关键词混在一起，统一翻译成
 * 「这次内容太长了，换个短一点的问题再试」—— 用户唯一能做的就是把问题重打一遍，
 * 而真正能解决问题的动作（压缩历史后重试）代码里根本没有。
 *
 * ⚠️ **必须与"输出被 max_tokens 截断"分开**，这是本类最关键的取舍：
 *  - 输入超长（`maximum context length` / `reduce the length of the messages`）⇒ 压缩历史**有用**；
 *  - 输出预算太小（`max_tokens is too large` / `finish_reason=length`）⇒ 压缩历史**毫无用处**，
 *    再发一次还是同样的输出上限，只会白烧一次请求。
 * 改造前的关键词列表把两者混在一起，照着它做重试会在后一类上稳定地空转。
 * 所以这里**只认输入侧措辞**，宁可不认（回落成原来的提示文案）也不乱重试。
 *
 * 同理也要区分**同类但不同因**的 400：模型名不存在、工具 schema 非法
 * （`ToolSchemaValidator` 守的那些）都不是上下文问题，压缩只会让用户多等一轮。
 */
internal object ContextOverflow {

    /**
     * 输入侧超长关键词（小写匹配服务端错误正文）。
     *
     * 逐条对应真实报文：
     *  - `maximum context length` —— DeepSeek：*"This model's maximum context length is 65536 tokens…"*
     *  - `reduce the length` —— OpenAI/DeepSeek 共有的行动建议句
     *  - `context window` / `context_length` —— Azure、部分聚合站
     *  - `too many tokens` —— vLLM / 自建推理
     *  - `input is too long` / `prompt is too long` —— Anthropic、Gemini 兼容层
     */
    private val INPUT_OVERFLOW_MARKERS = listOf(
        "maximum context length",
        "context length",
        "context_length",
        "context window",
        "reduce the length",
        "too many tokens",
        "input is too long",
        "input too long",
        "prompt is too long",
        "prompt is too large",
    )

    /**
     * 该异常是否是「输入上下文超长」。
     *
     * 只认 4xx（服务端明确拒绝）。网络异常 / 超时不属于此列 —— 那类失败重试本身
     * 由 `chatTurnStream` 的退避逻辑处理，压缩历史解决不了任何问题。
     *
     * （413 Payload Too Large 也算：有些网关用它表达"请求体过大"。）
     */
    fun isOverflow(e: Throwable): Boolean {
        val status = e as? HttpStatusException ?: return false
        if (status.code !in 400..499) return false
        val body = status.body.lowercase()
        return INPUT_OVERFLOW_MARKERS.any { body.contains(it) }
    }

    /** 命中原因（用于日志）：返回命中的关键词；未命中返回 null */
    fun matchedMarker(e: Throwable): String? {
        val status = e as? HttpStatusException ?: return null
        if (status.code !in 400..499) return null
        val body = status.body.lowercase()
        return INPUT_OVERFLOW_MARKERS.firstOrNull { body.contains(it) }
    }
}
