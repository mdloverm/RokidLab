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
 * 本 App 虽然声明了 `MANAGE_EXTERNAL_STORAGE`（`AppPermission.ALL_FILES`），但那是**给 proot 容器通道用的**：
 * 容器把 `Download/Lab` 用内核 `write()` 直连绑定成 `/mnt/lab`，需要该权限才能落到共享存储的真实路径上。
 * 这**不**代表文件工具可以读写全盘：本层刻意不走 `File` API 直接改公开存储 —— Android 10+ 上那对公开存储
 * 不可见/语义不稳（越权访问只会得到一个空目录而不是异常，正好是最难排查的那种）。
 * 所以「读任意文件」不按字面实现，只先把**能力边界**定清楚，再让工具在这个边界内提供完整 CRUD。
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
     * @return null = 项目不存在 / 路径非法 / 越出项目根
     *
     * `internal`（而非 private）：压缩/解压（[ArchiveTools]）也要按**同一套**路径规则
     * 定位项目文件 —— 复制一份规则出来迟早会与这份分叉（本文件的类注释已把"同一份约束
     * 不能有两套"写成了硬约束）。
     */
    internal fun resolveInProject(context: Context, project: String, rel: String): File? {
        val segs = splitRel(rel) ?: return null
        val root = projectRoot(context, project) ?: return null
        val target = if (segs.isEmpty()) root else File(root, segs.joinToString("/"))
        val rootPath = root.canonicalPath + File.separator
        val targetPath = runCatching { target.canonicalPath }.getOrNull() ?: return null
        if (targetPath != root.canonicalPath && !targetPath.startsWith(rootPath)) return null
        return target
    }

    /**
     * （项目名）→ 项目根目录；解析不出或目录不存在返回 null（`internal`：见 [resolveInProject]）。
     *
     * ⚠️ 名字先经 [AiuiProject.resolveProjectName] 归一：用户是按**应用名**叫项目的
     * （可能是中文显示名或带后缀的登记名，如「AIUI 自检 (aiui-selftest)」），而目录名是
     * **文件夹名**（`aiui-selftest`），两者经常不一致 —— 不归一就会出现
     * "让它改 AIUI 项目总说找不到"（2026-10-10 用户反馈）。
     */
    internal fun projectRoot(context: Context, project: String): File? {
        val resolved = AiuiProject.resolveProjectName(context, project) ?: return null
        val dir = AiuiProject.projectDir(context, resolved)
        return if (dir.isDirectory) dir else null
    }

    /**
     * 「项目不存在」时给模型的**可执行**提示：列出本机真实项目文件夹名。
     *
     * 为什么不只说"不存在"：模型拿不到候选名只能反复猜/换个说法再试（真机上就是这样循环报错的）。
     * 列出真实文件夹名后它下一轮就能直接用对。
     *
     * `internal`：WebTools（[WebTools.readProjectFile] / [WebTools.writeProjectFile]）也用同一句，
     * 同一份措辞不该有两处实现。
     */
    internal fun availableProjectsHint(context: Context): String {
        val names = File(context.filesDir, "aiui_projects").listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith(".") }
            ?.map { it.name }
            ?.sorted()
            .orEmpty()
        return if (names.isEmpty()) {
            "本机还没有任何项目，请先用「保存代码文件」生成。"
        } else {
            "本机现有项目：${names.joinToString("、")}（请直接用其中的**项目文件夹名**）。"
        }
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

    /**
     * 打开下载目录里某个文件的**字节流**（给解压/校验这类二进制场景用；文本读取走 [readTextFile]）。
     *
     * 存在的理由：下载目录在 API29+ 是 MediaStore 里的行，只能通过 `contentResolver` 打开，
     * 「找行 → 开流」这套细节只该有一份实现（[ArchiveTools] 解压时就靠它）。
     *
     * @param relPath 相对下载目录的路径，如 `app.zip` 或 `资料/app.zip`
     * @return 可读流；文件不存在、或该行是别的 App 创建的（本 App 无权限）时返回 null
     */
    internal fun openDownloadInput(context: Context, relPath: String): java.io.InputStream? {
        val segs = splitRel(relPath) ?: return null
        if (segs.isEmpty()) return null
        val name = segs.last()
        val relDir = "${Environment.DIRECTORY_DOWNLOADS}/" + segs.dropLast(1).joinToString("/") +
            if (segs.size > 1) "/" else ""
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val row = queryDownloads(context, relDir).firstOrNull { it.name == name } ?: return null
            return runCatching {
                context.contentResolver.openInputStream(
                    ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, row.id),
                )
            }.getOrNull()
        }
        val file = File(legacyDownloadRoot(context), segs.joinToString("/"))
        return if (file.isFile) runCatching { file.inputStream() }.getOrNull() else null
    }

    /**
     * 打开下载目录里某个文件的**可随机读**句柄（`PdfRenderer` 这类要求可 seek 的场景用；
     * 顺序读走 [openDownloadInput]）。
     *
     * 存在的理由与 [openDownloadInput] 相同：「找行 → 开句柄」这套 MediaStore 细节只该有一份实现。
     *
     * @return 可读句柄；文件不存在、或该行是别的 App 创建的（本 App 无权限）时返回 null
     */
    internal fun openDownloadFd(context: Context, relPath: String): android.os.ParcelFileDescriptor? {
        val segs = splitRel(relPath) ?: return null
        if (segs.isEmpty()) return null
        val name = segs.last()
        val relDir = "${Environment.DIRECTORY_DOWNLOADS}/" + segs.dropLast(1).joinToString("/") +
            if (segs.size > 1) "/" else ""
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val row = queryDownloads(context, relDir).firstOrNull { it.name == name } ?: return null
            return runCatching {
                context.contentResolver.openFileDescriptor(
                    ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, row.id),
                    "r",
                )
            }.getOrNull()
        }
        val file = File(legacyDownloadRoot(context), segs.joinToString("/"))
        if (!file.isFile) return null
        return runCatching {
            android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
        }.getOrNull()
    }

    /** 低版本（<29）下载目录根 → `File`（`internal`：见 [openDownloadInput]） */
    internal fun legacyRoot(context: Context): File = legacyDownloadRoot(context)

    /**
     * 下载目录里某个文件的**绝对路径**（产物芯片用，2026-10-13）。
     *
     * ## 为什么需要它
     * [ArchiveTools.zipFiles] 与 [WebTools.saveSummary] 把产物写进**下载目录**，
     * 而 `AgentStep.ARTIFACT_SPECS` 只能从**调用参数**推路径 —— 这两者的入参里没有产物路径
     * （名字是 `zipName` / `title` 经 sanitize 之后的），硬推会指向输入甚至不存在的文件。
     * 所以路径由工具侧在写成功后经 [ProducedFileSink] 回报，本函数是那个路径的**唯一产地**：
     * 与 [ArchiveTools.writeDownloadFile] 的落盘位置保持一一对应（API29+ 走 MediaStore
     * 的公共下载目录，低版本走应用专属目录）。
     *
     * ⚠️ 刻意**不用** `MediaStore` 查询反查（`DATA` 列已废弃，且同名文件可能被系统改名）：
     * 落盘代码已经先删同名行，路径可预期，直接拼即可。
     */
    internal fun downloadAbsPath(context: Context, name: String): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                name,
            ).absolutePath
        } else {
            File(legacyDownloadRoot(context), name).absolutePath
        }

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
        if (proj.isBlank()) {
            // ★ 不传 project = 看**本机有哪些项目**（2026-10-10 用户反馈："让它改某个项目总说找不到"）：
            //   模型在改之前最需要的就是这份清单。原先这里直接报"project 不能为空"，
            //   模型只能猜名字 → 猜错 → 再猜，正是"找不到"循环的起点。
            val root = File(context.filesDir, "aiui_projects")
            val dirs = root.listFiles()
                ?.filter { it.isDirectory && !it.name.startsWith(".") }
                ?.sortedBy { it.name }
                .orEmpty()
            if (dirs.isEmpty()) return "本机还没有任何项目，请先用「保存代码文件」生成。"
            val lines = dirs.map { d ->
                val n = d.walkTopDown().count { it.isFile && !it.name.startsWith(".") }
                "- ${d.name}（$n 个文件）"
            }
            return "本机项目清单（${dirs.size} 个）：\n" + lines.joinToString("\n") +
                "\n要看某个项目的文件明细，请传 project=<上面的名字>；下载目录用 scope=\"downloads\"。"
        }
        val root = projectRoot(context, proj)
            ?: return "项目「$proj」不存在或还没有任何文件。${availableProjectsHint(context)}" +
                "（下载目录里的文件请用 scope=\"downloads\" 查看）"
        val projName = root.name
        val dir = resolveInProject(context, projName, path)
            ?: return "列出失败：路径不合法或越出项目目录（$path）"
        if (!dir.isDirectory) return "「$projName/${path.trim('/')}」不是一个目录"
        val entries = (dir.listFiles() ?: emptyArray())
            .filter { !it.name.startsWith(".") }
            .sortedWith(compareBy({ !it.isDirectory }, { it.name }))
        if (entries.isEmpty()) return "「$projName/${relPathOf(dir, root)}」是空目录"
        val shown = entries.take(MAX_LIST_ENTRIES)
        val lines = shown.map { f ->
            val rel = relPathOf(f, root)
            if (f.isDirectory) "- $rel/（目录，${(f.listFiles()?.size ?: 0)} 项）"
            else "- $rel（${humanSize(f.length())}）"
        }
        val head = "项目「$projName」目录清单（${entries.size} 项" +
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
        // ★ 隐藏/系统条目过滤（2026-10-09，P2-13）：真机上 `list_files(scope="downloads")`
        // 曾返回 **5309 项**（只显示前 200 行），把整个下载目录的隐藏系统树
        // （`.7934039a/.u/u0/0/1/1…` 这类）连同用户文件一起灌进模型上下文 ——
        // 一次调用就吃掉大量 window，还让模型误以为"这里有几千个相关文件"。
        // 判据：**路径里任意一段以 `.` 开头**即视为隐藏/系统条目，不进清单（也不计入总数）。
        val visible = rows.filterNot { r ->
            (r.rel + r.name).split('/').any { seg -> seg.startsWith(".") }
        }
        val hiddenCount = rows.size - visible.size
        if (visible.isEmpty()) {
            return "系统下载目录里没有可展示的文件" +
                (if (hiddenCount > 0) "（已过滤 $hiddenCount 项隐藏/系统条目）" else "")
        }
        val shown = visible.sortedBy { it.rel + it.name }.take(MAX_LIST_ENTRIES)
        val lines = shown.map { "- ${it.rel}${it.name}（${humanSize(it.size)}）" }
        val head = "系统下载目录清单（${visible.size} 项" +
            (if (visible.size > shown.size) "，只显示前 ${shown.size} 项" else "") +
            (if (hiddenCount > 0) "；已过滤 $hiddenCount 项隐藏/系统条目" else "") + "）：\n"
        return head + lines.joinToString("\n") +
            if (visible.size > shown.size) {
                // 条目过多时给出**可执行的收窄建议**：只列一部分等于让模型猜，不如告诉它怎么缩小范围
                "\n（条目较多，请传 project 只看某一个项目，或先用 search_files 按文件名搜索；" +
                    "下载目录只支持查看与删除，要改文件内容请在 project 作用域操作）"
            } else {
                "\n（下载目录只支持查看与删除；要改文件内容请在 project 作用域操作）"
            }
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
        val root = projectRoot(context, proj)
            ?: return "项目「$proj」不存在或还没有可读取的源码。${availableProjectsHint(context)}"
        val projName = root.name
        val file = resolveInProject(context, projName, path)
            ?: return "读取失败：路径不合法或越出项目目录（$path）"
        if (!file.exists()) return "读取失败：「$projName/${path.trim('/')}」不存在"
        if (file.isDirectory) return "读取失败：「$projName/${path.trim('/')}」是目录，不是文件"
        // 解码走 TextEncoding（判定 BOM/UTF-8/GBK）而不是硬编码 UTF-8：
        // 中文用户手上的 txt 常是 GBK/UTF-16，硬解会读出一整篇替换符 —— 那时模型会
        // "诚实地"把乱码当内容转述给用户，比报错更糟
        val raw = runCatching { TextEncoding.decode(file.readBytes()) }.getOrElse {
            return "读取失败：无法读取 $projName/${path.trim('/')}（${it.message}）"
        }
        val label = "$projName/${path.trim('/')}"
        return if (raw.length > MAX_READ_CHARS) {
            "「$label」共 ${raw.length} 字符，已截断为前 $MAX_READ_CHARS 字符：\n" +
                raw.take(MAX_READ_CHARS) +
                "\n\n（已截断。需要后面的内容请缩小 path 指向更具体的文件，或用「搜索文件」定位）"
        } else {
            "「$label」完整内容（${raw.length} 字符）：\n$raw"
        }
    }

    /**
     * 下载作用域的**目录解析**（纯函数，`internal` 以便单测直接锁住规则）。
     *
     * ⚠️ 目录必须按**完整路径**拼，不能只取第一段当项目名：
     * [ArchiveTools.unzipFile] 解出来的包常有多级子目录（如 `app/pages/index/index.ink`），
     * 只取首段会定位到 `Download/app/` ⇒ 症状是"能解压、list_files 看得见、就是读不出内容"，
     * 而两个工具的 schema 互相承诺了这个闭环。
     *
     * 两种调用风格都认：path 自带项目名（schema 文档里的写法 `项目名/app.json`），
     * 或另传 `project` 参数（此时补在路径前面）。
     *
     * @return `(MediaStore 的 RELATIVE_PATH 前缀 "Download/a/b/", 给用户看的相对路径 "a/b/file.txt")`；
     *   null = 项目名或路径不合法
     */
    internal fun resolveDownloadPath(project: String, path: String): Pair<String, String>? {
        val rel = path.trim().replace('\\', '/').trim('/')
        if (rel.isEmpty()) return null
        val segs = splitRel(rel) ?: return null
        val fileName = segs.last()
        val dirSegs = segs.dropLast(1)
        val proj = project.trim()
        if (proj.isNotEmpty() && !PROJECT_NAME.matches(proj)) return null
        val fullDir = if (proj.isNotEmpty() && dirSegs.firstOrNull() != proj) {
            listOf(proj) + dirSegs
        } else {
            dirSegs
        }
        val relDir = buildString {
            append(Environment.DIRECTORY_DOWNLOADS).append('/')
            if (fullDir.isNotEmpty()) append(fullDir.joinToString("/")).append('/')
        }
        return relDir to (fullDir + fileName).joinToString("/")
    }

    private fun readDownload(context: Context, project: String, path: String): String {
        val resolved = resolveDownloadPath(project, path)
            ?: return if (path.trim().isBlank()) "读取失败：path 不能为空"
            else "读取失败：路径不合法（$path）"
        val (relDir, display) = resolved
        val fileName = display.substringAfterLast('/')

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val row = queryDownloads(context, relDir).firstOrNull {
                it.name == fileName && it.rel == relDir
            } ?: return "下载目录里没有「$display」"
            val text = runCatching {
                context.contentResolver.openInputStream(
                    ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, row.id),
                )?.use { TextEncoding.decode(it.readBytes()) }
            }.getOrNull() ?: return "读取失败：无法打开「$display」（可能是其他 App 创建的文件，本 App 无权限）"
            return clipRead("下载/$display", text)
        }
        val file = File(legacyDownloadRoot(context), display)
        if (!file.isFile) return "下载目录里没有「$display」"
        return clipRead("下载/$display", runCatching { TextEncoding.decode(file.readBytes()) }.getOrDefault(""))
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
        val root = projectRoot(context, proj)
            ?: return "修改失败：项目「$proj」不存在。${availableProjectsHint(context)}"
        val projName = root.name
        val file = resolveInProject(context, projName, path)
            ?: return "修改失败：路径不合法或越出项目目录（$path）"
        if (file.isDirectory) return "修改失败：「$projName/${path.trim('/')}」是目录"
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
            val backup = writeTextFileWithBackup(file, merged)
            val act = if (appending) "已追加" else "已覆盖"
            val undo = backup?.let { "\n（已备份原文件为 $it，需要退回可用「恢复文件」工具）" } ?: ""
            "$act「$projName/${path.trim('/')}」（${old.length} → ${merged.length} 字符）。$undo" +
                "注意：这是项目镜像（App 私有），要同步到手机下载目录请重新「保存代码文件」或安装 AIUI 项目。"
        }.getOrElse { "修改失败：写入出错（${it.message}）" }
    }

    // ═══════════════════════════════════════════════════════════════
    // ④' 恢复文件（从备份回退）—— 仅 project 作用域
    // ═══════════════════════════════════════════════════════════════

    /**
     * 把文件回退到某次**修改之前**的备份。
     *
     * ## 为什么是独立工具而不是 `edit_text_file` 的一个 mode
     * 「退回」和「改写」是两种意图完全不同的动作：前者是**撤销已发生的错误**
     * （模型发现改错了，需要把文件拿回去），后者是**推进任务**。
     * 混在一个 mode 里，模型在不确定时会在「退回」和「继续改」之间来回切，
     * 反而更容易把文件搞乱 —— 所以这里给一个名字明确的工具。
     *
     * @param backupName 指定备份文件名；**空 = 取最近一个**（模型绝大多数场景要的就是它）
     */
    fun restoreFile(context: Context, project: String, path: String, backupName: String): String {
        val proj = project.trim()
        if (proj.isBlank()) return "恢复失败：project 不能为空"
        if (path.trim().isBlank()) return "恢复失败：path 不能为空"
        val root = projectRoot(context, proj)
            ?: return "恢复失败：项目「$proj」不存在。${availableProjectsHint(context)}"
        val projName = root.name
        val file = resolveInProject(context, projName, path)
            ?: return "恢复失败：路径不合法或越出项目目录（$path）"
        if (file.isDirectory) return "恢复失败：「$projName/${path.trim('/')}」是目录"
        val dir = file.absoluteFile.parentFile
            ?: return "恢复失败：无法确定文件所在目录"
        val all = listBackups(dir, file.name)
        if (all.isEmpty()) {
            return "恢复失败：「$projName/${path.trim('/')}」没有任何备份。" +
                "备份只在文件被**修改前**自动创建（修改 / 增量编辑），新建文件不产生备份。" +
                "如果文件已被改坏且没有备份，只能用 edit_text_file 以 mode=overwrite 重写完整内容。"
        }
        val chosen = if (backupName.isBlank()) {
            all.first()
        } else {
            val raw = backupName.trim()
            // 只接受裸文件名：路径不该由模型拼，目录已由 scope+path 定死了
            val hit = all.firstOrNull { it.name == raw }
                ?: return "恢复失败：没有名为 $raw 的备份。可用备份：" +
                    all.joinToString("、") { it.name }
            hit
        }
        val original = FileBackupNaming.originalNameOf(chosen.name)
        return runCatching {
            val content = TextEncoding.decode(chosen.readBytes())
            // 恢复本身也先备份：万一"恢复"这个动作选错了备份，还能再退回来
            writeTextFileWithBackup(file, content)
            "已把「$projName/${path.trim('/')}」恢复到备份 $original 的内容（${content.length} 字符，" +
                "备份时间戳 ${FileBackupNaming.timestampOf(chosen.name)}）。" +
                "（恢复前的版本也已备份，可再次恢复回去）"
        }.getOrElse { "恢复失败：读取备份出错（${it.message}）" }
    }

    // ═══════════════════════════════════════════════════════
    // ⑤ 删文件
    // ═══════════════════════════════════════════════════════

    // ═══════════════════════════════════════════════════════════════
    // ④'' 增量补丁（apply_patch 语义）—— 仅 project 作用域
    // ═══════════════════════════════════════════════════════════════

    /**
     * 以**补丁**方式局部修改文件（对标 Codex 的 `apply_patch`）。
     *
     * ## 为什么与 [editTextFile] 并存而不是替换它
     * - 「生成一个新文件」用 `save_code_file` / `edit_text_file(mode=overwrite)` 仍是最省事的
     *   （补丁要写上下文，而新文件没有上下文）；
     * - 「改已有文件的一小段」用本工具才是 O(变更量) 而不是 O(文件) ——
     *   整份覆盖要先读全文（吃上下文）、再生成全量（吃输出、有截断风险）。
     * 所以两者分工：**新文件走覆盖，已有文件走补丁**。
     *
     * @param operation `update`（改已有文件，默认）/ `create`（新建，文件必须不存在）/ `delete`（删文件）
     */
    fun applyPatch(
        context: Context,
        project: String,
        path: String,
        operation: String,
        patch: String,
        lineHint: Int,
    ): String {
        val proj = project.trim()
        if (proj.isBlank()) return "补丁失败：project 不能为空"
        if (path.trim().isBlank()) return "补丁失败：path 不能为空"
        if (patch.isBlank()) return "补丁失败：patch 不能为空"
        val root = projectRoot(context, proj)
            ?: return "补丁失败：项目「$proj」不存在。${availableProjectsHint(context)}"
        val projName = root.name
        val file = resolveInProject(context, projName, path)
            ?: return "补丁失败：路径不合法或越出项目目录（$path）"
        if (file.isDirectory) return "补丁失败：「$projName/${path.trim('/')}」是目录"
        val label = "$projName/${path.trim('/')}"
        val op = operation.trim().lowercase().ifEmpty { "update" }

        if (op == "delete") {
            if (!file.exists()) return "补丁失败：「$label」不存在，无需删除"
            return runCatching {
                if (file.delete()) "已删除「$label」（不可恢复）"
                else "补丁失败：删除「$label」未成功"
            }.getOrElse { "补丁失败：${it.message}" }
        }

        if (op == "create") {
            if (file.exists()) {
                return "补丁失败：「$label」已存在。⚠️ create 只用于**新**文件；" +
                    "改已有文件请用 operation=update（或用「修改文件」整份覆盖）。"
            }
            val parsed = runCatching { PatchApplier.apply("", patch, null) }
                .getOrElse { return "补丁失败：${it.message}" }
            if (parsed.content.isEmpty()) return "补丁失败：补丁解析后内容为空，新建失败"
            if (parsed.content.length > MAX_WRITE_CHARS) {
                return "补丁失败：内容过大（${parsed.content.length} 字符 > $MAX_WRITE_CHARS）"
            }
            return runCatching {
                file.parentFile?.mkdirs()
                writeTextFileAtomically(file, parsed.content)
                "已新建「$label」（${parsed.content.length} 字符，${parsed.hunks.size} 个片段）。" +
                    "注意：这是项目镜像（App 私有），要同步到手机下载目录请重新「保存代码文件」或安装 AIUI 项目。"
            }.getOrElse { "补丁失败：写入出错（${it.message}）" }
        }

        if (op != "update") {
            return "补丁失败：operation 只能是 update / create / delete（收到「$operation」）"
        }
        if (!file.isFile) {
            return "补丁失败：「$label」不存在。⚠️ 新建文件请用 operation=create，或先用「保存代码文件」。"
        }
        val old = runCatching { TextEncoding.decode(file.readBytes()) }
            .getOrElse { return "补丁失败：无法读取原内容（${it.message}）" }

        val result = runCatching {
            PatchApplier.apply(old, patch, lineHint.takeIf { it > 0 })
        }.getOrElse { return "补丁失败：${it.message}" }

        if (result.content.length > MAX_WRITE_CHARS) {
            return "补丁失败：应用后共 ${result.content.length} 字符 > $MAX_WRITE_CHARS，本次没有改动任何内容"
        }
        if (result.content == old) {
            return "补丁应用后内容与原文完全一致（${result.hunks.size} 个片段），本次没有改动任何内容。" +
                "如果这不符合预期，请确认补丁里「-」（删除行）与「+」（新增行）两侧是否写反。"
        }
        return runCatching {
            // 写前备份：局部编辑的失败模式是"看起来成功但改错了地方"，
            // 没有备份就只能让 AI 重新生成整个文件（那正是本工具要消除的成本）。
            val backup = writeTextFileWithBackup(file, result.content)
            val lines = result.hunks.joinToString("；") { "第 ${it.line} 行起 +${it.added}/-${it.removed}" }
            "已对「$label」应用补丁（${old.length} → ${result.content.length} 字符）：$lines。" +
                (backup?.let { "原内容已备份为 $it，需要退回可用「恢复文件」工具。" } ?: "") +
                "注意：这是项目镜像（App 私有），要同步到手机下载目录请重新「保存代码文件」或安装 AIUI 项目。"
        }.getOrElse { "补丁失败：写入出错（${it.message}）" }
    }

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
        // 名字先归一（应用名 → 项目文件夹名）：删除会连带注销登记 + 删眼镜端副本，
        // 用对名字比"报个找不到"重要（2026-10-10 用户反馈：改/删 AIUI 项目总说找不到）。
        val resolved = AiuiProject.resolveProjectName(context, proj)
        val root = resolved?.let { AiuiProject.projectDir(context, it).takeIf(File::isDirectory) }
        val name = resolved ?: proj
        if (root == null) {
            // 项目镜像不在，可能只剩公开副本（名称解析不出时用原名再尽力试一次）
            val pub = WebTools.deleteDownloadedProject(context, name)
            val glasses = unlinkRegistryRecords(context, name)
            val glassesNote = if (glasses.isNullOrEmpty()) "" else "；眼镜端：${glasses.joinToString("；")}"
            return if (pub > 0 || !glasses.isNullOrEmpty()) {
                "项目「$name」没有工程镜像，已删除下载目录里的 $pub 个公开文件$glassesNote"
            } else {
                "删除失败：项目「$proj」不存在。${availableProjectsHint(context)}"
            }
        }
        if (path.trim().isBlank()) {
            val mirrorOk = runCatching { root.deleteRecursively() }.getOrDefault(false)
            val pub = runCatching { WebTools.deleteDownloadedProject(context, name) }.getOrDefault(0)
            // 删除已安装记录：登记表里的 agentId 对应本地 .aix 包（含彩色预览包）与眼镜端副本。
            // 不联动的话：对话里说"把项目删了"，手机源文件没了，眼镜上却还能语音打开它。
            val glasses = unlinkRegistryRecords(context, name)
            Log.i(TAG, "delete project $name: mirror=$mirrorOk public=$pub registry=$glasses")
            val glassesNote = when {
                glasses == null -> ""
                glasses.isEmpty() -> ""
                else -> "；眼镜端：${glasses.joinToString("；")}"
            }
            return if (mirrorOk) {
                "已删除项目「$name」（工程镜像 + 下载目录里的 $pub 个公开文件$glassesNote），不可恢复"
            } else {
                "删除项目「$name」失败（工程镜像未能完整删除）"
            }
        }
        val target = resolveInProject(context, name, path)
            ?: return "删除失败：路径不合法或越出项目目录（$path）"
        if (!target.exists()) return "删除失败：「$name/${path.trim('/')}」不存在"
        val label = "$name/${path.trim('/')}"
        val ok = if (target.isDirectory) runCatching { target.deleteRecursively() }.getOrDefault(false)
        else runCatching { target.delete() }.getOrDefault(false)
        return if (ok) "已删除「$label」，不可恢复" else "删除「$label」失败"
    }

    /**
     * 整项目删除时联动 AIUI 安装记录：注销登记、删本地 .aix 包（含彩色预览包）、
     * 尽力删除眼镜端副本（眼镜未连接时如实报告，不阻塞手机侧删除）。
     *
     * @return null = 注册表里没有该项目的记录（从未打包安装过，无需联动）；
     *         非空 = 每条记录的删除结果描述（供拼进工具回复告知模型/用户）
     */
    private fun unlinkRegistryRecords(context: Context, project: String): List<String>? {
        val records = runCatching { AiuiAppRegistry.list(context) }.getOrNull()
            ?.filter { it.project == project }
            ?: return null
        if (records.isEmpty()) return null
        return records.map { r ->
            runCatching { AiuiAppRegistry.remove(context, r.agentId) }
            runCatching { AiuiProject.deletePackageFiles(context, r.agentId) }
            val err = runCatching { AiuiProject.deleteAixOnGlasses(context, r.agentId) }.getOrNull()
            if (err == null) "「${r.appName}」已从眼镜删除"
            else "「${r.appName}」眼镜端未能删除（$err），可连接眼镜后到 AI 应用管理页删除"
        }
    }

    private fun deleteDownload(context: Context, project: String, path: String): String {
        val rel = path.trim().replace('\\', '/').trim('/')
        if (rel.isEmpty()) return "删除失败：path 不能为空（不允许清空下载目录）"
        val segs = splitRel(rel) ?: return "删除失败：路径不合法（$rel）"
        // ★ 护栏文件保护（2026-10-09）：`下载/RokidLab规则.md` 的内容会被逐轮注入 system
        //   prompt，是模型自己遵守的约定 —— 允许它删/改等于允许它修改自己的行为准则
        //   （对应 Muse 的「agent 不能改写自己运行的规则」）。其余几条写入路径经核查不可达，
        //   只有 delete 这一条真的能碰到它，理由与核查表见 [PromptGuardrails] 的类注释。
        if (PromptGuardrails.isGuardedDownloadPath(rel)) {
            return PromptGuardrails.refuseDeleteMessage(rel)
        }
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
        val root = projectRoot(context, proj)
            ?: return "移动失败：项目「$proj」不存在。${availableProjectsHint(context)}"
        val projName = root.name
        val src = resolveInProject(context, projName, from)
            ?: return "移动失败：源路径不合法或越出项目目录（$from）"
        val dst = resolveInProject(context, projName, to)
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
