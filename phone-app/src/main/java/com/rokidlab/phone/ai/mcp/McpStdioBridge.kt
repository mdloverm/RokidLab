package com.rokidlab.phone.ai.mcp

import android.content.Context
import android.util.Log
import com.rokidlab.phone.platform.ProotShell
import com.rokidlab.phone.platform.WebPreviewManager
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * stdio MCP server 的**回环桥**管理器：把「本地进程型 MCP server」接进现有 HTTP 通道。
 *
 * ## 为什么需要它
 * 生态里约一半的 MCP server 是 stdio 形态（`npx xxx` / `python3 xxx.py`，通过 stdin/stdout
 * 逐行 JSON-RPC 通信）。手机上没有能直接跑它们的宿主 shell，但**自持 proot 就是干这个的**：
 * 桥脚本（纯 Python 标准库，随 assets 下发）在容器里把子进程的 stdio 包成
 * Streamable HTTP 端点，绑在 `127.0.0.1:<port>` —— 容器与宿主共享网络栈，
 * `McpClient` 走既有回环 http 白名单**零协议改动**接入。
 *
 * ## 生命周期
 *  - `McpRegistry.connect`（配置启用/重连）时 [ensureStarted]：懒启动，每个 server 一个桥；
 *  - `McpRegistry.disconnect` / 配置删除时 [stop]；
 *  - rootfs 安装/重装/卸载前由调用方 [stopAll]（与 `WebPreviewManager.stopAll` 同一时机）；
 *  - App 被杀遗留的野进程：[sweepOrphans] 按脚本标记 `rl-mcp-` 清扫（在 `/proc` 里认领，
 *    只杀仍登记在册之外的 —— 与网页预览的 `rl-preview-` 标记分开，互不误伤）。
 *
 * ## 两个环境前提（不满足时给**可读错误**，不静默失败）
 *  1. rootfs 已安装（[ProotShell.rootfsReady]）；
 *  2. 容器里有 python3（桥脚本只用标准库，但 ubuntu-base 不带 python3；
 *     没有时报错文案直接给出安装路径）。
 */
internal object McpStdioBridge {

    private const val TAG = "McpStdioBridge"

    /** 桥脚本在 assets 里的路径（随包分发，连接时拷进 extras 目录） */
    private const val ASSET_PATH = "mcp/mcp_stdio_bridge.py"

    /** extras 里桥脚本的宿主侧相对路径；guest 侧固定挂载在 /opt/extras（见 ProotShell） */
    private const val BRIDGE_REL = "mcp/mcp_stdio_bridge.py"
    private const val GUEST_BRIDGE = "/opt/extras/$BRIDGE_REL"

    /** 脚本文件名标记：孤儿清扫按它认领"是我们起的 proot" */
    internal const val SCRIPT_MARKER = "rl-mcp-"

    /** 桥健康检查等待：python 冷启 + 子进程 spawn，几秒内应完成 */
    private const val HEALTH_WAIT_MS = 10_000L
    private const val HEALTH_POLL_MS = 250L

    private class Session(
        val port: Int,
        val process: java.lang.Process,
        val scriptFile: File,
        val log: MutableList<String>,
    )

    /** serverId → 在跑的桥 */
    private val sessions = ConcurrentHashMap<String, Session>()

    /** 某 server 的桥是否在跑 */
    fun isRunning(serverId: String): Boolean = sessions.containsKey(serverId)

    /**
     * 确保 [config] 的桥在跑，返回回环端口。**阻塞调用**（内含健康检查），须在 IO 线程调。
     *
     * @return 成功 = 端口；失败 = 面向用户的可读原因（McpRegistry 会记进 ServerState 展示）
     */
    fun ensureStarted(ctx: Context, config: McpServerConfig): Result<Int> {
        sessions[config.id]?.let { return Result.success(it.port) }
        if (!ProotShell.rootfsReady(ctx)) {
            return Result.failure(
                IllegalStateException(
                    "本地 stdio MCP 需要本机执行环境（Ubuntu 容器）。请到「设置 → 本机执行环境」完成安装后再连接。",
                ),
            )
        }
        // 桥脚本只用标准库，但 ubuntu-base 不带 python3 —— 先探，别让桥起在"python 不存在"上
        val probe = ProotShell.probeCommands(ctx, listOf("python3"))
        if (probe["python3"] != true) {
            return Result.failure(
                IllegalStateException(
                    "本地 stdio MCP 需要容器里的 python3（桥脚本用它做协议转换）。" +
                        "在对话里说「帮我装 python3」即可自动安装，装好后重连。",
                ),
            )
        }
        val bridge = ensureBridgeScript(ctx)
            ?: return Result.failure(IllegalStateException("桥脚本写入 extras 目录失败（磁盘满？）"))

        val port = allocPort()
            ?: return Result.failure(IllegalStateException("分配不到空闲回环端口，本次没有启动"))
        val cmd = config.stdioCommand.trim()
        val script = "exec python3 ${ProotShell.shellQuote(bridge)} --port \"\$PREVIEW_PORT\" -- $cmd\n"
        val log = mutableListOf<String>()
        val resident = ProotShell.startResident(ctx, script, port, scriptMarker = SCRIPT_MARKER) { line ->
            synchronized(log) {
                log.add(line)
                while (log.size > 30) log.removeAt(0)
            }
        } ?: run {
            return Result.failure(IllegalStateException("桥进程没能拉起（proot 环境异常），可在「设置 → 本机执行环境」跑一次自检"))
        }

        // 进程死了自动摘除（子命令启动失败 / 崩溃时不应残留假状态）
        val sid = config.id
        Thread {
            runCatching { resident.process.waitFor() }
            if (sessions.remove(sid) != null) {
                Log.i(TAG, "桥进程自行退出：$sid")
                runCatching { resident.scriptFile.delete() }
            }
        }.apply { isDaemon = true }.start()

        // 健康检查：TCP 层可连即认为 HTTP server 起来了
        val deadline = System.currentTimeMillis() + HEALTH_WAIT_MS
        var reachable = false
        while (System.currentTimeMillis() < deadline) {
            if (!resident.process.isAlive) {
                resident.process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
                runCatching { resident.scriptFile.delete() }
                val tail = synchronized(log) { log.takeLast(8).joinToString("\n") }
                return Result.failure(
                    IllegalStateException(
                        "桥启动后立即退出（子命令「${cmd.take(60)}」可能不存在或不是长期前台服务）。日志：\n$tail",
                    ),
                )
            }
            if (portReachable(port)) {
                reachable = true
                break
            }
            Thread.sleep(HEALTH_POLL_MS)
        }
        if (!reachable) {
            stop(ctx, config.id)
            return Result.failure(
                IllegalStateException("桥端口 $port 在 ${HEALTH_WAIT_MS / 1000}s 内没有就绪，已停止。可重试一次。"),
            )
        }
        sessions[config.id] = Session(port, resident.process, resident.scriptFile, log)
        Log.i(TAG, "stdio 桥已启动：${config.id} -> 127.0.0.1:$port（${cmd.take(60)}）")
        return Result.success(port)
    }

    /** 停掉某 server 的桥（SIGTERM → 宽限后 SIGKILL；proot 的 --kill-on-exit 连带清子进程树） */
    fun stop(ctx: Context, serverId: String) {
        val s = sessions.remove(serverId) ?: return
        runCatching {
            s.process.destroy()
            if (!s.process.waitFor(2_000L, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                s.process.destroyForcibly()
            }
        }.onFailure { Log.w(TAG, "停止桥 $serverId 出错：${it.message}") }
        runCatching { s.scriptFile.delete() }
    }

    /** rootfs 安装/重装/卸载前的强制清场（与 WebPreviewManager.stopAll 同一时机） */
    fun stopAll(reason: String) {
        if (sessions.isEmpty()) return
        Log.i(TAG, "stopAll：$reason（${sessions.size} 个桥）")
        sessions.keys.toList().forEach { sessions.remove(it)?.let(::stopSession) }
    }

    private fun stopSession(s: Session) {
        runCatching {
            s.process.destroy()
            if (!s.process.waitFor(2_000L, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                s.process.destroyForcibly()
            }
        }
        runCatching { s.scriptFile.delete() }
    }

    /**
     * 清扫上一轮 App 被杀遗留的野桥进程：`/proc` 里 cmdline 含 [SCRIPT_MARKER]、
     * 且**不在当前在册表**里的 proot 一律杀掉（在册的手柄自己会管理生命周期）。
     * 冷启动调用一次即可，幂等。
     */
    fun sweepOrphans() {
        runCatching {
            val tracked = sessions.keys.toSet()
            val ours = File("/proc").listFiles()
                ?.filter { it.name.matches(Regex("\\d+")) }
                ?.filter { f ->
                    runCatching {
                        val cmd = File(f, "cmdline").readBytes().toString(Charsets.UTF_8)
                        cmd.contains(SCRIPT_MARKER) && tracked.none { cmd.contains(it) }
                    }.getOrDefault(false)
                }
                ?.mapNotNull { it.name.toIntOrNull() }
                .orEmpty()
            if (ours.isEmpty()) return
            Log.w(TAG, "发现 ${ours.size} 个遗留的 MCP 桥进程，清理中：$ours")
            ours.forEach { WebPreviewManager.killTree(it) }
        }.onFailure { Log.w(TAG, "sweepOrphans 失败：${it.message}") }
    }

    /** 桥脚本落到 extras（宿主侧），内容与 assets 不一致才覆盖；返回 guest 内路径或 null */
    private fun ensureBridgeScript(ctx: Context): String? = runCatching {
        val target = File(ProotShell.extrasDir(ctx), BRIDGE_REL)
        val text = ctx.assets.open(ASSET_PATH).reader(Charsets.UTF_8).use { it.readText() }
        if (!target.isFile || target.readText(Charsets.UTF_8) != text) {
            target.parentFile?.mkdirs()
            target.writeText(text, Charsets.UTF_8)
            Log.i(TAG, "桥脚本已更新：${target.absolutePath}")
        }
        GUEST_BRIDGE
    }.getOrNull()

    /** 宿主侧拿一个空闲回环端口（分完即关，桥随后绑定它；与 WebPreviewManager 同法） */
    private fun allocPort(): Int? = runCatching {
        ServerSocket().use { s ->
            s.bind(InetSocketAddress("127.0.0.1", 0))
            s.localPort
        }
    }.getOrNull()

    private fun portReachable(port: Int): Boolean = runCatching {
        Socket().use { s ->
            s.connect(InetSocketAddress("127.0.0.1", port), 250)
            true
        }
    }.getOrDefault(false)
}
