package com.rokidlab.phone.settings

import com.rokidlab.phone.R
import com.rokidlab.phone.access.LabAccessibility
import com.rokidlab.phone.design.*
import com.rokidlab.phone.platform.ExecResult
import com.rokidlab.phone.platform.NodeAddon
import com.rokidlab.phone.platform.ProotInstaller
import com.rokidlab.phone.platform.ProotShell
import com.rokidlab.phone.util.ManufacturerUtils
import android.content.Context
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccessibilityNew
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.FolderShared
import androidx.compose.material.icons.outlined.IntegrationInstructions
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 「本机执行环境」设置页 —— 按**使用流程**纵向排布：
 *
 * 1. 装环境（未装时唯一主动作；rootfs 下载有阶段 + 百分比进度，可取消）；
 * 2. 按需装组件（Python / Git 走 apt、Node.js 走下载，都有流式进度）；
 * 3. 共享文件夹（一键跳系统授权）；
 * 4. 检测 / 删除（低频操作收在底部）。
 *
 * 状态全部**现场读**（[readStatus]）：页面上每个数字都可能被上一步操作改变，不缓存。
 * 失败原因留在屏幕上（[errorMsg]），不吞掉。
 */

private enum class Task { NONE, INSTALL, PYTHON, NODE, GIT, SELFTEST, UNINSTALL }

/**
 * 一张组件卡在安装中要显示的东西。
 *
 * [percent] = -1 表示当前阶段给不出百分比（apt 解析依赖、Node 解压都属这类）⇒ UI 用不定条。
 */
private data class AddonProgress(
    val percent: Int,
    val label: String,
    val detail: String = "",
)

@Composable
internal fun LocalExecScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    BackHandler(onBack = onBack)

    var task by remember { mutableStateOf(Task.NONE) }
    var status by remember { mutableStateOf(StatusSnapshot.EMPTY) }
    var loading by remember { mutableStateOf(true) }

    // rootfs 安装进度
    var stage by remember { mutableStateOf(ProotInstaller.Stage.CHECK) }
    var stagePct by remember { mutableIntStateOf(-1) }
    // 组件（Python / Node.js / Git）安装进度：同一时刻只会有一个在装（task 互斥，见 installEnv 的连点保护）
    var addon by remember { mutableStateOf<AddonProgress?>(null) }

    var report by remember { mutableStateOf<String?>(null) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var showUninstallConfirm by remember { mutableStateOf(false) }

    // 取消旗：install 的 isCancelled 在 IO 线程被反复读，必须跨线程可见
    val cancelFlag = remember { AtomicBoolean(false) }

    LaunchedEffect(Unit) {
        status = readStatus(ctx)
        loading = false
    }

    // 从「所有文件访问」系统设置页返回时授权状态会变 ⇒ 回前台自动重读
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && task == Task.NONE && !loading) {
                scope.launch { status = readStatus(ctx) }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // ── 安装 / 重试 Linux 环境 ──
    fun installEnv() {
        // 连点保护：`task` 原来是在协程**内部**赋值的，两次快速点击会各自进一次 install。
        // [ProotInstaller.install] 内部现在也有 rootfs 独占锁兜底（第二次会如实拒绝），
        // 但在这里挡住更干净 —— 用户不会看到一条本可避免的"环境正忙"提示。
        if (task != Task.NONE) return
        task = Task.INSTALL
        scope.launch {
            errorMsg = null
            report = null
            stage = ProotInstaller.Stage.CHECK
            stagePct = -1
            cancelFlag.set(false)
            val cap = ProotInstaller.install(
                ctx,
                force = status.installed,
                onProgress = { s, p -> stage = s; stagePct = p },
                isCancelled = { cancelFlag.get() },
            )
            if (!cancelFlag.get()) {
                cap.reasonOrNull?.let { errorMsg = ctx.getString(R.string.local_exec_state_failed, it) }
            }
            status = readStatus(ctx)
            task = Task.NONE
        }
    }

    // ── 走 apt 装组件（Python / Git 共用；Node.js 走下载，见 installNode）──
    fun installAptAddon(target: Task, packages: List<String>, verify: String) {
        if (task != Task.NONE) return
        task = target
        scope.launch {
            errorMsg = null
            report = null
            addon = null
            val r = withContext(Dispatchers.IO) {
                ProotShell.installPackages(ctx, packages) { p ->
                    addon = AddonProgress(p.percent, aptPhaseLabel(ctx, p), p.detail)
                }
            }
            if (r.ok) {
                val check = withContext(Dispatchers.IO) { ProotShell.runCommand(ctx, verify) }
                if (check.ok) {
                    report = check.stdout
                } else {
                    errorMsg = ctx.getString(R.string.local_exec_state_failed, check.summary())
                }
            } else {
                errorMsg = ctx.getString(
                    R.string.local_exec_state_failed,
                    r.stderrTail(4).ifBlank { r.summary() },
                )
            }
            status = readStatus(ctx)
            task = Task.NONE
        }
    }

    // ── 下载安装 Node.js（含 npm/npx；不占 apt，故独立一条路径）──
    fun installNode() {
        if (task != Task.NONE) return
        task = Task.NODE
        scope.launch {
            errorMsg = null
            report = null
            addon = null
            cancelFlag.set(false)
            val cap = NodeAddon.install(
                ctx,
                onProgress = { s, p -> addon = AddonProgress(p, stageLabel(ctx, s, p)) },
                isCancelled = { cancelFlag.get() },
            )
            if (!cancelFlag.get()) {
                cap.reasonOrNull?.let { errorMsg = ctx.getString(R.string.local_exec_state_failed, it) }
            }
            status = readStatus(ctx)
            task = Task.NONE
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BrewBg)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        ModuleHeader(
            title = ctx.getString(R.string.settings_local_exec),
            subtitle = ctx.getString(R.string.settings_local_exec_subtitle),
            color = BrewTeal,
        )
        Spacer(modifier = Modifier.height(20.dp))

        // ════════ 第一步：Linux 环境本体 ════════
        PanelCard {
            when {
                loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = BrewTeal)
                    Spacer(Modifier.width(10.dp))
                    Text(ctx.getString(R.string.local_exec_reading), color = BrewMuted, fontSize = 13.sp)
                }

                task == Task.INSTALL -> EnvInstallProgress(
                    stageLabel = stageLabel(ctx, stage, stagePct),
                    percent = stagePct,
                    onCancel = {
                        // 只置旗：下载循环在下个检查点退出，保证半截文件被清掉
                        cancelFlag.set(true)
                    },
                )

                !status.installed -> Column {
                    CardHeader(
                        icon = Icons.Outlined.Terminal,
                        tint = BrewTeal,
                        title = ctx.getString(R.string.local_exec_install_title),
                        subtitle = ctx.getString(R.string.local_exec_install_hint),
                    )
                    Spacer(Modifier.height(14.dp))
                    BrewButton(
                        text = ctx.getString(R.string.local_exec_install_cta),
                        color = BrewTeal,
                        modifier = Modifier.fillMaxWidth(),
                        onClick = { installEnv() },
                    )
                    errorMsg?.let {
                        Spacer(Modifier.height(10.dp))
                        Text(it, color = BrewRed, fontSize = 12.sp, lineHeight = 17.sp)
                        Spacer(Modifier.height(4.dp))
                        BrewOutlineButton(
                            text = ctx.getString(R.string.local_exec_retry),
                            color = BrewRed,
                            onClick = { installEnv() },
                        )
                    }
                }

                else -> Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(9.dp)
                                .background(BrewSuccess, CircleShape),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = ctx.getString(R.string.local_exec_ready, status.version ?: "?"),
                            color = BrewTextBright,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = ctx.getString(
                            R.string.local_exec_foot,
                            formatSize(status.usageBytes),
                            formatSize(status.freeBytes),
                        ),
                        color = BrewMuted,
                        fontSize = 12.sp,
                    )
                    Spacer(Modifier.height(12.dp))
                    BrewCompactButton(
                        text = ctx.getString(
                            if (task == Task.SELFTEST) R.string.local_exec_testing else R.string.local_exec_selftest,
                        ),
                        color = BrewTeal,
                        enabled = task == Task.NONE,
                        onClick = {
                            scope.launch {
                                task = Task.SELFTEST
                                report = null
                                errorMsg = null
                                val env = ProotShell.environmentReport(ctx)
                                val smoke = withContext(Dispatchers.IO) { ProotShell.smokeTest(ctx) }
                                report = env + "\n\n" + renderExec(smoke)
                                task = Task.NONE
                            }
                        },
                    )
                }
            }
        }

        // ════════ 第二步：按需组件（装完环境才出现）════════
        if (status.installed && !loading) {
            Spacer(Modifier.height(20.dp))
            SectionLabel(ctx.getString(R.string.local_exec_section_addons))
            Spacer(Modifier.height(10.dp))

            // ── 三个按需组件：同一张卡样式，只有图标、文案与安装方式不同 ──
            AddonCard(
                icon = Icons.Outlined.IntegrationInstructions,
                title = ctx.getString(R.string.local_exec_python_name),
                hint = ctx.getString(
                    if (status.pythonReady) R.string.local_exec_python_ready_hint
                    else R.string.local_exec_python_hint,
                ),
                ready = status.pythonReady,
                progress = if (task == Task.PYTHON) addon else null,
                enabled = task == Task.NONE,
                onInstall = {
                    installAptAddon(
                        Task.PYTHON,
                        listOf("python3", "ca-certificates"),
                        "command -v python3 && python3 -V",
                    )
                },
            )

            Spacer(Modifier.height(10.dp))

            // ── Node.js（含 npm/npx）：官方自包含包下载；58 MB 属大件，给「取消」──
            AddonCard(
                icon = Icons.Outlined.Code,
                title = ctx.getString(R.string.local_exec_node_name),
                hint = ctx.getString(
                    if (status.nodeReady) R.string.local_exec_node_ready_hint
                    else R.string.local_exec_node_hint,
                ),
                ready = status.nodeReady,
                progress = if (task == Task.NODE) addon else null,
                enabled = task == Task.NONE,
                onInstall = { installNode() },
                onCancel = { cancelFlag.set(true) },
            )

            Spacer(Modifier.height(10.dp))

            // ── Git（apt：它没有官方静态二进制，手动凑 deb 等于自己重写一遍依赖解析）──
            AddonCard(
                icon = Icons.Outlined.AccountTree,
                title = ctx.getString(R.string.local_exec_git_name),
                hint = ctx.getString(
                    if (status.gitReady) R.string.local_exec_git_ready_hint
                    else R.string.local_exec_git_hint,
                ),
                ready = status.gitReady,
                progress = if (task == Task.GIT) addon else null,
                enabled = task == Task.NONE,
                onInstall = {
                    // ⚠️ 必须带上 ca-certificates：apt 走的是 --no-install-recommends，而它只是 git 的
                    // Recommends ⇒ 单独装 git 后 `git clone https://…` 会因证书校验失败。
                    installAptAddon(
                        Task.GIT,
                        listOf("git", "ca-certificates"),
                        "command -v git && git --version",
                    )
                },
            )

            Spacer(Modifier.height(10.dp))

            // ── 共享文件夹：/mnt/lab ↔ 下载/Lab ──
            PanelCard(
                clickable = status.shareCanOpen && task == Task.NONE,
                onClick = { ManufacturerUtils.openAllFilesSettings(ctx) },
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LeadingIcon(
                        Icons.Outlined.FolderShared,
                        if (status.sharePublic) BrewSuccess else BrewAmber,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            ctx.getString(R.string.local_exec_share_title),
                            color = BrewTextBright,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = when {
                                status.sharePublic ->
                                    ctx.getString(R.string.local_exec_share_public, "下载/Lab")
                                Build.VERSION.SDK_INT < Build.VERSION_CODES.R ->
                                    ctx.getString(R.string.local_exec_share_unsupported)
                                else -> ctx.getString(R.string.local_exec_share_private)
                            },
                            color = BrewMuted,
                            fontSize = 12.sp,
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    when {
                        status.sharePublic -> Icon(
                            Icons.Outlined.CheckCircle,
                            contentDescription = null,
                            tint = BrewSuccess,
                            modifier = Modifier.size(22.dp),
                        )
                        status.shareCanOpen && task == Task.NONE -> BrewCompactButton(
                            text = ctx.getString(R.string.local_exec_enable),
                            color = BrewAmber,
                            onClick = { ManufacturerUtils.openAllFilesSettings(ctx) },
                        )
                    }
                }
            }
        }

        // ════════ 屏幕操作权限（无障碍服务）════════
        // 不受「环境已装」门控：免弹窗截屏与读屏/点击只依赖这个开关，与 Linux 环境是否
        // 已安装无关 —— 只是排布上跟在共享文件夹卡的后面。
        // ⚠️ 但要受「扫描完成」门控（!loading）：readStatus 在已装环境时要起一次 proot
        // 探测组件，期间 status 还是默认快照（accessOn=false）⇒ 不挡的话这张卡会带着
        // 琥珀色"开通"按钮提前露脸，扫描完又闪变成对勾 —— 跟其他卡一样等扫描完再显示。
        if (!loading) {
            Spacer(Modifier.height(20.dp))
            PanelCard(
                clickable = !status.accessOn,
                onClick = { ManufacturerUtils.openAccessibilitySettings(ctx) },
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LeadingIcon(
                        Icons.Outlined.AccessibilityNew,
                        if (status.accessOn) BrewSuccess else BrewAmber,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            ctx.getString(R.string.local_exec_access_title),
                            color = BrewTextBright,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            text = ctx.getString(
                                if (status.accessOn) R.string.local_exec_access_on
                                else R.string.local_exec_access_off,
                            ),
                            color = BrewMuted,
                            fontSize = 12.sp,
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    if (status.accessOn) {
                        Icon(
                            Icons.Outlined.CheckCircle,
                            contentDescription = null,
                            tint = BrewSuccess,
                            modifier = Modifier.size(22.dp),
                        )
                    } else {
                        BrewCompactButton(
                            text = ctx.getString(R.string.local_exec_enable),
                            color = BrewAmber,
                            onClick = { ManufacturerUtils.openAccessibilitySettings(ctx) },
                        )
                    }
                }
            }
        }

        // ── 错误原因（任何任务失败都留在这里）──
        errorMsg?.let { msg ->
            Spacer(Modifier.height(12.dp))
            Text(
                text = msg,
                color = BrewRed,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(BrewShapeStandard)
                    .background(BrewPanel)
                    .border(1.dp, BrewRed.copy(alpha = 0.4f), BrewShapeStandard)
                    .padding(12.dp),
            )
        }

        // ── 检测报告 / Python 版本输出 ──
        report?.let { text ->
            Spacer(Modifier.height(16.dp))
            Text(
                ctx.getString(R.string.local_exec_report),
                color = BrewMuted,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = text,
                color = BrewMuted,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(BrewPanel, BrewShapeStandard)
                    .padding(12.dp),
            )
        }

        // ════════ 底部：删除环境 + 一句说明 ════════
        if (status.installed && !loading) {
            Spacer(Modifier.height(24.dp))
            Row(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .clip(BrewShapeStandard)
                    .clickable(enabled = task == Task.NONE) { showUninstallConfirm = true }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Outlined.DeleteOutline,
                    contentDescription = null,
                    tint = BrewRed,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    ctx.getString(R.string.local_exec_uninstall_cta),
                    color = BrewRed,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        Text(
            text = ctx.getString(R.string.local_exec_note),
            color = BrewDim,
            fontSize = 11.sp,
            lineHeight = 16.sp,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        Spacer(Modifier.height(40.dp))
    }

    if (showUninstallConfirm) {
        BrewDialog(
            onDismiss = { showUninstallConfirm = false },
            title = ctx.getString(R.string.local_exec_uninstall),
            color = BrewRed,
        ) {
            BrewDialogContent {
                Text(
                    text = ctx.getString(R.string.local_exec_uninstall_confirm),
                    color = BrewText,
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                )
            }
            BrewDialogActions {
                BrewDialogButton(
                    text = ctx.getString(R.string.cancel),
                    onClick = { showUninstallConfirm = false },
                )
                Spacer(modifier = Modifier.width(12.dp))
                BrewDialogButton(
                    text = ctx.getString(R.string.delete),
                    color = BrewRed,
                    onClick = {
                        showUninstallConfirm = false
                        scope.launch {
                            task = Task.UNINSTALL
                            errorMsg = null
                            report = null
                            val r = withContext(Dispatchers.IO) { ProotInstaller.uninstall(ctx) }
                            if (!r.ok) {
                                errorMsg = ctx.getString(R.string.local_exec_uninstall_failed, r.summary())
                            }
                            status = readStatus(ctx)
                            task = Task.NONE
                        }
                    },
                )
            }
        }
    }
}

// ═══════════════════ 页面小组件 ═══════════════════

/** 统一的面板容器：BrewPanel 底 + 细边框 + 12dp 圆角，可选整卡点击 */
@Composable
private fun PanelCard(
    clickable: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(BrewShapeStandard)
            .background(BrewPanel)
            .border(1.dp, BrewBorder, BrewShapeStandard)
            .let { if (clickable && onClick != null) it.clickable(onClick = onClick) else it }
            .padding(16.dp),
        content = content,
    )
}

/**
 * 一张「按需组件」卡：图标 + 名称 + （就绪说明 / 安装进度）+ 尾部动作。
 *
 * 三个组件（Python / Node.js / Git）共用同一份布局 —— 它们只有图标、文案与安装方式不同，
 * 抄三遍必然有一份漏改。
 *
 * @param progress 非空 = "这个组件正在装"；[AddonProgress.percent] 为 -1 时用不定条
 * @param onCancel 非空时"安装中"显示「取消」。只有 Node.js 给 —— 它是 58 MB 下载，
 *   而 apt 那条路径（[ProotShell.installPackages]）没有取消能力，给了按钮也只是错觉。
 */
@Composable
private fun AddonCard(
    icon: ImageVector,
    title: String,
    hint: String,
    ready: Boolean,
    progress: AddonProgress?,
    enabled: Boolean,
    onInstall: () -> Unit,
    onCancel: (() -> Unit)? = null,
) {
    val ctx = LocalContext.current
    PanelCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LeadingIcon(icon, BrewTeal)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = BrewTextBright, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(3.dp))
                if (progress != null) {
                    Text(progress.label, color = BrewTeal, fontSize = 12.sp)
                    Spacer(Modifier.height(6.dp))
                    if (progress.percent in 0..100) {
                        LinearProgressIndicator(
                            progress = { progress.percent / 100f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(5.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = BrewTeal,
                            trackColor = BrewBorder,
                        )
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(5.dp)
                                .clip(RoundedCornerShape(3.dp)),
                            color = BrewTeal,
                            trackColor = BrewBorder,
                        )
                    }
                    if (progress.detail.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(progress.detail, color = BrewMuted, fontSize = 11.sp)
                    }
                } else {
                    Text(hint, color = BrewMuted, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.width(10.dp))
            when {
                progress != null && onCancel != null -> BrewCompactButton(
                    text = ctx.getString(R.string.local_exec_cancel),
                    color = BrewAmber,
                    onClick = onCancel,
                )
                progress != null -> CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = BrewTeal,
                )
                ready -> Icon(
                    Icons.Outlined.CheckCircle,
                    contentDescription = null,
                    tint = BrewSuccess,
                    modifier = Modifier.size(22.dp),
                )
                else -> BrewCompactButton(
                    text = ctx.getString(R.string.local_exec_addon_install),
                    color = BrewTeal,
                    enabled = enabled,
                    onClick = onInstall,
                )
            }
        }
    }
}

/** 分区小标题（大写宽字距） */
@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        color = BrewMuted,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 2.sp,
    )
}

/** 圆形浅底图标 */
@Composable
private fun LeadingIcon(icon: ImageVector, tint: androidx.compose.ui.graphics.Color) {
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(tint.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
    }
}

/** 大图标 + 标题 + 说明（安装引导卡头部） */
@Composable
private fun CardHeader(icon: ImageVector, tint: androidx.compose.ui.graphics.Color, title: String, subtitle: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        LeadingIcon(icon, tint)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, color = BrewTextBright, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(3.dp))
            Text(subtitle, color = BrewMuted, fontSize = 12.sp)
        }
    }
}

/** rootfs 安装中的进度区块：阶段名 + 百分比/不定条 + 取消 */
@Composable
private fun EnvInstallProgress(stageLabel: String, percent: Int, onCancel: () -> Unit) {
    val ctx = LocalContext.current
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stageLabel, color = BrewTeal, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            if (percent in 0..100) {
                Text("$percent%", color = BrewMuted, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(12.dp))
        if (percent in 0..100) {
            LinearProgressIndicator(
                progress = { percent / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = BrewTeal,
                trackColor = BrewBorder,
            )
        } else {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = BrewTeal,
                trackColor = BrewBorder,
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                ctx.getString(R.string.local_exec_install_cancel_hint),
                color = BrewDim,
                fontSize = 11.sp,
                modifier = Modifier.weight(1f),
            )
            BrewCompactButton(
                text = ctx.getString(R.string.local_exec_cancel),
                color = BrewAmber,
                onClick = onCancel,
            )
        }
    }
}

// ═══════════════════ 状态快照 ═══════════════════

/**
 * 页面上要显示的一切事实，一次性读完（避免"版本已更新、占用还是旧的"中间态）。
 */
private data class StatusSnapshot(
    val installed: Boolean,
    val version: String?,
    val usageBytes: Long,
    val freeBytes: Long,
    val pythonReady: Boolean,
    val nodeReady: Boolean = false,
    val gitReady: Boolean = false,
    val sharePublic: Boolean = false,
    val accessOn: Boolean = false,
) {
    /** 共享文件夹卡是否可点「开通」（Android 11+ 且尚未直通） */
    val shareCanOpen: Boolean
        get() = installed && !sharePublic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    companion object {
        val EMPTY = StatusSnapshot(false, null, 0L, 0L, false)
    }
}

/**
 * 读一次完整状态（IO 线程）。
 *
 * ⚠️ 三个组件的就绪判定**合成一条命令**（[ProotShell.probeCommands]）：每次进 guest 都是一次
 * proot 启动，逐个探测要起三次。apt / 下载的中间态（解包完成但链接未建立）只能靠真跑一次分辨，
 * 所以必须真探测，不能只看文件在不在。未装 rootfs 时不跑。
 */
private suspend fun readStatus(ctx: Context): StatusSnapshot = withContext(Dispatchers.IO) {
    val installed = ProotInstaller.isInstalled(ctx)
    val share = ProotShell.shareStatus(ctx)
    val probe = if (installed) ProotShell.probeCommands(ctx, listOf("python3", "node", "git")) else emptyMap()
    StatusSnapshot(
        installed = installed,
        version = if (installed) ProotInstaller.installedVersion(ctx) else null,
        usageBytes = if (installed) ProotInstaller.diskUsageBytes(ctx) else 0L,
        freeBytes = ProotInstaller.freeBytes(ctx.filesDir),
        pythonReady = probe["python3"] == true,
        nodeReady = probe["node"] == true,
        gitReady = probe["git"] == true,
        sharePublic = share.publicDownload,
        accessOn = LabAccessibility.isEnabled(ctx),
    )
}

// ═══════════════════ 文案与小工具 ═══════════════════

/** rootfs 安装阶段 → 可读文案（`percent < 0` 表示该阶段无法给百分比） */
private fun stageLabel(ctx: Context, stage: ProotInstaller.Stage, percent: Int): String = when (stage) {
    ProotInstaller.Stage.CHECK -> ctx.getString(R.string.local_exec_stage_check)
    ProotInstaller.Stage.DOWNLOAD ->
        ctx.getString(R.string.local_exec_stage_download, percent.coerceAtLeast(0))
    ProotInstaller.Stage.EXTRACT -> ctx.getString(R.string.local_exec_stage_extract)
    ProotInstaller.Stage.CONFIGURE -> ctx.getString(R.string.local_exec_stage_configure)
    ProotInstaller.Stage.DONE -> ctx.getString(R.string.local_exec_stage_done)
}

/** apt 阶段 → 可读文案（百分比在进度条上、下载量由 [AddonProgress.detail] 单独展示，这里只给阶段名） */
private fun aptPhaseLabel(ctx: Context, p: ProotShell.AptProgress?): String = when (p?.phase) {
    ProotShell.AptProgress.Phase.UPDATE -> ctx.getString(R.string.local_exec_apt_update)
    ProotShell.AptProgress.Phase.DEPS -> ctx.getString(R.string.local_exec_apt_deps)
    ProotShell.AptProgress.Phase.FETCH -> ctx.getString(R.string.local_exec_apt_fetch)
    ProotShell.AptProgress.Phase.UNPACK -> ctx.getString(R.string.local_exec_apt_unpack)
    ProotShell.AptProgress.Phase.SETUP -> ctx.getString(R.string.local_exec_apt_setup)
    null -> ctx.getString(R.string.local_exec_apt_update)
}

/** 字节 → 人类可读（只用于展示占用，不做精确换算） */
private fun formatSize(bytes: Long): String = when {
    bytes <= 0L -> "0 MB"
    bytes < 1024L * 1024L -> "${bytes / 1024L} KB"
    else -> "${bytes / 1024L / 1024L} MB"
}

/**
 * 执行结果 → 给用户看的文本：空的段落省掉，末尾带退出码。
 */
private fun renderExec(r: ExecResult): String {
    if (!r.launched) return "未能启动：${r.failure}"
    val sb = StringBuilder()
    if (r.stdout.isNotBlank()) sb.append(r.stdout.trim())
    if (r.stderr.isNotBlank()) {
        if (sb.isNotEmpty()) sb.append("\n")
        sb.append(r.stderr.trim())
    }
    if (sb.isEmpty()) sb.append("（没有输出）")
    sb.append("\n\n退出码 ").append(r.code)
    return sb.toString()
}
