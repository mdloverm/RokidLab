package com.rokidlab.phone.ai.tools

import com.rokidlab.phone.R
import com.rokidlab.phone.ai.ToolRisk

import android.content.Context
import android.util.Log
import com.rokidlab.phone.ai.AiuiAppRegistry
import com.rokidlab.phone.ai.AiuiProject
import com.rokidlab.phone.ai.Calculator
import com.rokidlab.phone.ai.FileWorkspace
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
 * FilesToolProvider —— 文件产出域（总结 txt / AIUI 代码落盘与回读）。
 * Phase 4 从 `ToolRegistry.execute` 迁出的该域工具执行分支（逐字搬运，行为不变）。
 */
internal object FilesToolProvider : ToolProvider {
    private const val TAG = "FilesToolProvider"

    override val toolNames = setOf(
        "save_summary_txt",
        "save_code_file",
        "read_code_file",
        "list_files",
        "read_text_file",
        "search_files",
        "edit_text_file",
        "delete_file",
        "move_file",
    )

    override fun tools(): List<ToolEntry> = listOf(
        ToolEntry(
            name = "save_summary_txt",
            group = ToolRegistry.DOMAIN_FILES,
            displayNameRes = R.string.ai_tool_save_summary_txt_name,
            descriptionRes = R.string.ai_tool_save_summary_txt_desc,
            risk = ToolRisk.LOCAL_SIDE_EFFECT,
            statusText = "正在生成并保存总结…",
            schema = toolSchema(
                name = "save_summary_txt",
                description = "将总结好的内容保存为 txt 文件：写入手机系统下载目录，并同步导入本地知识库（之后可用 search_knowledge_base 检索）。当用户明确要求“把总结存成 txt/文件”“保存总结”“整理成文档/笔记”时，在完成内容总结后调用。内容应完整、条理清晰，尽量包含核心要点。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "title" to mapOf("type" to "string", "description" to "文档标题（会作为文件名与知识库条目名），简洁概括主题，如“Rokid 眼镜评测总结”"),
                        "content" to mapOf("type" to "string", "description" to "要保存的完整总结正文，包含标题下的具体要点内容，供落盘与知识库检索"),
                    ),
                    "required" to listOf("title", "content"),
                ),
            ),
        ),
        ToolEntry(
            name = "save_code_file",
            group = ToolRegistry.DOMAIN_FILES,
            displayNameRes = R.string.ai_tool_save_code_file_name,
            descriptionRes = R.string.ai_tool_save_code_file_desc,
            risk = ToolRisk.LOCAL_SIDE_EFFECT,
            statusText = "正在生成代码文件…",
            schema = toolSchema(
                name = "save_code_file",
                description = "把生成的一段代码保存为项目文件，写入手机“下载/项目名/”目录。当用户要求“写代码/生成页面或应用/创建项目文件”等需要产出代码、脚本、配置或页面文件时使用；一次调用只写一个文件，一个项目有多个文件时按文件逐个调用（每次用相同的项目名）。必须通过本工具把代码落盘，不要把整段代码直接当作回复内容发给用户；回复只简短告知项目名与文件数量。若目标是“AIUI 智能体应用（.aix）”，至少需要 app.json（页面清单）与 pages/index/index.ink（首页），可再加 VERSION/AGENTS.md/app.js 与更多 pages/*/index.ink；app.json 的页面路径与文件名一致。每个 .ink 页面必须遵循 AIUI SFC 四块结构并按顺序书写：<script def>（页面级 JSON 配置，如导航栏标题）→ <script setup>（export default 逻辑/data/生命周期）→ <page>（WXML 模板，根标签必须是 <page>，禁止用 <template>）→ <style>（样式）。写成 Vue 风格（<template>/<script>）会被拒绝保存，眼镜上也无法渲染。若页面需要调用手机端能力（放歌、查天气、搜网页、设提醒等），只能走 Lab 工具口。⚠️ ink 沙箱只执行同步代码、不执行 Promise 回调：禁止 await、.then、Promise.all、setTimeout/setInterval（写了会永不返回，页面当场卡死）。唯一正确写法是回调式：globalThis.Lab.callTool('工具名', {参数}, function(res, err){ 在回调里 setData 刷新界面 })，查可用工具名用 globalThis.Lab.listTools(function(tools, err){...})；必须写 globalThis.Lab 或 window.Lab（裸 Lab 在页面 realm 不可用）；工具名必须与清单逐字一致，禁止自造名字；一次只调一个工具，失败不要写自动重试循环。若目标是修改一个已生成的项目，请先调用 read_code_file 读取该文件当前的真实内容，在它基础上改动后覆盖写回同一路径，不要凭印象整文件重编。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "project" to mapOf("type" to "string", "description" to "项目文件夹名（下载目录下的一级文件夹名，同一项目的多个文件必须相同），如 aiui-demo"),
                        "file" to mapOf("type" to "string", "description" to "该文件的相对路径（含文件名，可带子目录），如 app.json、pages/index/index.ink"),
                        "content" to mapOf("type" to "string", "description" to "该文件的完整代码内容"),
                    ),
                    "required" to listOf("project", "file", "content"),
                ),
            ),
        ),
        ToolEntry(
            name = "read_code_file",
            group = ToolRegistry.DOMAIN_FILES,
            displayNameRes = R.string.ai_tool_read_code_file_name,
            descriptionRes = R.string.ai_tool_read_code_file_desc,
            risk = ToolRisk.READ_ONLY,
            statusText = "正在读取项目源码…",
            schema = toolSchema(
                name = "read_code_file",
                description = "读取对话中通过写文件工具生成的项目源码当前内容（读取手机本地的项目镜像，不改动任何文件）。当用户说“修改/改一下/调整/优化/重做/对之前的 XXX 不满意”而对象是之前生成过的代码项目或 AIUI 智能体应用时必须先调用本工具：不确定要改哪个文件时可只传 project（返回该项目的文件清单），确定后带 file 读取对应文件的完整内容，再基于读到的真实源码用“保存代码文件”工具(save_code_file)覆盖写回同一 project 的同一路径，最后用“安装 AIUI 项目”工具(install_aiui_project)重新安装到眼镜。禁止凭印象或记忆整文件重编——必须先读现网源码再动手，只重写受影响的文件。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "project" to mapOf("type" to "string", "description" to "项目文件夹名（与写文件时的 project 参数完全一致），如 aiui-demo"),
                        "file" to mapOf("type" to "string", "description" to "可选：要读取文件的相对路径（含文件名，可带子目录），如 app.json、pages/index/index.ink；不传则返回整个项目的文件清单"),
                    ),
                    "required" to listOf("project"),
                ),
            ),
        ),

        // ══════════ 文件工作区（列 / 读 / 搜 / 改 / 删 / 移） ══════════
        // 作用域见 ai/FileWorkspace.kt 的类注释：project = App 私有项目镜像（全部能力），
        // downloads = 系统下载目录（只能列/读/搜/删）。没有全盘权限，边界必须写进 schema，
        // 否则模型会按"能读任意文件"的直觉乱猜路径。
        ToolEntry(
            name = "list_files",
            group = ToolRegistry.DOMAIN_FILES,
            displayNameRes = R.string.ai_tool_list_files_name,
            descriptionRes = R.string.ai_tool_list_files_desc,
            risk = ToolRisk.READ_ONLY,
            statusText = "正在查看文件…",
            schema = toolSchema(
                name = "list_files",
                description = "列出文件。scope=\"project\"（默认）列出 App 生成过的项目工程目录（filesDir 私有镜像）；" +
                    "scope=\"downloads\" 列出系统下载目录里本 App 生成的文件。project 可选（下载目录下传项目名可只看某个项目）；" +
                    "path 可选（project 作用域下的子目录，如 pages/index）。用户问「我生成过哪些文件」「那个项目里有什么」时先用本工具。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "scope" to mapOf(
                            "type" to "string",
                            "enum" to listOf("project", "downloads"),
                            "description" to "工作区：project=App 项目工程目录（可改），downloads=系统下载目录（只能看/删）。默认 project",
                        ),
                        "project" to mapOf("type" to "string", "description" to "可选，项目名（下载目录作用域下也支持），如 aiui-demo"),
                        "path" to mapOf("type" to "string", "description" to "可选，子目录相对路径（仅 project 作用域下钻），如 pages/index"),
                    ),
                    "required" to emptyList<String>(),
                ),
            ),
        ),
        ToolEntry(
            name = "read_text_file",
            group = ToolRegistry.DOMAIN_FILES,
            displayNameRes = R.string.ai_tool_read_text_file_name,
            descriptionRes = R.string.ai_tool_read_text_file_desc,
            risk = ToolRisk.READ_ONLY,
            statusText = "正在读取文件…",
            schema = toolSchema(
                name = "read_text_file",
                description = "读取一个文本文件的完整内容。scope=\"project\" 读 App 项目工程目录里的文件；" +
                    "scope=\"downloads\" 读系统下载目录里本 App 生成的文件（path 传相对路径，如 项目名/app.json 或 xxx.txt）。" +
                    "读取前若不确定路径，先用 list_files 或 search_files 定位。**不要凭印象回答文件内容**——必须先读。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "scope" to mapOf(
                            "type" to "string",
                            "enum" to listOf("project", "downloads"),
                            "description" to "工作区，默认 project",
                        ),
                        "project" to mapOf("type" to "string", "description" to "项目名（project 作用域必填；downloads 作用域可省略，会自动取路径首段）"),
                        "path" to mapOf("type" to "string", "description" to "文件相对路径，如 app.json、pages/index/index.ink（downloads 作用域写 项目名/文件名，或直接 文件名）"),
                    ),
                    "required" to listOf("path"),
                ),
            ),
        ),
        ToolEntry(
            name = "search_files",
            group = ToolRegistry.DOMAIN_FILES,
            displayNameRes = R.string.ai_tool_search_files_name,
            descriptionRes = R.string.ai_tool_search_files_desc,
            risk = ToolRisk.READ_ONLY,
            statusText = "正在搜索文件…",
            schema = toolSchema(
                name = "search_files",
                description = "按**文件名**关键词搜索（不搜正文）App 项目工程目录与系统下载目录，返回匹配的文件路径。" +
                    "用户说「刚才那个文件叫什么」「找一下 index.ink」时用本工具，比逐个目录列更快。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "keyword" to mapOf("type" to "string", "description" to "文件名关键词，如 index、app.json、总结"),
                        "scope" to mapOf(
                            "type" to "string",
                            "enum" to listOf("all", "project", "downloads"),
                            "description" to "搜索范围，默认 all（两处都搜）",
                        ),
                    ),
                    "required" to listOf("keyword"),
                ),
            ),
        ),
        ToolEntry(
            name = "edit_text_file",
            group = ToolRegistry.DOMAIN_FILES,
            displayNameRes = R.string.ai_tool_edit_text_file_name,
            descriptionRes = R.string.ai_tool_edit_text_file_desc,
            risk = ToolRisk.LOCAL_SIDE_EFFECT,
            sideEffect = true,
            statusText = "正在修改文件…",
            schema = toolSchema(
                name = "edit_text_file",
                description = "修改**项目工程目录**里已有文本文件的内容（该目录是 App 私有镜像，也是后续安装 AIUI 项目读取的源）。" +
                    "mode=\"append\" 追加到文件末尾（文件必须已存在）；mode=\"overwrite\" 整文件替换。" +
                    "⚠️ 只改一小段也必须先用 read_text_file 读到全文，改完把**完整内容**用 overwrite 写回——" +
                    "本工具没有「替换某一行」能力，不要凭记忆只写改动片段，否则会丢掉其余内容。" +
                    "改完若要同步到手机下载目录，需用户重新执行「保存代码文件」或安装 AIUI 项目。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "project" to mapOf("type" to "string", "description" to "项目名，如 aiui-demo"),
                        "path" to mapOf("type" to "string", "description" to "文件相对路径，如 pages/index/index.ink"),
                        "mode" to mapOf(
                            "type" to "string",
                            "enum" to listOf("append", "overwrite"),
                            "description" to "append=追加到末尾（文件必须已存在）；overwrite=整文件替换（默认 append）",
                        ),
                        "content" to mapOf("type" to "string", "description" to "要写入的内容（overwrite 时为完整新内容）"),
                    ),
                    "required" to listOf("project", "path", "content"),
                ),
            ),
        ),
        ToolEntry(
            name = "delete_file",
            group = ToolRegistry.DOMAIN_FILES,
            displayNameRes = R.string.ai_tool_delete_file_name,
            descriptionRes = R.string.ai_tool_delete_file_desc,
            risk = ToolRisk.EXTERNAL_SIDE_EFFECT,
            sideEffect = true,
            statusText = "正在删除文件…",
            summarize = { args ->
                val proj = args.optString("project").trim()
                val path = args.optString("path").trim()
                val scope = args.optString("scope").trim()
                when {
                    path.isBlank() && proj.isNotEmpty() -> "删除整个项目「$proj」（工程镜像 + 下载目录副本）"
                    scope.equals("downloads", ignoreCase = true) -> "从下载目录删除「$path」"
                    proj.isNotEmpty() -> "删除项目「$proj」里的「$path」"
                    else -> "删除文件「$path」"
                }
            },
            schema = toolSchema(
                name = "delete_file",
                description = "删除文件或目录。scope=\"project\" 删项目工程目录里的文件/目录——**path 留空并传 project = 删除整个项目**" +
                    "（会同时删掉下载目录里的公开副本）；scope=\"downloads\" 从系统下载目录删文件。" +
                    "⚠️ 删除不可恢复。用户只说「清理一下」「不要了」这类含糊表达时，**先用 list_files 确认清单**，" +
                    "并明确告知将删除哪些文件，得到确认后再调用；不要自行扩大删除范围。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "scope" to mapOf(
                            "type" to "string",
                            "enum" to listOf("project", "downloads"),
                            "description" to "工作区，默认 project",
                        ),
                        "project" to mapOf("type" to "string", "description" to "项目名。project 作用域下 path 留空 = 删除整个项目"),
                        "path" to mapOf("type" to "string", "description" to "要删除的相对路径；留空（配合 project）= 删除整个项目"),
                    ),
                    "required" to emptyList<String>(),
                ),
            ),
        ),
        ToolEntry(
            name = "move_file",
            group = ToolRegistry.DOMAIN_FILES,
            displayNameRes = R.string.ai_tool_move_file_name,
            descriptionRes = R.string.ai_tool_move_file_desc,
            risk = ToolRisk.LOCAL_SIDE_EFFECT,
            sideEffect = true,
            statusText = "正在移动文件…",
            schema = toolSchema(
                name = "move_file",
                description = "在**项目工程目录内**重命名或移动文件/目录（from → to，都是相对项目根的路径）。" +
                    "目标已存在时会拒绝（不覆盖）。跨项目、跨到下载目录不支持——需要那种操作请说明理由，" +
                    "改用「读取文件」取出内容后「保存代码文件」写到目标位置。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "project" to mapOf("type" to "string", "description" to "项目名"),
                        "from" to mapOf("type" to "string", "description" to "源相对路径，如 pages/index/index.ink"),
                        "to" to mapOf("type" to "string", "description" to "目标相对路径，如 pages/home/index.ink"),
                    ),
                    "required" to listOf("project", "from", "to"),
                ),
            ),
        ),
    )

    override fun execute(context: Context, name: String, args: JSONObject): String {
        return when (name) {
            "save_summary_txt" -> WebTools.saveSummary(
                context,
                title = args.optString("title"),
                content = args.optString("content"),
            )

            ToolRegistry.TOOL_CODE_FILE -> WebTools.writeProjectFile(
                context,
                project = args.optString("project"),
                filePath = args.optString("file"),
                content = args.optString("content"),
            )

            ToolRegistry.TOOL_READ_CODE_FILE -> WebTools.readProjectFile(
                context,
                project = args.optString("project"),
                filePath = args.optString("file"),
            )

            // ── 文件工作区（作用域与禁区见 FileWorkspace 的类注释）──
            "list_files" -> FileWorkspace.listFiles(
                context,
                scope = args.optString("scope"),
                project = args.optString("project"),
                path = args.optString("path"),
            )

            "read_text_file" -> FileWorkspace.readTextFile(
                context,
                scope = args.optString("scope"),
                project = args.optString("project"),
                path = args.optString("path"),
            )

            "search_files" -> FileWorkspace.searchFiles(
                context,
                keyword = args.optString("keyword"),
                scope = args.optString("scope"),
            )

            "edit_text_file" -> FileWorkspace.editTextFile(
                context,
                project = args.optString("project"),
                path = args.optString("path"),
                mode = args.optString("mode"),
                content = args.optString("content"),
            )

            "delete_file" -> FileWorkspace.deleteEntry(
                context,
                scope = args.optString("scope"),
                project = args.optString("project"),
                path = args.optString("path"),
            )

            "move_file" -> FileWorkspace.moveEntry(
                context,
                project = args.optString("project"),
                from = args.optString("from"),
                to = args.optString("to"),
            )

        else -> throw IllegalArgumentException("未知工具: $name")
        }
    }
}
