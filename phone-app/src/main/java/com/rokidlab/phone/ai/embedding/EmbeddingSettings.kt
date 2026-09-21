package com.rokidlab.phone.ai.embedding

import android.content.Context
import com.rokidlab.phone.util.SecretStore

/**
 * 语义检索（向量索引）的持久化配置。
 *
 * ## 为什么端点信息要在这里存一份快照
 * 聊天模型配置（baseUrl/apiKey/model）属于会话层，知识库是一个不持有会话句柄的静态 object，
 * 检索发生在对话主循环深处。直接读会话配置既会让 KnowledgeBase 反向依赖 glasses 层，
 * 也无法表达「embeddings 用另一个模型/端点」的合理需求（如聊天用 DeepSeek、
 * 向量用硅基流动 bge-m3）。用户开启语义检索时把当时的端点快照存到这里即可。
 *
 * ## 安全
 * apiKey 走与聊天 key 相同的 [SecretStore]（Keystore 加密），prefs 里只有密文。
 *
 * @param enabled 总开关；关闭时检索纯 BM25、零网络请求（行为与升级前完全一致）
 * @param model   已探测成功的嵌入模型名；空串 = 尚未探测/不可用
 * @param baseUrl OpenAI 兼容端点（快照自聊天配置或用户在开启时的实际配置）
 */
data class EmbeddingSnapshot(
    val enabled: Boolean = false,
    val model: String = "",
    val baseUrl: String = "",
) {
    /** 开关开、模型与端点就绪才允许走向量通道（apiKey 允许为空，如本地 Ollama） */
    val ready: Boolean get() = enabled && model.isNotBlank() && baseUrl.isNotBlank()
}

object EmbeddingSettings {
    private const val PREFS = "kb_embedding_prefs"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_MODEL = "model"
    private const val KEY_BASE_URL = "base_url"
    private const val KEY_API_KEY = "api_key_enc"

    fun load(context: Context): EmbeddingSnapshot {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return EmbeddingSnapshot(
            enabled = prefs.getBoolean(KEY_ENABLED, false),
            model = prefs.getString(KEY_MODEL, "").orEmpty(),
            baseUrl = prefs.getString(KEY_BASE_URL, "").orEmpty(),
        )
    }

    /** 读出解密后的 apiKey（未配置返回空串） */
    fun apiKey(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return SecretStore.get(prefs, KEY_API_KEY).orEmpty()
    }

    /** 保存配置（apiKey 传 null 表示保持原值不变，传空串表示清除） */
    fun save(
        context: Context,
        enabled: Boolean,
        model: String,
        baseUrl: String,
        apiKey: String? = null,
    ) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean(KEY_ENABLED, enabled)
            .putString(KEY_MODEL, model.trim())
            .putString(KEY_BASE_URL, baseUrl.trim())
            .apply()
        if (apiKey != null) SecretStore.put(prefs, KEY_API_KEY, apiKey)
    }
}
