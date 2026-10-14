package com.rokidlab.phone.ai

import org.json.JSONArray
import org.json.JSONObject

/**
 * Agent 决策点伪工具（ask_user）—— 把「需要用户拍板的点」做成**可点选的选项卡**。
 *
 * ## 为什么需要它（2026-10-12，用户对照大厂 Agent 的「讨论阶段」提出）
 * 此前模型遇到方案取舍/需求歧义只有两条路，都不达标：
 *  1. 把问题写进回复正文 → 结束本轮 → 用户打字回复 → 靠续做机制接上
 *     —— 纯自然语言，没有结构化选项，用户还要重新描述上下文；
 *  2. 工具审批弹窗（[com.rokidlab.phone.ai.approval.ApprovalGate]）
 *     —— 二值（允许/取消）、限时、语义是"放行副作用"，不是"做选择"。
 *
 * 现在：模型调 ask_user → 手机弹选项卡（单选/多选/可自定义输入）→ **轮内阻塞**等用户点选
 * → 选择结果作为工具结果回填 → 循环继续，上下文零丢失。眼镜在线时同时 TTS 提醒看手机
 * （眼镜端没有承载 N 选一的输入手段：按键只有三手势、语音 ASR 会打断当前任务，见
 * [AskUserChannel] 的 KDoc）。
 *
 * 与 load_skill / update_plan 一样是伪工具：不在 `ToolRegistry.toolList`
 * （不进设置页开关、不进 ToolGateway 白名单），仅在主 Agent 会话装配，
 * 由 AiConversationService 的前置分支执行。
 */
object AskUser {
    const val TOOL_NAME = "ask_user"

    /** 单个候选项 */
    data class Option(val label: String, val hint: String)

    /**
     * 一个待回答的问题。
     *
     * @param options 候选项（可为空 = 纯自由输入的开放问题，此时 [allowFreeText] 必须为 true）
     * @param multiSelect true = 多选
     * @param allowFreeText true = 允许用户不选候选项、自己输入
     */
    data class Question(
        val text: String,
        val options: List<Option>,
        val multiSelect: Boolean,
        val allowFreeText: Boolean,
    )

    // ⚠️ schema 是无参纯常量，`by lazy` 缓存 —— 与 [AgentPlan.SCHEMA] 同一理由
    // （三处消费路径均只读，JSONObject 并发只读安全）。
    fun schema(): JSONObject = SCHEMA

    private val SCHEMA: JSONObject = JSONObject().apply {
        put("type", "function")
        put("function", JSONObject().apply {
            put("name", TOOL_NAME)
            put(
                "description",
                "需要用户做选择/拍板时调用：把问题与候选项做成选项卡弹给用户（手机上点选），" +
                    "阻塞等待用户选择后把结果返回给你。适用于：方案取舍、需求有歧义、执行方向不确定、" +
                    "update_plan 列出计划后请用户确认等。一次只问一个问题；候选项 2~6 个，" +
                    "每项一句话（可附一句说明）。纯信息性追问（「然后呢？」）或你自己能定的细节" +
                    "不要用本工具，直接在回复里问或直接做。",
            )
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put(
                        "question",
                        JSONObject().put("type", "string")
                            .put("description", "要问的问题，一句话，如「界面用哪种风格？」"),
                    )
                    // ⚠️ 数组/对象节点必须是 JSONSchema 对象形状（与 AgentPlan.steps 同一条事故判据：
                    // 写成裸 JSONArray 会让服务端 400、整请求所有工具一起失效）
                    put("options", JSONObject().apply {
                        put("type", "array")
                        put("description", "候选项清单，2~6 个；纯开放问题（只要用户写字）可不传")
                        put("items", JSONObject().apply {
                            put("type", "object")
                            put("properties", JSONObject().apply {
                                put(
                                    "label",
                                    JSONObject().put("type", "string")
                                        .put("description", "选项正文，一句话，如「深色极简风」"),
                                )
                                put(
                                    "hint",
                                    JSONObject().put("type", "string")
                                        .put("description", "可选：一句补充说明，如「开发快，适合先跑通」"),
                                )
                            })
                            put("required", JSONArray(listOf("label")))
                        })
                    })
                    put(
                        "multi_select",
                        JSONObject().put("type", "boolean")
                            .put("description", "是否允许多选，默认 false"),
                    )
                    put(
                        "allow_free_text",
                        JSONObject().put("type", "boolean")
                            .put("description", "是否允许用户自定义输入（不选候选项），默认 true"),
                    )
                })
                put("required", JSONArray(listOf("question")))
            })
        })
    }

    /** 候选项上限：超过直接截断（schema 说 2~6，防模型失控写 30 项把弹窗撑爆） */
    private const val MAX_OPTIONS = 8

    /**
     * 解析 ask_user 参数（纯函数，可单测）。
     * 非法 JSON / question 为空 / 无候选项又不允许自由输入 → null（调用方回用法说明）。
     */
    fun parse(arguments: String): Question? {
        val args = runCatching { JSONObject(arguments) }.getOrNull() ?: return null
        val text = args.optString("question").trim()
        if (text.isEmpty()) return null
        val opts = mutableListOf<Option>()
        args.optJSONArray("options")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val label = o.optString("label").trim()
                if (label.isEmpty()) continue
                opts.add(Option(label, o.optString("hint").trim()))
            }
        }
        if (opts.size > MAX_OPTIONS) {
            while (opts.size > MAX_OPTIONS) opts.removeAt(opts.size - 1)
        }
        val freeOk = args.optBoolean("allow_free_text", true)
        if (opts.isEmpty() && !freeOk) return null
        return Question(
            text = text,
            options = opts,
            multiSelect = args.optBoolean("multi_select", false),
            allowFreeText = freeOk,
        )
    }

    /** 给模型的用法说明（参数不合法时回填） */
    const val USAGE_NOTE: String =
        "ask_user 参数不合法：question 必填且非空；没有候选项时必须 allow_free_text=true。" +
            "请修正后重试，或直接在回复里用自然语言问。"

    /** 等待超时（用户暂时没回应）时回填给模型的说明 —— 宁可明说，不许模型臆测用户的选择 */
    const val TIMEOUT_NOTE: String =
        "（等待超时，用户暂时没有回应。）不要臆测用户的选择：可以稍后再问一次，" +
            "或先按你认为最稳妥的方式继续，但必须在回复里明确说明这是默认选择、用户可以改。"

    /** 用户显式跳过时回填给模型的说明 —— 同样不许臆测 */
    const val DECLINED_NOTE: String =
        "（用户跳过了这个问题。）不要臆测用户的选择：先按你认为最稳妥的方式继续，" +
            "但必须在回复里明确说明这是默认选择、用户可以改。"

    /** 弹不出选择界面（App 不在前台且无悬浮窗权限）时回填给模型的说明 */
    const val UNAVAILABLE_NOTE: String =
        "（此刻无法弹出选择界面：应用不在前台且没有悬浮窗权限。）" +
            "不要假装问过用户：把问题和候选项直接写进回复里，结束本轮等用户文字回复。"

    /**
     * 把用户的作答格式化回给模型（纯函数，可单测）。
     * 自由输入与勾选并存时都带上；两者皆空不应走到这里（界面侧保证）。
     */
    fun formatAnswer(selected: List<String>, freeText: String): String {
        val sel = selected.filter { it.isNotBlank() }
        val free = freeText.trim()
        return when {
            sel.isNotEmpty() && free.isNotEmpty() -> "用户的选择：${sel.joinToString("、")}；用户补充：$free"
            sel.isNotEmpty() -> "用户的选择：${sel.joinToString("、")}"
            else -> "用户的回答：$free"
        }
    }
}
