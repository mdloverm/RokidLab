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

    /** 构造 system 消息：定义 AI 角色，可选注入知识库检索资料（RAG）与额外指令（如答题要求） */
    fun buildSystemMessage(contextText: String? = null, instruction: String? = null): JSONObject {
        val systemMsg = JSONObject()
        systemMsg.put("role", "system")
        val systemContent = buildString {
            append("你是一个智能助手，通过 Rokid 眼镜与用户对话。请用简洁的口语化中文回复，回复不要太长，适合语音播报。")
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
