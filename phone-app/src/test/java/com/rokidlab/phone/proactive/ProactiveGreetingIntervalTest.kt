package com.rokidlab.phone.proactive

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「上限即频率」换算钉测：问候间隔 = 清醒窗口 / 每日上限，30 分钟下限保护。
 * 清醒窗口默认 23:00→8:00 = 15 小时。
 */
class ProactiveGreetingIntervalTest {

    private val start = ProactiveGatePolicy.QUIET_START_MIN_DEFAULT
    private val end = ProactiveGatePolicy.QUIET_END_MIN_DEFAULT

    @Test
    fun cap1_fullAwakeWindow() {
        assertEquals(15 * 60 * 60_000L, ProactiveGatePolicy.greetingIntervalMs(1, start, end))
    }

    @Test
    fun cap3_aboutFiveHours() {
        assertEquals(5 * 60 * 60_000L, ProactiveGatePolicy.greetingIntervalMs(3, start, end))
    }

    @Test
    fun cap8_aboutTwoHours() {
        // 15h / 8 = 1.875h（112.5 分钟）
        assertEquals(6_750_000L, ProactiveGatePolicy.greetingIntervalMs(8, start, end))
    }

    @Test
    fun cap20_clampedToMinGap() {
        // 15h / 20 = 45min，但不得短于 30min 下限 → 45min 合法保留
        assertEquals(45 * 60_000L, ProactiveGatePolicy.greetingIntervalMs(20, start, end))
    }

    @Test
    fun hugeCap_hitsMinGapFloor() {
        // cap=60 → 15min < 30min 下限 → clamp 到 30min
        assertEquals(30 * 60_000L, ProactiveGatePolicy.greetingIntervalMs(60, start, end))
    }

    @Test
    fun capZero_maxValue() {
        assertEquals(Long.MAX_VALUE, ProactiveGatePolicy.greetingIntervalMs(0, start, end))
    }

    @Test
    fun customQuietWindow_crossMidnight() {
        // 免打扰 1:00–7:00（start<end 同日窗口）：清醒 7:00→1:00 = 18h；cap=2 → 9h
        assertEquals(9 * 60 * 60_000L, ProactiveGatePolicy.greetingIntervalMs(2, 1 * 60, 7 * 60))
    }
}
