package com.rokidlab.phone.store

import com.rokidlab.phone.ai.AgentStep
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 「过程」落点回归 —— 一轮的过程必须只落在**一条**消息上。
 *
 * 背景（2026-09-20 真机 bug，症状「图片已经显示出来了，过程里还在思考」）：
 * [ChatStateHolder] 原先用「列表末尾那条非用户非状态消息」定位过程消息，**位置不是身份** ——
 * 一轮进行中只要**别的**消息被追加，"末尾"就换人，于是同一个 `tool:<call_id>` 的 RUNNING
 * 留在旧气泡、OK/FAILED 写进新气泡；[AgentStep] 的「同 key 覆盖」只在单条消息内生效，
 * 覆盖随即失效，旧气泡永久转圈（收尾时也只结清末尾那条，救不回来）。
 *
 * 真机上有**多条互相独立**的插入路径，跟"调用了哪个工具"无关：
 *  1. `show_image` 的图片气泡（[ChatStateHolder.addImage]）；
 *  2. 任何 [ChatStateHolder.add] / [ChatStateHolder.updateLastStatus] 落地的新消息
 *     （拍照答题的状态行原先走这条路，2026-10-10 已改走过程面板，但**别的**入口
 *     仍可能在任何时刻插消息 —— 所以这条不变式必须继续钉住）。
 *
 * 现在落点由 `ChatStateHolder` 内部的 `traceAnchorId` 锚定，本类就是钉住它：
 * 任何"轮中途插一条消息"的写法都不该再把过程劈开。
 *
 * 可测性：`testOptions.unitTests.isReturnDefaultValues = true` ⇒ `Looper.myLooper()` 与
 * `getMainLooper()` 都返回 null，`runOnMain` 走内联分支；`appContext` 为 null ⇒ 所有落盘
 * 静默跳过。因此本类可以直接驱动单例（**不动设备、不碰文件**）。
 */
class ChatStateHolderTraceTest {

    private val messages get() = ChatStateHolder.messages

    @Before
    fun setUp() {
        messages.clear()
        ChatStateHolder.finishTrace(failed = true) // 释放可能残留的锚点
    }

    @After
    fun tearDown() {
        messages.clear()
        ChatStateHolder.finishTrace(failed = true)
    }

    /** 拍照流程 / OCR 下载百分比走的那条：末尾不是状态气泡时会**新增**一条 */
    @Test
    fun `轮中途插入状态气泡不会把过程劈成两条消息`() {
        ChatStateHolder.add(isUser = true, content = "帮我看看这道题")
        ChatStateHolder.upsertTrace(think(0, AgentStep.State.RUNNING))

        ChatStateHolder.updateLastStatus("正在识别…") // ← 插入一条状态气泡

        ChatStateHolder.upsertTrace(think(0, AgentStep.State.OK)) // ← 同 key 的终态

        val carriers = messages.filter { it.trace.isNotEmpty() }
        assertEquals("过程只应落在一条消息上", 1, carriers.size)
        assertEquals("同 key 覆盖：RUNNING 应被 OK 替换，不是追加", 1, carriers[0].trace.size)
        assertEquals(AgentStep.State.OK, carriers[0].trace[0].state)
        assertTrue("收尾后不该再有进行中的步骤", carriers[0].trace.none { it.state == AgentStep.State.RUNNING })
    }

    /** `show_image` 的图片气泡：过程不被劈开，且图片与过程落在同一条上 */
    @Test
    fun `轮中途显示图片不会把过程劈开`() {
        ChatStateHolder.add(isUser = true, content = "给我找张小狗的图")
        ChatStateHolder.upsertTrace(toolStep("call_1", "baidu_image_search", AgentStep.State.RUNNING))

        ChatStateHolder.addImage(
            isUser = false,
            imageUrl = "https://img.example/x.jpg",
            caption = "找了一张小狗的图",
        )

        ChatStateHolder.upsertTrace(
            toolStep("call_1", "baidu_image_search", AgentStep.State.OK, result = "{\"code\":200}"),
        )
        ChatStateHolder.upsertTrace(think(1, AgentStep.State.RUNNING))
        ChatStateHolder.finishTrace()

        val carriers = messages.filter { it.trace.isNotEmpty() }
        assertEquals("过程只应落在一条消息上", 1, carriers.size)
        assertEquals("图片应该和过程在同一条上", "https://img.example/x.jpg", carriers[0].imageUrl)
        assertEquals(2, carriers[0].trace.size)
        assertTrue("收尾后不该再有进行中的步骤", carriers[0].trace.none { it.state == AgentStep.State.RUNNING })
    }

    /** 收尾**只**结清本轮 —— 历史消息里的残留不该被顺手改绿（那是要查的线索，不是要抹的症状） */
    @Test
    fun `收尾只结清本轮不碰历史消息`() {
        messages.add(
            ChatMsg(
                id = 1L,
                isUser = false,
                content = "旧回复",
                time = "10:00",
                isStatus = false,
                trace = listOf(think(0, AgentStep.State.RUNNING)),
            ),
        )

        ChatStateHolder.add(isUser = true, content = "新问题")
        ChatStateHolder.upsertTrace(think(0, AgentStep.State.RUNNING))
        ChatStateHolder.finishTrace()

        assertEquals(
            "历史消息里的残留应原样保留",
            AgentStep.State.RUNNING,
            messages[0].trace[0].state,
        )
        assertEquals("本轮的过程应被结清", AgentStep.State.OK, messages.last().trace[0].state)
    }

    /** 收尾释放锚点：下一轮的过程必须落到新气泡上，不能写进上一轮的回复 */
    @Test
    fun `收尾后新一轮的过程不会写进上一条回复`() {
        ChatStateHolder.add(isUser = true, content = "一")
        ChatStateHolder.upsertTrace(think(0, AgentStep.State.RUNNING))
        ChatStateHolder.finalizeTraceReply("第一条回复")
        ChatStateHolder.finishTrace()

        ChatStateHolder.add(isUser = true, content = "二")
        ChatStateHolder.upsertTrace(think(0, AgentStep.State.RUNNING))

        val carriers = messages.filter { it.trace.isNotEmpty() }
        assertEquals("两轮应各有一条过程消息", 2, carriers.size)
        assertEquals("第一条回复", carriers[0].content)
        assertEquals(1, carriers[0].trace.size)
        assertEquals(AgentStep.State.OK, carriers[0].trace[0].state)
        assertEquals(1, carriers[1].trace.size)
        assertEquals(AgentStep.State.RUNNING, carriers[1].trace[0].state)
    }

    /** 打字路径的回复落点：锚点优先，状态气泡插在中间也不能把正文写进别的消息 */
    @Test
    fun `状态气泡插在中间时回复仍落回本轮那条消息`() {
        ChatStateHolder.add(isUser = true, content = "讲个笑话")
        ChatStateHolder.upsertTrace(think(0, AgentStep.State.RUNNING))
        ChatStateHolder.updateLastStatus("正在生成…")

        ChatStateHolder.finalizeLastAi("从前有只猫", usage = null, turn = 7)
        ChatStateHolder.finishTrace()

        val carriers = messages.filter { it.trace.isNotEmpty() }
        assertEquals(1, carriers.size)
        assertEquals("从前有只猫", carriers[0].content)
        assertEquals("轮号也要跟着落在同一条上", 7, carriers[0].turn)
        assertEquals(AgentStep.State.OK, carriers[0].trace[0].state)
        // 用户气泡 + 本条 AI 气泡 + 中间那条状态气泡，不该为回复另起第四条
        assertEquals(3, messages.size)
    }

    /** 换会话要把锚点作废：否则新会话里同 id 的消息会被误认成本轮的过程消息 */
    @Test
    fun `换会话后旧锚点不再指向新会话的同 id 消息`() {
        ChatStateHolder.add(isUser = true, content = "问题")
        ChatStateHolder.upsertTrace(think(0, AgentStep.State.RUNNING))
        val anchorId = messages.last().id

        ChatStateHolder.newSession() // 内部会 forgetTraceAnchor + 清空列表
        // 新会话里造一条 id 恰好等于旧锚点的消息（id 从 1 重新开始，这在真机上会出现）
        messages.add(ChatMsg(id = anchorId, isUser = false, content = "别的会话的回复", time = "11:00"))

        ChatStateHolder.upsertTrace(think(1, AgentStep.State.RUNNING))

        assertEquals("不该写进别人的消息", emptyList<AgentStep>(), messages[0].trace)
        assertEquals("应为本轮另起一条过程消息", 1, messages.last().trace.size)
    }

    // ===== 跨轮动作（手动运行程序，2026-10-10） =====

    /**
     * 手动运行程序**不属于任何一轮**：它的过程不该占住本轮锚点。
     *
     * 若它占了锚点，紧接着那一轮 AI 的思考/工具步骤会全部并进那张运行过程卡 ——
     * 用户看到"我自己手动跑的程序"里混着"AI 刚才想了什么"。
     */
    @Test
    fun `跨轮动作的过程不占本轮锚点`() {
        ChatStateHolder.addRunCard("run_1", "PYTHON")
        val runCarriers = messages.filter { m -> m.trace.any { it.key == "run:run_1" } }
        assertEquals(1, runCarriers.size)

        // 紧接着的一轮对话
        ChatStateHolder.add(isUser = true, content = "帮我改一下")
        ChatStateHolder.upsertTrace(think(0, AgentStep.State.RUNNING))

        val thinkCarriers = messages.filter { m -> m.trace.any { it.kind == AgentStep.Kind.THINKING } }
        assertEquals("本轮过程必须另起一条，不能并进运行卡", 1, thinkCarriers.size)
        assertTrue(
            "运行卡里不该混进本轮的思考",
            runCarriers[0].trace.none { it.kind == AgentStep.Kind.THINKING },
        )
    }

    /** 同一次运行的"开始"与"结束"必须落在同一条过程消息上（同 key 覆盖），否则会留一颗永久转圈的点 */
    @Test
    fun `同一次运行的开始与结束落在同一条过程消息上`() {
        ChatStateHolder.addRunCard("run_2", "PYTHON")
        assertEquals(AgentStep.State.RUNNING, firstStep("run:run_2").state)

        ChatStateHolder.finishRunCard("run_2", output = "hello", tail = "退出码 0")

        val carriers = messages.filter { m -> m.trace.any { it.key == "run:run_2" } }
        assertEquals(1, carriers.size)
        assertEquals(1, carriers[0].trace.size)
        assertEquals(AgentStep.State.OK, carriers[0].trace[0].state)
        assertTrue("收尾信息要能看见", carriers[0].trace[0].detail.contains("退出码 0"))
    }

    // ===== 成本行的归属（2026-10-10） =====

    /**
     * 成本行挂「过程」而不是正文气泡。
     *
     * 真机症状（用户反馈："你把 token 显示那些放到输出气泡里了"）：正文是**流式**先落地的
     * （[ChatStateHolder.appendAiDelta]），收尾才拿得到 usage；收尾若把 usage 补到正文气泡上，
     * 过程卡永远拿不到成本行，而正文气泡会凭空多出一行 token —— 那一行的设计本意只是
     * "没有过程卡的纯闲聊"的兜底出口（见 ChatBubble 的 InlineCostText）。
     */
    @Test
    fun `收尾的成本行落在过程消息上而不是正文气泡`() {
        ChatStateHolder.add(isUser = true, content = "生成一个 HTML 项目")
        ChatStateHolder.upsertTrace(think(0, AgentStep.State.RUNNING))
        ChatStateHolder.appendAiDelta("好的，我") // ← 流式正文气泡先出现
        ChatStateHolder.upsertTrace(think(0, AgentStep.State.OK))

        ChatStateHolder.finalizeLastAi(
            fullContent = "好的，我已经生成好了",
            usage = MsgUsage(inputTokens = 1200, outputTokens = 900, modelCalls = 1, elapsedMs = 1234L),
            turn = 1,
        )

        val traceMsg = messages.first { it.trace.isNotEmpty() }
        val replyMsg = messages.last()
        assertTrue("正文应另起一条气泡，而不是并进过程消息", replyMsg.id != traceMsg.id)
        assertEquals("成本行必须挂在过程消息上", 1200, traceMsg.usage?.inputTokens)
        assertEquals("正文气泡不该带成本行（气泡内那行只是纯闲聊的兜底出口）", null, replyMsg.usage)
    }

    // ===== 过程卡片的可视步骤（2026-10-10） =====

    /**
     * 文件操作步骤**不会被后置的命令步骤挤出可视区**。
     *
     * 真机症状（用户反馈："文件操作的过程也看不到了"）：一轮 36 步里 `保存代码文件` 集中在前段，
     * 收尾阶段一堆 `运行命令` 排在最后；旧实现 `takeLast(6)` 只留最后 6 条 ⇒
     * 用户看到的全是跑命令，文件操作凭空消失。
     */
    @Test
    fun `文件操作步骤不会被后置的命令步骤挤出可视区`() {
        val steps = listOf(saveStep("call_0")) + (1..8).map { cmdStep("call_$it") }

        val shown = visibleTraceSteps(steps)

        assertTrue("文件操作必须始终可见", shown.any { it.key == "tool:call_0" })
        assertEquals("最近 6 条 + 那条文件操作", 7, shown.size)
        assertEquals("必须保持发生顺序，不重排", "tool:call_0", shown.first().key)
    }

    /** 步骤数没超预算时原样返回（别顺手把"本来就没被挤"的正常情况改掉） */
    @Test
    fun `步骤不超过预算时原样返回`() {
        val steps = (1..6).map { cmdStep("call_$it") }
        assertEquals(steps, visibleTraceSteps(steps))
    }

    /** 点开「看全部」后必须给出完整序列 —— 否则那行折叠提示就是个死胡同（用户反馈的问题） */
    @Test
    fun `展开全部时返回完整步骤序列`() {
        val steps = listOf(saveStep("call_0")) + (1..8).map { cmdStep("call_$it") }
        assertEquals(steps, visibleTraceSteps(steps, showAll = true))
    }

    // ===== 构造器 =====

    /** 一条带文件产物的步骤（`保存代码文件` 这类"用户最想知道它动了什么"的步骤） */
    private fun saveStep(callId: String) = AgentStep.tool(
        callId = callId,
        name = "save_code_file",
        state = AgentStep.State.OK,
        argsRaw = "{\"file\":\"index.ink\"}",
        artifacts = listOf(
            AgentStep.Artifact("/mnt/download/demo/index.ink", "index.ink", AgentStep.Action.CREATE),
        ),
    )

    /** 一条不带产物的普通步骤（命令 / 思考这类会把文件操作挤出窗口的"后置步骤"） */
    private fun cmdStep(callId: String) = AgentStep.tool(
        callId = callId,
        name = "run_shell",
        state = AgentStep.State.OK,
        argsRaw = "{\"command\":\"ls\"}",
        result = "退出码 0",
    )

    private fun firstStep(key: String): AgentStep =
        messages.first { m -> m.trace.any { it.key == key } }.trace.first { it.key == key }

    private fun think(round: Int, state: AgentStep.State) =
        AgentStep.thinking(round = round, detail = "", state = state)

    private fun toolStep(
        callId: String,
        name: String,
        state: AgentStep.State,
        result: String? = null,
    ) = AgentStep.tool(callId = callId, name = name, state = state, argsRaw = "{}", result = result)
}
