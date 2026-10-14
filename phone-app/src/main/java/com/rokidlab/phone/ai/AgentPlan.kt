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
    data class PlanStep(
        val title: String,
        val status: String,
        /**
         * 依赖的步骤下标（0-based，指向同一次 `steps` 数组里的其它元素）。
         *
         * ## 为什么要加依赖（2026-10-06）
         * 原来 `PlanStep(title, status)` 是**平铺数组**，只能表达"顺序"不能表达"前提"。
         * 于是模型可以把「装依赖」标成 done 而「写代码」仍是 pending，系统也不拦 ——
         * 续做时它只看"还有没有 pending"，于是跳过前置条件直接往下做，产出依赖缺失的产物。
         *
         * ⚠️ 加依赖**不改变**"顺序就是依赖"的默认行为：`dependsOn` 为空时仍按原语义处理。
         * 它只是让模型在"顺序不是真实前提、但仍有先后"这种场合能**显式声明**，
         * 从而在标记 done 时被校验拦一下（见 [violationsOf]）。
         */
        val dependsOn: List<Int> = emptyList(),
    )

    // ⚠️ `by lazy` 缓存（2026-10-05）：无参纯常量声明，缓存理由与共享前提见
    // [SkillRegistry.schema] 的 KDoc（三处消费路径均只读，JSONObject 并发只读安全）。
    fun schema(): JSONObject = SCHEMA

    private val SCHEMA: JSONObject = JSONObject().apply {
        put("type", "function")
        put("function", JSONObject().apply {
            put("name", TOOL_NAME)
            put(
                "description",
                "制定或更新当前任务计划。对于需要多个步骤的任务（多文件代码生成、先查资料再操作设备、" +
                    "多工具配合等），先调用本工具列出 2~6 个步骤，并在关键进展时更新各步骤状态，" +
                    "让用户随时了解进度。⚠️ 状态必须与实际进度一致：每完成一步就更新一次；某一步经核实" +
                    "其实不需要做、或已经满足，也要把它标成 done —— 留在 in_progress 的步骤会一直以" +
                    "「还没做的步骤」出现在每轮收尾汇报里，计划面板也会永远停在它上面。" +
                    "首次建立计划后，如执行方向存在多种可能，可先用 ask_user " +
                    "请用户确认计划再开始。简单的一次性问答（闲聊、单工具可完成）不需要本工具。",
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
                                put(
                                    "depends_on",
                                    JSONObject().put("type", "array")
                                        .put("description", "可选：本步骤依赖的其它步骤下标（0 起，" +
                                            "如 [0,1] 表示必须等第 1、2 步完成）。" +
                                            "绝大多数情况不需要填——数组顺序本身就代表先后。")
                                        .put("items", JSONObject().put("type", "integer")),
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
            out.add(PlanStep(title, status, parseDeps(s.optJSONArray("depends_on"), out.size)))
        }
        return out.ifEmpty { null }
    }

    /**
     * 解析 `depends_on`（纯函数）。
     *
     * ⚠️ **只保留「指向更早步骤」的下标**，其余一律丢弃而不是报错：
     * - 自指（自己依赖自己）与前向引用（依赖还没排到的步骤）都是模型的笔误，
     *   报错的收益远小于直接忽略 —— 顺序本来就是依赖，丢掉这个字段等价于回到旧行为；
     * - 越界下标同理。
     * 这条判据是「宁可漏放、不可误拦」：拦住模型对 `update_plan` 的正常调用，
     * 代价（计划整体丢失）远大于放过一个非法依赖（回到旧行为）。
     */
    private fun parseDeps(arr: JSONArray?, selfIndex: Int): List<Int> {
        if (arr == null) return emptyList()
        val out = LinkedHashSet<Int>()
        for (i in 0 until arr.length()) {
            val v = arr.opt(i)
            if (v is Int && v in 0 until selfIndex) out += v
        }
        return out.toList()
    }

    /**
     * 校验依赖完整性：返回**"被标成 done 但前置未完成"**的下标（空 = 通过）。
     *
     * 判据只看**显式** `dependsOn`；没填的步骤维持"数组顺序即依赖"的旧语义
     * （真按顺序强校验会拦住大量合法的乱序推进 —— 模型常常先做后面能做的部分）。
     */
    fun violationsOf(steps: List<PlanStep>): List<Int> =
        steps.mapIndexedNotNull { i, s ->
            if (s.status != "done") return@mapIndexedNotNull null
            val bad = s.dependsOn.filter { steps.getOrNull(it)?.status != "done" }
            if (bad.isEmpty()) null else i
        }

    /** 依赖违规的**人话**说明（回给模型；空 = 无违规） */
    fun violationMessage(steps: List<PlanStep>): String? {
        val bad = violationsOf(steps)
        if (bad.isEmpty()) return null
        // ⚠️ violationsOf 返回的是 **0-based 下标**（给代码索引用），
        // 而这段文案是**给模型看的**，必须 +1 转成 1-based ——
        // 少这一加，模型会去核对"第 1 步"，而真正有问题的是第 2 步，
        // 于是它得出"计划没问题"的结论，下一轮照旧跳过前置条件。
        return "计划里第 " + bad.joinToString("、") { (it + 1).toString() } +
            " 步被标成已完成，但它依赖的步骤还没完成：" +
            bad.flatMap { i ->
                steps[i].dependsOn.filter { steps.getOrNull(it)?.status != "done" }
                    .map { "「${steps.getOrNull(it)?.title ?: "?"}」" }
            }.toSet().joinToString("、") +
            "。⚠️ 请先把前置步骤做完再标 done；确实不需要前置依赖就别声明 depends_on。"
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
        // ⚠️ 违规**不拒绝整个调用**（模型可能只是在如实记录"我并行做了两步"），
        // 但必须明确告知 —— 否则模型不知道自己标出了一个系统认为无效的状态。
        violationMessage(steps)?.let { out.append("\n\n⚠️ ").append(it) }
        return out.toString()
    }
}
