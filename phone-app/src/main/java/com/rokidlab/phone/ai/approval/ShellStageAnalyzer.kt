package com.rokidlab.phone.ai.approval

/**
 * 复合 shell 命令的**分阶段静态分析**（对齐 Muse Code 的 staged approvals）。
 *
 * ## 为什么需要它
 *
 * `run_shell` 目前是 [com.rokidlab.phone.ai.ToolRisk.LOCAL_SIDE_EFFECT] —— 对话路径
 * **静默执行**，判据是「容器可丢弃」：`rm -rf /` 只毁掉 rootfs，重下 28.5 MB 即恢复。
 *
 * 但这条判据有一个**盲区**：容器里有两个挂载点直通手机的真实文件系统
 * （见 `FileWorkspace` / `LabMediaScan`）：
 *  - `/mnt/lab` —— 内核 `write()` 直连绑定到**手机的「下载/Lab」文件夹**（双向读写）；
 *  - `/mnt/download` —— 只读镜像手机整个「下载」目录。
 *
 * 于是 `rm -rf /mnt/lab/…` 毁掉的是**用户真实的文件**，而它与 `rm -rf /tmp/x`
 * 在现有风险表里是同一档（都只是 LOCAL_SIDE_EFFECT）⇒ 都不问用户。
 * 这个分析器要解决的就是这件事：**按"命令碰到了谁的数据"分档，而不是按"命令看起来多危险"**。
 *
 * ⚠️ 注意本文件注释里**不要出现"斜杠 + 星号"这两个字符连写**（例如通配符形式的真实路径）：
 *   Kotlin 支持嵌套块注释，KDoc 里出现它会让注释永远闭合不上、报 "Unclosed comment"。
 *   写路径通配时用 `…` 或拆开字符代替（本行原样示范：拆分描述，不连写）。
 *
 * ## 判据：靶子是「真实用户数据」，不是「危险命令行」
 *
 * 📌 关键取舍：**`rm -rf` 本身不是靶子**。`rm -rf /tmp/build` 在可丢弃容器里完全无害，
 * 若按动词拦它，日常命令会被大量误伤（用户与模型都在容器里频繁删临时文件）。
 * 所以判定看的是**目标路径落在哪个文件系统**：
 *
 * | 情况 | 结论 |
 * |---|---|
 * | 破坏性动词（rm/rmdir/shred/truncate/dd/mv）作用于 `/mnt/lab` | [StageResolution.REVIEW_DANGEROUS] |
 * | 前序阶段 `cd` 进过 `/mnt/lab`，本阶段有破坏性动词（如 `rm -rf *`） | [StageResolution.REVIEW_UNRESOLVED] |
 * | 命令里有命令替换 / 变量前缀 / 无法静态展开的构造 | [StageResolution.REVIEW_UNRESOLVED] |
 * | 其余（纯容器内操作、只读命令、往 `/mnt/lab` **新建**文件） | [StageResolution.AUTO_SAFE] |
 *
 * ⚠️ **写 `/mnt/lab` 不拦**（只有删除类动词才拦）：往那里写正是本项目的主力用法
 *   （生成的项目落到「下载/Lab」，用户在文件管理器里能直接拿到）。把所有写都拦下来
 *   会把功能废掉 —— 这是**有意的取舍**，不是遗漏。
 *
 * ## 保守原则（唯一不能妥协的一条）
 *
 * **静态看不透 ⇒ 一律 [StageResolution.REVIEW_UNRESOLVED]，绝不自动放行。**
 * 代价是多问用户一次；反面是把一次真实的删除放过去。两者不对等，所以规则一律往保守偏。
 * 这条对应 Muse 的 `argv_complete = false` 语义：解析不完整的阶段不得判 `known_safe`。
 *
 * ## 纯函数
 *
 * 无 IO、无 Android 依赖、不看 Context —— 可直接 JVM 单测（锁在 `ShellStageAnalyzerTest`）。
 */
internal object ShellStageAnalyzer {

    /**
     * 直通手机真实文件系统的挂载点（唯一产地）。
     *
     * ⚠️ 与 `FileWorkspace` 的挂载约定必须保持一致：改挂载点要同步这里，
     * 否则分析器会把真实路径误判成容器内路径而静默放行 —— 那正是本类要防的事。
     */
    const val REAL_FS_MOUNT = "/mnt/lab"

    /** 只读镜像挂载点。写它会被内核拒绝，但**出现在破坏性动词里**仍值得提一句。 */
    const val READONLY_MOUNT = "/mnt/download"

    /** 一个阶段的判定结果（落盘 wire 值用 [wire]，对齐 MSP 的 `resolution.kind`） */
    internal enum class StageResolution(val wire: String) {
        /** 确认不触碰真实用户数据 ⇒ 自动放行，不打扰用户 */
        AUTO_SAFE("known_safe"),

        /** 已知的破坏性动作，目标落在真实文件系统 ⇒ 必须用户点头 */
        REVIEW_DANGEROUS("dangerous"),

        /** 静态看不透（变量/替换/前序 cd 改变了语义）⇒ 必须用户点头 */
        REVIEW_UNRESOLVED("unresolved"),
        ;

        /** 是否需要用户确认 */
        val needsReview: Boolean get() = this != AUTO_SAFE
    }

    /**
     * 单个阶段的解析结果。
     *
     * @param raw 该阶段在原始命令里的文本（保留原样，展示给用户看的就是它）
     * @param argv 静态拆出的参数列表（看不透时为空）
     * @param argvComplete false = 参数没能完整静态展开（Muse 的 `argv_complete`）
     * @param reason 为什么是这个判定（进审批文案与审计，人要看懂）
     */
    internal data class Stage(
        val raw: String,
        val argv: List<String> = emptyList(),
        val argvComplete: Boolean = true,
        val resolution: StageResolution,
        val reason: String,
    )

    /**
     * 破坏性动词（作用于真实文件系统时需审批）。
     *
     * 📌 只收**不可逆**的：删除、截断、覆盖式移动。
     * 刻意**不收** `mkdir` / `touch` / `cp`（拷贝进去）/ `tee` —— 它们往 `/mnt/lab` 写
     * 是主力用法（生成项目），拦下来等于把功能废掉（见类注释的取舍）。
     */
    private val DESTRUCTIVE = setOf(
        "rm", "rmdir", "shred", "unlink", "truncate", "dd", "mv",
    )

    /**
     * 名字本身**两义**的命令 → 触发破坏性的参数。
     *
     * 为什么单列一张表：`find` 是最常见的只读查询之一
     * （`find /mnt/lab -name "*.txt"`），若按名字整条拦下，高频用法会被大量误伤；
     * 但它同时也是删除入口（`-delete` / `-exec rm`）。判据因此落在**参数**上。
     *
     * 📌 这一档是"按行为而不是按名字"的判据本身：**同一个命令在不同参数下危险程度不同**，
     *   按名字一刀切会在两个方向上都错。
     */
    private val DESTRUCTIVE_FLAGS: Map<String, Set<String>> = mapOf(
        "find" to setOf("-delete", "-exec", "-execdir", "-ok", "-fprint"),
        "sed" to setOf("-i"),
        "awk" to setOf("-i"),
        "tee" to setOf("-a"),
        "git" to setOf("clean", "reset", "checkout"),
        "tar" to setOf("-x", "--extract", "xf", "xzf"),
        "unzip" to setOf("-o", "-d"),
    )

    /**
     * 确定只读的命令（这些出现在真实挂载点上也无害，直接判 safe）。
     *
     * ⚠️ 白名单而不是黑名单：新命令默认**不是**只读的，这保证"没见过的东西往保守偏"。
     * ⚠️ `find` / `sed` / `tee` / `git` 等**两义命令不在此表**：它们的只读性取决于参数，
     *   由 [DESTRUCTIVE_FLAGS] 判定（见该表的说明）。
     */
    private val READ_ONLY = setOf(
        "ls", "ll", "cat", "head", "tail", "wc", "grep", "egrep", "fgrep", "rg",
        "file", "stat", "du", "df", "tree", "pwd", "echo", "printf",
        "which", "command", "type", "sha256sum", "md5sum", "cmp", "diff",
        "python3", "python", "node", "jq", "sort", "uniq", "cut", "tr",
    )

    /**
     * 复合命令的分隔符（按**优先级从低到高**扫描；`;` 与换行同义）。
     *
     * 不建模的构造（命中即判定为"看不透"，交审批）：
     *  - 子 shell `( … )` / 命令组 `{ … }`：内部状态（cd）不透明
     *  - 反引号 / `$( … )`：命令替换，静态拿不到值
     *  - 变量赋值前缀 `X=…`：后续 `$X` 展开不可知
     *  - 重定向到变量路径：`> $OUT`
     */
    private val SEPARATORS = listOf("||", "&&", ";", "|", "&")

    /**
     * 分析整条命令。
     *
     * @return 有序阶段列表；单条简单命令也返回长度为 1 的列表（调用方不必分情况处理）
     */
    fun analyze(command: String): List<Stage> {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return emptyList()

        // 命令替换 / 反引号：整体看不透 —— 不做"只标那一段"的精细处理，
        // 因为替换结果可能决定后面所有阶段的语义（`rm -rf $(cat p)`）。
        val hasSubstitution = trimmed.contains("$(") || trimmed.contains('`')
        val segments = splitTopLevel(trimmed)

        var inRealFs = false
        val stages = ArrayList<Stage>(segments.size)
        for (seg in segments) {
            val stage = analyzeStage(seg.trim(), hasSubstitution, inRealFs)
            stages += stage
            // 前序 cd 进真实目录后，后续阶段（`rm -rf *`）的目标就不再字面可见
            if (cdTarget(seg.trim())?.startsWith(REAL_FS_MOUNT) == true) inRealFs = true
        }
        return stages
    }

    /** 整条命令是否需要用户确认（任一阶段需要 ⇒ 需要） */
    fun needsReview(stages: List<Stage>): Boolean = stages.any { it.resolution.needsReview }

    /**
     * 分阶段审批的展示文案（给用户看"这条命令里哪几步要你点头"）。
     *
     * 只列**需要审批**的阶段 —— 自动放行的阶段对用户是噪音，
     * 但它们仍会进审计（见 `SideEffectIntent.stageEvidence`）。
     */
    fun reviewSummary(stages: List<Stage>): String =
        stages.filter { it.resolution.needsReview }
            .joinToString("；") { "${it.raw}（${it.reason}）" }

    // ═══════════════════════ 内部 ═══════════════════════

    private fun analyzeStage(raw: String, hasSubstitution: Boolean, inRealFs: Boolean): Stage {
        if (raw.isEmpty()) {
            return Stage(raw, resolution = StageResolution.AUTO_SAFE, reason = "空阶段")
        }
        if (hasSubstitution) {
            return Stage(
                raw,
                argvComplete = false,
                resolution = StageResolution.REVIEW_UNRESOLVED,
                reason = "含命令替换，静态看不透实际参数",
            )
        }
        // 变量赋值前缀：`X=/mnt/lab/p; rm -rf $X` 这类绕过路径
        if (VARIABLE_PREFIX.containsMatchIn(raw)) {
            return Stage(
                raw,
                argvComplete = false,
                resolution = StageResolution.REVIEW_UNRESOLVED,
                reason = "含变量赋值，后续展开不可知",
            )
        }
        // 子 shell / 命令组：内部 cd 会改变后续语义，且我们拿不到其退出状态
        if (raw.startsWith("(") || raw.startsWith("{")) {
            return Stage(
                raw,
                argvComplete = false,
                resolution = StageResolution.REVIEW_UNRESOLVED,
                reason = "含子 shell / 命令组，内部状态不透明",
            )
        }

        val argv = tokenize(raw)
        val cmd = argv.firstOrNull()?.substringAfterLast('/')?.lowercase()
            ?: return Stage(raw, resolution = StageResolution.AUTO_SAFE, reason = "无命令")

        val touchesRealFs = argv.any { it.contains(REAL_FS_MOUNT) || it.contains(READONLY_MOUNT) }
            || raw.contains("$REAL_FS_MOUNT") || raw.contains("$READONLY_MOUNT")

        // 前序 cd 进过真实目录：本阶段的相对路径（`*`、`.`）指向真实文件
        if (inRealFs && isDestructive(cmd, argv)) {
            return Stage(
                raw,
                argv = argv,
                resolution = StageResolution.REVIEW_UNRESOLVED,
                reason = "前序已 cd 进 $REAL_FS_MOUNT，本阶段目标指向真实文件",
            )
        }

        // ★ 靶子：破坏性动作 × 真实文件系统。这是本类存在的**唯一**理由
        if (isDestructive(cmd, argv) && touchesRealFs) {
            return Stage(
                raw,
                argv = argv,
                resolution = StageResolution.REVIEW_DANGEROUS,
                reason = "$cmd 会删除/覆盖手机上的真实文件",
            )
        }

        // 重定向写真实挂载点：**不拦**（生成项目的主力用法，见类注释的取舍），
        // 但覆写只读挂载点要提一句 —— 那不是"写不进去"的问题，是意图可疑
        if (raw.contains(">") && raw.contains(READONLY_MOUNT)) {
            return Stage(
                raw,
                argv = argv,
                resolution = StageResolution.REVIEW_UNRESOLVED,
                reason = "试图写入只读挂载点 $READONLY_MOUNT",
            )
        }

        if (cmd in READ_ONLY) {
            return Stage(raw, argv = argv, resolution = StageResolution.AUTO_SAFE, reason = "$cmd 只读")
        }

        // 非只读、但确认**不碰**真实挂载点 ⇒ 只作用于可丢弃容器。
        // ⚠️ 文案必须与事实相符（判据⑧）：`cd /mnt/lab` 之类确实碰到了真实路径，
        //   只是它不破坏数据 —— 把它说成"容器内操作"会误导排查的人。
        return if (touchesRealFs) {
            Stage(
                raw,
                argv = argv,
                resolution = StageResolution.AUTO_SAFE,
                reason = "$cmd 触及 $REAL_FS_MOUNT 但不删除/覆盖数据",
            )
        } else {
            Stage(
                raw,
                argv = argv,
                resolution = StageResolution.AUTO_SAFE,
                reason = "容器内操作（可丢弃环境）",
            )
        }
    }

    /**
     * 该阶段是否是破坏性动作。
     *
     * 两类来源：**按名字**（[DESTRUCTIVE]，名字本身就只干破坏的事）
     * 与**按参数**（[DESTRUCTIVE_FLAGS]，名字两义、参数决定危险性）。
     */
    private fun isDestructive(cmd: String?, argv: List<String>): Boolean {
        if (cmd == null) return false
        if (cmd in DESTRUCTIVE) return true
        val flags = DESTRUCTIVE_FLAGS[cmd] ?: return false
        // 参数里出现任一危险开关即算：`find … -delete`、`sed -i`、`git clean -fd`…
        val args = argv.drop(1).map { it.lowercase() }
        return args.any { a -> flags.any { f -> a == f || a.startsWith("$f=") } }
    }

    /** 变量赋值前缀：`X=value cmd` / `export X=…` */
    private val VARIABLE_PREFIX = Regex("^\\s*(export\\s+)?[A-Za-z_][A-Za-z0-9_]*=")

    /** 阶段若是一条 `cd`，返回它的目标路径（用于跟踪"当前是否在真实目录里"） */
    private fun cdTarget(raw: String): String? {
        val argv = tokenize(raw)
        if (argv.firstOrNull()?.substringAfterLast('/') != "cd") return null
        return argv.getOrNull(1)
    }

    /**
     * 按顶层分隔符切分（**引号感知**）。
     *
     * 引号内的分隔符不是分隔符：`echo "a && b"` 是一个阶段。
     * 简化处理（有意）：不建模 `$'…'` / heredoc 内部的引号规则 ——
     * 前者罕见，后者一旦出现整条命令多半会落到"看不透"（含 `<<` 时由调用方保守处理）。
     */
    private fun splitTopLevel(command: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var quote: Char? = null
        var escaped = false
        var i = 0
        while (i < command.length) {
            val c = command[i]
            if (escaped) {
                cur.append(c); escaped = false; i++; continue
            }
            if (c == '\\' && quote != '\'') {
                cur.append(c); escaped = true; i++; continue
            }
            if (quote != null) {
                if (c == quote) quote = null
                cur.append(c); i++; continue
            }
            if (c == '\'' || c == '"') {
                quote = c; cur.append(c); i++; continue
            }
            // 换行与 ; 同义
            if (c == '\n') {
                out += cur.toString(); cur.setLength(0); i++; continue
            }
            val sep = SEPARATORS.firstOrNull { command.startsWith(it, i) }
            if (sep != null) {
                out += cur.toString()
                cur.setLength(0)
                // `&&` / `||` 是逻辑连接，会决定后一阶段是否执行 —— 但审批不关心条件
                // （保守起见：后一阶段**可能**执行就按会执行审），所以两者一视同仁地切开。
                i += sep.length
                continue
            }
            cur.append(c); i++
        }
        out += cur.toString()
        // 丢弃全部由空白组成的空段（`a && && b` 这种畸形输入不该造出"空阶段"）
        return out.filter { it.isNotBlank() }
    }

    /**
     * 静态分词（引号感知的最小实现）。
     *
     * ⚠️ 刻意**不**做完整 bash 词法：目标是"能不能看出这个阶段碰到了真实路径"，
     * 不是精确复现 bash 的解析。看不透的构造由调用方判 `argvComplete = false`。
     */
    private fun tokenize(raw: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var quote: Char? = null
        var escaped = false
        for (c in raw) {
            if (escaped) {
                cur.append(c); escaped = false; continue
            }
            if (c == '\\' && quote != '\'') {
                escaped = true; continue
            }
            if (quote != null) {
                if (c == quote) quote = null else cur.append(c)
                continue
            }
            when {
                c == '\'' || c == '"' -> quote = c
                c.isWhitespace() -> {
                    if (cur.isNotEmpty()) { out += cur.toString(); cur.setLength(0) }
                }
                else -> cur.append(c)
            }
        }
        if (cur.isNotEmpty()) out += cur.toString()
        return out
    }
}
