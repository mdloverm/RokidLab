package com.rokidlab.phone.ai

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Agent 工具管线金标评测（纯 JVM，无网络/无模型）。
 *
 * 三类用例锁定「用户输入 → 工具被正确选中并执行」链路上所有确定性环节，防提示词/注册/解析回归：
 *   A. SSE 断线重连的重放安全语义（hasEmittedContent 判定是重连不发重复输出的关键）
 *   B. 工具声明完整性（新增工具忘写描述/漏 required/域未注册 → 立即红）
 *   C. 系统提示词路由准则（模型选工具的依据；条款被误删 → 立即红）
 */
class GoldenAgentEvalTest {

    private fun sse(json: String) = "data: $json"

    // ═══════════ A. SSE 重放安全语义（断线重连） ═══════════

    @Test
    fun `A1 工具增量已累积但 content 为空时可安全重放`() {
        val acc = SseStreamAccumulator()
        acc.onSseLine(sse("""{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"get_curr","arguments":"{\"q\""}}]}}]}"""))
        assertTrue("工具增量已开始", acc.hasStarted())
        assertFalse("content 未推送 UI，重放安全", acc.hasEmittedContent())
    }

    @Test
    fun `A2 content 增量一旦出现即不可安全重放`() {
        val acc = SseStreamAccumulator()
        acc.onSseLine(sse("""{"choices":[{"delta":{"content":"你"}}]}"""))
        assertTrue(acc.hasEmittedContent())
    }

    @Test
    fun `A3 reasoning_content 累积不影响重放安全性判定`() {
        val acc = SseStreamAccumulator()
        acc.onSseLine(sse("""{"choices":[{"delta":{"reasoning_content":"思考过程很长"}}]}"""))
        acc.onSseLine(sse("""{"choices":[{"delta":{"reasoning_content":"继续思考"}}]}"""))
        assertEquals(10, acc.reasoningChars)
        assertFalse("推理过程不推给 UI，重放安全", acc.hasEmittedContent())
    }

    @Test
    fun `A4 空转开始前 hasStarted 为 false`() {
        val acc = SseStreamAccumulator()
        acc.onSseLine(": keep-alive 注释行")
        acc.onSseLine(sse("""{"choices":[{"delta":{}}]}"""))
        assertFalse(acc.hasStarted())
        assertFalse(acc.hasEmittedContent())
    }

    @Test
    fun `A5 tool_call id 只在首帧出现也能关联到结果`() {
        val acc = SseStreamAccumulator()
        acc.onSseLine(sse("""{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_x","function":{"name":"play_song"}}]}}]}"""))
        // 后续分片不带 id（真实协议常见），仍应归并到 call_x
        acc.onSseLine(sse("""{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"songName\":\"晴天\"}"}}]}}]}"""))
        acc.onSseLine(sse("""data: [DONE]"""))
        val turn = acc.build()
        assertEquals(1, turn.toolCalls.size)
        assertEquals("call_x", turn.toolCalls[0].id)
        assertEquals("play_song", turn.toolCalls[0].name)
        assertEquals("""{"songName":"晴天"}""", turn.toolCalls[0].arguments)
    }

    @Test
    fun `A6 finish_reason length 截断可被诊断捕获`() {
        val acc = SseStreamAccumulator()
        acc.onSseLine(sse("""{"choices":[{"delta":{"content":"部分"},"finish_reason":null}]}"""))
        acc.onSseLine(sse("""{"choices":[{"delta":{},"finish_reason":"length"}]}"""))
        acc.onSseLine(sse("""data: [DONE]"""))
        assertEquals("length", acc.finishReason)
    }

    // ═══════════ B. 工具声明完整性 ═══════════

    @Test
    fun `B1 全部工具名唯一`() {
        val names = ToolRegistry.toolList.map { it.name }
        assertEquals("工具名重复会使命中/回填错乱", names.size, names.toSet().size)
    }

    @Test
    fun `B2 全部工具 schema 描述非兜底文案`() {
        // buildSchema 走兜底分支（"执行 X 工具"）= 新工具忘写声明，模型几乎不可能选中它
        ToolRegistry.toolList.forEach { meta ->
            val schema = ToolRegistry.buildSchema(meta)
            val desc = schema.getJSONObject("function").getString("description")
            assertTrue(
                "工具 ${meta.name} 的描述是兜底文案（忘写声明）",
                desc.isNotBlank() && !desc.startsWith("执行 ") || desc.length > 40,
            )
        }
    }

    @Test
    fun `B3 statusText 覆盖全部工具无兜底`() {
        ToolRegistry.toolList.forEach { meta ->
            val text = ToolRegistry.statusText(meta.name)
            assertNotEquals("工具 ${meta.name} 缺少进度文案（掉进兜底）", "正在执行 ${meta.name}…", text)
        }
    }

    @Test
    fun `B4 全部工具域均注册在 DOMAIN_ALL`() {
        ToolRegistry.toolList.forEach { meta ->
            assertTrue("工具 ${meta.name} 的域 ${meta.group} 未注册进 DOMAIN_ALL", meta.group in ToolRegistry.DOMAIN_ALL)
        }
    }

    @Test
    fun `B5 AIUI 会话域仅含 aiui files info`() {
        assertEquals(setOf(ToolRegistry.DOMAIN_AIUI, ToolRegistry.DOMAIN_FILES, ToolRegistry.DOMAIN_INFO), ToolRegistry.SESSION_AIUI_DOMAINS)
    }

    @Test
    fun `B6 本地轻量会话域为空集`() {
        assertTrue("本地小模型不应装配任何工具", ToolRegistry.SESSION_LOCAL_DOMAINS.isEmpty())
    }

    @Test
    fun `B7 核心工具的 required 参数声明完整`() {
        fun requiredOf(name: String): List<String> {
            val meta = ToolRegistry.toolList.first { it.name == name }
            val params = ToolRegistry.buildSchema(meta).getJSONObject("function").getJSONObject("parameters")
            return params.optJSONArray("required")?.let { arr ->
                (0 until arr.length()).map { arr.getString(it) }
            } ?: emptyList()
        }
        assertTrue(requiredOf("search_knowledge_base").contains("query"))
        assertTrue(requiredOf("search_web").contains("query"))
        assertTrue(requiredOf("fetch_webpage").contains("url"))
        assertTrue(requiredOf("play_song").contains("songName"))
        assertTrue(requiredOf("set_timer").containsAll(listOf("time", "content")))
        assertTrue(requiredOf(ToolRegistry.TOOL_CODE_FILE).containsAll(listOf("project", "file", "content")))
        assertTrue(requiredOf("launch_glasses_app").contains("appName"))
    }

    // ═══════════ C. 系统提示词路由准则 ═══════════

    private val service = OpenAiService(apiKey = "test-key")

    @Test
    fun `C1 在线模式包含工具使用准则与禁止编造条款`() {
        val sys = service.buildSystemMessage().getString("content")
        assertTrue(sys.contains("【工具使用准则】"))
        assertTrue("必须禁止编造工具结果", sys.contains("严禁编造"))
        assertTrue("必须支持多工具连续调用", sys.contains("连续调用多个工具"))
        assertTrue("失败必须如实告知", sys.contains("如实告知"))
    }

    @Test
    fun `C2 在线模式包含长期记忆指令`() {
        val sys = service.buildSystemMessage().getString("content")
        assertTrue("缺 manage_memory 指令（模型不会主动记忆用户偏好）", sys.contains("manage_memory"))
    }

    @Test
    fun `C3 在线模式包含代码落盘纪律`() {
        val sys = service.buildSystemMessage().getString("content")
        assertTrue("缺 save_code_file 落盘指令", sys.contains("save_code_file"))
        assertTrue("缺「严禁源码当回复」条款", sys.contains("严禁把大段代码"))
    }

    @Test
    fun `C4 本地模式包含能力禁令与切换建议`() {
        val sys = service.buildSystemMessage(localMode = true).getString("content")
        assertTrue("缺本地模式禁令", sys.contains("本地轻量模式"))
        assertTrue("缺禁止编造要求", sys.contains("不要编造"))
        assertTrue("缺切回在线建议", sys.contains("切换到联网"))
        assertFalse("本地模式不得出现工具准则", sys.contains("【工具使用准则】"))
    }

    @Test
    fun `C5 memories 段按需注入`() {
        val with = service.buildSystemMessage(memories = "用户喜欢周杰伦").getString("content")
        assertTrue(with.contains("<memories>"))
        assertTrue(with.contains("用户喜欢周杰伦"))
        val without = service.buildSystemMessage().getString("content")
        assertFalse("无记忆时不注入空段（省 token）", without.contains("<memories>"))
    }

    @Test
    fun `C6 skills 与知识库资料按需注入`() {
        val with = service.buildSystemMessage(
            contextText = "知识库资料：xxx",
            skills = "skill: demo",
        ).getString("content")
        assertTrue(with.contains("<skills>"))
        assertTrue(with.contains("知识库中检索到的参考资料"))
        val without = service.buildSystemMessage().getString("content")
        assertFalse(without.contains("<skills>"))
        assertFalse(without.contains("知识库中检索到的参考资料"))
    }

    @Test
    fun `C7 答题指令按需注入`() {
        val with = service.buildSystemMessage(instruction = "只给出最终答案").getString("content")
        assertTrue(with.contains("只给出最终答案"))
        val without = service.buildSystemMessage().getString("content")
        assertFalse(without.contains("请遵守以下答题要求"))
    }

    @Test
    fun `C8 历史消息按序组装进 messages`() {
        // chatTurn(userMessage, history) 的组装顺序：system → history → user
        val history = listOf(
            ChatMessage("user", "第一问"),
            ChatMessage("assistant", "第一答"),
        )
        val service = OpenAiService(apiKey = "test-key")
        // 无法直接断言内部数组（chatTurn 会发网络请求），改为验证 system 消息组装入口行为：
        // buildSystemMessage 是 messages[0]，此处锁定「历史由调用方传入且顺序即插入顺序」的契约
        // 通过 AgentSessionHistory 的 recordTurn 快照间接锁定
        val store = AgentSessionHistory(clock = { 1000L })
        store.recordTurn("第一问", "第一答")
        val h = store.getHistory()
        assertEquals("user", h[0].role)
        assertEquals("第一问", h[0].content)
        assertEquals("assistant", h[1].role)
        assertEquals("第一答", h[1].content)
    }

    // ═══════════ 附加：RAG 评分与记忆检索纯逻辑 ═══════════

    @Test
    fun `C9 金标多工具序列 search_web 后接 fetch_webpage 完整解析`() {
        // 典型两步检索链路的完整 SSE 轨迹金标：模型一轮返回 2 个工具调用，
        // 解析器必须产出按序、参数完整的 ToolCallInfo 列表供循环执行
        val acc = SseStreamAccumulator()
        acc.onSseLine(sse("""{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c1","function":{"name":"search_web","arguments":"{\"quer"}}]}}]}"""))
        acc.onSseLine(sse("""{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"y\":\"Rokid 评测\"}"}}]}}]}"""))
        acc.onSseLine(sse("""{"choices":[{"delta":{"tool_calls":[{"index":1,"id":"c2","function":{"name":"fetch_webpage","arguments":"{\"url\":\"https://e.com/a\"}"}}]}}]}"""))
        acc.onSseLine(sse("""{"choices":[{"delta":{},"finish_reason":"tool_calls"}]}"""))
        acc.onSseLine("""data: [DONE]""")
        val turn = acc.build()
        assertEquals(2, turn.toolCalls.size)
        assertEquals("search_web", turn.toolCalls[0].name)
        assertEquals("""{"query":"Rokid 评测"}""", turn.toolCalls[0].arguments)
        assertEquals("fetch_webpage", turn.toolCalls[1].name)
        assertEquals("https://e.com/a", JSONObject(turn.toolCalls[1].arguments).getString("url"))
        assertEquals("tool_calls", acc.finishReason)
    }

    @Test
    fun `C10 工具输出截断保护上下文`() {
        val long = "x".repeat(5000)
        val truncated = truncateToolOutput(long)
        assertTrue(truncated.length < 2000)
        assertTrue(truncated.contains("已截断"))
        assertEquals("短输出原样返回", "ok", truncateToolOutput("ok"))
    }
}
