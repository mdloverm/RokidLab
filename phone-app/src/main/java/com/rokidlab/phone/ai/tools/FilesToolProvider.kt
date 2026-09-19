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
 * FilesToolProvider —— 文件产出域（总结 txt / AIUI 代码落盘与回读）。
 * Phase 4 从 `ToolRegistry.execute` 迁出的该域工具执行分支（逐字搬运，行为不变）。
 */
internal object FilesToolProvider : ToolProvider {
    private const val TAG = "FilesToolProvider"

    override val toolNames = setOf(
        "save_summary_txt",
        "save_code_file",
        "read_code_file",
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
                description = "把生成的一段代码保存为项目文件，写入手机“下载/项目名/”目录。当用户要求“写代码/生成页面或应用/创建项目文件”等需要产出代码、脚本、配置或页面文件时使用；一次调用只写一个文件，一个项目有多个文件时按文件逐个调用（每次用相同的项目名）。必须通过本工具把代码落盘，不要把整段代码直接当作回复内容发给用户；回复只简短告知项目名与文件数量。若目标是“AIUI 智能体应用（.aix）”，至少需要 app.json（页面清单）与 pages/index/index.ink（首页），可再加 VERSION/AGENTS.md/app.js 与更多 pages/*/index.ink；app.json 的页面路径与文件名一致。每个 .ink 页面必须遵循 AIUI SFC 四块结构并按顺序书写：<script def>（页面级 JSON 配置，如导航栏标题）→ <script setup>（export default 逻辑/data/生命周期）→ <page>（WXML 模板，根标签必须是 <page>，禁止用 <template>）→ <style>（样式）。写成 Vue 风格（<template>/<script>）会被拒绝保存，眼镜上也无法渲染。若页面需要调用手机端能力（放歌、查天气、搜网页、设提醒等），只能走 Lab 工具口：在页面逻辑里写 await globalThis.Lab.callTool('工具名', {参数})，并用 await globalThis.Lab.listTools() 查可用工具名；工具名必须与清单逐字一致，禁止自造名字；必须 try/catch 并显示 loading 态，一次只调一个工具，失败不要写自动重试循环。若目标是修改一个已生成的项目，请先调用 read_code_file 读取该文件当前的真实内容，在它基础上改动后覆盖写回同一路径，不要凭印象整文件重编。",
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

        else -> throw IllegalArgumentException("未知工具: $name")
        }
    }
}
