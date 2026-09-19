package com.rokidlab.phone.ai.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `llm` 接缝的能力解析回归测试（[ModelPresets] / [ModelCapabilities] / [ModelRoute]）。
 *
 * 为什么这些纯函数值得专门锁：它们的结论**直接决定用户能不能用一个功能** ——
 * `supportsImage = false` 会把图像理解开关置灰，`supportsTools = false` 意味着不发工具声明。
 * 而它们全是"按模型名匹配"的启发式，最容易在加一条规则时把另一条挤掉
 * （规则顺序变了、新加的前缀把视觉家族吃掉了），而这类错误**编译通过、界面正常、单测照绿**，
 * 只有用户发现"这个模型怎么突然不能看图了"才会暴露。
 *
 * 本测试锁两类东西：
 *  1. **已知事实**（DeepSeek 官方 chat/reasoner 都不吃图；视觉家族要认出来）；
 *  2. **失败方向的不对称性** —— 未知必须是"未知"而不是"不支持"，
 *     因为把未知当不支持会**平白剥夺**用户已有的能力（详见 [ModelPresets.VISION_MARKERS] 注释）。
 */
class ModelPresetsTest {

    // ═══════════ A. DeepSeek：已知事实 + 关思考字段规则 ═══════════

    @Test
    fun `A1 deepseek-chat 确认不支持图像但支持工具与关思考字段`() {
        val capsOpt = ModelPresets.lookup("deepseek-chat")
        assertNotNull("deepseek 必须在内置表里（在线默认模型，结论影响最大）", capsOpt)
        val caps = capsOpt!!
        // 官方 API 的 chat 系列没有视觉能力：以前用户开「图像理解」+ deepseek-chat 必然白跑一轮
        assertEquals(false, caps.supportsImage)
        assertTrue(caps.imageKnownUnsupported)
        assertTrue(caps.supportsTools)
        assertTrue(caps.supportsThinkingDisable)
        assertEquals(131072, caps.contextWindow)
        assertEquals(CapabilitySource.PRESET, caps.source)
    }

    @Test
    fun `A2 推理专用模型既不吃图也不支持工具调用`() {
        listOf("deepseek-reasoner", "deepseek-r1", "deepseek-r1-distill-qwen").forEach { name ->
            val capsOpt = ModelPresets.lookup(name)
            assertNotNull("$name 应命中推理专用规则", capsOpt)
            val caps = capsOpt!!
            // 给推理模型发 tools 会被服务端直接拒绝：这条规则省掉的是整轮 400
            assertFalse("$name 不应声明支持工具调用", caps.supportsTools)
            assertEquals(false, caps.supportsImage)
            assertFalse("$name 不支持关思考（发了会被拒）", caps.supportsThinkingDisable)
        }
    }

    @Test
    fun `A3 关思考字段规则与迁移前的实现逐字一致`() {
        // 历史坑（2026-09-14 真机）：曾只认名字里带 v4 / v3.2 的模型，deepseek-flash 不匹配，
        // 用户关了「长思考」但请求没带关思考字段 → reasoning 吃光输出预算 → 连续空轮
        assertTrue("deepseek-flash 必须能关思考", ModelPresets.supportsThinkingDisabled("deepseek-flash"))
        assertTrue(ModelPresets.supportsThinkingDisabled("DeepSeek-V4"))
        // 推理专用模型设计上不支持关闭 → 宁可少关也不能把请求打挂
        assertFalse(ModelPresets.supportsThinkingDisabled("deepseek-reasoner"))
        assertFalse(ModelPresets.supportsThinkingDisabled("deepseek-r1"))
        assertFalse(ModelPresets.supportsThinkingDisabled("r1-0528"))
        // 非 DeepSeek 端点收到该字段会 400 → 一律不发
        assertFalse(ModelPresets.supportsThinkingDisabled("gpt-4o"))
        assertFalse(ModelPresets.supportsThinkingDisabled("qwen2.5:7b"))
    }

    // ═══════════ B. 视觉家族识别 ═══════════

    @Test
    fun `B1 视觉家族按命名识别，含 Ollama 无中划线写法`() {
        // `qwen2.5-vl` 是中划线写法；Ollama 官方模型名是 `qwen2.5vl:7b`（无中划线，靠 : 分 tag）。
        // 只收 `-vl` 会漏掉本地最常见的那一个 —— 而漏收的后果是开关被置灰，用户平白丢能力。
        listOf(
            "qwen2.5-vl", "qwen2.5vl:7b", "qwen2-vl", "llama3.2-vision", "gemma3:4b",
            "llava:13b", "minicpm-v", "gpt-4-vision-preview", "pixtral-12b",
        ).forEach { name ->
            assertEquals("$name 应被识别为支持图像", true, ModelPresets.lookup(name)?.supportsImage)
        }
    }

    @Test
    fun `B2 同名家族的非视觉版本不能误判为支持图像`() {
        listOf("qwen2.5:7b", "llama3.1:8b", "gemma2:9b", "mistral:7b", "glm-4").forEach { name ->
            assertEquals("$name 是纯文本模型", false, ModelPresets.lookup(name)?.supportsImage)
        }
    }

    @Test
    fun `B3 云端视觉模型与上下文窗口抽样`() {
        ModelPresets.lookup("gpt-4o")!!.let {
            assertEquals(true, it.supportsImage)
            assertEquals(128000, it.contextWindow)
        }
        ModelPresets.lookup("claude-3-5-sonnet")!!.let {
            assertEquals(true, it.supportsImage)
            assertEquals(200000, it.contextWindow)
        }
        // moonshot-v1 系列是纯文本，kimi 新模型才带视觉 —— 两者不能混为一谈
        assertEquals(false, ModelPresets.lookup("moonshot-v1-128k")!!.supportsImage)
        assertEquals(true, ModelPresets.lookup("kimi-latest")!!.supportsImage)
    }

    // ═══════════ C. 失败方向：未知 ≠ 不支持 ═══════════

    @Test
    fun `C1 未收录的模型必须是图像能力未知，而不是不支持`() {
        assertNull("未收录模型不应硬编一个结论", ModelPresets.lookup("acme-llm-v7"))
        // 这是本接缝最关键的一条不变式：未知时允许尝试（失败回退 OCR），
        // 若在这里返回 supportsImage=false，未收录模型的图像理解开关会被无故置灰
        assertNull("保守默认的图像能力必须是未知", ModelPresets.ASSUMED.supportsImage)
        assertFalse(ModelPresets.ASSUMED.imageKnownUnsupported)
        assertFalse(ModelPresets.ASSUMED.imageKnown)
        // 其余能力保守默认"支持"：不支持时问题会立刻以报错暴露，而少发工具声明是静默的能力缺失
        assertTrue(ModelPresets.ASSUMED.supportsTools)
        assertTrue(ModelPresets.ASSUMED.supportsStreaming)
    }

    @Test
    fun `C2 本地端点未收录时给出 Ollama 默认窗口而不是未知`() {
        val capsOpt = ModelPresets.lookup("acme-llm-v7", isLocal = true)
        assertNotNull("本地端点必须有兜底（Ollama 的默认 num_ctx 我们有把握）", capsOpt)
        val caps = capsOpt!!
        assertEquals(4096, caps.contextWindow)
        assertEquals(CapabilitySource.PRESET, caps.source)
        // 图像能力仍然保持未知：本地兜底不该顺手给出一个否定结论
        assertNull(caps.supportsImage)
    }

    // ═══════════ D. 路由与能力数据的辅助行为 ═══════════

    @Test
    fun `D1 本地端点判断收口一致`() {
        assertTrue(LlmRegistry.isLocalBase("http://127.0.0.1:11434/v1"))
        assertTrue(LlmRegistry.isLocalBase("http://localhost:11434"))
        assertTrue(LlmRegistry.isLocalBase("http://LocalHost:1234/v1"))
        assertFalse(LlmRegistry.isLocalBase("https://api.deepseek.com"))
        assertFalse(LlmRegistry.isLocalBase(""))
    }

    @Test
    fun `D2 能力缓存键必须带端点且大小写归一`() {
        assertEquals(
            "https://api.deepseek.com|deepseek-chat",
            ModelRoute.keyOf("https://API.DeepSeek.com/", " DeepSeek-Chat "),
        )
        // 同模型名不同端点必须是两个键：聚合站把 deepseek-chat 接到别家视觉模型并非天方夜谭
        assertTrue(
            ModelRoute.keyOf("https://a.example.com", "deepseek-chat") !=
                ModelRoute.keyOf("https://b.example.com", "deepseek-chat"),
        )
    }

    @Test
    fun `D3 上下文窗口可读文本与探测回填`() {
        assertEquals("128K", ModelCapabilities(contextWindow = 131072).contextWindowText())
        assertEquals("4K", ModelCapabilities(contextWindow = 4096).contextWindowText())
        // 未知（0）必须返回 null 让 UI 自己决定怎么措辞，而不是显示"0K"
        assertNull(ModelCapabilities(contextWindow = 0).contextWindowText())
        assertNull(ModelCapabilities().contextWindowText())

        // 探测只改图像结论与来源，其余能力（窗口/工具/流式）必须原样保留 ——
        // 探测只验证了"图像"这一件事，没有理由顺手改写别的字段
        val probed = ModelPresets.lookup("acme-llm-v7") ?: ModelPresets.ASSUMED
        val updated = probed.withImage(true, CapabilitySource.PROBED)
        assertEquals(true, updated.supportsImage)
        assertEquals(CapabilitySource.PROBED, updated.source)
        assertEquals(probed.supportsTools, updated.supportsTools)
        assertEquals(probed.contextWindow, updated.contextWindow)
    }

    @Test
    fun `D4 服务商标识推断`() {
        assertEquals("deepseek", ModelPresets.providerOf("https://api.deepseek.com", "deepseek-chat"))
        assertEquals("ollama", ModelPresets.providerOf("http://127.0.0.1:11434/v1", "qwen2.5:7b"))
        // 端点看不出服务商时按模型名兜底（聚合站/自建代理很常见）
        assertEquals("anthropic", ModelPresets.providerOf("https://my-gateway.internal/v1", "claude-3-5-sonnet"))
        assertEquals("openai-compatible", ModelPresets.providerOf("https://my-gateway.internal/v1", "acme-llm-v7"))
    }
}
