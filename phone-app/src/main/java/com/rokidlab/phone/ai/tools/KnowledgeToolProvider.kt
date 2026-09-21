package com.rokidlab.phone.ai.tools

import com.rokidlab.phone.R
import com.rokidlab.phone.ai.ToolContentTrust
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
            // 文档是用户导入的，但原文可能来自外部（说明书 PDF 转出的 txt、网页保存…），
            // 纵深防御：一律按不可信外部内容隔离，成本仅是两个标签
            contentTrust = ToolContentTrust.UNTRUSTED_EXTERNAL,
            statusText = "正在检索知识库…",
            schema = toolSchema(
                name = "search_knowledge_base",
                description = "在用户的本地知识库中检索资料并返回相关内容（结果带文档名+段号，可溯源）。" +
                    "当用户询问已导入文档（说明书、资料、笔记等）中的内容时调用，例如「键盘怎么用」「说明书里怎么说的」" +
                    "「我传给你的那份文档里写了什么」。⚠️ 泛问（「这文档讲了啥」「资料里怎么说的」）也要先调用它，" +
                    "用文档标题里的词或专有名词当 query；返回为空时结果里会列出知识库现有的文档名，据此换关键词再试一次。" +
                    "文档里确实没有的，如实说没有，不要凭记忆编。",
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
                    // ⚠️ 空结果不能只回一句"没找到"就此结束：词法检索（中文 2-gram + LIKE）
                    //    对"换个说法问"天然会失手（用户说"这东西怎么连"，文档里写的是"配对方法"）。
                    //    把库里**有哪些文档**告诉模型，它就能换成文档里更可能出现的关键词重试 ——
                    //    否则模型只能回用户一句"知识库里没有"，而用户知道文档明明在里面。
                    val names = runCatching { KnowledgeBase.docNames(context) }.getOrDefault(emptyList())
                    if (names.isEmpty()) {
                        "知识库是空的：用户还没有导入任何文档。请如实告知用户先去知识库导入文档。"
                    } else {
                        "知识库里没有检索到与「$query」相关的段落。当前知识库中的文档：" +
                            names.joinToString("、") { "《$it》" } +
                            "。如果用户问的确实与这些文档有关，请换用文档里更可能出现的关键词" +
                            "（文档标题里的词、专有名词、原文用词）再检索一次；确实没有就如实说没有，不要凭记忆编。"
                    }
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
