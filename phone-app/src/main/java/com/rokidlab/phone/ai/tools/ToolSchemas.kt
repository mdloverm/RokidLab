package com.rokidlab.phone.ai.tools

import com.rokidlab.phone.ai.ToolRegistry
import com.rokidlab.phone.ai.ToolRegistry.ToolMeta
import org.json.JSONObject

/**
 * 工具 Schema 构建器（Phase 4 二轮：从 ToolRegistry.buildSchema 逐字迁出；
 * 注意：函数名不能叫 buildSchema —— 会被 ToolRegistry 同名成员遮蔽而自递归（曾导致栈溢出闪退）
 * 33 个工具的参数 schema 保持不变，经扩展接收者访问 ToolRegistry 的工具名常量）。
 */
internal fun ToolRegistry.buildToolSchema(meta: ToolMeta): JSONObject {
    return when (meta.name) {
        "search_knowledge_base" -> toolSchema(
            name = meta.name,
            description = "在用户的本地知识库中检索资料并返回相关内容。当用户询问已导入文档（说明书、资料、笔记等）中的内容时调用，例如“键盘怎么用”、“说明书里怎么说的”。",
            parameters = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "query" to mapOf("type" to "string", "description" to "检索关键词，用最核心的 2~4 个词"),
                    "topK" to mapOf("type" to "integer", "description" to "返回的资料块数量，默认 3", "minimum" to 1, "maximum" to 5),
                ),
                "required" to listOf("query"),
            ),
        )

        "get_current_time" -> toolSchema(
            name = meta.name,
            description = "获取当前的准确时间（日期、星期与 24 小时制时刻）。凡用户问「现在几点」「今天几号」「星期几」等时间问题时必须调用本工具，严禁自行推算或编造时间。",
            parameters = mapOf(
                "type" to "object",
                "properties" to mapOf<String, Any>(),
            ),
        )

        "get_glasses_battery" -> toolSchema(
            name = meta.name,
            description = "查询 Rokid 眼镜的当前电量百分比与充电状态（是否在充电/已接电源）。当用户问「眼镜还有多少电」「眼镜要充电吗」等电量问题时调用。",
            parameters = mapOf(
                "type" to "object",
                "properties" to mapOf<String, Any>(),
            ),
        )

        "get_glasses_storage" -> toolSchema(
            name = meta.name,
            description = "查询 Rokid 眼镜的存储空间占用情况（已用/剩余容量）。当用户问「眼镜存储还剩多少」「内存够不够」等存储问题时调用。",
            parameters = mapOf(
                "type" to "object",
                "properties" to mapOf<String, Any>(),
            ),
        )

        "list_glasses_apps" -> toolSchema(
            name = meta.name,
            description = "列出 Rokid 眼镜上安装的应用。当用户询问眼镜装了哪些应用、有没有某个应用时调用。",
            parameters = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "includeSystem" to mapOf("type" to "boolean", "description" to "是否包含系统应用，默认 false"),
                ),
            ),
        )

        "launch_glasses_app" -> toolSchema(
            name = meta.name,
            description = "打开眼镜上安装的应用。当用户说“打开某应用”“启动某应用”时调用。传入用户口中的应用名称（如“小智”“Via”“小游戏”），不需要包名。",
            parameters = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "appName" to mapOf("type" to "string", "description" to "用户想要打开的应用名称，原样转述用户的话，如“小智”“浏览器”“B站”"),
                ),
                "required" to listOf("appName"),
            ),
        )

        "set_timer" -> toolSchema(
            name = meta.name,
            description = "创建定时提醒或定时任务，到点后眼镜语音播报（可同时打开应用）。当用户说“X分钟后提醒我”“X点叫我”“X点打开某应用”时调用。绝对时间需转换为 24 小时制 HH:mm。",
            parameters = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "time" to mapOf("type" to "string", "description" to "触发时间（24 小时制 HH:mm，如 17:00）。“X分钟后”转换为当前时间加 X 分钟后的时间"),
                    "content" to mapOf("type" to "string", "description" to "提醒内容，到点后语音播报，如“该喝水了”"),
                    "action" to mapOf("type" to "string", "enum" to listOf("notify", "launch"), "description" to "动作类型：notify=仅语音提醒（默认），launch=到点打开应用并提醒"),
                    "appName" to mapOf("type" to "string", "description" to "action=launch 时要打开的应用名称（如“小智”）"),
                    "repeatDaily" to mapOf("type" to "boolean", "description" to "是否每天重复，默认 false"),
                ),
                "required" to listOf("time", "content"),
            ),
        )

        "play_song" -> toolSchema(
            name = meta.name,
            description = "播放用户点名的歌曲。当用户说“播放某某歌”“来一首某歌”“放首某某的歌”时调用，联网搜索并直接播放该歌曲。",
            parameters = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "songName" to mapOf("type" to "string", "description" to "歌曲名称，如“晴天”“海阔天空”"),
                    "artist" to mapOf("type" to "string", "description" to "歌手名（可选），用于更精确地找到歌曲，如“周杰伦”"),
                ),
                "required" to listOf("songName"),
            ),
        )

        "stop_music" -> toolSchema(
            name = meta.name,
            description = "停止当前正在播放的音乐。当用户说“停止播放”“别放了”“停一下”“不听了”时调用。",
            parameters = mapOf(
                "type" to "object",
                "properties" to mapOf<String, Any>(),
            ),
        )

        "show_lyrics" -> toolSchema(
            name = meta.name,
            description = "在 Rokid 眼镜上显示当前播放歌曲的歌词：会主动打开眼镜上的音乐页并逐行实时刷新歌词。当用户说“显示歌词”“打开歌词”“看歌词”“我要看歌词”时调用，仅在音乐正在播放时有效。",
            parameters = mapOf(
                "type" to "object",
                "properties" to mapOf<String, Any>(),
            ),
        )

        "get_now_playing" -> toolSchema(
            name = meta.name,
            description = "读取当前播放歌曲的完整信息，返回 JSON 文本：歌名 title、歌手 artist、专辑 album、时长 durationMs、当前进度 positionMs、当前歌词行号 lineIndex、封面图地址 cover、逐行歌词 lyrics（每行含 timeMs 与 text）。AIUI 播放器页面用它取封面与歌词来渲染；语音场景下用户问“现在放的是什么歌”时也可调用。没有正在播放的音乐时返回 playing=false。",
            parameters = mapOf(
                "type" to "object",
                "properties" to mapOf<String, Any>(),
            ),
        )

        "show_image" -> toolSchema(
            name = meta.name,
            description = "显示一张图片：默认渲染在手机端对话气泡里；若眼镜已连接，会同时同步到眼镜端悬浮显示（约 12 秒后自动消失）。当用户说“显示图片”“显示封面”“给我看张图”“把刚才那张图发出来”时调用。若用户给了图片链接就传 imageUrl；若用户只说“显示封面”或想看在播歌曲的封面，可以不传 imageUrl，会自动显示当前播放歌曲的封面。",
            parameters = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "imageUrl" to mapOf("type" to "string", "description" to "图片的可访问直链（http/https）。用户没给链接时可省略，仅在播放歌曲时会退回显示当前歌曲封面。"),
                    "caption" to mapOf("type" to "string", "description" to "图片下方的说明文字（可选），如“这是一只小狗”。"),
                ),
            ),
        )

        "get_glasses_device_info" -> toolSchema(
            name = meta.name,
            description = "查询 Rokid 眼镜的设备/系统信息，包括型号、厂商、Android 系统版本、SDK 版本、序列号。当用户询问眼镜的“系统信息”“设备信息”“是什么型号”“什么版本”“固件版本”时调用。",
            parameters = mapOf(
                "type" to "object",
                "properties" to mapOf<String, Any>(),
            ),
        )

        "search_web" -> toolSchema(
            name = meta.name,
            description = "联网搜索网页信息并返回候选列表（标题+链接+摘要）。当用户要求搜索某主题的最新内容、资讯、攻略、评测等，或要求“搜一下/查一下/搜索”时先调用本工具（例如“搜一下 Rokid 眼镜的评测”）。拿到结果后，若需要深入了解详情，再对选中的链接调用 fetch_webpage 读取正文。",
            parameters = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "query" to mapOf("type" to "string", "description" to "搜索关键词，用能代表主题的 2~6 个词，如“Rokid 眼镜 评测”"),
                ),
                "required" to listOf("query"),
            ),
        )

        "fetch_webpage" -> toolSchema(
            name = meta.name,
            description = "读取一个具体网页链接的正文内容并转为纯文本（含网页标题）。配合 search_web 使用：当用户想了解某网页的详细内容、或需要总结某个搜索结果页面时，传入该网页完整链接调用本工具。返回的正文供你阅读后进行总结。",
            parameters = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "url" to mapOf("type" to "string", "description" to "要读取的网页完整链接，须以 http:// 或 https:// 开头，如 https://example.com/article"),
                ),
                "required" to listOf("url"),
            ),
        )

        "save_summary_txt" -> toolSchema(
            name = meta.name,
            description = "将总结好的内容保存为 txt 文件：写入手机系统下载目录，并同步导入本地知识库（之后可用 search_knowledge_base 检索）。当用户明确要求“把总结存成 txt/文件”“保存总结”“整理成文档/笔记”时，在完成内容总结后调用。内容应完整、条理清晰，尽量包含核心要点。",
            parameters = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "title" to mapOf("type" to "string", "description" to "文档标题（会作为文件名与知识库条目名），简洁概括主题，如“Rokid 眼镜评测总结”"),
                    "content" to mapOf("type" to "string", "description" to "要保存的完整总结正文，包含标题下的具体要点内容，供落盘与知识库检索"),
                ),
                "required" to listOf("title", "content"),
            ),
        )

        TOOL_CODE_FILE -> toolSchema(
            name = meta.name,
            description = "把生成的一段代码保存为项目文件，写入手机“下载/项目名/”目录。当用户要求“写代码/生成页面或应用/创建项目文件”等需要产出代码、脚本、配置或页面文件时使用；一次调用只写一个文件，一个项目有多个文件时按文件逐个调用（每次用相同的项目名）。必须通过本工具把代码落盘，不要把整段代码直接当作回复内容发给用户；回复只简短告知项目名与文件数量。若目标是“AIUI 智能体应用（.aix）”，至少需要 app.json（页面清单）与 pages/index/index.ink（首页），可再加 VERSION/AGENTS.md/app.js 与更多 pages/*/index.ink；app.json 的页面路径与文件名一致。每个 .ink 页面必须遵循 AIUI SFC 四块结构并按顺序书写：<script def>（页面级 JSON 配置，如导航栏标题）→ <script setup>（export default 逻辑/data/生命周期）→ <page>（WXML 模板，根标签必须是 <page>，禁止用 <template>）→ <style>（样式）。写成 Vue 风格（<template>/<script>）会被拒绝保存，眼镜上也无法渲染。若目标是修改一个已生成的项目，请先调用 read_code_file 读取该文件当前的真实内容，在它基础上改动后覆盖写回同一路径，不要凭印象整文件重编。",
            parameters = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "project" to mapOf("type" to "string", "description" to "项目文件夹名（下载目录下的一级文件夹名，同一项目的多个文件必须相同），如 aiui-demo"),
                    "file" to mapOf("type" to "string", "description" to "该文件的相对路径（含文件名，可带子目录），如 app.json、pages/index/index.ink"),
                    "content" to mapOf("type" to "string", "description" to "该文件的完整代码内容"),
                ),
                "required" to listOf("project", "file", "content"),
            ),
        )

        TOOL_READ_CODE_FILE -> toolSchema(
            name = meta.name,
            description = "读取对话中通过写文件工具生成的项目源码当前内容（读取手机本地的项目镜像，不改动任何文件）。当用户说“修改/改一下/调整/优化/重做/对之前的 XXX 不满意”而对象是之前生成过的代码项目或 AIUI 智能体应用时必须先调用本工具：不确定要改哪个文件时可只传 project（返回该项目的文件清单），确定后带 file 读取对应文件的完整内容，再基于读到的真实源码用“保存代码文件”工具(save_code_file)覆盖写回同一 project 的同一路径，最后用“安装 AIUI 项目”工具(install_aiui_project)重新安装到眼镜。禁止凭印象或记忆整文件重编——必须先读现网源码再动手，只重写受影响的文件。",
            parameters = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "project" to mapOf("type" to "string", "description" to "项目文件夹名（与写文件时的 project 参数完全一致），如 aiui-demo"),
                    "file" to mapOf("type" to "string", "description" to "可选：要读取文件的相对路径（含文件名，可带子目录），如 app.json、pages/index/index.ink；不传则返回整个项目的文件清单"),
                ),
                "required" to listOf("project"),
            ),
        )

        "open_aiui_app" -> toolSchema(
            name = meta.name,
            description = "在 Rokid 眼镜上打开一个 AIUI 智能体应用（.aix 卡片应用，如“我是黑客”“音乐播放”）。当用户说“打开/启动/演示/预览 XXX（智能体名）”“打开我是黑客”“打开智能体”“打开某 AI 应用/小游戏”“用/通过/拿 XXX 智能体去做某事（如“用音乐播放智能体播放七里香”）”等、且该名字命中智能体应用列表时调用；普通应用（小智/浏览器等）请用 launch_glasses_app。本机生成/上传的应用（本地有 .aix）会自动推送到 RokidLink 自托管宿主（官方 ink web 宿主），支持用 Lab 手机蓝牙手柄直接操控页面；内置官方智能体走 AgentStore 打开。注意：不要仅仅口头回复“已经打开/已经在播放”，必须实际调用本工具才能把用户请求交给智能体执行。",
            parameters = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "appName" to mapOf("type" to "string", "description" to "用户想要打开或使用的智能体应用名称，原样转述，如“我是黑客”“音乐播放”"),
                    "params" to mapOf("type" to "string", "description" to "传给该应用的启动参数，JSON 对象字符串（如 {\"songName\":\"七里香\"}）。只要用户要求“用/通过/拿某个智能体去做某事”并给出了具体对象/参数，就必须填写并调用本工具；只是“打开某应用”时可不传。传参用页面期望的参数名（如 songName / keyword / city），不确定就留空，让页面用自己的默认值处理。"),
                ),
                "required" to listOf("appName"),
            ),
        )

        "install_aiui_project" -> toolSchema(
            name = meta.name,
            description = "把对话中已通过写文件工具生成的 AIUI 项目打包成 .aix 并推送到 Rokid 眼镜（登记后可语音打开/演示）。当用户说“把这个项目/应用装到眼镜上”“安装我做的 AI 应用”“打包这个 AIUI 项目”时调用；前提是先调用写文件工具生成该项目（须含 app.json 与 pages/index/index.ink）。本工具只负责打包推送，不自动打开（需用户确认后再用 open_aiui_app 演示，避免打断眼镜当前画面）。对已生成的项目做修改后重新安装到眼镜时，同样用本工具（同一 project 覆盖更新，源码有变化眼镜端会自动重新解压加载）。",
            parameters = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "project" to mapOf("type" to "string", "description" to "要安装的项目名（与写文件时的 project 参数完全一致），如 aiui-demo"),
                    "appName" to mapOf("type" to "string", "description" to "安装后在眼镜上显示的应用名（用户之后会说“打开这个名字”），简洁可念，如“记单词助手”"),
                ),
                "required" to listOf("project", "appName"),
            ),
        )

        "stop_aiui_app" -> toolSchema(
            name = meta.name,
            description = "关闭 Rokid 眼镜上正在显示的 AIUI 智能体应用（.aix 卡片界面），回到主界面。当用户说“退出/关闭这个 AI 应用”“退出 AIUI”“关掉刚打开的那个应用”“返回”等、画面是 AIUI 智能体卡片时调用；普通应用（小智/浏览器等）请用其他关闭手段而非本工具。无需知道应用名也能关闭当前正在显示的那个。",
            parameters = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "appName" to mapOf("type" to "string", "description" to "要关闭的智能体应用名称（可选）。若用户没提名字，说明是刚打开/生成的那个，可不传本参数"),
                ),
            ),
        )

        "list_my_aiui_apps" -> toolSchema(
            name = meta.name,
            description = "列出用户在本机生成/上传过的 AIUI 智能体应用记录（名称、项目名、最近更新时间、是否已送到眼镜、来源）。当用户问“我生成过哪些 AI 应用/智能体”“我之前做的那个 AIUI”“我有哪些 AI 应用”，或需要再次修改/打开/安装历史 AIUI 项目时先调用本工具拿到项目名（project）；随后要修改代码时先调 read_code_file 读取现网源码，再用 save_code_file 覆盖写回、install_aiui_project 重装。",
            parameters = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "keyword" to mapOf("type" to "string", "description" to "可选：按应用名/项目名过滤的关键字，不传则列出全部"),
                ),
            ),
        )

        "list_timers" -> toolSchema(
            name = meta.name,
            description = "列出当前已设置的全部定时任务（名称、触发时间、是否每天重复、运行状态）。当用户问「我有哪些提醒」「都有什么定时任务」「几点有提醒」时调用；用户想取消某个提醒前也先调用本工具拿到任务名称或编号。",
            parameters = mapOf(
                "type" to "object",
                "properties" to mapOf<String, Any>(),
            ),
        )

        "cancel_timer" -> toolSchema(
            name = meta.name,
            description = "取消/删除一个已存在的定时任务。当用户说「取消刚才的提醒」「删掉明天的闹钟」「不要那个定时了」时调用；不知道要取消哪个时先用 list_timers 查看再取消。",
            parameters = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "timerName" to mapOf("type" to "string", "description" to "要取消的定时任务名称或提醒内容（用户提到的那个，如「喝水提醒」）"),
                    "all" to mapOf("type" to "boolean", "description" to "用户要求全部取消时传 true，默认 false"),
                ),
                "required" to listOf("timerName"),
            ),
        )

        "get_weather" -> toolSchema(
            name = meta.name,
            description = "查询指定城市的天气（实况温度/体感/湿度/风力 + 未来两天预报）。凡用户问「今天天气怎么样」「明天会下雨吗」「杭州冷不冷」等天气问题时必须调用本工具，严禁自行编造天气。",
            parameters = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "city" to mapOf("type" to "string", "description" to "城市名称，如「杭州」「上海」；用户没说城市时问一句或按记忆中的常居城市"),
                    "date" to mapOf("type" to "string", "enum" to listOf("today", "tomorrow", "all"), "description" to "today=只报今天（默认），tomorrow=只报明天，all=实况+未来两天"),
                ),
                "required" to listOf("city"),
            ),
        )

        "calculate" -> toolSchema(
            name = meta.name,
            description = "精确计算算术表达式（大数乘除、百分比、幂、括号均可）。凡涉及精确数值计算（「378×56 等于多少」「(128+64)×12」「2 的 20 次方」「打 85 折多少钱」）时必须调用本工具，不要心算。",
            parameters = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "expression" to mapOf("type" to "string", "description" to "标准算术表达式，支持 + - * / % ^ 与括号，如 \"378*56\"、\"(128+64)*12\"、\"599*0.85\""),
                ),
                "required" to listOf("expression"),
            ),
        )

        "search_contacts" -> toolSchema(
            name = meta.name,
            description = "在手机通讯录中按姓名查找联系人及其电话号码。当用户问「XX 的电话是多少」「XX 手机号」「我存的 XX 的号码」时调用。",
            parameters = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "name" to mapOf("type" to "string", "description" to "联系人姓名，如「张三」"),
                ),
                "required" to listOf("name"),
            ),
        )

        "call_phone" -> toolSchema(
            name = meta.name,
            description = "用手机**直接拨出**电话（已授电话权限即刻拨号，无需用户二次确认；未授权时退化为打开拨号盘）。参数可以是联系人姓名（自动查通讯录）或手机号。当用户说「给张三打电话」「拨打 138xxxx」「打电话给妈妈」时调用。",
            parameters = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "contact" to mapOf("type" to "string", "description" to "联系人姓名（如「张三」）或完整手机号（如「13800138000」）"),
                ),
                "required" to listOf("contact"),
            ),
        )

        "set_phone_alarm" -> toolSchema(
            name = meta.name,
            description = "在手机上设置闹钟或倒计时（区别于眼镜端的定时提醒，本工具响铃在手机上）。当用户说「明早 7 点叫我起床」「30 分钟后手机闹我」「设个手机闹钟」时调用。「X分钟后提醒」若未强调手机，优先用眼镜定时任务（set_timer）。",
            parameters = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "message" to mapOf("type" to "string", "description" to "闹钟标签/提醒内容，如「起床」「该出发了」"),
                    "hour" to mapOf("type" to "integer", "description" to "24 小时制小时（绝对时间闹钟必填，0-23），如 7"),
                    "minute" to mapOf("type" to "integer", "description" to "分钟（绝对时间闹钟必填，0-59），如 30"),
                    "minutesFromNow" to mapOf("type" to "integer", "description" to "相对分钟数（倒计时用，如「30分钟后」传 30；与 hour/minute 二选一）"),
                ),
            ),
        )

        "open_phone_app" -> toolSchema(
            name = meta.name,
            description = "打开手机上安装的应用（微信/支付宝/相机等）。当用户说「打开手机上的微信」「帮我打开支付宝」且对象是手机应用时调用；打开眼镜上的应用请用 launch_glasses_app。",
            parameters = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "appName" to mapOf("type" to "string", "description" to "要打开的手机应用名称，原样转述，如「微信」「支付宝」「设置」"),
                ),
                "required" to listOf("appName"),
            ),
        )

        "get_phone_status" -> toolSchema(
            name = meta.name,
            description = "查询手机当前状态：电量百分比、是否在充电、媒体音量、屏幕亮度。当用户问「手机还有多少电」「在充电吗」「音量多大」「手机什么状态」时调用。",
            parameters = mapOf(
                "type" to "object",
                "properties" to mapOf<String, Any>(),
            ),
        )

        "get_location" -> toolSchema(
            name = meta.name,
            description = "获取手机当前所在位置（城市 / 街区 / 地标）。当用户问「我在哪」「我在什么地方」「这是哪里」「我附近有什么」等需要知道用户当前位置的问题时，必须调用本工具，严禁自行编造位置。",
            parameters = mapOf(
                "type" to "object",
                "properties" to mapOf<String, Any>(),
            ),
        )

        "set_phone_volume" -> toolSchema(
            name = meta.name,
            description = "调节手机媒体音量（百分比）。当用户说「手机音量调到 50」「音量小一点/大一点」（对象是手机时）调用；「大一点/小一点」换算为比当前值高/低 20% 左右的具体数字。",
            parameters = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "volume" to mapOf("type" to "integer", "description" to "目标音量百分比 0~100，如 50 表示一半"),
                ),
                "required" to listOf("volume"),
            ),
        )

        "query_calendar" -> toolSchema(
            name = meta.name,
            description = "查询手机日历中某天的日程安排。当用户问「我明天有什么安排」「今天有什么日程」「9月10号我要干什么」时调用。",
            parameters = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "date" to mapOf("type" to "string", "description" to "要查询的日期：today/tomorrow/今天/明天，或 YYYY-MM-DD；不传默认今天"),
                ),
            ),
        )

        "add_calendar_event" -> toolSchema(
            name = meta.name,
            description = "在手机日历上创建一个日程。当用户说「帮我记一下明天下午 3 点开会」「加个日程：周五 19 点健身」时调用。开始时间必须是具体的 HH:mm（用户只说「下午」按 15:00 左右估算并告知用户）。",
            parameters = mapOf(
                "type" to "object",
                "properties" to mapOf(
                    "title" to mapOf("type" to "string", "description" to "日程标题，如「团队会议」"),
                    "date" to mapOf("type" to "string", "description" to "日期：today/tomorrow/今天/明天，或 YYYY-MM-DD；不传默认今天（已过时刻自动顺延到明天）"),
                    "startTime" to mapOf("type" to "string", "description" to "开始时间，24 小时制 HH:mm，如 14:30"),
                    "durationMinutes" to mapOf("type" to "integer", "description" to "时长（分钟），默认 60"),
                    "note" to mapOf("type" to "string", "description" to "备注（可选），如地点、参会人"),
                ),
                "required" to listOf("title", "startTime"),
            ),
        )

        else -> toolSchema(
            name = meta.name,
            description = "执行 ${meta.name} 工具。",
            parameters = mapOf(
                "type" to "object",
                "properties" to mapOf<String, Any>(),
            ),
        )
    }
}

private fun toolSchema(name: String, description: String, parameters: Map<String, Any>): JSONObject {
    return JSONObject().apply {
        put("type", "function")
        put("function", JSONObject().apply {
            put("name", name)
            put("description", description)
            put("parameters", JSONObject(parameters))
        })
    }
}
