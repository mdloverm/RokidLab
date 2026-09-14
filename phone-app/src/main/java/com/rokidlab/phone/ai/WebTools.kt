package com.rokidlab.phone.ai

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.Charset

/**
 * 网页「搜索 → 抓取正文 → 总结落盘」工具集（AI 网页总结能力）。
 *
 * 三个能力分别对应 Agent 工具：
 * - [search]：关键词 → Bing 网页搜索，返回「标题+链接+摘要」候选（供模型挑选最相关的链接）
 * - [fetchPage]：抓取指定网页正文并转为纯文本（供模型总结）
 * - [saveSummary]：把总结文本写入系统下载目录，并同步导入本地知识库（双写）
 *
 * 注意：本工具集全部由 Agent 工具线程调用（非主线程），可直接执行网络 IO；
 * 结果文本回填给模型后由模型组织最终口语回复。
 */
object WebTools {
    private const val TAG = "WebTools"

    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"

    /** 抓取网页响应体上限（防异常大页面拖垮内存） */
    private const val MAX_BODY_BYTES = 2 * 1024 * 1024

    /** 回填给模型的正文上限字符 */
    private const val MAX_FETCH_TEXT = 16000

    /** 搜索结果最多返回条数 */
    private const val MAX_SEARCH_RESULTS = 6

    private data class SearchItem(val title: String, val url: String, val snippet: String)

    private data class FetchResult(val title: String, val text: String)

    // ═══════════════════════════════════════════════════
    // 搜索
    // ═══════════════════════════════════════════════════

    /**
     * 关键词搜索网页（Bing，无需 Key）。
     * 成功返回候选条目文本；失败返回以"网页搜索失败"开头的说明，模型应如实告知用户。
     */
    fun search(query: String): String {
        val q = query.trim()
        if (q.isEmpty()) return "请提供要搜索的关键词"
        val url = "https://cn.bing.com/search?q=" + URLEncoder.encode(q, "UTF-8") +
            "&count=10&setlang=zh-hans&ensearch=0"
        return try {
            val body = rawGet(
                url,
                headers = mapOf("User-Agent" to UA, "Accept-Language" to "zh-CN,zh;q=0.9"),
            )
            val items = parseBingResults(body)
            if (items.isEmpty()) {
                return "网页搜索没有找到与“$q”相关的结果，请换个关键词试试"
            }
            items.take(MAX_SEARCH_RESULTS).mapIndexed { i, it ->
                "[${i + 1}] ${it.title}\n    链接：${it.url}\n    摘要：${it.snippet}"
            }.joinToString("\n\n") +
                "\n\n提示：如需深入了解，请对最相关的链接调用 fetch_webpage 读取正文后总结。"
        } catch (e: Exception) {
            Log.e(TAG, "search failed: $q -> ${e.message}")
            "网页搜索失败（${e.message}），请稍后重试或换个关键词"
        }
    }

    /** 解析 Bing 搜索结果：以 `<li class="b_algo"` 切块，逐块提取 h2>a 链接/标题与首段摘要 */
    private fun parseBingResults(html: String): List<SearchItem> {
        val head = html.indexOf("<li class=\"b_algo\"")
        if (head < 0) return emptyList()
        val items = mutableListOf<SearchItem>()
        html.substring(head)
            .split(Regex("(?=<li class=\"b_algo\")"))
            .drop(1)
            .forEach { part ->
                if (items.size >= MAX_SEARCH_RESULTS) return@forEach
                val url = Regex("<a[^>]+href=\"(https?://[^\"]+)\"").find(part)
                    ?.groupValues?.get(1) ?: return@forEach
                val title = stripHtml(Regex("<h2[^>]*>([\\s\\S]*?)</h2>").find(part)?.groupValues?.get(1) ?: "")
                    .ifBlank { return@forEach }
                val snippet = Regex("<p[^>]*>([\\s\\S]*?)</p>").find(part)?.groupValues?.get(1)
                    ?.let { stripHtml(it) }?.ifBlank { null }
                    ?: stripHtml(part).take(120)
                items.add(SearchItem(title, url, snippet.take(150)))
            }
        return items
    }

    // ═══════════════════════════════════════════════════
    // 抓取正文
    // ═══════════════════════════════════════════════════

    /**
     * 抓取网页正文并转为纯文本。
     * 失败返回以"网页读取失败"开头的说明，模型应如实告知用户。
     */
    fun fetchPage(url: String): String {
        val u = url.trim()
        if (!u.startsWith("https://") && !u.startsWith("http://")) {
            return "仅支持 http/https 网页链接：$u"
        }
        return try {
            val body = rawGet(
                u,
                headers = mapOf("User-Agent" to UA, "Accept-Language" to "zh-CN,zh;q=0.9"),
            )
            val page = extractPage(body)
            val text = page.text.ifBlank { "（未能从该网页提取到正文，可能页面需要登录或使用了脚本渲染）" }
            val truncated = if (text.length > MAX_FETCH_TEXT) {
                text.take(MAX_FETCH_TEXT) + "\n…（正文过长已截断）"
            } else {
                text
            }
            buildString {
                append("网页标题：").append(page.title.ifBlank { "（无标题）" }).append("\n\n")
                append(truncated)
            }
        } catch (e: Exception) {
            Log.e(TAG, "fetch failed: $u -> ${e.message}")
            "网页读取失败（${e.message}），请确认链接可正常访问后重试"
        }
    }

    /** 提取正文：去脚本/样式/导航等噪音块 → 剥离标签 → 解码实体 → 压缩空白 */
    private fun extractPage(html: String): FetchResult {
        val title = stripHtml(
            Regex("<title[^>]*>([\\s\\S]*?)</title>", RegexOption.IGNORE_CASE)
                .find(html)?.groupValues?.get(1) ?: "",
        )
        var text = html
        text = Regex("(?is)<(script|style|noscript|svg|iframe|nav|footer|header|form)[^>]*>.*?</\\1>")
            .replace(text, " ")
        text = text.replace(Regex("(?is)<!--.*?-->"), " ")
        text = stripHtml(text)
        return FetchResult(title, text)
    }

    // ═══════════════════════════════════════════════════
    // 保存总结（下载目录 + 知识库双写）
    // ═══════════════════════════════════════════════════

    /**
     * 将总结文本保存为 txt：写入系统下载目录（API29+ 走 MediaStore.Downloads），
     * 并导入本地知识库，返回给模型的结果文本（含文件位置与后续可追问提示）。
     */
    fun saveSummary(context: Context, title: String, content: String): String {
        val t = title.trim().ifBlank { "网页总结" }
        val body = content.trim()
        if (body.isEmpty()) return "没有可保存的总结内容，请先完成总结再调用本工具"
        val saved = try {
            writeToDownloads(context, t, body)
        } catch (e: Exception) {
            Log.e(TAG, "save txt failed: ${e.message}")
            return "保存 txt 文件失败（${e.message}），请稍后重试"
        }
        // 同步导入知识库，之后可被 search_knowledge_base 检索
        val kbOk = runCatching { KnowledgeBase.importText(context, "网页总结：$t", body) }.isSuccess
        Log.i(TAG, "saveSummary ok: file=$saved kb=$kbOk")
        return if (kbOk) {
            "总结已保存为 txt 文件：$saved（位于系统下载目录），并已存入知识库，之后可随时问我“$t”的相关内容"
        } else {
            "总结已保存为 txt 文件：$saved（位于系统下载目录），但知识库导入失败"
        }
    }

    /** 写入下载目录（API29+ MediaStore；低版本退化为应用专属目录，免存储权限） */
    private fun writeToDownloads(context: Context, title: String, text: String): String {
        val safeTitle = title.replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), " ")
            .trim().take(60).ifBlank { "网页总结" }
        val fileName = "$safeTitle.txt"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = context.contentResolver
                .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("无法在下载目录创建文件")
            context.contentResolver.openOutputStream(uri)?.use { os ->
                os.write(text.toByteArray(Charsets.UTF_8))
            } ?: throw IOException("无法写入文件内容")
            context.contentResolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null,
            )
            return fileName
        }
        // API < 29：写入应用专属下载目录（无需存储权限）
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
        val file = File(dir, fileName)
        file.writeText(text, Charsets.UTF_8)
        return file.absolutePath
    }

    /**
     * 把 AI 生成的单个代码/项目文件写入「下载目录/<项目名>/…」（Agent 的 save_code_file 工具）。
     *
     * API29+ 走 MediaStore.Downloads：RELATIVE_PATH 支持多级子目录（Download/<project>/<dir>），
     * 文件管理器可见；低版本退化为应用专属目录（免存储权限）。同名文件先删后写，支持覆盖式重复生成。
     * 一次只写一个文件；多文件项目由模型按文件逐个调用。
     *
     * @return 给模型的结果文本：成功以「已生成」开头（含项目路径），失败以「保存失败」开头
     */
    fun writeProjectFile(context: Context, project: String, filePath: String, content: String): String {
        val proj = project.trim()
        if (proj.isBlank()) return "保存失败：项目名不能为空"
        if (!proj.matches(Regex("[\\w\\-\\u4e00-\\u9fa5]{1,40}"))) {
            return "保存失败：项目名不合法（仅限中英文、数字、下划线、连字符，最长 40 个字符）"
        }
        val rel = filePath.trim().replace('\\', '/').trim('/')
        if (rel.isBlank()) return "保存失败：文件路径不能为空"
        val segments = rel.split('/')
        if (segments.any { it.isBlank() || it == "." || it == ".." }) {
            return "保存失败：文件路径不合法（$rel）"
        }
        val segOk = Regex("^[\\w\\-\\u4e00-\\u9fa5][\\w.\\- \\u4e00-\\u9fa5]{0,80}$")
        if (segments.any { !it.matches(segOk) }) {
            return "保存失败：文件名含非法字符或超长（最长 81 字符）"
        }
        if (content.length > 200_000) {
            return "保存失败：单文件内容过大（${content.length} 字符 > 20 万），请拆小后分次生成"
        }
        val fileName = segments.last()
        val subDir = segments.dropLast(1)
        Log.i(TAG, "writeProjectFile: project=$proj file=$rel chars=${content.length}")
        // AIUI 页面（.ink）强制符合 SFC 规范：渲染引擎只认 <page> 作模板根，
        // 若写成 <template> 的 Vue 风格会被判为不合规直接拒绝落盘，模型收到
        // 错误提示后会在工具循环里按规范重写 → 保证源文件从写入起就合规（防黑屏）。
        if (fileName.endsWith(".ink", ignoreCase = true)) {
            AiuiProject.validateInkContent(content)?.let { err ->
                Log.w(TAG, "writeProjectFile: ink rejected: $proj/$rel -> $err")
                return "保存失败：$proj/$rel 不符合 AIUI 页面规范：$err"
            }
        }
        // 私有目录镜像：build_aiui_app 打包 .aix 时从 filesDir/aiui_projects/<project>/ 读取全部生成文件
        runCatching {
            val mirror = File(context.filesDir, "aiui_projects/$proj/$rel").apply { parentFile?.mkdirs() }
            mirror.writeText(content, Charsets.UTF_8)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val relPath = (listOf(Environment.DIRECTORY_DOWNLOADS, proj) + subDir).joinToString("/")
            // 覆盖式生成：先清掉同路径同名旧文件，避免 MediaStore 自动改名堆积重复文件
            context.contentResolver.delete(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?",
                arrayOf("$relPath/", fileName),
            )
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeFor(fileName))
                put(MediaStore.MediaColumns.RELATIVE_PATH, relPath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return "保存失败：无法在下载目录创建文件"
            context.contentResolver.openOutputStream(uri)?.use { os ->
                os.write(content.toByteArray(Charsets.UTF_8))
            } ?: return "保存失败：无法写入文件内容"
            context.contentResolver.update(
                uri,
                ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                null,
                null,
            )
            return "已生成 $proj/$rel（${content.length} 字符）。项目目录：手机存储/Download/$proj/"
        }
        // API < 29：写入应用专属目录兜底（内容完整，路径对用户不可见）
        val base = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
        val file = File(base, "$proj/$rel").apply { parentFile?.mkdirs() }
        file.writeText(content, Charsets.UTF_8)
        return "已生成 $proj/$rel（${content.length} 字符）。项目目录：$file"
    }

    /**
     * 删除「下载目录/<项目名>/」下的全部生成文件（[writeProjectFile] 落盘的公开副本）。
     *
     * **为什么必须删这里**：生成的源文件同时存在两处 —— 公开的 `Download/<项目名>/`
     * （MediaStore，用户在文件管理器里能看到，AI 回复里报的也是这个路径）与私有镜像
     * `filesDir/aiui_projects/<项目名>/`（打包 .aix 时读它）。管理页删除项目时只删了
     * 私有镜像，于是用户看到「项目删了，手机上的源文件目录还在」。
     *
     * API29+ 走 MediaStore：先查 RELATIVE_PATH 前缀匹配的行，再按 _ID 逐条删除 ——
     * 用 id 而不是 LIKE 通配，避免项目名里的 `_` / `%` 被当成 LIKE 通配符误伤。
     * 低版本退化为删除应用专属下载目录（免存储权限）。
     *
     * @return 实际删除的文件数（仅用于日志）
     */
    fun deleteDownloadedProject(context: Context, project: String): Int {
        val proj = project.trim()
        if (proj.isBlank()) return 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val prefix = "${Environment.DIRECTORY_DOWNLOADS}/$proj/"
            val uri = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            val ids = mutableListOf<Long>()
            runCatching {
                context.contentResolver.query(
                    uri,
                    arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.RELATIVE_PATH),
                    null,
                    null,
                    null,
                )?.use { c ->
                    val idCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val relCol = c.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
                    while (c.moveToNext()) {
                        val rel = c.getString(relCol) ?: continue
                        if (rel.startsWith(prefix)) ids.add(c.getLong(idCol))
                    }
                }
            }.onFailure { Log.w(TAG, "deleteDownloadedProject: query failed for $proj: ${it.message}") }
            var deleted = 0
            ids.forEach { id ->
                runCatching {
                    if (context.contentResolver.delete(ContentUris.withAppendedId(uri, id), null, null) > 0) {
                        deleted++
                    }
                }
            }
            Log.i(TAG, "deleteDownloadedProject: $proj -> deleted $deleted/${ids.size} files under Download/$proj/")
            return deleted
        }
        // API < 29：writeProjectFile 的兜底路径写在应用专属下载目录，直接整目录删
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir, proj)
        val ok = runCatching { dir.deleteRecursively() }.getOrDefault(false)
        Log.i(TAG, "deleteDownloadedProject(legacy): $proj -> removed=$ok")
        return if (ok) 1 else 0
    }

    /**
     * 读取 AI 生成项目（私有镜像 aiui_projects/<project>/）里的源码，供模型修改
     * 已有代码时参考（read_code_file 工具）。只读不改任何文件。
     * filePath 为空时返回整个项目的文件清单；带 filePath 时返回该文件完整内容。
     *
     * @return 给模型的结果文本：成功以「项目」描述开头，失败以「读取失败」开头
     */
    fun readProjectFile(context: Context, project: String, filePath: String): String {
        val proj = project.trim()
        if (proj.isBlank()) return "读取失败：项目名不能为空"
        if (!proj.matches(Regex("[\\w\\-\\u4e00-\\u9fa5]{1,40}"))) {
            return "读取失败：项目名不合法（仅限中英文、数字、下划线、连字符，最长 40 个字符）"
        }
        val dir = AiuiProject.projectDir(context, proj)
        if (!dir.isDirectory) {
            return "项目“$proj”还没有可读取的源码：若它是之前对话生成的，请先确认项目名（可让我查“我的 AI 应用”），或先用“保存代码文件”工具生成；若它只是本地上传的 .aix，则没有可编辑的工程源码。"
        }
        val sources = dir.walkTopDown()
            .filter { it.isFile && !it.name.startsWith(".") }
            .sortedBy { it.relativeTo(dir).path }
            .toList()
        if (sources.isEmpty()) return "项目“$proj”目录里还没有任何源码文件"

        val rel = filePath.trim().replace('\\', '/').trim('/')
        if (rel.isEmpty()) {
            val lines = sources.map { f ->
                val p = f.relativeTo(dir).path.replace('\\', '/')
                "- $p（${f.readText(Charsets.UTF_8).length} 字符）"
            }
            return "项目“$proj”现有 ${sources.size} 个源码文件：\n" + lines.joinToString("\n") +
                "\n需要读取某个文件的完整内容时，把 file 参数设为上面的相对路径即可"
        }
        // 相对路径合法性校验（与写文件同规则），防止逃出项目目录
        val segments = rel.split('/')
        if (segments.any { it.isBlank() || it == "." || it == ".." }) {
            return "读取失败：文件路径不合法（$rel）"
        }
        val segOk = Regex("^[\\w\\-\\u4e00-\\u9fa5][\\w.\\- \\u4e00-\\u9fa5]{0,80}$")
        if (segments.any { !it.matches(segOk) }) return "读取失败：文件名含非法字符或超长"
        val file = File(dir, rel)
        val dirRoot = dir.canonicalPath + File.separator
        if (!file.isFile || !file.canonicalPath.startsWith(dirRoot)) {
            val names = sources.joinToString("、") { it.relativeTo(dir).path.replace('\\', '/') }
            return "项目“$proj”里没有文件“$rel”。现有文件：$names"
        }
        val content = runCatching { file.readText(Charsets.UTF_8) }.getOrElse {
            return "读取失败：无法读取 $proj/$rel（${it.message}）"
        }
        return "「$proj/$rel」当前完整源码（${content.length} 字符）：\n$content"
    }

    private fun mimeFor(fileName: String): String = when (fileName.substringAfterLast('.', "").lowercase()) {
        "json" -> "application/json"
        "js", "mjs" -> "text/javascript"
        "html", "htm" -> "text/html"
        "css" -> "text/css"
        "md", "markdown" -> "text/markdown"
        "xml" -> "application/xml"
        "yaml", "yml" -> "text/yaml"
        "txt", "log" -> "text/plain"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        else -> "text/plain"
    }

    // ═══════════════════════════════════════════════════
    // 基础工具
    // ═══════════════════════════════════════════════════

    /** GET 抓取并按实际编码解码，返回 (解码文本) */
    private fun rawGet(
        url: String,
        connectTimeout: Int = 10000,
        readTimeout: Int = 15000,
        headers: Map<String, String> = emptyMap(),
    ): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            this.connectTimeout = connectTimeout
            this.readTimeout = readTimeout
            requestMethod = "GET"
            instanceFollowRedirects = true
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        try {
            conn.connect()
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code: ${conn.responseMessage}")
            val bytes = conn.inputStream.use { readLimited(it) }
            val cs = runCatching {
                Charset.forName(detectCharset(conn.getHeaderField("Content-Type"), bytes))
            }.getOrDefault(Charsets.UTF_8)
            return String(bytes, cs)
        } finally {
            conn.disconnect()
        }
    }

    /** 受限读取响应字节，超限抛错避免内存击穿 */
    private fun readLimited(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            total += n
            if (total > MAX_BODY_BYTES) throw IOException("网页响应过大（>${MAX_BODY_BYTES / 1024 / 1024}MB），已放弃")
        }
        return out.toByteArray()
    }

    /** 推断网页编码：HTTP 头 charset > HTML meta charset > UTF-8 */
    private fun detectCharset(contentType: String?, bytes: ByteArray): String {
        Regex("charset\\s*=\\s*[\"']?([\\w-]+)", RegexOption.IGNORE_CASE)
            .find(contentType.orEmpty())?.let { return it.groupValues[1] }
        val head = String(bytes, Charsets.ISO_8859_1).take(4096)
        Regex("<meta[^>]+charset\\s*=\\s*[\"']?([\\w-]+)", RegexOption.IGNORE_CASE)
            .find(head)?.let { return it.groupValues[1] }
        return "UTF-8"
    }

    /** 剥离 HTML 标签并解码常见实体，压缩空白后返回纯文本 */
    private fun stripHtml(html: String): String {
        if (html.isBlank()) return ""
        var s = html
        s = s.replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ")
        s = s.replace(Regex("<[^>]+>"), " ")
        s = htmlUnescape(s)
        s = s.replace(Regex("[\\t\\r]+"), " ")
        s = s.replace(Regex("\\n\\s*\\n+"), "\n")
        return s.trim()
    }

    private fun htmlUnescape(s: String): String = s
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&apos;", "'")
        .replace("&ldquo;", "“")
        .replace("&rdquo;", "”")
        .replace("&hellip;", "…")
        .replace("&middot;", "·")
        .replace("&times;", "×")
}
