package com.rokidlab.phone.platform

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 一次外部进程执行的结果。
 *
 * [failure] 非空表示**进程根本没起来**（与"起来了但返回非 0"是两类事，必须分开说）：
 *  - `启动失败：IOException: …: error=13, Permission denied` ⇒ SELinux 域不许 exec
 *  - `error=2, No such file or directory` ⇒ 二进制不在位（例如 `nativeLibraryDir` 是空目录）
 */
data class ExecResult(
    val code: Int,
    val stdout: String,
    val stderr: String,
    val failure: String? = null,
) {
    /** 进程是否真的被拉起来了（能拉起才谈得上"返回值"） */
    val launched: Boolean get() = failure == null

    val ok: Boolean get() = launched && code == 0

    /** 单行摘要，便于落日志 */
    fun summary(): String = when {
        !launched -> "未拉起（$failure）"
        code == 0 -> "OK（退出码 0）"
        else -> "退出码 $code"
    }

    /** stderr 末几行 —— 报错时给人看的通常就是这几行（tar 的中途告警会淹没开头） */
    fun stderrTail(lines: Int = 3): String =
        stderr.lineSequence().filter { it.isNotBlank() }.toList().takeLast(lines).joinToString(" / ")
}

/**
 * 最小可用的**外部进程执行器**：统一收 stdout/stderr/退出码，带超时强杀。
 *
 * ## 为什么要抽出来
 * proot 与设备自带工具（`tar` / `rm`）都得用同一套方式被拉起来，而这套里有两条
 * **写第二遍必漏一处**的细节：
 *  1. 两个管道必须**并发**读 —— 子进程写满管道时会阻塞在写，而父进程阻塞在读另一个流，
 *     双向死锁（表现为"命令永远不返回"）；
 *  2. 超时必须 `destroyForcibly()` —— 否则调用方永久挂住（解压 350 MB 的文件时尤其危险）。
 *
 * ## ⚠️ 不做 shell 展开
 * 参数**逐项传数组**，绝不拼接命令行字符串：路径里出现空格/引号不会被拆错，
 * 也避免把工具入参（未来来自模型）变成 shell 注入面。需要 shell 语义时，
 * 由调用方**显式**走 `bash script`（见 [ProotShell.runScript]）。
 */
object ProcessRun {

    /**
     * 执行设备自带工具：`/system/bin/<name>`。
     *
     * Android 10+ 的 `/system/bin` 是 toybox 的 applet 目录，`tar`/`rm` 等都在这里；
     * 不存在时返回**明确的** failure（而不是抛异常），便于上层给出可行动的提示。
     */
    fun runTool(name: String, args: List<String>, timeoutSec: Long = 300L): ExecResult =
        run(File("/system/bin", name), args, timeoutSec)

    fun run(
        bin: File,
        args: List<String>,
        timeoutSec: Long = 300L,
        workDir: File? = null,
        env: Map<String, String> = emptyMap(),
        /** stdout 逐行回调（在读线程上触发，调用方需自行保证线程安全）；默认 null = 不回调 */
        onStdoutLine: ((String) -> Unit)? = null,
        /** stderr 逐行回调（在读线程上触发，调用方需自行保证线程安全）；默认 null = 不回调 */
        onStderrLine: ((String) -> Unit)? = null,
    ): ExecResult {
        if (!bin.exists()) return ExecResult(-1, "", "", "二进制不在位：${bin.absolutePath}")
        return try {
            val pb = ProcessBuilder(listOf(bin.absolutePath) + args)
            if (workDir != null) pb.directory(workDir)
            env.forEach { (k, v) -> pb.environment()[k] = v }
            val proc = pb.start()

            val out = StringBuilder()
            val err = StringBuilder()
            val outThread = Thread {
                runCatching {
                    proc.inputStream.bufferedReader().forEachLine {
                        out.appendLine(it)
                        onStdoutLine?.invoke(it)
                    }
                }
            }
            val errThread = Thread {
                runCatching {
                    proc.errorStream.bufferedReader().forEachLine {
                        err.appendLine(it)
                        onStderrLine?.invoke(it)
                    }
                }
            }
            outThread.isDaemon = true
            errThread.isDaemon = true
            outThread.start()
            errThread.start()

            val finished = proc.waitFor(timeoutSec, TimeUnit.SECONDS)
            if (!finished) {
                proc.destroyForcibly()
                outThread.join(500L)
                errThread.join(500L)
                return ExecResult(-1, out.toString().trim(), err.toString().trim(), "超时 ${timeoutSec}s（已强杀）")
            }
            outThread.join(1_000L)
            errThread.join(1_000L)
            ExecResult(proc.exitValue(), out.toString().trim(), err.toString().trim())
        } catch (e: Exception) {
            // IOException 的 message 自带 errno（error=13 / error=2 …），这正是判据所在
            ExecResult(-1, "", "", "启动失败：${e.javaClass.simpleName}: ${e.message}")
        }
    }
}
