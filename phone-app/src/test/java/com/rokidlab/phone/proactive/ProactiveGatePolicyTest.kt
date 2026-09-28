package com.rokidlab.phone.proactive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 决策门纯逻辑单测：免打扰时段边界 / 跨午夜顺延点 / 准入链优先级 / 静默关键词。
 */
class ProactiveGatePolicyTest {
    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    private fun at(hour: Int, minute: Int): Long =
        ZonedDateTime.of(2026, 9, 26, hour, minute, 0, 0, zone).toInstant().toEpochMilli()

    // ── 免打扰时段 ──

    @Test
    fun quietHours_boundaries() {
        assertFalse("22:59 在时段外", ProactiveGatePolicy.inQuietHours(22, 59))
        assertTrue("23:00 起点含", ProactiveGatePolicy.inQuietHours(23, 0))
        assertTrue("03:00 在时段内", ProactiveGatePolicy.inQuietHours(3, 0))
        assertTrue("07:59 在时段内", ProactiveGatePolicy.inQuietHours(7, 59))
        assertFalse("08:00 终点不含", ProactiveGatePolicy.inQuietHours(8, 0))
        assertFalse("12:30 在时段外", ProactiveGatePolicy.inQuietHours(12, 30))
    }

    // ── 顺延点 ──

    @Test
    fun nextQuietEnd_fromLateNight_goesToTomorrowMorning() {
        val expected = ZonedDateTime.of(2026, 9, 27, 8, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(expected, ProactiveGatePolicy.nextQuietEnd(at(23, 30), zone))
        assertEquals(expected, ProactiveGatePolicy.nextQuietEnd(at(23, 0), zone))
    }

    @Test
    fun nextQuietEnd_fromSmallHours_goesToTodayMorning() {
        val expected = ZonedDateTime.of(2026, 9, 26, 8, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(expected, ProactiveGatePolicy.nextQuietEnd(at(3, 0), zone))
        assertEquals(expected, ProactiveGatePolicy.nextQuietEnd(at(7, 30), zone))
    }

    // ── 准入链 ──

    private fun admit(
        nowMs: Long,
        cooldownUntil: Long = 0L,
        sentCountToday: Int = 0,
        lastSentAt: Long = 0L,
    ): GateDecision = ProactiveGatePolicy.admitSystemProactive(
        nowMs = nowMs,
        cooldownUntil = cooldownUntil,
        todayKey = "2026-09-26",
        sentCountToday = sentCountToday,
        lastSentAt = lastSentAt,
        zone = zone,
    )

    @Test
    fun admit_cooldownWins_overEverything() {
        val decision = admit(
            nowMs = at(14, 0),
            cooldownUntil = at(20, 0),
            sentCountToday = ProactiveGatePolicy.DAILY_CAP,
        )
        assertTrue("冷却期压过一切", decision is GateDecision.Suppress && decision.reason == "cooldown")
    }

    @Test
    fun admit_expiredCooldown_fallsThrough() {
        val decision = admit(nowMs = at(14, 0), cooldownUntil = at(13, 0))
        assertEquals(GateDecision.Pass, decision)
    }

    @Test
    fun admit_quietHours_defersNotDrops() {
        val decision = admit(nowMs = at(23, 30))
        val expected = ZonedDateTime.of(2026, 9, 27, 8, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(GateDecision.Defer(expected), decision)
    }

    @Test
    fun admit_dailyCap_suppresses() {
        val decision = admit(nowMs = at(14, 0), sentCountToday = ProactiveGatePolicy.DAILY_CAP)
        assertTrue(decision is GateDecision.Suppress && decision.reason == "daily-cap")
    }

    @Test
    fun admit_minInterval_suppresses_thenAllowsAfterGap() {
        val within = admit(nowMs = at(14, 0), lastSentAt = at(13, 0))
        assertTrue(within is GateDecision.Suppress && within.reason == "min-interval")
        // 恰好达到最小间隔（不小于）即放行
        val afterGap = admit(nowMs = at(14, 0), lastSentAt = at(12, 0))
        assertEquals(GateDecision.Pass, afterGap)
    }

    // ── 空闲判定 ──

    @Test
    fun idleDue_thresholdBoundary() {
        val now = at(14, 0)
        val threshold = ProactiveGatePolicy.IDLE_THRESHOLD_MS
        assertFalse("从未交互不算空闲", ProactiveGatePolicy.idleDue(now, 0L, threshold))
        assertFalse("不足阈值", ProactiveGatePolicy.idleDue(now, now - threshold + 60_000L, threshold))
        assertTrue("恰达阈值", ProactiveGatePolicy.idleDue(now, now - threshold, threshold))
        assertTrue("远超阈值", ProactiveGatePolicy.idleDue(now, now - threshold * 3, threshold))
    }

    // ── 亲密度 streak ──

    @Test
    fun nextStreak_progression() {
        assertEquals(1, ProactiveGatePolicy.nextStreak("2026-09-26", null, 0))
        assertEquals(5, ProactiveGatePolicy.nextStreak("2026-09-26", "2026-09-26", 5))
        assertEquals(6, ProactiveGatePolicy.nextStreak("2026-09-26", "2026-09-25", 5))
        assertEquals(1, ProactiveGatePolicy.nextStreak("2026-09-26", "2026-09-20", 9))
    }

    @Test
    fun isConsecutiveDays_crossesMonthBoundary() {
        assertTrue(ProactiveGatePolicy.isConsecutiveDays("2026-08-31", "2026-09-01"))
        assertFalse(ProactiveGatePolicy.isConsecutiveDays("2026-08-30", "2026-09-01"))
    }

    // ── 陪伴场景与闲聊频率档 ──

    @Test
    fun scenes_monotonicAndComplete() {
        // 勿扰：全关，不发消息也不追
        with(ProactiveGatePolicy.Scene.MUTE) {
            assertTrue("勿扰无任何能力", features.isEmpty())
            assertEquals(0, chatFreq.dailyCap)
            assertEquals(0, maxNudges)
        }
        // 场景越高：能力集只增不减、闲聊额度与追击名额单调不减
        val entries = ProactiveGatePolicy.Scene.entries
        for (i in 1 until entries.size) {
            val prev = entries[i - 1]
            val cur = entries[i]
            assertTrue("能力集单调不减: $prev -> $cur", cur.features.containsAll(prev.features))
            assertTrue("闲聊额度单调不减: $prev -> $cur", cur.chatFreq.dailyCap >= prev.chatFreq.dailyCap)
            assertTrue("追击名额单调不减: $prev -> $cur", cur.maxNudges >= prev.maxNudges)
        }
        // 全陪是能力全集
        assertEquals(
            ProactiveGatePolicy.ProactiveFeature.entries.toSet(),
            ProactiveGatePolicy.Scene.FULL.features,
        )
        // 未知/空 id 兜底到轻陪伴
        assertEquals(ProactiveGatePolicy.Scene.LIGHT, ProactiveGatePolicy.Scene.of(null))
        assertEquals(ProactiveGatePolicy.Scene.FULL, ProactiveGatePolicy.Scene.of("full"))
    }

    @Test
    fun chatFrequency_capsAscending() {
        assertEquals("安静档不发", 0, ProactiveGatePolicy.ChatFrequency.SILENT.dailyCap)
        // 档位越高额度单调增
        val entries = ProactiveGatePolicy.ChatFrequency.entries
        for (i in 1 until entries.size) {
            assertTrue(
                "额度单调增: ${entries[i - 1]} -> ${entries[i]}",
                entries[i].dailyCap > entries[i - 1].dailyCap,
            )
        }
        assertEquals(ProactiveGatePolicy.ChatFrequency.SILENT, ProactiveGatePolicy.ChatFrequency.of(null))
        assertEquals(ProactiveGatePolicy.ChatFrequency.MODERATE, ProactiveGatePolicy.ChatFrequency.of("moderate"))
    }

    @Test
    fun visionInterval_isFixedQuarter() {
        assertEquals(15 * 60 * 1000L, ProactiveGatePolicy.VISION_INTERVAL_MS)
    }

    @Test
    fun careLimitMs_scalesAndCloses() {
        val base = 45 * 60 * 1000L
        assertEquals("标准 1x 原值", base, ProactiveGatePolicy.careLimitMs(base, 1f))
        assertEquals(
            "热情 0.5x 减半",
            (base * 0.5f).toLong(),
            ProactiveGatePolicy.careLimitMs(base, 0.5f),
        )
        assertEquals(
            "安静 1.5x 放宽",
            (base * 1.5f).toLong(),
            ProactiveGatePolicy.careLimitMs(base, 1.5f),
        )
        assertEquals("scale=0 关闭", Long.MAX_VALUE, ProactiveGatePolicy.careLimitMs(base, 0f))
        assertEquals("负 scale 关闭", Long.MAX_VALUE, ProactiveGatePolicy.careLimitMs(base, -1f))
    }

    // ── 响应率自适应 ──

    @Test
    fun responseFactor_bounds() {
        assertEquals(
            "无记录保持档位基线",
            1.0,
            ProactiveGatePolicy.responseFactor(emptyList()),
            1e-9,
        )
        assertEquals(
            "全响应 → 2.0（间隔÷2）",
            2.0,
            ProactiveGatePolicy.responseFactor(List(8) { true }),
            1e-9,
        )
        assertEquals(
            "全忽略 → 0.5（间隔×2）",
            0.5,
            ProactiveGatePolicy.responseFactor(List(8) { false }),
            1e-9,
        )
        assertEquals(
            "半响应（r=0.5）→ 1.25",
            1.25,
            ProactiveGatePolicy.responseFactor(listOf(true, false, true, false)),
            1e-9,
        )
    }

    @Test
    fun responsePenalty_onlyAfterThreeConsecutiveMisses() {
        assertEquals(0L, ProactiveGatePolicy.responsePenaltyMs(emptyList()))
        assertEquals(0L, ProactiveGatePolicy.responsePenaltyMs(listOf(false, false)))
        assertEquals(
            ProactiveGatePolicy.RESPONSE_PENALTY_MS,
            ProactiveGatePolicy.responsePenaltyMs(listOf(false, false, false)),
        )
        assertEquals(
            "中间有回应就不罚",
            0L,
            ProactiveGatePolicy.responsePenaltyMs(listOf(true, false, false)),
        )
        assertEquals(
            ProactiveGatePolicy.RESPONSE_PENALTY_MS,
            ProactiveGatePolicy.responsePenaltyMs(List(8) { false }),
        )
    }

    // ── 时机内容绑定的跳过信号 ──

    @Test
    fun skipReply_detection() {
        assertTrue(ProactiveGatePolicy.isSkipReply("[SKIP]"))
        assertTrue(ProactiveGatePolicy.isSkipReply("[skip]"))
        assertTrue(ProactiveGatePolicy.isSkipReply(" [SKIP] "))
        assertTrue(ProactiveGatePolicy.isSkipReply("[SKIP]。"))
        assertFalse(ProactiveGatePolicy.isSkipReply("早上好呀"))
        assertFalse(ProactiveGatePolicy.isSkipReply("今天有雨，记得带伞"))
        assertFalse(ProactiveGatePolicy.isSkipReply("[SKIP] 今天有雨"))
    }

    // ── 移动判定（走走拍拍门控） ──

    @Test
    fun isMoving_thresholds() {
        // 静止：模长恒在 ~9.8，RMS 波动极小
        val still = List(20) { 9.8f + 0.05f * it }
        assertFalse(ProactiveGatePolicy.isMoving(still))
        // 行走：模长在 8.5~11 间抖动
        val walking = List(30) { if (it % 2 == 0) 8.6f else 11.0f }
        assertTrue(ProactiveGatePolicy.isMoving(walking))
        // 样本不足（IMU 未启动/刚连接）一律判静止
        assertFalse(ProactiveGatePolicy.isMoving(listOf(9.8f, 9.8f, 9.9f)))
        assertFalse(ProactiveGatePolicy.isMoving(emptyList()))
    }

    // ── 静默关键词 ──

    @Test
    fun silenceKeywords_hit() {
        assertTrue(ProactiveGatePolicy.matchesSilence("别烦我"))
        assertTrue(ProactiveGatePolicy.matchesSilence("以后别主动找我"))
        assertTrue(ProactiveGatePolicy.matchesSilence("别再主动打扰我睡觉"))
        assertTrue(ProactiveGatePolicy.matchesSilence("不要主动给我推消息"))
    }

    @Test
    fun silenceKeywords_miss_onOrdinaryText() {
        assertFalse(ProactiveGatePolicy.matchesSilence("今天天气怎么样"))
        assertFalse(ProactiveGatePolicy.matchesSilence("帮我放首歌"))
        // 语义冲突保护：「闭嘴/安静点」属于打断当前播报（stop_tts），不触发冷却
        assertFalse(ProactiveGatePolicy.matchesSilence("闭嘴"))
        assertFalse(ProactiveGatePolicy.matchesSilence("安静点"))
    }
}
