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
         * **自动续做**次数上限（分账额度触顶后自动再开一段，无需用户说「继续」）。
         *
         * 为什么需要：分账额度（动作 6 / 只读 8）触顶 ≠ 任务做完，而总轮次 [MAX_TOTAL_ROUNDS]
         * 通常还有余量 —— 旧实现在触顶处直接跳出工具循环，把剩下的总轮次白扔，然后让模型
         * 在结论里"提示用户说继续"。可用户要的是"你把活干完"，不是"你告诉我该催你一下"。
         *
         * 为什么只给 1 次：每次续做都是**又一段真实的模型+工具开销**，且眼镜端在这期间是静默的
         * （用户只看到"过程"在走）。给 1 次已经在"把活干完"与"别一声不吭烧几分钟"之间取到平衡；
         * 真正无界的长任务仍走 AgentTaskStore 的多轮续做（用户说「继续」时接着做）。
         */
        const val MAX_AUTO_CONTINUES = 1

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
                    // sendAiTextViaLink 内含多次 Thread.sleep + deepSeekThread.join（最长可阻塞 30s），
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
            // 本轮的事件流记录句柄（null = 不记录：拍照答题 recordHistory=false / 记忆开关关闭 /
            // 会话尚未绑定）。提到 try 外是为了让 catch 分支也能收尾本轮 —— 否则被打断/失败的轮
            // 会永远停在只有 TurnStart 的半轮状态。
            var agentTurn: com.rokidlab.phone.ai.session.AgentTurn? = null
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
                val llmOptions = com.rokidlab.phone.ai.llm.LlmRegistry.Options(
                    thinkingEnabled = session.isThinkingEnabled(),
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
                val kbContext = if (kbDocs.isNotEmpty()) {
                    val digest = kbHits
                        .joinToString("\n") { "《${it.docName}》第${it.chunkIdx + 1}块：${it.text}" }
                        .takeIf { it.isNotBlank() }
                    digest ?: (
                        "（按当前提问没有自动检索到相关段落）本地知识库中已有 ${kbDocs.size} 份文档：" +
                            kbDocs.take(12).joinToString("、") { "《${it.name}》" } +
                            "。若与本问题相关，请调用 search_knowledge_base 用文档里更可能出现的关键词检索后再回答；" +
                            "若无关则忽略本条。"
                    )
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
                        "累计不超过 $MAX_READONLY_ROUNDS 轮、含动作类（落盘/装机/拨号/发消息等）不超过 $MAX_ACTION_ROUNDS 轮；" +
                        "触顶后平台会**自动追加一段额度**（$MAX_AUTO_CONTINUES 次），所以你不需要为了省额度而跳过必要步骤，" +
                        "更不要在中途停下来让用户说「继续」。接近上限时请优先完成关键步骤并给出阶段性结论；" +
                        "若确实做不完，请如实说明还剩哪些步骤没做，并提示用户说「继续」以便下次接着完成。"
                }
                // 长任务续做（Agent 缺口「长任务可恢复」）：TTL 内未完成任务的流程级状态
                // （原话 / 计划进度 / 已产出文件 / 上次中断原因）注入提示词 —— 用户说「继续」时
                // 模型能接着做而不是从头重来；已完成或过期任务不会注入。
                val taskNote = if (localLight) null else {
                    com.rokidlab.phone.ai.AgentTaskStore.pendingContext(session.appContext)
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
                var activeTools = buildTools(
                    if (localLight) ToolRegistry.SESSION_LOCAL_DOMAINS
                    else ToolRegistry.SESSION_AGENT_DOMAINS,
                )
                // 本次请求**真正下发**的工具名（装配侧的唯一投影，供系统提示做能力闸门）。
                // 切换域子集（aiuiMode）时一并更新 —— 见下面两处 buildTools 调用。
                var assembledToolNames: Set<String> = ToolRegistry.namesOf(activeTools)

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
                            service.buildSystemMessage(
                                localMode = localLight,
                                // 人设里点名的工具条款按**本次真正下发的**工具做闸门（见该参数 KDoc）
                                availableTools = assembledToolNames,
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
                            memories = longTermContext,
                            lessons = lessonsContext,
                            skills = skillsContext,
                            budget = listOfNotNull(taskNote, budgetNote).joinToString("\n\n").ifBlank { null },
                            sessionPrompt = sessionPrompt,
                            availableTools = assembledToolNames,
                        )?.let { put(it) }
                        put(userMsg)
                    }
                }
                // var（不是 val）：溢出恢复时整体替换，见下方 callModel 的 catch
                var messages = buildMessages()

                // 命中该调用的回合视为进入 AIUI/代码生成会话 → 下轮起切精简工具子集（仅切一次）
                val isCodeGenCall: (String, String) -> Boolean = { name, args ->
                    name == ToolRegistry.TOOL_CODE_FILE ||
                        (name == com.rokidlab.phone.ai.SkillRegistry.TOOL_NAME && args.contains("aiui-dev"))
                }
                // 工具执行统一入口（主循环与总结兜底轮共用）：异常类失败（网络抖动/ADB 隧道
                // 瞬断等瞬时错误）自动重试一次（500ms 退避）再如实回报模型——瞬时失败直接
                // 上报会让模型过早放弃或向用户播报失败；业务性失败（"没有找到歌曲"等字符串
                // 返回值）不重试，语义已经是确定性结果。
                fun executeTool(tc: com.rokidlab.phone.ai.ToolCallInfo): String {
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
                    val argsObj = if (tc.arguments.isBlank()) {
                        org.json.JSONObject()
                    } else {
                        runCatching { org.json.JSONObject(tc.arguments) }.getOrNull()
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
                        localOnly = phoneOnly,
                        // 无人值守（定时自主任务）：装配侧只下发了只读∪媒体白名单，
                        // 这里让**执行侧**与之一致 —— 模型凭历史复述出 call_phone 也会被拦下
                        unattended = readOnlyTools,
                        // 读工具开关（MCP 第三方工具首次出现时被写成显式 false）
                        context = session.appContext,
                    )
                    if (decision is com.rokidlab.phone.ai.approval.ToolDecision.Deny) {
                        Log.w(TAG, "tool ${tc.name} denied by approval gate: [${decision.origin}] ${decision.reason}")
                        return "工具 ${tc.name} 被安全策略拦截：${decision.reason}。" +
                            "请换用其他合适的方式完成任务，或如实告知用户。"
                    }
                    // A3 修复：带副作用的工具（拨号/短信/日历/安装/打开应用等）失败不重试，
                    // 否则隧道瞬断（命令已送达、回执丢失）会触发重复拨号/重复建日程等不可逆后果。
                    // 伪工具里也有带副作用的（manage_memory 写记忆、install_skill 装技能、delete_skill 删技能），
                    // 它们不在 SIDE_EFFECT_TOOLS（那张表只由真实工具条目派生），必须一并算进来。
                    val sideEffecting = ToolRegistry.SIDE_EFFECT_TOOLS.contains(tc.name) ||
                        tc.name in com.rokidlab.phone.ai.approval.PseudoTools.sideEffectNames()
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
                                com.rokidlab.phone.ai.SkillRegistry.TOOL_INSTALL ->
                                    com.rokidlab.phone.ai.SkillRegistry.executeInstall(session.appContext, tc.arguments)
                                com.rokidlab.phone.ai.SkillRegistry.TOOL_LIST ->
                                    com.rokidlab.phone.ai.SkillRegistry.executeList(session.appContext)
                                com.rokidlab.phone.ai.SkillRegistry.TOOL_DELETE ->
                                    com.rokidlab.phone.ai.SkillRegistry.executeDelete(session.appContext, tc.arguments)
                                com.rokidlab.phone.ai.AgentPlan.TOOL_NAME ->
                                    com.rokidlab.phone.ai.AgentPlan.execute(tc.arguments)
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
                    if (longTermOn && !localLight) {
                        val brief = lastError?.message.orEmpty()
                            .replace(Regex("\\s+"), " ").trim().take(60)
                        longTermMemory.addLessonOnce(
                            session.appContext,
                            dedupKey = tc.name,
                            content = if (brief.isEmpty()) {
                                "工具 ${tc.name} 在本机执行失败过：原样重试无效，下次先确认前置条件（连接/权限/环境是否就绪）或换用其它工具与参数。"
                            } else {
                                "工具 ${tc.name} 在本机执行失败过（$brief）：原样重试无效，下次先确认前置条件（连接/权限/环境是否就绪）或换用其它工具与参数。"
                            },
                        )
                    }
                    // 反思引导（Agent 缺口 #4）：瞬时重试耗尽仍失败，不能只回干巴巴的错误串 ——
                    // 明确要求模型分析原因并换策略（改参数/换工具/拆任务），禁止原样重试同一调用。
                    return "工具执行失败: ${lastError?.message}。请先分析失败原因再决定下一步：" +
                        "参数是否正确？是否换用其他工具或参数？是否把任务拆小？" +
                        "不要原样重复刚才的调用；若换一种方式仍无法完成，请如实告知用户。"
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
                 */
                fun runTool(tc: com.rokidlab.phone.ai.ToolCallInfo): String {
                    agentTurn?.toolCall(tc.id, tc.name, tc.arguments)
                    val out = executeTool(tc)
                    agentTurn?.toolResult(tc.id, tc.name, out)
                    return out
                }

                var reply = ""
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
                // 已自动续做次数（分账额度触顶后自动再开一段，见 MAX_AUTO_CONTINUES）
                var autoContinues = 0
                // 上下文溢出是否已恢复过一次（compaction 接缝的 CONTEXT_OVERFLOW 入口）。
                // 整个请求**只允许一次**：第二次仍溢出就照旧上抛，绝不在这里打转 ——
                // 宁可能力退化成改造前的"提示用户换个短点的问题"，也不要变成"卡住不动"。
                var overflowRecovered = false
                // 工具循环：支持多步任务（先查时间再设定时等），同时防止模型反复请求工具导致死循环
                for (round in 0 until MAX_TOTAL_ROUNDS) {
                    // 用户打断（有更新代际的请求进入）或链路已被替换：放弃后续生成，尽快让出 aiSendLock
                    if (isSuperseded()) {
                        Log.i(TAG, "AI generation superseded at round=$round (gen=$generation, latest=$aiGenSeq), abort")
                        // 事件流：本轮被抢占 —— 记一次"被抢占的尝试"，并声明本轮**不算数**
                        // （本轮用户消息在轮次开始时就落盘了，不收尾的话会留下"有 user 没有
                        //  assistant"的半轮，下一轮请求就会出现两条连续 user → 部分服务端 400）
                        agentTurn?.attempt(
                            com.rokidlab.phone.ai.session.AttemptOutcome.SUPERSEDED,
                            "round=$round",
                        )
                        agentTurn?.finish(com.rokidlab.phone.ai.session.TurnEndReason.INTERRUPTED)
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
                    // Agent 过程（手机端「过程」时间线）：先发一条「正在思考」。
                    // 工具调用轮 content 为空，若不发这条，用户从点发送到最终回复之间只看到空白，
                    // 无法判断 Agent 是在思考、在等网络、还是已经卡死（这正是"看不到调用工具"的根因）。
                    onTrace?.invoke(AgentStep.thinking(round))
                    // 思考增量（仅开启长思考时有数据）：节流后再推 UI —— 推理可能上万个 delta，
                    // 每个都写一次 SnapshotStateList 会让主线程持续重组。
                    val reasoningBuf = StringBuilder()
                    var lastThinkEmitAt = 0L
                    // 本轮模型请求。抽成局部函数是为了在里面接**一次**上下文溢出恢复：
                    // 改造前溢出只会整轮失败，给用户一句「这次内容太长了，换个短一点的问题再试」——
                    // 而真正能修的动作（压掉历史再发一次）代码里根本没有。现在命中"输入超长"
                    // 就强压历史 + 整体重建消息序列 + 原地重试同一轮（递归一层，受
                    // overflowRecovered 约束；第二次仍溢出会原样抛出，行为退化成改造前）。
                    fun callModel(): com.rokidlab.phone.ai.ChatTurn = try {
                        service.chatTurnStream(
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
                    }
                    assistantMsg.put("tool_calls", calls)
                    messages.put(assistantMsg)

                    // 并发执行工具：ADB 类工具在 ToolRegistry 内通过 adbLock 串行（蓝牙单连接安全），
                    // 非 ADB 工具真并发，降低多工具延迟叠加；结果按原顺序回填保证 messages 顺序稳定
                    val results = arrayOfNulls<String>(turn.toolCalls.size)
                    // 视觉工具（look_at_view）拍到的画面（base64）。必须**在工具自己的线程上**取走 ——
                    // provider 用 ThreadLocal 记录"这条调用的产出"，跨线程取会拿到 null。
                    val lookImages = arrayOfNulls<String>(turn.toolCalls.size)
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
                                    // 「→」＝进行中图标（GB2312 字形，单色屏不缺字）
                                    sendGlassesProgress(
                                        it,
                                        if (isCodeFile && relFile.isNotEmpty()) "→ 正在生成 $relFile…"
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
                                tc.name != com.rokidlab.phone.ai.AgentPlan.TOOL_NAME
                            if (traceVisible) {
                                onTrace?.invoke(
                                    AgentStep.tool(
                                        tc.id, tc.name, AgentStep.State.RUNNING, argsRaw = tc.arguments,
                                    ),
                                )
                            }
                            results[idx] = runTool(tc)
                            // ⚠️ 必须在**执行工具的这个线程**上取（ThreadLocal 语义），且必须在
                            // trace 回调之前 —— 与 runTool 紧邻，避免中间被别的逻辑岔开。
                            // 出图工具（眼镜相机 look_at_view / 手机截屏 capture_screen）都走这条通道，
                            // 名单见 VisionToolProvider.pendingImageTools（新增出图工具必须登记）。
                            if (tc.name in com.rokidlab.phone.ai.tools.VisionToolProvider.pendingImageTools) {
                                lookImages[idx] = com.rokidlab.phone.ai.tools.VisionToolProvider.takePendingImage()
                            }
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
                                        if (r?.startsWith("已生成") == true) "√ $relFile 生成成功"
                                        else "× 生成 $relFile 失败，请换个说法再试",
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
                                        if (ok) "√ ${ToolRegistry.doneText(tc.name)}"
                                        else "× ${ToolRegistry.doneText(tc.name)} 失败",
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
                        // 注入隔离：外部作者内容（网页/MCP/文档/OCR/子助手）包进 <untrusted_source>，
                        // 本地确定性工具结果原样回填。信任级别在 ToolEntry 声明、这里单一出口包装。
                        val safeResult = com.rokidlab.phone.ai.UntrustedContent.wrap(
                            ToolRegistry.contentTrustOf(tc.name), tc.name, result,
                        )
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
                            messages.put(JSONObject().apply {
                                put("role", "user")
                                put(
                                    "content",
                                    "继续执行剩余步骤（已完成的不要重做）：工具轮次额度已自动追加。" +
                                        "请接着完成目标；若确实已经全部完成，直接用一两句中文给出最终结论。",
                                )
                            })
                            link?.let { sendGlassesProgress(it, "→ 继续执行剩余步骤…") }
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
                        agentTurn?.finish(com.rokidlab.phone.ai.session.TurnEndReason.INTERRUPTED)
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
                        // 成本记账：收尾轮也是真实请求（且常是大 prompt），一并累进本轮
                        agentTurn?.recordModelCall(finalTurn?.usage)
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
                                // 同一处注入隔离出口（与主工具循环一致）
                                put(
                                    "content",
                                    com.rokidlab.phone.ai.UntrustedContent.wrap(
                                        ToolRegistry.contentTrustOf(tc.name), tc.name, out,
                                    ),
                                )
                            })
                        }
                        // 兜底总结轮同样支持进入 AIUI 精简工具子集（省 session.token），下一轮重试即生效
                        if (!readOnlyTools && !aiuiMode && finalTurn.toolCalls.any { isCodeGenCall(it.name, it.arguments) }) {
                            aiuiMode = true
                            activeTools = buildTools(ToolRegistry.SESSION_AIUI_DOMAINS)
                            assembledToolNames = ToolRegistry.namesOf(activeTools)
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
                // 收尾本轮事件流：落 [AssistantMessage] + [TurnEnd]，并做压力裁剪
                // （= 改造前 recordTurn 里那一步 trim）。工具轨迹不再单独存一份字符串列表 ——
                // 它由本轮的 ToolCall 事件派生（同一条事实只留一个出处）。
                agentTurn?.finish(
                    if (budgetExhausted) com.rokidlab.phone.ai.session.TurnEndReason.BUDGET_EXHAUSTED
                    else com.rokidlab.phone.ai.session.TurnEndReason.COMPLETED,
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
                    agentTurn?.finish(
                        com.rokidlab.phone.ai.session.TurnEndReason.INTERRUPTED,
                        detail = e.message,
                    )
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
                // 事件流收尾：本轮失败（未产出结论）→ 记一次失败尝试并把本轮声明为"不算数"，
                // 与改造前"失败不写会话记忆"的行为一致，但这次**留下了可归因的事实**。
                agentTurn?.attempt(
                    com.rokidlab.phone.ai.session.AttemptOutcome.FAILED,
                    e.message,
                )
                agentTurn?.finish(
                    com.rokidlab.phone.ai.session.TurnEndReason.FAILED,
                    detail = e.message,
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
        deepSeekThread.join()
        // 兜底再清洗一次（生成线程里已清过一次；失败兜底文案等其他赋值路径也覆盖到）。
        // 幂等，成本可忽略。
        reply = com.rokidlab.phone.ai.ReplySanitizer.sanitize(replyRef.get())
        // 登记回复全文：环境音链路据此做 TTS 回声判定（播报被远场麦拾到会形成自激，见 dispatchGlassesAsrText）
        lastAiReplyText = reply
        lastAiReplyAtMs = System.currentTimeMillis()
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
            val glassSpoken = com.rokidlab.phone.ai.ReplySanitizer.sanitizeForGlass(reply).ifBlank { reply }
            // 官方协议: caps[0] = "TTS_Result", caps[1] = 回复文字，
            // caps[2] = Lab 来源标记（眼镜端据此区分 Lab 回复与官方回声，见 LinkProtocol.AI_REPLY_MARK）。
            // 官方只按索引读前两个元素，多写一个会被忽略，向后兼容。
            // （2026-09-26 用户拍板：回复前不再加分隔线——过程/答案的区分靠 MD 渲染已足够。）
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
                AiChannel.encodeTtsPlay(glassSpoken).forEach { ttsPlayCaps.write(it) }
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
