package com.rokidlab.phone.ai

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具风险表（[ToolRiskMap]）与策略闸门（[ToolPolicy]）回归测试。
 *
 * 核心不变式（RULES §12.10）：`ToolRegistry.toolList` 里的每个工具都必须在 [ToolRiskMap] 登记 ——
 * 漏登记历史上造成过严重事故：`save_code_file` 漏登记时兜底策略是「未登记 → 最保守档」，
 * 于是 AIUI 代码生成工具被要求眼镜端确认 → 确认通道不可用即被拒 → **整条 AIUI 生成链路直接失败**
 * （用户侧只看到「提示眼镜没权限 / 没反应」）。本测试让「漏登记」在 CI 就暴露，而不是在用户手上。
 */
class ToolRiskMapTest {

    @After
    fun tearDown() {
        ToolPolicy.confirmationChannel = null
    }

    @Test
    fun `所有已注册工具都必须在风险表中登记`() {
        // 直接读 toolList：若 ToolRegistry 初始化失败，这里必须显式失败，而不是让下面的自检空跑通过
        assertTrue("工具表不应为空（否则自检形同虚设）", ToolRegistry.toolList.size >= 30)
        assertEquals(
            "存在未登记风险档位的工具（漏登记会让功能静默瘫痪）",
            emptyList<String>(),
            ToolRiskMap.unregisteredTools(),
        )
    }

    @Test
    fun `风险分级抽样：只读 本机副作用 未知`() {
        assertEquals(ToolRisk.READ_ONLY, ToolRiskMap.riskOf("get_current_time"))
        assertEquals(ToolRisk.READ_ONLY, ToolRiskMap.riskOf("read_code_file"))

        assertEquals(ToolRisk.LOCAL_SIDE_EFFECT, ToolRiskMap.riskOf("save_code_file"))
        assertEquals(ToolRisk.LOCAL_SIDE_EFFECT, ToolRiskMap.riskOf("show_image"))
        assertEquals(ToolRisk.LOCAL_SIDE_EFFECT, ToolRiskMap.riskOf("call_phone"))

        // 完全不存在的名字（模型幻觉 / 攻击构造）必须保持最保守
        assertEquals(ToolRisk.EXTERNAL_SIDE_EFFECT, ToolRiskMap.riskOf("hallucinated_tool_xyz"))
    }

    /**
     * 无人值守安全不变式：定时自主任务只装配 [ToolRegistry.readOnlyToolNames]，
     * 该名单里**绝不能**出现任何会改状态的工具 —— 无人监管时跑错一次
     * （半夜拨号 / 装机 / 改设置）代价远高于「少做一点」。
     *
     * 新增工具时若把风险档登记错了（例如给 `call_phone` 标成 READ_ONLY），
     * 本测试立刻失败，而不是等用户在半夜被拨出去一通电话。
     */
    @Test
    fun `无人值守只读名单不含任何副作用工具`() {
        val allowed = ToolRegistry.readOnlyToolNames()
        assertTrue("只读名单不应为空（否则自主任务什么也查不了）", allowed.size >= 10)

        // 有副作用的工具，一个都不能进（覆盖各域的代表：拨号/装机/写文件/改设置/定时/媒体/展示）
        val mustBeExcluded = listOf(
            "call_phone", "set_phone_alarm", "manage_calendar", "set_phone_volume",
            "install_aiui_project", "open_aiui_app", "stop_aiui_app", "save_code_file",
            "save_summary_txt", "manage_timer", "schedule_agent_task",
            "control_music", "show_lyrics", "launch_glasses_app",
            "open_phone_app", "show_image", "clear_agent_task",
        )
        mustBeExcluded.forEach { name ->
            assertTrue(
                "副作用工具 $name 不得进入无人值守只读名单",
                name !in allowed,
            )
        }

        // 查询类能力必须真的可用，否则自主任务只能空口回复
        listOf(
            "get_current_time", "get_weather", "search_web", "fetch_webpage",
            "search_knowledge_base", "get_agent_status", "search_past_conversations",
        ).forEach { name ->
            assertTrue("查询工具 $name 应在只读名单内", name in allowed)
        }
    }

    // ── 风险闸门（fail-open 语义）──

    @Test
    fun `无确认通道时外部副作用工具降级放行而不是硬拒`() {
        ToolPolicy.confirmationChannel = null
        assertEquals(
            "硬拒会让功能表现为「被安全策略挡住」，必须降级放行",
            ToolPolicy.Decision.Allow,
            ToolPolicy.check("test-no-channel", "hallucinated_tool_xyz", JSONObject()),
        )
    }

    @Test
    fun `用户在眼镜上显式取消才拒绝`() {
        ToolPolicy.confirmationChannel = FakeChannel(available = true, confirmed = false, cancelled = true)
        val decision = ToolPolicy.check("test-cancelled", "hallucinated_tool_xyz", JSONObject())
        assertTrue("显式取消必须拒绝", decision is ToolPolicy.Decision.Deny)
        assertTrue((decision as ToolPolicy.Decision.Deny).reason.contains("取消"))
    }

    @Test
    fun `确认超时未响应时降级放行`() {
        ToolPolicy.confirmationChannel = FakeChannel(available = true, confirmed = false, cancelled = false)
        assertEquals(
            ToolPolicy.Decision.Allow,
            ToolPolicy.check("test-timeout", "hallucinated_tool_xyz", JSONObject()),
        )
    }

    @Test
    fun `用户确认后放行`() {
        ToolPolicy.confirmationChannel = FakeChannel(available = true, confirmed = true)
        assertEquals(
            ToolPolicy.Decision.Allow,
            ToolPolicy.check("test-confirmed", "hallucinated_tool_xyz", JSONObject()),
        )
    }

    @Test
    fun `只读工具不触发确认通道`() {
        ToolPolicy.confirmationChannel = FakeChannel(available = true, confirmed = false, cancelled = true)
        assertEquals(
            "只读工具不应被确认闸门拦截",
            ToolPolicy.Decision.Allow,
            ToolPolicy.check("test-readonly", "get_current_time", JSONObject()),
        )
    }

    // ── 限流 ──

    @Test
    fun `AIUI 页面来源每分钟上限 30 次`() {
        // 页面可能死循环刷工具，故 AIUI 页面单独限流 30/min；对话路径是 120/min（不同的桶）
        repeat(30) { i ->
            assertEquals(
                "第 ${i + 1} 次调用应放行",
                ToolPolicy.Decision.Allow,
                ToolPolicy.check(ToolPolicy.SOURCE_AIUI_PAGE, "get_current_time", JSONObject()),
            )
        }
        val overflow = ToolPolicy.check(ToolPolicy.SOURCE_AIUI_PAGE, "get_current_time", JSONObject())
        assertTrue("第 31 次必须被限流", overflow is ToolPolicy.Decision.Deny)
        assertTrue((overflow as ToolPolicy.Decision.Deny).reason.contains("30"))
    }

    /** 确认通道假实现：只关心「可用 / 用户是否同意 / 是否显式取消」三态 */
    private class FakeChannel(
        private val available: Boolean,
        private val confirmed: Boolean,
        private val cancelled: Boolean = false,
    ) : ToolPolicy.ConfirmationChannel {
        override fun isAvailable(): Boolean = available

        override fun requestConfirmation(toolName: String, args: JSONObject): Boolean = confirmed

        override fun wasCancelled(): Boolean = cancelled
    }
}
