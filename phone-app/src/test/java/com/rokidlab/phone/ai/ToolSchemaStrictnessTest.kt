package com.rokidlab.phone.ai

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具 JSON Schema **严格性回归测试**。
 *
 * 服务端（DeepSeek 等 OpenAI 兼容端点）对 `tools[].function.parameters` 做严格校验，
 * 且**失败是整请求级**的：几十个工具里只要 1 个嵌套节点形状不合规，整个请求 400，
 * 所有工具一起失效 —— 症状是「AI 突然不会说话了」，成因却在某个深层 schema 节点里。
 *
 * 2026-09-15 真机事故：`AgentPlan.schema()` 把 `properties.steps` 直接写成了 `JSONArray`
 * （`"steps":[{…}]`），服务端报
 * `Invalid schema for function 'update_plan': [{…}] is not of types "boolean", "object"`，
 * 导致乐奇聊天的 AI 能力**整体失能**，而日志里只有 `toolCalls=0 content=null finish=none` 的空轮。
 *
 * 本测试跑在 JVM 上（`ToolRegistry.buildSchema` 是 internal 且不依赖 Context），
 * 每次构建都会拦下这一类错误。
 */
class ToolSchemaStrictnessTest {

    /** 全部会被真实下发给服务端的工具声明（provider 工具 + 4 个伪工具）。 */
    private fun allSchemas(): List<Pair<String, JSONObject>> {
        val out = mutableListOf<Pair<String, JSONObject>>()
        assertTrue(
            "工具表不应为空，否则本测试形同虚设",
            ToolRegistry.toolList.size >= 30,
        )
        ToolRegistry.toolList.forEach { meta ->
            out += meta.name to ToolRegistry.buildSchema(meta)
        }
        // 伪工具：不在 toolList 中，但同样会进 tools 参数
        out += "update_plan" to AgentPlan.schema()
        out += SkillRegistry.TOOL_NAME to SkillRegistry.schema()
        out += SkillRegistry.TOOL_NAME_SECTION to SkillRegistry.sectionSchema()
        out += "manage_memory" to LongTermMemoryManager.schema()
        return out
    }

    @Test
    fun `全部工具 schema 通过严格校验`() {
        val problems = ToolSchemaValidator.validateAll(allSchemas())
        assertTrue(
            "以下 schema 会被服务端拒绝并让整个 AI 请求 400：\n" + problems.joinToString("\n"),
            problems.isEmpty(),
        )
    }

    @Test
    fun `全部工具名唯一且非空`() {
        val names = allSchemas().map { it.first }
        assertEquals("工具名重复会让命中/回填错乱", names.size, names.toSet().size)
        assertTrue("存在空工具名", names.none { it.isBlank() })
    }

    // ═══════════ update_plan 专项（事故回归） ═══════════

    @Test
    fun `update_plan 的 steps 必须是 array schema 且带 items`() {
        val params = AgentPlan.schema().getJSONObject("function").getJSONObject("parameters")
        val steps = params.getJSONObject("properties").getJSONObject("steps")
        assertEquals("steps 必须是 array 类型", "array", steps.getString("type"))
        assertTrue("steps 必须声明 items", steps.optJSONObject("items") != null)
        val items = steps.getJSONObject("items")
        assertEquals("object", items.getString("type"))
        val required = items.getJSONArray("required")
        val reqNames = (0 until required.length()).map { required.getString(it) }
        assertTrue("items.required 必须含 title/status", reqNames.containsAll(listOf("title", "status")))
        assertTrue("steps 必须在 parameters.required 里", params.getJSONArray("required").let { r ->
            (0 until r.length()).any { r.getString(it) == "steps" }
        })
    }

    @Test
    fun `校验器能识别出事故当时的数组写法`() {
        // 复刻 2026-09-15 的坏结构：properties.steps 直接 put 了 JSONArray
        val broken = JSONObject()
            .put("type", "function")
            .put(
                "function",
                JSONObject()
                    .put("name", "update_plan")
                    .put("description", "占位描述，长度足够避免被其它规则误判")
                    .put(
                        "parameters",
                        JSONObject()
                            .put("type", "object")
                            .put(
                                "properties",
                                JSONObject().put(
                                    "steps",
                                    JSONArray().put(
                                        JSONObject()
                                            .put("type", "object")
                                            .put("properties", JSONObject().put("title", JSONObject().put("type", "string")))
                                            .put("required", JSONArray(listOf("title"))),
                                    ),
                                ),
                            ),
                    ),
            )
        val problems = ToolSchemaValidator.validate(broken, "update_plan")
        assertTrue(
            "校验器必须能识别出被写成 JSON 数组的 schema 节点，实际问题列表：$problems",
            problems.any { it.contains("properties.steps") && it.contains("JSON 数组") },
        )
    }

    @Test
    fun `校验器能识别缺失 items 的 array`() {
        val bad = JSONObject()
            .put("type", "function")
            .put(
                "function",
                JSONObject()
                    .put("name", "t")
                    .put("description", "占位描述，长度足够避免被其它规则误判")
                    .put(
                        "parameters",
                        JSONObject()
                            .put("type", "object")
                            .put("properties", JSONObject().put("xs", JSONObject().put("type", "array"))),
                    ),
            )
        val problems = ToolSchemaValidator.validate(bad, "t")
        assertTrue("应为 type=array 缺 items 报错：$problems", problems.any { it.contains("缺少 items") })
    }

    @Test
    fun `校验器能识别 parameters 不是对象`() {
        val bad = JSONObject()
            .put("type", "function")
            .put(
                "function",
                JSONObject()
                    .put("name", "t")
                    .put("description", "占位描述，长度足够避免被其它规则误判")
                    .put("parameters", JSONArray().put(JSONObject().put("type", "object"))),
            )
        val problems = ToolSchemaValidator.validate(bad, "t")
        assertTrue("应为 parameters 非对象报错：$problems", problems.any { it.contains("必须是 JSON 对象") })
    }

    @Test
    fun `合法 schema 不产生任何问题`() {
        val ok = JSONObject()
            .put("type", "function")
            .put(
                "function",
                JSONObject()
                    .put("name", "t")
                    .put("description", "占位描述，长度足够避免被其它规则误判")
                    .put(
                        "parameters",
                        JSONObject()
                            .put("type", "object")
                            .put(
                                "properties",
                                JSONObject()
                                    .put("q", JSONObject().put("type", "string").put("description", "查询"))
                                    .put(
                                        "tags",
                                        JSONObject()
                                            .put("type", "array")
                                            .put("items", JSONObject().put("type", "string")),
                                    ),
                            )
                            .put("required", JSONArray(listOf("q"))),
                    ),
            )
        assertEquals("合法 schema 不应报错", emptyList<String>(), ToolSchemaValidator.validate(ok, "t"))
    }
}
