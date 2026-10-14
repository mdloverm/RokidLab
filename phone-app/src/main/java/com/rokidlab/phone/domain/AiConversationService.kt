package com.rokidlab.phone.domain

import android.util.Log
import com.rokid.cxr.Caps
import com.rokid.cxr.link.CXRLink
import com.rokidlab.phone.R
import com.rokidlab.phone.ai.AgentStep
import com.rokidlab.phone.ai.LongTermMemoryManager
import com.rokidlab.phone.ai.ToolRegistry
import com.rokidlab.phone.design.RokidHostApp
import com.rokidlab.phone.glasses.AiChannel
import com.rokidlab.phone.glasses.ChartGlassPush
import com.rokidlab.phone.glasses.GlassesHandshake
import com.rokidlab.phone.glasses.LinkProtocol
import com.rokidlab.phone.store.VoiceBroadcast
import com.rokidlab.phone.store.VoicePrefs
import com.rokidlab.phone.store.extractEChartsSources
import com.rokidlab.phone.store.replaceEChartsBlocks
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import org.json.JSONArray
import org.json.JSONObject

/**
 * L3 domain/AiConversationService —— Phase 3 拆 `CxrLHiRokidSession` 的 AI 对话域服务。
 *
 * 职责：AI 下行主链路（sendAiTextMessage / sendAiTextViaLink：KeyDown → open →
 * ASR_Result → ASR_End → DeepSeek 工具循环 → TTS_Result → tts_play）、
 * ASR 文字分发（dispatchGlassesAsrText）、发送中断（abortCurrentAi / 代际号）、
 * 工具执行进度推送（sendGlassesProgress）。
 *
 * 依赖：L3 ConnectionService（慢速路径建链，经 session.connection）、
 * L3 AiConfigService（模型配置，经 session.aiConfig / session.getAiConfig()）、
 * L2 会话状态（cxrLink / aiCmdLock / 连接标志经 session 句柄）。
 * Session 保留同名 public 门面（聊天界面 / photoQuiz / asrBridge 零改动）。
 */
class AiConversationService(private val session: com.rokidlab.phone.glasses.CxrLHiRokidSession) {
    private companion object {
        const val TAG = "AiConversationService"

        /**
         * RAG 命中块注入提示词时的字符上限（防御性，见使用处注释）。
         *
         * 现状实际用量远低于它：`KnowledgeBase.CHUNK_SIZE = 500` × top-2 ≈ 1000 字符。
         * 定 1500 是给「调大 chunk 或提高 topN」留余量，同时保证这段注入
         * **永远**只是上下文尾部的一小块，不会变成成本大头。
         */
        const val KB_HIT_MAX_CHARS = com.rokidlab.phone.ai.ContextAssembly.KB_HIT_MAX_CHARS

        /** 「等官方对话场景打开」的基础等待窗口（ms）。官方 App 节奏：KeyDown 后 ~502ms 自开场景、
         *  ~718ms 上行 Ai_SceneStatus（2026-09-19 实测），800ms 足够覆盖**热**场景。 */
        const val SCENE_OPEN_WAIT_MS = 800L

        /**
         * **冷启动**时的追加等待窗口（ms）。
         *
         * 2026-09-21 真机实测：App 刚装完/刚拉起后的第一轮，`waitAiSceneOpen(800)` 返回 **false**，
         * 而旧逻辑照样按节奏发 `ASR_Result`/`ASR_End`；官方 `AsrEndHandler` 里有一个
         * `if (SceneUtils.aiIsRunning())` 门禁 —— 场景没起来时**不置气泡 status 4**，
         * 于是渲染闸门永不打开 ⇒ 本轮「我说的、你说的都不显示，但 TTS 正常」
         * （声音走私有 topic `tts_play`，与官方界面无关）。
         *
         * ⇒ ready=false 时再等这一段（宁可晚一两秒），仍不开才按原节奏发，并留日志便于取证。
         */
        const val SCENE_OPEN_RETRY_MS = 2_500L

        /**
         * 代码生成轮的 HTTP 读超时（毫秒）。
         * 模型产出超大工具参数 JSON（save_code_file 的 content）前可能长时间不向 SSE 下发
         * 数据，默认 30s 会在首包到达前就超时、白白重放整轮（重放等于重新生成一次大文件）。
         */
        const val CODE_GEN_READ_TIMEOUT_MS = 120_000

        /**
         * 思考过程推 UI 的最小间隔（毫秒）。
         *
         * 一次长思考的 reasoning 可能上万个 SSE delta（实测单轮 ~2.5 万字符）。每个 delta 都推一次
         * 会让手机端「过程」卡片持续重组、把主线程写爆，因此按时间节流；最终一轮结束时会再推一次
         * 完整预览（同一个覆盖键），所以节流不会丢信息，只影响过程中的刷新粒度。
         */
        const val THINKING_EMIT_INTERVAL_MS = 400L

        /**
         * 工具参数流式进度推 UI 的最小间隔（毫秒）。
         *
         * 代码生成轮模型会逐帧吐 `save_code_file` 的 content（实测单文件数万字符、上千个 delta），
         * 每个 delta 都推一次会把「过程」卡片刷爆；与思考同一套节流理由。间隔比思考略长：
         * 这里的文案只变一个数字，刷太快反而晃眼。
         */
        const val TOOL_ARG_EMIT_INTERVAL_MS = 600L

        /**
         * 兜底总结轮的最大轮数（主循环工具轮预算用尽后的收尾阶段）。
         * 每轮都会注入「禁止再调工具、直接给结论」的引导语，并把当轮 tool_calls 照常执行 ——
         * 多文件生成时模型可能仍需连续几轮 save_code_file 落盘，因此"当轮没有正文"属正常，
         * 不能提前中断（否则会截断正常的多次落盘）。
         */
        const val SUMMARY_MAX_ROUNDS = 3

        /**
         * 工具循环的「动作轮」预算：含至少一个非只读工具（落盘 / 装机 / 拨号等）的轮次。
         * 分账原因见 [MAX_READONLY_ROUNDS]。
         *
         * 2026-10-01 6→12：多文件项目（HTML+CSS+JS+README 轻松 5-8 文件）加上
         * 「落盘后运行验证→修一轮」的闭环（见 buildSystemMessage 的 save_code_file 准则），
         * 6 轮根本不够用——模型会在第 7 个文件被掐断进兜底总结，这是「执行逻辑差」的
         * 结构性来源之一。放宽靠分账+续段兜住成本，不靠无限轮。
         */
        const val MAX_ACTION_ROUNDS = 12

        /**
         * 工具循环的「只读轮」预算：整轮只调用只读工具（查资料）。
         *
         * **为什么与动作轮分账**：AIUI 任务天然「读多写少」——实测一次生成里 6 轮工具全花在
         * `list_my_aiui_apps` / `read_code_file` / `load_skill_section` 上，勘察把统一预算吃光，
         * 模型还没开始落盘就被判定该收尾，最终只能回固定兜底文案。只读轮本身很轻（单轮 2s 量级），
         * 给它单独额度比整体调大轮次更对症，也不会让「动作轮」被顺带放长。
         *
         * 为什么 8→10（2026-10-01）：aiui-dev 技能是目录式大技能——SKILL.md 要求按任务加载
         * framework/events/components/wxss/design-system/apis+域文件/checklist 等 7-8 份参考，
         * 每次 load_skill(_section) 计 1 只读轮；8 轮会全烧在读文档上还没写一行代码
         * （真机取证：color-runner 只活到 app.json）。10 给文档装载留裕量。
         */
        const val MAX_READONLY_ROUNDS = 10

        /**
         * 工具循环的轮次硬顶（兜底）。正常应由两个分账预算之一先触发；
         * 此值取两者之和（10+12），仅用于防御计数器异常导致的死循环。
         */
        const val MAX_TOTAL_ROUNDS = 22

        /**
         * **自动续做**次数上限（分账额度触顶后自动再开一段，无需用户说「继续」）。
         *
         * 为什么需要：分账额度（动作 6 / 只读 8）触顶 ≠ 任务做完，而总轮次 [MAX_TOTAL_ROUNDS]
         * 通常还有余量 —— 旧实现在触顶处直接跳出工具循环，把剩下的总轮次白扔，然后让模型
         * 在结论里"提示用户说继续"。可用户要的是"你把活干完"，不是"你告诉我该催你一下"。
         *
         * 为什么只给 2 次（2026-10-01 1→2）：每次续做都是**又一段真实的模型+工具开销**，
         * 且眼镜端在这期间是静默的（用户只看到"过程"在走）。动作轮放宽到 12 后，单段预算
         * 覆盖的多文件任务更长，但仍可能带着「验证→修一轮」闭环在第二段收尾；2 次续段
         * 基本消灭"做一半让用户说继续"。真正无界的长任务仍走 AgentTaskStore 的多轮续做。
         */
        const val MAX_AUTO_CONTINUES = 2

        /**
         * 计划驱动续做上限（todo 驱动重入，2026-10-02，补评估 C 档 #7「update_plan 无执行引擎」）。
         *
         * 行为：模型给出**无工具调用的正文回复**（它自己认为是收尾）但 [AgentTaskStore] 里
         * 活跃计划仍有 pending/in_progress 步骤时，把剩余步骤清单作为 user 消息注入、
         * 强制 `tool_choice=required`（端点支持时）原地续做 —— 这就是 Manus「todo.md 驱动
         * 续跑」的轮内等价物，与预算触顶触发的 [MAX_AUTO_CONTINUES]（额度维度）互补：
         * 一个管"预算没花完别停"，一个管"计划没做完别停"。
         *
         * 为什么限 2 次：每次续做都是真实的模型+工具开销；无限续做等于把"计划写错"
         * （模型把不可能完成的步骤列进计划）放大成死循环。2 次后如实收尾，
         * AgentTaskStore 会把剩余步骤标为可续做，用户说「继续」即可接着做。
         */
        const val MAX_PLAN_CONTINUES = 2

        /**
         * 内部标记（`[NEED_TOOLS]` / `[TOOLS]`）的**本轮升级上限**（2026-10-09，P0-1 终态护栏）。
         *
         * 背景（gps-live 真机事故）：三层降载的逃生舱**只升级一次** —— 升级后
         * `fastPathActive`/`routedToolNames`/`menuModeActive` 全被清空，第二个标记再没人接，
         * 于是 `[NEED_TOOLS]` 那 11 个字符被当成正文播进聊天气泡（还记成 `completed`）。
         *
         * 为什么是 2 而不是 1：模型喊第二次有两类原因 ——
         *  ① 第一次升级的时机它"看不见"（非探测轮升级不重建 messages，尾部的旧指令还在，
         *     见 [InternalMarkerGuard] 的说明）⇒ 它只是**重复了一遍**，第二次给全量就正常了；
         *  ② 它确实缺一个清单外能力。
         * 给到 2 次能消化第 ① 类（本轮事故就是这一类），第 ② 类由"仍喊标记 ⇒ 要求它
         * 如实说明缺什么"收尾，不再继续烧轮次。
         */
        const val MAX_MARKER_UPGRADES = 2

        /**
         * 项目规则文件（差距⑥，2026-10-01）：下载目录下用户自维护的约定文件，
         * 代码/项目类任务装配工具时注入 system（见 [buildMessages] → buildSystemMessage）。
         * 用户用任意文本编辑器写一份「框架约定/命名风格/禁用项」，生成一致性长期受益。
         * 文件不存在 = 无规则，零开销（每个任务只读一次）。
         *
         * ⚠️ 2026-10-09：常量值改由 [com.rokidlab.phone.ai.PromptGuardrails.PROJECT_RULES_FILE]
         *   提供 —— 同一个文件名有两处用途（这里读、`FileWorkspace` 那里防删），
         *   各写一份字面量会让"改了读取路径忘了改防护"变成静默失效的缺口。
         */
        const val PROJECT_RULES_RELPATH = com.rokidlab.phone.ai.PromptGuardrails.PROJECT_RULES_FILE

        /** 项目规则注入上限（字符）：规则是用户手写约定，超长多半混入了无关内容 */
        const val PROJECT_RULES_MAX_CHARS = 6000

        /**
         * 工具 Schema 自检是否已跑过（每进程一次，见 [auditToolSchemas]）。
         *
         * 服务端对 `tools[].function.parameters` 是**严格 JSON Schema 校验**，且失败是**整请求级**的：
         * 几十个工具里只要 1 个嵌套节点的形状写错，整个请求 400，**所有工具一起失效**
         * ——模型既调不了工具也拿不到正文，症状是「AI 突然不会说话了」。
         * 2026-09-15 真机事故即由 `update_plan` 的 `steps` 被写成 JSON 数组引起。
         * 这里在首次装配工具时本地校验一遍并写进 App 内日志面板，
         * 让这类「症状与原因严重脱节」的故障在本地就能定位，而不是只能靠读服务端 400 报文。
         */
        private val schemaAudited = java.util.concurrent.atomic.AtomicBoolean(false)
    }

    /**
     * 判断工具是否为「只读」—— 用于工具循环的轮次预算分账。
     *
     * 判定收口到 approval 接缝（[com.rokidlab.phone.ai.approval.ApprovalGate.isReadOnly]）：
     * 那里同时管着审批闸门，两者共用同一张风险表 + 同一张伪工具表
     * （[com.rokidlab.phone.ai.approval.PseudoTools]），不会出现「审批按只读放行、
     * 分账按动作轮计费」这种自相矛盾。
     *
     * ⚠️ 改造前这里复制了一份判定，还硬编码了 `load_skill` / `load_skill_section` /
     * `update_plan` 三个伪工具名 —— 新增伪工具时极易漏改（漏了就把只读工具算成动作轮，
     * 让"读多写少"的 AIUI 任务白吃预算，正是当初给只读轮单独分账要解决的问题）。
     */
    private fun isReadOnlyTool(name: String): Boolean =
        com.rokidlab.phone.ai.approval.ApprovalGate.isReadOnly(name)

    /**
     * 工具 Schema 本地严格校验（每进程一次，见 [schemaAudited]）。
     *
     * 校验本次装配用的工具列表，**并额外强制校验全部伪工具声明**
     * （update_plan / load_skill / load_skill_section / manage_memory）——它们可能因当前场景
     * （本地轻量、无人值守只读）没进本次列表，但同样会造成整请求 400，必须一起校验。
     * 按工具名去重，重复传入无害。结果写进 App 内日志面板（「乐奇聊天 → 工具 → 查看日志」）。
     */
    private fun auditToolSchemas(assembled: List<JSONObject>) {
        if (!schemaAudited.compareAndSet(false, true)) return
        runCatching {
            val byName = LinkedHashMap<String, JSONObject>()
            fun collect(list: List<JSONObject>) {
                list.forEach { s ->
                    val n = s.optJSONObject("function")?.optString("name").orEmpty()
                    if (n.isNotEmpty() && !byName.containsKey(n)) byName[n] = s
                }
            }
            collect(assembled)
            collect(
                listOf(
                    com.rokidlab.phone.ai.AgentPlan.schema(),
                    com.rokidlab.phone.ai.AskUser.schema(),
                    com.rokidlab.phone.ai.SkillRegistry.schema(),
                    com.rokidlab.phone.ai.SkillRegistry.sectionSchema(),
                    com.rokidlab.phone.ai.SkillRegistry.installSchema(),
                    com.rokidlab.phone.ai.SkillRegistry.listSchema(),
                    com.rokidlab.phone.ai.SkillRegistry.deleteSchema(),
                    com.rokidlab.phone.ai.LongTermMemoryManager.schema(),
                ),
            )
            val problems = com.rokidlab.phone.ai.ToolSchemaValidator.validateAll(byName.map { it.key to it.value })
            if (problems.isEmpty()) {
                com.rokidlab.phone.util.LogCollector.i(TAG, "工具 schema 自检通过：${byName.size} 个声明全部合规")
            } else {
                com.rokidlab.phone.util.LogCollector.w(
                    TAG,
                    "工具 schema 自检发现问题（会让 AI 请求被服务端整体拒绝 400）：\n" + problems.joinToString("\n"),
                )
            }
        }.onFailure {
            com.rokidlab.phone.util.LogCollector.w(TAG, "工具 schema 自检异常：${it.message}")
        }
    }

    /**
     * AI 下行发送互斥锁：WiFi 稳定连接时聊天发送 / ASR push / 轮询 / SDK 上行
     * 多个并发入口同时命中 sendAiTextMessage 快速路径，无锁并发 sendCustomCmd
     * 同一 CXRLink 会与 cleanup() 的 disconnect 产生竞态（SDK native 崩溃），
     * 因此 sendAiTextViaLink 全流程加锁串行执行。
     */
    private val aiSendLock = Any()

    /**
     * 上一轮对话是否用过工具（进程内观测位）。
     *
     * ⚠️ 2026-09-29 第六轮起**不再拦截**三层省 token 装配（快路/路由/菜单探测）：三层的
     * 逃生舱（[NEED_TOOLS] 升级、菜单点名）已齐，粘滞位只剩「衔接句少一次探测 RTT」的
     * 延迟收益，却让「查天气→谢谢/那上海呢」这类最高频的后续轮全价发 83 个 Schema，
     * 方舟无缓存端点下净亏。保留落账供日志观测与未来策略使用。
     */
    @Volatile
    private var lastTurnHadToolCalls = false

    /**
     * AI 生成代际计数：每次新的 sendAiTextMessage（语音唤醒/聊天/拍照答题）进入即 +1。
     * 执行中或排队中的旧请求检测到自身代际已过期（用户已发起新请求）即放弃继续生成，
     * 避免旧的多步工具任务长时间占用 aiSendLock 链路，让新语音/消息尽快接管
     * （用户打断场景：Agent 还在跑工具循环时用户再说话，旧任务应让路）。
     */
    @Volatile
    private var aiGenSeq = 0L

    /**
     * 抢占新一代际号。写入可能来自 UI 主线程 / ASR 轮询线程 / 生成线程，
     * `++` 在 @Volatile 字段上不是原子操作（并发写会丢计数，导致旧任务判断失误），故同步递增。
     */
    @Synchronized
    private fun bumpAiGen(): Long = ++aiGenSeq

    /**
     * AI 主循环的**结构化并发域**（2026-10-02 协程化）。
     *
     * 改造前：生成走裸线程（`namedThread`），被打断的请求要等它**自己**在下一个检查点
     * 比对代际后才退出 —— 若模型阻塞在网络读上，最坏要等一个 readTimeout（120s）。
     * 改造后：生成跑在 [runInterruptible] 里，[abortCurrentAi]/新请求抢占时 cancel
     * 对应 Job ⇒ 取消**当场转化为线程中断** ⇒ OkHttp 的阻塞读立刻以 IO 异常退出，
     * "用户已打断、AI 还在偷偷跑工具烧 token"的窗口从分钟级缩到毫秒级。
     *
     * SupervisorJob：单次生成失败不拖垮域内其它任务（工具 worker、后台收尾）。
     * 线程池 = Dispatchers.IO（64 线程上限，远高于本服务的并发需求）。
     */
    private val aiScope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("ai-main-loop"))

    /**
     * 并行工具泳道的**专属线程**（2026-10-06 审计修）。
     *
     * ## 为什么不能复用池线程
     * 工具产出（眼镜拍到的图、子代理的用户原话）经 ThreadLocal 从"执行工具的线程"
     * 回传给主循环。这要求**写入与读取在同一根线程**上，而 `Dispatchers.IO` 的池线程
     * 是**跨任务复用**的：下一个任务可能在同一根线程上跑，于是
     * ① 读不到本任务的产出（图丢了，模型"什么都没看到"），
     * ② 更糟的是**残留泄漏**——上一次的产出被下一个任务取走，
     *    即**上一个用户的画面/原话被注入本次对话**。
     *
     * ⇒ 每条并行泳道给一根**自己的**线程，跑完即结束。`latch.countDown()` 在
     * [runToolWorker] 自己的 `finally` 里，与本方法无关，语义不变。
     *
     * 📌 不入 [aiScope]：代际取消不波及工具执行（工具必须跑完当前这一次调用），
     * 与改造前 `aiScope.launch` 的语义一致。线程是**非守护**的短命线程，
     * `countDown` 兜底保证不会永久挂死。
     */
    private fun launchDedicatedLane(name: String, block: () -> Unit) {
        val t = Thread({
            try {
                block()
            } catch (e: Throwable) {
                Log.e(TAG, "tool lane $name crashed", e)
            }
        }, name)
        t.isDaemon = false
        t.start()
    }

    /** 当前代际对应的生成 Job（与 [aiGenSeq] 同生命周期；null = 没有在跑的生成） */
    @Volatile
    private var activeGenJob: Pair<Long, Job>? = null

    /**
     * 把一个代际的生成体跑进结构化并发：登记 Job、抢占时自动取消上一代际。
     *
     * ⚠️ 调用线程**不会被阻塞**（与 `namedThread(start=true)` 语义一致）——
     * 外层主流程稍后用返回的 Job 自行 join。
     */
    private fun launchAiGeneration(generation: Long, block: () -> Unit): Job {
        // 上一代际的 Job：能活着到这里的必然已过期（代际被本请求抢占），
        // 主动 cancel 把"等旧请求下一个检查点自弃"升级成"当场中断其网络读"
        activeGenJob?.let { (oldGen, oldJob) ->
            if (oldGen != generation) {
                oldJob.cancel()
                Log.i(TAG, "superseded gen=$oldGen job cancelled (new gen=$generation)")
                // ★ 2026-10-13 审计修（H1+M3）：被抢占的旧生成可能正**阻塞在确认/提问等待**上。
                //   cancel 会把协程取消翻译成线程中断（runInterruptible），但屏幕上的
                //   确认框/选项卡不会被撤掉 —— 用户面对一个点什么都没用的死弹窗，
                //   而对应的副作用工具还可能按"超时"语义被静默放行（H1）。
                //   aborted=true 让等待方立刻拿到"被中止"，闸门一律拒绝执行。
                com.rokidlab.phone.ai.PhoneToolConfirmChannel.abortAll(aborted = true)
                com.rokidlab.phone.ai.AskUserChannel.abortAll()
            }
        }
        val job = aiScope.launch {
            // 本层已在 Dispatchers.IO 上；runInterruptible 只负责"取消 → 线程中断"的翻译
            runInterruptible { block() }
        }
        activeGenJob = generation to job
        return job
    }

    /** 眼镜端唤醒词对话（语音 ASR）的 UI 回调：同步显示到乐奇聊天窗口 */
    @Volatile
    private var glassesAiTextCb: (String) -> Unit = {}
    @Volatile
    private var glassesAiReplyCb: (String) -> Unit = {}

    /**
     * 「过程」（思考 / 工具调用）的**全局**输出端，由 [setAgentTraceSink] 在 App 启动时注册一次。
     *
     * 为什么做成全局而不是像 [onTrace] 那样逐次调用传参：AI 入口有 **4 条**
     * （打字 / 眼镜语音 ASR / 拍照答题 / 定时自主任务），传参式设计必须每条入口都记得传，
     * 漏一条那条路就完全没有过程显示 —— **实测已因此漏掉眼镜语音**（用户反馈"眼镜上说，
     * 手机上没显示思考链路，但打字可以"）。全局汇聚点让 4 条入口天然全覆盖。
     *
     * 调用级 [onTrace] 依然可用（优先级更高），供测试/特殊链路显式覆盖。
     */
    @Volatile
    private var traceSink: ((com.rokidlab.phone.ai.AgentStep) -> Unit)? = null

    /** 过程收尾（把残留的「进行中」步骤置终态），见 [setAgentTraceSink] */
    @Volatile
    private var traceFinishSink: ((Boolean) -> Unit)? = null

    /** 最近一次对话输入登记（任意来源：手机输入/眼镜推送/定时任务等）。
     *  供 [dispatchGlassesAsrText] 做跨入口同文回声判定（见该函数注释）。 */
    @Volatile
    private var lastChatInputText: String? = null
    @Volatile
    private var lastChatInputAtMs = 0L

    /** 跨入口同文回声抑制窗：实测眼镜推送经 RFCOMM 拥塞可迟到 3.5s+，取 8s 覆盖 */
    private val CHAT_INPUT_ECHO_MS = 8_000L

    /** 最近一次 AI 回复全文（环境音链路 TTS 回声判定用，见 [dispatchGlassesAsrText]） */
    @Volatile
    private var lastAiReplyText: String? = null
    @Volatile
    private var lastAiReplyAtMs = 0L

    /** TTS 回声判定窗：回复播报可能持续数十秒，播报期间被远场麦拾到的都算回声 */
    private val AMBIENT_REPLY_ECHO_MS = 45_000L

    /**
     * 通过 CXR-L SDK 发送文字指令到眼镜端 AssistServer（Lab 接管官方 AI 的下行入口）。
     * 协议（反编译自 RokidSpriteAssistServer）：
     *   - topic = "Ai"
     *   - caps[0] = "ASR_Result"  (KEY_BLUETOOTH_AI_ASR_MESSAGE)
     *   - caps[1] = 文字内容 (String)
     * AssistServer 用 CXRServiceBridge.subscribe("Ai", ...) 全局订阅，
     * 理论上 RokidLab 在 CUSTOMAPP 会话内 sendCustomCmd(LinkProtocol.CXR_CHANNEL_AI, caps) 即可送达。
     */
    fun sendAiTextMessage(
        text: String,
        onResult: ((Boolean, String?) -> Unit)? = null,
        onReply: ((String) -> Unit)? = null,
        contextText: String? = null,
        interruptOfficialFirst: Boolean = false,
        skipTtsAudioFinished: Boolean = false,
        /** 是否在眼镜端重发用户问题（ASR_Result）：眼镜语音唤醒链路中官方已显示提问，避免重复显示 */
        showAsrResult: Boolean = true,
        /** 眼镜端是否已本地接管显示（KeyButtonService 在 ASR_End 后已本地打开会话并显示提问）：
         *  为 true 时下行只发 DeepSeek 回复（TTS_Result + tts_play），跳过 KeyDown/open/ASR_Result/ASR_End。
         *  ⚠️ 别给它补发 KeyDown/open：实测会让 Lab 回复「有声音没文字」（见 sendAiTextViaLink 步骤0 注释）。 */
        localTakeover: Boolean = false,
        /** 附加指令：注入 system 提示词控制回答方式（如「只显示答案」「给出解题步骤」） */
        instruction: String? = null,
        /** 是否记录到 Agent 会话记忆（多轮上下文）。拍照答题等一次性场景传 false */
        recordHistory: Boolean = true,
        /** AI 回复流式增量回调（每个 content delta），用于 UI 边生成边显示；眼镜 TTS 仍整段发送 */
        onDelta: ((String) -> Unit)? = null,
        /**
         * Agent **过程**回调：思考中 / 调用了哪个工具 / 结果如何。
         *
         * 与 [onDelta] 的区别：[onDelta] 只承载回复正文，工具调用轮 content 通常为空，
         * 所以「手机端聊天窗口只看到长时间空白」——本回调就是补上这条过程通道
         * （详见 [com.rokidlab.phone.ai.AgentStep] 的类注释）。
         * 回调可能在任意子线程触发，UI 侧自行切主线程。
         */
        onTrace: ((com.rokidlab.phone.ai.AgentStep) -> Unit)? = null,
        /**
         * **无人值守模式**：本轮只装配 [com.rokidlab.phone.ai.ToolRegistry.schemasUnattended]
         * （只读档 ∪ 本地媒体白名单），并禁用 AIUI 精简子集切换（那会引入 save_code_file 等写操作）。
         *
         * 用于**无人值守**场景（定时触发的自主任务）。无人监管时模型跑错一次
         * （半夜拨号/装机/改设置）代价远高于「少做一点」，因此物理上不给它副作用工具；
         * 例外只有白名单里的本机可撤销媒体工具（放歌），原因见
         * [com.rokidlab.phone.ai.ToolRegistry.UNATTENDED_MEDIA_ALLOWLIST]。
         */
        readOnlyTools: Boolean = false,
        /**
         * 多模态图像（base64，**不含** `data:` 前缀）。
         *
         * 非空时本轮 user 消息的 content 改成分片数组（text + image_url），
         * 供支持视觉的模型直接看图 —— 拍照问 AI 的「图像理解」路径用它绕开本地 OCR。
         */
        imageBase64: String? = null,
        /**
         * **本会话的附加提示词**（用户在会话设置里为该会话单独指定；null = 没设）。
         *
         * 加在参数表**末尾**是刻意的：既有调用点大量使用位置参数，插在中间会静默错位。
         * 语义是**追加**到全局人设之后（见 `OpenAiService.buildSystemMessage`），不是替换。
         */
        sessionPrompt: String? = null,
    ) {
        Log.i(TAG, "sendAiTextMessage(\"$text\") called. session.cxrlConnected=${session.cxrlConnected}, session.glassBtConnected=${session.glassBtConnected}, session.cxrLink=${session.cxrLink != null}, session.token=${session.token?.take(8) ?: "null"}, readOnlyTools=$readOnlyTools")
        // 登记本次对话输入（任意来源），供 dispatchGlassesAsrText 做跨入口同文回声判定
        lastChatInputText = text
        lastChatInputAtMs = System.currentTimeMillis()

        // 抢占新一代际：让正在执行/排队的旧 Agent 任务让路（用户打断）
        val myGen = bumpAiGen()

        // 上一轮已在本行被抢占：它的过程步骤不会再有终态事件，这里替它收尾
        // （否则那条被打断的思考/工具会永远以「进行中」转圈挂在聊天窗口里）
        traceFinishSink?.invoke(true)

        // 过程输出端：调用级参数优先，未传则走 App 级全局汇聚点（见 [setAgentTraceSink]）。
        // 眼镜语音 / 拍照答题 / 定时任务都不传 onTrace —— 全局汇聚点就是它们的通路。
        val trace = onTrace ?: traceSink

        // 结果回调统一包一层：请求一结束（不论成功失败）就把残留的「进行中」步骤置终态。
        // 放在这个入口包，比要求每条入口自己记得收尾更可靠；幂等，重复调用无副作用。
        val settledResult: (Boolean, String?) -> Unit = { success, err ->
            traceFinishSink?.invoke(!success)
            onResult?.invoke(success, err)
        }

        // ★ 本机模式（设置页「乐奇聊天 → 工具 → 本机模式」）：用户明确要求不依赖眼镜时，
        //   直接把 link 置空交给 sendAiTextViaLink —— 那里会跳过全部眼镜下行并摘掉眼镜类工具。
        //   放在最前面是刻意的：**不再尝试连接眼镜**，所以没连眼镜时不会白等 15s 连接超时。
        val phoneOnly = (session.appContext as? com.rokidlab.phone.app.LabApplication)
            ?.chatLocalOnlyEnabled == true
        if (phoneOnly) {
            Log.i(TAG, "sendAiTextMessage: local-only mode (no glasses), skip link/prerequisites")
            session.onBusyChanged(true)
            session.appScope.launch(Dispatchers.IO) {
                sendAiTextViaLink(null, text, settledResult, onReply, contextText, interruptOfficialFirst, skipTtsAudioFinished, showAsrResult, localTakeover, instruction, recordHistory, onDelta, myGen, readOnlyTools, trace, imageBase64, sessionPrompt)
            }
            return
        }

        // 快速路径: 如果 CXR 已连接且 link 可用，直接发送（跳过前置检查 + 重新 connect）
        val link = session.cxrLink
        if (session.cxrlConnected && session.glassBtConnected && link != null) {
            Log.i(TAG, "sendAiTextMessage: using existing CXRLink (fast path)")
            sendAiTextViaLink(link, text, settledResult, onReply, contextText, interruptOfficialFirst, skipTtsAudioFinished, showAsrResult, localTakeover, instruction, recordHistory, onDelta, myGen, readOnlyTools, trace, imageBase64, sessionPrompt)
            return
        }

        // 慢速路径: 需要先建立连接
        Log.i(TAG, "sendAiTextMessage: no active link, falling back to connectAndRun path")
        val targetHostApp = session.hostApp
        if (!session.hasGlassesOperationPrerequisites(targetHostApp, requestAuthorizationIfMissing = true)) {
            // 前置条件不满足（宿主 App 没装/版本太低/未授权）**同样退本机模式**，而不是报错退出：
            // 用户的诉求就是「没有眼镜也能用聊天」，此时唯一正确的行为是继续把问题交给模型。
            Log.w(TAG, "sendAiTextMessage: missing glasses prerequisites -> fall back to local-only")
            session.onBusyChanged(true)
            session.appScope.launch(Dispatchers.IO) {
                sendAiTextViaLink(null, text, settledResult, onReply, contextText, interruptOfficialFirst, skipTtsAudioFinished, showAsrResult, localTakeover, instruction, recordHistory, onDelta, myGen, readOnlyTools, trace, imageBase64, sessionPrompt)
            }
            return
        }
        val authToken = session.token.orEmpty()

        session.onBusyChanged(true)
        session.connection.connectAndRunCustomAppOperation(
            authToken = authToken,
            targetHostApp = targetHostApp,
            operation = CxrAppOperation(
                packageName = "com.rokidlab.rokidlink",
                timeoutMillis = 15_000,
                timeoutMessage = "AI text send timeout",
                bindMessage = "Sending AI text: $text",
                configureFailureMessage = "Configure CXR-L CUSTOMAPP session failed",
                bindFailureMessage = "Bind host service failed",
                showConnectionStatus = false,
                onReady = { l ->
                    // onReady 由连接回调（maybeRunPendingOperation）在主线程触发；
                    // sendAiTextViaLink 内含多次 Thread.sleep + 生成 Job 的阻塞 join（最长可阻塞 30s），
                    // 必须切后台线程执行，否则慢速路径阻塞主线程导致 ANR/闪退。
                    // session.onStatus/session.onBusyChanged 已线程安全，onReply 由调用方切主线程，onResult 内部 runOnUiThread。
                    session.appScope.launch(Dispatchers.IO) {
                        sendAiTextViaLink(l, text, settledResult, onReply, contextText, interruptOfficialFirst, skipTtsAudioFinished, showAsrResult, localTakeover, instruction, recordHistory, onDelta, myGen, readOnlyTools, trace, imageBase64, sessionPrompt)
                    }
                },
                onFailure = {
                    // 连接失败不再直接报错 —— 退到本机模式照样把问题回答给用户。
                    // 旧行为是 settledResult(false, "connection failed")，用户侧表现为
                    // 「眼镜不在/连不上 → 聊天完全不能用」，而模型其实完全有能力离线回答。
                    Log.w(TAG, "sendAiTextMessage: glasses connect failed -> fall back to local-only")
                    session.cleanup()
                    session.appScope.launch(Dispatchers.IO) {
                        sendAiTextViaLink(null, text, settledResult, onReply, contextText, interruptOfficialFirst, skipTtsAudioFinished, showAsrResult, localTakeover, instruction, recordHistory, onDelta, myGen, readOnlyTools, trace, imageBase64, sessionPrompt)
                    }
                },
            ),
        )
    }

    /**
     * 处理眼镜端上行的语音识别文字（唤醒词 + 语音场景）。
     *
     * 链路：眼镜端唤醒词触发官方 ASR → RokidLink 拦截 ASR_End 文字入队
     *  → RokidLab 定时轮询拉取 → 本方法收到文字
     *  → 用 Lab 配置的模型生成回复 → sendAiTextMessage 走完整链路
     *    （KeyDown_Client → open → ASR_End → TTS_Result → tts_play，不重发 ASR_Result）
     *    在眼镜端显示 Lab 回复并播报。
     * 同时通过 glassesAi 回调把提问与回复同步显示到乐奇聊天窗口。
     */
    /** ASR 文字分发核心（去重由 AsrBridgeCoordinator.onAsrText 负责，此处只做对话链路） */
    internal fun dispatchGlassesAsrText(text: String, fromAmbient: Boolean = false) {
        // 跨入口同文回声抑制：眼镜 ASR 推送可能被 RFCOMM 下行拥塞缓冲数秒后迟到，
        // 而同一句已由手机端输入（或其他入口）先行处理完 —— 协调器的 lastAsrText
        // 看不到不经它入队的输入（2026-09-21 实测两连案例），判定必须在会话层做：
        // 「这句与最近一次对话输入相同，且间隔很短」⇒ 视为同一次语音的迟到回声，丢弃。
        val now = System.currentTimeMillis()
        val recent = lastChatInputText
        if (recent != null && text == recent && now - lastChatInputAtMs < CHAT_INPUT_ECHO_MS) {
            Log.i(TAG, "dispatchGlassesAsrText: same text as chat input ${now - lastChatInputAtMs}ms ago, skip as echo")
            return
        }
        // 环境音链路 TTS 回声防护（防自激）：AI 回复经眼镜扬声器播报时会被远场麦重新拾到，
        // 若不丢弃会形成「AI 听到自己的回答 → 再回答」死循环。判定：本句在回复播报窗内到达
        // 且内容与最近回复重合（ASR 可能只听到回复中一段，故做互相包含判定）。
        // 内容不重合（对方复述/引用回复之外的独立发言）不受影响，正常进对话。
        if (fromAmbient) {
            val lastReply = lastAiReplyText
            if (lastReply != null && now - lastAiReplyAtMs < AMBIENT_REPLY_ECHO_MS && isReplyEcho(text, lastReply)) {
                Log.i(TAG, "dispatchGlassesAsrText: ambient text matches recent AI reply, skip as TTS echo")
                return
            }
        }
        // 标记「本条 ASR 处理中」，供 AsrBridgeCoordinator 判定后续相同文字是否丢弃。
        // sendAiTextMessage 是异步回调式：onResult 在 AI 下行结束（成功/失败）时回调，
        // 此处据此复位 handling；AsrBridgeCoordinator 内还有 90s 超时兜底，防异常路径标志卡死。
        session.asrBridge.markAsrHandling(true)
        // 环境音链路：AI 回合开始 —— 先真闭麦（远场字幕链路 stop）再分发，回答期间不拾音；
        // 眼镜端播完标记（MARKER_AMBIENT_TTS_DONE）到达后自动重开麦进入下一轮，
        // 标记丢失由 150s 兜底强制恢复（AmbientListenCoordinator.onAiRoundStart）。
        if (fromAmbient) session.ambientListen.onAiRoundStart()
        try {
            Log.i(TAG, "dispatchGlassesAsrText: $text")
            // 用户提问同步到聊天窗口（轮询在 IO 线程，需切回主线程更新 Compose 状态）
            session.safeRunOnUiThread { glassesAiTextCb(text) }
            sendAiTextMessage(
                text,
                onResult = { success, err ->
                    Log.i(TAG, "handleGlassesAiAsrText sendAiTextMessage: success=$success err=$err")
                    session.asrBridge.markAsrHandling(false)
                    // 失败/被抢占路径没有播报、也就没有播完标记，立即恢复远场接收
                    if (fromAmbient && !success) session.ambientListen.onAiRoundDone()
                },
                onReply = { reply ->
                    Log.i(TAG, "handleGlassesAiAsrText reply: ${reply.take(40)}")
                    // Lab 回复同步到聊天窗口（onReply 在子线程回调，需切回主线程）
                    session.safeRunOnUiThread { glassesAiReplyCb(reply) }
                },
                // 近场语音（唤醒词）：眼镜端 KeyButtonService 已在 ASR_End 后本地打开会话并显示提问，
                // 下行只发 Lab 回复，不再重发 KeyDown/open/ASR_Result/ASR_End。
                // ⚠️ 近场这条不要改成 localTakeover=false：实测会「有声音没文字」（已回滚）。
                // 环境音（fromAmbient）：眼镜端**从未**显示过提问（远场 ASR 走官方字幕频道，
                // 不经过 KeyButtonService 的本地接管），若沿用上面这组参数，眼镜端全程无字。
                // 故环境音改走与「文字输入」完全相同的下行（见 sendAiTextViaLink 步骤 0/1/2）：
                // KeyDown_Client → 等场景就绪 → ASR_Result → ASR_End，由官方 AI 界面显示对方的话。
                // interruptOfficialFirst 必须为 false —— 该分支会下发入站 `Exit`，把官方置为
                // 「已退出」态，之后 TTS_Result 只出声不上屏（2026-09-17 实测，见步骤-1 注释）。
                interruptOfficialFirst = !fromAmbient,
                skipTtsAudioFinished = true,
                showAsrResult = fromAmbient,
                localTakeover = !fromAmbient,
            )
        } catch (e: Exception) {
            Log.e(TAG, "handleGlassesAiAsrText error", e)
            session.asrBridge.markAsrHandling(false)
            // 抛错路径不会走到 sendAiTextMessage 的结果包装，这里单独兜底「过程」收尾
            traceFinishSink?.invoke(true)
        }
    }

    /**
     * 环境音 TTS 回声判定：ambient 句内容与最近 AI 回复是否重合。
     * 启发式：去掉标点空白后，ambient 句是回复的子串（听到了播报中一段），
     * 或包含回复开头片段（从播报起始拾到）。短句（<6 字）不判定，防误杀真实短提问。
     */
    private fun isReplyEcho(text: String, reply: String): Boolean {
        fun norm(s: String) = s.replace(Regex("[\\s，。？！、,.?!；;：:\"'“”‘’…—]"), "")
        val a = norm(text)
        val b = norm(reply)
        if (a.length < 6 || b.length < 6) return false
        return b.contains(a) || a.contains(b.take(12))
    }

    /**
     * 发送过程中校验 CXRLink 是否仍然有效（未被 session.cleanup 断开/替换）。
     * 无效时复位状态并回调失败，返回 false 供调用方中止发送，
     * 避免用已断开的 link 调 sendCustomCmd 导致 SDK native 崩溃。
     */
    internal fun abortAiSendIfLinkInvalid(link: CXRLink, onResult: ((Boolean, String?) -> Unit)?): Boolean {
        if (session.cxrLink === link && session.cxrlConnected) return true
        Log.w(TAG, "sendAiTextViaLink: link stale/closed, abort send")
        session.mainHandler.post {
            session.connection.completeActiveOperation()
            session.onBusyChanged(false)
            onResult?.invoke(false, "link disconnected")
        }
        return false
    }

    /**
     * 工具执行期间向眼镜推送进度提示（如「正在查询眼镜电量…」）。
     * 仅更新 AI 会话显示文字，不触发语音播报；最终回复的 TTS_Result 会覆盖该文字。
     * 按条加锁（session.aiCmdLock）与下行主链路串行；失败静默——进度提示是增强体验，不能影响主流程。
     *
     * ⚠️ 2026-09-26 真机实测：官方气泡把带来源标记的 Lab 帧**追加进同一块文本缓冲**，
     * 帧边界不产生换行（过程行会连成一片）——换行必须由文本自身携带。每条帧尾补 `\n\n`
     * （双换行：若缓冲按 markdown 渲染，单个 `\n` 会被折叠成空格，双换行才是段落分隔）。
     * [session.onStatus] 是手机端状态行，不吃换行，保持原文。
     */
    internal fun sendGlassesProgress(link: CXRLink, text: String) {
        if (session.cxrLink !== link || !session.cxrlConnected) return
        runCatching {
            synchronized(session.aiCmdLock) {
                val caps = Caps()
                caps.write("TTS_Result")
                caps.write(text + "\n\n")
                // 进度帧同样是 Lab 的回复正文，必须带来源标记，否则会被眼镜端当作官方回声丢弃
                caps.write(LinkProtocol.AI_REPLY_MARK)
                link.sendCustomCmd(LinkProtocol.CXR_CHANNEL_AI, caps)
            }
        }
        session.onStatus(text)
    }

    /**
     * 请求眼镜端官方 AssistServer 重开拾音 —— 「连续对话（多轮免唤醒）」的下一轮入口。
     *
     * ⚠️ **为什么这一帧必须由手机端发（而不是眼镜端本机 `sendAi`）**：
     * 官方 AssistServer 的 `AudioFinishedHandler` 只处理**入站**（手机→眼镜）的 `Ai` 帧
     * （真机 `_g_proto_trace.md` 实测：`[wire] recv cmd=Ai` → `AudioFinishedHandler handle`
     * → `aiAudioFinishWake` → `AIModeManager.startNewTalk` → `CXRServiceManager
     * startAudioStream`）。眼镜端本机 `sendAi("TTS_AudioFinished")` 是**出站**帧
     * （`[wire] cmd=Ai caps=...`），官方自己的分发器收不到 —— 实测 21:07:32 发完官方侧
     * 毫无反应，麦克风不重开，用户说下一句没人听。
     *
     * 触发时机由眼镜端决定（只有它拿得到 TTS 真实播完时刻，见 `KeyButtonService`），
     * 眼镜端推 [LinkProtocol.MARKER_CONTINUE_DIALOG] 上来后本方法经 CXR `Ai` 频道下发。
     *
     * 效果：官方 `startNewTalk` 会重开会话（`showAudioFinishUI` 保留已显示的上一轮回复，
     * 只是追加一条新气泡），并重开麦克风 —— 用户无需再说唤醒词。
     *
     * 按条加锁（[CxrLHiRokidSession.aiCmdLock]）与下行主链路串行，避免与
     * `TTS_Result`/`tts_play` 序列交错。失败只记日志：续听是增强体验，不能影响主流程。
     */
    internal fun requestGlassesContinueDialog(): Boolean {
        val link = session.cxrLink
        if (link == null || !session.cxrlConnected) {
            Log.w(TAG, "requestGlassesContinueDialog: no active link, skipped")
            return false
        }
        return try {
            val caps = Caps()
            caps.write("TTS_AudioFinished")
            caps.write("true")
            val r = synchronized(session.aiCmdLock) {
                // 加锁期间链路可能被 cleanup 替换，复查同一条 link 再发
                if (session.cxrLink !== link || !session.cxrlConnected) return@synchronized -1
                link.sendCustomCmd(LinkProtocol.CXR_CHANNEL_AI, caps)
            }
            Log.i(TAG, "continue dialog: sendCustomCmd(Ai, TTS_AudioFinished, true) -> $r")
            r == 0
        } catch (e: Exception) {
            Log.e(TAG, "requestGlassesContinueDialog failed", e)
            false
        }
    }

    /**
     * 用已连接的 CXRLink 直接发送 AI 文字指令。
     *
     * 完整流程（复刻官方 App 行为，12:18:45 日志验证）：
     *   1. 发 ASR_Result（用户文字）→ 眼镜显示用户问题
     *   2. 发 ASR_End → 眼镜标记 ASR 结束
     *   3. 调用 DeepSeek API 获取 AI 回复
     *   4. 发 TTS_Result（AI 回复）→ 眼镜显示回复 + 语音播放
     *
     * 前置条件：眼镜端 ai_assist 场景已开启（用户按按键开 AI），即 aiIsRunning=true
     *
     * ## ★ `link` 可为 null = **本机模式**（乐奇聊天不依赖眼镜）
     *
     * `link == null` 时：**跳过全部眼镜下行**（9 处 `sendCustomCmd`），
     * 并从下发给模型的工具清单里摘掉 [ToolRegistry.GLASSES_REQUIRED_TOOLS]；
     * 而模型调用、Agent 工具循环、会话记忆、长期记忆、流式 `onDelta`、`onReply` 全部照常 ——
     * 也就是说「聊天」这条主业务不再需要眼镜。回复照常进手机聊天窗口，只是不再推给眼镜。
     *
     * 这样设计的原因是：眼镜在线时行为**一字不改**（`link != null` 走原路径），
     * 只有明确无眼镜时才走新分支，不存在回归面。
     */
    /**
     * 本轮一次工具调用的执行上下文（[executeToolInTurn] 的参数包）。
     *
     * 抽出理由（2026-10-05 god-class 拆分第二步）：`executeTool` 原先是
     * `sendAiTextViaLink` 里的局部 `fun`，隐式捕获 6 个外层变量（`phoneOnly` /
     * `readOnlyTools` / `localLight` / `pseudoToolEntries` / `longTermMemory` /
     * `longTermOn`）**加成员 `session`**。隐式捕获的直接后果是**改任何一个判定都要
     * 肉眼扫一遍这 6 个变量在别处有没有被改过**，而这段是"工具会不会真被执行"的唯一闸门。
     *
     * ⚠️ 本包**刻意不含并发同步**（这是取证的结论，不是省事）：
     * 主循环的两级调度里，并行泳道 `runToolWorker` 只写 `results[idx]` / `lookImages[idx]`
     * （按调用下标分槽、互不重叠），**不碰本包任何字段**；而 `executeToolInTurn` 的所有
     * 调用点都在生成线程上（串行泳道内联执行、收尾总结轮同样在生成线程）。
     * ⇒ 本包只被生成线程读写，加 `@Volatile`/锁既无必要又会掩盖真实的共享面。
     * 若将来把工具执行挪到工作线程，**必须同时补同步**（判据= 看 `results[idx]` 之外的字段）。
     */
    private class TurnExecContext(
        /** 本机模式（`link == null`）：用户明确说了不经眼镜 ⇒ 眼镜类工具单调拒绝 */
        val phoneOnly: Boolean,
        /** 无人值守（定时自主任务）：执行侧据此与装配侧白名单保持一致 */
        val readOnlyTools: Boolean,
        /** 闲聊快路（0 工具）/ 纯参数轮：失败不沉淀教训 */
        val localLight: Boolean,
        /** 本轮真正下发的伪工具条目（名字用于"未知工具"判据） */
        val pseudoToolNames: Set<String>,
        /**
         * 长期记忆是否启用：本轮装配期算出（`LongTermMemoryManager.isEnabled`）。
         * 关掉时既不注入上下文也不沉淀教训，但**执行入口本身仍走**（`manage_memory`
         * 会被 ApprovalGate 拦），所以这里是"要不要记账"的判据而非"能不能调"。
         */
        val longTermOn: Boolean,
        /**
         * 本轮用户消息原文（2026-10-06）。
         *
         * 加它的理由与这个类存在的理由完全一致：[sendAiTextViaLink] 里的 `text` 是隐式捕获的，
         * 搬到成员方法后就取不到了。而它**不是**可有可无的上下文 ——
         * 子代理靠它做指代消解（"这个/刚才那个"指什么），缺了就只能按字面泛泛地查。
         */
        val userText: String,
        /**
         * 过程步骤上报回调（2026-10-06 审计加，供审批等待可见用）。
         *
         * 加它的理由：[executeToolInTurn] 里拿不到主循环的 `onTrace`（局部变量），
         * 而"正在等用户确认"这件事只有它能发 —— 确认等待是 18~40s 的同步阻塞，
         * 不发这条的话过程卡片上只是一个无说明的转圈点（见 [AgentStep.State.AWAITING_CONFIRM]）。
         *
         * 为 null（总结兜底轮不传）时审批等待不可见，但不影响功能 —— 那条路径
         * 不发RUNNING，自然也不会有"转圈不变"的落差。
         */
        val onTrace: ((AgentStep) -> Unit)? = null,
    )

    /**
     * 工具执行统一入口（主循环与总结兜底轮共用）：异常类失败（网络抖动/ADB 隧道
     * 瞬断等瞬时错误）自动重试一次（500ms 退避）再如实回报模型——瞬时失败直接
     * 上报会让模型过早放弃或向用户播报失败；业务性失败（"没有找到歌曲"等字符串
     * 返回值）不重试，语义已经是确定性结果。
     *
     * 2026-10-05：从局部 `fun executeTool` 原样提为成员方法，隐式捕获全部转显式入参。
     * **行为零变更**（逐字搬迁）。
     */
private fun executeToolInTurn(
        tc: com.rokidlab.phone.ai.ToolCallInfo,
        ctx: TurnExecContext,
    ): String {
        // ★ 授权留痕（写前日志，2026-10-09）：把闸门的放行结论落进事件流。
        //
        // 装在这里而不是每个调用点各注册一次的理由：闸门是**纯判定层**（不依赖事件流），
        // 接线只有这一处；`try/finally` 保证即便抛异常也不会留下一个污染全局的 sink
        // —— 那个污染会让后续**别的**会话把授权记到错的流里，比不记还糟。
        //
        // ⚠️ 写入**时机**由闸门保证（放行前），这里只负责"往哪写"（当前轮 + 当前 callId）。
        // 拒绝路径不会通知 sink ——「未授权的副作用不产生记录」是反向不变式。
        val previousSink = com.rokidlab.phone.ai.approval.ApprovalGate.authorizationSink
        com.rokidlab.phone.ai.approval.ApprovalGate.authorizationSink =
            com.rokidlab.phone.ai.approval.ApprovalGate.AuthorizationTrace { name, source, prompt, stages ->
                com.rokidlab.phone.ai.AgentSessionManager.appendSideEffectIntent(
                    turn = com.rokidlab.phone.ai.AgentSessionManager.currentTurn().takeIf { it > 0 },
                    name = name,
                    source = source,
                    callId = tc.id,
                    summary = prompt,
                    stageEvidence = stages,
                )
                // ★ 授权结果也进「过程」（2026-10-10）。此前 trace 只有
                //   AWAITING_CONFIRM 这个**瞬时**状态，"最后是谁放行的"完全看不到：
                //   - USER_APPROVED = 用户点了确认（用户记得）；
                //   - POLICY = 问不到用户 / 超时，按 PROCEED 静默放行（用户**不记得**，
                //     却有一个高危动作真的发生了 —— 这是最需要被看见的一条）。
                // ⚠️ 只在这条 sink 里发（放行前）：拒绝路径不调 sink，
                //   所以「被拒绝」不会在这里出现 —— 那由工具结果里的
                //   「被安全策略拦截」表达（见 AgentStep.isFailureResult）。
                ctx.onTrace?.invoke(
                    AgentStep.framework(
                        when (source) {
                            com.rokidlab.phone.ai.session.AuthorizationSource.USER_APPROVED ->
                                "已获你确认：$name"
                            com.rokidlab.phone.ai.session.AuthorizationSource.POLICY ->
                                "未能征求你确认，按策略放行：$name"
                            else -> "已授权：$name"
                        },
                        id = "authz_${tc.id}",
                    )
                )
            }
        try {
            return executeToolWithApproval(tc, ctx)
        } finally {
            com.rokidlab.phone.ai.approval.ApprovalGate.authorizationSink = previousSink
        }
    }

    /**
     * 参数解析 + 审批 + 执行的真正主体（原 `executeToolInTurn` 的全部逻辑，逐字搬迁）。
     *
     * ★ 为什么要拆出这一层：授权留痕需要在**整个调用前后**挂上 sink（`try/finally`），
     * 而原有逻辑是一个数百行的大方法，无法在它内部"前后"包一层而不动它的结构。
     * 拆分保持**行为零变更** —— 调用方仍只经过 [executeToolInTurn] 一个入口。
     */
    private fun executeToolWithApproval(
        tc: com.rokidlab.phone.ai.ToolCallInfo,
        ctx: TurnExecContext,
    ): String {
        // ★ 参数解析 + 审批闸门在**重试循环之外**只过一遍（改造前闸门藏在 else 分支里，
        //   每次重试都会重新 `check`）。两个理由：
        //   ① 瞬时失败重试是同一次调用的重放，不该重复扣限流配额、
        //      更不该重复弹眼镜确认（用户会看到同一个问题被问两次）；
        //   ② 伪工具（manage_memory / load_skill / load_skill_section / update_plan）
        //      原先走下面 when 的前置分支、**完全绕过任何闸门**，现在与真实工具同一条判定。
        //
        // 参数 JSON 非法（非空白却解析失败）：多半是模型单次输出被 max_tokens 截断、
        // 工具参数在半途断开（少数是模型格式瑕疵）。旧实现沿用 ToolRegistry.execute 的
        // 「非法即兜底成空对象」策略，下游只会回「保存失败：项目名不能为空」这类误导性错误
        // —— 模型看不出真实原因，往往原样重试同样大的内容，白耗轮次。这里直接告诉它真因，
        // 且**不重试**（确定性失败，重试只是白等 500ms）。
        // 空白参数仍按空对象处理（国产/本地模型对无参工具常返回 ""）。
        // ★ 截断自愈（2026-10-01）：解析失败先尝试闭合修复（字符串/括号悬空是输出
        //   截断的主要形态），修好了照常执行，只打日志 —— 不再白耗一轮。
        val argsObj = if (tc.arguments.isBlank()) {
            org.json.JSONObject()
        } else {
            runCatching { org.json.JSONObject(tc.arguments) }.getOrNull()
                ?: com.rokidlab.phone.ai.AiToolParamRepair.repairTruncatedJson(tc.arguments)
                    ?.let { repaired ->
                        runCatching { org.json.JSONObject(repaired) }.getOrNull().also {
                            if (it != null) {
                                Log.w(
                                    TAG,
                                    "tool ${tc.name}: arguments JSON repaired after truncation " +
                                        "(${tc.arguments.length} -> $repaired chars)",
                                )
                            }
                        }
                    }
        }
        // ★ 未知工具前置教学（差距③，2026-10-01）：以前走闸门的 UnknownToolGuard
        //   拿回一句「unknown tool: X」，模型只能再猜再错。现在执行侧先查名，
        //   给出最接近的真实工具名（编辑距离 ≤2）当"你可能想调的是"，一步纠正。
        val knownToolNames = ToolRegistry.toolList.map { it.name }.toSet() + ctx.pseudoToolNames
        if (tc.name !in knownToolNames) {
            val suggestion = knownToolNames
                .map { it to com.rokidlab.phone.ai.AiToolParamRepair.editDistance(tc.name, it) }
                .filter { it.second <= 2 }
                .minByOrNull { it.second }
                ?.first
            return "工具 ${tc.name} 不存在（名字拼错或已下线）。" +
                (suggestion?.let { "你可能想调的是 \"$it\"，参数结构以它的 Schema 为准。" }
                    ?: "请核对系统提示里的工具清单后重试。") +
                "若清单里确实没有所需能力，直接告知用户，不要编造工具名。"
        }
        if (argsObj == null) {
            return "工具 ${tc.name} 的参数不是合法 JSON（多半是内容太长被输出长度" +
                "截断，也可能是格式有误）。请修正后重发，不要原样重试：内容过长就拆小" +
                "——一次只写一个文件、单个文件不超过 120 行、多个文件分多次调用。"
        }
        // 唯一审批入口：未知名 / 工具开关 / 无人值守白名单 / per-source 限流 /
        // 本机模式下的眼镜依赖 / 外部副作用的眼镜端确认，全在 ApprovalGate 里判
        // （不再有第二条判定路径）。
        val decision = com.rokidlab.phone.ai.approval.ApprovalGate.preExecute(
            com.rokidlab.phone.ai.approval.ToolSource.CONVERSATION,
            tc.name,
            argsObj,
            // 本机模式（link == null）：用户明确说了不经眼镜 → 眼镜类工具单调拒绝
            localOnly = ctx.phoneOnly,
            // 无人值守（定时自主任务）：装配侧只下发了只读∪媒体白名单，
            // 这里让**执行侧**与之一致 —— 模型凭历史复述出 call_phone 也会被拦下
            unattended = ctx.readOnlyTools,
            // 读工具开关（MCP 第三方工具首次出现时被写成显式 false）
            context = session.appContext,
            // ★ 即将阻塞等用户确认时上报一条 AWAITING_CONFIRM（2026-10-06 审计修）。
            //   确认等待 18~40s 同步阻塞，不发这条的话用户只看到一个无说明的转圈点，
            //   会以为卡死 —— 而它等的正是最危险的副作用动作。
            //   key 与后面那条 RUNNING/终态**相同**（都是 tool:<callId>）⇒ 天然被覆盖，不会多出一行。
            //   摘要由闸门传入（产地唯一，见 ToolEntry.summarize），这里只用来判断"要不要提示渠道"，
            //   不再拼进文案 —— 文案由 awaitingConfirm 的固定 reason 负责，避免与确认框内容不一致。
            onAwaitConfirm = { _ ->
                ctx.onTrace?.invoke(
                    AgentStep.awaitingConfirm(
                        callId = tc.id,
                        name = tc.name,
                        argsRaw = tc.arguments,
                        reason = if (ctx.phoneOnly) "请在手机上确认" else "请在眼镜或手机上确认",
                    ),
                )
            },
        )
        if (decision is com.rokidlab.phone.ai.approval.ToolDecision.Deny) {
            Log.w(TAG, "tool ${tc.name} denied by approval gate: [${decision.origin}] ${decision.reason}")
            // ★ 「用户自己点的取消」标成 CANCELLED 而不是 FAILED（2026-10-06 审计修）：
            //   回来看到一圈红点 + "失败"等于告诉用户"你的操作失败了"，
            //   而实际发生的是"你说了不做，它就没做" —— 后者是**用户的决定**，不是错误。
            //   ⚠️ 判据 2026-10-13 从「reason 文本 contains("已取消")」改为**结构化标志**
            //   [com.rokidlab.phone.ai.approval.ToolDecision.Deny.cancelled]：
            //   读文案的写法太脆 —— 改一个字，"你的决定"就变成"操作失败"的红叉。
            if (decision.cancelled) {
                ctx.onTrace?.invoke(AgentStep.cancelled(tc.id, tc.name, decision.reason))
            }
            // 差距③（2026-10-01）：拒绝信息从"一句话"升级为"错误+原因+下一步"，
            // 按来源给模型可执行的出路 —— 尤其 RISK_CONFIRMATION（问不到用户/被取消）
            // 与 UNKNOWN_TOOL 类拒绝，重试同一调用必然再吃一次 Deny。
            val nextStep = when (decision.origin) {
                com.rokidlab.phone.ai.approval.DecisionOrigin.UNKNOWN_TOOL ->
                    "请核对工具清单与拼写；若确实没有该能力，直接告知用户，不要重试本调用"
                com.rokidlab.phone.ai.approval.DecisionOrigin.RISK_CONFIRMATION ->
                    "该动作必须经用户确认但本次确认通道不可用或被取消，**不要重试本工具**；改用无需确认的方式完成，或如实告知用户"
                com.rokidlab.phone.ai.approval.DecisionOrigin.RATE_LIMIT ->
                    "触发频次限制，属暂时性拒绝：请先执行其他步骤或稍后重试，不要立刻原样重发"
                com.rokidlab.phone.ai.approval.DecisionOrigin.TOOL_DISABLED ->
                    "该工具已被用户在设置里关闭：请改用其他工具完成，或告知用户到「设置 → 乐奇工具」里开启，不要重试本调用"
                else ->
                    "请换用其他合适的方式完成任务，或如实告知用户"
            }
            return "工具 ${tc.name} 被安全策略拦截：${decision.reason}。$nextStep。"
        }
        // A3 修复：带副作用的工具（拨号/短信/日历/安装/打开应用等）失败不重试，
        // 否则隧道瞬断（命令已送达、回执丢失）会触发重复拨号/重复建日程等不可逆后果。
        // 伪工具里也有带副作用的（manage_memory 写记忆、install_skill 装技能、delete_skill 删技能），
        // 它们不在 SIDE_EFFECT_TOOLS（那张表只由真实工具条目派生），必须一并算进来。
        val sideEffecting = ToolRegistry.SIDE_EFFECT_TOOLS.contains(tc.name) ||
            tc.name in com.rokidlab.phone.ai.approval.PseudoTools.sideEffectNames()
        // ★ 子代理背景注入（2026-10-06）：只传**本轮用户消息**，不带整个历史 ——
        //   子代理看不到对话，少了它就无法做指代消解（"这个/刚才那个"指什么），
        //   只能按 question 的字面泛泛地查。走 ThreadLocal 与 vision 的 takePendingImage() 同一套手法，
        //   理由是改 ToolProvider.execute 签名要动全部 21 个 provider，代价与需求不成比例。
        //   由 ReadOnlySubagent **取走即清**（不取会残留到同线程的下一次调用）。
        //   放在重试循环之外：同一次调用的重放不该重复注入。
        if (tc.name == com.rokidlab.phone.ai.subagent.ReadOnlySubagent.TOOL_NAME) {
            com.rokidlab.phone.ai.subagent.ReadOnlySubagent.setPendingUserText(ctx.userText)
            // ★ 内层进度上报（2026-10-06 审计修）：子代理最多 6 轮 × 每轮多个工具、最长 90秒，
            //   此前全部只进 logcat ⇒ 过程时间线上只有外层一行 research_subtask 在转圈，
            //   是唯一一条"用户能明确感到它在骗我"的路径。
            // key 用 `sub:` 前缀，与主循环的 `tool:<id>` 天然不撞（覆盖键相同=同一行）。
            // ★ result 也带上（2026-10-10）：原先终态回调不带结果，每条内层步骤
            //   detail 恒空 —— 用户看到一串工具名却不知道查到了什么、哪一步失败。
            com.rokidlab.phone.ai.subagent.ReadOnlySubagent.setSubStepSink { round, toolName, stateName, result ->
                val state = runCatching { AgentStep.State.valueOf(stateName) }
                    .getOrDefault(AgentStep.State.RUNNING)
                ctx.onTrace?.invoke(
                    AgentStep.subStep(
                        key = "sub:${tc.id}:$round:$toolName",
                        name = toolName,
                        state = state,
                        result = result,
                    ),
                )
            }
        }
        val maxAttempts = if (sideEffecting) 1 else 3
        var lastError: Exception? = null
        repeat(maxAttempts) { attempt ->
            if (attempt > 0) {
                // 指数退避（2026-10-06）：原先固定 500ms，连着三次瞬断（网络抖动/服务冷启动）
                // 会在 1 秒内全部打光，而此时对方根本还没恢复。500→1000→2000ms 才给得出窗口。
                val backoffMs = 500L shl (attempt - 1)
                try {
                    Thread.sleep(backoffMs)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
                Log.w(TAG, "tool ${tc.name} retry #$attempt after ${backoffMs}ms: ${lastError?.message}")
                // ★ 进「过程」（2026-10-10）：重试之间有指数退避的 sleep（最长 2s），
                //   此前界面上是"一个转圈点卡住了"，用户不知道它在重试还是死了。
                //   用 FRAMEWORK 而不是重复工具行：重试是**框架**的决定，不是模型的决定。
                ctx.onTrace?.invoke(
                    AgentStep.framework(
                        "${tc.name} 失败，正在第 $attempt 次重试（${lastError?.message ?: "未知原因"}）",
                        id = "retry_${tc.id}_$attempt",
                        state = AgentStep.State.RUNNING,
                    )
                )
            }
            try {
                return when (tc.name) {
                    com.rokidlab.phone.ai.LongTermMemoryManager.TOOL_NAME ->
                        com.rokidlab.phone.ai.LongTermMemoryManager.execute(
                            session.appContext, tc.arguments,
                        )
                    com.rokidlab.phone.ai.SkillRegistry.TOOL_NAME ->
                        com.rokidlab.phone.ai.SkillRegistry.execute(session.appContext, tc.arguments)
                    com.rokidlab.phone.ai.SkillRegistry.TOOL_NAME_SECTION ->
                        com.rokidlab.phone.ai.SkillRegistry.executeSection(session.appContext, tc.arguments)
                    com.rokidlab.phone.ai.SkillRegistry.TOOL_INSTALL ->
                        com.rokidlab.phone.ai.SkillRegistry.executeInstall(session.appContext, tc.arguments)
                    com.rokidlab.phone.ai.SkillRegistry.TOOL_LIST ->
                        com.rokidlab.phone.ai.SkillRegistry.executeList(session.appContext)
                    com.rokidlab.phone.ai.SkillRegistry.TOOL_DELETE ->
                        com.rokidlab.phone.ai.SkillRegistry.executeDelete(session.appContext, tc.arguments)
                    com.rokidlab.phone.ai.AgentPlan.TOOL_NAME ->
                        com.rokidlab.phone.ai.AgentPlan.execute(tc.arguments)
                    // ask_user（决策点伪工具）：阻塞等用户点选（最长 120s，并行泳道专属线程），
                    // 作答原文回填。过程行不走通用工具行（"执行 ask_user"是黑话）：
                    // 在分支内发「等待用户选择：<问题>」→ 终态「用户已…」两条框架步骤（同 key 覆盖）。
                    com.rokidlab.phone.ai.AskUser.TOOL_NAME -> {
                        val q = com.rokidlab.phone.ai.AskUser.parse(tc.arguments)
                        if (q != null) {
                            ctx.onTrace?.invoke(
                                AgentStep.framework(
                                    "等待用户选择：${q.text}",
                                    id = "ask_${tc.id}",
                                    state = AgentStep.State.RUNNING,
                                ),
                            )
                        }
                        val note = com.rokidlab.phone.ai.AskUserChannel.askParsed(
                            session.appContext, tc.arguments,
                        )
                        ctx.onTrace?.invoke(
                            AgentStep.framework(
                                when {
                                    note.startsWith("用户的选择") || note.startsWith("用户的回答") -> note
                                    note == com.rokidlab.phone.ai.AskUser.DECLINED_NOTE -> "用户跳过了这个问题"
                                    else -> "没有等到用户回应（超时或界面拉不起来）"
                                },
                                id = "ask_${tc.id}",
                                state = AgentStep.State.OK,
                            ),
                        )
                        note
                    }
                    // 真实工具：参数合法性与审批已在重试循环外统一处理，这里直接执行
                    else -> ToolRegistry.execute(session.appContext, tc.name, tc.arguments)
                }
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "tool ${tc.name} attempt ${attempt + 1} failed: ${e.message}")
            }
        }
        Log.e(TAG, "tool execute failed after retry: ${tc.name}", lastError)
        // 教训自动沉淀（Agent 缺口「同一个错误不犯第二次」）：**由代码写**，不指望模型
        // 自觉去调 manage_memory —— "刚失败完先反思再记一条"恰恰是模型最容易跳过的动作，
        // 而大厂 Agent 的自我改进正是靠这层程序化记账（而不是更长的提示词）。
        // 按工具名去重（addLessonOnce）：同一个坑只留第一条，否则错误串每次不同会把教训窗口塞满。
        if (ctx.longTermOn && !ctx.localLight) {
            // ⚠️ 文案由 LessonText 生成：**不含任何工具输出/异常消息**。
            // 异常 message 对 http_request 这类工具可能整段来自对端响应体（攻击者可控），
            // 而教训库每轮注入且尾句是指令性的「按它来」⇒ 写进去就是一条持久指令。
            com.rokidlab.phone.ai.LongTermMemoryManager.addLessonOnce(
                session.appContext,
                dedupKey = tc.name,
                content = com.rokidlab.phone.ai.LessonText.toolFailed(tc.name),
            )
        }
        // 反思引导（Agent 缺口 #4）：瞬时重试耗尽仍失败，不能只回干巴巴的错误串 ——
        // 明确要求模型分析原因并换策略（改参数/换工具/拆任务），禁止原样重试同一调用。
        //⚠️ 这段**可以**引用错误串：它只回填给本轮模型，不进长期记忆库。
        return com.rokidlab.phone.ai.LessonText.retryGuidance(lastError?.message)
    }

    /**
     * 工具执行 + **事件流记录**的唯一包装。
     *
     * 事件流在这里成对落 [ToolCall] / [ToolResult]：一次调用一条、结果一条，
     * callId 配对。于是"调了但没回来"（进程被杀 / 工具卡死）第一次可以被观察到
     * （`AgentSessionStore.orphanToolCalls()`），而改造前它和"调了并成功"
     * 在记录上完全一样（只有一条 `name(args)` 字符串）。
     *
     * 参数与结果都原样落盘（超长由 store 截断并标记）——"它到底传了什么、返回了什么"
     * 是排查工具体系问题时的第一现场。
     *
     * 2026-10-05：从局部 `fun runTool` 原样提为成员方法（隐式捕获转显式入参）。
     */
    private fun runToolInTurn(
        tc: com.rokidlab.phone.ai.ToolCallInfo,
        agentTurn: com.rokidlab.phone.ai.session.AgentTurn?,
        ctx: TurnExecContext,
    ): String {
        agentTurn?.toolCall(tc.id, tc.name, tc.arguments)
        val out = executeToolInTurn(tc, ctx)
        agentTurn?.toolResult(tc.id, tc.name, out)
        return out
    }

    internal fun sendAiTextViaLink(
        link: CXRLink?,
        text: String,
        onResult: ((Boolean, String?) -> Unit)?,
        onReply: ((String) -> Unit)? = null,
        contextText: String? = null,
        interruptOfficialFirst: Boolean = false,
        skipTtsAudioFinished: Boolean = false,
        /** 是否在眼镜端重发用户问题（ASR_Result）：语音唤醒链路中官方已显示提问，传 false 避免重复 */
        showAsrResult: Boolean = true,
        /** 眼镜端是否已本地接管显示（KeyButtonService 在 ASR_End 后已本地打开会话并显示提问）：
         *  为 true 时下行只发 DeepSeek 回复（TTS_Result + tts_play），跳过 KeyDown/open/ASR_Result/ASR_End。
         *  ⚠️ 别给它补发 KeyDown/open：实测会让 Lab 回复「有声音没文字」（见 sendAiTextViaLink 步骤0 注释）。 */
        localTakeover: Boolean = false,
        /** 附加指令：注入 system 提示词控制回答方式（如「只显示答案」「给出解题步骤」） */
        instruction: String? = null,
        /** 是否记录到 Agent 会话记忆（多轮上下文）。拍照答题等一次性场景传 false */
        recordHistory: Boolean = true,
        /** AI 回复流式增量回调（每个 content delta），用于 UI 边生成边显示；眼镜 TTS 仍整段发送 */
        onDelta: ((String) -> Unit)? = null,
        /** 发起时的代际号（sendAiTextMessage 入口抢占）。期间若有更新的代际进入（用户打断），本请求应放弃 */
        generation: Long,
        /** 只读模式（见 sendAiTextMessage 同名参数）：只装配只读工具，禁用 AIUI 子集切换 */
        readOnlyTools: Boolean = false,
        /**
         * Agent 过程回调（思考 / 工具调用），见 [com.rokidlab.phone.ai.AgentStep]。
         * 放在**最后一个参数**是为了让既有位置调用点无需改动。
         */
        onTrace: ((com.rokidlab.phone.ai.AgentStep) -> Unit)? = null,
        /**
         * 多模态图像（base64，不含 `data:` 前缀），语义见 [sendAiTextMessage] 的同名参数。
         * 同样挂在末尾：既有位置调用点加上它只是多一个尾参。
         */
        imageBase64: String? = null,
        /**
         * **本会话的附加提示词**（用户在会话设置里为该会话单独指定；null = 没设）。
         *
         * 加在参数表**末尾**是刻意的：既有调用点大量使用位置参数，插在中间会静默错位。
         * 语义是**追加**到全局人设之后（见 `OpenAiService.buildSystemMessage`），不是替换。
         */
        sessionPrompt: String? = null,
    ) {
        // 串行化所有 AI 下行发送：聊天发送 / ASR push / 文件轮询 / SDK 上行多个并发入口
        // 在 WiFi 稳定连接时全部命中快速路径，同一 CXRLink 并发 sendCustomCmd 会与
        // session.cleanup() 的 disconnect 产生竞态（SDK native 崩溃）。加锁保证同一时刻只有
        // 一条 AI 下行链路执行，并在发送过程中持续校验 link 有效性。
        synchronized(aiSendLock) {
        // 本机模式（link == null）：没有眼镜可校验，跳过链路有效性检查
        val phoneOnly = link == null
        if (link != null && !abortAiSendIfLinkInvalid(link, onResult)) return
        // 排队期间已有更新的请求进入（用户再次说话/发消息）：本请求作废，让出链路并复位调用方，
        // 避免过期任务抢到锁后继续跑多轮工具（用户新请求正在等待接管）
        if (generation != aiGenSeq) {
            Log.i(TAG, "sendAiTextViaLink superseded by newer request, abort (gen=$generation != latest=$aiGenSeq)")
            session.mainHandler.post {
                session.connection.completeActiveOperation()
                session.onBusyChanged(false)
                onResult?.invoke(false, null)
            }
            return
        }
        session.onBusyChanged(true)

        // 下行显示段与发送段（两个 try 块）共享的变量，提升到 try 外避免作用域不可见
        var reply = ""
        var asrResult: Int? = 0
        var endResult: Int? = 0
        // 播报文本（口语文本，剥 markdown；与显示文本分离的口径见下方 TTS_Result 段注释）
        var glassSpoken = ""
        // 云萌音色预合成的 PCM（reply ready 后填充；发送段起播用，null = 未启用/准备失败）
        var cloudPcm: ByteArray? = null

        try {
        // ===== 步骤-1: （可选）先打断官方乐奇会话 =====
        // ⚠️ 2026-09-17 实测：这条**入站** `Exit` 到了官方只会走 `AIExitHandler.handle`，
        // 官方**不会** dismissAiDialog / clearData（真机判据：14:15:13.417 有 AIExitHandler，
        // 但直到 14:15:38 官方自身超时才出现 clearData）。更糟的是它把官方置为「已退出」态，
        // 之后 Lab 的 TTS_Result 只播声音、不再进 UI（`TtsResultHandler` 有日志但气泡不更新）
        // ⇒ 用户看不到 Lab 回复。故眼镜语音链路（localTakeover）暂时**不发**这条；
        // 待找到真正能清空官方内容又不影响 Lab 渲染的手段再启用。
        val runOfficialInterrupt: () -> Unit = {
            // 本机模式没有眼镜可打断：link == null 时整段跳过（否则会在此处 NPE/编译不过）
            if (interruptOfficialFirst && !localTakeover && link != null) {
                val exitCaps = Caps()
                exitCaps.write("Exit")
                val exitResult = link.sendCustomCmd(LinkProtocol.CXR_CHANNEL_AI, exitCaps)
                Log.i(TAG, "sendCustomCmd(Ai, Exit) interrupt official -> $exitResult")
                Thread.sleep(300)
            }
        }

        // ===== 步骤3 提前并行：后台线程调用 AI 获取回复（与下行显示并行，省 1.5~2s）=====
        // 支持工具调用（function calling）：AI 可自主决定调用本地能力（如知识库检索），
        // 执行结果回填后再生成最终回复；最终回复照常走下方 TTS 链路到眼镜显示并语音播报。
        // 生成期间「应放弃」判定：两条独立失效来源 ——
        //   1) 代际被更新请求抢占（用户再次说话/发消息）；
        //   2) 本条请求绑定的 link 被替换/断开（会话重建，例如安装/启动等非 AI 操作触发的重连，
        //      这类重建不会 bump 代际，只能靠 link 身份兜底，否则生成线程会带着已失效的链路空跑）。
        // ⚠️ 本机模式（link == null）必须排除第 2 条：此时 session.cxrLink 是 null 或**别人的**链路，
        //    若仍比对身份，会立刻把自己判成「已被替换」而中断（用户侧表现为本机模式发消息没回复）。
        val isSuperseded: () -> Boolean = {
            generation != aiGenSeq || (link != null && session.cxrLink !== link)
        }

        // 本地轻量会话标志（catch 分支也要用，故提到 try 外；try 内算出后回填）
        var localLightMode = false
        val replyRef = java.util.concurrent.atomic.AtomicReference<String>("")
        // 生成体（2026-10-02 协程化）：原先是一个 namedThread 裸线程，抢占要等它跑到
        // 下一个代际检查点才能退出；现在交给 [launchAiGeneration] 跑进结构化并发 +
        // runInterruptible —— 被抢占/打断时 cancel 当场转化为线程中断，
        // 阻塞在 OkHttp 网络读上的生成立刻退出（不再白等一个 readTimeout）。
        // ⚠️ body 必须以**尾 lambda** 形式传给函数（K2 禁止对赋值给 val 的 lambda 做
        // 标签 return，原 return@namedThread 之所以合法正是"函数实参的尾 lambda"形态）
        val deepSeekJob = launchAiGeneration(generation) {
            val tGenStart = System.currentTimeMillis()
            // 本轮的事件流记录句柄（null = 不记录：拍照答题 recordHistory=false / 记忆开关关闭 /
            // 会话尚未绑定）。提到 try 外是为了让 catch 分支也能收尾本轮 —— 否则被打断/失败的轮
            // 会永远停在只有 TurnStart 的半轮状态。
            var agentTurn: com.rokidlab.phone.ai.session.AgentTurn? = null
            // ★ 本轮**已推给 UI 的增量正文**（2026-10-09，被打断时落盘用）。
            // 改造前这半截内容只存在于屏幕气泡里，事件流中一条 assistant 都没有 ——
            // 用户看得见 AI 说到一半，说「继续」时模型却不知道自己说过什么。
            //
            // ⚠️ 声明必须与 [agentTurn] 同级（在 try **之外**）：catch 分支里也要读它
            // （异常导致的打断同样要落半截），放在 try 内则 catch 处不可见。
            val streamedBuf = StringBuilder()
            try {
                val cfg = session.getAiConfig()
                // 是否本机 Ollama 端点：判断口径统一走 LlmRegistry（改造前这里另有一份完全相同的
                // 字符串判断，与构造参数里的口径各写一遍，任何一处漏改都会让本地模型的超时/调参失效）
                val localBase = com.rokidlab.phone.ai.llm.LlmRegistry.isLocalBase(cfg.baseUrl)
                // 本地用户自定义请求参数（JSON，替代原「深度思考」布尔开关）：逐字段合并进每次
                // 本地对话请求体（如 {"think": false, "options": {"num_ctx": 2048}}）；远程服务不附加
                val localParams = if (localBase) session.aiConfig.parseLocalChatParams() else null
                // 本轮附带的运行时参数（发送键旁「思考」开关：仅在线 DeepSeek 生效；
                // 本地模型由 localParams 自行调参）
                //
                // ★ 任务轮自动开思考（2026-10-01）：deepseek-flash 这类混合思考模型，
                //   官方 agent 基准全在思考开启口径下测得；改造前只有用户手动开「长思考」
                //   才有思考，默认档（ai_thinking=false）每轮都显式关思考 —— 等于让最强
                //   agent 档位全程裸奔，是「生成的代码差 / 执行逻辑差」的第一成因。
                //   分工口径：**闲聊快路轮关**（0 工具，秒回）、**高置信路由轮关**
                // （天气/时间/算式 2~3 个 Schema，思考反而伤首字延迟）、**其余任务轮开**
                // （菜单探测 / 全量 / [NEED_TOOLS] 升级 —— 都是要动脑的多步任务）。
                // isPureChat / route 都是对 text 的纯函数，在这里先算一次、循环内照旧
                // 各自再调，结果必然一致。图片/答题轮也不开（首字延迟敏感）。
                // 已知留白：路由命中后 [NEED_TOOLS] 升级到全量的轮沿用轮 0 的决定（不开），
                //   概率低（路由误判才触发），先不做跨轮改档 —— 那会破坏客户端实例复用。
                val autoThink = imageBase64 == null && instruction == null &&
                    com.rokidlab.phone.ai.llm.ModelPresets.prefersThinking(cfg.model) &&
                    !com.rokidlab.phone.ai.ChatFastPath.isPureChat(text) &&
                    com.rokidlab.phone.ai.ToolIntentRouter.route(text) == null
                val llmOptions = com.rokidlab.phone.ai.llm.LlmRegistry.Options(
                    thinkingEnabled = session.isThinkingEnabled() || autoThink,
                    localParams = localParams,
                )
                // 路由解析 + 打点：这一轮实际打到哪个服务商/模型、它有哪些能力。
                // 「连续空轮」「工具请求 400」「送图被拒」的第一现场都在这几个数字里 ——
                // 改造前这些事实在日志里完全看不到（能力根本没有表达，见 ModelCapabilities）。
                val route = com.rokidlab.phone.ai.llm.LlmRegistry.route(cfg, session.appContext, llmOptions)
                Log.i(
                    TAG,
                    "AI route: provider=${route.provider} model=${route.model} local=${route.isLocal} " +
                        "ctx=${route.capabilities.contextWindow} tools=${route.capabilities.supportsTools} " +
                        "image=${route.capabilities.supportsImage} capSrc=${route.capabilities.source}",
                )
                // 客户端构造收口到 llm 接缝（超时/本地调参/关思考字段的取值规则只在注册表一份）
                val service = com.rokidlab.phone.ai.llm.LlmRegistry.newService(
                    cfg,
                    com.rokidlab.phone.ai.llm.LlmRegistry.Profile.CHAT,
                    llmOptions,
                )
                // Agent 会话记忆：注入历史消息（多轮上下文），使 AI 能理解「再来一首」等指代。
                // ⚠️ 这里**不再**做空闲过期清理：原先"10 分钟无活动自动清空"已于 2026-09-20 移除 ——
                //    它与"记忆随对话持久保存"的用户心智正面相抵，而且是**静默**发生的（满屏对话还在、
                //    AI 却不认了）。清空现在只由用户显式触发（聊天页「清空对话」/ 设置页「清空全部会话记忆」）。
                val agentSession = com.rokidlab.phone.ai.AgentSessionManager
                // 压缩预算跟着这一轮的模型窗口走（compaction 接缝 ← llm 接缝：
                // ModelCapabilities.contextWindow 就是它的输入）。规则是**只收紧不放宽**，
                // 大窗口模型与窗口未知时行为与改造前逐字一致（见 CompactionPolicy.forWindow）。
                agentSession.applyModelCapabilities(route.capabilities)
                // 同时校验 AgentSessionManager 开关，关闭时本次不注入历史也不记录本轮
                val memoryEnabled = agentSession.isEnabled(session.appContext)
                val effectiveRecord = recordHistory && memoryEnabled
                // 本轮用户输入的来源（"这句是说的、打的、还是拍的"）—— 只为落盘回溯，不参与逻辑
                val msgSource = when {
                    imageBase64 != null -> com.rokidlab.phone.ai.session.MessageSource.IMAGE
                    // 眼镜端已本地接管显示 = KeyButtonService 在 ASR_End 后接管，即语音唤醒链路
                    localTakeover -> com.rokidlab.phone.ai.session.MessageSource.VOICE
                    else -> com.rokidlab.phone.ai.session.MessageSource.TEXT
                }
                // ★ 开始一轮事件流记录：轮号由事件流唯一分配，用户消息**此刻就落盘** ——
                //   生成到一半进程被杀，也不至于丢掉"用户问了什么"（改造前只在一轮结束时
                //   一次性 recordTurn，中途崩溃等于这轮从未存在）。
                if (effectiveRecord) {
                    agentTurn = agentSession.beginTurn(msgSource)
                    agentTurn?.userMessage(text)
                }
                // 决策门钩子（只在用户真实发起的轮次生效）：刷新空闲检测活跃时刻 +
                // 「别烦我」句式命中记 24h 冷却。自主任务/拍照答题 recordHistory=false，
                // Agent 自发的 prompt 不算「用户活跃」，否则空闲问候永远不触发
                if (recordHistory) {
                    com.rokidlab.phone.proactive.ProactiveGate.get(session.appContext)
                        .onUserText(text)
                }
                // 长期记忆：跨会话记住用户事实/偏好（注入 <memories> + 注册 manage_memory 工具）
                val longTermMemory = com.rokidlab.phone.ai.LongTermMemoryManager
                val longTermOn = longTermMemory.isEnabled(session.appContext)
                // 检索式注入：按当前提问相关性取 top-K 长期记忆（无相关性时回退最近 K 条）
                val longTermContext = if (longTermOn) longTermMemory.memoriesContext(session.appContext, text) else null
                // 经验教训（Agent 缺口「同一错误不犯第二次」）：同样是跨会话积累，但与用户事实
                // 分开成独立注入窗口 —— 教训是会自己长大的（失败后自动写、模型也可主动记），
                // 混在一个 top-K 里迟早把用户亲口说过的事实挤出窗口（见 LongTermMemoryManager 类注释）。
                val lessonsContext = if (longTermOn) longTermMemory.lessonsContext(session.appContext, text) else null
                // 本地模型 → 本地轻量会话：不装配工具/技能/长期记忆工具，精简人设，仅闲聊问答。
                // 本地小模型背不动全部工具 Schema（每轮全量下发拖慢 prefill 且小模型调用工具不可靠），
                // 设备操作/联网等能力由用户切回在线 Agent 提供（对齐 RikkaHub 按会话装配思路）。
                val localLight = localBase
                localLightMode = localLight
                // 用户自定义技能：注入技能清单（第 1 层渐进披露）+ 注册 load_skill 伪工具（仅在线 Agent）
                //
                // ⚠️ 这里拆成两个概念，不要合并：
                //  - [skillsOn] = 技能功能是否可用（在线 Agent + 总开关开）→ 决定**技能工具下不下发**；
                //  - [skillsContext] = 有没有**技能清单可注入**（零技能时为 null）→ 只决定清单那段文本。
                // 合成一个布尔会漏掉「一个技能都没装」这个场景：那时清单为空、却恰恰是最需要
                // install_skill 的时候（用户说「把这个技能装上」）—— 旧实现按清单空否挂工具，
                // 于是零技能用户**根本没有装技能的工具**，只能自己去设置页点。
                val skillsOn = !localLight && com.rokidlab.phone.ai.SkillRegistry.isEnabled(session.appContext)
                val skillsContext = if (skillsOn) {
                    com.rokidlab.phone.ai.SkillRegistry.skillsContext(session.appContext)
                } else null
                // 伪工具的菜单/点名口径（R11）：伪工具不在 ToolRegistry.toolList 里，
                // 菜单漏列 + 点名集合不含它们 ⇒ 模型回「[TOOLS] update_plan」会被静默丢弃
                // （它以为点到了）。这里的**装配条件必须与 buildTools 的伪工具装配逐条一致** ——
                // 菜单承诺了的，点名后必须真能被装配出来，否则又是一个"空头承诺"。
                // 一份产地供「菜单文本」与「点名解析的 knownNames」两处共用。
                val pseudoToolEntries: List<Pair<String, String>> = buildList {
                    if (longTermOn) {
                        add(LongTermMemoryManager.TOOL_NAME to "读写长期记忆：跨会话记住/查询用户的事实与偏好")
                    }
                    if (!localLight) {
                        add(com.rokidlab.phone.ai.AgentPlan.TOOL_NAME to "多步任务显式规划：生成带状态的步骤计划")
                        add(com.rokidlab.phone.ai.AskUser.TOOL_NAME to "需要用户拍板时给出候选项，弹选项卡等用户点选后继续")
                    }
                    if (skillsOn) {
                        if (skillsContext != null) {
                            add(com.rokidlab.phone.ai.SkillRegistry.TOOL_NAME to "加载指定技能的完整说明")
                            add(com.rokidlab.phone.ai.SkillRegistry.TOOL_NAME_SECTION to "按章节读技能文档（省 token）")
                        }
                        add(com.rokidlab.phone.ai.SkillRegistry.TOOL_INSTALL to "安装技能包")
                        add(com.rokidlab.phone.ai.SkillRegistry.TOOL_LIST to "列出已安装的技能")
                        add(com.rokidlab.phone.ai.SkillRegistry.TOOL_DELETE to "删除已安装的技能")
                    }
                }
                // 自动 RAG（Agent 缺口 #3）：知识库有文档时，按当前提问自动检索 top-2 命中注入
                // system 提示词（带文档名/块序号来源），不再依赖模型自觉调用 search_knowledge_base
                // —— 词法检索对「换个说法问」的召回缺口仍由模型按需补调工具（工具口保留，双通道）。
                // 限制：仅在线 Agent、提问长度合理时才检索，避免短感叹词/超长粘贴无意义扫库。
                //
                // ⚠️ 改造前这里只有"命中才注入"，于是词法检索一失手（用户换了个说法、或问的
                //    是「这个文档讲了什么」这种没有可命中关键词的泛问），模型连"存在知识库"都
                //    不知道 ⇒ 既没有资料、又不会去调 search_knowledge_base，表现就是
                //    "问文档里的内容答不出来"。所以**未命中也要把库里有哪些文档告诉模型**
                //    （只给文档名这份"目录"，不塞正文，避免把无关内容误当答案依据）。
                val kb = com.rokidlab.phone.ai.KnowledgeBase
                val kbDocs: List<com.rokidlab.phone.ai.KbDocInfo> = if (localLight) {
                    emptyList()
                } else {
                    runCatching { kb.listDocs(session.appContext) }.getOrDefault(emptyList())
                }
                // 真去检索一次：命中块既用于注入提示词，也用于「过程」里的来源标注
                val kbSearched = kbDocs.isNotEmpty() && text.length in 4..500
                val kbHits: List<com.rokidlab.phone.ai.KbHit> = if (kbSearched) {
                    runCatching { kb.searchHits(session.appContext, text, 2) }.getOrDefault(emptyList())
                } else {
                    emptyList()
                }
                // 「过程」可视化（2026-09-20）：这条检索**不是工具调用**（没有 tool_call id），
                // 若不手工上报，用户就完全看不到"AI 先去查了我导入的文档" —— 只看到它直接回答了，
                // 于是合理地怀疑"它到底看没看我传的东西"。只在**真的检索过**时上报
                // （没知识库、或提问过短/过长时不发），不凭空多出一步。
                if (kbSearched) {
                    onTrace?.invoke(
                        AgentStep.knowledge(
                            hitCount = kbHits.size,
                            sources = kbHits.map { "《${it.docName}》第${it.chunkIdx + 1}块" },
                            docCount = kbDocs.size,
                        ),
                    )
                }
                val kbContext = com.rokidlab.phone.ai.ContextAssembly.knowledgeContext(
                    hitLines = kbHits.map {
                        com.rokidlab.phone.ai.ContextAssembly.formatHit(it.docName, it.chunkIdx, it.text)
                    },
                    docCount = kbDocs.size,
                    docNames = kbDocs.map { it.name },
                )
                // 与调用方已带的 contextText（如拍照答题资料）合并，共用「知识库参考资料」槽位
                val mergedContext = com.rokidlab.phone.ai.ContextAssembly.mergeSources(kbContext, contextText)
                // 预算自感知（Agent 缺口「自我认知」）：把本轮轮次预算如实写进提示词，
                // 让模型有能力规划"把额度花在哪" —— 旧行为是触顶后静默截断，
                // 模型和用户都不知道为什么任务只做了一半。
                val budgetNote = if (localLight) null else {
                    com.rokidlab.phone.ai.ContextAssembly.budgetNote(
                        maxTotalRounds = MAX_TOTAL_ROUNDS,
                        maxReadonlyRounds = MAX_READONLY_ROUNDS,
                        maxActionRounds = MAX_ACTION_ROUNDS,
                        maxAutoContinues = MAX_AUTO_CONTINUES,
                        // token 上限只在**模型窗口已知**时给（未知时预算不限，给一个猜的数
                        // 反而会让模型按错误的假设分配步骤）。口径与 forWindow 一致。
                        contextWindow = route.capabilities.contextWindow,
                    )
                }
                // 长任务续做（Agent 缺口「长任务可恢复」）：TTL 内未完成任务的流程级状态
                // （原话 / 计划进度 / 已产出文件 / 上次中断原因）注入提示词 —— 用户说「继续」时
                // 模型能接着做而不是从头重来；已完成或过期任务不会注入。
                val taskNote = if (localLight) null else {
                    com.rokidlab.phone.ai.ContextAssembly.taskNote(
                        com.rokidlab.phone.ai.AgentTaskStore.pendingContext(session.appContext)
                    )
                }
                // 事件流：把本轮**实际注入提示词的上下文**记下来。改造前这些注入是一次性的 ——
                // 拼进 system、发出去、消失，用户问"它怎么知道这个"时无从回溯。
                // 超长自动截断并标记（见 AgentSessionStore.appendContextInject）。
                agentTurn?.contextInject("memory", longTermContext)
                agentTurn?.contextInject("lesson", lessonsContext)
                agentTurn?.contextInject("knowledge", mergedContext)
                agentTurn?.contextInject("skills", skillsContext)
                agentTurn?.contextInject("task", taskNote)
                agentTurn?.contextInject("budget", budgetNote)
                // ★ 注入也进「过程」（2026-10-10）。此前 6 个 origin 里只有 `knowledge`
                //   有对应 Kind（KNOWLEDGE），另外 5 类对用户完全隐形 ——
                //   于是"它凭什么知道我的偏好/上次教训/这个技能/还有哪些没做完"全都没有答案。
                //
                // ⚠️ 只显示**来源摘要，不显示注入内容**：注入体可能含外部文档正文，
                //   原样回显既刷屏，又把外部内容重新摆到用户面前（注入隔离的意义就在这里）。
                //   因此这里给的是"注入了什么来源"，不是"注入了什么字"。
                onTrace?.let { tr ->
                    // 记忆：只报条数，不报内容（内容是用户自己说过的，但没必要在过程里复述）
                    longTermContext?.takeIf { it.isNotBlank() }?.let {
                        tr(AgentStep.contextInjected("memory", "已注入长期记忆"))
                    }
                    lessonsContext?.takeIf { it.isNotBlank() }?.let {
                        tr(AgentStep.contextInjected("lesson", "已注入历史教训"))
                    }
                    // knowledge 走 [AgentStep.knowledge]，它带命中来源（用户核对答案出处要用）
                    skillsContext?.takeIf { it.isNotBlank() }?.let {
                        tr(AgentStep.contextInjected("skills", "已注入技能说明"))
                    }
                    taskNote?.takeIf { it.isNotBlank() }?.let {
                        tr(AgentStep.contextInjected("task", "已接上未完成的任务进度"))
                    }
                    budgetNote?.takeIf { it.isNotBlank() }?.let {
                        tr(AgentStep.contextInjected("budget", "已告知本轮预算限制"))
                    }
                }
                // 当前提问那条 user 消息（多模态时 content 是 text + image_url 分片数组）。
                // 独立构造一次：溢出恢复要整体重建 messages，重建时复用同一条提问。
                val userMsg = JSONObject()
                userMsg.put("role", "user")
                if (imageBase64.isNullOrBlank()) {
                    userMsg.put("content", text)
                } else {
                    // 多模态：content 改成分片数组。文本在前、图片在后 —— 先给"要做什么"再给素材，
                    // 符合模型读取指令+素材的常规顺序。
                    // data URL 前缀必须带（OpenAI 兼容服务靠它判图片类型），base64 本体不含换行
                    // （`Base64.NO_WRAP`），否则 JSON 里出现裸换行会直接坏掉请求体。
                    userMsg.put(
                        "content",
                        JSONArray().apply {
                            put(JSONObject().apply {
                                put("type", "text")
                                put("text", text)
                            })
                            put(JSONObject().apply {
                                put("type", "image_url")
                                put(
                                    "image_url",
                                    JSONObject().put("url", "data:image/jpeg;base64,$imageBase64"),
                                )
                            })
                        },
                    )
                }
                // ★ 闲聊快路（2026-09-29，结构性省 token）：高置信纯闲聊轮不装配任何工具 ——
                // 83 个工具 Schema 每轮 ~2 万 input token 是闲聊场景的成本大头（方舟等无
                // 前缀缓存的端点每轮全价）。误判兜底=精简人设里的逃生舱：模型声明
                // [NEED_TOOLS] 时主循环自动挂载全量工具原地重来（见下方升级分支）。
                // 触发条件：只读自主任务/图片/答题指令不走。
                // ⚠️ 2026-09-29 第六轮：撤掉「上一轮用过工具就跳过」的粘滞位 —— 三层的逃生舱
                // 已齐（[NEED_TOOLS] 升级/菜单点名），粘滞位只剩延迟收益，却让「查天气→谢谢/
                // 那上海呢」这类最高频的后续轮全价发 83 个 Schema（方舟无缓存端点尤其亏）。
                var fastPathActive = !localLight && !readOnlyTools && imageBase64 == null &&
                    instruction == null &&
                    com.rokidlab.phone.ai.ChatFastPath.isPureChat(text)
                if (fastPathActive) {
                    Log.i(TAG, "chat fast path armed: pure-chat turn, tools withheld (probe mode)")
                }
                // ★ 意图路由（第二刀，2026-09-29）：高置信任务意图 → 只装配命中意图的工具子集
                //（按工具名截取，不按粗域）。天气问题带 3 个 Schema 而不是 83 个。与快路同款
                // 护栏（本机/只读/图片/答题不走）；结构排除（写代码/生成页面/翻译等）在路由器
                // 内部兜底。误判逃生舱与快路共用 [NEED_TOOLS]：模型喊标记或子集轮空响应 →
                // 升级全量（见下方升级分支）。子集**贯穿本轮不扩全量**（2026-09-29 第七轮，
                // 实测 42.4k 账单教训）：扩全量让工具循环的每一轮都背全价 83 个 Schema，
                // 还会打断缓存前缀；多步意图由路由并集覆盖（「查天气然后提醒我」= 天气∪提醒），
                // 清单外能力由 escapeNote 里的 [NEED_TOOLS] 约定兜底。粘滞位已撤（第六轮）。
                var routedToolNames: Set<String>? = if (!fastPathActive && !localLight &&
                    !readOnlyTools && imageBase64 == null && instruction == null
                ) {
                    com.rokidlab.phone.ai.ToolIntentRouter.route(text)
                } else {
                    null
                }
                if (routedToolNames != null) {
                    Log.i(TAG, "tool intent routed: ${routedToolNames.size} schemas ($routedToolNames)")
                }
                // ★ 菜单模式（第三层兜底，2026-09-29）：关键词路由没命中的任务轮，不再无脑
                // 全量 83 个 Schema —— 发轻量「工具菜单」（名称+一句话用途，≈2k token）+
                // 引导工具集（搜索/读网页/时间，见 BOOTSTRAP_TOOLS），模型先查证、再点名
                // [TOOLS]，点中后按名装配完整 Schema。菜单模式贯穿本轮所有轮次（模型随时可
                // 追加点名）；全量 83 只剩 [NEED_TOOLS]（模型坚持要清单外能力）与探测轮空响应
                // 两种窄兜底。护栏与快路/路由同款；粘滞位已撤（同快路，2026-09-29 第六轮）——
                // 「那上海呢」「再来一首」这类工具对话衔接句现在走菜单点名（多一次探测 RTT，
                // 换每轮 ~1.8 万 input token 的节省，方舟无缓存端点下净赚）。
                // ★ 续说词豁免（2026-10-09，P1-10）：**接着上一轮继续做**的短句（继续/重写/还是不行）
                // 关键词路由天然无命中，原先会掉进菜单探测轮 —— 而探测轮**没有写文件能力**，
                // 模型想改也改不了，只能再读一遍文件（gps-live 案 T5/T6/T7 三轮都是这样空转的）。
                // 判据两段：① 句子指代上一轮产物（`isContinuation`）② 上一轮确实动过手
                // （`lastTurnHadToolCalls`）—— 少了 ② 会把"继续聊天"也误判成续做任务。
                // ⚠️ 这不违反「路由子集不自动扩全量」那条教训：那是"别为省事无脑扩全量"，
                // 这里是"续做轮本就需要动手能力，而探测轮把它削没了" —— 修的是能力缺失。
                val continuationTurn = com.rokidlab.phone.ai.ToolIntentRouter.isContinuation(text) &&
                    lastTurnHadToolCalls
                if (continuationTurn) {
                    Log.i(TAG, "continuation turn: skip menu probe, give full toolset (text=${text.take(20)})")
                }
                var menuModeActive = !fastPathActive && routedToolNames == null && !localLight &&
                    !readOnlyTools && imageBase64 == null && instruction == null && !continuationTurn
                // 探测轮 = 菜单模式的第一轮（不流式；响应后菜单收束，后续轮转常规处理）
                var menuProbeActive = menuModeActive
                // 可用工具随会话推进可变：主 Agent 全量域起步；命中 AIUI 生成场景后切到精简
                // AIUI 子集，每轮少发 ~14 个无关工具 Schema（省 input session.token、加快 prefill）。
                // buildTools 按域装配，并附上仅在线 Agent 的长期记忆 manage_memory 与技能
                // load_skill/load_skill_section 两个动态伪工具；切换子集时复用同一装配逻辑。
                //
                // ⚠️ 必须排在 buildMessages 之前：系统提示里的"可选能力"条款要按**真实下发的**
                // 工具做闸门（见 buildSystemMessage 的 availableTools），所以先装配工具、再装消息。
                val buildTools: (Set<String>) -> MutableList<JSONObject> = { domains ->
                    val built = if (readOnlyTools) {
                        // 无人值守（定时自主任务，见 readOnlyTools 说明）：只读档 + 本地媒体白名单。
                        // 连伪工具也不挂 —— manage_memory 会写长期记忆库、load_skill 可能把模型
                        // 引向代码生成（写出文件），update_plan 的进度也无人看，全部无意义。
                        ToolRegistry.schemasUnattended(session.appContext, excludeGlassesTools = phoneOnly).toMutableList()
                    } else {
                        // 本机模式（无眼镜）：把「需要眼镜的工具」从 Schema 里摘掉 ——
                        // 模型看不到就不会去调，避免每轮白等一次注定失败的调用与 15s 超时。
                        ToolRegistry.schemasFor(session.appContext, domains, excludeGlassesTools = phoneOnly).toMutableList().apply {
                            if (longTermOn && !localLight) add(longTermMemory.schema())
                            // 任务计划伪工具（Agent 缺口 #1）：所有域子集都带，多步任务显式规划
                            if (!localLight) add(com.rokidlab.phone.ai.AgentPlan.schema())
                            // 决策点伪工具（2026-10-12）：需要用户拍板时弹选项卡收集作答。
                            // 与 update_plan 同闸门（localLight 本地轻量无此链路）；
                            // 菜单探测/意图路由子集按名截取时天然不带 —— 决策点只出现在完整 Agent 任务里。
                            if (!localLight) add(com.rokidlab.phone.ai.AskUser.schema())
                            if (skillsOn) {
                                // 读技能（第 2 层按需加载）：只有**装了技能**才挂 —— 零技能时
                                // load_skill 没有任何可加载对象，挂上只会诱使模型空调一次。
                                if (skillsContext != null) {
                                    add(com.rokidlab.phone.ai.SkillRegistry.schema())
                                    add(com.rokidlab.phone.ai.SkillRegistry.sectionSchema())
                                }
                                // 技能管理三件套（装/看/删）：与"有没有技能"无关，只要技能功能开着就挂 ——
                                // 零技能时恰恰最需要 install_skill（用户说"把这个技能装上"）。
                                add(com.rokidlab.phone.ai.SkillRegistry.installSchema())
                                add(com.rokidlab.phone.ai.SkillRegistry.listSchema())
                                add(com.rokidlab.phone.ai.SkillRegistry.deleteSchema())
                            }
                        }
                    }
                    // 本地严格校验一遍（每进程一次）：服务端对 schema 是严格校验，
                    // 一个节点形状写错会让整个请求 400、所有工具一起失效
                    auditToolSchemas(built)
                    built
                }
                var activeTools = when {
                    fastPathActive -> mutableListOf()
                    // 菜单探测轮：只挂引导工具集（搜索/读网页/时间），全量清单以菜单文本
                    // 进上下文尾（见 buildMessages）—— 模型可先查证再点名，不空手探测
                    menuProbeActive -> {
                        val boot = com.rokidlab.phone.ai.ToolIntentRouter.BOOTSTRAP_TOOLS
                        buildTools(ToolRegistry.SESSION_AGENT_DOMAINS)
                            .filter { ToolRegistry.nameOf(it) in boot }
                            .toMutableList()
                    }
                    // 意图路由子集：从全量装配里按名截取（设置页禁用状态照旧生效，auditToolSchemas
                    // 校验也照跑）；伪工具（长期记忆/任务计划/技能）本轮一律不挂 —— 求最小 Schema 面。
                    routedToolNames != null -> {
                        val routed = routedToolNames!!
                        val subset = buildTools(ToolRegistry.SESSION_AGENT_DOMAINS)
                            .filter { ToolRegistry.nameOf(it) in routed }
                            .toMutableList()
                        // ★ 空集兜底（2026-10-13 审计修）：路由词表与注册表**可能漂移**
                        //   （工具改名/被设置页禁用后，词表里的名字一个都匹配不上）。
                        //   改造前路由分支不检查空集 ⇒ 本轮下发 0 个工具，模型"想调也没得调"，
                        //   且不报错（表现为"AI 说做不了"）。与菜单点名分支的兜底（同上）对齐：
                        //   退化成全量，宁可多花 token 也不让能力凭空消失。
                        if (subset.isEmpty()) {
                            Log.w(TAG, "routed tool subset empty (routed=$routed) -> fall back to full set")
                            buildTools(ToolRegistry.SESSION_AGENT_DOMAINS).toMutableList()
                        } else {
                            subset
                        }
                    }
                    else -> buildTools(
                        if (localLight) ToolRegistry.SESSION_LOCAL_DOMAINS
                        else ToolRegistry.SESSION_AGENT_DOMAINS,
                    )
                }
                // 本次请求**真正下发**的工具名（装配侧的唯一投影，供系统提示做能力闸门）。
                // 切换域子集（aiuiMode）时一并更新 —— 见下面两处 buildTools 调用。
                var assembledToolNames: Set<String> = ToolRegistry.namesOf(activeTools)

                // 项目规则（差距⑥，2026-10-01）：用户写在「下载/RokidLab规则.md」的约定，
                // 代码/项目类任务注入 system。**懒加载 + 每任务只读一次**：闲聊快路轮零开销
                // （不注入），工具轮首次装配时读；缓存后任务内逐字节稳定（缓存前缀纪律）。
                // MediaStore 查询 + 流读取都套 runCatching：文件不存在/读取失败 = 无规则，静默降级。
                var projectRulesCache: String? = null
                var projectRulesLoaded = false
                fun projectRules(): String? {
                    if (projectRulesLoaded) return projectRulesCache
                    projectRulesLoaded = true
                    if (localLight || readOnlyTools) return null
                    projectRulesCache = runCatching {
                        com.rokidlab.phone.ai.FileWorkspace.openDownloadInput(
                            session.appContext,
                            PROJECT_RULES_RELPATH,
                        )?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                    }.getOrNull()
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                        ?.take(PROJECT_RULES_MAX_CHARS)
                    if (projectRulesCache != null) {
                        Log.i(TAG, "project rules loaded: ${PROJECT_RULES_RELPATH} " +
                            "(${projectRulesCache!!.length} chars)")
                    }
                    return projectRulesCache
                }

                // 消息序列的**装配**抽成可重放的 lambda（compaction 接缝的 CONTEXT_OVERFLOW 需要）。
                //
                // 溢出恢复**不能**就地删掉 in-flight 里最早的 assistant/tool 段：拆散
                // assistant(tool_calls) 与它对应的 tool 结果，服务端会直接以 400 拒绝
                // （这正是 DSH 要求"压缩范围保持 tool-call/result 配对平衡"的那件事）。
                // 「丢掉本轮已累积的一切、按压缩后的历史重新装配一遍」是唯一协议合法的收敛方式。
                val buildMessages: () -> JSONArray = {
                    JSONArray().apply {
                        // 稳定头部（人设+工具准则+风格，逐字节稳定）：DeepSeek/OpenAI 服务端按请求
                        // 前缀自动做 prompt caching，首条 system 一旦掺入每轮变化的记忆/资料，
                        // 缓存就从那里断掉 —— 命中价 ≈ miss 价的 2%，差距是真金白银。
                        put(
                        // 闲聊快路：精简人设（无工具准则，含 [NEED_TOOLS] 逃生舱约定）；
                        // 菜单探测轮：中间档人设（2026-10-12 P2 —— 探测轮只挂 3 个引导工具，
                        // 完整版人设 ~10.5k 字符的准则绝大多数用不上，却每轮付全价；
                        // 点中工具重建请求时 menuProbeActive 已复位，自动换回完整人设）；
                        // 正常轮：完整人设，可选能力条款按**本次真正下发的**工具做闸门
                        if (fastPathActive) service.buildChatOnlySystemMessage()
                        else if (menuProbeActive) service.buildMenuProbeSystemMessage()
                        else service.buildSystemMessage(
                                localMode = localLight,
                                availableTools = assembledToolNames,
                                projectRules = projectRules(),
                            ),
                        )
                        // 本轮**之前**的历史（不含本轮）：本轮用户消息在下面单独构造 ——
                        // 多模态时它是 text+image_url 分片数组，而事件流里只存文本（base64 不落盘）。
                        // 这句读的是"当前投影"，所以溢出恢复重跑它时拿到的是**压缩之后**的历史，
                        // 而不是冻结在轮次开始时的旧快照（否则压完再发一次还是原来那条超长请求）。
                        agentTurn?.historyForRequest()?.forEach { msg ->
                            put(JSONObject().apply {
                                put("role", msg.role)
                                put("content", msg.content)
                            })
                        }
                        // 轮变上下文（记忆/教训/技能/任务说明/RAG 资料/答题要求/会话要求）插在
                        // 历史之后、本轮输入之前：每轮变的只有请求尾部，system+整段历史保持
                        // 逐字节稳定的缓存前缀（P0 优化，2026-09-26，见 buildContextTailMessage KDoc）
                        service.buildContextTailMessage(
                            contextText = mergedContext,
                            instruction = instruction,
                            // 记忆/教训按轮降档（2026-10-12 P2）：闲聊快路与菜单探测轮要么不干活、
                            // 要么只做意图盘点，top-12 记忆（峰值 ~3.6k 字符）+ top-6 教训纯属陪跑
                            // —— 各降为 4/2 条。正常轮（含工具轮）不变。
                            // ⚠️ 只影响尾消息（历史之后），不碰缓存前缀纪律。
                            memories = if ((fastPathActive || menuProbeActive) && longTermOn) {
                                longTermMemory.memoriesContext(session.appContext, text, topK = 4)
                            } else {
                                longTermContext
                            },
                            lessons = if ((fastPathActive || menuProbeActive) && longTermOn) {
                                longTermMemory.lessonsContext(session.appContext, text, lessonTopK = 2)
                            } else {
                                lessonsContext
                            },
                            skills = skillsContext,
                            // 闲聊快路不下发预算说明：那段讲的是"工具调用轮次"，本轮压根没有工具，
                            // 提它等于诱导模型去找不存在的额度机制
                            budget = if (fastPathActive) {
                                null
                            } else {
                                // 路由轮多注入一条逃生舱说明：模型发现子集不够用 → 回 [NEED_TOOLS]
                                listOfNotNull(
                                    taskNote,
                                    budgetNote,
                                    routedToolNames?.let {
                                        com.rokidlab.phone.ai.ToolIntentRouter.escapeNote(it)
                                    },
                                ).joinToString("\n\n").ifBlank { null }
                            },
                            sessionPrompt = sessionPrompt,
                            availableTools = assembledToolNames,
                            // 菜单探测轮：83 个完整 Schema 换成轻量菜单 + 点名约定
                            toolMenu = if (menuProbeActive) {
                                // 伪工具补进菜单（R11）：条件见 pseudoToolEntries 注释
                                ToolRegistry.toolMenu(session.appContext, excludeGlassesTools = phoneOnly) +
                                    pseudoToolEntries.joinToString("\n") { (n, d) -> "- $n：$d" }
                            } else {
                                null
                            },
                        )?.let { put(it) }
                        put(userMsg)
                    }
                }
                // var（不是 val）：溢出恢复时整体替换，见下方 callModel 的 catch
                var messages = buildMessages()

                /**
                 * 撤掉 tail 里"不够用时回复 [NEED_TOOLS]"那条旧指令（2026-10-09，P0-2）。
                 *
                 * 只在**菜单模式的后续轮升级到全量**时调用 —— 那条路径不重建 messages
                 * （重建会劈散 in-flight 的 tool 配对 → 400），于是菜单段连同它的约定残留在上下文里，
                 * 模型会照着"已经失效的指令"再喊一次标记。
                 * 详见 [com.rokidlab.phone.ai.InternalMarkerGuard.neutralizeMenuInstruction]。
                 * 幂等：已撤过则不改（可安全重复调用）。
                 */
                fun neutralizeMenuTail() {
                    for (i in 0 until messages.length()) {
                        val m = messages.optJSONObject(i) ?: continue
                        if (m.optString("role") != "system") continue
                        val c = m.optString("content")
                        val fixed = com.rokidlab.phone.ai.InternalMarkerGuard
                            .neutralizeMenuInstruction(c, activeTools.size)
                        if (fixed != null && fixed != c) {
                            m.put("content", fixed)
                            Log.i(TAG, "menu instruction revoked at msg[$i] (tools=${activeTools.size})")
                        }
                    }
                }

                // 命中该调用的回合视为进入 AIUI/代码生成会话 → 下轮起切精简工具子集（仅切一次）
                val isCodeGenCall: (String, String) -> Boolean = { name, args ->
                    name == ToolRegistry.TOOL_CODE_FILE ||
                        (name == com.rokidlab.phone.ai.SkillRegistry.TOOL_NAME && args.contains("aiui-dev"))
                }

                // 工具参数截断自愈、未知工具名纠错、save_code_file 目标提取：纯函数，已抽到
                // com.rokidlab.phone.ai.AiToolParamRepair（2026-10-05 第一次 god-class 拆分第一步）。
                // 抽出动机不是"文件太长"，而是这四段原先是局部函数 ⇒ **单测碰不到**，
                // 「AI 空轮 400」「参数截断白耗一轮」只能靠真机肉眼验证。抽完后有单测锁定。

                // executeTool / runTool 已提为成员方法 executeToolInTurn / runToolInTurn
                //（2026-10-05 god-class 拆分第二步）：隐式捕获 6 个外层变量 + session 全转显式入参。
                val execCtx =                 TurnExecContext(
                    phoneOnly = phoneOnly,
                    readOnlyTools = readOnlyTools,
                    localLight = localLight,
                    pseudoToolNames = pseudoToolEntries.map { it.first }.toSet(),
                    longTermOn = longTermOn,
                    userText = text,
                    // 供 [executeToolInTurn] 上报「正在等你确认」/「你已取消」两条过程步骤
                    // （2026-10-06 审计修）。取本函数的 onTrace 参数 —— 它在 execCtx 之前
                    // 就是可用的函数参数，无需再往下传一层。
                    onTrace = onTrace,
                )

                var reply = ""
                // 每轮开头会 `streamedBuf.setLength(0)`（见工具循环首行）——
                // 跨轮累积会把多段不相干的话拼成一段假正文（工具轮 content 通常为空）。
                // 本轮是否出现过工具调用（含快路升级）：收尾时写入 lastTurnHadToolCalls
                // 观测位（2026-09-29 第六轮起它不再拦截三层装配，仅作日志观测）
                var turnUsedTools = false
                // 连续空轮计数：空轮 = 既无工具调用也无正文，说明模型在"只思考不出手"。
                // 实测每轮空转 60-75s，若放任空轮到预算用尽要数分钟，用户侧表现就是"一直等待回复"。
                var emptyRounds = 0
                // 上一轮是否被 max_tokens 截断（finish_reason=length）：截断会造成空轮或半截工具参数。
                // 空轮引导语必须点明"被截断了、请拆小再发"，否则模型以为自己没出手，会原样重试同样大的内容。
                var truncatedLastRound = false
                // AIUI 生成场景标志：命中 load_skill(aiui-dev)/save_code_file 后置位，并把 tools 切到
                // 精简 AIUI 子集（SESSION_AIUI_DOMAINS），仅切换一次
                var aiuiMode = false
                // AIUI/代码生成回合收敛标记：本轮是否执行过 save_code_file，是则最终回复只允许简短结论
                var codeGenUsed = false
                // 记录各项目成功生成的源文件（project -> 文件名集合），供收敛时生成权威结论
                val genFilesByProject = LinkedHashMap<String, MutableSet<String>>()
                // save_code_file 的 (project, file) 提取器 —— 供**生成线程**上两处回填复用
                //（主工具循环 / 收尾总结轮），避免同一份 arguments 反复 JSON 解析。
                // 实现已抽到 AiToolParamRepair.codeFileTarget（纯函数 + 有单测）。
                fun codeFileTargetOf(tc: com.rokidlab.phone.ai.ToolCallInfo): Pair<String, String>? =
                    com.rokidlab.phone.ai.AiToolParamRepair.codeFileTarget(tc.arguments)

                // ★ 过程面板的**文件产物**解析基准（2026-10-10）：文件类工具的
                //   `path`/`file` 参数都是**相对路径**，基准是该项目的私有镜像根
                //   （`AiuiProject.projectDir`，与 save_code_file 的落盘镜像同一棵树）。
                //   解析不出来时返回 null —— `AgentStep.artifactOf` 会只认绝对路径，
                //   **宁可少显示一个芯片，也不给一个打不开的路径**（用户会以为文件丢了）。
                fun artifactBaseDirOf(argsRaw: String): String? = runCatching {
                    val proj = org.json.JSONObject(argsRaw).optString("project").trim()
                    if (proj.isEmpty()) return@runCatching null
                    // 名字归一（应用名 → 项目文件夹名）：与文件工具走**同一套**解析，
                    // 否则用户用应用名指代项目时，芯片路径会指向一个不存在的目录（点开必失败）。
                    // 解析不出时退回原名 —— 至多让"点开预览"如实报文件不存在，不会指错项目。
                    val real = com.rokidlab.phone.ai.AiuiProject
                        .resolveProjectName(session.appContext, proj) ?: proj
                    com.rokidlab.phone.ai.AiuiProject.projectDir(session.appContext, real).absolutePath
                }.getOrNull()

                // save_code_file 的产物形态特殊：参数是 project + **file**（不是 path），
                // 且它写的是私有镜像 ⇒ 必须在**结束后**才知道文件是否真落盘。
                // 这类产物由 saveCodeArtifact 单独算，不走通用 artifactOf。
                fun saveCodeArtifact(
                    argsRaw: String,
                    succeeded: Boolean,
                ): List<com.rokidlab.phone.ai.AgentStep.Artifact> {
                    val target = com.rokidlab.phone.ai.AiToolParamRepair.codeFileTarget(argsRaw)
                        ?: return emptyList()
                    val (p, f) = target
                    // 与落盘同一套名字解析：解析出的**真实文件夹名**才是芯片该显示的归属
                    val real = com.rokidlab.phone.ai.AiuiProject
                        .resolveProjectName(session.appContext, p) ?: p
                    val abs = runCatching {
                        com.rokidlab.phone.ai.AiuiProject.projectDir(session.appContext, real)
                            .resolve(f).absolutePath
                    }.getOrNull() ?: return emptyList()
                    return listOf(
                        com.rokidlab.phone.ai.AgentStep.Artifact(
                            path = abs,
                            // 带项目名前缀（`aiui-test/app.json`）：用户核对"生成到了哪个项目"，
                            // 只给文件名时多项目并行会看不出归属。
                            label = "$real/$f",
                            action = if (succeeded) {
                                com.rokidlab.phone.ai.AgentStep.Action.CREATE
                            } else {
                                com.rokidlab.phone.ai.AgentStep.Action.WRITE
                            },
                        )
                    )
                }
                // 轮次预算分账（详见 MAX_READONLY_ROUNDS 注释）：只读轮（查资料）与动作轮（落盘/装机等）
                // 各自独立计数，避免 AIUI 任务的勘察把落盘额度吃光。混合轮按动作轮计费。
                var readOnlyRounds = 0
                var actionRounds = 0
                // 轮次预算是否已触顶（触顶后收尾引导要如实告知"预算用完"，而不是笼统收尾）
                var budgetExhausted = false
                // ★ token 预算（2026-10-06，与轮次预算**并行**，不是替代）：
                //   轮次管"能做多少步"，token 管"别烧钱/别撑爆窗口" —— 一轮的成本能差一个数量级，
                //   22 轮既可能几乎不花钱也可能烧掉整月配额。判据见 TokenBudget 类注释。
                //   按窗口推导（窗口未知时不限），服务端 usage 优先、TokenMeter 兜底。
                val tokenBudget = com.rokidlab.phone.ai.compaction.TokenBudget
                    .forWindow(route.capabilities.contextWindow)
                // 新一轮开始：清掉上一轮的实时 token 计数（过程卡在生成中才会重新显示）
                com.rokidlab.phone.store.ChatStateHolder.setLiveTokens(null)
                var tokenExhausted = false
                // 已自动续做次数（分账额度触顶后自动再开一段，见 MAX_AUTO_CONTINUES）
                var autoContinues = 0
                // 计划驱动续做次数（todo 驱动重入，见 MAX_PLAN_CONTINUES）：模型给出"收尾"回复
                // 但活跃计划仍有未完成步骤时，注入剩余步骤清单拉回来继续干
                var planContinues = 0
                // ★ 内部标记（[NEED_TOOLS]/[TOOLS]）的**本轮升级次数**（2026-10-09，P0-1）。
                // 为什么必须显式计数：升级分支原先只靠 `probeMode` 这个**瞬时布尔**判定
                // （升级后 fastPath/routed/menu 全被清空 ⇒ probeMode=false ⇒ 第二个标记无人接管 ⇒
                // 直接当正文播给用户）。布尔表达不了"已经升级过几次"，计数才可以。
                var markerUpgrades = 0
                // 本轮是否出现过"标记未产出"（供终态判定 TurnEndReason 用，见下方收尾）
                var markerLeakSeen = false
                // ★「诉求—动作」对齐检查（2026-10-09，P0-6）：本轮是否调用过**有副作用**的工具
                // （落盘/装机/拨号/建日程…）。用来发现"用户要改，agent 却只读了一圈"这种假成功
                // —— gps-live 案 T2~T7 共 37 次工具调用、**0 次写操作**，用户说的却是"修"和"重写"。
                var actionToolUsed = false
                // ★ 诉求漂移提醒只发一次（2026-10-12，补 intent_mismatch）：本轮已就
                // git clone 外部仓库提醒过模型 ⇒ 后续同类调用不再重复注入。
                var driftNoted = false
                // 该检查是否已触发过（只自问一次，防死循环）
                var intentAlignChecked = false
                // 下一轮是否强制工具调用（tool_choice=required）：计划续做轮置位，
                // 模型真的出手调工具后复位 —— 防止续做轮又出一轮"总结性空谈"
                var forceToolChoice = false
                // 上下文溢出是否已恢复过一次（compaction 接缝的 CONTEXT_OVERFLOW 入口）。
                // 整个请求**只允许一次**：第二次仍溢出就照旧上抛，绝不在这里打转 ——
                // 宁可能力退化成改造前的"提示用户换个短点的问题"，也不要变成"卡住不动"。
                var overflowRecovered = false
                // 工具循环：支持多步任务（先查时间再设定时等），同时防止模型反复请求工具导致死循环
                for (round in 0 until MAX_TOTAL_ROUNDS) {
                    // 每轮清空增量缓冲：本轮推给 UI 的正文就是"这一轮说到哪"，
                    // 跨轮累积会把多段不相干的话拼成一段假正文（工具轮 content 通常为空）
                    streamedBuf.setLength(0)
                    // 用户打断（有更新代际的请求进入）或链路已被替换：放弃后续生成，尽快让出 aiSendLock
                    if (isSuperseded()) {
                        Log.i(TAG, "AI generation superseded at round=$round (gen=$generation, latest=$aiGenSeq), abort")
                        // 事件流：本轮被抢占 —— 记一次"被抢占的尝试"，并落下半截正文
                        //（⚠️ 2026-10-09 行为变更：本轮**不再整轮作废**。整轮剪掉会连用户原话
                        //  一起剪掉，用户说「继续」时模型既看不到原话、也不知道发生过什么。
                        //  现在保留 user 消息 + 落半截 assistant，占位保证不出现连续两条 user。）
                        agentTurn?.attempt(
                            com.rokidlab.phone.ai.session.AttemptOutcome.SUPERSEDED,
                            "round=$round",
                        )
                        agentTurn?.finish(
                            com.rokidlab.phone.ai.session.TurnEndReason.INTERRUPTED,
                            partialReply = streamedBuf.toString(),
                        )
                        // 被打断的长任务标为可续做：下轮用户说「继续」即可接着做
                        if (!localLight) {
                            com.rokidlab.phone.ai.AgentTaskStore.markStatus(
                                session.appContext,
                                com.rokidlab.phone.ai.AgentTaskStore.STATUS_INTERRUPTED,
                                "被新消息打断（可续做）",
                                userText = text,
                            )
                        }
                        // ★ 进「过程」（2026-10-12，P1）：抢占此前全程静默——半截正文只落
                        //   事件流，气泡里没有任何"任务被打断、可续做"的痕迹，用户感知
                        //   就是"跑到一半悄悄没了"。FRAMEWORK 行：中断是框架的决定。
                        //   （锚点若已被结清，这条会落空 —— 无害，只是少一行提示。）
                        onTrace?.invoke(
                            AgentStep.framework(
                                "任务已中断，进度已保存；说「继续」可接着做",
                                id = "superseded_round_$round",
                            )
                        )
                        return@launchAiGeneration
                    }
                    // 流式：实时推送 content 增量给 UI（工具调用轮 content 通常为空，最终回复轮逐字推送）；
                    // isCancelled 使 SSE 行间隙可感知打断并立即停止读取
                    //
                    // Agent 过程（手机端「过程」时间线）：先发一条「正在思考」。
                    // 工具调用轮 content 为空，若不发这条，用户从点发送到最终回复之间只看到空白，
                    // 无法判断 Agent 是在思考、在等网络、还是已经卡死（这正是"看不到调用工具"的根因）。
                    onTrace?.invoke(AgentStep.thinking(round))
                    // 思考增量（仅开启长思考时有数据）：节流后再推 UI —— 推理可能上万个 delta，
                    // 每个都写一次 SnapshotStateList 会让主线程持续重组。
                    val reasoningBuf = StringBuilder()
                    var lastThinkEmitAt = 0L
                    // 工具参数流式进度的节流时间戳（见 TOOL_ARG_EMIT_INTERVAL_MS）
                    var lastToolArgEmitAt = 0L
                    // 本轮模型请求。抽成局部函数是为了在里面接**一次**上下文溢出恢复：
                    // 改造前溢出只会整轮失败，给用户一句「这次内容太长了，换个短一点的问题再试」——
                    // 而真正能修的动作（压掉历史再发一次）代码里根本没有。现在命中"输入超长"
                    // 就强压历史 + 整体重建消息序列 + 原地重试同一轮（递归一层，受
                    // overflowRecovered 约束；第二次仍溢出会原样抛出，行为退化成改造前）。
                    fun callModel(): com.rokidlab.phone.ai.ChatTurn = try {
                        service.chatTurnStream(
                            messages,
                            tools = activeTools,
                            // 闲聊快路/菜单探测轮不流式：模型可能按约定回复 [NEED_TOOLS]/[TOOLS] 标记，
                            // 流出去会闪现在聊天气泡里（探测回复都很短，损失可忽略）
                            onDelta = if (fastPathActive || menuProbeActive) {
                            null
                        } else {
                            // ★ 2026-10-09：旁路一份到 streamedBuf —— 打断时用它把半截正文
                            // 落进事件流（见 finishTurn 的 partialReply）。改造前这半截只到 UI，
                            // 模型侧完全失忆。**必须与推给 UI 的是同一份增量**，否则落盘内容
                            // 与用户看到的对不上，续做时模型会以为自己说过别的话。
                            if (onDelta == null) {
                                null
                            } else {
                                { delta ->
                                    streamedBuf.append(delta)
                                    onDelta.invoke(delta)
                                }
                            }
                        },
                            isCancelled = isSuperseded,
                            // 计划续做轮强制出手（P1 协议现代化 tool_choice）：防止模型又出一轮
                            // "总结性空谈"。探测/菜单轮绝不带（会破坏 [TOOLS] 标记协议）；
                            // 端点不认时 OpenAiService 自动撤字段重试并记住
                            toolChoice = if (forceToolChoice && !fastPathActive &&
                                !menuModeActive && !menuProbeActive
                            ) "required" else null,
                            // 本地 Ollama 不重试：首字慢是「加载/思考中」而非抖动，重试只会重复加载翻倍等待；
                            // 远程 3 次配合指数退避（500ms→1s→2s），重放安全边界=尚无 content 推给 UI
                            retryAttempts = if (localBase) 1 else 3,
                            // 代码生成模式（已 load_skill(aiui-dev) / 已开始落盘）才放大读超时：模型产出
                            // 超大工具参数 JSON 前可能长时间无 SSE 数据，30s 会在首包前超时、白白重放整轮。
                            // 非代码生成轮保持 30s，让「用户打断」的让出时间有界（见 chatTurnStream doc）。
                            readTimeout = if (aiuiMode) CODE_GEN_READ_TIMEOUT_MS else null,
                            onReasoning = if (onTrace == null) null else { delta ->
                                reasoningBuf.append(delta)
                                val now = System.currentTimeMillis()
                                if (now - lastThinkEmitAt >= THINKING_EMIT_INTERVAL_MS) {
                                    lastThinkEmitAt = now
                                    onTrace.invoke(AgentStep.thinking(round, reasoningBuf.toString()))
                                }
                            },
                            // ★ 工具参数流式进度（2026-10-12 对标 Codex）：代码生成的**主耗时**
                            //   在模型逐帧吐 save_code_file 的 content 上 —— 这期间既无 reasoning
                            //   也无 content，过程卡片此前只有一行静止的「正在思考」，用户不知道
                            //   它在写哪个文件、写到哪了（用户反馈"生成/修改文件全程只有思考中"）。
                            //   这里把累积参数长度刷成实时进度（「正在生成代码文件 · 3.2 KB」）。
                            //   ⚠️ 思考已产出内容时不覆盖：那行正在流式显示推理正文，用它换一个
                            //   数字是净损失；思考没开（reasoningBuf 恒空）时才由它接管这一行。
                            onToolArgs = if (onTrace == null) null else { toolName, argChars ->
                                if (reasoningBuf.isEmpty()) {
                                    val now = System.currentTimeMillis()
                                    if (now - lastToolArgEmitAt >= TOOL_ARG_EMIT_INTERVAL_MS) {
                                        lastToolArgEmitAt = now
                                        onTrace.invoke(
                                            AgentStep.thinking(round, toolArgProgressText(toolName, argChars)),
                                        )
                                    }
                                }
                            },
                        )
                    } catch (e: Exception) {
                        // 只认**输入侧**超长（ContextOverflow 特意与"输出被 max_tokens 截断"分开：
                        // 后者压缩历史毫无用处，重试只是白烧一次请求）
                        if (overflowRecovered ||
                            !com.rokidlab.phone.ai.compaction.ContextOverflow.isOverflow(e)
                        ) {
                            throw e
                        }
                        overflowRecovered = true
                        Log.w(
                            TAG,
                            "context overflow rejected by server " +
                                "(marker=${com.rokidlab.phone.ai.compaction.ContextOverflow.matchedMarker(e)}, " +
                                "round=$round): ${e.message} —— 压缩历史后重试同一轮",
                        )
                        // 事件流：这次失败尝试也要留痕 —— 改造前它只表现为"这轮特别慢"，
                        // 日志里连"被服务端以超长拒过"都查不到（不进模型历史，见 AssistantAttempt）
                        agentTurn?.attempt(
                            com.rokidlab.phone.ai.session.AttemptOutcome.OVERFLOW,
                            com.rokidlab.phone.ai.compaction.ContextOverflow.matchedMarker(e),
                        )
                        val turnForRecovery = agentTurn
                        val compacted = if (turnForRecovery != null) {
                            turnForRecovery.compactNow(com.rokidlab.phone.ai.compaction.CompactionTrigger.CONTEXT_OVERFLOW)
                        } else {
                            agentSession.compactNow(com.rokidlab.phone.ai.compaction.CompactionTrigger.CONTEXT_OVERFLOW)
                        }
                        messages = buildMessages()
                        // ★ 上报"压缩了历史"（2026-10-06 审计修）：原先只有下面那行 Log，
                        //   用户只觉得"这轮怎么这么慢"。而压缩**改变了后续回答的基础**，
                        //   用户必须知道自己之前提过的那些历史被压掉了 —— 否则追问时会基于
                        //   一个自己没意识到的残缺上下文继续对话。
                        onTrace?.invoke(
                            AgentStep.compressed(
                                if (compacted != null) {
                                    "对话历史已过长，已自动压缩早期内容以继续回答（${messages.length()} 条消息在册）"
                                } else {
                                    "对话历史已过长，本次重试未能压缩（${messages.length()} 条消息在册）"
                                },
                            ),
                        )
                        Log.i(
                            TAG,
                            "overflow recovery: compact=${compacted ?: "nothing to compact"}, " +
                                "messages rebuilt (${messages.length()} entries)",
                        )
                        callModel()
                    }
                    val turn = callModel()
                    // 成本记账：每次成功的模型调用都把真实用量累进本轮（多轮工具循环 = 多次请求，
                    // 输入 token 每轮都要重发，那才是成本大头）。服务端没给 usage 时记 null，
                    // 只累加调用次数 —— 绝不用字符数编一个 token 数冒充真实值。
                    agentTurn?.recordModelCall(turn.usage)
                    // 本轮思考收束：有推理内容就把预览落到过程卡片上（同一个 key 覆盖）；
                    // fullText 带推理全文（仅结束态这一次），手机端过程卡片可点击展开
                    onTrace?.invoke(
                        AgentStep.thinking(
                            round,
                            reasoningBuf.toString(),
                            AgentStep.State.OK,
                            fullText = reasoningBuf.toString(),
                        ),
                    )
                    truncatedLastRound = turn.finishReason == "length"
                    // ★ 菜单模式标记收束（探测轮 + 后续轮通用）：模型要么直接作答（无标记 →
                    // 落到下方最终回复路径）；要么点名 [TOOLS] → 按名装配完整 Schema；
                    // 要么 [NEED_TOOLS]/空响应（仅探测轮）/"[" 开头的变形标记 → 全量兜底。
                    // ⚠️ 探测轮（menuProbeActive）尚无 in-flight assistant/tool 消息，重建
                    // 消息序列是协议安全的（顺带摘掉菜单文本、换上正式人设）；后续轮只换
                    // tools 不重建 —— in-flight 的 tool 配对在 messages 里，重建会劈散 → 400。
                    // 菜单贯穿本轮（在轮 0 的尾消息里），模型随时可追加点名；
                    // 用量已在上面 recordModelCall 落账，多花的只是 ~3k token 的探测成本。
                    if (menuModeActive && turn.toolCalls.isEmpty()) {
                        val probeRound = menuProbeActive
                        val menuContent = turn.content
                        val menuTrimmed = menuContent?.trim().orEmpty()
                        // 点名集合含伪工具（R11）：否则模型回「[TOOLS] update_plan」时
                        // update_plan 不在 knownNames 里 → picks 空集 → 走全量兜底 ——
                        // 功能上碰巧能跑，但点名单工具的省 token 语义被静默破坏。
                        val knownNames = ToolRegistry.toolList.map { it.name }.toSet() +
                            execCtx.pseudoToolNames
                        val picks = com.rokidlab.phone.ai.ToolIntentRouter.parseToolPick(menuContent, knownNames)
                        val wantsFull = com.rokidlab.phone.ai.ChatFastPath.needsUpgrade(menuContent) ||
                            (com.rokidlab.phone.ai.ToolIntentRouter.isMarkerLike(menuContent) &&
                                picks.isNullOrEmpty()) ||
                            (probeRound && menuTrimmed.isEmpty())
                        if (wantsFull) {
                            menuModeActive = false
                            menuProbeActive = false
                            turnUsedTools = true
                            routedToolNames = null
                            // 与 probeMode 分支共用同一个升级计数（P0-1）：菜单模式的"要全量"
                            // 也是标记驱动的升级，必须一起计数，否则"菜单→全量"之后的第二个
                            // 标记会被算作第一次、白多升一轮。
                            markerUpgrades++
                            activeTools = buildTools(ToolRegistry.SESSION_AGENT_DOMAINS)
                            assembledToolNames = ToolRegistry.namesOf(activeTools)
                            // 探测轮：整体重建（摘菜单文本）；后续轮只换表（in-flight 消息不可重建）
                            if (probeRound) {
                                messages = buildMessages()
                            } else {
                                // ★ P0-2 的真实落点：**后续轮升级不重建** ⇒ `<tool_menu>` 那段
                                // "不够用时回复 [NEED_TOOLS]" 的约定**留在 messages 里到本轮结束**。
                                // 于是模型既拿到全量工具、又仍读到那条旧指令，还会再喊一次标记
                                // —— 这就是 gps-live 案每轮多烧 2 次模型调用的来源。
                                // 不重建是因为 in-flight 的 assistant(tool_calls)/tool 配对在 messages 里，
                                // 劈散会被服务端 400；所以改用**追加显式覆盖**（幂等、位置更靠后＝优先级更高）。
                                neutralizeMenuTail()
                            }
                            Log.i(
                                TAG,
                                "tool menu -> full toolset (${activeTools.size} schemas, " +
                                    "content=${menuTrimmed.take(40)}, finish=${turn.finishReason}), " +
                                    "upgrade#$markerUpgrades/$MAX_MARKER_UPGRADES" +
                                    if (probeRound) " (rebuilt)" else " (menu instruction revoked)",
                            )
                            continue
                        }
                        if (picks != null && picks.isNotEmpty()) {
                            turnUsedTools = true
                            // 点名 ∪ 引导集：搜索/时间常是后续轮的辅助，保留在场
                            val wanted = picks + com.rokidlab.phone.ai.ToolIntentRouter.BOOTSTRAP_TOOLS
                            routedToolNames = wanted
                            val routed = wanted
                            activeTools = buildTools(ToolRegistry.SESSION_AGENT_DOMAINS)
                                .filter { ToolRegistry.nameOf(it) in routed }
                                .toMutableList()
                            if (activeTools.isEmpty()) {
                                // 点的名全被设置页禁用 → 全量兜底（装配侧尊重用户开关，
                                // 但不能因此一个工具都没有）
                                routedToolNames = null
                                activeTools = buildTools(ToolRegistry.SESSION_AGENT_DOMAINS)
                            }
                            assembledToolNames = ToolRegistry.namesOf(activeTools)
                            if (probeRound) {
                                // 探测轮：整体重建（摘掉菜单文本，人设/闸门按新工具集重算）
                                menuProbeActive = false
                                messages = buildMessages()
                            }
                            Log.i(
                                TAG,
                                "tool menu -> ${activeTools.size} schemas picked ($routedToolNames)",
                            )
                            continue
                        }
                        // 其余情况 = 模型直接作答（无任何标记）：落到下方最终回复收尾路径
                    }
                    menuProbeActive = false
                    // ★ 快路/路由升级判定（探测轮只生效一次）：模型声明需要工具（[NEED_TOOLS]）、
                    // 或探测轮干脆没出声（无法确认能作答）、或回复以 "[" 开头（约定标记的变形
                    // —— 子集轮正常作答不会这样开头），都按"需要工具"处理 —— 全量重装、
                    // 整体重建消息序列原地重来。此时本轮尚无 in-flight 的 assistant/tool 消息，
                    // 重建是协议安全的。探测/子集轮的用量已在上面 recordModelCall 落账，
                    // 升级后多花的只是千级 token 的探测成本；换来的保障是**绝不因省 token 丢能力**。
                    // 菜单模式不走此分支：标记/空响应/点名已由上方菜单分支收束
                    val probeMode = !menuModeActive && (fastPathActive || routedToolNames != null)
                    if (probeMode && turn.toolCalls.isEmpty() &&
                        (com.rokidlab.phone.ai.ChatFastPath.needsUpgrade(turn.content) ||
                            turn.content.isNullOrBlank() ||
                            com.rokidlab.phone.ai.ToolIntentRouter.isMarkerLike(turn.content))
                    ) {
                        fastPathActive = false
                        routedToolNames = null
                        turnUsedTools = true
                        markerUpgrades++
                        activeTools = buildTools(ToolRegistry.SESSION_AGENT_DOMAINS)
                        assembledToolNames = ToolRegistry.namesOf(activeTools)
                        messages = buildMessages()
                        Log.i(
                            TAG,
                            "chat probe upgraded: model requested tools " +
                                "(content=${turn.content?.take(40)}, finish=${turn.finishReason}), " +
                                "full toolset attached (${activeTools.size} schemas), " +
                                "upgrade#$markerUpgrades/$MAX_MARKER_UPGRADES",
                        )
                        continue
                    }
                    // ★ 终态护栏（2026-10-09，P0-1）：**升级额度已用完，它还在回内部标记**。
                    // 上面的 probeMode 分支靠 `fastPathActive || routedToolNames != null` 判定，
                    // 升级后这两个都被清空 ⇒ 第二个标记原本无人接管、直接被当正文播给用户
                    // （gps-live 事故：气泡里就是一行 `[NEED_TOOLS]`，还记成 completed）。
                    // 这里补上那个缺失的分支：额度没用完就再升一次（消化"重复喊"），
                    // 用完则注入硬引导要求给出可读结论 —— 绝不把标记留给用户。
                    // ⚠️ 与 probeMode 分支的**判据不同**：那里还管"空响应/变形标记"，
                    // 这里只认 `isMarkerOnly`（严格整段匹配）—— 因为此处已无法重建 messages，
                    // 误判的代价是白注一轮引导，所以要保守。
                    if (turn.toolCalls.isEmpty() &&
                        com.rokidlab.phone.ai.InternalMarkerGuard.isMarkerOnly(turn.content)
                    ) {
                        markerLeakSeen = true
                        if (markerUpgrades < MAX_MARKER_UPGRADES) {
                            // 还有额度：再升一次（此时 activeTools 可能已是全量，重建无害）
                            fastPathActive = false
                            routedToolNames = null
                            turnUsedTools = true
                            markerUpgrades++
                            activeTools = buildTools(ToolRegistry.SESSION_AGENT_DOMAINS)
                            assembledToolNames = ToolRegistry.namesOf(activeTools)
                            messages = buildMessages()
                            Log.w(
                                TAG,
                                "marker re-upgrade #$markerUpgrades/$MAX_MARKER_UPGRADES " +
                                    "(content=${turn.content?.take(40)}, finish=${turn.finishReason})",
                            )
                            continue
                        }
                        // 额度用完：**不许再回标记**，注入硬引导让它给可读结论
                        // （不重建 messages —— 直接覆盖尾部，避免劈散 in-flight 配对）
                        val tailMsg = messages.optJSONObject(messages.length() - 1)
                        val nudgeText = "你刚才只回复了内部标记，用户什么也没看到。" +
                            "现在**不要再输出任何方括号标记**：请直接用一两句中文告诉用户结论 —— " +
                            "如果你已经完成了任务就说完成了什么；" +
                            "如果缺少某个能力而做不了，就如实说明缺什么、用户能怎么办。"
                        if (tailMsg != null && tailMsg.optString("role") == "user") {
                            tailMsg.put("content", nudgeText)
                        } else {
                            messages.put(JSONObject().apply {
                                put("role", "user")
                                put("content", nudgeText)
                            })
                        }
                        Log.w(
                            TAG,
                            "marker leaked with no upgrade left ($markerUpgrades/$MAX_MARKER_UPGRADES): " +
                                "injecting nudge to force a readable conclusion",
                        )
                        // P0-7：反思落盘 —— 这是"它哪一轮意识到自己错了"的唯一证据来源
                        agentTurn?.reflect(
                            com.rokidlab.phone.ai.session.ReflectTrigger.MARKER_LEAK,
                            detail = "模型回了内部标记 ${turn.content?.take(40)}，升级额度已用尽（$markerUpgrades/$MAX_MARKER_UPGRADES）",
                            action = "注入硬引导：禁止再输出方括号标记，要求给出可读结论或说明缺什么能力",
                        )
                        // ★ 进「过程」（2026-10-10）：这轮会被静默重试，用户看到的是
                        //   "回答突然又从头开始"。不说明就会以为 App 卡了或重复发送。
                        onTrace?.invoke(
                            AgentStep.reflect(
                                "marker_leak",
                                "模型输出的是内部标记而非回答，正在重试（$markerUpgrades/$MAX_MARKER_UPGRADES）",
                            )
                        )
                        continue
                    }
                    fastPathActive = false
                    // ⚠️ 路由子集**不再自动扩全量**（2026-09-29 第七轮，实测教训：一条天气消息
                    // 42.4k token，其中 ~30k 是扩全量后第 2/3 轮的全价 83 Schema，且扩全量打断
                    // 缓存前缀让第 2 轮全 miss）。子集贯穿本轮：多步意图由路由并集覆盖；模型中途
                    // 发现需要清单外工具时，按 escapeNote 约定回 [NEED_TOOLS] → 上方升级分支
                    // 挂全量重答（重建消息序列会丢本轮 in-flight 工具结果，模型会重新查 ——
                    // 这是罕见路径，为它常驻全价不划算）。escapeNote 每轮都在（tail 稳定可缓存）。
                    if (turn.toolCalls.isEmpty()) {
                        // 有正文：先看计划驱动续做（todo 驱动重入，2026-10-02）——
                        // 模型自己认为是收尾，但活跃计划仍有未完成步骤时，注入剩余步骤清单
                        // + 强制 tool_choice=required 拉回来继续干（受 MAX_PLAN_CONTINUES 约束；
                        // 无人值守 readOnlyTools / 本地轻量不续做，与预算续做同一边界）。
                        if (!turn.content.isNullOrBlank()) {
                            val planPrompt = if (planContinues < MAX_PLAN_CONTINUES &&
                                !readOnlyTools && !localLight
                            ) {
                                com.rokidlab.phone.ai.AgentTaskStore.planContinuationPrompt(
                                    session.appContext,
                                )
                            } else {
                                null
                            }
                            if (planPrompt != null) {
                                planContinues++
                                forceToolChoice = true
                                // 回填 assistant 正文（下一轮请求的历史连贯性：assistant 内容
                                // 之后接 user 续做指令是协议合法的；reasoning 按工具轮同规则带回）
                                messages.put(JSONObject().apply {
                                    put("role", "assistant")
                                    if (turn.reasoning != null) put("reasoning_content", turn.reasoning)
                                    put("content", turn.content)
                                })
                                messages.put(JSONObject().apply {
                                    put("role", "user")
                                    put("content", planPrompt)
                                })
                                link?.let { sendGlassesProgress(it, "→ 继续执行…") }
                                // ★ 同时上报手机过程（2026-10-06 审计修）：原先只发眼镜，
                                //   手机过程里**没有任何痕迹** ⇒ 用户看到的是
                                //   「多出几个 think 但工具行没增加」，以为它卡住重复思考；
                                //   而眼镜端用户反而能看到这个说明。同一件事两端可见性不一致。
                                // 用 THINKING 而不是新增 Kind：它就是"框架自己决定再想一轮"，
                                // 且 round 与后续 THINKING 步骤共用覆盖键 ⇒ 下一轮的 think 覆盖它。
                                onTrace?.invoke(
                                    AgentStep.thinking(
                                        round = round,
                                        detail = "计划未完成，继续执行…",
                                        state = AgentStep.State.RUNNING,
                                    ),
                                )
                                Log.i(
                                    TAG,
                                    "plan continuation #$planContinues/$MAX_PLAN_CONTINUES at round=$round: " +
                                        "model tried to wrap up with pending plan steps, re-entering loop",
                                )
                                continue
                            }
                            // ★「诉求—动作」对齐检查（2026-10-09，P0-6）：模型准备收尾了，
                            // 但**用户要的是"改"，本轮却一个动作类工具都没调** —— 这是真机上
                            // 最典型的"假成功"：它读了一圈、说了几句话，任务一步没推进。
                            // （gps-live：用户说"修"和"重写"，T2~T7 共 37 次工具调用、0 次写操作。）
                            // 只自问一次，且**必须有明确的动作意图**才触发 —— 否则用户只是问个问题
                            // 也会被无端打断（防误伤，见报告 §8 的判据）。
                            val wantsAction = com.rokidlab.phone.ai.ActionIntent.looksLikeActionRequest(text)
                            if (!intentAlignChecked && wantsAction && turnUsedTools && !actionToolUsed &&
                                !readOnlyTools && !localLight
                            ) {
                                intentAlignChecked = true
                                val alignPrompt = "你刚才只做了查询、**没有产生任何改动**，但用户要的是" +
                                    "「${text.take(60)}」这类需要动手的请求。" +
                                    "请现在动手：如果确实需要改，就直接调用写类工具（写文件/改文件/装应用等）；" +
                                    "如果已经不需要改（比如问题不在那儿），就用一两句中文说清你的判断依据；" +
                                    "如果你缺少完成它所需的能力，就如实说明缺什么、用户能怎么办。"
                                messages.put(JSONObject().apply {
                                    put("role", "assistant")
                                    if (turn.reasoning != null) put("reasoning_content", turn.reasoning)
                                    put("content", turn.content)
                                })
                                messages.put(JSONObject().apply {
                                    put("role", "user")
                                    put("content", alignPrompt)
                                })
                                onTrace?.invoke(
                                    AgentStep.thinking(
                                        round = round,
                                        detail = "用户要的是修改，但还没动手…",
                                        state = AgentStep.State.RUNNING,
                                    ),
                                )
                                Log.w(
                                    TAG,
                                    "intent-action mismatch at round=$round: user asked to change " +
                                        "(\"${text.take(40)}\") but no side-effect tool ran in $turnUsedTools; " +
                                        "re-entering loop once",
                                )
                                // P0-7：反思落盘（第三种"假成功"：诉求与动作不一致）
                                agentTurn?.reflect(
                                    com.rokidlab.phone.ai.session.ReflectTrigger.INTENT_ACTION_MISMATCH,
                                    detail = "用户诉求「${text.take(60)}」属动手类，但本轮只有查询、零动作类调用",
                                    action = "注入对齐提示：要求动手改 / 说明为何不需改 / 说清缺什么能力",
                                )
                                continue
                            }
                            reply = turn.content
                            break
                        }
                        // 空轮（既无工具也无文本）：模型在反复"思考但不落子"，实测每轮空转 60-75s，
                        // 连等数轮就是好几分钟。故首轮空即注入硬引导（原实现要求 round>=1，
                        // 等于白耗一整轮 60-75s）；若紧接一轮仍是空轮，判定本轮无法产出、立即收尾 ——
                        // 跳出后下方 fallback 总结轮会带着 tools 再要一次，回复不会丢。
                        // 若进入 AIUI 代码生成模式，引导语给出具体的分文件落盘指令。
                        emptyRounds++
                        if (emptyRounds >= 2) {
                            reply = ""
                            Log.w(TAG, "chatTurnStream empty turn round=$round, give up after $emptyRounds consecutive empty rounds")
                            break
                        }
                        val (nudgeKind, nudge) = when {
                            // 被截断导致的空轮（content 被切掉、工具调用没收尾）：点明真实原因，
                            // 否则模型会以为是"自己没出手"，原样重发同样大的内容再被截断一次。
                            truncatedLastRound ->
                                "truncated" to
                                    "你上一次的输出因过长被截断了。请立刻把内容拆小：一次只调用 save_code_file 写一个文件，" +
                                    "单个文件控制在 300 行内，app.json 与页面代码分开写，绝不要在一次调用里塞多个文件。"
                            aiuiMode ->
                                "aiui" to
                                    "请立即行动，不要再空想：你已加载 aiui-dev 技能。按顺序调用 save_code_file，" +
                                    "先保存 app.json（含 pages 与 window 配置），再逐文件保存页面代码（pages/index/index），" +
                                    "一次只写一个文件、不要一次输出超大 JSON。全部写完后再用一两句中文总结。"
                            else ->
                                "generic" to
                                    "请不要再停留在思考：如果任务需要写代码，立即调用 save_code_file 一次写一个文件；" +
                                    "如果已写完或无法完成，直接用一两句中文给出最终结论。"
                        }
                        messages.put(JSONObject().apply {
                            put("role", "user")
                            put("content", nudge)
                        })
                        // 记下注入了哪种引导与真实的 finish 原因：空轮排查的第一现场
                        // （finish=length 说明被 max_tokens 截断，需要查该轮的输出预算而非引导语）
                        Log.i(
                            TAG,
                            "chatTurnStream empty turn round=$round, injected nudge (kind=$nudgeKind, " +
                                "finish=${turn.finishReason ?: "none"}, aiuiMode=$aiuiMode)",
                        )
                        continue
                    }
                    // 本轮有工具调用 = 模型正常出手，连续空轮计数归零；tool_choice 强制位也复位
                    // （它只管"续做后的第一轮"，模型出手后恢复 auto，避免全程锁死在 required）
                    emptyRounds = 0
                    forceToolChoice = false
                    turnUsedTools = true
                    // 回填 assistant 消息（OpenAI 协议要求原样带上 tool_calls）
                    val assistantMsg = JSONObject()
                    assistantMsg.put("role", "assistant")
                    assistantMsg.put("content", JSONObject.NULL)
                    // 思考开启时（thinkingEnabled=true）DeepSeek V4 要求把 reasoning_content
                    // 原样回传历史，否则多轮工具循环直接 400；关闭思考时服务端无此字段，恒为 null
                    if (turn.reasoning != null) assistantMsg.put("reasoning_content", turn.reasoning)
                    val calls = JSONArray()
                    turn.toolCalls.forEach { tc ->
                        calls.put(JSONObject().apply {
                            put("id", tc.id)
                            put("type", "function")
                            put("function", JSONObject().apply {
                                put("name", tc.name)
                                put("arguments", tc.arguments)
                            })
                        })
                    }
                    assistantMsg.put("tool_calls", calls)
                    messages.put(assistantMsg)

                    // 两级调度（2026-10-02）：一轮 toolCalls 按「是否有副作用」分两条泳道 ——
                    // ★ 为什么不再全部并发：此前的并发化把副作用工具（发短信/拨号/装应用/
                    //   save_code_file/manage_memory…）也并发了 —— 两个写盘工具同时落同一项目、
                    //   两个确认弹窗同时抢审批通道、眼镜端进度行互相覆盖，都是真实竞态。
                    //   读类工具（查询/读取/look_at_view）才配得上并发的收益。
                    //  - 并行泳道：无副作用工具，aiScope.launch 并发，ADB 类工具在 ToolRegistry
                    //    内仍经 adbLock 串行（蓝牙单连接安全）；
                    //  - 串行泳道：副作用工具按模型给出的原顺序在生成线程上内联逐个执行
                    //    （模型顺序 = 计划顺序），跑完与并行批在 latch 汇合。
                    // 结果始终按原顺序回填（results[idx]），messages 顺序与模型预期一致。
                    val results = arrayOfNulls<String>(turn.toolCalls.size)
                    // 视觉工具（look_at_view）拍到的画面（base64）。必须**在工具自己的线程上**取走 ——
                    // provider 用 ThreadLocal 记录"这条调用的产出"，跨线程取会拿到 null。
                    // （两条泳道各自成立：并行泳道在协程线程取，串行泳道在生成线程取。）
                    val lookImages = arrayOfNulls<String>(turn.toolCalls.size)

                    // 副作用判据与 runTool 内「失败不重试」的 A3 修复同源（真实工具表 + 伪工具表）：
                    // 两处表达的是同一个事实 ——「这个动作不可逆」—— 不允许分叉
                    val sideEffecting =
                        ToolRegistry.SIDE_EFFECT_TOOLS +
                            com.rokidlab.phone.ai.approval.PseudoTools.sideEffectNames()
                    val parallelIdx = ArrayList<Int>(turn.toolCalls.size)
                    val serialIdx = ArrayList<Int>()
                    turn.toolCalls.forEachIndexed { idx, tc ->
                        if (tc.name in sideEffecting) serialIdx.add(idx) else parallelIdx.add(idx)
                    }
                    // ★ P0-6：本轮出现过动作类调用 ⇒ 记下来（供收尾时做「诉求—动作」对齐检查）。
                    // 用装配侧同一个 `sideEffecting` 集合，判据只有一份，不会与串行泳道漂移。
                    if (serialIdx.isNotEmpty()) actionToolUsed = true
                    // 并行批为空（一轮全是副作用工具）时 CountDownLatch(0) 合法：await 立即返回
                    val latch = java.util.concurrent.CountDownLatch(parallelIdx.size)

                    // 单个工具的完整执行体（进度推送 → trace → runTool → 取图 → 完成行），
                    // 两条泳道共用同一份逻辑 —— 分成两份迟早漂移
                    fun runToolWorker(idx: Int) {
                        val tc = turn.toolCalls[idx]
                        try {
                            // 静默工具（眼镜端不显示进度行）：长期记忆维护（纯内部记账）+
                            // update_plan（无专属 statusText，避免显示"正在执行 update_plan…"这种黑话，
                            // 完成后自行推送格式化计划文本）。
                            //
                            // ★ 2026-10-06 审计修：**移除了 load_skill / load_skill_section**。
                            // 它们原来是"眼镜静默、手机可见"，但静默的代价是：
                            // 用户在眼镜上问"你能做什么"，AI 加载技能文档（读文件，可能数秒）
                            // 时眼镜端**一行都不显示**，只有 TTS 静默等待 ⇒ 用户以为眼镜没听见。
                            // 讽刺点：`ToolRegistry.statusText` 为它们备好了"正在加载技能…"，
                            // 而 `if (!silent)` 恰好把它跳过 ⇒ 这份文案永远用不上。
                            // 这两个工具是 READ_ONLY 档（不弹审批、不写盘），属于用户可感知的能力。
                            val silent = tc.name == com.rokidlab.phone.ai.LongTermMemoryManager.TOOL_NAME ||
                                tc.name == com.rokidlab.phone.ai.AgentPlan.TOOL_NAME ||
                                // ask_user：眼镜进度走通道里的 TTS 提醒（"请看手机"），
                                // 不发「→ 正在执行 ask_user…」这种黑话行
                                tc.name == com.rokidlab.phone.ai.AskUser.TOOL_NAME
                            // 代码落盘工具：进度提示要带具体文件名（「正在生成 app.json…」→「app.json 生成成功」）
                            val isCodeFile = tc.name == ToolRegistry.TOOL_CODE_FILE
                            val relFile = if (isCodeFile) {
                                runCatching { JSONObject(tc.arguments).optString("file").trim() }
                                    .getOrDefault("")
                            } else ""
                            if (!silent) {
                                // 本机模式（link == null）没有眼镜可推，进度只进手机端过程时间线
                                link?.let {
                                    // 「→」＝进行中图标（GB2312 字形，单色屏不缺字）
                                    sendGlassesProgress(
                                        it,
                                        // ★ 精简（2026-10-12 用户要求）：眼镜单色屏只留「操作 + 文件名」，
                                        //   进行中/成功/失败三个状态靠 →/√/× 区分，不再铺一句完整说明
                                        if (isCodeFile && relFile.isNotEmpty()) "→ 生成 $relFile"
                                        else "→ " + ToolRegistry.statusText(tc.name),
                                    )
                                }
                            }
                            // Agent 过程：把这一步工具调用呈现到手机端「过程」时间线。
                            // 只屏蔽长期记忆维护（纯内部记账，用户既看不懂也不需要看）；
                            // load_skill 等虽然对眼镜静默，但用户明确想知道
                            // "Agent 到底做了什么"，因此照常显示。
                            // update_plan 不显示为工具行（"执行 update_plan…"是黑话），
                            // 改由下方的结构化计划清单（AgentStep.Kind.PLAN）呈现。
                            val traceVisible = tc.name != LongTermMemoryManager.TOOL_NAME &&
                                tc.name != com.rokidlab.phone.ai.AgentPlan.TOOL_NAME &&
                                // ask_user 不走通用工具行：分支内自发自终两条框架步骤
                                // （「等待用户选择：…」→「用户已选择/跳过/超时」，同 key 覆盖）
                                tc.name != com.rokidlab.phone.ai.AskUser.TOOL_NAME
                            if (traceVisible) {
                                // ★ 文件产物（2026-10-10）：让「过程」行里的文件名可点开预览。
                                //   save_code_file 特殊 —— 参数是 project+file（不是 path），
                                //   等结果才知道是真新建还是失败，RUNNING 阶段一律按 WRITE 呈现
                                //   （终态那次会以同 key 覆盖掉这条）。
                                val isCodeFileStep = tc.name == ToolRegistry.TOOL_CODE_FILE
                                val runningArtifacts = if (isCodeFileStep) {
                                    saveCodeArtifact(tc.arguments, succeeded = false)
                                } else {
                                    AgentStep.artifactOf(tc.name, tc.arguments, artifactBaseDirOf(tc.arguments))
                                }
                                onTrace?.invoke(
                                    AgentStep.tool(
                                        tc.id,
                                        tc.name,
                                        AgentStep.State.RUNNING,
                                        argsRaw = tc.arguments,
                                        // 产物走参数传入（而不是事后 copy）：tool() 据此决定 detail
                                        // 要不要收原始参数 —— 有芯片就不再漏参数正文。
                                        artifacts = runningArtifacts,
                                    ),
                                )
                            }
                            // ★ write 型子代理内层进度上报（2026-10-12）：execute_subtask 内层
                            //   6~8 轮写文件/打补丁此前一行过程都不发，用户只见外层一行转圈
                            //   （最长 120 秒）——"改代码/生成代码不知道它在干嘛"的主路径。
                            //   与只读子代理的 setSubStepSink 同一套 ThreadLocal 手法；
                            //   sink 在这里（执行工具的线程、artifactBaseDirOf 可见的作用域）写入，
                            //   由 SubagentToolProvider 在 execute 时取走即清。
                            //   ⚠️ 写型子代理 sideEffect=true 恒走串行泳道（本线程直接执行），
                            //   set/take 必在同线程；若未来改走并行泳道，sink 会取不到
                            //   ⇒ 退化为"只有外层一行"（与改造前一致，不会错显）。
                            if (tc.name == com.rokidlab.phone.ai.subagent.WriteSubagent.TOOL_NAME) {
                                com.rokidlab.phone.ai.subagent.WriteSubagent.setSubStepSink { round, toolName, stateName, argsJson, result ->
                                    val subState = runCatching { AgentStep.State.valueOf(stateName) }
                                        .getOrDefault(AgentStep.State.RUNNING)
                                    onTrace?.invoke(
                                        AgentStep.subStep(
                                            key = "sub:${tc.id}:$round:$toolName",
                                            name = toolName,
                                            state = subState,
                                            result = result,
                                            // 产物芯片：内层文件工具的参数同样带 project/path，
                                            // 与主循环工具行走同一套解析（解析不出 → 无芯片，不猜路径）
                                            artifacts = AgentStep.artifactOf(
                                                toolName, argsJson, artifactBaseDirOf(argsJson),
                                            ),
                                        ),
                                    )
                                }
                            }
                            // ★ 输出直播 + 耗时（2026-10-12 对标 Codex）：shell/script 是 sideEffect 工具
                            //   ⇒ 恒走串行泳道（本线程直接执行），执行前装 ThreadLocal sink，
                            //   逐行输出在此**节流**（≥500ms）刷回 RUNNING 命令卡 —— 用户能看着
                            //   输出滚出来，而不是盯着一圈转圈猜。finally 清 sink 兜底（同线程）。
                            val execT0 = android.os.SystemClock.elapsedRealtime()
                            val liveTool = tc.name == com.rokidlab.phone.ai.tools.ShellToolProvider.TOOL_RUN ||
                                tc.name == "run_script"
                            if (liveTool) {
                                val liveBuf = StringBuilder()
                                var lastPush = 0L
                                com.rokidlab.phone.ai.tools.ShellToolProvider.setLiveOutputSink { line ->
                                    if (liveBuf.length + line.length + 1 <= AgentStep.MAX_OUTPUT_CHARS) {
                                        liveBuf.append(line).append('\n')
                                    }
                                    val now = android.os.SystemClock.elapsedRealtime()
                                    if (now - lastPush >= 500L) {
                                        lastPush = now
                                        val tail = liveBuf.toString().lines()
                                            .takeLast(15).joinToString("\n").takeLast(2_000)
                                        onTrace?.invoke(
                                            AgentStep.tool(
                                                tc.id, tc.name, AgentStep.State.RUNNING,
                                                argsRaw = tc.arguments,
                                            ).copy(output = tail.takeIf { it.isNotBlank() }),
                                        )
                                    }
                                }
                            }
                            // ★ 产物回报通道开工（2026-10-13 审计修）：工具在写成功那一刻
                            //   `ProducedFileSink.record(真实绝对路径)` —— zip / 总结 txt 这类
                            //   "路径不在参数里"的产物只可能这样拿到（ARTIFACT_SPECS 推不出来）。
                            //   begin 在此清残留，取走见下方 take()，兜底清理在 finally。
                            com.rokidlab.phone.ai.ProducedFileSink.begin()
                            try {
                                results[idx] = runToolInTurn(tc, agentTurn, execCtx)
                            } finally {
                                if (liveTool) com.rokidlab.phone.ai.tools.ShellToolProvider.setLiveOutputSink(null)
                            }
                            val execElapsedMs = android.os.SystemClock.elapsedRealtime() - execT0
                            // ⚠️ 必须在**执行工具的这个线程**上取（ThreadLocal 语义），且必须在
                            // trace 回调之前 —— 与 runTool 紧邻，避免中间被别的逻辑岔开。
                            // 出图工具（眼镜相机 look_at_view / 手机截屏 capture_screen）都走这条通道，
                            // 名单见 VisionToolProvider.pendingImageTools（新增出图工具必须登记）。
                            if (tc.name in com.rokidlab.phone.ai.tools.VisionToolProvider.pendingImageTools) {
                                lookImages[idx] = com.rokidlab.phone.ai.tools.VisionToolProvider.takePendingImage()
                            }
                            // 工具自己回报的产物（取走即清，必须在构造终态过程行之前取）
                            val reportedFiles = com.rokidlab.phone.ai.ProducedFileSink.take()
                            if (traceVisible) {
                                val okStep = !AgentStep.isFailureResult(results[idx])
                                val endState =
                                    if (okStep) AgentStep.State.OK else AgentStep.State.FAILED
                                // 参数推出的产物（ARTIFACT_SPECS）+ 工具回报的产物（ProducedFileSink）
                                // 合并；按路径去重，参数推的那条优先（它的 action 更精确，如 READ/WRITE）。
                                val baseArts = if (tc.name == ToolRegistry.TOOL_CODE_FILE) {
                                    saveCodeArtifact(
                                        tc.arguments,
                                        results[idx]?.startsWith("已生成") == true,
                                    )
                                } else {
                                    AgentStep.artifactOf(tc.name, tc.arguments, artifactBaseDirOf(tc.arguments))
                                }
                                val arts = if (reportedFiles.isEmpty()) {
                                    baseArts
                                } else {
                                    val known = baseArts.mapTo(HashSet()) { it.path }
                                    baseArts + reportedFiles
                                        .filter { it.path !in known }
                                        .map { AgentStep.Artifact(it.path, it.label, AgentStep.Action.CREATE) }
                                }
                                val step = AgentStep.tool(
                                    tc.id, tc.name, endState,
                                    argsRaw = tc.arguments, result = results[idx],
                                    durationMs = execElapsedMs,
                                    artifacts = arts,
                                )
                                onTrace?.invoke(step)
                            }
                            // 计划更新（update_plan 伪工具）：
                            //  - 手机端：结构化计划清单（勾选式，同 key 覆盖，只显示最新一版）；
                            //  - 眼镜端：不下发（2026-09-26 用户实测后拍板：单行「计划 2/5」没显示出来
                            //    且不想要；眼镜过程只保留工具行）；
                            //  - 流程级 checkpoint：计划落盘后即使本 turn 被截断/中断，下轮也能接着做。
                            if (tc.name == com.rokidlab.phone.ai.AgentPlan.TOOL_NAME) {
                                val planSteps = com.rokidlab.phone.ai.AgentPlan.parseSteps(tc.arguments)
                                if (planSteps != null) {
                                    onTrace?.invoke(AgentStep.plan(planSteps))
                                }
                                com.rokidlab.phone.ai.AgentTaskStore.recordPlan(
                                    session.appContext, goal = text, arguments = tc.arguments,
                                )
                            }
                            // 落盘结果一句话回报眼镜（「√/×」覆盖上面的「→ 正在生成」，最终 TTS 总结再接续）
                            if (isCodeFile && relFile.isNotEmpty()) {
                                val r = results[idx]
                                link?.let {
                                    sendGlassesProgress(
                                        it,
                                        // ★ 精简：只回「符号 + 文件名」，失败建议留给手机端/TTS 总结
                                        if (r?.startsWith("已生成") == true) "√ $relFile"
                                        else "× $relFile",
                                    )
                                }
                            }
                            // 通用完成行：发过「→ 开始」行的工具（非 silent、非代码落盘——它有专属行）
                            // 结束时回报 √/×，过程行才完整（此前普通工具查完就没了，只有开始没有结果）
                            else if (!silent) {
                                val r = results[idx]
                                val ok = r != null && !AgentStep.isFailureResult(r)
                                link?.let {
                                    sendGlassesProgress(
                                        it,
                                        // ★ 精简：× 已经表达失败，不再补「失败」二字
                                        if (ok) "√ ${ToolRegistry.doneText(tc.name)}"
                                        else "× ${ToolRegistry.doneText(tc.name)}",
                                    )
                                }
                            }
                        } finally {
                            // 残留兜底：**必须挂在 runToolWorker 自己的 finally 上**，
                            // 不能挂在泳道 launcher（launchDedicatedLane）里——挂那里只覆盖并行泳道。
                            // 2026-10-07 审计实测：`capture_screen` 是 sideEffect=true
                            // ⇒ **必然走串行泳道**（serialIdx.forEach 直接在生成协程线程上跑，
                            // 而它是 Dispatchers.IO 池线程），恰好是 launcher 兜底覆盖不到的那一条。
                            // 后果：runToolInTurn 之后若抛异常，用户整屏截图留在池线程上，
                            // 被下一个恰好复用该线程的无关任务取走（上一个用户的画面泄漏进本次对话）。
                            //
                            // 三条泳道（并行/串行/收尾）共用这一个 finally，行为一致。
                            com.rokidlab.phone.ai.tools.VisionToolProvider.clearPendingImage()
                            com.rokidlab.phone.ai.subagent.ReadOnlySubagent.clearPendingUserText()
                            // write 型子代理 sink 的同类兜底：工具在真正执行前被拦下时
                            // 槽位不会被 take 走，不清会让池线程上后来者拿到旧 sink。
                            com.rokidlab.phone.ai.subagent.WriteSubagent.clearSubStepSink()
                            // 产物回报通道的同类兜底（2026-10-13）：工具被拦下/抛异常时槽位
                            // 不会被 take 走，不清会让后来者拿到上一件产物的路径（错挂芯片）。
                            com.rokidlab.phone.ai.ProducedFileSink.clear()
                            // finally 兜底 countDown（幂等，可重复减到 0）：body 若抛出未捕获异常，
                            // latch 永不归零 ⇒ await 永久挂起 ⇒ 生成线程卡死。
                            // 串行泳道没有自己的份额，多减在 count=0 后是无操作，无害。
                            latch.countDown()
                        }
                    }

                    // 先发射并行泳道，再在本线程按原顺序串行跑副作用工具，最后等并行批汇合
                    parallelIdx.forEach { idx ->
                        // ★ 2026-10-06 审计修：并发工具改用**每条泳道一根专属单线程**。
                        //
                        // 原实现走 `aiScope.launch`（Dispatchers.IO 池，**线程跨任务复用**），
                        // 而工具产出经 ThreadLocal 回传给主循环
                        // （`VisionToolProvider.takePendingImage` / 子代理的用户原话）——
                        // 这在池线程上是**不成立**的：
                        //   ① 取不到：调度器把写与读派到不同线程 ⇒ `get()` 返回 null，
                        //      症状是"工具说好了给图，模型却什么都没看到"；
                        //   ② 更严重的是**残留泄漏**：中途抛异常时 ThreadLocal 留在池线程上，
                        //      被下一个恰好复用该线程的无关任务取走 ⇒
                        //      **上一个用户的眼镜画面/原话被注入本次对话**。
                        //
                        // 专属单线程让"写入与读取同线程"重新成立（这本来就是 ThreadLocal 的前提），
                        // 且 `finally` 里显式清残留，线程归还前不留任何产出。
                        // 代际取消不波及这些子任务 —— 工具必须跑完当前这一次调用。
                        launchDedicatedLane("ai-turn-worker-${idx}") {
                            // 残留清理在 runToolWorker 自己的 finally 里（2026-10-07 上提），
                            // 三条泳道共用同一处兜底 —— 这里不再重复，避免"两处清理、漏一处"的错觉。
                            runToolWorker(idx)
                        }
                    }
                    serialIdx.forEach { idx ->
                        // ★ H1 修复（2026-10-13 审计）：副作用工具执行**前**补一个代际检查点。
                        //
                        // 改造前检查点只在每轮开头（`for (round ...)` 顶部），而确认等待是 18~40s
                        // 的同步阻塞 —— 用户在这段时间里叫停（或发新消息抢占）时：
                        //   ① 协程取消 → 线程中断 → 通道返回"没问到" → PROCEED 工具被静默放行，
                        //      副作用真的发生（删文件/装包），而结果随代际作废被一并丢弃，
                        //      用户、模型、过程卡片三边都看不到；
                        //   ② 即便通道那侧已按 [ApprovalGate.ConfirmOutcome.aborted] 拒绝，
                        //      这一层再兜一次，覆盖"工具不检查中断标志"的少数实现。
                        // 只对**串行泳道**（副作用工具）生效：并行泳道是只读工具，
                        // 按设计"代际取消不波及这些子任务"，不在这里跳过。
                        if (isSuperseded()) {
                            val skipped = turn.toolCalls[idx]
                            results[idx] = "任务已中止，未执行：'${skipped.name}'"
                            Log.i(TAG, "skip side-effect tool ${skipped.name} (superseded at serial lane)")
                        } else {
                            runToolWorker(idx)
                        }
                    }
                    // 超时兜底（2026-10-02 体检）：await 是阻塞调用、不响应协程取消 —— 若 aiScope 在
                    // launch 的子任务起跑前被取消（服务销毁时序），协程体不执行、finally 里的
                    // countDown 不会跑，无界 await 会把生成线程永久挂死。
                    // ⚠️ 上限 150s（2026-10-13 审计修）：必须**严格大于**并行泳道里最长的工具等待，
                    //    否则会出现"主循环已超时放行、真实结果随后才到"的分叉。最长的是
                    //    ask_user 的 120s（AskUserChannel.ASK_TIMEOUT_MS，READ_ONLY 伪工具走并行泳道），
                    //    改造前两者都是 120s ⇒ 恰好等值时存在边界竞争（模型上下文里是
                    //    "工具执行失败"，事件流里是真实结果）。留 30s 余量把注释里那句
                    //    "远大于正常上界"重新变成事实。
                    // 只在「子任务永远没起跑」的病态场景触发；超时放行后缺位的并行结果
                    // 由下方 `results[idx] ?: "工具执行失败"` 兜底，顺序不变。
                    val joined = latch.await(150, java.util.concurrent.TimeUnit.SECONDS)
                    if (!joined) {
                        Log.e(TAG, "parallel tool lane did not join in 150s (scope cancelled?) — proceed with partial results")
                    }
                    // 按原顺序回填 tool 消息
                    turn.toolCalls.forEachIndexed { idx, tc ->
                        // 收集代码落盘事实：供本回合最终回复收敛为“已生成 N 个文件…”的简短结论
                        if (tc.name == ToolRegistry.TOOL_CODE_FILE) {
                            codeGenUsed = true
                            if (results[idx]?.startsWith("已生成") == true) {
                                codeFileTargetOf(tc)?.let { (p, f) ->
                                    val files = genFilesByProject.getOrPut(p) { LinkedHashSet() }
                                    files.add(f)
                                    // ★ 多文件生成汇总进度（2026-10-12 对标 Codex）：每成功落一个
                                    //   文件就刷新同一行框架步骤「项目 p 已生成 N 个文件」——
                                    //   此前只有逐个 save_code_file 工具行，没有"整个项目
                                    //   生成了多少"的汇总视角，多文件长任务看不到进度全貌。
                                    onTrace?.invoke(
                                        AgentStep.framework("项目 $p 已生成 ${files.size} 个文件", "codegen:progress"),
                                    )
                                    // 产物级 checkpoint：成功落盘的文件进任务状态，
                                    // 续做时模型知道哪些文件已有、不必重新生成
                                    com.rokidlab.phone.ai.AgentTaskStore.recordArtifact(
                                        session.appContext, goal = text, project = p, file = f,
                                    )
                                }
                            }
                        }
                        val raw = results[idx] ?: "工具执行失败"
                        // ★ 失败教训自动沉淀（2026-10-06）：原来教训要靠模型自己想起来调
                        //   manage_memory(kind=lesson) 写入，没有"失败后自动提炼"这一步 ——
                        //   而模型恰恰在刚失败时最不会主动记账。同一类错误于是反复踩。
                        //   这里只沉淀**框架能确证**的失败（文案由框架自己产出），
                        //   不做语义分析：把"没有找到歌曲"这类正常返回也记成教训，
                        //   会让教训库被噪音灌满、注入时挤掉真正有用的那几条。
                        if (longTermOn && !localLight &&
                            com.rokidlab.phone.ai.AgentStep.isFailureResult(raw)
                        ) {
                            runCatching {
                                // ⚠️ 用 addLessonOnce（按工具名去重）而不是 addLesson：
                                //   同一个工具失败十次就是十条近似重复的教训，会把 60 条上限灌满。
                                //   它的 KDoc 明确写了就是为"自动沉淀"设计的。
                                //
                                // 🚨🚨 只写**框架自产的确定性文案**，绝不写 raw（2026-10-07 安全修复）：
                                //   raw 是工具的原始输出，对 fetch_webpage / search_web 这类工具
                                //   而言**整段可能来自攻击者控制的网页正文**。
                                //   教训库是长期记忆、每轮注入提示词，且注入时尾句是**指令性**的
                                //   「做同类事情时按它来」（OpenAiService.buildSystemMessage）
                                //   ⇒ 写 raw 等于让攻击者写一条持久生效的指令，
                                //   且**攻击者不需要接触这台设备**，构造一个让工具失败的页面就够了。
                                //
                                //   📌 同一个类里另一处（:996 工具抛异常那条）**本来就是这么做的**
                                //   ——它只写 lastError 的短摘要 + 框架的处置建议，不写全文。
                                //   这里曾经是两种做法，现已对齐。
                                com.rokidlab.phone.ai.LongTermMemoryManager.addLessonOnce(
                                    session.appContext,
                                    dedupKey = tc.name,
                                    content = com.rokidlab.phone.ai.LessonText.toolFailed(tc.name),
                                )
                            }.onFailure { Log.w(TAG, "recordLesson failed: ${it.message}") }
                        }

                        // 工具输出截断：dumpsys/df 等可能返回超长文本，全量回填浪费 session.token 且易超模型上下文。
                        // 超过上限截断为开头预览 + 明确提示（模型通常只用开头几行结论；
                        // 若确实需要更多可说明已截断让模型如实回复）。实现见 ToolRegistry.truncateToolOutput。
                        // load_skill 系列返回的是技能说明书/章节全文，必须完整给模型，跳过截断。
                        // read_code_file 返回项目源码全文，同样跳过截断（截断会导致模型基于残缺代码改写）。
                        val skipTruncate = tc.name == com.rokidlab.phone.ai.SkillRegistry.TOOL_NAME ||
                            tc.name == com.rokidlab.phone.ai.SkillRegistry.TOOL_NAME_SECTION ||
                            tc.name == ToolRegistry.TOOL_READ_CODE_FILE
                        val result = if (skipTruncate) {
                            raw
                        } else {
                            com.rokidlab.phone.ai.truncateToolOutput(raw)
                        }
                        if (result !== raw) {
                            Log.w(TAG, "tool ${tc.name} output truncated: ${raw.length} chars")
                        }
                        // ⚠️ 只记**工具名与长度**，绝不记参数正文（2026-10-10 用户反馈：生成的源码 /
                        //    shell 脚本正文经这一行原样漏进了系统日志 logcat）。参数与结果全文另有
                        //    去向：App 内「过程」卡片（用户可见）、agent_sessions 事件流（应用私有）。
                        Log.i(
                            TAG,
                            "tool ${tc.name} args=${tc.arguments.length}chars -> ${result.length}chars",
                        )
                        // ★ 诉求漂移自省（2026-10-12，补 AgentStep 注释承诺的 intent_mismatch）：
                        // 生成任务里模型跑去 git clone 外部仓库 —— 真机实例（贪吃蛇白屏修复）：
                        // 修贪吃蛇却在 Download/Lab/ 下克隆了 pacman 参考仓库（30s+ 下载、留下整个
                        // 无关项目目录、事后无人清理），且全程无任何自省——intent_mismatch 触发器
                        // 此前只存在于 KDoc 里没有实现。这里把「拉外部仓库」这个最容易漂移的大动作
                        // 先兜住。⚠️ 只提示不拦截（clone 可能正是用户要求的"帮我 clone xx 项目"）。
                        val driftNote = if (!driftNoted && tc.name == "run_shell" &&
                            runCatching {
                                JSONObject(tc.arguments).optString("command")
                            }.getOrDefault("").contains(
                                Regex("git\\s+clone", RegexOption.IGNORE_CASE),
                            )
                        ) {
                            driftNoted = true
                            agentTurn?.reflect(
                                com.rokidlab.phone.ai.session.ReflectTrigger.INTENT_ACTION_MISMATCH,
                                detail = "生成任务中拉取外部仓库（git clone）",
                                action = "确认克隆确属用户诉求；完成后说明理由并清理无关目录",
                            )
                            onTrace?.invoke(
                                AgentStep.reflect(
                                    "intent_mismatch",
                                    "拉取外部仓库（git clone）——已提醒确认这步是否必要",
                                )
                            )
                            "\n\n（框架提醒：本轮在生成任务里执行了 git clone。若这不是用户明确要求的动作，请在最终回复说明克隆它的理由；用完的参考仓库要清理掉，不要把无关项目目录留在用户下载目录。）"
                        } else {
                            ""
                        }
                        // ★ 原地打转检测（2026-10-09，P0-6）：同指纹在滑动窗口内第 2 次出现就提醒。
                        // 现有自省只认"工具抛异常"，而真机的失效模式是**工具全部成功却毫无进展**
                        // （gps-live：37 次调用 0 写、同一文件读 5 遍、同一个不存在的文件读 2 遍），
                        // 全程无异常 ⇒ 所有守卫看不见。这里用"调用指纹重复"作为"没进展"的可靠代理。
                        // ⚠️ 只提示、不拦截：拦截会在"重读一个可能已变的文件"这类正当场景下造成能力缺失。
                        val repeatCount = com.rokidlab.phone.ai.ToolRepetitionTracker.noteAndCount(
                            // ⚠️ 会话隔离的 key 用**聊天会话 id**（`ChatStateHolder.currentSessionId`），
                            // 不是 `session`（那是眼镜连接会话 CxrLHiRokidSession，与聊天会话无关）。
                            // 跨会话隔离是硬要求：否则"新对话"会被别的对话的重复计数误伤。
                            com.rokidlab.phone.store.ChatStateHolder.currentSessionId,
                            com.rokidlab.phone.ai.ToolRepetitionTracker.fingerprint(tc.name, tc.arguments),
                        )
                        val repeatNote = if (repeatCount >=
                            com.rokidlab.phone.ai.ToolRepetitionTracker.WARN_FROM_OCCURRENCE
                        ) {
                            Log.w(TAG, "repeated call #$repeatCount: ${tc.name} (args unchanged)")
                            // P0-7：反思落盘（第二种"假成功"：原地打转）
                            agentTurn?.reflect(
                                com.rokidlab.phone.ai.session.ReflectTrigger.REPEATED_CALL,
                                detail = "同一调用第 $repeatCount 次出现：${tc.name}（参数一致）",
                                action = "提醒模型换动作（写/改/换参数）或说明卡在哪一步",
                            )
                            // ★ 同时进「过程」（2026-10-10）：用户有权知道它在自己打转。
                            //   "同一个文件读了 5 遍"比"它回答了"更能解释这次为什么慢/这么差，
                            //   而此前这件事只存在于 logcat 与事件流里（要导出来才看得见）。
                            onTrace?.invoke(
                                AgentStep.reflect(
                                    "repeat_call",
                                    "同一调用第 $repeatCount 次出现（${tc.name}，参数一致）",
                                )
                            )
                            "\n\n" + com.rokidlab.phone.ai.ToolRepetitionTracker.reminder(tc.name, repeatCount)
                        } else {
                            ""
                        }
                        // 注入隔离：外部作者内容（网页/MCP/文档/OCR/子助手）包进 <untrusted_source>，
                        // 本地确定性工具结果原样回填。信任级别在 ToolEntry 声明、这里单一出口包装。
                        // ⚠️ 提醒语拼在**包装之外**：它是框架自产的控制信号，
                        // 若拼进 <untrusted_source> 里，模型会把它当成"外部资料里的文字"而不敢照做
                        // —— 注入隔离的语义会反过来伤到自己。
                        val safeResult = com.rokidlab.phone.ai.UntrustedContent.wrap(
                            ToolRegistry.contentTrustOf(tc.name), tc.name, result,
                        ) + repeatNote + driftNote
                        messages.put(JSONObject().apply {
                            put("role", "tool")
                            put("tool_call_id", tc.id)
                            put("content", safeResult)
                        })
                    }
                    // 视觉工具拍到的画面：补一条带 image_url 的 user 消息，让**主模型自己看图**。
                    //
                    // 为什么不让工具自己去问模型：工具线程正处在 `aiSendLock` 之内，
                    // 再回调 `sendAiTextMessage` 会**非重入死锁**（同一个 synchronized 对象）。
                    // 补一条消息则是协议合法的（tool 结果之后跟 user 消息），且只多一次图片输入、
                    // 不额外发起一次对话 —— 模型还能顺带用上会话提示词与前面的上下文。
                    //
                    // ⚠️ 只注入 provider **真的取到图**的那些（vision 路径没走通时为空），
                    // 因此不会出现"说好了给图却没图"或给不支持的模型塞图片（那会整轮 400）。
                    lookImages.forEachIndexed { idx, img ->
                        if (img.isNullOrBlank()) return@forEachIndexed
                        // 图片来源决定说明文字：截图说成"眼镜拍到的画面"会让模型对着一张手机屏幕
                        // 讲"现实世界"，两次转述都错
                        val caption = if (turn.toolCalls[idx].name ==
                            com.rokidlab.phone.ai.ScreenCaptureTools.TOOL_CAPTURE
                        ) {
                            "这是刚才截取的用户手机屏幕画面，请据此回答用户的问题。"
                        } else {
                            "这是刚才用眼镜拍到的画面，请据此回答用户的问题。"
                        }
                        messages.put(JSONObject().apply {
                            put("role", "user")
                            put("content", JSONArray().apply {
                                put(JSONObject().apply {
                                    put("type", "text")
                                    put("text", caption)
                                })
                                put(JSONObject().apply {
                                    put("type", "image_url")
                                    put(
                                        "image_url",
                                        JSONObject().put("url", "data:image/jpeg;base64,$img"),
                                    )
                                })
                            })
                        })
                        Log.i(TAG, "look_at_view: injected image for call #$idx (${img.length} b64 chars)")
                    }
                    // AIUI/代码生成会话降载：本轮执行过 save_code_file 或 load_skill(aiui-dev) 后，
                    // 自下一轮起只装配精简 AIUI 子集，省去 ~14 个无关工具的 Schema 反复下发。
                    if (!readOnlyTools && !aiuiMode && turn.toolCalls.any { isCodeGenCall(it.name, it.arguments) }) {
                        aiuiMode = true
                        activeTools = buildTools(ToolRegistry.SESSION_AIUI_DOMAINS)
                        assembledToolNames = ToolRegistry.namesOf(activeTools)
                        // ★ 必须同时重建 messages（2026-10-06 审计修）：系统提示里的
                        // `has("工具名")` 闸门是按**实际下发的工具集**生成的，
                        // `assembledToolNames` 只被 buildMessages 读取 —— 只换工具表而
                        // 不重建 messages，下一轮请求就会带着**切换前**的全量闸门。
                        // 而 SESSION_AIUI_DOMAINS 相比 SESSION_AGENT_DOMAINS 摘掉了
                        // glasses/timer/media/display/phone/research/vision/hearing/mcp/browser/screen 十个域，
                        // 模型会被引导去调 5 个**已不再下发**的工具
                        // （look_at_view、manage_glasses_app、schedule_agent_task、
                        //   delete_calendar_event、research_subtask）
                        // ⇒ 每轮白耗一次 unknown tool。
                        // 与上方"聊天探测升级"（:1832 无条件重建）是同一件事，本处只是漏了。
                        // ⚠️ 2026-10-09 修正（此处原先是无条件重建，是个**漏点**）：
                        // 重建会把本轮**已经 put 进 messages 的工具结果与图片一起冲掉** ——
                        // 而触发条件恰恰是"本轮刚调过 save_code_file"（isCodeGenCall 命中）。
                        // 症状：AIUI 生成时进度显示文件已落盘，下一轮却不认、重复生成；
                        // 多模态轮刚拍的照片模型自己看不见（图片只在 messages 里，事件流不存 base64）。
                        //
                        // 📌 判据：**只有本轮没有任何工具调用/结果时才能重建**（与上方探测轮
                        // 的 probeRound 闸门同款）。工具表闸门不刷新会导致"模型去调已摘掉的工具"，
                        // 那个代价是每轮白耗一次 unknown tool；而在这里丢的是**已执行动作的结果**，
                        // 代价更大 —— 两者不可比，不能为了闸门正确性牺牲 in-flight 的工具配对。
                        //
                        // 代价：首次切入 aiuiMode 那一轮，人设里的工具闸门仍按切换前的全量算，
                        // 可能多引导一次调已摘掉的工具（下一个 tool-call 轮会自然纠正）。
                        if (turn.toolCalls.isEmpty()) {
                            messages = buildMessages()
                            Log.i(TAG, "aiuiMode on: tools switched to SESSION_AIUI_DOMAINS subset (${activeTools.size} schemas), messages rebuilt")
                        } else {
                            // ⚠️ 本轮有工具调用 ⇒ **不重建**。只换工具表、不重建 messages：
                            // 下一轮请求带的是"切换前"的人设闸门，可能多引导一次调已摘掉的工具
                            //（代价：一次 unknown tool）；反过来重建则要丢掉本轮刚 put 的工具结果
                            //（代价：已执行动作的结果丢失 + 模型不认刚生成的文件）。
                            // 两者不可比，选代价小的那个。
                            Log.i(TAG, "aiuiMode on: tools switched to SESSION_AIUI_DOMAINS subset (${activeTools.size} schemas), messages NOT rebuilt (in-flight tool results)")
                        }
                    }
                    // ★ token 记账（2026-10-06）：在**分账结算之前**，因为两者任一触顶都要收尾，
                    //   而收尾分支的文案要能区分是"轮次用完"还是"预算用完"。
                    //   口径：服务端 usage 优先；端点不给（部分实现忽略
                    //   stream_options.include_usage）时用 TokenMeter 估算本轮**新增**内容
                    //   （输出 + 工具结果），prompt 部分不重复计 —— 历史每轮都要重发，
                    //   把 prompt 全额累加会让数字虚高到第 2 轮就误触顶。
                    val estThisRound = com.rokidlab.phone.ai.compaction.TokenMeter.count(
                        (turn.content ?: "") + results.joinToString("") { it ?: "" },
                    )
                    val charged = tokenBudget.chargeRound(turn.usage, estThisRound)
                    // ★ 实时 token 计量（2026-10-12 对标 Codex）：每轮计费后刷新的累计值，
                    //   过程卡在生成中显示「≈N tokens」（ChatStateHolder.liveTokens）。
                    com.rokidlab.phone.store.ChatStateHolder.setLiveTokens(tokenBudget.total)
                    if (turn.usage != null || estThisRound > 0) {
                        Log.i(
                            TAG,
                            "token round=$round charged=$charged total=${tokenBudget.total} " +
                                "(${tokenBudget.summary()})",
                        )
                    }
                    // 分账结算：整轮都是只读工具才扣只读额度，否则（含混合轮）按动作轮计费
                    if (turn.toolCalls.all { isReadOnlyTool(it.name) }) readOnlyRounds++ else actionRounds++
                    if (tokenBudget.shouldStop(charged)) {
                        // ⚠️ token 触顶**不自动续做**（与轮次触顶相反）：续做意味着再来一轮
                        //   请求，而触发条件本身就是"这一轮已经花了不少"。续做会把超支放大，
                        //   那是拿"把活干完"换"账单失控"。这里如实收尾并说清原因。
                        tokenExhausted = true
                        Log.i(
                            TAG,
                            "token budget exhausted at round=$round (${tokenBudget.summary()}), " +
                                "rounds: action=$actionRounds readonly=$readOnlyRounds",
                        )
                        if (!localLight) {
                            com.rokidlab.phone.ai.AgentTaskStore.markStatus(
                                session.appContext,
                                com.rokidlab.phone.ai.AgentTaskStore.STATUS_BUDGET_EXHAUSTED,
                                "token 预算用尽（可续做）",
                                userText = text,
                            )
                        }
                        // ★ 进「过程」（2026-10-10）：token 触顶是**框架替用户做的决定**
                        //   （"我不再让它继续花钱"），此前只落任务快照与 logcat ——
                        //   用户只看到回答突然变短，没有任何解释。
                        //   用 FRAMEWORK 而不是 THINKING：不是模型在想，是框架叫停。
                        onTrace?.invoke(
                            AgentStep.framework(
                                "token 预算用尽，就此收尾（${tokenBudget.summary()}）",
                                id = "token_exhausted",
                            )
                        )
                        break
                    }
                    if (actionRounds >= MAX_ACTION_ROUNDS || readOnlyRounds >= MAX_READONLY_ROUNDS) {
                        Log.i(
                            TAG,
                            "tool loop budget exhausted at round=$round " +
                                "(action=$actionRounds/$MAX_ACTION_ROUNDS, readonly=$readOnlyRounds/$MAX_READONLY_ROUNDS)",
                        )
                        // ── 自动续做（缺口「把活干完」）────────────────────────────────────
                        // 分账额度触顶 ≠ 任务做完：总轮次硬顶往往还有余量，而旧实现在这里直接
                        // 跳出，把余量扔掉、把"接着做"推给用户的一句「继续」。这里改成**就地
                        // 再开一段**（受 MAX_AUTO_CONTINUES 约束，仍受 MAX_TOTAL_ROUNDS 硬顶限制），
                        // 并注入一条"接着做、别重做已完成部分"的引导。
                        //
                        // 例外：只读模式（定时自主任务）不续做 —— 那是无人值守场景，没人能打断，
                        // 越长的自主执行越像失控；宁可如实报告本轮查到什么。
                        if (autoContinues < MAX_AUTO_CONTINUES && !readOnlyTools && !localLight) {
                            autoContinues++
                            readOnlyRounds = 0
                            actionRounds = 0
                            // ★ 进「过程」（2026-10-10）：自动续做是**框架追加了预算**，
                            //   用户看到的现象是"它怎么还在跑"—— 不说就会以为死循环。
                            onTrace?.invoke(
                                AgentStep.framework(
                                    "工具轮次额度已自动追加，继续执行剩余步骤",
                                    id = "auto_continue_$autoContinues",
                                )
                            )
                            messages.put(JSONObject().apply {
                                put("role", "user")
                                put(
                                    "content",
                                    "继续执行剩余步骤（已完成的不要重做）：工具轮次额度已自动追加。" +
                                        "请接着完成目标；若确实已经全部完成，直接用一两句中文给出最终结论。",
                                )
                            })
                            link?.let { sendGlassesProgress(it, "→ 继续执行…") }
                            // ★ 同上：手机过程也要能看到预算触顶的自动续做（原先只有眼镜有）
                            onTrace?.invoke(
                                AgentStep.thinking(
                                    round = round,
                                    detail = "预算已自动追加，继续执行剩余步骤…",
                                    state = AgentStep.State.RUNNING,
                                ),
                            )
                            Log.i(
                                TAG,
                                "auto-continue #$autoContinues at round=$round: split budget reset, " +
                                    "remaining total rounds=${MAX_TOTAL_ROUNDS - round - 1}",
                            )
                            continue
                        }
                        budgetExhausted = true
                        // 立即落一条终态：即使随后进程被杀/链路断，下轮也知道「上次是预算用尽」
                        if (!localLight) {
                            com.rokidlab.phone.ai.AgentTaskStore.markStatus(
                                session.appContext,
                                com.rokidlab.phone.ai.AgentTaskStore.STATUS_BUDGET_EXHAUSTED,
                                "工具轮次预算用尽（可续做）",
                                userText = text,
                            )
                        }
                        break
                    }
                }
                // 工具轮预算用尽或某轮空返回，仍无最终回复：必须先带 tools 再请求一次强制生成总结。
                // AIUI/代码生成回合模型可能仍需调 save_code_file 等工具落盘；摘掉工具会导致它只能
                // 把源码当纯文本输出、随后被 finalizeCodeGenReply 收敛丢弃（“生成卡死/白耗”根因）。
                // 收尾阶段最多 SUMMARY_MAX_ROUNDS 轮，每轮都注入收尾引导强制模型给出结论。
                if (reply.isBlank()) {
                    // 用户已打断或链路已失效：跳过非流式兜底请求，直接放弃
                    if (isSuperseded()) {
                        Log.i(TAG, "AI summary superseded (gen=$generation, latest=$aiGenSeq), skip final chat")
                        agentTurn?.attempt(
                            com.rokidlab.phone.ai.session.AttemptOutcome.SUPERSEDED,
                            "summary",
                        )
                        agentTurn?.finish(
                            com.rokidlab.phone.ai.session.TurnEndReason.INTERRUPTED,
                            partialReply = streamedBuf.toString(),
                        )
                        if (!localLight) {
                            com.rokidlab.phone.ai.AgentTaskStore.markStatus(
                                session.appContext,
                                com.rokidlab.phone.ai.AgentTaskStore.STATUS_INTERRUPTED,
                                "收尾阶段被新消息打断（可续做）",
                                userText = text,
                            )
                        }
                        // ★ 进「过程」（2026-10-12，P1）：收尾阶段被抢占也不再静默（同轮首检查点）
                        onTrace?.invoke(
                            AgentStep.framework(
                                "收尾被打断，进度已保存；说「继续」可接着做",
                                id = "superseded_summary",
                            )
                        )
                        return@launchAiGeneration
                    }
                    var finalTurn: com.rokidlab.phone.ai.ChatTurn? = null
                    // 总结轮里模型给出的正文。不能用「最后一轮的 content」代替 —— 末轮若只调了工具，
                    // content 为空，而前一轮的结论句其实是有效的（旧实现会连它一起丢掉）。
                    var summaryText: String? = null
                    // 收尾占位是否真的发出去过（决定要不要补一条 summaryDone 结清它）
                    var summaryStepShown = false
                    // 收尾阶段摘掉出图工具（look_at_view / capture_screen）：总结轮没有
                    // takePendingImage 的带图注入链，模型调了只会得到「图片会随本条消息一起交给你」
                    // 的空头承诺 —— 图片永不投递，模型极易幻觉描述画面。收尾唯一必要动作是
                    // save_code_file 落盘（收尾引导也是这么说的），出图在这里没有任何正当用途。
                    activeTools.removeAll { t ->
                        val n = t.optJSONObject("function")?.optString("name")
                        n != null && n in com.rokidlab.phone.ai.tools.VisionToolProvider.pendingImageTools
                    }
                    for (retry in 0 until SUMMARY_MAX_ROUNDS) {
                        // 每轮都必须注入收尾引导：实测模型会把总结轮额度继续花在「再读一个文件」上，
                        // 三轮 content 全空、最后 reply 落到固定兜底文案，用户被告知"无法处理"，
                        // 而文件其实已经落盘。只放行 save_code_file —— 收尾阶段唯一必要的工作是落盘，
                        // 其余读取类调用纯属浪费轮次；若上一轮仍没给结论，措辞升级为「禁止」。
                        val nudge = if (readOnlyTools) {
                            // 只读模式（定时自主任务）：本轮不存在 save_code_file，别按"还能落盘"引导，
                            // 直接要求给结论；也不需要"预算用尽让用户说继续"（无人值守没人接话）
                            "请直接收尾：不要再调用任何工具，用一两句中文给出最终结论。"
                        } else if (retry == 0) {
                            "请收尾：如果还有文件没落盘，只允许再调用 save_code_file 写入（一次一个文件）；" +
                                "除此之外不要再调用任何工具，直接用一两句中文给出最终结论。" +
                                // 触顶是"预算用完"而非"任务做完"：必须让模型在结论里如实交代未完成部分，
                                // 而不是让用户以为任务已结束（旧行为是静默截断）。
                                if (budgetExhausted) {
                                    "注意：本次工具调用轮次预算已经用尽，因此请在结论中如实说明还剩哪些步骤没做，" +
                                        "并提示用户说「继续」以便接着完成。"
                                } else {
                                    ""
                                }
                        } else {
                            "你上一次仍没有给出结论。现在除必要的 save_code_file 落盘外禁止再调用任何工具，" +
                                "请立刻用一两句中文给出最终结论。"
                        }
                        // 主循环空轮退出时末尾已是一条 user 引导语，此时替换而非追加：
                        // 连续两条 user 消息会被部分服务端直接 400，把整个收尾阶段打掉。
                        val tail = messages.optJSONObject(messages.length() - 1)
                        if (tail != null && tail.optString("role") == "user") {
                            tail.put("content", nudge)
                        } else {
                            messages.put(JSONObject().apply {
                                put("role", "user")
                                put("content", nudge)
                            })
                        }
                        finalTurn = try {
                            // 总结轮常携带大工具参数/大段代码，deepseek 单次生成可能远超默认 30s：
                            // 用 120s 单次（不重试，避免翻倍等待）保证能等到模型产出 save_code_file 调用。
                            service.chatTurn(messages, tools = activeTools, readTimeout = CODE_GEN_READ_TIMEOUT_MS, attempts = 1)
                        } catch (e: Exception) {
                            // 不能静默吞：收尾轮失败时如果只留 contentLen=0，日志里看不出是
                            // 「模型没给结论」还是「请求本身被拒」，而那正是本次事故的排查瓶颈
                            Log.w(TAG, "summary chatTurn failed: ${e.message}")
                            null
                        }
                        // 成本记账：收尾轮也是真实请求（且常是大 prompt），一并累进本轮
                        agentTurn?.recordModelCall(finalTurn?.usage)
                        // 先留下正文（模型可能一边调工具一边给结论），再判断是否继续
                        finalTurn?.content?.takeIf { it.isNotBlank() }?.let { summaryText = it }
                        // ★ 收尾阶段也要进「过程」（2026-10-10）。此前这段跑工具完全静默：
                        //   过程卡片停在主循环最后一个 THINKING:OK 上，用户以为已经答完了，
                        //   而模型实际上还在最多 [SUMMARY_MAX_ROUNDS] 轮里写着文件
                        //   （每轮最长 [CODE_GEN_READ_TIMEOUT_MS]）—— 界面看着空转。
                        // 用 [AgentStep.Kind.SUMMARY] 而非 THINKING：这次等待的语义是
                        //   「框架在补跑落盘」，不是「模型在想」—— 用 THINKING 会让用户
                        //   以为它还在推理，而实际它在写文件。
                        if (!finalTurn?.toolCalls.isNullOrEmpty()) {
                            onTrace?.invoke(AgentStep.summaryRunning())
                            summaryStepShown = true
                        }
                        // 收尾轮逐轮打点：这一阶段此前没有任何日志，出问题时只能从"没有输出"反推
                        Log.i(
                            TAG,
                            "summary round=$retry/$SUMMARY_MAX_ROUNDS toolCalls=${finalTurn?.toolCalls?.size ?: 0} " +
                                "contentLen=${finalTurn?.content?.length ?: 0} finish=${finalTurn?.finishReason ?: "none"}",
                        )
                        if (finalTurn == null || finalTurn.toolCalls.isEmpty()) break
                        // 回填 assistant tool_calls 消息（协议要求原样携带）
                        val assistantMsg = JSONObject()
                        assistantMsg.put("role", "assistant")
                        assistantMsg.put("content", JSONObject.NULL)
                        // 思考开启时回传 reasoning_content（同主循环，DeepSeek V4 多轮校验）
                        if (finalTurn.reasoning != null) assistantMsg.put("reasoning_content", finalTurn.reasoning)
                        val calls = JSONArray()
                        finalTurn.toolCalls.forEach { tc ->
                            calls.put(JSONObject().apply {
                                put("id", tc.id)
                                put("type", "function")
                                put("function", JSONObject().apply {
                                    put("name", tc.name)
                                    put("arguments", tc.arguments)
                                })
                            })
                        }
                        assistantMsg.put("tool_calls", calls)
                        messages.put(assistantMsg)
                        // 同步执行本轮工具调用并回填结果（总结轮工具极少，无需并发）
                        finalTurn.toolCalls.forEach { tc ->
                            // ★ 收尾轮的工具也进「过程」（与主循环同一条通道，见上方说明）。
                            //   这是目前**唯一**能看到"文件还在陆续落盘"的地方 ——
                            //   收尾最多跑 3 轮、每轮可达 120s，此前这段时间界面完全无反馈。
                            val isSumCodeFile = tc.name == ToolRegistry.TOOL_CODE_FILE
                            val sumBase = if (isSumCodeFile) null else artifactBaseDirOf(tc.arguments)
                            // save_code_file 的产物走专用提取（参数是 project+file，不在通用表里）；
                            // 不传的话 tool() 会把含源码正文的原始参数漏进过程行。
                            val sumArtifacts = if (isSumCodeFile) {
                                saveCodeArtifact(tc.arguments, succeeded = false)
                            } else {
                                null
                            }
                            onTrace?.invoke(
                                AgentStep.tool(
                                    tc.id, tc.name, AgentStep.State.RUNNING,
                                    argsRaw = tc.arguments, artifactBaseDir = sumBase,
                                    artifacts = sumArtifacts,
                                )
                            )
                            val sumT0 = android.os.SystemClock.elapsedRealtime()
                            val out = runToolInTurn(tc, agentTurn, execCtx)
                            onTrace?.invoke(
                                AgentStep.tool(
                                    tc.id,
                                    tc.name,
                                    if (AgentStep.isFailureResult(out)) AgentStep.State.FAILED
                                    else AgentStep.State.OK,
                                    argsRaw = tc.arguments,
                                    result = out,
                                    durationMs = android.os.SystemClock.elapsedRealtime() - sumT0,
                                    artifactBaseDir = sumBase,
                                    artifacts = if (isSumCodeFile) {
                                        saveCodeArtifact(tc.arguments, out.startsWith("已生成"))
                                    } else {
                                        null
                                    },
                                )
                            )
                            // 兜底排水：schema 已摘但模型凭记忆复述出图工具名时仍会真执行 ——
                            // ThreadLocal 里的图必须取走（否则滞留到同线程后续轮），并把空头承诺
                            // 纠正成明确的事实，防止模型幻觉描述画面
                            val outWithDrain = if (tc.name in com.rokidlab.phone.ai.tools.VisionToolProvider.pendingImageTools) {
                                val drained = com.rokidlab.phone.ai.tools.VisionToolProvider.takePendingImage()
                                if (drained != null) {
                                    out + "\n（系统更正：收尾阶段无法携带图片，这张图已被丢弃。请不要再调用拍照/截屏工具，直接用文字给出最终结论。）"
                                } else out
                            } else out
                            if (tc.name == ToolRegistry.TOOL_CODE_FILE) {
                                codeGenUsed = true
                                if (out.startsWith("已生成")) {
                                    codeFileTargetOf(tc)?.let { (p, f) ->
                                        genFilesByProject.getOrPut(p) { LinkedHashSet() }.add(f)
                                    }
                                }
                            }
                            messages.put(JSONObject().apply {
                                put("role", "tool")
                                put("tool_call_id", tc.id)
                                // 同一处注入隔离出口（与主工具循环一致）
                                put(
                                    "content",
                                    com.rokidlab.phone.ai.UntrustedContent.wrap(
                                        ToolRegistry.contentTrustOf(tc.name), tc.name, outWithDrain,
                                    ),
                                )
                            })
                        }
                        // 兜底总结轮同样支持进入 AIUI 精简工具子集（省 session.token），下一轮重试即生效
                        if (!readOnlyTools && !aiuiMode && finalTurn.toolCalls.any { isCodeGenCall(it.name, it.arguments) }) {
                            aiuiMode = true
                            activeTools = buildTools(ToolRegistry.SESSION_AIUI_DOMAINS)
                            assembledToolNames = ToolRegistry.namesOf(activeTools)
                            // 同主循环 :2244 —— 只换工具表不重建 messages，下一轮的 system
                            // 仍带切换前的全量 has() 闸门，引导模型去调已摘域的工具。
                            //
                            // ⚠️ 2026-10-09 修正：此处原先是无条件重建，且原注释写着
                            // 「buildMessages 从 agentTurn.historyForRequest() 重建、不自引用，
                            //  所以在这里重建不会丢掉本轮刚注入的图片消息」—— **与事实相反**：
                            // 多模态图片只存在于 messages（事件流只存文本，不存 base64），
                            // 而上面几行刚把工具结果 put 进 messages，重建会把它们一并冲掉。
                            // 闸门与主循环一致：仅在本轮无工具调用时重建。
                            if (finalTurn.toolCalls.isEmpty()) {
                                messages = buildMessages()
                                Log.i(TAG, "aiuiMode on (final chat): tools switched to SESSION_AIUI_DOMAINS (${activeTools.size}), messages rebuilt")
                            } else {
                                Log.i(TAG, "aiuiMode on (final chat): tools switched to SESSION_AIUI_DOMAINS (${activeTools.size}), messages NOT rebuilt (in-flight tool results)")
                            }
                        }
                    }
                    // 收尾结束：结清那条「收尾中」占位（同 key 覆盖，不需要单独的收尾调用）。
                    // ⚠️ **无论有没有产物都要结清** —— 收尾轮 break 有多条出口
                    // （无工具调用 / 用完 SUMMARY_MAX_ROUNDS / 请求失败），
                    // 漏一条就留下一颗永远转圈的点。
                    if (summaryStepShown) {
                        onTrace?.invoke(
                            AgentStep.summaryDone(genFilesByProject.values.sumOf { it.size })
                        )
                    }
                    // 全程没有正文时先留空，交给下方 finalizeCodeGenReply / 最终兜底决定。
                    // 不能在这里就填固定兜底文案：那串文案只有 23 字符、也不像代码，
                    // finalizeCodeGenReply 会把它当「模型给出的正常结论句」原样保留，
                    // 从而丢掉真正的权威结论 —— 实测表现为「文件已落盘却回报无法处理」。
                    reply = summaryText.orEmpty()
                }
                // AIUI/代码生成回合：把模型最终回复收敛为简短结论，严禁把整段源码当回复
                // 播报/显示到眼镜；同时保证写入会话记忆的是干净文本，避免历史脏样本反复
                // 诱导模型继续输出代码（“回复里还有出现代码”的根因之一）
                if (codeGenUsed) {
                    val totalFiles = genFilesByProject.values.sumOf { it.size }
                    val projName = genFilesByProject.keys.firstOrNull()
                    val authoritative = if (totalFiles > 0) {
                        buildString {
                            append("已生成 $totalFiles 个文件，保存在手机下载目录")
                            if (!projName.isNullOrBlank()) append("的“$projName”项目")
                            append("。")
                        }
                    } else {
                        // 一次 save_code_file 都没写成功（参数被截断 / 校验被拒）：必须给失败结论。
                        // 传 null 会落到 finalizeCodeGenReply 内部的「文件已生成完毕，保存在手机下载目录。」
                        // 兜底 —— 用户看到的是假成功提示，而实际一个文件都没有落盘。
                        "抱歉，这次没能成功生成文件，生成被中断了。请再说一次，或把需求拆小一点（比如先只做首页）。"
                    }
                    reply = com.rokidlab.phone.ai.finalizeCodeGenReply(reply, authoritative)
                    // 落盘产物 → 聊天文件卡：收敛后的回复只有一句「已生成 N 个文件」，
                    // 用户在聊天流里没有任何运行入口（2048 那次：代码进了文件，找遍聊天没按钮）。
                    // writeProjectFile 落盘时在私有目录留了镜像（filesDir/aiui_projects/<项目>/），
                    // 文件卡读镜像即可，不用碰 MediaStore；可运行文件（py/js/sh/html）的卡上
                    // 自带运行键，与代码块卡片同一条分流链路。
                    runCatching {
                        val appCtx = session.appContext
                        genFilesByProject.forEach { (proj, files) ->
                            files.forEach { rel ->
                                val mirror = java.io.File(appCtx.filesDir, "aiui_projects/$proj/$rel")
                                if (mirror.isFile) {
                                    android.util.Log.i(TAG, "codegen file card: $proj/$rel (${mirror.length()}B)")
                                    // ★ 工具循环线程不是主线程：文件卡要写 Compose 状态列表，
                                    //   统一切回主线程（[ChatStateHolder.runOnMain] 是唯一入口）
                                    com.rokidlab.phone.store.ChatStateHolder.runOnMain {
                                        com.rokidlab.phone.store.ChatStateHolder.addFile(
                                            isUser = false,
                                            path = mirror.absolutePath,
                                            name = "$proj/$rel",
                                            chars = mirror.length().toInt(),
                                        )
                                    }
                                }
                            }
                        }
                    }.onFailure { Log.w(TAG, "codegen file cards failed: ${it.message}") }
                }
                // ★ 终态护栏（2026-10-09，P0-1）：**内部标记永不进入用户可见文本**。
                // 这是唯一的出口 —— 显示、眼镜 TTS、会话记忆三处都从这里取（看下一行的 replyRef）。
                // 为什么放在终态而不是只在升级分支拦：标记的变形无穷（下划线/空格/连字符/大小写），
                // 只有"它最后变成了要播给用户的那段文本"这个位置能穷尽所有情形。
                // 循环内已尽力消化（升级 / 硬引导）；走到这里说明都没成功 —— 那就如实兜底，
                // 而不是把 `[NEED_TOOLS]` 播给用户（gps-live 事故正是漏在这一步）。
                val leakedBeforeStrip = com.rokidlab.phone.ai.InternalMarkerGuard.isMarkerOnly(reply)
                if (leakedBeforeStrip) {
                    markerLeakSeen = true
                    Log.w(TAG, "internal marker reached final reply, stripping: ${reply.take(40)}")
                }
                // 宽清洗：连"我看看…\n[NEED_TOOLS]"这种带前缀的形态也剥掉（防漏）
                reply = com.rokidlab.phone.ai.InternalMarkerGuard.stripMarkers(reply)
                // 走到这里仍无正文 = 模型既没给结论、也没落盘过文件（codeGenUsed=false 时
                // finalizeCodeGenReply 不会兜底）：只有这种情况才用固定兜底文案。
                if (reply.isBlank()) {
                    reply = if (markerLeakSeen) {
                        // 标记轮如实告知"没做成"，而不是"请换个说法" —— 后者会误导用户以为是提问方式的问题
                        "抱歉，这次我没能完成。我已经拿到了需要的工具但仍没给出结论，请再说一次，或把要求拆小一点。"
                    } else {
                        "抱歉，我暂时无法处理这个问题，请换个说法再试一次。"
                    }
                }
                // 剥离模型偶发输出的包裹标签（如 `<answer>…</answer>`）。
                // 放在「落 replyRef 之前」＝显示、语音播报、会话记忆三处统一拿到干净文本。
                // 真机事故（2026-09-17 15:11）：眼镜上直接显示 `<answer>西安明天晴，最高31度…`。
                reply = com.rokidlab.phone.ai.ReplySanitizer.sanitize(reply)
                // ★ 触顶确定性收尾（2026-10-12，P0-B）：轮次/token 预算用尽时，由**框架**在
                //   回复尾部追加"停在哪 + 还剩什么 + 说继续可接着做"，不依赖收尾轮模型自觉。
                //   为什么放这里（定稿链最后一步、replyRef.set 之前）：
                //   ① 收尾总结轮可能 3 轮无正文 → reply 落固定兜底「抱歉，无法处理」——
                //      那串文案必须照样带上预算说明，用户才知道不是任务完成；
                //   ② codeGenUsed 时 finalizeCodeGenReply 会把结论收敛成「已生成 N 个文件」，
                //      模型按提示写的"还剩哪些步骤"在这条路径上会被整段丢弃 —— 只有在
                //      收敛**之后**追加才保得住；
                //   ③ 放在 sanitize 之后：这段文案由框架拼，不走清洗器，避免被误伤。
                //   下一轮的续做由 AgentTaskStore.pendingContext 注入（说「继续」即接续）。
                if (budgetExhausted || tokenExhausted) {
                    val stopNote = if (localLight) {
                        // 本地轻量模式不落任务快照（markStatus 全程被 !localLight 挡住），
                        // 读快照会拿到旧会话的残留 —— 退化为不带步骤的通用说明
                        val reason = if (tokenExhausted) "本轮 token 预算用尽" else "本轮工具轮次预算用尽"
                        "（$reason，先做到这里。对我说「继续」我可以接着做。）"
                    } else {
                        com.rokidlab.phone.ai.AgentTaskStore.budgetStopNote(
                            session.appContext, tokenExhausted = tokenExhausted,
                        )
                    }
                    reply = (reply + "\n\n" + stopNote).trim()
                }
                replyRef.set(reply)
                // 粘滞位落账：本轮用过工具（含收尾阶段的 save_code_file）→ 下一轮闲聊快路避让
                lastTurnHadToolCalls = turnUsedTools || codeGenUsed
                // 收尾本轮事件流：落 [AssistantMessage] + [TurnEnd]，并做压力裁剪
                // （= 改造前 recordTurn 里那一步 trim）。工具轨迹不再单独存一份字符串列表 ——
                // 它由本轮的 ToolCall 事件派生（同一条事实只留一个出处）。
                // ⚠️ P0-1：本轮出现过标记泄漏 ⇒ **不得记 COMPLETED** ——
                // 记"已完成"等于对用户和后续排查都说谎（gps-live 案 5 轮全是 completed，
                // 单看事件流完全看不出"它其实什么也没产出"）。
                agentTurn?.finish(
                    when {
                        budgetExhausted -> com.rokidlab.phone.ai.session.TurnEndReason.BUDGET_EXHAUSTED
                        markerLeakSeen -> com.rokidlab.phone.ai.session.TurnEndReason.FAILED
                        else -> com.rokidlab.phone.ai.session.TurnEndReason.COMPLETED
                    },
                    reply,
                )
                // 任务终态结算（缺口「长任务可恢复」）：计划步骤全 done 或没有计划 → 完成；
                // 仍有 pending/in_progress → 标为可续做，下轮注入提示词时模型据此接着做。
                if (!localLight) {
                    com.rokidlab.phone.ai.AgentTaskStore.settleAfterTurn(
                        session.appContext,
                        reason = if (budgetExhausted) "工具轮次预算用尽（可续做）" else null,
                    )
                }
                Log.i(TAG, "AI reply generated in ${System.currentTimeMillis() - tGenStart}ms: ${reply.take(80)}...")
            } catch (e: Exception) {
                // 用户打断（代际被抢占）或链路已被替换导致的终止（chatTurnStream 被取消 / 轮间 return
                // 前的异常）：静默退出，不覆盖 replyRef（保持空），也避免误播"服务不可用"
                if (isSuperseded()) {
                    Log.i(TAG, "AI generation aborted (superseded, gen=$generation): ${e.message}")
                    agentTurn?.attempt(
                        com.rokidlab.phone.ai.session.AttemptOutcome.SUPERSEDED,
                        e.message,
                    )
                    // ⚠️ 与上面两处同款：半截正文必须一起落，否则用户看得见、模型看不见
                    agentTurn?.finish(
                        com.rokidlab.phone.ai.session.TurnEndReason.INTERRUPTED,
                        detail = e.message,
                        partialReply = streamedBuf.toString(),
                    )
                    if (!localLightMode) {
                        com.rokidlab.phone.ai.AgentTaskStore.markStatus(
                            session.appContext,
                            com.rokidlab.phone.ai.AgentTaskStore.STATUS_INTERRUPTED,
                            "被新消息打断（可续做）",
                            userText = text,
                        )
                    }
                    // ★ 进「过程」（2026-10-12，P1）：异常路径的抢占同样不再静默
                    //   （与上方两处 superseded 分支同款；锚点已结清时落空，无害）
                    onTrace?.invoke(
                        AgentStep.framework(
                            "任务已中断，进度已保存；说「继续」可接着做",
                            id = "superseded_catch",
                        )
                    )
                    return@launchAiGeneration
                }
                Log.e(TAG, "DeepSeek API failed", e)
                // 事件流收尾：本轮失败（未产出结论）→ 记一次失败尝试并把本轮声明为"不算数"，
                // 与改造前"失败不写会话记忆"的行为一致，但这次**留下了可归因的事实**。
                agentTurn?.attempt(
                    com.rokidlab.phone.ai.session.AttemptOutcome.FAILED,
                    e.message,
                )
                agentTurn?.finish(
                    com.rokidlab.phone.ai.session.TurnEndReason.FAILED,
                    detail = e.message,
                    // ⚠️ 2026-10-09 补漏：这一分支原先**只**传 detail，与上面两处 superseded
                    // 分支不一致 ⇒ 网络异常时已经流式显示的半截正文既没落事件流、
                    // 又会被下面的失败提示整段覆盖（用户看到"正在读的字被抹掉"）。
                    partialReply = streamedBuf.toString(),
                )
                // 如实上报 + 记进 App 内日志面板：此前流式 HTTP 报错被静默吞成空轮，
                // 用户只看到「我无法处理这个问题」，在「日志」里也找不到任何原因
                // （2026-09-15 真机事故：连续空轮、日志只有 toolCalls=0 content=null）。
                com.rokidlab.phone.util.LogCollector.e(TAG, "AI 生成失败: ${e.message}", e)
                replyRef.set(com.rokidlab.phone.ai.OpenAiService.aiFailureHint(e))
            }
        }

        // 步骤-1 在 AI 线程启动后执行（原位置在 thread 启动前）：打断等待 300ms 与 AI 请求并行
        runOfficialInterrupt()

        // ===== 步骤-1b: 抢占官方文案的显示位（仅眼镜语音链路）=====
        // 背景（2026-09-17 真机实测）：官方云的答案比我们的大模型快得多 —— 官方 `ASR_End` 后
        // 仅 3ms 它就把「新城区今天晴…」推上屏（`showUpdateTTSUI` status3），而我们的第一条
        // 文本要等大模型首次工具调用（约 1s 后）才发；两者落在**同一个气泡**（`id=1`），
        // 于是用户会读到约 1s 的官方文案（实测 14:31:28.649 上屏 → 14:31:29.670 被覆盖）。
        // 这里先发一条极短占位，把官方文案的可见时间压到单程链路耗时（约 0.15s）。
        // 注意：入站 `TTS_Result` 只更新 UI、不会触发官方 TTS 播报（播报走我们的 `tts_play`），
        // 所以这条占位是静默的。
        if (localTakeover && link != null) {
            var shieldResult: Int? = null
            val shieldCaps = Caps()
            shieldCaps.write("TTS_Result")
            shieldCaps.write("→ 正在思考…\n\n")
            shieldCaps.write(LinkProtocol.AI_REPLY_MARK)
            synchronized(session.aiCmdLock) {
                if (!abortAiSendIfLinkInvalid(link, onResult)) return
                shieldResult = link.sendCustomCmd(LinkProtocol.CXR_CHANNEL_AI, shieldCaps)
            }
            Log.i(TAG, "sendCustomCmd(Ai, TTS_Result, 占位“正在思考…”) -> $shieldResult (shield official text)")
        }

        // ===== 步骤0+1+2: 开启会话 + 显示提问 + 结束识别 =====
        // localTakeover：眼镜端已本地完成（KeyButtonService ASR_End 后立即 open + 显示提问），
        // 下行无需重发，避免官方界面残留"思考中"等待手机端轮询（约 3s 空白）。
        // ⚠️ 2026-09-17 实测教训：**不要**在这里给 localTakeover 补发 KeyDown_Client/open。
        // 补发后官方会 `startNewTalk` + `insertNew` 出一个新气泡，但 Lab 的 TTS_Result 便不再
        // 更新对话框（只有 `TtsResultHandler` 日志、没有 `showUpdateTTSUI`/`AiAdapter setData`），
        // 表现＝「有声音没文字」，官方自己的答案反而留在原气泡里。已回滚，详见当日日志。
        var keyDownResult: Int? = 0
        if (link == null) {
            // 本机模式：没有眼镜可下发显示，整段跳过（原 5 条下行全部不做）
            Log.i(TAG, "local-only: skip KeyDown/ASR_Result/ASR_End downlink (no glasses)")
            session.onStatus("正在获取 AI 回复…（本机模式，未连接眼镜）")
        } else if (!localTakeover) {
            // 0. 发送 KeyDown_Client（privacy_level=2）：眼镜端 AIPhoneOpenHandler 在 AI 未运行时
            //    约 500ms 后自行 openAiAssistant() -> openSceneWithIgnoreTips("ai_assist") ->
            //    AIOpenHandler/startNewTalk，这是 ASR_Result / TTS_Result 能显示文字的前置条件。
            val keyDownCaps = Caps()
            keyDownCaps.write("KeyDown_Client")
            keyDownCaps.write("{\"privacy_level\":2}")
            synchronized(session.aiCmdLock) {
                if (!abortAiSendIfLinkInvalid(link, onResult)) return
                keyDownResult = link.sendCustomCmd(LinkProtocol.CXR_CHANNEL_AI, keyDownCaps)
            }
            Log.i(TAG, "sendCustomCmd(Ai, KeyDown_Client, privacy_level=2) -> $keyDownResult")

            // 对齐官方 App 文字输入时序（2026-09-19 双端抓包实测）：
            // 官方 App 从不手机端下发 open —— 眼镜收到 KeyDown 后约 502ms 必然自开场景。
            // 但 ASR 不能抢在场景打开之前发：实测早到时 AsrMessageHandler 虽执行，却没有
            // 会话 item 可写，随后 AIOpenHandler 的 clearData 又把状态清空 → 提问文字丢失，
            // 只弹出一个空 AI 助手（官方 App 间隔 213ms，官方自己也有这个 bug）。
            // 旧实现 sleep(600) 后补发 open 则会撞在眼镜自开时刻，startNewTalk 跑两次
            // ＝双气泡（2026-09-17「有声音没文字」竞态根因）。
            //
            // 现节奏（方案 B）：KeyDown → 等眼镜上行 Ai_SceneStatus（场景已开）→ ASR_Result →
            // ASR_End 连发。实测信号在 KeyDown 后约 718ms 到手机；800ms 超时兜底也已晚于
            // 502ms 自开 + 链路传输余量（眼镜 ~885ms 收到 ASR，场景已开 383ms），文字必能落
            // 到会话 item；热场景（状态已知打开）则立即放行，不多等。
            var sceneReady = session.waitAiSceneOpen(SCENE_OPEN_WAIT_MS)
            Log.i(TAG, "waitAiSceneOpen after KeyDown -> ready=$sceneReady")
            if (!sceneReady) {
                // 冷启动/眼镜端服务刚拉起时官方自开会更慢（见 SCENE_OPEN_RETRY_MS 注释）。
                // ⚠️ 这里**不能**按原节奏硬发：ASR 早于场景打开到达会被 AsrMessageHandler 丢弃
                // （无会话 item 可写，随后 AIOpenHandler.clearData 又清空）⇒ 本轮注定「有声音没文字」。
                Log.w(TAG, "AI scene not open in ${SCENE_OPEN_WAIT_MS}ms (cold start?), wait longer")
                sceneReady = session.waitAiSceneOpen(SCENE_OPEN_RETRY_MS)
                Log.i(TAG, "waitAiSceneOpen retry -> ready=$sceneReady")
                if (!sceneReady) {
                    Log.w(TAG, "AI scene still not open after retry; sending anyway (text may not render)")
                }
            }

            // ===== 步骤1: 发送 ASR_Result（用户文字）=====
            // 语音唤醒链路（showAsrResult=false）：官方 ASR 已在眼镜上显示提问，不再重发避免重复显示。
            if (showAsrResult) {
                val asrCaps = Caps()
                asrCaps.write("ASR_Result")
                asrCaps.write(text)
                synchronized(session.aiCmdLock) {
                    if (!abortAiSendIfLinkInvalid(link, onResult)) return
                    asrResult = link.sendCustomCmd(LinkProtocol.CXR_CHANNEL_AI, asrCaps)
                }
                Log.i(TAG, "sendCustomCmd(Ai, ASR_Result, \"$text\") -> $asrResult")
            } else {
                Log.i(TAG, "skip ASR_Result resend (voice wakeup chain, question already shown)")
            }

            // ===== 步骤2: 发送 ASR_End（标记 ASR 结束）=====
            val endCaps = Caps()
            endCaps.write("ASR_End")
            synchronized(session.aiCmdLock) {
                if (!abortAiSendIfLinkInvalid(link, onResult)) return
                endResult = link.sendCustomCmd(LinkProtocol.CXR_CHANNEL_AI, endCaps)
            }
            Log.i(TAG, "sendCustomCmd(Ai, ASR_End) -> $endResult")
            session.onStatus("已发送到眼镜，正在获取 AI 回复...")
        } else {
            Log.i(TAG, "localTakeover: skip KeyDown/open/ASR_Result/ASR_End (glasses already shown)")
            session.onStatus("正在获取 AI 回复...")
        }

        // ===== 等待 DeepSeek 完成（下行显示期间已并行执行）=====
        // runBlocking 而非 Thread.join：Job 是协程句柄；本调用方本就是后台线程且历来在此
        // 阻塞等待（最长一轮生成的时长），语义与 join 完全一致
        runBlocking { deepSeekJob.join() }
        // 兜底再清洗一次（生成线程里已清过一次；失败兜底文案等其他赋值路径也覆盖到）。
        // 幂等，成本可忽略。
        reply = com.rokidlab.phone.ai.ReplySanitizer.sanitize(replyRef.get())
        // 登记回复全文：环境音链路据此做 TTS 回声判定（播报被远场麦拾到会形成自激，见 dispatchGlassesAsrText）
        lastAiReplyText = reply
        lastAiReplyAtMs = System.currentTimeMillis()
        // 生成期间用户已发起新请求（新语音/新消息）或本次请求的 link 已被替换/断开：
        // 本回复已过期（可能为空串，由 isSuperseded 触发的提前退出所致），
        // 放弃显示/播报/回调，复位状态并把链路让给新请求（join 期间生成协程已提前完成，等待有界）
        if (isSuperseded()) {
            Log.i(TAG, "sendAiTextViaLink: reply superseded during generation (gen=$generation, latest=$aiGenSeq), skip downlink")
            session.mainHandler.post {
                session.connection.completeActiveOperation()
                session.onBusyChanged(false)
                onResult?.invoke(false, null)
            }
            return
        }
        Log.i(TAG, "AI reply ready: ${reply.take(40)}")
        glassSpoken = com.rokidlab.phone.ai.ReplySanitizer.sanitizeForGlass(reply).ifBlank { reply }
        // 云萌音色：先把语音备好（合成+下载+解码约 1-2s，期间文字不下发），
        // ready 后 onReply/TTS_Result/起播三步紧挨着执行 —— 文字与声音同步出现。
        // 备声失败置空，下方回退眼镜端离线 TTS。
        if (VoicePrefs.cloudEnabled && glassSpoken.isNotBlank()) {
            val prepared = VoiceBroadcast.prepare(glassSpoken, VoicePrefs.voiceId, VoicePrefs.rate).getOrNull()
            if (prepared != null && prepared.size >= 2) cloudPcm = prepared
            if (isSuperseded()) {
                Log.i(TAG, "sendAiTextViaLink: reply superseded during voice prepare, skip downlink")
                session.mainHandler.post {
                    session.connection.completeActiveOperation()
                    session.onBusyChanged(false)
                    onResult?.invoke(false, null)
                }
                return
            }
        }
        // 手机气泡拿 Markdown 原文（渲染代码块卡片等富文本）；眼镜版在步骤4 下发前现算
        onReply?.invoke(reply)
        } catch (e: Exception) {
            // 下行指令段（sendCustomCmd/sleep/join）异常：统一复位状态，避免 sending/busy 永久卡死
            Log.e(TAG, "AI send downlink failed", e)
            session.mainHandler.post {
                session.connection.completeActiveOperation()
                session.onBusyChanged(false)
                onResult?.invoke(false, "AI send error: ${e.message}")
            }
            return
        }

        // ===== 步骤4: 发送 TTS_Result（AI 回复）到眼镜 =====
        try {
            // 本机模式：回复已在上方经 onReply 交给聊天窗口，这里没有眼镜可下发。
            // 必须显式补上「收尾」（busy 复位 + onResult 成功）：否则调用方会一直停在 busy，
            // 表现为发送按钮转圈不停/后续消息被当成并发而拒绝。
            if (link == null) {
                Log.i(TAG, "local-only: skip TTS_Result/tts_play downlink, reply delivered to chat only")
                // 云萌音色：本机模式没有眼镜链路，语音在手机端播（上面已备好）
                val localPcm = cloudPcm
                if (localPcm != null) {
                    Thread { VoiceBroadcast.playPcm(localPcm) }
                        .apply { name = "voice-broadcast"; isDaemon = true }.start()
                }
                session.mainHandler.post {
                    session.onStatus("AI 回复已生成（本机模式：仅手机端）")
                    session.connection.completeActiveOperation()
                    session.onBusyChanged(false)
                    onResult?.invoke(true, null)
                }
                return
            }
            // join 等待 DeepSeek 期间可能发生 session.cleanup 断开/替换 link，发送前重新校验
            if (!abortAiSendIfLinkInvalid(link, onResult)) return
            // 时机内容绑定（[SKIP]）：无人值守主动轮里模型判定「此刻没值得说的」的内部控制信号，
            // 不下发眼镜（TTS_Result/tts_play 全部抑制）；改发 TTS_AudioFinished 复位助手页
            // （清掉「正在思考…」占位），回调照常收尾 —— [SKIP] 由调用方（TimerScheduler）静默收回。
            if (com.rokidlab.phone.proactive.ProactiveGatePolicy.isSkipReply(reply)) {
                Log.i(TAG, "reply is [SKIP]: suppress TTS_Result/tts_play, send TTS_AudioFinished to reset scene")
                try {
                    val skipFinishCaps = Caps()
                    skipFinishCaps.write("TTS_AudioFinished")
                    skipFinishCaps.write("true")
                    link.sendCustomCmd(LinkProtocol.CXR_CHANNEL_AI, skipFinishCaps)
                    Log.i(TAG, "TTS_AudioFinished sent (skip cleanup)")
                } catch (e: Exception) {
                    Log.w(TAG, "skip cleanup TTS_AudioFinished failed: ${e.message}")
                }
                session.mainHandler.post {
                    session.onStatus("本轮无值得说的，已静默跳过")
                    session.connection.completeActiveOperation()
                    session.onBusyChanged(false)
                    onResult?.invoke(true, null)
                }
                return
            }
            // 拆两份：**显示帧（TTS_Result）发 markdown 原文** —— 官方对话框的气泡渲染器吃
            // markdown（官方自己的回复就是带格式渲染的），此前显示/播报共用剥格式的口语文本，
            // 导致眼镜上"没有任何格式"；**播报帧（tts_play）仍用口语文本**：窄屏+语音不适合读
            // 符号，围栏代码块不播报（整段是代码时引导看手机）。
            // ⚠️ 若真机上官方渲染器把 MD 原文显示成裸符号，回退点＝把下面的 ttsCaps.write(reply)
            // 改回 glassSpoken（一行）。
            // （glassSpoken 声明已提前到 try 外的共享变量区，reply ready 后即刻填充）
            // 官方协议: caps[0] = "TTS_Result", caps[1] = 回复文字，
            // caps[2] = Lab 来源标记（眼镜端据此区分 Lab 回复与官方回声，见 LinkProtocol.AI_REPLY_MARK）。
            // 官方只按索引读前两个元素，多写一个会被忽略，向后兼容。
            // （2026-09-26 用户拍板：回复前不再加分隔线——过程/答案的区分靠 MD 渲染已足够。）
            // 图表回合（Cap.CHART_VIEW）：眼镜官方气泡渲染器不渲染 echarts，```echarts 代码块
            // 原样下发就是一屏代码（2026-10-06 真机实测）。显示帧把闭合 echarts 块替换为占位一行；
            // 同时自动把图表离屏渲染推送到眼镜全屏查看器（滑动切换），与图表卡「推眼镜」按钮共用
            // ChartGlassPush 渲染路径。异步执行不阻塞 TTS_Result/tts_play；推送结果仅记日志。
            var glassDisplay = reply
            val chartSources = extractEChartsSources(reply)
            if (chartSources.isNotEmpty()) {
                val note = if (GlassesHandshake.supports(LinkProtocol.Cap.CHART_VIEW) == false) {
                    // 旧眼镜端无查看器能力：不下发代码，只引导回手机看
                    session.appContext.getString(R.string.glass_chart_phone_note)
                } else {
                    chartSources.take(5).forEach { src -> // 眼镜端查看器 MAX_SLIDES=5
                        // caption 取 option.title.text（多张滑动时帮助辨认），解析失败留空
                        val caption = runCatching {
                            JSONObject(src).optJSONObject("title")?.optString("text").orEmpty()
                        }.getOrDefault("")
                        ChartGlassPush.push(session.appContext, src, caption) { msg ->
                            com.rokidlab.phone.util.LogCollector.i(TAG, "auto chart push: $msg")
                        }
                    }
                    session.appContext.getString(R.string.glass_chart_pushed_note)
                }
                glassDisplay = replaceEChartsBlocks(reply, note)
            }
            val ttsCaps = Caps()
            ttsCaps.write("TTS_Result")
            ttsCaps.write(glassDisplay)
            ttsCaps.write(LinkProtocol.AI_REPLY_MARK)
            val ttsResult = link.sendCustomCmd(LinkProtocol.CXR_CHANNEL_AI, ttsCaps)
            Log.i(TAG, "sendCustomCmd(Ai, TTS_Result, \"${reply.take(40)}...\") -> $ttsResult")

            // 与 tts_play 之间加 300ms 间隔，降低链路抖动时两条指令一起丢失的概率
            // （云萌音色没有第二条指令，跳过间隔直接起合成线程）

            // ===== 步骤4.5: 播报 =====
            // 云萌音色：语音已在上方（reply ready 后）预合成备好，这里直接起播 ——
            // 文字（onReply/TTS_Result）与声音几乎同时出现。仅手机出声（外放眼镜可听）。
            // 默认音色：眼镜端离线 TTS（tts_play）原链路。RokidLink 收到 tts_play 后
            // 调用系统 TtsService 本地合成并播放语音
            fun sendTtsPlay(): Int? {
                if (!abortAiSendIfLinkInvalid(link, onResult)) return -99
                val ttsPlayCaps = Caps()
                AiChannel.encodeTtsPlay(glassSpoken).forEach { ttsPlayCaps.write(it) }
                return link.sendCustomCmd(AiChannel.TOPIC_TTS_PLAY, ttsPlayCaps)
            }
            fun sendTtsPlayWithRetry() {
                val r = sendTtsPlay()
                Log.i(TAG, "sendCustomCmd(tts_play, \"${reply.take(40)}...\") -> $r")
                // 发送失败（返回值 != 0）时延迟 500ms 重发一次，降低偶发丢包
                if (r != 0) {
                    Thread.sleep(500)
                    val retry = sendTtsPlay()
                    Log.w(TAG, "retry sendCustomCmd(tts_play) -> $retry")
                }
            }
            if (VoicePrefs.cloudEnabled) {
                val pcm = cloudPcm
                Thread {
                    if (pcm != null && VoiceBroadcast.playPcm(pcm).isSuccess) {
                        Log.i(TAG, "cloud voice broadcast done")
                        // 云萌音色只在手机外放，眼镜端没有 TTS 播完事件、不会上行续听标记
                        // （默认音色链路 = 眼镜 TTS 播完 -> __LAB_CONTINUE_DIALOG__ -> 重开拾音）。
                        // 这里播放完成后由手机端代发，连续对话在自定义音色下同样生效。
                        // 失败回退走眼镜端离线 TTS，那条链路眼镜自己会发标记，不在此重复。
                        requestGlassesContinueDialog()
                    } else {
                        // 预合成/播放失败 → 回退眼镜端离线 TTS，保证「有声音」不缺位
                        Log.w(TAG, "cloud voice unavailable, fallback to glass TTS")
                        runCatching { sendTtsPlayWithRetry() }
                    }
                }.apply { name = "voice-broadcast"; isDaemon = true }.start()
            } else {
                // 16KB 图表突发与 tts_play 挤同一条 BT socket 会把小语音帧挤丢
                // （2026-10-06 真机：图表回合 tts_play 无接收记录，眼镜无声且流程卡死）。
                // 先等自动图表推送全部发出（含串行渲染），再起播报。
                ChartGlassPush.awaitIdle(15_000)
                Thread.sleep(300)
                sendTtsPlayWithRetry()
            }

            // 发送 TTS_AudioFinished 通知眼镜播放完毕（可选，官方 App 也会发）。
            // 注意：TTS_AudioFinished 会触发 AssistServer 的 AudioFinishedHandler → startNewTalk，
            // 把 ai_assist 界面重置为新会话，刚显示的 Lab 回复会被清掉。
            // 眼镜 ASR 唤醒链路（handleGlassesAiAsrText）需要保留 Lab 回复显示 → 跳过该消息。
            if (!skipTtsAudioFinished) {
                Thread.sleep(500)
                val finishCaps = Caps()
                finishCaps.write("TTS_AudioFinished")
                finishCaps.write("true")
                link.sendCustomCmd(LinkProtocol.CXR_CHANNEL_AI, finishCaps)
                Log.i(TAG, "TTS_AudioFinished sent")
            } else {
                Log.i(TAG, "skipTtsAudioFinished=true: TTS_AudioFinished not sent (keep Lab reply visible)")
            }

            session.mainHandler.post {
                session.onStatus("AI 回复已发送: \"${reply.take(30)}...\"")
                session.connection.completeActiveOperation()
                session.onBusyChanged(false)
                onResult?.invoke((if (showAsrResult) (asrResult ?: -1) == 0 else true) && endResult == 0 && ttsResult == 0, null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "AI reply send failed", e)
            session.mainHandler.post {
                session.onStatus("AI 回复失败: ${e.message}")
                session.connection.completeActiveOperation()
                session.onBusyChanged(false)
                onResult?.invoke(false, "AI send error: ${e.message}")
            }
        }
        }
    }

    /**
     * 取消当前正在运行的 Lab AI 请求（用户关闭助手/停止播报时调用）。
     * 实现 = bump 代际号 + **cancel 生成协程**：
     *  - 代际号：deepSeekBody 在每轮工具循环/下行检查点比较 generation != aiGenSeq 后自弃；
     *  - 协程取消（2026-10-02）：生成跑在 runInterruptible 里，cancel 当场转化为线程中断 ——
     *    阻塞在网络读上的生成**立刻**退出，不再有"最坏等一个 readTimeout"的窗口（原局限已消除）。
     *  - SSE 流式读取在 isCancelled 回调处的逐行取消仍然有效（取消前已到检查点的场景）。
     */
    fun abortCurrentAi() {
        val seq = bumpAiGen()
        val job = activeGenJob
        if (job != null && job.first != seq) {
            job.second.cancel()
            Log.i(TAG, "abortCurrentAi: bumped aiGenSeq -> $seq, gen=${job.first} job cancelled")
        } else {
            Log.i(TAG, "abortCurrentAi: bumped aiGenSeq -> $seq (no in-flight generation)")
        }
        // ★ 2026-10-13 审计修（M3+H1）：用户叫停时，屏幕上**等待中的**确认框/选项卡必须一起撤掉，
        //   否则留下一个点什么都没用的死弹窗（改造前 abortAll 只在内存压力与眼镜 cleanup 被调，
        //   这条路径从不调）。aborted=true 让等待方立刻拿到"被中止" —— 闸门一律拒绝执行，
        //   不会有"用户已叫停却仍发生副作用"（H1）。
        com.rokidlab.phone.ai.PhoneToolConfirmChannel.abortAll(aborted = true)
        com.rokidlab.phone.ai.AskUserChannel.abortAll()
        // 收尾「过程」：被打断的请求不会再有第二段（OK/FAILED）事件，不兜底的话
        // 聊天窗口里会永远挂着一个转圈的「进行中」步骤，看起来像卡死
        traceFinishSink?.invoke(true)
        // 同步通知眼镜端停止正在播放的语音（tts_stop 下行通道）
        session.stopTtsOnGlass()
        // 云萌 PCM 流同步打断：手机端 AudioTrack 作废 + 眼镜端 PcmStreamPlayer 立即静音
        VoiceBroadcast.cancel()
        runCatching {
            session.cxrLink?.let { l ->
                val caps = Caps()
                caps.write("stop")
                l.sendCustomCmd(LinkProtocol.TOPIC_TTS_PCM_CTRL, caps)
            }
        }
    }

    /** 注册眼镜端语音对话的 UI 回调（乐奇聊天界面进入时调用）：
     *  onText：眼镜上识别出的用户提问；onReply：Lab 生成并下发到眼镜的回复 */
    fun setGlassesAiUiCallbacks(
        onText: (String) -> Unit,
        onReply: (String) -> Unit,
    ) {
        glassesAiTextCb = onText
        glassesAiReplyCb = onReply
    }

    /**
     * 注册「过程」全局汇聚点（App 启动时注册一次，见 `LabApplication.setCxrL`）。
     *
     * 注册在 App 层而不是聊天界面，有两个好处：
     *  1. **覆盖全部 AI 入口**（打字 / 眼镜语音 / 拍照答题 / 定时任务）——
     *     界面级注册只能覆盖"界面自己发起"的那条路，实测漏掉了眼镜语音；
     *  2. **任意页面都能收**：用户停在别的页面时用眼镜提问，过程照样落进聊天记录，
     *     回到聊天页即可看到（不再要求先打开过聊天页）。
     *
     * @param onStep 每条过程步骤（可能从任意后台线程回调，实现方自行切主线程）
     * @param onFinish 本轮收尾，参数 failed=true 表示整体失败/被打断。
     *   两个调用点：请求入口抢占上一轮时、[abortCurrentAi] 时。
     */
    fun setAgentTraceSink(
        onStep: ((com.rokidlab.phone.ai.AgentStep) -> Unit)?,
        onFinish: ((Boolean) -> Unit)? = null,
    ) {
        traceSink = onStep
        traceFinishSink = onFinish
    }

    /**
     * 工具**参数流式生成期**的一行实时进度（见 [TOOL_ARG_EMIT_INTERVAL_MS]）。
     *
     * 文案取自 [ToolRegistry.statusText]（眼镜进度行与状态栏的同一份"人性化进度文案"），
     * 剥掉尾缀「…」再挂上"已生成多大"——**不另造一套说法**，否则同一个动作在三处
     * 各说一词。查不到的工具类型走它的兜底（"正在执行 xxx"）。
     */
    private fun toolArgProgressText(toolName: String, argChars: Int): String {
        val base = ToolRegistry.statusText(toolName).removeSuffix("…")
        if (argChars <= 0) return base
        return "$base · ${argSizeText(argChars)}"
    }

    /** 参数字节数的可读形式（`680 B` / `3.2 KB`），与 AgentStep 的 content 大小同刻度 */
    private fun argSizeText(n: Int): String =
        if (n < 1024) "$n B" else String.format(java.util.Locale.ROOT, "%.1f KB", n / 1024.0)
}
