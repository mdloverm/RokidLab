package com.rokidlab.phone.ai

import android.util.Log
import com.rokidlab.phone.util.HttpClient
import org.json.JSONArray
import org.json.JSONObject

/**
 * OpenAI 兼容的 AI 服务封装。
 *
 * 支持任意 OpenAI 协议端点（DeepSeek / 通义千问 / Kimi / 智谱 / 本地 Ollama 等），
 * 通过配置 baseUrl + apiKey + model 即可切换服务商。
 *
 * 端点: {baseUrl}/chat/completions（baseUrl 兼容带 /v1 与不带两种写法）
 * 认证: Bearer <api_key>
 *
 * 用法:
 *   val service = OpenAiService(apiKey = "sk-xxx", model = "deepseek-chat", baseUrl = "https://api.deepseek.com")
 *   val reply = service.chat("你好")
 */
class OpenAiService(
    private val apiKey: String,
    private val model: String = "deepseek-chat",
    private val baseUrl: String = "https://api.deepseek.com",
    /** 单次请求读超时（毫秒）。本地 Ollama 首次加载大模型/思考模型首字可能远超 30s，
     *  调用方按需调大（如 180s）；远程服务用默认 30s 避免长时间无回复 */
    private val readTimeoutMs: Int = 30000,
    /** 用户自定义附加请求参数（JSON 对象）：逐字段合并进每次请求体，用户字段覆盖服务端默认值。
     *  本地 Ollama 高级调参用，如 {"think": false, "options": {"num_ctx": 2048}, "temperature": 0.3}；
     *  null=不附加。结构性字段 model/messages/stream/tools 不允许覆盖，避免破坏会话与流式链路 */
    private val extraBody: JSONObject? = null,
    /** 是否开启模型长思考（仅 DeepSeek V4/V3.2 系生效）：
     *  false=请求附加 thinking disabled（默认，reasoning 会吞掉输出预算导致 finish=length 空轮，
     *  工具驱动会话必须关闭才能稳定工具调用）；true=不附加（服务端思考默认开启），
     *  此时调用方需把每轮返回的 reasoning_content 原样回传历史以通过多轮校验 */
    private val thinkingEnabled: Boolean = false,
) {
    companion object {
        private const val TAG = "OpenAiService"
    }

    /** 将用户自定义 JSON 逐字段合并进请求体；结构性字段（model/messages/stream/tools）忽略 */
    private fun mergeExtraBody(body: JSONObject, extra: JSONObject?) {
        if (extra == null) return
        val iter = extra.keys()
        while (iter.hasNext()) {
            val k = iter.next()
            if (k == "model" || k == "messages" || k == "stream" || k == "tools") continue
            body.put(k, extra.get(k))
        }
    }

    /**
     * 发送对话请求，返回 AI 回复文本。
     * 在 IO 线程调用（阻塞方法）。
     *
     * @param userMessage 用户输入的文字
     * @param history 之前的对话历史（可选，用于多轮对话）
     * @param contextText 知识库检索出的参考资料（可选，注入 system 提示词实现 RAG）
     * @return AI 回复文字，失败时抛出异常
     */
    fun chat(
        userMessage: String,
        history: List<ChatMessage> = emptyList(),
        contextText: String? = null,
    ): String {
        return chatTurn(userMessage, history, contextText).content.orEmpty()
    }

    /**
     * 构造 system 消息：Agent 人设 + 工具使用准则 + 眼镜播报风格，
     * 可选注入知识库检索资料（RAG）与额外指令（如答题要求）
     *
     * @param memories 长期记忆文本（跨会话的用户事实/偏好，注入 <memories> 段）；
     *                 null 表示无记忆不注入（省 token）
     * @param skills 用户自定义技能清单（注入 <skills> 段，name+description 第 1 层披露）；
     *               模型命中描述时须调用 load_skill 加载完整步骤再执行。null 表示无技能不注入
     * @param localMode 本地轻量模式：使用精简人设（无工具准则段），明确告知模型无联网/无工具，
     *                  涉及设备操作/实时信息/联网任务时如实说明并引导切回在线模式（配合空工具集使用）
     */
    fun buildSystemMessage(
        contextText: String? = null,
        instruction: String? = null,
        memories: String? = null,
        skills: String? = null,
        localMode: Boolean = false,
    ): JSONObject {
        val systemMsg = JSONObject()
        systemMsg.put("role", "system")
        val systemContent = buildString {
            if (localMode) {
                append("你是乐奇，运行在手机上的 Rokid 眼镜 AI 助理。用户通过眼镜与你语音对话，你的回复会显示在眼镜屏幕上并语音播报。")
                append("\n\n你当前运行在本地轻量模式（本地小模型，无联网、无工具能力）。必须遵守：")
                append("\n- 涉及设备操作或实时信息的内容（眼镜电量/设备信息、打开应用、播放/停止音乐、显示歌词、定时提醒、拍照等）：无法执行，如实告知，不要编造结果")
                append("\n- 需要联网的内容（最新资讯、天气、网页搜索等）：无法执行，如实告知，不要编造结果")
                append("\n- 需要写代码、生成/安装 AI 应用等复杂任务：无法执行，如实告知，不要编造结果")
                append("\n- 这类请求请统一建议用户切换到联网的智能模式；其余闲聊与常识问答直接作答")
            } else {
                append("你是乐奇，运行在用户手机上的 Rokid 眼镜 AI 助理。用户通过眼镜与你语音对话，")
                append("你的回复会在眼镜屏幕上显示并通过语音播报给用户。")
                append("\n\n【工具使用准则】")
                append("\n- 涉及实时信息（时间、电量、应用列表等）或设备操作（打开应用、播放音乐、停止播放、显示歌词、设定时提醒等）时，必须调用对应工具获取真实结果，严禁编造")
                append("\n- 可以在一次回答中连续调用多个工具来完成多步任务（如先查时间再设定时提醒）")
                append("\n- 工具返回失败或查不到时，如实告知用户，不要假装成功")
                append("\n- 闲聊、常识问答、创作类问题不需要调用工具，直接回答")
                append("\n- 结合对话历史理解上下文：用户说「再来一首」「它是什么意思」时，指代的是之前聊到的内容")
                append("\n- 当用户表达了需要长期记住的个人事实或偏好（如称呼、喜欢的歌手、常用应用、作息习惯）时，调用 manage_memory 工具记住，以便后续对话延续")
                append("\n- 当用户让你写代码、生成页面/应用或输出项目文件时：先用 save_code_file 工具把每个文件写入手机「下载/项目名/」目录（一次一个文件、逐个调用），生成每个文件前告知「正在生成 文件名…」，成功后告知「文件名 生成成功」；眼镜端最终只做简短结论播报（如「已生成 4 个文件，保存在下载目录的 xxx 项目」），严禁把大段代码原文直接当作回复发给用户")
            }
            append("\n\n【回复风格】（眼镜语音播报场景）")
            append("\n- 口语化、简短自然，一般不超过 3 句话")
            append("\n- 纯文本：不要用 markdown、序号、表情符号、换行符")
            append("\n- 直接给结论，不要复述问题，不要描述「根据工具结果」这类过程")
            if (!memories.isNullOrBlank()) {
                append("\n\n<memories>\n")
                append(memories)
                append("\n</memories>\n以下是与用户相关的长期记忆，回答时如有涉及请据此个性化；与当前问题无关可忽略。")
            }
            if (!skills.isNullOrBlank()) {
                append("\n\n<skills>\n")
                append(skills)
                append("\n</skills>")
            }
            if (!contextText.isNullOrBlank()) {
                append("\n\n以下是知识库中检索到的参考资料，请优先基于这些资料回答用户问题；如果资料与问题无关，可忽略：\n")
                append(contextText)
            }
            if (!instruction.isNullOrBlank()) {
                append("\n\n请遵守以下答题要求：\n")
                append(instruction)
            }
        }
        systemMsg.put("content", systemContent)
        return systemMsg
    }

    /**
     * 带工具（function calling）的对话请求：由本方法自动组装 system + history + user 消息。
     * 返回的结构中若 [ChatTurn.toolCalls] 非空，调用方需执行工具并把结果以 tool 消息
     * 追加进 messages 后再调用 [chatTurn]（底层重载）继续请求，直到返回纯文本回复。
     */
    fun chatTurn(
        userMessage: String,
        history: List<ChatMessage> = emptyList(),
        contextText: String? = null,
        tools: List<JSONObject>? = null,
    ): ChatTurn {
        val messages = JSONArray()
        messages.put(buildSystemMessage(contextText))
        // 历史对话
        history.forEach { msg ->
            val m = JSONObject()
            m.put("role", msg.role)
            m.put("content", msg.content)
            messages.put(m)
        }
        // 当前用户消息
        val userMsg = JSONObject()
        userMsg.put("role", "user")
        userMsg.put("content", userMessage)
        messages.put(userMsg)
        return chatTurn(messages, tools)
    }

    /**
     * 底层请求：直接传入完整 messages 数组（含工具回填消息），并解析返回的 tool_calls。
     * 在 IO 线程调用（阻塞方法）。
     *
     * @param readTimeout 单次 HTTP 读超时（毫秒），默认用构造 [readTimeoutMs]；
     *                    大代码/长工具参数生成可能超过默认 30s，调用方可传更大值（如 120s）。
     * @param attempts    总请求次数（含重试），默认 2（一次失败即整轮失败对用户太不友好）；
     *                    长超时场景建议传 1（单次 120s 足够，重试只会翻倍等待）。
     */
    fun chatTurn(
        messages: JSONArray,
        tools: List<JSONObject>? = null,
        readTimeout: Int? = null,
        attempts: Int = 2,
    ): ChatTurn {
        // 网络瞬断/服务商抖动时重试一次：一次失败即整轮失败对用户太不友好（30s 超时后直接没回复）
        var lastError: Exception? = null
        repeat(attempts) { attempt ->
            try {
                return chatTurnOnce(messages, tools, readTimeout)
            } catch (e: Exception) {
                lastError = e
                if (attempt == 0 && attempts > 1) {
                    Log.w(TAG, "chatTurn attempt 1 failed: ${e.message}, retrying")
                }
            }
        }
        throw lastError ?: Exception("chatTurn failed")
    }

    /**
     * 流式对话请求（stream=true，SSE）：实时通过 [onDelta] 推送 content 增量，
     * 流结束后返回完整 ChatTurn（含 tool_calls 增量合并结果）。
     *
     * 用于降低首字延迟、让 UI 边生成边显示。工具调用轮 content 增量通常为空
     * （只有 tool_calls），最终回复轮 content 流式增量推送。
     * 在 IO 线程调用（阻塞方法，直至流关闭）。
     *
     * 断线重连（指数退避）：请求失败时按 [retryBaseDelayMs]×2^attempt 退避后整轮重放，
     * 重放安全边界 = 「尚未向 UI 推送过任何 content 增量」——
     *   - 首字前失败：完全安全（用户未看到任何输出）；
     *   - 工具调用增量下发中途断线（content 仍为空）：工具尚未执行、无副作用，重放安全；
     *   - content 已部分推送后断线：重放会导致重复输出，不重试、直接抛出（OpenAI 协议无断点续传，
     *     真正的中途续传需要服务端支持 last-event-id，当前端点不支持）。
     *
     * @param isCancelled 流式读取期间周期性检查的取消回调（供上层"用户打断"使用）；
     *                    返回 true 时停止读取并断开连接。若服务端暂无数据推送而阻塞在
     *                    readLine，最迟在 [readTimeoutMs] 后超时返回（本地模型首字/模型加载
     *                    可能远慢于远程，调用方对本地端点已调大超时；远程保持 30s 使打断
     *                    让出时间有界）。返回半截数据由调用方依据自己的取消标志丢弃。
     * @param retryBaseDelayMs 首次重试的退避基数（毫秒），逐次翻倍，上限 4s。
     */
    fun chatTurnStream(
        messages: JSONArray,
        tools: List<JSONObject>? = null,
        onDelta: ((String) -> Unit)? = null,
        isCancelled: (() -> Boolean)? = null,
        /**
         * 失败重试总次数（含首次，默认 2）。
         * 远程服务商偶发抖动时建议 3（配合指数退避）；本地 Ollama 首字慢（思考模型/
         * 首次加载）时重试只会让模型重复加载、等待翻倍，应传 1（不重试）。
         */
        retryAttempts: Int = 2,
        retryBaseDelayMs: Long = 500,
    ): ChatTurn {
        var lastError: Exception? = null
        repeat(retryAttempts) { attempt ->
            val accumulator = SseStreamAccumulator(onDelta)
            try {
                return streamOnce(messages, tools, accumulator, isCancelled)
            } catch (e: Exception) {
                lastError = e
                val retryable = attempt < retryAttempts - 1
                // 重放安全判定：只要还没有任何 content 增量推给 UI，整轮重放无副作用
                if (retryable && !accumulator.hasEmittedContent()) {
                    val delay = (retryBaseDelayMs shl attempt).coerceAtMost(4000L)
                    Log.w(
                        TAG,
                        "chatTurnStream attempt ${attempt + 1}/$retryAttempts failed " +
                            "(no content emitted yet, toolCalls partial=${accumulator.hasStarted()}): " +
                            "${e.message}, reconnecting in ${delay}ms",
                    )
                    if (delay > 0) {
                        try {
                            Thread.sleep(delay)
                        } catch (_: InterruptedException) {
                            Thread.currentThread().interrupt()
                            throw e
                        }
                    }
                } else {
                    if (retryable) {
                        Log.w(TAG, "chatTurnStream failed after partial content emitted, cannot safely replay: ${e.message}")
                    }
                    throw e
                }
            }
        }
        throw lastError ?: Exception("chatTurnStream failed")
    }

    /** 单次流式请求：建立连接并逐行读取 SSE 直至 [DONE]/取消/流结束 */
    private fun streamOnce(
        messages: JSONArray,
        tools: List<JSONObject>?,
        accumulator: SseStreamAccumulator,
        isCancelled: (() -> Boolean)?,
    ): ChatTurn {
        val base = baseUrl.trimEnd('/')
        val endpoint = when {
            base.endsWith("/chat/completions") -> base
            else -> "$base/chat/completions"
        }
        val requestBody = JSONObject().apply {
            put("model", model)
            put("messages", messages)
            put("stream", true)
            // 8192：推理模型（deepseek 等）先消耗大量 token 做 reasoning，4096 会被思考吃光、
            // 还没轮到输出 content/工具调用就 finish_reason=length 截断（表现为连续 content=null 空轮）。
            // 8192 给「reasoning + 一次大文件工具调用 JSON」留足余量；多文件写入仍靠分轮工具拆解。
            put("max_tokens", 8192)
            put("temperature", 0.7)
            if (!tools.isNullOrEmpty()) put("tools", JSONArray(tools))
            // DeepSeek V4 / V3.2 思考默认开启：reasoning_content 会吞掉 max_tokens 预算，
            // 实测单轮 reasoning ~2.5 万字符 → finish=length 截断 → 工具调用永不发生（连续空轮）。
            // 默认关闭思考（输出仅约 1/7 token、更快更稳）；用户开启思考（thinkingEnabled）时
            // 不附加，恢复服务端默认长推理（需配合 reasoning_content 历史回传）。
            val lowerModel = model.lowercase()
            if (!thinkingEnabled && lowerModel.contains("deepseek") &&
                (lowerModel.contains("v4") || lowerModel.contains("v3.2"))
            ) {
                put("thinking", JSONObject().put("type", "disabled"))
            }
            // 用户自定义请求参数（本地 Ollama 调参）：最后合并，覆盖上面的默认值
            mergeExtraBody(this, extraBody)
        }
        val headers = mapOf(
            "Authorization" to "Bearer $apiKey",
            "Content-Type" to "application/json; charset=utf-8",
            "Accept" to "text/event-stream",
        )
        // 流式走 HttpClient.postSse（OkHttp 连接池）：逐行回调读取 SSE，
        // 回调返回 false 即停止（用户打断 / 累积器完成），连接归还连接池复用
        HttpClient.postSse(
            url = endpoint,
            body = requestBody.toString(),
            headers = headers,
            connectTimeout = 15000,
            // 由 readTimeoutMs 控制：本地 Ollama 加载/思考首字慢，已按需调大
            readTimeout = readTimeoutMs,
        ) { data ->
            // 用户打断：尽快停止读取（readLine 未阻塞时立即生效）
            if (isCancelled?.invoke() == true) {
                Log.w(TAG, "chatTurnStream: cancelled by user interrupt, stop reading")
                false
            } else {
                accumulator.onSseLine(data)
            }
        }
        val turn = accumulator.build()
        Log.i(
            TAG,
            "chatTurnStream: toolCalls=${turn.toolCalls.size} content=${turn.content?.take(60)} " +
                "finish=${accumulator.finishReason ?: "none"} reasoning=${accumulator.reasoningChars}",
        )
        return turn
    }

    private fun chatTurnOnce(
        messages: JSONArray,
        tools: List<JSONObject>? = null,
        readTimeout: Int? = null,
    ): ChatTurn {
        // baseUrl 兼容：带 /v1 或已含完整 /chat/completions 的填法
        val base = baseUrl.trimEnd('/')
        val endpoint = when {
            base.endsWith("/chat/completions") -> base
            else -> "$base/chat/completions"
        }

        val requestBody = JSONObject()
        requestBody.put("model", model)
        requestBody.put("messages", messages)
        requestBody.put("stream", false)
        // 思考开启时预算放大（reasoning+输出都需容纳），关闭思考保持 8192 足够大 JSON 工具参数
        requestBody.put("max_tokens", if (thinkingEnabled) 32000 else 8192)
        requestBody.put("temperature", 0.7)
        if (!tools.isNullOrEmpty()) requestBody.put("tools", JSONArray(tools))
        // DeepSeek V4/V3.2 默认关闭思考（reasoning 会耗尽输出预算导致空轮），同流式路径；
        // 开启思考（thinkingEnabled）时不附加，恢复服务端默认长推理
        val lowerModel = model.lowercase()
        if (!thinkingEnabled && lowerModel.contains("deepseek") &&
            (lowerModel.contains("v4") || lowerModel.contains("v3.2"))
        ) {
            requestBody.put("thinking", JSONObject().put("type", "disabled"))
        }
        // 用户自定义请求参数（本地 Ollama 调参）：最后合并，覆盖上面的默认值
        mergeExtraBody(requestBody, extraBody)

        val headers = mapOf(
            "Authorization" to "Bearer $apiKey",
            "Content-Type" to "application/json; charset=utf-8",
        )

        Log.i(TAG, "chatTurn: POST $endpoint model=$model tools=${tools?.size ?: 0}")
        val response = HttpClient.postString(
            url = endpoint,
            body = requestBody.toString(),
            headers = headers,
            readTimeout = readTimeout ?: readTimeoutMs,
        )
        Log.i(TAG, "chatTurn: response length=${response.length}")

        // 解析响应: {"choices":[{"message":{"content":"...","tool_calls":[...]}}]}
        val json = JSONObject(response)
        val choices = json.optJSONArray("choices")
        if (choices == null || choices.length() == 0) {
            val err = json.optJSONObject("error")
            val errMsg = err?.optString("message") ?: "no choices in response"
            throw Exception("AI API error: $errMsg")
        }
        val message = choices.getJSONObject(0).getJSONObject("message")
        val content = if (message.isNull("content")) null else message.optString("content")
        // 非流式响应：思考过程位于顶层 message.reasoning_content（思考开启时有值，供多轮回传）
        val reasoning = if (message.isNull("reasoning_content")) null else message.optString("reasoning_content")
        val toolCalls = mutableListOf<ToolCallInfo>()
        val calls = message.optJSONArray("tool_calls")
        if (calls != null) {
            for (i in 0 until calls.length()) {
                val call = calls.optJSONObject(i) ?: continue
                val fn = call.optJSONObject("function") ?: continue
                toolCalls.add(
                    ToolCallInfo(
                        id = call.optString("id"),
                        name = fn.optString("name"),
                        arguments = fn.optString("arguments"),
                    )
                )
            }
        }
        Log.i(TAG, "chatTurn: toolCalls=${toolCalls.size} content=${content?.take(60)} reasoning=${reasoning?.length ?: 0}")
        return ChatTurn(content, toolCalls, reasoning)
    }

    /**
     * 获取该服务商支持的全部模型 ID 列表（GET {base}/models）。
     * 在 IO 线程调用（阻塞方法），失败时抛出异常（由调用方兜底为手动输入）。
     */
    fun listModels(): List<String> {
        val base = baseUrl.trimEnd('/')
        val endpoint = when {
            base.endsWith("/models") -> base
            base.endsWith("/chat/completions") -> base.removeSuffix("/chat/completions") + "/models"
            else -> "$base/models"
        }
        val headers = mapOf("Authorization" to "Bearer $apiKey")
        Log.i(TAG, "listModels: GET $endpoint")
        val response = HttpClient.getString(endpoint, headers = headers, readTimeout = 15000)
        val json = JSONObject(response)
        val data = json.optJSONArray("data")
            ?: throw Exception("no data in response: ${response.take(200)}")
        val ids = mutableListOf<String>()
        for (i in 0 until data.length()) {
            val id = data.optJSONObject(i)?.optString("id").orEmpty()
            if (id.isNotBlank()) ids.add(id)
        }
        Log.i(TAG, "listModels: ${ids.size} models: ${ids.take(8)}")
        return ids
    }
}

/** 对话历史消息 */
data class ChatMessage(
    val role: String,    // "user" 或 "assistant"
    val content: String,
    /** 本轮工具调用轨迹（仅 assistant 消息，本地回溯用，不注入回 LLM 上下文） */
    val toolTrace: List<String> = emptyList(),
)

/** 工具调用 id 兜底：国产/本地模型常缺 id 字段，回填 tool_call_id 时空串会让部分服务端返回 400，
 *  故缺失时生成 call_<uuid> 兜底，保证每次调用都有稳定非空 id（B3）。 */
private fun resolveToolId(raw: String): String =
    raw.ifBlank { "call_" + java.util.UUID.randomUUID().toString() }

/** 模型请求的一次工具调用 */
data class ToolCallInfo(
    val id: String,          // 工具调用 id（回填 tool 消息时使用）
    val name: String,        // 工具名
    val arguments: String,   // 工具参数（JSON 字符串）
)

/** 一次对话轮次的结果：要么是纯文本回复（[content]），要么请求调用工具（[toolCalls]） */
data class ChatTurn(
    val content: String?,
    val toolCalls: List<ToolCallInfo>,
    /** 本轮的模型思考过程全文（reasoning_content，仅思考开启时有值）。
     *  开启思考的多轮对话须把该内容原样回传给服务端，否则 DeepSeek V4 会拒绝后续请求 */
    val reasoning: String? = null,
)

/**
 * SSE 流式响应累积器（OpenAI Chat Completions 协议）。
 *
 * 逐行喂入 SSE 数据行，累积 content 增量，并将 tool_calls 增量按 index
 * 分片拼接（OpenAI 流式协议 name/arguments 会被拆成多片，跨 chunk 增量下发）。
 *
 * 从 [OpenAiService.chatTurnStream] 的网络循环中独立抽出，便于纯 JVM 单测
 * 锁定 tool_calls 增量拼接与 [DONE] 终止行为（该区域历史上出错过多次）。
 */
internal class SseStreamAccumulator(
    private val onDelta: ((String) -> Unit)? = null,
) {
    private val content = StringBuilder()
    private val toolNameParts = mutableMapOf<Int, StringBuilder>()
    private val toolArgParts = mutableMapOf<Int, StringBuilder>()
    private val toolIds = mutableMapOf<Int, String>()
    private var finished = false
    /** 推理过程全文（thinking 开启时服务端下发 reasoning_content，逐帧累积，供多轮回传） */
    private val reasoning = StringBuilder()
    var reasoningChars: Int = 0
        private set
    /** 流终止原因：length=被 max_tokens 截断；stop=正常结束；空=服务端未给出 */
    var finishReason: String? = null
        private set

    /** 已收到 [DONE] 或已终止 */
    fun hasFinished(): Boolean = finished

    /** 是否已产生任何 content / tool_calls 增量（首字前判定，供上层决定重试是否安全） */
    fun hasStarted(): Boolean =
        content.isNotEmpty() || toolNameParts.isNotEmpty() || toolArgParts.isNotEmpty()

    /**
     * 是否已向 UI 推送过 content 增量（SSE 断线重连的「重放安全」判定）：
     * content 为空 = 用户未看到任何输出，整轮重放无副作用（工具调用增量只被累积、尚未执行）。
     */
    fun hasEmittedContent(): Boolean = content.isNotEmpty()

    /**
     * 处理一行 SSE（如 `data: {...}` 或 `data: [DONE]`）。
     * @return 是否继续读取下一行（false = 已收到 [DONE]，调用方应停止）
     */
    fun onSseLine(line: String): Boolean {
        if (finished) return false
        if (!line.startsWith("data:")) return true
        val payload = line.removePrefix("data:").trim()
        if (payload == "[DONE]") {
            finished = true
            return false
        }
        val json = try { JSONObject(payload) } catch (_: Exception) { return true }
        val choices = json.optJSONArray("choices") ?: return true
        if (choices.length() == 0) return true
        val choice = choices.getJSONObject(0)
        // 终止原因：部分服务商在最后一帧的 choice 上带 finish_reason（截断诊断关键）
        val fr = choice.optString("finish_reason")
        if (fr.isNotEmpty()) finishReason = fr
        val delta = choice.optJSONObject("delta") ?: return true
        // 推理内容增量（deepseek reasoner / 带思考的模型）：仅累计长度用于诊断，
        // 推理过程不推给 UI、也不作为回复正文
        val reasoningDelta = delta.optString("reasoning_content")
        if (reasoningDelta.isNotEmpty()) {
            reasoningChars += reasoningDelta.length
            reasoning.append(reasoningDelta)
        }
        // content 增量
        if (!delta.isNull("content")) {
            val deltaContent = delta.optString("content")
            if (deltaContent.isNotEmpty()) {
                content.append(deltaContent)
                onDelta?.invoke(deltaContent)
            }
        }
        // tool_calls 增量（按 index 拼接 name/arguments）
        val tcArray = delta.optJSONArray("tool_calls") ?: return true
        for (i in 0 until tcArray.length()) {
            val tc = tcArray.optJSONObject(i) ?: continue
            val idx = tc.optInt("index", 0)
            val idStr = tc.optString("id")
            if (idStr.isNotEmpty()) toolIds[idx] = idStr
            val fn = tc.optJSONObject("function") ?: continue
            val namePart = fn.optString("name")
            if (namePart.isNotEmpty()) {
                toolNameParts.getOrPut(idx) { StringBuilder() }.append(namePart)
            }
            val argPart = fn.optString("arguments")
            if (argPart.isNotEmpty()) {
                toolArgParts.getOrPut(idx) { StringBuilder() }.append(argPart)
            }
        }
        return true
    }

    /** 累积结果：[content] 纯文本回复（可空），[toolCalls] 按 index 升序合并后的完整工具调用 */
    fun build(): ChatTurn {
        val toolCalls = toolNameParts.entries.sortedBy { it.key }.map { (idx, _) ->
            ToolCallInfo(
                id = resolveToolId(toolIds[idx] ?: ""),
                name = toolNameParts[idx]!!.toString(),
                arguments = toolArgParts[idx]?.toString() ?: "",
            )
        }
        return ChatTurn(
            content.toString().ifBlank { null },
            toolCalls,
            reasoning.toString().ifBlank { null },
        )
    }
}
