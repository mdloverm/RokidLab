package com.rokidlab.phone.ai

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File

/**
 * 文件工作区 —— AI「文件操作」工具（列目录 / 读 / 搜 / 改 / 删 / 移动）的唯一落地层。
 *
 * ## 为什么要有"工作区"这个概念
 *
 * 本 App **没有** `MANAGE_EXTERNAL_STORAGE`：全盘任意路径读写在 Android 10+ 上根本做不到
 * （`File` API 对公开存储不可见，越权访问只会得到一个空目录而不是异常 —— 正好是最难排查的那种）。
 * 所以「读任意文件」不能按字面实现，只能先把**能力边界**定清楚，再让工具在这个边界内提供完整 CRUD。
 * 边界有两个，正好覆盖 App 自己产出的全部文件：
 *
 * | scope | 位置 | 支持的操作 |
 * |---|---|---|
 * | `project` | `filesDir/aiui_projects/<项目>/`（私有镜像，打包 .aix 时读它） | 列表 / 读 / 搜 / 追加 / 覆盖 / 删除 / 移动 |
 * | `downloads` | 系统下载目录（API29+ 走 MediaStore，公开可见） | 列表 / 读 / 搜 / 删除 |
 *
 * ⚠️ `downloads` 里**不能**改文件内容：MediaStore 的更新语义要求按 `_ID` 重写整份内容，
 * 而"追加"在 `IS_PENDING` 之外没有稳定做法，硬做只会在部分 ROM 上产生半截文件。
 * 需要改内容就在 `project` 作用域改（那才是工程源码的真相），再由打包装机流程同步。
 *
 * ## 禁区（改这个文件前必读）
 *
 * 所有路径都经 [resolveInProject] / [splitRel] 归一，并且：
 *  1. 拒绝 `..`、`.`、空段、绝对路径（`/` 开头）、盘符与 `\`；
 *  2. `project` 作用域最终必须**落在项目根目录内**（`canonicalPath` 前缀比对，
 *     防 `../..` 之类绕过）；`downloads` 作用域只认 `Download/` 下的相对路径；
 *  3. **删除时禁止空路径**（空 = 整个目录），要删整个项目必须显式传 `project` 且不带 path ——
 *     这条把"手一抖把工作区清空"从可能变成不可能；
 *  4. 删除/移动**不跟随符号链接**（用 `canonicalPath` 判定后按原路径操作）。
 */
object FileWorkspace {
    private const val TAG = "FileWorkspace"

    /** 私有项目镜像：完整 CRUD（工程源码的真相所在） */
    const val SCOPE_PROJECT = "project"

    /** 系统下载目录：列表 / 读 / 删（用户在文件管理器里也能看到的那份） */
    const val SCOPE_DOWNLOADS = "downloads"

    /** 单次读取回填给模型的上限（超出截断并说明，避免顶爆上下文） */
    private const val MAX_READ_CHARS = 60_000

    /** 单次写入/追加的内容上限 */
    private const val MAX_WRITE_CHARS = 200_000

    private const val MAX_LIST_ENTRIES = 200
    private const val MAX_SEARCH_HITS = 60

    /** 项目名规则：与 `WebTools.writeProjectFile` 逐字一致（同一份约束不能有两套） */
    private val PROJECT_NAME = Regex("[\\w\\-\\u4e00-\\u9fa5]{1,40}")

    /** 路径段规则：与 `WebTools.writeProjectFile` 逐字一致 */
    private val PATH_SEGMENT = Regex("^[\\w\\-\\u4e00-\\u9fa5][\\w.\\- \\u4e00-\\u9fa5]{0,80}$")

    // ═══════════════════════════════════════════════════════
    // 路径归一
    // ═══════════════════════════════════════════════════════

    /**
     * 拆分并校验相对路径。
     * @return null = 非法（空 / 含 `..` / 段名不合规 / 以 `/` 开头）
     */
    private fun splitRel(rel: String): List<String>? {
        val clean = rel.trim().replace('\\', '/').trim('/')
        if (clean.isEmpty()) return emptyList()
        val segs = clean.split('/')
        if (segs.any { it.isBlank() || it == "." || it == ".." }) return null
        if (segs.any { !PATH_SEGMENT.matches(it) }) return null
        return segs
    }

    /**
     * 在项目根目录内解析相对路径。
     * @return null = 项目名非法 / 项目不存在 / 路径非法 / 越出项目根
     */
    private fun resolveInProject(context: Context, project: String, rel: String): File? {
        val proj = project.trim()
        if (!PROJECT_NAME.matches(proj)) return null
        val segs = splitRel(rel) ?: return null
        val root = AiuiProject.projectDir(context, proj)
        if (!root.isDirectory) return null
        val target = if (segs.isEmpty()) root else File(root, segs.joinToString("/"))
        val rootPath = root.canonicalPath + File.separator
        val targetPath = runCatching { target.canonicalPath }.getOrNull() ?: return null
        if (targetPath != root.canonicalPath && !targetPath.startsWith(rootPath)) return null
        return target
    }

    /** 列出（项目名）→ 项目根；名非法或目录不存在返回 null */
    private fun projectRoot(context: Context, project: String): File? {
        val proj = project.trim()
        if (!PROJECT_NAME.matches(proj)) return null
        val dir = AiuiProject.projectDir(context, proj)
        return if (dir.isDirectory) dir else null
    }

    private fun scopeOf(raw: String): String =
        if (raw.trim().equals(SCOPE_DOWNLOADS, ignoreCase = true)) SCOPE_DOWNLOADS else SCOPE_PROJECT

    private fun relPathOf(file: File, root: File): String =
        file.relativeTo(root).path.replace('\\', '/')

    private fun humanSize(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> "${bytes / (1024 * 1024)}MB"
        bytes >= 1024 -> "${bytes / 1024}KB"
        else -> "${bytes}B"
    }

    // ═══════════════════════════════════════════════════════
    // 公开存储（下载目录）枚举
    // ═══════════════════════════════════════════════════════

    /** 下载目录里的一条记录（API29+ 来自 MediaStore；低版本来自应用专属目录） */
    private data class DownRow(val id: Long, val name: String, val rel: String, val size: Long)

    /**
     * 查询下载目录下的条目。
     * @param relPrefix 例：`Download/`（全部）或 `Download/项目名/`（某个项目）
     */
    private fun queryDownloads(context: Context, relPrefix: String): List<DownRow> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return emptyList()
        val uri = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val rows = mutableListOf<DownRow>()
        runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(
                    MediaStore.MediaColumns._ID,
                    MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.RELATIVE_PATH,
                    MediaStore.MediaColumns.SIZE,
                ),
                null,
                null,
                null,
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val relCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
                val sizeCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                while (c.moveToNext()) {
                    val rel = c.getString(relCol) ?: continue
                    if (!rel.startsWith(relPrefix)) continue
                    rows += DownRow(
                        id = c.getLong(idCol),
                        name = c.getString(nameCol) ?: continue,
                        rel = rel,
                        size = c.getLong(sizeCol),
                    )
                }
            }
        }.onFailure { Log.w(TAG, "queryDownloads($relPrefix) failed: ${it.message}") }
        return rows
    }

    /** 低版本（<29）兜底根目录：`writeProjectFile` 的公开副本就写在这里 */
    private fun legacyDownloadRoot(context: Context): File =
        context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir

    private fun legacyDownloadRows(context: Context, relPrefix: String): List<DownRow> {
        val base = legacyDownloadRoot(context)
        val prefix = relPrefix.removePrefix("${Environment.DIRECTORY_DOWNLOADS}/")
        val dir = if (prefix.isBlank()) base else File(base, prefix.trimEnd('/'))
        if (!dir.isDirectory) return emptyList()
        return (dir.listFiles() ?: emptyArray()).filter { it.isFile }.map {
            DownRow(0L, it.name, "", it.length())
        }
    }

    private fun deleteDownloadsUri(context: Context, row: DownRow): Boolean =
        runCatching {
            context.contentResolver.delete(
                ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, row.id),
                null,
                null,
            ) > 0
        }.getOrDefault(false)

    // ═══════════════════════════════════════════════════════
    // ① 列目录
    // ═══════════════════════════════════════════════════════

    /**
     * 列出工作区内的文件。
     * @param path 可选子目录（仅 project 作用域支持下钻）
     * @return 给模型的结果文本
     */
    fun listFiles(context: Context, scope: String, project: String, path: String): String =
        when (scopeOf(scope)) {
            SCOPE_DOWNLOADS -> listDownloads(context, project, path)
            else -> listProject(context, project, path)
        }

    private fun listProject(context: Context, project: String, path: String): String {
        val proj = project.trim()
        if (proj.isBlank()) return "列出失败：project 不能为空（要列出所有项目请传 path 为空并先说明项目名）"
        if (!PROJECT_NAME.matches(proj)) return "列出失败：项目名不合法（仅限中英文、数字、下划线、连字符，最长 40 字符）"
        val root = projectRoot(context, proj)
            ?: return "项目「$proj」不存在或还没有任何文件。可用 download 作用域查看下载目录，" +
                "或先用「保存代码文件」生成项目。"
        val dir = resolveInProject(context, proj, path)
            ?: return "列出失败：路径不合法或越出项目目录（$path）"
        if (!dir.isDirectory) return "「$proj/${path.trim('/')}」不是一个目录"
        val entries = (dir.listFiles() ?: emptyArray())
            .filter { !it.name.startsWith(".") }
            .sortedWith(compareBy({ !it.isDirectory }, { it.name }))
        if (entries.isEmpty()) return "「$proj/${relPathOf(dir, root)}」是空目录"
        val shown = entries.take(MAX_LIST_ENTRIES)
        val lines = shown.map { f ->
            val rel = relPathOf(f, root)
            if (f.isDirectory) "- $rel/（目录，${(f.listFiles()?.size ?: 0)} 项）"
            else "- $rel（${humanSize(f.length())}）"
        }
        val head = "项目「$proj」目录清单（${entries.size} 项" +
            (if (entries.size > shown.size) "，只显示前 ${shown.size} 项" else "") + "）：\n"
        return head + lines.joinToString("\n")
    }

    private fun listDownloads(context: Context, project: String, path: String): String {
        val proj = project.trim()
        val prefix = if (proj.isEmpty()) "${Environment.DIRECTORY_DOWNLOADS}/"
        else "${Environment.DIRECTORY_DOWNLOADS}/$proj/"
        val rows = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            queryDownloads(context, prefix)
        } else {
            legacyDownloadRows(context, prefix)
        }
        if (rows.isEmpty()) {
            return if (proj.isEmpty()) "系统下载目录里没有本 App 生成的文件"
            else "下载目录里没有「$proj」项目的公开副本"
        }
        val shown = rows.sortedBy { it.rel + it.name }.take(MAX_LIST_ENTRIES)
        val lines = shown.map { "- ${it.rel}${it.name}（${humanSize(it.size)}）" }
        val head = "系统下载目录清单（${rows.size} 项" +
            (if (rows.size > shown.size) "，只显示前 ${shown.size} 项" else "") + "）：\n"
        return head + lines.joinToString("\n") +
            "\n（下载目录只支持查看与删除；要改文件内容请在 project 作用域操作）"
    }

    // ═══════════════════════════════════════════════════════
    // ② 读文件
    // ═══════════════════════════════════════════════════════

    fun readTextFile(context: Context, scope: String, project: String, path: String): String =
        if (scopeOf(scope) == SCOPE_DOWNLOADS) readDownload(context, project, path)
        else readProject(context, project, path)

    private fun readProject(context: Context, project: String, path: String): String {
        val proj = project.trim()
        if (path.trim().isBlank()) {
            return "读取失败：path 不能为空（要列出目录请用「列出文件」工具）"
        }
        if (projectRoot(context, proj) == null) {
            return "项目「$proj」不存在或还没有可读取的源码"
        }
        val file = resolveInProject(context, proj, path)
            ?: return "读取失败：路径不合法或越出项目目录（$path）"
        if (!file.exists()) return "读取失败：「$proj/${path.trim('/')}」不存在"
        if (file.isDirectory) return "读取失败：「$proj/${path.trim('/')}」是目录，不是文件"
        // 解码走 TextEncoding（判定 BOM/UTF-8/GBK）而不是硬编码 UTF-8：
        // 中文用户手上的 txt 常是 GBK/UTF-16，硬解会读出一整篇替换符 —— 那时模型会
        // "诚实地"把乱码当内容转述给用户，比报错更糟
        val raw = runCatching { TextEncoding.decode(file.readBytes()) }.getOrElse {
            return "读取失败：无法读取 $proj/${path.trim('/')}（${it.message}）"
        }
        return if (raw.length > MAX_READ_CHARS) {
            "「$proj/${path.trim('/')}」共 ${raw.length} 字符，已截断为前 $MAX_READ_CHARS 字符：\n" +
                raw.take(MAX_READ_CHARS) +
                "\n\n（已截断。需要后面的内容请缩小 path 指向更具体的文件，或用「搜索文件」定位）"
        } else {
            "「$proj/${path.trim('/')}」完整内容（${raw.length} 字符）：\n$raw"
        }
    }

    private fun readDownload(context: Context, project: String, path: String): String {
        val rel = path.trim().replace('\\', '/').trim('/')
        if (rel.isEmpty()) return "读取失败：path 不能为空"
        val segs = splitRel(rel) ?: return "读取失败：路径不合法（$rel）"
        val fileName = segs.last()
        val dirSegs = segs.dropLast(1)
        val proj = project.trim().ifEmpty { dirSegs.firstOrNull().orEmpty() }
        if (proj.isNotBlank() && !PROJECT_NAME.matches(proj)) {
            return "读取失败：文件名/项目名不合法（$rel）"
        }
        val relDir = listOf(Environment.DIRECTORY_DOWNLOADS, proj).filter { it.isNotBlank() }
            .joinToString("/") + "/"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val row = queryDownloads(context, relDir).firstOrNull {
                it.name == fileName && it.rel == relDir
            } ?: return "下载目录里没有「$rel」"
            val text = runCatching {
                context.contentResolver.openInputStream(
                    ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, row.id),
                )?.use { TextEncoding.decode(it.readBytes()) }
            }.getOrNull() ?: return "读取失败：无法打开「$rel」（可能是其他 App 创建的文件，本 App 无权限）"
            return clipRead("下载/$rel", text)
        }
        val file = File(legacyDownloadRoot(context), rel)
        if (!file.isFile) return "下载目录里没有「$rel」"
        return clipRead("下载/$rel", runCatching { TextEncoding.decode(file.readBytes()) }.getOrDefault(""))
    }

    private fun clipRead(label: String, raw: String): String = if (raw.length > MAX_READ_CHARS) {
        "「$label」共 ${raw.length} 字符，已截断为前 $MAX_READ_CHARS 字符：\n" + raw.take(MAX_READ_CHARS)
    } else {
        "「$label」完整内容（${raw.length} 字符）：\n$raw"
    }

    // ═══════════════════════════════════════════════════════
    // ③ 搜文件
    // ═══════════════════════════════════════════════════════

    /** 按文件名关键词搜索（不搜正文）：项目镜像 + 下载目录 */
    fun searchFiles(context: Context, keyword: String, scope: String): String {
        val kw = keyword.trim()
        if (kw.isEmpty()) return "搜索失败：keyword 不能为空"
        // scope 原样取用（**不走 scopeOf**）：这里 all/空 = 两处都搜，
        // 而 scopeOf 会把一切非 downloads 归成 project，正好把 all 吃掉。
        val raw = scope.trim().lowercase()
        val wantProject = raw != SCOPE_DOWNLOADS
        val wantDownloads = raw != SCOPE_PROJECT
        val hits = mutableListOf<String>()
        if (wantProject) {
            val root = File(context.filesDir, "aiui_projects")
            root.listFiles()?.filter { it.isDirectory }?.forEach { proj ->
                proj.walkTopDown()
                    .filter { it.isFile && !it.name.startsWith(".") && it.name.contains(kw, ignoreCase = true) }
                    .take(MAX_SEARCH_HITS - hits.size)
                    .forEach { hits += "项目 ${proj.name}/${relPathOf(it, proj)}（${humanSize(it.length())}）" }
            }
        }
        if (wantDownloads) {
            val rows = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                queryDownloads(context, "${Environment.DIRECTORY_DOWNLOADS}/")
            } else {
                legacyDownloadRows(context, "${Environment.DIRECTORY_DOWNLOADS}/")
            }
            rows.filter { it.name.contains(kw, ignoreCase = true) }
                .take(MAX_SEARCH_HITS - hits.size)
                .forEach { hits += "下载 ${it.rel}${it.name}（${humanSize(it.size)}）" }
        }
        if (hits.isEmpty()) return "没有找到文件名包含「$kw」的文件"
        return "文件名包含「$kw」的文件（${hits.size} 个）：\n" + hits.joinToString("\n")
    }

    // ═══════════════════════════════════════════════════════
    // ④ 改文件（追加 / 覆盖）—— 仅 project 作用域
    // ═══════════════════════════════════════════════════════

    /**
     * @param mode `append`（追加到末尾）/ `overwrite`（整文件替换）
     */
    fun editTextFile(
        context: Context,
        project: String,
        path: String,
        mode: String,
        content: String,
    ): String {
        val proj = project.trim()
        if (proj.isBlank()) return "修改失败：project 不能为空"
        if (path.trim().isBlank()) return "修改失败：path 不能为空"
        if (content.isEmpty()) return "修改失败：content 不能为空"
        if (content.length > MAX_WRITE_CHARS) {
            return "修改失败：内容过大（${content.length} 字符 > $MAX_WRITE_CHARS），请拆小后分次写入"
        }
        if (projectRoot(context, proj) == null) {
            return "修改失败：项目「$proj」不存在。请先用「保存代码文件」生成项目，或确认项目名"
        }
        val file = resolveInProject(context, proj, path)
            ?: return "修改失败：路径不合法或越出项目目录（$path）"
        if (file.isDirectory) return "修改失败：「$proj/${path.trim('/')}」是目录"
        val appending = !mode.trim().equals("overwrite", ignoreCase = true)
        if (appending && !file.isFile) {
            return "修改失败：要追加的文件不存在。追加只能用于已有文件；新建文件请用「保存代码文件」"
        }
        val old = if (file.isFile) runCatching { TextEncoding.decode(file.readBytes()) }.getOrDefault("") else ""
        val merged = if (appending) old + content else content
        if (merged.length > MAX_WRITE_CHARS) {
            return "修改失败：合并后共 ${merged.length} 字符 > $MAX_WRITE_CHARS，请拆小或改用覆盖模式"
        }
        return runCatching {
            file.parentFile?.mkdirs()
            file.writeText(merged, Charsets.UTF_8)
            val act = if (appending) "已追加" else "已覆盖"
            "$act「$proj/${path.trim('/')}」（${old.length} → ${merged.length} 字符）。" +
                "注意：这是项目镜像（App 私有），要同步到手机下载目录请重新「保存代码文件」或安装 AIUI 项目。"
        }.getOrElse { "修改失败：写入出错（${it.message}）" }
    }

    // ═══════════════════════════════════════════════════════
    // ⑤ 删文件
    // ═══════════════════════════════════════════════════════

    /**
     * 删除文件 / 目录。
     *
     * ⚠️ [path] 为空 = 删除**整个项目**（含下载目录里的公开副本），这是唯一允许的"整目录删除"，
     *    且必须显式传 project；其余情况一律要求非空路径（见类注释的禁区第 3 条）。
     */
    fun deleteEntry(context: Context, scope: String, project: String, path: String): String =
        if (scopeOf(scope) == SCOPE_DOWNLOADS) deleteDownload(context, project, path)
        else deleteProject(context, project, path)

    private fun deleteProject(context: Context, project: String, path: String): String {
        val proj = project.trim()
        if (proj.isBlank()) return "删除失败：project 不能为空（不允许删除整个工作区）"
        if (!PROJECT_NAME.matches(proj)) return "删除失败：项目名不合法（$proj）"
        val root = projectRoot(context, proj)
        if (root == null) {
            // 项目镜像不在，可能只剩公开副本
            val pub = WebTools.deleteDownloadedProject(context, proj)
            return if (pub > 0) "项目「$proj」没有工程镜像，已删除下载目录里的 $pub 个公开文件"
            else "删除失败：项目「$proj」不存在"
        }
        if (path.trim().isBlank()) {
            val mirrorOk = runCatching { root.deleteRecursively() }.getOrDefault(false)
            val pub = runCatching { WebTools.deleteDownloadedProject(context, proj) }.getOrDefault(0)
            Log.i(TAG, "delete project $proj: mirror=$mirrorOk public=$pub")
            return if (mirrorOk) {
                "已删除项目「$proj」（工程镜像 + 下载目录里的 $pub 个公开文件），不可恢复"
            } else {
                "删除项目「$proj」失败（工程镜像未能完整删除）"
            }
        }
        val target = resolveInProject(context, proj, path)
            ?: return "删除失败：路径不合法或越出项目目录（$path）"
        if (!target.exists()) return "删除失败：「$proj/${path.trim('/')}」不存在"
        val label = "$proj/${path.trim('/')}"
        val ok = if (target.isDirectory) runCatching { target.deleteRecursively() }.getOrDefault(false)
        else runCatching { target.delete() }.getOrDefault(false)
        return if (ok) "已删除「$label」，不可恢复" else "删除「$label」失败"
    }

    private fun deleteDownload(context: Context, project: String, path: String): String {
        val rel = path.trim().replace('\\', '/').trim('/')
        if (rel.isEmpty()) return "删除失败：path 不能为空（不允许清空下载目录）"
        val segs = splitRel(rel) ?: return "删除失败：路径不合法（$rel）"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val relDir = "${Environment.DIRECTORY_DOWNLOADS}/" + segs.dropLast(1).joinToString("/") +
                if (segs.size > 1) "/" else "/"
            val matched = queryDownloads(context, relDir).filter { it.name == segs.last() }
            val dirMatched = queryDownloads(context, "${Environment.DIRECTORY_DOWNLOADS}/$rel/")
            val targets = matched + dirMatched
            if (targets.isEmpty()) return "下载目录里没有「$rel」"
            var n = 0
            targets.forEach { if (deleteDownloadsUri(context, it)) n++ }
            if (n > 0) "已从下载目录删除「$rel」（$n 个文件），不可恢复" else "删除「$rel」失败"
        } else {
            val file = File(legacyDownloadRoot(context), rel)
            if (!file.exists()) return "下载目录里没有「$rel」"
            val ok = runCatching {
                if (file.isDirectory) file.deleteRecursively() else file.delete()
            }.getOrDefault(false)
            if (ok) "已从下载目录删除「$rel」，不可恢复" else "删除「$rel」失败"
        }
    }

    // ═══════════════════════════════════════════════════════
    // ⑥ 移动 / 重命名 —— 仅 project 作用域
    // ═══════════════════════════════════════════════════════

    fun moveEntry(context: Context, project: String, from: String, to: String): String {
        val proj = project.trim()
        if (proj.isBlank()) return "移动失败：project 不能为空"
        if (from.trim().isBlank()) return "移动失败：from 不能为空"
        if (to.trim().isBlank()) return "移动失败：to 不能为空"
        if (projectRoot(context, proj) == null) return "移动失败：项目「$proj」不存在"
        val src = resolveInProject(context, proj, from)
            ?: return "移动失败：源路径不合法或越出项目目录（$from）"
        val dst = resolveInProject(context, proj, to)
            ?: return "移动失败：目标路径不合法或越出项目目录（$to）"
        if (!src.exists()) return "移动失败：源「$from」不存在"
        if (dst.exists()) return "移动失败：目标「$to」已存在，请换一个名字（本工具不覆盖）"
        return runCatching {
            dst.parentFile?.mkdirs()
            val ok = src.renameTo(dst)
            if (ok) "已移动「$from」→「$to」（项目镜像内，不可通过撤销恢复）"
            else "移动失败：重命名未成功（跨目录移动请先确认目标目录可写）"
        }.getOrElse { "移动失败：${it.message}" }
    }
}
