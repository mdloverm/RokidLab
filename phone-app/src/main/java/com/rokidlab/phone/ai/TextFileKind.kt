package com.rokidlab.phone.ai

/**
 * 「这个文件能不能当文本读」的唯一定产地。
 *
 * 为什么要单独抽出来：同一判定有两个消费方，且分属两层 ——
 *  - **UI 层**（用户选文件时的类型闸门 + 文件卡的扩展名标签）：`store/ChatAttachments`；
 *  - **ai 层**（容器产出文件的成果筛选）：[LabFileOutputs]。
 * ai 层不能反过来依赖 store（那是 UI），所以口径放这里，两边都调它。
 *
 * ⚠️ 白名单而不是黑名单：模型只能读文本，未知扩展名（`.xyz`、无后缀）默认**拒绝** ——
 * 把二进制解成乱码发过去，模型的回答会离谱，而用户看到的现象只是"AI 变傻了"，
 * 根本联想不到是文件格式的问题。
 */
internal object TextFileKind {

    /**
     * 能给模型读的文本类扩展名。
     * 放开新格式就往这里加 —— 前提是它真是纯文本。
     */
    private val TEXT_EXTS = setOf(
        "txt", "text", "md", "markdown", "log", "csv", "tsv", "json", "jsonl", "xml", "yml", "yaml",
        "ini", "conf", "cfg", "properties", "env", "toml", "sql", "srt", "vtt",
        "kt", "kts", "java", "py", "js", "ts", "tsx", "jsx", "c", "h", "cpp", "hpp", "cs", "go",
        "rs", "swift", "m", "mm", "rb", "php", "sh", "bat", "ps1", "gradle", "pro", "html", "htm",
        "css", "scss", "less", "vue", "dart", "lua", "r", "pl", "asm", "makefile", "dockerfile",
    )

    /** 文件名的扩展名（小写、无点）；没有扩展名返回空串 */
    fun extOf(name: String): String =
        name.substringAfterLast('.', "").lowercase()
            .takeIf { it.length in 1..8 && it != name.lowercase() }
            ?: ""

    /**
     * 文件选择器（SAF）用的 MIME 过滤列表 —— 必须与上面的白名单**同源**。
     *
     * 为什么必须同源：选择器放行、读的时候又拒绝，用户看到的是「能选却发不出去」，
     * 比一开始就看不到更让人困惑（2026-09-24 用户要求"只显示能识别的格式"）。
     *
     * ⚠️ 只能**收窄**、不能封闭：SAF 的 MIME 是按扩展名猜出来的，`.kt` / `.md` / 无后缀的
     *   `Makefile` 在多数机型上落到纯文本通配或干脆 `application/octet-stream`，后者无法用
     *   白名单表达 ⇒ 这里用纯文本通配 + 少量明确的应用型 MIME 覆盖绝大多数；
     *   真漏掉了，读入侧 [isTextLike] 仍会给准确提示，而不是静默发一堆乱码给模型。
     *
     * ⚠️ 注释里**不要**写出"纯文本通配"的实际字面量（斜杠加星号）：Kotlin 块注释可嵌套，
     *   那一对字符会把注释重新打开，报 Unclosed comment 且错误行飘到文件末尾。
     */
    val PICKER_MIMES: Array<String> = arrayOf(
        "text/*",
        "application/json",
        "application/xml",
        "application/javascript",
        "application/x-yaml",
        "application/yaml",
        "application/toml",
        "application/x-sh",
    )

    /**
     * 图片选择器可放行的 MIME：只列**能解码成 Bitmap** 的几种，
     * 避免选了 heic/svg 之类解不出来、到发送时才报错（svg 尤其常见，它根本不是位图）。
     */
    val PICKER_IMAGE_MIMES: Array<String> = arrayOf(
        "image/jpeg", "image/png", "image/webp", "image/gif", "image/bmp",
    )

    /**
     * 能否当文本读。
     *
     * @param mime 可空（SAF 有时不给）；只有**没有扩展名**、但 MIME 明确以 `text/` 开头时才放行
     *   —— 反过来（MIME 说 text、扩展名是 .pdf）不救：那种多半是服务端给错了 MIME。
     */
    fun isTextLike(name: String, mime: String?): Boolean {
        val ext = extOf(name)
        if (ext.isNotEmpty() && TEXT_EXTS.contains(ext)) return true
        return ext.isEmpty() && mime?.startsWith("text/", ignoreCase = true) == true
    }
}
