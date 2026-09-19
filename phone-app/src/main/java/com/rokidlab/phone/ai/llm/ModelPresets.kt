package com.rokidlab.phone.ai.llm

/**
 * 已知模型家族的能力表（`llm` 接缝的 Provider 侧之一：**内置知识**）。
 *
 * 定位：这是"没有更好证据时的最好猜测"。优先于它的两路证据依次是
 * [CapabilitySource.PROBED]（本机实测）与 [CapabilitySource.DECLARED]（服务端自报，本地 Ollama 有），
 * 它只在两者都拿不到时生效，且结论一律标注为 [CapabilitySource.PRESET] ——
 * **标注来源的意义就在于：用户/别名/代理随时可以推翻它，UI 必须留出「检测」入口。**
 *
 * 维护约定：
 *  - 只收录**真正常见**的模型；拿不准的宁可不写（回落成 ASSUMED 的"图像能力未知"，
 *    比写错一个 `false` 好 —— 写错 `false` 会让本来能用的视觉模型被开关置灰）；
 *  - 顺序即优先级，**视觉家族必须排在通用前缀之前**（`qwen2.5-vl` 要命中视觉规则，
 *    不能被 `qwen` 通用规则先吃掉 —— 改造前正是"按名字瞎猜"最容易踩的坑）；
 *  - 上下文窗口取该家族**常见档位**，宁可偏小也不夸大：这个数字后面要驱动会话压缩阈值，
 *    夸大等于压缩晚半拍，而上下文真的溢出时整轮请求会 400，用户直接拿不到回复。
 */
internal object ModelPresets {

    /** 一条内置规则：命中 [match] 即采用其余字段（未列出的能力沿用保守默认） */
    private data class Preset(
        val match: (String) -> Boolean,
        val family: String,
        val contextWindow: Int,
        /** null = 该家族内部有分歧，不写死结论（让它回落成"未知"，允许尝试） */
        val image: Boolean?,
        val tools: Boolean = true,
        val thinkingDisable: Boolean = false,
    )

    /**
     * 无法从任何渠道得知能力时的**保守默认**。
     *
     * 关键取舍在 [supportsImage] = null（未知）而不是 false：
     * 改造前的用户已经可以靠开关把图发给模型，遇到不认识的模型名就把这条能力剥夺掉，
     * 等于"我们不知道"惩罚成了"你不准用"。未知就允许尝试、失败回退 OCR ——
     * 这也是改造前的既有行为，不因引入能力表而变差。
     */
    val ASSUMED = ModelCapabilities(
        contextWindow = 0,
        supportsTools = true,
        supportsImage = null,
        supportsStreaming = true,
        supportsThinkingDisable = false,
        source = CapabilitySource.ASSUMED,
    )

    /** 本地 Ollama 完全拿不到自报能力时的兜底上下文窗口（Ollama 0.6+ 默认 num_ctx 4096） */
    private const val OLLAMA_DEFAULT_CTX = 4096

    // ═══════════════════ 视觉家族（必须排在通用前缀前）═══════════════════

    /**
     * 视觉家族的命名标记。
     *
     * 注意 `"vl"` 的两个写法都要收：`qwen2.5-vl` 是**中划线**，而 Ollama 官方模型名是
     * `qwen2.5vl`（**无中划线**，靠 `:` 分 tag）—— 只收 `-vl` 会漏掉本地最常见的那一个。
     *
     * 这里**宁可多收**：命中视觉标记的结论是"支持图像"，猜错的表现是"发图失败 → 自动回退 OCR"，
     * 与改造前完全一致、用户无感；而漏收的结论是"不支持图像"→ 开关被置灰、
     * 用户**平白丢掉一个本来能用的能力**。两类错误的代价不对称，所以取宽。
     * （反过来，对"确定不支持"的家族如 deepseek-chat 才敢写死 false —— 那是已知事实。）
     */
    private val VISION_MARKERS = listOf(
        "-vl", "vl:", "vl-", "vision", "llava", "bakllava", "moondream",
        "minicpm-v", "internvl", "cogvlm", "qwen-vl", "qwen2vl", "qwen3-vl",
        "step-1v", "pixtral", "gemma3", "smolvlm", "granite-vision",
    )

    private val PRESETS: List<Preset> = listOf(
        // ── DeepSeek：官方在线 API 的 chat/reasoner 都**不支持**图像输入，
        //    这条是本次改造最直接的收益：以前用户开「图像理解」+ deepseek-chat，
        //    必然白跑一轮（服务端 400 → 回退 OCR），现在开关直接置灰并说明原因。
        Preset(
            match = { m -> m.contains("deepseek") && (m.contains("reasoner") || m.contains("-r1") || m.contains("r1-")) },
            family = "deepseek-reasoner",
            contextWindow = 65536,
            image = false,
            // 推理专用模型不支持 function calling：硬发 tools 会被服务端拒绝
            tools = false,
            thinkingDisable = false,
        ),
        Preset(
            match = { it.contains("deepseek") },
            family = "deepseek",
            contextWindow = 131072,
            image = false,
            tools = true,
            thinkingDisable = true,
        ),

        // ── 视觉通用规则：名字里带视觉标记的一律按支持图像处理
        Preset(
            match = { m -> VISION_MARKERS.any { m.contains(it) } },
            family = "vision",
            contextWindow = 32768,
            image = true,
        ),

        // ── 阿里通义千问（qwen2.5-vl 已被上面的视觉规则截走）
        Preset(
            match = { m -> m.contains("qwen") || m.contains("qwq") },
            family = "qwen",
            contextWindow = 32768,
            image = false,
        ),

        // ── OpenAI
        Preset(
            match = { m ->
                listOf("gpt-4o", "gpt-4.1", "gpt-4-turbo", "chatgpt-4o", "o1", "o3", "o4", "gpt-5")
                    .any { m.contains(it) }
            },
            family = "openai",
            contextWindow = 128000,
            image = true,
        ),
        Preset(
            match = { m -> m.contains("gpt-3.5") || m.startsWith("gpt-4") },
            family = "openai-legacy",
            contextWindow = 8192,
            image = false,
        ),

        // ── Anthropic（Claude 3 起全线支持视觉）
        Preset(
            match = { it.contains("claude") },
            family = "anthropic",
            contextWindow = 200000,
            image = true,
        ),

        // ── Google Gemini
        Preset(
            match = { it.contains("gemini") },
            family = "gemini",
            contextWindow = 1048576,
            image = true,
        ),

        // ── 智谱 GLM：只有 -v 后缀才是视觉（上面视觉规则已处理），glm-4 本身是纯文本
        Preset(
            match = { m -> m.contains("glm") || m.contains("chatglm") },
            family = "glm",
            contextWindow = 131072,
            image = false,
        ),

        // ── 月之暗面：moonshot-v1 系列为纯文本；kimi 新模型（kimi-latest / k2）带视觉
        Preset(
            match = { it.contains("moonshot-v1") },
            family = "moonshot-v1",
            contextWindow = 131072,
            image = false,
        ),
        Preset(
            match = { it.contains("kimi") || it.contains("moonshot") },
            family = "kimi",
            contextWindow = 131072,
            image = true,
        ),

        // ── xAI Grok（grok-2 起带视觉）
        Preset(
            match = { it.contains("grok") },
            family = "grok",
            contextWindow = 131072,
            image = true,
        ),

        // ── 本地开源纯文本家族（llama3.2-vision / gemma3 等带视觉标记的已被视觉规则截走）
        Preset(
            match = { m ->
                listOf("llama", "mistral", "mixtral", "phi", "gemma", "qwen", "yi", "deepseek",
                    "glm", "internlm", "baichuan", "tinyllama", "smollm")
                    .any { m.contains(it) }
            },
            family = "open-weights",
            contextWindow = 8192,
            image = false,
        ),
    )

    /**
     * 按模型名查内置表；未收录返回 `null`（调用方据此回落 [ASSUMED]）。
     *
     * @param isLocal 本地（Ollama）端点：未被任何规则命中时不再回落成 ASSUMED 的
     *   `contextWindow = 0`，而是给 Ollama 的默认 num_ctx —— 本地模型这个数字我们有把握，
     *   给 0 反而会让压缩侧以为"完全未知"而过度保守。
     */
    fun lookup(model: String, isLocal: Boolean = false): ModelCapabilities? {
        val m = model.trim().lowercase()
        if (m.isEmpty()) return null
        val hit = PRESETS.firstOrNull { it.match(m) }
        if (hit != null) {
            return ModelCapabilities(
                contextWindow = hit.contextWindow,
                supportsTools = hit.tools,
                supportsImage = hit.image,
                supportsStreaming = true,
                supportsThinkingDisable = hit.thinkingDisable,
                source = CapabilitySource.PRESET,
            )
        }
        if (isLocal) {
            return ASSUMED.copy(
                contextWindow = OLLAMA_DEFAULT_CTX,
                source = CapabilitySource.PRESET,
            )
        }
        return null
    }

    /**
     * 该模型是否支持用 `thinking: {"type":"disabled"}` 关闭思考。
     *
     * 只能对 DeepSeek 系发送（该字段是 DeepSeek 专有，发给 OpenAI 兼容端点会 400），
     * 且必须排除推理专用模型 —— `deepseek-reasoner` / `deepseek-r1` 设计上不支持关闭思考，
     * 发了会被服务端拒绝（宁可少关也不要把请求打挂）。
     *
     * 历史坑：曾只认名字里带 `v4` / `v3.2` 的模型，`deepseek-flash` 不匹配 ——
     * 用户在聊天设置里关掉「长思考」后，请求其实没带关思考字段，服务端照样开思考，
     * 表现为「开关关了却仍被 reasoning 吃光预算」（2026-09-14 真机日志复现）。
     *
     * ⚠️ 这条规则**原来长在 `OpenAiService` 里**（传输层按模型名硬编码），2026-09-19 迁到这里，
     * 由 [ModelCapabilities.supportsThinkingDisable] 与传输层共用同一份判断。
     */
    fun supportsThinkingDisabled(model: String): Boolean {
        val m = model.lowercase()
        if (!m.contains("deepseek")) return false
        return !m.contains("reasoner") && !m.contains("-r1") && !m.contains("r1-")
    }

    // ═══════════════════ 服务商标识（仅用于展示/统计）═══════════════════

    /** 从 baseUrl 推断服务商名；识别不出统一叫 openai-compatible */
    fun providerOf(baseUrl: String, model: String): String {
        val b = baseUrl.lowercase()
        return when {
            b.contains("deepseek") -> "deepseek"
            b.contains("ollama") || b.contains("11434") || b.contains("127.0.0.1") || b.contains("localhost") ->
                "ollama"
            b.contains("moonshot") || b.contains("kimi") -> "moonshot"
            b.contains("dashscope") || b.contains("aliyun") -> "dashscope"
            b.contains("bigmodel") || b.contains("zhipu") -> "zhipu"
            b.contains("anthropic") -> "anthropic"
            b.contains("openai") -> "openai"
            b.contains("generativelanguage") -> "google"
            else -> {
                // 端点看不出服务商时退回按模型名猜（聚合站/自建代理很常见）
                val m = model.lowercase()
                when {
                    m.contains("deepseek") -> "deepseek"
                    m.contains("claude") -> "anthropic"
                    m.contains("qwen") -> "dashscope"
                    m.contains("glm") -> "zhipu"
                    m.contains("kimi") || m.contains("moonshot") -> "moonshot"
                    else -> "openai-compatible"
                }
            }
        }
    }
}
