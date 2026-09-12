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

    /** Termux 前缀路径（RUN_COMMAND 的 bash/home 都在其下） */
    private const val TERMUX_PREFIX = "/data/data/com.termux/files/usr"

    /**
     * 在 Termux 后台执行一段 shell（通过 RUN_COMMAND 广播/服务调用）。
     *
     * 背景：本机大模型（Ollama）由 Termux 宿主，Lab 借 RUN_COMMAND 驱动其执行命令。
     * 执行结果不会回传（RUN_COMMAND 为单向），成败由调用方轮询 HTTP 判断。
     *
     * 新版 Termux 由 RunCommandService 处理该 Action（旧版才是 Activity）：
     * 优先 `startForegroundService`，解析不到服务时退回 Activity 方式。
     *
     * @throws java.io.IOException Termux 不可解析（未安装 / 未开「允许外部应用」）
     * @throws SecurityException 本 App 未被授予 RUN_COMMAND 权限
     */
    fun runTermuxCommand(ctx: android.content.Context, command: String) {
        val intent = android.content.Intent("com.termux.RUN_COMMAND").apply {
            setPackage("com.termux")
            putExtra("com.termux.RUN_COMMAND_PATH", "$TERMUX_PREFIX/bin/bash")
            putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", command))
            putExtra("com.termux.RUN_COMMAND_WORKDIR", "$TERMUX_PREFIX/home")
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
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
