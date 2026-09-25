package com.rokidlab.phone.ai.tools

import com.rokidlab.phone.R
import com.rokidlab.phone.ai.LabFileOutputs
import com.rokidlab.phone.ai.LabMediaScan
import com.rokidlab.phone.ai.ToolContentTrust
import com.rokidlab.phone.ai.ToolConfirmPolicy
import com.rokidlab.phone.ai.ToolRisk

import android.content.Context
import android.util.Log
import com.rokidlab.phone.ai.ToolRegistry
import com.rokidlab.phone.platform.ProotInstaller
import com.rokidlab.phone.platform.ProotShell
import org.json.JSONObject

/**
 * ShellToolProvider —— 本机执行域：在手机上的 Linux 用户态环境里跑命令。
 *
 * ## 这为什么不是"又一条 Termux 通道"
 * 命令跑在 [ProotShell] 拉起的 **proot 子进程**里（随包 214 KB + 运行期下载的 Ubuntu rootfs），
 * 不是借第三方的 Termux 进程 ⇒ stdout / stderr / 退出码原生可达、超时能强杀。
 * 详见 `platform/ProotShell.kt` 的类注释（域闸门、jniLibs 约束、`useLegacyPackaging` 那个坑）。
 *
 * ## 能力边界（**必须写进 schema**，否则模型会按"能跑一切"的直觉乱猜）
 *  - 有：bash + coreutils（grep/sed/awk/tar/curl…）、apt（可装包）、**可选**的 python3 / node / git；
 *  - 没有：Android 系统命令（`pm`/`am`/`dumpsys`/`input` 是宿主的东西，容器里不存在）、
 *    **真机 root**（`-0` 只是 proot 的 fake root，够 apt 用，不改真机权限）；
 *  - **不挂整个 `/sdcard`**，只开一个共享口子 `/mnt/lab`：授予「所有文件访问」后直通公共
 *    「下载/Lab」，未授权降级 App 私有目录（见 [ProotShell.shareStatus]）。
 *    除 `/mnt/lab` 外的手机存储路径在容器里都不存在，别让模型去猜。
 *
 * ## 风险档为什么是 [ToolRisk.LOCAL_SIDE_EFFECT] 而不是 EXTERNAL
 * 判据是「最坏能坏成什么」：容器是可丢弃的（`rm -rf /` 只毁掉 rootfs，重下 28.5 MB 即恢复），
 * 且**不提权**（同 UID 跑；只 bind `/dev` `/proc` `/sys` 与一个用户知情的专用共享目录
 * `/mnt/lab` ↔ 下载/Lab，不 bind 任何其他用户数据）⇒ 触达不到第三方、
 * 也改不了系统状态。反过来若登记成 [ToolRisk.EXTERNAL_SIDE_EFFECT]，**每次跑条命令都要用户确认**
 * ——"跑条 `ls` 也要先点头"是本机模式下最烦人的交互，而这正是 `save_code_file` 当年那个事故的形状
 * （见 [ToolRisk] 的历史事故注释），是**用错档位本身**造出来的故障，不是安全加固。
 *
 * ⚠️ 但**装包（[TOOL_INSTALL]）是另一件事**，它定档 EXTERNAL：会下几百 MB 外网流量、
 * 改容器状态，且用户对"跑条命令看看"和"给我装个软件"的授权预期不同。判据依然一致 ——
 * **"最坏能坏成什么"**：`run_shell` 最坏毁掉可重下的 rootfs（无外部代价），
 * 而装包最坏是用户流量与存储的实打实消耗。
 *
 * ## 为什么 `shell` 域不在 [com.rokidlab.phone.ai.approval.PageScope.ALLOWED_DOMAINS] 里
 * AIUI 页面是**第三方制品**（模型生成或用户导入的 `.aix`），一旦能调 `run_shell`
 * 就等于把"任意命令执行"交给了页面作者（`install_packages` 同理：任意装包）。
 * 对话路径（用户本人在场）给这个能力是产品意图，
 * 页面路径不是 —— 边界写在 `PageScope.ALLOWED_DOMAINS`（唯一产地）。
 * ⚠️ 刻意**不**写进 `PageScope.DENY_TOOLS`：`RULES.md` 把那张表的定位钉在「技术故障类」，
 * 并要求不逐工具做安全判断；摘域还能让页面拿到的工具清单里也不出现它。
 *
 * ## 结果为什么按**不可信内容**回填
 * 同一条工具既能跑 `ls -la`（本地确定性输出），也能跑 `curl`（外部作者写的内容）——
 * 工具自身分辨不了，所以取保守档：统一走
 * [com.rokidlab.phone.ai.UntrustedContent] 的结构隔离。代价只是多一层包装，
 * 收益是"命令输出里夹带的注入话术"不会被当成指令（这是 `UNTRUSTED_EXTERNAL` 的设计目的）。
 */
internal object ShellToolProvider : ToolProvider {
    private const val TAG = "ShellToolProvider"

    const val TOOL_RUN = "run_shell"

    /**
     * 装包必须走**独立工具**而不是 `run_shell` 里自由发挥的 `apt-get install`。
     *
     * 原因有三条，都是机制性的：
     *  1. **该问用户的要问**：装包会下几百 MB 外网流量、且改动容器状态。它定档
     *     [ToolRisk.EXTERNAL_SIDE_EFFECT] ⇒ 过 [com.rokidlab.phone.ai.approval.ApprovalGate]
     *     的确认闸门（无确认通道/超时按既有 fail-open 语义放行）。而 [TOOL_RUN] 是
     *     `LOCAL_SIDE_EFFECT`（对话路径才能静默执行），**任它在 shell 里 apt 就等于绕开确认** ——
     *     改造前 schema 里写着"装包要用户同意"，但没有任何机制实现这件事。
     *  2. **超时要够长**：apt 装大包实测可到十几分钟，而 [TOOL_RUN] 的超时上限是 600s；
     *     被超时强杀会留下 dpkg 半配置状态（下次得先 `dpkg --configure -a`）。
     *  3. **包名要受限**：本工具只接受包名白名单字符，`apt` 的参数由实现拼装，
     *     模型没有机会把任意 shell 片段塞进 apt 命令行。
     */
    const val TOOL_INSTALL = "install_packages"

    override val toolNames = setOf(TOOL_RUN, TOOL_INSTALL)

    /** 默认超时：日常命令秒级返回；装包那种慢活由模型显式传更大的值 */
    private const val DEFAULT_TIMEOUT_SEC = 120

    /** 超时上限。比 [ProotShell.installPackages] 的 900s 小：对话轮次不该被一条命令钉住太久 */
    private const val MAX_TIMEOUT_SEC = 600

    /** 装包默认/上限超时：apt 装大包（如 ffmpeg）实测可到十几分钟，给足余量避免半配置状态 */
    private const val DEFAULT_INSTALL_TIMEOUT_SEC = 900
    private const val MAX_INSTALL_TIMEOUT_SEC = 1800

    override fun tools(): List<ToolEntry> = listOf(
        ToolEntry(
            name = TOOL_RUN,
            group = ToolRegistry.DOMAIN_SHELL,
            displayNameRes = R.string.ai_tool_run_shell_name,
            descriptionRes = R.string.ai_tool_run_shell_desc,
            risk = ToolRisk.LOCAL_SIDE_EFFECT,
            // 命令可能不是幂等的（`>>` 追加、`rm`、`mv`）：瞬时失败不重试，如实回报由模型决定是否重来
            sideEffect = true,
            contentTrust = ToolContentTrust.UNTRUSTED_EXTERNAL,
            statusText = "正在本机执行命令…",
            schema = toolSchema(
                name = TOOL_RUN,
                description = "在这台手机上的一台 Linux 环境（Ubuntu 容器）里执行 shell 命令，返回退出码、标准输出与标准错误。用于用户要求「跑个命令/算一下/处理文件/写个脚本/用 python/grep 一下」这类需要真正执行的任务，例如统计、文本与数据处理、下载（curl/wget）、解压、调 python3 脚本。⚠️ 这里**不是**手机的 Android shell：pm/am/dumpsys/input/getprop 这类系统命令在容器里不存在，也不要指望真机 root（容器里的 root 是假的，只够 apt 用）。📁 **与手机交换文件只走 /mnt/lab**：它是容器里唯一挂载的手机目录——用户已授予「所有文件访问」时直通手机的「下载/Lab」文件夹（在系统文件管理器和下载里可见，双向读写）；未授权时它在 App 私有目录、用户看不到（结果里会注明当前是哪种）。用户给的文件让他放进「下载/Lab」，容器产出的成品（脚本/图表/CSV）写到 /mnt/lab 用户就能在手机里拿到；除它之外不要猜 /sdcard 等任何手机路径。可用的东西：bash + 常用命令行工具。python3 / node / git 属**可选组件**：缺 python3、git 时可自己用 install_packages 装，缺 node（用户没在本机执行环境里装 Node.js）只能如实告知并指引他去「设置 → 本机执行环境」安装，**不要试着用 apt 装 nodejs 顶替**。⚠️ **缺命令要装包时用 install_packages 工具**，不要在 run_shell 里跑 `apt-get install`：那条通道不问用户、且 600s 超时上限会把装到一半的 dpkg 强杀（留下半配置状态）。命令里多级引号容易写错，建议把复杂逻辑写成 heredoc 或 `bash -c`。⚠️ 返回内容过长会被截断，所以**自己先过滤**：加 `head`/`tail`/`grep -c`/`wc -l`，不要直接 cat 一个大文件。若返回里说「本机执行环境还没安装」，就把这个事实如实告诉用户并指引他去「设置 → 本机执行环境」安装，**不要假装命令已经执行过**。音乐、电话、闹钟、网页搜索这些已有专用工具，不要用本工具绕道。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "command" to mapOf(
                            "type" to "string",
                            "description" to "要执行的 shell 命令（可多行，按 bash 语法书写）。例如：ls -la /etc | head -20、python3 -c \"print(sum(range(10)))\"",
                        ),
                        "cwd" to mapOf(
                            "type" to "string",
                            "description" to "命令的工作目录（容器内路径）。默认 /（容器根，也就是 rootfs）；目录不存在会以退出码 90 失败",
                        ),
                        "timeoutSec" to mapOf(
                            "type" to "integer",
                            "description" to "超时秒数，默认 $DEFAULT_TIMEOUT_SEC，上限 $MAX_TIMEOUT_SEC。装包、下载大文件这类慢操作要显式传大一点",
                            "minimum" to 5,
                            "maximum" to MAX_TIMEOUT_SEC,
                        ),
                    ),
                    "required" to listOf("command"),
                ),
            ),
        ),
        ToolEntry(
            name = TOOL_INSTALL,
            group = ToolRegistry.DOMAIN_SHELL,
            displayNameRes = R.string.ai_tool_install_packages_name,
            descriptionRes = R.string.ai_tool_install_packages_desc,
            // 定档 EXTERNAL 的理由见 [TOOL_INSTALL] 的注释：要下外网流量、改容器状态，
            // 必须有"问用户"这一环。
            risk = ToolRisk.EXTERNAL_SIDE_EFFECT,
            // 但问不到用户时**照做**（PROCEED）：装坏了的代价是"容器里有半个包"，
            // 而容器是可丢弃的（重下 28.5 MB 即恢复），与发短信那种"发出去收不回"不同量级。
            // 反过来若按 BLOCK，本机模式下装包会直接失败 —— 那正是 save_code_file 事故的方向。
            confirmPolicy = ToolConfirmPolicy.PROCEED,
            sideEffect = true,
            contentTrust = ToolContentTrust.UNTRUSTED_EXTERNAL,
            statusText = "正在安装 Linux 软件包…",
            summarize = { args ->
                val names = args.optJSONArray("packages")?.let { a ->
                    (0 until a.length()).map { a.optString(it).trim() }.filter { it.isNotEmpty() }
                }.orEmpty()
                if (names.isEmpty()) "在本机 Linux 环境安装软件包（需要联网下载）"
                else "在本机 Linux 环境安装 ${names.joinToString("、")}（共 ${names.size} 个包，需要联网下载，可能要几分钟）"
            },
            schema = toolSchema(
                name = TOOL_INSTALL,
                description = "在本机的 Linux 环境里用 apt 安装软件包（会先 apt-get update 再安装）。" +
                    "用在你确实需要某个**容器里没有的命令/库**时（如 python3、ffmpeg、imagemagick、jq），" +
                    "不要因为有别的办法可行也来装一遍。⚠️ 装 git 时**一并装 ca-certificates**（apt 走 --no-install-recommends，" +
                    "少了它 `git clone https://…` 会证书校验失败）。" +
                    "⚠️ 装包会联网下载、耗时可能几分钟，会**先请你确认**；" +
                    "用户不同意或环境没装时按返回的说明如实告知，不要假装装过了。⚠️ packages 只写**包名**：" +
                    "不要带版本号（写 python3，不写 python3=3.12）、不要带 apt 参数（不要 -y / --fix-broken）、" +
                    "一次可以传多个。装完**务必实际验证**再用（`command -v <命令>` 或 `<命令> --version`）：" +
                    "apt 在「包其实已装好、只是收尾写日志失败」时也会返回非 0 退出码，只看退出码会误报失败。" +
                    "⚠️ 与 run_shell 是同一个容器环境（同一个 rootfs）；不要在 run_shell 里跑 apt-get install。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "packages" to mapOf(
                            "type" to "array",
                            "items" to mapOf("type" to "string"),
                            "description" to "要安装的包名列表，只写包名，如 [\"python3\", \"ca-certificates\"]",
                        ),
                        "timeoutSec" to mapOf(
                            "type" to "integer",
                            "description" to "超时秒数，默认 $DEFAULT_INSTALL_TIMEOUT_SEC（装大包要十几分钟），上限 $MAX_INSTALL_TIMEOUT_SEC",
                            "minimum" to 60,
                            "maximum" to MAX_INSTALL_TIMEOUT_SEC,
                        ),
                    ),
                    "required" to listOf("packages"),
                ),
            ),
        ),
    )

    override fun execute(context: Context, name: String, args: JSONObject): String {
        // 执行前的共享目录快照：跑完与它做 diff，差集就是这次真正写出来的文件。
        // 为什么靠 diff 而不是解析命令：写文件的方式太多（重定向/tee/脚本内部写/clone），
        // 而**文件系统是唯一权威**。见 [LabFileOutputs]。
        val before = if (name == TOOL_RUN) LabFileOutputs.snapshot(context) else emptyMap()
        val result = when (name) {
            TOOL_RUN -> runShell(context, args)
            TOOL_INSTALL -> installPackages(context, args)
            else -> throw IllegalArgumentException("未知工具: $name")
        }
        // 容器写 /mnt/lab 走的是**内核路径**（不经 MediaProvider），所以产出不会自动进媒体库 ——
        // 表现为 list_files(scope="downloads") 看不到、delete_file 找不到那一行。
        // 每轮命令执行后补扫一次，让两个视图指向同一份东西（异步、幂等、失败只记日志）。
        if (name == TOOL_RUN) {
            LabMediaScan.scanLabOutputs(context)
            // 产出文件报给聊天窗口（没人在看聊天页时 sink 为空，静默）
            LabFileOutputs.publish(context, before)
        }
        return result
    }

    private fun runShell(context: Context, args: JSONObject): String {
        val command = args.optString("command").trim()
        if (command.isEmpty()) return "命令为空，没有执行任何东西。请把要跑的命令放在 command 参数里。"

        // 未装 rootfs 时**不自动下载**：那是 28.5 MB 的外网流量 + 用户存储，
        // 只能由用户在设置页决定（模型自己触发一次 30 MB 下载是最不该有的惊喜）
        if (!ProotInstaller.isInstalled(context)) {
            return "本机执行环境还没安装（缺少 Ubuntu 容器），命令没有执行。" +
                "请如实告诉用户：需要到「设置 → 本机执行环境」点一下安装（约 28 MB，一次性下载），" +
                "装好之后我就能在这台手机上跑命令了。现在不要假装已经执行过，也不要凭空编造输出。"
        }

        val cwd = args.optString("cwd").trim().ifEmpty { "/" }
        val timeout = args.optInt("timeoutSec", DEFAULT_TIMEOUT_SEC).coerceIn(5, MAX_TIMEOUT_SEC)
        val result = runCatching { ProotShell.runCommand(context, command, cwd = cwd, timeoutSec = timeout.toLong()) }
            .getOrElse { e ->
                Log.w(TAG, "run_shell 抛异常：${e.javaClass.simpleName}: ${e.message}")
                return "执行本机命令时出错：${e.javaClass.simpleName}: ${e.message}"
            }
        return format(result) + shareHint(context)
    }

    /**
     * 用 apt 装包（[TOOL_INSTALL] 的实现）。
     *
     * 与 [runShell] 的三点不同，都是刻意的：
     *  1. 超时放宽到 [DEFAULT_INSTALL_TIMEOUT_SEC]（apt 装大包实测十几分钟，600s 会被强杀）；
     *  2. 只接受包名（[ProotShell.installPackages] 内部再校验一次字符集）—— 模型没有机会
     *     把 apt 参数或 shell 片段拼进来；
     *  3. 结果里对 **apt 退出码 100** 加一句解释：`EIPP` 收尾写日志失败时包其实已经装好，
     *     只说"退出码 100"会让模型下"安装失败"的结论并做多余的重试。
     */
    private fun installPackages(context: Context, args: JSONObject): String {
        val arr = args.optJSONArray("packages")
        val packages = buildList {
            if (arr != null) for (i in 0 until arr.length()) add(arr.optString(i).trim())
        }.filter { it.isNotEmpty() }.distinct()
        if (packages.isEmpty()) {
            return "没有指定要安装的包（packages 为空或全是空字符串），没有执行任何操作。" +
                "请把包名放进 packages，例如 [\"python3\"]。"
        }
        // 未装 rootfs 时不自动下载（同 run_shell：28.5 MB 的外网流量只能由用户决定）
        if (!ProotInstaller.isInstalled(context)) {
            return "本机执行环境还没安装（缺少 Ubuntu 容器），没有安装任何软件包。" +
                "请如实告诉用户：需要到「设置 → 本机执行环境」点一下安装（约 28 MB，一次性下载），" +
                "装好之后才能在里面装包。现在不要假装已经装过。"
        }
        val timeout = args.optInt("timeoutSec", DEFAULT_INSTALL_TIMEOUT_SEC)
            .coerceIn(60, MAX_INSTALL_TIMEOUT_SEC).toLong()
        Log.i(TAG, "install_packages: ${packages.joinToString()}（超时 ${timeout}s）")
        val result = runCatching { ProotShell.installPackages(context, packages, timeout) }
            .getOrElse { e ->
                Log.w(TAG, "install_packages 抛异常：${e.javaClass.simpleName}: ${e.message}")
                return "安装软件包时出错：${e.javaClass.simpleName}: ${e.message}"
            }
        val note = if (result.launched && result.code == 100) {
            "\n\n（apt 退出码 100 常见于「包已装好、只是收尾写日志失败」。请用 " +
                "`command -v <命令>` 或 `<命令> --version` 实际验证再下结论，不要直接报失败。）"
        } else {
            ""
        }
        // 刻意**不附** shareHint：装包不产出文件，"共享目录现在指向哪 / 用户看不看得见"
        // 与这次调用无关。那段文案里"用户在文件管理器可见"只对文件产出成立，
        // 附在装包结果后面既是噪音，也会诱导模型向用户报告一个它没做过的事。
        return "安装结果（${packages.joinToString("、")}）：\n" + format(result) + note
    }

    /**
     * 每次结果附一行共享盘当前指向：模型据此判断产出的文件用户在手机上看不看得见。
     *
     * 公共目录那一条额外说明"已补登记到媒体库"—— 容器走内核路径直写 `/mnt/lab`、
     * 不经 MediaProvider，索引由 [LabMediaScan] 在每次执行后补上。
     * 少了这半句，模型会以为"用户能看到"只是文件管理器层面的事，而它自己
     * `list_files(scope="downloads")` 查不到时会转而怀疑文件没写成功。
     */
    internal fun shareHint(context: Context): String {
        val s = ProotShell.shareStatus(context)
        return if (s.publicDownload) {
            "\n\n[共享目录] /mnt/lab ↔ 手机「下载/Lab」，双向读写，用户在系统文件管理器可见" +
                "（产出已补登记进媒体库，list_files/downloads 也能查到）。"
        } else {
            "\n\n[共享目录] /mnt/lab 当前指向 App 私有目录（未授予「所有文件访问」），用户看不到；" +
                "要把文件交给用户，请引导其到「设置 → 本机执行环境」授权后再写入 /mnt/lab。"
        }
    }

    /**
     * 把执行结果拼成回填给模型的文本。
     *
     * 三样东西都要给：**退出码**（模型据此判断成没成）、**stdout**、**stderr**
     * （很多命令失败时只往 stderr 写、stdout 是空的，只回 stdout 会让模型以为"没输出=没报错"）。
     * [com.rokidlab.phone.ai.ToolRegistry.execute] 的调用方还会统一做超长截断，
     * 所以这里不做二次截断，只把空段省掉以免白占 token。
     */
    internal fun format(r: com.rokidlab.phone.platform.ExecResult): String {
        if (!r.launched) {
            return "命令没被拉起来（环境本身有问题）：${r.failure}。" +
                "这通常意味着容器二进制缺失或被系统挡住，请如实告知用户，并建议他到「设置 → 本机执行环境」跑一次自检。"
        }
        val sb = StringBuilder("退出码 ${r.code}")
        if (r.stdout.isNotBlank()) sb.append("\n\n[标准输出]\n").append(r.stdout.trim())
        if (r.stderr.isNotBlank()) sb.append("\n\n[标准错误]\n").append(r.stderr.trim())
        if (r.code != 0 && r.stdout.isBlank() && r.stderr.isBlank()) {
            sb.append("\n（命令没有产生任何输出，但退出码非 0）")
        }
        return sb.toString()
    }
}
