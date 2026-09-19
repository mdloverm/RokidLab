package com.rokidlab.phone.ai.llm

import android.content.Context
import android.util.Log
import com.rokidlab.phone.ai.LocalOllamaManager
import com.rokidlab.phone.ai.OpenAiService
import com.rokidlab.phone.domain.AiConfig
import org.json.JSONObject

/**
 * LLM 接缝的**唯一入口**（`llm` 接缝的 Service Definition）。
 *
 * 改造前的问题（2026-09-19）：`OpenAiService` 在三个地方被各自 new 出来，每个地方都自己决定
 * 超时、要不要附加本地调参、要不要开思考：
 *  - `AiConversationService`：`readTimeout = if (local) 180s else 30s`，本地才附 `extraBody`
 *  - `TimerScheduler`：写死 `8s`
 *  - `ChatSettingsDialog`：什么都不传（拿默认 30s）
 * 同时"这是不是本地端点"的判断（`contains("127.0.0.1") || contains("localhost") || contains("11434")`）
 * 在别处还有另外几份各写各的。于是"换/加一个模型"要在多处同步改，
 * 而"模型支持什么"这件事干脆没有任何地方表达（见 [ModelCapabilities] 的说明）。
 *
 * 现在：**构造客户端只走 [newService]**、「是不是本地」只走 [isLocalBase]、
 * 「模型支持什么」只走 [capabilities]。所有消费方（对话主循环 / 后台任务 / 设置页）
 * 读的是同一份答案。
 *
 * @see ModelCapabilities 能力数据面
 * @see ModelPresets 内置模型家族表（Provider 侧）
 * @see ModelCapabilityProbe 把"猜"变成"实测"的探测
 * @see CapabilityCache 探测结论落盘
 */
internal object LlmRegistry {
    private const val TAG = "LlmRegistry"

    // ═══════════════════ 请求档位（Profile）═══════════════════

    /**
     * 主对话链路的远程读超时。
     *
     * 保持 30s 是刻意的：远程端点读超时也等于「用户打断」的让出时间上界 ——
     * SSE 阻塞在 readLine 期间无法感知 `isCancelled`，超时太长会让打断变成"点了没反应"。
     * 代码生成轮需要更长时间时由调用方在请求级传大值（见 `chatTurnStream` 的 readTimeout）。
     */
    private const val CHAT_REMOTE_TIMEOUT_MS = 30_000

    /**
     * 主对话链路的本地读超时。
     *
     * 放宽到 3 分钟：本地 Ollama 首次加载模型（几 GB 权重进内存）或思考模型首字，
     * 远超 30s，用远程超时会在首包到达前就超时、白白重放整轮。
     */
    private const val CHAT_LOCAL_TIMEOUT_MS = 180_000

    /**
     * 后台轻量链路（定时任务的自然语言解析等）。
     *
     * 写死 8s 沿用改造前的取值：这类任务**失败是可以接受的**（顶多把原始文本原样展示），
     * 而等待是有代价的（用户正等着定时任务触发的结果）。不该跟主对话共用超时。
     */
    private const val BACKGROUND_TIMEOUT_MS = 8_000

    /** 请求档位：决定超时与是否附加本地调参，让调用方不必再自己拼这些 */
    enum class Profile {
        /** 主对话（乐奇聊天 / 眼镜语音 / 拍照答题） */
        CHAT,

        /** 后台一次性调用（定时任务解析等），超时短、失败可弃 */
        BACKGROUND,

        /**
         * 只读子代理（`research_subtask` 的多轮调研循环）。
         *
         * 为什么不能复用 [BACKGROUND]：那个档位是"**失败可以接受**"的短调用（8s，超时就把原文
         * 原样展示）；子代理要连跑好几轮工具 + 读网页，8s 必然不够，而且它是**同步调用**
         * （主循环在等它的结论），超时等于把一次正常调研判死。
         * 也不能复用 [CHAT]：子代理不接用户的"思考开关"（那是交互语境的偏好），
         * 也不该继承本地 OLLAMA 的聊天调参。
         */
        SUBAGENT,
    }

    /**
     * 本次调用附带的运行时参数。
     *
     * @param thinkingEnabled 是否开启长思考（仅在线 DeepSeek 系生效；本地由 [localParams] 自己调）
     * @param localParams 本地 Ollama 的自定义请求参数（`{"think":false,"options":{"num_ctx":2048}}`）；
     *   远程端点**不附加** —— 这些字段是 Ollama 专有，发给 OpenAI 兼容端点会 400
     */
    data class Options(
        val thinkingEnabled: Boolean = false,
        val localParams: JSONObject? = null,
    )

    // ═══════════════════ 路由解析 ═══════════════════

    /**
     * 是否本机回环端点（= 本地 Ollama）。
     *
     * 判断口径收口在此处：改造前同样的三条件判断在 `AiConversationService` 与
     * `LocalOllamaManager` 各写了一遍，任何一处漏改都会让本地模型的超时/参数附加失效。
     */
    fun isLocalBase(baseUrl: String): Boolean {
        val b = baseUrl.lowercase()
        return b.contains("127.0.0.1") || b.contains("localhost") || b.contains("11434")
    }

    /** 解析本次请求真正会打到哪个模型、它有哪些能力 */
    fun route(cfg: AiConfig, ctx: Context? = null, options: Options = Options()): ModelRoute {
        val local = isLocalBase(cfg.baseUrl)
        return ModelRoute(
            id = ModelRoute.keyOf(cfg.baseUrl, cfg.model),
            provider = ModelPresets.providerOf(cfg.baseUrl, cfg.model),
            model = cfg.model,
            baseUrl = cfg.baseUrl,
            isLocal = local,
            capabilities = capabilities(cfg, ctx, options),
        )
    }

    /**
     * 模型能力的解析入口（**不发网络请求**，可在主线程调用）。
     *
     * 证据链按可信度从强到弱，先命中先返回：
     *  1. **本机实测**（[CapabilityCache]，标 [CapabilitySource.PROBED]）—— 只覆盖图像能力，
     *     其余字段仍取下面两级的结论（探测只验证了图像这一件事，不该顺手改写别的）；
     *  2. **服务端自报**（仅本地 Ollama 有，[CapabilitySource.DECLARED]）；
     *  3. **内置模型表**（[ModelPresets]，[CapabilitySource.PRESET]）；
     *  4. **保守默认**（[ModelPresets.ASSUMED]：图像能力未知 ⇒ 允许尝试、失败回退）。
     *
     * 本地模型还会被 [Options.localParams] 里的 `options.num_ctx` 覆盖上下文窗口 ——
     * 用户在本地页手填的参数就是这次请求真正用的窗口，比任何来源都准。
     */
    fun capabilities(
        cfg: AiConfig,
        ctx: Context? = null,
        options: Options = Options(),
    ): ModelCapabilities {
        val local = isLocalBase(cfg.baseUrl)
        val base = declaredCapabilities(cfg.model, local)
            ?: ModelPresets.lookup(cfg.model, local)
            ?: ModelPresets.ASSUMED
        var caps = CapabilityCache.read(ctx, cfg.baseUrl, cfg.model)
            ?.let { base.withImage(it.image, CapabilitySource.PROBED) }
            ?: base
        if (local) {
            val numCtx = options.localParams?.optJSONObject("options")?.optInt("num_ctx", 0) ?: 0
            if (numCtx > 0) caps = caps.withContextWindow(numCtx)
        }
        return caps
    }

    /** 把本地 Ollama 自报的能力翻译成 [ModelCapabilities]；没有自报信息返回 null */
    private fun declaredCapabilities(model: String, local: Boolean): ModelCapabilities? {
        if (!local) return null
        val d = LocalOllamaManager.cachedCapabilitiesOf(model) ?: return null
        // 旧版 Ollama 两者都不给 → 视作"没有自报"，交给内置表，别把一个空表当成"什么能力都没有"
        if (d.capabilities.isEmpty() && d.contextLength <= 0) return null
        return ModelCapabilities(
            contextWindow = d.contextLength,
            // 自报表非空时它是权威答案；为空则沿用保守默认（支持工具）
            supportsTools = if (d.capabilities.isEmpty()) true else d.supportsTools,
            supportsImage = if (d.capabilities.isEmpty()) null else d.supportsVision,
            supportsStreaming = true,
            // Ollama 的 "thinking" 指的是"这是个思考型模型"，与 DeepSeek 专有的
            // `thinking:{"type":"disabled"}` 关思考字段不是一回事，本地一律不下发该字段
            supportsThinkingDisable = false,
            source = CapabilitySource.DECLARED,
        )
    }

    // ═══════════════════ 客户端构造（唯一入口）═══════════════════

    /**
     * 按档位构造 OpenAI 兼容客户端。**所有 `OpenAiService` 的 new 都应走这里。**
     *
     * 调用方零改动地保留原有行为：本地才附 `localParams`、本地不下发关思考字段、
     * 各档位超时与改造前逐项一致。
     */
    fun newService(cfg: AiConfig, profile: Profile, options: Options = Options()): OpenAiService {
        val local = isLocalBase(cfg.baseUrl)
        return when (profile) {
            Profile.CHAT -> OpenAiService(
                apiKey = cfg.apiKey,
                model = cfg.model,
                baseUrl = cfg.baseUrl,
                readTimeoutMs = if (local) CHAT_LOCAL_TIMEOUT_MS else CHAT_REMOTE_TIMEOUT_MS,
                extraBody = if (local) options.localParams else null,
                thinkingEnabled = !local && options.thinkingEnabled,
            )

            Profile.BACKGROUND -> OpenAiService(
                apiKey = cfg.apiKey,
                model = cfg.model,
                baseUrl = cfg.baseUrl,
                readTimeoutMs = BACKGROUND_TIMEOUT_MS,
            )

            Profile.SUBAGENT -> OpenAiService(
                apiKey = cfg.apiKey,
                model = cfg.model,
                baseUrl = cfg.baseUrl,
                // 与主对话同量级的超时：子代理是同步调用（主循环在等结论），
                // 而且它一轮里可能读一整篇网页 —— 8s 会把正常调研直接判死
                readTimeoutMs = if (local) CHAT_LOCAL_TIMEOUT_MS else CHAT_REMOTE_TIMEOUT_MS,
            )
        }
    }

    /** 列模型（GET /models）：需要在保存前先探服务商，用一个临时配置即可 */
    fun listModels(baseUrl: String, apiKey: String, model: String = ""): List<String> {
        val cfg = AiConfig(
            baseUrl = baseUrl.trim().ifBlank { "https://api.deepseek.com" },
            apiKey = apiKey.trim(),
            model = model.trim().ifBlank { "deepseek-chat" },
        )
        return newService(cfg, Profile.BACKGROUND).listModels()
    }

    // ═══════════════════ 图像能力：探测与决策 ═══════════════════

    /**
     * 实测一次图像输入能力并把结论落盘，返回更新后的能力。
     *
     * **阻塞方法，须在 IO 线程调用**（本地端点可能要几十秒——要么探一次真实推理，
     * 要么让 Ollama 加载模型）。只应由用户显式点「检测」或保存新模型时后台触发。
     *
     * 本地优先问自报（`/api/show` 免费且准确），自报答不了才去发真实请求：
     * 本地模型跑一次推理要真加载权重，代价比云端大得多。
     */
    fun probeImage(ctx: Context?, cfg: AiConfig): ModelCapabilities {
        // 探测的语义就是"我怀疑旧结论过时了" → 必须先清，否则 7 天内的旧值会盖住新结果
        CapabilityCache.clear(ctx, cfg.baseUrl, cfg.model)
        val local = isLocalBase(cfg.baseUrl)

        if (local) {
            val declared = LocalOllamaManager.refreshCapabilities(cfg.model)
            if (declared != null && declared.capabilities.isNotEmpty()) {
                Log.i(TAG, "probeImage: local model answered by /api/show, vision=${declared.supportsVision}")
                CapabilityCache.write(ctx, cfg.baseUrl, cfg.model, declared.supportsVision)
                return capabilities(cfg, ctx)
            }
        }

        val outcome = ModelCapabilityProbe.probeImage(
            cfg,
            timeoutMs = if (local) 120_000 else 20_000,
        )
        when (outcome) {
            is ModelCapabilityProbe.Outcome.Accepted ->
                CapabilityCache.write(ctx, cfg.baseUrl, cfg.model, true)

            is ModelCapabilityProbe.Outcome.Rejected ->
                CapabilityCache.write(ctx, cfg.baseUrl, cfg.model, false)

            is ModelCapabilityProbe.Outcome.Inconclusive ->
                // 探不出结论就**保持原判断**：网络问题不该被记成"这模型不吃图"
                Log.w(TAG, "probeImage inconclusive for ${cfg.model}: ${outcome.reason}")
        }
        return capabilities(cfg, ctx)
    }

    /** 失效某个模型的能力结论（换服务商/换模型/手工重探前调用） */
    fun invalidateCapability(ctx: Context?, baseUrl: String, model: String) {
        CapabilityCache.clear(ctx, baseUrl, model)
    }

    /**
     * 拍照问 AI 该走哪条识别路径 —— 把「用户猜」变成「按能力定」的唯一决策点。
     *
     * @param enabledByUser 用户在设置页里的图像理解开关
     */
    fun imageInputDecision(
        cfg: AiConfig,
        ctx: Context? = null,
        enabledByUser: Boolean,
    ): ImageInputDecision {
        if (!enabledByUser) return ImageInputDecision.SwitchOff
        val caps = capabilities(cfg, ctx)
        return if (caps.imageKnownUnsupported) ImageInputDecision.Unsupported(caps)
        else ImageInputDecision.Allow(caps)
    }
}

/**
 * 拍照问 AI 的识别路径决策。
 *
 * 为什么是三态而不是布尔：改造前消费侧只拿到 `Boolean`，于是
 * 「用户没开这个功能」和「开了但模型根本不支持」被压成同一件事（都走 OCR），
 * 用户永远不知道后者的存在 —— 他明明开了开关、还以为是功能坏了。
 * 拆出 [Unsupported] 之后，流程可以既走 OCR 又把原因**说出来**。
 */
internal sealed interface ImageInputDecision {
    /** 发图给模型（能力已确认支持，或未知但允许尝试 —— 失败会自动回退 OCR） */
    data class Allow(val capabilities: ModelCapabilities) : ImageInputDecision

    /** 用户开关未开：走 OCR，静默（这是用户自己的选择，不需要解释） */
    data object SwitchOff : ImageInputDecision

    /** 能力已确认不支持图像输入：走 OCR，并**必须告知原因**，否则用户以为功能坏了 */
    data class Unsupported(val capabilities: ModelCapabilities) : ImageInputDecision
}
