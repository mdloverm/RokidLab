package com.rokidlab.phone.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 内部标记终态护栏的回归测试（2026-10-09，gps-live 事故 P0-1）。
 *
 * ## 这些断言"能红"吗
 *
 * 判据（审计要求）：**测试要验证它真的能红** —— 把被保护的东西删掉，它必须失败。
 * 本类每条断言都直盯一个**具体失效形态**，不是"跑一遍看看有没有异常"：
 *  - [isMarkerOnly 各变形] —— 删掉归一化里的 `_`/`-`/空白处理，`[NEED TOOLS]` 那条立即红；
 *  - [stripMarkers 带前缀形态] —— 删掉行内替换，`我看看…[NEED_TOOLS]` 那条红；
 *  - [neutralize 幂等] —— 去掉 `OVERRIDE_TAG` 判断，重复调用那条红。
 *
 * ## 事故背景（为什么值得一组专门的测试）
 *
 * 逃生舱只升级一次 ⇒ 第二次 `[NEED_TOOLS]` 无人接管 ⇒ 直接被当正文播进聊天气泡，
 * 而该轮还被记成 `reason=completed`。真机 5 轮全部命中，每轮白烧 2 次模型调用。
 */
class InternalMarkerGuardTest {

    // ═══════════ isMarkerOnly：整段就是标记（= 未产出）═══════════

    @Test
    fun `A1 纯标记判为未产出`() {
        assertTrue(InternalMarkerGuard.isMarkerOnly("[NEED_TOOLS]"))
        assertTrue(InternalMarkerGuard.isMarkerOnly("[TOOLS]"))
        assertTrue(InternalMarkerGuard.isMarkerOnly("  [NEED_TOOLS]  "))
        assertTrue(InternalMarkerGuard.isMarkerOnly("[NEED_TOOLS]。"))
    }

    /**
     * 变形是**真机上会出现**的形态，不是臆想：模型写标记时分隔符三种都用过。
     * 这条是"归一化必须做"的直接证据 —— 只比对原样字面量会漏掉后两种。
     */
    @Test
    fun `A2 标记的变形同样判为未产出`() {
        assertTrue("空格分隔", InternalMarkerGuard.isMarkerOnly("[NEED TOOLS]"))
        assertTrue("连字符分隔", InternalMarkerGuard.isMarkerOnly("[NEED-TOOLS]"))
        assertTrue("小写", InternalMarkerGuard.isMarkerOnly("[need_tools]"))
        assertTrue("混合大小写", InternalMarkerGuard.isMarkerOnly("[Need_Tools]"))
    }

    /** 正常回复绝不能被判成"未产出"，否则用户白等一轮重试。 */
    @Test
    fun `A3 正常回复不判为未产出`() {
        assertFalse(InternalMarkerGuard.isMarkerOnly("已经改好了，你试试看"))
        assertFalse(InternalMarkerGuard.isMarkerOnly(""))
        assertFalse(InternalMarkerGuard.isMarkerOnly(null))
        // 用户问"“[NEED_TOOLS] 是什么"时模型的**解释**属于正常回答，不该被拦
        assertFalse(
            InternalMarkerGuard.isMarkerOnly(
                "[NEED_TOOLS] 是系统内部用的标记，表示模型觉得手头工具不够，你不用关心它。",
            ),
        )
    }

    /** 超长文本不判（防误伤长文：长文里恰好出现标记属于极罕见且无害）。 */
    @Test
    fun `A4 超长文本不判`() {
        val long = "。".repeat(500) + "[NEED_TOOLS]"
        assertFalse(InternalMarkerGuard.isMarkerOnly(long))
    }

    // ═══════════ stripMarkers：清洗（宽）═══════════

    @Test
    fun `B1 整行标记被删掉`() {
        assertEquals("", InternalMarkerGuard.stripMarkers("[NEED_TOOLS]"))
        assertEquals("", InternalMarkerGuard.stripMarkers("  [TOOLS]  "))
    }

    /**
     * **带前缀**的形态：判定走严格（不判为未产出），但清洗必须宽 —— 否则那 11 个字符
     * 会跟着正文一起播出去（判定与清洗分工不同：判定防误伤，清洗防漏）。
     */
    @Test
    fun `B2 带前缀的标记也要剥掉`() {
        val out = InternalMarkerGuard.stripMarkers("我看看…\n[NEED_TOOLS]")
        assertFalse("标记不该残留", out.contains("NEED_TOOLS"))
        assertTrue("正文要保留", out.contains("我看看"))
    }

    @Test
    fun `B3 不含标记的文本原样返回`() {
        assertEquals("项目已改好，屏幕上看一下", InternalMarkerGuard.stripMarkers("项目已改好，屏幕上看一下"))
    }

    // ═══════════ neutralizeMenuInstruction：撤掉已失效的旧指令 ═══════════

    @Test
    fun `C1 含菜单段时追加覆盖说明`() {
        val menu = "……\n<tool_menu>\n- get_weather：查天气\n</tool_menu>\n若需要清单里的其他工具，请只回复一行 [TOOLS]"
        val out = InternalMarkerGuard.neutralizeMenuInstruction(menu, 106)
        assertTrue("要带上真实工具数", out!!.contains("106"))
        assertTrue("要明说旧约定作废", out.contains("作废"))
        assertTrue("要点名不再输出标记", out.contains("不要再输出任何方括号标记"))
    }

    /** 幂等：升级路径可能被多次走到，重复追加会让提示越滚越长。 */
    @Test
    fun `C2 重复调用不重复追加`() {
        val menu = "<tool_menu>\n- x：y\n</tool_menu>"
        val once = InternalMarkerGuard.neutralizeMenuInstruction(menu, 10)
        val twice = InternalMarkerGuard.neutralizeMenuInstruction(once, 10)
        assertEquals("第二次应原样返回", once, twice)
    }

    /** 不含菜单段的消息**必须原样返回** —— 否则会把记忆/知识库那几段无关的 system 消息改坏。 */
    @Test
    fun `C3 不含菜单段时不动它`() {
        val other = "以下是与用户相关的长期记忆，回答时据此个性化。"
        assertEquals(other, InternalMarkerGuard.neutralizeMenuInstruction(other, 10))
        assertEquals(other, InternalMarkerGuard.neutralizeMenuInstruction(other, 106))
    }

    @Test
    fun `C4 null 安全`() {
        assertEquals(null, InternalMarkerGuard.neutralizeMenuInstruction(null, 10))
    }
}
