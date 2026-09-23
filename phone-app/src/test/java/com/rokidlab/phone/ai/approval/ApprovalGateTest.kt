package com.rokidlab.phone.ai.approval

import com.rokidlab.phone.ai.ToolGateway
import com.rokidlab.phone.ai.ToolRegistry
import com.rokidlab.phone.ai.ToolRisk
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 审批接缝（[ApprovalGate] / [ToolGuards] / [PageScope]）回归测试。
 *
 * 改造前这套判定长在 `ToolPolicy` 里，测试只有 6 条、且必须借"不存在的工具名"来触发确认闸门
 * （当时**没有任何真实工具标 EXTERNAL_SIDE_EFFECT**；2026-09-20 起 `delete_file` 成为首个，
 * 于是 E3b 可以用真实工具名做确认用例了）。现在判定被拆成可独立求值的 guard，
 * 每条策略可以单独锁住 —— 这正是接缝化想换来的东西。
 *
 * 本文件锁住的五类不变式：
 * 1. **合成语义**：Deny 单调短路（DSH 的 `ctx.tools.guard()`）、Ask 不阻断其他 guard 的拒绝。
 * 2. **伪工具必须被当作已知工具**（[PseudoTools]）—— 少这一条，把伪工具接上闸门的瞬间
 *    `load_skill` 就会被判"未知工具"，技能链直接断。
 * 3. **fail-open 三态**：无通道 / 用户显式取消 / 超时，处理方式各不相同，
 *    混淆任意两个都会造成「用户没操作却说被取消」或「用户取消了却照样执行」。
 *    2026-09-23 起再叠一层：**问不到时的放行/拒绝由工具声明的 `ToolConfirmPolicy` 决定**
 *    （`BLOCK` 拒 / `PROCEED` 放行），不再按域硬编码 —— 见 F8 / K4 / K5。
 * 4. **确认通道是眼镜优先、手机兜底**：手机通道只为补上"本机模式下根本没有确认"这个洞，
 *    不替换既有眼镜主路径 —— 见 F9 / F10。
 * 5. **AIUI 页面的拒绝文案逐字不变**（页面侧按字面分支处理，[PageScope] 是唯一产地）。
 */
class ApprovalGateTest {

    @After
    fun tearDown() {
        ApprovalGate.resetForTest()
    }

    // ════════════════════════════════════════════════════════════════════
    // A. 合成语义（纯函数，用探针 guard 测）
    // ════════════════════════════════════════════════════════════════════

    /** 固定返回某个判定的探针，并记录被求值次数 */
    private class Probe(
        override val id: String,
        private val fixed: ToolDecision?,
        override val sources: Set<ToolSource> = ToolSource.values().toSet(),
    ) : ToolGuard {
        var calls = 0
        override fun evaluate(ctx: ToolCallContext): ToolDecision? {
            calls++
            return fixed
        }
    }

    private fun ctxOf(
        name: String = "get_current_time",
        source: ToolSource = ToolSource.CONVERSATION,
        localOnly: Boolean = false,
        unattended: Boolean = false,
        enabled: Boolean? = null,
    ): ToolCallContext = ToolCallContext(source, name, JSONObject(), localOnly, unattended, enabled)

    @Test
    fun `A1 任一 Deny 立即短路 后续 guard 不再求值`() {
        val deny = Probe("deny", ToolDecision.Deny(DecisionOrigin.PAGE_SCOPE, "nope"))
        val later = Probe("later", ToolDecision.Allow)

        val r = ApprovalGate.compose(ctxOf(), listOf(deny, later))

        assertTrue("必须是 Deny", r is ToolDecision.Deny)
        assertEquals("拒绝理由应原样透出", "nope", (r as ToolDecision.Deny).reason)
        assertEquals("短路后后续 guard 不得被求值", 0, later.calls)
    }

    @Test
    fun `A2 不表态的 guard 不阻断后续`() {
        val silent = Probe("silent", null)
        val allow = Probe("allow", ToolDecision.Allow)

        assertEquals(ToolDecision.Allow, ApprovalGate.compose(ctxOf(), listOf(silent, allow)))
        assertEquals(1, silent.calls)
        assertEquals(1, allow.calls)
    }

    @Test
    fun `A3 无 Deny 时第一个 Ask 胜出`() {
        val first = Probe("a", ToolDecision.Ask(DecisionOrigin.RISK_CONFIRMATION, "第一条"))
        val second = Probe("b", ToolDecision.Ask(DecisionOrigin.RISK_CONFIRMATION, "第二条"))

        val r = ApprovalGate.compose(ctxOf(), listOf(Probe("s0", null), first, second))

        assertEquals("按 guard 顺序取第一个 Ask", "第一条", (r as ToolDecision.Ask).prompt)
    }

    @Test
    fun `A4 全部不表态时放行`() {
        assertEquals(ToolDecision.Allow, ApprovalGate.compose(ctxOf(), listOf(Probe("a", null), Probe("b", null))))
    }

    @Test
    fun `A5 来源不匹配的 guard 完全不参与`() {
        val pageOnly = Probe("page", ToolDecision.Deny(DecisionOrigin.PAGE_SCOPE, "x"), setOf(ToolSource.AIUI_PAGE))

        assertEquals(
            "对话路径不该被页面策略影响",
            ToolDecision.Allow,
            ApprovalGate.compose(ctxOf(source = ToolSource.CONVERSATION), listOf(pageOnly)),
        )
        assertEquals("来源匹配时才求值", 0, pageOnly.calls)

        assertTrue(
            "AIUI 路径下该策略生效",
            ApprovalGate.compose(ctxOf(source = ToolSource.AIUI_PAGE), listOf(pageOnly)) is ToolDecision.Deny,
        )
        assertEquals(1, pageOnly.calls)
    }

    // ════════════════════════════════════════════════════════════════════
    // B. UnknownToolGuard —— 含"伪工具必须被认作已知"这条关键不变式
    // ════════════════════════════════════════════════════════════════════

    private val unknownGuard = UnknownToolGuard()

    @Test
    fun `B1 真实工具不被判未知`() {
        assertNull(unknownGuard.evaluate(ctxOf("get_current_time")))
        assertNull(unknownGuard.evaluate(ctxOf("call_phone")))
        assertNull(unknownGuard.evaluate(ctxOf("save_code_file")))
    }

    @Test
    fun `B2 伪工具必须被认作已知（少这条技能链会直接断）`() {
        PseudoTools.names().forEach { name ->
            assertNull(
                "伪工具 $name 不在 toolList 里，但绝不能因此被判未知 —— " +
                    "否则它一接上闸门就会被拒，load_skill / update_plan / manage_memory / " +
                    "install_skill / list_skills / delete_skill 全线失效",
                unknownGuard.evaluate(ctxOf(name)),
            )
        }
        assertEquals(
            "伪工具应恰为 7 个（4 个原有 + 2026-09-20 新增的技能管理三件套；新增/删除都要同步这里）",
            7,
            PseudoTools.names().size,
        )
    }

    @Test
    fun `B3 幻觉工具名被单调拒绝`() {
        val r = unknownGuard.evaluate(ctxOf("hallucinated_tool_xyz"))
        assertTrue(r is ToolDecision.Deny)
        assertEquals(DecisionOrigin.UNKNOWN_TOOL, (r as ToolDecision.Deny).origin)
        assertTrue("文案需带工具名便于排查", r.reason.contains("hallucinated_tool_xyz"))
    }

    @Test
    fun `B4 未知名先被 UnknownToolGuard 拒 不再白等眼镜确认`() {
        // 改造前的行为：未知名 → ToolRiskMap 兜底 EXTERNAL_SIDE_EFFECT → 走确认闸门 →
        // 眼镜在线时白等 35s 问用户「是否执行 hallucinated_tool_xyz」→ 超时 → 才抛未知工具。
        // 这是纯缺陷（那个名字根本不存在，确认毫无意义），现在被 B3 提前拦下。
        ApprovalGate.confirmationResolver = FakeResolver(available = true, confirmed = false)
        val r = ApprovalGate.preExecute(ToolSource.CONVERSATION, "hallucinated_tool_xyz", JSONObject())
        assertTrue("必须是拒绝，而不是放行后由下游抛异常", r is ToolDecision.Deny)
        assertEquals(DecisionOrigin.UNKNOWN_TOOL, (r as ToolDecision.Deny).origin)
    }

    // ════════════════════════════════════════════════════════════════════
    // C. GlassesDependencyGuard
    // ════════════════════════════════════════════════════════════════════

    private val glassesGuard = GlassesDependencyGuard()

    @Test
    fun `C1 非本机模式下眼镜工具不受影响`() {
        assertNull(glassesGuard.evaluate(ctxOf("show_lyrics", localOnly = false)))
    }

    @Test
    fun `C2 本机模式下眼镜工具被拒绝`() {
        val r = glassesGuard.evaluate(ctxOf("show_lyrics", localOnly = true))
        assertTrue(r is ToolDecision.Deny)
        assertEquals(DecisionOrigin.GLASSES_REQUIRED, (r as ToolDecision.Deny).origin)
    }

    @Test
    fun `C3 本机模式下手机侧工具照常放行`() {
        assertNull("纯手机侧实现必须不受本机模式影响", glassesGuard.evaluate(ctxOf("get_current_time", localOnly = true)))
        assertNull(glassesGuard.evaluate(ctxOf("save_code_file", localOnly = true)))
    }

    @Test
    fun `C4 只对对话路径生效（AIUI 页面没有本机模式）`() {
        // ⚠️ 来源过滤发生在 compose 层，不在 guard 里 —— guard.evaluate 只回答
        // "本机模式 且 需要眼镜" 这一个窄问题（这样每个 guard 都能独立单测）。
        // 所以验证"AIUI 页面不受影响"必须走 compose，直接调 evaluate 验不出来源语义。
        val aiuiCtx = ctxOf("show_lyrics", source = ToolSource.AIUI_PAGE, localOnly = true)
        assertNotNull(
            "guard 只回答「本机模式 且 需要眼镜」，不看来源 —— 来源过滤是 compose 的职责",
            glassesGuard.evaluate(aiuiCtx),
        )
        assertEquals(
            "AIUI 页面的代码就在眼镜上跑，不存在「本机模式」这个状态 —— 必须整体放行",
            ToolDecision.Allow,
            ApprovalGate.compose(aiuiCtx, listOf(glassesGuard)),
        )
        assertFalse("来源集合必须排除 AIUI_PAGE", ToolSource.AIUI_PAGE in glassesGuard.sources)
    }

    @Test
    fun `C5 判据是用户开关而不是链路状态`() {
        // 单测无法构造"链路在线但开了本机模式"的会话，这里锁住的是"两条判据不能混用"这个设计：
        // GlassesDependencyGuard 只看 localOnly —— 链路抖动时若自动拒绝，
        // 会重演 2026-09-11「用户已授权却打不出电话，表现为被安全策略挡住」的事故。
        assertNull("localOnly=false 时即使眼镜工具也绝不拒绝", glassesGuard.evaluate(ctxOf("launch_glasses_app")))
        assertNotNull(glassesGuard.evaluate(ctxOf("launch_glasses_app", localOnly = true)))
    }

    // ════════════════════════════════════════════════════════════════════
    // D. SourceRateLimitGuard
    // ════════════════════════════════════════════════════════════════════

    @Test
    fun `D1 AIUI 页面每分钟上限 30 次`() {
        // 页面是模型生成的 .ink，可能死循环刷工具
        val g = SourceRateLimitGuard()
        repeat(30) { i ->
            assertNull("第 ${i + 1} 次应放行", g.evaluate(ctxOf(source = ToolSource.AIUI_PAGE)))
        }
        val overflow = g.evaluate(ctxOf(source = ToolSource.AIUI_PAGE))
        assertTrue("第 31 次必须被限流", overflow is ToolDecision.Deny)
        assertTrue("文案要给出具体上限", (overflow as ToolDecision.Deny).reason.contains("30"))
    }

    @Test
    fun `D2 对话路径上限 120 且与页面分桶`() {
        val g = SourceRateLimitGuard()
        // 先把 AIUI 桶用满
        repeat(31) { g.evaluate(ctxOf(source = ToolSource.AIUI_PAGE)) }
        assertTrue("AIUI 桶已耗尽", g.evaluate(ctxOf(source = ToolSource.AIUI_PAGE)) is ToolDecision.Deny)

        // 对话桶必须完全不受影响（曾统一用 30/min，打断正常的多轮工具循环）
        repeat(120) { i ->
            assertNull("对话第 ${i + 1} 次应放行", g.evaluate(ctxOf(source = ToolSource.CONVERSATION)))
        }
        val overflow = g.evaluate(ctxOf(source = ToolSource.CONVERSATION))
        assertTrue(overflow is ToolDecision.Deny)
        assertTrue((overflow as ToolDecision.Deny).reason.contains("120"))
    }

    @Test
    fun `D3 时间窗滑过后恢复`() {
        var now = 0L
        val g = SourceRateLimitGuard(aiuiPageLimit = 2, windowMs = 1000L, clock = { now })
        assertNull(g.evaluate(ctxOf(source = ToolSource.AIUI_PAGE)))
        assertNull(g.evaluate(ctxOf(source = ToolSource.AIUI_PAGE)))
        assertTrue(g.evaluate(ctxOf(source = ToolSource.AIUI_PAGE)) is ToolDecision.Deny)
        now = 1001L
        assertNull("窗口滑过后应恢复", g.evaluate(ctxOf(source = ToolSource.AIUI_PAGE)))
    }

    // ════════════════════════════════════════════════════════════════════
    // E. RiskApprovalGuard
    // ════════════════════════════════════════════════════════════════════

    private val riskGuard = RiskApprovalGuard()

    @Test
    fun `E1 走确认闸门的内置工具恰为这四个`() {
        // ★ 2026-09-20 起这条断言不再是"空集"。文件工作区把**删除**标成了 EXTERNAL_SIDE_EFFECT：
        // 删除不可恢复，用户明确要求"删除走确认闸门"（对照 edit_text_file / move_file 只是
        // LOCAL_SIDE_EFFECT，直接执行不打扰）。
        // ★ 2026-09-23 本机执行域新增两项：delete_script（删脚本库条目，与 delete_file 同判据）
        // 与 install_packages（apt 会拉外网流量、改容器状态）。后者收进闸门还有一层机制考虑：
        // run_shell 是 LOCAL 档（对话路径静默执行），把 apt 留在 shell 里 = 一条**不问用户**的装包通道。
        // 本断言的职责：守住闸门只服务被显式选中的这几个 —— 任何人新增 EXTERNAL_SIDE_EFFECT
        // 工具，都必须回到这里同步，并补一条像 E3b / E3c 那样的确认用例。
        val external = ToolRegistry.toolList
            .filter { ToolRegistry.riskOfOrNull(it.name) == ToolRisk.EXTERNAL_SIDE_EFFECT }
            .map { it.name }
            .sorted()
        assertEquals(
            "若此项变化，说明确认闸门的服务对象变了，请同步回归用例（见 E3b / E3c）",
            listOf("delete_file", "delete_script", "install_packages", "send_sms"),
            external,
        )
    }

    @Test
    fun `E2 只读与本机副作用工具都不触发确认`() {
        assertNull(riskGuard.evaluate(ctxOf("get_current_time")))
        assertNull(riskGuard.evaluate(ctxOf("call_phone")))
        assertNull(riskGuard.evaluate(ctxOf("install_aiui_project")))
        // 文件工作区里只有「删除」走确认；改/移是同机就地操作，不该弹确认框打扰用户
        assertNull(riskGuard.evaluate(ctxOf("edit_text_file")))
        assertNull(riskGuard.evaluate(ctxOf("move_file")))
        // run_shell（本机执行）必须是**本机副作用**档：容器可丢弃、不提权、触达不到第三方，
        // 够不上"外部副作用"。若有人把它调成 EXTERNAL_SIDE_EFFECT，会退化成
        // 「每次执行命令都要眼镜确认、未连眼镜白等 35 秒」—— 正是 save_code_file 当年的故障形状。
        assertNull(riskGuard.evaluate(ctxOf("run_shell")))
    }

    @Test
    fun `E3 外部副作用档返回 Ask 且摘要来自工具声明`() {
        // 未知名会命中 ToolRiskMap 的"最保守档 = EXTERNAL"，用它触发本 guard
        val r = riskGuard.evaluate(ctxOf("hallucinated_tool_xyz"))
        assertTrue(r is ToolDecision.Ask)
        assertEquals(DecisionOrigin.RISK_CONFIRMATION, (r as ToolDecision.Ask).origin)
        assertTrue("摘要必须非空（否则眼镜端会弹一个空白确认框）", r.prompt.isNotBlank())
    }

    @Test
    fun `E3b delete_file 的确认摘要读出被删对象`() {
        // 回归 2026-09-20：delete_file 是当前**唯一**走确认闸门的内置工具（见 E1）。
        // 它支持三种语义（删整个项目 / 删项目内文件 / 删下载目录文件），摘要必须把它们区分开，
        // 否则用户在眼镜上只会看到一句"删除文件"，根本不知道要删掉什么。
        fun ask(args: JSONObject): String =
            (riskGuard.evaluate(ToolCallContext(ToolSource.CONVERSATION, "delete_file", args, false))
                as ToolDecision.Ask).prompt

        val wholeProject = ask(JSONObject().put("project", "aiui-demo"))
        assertTrue("删整个项目要写明项目名，实际：$wholeProject", wholeProject.contains("aiui-demo"))

        val insideProject = ask(JSONObject().put("project", "aiui-demo").put("path", "pages/index/index.ink"))
        assertTrue("删项目内文件要写出路径，实际：$insideProject", insideProject.contains("index.ink"))

        val fromDownloads = ask(JSONObject().put("scope", "downloads").put("path", "note.txt"))
        assertTrue("删下载目录文件要写出路径，实际：$fromDownloads", fromDownloads.contains("note.txt"))
    }

    @Test
    fun `E3c 新增外部档工具的确认摘要也读出对象`() {
        // 回归 2026-09-23：本机执行域新增两个外部副作用工具（见 E1）。确认摘要必须让用户在
        // 眼镜上一眼看出"要装什么 / 要删哪个" —— 摘要含糊时用户没法判断该不该同意，
        // 只能盲点，等于把闸门降级成一次点击负担。
        fun askOf(name: String, args: JSONObject): String =
            (riskGuard.evaluate(ToolCallContext(ToolSource.CONVERSATION, name, args, false))
                as ToolDecision.Ask).prompt

        val install = askOf(
            "install_packages",
            JSONObject().put("packages", JSONArray().put("python3").put("ffmpeg")),
        )
        assertTrue("装包确认要列出包名，实际：$install", install.contains("python3"))

        val emptyInstall = askOf("install_packages", JSONObject())
        assertTrue("包名为空时也要给出一句可读的摘要，实际：$emptyInstall", emptyInstall.isNotBlank())

        val del = askOf("delete_script", JSONObject().put("name", "resize-images.sh"))
        assertTrue("删脚本确认要写出脚本名，实际：$del", del.contains("resize-images.sh"))
    }

    // ════════════════════════════════════════════════════════════════════
    // F. resolveAsk —— 通道选择 + fail-open 三态 + 声明分流（安全关键）
    // ════════════════════════════════════════════════════════════════════

    private class FakeResolver(
        private val available: Boolean,
        private val confirmed: Boolean,
        private val cancelled: Boolean = false,
        /** 通道标识：用它在断言里区分"这次问的是眼镜还是手机"（眼镜优先，见 F9） */
        override val channelId: String = "glasses-fake",
    ) : ApprovalGate.ConfirmResolver {
        var lastPrompt: String? = null
        var calls = 0
        override fun isAvailable(): Boolean = available
        override fun confirm(toolName: String, prompt: String): Boolean {
            calls++
            lastPrompt = prompt
            return confirmed
        }
        override fun wasCancelled(): Boolean = cancelled
    }

    private val ask = ToolDecision.Ask(DecisionOrigin.RISK_CONFIRMATION, "是否拨打 张三")

    @Test
    fun `F1 无确认通道时降级放行而不是硬拒`() {
        ApprovalGate.confirmationResolver = null
        assertEquals(
            "硬拒会让功能表现为「被安全策略挡住」（call_phone 只打开拨号盘，绝不自动拨出）",
            ToolDecision.Allow,
            ApprovalGate.resolveAsk(ask, "call_phone"),
        )
    }

    @Test
    fun `F2 通道不可用（未连接、眼镜端旧版）同样降级放行`() {
        ApprovalGate.confirmationResolver = FakeResolver(available = false, confirmed = false, cancelled = true)
        assertEquals(
            "通道不可用时连 wasCancelled 都不该采信（那个标志是上一次调用留下的）",
            ToolDecision.Allow,
            ApprovalGate.resolveAsk(ask, "call_phone"),
        )
    }

    @Test
    fun `F3 用户显式取消才拒绝`() {
        ApprovalGate.confirmationResolver = FakeResolver(available = true, confirmed = false, cancelled = true)
        val r = ApprovalGate.resolveAsk(ask, "call_phone")
        assertTrue(r is ToolDecision.Deny)
        assertEquals(DecisionOrigin.RISK_CONFIRMATION, (r as ToolDecision.Deny).origin)
        assertTrue("文案需让用户知道自己按了取消", r.reason.contains("取消"))
    }

    @Test
    fun `F4 超时未响应降级放行（只有显式取消才算拒绝）`() {
        ApprovalGate.confirmationResolver = FakeResolver(available = true, confirmed = false, cancelled = false)
        assertEquals(
            "超时与取消在眼镜端都是 allowed=false，只能靠 wasCancelled 区分 —— 混为一谈会" +
                "让「眼镜没响应」被读成「用户拒绝了」",
            ToolDecision.Allow,
            ApprovalGate.resolveAsk(ask, "call_phone"),
        )
    }

    @Test
    fun `F5 用户确认后放行`() {
        ApprovalGate.confirmationResolver = FakeResolver(available = true, confirmed = true)
        assertEquals(ToolDecision.Allow, ApprovalGate.resolveAsk(ask, "call_phone"))
    }

    @Test
    fun `F6 确认通道抛异常不得把调用打挂`() {
        ApprovalGate.confirmationResolver = object : ApprovalGate.ConfirmResolver {
            override val channelId: String = "throwing"
            override fun isAvailable(): Boolean = true
            override fun confirm(toolName: String, prompt: String): Boolean = throw IllegalStateException("boom")
        }
        assertEquals("通道异常按「没确认」处理并降级放行", ToolDecision.Allow, ApprovalGate.resolveAsk(ask, "call_phone"))
    }

    @Test
    fun `F7 Ask 的 prompt 会传给通道（摘要不由通道自己拼）`() {
        val fake = FakeResolver(available = true, confirmed = true)
        ApprovalGate.confirmationResolver = fake
        ApprovalGate.resolveAsk(ask, "call_phone")
        assertEquals("摘要必须来自 Ask（即工具声明），通道只负责传输", "是否拨打 张三", fake.lastPrompt)
    }

    @Test
    fun `F8 无通道时按工具声明分流：send_sms 拒绝、delete_file 放行`() {
        // ★ 2026-09-23 改造的核心不变式。改造前 fail-open 判据硬编码 `domain == MCP`，
        // 于是 send_sms（**真的会把短信发出去**）与 call_phone（只开拨号盘）共享同一条
        // 静默放行路径 —— 两者的代价根本不是一个量级。现在读工具自己声明的 confirmPolicy。
        ApprovalGate.confirmationResolver = null
        ApprovalGate.phoneConfirmationResolver = null
        val noChannel = { name: String, args: JSONObject ->
            riskGuard.evaluate(ToolCallContext(ToolSource.CONVERSATION, name, args, false)) as ToolDecision.Ask
        }

        val smsAsk = noChannel("send_sms", JSONObject().put("to", "10086").put("text", "hi"))
        assertTrue("send_sms 声明 BLOCK ⇒ Ask 必须带 failClosed", smsAsk.failClosed)
        assertTrue(
            "问不到用户时绝不能静默把短信发出去",
            ApprovalGate.resolveAsk(smsAsk, "send_sms") is ToolDecision.Deny,
        )

        val delAsk = noChannel("delete_file", JSONObject().put("scope", "downloads").put("path", "a.txt"))
        assertFalse("delete_file 显式声明 PROCEED ⇒ 不 fail-closed", delAsk.failClosed)
        assertEquals(
            "删本机文件可重做，问不到就照做 —— 按 BLOCK 会表现为「被安全策略挡住」（save_code_file 事故方向）",
            ToolDecision.Allow,
            ApprovalGate.resolveAsk(delAsk, "delete_file"),
        )
    }

    @Test
    fun `F9 眼镜通道优先 手机通道只在它不可用时接管`() {
        val glasses = FakeResolver(available = true, confirmed = false, cancelled = true, channelId = "glasses")
        val phone = FakeResolver(available = true, confirmed = true, channelId = "phone")

        // 眼镜可用时，即使手机端会「同意」也不该走到手机 —— 眼镜语音是既有主路径
        ApprovalGate.confirmationResolver = glasses
        ApprovalGate.phoneConfirmationResolver = phone
        assertTrue(
            "眼镜通道可用却去问手机，会换掉用户熟悉的主路径；且这里眼镜端是「显式取消」⇒ 必须拒绝",
            ApprovalGate.resolveAsk(ask, "call_phone") is ToolDecision.Deny,
        )
        assertEquals("眼镜可用时手机通道不应被调用", 0, phone.calls)
        assertEquals(1, glasses.calls)

        // 眼镜不可用（本机模式 / 未连接）⇒ 手机通道接管，这正是补上的那个洞
        ApprovalGate.confirmationResolver = FakeResolver(available = false, confirmed = false)
        assertEquals("手机通道接管后用户点了允许 ⇒ 放行", ToolDecision.Allow, ApprovalGate.resolveAsk(ask, "call_phone"))
        assertEquals("接管后必须真的问过手机通道", 1, phone.calls)
    }

    @Test
    fun `F10 两条通道都不可用时才算问不到`() {
        ApprovalGate.confirmationResolver = FakeResolver(available = false, confirmed = true)
        ApprovalGate.phoneConfirmationResolver = FakeResolver(available = false, confirmed = true)
        // 都"不可用"⇒ 谁都不该被问；且不能把上一条通道的 confirmed=true 当成结论
        assertEquals(
            "都不可用 ⇒ 按声明分流（call_phone 是 PROCEED）",
            ToolDecision.Allow,
            ApprovalGate.resolveAsk(ask, "call_phone"),
        )
        assertTrue(
            "MCP 的 Ask 是 fail-closed ⇒ 都不可用时必须拒绝",
            ApprovalGate.resolveAsk(mcpAsk, "mcp__srv__danger") is ToolDecision.Deny,
        )
    }

    // ════════════════════════════════════════════════════════════════════
    // G. 风险表的另两个消费者（伪工具共用一份声明）
    // ════════════════════════════════════════════════════════════════════

    @Test
    fun `G1 伪工具只看读判定与审批共用同一张表`() {
        assertTrue("load_skill 只读本地技能文件", ApprovalGate.isReadOnly("load_skill"))
        assertTrue(ApprovalGate.isReadOnly("load_skill_section"))
        assertTrue("update_plan 只回填计划文本", ApprovalGate.isReadOnly("update_plan"))
        assertFalse("manage_memory 会写长期记忆库 → 不算只读，保持动作轮计费", ApprovalGate.isReadOnly("manage_memory"))
    }

    @Test
    fun `G2 伪工具风险档不得落到保守兜底`() {
        // 若 PseudoTools 漏了某个伪工具名，它会掉进 ToolRiskMap 的"未知名 → EXTERNAL"兜底，
        // 于是纯读的工具被要求眼镜端确认 —— save_code_file 漏登记那次事故的翻版
        assertEquals(ToolRisk.READ_ONLY, ApprovalGate.riskOf("load_skill"))
        assertEquals(ToolRisk.LOCAL_SIDE_EFFECT, ApprovalGate.riskOf("manage_memory"))
        assertEquals("未知名的兜底语义必须保留", ToolRisk.EXTERNAL_SIDE_EFFECT, ApprovalGate.riskOf("hallucinated_tool_xyz"))
    }

    @Test
    fun `G3 已知工具 = 真实工具 并 伪工具`() {
        assertTrue(ApprovalGate.isKnownTool("get_current_time"))
        PseudoTools.names().forEach { assertTrue("伪工具 $it 应算已知", ApprovalGate.isKnownTool(it)) }
        assertFalse(ApprovalGate.isKnownTool("hallucinated_tool_xyz"))
    }

    // ════════════════════════════════════════════════════════════════════
    // H. PageScope —— AIUI 页面拒绝文案逐字不变（页面按字面分支处理）
    // ════════════════════════════════════════════════════════════════════

    @Test
    fun `H1 黑名单拒绝文案逐字一致`() {
        val r = PageScopeGuard().evaluate(ctxOf("open_aiui_app", source = ToolSource.AIUI_PAGE))
        assertTrue(r is ToolDecision.Deny)
        assertEquals("tool 'open_aiui_app' is not allowed in AIUI pages", (r as ToolDecision.Deny).reason)
    }

    @Test
    fun `H2 未知名与域越界的文案逐字一致`() {
        assertEquals("unknown tool: nope", PageScope.rejectReason("nope", setOf("get_weather"), { null }))
        assertEquals(
            "domain 'web' of tool 'get_weather' is not allowed in AIUI pages",
            PageScope.rejectReason(
                "get_weather",
                knownTools = setOf("get_weather"),
                toolDomain = { ToolRegistry.DOMAIN_WEB },
                allowedDomains = setOf(ToolRegistry.DOMAIN_MEDIA),
            ),
        )
    }

    @Test
    fun `H3 判定与 ToolGateway 入口校验同源（文案不得漂移）`() {
        // PageScope 是文案唯一产地：网关入口与审批链必须给出完全一样的拒绝文本，
        // 否则页面会看到两套措辞，按字面分支的逻辑就失效了
        val viaGateway = ToolGateway.precheckToolCall("open_aiui_app", "{}")
        val viaGate = PageScope.rejectReason("open_aiui_app", PageScope.pageVisibleTools(), PageScope::domainOf)
        assertTrue(viaGateway is ToolGateway.Precheck.Reject)
        assertEquals((viaGateway as ToolGateway.Precheck.Reject).error, viaGate)
    }

    @Test
    fun `H4 页面不可见伪工具（伪工具只服务对话路径）`() {
        assertEquals("unknown tool: load_skill", PageScope.rejectReason("load_skill", PageScope.pageVisibleTools(), PageScope::domainOf))
    }

    @Test
    fun `H5 已知工具在开放域下通过`() {
        assertNull(PageScope.rejectReason("get_current_time", PageScope.pageVisibleTools(), PageScope::domainOf))
    }

    @Test
    fun `H6 run_shell 不对 AIUI 页面开放`() {
        // 页面是第三方制品（模型生成 / 用户导入的 .aix）。`run_shell` 是全表唯一的
        // **任意命令执行**原语 —— 开放它等于让页面作者在用户手机上跑任意命令。
        // 对话路径保留该能力是产品意图，页面路径必须拒绝。
        val reason = PageScope.rejectReason("run_shell", PageScope.pageVisibleTools(), PageScope::domainOf)
        assertEquals("domain 'shell' of tool 'run_shell' is not allowed in AIUI pages", reason)
        // 边界落在**域**上而不是 DENY_TOOLS：RULES 规定黑名单只放技术故障类，
        // 且摘域能让页面拿到的工具清单里也没有它（黑名单做不到这一点）
        assertTrue("shell 域必须不在页面开放域里", ToolRegistry.DOMAIN_SHELL !in PageScope.ALLOWED_DOMAINS)
        assertTrue("黑名单不该为它开先例", "run_shell" !in PageScope.DENY_TOOLS)
    }

    // ════════════════════════════════════════════════════════════════════
    // I. ToolEnabledGuard —— 用户开关的**执行侧**落点
    // ════════════════════════════════════════════════════════════════════

    private val enabledGuard = ToolEnabledGuard()

    @Test
    fun `I1 开关关掉的工具被拒绝`() {
        val r = enabledGuard.evaluate(ctxOf("show_image", enabled = false))
        assertTrue(r is ToolDecision.Deny)
        assertEquals(DecisionOrigin.TOOL_DISABLED, (r as ToolDecision.Deny).origin)
        assertTrue("文案要指出哪个工具被关", r.reason.contains("show_image"))
        assertTrue("文案要告诉用户去哪打开（否则只看到被拒，无从下手）", r.reason.contains("设置"))
    }

    @Test
    fun `I2 开关开着放行 开关状态未知不表态`() {
        assertNull(enabledGuard.evaluate(ctxOf("show_image", enabled = true)))
        assertNull(
            "没带开关状态（单测/无 Context 的调用路径）必须不表态 —— " +
                "把 null 读成「已关闭」会让整条判定链在没有 Context 时全线拒绝",
            enabledGuard.evaluate(ctxOf("show_image")),
        )
    }

    @Test
    fun `I3 开关闸门排在未知工具之后`() {
        // 顺序反了会出现「被关掉的伪工具报 unknown tool」，排查会指向完全错误的地方；
        // 而且限流必须排在纯判定之后（被拒的调用不该白扣配额）
        val order = ToolGuards.default().map { it.id }
        assertTrue("未知工具必须先于开关闸门", order.indexOf("unknown-tool") < order.indexOf("tool-disabled"))
        assertTrue("开关闸门必须早于限流", order.indexOf("tool-disabled") < order.indexOf("rate-limit"))
    }

    // ════════════════════════════════════════════════════════════════════
    // J. UnattendedScopeGuard —— 无人值守的执行侧白名单
    // ════════════════════════════════════════════════════════════════════

    private val unattendedGuard = UnattendedScopeGuard()

    @Test
    fun `J1 无人值守下名单外的工具被拒绝`() {
        // 装配侧（schemasUnattended）压根不下发这些；本 guard 防的是"模型凭历史复述出工具名"
        listOf("call_phone", "install_aiui_project", ToolRegistry.TOOL_CODE_FILE, "manage_timer").forEach { name ->
            val r = unattendedGuard.evaluate(ctxOf(name, unattended = true))
            assertTrue("无人监管时 $name 绝不能被跑", r is ToolDecision.Deny)
            assertEquals(DecisionOrigin.UNATTENDED_SCOPE, (r as ToolDecision.Deny).origin)
            assertTrue("文案要说明为什么不让跑", r.reason.contains("本人"))
        }
    }

    @Test
    fun `J2 无人值守下名单内的工具照常放行`() {
        assertNull("只读查询类必须可用", unattendedGuard.evaluate(ctxOf("get_current_time", unattended = true)))
        assertNull("到点放歌是唯一被放行的副作用工具", unattendedGuard.evaluate(ctxOf("control_music", unattended = true)))
    }

    @Test
    fun `J3 非无人值守时一条都不拒`() {
        listOf("call_phone", "install_aiui_project", "manage_timer").forEach { name ->
            assertNull("$name 在正常对话里必须放行", unattendedGuard.evaluate(ctxOf(name)))
        }
    }

    @Test
    fun `J4 只对对话路径生效（页面与头动规则没有无人值守这个状态）`() {
        assertEquals(setOf(ToolSource.CONVERSATION), unattendedGuard.sources)
        val pageCtx = ctxOf("call_phone", source = ToolSource.AIUI_PAGE, unattended = true)
        assertEquals(
            "来源过滤在 compose 层：页面路径不该被这条拒绝",
            ToolDecision.Allow,
            ApprovalGate.compose(pageCtx, listOf(unattendedGuard)),
        )
    }

    // ════════════════════════════════════════════════════════════════════
    // K. fail-closed 例外 —— 第三方远端工具问不到人必须拒绝
    // ════════════════════════════════════════════════════════════════════

    private val mcpAsk = ToolDecision.Ask(DecisionOrigin.RISK_CONFIRMATION, "是否调用 MCP 工具 x", failClosed = true)

    @Test
    fun `K1 MCP 工具无确认通道时拒绝而不是降级放行`() {
        ApprovalGate.confirmationResolver = null
        val r = ApprovalGate.resolveAsk(mcpAsk, "mcp__srv__danger")
        assertTrue("第三方远端副作用绝不能静默放行", r is ToolDecision.Deny)
        assertEquals(DecisionOrigin.RISK_CONFIRMATION, (r as ToolDecision.Deny).origin)
        assertTrue("要给出出路之一：连眼镜确认", r.reason.contains("眼镜"))
        assertTrue("要给出出路之二：设置页标信任", r.reason.contains("信任"))
    }

    @Test
    fun `K2 MCP 工具超时未响应同样拒绝（超时≠用户拒绝，但对 MCP 两种都不放行）`() {
        ApprovalGate.confirmationResolver = FakeResolver(available = true, confirmed = false, cancelled = false)
        assertTrue(ApprovalGate.resolveAsk(mcpAsk, "mcp__srv__danger") is ToolDecision.Deny)
        ApprovalGate.confirmationResolver = FakeResolver(available = false, confirmed = false, cancelled = true)
        assertTrue(ApprovalGate.resolveAsk(mcpAsk, "mcp__srv__danger") is ToolDecision.Deny)
    }

    @Test
    fun `K3 MCP 工具用户确认后放行`() {
        ApprovalGate.confirmationResolver = FakeResolver(available = true, confirmed = true)
        assertEquals(ToolDecision.Allow, ApprovalGate.resolveAsk(mcpAsk, "mcp__srv__danger"))
    }

    @Test
    fun `K4 声明 PROCEED 的工具不 fail-closed（fail-open 语义不变）`() {
        // fail-open 的前提是工具侧自带"未确认就降级为无副作用动作"的保证
        // （call_phone 只开拨号盘、装机只到安装确认页）。这些内置工具满足，MCP 无法保证。
        // ⚠️ 2026-09-23 起这**不再等于"所有内置工具"**：`send_sms` 被显式标成 BLOCK
        // （它真的会把短信发出去，没有"降级"可言），见 E1 / F8。
        listOf("delete_file", "delete_script", "install_packages").forEach { name ->
            val r = riskGuard.evaluate(ctxOf(name))
            assertTrue(r is ToolDecision.Ask)
            assertFalse("$name 声明的是 PROCEED，不得改成 fail-closed", (r as ToolDecision.Ask).failClosed)
        }
    }

    @Test
    fun `K4b 未知名兜底 fail-closed（未知即最保守）`() {
        // 未知名（模型幻觉 / 历史残留）在 ToolRegistry 里取不到声明 ⇒ confirmPolicy 兜底 BLOCK，
        // 与 ToolRiskMap.riskOf 的"未知名 → 最保守档"是同一条原则。
        // 这**不会**造成"白等 40s 再被拒"：UnknownToolGuard 在到达闸门之前就单调拒绝了（见 B3/B4），
        // 本断言守的是"万一有人把 UnknownToolGuard 摘掉，也不会漏成 fail-open"这层兜底。
        val r = riskGuard.evaluate(ctxOf("hallucinated_tool_xyz"))
        assertTrue(r is ToolDecision.Ask)
        assertTrue("未知名字必须保守", (r as ToolDecision.Ask).failClosed)
    }

    @Test
    fun `K5 MCP 文案按名字前缀判定 不依赖 server 是否连接`() {
        // ★ 回归 2026-09-23：这里原本查 `ToolRegistry.domainOfOrNull(name) == DOMAIN_MCP`。
        // 但 MCP 工具是**动态**注册的（dynamicProviders 只在 server 连上时才有内容），
        // 单测环境与"server 已断开"两种情况下都查不到 ⇒ 误落到内置工具分支，
        // 用户拿到的出路变成"去打开乐奇实验室"，而正确的出路是"把这个 server 标为信任"。
        ApprovalGate.confirmationResolver = null
        ApprovalGate.phoneConfirmationResolver = null
        val reason = (ApprovalGate.resolveAsk(mcpAsk, "mcp__srv__danger") as ToolDecision.Deny).reason
        assertTrue("必须给出 MCP 专属出路：标信任", reason.contains("信任"))
        assertFalse("不得落到内置工具文案", reason.contains("打开乐奇实验室"))
        assertTrue("前缀判据必须与生成侧同源", ToolRegistry.isMcpTool("mcp__srv__danger"))
        assertFalse("内置工具不能被前缀判据误伤", ToolRegistry.isMcpTool("send_sms"))
    }

    // ════════════════════════════════════════════════════════════════════
    // L. 重试判定与装配投影的数据源
    // ════════════════════════════════════════════════════════════════════

    @Test
    fun `L1 伪工具的副作用子集（瞬时失败不可自动重试）`() {
        val side = PseudoTools.sideEffectNames()
        assertTrue("写记忆不可自动重放", "manage_memory" in side)
        assertFalse("读技能原文可安全重试", "load_skill" in side)
        assertFalse("回填计划文本可安全重试", "update_plan" in side)
        assertFalse("只读清单可安全重试", "list_skills" in side)
    }

    @Test
    fun `L2 装配投影只认合法 schema 且不炸`() {
        val meta = ToolRegistry.toolList.first { it.name == "get_current_time" }
        assertEquals(
            "提示词闸门的数据源：必须与真正下发的 schema 同名同集合",
            setOf("get_current_time"),
            ToolRegistry.namesOf(listOf(ToolRegistry.buildSchema(meta))),
        )
        assertEquals("畸形 schema 不得让装配投影抛异常", emptySet<String>(), ToolRegistry.namesOf(listOf(JSONObject())))
    }
}
