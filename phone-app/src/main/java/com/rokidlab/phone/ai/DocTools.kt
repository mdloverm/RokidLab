package com.rokidlab.phone.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.util.Log
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.ZipInputStream

/**
 * DocTools —— 文档取文（docx / xlsx / pptx / pdf → 纯文本）。
 *
 * ## 为什么这条能力必须有
 *
 * 用户手机「下载」目录里躺着的资料就是 docx / xlsx / pdf，而模型只能读纯文本文件
 * （`read_text_file`）—— 于是「把这个表格总结一下」只能得到一句"我读不了这个格式"。
 * 缺口是**取文**，不是总结。
 *
 * ## 为什么零依赖
 *
 * docx / xlsx / pptx 本质都是 **zip + XML**，`java.util.zip` 就够（与 [ArchiveTools] 同源）；
 * PDF 不做自研解析（内容流里的字体编码/CID 映射是个坑，做半套比不做更糟），改走
 * **`PdfRenderer` 渲染成图 → 本地 OCR**：系统组件负责解码，[LocalOcr] 负责取字。
 * 代价是 PDF 走的是"看图识字"（对扫描件反而是唯一可行路径），且首次使用要下载 OCR 模型。
 *
 * ## 边界
 *
 * - 只读**下载目录**（与 `unzip_file` 同一约定），因为那里的文件本来就是用户给的；
 * - 解压总体积、返回字符数都有上限（zip 炸弹与上下文击穿）；
 * - 不做版式还原：段落/表格/页的顺序保留，样式一律丢弃 —— 模型要的是内容，不是排版。
 */
object DocTools {
    private const val TAG = "DocTools"

    /** 回填给模型的正文上限字符（超出部分截断并注明） */
    private const val MAX_DOC_CHARS = 12000

    /** 解压后总体积上限：文档都是 zip，防 zip 炸弹 */
    private const val MAX_DOC_BYTES = 40L * 1024 * 1024

    /** PDF 最多渲染 + 识别的页数（OCR 每页要几百毫秒，再多应该劝用户拆文件） */
    private const val MAX_PDF_PAGES = 20

    /** PDF 渲染放大倍数：光波导/文档扫描件的字通常很小，2 倍能让 OCR 命中率明显提升 */
    private const val PDF_RENDER_SCALE = 2

    /**
     * 从下载目录读取文档并转成纯文本。
     * @param path 相对下载目录的路径，如 `报告.docx` 或 `资料/年报.pdf`
     */
    fun parse(context: Context, path: String): String {
        val p = path.trim().trimStart('/')
        if (p.isEmpty()) return "请提供文档在下载目录里的路径，如「报告.docx」"
        val ext = p.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "docx", "xlsx", "pptx" -> parseZipDoc(context, p, ext)
            "pdf" -> parsePdf(context, p)
            "txt", "md", "csv", "json", "xml", "log", "html", "htm" ->
                "「$p」是纯文本，直接用 read_text_file 读即可（本工具只管 docx/xlsx/pptx/pdf）"
            else -> "不支持的文档格式（.${ext.ifEmpty { "无扩展名" }}）：目前只能解析 docx / xlsx / pptx / pdf。" +
                "如果是压缩包请先用 unzip_file 解压"
        }
    }

    // ═══════════════════════════════════════════════════
    // Office：zip + XML
    // ═══════════════════════════════════════════════════

    private fun parseZipDoc(context: Context, path: String, ext: String): String {
        val zip = try {
            FileWorkspace.openDownloadInput(context, path)?.use { readBounded(it) }
        } catch (e: Exception) {
            Log.w(TAG, "open $path failed: ${e.message}")
            null
        } ?: return "找不到「$path」：请确认它就在手机「下载」目录里（别的 App 建的、本 App 无权限的文件也读不到）"

        val entries = try {
            unzipEntries(zip)
        } catch (e: Exception) {
            Log.w(TAG, "unzip $path failed: ${e.message}")
            return "「$path」不是有效的 Office 文档（解压失败：${e.message}）"
        }

        val text = when (ext) {
            "docx" -> fromDocx(entries)
            "xlsx" -> fromXlsx(entries)
            else -> fromPptx(entries)
        }.trim()

        if (text.isEmpty()) {
            return "「$path」里没有提取到文字（可能是空文档，或内容全在图片里 —— 那种情况需要渲染成图再识别）"
        }
        return "《$path》内容如下：\n\n" + clamp(text)
    }

    /** 文档都是 zip：一次性解出全部条目（带总体积上限，防 zip 炸弹） */
    private fun unzipEntries(zip: ByteArray): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        var total = 0L
        ZipInputStream(ByteArrayInputStream(zip)).use { zis ->
            while (true) {
                val e = zis.nextEntry ?: break
                if (e.isDirectory) continue
                val buf = ByteArrayOutputStream()
                val chunk = ByteArray(8192)
                while (true) {
                    val n = zis.read(chunk)
                    if (n < 0) break
                    buf.write(chunk, 0, n)
                    total += n
                    if (total > MAX_DOC_BYTES) {
                        throw IOException("解压后超过 ${MAX_DOC_BYTES / 1024 / 1024}MB")
                    }
                }
                out[e.name] = buf.toByteArray()
            }
        }
        return out
    }

    /** docx：正文全在 `word/document.xml`，`<w:t>` 是文本片段，`</w:p>` 是段落边界 */
    private fun fromDocx(entries: Map<String, ByteArray>): String {
        val xml = entries["word/document.xml"]?.toString(Charsets.UTF_8) ?: return ""
        return xml.split("</w:p>")
            .joinToString("\n") { para ->
                val withBreaks = para.replace("<w:tab/>", "\t").replace("<w:br/>", "\n")
                Regex("<w:t(?:\\s[^>]*)?>([\\s\\S]*?)</w:t>")
                    .findAll(withBreaks)
                    .joinToString("") { xmlUnescape(it.groupValues[1]) }
            }
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    /** pptx：每页一个 `ppt/slides/slideN.xml`，`<a:t>` 是文本片段，`</a:p>` 是段落边界 */
    private fun fromPptx(entries: Map<String, ByteArray>): String {
        val slides = entries.keys
            .filter { Regex("ppt/slides/slide\\d+\\.xml").matches(it) }
            .sortedBy { Regex("(\\d+)").find(it)?.groupValues?.get(1)?.toIntOrNull() ?: 0 }
        if (slides.isEmpty()) return ""
        return slides.mapIndexed { i, name ->
            val xml = entries.getValue(name).toString(Charsets.UTF_8)
            val text = xml.split("</a:p>").joinToString("\n") { para ->
                Regex("<a:t>([\\s\\S]*?)</a:t>").findAll(para)
                    .joinToString("") { xmlUnescape(it.groupValues[1]) }
            }.replace(Regex("\n{2,}"), "\n").trim()
            "【第 ${i + 1} 页】\n$text"
        }.joinToString("\n\n")
    }

    /** xlsx：共享字符串表 + 每张工作表；单元格按 `t` 属性区分"共享串下标 / 内联串 / 字面值" */
    private fun fromXlsx(entries: Map<String, ByteArray>): String {
        val shared = entries["xl/sharedStrings.xml"]?.toString(Charsets.UTF_8)
            ?.let { xml ->
                Regex("<si>([\\s\\S]*?)</si>").findAll(xml).map { si ->
                    Regex("<t(?:\\s[^>]*)?>([\\s\\S]*?)</t>").findAll(si.groupValues[1])
                        .joinToString("") { xmlUnescape(it.groupValues[1]) }
                }.toList()
            } ?: emptyList()

        val sheets = sheetOrder(entries)
        if (sheets.isEmpty()) return ""
        return sheets.mapNotNull { (sheetName, part) ->
            val xml = entries[part]?.toString(Charsets.UTF_8) ?: return@mapNotNull null
            val rows = xml.split("</row>").mapNotNull { row ->
                val cells = Regex("<c([^>]*?)(?:/>|>([\\s\\S]*?)</c>)").findAll(row)
                    .map { xlsxCell(it.groupValues[1], it.groupValues[2], shared) }
                    .toList()
                    // 表格右侧大片空单元格对模型没意义，去掉行尾空列再判断本行是否为空
                    .dropLastWhile { it.isEmpty() }
                if (cells.isEmpty()) null else cells.joinToString(" | ")
            }
            if (rows.isEmpty()) null else "【$sheetName】\n" + rows.joinToString("\n")
        }.joinToString("\n\n")
    }

    /**
     * 单元格取值。
     * `t="s"` → `<v>` 是共享字符串下标；`t="inlineStr"` → 文本在 `<t>` 里；
     * 其余（含 `t="str"` 公式结果、纯数字）→ `<v>` 就是值本身。
     */
    private fun xlsxCell(attrs: String, inner: String, shared: List<String>): String {
        val v = Regex("<v>([\\s\\S]*?)</v>").find(inner)?.groupValues?.get(1)
        val raw = when {
            attrs.contains("t=\"s\"") -> v?.trim()?.toIntOrNull()?.let { shared.getOrNull(it) } ?: ""
            attrs.contains("t=\"inlineStr\"") -> Regex("<t(?:\\s[^>]*)?>([\\s\\S]*?)</t>")
                .findAll(inner).joinToString("") { xmlUnescape(it.groupValues[1]) }
            else -> v?.let { xmlUnescape(it) } ?: ""
        }
        return raw.replace('\n', ' ').trim()
    }

    /**
     * 工作表顺序：`workbook.xml` 里的 `<sheet name r:id>` 只有 rId，
     * 真正的部件文件名在 `xl/_rels/workbook.xml.rels` 里 —— 按 rels 映射才能拿到正确的表名与顺序
     * （只按 `sheetN.xml` 的数字排会错位：工作表可以改名、可以被删，文件名编号与显示顺序并不一致）。
     */
    private fun sheetOrder(entries: Map<String, ByteArray>): List<Pair<String, String>> {
        val wb = entries["xl/workbook.xml"]?.toString(Charsets.UTF_8)
        val rels = entries["xl/_rels/workbook.xml.rels"]?.toString(Charsets.UTF_8)
        if (wb != null && rels != null) {
            val relMap = Regex("<Relationship\\s[^>]*/>").findAll(rels).mapNotNull { m ->
                val id = Regex("Id=\"([^\"]+)\"").find(m.value)?.groupValues?.get(1) ?: return@mapNotNull null
                val target = Regex("Target=\"([^\"]+)\"").find(m.value)?.groupValues?.get(1)
                    ?: return@mapNotNull null
                id to when {
                    target.startsWith("/") -> target.removePrefix("/")
                    target.startsWith("xl/") -> target
                    else -> "xl/$target"
                }
            }.toMap()
            val ordered = Regex("<sheet\\s[^>]*/>").findAll(wb).mapNotNull { m ->
                val name = Regex("name=\"([^\"]*)\"").find(m.value)?.groupValues?.get(1)
                    ?: return@mapNotNull null
                val rid = Regex("r:id=\"([^\"]+)\"").find(m.value)?.groupValues?.get(1)
                    ?: return@mapNotNull null
                relMap[rid]?.let { name to it }
            }.toList()
            if (ordered.isNotEmpty()) return ordered
        }
        // 兜底：workbook/rels 缺失或结构异常时，按部件文件名排序，表名用文件名
        return entries.keys.filter { Regex("xl/worksheets/sheet\\d+\\.xml").matches(it) }
            .sortedBy { Regex("(\\d+)").find(it)?.groupValues?.get(1)?.toIntOrNull() ?: 0 }
            .map { it.substringAfterLast('/').removeSuffix(".xml") to it }
    }

    // ═══════════════════════════════════════════════════
    // PDF：系统渲染成图 → 本地 OCR
    // ═══════════════════════════════════════════════════

    @Suppress("DEPRECATION") // PdfRenderer(ParcelFileDescriptor) 在 API 35 起有替代构造器，低版本仍用这个
    private fun parsePdf(context: Context, path: String): String {
        val fd = FileWorkspace.openDownloadFd(context, path)
            ?: return "找不到「$path」：请确认它就在手机「下载」目录里"

        if (!LocalOcr.ensureInit(context, null)) {
            val why = when {
                LocalOcr.nativeUnavailable -> "本机不支持本地 OCR"
                else -> "OCR 模型还没准备好（首次使用需要下载，约 15MB）"
            }
            return "PDF 需要先渲染成图再本地识别文字，但现在用不了：$why。" +
                "请如实告诉用户这一条，并建议稍后重试或改用图片/文本格式"
        }

        val renderer = try {
            PdfRenderer(fd)
        } catch (e: SecurityException) {
            return "「$path」打不开：文件被密码保护（需要带密码的 PDF 请先在别的 App 里解开）"
        } catch (e: Exception) {
            Log.w(TAG, "PdfRenderer($path) failed: ${e.message}")
            return "「$path」不是有效 PDF（系统打不开：${e.message}）"
        }

        return try {
            val pages = minOf(renderer.pageCount, MAX_PDF_PAGES)
            if (pages <= 0) return "「$path」里没有可读页面"
            val sb = StringBuilder()
            for (i in 0 until pages) {
                val page = renderer.openPage(i)
                val text = try {
                    val w = (page.width * PDF_RENDER_SCALE).coerceAtLeast(1)
                    val h = (page.height * PDF_RENDER_SCALE).coerceAtLeast(1)
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    try {
                        // PdfRenderer 不铺底色：不先涂白，透明区域 OCR 会整片丢字
                        bmp.eraseColor(Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        LocalOcr.recognize(context, bmp)
                    } finally {
                        bmp.recycle()
                    }
                } finally {
                    page.close()
                }
                sb.append("【第 ${i + 1} 页】\n").append(text.trim()).append("\n\n")
            }
            val body = sb.toString().trim()
            val note = if (renderer.pageCount > pages) {
                "\n\n（共 ${renderer.pageCount} 页，这里只解析了前 $pages 页）"
            } else {
                ""
            }
            if (body.replace(Regex("【第 \\d+ 页】"), "").isBlank()) {
                return "「$path」里没有识别到文字（可能是纯图片页且画面太模糊，或扫描件质量过低）"
            }
            "《$path》文字识别结果（PDF 走的是本地识别，可能有错字）：\n\n" + clamp(body) + note
        } catch (e: Exception) {
            Log.w(TAG, "pdf ocr $path failed: ${e.message}")
            "「$path」解析失败：${e.message}。请如实告诉用户，不要凭文档名猜内容"
        } finally {
            runCatching { renderer.close() }
            runCatching { fd.close() }
        }
    }

    // ═══════════════════════════════════════════════════
    // 工具
    // ═══════════════════════════════════════════════════

    /** 带体积上限的整读（文档就是 zip，必须先落成字节数组才能当随机访问的包来解） */
    private fun readBounded(input: java.io.InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            total += n
            if (total > MAX_DOC_BYTES) {
                throw IOException("文档超过 ${MAX_DOC_BYTES / 1024 / 1024}MB，已放弃")
            }
        }
        return out.toByteArray()
    }

    private fun clamp(text: String): String =
        if (text.length <= MAX_DOC_CHARS) text
        else text.take(MAX_DOC_CHARS) + "\n\n…（文档较长，已截断，共 ${text.length} 字）"

    /**
     * XML 实体反转义。
     * 不复用 `WebTools.htmlUnescape`：那个要处理上百个 HTML 命名实体，
     * 而 XML 只有这 5 个预定义实体 + 数字实体 —— 两边的语义与规模都不同，合成一个反而容易出错。
     */
    private fun xmlUnescape(s: String): String {
        if (!s.contains('&')) return s
        return s.replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&amp;", "&")
            .replace(Regex("&#(\\d+);")) { m ->
                // ⚠️ 码点必须落在**合法范围**（1..0x10FFFF）内再交给 toChars：
                // `Character.toChars` 对越界值直接抛 IllegalArgumentException，而它被上层 catch 后
                // 表现为"整个文档解析失败"—— 畸形 docx 里一个 `&#1114112;` 就能造成这个结果，
                // 用户完全看不出问题出在哪个字符。越界时保留原文（可读、可定位），不抛异常。
                m.groupValues[1].toIntOrNull()
                    ?.takeIf { it in 1..0x10FFFF }
                    ?.let { cp -> runCatching { String(Character.toChars(cp)) }.getOrNull() }
                    ?: m.value
            }
    }
}
