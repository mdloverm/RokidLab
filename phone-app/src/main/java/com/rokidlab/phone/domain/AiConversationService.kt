package com.rokidlab.phone.domain

import android.util.Log
import com.rokid.cxr.Caps
import com.rokid.cxr.link.CXRLink
import com.rokidlab.phone.ai.ToolRegistry
import com.rokidlab.phone.design.RokidHostApp
import com.rokidlab.phone.glasses.AiChannel
import com.rokidlab.phone.glasses.LinkProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
    private companion object { const val TAG = "AiConversationService" }

    /**
     * AI 下行发送互斥锁：WiFi 稳定连接时聊天发送 / ASR push / 轮询 / SDK 上行
     * 多个并发入口同时命中 sendAiTextMessage 快速路径，无锁并发 sendCustomCmd
     * 同一 CXRLink 会与 cleanup() 的 disconnect 产生竞态（SDK native 崩溃），
     * 因此 sendAiTextViaLink 全流程加锁串行执行。
     */
    private val aiSendLock = Any()

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

    /** 眼镜端唤醒词对话（语音 ASR）的 UI 回调：同步显示到乐奇聊天窗口 */
    @Volatile
    private var glassesAiTextCb: (String) -> Unit = {}
    @Volatile
    private var glassesAiReplyCb: (String) -> Unit = {}

    /**
     * 【临时测试】通过 CXR-L SDK 发送文字指令到眼镜端 AssistServer。
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
         *  为 true 时下行只发 DeepSeek 回复（TTS_Result + tts_play），跳过 KeyDown/open/ASR_Result/ASR_End */
        localTakeover: Boolean = false,
        /** 附加指令：注入 system 提示词控制回答方式（如「只显示答案」「给出解题步骤」） */
        instruction: String? = null,
        /** 是否记录到 Agent 会话记忆（多轮上下文）。拍照答题等一次性场景传 false */
        recordHistory: Boolean = true,
        /** AI 回复流式增量回调（每个 content delta），用于 UI 边生成边显示；眼镜 TTS 仍整段发送 */
        onDelta: ((String) -> Unit)? = null,
    ) {
        Log.i(TAG, "sendAiTextMessage(\"$text\") called. session.cxrlConnected=${session.cxrlConnected}, session.glassBtConnected=${session.glassBtConnected}, session.cxrLink=${session.cxrLink != null}, session.token=${session.token?.take(8) ?: "null"}")

        // 抢占新一代际：让正在执行/排队的旧 Agent 任务让路（用户打断）
        val myGen = bumpAiGen()

        // 快速路径: 如果 CXR 已连接且 link 可用，直接发送（跳过前置检查 + 重新 connect）
        val link = session.cxrLink
        if (session.cxrlConnected && session.glassBtConnected && link != null) {
            Log.i(TAG, "sendAiTextMessage: using existing CXRLink (fast path)")
            sendAiTextViaLink(link, text, onResult, onReply, contextText, interruptOfficialFirst, skipTtsAudioFinished, showAsrResult, localTakeover, instruction, recordHistory, onDelta, myGen)
            return
        }

        // 慢速路径: 需要先建立连接
        Log.i(TAG, "sendAiTextMessage: no active link, falling back to connectAndRun path")
        val targetHostApp = session.hostApp
        if (!session.hasGlassesOperationPrerequisites(targetHostApp, requestAuthorizationIfMissing = true)) {
            Log.w(TAG, "sendAiTextMessage: missing prerequisites")
            session.mainHandler.post { onResult?.invoke(false, "missing prerequisites") }
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
                    // sendAiTextViaLink 内含多次 Thread.sleep + deepSeekThread.join（最长可阻塞 30s），
                    // 必须切后台线程执行，否则慢速路径阻塞主线程导致 ANR/闪退。
                    // session.onStatus/session.onBusyChanged 已线程安全，onReply 由调用方切主线程，onResult 内部 runOnUiThread。
                    session.appScope.launch(Dispatchers.IO) {
                        sendAiTextViaLink(l, text, onResult, onReply, contextText, interruptOfficialFirst, skipTtsAudioFinished, showAsrResult, localTakeover, instruction, recordHistory, onDelta, myGen)
                    }
                },
                onFailure = {
                    session.cleanup()
                    session.onBusyChanged(false)
                    session.mainHandler.post { onResult?.invoke(false, "connection failed") }
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
    internal fun dispatchGlassesAsrText(text: String) {
        // 标记「本条 ASR 处理中」，供 AsrBridgeCoordinator 判定后续相同文字是否丢弃。
        // sendAiTextMessage 是异步回调式：onResult 在 AI 下行结束（成功/失败）时回调，
        // 此处据此复位 handling；AsrBridgeCoordinator 内还有 90s 超时兜底，防异常路径标志卡死。
        session.asrBridge.markAsrHandling(true)
        try {
            Log.i(TAG, "dispatchGlassesAsrText: $text")
            // 用户提问同步到聊天窗口（轮询在 IO 线程，需切回主线程更新 Compose 状态）
            session.safeRunOnUiThread { glassesAiTextCb(text) }
            sendAiTextMessage(
                text,
                onResult = { success, err ->
                    Log.i(TAG, "handleGlassesAiAsrText sendAiTextMessage: success=$success err=$err")
                    session.asrBridge.markAsrHandling(false)
                },
                onReply = { reply ->
                    Log.i(TAG, "handleGlassesAiAsrText reply: ${reply.take(40)}")
                    // Lab 回复同步到聊天窗口（onReply 在子线程回调，需切回主线程）
                    session.safeRunOnUiThread { glassesAiReplyCb(reply) }
                },
                interruptOfficialFirst = true,
                skipTtsAudioFinished = true,
                // 官方 ASR 已在眼镜上显示提问，下行不再重发避免重复显示
                showAsrResult = false,
                // 眼镜端 KeyButtonService 已在 ASR_End 后本地打开会话并显示提问（本地接管），
                // 下行只发 DeepSeek 回复，不再重发 KeyDown/open/ASR_End（避免官方界面残留"思考中"等待）
                localTakeover = true,
            )
        } catch (e: Exception) {
            Log.e(TAG, "handleGlassesAiAsrText error", e)
            session.asrBridge.markAsrHandling(false)
        }
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
     */
    internal fun sendGlassesProgress(link: CXRLink, text: String) {
        if (session.cxrLink !== link || !session.cxrlConnected) return
        runCatching {
            synchronized(session.aiCmdLock) {
                val caps = Caps()
                caps.write("TTS_Result")
                caps.write(text)
                link.sendCustomCmd(LinkProtocol.CXR_CHANNEL_AI, caps)
            }
        }
        session.onStatus(text)
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
     */
    internal fun sendAiTextViaLink(
        link: CXRLink,
        text: String,
        onResult: ((Boolean, String?) -> Unit)?,
        onReply: ((String) -> Unit)? = null,
        contextText: String? = null,
        interruptOfficialFirst: Boolean = false,
        skipTtsAudioFinished: Boolean = false,
        /** 是否在眼镜端重发用户问题（ASR_Result）：语音唤醒链路中官方已显示提问，传 false 避免重复 */
        showAsrResult: Boolean = true,
        /** 眼镜端是否已本地接管显示（KeyButtonService 在 ASR_End 后已本地打开会话并显示提问）：
         *  为 true 时下行只发 DeepSeek 回复（TTS_Result + tts_play），跳过 KeyDown/open/ASR_Result/ASR_End */
        localTakeover: Boolean = false,
        /** 附加指令：注入 system 提示词控制回答方式（如「只显示答案」「给出解题步骤」） */
        instruction: String? = null,
        /** 是否记录到 Agent 会话记忆（多轮上下文）。拍照答题等一次性场景传 false */
        recordHistory: Boolean = true,
        /** AI 回复流式增量回调（每个 content delta），用于 UI 边生成边显示；眼镜 TTS 仍整段发送 */
        onDelta: ((String) -> Unit)? = null,
        /** 发起时的代际号（sendAiTextMessage 入口抢占）。期间若有更新的代际进入（用户打断），本请求应放弃 */
        generation: Long,
    ) {
        // 串行化所有 AI 下行发送：聊天发送 / ASR push / 文件轮询 / SDK 上行多个并发入口
        // 在 WiFi 稳定连接时全部命中快速路径，同一 CXRLink 并发 sendCustomCmd 会与
        // session.cleanup() 的 disconnect 产生竞态（SDK native 崩溃）。加锁保证同一时刻只有
        // 一条 AI 下行链路执行，并在发送过程中持续校验 link 有效性。
        synchronized(aiSendLock) {
        // 入口校验：link 必须仍是最新且未断开（防止慢速路径 session.cleanup 后使用旧 link）
        if (!abortAiSendIfLinkInvalid(link, onResult)) return
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

        try {
        // ===== 步骤-1: （可选）先打断官方乐奇会话 =====
        // 语音唤醒链路中眼镜端已本地打断（interruptOfficialLocally），此处作为双保险，
        // 等待时间从 1000ms 压缩到 300ms 提速。localTakeover 时眼镜端已打断，跳过。
        // 注意：实际执行挪到 deepSeekThread 启动之后（见下方 runOfficialInterrupt 调用处），
        // 让 AI 网络请求与这 300ms 等待并行——对官方 App 的指令时序完全不变（Exit 仍先于
        // KeyDown_Client 下发），仅 AI 提前 ~300ms 开跑，缩短端到端首响。
        val runOfficialInterrupt: () -> Unit = {
            if (interruptOfficialFirst && !localTakeover) {
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
        val isSuperseded: () -> Boolean = { generation != aiGenSeq || session.cxrLink !== link }
        val replyRef = java.util.concurrent.atomic.AtomicReference<String>("")
        val deepSeekThread = Thread {
            val tGenStart = System.currentTimeMillis()
            try {
                val cfg = session.getAiConfig()
                // 本地 Ollama 端点：首次加载大模型/思考模型首字远慢于远程，读超时放宽到 3 分钟
                val localBase = cfg.baseUrl.contains("127.0.0.1") || cfg.baseUrl.contains("localhost") ||
                    cfg.baseUrl.contains("11434")
                // 本地用户自定义请求参数（JSON，替代原「深度思考」布尔开关）：逐字段合并进每次
                // 本地对话请求体（如 {"think": false, "options": {"num_ctx": 2048}}）；远程服务不附加
                val extraBody = if (localBase) session.aiConfig.parseLocalChatParams() else null
                val service = com.rokidlab.phone.ai.OpenAiService(
                    cfg.apiKey, cfg.model, cfg.baseUrl,
                    readTimeoutMs = if (localBase) 180000 else 30000,
                    extraBody = extraBody,
                    // 发送键旁「思考」开关：仅在线 DeepSeek V4/V3.2 生效；本地模型由 extraBody 自行调参
                    thinkingEnabled = !localBase && session.isThinkingEnabled(),
                )
                // Agent 会话记忆：超时清理 + 注入历史消息（多轮上下文），使 AI 能理解「再来一首」等指代
                val agentSession = com.rokidlab.phone.ai.AgentSessionManager
                agentSession.maybeExpire()
                // 同时校验 AgentSessionManager 开关，关闭时本次不注入历史也不记录本轮
                val memoryEnabled = agentSession.isEnabled(session.appContext)
                val effectiveRecord = recordHistory && memoryEnabled
                // 长期记忆：跨会话记住用户事实/偏好（注入 <memories> + 注册 manage_memory 工具）
                val longTermMemory = com.rokidlab.phone.ai.LongTermMemoryManager
                val longTermOn = longTermMemory.isEnabled(session.appContext)
                // 检索式注入：按当前提问相关性取 top-K 长期记忆（无相关性时回退最近 K 条）
                val longTermContext = if (longTermOn) longTermMemory.memoriesContext(session.appContext, text) else null
                val messages = JSONArray()
                // 本地模型 → 本地轻量会话：不装配工具/技能/长期记忆工具，精简人设，仅闲聊问答。
                // 本地小模型背不动全部工具 Schema（每轮全量下发拖慢 prefill 且小模型调用工具不可靠），
                // 设备操作/联网等能力由用户切回在线 Agent 提供（对齐 RikkaHub 按会话装配思路）。
                val localLight = localBase
                // 用户自定义技能：注入技能清单（第 1 层渐进披露）+ 注册 load_skill 伪工具（仅在线 Agent）
                val skillsContext = if (!localLight && com.rokidlab.phone.ai.SkillRegistry.isEnabled(session.appContext)) {
                    com.rokidlab.phone.ai.SkillRegistry.skillsContext(session.appContext)
                } else null
                messages.put(
                    service.buildSystemMessage(
                        contextText = contextText,
                        instruction = instruction,
                        memories = longTermContext,
                        skills = skillsContext,
                        localMode = localLight,
                    ),
                )
                if (effectiveRecord) {
                    agentSession.getHistory().forEach { msg ->
                        messages.put(JSONObject().apply {
                            put("role", msg.role)
                            put("content", msg.content)
                        })
                    }
                }
                val userMsg = JSONObject()
                userMsg.put("role", "user")
                userMsg.put("content", text)
                messages.put(userMsg)

                // 可用工具随会话推进可变：主 Agent 全量域起步；命中 AIUI 生成场景后切到精简
                // AIUI 子集，每轮少发 ~14 个无关工具 Schema（省 input session.token、加快 prefill）。
                // buildTools 按域装配，并附上仅在线 Agent 的长期记忆 manage_memory 与技能
                // load_skill/load_skill_section 两个动态伪工具；切换子集时复用同一装配逻辑。
                val buildTools: (Set<String>) -> MutableList<JSONObject> = { domains ->
                    ToolRegistry.schemasFor(session.appContext, domains).toMutableList().apply {
                        if (longTermOn && !localLight) add(longTermMemory.schema())
                        if (skillsContext != null) {
                            add(com.rokidlab.phone.ai.SkillRegistry.schema())
                            add(com.rokidlab.phone.ai.SkillRegistry.sectionSchema())
                        }
                    }
                }
                // 命中该调用的回合视为进入 AIUI/代码生成会话 → 下轮起切精简工具子集（仅切一次）
                val isCodeGenCall: (String, String) -> Boolean = { name, args ->
                    name == ToolRegistry.TOOL_CODE_FILE ||
                        (name == com.rokidlab.phone.ai.SkillRegistry.TOOL_NAME && args.contains("aiui-dev"))
                }
                // 工具执行统一入口（主循环与总结兜底轮共用）：异常类失败（网络抖动/ADB 隧道
                // 瞬断等瞬时错误）自动重试一次（500ms 退避）再如实回报模型——瞬时失败直接
                // 上报会让模型过早放弃或向用户播报失败；业务性失败（"没有找到歌曲"等字符串
                // 返回值）不重试，语义已经是确定性结果。
                fun runTool(tc: com.rokidlab.phone.ai.ToolCallInfo): String {
                    // A3 修复：带副作用的工具（拨号/短信/日历/安装/打开应用等）失败不重试，
                    // 否则隧道瞬断（命令已送达、回执丢失）会触发重复拨号/重复建日程等不可逆后果。
                    val sideEffecting = com.rokidlab.phone.ai.ToolRegistry.SIDE_EFFECT_TOOLS.contains(tc.name)
                    val maxAttempts = if (sideEffecting) 1 else 2
                    var lastError: Exception? = null
                    repeat(maxAttempts) { attempt ->
                        if (attempt > 0) {
                            try {
                                Thread.sleep(500)
                            } catch (_: InterruptedException) {
                                Thread.currentThread().interrupt()
                            }
                            Log.w(TAG, "tool ${tc.name} retry after transient failure: ${lastError?.message}")
                        }
                        try {
                            return when (tc.name) {
                                com.rokidlab.phone.ai.LongTermMemoryManager.TOOL_NAME ->
                                    longTermMemory.execute(session.appContext, tc.arguments)
                                com.rokidlab.phone.ai.SkillRegistry.TOOL_NAME ->
                                    com.rokidlab.phone.ai.SkillRegistry.execute(session.appContext, tc.arguments)
                                com.rokidlab.phone.ai.SkillRegistry.TOOL_NAME_SECTION ->
                                    com.rokidlab.phone.ai.SkillRegistry.executeSection(session.appContext, tc.arguments)
                                else -> {
                                    // Phase 4 风险闸门：限流 + EXTERNAL_SIDE_EFFECT 确认
                                    // （fail-open：无确认通道/超时→降级放行；仅眼镜端显式取消才拦截，见 ToolPolicy doc）
                                    val argsObj = runCatching { org.json.JSONObject(tc.arguments) }
                                        .getOrNull() ?: org.json.JSONObject()
                                    when (
                                        val policy = com.rokidlab.phone.ai.ToolPolicy.check(
                                            com.rokidlab.phone.ai.ToolPolicy.SOURCE_CONVERSATION,
                                            tc.name,
                                            argsObj,
                                        )
                                    ) {
                                        is com.rokidlab.phone.ai.ToolPolicy.Decision.Deny ->
                                            "工具 ${tc.name} 被安全策略拦截：${policy.reason}"
                                        is com.rokidlab.phone.ai.ToolPolicy.Decision.Allow ->
                                            ToolRegistry.execute(session.appContext, tc.name, tc.arguments)
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            lastError = e
                            Log.w(TAG, "tool ${tc.name} attempt ${attempt + 1} failed: ${e.message}")
                        }
                    }
                    Log.e(TAG, "tool execute failed after retry: ${tc.name}", lastError)
                    return "工具执行失败: ${lastError?.message}"
                }
                var activeTools = buildTools(
                    if (localLight) ToolRegistry.SESSION_LOCAL_DOMAINS
                    else ToolRegistry.SESSION_AGENT_DOMAINS,
                )

                var reply = ""
                val toolTrace = mutableListOf<String>()
                // 连续空轮计数：空轮 = 既无工具调用也无正文，说明模型在"只思考不出手"。
                // 实测每轮空转 60-75s，等满 6 轮要 6 分钟以上，用户侧表现就是"一直等待回复"。
                var emptyRounds = 0
                // AIUI 生成场景标志：命中 load_skill(aiui-dev)/save_code_file 后置位，并把 tools 切到
                // 精简 AIUI 子集（SESSION_AIUI_DOMAINS），仅切换一次
                var aiuiMode = false
                // AIUI/代码生成回合收敛标记：本轮是否执行过 save_code_file，是则最终回复只允许简短结论
                var codeGenUsed = false
                // 记录各项目成功生成的源文件（project -> 文件名集合），供收敛时生成权威结论
                val genFilesByProject = LinkedHashMap<String, MutableSet<String>>()
                // 最多 6 轮工具循环：支持多步任务（先查时间再设定时等），同时防止模型反复请求工具导致死循环
                for (round in 0 until 6) {
                    // 用户打断（有更新代际的请求进入）或链路已被替换：放弃后续生成，尽快让出 aiSendLock
                    if (isSuperseded()) {
                        Log.i(TAG, "AI generation superseded at round=$round (gen=$generation, latest=$aiGenSeq), abort")
                        return@Thread
                    }
                    // 流式：实时推送 content 增量给 UI（工具调用轮 content 通常为空，最终回复轮逐字推送）；
                    // isCancelled 使 SSE 行间隙可感知打断并立即停止读取
                    val turn = service.chatTurnStream(
                        messages,
                        tools = activeTools,
                        onDelta = onDelta,
                        isCancelled = isSuperseded,
                        // 本地 Ollama 不重试：首字慢是「加载/思考中」而非抖动，重试只会重复加载翻倍等待；
                        // 远程 3 次配合指数退避（500ms→1s→2s），重放安全边界=尚无 content 推给 UI
                        retryAttempts = if (localBase) 1 else 3,
                    )
                    if (turn.toolCalls.isEmpty()) {
                        // 有正文：最终回复，收尾
                        if (!turn.content.isNullOrBlank()) {
                            reply = turn.content
                            break
                        }
                        // 空轮（既无工具也无文本）：模型在反复"思考但不落子"，实测每轮空转 60-75s，
                        // 等满 6 轮要 6 分钟以上。故首轮空即注入硬引导（原实现要求 round>=1，
                        // 等于白耗一整轮 60-75s）；若紧接一轮仍是空轮，判定本轮无法产出、立即收尾 ——
                        // 跳出后下方 fallback 总结轮会带着 tools 再要一次，回复不会丢。
                        // 若进入 AIUI 代码生成模式，引导语给出具体的分文件落盘指令。
                        emptyRounds++
                        if (emptyRounds >= 2) {
                            reply = ""
                            Log.w(TAG, "chatTurnStream empty turn round=$round, give up after $emptyRounds consecutive empty rounds")
                            break
                        }
                        val nudge = if (aiuiMode) {
                            "请立即行动，不要再空想：你已加载 aiui-dev 技能。按顺序调用 save_code_file，" +
                                "先保存 app.json（含 pages 与 window 配置），再逐文件保存页面代码（pages/index/index），" +
                                "一次只写一个文件、不要一次输出超大 JSON。全部写完后再用一两句中文总结。"
                        } else {
                            "请不要再停留在思考：如果任务需要写代码，立即调用 save_code_file 一次写一个文件；" +
                                "如果已写完或无法完成，直接用一两句中文给出最终结论。"
                        }
                        messages.put(JSONObject().apply {
                            put("role", "user")
                            put("content", nudge)
                        })
                        Log.i(TAG, "chatTurnStream empty turn round=$round, injected nudge (aiuiMode=$aiuiMode)")
                        continue
                    }
                    // 本轮有工具调用 = 模型正常出手，连续空轮计数归零
                    emptyRounds = 0
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
                        toolTrace.add("${tc.name}(${tc.arguments})")
                    }
                    assistantMsg.put("tool_calls", calls)
                    messages.put(assistantMsg)

                    // 并发执行工具：ADB 类工具在 ToolRegistry 内通过 adbLock 串行（蓝牙单连接安全），
                    // 非 ADB 工具真并发，降低多工具延迟叠加；结果按原顺序回填保证 messages 顺序稳定
                    val results = arrayOfNulls<String>(turn.toolCalls.size)
                    val latch = java.util.concurrent.CountDownLatch(turn.toolCalls.size)
                    turn.toolCalls.forEachIndexed { idx, tc ->
                        Thread {
                            // 静默工具：长期记忆维护 + load_skill 系列本地即时读取，均无用户可见进度，跳过推送
                            val silent = tc.name == com.rokidlab.phone.ai.LongTermMemoryManager.TOOL_NAME ||
                                tc.name == com.rokidlab.phone.ai.SkillRegistry.TOOL_NAME ||
                                tc.name == com.rokidlab.phone.ai.SkillRegistry.TOOL_NAME_SECTION
                            // 代码落盘工具：进度提示要带具体文件名（「正在生成 app.json…」→「app.json 生成成功」）
                            val isCodeFile = tc.name == ToolRegistry.TOOL_CODE_FILE
                            val relFile = if (isCodeFile) {
                                runCatching { JSONObject(tc.arguments).optString("file").trim() }
                                    .getOrDefault("")
                            } else ""
                            if (!silent) {
                                sendGlassesProgress(
                                    link,
                                    if (isCodeFile && relFile.isNotEmpty()) "正在生成 $relFile…"
                                    else ToolRegistry.statusText(tc.name),
                                )
                            }
                            results[idx] = runTool(tc)
                            // 落盘结果一句话回报眼镜（覆盖上面的「正在生成」，最终 TTS 总结再覆盖）
                            if (isCodeFile && relFile.isNotEmpty()) {
                                val r = results[idx]
                                sendGlassesProgress(
                                    link,
                                    if (r?.startsWith("已生成") == true) "$relFile 生成成功"
                                    else "生成 $relFile 失败，请换个说法再试",
                                )
                            }
                            latch.countDown()
                        }.start()
                    }
                    latch.await()
                    // 按原顺序回填 tool 消息
                    turn.toolCalls.forEachIndexed { idx, tc ->
                        // 收集代码落盘事实：供本回合最终回复收敛为“已生成 N 个文件…”的简短结论
                        if (tc.name == ToolRegistry.TOOL_CODE_FILE) {
                            codeGenUsed = true
                            if (results[idx]?.startsWith("已生成") == true) {
                                runCatching {
                                    val fa = JSONObject(tc.arguments)
                                    val p = fa.optString("project").trim()
                                    val f = fa.optString("file").trim()
                                    if (p.isNotEmpty() && f.isNotEmpty()) {
                                        genFilesByProject.getOrPut(p) { LinkedHashSet() }.add(f)
                                    }
                                }
                            }
                        }
                        val raw = results[idx] ?: "工具执行失败"
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
                        Log.i(TAG, "tool ${tc.name}(${tc.arguments}) -> ${result.take(100)}")
                        messages.put(JSONObject().apply {
                            put("role", "tool")
                            put("tool_call_id", tc.id)
                            put("content", result)
                        })
                    }
                    // AIUI/代码生成会话降载：本轮执行过 save_code_file 或 load_skill(aiui-dev) 后，
                    // 自下一轮起只装配精简 AIUI 子集，省去 ~14 个无关工具的 Schema 反复下发。
                    if (!aiuiMode && turn.toolCalls.any { isCodeGenCall(it.name, it.arguments) }) {
                        aiuiMode = true
                        activeTools = buildTools(ToolRegistry.SESSION_AIUI_DOMAINS)
                        Log.i(TAG, "aiuiMode on: tools switched to SESSION_AIUI_DOMAINS subset (${activeTools.size} schemas)")
                    }
                }
                // 6 轮工具用尽或某轮空返回，仍无最终回复：必须先带 tools 再请求一次强制生成总结。
                // AIUI/代码生成回合模型可能仍需调 save_code_file 等工具落盘；摘掉工具会导致它只能
                // 把源码当纯文本输出、随后被 finalizeCodeGenReply 收敛丢弃（“生成卡死/白耗”根因）。
                // 允许总结轮再执行最多 2 轮工具调用，之后若仍无文本再走固定兜底文案。
                if (reply.isBlank()) {
                    // 用户已打断或链路已失效：跳过非流式兜底请求，直接放弃
                    if (isSuperseded()) {
                        Log.i(TAG, "AI summary superseded (gen=$generation, latest=$aiGenSeq), skip final chat")
                        return@Thread
                    }
                    var finalTurn: com.rokidlab.phone.ai.ChatTurn? = null
                    for (retry in 0 until 3) {
                        finalTurn = try {
                            // 总结轮常携带大工具参数/大段代码，deepseek 单次生成可能远超默认 30s：
                            // 用 120s 单次（不重试，避免翻倍等待）保证能等到模型产出 save_code_file 调用。
                            service.chatTurn(messages, tools = activeTools, readTimeout = 120_000, attempts = 1)
                        } catch (_: Exception) {
                            null
                        }
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
                            toolTrace.add("${tc.name}(${tc.arguments})")
                        }
                        assistantMsg.put("tool_calls", calls)
                        messages.put(assistantMsg)
                        // 同步执行本轮工具调用并回填结果（总结轮工具极少，无需并发）
                        finalTurn.toolCalls.forEach { tc ->
                            val out = runTool(tc)
                            if (tc.name == ToolRegistry.TOOL_CODE_FILE) {
                                codeGenUsed = true
                                if (out.startsWith("已生成")) {
                                    runCatching {
                                        val fa = JSONObject(tc.arguments)
                                        val p = fa.optString("project").trim()
                                        val f = fa.optString("file").trim()
                                        if (p.isNotEmpty() && f.isNotEmpty()) {
                                            genFilesByProject.getOrPut(p) { LinkedHashSet() }.add(f)
                                        }
                                    }
                                }
                            }
                            messages.put(JSONObject().apply {
                                put("role", "tool")
                                put("tool_call_id", tc.id)
                                put("content", out)
                            })
                        }
                        // 兜底总结轮同样支持进入 AIUI 精简工具子集（省 session.token），下一轮重试即生效
                        if (!aiuiMode && finalTurn.toolCalls.any { isCodeGenCall(it.name, it.arguments) }) {
                            aiuiMode = true
                            activeTools = buildTools(ToolRegistry.SESSION_AIUI_DOMAINS)
                            Log.i(TAG, "aiuiMode on (final chat): tools switched to SESSION_AIUI_DOMAINS (${activeTools.size})")
                        }
                    }
                    reply = finalTurn?.content?.takeIf { it.isNotBlank() }
                        ?: "抱歉，我暂时无法处理这个问题，请换个说法再试一次。"
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
                    } else null
                    reply = com.rokidlab.phone.ai.finalizeCodeGenReply(reply, authoritative)
                }
                replyRef.set(reply)
                // 记录本轮到会话记忆（含工具轨迹，catch 分支的失败兜底回复不记录，避免污染上下文）
                if (effectiveRecord) {
                    agentSession.recordTurn(text, reply, toolTrace)
                }
                Log.i(TAG, "AI reply generated in ${System.currentTimeMillis() - tGenStart}ms: ${reply.take(80)}...")
            } catch (e: Exception) {
                // 用户打断（代际被抢占）或链路已被替换导致的终止（chatTurnStream 被取消 / 轮间 return
                // 前的异常）：静默退出，不覆盖 replyRef（保持空），也避免误播"服务不可用"
                if (isSuperseded()) {
                    Log.i(TAG, "AI generation aborted (superseded, gen=$generation): ${e.message}")
                    return@Thread
                }
                Log.e(TAG, "DeepSeek API failed", e)
                replyRef.set("抱歉，AI 服务暂时不可用。")
            }
        }.apply { start() }

        // 步骤-1 在 AI 线程启动后执行（原位置在 thread 启动前）：打断等待 300ms 与 AI 请求并行
        runOfficialInterrupt()

        // ===== 步骤0+1+2: 开启会话 + 显示提问 + 结束识别 =====
        // localTakeover：眼镜端已本地完成（KeyButtonService ASR_End 后立即 open + 显示提问），
        // 下行无需重发，避免官方界面残留"思考中"等待手机端轮询（约 3s 空白）。
        var keyDownResult: Int? = 0
        var openResult: Int? = 0
        if (!localTakeover) {
            // 0a. 发送 KeyDown_Client（privacy_level=2）：眼镜端 AIPhoneOpenHandler 在 AI 未运行时
            //     调用 openAiAssistant() -> openSceneWithIgnoreTips("ai_assist")，真正设置 aiIsRunning=true，
            //     这是 ASR_Result / TTS_Result 能显示文字的前置条件
            val keyDownCaps = Caps()
            keyDownCaps.write("KeyDown_Client")
            keyDownCaps.write("{\"privacy_level\":2}")
            synchronized(session.aiCmdLock) {
                if (!abortAiSendIfLinkInvalid(link, onResult)) return
                keyDownResult = link.sendCustomCmd(LinkProtocol.CXR_CHANNEL_AI, keyDownCaps)
            }
            Log.i(TAG, "sendCustomCmd(Ai, KeyDown_Client, privacy_level=2) -> $keyDownResult")
            Thread.sleep(600)

            // 0b. 发送 Ai + open：眼镜端 AIOpenHandler 调用 startNewTalk()，开启 AI 对话
            val openCaps = Caps()
            openCaps.write("open")
            synchronized(session.aiCmdLock) {
                if (!abortAiSendIfLinkInvalid(link, onResult)) return
                openResult = link.sendCustomCmd(LinkProtocol.CXR_CHANNEL_AI, openCaps)
            }
            Log.i(TAG, "sendCustomCmd(Ai, open) -> $openResult")
            Thread.sleep(400)

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
        deepSeekThread.join()
        reply = replyRef.get()
        // 生成期间用户已发起新请求（新语音/新消息）或本次请求的 link 已被替换/断开：
        // 本回复已过期（可能为空串，由 isSuperseded 触发的提前退出所致），
        // 放弃显示/播报/回调，复位状态并把链路让给新请求（join 期间 deepSeekThread 已提前退出，等待有界）
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
            // join 等待 DeepSeek 期间可能发生 session.cleanup 断开/替换 link，发送前重新校验
            if (!abortAiSendIfLinkInvalid(link, onResult)) return
            // 官方协议: caps[0] = "TTS_Result", caps[1] = 回复文字
            val ttsCaps = Caps()
            ttsCaps.write("TTS_Result")
            ttsCaps.write(reply)
            val ttsResult = link.sendCustomCmd(LinkProtocol.CXR_CHANNEL_AI, ttsCaps)
            Log.i(TAG, "sendCustomCmd(Ai, TTS_Result, \"${reply.take(40)}...\") -> $ttsResult")

            // 与 tts_play 之间加 300ms 间隔，降低链路抖动时两条指令一起丢失的概率
            Thread.sleep(300)

            // ===== 步骤4.5: 发送 TTS_Play（触发 RokidLink 眼镜本地语音播放）=====
            // RokidLink 通过 CXRServiceBridge.subscribe("tts_play") 收到后，
            // 调用系统 TtsService 本地合成并播放语音
            fun sendTtsPlay(): Int? {
                if (!abortAiSendIfLinkInvalid(link, onResult)) return -99
                val ttsPlayCaps = Caps()
                AiChannel.encodeTtsPlay(reply).forEach { ttsPlayCaps.write(it) }
                return link.sendCustomCmd(AiChannel.TOPIC_TTS_PLAY, ttsPlayCaps)
            }
            var ttsPlayResult: Int? = sendTtsPlay()
            Log.i(TAG, "sendCustomCmd(tts_play, \"${reply.take(40)}...\") -> $ttsPlayResult")
            // 发送失败（返回值 != 0）时延迟 500ms 重发一次，降低偶发丢包
            if (ttsPlayResult != 0) {
                Thread.sleep(500)
                ttsPlayResult = sendTtsPlay()
                Log.w(TAG, "retry sendCustomCmd(tts_play) -> $ttsPlayResult")
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
     * 实现 = bump 代际号：deepSeekThread 在每轮工具循环/下行检查点比较
     * generation != aiGenSeq 后自弃；SSE 流式读取在 isCancelled 回调处中断。
     * 局限：若模型阻塞在 readLine 等待网络，最坏延迟一个 readTimeout 才退出，
     * 但不会再发送任何下行（各下行点均有 abortAiSendIfLinkInvalid/代际校验）。
     */
    fun abortCurrentAi() {
        val seq = bumpAiGen()
        Log.i(TAG, "abortCurrentAi: bumped aiGenSeq -> $seq (in-flight AI request will self-abort)")
        // 同步通知眼镜端停止正在播放的语音（tts_stop 下行通道）
        session.stopTtsOnGlass()
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
}
