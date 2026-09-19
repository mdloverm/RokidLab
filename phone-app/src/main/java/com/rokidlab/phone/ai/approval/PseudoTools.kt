package com.rokidlab.phone.ai.approval

import com.rokidlab.phone.ai.AgentPlan
import com.rokidlab.phone.ai.LongTermMemoryManager
import com.rokidlab.phone.ai.SkillRegistry
import com.rokidlab.phone.ai.ToolRisk

/**
 * L4 agent/approval —— **伪工具**（下发给模型、但不在 `ToolRegistry.toolList` 里的工具）。
 *
 * ## 为什么需要这张表（这是本轮补上的一个真实盲区）
 *
 * `load_skill` / `load_skill_section` / `update_plan` / `manage_memory` 四个工具的 schema
 * 直接拼在 `AiConversationService.buildTools()` 里，执行则走 `runTool()` 的前置 `when` 分支 ——
 * 它们**从未经过任何审批闸门**，也**不在风险表里**。
 *
 * 后果有两个方向：
 * 1. **无档可查**：`ToolRiskMap.riskOf("load_skill")` 会命中"完全未知的名字 → EXTERNAL_SIDE_EFFECT"
 *    的最保守兜底。改造前这没出事只是因为它们绕过了闸门；一旦哪天有人把它们接上闸门
 *    （或写进 `toolList`），就会立刻变成"读技能文件要先经过眼镜端确认，通道不可用就整条链失败"
 *    —— 正是 `save_code_file` 漏登记那次事故的翻版。
 * 2. **无人值守下无准入检查**：`readOnlyTools` 模式装配时刻意不挂伪工具，但 `runTool` 的分支
 *    并不检查这个模式 —— 模型若在自主任务里调出 `load_skill`，仍会被执行。
 *
 * 现在伪工具的风险档在这里**有了唯一产地**：[ToolCallContext.risk] 先问本表，
 * `ApprovalGate.isReadOnly` / `isKnownTool` 也从这里取，三个用途共用一份声明。
 *
 * ⚠️ 改这里的风险档会同时影响「审批闸门」和「无人值守只读名单」，
 * 变更前请确认两处语义都成立。
 */
internal object PseudoTools {

    /**
     * 伪工具名 → 风险档。
     *
     * 取值理由：
     * - `load_skill` / `load_skill_section`：只读本地技能文件（第 1/2.5 层渐进披露），无副作用。
     * - `update_plan`：只把计划文本格式化回填给 UI 展示，无副作用。
     * - `manage_memory`：会**写**长期记忆库（`MAX_ITEMS` 满时还会 FIFO 淘汰旧记忆）——
     *   有真实副作用，因此**不**算只读（保持动作轮计费）。但它是本机存储、影响可控且可撤销，
     *   所以是 [ToolRisk.LOCAL_SIDE_EFFECT] 而不是 EXTERNAL —— 不该走眼镜端确认。
     */
    private val BY_NAME: Map<String, ToolRisk> = linkedMapOf(
        SkillRegistry.TOOL_NAME to ToolRisk.READ_ONLY,
        SkillRegistry.TOOL_NAME_SECTION to ToolRisk.READ_ONLY,
        AgentPlan.TOOL_NAME to ToolRisk.READ_ONLY,
        LongTermMemoryManager.TOOL_NAME to ToolRisk.LOCAL_SIDE_EFFECT,
    )

    /** 伪工具风险档；非伪工具返回 null（由调用方回落到真实工具的风险表） */
    fun riskOf(name: String): ToolRisk? = BY_NAME[name]

    /** 全部伪工具名 */
    fun names(): Set<String> = BY_NAME.keys

    /**
     * 本次会话里"模型可能调到的**全部**工具名" = 真实工具 ∪ 伪工具。
     *
     * 这是判定"未知名"的唯一口径（[UnknownToolGuard] 与 [ApprovalGate.isKnownTool] 共用）。
     * ⚠️ 少算伪工具会造成"接上闸门就误拒 `load_skill`"，多算则放行一个不存在的名字让下游抛异常。
     */
    fun knownToolNames(): Set<String> =
        com.rokidlab.phone.ai.ToolRegistry.allToolNames().toSet() + names()
}
