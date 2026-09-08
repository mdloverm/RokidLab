package com.rokidlab.rokidlink

import android.util.Base64
import android.util.Log
import java.io.File
import java.util.zip.ZipFile

/**
 * .aix 解包器：.aix 本质是 zip（VERSION / app.json / app.js / AGENTS.md / pages/<page>/index.ink ...）。
 * 解包结果交给 AiuiLinkActivity 的 WebView 宿主，以 openBundle(files) 方式交给官方 ink 引擎。
 */
object AixBundleReader {
    private const val TAG = "AixBundleReader"

    private val TEXT_EXT = setOf(
        "json", "js", "ink", "wxml", "wxss", "ts", "css", "md", "txt", "html",
        "svg", "xml", "yml", "yaml", "tts", "map", "gitignore", "properties"
    )

    /** 单个 bundle 文件：文本直存，二进制转 base64（openBundle 支持 Uint8Array） */
    data class FileVal(val text: String? = null, val b64: String? = null)

    data class Bundle(
        val appId: String,
        val files: LinkedHashMap<String, FileVal>,
        val initialPage: String?,
    )

    private val MAX_BINARY_B64 = 1_500_000 // 超过 1.5MB 的二进制资源跳过（生成项目基本纯文本+小图）

    fun read(aix: File, appIdFallback: String? = null): Bundle {
        val files = LinkedHashMap<String, FileVal>()
        var appJsonText: String? = null
        var skipped = 0
        runCatching {
            ZipFile(aix).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val e = entries.nextElement()
                    if (e.isDirectory) continue
                    val name = e.name
                    // 去开头的 package 目录层级（官方 .aix 里页面路径以 / 开头时不带盘符）
                    val clean = name.removePrefix("/").removePrefix("package/")
                    if (clean.isEmpty() || clean.startsWith("__MACOSX")) continue
                    val ext = clean.substringAfterLast('.', "").lowercase()
                    val isText = ext in TEXT_EXT || ext.isEmpty() // VERSION 等无扩展名按文本
                    val stream = zip.getInputStream(e)
                    if (isText) {
                        val text = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                        files[clean] = FileVal(text = text)
                        if (clean == "app.json") appJsonText = text
                    } else {
                        val bytes = stream.readBytes()
                        if (bytes.size <= MAX_BINARY_B64) {
                            files[clean] = FileVal(b64 = Base64.encodeToString(bytes, Base64.NO_WRAP))
                        } else {
                            skipped++
                            Log.w(TAG, "skip oversized binary: $clean (${bytes.size}B)")
                        }
                    }
                }
            }
        }.onFailure { Log.e(TAG, "unzip fail: ${aix.name}", it) }

        if (files.isEmpty()) {
            Log.e(TAG, "empty bundle: ${aix.absolutePath}")
        }

        val appId = appIdFallback ?: aix.name.removeSuffix(".aix")
        val initialPage = parseInitialPage(appJsonText)
        Log.i(TAG, "read bundle $appId files=${files.size} initialPage=$initialPage skipped=$skipped")
        return Bundle(appId, files, initialPage)
    }

    private fun parseInitialPage(appJson: String?): String? {
        if (appJson.isNullOrBlank()) return null
        return runCatching {
            val arr = org.json.JSONObject(appJson).optJSONArray("pages")
            if (arr != null && arr.length() > 0) arr.getString(0) else null
        }.getOrNull()
    }
}
