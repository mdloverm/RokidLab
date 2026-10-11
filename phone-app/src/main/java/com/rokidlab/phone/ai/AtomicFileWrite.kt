package com.rokidlab.phone.ai

import android.util.Log
import java.io.File
import java.io.IOException

/**
 * **原子**写文本文件：同目录临时文件 → `renameTo` 覆盖。
 *
 * ## 为什么全仓只此一份（2026-10-05 审计修）
 * 审计发现两处各写一份非原子写：
 * - [FileWorkspace.editTextFile] —— 「读旧 + 拼新 + 写回」的读改写序列，
 *   崩溃/磁盘满会留下**半截或 0 字节**的 AIUI 工程源码（append 模式还会丢掉原有内容，
 *   本工具没有撤销）。而这些文件是工程真相所在，损坏后只能让 AI 重新生成整个工程。
 * - [WebTools.writeProjectFile] 的私有镜像落盘 —— 同样会**静默**把损坏带到眼镜端
 *   （模型收到「已生成」就继续下一步，排查时早已看不出是写入那一瞬间崩的）。
 *
 * 📌 **同一份约束不能有两套实现**（`FileWorkspace` / `WebTools` 的路径正则已是这个规矩）。
 * 故抽到本文件，两个调用方都走这里。
 *
 * ## 为什么是「同目录 + rename」，而不是 `File.createTempFile`
 * [File.renameTo] 只在**同一文件系统内**才是原子的。临时文件必须落在**同目录**（同挂载点）：
 * 用 `context.cacheDir` 或 `createTempFile`（默认落在 `java.io.tmpdir`，在 Android 上
 * 可能跨到别的分区）就退化成「复制 + 删除」，中途失败反而更糟。
 *
 * ## 为什么不 `recycle` 之类的额外补偿
 * `renameTo` 不抛异常、只返回 false（Android 上覆盖已有文件的行为依ROM 而异），
 * 所以两条失败路径都要显式处理：临时文件删掉 + 抛可读异常（由调用方转成给模型看的文案）。
 * 这也顺带保证了：**任何失败路径都不会在工作区留下半截临时文件**（`list_files` 看得见它）。
 *
 * ## ★ 为什么**不能**先`target.delete()` 再 rename（2026-10-06 审计修）
 * 本实现原先有一句 `if (target.exists() && !target.delete())`，注释说"先删目标，让 rename
 * 走新建路径"。但那正是 [ScriptStore] 早就在注释里**明令禁止**的做法：
 * > **不要**先 `dst.delete()` 再 rename：delete 与 rename 之间被杀会让**整个文件消失**
 *
 * 两者都是"读改写"序列的落盘末端，delete 与 rename 之间存在一个**目标不存在的窗口**；
 * 进程在这个窗口里被 LMK 杀掉/用户杀进程，结果不是半截文件，而是**文件彻底消失** ——
 * 比写坏更糟（写坏还能让模型重新生成，彻底消失则连"原本的内容"都没了）。
 * 本实现的原注释还宣称这样"防中途被杀"，实际是把自己要防的那件事做成了。
 *
 * ### 修法：直接用 POSIX 覆盖式原子 rename
 * `rename(2)` 本身就是**覆盖式原子操作**，根本不需要先删 —— 同一文件系统内要么成功、
 * 要么失败，中间态不可观测。主路径用 [java.nio.file.Files.move] + `ATOMIC_MOVE`（明确要求
 * 覆盖语义），个别 ROM 的 FUSE 上不支持时退回 `tmp.renameTo(target)`
 * （同目录 rename 在 POSIX 上同样是覆盖式原子），**并检查返回值**。
 *
 * 📌 这套实现是从 [ScriptStore.replaceAtomically] **上提**到本文件的 ——
 * 同一份"原子替换"约束不该有两个实现，且原来那个（带 delete 窗口的）是错的。
 *
 * 可见性为 `internal` 而非 `private`：单测需要直接锁住「不留残留 / 覆盖生效 / 失败不吞 /
 * 覆盖写不丢原文件（无 delete 窗口）」四条。
 */
internal fun writeTextFileAtomically(target: File, content: String) {
    val dir = target.absoluteFile.parentFile
        ?: throw IOException("无法确定目标文件所在目录：${target.path}")
    // 临时名带 nanoTime 后缀：同目录下并发写同一文件不会互相踩
    //（AI 同一轮里两次 edit 同一路径是有可能的）
    val tmp = File(dir, ".${target.name}.${System.nanoTime()}.tmp")
    try {
        tmp.writeText(content, Charsets.UTF_8)
        if (!replaceAtomically(tmp, target)) {
            throw IOException("临时文件无法重命名为目标文件")
        }
    } finally {
        // 成功路径下 tmp 已不存在；失败路径下清掉半截临时文件
        if (tmp.exists()) tmp.delete()
    }
}

/**
 * 用 [tmp] 覆盖 [dst]，返回是否成功。**全程不出现"目标不存在"的窗口**。
 *
 * 主路径 `Files.move(ATOMIC_MOVE)`；个别 ROM 的 FUSE 上不支持时退回 `renameTo`
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
    Log.w("AtomicFileWrite", "atomic move unsupported, falling back to renameTo(): ${e.message}")
    tmp.renameTo(dst)
}

// ═══════════════════════════════════════════════════════════════
// 备份层（2026-10-06）
// ═══════════════════════════════════════════════════════════════

/**
 * 备份文件名的**统一格式**与解析（纯逻辑，`internal` 便于单测锁住往返一致）。
 *
 * ## 格式：`.<原文件名>.<时间戳毫秒>.bak`
 * 三个约束各解决一个具体问题：
 * 1. **点开头** ⇒ [FileWorkspace.listProject] 已有的 `!it.name.startsWith(".")` 过滤
 *    会自动把备份藏起来 ⇒ 用户列目录看不到一堆 `.bak`，模型也不会被干扰。
 * 2. **时间戳在扩展名之前** ⇒ 按文件名排序即按时间排序，清理时挑最旧的很简单。
 * 3. **`.bak` 结尾** ⇒ 与 `.tmp`（[writeTextFileAtomically] 的中间态）明确区分开。
 *
 * ⚠️ 备份**不做**内容寻址（hash）：同一份内容改两次会产生两个同名不同戳的备份，
 * 这在「撤销到某个版本」的场景下反而是有用的（用户能看到改了两次）。
 */
internal object FileBackupNaming {
    const val SUFFIX = ".bak"

    fun backupName(originalName: String, timestampMillis: Long): String =
        ".$originalName.$timestampMillis$SUFFIX"

    /**
     * 同毫秒内连续覆盖时的**去重命名**：从 [timestampMillis] 起逐 +1ms，直到 [isTaken] 不再命中。
     *
     * ⚠️ 为什么必须有这一步（2026-10-11 修）：`writeTextFileWithBackup` 读写都在毫秒级完成，
     * AI 快速试错时**两次覆盖常落在同一毫秒** —— 撞车的备份名会互相覆盖，结果是
     * 「改了三次只留下一份备份」，既违背「每次覆盖都留一份」的契约，也让
     * `pruneBackups` 的上限恒达不到（`FileBackupTest` 在快机器上必现）。
     * +1ms 递增仍保持「名字排序 = 时间排序」，清理逻辑不变。
     *
     * @param isTaken 该备份名是否已被占用（调用方查目录）；重载无参版不查占用，单测直接调时行为不变
     */
    fun backupName(originalName: String, timestampMillis: Long, isTaken: (String) -> Boolean): String {
        var ts = timestampMillis
        var name = backupName(originalName, ts)
        while (isTaken(name)) {
            ts += 1
            name = backupName(originalName, ts)
        }
        return name
    }

    /** 从备份名还原被备份的文件名；不是备份名返回 null */
    fun originalNameOf(backupName: String): String? {
        // ⚠️ 长度校验必须在 substring **之前**：像 ".bak"（正好 4 字符）这种输入，
        // 前两个 startsWith/endsWith 都通过，但 substring(1, 0) 会抛
        // StringIndexOutOfBoundsException —— 而这个函数被 pruneBackups 用来
        // 过滤目录里的**每一个**文件名，抛异常等于整个清理流程崩掉。
        if (backupName.length <= 1 + SUFFIX.length) return null
        if (!backupName.startsWith(".") || !backupName.endsWith(SUFFIX)) return null
        val mid = backupName.substring(1, backupName.length - SUFFIX.length)
        val dot = mid.lastIndexOf('.')
        if (dot <= 0) return null
        val name = mid.substring(0, dot)
        val ts = mid.substring(dot + 1).toLongOrNull() ?: return null
        if (ts <= 0L) return null
        return name
    }

    /** 备份时间戳；不是备份名返回 null */
    fun timestampOf(backupName: String): Long? {
        if (originalNameOf(backupName) == null) return null
        return backupName.removePrefix(".")
            .removeSuffix(SUFFIX)
            .substringAfterLast('.')
            .toLongOrNull()
    }

    fun isBackup(name: String): Boolean = originalNameOf(name) != null
}

/**
 * 每个文件最多保留几个备份（**资源类不变量**：备份是"越攒越多"的东西，没有上限
 * 就是准定时炸弹 —— AI 在一个文件上反复试错几十次就会把用户存储吃满）。
 * 超出后按时间戳删最旧的。
 */
internal const val MAX_BACKUPS_PER_FILE = 5

/**
 * 原子写 + **先备份原文**。
 *
 * ## 为什么需要它
 * [writeTextFileAtomically] 只保证**不出现半截文件**，它 KDoc 里那句「本工具没有撤销」
 * 在 AI 上下文里的真实含义是：**一次错误覆写 = 整个文件内容永久丢失，只能让模型重新生成**。
 * 引入 `apply_patch` 式增量编辑后风险只会更高（改动是局部的，但一旦上下文定位错，
 * 写回去的东西可能正好抹掉了不该动的那几行）。有备份才能把「改错了」变成「改错了，退回去」。
 *
 * ## 为什么不在这里做「失败自动回滚」
 * 因为**失败的不是写，而是模型想写的内容** —— 写入技术上成功、语义上错了，
 * 这种错只有模型自己能发现（它要读回来看）。所以正确的分工是：
 * 本层负责**留后路**（写之前一定有 `.bak`），由模型显式调 `restore_file` 回来退。
 *
 * @return 备份文件名（不含目录）；**null = 原文件不存在，本次没有可备份的内容**（新建文件）
 * @throws IOException 备份失败或写入失败。⚠️ 备份失败**必须**抛异常而不能继续写 ——
 *   静默跳过备份等于把"唯一的后路"悄悄丢掉，用户和模型都不会知道。
 */
internal fun writeTextFileWithBackup(target: File, content: String): String? {
    val backupName: String? = if (target.isFile) {
        val dir = target.absoluteFile.parentFile
            ?: throw IOException("无法确定目标文件所在目录：${target.path}")
        val stamp = System.currentTimeMillis()
        // 加 isTaken 查重：同毫秒内的连续覆盖不会撞名互相覆盖（见 backupName 重载的 KDoc）
        val name = FileBackupNaming.backupName(target.name, stamp) { File(dir, it).exists() }
        val backup = File(dir, name)
        // 备份本身也走原子写：一次「备份到一半」的备份文件比没有备份更坏 ——
        // restore 会拿它当真相源，把损坏内容"恢复"成正常样子。
        writeTextFileAtomically(backup, target.readText(Charsets.UTF_8))
        pruneBackups(dir, target.name, keep = MAX_BACKUPS_PER_FILE)
        name
    } else {
        null
    }
    writeTextFileAtomically(target, content)
    return backupName
}

/**
 * 清理某个文件的旧备份，只保留时间戳最新的 [keep] 个。
 *
 * 判据用**文件名排序**而不是 [File.lastModified]：备份名里已经带时间戳，
 * 而同一次写入里 create 与 mtime 可能差几毫秒，按 mtime 排序在极端情况下会把
 * 刚写的那个当最旧的删掉。文件名排序是确定性的。
 */
internal fun pruneBackups(dir: File, originalName: String, keep: Int) {
    val backups = (dir.listFiles() ?: emptyArray())
        .filter { FileBackupNaming.originalNameOf(it.name) == originalName }
        .sortedBy { it.name }
    if (backups.size <= keep) return
    backups.take(backups.size - keep).forEach { runCatching { it.delete() } }
}

/** 列出某个文件的所有备份（时间戳**新 → 旧**），供 `restore_file` 与单测使用 */
internal fun listBackups(dir: File, originalName: String): List<File> =
    (dir.listFiles()?.toList() ?: emptyList())
        .filter { FileBackupNaming.originalNameOf(it.name) == originalName }
        .sortedBy { it.name }
        .reversed()
