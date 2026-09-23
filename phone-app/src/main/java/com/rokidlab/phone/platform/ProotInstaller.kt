package com.rokidlab.phone.platform

import android.content.Context
import android.os.Build
import android.os.StatFs
import android.util.Log
import com.rokidlab.phone.network.ApkDownloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * rootfs 的**下载 → 校验 → 解压 → 裁剪 → 就绪**全流程。
 *
 * ## 为什么 rootfs 走运行期下载而不是随包
 * 只带 `proot` + `loader`（214 KB）时：APK 几乎不长大、且**只分发我们自己的东西**；
 * rootfs 是别人的发行版打包（Ubuntu 另有 Canonical 条款），不经我们分发，法务面最干净。
 * 代价是用户首次要下 28.5 MB —— 属可选能力，未安装时其它功能不受影响。
 *
 * ## 为什么拿**设备自带的 tar** 解压，而不是 Kotlin 流
 * 实测 `tar xzf` 解 350 MB **1.5 秒**（F2FS）；用 `GZIPInputStream` 走 JVM 是几十倍的时间，
 * 且要自己处理 tar 头、硬链接、权限位。少写一套解析器 = 少一类 bug。
 * 🚨 格式硬约束：**必须 `.tar.gz`**。设备上没有 `xz`，`tar xJf` 必然
 * `exec xz: No such file or directory`（toybox `tar --help` 里写着支持 `J`，但那是去调外部可执行文件
 * —— **help 里写了 ≠ 能用**）。
 *
 * ## 幂等与可重试
 * 解压前会**先清空** `rootfs/`（半截状态比重来更危险）；下载器自带 sha256 校验与重试，
 * 校验不过的文件会被丢弃、换下一个镜像。任何一个镜像下完都能通过校验（各镜像字节一致）。
 */
object ProotInstaller {

    private const val TAG = "ProotInstaller"

    /** rootfs 版本与校验和。**改版本时必须同时更新 sha256**，否则下载必然校验失败。 */
    const val ROOTFS_VERSION = "24.04.5"

    /**
     * 24.04.5 arm64 的官方 sha256（29,936,675 B）。
     * 与 `https://cdimage.ubuntu.com/ubuntu-base/releases/24.04.5/release/SHA256SUMS` 对齐。
     */
    const val ROOTFS_SHA256 = "a91d5a93010193712d346d761372b7c9db6dfcf093893161c64ca107f05914f2"

    private const val ROOTFS_ARCHIVE = "ubuntu-base-$ROOTFS_VERSION-base-arm64.tar.gz"
    private const val RELEASE_REL = "$ROOTFS_VERSION/release/$ROOTFS_ARCHIVE"

    /**
     * 镜像顺序（2026-09-22 实测 HEAD 全为 200，且 `Content-Length` 均为 29,936,675 —— 与官方**同字节**，
     * 所以同一个 sha256 对所有镜像都成立）。
     *
     * 国内优先：CDN 之外最实际的差别是**带宽**而非可用性。
     * ⚠️ 已剔除两个不稳定的公共镜像：中科大（HEAD 403）、南大（302 自跳循环）。
     */
    private val MIRRORS = listOf(
        "https://mirrors.tuna.tsinghua.edu.cn/ubuntu-cdimage/ubuntu-base/releases/$RELEASE_REL",
        "https://mirrors.aliyun.com/ubuntu-cdimage/ubuntu-base/releases/$RELEASE_REL",
        "https://mirrors.huaweicloud.com/ubuntu-cdimage/ubuntu-base/releases/$RELEASE_REL",
        "https://cdimage.ubuntu.com/ubuntu-base/releases/$RELEASE_REL",
    )

    /** 就绪标记（放在 `files/proot/`，与 rootfs 平级，便于与 rootfs 一起删） */
    private const val MARKER_NAME = "rootfs.version"

    /**
     * 安装所需可用空间。
     *
     * 峰值 = 压缩包 30 MB（cacheDir）+ 解压本体（一百多 MB）⇒ 300 MB 有充裕余量。
     * ⚠️ 这是**安装**的门槛；之后用户若要用 apt 装包，还会再涨约 200 MB（apt 索引），
     * 那一步由 guest 内的 apt 自己报空间不足，不在这里拦。
     */
    private const val REQUIRED_FREE_BYTES = 300L * 1024 * 1024

    /** 解压超时：实测 1.5 秒，给 5 分钟是给"低端机 + 慢存储"留量 */
    private const val EXTRACT_TIMEOUT_SEC = 300L

    /**
     * 等 rootfs 独占锁（安装 / 卸载）的上限。
     *
     * 超时即**如实拒绝**而不是硬等：等下去只会让用户完全看不到原因地多等一截，
     * 不如直接说"有条命令正在跑，请稍后重试"。
     */
    private const val ROOTFS_LOCK_WAIT_SEC = 5L

    /** 解压后**可删**的目录（apt 索引会在下次 `apt-get update` 重建；doc/man/locale 只是省地方） */
    private val TRIMMABLE = listOf(
        "var/lib/apt/lists",
        "var/cache/apt",
        "var/log",
        "usr/share/doc",
        "usr/share/man",
        "usr/share/locale",
    )

    /** 安装阶段（UI 据此显示"正在做什么"，percent = -1 表示该阶段无法给出百分比） */
    enum class Stage { CHECK, DOWNLOAD, EXTRACT, CONFIGURE, DONE }

    // ═══════════════════ 状态查询 ═══════════════════

    /**
     * 是否已安装就绪。**这是"装好了没有"的唯一产地** —— 设置页、`run_shell`/`run_script`、
     * [install] 的幂等分支全部用它，不再各自判一遍。
     *
     * 三个条件缺一不可：
     *  1. guest 的 `bin/bash` 在位（执行能力的前提，见 [ProotShell.rootfsReady]）；
     *  2. **版本标记在位** —— [writeMarker] 是整个流程的**唯一收尾动作**。
     *     只看 `bin/bash` 会把「tar 解压到一半被杀」判成已安装（Ubuntu base 是 merged-/usr
     *     布局，中断时 `usr/bin/bash` 早已落盘）：那时设置页会进"就绪"分支（重装按钮只在
     *     `!installed` 分支里），用户**永远修不好**；
     *  3. 标记里的版本 == [ROOTFS_VERSION] —— 版本升级后必须走一次重装（sha256 与裁剪
     *     策略都变了），否则会继续用旧 rootfs 并按新版本号自报。
     *
     * 不满足时用户看到的是"未安装 + 重装按钮"，且 [install] 会先清空半截目录再重来 ——
     * 也就是说"能自愈"，而不是"卡死"。
     */
    fun isInstalled(ctx: Context): Boolean =
        ProotShell.rootfsReady(ctx) && installedVersion(ctx) == ROOTFS_VERSION

    /** 已安装的版本号（读版本标记）；未安装或标记缺失返回 null */
    fun installedVersion(ctx: Context): String? =
        markerFile(ctx).takeIf { it.exists() }?.let {
            runCatching { it.readLines().firstOrNull { l -> l.startsWith("version=") }?.substringAfter('=') }.getOrNull()
        }

    /** 占用空间（rootfs + 临时目录，字节）。用于设置页显示"这东西占了多大"。 */
    fun diskUsageBytes(ctx: Context): Long = dirSize(ProotShell.workDir(ctx))

    /** 设备剩余可用空间（用于与 [REQUIRED_FREE_BYTES] 对比） */
    fun freeBytes(dir: File): Long =
        runCatching { StatFs(dir.absolutePath).availableBytes }.getOrDefault(-1L)

    // ═══════════════════ 安装 / 卸载 ═══════════════════

    /**
     * 安装 rootfs。**幂等**：已就绪且 [force] = false 时直接返回成功。
     *
     * 全程在 IO 线程，[onProgress] 会从 IO 线程回调 —— `percent` 为 -1 表示阶段内无百分比
     * （解压靠设备 tar，拿不到进度）。
     *
     * 失败一律以 [Capability.Unavailable] 返回原因（不抛异常）：这些原因都是**要展示给用户的**
     * （空间不足 / 网络不通 / 校验失败 / 解压失败），抛异常只会让上游多写一层 catch。
     *
     * ## 锁的边界：为什么独占锁只罩住「后半段」
     * `ReentrantReadWriteLock` 的写锁**必须由持锁的那个线程释放**，而协程在挂起点之后
     * **可能被恢复到另一个线程**。所以「下载」（[ApkDownloader.download] 是 suspend）
     * **绝不能**在锁内 —— 否则 `unlock` 会抛 `IllegalMonitorStateException`、被 `runCatching`
     * 静默吞掉 ⇒ 写锁**永久泄漏**，此后每一条 `run_shell` 都被判"正在安装中"，要重启 App 才恢复。
     *
     * 因此顺序是：ABI → 幂等 → 空间 → **下载（不持锁）** → 抢独占锁 → 解压 / 裁剪 / 写标记。
     * 真正必须独占的只有后半段（`rm -rf rootfs/` → `tar` → 写标记），而下载是最长的一段
     * （30 MB），不占锁反而不会把用户的执行请求白白堵住。
     *
     * ⚠️ 抢锁失败时**把已下好的归档删掉**：否则白占 30 MB，且下次还得重下。
     * ⚠️ 锁内**不得出现挂起点**（见上），且要与正在执行的命令（或另一次安装）互斥 ——
     * 否则 AI 读到的文件会凭空消失、两条 tar 往同一目录解压，失败还是随机复现的
     * （见 [ProotShell.tryLockRootfsExclusive]）。
     */
    suspend fun install(
        ctx: Context,
        force: Boolean = false,
        onProgress: (Stage, Int) -> Unit = { _, _ -> },
        isCancelled: () -> Boolean = { false },
    ): Capability<File> = withContext(Dispatchers.IO) {
        onProgress(Stage.CHECK, -1)

        // ① ABI：proot/rootfs 都是 arm64；别的 ABI 现在直接说清，别让用户白下 30 MB
        val primaryAbi = Build.SUPPORTED_ABIS.firstOrNull() ?: "?"
        if (primaryAbi != "arm64-v8a") {
            return@withContext Capability.Unavailable("本机执行环境暂只支持 arm64-v8a（当前 $primaryAbi）")
        }

        val rootfs = ProotShell.rootfsDir(ctx)
        if (isInstalled(ctx) && !force) {
            onProgress(Stage.DONE, 100)
            return@withContext Capability.Available(rootfs)
        }

        // ② 空间
        val free = freeBytes(ctx.filesDir)
        if (free in 0 until REQUIRED_FREE_BYTES) {
            return@withContext Capability.Unavailable(
                "存储空间不足：安装约需 ${REQUIRED_FREE_BYTES / 1024 / 1024} MB，当前可用 ${free / 1024 / 1024} MB",
            )
        }
        if (free < 0) Log.w(TAG, "读不到可用空间，跳过预检继续安装")

        // ③ 下载（多镜像依次试；下载器内部已经做了 sha256 校验 + 单镜像重试 2 次）
        //    ⚠️ 这一段是 suspend 的，**不能在锁内**（见 KDoc）。
        onProgress(Stage.DOWNLOAD, 0)
        val downloader = ApkDownloader(ctx)
        var archive: File? = null
        var lastError = "未知错误"
        for ((index, url) in MIRRORS.withIndex()) {
            if (isCancelled()) return@withContext Capability.Unavailable("已取消")
            try {
                Log.i(TAG, "下载 rootfs（${index + 1}/${MIRRORS.size}）：$url")
                archive = downloader.download(
                    url = url,
                    label = ROOTFS_ARCHIVE,
                    expectedSha256 = ROOTFS_SHA256,
                    onProgress = { onProgress(Stage.DOWNLOAD, it) },
                    isCancelled = isCancelled,
                )
                break
            } catch (e: kotlinx.coroutines.CancellationException) {
                return@withContext Capability.Unavailable("已取消")
            } catch (e: Exception) {
                lastError = "${e.javaClass.simpleName}: ${e.message}"
                Log.w(TAG, "镜像失败（${index + 1}/${MIRRORS.size}）：$lastError")
            }
        }
        val zip = archive
            ?: return@withContext Capability.Unavailable("下载失败（${MIRRORS.size} 个镜像都不可用）：$lastError")

        // ④ 破坏性区间（先 rm -rf 再解压）⇒ 必须独占；本段全是阻塞调用，同一线程收尾。
        if (!ProotShell.tryLockRootfsExclusive(ROOTFS_LOCK_WAIT_SEC)) {
            runCatching { zip.delete() }
            return@withContext Capability.Unavailable(
                "本机执行环境正忙（有命令正在跑，或另一次安装/卸载正在进行），本次安装没有开始，临时归档已清理，请稍后重试",
            )
        }
        try {
            // 解压阶段：**任何失败都必须把 30 MB 归档与半截 rootfs 一起清掉**。
            // 留着的代价是真实的：半截 rootfs 有一百多 MB，而下次安装本来也要先删它 ——
            // 与其占着用户存储等下次，不如现在就回收。
            installFromArchive(ctx, zip, rootfs, onProgress)
        } catch (e: Exception) {
            deleteTree(rootfs)
            runCatching { zip.delete() }
            Log.e(TAG, "安装中断：${e.javaClass.simpleName}: ${e.message}", e)
            Capability.Unavailable("安装中断：${e.javaClass.simpleName}: ${e.message}")
        } finally {
            ProotShell.unlockRootfsExclusive()
        }
    }

    /**
     * ④ 解压 → ⑤ 裁剪 + guest 配置。
     *
     * ⚠️ 调用方（[install]）必须已持有 rootfs **独占锁**，且本函数内**不得出现挂起点**
     * （锁要靠同一线程释放）；失败时由 [install] 统一回收归档与半截目录，本函数内的各失败
     * 分支只做"尽早清掉"。
     */
    private fun installFromArchive(
        ctx: Context,
        zip: File,
        rootfs: File,
        onProgress: (Stage, Int) -> Unit,
    ): Capability<File> {
        onProgress(Stage.EXTRACT, -1)
        deleteTree(rootfs)
        if (!rootfs.exists() && !rootfs.mkdirs()) {
            runCatching { zip.delete() }
            return Capability.Unavailable("无法创建目录：${rootfs.absolutePath}")
        }
        val extract = ProcessRun.runTool(
            "tar",
            listOf("xzf", zip.absolutePath, "-C", rootfs.absolutePath),
            EXTRACT_TIMEOUT_SEC,
        )
        // ⚠️ 不看退出码就下结论：Android 禁硬链接，ubuntu-base 里有 2 个硬链接必然失败
        // （`perl5.38.2→perl`、`uncompress→gunzip`），tar 仍会继续且退出码非 0 ⇒ **以 bin/bash 为准**
        if (!ProotShell.rootfsReady(ctx)) {
            deleteTree(rootfs)
            runCatching { zip.delete() }
            return Capability.Unavailable(
                "解压失败（${extract.summary()}）：${extract.stderrTail()}",
            )
        }
        if (!extract.ok) Log.w(TAG, "解压有告警（不影响可用性）：${extract.stderrTail(5)}")

        onProgress(Stage.CONFIGURE, -1)
        trim(rootfs)
        val guest = ProotShell.ensureGuestConfig(ctx)
        if (!guest.allOk) Log.w(TAG, "guest 配置未完全成功：${guest.notes.joinToString()}")

        writeMarker(ctx)
        runCatching { zip.delete() }
        onProgress(Stage.DONE, 100)
        Log.i(TAG, "rootfs 就绪：${rootfs.absolutePath}（占用 ${diskUsageBytes(ctx) / 1024 / 1024} MB）")
        return Capability.Available(rootfs)
    }

    /**
     * 卸载：删掉整个 `files/proot/`（含 rootfs、临时目录、标记）。
     *
     * ⚠️ **不含**随包二进制（那在 `nativeLibraryDir`，属 APK 的一部分，删不掉也不该删）。
     * 走设备的 `rm -rf` 而不是 Kotlin 递归：`rm` **不会跟随符号链接**，
     * 而 rootfs 里有指向 `/etc/alternatives/...` 之类的绝对链接 —— 自己写递归容易踩到"跟随链接删到里面"。
     *
     * ⚠️ 与安装同样取 rootfs **独占锁**：正在跑的命令不能眼看着自己的根文件系统被删掉。
     */
    fun uninstall(ctx: Context): ExecResult {
        val work = ProotShell.workDir(ctx)
        if (!work.exists()) return ExecResult(0, "", "")
        if (!ProotShell.tryLockRootfsExclusive(ROOTFS_LOCK_WAIT_SEC)) {
            return ExecResult(
                -1, "", "",
                "有命令正在本机执行环境中运行，本次没有卸载。请等它跑完再试。",
            )
        }
        try {
            val r = ProcessRun.runTool("rm", listOf("-rf", work.absolutePath), 120L)
            if (work.exists()) {
                Log.w(TAG, "卸载后目录仍在：${work.absolutePath}")
            }
            return r
        } finally {
            ProotShell.unlockRootfsExclusive()
        }
    }

    // ═══════════════════ 内部 ═══════════════════

    private fun markerFile(ctx: Context): File = File(ProotShell.workDir(ctx), MARKER_NAME)

    private fun writeMarker(ctx: Context) {
        runCatching {
            ProotShell.workDir(ctx).mkdirs()
            markerFile(ctx).writeText(
                buildString {
                    appendLine("version=$ROOTFS_VERSION")
                    appendLine("abi=arm64-v8a")
                    appendLine("sha256=$ROOTFS_SHA256")
                    appendLine("installed=${System.currentTimeMillis()}")
                },
            )
        }.onFailure { Log.w(TAG, "写版本标记失败：${it.message}") }
    }

    private fun trim(rootfs: File) {
        val paths = TRIMMABLE.map { "${rootfs.absolutePath}/$it" }
        val r = ProcessRun.runTool("rm", listOf("-rf") + paths, 120L)
        // 裁剪失败不算致命（只是占地方），但要把原因留在日志里
        if (!r.ok) Log.w(TAG, "裁剪未完全成功（${r.summary()}）：${r.stderrTail()}")
    }

    private fun deleteTree(dir: File) {
        if (!dir.exists()) return
        ProcessRun.runTool("rm", listOf("-rf", dir.absolutePath), 120L)
    }

    /**
     * 目录占用统计。**不跟随符号链接**（rootfs 里大量链接指到 guest 内部路径，
     * 跟随会算重复甚至成环），链接自身按 0 计。
     */
    private fun dirSize(root: File): Long {
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
