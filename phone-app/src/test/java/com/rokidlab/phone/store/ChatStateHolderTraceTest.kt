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
 * 真机上有**两条互相独立**的插入路径，跟"调用了哪个工具"无关：
 *  1. `show_image` 的图片气泡（[ChatStateHolder.addImage]）；
 *  2. 拍照答题流程的状态气泡（`onStage` → [ChatStateHolder.add] /
 *     `onStageText` → [ChatStateHolder.updateLastStatus]，后者在末尾不是状态气泡时**新增**一条）。
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

    // ===== 构造器 =====

    private fun think(round: Int, state: AgentStep.State) =
        AgentStep.thinking(round = round, detail = "", state = state)

    private fun toolStep(
        callId: String,
        name: String,
        state: AgentStep.State,
        result: String? = null,
    ) = AgentStep.tool(callId = callId, name = name, state = state, argsRaw = "{}", result = result)
}
