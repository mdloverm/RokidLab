package com.rokidlab.phone.ai.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SessionGraph]（对话记录图数据层）单测。
 *
 * 为什么值得钉：这张图是"会话记录"这件事**唯一的结构化出口** —— 它同时承载三件容易做错的事：
 *  1. **工具配对**：`tool_call` 与 `tool_result` 由 `callId` 配对，落盘顺序 ≠ 调用顺序
 *     （并发执行时）。配对错了，用户看到的"执行过程"就是错的；
 *  2. **可见性**：被压缩覆盖 / 作废的事件**仍在图里**（事件流只追加），但已不进模型上下文 ——
 *     混在一起会让"AI 为什么忘了这段"和"这段到底发生过没有"变成同一件事；
 *  3. **导出**：导出的是给**人**看的文档，按轮次组织；拿不到的用量必须写"未知"而不是 0。
 */
class SessionGraphTest {

    private var seq = 0L
    private fun rec(event: SessionEvent, ts: Long = 1_000L * (++seq)): SessionRecord =
        SessionRecord(seq, ts, event)

    private fun turn(n: Int, q: String, a: String): List<SessionRecord> = listOf(
        rec(TurnStart(n)),
        rec(UserMessage(n, text = q)),
        rec(AssistantMessage(n, text = a)),
        rec(TurnEnd(n, reason = TurnEndReason.COMPLETED)),
    )

    // ── 节点类型与主脊 ──

    @Test
    fun `事件类型映射成节点类型`() {
        val records = listOf(
            rec(TurnStart(1)),
            rec(UserMessage(1, text = "问")),
            rec(ContextInject(1, origin = "memory", content = "记忆")),
            rec(ToolCall(1, callId = "c1", name = "get_weather", args = "{}")),
            rec(ToolResult(1, callId = "c1", name = "get_weather", content = "晴")),
            rec(AssistantMessage(1, text = "答")),
            rec(AssistantAttempt(1, outcome = AttemptOutcome.FAILED, detail = "400")),
            rec(TurnEnd(1, reason = TurnEndReason.COMPLETED)),
        )
        val kinds = SessionGraph.build(records).nodes.map { it.kind }
        assertEquals(
            listOf(
                SessionGraph.Kind.LIFECYCLE,
                SessionGraph.Kind.USER,
                SessionGraph.Kind.CONTEXT,
                SessionGraph.Kind.TOOL_CALL,
                SessionGraph.Kind.TOOL_RESULT,
                SessionGraph.Kind.ASSISTANT,
                SessionGraph.Kind.ATTEMPT,
                SessionGraph.Kind.LIFECYCLE,
            ),
            kinds,
        )
    }

    @Test
    fun `主脊把相邻节点依次连起来`() {
        val g = SessionGraph.build(turn(1, "问", "答"))
        val next = g.edges.filter { it.kind == SessionGraph.EdgeKind.NEXT }
        assertEquals("4 个节点应有 3 条主脊边", 3, next.size)
        assertEquals(g.nodes[0].seq, next[0].from)
        assertEquals(g.nodes[1].seq, next[0].to)
    }

    // ── 工具配对 ──

    @Test
    fun `工具调用与结果按 callId 配对，即使中间隔着别的调用`() {
        val records = listOf(
            rec(TurnStart(1)),
            rec(ToolCall(1, callId = "a", name = "t_a", args = "{}")),
            rec(ToolCall(1, callId = "b", name = "t_b", args = "{}")),
            // 先返回 b 再返回 a（并发执行的落盘顺序不等于调用顺序）
            rec(ToolResult(1, callId = "b", name = "t_b", content = "B")),
            rec(ToolResult(1, callId = "a", name = "t_a", content = "A")),
        )
        val g = SessionGraph.build(records)
        val pairs = g.edges.filter { it.kind == SessionGraph.EdgeKind.TOOL_PAIR }
        assertEquals(2, pairs.size)
        val callA = g.nodes.first { it.kind == SessionGraph.Kind.TOOL_CALL && it.callId == "a" }.seq
        val resA = g.nodes.first { it.kind == SessionGraph.Kind.TOOL_RESULT && it.callId == "a" }.seq
        assertTrue("a 的配对边必须连到 a 的结果，而不是先落盘的那个", pairs.any { it.from == callA && it.to == resA })
        assertTrue(g.orphanCalls.isEmpty())
    }

    @Test
    fun `有调用无结果时被识别为孤儿`() {
        val records = listOf(
            rec(TurnStart(1)),
            rec(ToolCall(1, callId = "x", name = "t_x", args = "{}")),
        )
        val g = SessionGraph.build(records)
        assertEquals(1, g.nodes.count { it.kind == SessionGraph.Kind.TOOL_CALL })
        assertEquals("崩溃留下的调用要能被指出", 1, g.orphanCalls.size)
        assertEquals(0, g.toolPairCount)
    }

    // ── 可见性（只追加不变式的可见面） ──

    @Test
    fun `被压缩覆盖与作废的节点仍在图里但标记为已排除出上下文`() {
        val t1 = turn(1, "一", "壹")
        val userSeq = t1[1].seq
        val assistantSeq = t1[2].seq
        val records = t1 + listOf(
            rec(CompactionSummary(turn = null, shadowedSeqs = listOf(userSeq, assistantSeq), text = "摘要")),
            rec(VisibilityCut(turn = null, seqs = listOf(assistantSeq), clearDigest = false, reason = "drop_last_turn")),
        )
        val g = SessionGraph.build(records)
        assertEquals("压缩/作废都不删行 —— 图里必须还能看到它们", 6, g.nodes.size)
        assertTrue("被压缩覆盖 ⇒ 已排除出上下文，但记录仍在", g.nodes.first { it.seq == userSeq }.excludedFromContext)
        assertTrue("作废同理", g.nodes.first { it.seq == assistantSeq }.excludedFromContext)
        assertEquals(2, g.shadowedCount)
        assertEquals(1, g.cutCount)
        // ⚠️ 没被碰到的节点**一律不算"已排除"** —— 包括 TurnStart 这类
        // `SessionEvent.modelVisible = false` 的事件：那个字段说的是"能不能变成聊天消息"，
        // 照它判会把图上除消息以外的每个节点都标灰（真实踩过的 bug）。
        assertFalse(
            "生命周期节点没有被排除出上下文",
            g.nodes.first { it.kind == SessionGraph.Kind.LIFECYCLE }.excludedFromContext,
        )
    }

    // ── 搜索 ──

    @Test
    fun `关键词过滤同时搜标题与详情，且不留下悬空边`() {
        val records = listOf(
            rec(TurnStart(1)),
            rec(UserMessage(1, text = "今天天气")),
            rec(ToolCall(1, callId = "c", name = "get_weather", args = "{\"city\":\"西安\"}")),
            rec(ToolResult(1, callId = "c", name = "get_weather", content = "晴 31 度")),
            rec(AssistantMessage(1, text = "西安今天晴")),
            rec(TurnEnd(1, reason = TurnEndReason.COMPLETED)),
        )
        val graph = SessionGraph.build(records)

        // 中文关键词命中消息正文
        val cn = graph.filter("天气")
        assertTrue("应命中用户消息", cn.nodes.any { it.kind == SessionGraph.Kind.USER })

        // 工具名（英文）命中工具调用与结果 —— 顺带说明一件事：
        // 搜中文「天气」**不会**命中 `get_weather`（标题里只有工具名，没有中文别名）。
        // 这是刻意的：图上显示什么就搜什么，不偷偷做一层语义映射。
        val tool = graph.filter("weather")
        assertTrue("应按名字命中工具调用", tool.nodes.count { it.kind == SessionGraph.Kind.TOOL_CALL } == 1)
        assertTrue("也应命中它的结果", tool.nodes.count { it.kind == SessionGraph.Kind.TOOL_RESULT } == 1)

        assertEquals("过滤后不该留下指向不存在节点的边", 0, tool.edges.count { e ->
            tool.nodes.none { it.seq == e.from } || tool.nodes.none { it.seq == e.to }
        })
        assertTrue("空关键词不过滤", graph.filter("").nodes.isNotEmpty())
        assertTrue(graph.filter("不存在的词").nodes.isEmpty())
    }

    // ── Markdown 导出 ──

    @Test
    fun `导出按轮次组织并带上工具调用与结果`() {
        val records = listOf(
            rec(TurnStart(1), ts = 1_700_000_000_000L),
            rec(UserMessage(1, text = "西安天气")),
            rec(ToolCall(1, callId = "c", name = "get_weather", args = "{\"city\":\"西安\"}")),
            rec(ToolResult(1, callId = "c", name = "get_weather", content = "晴 31 度")),
            rec(AssistantMessage(1, text = "今天晴，31 度")),
            rec(TurnEnd(1, reason = TurnEndReason.COMPLETED, promptTokens = 1000, completionTokens = 20, modelCalls = 2)),
        ) + turn(2, "那明天呢", "明天多云")
        val md = SessionGraph.toMarkdown(records, "测试会话", exportedAt = 1_700_000_100_000L)

        assertTrue("要有标题\n$md", md.startsWith("# 测试会话"))
        assertTrue("要有轮次小节", md.contains("## 第 1 轮"))
        assertTrue("要有第 2 轮", md.contains("## 第 2 轮"))
        assertTrue("用户话要落进去", md.contains("**你**：西安天气"))
        assertTrue("回答要落进去", md.contains("**乐奇**：今天晴，31 度"))
        assertTrue("工具名要落进去", md.contains("调用 `get_weather`"))
        assertTrue("工具返回要落进去", md.contains("晴 31 度"))
        assertTrue("用量要落进去", md.contains("输入 1000 / 输出 20"))
        assertTrue("统计行要落进去", md.contains("2 轮"))
    }

    @Test
    fun `拿不到用量时导出写未知而不是 0`() {
        val records = turn(1, "问", "答")
        val md = SessionGraph.toMarkdown(records, "t", exportedAt = 1_700_000_000_000L)
        assertTrue("不能出现「输入 0 / 输出 0」这种编造的数字\n$md", !md.contains("输入 0 / 输出 0"))
    }

    @Test
    fun `非轮次事件归到维护小节而不是某一轮`() {
        val records = turn(1, "一", "壹") + listOf(
            rec(CompactionSummary(turn = null, shadowedSeqs = listOf(2L), text = "更早对话摘要", turnsCompressed = 1)),
            rec(VisibilityCut(turn = null, seqs = listOf(3L), clearDigest = true, reason = "expire")),
        )
        val md = SessionGraph.toMarkdown(records, "t", exportedAt = 1_700_000_000_000L)
        assertTrue(md.contains("## 历史裁剪与维护"))
        assertTrue("要写明作废原因（人能读懂的那种）", md.contains("长时间无活动"))
        assertTrue("要写明压缩覆盖了多少条", md.contains("被压缩摘要覆盖"))
    }
}
