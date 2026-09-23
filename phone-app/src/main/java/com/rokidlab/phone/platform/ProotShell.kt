package com.rokidlab.phone.platform

import android.content.Context
import android.os.Build
import android.os.Environment
import android.util.Log
import java.io.File
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantReadWriteLock

/**
 * 自持 proot 的启动器：本机 Linux 用户态执行环境的唯一入口。
 *
 * ## 为什么不是"又一个 Termux"
 * Termux 路线的死穴（单向不回传 stdout、服务卡死、并发覆盖、跨 App 保活做不到）
 * 都是**借别人进程**的必然代价。自持 proot 后 proot 是**本 App 的子进程**：
 * stdout / 退出码原生可达、卡了能 kill、WakeLock 天然覆盖。
 *
 * ## 二进制为什么必须随包（不能像 rootfs 那样下载）
 * Android 10+ 的 W^X 只限制 **`execve`**：需要内核 `execve` 的东西（proot 与它的 loader）
 * 必须落在**唯一被挂载为可执行**的位置 —— `nativeLibraryDir`（SELinux 类型 `apk_data_file`）。
 * 而 rootfs 里那几万个二进制**不需要**（proot 用 ptrace 把 `execve` 改写成 loader 调用，
 * 由 loader 自己 mmap 加载，绕过内核 `execve`）⇒ 放 `filesDir` 照样跑。
 *
 * ⚠️ **域闸门**：`nativeLibraryDir` 在各 app 域**都可 exec**，但 `untrusted_app_xx`
 * （装包 `targetSdk ≥ 29`）对 app **私有**目录是禁的 —— 判据是**装包时的 targetSdkVersion**，
 * 不是 Android 版本，且运行期不可变。本 App `targetSdk 34` ⇒ 只能走本文件这条路。
 *
 * ## jniLibs 的两个硬约束（都已实测）
 *  - **不支持子目录** ⇒ `proot` 与 `loader` 平铺在 `lib/arm64-v8a/`，用 `PROOT_LOADER` 指路；
 *  - 改名成 `.so` **不影响**能否 exec（看的是 ELF 有无 entry point / `PT_INTERP`）。
 *
 * ⚠️ 还有一条**打包**侧的约束（不在本文件里）：`build.gradle.kts` 必须
 * `useLegacyPackaging = true`，否则 `.so` 不落盘、`nativeLibraryDir` 是空目录 ⇒ 必然 ENOENT。
 *
 * ## 职责边界
 * 本对象只管"把 proot 拉起来 / 把命令送进去"；**rootfs 的下载与解压属于 [ProotInstaller]**，
 * 本对象只假定"rootfs 已就绪"（用 [rootfsReady] 判定）。
 *
 * ## ⚠️ 法务（二进制随包 ≠ 无义务）
 * `proot` 上游是 **GPLv2**，把二进制放进 APK 属于**分发** ⇒ 带传染性义务
 * （须附对应源码或书面 offer）；`loader` 同源。rootfs 走**运行期下载**、不经我们分发
 * ⇒ 只带 `proot` + `loader`（约 214 KB）是法务面最干净的组合。
 *
 * > 取证与 POC 全过程见技能 `android-proot-selfhost`（§1.1 域选择表 / §4 启动参数 / §7 jniLibs 两坑 / references §F）。
 */
object ProotShell {

    private const val TAG = "ProotShell"

    /** 随包二进制（必须与 `jniLibs/arm64-v8a/` 下的文件名逐字一致） */
    const val LIB_PROOT = "libproot.so"
    const val LIB_LOADER = "libproot-loader.so"

    /** filesDir 下的工作区：`files/proot/{tmp,rootfs,extras}` */
    private const val WORK_DIR = "proot"
    private const val TMP_NAME = "tmp"
    private const val ROOTFS_NAME = "rootfs"
    private const val EXTRAS_NAME = "extras"

    /**
     * guest 内的固定环境值。
     *
     * ⚠️ `PATH` **必须显式给**：不给就继承宿主 Android 的 PATH，guest 里 `ls`/`cat`/`id`
     * 全部 `command not found` —— 这是**最容易误判成"proot 坏了"**的坑。
     */
    private const val GUEST_PATH = "/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"
    private const val GUEST_HOME = "/root"

    /**
     * guest 内的**共享文件夹**固定挂载点：容器与手机交换文件的唯一口子。
     * - 授予「所有文件访问」后直通公共 **下载/Lab**（文件管理器/USB 可见，双向读写）；
     * - 未授权时降级到 App 私有外部目录（功能不中断，只是用户不方便直接看到）。
     */
    const val GUEST_SHARE = "/mnt/lab"

    /**
     * guest 内的**附加组件**固定挂载点（[extrasDir] 的宿主目录挂到这里）。
     *
     * 与 rootfs 分开是有意的：附加组件（如 Node.js）体积不小、下载也慢，而 rootfs
     * 是"重装一次全部清空"的语义 —— 挂在外面，重装 rootfs 不会把它们一起清掉。
     */
    const val GUEST_EXTRAS = "/opt/extras"

    /**
     * 宿主侧共享目录名（公共下载目录下的子目录）。
     *
     * ⚠️ `internal` 而不是 `private`：`WebToolProvider` 的 `download_file` 需要把默认落盘目录
     * 对齐到它（否则 `download_file` 下到 `Download/` 顶层、容器只挂了 `Download/Lab/`，
     * 系统提示里那句"下完可以在 run_shell 里直接用"就成了空头承诺）。
     * 名字只有一个产地，改一处两边同步。
     */
    internal const val SHARE_DIR_NAME = "Lab"

    /**
     * guest 的 DNS。rootfs 自带的 `/etc/resolv.conf` 是 **0 字节**，
     * 而宿主 Android **根本没有** `/etc/resolv.conf`（用 netd）⇒ 不写就解析不了域名。
     */
    private val DNS_SERVERS = listOf("223.5.5.5", "119.29.29.29")

    /**
     * apt 镜像（arm64 走 `-ports` 仓库）。
     *
     * 为什么是 **http** 而不是 https：ubuntu-base **不带 `ca-certificates`**，
     * 首次 apt 走 https 必然失败（鸡生蛋）；装上 python3 后会连带装上 121 个证书，之后 https 自然通。
     */
    const val APT_MIRROR_HTTP = "http://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports/"

    /** 脚本序号：并发执行时避免同名脚本互相覆盖（命令是"落盘再执行"的，见 [runScript]） */
    private val scriptSeq = AtomicInteger(0)

    // ═══════════════════ 并发闸门 ═══════════════════

    /**
     * rootfs 的**读写锁**：执行命令取共享侧，安装 / 卸载取独占侧。
     *
     * ## 为什么必须有
     * [ProotInstaller.install] 的第一步是「先 `rm -rf rootfs/` 再解压」，而 AI 完全可能
     * 正在同一个 rootfs 里跑命令（用户点「重新安装」的那一刻，恰好有一条 `run_shell` 在跑）。
     * 两者交叠的后果是**随机**的：两条 `tar` 往同一目录解压 → sha256 必挂 / 目录损坏；
     * 或者 AI 正在读的文件被 `rm` 掉 → 报一个与管理无关的诡异错误。极难定位。
     *
     * ## 为什么是读写锁而不是互斥锁
     * 多条命令并行执行本身是允许的（只争 CPU，见 [MAX_CONCURRENT_EXECUTIONS]）；
     * 真正不能与任何人重叠的只有"**换掉整个 rootfs 目录**"这一个动作。
     *
     * ⚠️ `unlock` 必须与 `tryLock` 在**同一线程**（JDK 语义）⇒ 持锁区间内**不得有挂起点**
     * （协程在挂起点之后可能被恢复到另一个线程）。
     * 执行侧（[runScript]）本来就是同步阻塞调用，天然满足；**安装侧**原先跨了 suspend 的下载，
     * 已把锁收缩到「解压 / 裁剪 / 写标记」这段非 suspend 区间（见 [ProotInstaller.install]）。
     */
    private val rootfsLock = ReentrantReadWriteLock()

    /**
     * 同时在跑的 proot 数量上限。
     *
     * 模型在同一轮里并发发起多个工具调用是常态，而每条命令都是一个真进程树，
     * 且都往同一个 `/mnt/lab` 写。不设上限时「两条 apt + 一条 tar」足以让低端机卡到 ANR。
     * 取 2：够覆盖"一边跑主任务一边探一下环境"的正常用法。
     */
    private const val MAX_CONCURRENT_EXECUTIONS = 2

    /** 执行槽位（公平模式：先到先得，避免后到的大批量调用把先到的饿死） */
    private val execSlots = Semaphore(MAX_CONCURRENT_EXECUTIONS, true)

    /** 等 rootfs / 槽位的上限：超时即**如实拒绝**，不硬等（等待会让模型这一轮整体变慢且看不到原因） */
    private const val EXEC_WAIT_SEC = 5L

    /**
     * 申请 rootfs **独占**（安装 / 卸载用）。
     *
     * @return false = 有命令正在跑（或另一次安装在进行）⇒ 调用方应如实拒绝并让用户稍后重试
     */
    internal fun tryLockRootfsExclusive(timeoutSec: Long = EXEC_WAIT_SEC): Boolean =
        runCatching { rootfsLock.writeLock().tryLock(timeoutSec, TimeUnit.SECONDS) }.getOrDefault(false)

    /** 释放 [tryLockRootfsExclusive]（必须是同一线程） */
    internal fun unlockRootfsExclusive() {
        unlockLogged(rootfsLock.writeLock())
    }

    /**
     * 释放锁并**把失败喊出来**。
     *
     * 这里刻意不静默：`unlock` 抛异常只有一种原因 —— 释放线程不是持锁线程（协程跨了挂起点）。
     * 一旦发生，锁**永久泄漏**，此后每条命令都被判"正在安装中"、重启 App 才恢复。
     * 静默吞掉会把这种"最贵的 bug"变成无迹可寻的偶发故障，所以宁可留一条 error 日志。
     */
    private fun unlockLogged(lock: java.util.concurrent.locks.Lock) {
        runCatching { lock.unlock() }
            .onFailure { Log.e(TAG, "释放 rootfs 锁失败（疑似跨线程释放 ⇒ 锁已泄漏）：${it.message}") }
    }

    // ═══════════════════ 路径 ═══════════════════

    /** 随包二进制的落地目录（`/data/app/<随机>/<包>-<hash>/lib/arm64`） */
    fun binDir(ctx: Context): File = File(ctx.applicationInfo.nativeLibraryDir.orEmpty())

    fun prootFile(ctx: Context): File = File(binDir(ctx), LIB_PROOT)

    fun loaderFile(ctx: Context): File = File(binDir(ctx), LIB_LOADER)

    fun workDir(ctx: Context): File = File(ctx.filesDir, WORK_DIR)

    /** proot 的临时目录。⚠️ **必须设** `PROOT_TMP_DIR`：默认 `/tmp` 在 Android 不存在，不设直接报错。 */
    fun tmpDir(ctx: Context): File = File(workDir(ctx), TMP_NAME)

    fun rootfsDir(ctx: Context): File = File(workDir(ctx), ROOTFS_NAME)

    /** guest 内的 `/tmp`（＝宿主 `<rootfs>/tmp`）：脚本先落这里，再由 guest 按 `/tmp/x.sh` 执行 */
    fun guestTmpDir(ctx: Context): File = File(rootfsDir(ctx), "tmp")

    /** rootfs 是否已就绪（以 guest 的 `/bin/bash` 为锚 —— 它属于 Ubuntu Base 的基线包） */
    fun rootfsReady(ctx: Context): Boolean = File(rootfsDir(ctx), "bin/bash").exists()

    // ═══════════════════ 附加组件（rootfs 之外）═══════════════════

    /** 附加组件根目录：`files/proot/extras/`。**不在 rootfs 里** ⇒ 重装 rootfs 不会清掉。 */
    fun extrasDir(ctx: Context): File = File(workDir(ctx), EXTRAS_NAME)

    /** Node.js 的安装目录（= [NodeAddon] 解压后的落地位置） */
    fun nodeDir(ctx: Context): File = File(extrasDir(ctx), "node")

    /** node 是否在位（以 `bin/node` 为锚；版本一致性由 [NodeAddon.isInstalled] 负责） */
    fun nodeReady(ctx: Context): Boolean = File(nodeDir(ctx), "bin/node").exists()

    /**
     * 挂载附加组件目录（幂等，每次执行前跑一遍：rootfs 重装后 guest 挂载点会一起被删）。
     *
     * 目录不存在时返回 null 而不是先建出来 —— 没装组件就不该在 guest 里凭空多一个空目录。
     */
    private fun ensureExtrasBind(ctx: Context): String? = runCatching {
        val extras = extrasDir(ctx)
        if (!extras.exists()) return null
        val guest = File(rootfsDir(ctx), GUEST_EXTRAS.trimStart('/'))
        if (!guest.exists() && !guest.mkdirs()) {
            Log.w(TAG, "extras guest mkdirs failed: ${guest.absolutePath}")
            return null
        }
        "${extras.absolutePath}:$GUEST_EXTRAS"
    }.getOrNull()

    /**
     * 附加组件追加到 guest `PATH` 的目录。
     *
     * ⚠️ 判据是**目录/二进制是否在位**，不是"某个安装流程是否跑完"：这样命令里的
     * `node` 与"装没装"只有一处产地，也不会因为标记文件缺失就被静默摘掉。
     * 没装时返回空串 —— PATH 里塞不存在的目录只会让排查时多一层困惑。
     */
    private fun guestExtraPath(ctx: Context): String =
        if (nodeReady(ctx)) "$GUEST_EXTRAS/node/bin" else ""

    // ═══════════════════ 共享文件夹（/mnt/lab ↔ 下载/Lab）═══════════════════

    /** 共享文件夹状态（设置页与 run_shell 提示共用） */
    data class ShareStatus(
        /** true = 直通公共「下载/Lab」；false = 降级在 App 私有目录 */
        val publicDownload: Boolean,
        /** 手机上的真实路径 */
        val hostPath: String,
        /** 容器内的挂载点（固定 [GUEST_SHARE]） */
        val guestPath: String = GUEST_SHARE,
    )

    /**
     * 公共下载目录能否走**内核路径**直读写。
     *
     * 只有 Android 11+ 且拿到 `MANAGE_EXTERNAL_STORAGE` 才行：分区存储下 FUSE 会拦截
     * targetSdk≥29 App 对共享集合的直接 File 写入。Android 10 及以下本 App 未声明
     * 存储权限（WRITE_EXTERNAL_STORAGE maxSdk=28 也没有）⇒ 一律走私有降级，不做更花的探测。
     */
    fun canUsePublicDownload(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()

    fun shareStatus(ctx: Context): ShareStatus =
        ShareStatus(canUsePublicDownload(), sharedHostDir(ctx).absolutePath)

    private fun sharedHostDir(ctx: Context): File =
        if (canUsePublicDownload()) {
            File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                SHARE_DIR_NAME,
            )
        } else {
            File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, SHARE_DIR_NAME)
        }

    /**
     * 准备共享挂载：建好宿主目录与 guest 挂载点，返回 `host:guest` 形式的 proot `-b` 参数。
     * 任何一步失败只记日志并返回 null —— 共享盘是增强项，不能因为它把整条命令执行拖垮。
     * 每次执行都重跑一遍（幂等）：rootfs 重装后 guest 挂载点会随 rootfs 一起被删掉。
     */
    private fun ensureShareBind(ctx: Context): String? = runCatching {
        val host = sharedHostDir(ctx)
        if (!host.exists() && !host.mkdirs()) {
            Log.w(TAG, "share host mkdirs failed: ${host.absolutePath}")
            return null
        }
        val guest = File(rootfsDir(ctx), GUEST_SHARE.trimStart('/'))
        if (!guest.exists() && !guest.mkdirs()) {
            Log.w(TAG, "share guest mkdirs failed: ${guest.absolutePath}")
            return null
        }
        "${host.absolutePath}:$GUEST_SHARE"
    }.getOrNull()

    // ═══════════════════ 诊断 ═══════════════════

    /**
     * 环境自述。**失败时先看这一行** —— 它把"是哪个前提不成立"一次讲清，省掉来回猜：
     * 域（SELinux）/ ABI / 二进制是否存在、多大、可读、**可执行**。
     *
     * `canExecute()` 走 `access(X_OK)`，而 SELinux 的 `execute` 权限**就挂在这条系统调用上**，
     * 所以它能当"域许不许 exec"的**免打包探针**（真正执行则是最终判据）。
     */
    fun environmentReport(ctx: Context): String {
        val p = prootFile(ctx)
        val l = loaderFile(ctx)
        return buildString {
            append("selinux=").append(selfSelinuxContext())
            append(" abi=").append(android.os.Build.SUPPORTED_ABIS.firstOrNull() ?: "?")
            append(" sdk=").append(android.os.Build.VERSION.SDK_INT)
            append(" bindir=").append(binDir(ctx).absolutePath)
            append(" proot[exists=").append(p.exists())
            append(" len=").append(p.length())
            append(" r=").append(p.canRead())
            append(" x=").append(p.canExecute()).append(']')
            append(" loader[exists=").append(l.exists())
            append(" len=").append(l.length())
            append(" x=").append(l.canExecute()).append(']')
            append(" rootfs=").append(rootfsReady(ctx))
        }
    }

    /** 本进程的 SELinux 域（判定"域许不许 exec"时最关键的一格） */
    private fun selfSelinuxContext(): String =
        runCatching { File("/proc/self/attr/current").readText().trim() }.getOrDefault("?")

    // ═══════════════════ 执行 proot ═══════════════════

    /**
     * 执行 `proot <args>` 并回收 stdout / stderr / 退出码。
     *
     * 本函数只负责**把 proot 拉起来并收结果**，不替调用方拼 rootfs 参数 —— 那样调用方
     * 才能按需决定挂载与工作目录。两个必设环境变量在这里统一注入，避免每个调用点各写一遍。
     */
    fun exec(
        ctx: Context,
        args: List<String>,
        timeoutSec: Long = 60L,
        onStdoutLine: ((String) -> Unit)? = null,
        onStderrLine: ((String) -> Unit)? = null,
    ): ExecResult {
        workDir(ctx).mkdirs()
        tmpDir(ctx).mkdirs()
        return ProcessRun.run(
            bin = prootFile(ctx),
            args = args,
            timeoutSec = timeoutSec,
            workDir = workDir(ctx),
            env = mapOf(
                "PROOT_TMP_DIR" to tmpDir(ctx).absolutePath,
                "PROOT_LOADER" to loaderFile(ctx).absolutePath,
            ),
            onStdoutLine = onStdoutLine,
            onStderrLine = onStderrLine,
        )
    }

    /** `proot -V`：能跑出完整版本 banner ⇒ 进程真被 execve 起来了 */
    fun selftest(ctx: Context): ExecResult = exec(ctx, listOf("-V"), timeoutSec = 20L)

    /**
     * 启动期自检：把环境自述与 `-V` 结果落 **logcat**（tag `ProotShell`）。
     *
     * ⚠️ 刻意用 `android.util.Log` 而不是 `LogCollector`：后者**只进内存环形缓冲、不写 logcat**，
     * 而这正是从 adb 侧排查需要读到的东西。
     *
     * 每次冷启动都跑（一次 exec 开销 < 100ms、在后台线程），因为它同时是"环境健康检查"。
     */
    fun startupCheck(ctx: Context) {
        val env = environmentReport(ctx)
        Log.i(TAG, "[env] $env")
        val r = selftest(ctx)
        if (r.launched && r.code == 0) {
            Log.i(TAG, "[env] PASS: proot 可执行 → ${firstLine(r.stdout)}")
        } else {
            Log.e(TAG, "[env] FAIL: ${r.summary()}")
            if (r.stdout.isNotBlank()) Log.e(TAG, "[env] stdout: ${r.stdout.replace('\n', ' ')}")
            if (r.stderr.isNotBlank()) Log.e(TAG, "[env] stderr: ${r.stderr.replace('\n', ' ')}")
        }
    }

    // ═══════════════════ 在 rootfs 里执行 ═══════════════════

    /**
     * proot 的基础参数 —— **逐字对照真机 POC 实测通过的那一条**（技能 §4 / runbook §A.5）。
     *
     * ⚠️ 不要凭记忆增删开关：`--link2symlink` 是 Android 禁硬链接的应对（缺了 apt/解压会报
     * `can't link`）；`-p -L` 与 `PROOT_LOADER` 一起解决 **jniLibs 不支持子目录**（loader 必须平铺）。
     */
    private fun baseArgs(ctx: Context, extraBinds: List<String>): List<String> = buildList {
        add("-0")                                  // fake root：apt 必需
        add("-r"); add(rootfsDir(ctx).absolutePath)
        add("-b"); add("/dev")
        add("-b"); add("/proc")
        add("-b"); add("/sys")
        extraBinds.forEach { add("-b"); add(it) }
        add("--link2symlink")
        // ⚠️ 超时强杀只杀掉 proot 自己：tracee 会被 reparent 到 init 继续跑（占 CPU/IO，
        // 且我们再也回收不到它）。`--kill-on-exit` 让 proot 退出时连带清掉整棵进程树 ——
        // 这条对「被超时打断的 apt」尤其重要（否则 dpkg 留在半配置状态，下次还得
        // `dpkg --configure -a`）。见技能 android-proot-selfhost §7.5）。
        add("--kill-on-exit")
        add("-p"); add("-L")
    }

    /**
     * 补建 guest 运行期需要、但 ubuntu-base 里**不存在**的目录。
     *
     * 目前只有一项，但它是"装了却报失败"的元凶：`apt-get install` 收尾（EIPP）要写
     * `/var/log/apt/eipp.log.xz`，ubuntu-base 连 `/var/log` 都没有 ⇒ apt 以 **退出码 100**
     * 结束（**包其实已经装好**），上层只看退出码就会显示"安装失败"。
     *
     * 与 [ensureShareBind] 同样处理：每次执行前跑一遍（幂等），失败只记日志不拖垮命令。
     */
    private fun ensureGuestDirs(ctx: Context) {
        runCatching { File(rootfsDir(ctx), "var/log/apt").mkdirs() }
            .onFailure { Log.w(TAG, "log dir mkdirs failed: ${it.message}") }
    }

    /**
     * 在 rootfs 里执行一段脚本，回收 stdout / stderr / 退出码。
     *
     * **脚本先落盘再执行**：命令行里堆多级引号是自找麻烦，且脚本文件让报错能定位到行。
     * 脚本随执行结束即删除（放 `rootfs/tmp`，guest 用 `/tmp/<name>` 引用）。
     *
     * 进门要过并发闸门（超时即**如实拒绝**，返回带原因的 `ExecResult` 而不是抛异常）：
     *  1. rootfs 锁 —— [exclusive] = false 时取**共享读锁**（只与"换整个 rootfs"互斥，
     *     多条命令之间仍可并行）；[exclusive] = true 时取**独占写锁**，与其它命令和安装全互斥；
     *  2. 执行槽位 —— 同时在跑的命令上限 [MAX_CONCURRENT_EXECUTIONS]（仅共享路径需要，
     *     独占路径本身已经排他）。
     *
     * ⚠️ 锁的获取与释放在**同一线程**（JDK 语义）⇒ 本函数内不得有挂起点。它不是 suspend 函数，
     * 调用方（工具循环线程 / IO 协程）都在同一线程上同步调用，天然满足。
     *
     * @param exclusive true = 本次执行会**改动容器内容**（装包），必须独占。
     *   为什么装包要独占：apt 解包期间 `/var/lib/dpkg` 处于"半配置"状态、包文件也只解了一半，
     *   此时另一条命令读到的是**半写**的结果（`command -v` 说没有、`ls` 说文件在）；
     *   而且装包动辄几百秒，占着一个执行槽位会让并发的命令白等。
     * @param extraBinds 追加的挂载（如 `"/storage/emulated/0:/sdcard"`）。
     *   ⚠️ **默认不挂 `/sdcard`**：app 域通常没有该目录的访问权（shell 域能读不代表 app 域能读），
     *   挂了只会让 guest 里出现一个"看得见但打不开"的目录，徒增困惑。
     */
    fun runScript(
        ctx: Context,
        script: String,
        timeoutSec: Long = 180L,
        extraBinds: List<String> = emptyList(),
        onStdoutLine: ((String) -> Unit)? = null,
        onStderrLine: ((String) -> Unit)? = null,
        exclusive: Boolean = false,
    ): ExecResult {
        if (exclusive) {
            if (!tryLockRootfsExclusive(EXEC_WAIT_SEC)) {
                return ExecResult(
                    -1, "", "",
                    "本机执行环境正忙（有命令正在跑，或另一次安装/卸载正在进行），本次操作没有执行，请稍后重试。",
                )
            }
            return try {
                runScriptLocked(ctx, script, timeoutSec, extraBinds, onStdoutLine, onStderrLine)
            } finally {
                unlockRootfsExclusive()
            }
        }
        // ① 与「换整个 rootfs」互斥（共享侧：多条命令之间仍可并行）
        val readLock = rootfsLock.readLock()
        if (!runCatching { readLock.tryLock(EXEC_WAIT_SEC, TimeUnit.SECONDS) }.getOrDefault(false)) {
            return ExecResult(
                -1, "", "",
                "本机执行环境正在安装或更新中，命令没有执行。请等安装完成后再试。",
            )
        }
        // ② 并发上限
        if (!runCatching { execSlots.tryAcquire(EXEC_WAIT_SEC, TimeUnit.SECONDS) }.getOrDefault(false)) {
            unlockLogged(readLock)
            return ExecResult(
                -1, "", "",
                "同时运行的本机命令太多（上限 $MAX_CONCURRENT_EXECUTIONS 条），本次没有执行，请稍后重试。",
            )
        }
        try {
            return runScriptLocked(ctx, script, timeoutSec, extraBinds, onStdoutLine, onStderrLine)
        } finally {
            execSlots.release()
            unlockLogged(readLock)
        }
    }

    /**
     * [runScript] 的实际执行体（调用方**已持有** rootfs 锁 —— 共享读锁或独占写锁）。
     *
     * 抽出来只为让"取锁 / 释放"在 [runScript] 里各有一处、两条路径共用同一段执行逻辑：
     * 否则加一条并发语义就要把这一百行再抄一遍，两边的差异（比如某个 early return 忘了释放）
     * 是**不会报错**的那种 bug。
     */
    private fun runScriptLocked(
        ctx: Context,
        script: String,
        timeoutSec: Long,
        extraBinds: List<String>,
        onStdoutLine: ((String) -> Unit)?,
        onStderrLine: ((String) -> Unit)?,
    ): ExecResult {
        if (!rootfsReady(ctx)) {
            return ExecResult(-1, "", "", "rootfs 未安装（缺 ${File(rootfsDir(ctx), "bin/bash").absolutePath}）")
        }
        val tmp = guestTmpDir(ctx)
        if (!tmp.exists() && !tmp.mkdirs()) {
            return ExecResult(-1, "", "", "无法创建 guest 临时目录：${tmp.absolutePath}")
        }
        ensureGuestDirs(ctx)
        val name = "rl-${scriptSeq.incrementAndGet()}-${System.currentTimeMillis() % 100000}.sh"
        val file = File(tmp, name)
        return try {
            // CRLF 会让 shebang/`sed` 出诡异错（Windows 侧传进来的脚本尤其容易带上）
            file.writeText(script.replace("\r\n", "\n"))
            // 默认挂共享文件夹 /mnt/lab 与附加组件 /opt/extras；调用方显式传的 extraBinds 在后（允许覆盖/扩展）
            val binds = listOfNotNull(ensureShareBind(ctx), ensureExtrasBind(ctx)) + extraBinds
            val extraPath = guestExtraPath(ctx)
            val pathValue = if (extraPath.isEmpty()) GUEST_PATH else "$GUEST_PATH:$extraPath"
            val args = baseArgs(ctx, binds) + listOf(
                "/usr/bin/env", "-i",
                "PATH=$pathValue",
                "HOME=$GUEST_HOME",
                "TERM=dumb",
                "LANG=C.UTF-8",
                "/bin/bash", "--noprofile", "--norc", "/tmp/$name",
            )
            exec(ctx, args, timeoutSec, onStdoutLine, onStderrLine)
        } catch (e: Exception) {
            ExecResult(-1, "", "", "写脚本失败：${e.javaClass.simpleName}: ${e.message}")
        } finally {
            runCatching { file.delete() }
        }
    }

    /**
     * 在 rootfs 里执行一条命令（[runScript] 的便捷包装）。
     *
     * `cwd != "/"` 时**用脚本内 `cd` 实现**，不用 proot 的 `-w`：`cd` 失败会由 bash 自己带着
     * 退出码回来（90），比 proot 启动期报错更好定位。
     */
    fun runCommand(
        ctx: Context,
        command: String,
        cwd: String = "/",
        timeoutSec: Long = 180L,
        extraBinds: List<String> = emptyList(),
        onStdoutLine: ((String) -> Unit)? = null,
        onStderrLine: ((String) -> Unit)? = null,
    ): ExecResult {
        val prefix = if (cwd == "/") "" else "cd ${shellQuote(cwd)} || exit 90\n"
        return runScript(ctx, prefix + command + "\n", timeoutSec, extraBinds, onStdoutLine, onStderrLine)
    }

    // ═══════════════════ guest 运行期配置 ═══════════════════

    /**
     * 冒烟测试：一条命令把"这环境到底能不能用"讲清（架构 / 发行版 / 是否已有 python3 / 磁盘）。
     *
     * 为什么不用 `python3 -V` 当唯一判据：ubuntu-base **不带 python3**，
     * 装包前那次必然失败 —— 失败信息会让人以为"环境坏了"。
     */
    fun smokeTest(ctx: Context, timeoutSec: Long = 60L): ExecResult = runCommand(
        ctx,
        """
        echo "abi: $(uname -m)"
        echo "os: $(sed -n 's/^PRETTY_NAME=//p' /etc/os-release 2>/dev/null)"
        echo "python: $(command -v python3 || echo '未安装')"
        echo "node: $(command -v node || echo '未安装')"
        echo "git: $(command -v git || echo '未安装')"
        echo "share: $(if touch /mnt/lab/.wtest 2>/dev/null; then rm -f /mnt/lab/.wtest; echo '/mnt/lab 可读写'; else echo '/mnt/lab 不可用'; fi)"
        echo "disk: $(df -h / | tail -1 | tr -s ' ' | cut -d' ' -f2-4)"
        """.trimIndent(),
        timeoutSec = timeoutSec,
    )

    /**
     * 一次性探测多个 guest 命令是否可用。
     *
     * 为什么合成一条：每次 [runCommand] 都是**一次完整的 proot 启动**（几十毫秒到上百毫秒），
     * 设置页要同时看 python3 / node / git 三个组件，各起一次纯属浪费 —— 一次进 guest 全问完。
     *
     * 非法命令名（含空白、`;` 等）直接剔除：这条字符串会进 shell 脚本，
     * 保持"进入 shell 的输入必须受限"这条纪律。
     */
    fun probeCommands(ctx: Context, names: List<String>): Map<String, Boolean> {
        val safe = names.filter { it.matches(Regex("[a-z0-9][a-z0-9._+-]*")) }
        if (safe.isEmpty()) return emptyMap()
        val script = safe.joinToString("\n") { n ->
            "if command -v $n >/dev/null 2>&1; then echo '$n=1'; else echo '$n=0'; fi"
        } + "\n"
        val r = runScript(ctx, script, timeoutSec = 30L)
        return safe.associateWith { n -> r.stdout.lineSequence().any { it.trim() == "$n=1" } }
    }

    /**
     * apt 安装的可见进度（给 UI 用）。
     *
     * apt 在**非 tty** 下不输出百分比进度条（fancy progress 需要 tty），
     * 只能从它的状态行里**尽力解析**：
     *  - [Phase.UPDATE] / [Phase.DEPS] / [Phase.UNPACK] / [Phase.SETUP] 无法给百分比 ⇒ percent = -1；
     *  - [Phase.FETCH] 用 `Need to get <总大小>` 与每行 `Get:… [<单项大小>]` 累计出百分比。
     *
     * @param percent 0..100；-1 表示该阶段无确定百分比（UI 用不定进度条）
     * @param detail 人类可读的子状态（如 "12.3 MB / 45.6 MB"）；无则空串
     */
    data class AptProgress(
        val phase: Phase,
        val percent: Int = -1,
        val detail: String = "",
    ) {
        enum class Phase { UPDATE, DEPS, FETCH, UNPACK, SETUP }
    }

    /**
     * apt 输出行 → [AptProgress] 的流式解析器（有状态，一次安装用一个实例）。
     *
     * 回调在 ProcessRun 的 stderr 读线程上触发，[onChange] 只在**阶段或百分比变化**时调用，
     * 避免每个 Get: 行都刷一遍 UI。
     */
    private class AptProgressParser(private val onChange: (AptProgress) -> Unit) {
        private var phase = AptProgress.Phase.UPDATE
        private var percent = -1
        private var totalBytes = 0L
        private var fetchedBytes = 0L

        init {
            // 先吐一个初态，UI 立刻能切到"更新软件源"
            onChange(AptProgress(phase, -1, ""))
        }

        fun feed(line: String) {
            val s = line.trim()
            when {
                s.startsWith("Setting up ") -> update(AptProgress.Phase.SETUP)
                s.startsWith("Unpacking ") || s.startsWith("Selecting ") ||
                    s.startsWith("Preparing to unpack") -> update(AptProgress.Phase.UNPACK)

                s.startsWith("Fetched ") -> update(
                    AptProgress.Phase.FETCH,
                    percent = if (totalBytes > 0) 100 else -1,
                    detail = if (totalBytes > 0) "${humanSize(totalBytes)} / ${humanSize(totalBytes)}" else "",
                )

                else -> {
                    val need = NEED_GET.find(s)
                    if (need != null) {
                        // "Need to get 45.6 MB of archives." —— 随后每个 Get: 行的方括号就是单项大小
                        totalBytes = parseSize(need.groupValues[1], need.groupValues[2])
                        fetchedBytes = 0L
                        update(AptProgress.Phase.FETCH, -1, "")
                    } else if (s.contains("Building dependency tree") ||
                        s.contains("Reading state information")) {
                        update(AptProgress.Phase.DEPS)
                    } else if (s.startsWith("Get:") || s.startsWith("Ign:") || s.startsWith("Hit:")) {
                        if (phase == AptProgress.Phase.FETCH) {
                            val item = BRACKET_SIZE.find(s) ?: return
                            fetchedBytes += parseSize(item.groupValues[1], item.groupValues[2])
                            val pct = if (totalBytes > 0) {
                                (fetchedBytes * 100 / totalBytes).toInt().coerceIn(0, 99)
                            } else -1
                            val detail = if (totalBytes > 0) {
                                "${humanSize(fetchedBytes)} / ${humanSize(totalBytes)}"
                            } else ""
                            update(AptProgress.Phase.FETCH, pct, detail)
                        } else {
                            // apt-get update 的索引下载（Hit/Get 仓库元数据），无总量 ⇒ 不定条
                            update(AptProgress.Phase.UPDATE)
                        }
                    }
                }
            }
        }

        private fun update(newPhase: AptProgress.Phase, percent: Int = -1, detail: String = "") {
            if (newPhase == phase && percent == this.percent && detail == lastDetail) return
            phase = newPhase
            this.percent = percent
            lastDetail = detail
            onChange(AptProgress(newPhase, percent, detail))
        }

        private var lastDetail = ""

        companion object {
            // apt 输出："Need to get 45.6 MB of archives."（LANG=C 时无千分位；保险起见兼容逗号）
            val NEED_GET = Regex("""Need to get ([\d.]+)\s*([kMG]?B) of archives""")
            val BRACKET_SIZE = Regex("""\[([\d.,]+)\s*([kMG]?B)]\s*$""")

            fun parseSize(num: String, unit: String): Long {
                val v = num.replace(",", "").toDoubleOrNull() ?: return 0L
                val mult = when (unit) {
                    "B" -> 1L
                    "kB" -> 1024L
                    "MB" -> 1024L * 1024L
                    "GB" -> 1024L * 1024L * 1024L
                    else -> 1L
                }
                return (v * mult).toLong()
            }

            fun humanSize(bytes: Long): String = when {
                bytes >= 1024L * 1024L * 1024L -> "%.1f GB".format(bytes.toDouble() / 1024 / 1024 / 1024)
                bytes >= 1024L * 1024L -> "%.1f MB".format(bytes.toDouble() / 1024 / 1024)
                bytes >= 1024L -> "%.0f kB".format(bytes.toDouble() / 1024)
                else -> "$bytes B"
            }
        }
    }

    /**
     * 用 guest 内的 apt 装包（首次装 `python3` 会**顺带装上 `ca-certificates`**，
     * 之后 https 自然可用 —— 这正是"首次 apt 必须走 http 源"的鸡生蛋解开点）。
     *
     * ⚠️ 包名只允许 `[a-z0-9.+-]`：这条字符串会进 shell，虽然目前只由我们自己的代码传，
     * 但保持"进入 shell 的输入必须受限"这条纪律，将来接用户/模型输入时才不会出事。
     *
     * `DEBIAN_FRONTEND=noninteractive` 必须有：否则某些包会卡在交互式提问上直到超时。
     *
     * @param onProgress apt 流式进度（阶段 + 尽力解析的百分比），回调在 IO 读线程；默认 null
     */
    fun installPackages(
        ctx: Context,
        packages: List<String>,
        timeoutSec: Long = 900L,
        onProgress: ((AptProgress) -> Unit)? = null,
    ): ExecResult {
        val bad = packages.firstOrNull { !it.matches(Regex("[a-z0-9][a-z0-9.+-]*")) }
        if (bad != null) return ExecResult(-1, "", "", "非法包名：$bad")
        if (packages.isEmpty()) return ExecResult(-1, "", "", "包列表为空")
        val parser = onProgress?.let { AptProgressParser(it) }
        return runScript(
            ctx,
            """
            export DEBIAN_FRONTEND=noninteractive
            apt-get update && apt-get install -y --no-install-recommends ${packages.joinToString(" ")}
            """.trimIndent() + "\n",
            timeoutSec = timeoutSec,
            onStderrLine = parser?.let { p -> { line -> p.feed(line) } },
        )
    }

    /** [ensureGuestConfig] 的结果：两项各自成败 + 失败原因（不抛异常，供 UI 直显） */
    data class GuestConfigResult(
        val dnsOk: Boolean,
        val aptSourceOk: Boolean,
        val notes: List<String> = emptyList(),
    ) {
        val allOk: Boolean get() = dnsOk && aptSourceOk
    }

    /**
     * 把 guest **跑起来之后立刻需要**的两项配置补齐（幂等，可重复调用）：
     *
     *  1. **DNS**：`/etc/resolv.conf` 在 rootfs 里是 0 字节 ⇒ 不写就解析不了域名（apt 直接失败）；
     *  2. **apt 源**：换成国内 http 镜像 —— ①ubuntu-base 没 `ca-certificates`，https 首次必然失败；
     *     ②arm64 必须走 `-ports` 仓库。改的是 **deb822** 的 `URIs:` 行（新格式），不是老的 `sources.list`。
     *
     * 直接操作文件、**不经过 proot**：rootfs 就是一个普通目录，能省一次进程启动，也少一处失败面。
     * 失败不抛异常 —— 这两项都失败时 bash 本身照样能跑，只是"联网/装包"不可用，
     * 所以按能力降级上报（与 `Capability` 接缝的取向一致）。
     */
    fun ensureGuestConfig(ctx: Context): GuestConfigResult {
        val rootfs = rootfsDir(ctx)
        if (!rootfsReady(ctx)) return GuestConfigResult(false, false, listOf("rootfs 未安装"))
        val notes = mutableListOf<String>()

        // ① DNS
        val dnsOk = runCatching {
            val resolv = File(rootfs, "etc/resolv.conf")
            val current = if (resolv.exists()) resolv.readText() else ""
            if (current.contains("nameserver")) return@runCatching true   // 已配过
            resolv.parentFile?.mkdirs()
            resolv.writeText(DNS_SERVERS.joinToString("") { "nameserver $it\n" })
            true
        }.getOrElse {
            notes += "写 resolv.conf 失败：${it.message}"
            false
        }

        // ② apt 源（deb822 优先；老的 sources.list 作为兜底）
        val candidates = listOf("etc/apt/sources.list.d/ubuntu.sources", "etc/apt/sources.list")
        val sources = candidates.map { File(rootfs, it) }.firstOrNull { it.isFile && it.length() > 0 }
        val aptOk = if (sources == null) {
            notes += "未找到 apt 源文件（找过：${candidates.joinToString()}）"
            false
        } else {
            runCatching {
                val text = sources.readText()
                if (!text.contains("URIs:")) {
                    notes += "${sources.name} 没有 URIs: 行（不是 deb822 格式？）"
                    false
                } else {
                    val replaced = text.lineSequence().joinToString("\n") { line ->
                        if (line.trimStart().startsWith("URIs:")) "URIs: $APT_MIRROR_HTTP" else line
                    }
                    sources.writeText(replaced)
                    true
                }
            }.getOrElse {
                notes += "改写 ${sources.name} 失败：${it.message}"
                false
            }
        }
        return GuestConfigResult(dnsOk, aptSourceOk = aptOk, notes = notes)
    }

    // ═══════════════════ 工具 ═══════════════════

    /** POSIX 单引号转义：`it's` → `'it'\''s'`。用于把路径安全塞进脚本。 */
    fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    private fun firstLine(s: String): String = s.lineSequence().firstOrNull { it.isNotBlank() } ?: ""
}
