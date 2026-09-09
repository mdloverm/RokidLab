package com.rokidlab.phone.ai

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicReference

/**
 * AIUI 页面统一工具网关（"整个工具出一个口"）。
 *
 * 背景：AIUI 页面（模型生成的 .ink）运行在眼镜端 WebView 里，除了渲染什么都做不了。
 * 本网关把手机端已有的 34 个工具通过一个固定入口暴露给页面：
 *
 *   页面 Lab.callTool('play_song', { songName: '西厢' })
 *     → RFCOMM 上行 __LAB_TOOL__
 *     → [ToolGateway.call]
 *     → ToolRegistry.execute
 *     → 结果 JSON 下行回页面
 *
 * 设计约束（改代码前请先读）：
 * 1. 域白名单按 DOMAIN 继承而非逐工具配置 —— 后期加工具只改 ToolRegistry 一行注册，
 *    网关/协议/JS/skill 全不动。逐工具配会破坏这个性质。
 * 2. 全部域开放（用户 2026-09-09 拍板）。保留 [ALLOWED_DOMAINS] 是为了想收窄时只改一处。
 * 3. [DENY_TOOLS] 只放会造成技术故障的工具（自指递归），不放"危险"工具——
 *    安全边界由 [isEnabled] 总开关负责，不做逐工具安全判断。
 * 4. 本类是同步阻塞 API，调用方必须在非主线程调用。
 */
object ToolGateway {

    private const val TAG = "ToolGateway"
    private const val PREFS = "aiui_tool_gateway"
    private const val KEY_ENABLED = "enabled"

        /** 能力发现：不是 ToolRegistry 里的真实工具，页面用它拿到"我现在能调什么" */
        const val LIST_TOOLS = "list_tools"

        /** 结果返回页面前的截断上限：RFCOMM 单帧上限 64KB，且页面也渲染不下超长文本 */
        private const val MAX_RESULT_CHARS = 8000

    /** 单次调用超时。工具可能走外网（搜索/天气 1~3s），留足余量 */
    private const val CALL_TIMEOUT_MS = 15_000L

    /**
     * 页面可调用的工具域：全部开放。
     * 想收窄时改这里即可，不用动调用逻辑。
     */
    val ALLOWED_DOMAINS: Set<String> = ToolRegistry.DOMAIN_ALL

    /**
     * 例外：不开放给页面的工具。
     *
     * open_aiui_app 会让页面去打开一个 AIUI 应用 —— 页面很可能打开它自己，
     * 形成「启动 → 页面又启动」的自指递归，属于技术故障，不是安全问题。
     * 其余 AIUI 管理工具（install/stop/list）保留开放。
     */
    private val DENY_TOOLS: Set<String> = setOf("open_aiui_app")

    /** 工具摘要：下发给页面，也用于让生成方模型知道当前有什么能力 */
    data class ToolBrief(
        val name: String,
        val description: String,
        val domain: String,
    )

    /** 调用结果，直接序列化成 JSON 回传页面 */
    data class CallResult(
        val ok: Boolean,
        val name: String,
        val result: String? = null,
        val error: String? = null,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("ok", ok)
            put("name", name)
            if (result != null) put("result", result)
            if (error != null) put("error", error)
        }
    }

    /**
     * 总开关。全部域开放的前提下，这是唯一的安全兜底：
     * 出问题时可一键断开页面侧的全部工具能力，默认开启。
     */
    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, 0).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, 0).edit().putBoolean(KEY_ENABLED, enabled).apply()
        Log.i(TAG, "tool gateway enabled=$enabled")
    }

    /** 当前页面可调用的工具清单（描述取当前语言，供页面与生成方模型感知） */
    fun listAllowed(context: Context): List<ToolBrief> =
        ToolRegistry.toolList
            .filter { it.group in ALLOWED_DOMAINS && it.name !in DENY_TOOLS }
            .map { ToolBrief(it.name, context.getString(it.descriptionRes), it.group) }

    /** 清单的紧凑 JSON（页面 Lab.listTools() 用），避免页面自己拼装 */
    fun listAllowedJson(context: Context): String =
        JSONObject().apply {
            val arr = org.json.JSONArray()
            for (t in listAllowed(context)) {
                arr.put(JSONObject().put("name", t.name).put("description", t.description))
            }
            put("tools", arr)
        }.toString()

    /**
     * 执行一个工具。同步返回，内部带超时保护。
     *
     * 必须在非主线程调用：[ToolRegistry.execute] 内部有网络/文件 IO。
     *
     * @param arguments JSON 对象字符串，可为空（按 {} 处理）
     */
    fun call(context: Context, name: String, arguments: String): CallResult {
        val toolName = name.trim()
        if (!isEnabled(context)) {
            return CallResult(false, toolName, error = "tool gateway is disabled")
        }
        if (toolName.isEmpty()) {
            return CallResult(false, toolName, error = "empty tool name")
        }
        // 能力发现走同一条通道，页面不必额外学一套协议：
        // 结果就是 listAllowedJson，页面 JSON.parse 后得到 {tools:[{name,description}]}
        if (toolName == LIST_TOOLS) {
            return CallResult(true, toolName, result = listAllowedJson(context))
        }
        if (toolName in DENY_TOOLS) {
            return CallResult(false, toolName, error = "tool '$toolName' is not allowed in AIUI pages")
        }

        val meta = ToolRegistry.toolList.firstOrNull { it.name == toolName }
            ?: return CallResult(false, toolName, error = "unknown tool: $toolName")
        if (meta.group !in ALLOWED_DOMAINS) {
            return CallResult(false, toolName, error = "domain '${meta.group}' of tool '$toolName' is not allowed in AIUI pages")
        }

        // 参数必须是合法 JSON 对象：ToolRegistry.execute 内部直接 JSONObject(arguments)，
        // 传入非法串会在那里抛异常，页面侧只会看到一个无信息的超时。
        val normalizedArgs = try {
            if (arguments.isBlank()) "{}" else JSONObject(arguments).toString()
        } catch (e: Exception) {
            return CallResult(false, toolName, error = "arguments is not valid JSON: ${e.message}")
        }

        val appContext = context.applicationContext
        val resultRef = AtomicReference<String?>(null)
        val errorRef = AtomicReference<String?>(null)
        val worker = Thread({
            try {
                resultRef.set(ToolRegistry.execute(appContext, toolName, normalizedArgs))
            } catch (e: Throwable) {
                errorRef.set(e.message ?: e.javaClass.simpleName)
            }
        }, "aiui-tool-$toolName").apply {
            isDaemon = true
            start()
        }
        worker.join(CALL_TIMEOUT_MS)
        if (worker.isAlive) {
            // 超时后不 interrupt：工具可能持有文件/网络资源，强中断会留下半写状态，
            // 让线程自己跑完（daemon 线程不阻塞进程退出）。
            Log.w(TAG, "tool $toolName timed out after ${CALL_TIMEOUT_MS}ms")
            return CallResult(false, toolName, error = "tool '$toolName' timed out")
        }

        errorRef.get()?.let {
            Log.w(TAG, "tool $toolName failed: $it")
            return CallResult(false, toolName, error = it)
        }

        var result = resultRef.get() ?: ""
        if (result.length > MAX_RESULT_CHARS) {
            result = result.take(MAX_RESULT_CHARS) + "…(truncated)"
        }
        Log.i(TAG, "tool $toolName ok, ${result.length} chars")
        return CallResult(true, toolName, result = result)
    }
}
