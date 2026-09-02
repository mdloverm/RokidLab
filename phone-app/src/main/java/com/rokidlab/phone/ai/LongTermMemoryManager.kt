package com.rokidlab.phone.ai

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * AI Agent 长期记忆管理器（跨会话持久）。
 *
 * 与 [AgentSessionManager]（短期会话内多轮记忆，超时自动清空）互补：
 * 短期记忆管「本轮对话上下文」，长期记忆管「跨会话记住用户的事实与偏好」。
 *
 * 设计（参考 ChatGPT-like memory 思路，按眼镜语音场景简化）：
 *   - 写入：注册 memory 工具给 AI，AI 在用户表达持久偏好/个人背景/习惯时自主调用
 *     `manage_memory`（action=create/delete/list/clear），不依赖用户手动整理。
 *   - 读取：每次对话开始时把已存记忆注入 system prompt（<memories> 段），
 *     使 AI 跨会话记得用户（如「用户喜欢周杰伦」），无需用户重复告知。
 *   - 存储：SharedPreferences JSON 数组（无 Room 依赖），上限条数 + 单条长度双重约束，
 *     防止无限膨胀；禁止写入的内容由 AI 工具说明约束。
 *
 * 开关独立于短期会话记忆，持久化在 SharedPreferences。
 */
object LongTermMemoryManager {
    private const val TAG = "LongTermMemory"
    private const val PREFS = "long_term_memory_prefs"
    private const val KEY_ENABLED = "long_term_memory_enabled"
    private const val KEY_ITEMS = "long_term_memory_items"

    /** 记忆工具名（OpenAI function calling） */
    const val TOOL_NAME = "manage_memory"

    /** 记忆条数上限 */
    private const val MAX_ITEMS = 30

    /** 单条记忆字符上限（超出截断） */
    private const val MAX_ITEM_CHARS = 300

    /**
     * 写锁：AI 工具循环会并发执行多个 toolCalls（如 delete+create 同时到达），
     * 若多个 manage_memory 并发做「读-改-写」，后 save 的会覆盖先 save 的导致记忆丢失。
     * 所有 mutator 必须串行且基于最新数据操作。
     */
    private val lock = Any()

    @Volatile
    private var cacheEnabled: Boolean? = null

    @Volatile
    private var cacheItems: List<String>? = null

    /** 长期记忆总开关 */
    fun isEnabled(context: Context): Boolean {
        val cached = cacheEnabled
        if (cached != null) return cached
        return prefs(context).getBoolean(KEY_ENABLED, true).also { cacheEnabled = it }
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        cacheEnabled = enabled
        Log.i(TAG, "set enabled=$enabled")
    }

    /** 读取全部记忆（内部缓存；mutator 持锁完成并刷新缓存，故缓存始终一致） */
    fun items(context: Context): List<String> {
        val cached = cacheItems
        if (cached != null) return cached
        return loadItems(context).also { cacheItems = it }
    }

    /** 记忆条数 */
    fun count(context: Context): Int = items(context).size

    /** 新增一条记忆：与已有完全相同则忽略（幂等），成功返回新条数 */
    fun add(context: Context, content: String): Int = synchronized(lock) {
        val text = content.trim().take(MAX_ITEM_CHARS)
        if (text.isEmpty()) return@synchronized loadItems(context).size
        // 锁内直接读最新数据，不依赖缓存，保证并发 delete/create 串行且不互相覆盖
        val list = loadItems(context).toMutableList()
        if (list.contains(text)) return@synchronized list.size
        list.add(text)
        // 超出上限丢弃最旧（先入先出）
        while (list.size > MAX_ITEMS) list.removeAt(0)
        save(context, list)
        Log.i(TAG, "add: total=${list.size}")
        list.size
    }

    /**
     * 删除记忆：入参可为记忆编号（1 起）或内容片段（包含匹配）。
     * @return 删除成功返回 true；未找到返回 false
     */
    fun remove(context: Context, query: String): Boolean = synchronized(lock) {
        val q = query.trim()
        if (q.isEmpty()) return@synchronized false
        val list = loadItems(context).toMutableList()
        val before = list.size
        val idx = q.toIntOrNull()
        val removed = if (idx != null && idx in 1..before) {
            list.removeAt(idx - 1)
            true
        } else {
            list.removeAll { it.contains(q) || q.contains(it) }
            list.size < before
        }
        if (removed) {
            save(context, list)
            Log.i(TAG, "remove: total=${list.size}")
        }
        removed
    }

    /** 清空全部记忆 */
    fun clear(context: Context) {
        synchronized(lock) {
            save(context, emptyList())
        }
        Log.i(TAG, "cleared")
    }

    /**
     * 注入用文本：把记忆拼成 system prompt 中可读的 <memories> 段。
     * 无记忆返回 null（调用方不注入，省 token）。
     */
    fun memoriesContext(context: Context): String? {
        val list = items(context)
        if (list.isEmpty()) return null
        return buildString {
            append("以下是与用户相关的长期记忆（跨会话积累，可据此个性化回答）：\n")
            list.forEachIndexed { i, s -> append("${i + 1}. $s\n") }
        }.trimEnd()
    }

    /**
     * 记忆工具的 JSON Schema（注册给 OpenAI 兼容协议的 tools 参数）。
     * description 里明确约束「写什么/不写什么」，防止 AI 误存敏感信息。
     */
    fun schema(): JSONObject {
        return JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", TOOL_NAME)
                put("description",
                    "管理长期记忆：跨会话记住/删除关于用户本人的事实与偏好。" +
                        "当用户表达持久性信息时应调用，例如「我喜欢周杰伦」「我是学生」「我每天 9 点上班」" +
                        "「叫我小乐」等（会一直记住，下次对话仍生效）；用户说「忘掉/删除…」时删除。" +
                        "严禁记忆密码、账号、地址、支付等敏感信息。")
                put("parameters", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("action", JSONObject().apply {
                            put("type", "string")
                            put("enum", JSONArray(listOf("create", "delete", "list", "clear")))
                            put("description", "操作类型：create=新增记忆；delete=删除（content 传编号如 1，或内容片段）；list=列出全部；clear=清空")
                        })
                        put("content", JSONObject().apply {
                            put("type", "string")
                            put("description", "create 时是要记住的内容；delete 时是要删除的编号或内容片段；list/clear 时省略")
                        })
                    })
                    put("required", JSONArray(listOf("action")))
                })
            })
        }
    }

    /** 执行记忆工具，返回给 AI 的结果文本 */
    fun execute(context: Context, arguments: String): String {
        val args = try {
            JSONObject(arguments)
        } catch (_: Exception) {
            return "工具参数解析失败，请传入合法 JSON"
        }
        return when (args.optString("action")) {
            "create" -> {
                val content = args.optString("content").trim()
                if (content.isEmpty()) return "create 需要提供 content 内容"
                val total = add(context, content)
                "已记住：$content（共 $total 条长期记忆）"
            }

            "delete" -> {
                val q = args.optString("content").trim()
                if (q.isEmpty()) return "delete 需要提供编号或内容片段"
                if (remove(context, q)) "已删除相关长期记忆" else "没有找到可删除的记忆条目"
            }

            "list" -> {
                val list = items(context)
                if (list.isEmpty()) "暂无长期记忆"
                else list.mapIndexed { i, s -> "[${i + 1}] $s" }.joinToString("\n")
            }

            "clear" -> {
                clear(context)
                "已清空全部长期记忆"
            }

            else -> "未知操作，可选：create / delete / list / clear"
        }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun loadItems(context: Context): List<String> {
        val raw = prefs(context).getString(KEY_ITEMS, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }
        } catch (e: Exception) {
            Log.w(TAG, "loadItems parse failed: ${e.message}")
            emptyList()
        }
    }

    private fun save(context: Context, list: List<String>) {
        // commit() 同步落盘（在后台工具线程调用，毫秒级），
        // 保证「说完就退出 App」时记忆已持久化，不会因 apply() 异步写盘被进程回收而丢失
        val ok = prefs(context).edit().putString(KEY_ITEMS, JSONArray(list).toString()).commit()
        if (!ok) Log.w(TAG, "save commit failed")
        cacheItems = list
    }
}
