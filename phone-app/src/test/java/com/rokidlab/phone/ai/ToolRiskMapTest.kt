package com.rokidlab.phone.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具风险表（[ToolRiskMap]）回归测试。
 *
 * 核心不变式（RULES §12.10）：`ToolRegistry.toolList` 里的每个工具都必须在 [ToolRiskMap] 登记 ——
 * 漏登记历史上造成过严重事故：`save_code_file` 漏登记时兜底策略是「未登记 → 最保守档」，
 * 于是 AIUI 代码生成工具被要求眼镜端确认 → 确认通道不可用即被拒 → **整条 AIUI 生成链路直接失败**
 * （用户侧只看到「提示眼镜没权限 / 没反应」）。本测试让「漏登记」在 CI 就暴露，而不是在用户手上。
 *
 * ★ 接缝化后风险档是每个工具自己的声明字段（`ToolEntry.risk`），"漏登记"在结构上已不可能发生；
 * `unregisteredTools()` 保留下来是为了让这条不变式断言继续有意义 —— 一旦有人把风险档从工具声明里
 * 摘掉、或新增了绕过 provider 注册的工具，它会立刻不为空。
 *
 * ⚠️ 审批闸门相关的用例（per-source 限流、眼镜端确认、fail-open 三态、页面域白名单）已迁到
 * `ai/approval/ApprovalGateTest` —— 那部分判定已从 `ToolPolicy` 收敛进 `ApprovalGate`。
 */
class ToolRiskMapTest {

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

        // 完全不存在的名字（模型幻觉 / 攻击构造）必须保持最保守档
        // （注意：它在审批链里会先被 UnknownToolGuard 拦下，见 ApprovalGateTest.B3/B4）
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

    /**
     * 无人值守名单 = 只读名单 ∪ 本地媒体白名单，且白名单**只**放媒体。
     *
     * 背景：「16 点帮我放首《断桥残雪》」的定时自主任务原先装配纯只读工具集，
     * `control_music` 被排除 → 模型看不到播歌工具，只能把歌名念到眼镜上，
     * 用户侧表现为「明明有播歌工具却只跑到眼镜上」。本测试锁死修复后的边界：
     *  - 放行本机可撤销的媒体工具（control_music）；
     *  - 其余副作用工具（拨号/装机/写文件/设定时/开眼镜应用）依旧进不来；
     *  - `show_lyrics` 仍不放行（它会拉起眼镜系统音乐页，越出"本机可撤销"的边界）。
     */
    @Test
    fun `无人值守名单只比只读名单多放本地媒体`() {
        val readOnly = ToolRegistry.readOnlyToolNames()
        val unattended = ToolRegistry.unattendedToolNames()

        assertEquals(
            "增量必须与白名单完全一致",
            ToolRegistry.UNATTENDED_MEDIA_ALLOWLIST,
            unattended - readOnly,
        )
        assertTrue("放歌工具必须可用", "control_music" in unattended)
        assertTrue("只读名单本身仍不含放歌工具（只读子代理不受影响）", "control_music" !in readOnly)

        listOf(
            "show_lyrics", "call_phone", "set_phone_alarm", "set_phone_volume",
            "install_aiui_project", "open_aiui_app", "save_code_file",
            "manage_timer", "schedule_agent_task", "launch_glasses_app", "show_image",
        ).forEach { name ->
            assertTrue("$name 不得借白名单之名进入无人值守名单", name !in unattended)
        }
    }
}
