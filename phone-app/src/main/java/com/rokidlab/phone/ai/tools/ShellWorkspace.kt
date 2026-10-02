package com.rokidlab.phone.ai.tools

/**
 * ShellWorkspace —— shell 域的**工作目录接缝**（`run_shell` / `start_web_preview` 共用）。
 *
 * ## 为什么要有这一层（2026-09-30，「运行指错目录」的治本修复）
 * 容器进程无状态、每次工具调用独立：`cwd` 省略时若一律落回 `/`，模型必须在历史轮里
 * 「回忆」上次把文件写到了哪 —— 上下文一旦压缩（CompactionPolicy）目录信息就丢了，
 * 只能猜 ⇒ 「运行刚生成的代码」指到错误目录。本接缝给出 shell 的直觉语义：
 * **省略 cwd = 沿用上一次成功落地的工作目录**。
 *
 * ## 语义（三条都要成立）
 *  1. **默认沿用**：cwd 省略时用 [lastCwd]（首次为 `/`）；
 *  2. **成功才记忆**：退出码 90（`cd` 失败，见 [com.rokidlab.phone.platform.ProotShell]）不更新，
 *     失败的目录不污染下一次默认值；
 *  3. **每轮回显**：结果里始终带当前工作目录（[ShellToolProvider.cwdEcho]），
 *     即使早期结果被压缩，最近一条结果仍携带真实路径 —— 这是压缩后唯一的可靠恢复源。
 *
 * ## 为什么是进程级单例而不是按会话隔离
 * 同一时刻只有一个活跃对话在跑命令；跨会话沿用「上一个项目目录」符合
 * 「接着上次继续」的用户预期。App 被杀后重置回 `/` 也无害 ——
 * 「运行前先 ls 确认路径」的纪律已写进两个工具的 schema，首轮显式传 cwd 即可。
 */
internal object ShellWorkspace {

    @Volatile
    var lastCwd: String? = null
        private set

    /** cwd 省略时的默认值：有记忆用记忆，否则容器根 */
    val defaultCwd: String
        get() = lastCwd ?: "/"

    /** 记住一次成功落地的 cwd（调用方负责过滤 `cd` 失败的情形） */
    fun remember(cwd: String) {
        val c = cwd.trim()
        if (c.isNotEmpty()) lastCwd = c
    }
}
