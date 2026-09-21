package com.rokidlab.phone.ai

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * 文本编码判定：把「一段字节」判成该用哪个字符集解码。
 *
 * 为什么必须有这一层（2026-09-20 真机 bug）：
 * 知识库导入原先**无条件按 UTF-8 解码**。中文用户从 Windows 记事本/各类导出工具拿到
 * 的 txt 常是「ANSI(GBK)」或「Unicode(UTF-16LE)」，按 UTF-8 硬解后整篇变成替换符
 * （实测 59 字里 45 个 U+FFFD），而**文档名与字节数照旧正确显示** ⇒ 列表里看不出任何
 * 异常。症状于是表现为"文档明明导入成功了，问里面的内容却永远答不出来" ——
 * 检索侧完全无辜，它只是没东西可命中。
 *
 * 判定顺序（先事实、后猜测，且猜测永远取保守项）：
 *  1. **BOM 明确**（UTF-8 / UTF-16LE / UTF-16BE）⇒ 按 BOM 走，不做探测；
 *  2. **无 BOM 但含大量 NUL** ⇒ UTF-16（NUL 是合法 UTF-8，不先拦会被判成 UTF-8 而出现满屏空洞）；
 *  3. 对**前 [PROBE_BYTES] 字节**做严格 UTF-8 解码，合法即 UTF-8；
 *  4. 仍不合法 ⇒ GB18030（GBK 的超集，覆盖中文 Windows「ANSI」的全部现实情况）。
 *
 * 纯 JVM 实现（不依赖 Android / SQLite）⇒ 可直接单测，见 `TextEncodingTest`。
 */
object TextEncoding {

    /** 判定结果：[charset] 用哪个字符集解码、[bomBytes] 开头跳过几个字节（BOM 本身不是内容）。 */
    data class Decision(val charset: Charset, val bomBytes: Int)

    /**
     * 探测样本上限：64KB 足够判定任意现实编码，同时让「整文件解码」场景不会为了判定
     * 把大文件多解一遍（`decide` 内部只吃前这么多字节）。
     */
    const val PROBE_BYTES = 64 * 1024

    private val UTF8: Charset = Charsets.UTF_8

    /**
     * GB18030 是 GBK 的超集 ⇒ 用它解 GBK 文本同样正确，一个字符集覆盖「ANSI」。
     * 取不到时逐级退回（宁可乱码也不要抛异常 —— 导入链路的兜底不该是崩溃）。
     */
    private val GB: Charset = charsetOrNull("GB18030") ?: charsetOrNull("GBK") ?: UTF8

    /** 按判定结果把整段字节解码成文本（适用于小文件 / 已整体读入内存的场景）。 */
    fun decode(bytes: ByteArray): String {
        val d = decide(bytes)
        return String(bytes, d.bomBytes, bytes.size - d.bomBytes, d.charset)
    }

    fun decide(bytes: ByteArray): Decision {
        // 1) BOM 优先 —— 带 BOM 的文件编码是**明确事实**，不该再猜
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        ) {
            return Decision(UTF8, 3)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return Decision(charsetOrNull("UTF-16LE") ?: UTF8, 2)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return Decision(charsetOrNull("UTF-16BE") ?: UTF8, 2)
        }
        // 2) 无 BOM 的 UTF-16：文本里会密集出现 0x00。必须排在 UTF-8 判定**之前** ——
        //    0x00 是合法 UTF-8 单字节，若不先拦，这种文件会被"成功"解成隔字带空洞的乱码。
        detectUtf16WithoutBom(bytes)?.let { return Decision(it, 0) }
        // 3) 严格 UTF-8 试解：过得了就是 UTF-8，过不了就落到 GB
        return if (isStrictUtf8(bytes)) Decision(UTF8, 0) else Decision(GB, 0)
    }

    /**
     * 无 BOM 的 UTF-16 判定：按 NUL 密度 + NUL 落在奇/偶位判断。
     *
     * ASCII 占主导的文本在 UTF-16 里每个字符带一个 0x00 ⇒ NUL 占比接近 50%。
     * 阈值取 1/5 已经很宽（正常 UTF-8 文本几乎不可能含 NUL），不会误伤正常文件。
     * 样本只取前 4KB：判定"是不是 UTF-16"不需要更多。
     */
    private fun detectUtf16WithoutBom(bytes: ByteArray): Charset? {
        val len = minOf(bytes.size, 4096)
        if (len < 4) return null
        var zeros = 0
        var zerosAtOdd = 0
        var zerosAtEven = 0
        for (i in 0 until len) {
            if (bytes[i] == 0.toByte()) {
                zeros++
                if (i % 2 == 1) zerosAtOdd++ else zerosAtEven++
            }
        }
        if (zeros * 5 < len) return null
        // 小端：高字节在后（下标为奇数）为 0；大端反之
        return if (zerosAtOdd >= zerosAtEven) charsetOrNull("UTF-16LE") else charsetOrNull("UTF-16BE")
    }

    /**
     * 前 [PROBE_BYTES] 字节能否**严格**（不许替换、不许丢弃）解成 UTF-8。
     *
     * ⚠️ 允许样本尾部最多 3 字节不参与判定：流式导入的 64KB 样本、或整文件探测点，
     * 都可能正好把一个多字节字符切成两半 —— 不容忍这一点，会把**正常的 UTF-8 文件**
     * 误判成 GB，那是比"不判定"更糟的反向破坏（把好的文档弄成乱码）。
     */
    private fun isStrictUtf8(bytes: ByteArray): Boolean {
        var end = minOf(bytes.size, PROBE_BYTES)
        // 最多回退 3 字节（UTF-8 单字符最长 4 字节）
        repeat(4) {
            if (end <= 0) return true
            if (strictDecodes(bytes, end)) return true
            end--
        }
        return false
    }

    private fun strictDecodes(bytes: ByteArray, end: Int): Boolean = try {
        UTF8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes, 0, end))
        true
    } catch (e: CharacterCodingException) {
        false
    }

    private fun charsetOrNull(name: String): Charset? =
        runCatching { Charset.forName(name) }.getOrNull()
}
