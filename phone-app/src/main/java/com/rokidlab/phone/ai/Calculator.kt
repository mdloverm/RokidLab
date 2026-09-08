package com.rokidlab.phone.ai

/**
 * 精确算术计算器（纯 JVM、无 Android 依赖，可单测）。
 *
 * 递归下降 + 优先级 climbing 解析四则运算表达式，防止 LLM 心算大数乘除出错。
 * 支持：+ - * / % ^（幂）、括号、一元负号、整数与小数。
 * 不支持函数/变量（模型遇到需要换算口径的先自行化简为算式再调用）。
 */
internal object Calculator {

    /** 计算表达式；非法（除零/空/含未知字符）时抛 [IllegalArgumentException] */
    fun evaluate(expr: String): Double {
        val tokens = tokenize(expr)
        if (tokens.isEmpty()) throw IllegalArgumentException("表达式为空")
        val parser = Parser(tokens)
        val result = parser.parseExpression()
        if (parser.pos < tokens.size) throw IllegalArgumentException("表达式在位置 ${parser.pos} 后有非法残余")
        return result
    }

    private sealed class Tok {
        data class Num(val value: Double) : Tok()
        data class Op(val ch: Char) : Tok()
        object LParen : Tok()
        object RParen : Tok()
    }

    private fun tokenize(expr: String): List<Tok> {
        val tokens = mutableListOf<Tok>()
        var i = 0
        val s = expr.replace("×", "*").replace("÷", "/").replace("，", "").replace(",", "")
        while (i < s.length) {
            val c = s[i]
            when {
                c.isWhitespace() -> i++
                c.isDigit() || c == '.' -> {
                    var j = i
                    var dots = 0
                    while (j < s.length && (s[j].isDigit() || s[j] == '.')) {
                        if (s[j] == '.') dots++
                        j++
                    }
                    if (dots > 1) throw IllegalArgumentException("数字格式非法: ${s.substring(i, j)}")
                    val text = s.substring(i, j)
                    tokens.add(Tok.Num(text.toDoubleOrNull() ?: throw IllegalArgumentException("数字格式非法: $text")))
                    i = j
                }
                c == '+' || c == '-' || c == '*' || c == '/' || c == '%' || c == '^' -> {
                    tokens.add(Tok.Op(c)); i++
                }
                c == '(' -> { tokens.add(Tok.LParen); i++ }
                c == ')' -> { tokens.add(Tok.RParen); i++ }
                else -> throw IllegalArgumentException("非法字符: $c")
            }
        }
        return tokens
    }

    private class Parser(val tokens: List<Tok>) {
        var pos = 0

        /** expr := term (('+'|'-') term)* */
        fun parseExpression(): Double {
            var left = parseTerm()
            while (true) {
                val t = peek() as? Tok.Op ?: break
                if (t.ch != '+' && t.ch != '-') break
                pos++
                val right = parseTerm()
                left = if (t.ch == '+') left + right else left - right
            }
            return left
        }

        /** term := factor (('*'|'/'|'%') factor)* */
        private fun parseTerm(): Double {
            var left = parseFactor()
            while (true) {
                val t = peek() as? Tok.Op ?: break
                if (t.ch != '*' && t.ch != '/' && t.ch != '%') break
                pos++
                val right = parseFactor()
                when (t.ch) {
                    '*' -> left *= right
                    '/' -> {
                        if (right == 0.0) throw IllegalArgumentException("除数为零")
                        left /= right
                    }
                    '%' -> {
                        if (right == 0.0) throw IllegalArgumentException("取模除数为零")
                        left %= right
                    }
                }
            }
            return left
        }

        /** factor := unary ('^' factor)? （右结合幂） */
        private fun parseFactor(): Double {
            val base = parseUnary()
            val t = peek() as? Tok.Op
            if (t != null && t.ch == '^') {
                pos++
                val exp = parseFactor() // 右结合：2^3^2 = 2^(3^2)
                val r = pow(base, exp)
                if (r.isNaN() || r.isInfinite()) throw IllegalArgumentException("幂运算结果溢出")
                return r
            }
            return base
        }

        /** unary := ('-')* atom */
        private fun parseUnary(): Double {
            val t = peek()
            if (t is Tok.Op && t.ch == '-') {
                pos++
                return -parseUnary()
            }
            return parseAtom()
        }

        /** atom := number | '(' expr ')' */
        private fun parseAtom(): Double {
            val t = next() ?: throw IllegalArgumentException("表达式不完整")
            return when (t) {
                is Tok.Num -> t.value
                Tok.LParen -> {
                    val v = parseExpression()
                    val close = next()
                    if (close != Tok.RParen) throw IllegalArgumentException("括号不匹配")
                    v
                }
                else -> throw IllegalArgumentException("意外的符号")
            }
        }

        private fun peek(): Tok? = tokens.getOrNull(pos)
        private fun next(): Tok? = tokens.getOrNull(pos)?.also { pos++ }

        private fun pow(base: Double, exp: Double): Double {
            // 整数指数用连乘避免 Math.pow 的浮点边缘误差；小数指数回退 Math.pow
            return if (exp == Math.floor(exp) && exp >= 0 && exp <= 512) {
                var result = 1.0
                repeat(exp.toInt()) { result *= base }
                result
            } else {
                Math.pow(base, exp)
            }
        }
    }
}
