package com.rokidlab.phone.ai.tools

import android.content.Context
import com.rokidlab.phone.ai.mcp.McpRegistry
import org.json.JSONObject

/**
 * 外部 MCP server 提供的工具的 provider（工具接缝的"动态"实现）。
 *
 * ## ⚠️ 与其余 12 个 provider 的本质区别
 * 它的 [toolNames] 与 [tools] 是**动态 getter**，每次读的是 [McpRegistry] 的当下状态。
 * **绝不能**写成 `override val toolNames = ...` 那种属性初始化形式 —— 那等于又把结果固化成
 * 快照，MCP 工具连上之后永远进不了 `ToolRegistry` 的派生表（且编译通过、不报错）。
 *
 * 同理，它的 `toolNames` 与 `tools()` 天然"随时可能不一致"（两次读之间 server 可能断开），
 * 因此 `check_tool_wiring.py` 的双向核对对它不适用，需要按"同一快照内自洽"来断言 —— 见该脚本。
 *
 * 本类由 [McpRegistry.rebuildIndex] 通过 `ToolRegistry.setDynamicProviders` 注入，
 * **不写进 `ToolRegistry.providers` 的静态列表**（静态列表是编译期常量，装不下运行时才知道的东西）。
 */
internal object McpToolProvider : ToolProvider {

    override val toolNames: Set<String>
        get() = McpRegistry.activeToolNames()

    override fun tools(): List<ToolEntry> = McpRegistry.activeEntries()

    override fun execute(context: Context, name: String, args: JSONObject): String =
        McpRegistry.call(name, args)
}
