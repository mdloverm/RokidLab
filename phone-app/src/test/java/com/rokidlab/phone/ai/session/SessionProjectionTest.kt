package com.rokidlab.phone.ai.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SessionProjection]（事件流 → 模型可见历史）单测。
 *
 * 锁定语义：
 *  1. 历史是**派生**的：只投影 user/assistant，失败尝试与压缩元事件不进上下文；
 *  2. 工具轨迹由同轮 [ToolCall] 派生（不再单独存一份，格式与改造前一致）；
 *  3. **压缩不销毁历史**：被覆盖的事件用 seq 标记排除，但它们仍在事件流里；
 *  4. 多次压缩的覆盖集合取并集；当前摘要取最后一次（不是拼接）。
 *  5. [VisibilityCut]（过期清空 / 丢弃最后一轮）与压缩是**两种不同的**退出可见历史：
 *     前者"不算数"（`hiddenSeqs` 独有）、后者"换了形态"（`shadowedSeqs`），
 *     且 `clearDigest` 决定要不要连滚动摘要一起清 —— 这个区别分开暴露就是为了能被测出来。
 */
class SessionProjectionTest {

    /** 按顺序打上递增 seq 与递增 ts，模拟真实日志 */
    private fun records(vararg events: SessionEvent): List<SessionRecord> {
        var seq = 0L
        return events.map { SessionRecord(seq = ++seq, ts = 1_000L + seq, event = it) }
    }

    private fun projectionOf(vararg events: SessionEvent) = SessionProjection(records(*events))

    /** 一轮完整事件：开始 → 用户 → 回复 → 结束 */
    private fun turn(n: Int, user: String, reply: String) = arrayOf<SessionEvent>(
        TurnStart(n),
        UserMessage(n, text = user),
        AssistantMessage(n, text = reply),
        TurnEnd(n, reason = TurnEndReason.COMPLETED),
    )

    // ═══════════════════ 基本投影 ═══════════════════

    @Test
    fun `历史按事件流重建为交替的 user 与 assistant`() {
        val p = projectionOf(*turn(1, "你好", "你好呀"), *turn(2, "天气", "晴天"))

        val h = p.history()
        assertEquals(4, h.size)
        assertEquals(listOf("user", "assistant", "user", "assistant"), h.map { it.role })
        assertEquals(listOf("你好", "你好呀", "天气", "晴天"), h.map { it.content })
        assertTrue(p.isTurnBalanced())
        assertEquals(4, p.messageCount())
    }

    /** 失败尝试是"可观测但不能污染上下文"的典型 —— 这是事件流最直接的收益之一 */
    @Test
    fun `失败尝试不进模型历史`() {
        val p = projectionOf(
            TurnStart(1),
            UserMessage(1, text = "在吗"),
            AssistantAttempt(1, outcome = AttemptOutcome.TIMEOUT, detail = "30s"),
            AssistantAttempt(1, outcome = AttemptOutcome.SUPERSEDED),
            AssistantMessage(1, text = "在的"),
            TurnEnd(1, reason = TurnEndReason.COMPLETED),
        )

        val h = p.history()
        assertEquals(2, h.size)
        assertEquals(listOf("在吗", "在的"), h.map { it.content })
    }

    @Test
    fun `工具轨迹由同轮调用派生且格式不变`() {
        val p = projectionOf(
            TurnStart(1),
            UserMessage(1, text = "看下天气"),
            ToolCall(1, step = 1, callId = "c1", name = "get_weather", args = "{\"city\":\"北京\"}"),
            ToolResult(1, step = 1, callId = "c1", name = "get_weather", content = "晴 26℃"),
            ToolCall(1, step = 2, callId = "c2", name = "get_now_playing", args = "{}"),
            ToolResult(1, step = 2, callId = "c2", name = "get_now_playing", content = "无"),
            AssistantMessage(1, text = "北京晴，26 度"),
            TurnEnd(1, reason = TurnEndReason.COMPLETED),
        )

        val assistant = p.history().last()
        assertEquals("assistant", assistant.role)
        assertEquals(
            listOf("get_weather({\"city\":\"北京\"})", "get_now_playing({})"),
            assistant.toolTrace,
        )
        // 工具事件本身不进模型历史（只有派生的轨迹附着在 assistant 上）
        assertEquals(2, p.history().size)
    }

    @Test
    fun `工具事件不改变消息条数与字符统计口径`() {
        val withTools = projectionOf(
            TurnStart(1),
            UserMessage(1, text = "查"),
            ToolCall(1, callId = "c1", name = "f", args = "{}"),
            ToolResult(1, callId = "c1", name = "f", content = "很长很长的工具返回内容"),
            AssistantMessage(1, text = "好"),
            TurnEnd(1, reason = TurnEndReason.COMPLETED),
        )
        val withoutTools = projectionOf(*turn(1, "查", "好"))

        assertEquals(withoutTools.messageCount(), withTools.messageCount())
        assertEquals(withoutTools.chars(), withTools.chars())
    }

    // ═══════════════════ 压缩：不销毁历史 ═══════════════════

    @Test
    fun `被摘要覆盖的事件不进历史但摘要置顶`() {
        val all = records(
            *turn(1, "第一轮", "回复一"),
            *turn(2, "第二轮", "回复二"),
        )
        // seq 1..4 是第一轮，5..8 是第二轮；假设第一轮被折进摘要
        val shadowed = listOf(1L, 2L, 3L, 4L)
        val withDigest = all + SessionRecord(
            seq = 9L,
            ts = 2_000L,
            event = CompactionSummary(
                turn = null,
                shadowedSeqs = shadowed,
                text = "[更早对话摘要]\n· 用户问\"第一轮\"，AI答\"回复一\"",
                turnsCompressed = 1,
            ),
        )
        val p = SessionProjection(withDigest)

        val h = p.history()
        assertEquals(listOf("system", "user", "assistant"), h.map { it.role })
        assertTrue(h[0].content.startsWith("[更早对话摘要]"))
        assertEquals("第二轮", h[1].content)
        // 被覆盖的事件仍在事件流里（输入 9 条一条没少）—— "AI 忘了"与"查不到"是两件事
        assertEquals(9, withDigest.size)
        assertEquals(shadowed.toSet(), p.shadowedSeqs)
        assertTrue(p.hasDigest())
    }

    @Test
    fun `多次压缩的覆盖集合取并集`() {
        val p = projectionOf(
            *turn(1, "一", "壹"),
            *turn(2, "二", "贰"),
            *turn(3, "三", "叁"),
            CompactionSummary(turn = null, shadowedSeqs = listOf(1L, 2L, 3L, 4L), text = "[更早对话摘要]\n· 一"),
            CompactionSummary(turn = null, shadowedSeqs = listOf(5L, 6L, 7L, 8L), text = "[更早对话摘要]\n· 二"),
        )

        assertEquals(setOf(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L), p.shadowedSeqs)
        // 只剩第三轮
        assertEquals(listOf("user", "assistant"), p.history().drop(1).map { it.role })
        assertEquals("三", p.history()[1].content)
    }

    @Test
    fun `当前摘要取最后一次而不是拼接`() {
        val p = projectionOf(
            CompactionSummary(turn = null, shadowedSeqs = listOf(1L), text = "旧摘要"),
            CompactionSummary(turn = null, shadowedSeqs = listOf(2L), text = "新摘要"),
        )
        // 拼接会让摘要随压缩次数线性增长 —— 那正是它要防的事
        assertEquals("新摘要", p.digest)
    }

    @Test
    fun `没有压缩时摘要为空`() {
        val p = projectionOf(*turn(1, "一", "壹"))
        assertNull(p.digest)
        assertFalse(p.hasDigest())
        assertTrue(p.shadowedSeqs.isEmpty())
    }

    @Test
    fun `摘要字符数计入上下文占用`() {
        val p = projectionOf(
            *turn(1, "一", "壹"),
            CompactionSummary(turn = null, shadowedSeqs = listOf(1L, 2L, 3L, 4L), text = "0123456789"),
        )
        // 摘要 10 字符 + 空的历史（第一轮全被覆盖）
        assertEquals(10, p.chars())
        assertEquals(1, p.history().size)
        assertEquals(0, p.messageCount())
    }

    @Test
    fun `空事件流投影出空历史`() {
        val p = SessionProjection(emptyList())
        assertTrue(p.history().isEmpty())
        assertTrue(p.isTurnBalanced())
        assertEquals(0, p.messageCount())
        assertEquals(0, p.chars())
        assertEquals(0L, p.lastActivityTs())
    }

    // ═══════════════════ 诊断 ═══════════════════

    @Test
    fun `lastActivityTs 取最后一条事件的时间`() {
        val p = projectionOf(*turn(1, "一", "壹"))
        assertEquals(1_004L, p.lastActivityTs())
    }

    @Test
    fun `孤儿工具调用可被识别`() {
        val p = projectionOf(
            TurnStart(1),
            UserMessage(1, text = "跑个长任务"),
            ToolCall(1, callId = "c1", name = "start_job", args = "{}"),
            ToolCall(1, callId = "c2", name = "poll", args = "{}"),
            ToolResult(1, callId = "c2", name = "poll", content = "running"),
            // c1 没有结果 —— 进程在这里被杀过
        )

        val orphans = p.orphanToolCalls()
        assertEquals(1, orphans.size)
        assertEquals("c1", orphans[0].callId)
    }

    @Test
    fun `空白 callId 不算孤儿`() {
        val p = projectionOf(
            TurnStart(1),
            ToolCall(1, callId = "", name = "f", args = "{}"),
        )
        assertTrue(p.orphanToolCalls().isEmpty())
    }

    @Test
    fun `上下文字段不进入模型历史`() {
        val p = projectionOf(
            TurnStart(1),
            ContextInject(1, origin = "knowledge", content = "《手册》第1块：……"),
            UserMessage(1, text = "问"),
            AssistantMessage(1, text = "答"),
            TurnEnd(1, reason = TurnEndReason.COMPLETED),
        )
        assertEquals(listOf("问", "答"), p.history().map { it.content })
    }

    // ═══════════════════ VisibilityCut：退出可见历史（不删行）═══════════════════

    @Test
    fun `VisibilityCut 把指定事件移出模型历史但事件仍在流里`() {
        // 事件：1 TurnStart / 2 user / 3 assistant / 4 TurnEnd / 5 丢弃最后一轮
        val all = records(*turn(1, "甲", "壹"), *turn(2, "乙", "贰"))
        val p = SessionProjection(all + SessionRecord(9, 9_999L, VisibilityCut(
            turn = null, seqs = listOf(5L, 6L, 7L, 8L), reason = "drop_last_turn",
        )))

        assertEquals("最后一轮退出可见历史", listOf("甲", "壹"), p.history().map { it.content })
        assertEquals("被作废的 seq 单独暴露（与『被摘要覆盖』不是一回事）", setOf(5L, 6L, 7L, 8L), p.hiddenSeqs - p.shadowedSeqs)
        assertTrue("这不是压缩，所以不该出现在 shadowedSeqs 里", p.shadowedSeqs.isEmpty())
        assertEquals("事件流本身没有被改写：被作废那轮的轮号仍然在", 2, p.maxTurn())
    }

    @Test
    fun `clearDigest 的 VisibilityCut 连滚动摘要一起清掉`() {
        val p = projectionOf(
            *turn(1, "甲", "壹"),
            CompactionSummary(turn = null, shadowedSeqs = listOf(2L, 3L), text = "[更早对话摘要]\n· 甲"),
            *turn(2, "乙", "贰"),
            // 10 分钟无活动：把当时可见的一切（含摘要）声明为不算数（seq 7=user、8=assistant）
            VisibilityCut(turn = null, seqs = listOf(7L, 8L), clearDigest = true, reason = "expire"),
        )

        assertTrue("过期清空后历史必须为空", p.history().isEmpty())
        assertNull("摘要也要一起消失 —— 否则像没清干净", p.digest)
        assertFalse("hasDigest 必须跟着变 false", p.hasDigest())
    }

    @Test
    fun `不带 clearDigest 的 VisibilityCut 保留摘要`() {
        val p = projectionOf(
            *turn(1, "甲", "壹"),
            CompactionSummary(turn = null, shadowedSeqs = listOf(2L, 3L), text = "[更早对话摘要]\n· 甲"),
            *turn(2, "乙", "贰"),
            VisibilityCut(turn = null, seqs = listOf(7L, 8L), clearDigest = false, reason = "drop_last_turn"),
        )

        assertEquals("更早的记忆仍然有效：只剩置顶摘要", 1, p.history().size)
        assertEquals("[更早对话摘要]\n· 甲", p.history().first().content)
        assertEquals("system", p.history().first().role)
        assertTrue(p.hasDigest())
    }

    @Test
    fun `清空发生在压缩之前时 之后的摘要仍然有效`() {
        // 先过期清空（含摘要），用户又聊了一轮并触发压缩 —— 新摘要不能被旧清空误伤
        val p = projectionOf(
            *turn(1, "甲", "壹"),
            CompactionSummary(turn = null, shadowedSeqs = listOf(2L, 3L), text = "旧摘要"),
            VisibilityCut(turn = null, seqs = emptyList(), clearDigest = true, reason = "expire"),
            *turn(2, "乙", "贰"),
            CompactionSummary(turn = null, shadowedSeqs = listOf(8L, 9L), text = "新摘要"),
        )

        assertEquals("新摘要", p.digest)
        assertTrue(p.hasDigest())
    }

    // ═══════════════════ 成本可观测（用量与耗时）═══════════════════

    @Test
    fun `耗时由 TurnStart 与 TurnEnd 的时间差算出`() {
        val p = SessionProjection(
            listOf(
                SessionRecord(1, 1_000L, TurnStart(1)),
                SessionRecord(2, 1_100L, UserMessage(1, text = "问")),
                SessionRecord(3, 5_000L, AssistantMessage(1, text = "答")),
                SessionRecord(
                    4, 7_500L,
                    TurnEnd(1, reason = TurnEndReason.COMPLETED, promptTokens = 10, completionTokens = 2),
                ),
            )
        )
        val stat = p.lastTurnStat()!!
        assertEquals(6_500L, stat.elapsedMs)
        assertEquals(10, stat.promptTokens)
        assertEquals(2, stat.completionTokens)
        assertEquals("回复字符数用于拿不到 token 时的估算口径", "答".length, stat.replyChars)
    }

    /** 进程在轮中被杀 ⇒ 只有 TurnStart 没有 TurnEnd ⇒ 这一轮不可统计（也就无处可编） */
    @Test
    fun `没有 TurnEnd 的轮不出现在用量统计里`() {
        val p = projectionOf(TurnStart(1), UserMessage(1, text = "问"))
        assertTrue(p.turnStats().isEmpty())
        assertNull(p.lastTurnStat())
        assertNull("一次真实用量都没有 ⇒ 累计为未知，不是 0", p.usageTotals())
    }

    @Test
    fun `累计用量只算真给过用量的轮`() {
        val p = projectionOf(
            TurnStart(1),
            UserMessage(1, text = "一"),
            AssistantMessage(1, text = "壹"),
            TurnEnd(1, reason = TurnEndReason.COMPLETED, promptTokens = 300, completionTokens = 20, modelCalls = 2),
            TurnStart(2),
            UserMessage(2, text = "二"),
            AssistantMessage(2, text = "贰"),
            // 第二轮服务端没给 usage：不该被算进累计，但也不该让累计消失
            TurnEnd(2, reason = TurnEndReason.COMPLETED),
        )
        val totals = p.usageTotals()!!
        assertEquals(300, totals.promptTokens)
        assertEquals(20, totals.completionTokens)
        assertEquals("2 轮里只有 1 轮贡献了数字，必须一并回报", 1, totals.turnsWithUsage)
    }
}
