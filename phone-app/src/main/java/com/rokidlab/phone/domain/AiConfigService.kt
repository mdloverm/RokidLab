package com.rokidlab.phone.domain

import android.util.Log
import com.rokid.cxr.Caps
import com.rokidlab.phone.glasses.AiChannel
import com.rokidlab.phone.glasses.CxrLHiRokidSession.Companion.AI_PREFS
import com.rokidlab.phone.glasses.CxrLHiRokidSession.Companion.KEY_AI_API_KEY
import com.rokidlab.phone.glasses.CxrLHiRokidSession.Companion.KEY_AI_BASE_URL
import com.rokidlab.phone.glasses.CxrLHiRokidSession.Companion.KEY_AI_LOCAL_MODEL
import com.rokidlab.phone.glasses.CxrLHiRokidSession.Companion.KEY_AI_LOCAL_PARAMS
import com.rokidlab.phone.glasses.CxrLHiRokidSession.Companion.KEY_AI_LOCAL_THINK_LEGACY
import com.rokidlab.phone.glasses.CxrLHiRokidSession.Companion.KEY_AI_MODE
import com.rokidlab.phone.glasses.CxrLHiRokidSession.Companion.KEY_AI_MODEL
import com.rokidlab.phone.glasses.CxrLHiRokidSession.Companion.KEY_AI_THINKING
import com.rokidlab.phone.glasses.CxrLHiRokidSession.Companion.KEY_AI_USE_LOCAL
import com.rokidlab.phone.glasses.CxrLHiRokidSession.Companion.KEY_QUIZ_INSTRUCTION
import com.rokidlab.phone.util.SecretStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

/** OpenAI 兼容 AI 服务配置（原 `CxrLHiRokidSession.AiConfig` 嵌套类，Phase 3 迁至 domain 顶层） */
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
 * L3 domain/AiConfigService —— Phase 3 拆 `CxrLHiRokidSession` 的 AI 配置域服务。
 *
 * 职责：在线 AI（DeepSeek 等 OpenAI 兼容服务）与本地模型（Ollama）两个配置槽位的
 * 读写、迁移（旧 think 布尔键 → JSON 参数）、以及把有效配置下发眼镜端。
 *
 * 依赖：L2 会话状态（appContext / appScope / cxrLink 经 session 句柄）；
 * prefs 键常量仍以 `CxrLHiRokidSession.Companion` 为单一数据源（避免双端漂移）。
 * Session 保留同名 public 门面（ChatSettingsDialog / LocalModelPage / ChatScreen 零改动）。
 */
class AiConfigService(private val session: com.rokidlab.phone.glasses.CxrLHiRokidSession) {
    private companion object { const val TAG = "AiConfigService" }

    /** DeepSeek API Key（用户显式配置后保存；禁止内置 key 防止反编译盗用） */
    private var deepSeekApiKey: String = ""

    private var aiConfigPushJob: Job? = null

    /** 更新 DeepSeek API Key */
    fun setDeepSeekApiKey(key: String) {
        deepSeekApiKey = key
    }

    /**
     * 保存「在线」AI 配置（自定义服务/乐奇官方），并关闭本地模型模式。
     * 本地模型走 [setLocalChatModel]，不会写入本槽位。
     */
    fun setAiConfig(config: AiConfig) {
        runCatching {
            val prefs = session.appContext.getSharedPreferences(AI_PREFS, 0)
            prefs.edit()
                .putString(KEY_AI_BASE_URL, config.baseUrl)
                .putString(KEY_AI_MODEL, config.model)
                .putString(KEY_AI_MODE, config.mode)
                .putString(KEY_QUIZ_INSTRUCTION, config.quizInstruction)
                .putBoolean(KEY_AI_USE_LOCAL, false)
                // 切到在线/自定义服务时清掉残留的本地模型名，
                // 否则 ai_local_model 非空会让设置弹窗的 LaunchedEffect 误判为「仍选本地模型」。
                .remove(KEY_AI_LOCAL_MODEL)
                .apply()
            // API Key 单独走 Keystore 加密落盘（prefs 里只有密文）
            SecretStore.put(prefs, KEY_AI_API_KEY, config.apiKey)
        }
        if (config.apiKey.isNotBlank()) deepSeekApiKey = config.apiKey
        // 同步下发到眼镜端：唤醒词识别出的文字由眼镜端直接调用该模型回复
        pushAiConfigToGlass(config)
    }

    /** 是否已启用本地 Ollama 作为眼镜对话模型 */
    fun isLocalChatActive(): Boolean =
        session.appContext.getSharedPreferences(AI_PREFS, 0).getBoolean(KEY_AI_USE_LOCAL, false)

    /** 当前选择的本地对话模型名（未启用/未选择返回空串） */
    fun localChatModel(): String =
        session.appContext.getSharedPreferences(AI_PREFS, 0).getString(KEY_AI_LOCAL_MODEL, "").orEmpty()

    /** 本地对话请求的自定义 JSON 参数（原始字符串，空串=未配置）。
     *  旧版 ai_local_think 布尔开关首次读取时自动迁移为 {"think": <旧值>} 并清除旧键 */
    fun localChatParams(): String {
        val prefs = session.appContext.getSharedPreferences(AI_PREFS, 0)
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
        session.appContext.getSharedPreferences(AI_PREFS, 0).edit()
            .putString(KEY_AI_LOCAL_PARAMS, json).apply()
    }

    /** 解析本地请求参数为 JSON 对象；未配置/非法返回 null（非法时打日志并忽略，不影响对话） */
    internal fun parseLocalChatParams(): JSONObject? {
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
        val prefs = session.appContext.getSharedPreferences(AI_PREFS, 0)
        val baseUrl = prefs.getString(KEY_AI_BASE_URL, "").orEmpty().ifBlank { "https://api.deepseek.com" }
        val apiKey = SecretStore.get(prefs, KEY_AI_API_KEY).orEmpty()
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
            session.appContext.getSharedPreferences(AI_PREFS, 0).edit()
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
            session.appContext.getSharedPreferences(AI_PREFS, 0).edit()
                .putString(KEY_QUIZ_INSTRUCTION, text.trim())
                .apply()
        }
    }

    /** 仅回填在线槽位的 API Key（旧版 deepseek_key 迁移用，不影响本地开关与在线其他字段） */
    fun backfillOnlineApiKey(key: String) {
        if (key.isBlank()) return
        runCatching {
            SecretStore.put(
                session.appContext.getSharedPreferences(AI_PREFS, 0),
                KEY_AI_API_KEY,
                key,
            )
        }
        pushAiConfigToGlass(getAiConfig())
    }

    /** 在线模型长思考是否开启（默认关闭：思考吞输出预算导致工具调用空轮，已实测） */
    fun isThinkingEnabled(): Boolean =
        session.appContext.getSharedPreferences(AI_PREFS, 0).getBoolean(KEY_AI_THINKING, false)

    /** 持久化在线模型长思考开关（全局生效：眼镜语音与手机聊天共用同一在线槽位） */
    fun setThinkingEnabled(enabled: Boolean) {
        runCatching {
            session.appContext.getSharedPreferences(AI_PREFS, 0).edit()
                .putBoolean(KEY_AI_THINKING, enabled)
                .apply()
        }
        Log.i(TAG, "AI thinking mode = $enabled")
    }

    /** 下发 AI 配置（baseUrl/apiKey/model/mode）到眼镜端，供眼镜端本地直接调用模型。
     *  AI App 的 binder 在 bind 后异步就绪，此处每秒重试直到成功（最多 30 次）。 */
    internal fun pushAiConfigToGlass(config: AiConfig) {
        aiConfigPushJob?.cancel()
        aiConfigPushJob = session.appScope.launch {
            repeat(30) { attempt ->
                val link = session.cxrLink
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
        val prefs = session.appContext.getSharedPreferences(AI_PREFS, 0)
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

    /** 取消进行中的配置下发协程（Session.cleanup 调用） */
    fun cancelPushJob() {
        aiConfigPushJob?.cancel()
        aiConfigPushJob = null
    }
}
