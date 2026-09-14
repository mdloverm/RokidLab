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
            // 用户已在线同步覆盖的内置技能：seed 跳过，防止官方更新被 App 内置旧版回滚
            if (isSeedSuppressed(context, name)) {
                Log.i(TAG, "seedBundledSkills: skip $name (user synced official update)")
                continue
            }
            val files = runCatching { context.assets.list("skills/$name")?.toList().orEmpty() }
                .getOrDefault(emptyList())
            for (file in files.filter { it.endsWith(".md", ignoreCase = true) }) {
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

    /** 技能目录内全部 .md 文件名（含 SKILL.md），目录缺失返回空列表 */
    fun listSkillFileNames(context: Context, name: String): List<String> {
        val dir = File(skillsDir(context), name)
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles()?.asSequence()
            ?.filter { it.isFile && it.name.endsWith(".md", ignoreCase = true) }
            ?.map { it.name }
            ?.sorted()
            ?.toList()
            .orEmpty()
    }

    /** 读取技能目录内指定参考文件全文；文件不存在/非法返回 null */
    fun readSkillFileText(context: Context, name: String, fileName: String): String? {
        val safe = safeFileName(fileName) ?: return null
        val file = File(skillsDir(context), name).resolve(safe)
        return if (file.isFile) runCatching { file.readText(Charsets.UTF_8) }.getOrNull() else null
    }

    /**
     * 校验技能目录内文件名：仅允许「字母/数字/点/下划线/连字符」组成的 .md 文件名，
     * 拒绝路径分隔符与 ..（防目录穿越），拒绝隐藏文件名。
     */
    fun safeFileName(fileName: String): String? {
        val f = fileName.trim()
        if (f.length > 64 || f.startsWith(".")) return null
        if (!Regex("^[A-Za-z0-9._-]+\\.md$").matches(f)) return null
        if (f.contains("..")) return null
        return f
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
        val full = loadFullText(context, name)
        if (full == null) {
            return "未找到技能「$name」，可用的技能名：" +
                listSkills(context).joinToString("、") { it.name }.ifEmpty { "（无）" }
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
            val runtimeName = "lab-runtime.md"
            if (runtimeName in refFiles) {
                val runtimeText = readSkillFileText(context, name, runtimeName)
                if (runtimeText != null) {
                    val runtimeDoc = SkillMarkdown.parse(runtimeText)
                    val runtimeBody = runtimeDoc?.body ?: runtimeText
                    if (runtimeBody.length <= SkillMarkdown.MAX_INLINE_CHARS) {
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
                if (purpose != null) sb.append("：").append(purpose)
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
     * 从 zip 字节安装技能包：扫描包内所有 .md 文件，仅安装 frontmatter 合法的技能。
     * - 目录结构不限（顶层 / skills/name/ / name/ 均可）；
     * - 文件名不限（SKILL.md / xxx.md），技能名一律取 frontmatter 的 name；
     * - zipslip 防护：拒绝 ../ 与绝对路径条目；单文件与总量均限流，防 zip bomb。
     *
     * @return 每个安装结果（成功/失败逐条返回，供 UI 展示汇总）
     */
    fun installFromZip(context: Context, zipBytes: ByteArray): List<InstallResult> {
        if (zipBytes.size > MAX_ZIP_BYTES) {
            return listOf(InstallResult(null, false, "技能包过大（>${MAX_ZIP_BYTES / 1024}KB），仅支持纯文本技能包"))
        }
        val results = mutableListOf<InstallResult>()
        var anyParsed = false
        // 不用 ZipInputStream.use{}：其 inline lambda 内不允许 break/continue，改显式 try/finally
        val zip = ZipInputStream(ByteArrayInputStream(zipBytes))
        try {
            while (true) {
                val entry = zip.nextEntry ?: break
                val rawName = entry.name.replace('\\', '/')
                // zipslip 防护
                val unsafe = rawName.startsWith("/") ||
                    rawName.split('/').any { it == ".." } ||
                    !entry.isDirectory && !rawName.endsWith(".md", ignoreCase = true)
                if (unsafe) { zip.closeEntry(); continue }
                if (entry.isDirectory || !rawName.endsWith(".md", ignoreCase = true)) {
                    zip.closeEntry()
                    continue
                }
                if (entry.size > MAX_ENTRY_BYTES) { zip.closeEntry(); continue }
                val text = readLimited(zip, MAX_ENTRY_BYTES)
                if (text == null) {
                    zip.closeEntry()
                    continue
                }
                zip.closeEntry()
                // 只收 frontmatter 合法的技能文件（README/说明 md 自然被过滤）
                val doc = SkillMarkdown.parse(text)
                if (doc == null) continue
                anyParsed = true
                results.add(installFromMarkdown(context, text))
            }
        } catch (e: Exception) {
            Log.e(TAG, "zip install failed", e)
            if (results.isNotEmpty()) return results
            return listOf(InstallResult(null, false, "技能包解析失败（不是合法的 zip 或内容损坏）"))
        } finally {
            runCatching { zip.close() }
        }
        if (results.isEmpty()) {
            val msg = if (anyParsed) "zip 内没有可安装的技能" else "zip 内未找到含 name/description 的 SKILL.md 技能文件"
            return listOf(InstallResult(null, false, msg))
        }
        return results
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
