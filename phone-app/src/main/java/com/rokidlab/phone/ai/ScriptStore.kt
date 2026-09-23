package com.rokidlab.phone.ai

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 脚本库：把跑通的做法**固化成可复用脚本**（跨会话留存）。
 *
 * ## 它补的是哪条断链
 * [SkillRegistry] 存的是**说明书**（Markdown，给人/模型看的步骤），命令本身每轮都要重新拼；
 * 而大厂 Agent 的"自我改进"里有一条很实在的东西：**把试通的命令序列存下来，下次直接跑**
 * —— 既省轮次，也省掉"同一串引号再拼错一次"的随机失败。
 * 于是能力闭环变成：说明书（skill）+ 可复用脚本（本对象）。
 *
 * ## 存储与生命周期
 *  - 位置：`filesDir/scripts.json`（**不进 git、不进下载目录**：脚本内容是我们自己生成的产物，
 *    不是用户文档；要交给用户的成品请让脚本写到 `/mnt/lab`）。
 *  - 上限 [MAX_SCRIPTS] 条，超出按**最旧**淘汰 —— 脚本是自动积累的，必须自带容量边界，
 *    否则一次成功的探索就长一条，库会无限膨胀（与长期记忆同一个思路）。
 *  - 同名**覆盖**（save 即 upsert）：脚本的典型生命周期是"改了再跑"，而不是"每次起个新名字"。
 *  - 存错了可以**删**（[delete]，对应 `delete_script` 工具）：容量上限只是自动兜底，
 *    不该是唯一的清理手段 —— 脏条目会一直占库容、并在 `list_scripts` 里干扰模型选型。
 *
 * ## 为什么脚本存宿主侧、执行在容器里
 * 内容存成宿主侧 JSON（读写不依赖容器是否装好），执行时交给 [com.rokidlab.phone.platform.ProotShell]
 * 送进容器 —— 没装执行环境时 `list_scripts` 照样能看到有什么脚本（只是跑不了）。
 */
object ScriptStore {

    private const val TAG = "ScriptStore"
    private const val FILE_NAME = "scripts.json"

    /** 脚本条数上限（超出淘汰最旧） */
    private const val MAX_SCRIPTS = 30

    /** 单条脚本正文上限（字符）：够放一个几十行的数据处理脚本，防止模型把整个项目塞进来 */
    private const val MAX_CONTENT_CHARS = 20000

    private const val MAX_NAME_CHARS = 40
    private const val MAX_DESC_CHARS = 120

    /** bash 脚本 */
    const val LANG_BASH = "bash"

    /** python3 脚本（要跑得先 apt 装 python3，与 run_shell 同一个环境） */
    const val LANG_PYTHON = "python"

    /**
     * 一条已保存的脚本。
     *
     * @param name 脚本名（用户/模型引用的唯一键，重名即覆盖）
     * @param description 一句话说明"这脚本干什么、什么时候用"（注入模型的就是它）
     * @param language [LANG_BASH] / [LANG_PYTHON]
     * @param content 脚本正文
     * @param createdAt 最近一次保存时间（淘汰最旧、列表排序用）
     */
    data class Script(
        val name: String,
        val description: String,
        val language: String,
        val content: String,
        val createdAt: Long,
    )

    private val lock = Any()

    private fun file(ctx: Context): File = File(ctx.applicationContext.filesDir, FILE_NAME)

    /** 全部脚本（按保存时间倒序 = 最近改过的在前） */
    fun list(context: Context): List<Script> = load(context).sortedByDescending { it.createdAt }

    /** 按名精确查找（找不到返回 null） */
    fun find(context: Context, name: String): Script? {
        val n = name.trim()
        if (n.isEmpty()) return null
        return load(context).firstOrNull { it.name == n }
    }

    /**
     * 保存（同名覆盖）。
     *
     * @return 面向模型的结果文本。**校验失败也走返回值**而不是抛异常：
     *   这是模型会直接看到的唯一反馈口，让它读到"名字不合法，请改用…"才有机会改对参数。
     */
    fun save(
        context: Context,
        name: String,
        description: String,
        language: String,
        content: String,
    ): String = synchronized(lock) {
        val n = name.trim()
        val invalid = invalidNameReason(n)
        if (invalid != null) {
            return@synchronized "保存失败：$invalid"
        }
        val lang = normalizeLanguage(language)
        val body = content.replace("\r\n", "\n").trim()
        if (body.isEmpty()) {
            return@synchronized "保存失败：content 为空，没有任何内容可保存。"
        }
        if (body.length > MAX_CONTENT_CHARS) {
            return@synchronized "保存失败：脚本太长（${body.length} 字符，上限 $MAX_CONTENT_CHARS）。" +
                "请拆成多个小脚本，或把大段逻辑改成读取 /mnt/lab 里的文件。"
        }
        val items = load(context).toMutableList()
        val idx = items.indexOfFirst { it.name == n }
        val overwritten = idx >= 0
        val script = Script(
            name = n,
            description = description.trim().take(MAX_DESC_CHARS),
            language = lang,
            content = body,
            createdAt = System.currentTimeMillis(),
        )
        if (overwritten) items[idx] = script else items.add(script)
        // 超出上限淘汰最旧
        while (items.size > MAX_SCRIPTS) {
            val oldest = items.minByOrNull { it.createdAt } ?: break
            items.remove(oldest)
        }
        val ok = write(context, items)
        if (!ok) {
            return@synchronized "保存失败：写入本地文件出错（存储空间不足？）。脚本没有保存成功。"
        }
        val action = if (overwritten) "已更新脚本" else "已保存脚本"
        "$action「$n」（$lang，${body.length} 字符，共 ${items.size} 个脚本）"
    }

    /** 名字合法性：返回 null = 合法，否则返回给模型看的原因 */
    private fun invalidNameReason(name: String): String? = when {
        name.isEmpty() -> "脚本名不能为空，给个一眼能认出的名字（如 每日天气播报）。"
        name.length > MAX_NAME_CHARS -> "脚本名太长（上限 $MAX_NAME_CHARS 个字符）。"
        name.any { !isNameChar(it) } ->
            "脚本名只能包含中英文、数字、下划线、短横线（不含空格与标点），请换一个名字。"
        else -> null
    }

    private fun isNameChar(c: Char): Boolean =
        c.isLetterOrDigit() || c == '_' || c == '-'

    /** 语言归一化：认几个常见写法，其余一律当 bash（bash 是本环境的默认也是最稳的） */
    private fun normalizeLanguage(raw: String): String {
        val v = raw.trim().lowercase()
        return if (v.startsWith("py")) LANG_PYTHON else LANG_BASH
    }

    // ──────────────────────────────────────────────
    //  读写（JSON 文件；读失败一律当空库，不让存储问题炸掉对话）
    // ──────────────────────────────────────────────

    private fun load(context: Context): List<Script> {
        val f = file(context)
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val n = o.optString("name").trim()
                if (n.isEmpty()) return@mapNotNull null
                Script(
                    name = n,
                    description = o.optString("description"),
                    language = normalizeLanguage(o.optString("language")),
                    content = o.optString("content"),
                    createdAt = o.optLong("createdAt"),
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "load failed (treated as empty): ${e.message}")
            emptyList()
        }
    }

    private fun write(context: Context, items: List<Script>): Boolean {
        val arr = JSONArray()
        items.forEach { s ->
            arr.put(JSONObject().apply {
                put("name", s.name)
                put("description", s.description)
                put("language", s.language)
                put("content", s.content)
                put("createdAt", s.createdAt)
            })
        }
        return try {
            val f = file(context)
            // 先写临时文件再**原子替换**：中途被杀不会留下半截 JSON 把整个脚本库读成空。
            val tmp = File(f.parentFile, "$FILE_NAME.tmp")
            tmp.writeText(arr.toString())
            replaceAtomically(tmp, f)
        } catch (e: Exception) {
            Log.w(TAG, "write failed: ${e.message}")
            false
        }
    }

    /**
     * 用 `tmp` 原子替换 `dst`，返回是否成功。
     *
     * ⚠️ **不要**先 `dst.delete()` 再 rename：POSIX `rename` 本身就是"覆盖式原子操作"，
     * 而 delete 与 rename 之间被杀会让**整个脚本库消失**（临时文件 + 改名的全部意义就是防这个）。
     * 改造前就是这么写的，注释还宣称"防中途被杀"—— 实际是把自己防的那件事做成了。
     *
     * 主路径走 `Files.move(ATOMIC_MOVE)`；个别 ROM 的 FUSE 上不支持时才退回 `renameTo`
     * （同目录内 rename 在 POSIX 上同样是覆盖式原子的），且**检查返回值** ——
     * 改造前 `tmp.renameTo(f)` 的结果被丢弃，写失败会被当成保存成功。
     */
    private fun replaceAtomically(tmp: File, dst: File): Boolean = runCatching {
        java.nio.file.Files.move(
            tmp.toPath(),
            dst.toPath(),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            java.nio.file.StandardCopyOption.ATOMIC_MOVE,
        )
        true
    }.getOrElse { e ->
        Log.w(TAG, "atomic move unsupported, falling back to renameTo(): ${e.message}")
        tmp.renameTo(dst)
    }

    /**
     * 删除一个脚本。
     *
     * 为什么需要它：脚本是模型/用户自动积累的，[MAX_SCRIPTS] 只能靠"淘汰最旧"兜底 ——
     * 存错一个（名字写错、内容是废弃试验）时用户**无法清理**，只能覆盖同名，
     * 于是脏条目会一直占着库容并出现在 `list_scripts` 里干扰模型选型。
     *
     * @return 面向模型的结果文本（失败原因走返回值而不是抛异常，与 [save] 同一约定）
     */
    fun delete(context: Context, name: String): String = synchronized(lock) {
        val n = name.trim()
        if (n.isEmpty()) return@synchronized "删除失败：脚本名不能为空，请传 list_scripts 里列出的名字。"
        val items = load(context).toMutableList()
        val before = items.size
        items.removeAll { it.name == n }
        if (items.size == before) {
            return@synchronized "脚本库里没有叫「$n」的脚本，没有删除任何东西。" +
                "先用 list_scripts 看有哪些脚本，再用**列出来的名字**重试。"
        }
        if (!write(context, items)) {
            return@synchronized "删除失败：写入本地文件出错。脚本「$n」仍然保留。"
        }
        "已删除脚本「$n」（还剩 ${items.size} 个脚本），不可恢复。"
    }
}
