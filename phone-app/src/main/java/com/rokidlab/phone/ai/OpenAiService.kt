package com.rokidlab.phone.ai

import android.util.Log
import com.rokidlab.phone.util.HttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

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
) {
    companion object {
        private const val TAG = "OpenAiService"
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
     */
    fun buildSystemMessage(contextText: String? = null, instruction: String? = null, memories: String? = null): JSONObject {
        val systemMsg = JSONObject()
        systemMsg.put("role", "system")
        val systemContent = buildString {
            append("你是乐奇，运行在用户手机上的 Rokid 眼镜 AI 助理。用户通过眼镜与你语音对话，")
            append("你的回复会在眼镜屏幕上显示并通过语音播报给用户。")
            append("\n\n【工具使用准则】")
            append("\n- 涉及实时信息（时间、电量、应用列表等）或设备操作（打开应用、播放音乐、停止播放、显示歌词、设定时提醒等）时，必须调用对应工具获取真实结果，严禁编造")
            append("\n- 可以在一次回答中连续调用多个工具来完成多步任务（如先查时间再设定时提醒）")
            append("\n- 工具返回失败或查不到时，如实告知用户，不要假装成功")
            append("\n- 闲聊、常识问答、创作类问题不需要调用工具，直接回答")
            append("\n- 结合对话历史理解上下文：用户说「再来一首」「它是什么意思」时，指代的是之前聊到的内容")
            append("\n- 当用户表达了需要长期记住的个人事实或偏好（如称呼、喜欢的歌手、常用应用、作息习惯）时，调用 manage_memory 工具记住，以便后续对话延续")
            append("\n\n【回复风格】（眼镜语音播报场景）")
            append("\n- 口语化、简短自然，一般不超过 3 句话")
            append("\n- 纯文本：不要用 markdown、序号、表情符号、换行符")
            append("\n- 直接给结论，不要复述问题，不要描述「根据工具结果」这类过程")
            if (!memories.isNullOrBlank()) {
                append("\n\n<memories>\n")
                append(memories)
                append("\n</memories>\n以下是与用户相关的长期记忆，回答时如有涉及请据此个性化；与当前问题无关可忽略。")
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
     */
    fun chatTurn(messages: JSONArray, tools: List<JSONObject>? = null): ChatTurn {
        // 网络瞬断/服务商抖动时重试一次：一次失败即整轮失败对用户太不友好（30s 超时后直接没回复）
        var lastError: Exception? = null
        repeat(2) { attempt ->
            try {
                return chatTurnOnce(messages, tools)
            } catch (e: Exception) {
                lastError = e
                if (attempt == 0) Log.w(TAG, "chatTurn attempt 1 failed: ${e.message}, retrying")
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
     */
    fun chatTurnStream(
        messages: JSONArray,
        tools: List<JSONObject>? = null,
        onDelta: ((String) -> Unit)? = null,
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
            put("max_tokens", 1024)
            put("temperature", 0.7)
            if (!tools.isNullOrEmpty()) put("tools", JSONArray(tools))
        }
        val headers = mapOf(
            "Authorization" to "Bearer $apiKey",
            "Content-Type" to "application/json; charset=utf-8",
            "Accept" to "text/event-stream",
        )
        // 流式需直接持有 HttpURLConnection 以逐行读取 SSE，不复用 HttpClient.postString（一次性 readText）
        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15000
            readTimeout = 60000
            useCaches = false
            instanceFollowRedirects = true
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        try {
            conn.outputStream.use { it.write(requestBody.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                ?: throw java.io.IOException("HTTP $code: ${conn.responseMessage}")
            val content = StringBuilder()
            // tool_calls 增量合并：按 index 累积 name/arguments（OpenAI 流式协议为分片拼接）
            val toolNameParts = mutableMapOf<Int, StringBuilder>()
            val toolArgParts = mutableMapOf<Int, StringBuilder>()
            val toolIds = mutableMapOf<Int, String>()
            stream.bufferedReader().use { reader ->
                while (true) {
                    val data = reader.readLine() ?: break
                    if (!data.startsWith("data:")) continue
                    val payload = data.removePrefix("data:").trim()
                    if (payload == "[DONE]") break
                    val json = try { JSONObject(payload) } catch (_: Exception) { continue }
                    val choices = json.optJSONArray("choices") ?: continue
                    if (choices.length() == 0) continue
                    val delta = choices.getJSONObject(0).optJSONObject("delta") ?: continue
                    // content 增量
                    if (!delta.isNull("content")) {
                        val deltaContent = delta.optString("content")
                        if (deltaContent.isNotEmpty()) {
                            content.append(deltaContent)
                            onDelta?.invoke(deltaContent)
                        }
                    }
                    // tool_calls 增量（按 index 拼接 name/arguments）
                    val tcArray = delta.optJSONArray("tool_calls")
                    if (tcArray != null) {
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
                    }
                }
            }
            val toolCalls = toolNameParts.entries.sortedBy { it.key }.map { (idx, _) ->
                ToolCallInfo(
                    id = toolIds[idx] ?: "",
                    name = toolNameParts[idx]!!.toString(),
                    arguments = toolArgParts[idx]?.toString() ?: "",
                )
            }
            Log.i(TAG, "chatTurnStream: toolCalls=${toolCalls.size} content=${content.take(60)}")
            return ChatTurn(content.toString().ifBlank { null }, toolCalls)
        } finally {
            conn.disconnect()
        }
    }

    private fun chatTurnOnce(messages: JSONArray, tools: List<JSONObject>? = null): ChatTurn {
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
        requestBody.put("max_tokens", 1024)
        requestBody.put("temperature", 0.7)
        if (!tools.isNullOrEmpty()) requestBody.put("tools", JSONArray(tools))

        val headers = mapOf(
            "Authorization" to "Bearer $apiKey",
            "Content-Type" to "application/json; charset=utf-8",
        )

        Log.i(TAG, "chatTurn: POST $endpoint model=$model tools=${tools?.size ?: 0}")
        val response = HttpClient.postString(
            url = endpoint,
            body = requestBody.toString(),
            headers = headers,
            readTimeout = 30000,
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
        Log.i(TAG, "chatTurn: toolCalls=${toolCalls.size} content=${content?.take(60)}")
        return ChatTurn(content, toolCalls)
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
)
