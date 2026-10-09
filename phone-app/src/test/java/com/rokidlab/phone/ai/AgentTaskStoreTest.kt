package com.rokidlab.phone.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AgentTaskStore] 的**纯逻辑**测试。
 *
 * ## 为什么只有这几个方法
 *
 * 本类的读写都走 `Context.filesDir`（[AgentTaskStore.load] / [AgentTaskStore.save]），
 * 而工程的 test 依赖只有 junit + org.json —— **没有 Robolectric、没有 mock 框架**，
 * 所以"存进去再读出来"这类测试在这里写不出来（真机验证仍然是设备实测那一环）。
 *
 * 那就把**决策**从 IO 里剥出来单测（项目一贯的接缝提取做法）：
 * 续做提示的**措辞选择**是 2026-10-09 那次修复的关键判据 ——
 * 有 plan 时说"从第几步接着做"，无 plan 时说"重新回答上面那条请求"，
 * 这个分岔错了会让模型去找并不存在的步骤。纯函数化后由本测试直接钉住。
 */
class AgentTaskStoreTest {

    /**
     * 有 plan 时的措辞里出现"第几步 / clear_agent_task"；
     * 无 plan 时**绝不能**出现（那是指向不存在对象的指令）。
     *
     * ★ 这条判据比"文案好不好"重要：说错会让模型去找虚构的步骤编号，
     * 或者去清一个不存在的任务 —— 两者都是凭空捏造上下文。
     */
    @Test
    fun `无计划时的续做措辞不得指向不存在的计划或任务`() {
        val note = AgentTaskStore.plainInterruptNote(
            goal = "帮我看下这个配置文件",
            lastError = "被新消息打断（可续做）",
        )
        assertTrue("必须保留用户原话（继续需要指代对象）", note.contains("帮我看下这个配置文件"))
        assertTrue("必须说明中断原因", note.contains("被新消息打断"))
        assertFalse(
            "无计划时说「第几步」＝指向不存在的对象",
            note.contains("接着做") || note.contains("→"),
        )
        assertFalse(
            "无计划时说 clear_agent_task ＝让模型去清一个不存在的任务",
            note.contains("clear_agent_task"),
        )
        assertTrue("要说清继续的方式是重新作答", note.contains("重新回答"))
    }

    /** goal 缺失时也不能崩、也不能丢主要内容 —— 只有中断原因也够用 */
    @Test
    fun `goal 缺失时只给中断原因且不抛异常`() {
        val note = AgentTaskStore.plainInterruptNote(goal = null, lastError = "连接超时")
        assertTrue(note.contains("连接超时"))
        // 空 goal 时不该出现"用户当时的问题："这个空标签
        assertFalse("空 goal 不应留下空标签", note.contains("用户当时的问题："))
    }

    /** 两项皆空（理论上不该发生）也要产出一句可读的话，而不是空串或异常 */
    @Test
    fun `goal 与原因都缺失时仍产出可用提示`() {
        val note = AgentTaskStore.plainInterruptNote(goal = "", lastError = null)
        assertTrue("不能是空串（空串与'没内容'无法区分）", note.isNotBlank())
        assertTrue(note.contains("上一轮被中断"))
    }
}