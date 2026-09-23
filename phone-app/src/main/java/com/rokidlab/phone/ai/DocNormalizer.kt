package com.rokidlab.phone.ai

import org.json.JSONArray
import org.json.JSONObject

/**
 * 导入文档的**格式识别与规范化**：不管源文件是 txt / md / json，入库正文统一成
 * Markdown 这一种规范表示（canonical form）——预览渲染、编辑、切块、检索注入全都只面对 md。
 *
 * - [SourceFormat.MD]：Markdown 原样保留（标题/列表/围栏等结构正是切块与渲染要用的信息）。
 * - [SourceFormat.TXT]：纯文本原样保留 —— 纯文本本身就是合法的 Markdown（段落子集），
 *   不做"猜测哪行像标题就加 #"这类臆测式改写，任何误加标记都是对用户原文的污染。
 * - [SourceFormat.JSON]：合法 JSON（对象/数组）缩进美化后整体包进 ` ```json ` 围栏。
 *   刻意不做"按 key 展开成 md 字段"：真实 JSON schema 千差万别（深层嵌套、对象数组），
 *   通用展开既不可靠也不可逆；美化后的代码块对人可读、对模型可解析、对检索无损。
 *   解析失败（其实是改了扩展名的文本）→ 原样退回，绝不因"不合法"丢掉内容。
 */
object DocNormalizer {

    enum class SourceFormat(val columnValue: String) {
        MD("md"),
        TXT("txt"),
        JSON("json"),
    }

    /** 按文件名扩展名判定来源格式；未知扩展名按纯文本处理（与改造前行为一致） */
    fun formatFromName(name: String): SourceFormat {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "md", "markdown" -> SourceFormat.MD
            "json" -> SourceFormat.JSON
            else -> SourceFormat.TXT
        }
    }

    /**
     * 把已解码的原始文本规范化为 Markdown。
     *
     * JSON 路径需要完整文本才能解析（无法配合 8KB 流式窗口），由调用方全量读入后走这里；
     * md/txt 是恒等映射，调用方仍走流式导入路径。
     */
    fun normalize(raw: String, format: SourceFormat): String {
        if (format != SourceFormat.JSON) return raw
        val pretty = prettifyJson(raw) ?: return raw
        return buildString(pretty.length + 12) {
            append("```json\n")
            append(pretty)
            if (!pretty.endsWith('\n')) append('\n')
            append("```")
        }
    }

    /**
     * 美化 JSON（2 空格缩进）。只接受顶层对象/数组这两种主流形态；
     * 裸标量/非法 JSON/首尾有多余内容都返回 null，由调用方按纯文本兜底。
     */
    private fun prettifyJson(raw: String): String? {
        val head = raw.trimStart()
        return try {
            when (head.firstOrNull()) {
                '{' -> JSONObject(raw).toString(2)
                '[' -> JSONArray(raw).toString(2)
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }
}
