package com.rokidlab.phone.ai

import org.json.JSONArray
import org.json.JSONObject

/**
 * 工具 JSON Schema 的**本地严格校验**。
 *
 * ## 为什么需要它
 * OpenAI 兼容服务端（DeepSeek 等）对 `tools[].function.parameters` 是做**严格 JSON Schema 校验**的，
 * 而且**校验失败是整请求级**的 —— 只要 44 个工具里有 1 个节点的形状不合规，服务端直接
 * `400 Invalid schema for function 'xxx'`，**所有工具一起失效**：模型既不能调用工具，
 * 也拿不到任何正文，表现为连续空轮（`toolCalls=0 content=null finish=none`）。
 *
 * 这类错误最恶劣的地方在于**症状与原因完全脱节**：写错的只是一个嵌套很深的 schema 节点，
 * 用户看到的是「AI 突然不会说话了」，排查时只能靠读服务端 400 报文里的原文。
 *
 * ## 2026-09-15 真机事故（本校验器的诞生原因）
 * `AgentPlan.schema()` 里 `properties.steps` 被直接 put 了一个 `JSONArray`，
 * 生成出 `"steps":[{…}]` —— schema 节点是个数组。服务端报：
 * ```
 * Invalid schema for function 'update_plan':
 * [{"type":"object",...}] is not of types "boolean", "object"
 * ```
 * 正确写法必须是 `{"type":"array","items":{…}}`。此错误让**乐奇聊天的 AI 能力整体失能**。
 *
 * ## 校验范围
 * 只校验「服务端会拒绝、且我们可能写错」的那些形状，不追求完整 JSON Schema 规范实现：
 *  - 任一 schema 节点必须是 JSON **对象**（是数组/字符串/数字一律报错）；
 *  - `properties` 必须是对象，其每个子值必须是 schema 节点；
 *  - `type=array` 必须带 `items`，且 `items` 必须是 schema 节点；
 *  - `required` 必须是字符串数组、`enum` 必须是数组、
 *    `additionalProperties` 必须是对象或布尔、`type` 取值必须在 7 种合法类型内。
 */
internal object ToolSchemaValidator {

    private val VALID_TYPES = setOf(
        "string", "number", "integer", "boolean", "object", "array", "null",
    )

    /** 校验单个工具声明；返回问题列表（空 = 通过）。[label] 仅用于报错定位。 */
    fun validate(tool: JSONObject, label: String): List<String> {
        val out = mutableListOf<String>()
        fun add(path: String, msg: String) {
            out += if (path.isEmpty()) "$label: $msg" else "$label · $path: $msg"
        }

        if (tool.optString("type") != "function") {
            add("", "type 必须是 \"function\"，实际是 ${typeNameOf(tool.opt("type"))}")
        }
        val fn = tool.optJSONObject("function")
        if (fn == null) {
            add("", "缺少 function 对象")
            return out
        }
        if (fn.optString("name").isBlank()) add("function.name", "为空")
        if (fn.optString("description").isBlank()) add("function.description", "为空（模型将无法判断何时调用）")

        val params = fn.opt("parameters")
        if (params == null || params === JSONObject.NULL) return out // 无参数工具，合法
        if (params !is JSONObject) {
            add(
                "function.parameters",
                "必须是 JSON 对象，实际是 ${typeNameOf(params)} —— 服务端会报 " +
                    "is not of types \"boolean\", \"object\"",
            )
            return out
        }
        if (params.optString("type") != "object") {
            add("function.parameters.type", "必须是 \"object\"，实际是 ${typeNameOf(params.opt("type"))}")
        }
        // 递归结果同样带上工具名前缀 —— 否则 44 个工具里出的问题无从定位（本次实测踩到）
        out += validateNode(params, "function.parameters").map { "$label · $it" }
        return out
    }

    /** 批量校验（按需去重由调用方负责）；返回全部问题，空 = 全部合规。 */
    fun validateAll(named: List<Pair<String, JSONObject>>): List<String> =
        named.flatMap { (label, schema) -> validate(schema, label) }

    /** 递归校验一个 JSON-Schema 节点，返回「路径: 问题」列表（不含工具名前缀）。 */
    private fun validateNode(node: Any?, path: String): List<String> {
        val out = mutableListOf<String>()
        if (node !is JSONObject) {
            out += "$path 必须是 JSON 对象（schema 节点），实际是 ${typeNameOf(node)} —— 服务端会整体拒绝该工具"
            return out
        }

        val type = node.opt("type")
        if (type != null && type !== JSONObject.NULL) {
            when (type) {
                is String -> if (type !in VALID_TYPES) out += "$path.type 取值非法：\"$type\""
                is JSONArray -> for (i in 0 until type.length()) {
                    val v = type.optString(i)
                    if (v !in VALID_TYPES) out += "$path.type[$i] 取值非法：\"$v\""
                }
                else -> out += "$path.type 必须是字符串，实际是 ${typeNameOf(type)}"
            }
        }

        val propsRaw = node.opt("properties")
        if (propsRaw != null && propsRaw !== JSONObject.NULL) {
            if (propsRaw !is JSONObject) {
                out += "$path.properties 必须是 JSON 对象，实际是 ${typeNameOf(propsRaw)}"
            } else {
                propsRaw.keys().forEach { k ->
                    out += validateNode(propsRaw.opt(k), "$path.properties.$k")
                }
            }
        }

        val itemsRaw = node.opt("items")
        if (itemsRaw != null && itemsRaw !== JSONObject.NULL) {
            out += validateNode(itemsRaw, "$path.items")
        }
        if (node.optString("type") == "array" && (itemsRaw == null || itemsRaw === JSONObject.NULL)) {
            out += "$path: type=array 但缺少 items（数组必须声明元素 schema）"
        }

        val reqRaw = node.opt("required")
        if (reqRaw != null && reqRaw !== JSONObject.NULL) {
            if (reqRaw !is JSONArray) {
                out += "$path.required 必须是数组，实际是 ${typeNameOf(reqRaw)}"
            } else {
                for (i in 0 until reqRaw.length()) {
                    if (reqRaw.opt(i) !is String) out += "$path.required[$i] 必须是字符串"
                }
            }
        }

        val enumRaw = node.opt("enum")
        if (enumRaw != null && enumRaw !== JSONObject.NULL && enumRaw !is JSONArray) {
            out += "$path.enum 必须是数组，实际是 ${typeNameOf(enumRaw)}"
        }

        val apRaw = node.opt("additionalProperties")
        if (apRaw != null && apRaw !== JSONObject.NULL && apRaw !is JSONObject && apRaw !is Boolean) {
            out += "$path.additionalProperties 必须是对象或布尔，实际是 ${typeNameOf(apRaw)}"
        }

        return out
    }

    private fun typeNameOf(v: Any?): String = when (v) {
        null -> "缺失"
        JSONObject.NULL -> "null"
        is JSONObject -> "JSON 对象"
        is JSONArray -> "JSON 数组"
        is String -> "字符串"
        is Boolean -> "布尔"
        is Number -> "数字"
        else -> v::class.java.simpleName
    }
}
