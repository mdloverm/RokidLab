package com.rokidlab.phone.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 无进展检测的两个纯函数判据（2026-10-09，gps-live 事故 P0-6）。
 *
 * 覆盖的失效形态（都来自真机事件流，不是臆想）：
 *  - `read_code_file(index.ink)` 被读了 **5 次**、同一个**不存在**的 `index.html` 读了 2 次；
 *  - 用户说"修"/"重写"，6 轮 **37 次工具调用、0 次写操作**，全程无异常。
 */
class NoProgressDetectionTest {

    // ═══════════ ToolRepetitionTracker：调用指纹 ═══════════

    /**
     * 参数**书写顺序**不同、语义相同的调用，必须是**同一个指纹** ——
     * 否则"换个 key 顺序再调一次"就绕过了检测，等于没检测。
     */
    @Test
    fun `D1 参数顺序不影响指纹`() {
        val a = ToolRepetitionTracker.fingerprint("read_code_file", """{"project":"gps-live","file":"app.json"}""")
        val b = ToolRepetitionTracker.fingerprint("read_code_file", """{"file":"app.json","project":"gps-live"}""")
        assertEquals(a, b)
    }

    /**
     * **易变字段必须排除**：`timestamp`/`cbId` 这类每次调用都不同，
     * 参与指纹会让"同一个动作"永远看起来是新的 ⇒ 检测形同虚设。
     */
    @Test
    fun `D2 时间戳等易变字段不影响指纹`() {
        val a = ToolRepetitionTracker.fingerprint("get_location", """{"format":"text","timestamp":111}""")
        val b = ToolRepetitionTracker.fingerprint("get_location", """{"format":"text","timestamp":999}""")
        assertEquals("易变字段被排除后应同指纹", a, b)
    }

    /** 参数真的不同时必须区分开（否则正常的不同调用会被误报成重复）。 */
    @Test
    fun `D3 参数不同则指纹不同`() {
        val a = ToolRepetitionTracker.fingerprint("read_code_file", """{"file":"app.json"}""")
        val b = ToolRepetitionTracker.fingerprint("read_code_file", """{"file":"index.ink"}""")
        assertNotEquals(a, b)
    }

    /** 非法 JSON 不能抛异常（工具参数来自模型，格式不保证）—— 退回原文但仍是稳定指纹。 */
    @Test
    fun `D4 非法参数不抛异常`() {
        val a = ToolRepetitionTracker.fingerprint("save_code_file", "not-json-at-all")
        val b = ToolRepetitionTracker.fingerprint("save_code_file", "not-json-at-all")
        assertEquals(a, b)
    }

    /**
     * 重复计数：第 1 次是 1（不该提醒），第 2 次是 2（该提醒）。
     * 这条钉住 [ToolRepetitionTracker.WARN_FROM_OCCURRENCE] 的语义。
     */
    @Test
    fun `E1 同指纹第几次可数且按会话隔离`() {
        val s1 = "session-repeat-test-1"
        val s2 = "session-repeat-test-2"
        ToolRepetitionTracker.reset(s1)
        ToolRepetitionTracker.reset(s2)
        val fp = ToolRepetitionTracker.fingerprint("read_code_file", """{"file":"index.ink","project":"gps-live"}""")
        assertEquals(1, ToolRepetitionTracker.noteAndCount(s1, fp))
        assertEquals(2, ToolRepetitionTracker.noteAndCount(s1, fp))
        assertEquals(3, ToolRepetitionTracker.noteAndCount(s1, fp))
        // 另一个会话从 1 开始 —— 跨会话隔离是硬要求（判据同"新对话不该记着别的会话"）
        assertEquals("别的会话必须从 1 起算", 1, ToolRepetitionTracker.noteAndCount(s2, fp))
        assertTrue(ToolRepetitionTracker.WARN_FROM_OCCURRENCE == 2)
    }

    /** 提醒文案必须**点名工具名与次数**（否则模型不知道在说哪个动作）。 */
    @Test
    fun `E2 提醒文案包含工具名与次数`() {
        val r = ToolRepetitionTracker.reminder("read_code_file", 5)
        assertTrue(r.contains("read_code_file"))
        assertTrue(r.contains("5"))
    }

    // ═══════════ ActionIntent：用户是不是在要求"动手改" ═══════════

    /** 真机原话（gps-live）：这三句触发了后续 6 轮空转，必须识别为动作请求。 */
    @Test
    fun `F1 真机的动作请求被识别`() {
        assertTrue(ActionIntent.looksLikeActionRequest("重写"))
        assertTrue(ActionIntent.looksLikeActionRequest("继续"))
        assertTrue(ActionIntent.looksLikeActionRequest("在修复"))
        assertTrue(ActionIntent.looksLikeActionRequest("帮我改一下颜色"))
        assertTrue(ActionIntent.looksLikeActionRequest("删掉那个文件"))
        assertTrue(ActionIntent.looksLikeActionRequest("生成一个天气页面"))
    }

    /**
     * **误放比漏放更烦**：用户只是**问**、或者明说先别动时，绝不能触发自问轮
     * （否则正常问答也会被框架打断、白花一轮）。
     */
    @Test
    fun `F2 提问与否定语境不触发`() {
        assertFalse("问原因不算要求改", ActionIntent.looksLikeActionRequest("为什么拿不到位置"))
        assertFalse("问含义不算", ActionIntent.looksLikeActionRequest("这是什么意思"))
        assertFalse("明说先别改", ActionIntent.looksLikeActionRequest("先别改，我再看看"))
        assertFalse("纯查询", ActionIntent.looksLikeActionRequest("眼镜还有多少电"))
        assertFalse("问候", ActionIntent.looksLikeActionRequest("你好"))
        assertFalse("空", ActionIntent.looksLikeActionRequest(null))
    }

    /**
     * **单字不收**：只认两字以上词组 —— 因为 `写`/`改`/`加` 会命中
     * "描写""改写历史""加班"这类与"改动产物"无关的语境。
     */
    @Test
    fun `F3 单字不误伤`() {
        assertFalse("描写不含动作意图", ActionIntent.looksLikeActionRequest("这个词描写得很生动"))
        assertFalse("加班无关", ActionIntent.looksLikeActionRequest("我今天要加班"))
    }

    /** 超长（多半是粘贴的任务描述）不在此处插手，交给模型自己判断。 */
    @Test
    fun `F4 超长文本不判`() {
        assertFalse(ActionIntent.looksLikeActionRequest("改".repeat(200)))
    }
}
