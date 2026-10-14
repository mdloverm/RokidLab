package com.rokidlab.phone.ai

/**
 * **补丁应用引擎** —— 纯函数，无 Android 依赖，可直接单测。
 *
 * ## 为什么需要它（2026-10-06 对标 Codex 的头号缺口）
 * 现有 `edit_text_file` 只有 `append` / `overwrite` 两种模式，schema 自己写着：
 * 「⚠️ 只改一小段也必须先 read_text_file 读到全文，改完把**完整内容**用 overwrite 写回
 * —— 本工具没有『替换某一行』能力，不要凭记忆只写改动片段，否则会丢掉其余内容。」
 *
 * 后果是**改 1 行的成本 = 改 500 行**：读全文（吃上下文）→ 生成全量新内容（吃输出 token、
 * 有截断风险）→ 整份写回。对标 Codex 的 `apply_patch`（O(变更量)），这是结构性差距。
 *
 * ## 设计上唯一不能妥协的一条：**定位失败必须拒绝，不许猜**
 *
 * 整份覆盖的失败模式是「整份文件变乱」，用户能一眼看出来；
 * 局部编辑的失败模式是「**看起来写成功了，但改错了地方**」——
 * 最坏情况是把不该动的那几行抹掉，而模型拿到的是成功回执。
 * 所以本引擎的四条硬规则：
 * 1. 定位不到 ⇒ **抛 [PatchApplyException]**，不写任何文件；
 * 2. 定位到多处 ⇒ **同样拒绝**（不猜"是哪一个"），除非显式给了行号且只有一个命中落在附近；
 * 3. **所有片段定位成功后才落盘** ⇒ 不存在"改了一半"的中间态；
 * 4. 定位失败/片段不唯一时，**报错文案必须说清下一步做什么**（见 [notFoundMessage]）。
 *
 * 至于**"技术上写成功、语义上写错了"**，框架无法判断（只有模型知道自己想改成什么），
 * 那由备份层兜底（见 [writeTextFileWithBackup]）—— 改错了能退回去。
 *
 * ## 补丁格式（借鉴 Codex 的 `apply_patch`）
 * ```
 * @@ <可选定位提示>
 *  上下文行（以空格开头，或原样给出）
 * -被删除的行
 * +新增的行
 *  上下文行
 * @@ 第二个片段（可选）
 * ```
 * ⚠️ **上下文与增删是交错出现的**，所以内部用 [Op] 列表保持原始顺序，
 *    不能拆成"context 段 + 删除段"两个数组再拼 —— 那样拼出来的匹配序列是错的
 *    （`-two` / `+TWO` / ` three` 会变成 old=`[three, two]`，定位必然失败）。
 *    这条是本引擎最容易踩的实现坑，已被单测「多片段与增删交错」锁住。
 *
 * 之所以**不带** `*** Begin Patch` / `*** Add File:` 这类文件头：
 * 文件级操作（新建/删除整文件）由参数 `operation` 表达，不从补丁文本里猜 ——
 * 猜文件类型意味着要处理"头写错一半"的输入，那类输入的失败必然含混。
 */
internal object PatchApplier {

    /** 补丁应用失败。**只在"不该写"的时候抛**，调用方据此回话给模型。 */
    internal class PatchApplyException(message: String) : Exception(message)

    /** 单行操作：保持补丁里的原始顺序（见类注释的交错警告） */
    internal data class Op(val kind: Char, val text: String) {
        companion object {
            const val CONTEXT = ' '
            const val REMOVE = '-'
            const val ADD = '+'
        }
    }

    /** 一个片段：有序的 [Op] 列表 */
    internal data class Hunk(val ops: List<Op>) {
        /**
         * **用于在原文件里匹配的序列** = 上下文 + 删除行（保持原始交错顺序）。
         *
         * ⚠️ 这里**不含**新增行 —— 新增行是"写出去的内容"，原文件里本来就不存在，
         * 把它塞进匹配序列会导致永远匹配不上（这是第一版实现踩过的坑）。
         */
        val pattern: List<String>
            get() = ops.filter { it.kind != Op.ADD }.map { it.text }

        /**
         * **替换成的内容** = 上下文 + 新增行（保持原始顺序，去掉删除行）。
         *
         * ⚠️ 替换时**必须把上下文行一起写回**。只写新增行的话，
         * "删掉 B、保留 A 和 C" 会变成"整段被空替换" ⇒ 上下文全丢。
         */
        val replacement: List<String>
            get() = ops.filter { it.kind != Op.REMOVE }.map { it.text }

        val removed: List<String> get() = ops.filter { it.kind == Op.REMOVE }.map { it.text }
        val added: List<String> get() = ops.filter { it.kind == Op.ADD }.map { it.text }
        val hasContext: Boolean get() = ops.any { it.kind == Op.CONTEXT }
        val hasRemove: Boolean get() = ops.any { it.kind == Op.REMOVE }
    }

    /** 单个片段应用后的结果摘要（给模型看的回执） */
    data class HunkResult(
        /** 1-based 起始行号（应用后文件里的位置） */
        val line: Int,
        val added: Int,
        val removed: Int,
    )

    data class ApplyResult(
        val content: String,
        val hunks: List<HunkResult>,
    )

    /** 定位容差：给了行号时，命中位置前后各留这么多行算数。0 = 严格按行号。 */
    private const val SEARCH_FLOOR = 3

    /** 单个补丁允许的最大行数（防御模型吐出超长补丁把内存打爆） */
    private const val MAX_PATCH_LINES = 4_000

    /**
     * 应用补丁。
     *
     * @param original 原文件内容（新建文件时传空串）
     * @param patch 补丁文本
     * @param lineHint `@@` 里给出的"大约在第几行"（1-based，可空）。**只是提示不是命令**：
     *   实际定位优先用上下文逐行匹配，匹配不上才退化成按行号切片。
     * @return 应用后的完整内容 + 每个片段的位置摘要
     * @throws PatchApplyException 格式非法 / 定位失败 / 定位到多处而无法判定
     */
    fun apply(original: String, patch: String, lineHint: Int? = null): ApplyResult {
        if (patch.lines().size > MAX_PATCH_LINES) {
            throw PatchApplyException("补丁过长（${patch.lines().size} 行 > $MAX_PATCH_LINES），请拆成多次调用")
        }
        val hunks = parseHunks(patch)
        if (hunks.isEmpty()) {
            throw PatchApplyException(
                "补丁里没有任何可应用的片段。每个片段要以 ' '（上下文）/ '-'（删除）/ '+'（新增）开头，" +
                    "并用 @@ 开头一个新片段。⚠️ 空行必须写成 ' ' 或 '+'，裸空行会被当成片段结束。"
            )
        }
        // 从后往前应用：前面的片段不受后面行号变动影响（改后面的行不会移动前面的行）
        var work = splitLines(original)
        val results = arrayOfNulls<HunkResult>(hunks.size)
        // 行号提示只在**单片段**时采纳：多片段时同一个 hint 会被误用到每个片段上
        val hint = lineHint?.takeIf { it > 0 && hunks.size == 1 }
        for (i in hunks.indices.reversed()) {
            val (next, res) = applyOne(work, hunks[i], hint)
            work = next
            results[i] = res
        }
        return ApplyResult(joinLines(work), results.filterNotNull())
    }

    // ── 解析 ────────────────────────────────────────────────────

    internal fun parseHunks(patch: String): List<Hunk> {
        val out = mutableListOf<Hunk>()
        var ops = mutableListOf<Op>()
        var inHunk = false

        fun flush() {
            if (inHunk && ops.isNotEmpty()) out += Hunk(ops.toList())
            ops = mutableListOf()
            inHunk = false
        }

        patch.lines().forEach { raw ->
            val line = raw.trimEnd('\r')
            val t = line.trimStart()
            if (t.startsWith("@@")) {   // 新片段（可带 "第 N 行" 之类的提示，本引擎只用它做兜底切片）
                flush()
                inHunk = true
                return@forEach
            }
            if (t.startsWith("***")) return@forEach   // 兼容模型习惯性加的格式头，忽略
            if (!inHunk) return@forEach                // 第一个 @@ 之前的说明文字
            when {
                line.startsWith("+") -> ops += Op(Op.ADD, line.substring(1))
                line.startsWith("-") -> ops += Op(Op.REMOVE, line.substring(1))
                line.startsWith(" ") -> ops += Op(Op.CONTEXT, line.substring(1))
                // ⚠️ 裸空行：按"上下文空行"处理（最常见意图），而不是结束片段 ——
                // 结束片段只能靠 @@ 或另一个文件。新增一个空行请写 '+'。
                line.isEmpty() -> ops += Op(Op.CONTEXT, "")
                // 模型忘了加前缀：当作上下文行（宽容处理，好过直接失败）
                else -> ops += Op(Op.CONTEXT, line)
            }
        }
        flush()
        return out
    }

    // ── 单个片段应用 ────────────────────────────────────────────

    private fun applyOne(
        src: List<String>,
        hunk: Hunk,
        hintLine: Int?,
    ): Pair<List<String>, HunkResult> {
        val pattern = hunk.pattern          // 原文件里要匹配的那段（上下文 + 删除行）
        val replacement = hunk.replacement  // 替换成的那段（上下文 + 新增行）
        val addedCount = hunk.added.size
        val removedCount = hunk.removed.size

        // 纯新增：没有任何可匹配的旧内容 ⇒ 只能靠行号插，或追加到末尾
        if (pattern.isEmpty()) {
            if (hintLine == null) {
                val end = src.size
                return splice(src, end, 0, replacement) to
                    HunkResult(end + 1, addedCount, 0)
            }
            // ⚠️ 行号越界**拒绝**而不是 clamp 到边界：越界说明模型对文件长度的判断是错的，
            // 硬插到一个被夹出来的位置正是"看起来写成功了但改错地方"的典型形态。
            if (hintLine < 1 || hintLine > src.size + 1) {
                throw PatchApplyException(
                    "补丁要插入到第 $hintLine 行，但文件只有 ${src.size + 1} 行（含末尾空行），位置越界。" +
                        "⚠️ 请先 read_text_file 确认行数，或改用 edit_text_file(mode=overwrite) 写完整内容。" +
                        "本次没有改动任何内容。"
                )
            }
            val at = hintLine - 1
            return splice(src, at, 0, replacement) to
                HunkResult(firstChangedLine(at, hunk), addedCount, 0)
        }
        val hits = findAll(src, pattern)
        val at = when {
            hits.size == 1 -> hits.first()
            hits.size > 1 -> {
                // ⚠️ 多处命中 = 无法判定是哪一个。**不猜**。
                // 唯一例外：给了 hint 且只有一个命中落在它附近 —— 那说明模型知道大致位置。
                if (hintLine != null) {
                    // ⚠️ 判据是「**距离最近且唯一**」，不是「落在容差范围内」：
                    // 邻近容差会同时圈进两处（相邻两行都有 target 时命中 0 和 2，
                    // 两者都离 hint=3 不远）⇒ 仍然无法判定，等于白给 hint。
                    val want = hintLine - 1
                    val ranked = hits.sortedBy { kotlin.math.abs(it - want) }
                    val best = ranked.first()
                    val tied = ranked.count { kotlin.math.abs(it - want) == kotlin.math.abs(best - want) }
                    if (tied == 1) best
                    else throw PatchApplyException(ambiguous(hits, pattern, hintLine))
                } else {
                    throw PatchApplyException(ambiguous(hits, pattern, hintLine))
                }
            }
            else -> {
                // 逐行匹配失败 → 退化成"按 hint 行号直接切片"（模型给的上下文写错了但行号对）。
                // ⚠️ 这条退路必须要求有删除行：只有新增的话，拿行号硬插会插到完全无关的位置，
                // 那种情况宁可失败也不能写。
                if (hintLine != null && hunk.hasRemove) {
                    val want = hintLine - 1
                    // ⚠️ 位置合法**且内容确实匹配**才认。少后者这条退路就变成
                    // "行号对得上就硬改" ⇒ 会覆盖用户根本没让改的那几行。
                    val fits = want >= 0 && want + pattern.size <= src.size
                    val matches = fits && src.subList(want, want + pattern.size)
                        .indices.all { src[want + it].trimEnd() == pattern[it].trimEnd() }
                    if (matches) want else throw PatchApplyException(notFound(src, pattern, hintLine))
                } else {
                    throw PatchApplyException(notFound(src, pattern, hintLine))
                }
            }
        }
        return splice(src, at, pattern.size, replacement) to
            HunkResult(firstChangedLine(at, hunk), addedCount, removedCount)
    }

    /**
     * 片段里**第一个实际增删的行**，在应用后文件里的 1-based 行号。
     *
     * ⚠️ 不能直接报 `at + 1`（片段起点）：一个「在两行上下文之间插入」的片段，
     * 起点是第 2 行，但真正变化的是第 3 行。报起点会让模型按回执去核对时看到
     * "第 2 行没变"，怀疑自己记错了工具的语义。
     */
    private fun firstChangedLine(at: Int, hunk: Hunk): Int {
        val idx = hunk.ops.indexOfFirst { it.kind != Op.CONTEXT }
        return if (idx < 0) at + 1 else at + idx + 1
    }

    /**
     * 就地替换。
     *
     * @param removeCount 删掉多少行（= pattern 长度）
     * @param replacement **要写回去的完整行序列**（上下文 + 新增，顺序不变）
     *        ⚠️ 传 `replacement` 而不是 `added`：只写新增行会把上下文整段丢掉。
     */
    private fun splice(
        src: List<String>,
        at: Int,
        removeCount: Int,
        replacement: List<String>,
    ): List<String> = buildList {
        addAll(src.subList(0, at.coerceIn(0, src.size)))
        addAll(replacement)
        addAll(src.subList((at + removeCount).coerceIn(0, src.size), src.size))
    }

    /** 在 [src] 里找 [pattern] 的所有出现位置（完整连续匹配，行尾空白容错） */
    private fun findAll(src: List<String>, pattern: List<String>): List<Int> {
        if (pattern.isEmpty() || pattern.size > src.size) return emptyList()
        val hits = mutableListOf<Int>()
        for (i in 0..(src.size - pattern.size)) {
            val window = src.subList(i, i + pattern.size)
            if (window.indices.all { window[it].trimEnd() == pattern[it].trimEnd() }) hits += i
        }
        return hits
    }


    private fun notFound(src: List<String>, old: List<String>, hintLine: Int?): String {
        val where = if (hintLine != null) "（@@ 里给的行号是第 $hintLine 行附近）" else ""
        val sample = old.filter { it.isNotEmpty() }.take(2)
            .joinToString(" / ") { "「${it.take(40)}」" }
        // 定位失败时附上文件里**最接近的实拍内容**：模型不必再整读一遍文件就能照着改
        // 上下文行 —— 这是省一轮重试的关键（历史实测补丁失败往往要多烧 2~3 轮）。
        val nearest = nearestContext(src, old)
        return "补丁定位失败$where：在文件里找不到这几行${if (sample.isEmpty()) "" else " —— $sample"}。" +
            (if (nearest.isNullOrBlank()) "" else "\n文件里最接近的几行（注意实际行号与措辞，多半与补丁里的写法有出入）：\n$nearest") +
            "⚠️ **不要原样重试同一个补丁**：对照上面实际内容修正上下文（并多带 1~2 行独特行）；" +
            "拿不准就先 read_text_file 读该文件确认，再按实际内容重写片段；" +
            "或改用 edit_text_file(mode=overwrite) 写完整内容。" +
            "⚠️ 本次没有改动任何内容，文件保持原样。"
    }

    /**
     * 在 [src] 里找与 [pattern] **最接近**的位置，返回带行号的实拍片段（最多 6 行，行号 1-based）。
     * 评分 = 窗口内逐行（trimEnd 容错）匹配的行数；全文件 0 分时返回 null（没有可比的东西）。
     */
    private fun nearestContext(src: List<String>, pattern: List<String>): String? {
        val pats = pattern.filter { it.isNotBlank() }
        if (pats.isEmpty() || src.isEmpty()) return null
        var bestScore = 0
        var bestAt = -1
        for (i in 0..(src.size - pats.size).coerceAtLeast(0)) {
            var score = 0
            for (j in pats.indices) {
                if (src[i + j].trimEnd() == pats[j].trimEnd()) score++
            }
            if (score > bestScore) {
                bestScore = score
                bestAt = i
            }
        }
        if (bestAt < 0) return null
        val sb = StringBuilder()
        var shown = 0
        for (k in bestAt until (bestAt + pats.size).coerceAtMost(src.size)) {
            if (src[k].isBlank()) continue
            sb.append("  L${k + 1}｜").append(src[k].trim().take(60)).append('\n')
            if (++shown >= 6) break
        }
        return sb.toString().trimEnd().ifEmpty { null }
    }

    private fun ambiguous(hits: List<Int>, old: List<String>, hintLine: Int?): String {
        val lines = hits.joinToString("、") { (it + 1).toString() }
        val hint = if (hintLine != null) "，而 @@ 给的是第 $hintLine 行附近" else ""
        val sample = old.filter { it.isNotEmpty() }.take(2)
            .joinToString(" / ") { "「${it.take(40)}」" }
        return "补丁定位到**多处**（第 $lines 行）$hint，无法判定该改哪一处${if (sample.isEmpty()) "" else " —— $sample"}。" +
            "⚠️ 请在 @@ 里补上更长的上下文（把这段前后各带 2~3 行独特内容）让它唯一，" +
            "或直接用 edit_text_file(mode=overwrite) 写完整内容。本次没有改动任何内容。"
    }

    // ── 行工具 ──────────────────────────────────────────────────

    /**
     * 拆行。**末尾换行**天然被表示成一条空尾行：`"a\n".split("\n") == ["a", ""]`，
     * 所以 `joinLines(splitLines(s)) == s` 恒成立 ——
     * 不这么处理的话，"改最后一行"会把文件结尾的换行吃掉（diff 显示 \ No newline at end of file）。
     */
    internal fun splitLines(s: String): List<String> = if (s.isEmpty()) emptyList() else s.split("\n")

    internal fun joinLines(lines: List<String>): String = lines.joinToString("\n")
}
