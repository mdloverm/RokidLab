package com.rokidlab.phone.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 精确计算器（递归下降解析）金标用例 */
class CalculatorTest {

    private fun eval(s: String) = Calculator.evaluate(s)

    @Test
    fun `四则运算优先级`() {
        assertEquals(14.0, eval("2+3*4"), 0.0)
        assertEquals(10.0, eval("(2+3)*2"), 0.0)
        assertEquals(-4.0, eval("2-3*2"), 0.0)
    }

    @Test
    fun `大数乘除精确`() {
        assertEquals(21168.0, eval("378*56"), 0.0)
        assertEquals(47.0, eval("312973/6659"), 1e-9) // 312973 = 6659 × 47
        assertEquals(2.0, eval("14000000000/7000000000"), 0.0)
    }

    @Test
    fun `幂与右结合`() {
        assertEquals(512.0, eval("2^3^2"), 0.0) // 2^(3^2)，非 (2^3)^2=64
        assertEquals(1024.0, eval("2^10"), 0.0)
    }

    @Test
    fun `取模小数与负号`() {
        assertEquals(1.0, eval("10%3"), 0.0)
        assertEquals(0.5, eval("1.5/3"), 0.0)
        assertEquals(-5.0, eval("-2.5*2"), 0.0)
        assertEquals(5.0, eval("2--3"), 0.0)
    }

    @Test
    fun `全角符号容错`() {
        assertEquals(7.0, eval("3×4÷2+1"), 0.0) // × ÷ 自动替换
        assertEquals(7.0, eval("1,000-9,9,3"), 0.0) // 千分位逗号剔除
    }

    @Test
    fun `除零与非法输入抛异常`() {
        listOf("1/0", "10%0", "", "1+2)", "abc", "1..2", "((1+2)", "1+").forEach { expr ->
            var thrown = false
            try {
                eval(expr)
            } catch (_: IllegalArgumentException) {
                thrown = true
            }
            assertTrue("「$expr」应抛 IllegalArgumentException", thrown)
        }
    }
}
