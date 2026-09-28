package com.rokidlab.phone.ai.llm

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.rokidlab.phone.util.HttpClient
import com.rokidlab.phone.util.SecretStore
import org.json.JSONObject
import java.net.URI

/**
 * 品牌供应商目录 + 每家配置的本地存储（「模型供应商」功能的数据面）。
 *
 * 定位（参考 rikkahub 的多供应商设计，按 Lab 风格落地）：
 *  - [ProviderCatalog]：内置主流品牌预设（OpenAI 兼容端点 + 是否支持查余额），
 *    是「选择品牌 → 填密钥 → 选模型」这条路径的**静态半边**；
 *  - [ProviderStore]：每家供应商的动态半边（baseUrl / apiKey / model，密钥走 Keystore 加密），
 *    支持同时配置多家 —— 聊天输入栏的供应商面板据此展示「配置好的供应商及其模型」。
 *
 * ⚠️ 职责边界：**当前生效**的对话配置仍由 [com.rokidlab.phone.domain.AiConfigService] 的
 * 在线槽位单一管理（含下发眼镜端）。[ProviderStore] 只是「多家的钥匙串」——
 * 从面板/管理页选中某家某模型时，才把它写进在线槽位并激活。
 */

/** 一个内置品牌供应商预设 */
data class ProviderPreset(
    /** 稳定 id（存储键、匹配用） */
    val id: String,
    /** 展示名（中文语境直接用品牌名） */
    val name: String,
    /** OpenAI 兼容 baseUrl（chat 端点 = base + /chat/completions，与 OpenAiService 口径一致） */
    val baseUrl: String,
    /** 是否支持余额查询（有专属余额端点的才查，其余显示 --） */
    val supportsBalance: Boolean = false,
)

/** 已配置的一家供应商（动态值，id 对应 [ProviderPreset.id]） */
data class ProviderConfig(
    val id: String,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
)

internal object ProviderCatalog {
    private const val TAG = "ProviderCatalog"

    /**
     * 品牌预设表（顺序即展示顺序）。
     * baseUrl 一律给到可直接拼 /chat/completions 的完整前缀（与 OpenAiService 的口径一致）。
     *
     * ⚠️ 不再内置「推荐模型清单」（2026-09-28 用户指令移除）：预置清单必然过期
     * （火山方舟两个预置模型 Retiring 后用户一选就 404 即为例证），模型一律以
     * 填入 key 后实拉 /models 的结果为准（最新优先，见 OpenAiService.listModels）。
     */
    val PRESETS: List<ProviderPreset> = listOf(
        ProviderPreset(
            id = "deepseek",
            name = "DeepSeek 深度求索",
            baseUrl = "https://api.deepseek.com",
            supportsBalance = true,
        ),
        ProviderPreset(
            id = "moonshot",
            name = "月之暗面 Kimi",
            baseUrl = "https://api.moonshot.cn/v1",
        ),
        ProviderPreset(
            id = "dashscope",
            name = "阿里云百炼 · 通义千问",
            baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
        ),
        ProviderPreset(
            id = "zhipu",
            name = "智谱 GLM",
            baseUrl = "https://open.bigmodel.cn/api/paas/v4",
        ),
        ProviderPreset(
            id = "volces",
            name = "火山方舟 · 豆包",
            baseUrl = "https://ark.cn-beijing.volces.com/api/v3",
        ),
        ProviderPreset(
            id = "siliconflow",
            name = "硅基流动 SiliconFlow",
            baseUrl = "https://api.siliconflow.cn/v1",
            supportsBalance = true,
        ),
        ProviderPreset(
            id = "openai",
            name = "OpenAI",
            baseUrl = "https://api.openai.com/v1",
        ),
        ProviderPreset(
            id = "anthropic",
            name = "Anthropic Claude",
            baseUrl = "https://api.anthropic.com/v1",
        ),
        ProviderPreset(
            id = "gemini",
            name = "Google Gemini",
            baseUrl = "https://generativelanguage.googleapis.com/v1beta/openai",
        ),
        ProviderPreset(
            id = "xai",
            name = "xAI Grok",
            baseUrl = "https://api.x.ai/v1",
        ),
        ProviderPreset(
            id = "openrouter",
            name = "OpenRouter",
            baseUrl = "https://openrouter.ai/api/v1",
        ),
        // 兜底项：任何 OpenAI 兼容端点 / 聚合站 / 自建代理
        ProviderPreset(id = "custom", name = "自定义（OpenAI 兼容）", baseUrl = ""),
    )

    fun byId(id: String): ProviderPreset? = PRESETS.firstOrNull { it.id == id }

    /** 按 baseUrl 匹配品牌（host 级比对）；匹配不出（含自定义端点）返回 null */
    fun matchBaseUrl(baseUrl: String): ProviderPreset? {
        val host = hostOf(baseUrl) ?: return null
        return PRESETS.firstOrNull { p -> p.baseUrl.isNotBlank() && hostOf(p.baseUrl) == host }
    }

    private fun hostOf(url: String): String? = runCatching {
        URI(url.trim()).host?.lowercase()
    }.getOrNull()?.takeIf { it.isNotBlank() }

    /**
     * 查询账户余额（阻塞方法，须在 IO 线程调用）。
     *
     * 只对实现了专属余额端点的品牌生效（DeepSeek / SiliconFlow），其余返回 null ——
     * OpenAI 协议本身没有余额端点，猜不出结论就如实显示「--」，不要编数字。
     *
     * @return 形如 "¥110.00" 的展示串；不支持 / 查询失败 / 解析失败一律 null
     */
    fun queryBalance(baseUrl: String, apiKey: String): String? {
        if (apiKey.isBlank()) return null
        val preset = matchBaseUrl(baseUrl) ?: return null
        return try {
            when (preset.id) {
                // DeepSeek：GET /user/balance（base 可能带 /v1 后缀，先剥掉）
                "deepseek" -> {
                    val base = baseUrl.trimEnd('/').removeSuffix("/v1")
                    val json = JSONObject(
                        HttpClient.getString(
                            "$base/user/balance",
                            headers = mapOf("Authorization" to "Bearer $apiKey"),
                            readTimeout = 8000,
                        ),
                    )
                    val info = json.optJSONArray("balance_infos")?.optJSONObject(0) ?: return null
                    formatBalance(info.optString("currency", "CNY"), info.optString("total_balance"))
                }
                // SiliconFlow：GET /v1/user/info → data.balance（人民币）
                "siliconflow" -> {
                    val base = baseUrl.trimEnd('/')
                    val withV1 = if (base.endsWith("/v1")) base else "$base/v1"
                    val json = JSONObject(
                        HttpClient.getString(
                            "$withV1/user/info",
                            headers = mapOf("Authorization" to "Bearer $apiKey"),
                            readTimeout = 8000,
                        ),
                    )
                    val data = json.optJSONObject("data") ?: return null
                    formatBalance("CNY", data.optString("balance"))
                }
                else -> null
            }
        } catch (e: Exception) {
            Log.w(TAG, "queryBalance(${preset.id}) failed: ${e.message}")
            null
        }
    }

    private fun formatBalance(currency: String, amount: String): String? {
        val v = amount.toDoubleOrNull() ?: return null
        return when (currency.uppercase()) {
            "CNY" -> "¥%.2f".format(v)
            "USD" -> "$%.2f".format(v)
            else -> "%.2f %s".format(v, currency)
        }
    }
}

/**
 * 每家供应商配置的本地存储（SharedPreferences + Keystore 加密密钥）。
 *
 * 为什么不塞进 AI_PREFS 的在线槽位：在线槽位只有一份「当前生效」；
 * 这里要的是「钥匙串」—— 多家都留着密钥与模型选择，切换时零输入。
 */
internal object ProviderStore {
    private const val PREFS = "provider_store"
    private const val TAG = "ProviderStore"

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun keyBase(id: String) = "provider_${id}_base"
    private fun keyKey(id: String) = "provider_${id}_key"
    private fun keyModel(id: String) = "provider_${id}_model"

    /** 读取一家已保存的配置；未保存返回 null */
    fun get(ctx: Context, id: String): ProviderConfig? {
        val p = prefs(ctx)
        val base = p.getString(keyBase(id), null).orEmpty()
        val key = SecretStore.get(p, keyKey(id)).orEmpty()
        val model = p.getString(keyModel(id), null).orEmpty()
        if (base.isBlank() && key.isBlank() && model.isBlank()) return null
        return ProviderConfig(id = id, baseUrl = base, apiKey = key, model = model)
    }

    /** 保存一家配置（密钥走 Keystore 加密落盘，prefs 里只有密文） */
    fun save(ctx: Context, cfg: ProviderConfig) {
        runCatching {
            val p = prefs(ctx)
            p.edit()
                .putString(keyBase(cfg.id), cfg.baseUrl)
                .putString(keyModel(cfg.id), cfg.model)
                .apply()
            SecretStore.put(p, keyKey(cfg.id), cfg.apiKey)
        }.onFailure { Log.e(TAG, "save(${cfg.id}) failed: ${it.message}") }
    }

    /** 删除一家配置 */
    fun remove(ctx: Context, id: String) {
        runCatching {
            val p = prefs(ctx)
            p.edit()
                .remove(keyBase(id))
                .remove(keyModel(id))
                .apply()
            SecretStore.put(p, keyKey(id), null)
        }
    }

    /**
     * 全部已保存的配置（按 [ProviderCatalog.PRESETS] 顺序）。
     * 判定口径：密钥非空才算「配置好」—— 只存了个 baseUrl 的半成品不算。
     */
    fun configured(ctx: Context): List<ProviderConfig> =
        ProviderCatalog.PRESETS.mapNotNull { preset ->
            get(ctx, preset.id)?.takeIf { it.apiKey.isNotBlank() }
        }
}
