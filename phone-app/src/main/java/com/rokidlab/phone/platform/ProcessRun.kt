package com.rokidlab.phone.platform

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 一次外部进程执行的结果。
 *
 * [failure] 非空表示**这一轮没拿到「起来了且正常返回」的结果**（与"起来了但返回非 0"是两类事，
 * 必须分开说）。它有两种成因，靠 [timedOut] 区分：
 *  - **根本没起来**（[timedOut] = false）：`启动失败：IOException: …: error=13, Permission denied`
 *    ⇒ SELinux 域不许 exec；`error=2, No such file or directory` ⇒ 二进制不在位。
 *  - **起来了但超时被强杀**（[timedOut] = true）：`超时 180s（已强杀）` —— 环境没问题，
 *    是那条命令自己没结束。
 */
data class ExecResult(
    val code: Int,
    val stdout: String,
    val stderr: String,
    val failure: String? = null,
    /**
     * 超时被强杀 —— 进程**起来过**，只是没在时限内结束。
     *
     * ⚠️ 必须与 [failure] 的另一半（"根本没起来"：二进制缺失 / SELinux 挡住）分开：
     * 2026-10-12 实锤，两者在 [failure] 上长得一模一样，上层把「超时」汇报成
     * 「命令没被拉起来（环境本身有问题）」，把模型和用户的排查方向整个带偏。
     */
    val timedOut: Boolean = false,
) {
    /** 进程是否真的被拉起来了（**超时也算拉起来了** —— 它跑过，只是没结束） */
    val launched: Boolean get() = failure == null || timedOut

    val ok: Boolean get() = launched && code == 0

    /** 单行摘要，便于落日志 */
    fun summary(): String = when {
        timedOut -> "超时中断（$failure）"
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
 *  2. 超时必须把进程收掉（[stopProcess]：先 SIGTERM 再 SIGKILL）——
 *     否则调用方永久挂住（解压 350 MB 的文件时尤其危险）。
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
            // stdin 必须接 /dev/null：ProcessBuilder 默认继承宿主进程的 stdin（永不 EOF），
            // guest 里的交互程序（java Scanner / bash read / python input 走独立 stdin 的场景）
            // 会**永远阻塞等输入** → 挂满超时。所有数据投喂都走脚本内管道，没有任何调用方
            // 需要经宿主 stdin 传数据（2026-10-01 真机实锤：代码卡跑猜数字游戏挂满 120s）。
            pb.redirectInput(File("/dev/null"))
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
                stopProcess(proc)
                outThread.join(500L)
                errThread.join(500L)
                return ExecResult(
                    -1, out.toString().trim(), err.toString().trim(),
                    "超时 ${timeoutSec}s（已强杀）", timedOut = true,
                )
            }
            outThread.join(1_000L)
            errThread.join(1_000L)
            ExecResult(proc.exitValue(), out.toString().trim(), err.toString().trim())
        } catch (e: Exception) {
            // IOException 的 message 自带 errno（error=13 / error=2 …），这正是判据所在
            ExecResult(-1, "", "", "启动失败：${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /**
     * 超时回收：**先 SIGTERM 再 SIGKILL** —— 别直接糊 SIGKILL。
     *
     * ## 为什么不能只 `destroyForcibly()`
     * 那一发是 SIGKILL，只作用于被直接拉起的那个进程（proot）自己。而 proot 是 tracer：
     * 正常路径上它会把信号转给 tracee（guest 里的 bash）并跑 `--kill-on-exit` 的清理；
     * SIGKILL 把这条路径整个绕过 ⇒ guest 那一侧的进程没人接管，被 reparent 到 init 继续跑
     * （占 CPU / IO，而且我们再也拿不到它们的 pid）。先用**可捕获的** SIGTERM 给它一条
     * 正常退出路径，3 秒不退再 SIGKILL 兜底。
     *
     * ## 已知不保证（保守处理，留痕）
     * 若 proot 卡到连 SIGTERM 都处理不了，兜底的 SIGKILL 仍可能留下 guest 残留进程。
     * 根治要在超时前枚举 proot 的整棵进程树 —— 但 Android 的 `java.lang.Process`
     * **没有 `pid()`**，只能按脚本名扫 `/proc/<pid>/cmdline` 反查，代价明显大于收益，故暂不做。
     * 重开条件：再次在真机上观察到「超时后遗留容器进程」（`ps` 里 proot/bash/node 仍在）。
     */
    private fun stopProcess(proc: Process) {
        runCatching { proc.destroy() }
        val exited = runCatching { proc.waitFor(3, TimeUnit.SECONDS) }.getOrDefault(false)
        if (!exited) runCatching { proc.destroyForcibly() }
    }
}
