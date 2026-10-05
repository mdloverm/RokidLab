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

    /**
     * 二进制资源的 base64 内联上限。
     *
     * ⚠️ 为什么音频**必须**内联（2026-10-05 从 wasm 取证）：
     *   ink 把 aix 挂成虚拟文件系统（wasm 里有 `/__ink_bundle__/`、`ink://`、
     *   `"VFS resources must use relative or ..."`），音频由 `Resolved Web audio src=`
     *   在 VFS 里查；查不到就打 `"Web audio bundle lookup miss for src="`。
     *   `host.js` 的 `toFiles()` 只会把 b64 解成 Uint8Array，**没有"路径"这条形态**
     *   ⇒ 「落盘 + 传 path」方案走不通，**只能调大阈值**。
     *
     * 代价（base64 膨胀 ~33%，随后进 JSON、跨 JNI、再解成 Uint8Array，三处都有拷贝）：
     *   1.84MB wav → 2.34MB base64 → JSON 转义后 ~2.46MB → 峰值约 5MB
     * 眼镜堆内存量级 96-256MB，音频级 8MB 上限（base64 后 10.7MB、峰值约 13MB）仍安全。
     */
    private val MAX_BINARY_B64 = 1_500_000      // 图片等普通二进制：1.5MB
    private val MAX_AUDIO_B64 = 8_388_608       // 音频：8MB（覆盖常见 BGM 长度）

    /** 音频扩展名：走更宽的上限（见 MAX_AUDIO_B64 注释） */
    private val AUDIO_EXT = setOf(
        "mp3", "wav", "ogg", "m4a", "aac", "flac", "opus", "weba", "aiff"
    )

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
                        // 音频走更宽的上限：ink 的音频只能内联进 bundle（见 MAX_AUDIO_B64 注释），
                        // 超阈值被跳过 ⇒ VFS 查不到 ⇒ "Web audio bundle lookup miss" ⇒ BGM/音效全无声。
                        val limit = if (ext in AUDIO_EXT) MAX_AUDIO_B64 else MAX_BINARY_B64
                        if (bytes.size <= limit) {
                            files[clean] = FileVal(b64 = Base64.encodeToString(bytes, Base64.NO_WRAP))
                        } else {
                            skipped++
                            Log.w(
                                TAG,
                                "skip oversized ${if (ext in AUDIO_EXT) "audio" else "binary"}: " +
                                    "$clean (${bytes.size}B > ${limit}B)",
                            )
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
