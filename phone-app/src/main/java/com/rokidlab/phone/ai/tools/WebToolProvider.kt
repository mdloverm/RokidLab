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
 * WebToolProvider —— 联网域（搜索/读网页）。
 * Phase 4 从 `ToolRegistry.execute` 迁出的该域工具执行分支（逐字搬运，行为不变）。
 */
internal object WebToolProvider : ToolProvider {
    private const val TAG = "WebToolProvider"

    override val toolNames = setOf(
        "search_web",
        "fetch_webpage",
    )

    override fun tools(): List<ToolEntry> = listOf(
        ToolEntry(
            name = "search_web",
            group = ToolRegistry.DOMAIN_WEB,
            displayNameRes = R.string.ai_tool_search_web_name,
            descriptionRes = R.string.ai_tool_search_web_desc,
            risk = ToolRisk.READ_ONLY,
            statusText = "正在搜索网页…",
            schema = toolSchema(
                name = "search_web",
                description = "联网搜索网页信息并返回候选列表（标题+链接+摘要）。当用户要求搜索某主题的最新内容、资讯、攻略、评测等，或要求“搜一下/查一下/搜索”时先调用本工具（例如“搜一下 Rokid 眼镜的评测”）。拿到结果后，若需要深入了解详情，再对选中的链接调用 fetch_webpage 读取正文。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "query" to mapOf("type" to "string", "description" to "搜索关键词，用能代表主题的 2~6 个词，如“Rokid 眼镜 评测”"),
                    ),
                    "required" to listOf("query"),
                ),
            ),
        ),
        ToolEntry(
            name = "fetch_webpage",
            group = ToolRegistry.DOMAIN_WEB,
            displayNameRes = R.string.ai_tool_fetch_webpage_name,
            descriptionRes = R.string.ai_tool_fetch_webpage_desc,
            risk = ToolRisk.READ_ONLY,
            statusText = "正在读取网页内容…",
            schema = toolSchema(
                name = "fetch_webpage",
                description = "读取一个具体网页链接的正文内容并转为纯文本（含网页标题）。配合 search_web 使用：当用户想了解某网页的详细内容、或需要总结某个搜索结果页面时，传入该网页完整链接调用本工具。返回的正文供你阅读后进行总结。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "url" to mapOf("type" to "string", "description" to "要读取的网页完整链接，须以 http:// 或 https:// 开头，如 https://example.com/article"),
                    ),
                    "required" to listOf("url"),
                ),
            ),
        ),
    )

    override fun execute(context: Context, name: String, args: JSONObject): String = when (name) {
            "search_web" -> WebTools.search(args.optString("query"))

            "fetch_webpage" -> WebTools.fetchPage(args.optString("url"))

        else -> throw IllegalArgumentException("未知工具: $name")
    }
}
