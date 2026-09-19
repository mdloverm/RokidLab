package com.rokidlab.phone.ai.tools

import android.content.Context
import org.json.JSONObject

/**
 * 工具域提供者。
 *
 * 职责（Phase 4 拆分 → 工具接缝化后定型）：
 *  - **[tools]**：声明本域全部工具的**完整元数据**（名称/域/设置页文案/风险档/副作用/文案/schema），
 *    见 [ToolEntry]。这是唯一的登记处 —— `ToolRegistry` 的六张表全部由它聚合派生。
 *  - **[execute]**：执行本域工具（从 ToolRegistry 逐字迁移的执行分支）。
 *
 * 共享设施（ADB 连接/包名匹配/最近启动的 AIUI 记录等）仍在 ToolRegistry，
 * 以 `ToolRegistry.xxx` 访问，保持单一数据源。
 *
 * ★ 新增一个工具 = 在对应 provider 的 `tools()` 里加一条 + 在 `execute` 里加一个分支。
 *   不需要再改任何"名单"；漏登记会让 schema/风险档/文案同时缺失，**不可能只漏其中一张表**。
 */
internal interface ToolProvider {
    /** 本提供者负责的工具名集合（必须与 [tools] 声明的名字完全一致，由回归脚本双向核对） */
    val toolNames: Set<String>

    /** 本提供者声明的工具清单（元数据 + schema + 风险 + 文案） */
    fun tools(): List<ToolEntry>

    /** 执行工具并返回给模型的结果文本；未登记的工具抛 [IllegalArgumentException] */
    fun execute(context: Context, name: String, args: JSONObject): String
}
