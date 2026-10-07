package com.rokidlab.phone.ai

import org.json.JSONObject

/**
 * 工具调用参数的**纯函数**工具集：截断自愈 + 名字纠错。
 *
 * ## 为什么要独立成文件
 *
 * 这四段原先是 `AiConversationService.sendAiTextViaLink`（单函数 2000+ 行）里的局部函数。
 * 局部函数的特点是「能跑但测不了」：没有 test 目录里的入口能碰到它，于是
 * 「AI 空轮 400」和「工具参数截断」这两类根因只能靠真机肉眼验证。
 * 搬到这里之后它们是 [AiToolParamRepair] 的公开成员，单测直接锁行为。
 *
 * **行为与搬迁前逐字一致**（纯重构，不改任何判定）。
 *
 * ## 截断自愈的由来（差距⑦，2026-10-01）
 *
 * 原生 `tool_calls` 协议下，输出被 `max_tokens` 截断表现为 `arguments` JSON 断在半途 ——
 * 直接判失败会白耗一轮。多数截断只是「没闭合」：字符串没闭合、括号没收尾。
 * 这里做**保守**修复：只闭合悬空结构，截在值中间就逐层回退到最近结构位重试。
 * 修不了返回 `null`，由调用方照旧走教学式报错。
 */
object AiToolParamRepair {

    /**
     * 闭合被截断的 JSON 片段。
     *
     * 只做「补缺口」：补引号、补右括号、悬空冒号补 `null`。
     * **只碰悬空结构，绝不改动已经写好的值**。
     *
     * @return 闭合后的文本；原本就闭合（或结构已经错乱到无法安全闭合）时返回 `null`
     */
    fun closeTruncatedJson(s: String): String? {
        val stack = ArrayDeque<Char>()
        var inStr = false
        var esc = false
        for (c in s) {
            if (inStr) {
                when {
                    esc -> esc = false
                    c == '\\' -> esc = true
                    c == '"' -> inStr = false
                }
            } else {
                when (c) {
                    '"' -> inStr = true
                    '{' -> stack.addLast('}')
                    '[' -> stack.addLast(']')
                    '}' -> if (stack.isEmpty() || stack.removeLast() != '}') return null
                    ']' -> if (stack.isEmpty() || stack.removeLast() != ']') return null
                }
            }
        }
        if (!inStr && stack.isEmpty()) return null // 本来就闭合，无需修复
        val sb = StringBuilder(s)
        if (inStr) {
            // 悬空转义符（截断正落在 \" 的反斜杠后）会吞掉补上的引号，先摘掉
            if (esc && sb.isNotEmpty() && sb[sb.length - 1] == '\\') sb.setLength(sb.length - 1)
            sb.append('"')
        }
        var t = sb.toString().trimEnd()
        if (t.endsWith(":")) t += "null" // 悬空冒号：key 后没来得及写值
        while (stack.isNotEmpty()) t += stack.removeLast()
        return t
    }

    /**
     * 把「闭合后仍非法」的片段逐层回退到最近的结构位重试。
     *
     * 为什么要回退：截断点可能落在数字/关键字/逗号中间（`{"a":12,34` 或 `{"flag":tr`），
     * 这时单纯补括号补不出合法 JSON，得退到 `,`/`:`/`{`/`[` 之后重试。
     * 最多回退 [MAX_REPAIR_ATTEMPTS] 次仍不成即放弃（返回 `null`），不让病态输入拖住一轮对话。
     *
     * ⚠️ 两条已确认的边界（2026-10-05 补单测时实测，别误当 bug 修）：
     * 1. **已闭合的合法 JSON 返 `null`**（`{}` 也是）—— 上层只在首次解析失败后才调本函数，
     *    所以正常输入走不到这里，返 `null` 不会造成误判。
     * 2. **本函数只保证"多数截断能救回来"，不保证"语义正确"**：org.json 的 tokener 偏宽松，
     *    例如 `{"a":@@}` 补完括号后它**居然解析成功**，于是原样返回。
     *    真要严校验参数得在调用侧做，不是这里该管的事。
     */
    fun repairTruncatedJson(raw: String): String? {
        val s = raw.trim()
        if (!s.startsWith("{")) return null
        var cut = s
        var attempt = 0
        while (attempt < MAX_REPAIR_ATTEMPTS) {
            val closed = closeTruncatedJson(cut)
            if (closed != null) {
                val parsed = runCatching { JSONObject(closed) }.getOrNull()
                if (parsed != null) return closed
            }
            // 闭合后仍非法（截在数字/关键字/逗号中间）→ 回退到最近的结构位重试
            val lastStruct = cut.indexOfLast { it == ',' || it == ':' || it == '{' || it == '[' }
            if (lastStruct <= 0) return null
            cut = cut.substring(0, lastStruct)
            attempt++
        }
        return null
    }

    /** 经典编辑距离（未知工具的"你可能想调的是"建议用，只在小字符串上跑） */
    fun editDistance(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var curr = IntArray(b.length + 1)
        for (i in 1..a.length) {
            curr[0] = i
            for (j in 1..b.length) {
                curr[j] = minOf(
                    prev[j] + 1,
                    curr[j - 1] + 1,
                    prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1,
                )
            }
            val tmp = prev; prev = curr; curr = tmp
        }
        return prev[b.length]
    }

    /**
     * `save_code_file` 的 `(project, file)` 提取器。
     *
     * ⚠️ 提取成**不可变的两个 String** 才是真安全：不给 `ToolCallInfo` 加「懒解析
     * arguments」字段，是因为 `JSONObject` 底层是 `LinkedHashMap`，**非线程安全**；
     * Kotlin `by lazy` 默认 `SynchronizedLazyImpl` 虽保证初始化安全，却会把**同一个实例**
     * 交给多个线程 —— 一旦有任一线程后续调 put/opt 触发结构性修改，就是跨轮跨线程的
     * 隐蔽污染源。
     *
     * ⚠️ 生成线程上的进度提示那处**故意仍自己解析**：它与本处并发，不共享任何对象。
     * 解析 `arguments` 本身很廉价，不值得为省这一次引入共享。
     */
    fun codeFileTarget(arguments: String): Pair<String, String>? =
        runCatching {
            val fa = JSONObject(arguments)
            val p = fa.optString("project").trim()
            val f = fa.optString("file").trim()
            if (p.isNotEmpty() && f.isNotEmpty()) p to f else null
        }.getOrNull()

    private const val MAX_REPAIR_ATTEMPTS = 64
}
