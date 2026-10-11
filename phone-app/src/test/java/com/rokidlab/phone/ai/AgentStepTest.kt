package com.rokidlab.phone.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AgentStep] 的纯逻辑回归测试。
 *
 * 为什么值得锁：手机端「过程」卡片上**工具那一步是成功还是失败**完全由
 * [AgentStep.isFailureResult] 从工具的返回文案里推断 —— 工具契约是「回一句人话」而不是抛异常，
 * 所以这里一旦把正常返回误判成失败，用户看到的每一行工具记录都会挂红叉（反之失败被当成功，
 * 就完全失去了"过程可见"的意义）。
 */
class AgentStepTest {

    // ── 失败判定 ──

    @Test
    fun `框架自己产出的失败文案被判定为失败`() {
        assertTrue(
            AgentStep.isFailureResult(
                "工具执行失败: connect timeout。请先分析失败原因再决定下一步：参数是否正确？"
            )
        )
        assertTrue(AgentStep.isFailureResult("工具 install_apk 被安全策略拦截：外部副作用未确认。"))
        assertTrue(AgentStep.isFailureResult("unknown tool: music_play"))
        assertTrue(AgentStep.isFailureResult("未知工具: music_play"))
    }

    @Test
    fun `空结果是失败`() {
        assertTrue(AgentStep.isFailureResult(null))
        assertTrue(AgentStep.isFailureResult(""))
        assertTrue(AgentStep.isFailureResult("   "))
    }

    @Test
    fun `业务上不理想但正常返回的文案算成功`() {
        // 这些是工具的正常返回，只是结果不理想：不该挂红叉，否则过程卡片全是失败噪音
        assertFalse(AgentStep.isFailureResult("没有找到歌曲《不存在》，请换个歌名试试"))
        assertFalse(AgentStep.isFailureResult("当前没有正在播放的音乐：先调用 control_music。"))
        assertFalse(AgentStep.isFailureResult("已开始播放《西厢》 - 后弦"))
    }

    // ── 覆盖键与状态 ──

    @Test
    fun `同一工具调用的两段式共用同一个覆盖键`() {
        val running = AgentStep.tool("call_1", "control_music", AgentStep.State.RUNNING, argsRaw = "{\"songName\":\"西厢\"}")
        val done = AgentStep.tool("call_1", "control_music", AgentStep.State.OK, argsRaw = "{\"songName\":\"西厢\"}", result = "已开始播放《西厢》")
        assertEquals("先 RUNNING 后完成必须能覆盖同一行", running.key, done.key)
        assertEquals("tool:call_1", running.key)
        assertEquals(AgentStep.State.RUNNING, running.state)
        assertEquals(AgentStep.State.OK, done.state)
    }

    @Test
    fun `不同轮次的同名工具不会互相覆盖`() {
        val a = AgentStep.tool("call_1", "read_code_file", AgentStep.State.OK)
        val b = AgentStep.tool("call_2", "read_code_file", AgentStep.State.OK)
        assertFalse(a.key == b.key)
    }

    @Test
    fun `思考步骤按轮次共用一个覆盖键`() {
        val a = AgentStep.thinking(0)
        val b = AgentStep.thinking(0, detail = "推理中", state = AgentStep.State.OK)
        val c = AgentStep.thinking(1)
        assertEquals(a.key, b.key)
        assertFalse(a.key == c.key)
    }

    @Test
    fun `思考步骤标题恒为空由 UI 本地化`() {
        // 服务层不产出面向用户的文案（i18n 归属 UI），标题必须留给 UI 填
        assertEquals("", AgentStep.thinking(0).title)
        assertEquals("", AgentStep.thinking(0, detail = "x", state = AgentStep.State.OK).title)
    }

    // ── 摘要压缩 ──

    @Test
    fun `多行结果被压成一行避免卡片高度失控`() {
        val step = AgentStep.tool(
            "call_1", "adb_shell", AgentStep.State.OK,
            result = "line1\nline2\n\nline3   spaced",
        )
        assertFalse("不该残留换行", step.detail.contains('\n'))
        assertTrue(step.detail.contains("line1 line2 line3 spaced"))
    }

    @Test
    fun `超长结果被截断并加省略号`() {
        val step = AgentStep.tool("call_1", "dumpsys", AgentStep.State.OK, result = "a".repeat(2000))
        assertTrue(step.detail.length <= AgentStep.MAX_DETAIL_CHARS + 8)
        assertTrue(step.detail.endsWith("…"))
    }

    @Test
    fun `超长参数被截断`() {
        // 用非内容类工具测截断：内容类工具（save_code_file 等）的参数走白名单提取，
        // 根本不会把原文拍进 detail（见「防泄漏」一节），拿它测截断等于测了个空串
        val step = AgentStep.tool(
            "call_1", "adb_shell", AgentStep.State.RUNNING,
            argsRaw = "{\"command\":\"" + "x".repeat(5000) + "\"}",
        )
        assertTrue(step.detail.endsWith("…"))
    }

    @Test
    fun `截断不切断 UTF-16 代理对`() {
        // emoji 占两个 char：从中间切会留下孤立高代理项，Compose 会渲染成方块
        val emoji = "\uD83D\uDE00"
        val args = "{\"x\":\"" + emoji.repeat(AgentStep.MAX_ARGS_CHARS) + "\"}"
        val step = AgentStep.tool("call_1", "tool", AgentStep.State.RUNNING, argsRaw = args)
        val cut = step.detail.removeSuffix("…")
        assertFalse(
            "末字符不能是孤立的高代理项",
            cut.isNotEmpty() && Character.isHighSurrogate(cut[cut.length - 1]),
        )
    }

    // ── 知识库自动检索（不是模型调的工具） ──

    @Test
    fun `知识库检索键固定且标题留给 UI 本地化`() {
        val step = AgentStep.knowledge(hitCount = 2, sources = listOf("《问.txt》第1块"), docCount = 1)
        assertEquals("一轮最多一条，不需要区分轮次", "kb:auto", step.key)
        assertEquals(AgentStep.Kind.KNOWLEDGE, step.kind)
        // 与 thinking 同规矩：服务层不产出面向用户的文案
        assertEquals("", step.title)
        assertEquals(AgentStep.State.OK, step.state)
    }

    @Test
    fun `知识库检索必须如实标为 KNOWLEDGE 而不是 TOOL`() {
        // 复用 TOOL 会让"模型为什么没调检索工具"这种排查被误导（两者语义不同）
        assertFalse(AgentStep.knowledge(1, listOf("《a》第1块"), 1).kind == AgentStep.Kind.TOOL)
    }

    @Test
    fun `命中时把全部来源标注写进 detail 供用户核对出处`() {
        val step = AgentStep.knowledge(
            hitCount = 3,
            sources = listOf("《问.txt》第1块", "《问.txt》第15块", "《手册.txt》第2块"),
            docCount = 2,
        )
        assertTrue(step.detail.contains("《问.txt》第1块"))
        assertTrue("第二份文档的来源也要在，否则用户核对不到", step.detail.contains("《手册.txt》第2块"))
        assertFalse(step.detail.contains('\n'))
    }

    @Test
    fun `未命中时说明库里有多少份文档`() {
        // 用户最需要区分的是"库里没有"还是"有但没匹配上" —— 这句话就是那个区分
        val step = AgentStep.knowledge(hitCount = 0, sources = emptyList(), docCount = 3)
        assertTrue(step.detail.contains("3"))
        assertTrue(step.detail.contains("未匹配"))
    }

    @Test
    fun `来源过多时压成一行并截断`() {
        val many = (1..200).map { "《很长的文档名$it》第${it}块" }
        val step = AgentStep.knowledge(hitCount = many.size, sources = many, docCount = 1)
        assertFalse("换行会让过程卡片高度失控", step.detail.contains('\n'))
        assertTrue(step.detail.endsWith("…"))
    }

    // ── 文件产物（2026-10-10）：让「改了哪个文件」可点开预览 ──

    private val projBase = "/data/user/0/com.rokidlab.phone/files/aiui_projects/demo"

    @Test
    fun `写文件类工具提取出相对路径并解析成绝对路径`() {
        val a = AgentStep.artifactOf(
            "apply_patch",
            """{"project":"demo","path":"pages/index/index.ink","patch":"@@"}""",
            projBase,
        )
        assertEquals(1, a.size)
        assertEquals(AgentStep.Action.WRITE, a[0].action)
        assertEquals("$projBase/pages/index/index.ink", a[0].path)
        // label = **项目名 + 项目内相对路径**（2026-10-10 用户反馈）：只给 `pages/index/index.ink`
        // 时多项目并行分不清归属，光有文件名（`index.ink`）更看不出来 —— 必须带项目名。
        assertEquals("demo/pages/index/index.ink", a[0].label)
    }

    @Test
    fun `没有基准目录时不给相对路径编造绝对路径`() {
        // ⚠️ 这是本组测试里最要紧的一条：宁可少显示一个芯片，也不能给一个打不开的路径 ——
        //   用户会去点，然后以为文件丢了。
        val a = AgentStep.artifactOf("apply_patch", """{"path":"pages/index.ink"}""", null)
        assertTrue(a.isEmpty())
    }

    @Test
    fun `绝对路径不依赖基准目录`() {
        val a = AgentStep.artifactOf("read_code_file", """{"file":"/sdcard/Download/a.txt"}""")
        assertEquals("/sdcard/Download/a.txt", a[0].path)
        assertEquals(AgentStep.Action.READ, a[0].action)
    }

    @Test
    fun `未登记的工具不猜产物`() {
        assertTrue(AgentStep.artifactOf("play_song", """{"songName":"西厢"}""", "/base").isEmpty())
    }

    @Test
    fun `源码正文里的路径不会被当成产物`() {
        // save_code_file 的 content 是源码，里面出现的 import 路径不是"它操作了这个文件" ——
        // 这条若失效，用户会看到一堆他没让AI碰过的文件芯片。
        val a = AgentStep.artifactOf(
            "list_files",
            """{"project":"demo","path":"app.js","content":"import x from '../src/a.js'"}""",
            projBase,
        )
        assertEquals(1, a.size)
        assertEquals("demo/app.js", a[0].label)
    }

    @Test
    fun `读与写的动作如实区分`() {
        val read = AgentStep.artifactOf("read_code_file", """{"file":"a.txt"}""", projBase)[0]
        val write = AgentStep.artifactOf("edit_text_file", """{"path":"a.txt"}""", projBase)[0]
        val del = AgentStep.artifactOf("delete_file", """{"path":"a.txt"}""", projBase)[0]
        assertEquals(AgentStep.Action.READ, read.action)
        assertEquals(AgentStep.Action.WRITE, write.action)
        assertEquals(AgentStep.Action.DELETE, del.action)
    }

    @Test
    fun `同一文件被多个键命中时取更强的动作`() {
        // model 同时给了 path 与 file 且都指向 a.txt：后写覆盖前写会把"改了"显示成"读了"
        val a = AgentStep.artifactOf(
            "restore_file",
            """{"file":"a.txt","path":"a.txt"}""",
            projBase,
        )
        assertEquals(1, a.size)
        assertEquals(AgentStep.Action.WRITE, a[0].action)
    }

    @Test
    fun `逗号分隔的多路径各自成一条产物`() {
        val a = AgentStep.artifactOf("apply_patch", """{"path":"a.txt, b.txt"}""", projBase)
        assertEquals(2, a.size)
    }

    @Test
    fun `参数不是合法 JSON 时静默返回空而不是崩`() {
        // 模型输出被 max_tokens 截断 → 参数 JSON 解析失败是**常态**，不是异常
        assertTrue(AgentStep.artifactOf("apply_patch", """{"path":"a.txt""", projBase).isEmpty())
    }

    @Test
    fun `工具步骤自动带上产物且两段式覆盖同一行`() {
        val args = """{"project":"demo","path":"a.txt"}"""
        val running = AgentStep.tool(
            "call_9", "edit_text_file", AgentStep.State.RUNNING,
            argsRaw = args, artifactBaseDir = projBase,
        )
        val done = AgentStep.tool(
            "call_9", "edit_text_file", AgentStep.State.OK,
            argsRaw = args, result = "已保存", artifactBaseDir = projBase,
        )
        assertEquals(running.key, done.key)
        assertEquals(1, done.artifacts.size)
        assertEquals("$projBase/a.txt", done.artifacts[0].path)
    }

    @Test
    fun `等待确认的步骤也带产物`() {
        // 审批可能阻塞 40 秒，这段时间用户最想知道"它要动哪个文件"。
        // ⚠️ awaitingConfirm 目前不接收基准目录 ⇒ 相对路径解析不出来就是空列表
        //   （与 artifactOf 的判据一致：不编造路径）。真正能带出产物的是
        //   绝对路径形态，以及 tool() 那条通道（它拿得到基准）。
        val rel = AgentStep.awaitingConfirm(
            "call_1", "delete_file", argsRaw = """{"path":"a.txt"}""",
        )
        assertTrue("没基准就不该编造路径", rel.artifacts.isEmpty())
        assertEquals(AgentStep.State.AWAITING_CONFIRM, rel.state)

        val abs = AgentStep.awaitingConfirm(
            "call_2", "delete_file", argsRaw = """{"path":"/sdcard/Download/a.txt"}""",
        )
        assertEquals(1, abs.artifacts.size)
        assertEquals(AgentStep.Action.DELETE, abs.artifacts[0].action)
    }

    // ── 防泄漏（2026-10-10 用户反馈：对话里出现了生成结果的内容）──

    @Test
    fun `save_code_file 的参数只展示项目与文件名绝不展示源码`() {
        val secret = "fun leak() = 42"
        val step = AgentStep.tool(
            "call_1", "save_code_file", AgentStep.State.RUNNING,
            argsRaw = """{"project":"demo","file":"app.js","content":"$secret"}""",
        )
        assertFalse("源码正文绝不能漏进过程行", step.detail.contains(secret))
        assertFalse(step.detail.contains("content"))
        assertTrue("目标键要留住，用户得知道在写哪个文件", step.detail.contains("app.js"))
    }

    @Test
    fun `内容工具参数 JSON 非法时宁可不显示也不漏半行源码`() {
        // 模型输出被 max_tokens 截断 → 参数解析失败是常态；
        // 此时若退回原始字符串截断，diff/源码开头就会漏进过程卡片。
        val step = AgentStep.tool(
            "call_1", "apply_patch", AgentStep.State.RUNNING,
            argsRaw = """{"path":"a.txt","patch":"@@ -1 +@@"""
        )
        assertEquals("", step.detail)
    }

    @Test
    fun `运行命令的参数只留命令首行绝不展示脚本体`() {
        // 模型常用 `python3 - <<'EOF' …` 整段脚本做校验：命令要留（对齐 TRAE 的形态），
        // 但 heredoc 脚本体与 JSON 外壳都不该进过程卡片。
        // 命令类工具（2026-10-10 方案 B）走专用命令卡：命令进 command，detail 恒为空。
        val argsRaw =
            "{\"command\":\"cd /mnt/download/demo && python3 - <<'EOF'\\nimport json\\nEOF\"}"
        val step = AgentStep.tool("call_1", "run_shell", AgentStep.State.RUNNING, argsRaw = argsRaw)
        assertEquals("命令卡正文不重复塞进 detail", "", step.detail)
        assertTrue("命令本身要留住", step.command!!.startsWith("cd /mnt/download/demo"))
        assertFalse("heredoc 脚本体不能进过程卡片", step.command!!.contains("import json"))
        assertFalse("不该留 JSON 外壳", step.command!!.contains("\"command\""))
    }

    @Test
    fun `运行命令的终态带可展开输出但摘掉给模型的提示段`() {
        // 方案 B：输出有专属的收起/展开容器 ⇒ 真实输出（退出码/stdout）留进 output；
        // 但工具尾部追加的 [工作目录] / [共享目录] 是**给模型看的**提示，必须摘掉（外泄）。
        val step = AgentStep.tool(
            "call_1", "run_shell", AgentStep.State.OK,
            argsRaw = "{\"command\":\"ls -R .\"}",
            result = "退出码 0\n\n[标准输出]\n./pages/index:\nindex.ink" +
                "\n\n[工作目录] /（run_shell / start_web_preview 省略 cwd 时沿用此目录）" +
                "\n\n[共享目录] /mnt/lab 当前指向 App 私有目录（未授予「所有文件访问」），" +
                "用户看不到；要把文件交给用户，请引导其到「设置 → 本机执行环境」授权后再写入 /mnt/lab。",
        )
        assertEquals("命令卡正文不重复塞进 detail", "", step.detail)
        assertTrue("退出码要留住（收起时的摘要行）", step.output!!.startsWith("退出码 0"))
        assertTrue("真实输出要留住（展开后可见）", step.output!!.contains("index.ink"))
        assertFalse("[工作目录] 是给模型的提示", step.output!!.contains("[工作目录]"))
        assertFalse("[共享目录] 的内部话术绝不能外泄", step.output!!.contains("请引导其到"))
    }

    @Test
    fun `还在跑的命令没有输出副本`() {
        // result == null（RUNNING）时不能凭空造一个输出，否则卡片会显示一个空的"展开输出"
        val step = AgentStep.tool(
            "call_1", "run_shell", AgentStep.State.RUNNING,
            argsRaw = "{\"command\":\"sleep 30\"}",
        )
        assertNull(step.output)
    }

    @Test
    fun `运行脚本按脚本名展示命令`() {
        // run_script 的参数是 `name`（不是 command）：命令卡要显示脚本名本身
        val step = AgentStep.tool(
            "call_1", "run_script", AgentStep.State.RUNNING,
            argsRaw = """{"name":"demo.sh","timeoutSec":60}""",
        )
        assertEquals("", step.detail)
        assertEquals("demo.sh", step.command)
    }

    @Test
    fun `启动网页预览只留命令与状态行不搬给模型看的提示`() {
        // 真机截屏（2026-10-10）：没登记白名单 ⇒ 整段 `{"command": …}` 原样进卡片；
        // 结果里的 `[工作目录]` / `[服务日志…]` 与"请把地址告诉用户"是**给模型看的**提示，
        // 搬进用户可见的过程卡片就是刷屏 + 内部提示外泄 ⇒ 只留状态首行，不提供可展开输出。
        val step = AgentStep.tool(
            "call_1", "start_web_preview", AgentStep.State.OK,
            argsRaw = """{"command":"python3 -m http.server ${'$'}PREVIEW_PORT --directory /mnt/download/html-test","cwd":"/"}""",
            result = "网页预览已启动：http://127.0.0.1:46145（id=pv-1）\n" +
                "聊天窗口已出现预览入口，用户点开即可看到页面 —— 请把地址与用法一并告诉用户。\n" +
                "[工作目录] /（run_shell / start_web_preview 省略 cwd 时沿用此目录）\n" +
                "[服务日志（最近几行）] proot warning: can't chdir",
        )
        assertEquals("命令卡正文不重复塞进 detail", "", step.detail)
        assertTrue("命令本身要留住", step.command!!.startsWith("python3 -m http.server"))
        assertFalse("不该留 JSON 外壳", step.command!!.contains("\"command\""))
        assertEquals("只留状态首行（URL 在回复里另有交代）", "网页预览已启动：http://127.0.0.1:46145（id=pv-1）", step.output)
        assertFalse("给模型看的操作提示不能外泄", step.output!!.contains("请把地址"))
        assertFalse("服务日志不能外泄", step.output!!.contains("proot warning"))
    }

    @Test
    fun `未登记的工具也不把 JSON 外壳拍进过程卡片`() {
        // 真机截屏（2026-10-10「过程显示太乱」）：兜底分支原样 compact 了 argsRaw，
        // 于是 `{"url": "…"}` / `{"command": "…"}` 的花括号引号全进了卡片。
        val step = AgentStep.tool(
            "call_1", "fetch_webpage", AgentStep.State.OK,
            argsRaw = """{"url":"http://127.0.0.1:46145/index.html"}""",
            result = "网页读取失败：这个地址在内网或本机",
        )
        assertFalse("不该留 JSON 外壳", step.detail.contains("{\""))
        assertTrue("目标本身要留住", step.detail.contains("http://127.0.0.1:46145/index.html"))
    }

    @Test
    fun `未登记工具的内容键整个跳过其余标量照常显示`() {
        val step = AgentStep.tool(
            "call_1", "some_tool", AgentStep.State.RUNNING,
            argsRaw = """{"name":"demo","content":"SECRET_SRC_LINE1\nLINE2"}""",
        )
        assertFalse("内容键不能进过程卡片", step.detail.contains("SECRET_SRC_LINE1"))
        assertTrue("其余标量照常显示", step.detail.contains("name: demo"))
    }

    @Test
    fun `有写产物芯片的步骤 detail 只留结果句`() {
        val step = AgentStep.tool(
            "call_1", "edit_text_file", AgentStep.State.OK,
            argsRaw = """{"path":"a.txt","content":"SECRET_SRC"}""",
            result = "已保存 a.txt（12 行）",
            artifactBaseDir = projBase,
        )
        assertFalse(step.detail.contains("SECRET_SRC"))
        assertEquals("已保存 a.txt（12 行）", step.detail)
        assertEquals(1, step.artifacts.size)
    }

    @Test
    fun `只读产物步骤 detail 为空（文件内容点芯片看）`() {
        // read_code_file 的 result 本身就是文件正文——把它拍进过程行等于又一次内容泄漏，
        // 而且正是用户投诉的形态。文件身份与内容都由芯片承载。
        val step = AgentStep.tool(
            "call_1", "read_code_file", AgentStep.State.OK,
            argsRaw = """{"file":"$projBase/a.txt"}""",
            result = "整个文件的正文正文正文…",
        )
        assertEquals("", step.detail)
        assertEquals(1, step.artifacts.size)
        assertEquals(AgentStep.Action.READ, step.artifacts[0].action)
    }

    @Test
    fun `框架行为与自省事件用各自独立的覆盖键`() {
        // 三类新步骤若共用一个 key，重试/自省/注入会互相覆盖掉，用户只看到最后一条
        assertEquals(AgentStep.STATUS_STEP_KEY, AgentStep.statusProgress("识别中").key)
        assertEquals(AgentStep.STATUS_STEP_KEY, AgentStep.statusDone("识别失败").key)
        assertEquals("fw:token_exhausted", AgentStep.framework("x", "token_exhausted").key)
        assertEquals("reflect:repeat_call", AgentStep.reflect("repeat_call", "同一调用第 2 次").key)
        assertEquals("inject:memory", AgentStep.contextInjected("memory", "已注入长期记忆").key)
    }

    @Test
    fun `流程状态的失败态必须如实标记为 FAILED`() {
        // 把失败画成绿色对勾 = 撒谎：用户会以为照片已经处理好了。
        // （拍照答题的 chat_photo_failed / chat_ocr_empty 走的就是 ok=false）
        assertEquals(AgentStep.State.RUNNING, AgentStep.statusProgress("正在识别…").state)
        assertEquals(AgentStep.State.OK, AgentStep.statusDone("识别完成").state)
        assertEquals(AgentStep.State.FAILED, AgentStep.statusDone("没识别出文字", ok = false).state)
    }

    @Test
    fun `收尾阶段的占位与终态共用覆盖键`() {
        assertEquals(
            AgentStep.SUMMARY_STEP_KEY,
            AgentStep.summaryRunning().key,
        )
        assertEquals(
            AgentStep.summaryRunning().key,
            AgentStep.summaryDone(produced = 2).key,
        )
        assertEquals(AgentStep.Kind.SUMMARY, AgentStep.summaryRunning().kind)
    }

    @Test
    fun `本地程序运行的开始与结束共用覆盖键`() {
        val a = AgentStep.runStarted("PYTHON", "run_1")
        val b = AgentStep.runFinished("run_1", "退出码 0")
        assertEquals(a.key, b.key)
        assertFalse(a.key == AgentStep.runStarted("PYTHON", "run_2").key)
        assertEquals(AgentStep.State.RUNNING, a.state)
        assertEquals(AgentStep.State.OK, b.state)
    }

    @Test
    fun `框架自产的过程步骤标题恒为空由UI 本地化`() {
        // 与 thinking / knowledge 同纪律：服务层不产出面向用户的文案
        assertEquals("", AgentStep.statusProgress("识别中").title)
        assertEquals("", AgentStep.framework("x", "id").title)
        assertEquals("", AgentStep.contextInjected("memory", "x").title)
        assertEquals("", AgentStep.reflect("repeat_call", "x").title)
    }
}
