package com.rokidlab.phone.domain

import android.util.Log
import com.rokid.cxr.Caps
import com.rokid.cxr.link.CXRLink
import com.rokidlab.phone.ai.AgentStep
import com.rokidlab.phone.ai.LongTermMemoryManager
import com.rokidlab.phone.ai.ToolRegistry
import com.rokidlab.phone.design.RokidHostApp
import com.rokidlab.phone.glasses.AiChannel
import com.rokidlab.phone.glasses.LinkProtocol
import com.rokidlab.phone.util.namedThread
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
    private companion object {
        const val TAG = "AiConversationService"

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
         * 兜底总结轮的最大轮数（主循环工具轮预算用尽后的收尾阶段）。
         * 每轮都会注入「禁止再调工具、直接给结论」的引导语，并把当轮 tool_calls 照常执行 ——
         * 多文件生成时模型可能仍需连续几轮 save_code_file 落盘，因此"当轮没有正文"属正常，
         * 不能提前中断（否则会截断正常的多次落盘）。
         */
        const val SUMMARY_MAX_ROUNDS = 3

        /**
         * 工具循环的「动作轮」预算：含至少一个非只读工具（落盘 / 装机 / 拨号等）的轮次。
         * 分账原因见 [MAX_READONLY_ROUNDS]；本值保持原 6 轮不变。
         */
        const val MAX_ACTION_ROUNDS = 6

        /**
         * 工具循环的「只读轮」预算：整轮只调用只读工具（查资料）。
         *
         * **为什么与动作轮分账**：AIUI 任务天然「读多写少」——实测一次生成里 6 轮工具全花在
         * `list_my_aiui_apps` / `read_code_file` / `load_skill_section` 上，勘察把统一预算吃光，
         * 模型还没开始落盘就被判定该收尾，最终只能回固定兜底文案。只读轮本身很轻（单轮 2s 量级），
         * 给它单独额度比整体调大轮次更对症，也不会让「动作轮」被顺带放长。
         */
        const val MAX_READONLY_ROUNDS = 8

        /**
         * 工具循环的轮次硬顶（兜底）。正常应由两个分账预算之一先触发；
         * 此值取两者之和，仅用于防御计数器异常导致的死循环。
         */
        const val MAX_TOTAL_ROUNDS = 14

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
     * 直接复用 [com.rokidlab.phone.ai.ToolRiskMap] 的登记，另补两个 `riskOf` 判不出来的名字：
     * - `load_skill` / `load_skill_section` 是伪工具，不在 `ToolRegistry.toolList` 中，
     *   `riskOf` 会把它们当「完全未知的名字」判成 EXTERNAL_SIDE_EFFECT（会被算成动作轮）；
     * - `long_term_memory` 会写长期记忆库（有副作用），因此**不**算只读，保持动作轮计费。
     */
    private fun isReadOnlyTool(name: String): Boolean = when (name) {
        com.rokidlab.phone.ai.SkillRegistry.TOOL_NAME,
        com.rokidlab.phone.ai.SkillRegistry.TOOL_NAME_SECTION,
        // update_plan 只是把计划文本格式化回填，无任何副作用
        com.rokidlab.phone.ai.AgentPlan.TOOL_NAME,
        -> true
        else -> runCatching {
            com.rokidlab.phone.ai.ToolRiskMap.riskOf(name) == com.rokidlab.phone.ai.ToolRisk.READ_ONLY
        }.getOrDefault(false)
    }

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
                    com.rokidlab.phone.ai.SkillRegistry.schema(),
                    com.rokidlab.phone.ai.SkillRegistry.sectionSchema(),
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
         * **只读模式**：本轮只装配 [com.rokidlab.phone.ai.ToolRisk.READ_ONLY] 档的工具，
         * 并禁用 AIUI 精简子集切换（那会引入 save_code_file 等写操作）。
         *
         * 用于**无人值守**场景（定时触发的自主任务）。无人监管时模型跑错一次
         * （半夜拨号/装机/改设置）代价远高于「少做一点」，因此物理上不给它副作用工具。
         */
        readOnlyTools: Boolean = false,
    ) {
        Log.i(TAG, "sendAiTextMessage(\"$text\") called. session.cxrlConnected=${session.cxrlConnected}, session.glassBtConnected=${session.glassBtConnected}, session.cxrLink=${session.cxrLink != null}, session.token=${session.token?.take(8) ?: "null"}, readOnlyTools=$readOnlyTools")

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
                sendAiTextViaLink(null, text, settledResult, onReply, contextText, interruptOfficialFirst, skipTtsAudioFinished, showAsrResult, localTakeover, instruction, recordHistory, onDelta, myGen, readOnlyTools, trace)
            }
            return
        }

        // 快速路径: 如果 CXR 已连接且 link 可用，直接发送（跳过前置检查 + 重新 connect）
        val link = session.cxrLink
        if (session.cxrlConnected && session.glassBtConnected && link != null) {
            Log.i(TAG, "sendAiTextMessage: using existing CXRLink (fast path)")
            sendAiTextViaLink(link, text, settledResult, onReply, contextText, interruptOfficialFirst, skipTtsAudioFinished, showAsrResult, localTakeover, instruction, recordHistory, onDelta, myGen, readOnlyTools, trace)
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
                sendAiTextViaLink(null, text, settledResult, onReply, contextText, interruptOfficialFirst, skipTtsAudioFinished, showAsrResult, localTakeover, instruction, recordHistory, onDelta, myGen, readOnlyTools, trace)
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
                    // sendAiTextViaLink 内含多次 Thread.sleep + deepSeekThread.join（最长可阻塞 30s），
                    // 必须切后台线程执行，否则慢速路径阻塞主线程导致 ANR/闪退。
                    // session.onStatus/session.onBusyChanged 已线程安全，onReply 由调用方切主线程，onResult 内部 runOnUiThread。
                    session.appScope.launch(Dispatchers.IO) {
                        sendAiTextViaLink(l, text, settledResult, onReply, contextText, interruptOfficialFirst, skipTtsAudioFinished, showAsrResult, localTakeover, instruction, recordHistory, onDelta, myGen, readOnlyTools, trace)
                    }
                },
                onFailure = {
                    // 连接失败不再直接报错 —— 退到本机模式照样把问题回答给用户。
                    // 旧行为是 settledResult(false, "connection failed")，用户侧表现为
                    // 「眼镜不在/连不上 → 聊天完全不能用」，而模型其实完全有能力离线回答。
                    Log.w(TAG, "sendAiTextMessage: glasses connect failed -> fall back to local-only")
                    session.cleanup()
                    session.appScope.launch(Dispatchers.IO) {
                        sendAiTextViaLink(null, text, settledResult, onReply, contextText, interruptOfficialFirst, skipTtsAudioFinished, showAsrResult, localTakeover, instruction, recordHistory, onDelta, myGen, readOnlyTools, trace)
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
                // ⚠️ 也不要改成 false：实测补发 KeyDown/open 会让 Lab 回复只出声不上屏（已回滚）。
                localTakeover = true,
            )
        } catch (e: Exception) {
            Log.e(TAG, "handleGlassesAiAsrText error", e)
            session.asrBridge.markAsrHandling(false)
            // 抛错路径不会走到 sendAiTextMessage 的结果包装，这里单独兜底「过程」收尾
            traceFinishSink?.invoke(true)
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
        val deepSeekThread = namedThread("ai-deepseek-request", start = true) {
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
                localLightMode = localLight
                // 用户自定义技能：注入技能清单（第 1 层渐进披露）+ 注册 load_skill 伪工具（仅在线 Agent）
                val skillsContext = if (!localLight && com.rokidlab.phone.ai.SkillRegistry.isEnabled(session.appContext)) {
                    com.rokidlab.phone.ai.SkillRegistry.skillsContext(session.appContext)
                } else null
                // 自动 RAG（Agent 缺口 #3）：知识库有文档时，按当前提问自动检索 top-2 命中注入
                // system 提示词（带文档名/块序号来源），不再依赖模型自觉调用 search_knowledge_base
                // —— 词法检索对「换个说法问」的召回缺口仍由模型按需补调工具（工具口保留，双通道）。
                // 限制：仅在线 Agent、提问长度合理时才检索，避免短感叹词/超长粘贴无意义扫库。
                val kb = com.rokidlab.phone.ai.KnowledgeBase
                val kbContext = if (!localLight && text.length in 4..500 &&
                    runCatching { kb.docCount(session.appContext) }.getOrDefault(0) > 0
                ) {
                    runCatching {
                        kb.searchHits(session.appContext, text, 2)
                            .joinToString("\n") { "《${it.docName}》第${it.chunkIdx + 1}块：${it.text}" }
                            .takeIf { it.isNotBlank() }
                    }.getOrNull()
                } else null
                // 与调用方已带的 contextText（如拍照答题资料）合并，共用「知识库参考资料」槽位
                val mergedContext = listOfNotNull(kbContext, contextText)
                    .filter { it.isNotBlank() }
                    .joinToString("\n\n")
                    .ifBlank { null }
                // 预算自感知（Agent 缺口「自我认知」）：把本轮轮次预算如实写进提示词，
                // 让模型有能力规划"把额度花在哪" —— 旧行为是触顶后静默截断，
                // 模型和用户都不知道为什么任务只做了一半。
                val budgetNote = if (localLight) null else {
                    "【本次回答的运行预算】最多进行 $MAX_TOTAL_ROUNDS 轮工具调用，其中只读类（查询/检索/读取）" +
                        "累计不超过 $MAX_READONLY_ROUNDS 轮、含动作类（落盘/装机/拨号/发消息等）不超过 $MAX_ACTION_ROUNDS 轮。" +
                        "接近上限时请优先完成关键步骤并给出阶段性结论；若确实做不完，请如实说明还剩哪些步骤没做，" +
                        "并提示用户说「继续」以便下次接着完成。"
                }
                // 长任务续做（Agent 缺口「长任务可恢复」）：TTL 内未完成任务的流程级状态
                // （原话 / 计划进度 / 已产出文件 / 上次中断原因）注入提示词 —— 用户说「继续」时
                // 模型能接着做而不是从头重来；已完成或过期任务不会注入。
                val taskNote = if (localLight) null else {
                    com.rokidlab.phone.ai.AgentTaskStore.pendingContext(session.appContext)
                }
                messages.put(
                    service.buildSystemMessage(
                        contextText = mergedContext,
                        instruction = instruction,
                        memories = longTermContext,
                        skills = skillsContext,
                        budget = listOfNotNull(taskNote, budgetNote).joinToString("\n\n").ifBlank { null },
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
                    val built = if (readOnlyTools) {
                        // 无人值守（定时自主任务，见 readOnlyTools 说明）：只装配只读工具。
                        // 连伪工具也不挂 —— manage_memory 会写长期记忆库、load_skill 可能把模型
                        // 引向代码生成（写出文件），update_plan 的进度也无人看，全部无意义。
                        ToolRegistry.schemasReadOnly(session.appContext, excludeGlassesTools = phoneOnly).toMutableList()
                    } else {
                        // 本机模式（无眼镜）：把「需要眼镜的工具」从 Schema 里摘掉 ——
                        // 模型看不到就不会去调，避免每轮白等一次注定失败的调用与 15s 超时。
                        ToolRegistry.schemasFor(session.appContext, domains, excludeGlassesTools = phoneOnly).toMutableList().apply {
                            if (longTermOn && !localLight) add(longTermMemory.schema())
                            // 任务计划伪工具（Agent 缺口 #1）：所有域子集都带，多步任务显式规划
                            if (!localLight) add(com.rokidlab.phone.ai.AgentPlan.schema())
                            if (skillsContext != null) {
                                add(com.rokidlab.phone.ai.SkillRegistry.schema())
                                add(com.rokidlab.phone.ai.SkillRegistry.sectionSchema())
                            }
                        }
                    }
                    // 本地严格校验一遍（每进程一次）：服务端对 schema 是严格校验，
                    // 一个节点形状写错会让整个请求 400、所有工具一起失效
                    auditToolSchemas(built)
                    built
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
                                com.rokidlab.phone.ai.AgentPlan.TOOL_NAME ->
                                    com.rokidlab.phone.ai.AgentPlan.execute(tc.arguments)
                                else -> {
                                    // 参数 JSON 非法（非空白却解析失败）：多半是模型单次输出被 max_tokens
                                    // 截断、工具参数在半途断开（少数是模型格式瑕疵）。旧实现沿用 ToolRegistry.execute
                                    // 的「非法即兜底成空对象」策略，下游只会回「保存失败：项目名不能为空」
                                    // 这类误导性错误 —— 模型看不出真实原因，往往原样重试同样大的内容，
                                    // 白耗轮次。这里把真实原因直接告诉它。
                                    // 空白参数仍按空对象处理（国产/本地模型对无参工具常返回 ""）。
                                    val argsObj = if (tc.arguments.isBlank()) {
                                        org.json.JSONObject()
                                    } else {
                                        runCatching { org.json.JSONObject(tc.arguments) }.getOrNull()
                                    }
                                    if (argsObj == null) {
                                        "工具 ${tc.name} 的参数不是合法 JSON（多半是内容太长被输出长度" +
                                            "截断，也可能是格式有误）。请修正后重发，不要原样重试：内容过长就拆小" +
                                            "——一次只写一个文件、单个文件不超过 120 行、多个文件分多次调用。"
                                    } else when (
                                        val policy = com.rokidlab.phone.ai.ToolPolicy.check(
                                            com.rokidlab.phone.ai.ToolPolicy.SOURCE_CONVERSATION,
                                            tc.name,
                                            argsObj,
                                        )
                                    ) {
                                        is com.rokidlab.phone.ai.ToolPolicy.Decision.Deny ->
                                            "工具 ${tc.name} 被安全策略拦截：${policy.reason}。" +
                                                "请换用其他合适的方式完成任务，或如实告知用户。"
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
                    // 反思引导（Agent 缺口 #4）：瞬时重试耗尽仍失败，不能只回干巴巴的错误串 ——
                    // 明确要求模型分析原因并换策略（改参数/换工具/拆任务），禁止原样重试同一调用。
                    return "工具执行失败: ${lastError?.message}。请先分析失败原因再决定下一步：" +
                        "参数是否正确？是否换用其他工具或参数？是否把任务拆小？" +
                        "不要原样重复刚才的调用；若换一种方式仍无法完成，请如实告知用户。"
                }
                var activeTools = buildTools(
                    if (localLight) ToolRegistry.SESSION_LOCAL_DOMAINS
                    else ToolRegistry.SESSION_AGENT_DOMAINS,
                )

                var reply = ""
                val toolTrace = mutableListOf<String>()
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
                // 轮次预算分账（详见 MAX_READONLY_ROUNDS 注释）：只读轮（查资料）与动作轮（落盘/装机等）
                // 各自独立计数，避免 AIUI 任务的勘察把落盘额度吃光。混合轮按动作轮计费。
                var readOnlyRounds = 0
                var actionRounds = 0
                // 轮次预算是否已触顶（触顶后收尾引导要如实告知"预算用完"，而不是笼统收尾）
                var budgetExhausted = false
                // 工具循环：支持多步任务（先查时间再设定时等），同时防止模型反复请求工具导致死循环
                for (round in 0 until MAX_TOTAL_ROUNDS) {
                    // 用户打断（有更新代际的请求进入）或链路已被替换：放弃后续生成，尽快让出 aiSendLock
                    if (isSuperseded()) {
                        Log.i(TAG, "AI generation superseded at round=$round (gen=$generation, latest=$aiGenSeq), abort")
                        // 被打断的长任务标为可续做：下轮用户说「继续」即可接着做
                        if (!localLight) {
                            com.rokidlab.phone.ai.AgentTaskStore.markStatus(
                                session.appContext,
                                com.rokidlab.phone.ai.AgentTaskStore.STATUS_INTERRUPTED,
                                "被新消息打断（可续做）",
                            )
                        }
                        return@namedThread
                    }
                    // 流式：实时推送 content 增量给 UI（工具调用轮 content 通常为空，最终回复轮逐字推送）；
                    // isCancelled 使 SSE 行间隙可感知打断并立即停止读取
                    //
                    // Agent 过程（手机端「过程」时间线）：先发一条「思考中…」。
                    // 工具调用轮 content 为空，若不发这条，用户从点发送到最终回复之间只看到空白，
                    // 无法判断 Agent 是在思考、在等网络、还是已经卡死（这正是"看不到调用工具"的根因）。
                    onTrace?.invoke(AgentStep.thinking(round))
                    // 思考增量（仅开启长思考时有数据）：节流后再推 UI —— 推理可能上万个 delta，
                    // 每个都写一次 SnapshotStateList 会让主线程持续重组。
                    val reasoningBuf = StringBuilder()
                    var lastThinkEmitAt = 0L
                    val turn = service.chatTurnStream(
                        messages,
                        tools = activeTools,
                        onDelta = onDelta,
                        isCancelled = isSuperseded,
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
                    )
                    // 本轮思考收束：有推理内容就把预览落到过程卡片上（同一个 key 覆盖）
                    onTrace?.invoke(
                        AgentStep.thinking(round, reasoningBuf.toString(), AgentStep.State.OK),
                    )
                    truncatedLastRound = turn.finishReason == "length"
                    if (turn.toolCalls.isEmpty()) {
                        // 有正文：最终回复，收尾
                        if (!turn.content.isNullOrBlank()) {
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
                        namedThread("ai-turn-worker", start = true) {
                            // 静默工具：长期记忆维护 + load_skill 系列本地即时读取，均无用户可见进度，跳过推送；
                            // update_plan 无专属 statusText（避免显示"正在执行 update_plan…"这种黑话），
                            // 同样静默执行，完成后自行推送格式化计划文本
                            val silent = tc.name == com.rokidlab.phone.ai.LongTermMemoryManager.TOOL_NAME ||
                                tc.name == com.rokidlab.phone.ai.SkillRegistry.TOOL_NAME ||
                                tc.name == com.rokidlab.phone.ai.SkillRegistry.TOOL_NAME_SECTION ||
                                tc.name == com.rokidlab.phone.ai.AgentPlan.TOOL_NAME
                            // 代码落盘工具：进度提示要带具体文件名（「正在生成 app.json…」→「app.json 生成成功」）
                            val isCodeFile = tc.name == ToolRegistry.TOOL_CODE_FILE
                            val relFile = if (isCodeFile) {
                                runCatching { JSONObject(tc.arguments).optString("file").trim() }
                                    .getOrDefault("")
                            } else ""
                            if (!silent) {
                                // 本机模式（link == null）没有眼镜可推，进度只进手机端过程时间线
                                link?.let {
                                    sendGlassesProgress(
                                        it,
                                        if (isCodeFile && relFile.isNotEmpty()) "正在生成 $relFile…"
                                        else ToolRegistry.statusText(tc.name),
                                    )
                                }
                            }
                            // Agent 过程：把这一步工具调用呈现到手机端「过程」时间线。
                            // 只屏蔽长期记忆维护（纯内部记账，用户既看不懂也不需要看）；
                            // load_skill / update_plan 等虽然对眼镜静默，但用户明确想知道
                            // "Agent 到底做了什么"，因此照常显示。
                            val traceVisible = tc.name != LongTermMemoryManager.TOOL_NAME
                            if (traceVisible) {
                                onTrace?.invoke(
                                    AgentStep.tool(
                                        tc.id, tc.name, AgentStep.State.RUNNING, argsRaw = tc.arguments,
                                    ),
                                )
                            }
                            results[idx] = runTool(tc)
                            if (traceVisible) {
                                onTrace?.invoke(
                                    AgentStep.tool(
                                        tc.id,
                                        tc.name,
                                        if (AgentStep.isFailureResult(results[idx])) AgentStep.State.FAILED
                                        else AgentStep.State.OK,
                                        argsRaw = tc.arguments,
                                        result = results[idx],
                                    ),
                                )
                            }
                            // 计划更新（Agent 缺口 #1）：把最新计划文本作为进度显示推到眼镜，
                            // 用户随时能看到「做到第几步」，替代多轮工具执行期间的黑盒等待
                            if (tc.name == com.rokidlab.phone.ai.AgentPlan.TOOL_NAME) {
                                link?.let { sendGlassesProgress(it, results[idx] ?: "") }
                                // 流程级 checkpoint（缺口「长任务可恢复」）：计划一落盘，
                                // 即便本 turn 随后被预算截断/链路中断，下轮也能接着做
                                com.rokidlab.phone.ai.AgentTaskStore.recordPlan(
                                    session.appContext, goal = text, arguments = tc.arguments,
                                )
                            }
                            // 落盘结果一句话回报眼镜（覆盖上面的「正在生成」，最终 TTS 总结再覆盖）
                            if (isCodeFile && relFile.isNotEmpty()) {
                                val r = results[idx]
                                link?.let {
                                    sendGlassesProgress(
                                        it,
                                        if (r?.startsWith("已生成") == true) "$relFile 生成成功"
                                        else "生成 $relFile 失败，请换个说法再试",
                                    )
                                }
                            }
                            latch.countDown()
                        }
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
                                        // 产物级 checkpoint：成功落盘的文件进任务状态，
                                        // 续做时模型知道哪些文件已有、不必重新生成
                                        com.rokidlab.phone.ai.AgentTaskStore.recordArtifact(
                                            session.appContext, goal = text, project = p, file = f,
                                        )
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
                    if (!readOnlyTools && !aiuiMode && turn.toolCalls.any { isCodeGenCall(it.name, it.arguments) }) {
                        aiuiMode = true
                        activeTools = buildTools(ToolRegistry.SESSION_AIUI_DOMAINS)
                        Log.i(TAG, "aiuiMode on: tools switched to SESSION_AIUI_DOMAINS subset (${activeTools.size} schemas)")
                    }
                    // 分账结算：整轮都是只读工具才扣只读额度，否则（含混合轮）按动作轮计费
                    if (turn.toolCalls.all { isReadOnlyTool(it.name) }) readOnlyRounds++ else actionRounds++
                    if (actionRounds >= MAX_ACTION_ROUNDS || readOnlyRounds >= MAX_READONLY_ROUNDS) {
                        Log.i(
                            TAG,
                            "tool loop budget exhausted at round=$round " +
                                "(action=$actionRounds/$MAX_ACTION_ROUNDS, readonly=$readOnlyRounds/$MAX_READONLY_ROUNDS)",
                        )
                        budgetExhausted = true
                        // 立即落一条终态：即使随后进程被杀/链路断，下轮也知道「上次是预算用尽」
                        if (!localLight) {
                            com.rokidlab.phone.ai.AgentTaskStore.markStatus(
                                session.appContext,
                                com.rokidlab.phone.ai.AgentTaskStore.STATUS_BUDGET_EXHAUSTED,
                                "工具轮次预算用尽（可续做）",
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
                        if (!localLight) {
                            com.rokidlab.phone.ai.AgentTaskStore.markStatus(
                                session.appContext,
                                com.rokidlab.phone.ai.AgentTaskStore.STATUS_INTERRUPTED,
                                "收尾阶段被新消息打断（可续做）",
                            )
                        }
                        return@namedThread
                    }
                    var finalTurn: com.rokidlab.phone.ai.ChatTurn? = null
                    // 总结轮里模型给出的正文。不能用「最后一轮的 content」代替 —— 末轮若只调了工具，
                    // content 为空，而前一轮的结论句其实是有效的（旧实现会连它一起丢掉）。
                    var summaryText: String? = null
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
                        // 先留下正文（模型可能一边调工具一边给结论），再判断是否继续
                        finalTurn?.content?.takeIf { it.isNotBlank() }?.let { summaryText = it }
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
                        if (!readOnlyTools && !aiuiMode && finalTurn.toolCalls.any { isCodeGenCall(it.name, it.arguments) }) {
                            aiuiMode = true
                            activeTools = buildTools(ToolRegistry.SESSION_AIUI_DOMAINS)
                            Log.i(TAG, "aiuiMode on (final chat): tools switched to SESSION_AIUI_DOMAINS (${activeTools.size})")
                        }
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
                }
                // 走到这里仍无正文 = 模型既没给结论、也没落盘过文件（codeGenUsed=false 时
                // finalizeCodeGenReply 不会兜底）：只有这种情况才用固定兜底文案。
                if (reply.isBlank()) {
                    reply = "抱歉，我暂时无法处理这个问题，请换个说法再试一次。"
                }
                // 剥离模型偶发输出的包裹标签（如 `<answer>…</answer>`）。
                // 放在「落 replyRef 之前」＝显示、语音播报、会话记忆三处统一拿到干净文本。
                // 真机事故（2026-09-17 15:11）：眼镜上直接显示 `<answer>西安明天晴，最高31度…`。
                reply = com.rokidlab.phone.ai.ReplySanitizer.sanitize(reply)
                replyRef.set(reply)
                // 记录本轮到会话记忆（含工具轨迹，catch 分支的失败兜底回复不记录，避免污染上下文）
                if (effectiveRecord) {
                    agentSession.recordTurn(text, reply, toolTrace)
                }
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
                    if (!localLightMode) {
                        com.rokidlab.phone.ai.AgentTaskStore.markStatus(
                            session.appContext,
                            com.rokidlab.phone.ai.AgentTaskStore.STATUS_INTERRUPTED,
                            "被新消息打断（可续做）",
                        )
                    }
                    return@namedThread
                }
                Log.e(TAG, "DeepSeek API failed", e)
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
            shieldCaps.write("正在思考…")
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
        var openResult: Int? = 0
        if (link == null) {
            // 本机模式：没有眼镜可下发显示，整段跳过（原 5 条下行全部不做）
            Log.i(TAG, "local-only: skip KeyDown/open/ASR_Result/ASR_End downlink (no glasses)")
            session.onStatus("正在获取 AI 回复…（本机模式，未连接眼镜）")
        } else if (!localTakeover) {
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
        // 兜底再清洗一次（生成线程里已清过一次；失败兜底文案等其他赋值路径也覆盖到）。
        // 幂等，成本可忽略。
        reply = com.rokidlab.phone.ai.ReplySanitizer.sanitize(replyRef.get())
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
            // 本机模式：回复已在上方经 onReply 交给聊天窗口，这里没有眼镜可下发。
            // 必须显式补上「收尾」（busy 复位 + onResult 成功）：否则调用方会一直停在 busy，
            // 表现为发送按钮转圈不停/后续消息被当成并发而拒绝。
            if (link == null) {
                Log.i(TAG, "local-only: skip TTS_Result/tts_play downlink, reply delivered to chat only")
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
            // 官方协议: caps[0] = "TTS_Result", caps[1] = 回复文字，
            // caps[2] = Lab 来源标记（眼镜端据此区分 Lab 回复与官方回声，见 LinkProtocol.AI_REPLY_MARK）。
            // 官方只按索引读前两个元素，多写一个会被忽略，向后兼容。
            val ttsCaps = Caps()
            ttsCaps.write("TTS_Result")
            ttsCaps.write(reply)
            ttsCaps.write(LinkProtocol.AI_REPLY_MARK)
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
        // 收尾「过程」：被打断的请求不会再有第二段（OK/FAILED）事件，不兜底的话
        // 聊天窗口里会永远挂着一个转圈的「进行中」步骤，看起来像卡死
        traceFinishSink?.invoke(true)
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
}
