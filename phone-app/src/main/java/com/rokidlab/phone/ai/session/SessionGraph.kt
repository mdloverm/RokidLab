package com.rokidlab.phone.ai.session

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 会话事件流的**节点连接图**（会话记录视图的数据层，纯函数、可单测）。
 *
 * ★ 为什么是"图"而不是"列表"：事件流里有两类**不是时间顺序**的关系，列表表达不了 ——
 *  1. **工具配对**：`tool_call` 与它的 `tool_result` 由 `callId` 配对，中间可能隔着别的调用
 *     （并发执行时落盘顺序不等于调用顺序）。只看时间顺序，"哪个结果属于哪次调用"要靠猜；
 *     有配对连线，这件事在图上是一眼可见的。
 *  2. **可见性**：被压缩覆盖（[CompactionSummary.shadowedSeqs]）或作废（[VisibilityCut.seqs]）的事件
 *     **仍在图里**（只追加不变式），但已经不进模型上下文 —— 图上必须能区分，
 *     否则"AI 为什么忘了这段"和"这段到底发生过没有"会混成一件事。
 *
 * ★ 布局口径（**刻意选竖向节点链**，不是力导向二维布局）：事件流本身是一条**线性路径**，
 *   力导向布局只会把一条直线随机摊开、还失去"发生的先后"这个最重要的信息；
 *   而手机屏幕是窄的竖屏。所以主脊是竖向时间线，工具调用/结果作为**分支**挂在主脊上，
 *   配对关系用回连（elbow）画出来。它仍是严格意义的节点连接图（路径图 + 配对边）。
 */
internal object SessionGraph {

    /** 节点类型（决定颜色/图标；由事件类型退化而来，不含"未知"—— 未知类型根本进不了流） */
    enum class Kind {
        /** 第 N 轮开始/结束这类生命周期事件 */
        LIFECYCLE,

        /** 用户消息（模型可见） */
        USER,

        /** AI 回复（模型可见） */
        ASSISTANT,

        /** 一次失败/被抢占的尝试（不进模型历史） */
        ATTEMPT,

        /** 模型请求调用工具 */
        TOOL_CALL,

        /** 工具返回 */
        TOOL_RESULT,

        /** 注入到提示词的上下文（记忆/知识库/技能/预算…） */
        CONTEXT,

        /** 压缩（开始/摘要/结束） */
        COMPACTION,

        /** 事件被声明为不进模型上下文（过期清空 / 丢弃一轮） */
        CUT,
    }

    /** 连线类型 */
    enum class EdgeKind {
        /** 时间顺序：上一条 → 这一条（主脊） */
        NEXT,

        /** 工具配对：`tool_call` → 它的 `tool_result`（括号连线，图上画成分支） */
        TOOL_PAIR,
    }

    /**
     * 一个节点。
     *
     * @param title 图上显示的一行（已压平空白并截断）
     * @param detail 详情（点击展开 / 复制用）；工具调用是参数，工具结果是返回内容
     * @param callId 工具配对 id（仅 TOOL_CALL / TOOL_RESULT 非空）
     * @param excludedFromContext 这条记录是否**已被排除出模型上下文**（被压缩覆盖 / 作废）。
     *   ⚠️ 判据刻意**不是** `SessionEvent.modelVisible` —— 那个说的是"能不能变成一条聊天消息"，
     *   于 `TurnStart`/`ToolCall`/`ContextInject` 全是 false，而它们确实在请求里
     *   （工具调用是 tool 消息、注入在 system 里）。照它判会把图上**除消息以外的每个节点**
     *   都标成"不进上下文"（一片灰 + 红色标注），那既错误又掩盖了真正被排除的那几条。
     */
    data class Node(
        val seq: Long,
        val ts: Long,
        val turn: Int?,
        val kind: Kind,
        val title: String,
        val detail: String,
        val callId: String? = null,
        val excludedFromContext: Boolean = false,
    ) {
        /** 该节点是否代表一条**用户/助手消息**（决定能否编辑/删除 —— 它们与 UI 消息一一对应） */
        val isMessage: Boolean get() = kind == Kind.USER || kind == Kind.ASSISTANT

        /** 是否属于用户（用于编辑重发的方向判断） */
        val isUserMessage: Boolean get() = kind == Kind.USER
    }

    data class Edge(val from: Long, val to: Long, val kind: EdgeKind)

    /**
     * 图。
     *
     * @param shadowedCount 被压缩覆盖的节点数（"AI 忘了这段"的原因之一）
     * @param cutCount 被作废的节点数（原因之二）
     */
    data class Graph(
        val nodes: List<Node>,
        val edges: List<Edge>,
        val shadowedCount: Int,
        val cutCount: Int,
    ) {
        val toolPairCount: Int get() = edges.count { it.kind == EdgeKind.TOOL_PAIR }

        /** 有调用、无结果的工具调用（崩溃留下的痕迹） */
        val orphanCalls: List<Node>
            get() {
                val answered = nodes.filter { it.kind == Kind.TOOL_RESULT }
                    .mapNotNull { it.callId }
                    .toSet()
                return nodes.filter { it.kind == Kind.TOOL_CALL && it.callId != null && it.callId !in answered }
            }

        /** 关键词过滤（标题 + 详情，忽略大小写）；空串返回原图 */
        fun filter(keyword: String): Graph {
            val k = keyword.trim()
            if (k.isEmpty()) return this
            val hit = nodes.filter {
                it.title.contains(k, ignoreCase = true) || it.detail.contains(k, ignoreCase = true)
            }
            val keep = hit.map { it.seq }.toSet()
            // 边只保留两端都在的（过滤后画一条指向不存在节点的线会误导）
            return copy(nodes = hit, edges = edges.filter { it.from in keep && it.to in keep })
        }
    }

    // ═══════════════════════ 构建 ═══════════════════════

    fun build(records: List<SessionRecord>): Graph {
        // 可见性：被压缩覆盖的 + 被作废的。两者都**不删行**，只是不再进模型上下文。
        val shadowed = HashSet<Long>()
        val cut = HashSet<Long>()
        records.forEach { r ->
            when (val e = r.event) {
                is CompactionSummary -> shadowed.addAll(e.shadowedSeqs)
                is VisibilityCut -> cut.addAll(e.seqs)
                else -> Unit
            }
        }

        val nodes = records.map { r -> toNode(r, r.seq in shadowed, r.seq in cut) }

        val edges = ArrayList<Edge>(nodes.size)
        nodes.zipWithNext { a, b -> edges.add(Edge(a.seq, b.seq, EdgeKind.NEXT)) }
        // 工具配对：call → result（按 callId 找第一条匹配的结果；重放/重试可能产生多条，
        // 只连第一条 —— 连多条会让图里出现一团交叉线，反而看不出配对）
        val resultByCall = HashMap<String, Long>()
        nodes.forEach { n ->
            if (n.kind == Kind.TOOL_RESULT && n.callId != null) resultByCall.putIfAbsent(n.callId, n.seq)
        }
        nodes.forEach { n ->
            if (n.kind == Kind.TOOL_CALL && n.callId != null) {
                resultByCall[n.callId]?.let { edges.add(Edge(n.seq, it, EdgeKind.TOOL_PAIR)) }
            }
        }

        return Graph(nodes, edges, shadowed.size, cut.size)
    }

    private fun toNode(r: SessionRecord, shadowed: Boolean, cut: Boolean): Node {
        val e = r.event
        val kind: Kind
        val title: String
        val detail: String
        when (e) {
            is TurnStart -> {
                kind = Kind.LIFECYCLE
                title = "第 ${e.turn} 轮开始（${sourceLabel(e.source)}）"
                detail = ""
            }

            is TurnEnd -> {
                kind = Kind.LIFECYCLE
                val cost = buildString {
                    if (e.promptTokens != null || e.completionTokens != null) {
                        append("输入 ").append(e.promptTokens ?: "?").append(" / 输出 ")
                            .append(e.completionTokens ?: "?")
                    }
                    e.modelCalls?.let { if (isNotEmpty()) append(" · "); append("$it 次模型调用") }
                    if (isEmpty()) append("用量未知（服务端未返回）")
                }
                title = "第 ${e.turn} 轮结束（${reasonLabel(e.reason)}）"
                detail = cost + (e.detail?.let { "\n$it" } ?: "")
            }

            is UserMessage -> {
                kind = Kind.USER
                title = "你：" + oneLine(e.text)
                detail = e.text
            }

            is AssistantMessage -> {
                kind = Kind.ASSISTANT
                title = "乐奇：" + oneLine(e.text)
                detail = e.text
            }

            is AssistantAttempt -> {
                kind = Kind.ATTEMPT
                title = "尝试失败（${e.outcome.wire}）"
                detail = e.detail.orEmpty()
            }

            is ToolCall -> {
                kind = Kind.TOOL_CALL
                title = "调用 ${e.name}"
                detail = e.args
            }

            is ToolResult -> {
                kind = Kind.TOOL_RESULT
                title = "${e.name} 返回" + if (e.truncated) "（已截断）" else ""
                detail = e.content
            }

            is ContextInject -> {
                kind = Kind.CONTEXT
                title = "注入（${e.origin}）" + if (e.truncated) "（已截断）" else ""
                detail = e.content
            }

            is CompactionStart -> {
                kind = Kind.COMPACTION
                title = "开始压缩（${e.trigger}）"
                detail = ""
            }

            is CompactionSummary -> {
                kind = Kind.COMPACTION
                title = "压缩摘要（覆盖 ${e.shadowedSeqs.size} 条" +
                    if (e.turnsCompressed > 0) "，$e.turnsCompressed 轮" else "" + "）"
                detail = e.text
            }

            is CompactionEnd -> {
                kind = Kind.COMPACTION
                title = if (e.error == null) "压缩完成" else "压缩失败"
                detail = e.error.orEmpty()
            }

            is VisibilityCut -> {
                kind = Kind.CUT
                title = "作废 ${e.seqs.size} 条（${cutReasonLabel(e.reason)}）"
                detail = if (e.clearDigest) "含滚动摘要一起清空" else "滚动摘要保留"
            }
        }
        return Node(
            seq = r.seq,
            ts = r.ts,
            turn = e.turn,
            kind = kind,
            title = title,
            detail = detail,
            callId = when (e) {
                is ToolCall -> e.callId
                is ToolResult -> e.callId
                else -> null
            },
            excludedFromContext = shadowed || cut,
        )
    }

    // ═══════════════════════ Markdown 导出 ═══════════════════════

    /**
     * 导出成 Markdown（用户要求的「导出记忆为 md」）。
     *
     * 口径：**按轮次组织**，而不是按 seq 平铺 —— 人读的是"这一轮发生了什么"，
     * seq 是我们内部的编号。非轮次事件（压缩/作废）单独归到末尾一节，
     * 因为它们描述的是"整段历史怎么被裁剪的"，不属于某一轮。
     *
     * ⚠️ 拿不到的用量写"未知"，**不写 0**（理由同 [TurnEnd] 的注释）。
     */
    fun toMarkdown(
        records: List<SessionRecord>,
        title: String,
        exportedAt: Long = System.currentTimeMillis(),
    ): String {
        val graph = build(records)
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val sb = StringBuilder()
        sb.append("# ").append(title.ifBlank { "对话记录" }).append("\n\n")

        val turns = graph.nodes.filter { it.kind == Kind.LIFECYCLE && it.turn != null }
            .mapNotNull { it.turn }.distinct().sorted()
        val toolCalls = graph.nodes.count { it.kind == Kind.TOOL_CALL }
        sb.append("> 导出时间 ").append(fmt.format(Date(exportedAt)))
            .append(" · 共 ").append(turns.size).append(" 轮 · ")
            .append(toolCalls).append(" 次工具调用 · ")
            .append(records.size).append(" 条事件\n")
        if (graph.shadowedCount > 0) {
            sb.append("> 其中 ").append(graph.shadowedCount)
                .append(" 条已被压缩摘要覆盖（模型看不到，但记录仍在）\n")
        }
        if (graph.cutCount > 0) {
            sb.append("> 其中 ").append(graph.cutCount).append(" 条已作废（不计入上下文）\n")
        }
        sb.append('\n')

        val turnScoped = graph.nodes.filter { it.turn != null }
        turnScoped.groupBy { it.turn!! }.toSortedMap().forEach { (turn, nodes) ->
            val startNode = nodes.firstOrNull { it.kind == Kind.LIFECYCLE && it.title.startsWith("第 $turn 轮开始") }
            sb.append("## 第 ").append(turn).append(" 轮")
            startNode?.let { sb.append("　").append(fmt.format(Date(it.ts))) }
            sb.append("\n\n")
            appendTurnBody(sb, nodes)
            sb.append('\n')
        }

        val globals = graph.nodes.filter { it.turn == null }
        if (globals.isNotEmpty()) {
            sb.append("## 历史裁剪与维护\n\n")
            globals.forEach { n ->
                sb.append("- [").append(n.seq).append("] ").append(n.title)
                if (n.detail.isNotBlank() && n.detail != "0") sb.append("：").append(oneLine(n.detail, 160))
                sb.append('\n')
            }
            sb.append('\n')
        }

        val orphans = graph.orphanCalls
        if (orphans.isNotEmpty()) {
            sb.append("## ⚠️ 未收到结果的工具调用\n\n")
            orphans.forEach { sb.append("- [").append(it.seq).append("] ").append(it.title).append('\n') }
            sb.append('\n')
        }
        return sb.toString().trimEnd() + "\n"
    }

    private fun appendTurnBody(sb: StringBuilder, nodes: List<Node>) {
        nodes.filter { it.kind == Kind.CONTEXT }.forEach { n ->
            sb.append("<details><summary>").append(n.title).append("</summary>\n\n")
            sb.append("```\n").append(n.detail.trim()).append("\n```\n\n</details>\n\n")
        }
        nodes.filter { it.kind == Kind.USER || it.kind == Kind.ASSISTANT }.forEach { n ->
            sb.append("**").append(if (n.kind == Kind.USER) "你" else "乐奇").append("**：")
                .append(n.detail.trim()).append("\n\n")
        }
        nodes.filter { it.kind == Kind.TOOL_CALL || it.kind == Kind.TOOL_RESULT }.forEach { n ->
            if (n.kind == Kind.TOOL_CALL) {
                sb.append("调用 `").append(n.title.removePrefix("调用 ")).append("`：\n\n")
                sb.append("```json\n").append(n.detail.trim().ifBlank { "{}" }).append("\n```\n\n")
            } else {
                sb.append("返回").append(if (n.title.contains("已截断")) "（已截断）" else "").append("：\n\n")
                sb.append("```\n").append(n.detail.trim()).append("\n```\n\n")
            }
        }
        nodes.filter { it.kind == Kind.ATTEMPT }.forEach { n ->
            sb.append("> ⚠️ ").append(n.title)
            if (n.detail.isNotBlank()) sb.append("：").append(oneLine(n.detail, 200))
            sb.append('\n')
        }
        nodes.filter { it.kind == Kind.LIFECYCLE && it.title.contains("轮结束") }.forEach { n ->
            sb.append("> ").append(n.title)
            if (n.detail.isNotBlank()) sb.append("　").append(oneLine(n.detail, 120))
            sb.append('\n')
        }
    }

    // ═══════════════════════ 文案 ═══════════════════════

    private fun sourceLabel(s: MessageSource): String = when (s) {
        MessageSource.TEXT -> "文字"
        MessageSource.VOICE -> "眼镜语音"
        MessageSource.IMAGE -> "拍照"
        MessageSource.UNKNOWN -> "未知来源"
    }

    private fun reasonLabel(r: TurnEndReason): String = when (r) {
        TurnEndReason.COMPLETED -> "完成"
        TurnEndReason.INTERRUPTED -> "被打断"
        TurnEndReason.FAILED -> "失败"
        TurnEndReason.BUDGET_EXHAUSTED -> "轮次预算用尽"
    }

    private fun cutReasonLabel(raw: String): String = when (raw) {
        "expire" -> "长时间无活动，视为新会话"
        "drop_last_turn" -> "重新生成 / 编辑重发"
        "manual_clear" -> "手动清空"
        else -> raw
    }

    /** 压平空白 + 截断（图里一行放得下） */
    private fun oneLine(text: String, max: Int = 60): String {
        val flat = text.replace('\n', ' ').replace(Regex("\\s+"), " ").trim()
        return if (flat.length > max) flat.take(max) + "…" else flat
    }
}
