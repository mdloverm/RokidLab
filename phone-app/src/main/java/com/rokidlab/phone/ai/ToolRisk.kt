package com.rokidlab.phone.ai

import android.util.Log

/**
 * L4 agent/ToolRisk —— 工具风险分级（架构文档 §3.5，对应 ARCHITECTURE_REVIEW 的 P0 建议）。
 *
 * 全部工具按副作用分三档；新增工具时必须在 [ToolRiskMap] 登记。
 *
 * ⚠️ 历史事故（务必读）：`save_code_file` / `read_code_file` 曾漏登记风险档，
 * 而当时兜底是「未登记 → 最保守档 [EXTERNAL_SIDE_EFFECT]」，于是这两个 AIUI 代码生成工具
 * 被要求「眼镜端用户确认」→ 确认通道不可用即被拒绝 → **AIUI 生成整条链路直接失败**，
 * 用户侧表现为「提示眼镜没权限 / 没反应」。
 *
 * 接缝化后这类事故在**结构上**不可能重演：风险档是
 * [com.rokidlab.phone.ai.tools.ToolEntry.risk] 的必填字段，与工具定义写在同一个结构体里。
 * 剩下的「完全未知的名字」（模型幻觉 / 历史残留）兜底仍是保守档，但它在到达风险闸门之前
 * 就已经被 [com.rokidlab.phone.ai.approval.ApprovalGate] 的 `UnknownToolGuard` **单调拒绝**，
 * 不会再把用户拖进一次注定超时的 35 秒确认。
 */
enum class ToolRisk {
    /** 纯读取：查询 / 搜索类，无副作用 */
    READ_ONLY,

    /** 本机副作用：改手机/眼镜本地状态（设置音量、定时器、安装 AIUI 等），影响可控或可撤销 */
    LOCAL_SIDE_EFFECT,

    /** 外部副作用：触达第三方、不可撤销的动作 —— 必须经过 [com.rokidlab.phone.ai.approval.ApprovalGate] 确认闸门 */
    EXTERNAL_SIDE_EFFECT,
}

/**
 * 工具名 → 风险档。
 *
 * 接缝化后，风险档是**每个工具自己的声明字段**（[com.rokidlab.phone.ai.tools.ToolEntry.risk]），
 * 登记处 = 各 provider 的 `tools()`。这里只剩一个查询/审计入口，
 * 不再是一张需要手工与 toolList 对齐的独立名单。
 *
 * ★ 这消灭了一整类历史事故：`save_code_file` / `read_code_file` 曾漏登记风险档，
 *   而当时兜底是"最保守档" → 被要求眼镜端确认 → 通道不可用即拒绝 →
 *   **AIUI 代码生成整条链路静默失败**。现在风险档跟工具定义写在同一个结构体里，
 *   "漏登记"在结构上不可能发生。
 */
object ToolRiskMap {

    /**
     * 查询风险档位。
     *
     * 表里没有 = **完全未知的名字**（模型幻觉 / 攻击构造）→ 最保守的
     * [ToolRisk.EXTERNAL_SIDE_EFFECT]，保持"必须先过确认闸门"；
     * 且它随后会因 ToolRegistry 查不到工具而失败，不会真的执行。
     * （真实工具不可能落到这个分支：风险档是强制字段。）
     */
    fun riskOf(name: String): ToolRisk =
        runCatching { ToolRegistry.riskOfOrNull(name) }.getOrNull()
            ?: ToolRisk.EXTERNAL_SIDE_EFFECT

    /**
     * 供诊断/自检：列出「真实工具但未登记风险」的名字。
     *
     * 接缝化后**结构上恒为空**（风险档是声明的必填字段）。保留此 API 是为了让
     * `RULES.md §12.10` 的不变式断言继续有意义 —— 一旦有人把风险档从工具声明里摘掉、
     * 或新增了绕过 provider 注册的工具，这里会立刻不为空。
     */
    fun unregisteredTools(): List<String> =
        runCatching { ToolRegistry.allToolNames().filter { ToolRegistry.riskOfOrNull(it) == null } }
            .getOrDefault(emptyList())
}
