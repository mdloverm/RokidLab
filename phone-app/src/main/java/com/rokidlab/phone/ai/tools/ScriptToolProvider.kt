package com.rokidlab.phone.ai.tools

import android.content.Context
import android.util.Base64
import android.util.Log
import com.rokidlab.phone.R
import com.rokidlab.phone.ai.LabMediaScan
import com.rokidlab.phone.ai.ScriptStore
import com.rokidlab.phone.ai.ToolContentTrust
import com.rokidlab.phone.ai.ToolRegistry
import com.rokidlab.phone.ai.ToolConfirmPolicy
import com.rokidlab.phone.ai.ToolRisk
import com.rokidlab.phone.platform.ProotInstaller
import com.rokidlab.phone.platform.ProotShell
import org.json.JSONObject

/**
 * ScriptToolProvider —— 脚本库：把跑通的做法存下来、下次直接跑。
 *
 * ## 为什么要有这一层（对标大厂 Agent 的"自我改进"）
 * [com.rokidlab.phone.ai.SkillRegistry] 解决的是"**步骤说明书**要不要写下来"，
 * 而说明书里那句 `awk -F',' '...'` 每次仍要模型**重新拼一遍** —— 引号一错就白跑一轮。
 * 脚本库把"试通的命令序列"本身固化成制品：存一次、按名字跑，**消除的是重复拼写这一类随机失败**。
 *
 * ## 域与风险档
 *  - 归 [ToolRegistry.DOMAIN_SHELL]：脚本就是在这个 proot 容器里跑的，与 `run_shell` 同一执行面、
 *    同一前提（要先装执行环境）。顺带继承了 shell 域的两条现成边界：**AIUI 页面拿不到**
 *    （第三方 `.aix` 制品不该有任意命令执行）、**未装环境时如实拒绝而不是假装执行**。
 *  - `save` / `run` 是 [ToolRisk.LOCAL_SIDE_EFFECT]（容器可丢弃、不提权，同 `run_shell` 的判据）；
 *    `run` 明确 `sideEffect = true`：脚本可能 `rm`/`>>`，不幂等 ⇒ 瞬时失败**不重试**，由模型决定是否重来。
 *  - `list` 是 [ToolRisk.READ_ONLY]，只读文件、无副作用。
 *  - `delete` 是 [ToolRisk.EXTERNAL_SIDE_EFFECT]（**删除不可恢复**，与 `delete_file` 同级）：
 *    它跟 `save` 不是同一类事 —— 存错可以再覆盖，删掉只能重存。档位不一致的名单
 *    迟早会被问"凭什么这个删得那个删不得"。
 *
 * ## 为什么不给 `run_script` 传参
 * 参数化会把 API 变成"脚本 + argv 拼装"两件事，而模型对拼装参数的可靠性远低于直接写一段脚本。
 * 需要输入时让它读 `/mnt/lab` 里的文件（那本来就是与手机交换数据的口子），或另存一个脚本。
 */
internal object ScriptToolProvider : ToolProvider {

    private const val TAG = "ScriptProvider"

    const val TOOL_SAVE = "save_script"
    const val TOOL_LIST = "list_scripts"
    const val TOOL_RUN = "run_script"

    /**
     * 删除一个脚本。
     *
     * 为什么必须有：脚本是自动积累的（一次成功的探索就长一条），[ScriptStore.MAX_SCRIPTS]
     * 只能靠"淘汰最旧"兜底 —— 存错一个（名字写错、内容是废弃试验）时**没有任何清理手段**，
     * 只能覆盖同名；于是脏条目会一直占着库容，还会出现在 `list_scripts` 里干扰模型选型。
     *
     * 风险档与 `delete_file` **同级**（[ToolRisk.EXTERNAL_SIDE_EFFECT] + 不可重试）：
     * 判据是"删除不可恢复"，而不是"删的东西值不值钱" —— 不一致的档位迟早会被问
     * "凭什么这个删得那个删不得"。
     */
    const val TOOL_DELETE = "delete_script"

    override val toolNames = setOf(TOOL_SAVE, TOOL_LIST, TOOL_RUN, TOOL_DELETE)

    /** 默认超时（同 run_shell）：日常脚本秒级返回 */
    private const val DEFAULT_TIMEOUT_SEC = 120

    /** 超时上限（同 run_shell） */
    private const val MAX_TIMEOUT_SEC = 600

    override fun tools(): List<ToolEntry> = listOf(
        ToolEntry(
            name = TOOL_SAVE,
            group = ToolRegistry.DOMAIN_SHELL,
            displayNameRes = R.string.ai_tool_save_script_name,
            descriptionRes = R.string.ai_tool_save_script_desc,
            risk = ToolRisk.LOCAL_SIDE_EFFECT,
            sideEffect = false,
            statusText = "正在保存脚本…",
            schema = toolSchema(
                name = TOOL_SAVE,
                description = "把一个**已经验证跑通**的命令序列存成脚本，方便以后直接用名字重复执行（不用再重拼一遍命令）。" +
                    "典型时机：你用 run_shell 试了几次才成功的处理流程、用户说「以后每次这样做」「存成脚本」，或你发现某个固定流程要反复执行。" +
                    "存之前**先确认它真的能跑通**（把跑通的那条命令原样放进来，不要存没验证过的），并且给一个一眼能认出的名字与一句话说明——" +
                    "说明是以后判断「该不该用这个脚本」的唯一依据。⚠️ 同名会**覆盖**旧脚本；正文里若要输出文件，" +
                    "写到 /mnt/lab 下用户才拿得到。纯查询式的网页/设备操作请用各自的专用工具，不要绕道存脚本。" +
                    "（脚本跑在本机的 Linux 容器里，需要用户先在「设置 → 本机执行环境」装好环境，用 python 的还要先 apt 装 python3。）",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "name" to mapOf(
                            "type" to "string",
                            "description" to "脚本名（之后用 run_script 按它执行）。只能含中英文、数字、下划线、短横线，如 整理下载目录",
                        ),
                        "description" to mapOf(
                            "type" to "string",
                            "description" to "一句话说明这脚本做什么、什么时候用（会展示在清单里），如 把 /mnt/lab 里的 csv 合成一个表",
                        ),
                        "language" to mapOf(
                            "type" to "string",
                            "enum" to listOf(ScriptStore.LANG_BASH, ScriptStore.LANG_PYTHON),
                            "description" to "脚本语言：bash（默认，最常见）或 python（需容器里已装 python3）",
                        ),
                        "content" to mapOf(
                            "type" to "string",
                            "description" to "脚本正文（原样执行，不要加 ``` 代码块标记，也不要写\"#!/bin/bash\"以外的解释性文字）",
                        ),
                    ),
                    "required" to listOf("name", "content"),
                ),
            ),
        ),
        ToolEntry(
            name = TOOL_LIST,
            group = ToolRegistry.DOMAIN_SHELL,
            displayNameRes = R.string.ai_tool_list_scripts_name,
            descriptionRes = R.string.ai_tool_list_scripts_desc,
            risk = ToolRisk.READ_ONLY,
            statusText = "正在查看脚本库…",
            schema = toolSchema(
                name = TOOL_LIST,
                description = "列出本机脚本库里已保存的脚本（名字、语言、说明）。" +
                    "想知道「有没有现成的可以用」或用户提到「上次那个脚本」时先看这里，别凭印象猜名字 —— " +
                    "调 run_script 时名字必须与这里列出的完全一致。没有任何脚本时如实说没有。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf<String, Any>(),
                ),
            ),
        ),
        ToolEntry(
            name = TOOL_RUN,
            group = ToolRegistry.DOMAIN_SHELL,
            displayNameRes = R.string.ai_tool_run_script_name,
            descriptionRes = R.string.ai_tool_run_script_desc,
            risk = ToolRisk.LOCAL_SIDE_EFFECT,
            sideEffect = true,
            // 脚本正文可能是我们自己写的、也可能夹带外部内容（curl 结果等），取保守档统一隔离
            contentTrust = ToolContentTrust.UNTRUSTED_EXTERNAL,
            statusText = "正在运行脚本…",
            schema = toolSchema(
                name = TOOL_RUN,
                description = "按名字运行脚本库里已保存的脚本，返回退出码、标准输出与标准错误。" +
                    "用户说「跑一下那个脚本」「按上次那样处理」时用它（不知道名字就先 list_scripts）。" +
                    "名字必须与 list_scripts 里的完全一致，否则会如实告诉你找不到 —— 此时不要自己编一个名字乱试。" +
                    "若结果里说脚本执行失败，先看退出码与标准错误：失败原因通常是脚本里的路径或前置条件变了，" +
                    "这时可以改好脚本再 save_script 覆盖同名脚本（而不是反复原样重试）。" +
                    "⚠️ 与 run_shell 同一个环境：没装执行环境时脚本跑不了，要如实告知并指引用户去「设置 → 本机执行环境」安装。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "name" to mapOf(
                            "type" to "string",
                            "description" to "要运行的脚本名（与 list_scripts 列出的完全一致）",
                        ),
                        "timeoutSec" to mapOf(
                            "type" to "integer",
                            "description" to "超时秒数，默认 $DEFAULT_TIMEOUT_SEC，上限 $MAX_TIMEOUT_SEC",
                            "minimum" to 5,
                            "maximum" to MAX_TIMEOUT_SEC,
                        ),
                    ),
                    "required" to listOf("name"),
                ),
            ),
        ),
        ToolEntry(
            name = TOOL_DELETE,
            group = ToolRegistry.DOMAIN_SHELL,
            displayNameRes = R.string.ai_tool_delete_script_name,
            descriptionRes = R.string.ai_tool_delete_script_desc,
            // 与 delete_file 同级：删除不可恢复，必须有"问用户"这一环。
            risk = ToolRisk.EXTERNAL_SIDE_EFFECT,
            // 问不到用户时照做（PROCEED）：删的是**用户自己的脚本草稿**（可重新 save_script 写回来），
            // 不像短信那样越出本机边界。按 BLOCK 会让"本机模式下清个废弃脚本"直接失败。
            confirmPolicy = ToolConfirmPolicy.PROCEED,
            sideEffect = true,
            statusText = "正在删除脚本…",
            summarize = { args ->
                val n = args.optString("name").trim()
                if (n.isEmpty()) "从脚本库删除脚本" else "从脚本库删除脚本「$n」（不可恢复）"
            },
            schema = toolSchema(
                name = TOOL_DELETE,
                description = "从脚本库里删掉一个脚本，不可恢复。" +
                    "用在脚本已经被废弃、写错了要清掉，或用户明确说「把那个脚本删了」时。" +
                    "⚠️ 名字必须与 list_scripts 列出的完全一致（不确定就先 list_scripts，不要凭印象猜名字）；" +
                    "用户只是说「这次别用」「先不要它」时**不要删**（那只是这一次不用），" +
                    "要删就先说清将删哪个脚本并得到确认。删掉之后若要重来，用 save_script 重新存。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "name" to mapOf(
                            "type" to "string",
                            "description" to "要删除的脚本名（与 list_scripts 列出的完全一致）",
                        ),
                    ),
                    "required" to listOf("name"),
                ),
            ),
        ),
    )

    override fun execute(context: Context, name: String, args: JSONObject): String {
        val result = when (name) {
            TOOL_SAVE -> save(context, args)
            TOOL_LIST -> list(context)
            TOOL_RUN -> run(context, args)
            TOOL_DELETE -> ScriptStore.delete(context, args.optString("name"))
            else -> throw IllegalArgumentException("未知工具: $name")
        }
        // 脚本在同一个容器里跑，同样可能往 /mnt/lab 写东西 —— 补扫一次让媒体库跟上
        // （与 ShellToolProvider 同一理由：内核路径直写不进 MediaStore）。save/list/delete 不碰共享盘。
        if (name == TOOL_RUN) LabMediaScan.scanLabOutputs(context)
        return result
    }

    private fun save(context: Context, args: JSONObject): String = ScriptStore.save(
        context = context,
        name = args.optString("name"),
        description = args.optString("description"),
        language = args.optString("language").ifBlank { ScriptStore.LANG_BASH },
        content = args.optString("content"),
    )

    private fun list(context: Context): String {
        val scripts = ScriptStore.list(context)
        if (scripts.isEmpty()) {
            return "脚本库是空的（还没有保存过脚本）。用 save_script 把跑通的做法存下来，之后就能按名字直接跑。"
        }
        return buildString {
            append("已保存 ${scripts.size} 个脚本：\n")
            scripts.forEach { s ->
                append("· ").append(s.name)
                append("（").append(s.language).append("）")
                if (s.description.isNotBlank()) append("：").append(s.description)
                append('\n')
            }
            append("要运行请用 run_script（name 必须与上面完全一致）；要清掉废弃的用 delete_script。")
        }
    }

    private fun run(context: Context, args: JSONObject): String {
        val name = args.optString("name").trim()
        if (name.isEmpty()) return "name 为空，没有执行任何脚本。请传 list_scripts 里列出的脚本名。"

        val script = ScriptStore.find(context, name)
            ?: return "脚本库里没有叫「$name」的脚本，没有执行任何东西。" +
                "先用 list_scripts 看有哪些脚本，再用**列出来的名字**重试；不要凭空编名字反复试。"

        // 与 run_shell 同一前提：没装 rootfs 就如实拒绝，绝不假装跑过（28.5 MB 的下载只能由用户决定）
        if (!ProotInstaller.isInstalled(context)) {
            return "本机执行环境还没安装（缺少 Ubuntu 容器），脚本「$name」没有执行。" +
                "请如实告诉用户：需要到「设置 → 本机执行环境」点一下安装，装好之后就能跑这个脚本了。"
        }

        val timeout = args.optInt("timeoutSec", DEFAULT_TIMEOUT_SEC).coerceIn(5, MAX_TIMEOUT_SEC).toLong()
        val code = toShellCode(script)
        val result = runCatching {
            ProotShell.runCommand(context, code, timeoutSec = timeout)
        }.getOrElse { e ->
            Log.w(TAG, "run_script 抛异常：${e.javaClass.simpleName}: ${e.message}")
            return "运行脚本时出错：${e.javaClass.simpleName}: ${e.message}"
        }
        return "脚本「$name」（${script.language}）执行结果：\n" +
            ShellToolProvider.format(result) + ShellToolProvider.shareHint(context)
    }

    /**
     * 脚本正文 → 交给容器执行的一段 shell。
     *
     * bash 直接执行；**python 走 base64 管道**（`echo <b64> | base64 -d | python3 -`）：
     * 直接 `python3 - <<'EOF' … EOF` 会在正文里出现同名 heredoc 终止符时提前截断，
     * 而 base64 字母表里没有引号/换行，正文无论含什么都不会破坏外层命令（与代码卡片同一套做法）。
     */
    private fun toShellCode(script: ScriptStore.Script): String {
        if (script.language != ScriptStore.LANG_PYTHON) return script.content
        val b64 = Base64.encodeToString(script.content.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        return "echo '$b64' | base64 -d | python3 -"
    }
}
