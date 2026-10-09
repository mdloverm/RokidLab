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
        "manage_knowledge_base",
        "delete_knowledge_doc",
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
        ToolEntry(
            name = "manage_knowledge_base",
            group = ToolRegistry.DOMAIN_KNOWLEDGE,
            displayNameRes = R.string.ai_tool_manage_knowledge_base_name,
            descriptionRes = R.string.ai_tool_manage_knowledge_base_desc,
            risk = ToolRisk.LOCAL_SIDE_EFFECT,
            contentTrust = ToolContentTrust.TRUSTED,
            // ⚠️ 2026-10-09 补：action=add 是**入库**（真副作用），原先漏标 ⇒ 并行执行 +
            //   失败被重放 3 次 ⇒ 一次入库失败会多出 2~3 条重复文档。list 分支是只读，
            //   但按工具粒度标注是保守方向（宁可串行一点，不可重复写入）。
            sideEffect = true,
            statusText = "正在整理知识库…",
            schema = toolSchema(
                name = "manage_knowledge_base",
                description = "管理用户的本地知识库（列出 / 存入）。action=list 列出现有文档（带 id，删除前必查）；" +
                    "action=add 把一段文本存为新文档（name=文档名，text=正文），适合「把这段话存进知识库」、" +
                    "「记住这份资料以后可以查」这类请求。存完建议用 search_knowledge_base 检索一次验证内容可查。" +
                    "删除文档不在这里，用 delete_knowledge_doc。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "action" to mapOf(
                            "type" to "string",
                            "enum" to listOf("list", "add"),
                            "description" to "list=列出现有文档；add=存入新文档",
                        ),
                        "name" to mapOf("type" to "string", "description" to "add 时必填：新文档的名称（如「火锅店清单」）"),
                        "text" to mapOf("type" to "string", "description" to "add 时必填：要存入的正文内容"),
                    ),
                    "required" to listOf("action"),
                ),
            ),
        ),
        ToolEntry(
            name = "delete_knowledge_doc",
            group = ToolRegistry.DOMAIN_KNOWLEDGE,
            displayNameRes = R.string.ai_tool_delete_knowledge_doc_name,
            descriptionRes = R.string.ai_tool_delete_knowledge_doc_desc,
            // 删除不可恢复 → EXTERNAL 档；confirmPolicy 保持缺省 BLOCK（问不到用户就拒）。
            // 走 ApprovalGate 确认闸门：用户在眼镜/手机上亲自点头才删。
            // sideEffect=true（2026-10-02 护栏单测抓出的漏标）：删除必须进串行泳道且失败不重试，
            // 否则隧道瞬断回执丢失时会原样重试「删除」——不可逆动作绝不允许重放。
            risk = ToolRisk.EXTERNAL_SIDE_EFFECT,
            sideEffect = true,
            contentTrust = ToolContentTrust.TRUSTED,
            statusText = "正在删除知识库文档…",
            schema = toolSchema(
                name = "delete_knowledge_doc",
                description = "从用户的本地知识库中**删除一份文档**（不可恢复，会先向用户确认）。" +
                    "docId 必须先用 manage_knowledge_base 的 list 动作查到，不要凭记忆猜 id；" +
                    "动手前先向用户复述将删的文档名并得到确认。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "docId" to mapOf("type" to "integer", "description" to "要删除的文档 id（来自 manage_knowledge_base 的 list）"),
                    ),
                    "required" to listOf("docId"),
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

            "manage_knowledge_base" -> {
                when (args.optString("action").trim()) {
                    "list" -> {
                        val docs = KnowledgeBase.listDocs(context)
                        if (docs.isEmpty()) {
                            "知识库当前为空。可以用本工具的 add 动作把文本存进去。"
                        } else {
                            docs.joinToString("\n") { d ->
                                "《${d.name}》（id=${d.id}，${d.size / 1024}KB，${d.chunkCount} 块，charset=${d.charset.ifEmpty { "未知" }}）"
                            } + "\n删除其中一份用 delete_knowledge_doc（不可恢复，删除前先向用户确认）。"
                        }
                    }
                    "add" -> {
                        val name = args.optString("name").trim()
                        val text = args.optString("text")
                        if (name.isEmpty() || text.isBlank()) {
                            "参数不完整：add 需要 name（文档名）和 text（正文），两者都不能为空。"
                        } else {
                            runCatching { KnowledgeBase.importText(context, name, text) }
                                .fold(
                                    onSuccess = { info ->
                                        "已导入知识库：《${info.name}》（id=${info.id}，${info.chunkCount} 块）。" +
                                            "建议用 search_knowledge_base 检索一次验证内容可查；若块数为 0 说明内容过短或为空，请如实告知用户。"
                                    },
                                    onFailure = { e ->
                                        "导入知识库失败：${e.message ?: e.javaClass.simpleName}。请检查文本内容后重试。"
                                    },
                                )
                        }
                    }
                    else -> "未知 action：${args.optString("action")}。可用值：list、add。"
                }
            }

            "delete_knowledge_doc" -> {
                val docId = args.optLong("docId", -1L)
                if (docId <= 0) {
                    "参数不完整：需要有效的 docId。先用 manage_knowledge_base 的 list 动作查询现有文档与 id。"
                } else {
                    val target = KnowledgeBase.listDocs(context).firstOrNull { it.id == docId }
                    if (target == null) {
                        "找不到 id=$docId 的知识库文档（可能已删除）。先用 manage_knowledge_base 的 list 动作确认现有文档与 id。"
                    } else {
                        runCatching { KnowledgeBase.deleteDoc(context, docId) }
                            .fold(
                                onSuccess = { "已删除知识库文档：《${target.name}》（id=$docId）。此操作不可恢复。" },
                                onFailure = { e -> "删除失败：${e.message ?: e.javaClass.simpleName}" },
                            )
                    }
                }
            }

        else -> throw IllegalArgumentException("未知工具: $name")
        }
    }
}
