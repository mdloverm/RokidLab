package com.rokidlab.phone.ai

/**
 * SKILL.md 技能文件解析/序列化（纯 Kotlin，无 Android 依赖，可单测）。
 *
 * 格式对齐 Anthropic Agent Skills 的简化版 frontmatter：
 *
 * ```
 * ---
 * name: weather-run-advice
 * description: 当用户问「今天适合跑步吗 / 出门穿什么」时使用
 * ---
 *
 * ## 操作步骤
 * 1. 调用 get_current_time 确认今天是星期几
 * 2. 调用 search_web 查询今天天气
 * 3. 结合体感温度给出穿衣与补水建议，回复不超过 3 句
 * ```
 *
 * 约束：
 *  - name：小写字母开头，仅含小写字母/数字/连字符，2~32 字符（目录名安全）；
 *  - description：一句话描述「做什么 + 何时用」，注入系统清单段时给模型做命中判断；
 *  - body：Markdown 步骤正文，仅在模型命中该技能时按需加载（progressive disclosure）。
 *
 * 安全模型：技能正文只是「说明书」，运行时模型仍只能调用 ToolRegistry 注册的受管
 * 工具，技能无法自行注册或执行任意动作。正文中引用的动作名会被 [checkUnsafeRefs]
 * 静态提示（如引用了不存在的工具），但仅作警告不阻断安装。
 */
object SkillMarkdown {
    /**
     * 技能正文安装上限：允许参考手册级大技能入库（如 AIUI 官方 3.9 万字符技能）。
     * 大技能不会一次性全文回填给模型——load_skill 返回时按 [MAX_INLINE_CHARS] 阈值决定
     * 「内联全文」还是「章节目录 + load_skill_section 按需取章」。
     */
    const val MAX_BODY_CHARS = 100000

    /**
     * 内联加载阈值：正文 ≤ 该值时 load_skill 直接返回全文（原渐进披露行为）；
     * 正文更大时进入分片模式（目录 + 按章加载），避免单条 tool 消息撑爆模型上下文。
     */
    const val MAX_INLINE_CHARS = 6000

    /**
     * load_skill_section 单章返回上限：个别章节可能异常庞大，超过时截断并提示，
     * 防止一次取章仍顶满上下文。
     */
    const val MAX_SECTION_CHARS = 15000

    /** description 长度上限 */
    const val MAX_DESCRIPTION_CHARS = 300

    /** 大技能按 ## 切出的单个章节 */
    data class Section(
        val heading: String, // 章节标题（不含 "## " 前缀与编号，如 "1. Project Structure"）
        val content: String, // 章节正文（不含标题行，从标题下一行到下一标题行前）
    )

    /** 解析后的技能结构 */
    data class ParsedSkill(
        val name: String,
        val description: String,
        val body: String,
    )

    /**
     * 校验技能名是否合法（目录名安全 + 兼容 OpenAI 函数名风格）。
     * @return null 表示合法，否则返回错误信息
     */
    fun validateName(name: String): String? {
        val trimmed = name.trim()
        if (!Regex("^[a-z][a-z0-9-]{1,31}$").matches(trimmed)) {
            return "技能名需为 2~32 位小写字母/数字/连字符，且以字母开头"
        }
        return null
    }

    /**
     * 从 SKILL.md 全文解析出技能（frontmatter 不合法返回 null）。
     * 容忍 CRLF、首行 BOM 与 frontmatter 前后空行。
     */
    fun parse(text: String): ParsedSkill? {
        val raw = text.removePrefix("\uFEFF").replace("\r\n", "\n").trim('\n', ' ', '\t')
        // 必须以 --- 开头
        val lines = raw.split('\n')
        if (lines.isEmpty() || lines[0].trim() != "---") return null

        // 找到 frontmatter 结束行（第二个 ---）
        var endIdx = -1
        for (i in 1 until lines.size) {
            if (lines[i].trim() == "---") { endIdx = i; break }
        }
        if (endIdx <= 0) return null

        val fmLines = lines.subList(1, endIdx)
        var name: String? = null
        var description: String? = null
        for (line in fmLines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            val sep = trimmed.indexOf(':')
            if (sep <= 0) continue
            val key = trimmed.substring(0, sep).trim().lowercase()
            val value = trimmed.substring(sep + 1).trim().trim('"', '\'')
            when (key) {
                "name" -> if (name == null) name = value
                "description", "desc", "what_it_does" -> if (description == null) description = value
            }
        }
        // body：frontmatter 之后的内容（去掉首尾多余空行）
        val body = lines.subList(endIdx + 1, lines.size)
            .joinToString("\n")
            .trim('\n', ' ', '\t')
            .trim()

        val skillName = name?.trim() ?: return null
        if (skillName.isEmpty()) return null
        val desc = description?.trim().orEmpty()
        return ParsedSkill(skillName, desc, body)
    }

    /** 序列化为 SKILL.md 全文 */
    fun build(name: String, description: String, body: String): String {
        val sb = StringBuilder()
        sb.append("---\n")
        sb.append("name: ").append(name.trim()).append('\n')
        sb.append("description: ").append(description.trim().replace('\n', ' ')).append('\n')
        sb.append("---\n\n")
        sb.append(body.trim())
        return sb.toString()
    }

    /**
     * 静态检查技能正文中引用的动作是否越权：
     * 找出「调用 xxx / xxx()」形式的动作名，凡不在受管工具白名单内的都收集起来。
     * 仅提示（模型会照步骤描述调用，引用不存在工具只会失败并如实告知），不阻断安装。
     *
     * @return 未识别动作名列表（可能为空）
     */
    fun checkUnsafeRefs(body: String, knownToolNames: Set<String>): List<String> {
        val unsafe = LinkedHashSet<String>()
        // 形如：调用 xxx / xxx 工具 / xxx()。中文场景自由文本多，只挑明显是动作引用的候选
        val candidates = mutableListOf<String>()
        // 「调用 xxx」
        Regex("调用\\s+([a-zA-Z][a-zA-Z0-9_]{1,39})").findAll(body).forEach { candidates.add(it.groupValues[1]) }
        // 「xxx()」
        Regex("\\b([a-zA-Z][a-zA-Z0-9_]{1,39})\\s*\\(").findAll(body).forEach { candidates.add(it.groupValues[1]) }
        for (c in candidates) {
            val lower = c.lowercase()
            if (lower in knownToolNames) continue
            // 排除常见非工具词（方法名噪音）
            if (lower in COMMON_NON_TOOL_WORDS) continue
            unsafe.add(lower)
        }
        return unsafe.toList()
    }

    private val COMMON_NON_TOOL_WORDS = setOf(
        "if", "for", "while", "return", "function", "not", "and", "or", "get", "set",
        "https", "http", "txt", "json", "time", "date", "api", "app", "send", "show",
    )

    // ═══════════════════════════════════════════════
    // 大技能分片（按 ## 章节切分 + 定位 + 目录）
    // ═══════════════════════════════════════════════

    /** 提取纯标题（去 "## " 前缀与行内尾随标记的空白），仅用于展示/匹配 */
    private fun cleanHeading(line: String): String =
        line.removePrefix("##").trim()

    /**
     * 把技能正文按顶层 ## 章节切分（fence 代码块内的 "## " 不算章节边界）。
     * 返回章节列表；正文无任何 ## 时返回空列表（调用方按无结构处理）。
     */
    fun parseChapters(body: String): List<Section> {
        if (body.isBlank()) return emptyList()
        val lines = body.split('\n')
        val sections = mutableListOf<Section>()
        var current: MutableList<String>? = null
        var currentHeading = ""
        var inFence = false
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("```")) { inFence = !inFence; continue }
            if (!inFence && trimmed.startsWith("## ")) {
                if (current != null) {
                    sections.add(Section(currentHeading, current.joinToString("\n").trim()))
                }
                currentHeading = cleanHeading(line)
                current = mutableListOf()
            } else {
                current?.add(line)
            }
        }
        if (current != null) {
            sections.add(Section(currentHeading, current.joinToString("\n").trim()))
        }
        return sections
    }

    /** 标题规范化：去首尾空白、去 "## "、去 "1." / "1、" / "1)" 等序号前缀，转小写（便于模糊匹配） */
    private fun normTitle(title: String): String =
        title.trim()
            .replace(Regex("^#{1,6}\\s*"), "")
            .replace(Regex("^\\d+[.、)\\s]+"), "")
            .trim()
            .lowercase()

    /**
     * 按查询定位章节。查询可为：
     *   1. 纯数字序号（1-based，对应目录顺序，即目录里的「第 N 章」）；
     *   2. 标题自带编号（如目录里「第 1 章 0. 开发必读顺序」的 `0`）—— 目录同时展示两套编号，
     *      模型按字面传标题编号是合理行为，位置序号越界时按标题编号再匹配一次；
     *   3. 标题原文/去序号前缀后的标题（忽略大小写，先精确后包含）。
     * @return 命中的章节；找不到返回 null
     */
    fun locateSection(body: String, query: String): Section? {
        val sections = parseChapters(body)
        if (sections.isEmpty()) return null
        val q = query.trim()
        if (q.isEmpty()) return null
        // 1) 纯数字 / 「第 N 章」 → 第 N 章
        val number = q.toIntOrNull()
            ?: Regex("^第\\s*(\\d+)\\s*章$").find(q)?.groupValues?.get(1)?.toIntOrNull()
        if (number != null) {
            if (number in 1..sections.size) return sections[number - 1]
            // 位置序号越界：目录里标题可能自带另一套编号（「第 1 章 0. 开发必读顺序」），
            // 模型传 `0` 就是照字面读的结果，此处按标题自带编号补匹配一次。
            // 必须比对**原始 heading**：normTitle 会剥掉开头数字前缀（^\d+[.、)\s]+），
            // "0." 被抹掉后 "0" 永远匹配不到，下方的标题匹配帮不上忙。
            val selfNumbered = Regex("^\\s*" + number + "\\s*[.、:：)）]")
            return sections.firstOrNull { selfNumbered.containsMatchIn(it.heading) }
        }
        // 2) 标题精确/前缀匹配
        val normQ = normTitle(q)
        sections.forEach { s ->
            if (normTitle(s.heading) == normQ) return s
        }
        // 3) 包含匹配（模型可能给近似名）
        sections.forEach { s ->
            val nh = normTitle(s.heading)
            if (nh.contains(normQ) || normQ.contains(nh)) return s
        }
        return null
    }

    /**
     * 生成大技能的章节目录文本（供 load_skill 返回给模型“点菜”）。
     * 每章一行：序号 + 标题 + 字符数 + 开头预览，方便模型决定先取哪章。
     */
    fun buildChaptersIndex(body: String, previewChars: Int = 80): String {
        val sections = parseChapters(body)
        if (sections.isEmpty()) return ""
        val sb = StringBuilder()
        sections.forEachIndexed { i, s ->
            sb.append("第 ").append(i + 1).append(" 章 ").append(s.heading)
                .append("（").append(s.content.length).append(" 字）\n")
            val preview = s.content.replace('\n', ' ').trim()
            if (preview.isNotEmpty()) {
                sb.append("   ").append(preview.take(previewChars))
                if (preview.length > previewChars) sb.append("…")
                sb.append('\n')
            }
        }
        return sb.toString().trimEnd()
    }
}
