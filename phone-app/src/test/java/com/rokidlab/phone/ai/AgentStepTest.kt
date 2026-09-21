package com.rokidlab.phone.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AgentStep] 的纯逻辑回归测试。
 *
 * 为什么值得锁：手机端「过程」卡片上**工具那一步是成功还是失败**完全由
 * [AgentStep.isFailureResult] 从工具的返回文案里推断 —— 工具契约是「回一句人话」而不是抛异常，
 * 所以这里一旦把正常返回误判成失败，用户看到的每一行工具记录都会挂红叉（反之失败被当成功，
 * 就完全失去了"过程可见"的意义）。
 */
class AgentStepTest {

    // ── 失败判定 ──

    @Test
    fun `框架自己产出的失败文案被判定为失败`() {
        assertTrue(
            AgentStep.isFailureResult(
                "工具执行失败: connect timeout。请先分析失败原因再决定下一步：参数是否正确？"
            )
        )
        assertTrue(AgentStep.isFailureResult("工具 install_apk 被安全策略拦截：外部副作用未确认。"))
        assertTrue(AgentStep.isFailureResult("unknown tool: music_play"))
        assertTrue(AgentStep.isFailureResult("未知工具: music_play"))
    }

    @Test
    fun `空结果是失败`() {
        assertTrue(AgentStep.isFailureResult(null))
        assertTrue(AgentStep.isFailureResult(""))
        assertTrue(AgentStep.isFailureResult("   "))
    }

    @Test
    fun `业务上不理想但正常返回的文案算成功`() {
        // 这些是工具的正常返回，只是结果不理想：不该挂红叉，否则过程卡片全是失败噪音
        assertFalse(AgentStep.isFailureResult("没有找到歌曲《不存在》，请换个歌名试试"))
        assertFalse(AgentStep.isFailureResult("当前没有正在播放的音乐：先调用 control_music。"))
        assertFalse(AgentStep.isFailureResult("已开始播放《西厢》 - 后弦"))
    }

    // ── 覆盖键与状态 ──

    @Test
    fun `同一工具调用的两段式共用同一个覆盖键`() {
        val running = AgentStep.tool("call_1", "control_music", AgentStep.State.RUNNING, argsRaw = "{\"songName\":\"西厢\"}")
        val done = AgentStep.tool("call_1", "control_music", AgentStep.State.OK, argsRaw = "{\"songName\":\"西厢\"}", result = "已开始播放《西厢》")
        assertEquals("先 RUNNING 后完成必须能覆盖同一行", running.key, done.key)
        assertEquals("tool:call_1", running.key)
        assertEquals(AgentStep.State.RUNNING, running.state)
        assertEquals(AgentStep.State.OK, done.state)
    }

    @Test
    fun `不同轮次的同名工具不会互相覆盖`() {
        val a = AgentStep.tool("call_1", "read_code_file", AgentStep.State.OK)
        val b = AgentStep.tool("call_2", "read_code_file", AgentStep.State.OK)
        assertFalse(a.key == b.key)
    }

    @Test
    fun `思考步骤按轮次共用一个覆盖键`() {
        val a = AgentStep.thinking(0)
        val b = AgentStep.thinking(0, detail = "推理中", state = AgentStep.State.OK)
        val c = AgentStep.thinking(1)
        assertEquals(a.key, b.key)
        assertFalse(a.key == c.key)
    }

    @Test
    fun `思考步骤标题恒为空由 UI 本地化`() {
        // 服务层不产出面向用户的文案（i18n 归属 UI），标题必须留给 UI 填
        assertEquals("", AgentStep.thinking(0).title)
        assertEquals("", AgentStep.thinking(0, detail = "x", state = AgentStep.State.OK).title)
    }

    // ── 摘要压缩 ──

    @Test
    fun `多行结果被压成一行避免卡片高度失控`() {
        val step = AgentStep.tool(
            "call_1", "adb_shell", AgentStep.State.OK,
            result = "line1\nline2\n\nline3   spaced",
        )
        assertFalse("不该残留换行", step.detail.contains('\n'))
        assertTrue(step.detail.contains("line1 line2 line3 spaced"))
    }

    @Test
    fun `超长结果被截断并加省略号`() {
        val step = AgentStep.tool("call_1", "dumpsys", AgentStep.State.OK, result = "a".repeat(2000))
        assertTrue(step.detail.length <= AgentStep.MAX_DETAIL_CHARS + 8)
        assertTrue(step.detail.endsWith("…"))
    }

    @Test
    fun `超长参数被截断`() {
        val step = AgentStep.tool(
            "call_1", "save_code_file", AgentStep.State.RUNNING,
            argsRaw = "{\"content\":\"" + "x".repeat(5000) + "\"}",
        )
        assertTrue(step.detail.endsWith("…"))
    }

    @Test
    fun `截断不切断 UTF-16 代理对`() {
        // emoji 占两个 char：从中间切会留下孤立高代理项，Compose 会渲染成方块
        val emoji = "\uD83D\uDE00"
        val args = "{\"x\":\"" + emoji.repeat(AgentStep.MAX_ARGS_CHARS) + "\"}"
        val step = AgentStep.tool("call_1", "tool", AgentStep.State.RUNNING, argsRaw = args)
        val cut = step.detail.removeSuffix("…")
        assertFalse(
            "末字符不能是孤立的高代理项",
            cut.isNotEmpty() && Character.isHighSurrogate(cut[cut.length - 1]),
        )
    }

    // ── 知识库自动检索（不是模型调的工具） ──

    @Test
    fun `知识库检索键固定且标题留给 UI 本地化`() {
        val step = AgentStep.knowledge(hitCount = 2, sources = listOf("《问.txt》第1块"), docCount = 1)
        assertEquals("一轮最多一条，不需要区分轮次", "kb:auto", step.key)
        assertEquals(AgentStep.Kind.KNOWLEDGE, step.kind)
        // 与 thinking 同规矩：服务层不产出面向用户的文案
        assertEquals("", step.title)
        assertEquals(AgentStep.State.OK, step.state)
    }

    @Test
    fun `知识库检索必须如实标为 KNOWLEDGE 而不是 TOOL`() {
        // 复用 TOOL 会让"模型为什么没调检索工具"这种排查被误导（两者语义不同）
        assertFalse(AgentStep.knowledge(1, listOf("《a》第1块"), 1).kind == AgentStep.Kind.TOOL)
    }

    @Test
    fun `命中时把全部来源标注写进 detail 供用户核对出处`() {
        val step = AgentStep.knowledge(
            hitCount = 3,
            sources = listOf("《问.txt》第1块", "《问.txt》第15块", "《手册.txt》第2块"),
            docCount = 2,
        )
        assertTrue(step.detail.contains("《问.txt》第1块"))
        assertTrue("第二份文档的来源也要在，否则用户核对不到", step.detail.contains("《手册.txt》第2块"))
        assertFalse(step.detail.contains('\n'))
    }

    @Test
    fun `未命中时说明库里有多少份文档`() {
        // 用户最需要区分的是"库里没有"还是"有但没匹配上" —— 这句话就是那个区分
        val step = AgentStep.knowledge(hitCount = 0, sources = emptyList(), docCount = 3)
        assertTrue(step.detail.contains("3"))
        assertTrue(step.detail.contains("未匹配"))
    }

    @Test
    fun `来源过多时压成一行并截断`() {
        val many = (1..200).map { "《很长的文档名$it》第${it}块" }
        val step = AgentStep.knowledge(hitCount = many.size, sources = many, docCount = 1)
        assertFalse("换行会让过程卡片高度失控", step.detail.contains('\n'))
        assertTrue(step.detail.endsWith("…"))
    }
}
