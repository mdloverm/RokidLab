package com.rokidlab.phone.ai.session

import com.rokidlab.phone.ai.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **会话回放测试**（golden fixture replay，对齐 Muse Code 的 deterministic replay in CI）。
 *
 * ## 与 `SessionProjectionTest` 的分工（不要合并）
 *
 * [SessionProjectionTest] 是**单特性隔离测**：每条用例只开一个机制、只断言一个性质
 * （压缩只测压缩、作废只测作废）。那是对的，但它覆盖不到**多条机制同时作用**的真实会话 ——
 * 而主循环每轮面对的恰恰是"压过 + 打断过 + 删过 + 授权过"的混合状态。
 *
 * 本类补的就是那个交叉面：**一份真实形状的录制 fixture，一次性断言整条投影链**。
 *
 * ## 为什么用文本 fixture 而不是代码构造
 *
 * 代码构造（`store.appendUserMessage(...)` 一串调用）有两个缺点：
 *  1. **写不出"历史遗留状态"**：比如"压缩发生在删除之前"这种顺序，用 API 很难摆出来，
 *     而真实会话里它天天发生；
 *  2. **不可 review**：改动时看到的是 diff 后的一堆 Kotlin 语句，看不出"会话变成了什么样"。
 *
 * 文本 jsonl 两边都解决：它是**落盘格式本身**（所以走的是真实解析路径，不是模拟），
 * 又能在 code review 里直接读懂、甚至手改。
 *
 * ## 断言分两层（缺一不可）
 *
 * - **语义不变式**：用人能读懂的话钉住关键性质（用户原话还在吗、会不会出现半轮）。
 *   它解释"为什么"这条快照是对的。
 * - **快照**：整个投影输出的确定性渲染。它抓"无声漂移"——
 *   单测全绿但整体行为变了的那种改动。
 *
 * ⚠️ **快照单独用是有害的**（它只说"变了"不说"变错了"），所以本类两条都必须有。
 * 快照红时**先读语义断言**：语义断言仍绿 → 多半是预期内的格式变化，更新快照；
 * 语义断言也红 → 那是真回归，别急着更新快照。
 *
 * ⚠️ fixture 加字段/加事件类型时，**同步更新本类的快照与不变式**，
 * 否则这里会变成"钉住旧行为"的阻力（旧测试红着是诚实信号，要在注释里写清为何改钉）。
 */
class SessionReplayTest {

    private companion object {
        /** 与 fixture 文件同名（`src/test/resources/sessions/` 下） */
        const val MIXED = "mixed_session.jsonl"

        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"
        const val ROLE_SYSTEM = "system"
    }

    // ═══════════════════════ 装载与投影 ═══════════════════════

    private fun loadRaw(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/sessions/$name")) {
            "fixture 缺失（应在 src/test/resources/sessions/$name）"
        }.bufferedReader(Charsets.UTF_8).use { it.readText() }

    /**
     * 从 fixture 文本回放成事件流。
     *
     * ⚠️ 用 `SessionLog.parse`（**生产解析路径**）而不是自己 `JSONObject` 逐行读 ——
     *   否则测的是"我理解的格式"而不是"程序认识的格式"，格式漂移会被同时藏在两边。
     */
    private fun replay(name: String): List<SessionRecord> =
        SessionLog(File("unused-replay.jsonl")).parse(loadRaw(name))

    private fun projectionOf(name: String) = SessionProjection(replay(name))

    /**
     * **输入完整性检查**（每条用例的前置）。
     *
     * 📌 先验证输入再验证输出：fixture 里一个字段名写错会让该行**被静默跳过**
     *   （`decode` 的设计就是"看不懂就跳过"，见 `SessionLog` 的三条不变式），
     *   于是下游断言报的是"历史少了一条"—— 一个与真实原因毫无关系的症状。
     *   这条把"输入不完整"变成一个直接、可归因的失败。
     */
    private fun assertFixtureFullyParsed(name: String) {
        val raw = loadRaw(name)
        val nonBlank = raw.lines().count { it.isNotBlank() }
        val parsed = replay(name).size
        assertEquals(
            "fixture $name 有 $nonBlank 行但只解析出 $parsed 条 —— " +
                "多半是某行的字段名/类型写错被静默跳过（decode 会跳过看不懂的行）",
            nonBlank,
            parsed,
        )
    }

    /** 投影渲染成人类可读文本（快照用；diff 要能直接看懂） */
    private fun render(history: List<ChatMessage>): String = history.joinToString("\n") { m ->
        val trace = m.toolTrace.takeIf { it.isNotEmpty() }
            ?.joinToString(prefix = " <<", postfix = ">>") { it.substringBefore('(') }
            .orEmpty()
        "${m.role}: ${m.content.replace('\n', '⏎')}$trace"
    }

    // ═══════════════════════ A. 语义不变式 ═══════════════════════

    @Test
    fun `A1 fixture 完整解析无静默跳行`() {
        assertFixtureFullyParsed(MIXED)
    }

    @Test
    fun `A2 历史严格 user-assistant 交替且以 user 开头`() {
        // 这是「请求协议合法」的最低要求：部分服务端遇到连续两条 user 直接 400。
        val history = projectionOf(MIXED).history()
        val talk = history.filter { it.role != ROLE_SYSTEM }
        assertTrue("历史不该为空", talk.isNotEmpty())
        assertEquals("第一条对话消息必须是 user", ROLE_USER, talk.first().role)
        talk.forEachIndexed { i, msg ->
            val expected = if (i % 2 == 0) ROLE_USER else ROLE_ASSISTANT
            assertEquals(
                "第 ${i + 1} 条应为 $expected，实际 ${msg.role}（消息序列：${talk.map { it.role }}）",
                expected,
                msg.role,
            )
        }
        assertEquals("对话消息条数必须为偶数（u/a 成对）", 0, talk.size % 2)
    }

    @Test
    fun `A3 被用户删掉的轮完全消失但事件仍在文件里`() {
        // fixture 的轮 6（「你刚才说错了，重来」）被 VisibilityCut(reason=drop_last_turn) 作废。
        // 语义：它不该出现在模型上下文里（否则模型会看到自己上一版答案）。
        val p = projectionOf(MIXED)
        assertFalse(
            "被删轮的内容不得进入模型历史",
            p.history().any { it.content.contains("你刚才说错了") },
        )
        // ★ 但事件**必须在流里** —— "作废"是声明，不是删除（只追加不变式）。
        //   这一条同时守住"查得到"与"模型看不到"两件事（`SessionProjection` 的类注释）。
        //   直接在**解析出的记录**上找（投影的 records 是 private，且这里要的就是"全部"）
        assertTrue(
            "作废只是声明，原文必须仍在事件流里（否则『查不到前面聊了什么』）",
            replay(MIXED).any { it.event.let { e -> e is UserMessage && e.text.contains("你刚才说错了") } },
        )
    }

    @Test
    fun `A4 压缩过的轮不在历史里但摘要置顶且保留要点`() {
        // 轮 1~3 的 6 条消息被 CompactionSummary 覆盖。
        val p = projectionOf(MIXED)
        val history = p.history()
        assertEquals("摘要必须在最前（置顶）", ROLE_SYSTEM, history.first().role)
        assertTrue("置顶摘要应说明这是更早对话", history.first().content.contains("更早对话摘要"))
        // 覆盖掉的正文不得出现（省 token 的意义所在）
        assertFalse(
            "被压缩的原文不得留在历史里",
            history.any { it.content.contains("北京今天晴，18 到 26 度") },
        )
        // ★ 但**要点必须还在**（这是 2026-10-09 修过的累积性遗忘的反面）：
        //   压缩是换形态不是丢信息。摘要里漏了要点 = 模型永久失忆。
        val digest = history.first().content
        assertTrue("摘要须含天气结论：$digest", digest.contains("小雨"))
        assertTrue("摘要须含已执行的删除动作：$digest", digest.contains("旧报告"))
        assertTrue("摘要须含用户偏好：$digest", digest.contains("周杰伦"))
    }

    @Test
    fun `A5 被打断的轮保留用户原话与半截正文`() {
        // fixture 的轮 4：带工具的长任务被打断。
        // 2026-10-09 修的那条：原先整轮作废 ⇒ 用户说「继续」时模型没有指代对象。
        val history = projectionOf(MIXED).history()
        val idx = history.indexOfFirst { it.content.contains("整理成一个 markdown 文件") }
        assertTrue("被打断轮的用户原话必须保留（否则『继续』失去指代对象）", idx >= 0)
        assertEquals(
            "原话之后必须紧跟 assistant（半截或占位），不能留半轮",
            ROLE_ASSISTANT,
            history[idx + 1].role,
        )
        assertTrue(
            "半截正文必须落盘：模型要知道自己刚才说到哪",
            history[idx + 1].content.contains("前两段已经整理好"),
        )
    }

    @Test
    fun `A6 失败轮用占位 assistant 兜住 不留半轮`() {
        // fixture 的轮 5：attempt(timeout) + failed，一个字都没产出。
        val history = projectionOf(MIXED).history()
        val idx = history.indexOfFirst { it.content.contains("帮我查下明天的日程") }
        assertTrue("失败轮的用户原话同样要保留", idx >= 0)
        val reply = history[idx + 1]
        assertEquals(ROLE_ASSISTANT, reply.role)
        // ⚠️ 占位文本的**措辞本身是安全边界**：不能写"生成失败"，否则模型下一轮
        //   会以为自己说错了什么而道歉（凭空捏造上下文）。
        assertTrue(
            "占位措辞必须中性（不能是『生成失败』这类会让模型自我怀疑的表述）：${reply.content}",
            reply.content.contains("被中断") && !reply.content.contains("生成失败"),
        )
    }

    @Test
    fun `A7 授权留痕不进模型上下文但仍在事件流里`() {
        // 轮 3 的 delete_file 有一次已授权声明（authorizationSink 写入）。
        val records = replay(MIXED)
        val intents = records.filter { it.event is SideEffectIntent }
        assertEquals("fixture 应含 1 条授权留痕", 1, intents.size)
        assertEquals(
            "授权来源要能区分『用户点头』与『规则放行』",
            AuthorizationSource.USER_APPROVED,
            (intents[0].event as SideEffectIntent).source,
        )
        // ★ 审计事实不进模型上下文：塞进 messages 会让每轮白烧 token，
        //   且模型不需要知道"上次是谁点头的"。
        assertFalse(
            "授权留痕不得出现在模型历史里",
            projectionOf(MIXED).history().any { it.content.contains("delete_file") },
        )
    }

    @Test
    fun `A8 历史与 visibleRecords 一一对应`() {
        // 这是压缩接线的**契约**：引擎按"压缩后还剩几条消息"反推哪些 seq 被覆盖，
        // 若投影多产生或少产生消息，压缩就会声明覆盖错的 seq。
        val p = projectionOf(MIXED)
        val talk = p.history().filter { it.role != ROLE_SYSTEM }
        assertEquals(
            "可见记录数必须与（摘要之外的）历史条数一一对应",
            p.visibleRecords().size,
            talk.size,
        )
    }

    @Test
    fun `A9 工具轨迹指向本轮实际调用（含被作废的轨迹这一已知不一致）`() {
        val history = projectionOf(MIXED).history()
        val idx = history.indexOfFirst { it.content.contains("后天小雨") }
        val trace = history[idx].toolTrace
        assertEquals(1, trace.size)
        assertTrue(
            "轨迹格式为 name(args)，与改造前 ChatMessage 逐字一致：$trace",
            trace[0].startsWith("get_weather("),
        )

        // ⚠️ **已记录的真实不一致**（不是本测试要保护的契约，而是待处理项）：
        //   轮 4 的 ToolCall 被 VisibilityCut 作废（工具轨迹"不算数"），
        //   但 `traceByTurn` 是从**全部 events**派生的，不受 hiddenSeqs 影响 ⇒
        //   作废轮的工具名仍出现在轨迹里。
        //   影响面：轨迹只用于本地回溯展示、不注入模型上下文，所以目前无害；
        //   但语义上矛盾（声明"这轮工具不算数"却仍在展示）。
        //   📌 这里**显式记录当前行为**而不是断言"它是对的" —— 将来修的时候这条会红，
        //      看到注释就知道是有意改的，不是回归。
        val interrupted = history.first { it.content.contains("前两段已经整理好") }
        assertTrue(
            "当前行为：被作废轮的工具轨迹仍被派生出（见本测试注释的待处理项）",
            interrupted.toolTrace.any { it.startsWith("save_code_file(") },
        )
    }

    // ═══════════════════════ B. 快照（防无声漂移） ═══════════════════════

    @Test
    fun `B1 整条投影快照`() {
        // ⚠️ 红之前**先读 A 组**：A 全绿而这里红 = 预期内的形态变化，更新下方期望即可；
        //   A 也红 = 真回归，先查根因再谈更新快照。
        //
        // 📌 快照用可读文本而不是 JSON dump：这份期望值本身就是"模型这一轮会看到什么"的
        //   文档，改它的人应当能一眼看懂自己改了什么。
        val expected = """
system: [更早对话摘要]⏎- 用户查过北京天气：今天晴 18~26℃，明天多云转小雨 16~23℃，提醒了带伞⏎- 已按用户要求删除下载目录里的「旧报告.docx」（不可恢复）⏎- 用户表示喜欢听周杰伦
user: 把我刚才发的那篇长文整理成一个 markdown 文件存到下载目录
assistant: 我正在把文章写入文件，前两段已经整理好了 <<save_code_file>>
user: 算了先不管了，帮我查下明天的日程
assistant: （这一轮被中断了，没有完整回复。用户若要求继续，请基于上面那条请求重新作答。）
user: 我们刚才聊到哪了
assistant: 刚聊过北京今明两天的天气，还帮你删了下载目录里那份旧报告。
user: 对了，我平时喜欢听周杰伦
assistant: 记住了，以后你说想听歌我就知道先给你找周杰伦的。
user: 那后天呢
assistant: 后天小雨，15 到 20 度。连着两天有雨，出门都带着伞吧。 <<get_weather>>
        """.trimIndent()

        assertEquals(expected, render(projectionOf(MIXED).history()))
    }

    @Test
    fun `B2 诊断派生量快照`() {
        // 这些量主循环与 UI 面板都要用（压缩判定、用量展示、上下文占用），
        // 一起钉住比逐个单测更能反映"整体状态对不对"。
        val p = projectionOf(MIXED)
        assertEquals("可见消息 10 条（5 轮 × u/a）+ 摘要 = 模型历史 11 条", 11, p.history().size)
        assertEquals("可见记录不含摘要", 10, p.messageCount())
        assertTrue("已产生滚动摘要", p.hasDigest())
        assertTrue("轮完整性：user/assistant 成对", p.isTurnBalanced())
        assertEquals("最大轮号为 9（轮 6 被作废但仍占号，号不回收）", 9, p.maxTurn())

        // ★ 孤儿工具调用**恰为被打断的那一个** —— 这是预期行为，不是数据损坏。
        //   轮 4 的 save_code_file 在任务被打断时还没拿到结果，而
        //   `orphanToolCalls()` 的语义正是「有调用、无结果 —— 崩溃/中断留下的痕迹」
        //   （见其 KDoc），用途是回答"上次中断在哪一步"。
        //   📌 这条断言最初我写的是 `isEmpty()`，跑出来是红的 —— **红的是断言不是代码**：
        //      "调了但没回来"在改造前无法与"调了并成功"区分，能看见它正是事件流带来的能力。
        val orphans = p.orphanToolCalls()
        assertEquals("被打断轮恰好留下 1 个孤儿调用", 1, orphans.size)
        assertEquals("call_s1", orphans[0].callId)
        assertEquals("save_code_file", orphans[0].name)

        assertEquals(
            "上下文占用 = 摘要 + 10 条消息的字符数",
            p.history().sumOf { it.content.length },
            p.chars(),
        )
        assertNotNull("用量统计应为非空", p.usageTotals())
    }
}
