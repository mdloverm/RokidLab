package com.rokidlab.phone.ai.session

/**
 * 把事件流折叠成**给界面看的结构化轨迹**（方案 §4.3.5 —— 整个方案的"收口"：
 * 事件流到这里才真正对用户可见）。
 *
 * ★ 与 [SessionDump] 的分工（同源不同消费者，**刻意不合并**）：
 *  - [SessionDump] 产出**给模型读的连贯叙述**（"#12 轮3 调用工具 get_weather({...})"），
 *    要求紧凑、单行、可续读；
 *  - 本对象产出**给界面分组展开的条目**（一行标题 + 可展开的原文 + 来源分类），
 *    要求能扫、能筛、能看细节。
 *  合并成一份的诱惑很真实，但两者要的形状不同 —— 硬合并的结果是两边都不好用，
 *  而且以后改任一侧的文案都会牵动另一侧的测试。
 *
 * ★ 为什么值得做：改造前用户能看到的只有"我发了什么、它回了什么"，中间的**依据与动作**
 *   （注入了哪些记忆/资料、调了哪个工具、参数和返回是什么、哪几轮被压缩了）全部不可见。
 *  于是"它怎么知道这个""它到底做了什么"只能靠猜 —— 这是信任问题的根源，不是体验问题。
 */
internal object SessionTrace {

    /** 轨迹条目的来源分类（界面按它筛选） */
    enum class Category(val wire: String) {
        /** 用户 / AI 的正文消息 */
        MESSAGE("message"),

        /** 工具调用与返回 */
        TOOL("tool"),

        /** 注入到提示词的上下文（长期记忆 / 知识库 / 技能清单 / 续做任务 / 预算说明） */
        INJECT("inject"),

        /** 压缩（滚动摘要） */
        COMPACTION("compaction"),

        /** 可见性裁剪（过期清空 / 丢弃一轮 / 手动清空） */
        CUT("cut"),

        /** 失败尝试与轮次边界 */
        LIFECYCLE("lifecycle"),
    }

    /**
     * 一条轨迹。
     *
     * @param title 一行标题（界面主文案，已压平换行并截断）
     * @param detail 可展开的原文（注入内容 / 工具参数与返回 / 失败详情）；null = 没有更多可看
     */
    data class Item(
        val seq: Long,
        val turn: Int?,
        val ts: Long,
        val category: Category,
        val title: String,
        val detail: String?,
    )

    /** 顶部一行摘要（用户先看规模，再决定要不要展开） */
    data class Summary(
        val turns: Int,
        val toolCalls: Int,
        val injects: Int,
        val compactions: Int,
    )

    /** 标题里正文的截断长度（详情里有全文） */
    private const val TITLE_CLIP = 60

    /** 详情里单条内容的截断长度（界面不该因为一条超长工具返回而卡住） */
    private const val DETAIL_CLIP = 4000

    /**
     * 折叠成轨迹条目（**时间正序**，保留最后 [limit] 条）。
     *
     * 取"最后 N 条"而不是"前 N 条"：用户点开轨迹视图时想看的是**刚刚发生了什么**，
     * 一个几千条事件的会话从第一天开始列没有任何意义。
     */
    fun items(records: List<SessionRecord>, limit: Int = 300): List<Item> {
        if (records.isEmpty()) return emptyList()
        val elapsedByTurn = records.let { SessionProjection(it).turnStats().associateBy({ s -> s.turn }, { s -> s }) }
        val out = records.map { r -> toItem(r, elapsedByTurn[r.event.turn ?: -1]) }
        return if (out.size <= limit) out else out.subList(out.size - limit, out.size)
    }

    /** 规模摘要（一眼看出"这次对话有多重"） */
    fun summary(records: List<SessionRecord>): Summary {
        var turns = 0
        var tools = 0
        var injects = 0
        var compactions = 0
        for (r in records) {
            when (r.event) {
                is TurnStart -> turns++
                is ToolCall -> tools++
                is ContextInject -> injects++
                is CompactionSummary -> compactions++
                else -> Unit
            }
        }
        return Summary(turns, tools, injects, compactions)
    }

    // ═══════════════════════ 内部 ═══════════════════════

    private fun toItem(r: SessionRecord, stat: TurnStat?): Item = clipDetail(rawItem(r, stat))

    private fun rawItem(r: SessionRecord, stat: TurnStat?): Item {
        val e = r.event
        return when (e) {
            is TurnStart -> Item(r.seq, e.turn, r.ts, Category.LIFECYCLE, "开始（${e.source.wire}）", null)

            is UserMessage -> Item(r.seq, e.turn, r.ts, Category.MESSAGE, "你：${oneLine(e.text, TITLE_CLIP)}", e.text)

            is AssistantMessage -> Item(r.seq, e.turn, r.ts, Category.MESSAGE, "乐奇：${oneLine(e.text, TITLE_CLIP)}", e.text)

            is ToolCall -> Item(
                r.seq, e.turn, r.ts, Category.TOOL,
                "调用 ${e.name}",
                e.args.takeIf { it.isNotBlank() && it != "{}" },
            )

            is ToolResult -> Item(
                r.seq, e.turn, r.ts, Category.TOOL,
                "返回 ${e.name}${if (e.truncated) "（原文已截断）" else ""}",
                e.content,
            )

            is ContextInject -> Item(
                r.seq, e.turn, r.ts, Category.INJECT,
                "注入 ${e.origin}（${e.content.length} 字${if (e.truncated) "，已截断" else ""}）",
                e.content,
            )

            is AssistantAttempt -> Item(
                r.seq, e.turn, r.ts, Category.LIFECYCLE,
                "第 ${e.turn} 轮有一次失败尝试（${e.outcome.wire}）",
                e.detail,
            )

            is TurnEnd -> Item(
                r.seq, e.turn, r.ts, Category.LIFECYCLE,
                "结束（${e.reason.wire}）",
                endDetail(e, stat),
            )

            is CompactionStart -> Item(r.seq, null, r.ts, Category.COMPACTION, "压缩开始（${e.trigger}）", null)

            is CompactionSummary -> Item(
                r.seq, null, r.ts, Category.COMPACTION,
                "压缩：把 ${e.shadowedSeqs.size} 条早期事件折进摘要",
                e.text,
            )

            is CompactionEnd -> Item(
                r.seq, null, r.ts, Category.COMPACTION,
                "压缩结束" + (e.error?.let { "（失败）" } ?: ""),
                e.error,
            )

            is VisibilityCut -> Item(
                r.seq, null, r.ts, Category.CUT,
                "作废 ${e.seqs.size} 条（${e.reason}）—— 不再进模型上下文，原文仍在",
                null,
            )
        }
    }

    /** 结束行的详情：把这一轮的代价摊开（与上下文面板同源，都是事件流的派生量） */
    private fun endDetail(e: TurnEnd, stat: TurnStat?): String? {
        val parts = ArrayList<String>(4)
        e.detail?.takeIf { it.isNotBlank() }?.let { parts.add("原因：$it") }
        stat?.modelCalls?.let { parts.add("模型调用 $it 次") }
        if (e.promptTokens != null || e.completionTokens != null) {
            parts.add("输入 ${e.promptTokens ?: 0} / 输出 ${e.completionTokens ?: 0} token")
        }
        stat?.elapsedMs?.let { parts.add("耗时 ${"%.1f".format(it / 1000.0)} 秒") }
        return parts.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    private fun oneLine(s: String, max: Int): String {
        val flat = s.replace('\n', ' ').replace(Regex("\\s+"), " ").trim()
        return if (flat.length > max) flat.take(max) + "…" else flat
    }

    /** 详情统一截断（界面不该被一条超长工具返回卡住） */
    private fun clipDetail(i: Item): Item {
        val d = i.detail ?: return i
        return if (d.length <= DETAIL_CLIP) i else i.copy(detail = d.take(DETAIL_CLIP) + "…（已截断）")
    }
}
