package com.rokidlab.phone.ai.tools

import com.rokidlab.phone.R
import com.rokidlab.phone.ai.ToolContentTrust
import com.rokidlab.phone.ai.ToolRisk

import android.content.Context
import com.rokidlab.phone.ai.ToolRegistry
import com.rokidlab.phone.ai.subagent.ReadOnlySubagent
import org.json.JSONObject

/**
 * SubagentToolProvider —— 只读子代理委派域（方案 §4.3.2）。
 *
 * 只有一个工具：把一个"查资料并给结论"的多轮调研派给独立的子助手去做，
 * 主循环只收结论，把轮次预算留给主线任务。
 *
 * ⚠️ 它**刻意不在** `SESSION_AIUI_DOMAINS` 里：AIUI / 代码生成回合需要的是装配文件与页面能力，
 * 派一次调研子任务既帮不上忙又会拖长这段最怕卡住的时间。
 */
internal object SubagentToolProvider : ToolProvider {

    override val toolNames = setOf(ReadOnlySubagent.TOOL_NAME)

    override fun tools(): List<ToolEntry> = listOf(
        ToolEntry(
            name = ReadOnlySubagent.TOOL_NAME,
            group = ToolRegistry.DOMAIN_RESEARCH,
            displayNameRes = R.string.ai_tool_research_subtask_name,
            descriptionRes = R.string.ai_tool_research_subtask_desc,
            risk = ToolRisk.READ_ONLY,
            // 子助手的结论整理自网页/知识库等外部内容，同样按不可信数据隔离
            contentTrust = ToolContentTrust.UNTRUSTED_EXTERNAL,
            statusText = "正在派子任务查资料…",
            schema = toolSchema(
                name = ReadOnlySubagent.TOOL_NAME,
                description = "把一个「只查资料、不改状态」的调研任务**派给独立的子助手**去做：" +
                    "它自己多轮搜索/读网页/查知识库，最后把带来源的结论交回来。" +
                    "**该用**：需要翻好几份资料才能回答的问题（如「帮我查一下 X 和 Y 的对比」「把这几篇文章的要点整理出来」）——" +
                    "派出去比你自己一轮一轮查更省轮次，也不会让大段网页正文把你的上下文塞满。" +
                    "**别用**：一句话能答的；需要设备操作、写文件、拨号等改状态动作的（子助手只有只读能力，做不了）；" +
                    "以及需要看用户原话或图片的（它只拿到你写的 question，看不到对话）。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "question" to mapOf(
                            "type" to "string",
                            "description" to "要查什么、要得出什么结论。写具体：子助手看不到你和用户的对话，只能看到这一段",
                        ),
                        "rounds" to mapOf(
                            "type" to "integer",
                            "description" to "最多让它查几轮，默认 4，最多 6",
                            "minimum" to 1,
                            "maximum" to 6,
                        ),
                    ),
                    "required" to listOf("question"),
                ),
            ),
        ),
    )

    override fun execute(context: Context, name: String, args: JSONObject): String = when (name) {
        ReadOnlySubagent.TOOL_NAME ->
            ReadOnlySubagent.run(context, args.optString("question"), args.optInt("rounds", 0))

        else -> throw IllegalArgumentException("未知工具: $name")
    }
}
