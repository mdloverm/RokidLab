package com.rokidlab.phone.ai.tools

import android.content.Context
import org.json.JSONObject

/**
 * 工具域提供者（Phase 4：ToolRegistry.execute 的巨型 when 按域拆分）。
 *
 * 每个域一个 provider 对象，持有该域全部工具的执行分支（从 ToolRegistry 逐字迁移）；
 * [ToolRegistry.execute] 统一解析参数后按 toolNames 路由到 provider。
 * 共享设施（ADB 连接/包名匹配/最近启动的 AIUI 记录等）仍在 ToolRegistry，
 * 以 `ToolRegistry.xxx` 访问，保持单一数据源。
 */
internal interface ToolProvider {
    /** 本提供者负责的工具名集合 */
    val toolNames: Set<String>

    /** 执行工具并返回给模型的结果文本；未登记的工具抛 [IllegalArgumentException] */
    fun execute(context: Context, name: String, args: JSONObject): String
}
