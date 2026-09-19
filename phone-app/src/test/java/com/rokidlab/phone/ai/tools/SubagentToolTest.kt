package com.rokidlab.phone.ai.tools

import com.rokidlab.phone.ai.ToolRegistry
import com.rokidlab.phone.ai.ToolRisk
import com.rokidlab.phone.ai.approval.PageScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 只读子代理工具（`research_subtask`，方案 §4.3.2）的接线与安全契约。
 *
 * 这个工具最危险的失败方式不是"报错"，而是**悄悄把约束放宽**：
 *  - 被错误标成有副作用 → 主循环失败不重试，行为变化没人发现；
 *  - 被放进 AIUI 代码生成子集 → 在那段最怕卡住的时间里装配一个会发起多轮模型调用的工具；
 *  - 对 AIUI 页面开放 → 页面一次 `callTool` 就能触发若干次模型调用（且无法取消）；
 *  - 域被漏加进 `DOMAIN_ALL` → 模型永远看不到它（编译不报错、单测全绿，见 `ToolEntry` 的类注释）。
 */
class SubagentToolTest {

    private val name = "research_subtask"

    @Test
    fun `research_subtask 已注册且是只读`() {
        val meta = ToolRegistry.toolList.firstOrNull { it.name == name }
        assertNotNull("$name 没在 provider 的 tools() 里声明 —— 模型永远调不到，且不会报错", meta)
        assertFalse("子代理是正常能力，不该藏起来", meta!!.hidden)
        assertEquals(ToolRegistry.DOMAIN_RESEARCH, meta.group)
        assertEquals("子代理只做调研，绝不能登记成有副作用", ToolRisk.READ_ONLY, ToolRegistry.riskOfOrNull(name))
        assertFalse("子代理不下发眼镜类工具，自身也不依赖眼镜", name in ToolRegistry.GLASSES_REQUIRED_TOOLS)
        assertTrue("它要进无人值守只读名单（定时任务也需要查资料）", name in ToolRegistry.readOnlyToolNames())
    }

    @Test
    fun `调研域在主 Agent 装配集内但不在 AIUI 代码生成子集内`() {
        assertTrue("主 Agent 必须能装配它", ToolRegistry.DOMAIN_RESEARCH in ToolRegistry.SESSION_AGENT_DOMAINS)
        assertFalse(
            "代码生成回合不该装配调研工具：它会发起多轮模型调用，拖长这段最怕卡住的时间",
            ToolRegistry.DOMAIN_RESEARCH in ToolRegistry.SESSION_AIUI_DOMAINS,
        )
        assertFalse("本地轻量会话不装配任何工具", ToolRegistry.DOMAIN_RESEARCH in ToolRegistry.SESSION_LOCAL_DOMAINS)
    }

    @Test
    fun `不对 AIUI 页面开放`() {
        val known = PageScope.pageVisibleTools()
        val reason = PageScope.rejectReason(name, known, PageScope::domainOf)
        assertNotNull("页面不该能派子任务：一次 callTool 会触发若干次模型调用，而且无法取消", reason)
        assertTrue("拒绝理由要指明是页面准入限制：$reason", reason!!.contains("not allowed in AIUI pages"))
    }

    @Test
    fun `schema 声明 question 必填与轮次上限`() {
        val meta = ToolRegistry.toolList.first { it.name == name }
        val params = ToolRegistry.buildSchema(meta)
            .getJSONObject("function").getJSONObject("parameters")
        val props = params.getJSONObject("properties")

        val question = props.optJSONObject("question")
        assertNotNull("没有 question，子代理不知道要查什么", question)
        val required = params.getJSONArray("required")
        assertTrue(
            "question 必须是必填 —— 它是子代理唯一的信息来源（它看不到对话）",
            (0 until required.length()).any { required.getString(it) == "question" },
        )

        val rounds = props.optJSONObject("rounds")
        assertNotNull("没有 rounds 就限制不住开销", rounds)
        assertEquals("轮次上限要写在 schema 里（服务端与模型都看得到）", 6, rounds!!.getInt("maximum"))
    }
}
