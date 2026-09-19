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
 * 为什么摘要**不调模型**生成：压缩的触发条件正是"上下文快满了"，此刻再发一个大请求
 * 既慢又贵，还可能在同一个窗口上再次溢出 —— 用本地提取的要点行（成本 0、必然成功）
 * 换掉原文，是这个场景下唯一稳妥的选择。将来若要换成模型摘要，实现
 * [CompactionEngine] 即可，调用方零改动。
 */
internal class BasicCompactionEngine(
    override val policy: CompactionPolicy = CompactionPolicy.DEFAULT,
    /** 裁剪日志回调（保持与原实现的 `Log.d("AgentSession", ...)` 输出一致） */
    private val onTrim: (String) -> Unit = {},
) : CompactionEngine {

    private companion object {
        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"
        const val ROLE_SYSTEM = "system"

        /** 滚动摘要的标题行（`hasDigest()` / UI 都靠它判断"早期对话已被压缩"） */
        const val DIGEST_HEADER = "[更早对话摘要]"

        /** [compactNow] 的迭代硬上限：正常收敛远小于它，存在只为"任何情况下都不卡死调用方" */
        const val MAX_ITERATIONS = 64
    }

    /** 一轮裁剪的收敛方向：折进摘要 / 丢孤儿消息 / 无进展 */
    private enum class Progress { COMPRESSED, DROPPED, NONE }

    override fun pressure(history: List<ChatMessage>): Boolean =
        history.size > policy.maxMessages || chars(history) > policy.maxChars

    override fun compactIfNeeded(
        history: MutableList<ChatMessage>,
        trigger: CompactionTrigger,
    ): CompactionResult? {
        val beforeMsgs = history.size
        val beforeChars = chars(history)
        var turns = 0
        var dropped = 0
        // 条件与改造前的 while 逐字一致（条数与字符数是"或"的关系）
        while (history.size > policy.maxMessages || chars(history) > policy.maxChars) {
            if (history.size <= 2) break
            when (compressOldest(history)) {
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
            when (compressOldest(history)) {
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
    private fun compressOldest(history: MutableList<ChatMessage>): Progress = when {
        history[0].role == ROLE_USER -> {
            compressTurnIntoDigest(history, 0)
            Progress.COMPRESSED
        }

        history[0].role == ROLE_SYSTEM && history.size > 1 && history[1].role == ROLE_USER -> {
            compressTurnIntoDigest(history, 1)
            Progress.COMPRESSED
        }

        else -> {
            val dropped = history.removeAt(0)
            onTrim("trim: dropped oldest ${dropped.role} (${dropped.content.length} chars)")
            Progress.DROPPED
        }
    }

    /** 把 [startIndex] 处的 user+assistant 一轮压缩成摘要行，并入置顶滚动摘要 */
    private fun compressTurnIntoDigest(history: MutableList<ChatMessage>, startIndex: Int) {
        val u = history.removeAt(startIndex)
        val a = if (history.size > startIndex && history[startIndex].role == ROLE_ASSISTANT) {
            history.removeAt(startIndex)
        } else null
        val line = "· 用户问\"${keySnippet(u.content)}\"" +
            (a?.let { "，AI答\"${keySnippet(it.content)}\"" } ?: "")

        val digestIdx = if (startIndex == 1) {
            0 // 摘要已在置顶
        } else {
            history.add(0, ChatMessage(role = ROLE_SYSTEM, content = DIGEST_HEADER))
            0
        }
        val digest = history[digestIdx]
        val lines = digest.content.split('\n').toMutableList()
        if (lines.size == 1 && lines[0] == DIGEST_HEADER) {
            lines.add(line)
        } else {
            lines.add(line)
            // 摘要超字符上限：丢最旧的行（保头标题与最新要点）
            while (lines.joinToString("\n").length > policy.digestMaxChars && lines.size > 2) {
                lines.removeAt(1)
            }
        }
        history[digestIdx] = digest.copy(content = lines.joinToString("\n"))
        onTrim("trim: compressed oldest turn into rolling digest")
    }

    /** 提取关键片段：首个完整句（到首个句读符），截断到 60 字 */
    private fun keySnippet(text: String): String {
        val t = text.trim()
        val cut = t.indexOfFirst { it in "。！？!?；;\n" }
        val s = if (cut > 0) t.substring(0, cut + 1) else t
        return if (s.length > 60) s.take(57) + "…" else s
    }

    private fun remainingTurns(history: List<ChatMessage>): Int =
        history.count { it.role == ROLE_USER }

    private fun chars(history: List<ChatMessage>): Int = history.sumOf { it.content.length }
}
