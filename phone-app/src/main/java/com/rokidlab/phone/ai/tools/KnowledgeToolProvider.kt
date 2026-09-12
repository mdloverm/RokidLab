package com.rokidlab.phone.ai.tools

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
