package com.rokidlab.phone.ai.tools

import com.rokidlab.phone.ai.ToolRegistry
import com.rokidlab.phone.ai.ToolRisk
import com.rokidlab.phone.ai.approval.PageScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话查询工具族（方案 §4.3.1）的**接线契约**测试。
 *
 * 为什么值得单独钉：这几个工具"没接上"是**完全静默**的 —— 不在 provider 的 `tools()` 里声明
 * 就永远不会被模型调到，而编译不错、不抛异常、其余单测照绿（`list_glasses_apps` 曾这样
 * 被静默删掉，见 `ToolEntry` 的类注释）。本测试就是那条线的哨兵。
 *
 * 同时钉住两件与安全相关的事：
 *  1. **只能读** —— 它们在无人值守的定时自主任务名单里（那里的承诺是"绝不改状态"）；
 *  2. **不对 AIUI 页面开放** —— 页面是第三方制品（模型生成 / 用户导入的 `.aix`），
 *     `list_sessions` + `read_session` 合起来等于"枚举全部会话 + 整段导出事件流"。
 */
class SessionQueryToolsTest {

    private val sessionTools = listOf("list_sessions", "read_session", "session_trace")

    private fun metaOf(name: String) = ToolRegistry.toolList.firstOrNull { it.name == name }

    @Test
    fun `三个会话查询工具都已注册且对模型可见`() {
        sessionTools.forEach { name ->
            val meta = metaOf(name)
            assertNotNull("$name 没在 provider 的 tools() 里声明 —— 模型永远调不到，且不会报错", meta)
            assertFalse("会话查询是正常能力，不该藏在设置页外", meta!!.hidden)
            assertEquals("$name 应归 info 域（与 search_past_conversations 同域）", ToolRegistry.DOMAIN_INFO, meta.group)
            assertEquals("$name 必须是只读档", ToolRisk.READ_ONLY, ToolRegistry.riskOfOrNull(name))
            assertFalse("$name 是纯本地读取，不依赖眼镜", name in ToolRegistry.GLASSES_REQUIRED_TOOLS)
            assertFalse("$name 无副作用，不该进 SIDE_EFFECT_TOOLS", name in ToolRegistry.SIDE_EFFECT_TOOLS)
            assertTrue(
                "$name 需要一条过程文案（否则过程时间线显示黑话「正在执行 $name…」）",
                ToolRegistry.statusText(name) != "正在执行 $name…",
            )
        }
    }

    @Test
    fun `会话查询工具进无人值守只读名单`() {
        val allowed = ToolRegistry.readOnlyToolNames()
        sessionTools.forEach { name ->
            assertTrue("$name 是只读的，定时自主任务也该能用", name in allowed)
        }
    }

    @Test
    fun `会话查询工具不对 AIUI 页面开放`() {
        val known = PageScope.pageVisibleTools()
        sessionTools.forEach { name ->
            val reason = PageScope.rejectReason(name, known, PageScope::domainOf)
            assertNotNull("$name 不该能从 AIUI 页面调用", reason)
            assertTrue("拒绝理由要指明是页面准入限制：$reason", reason!!.contains("not allowed in AIUI pages"))
        }
        // 反向断言：普通只读工具仍要开放 —— 防止 DENY 集合被误写成"拒绝一切"
        assertNull(
            "get_current_time 应仍可从页面调用",
            PageScope.rejectReason("get_current_time", known, PageScope::domainOf),
        )
    }

    @Test
    fun `read_session 声明了会话 续读位置与限量参数`() {
        val meta = metaOf("read_session")!!
        val props = ToolRegistry.buildSchema(meta)
            .getJSONObject("function").getJSONObject("parameters").getJSONObject("properties")
        assertNotNull("没有 sessionId，模型选不中要读哪个会话", props.optJSONObject("sessionId"))
        assertNotNull("没有 fromSeq 就没法接着读超长历史", props.optJSONObject("fromSeq"))
        assertNotNull("没有 limit 就可能一次吃光上下文", props.optJSONObject("limit"))
    }

    @Test
    fun `session_trace 可以只按轮次定位`() {
        val meta = metaOf("session_trace")!!
        val props = ToolRegistry.buildSchema(meta)
            .getJSONObject("function").getJSONObject("parameters").getJSONObject("properties")
        assertNotNull(props.optJSONObject("turn"))
        assertNotNull(props.optJSONObject("sessionId"))
    }
}
