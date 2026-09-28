package com.rokidlab.phone.ai

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.ZipInputStream

/**
 * 用户自定义技能注册表（AI Skills）。
 *
 * 理念：技能 = 「说明书」（SKILL.md：frontmatter 的 name/description + Markdown 步骤正文）。
 * 用户通过三种入口（手动填写 / 导入文件 / 填写 URL 下载）把技能写入 App 私有目录：
 *
 *   {filesDir}/skills/<name>/SKILL.md
 *
 * 运行机制（progressive disclosure，对齐 Anthropic Agent Skills / 大厂通用做法）：
 *   第 1 层：对话开始时，把「已启用技能」的 name + 一句话 description 清单注入 system prompt
 *            （占 token 极少，装几十个技能也不撑爆上下文）；
 *   第 2 层：模型判断用户请求命中某技能描述时，调用内置伪工具 load_skill(name)，
 *            execute 返回该 SKILL.md 全文，模型再据此编排调用 ToolRegistry 受管工具完成。
 *   「一次请求命中多个技能」= 模型在同一 turn 并发调用多个 load_skill（触发层自动叠加）。
 *
 * 安全模型：
 *   - 技能只是文本，无法注册新工具、无法执行任意动作；模型最终仍只能调用 ToolRegistry
 *     白名单工具（受管：ADB/音乐/定时/知识库/联网）。静态引用检查仅提示。
 *   - 技能名经白名单正则校验（目录名安全），安装落盘前 parse + 校验；
 *   - zip 解压带 zipslip / 解压炸弹防护；只信任 frontmatter 里的 name，不信路径。
 *
 * 总开关与单技能开关均持久化 SharedPreferences。
 */
object SkillRegistry {
    private const val TAG = "SkillRegistry"
    private const val PREFS = "ai_skill_prefs"
    private const val KEY_ENABLED = "skills_enabled"
    private const val KEY_PREFIX = "skill_enabled_"
    /** 内置技能被用户在线同步覆盖后置位，seed 启动覆盖时跳过该技能（防止官方更新被回滚） */
    private const val KEY_SEED_SUPPRESS = "seed_suppress_"

    /** load_skill 伪工具名（OpenAI function calling）：小技能返回全文，大技能返回章节目录 */
    const val TOOL_NAME = "load_skill"

    /** load_skill_section 伪工具名：大技能按章取正文（分片模式的第 2.5 层） */
    const val TOOL_NAME_SECTION = "load_skill_section"

    /**
     * 技能**管理**伪工具名（对话里直接装/看/删技能）。
     *
     * 为什么要有这三个：技能安装原先只有设置页三个入口（手动填写 / 导入文件 / 填 URL），
     * 用户在对话里说「你把这个技能装上」「我发个链接你装一下」时，模型**只能让用户自己去设置页点**——
     * 而 `SkillRegistry` 的安装管线早就写好了，差的只是"把它暴露给模型"这一层。
     *
     * 风险档见 [com.rokidlab.phone.ai.approval.PseudoTools]：写本地技能目录，属本机副作用，不走眼镜确认。
     */
    const val TOOL_INSTALL = "install_skill"
    const val TOOL_LIST = "list_skills"
    const val TOOL_DELETE = "delete_skill"

    /** 内置官方大技能名（aiui-dev = 官方 SKILL.md + 参考手册 + 本地 lab-runtime.md） */
    const val BUNDLED_SKILL = "aiui-dev"

    /**
     * aiui-dev 的本地 description（中文命中词）。assets 内置版与官方在线同步写盘版统一
     * 使用本值：官方仓库更新正文时，description 不会被官方英文描述冲掉，保证命中稳定。
     * 与 assets/skills/aiui-dev/SKILL.md 的 frontmatter 保持一致。
     */
    const val LOCAL_AIUI_DESCRIPTION = "当用户要求在 Rokid 眼镜上生成/开发/修改 AIUI 智能体应用（.aix：会话卡、全屏交互页、答题器、小游戏等），需要 jsui/wx 组件与 API 参考、调试 AIUI 应用，或按官方设计规范对齐视觉时使用。命中词：AIUI、智能体应用、做个卡片、做个页面、眼镜小游戏、.aix。"

    /** 技能数量上限（防止列表无节制膨胀冲击第 1 层清单 token） */
    private const val MAX_SKILLS = 50

    /** zip 技能包大小上限（1MB：允许含多个参考手册级大技能的包，防超大包拖垮导入） */
    private const val MAX_ZIP_BYTES = 1024L * 1024

    /** 解压单文件大小上限（320KB ≈ 10 万中文字符的 SKILL.md，防 zip bomb） */
    private const val MAX_ENTRY_BYTES = 320L * 1024

    /**
     * 技能目录里允许落盘的**非 .md 附件**扩展名（小写）。
     *
     * 为什么要放行脚本：Anthropic 生态的技能包常带 `scripts/`（python/shell），只收 .md
     * 意味着"装得进、跑不了"。脚本只是文本，最终仍经 `run_shell`（受管工具）执行 ——
     * 技能本身依旧不能注册工具、不能绕过审批闸门，安全模型不变。
     */
    private val ASSET_EXTENSIONS = setOf("py", "sh", "js", "mjs", "json", "txt", "yaml", "yml")

    /** 文件是否为技能可携带的附件（.md 参考手册 + 脚本/数据类附件） */
    fun isSkillAssetFile(fileName: String): Boolean {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return ext == "md" || ext in ASSET_EXTENSIONS
    }

    /** 单技能安装结果 */
    data class SkillMeta(
        val name: String,
        val description: String,
        val enabled: Boolean,
    )

    /** 安装/导入结果 */
    data class InstallResult(
        val skillName: String?,
        val success: Boolean,
        val message: String,
    )

    // ═══════════════════════════════════════════════
    // 总开关
    // ═══════════════════════════════════════════════

    /** 技能功能总开关（关闭时：不注入清单、不注册 load_skill 工具） */
    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        Log.i(TAG, "set total enabled=$enabled")
    }

    /** 单技能开关（默认开启） */
    fun isSkillEnabled(context: Context, name: String): Boolean =
        prefs(context).getBoolean(KEY_PREFIX + name, true)

    fun setSkillEnabled(context: Context, name: String, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_PREFIX + name, enabled).apply()
        Log.i(TAG, "set skill [$name] enabled=$enabled")
    }

    /** 内置技能是否被用户在线同步覆盖（置位后 seed 不再以 assets 覆盖该技能） */
    fun isSeedSuppressed(context: Context, name: String): Boolean =
        prefs(context).getBoolean(KEY_SEED_SUPPRESS + name, false)

    fun setSeedSuppressed(context: Context, name: String, suppressed: Boolean) {
        prefs(context).edit().putBoolean(KEY_SEED_SUPPRESS + name, suppressed).apply()
        Log.i(TAG, "seed suppress [$name] = $suppressed")
    }

    /**
     * Lab 本地配套技能文件（不在官方 aiui-dev 清单内，见 SkillFetcher.OFFICIAL_AIUI_DEV_FILES）。
     * 这些文件由 Lab 自己维护（宿主运行须知、画布/布局规范等），必须始终随 App 包内版本同步，
     * 不受 [setSeedSuppressed] 抑制。新增本地配套文件时在此登记。
     */
    private val LOCAL_COMPANION_SKILL_FILES = setOf("lab-runtime.md")

    private fun isLocalCompanionSkillFile(file: String): Boolean =
        LOCAL_COMPANION_SKILL_FILES.any { it.equals(file, ignoreCase = true) }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ═══════════════════════════════════════════════
    // 存储
    // ═══════════════════════════════════════════════

    fun skillsDir(context: Context): File =
        File(context.filesDir, "skills").apply { mkdirs() }

    fun skillFile(context: Context, name: String): File =
        File(skillsDir(context), name).resolve("SKILL.md")

    /**
     * 启动时安装/同步内置技能：assets 约定 skills/<name>/ 目录（SKILL.md + 可选参考文档
     * lab-runtime.md / components.md / apis-*.md 等，官方 aiui-dev 即多文件技能）。
     * 覆盖策略 = 内容比对：本地文件与 assets 文本不一致才覆盖（内置技能以 App 内版本为准，
     * 保证语义/键位标准等更新随包生效）；一致则跳过。用户关闭技能的开关存在
     * SharedPreferences，不受此影响。filesDir 中 assets 没有的文件不删除（防误删用户数据）。
     */
    fun seedBundledSkills(context: Context) {
        val dirs = runCatching { context.assets.list("skills")?.toList().orEmpty() }
            .getOrDefault(emptyList())
        for (name in dirs) {
            // 用户已在线同步覆盖的内置技能：**官方文件**跳过，防止官方更新被 App 内置旧版回滚。
            // ⚠️ 但抑制必须只作用于官方清单内的文件：lab-runtime.md 是 Lab 本地配套（不在官方
            //    OFFICIAL_AIUI_DEV_FILES 内），若随整目录一起跳过，本宿主运行须知将永远无法
            //    随 App 升级生效（历史缺陷：用户点过一次「同步官方技能」后，画布/布局规范
            //    之类的修订再也发不出去）。故此处按文件粒度过滤，而非整目录 continue。
            val suppressed = isSeedSuppressed(context, name)
            if (suppressed) {
                Log.i(TAG, "seedBundledSkills: $name suppressed by official sync (local companion files still seeded)")
            }
            val files = runCatching { context.assets.list("skills/$name")?.toList().orEmpty() }
                .getOrDefault(emptyList())
            for (file in files.filter { it.endsWith(".md", ignoreCase = true) }) {
                if (suppressed && !isLocalCompanionSkillFile(file)) continue
                try {
                    val text = context.assets
                        .open("skills/$name/$file")
                        .reader(Charsets.UTF_8)
                        .use { it.readText() }
                    if (file.equals("SKILL.md", ignoreCase = true) && SkillMarkdown.parse(text) == null) {
                        Log.w(TAG, "seedBundledSkills: invalid SKILL.md for $name")
                        continue
                    }
                    val target = File(skillsDir(context), name).resolve(file)
                    if (target.isFile && target.readText(Charsets.UTF_8) == text) continue
                    target.parentFile?.mkdirs()
                    target.writeText(text, Charsets.UTF_8)
                    Log.i(TAG, "seeded/updated bundled skill file: $name/$file")
                } catch (e: Exception) {
                    Log.w(TAG, "seedBundledSkills($name/$file) failed: ${e.message}")
                }
            }
        }
    }

    // ═══════════════════════════════════════════════
    // 扫描 / 读取
    // ═══════════════════════════════════════════════

    /** 列出全部已安装技能（含 disabled），按名称排序 */
    fun listSkills(context: Context): List<SkillMeta> {
        val dir = skillsDir(context)
        val result = mutableListOf<SkillMeta>()
        dir.listFiles()?.forEach { sub ->
            val md = File(sub, "SKILL.md")
            if (sub.isDirectory && md.isFile) {
                val doc = parseFile(md) ?: return@forEach
                result.add(SkillMeta(doc.name, doc.description, isSkillEnabled(context, doc.name)))
            }
        }
        return result.sortedBy { it.name }
    }

    /** 技能数量 */
    fun count(context: Context): Int = listSkills(context).size

    /** 读取并解析某技能（未安装返回 null） */
    fun loadSkill(context: Context, name: String): SkillMarkdown.ParsedSkill? {
        val file = skillFile(context, name)
        if (!file.isFile) return null
        return parseFile(file)
    }

    /** SKILL.md 全文（execute 回填给模型用） */
    fun loadFullText(context: Context, name: String): String? {
        val file = skillFile(context, name)
        return if (file.isFile) runCatching { file.readText(Charsets.UTF_8) }.getOrNull() else null
    }

    /**
     * load_skill / load_skill_section 的统一准入：返回 null = 可用，非 null = 拒绝原因。
     *
     * ★ 为什么要在**读取侧**再查一次开关：装配侧只把"已启用"的技能写进清单、并在总开关
     * 关闭时整个不下发技能工具 —— 但模型可以从**历史对话**里复述出一个已被用户关掉的技能名
     * （用户先在对话里用过它，之后去设置页关掉，历史仍在上下文里）。
     * 只靠"不下发"挡不住这种调用，开关会形同虚设。
     */
    private fun unavailableReason(context: Context, name: String): String? = when {
        !isEnabled(context) -> "技能功能已在设置里关闭"
        !isSkillEnabled(context, name) -> "技能「$name」已被关闭（在手机 App 的技能列表里打开后再用）"
        else -> null
    }

    /** 可加载的技能名（总开关关掉时为空表，避免把"可用的技能名"这一栏暴露成误导信息） */
    private fun loadableSkillNames(context: Context): String {
        if (!isEnabled(context)) return "（技能功能已关闭）"
        return listSkills(context).filter { it.enabled }.joinToString("、") { it.name }.ifEmpty { "（无）" }
    }

    /** 技能目录内全部可携带文件（含 SKILL.md、参考手册与脚本），带相对路径，目录缺失返回空列表 */
    fun listSkillFileNames(context: Context, name: String): List<String> {
        val root = File(skillsDir(context), name)
        if (!root.isDirectory) return emptyList()
        val out = mutableListOf<String>()
        fun walk(dir: File, prefix: String) {
            dir.listFiles()?.forEach { f ->
                if (f.isDirectory) {
                    walk(f, "$prefix${f.name}/")
                } else if (isSkillAssetFile(f.name)) {
                    out.add("$prefix${f.name}")
                }
            }
        }
        walk(root, "")
        return out.sorted()
    }

    /** 读取技能目录内指定文件全文；文件不存在/非法返回 null（相对路径，允许子目录） */
    fun readSkillFileText(context: Context, name: String, fileName: String): String? {
        val safe = safeFileName(fileName) ?: return null
        val file = File(skillsDir(context), name).resolve(safe)
        if (!file.isFile) return null
        // 防符号链接/越界：规范化后必须仍在技能目录内
        val root = File(skillsDir(context), name).canonicalFile
        if (!file.canonicalFile.path.startsWith(root.path)) return null
        return runCatching { file.readText(Charsets.UTF_8) }.getOrNull()
    }

    /**
     * 校验技能目录内文件相对路径：目录名与文件名只允许「字母/数字/点/下划线/连字符/斜杠」，
     * 扩展名必须在附件白名单内；拒绝路径穿越（..）、绝对路径与隐藏文件。
     */
    fun safeFileName(fileName: String): String? {
        val f = fileName.trim()
        if (f.isEmpty() || f.length > 128 || f.startsWith(".")) return null
        if (f.contains("..")) return null
        if (!Regex("^[A-Za-z0-9._/\\-]+$").matches(f)) return null
        return if (isSkillAssetFile(f)) f else null
    }

    private fun parseFile(file: File): SkillMarkdown.ParsedSkill? {
        val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return null
        val doc = SkillMarkdown.parse(text) ?: run {
            Log.w(TAG, "SKILL.md parse failed: ${file.absolutePath}")
            null
        }
        return doc
    }

    // ═══════════════════════════════════════════════
    // 第 1 层：注入系统清单（name + description）
    // ═══════════════════════════════════════════════

    /**
     * 生成注入 system 的技能清单文本。仅包含「已启用」技能。
     * 无启用技能时返回 null（调用方不注入，省 token）。
     *
     * 使用约定：模型命中后先 load_skill 取步骤；大技能（load_skill 只回目录）需再
     * load_skill_section 逐章取正文，避免单次回填撑爆上下文。
     */
    fun skillsContext(context: Context): String? {
        val enabled = listSkills(context).filter { it.enabled }
        if (enabled.isEmpty()) return null
        return buildString {
            append("以下是用户安装的自定义技能清单。当用户请求命中某技能的 description 场景时，")
            append("先调用 load_skill 工具加载该技能：小技能会直接返回完整步骤，")
            append("大技能会返回「章节目录」，此时再按需调用 load_skill_section 读取相关章节的正文，")
            append("然后严格按步骤调用工具执行；一次请求可组合使用多个技能。")
            append("技能不能替代下述工具，只是使用步骤说明：\n")
            enabled.forEach { s ->
                append("- ").append(s.name).append(": ").append(s.description.take(120)).append('\n')
            }
        }.trimEnd()
    }

    // ═══════════════════════════════════════════════
    // load_skill / load_skill_section 伪工具（第 2 层按需加载）
    // ═══════════════════════════════════════════════

    /** load_skill 工具 Schema（注册给 OpenAI 兼容协议 tools 参数） */
    fun schema(): JSONObject {
        return JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", TOOL_NAME)
                put("description",
                    "加载一个自定义技能的步骤说明。当用户请求命中技能清单（system 提示中列出的 " +
                        "「自定义技能清单」）中某个技能的用途时，必须先调用本工具：小技能返回完整步骤，直接按步骤执行；" +
                        "大技能返回章节目录，此时再用 load_skill_section 读取所需章节正文；" +
                        "目录型技能（如 aiui-dev，含 SKILL.md 与 components/apis 等参考文件）还会返回参考文件清单，可按需读取。")
                put("parameters", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("name", JSONObject().apply {
                            put("type", "string")
                            put("description", "要加载的技能名（取自技能清单中的 name）")
                        })
                    })
                    put("required", JSONArray(listOf("name")))
                })
            })
        }
    }

    /** load_skill_section 工具 Schema：读取大技能单个章节的完整正文（支持指定技能目录内的参考文件） */
    fun sectionSchema(): JSONObject {
        return JSONObject().apply {
            put("type", "function")
            put("function", JSONObject().apply {
                put("name", TOOL_NAME_SECTION)
                put("description",
                    "读取大技能（load_skill 返回的是章节目录而非全文）的某个章节正文。传章节序号（目录中的 " +
                        "1、2、3…）或章节标题。一次只取当前最需要的一章，避免上下文膨胀。\n" +
                        "参考文件（components.md / apis-*.md / wxss.md / lab-runtime.md 等）通过可选参数 file " +
                        "指定文件名；省略 file 时读取技能主文件 SKILL.md。")
                put("parameters", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("name", JSONObject().apply {
                            put("type", "string")
                            put("description", "技能名（取自 load_skill 返回目录开头）")
                        })
                        put("file", JSONObject().apply {
                            put("type", "string")
                            put("description", "可选，技能目录内要读取的参考文件名（如 components.md、apis-wx.md、lab-runtime.md）；缺省读取主文件 SKILL.md")
                        })
                        put("section", JSONObject().apply {
                            put("type", "string")
                            put("description", "章节序号（如 \"1\"）或章节标题（如 \"Project Structure\"），取自 load_skill 返回的目录")
                        })
                    })
                    put("required", JSONArray(listOf("name", "section")))
                })
            })
        }
    }

    /**
     * 执行 load_skill：正文 ≤ [SkillMarkdown.MAX_INLINE_CHARS] 返回全文；
     * 更大时返回「大技能说明 + 章节目录」，指引模型按需 load_skill_section。
     * 技能目录存在参考文件时（SKILL.md 之外的 .md），改为返回目录型技能 outline：
     * lab-runtime 须知（若有）+ SKILL.md 章节目录 + 参考文件清单。
     */
    fun execute(context: Context, arguments: String): String {
        val args = try { JSONObject(arguments) } catch (_: Exception) {
            return "技能参数解析失败，请传入 {\"name\":\"技能名\"}"
        }
        val name = args.optString("name").trim()
        if (name.isEmpty()) return "请提供要加载的技能名 name"
        unavailableReason(context, name)?.let { return "$it。可用的技能：${loadableSkillNames(context)}" }
        val full = loadFullText(context, name)
        if (full == null) {
            return "未找到技能「$name」，可用的技能名：" + loadableSkillNames(context)
        }
        val doc = SkillMarkdown.parse(full) ?: return "技能「$name」文件损坏（SKILL.md 解析失败）"
        if (doc.body.length <= SkillMarkdown.MAX_INLINE_CHARS) {
            return full // 小技能：保持原渐进披露行为，直接给全文
        }
        return buildLargeSkillOutline(context, name, doc)
    }

    /**
     * 大技能 outline：宿主须知（lab-runtime.md）+ SKILL.md 章节长度/目录 +
     * 参考文件清单与取章指引（单次返回有界，约几百~3K 字符）。
     */
    private fun buildLargeSkillOutline(context: Context, name: String, doc: SkillMarkdown.ParsedSkill): String {
        val index = SkillMarkdown.buildChaptersIndex(doc.body)
        if (index.isBlank()) {
            // 无 ## 结构的大技能：给开头预览 + 提示无法整片取回
            val head = doc.body.take(SkillMarkdown.MAX_INLINE_CHARS)
            return "技能「$name」正文共 ${doc.body.length} 字符，且没有 ## 章节结构，无法分片加载。" +
                "以下是前 ${head.length} 字符：\n\n$head\n\n" +
                "（如需剩余内容，请在手机 App 中拆分该技能为多个带 ## 章节的小技能）"
        }
        val refFiles = listSkillFileNames(context, name).filter { it != "SKILL.md" }
        val sb = StringBuilder()
        // 1) 目录型技能：优先展示本宿主运行须知（开发前必读），再给主指南目录与参考文件清单
        if (refFiles.isNotEmpty()) {
            val runtimeName = SkillMarkdown.RUNTIME_FILE
            if (runtimeName in refFiles) {
                val runtimeText = readSkillFileText(context, name, runtimeName)
                if (runtimeText != null) {
                    val runtimeDoc = SkillMarkdown.parse(runtimeText)
                    val runtimeBody = runtimeDoc?.body ?: runtimeText
                    // 宿主须知用**自己的**预算（比通用阈值更宽）：它是所有页面开发的公共前置，
                    // 退化成"章节目录"等于让模型先点菜才知道"本宿主没有事件循环"，很可能就不知道了。
                    if (runtimeBody.length <= SkillMarkdown.MAX_RUNTIME_INLINE_CHARS) {
                        sb.append("【$runtimeName · 本宿主运行须知（开发前必读）】\n\n")
                            .append(runtimeText.trim()).append("\n\n")
                    } else {
                        sb.append("【$runtimeName · 本宿主运行须知（开发前必读）】共 ")
                            .append(runtimeBody.length).append(" 字符，按章节目录读取（file=\"$runtimeName\"）：\n")
                            .append(SkillMarkdown.buildChaptersIndex(runtimeBody)).append("\n\n")
                    }
                }
            }
            sb.append("【主指南 SKILL.md】正文共 ${doc.body.length} 字符，已按 ## 章节分片，章节目录：\n\n")
                .append(index).append("\n\n")
            sb.append("【参考文件清单】以下文件位于技能目录中，含官方组件/API/样式权威细节；")
                .append("需要对应细节时用 load_skill_section 读取（name=\"$name\", file=\"文件名\", section=序号或标题）。\n")
            refFiles.sorted().forEach { f ->
                val size = runCatching {
                    File(skillsDir(context), name).resolve(f).length()
                }.getOrDefault(0L)
                sb.append("- ").append(f).append("（约 ").append(size / 512 + 1).append("KB）")
                val purpose = refPurpose(f)
                if (purpose != null) {
                    sb.append("：").append(purpose)
                } else if (!f.endsWith(".md", ignoreCase = true)) {
                    sb.append("：脚本/数据附件。用 load_skill_section（file=\"$f\"，section=任意值）取全文，")
                        .append("再经 run_shell 写盘后执行")
                }
                sb.append('\n')
            }
            sb.append("\n写代码任务（开发 AIUI）执行顺序：先遵守运行须知（lab-runtime 第 0/1 节），")
                .append("然后按需 load_skill_section 读取主指南章节——写 .ink 前至少读第 2 章 SFC 规范，")
                .append("涉及按键交互再读第 4 章 Events；组件/样式/API 细节分别查参考文件清单中对应文件章节。")
                .append("一次只读取与当前任务最相关的一个文件/章节。")
            return sb.toString()
        }
        // 2) 单文件大技能：原渐进披露行为
        return "技能「$name」正文共 ${doc.body.length} 字符，超过单次加载上限，已按 ## 章节分片。\n" +
            "描述：${doc.description.take(SkillMarkdown.MAX_DESCRIPTION_CHARS)}\n" +
            "以下是章节目录（每章附字数与开头预览）。请判断哪些章节与当前任务相关，" +
            "然后用 load_skill_section 工具逐个读取（name=\"$name\"，section=序号或标题）：\n\n" +
            index
    }

    /** 参考文件用途注解（load_skill outline 的参考文件清单用） */
    private fun refPurpose(fileName: String): String? = when (fileName.lowercase()) {
        "components.md" -> "官方内置组件逐参文档（结构/属性/事件/内容模型）"
        "apis.md" -> "API 参考总览"
        "apis-ai.md" -> "AI/ASR/TTS 语音相关 API"
        "apis-canvas.md" -> "画布 Canvas API"
        "apis-device.md" -> "设备/传感器 API"
        "apis-media.md" -> "媒体 API"
        "apis-web.md" -> "Web/网络 API"
        "apis-wx.md" -> "wx.* 小程序风格 API"
        "wxss.md" -> "WXSS 选择器/布局/样式支持细节"
        "design-system-green.md" -> "官方「绿色」设计系统规范"
        else -> null
    }

    /** 执行 load_skill_section：返回指定文件（默认 SKILL.md）中指定章节正文（超长截断防上下文顶满） */
    fun executeSection(context: Context, arguments: String): String {
        val args = try { JSONObject(arguments) } catch (_: Exception) {
            return "技能章节参数解析失败，请传入 {\"name\":\"技能名\",\"section\":\"序号或标题\"}"
        }
        val name = args.optString("name").trim()
        val section = args.optString("section").trim()
        val fileArg = args.optString("file").trim()
        if (name.isEmpty() || section.isEmpty()) {
            return "请提供技能名 name 与章节 section（序号或标题）"
        }
        // 与 load_skill 同一道准入：关掉的技能不该还能被"取章节"读到正文
        unavailableReason(context, name)?.let { return "$it。可用的技能：${loadableSkillNames(context)}" }
        // 取正文文本：file 参数显式指定时读技能目录内参考文件，否则读主文件 SKILL.md
        val (fileName, full) = if (fileArg.isNotEmpty()) {
            val safe = safeFileName(fileArg)
            if (safe == null) {
                return "文件名不合法（file=\"$fileArg\"），只能填技能目录内的 .md 文件名，如 components.md、apis-wx.md、lab-runtime.md"
            }
            val text = readSkillFileText(context, name, safe)
                ?: return "技能「$name」目录中没有文件「$safe」。可用文件：\n" +
                    listSkillFileNames(context, name).joinToString("\n").ifEmpty { "（无）" }
            safe to text
        } else {
            "SKILL.md" to (loadFullText(context, name)
                ?: return "未找到技能「$name」，可用的技能名：" +
                    listSkills(context).joinToString("、") { it.name }.ifEmpty { "（无）" })
        }
        // 脚本/数据类附件（非 .md）：整文件即内容，不按章节切 —— 模型拿到后经 run_shell 写盘执行
        if (!fileName.equals("SKILL.md", ignoreCase = true) && !fileName.endsWith(".md", ignoreCase = true)) {
            return if (full.length <= SkillMarkdown.MAX_SECTION_CHARS) {
                "「$name/$fileName」共 ${full.length} 字符，完整内容：\n\n${full.trim()}"
            } else {
                "「$name/$fileName」共 ${full.length} 字符，已截断为前 ${SkillMarkdown.MAX_SECTION_CHARS} 字符：" +
                    "\n\n${full.take(SkillMarkdown.MAX_SECTION_CHARS)}"
            }
        }
        // 参考文件通常无 frontmatter，整段即正文；主文件则剥离 frontmatter
        val parsed = SkillMarkdown.parse(full)
        val body = parsed?.body ?: full
        // 小内容直接给全文，不需要取章
        if (body.length <= SkillMarkdown.MAX_INLINE_CHARS) {
            return "「$name/$fileName」共 ${body.length} 字符，完整内容：\n\n${full.trim()}"
        }
        val hit = SkillMarkdown.locateSection(body, section)
        if (hit == null) {
            val index = SkillMarkdown.buildChaptersIndex(body)
            // 报错要交代清楚「序号」有两套可能：目录里的「第 N 章」位置序号，或标题自带的编号。
            // 否则模型容易盯着「第 1 章 0. 开发必读顺序」反复猜数字，白耗轮次。
            return "「$name/$fileName」中找不到章节「$section」。可用章节（section 请传「第 N 章」的序号 N，" +
                "或标题自带的编号如 0，或章节标题原文）：\n$index"
        }
        val sectionText = "## ${hit.heading}\n${hit.content}".trim()
        if (sectionText.length > SkillMarkdown.MAX_SECTION_CHARS) {
            return sectionText.take(SkillMarkdown.MAX_SECTION_CHARS) +
                "\n\n（本章共 ${sectionText.length} 字符，已截断为前 ${SkillMarkdown.MAX_SECTION_CHARS} 字符；" +
                "如需更细信息，请传更具体的章节标题）"
        }
        return sectionText
    }

    /** 技能工具执行中的进度文案（load_skill 系列为本地即时读取，无需播报进度） */
    fun statusText(name: String): String = when (name) {
        TOOL_NAME -> "正在加载技能…"
        TOOL_NAME_SECTION -> "正在读取技能章节…"
        TOOL_INSTALL -> "正在安装技能…"
        TOOL_LIST -> "正在查看技能清单…"
        TOOL_DELETE -> "正在删除技能…"
        else -> "正在执行 $name…"
    }

    // ═══════════════════════════════════════════════
    // 官方 aiui-dev 在线同步（官方仓库更新无需重新编译发版）
    // ═══════════════════════════════════════════════

    /** 官方同步结果 */
    data class SyncResult(
        val success: Boolean,
        val message: String,
    )

    /**
     * 从官方仓库（yodaos-project/AIUI）同步最新 aiui-dev 到本地技能目录（IO 线程调用）。
     * - 仅覆盖官方自有文件（SKILL.md + 参考手册）；本地配套 lab-runtime.md 不在官方清单，
     *   不会被覆盖（键码契约 / 页面规范 / 交付纪律等 Lab 经验始终保留）。
     * - SKILL.md 写盘前重建 frontmatter：正文用官方最新，description 用本地中文增强
     *   [LOCAL_AIUI_DESCRIPTION]，保证 Chat 命中不被官方英文描述削弱。
     * - 成功后置位 seed 抑制标记，启动 seed 不再以 App 内置旧版回滚。
     * - 失败文件逐个跳过并在 message 汇总；全部失败视为同步失败。
     *
     * @return 结果与汇总文案（供 UI Toast 展示）
     */
    fun syncBundledOfficial(context: Context): SyncResult {
        val fetched = SkillFetcher.fetchOfficialAiuiDevFiles()
        if (fetched.isEmpty()) {
            return SyncResult(false, "同步失败：无法连接官方源（jsdelivr / GitHub raw 均不可达），请检查网络后重试")
        }
        val dir = File(skillsDir(context), BUNDLED_SKILL)
        val failed = mutableListOf<String>()
        var ok = 0
        for ((fileName, text) in fetched) {
            try {
                var content = text
                // SKILL.md：官方正文 + 本地中文 description（命中稳定）
                if (fileName.equals("SKILL.md", ignoreCase = true)) {
                    val doc = SkillMarkdown.parse(text)
                    content = if (doc != null) {
                        SkillMarkdown.build(doc.name, LOCAL_AIUI_DESCRIPTION, doc.body)
                    } else {
                        Log.w(TAG, "sync official SKILL.md parse failed, 原样写盘")
                        text
                    }
                }
                dir.mkdirs()
                dir.resolve(fileName).writeText(content, Charsets.UTF_8)
                ok++
            } catch (e: Exception) {
                Log.w(TAG, "sync official write $fileName failed: ${e.message}")
                failed.add(fileName)
            }
        }
        // 有文件落地才置位抑制标记（避免「全失败还冻结 seed」）
        if (ok > 0) {
            setSeedSuppressed(context, BUNDLED_SKILL, true)
        }
        val msg = if (failed.isEmpty()) {
            "官方 aiui-dev 已更新（$ok 个文件）。本地 lab-runtime.md 与配套 skill 保持不变"
        } else {
            "官方 aiui-dev 更新 $ok 个文件，失败：${failed.joinToString("、")}。本地配套保持不变"
        }
        return SyncResult(failed.isEmpty(), msg)
    }

    // ═══════════════════════════════════════════════
    // 安装管线（手动填写 / 导入 / URL 下载共用收尾）
    // ═══════════════════════════════════════════════

    /**
     * 从 SKILL.md 全文安装单个技能（三入口的公共收尾）。
     * 校验 frontmatter -> 写 {skillsDir}/<name>/SKILL.md（同名覆盖更新）。
     * 返回成功/失败原因。
     */
    fun installFromMarkdown(context: Context, markdown: String): InstallResult {
        val doc = SkillMarkdown.parse(markdown)
            ?: return InstallResult(null, false, "SKILL.md 格式不正确：缺少 frontmatter 或 name/description 字段")
        val nameErr = SkillMarkdown.validateName(doc.name)
        if (nameErr != null) return InstallResult(null, false, nameErr)
        if (doc.description.isBlank()) {
            return InstallResult(doc.name, false, "技能「${doc.name}」缺少 description（一句话描述用途）")
        }
        if (doc.body.isBlank()) {
            return InstallResult(doc.name, false, "技能「${doc.name}」正文为空，请填写操作步骤")
        }
        if (doc.body.length > SkillMarkdown.MAX_BODY_CHARS) {
            return InstallResult(
                doc.name, false,
                "技能「${doc.name}」正文过长（${doc.body.length} 字符 > ${SkillMarkdown.MAX_BODY_CHARS}），请精简步骤",
            )
        }
        if (count(context) >= MAX_SKILLS && !skillFile(context, doc.name).exists()) {
            return InstallResult(doc.name, false, "技能数量已达上限（$MAX_SKILLS 个），请先删除部分技能")
        }

        val file = skillFile(context, doc.name)
        runCatching {
            file.parentFile?.mkdirs()
            // 用规范化后的内容落盘（trim），保证与 parse 结果一致
            file.writeText(SkillMarkdown.build(doc.name, doc.description, doc.body), Charsets.UTF_8)
        }.getOrElse { e ->
            Log.e(TAG, "write skill ${doc.name} failed", e)
            return InstallResult(doc.name, false, "写入技能文件失败：${e.message}")
        }
        // 引用白名单静态提示（不阻断）：把技能正文里出现但不在受管工具集合的动作名记入日志，
        // 供用户排查（模型引用不存在工具只会失败并如实告知）
        val known = ToolRegistry.toolList.map { it.name }.toMutableSet().apply {
            add(TOOL_NAME)
            add(TOOL_NAME_SECTION)
            add(LongTermMemoryManager.TOOL_NAME)
        }
        val unsafe = SkillMarkdown.checkUnsafeRefs(doc.body, known)
        if (unsafe.isNotEmpty()) {
            Log.w(TAG, "skill ${doc.name} 正文引用了未注册动作: $unsafe")
        }
        Log.i(TAG, "installed skill ${doc.name} (${file.length()} bytes)")
        return InstallResult(doc.name, true, "已安装技能「${doc.name}」")
    }

    /**
     * 从 zip 字节安装技能包：识别包内带合法 frontmatter 的 SKILL.md，并把**同一技能目录**
     * （SKILL.md 所在目录及其子目录）内的参考手册与脚本一并落盘 —— 保留相对路径结构。
     * - 目录结构不限（顶层 / skills/name/ / name/ 均可）；
     * - 技能名一律取 frontmatter 的 name，不信路径；
     * - 嵌套技能（更深层的 SKILL.md）各自成技能，其文件归属**最近的**祖先 SKILL.md；
     * - zipslip 防护：拒绝 ../ 与绝对路径条目；单文件与总量均限流，防 zip bomb；
     * - 扩展名白名单：.md 参考手册 + [ASSET_EXTENSIONS] 脚本/数据（后者供 run_shell 执行）。
     *
     * @return 每个安装结果（成功/失败逐条返回，供 UI 展示汇总）
     */
    fun installFromZip(context: Context, zipBytes: ByteArray): List<InstallResult> {
        if (zipBytes.size > MAX_ZIP_BYTES) {
            return listOf(InstallResult(null, false, "技能包过大（>${MAX_ZIP_BYTES / 1024}KB），仅支持纯文本技能包"))
        }
        // ① 读完包内全部可携带文件（路径 → 文本）；越限/非法条目直接丢
        val files = LinkedHashMap<String, String>()
        // 不用 ZipInputStream.use{}：其 inline lambda 内不允许 break/continue，改显式 try/finally
        val zip = ZipInputStream(ByteArrayInputStream(zipBytes))
        try {
            while (true) {
                val entry = zip.nextEntry ?: break
                val rawName = entry.name.replace('\\', '/')
                // zipslip 防护
                val unsafe = rawName.startsWith("/") ||
                    rawName.split('/').any { it == ".." } ||
                    !entry.isDirectory && !isSkillAssetFile(rawName.substringAfterLast('/'))
                if (unsafe) { zip.closeEntry(); continue }
                if (entry.isDirectory || !isSkillAssetFile(rawName.substringAfterLast('/'))) {
                    zip.closeEntry()
                    continue
                }
                if (entry.size > MAX_ENTRY_BYTES) { zip.closeEntry(); continue }
                val text = readLimited(zip, MAX_ENTRY_BYTES)
                if (text != null) files[rawName.trimStart('/')] = text
                zip.closeEntry()
            }
        } catch (e: Exception) {
            Log.e(TAG, "zip install failed", e)
            return listOf(InstallResult(null, false, "技能包解析失败（不是合法的 zip 或内容损坏）"))
        } finally {
            runCatching { zip.close() }
        }
        if (files.isEmpty()) {
            return listOf(InstallResult(null, false, "zip 内未找到可携带的技能文件（.md 或脚本类）"))
        }

        // ② 找出全部技能根（合法 SKILL.md 的所在目录），技能名取 frontmatter
        data class SkillRoot(val dir: String, val name: String, val mdText: String)
        val roots = files.filterKeys { it.substringAfterLast('/').equals("SKILL.md", ignoreCase = true) }
            .mapNotNull { (path, text) ->
                val doc = SkillMarkdown.parse(text) ?: return@mapNotNull null
                SkillRoot(path.substringBeforeLast('/', ""), doc.name, text)
            }
        if (roots.isEmpty()) {
            return listOf(InstallResult(null, false, "zip 内未找到含 name/description 的 SKILL.md 技能文件"))
        }

        // ③ 文件归属：按"最近的祖先 SKILL.md 目录"归组（根目录按长度降序匹配）
        val sortedRoots = roots.sortedByDescending { it.dir.length }
        val assetsByRoot = mutableMapOf<String, MutableList<Pair<String, String>>>() // dir → (相对路径, 文本)
        for ((path, text) in files) {
            if (path.substringAfterLast('/').equals("SKILL.md", ignoreCase = true)) continue
            val dir = path.substringBeforeLast('/', "")
            val owner = sortedRoots.firstOrNull { dir == it.dir || dir.startsWith("${it.dir}/") }
            if (owner != null) {
                // 相对技能根的路径（根为空 = 顶层平铺）
                val rel = if (owner.dir.isEmpty()) path else path.removePrefix("${owner.dir}/")
                assetsByRoot.getOrPut(owner.dir) { mutableListOf() }.add(rel to text)
            }
        }

        // ④ 逐技能安装：SKILL.md 走既有校验管线；附件按相对路径落盘
        if (count(context) + roots.size > MAX_SKILLS) {
            return listOf(InstallResult(null, false, "技能数量将达到上限（$MAX_SKILLS 个），请先删除部分技能"))
        }
        val results = mutableListOf<InstallResult>()
        for (root in roots.distinctBy { it.name }) {
            val res = installFromMarkdown(context, root.mdText)
            results.add(res)
            if (!res.success) continue
            val skillName = res.skillName ?: root.name
            var copied = 0
            var failed = 0
            for ((rel, text) in assetsByRoot[root.dir].orEmpty()) {
                // SKILL.md 已由管线落盘；附件名再过一遍安全校验（防 zip 内手写的怪路径）
                if (rel.equals("SKILL.md", ignoreCase = true)) continue
                if (safeFileName(rel) == null) { failed++; continue }
                try {
                    val target = File(skillsDir(context), skillName).resolve(rel)
                    target.parentFile?.mkdirs()
                    target.writeText(text, Charsets.UTF_8)
                    copied++
                } catch (e: Exception) {
                    Log.w(TAG, "zip asset write $skillName/$rel failed: ${e.message}")
                    failed++
                }
            }
            if (copied > 0 || failed > 0) {
                val note = buildString {
                    append(res.message)
                    if (copied > 0) append("，附带 $copied 个参考/脚本文件")
                    if (failed > 0) append("（$failed 个附件因路径不合法被跳过）")
                }
                results[results.lastIndex] = res.copy(message = note)
            }
        }
        return results
    }

    // ═══════════════════════════════════════════════
    // 技能管理伪工具（install / list / delete）
    //
    // 三个工具把设置页那套安装管线原样接到模型手上：**执行体一行新逻辑都没有**，
    // 全部复用 installFromMarkdown / installFromZip / listSkills / delete。
    // 这样"模型装的技能"与"用户手装的技能"在同一条路径上落盘、同一套校验，
    // 不会出现"对话装进来的坏了但设置页装的是好的"这种分叉。
    // ═══════════════════════════════════════════════

    /** install_skill 工具 Schema */
    fun installSchema(): JSONObject = JSONObject().apply {
        put("type", "function")
        put("function", JSONObject().apply {
            put("name", TOOL_INSTALL)
            put(
                "description",
                "安装一个自定义技能（SKILL.md 说明书）到手机上，装好后可被 load_skill 加载。" +
                    "两种给法，二选一：① url = 技能包地址（支持 .zip 直链（可含参考手册与脚本，" +
                    "脚本经 run_shell 执行）、.md/raw 单文件直链、GitHub/Gitee 仓库或目录页）；" +
                    "② markdown = SKILL.md 全文（用户直接把内容贴进对话时用）。" +
                    "用户说「把这个技能装上」「我发个链接你装一下」「记住这套流程」时调用。" +
                    "⚠️ SKILL.md 必须带 frontmatter（--- 之间的 name 与 description），" +
                    "技能名只能用中英文/数字/下划线/连字符。失败时把工具返回的原因如实告诉用户。",
            )
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("url", JSONObject().apply {
                        put("type", "string")
                        put("description", "技能包地址（与 markdown 二选一）")
                    })
                    put("markdown", JSONObject().apply {
                        put("type", "string")
                        put("description", "SKILL.md 全文，须含 frontmatter 的 name 与 description（与 url 二选一）")
                    })
                })
                put("required", JSONArray(emptyList<String>()))
            })
        })
    }

    /** list_skills 工具 Schema */
    fun listSchema(): JSONObject = JSONObject().apply {
        put("type", "function")
        put("function", JSONObject().apply {
            put("name", TOOL_LIST)
            put(
                "description",
                "列出手机上已安装的自定义技能（含各自的一句话用途与启用状态）。" +
                    "用户问「你有哪些技能」「我装过什么技能」时调用；" +
                    "准备安装新技能前也可以先看一眼，避免重名覆盖。",
            )
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject())
                put("required", JSONArray(emptyList<String>()))
            })
        })
    }

    /** delete_skill 工具 Schema */
    fun deleteSchema(): JSONObject = JSONObject().apply {
        put("type", "function")
        put("function", JSONObject().apply {
            put("name", TOOL_DELETE)
            put(
                "description",
                "删除一个已安装的自定义技能（连同它的参考文件一起删，不可恢复）。" +
                    "用户明确说「把某某技能删掉/卸掉」时调用；不确定技能名先用 list_skills 查。" +
                    "⚠️ 用户只是说「这次别用那个技能」时**不要**删 —— 那应该关开关而不是卸载。",
            )
            put("parameters", JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("name", JSONObject().apply {
                        put("type", "string")
                        put("description", "要删除的技能名（取自 list_skills）")
                    })
                })
                put("required", JSONArray(listOf("name")))
            })
        })
    }

    /** 执行 install_skill */
    fun executeInstall(context: Context, arguments: String): String {
        val args = try {
            JSONObject(arguments.ifBlank { "{}" })
        } catch (_: Exception) {
            return "技能参数解析失败，请传入 {\"url\":\"…\"} 或 {\"markdown\":\"…\"}"
        }
        val markdown = args.optString("markdown").trim()
        if (markdown.isNotEmpty()) {
            return summarizeResults(listOf(installFromMarkdown(context, markdown)))
        }
        val url = args.optString("url").trim()
        if (url.isEmpty()) {
            return "请提供 url（技能包地址）或 markdown（SKILL.md 全文），二选一"
        }
        if (count(context) >= MAX_SKILLS) {
            return "技能数量已达上限（$MAX_SKILLS 个），请先让我用 delete_skill 删掉一些再装"
        }
        return try {
            when (val kind = SkillFetcher.classify(url)) {
                SkillFetcher.Kind.UNKNOWN ->
                    "无法识别的链接（只支持 .zip / .md / raw 直链，或 GitHub、Gitee 的仓库、目录页）"

                SkillFetcher.Kind.ZIP -> when (val dl = SkillFetcher.download(url)) {
                    is SkillFetcher.DownloadResult.Zip -> summarizeResults(installFromZip(context, dl.bytes))
                    is SkillFetcher.DownloadResult.Markdown ->
                        summarizeResults(listOf(installFromMarkdown(context, dl.text)))
                }

                SkillFetcher.Kind.MD_FILE -> when (val dl = SkillFetcher.download(url)) {
                    is SkillFetcher.DownloadResult.Markdown ->
                        summarizeResults(listOf(installFromMarkdown(context, dl.text)))
                    is SkillFetcher.DownloadResult.Zip ->
                        summarizeResults(installFromZip(context, dl.bytes))
                }

                SkillFetcher.Kind.REPO_PAGE -> {
                    val candidates = SkillFetcher.resolveRepoPage(url)
                    if (candidates.isEmpty()) {
                        "这个仓库/目录里没有找到可安装的 SKILL.md（技能文件需带 frontmatter 的 name/description）"
                    } else {
                        val results = candidates.mapNotNull { cand ->
                            runCatching {
                                when (val dl = SkillFetcher.download(cand.rawUrl)) {
                                    is SkillFetcher.DownloadResult.Markdown ->
                                        installFromMarkdown(context, dl.text)
                                    is SkillFetcher.DownloadResult.Zip ->
                                        installFromZip(context, dl.bytes).firstOrNull()
                                }
                            }.getOrNull()
                        }
                        if (results.isEmpty()) "技能包下载失败（网络不可达或文件格式不支持）"
                        else summarizeResults(results)
                    }
                }

                // 兜底：classify 新增枚举时会在这里编译报错（when 不穷尽），强制补分支
                else -> "暂不支持这种链接类型（$kind）"
            }
        } catch (e: Exception) {
            Log.w(TAG, "install from url failed: ${e.message}")
            "技能包下载/解析失败：${e.message}"
        }
    }

    /** 安装结果汇总（给模型的文本） */
    private fun summarizeResults(results: List<InstallResult>): String {
        val ok = results.filter { it.success }
        val bad = results.filterNot { it.success }
        return buildString {
            if (ok.isNotEmpty()) {
                append("已安装 ${ok.size} 个技能：")
                append(ok.joinToString("、") { it.skillName ?: "（未命名）" })
            }
            if (bad.isNotEmpty()) {
                if (isNotEmpty()) append("。")
                append("失败 ${bad.size} 个：")
                append(bad.joinToString("；") { it.message })
            }
            if (isEmpty()) append("没有可安装的技能")
        }
    }

    /** 执行 list_skills */
    fun executeList(context: Context): String {
        val all = listSkills(context)
        if (all.isEmpty()) return "目前没有安装任何自定义技能（可以在设置页或让我用 install_skill 安装）"
        val lines = all.map { s ->
            val state = if (s.enabled) "启用中" else "已停用"
            "- ${s.name}（$state）：${s.description.take(80)}"
        }
        return "已安装 ${all.size} 个技能：\n" + lines.joinToString("\n") +
            "\n（要加载某个技能的步骤，用 load_skill 传它的名字）"
    }

    /** 执行 delete_skill */
    fun executeDelete(context: Context, arguments: String): String {
        val args = try {
            JSONObject(arguments.ifBlank { "{}" })
        } catch (_: Exception) {
            return "技能参数解析失败，请传入 {\"name\":\"技能名\"}"
        }
        val name = args.optString("name").trim()
        if (name.isEmpty()) return "请提供要删除的技能名 name（可先用 list_skills 查看）"
        val exists = listSkills(context).any { it.name == name }
        if (!exists) {
            return "没有找到技能「$name」。已安装的技能：" +
                listSkills(context).joinToString("、") { it.name }.ifEmpty { "（无）" }
        }
        return if (delete(context, name)) "已删除技能「$name」（不可恢复）" else "删除技能「$name」失败"
    }

    /** 删除技能（目录整体删除） */
    fun delete(context: Context, name: String): Boolean {
        val dir = File(skillsDir(context), name)
        if (!dir.isDirectory) return false
        val ok = dir.deleteRecursively()
        // 删除后清掉开关残留
        prefs(context).edit().remove(KEY_PREFIX + name).apply()
        Log.i(TAG, "delete skill $name -> $ok")
        return ok
    }

    private fun readLimited(input: java.io.InputStream, max: Long): String? {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > max) return null
            out.write(buf, 0, n)
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }
}
