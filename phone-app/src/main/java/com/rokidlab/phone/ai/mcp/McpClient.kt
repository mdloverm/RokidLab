package com.rokidlab.phone.ai.mcp

import android.util.Log
import com.rokidlab.phone.BuildConfig
import com.rokidlab.phone.util.HttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * MCP（Model Context Protocol）**Streamable HTTP 客户端** —— 最小实现。
 *
 * 只覆盖「客户端调 server 的工具」这一条路：
 * `initialize` → `notifications/initialized` → `tools/list` → `tools/call`。
 * `resources` / `prompts` / `sampling` / 服务端推送（GET 长连接）**不做**（见方案 §2.2）。
 *
 * ## 为什么不引入官方 MCP SDK
 * 官方 Java SDK 面向 JVM/桌面宿主，带 stdio 传输与大量本项目用不到的抽象；我们只需要
 * 「POST 一个 JSON-RPC、读回一个响应」，用**已在依赖里**的 OkHttp（经 [HttpClient]）即可，
 * 零新增依赖（方案 §1.1）。SDK 版本演进也就不用跟。
 *
 * ## 三个必须踩准的协议细节
 *  1. **JSON-RPC 的 `id` 必须与响应配对**：一次响应可能是 SSE 分包，一个流里会有多条消息，
 *     不能假设"读到的第一条就是我要的"。这里按 id 匹配，**取到即停止读取** ——
 *     否则长连接会一直挂到读超时。
 *  2. **响应可能是 JSON 也可能是 SSE**：由 server/请求决定，两者都必须支持。
 *     因此走 [HttpClient.postStreamWithHeaders]，拿到状态码 + 响应头 + 逐行正文后自行分流
 *     （见 [jsonFromLine]）。
 *  3. **会话 ID 只在响应头里**：`initialize` 的 `Mcp-Session-Id` 正文拿不到，后续请求要回填。
 *     这正是 [HttpClient] 需要新增 [HttpClient.postStreamWithHeaders] 的原因。
 *
 * ⚠️ 本类**刻意不做重试**：工具执行是**同步阻塞在对话主循环**里的（方案 §1.2 第 4 条，
 * `AiConversationService` 的工具派发是同步调用），重试只会成倍拉长卡顿。
 * 失败就把错误文本交回模型，由模型决定换法子还是放弃。
 */
internal object McpClient {

    private const val TAG = "McpClient"

    /**
     * 我们声明的协议版本。
     *
     * 取 2025-06-18 —— Streamable HTTP 已并入核心规范，不必再兼容旧的「HTTP + 双端点 SSE」方案。
     *
     * ⚠️ **协商刻意宽松**：server 回一个不同版本时**不按规范断开**，只告警后继续。
     * 断开会让"对端版本略新/略旧"直接表现为整个 server 不可用，而 initialize / tools 这几个
     * 基础方法跨版本差异极小；可用性优先，真有不兼容会在 `tools/call` 上暴露，
     * 好过一上来就哑掉。
     */
    private const val PROTOCOL_VERSION = "2025-06-18"

    /** 握手 / 列工具的读超时（轻量请求，慢了说明 server 本身有问题） */
    private const val READ_TIMEOUT_HANDSHAKE_MS = 15_000

    /**
     * 工具调用的读超时。
     *
     * 为什么可以和握手一样、甚至更短：`tools/call` 会**同步阻塞整个对话主循环**，
     * 卡住的代价是"用户对着不动的界面干等"。宁可早点失败，把错误文本回给模型让它换法子。
     */
    private const val READ_TIMEOUT_CALL_MS = 20_000

    private const val CONNECT_TIMEOUT_MS = 8_000

    /** `tools/list` 翻页保护：cursor 不推进就自杀，避免死循环 */
    private const val MAX_PAGES = 20

    private val nextId = AtomicInteger(1)

    /** 一次 RPC 的结果：JSON-RPC 的 `result` + 命中响应里的会话 ID */
    internal class RpcOutcome(val result: JSONObject, val sessionId: String?)

    /**
     * 建立会话：`initialize` + `notifications/initialized`。
     *
     * @return server 分配的会话 ID；**可能为 null**（无状态 server 是合法的，
     *   后续请求不带该头即可，不要当成失败）
     */
    fun initialize(url: String, headers: Map<String, String>): String? {
        val params = JSONObject()
            .put("protocolVersion", PROTOCOL_VERSION)
            .put("capabilities", JSONObject())
            .put(
                "clientInfo",
                JSONObject()
                    .put("name", "RokidLab")
                    .put("version", runCatching { BuildConfig.VERSION_NAME }.getOrDefault("?")),
            )
        val outcome = rpc(url, "initialize", params, null, headers, READ_TIMEOUT_HANDSHAKE_MS)
        val negotiated = outcome.result.optString("protocolVersion")
        if (negotiated.isNotBlank() && negotiated != PROTOCOL_VERSION) {
            Log.w(TAG, "server negotiated $negotiated (offered $PROTOCOL_VERSION), continuing anyway")
        }
        // 通知类请求（无 id、规范上回 202）：失败不致命 —— 不少 server 直接忽略它
        runCatching { notify(url, "notifications/initialized", outcome.sessionId, headers) }
            .onFailure { Log.w(TAG, "notifications/initialized failed: ${it.message}") }
        return outcome.sessionId
    }

    /**
     * 列出 server 提供的工具（自动翻页）。
     *
     * @throws IOException 连接失败 / 协议错误 / 翻页异常
     */
    fun listTools(
        url: String,
        sessionId: String?,
        headers: Map<String, String>,
    ): List<McpToolDef> {
        val out = mutableListOf<McpToolDef>()
        var cursor: String? = null
        var pages = 0
        while (true) {
            if (++pages > MAX_PAGES) {
                throw McpRpcException("tools/list 翻页超过 $MAX_PAGES 页（cursor 似乎没推进）")
            }
            val params = JSONObject().apply { if (cursor != null) put("cursor", cursor) }
            val result = rpc(url, "tools/list", params, sessionId, headers, READ_TIMEOUT_HANDSHAKE_MS).result
            val arr = result.optJSONArray("tools") ?: JSONArray()
            for (i in 0 until arr.length()) {
                val t = arr.optJSONObject(i) ?: continue
                val name = t.optString("name")
                if (name.isBlank()) continue
                out += McpToolDef(
                    name = name,
                    description = t.optString("description"),
                    inputSchema = t.optJSONObject("inputSchema"),
                )
            }
            cursor = result.optString("nextCursor").takeIf { it.isNotBlank() }
            if (cursor == null) break
        }
        return out
    }

    /**
     * 调用工具：`tools/call`。
     *
     * @return **永远返回可读文本，不抛异常** —— 这是工具回填，抛出去会让主循环把整轮判失败
     *   （与 `ReadOnlySubagent.run` 同一约定）。失败原因写进文本，让模型自己决定怎么办。
     */
    fun callTool(
        url: String,
        sessionId: String?,
        headers: Map<String, String>,
        toolName: String,
        args: JSONObject,
    ): String = try {
        val params = JSONObject().put("name", toolName).put("arguments", args)
        val result = rpc(url, "tools/call", params, sessionId, headers, READ_TIMEOUT_CALL_MS).result
        val text = flattenContent(result)
        if (result.optBoolean("isError", false)) {
            "MCP 工具 $toolName 返回错误：${text.ifBlank { "（无错误详情）" }}"
        } else {
            text.ifBlank { "MCP 工具 $toolName 执行成功，但没有返回内容。" }
        }
    } catch (e: Exception) {
        Log.w(TAG, "callTool $toolName failed: ${e.message}")
        "MCP 工具 $toolName 调用失败：${e.message ?: e.javaClass.simpleName}"
    }

    // ═══════════════════ 内部 ═══════════════════

    /** 发一次带 `id` 的请求并取回 `result`；协议错误抛 [McpRpcException] */
    private fun rpc(
        url: String,
        method: String,
        params: JSONObject?,
        sessionId: String?,
        headers: Map<String, String>,
        readTimeout: Int,
    ): RpcOutcome {
        val id = nextId.getAndIncrement()
        val payload = JSONObject()
            .put("jsonrpc", "2.0")
            .put("id", id)
            .put("method", method)
        if (params != null) payload.put("params", params)

        var picked: JSONObject? = null
        val seen = StringBuilder()
        val stream = HttpClient.postStreamWithHeaders(
            url = url,
            body = payload.toString(),
            connectTimeout = CONNECT_TIMEOUT_MS,
            readTimeout = readTimeout,
            headers = requestHeaders(sessionId, headers),
        ) { line ->
            seen.append(line).append('\n')
            val obj = jsonFromLine(line)
            // ★ 按 id 配对：SSE 一个流里可能有多条消息，不能"来一条就当它是我要的"
            if (obj != null && obj.optInt("id", Int.MIN_VALUE) == id) {
                picked = obj
                false // 拿到目标响应立刻停读，别让长连接挂到超时
            } else {
                true
            }
        }

        val response = picked ?: throw McpRpcException(
            "未收到 id=$id 的响应（HTTP ${stream.code}，已读到 ${seen.length} 字符" +
                "${if (seen.isEmpty()) "，响应为空" else ""}）",
        )
        val error = response.optJSONObject("error")
        if (error != null) {
            throw McpRpcException(
                "JSON-RPC error ${error.optInt("code")}：${error.optString("message")}",
            )
        }
        return RpcOutcome(
            result = response.optJSONObject("result") ?: JSONObject(),
            sessionId = stream.header("mcp-session-id") ?: sessionId,
        )
    }

    /** 发送 JSON-RPC 通知（无 `id`、不应答） */
    private fun notify(
        url: String,
        method: String,
        sessionId: String?,
        headers: Map<String, String>,
    ) {
        val payload = JSONObject().put("jsonrpc", "2.0").put("method", method)
        HttpClient.postStreamWithHeaders(
            url = url,
            body = payload.toString(),
            connectTimeout = CONNECT_TIMEOUT_MS,
            readTimeout = READ_TIMEOUT_HANDSHAKE_MS,
            headers = requestHeaders(sessionId, headers),
        ) { false } // 通知不等响应：读到第一行就收工，没有正文则自然结束
    }

    /**
     * 请求头。
     *
     * `Accept` 必须**同时声明两种**：MCP 规范要求客户端既能收 `application/json`
     * 也能收 `text/event-stream`，只声明一种会被部分 server 以 406 拒绝。
     */
    private fun requestHeaders(sessionId: String?, extra: Map<String, String>): Map<String, String> =
        buildMap {
            put("Accept", "application/json, text/event-stream")
            if (!sessionId.isNullOrBlank()) put("Mcp-Session-Id", sessionId)
            putAll(extra) // 用户配置的鉴权头放最后：允许其覆盖上面的默认值
        }

    /**
     * 从一行响应里解析出 JSON-RPC 消息；不是消息行则返回 null。
     *
     * 兼容两种形态（这是"同时支持 JSON 与 SSE"的落点）：
     *  - `application/json`：整行就是一个 JSON 对象；
     *  - `text/event-stream`：载荷在 `data: {...}` 行里，`event:` / `id:` / `retry:` / 空行一律忽略。
     */
    private fun jsonFromLine(line: String): JSONObject? {
        val t = line.trim()
        if (t.isEmpty()) return null
        val payload = when {
            t.startsWith("data:") -> t.removePrefix("data:").trim()
            t.startsWith("{") -> t
            else -> return null
        }
        if (!payload.startsWith("{")) return null
        return runCatching { JSONObject(payload) }.getOrNull()
    }

    /**
     * `tools/call` 的 `result.content` 是**数组**（text / image / resource 各类），
     * 这里只把 text 拼成文本。
     *
     * 为什么不做 image：眼镜那条链路只能显示文本与预置素材（见 RULES 的 AIUI 渲染约定），
     * 把 base64 图片塞回模型也没有意义。真要多模态得另做附件回填，不在本版范围。
     */
    private fun flattenContent(result: JSONObject): String {
        val arr = result.optJSONArray("content") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            when (item.optString("type")) {
                "text" -> sb.append(item.optString("text"))
                "image" -> sb.append("[图片内容，当前链路无法展示]")
                "resource" -> sb.append(item.optJSONObject("resource")?.optString("text").orEmpty())
                else -> Unit
            }
        }
        return sb.toString().trim()
    }
}

/** 一个 MCP 工具的原始声明（`tools/list` 的单项） */
internal data class McpToolDef(
    val name: String,
    val description: String,
    /** server 声明的参数 JSON Schema；可能缺失（无参工具）或为 null */
    val inputSchema: JSONObject?,
)

/** MCP 协议层错误：连接失败 / JSON-RPC error / 响应缺失 / 翻页异常 */
internal class McpRpcException(message: String) : IOException(message)
