package com.rokidlab.phone.glasses

import com.rokidlab.phone.app.*
import com.rokidlab.phone.adb.*
import com.rokidlab.phone.design.*
import com.rokidlab.phone.filemanager.*
import com.rokidlab.phone.glasses.*
import com.rokidlab.phone.mirror.*
import com.rokidlab.phone.model.*
import com.rokidlab.phone.network.*
import com.rokidlab.phone.settings.*
import com.rokidlab.phone.store.*
import com.rokidlab.phone.util.*
import com.rokidlab.phone.ai.ToolRegistry
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
import androidx.lifecycle.lifecycleScope
import com.rokid.cxr.link.CXRLink
import com.rokid.cxr.link.callbacks.ICXRLinkCbk
import com.rokid.cxr.link.callbacks.IGlassAppCbk
import com.rokid.cxr.link.callbacks.IImageStreamCbk
import com.rokid.cxr.link.utils.CxrDefs
import com.rokid.cxr.Caps
import com.rokid.sprite.aiapp.externalapp.auth.AuthResult
import com.rokid.sprite.aiapp.externalapp.auth.AuthorizationHelper
import com.rokid.sprite.aiapp.externalapp.auth.GlassPermission
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
    private val activity: AppCompatActivity,
    private val onStatus: (String) -> Unit,
    private val onBusyChanged: (Boolean) -> Unit,
    private val onConnectionChanged: (CxrConnectionState) -> Unit,
    initialHostApp: RokidHostApp = RokidHostApp.DEFAULT,
    /** 用于启动授权 Activity 的现代 ActivityResultLauncher，替代已废弃的 startActivityForResult */
    private val authLauncher: ((Intent) -> Unit)? = null,
    /** 长驻任务作用域（Application 级）：ASR 推送/轮询等不随 Activity 销毁取消。
     *  配合保活前台服务，Activity 退后台/销毁后语音链路仍持续运行。 */
    private val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
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
        /** AI 文字轮询间隔：主通道为 RFCOMM 推送（毫秒级），文件轮询仅作推送断开时的兜底。
         *  蓝牙隧道（RFCOMM）仅支持单串行连接，高频打隧道会令 5556 等本地端口接收积压溢出被拒，
         *  因此推送通道健康时跳过轮询（见 startAiAsrBridgePolling），断开时才以兜底间隔读取。 */
        private const val AI_ASR_POLL_INTERVAL_MS = 5000L
        /** 隧道异常退避上限：轮询连接失败时指数退避，避免推送断开期间持续打隧道导致 5556 报错 */
        private const val AI_ASR_BACKOFF_MAX_MS = 30_000L
        /** ASR 文字文件通道：眼镜端把 ASR_TEXT 追加写入该文件，手机端轮询 tail 读取。
         *  logcat 缓冲会被眼镜高频系统日志数秒内冲掉，文件通道保证可靠读到 */
        private const val GLASSES_ASR_FILE = "/sdcard/Android/data/com.rokidlab.rokidlink/files/ai_asr.log"
        private const val KEY_LAST_ASR_TS = "ai_asr_last_ts"
        /** 按键答题开关下发通道（手机端 → 眼镜端） */
        private const val QUIZ_CONFIG_CMD = "rokidlab_key_quiz"

        /** 眼镜端「双击退出对话窗口」时经 RFCOMM 推送通道上行到手机的音乐停止标记 */
        private const val MUSIC_STOP_MARKER = "__LAB_MUSIC_STOP__"

        /**
         * 眼镜端「按键拍照答题」控制指令：经 RFCOMM 推送通道（AsrPushServer）上行，
         * 独立于 AI App 网关（custom cmd 的 rokidlab_photo_ask 可能被网关过滤收不到），
         * 且与 Sys_App_Resume_Change 不同——仅按键才发送，可严格区分「拍照意图」。
         */
        private const val PHOTO_ASK_MARKER = "__LAB_PHOTO_ASK__"

        /** OpenAI 兼容 AI 配置存储 */
        private const val AI_PREFS = "chat_prefs"
        private const val KEY_AI_BASE_URL = "ai_base_url"
        private const val KEY_AI_API_KEY = "ai_api_key"
        private const val KEY_AI_MODEL = "ai_model"
        /** 对话模型模式：official（官方乐奇）/ custom（Lab 自定义模型），持久化 + 下发眼镜端 */
        private const val KEY_AI_MODE = "ai_mode"
        const val AI_MODE_OFFICIAL = "official"
        const val AI_MODE_CUSTOM = "custom"
        /** 拍照答题指令：答题时注入 AI 提示词控制回答方式 */
        private const val KEY_QUIZ_INSTRUCTION = "quiz_instruction"
        private const val KEY_KEY_QUIZ_ENABLED = "key_quiz_enabled"

        private fun tokenPrefKey(hostApp: RokidHostApp) = KEY_TOKEN_PREFIX + hostApp.packageName
    }

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
    /** AI 文字轮询任务（连接后定期拉取眼镜端 ASR 文字，推送通道不可用时的兜底） */
    private var aiAsrPollJob: Job? = null
    /** ASR 文字推送客户端（第二 RFCOMM 通道长连接，实时接收眼镜端推送） */
    private var aiAsrPushClient: AsrPushClient? = null
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

    /** 最近一次眼镜 ASR 文字及时间（双通道去重：push 与轮询/WiFi 上行可能同时收到同一段文字） */
    @Volatile
    private var lastAsrText = ""
    @Volatile
    private var lastAsrTextAt = 0L

    /** 「拍照问 AI」流程进行中标志（防止按键/按钮重复触发） */
    @Volatile
    private var photoAskInProgress = false

    /**
     * 「拍照问 AI」默认 UI 回调（乐奇聊天界面注册）。
     * 镜腿按键 / 自定义指令触发的 startPhotoAsk() 不带显式回调，使用此处注册的回调
     * 在聊天界面展示流程气泡、识别文字与最终答案。
     */
    @Volatile
    private var photoAskStageCb: (Int) -> Unit = {}
    @Volatile
    private var photoAskTextCb: (String) -> Unit = {}
    @Volatile
    private var photoAskReplyCb: (String) -> Unit = {}

    /** 注册「拍照问 AI」流程的 UI 回调（乐奇聊天界面进入时调用，按键触发时复用展示） */
    fun setPhotoAskUiCallbacks(
        onStage: (Int) -> Unit,
        onText: (String) -> Unit,
        onReply: (String) -> Unit,
    ) {
        photoAskStageCb = onStage
        photoAskTextCb = onText
        photoAskReplyCb = onReply
    }

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
        /** 对话模型模式：AI_MODE_OFFICIAL（官方乐奇）/ AI_MODE_CUSTOM（Lab 自定义模型） */
        val mode: String = AI_MODE_CUSTOM,
        /** 拍照答题指令：设置页填写，答题时注入 AI 提示词控制回答方式（如「只显示答案」「给出解题步骤」） */
        val quizInstruction: String = "",
    )

    /** 保存 AI 配置（持久化到 SharedPreferences） */
    fun setAiConfig(config: AiConfig) {
        runCatching {
            activity.getSharedPreferences(AI_PREFS, 0).edit()
                .putString(KEY_AI_BASE_URL, config.baseUrl)
                .putString(KEY_AI_API_KEY, config.apiKey)
                .putString(KEY_AI_MODEL, config.model)
                .putString(KEY_AI_MODE, config.mode)
                .putString(KEY_QUIZ_INSTRUCTION, config.quizInstruction)
                .apply()
        }
        if (config.apiKey.isNotBlank()) deepSeekApiKey = config.apiKey
        // 同步下发到眼镜端：唤醒词识别出的文字由眼镜端直接调用该模型回复
        pushAiConfigToGlass(config)
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
                    val caps = Caps()
                    caps.write("ai_config")
                    caps.write(config.baseUrl)
                    caps.write(config.apiKey)
                    caps.write(config.model)
                    caps.write(config.mode)
                    val r = link.sendCustomCmd("rokidlab_ai_config", caps)
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

    /** 读取 AI 配置（prefs 优先，缺省回退默认值；key 未配置时留空，由用户在设置页显式填写） */
    fun getAiConfig(): AiConfig {
        val prefs = activity.getSharedPreferences(AI_PREFS, 0)
        val baseUrl = prefs.getString(KEY_AI_BASE_URL, "").orEmpty().ifBlank { "https://api.deepseek.com" }
        val apiKey = prefs.getString(KEY_AI_API_KEY, "").orEmpty()
        val model = prefs.getString(KEY_AI_MODEL, "").orEmpty().ifBlank { "deepseek-chat" }
        val mode = prefs.getString(KEY_AI_MODE, AI_MODE_CUSTOM).orEmpty().ifBlank { AI_MODE_CUSTOM }
        val quizInstruction = prefs.getString(KEY_QUIZ_INSTRUCTION, "").orEmpty()
        return AiConfig(baseUrl, apiKey, model, mode, quizInstruction)
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
                caps.write("tts_play")
                caps.write(text)
                return link.sendCustomCmd("tts_play", caps)
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
     * 获取（或懒创建并连接）ADB shell 客户端，供查询类 AI 工具使用。
     * 必须在后台线程调用（同步阻塞连接握手）。连接失败返回 null。
     */
    fun getAdbShellClient(): com.rokidlab.phone.adb.AdbShellClient? {
        adbShellClient?.let {
            if (it.isConnected()) return it
            runCatching { it.disconnect() }
        }
        return runCatching {
            val app = activity.application as LabApplication
            val prefs = activity.getSharedPreferences("adb_prefs", 0)
            val wifiIp = prefs.getString("ip", "192.168.1.168") ?: "192.168.1.168"
            val route = runBlocking { app.routeManager.resolve(wifiIp, 5555) }
            val (targetIp, targetPort) = when (route) {
                is ConnectionRoute.Wifi -> route.ip to route.port
                is ConnectionRoute.Bluetooth -> route.ip to route.localPort
                is ConnectionRoute.None -> return null
            }
            val client = com.rokidlab.phone.adb.AdbShellClient(activity.applicationContext, targetIp, targetPort)
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
            activity.getSharedPreferences(AI_PREFS, 0).getBoolean(KEY_KEY_QUIZ_ENABLED, false)
        }.getOrDefault(false)
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
            activity.getSharedPreferences(AI_PREFS, 0).edit()
                .putBoolean(KEY_KEY_QUIZ_ENABLED, enabled)
                .apply()
        }
        val targetHostApp = hostApp
        if (!hasGlassesOperationPrerequisites(targetHostApp, requestAuthorizationIfMissing = true)) {
            deliver(false)
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
                                caps.write("quiz_enabled")
                                caps.write(enabled.toString())
                                val result = link.sendCustomCmd(QUIZ_CONFIG_CMD, caps)
                                Log.i(TAG, "sendCustomCmd($QUIZ_CONFIG_CMD, enabled=$enabled) -> $result")
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
            val prefs = activity.getSharedPreferences(PREFS_NAME, 0)
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
            val prefs = activity.getSharedPreferences(PREFS_NAME, 0)
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
                activity.packageManager.getPackageInfo(targetHostApp.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                activity.packageManager.getPackageInfo(targetHostApp.packageName, 0)
            }
        }.isSuccess
    }

    fun requestAuthorization() {
        val targetHostApp = hostApp
        if (!isHostAppInstalled(targetHostApp)) {
            onStatus(activity.getString(R.string.install_glasses_host_first, targetHostApp.displayName))
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
            onStatus(activity.getString(R.string.auth_page_opened, targetHostApp.displayName))
        } else {
            // 兜底：使用已废弃的 startActivityForResult（无现代 Launcher 时）
            @Suppress("DEPRECATION")
            activity.startActivityForResult(launchIntent, 4027)
            onStatus(activity.getString(R.string.auth_page_opened, targetHostApp.displayName))
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
                    activity.getSharedPreferences(PREFS_NAME, 0)
                        .edit()
                        .putString(tokenPrefKey(hostApp), result.token)
                        .apply()
                }
                onStatus(activity.getString(R.string.auth_token_obtained, hostApp.displayName))
                notifyConnectionChanged()
            }

            is AuthResult.AuthCancel -> {
                token = null
                runCatching {
                    activity.getSharedPreferences(PREFS_NAME, 0)
                        .edit()
                        .remove(tokenPrefKey(hostApp))
                        .apply()
                }
                onStatus(activity.getString(R.string.auth_cancelled, hostApp.displayName))
                notifyConnectionChanged()
            }

            is AuthResult.AuthFail -> {
                token = null
                runCatching {
                    activity.getSharedPreferences(PREFS_NAME, 0)
                        .edit()
                        .remove(tokenPrefKey(hostApp))
                        .apply()
                }
                onStatus(activity.getString(R.string.auth_failed_simple, hostApp.displayName))
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
            onStatus(activity.getString(R.string.detected_package, packageName))
            connectAndUpload(authToken, targetHostApp, packageName, apkFile, onInstallResult)
        }.onFailure { error ->
            android.util.Log.e("CxrLInstall", "installApk: exception: ${error.javaClass.simpleName}: ${error.message}")
            onStatus(activity.getString(R.string.cxrl_failed_msg, error.message ?: error.javaClass.simpleName))
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
                timeoutMessage = activity.getString(com.rokidlab.phone.R.string.key_btn_timeout),
                bindMessage = activity.getString(com.rokidlab.phone.R.string.key_btn_binding),
                configureFailureMessage = activity.getString(com.rokidlab.phone.R.string.key_btn_config_failed),
                bindFailureMessage = activity.getString(com.rokidlab.phone.R.string.key_btn_bind_failed),
                showConnectionStatus = false,
                onReady = { link ->
                    // CUSTOMAPP 场景构建完成后 cxr-service 才会把自定义指令路由给眼镜端：
                    // 必须先 appStart 并等待 onOpenAppResult 成功，再 sendCustomCmd（与 sendKeyQuizConfig 一致）
                    val entryUri = "com.rokidlab.rokidlink.MainActivity"
                    link.appStart(entryUri, glassAppCallback(
                        onStart = { success ->
                            if (success) {
                                val caps = Caps()
                                caps.write("key_config")
                                caps.write(shortPkg)
                                caps.write(shortActivity)
                                caps.write(longPkg)
                                caps.write(longActivity)
                                val result = link.sendCustomCmd("rokidlab_key_config", caps)
                                val resultMsg = if (result == 0) "OK" else "error=$result"
                                onStatus(activity.getString(com.rokidlab.phone.R.string.key_btn_sent, shortPkg, longPkg, resultMsg))
                                onResult?.invoke(result == 0)
                            } else {
                                Log.w(TAG, "appStart failed, cannot send key config")
                                onStatus(activity.getString(com.rokidlab.phone.R.string.key_btn_send_failed))
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
                timeoutMessage = activity.getString(com.rokidlab.phone.R.string.wifi_config_timeout),
                bindMessage = activity.getString(com.rokidlab.phone.R.string.key_btn_binding),
                configureFailureMessage = activity.getString(com.rokidlab.phone.R.string.wifi_config_failed),
                bindFailureMessage = activity.getString(com.rokidlab.phone.R.string.key_btn_bind_failed),
                showConnectionStatus = false,
                onReady = { link ->
                    val json = """{"module":"setting","ssid":"$ssid","password":"$password","forceReconnect":true}"""
                    Log.i(TAG, "Sending WiFi config: mode=Wifi_Connect, json=$json")
                    
                    val caps = Caps()
                    caps.write("Wifi_Connect")
                    caps.write(json)
                    
                    var statusReceived = false
                    val timeoutHandler = android.os.Handler(activity.mainLooper)
                    
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
                                onStatus(activity.getString(com.rokidlab.phone.R.string.wifi_config_success, ssid))
                                onResult?.invoke(true, null)
                            } else {
                                val errorMsg = jsonObj.optString("message", "连接失败，请检查密码")
                                onStatus(activity.getString(com.rokidlab.phone.R.string.wifi_config_failed) + ": $errorMsg")
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
    ) {
        Log.i(TAG, "sendAiTextMessage(\"$text\") called. cxrlConnected=$cxrlConnected, glassBtConnected=$glassBtConnected, cxrLink=${cxrLink != null}, token=${token?.take(8) ?: "null"}")

        // 快速路径: 如果 CXR 已连接且 link 可用，直接发送（跳过前置检查 + 重新 connect）
        val link = cxrLink
        if (cxrlConnected && glassBtConnected && link != null) {
            Log.i(TAG, "sendAiTextMessage: using existing CXRLink (fast path)")
            sendAiTextViaLink(link, text, onResult, onReply, contextText, interruptOfficialFirst, skipTtsAudioFinished, showAsrResult, localTakeover, instruction)
            return
        }

        // 慢速路径: 需要先建立连接
        Log.i(TAG, "sendAiTextMessage: no active link, falling back to connectAndRun path")
        val targetHostApp = hostApp
        if (!hasGlassesOperationPrerequisites(targetHostApp, requestAuthorizationIfMissing = true)) {
            Log.w(TAG, "sendAiTextMessage: missing prerequisites")
            activity.runOnUiThread { onResult?.invoke(false, "missing prerequisites") }
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
                        sendAiTextViaLink(l, text, onResult, onReply, contextText, interruptOfficialFirst, skipTtsAudioFinished, showAsrResult, localTakeover, instruction)
                    }
                },
                onFailure = {
                    cleanup()
                    onBusyChanged(false)
                    activity.runOnUiThread { onResult?.invoke(false, "connection failed") }
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

    /**
     * 「拍照问 AI」全流程（镜腿按键 / 手机端按钮共用入口）：
     * 眼镜拍照 → 本地 OCR 识别题目文字 → 知识库检索（RAG）→ OpenAI 兼容 AI 生成答案
     * → 答案经 Ai 通道发回眼镜显示 + tts_play 语音播报。
     *
     * @param onStage 阶段状态回调（参数为 strings.xml 资源 id，UI 层可展示流程气泡）
     * @param onReply 最终答案回调（同时已发送到眼镜显示+播报）
     */
    fun startPhotoAsk(
        onStage: (Int) -> Unit = photoAskStageCb,
        onText: (String) -> Unit = photoAskTextCb,
        onReply: (String) -> Unit = photoAskReplyCb,
    ) {
        if (photoAskInProgress) {
            Log.i(TAG, "startPhotoAsk: already in progress, skip")
            return
        }
        photoAskInProgress = true
        // 全链路起点：记录开始时间，各阶段打印相对耗时（拍照/OCR/KB/AI），定位「出答案慢」
        val askStartMs = System.currentTimeMillis()
        Log.i(TAG, "startPhotoAsk: BEGIN, trigger=photo ask")
        onStage(com.rokidlab.phone.R.string.chat_photo_status)

        takeGlassesPhoto(
            width = 1024,
            height = 768,
            quality = 80,
            onPhoto = { jpeg ->
                Thread {
                    try {
                        Log.i(TAG, "photoAsk: photo received (${jpeg.size}B) after ${System.currentTimeMillis() - askStartMs}ms, starting OCR")
                        activity.runOnUiThread { onStage(com.rokidlab.phone.R.string.chat_ocr_status) }
                        // 2) 本地 OCR 识别题目文字
                        val tOcr = System.currentTimeMillis()
                        val text = runCatching {
                            val bmp = android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
                                ?: return@runCatching ""
                            try {
                                com.rokidlab.phone.ai.LocalOcr.recognize(activity, bmp)
                            } finally {
                                bmp.recycle()
                            }
                        }.getOrDefault("").trim()
                        Log.i(TAG, "photoAsk: OCR done in ${System.currentTimeMillis() - tOcr}ms -> ${text.take(50)}")
                        if (text.isEmpty()) {
                            Log.w(TAG, "photoAsk: OCR result empty, abort")
                            photoAskInProgress = false
                            activity.runOnUiThread { onStage(com.rokidlab.phone.R.string.chat_ocr_empty) }
                            return@Thread
                        }
                        // 3) 把识别出的文字回调给 UI（作为「用户消息」气泡展示）
                        activity.runOnUiThread { onText(text) }
                        // 4) 知识库检索相关资料（RAG）
                        activity.runOnUiThread { onStage(com.rokidlab.phone.R.string.chat_kb_status) }
                        val tKb = System.currentTimeMillis()
                        val kbText = com.rokidlab.phone.ai.KnowledgeBase
                            .search(activity, text, topK = 3)
                            .joinToString("\n\n")
                        Log.i(TAG, "photoAsk: KB search done in ${System.currentTimeMillis() - tKb}ms, hits=${kbText.length} chars")
                        // 4) 生成答案并发送到眼镜（显示 + 播报）
                        activity.runOnUiThread { onStage(com.rokidlab.phone.R.string.chat_ai_status) }
                        val tAi = System.currentTimeMillis()
                        sendAiTextMessage(
                            text,
                            contextText = kbText.ifBlank { null },
                            // 答题完成后保留眼镜端回复显示：skipTtsAudioFinished=true 不发送
                            // TTS_AudioFinished（该消息会触发官方会话 startNewTalk 重置，清掉刚显示的答案）
                            skipTtsAudioFinished = true,
                            // 注入设置页填写的答题指令（如「只显示答案」「给出解题步骤」）
                            instruction = getAiConfig().quizInstruction.ifBlank { null },
                            onResult = { success, err ->
                                photoAskInProgress = false
                                Log.i(TAG, "photoAsk: AI send onResult success=$success err=$err after ${System.currentTimeMillis() - tAi}ms (total ${System.currentTimeMillis() - askStartMs}ms)")
                                if (!success) {
                                    // 失败且无 onReply：通知 UI 复位 photoAsking（否则拍照问 AI 入口永久失效）
                                    activity.runOnUiThread { onStage(com.rokidlab.phone.R.string.chat_photo_failed) }
                                }
                            },
                            onReply = { reply ->
                                Log.i(TAG, "photoAsk: AI reply received (${reply.length} chars) after ${System.currentTimeMillis() - askStartMs}ms total")
                                onReply(reply)
                            },
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "startPhotoAsk failed", e)
                        photoAskInProgress = false
                        activity.runOnUiThread { onStage(com.rokidlab.phone.R.string.chat_photo_failed) }
                    }
                }.start()
            },
            onError = { err ->
                Log.e(TAG, "startPhotoAsk photo error: $err")
                photoAskInProgress = false
                activity.runOnUiThread { onStage(com.rokidlab.phone.R.string.chat_photo_failed) }
            },
        )
    }

    /**
     * 解析眼镜端轮询响应中的 ASR 文字（眼镜端 reply.end(Caps[text])）。
     */
    private fun parseAiAsrPollText(data: ByteArray?): String? {
        try {
            if (data == null || data.isEmpty()) return null
            val caps = Caps.fromBytes(data)
            if (caps == null || caps.size() < 1 || caps.at(0) == null) return null
            val text = caps.at(0).getString()?.trim().orEmpty()
            return text.ifEmpty { null }
        } catch (e: Exception) {
            Log.e(TAG, "parseAiAsrPollText error", e)
            return null
        }
    }

    /**
     * 安全切回主线程执行 UI 回调：Activity 已销毁（保活后台运行）时直接跳过，
     * 避免在已销毁 Activity 上调用 runOnUiThread 导致崩溃，同时下行链路不受影响。
     */
    private fun safeRunOnUiThread(block: () -> Unit) {
        try {
            if (activity.isDestroyed || activity.isFinishing) return
            activity.runOnUiThread {
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
    private fun handleGlassesAiAsrText(text: String) {
        try {
            // 双通道去重：push 推送 / 文件轮询 / Wifi_Connect_Status 上行可能同时收到
            // 同一段 ASR 文字（重复处理会并发触发 sendAiTextMessage，加剧 link 竞态）。
            // 3s 内相同文字只处理一次。
            val now = System.currentTimeMillis()
            if (text == lastAsrText && now - lastAsrTextAt < 3000) {
                Log.i(TAG, "handleGlassesAiAsrText: duplicate ASR text within 3s, skip")
                return
            }
            lastAsrText = text
            lastAsrTextAt = now

            Log.i(TAG, "handleGlassesAiAsrText: $text")
            // 用户提问同步到聊天窗口（轮询在 IO 线程，需切回主线程更新 Compose 状态）
            safeRunOnUiThread { glassesAiTextCb(text) }
            sendAiTextMessage(
                text,
                onResult = { success, err ->
                    Log.i(TAG, "handleGlassesAiAsrText sendAiTextMessage: success=$success err=$err")
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
        }
    }

    /**
     * 文件通道轮询（兜底）：
     * 眼镜端把 ASR 文字追加写入 ai_asr.log（每行 [ts] text），手机端经 ADB（蓝牙隧道）tail 读取。
     * 按时间戳去重并持久化，app 重启不会重复处理旧文本。
     * 主通道为 AsrPushClient（第二 RFCOMM 长连接，毫秒级实时接收）；
     * 眼镜端推送成功时不写文件，因此正常情况轮询无新数据，仅作为推送通道不可用时的兜底。
     */
    private fun startAiAsrBridgePolling() {
        aiAsrPollJob?.cancel()
        aiAsrPollJob = appScope.launch(Dispatchers.IO) {
            val prefs = activity.getSharedPreferences("adb_prefs", 0)
            var lastTs = prefs.getLong(KEY_LAST_ASR_TS, 0L)
            // 隧道异常退避：连接失败时逐步拉大间隔（5s→10s→…→30s），
            // 推送断开期间不再固定 5s 打一次隧道，避免 5556 本地端口积压溢出
            var backoffMs = AI_ASR_POLL_INTERVAL_MS
            while (isActive) {
                try {
                    // 主通道推送健康时跳过文件轮询：蓝牙隧道（RFCOMM）单串行连接，
                    // 每轮 ADB 连接都会占用隧道，推送正常时打隧道会造成
                    // 5556 本地端口接收积压溢出 → "connection refused"。
                    // 推送通道断开时才启用文件兜底轮询。
                    if (aiAsrPushClient?.isConnected != true) {
                        val r = readAiAsrBridgeTextOnce(lastTs)
                        if (r != null) {
                            if (r.tunnelOk) {
                                backoffMs = AI_ASR_POLL_INTERVAL_MS
                            } else {
                                backoffMs = (backoffMs * 2).coerceAtMost(AI_ASR_BACKOFF_MAX_MS)
                                Log.w(TAG, "ASR tunnel unavailable, backoff to ${backoffMs}ms")
                            }
                            r.text?.let { (ts, text) ->
                                lastTs = ts
                                prefs.edit().putLong(KEY_LAST_ASR_TS, ts).apply()
                                Log.i(TAG, "ASR via ADB bridge (fallback): $text")
                                // 打断已由眼镜端本地完成（KeyButtonService interruptOfficialLocally 发 Ai/Exit），
                                // 处理链路放后台线程执行（下行 sleep + DeepSeek join 耗时数秒），避免阻塞主线程。
                                Thread { handleGlassesAiAsrText(text) }.start()
                            }
                        }
                    } else {
                        // 推送恢复：复位退避
                        backoffMs = AI_ASR_POLL_INTERVAL_MS
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "aiAsrBridge poll error", e)
                    backoffMs = (backoffMs * 2).coerceAtMost(AI_ASR_BACKOFF_MAX_MS)
                }
                delay(backoffMs)
            }
        }
        // 推送通道：第二 RFCOMM 长连接，毫秒级实时接收眼镜端推送（正常主通道）。
        // 眼镜端推送成功时不会写文件，轮询自然无新数据；推送失败才写文件由轮询兜底。
        aiAsrPushClient?.stop()
        aiAsrPushClient = AsrPushClient(activity.applicationContext) { text ->
            try {
                // 音乐停止标记：眼镜端双击退出对话窗口时推送，收到后停止手机端音乐播放
                if (text == MUSIC_STOP_MARKER) {
                    Log.i(TAG, "Music stop marker received from glasses (conversation exited)")
                    com.rokidlab.phone.ai.MusicPlayerController.stop()
                    return@AsrPushClient
                }
                // 拍照答题标记：眼镜端镜腿按键时经 RFCOMM 通道推送（可靠区分按键意图，
                // 不依赖 AI App 网关，也不会被真实 resume 事件误触发）
                if (text == PHOTO_ASK_MARKER) {
                    Log.i(TAG, "Photo-ask marker received via RFCOMM push channel")
                    // 拍照+OCR+AI 全流程耗时数秒，切后台线程执行，避免阻塞 RFCOMM 读线程
                    Thread { startPhotoAsk() }.start()
                    return@AsrPushClient
                }
                // 更新去重游标：推送文字无真实时间戳，用接收时刻作为游标，
                // 防止轮询兜底读到同一条文字重复处理（眼镜端推送失败写文件的场景）。
                val prefs = activity.getSharedPreferences("adb_prefs", 0)
                prefs.edit().putLong(KEY_LAST_ASR_TS, System.currentTimeMillis()).apply()
                Log.i(TAG, "ASR via push channel: $text")
                Thread { handleGlassesAiAsrText(text) }.start()
            } catch (e: Exception) {
                Log.e(TAG, "asr push handle error", e)
            }
        }
        aiAsrPushClient?.start()
        Log.i(TAG, "startAiAsrBridgePolling: started (push + file fallback)")
    }

    private fun stopAiAsrBridgePolling() {
        aiAsrPollJob?.cancel()
        aiAsrPollJob = null
        aiAsrPushClient?.stop()
        aiAsrPushClient = null
    }

    /**
     * 创建一次性 ADB 短连接（用后即断），避免后台轮询长连接独占蓝牙隧道。
     * 必须在后台线程调用（同步阻塞连接握手）。连接失败返回 null。
     */
    private fun createShortAdbClient(): com.rokidlab.phone.adb.AdbShellClient? {
        return runCatching {
            val app = activity.application as LabApplication
            val prefs = activity.getSharedPreferences("adb_prefs", 0)
            val wifiIp = prefs.getString("ip", "192.168.1.168") ?: "192.168.1.168"
            val route = runBlocking { app.routeManager.resolve(wifiIp, 5555) }
            val (targetIp, targetPort) = when (route) {
                is ConnectionRoute.Wifi -> route.ip to route.port
                is ConnectionRoute.Bluetooth -> route.ip to route.localPort
                is ConnectionRoute.None -> return null
            }
            val client = com.rokidlab.phone.adb.AdbShellClient(activity.applicationContext, targetIp, targetPort)
            if (client.connect()) client else {
                runCatching { client.disconnect() }
                // 连接失败：清除线路缓存，下轮重新探测重建隧道（蓝牙隧道可能已断开）
                runCatching { app.routeManager.clearRouteCache() }
                null
            }
        }.getOrNull()
    }

    /** 文件通道轮询单次结果：text=读到的新文本（可能 null）；tunnelOk=隧道连接是否成功 */
    private data class AsrBridgeRead(val text: Pair<Long, String>?, val tunnelOk: Boolean)

    /**
     * 通过 ADB 读取眼镜端 ai_asr.log 中时间戳大于 lastTs 的最新 ASR_TEXT（短连接，用后即断）。
     * 返回 AsrBridgeRead：tunnelOk=false 表示隧道不可用（调用方应退避，避免持续打隧道），
     * text 为 null 表示隧道正常但无新文本。
     */
    private fun readAiAsrBridgeTextOnce(lastTs: Long): AsrBridgeRead? {
        val client = createShortAdbClient() ?: return AsrBridgeRead(null, false)
        return try {
            val out = runCatching {
                client.executeShellCommand("tail -n 20 $GLASSES_ASR_FILE 2>/dev/null", 10_000)
            }.getOrNull()
            if (out == null) return AsrBridgeRead(null, false)
            val latest = out.lineSequence()
                .mapNotNull { line ->
                    // 每行格式：[epochMs] text；解析失败（半行/脏数据）则忽略
                    val m = Regex("""\[(\d+)\] (.*)""").matchEntire(line.trim()) ?: return@mapNotNull null
                    val ts = m.groupValues[1].toLongOrNull() ?: return@mapNotNull null
                    val text = m.groupValues[2].trim()
                    if (text.isEmpty()) null else ts to text
                }
                .lastOrNull()
            // 隧道连接成功；latest 可能为 null（无新数据），也可能时间戳不新
            AsrBridgeRead(if (latest != null && latest.first > lastTs) latest else null, true)
        } finally {
            runCatching { client.disconnect() }
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
            activity.runOnUiThread {
                if (!done) {
                    Log.w(TAG, "requestPhotoFromLink: no image callback within 15s, forcing error")
                    finish { onError("photo timeout") }
                }
            }
        }

        link.setCXRImageCbk(object : IImageStreamCbk {
            override fun onImageReceived(data: ByteArray) {
                Log.i(TAG, "onImageReceived: ${data.size} bytes")
                activity.runOnUiThread { finish { onPhoto(data) } }
            }

            override fun onImageError(code: Int, message: String) {
                Log.e(TAG, "onImageError($code): $message")
                activity.runOnUiThread { finish { onError("photo error($code): $message") } }
            }
        })

        val ok = link.takePhoto(width, height, quality)
        Log.i(TAG, "takePhoto -> $ok")
        if (!ok) {
            activity.runOnUiThread { finish { onError("takePhoto failed") } }
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
        activity.runOnUiThread {
            completeActiveOperation()
            onBusyChanged(false)
            onResult?.invoke(false, "link disconnected")
        }
        return false
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
    ) {
        // 串行化所有 AI 下行发送：聊天发送 / ASR push / 文件轮询 / SDK 上行多个并发入口
        // 在 WiFi 稳定连接时全部命中快速路径，同一 CXRLink 并发 sendCustomCmd 会与
        // cleanup() 的 disconnect 产生竞态（SDK native 崩溃）。加锁保证同一时刻只有
        // 一条 AI 下行链路执行，并在发送过程中持续校验 link 有效性。
        synchronized(aiSendLock) {
        // 入口校验：link 必须仍是最新且未断开（防止慢速路径 cleanup 后使用旧 link）
        if (!abortAiSendIfLinkInvalid(link, onResult)) return
        // 反射绕过 CXR-L SDK 的 cmd 黑名单
        try {
            val field = link.javaClass.superclass.getDeclaredField("d")
            field.isAccessible = true
            field.set(link, arrayOf<String>())
            Log.i(TAG, "CXR-L cmd blacklist bypassed (cleared)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bypass CXR-L cmd blacklist", e)
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
        if (interruptOfficialFirst && !localTakeover) {
            val exitCaps = Caps()
            exitCaps.write("Exit")
            val exitResult = link.sendCustomCmd("Ai", exitCaps)
            Log.i(TAG, "sendCustomCmd(Ai, Exit) interrupt official -> $exitResult")
            Thread.sleep(300)
        }

        // ===== 步骤3 提前并行：后台线程调用 AI 获取回复（与下行显示并行，省 1.5~2s）=====
        // 支持工具调用（function calling）：AI 可自主决定调用本地能力（如知识库检索），
        // 执行结果回填后再生成最终回复；最终回复照常走下方 TTS 链路到眼镜显示并语音播报。
        val replyRef = java.util.concurrent.atomic.AtomicReference<String>("")
        val deepSeekThread = Thread {
            val tGenStart = System.currentTimeMillis()
            try {
                val cfg = getAiConfig()
                val service = com.rokidlab.phone.ai.OpenAiService(cfg.apiKey, cfg.model, cfg.baseUrl)
                val messages = JSONArray()
                messages.put(service.buildSystemMessage(contextText, instruction))
                val userMsg = JSONObject()
                userMsg.put("role", "user")
                userMsg.put("content", text)
                messages.put(userMsg)

                var reply = ""
                // 最多 3 轮工具循环，防止模型反复请求工具导致死循环
                for (round in 0 until 3) {
                    val turn = service.chatTurn(messages, tools = ToolRegistry.schemas(activity))
                    if (turn.toolCalls.isEmpty()) {
                        reply = turn.content.orEmpty()
                        break
                    }
                    // 回填 assistant 消息（OpenAI 协议要求原样带上 tool_calls）
                    val assistantMsg = JSONObject()
                    assistantMsg.put("role", "assistant")
                    assistantMsg.put("content", JSONObject.NULL)
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

                    // 依次执行工具，结果以 tool 消息回填
                    for (tc in turn.toolCalls) {
                        val result = try {
                            ToolRegistry.execute(activity, tc.name, tc.arguments)
                        } catch (e: Exception) {
                            Log.e(TAG, "tool execute failed: ${tc.name}", e)
                            "工具执行失败: ${e.message}"
                        }
                        onStatus("已调用工具: ${tc.name}")
                        Log.i(TAG, "tool ${tc.name}(${tc.arguments}) -> ${result.take(100)}")
                        messages.put(JSONObject().apply {
                            put("role", "tool")
                            put("tool_call_id", tc.id)
                            put("content", result)
                        })
                    }
                }
                if (reply.isBlank()) reply = "抱歉，我暂时无法处理这个问题，请换个说法再试一次。"
                replyRef.set(reply)
                Log.i(TAG, "AI reply generated in ${System.currentTimeMillis() - tGenStart}ms: ${reply.take(80)}...")
            } catch (e: Exception) {
                Log.e(TAG, "DeepSeek API failed", e)
                replyRef.set("抱歉，AI 服务暂时不可用。")
            }
        }.apply { start() }

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
            if (!abortAiSendIfLinkInvalid(link, onResult)) return
            keyDownResult = link.sendCustomCmd("Ai", keyDownCaps)
            Log.i(TAG, "sendCustomCmd(Ai, KeyDown_Client, privacy_level=2) -> $keyDownResult")
            Thread.sleep(600)

            // 0b. 发送 Ai + open：眼镜端 AIOpenHandler 调用 startNewTalk()，开启 AI 对话
            val openCaps = Caps()
            openCaps.write("open")
            if (!abortAiSendIfLinkInvalid(link, onResult)) return
            openResult = link.sendCustomCmd("Ai", openCaps)
            Log.i(TAG, "sendCustomCmd(Ai, open) -> $openResult")
            Thread.sleep(400)

            // ===== 步骤1: 发送 ASR_Result（用户文字）=====
            // 语音唤醒链路（showAsrResult=false）：官方 ASR 已在眼镜上显示提问，不再重发避免重复显示。
            if (showAsrResult) {
                val asrCaps = Caps()
                asrCaps.write("ASR_Result")
                asrCaps.write(text)
                if (!abortAiSendIfLinkInvalid(link, onResult)) return
                asrResult = link.sendCustomCmd("Ai", asrCaps)
                Log.i(TAG, "sendCustomCmd(Ai, ASR_Result, \"$text\") -> $asrResult")
            } else {
                Log.i(TAG, "skip ASR_Result resend (voice wakeup chain, question already shown)")
            }

            // ===== 步骤2: 发送 ASR_End（标记 ASR 结束）=====
            val endCaps = Caps()
            endCaps.write("ASR_End")
            if (!abortAiSendIfLinkInvalid(link, onResult)) return
            endResult = link.sendCustomCmd("Ai", endCaps)
            Log.i(TAG, "sendCustomCmd(Ai, ASR_End) -> $endResult")
            onStatus("已发送到眼镜，正在获取 AI 回复...")
        } else {
            Log.i(TAG, "localTakeover: skip KeyDown/open/ASR_Result/ASR_End (glasses already shown)")
            onStatus("正在获取 AI 回复...")
        }

        // ===== 等待 DeepSeek 完成（下行显示期间已并行执行）=====
        deepSeekThread.join()
        reply = replyRef.get()
        Log.i(TAG, "AI reply ready: ${reply.take(40)}")
        onReply?.invoke(reply)
        } catch (e: Exception) {
            // 下行指令段（sendCustomCmd/sleep/join）异常：统一复位状态，避免 sending/busy 永久卡死
            Log.e(TAG, "AI send downlink failed", e)
            activity.runOnUiThread {
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
                ttsPlayCaps.write("tts_play")
                ttsPlayCaps.write(reply)
                return link.sendCustomCmd("tts_play", ttsPlayCaps)
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

            activity.runOnUiThread {
                onStatus("AI 回复已发送: \"${reply.take(30)}...\"")
                completeActiveOperation()
                onBusyChanged(false)
                onResult?.invoke((if (showAsrResult) (asrResult ?: -1) == 0 else true) && endResult == 0 && ttsResult == 0, null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "AI reply send failed", e)
            activity.runOnUiThread {
                onStatus("AI 回复失败: ${e.message}")
                completeActiveOperation()
                onBusyChanged(false)
                onResult?.invoke(false, "AI send error: ${e.message}")
            }
        }
        }
    }

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
        stopAiAsrBridgePolling()
        runCatching { cxrLink?.disconnect() }
        cxrLink = null
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
                timeoutMessage = activity.getString(R.string.waiting_install_result, targetHostApp.displayName),
                bindMessage = activity.getString(R.string.binding_service, targetHostApp.displayName),
                configureFailureMessage = activity.getString(R.string.cxrl_config_failed),
                bindFailureMessage = activity.getString(R.string.service_bind_failed, targetHostApp.displayName, targetHostApp.displayName),
                showConnectionStatus = true,
                onReady = { link ->
                    android.util.Log.i("CxrLInstall", "connectAndUpload: onReady! Starting appUploadAndInstall...")
                    onStatus(activity.getString(R.string.cxrl_ready_installing))
                    link.appUploadAndInstall(apkFile.absolutePath, glassAppCallback(
                        onInstall = { success ->
                            completeActiveOperation()
                            onStatus(if (success) activity.getString(R.string.glasses_install_success) else activity.getString(R.string.glasses_install_failed))
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
                timeoutMessage = activity.getString(R.string.query_timeout, packageName),
                configureFailureMessage = activity.getString(R.string.query_config_failed, packageName),
                bindFailureMessage = activity.getString(R.string.service_bind_failed, targetHostApp.displayName, targetHostApp.displayName),
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
                timeoutMessage = activity.getString(R.string.uninstall_timeout, packageName),
                bindMessage = activity.getString(R.string.binding_service, targetHostApp.displayName),
                configureFailureMessage = activity.getString(R.string.uninstall_config_failed, packageName),
                bindFailureMessage = activity.getString(R.string.service_bind_failed, targetHostApp.displayName, targetHostApp.displayName),
                showConnectionStatus = true,
                onReady = { link ->
                    onStatus(activity.getString(R.string.cxrl_ready_uninstalling, packageName))
                    link.appUninstall(glassAppCallback(
                        onUninstall = { success ->
                            completeActiveOperation()
                            onStatus(if (success) activity.getString(R.string.glasses_uninstall_success) else activity.getString(R.string.glasses_uninstall_failed))
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
                timeoutMessage = activity.getString(R.string.launch_timeout, packageName),
                bindMessage = activity.getString(R.string.binding_service, targetHostApp.displayName),
                configureFailureMessage = activity.getString(R.string.launch_config_failed, packageName),
                bindFailureMessage = activity.getString(R.string.service_bind_failed, targetHostApp.displayName, targetHostApp.displayName),
                showConnectionStatus = true,
                onReady = { link ->
                    onStatus(activity.getString(R.string.cxrl_ready_launching, packageName))
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
                                    onStatus(activity.getString(R.string.cmd_result, sendCmdAfterLaunch, cmdResult))
                                }
                            }
                            completeActiveOperation()
                            onStatus(if (success) activity.getString(R.string.glasses_launch_success, packageName) else activity.getString(R.string.glasses_launch_failed, packageName))
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
                timeoutMessage = activity.getString(R.string.stop_timeout, packageName),
                bindMessage = activity.getString(R.string.binding_service, targetHostApp.displayName),
                configureFailureMessage = activity.getString(R.string.stop_config_failed, packageName),
                bindFailureMessage = activity.getString(R.string.service_bind_failed, targetHostApp.displayName, targetHostApp.displayName),
                showConnectionStatus = true,
                onReady = { link ->
                    onStatus(activity.getString(R.string.cxrl_ready_stopping, packageName))
                    link.appStop(object : IGlassAppCbk {
                        override fun onInstallAppResult(success: Boolean) = Unit
                        override fun onUnInstallAppResult(success: Boolean) = Unit
                        override fun onOpenAppResult(success: Boolean) = Unit
                        override fun onStopAppResult(success: Boolean) {
                            completeActiveOperation()
                            onStatus(if (success) activity.getString(R.string.glasses_stop_success, packageName) else activity.getString(R.string.glasses_stop_failed, packageName))
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

    private fun connectAndRunCustomAppOperation(
        authToken: String,
        targetHostApp: RokidHostApp,
        operation: CxrAppOperation,
    ) {
        cleanup()
        val link = CXRLink(activity.applicationContext).also { newLink ->
            newLink.setCXRLinkCbk(FullCXRLinkCallback(
                onConnected = { connected ->
                    activity.runOnUiThread {
                        cxrlConnected = connected
                        if (operation.showConnectionStatus) onStatus(activity.getString(R.string.cxrl_service_connected, connected.toString()))
                        if (connected) startAiAsrBridgePolling() else stopAiAsrBridgePolling()
                        notifyConnectionChanged()
                        maybeRunPendingOperation()
                    }
                },
                onBtConnected = { connected ->
                    activity.runOnUiThread {
                        glassBtConnected = connected
                        if (operation.showConnectionStatus) onStatus(activity.getString(R.string.bluetooth_connected_status, connected.toString()))
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
        startAiAsrBridgePolling()
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
     * 统一注册手机端收到的「眼镜 → 手机」指令监听。
     * 每个新建立的 CXRLink 都要注册一次，处理：
     *  - Wifi_Connect_Status：WiFi 连接状态回执（转发给 sendWifiConfig）
     *  - rokidlab_photo_ask：眼镜端镜腿按键触发「拍照问AI」
     */
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
                val quiz = activity.getSharedPreferences(AI_PREFS, 0)
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
                    // 分步验证-第1步：收到眼镜端上行信号（ASR 期间真实界面 resume）后打断官方回复。
                    // 延迟 800ms 等 ASR 结束再发 Ai/open（startNewTalk 重开对话）终止官方回复
                    appScope.launch {
                        delay(800)
                        interruptOfficialAi(link)
                    }
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

    /** 打断官方乐奇会话。
     *  "Ai/Exit" 会被 AI App 拦截（眼镜端 subscribe(Ai) 收不到），改用白名单指令
     *  "Ai/open"（startNewTalk 重开对话）来终止官方正在进行的回复。 */
    private fun interruptOfficialAi(link: CXRLink) {
        try {
            val field = link.javaClass.superclass.getDeclaredField("d")
            field.isAccessible = true
            field.set(link, arrayOf<String>())
        } catch (e: Exception) {
            Log.w(TAG, "clear cmd blacklist failed: ${e.message}")
        }
        try {
            val caps = Caps()
            caps.write("open")
            val r = link.sendCustomCmd("Ai", caps)
            Log.i(TAG, "interrupt official AI (Ai/open) -> $r")
        } catch (e: Exception) {
            Log.e(TAG, "interrupt official AI error", e)
        }
    }

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
                            appScope.launch(Dispatchers.IO) { handleGlassesAiAsrText(asrText) }
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
                            appScope.launch(Dispatchers.IO) { handleGlassesAiAsrText(text) }
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
            activity.runOnUiThread { onInstall(success) }
        }

        override fun onUnInstallAppResult(success: Boolean) {
            activity.runOnUiThread { onUninstall(success) }
        }

        override fun onOpenAppResult(success: Boolean) {
            activity.runOnUiThread { onStart(success) }
        }
        override fun onStopAppResult(success: Boolean) = Unit
        override fun onGlassAppResume(resumed: Boolean) = Unit

        override fun onQueryAppResult(installed: Boolean) {
            activity.runOnUiThread { onQuery(installed) }
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
        return runCatching {
            val intent = Intent(MEDIA_SERVICE_ACTION)
                .setPackage(targetHostApp.packageName)
                .putExtra(AUTH_TOKEN_EXTRA, authToken)
                .putExtra(AUTH_PACKAGE_EXTRA, activity.packageName)
            activity.applicationContext.bindService(intent, conn, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
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
        val wifiManager = activity.applicationContext.getSystemService(WifiManager::class.java)
        return wifiManager?.isWifiEnabled == true
    }

    private fun hasGlassesOperationPrerequisites(
        targetHostApp: RokidHostApp,
        requestAuthorizationIfMissing: Boolean,
    ): Boolean {
        if (!isHostAppInstalled(targetHostApp)) {
            onStatus(activity.getString(R.string.install_host_first, targetHostApp.displayName))
            return false
        }
        if (token.isNullOrBlank()) {
            onStatus(activity.getString(R.string.authorize_in_host, targetHostApp.displayName))
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
            activity.packageManager.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.GET_ACTIVITIES)
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

data class CxrConnectionState(
    val authorized: Boolean = false,
    val cxrlConnected: Boolean = false,
    val glassBtConnected: Boolean = false,
) {
    val connected: Boolean
        get() = cxrlConnected && glassBtConnected

    val connecting: Boolean
        get() = authorized && (cxrlConnected || glassBtConnected) && !connected
}
