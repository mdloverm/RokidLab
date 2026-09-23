package com.rokidlab.phone.platform

import android.util.Base64
import com.rokidlab.phone.adb.AdbShellClient

/**
 * Shell 运维操作：把散落在业务代码里的 `appops` / `run-as` / `input` / 分块落盘等
 * 隐藏通道收口到 L0 `platform/`（全仓允许反射/私有权限动作的另一处边界，见架构文档 §3.1）。
 *
 * 全部以 [Capability] 返回，调用方据此显式降级，而不是在 `catch (e: Exception) {}` 里静默吞掉。
 */
object ShellOps {

    /** Termux 前缀路径（RUN_COMMAND 的 bash 及 base64/pkg/python 等都在其下） */
    private const val TERMUX_PREFIX = "/data/data/com.termux/files/usr"

    /**
     * Termux home。
     *
     * ⚠️ 是 `files/home`，**不是** `files/usr/home` —— 后者是 `$PREFIX` 的子目录，
     * 与 home 无关。写错的表现极隐蔽：进程能起来（exec 审计照常放行），但 cwd 不落地，
     * 脚本里一切相对路径都偏。
     */
    private const val TERMUX_HOME = "/data/data/com.termux/files/home"

    /**
     * 每条 RUN_COMMAND 前都要带的环境自救前缀。
     *
     * 为什么不能省（2026-09-22 真机定位「命令发出去了、脚本 49ms 就无声退出、哪儿都没日志」）：
     *  1. **RUN_COMMAND 起的进程不加载 `~/.bashrc`**，`$HOME`/`$PATH` 都不能指望。
     *     最阴的不是报错：`$HOME` 为空或是别的值时会**静默走偏**。
     *  2. **`exec >> 文件` 重定向失败会让 bash 直接退出** —— `exec` 是特殊内建命令，
     *     重定向错误即致命。脚本开头正是用 `exec >> "$SETUP_LOG"` 收拢日志的，
     *     于是 "$HOME 不对" 直接表现为"脚本一字不吐地消失"。所以要**先把 HOME 修正**再交给脚本。
     *  3. **`base64 -d > 文件` 在命令找不到时已经把文件建成 0 字节**：PATH 缺 `$PREFIX/bin`
     *     时 `base64` 是 command not found，但 `>` 的重定向早已执行 ⇒ 后续 `bash <空文件>`
     *     瞬间退出。所以 PATH 必须先补齐，且落盘命令一律走绝对路径。
     *  4. **路径以硬编码为准、环境变量只作兜底**（顺序不能反）：`$HOME` "存在但是错的值"时，
     *     `${HOME:-默认}` 这种写法救不了，必须先 `[ -d ... ]` 验目录。
     */
    private val ENV_PROLOGUE: String = buildString {
        append("P=$TERMUX_PREFIX; ")
        append("H=$TERMUX_HOME; ")
        append("[ -d \"\$H\" ] || H=\"\${HOME:-$TERMUX_HOME}\"; ")
        append("export HOME=\"\$H\"; ")
        append("export PATH=\"\$P/bin:\$P/bin/applets:/system/bin:/system/xbin:/vendor/bin:\$PATH\"; ")
    }

    /**
     * 在 Termux 后台执行一段 shell（通过 RUN_COMMAND 服务调用）。
     *
     * 背景：本机大模型（Ollama）由 Termux 宿主，Lab 借 RUN_COMMAND 驱动它。
     * `RUN_COMMAND` 是**单向**的：执行结果不回传，成败由调用方自行轮询判断
     * （Ollama 的 stdout/stderr 由它自己重定向到 `$HOME/.rokid-ollama.log`）。
     *
     * ⚠️ **刻意不设 `RUN_COMMAND_WORKDIR`**（2026-09-22 实测后去掉）。
     * 不传时 Termux 自己会给默认值 `TERMUX_HOME_DIR_PATH`（官方 wiki 明写），
     * 而显式传一个路径会多走一道 `validateDirectoryFileExistenceAndPermissions` ——
     * 排查中"能到 bash"和"整条卡死"两次唯一的输入差异就是它，去掉即消除这个变量。
     * 本函数带的 [ENV_PROLOGUE] 已把 `HOME` 和全部工具路径钉成绝对值，**cwd 无关紧要**。
     *
     * [command] 会自动带上 [ENV_PROLOGUE]（修正 HOME/PATH），调用方直接写业务即可，
     * 并可直接使用它定义的 `$P`（prefix）与 `$H`（home）两个变量。
     *
     * ⚠️ **一次 [runTermuxCommand] 只发一条命令**，且多条之间会**并发**执行（Termux 为每条
     * 起一个后台任务）。有多步依赖关系时必须自己拼成**一条**命令，不能用多条代替。
     *
     * @throws java.io.IOException Termux 不可解析（未安装 / 未开「允许外部应用」）
     * @throws SecurityException 本 App 未被授予 RUN_COMMAND 权限
     */
    fun runTermuxCommand(ctx: android.content.Context, command: String) {
        val intent = android.content.Intent("com.termux.RUN_COMMAND").apply {
            setPackage("com.termux")
            putExtra("com.termux.RUN_COMMAND_PATH", "$TERMUX_PREFIX/bin/bash")
            putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", ENV_PROLOGUE + command))
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
            putExtra(
                "com.termux.RUN_COMMAND_COMMAND_HELP",
                "https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent",
            )
        }
        if (ctx.packageManager.resolveService(intent, 0) != null) {
            ctx.startForegroundService(intent)
        } else {
            // 兼容旧版 Termux（Activity 方式）
            val legacy = android.content.Intent(intent).apply {
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (ctx.packageManager.resolveActivity(legacy, 0) == null) {
                throw java.io.IOException("Termux RUN_COMMAND not resolvable")
            }
            ctx.startActivity(legacy)
        }
    }

    /**
     * 通过 ADB shell 给目标包授予 `SYSTEM_ALERT_WINDOW`（Android 12+ 后台启动限制 BAL 豁免）。
     * 失败（指令未知 / 通道不可用）时返回 [Capability.Unavailable]，由调用方决定兜底策略。
     */
    fun grantSystemAlertWindow(client: AdbShellClient, packageName: String): Capability<Unit> =
        runCatching {
            val r = client.executeShellCommand(
                "appops set $packageName android:system_alert_window allow 2>&1",
                10_000,
            )
            if (r.isNotBlank() && r.contains("Unknown", ignoreCase = true)) {
                Capability.Unavailable("appops 返回未知指令: $r")
            } else {
                Capability.Available(Unit)
            }
        }.getOrElse { e -> Capability.Unavailable("grantSystemAlertWindow 失败: ${e.message}") }

    /**
     * 查询目标包的 `SYSTEM_ALERT_WINDOW` appops 状态。
     *
     * 授权后**必须复核**：`appops set` 在部分 ROM 上返回码/输出正常但实际未生效
     * （实测眼镜端出现「读完是 allow、复核又变 default」的情况），只调用不核验会误判成功。
     *
     * @return true=allow；false=非 allow（default/deny/ignore）；Unavailable=输出不可解析或通道失败
     */
    fun isSystemAlertWindowAllowed(client: AdbShellClient, packageName: String): Capability<Boolean> =
        runCatching {
            // 注意：`appops get <pkg> <op>`（带 op 参数）在部分 ROM（实测 Rokid 眼镜）会偶发返回**空**，
            // 若据此判"未授权"会误判并反复重启流程。改为取该包全部 op 再筛目标行，稳定得多。
            val out = client.executeShellCommand("appops get $packageName 2>&1", 8_000)
                ?: return@runCatching Capability.Unavailable("appops get 无输出")
            val line = out.lineSequence().firstOrNull {
                it.contains("SYSTEM_ALERT_WINDOW", ignoreCase = true)
            } ?: return@runCatching Capability.Unavailable("appops 输出无 SYSTEM_ALERT_WINDOW 行: ${out.take(120)}")
            when {
                line.contains("allow", ignoreCase = true) -> Capability.Available(true)
                else -> Capability.Available(false)   // default / deny / ignore 均视为未授权
            }
        }.getOrElse { Capability.Unavailable("isSystemAlertWindowAllowed 失败: ${it.message}") }

    /**
     * 经 `run-as <pkg> sh -c` + base64 分块落盘，把 [body] 写到眼镜端 app 私有目录 [dir]/[fileName]。
     *
     * 要点（来自 AiuiFrontendController 原实现，迁移至此统一管理）：
     * - 整个 shell 逻辑必须包进 `run-as pkg sh -c '...'`，否则 `rm`/重定向由 adbd 的 shell 用户执行，
     *   无权操作 app 私有目录（实测 rm 静默失败导致文件残留叠加）。
     * - 命令本身无 stdout（`echo|base64 -d` 静默写盘），不以输出判成败，统一靠最后 `wc -c` 校验字节数兜底。
     * - 块 60K base64 ≈ 45KB，覆盖 AIUI 包常见大小。
     *
     * 仅当最终落盘字节数 == [body].size 时返回 [Capability.Available]，否则 [Capability.Unavailable]。
     */
    fun pushFileRunAs(
        client: AdbShellClient,
        packageName: String,
        dir: String,
        fileName: String,
        body: ByteArray,
    ): Capability<Unit> = runCatching {
        val relPath = "$dir/$fileName"
        client.executeShellCommand(
            "run-as $packageName sh -c 'mkdir -p $dir && rm -f $relPath'",
            10_000,
        )
        val b64 = Base64.encodeToString(body, Base64.NO_WRAP)
        var off = 0
        val step = 60000
        while (off < b64.length) {
            val end = if (off + step < b64.length) off + step else b64.length
            val chunk = b64.substring(off, end)
            off = end
            client.executeShellCommand(
                "run-as $packageName sh -c 'echo $chunk | base64 -d >> $relPath'",
                30_000,
            )
        }
        val wc = client.executeShellCommand(
            "run-as $packageName sh -c 'wc -c < $relPath'",
            10_000,
        )
        val written = wc?.trim()?.toLongOrNull()
        if (written == body.size.toLong()) {
            Capability.Available(Unit)
        } else {
            Capability.Unavailable("run-as 落盘字节数不符：写入 $written 期望 ${body.size}")
        }
    }.getOrElse { e -> Capability.Unavailable("pushFileRunAs 失败: ${e.message}") }
}
