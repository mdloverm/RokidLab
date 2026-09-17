package com.rokidlab.phone.ai

/**
 * 剥离模型偶发输出的「包裹标签」。
 *
 * 背景（2026-09-17 真机事故）：system prompt 里用了 `<memories>` / `<skills>` 这类 XML 风格标签，
 * 模型会**模仿这种风格**把正文包起来，于是回复变成了 `<answer>西安明天晴，最高 31 度…`，
 * 这段文本原样出现在眼镜屏幕上（用户实测看到 `answer` 字样）。
 *
 * 清洗策略（保守，只动「整段包裹」，绝不碰正文内部的尖括号）：
 * 1. 只有整段回复**以 `<` 开头**时才处理（正常中文正文不会这样开头，避免误伤）；
 * 2. 若存在 `<answer>…</answer>` 一类已知块，取**最后一个**块的内容
 *    （模型常把思考过程也吐出来，最后一个才是正文）；
 * 3. 反复剥掉最外层包裹标签（可嵌套，最多 5 层）；
 * 4. 去掉首尾残留的孤立已知标签（例如只有开标签、没闭合）。
 *
 * 「已知」标签白名单见 [KNOWN] —— 只认这些名字，因此正文里合法的 `<b>`/`<div>` 等不受影响。
 */
object ReplySanitizer {

    /** 被识别为「正文包裹标签」的名字（大小写不敏感）。刻意收窄，避免误伤正文。 */
    private const val KNOWN = "(?:answer|response|reply|result|final|output|text|回答|答案)"

    private const val ATTR = "(?:\\s[^>]*)?"

    private val BLOCK = Regex("<($KNOWN)$ATTR>([\\s\\S]*?)</\\1>", RegexOption.IGNORE_CASE)
    private val WRAP = Regex("^\\s*<([A-Za-z][A-Za-z0-9_-]*)$ATTR>([\\s\\S]*?)</\\1>\\s*$")
    private val LEAD = Regex("^\\s*<$KNOWN$ATTR>\\s*", RegexOption.IGNORE_CASE)
    private val TAIL = Regex("\\s*</$KNOWN>\\s*$", RegexOption.IGNORE_CASE)

    fun sanitize(raw: String): String {
        var s = raw.trim()
        if (s.isEmpty() || !s.startsWith("<")) return s

        val blocks = BLOCK.findAll(s).toList()
        if (blocks.isNotEmpty()) {
            val last = blocks.last()
            val body = last.groupValues[2].trim()
            // 块之后若还有正文（畸形输出），保留下来，避免丢字
            val after = s.substring(last.range.last + 1).trim()
            s = if (after.isEmpty()) body else "$body $after"
        }

        var guard = 0
        while (guard++ < 5) {
            val m = WRAP.find(s) ?: break
            s = m.groupValues[2].trim()
        }

        s = LEAD.replace(s, "")
        s = TAIL.replace(s, "")
        return s.trim()
    }
}
