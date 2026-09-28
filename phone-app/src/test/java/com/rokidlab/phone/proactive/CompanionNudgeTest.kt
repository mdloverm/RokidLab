package com.rokidlab.phone.proactive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 对话内陪伴纯逻辑：敷衍回复判定 + 沉默追击窗口 */
class CompanionNudgeTest {

    // ---------- dryReply ----------

    @Test
    fun `dry replies detected`() {
        assertTrue(CompanionNudge.dryReply("嗯") == true)
        assertTrue(CompanionNudge.dryReply("哈哈") == true)
        assertTrue(CompanionNudge.dryReply("不知道") == true)
        assertTrue(CompanionNudge.dryReply(" ok ") == true)
        assertTrue(CompanionNudge.dryReply("。。。") == true)
    }

    @Test
    fun `real replies not dry`() {
        assertTrue(CompanionNudge.dryReply("今天天气怎么样") == false)
        assertTrue(CompanionNudge.dryReply("好吧，那算了") == false) // 带内容
        assertTrue(CompanionNudge.dryReply("好的呀，那走吧") == false)
    }

    @Test
    fun `null is unknown not dry`() {
        assertEquals(null, CompanionNudge.dryReply(null))
    }

    // ---------- shouldNudge ----------

    private val base = 1_000_000_000_000L

    private fun ok(
        now: Long,
        last: Long?,
        count: Int = 0,
        on: Boolean = true,
        off: Boolean = false,
        cool: Boolean = false,
        max: Int = CompanionNudge.MAX_NUDGES_PER_SESSION,
    ) = CompanionNudge.shouldNudge(now, last, count, on, off, cool, max)

    @Test
    fun `nudge fires inside silence window`() {
        // 沉默 5 分钟（窗口 2.5~10 分钟内）
        assertTrue(ok(base + 300_000L, base))
    }

    @Test
    fun `too soon or too late skips`() {
        // 沉默 1 分钟：还在聊
        assertFalse(ok(base + 60_000L, base))
        // 沉默 11 分钟：已经冷了
        assertFalse(ok(base + 660_000L, base))
    }

    @Test
    fun `nudge cap per session`() {
        assertFalse(ok(base + 300_000L, base, count = CompanionNudge.MAX_NUDGES_PER_SESSION))
        assertTrue(ok(base + 300_000L, base, count = CompanionNudge.MAX_NUDGES_PER_SESSION - 1))
    }

    @Test
    fun `nudge cap follows level max`() {
        // 档位名额 0 = 不追（OFF 档）
        assertFalse(ok(base + 300_000L, base, max = 0))
        // 名额小于已用数也不追；名额更大则可继续追
        assertFalse(ok(base + 300_000L, base, count = 2, max = 1))
        assertTrue(ok(base + 300_000L, base, count = 2, max = 3))
    }

    @Test
    fun `switches and cooldown veto`() {
        assertFalse(ok(base + 300_000L, base, on = false))
        assertFalse(ok(base + 300_000L, base, off = true))
        assertFalse(ok(base + 300_000L, base, cool = true))
    }

    @Test
    fun `no interaction record skips`() {
        assertFalse(ok(base + 300_000L, null))
    }
}
