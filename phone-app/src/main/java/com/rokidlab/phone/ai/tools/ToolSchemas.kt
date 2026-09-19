package com.rokidlab.phone.ai.tools

import com.rokidlab.phone.ai.ToolRegistry
import com.rokidlab.phone.ai.ToolRegistry.ToolMeta
import org.json.JSONObject

/*
 * 工具 Schema 辅助函数。
 *
 * ⚠️ 历史上这里是一个 400+ 行的 `buildToolSchema(meta)` 巨型 `when`，集中声明全部工具的 schema。
 * 工具接缝化后，schema 已随工具定义搬进各 provider 的 `tools()`（见 [ToolEntry.schema]），
 * 本文件只剩这个构造辅助函数。
 *
 * 为什么必须搬：schema 与"谁能执行它"分处两个文件时，改名/新增工具只会改一处，
 * 另一处**不编译错、不抛异常**，只让模型看到错误的声明。放到一起才能保证同生同死。
 */
internal fun toolSchema(name: String, description: String, parameters: Map<String, Any>): JSONObject {
    return JSONObject().apply {
        put("type", "function")
        put("function", JSONObject().apply {
            put("name", name)
            put("description", description)
            put("parameters", JSONObject(parameters))
        })
    }
}
