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
 * 「问不到用户时怎么办」—— 需要确认的工具的**降级策略**。
 *
 * ## 为什么需要它
 * 闸门在**问不到用户**时（无确认通道、或用户没响应）必须二选一：放行还是拒绝。
 * 改造前这个选择是**硬编码**的 —— `ToolCallContext.failClosedConfirmation` 写死
 * `domain == MCP`，等于"按工具属于哪个域"来猜"该不该问到底"。于是出现了两个后果：
 *  - `send_sms`（**真的会把短信发出去**）在无通道时静默放行，与 `call_phone`（只开拨号盘）
 *    享受同一条 fail-open 路径 —— 而两者的代价根本不是一个量级；
 *  - 风险档早就由工具自己声明了，确认策略却还是"域的特例"。同一个思路只做了一半。
 *
 * 现在它跟 [ToolRisk] 一样是工具的**自声明字段**（[com.rokidlab.phone.ai.tools.ToolEntry.confirmPolicy]），
 * 闸门读声明而不是猜域。
 *
 * ## 两个值的区别只在"问不到时"
 * 问得到用户时两者完全一样：用户点允许就执行、点拒绝就拒绝。
 *
 * ★ 刻意**只有两个值**：唯一消费者（`ApprovalGate.resolveAsk` 的无通道分支）只有
 * "放行/拒绝"两种处置。曾考虑第三个值 SAFE_DEGRADE（声明"工具自己会降级为无副作用动作"），
 * 但它的处置与 [PROCEED] 完全相同 ⇒ 会变成一个没人读的字段。
 * 需要时再引入（同 [com.rokidlab.phone.ai.approval.ToolDecision] 里不引入 `hard` 字段的理由）。
 *
 * ⚠️ 默认值对 [ToolRisk.EXTERNAL_SIDE_EFFECT] 是 [BLOCK]（见 [defaultFor]）：
 * 宁可让新工具在问不到用户时**拒绝**，也不要让它静默执行一个越出本机边界的动作。
 * 要放宽成 [PROCEED]，作者必须显式写出来 —— 那就逼他思考一次"这个动作最坏能坏成什么"。
 */
enum class ToolConfirmPolicy {

    /**
     * 问不到用户就**照做**。
     *
     * 用于**影响不出本机、且可重做**的动作：删下载目录的一个文件、删脚本库里一条脚本、
     * 在容器里装个包（容器可丢弃，重下 28.5 MB 即恢复）。问不到人时拒绝它们，
     * 只会让功能表现为"被安全策略挡住"（`save_code_file` 那次事故的方向）。
     *
     * 也涵盖"工具侧自己保证未确认时只做无副作用动作"的情形（如只打开拨号盘不自动拨出）
     * —— 处置相同，无需第三个值。
     */
    PROCEED,

    /**
     * 问不到用户就**拒绝**。
     *
     * 用于**越出本机边界、且不可撤销**的动作：发出短信、调用第三方 MCP server。
     * 用户**显式取消**时两者都会拒绝（那是唯一真正的"用户说不"）；本值额外覆盖
     * "压根问不到"—— 因为这类动作没有"先照做、错了再改"的余地。
     */
    BLOCK,
    ;

    companion object {
        /**
         * 未显式声明时的默认策略。
         *
         * 判据是 [ToolRisk.EXTERNAL_SIDE_EFFECT]（"触达第三方、不可撤销"），
         * 它本来就该蕴含"问不到就别做"。非外部档的工具根本不经过确认闸门
         * （`RiskApprovalGuard` 只对 EXTERNAL 产生 Ask），取什么值都不会被读。
         */
        fun defaultFor(risk: ToolRisk): ToolConfirmPolicy =
            if (risk == ToolRisk.EXTERNAL_SIDE_EFFECT) BLOCK else PROCEED
    }
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
