package com.rokidlab.phone.proactive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 记忆联想纯逻辑单测：星期映射 / 时间锚格式 / 检索 query 的时间扩展词 / 联想块装配边界。
 */
class ProactiveRecallTest {
    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    /** 2026-10-11 是周日；09:30 是清醒窗口典型相位 */
    private fun at(month: Int, day: Int, hour: Int, minute: Int): ZonedDateTime =
        ZonedDateTime.of(2026, month, day, hour, minute, 0, 0, zone)

    // ── 星期映射 ──

    @Test
    fun weekdayCn_mapsAllSeven() {
        assertEquals("周一", ProactiveRecall.weekdayCn(DayOfWeek.MONDAY))
        assertEquals("周六", ProactiveRecall.weekdayCn(DayOfWeek.SATURDAY))
        assertEquals("周日", ProactiveRecall.weekdayCn(DayOfWeek.SUNDAY))
    }

    // ── 时间锚 ──

    @Test
    fun timeAnchor_formatsMonthDayWeekdayTime() {
        assertEquals("10月11日 周日 09:30", ProactiveRecall.timeAnchor(at(10, 11, 9, 30)))
    }

    @Test
    fun timeAnchor_padsHourAndMinute() {
        // 2026-01-05 是周一
        assertEquals("1月5日 周一 07:05", ProactiveRecall.timeAnchor(at(1, 5, 7, 5)))
    }

    // ── 检索 query ──

    @Test
    fun recallQuery_containsSceneAnchorTomorrowAndPlanWords() {
        // 周日 09:30 → 明天是周一
        val q = ProactiveRecall.recallQuery("闲聊 搭话", at(10, 11, 9, 30))
        assertTrue("场景词要进 query", q.contains("闲聊"))
        assertTrue("时间锚要进 query", q.contains("10月11日 周日 09:30"))
        assertTrue("明天星期要进 query（计划类记忆「明天周一去…」词法必中）", q.contains("明天周一"))
        assertTrue("计划类通用词要进 query", q.contains("约定") && q.contains("行程"))
    }

    @Test
    fun recallQuery_tomorrowFollowsRealCalendar() {
        // 周三晚上 → 明天是周四：晚安轮带「明天周四」能命中「周四要交报告」类记忆
        val wednesdayNight = ZonedDateTime.of(2026, 10, 14, 22, 40, 0, 0, zone)
        assertTrue(ProactiveRecall.recallQuery("明天 安排", wednesdayNight).contains("明天周四"))
    }

    // ── 联想块装配 ──

    @Test
    fun recallBlock_nullOrBlank_returnsNull() {
        assertNull(ProactiveRecall.recallBlock(null))
        assertNull(ProactiveRecall.recallBlock(""))
        assertNull(ProactiveRecall.recallBlock("   "))
    }

    @Test
    fun recallBlock_wrapsInstructionOutsideInjectedBody() {
        val recalled = "<memories source=\"memories\">1. 下周三去成都</memories>"
        val block = ProactiveRecall.recallBlock(recalled)!!
        assertTrue("指令行在前（可信框），注入块随后", block.startsWith("（记忆联想）"))
        assertTrue("注入内容原样保留", block.contains(recalled))
        assertTrue("限定「相关才提」", block.contains("自然提一句"))
        assertTrue("带「不硬扯」护栏", block.contains("不要硬扯"))
    }
}
