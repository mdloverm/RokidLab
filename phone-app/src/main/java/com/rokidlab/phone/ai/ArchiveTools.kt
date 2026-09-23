package com.rokidlab.phone.ai

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 压缩 / 解压（zip）—— AI「打包与解包」工具的唯一实现。
 *
 * ## 为什么自己实现而不用 `run_shell` 里的 zip/unzip
 * 容器里的 `zip` 只认 `/mnt/lab`（还得先装执行环境），而「把生成的项目打个包」「把刚下载的
 * zip 解开」是**没有前置条件也该能做**的日常动作。`java.util.zip` 是 JDK 自带的，
 * 零依赖、零下载，直接跑在 App 进程里，正好覆盖这两个场景。
 *
 * ## 能力边界（必须写进 schema，否则模型会按"能打包任意文件"的直觉乱猜）
 * | 工具 | 读 | 写 |
 * |---|---|---|
 * | [zipFiles] | `project` 作用域（App 生成的项目工程目录，路径规则同 [FileWorkspace]） | 下载目录 |
 * | [unzipFile] | 下载目录里的 `.zip` | 下载目录（默认解到以包名命名的子目录） |
 *
 * 刻意**不**支持「把下载目录里的散文件打成 zip」：下载目录在 API29+ 是 MediaStore 里的行，
 * 逐行开流打包会让实现复杂度翻倍，而这条需求用项目作用域 + `edit_text_file` 就能替代。
 *
 * ## 两道安全闸（改这个文件前必读）
 *  1. **zip slip**：解压时每个条目名逐段过 [SEGMENT] 白名单，出现 `..`/绝对路径/反斜杠/
 *     非法字符即**整包中止**（不是跳过）—— 一个带路径穿越的包不值得"尽量解开"；
 *  2. **zip 炸弹**：条目数上限 [MAX_UNZIP_ENTRIES] + 解压后总字节上限 [MAX_UNZIP_BYTES]，
 *     压缩比可以上万倍，只看 zip 本身大小根本防不住；越限即中止并**回滚本次已写出的文件**。
 */
object ArchiveTools {
    private const val TAG = "ArchiveTools"

    /** 打包纳入的条目数上限 */
    private const val MAX_ZIP_ENTRIES = 1000

    /** 打包输入总量上限 */
    private const val MAX_ZIP_INPUT_BYTES = 200L * 1024 * 1024

    /** 解压条目数上限 */
    private const val MAX_UNZIP_ENTRIES = 3000

    /** 解压后总字节上限 */
    private const val MAX_UNZIP_BYTES = 300L * 1024 * 1024

    /** 目标目录名规则（与 [FileWorkspace] 的项目名规则同源） */
    private val NAME = Regex("[\\w\\-\\u4e00-\\u9fa5]{1,40}")

    /** 解压条目名逐段规则（与 [FileWorkspace] 的路径段规则同源） */
    private val SEGMENT = Regex("^[\\w\\-\\u4e00-\\u9fa5][\\w.\\- \\u4e00-\\u9fa5]{0,80}$")

    // ═══════════════════════════════════════════════════════
    // 压缩
    // ═══════════════════════════════════════════════════════

    /**
     * 把项目工程目录里的文件/目录打包成一个 zip，写到手机下载目录。
     *
     * @param paths 可选，相对项目根的文件/目录（不传 = 整个项目）
     * @param zipName 可选，压缩包名（不带 .zip），默认用项目名
     * @return 给模型的结果文本：成功以「已打包」开头，失败以「打包失败」开头
     */
    fun zipFiles(context: Context, project: String, paths: List<String>, zipName: String): String {
        val proj = project.trim()
        if (proj.isBlank()) return "打包失败：project 不能为空（要打包哪个项目？）"
        val root = FileWorkspace.projectRoot(context, proj)
            ?: return "打包失败：项目「$proj」不存在。可先用「列出文件」确认项目名"
        val wanted = paths.map { it.trim() }.filter { it.isNotEmpty() }

        val entries = mutableListOf<Pair<File, String>>()
        if (wanted.isEmpty()) {
            entries += collect(root, root)
        } else {
            val missing = mutableListOf<String>()
            wanted.forEach { p ->
                val f = FileWorkspace.resolveInProject(context, proj, p)
                if (f == null || !f.exists()) missing += p else entries += collect(f, root)
            }
            if (missing.isNotEmpty()) {
                return "打包失败：项目「$proj」里没有这些路径：${missing.joinToString("、")}（先用「列出文件」看看有什么）"
            }
        }
        if (entries.isEmpty()) return "打包失败：项目「$proj」里没有可打包的文件"
        if (entries.size > MAX_ZIP_ENTRIES) {
            return "打包失败：文件太多（${entries.size} 个 > $MAX_ZIP_ENTRIES），请先用 paths 缩小范围"
        }
        val totalBytes = entries.sumOf { it.first.length() }
        if (totalBytes > MAX_ZIP_INPUT_BYTES) {
            return "打包失败：内容太大（${human(totalBytes)} > ${human(MAX_ZIP_INPUT_BYTES)}），请用 paths 分次打包"
        }

        val name = sanitizeName(zipName.ifBlank { proj }) + ".zip"
        val relDir = Environment.DIRECTORY_DOWNLOADS
        return try {
            writeDownloadFile(context, relDir, name) { out ->
                ZipOutputStream(BufferedOutputStream(out)).use { zos ->
                    entries.forEach { (file, rel) ->
                        zos.putNextEntry(ZipEntry(rel))
                        file.inputStream().use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
                }
            }
            Log.i(TAG, "zip ok: $proj -> $relDir/$name (${entries.size} entries, ${totalBytes}B)")
            "已打包「$proj」的 ${entries.size} 个文件（原始 ${human(totalBytes)}）为「手机存储/$relDir/$name」。"
        } catch (e: Exception) {
            Log.e(TAG, "zip failed: $proj -> ${e.message}")
            "打包失败（${e.message}）"
        }
    }

    /** 收集待打包文件（跳过隐藏文件与目录），返回 (文件, zip 内相对路径) */
    private fun collect(target: File, root: File): List<Pair<File, String>> {
        if (target.isFile) {
            return listOf(target to target.relativeTo(root).path.replace('\\', '/'))
        }
        val out = mutableListOf<Pair<File, String>>()
        target.walkTopDown()
            .filter { it.isFile && !it.name.startsWith(".") }
            .forEach { out += it to it.relativeTo(root).path.replace('\\', '/') }
        return out
    }

    // ═══════════════════════════════════════════════════════
    // 解压
    // ═══════════════════════════════════════════════════════

    /**
     * 解压下载目录里的一个 zip。
     *
     * @param zipPath 相对下载目录的 zip 路径，如 `app.zip` 或 `资料/app.zip`
     * @param folder  可选，解压到的子目录名（默认取 zip 文件名去后缀）
     * @return 给模型的结果文本：成功以「已解压」开头，失败以「解压失败」开头
     */
    fun unzipFile(context: Context, zipPath: String, folder: String): String {
        val rel = zipPath.trim().replace('\\', '/').trim('/')
        if (rel.isEmpty()) return "解压失败：zipPath 不能为空（相对下载目录，如 app.zip）"
        if (!rel.lowercase().endsWith(".zip")) return "解压失败：只支持 .zip 文件（当前：$rel）"
        val base = rel.substringAfterLast('/').dropLast(4)
        val dir = folder.trim().ifBlank { base }
        if (!NAME.matches(dir)) {
            return "解压失败：目标目录名不合法（$dir），只允许中英文/数字/下划线/连字符"
        }
        val stream = FileWorkspace.openDownloadInput(context, rel)
            ?: return "解压失败：下载目录里没有「$rel」，或该文件不是本 App 创建的（无权限读取）。可用「列出文件」确认路径"
        val relDir = "${Environment.DIRECTORY_DOWNLOADS}/$dir"
        val created = mutableListOf<Pair<String, String>>() // (相对目录, 文件名)，用于失败回滚

        return try {
            var count = 0
            var total = 0L
            stream.use { ins ->
                ZipInputStream(BufferedInputStream(ins)).use { zis ->
                    while (true) {
                        val entry = zis.nextEntry ?: break
                        val entryName = entry.name.replace('\\', '/').trim('/')
                        if (entry.isDirectory || entry.name.endsWith("/") || entryName.isEmpty()) continue
                        val segs = entryName.split('/')
                        if (segs.any { it.isBlank() || it == "." || it == ".." || !SEGMENT.matches(it) }) {
                            return "解压失败：压缩包里有不安全的条目名（$entryName），已中止。" +
                                "（常见原因是该 zip 用非 UTF-8 编码保存中文名；可在「运行命令」里用容器解压）"
                        }
                        if (count >= MAX_UNZIP_ENTRIES) {
                            return "解压失败：压缩包条目太多（超过 $MAX_UNZIP_ENTRIES 个），已中止"
                        }
                        val name = segs.last()
                        val entryDir = relDir + (if (segs.size > 1) "/" + segs.dropLast(1).joinToString("/") else "")
                        writeDownloadFile(context, entryDir, name) { out ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = zis.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                total += n
                                if (total > MAX_UNZIP_BYTES) {
                                    throw IOException("解压后内容超过 ${human(MAX_UNZIP_BYTES)} 上限，已中止")
                                }
                            }
                        }
                        created += entryDir to name
                        count++
                    }
                }
            }
            if (count == 0) {
                "解压失败：压缩包「$rel」里没有文件（可能是空包或只含目录）"
            } else {
                Log.i(TAG, "unzip ok: $rel -> $relDir ($count entries, ${total}B)")
                "已解压 $count 个文件到「手机存储/$relDir/」（解压后 ${human(total)}）。" +
                    "其中本 App 创建的文件可用 list_files(scope=\"downloads\") 或 read_text_file 查看。"
            }
        } catch (e: Exception) {
            // 越限/写入失败时回滚本次已写出的条目（半套解压结果比不解压更让人困惑）
            created.forEach { (d, n) -> deleteDownloadEntry(context, d, n) }
            Log.e(TAG, "unzip failed: $rel -> ${e.message}")
            "解压失败（${e.message}）"
        }
    }

    // ═══════════════════════════════════════════════════════
    // 下载目录读写（MediaStore / 低版本兜底）
    // ═══════════════════════════════════════════════════════

    /**
     * 在下载目录写一个文件（`Download/<relDir 去前缀>/<name>`），写完把 MediaStore 行转为可见。
     *
     * API29+ 走 MediaStore（`IS_PENDING` 先占位再落盘，**失败时删除该行** —— 否则会留下
     * 一个用户看得见却打不开的空文件）；低版本退化为应用专属下载目录。
     */
    private fun writeDownloadFile(
        context: Context,
        relDir: String,
        name: String,
        block: (OutputStream) -> Unit,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // 覆盖同名：先删旧行，避免 MediaStore 自动改名堆出 (1)(2)(3)
            deleteDownloadEntry(context, relDir, name)
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, if (name.endsWith(".zip")) "application/zip" else "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "$relDir/")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("无法在下载目录创建文件")
            try {
                context.contentResolver.openOutputStream(uri)?.use(block) ?: throw IOException("无法写入文件")
                context.contentResolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                    null,
                    null,
                )
            } catch (e: Exception) {
                runCatching { context.contentResolver.delete(uri, null, null) }
                throw e
            }
            return
        }
        val dir = File(FileWorkspace.legacyRoot(context), relDir.removePrefix("${Environment.DIRECTORY_DOWNLOADS}/").trim('/'))
        dir.mkdirs()
        val file = File(dir, name)
        try {
            FileOutputStream(file).use(block)
        } catch (e: Exception) {
            runCatching { file.delete() }
            throw e
        }
    }

    /** 删除下载目录里的一个文件（按 相对目录+文件名 定位，不走 LIKE 通配以免文件名里的 _ % 误伤） */
    private fun deleteDownloadEntry(context: Context, relDir: String, name: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                context.contentResolver.delete(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?",
                    arrayOf("$relDir/", name),
                )
            }
            return
        }
        runCatching {
            File(
                File(FileWorkspace.legacyRoot(context), relDir.removePrefix("${Environment.DIRECTORY_DOWNLOADS}/").trim('/')),
                name,
            ).delete()
        }
    }

    // ═══════════════════════════════════════════════════════
    // 小工具
    // ═══════════════════════════════════════════════════════

    /** 压缩包名清洗：去非法字符，超长截断（.zip 后缀由调用方补） */
    private fun sanitizeName(raw: String): String {
        val cleaned = raw.trim().replace(Regex("[\\\\/:*?\"<>|\\r\\n\\t]"), "_").trim().trim('.')
        if (cleaned.isEmpty()) return "archive"
        return cleaned.take(60)
    }

    private fun human(bytes: Long): String = when {
        bytes >= 1024L * 1024 -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1048576.0)
        bytes >= 1024L -> String.format(java.util.Locale.US, "%.0f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}
