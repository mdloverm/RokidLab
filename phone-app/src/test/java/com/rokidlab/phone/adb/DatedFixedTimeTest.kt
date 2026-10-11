package com.rokidlab.phone.adb

import com.rokidlab.phone.adb.ui.TimerSchedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * 「指定日期的一次性定时任务」解析（[TimerScheduler.resolveDatedFixedTime]）。
 *
 * ⚠️ 这组测试守的是本轮改动最核心的一条不变式：
 * **「周五要交报告」必须排到周五，不能排到明天**。
 * 改动前 `FixedTime` 只有时:分，回访必然落在"今天该点未过则今天，否则明天"——
 * 静默错：不报错，只在错的那天弹。
 */
class DatedFixedTimeTest {

    private val zone: ZoneId = ZoneId.systemDefault()

    /** 构造某天某点的 epoch ms（作为"现在"） */
    private fun at(date: String, hour: Int, minute: Int = 0): Long =
        LocalDate.parse(date).atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun epochDayOf(date: String): Long = LocalDate.parse(date).toEpochDay()

    @Test
    fun `未来日期落在那一天而不是明天`() {
        // 现在 = 10-11 10:00，约定 = 10-16 15:00
        val now = at("2026-10-11", 10)
        val got = TimerScheduler.resolveDatedFixedTime(
            epochDayOf("2026-10-16"), 15, 0, now, zone,
        )
        val gotDate = java.time.Instant.ofEpochMilli(got).atZone(zone).toLocalDate()
        assertEquals("必须落在 10-16", LocalDate.parse("2026-10-16"), gotDate)
    }

    @Test
    fun `未来日期的时刻正确`() {
        val now = at("2026-10-11", 10)
        val got = TimerScheduler.resolveDatedFixedTime(
            epochDayOf("2026-10-16"), 15, 30, now, zone,
        )
        val zdt = java.time.Instant.ofEpochMilli(got).atZone(zone)
        assertEquals(15, zdt.hour)
        assertEquals(30, zdt.minute)
        assertEquals(0, zdt.second)
    }

    @Test
    fun `今天该点未过仍落在今天`() {
        val now = at("2026-10-11", 10)
        val got = TimerScheduler.resolveDatedFixedTime(
            epochDayOf("2026-10-11"), 15, 0, now, zone,
        )
        val zdt = java.time.Instant.ofEpochMilli(got).atZone(zone)
        assertEquals(LocalDate.parse("2026-10-11"), zdt.toLocalDate())
        assertEquals(15, zdt.hour)
    }

    @Test
    fun `今天该点已过顺延到明天`() {
        // 现在 = 10-11 18:00，约定仍是 10-11 15:00（已过）→ 兜底顺延一天而不是永不触发
        val now = at("2026-10-11", 18)
        val got = TimerScheduler.resolveDatedFixedTime(
            epochDayOf("2026-10-11"), 15, 0, now, zone,
        )
        val zdt = java.time.Instant.ofEpochMilli(got).atZone(zone)
        assertEquals(LocalDate.parse("2026-10-12"), zdt.toLocalDate())
        assertEquals(15, zdt.hour)
    }

    @Test
    fun `过去日期退回下一次该时刻而不是死时间`() {
        // 硬不变式：**返回值必定在未来**。简单 +1 天对过去的日期仍是过去时刻，
        // 交给 AlarmManager 会立刻触发/被丢弃，所以必须退回「下一次该时刻」。
        val now = at("2026-10-11", 10)
        val got = TimerScheduler.resolveDatedFixedTime(
            epochDayOf("2026-10-01"), 15, 0, now, zone,
        )
        assertTrue("返回值必须严格大于 now", got > now)
        // now 是 10-11 10:00，请求的 15:00 今天还没到 → 落在今天
        assertEquals(
            LocalDate.parse("2026-10-11"),
            java.time.Instant.ofEpochMilli(got).atZone(zone).toLocalDate(),
        )
    }

    @Test
    fun `过去日期且该时刻已过落到明天`() {
        // now = 10-11 18:00，请求 15:00（已过）→ 明天 15:00，仍然在未来
        val now = at("2026-10-11", 18)
        val got = TimerScheduler.resolveDatedFixedTime(
            epochDayOf("2026-10-01"), 15, 0, now, zone,
        )
        assertTrue(got > now)
        assertEquals(
            LocalDate.parse("2026-10-12"),
            java.time.Instant.ofEpochMilli(got).atZone(zone).toLocalDate(),
        )
    }

    @Test
    fun `所有分支的返回值都在未来`() {
        // 把「必定未来」作为不变式整体扫一遍，而不是只测几个样例
        val now = at("2026-10-11", 10)
        listOf(
            epochDayOf("2026-10-11") to 9,   // 今天，时刻已过
            epochDayOf("2026-10-11") to 15,  // 今天，时刻未到
            epochDayOf("2026-10-12") to 0,   // 明天
            epochDayOf("2026-10-20") to 23,  // 远期
            epochDayOf("2026-01-01") to 12,  // 过去
        ).forEach { (day, hour) ->
            val got = TimerScheduler.resolveDatedFixedTime(day, hour, 0, now, zone)
            assertTrue("day=$day hour=$hour 的返回值必须在未来，实际 $got vs $now", got > now)
        }
    }

    @Test
    fun `跨月正确`() {
        val now = at("2026-10-28", 10)
        val got = TimerScheduler.resolveDatedFixedTime(
            epochDayOf("2026-11-03"), 9, 0, now, zone,
        )
        assertEquals(
            LocalDate.parse("2026-11-03"),
            java.time.Instant.ofEpochMilli(got).atZone(zone).toLocalDate(),
        )
    }

    @Test
    fun `跨年正确`() {
        val now = at("2026-12-28", 10)
        val got = TimerScheduler.resolveDatedFixedTime(
            epochDayOf("2027-01-05"), 9, 0, now, zone,
        )
        assertEquals(
            LocalDate.parse("2027-01-05"),
            java.time.Instant.ofEpochMilli(got).atZone(zone).toLocalDate(),
        )
    }

    @Test
    fun `闰日正确`() {
        // 2028-02-29 是闰日；2026-02-29 不存在（由 parseOnceDate 拒绝，那层测过）
        val now = at("2028-02-20", 10)
        val got = TimerScheduler.resolveDatedFixedTime(
            epochDayOf("2028-02-29"), 12, 0, now, zone,
        )
        assertEquals(
            LocalDate.parse("2028-02-29"),
            java.time.Instant.ofEpochMilli(got).atZone(zone).toLocalDate(),
        )
    }

    @Test
    fun `旧任务 dayEpochDay 为空时行为不变`() {
        // 持久化兼容：改动前构造出的任务没有这个字段，读出来必须是 null
        val s = TimerSchedule.FixedTime(9, 0, repeatDaily = false)
        assertEquals(null, s.dayEpochDay)
    }
}