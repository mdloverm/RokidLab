package com.rokidlab.phone.platform

import android.content.Context
import android.os.Process
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 本机网页预览的**常驻进程**管理器（run_shell 做不到的那一块）。
 *
 * ## 为什么 run_shell 起不了网页服务
 * `run_shell` 是「等结果」的模型：600s 超时上限 + `--kill-on-exit`（proot 退出时连带清掉
 * 整棵进程树）。网页服务恰恰要**一直活着**——起完就被杀，用户永远打不开页面。
 *
 * ## 本管理器补上的能力
 *  - **常驻**：走 [ProotShell.startResident]，进程拉起来就返回，不设超时；
 *  - **端口自动分配**：宿主侧拿空闲端口注入 `PREVIEW_PORT` 环境变量，模型只管用变量
 *    （容器与宿主共享网络栈 ⇒ 宿主侧 `127.0.0.1:port` 直接可达，浏览器/WebView 直接打开）；
 *  - **健康检查**：启动后轮询「进程活着 + 端口可连」，起不来说清楚原因（带服务日志尾巴），
 *    不让模型对着一个已死的服务编故事；
 *  - **可停**：`stop` 走 SIGTERM（proot 的 `--kill-on-exit` 会连带清 tracee），
 *    杀不掉再 SIGKILL；
 *  - **孤儿清扫**：App 被杀时子进程会被遗留（野 proot + 野服务占着端口）。每个预览的
 *    proot 命令行里都有唯一脚本标记 `rl-preview-`，冷启动时按标记扫 `/proc` 清一次。
 *
 * ## 与 rootfs 安装/重装的关系
 * 常驻服务跑在被替换的 rootfs 上，`rm -rf rootfs` 等于从服务脚下抽走文件系统。
 * 所以 [ProotInstaller.install] / [uninstall] 动手前会先 [stopAll] —— 停服务是
 * 「重装容器」的前置动作，不是可选项。
 *
 * ## 状态暴露
 * [previews] 是 StateFlow：聊天页收集它渲染预览条（有服务在跑才显示），
 * 不需要 LabFileOutputs 那种 sink 注册——预览是**持续状态**，不是一次性产出。
 */
object WebPreviewManager {

    private const val TAG = "WebPreview"

    /** 脚本文件名标记：孤儿清扫按它认领"是我们起的 proot"（cmdline 里含脚本路径） */
    internal const val SCRIPT_MARKER = "rl-preview-"

    /** 同时在跑的预览上限：预览是常驻进程 + 常占端口，多了没有意义 */
    const val MAX_PREVIEWS = 2

    /** 启动健康检查的最长等待：python 导入大库、node 冷启都可能要几秒 */
    private const val HEALTH_WAIT_MS = 12_000L
    private const val HEALTH_POLL_MS = 300L

    /** SIGTERM 后给优雅退出的时间，超了才 SIGKILL */
    private const val GRACE_TERM_MS = 2_000L

    /** 每个预览保留的日志行数（报错给人看，够定位"起不来"即可） */
    private const val LOG_LINES = 60

    /** 给模型/用户看的预览描述 */
    data class PreviewInfo(
        val id: String,
        val port: Int,
        /** 启动命令（截断过，仅用于展示） */
        val command: String,
        val startedAtMs: Long,
    ) {
        /** 宿主侧访问地址（容器与宿主共享网络栈，直接可达） */
        val url: String get() = "http://127.0.0.1:$port"
    }

    /** 启动结果：[Started.note] 非空 = 有要向模型说明的保留意见（如端口尚未响应） */
    sealed class StartResult {
        data class Started(val info: PreviewInfo, val note: String?, val logTail: String) : StartResult()
        data class Failed(val message: String) : StartResult()
    }

    private class RingLog {
        private val lines = ArrayDeque<String>()
        fun append(line: String) {
            synchronized(lines) {
                lines.addLast(line)
                while (lines.size > LOG_LINES) lines.removeFirst()
            }
        }

        fun tail(n: Int): String = synchronized(lines) { lines.takeLast(n).joinToString("\n") }
    }

    private class Session(
        val info: PreviewInfo,
        // ⚠️ 必须写全限定：本文件 import 了 android.os.Process（kill 系统调用用），
        // 裸写 `Process` 会被解析成它，destroy/waitFor 全部 Unresolved
        val process: java.lang.Process,
        val scriptFile: File,
        val log: RingLog,
    )

    private val sessions = ConcurrentHashMap<String, Session>()

    private val _previews = MutableStateFlow<List<PreviewInfo>>(emptyList())

    /** 当前在跑的预览（聊天页据此显示预览条） */
    val previews: StateFlow<List<PreviewInfo>> get() = _previews

    private fun refreshState() {
        _previews.value = sessions.values.map { it.info }.sortedBy { it.startedAtMs }
    }

    // ═══════════════════ 启动 ═══════════════════

    /**
     * 启动一个常驻预览服务。**阻塞调用**（内含最长 ~12s 的健康检查），须在 IO 线程调。
     *
     * 启动前顺带做一次孤儿清扫（[sweepOrphans]）：上一轮 App 被杀遗留的野进程先清掉，
     * 否则它们占着端口、耗着 CPU，模型与用户都无从知晓。
     */
    fun start(context: Context, command: String, cwd: String): StartResult {
        val cmd = command.trim()
        if (cmd.isEmpty()) {
            return StartResult.Failed("命令为空，没有启动任何服务。请把启动命令放进 command 参数。")
        }
        if (!ProotShell.rootfsReady(context)) {
            return StartResult.Failed(
                "本机执行环境还没安装（缺少 Ubuntu 容器），没有启动任何服务。" +
                    "请如实告诉用户：需要到「设置 → 本机执行环境」点一下安装（约 28 MB，一次性下载）。不要假装服务已经启动。",
            )
        }
        if (sessions.size >= MAX_PREVIEWS) {
            val running = sessions.values.joinToString("、") { "${it.info.id}（${it.info.url}）" }
            return StartResult.Failed(
                "网页预览最多同时 $MAX_PREVIEWS 个，当前已有：$running。" +
                    "请先调 stop_web_preview 停掉不需要的，再启动新的。",
            )
        }
        sweepOrphans(context)

        val port = allocPort()
            ?: return StartResult.Failed("分配不到空闲端口（本机端口耗尽？），本次没有启动。")
        val id = "pv-${System.currentTimeMillis().toString(36)}"
        val info = PreviewInfo(id, port, cmd.take(120), System.currentTimeMillis())
        val script = buildString {
            if (cwd.isNotBlank() && cwd != "/") append("cd ${ProotShell.shellQuote(cwd)} || exit 90\n")
            append(cmd).append('\n')
        }

        val ring = RingLog()
        val resident = ProotShell.startResident(context, script, port) { line -> ring.append(line) }
            ?: return StartResult.Failed(
                "常驻进程没被拉起来（本机执行环境本身有问题）。请如实告诉用户，" +
                    "并建议他到「设置 → 本机执行环境」跑一次自检。",
            )
        val process = resident.process

        // 进程死了自动摘除（服务崩溃/被外部杀掉时，预览条不该继续挂着假状态）
        Thread {
            runCatching { process.waitFor() }
            if (sessions.remove(id) != null) {
                Log.i(TAG, "预览进程自行退出：$id")
                runCatching { resident.scriptFile.delete() }
                refreshState()
            }
        }.apply { isDaemon = true }.start()

        // 健康检查：进程活着 + 端口可连才算"起来了"
        val deadline = System.currentTimeMillis() + HEALTH_WAIT_MS
        var reachable = false
        while (System.currentTimeMillis() < deadline) {
            if (!process.isAlive) {
                process.waitFor(2, TimeUnit.SECONDS)
                return StartResult.Failed(
                    "服务启动后立即退出了（不是常驻命令？）。服务日志：\n${ring.tail(10)}\n\n" +
                        "常见原因：① 命令不是长期前台运行的服务（脚本跑完就退出）；" +
                        "② 缺依赖（装包要走 install_packages）；③ 端口没用 \$PREVIEW_PORT 变量。" +
                        "修正后可再次尝试。",
                )
            }
            if (portReachable(port)) {
                reachable = true
                break
            }
            Thread.sleep(HEALTH_POLL_MS)
        }

        sessions[id] = Session(info, process, resident.scriptFile, ring)
        refreshState()
        Log.i(TAG, "预览已启动：$id ${info.url}（${cmd.take(60)}）")
        return if (reachable) {
            StartResult.Started(info, note = null, logTail = ring.tail(5))
        } else {
            StartResult.Started(
                info,
                note = "进程活着但端口 $port 在 ${HEALTH_WAIT_MS / 1000}s 内没有响应。" +
                    "可能服务仍在初始化，也可能它没监听 \$PREVIEW_PORT。可稍后让用户直接打开 ${info.url} 试试。",
                logTail = ring.tail(5),
            )
        }
    }

    // ═══════════════════ 停止 ═══════════════════

    /**
     * 停止预览。[id] 为 null/空 = 停全部。返回给模型的人类可读结果（阻塞，IO 线程调）。
     */
    fun stop(context: Context, id: String?): String {
        if (sessions.isEmpty()) return "当前没有正在运行的网页预览，无需停止。"
        if (id.isNullOrBlank()) {
            val n = sessions.size
            sessions.keys.toList().forEach { stopOne(context, it) }
            return "已停止全部 $n 个网页预览。"
        }
        val s = sessions[id]
            ?: return "找不到 id=$id 的预览（可能已自行退出）。当前在跑的：${
                sessions.values.joinToString("、") { "${it.info.id}（${it.info.url}）" }.ifEmpty { "（无）" }
            }"
        val url = s.info.url
        stopOne(context, id)
        return "已停止预览 $id（$url）。"
    }

    /** rootfs 安装/重装/卸载前的强制清场（见类注释），全部停掉并记日志 */
    fun stopAll(context: Context, reason: String) {
        if (sessions.isEmpty()) return
        Log.i(TAG, "stopAll：$reason（${sessions.size} 个预览）")
        sessions.keys.toList().forEach { stopOne(context, it) }
    }

    private fun stopOne(context: Context, id: String) {
        val s = sessions.remove(id) ?: return
        runCatching {
            // 先 SIGTERM：proot 的 --kill-on-exit 会在此退出路径上连带清掉整棵 tracee 树
            s.process.destroy()
            if (!s.process.waitFor(GRACE_TERM_MS, TimeUnit.MILLISECONDS)) {
                s.process.destroyForcibly()
            }
        }.onFailure { Log.w(TAG, "停止预览 $id 出错：${it.message}") }
        runCatching { s.scriptFile.delete() }
        refreshState()
    }

    // ═══════════════════ 孤儿清扫 ═══════════════════

    /**
     * 清扫上一轮 App 被杀遗留的野进程。
     *
     * 判据：`/proc/<pid>/cmdline` 含 [SCRIPT_MARKER] 的进程 = 我们起的 proot
     * （proot 的 argv 里有脚本路径，而脚本路径唯一含此标记；同 UID 之外读不到，不会误伤）。
     * 对每个命中走 [killTree]（先杀子进程再杀 proot，proot 死了 tracee 才不会被漏掉）。
     *
     * 幂等、无副作用：没有任何命中时只是空扫一遍 `/proc`。
     */
    fun sweepOrphans(context: Context) {
        runCatching {
            val ours = File("/proc").listFiles()
                ?.filter { it.name.matches(Regex("\\d+")) }
                ?.filter { f ->
                    runCatching {
                        File(f, "cmdline").readBytes().toString(Charsets.UTF_8).contains(SCRIPT_MARKER)
                    }.getOrDefault(false)
                }
                ?.mapNotNull { it.name.toIntOrNull() }
                .orEmpty()
            if (ours.isEmpty()) return
            Log.w(TAG, "发现 ${ours.size} 个遗留的预览进程，清理中：$ours")
            ours.forEach { killTree(it) }
        }.onFailure { Log.w(TAG, "sweepOrphans 失败：${it.message}") }
    }

    /**
     * 杀掉一棵进程树（同 UID 进程，`android.os.Process` 的 kill 权限足够）：
     * 先 SIGTERM 子进程与自己，宽限期后对幸存者 SIGKILL。
     * 用于孤儿清扫——那棵树上没有 Java `Process` 句柄，只能按 pid 动手。
     */
    /** 杀掉一棵进程树（同 UID 进程）。internal：MCP stdio 桥的孤儿清扫复用同一实现。 */
    internal fun killTree(pid: Int) {
        val children = childPids(pid)
        children.forEach { Process.sendSignal(it, 15) } // SIGTERM
        Process.sendSignal(pid, 15)
        val deadline = System.currentTimeMillis() + GRACE_TERM_MS
        while (System.currentTimeMillis() < deadline) {
            if (children.none { procAlive(it) } && !procAlive(pid)) return
            runCatching { Thread.sleep(150L) }
        }
        children.filter { procAlive(it) }.forEach { Process.killProcess(it) }
        if (procAlive(pid)) Process.killProcess(pid)
    }

    /** 直接子进程 pid 列表（读 `/proc/<pid>/stat` 的 ppid 字段）。internal：供 MCP 桥清扫复用 */
    internal fun childPids(parentPid: Int): List<Int> =
        File("/proc").listFiles()
            ?.filter { it.name.matches(Regex("\\d+")) }
            ?.mapNotNull { f ->
                runCatching {
                    // comm 可能含空格与括号：取最后一个 ')' 之后的字段，ppid 是其中第 2 个
                    val stat = File(f, "stat").readText()
                    val fields = stat.substringAfterLast(')').trim().split(' ')
                    val ppid = fields.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
                    if (ppid == parentPid) f.name.toIntOrNull() else null
                }.getOrNull()
            }.orEmpty()

    private fun procAlive(pid: Int): Boolean = File("/proc/$pid").exists()

    // ═══════════════════ 内部 ═══════════════════

    /** 宿主侧拿一个空闲回环端口（分完即关， guest 随后绑定它） */
    private fun allocPort(): Int? = runCatching {
        ServerSocket().use { s ->
            s.bind(InetSocketAddress("127.0.0.1", 0))
            s.localPort
        }
    }.getOrNull()

    /** 端口是否可连（TCP 层即可，不要求 HTTP） */
    private fun portReachable(port: Int): Boolean = runCatching {
        Socket().use { s ->
            s.connect(InetSocketAddress("127.0.0.1", port), 250)
            true
        }
    }.getOrDefault(false)
}
