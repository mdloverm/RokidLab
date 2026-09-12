package com.rokidlab.phone.ai

import android.util.Log
import org.json.JSONObject

/**
 * L4 agent/ToolPolicy —— 工具调用策略闸门（架构文档 §3.5：域过滤 + 风险闸门 + per-page 限流 + 审计）。
 *
 * 每次工具执行（AIUI 页面路径 / 对话路径）都必须先过 [check]：
 * 1. **per-source 限流**：AIUI 页面 30/min（防页面死循环刷工具）、对话路径 120/min（仅兜底失控循环）。
 * 2. **风险闸门**：[ToolRisk.EXTERNAL_SIDE_EFFECT]（触达第三方、不可撤销的动作）需经用户确认。
 *    **实际语义（fail-open，与代码一致）**：确认通道可用 → 眼镜端确认，用户**显式取消**才拒绝；
 *    通道不可用 / 眼镜端旧版 / 确认超时 → **降级放行**，由工具侧保证「未获确认时只做无副作用动作」
 *    （如 `call_phone` 只打开拨号盘，绝不自动拨出）。**绝不硬拒** —— 硬拒会让功能表现为
 *    「被安全策略挡住」（2026-09-11 实测：用户已授电话权限却打不出电话）。
 * 3. **审计**：每次决策打一行 `ToolPolicy` 日志（LogCollector 可观测）， Allow/Deny + 原因。
 *
 * 线程安全：check 可能从多个工具线程并发调用，内部已同步。
 */
object ToolPolicy {
    private const val TAG = "ToolPolicy"

    /**
     * 每来源每分钟调用上限。
     *
     * - AIUI 页面（模型生成的 .ink 在眼镜端跑，可能死循环刷工具）：30/min。
     * - 对话路径（用户驱动的多轮 Agent 工具循环）：120/min。
     *
     * ⚠️ 历史教训：曾对两个来源统一用 30/min，会打断正常的多轮工具循环
     * （一轮里多次读写文件 + 查资料，很快触顶 → 用户看到莫名的「调用过于频繁」）。
     * 对话路径的上限只用于兜底失控循环，不应影响正常使用。
     */
    private const val RATE_LIMIT_AIUI_PAGE = 30
    private const val RATE_LIMIT_CONVERSATION = 120

    private fun limitFor(source: String): Int =
        if (source == SOURCE_AIUI_PAGE) RATE_LIMIT_AIUI_PAGE else RATE_LIMIT_CONVERSATION
    private const val WINDOW_MS = 60_000L

    /** 调用来源标识（限流按来源隔离） */
    const val SOURCE_AIUI_PAGE = "aiui-page"
    const val SOURCE_CONVERSATION = "conversation"

    sealed class Decision {
        data object Allow : Decision()
        data class Deny(val reason: String) : Decision()
    }

    /**
     * 副作用工具的用户确认通道（眼镜端 `__lab/tool_call_sync` 阻塞端点，渲染"是否拨打 XXX"确认页）。
     * 常驻单例由 `GlassToolConfirmChannel.global` 注入（见 `CxrLHiRokidSession`）。
     * null / [ConfirmationChannel.isAvailable] 为 false 时 → **降级放行**（fail-open），
     * 由工具侧自行保证「未获确认只做无副作用动作」。
     */
    interface ConfirmationChannel {
        /** 确认通道当前是否可用（眼镜端已升级且连接在线） */
        fun isAvailable(): Boolean

        /** 阻塞等待用户确认（仅在 [isAvailable] 为 true 时调用）。返回 true=用户同意执行 */
        fun requestConfirmation(toolName: String, args: JSONObject): Boolean

        /** 上一次确认是否被用户**显式取消**（true=取消 → 拒绝；false=超时/未响应 → 降级） */
        fun wasCancelled(): Boolean = false
    }

    /** 眼镜端确认通道（[GlassToolConfirmChannel.global] 注入；null 时 EXTERNAL_SIDE_EFFECT 降级放行） */
    @Volatile
    var confirmationChannel: ConfirmationChannel? = null

    // ── per-source 滑动窗口限流 ──
    private val timestamps = HashMap<String, ArrayDeque<Long>>()

    private fun acquire(source: String): Boolean = synchronized(timestamps) {
        val now = System.currentTimeMillis()
        val limit = limitFor(source)
        val q = timestamps.getOrPut(source) { ArrayDeque() }
        while (q.isNotEmpty() && now - q.first() > WINDOW_MS) q.removeFirst()
        if (q.size >= limit) {
            false
        } else {
            q.addLast(now)
            true
        }
    }

    /**
     * 工具执行前的统一策略检查。
     *
     * @param source 来源标识（[SOURCE_AIUI_PAGE] / [SOURCE_CONVERSATION]，限流按此隔离）
     * @param name 工具名
     * @param args 已归一化的参数 JSON 对象
     */
    fun check(source: String, name: String, args: JSONObject): Decision {
        val risk = ToolRiskMap.riskOf(name)

        // 1. per-source 限流
        if (!acquire(source)) {
            val limit = limitFor(source)
            audit(source, name, risk, "DENY", "rate limit $limit/min exceeded")
            return Decision.Deny("工具调用过于频繁（每分钟上限 $limit 次），请稍后再试")
        }

        // 2. 风险闸门：外部副作用需确认；通道不可用/超时/旧版眼镜端一律降级放行（fail-open）
        if (risk == ToolRisk.EXTERNAL_SIDE_EFFECT) {
            val channel = confirmationChannel
            if (channel == null || !channel.isAvailable()) {
                // 不硬拒（硬拒会把功能表现为「被安全策略挡住」）→ 降级放行：
                // 工具侧在未获确认时只做无副作用动作（call_phone 只打开拨号盘，绝不自动拨出）
                audit(source, name, risk, "ALLOW", "downgraded (no confirmation channel)")
                return Decision.Allow
            }
            val confirmed = runCatching { channel.requestConfirmation(name, args) }.getOrDefault(false)
            if (!confirmed) {
                if (channel.wasCancelled()) {
                    audit(source, name, risk, "DENY", "user rejected")
                    return Decision.Deny("你已在眼镜上取消，操作未执行：'$name'")
                }
                // 超时/无响应：同样降级放行（无副作用动作）
                audit(source, name, risk, "ALLOW", "downgraded (confirm timeout)")
                return Decision.Allow
            }
        }

        audit(source, name, risk, "ALLOW", "")
        return Decision.Allow
    }

    /** 审计日志：一行决策记录，LogCollector 系统日志面板可直接观测 */
    private fun audit(source: String, name: String, risk: ToolRisk, outcome: String, detail: String) {
        if (detail.isEmpty()) {
            Log.i(TAG, "audit: source=$source tool=$name risk=$risk -> $outcome")
        } else {
            Log.i(TAG, "audit: source=$source tool=$name risk=$risk -> $outcome ($detail)")
        }
    }
}
