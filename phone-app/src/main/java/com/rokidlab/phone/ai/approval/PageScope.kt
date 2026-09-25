package com.rokidlab.phone.ai.approval

import com.rokidlab.phone.ai.ToolRegistry

/**
 * L4 agent/approval —— AIUI 页面的工具**准入域**判定（纯函数 + 策略常量，可直接单测）。
 *
 * ## 为什么单独抽出来
 *
 * 这段判定改造前长在 `ToolGateway.precheckToolCall` 里，和"参数是不是合法 JSON"混在一个函数。
 * 前者是**审批**（这个动作允不允许跑），后者是**传参形状校验**（这个请求本身合法吗），
 * 两者的失败处理、调用方、可变性都不同。混在一起导致：
 * AIUI 页面路径有一份准入判定、对话路径完全没有，两边永远在漂移。
 *
 * 现在判定逻辑与**文案**都只在这里一份：
 * - `ToolGateway` 调用它做页面入口校验（保持历史文案）；
 * - [PageScopeGuard] 在审批链里调用它（同样直接拒绝）。
 *
 * ⚠️ **文案被页面侧按字面分支处理**（页面 JS 见到 `unknown tool:` 前缀会提示"工具不存在"，
 * 见到 `domain ...` 会提示"该能力在此页面不可用"）。改文案 = 改协议，务必同步改页面模板
 * 与本文件的回归测试。
 */
internal object PageScope {

    /**
     * 页面可调用的工具域。
     *
     * 基线是"全部开放"（用户 2026-09-09 拍板），**减去本机执行域** —— 想收窄时只改这一处，
     * 调用逻辑不用动（`ToolGateway.ALLOWED_DOMAINS` 是本常量的别名，页面看到的工具清单
     * 与准入判定都由它派生，不会出现"页面列表里有、调用被拒"的错位）。
     *
     * ## 为什么摘掉 `shell` 域：这不是"逐工具安全判断"，而是**能力形态**的边界
     * `run_shell` 是全表唯一的**任意命令执行**原语。AIUI 页面是**第三方制品**
     * （对话生成、或用户从商店导入的 `.aix`），开放它等于把"在这台手机上执行任意命令"
     * 交给页面作者 —— 而用户对页面的心智是「显示点东西」，不是「可以在我手机上跑命令」。
     *
     * 为什么不用 `DENY_TOOLS` 表达（那里 5 条里已有隐私/成本类，加一条似乎更省事）：
     *  - `RULES.md §AIUI` 把 `DENY_TOOLS` 的定位钉死在「**技术故障类**」，
     *    并要求"不做逐工具安全判断"（安全边界归 `isEnabled` 总开关）；
     *  - 在**域**上表达还有一层实际好处：域是装配单位，摘掉后页面拿到的**工具清单**里
     *    也不会有它（只有黑名单的话，页面仍会在列表里看见 `run_shell` 却调不动）。
     *
     * 对话路径（用户本人在场、可追问、可撤销）**保留**该能力，这是产品意图。
     *
     * ## 为什么也摘掉 `screen` 域（2026-09-23 同一条判据）
     * `tap_screen` 是全表唯一的「在整台手机上注入任意触摸」原语 —— 与 `run_shell` 同属
     * **能力形态**越界，而不是"某个工具危险"：页面作者只要愿意，就能让手机点开银行 App、
     * 点掉一个确认框。页面是第三方制品（模型生成 / 商店导入的 `.aix`），
     * 用户对它的心智是「显示点东西」，不是"可以操作我的手机"。
     * 同样地，用**域**表达还顺带把工具清单也从页面里摘掉了（页面连 `read_screen` 都看不到），
     * 而不是"看得见却调不动"。
     */
    val ALLOWED_DOMAINS: Set<String> =
        ToolRegistry.DOMAIN_ALL - ToolRegistry.DOMAIN_SHELL - ToolRegistry.DOMAIN_SCREEN

    /**
     * 例外：不开放给页面的工具。
     *
     * **技术故障类**：`open_aiui_app` 会让页面去打开一个 AIUI 应用 —— 页面很可能打开它自己，
     * 形成「启动 → 页面又启动」的自指递归。其余 AIUI 管理工具（install/stop/list）保留开放。
     *
     * **隐私类**：会话查询工具族（`list_sessions` / `read_session` / `session_trace`）。
     * AIUI 页面是**第三方制品**（模型生成或用户导入的 `.aix`），而这三个工具合起来
     * 等于"枚举全部会话 + 整段导出事件流（含工具参数与返回值）"。页面是**展示层**，
     * 没有需要跨会话推理的长任务，给它这个能力只有代价。
     * ⚠️ `search_past_conversations` **保持开放** —— 它是本次改造之前就有的关键词检索
     * （只返回最近一段、每条 220 字），收紧它会是一次未经确认的行为变更；
     * 这里的界线是「**不让页面做批量枚举与导出**」，不是"页面不能碰任何历史"。
     *
     * **成本类**：`research_subtask`（只读子代理）。它会在页面的一次 `callTool` 里
     * **再发起若干次模型调用**，而页面侧既看不到过程、也无法中途取消（页面桥是
     * fire-and-forget + onMessage，没有取消通道）。页面想"查资料"，直接调
     * `search_web` / `fetch_webpage` 就够了 —— 那些是单次调用。
     *
     * 注意：安全边界由 `ToolGateway.isEnabled` 总开关负责，这里不做逐工具安全判断。
     * ⚠️ 这条纪律意味着**不能**因为"某个工具很危险"就往本表里加名字 ——
     * 那类收窄要落在**域**上（见 [ALLOWED_DOMAINS] 对 `shell` 域的处理）。
     */
    val DENY_TOOLS: Set<String> = setOf(
        "open_aiui_app",
        "list_sessions",
        "read_session",
        "session_trace",
        "research_subtask",
    )

    /**
     * 判定一个工具名能否在 AIUI 页面里调用。返回**拒绝文案**；`null` = 通过。
     *
     * 依次校验：黑名单 → 未知工具 → 域白名单。空名的检查不在这里
     * （那是调用入口的输入校验，见 `ToolGateway.precheckToolCall`）。
     *
     * @param knownTools  页面可见的工具名（**不含伪工具** —— 伪工具从来不开放给页面）
     * @param toolDomain  工具名 → 域；返回 null 视为未知工具（防御注册表与域映射不一致）
     */
    fun rejectReason(
        toolName: String,
        knownTools: Set<String>,
        toolDomain: (String) -> String?,
        allowedDomains: Set<String> = ALLOWED_DOMAINS,
        denyTools: Set<String> = DENY_TOOLS,
    ): String? {
        if (toolName in denyTools) {
            return "tool '$toolName' is not allowed in AIUI pages"
        }
        if (toolName !in knownTools) {
            return "unknown tool: $toolName"
        }
        val domain = toolDomain(toolName)
            // 防御性分支：注册表说存在、域映射却给 null —— 不放行
            ?: return "unknown tool: $toolName"
        if (domain !in allowedDomains) {
            return "domain '$domain' of tool '$toolName' is not allowed in AIUI pages"
        }
        return null
    }

    /** 生产路径的默认 `toolDomain`：查真实工具注册表 */
    fun domainOf(toolName: String): String? =
        ToolRegistry.toolList.firstOrNull { it.name == toolName }?.group

    /** 生产路径的默认 `knownTools`：真实工具注册表（伪工具不在其中，符合"不开放给页面"） */
    fun pageVisibleTools(): Set<String> =
        ToolRegistry.toolList.map { it.name }.toSet()
}
