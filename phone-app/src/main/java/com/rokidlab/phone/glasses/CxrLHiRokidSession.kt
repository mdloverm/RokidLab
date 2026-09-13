// Rokid SDK 的 sendCustomCmd 自带「注意 Caps 体积」的废弃标记，
// 但这是当前唯一的下行通道，短期内不可能替换，故整文件抑制该告警。
@file:Suppress("DEPRECATION")

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
import com.rokidlab.phone.connection.ChannelPriority
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
import com.rokid.cxr.session.CxrSessionManager
import com.rokid.cxr.session.RokidAppStatus
import com.rokid.sprite.aiapp.externalapp.auth.AuthResult
import com.rokid.sprite.aiapp.externalapp.auth.AuthorizationHelper
import com.rokid.sprite.aiapp.externalapp.auth.GlassPermission
import com.rokidlab.phone.domain.AiConfig
import com.rokidlab.phone.domain.CxrAppOperation
import com.rokidlab.phone.platform.Capability
import com.rokidlab.phone.platform.CapabilityProbe
import java.io.File
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
    internal var onStatus: (String) -> Unit,
    internal var onBusyChanged: (Boolean) -> Unit,
    private var onConnectionChanged: (CxrConnectionState) -> Unit,
    initialHostApp: RokidHostApp = RokidHostApp.DEFAULT,
    /** 用于启动授权 Activity 的现代 ActivityResultLauncher，替代已废弃的 startActivityForResult */
    private val authLauncher: ((Intent) -> Unit)? = null,
    /** 长驻任务作用域（Application 级）：ASR 推送/轮询等不随 Activity 销毁取消。
     *  配合保活前台服务，Activity 退后台/销毁后语音链路仍持续运行。 */
    internal val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    // ── Activity 泄漏防护 ──
    // 本会话由 LabApplication（应用级单例）持有，若强引用 Activity，
    // 保活模式下 Activity 销毁后仍被单例钉住无法回收（泄漏整棵 View 树）。
    // 因此：仅持 application 级 Context + Activity 弱引用；
    // 确需 Activity 的场景（授权页跳转/销毁检查）走 activityRef。
    internal val appContext: android.content.Context = activity.applicationContext
    private val activityRef = java.lang.ref.WeakReference(activity)
    /** 主线程调度：替代 activity.runOnUiThread（Activity 回收后仍可用） */
    internal val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

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
        internal const val MEDIA_SERVICE_ACTION = "com.rokid.sprite.aiapp.externalapp.MEDIA_STREAM_SERVICE"
        internal const val AUTH_TOKEN_EXTRA = "auth_token"
        internal const val AUTH_PACKAGE_EXTRA = "auth_package"

        /** 眼镜端镜腿按键触发的「拍照问 AI」指令通道 */
        private const val PHOTO_ASK_CMD = "rokidlab_photo_ask"
        /**
         * AI 文字轮询通道（手机端 → 眼镜端）：定时 sendCustomCmd 轮询，
         * 眼镜端可回复订阅返回 ASR 文字。请求-响应机制可绕过 AI App 对未知上行指令的过滤。
         */
        private const val AI_ASR_POLL_CMD = "rokidlab_ai_asr_poll"




        /** OpenAI 兼容 AI 配置存储 */
        internal const val AI_PREFS = "chat_prefs"
        internal const val KEY_AI_BASE_URL = "ai_base_url"
        internal const val KEY_AI_API_KEY = "ai_api_key"
        internal const val KEY_AI_MODEL = "ai_model"
        /** 对话模型模式键：official（官方乐奇）/ custom（Lab 自定义模型），持久化 + 下发眼镜端（值常量统一见 AiChannel.AI_MODE_*） */
        internal const val KEY_AI_MODE = "ai_mode"
        /** 拍照答题指令：答题时注入 AI 提示词控制回答方式 */
        internal const val KEY_QUIZ_INSTRUCTION = "quiz_instruction"
        /** 本地模型（Ollama）是否作为眼镜对话模型：true=对话仅走本机 Ollama；
         *  与在线槽位（KEY_AI_BASE_URL/API_KEY/MODEL/MODE）相互独立，互不覆盖 */
        internal const val KEY_AI_USE_LOCAL = "ai_use_local"
        /** 当前选择的本地对话模型名（如 qwen2.5:0.5b） */
        internal const val KEY_AI_LOCAL_MODEL = "ai_local_model"
        /** 本地对话请求的用户自定义 JSON 参数（逐字段合并进每次本地请求体，如 {"think": false}；
         *  空串=不附加。替代原「深度思考」布尔开关，兼容任何模型的调参需求） */
        internal const val KEY_AI_LOCAL_PARAMS = "ai_local_params"
        /** 旧版「深度思考」布尔开关键（已废弃，读取时自动迁移到 KEY_AI_LOCAL_PARAMS 后清除） */
        internal const val KEY_AI_LOCAL_THINK_LEGACY = "ai_local_think"
        /** 在线模型长思考开关（DeepSeek V4/V3.2 系生效）：默认关闭（推理吞预算→空轮）；
         *  与发送键旁的「思考」切换按钮共享此键（ChatScreen 直接读写同一 prefs） */
        internal const val KEY_AI_THINKING = "ai_thinking"
        internal const val KEY_KEY_QUIZ_ENABLED = "key_quiz_enabled"

        private fun tokenPrefKey(hostApp: RokidHostApp) = KEY_TOKEN_PREFIX + hostApp.packageName
    }

    // ═══════════════════════════════════════════════════
    // 内部状态字段 / UI 回调注册 / AI 配置（持久化与下发眼镜端）
    // ═══════════════════════════════════════════════════

    internal var hostApp: RokidHostApp = initialHostApp
    internal var token: String? = null
    internal var cxrLink: CXRLink? = null
    internal var pendingOperation: CxrAppOperation? = null
    internal var queryQueue: ArrayDeque<String> = ArrayDeque()
    internal var onQueryResult: ((String, Boolean) -> Unit)? = null
    internal var onQueryComplete: (() -> Unit)? = null
    internal var cxrlConnected = false
    internal var glassBtConnected = false
    internal var operationStarted = false
    /** 防止超时与 operation.onReady 回调竞态 */
    internal var operationCompleted = false
    internal var timeoutJob: Job? = null
    /** 「拍照问 AI」图片回调超时兜底（takePhoto 成功但图片回调永不到达时复位状态） */
    /** 使用同步锁保护操作状态 */
    internal val operationLock = Any()

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
    internal val aiCmdLock = Any()

    /** ASR 桥接协调器（双通道接收/去重/控制标记/下行 ping，从本类拆出，职责见其文档） */
    internal val asrBridge = AsrBridgeCoordinator(
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
        // 兜底轮询复用全 App 共享 ADB 会话：自建会话会挤断用户正在用的 ADB 工具/投屏会话。
        // 长连接（屏幕镜像/手机投屏/文件浏览）占用隧道时让路 —— 返回 null 触发退避，
        // 避免这条「兜底」通道反而把用户正在用的长连接挤断。
        adbClientProvider = {
            val rm = (appContext as LabApplication).routeManager
            // 长连接（屏幕镜像/手机投屏/文件浏览）占用蓝牙通道时，BACKGROUND 兜底轮询
            // 按优先级让路 —— 避免这条「兜底」通道反而把用户正在用的长连接挤断。
            if (rm.channelArbiter.shouldYield(ChannelPriority.BACKGROUND)) null else getAdbShellClient()
        },
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
            // 带上 cbId：页面 15s 超时重试会带同一个 cbId 回来，
            // 网关据此幂等去重，避免重复拨号/重复安装这类真实副作用。
            val r = com.rokidlab.phone.ai.ToolGateway.call(appContext, name, args, cbId)
            val out = JSONObject()
                .put("type", "toolResult")
                .put("cbId", cbId)
                .put("ok", r.ok)
            r.result?.let { out.put("result", it) }
            r.error?.let { out.put("error", it) }
            aiuiHost.sendAiuiHostMessage(out.toString())
        } catch (e: Exception) {
            Log.e(TAG, "handleAiuiToolCall failed", e)
            // 解析/下发失败也要尽力回传，否则页面 Promise 会挂到超时
            runCatching {
                aiuiHost.sendAiuiHostMessage(
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

    /** 「拍照问 AI」域服务（Phase 3 三轮：PhotoQuizFlow 编排 + 拍照链路迁至 domain/PhotoQuizService） */
    internal val photoQuizService = com.rokidlab.phone.domain.PhotoQuizService(this)

    /** 注册「拍照问 AI」流程的 UI 回调（乐奇聊天界面进入时调用，按键触发时复用展示） */
    fun setPhotoAskUiCallbacks(
        onStage: (Int) -> Unit,
        onText: (String) -> Unit,
        onReply: (String) -> Unit,
    ) = photoQuizService.setPhotoAskUiCallbacks(onStage, onText, onReply)


    /**
     * appStart 后的一次性冷却标志：真实 resume（appStart 触发的 Sys_App_Resume_Change）只会到达一次，
     * 用一次性标志消费而非 3s 时间窗口——时间窗口会把「开启后 3s 内的第一次按键」也误过滤，
     * 导致按键答题开启后第一次按键没反应。标志设置后 3s 未收到真实 resume 会自动清除（防残留）。
     */
    @Volatile
    internal var quizResumeCooling = false

    /** WiFi 连接状态回调（由 sendWifiConfig 设置，统一在全局指令监听中转发） */
    @Volatile
    internal var wifiStatusCallback: ((String) -> Unit)? = null

    // ═══════════════════════════════════════════════════
    // AI 配置域（Phase 3：委派到 L3 domain/AiConfigService，门面签名不变）
    // ═══════════════════════════════════════════════════

    internal val aiConfig = com.rokidlab.phone.domain.AiConfigService(this)

    fun setDeepSeekApiKey(key: String) = aiConfig.setDeepSeekApiKey(key)
    fun setAiConfig(config: AiConfig) = aiConfig.setAiConfig(config)
    fun isLocalChatActive(): Boolean = aiConfig.isLocalChatActive()
    fun localChatModel(): String = aiConfig.localChatModel()
    fun localChatParams(): String = aiConfig.localChatParams()
    fun setLocalChatParams(json: String) = aiConfig.setLocalChatParams(json)
    fun getOnlineAiConfig(): AiConfig = aiConfig.getOnlineAiConfig()
    fun setLocalChatModel(modelName: String) = aiConfig.setLocalChatModel(modelName)
    fun setQuizInstructionOnly(text: String) = aiConfig.setQuizInstructionOnly(text)
    fun backfillOnlineApiKey(key: String) = aiConfig.backfillOnlineApiKey(key)
    fun isThinkingEnabled(): Boolean = aiConfig.isThinkingEnabled()
    fun setThinkingEnabled(enabled: Boolean) = aiConfig.setThinkingEnabled(enabled)
    fun getAiConfig(): AiConfig = aiConfig.getAiConfig()

    // ═══════════════════════════════════════════════════
    // Phase 3 · L3 domain 服务装配（Connection / DeviceControl / AiConversation）
    // 门面签名不变、调用方零改动；实现见 domain/ 对应服务
    // ═══════════════════════════════════════════════════

    internal val connection = com.rokidlab.phone.domain.ConnectionService(this)
    internal val deviceControl = com.rokidlab.phone.domain.DeviceControlService(this)
    internal val aiConversation = com.rokidlab.phone.domain.AiConversationService(this)

    /** L4 工具确认通道（Phase 4：副作用工具的眼镜端用户确认，注入 ToolPolicy） */
    /** Phase 4：副作用工具确认通道 —— 用全局常驻单例（会话重建/清理都不会让通道消失） */
    internal val toolConfirm = com.rokidlab.phone.ai.GlassToolConfirmChannel.global

    // —— 设备控制域（domain/DeviceControlService）——
    fun sendTtsToGlass(text: String): Int = deviceControl.sendTtsToGlass(text)
    fun stopTtsOnGlass(): Int = deviceControl.stopTtsOnGlass()
    fun stopPhoneMirrorOnGlasses(): Int = deviceControl.stopPhoneMirrorOnGlasses()
    fun installApk(apkFile: File, onInstallResult: ((Boolean) -> Unit)? = null) =
        deviceControl.installApk(apkFile, onInstallResult)
    fun installApk(apkFile: File, packageName: String, onInstallResult: ((Boolean) -> Unit)? = null) =
        deviceControl.installApk(apkFile, packageName, onInstallResult)
    fun launchApp(packageName: String, activityClass: String = ".MainActivity", sendCmdAfterLaunch: String? = null, onLaunchResult: ((Boolean) -> Unit)? = null) =
        deviceControl.launchApp(packageName, activityClass, sendCmdAfterLaunch, onLaunchResult)
    fun sendKeyButtonConfig(shortPkg: String, shortActivity: String, longPkg: String, longActivity: String, onResult: ((Boolean) -> Unit)? = null) =
        deviceControl.sendKeyButtonConfig(shortPkg, shortActivity, longPkg, longActivity, onResult)
    fun sendWifiConfig(ssid: String, password: String, onResult: ((Boolean, String?) -> Unit)? = null) =
        deviceControl.sendWifiConfig(ssid, password, onResult)
    fun sendKeyQuizConfig(enabled: Boolean, onResult: ((Boolean) -> Unit)? = null) =
        deviceControl.sendKeyQuizConfig(enabled, onResult)
    fun stopApp(packageName: String, onStopResult: ((Boolean) -> Unit)? = null) =
        deviceControl.stopApp(packageName, onStopResult)
    fun uninstallApp(packageName: String, onUninstallResult: ((Boolean) -> Unit)? = null) =
        deviceControl.uninstallApp(packageName, onUninstallResult)
    fun queryInstalledApps(packageNames: List<String>, onResult: (String, Boolean) -> Unit, onComplete: () -> Unit) =
        deviceControl.queryInstalledApps(packageNames, onResult, onComplete)

    // —— AI 对话域（domain/AiConversationService）——
    fun sendAiTextMessage(
        text: String,
        onResult: ((Boolean, String?) -> Unit)? = null,
        onReply: ((String) -> Unit)? = null,
        contextText: String? = null,
        interruptOfficialFirst: Boolean = false,
        skipTtsAudioFinished: Boolean = false,
        showAsrResult: Boolean = true,
        localTakeover: Boolean = false,
        instruction: String? = null,
        recordHistory: Boolean = true,
        onDelta: ((String) -> Unit)? = null,
    ) = aiConversation.sendAiTextMessage(text, onResult, onReply, contextText, interruptOfficialFirst, skipTtsAudioFinished, showAsrResult, localTakeover, instruction, recordHistory, onDelta)

    fun abortCurrentAi() = aiConversation.abortCurrentAi()

    /** 注册眼镜端语音对话的 UI 回调（乐奇聊天界面进入时调用） */
    fun setGlassesAiUiCallbacks(onText: (String) -> Unit, onReply: (String) -> Unit) =
        aiConversation.setGlassesAiUiCallbacks(onText, onReply)

    /** ASR 文字分发（Phase 3：委派到 L3 AiConversationService，AsrBridgeCoordinator 装配点零改动） */
    private fun dispatchGlassesAsrText(text: String) = aiConversation.dispatchGlassesAsrText(text)


    // ═══════════════════════════════════════════════════
    // AI 工具：ADB 查询客户端（Phase 2 收尾：所有权移交 L0 platform/AdbTransport）
    // ═══════════════════════════════════════════════════

    /**
     * 全 App 共享 ADB 会话的唯一所有者（L0 边界，见 [com.rokidlab.phone.platform.AdbTransport]）。
     *
     * 端点由当前路由解析：WiFi 直连优先，失败回落蓝牙隧道（[ConnectionRoute]）。
     * 建链失败时清线路缓存，下次强制重新探测。
     */
    private val adbTransport = com.rokidlab.phone.platform.AdbTransport(
        contextProvider = { appContext },
        endpointProvider = {
            runCatching {
                val app = appContext as LabApplication
                val route = runBlocking { app.routeManager.resolve(app.glassesIp, 5555) }
                when (route) {
                    is ConnectionRoute.Wifi -> com.rokidlab.phone.platform.AdbEndpoint(route.ip, route.port)
                    is ConnectionRoute.Bluetooth ->
                        com.rokidlab.phone.platform.AdbEndpoint(route.ip, route.localPort, viaBluetooth = true)
                    is ConnectionRoute.None -> null
                }
            }.getOrNull()
        },
        onRouteFailure = {
            runCatching { (appContext as LabApplication).routeManager.clearRouteCache() }
        },
        // 后台线路升级：只读探测 WiFi 直连是否就绪，就绪则把共享会话从蓝牙隧道拆掉重建成 WiFi。
        // 只探 adbd(5555)，不调 resolve() —— resolve 会顺带建蓝牙隧道，与此处「只判可达」的语义不符。
        preferredEndpointProvider = {
            runCatching {
                val app = appContext as LabApplication
                val ip = app.glassesIp
                if (ip.isNotBlank() && app.routeManager.isWifiReachable(ip)) {
                    com.rokidlab.phone.platform.AdbEndpoint(ip, 5555)
                } else null
            }.getOrNull()
        },
        // WiFi 端点建链失败 → 记账（清缓存 + 记录断线信号），使下一次 resolve 优先落到蓝牙隧道
        onWifiFailure = {
            runCatching { (appContext as LabApplication).routeManager.noteWifiFailure() }
        },
    )






    /** L3 AIUI 宿主服务（Phase 3：AiuiFrontendController 装配 + 12 个委派收口到 domain/） */
    internal val aiuiHost = com.rokidlab.phone.domain.AiuiHostService(this)

    /** 在眼镜上打开一个 AIUI agent（.aix），协议与参数见 AiuiFrontendController */
    fun openAiuiAgent(
        agentId: String,
        agentName: String,
        nativeVersion: String = "0.0.74",
        pageName: String = "pages/index/index",
    ): Int = aiuiHost.openAiuiAgent(agentId, agentName, nativeVersion, pageName)

    /** 直启眼镜 cxr 目录已存在的 .aix（Sys_AIUI_Start） */
    fun startAiuiPackage(packageName: String): Int = aiuiHost.startAiuiPackage(packageName)

    /** 关闭眼镜上正在渲染的 .aix（Sys_AIUI_Stop） */
    fun stopAiuiPackage(packageName: String): Int = aiuiHost.stopAiuiPackage(packageName)

    /** 打开自托管宿主渲染本地已推送的 .aix */
    fun openAiuiHost(fileName: String? = null): Int = aiuiHost.openAiuiHost(fileName)

    /** 关闭正在渲染的宿主 */
    fun closeAiuiHost(): Int = aiuiHost.closeAiuiHost()

    /** 以 onMessage 协议向宿主页面注入消息 */
    fun sendAiuiHostMessage(json: String): Int = aiuiHost.sendAiuiHostMessage(json)

    /** 把本地 .aix 推到 RokidLink aiui_host 目录并自动拉起宿主渲染 */
    fun pushAixToRokidLinkHost(
        aixFile: File,
        openAfter: Boolean = true,
        launchParams: String? = null,
    ): String? = aiuiHost.pushAixToRokidLinkHost(aixFile, openAfter, launchParams)

    /**
     * CXR-L 1.1.0 的 ExternalAppClient.sendCustomCmd 内置保留 cmd 黑名单
     * （Dev/Med/Ota/Ai/Ntf/Nav/Sys/ARTC/Trans/Pay/Settings/Custom_View/Schedule/Memo），
     * 命中即返回 -1，外部无法直发 "Sys"/"Ai"。SDK 内部封装（appStart/openApp 等）
     * 同样绕过黑名单直接走 Binder 层 IMediaStreamService.sendCustomCmd(cmd, bytes)。
     * 这里用反射取 ExternalAppClient 私有字段 b（IMediaStreamService）绕过黑名单直发。
     */
    internal fun rawSendCustomCmd(link: CXRLink, cmd: String, caps: Caps): Int {
        return when (val r = CapabilityProbe.sdkBridge.rawSendCustomCmd(link, cmd, caps)) {
            is Capability.Available -> r.value
            is Capability.Unavailable -> {
                Log.e(TAG, "rawSendCustomCmd($cmd) 失败: ${r.reason}")
                -3
            }
        }
    }

    /** 安装 AIUI agent 到眼镜（Jsai_AddNativeAgent） */
    fun installAiuiAgent(
        agentId: String,
        agentName: String,
        url: String,
        fileMd5: String,
        nativeVersion: String = "0.0.74",
        agentDesc: String = "",
    ): Int = aiuiHost.installAiuiAgent(agentId, agentName, url, fileMd5, nativeVersion, agentDesc)

    /** 直装 .aix 并自动打开一次 */
    fun installAndOpenAiuiAgentOnce(
        agentId: String,
        agentName: String,
        url: String,
        fileMd5: String,
        openDelayMs: Long = 3000L,
        openRetryMs: Long = 2000L,
        openTimeoutMs: Long = 25_000L,
    ): Int = aiuiHost.installAndOpenAiuiAgentOnce(agentId, agentName, url, fileMd5, openDelayMs, openRetryMs, openTimeoutMs)

    /** 向眼镜下发 native agent 目录地址（Jsai_GetRequestInfo） */
    fun pushAiuiAgentListUrl(agentListUrl: String): Int = aiuiHost.pushAiuiAgentListUrl(agentListUrl)

    /** 在时间窗口内周期下发目录配置 */
    fun startAgentListPushWindow(
        catalogUrl: String,
        durationMs: Long = 60_000L,
        intervalMs: Long = 1_500L,
    ) = aiuiHost.startAgentListPushWindow(catalogUrl, durationMs, intervalMs)

    fun stopAgentListPushWindow() = aiuiHost.stopAgentListPushWindow()

    /** 通知眼镜立即重新拉取 native agent 目录（Jsai_NotifyGlassGetList） */
    fun notifyGlassGetAgentList(): Int = aiuiHost.notifyGlassGetAgentList()

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
     * 获取（或懒创建并连接）ADB shell 客户端 —— 全 App 唯一的 ADB 会话入口。
     * 必须在后台线程调用（同步阻塞连接握手）。连接失败返回 null。
     *
     * ⚠️ 所有需要 ADB 会话的消费者（AI 工具 / ADB 工具页 / ASR 兜底轮询 / 定时任务）
     * 都必须走这里复用同一份会话，**不得各自新建**：
     * 手机侧蓝牙栈对「同一设备 + 同一 SCN」只允许一条客户端 RFCOMM 通道，
     * 自建的第二条会话会把正在服务的那条挤断（表现为"用着用着突然不行了"）。
     *
     * 连接复用/重建、端点解析与串行化锁由 L0 [com.rokidlab.phone.platform.AdbTransport] 负责
     * （`AdbTransport` 内部 `@Synchronized`：多个后台消费者并发来取时不加锁会并发建链、触发通道争抢）。
     *
     * 优先级让路由 L1 [com.rokidlab.phone.connection.ChannelArbiter] 负责：本方法是常规消费者
     * （AI 工具 / ADB 工具页 / 定时任务 / AIUI 工具）的 NORMAL 档位出口，存在 LONG_LIVED
     * 持有者（屏幕镜像 / 手机投屏 / 文件浏览）时返回 null 让路 —— 此时共享会话已被
     * [releaseAdbShellClient] 主动断开，再尝试建链不仅会被蓝牙栈拒绝，还可能把用户正在
     * 使用的长连接挤断。NORMAL 消费者之间共享同一条会话，互不让路。
     */
    fun getAdbShellClient(): com.rokidlab.phone.adb.AdbShellClient? {
        val arbiter = (appContext as? LabApplication)?.container?.arbiter
        if (arbiter != null && arbiter.shouldYield(ChannelPriority.NORMAL)) {
            Log.i(TAG, "getAdbShellClient: yield to higher-priority holder ${arbiter.snapshot()}")
            return null
        }
        val client = adbTransport.get() ?: return null
        // ADB 通道一旦可用，就把眼镜端的「后台启动豁免」补上（见 ensureGlassesBackgroundLaunchAllowed）。
        // 放在这里是因为它是全 App 唯一的 ADB 会话出口，且调用方都在后台线程。
        ensureGlassesBackgroundLaunchAllowed(client)
        return client
    }

    /** 本次会话是否已确认眼镜端拿到 BAL 豁免（避免每次取 client 都跑 appops） */
    @Volatile
    private var glassesBalExemptConfirmed = false

    /**
     * 授予并**复核**眼镜端 `SYSTEM_ALERT_WINDOW`（Android 12+ 后台启动限制 BAL 豁免）。
     *
     * 背景：RokidLink 是常驻前台服务，它 `startActivity` 拉起 `AiuiLinkActivity`（AIUI 宿主）/
     * `KeyButtonBridgeActivity` 时会被系统以
     * `Background activity start ... allowBackgroundActivityStart: false` **静默拒绝** ——
     * 用户侧表现为「生成 AIUI 界面提示眼镜没权限 / 点了没反应」。
     * 唯一可靠的解法就是让 RokidLink 拿到该 appop（或改用 ADB 直启，见 AiuiFrontendController）。
     */
    internal fun ensureGlassesBackgroundLaunchAllowed(client: com.rokidlab.phone.adb.AdbShellClient) {
        if (glassesBalExemptConfirmed) return
        val pkg = "com.rokidlab.rokidlink"
        when (val cur = com.rokidlab.phone.platform.ShellOps.isSystemAlertWindowAllowed(client, pkg)) {
            is com.rokidlab.phone.platform.Capability.Available ->
                if (cur.value) {
                    glassesBalExemptConfirmed = true
                    Log.i(TAG, "glasses BAL exemption already granted (SYSTEM_ALERT_WINDOW=allow)")
                    return
                }
            is com.rokidlab.phone.platform.Capability.Unavailable ->
                Log.w(TAG, "query glasses SYSTEM_ALERT_WINDOW failed: ${cur.reason}")
        }
        when (val g = com.rokidlab.phone.platform.ShellOps.grantSystemAlertWindow(client, pkg)) {
            is com.rokidlab.phone.platform.Capability.Available -> {
                val verified = com.rokidlab.phone.platform.ShellOps.isSystemAlertWindowAllowed(client, pkg)
                val ok = (verified as? com.rokidlab.phone.platform.Capability.Available)?.value == true
                glassesBalExemptConfirmed = ok
                if (ok) Log.i(TAG, "glasses BAL exemption granted+verified (SYSTEM_ALERT_WINDOW=allow)")
                else Log.w(TAG, "glasses BAL exemption grant NOT verified: " +
                    ((verified as? com.rokidlab.phone.platform.Capability.Unavailable)?.reason ?: "appops still not allow"))
            }
            is com.rokidlab.phone.platform.Capability.Unavailable ->
                Log.w(TAG, "grant glasses BAL exemption failed: ${g.reason}")
        }
    }

    /**
     * 主动释放共享 ADB 会话（长连接消费者上场前调用）。
     *
     * 手机侧蓝牙栈对「同一设备 + 同一 SCN」只允许一条客户端 RFCOMM 通道：
     * 屏幕镜像 / 手机投屏 / 文件浏览需要长时间独占隧道，若共享会话还占着通道，
     * 它们的建链会被栈直接拒绝（表现为长连接「连接失败」）。
     * 调用后共享会话立刻断开腾出通道；下次 [getAdbShellClient] 会自动重建，调用方无感知。
     */
    fun releaseAdbShellClient() = adbTransport.release()

    /**
     * 手机侧 WiFi 重新接入时通知共享 ADB 会话立刻重探首选（WiFi）线路。
     *
     * 见 [com.rokidlab.phone.platform.AdbTransport.onPreferredRouteMaybeAvailable]：
     * 共享会话可能一直挂在蓝牙隧道上，不主动重探就要等满探测节流、且期间恰好有取用才会切换。
     */
    fun onWifiMaybeAvailable() = adbTransport.onPreferredRouteMaybeAvailable()

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


    private val authorizationService = com.rokidlab.phone.domain.AuthorizationService()

    /**
     * 补齐 SDK 内部权限列表（Phase 3：委派到 L3 [com.rokidlab.phone.domain.AuthorizationService]，
     * 详见该类的 doc）。保留此方法是为了 init / selectHostApp / handleAuthorizationResult
     * 三处调用点零改动迁移（Phase 3 门面委派策略）。
     */
    private fun grantGlassPermissions() {
        authorizationService.grantGlassPermissions()
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
        // Phase 4：把常驻确认通道绑定到本会话，并（幂等地）注入 ToolPolicy 风险闸门
        com.rokidlab.phone.ai.GlassToolConfirmChannel.bind(this)
        com.rokidlab.phone.ai.ToolPolicy.confirmationChannel = toolConfirm
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

    /**
     * 查询 Rokid AI App 的兼容状态（官方接入前置）。
     *
     * 以 SDK `checkRokidAppCompatibility` 返回为准，不写死版本串（阈值随 SDK 演进）；
     * 检测异常返回 null，调用方按"放行"处理，避免 SDK 查询失败反而阻断正常授权。
     */
    private fun rokidAppStatus(targetHostApp: RokidHostApp): RokidAppStatus? {
        return runCatching {
            CxrSessionManager.getInstance(appContext).checkRokidAppCompatibility(appContext)
        }.getOrNull()
    }

    fun requestAuthorization() {
        val targetHostApp = hostApp
        if (!isHostAppInstalled(targetHostApp)) {
            onStatus(appContext.getString(R.string.install_glasses_host_first, targetHostApp.displayName))
            return
        }
        // 官方接入前置：Rokid AI App 兼容版本（checkRokidAppCompatibility 阈值随 SDK 演进，
        // 代码不写死版本串，以 SDK 返回为准）。版本过低时授权页/建链都会莫名失败，须先拦截提示。
        if (rokidAppStatus(targetHostApp) is RokidAppStatus.VersionTooLow) {
            onStatus(appContext.getString(R.string.auth_host_version_too_low, targetHostApp.displayName))
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






    // ═══════════════════════════════════════════════════
    // AI 下行主链路：sendAiTextMessage / sendAiTextViaLink / 拍照答题 / ASR 上行去重与轮询
    // ═══════════════════════════════════════════════════


    fun takeGlassesPhoto(
        width: Int = 1024,
        height: Int = 768,
        quality: Int = 80,
        onPhoto: (ByteArray) -> Unit,
        onError: (String) -> Unit,
    ) = photoQuizService.takeGlassesPhoto(width, height, quality, onPhoto, onError)

    /** 「拍照问 AI」全流程入口（无显式回调，用聊天界面注册的默认回调；编排见 PhotoQuizFlow.start） */
    fun startPhotoAsk() = photoQuizService.startPhotoAsk()

    /** 「拍照问 AI」全流程入口（显式回调；编排见 PhotoQuizFlow.start） */
    fun startPhotoAsk(
        onStage: (Int) -> Unit,
        onText: (String) -> Unit,
        onReply: (String) -> Unit,
    ) = photoQuizService.startPhotoAsk(onStage, onText, onReply)

    /**
     * 安全切回主线程执行 UI 回调：Activity 已销毁（保活后台运行）时直接跳过，
     * 避免在已销毁 Activity 上调用 runOnUiThread 导致崩溃，同时下行链路不受影响。
     */
    internal fun safeRunOnUiThread(block: () -> Unit) {
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






    // ═══════════════════════════════════════════════════
    // 会话生命周期：cleanup（连接/任务/服务绑定统一清理）
    // ═══════════════════════════════════════════════════




    /**
     * 会话清理：连接 / 任务 / 服务绑定统一释放。
     *
     * @param abortInFlightAi 是否抢占 AI 代际号打断在途 AI 请求（默认 true）。
     *   `connectAndRunCustomAppOperation()` 首行也调本方法拆旧会话，若此时抢占代际，
     *   会把「发起本次重连的请求自身」的代际顶掉 —— 慢速路径的 sendAiTextViaLink
     *   入口校验必然失败（P0：连不上走慢路径时 AI 有响应但永不上屏）。该调用点传 false。
     */
    fun cleanup(abortInFlightAi: Boolean = true) {
        android.util.Log.i("CxrLInstall", "cleanup() called")
        timeoutJob?.cancel()
        timeoutJob = null
        asrBridge.stop()
        // 补漏：取消其余遗留协程任务（原实现漏取消导致后台任务残留）
        aiConfig.cancelPushJob()
        photoQuizService.cancelPhotoRequestTimeout()
        aiuiHost.stopAgentListPushWindow()
        // Phase 4：清空等待中的工具确认并摘除全局通道（Session 销毁后拒绝新确认）
        toolConfirm.abortAll()
        // 通道常驻：只解绑会话，**不**把 ToolPolicy.confirmationChannel 置 null
        // （置 null 会让重连后的会话里「打电话」等工具再次被判 no confirmation channel）
        com.rokidlab.phone.ai.GlassToolConfirmChannel.unbind(this)
        // 插播 B：复位握手状态，下次连接重新协商眼镜端能力
        GlassesHandshake.reset()
        // 下次会话重新核验眼镜端 BAL 豁免（重装 RokidLink 后 appops 会回到 default）
        glassesBalExemptConfirmed = false
        // 解除 Rokid 主机 App 服务绑定（applicationContext 绑定不随 Activity 销毁自动解绑）
        connection.unbindAllHostServices()
        // 打断在途 AI：bump 代际号让 deepSeekThread 自弃（避免断连后仍空跑至超时），
        // 并趁链路尚在通知眼镜端停止播报；随后才断开连接。
        // abortInFlightAi=false（连接重建场景）时只停播报、不 bump 代际，
        // 否则会连带废掉发起本次重连的那个请求自身。
        if (abortInFlightAi) {
            abortCurrentAi()
        } else {
            stopTtsOnGlass()
        }
        runCatching { cxrLink?.disconnect() }
        cxrLink = null
        // 断开共享 ADB 常驻连接（会话重建/操作失败清理时释放，避免隧道连接泄漏；
        // 下次工具调用经 getAdbShellClient 自动重建）
        adbTransport.shutdown()
        proxyRelay.closeAll()
        pendingOperation = null
        queryQueue.clear()
        cxrlConnected = false
        glassBtConnected = false
        operationStarted = false
        notifyConnectionChanged()
    }







    // ═══════════════════════════════════════════════════
    // 连接编排内核 / pending 调度 / 全局指令监听 / bind 反射与授权前置检查
    // ═══════════════════════════════════════════════════



    /**
     * 注册「按键答题」的眼镜端 resume 监听（经 Sys 频道上行触发拍照答题）。
     *
     * 注意：SDK 的 [CXRLink.appStart] 内部会调用 setCXRGlassAppCbk(传入 cbk) 覆盖本回调，
     * 因此 appStart 成功后必须【再次调用本方法】恢复按键答题监听。
     */
    internal fun registerKeyQuizResumeListener(link: CXRLink) {
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
    internal fun registerGlobalCmdListener(link: CXRLink) {
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
                            // onAsrText 现在只做「入队到 ASR 单消费者串行队列」（非阻塞），
                            // 可直接在 binder 线程调用：既避免独占 binder 线程池，
                            // 又保证入队顺序严格 FIFO（不再经 launch 二次调度导致后发先至）
                            asrBridge.onAsrText(asrText)
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
                    LinkProtocol.TOPIC_TOOL_CONFIRM_RESULT -> {
                        // Phase 4：眼镜端工具确认结果回执（binder 线程，仅 countDown 无阻塞）
                        val f = capsToFieldList(data)
                        val confirmId = f.getOrNull(0)
                        if (!confirmId.isNullOrBlank()) {
                            toolConfirm.onResult(confirmId, f.getOrNull(1) == "yes")
                        }
                    }
                    LinkProtocol.TOPIC_HELLO -> {
                        // 插播 B：眼镜端协议版本/能力通告（caps = [version, capsBitmask, linkVersion]）
                        val f = capsToFieldList(data)
                        val ver = f.getOrNull(0)?.toIntOrNull() ?: 0
                        val caps = f.getOrNull(1)?.toIntOrNull() ?: 0
                        GlassesHandshake.onHello(ver, caps, f.getOrNull(2))
                    }
                    AI_ASR_POLL_CMD -> {
                        val text = parseAiAsrPollText(data)
                        if (!text.isNullOrBlank()) {
                            Log.i(TAG, "AI ASR poll got text: $text")
                            // 同上：仅入队，非阻塞，可在 binder 线程直接调用以保证 FIFO
                            asrBridge.onAsrText(text)
                        }
                    }
                    "Proxy" -> {
                        // 眼镜端 AssistServer NetProxy 请求（Jsai 下载 .aix 时眼镜经手机代理拉文件）。
                        // 手机侧作为 TCP 中继应答：解析 Proxy_NetRequest → 本机 socket 收发 →
                        // 以 Proxy_NetResponse 应答（协议与状态机详见 GlassProxyRelay）。
                        proxyRelay.onInbound(data)
                    }
                    AiChannel.TOPIC_GLASSES_IP -> {
                        // 眼镜端连上 WiFi 后自动上报自身 IP，手机端据此免手动输入，
                        // 自动回填到投屏 / 手机镜像 / 文件管理 / ADB 共用的单一数据源。
                        val ip = AiChannel.decodeGlassesIp(capsToFieldList(data))
                        if (!ip.isNullOrBlank()) {
                            Log.i(TAG, "Received glasses WiFi IP from link: $ip")
                            (appContext as? LabApplication)?.setGlassesIp(ip)
                        } else {
                            Log.w(TAG, "Received glasses IP payload but decode failed (size=${data?.size ?: 0})")
                        }
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
            // 插播 B：主动询问眼镜端协议版本/能力（旧版眼镜端不应答 → 超时判为 legacy）
            requestGlassesHello(link)
        } catch (e: Exception) {
            Log.e(TAG, "registerGlobalCmdListener failed", e)
        }
    }

    /**
     * 发送协议握手请求 [LinkProtocol.TOPIC_HELLO_REQ]，并启动「旧版眼镜端」判定计时。
     * 眼镜端服务就绪时也会主动通告，此处请求用于覆盖「眼镜端后启动 / 手机端重连」。
     */
    internal fun requestGlassesHello(link: CXRLink) {
        runCatching {
            val r = rawSendCustomCmd(link, LinkProtocol.TOPIC_HELLO_REQ, Caps())
            Log.i(TAG, "hello request sent -> $r (current: ${GlassesHandshake.describe()})")
        }.onFailure { Log.w(TAG, "hello request failed: ${it.message}") }
        // 超时未见通告 → 判为旧版眼镜端：后续副作用工具确认立即降级，不再空等 35s
        appScope.launch {
            delay(GlassesHandshake.LEGACY_DETECT_DELAY_MS)
            GlassesHandshake.markLegacy()
        }
    }


    internal fun isWifiEnabled(): Boolean {
        val wifiManager = appContext.getSystemService(WifiManager::class.java)
        return wifiManager?.isWifiEnabled == true
    }

    internal fun hasGlassesOperationPrerequisites(
        targetHostApp: RokidHostApp,
        requestAuthorizationIfMissing: Boolean,
    ): Boolean {
        if (!isHostAppInstalled(targetHostApp)) {
            onStatus(appContext.getString(R.string.install_host_first, targetHostApp.displayName))
            return false
        }
        // 建链兜底：Rokid AI App 版本过低时授权/建链都会失败，提前拦截
        if (rokidAppStatus(targetHostApp) is RokidAppStatus.VersionTooLow) {
            onStatus(appContext.getString(R.string.auth_host_version_too_low, targetHostApp.displayName))
            return false
        }
        if (token.isNullOrBlank()) {
            onStatus(appContext.getString(R.string.authorize_in_host, targetHostApp.displayName))
            if (requestAuthorizationIfMissing) requestAuthorization()
            return false
        }
        return true
    }


    internal fun notifyConnectionChanged() {
        onConnectionChanged(
            CxrConnectionState(
                authorized = hasAuthorization(),
                cxrlConnected = cxrlConnected,
                glassBtConnected = glassBtConnected,
            ),
        )
    }
}
