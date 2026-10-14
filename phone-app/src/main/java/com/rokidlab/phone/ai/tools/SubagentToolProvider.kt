package com.rokidlab.phone.ai.tools

import com.rokidlab.phone.R
import com.rokidlab.phone.ai.ToolContentTrust
import com.rokidlab.phone.ai.ToolRisk

import android.content.Context
import com.rokidlab.phone.ai.ToolRegistry
import com.rokidlab.phone.ai.subagent.ReadOnlySubagent
import com.rokidlab.phone.ai.subagent.WriteSubagent
import org.json.JSONObject

/**
 * SubagentToolProvider —— 子代理委派域（方案 §4.3.2，2026-10-02 扩为读写两型）。
 *
 * 两个工具：
 *  - [ReadOnlySubagent.TOOL_NAME]：把「查资料并给结论」的多轮调研派给独立子助手（只读）；
 *  - [WriteSubagent.TOOL_NAME]：把「动手做一件本机的事」的多轮执行派给独立子助手
 *    （只读+本机副作用，外部副作用工具在装配层就不可见，见 WriteSubagent 类注释）。
 *
 * 主循环只收结论/结果，把轮次预算留给主线任务。
 *
 * ⚠️ 它们**刻意不在** `SESSION_AIUI_DOMAINS` 里：AIUI / 代码生成回合需要的是装配文件与页面能力，
 * 派子任务既帮不上忙又会拖长这段最怕卡住的时间。
 */
internal object SubagentToolProvider : ToolProvider {

    override val toolNames = setOf(ReadOnlySubagent.TOOL_NAME, WriteSubagent.TOOL_NAME)

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
        ToolEntry(
            name = WriteSubagent.TOOL_NAME,
            group = ToolRegistry.DOMAIN_RESEARCH,
            displayNameRes = R.string.ai_tool_execute_subtask_name,
            descriptionRes = R.string.ai_tool_execute_subtask_desc,
            // 子代理可执行本机副作用工具（写文件/设定时器等），按其中最高风险登记；
            // 外部副作用工具在 WriteSubagent 装配层已摘除，执行层另有双保险
            risk = ToolRisk.LOCAL_SIDE_EFFECT,
            // ⚠️ 2026-10-09 补：原先**漏标** ⇒ 进了并行泳道 + maxAttempts 仍是 3。
            //   两个后果都是真机上能观察到的：
            //   ① 主代理可同时派 2 个 write 子代理（SubagentSlots 上限 2）⇒ 两个子代理
            //      并发写同一个项目目录（这正是"写盘竞态"的形状）；
            //   ② 瞬时失败重放 3 次 ⇒ 子代理里的 `set_phone_alarm` / `manage_timer` 会
            //      **设出 3 个重复提醒**（写文件重放相对无害，设提醒不是）。
            //   📌 判据：LOCAL 档**允许**幂等工具不标（`stop_tts`/`show_image` 重放无害），
            //      所以这里逐个判断"重放会不会产生用户可见的重复"。本工具**会** ——
            //      它把一整个多步任务派出去做，而任务里含设定时器这类累积性动作。
            sideEffect = true,
            // 子助手的结论整理自网页/知识库等外部内容，同样按不可信数据隔离
            contentTrust = ToolContentTrust.UNTRUSTED_EXTERNAL,
            statusText = "正在派子任务执行…",
            schema = toolSchema(
                name = WriteSubagent.TOOL_NAME,
                description = "把一件「动手做的本机任务」**派给独立的子助手**去做：它可以多轮调用" +
                    "写文件/设定时器/查资料等本机工具，做完把交代交回来。" +
                    "**该用**：需要好几个步骤的本机任务（如「把这几篇文章存成文件」「设三个提醒」）——" +
                    "派出去比你自己一轮一轮做更省轮次。" +
                    "**别用**：拨号、发短信等触达第三方的动作（子助手没有这类工具，做了也会被拦）；" +
                    "执行 shell 命令或模拟点击屏幕（同样不可用）；" +
                    "一句话能完成的事你自己做就行。" +
                    "它看不到你和用户的对话，把要做的事在 task 里写清楚。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "task" to mapOf(
                            "type" to "string",
                            "description" to "要完成什么任务、做到什么程度算完成。写具体：子助手看不到你和用户的对话，只能看到这一段",
                        ),
                        "rounds" to mapOf(
                            "type" to "integer",
                            "description" to "最多让它做几轮，默认 6，最多 8",
                            "minimum" to 1,
                            "maximum" to 8,
                        ),
                    ),
                    "required" to listOf("task"),
                ),
            ),
        ),
    )

    override fun execute(context: Context, name: String, args: JSONObject): String = when (name) {
        ReadOnlySubagent.TOOL_NAME ->
            // ⚠️ 把**最后一条用户消息**一并传下去（2026-10-06）：子代理看不到对话历史，
            // 少了它就无法做指代消解（"这个/刚才那个"指什么），只能按字面泛泛地查。
            // 走 ThreadLocal 而不是改 ToolProvider 接口 —— 后者要动全部 21 个 provider，
            // 与 vision 的 takePendingImage() 是同一套手法（主循环同线程 runTool 后取出）。
            //
            // ★ 内层进度上报（2026-10-06 审计修）：子代理最多 6 轮 × 每轮多个工具、
            // 最长 90 秒，此前**全部只进 logcat** ⇒ 过程时间线上只有这一行在转圈。
            // sink 由主循环在 runTool 之前写入（[ReadOnlySubagent.setSubStepSink]），
            // 这里只需把它传进 run —— run() 内部会**取走即清**，不让它泄漏到下一次子代理。
            ReadOnlySubagent.run(
                context,
                args.optString("question"),
                args.optInt("rounds", 0),
                ReadOnlySubagent.takeSubStepSink(),
            )

        WriteSubagent.TOOL_NAME ->
            // ★ 内层进度上报（2026-10-12）：与只读子代理同款——write 型子代理内层
            //   6~8 轮写文件/打补丁此前一行过程都不发，用户只见外层转圈最长 120 秒。
            //   sink 由主循环在 runTool 之前写入（[WriteSubagent.setSubStepSink]），
            //   run() 内部**取走即清**，不让它泄漏到下一次子代理。
            WriteSubagent.run(
                context,
                args.optString("task"),
                args.optInt("rounds", 0),
                WriteSubagent.takeSubStepSink(),
            )

        else -> throw IllegalArgumentException("未知工具: $name")
    }
}
