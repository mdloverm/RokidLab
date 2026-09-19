package com.rokidlab.phone.ai.session

/**
 * 把事件流**渲染成给模型看的文本**（会话查询工具族的展示层）。
 *
 * ★ 为什么单独一层而不是写在工具里：同一份"事件长什么样"会被三个地方用 ——
 *   模型可见的工具回填（`read_session` / `session_trace`）、App 内日志面板、
 *   以及将来的轨迹视图（方案 §4.3.5）。写在工具里等于把展示口径绑死在工具上，
 *   改一次文案要动三个地方，而且**只有工具那侧可测**。
 *
 * ★ 纯函数、无 Android 依赖 ⇒ 可被 JVM 单测直接驱动，也就能钉住"截断/续读"这类
 *   容易写错又不容易被发现的边界（超长历史截在那里、`fromSeq` 给的是不是接得上）。
 */
internal object SessionDump {

    /** 单次回填的字符上限（事件流可以很长，全量回填会直接吃光上下文） */
    const val MAX_CHARS = 6000

    /** 单条正文的展示上限（工具返回动辄上千字，逐条截断比整体截断更可读） */
    const val TEXT_CLIP = 160

    /** 压缩摘要本身的展示上限（它自己可能就有几百字，且往往不是本次要查的重点） */
    private const val DIGEST_CLIP = 240

    /**
     * 按时间序渲染事件流。
     *
     * @param fromSeq 只渲染 `seq >= fromSeq` 的事件（配合返回里给的续读位置）
     * @param limit 最多渲染多少条
     * @return 文本；**超出上限时会明确写出"下次该传什么 fromSeq"** —— 少了这一句，
     *   模型只会看到一段莫名中断的历史，然后猜"是不是只有这些"
     */
    fun dumpSession(
        records: List<SessionRecord>,
        fromSeq: Long = 0L,
        limit: Int = 30,
        maxChars: Int = MAX_CHARS,
    ): String {
        val picked = records.filter { it.seq >= fromSeq }.take(limit)
        if (picked.isEmpty()) {
            return if (records.isEmpty()) "（这个会话还没有任何事件）" else "（这个位置之后没有事件了）"
        }
        val sb = StringBuilder()
        var used = 0
        var lastSeq = 0L
        var skipped = 0
        for (r in picked) {
            val line = renderEvent(r)
            if (used + line.length + 1 > maxChars) {
                skipped++
                continue
            }
            sb.append(line).append('\n')
            used += line.length + 1
            lastSeq = r.seq
        }
        val notes = ArrayList<String>()
        if (skipped > 0) {
            notes.add("内容较长，本次显示到 #$lastSeq，还有 $skipped 条没显示；接着读请传 fromSeq=${lastSeq + 1}")
        } else if (records.size > picked.size) {
            notes.add("本次只显示了 $limit 条（总计 ${records.size} 条）；接着读请传 fromSeq=${lastSeq + 1}")
        }
        if (notes.isNotEmpty()) sb.append("……（").append(notes.joinToString("；")).append("）")
        return sb.toString().trimEnd()
    }

    /** 最近一个**调用过工具**的轮次；一次都没调过时返回 null */
    fun latestToolTurn(records: List<SessionRecord>): Int? =
        records.mapNotNull { (it.event as? ToolCall)?.turn }.maxOrNull()

    /**
     * 渲染某一轮的工具调用链（血缘）：谁调了、参数是什么、回来什么、有没有没回来的。
     *
     * "调了没回来"是事件流带来的新可观测性：改造前工具调用只是一条 `name(args)` 字符串，
     * 进程被杀和正常完成在记录上长得**一模一样**（见 [SessionProjection.orphanToolCalls]）。
     */
    fun dumpTrace(records: List<SessionRecord>, turn: Int): String {
        val calls = records.filter { (it.event as? ToolCall)?.turn == turn }.mapNotNull { it.event as? ToolCall }
        if (calls.isEmpty()) return "第 $turn 轮没有调用任何工具。"
        val results = records.mapNotNull { it.event as? ToolResult }.associateBy { it.callId }
        val projection = SessionProjection(records)
        val orphans = projection.orphanToolCalls().map { it.callId }.toSet()
        val stat = projection.turnStats().firstOrNull { it.turn == turn }

        val head = buildString {
            append("第 $turn 轮的工具调用链：共 ${calls.size} 次调用")
            stat?.reason?.let { append("，结束原因 ${it.wire}") }
            stat?.elapsedMs?.let { append("，耗时 ${formatSeconds(it)}") }
            // 调用次数只在 >1 时报：1 次是常态，写出来是噪音
            stat?.modelCalls?.takeIf { it > 1 }?.let { append("，共 $it 次模型调用") }
            stat?.promptTokens?.let { append("，输入 ${it} token") }
            stat?.completionTokens?.let { append("，输出 ${it} token") }
        }
        val body = calls.mapIndexed { i, c ->
            val res = results[c.callId]
            val outcome = when {
                res != null -> clip(res.content, TEXT_CLIP) + if (res.truncated) "（原文已截断）" else ""
                c.callId in orphans -> "**没有返回** —— 这一步之后进程被打断，或工具卡死了"
                else -> "（没有记录到返回）"
            }
            "[${i + 1}] ${c.name}(${clip(c.args, TEXT_CLIP)})\n     → $outcome"
        }
        return head + "\n" + body.joinToString("\n")
    }

    // ═══════════════════════ 内部 ═══════════════════════

    /** 一条事件的单行叙述（`#seq 轮N 谁：说了什么`） */
    private fun renderEvent(r: SessionRecord): String {
        val who = r.event.turn?.let { "轮$it " }.orEmpty()
        return when (val e = r.event) {
            is TurnStart -> "#${r.seq} ${who}— 收到（${e.source.wire}）"
            is UserMessage -> "#${r.seq} ${who}用户：${clip(e.text, TEXT_CLIP)}"
            is AssistantMessage -> "#${r.seq} ${who}乐奇：${clip(e.text, TEXT_CLIP)}"
            is ToolCall -> "#${r.seq} ${who}调用工具 ${e.name}(${clip(e.args, TEXT_CLIP)})"
            is ToolResult -> "#${r.seq} ${who}工具返回 ${e.name} → ${clip(e.content, TEXT_CLIP)}" +
                if (e.truncated) "（原文已截断）" else ""
            is ContextInject -> "#${r.seq} ${who}注入上下文[${e.origin}]：${clip(e.content, TEXT_CLIP)}"
            is AssistantAttempt -> "#${r.seq} ${who}失败尝试（${e.outcome.wire}）" +
                (e.detail?.let { "：$it" } ?: "")
            is TurnEnd -> "#${r.seq} ${who}— 结束（${e.reason.wire}）" + (e.detail?.let { "：$it" } ?: "")
            is CompactionStart -> "#${r.seq} 压缩开始（${e.trigger}）"
            is CompactionSummary -> "#${r.seq} 压缩：把 ${e.shadowedSeqs.size} 条早期事件折成摘要 → " +
                clip(e.text, DIGEST_CLIP)
            is CompactionEnd -> "#${r.seq} 压缩结束" + (e.error?.let { "（失败：$it）" } ?: "")
            is VisibilityCut -> "#${r.seq} 作废 ${e.seqs.size} 条（${e.reason}）" +
                "—— 这些不再进模型上下文，但原文仍在"
        }
    }

    private fun clip(s: String, max: Int): String {
        val oneLine = s.replace('\n', ' ').replace(Regex("\\s+"), " ").trim()
        return if (oneLine.length > max) oneLine.take(max) + "…" else oneLine
    }

    private fun formatSeconds(ms: Long): String = "%.1f".format(ms / 1000.0) + " 秒"
}
