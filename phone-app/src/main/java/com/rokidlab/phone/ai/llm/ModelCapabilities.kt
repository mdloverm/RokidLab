package com.rokidlab.phone.ai.llm

/**
 * 一条能力结论的**来源**，同时就是它的可信度等级（从上到下依次减弱）。
 *
 * 为什么要标注来源而不是只给布尔值：同一个问题「这个模型支不支持图像输入」有四种答案质量
 * 截然不同 —— 本机实测过、服务端自己声明的、我们内置表里抄的、纯粹没消息只能保守假设。
 * 前两种可以直接当事实用，后两种必须留出「用户可推翻」的口子（见 [LlmRegistry] 的探测入口）。
 * 只给一个 `Boolean` 会把「确定不支持」和「不知道，先按不支持处理」混成同一件事，
 * 而这两者对用户的措辞、以及要不要再去实测一次，处置完全不同。
 */
enum class CapabilitySource {
    /** 本机实测过（发过真实请求验证），最强证据 */
    PROBED,

    /** 服务端/运行时自报（本地 Ollama 的 `/api/tags`、`/api/show` 会带 `capabilities`） */
    DECLARED,

    /** 我们内置的模型家族表（[ModelPresets]）：按命名匹配，通常对但会被别名/代理打破 */
    PRESET,

    /** 一无所知 → 保守默认（支持工具与流式，图像能力**未知**） */
    ASSUMED,
}

/**
 * 一次对话请求**真正依赖**的模型能力（`llm` 接缝的 Service Definition 数据面）。
 *
 * 存在的理由（改造前的问题，2026-09-19）：
 * 「模型支持什么」这件事在代码里根本没有表达 —— 它散落在三处各自为政的猜测里：
 *  1. 图像理解开关：**完全靠用户自己判断**"我配的那个模型支不支持看图"，猜错了要等
 *     拍照失败一轮才知道（服务端拒绝 → 回退 OCR，用户白等十几秒）；
 *  2. `OpenAiService.supportsThinkingDisabled()`：按模型名里有没有 `deepseek` 判断能否下发
 *     关思考字段，是**模型名硬编码在传输层**里；
 *  3. 会话压缩阈值写死 6000 字符，跟模型真实的上下文窗口毫无关系。
 * 于是「换一个模型」实际上要在三个地方各改一点，加一个本地模型（Ollama）更是只能靠默认值瞎蒙。
 *
 * 现在这三件事都读同一个 [ModelCapabilities]：图像开关看 [supportsImage]、
 * 关思考字段看 [supportsThinkingDisable]、压缩预算看 [contextWindow]。
 *
 * @param contextWindow 上下文窗口（token）。**0 = 未知**（不是"无限"，压缩侧必须按保守值兜底）
 * @param supportsTools 是否支持 function calling。不支持时应停下发工具声明 ——
 *   部分服务端收到不认识的 `tools` 字段会直接 400，表现成「AI 突然不说话」。
 * @param supportsImage 是否接受 `image_url` 分片。**三态**：`true`/`false` 是结论，
 *   `null` 是「不知道」—— 不知道时允许尝试（失败自动回退 OCR），
 *   这是与改造前行为兼容的保守选择，不能因为不认识就剥夺用户已有能力。
 * @param supportsStreaming 是否支持 `stream=true` 的 SSE 增量返回
 * @param supportsThinkingDisable 是否接受 `thinking:{"type":"disabled"}` 关思考字段
 *   （DeepSeek 专有；发给别的端点会 400）
 * @param source 本结论的来源/可信度，UI 据此决定措辞与是否提供「检测」入口
 */
data class ModelCapabilities(
    val contextWindow: Int = 0,
    val supportsTools: Boolean = true,
    val supportsImage: Boolean? = null,
    val supportsStreaming: Boolean = true,
    val supportsThinkingDisable: Boolean = false,
    val source: CapabilitySource = CapabilitySource.ASSUMED,
) {
    /** 已确认**不支持**图像输入（与「未知」区分开：这一条才允许把开关置灰） */
    val imageKnownUnsupported: Boolean get() = supportsImage == false

    /** 是否已经拿到关于图像能力的确定结论（不论支持还是不支持） */
    val imageKnown: Boolean get() = supportsImage != null

    /** 上下文窗口的可读文本（UI 用）；未知返回 null 让调用方自己决定怎么措辞 */
    fun contextWindowText(): String? {
        if (contextWindow <= 0) return null
        return if (contextWindow >= 1024) "${contextWindow / 1024}K" else "$contextWindow"
    }

    /** 换一个图像结论但保留其余能力（探测结果回填用） */
    fun withImage(supported: Boolean, newSource: CapabilitySource): ModelCapabilities =
        copy(supportsImage = supported, source = newSource)

    /** 覆盖上下文窗口（本地模型能读到真实值 / 用户在参数里写了 `num_ctx` 时用） */
    fun withContextWindow(tokens: Int): ModelCapabilities =
        if (tokens <= 0) this else copy(contextWindow = tokens)
}

/**
 * 一条已解析好的模型路由（`llm` 接缝里「这次请求会打到哪个模型」的唯一答案）。
 *
 * 为什么需要它而不是继续传 `AiConfig`：`AiConfig` 是**用户的配置**（三个字段、可空、
 * 可能是本地槽位也可能是在线槽位），而执行侧真正关心的是**解析后的结果** ——
 * 到底打哪个端点、是不是本机、有哪些能力。改造前 `AiConversationService` 自己用
 * `baseUrl.contains("127.0.0.1") || contains("localhost") || contains("11434")` 猜是不是本地，
 * 这个判断在别处（`AiConfigService`、`LocalOllamaManager`）还有各自一份，口径随时可能漂移。
 */
data class ModelRoute(
    /** 稳定标识：`provider|model|baseUrl`，能力探测缓存的键就取自它 */
    val id: String,
    /** 服务商标识（deepseek / openai / anthropic / ollama / openai-compatible…），仅用于展示与统计 */
    val provider: String,
    val model: String,
    val baseUrl: String,
    /** 是否本机回环端点（本地 Ollama）：决定超时、是否附加本地调参、是否允许下发关思考字段 */
    val isLocal: Boolean,
    val capabilities: ModelCapabilities,
) {
    companion object {
        /**
         * 能力缓存键。
         *
         * 必须带 baseUrl：同一个模型名（如 `qwen2.5:7b`、`deepseek-chat`）在不同服务商/
         * 代理后面能力完全不同 —— 用户的 NewAPI 聚合站把 `deepseek-chat` 映射到别家视觉模型
         * 并不是天方夜谭，按模型名缓存会把这个结论错误地沿用到另一个端点。
         */
        fun keyOf(baseUrl: String, model: String): String =
            baseUrl.trim().trimEnd('/').lowercase() + "|" + model.trim().lowercase()
    }
}
