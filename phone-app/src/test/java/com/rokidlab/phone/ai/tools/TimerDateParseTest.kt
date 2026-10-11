package com.rokidlab.phone.ai.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * `schedule_agent_task` 的 `date` 参数解析（[TimerToolProvider.parseOnceDate]）。
 *
 * ⚠️ 这组测试盯的是一个**静默错误**：日期解析失败若被容错通过，
 * 回访就会在错误的那天弹出 —— 用户看到"周一问我周五的事"，
 * 比不提醒更伤（说明这功能不可信）。
 */
class TimerDateParseTest {

    /** 固定"今天"为 2026-10-11（周日），让断言与真实日期无关 */
    private val today: Long = LocalDate.of(2026, 10, 11).toEpochDay()

    @Test
    fun `空日期走旧语义不限日期`() {
        val r = TimerToolProvider.parseOnceDate(null, repeatDaily = false, today = today)
        assertTrue(r is TimerToolProvider.ParseResult.Ok)
        assertEquals(null, (r as TimerToolProvider.ParseResult.Ok).day)
    }

    @Test
    fun `未来日期正确解析为 epochDay`() {
        val r = TimerToolProvider.parseOnceDate("2026-10-16", repeatDaily = false, today = today)
        assertTrue(r is TimerToolProvider.ParseResult.Ok)
        assertEquals(
            LocalDate.of(2026, 10, 16).toEpochDay(),
            (r as TimerToolProvider.ParseResult.Ok).day,
        )
    }

    @Test
    fun `今天合法`() {
        // 今天该点还没到的话写今天是对的，不能拦
        val r = TimerToolProvider.parseOnceDate("2026-10-11", repeatDaily = false, today = today)
        assertTrue(r is TimerToolProvider.ParseResult.Ok)
        assertEquals(today, (r as TimerToolProvider.ParseResult.Ok).day)
    }

    @Test
    fun `明天解析正确`() {
        val r = TimerToolProvider.parseOnceDate("2026-10-12", repeatDaily = false, today = today)
        assertEquals(
            LocalDate.of(2026, 10, 12).toEpochDay(),
            (r as TimerToolProvider.ParseResult.Ok).day,
        )
    }

    @Test
    fun `过去日期被拒绝而非静默顺延`() {
        // 这是本功能的核心断言：宁可不建，也不要在错的那天弹
        val r = TimerToolProvider.parseOnceDate("2026-10-01", repeatDaily = false, today = today)
        assertTrue("过去日期必须被拒绝", r is TimerToolProvider.ParseResult.Past)
    }

    @Test
    fun `非法格式被拒绝`() {
        listOf("2026/10/16", "16-10-2026", "下周五", "", "   ", "20261016")
            .forEach { raw ->
                val r = TimerToolProvider.parseOnceDate(raw, repeatDaily = false, today = today)
                // 空串与空白等价于"不填"，其余必须判格式非法
                if (raw.isBlank()) {
                    assertTrue("空白应视为不填: '$raw'", r is TimerToolProvider.ParseResult.Ok)
                } else {
                    assertTrue("非法日期必须拒绝: '$raw'", r is TimerToolProvider.ParseResult.BadFormat)
                }
            }
    }

    @Test
    fun `不存在的日期被拒绝而非滚到下一年`() {
        // LocalDate.parse 严格；不能像 SimpleDateFormat 那样把 2026-13-45 滚成 2027-02-14
        val r = TimerToolProvider.parseOnceDate("2026-13-45", repeatDaily = false, today = today)
        assertTrue(r is TimerToolProvider.ParseResult.BadFormat)
    }

    @Test
    fun `每天重复时忽略多余日期`() {
        // 模型顺手填了 date 不该让整个建任务失败
        val r = TimerToolProvider.parseOnceDate("2026-10-16", repeatDaily = true, today = today)
        assertTrue(r is TimerToolProvider.ParseResult.Ok)
        assertEquals(null, (r as TimerToolProvider.ParseResult.Ok).day)
    }

    @Test
    fun `日期前后空格被容忍`() {
        val r = TimerToolProvider.parseOnceDate("  2026-10-16  ", repeatDaily = false, today = today)
        assertEquals(
            LocalDate.of(2026, 10, 16).toEpochDay(),
            (r as TimerToolProvider.ParseResult.Ok).day,
        )
    }

    @Test
    fun `跨年日期正确解析`() {
        val r = TimerToolProvider.parseOnceDate("2027-01-05", repeatDaily = false, today = today)
        assertEquals(
            LocalDate.of(2027, 1, 5).toEpochDay(),
            (r as TimerToolProvider.ParseResult.Ok).day,
        )
    }
}