package com.rokidlab.phone.platform

import android.content.Context
import android.util.Log
import com.rokidlab.phone.network.ApkDownloader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Node.js 运行时（含 npm/npx）的**下载 → 校验 → 解压 → 就位**流程。
 *
 * ## 为什么走下载而不是 `apt install nodejs`
 * 三条硬理由，不是口味问题：
 *  1. **版本**：Ubuntu 24.04 源里是 Node 18（已 EOL），而官方自包含包能直接给当前 LTS；
 *  2. **npm 在 universe 源**：走 apt 还得让用户先开 universe（额外一轮 `软件源 → 更新` 的等待）；
 *  3. **位置**：apt 装进 rootfs，**重装环境就一起没了**；这里装在 rootfs **之外**的
 *     `files/proot/extras/`，重装 rootfs 不丢（见 [ProotShell.extrasDir]）。
 *
 * ## ⚠️ 用 `.tar.gz` 而不是体积只有一半的 `.tar.xz`
 * 设备自带的 toybox `tar` **不支持 `J`**（里面写着支持，实则去调不存在的 `xz`，见
 * [ProotInstaller] 的同名注释）。用 `.tar.xz` 就得先进 guest 用 GNU tar 解 —— 多一条
 * "rootfs 必须先能跑"的前置依赖，而 rootfs 本身也可能正被重装。
 * 代价是下载量 58 MB（xz 约 27 MB）；换来的是与 rootfs 解压**完全同一条已被真机验证的路径**，
 * 零新增失败面。这是一次性下载，用户明确点了才发生。
 *
 * ## 为什么解压到 `.staging` 再 `rename` 就位
 * 直接往 `extras/node/` 解压时，中途失败会留下一个**半截的 node** —— 而 `bin/node` 可能
 * 早已落盘，于是"看起来装好了、一跑就缺库"。先解到同级 staging 目录、验过 `bin/node` 再整体
 * 改名，就位动作是**原子的**：要么是完整的 node，要么什么都没有。
 */
object NodeAddon {

    private const val TAG = "NodeAddon"

    /** 版本与校验和。**改版本时必须同时更新 sha256**，否则下载必然校验失败。 */
    const val VERSION = "24.21.0"

    /**
     * v24.21.0 linux-arm64 `.tar.gz` 的官方 sha256（57,824,078 B）。
     * 取自 `https://nodejs.org/dist/v24.21.0/SHASUMS256.txt`。
     */
    private const val SHA256 = "724282c3b43aec998aa9527380465b45d229e021b58035f5f4f63095eabfe5d5"

    /** 官方包内的顶层目录名（解压后要把这一层摘掉） */
    private const val TOP_DIR = "node-v$VERSION-linux-arm64"
    private const val ARCHIVE = "$TOP_DIR.tar.gz"

    /**
     * 镜像顺序（2026-09-23 实测 HEAD 全为 200，`Content-Length` 均为 57,824,078
     * —— 与官方**同字节**，所以同一个 sha256 对所有镜像都成立）。
     *
     * 国内优先：默认源 `nodejs.org` 在国内常被限速。
     * ⚠️ 已剔除两个不可用地址：清华（该目录 404）、`registry.npmmirror.com`（302 跳到 cdn，
     * 直接写最终的 cdn 域名，少一跳）。
     */
    private val MIRRORS = listOf(
        "https://cdn.npmmirror.com/binaries/node/v$VERSION/$ARCHIVE",
        "https://mirrors.huaweicloud.com/nodejs/v$VERSION/$ARCHIVE",
        "https://nodejs.org/dist/v$VERSION/$ARCHIVE",
    )

    /** 安装所需可用空间：归档 58 MB（cacheDir）+ 解压本体约 130 MB ⇒ 400 MB 有充裕余量 */
    private const val REQUIRED_FREE_BYTES = 400L * 1024 * 1024

    /** 解压超时：rootfs（350 MB）实测 1.5 秒，node 只有其三分之一，给 5 分钟是给慢存储留量 */
    private const val EXTRACT_TIMEOUT_SEC = 300L

    /** 等 rootfs 独占锁的上限（超时即如实拒绝，不硬等） */
    private const val LOCK_WAIT_SEC = 5L

    private const val MARKER_NAME = "node.version"

    /**
     * npm 全局配置里写死的 registry。
     *
     * 与 apt 换国内镜像同一个理由：默认 `registry.npmjs.org` 在国内基本不可用，
     * 不换的话"装好 npm"等于装了个超时器。全局配置对 node 前缀就是 `<prefix>/etc/npmrc`。
     */
    private const val NPM_REGISTRY = "https://registry.npmmirror.com/"

    // ═══════════════════ 状态查询 ═══════════════════

    /**
     * 是否已安装就绪。两个条件缺一不可：
     *  1. `bin/node` 在位（执行能力的前提，见 [ProotShell.nodeReady]）；
     *  2. 版本标记在位且 == [VERSION] —— 只认 `bin/node` 的话，解压到一半被杀会被判成已安装，
     *     用户就**永远修不好**（重装按钮只在未就绪分支里）。版本变时必须走一次重装。
     */
    fun isInstalled(ctx: Context): Boolean =
        ProotShell.nodeReady(ctx) && installedVersion(ctx) == VERSION

    /** 已安装的版本号（读版本标记）；未安装或标记缺失返回 null */
    fun installedVersion(ctx: Context): String? =
        markerFile(ctx).takeIf { it.exists() }?.let {
            runCatching { it.readLines().firstOrNull { l -> l.startsWith("version=") }?.substringAfter('=') }.getOrNull()
        }

    /** 占用空间（附加组件目录，字节） */
    fun diskUsageBytes(ctx: Context): Long = extrasSize(ProotShell.extrasDir(ctx))

    // ═══════════════════ 安装 ═══════════════════

    /**
     * 安装 Node.js。**幂等**：已就绪时直接返回成功。
     *
     * 全程在 IO 线程，[onProgress] 会从 IO 线程回调。阶段复用 [ProotInstaller.Stage]
     * —— 它与 rootfs 安装是**同一套安装语汇**（检查 / 下载 / 解压 / 配置 / 完成），
     * UI 侧因此能共用同一份阶段文案映射，不必为第二个安装流程再写一遍。
     *
     * 失败一律以 [Capability.Unavailable] 返回原因（不抛异常）：这些原因都是要展示给用户的。
     *
     * ⚠️ 与 [ProotInstaller.install] 同样的锁边界：下载是 suspend 的，**绝不能在锁内**
     * （写锁必须由持锁线程释放，协程挂起点之后可能换线程 ⇒ 锁永久泄漏）。
     * 只有"删旧目录 → 改名就位"这段阻塞调用取独占锁，与正在 guest 里跑的命令互斥。
     */
    suspend fun install(
        ctx: Context,
        onProgress: (ProotInstaller.Stage, Int) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false },
    ): Capability<File> = withContext(Dispatchers.IO) {
        onProgress(ProotInstaller.Stage.CHECK, -1)

        if (isInstalled(ctx)) {
            onProgress(ProotInstaller.Stage.DONE, 100)
            return@withContext Capability.Available(ProotShell.nodeDir(ctx))
        }
        // 附加组件挂在 rootfs 之外，但**运行**要经 guest —— 没环境时装了也跑不起来
        if (!ProotInstaller.isInstalled(ctx)) {
            return@withContext Capability.Unavailable("请先安装 Linux 环境")
        }

        val free = ProotInstaller.freeBytes(ctx.filesDir)
        if (free in 0 until REQUIRED_FREE_BYTES) {
            return@withContext Capability.Unavailable(
                "存储空间不足：安装约需 ${REQUIRED_FREE_BYTES / 1024 / 1024} MB，当前可用 ${free / 1024 / 1024} MB",
            )
        }
        if (free < 0) Log.w(TAG, "读不到可用空间，跳过预检继续安装")

        // 下载（多镜像依次试；下载器内部已做 sha256 校验 + 单镜像重试 2 次）
        onProgress(ProotInstaller.Stage.DOWNLOAD, 0)
        val downloader = ApkDownloader(ctx)
        var archive: File? = null
        var lastError = "未知错误"
        for ((index, url) in MIRRORS.withIndex()) {
            if (isCancelled()) return@withContext Capability.Unavailable("已取消")
            try {
                Log.i(TAG, "下载 Node.js（${index + 1}/${MIRRORS.size}）：$url")
                archive = downloader.download(
                    url = url,
                    label = "node-$VERSION.tar.gz",
                    expectedSha256 = SHA256,
                    onProgress = { onProgress(ProotInstaller.Stage.DOWNLOAD, it) },
                    isCancelled = isCancelled,
                )
                break
            } catch (e: CancellationException) {
                return@withContext Capability.Unavailable("已取消")
            } catch (e: Exception) {
                lastError = "${e.javaClass.simpleName}: ${e.message}"
                Log.w(TAG, "镜像失败（${index + 1}/${MIRRORS.size}）：$lastError")
            }
        }
        val zip = archive
            ?: return@withContext Capability.Unavailable("下载失败（${MIRRORS.size} 个镜像都不可用）：$lastError")

        // 破坏性区间（删旧 node 目录）⇒ 必须独占；本段全是阻塞调用，同一线程收尾。
        if (!ProotShell.tryLockRootfsExclusive(LOCK_WAIT_SEC)) {
            runCatching { zip.delete() }
            return@withContext Capability.Unavailable(
                "本机执行环境正忙（有命令正在跑），本次安装没有开始，临时归档已清理，请稍后重试",
            )
        }
        try {
            installFromArchive(ctx, zip, onProgress)
        } catch (e: Exception) {
            runCatching { zip.delete() }
            Log.e(TAG, "安装中断：${e.javaClass.simpleName}: ${e.message}", e)
            Capability.Unavailable("安装中断：${e.javaClass.simpleName}: ${e.message}")
        } finally {
            ProotShell.unlockRootfsExclusive()
        }
    }

    /**
     * 解压 → 就位 → 写 npm 全局配置与版本标记。
     *
     * ⚠️ 调用方必须已持有 rootfs **独占锁**，且本函数内**不得出现挂起点**（锁要靠同一线程释放）。
     */
    private fun installFromArchive(
        ctx: Context,
        archive: File,
        onProgress: (ProotInstaller.Stage, Int) -> Unit,
    ): Capability<File> {
        onProgress(ProotInstaller.Stage.EXTRACT, -1)

        val extras = ProotShell.extrasDir(ctx)
        val staging = File(extras, ".staging")
        deleteTree(staging)
        if (!staging.mkdirs()) {
            runCatching { archive.delete() }
            return Capability.Unavailable("无法创建目录：${staging.absolutePath}")
        }

        val extract = ProcessRun.runTool(
            "tar",
            listOf("xzf", archive.absolutePath, "-C", staging.absolutePath),
            EXTRACT_TIMEOUT_SEC,
        )
        // ⚠️ 先看**产物**再看退出码：tar 遇到个别条目可能返回非 0 却把绝大多数文件解好，
        // 只看退出码会把"可用"误判成"失败"（rootfs 那边的硬链接告警就是同一类情况）。
        val extracted = File(staging, TOP_DIR)
        if (!File(extracted, "bin/node").exists()) {
            deleteTree(staging)
            runCatching { archive.delete() }
            return Capability.Unavailable("解压失败（${extract.summary()}）：${extract.stderrTail()}")
        }
        if (!extract.ok) Log.w(TAG, "解压有告警（不影响可用性）：${extract.stderrTail(5)}")

        onProgress(ProotInstaller.Stage.CONFIGURE, -1)
        val target = ProotShell.nodeDir(ctx)
        deleteTree(target)
        if (!extracted.renameTo(target)) {
            deleteTree(staging)
            runCatching { archive.delete() }
            return Capability.Unavailable("无法就位：${target.absolutePath}")
        }
        deleteTree(staging)

        writeNpmrc(target)
        writeMarker(ctx)
        runCatching { archive.delete() }
        onProgress(ProotInstaller.Stage.DONE, 100)
        Log.i(TAG, "Node.js 就绪：${target.absolutePath}（占用 ${diskUsageBytes(ctx) / 1024 / 1024} MB）")
        return Capability.Available(target)
    }

    // ═══════════════════ 内部 ═══════════════════

    private fun markerFile(ctx: Context): File = File(ProotShell.extrasDir(ctx), MARKER_NAME)

    private fun writeMarker(ctx: Context) {
        runCatching {
            ProotShell.extrasDir(ctx).mkdirs()
            markerFile(ctx).writeText(
                buildString {
                    appendLine("version=$VERSION")
                    appendLine("sha256=$SHA256")
                    appendLine("installed=${System.currentTimeMillis()}")
                },
            )
        }.onFailure { Log.w(TAG, "写版本标记失败：${it.message}") }
    }

    /**
     * 写 npm 全局配置（`<prefix>/etc/npmrc`）。
     *
     * 失败**不算致命**（用户仍可 `npm config set registry …` 自己修），只记日志 ——
     * 与 [ProotShell.ensureGuestConfig] 同样的降级取向。
     */
    private fun writeNpmrc(nodeDir: File) {
        runCatching {
            val etc = File(nodeDir, "etc")
            etc.mkdirs()
            File(etc, "npmrc").writeText("registry=$NPM_REGISTRY\n")
        }.onFailure { Log.w(TAG, "写 npmrc 失败：${it.message}") }
    }

    private fun deleteTree(dir: File) {
        if (!dir.exists()) return
        ProcessRun.runTool("rm", listOf("-rf", dir.absolutePath), 120L)
    }

    /**
     * 目录占用统计。**不跟随符号链接**（node 的 `bin/npm` 等是链接，跟随会算重），
     * 链接自身按 0 计。
     */
    private fun extrasSize(root: File): Long {
        if (!root.exists()) return 0L
        var total = 0L
        val stack = ArrayDeque<File>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            val dir = stack.removeLast()
            val children = dir.listFiles() ?: continue
            for (child in children) {
                if (isSymlink(child)) continue
                if (child.isDirectory) stack.addLast(child) else total += child.length()
            }
        }
        return total
    }

    private fun isSymlink(f: File): Boolean =
        runCatching { java.nio.file.Files.isSymbolicLink(f.toPath()) }.getOrDefault(false)
}
