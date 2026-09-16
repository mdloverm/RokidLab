package com.rokidlab.phone.ai

import org.json.JSONArray
import org.json.JSONObject

/**
 * Agent 任务计划伪工具（update_plan）—— 显式规划/任务分解。
 *
 * 让模型在开始多步任务前先列出步骤清单、执行中随进展更新状态：
 *  - 计划文本实时推送到眼镜端显示（用户能看到「做到哪一步了」，替代黑盒等待）；
 *  - 模型自身借助计划在多轮工具循环中保持结构化推进 —— 只读/动作轮预算触顶前，
 *    可依据计划把剩余额度花在未竟步骤上，而不是被勘察消耗殆尽。
 *
 * 与 load_skill 一样是伪工具：不在 [ToolRegistry.toolList]（不进设置页开关、
 * 不进 ToolGateway 页面白名单），仅在主 Agent 会话装配，由 AiConversationService 执行。
 */
object AgentPlan {
    const val TOOL_NAME = "update_plan"

    /** 一个计划步骤（title + 状态），与 [AgentTaskStore] 的落盘结构共用 */
    data class PlanStep(val title: String, val status: String)

    fun schema(): JSONObject = JSONObject().apply {
        put("type", "function")
        put("function", JSONObject().apply {
            put("name", TOOL_NAME)
            put(
                "description",
                "制定或更新当前任务计划。对于需要多个步骤的任务（多文件代码生成、先查资料再操作设备、" +
                    "多工具配合等），先调用本工具列出 2~6 个步骤，并在关键进展时更新各步骤状态，" +
                    "让用户随时了解进度。简单的一次性问答（闲聊、单工具可完成）不需要本工具。",
            )
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    // ⚠️ 这里必须是 JSONSchema 对象（{"type":"array","items":{…}}），
                    // **不能**直接 put 一个 JSONArray —— 那会让服务端看到
                    // `"steps":[{…}]`，即「schema 节点是一个数组」，报
                    // `Invalid schema for function 'update_plan': [{…}] is not of types "boolean", "object"`，
                    // 整个请求 400 被拒、**所有工具都用不了**（2026-09-15 真机事故：
                    // 连续空轮、AI 完全失能，日志只有 toolCalls=0 content=null finish=none）。
                    put("steps", JSONObject().apply {
                        put("type", "array")
                        put("description", "步骤清单，2~6 条，按执行顺序排列")
                        put("items", JSONObject().apply {
                            put("type", "object")
                            put("properties", JSONObject().apply {
                                put(
                                    "title",
                                    JSONObject().put("type", "string")
                                        .put("description", "步骤内容，一句话，如「查询眼镜电量」"),
                                )
                                put(
                                    "status",
                                    JSONObject().put("type", "string")
                                        .put("enum", JSONArray(listOf("pending", "in_progress", "done")))
                                        .put("description", "pending=未开始，in_progress=进行中，done=已完成"),
                                )
                            })
                            put("required", JSONArray(listOf("title", "status")))
                        })
                    })
                })
                // ⚠️ required 必须是 properties 的**兄弟**（挂在 parameters 下）。
                // 误写在 properties 里会变成一个名字叫 "required" 的**参数**、且其 schema 是数组，
                // 服务端同样报 `[{…}] is not of types "boolean","object"` 把整个请求 400 ——
                // 这是 2026-09-15 事故里 update_plan 的第二处缺陷，与 steps 那处一并修掉。
                put("required", JSONArray(listOf("steps")))
            })
        })
    }

    /**
     * 解析 steps 参数为步骤列表（纯函数，可单测）。
     * 非法 JSON / 缺字段 / title 为空 → 返回 null，调用方按「无计划」处理。
     */
    fun parseSteps(arguments: String): List<PlanStep>? {
        val args = runCatching { JSONObject(arguments) }.getOrNull() ?: return null
        val steps = args.optJSONArray("steps") ?: return null
        if (steps.length() == 0) return null
        val out = mutableListOf<PlanStep>()
        for (i in 0 until steps.length()) {
            val s = steps.optJSONObject(i) ?: continue
            val title = s.optString("title").trim()
            if (title.isEmpty()) continue
            val status = when (s.optString("status")) {
                "done" -> "done"
                "in_progress" -> "in_progress"
                else -> "pending"
            }
            out.add(PlanStep(title, status))
        }
        return out.ifEmpty { null }
    }

    /**
     * 执行：解析步骤清单 → 格式化为进度文本。
     * 返回值同时作为工具结果回填模型、与眼镜端进度显示文本（调用方推送）。
     */
    fun execute(arguments: String): String {
        val steps = parseSteps(arguments)
            ?: return "计划参数不合法或为空，请传 {\"steps\":[{\"title\":\"…\",\"status\":\"pending|in_progress|done\"}]}"
        val out = StringBuilder("任务计划：")
        steps.forEachIndexed { i, s ->
            val mark = when (s.status) {
                "done" -> "✓"
                "in_progress" -> "→"
                else -> "·"
            }
            out.append("\n").append(mark).append(" ").append(i + 1).append(". ").append(s.title)
        }
        return out.toString()
    }
}
