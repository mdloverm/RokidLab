package com.rokidlab.phone.ai

import android.content.Context
import android.util.Log
import com.rokidlab.phone.glasses.CxrLHiRokidSession.Companion.KEY_AI_API_KEY
import com.rokidlab.phone.glasses.CxrLHiRokidSession.Companion.KEY_AI_BASE_URL
import com.rokidlab.phone.glasses.CxrLHiRokidSession.Companion.KEY_AI_MODEL
import com.rokidlab.phone.glasses.CxrLHiRokidSession.Companion.KEY_AI_USE_LOCAL
import com.rokidlab.phone.glasses.CxrLHiRokidSession.Companion.AI_PREFS
import com.rokidlab.phone.ai.llm.LlmRegistry
import com.rokidlab.phone.ai.session.AgentSessionStore
import com.rokidlab.phone.domain.AiConfig
import com.rokidlab.phone.util.SecretStore
import org.json.JSONArray
import org.json.JSONObject

/**
 * 滚动摘要的 **LLM 精炼器**（`AgentSessionStore.digestRefiner` 的 Android 侧实现）。
 *
 * 为什么读配置不走 `AiConfigService`：那个服务挂在 [com.rokidlab.phone.glasses.CxrLHiRokidSession]
 * 会话实例上，而摘要精炼在**任何会话记录路径**上都会发生（含后台自主任务）。
 * 直接读同一份 prefs（`AI_PREFS`，键常量单一数据源在 `CxrLHiRokidSession.Companion`），
 * 与 `getAiConfig()` 读的是同一批键 —— 不会漂移。
 *
 * 失败语义（与 [AgentSessionStore.DigestRefiner] 契约一致）：**任何**失败都返回 null，
 * 上层保留本地提取的摘要。精炼是锦上添花，绝不能变成新的故障点。
 */
internal object LlmDigestRefiner {
    private const val TAG = "LlmDigestRefiner"

    /** 构造绑定 [context] 的精炼器（每会话存一份 lambda，配置每次调用时现读 —— 换模型即时生效） */
    fun refinerFor(context: Context): AgentSessionStore.DigestRefiner =
        AgentSessionStore.DigestRefiner { turns, budget -> refine(context.applicationContext, turns, budget) }

    private fun refine(context: Context, turns: List<Pair<String, String>>, budget: Int): String? {
        val prefs = context.getSharedPreferences(AI_PREFS, 0)
        // 本地模型不精炼：小窗口 + 数秒级首字延迟，摘要这点收益盖不住开销（本地摘要兜底）
        if (prefs.getBoolean(KEY_AI_USE_LOCAL, false)) return null
        val cfg = AiConfig(
            baseUrl = prefs.getString(KEY_AI_BASE_URL, "").orEmpty().ifBlank { "https://api.deepseek.com" },
            apiKey = SecretStore.get(prefs, KEY_AI_API_KEY).orEmpty(),
            model = prefs.getString(KEY_AI_MODEL, "").orEmpty().ifBlank { "deepseek-chat" },
        )
        val service = LlmRegistry.newService(cfg, LlmRegistry.Profile.DIGEST)
        val prompt = buildString {
            append("以下是更早的一段对话记录。请把它压缩成一份滚动摘要，供助手在后续对话中恢复记忆。\n")
            append("要求：\n")
            append("1. 只输出要点行：每行以「· 」开头，一行一个要点；不要任何开场白、解释或代码围栏。\n")
            append("2. 保留**结论与承诺**——问出的答案、做出的决定、约定或未完成的事；不要只写\"聊过某某话题\"。\n")
            append("3. 要点按对话时间排序，总长不超过 ").append(budget).append(" 字。\n\n对话记录：\n")
            turns.forEach { (role, text) ->
                append(if (role == "user") "用户：" else "AI：").append(text).append('\n')
            }
        }
        val messages = JSONArray().put(
            JSONObject().put("role", "user").put("content", prompt),
        )
        val out = service.chatTurn(messages).content?.trim().orEmpty()
        if (out.isBlank()) return null
        // 剥掉模型偶尔加的围栏与自由文本，只收要点行；实在一行都不合规就收原文（比丢弃强）
        val lines = out.lineSequence()
            .map { it.trim().removePrefix("```").trim() }
            .filter { it.startsWith("·") || it.startsWith("-") || it.startsWith("•") }
            .map { it.removePrefix("·").removePrefix("-").removePrefix("•").trim() }
            .filter { it.isNotBlank() }
            .toList()
        val refined = if (lines.isEmpty()) out else lines.map { "· $it" }.joinToString("\n")
        Log.i(TAG, "digest refined: ${refined.length} chars (budget=$budget, turns=${turns.size})")
        return refined
    }
}
