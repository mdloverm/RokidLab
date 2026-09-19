package com.rokidlab.phone.store

import com.rokidlab.phone.ai.AgentStep
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 聊天历史落盘格式（[ChatHistoryStore]）的回归测试。
 *
 * 为什么值得锁：#9 之前每次追加消息都**重写整份 JSON 数组**（O(n²) 写放大）且在主线程做，
 * 改成 JSONL 增量写 + 后台串行落盘后，"后写覆盖先写"、"旧格式迁移不丢历史"、
 * "崩溃留下的半行不毁掉整份历史"这三条语义一旦漂移，用户看到的是**聊天记录静默丢失**。
 */
class ChatHistoryStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun msg(
        id: Long,
        content: String,
        isUser: Boolean = false,
        isStatus: Boolean = false,
        imageUrl: String? = null,
        trace: List<AgentStep> = emptyList(),
    ) = ChatMsg(
        id = id,
        isUser = isUser,
        content = content,
        time = "10:00",
        isStatus = isStatus,
        imageUrl = imageUrl,
        trace = trace,
    )

    private fun steps() = listOf(
        AgentStep(key = "think:0", kind = AgentStep.Kind.THINKING, title = "", detail = "先看看要调什么"),
        AgentStep(
            key = "tool:call_1",
            kind = AgentStep.Kind.TOOL,
            title = "control_music",
            detail = "{\"songName\":\"西厢\"} → 已开始播放《西厢》",
            state = AgentStep.State.OK,
        ),
        AgentStep(
            key = "tool:call_2",
            kind = AgentStep.Kind.TOOL,
            title = "install_aiui_project",
            detail = "→ 工具执行失败: 眼镜未连接",
            state = AgentStep.State.FAILED,
        ),
    )

    private fun newFile(name: String = "chat_history.json"): File = File(tmp.newFolder(), name)

    private fun legacyArrayJson(vararg msgs: ChatMsg): String {
        val arr = JSONArray()
        msgs.forEach { arr.put(JSONObject(ChatHistoryStore.toLine(it))) }
        return arr.toString()
    }

    // ── 往返与覆盖语义 ──

    @Test
    fun `单条往返保留全部字段`() {
        val src = msg(7, "你好", isUser = true, imageUrl = "https://x/y.png")
        val back = ChatHistoryStore.parse(ChatHistoryStore.toLine(src)).single()
        assertEquals(7L, back.id)
        assertTrue(back.isUser)
        assertEquals("你好", back.content)
        assertEquals("10:00", back.time)
        assertEquals("https://x/y.png", back.imageUrl)
    }

    @Test
    fun `imageUrl 缺省时落盘不含该字段且回放为 null`() {
        val src = msg(1, "纯文本")
        assertFalse("缺省 imageUrl 不应写入字段", ChatHistoryStore.toLine(src).contains("imageUrl"))
        assertNull(ChatHistoryStore.parse(ChatHistoryStore.toLine(src)).single().imageUrl)
    }

    @Test
    fun `同 id 后写覆盖先写且位置不变`() {
        // finalizeLastAi 的落盘形态：先追加正文首版，再追加同 id 的完整版
        val text = listOf(
            ChatHistoryStore.toLine(msg(1, "你是")),
            ChatHistoryStore.toLine(msg(2, "在吗", isUser = true)),
            ChatHistoryStore.toLine(msg(1, "你好，我是乐奇")),
        ).joinToString("\n")
        val out = ChatHistoryStore.parse(text)
        assertEquals("覆盖行不应新增条目", 2, out.size)
        assertEquals("位置沿用首次出现", 1L, out[0].id)
        assertEquals("你好，我是乐奇", out[0].content)
        assertEquals(2L, out[1].id)
    }

    @Test
    fun `无 id 的条目按独立条目保留不去重`() {
        val text = listOf(
            "{\"content\":\"a\"}",
            "{\"content\":\"b\"}",
        ).joinToString("\n")
        val out = ChatHistoryStore.parse(text)
        assertEquals(2, out.size)
        assertEquals(listOf("a", "b"), out.map { it.content })
    }

    // ── 崩溃/脏数据容错 ──

    @Test
    fun `非法行与空行被跳过而非整体失败`() {
        val text = listOf(
            ChatHistoryStore.toLine(msg(1, "第一条")),
            "",
            "这不是 JSON",
            "{}",
            ChatHistoryStore.toLine(msg(2, "第二条")),
        ).joinToString("\n")
        val out = ChatHistoryStore.parse(text)
        assertEquals(2, out.size)
        assertEquals(listOf("第一条", "第二条"), out.map { it.content })
    }

    @Test
    fun `写入中途被截断的半行不影响已落盘历史`() {
        // 崩溃时可能留下 `{"id":3,"conte` 这样的半行
        val text = ChatHistoryStore.toLine(msg(1, "已保存")) + "\n" + "{\"id\":3,\"conte"
        val out = ChatHistoryStore.parse(text)
        assertEquals(1, out.size)
        assertEquals("已保存", out[0].content)
    }

    @Test
    fun `上限之外的旧消息被裁掉只留最新`() {
        val text = (1..10).joinToString("\n") { ChatHistoryStore.toLine(msg(it.toLong(), "m$it")) }
        val out = ChatHistoryStore.parse(text, maxHistory = 3)
        assertEquals(listOf("m8", "m9", "m10"), out.map { it.content })
    }

    // ── 旧格式迁移 ──

    @Test
    fun `旧版 JSON 数组格式被识别并完整迁移不丢历史`() {
        val legacy = legacyArrayJson(msg(1, "旧一"), msg(2, "旧二", isUser = true), msg(3, "旧三"))
        assertTrue(ChatHistoryStore.isLegacyFormat(legacy))
        val out = ChatHistoryStore.parse(legacy)
        assertEquals(3, out.size)
        assertEquals(listOf("旧一", "旧二", "旧三"), out.map { it.content })
        assertTrue(out[1].isUser)
    }

    @Test
    fun `旧格式数组内含非法元素时跳过该元素而非整份丢弃`() {
        val legacy = "[{\"id\":1,\"content\":\"ok\"}, \"裸字符串\", {\"id\":2,\"content\":\"ok2\"}]"
        val out = ChatHistoryStore.parse(legacy)
        assertEquals(listOf("ok", "ok2"), out.map { it.content })
    }

    @Test
    fun `迁移写回 JSONL 后不再是旧格式且可继续增量追加`() {
        val file = newFile()
        val legacy = legacyArrayJson(msg(1, "旧一"), msg(2, "旧二"))
        file.writeText(legacy)

        // 模拟 ChatStateHolder.load() 的迁移动作
        val loaded = ChatHistoryStore.readHistory(file)
        ChatHistoryStore.rewrite(file, loaded.map { ChatHistoryStore.toLine(it) })

        val afterMigration = file.readText()
        assertFalse("迁移后首字符不应再是 '['", ChatHistoryStore.isLegacyFormat(afterMigration))
        ChatHistoryStore.appendLine(file, ChatHistoryStore.toLine(msg(3, "新三")))
        assertEquals(listOf("旧一", "旧二", "新三"), ChatHistoryStore.readHistory(file).map { it.content })
    }

    // ── 过程（思考 / 工具调用）字段 ──

    @Test
    fun `过程步骤往返保留顺序键类型状态与摘要`() {
        val src = msg(9, "已为你播放", trace = steps())
        val back = ChatHistoryStore.parse(ChatHistoryStore.toLine(src)).single()
        assertEquals(3, back.trace.size)
        assertEquals(listOf("think:0", "tool:call_1", "tool:call_2"), back.trace.map { it.key })
        assertEquals(AgentStep.Kind.THINKING, back.trace[0].kind)
        assertEquals(AgentStep.Kind.TOOL, back.trace[1].kind)
        assertEquals("control_music", back.trace[1].title)
        assertEquals(AgentStep.State.OK, back.trace[1].state)
        assertEquals(AgentStep.State.FAILED, back.trace[2].state)
        assertTrue(
            "结果摘要要能往返",
            back.trace[1].detail.contains("已开始播放《西厢》"),
        )
    }

    @Test
    fun `无过程的消息落盘不含 trace 字段且回放为空列表`() {
        val src = msg(1, "普通闲聊")
        assertFalse("空过程不应写入字段", ChatHistoryStore.toLine(src).contains("trace"))
        assertTrue(ChatHistoryStore.parse(ChatHistoryStore.toLine(src)).single().trace.isEmpty())
    }

    @Test
    fun `只有过程没有正文的消息也能落盘并回放`() {
        // 工具执行阶段正文还没开始流式生成，此时消息 content 为空、只有 trace
        val src = msg(5, "", trace = steps().take(1))
        val back = ChatHistoryStore.parse(ChatHistoryStore.toLine(src)).single()
        assertEquals("", back.content)
        assertEquals(1, back.trace.size)
        assertEquals("think:0", back.trace[0].key)
    }

    @Test
    fun `过程字段脏数据降级为安全默认而不丢整条消息`() {
        val line = "{\"id\":1,\"content\":\"回复\",\"trace\":[" +
            "{\"k\":\"tool:x\",\"i\":\"NOT_A_KIND\",\"t\":\"t\",\"d\":\"d\",\"s\":\"NOT_A_STATE\"}," +
            "{\"t\":\"缺 key 应被跳过\"}," +
            "\"裸字符串\"" +
            "]}"
        val back = ChatHistoryStore.parse(line).single()
        assertEquals("回复", back.content)
        assertEquals("脏枚举要降级而非丢弃整条", 1, back.trace.size)
        assertEquals(AgentStep.Kind.TOOL, back.trace[0].kind)
        assertEquals(AgentStep.State.OK, back.trace[0].state)
    }

    @Test
    fun `旧历史无 trace 字段照样能解析（向后兼容）`() {
        val line = "{\"id\":1,\"isUser\":false,\"content\":\"老消息\",\"time\":\"10:00\",\"isStatus\":false}"
        val back = ChatHistoryStore.parse(line).single()
        assertEquals("老消息", back.content)
        assertTrue(back.trace.isEmpty())
    }

    // ── 文件级语义 ──

    @Test
    fun `appendLine 追加不破坏已有内容`() {
        val file = newFile()
        ChatHistoryStore.appendLine(file, ChatHistoryStore.toLine(msg(1, "a")))
        ChatHistoryStore.appendLine(file, ChatHistoryStore.toLine(msg(2, "b")))
        assertEquals(2, file.readText().trim().lines().size)
        ChatHistoryStore.appendLine(file, ChatHistoryStore.toLine(msg(3, "c")))
        assertEquals(listOf("a", "b", "c"), ChatHistoryStore.readHistory(file).map { it.content })
    }

    @Test
    fun `rewrite 空列表删除文件（clear 后重启不复活历史）`() {
        val file = newFile()
        ChatHistoryStore.appendLine(file, ChatHistoryStore.toLine(msg(1, "a")))
        assertTrue(file.exists())
        ChatHistoryStore.rewrite(file, emptyList())
        assertFalse("clear 必须删文件，否则重启后历史复活", file.exists())
        assertTrue(ChatHistoryStore.readHistory(file).isEmpty())
    }

    @Test
    fun `readHistory 文件不存在返回空列表`() {
        assertTrue(ChatHistoryStore.readHistory(File(tmp.root, "nope.json")).isEmpty())
    }

    @Test
    fun `appendLine 自动创建父目录`() {
        val nested = File(tmp.newFolder(), "sub/dir/history.json")
        ChatHistoryStore.appendLine(nested, ChatHistoryStore.toLine(msg(1, "a")))
        assertEquals(listOf("a"), ChatHistoryStore.readHistory(nested).map { it.content })
    }

    // ── 本轮 token 成本（「过程」区块右下角）──

    @Test
    fun `用量往返保留四个字段`() {
        val src = msg(3, "答").copy(
            usage = MsgUsage(inputTokens = 19518, outputTokens = 73, modelCalls = 2, elapsedMs = 1968L),
        )
        val back = ChatHistoryStore.parse(ChatHistoryStore.toLine(src)).single()
        assertEquals(19518, back.usage?.inputTokens)
        assertEquals(73, back.usage?.outputTokens)
        assertEquals(2, back.usage?.modelCalls)
        assertEquals(1968L, back.usage?.elapsedMs)
    }

    @Test
    fun `拿不到的用量读回 null 而不是 0`() {
        // 服务端不返回 usage 时 token 数是 null = "不知道"。落盘再读回来必须还是 null：
        // 面板上写「输入 0 / 输出 0」是**错误信息**，比不显示更糟（用户会拿它当账单）。
        val src = msg(4, "答").copy(
            usage = MsgUsage(inputTokens = null, outputTokens = null, modelCalls = 3, elapsedMs = null),
        )
        val back = ChatHistoryStore.parse(ChatHistoryStore.toLine(src)).single()
        assertNull(back.usage?.inputTokens)
        assertNull(back.usage?.outputTokens)
        assertEquals(3, back.usage?.modelCalls)
        assertNull(back.usage?.elapsedMs)
    }

    @Test
    fun `没有用量时不落字段且回放为 null`() {
        val plain = msg(5, "纯文本")
        assertFalse("无用量不该写字段", ChatHistoryStore.toLine(plain).contains("usage"))
        assertNull(ChatHistoryStore.parse(ChatHistoryStore.toLine(plain)).single().usage)
    }

    @Test
    fun `四个字段全空时整组不落盘`() {
        val empty = msg(6, "答").copy(usage = MsgUsage(null, null, null, null))
        assertFalse("全空不该留下一个空对象", ChatHistoryStore.toLine(empty).contains("usage"))
        assertNull(ChatHistoryStore.parse(ChatHistoryStore.toLine(empty)).single().usage)
    }
}
