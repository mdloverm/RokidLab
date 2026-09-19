package com.rokidlab.phone.ai.tools

import com.rokidlab.phone.R
import com.rokidlab.phone.ai.ToolRisk

import android.content.Context
import android.util.Log
import com.rokidlab.phone.ai.AiuiAppRegistry
import com.rokidlab.phone.ai.AiuiProject
import com.rokidlab.phone.ai.Calculator
import com.rokidlab.phone.ai.KnowledgeBase
import com.rokidlab.phone.ai.KuwoMusicApi
import com.rokidlab.phone.ai.LocationTools
import com.rokidlab.phone.ai.MusicPlayerController
import com.rokidlab.phone.ai.PhoneTools
import com.rokidlab.phone.ai.ToolRegistry
import com.rokidlab.phone.ai.WeatherTools
import com.rokidlab.phone.ai.WebTools
import com.rokidlab.phone.app.LabApplication
import com.rokidlab.phone.adb.ui.TimerAction
import com.rokidlab.phone.adb.ui.TimerSchedule
import com.rokidlab.phone.adb.ui.TimerTask
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * KnowledgeToolProvider —— 本地知识库检索域。
 * Phase 4 从 `ToolRegistry.execute` 迁出的该域工具执行分支（逐字搬运，行为不变）。
 */
internal object KnowledgeToolProvider : ToolProvider {
    private const val TAG = "KnowledgeToolProvider"

    override val toolNames = setOf(
        "search_knowledge_base",
    )

    override fun tools(): List<ToolEntry> = listOf(
        ToolEntry(
            name = "search_knowledge_base",
            group = ToolRegistry.DOMAIN_KNOWLEDGE,
            displayNameRes = R.string.ai_tool_search_knowledge_base_name,
            descriptionRes = R.string.ai_tool_search_knowledge_base_desc,
            risk = ToolRisk.READ_ONLY,
            statusText = "正在检索知识库…",
            schema = toolSchema(
                name = "search_knowledge_base",
                description = "在用户的本地知识库中检索资料并返回相关内容。当用户询问已导入文档（说明书、资料、笔记等）中的内容时调用，例如“键盘怎么用”、“说明书里怎么说的”。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "query" to mapOf("type" to "string", "description" to "检索关键词，用最核心的 2~4 个词"),
                        "topK" to mapOf("type" to "integer", "description" to "返回的资料块数量，默认 3", "minimum" to 1, "maximum" to 5),
                    ),
                    "required" to listOf("query"),
                ),
            ),
        ),
    )

    override fun execute(context: Context, name: String, args: JSONObject): String {
        return when (name) {
            "search_knowledge_base" -> {
                val query = args.optString("query")
                val topK = args.optInt("topK", 3).coerceIn(1, 5)
                val results = KnowledgeBase.searchHits(context, query, topK)
                if (results.isEmpty()) {
                    "知识库中没有找到与“$query”相关的内容"
                } else {
                    // 来源标注：让模型（与用户）知道结论出自哪份文档的哪一块，可溯源
                    results.mapIndexed { i, hit ->
                        "[${i + 1}]（来源：《${hit.docName}》第${hit.chunkIdx + 1}块）${hit.text}"
                    }.joinToString("\n")
                }
            }

        else -> throw IllegalArgumentException("未知工具: $name")
        }
    }
}
