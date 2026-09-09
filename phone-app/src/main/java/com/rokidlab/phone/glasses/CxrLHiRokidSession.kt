package com.rokidlab.phone.glasses

import com.rokidlab.phone.app.*
import com.rokidlab.phone.adb.*
import com.rokidlab.phone.design.*
import com.rokidlab.phone.filemanager.*
import com.rokidlab.phone.mirror.*
import com.rokidlab.phone.model.*
import com.rokidlab.phone.network.*
import com.rokidlab.phone.settings.*
import com.rokidlab.phone.store.*
import com.rokidlab.phone.util.*
import com.rokidlab.phone.ai.ToolRegistry
import com.rokidlab.phone.ai.AiuiProject
import com.rokidlab.phone.connection.ConnectionRoute
import com.rokidlab.phone.R
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.rokid.cxr.link.CXRLink
import com.rokid.cxr.link.callbacks.IGlassAppCbk
import com.rokid.cxr.link.callbacks.IImageStreamCbk
import com.rokid.cxr.link.utils.CxrDefs
import com.rokid.cxr.Caps
import com.rokid.sprite.aiapp.externalapp.auth.AuthResult
import com.rokid.sprite.aiapp.externalapp.auth.AuthorizationHelper
import com.rokid.sprite.aiapp.externalapp.auth.GlassPermission
import com.rokid.sprite.aiapp.externalapp.IMediaStreamService
import java.io.File
import java.lang.reflect.Field
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import java.util.ArrayDeque
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.runBlocking

class CxrLHiRokidSession(
    activity: AppCompatActivity,
    private var onStatus: (String) -> Unit,
    private var onBusyChanged: (Boolean) -> Unit,
    private var onConnectionChanged: (CxrConnectionState) -> Unit,
    initialHostApp: RokidHostApp = RokidHostApp.DEFAULT,
    /** 用于启动授权 Activity 的现代 ActivityResultLauncher，替代已废弃的 startActivityForResult */
    private val authLauncher: ((Intent) -> Unit)? = null,
    /** 长驻任务作用域（Application 级）：ASR 推送/轮询等不随 Activity 销毁取消。
     *  配合保活前台服务，Activity 退后台/销毁后语音链路仍持续运行。 */
    private val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    // ── Activity 泄漏防护 ──
    // 本会话由 LabApplication（应用级单例）持有，若强引用 Activity，
    // 保活模式下 Activity 销毁后仍被单例钉住无法回收（泄漏整棵 View 树）。
    // 因此：仅持 application 级 Context + Activity 弱引用；
    // 确需 Activity 的场景（授权页跳转/销毁检查）走 activityRef。
    private val appContext: android.content.Context = activity.applicationContext
    private val activityRef = java.lang.ref.WeakReference(activity)
    /** 主线程调度：替代 activity.runOnUiThread（Activity 回收后仍可用） */
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * 保活模式下 Activity 销毁时调用：解除回调对 Activity 方法引用的强引用。
     * 后台链路（AI/ASR/轮询/投屏）不受影响继续运行，
     * UI 回调降级为纯日志，下次打开 App 会重建会话恢复 UI。
     */
    fun detachUiCallbacks() {
        onStatus = { msg -> LogCollector.i(TAG, msg) }
        onBusyChanged = { }
        onConnectionChanged = { }
        Log.i(TAG, "UI callbacks detached (keep-alive background mode)")
    }
    companion object {
        private const val TAG = "CxrLHiRokidSession"
        private const val PREFS_NAME = "cxr_l_auth"
        private const val KEY_TOKEN_PREFIX = "token_"

        private const val AUTH_ACTIVITY_CLASS = "com.rokid.sprite.aiapp.externalapp.auth.AuthorizationActivity"
        private const val AUTH_ACTION = "com.rokid.sprite.aiapp.externalapp.AUTHORIZATION"
        private const val MEDIA_SERVICE_ACTION = "com.rokid.sprite.aiapp.externalapp.MEDIA_STREAM_SERVICE"
        private const val AUTH_TOKEN_EXTRA = "auth_token"
        private const val AUTH_PACKAGE_EXTRA = "auth_package"

        /** 眼镜端镜腿按键触发的「拍照问 AI」指令通道 */
        private const val PHOTO_ASK_CMD = "rokidlab_photo_ask"
        /**
         * AI 文字轮询通道（手机端 → 眼镜端）：定时 sendCustomCmd 轮询，
         * 眼镜端可回复订阅返回 ASR 文字。请求-响应机制可绕过 AI App 对未知上行指令的过滤。
         */
        private const val AI_ASR_POLL_CMD = "rokidlab_ai_asr_poll"




        /** OpenAI 兼容 AI 配置存储 */
        private const val AI_PREFS = "chat_prefs"
        private const val KEY_AI_BASE_URL = "ai_base_url"
        private const val KEY_AI_API_KEY = "ai_api_key"
        private const val KEY_AI_MODEL = "ai_model"
        /** 对话模型模式键：official（官方乐奇）/ custom（Lab 自定义模型），持久化 + 下发眼镜端（值常量统一见 AiChannel.AI_MODE_*） */
        private const val KEY_AI_MODE = "ai_mode"
        /** 拍照答题指令：答题时注入 AI 提示词控制回答方式 */
        private const val KEY_QUIZ_INSTRUCTION = "quiz_instruction"
        /** 本地模型（Ollama）是否作为眼镜对话模型：true=对话仅走本机 Ollama；
         *  与在线槽位（KEY_AI_BASE_URL/API_KEY/MODEL/MODE）相互独立，互不覆盖 */
        private const val KEY_AI_USE_LOCAL = "ai_use_local"
        /** 当前选择的本地对话模型名（如 qwen2.5:0.5b） */
        private const val KEY_AI_LOCAL_MODEL = "ai_local_model"
        /** 本地对话请求的用户自定义 JSON 参数（逐字段合并进每次本地请求体，如 {"think": false}；
         *  空串=不附加。替代原「深度思考」布尔开关，兼容任何模型的调参需求） */
        private const val KEY_AI_LOCAL_PARAMS = "ai_local_params"
        /** 旧版「深度思考」布尔开关键（已废弃，读取时自动迁移到 KEY_AI_LOCAL_PARAMS 后清除） */
        private const val KEY_AI_LOCAL_THINK_LEGACY = "ai_local_think"
        /** 在线模型长思考开关（DeepSeek V4/V3.2 系生效）：默认关闭（推理吞预算→空轮）；
         *  与发送键旁的「思考」切换按钮共享此键（ChatScreen 直接读写同一 prefs） */
        private const val KEY_AI_THINKING = "ai_thinking"
        private const val KEY_KEY_QUIZ_ENABLED = "key_quiz_enabled"

        private fun tokenPrefKey(hostApp: RokidHostApp) = KEY_TOKEN_PREFIX + hostApp.packageName
    }

    // ═══════════════════════════════════════════════════
    // 内部状态字段 / UI 回调注册 / AI 配置（持久化与下发眼镜端）
    // ═══════════════════════════════════════════════════

    private var hostApp: RokidHostApp = initialHostApp
    private var token: String? = null
    private var cxrLink: CXRLink? = null
    private var pendingOperation: CxrAppOperation? = null
    private var queryQueue: ArrayDeque<String> = ArrayDeque()
    private var onQueryResult: ((String, Boolean) -> Unit)? = null
    private var onQueryComplete: (() -> Unit)? = null
    private var cxrlConnected = false
    private var glassBtConnected = false
    private var operationStarted = false
    /** 防止超时与 operation.onReady 回调竞态 */
    private var operationCompleted = false
    private var timeoutJob: Job? = null
    private var aiConfigPushJob: Job? = null
    /** 「拍照问 AI」图片回调超时兜底（takePhoto 成功但图片回调永不到达时复位状态） */
    private var photoRequestTimeoutJob: Job? = null
    /** 使用同步锁保护操作状态 */
    private val operationLock = Any()

    /**
     * AI 下行发送互斥锁：WiFi 稳定连接时聊天发送 / ASR push / 轮询 / SDK 上行
     * 多个并发入口同时命中 sendAiTextMessage 快速路径，无锁并发 sendCustomCmd
     * 同一 CXRLink 会与 cleanup() 的 disconnect 产生竞态（SDK native 崩溃），
     * 因此 sendAiTextViaLink 全流程加锁串行执行。
     */
    private val aiSendLock = Any()

    /**
     * AI 单条指令发送互斥锁：sendAiTextViaLink 全流程锁（aiSendLock）持有期间，
     * 工具进度线程（deepSeekThread）会并发向眼镜发送进度提示（TTS_Result），
     * 与下行主链路的 KeyDown/open/ASR_Result/ASR_End 存在并发 sendCustomCmd 竞态。
     * 下行主链路各条指令与进度发送均按条加锁串行。
     *
     * 锁顺序约定：aiSendLock（外层）→ aiCmdLock（内层），全程单向获取，
     * 禁止反向（持有 aiCmdLock 时再去获取 aiSendLock）以防死锁。
     * 当前所有 aiCmdLock 临界区均在 aiSendLock 持有期间调用，顺序一致，无死锁风险。
     */
    private val aiCmdLock = Any()

    /** ASR 桥接协调器（双通道接收/去重/控制标记/下行 ping，从本类拆出，职责见其文档） */
    private val asrBridge = AsrBridgeCoordinator(
        appContext = appContext,
        appScope = appScope,
        linkProvider = { cxrLink },
        linkAlive = { cxrlConnected },
        cmdLock = aiCmdLock,
        asrDeliver = { text -> dispatchGlassesAsrText(text) },
        onAbortAi = {
            stopTtsOnGlass()
            abortCurrentAi()
        },
        onPhotoAsk = { startPhotoAsk() },
        onToolCall = { payload -> handleAiuiToolCall(payload) },
    )

    /**
     * AIUI 页面发起的工具调用（上行 __LAB_TOOL__ + JSON）。
     *
     * 页面 → 眼镜端 JS bridge → RFCOMM 上行 → 本方法 → ToolGateway 执行 →
     * 结果经 CMD_AIUI_MSG 下行 → 眼镜端 dispatchMessageToActive → 页面 Promise resolve。
     *
     * 已在 AsrBridgeCoordinator 侧切到后台线程，此处可直接同步执行。
     */
    private fun handleAiuiToolCall(payload: String) {
        var cbId = ""
        try {
            val obj = JSONObject(payload)
            cbId = obj.optString("cbId")
            val name = obj.optString("name")
            val args = obj.optString("args").ifBlank { "{}" }
            Log.i(TAG, "handleAiuiToolCall: name=$name cbId=$cbId")
            val r = com.rokidlab.phone.ai.ToolGateway.call(appContext, name, args)
            val out = JSONObject()
                .put("type", "toolResult")
                .put("cbId", cbId)
                .put("ok", r.ok)
            r.result?.let { out.put("result", it) }
            r.error?.let { out.put("error", it) }
            aiui.sendAiuiHostMessage(out.toString())
        } catch (e: Exception) {
            Log.e(TAG, "handleAiuiToolCall failed", e)
            // 解析/下发失败也要尽力回传，否则页面 Promise 会挂到超时
            runCatching {
                aiui.sendAiuiHostMessage(
                    JSONObject()
                        .put("type", "toolResult")
                        .put("cbId", cbId)
                        .put("ok", false)
                        .put("error", "tool call failed: ${e.message}")
                        .toString(),
                )
            }
        }
    }

    /**
     * 手机端 NetProxy 应答器：眼镜端 Jsai 下载 .aix（installAiuiAgent）时，
     * 眼镜经 BLE 代理把 HTTP 流量发到手机，本中继在手机侧执行真实 socket 收发后
     * 以 Proxy_NetResponse 应答（协议详见 GlassProxyRelay）。
     */
    private val proxyRelay: GlassProxyRelay = GlassProxyRelay(::sendProxyFrame)

    /**
     * AI 生成代际计数：每次新的 sendAiTextMessage（语音唤醒/聊天/拍照答题）进入即 +1。
     * 执行中或排队中的旧请求检测到自身代际已过期（用户已发起新请求）即放弃继续生成，
     * 避免旧的多步工具任务长时间占用 aiSendLock 链路，让新语音/消息尽快接管
     * （用户打断场景：Agent 还在跑工具循环时用户再说话，旧任务应让路）。
     */
    @Volatile
    private var aiGenSeq = 0L

    /** 「拍照问 AI」流程编排器（拍照→OCR→RAG→AI 答题，从本类拆出） */
    private val photoQuiz = PhotoQuizFlow(
        appContext = appContext,
        appScope = appScope,
        mainHandler = mainHandler,
        takePhoto = { w, h, q, onPhoto, onError -> takeGlassesPhoto(w, h, q, onPhoto, onError) },
        sendAiQuestion = { question, contextText, instruction, onResult, onReply ->
            sendAiTextMessage(
                question,
                contextText = contextText,
                // 答题完成后保留眼镜端回复显示：skipTtsAudioFinished=true 不发送
                // TTS_AudioFinished（该消息会触发官方会话 startNewTalk 重置，清掉刚显示的答案）
                skipTtsAudioFinished = true,
                // 注入设置页填写的答题指令（如「只显示答案」「给出解题步骤」）
                instruction = instruction,
                // 一次性问答且带答题指令，不记录到 Agent 会话记忆（避免污染闲聊上下文）
                recordHistory = false,
                onResult = onResult,
                onReply = onReply,
            )
        },
        quizInstructionProvider = { getAiConfig().quizInstruction },
    )

    /** 注册「拍照问 AI」流程的 UI 回调（乐奇聊天界面进入时调用，按键触发时复用展示） */
    fun setPhotoAskUiCallbacks(
        onStage: (Int) -> Unit,
        onText: (String) -> Unit,
        onReply: (String) -> Unit,
    ) = photoQuiz.setUiCallbacks(onStage, onText, onReply)

    /** 眼镜端唤醒词对话（语音 ASR）的 UI 回调：同步显示到乐奇聊天窗口 */
    @Volatile
    private var glassesAiTextCb: (String) -> Unit = {}
    @Volatile
    private var glassesAiReplyCb: (String) -> Unit = {}

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
     * appStart 后的一次性冷却标志：真实 resume（appStart 触发的 Sys_App_Resume_Change）只会到达一次，
     * 用一次性标志消费而非 3s 时间窗口——时间窗口会把「开启后 3s 内的第一次按键」也误过滤，
     * 导致按键答题开启后第一次按键没反应。标志设置后 3s 未收到真实 resume 会自动清除（防残留）。
     */
    @Volatile
    private var quizResumeCooling = false

    /** WiFi 连接状态回调（由 sendWifiConfig 设置，统一在全局指令监听中转发） */
    @Volatile
    private var wifiStatusCallback: ((String) -> Unit)? = null

    /** DeepSeek API Key（用户显式配置后保存；禁止内置 key 防止反编译盗用） */
    private var deepSeekApiKey: String = ""

    /** 更新 DeepSeek API Key */
    fun setDeepSeekApiKey(key: String) {
        deepSeekApiKey = key
    }

    /** OpenAI 兼容 AI 服务配置 */
    data class AiConfig(
        val baseUrl: String = "https://api.deepseek.com",
        val apiKey: String = "",
        val model: String = "deepseek-chat",
        /** 对话模型模式：AiChannel.AI_MODE_OFFICIAL（官方乐奇）/ AiChannel.AI_MODE_CUSTOM（Lab 自定义模型） */
        val mode: String = AiChannel.AI_MODE_CUSTOM,
        /** 拍照答题指令：设置页填写，答题时注入 AI 提示词控制回答方式（如「只显示答案」「给出解题步骤」） */
        val quizInstruction: String = "",
    )

    /**
     * 保存「在线」AI 配置（自定义服务/乐奇官方），并关闭本地模型模式。
     * 本地模型走 [setLocalChatModel]，不会写入本槽位。
     */
    fun setAiConfig(config: AiConfig) {
        runCatching {
            appContext.getSharedPreferences(AI_PREFS, 0).edit()
                .putString(KEY_AI_BASE_URL, config.baseUrl)
                .putString(KEY_AI_API_KEY, config.apiKey)
                .putString(KEY_AI_MODEL, config.model)
                .putString(KEY_AI_MODE, config.mode)
                .putString(KEY_QUIZ_INSTRUCTION, config.quizInstruction)
                .putBoolean(KEY_AI_USE_LOCAL, false)
                // 切到在线/自定义服务时清掉残留的本地模型名，
                // 否则 ai_local_model 非空会让设置弹窗的 LaunchedEffect 误判为「仍选本地模型」。
                .remove(KEY_AI_LOCAL_MODEL)
                .apply()
        }
        if (config.apiKey.isNotBlank()) deepSeekApiKey = config.apiKey
        // 同步下发到眼镜端：唤醒词识别出的文字由眼镜端直接调用该模型回复
        pushAiConfigToGlass(config)
    }

    /** 是否已启用本地 Ollama 作为眼镜对话模型 */
    fun isLocalChatActive(): Boolean =
        appContext.getSharedPreferences(AI_PREFS, 0).getBoolean(KEY_AI_USE_LOCAL, false)

    /** 当前选择的本地对话模型名（未启用/未选择返回空串） */
    fun localChatModel(): String =
        appContext.getSharedPreferences(AI_PREFS, 0).getString(KEY_AI_LOCAL_MODEL, "").orEmpty()

    /** 本地对话请求的自定义 JSON 参数（原始字符串，空串=未配置）。
     *  旧版 ai_local_think 布尔开关首次读取时自动迁移为 {"think": <旧值>} 并清除旧键 */
    fun localChatParams(): String {
        val prefs = appContext.getSharedPreferences(AI_PREFS, 0)
        var raw = prefs.getString(KEY_AI_LOCAL_PARAMS, null)
        if (raw.isNullOrBlank() && prefs.contains(KEY_AI_LOCAL_THINK_LEGACY)) {
            raw = runCatching {
                JSONObject().put("think", prefs.getBoolean(KEY_AI_LOCAL_THINK_LEGACY, false)).toString()
            }.getOrNull()
            if (raw != null) {
                prefs.edit().putString(KEY_AI_LOCAL_PARAMS, raw).remove(KEY_AI_LOCAL_THINK_LEGACY).apply()
            }
        }
        return raw.orEmpty()
    }

    /** 保存本地对话请求的自定义 JSON 参数（空串=清除；调用方负责校验 JSON 合法性） */
    fun setLocalChatParams(json: String) {
        appContext.getSharedPreferences(AI_PREFS, 0).edit()
            .putString(KEY_AI_LOCAL_PARAMS, json).apply()
    }

    /** 解析本地请求参数为 JSON 对象；未配置/非法返回 null（非法时打日志并忽略，不影响对话） */
    private fun parseLocalChatParams(): JSONObject? {
        val raw = localChatParams().trim()
        if (raw.isEmpty()) return null
        return runCatching {
            val obj = JSONObject(raw)
            if (obj.length() == 0) null else obj
        }.getOrElse {
            Log.w(TAG, "parseLocalChatParams: 非法 JSON 已忽略: $raw")
            null
        }
    }

    /**
     * 读取「在线」配置槽位原始值（设置页表单回填用），
     * 不叠加本地开关——即使当前在本地模型模式，也返回用户最后保存的在线服务配置。
     */
    fun getOnlineAiConfig(): AiConfig {
        val prefs = appContext.getSharedPreferences(AI_PREFS, 0)
        val baseUrl = prefs.getString(KEY_AI_BASE_URL, "").orEmpty().ifBlank { "https://api.deepseek.com" }
        val apiKey = prefs.getString(KEY_AI_API_KEY, "").orEmpty()
        val model = prefs.getString(KEY_AI_MODEL, "").orEmpty().ifBlank { "deepseek-chat" }
        val mode = prefs.getString(KEY_AI_MODE, AiChannel.AI_MODE_CUSTOM).orEmpty().ifBlank { AiChannel.AI_MODE_CUSTOM }
        val quizInstruction = prefs.getString(KEY_QUIZ_INSTRUCTION, "").orEmpty()
        return AiConfig(baseUrl, apiKey, model, mode, quizInstruction)
    }

    /**
     * 切换到本地模型并设为眼镜对话模型（[modelName] 如 qwen2.5:0.5b）。
     * 仅持久化本地槽位与开关，不影响在线槽位配置；随后把有效配置下发眼镜端。
     * 切换后自动在后台卸载其他已驻留模型（ollama 每个模型独立 llama-server，
     * 默认 keep_alive ~5 分钟，不清理会长期多进程并存抢内存/CPU）。
     */
    fun setLocalChatModel(modelName: String) {
        val name = modelName.trim()
        if (name.isEmpty()) return
        runCatching {
            appContext.getSharedPreferences(AI_PREFS, 0).edit()
                .putBoolean(KEY_AI_USE_LOCAL, true)
                .putString(KEY_AI_LOCAL_MODEL, name)
                .apply()
        }
        pushAiConfigToGlass(getAiConfig())
        val unloadThread = Thread {
            try {
                com.rokidlab.phone.ai.LocalOllamaManager.unloadOtherModels(name)
            } catch (e: Exception) {
                Log.w(TAG, "unloadOtherModels failed: ${e.message}")
            }
        }
        unloadThread.name = "ollama-unload-others"
        unloadThread.start()
    }

    /** 仅保存拍照答题指令（不切换对话来源；本地模型模式下点「保存」时用） */
    fun setQuizInstructionOnly(text: String) {
        runCatching {
            appContext.getSharedPreferences(AI_PREFS, 0).edit()
                .putString(KEY_QUIZ_INSTRUCTION, text.trim())
                .apply()
        }
    }

    /** 仅回填在线槽位的 API Key（旧版 deepseek_key 迁移用，不影响本地开关与在线其他字段） */
    fun backfillOnlineApiKey(key: String) {
        if (key.isBlank()) return
        runCatching {
            appContext.getSharedPreferences(AI_PREFS, 0).edit()
                .putString(KEY_AI_API_KEY, key)
                .apply()
        }
        pushAiConfigToGlass(getAiConfig())
    }

    /** 在线模型长思考是否开启（默认关闭：思考吞输出预算导致工具调用空轮，已实测） */
    fun isThinkingEnabled(): Boolean =
        appContext.getSharedPreferences(AI_PREFS, 0).getBoolean(KEY_AI_THINKING, false)

    /** 持久化在线模型长思考开关（全局生效：眼镜语音与手机聊天共用同一在线槽位） */
    fun setThinkingEnabled(enabled: Boolean) {
        runCatching {
            appContext.getSharedPreferences(AI_PREFS, 0).edit()
                .putBoolean(KEY_AI_THINKING, enabled)
                .apply()
        }
        Log.i(TAG, "AI thinking mode = $enabled")
    }

    /** 下发 AI 配置（baseUrl/apiKey/model/mode）到眼镜端，供眼镜端本地直接调用模型。
     *  AI App 的 binder 在 bind 后异步就绪，此处每秒重试直到成功（最多 30 次）。 */
    private fun pushAiConfigToGlass(config: AiConfig) {
        aiConfigPushJob?.cancel()
        aiConfigPushJob = appScope.launch {
            repeat(30) { attempt ->
                val link = cxrLink
                if (link == null) {
                    // 链路尚未就绪：继续重试等待（首次判空不再退出整个协程）
                    delay(1000)
                    return@repeat
                }
                try {
                    // 版本化载荷：[cmd, version, baseUrl, apiKey, model, mode]
                    val caps = Caps()
                    AiChannel.encodeAiConfig(config.baseUrl, config.apiKey, config.model, config.mode)
                        .forEach { caps.write(it) }
                    val r = link.sendCustomCmd(AiChannel.TOPIC_AI_CONFIG, caps)
                    Log.i(TAG, "pushAiConfigToGlass: attempt=$attempt r=$r model=${config.model} mode=${config.mode}")
                    if (r == 0) return@launch
                } catch (e: Exception) {
                    Log.e(TAG, "pushAiConfigToGlass error (retry in 1s): ${e.message}")
                }
                delay(1000)
            }
            Log.w(TAG, "pushAiConfigToGlass: give up after 30 retries")
        }
    }

    /**
     * 读取「有效」AI 配置（对话/下发眼镜端实际使用）：
     * 本地模型开启且已选模型 → 指向本机 Ollama；否则返回在线槽位配置。
     */
    fun getAiConfig(): AiConfig {
        val prefs = appContext.getSharedPreferences(AI_PREFS, 0)
        val quizInstruction = prefs.getString(KEY_QUIZ_INSTRUCTION, "").orEmpty()
        if (prefs.getBoolean(KEY_AI_USE_LOCAL, false)) {
            val localModel = prefs.getString(KEY_AI_LOCAL_MODEL, "").orEmpty()
            if (localModel.isNotBlank()) {
                return AiConfig(
                    baseUrl = com.rokidlab.phone.ai.LocalOllamaManager.CHAT_BASE,
                    apiKey = "",
                    model = localModel,
                    mode = AiChannel.AI_MODE_CUSTOM,
                    quizInstruction = quizInstruction,
                )
            }
        }
        return getOnlineAiConfig()
    }

    // ═══════════════════════════════════════════════════
    // AI 工具：ADB 查询客户端
    // ═══════════════════════════════════════════════════

    @Volatile
    private var adbShellClient: com.rokidlab.phone.adb.AdbShellClient? = null

    /**
     * 发送文本到眼镜端本地 TTS 语音播报（tts_play 下行通道）。
     * 供定时任务 / AI 工具到点时语音提醒使用（App 退后台后链路仍可用）。
     * @return 发送结果码（0=成功，非 0=失败）
     */
    fun sendTtsToGlass(text: String): Int {
        if (text.isBlank()) return -1
        val link = cxrLink ?: return -2
        return try {
            fun send(): Int? {
                val caps = Caps()
                AiChannel.encodeTtsPlay(text).forEach { caps.write(it) }
                return link.sendCustomCmd(AiChannel.TOPIC_TTS_PLAY, caps)
            }
            var result: Int? = send()
            Log.i(TAG, "sendTtsToGlass(\"${text.take(40)}...\") -> $result")
            if (result != null && result != 0) {
                Thread.sleep(500)
                result = send()
                Log.w(TAG, "retry sendTtsToGlass -> $result")
            }
            result ?: -3
        } catch (e: Exception) {
            Log.e(TAG, "sendTtsToGlass failed", e)
            -1
        }
    }

    /**
     * 通知眼镜端立即停止本地 TTS 播报（tts_stop 下行通道）。
     * 对话退出/打断播报时调用：眼镜端 RokidLink 订阅 tts_stop 后调用
     * TtsPlaybackHelper.stop() 作废排队分块并释放正在等待的分块。
     * @return 发送结果码（0=成功，非 0=失败）
     */
    fun stopTtsOnGlass(): Int {
        val link = cxrLink ?: return -2
        return try {
            val caps = Caps()
            caps.write(AiChannel.CMD_TTS_STOP)
            // 与下行主链路串行（aiCmdLock），避免打断指令与 TTS_Result/tts_play 序列交错
            val result = synchronized(aiCmdLock) {
                link.sendCustomCmd(AiChannel.TOPIC_TTS_STOP, caps)
            }
            Log.i(TAG, "sendCustomCmd(${AiChannel.TOPIC_TTS_STOP}) -> $result")
            result ?: -3
        } catch (e: Exception) {
            Log.e(TAG, "stopTtsOnGlass failed", e)
            -1
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
        val seq = ++aiGenSeq
        Log.i(TAG, "abortCurrentAi: bumped aiGenSeq -> $seq (in-flight AI request will self-abort)")
        // 同步通知眼镜端停止正在播放的语音（tts_stop 下行通道）
        stopTtsOnGlass()
    }

    /** AIUI 微前端控制器（AgentStore/直启/自托管宿主全链路，从本类拆出） */
    private val aiui = AiuiFrontendController(
        appContext = appContext,
        appScope = appScope,
        routeManager = (appContext as LabApplication).routeManager,
        linkProvider = { cxrLink },
        cmdLock = aiCmdLock,
        rawSendCmd = { link, cmd, caps -> rawSendCustomCmd(link, cmd, caps) },
        adbClientProvider = { getAdbShellClient() },
    )

    /** 在眼镜上打开一个 AIUI agent（.aix），协议与参数见 AiuiFrontendController */
    fun openAiuiAgent(
        agentId: String,
        agentName: String,
        nativeVersion: String = "0.0.74",
        pageName: String = "pages/index/index",
    ): Int = aiui.openAiuiAgent(agentId, agentName, nativeVersion, pageName)

    /** 直启眼镜 cxr 目录已存在的 .aix（Sys_AIUI_Start） */
    fun startAiuiPackage(packageName: String): Int = aiui.startAiuiPackage(packageName)

    /** 关闭眼镜上正在渲染的 .aix（Sys_AIUI_Stop） */
    fun stopAiuiPackage(packageName: String): Int = aiui.stopAiuiPackage(packageName)

    /** 打开自托管宿主渲染本地已推送的 .aix */
    fun openAiuiHost(fileName: String? = null): Int = aiui.openAiuiHost(fileName)

    /** 关闭正在渲染的宿主 */
    fun closeAiuiHost(): Int = aiui.closeAiuiHost()

    /** 以 onMessage 协议向宿主页面注入消息 */
    fun sendAiuiHostMessage(json: String): Int = aiui.sendAiuiHostMessage(json)

    /** 把本地 .aix 推到 RokidLink aiui_host 目录并自动拉起宿主渲染 */
    fun pushAixToRokidLinkHost(
        aixFile: File,
        openAfter: Boolean = true,
        launchParams: String? = null,
    ): String? = aiui.pushAixToRokidLinkHost(aixFile, openAfter, launchParams)

    /**
     * CXR-L 1.1.0 的 ExternalAppClient.sendCustomCmd 内置保留 cmd 黑名单
     * （Dev/Med/Ota/Ai/Ntf/Nav/Sys/ARTC/Trans/Pay/Settings/Custom_View/Schedule/Memo），
     * 命中即返回 -1，外部无法直发 "Sys"/"Ai"。SDK 内部封装（appStart/openApp 等）
     * 同样绕过黑名单直接走 Binder 层 IMediaStreamService.sendCustomCmd(cmd, bytes)。
     * 这里用反射取 ExternalAppClient 私有字段 b（IMediaStreamService）绕过黑名单直发。
     */
    private fun rawSendCustomCmd(link: CXRLink, cmd: String, caps: Caps): Int {
        var clazz: Class<*>? = link.javaClass
        var field: Field? = null
        while (clazz != null && field == null) {
            field = try {
                clazz.getDeclaredField("b")
            } catch (e: NoSuchFieldException) {
                null
            }
            clazz = clazz.superclass
        }
        val f = field ?: return -3
        f.isAccessible = true
        val svc = f.get(link) as? IMediaStreamService ?: return -4
        return svc.sendCustomCmd(cmd, caps.serialize())
    }

    /** 安装 AIUI agent 到眼镜（Jsai_AddNativeAgent） */
    fun installAiuiAgent(
        agentId: String,
        agentName: String,
        url: String,
        fileMd5: String,
        nativeVersion: String = "0.0.74",
        agentDesc: String = "",
    ): Int = aiui.installAiuiAgent(agentId, agentName, url, fileMd5, nativeVersion, agentDesc)

    /** 直装 .aix 并自动打开一次 */
    fun installAndOpenAiuiAgentOnce(
        agentId: String,
        agentName: String,
        url: String,
        fileMd5: String,
        openDelayMs: Long = 3000L,
        openRetryMs: Long = 2000L,
        openTimeoutMs: Long = 25_000L,
    ): Int = aiui.installAndOpenAiuiAgentOnce(agentId, agentName, url, fileMd5, openDelayMs, openRetryMs, openTimeoutMs)

    /** 向眼镜下发 native agent 目录地址（Jsai_GetRequestInfo） */
    fun pushAiuiAgentListUrl(agentListUrl: String): Int = aiui.pushAiuiAgentListUrl(agentListUrl)

    /** 在时间窗口内周期下发目录配置 */
    fun startAgentListPushWindow(
        catalogUrl: String,
        durationMs: Long = 60_000L,
        intervalMs: Long = 1_500L,
    ) = aiui.startAgentListPushWindow(catalogUrl, durationMs, intervalMs)

    fun stopAgentListPushWindow() = aiui.stopAgentListPushWindow()

    /** 通知眼镜立即重新拉取 native agent 目录（Jsai_NotifyGlassGetList） */
    fun notifyGlassGetAgentList(): Int = aiui.notifyGlassGetAgentList()

    /**
     * 向眼镜发送 NetProxy 应答帧（cmd="Proxy"，caps0=Proxy_NetResponse），
     * 供 [GlassProxyRelay] 下行使用。与 AI 主链路同一把 aiCmdLock 串行。
     */
    private fun sendProxyFrame(caps: Caps): Int {
        val link = cxrLink ?: return -2
        return try {
            synchronized(aiCmdLock) { link.sendCustomCmd("Proxy", caps) } ?: -3
        } catch (e: Exception) {
            Log.e(TAG, "sendProxyFrame failed", e)
            -1
        }
    }

    /**
     * 获取（或懒创建并连接）ADB shell 客户端，供查询类 AI 工具使用。
     * 必须在后台线程调用（同步阻塞连接握手）。连接失败返回 null。
     */
    fun getAdbShellClient(): com.rokidlab.phone.adb.AdbShellClient? {
        adbShellClient?.let {
            if (it.isConnected()) return it
            runCatching { it.disconnect() }
        }
        return runCatching {
            val app = appContext as LabApplication
            val prefs = appContext.getSharedPreferences("adb_prefs", 0)
            val wifiIp = prefs.getString("ip", "192.168.1.168") ?: "192.168.1.168"
            val route = runBlocking { app.routeManager.resolve(wifiIp, 5555) }
            val (targetIp, targetPort) = when (route) {
                is ConnectionRoute.Wifi -> route.ip to route.port
                is ConnectionRoute.Bluetooth -> route.ip to route.localPort
                is ConnectionRoute.None -> return null
            }
            val client = com.rokidlab.phone.adb.AdbShellClient(appContext, targetIp, targetPort)
            if (client.connect()) {
                adbShellClient = client
                client
            } else {
                runCatching { client.disconnect() }
                // 连接失败（含蓝牙隧道 RFCOMM 卡顿/半开）时清理线路缓存，下次强制重新探测
                runCatching { app.routeManager.clearRouteCache() }
                null
            }
        }.getOrNull()
    }

    /** 当前「按键答题」开关状态（手机端本地持久化） */
    fun isKeyQuizEnabled(): Boolean {
        return runCatching {
            appContext.getSharedPreferences(AI_PREFS, 0).getBoolean(KEY_KEY_QUIZ_ENABLED, false)
        }.getOrDefault(false)
    }

    /** 清空 Agent 会话记忆（用户点击清空对话按钮时调用） */
    fun clearAgentHistory() {
        com.rokidlab.phone.ai.AgentSessionManager.clear()
    }

    /**
     * 下发「按键答题」开关到眼镜端。
     * 开关打开后：短按镜腿按键 = 拍照问AI（覆盖原自定义按键短按），长按不受影响。
     */
    fun sendKeyQuizConfig(enabled: Boolean, onResult: ((Boolean) -> Unit)? = null) {
        // onResult 防重：超时 onFailure 与迟到的 appStart 回调都可能触发，保证只通知一次
        var resultDelivered = false
        fun deliver(success: Boolean) {
            if (resultDelivered) return
            resultDelivered = true
            onResult?.invoke(success)
        }
        runCatching {
            appContext.getSharedPreferences(AI_PREFS, 0).edit()
                .putBoolean(KEY_KEY_QUIZ_ENABLED, enabled)
                .apply()
        }
        val targetHostApp = hostApp
        if (!hasGlassesOperationPrerequisites(targetHostApp, requestAuthorizationIfMissing = true)) {
            deliver(false)
            return
        }
        val authToken = token.orEmpty()

        // 轻量配置下发优化：链路已就绪时复用现有连接直接下发自定义指令，
        // 不再走 connectAndRunCustomAppOperation 的 cleanup() 全链路重建——
        // 重建期间（3~5s）ASR 推送通道与 ADB 隧道均不可用，保存设置后紧接着说话的
        // 第一条语音必然丢失（实测 21:28:46：保存设置→cleanup→start 连调两次→通道断开→丢字）。
        val existingLink = cxrLink
        if (existingLink != null && cxrlConnected && glassBtConnected) {
            val caps = Caps()
            AiChannel.encodeQuizConfig(enabled).forEach { caps.write(it) }
            val r = synchronized(aiCmdLock) { existingLink.sendCustomCmd(AiChannel.TOPIC_KEY_QUIZ, caps) }
            Log.i(TAG, "sendKeyQuizConfig: reuse existing link, sendCustomCmd(${AiChannel.TOPIC_KEY_QUIZ}, enabled=$enabled) -> $r")
            if (r == 0) {
                deliver(true)
                onBusyChanged(false)
                return
            }
            // 复用失败（如 CUSTOMAPP 会话尚未 appStart，自定义指令还路由不到眼镜端）：
            // 退回完整流程重建链路后下发。
            Log.w(TAG, "sendKeyQuizConfig: reuse failed (r=$r), fall back to full connect")
        }

        onBusyChanged(true)
        connectAndRunCustomAppOperation(
            authToken = authToken,
            targetHostApp = targetHostApp,
            operation = CxrAppOperation(
                packageName = "com.rokidlab.rokidlink",
                timeoutMillis = 10_000,
                timeoutMessage = "quiz config timeout",
                bindMessage = "Sending quiz config",
                configureFailureMessage = "Configure CXR-L CUSTOMAPP session failed",
                bindFailureMessage = "Bind host service failed",
                showConnectionStatus = false,
                onReady = { link ->
                    // CUSTOMAPP 场景构建完成后 cxr-service 才会把自定义指令路由给眼镜端：
                    // 必须先 appStart 并等待 onOpenAppResult 成功，再 sendCustomCmd（与 launchApp 一致）
                    val entryUri = "com.rokidlab.rokidlink.MainActivity"
                    link.appStart(entryUri, glassAppCallback(
                        onStart = { success ->
                            if (success) {
                                // 置一次性冷却标志：appStart 后眼镜端 RokidLink 会真实 resume，
                                // 触发 Sys_App_Resume_Change 上行，需消费该 resume 避免误触发拍照答题。
                                // 用一次性标志而非 3s 时间窗口，避免「开启后 3s 内的第一次按键」被误过滤。
                                quizResumeCooling = true
                                appScope.launch {
                                    delay(3000)
                                    quizResumeCooling = false
                                }
                                // SDK 的 appStart 内部会用传入 cbk 覆盖 setCXRGlassAppCbk，
                                // 这里重新注册「按键答题」的 resume 监听，恢复短按触发拍照答题
                                registerKeyQuizResumeListener(link)
                                val caps = Caps()
                                AiChannel.encodeQuizConfig(enabled).forEach { caps.write(it) }
                                val result = link.sendCustomCmd(AiChannel.TOPIC_KEY_QUIZ, caps)
                                Log.i(TAG, "sendCustomCmd(${AiChannel.TOPIC_KEY_QUIZ}, enabled=$enabled) -> $result")
                                deliver(result == 0)
                            } else {
                                Log.w(TAG, "appStart failed, cannot send quiz config")
                                deliver(false)
                            }
                            completeActiveOperation()
                            onBusyChanged(false)
                        }
                    ))
                },
                onFailure = {
                    cleanup()
                    onBusyChanged(false)
                    deliver(false)
                },
            ),
        )
    }

    /**
     * 补齐 SDK 内部权限列表。
     *
     * 我们通过 ComponentName 直接打开 AuthorizationActivity 完成授权（避免 ContentProvider
     * 查询在 Android 15 上受限），绕过了 AuthorizationHelper.requestAuthorization()。
     * 而 SDK 的 takePhoto()/startAudioStream() 会检查静态权限数组 AuthorizationHelper.b，
     * 该数组只在 requestAuthorization() 中填充，绕过后恒为空，导致拍照/录音被拒。
     * 因此授权成功（或恢复 token）后需手动补齐。
     */
    private fun grantGlassPermissions() {
        runCatching {
            val clazz = AuthorizationHelper::class.java
            // 静态权限数组 b：Kotlin 无法直接访问该单字母字段名，用反射设置
            val field = clazz.getDeclaredField("b")
            field.isAccessible = true
            field.set(
                null,
                arrayOf(
                    GlassPermission.MICROPHONE,
                    GlassPermission.CAMERA,
                    GlassPermission.MEDIA,
                ),
            )
            // 静态标志 c：hasGlassPermission 要求 c==true 才放行，
            // 进程重启后 c 重置为 false（仅授权回调 parseAuthorizationResult 会置 true）
            val flagField = clazz.getDeclaredField("c")
            flagField.isAccessible = true
            flagField.setBoolean(null, true)
            Log.i(TAG, "glass permissions granted: MICROPHONE/CAMERA/MEDIA, flag=true")
        }.onFailure { e ->
            Log.e(TAG, "grant glass permissions failed", e)
        }
    }

    init {
        // 从 SharedPreferences 恢复之前保存的授权令牌
        runCatching {
            val prefs = appContext.getSharedPreferences(PREFS_NAME, 0)
            prefs.getString(tokenPrefKey(hostApp), null)?.takeIf { it.isNotBlank() }?.let {
                token = it
            }
        }
        // 已有 token 时补齐 SDK 内部权限列表，确保 takePhoto()/startAudioStream() 可用
        if (!token.isNullOrBlank()) {
            grantGlassPermissions()
        }
        // 初始化时通知连接状态（含授权状态），触发 checkRokidLinkInstallation() 等依赖连接状态的回调
        notifyConnectionChanged()
    }

    fun hasAuthorization(): Boolean = !token.isNullOrBlank()
    
    fun getToken(): String? = token

    fun ensureGlassesOperationReady(): Boolean {
        return hasGlassesOperationPrerequisites(hostApp, requestAuthorizationIfMissing = true)
    }

    fun selectHostApp(nextHostApp: RokidHostApp) {
        if (hostApp == nextHostApp) return
        cleanup()
        token = null
        hostApp = nextHostApp
        // 尝试加载新 hostApp 之前保存的令牌
        runCatching {
            val prefs = appContext.getSharedPreferences(PREFS_NAME, 0)
            prefs.getString(tokenPrefKey(hostApp), null)?.takeIf { it.isNotBlank() }?.let {
                token = it
            }
        }
        // 切换 hostApp 后同样补齐权限列表
        if (!token.isNullOrBlank()) {
            grantGlassPermissions()
        }
        notifyConnectionChanged()
    }

    fun isHostAppInstalled(targetHostApp: RokidHostApp = hostApp): Boolean {
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.packageManager.getPackageInfo(targetHostApp.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                appContext.packageManager.getPackageInfo(targetHostApp.packageName, 0)
            }
        }.isSuccess
    }

    fun requestAuthorization() {
        val targetHostApp = hostApp
        if (!isHostAppInstalled(targetHostApp)) {
            onStatus(appContext.getString(R.string.install_glasses_host_first, targetHostApp.displayName))
            return
        }

        val launchIntent = runCatching {
            Intent().setComponent(ComponentName(targetHostApp.packageName, AUTH_ACTIVITY_CLASS))
        }.getOrElse {
            Intent(AUTH_ACTION).setPackage(targetHostApp.packageName)
        }
        val launcher = authLauncher
        if (launcher != null) {
            launcher(launchIntent)
            onStatus(appContext.getString(R.string.auth_page_opened, targetHostApp.displayName))
        } else {
            // 兜底：使用已废弃的 startActivityForResult（无现代 Launcher 时）；
            // Activity 已被回收（弱引用失效）时无法跳授权页，仅提示
            val currentActivity = activityRef.get()
            if (currentActivity == null || currentActivity.isDestroyed || currentActivity.isFinishing) {
                onStatus(appContext.getString(R.string.authorize_in_host, targetHostApp.displayName))
                return
            }
            @Suppress("DEPRECATION")
            currentActivity.startActivityForResult(launchIntent, 4027)
            onStatus(appContext.getString(R.string.auth_page_opened, targetHostApp.displayName))
        }
    }

    fun handleAuthorizationResult(resultCode: Int, data: Intent?) {
        when (val result = AuthorizationHelper.parseAuthorizationResult(resultCode, data)) {
            is AuthResult.AuthSuccess -> {
                token = result.token
                // 补齐 SDK 内部权限列表（绕过 requestAuthorization 直接授权导致 b 数组为空）
                grantGlassPermissions()
                // 持久化保存授权令牌，Activity 重建（如切换语言）后可恢复
                runCatching {
                    appContext.getSharedPreferences(PREFS_NAME, 0)
                        .edit()
                        .putString(tokenPrefKey(hostApp), result.token)
                        .apply()
                }
                onStatus(appContext.getString(R.string.auth_token_obtained, hostApp.displayName))
                notifyConnectionChanged()
            }

            is AuthResult.AuthCancel -> {
                token = null
                runCatching {
                    appContext.getSharedPreferences(PREFS_NAME, 0)
                        .edit()
                        .remove(tokenPrefKey(hostApp))
                        .apply()
                }
                onStatus(appContext.getString(R.string.auth_cancelled, hostApp.displayName))
                notifyConnectionChanged()
            }

            is AuthResult.AuthFail -> {
                token = null
                runCatching {
                    appContext.getSharedPreferences(PREFS_NAME, 0)
                        .edit()
                        .remove(tokenPrefKey(hostApp))
                        .apply()
                }
                onStatus(appContext.getString(R.string.auth_failed_simple, hostApp.displayName))
                notifyConnectionChanged()
            }
        }
    }

    fun installApk(apkFile: File, onInstallResult: ((Boolean) -> Unit)? = null) {
        // 优先从 APK 头读取包名，兜底用文件名
        val packageName = runCatching { readPackageName(apkFile) }.getOrNull() ?: apkFile.name
        // 委托给指定包名重载，消除代码重复
        installApk(apkFile, packageName, onInstallResult)
    }

    /** 安装 APK（指定包名，绕过 APK 头读取——兼容部分国产手机 getPackageArchiveInfo 返回 null） */
    fun installApk(apkFile: File, packageName: String, onInstallResult: ((Boolean) -> Unit)? = null) {
        val targetHostApp = hostApp
        android.util.Log.i("CxrLInstall", "installApk: hostApp=$targetHostApp, packageName=$packageName, apkFile=$apkFile")
        if (!hasGlassesOperationPrerequisites(targetHostApp, requestAuthorizationIfMissing = true)) {
            android.util.Log.w("CxrLInstall", "installApk: prerequisites check FAILED (wifi=${isWifiEnabled()}, tokenBlank=${token.isNullOrBlank()})")
            onInstallResult?.invoke(false)
            return
        }
        val authToken = token.orEmpty()
        android.util.Log.i("CxrLInstall", "installApk: prerequisites OK, connecting... authToken=${authToken.take(8)}...")

        onBusyChanged(true)
        runCatching {
            onStatus(appContext.getString(R.string.detected_package, packageName))
            connectAndUpload(authToken, targetHostApp, packageName, apkFile, onInstallResult)
        }.onFailure { error ->
            android.util.Log.e("CxrLInstall", "installApk: exception: ${error.javaClass.simpleName}: ${error.message}")
            onStatus(appContext.getString(R.string.cxrl_failed_msg, error.message ?: error.javaClass.simpleName))
            onBusyChanged(false)
            onInstallResult?.invoke(false)
        }
    }

    fun launchApp(packageName: String, activityClass: String = ".MainActivity", sendCmdAfterLaunch: String? = null, onLaunchResult: ((Boolean) -> Unit)? = null) {
        val targetHostApp = hostApp
        if (!hasGlassesOperationPrerequisites(targetHostApp, requestAuthorizationIfMissing = true)) {
            onLaunchResult?.invoke(false)
            return
        }
        val authToken = token.orEmpty()

        onBusyChanged(true)
        connectAndLaunch(authToken, targetHostApp, packageName, activityClass, sendCmdAfterLaunch, onLaunchResult)
    }

    /**
     * 通过 SDK 自定义指令，将按键配置（短按/长按 → 应用包名+Activity）发送到眼镜端。
     * 眼镜端 RokidLink 的 KeyButtonService 接收后处理按键事件。
     */
    fun sendKeyButtonConfig(
        shortPkg: String,
        shortActivity: String,
        longPkg: String,
        longActivity: String,
        onResult: ((Boolean) -> Unit)? = null,
    ) {
        val targetHostApp = hostApp
        if (!hasGlassesOperationPrerequisites(targetHostApp, requestAuthorizationIfMissing = true)) {
            onResult?.invoke(false)
            return
        }
        val authToken = token.orEmpty()

        onBusyChanged(true)
        connectAndRunCustomAppOperation(
            authToken = authToken,
            targetHostApp = targetHostApp,
            operation = CxrAppOperation(
                packageName = "com.rokidlab.rokidlink",
                timeoutMillis = 10_000,
                timeoutMessage = appContext.getString(com.rokidlab.phone.R.string.key_btn_timeout),
                bindMessage = appContext.getString(com.rokidlab.phone.R.string.key_btn_binding),
                configureFailureMessage = appContext.getString(com.rokidlab.phone.R.string.key_btn_config_failed),
                bindFailureMessage = appContext.getString(com.rokidlab.phone.R.string.key_btn_bind_failed),
                showConnectionStatus = false,
                onReady = { link ->
                    // CUSTOMAPP 场景构建完成后 cxr-service 才会把自定义指令路由给眼镜端：
                    // 必须先 appStart 并等待 onOpenAppResult 成功，再 sendCustomCmd（与 sendKeyQuizConfig 一致）
                    val entryUri = "com.rokidlab.rokidlink.MainActivity"
                    link.appStart(entryUri, glassAppCallback(
                        onStart = { success ->
                            if (success) {
                                val caps = Caps()
                                AiChannel.encodeKeyConfig(shortPkg, shortActivity, longPkg, longActivity)
                                    .forEach { caps.write(it) }
                                val result = link.sendCustomCmd(AiChannel.TOPIC_KEY_CONFIG, caps)
                                val resultMsg = if (result == 0) "OK" else "error=$result"
                                onStatus(appContext.getString(com.rokidlab.phone.R.string.key_btn_sent, shortPkg, longPkg, resultMsg))
                                onResult?.invoke(result == 0)
                            } else {
                                Log.w(TAG, "appStart failed, cannot send key config")
                                onStatus(appContext.getString(com.rokidlab.phone.R.string.key_btn_send_failed))
                                onResult?.invoke(false)
                            }
                            completeActiveOperation()
                            onBusyChanged(false)
                        }
                    ))
                },
                onFailure = {
                    cleanup()
                    onBusyChanged(false)
                    onResult?.invoke(false)
                },
            ),
        )
    }

    /**
     * 通过 SDK 自定义指令，将 WiFi 凭证（SSID + 密码）发送到眼镜端。
     * 眼镜端系统服务（AssistServer）接收后自动连接 WiFi。
     * 等待 30 秒获取连接状态回调，支持超时和密码错误处理。
     */
    fun sendWifiConfig(
        ssid: String,
        password: String,
        onResult: ((Boolean, String?) -> Unit)? = null,
    ) {
        val targetHostApp = hostApp
        if (!hasGlassesOperationPrerequisites(targetHostApp, requestAuthorizationIfMissing = true)) {
            onResult?.invoke(false, "缺少前置条件")
            return
        }
        val authToken = token.orEmpty()

        onBusyChanged(true)
        connectAndRunCustomAppOperation(
            authToken = authToken,
            targetHostApp = targetHostApp,
            operation = CxrAppOperation(
                packageName = "com.rokidlab.rokidlink",
                timeoutMillis = 30_000,
                timeoutMessage = appContext.getString(com.rokidlab.phone.R.string.wifi_config_timeout),
                bindMessage = appContext.getString(com.rokidlab.phone.R.string.key_btn_binding),
                configureFailureMessage = appContext.getString(com.rokidlab.phone.R.string.wifi_config_failed),
                bindFailureMessage = appContext.getString(com.rokidlab.phone.R.string.key_btn_bind_failed),
                showConnectionStatus = false,
                onReady = { link ->
                    val json = """{"module":"setting","ssid":"$ssid","password":"$password","forceReconnect":true}"""
                    Log.i(TAG, "Sending WiFi config: mode=Wifi_Connect, json=$json")
                    
                    val caps = Caps()
                    caps.write("Wifi_Connect")
                    caps.write(json)
                    
                    var statusReceived = false
                    val timeoutHandler = android.os.Handler(android.os.Looper.getMainLooper())
                    
                    // WiFi 状态回执由统一指令监听（registerGlobalCmdListener）转发到此处
                    wifiStatusCallback = { statusJson ->
                        statusReceived = true
                        timeoutHandler.removeCallbacksAndMessages(null)
                        try {
                            Log.i(TAG, "Received Wifi_Connect_Status: $statusJson")
                            
                            val jsonObj = org.json.JSONObject(statusJson)
                            val code = jsonObj.getInt("code")
                            val status = jsonObj.getString("status")
                            
                            completeActiveOperation()
                            onBusyChanged(false)
                            
                            if (code == 0 && status == "CONNECTED") {
                                onStatus(appContext.getString(com.rokidlab.phone.R.string.wifi_config_success, ssid))
                                onResult?.invoke(true, null)
                            } else {
                                val errorMsg = jsonObj.optString("message", "连接失败，请检查密码")
                                onStatus(appContext.getString(com.rokidlab.phone.R.string.wifi_config_failed) + ": $errorMsg")
                                onResult?.invoke(false, errorMsg)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Parse Wifi_Connect_Status failed", e)
                            completeActiveOperation()
                            onBusyChanged(false)
                            onResult?.invoke(true, null)
                        }
                    }
                    
                    val result = link.sendCustomCmd("Wifi", caps)
                    Log.i(TAG, "sendCustomCmd(Wifi) -> $result")
                    
                    if (result != 0) {
                        timeoutHandler.removeCallbacksAndMessages(null)
                        completeActiveOperation()
                        onBusyChanged(false)
                        onResult?.invoke(false, "发送失败")
                    } else {
                        timeoutHandler.postDelayed({
                            if (!statusReceived) {
                                Log.w(TAG, "WiFi config timeout after 5s, assuming success")
                                completeActiveOperation()
                                onBusyChanged(false)
                                onResult?.invoke(true, null)
                            }
                        }, 5_000)
                    }
                },
                onFailure = {
                    cleanup()
                    onBusyChanged(false)
                    onResult?.invoke(false, "连接失败")
                },
            ),
        )
    }

    // ═══════════════════════════════════════════════════
    // AI 下行主链路：sendAiTextMessage / sendAiTextViaLink / 拍照答题 / ASR 上行去重与轮询
    // ═══════════════════════════════════════════════════

    /**
     * 【临时测试】通过 CXR-L SDK 发送文字指令到眼镜端 AssistServer。
     * 协议（反编译自 RokidSpriteAssistServer）：
     *   - topic = "Ai"
     *   - caps[0] = "ASR_Result"  (KEY_BLUETOOTH_AI_ASR_MESSAGE)
     *   - caps[1] = 文字内容 (String)
     * AssistServer 用 CXRServiceBridge.subscribe("Ai", ...) 全局订阅，
     * 理论上 RokidLab 在 CUSTOMAPP 会话内 sendCustomCmd("Ai", caps) 即可送达。
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
        Log.i(TAG, "sendAiTextMessage(\"$text\") called. cxrlConnected=$cxrlConnected, glassBtConnected=$glassBtConnected, cxrLink=${cxrLink != null}, token=${token?.take(8) ?: "null"}")

        // 抢占新一代际：让正在执行/排队的旧 Agent 任务让路（用户打断）
        val myGen = ++aiGenSeq

        // 快速路径: 如果 CXR 已连接且 link 可用，直接发送（跳过前置检查 + 重新 connect）
        val link = cxrLink
        if (cxrlConnected && glassBtConnected && link != null) {
            Log.i(TAG, "sendAiTextMessage: using existing CXRLink (fast path)")
            sendAiTextViaLink(link, text, onResult, onReply, contextText, interruptOfficialFirst, skipTtsAudioFinished, showAsrResult, localTakeover, instruction, recordHistory, onDelta, myGen)
            return
        }

        // 慢速路径: 需要先建立连接
        Log.i(TAG, "sendAiTextMessage: no active link, falling back to connectAndRun path")
        val targetHostApp = hostApp
        if (!hasGlassesOperationPrerequisites(targetHostApp, requestAuthorizationIfMissing = true)) {
            Log.w(TAG, "sendAiTextMessage: missing prerequisites")
            mainHandler.post { onResult?.invoke(false, "missing prerequisites") }
            return
        }
        val authToken = token.orEmpty()

        onBusyChanged(true)
        connectAndRunCustomAppOperation(
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
                    // onStatus/onBusyChanged 已线程安全，onReply 由调用方切主线程，onResult 内部 runOnUiThread。
                    appScope.launch(Dispatchers.IO) {
                        sendAiTextViaLink(l, text, onResult, onReply, contextText, interruptOfficialFirst, skipTtsAudioFinished, showAsrResult, localTakeover, instruction, recordHistory, onDelta, myGen)
                    }
                },
                onFailure = {
                    cleanup()
                    onBusyChanged(false)
                    mainHandler.post { onResult?.invoke(false, "connection failed") }
                },
            ),
        )
    }

    /**
     * 远程控制眼镜拍照，通过 IImageStreamCbk 回调获取 JPEG 图片字节。
     * 用于「拍照问 AI」：拍照 → 本地 OCR 识别 → 知识库检索 → DeepSeek 生成答案。
     *
     * @param width/height/quality 拍照参数（推荐 1024/768/80）
     * @param onPhoto 拍照成功，返回 JPEG 字节
     * @param onError 拍照失败原因
     */
    fun takeGlassesPhoto(
        width: Int = 1024,
        height: Int = 768,
        quality: Int = 80,
        onPhoto: (ByteArray) -> Unit,
        onError: (String) -> Unit,
    ) {
        Log.i(TAG, "takeGlassesPhoto($width,$height,$quality) called. cxrlConnected=$cxrlConnected, glassBtConnected=$glassBtConnected, cxrLink=${cxrLink != null}")

        // 快速路径: 已有连接直接拍照，拍完保持连接（乐奇聊天可继续使用）
        val link = cxrLink
        if (cxrlConnected && glassBtConnected && link != null) {
            Log.i(TAG, "takeGlassesPhoto: fast path, using existing CXRLink")
            requestPhotoFromLink(link, width, height, quality, onPhoto, onError, cleanupOnDone = false)
            return
        }

        // 慢速路径: 先建立连接再拍照
        Log.i(TAG, "takeGlassesPhoto: no active link, falling back to connectAndRun path")
        val targetHostApp = hostApp
        if (!hasGlassesOperationPrerequisites(targetHostApp, requestAuthorizationIfMissing = true)) {
            Log.w(TAG, "takeGlassesPhoto: missing prerequisites")
            onError("missing prerequisites")
            return
        }
        val authToken = token.orEmpty()

        onBusyChanged(true)
        connectAndRunCustomAppOperation(
            authToken = authToken,
            targetHostApp = targetHostApp,
            operation = CxrAppOperation(
                packageName = "com.rokidlab.rokidlink",
                timeoutMillis = 20_000,
                timeoutMessage = "photo request timeout",
                bindMessage = "Taking photo",
                configureFailureMessage = "Configure CXR-L CUSTOMAPP session failed",
                bindFailureMessage = "Bind host service failed",
                showConnectionStatus = false,
                onReady = { l ->
                    // cleanupOnDone=false + resetBusyOnDone=true：拍照完成后保持 CXR 链路复用
                    // （避免每次按键重建连接 5-10s，导致「按键后出答案慢」），同时复位 busy 状态；
                    // 后续按键/聊天直接走 fast path
                    requestPhotoFromLink(l, width, height, quality, onPhoto, onError, cleanupOnDone = false, resetBusyOnDone = true)
                },
                onFailure = {
                    cleanup()
                    onBusyChanged(false)
                    onError("connection failed")
                },
            ),
        )
    }

    /** 「拍照问 AI」全流程入口（无显式回调，用聊天界面注册的默认回调；编排见 PhotoQuizFlow.start） */
    fun startPhotoAsk() = photoQuiz.start()

    /** 「拍照问 AI」全流程入口（显式回调；编排见 PhotoQuizFlow.start） */
    fun startPhotoAsk(
        onStage: (Int) -> Unit,
        onText: (String) -> Unit,
        onReply: (String) -> Unit,
    ) = photoQuiz.start(onStage, onText, onReply)

    /**
     * 安全切回主线程执行 UI 回调：Activity 已销毁（保活后台运行）时直接跳过，
     * 避免在已销毁 Activity 上调用 runOnUiThread 导致崩溃，同时下行链路不受影响。
     */
    private fun safeRunOnUiThread(block: () -> Unit) {
        try {
            // Activity 已销毁（保活后台运行）或已被回收（弱引用失效）时直接跳过，
            // 避免在已销毁 Activity 上执行 UI 回调导致崩溃，同时下行链路不受影响。
            val currentActivity = activityRef.get()
            if (currentActivity == null || currentActivity.isDestroyed || currentActivity.isFinishing) return
            mainHandler.post {
                try {
                    block()
                } catch (e: Exception) {
                    Log.e(TAG, "ui callback error", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "safeRunOnUiThread error", e)
        }
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
    private fun dispatchGlassesAsrText(text: String) {
        // 标记「本条 ASR 处理中」，供 AsrBridgeCoordinator 判定后续相同文字是否丢弃。
        // sendAiTextMessage 是异步回调式：onResult 在 AI 下行结束（成功/失败）时回调，
        // 此处据此复位 handling；AsrBridgeCoordinator 内还有 90s 超时兜底，防异常路径标志卡死。
        asrBridge.markAsrHandling(true)
        try {
            Log.i(TAG, "dispatchGlassesAsrText: $text")
            // 用户提问同步到聊天窗口（轮询在 IO 线程，需切回主线程更新 Compose 状态）
            safeRunOnUiThread { glassesAiTextCb(text) }
            sendAiTextMessage(
                text,
                onResult = { success, err ->
                    Log.i(TAG, "handleGlassesAiAsrText sendAiTextMessage: success=$success err=$err")
                    asrBridge.markAsrHandling(false)
                },
                onReply = { reply ->
                    Log.i(TAG, "handleGlassesAiAsrText reply: ${reply.take(40)}")
                    // Lab 回复同步到聊天窗口（onReply 在子线程回调，需切回主线程）
                    safeRunOnUiThread { glassesAiReplyCb(reply) }
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
            asrBridge.markAsrHandling(false)
        }
    }

    private fun requestPhotoFromLink(
        link: CXRLink,
        width: Int,
        height: Int,
        quality: Int,
        onPhoto: (ByteArray) -> Unit,
        onError: (String) -> Unit,
        cleanupOnDone: Boolean,
        /**
         * 完成后是否复位 busy（fast path 未设 busy 时为 false，避免多余 UI 刷新；
         * slow path 保持连接时需显式复位 busy）
         */
        resetBusyOnDone: Boolean = cleanupOnDone,
    ) {
        var done = false
        fun finish(onResult: () -> Unit) {
            if (done) return
            done = true
            photoRequestTimeoutJob?.cancel()
            photoRequestTimeoutJob = null
            completeActiveOperation()
            if (cleanupOnDone) {
                cleanup()
                onBusyChanged(false)
            } else if (resetBusyOnDone) {
                // 保持连接复用（避免每次按键重建 CXR 链路）：复位 busy 但不断开 cxrLink
                onBusyChanged(false)
            }
            onResult()
        }

        // 超时兜底：takePhoto 返回成功但 onImageReceived/onImageError 永不到达
        // （SDK 静默失败/眼镜端场景被关闭）时，复位标志并回调错误，避免后续拍照被永久跳过
        photoRequestTimeoutJob?.cancel()
        photoRequestTimeoutJob = appScope.launch {
            delay(15_000)
            mainHandler.post {
                if (!done) {
                    Log.w(TAG, "requestPhotoFromLink: no image callback within 15s, forcing error")
                    finish { onError("photo timeout") }
                }
            }
        }

        link.setCXRImageCbk(object : IImageStreamCbk {
            override fun onImageReceived(data: ByteArray) {
                Log.i(TAG, "onImageReceived: ${data.size} bytes")
                mainHandler.post { finish { onPhoto(data) } }
            }

            override fun onImageError(code: Int, message: String) {
                Log.e(TAG, "onImageError($code): $message")
                mainHandler.post { finish { onError("photo error($code): $message") } }
            }
        })

        val ok = link.takePhoto(width, height, quality)
        Log.i(TAG, "takePhoto -> $ok")
        if (!ok) {
            mainHandler.post { finish { onError("takePhoto failed") } }
        }
    }

    /**
     * 发送过程中校验 CXRLink 是否仍然有效（未被 cleanup 断开/替换）。
     * 无效时复位状态并回调失败，返回 false 供调用方中止发送，
     * 避免用已断开的 link 调 sendCustomCmd 导致 SDK native 崩溃。
     */
    private fun abortAiSendIfLinkInvalid(link: CXRLink, onResult: ((Boolean, String?) -> Unit)?): Boolean {
        if (cxrLink === link && cxrlConnected) return true
        Log.w(TAG, "sendAiTextViaLink: link stale/closed, abort send")
        mainHandler.post {
            completeActiveOperation()
            onBusyChanged(false)
            onResult?.invoke(false, "link disconnected")
        }
        return false
    }

    /**
     * 工具执行期间向眼镜推送进度提示（如「正在查询眼镜电量…」）。
     * 仅更新 AI 会话显示文字，不触发语音播报；最终回复的 TTS_Result 会覆盖该文字。
     * 按条加锁（aiCmdLock）与下行主链路串行；失败静默——进度提示是增强体验，不能影响主流程。
     */
    private fun sendGlassesProgress(link: CXRLink, text: String) {
        if (cxrLink !== link || !cxrlConnected) return
        runCatching {
            synchronized(aiCmdLock) {
                val caps = Caps()
                caps.write("TTS_Result")
                caps.write(text)
                link.sendCustomCmd("Ai", caps)
            }
        }
        onStatus(text)
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
    private fun sendAiTextViaLink(
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
        // cleanup() 的 disconnect 产生竞态（SDK native 崩溃）。加锁保证同一时刻只有
        // 一条 AI 下行链路执行，并在发送过程中持续校验 link 有效性。
        synchronized(aiSendLock) {
        // 入口校验：link 必须仍是最新且未断开（防止慢速路径 cleanup 后使用旧 link）
        if (!abortAiSendIfLinkInvalid(link, onResult)) return
        // 排队期间已有更新的请求进入（用户再次说话/发消息）：本请求作废，让出链路并复位调用方，
        // 避免过期任务抢到锁后继续跑多轮工具（用户新请求正在等待接管）
        if (generation != aiGenSeq) {
            Log.i(TAG, "sendAiTextViaLink superseded by newer request, abort (gen=$generation != latest=$aiGenSeq)")
            mainHandler.post {
                completeActiveOperation()
                onBusyChanged(false)
                onResult?.invoke(false, null)
            }
            return
        }
        onBusyChanged(true)

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
                val exitResult = link.sendCustomCmd("Ai", exitCaps)
                Log.i(TAG, "sendCustomCmd(Ai, Exit) interrupt official -> $exitResult")
                Thread.sleep(300)
            }
        }

        // ===== 步骤3 提前并行：后台线程调用 AI 获取回复（与下行显示并行，省 1.5~2s）=====
        // 支持工具调用（function calling）：AI 可自主决定调用本地能力（如知识库检索），
        // 执行结果回填后再生成最终回复；最终回复照常走下方 TTS 链路到眼镜显示并语音播报。
        val replyRef = java.util.concurrent.atomic.AtomicReference<String>("")
        val deepSeekThread = Thread {
            val tGenStart = System.currentTimeMillis()
            try {
                val cfg = getAiConfig()
                // 本地 Ollama 端点：首次加载大模型/思考模型首字远慢于远程，读超时放宽到 3 分钟
                val localBase = cfg.baseUrl.contains("127.0.0.1") || cfg.baseUrl.contains("localhost") ||
                    cfg.baseUrl.contains("11434")
                // 本地用户自定义请求参数（JSON，替代原「深度思考」布尔开关）：逐字段合并进每次
                // 本地对话请求体（如 {"think": false, "options": {"num_ctx": 2048}}）；远程服务不附加
                val extraBody = if (localBase) parseLocalChatParams() else null
                val service = com.rokidlab.phone.ai.OpenAiService(
                    cfg.apiKey, cfg.model, cfg.baseUrl,
                    readTimeoutMs = if (localBase) 180000 else 30000,
                    extraBody = extraBody,
                    // 发送键旁「思考」开关：仅在线 DeepSeek V4/V3.2 生效；本地模型由 extraBody 自行调参
                    thinkingEnabled = !localBase && isThinkingEnabled(),
                )
                // Agent 会话记忆：超时清理 + 注入历史消息（多轮上下文），使 AI 能理解「再来一首」等指代
                val agentSession = com.rokidlab.phone.ai.AgentSessionManager
                agentSession.maybeExpire()
                // 同时校验 AgentSessionManager 开关，关闭时本次不注入历史也不记录本轮
                val memoryEnabled = agentSession.isEnabled(appContext)
                val effectiveRecord = recordHistory && memoryEnabled
                // 长期记忆：跨会话记住用户事实/偏好（注入 <memories> + 注册 manage_memory 工具）
                val longTermMemory = com.rokidlab.phone.ai.LongTermMemoryManager
                val longTermOn = longTermMemory.isEnabled(appContext)
                // 检索式注入：按当前提问相关性取 top-K 长期记忆（无相关性时回退最近 K 条）
                val longTermContext = if (longTermOn) longTermMemory.memoriesContext(appContext, text) else null
                val messages = JSONArray()
                // 本地模型 → 本地轻量会话：不装配工具/技能/长期记忆工具，精简人设，仅闲聊问答。
                // 本地小模型背不动全部工具 Schema（每轮全量下发拖慢 prefill 且小模型调用工具不可靠），
                // 设备操作/联网等能力由用户切回在线 Agent 提供（对齐 RikkaHub 按会话装配思路）。
                val localLight = localBase
                // 用户自定义技能：注入技能清单（第 1 层渐进披露）+ 注册 load_skill 伪工具（仅在线 Agent）
                val skillsContext = if (!localLight && com.rokidlab.phone.ai.SkillRegistry.isEnabled(appContext)) {
                    com.rokidlab.phone.ai.SkillRegistry.skillsContext(appContext)
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
                // AIUI 子集，每轮少发 ~14 个无关工具 Schema（省 input token、加快 prefill）。
                // buildTools 按域装配，并附上仅在线 Agent 的长期记忆 manage_memory 与技能
                // load_skill/load_skill_section 两个动态伪工具；切换子集时复用同一装配逻辑。
                val buildTools: (Set<String>) -> MutableList<JSONObject> = { domains ->
                    ToolRegistry.schemasFor(appContext, domains).toMutableList().apply {
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
                    var lastError: Exception? = null
                    repeat(2) { attempt ->
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
                                    longTermMemory.execute(appContext, tc.arguments)
                                com.rokidlab.phone.ai.SkillRegistry.TOOL_NAME ->
                                    com.rokidlab.phone.ai.SkillRegistry.execute(appContext, tc.arguments)
                                com.rokidlab.phone.ai.SkillRegistry.TOOL_NAME_SECTION ->
                                    com.rokidlab.phone.ai.SkillRegistry.executeSection(appContext, tc.arguments)
                                else -> ToolRegistry.execute(appContext, tc.name, tc.arguments)
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
                // 空轮打断：连续 2 个空轮后注入一条硬引导（只注入一次），阻止推理模型"只思考不出手"
                var emptyNudgeSent = false
                // AIUI 生成场景标志：命中 load_skill(aiui-dev)/save_code_file 后置位，并把 tools 切到
                // 精简 AIUI 子集（SESSION_AIUI_DOMAINS），仅切换一次
                var aiuiMode = false
                // AIUI/代码生成回合收敛标记：本轮是否执行过 save_code_file，是则最终回复只允许简短结论
                var codeGenUsed = false
                // 记录各项目成功生成的源文件（project -> 文件名集合），供收敛时生成权威结论
                val genFilesByProject = LinkedHashMap<String, MutableSet<String>>()
                // 最多 6 轮工具循环：支持多步任务（先查时间再设定时等），同时防止模型反复请求工具导致死循环
                for (round in 0 until 6) {
                    // 用户打断（有更新代际的请求进入）：放弃后续生成，尽快让出 aiSendLock
                    if (generation != aiGenSeq) {
                        Log.i(TAG, "AI generation superseded at round=$round (gen=$generation), abort")
                        return@Thread
                    }
                    // 流式：实时推送 content 增量给 UI（工具调用轮 content 通常为空，最终回复轮逐字推送）；
                    // isCancelled 使 SSE 行间隙可感知打断并立即停止读取
                    val turn = service.chatTurnStream(
                        messages,
                        tools = activeTools,
                        onDelta = onDelta,
                        isCancelled = { generation != aiGenSeq },
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
                        // 空轮（既无工具也无文本）：推理模型可能在反复"思考但不落子"。
                        // 实测每轮空转 60-75s，代价极高：首轮空即注入硬引导打断（此前等 round>=2
                        // 会白耗 ~2 轮），若进入 AIUI 代码生成模式则给出具体的分文件落盘指令。
                        if (round >= 5) {
                            reply = ""
                            break
                        }
                        if (round >= 1 && !emptyNudgeSent) {
                            emptyNudgeSent = true
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
                        }
                        Log.i(TAG, "chatTurnStream empty turn round=$round, one more stream round")
                        continue
                    }
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
                        // 工具输出截断：dumpsys/df 等可能返回超长文本，全量回填浪费 token 且易超模型上下文。
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
                    // 用户已打断：跳过非流式兜底请求，直接放弃
                    if (generation != aiGenSeq) {
                        Log.i(TAG, "AI summary superseded (gen=$generation), skip final chat")
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
                        // 兜底总结轮同样支持进入 AIUI 精简工具子集（省 token），下一轮重试即生效
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
                // 用户打断导致的终止（chatTurnStream 被取消 / 轮间 return 前的异常）：
                // 静默退出，不覆盖 replyRef（保持空），也避免误播"服务不可用"
                if (generation != aiGenSeq) {
                    Log.i(TAG, "AI generation aborted by user interrupt (gen=$generation): ${e.message}")
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
            synchronized(aiCmdLock) {
                if (!abortAiSendIfLinkInvalid(link, onResult)) return
                keyDownResult = link.sendCustomCmd("Ai", keyDownCaps)
            }
            Log.i(TAG, "sendCustomCmd(Ai, KeyDown_Client, privacy_level=2) -> $keyDownResult")
            Thread.sleep(600)

            // 0b. 发送 Ai + open：眼镜端 AIOpenHandler 调用 startNewTalk()，开启 AI 对话
            val openCaps = Caps()
            openCaps.write("open")
            synchronized(aiCmdLock) {
                if (!abortAiSendIfLinkInvalid(link, onResult)) return
                openResult = link.sendCustomCmd("Ai", openCaps)
            }
            Log.i(TAG, "sendCustomCmd(Ai, open) -> $openResult")
            Thread.sleep(400)

            // ===== 步骤1: 发送 ASR_Result（用户文字）=====
            // 语音唤醒链路（showAsrResult=false）：官方 ASR 已在眼镜上显示提问，不再重发避免重复显示。
            if (showAsrResult) {
                val asrCaps = Caps()
                asrCaps.write("ASR_Result")
                asrCaps.write(text)
                synchronized(aiCmdLock) {
                    if (!abortAiSendIfLinkInvalid(link, onResult)) return
                    asrResult = link.sendCustomCmd("Ai", asrCaps)
                }
                Log.i(TAG, "sendCustomCmd(Ai, ASR_Result, \"$text\") -> $asrResult")
            } else {
                Log.i(TAG, "skip ASR_Result resend (voice wakeup chain, question already shown)")
            }

            // ===== 步骤2: 发送 ASR_End（标记 ASR 结束）=====
            val endCaps = Caps()
            endCaps.write("ASR_End")
            synchronized(aiCmdLock) {
                if (!abortAiSendIfLinkInvalid(link, onResult)) return
                endResult = link.sendCustomCmd("Ai", endCaps)
            }
            Log.i(TAG, "sendCustomCmd(Ai, ASR_End) -> $endResult")
            onStatus("已发送到眼镜，正在获取 AI 回复...")
        } else {
            Log.i(TAG, "localTakeover: skip KeyDown/open/ASR_Result/ASR_End (glasses already shown)")
            onStatus("正在获取 AI 回复...")
        }

        // ===== 等待 DeepSeek 完成（下行显示期间已并行执行）=====
        deepSeekThread.join()
        reply = replyRef.get()
        // 生成期间用户已发起新请求（新语音/新消息）：本回复已过期，放弃显示/播报/回调，
        // 复位状态并把链路让给新请求（join 期间 deepSeekThread 已提前退出，等待有界）
        if (generation != aiGenSeq) {
            Log.i(TAG, "sendAiTextViaLink: reply superseded during generation (gen=$generation != $aiGenSeq), skip downlink")
            mainHandler.post {
                completeActiveOperation()
                onBusyChanged(false)
                onResult?.invoke(false, null)
            }
            return
        }
        Log.i(TAG, "AI reply ready: ${reply.take(40)}")
        onReply?.invoke(reply)
        } catch (e: Exception) {
            // 下行指令段（sendCustomCmd/sleep/join）异常：统一复位状态，避免 sending/busy 永久卡死
            Log.e(TAG, "AI send downlink failed", e)
            mainHandler.post {
                completeActiveOperation()
                onBusyChanged(false)
                onResult?.invoke(false, "AI send error: ${e.message}")
            }
            return
        }

        // ===== 步骤4: 发送 TTS_Result（AI 回复）到眼镜 =====
        try {
            // join 等待 DeepSeek 期间可能发生 cleanup 断开/替换 link，发送前重新校验
            if (!abortAiSendIfLinkInvalid(link, onResult)) return
            // 官方协议: caps[0] = "TTS_Result", caps[1] = 回复文字
            val ttsCaps = Caps()
            ttsCaps.write("TTS_Result")
            ttsCaps.write(reply)
            val ttsResult = link.sendCustomCmd("Ai", ttsCaps)
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
                link.sendCustomCmd("Ai", finishCaps)
                Log.i(TAG, "TTS_AudioFinished sent")
            } else {
                Log.i(TAG, "skipTtsAudioFinished=true: TTS_AudioFinished not sent (keep Lab reply visible)")
            }

            mainHandler.post {
                onStatus("AI 回复已发送: \"${reply.take(30)}...\"")
                completeActiveOperation()
                onBusyChanged(false)
                onResult?.invoke((if (showAsrResult) (asrResult ?: -1) == 0 else true) && endResult == 0 && ttsResult == 0, null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "AI reply send failed", e)
            mainHandler.post {
                onStatus("AI 回复失败: ${e.message}")
                completeActiveOperation()
                onBusyChanged(false)
                onResult?.invoke(false, "AI send error: ${e.message}")
            }
        }
        }
    }

    // ═══════════════════════════════════════════════════
    // App 管理对外入口与 connectAnd* 应用操作（stop / uninstall / query / upload / launch）
    // ═══════════════════════════════════════════════════

    fun stopApp(packageName: String, onStopResult: ((Boolean) -> Unit)? = null) {
        val targetHostApp = hostApp
        if (!hasGlassesOperationPrerequisites(targetHostApp, requestAuthorizationIfMissing = true)) {
            onStopResult?.invoke(false)
            return
        }
        val authToken = token.orEmpty()

        onBusyChanged(true)
        connectAndStop(authToken, targetHostApp, packageName, onStopResult)
    }

    fun uninstallApp(packageName: String, onUninstallResult: ((Boolean) -> Unit)? = null) {
        val targetHostApp = hostApp
        if (!hasGlassesOperationPrerequisites(targetHostApp, requestAuthorizationIfMissing = true)) {
            onUninstallResult?.invoke(false)
            return
        }
        val authToken = token.orEmpty()

        onBusyChanged(true)
        connectAndUninstall(authToken, targetHostApp, packageName, onUninstallResult)
    }

    fun queryInstalledApps(
        packageNames: List<String>,
        onResult: (String, Boolean) -> Unit,
        onComplete: () -> Unit,
    ) {
        val targetHostApp = hostApp
        if (packageNames.isEmpty()) {
            onComplete()
            return
        }
        if (!hasGlassesOperationPrerequisites(targetHostApp, requestAuthorizationIfMissing = true)) {
            onComplete()
            return
        }
        val authToken = token.orEmpty()

        // 如果已有查询在进行，追加包名并替换回调，不清除已有连接
        if (queryQueue.isNotEmpty() && !operationStarted) {
            queryQueue.addAll(packageNames.filterNot(queryQueue::contains))
            onQueryResult = onResult
            onQueryComplete = onComplete
            return
        }

        cleanup()
        queryQueue = ArrayDeque(packageNames.distinct())
        onQueryResult = onResult
        onQueryComplete = onComplete
        onBusyChanged(true)
        queryNext(authToken, targetHostApp)
    }

    fun cleanup() {
        android.util.Log.i("CxrLInstall", "cleanup() called")
        timeoutJob?.cancel()
        timeoutJob = null
        asrBridge.stop()
        // 补漏：取消其余遗留协程任务（原实现漏取消导致后台任务残留）
        aiConfigPushJob?.cancel()
        aiConfigPushJob = null
        photoRequestTimeoutJob?.cancel()
        photoRequestTimeoutJob = null
        aiui.stopAgentListPushWindow()
        // 解除 Rokid 主机 App 服务绑定（applicationContext 绑定不随 Activity 销毁自动解绑）
        unbindAllHostServices()
        // 打断在途 AI：bump 代际号让 deepSeekThread 自弃（避免断连后仍空跑至超时），
        // 并趁链路尚在通知眼镜端停止播报；随后才断开连接
        abortCurrentAi()
        runCatching { cxrLink?.disconnect() }
        cxrLink = null
        // 断开共享 ADB 常驻连接（会话重建/操作失败清理时释放，避免隧道连接泄漏；
        // 下次工具调用经 getAdbShellClient 自动重建）
        runCatching { adbShellClient?.disconnect() }
        adbShellClient = null
        proxyRelay.closeAll()
        pendingOperation = null
        queryQueue.clear()
        cxrlConnected = false
        glassBtConnected = false
        operationStarted = false
        notifyConnectionChanged()
    }

    private fun connectAndUpload(
        authToken: String,
        targetHostApp: RokidHostApp,
        packageName: String,
        apkFile: File,
        onInstallResult: ((Boolean) -> Unit)?,
    ) {
        connectAndRunCustomAppOperation(
            authToken = authToken,
            targetHostApp = targetHostApp,
            operation = CxrAppOperation(
                packageName = packageName,
                timeoutMillis = 90_000,
                timeoutMessage = appContext.getString(R.string.waiting_install_result, targetHostApp.displayName),
                bindMessage = appContext.getString(R.string.binding_service, targetHostApp.displayName),
                configureFailureMessage = appContext.getString(R.string.cxrl_config_failed),
                bindFailureMessage = appContext.getString(R.string.service_bind_failed, targetHostApp.displayName, targetHostApp.displayName),
                showConnectionStatus = true,
                onReady = { link ->
                    android.util.Log.i("CxrLInstall", "connectAndUpload: onReady! Starting appUploadAndInstall...")
                    onStatus(appContext.getString(R.string.cxrl_ready_installing))
                    link.appUploadAndInstall(apkFile.absolutePath, glassAppCallback(
                        onInstall = { success ->
                            completeActiveOperation()
                            onStatus(if (success) appContext.getString(R.string.glasses_install_success) else appContext.getString(R.string.glasses_install_failed))
                            onBusyChanged(false)
                            onInstallResult?.invoke(success)
                        },
                    ))
                },
                onFailure = {
                    cleanup()
                    onBusyChanged(false)
                    onInstallResult?.invoke(false)
                },
            ),
        )
    }

    private fun queryNext(authToken: String, targetHostApp: RokidHostApp) {
        val packageName = queryQueue.pollFirst()
        if (packageName == null) {
            finishQueries()
            return
        }
        connectAndQuery(authToken, targetHostApp, packageName)
    }

    private fun connectAndQuery(authToken: String, targetHostApp: RokidHostApp, packageName: String) {
        connectAndRunCustomAppOperation(
            authToken = authToken,
            targetHostApp = targetHostApp,
            operation = CxrAppOperation(
                packageName = packageName,
                timeoutMillis = 30_000,
                timeoutMessage = appContext.getString(R.string.query_timeout, packageName),
                configureFailureMessage = appContext.getString(R.string.query_config_failed, packageName),
                bindFailureMessage = appContext.getString(R.string.service_bind_failed, targetHostApp.displayName, targetHostApp.displayName),
                onReady = { link ->
                    link.appIsInstalled(glassAppCallback(
                        onQuery = { installed ->
                            completeActiveOperation()
                            onQueryResult?.invoke(packageName, installed)
                            queryNext(authToken, targetHostApp)
                        },
                    ))
                },
                onFailure = {
                    onQueryResult?.invoke(packageName, false)
                    queryNext(authToken, targetHostApp)
                },
                onBindFailure = {
                    finishQueries()
                },
            ),
        )
    }

    private fun connectAndUninstall(
        authToken: String,
        targetHostApp: RokidHostApp,
        packageName: String,
        onUninstallResult: ((Boolean) -> Unit)?,
    ) {
        connectAndRunCustomAppOperation(
            authToken = authToken,
            targetHostApp = targetHostApp,
            operation = CxrAppOperation(
                packageName = packageName,
                timeoutMillis = 60_000,
                timeoutMessage = appContext.getString(R.string.uninstall_timeout, packageName),
                bindMessage = appContext.getString(R.string.binding_service, targetHostApp.displayName),
                configureFailureMessage = appContext.getString(R.string.uninstall_config_failed, packageName),
                bindFailureMessage = appContext.getString(R.string.service_bind_failed, targetHostApp.displayName, targetHostApp.displayName),
                showConnectionStatus = true,
                onReady = { link ->
                    onStatus(appContext.getString(R.string.cxrl_ready_uninstalling, packageName))
                    link.appUninstall(glassAppCallback(
                        onUninstall = { success ->
                            completeActiveOperation()
                            onStatus(if (success) appContext.getString(R.string.glasses_uninstall_success) else appContext.getString(R.string.glasses_uninstall_failed))
                            onBusyChanged(false)
                            onUninstallResult?.invoke(success)
                        },
                    ))
                },
                onFailure = {
                    cleanup()
                    onBusyChanged(false)
                    onUninstallResult?.invoke(false)
                },
            ),
        )
    }

    private fun connectAndLaunch(
        authToken: String,
        targetHostApp: RokidHostApp,
        packageName: String,
        activityClass: String,
        sendCmdAfterLaunch: String?,
        onLaunchResult: ((Boolean) -> Unit)?,
    ) {
        connectAndRunCustomAppOperation(
            authToken = authToken,
            targetHostApp = targetHostApp,
            operation = CxrAppOperation(
                packageName = packageName,
                timeoutMillis = 30_000,
                timeoutMessage = appContext.getString(R.string.launch_timeout, packageName),
                bindMessage = appContext.getString(R.string.binding_service, targetHostApp.displayName),
                configureFailureMessage = appContext.getString(R.string.launch_config_failed, packageName),
                bindFailureMessage = appContext.getString(R.string.service_bind_failed, targetHostApp.displayName, targetHostApp.displayName),
                showConnectionStatus = true,
                onReady = { link ->
                    onStatus(appContext.getString(R.string.cxrl_ready_launching, packageName))
                    // 文档要求 appStart 使用 "${packageName}${activityClassName}" 格式
                    val entryUri = "$packageName$activityClass"
                    link.appStart(entryUri, object : IGlassAppCbk {
                        override fun onInstallAppResult(success: Boolean) = Unit
                        override fun onUnInstallAppResult(success: Boolean) = Unit
                        override fun onOpenAppResult(success: Boolean) {
                            if (success) {
                                // 置一次性冷却标志：appStart 后眼镜端 RokidLink 会真实 resume，
                                // 触发 Sys_App_Resume_Change 上行，消费该 resume 避免误触发拍照答题
                                quizResumeCooling = true
                                appScope.launch {
                                    delay(3000)
                                    quizResumeCooling = false
                                }
                                // SDK 的 appStart 内部会用传入 cbk 覆盖 setCXRGlassAppCbk，
                                // 重新注册以恢复 onGlassAppResume 回调（按键答题 + ASR 打断信号都依赖它）
                                registerKeyQuizResumeListener(link)
                                if (sendCmdAfterLaunch != null) {
                                    // 眼镜端已启动，发送自定义命令触发自动操作
                                    val cmdResult = link.sendCustomCmd(sendCmdAfterLaunch, Caps())
                                    onStatus(appContext.getString(R.string.cmd_result, sendCmdAfterLaunch, cmdResult))
                                }
                            }
                            completeActiveOperation()
                            onStatus(if (success) appContext.getString(R.string.glasses_launch_success, packageName) else appContext.getString(R.string.glasses_launch_failed, packageName))
                            onBusyChanged(false)
                            onLaunchResult?.invoke(success)
                        }
                        override fun onStopAppResult(success: Boolean) = Unit
                        override fun onGlassAppResume(resumed: Boolean) = Unit
                        override fun onQueryAppResult(installed: Boolean) = Unit
                    })
                },
                onFailure = {
                    cleanup()
                    onBusyChanged(false)
                    onLaunchResult?.invoke(false)
                },
            ),
        )
    }

    private fun connectAndStop(
        authToken: String,
        targetHostApp: RokidHostApp,
        packageName: String,
        onStopResult: ((Boolean) -> Unit)?,
    ) {
        connectAndRunCustomAppOperation(
            authToken = authToken,
            targetHostApp = targetHostApp,
            operation = CxrAppOperation(
                packageName = packageName,
                timeoutMillis = 30_000,
                timeoutMessage = appContext.getString(R.string.stop_timeout, packageName),
                bindMessage = appContext.getString(R.string.binding_service, targetHostApp.displayName),
                configureFailureMessage = appContext.getString(R.string.stop_config_failed, packageName),
                bindFailureMessage = appContext.getString(R.string.service_bind_failed, targetHostApp.displayName, targetHostApp.displayName),
                showConnectionStatus = true,
                onReady = { link ->
                    onStatus(appContext.getString(R.string.cxrl_ready_stopping, packageName))
                    link.appStop(object : IGlassAppCbk {
                        override fun onInstallAppResult(success: Boolean) = Unit
                        override fun onUnInstallAppResult(success: Boolean) = Unit
                        override fun onOpenAppResult(success: Boolean) = Unit
                        override fun onStopAppResult(success: Boolean) {
                            completeActiveOperation()
                            onStatus(if (success) appContext.getString(R.string.glasses_stop_success, packageName) else appContext.getString(R.string.glasses_stop_failed, packageName))
                            onBusyChanged(false)
                            onStopResult?.invoke(success)
                        }
                        override fun onGlassAppResume(resumed: Boolean) = Unit
                        override fun onQueryAppResult(installed: Boolean) = Unit
                    })
                },
                onFailure = {
                    cleanup()
                    onBusyChanged(false)
                    onStopResult?.invoke(false)
                },
            ),
        )
    }

    // ═══════════════════════════════════════════════════
    // 连接编排内核 / pending 调度 / 全局指令监听 / bind 反射与授权前置检查
    // ═══════════════════════════════════════════════════

    private fun connectAndRunCustomAppOperation(
        authToken: String,
        targetHostApp: RokidHostApp,
        operation: CxrAppOperation,
    ) {
        cleanup()
        val link = CXRLink(appContext).also { newLink ->
            // 反射绕过 CXR-L SDK 的 cmd 黑名单（一次到位：连接建立时清理，
            // 避免过去在 sendAiTextViaLink 每条 AI 消息下行都重复反射一次的开销）
            bypassCmdBlacklist(newLink)
            newLink.setCXRLinkCbk(FullCXRLinkCallback(
                onConnected = { connected ->
                    mainHandler.post {
                        cxrlConnected = connected
                        if (operation.showConnectionStatus) onStatus(appContext.getString(R.string.cxrl_service_connected, connected.toString()))
                        if (connected) asrBridge.start() else asrBridge.stop()
                        notifyConnectionChanged()
                        maybeRunPendingOperation()
                    }
                },
                onBtConnected = { connected ->
                    mainHandler.post {
                        glassBtConnected = connected
                        if (operation.showConnectionStatus) onStatus(appContext.getString(R.string.bluetooth_connected_status, connected.toString()))
                        notifyConnectionChanged()
                        maybeRunPendingOperation()
                    }
                }
            ))
            cxrLink = newLink
            newLink
        }

        pendingOperation = operation
        cxrlConnected = false
        glassBtConnected = false
        operationStarted = false
        operationCompleted = false
        timeoutJob = appScope.launch {
            delay(operation.timeoutMillis)
            // 使用同步锁检查操作是否已完成，防止竞态条件
            synchronized(operationLock) {
                if (pendingOperation === operation && !operationCompleted) {
                    android.util.Log.e("CxrLInstall", "connectAndRun: TIMEOUT after ${operation.timeoutMillis}ms, cxrlConnected=$cxrlConnected, glassBtConnected=$glassBtConnected")
                    pendingOperation = null
                    operationStarted = false
                    onStatus(operation.timeoutMessage)
                    operation.onFailure()
                }
            }
        }

        val configured = link.configCXRSession(
            CxrDefs.CXRSession(CxrDefs.CXRSessionType.CUSTOMAPP, operation.packageName),
        )
        if (!configured) {
            android.util.Log.e("CxrLInstall", "connectAndRun: configCXRSession FAILED (package=${operation.packageName})")
            pendingOperation = null
            operationStarted = false
            operationCompleted = true
            onStatus(operation.configureFailureMessage)
            operation.onFailure()
            cleanup()
            return
        }

        operation.bindMessage?.let(onStatus)
        if (!bindRokidHostService(link, targetHostApp, authToken)) {
            android.util.Log.e("CxrLInstall", "connectAndRun: bindRokidHostService FAILED (hostApp=$targetHostApp)")
            pendingOperation = null
            operationStarted = false
            onStatus(operation.bindFailureMessage)
            operation.onBindFailure()
            cleanup()
        }
        android.util.Log.i("CxrLInstall", "connectAndRun: bindRokidHostService OK, waiting for connected+btConnected...")
        // 直接启动 AI 文字轮询（不依赖 onCXRLConnected：实测该回调在部分会话中不触发）
        asrBridge.start()
        // 连接建立后补发一次 AI 配置到眼镜端（setAiConfig 时可能尚未连接）
        pushAiConfigToGlass(getAiConfig())
    }

    private fun maybeRunPendingOperation() {
        synchronized(operationLock) {
            val operation = pendingOperation ?: return
            if (operationStarted || !cxrlConnected || !glassBtConnected) return
            val link = cxrLink ?: return
            operationStarted = true
            operationCompleted = false
            // 注册统一指令监听（眼镜按键拍照问AI / WiFi 连接状态等），再执行具体操作
            registerGlobalCmdListener(link)
            operation.onReady(link)
        }
    }

    /**
     * 注册「按键答题」的眼镜端 resume 监听（经 Sys 频道上行触发拍照答题）。
     *
     * 注意：SDK 的 [CXRLink.appStart] 内部会调用 setCXRGlassAppCbk(传入 cbk) 覆盖本回调，
     * 因此 appStart 成功后必须【再次调用本方法】恢复按键答题监听。
     */
    private fun registerKeyQuizResumeListener(link: CXRLink) {
        // 眼镜端 RokidLink app 的 resume 变化（Sys_App_Resume_Change 经 AI App 无条件转发）：
        // 按键答题开启时，短按镜腿按键 → 眼镜端模拟 Sys_App_Resume_Change 上行，
        // SDK 匹配 customAppPackage 后回调 onGlassAppResume(true)，据此触发拍照答题。
        // appStart 后的真实 resume 通过一次性冷却标志（quizResumeCooling）过滤。
        link.setCXRGlassAppCbk(object : IGlassAppCbk {
            override fun onGlassAppResume(resumed: Boolean) {
                val quiz = appContext.getSharedPreferences(AI_PREFS, 0)
                    .getBoolean(KEY_KEY_QUIZ_ENABLED, false)
                // 一次性冷却：消费 appStart 触发的真实 resume（不会再来第二次），
                // 之后任意时刻按键触发的 resume 不再被时间窗口误过滤（修复「开启后第一次按键没反应」）
                if (resumed && quizResumeCooling) {
                    quizResumeCooling = false
                    Log.i(TAG, "onGlassAppResume: consumed real resume after appStart")
                    return
                }
                Log.i(TAG, "onGlassAppResume: resumed=$resumed quiz=$quiz cooling=$quizResumeCooling")
                if (resumed) {
                    // 打断官方 AI 已改由「ASR_READY 信号」驱动（眼镜端收到官方 ASR_End 后经 RFCOMM
                    // 推送，见 startAiAsrBridgePolling 的 ASR_READY_MARKER 分支）：官方识别完成后
                    // 才打断，避免固定 800ms 提前打断导致官方识别被掐断、ASR_End 永不产生的竞态。
                    // 此回调不再承担打断职责。
                    // 注意：不再用 onGlassAppResume 触发拍照答题！
                    // SDK 的 onGlassAppResumeChange 按包名匹配回调，任何 RokidLink 的真实 resume
                    //（AI 会话切换、进程重启等）都会到达这里，无法与眼镜端按键模拟的
                    // Sys_App_Resume_Change 区分，曾导致「未按键却自动拍照」。
                    // 拍照意图已改走 RFCOMM 推送通道（PHOTO_ASK_MARKER）+ custom cmd（PHOTO_ASK_CMD）。
                }
            }

            override fun onInstallAppResult(success: Boolean) {}
            override fun onUnInstallAppResult(success: Boolean) {}
            override fun onOpenAppResult(success: Boolean) {}
            override fun onStopAppResult(success: Boolean) {}
            override fun onQueryAppResult(installed: Boolean) {}
        })
    }

    /**
     * 统一注册手机端收到的「眼镜 → 手机」指令监听。
     * 每个新建立的 CXRLink 都要注册一次，处理：
     *  - Wifi_Connect_Status：WiFi 连接状态回执（转发给 sendWifiConfig）
     *  - rokidlab_photo_ask：眼镜端镜腿按键触发「拍照问AI」
     */
    private fun registerGlobalCmdListener(link: CXRLink) {
        try {
            registerKeyQuizResumeListener(link)

            link.setCXRCustomCmdCbk { cmd, data ->
                Log.i(TAG, "onCustomCmdResult: cmd=$cmd, dataLen=${data?.size ?: 0}")
                when (cmd) {
                    "Wifi_Connect_Status" -> {
                        // 眼镜端可能借该白名单通道上行 ASR 文字（caps: ["ASR_TEXT", text]），
                        // 或上行普通 WiFi 状态回执 JSON（配 WiFi 时）。
                        val asrText = parseAiAsrPollText(data)
                        if (!asrText.isNullOrBlank()) {
                            Log.i(TAG, "Wifi_Connect_Status carrying ASR text: $asrText")
                            // SDK 回调在 binder 线程：handleGlassesAiAsrText 内含 sleep+join（最长 30s+），
                            // 必须切后台线程，否则独占 binder 线程池导致其他 SDK 回调延迟/超时
                            appScope.launch(Dispatchers.IO) { asrBridge.onAsrText(asrText) }
                        } else {
                            val json = String(data ?: ByteArray(0))
                            Log.i(TAG, "Received Wifi_Connect_Status: $json")
                            wifiStatusCallback?.invoke(json)
                        }
                    }
                    PHOTO_ASK_CMD -> {
                        Log.i(TAG, "Photo-ask triggered from glasses button")
                        // 拍照+OCR+AI 全流程耗时数秒，同样切后台线程执行
                        appScope.launch(Dispatchers.IO) { startPhotoAsk() }
                    }
                    AI_ASR_POLL_CMD -> {
                        val text = parseAiAsrPollText(data)
                        if (!text.isNullOrBlank()) {
                            Log.i(TAG, "AI ASR poll got text: $text")
                            appScope.launch(Dispatchers.IO) { asrBridge.onAsrText(text) }
                        }
                    }
                    "Proxy" -> {
                        // 眼镜端 AssistServer NetProxy 请求（Jsai 下载 .aix 时眼镜经手机代理拉文件）。
                        // 手机侧作为 TCP 中继应答：解析 Proxy_NetRequest → 本机 socket 收发 →
                        // 以 Proxy_NetResponse 应答（协议与状态机详见 GlassProxyRelay）。
                        proxyRelay.onInbound(data)
                    }
                    "Jsai_GetRequestInfo" -> {
                        // 眼镜发起「agent 目录配置询问」（phone_request_info 飞行，60s 内首个回复被认领：
                        // claimPhoneResponse → parseMobileRequestInfo → JsaiAuthStore.update → 用新 URL 重拉）。
                        // 仅在本地有托管目录时回复，避免无谓覆盖官方配置导致官方 agent 被 purge。
                        val catalogUrl = AiuiProject.currentCatalogUrl()
                        if (catalogUrl != null) {
                            val r = pushAiuiAgentListUrl(catalogUrl)
                            Log.i(TAG, "Jsai_GetRequestInfo asked -> replied catalogUrl=$catalogUrl result=$r")
                        } else {
                            Log.i(TAG, "Jsai_GetRequestInfo asked -> no hosted catalog, keep silent")
                        }
                    }
                    else -> {
                        Log.d(TAG, "Unhandled cmd: $cmd")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "registerGlobalCmdListener failed", e)
        }
    }

    private fun completeActiveOperation() {
        android.util.Log.i("CxrLInstall", "completeActiveOperation() called")
        synchronized(operationLock) {
            operationCompleted = true
            timeoutJob?.cancel()
            timeoutJob = null
            pendingOperation = null
            operationStarted = false
        }
    }

    private fun glassAppCallback(
        onInstall: (Boolean) -> Unit = {},
        onUninstall: (Boolean) -> Unit = {},
        onQuery: (Boolean) -> Unit = {},
        onStart: (Boolean) -> Unit = {},
    ): IGlassAppCbk = object : IGlassAppCbk {
        override fun onInstallAppResult(success: Boolean) {
            android.util.Log.i("CxrLInstall", "glassAppCallback: onInstallAppResult(success=$success)")
            mainHandler.post { onInstall(success) }
        }

        override fun onUnInstallAppResult(success: Boolean) {
            mainHandler.post { onUninstall(success) }
        }

        override fun onOpenAppResult(success: Boolean) {
            mainHandler.post { onStart(success) }
        }
        override fun onStopAppResult(success: Boolean) = Unit
        override fun onGlassAppResume(resumed: Boolean) = Unit

        override fun onQueryAppResult(installed: Boolean) {
            mainHandler.post { onQuery(installed) }
        }
    }

    private data class CxrAppOperation(
        val packageName: String,
        val timeoutMillis: Long,
        val timeoutMessage: String,
        val configureFailureMessage: String,
        val bindFailureMessage: String,
        val bindMessage: String? = null,
        val showConnectionStatus: Boolean = false,
        val onReady: (CXRLink) -> Unit,
        val onFailure: () -> Unit,
        val onBindFailure: () -> Unit = onFailure,
    )

    private fun finishQueries() {
        val complete = onQueryComplete
        cleanup()
        queryQueue.clear()
        onQueryResult = null
        onQueryComplete = null
        onBusyChanged(false)
        complete?.invoke()
    }

    /** 缓存反射获取的 ServiceConnection 字段，避免每次操作都反射遍历 */
    @Volatile
    private var cachedServiceConnectionField: java.lang.reflect.Field? = null

    private fun bindRokidHostService(link: CXRLink, targetHostApp: RokidHostApp, authToken: String): Boolean {
        val conn = findServiceConnection(link) ?: return false
        val ok = runCatching {
            val intent = Intent(MEDIA_SERVICE_ACTION)
                .setPackage(targetHostApp.packageName)
                .putExtra(AUTH_TOKEN_EXTRA, authToken)
                .putExtra(AUTH_PACKAGE_EXTRA, appContext.packageName)
            // 注意：applicationContext 绑定的连接不会随 Activity 销毁自动解绑，
            // 必须登记并在 cleanup() 统一 unbind，否则 ServiceConnection 泄漏
            appContext.bindService(intent, conn, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
        if (ok) boundConnections.add(conn)
        return ok
    }

    /** 已 bind 的 Rokid 主机 App ServiceConnection（cleanup 时统一 unbind，防泄漏） */
    private val boundConnections = java.util.concurrent.CopyOnWriteArrayList<ServiceConnection>()

    /**
     * 反射清空 CXR-L SDK 的 cmd 黑名单字段（混淆名 "d"，String[] 类型）。
     * 仅在 CXRLink 创建时调用一次；SDK 升级导致字段名变化时静默失败并记日志，
     * 不影响主链路（与旧实现每条消息重试的行为等价——字段名变了旧代码同样每条失败）。
     */
    private fun bypassCmdBlacklist(link: CXRLink) {
        try {
            val field = link.javaClass.superclass.getDeclaredField("d")
            field.isAccessible = true
            field.set(link, arrayOf<String>())
            Log.i(TAG, "CXR-L cmd blacklist bypassed (cleared at link creation)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bypass CXR-L cmd blacklist", e)
        }
    }

    /** 统一解除所有已登记的服务绑定 */
    private fun unbindAllHostServices() {
        if (boundConnections.isEmpty()) return
        // SDK 若已自行解绑，重复 unbind 会抛 IllegalArgumentException，逐个容错
        boundConnections.forEach { conn ->
            runCatching { appContext.unbindService(conn) }
                .onFailure { Log.w(TAG, "unbindService failed (may already be unbound): ${it.message}") }
        }
        boundConnections.clear()
    }

    private fun findServiceConnection(link: CXRLink): ServiceConnection? {
        // 优先使用缓存的字段
        cachedServiceConnectionField?.let { field ->
            try {
                field.isAccessible = true
                return (field.get(link) as ServiceConnection?)
            } catch (e: Exception) {
                Log.w(TAG, "Cached ServiceConnection field access failed, re-scanning: ${e.message}")
                cachedServiceConnectionField = null
            }
        }
        // 缓存未命中，遍历查找
        var type: Class<*>? = link.javaClass
        while (type != null) {
            try {
                val field = type.declaredFields.firstOrNull { ServiceConnection::class.java.isAssignableFrom(it.type) }
                if (field != null) {
                    field.isAccessible = true
                    cachedServiceConnectionField = field
                    return field.get(link) as ServiceConnection
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to find ServiceConnection in ${type?.name}: ${e.message}")
            }
            type = type.superclass
        }
        Log.e(TAG, "CXR-L ServiceConnection field not found, CXR-L SDK version may be incompatible")
        return null
    }

    private fun isWifiEnabled(): Boolean {
        val wifiManager = appContext.getSystemService(WifiManager::class.java)
        return wifiManager?.isWifiEnabled == true
    }

    private fun hasGlassesOperationPrerequisites(
        targetHostApp: RokidHostApp,
        requestAuthorizationIfMissing: Boolean,
    ): Boolean {
        if (!isHostAppInstalled(targetHostApp)) {
            onStatus(appContext.getString(R.string.install_host_first, targetHostApp.displayName))
            return false
        }
        if (token.isNullOrBlank()) {
            onStatus(appContext.getString(R.string.authorize_in_host, targetHostApp.displayName))
            if (requestAuthorizationIfMissing) requestAuthorization()
            return false
        }
        return true
    }

    /** 已知 APK 文件名到包名的映射表（兜底 readPackageName 使用） */
    private val KNOWN_APK_PACKAGES = mapOf(
        "RokidLink" to "com.rokidlab.rokidlink",
    )

    private fun readPackageName(apkFile: File): String {
        // 先尝试从 APK 读取（部分国产手机 getPackageArchiveInfo 可能返回 null）
        @Suppress("DEPRECATION")
        val info = runCatching {
            appContext.packageManager.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.GET_ACTIVITIES)
        }.getOrNull()
        val fromApk = info?.packageName?.takeIf { it.isNotBlank() }
        if (fromApk != null) return fromApk
        // 兜底：从文件名推断（已知应用直接查映射表，未知用文件名自身）
        val name = apkFile.nameWithoutExtension
        val mapped = KNOWN_APK_PACKAGES[name] ?: name
        Log.w(TAG, "readPackageName: getPackageArchiveInfo failed, falling back: name=$name → pkg=$mapped")
        return mapped
    }

    private fun notifyConnectionChanged() {
        onConnectionChanged(
            CxrConnectionState(
                authorized = hasAuthorization(),
                cxrlConnected = cxrlConnected,
                glassBtConnected = glassBtConnected,
            ),
        )
    }
}
