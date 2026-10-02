package com.rokidlab.phone.ai.compaction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TokenMeter] 单测 —— 断言刻意**宽松**：这里锁的是"量级正确"（中文 ≈1 字 1 token、
 * 英文明显更省、结果稳定单调），不是 cl100k 词表的逐字节行为 —— 换一个 tokenizer
 * 实现或词表版本，精确数字本来就会动，把测试钉死在具体数字上只会制造无意义的红。
 */
class TokenMeterTest {

    @Test
    fun `A1 空串为 0`() {
        assertEquals(0, TokenMeter.count(""))
    }

    @Test
    fun `A2 英文明显省于 1 字符 1 token`() {
        val s = "hello world, this is a plain english sentence."
        val n = TokenMeter.count(s)
        assertTrue("47 字符的英文应明显少于 47 token，实际 $n", n in 1..30)
    }

    @Test
    fun `A3 中文常用字约 1 字 1 token`() {
        val s = "今天天气不错我们出去玩吧"
        val n = TokenMeter.count(s)
        assertTrue("12 个常用汉字应在 6~15 token 之间，实际 $n", n in 6..15)
    }

    @Test
    fun `A4 同一文本重复计数稳定（界缓存不改变结果）`() {
        val s = "混合内容 mixed content 12345"
        val first = TokenMeter.count(s)
        repeat(3) { assertEquals("重复计数必须一致", first, TokenMeter.count(s)) }
    }

    @Test
    fun `A5 token 数随文本追加单调不减`() {
        val base = "一段用于测试的中文文本"
        val longer = base + "再追加一句"
        assertTrue(
            "追加文本后 token 数不应减少：${TokenMeter.count(base)} -> ${TokenMeter.count(longer)}",
            TokenMeter.count(longer) >= TokenMeter.count(base),
        )
    }
}
