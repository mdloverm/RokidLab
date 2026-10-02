package com.rokidlab.phone.ai.compaction

import com.rokidlab.phone.ai.ChatMessage

/**
 * 默认压缩实现 = 改造前的「滚动摘要」，**算法逐字搬过来**，只有阈值改成读 [policy]。
 *
 * 语义（与改造前完全一致，`AgentSessionHistoryTest` 的 9 条断言就是这份语义的锁）：
 *  - 超预算时把**最旧一轮**（user + 它后面的 assistant）折成摘要里的一行要点
 *    （各取首句、截断 60 字），并入**单条置顶 system 消息**；
 *  - 摘要自身超 [CompactionPolicy.digestMaxChars] 时丢**最旧的行**（保标题与最新要点）；
 *  - 每轮压缩净减至少 1 条，因此循环必然收敛（不会死循环）；
 *  - 遇异常形态（置顶摘要后跟孤立 assistant）直接丢弃该条，防死循环。
 *
 * 摘要的**两层设计**（2026-10-02 起）：
 *  - **本地提取**（本类，成本 0、必然成功）：压缩发生时先用"各取首句、截断 60 字"折成要点行
 *    —— 压缩的触发条件正是"上下文快满了"，压缩本身必须无条件立即成功（那是防 400 的底线）；
 *  - **LLM 精炼**（可选，见 `AgentSessionStore.digestRefiner`）：压缩成功后**追加**一条只换
 *    摘要文本的声明，用被压缩轮的原文重写一版高密度摘要；失败静默保留本地版本。
 *    将来若要整体换成模型摘要，实现 [CompactionEngine] 即可，调用方零改动。
 */
internal class BasicCompactionEngine(
    override val policy: CompactionPolicy = CompactionPolicy.DEFAULT,
    /** 裁剪日志回调（保持与原实现的 `Log.d("AgentSession", ...)` 输出一致） */
    private val onTrim: (String) -> Unit = {},
) : CompactionEngine {

    // internal：`AgentSessionStore` 的 LLM 精炼路径要引用 DIGEST_HEADER（单一数据源）
    internal companion object {
        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"
        const val ROLE_SYSTEM = "system"

        /**
         * 滚动摘要的标题行（`hasDigest()` / UI 都靠它判断"早期对话已被压缩"）。
         * ⚠️ internal：`AgentSessionStore` 的 LLM 精炼路径也要用同一行标题（单一数据源，
         * 两处各写一份迟早分叉 —— 标题变了"被压缩过"的判断就静默失效）。
         */
        internal const val DIGEST_HEADER = "[更早对话摘要]"

        /** [compactNow] 的迭代硬上限：正常收敛远小于它，存在只为"任何情况下都不卡死调用方" */
        const val MAX_ITERATIONS = 64
    }

    /** 一轮裁剪的收敛方向：折进摘要 / 丢孤儿消息 / 无进展 */
    private enum class Progress { COMPRESSED, DROPPED, NONE }

    /**
     * 一次收缩的结果：收敛方向 + 历史 token 总量的**净变化**。
     *
     * 增量记账的理由（2026-10-02 换 token 口径时引入）：压缩循环每迭代一次都要重新判断
     * "还超不超预算"，若每次都对全历史重新编码（BPE 是 O(总长)），大历史下一次压缩
     * 要付 N × 全量编码的代价 —— 有增量 delta 后只对**被移除/变更的那几条**编码一次。
     */
    private class Shrink(val progress: Progress, val tokenDelta: Int)

    override fun pressure(history: List<ChatMessage>): Boolean =
        history.size > policy.maxMessages || overTokenBudget(history)

    override fun compactIfNeeded(
        history: MutableList<ChatMessage>,
        trigger: CompactionTrigger,
    ): CompactionResult? {
        val beforeMsgs = history.size
        val beforeChars = chars(history)
        // 全量编码一次，之后靠 Shrink.tokenDelta 增量维护（见 Shrink 的说明）
        var totalTokens = tokens(history)
        var turns = 0
        var dropped = 0
        // 条数与 token 数是"或"的关系（与改造前的 while 语义一致，只是单位换了）
        while (history.size > policy.maxMessages || totalTokens > policy.maxTokens) {
            if (history.size <= 2) break
            val shrink = compressOldest(history)
            totalTokens += shrink.tokenDelta
            when (shrink.progress) {
                Progress.COMPRESSED -> turns++
                Progress.DROPPED -> dropped++
                Progress.NONE -> break
            }
        }
        if (turns == 0 && dropped == 0) return null
        return CompactionResult(
            trigger = trigger,
            turnsCompressed = turns,
            messagesDropped = dropped,
            digestLines = digestLines(history),
            messagesBefore = beforeMsgs,
            messagesAfter = history.size,
            charsBefore = beforeChars,
            charsAfter = chars(history),
        )
    }

    override fun compactNow(
        history: MutableList<ChatMessage>,
        trigger: CompactionTrigger,
    ): CompactionResult? {
        val beforeMsgs = history.size
        val beforeChars = chars(history)
        var turns = 0
        var dropped = 0
        var guard = 0
        // 目标：除置顶摘要外只剩 policy.keepRecentTurns 轮（最近 2 轮 = 用户大概还能接上话）
        while (remainingTurns(history) > policy.keepRecentTurns && guard < MAX_ITERATIONS) {
            guard++
            when (compressOldest(history).progress) {
                Progress.COMPRESSED -> turns++
                Progress.DROPPED -> dropped++
                Progress.NONE -> break
            }
        }
        if (turns == 0 && dropped == 0) return null
        return CompactionResult(
            trigger = trigger,
            turnsCompressed = turns,
            messagesDropped = dropped,
            digestLines = digestLines(history),
            messagesBefore = beforeMsgs,
            messagesAfter = history.size,
            charsBefore = beforeChars,
            charsAfter = chars(history),
        )
    }

    override fun isTurnBalanced(history: List<ChatMessage>): Boolean {
        // 置顶摘要（system）允许存在且在消息序列之外；其余必须 user/assistant 严格交替、
        // 从 user 开始、**并以 assistant 结尾**（成对）。
        val body = history.dropWhile { it.role == ROLE_SYSTEM }
        // ⚠️ 长度必须为偶数，这一步不能省。只查"严格交替"会漏掉**尾部孤立的 user**：
        //   下标 0 期望 user，单条 [user] 恰好命中，于是被判成"平衡" —— 但它在请求里的表现是
        //   「历史末尾一条 user」紧接着「本轮提问一条 user」＝**两条连续 user 消息**，
        //   正是部分服务端会直接 400 的形态（本测试当场抓到了这个漏洞）。
        if (body.size % 2 != 0) return false
        body.forEachIndexed { i, m ->
            val expected = if (i % 2 == 0) ROLE_USER else ROLE_ASSISTANT
            if (m.role != expected) return false
        }
        return true
    }

    override fun digestLines(history: List<ChatMessage>): Int {
        val head = history.firstOrNull { it.role == ROLE_SYSTEM } ?: return 0
        // 只有标题行时算 0 行要点（"还没压过任何轮"），与 hasDigest 的语义区分开
        return (head.content.lineSequence().count() - 1).coerceAtLeast(0)
    }

    // ═══════════════════ 内部算法（全部与改造前逐字对应）═══════════════════

    /**
     * 对最旧的位置做一次收缩。
     *
     * 三种形态分别对应：正常（首条就是 user）、已有摘要（首条 system + 次条 user）、
     * 异常（摘要后跟孤立 assistant —— 不会出现"待压的轮"，只能丢）。
     */
    private fun compressOldest(history: MutableList<ChatMessage>): Shrink = when {
        history[0].role == ROLE_USER ->
            Shrink(Progress.COMPRESSED, compressTurnIntoDigest(history, 0))

        history[0].role == ROLE_SYSTEM && history.size > 1 && history[1].role == ROLE_USER ->
            Shrink(Progress.COMPRESSED, compressTurnIntoDigest(history, 1))

        else -> {
            val dropped = history.removeAt(0)
            onTrim("trim: dropped oldest ${dropped.role} (${dropped.content.length} chars)")
            Shrink(Progress.DROPPED, -TokenMeter.count(dropped.content))
        }
    }

    /** 把 [startIndex] 处的 user+assistant 一轮压缩成摘要行，并入置顶滚动摘要；返回历史 token 净变化（负数） */
    private fun compressTurnIntoDigest(history: MutableList<ChatMessage>, startIndex: Int): Int {
        val u = history.removeAt(startIndex)
        val a = if (history.size > startIndex && history[startIndex].role == ROLE_ASSISTANT) {
            history.removeAt(startIndex)
        } else null
        val line = "· 用户问\"${keySnippet(u.content)}\"" +
            (a?.let { "，AI答\"${keySnippet(it.content)}\"" } ?: "") +
            toolTraceSuffix(a)

        val digestIdx = if (startIndex == 1) {
            0 // 摘要已在置顶
        } else {
            history.add(0, ChatMessage(role = ROLE_SYSTEM, content = DIGEST_HEADER))
            0
        }
        val digest = history[digestIdx]
        // 增量记账：被移走的两条（负）+ 摘要条内容的变化（见 return 处的符号说明）。
        // 旧摘要/旧消息已编码过会命中 TokenMeter 缓存，真正新编码的只有追加的要点行
        // （一行 ≤ ~160 字符，常数级）。
        val removedTokens = TokenMeter.count(u.content) + (a?.let { TokenMeter.count(it.content) } ?: 0)
        val oldDigestTokens = TokenMeter.count(digest.content)
        val lines = digest.content.split('\n').toMutableList()
        if (lines.size == 1 && lines[0] == DIGEST_HEADER) {
            lines.add(line)
        } else {
            lines.add(line)
            // 摘要超字符上限：丢最旧的行（保头标题与最新要点）
            while (lines.joinToString("\n").length > policy.digestMaxChars && lines.size > 2) {
                lines.removeAt(1)
            }
            // ⚠️ 只剩「标题 + 最后一行」仍超限时硬截末行 —— digestMaxChars 是硬承诺
            //（小窗口下摘要预算是历史窗口的 1/4~1/10，超了等于没压）。
            // A6 测试当场抓到这个漏洞：长 snippet 场景下旧实现会突破上限。
            val joined = lines.joinToString("\n")
            if (joined.length > policy.digestMaxChars && lines.size >= 2) {
                val over = joined.length - policy.digestMaxChars
                val last = lines.removeAt(lines.size - 1)
                lines.add(if (over >= last.length) "" else last.dropLast(over))
            }
        }
        val newContent = lines.joinToString("\n")
        history[digestIdx] = digest.copy(content = newContent)
        onTrim("trim: compressed oldest turn into rolling digest")
        // 返回历史 token 总量的净变化（负数 = 总量下降）：
        //  - 摘要已存在（startIndex==1，原摘要被改写）：-移走的两条 -旧摘要 +新摘要；
        //  - 摘要是新建（startIndex==0）：新消息整个进入历史 → -移走的两条 +新摘要
        //    （⚠️ 新建那条的标题行本身也占 token，漏计会让 running total 偏低）。
        return if (startIndex == 1) {
            -removedTokens - oldDigestTokens + TokenMeter.count(newContent)
        } else {
            -removedTokens + TokenMeter.count(newContent)
        }
    }

    // ── 摘要密度（2026-10-02 升级）：要点行的信息量来自三处 ──
    //  1. 剥寒暄（LEAD_FILLER）：「好的，」「嗯，」对答案零信息，却常常占掉首句；
    //  2. 跳过程句（PROCESS_ONLY）：「我来帮你查一下」是动作不是答案，跳过取下一句；
    //  3. 带上工具轨迹（toolTraceSuffix）：本轮查了天气/设了闹钟，这些「做过什么」的证据
    //     原来在压缩时完全丢失，而它们往往是一轮里最值得记住的部分。

    /** 单条摘要 snippet 上限（字）：剥掉寒暄后适度放宽（原 60），完整事实不再被硬截 */
    private val SNIPPET_MAX = 72

    /** 开头寒暄（需至少跟一个分隔符，避免误剥「好多事情」这类「好」字开头的实义句） */
    private val LEAD_FILLER =
        Regex("^(?:好的?|嗯+|哦+|噢+|没问题|收到|明白了?|我知道了|好嘞|好呀|ok)[，,、.!！。～~\\s]+", RegexOption.IGNORE_CASE)

    /**
     * 纯过程句：整句只是「宣布要做什么」，没有答案内容。
     * ⚠️ 动词刻意写成「我来帮/我去/我帮」——不能只写「我来」，否则「我来上海出差」
     * 这种实义句会被整句跳掉。
     */
    private val PROCESS_ONLY =
        Regex("^(?:我来帮|让我来?|我去|我帮|马上|正在)[^。！？!?；;\n]{0,15}[。,，\\s]*$")

    /** 提取关键片段：剥寒暄 → （过程句则顺延）→ 首个实义句（到首个句读符），截断到 [SNIPPET_MAX] 字 */
    private fun keySnippet(text: String): String {
        var t = text.trim().replace(LEAD_FILLER, "")
        val firstCut = t.indexOfFirst { it in "。！？!?；;\n" }
        if (firstCut > 0 && PROCESS_ONLY.matches(t.substring(0, firstCut + 1))) {
            t = t.substring(firstCut + 1).trim().replace(LEAD_FILLER, "")
        }
        val cut = t.indexOfFirst { it in "。！？!?；;\n" }
        val s = if (cut > 0) t.substring(0, cut + 1) else t
        return if (s.length > SNIPPET_MAX) s.take(SNIPPET_MAX - 1) + "…" else s
    }

    /** 本轮用过的工具（assistant 的 toolTrace，"name(args)" 格式）：保留「做过什么」的证据 */
    private fun toolTraceSuffix(a: ChatMessage?): String {
        val tools = a?.toolTrace
            ?.map { it.substringBefore('(').trim() }
            ?.filter { it.isNotEmpty() }
            ?.distinct()
            .orEmpty()
        if (tools.isEmpty()) return ""
        return "（调用 " + tools.take(3).joinToString("、") + (if (tools.size > 3) "等" else "") + "）"
    }

    private fun remainingTurns(history: List<ChatMessage>): Int =
        history.count { it.role == ROLE_USER }

    /** 历史 token 总量（每条结果都会命中 [TokenMeter] 的界缓存，重复检查近乎零开销） */
    private fun tokens(history: List<ChatMessage>): Int = history.sumOf { TokenMeter.count(it.content) }

    /**
     * 历史 token 是否超预算（带廉价快路径）。
     *
     * cl100k 下文本的 token 数几乎必然 ≤ 字符数（英文约 4 字符/token、中文常用字
     * ≈1 token/字且高频词组合并；反例只有罕见字符 1 字折 2~3 token 的边缘情况）——
     * 因此总字符数还没到 token 预算时**不必逐条编码**。压缩检查每轮对话都会跑，
     * 大多数时候历史离预算很远，这条快路径把常驻开销压到接近 0。
     * 反方向不成立（字符超了 token 未必超），所以过了门槛才做精确计量；快路径放行的
     * 极少数边缘漏判，下一轮消息追加后字符数必然越过门槛、自然落到精确分支。
     */
    private fun overTokenBudget(history: List<ChatMessage>): Boolean {
        if (history.sumOf { it.content.length } <= policy.maxTokens) return false
        return tokens(history) > policy.maxTokens
    }

    /**
     * 字符口径统计 —— ⚠️ 只用于 [CompactionResult]（UI/日志的历史口径不变），
     * **不是**压缩触发的依据（触发看 token，见 [overTokenBudget]）。
     */
    private fun chars(history: List<ChatMessage>): Int = history.sumOf { it.content.length }
}
