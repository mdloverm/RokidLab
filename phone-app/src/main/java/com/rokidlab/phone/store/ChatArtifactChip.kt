package com.rokidlab.phone.store

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.rokidlab.phone.R
import com.rokidlab.phone.ai.AgentStep
import com.rokidlab.phone.design.BrewAmber
import com.rokidlab.phone.design.BrewBorder
import com.rokidlab.phone.design.BrewChat
import com.rokidlab.phone.design.BrewRed
import com.rokidlab.phone.design.BrewDim
import com.rokidlab.phone.design.BrewMuted
import com.rokidlab.phone.design.BrewPanel
import com.rokidlab.phone.design.BrewSuccess
import com.rokidlab.phone.design.BrewText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 「过程」面板里的**文件产物芯片**（2026-10-10）。
 *
 * ## 解决的问题
 * 改造前，一条文件类工具调用（`apply_patch` / `edit_text_file` / `save_code_file`…）在过程面板上
 * 只有一行"执行修改文件…"，"改了哪个文件"埋在 [AgentStep.detail] 那段
 * `参数 → 结果` 的拍平字符串里，且参数里往往还塞着完整 diff / 源码正文 ——
 * **`file` 字段在 120 字符内就被顶掉了**（见 `AgentStep.artifactOf` 的注释）。
 * 用户想核对"到底动了哪个文件"只能离开聊天页去文件管理器翻。
 *
 * ## 为什么内联在过程里，而不是再另起一条文件卡气泡
 * 一轮的**过程**和它**产出的东西**应当出现在同一个可折叠区块里。
 * 另起气泡（改造前 `ChatFileHolder.addFile` 的做法）会让文件卡看起来凭空出现，
 * 而且一次任务产出多个文件时，"一个带过程卡的气泡 + 一堆无过程卡的文件气泡"是割裂的。
 *
 * ## 交互（2026-10-10 用户审核确认的形态，2026-10-10 二次收敛）
 * 每个产物**独占一行**：行首是**操作方式**（读取 / 修改 / 新建 / 删除…），行尾是
 * **展示名**（`项目/相对路径`，等宽感、下划线、超长省略），整行可点弹出预览框。
 * ⚠️ 行尾一度显示**完整绝对路径**，真机上暴露两个问题（用户："过程画面太乱了"）：
 * ① 那是**应用私有镜像**路径（`/data/user/0/…/files/aiui_projects/…`），用户既找不到也没意义；
 * ② 长路径逐行折成 3 行，三个文件就是 9 行，卡片被撑爆。
 * ⇒ 改为 `项目/相对路径`（一行读得完、归属清晰），完整路径仍可通过点开预览框查看内容定位。
 * 预览**懒读 + 封顶** —— 与 [ChatFileCard] 同一套纪律：点开一个几万行的日志不该把界面撑爆。
 * 文件已不在时**如实说**（[R.string.chat_file_missing]），而不是给一个空白框。
 *
 * 为什么不横滑药丸（前一版）：路径被截断 + 要横滑才看得到，"操作方式在哪、动了哪个文件"
 * 一眼读不全；改逐行后长度交给自动换行，两端对齐即可扫读。
 */
@Composable
internal fun ArtifactChips(artifacts: List<AgentStep.Artifact>, modifier: Modifier = Modifier) {
    if (artifacts.isEmpty()) return
    var preview by remember { mutableStateOf<AgentStep.Artifact?>(null) }

    Column(
        modifier = modifier.padding(top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        artifacts.forEach { a ->
            ArtifactRow(a) { preview = a }
        }
    }

    preview?.let { target ->
        ArtifactPreviewDialog(
            artifact = target,
            onClose = { preview = null },
        )
    }
}

/**
 * 操作方式与文件展示名之间的间距。
 *
 * ⚠️ 操作方式**不再用定宽列**（一度是 52dp，为对齐英文最长的 "Created"）：中文只有两字
 * （"读取"/"修改"）时，定宽列在动作词与文件名之间留下近 40dp 空白 ——
 * 用户直接反馈"读取修改 这些离文件路径太远了"。改为随内容宽度、只留一个小间距；
 * 代价是各行文件名不再左对齐（信息密度优先，对齐与否不影响扫读）。
 */
private val ARTIFACT_ACTION_GAP = 6.dp

/**
 * 单行产物：**操作方式 + 展示名（下划线可点、超长省略）**。
 *
 * 展示名用 [AgentStep.Artifact.label]（`项目/相对路径`，如 `html-test/index.html`），
 * **不是** [AgentStep.Artifact.path]（应用私有镜像的绝对路径，`/data/user/0/…`）：
 * 后者既不是用户能去找的位置，又长到逐行折 3 行，三个文件就把卡片撑爆（用户："太乱了"）。
 * 完整路径/内容交给点开的预览框，行内只保留"是哪个项目的哪个文件"这一条能一眼扫读的信息。
 */
@Composable
private fun ArtifactRow(a: AgentStep.Artifact, onClick: () -> Unit) {
    val tint = when (a.action) {
        AgentStep.Action.READ -> BrewMuted
        AgentStep.Action.WRITE -> BrewAmber
        AgentStep.Action.CREATE -> BrewSuccess
        AgentStep.Action.DELETE -> BrewRed
        AgentStep.Action.MOVE -> BrewAmber
        AgentStep.Action.TOUCH -> BrewDim
    }
    val actionLabel = stringResource(
        when (a.action) {
            AgentStep.Action.READ -> R.string.chat_trace_artifact_read
            AgentStep.Action.WRITE -> R.string.chat_trace_artifact_write
            AgentStep.Action.CREATE -> R.string.chat_trace_artifact_create
            AgentStep.Action.DELETE -> R.string.chat_trace_artifact_delete
            AgentStep.Action.MOVE -> R.string.chat_trace_artifact_move
            AgentStep.Action.TOUCH -> R.string.chat_trace_artifact_touch
        }
    )
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 2.dp),
    ) {
        Text(
            text = actionLabel,
            color = tint,
            fontSize = 10.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
        Spacer(Modifier.width(ARTIFACT_ACTION_GAP))
        Text(
            text = a.label,
            color = BrewChat,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            // ★ 下划线 = "这是能点的链接"的通用 affordance（用户明确要求）
            textDecoration = TextDecoration.Underline,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/** 预览框最多渲染这么多行（再多就提示用户去文件管理器看） */
private const val PREVIEW_MAX_LINES = 400

/**
 * 文件产物预览框。
 *
 * 与 [ChatFileCard] 的**展开态**同一套纪律：懒读（后台线程）、限行、按需滚动。
 * 媒体文件不在这里处理 —— 芯片只在过程面板出现，音视频预览走对话区的
 * [ChatMediaCard]（那需要 `VideoView` 等播放器依赖，塞进 Dialog 不合适）。
 * 遇到媒体扩展名时如实说明不可在此预览，而不是显示一堆乱码。
 */
@Composable
private fun ArtifactPreviewDialog(
    artifact: AgentStep.Artifact,
    onClose: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    var state by remember(artifact.path) { mutableStateOf<PreviewState>(PreviewState.Loading) }

    LaunchedEffect(artifact.path) {
        state = withContext(Dispatchers.IO) { readPreview(artifact.path) }
    }

    Dialog(onDismissRequest = onClose) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 460.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(BrewPanel)
                .border(1.dp, BrewBorder, RoundedCornerShape(16.dp))
                .padding(16.dp),
        ) {
            // ── 标题栏：类型标签 + 文件名 + 关闭 ──
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.Description,
                    contentDescription = null,
                    tint = BrewMuted,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.chat_artifact_preview_title),
                        color = BrewMuted,
                        fontSize = 11.sp,
                    )
                    Text(
                        text = artifact.label,
                        color = BrewText,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(R.string.chat_artifact_close),
                    tint = BrewMuted,
                    modifier = Modifier
                        .size(20.dp)
                        .clickable(onClick = onClose),
                )
            }
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(BrewBorder),
            )
            Spacer(Modifier.height(10.dp))

            when (val s = state) {
                is PreviewState.Loading -> Text(
                    text = stringResource(R.string.chat_file_reading),
                    color = BrewDim,
                    fontSize = 12.sp,
                )

                is PreviewState.Unsupported -> Text(
                    text = when (s.reason) {
                        PreviewState.UnsupReason.MEDIA ->
                            stringResource(R.string.chat_artifact_unsupported_media, s.arg)

                        PreviewState.UnsupReason.TOO_LARGE ->
                            stringResource(R.string.chat_artifact_unsupported_large, s.arg)

                        PreviewState.UnsupReason.NOT_TEXT ->
                            stringResource(R.string.chat_artifact_unsupported_binary)

                        PreviewState.UnsupReason.DIR ->
                            stringResource(R.string.chat_artifact_unsupported_dir)
                    },
                    color = BrewDim,
                    fontSize = 12.sp,
                )

                is PreviewState.Missing -> Text(
                    text = stringResource(R.string.chat_file_missing),
                    color = BrewMuted,
                    fontSize = 12.sp,
                )

                is PreviewState.Ready -> Column {
                    Text(
                        text = when {
                            s.directory -> stringResource(R.string.chat_artifact_dir_entries, s.lines)
                            s.truncated -> stringResource(R.string.chat_artifact_lines_total, s.lines)
                            else -> stringResource(
                                R.string.chat_artifact_lines_chars, s.lines, s.chars,
                            )
                        },
                        color = BrewDim,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(8.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f, fill = false)
                            .heightIn(max = 320.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        Text(
                            text = s.body,
                            color = BrewText,
                            fontSize = 12.sp,
                            lineHeight = 17.sp,
                        )
                    }
                    if (s.truncated) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = stringResource(
                                R.string.chat_artifact_too_large,
                                PREVIEW_MAX_LINES,
                            ),
                            color = BrewDim,
                            fontSize = 10.sp,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = {
                            clipboard.setText(AnnotatedString(s.body))
                        }) {
                            Text(
                                text = stringResource(R.string.chat_file_copy),
                                color = BrewMuted,
                                fontSize = 12.sp,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 预览读取结果（四态：**别把"读不出来"和"文件不存在"混成一句**）。
 *
 * ⚠️ 本类型只带**数据**，不带用户可见文案 —— 文案由弹窗层用 `stringResource` 本地化
 *（多语种规则：纯 JVM 的 [readPreview] 拿不到 Context，所以理由用枚举给，不写死字符串）。
 */
internal sealed interface PreviewState {
    data object Loading : PreviewState

    /** 不可预览的原因（决定弹窗用哪条语言包文案） */
    enum class UnsupReason {
        /** 媒体/二进制扩展名 */
        MEDIA,

        /** 体积过大 */
        TOO_LARGE,

        /** 读出来不是合法文本 */
        NOT_TEXT,

        /** 目录读不了（权限/IO） */
        DIR,
    }

    /** 文件在，但这一层不做预览；[arg] 是填进文案的参数（扩展名 / KB 数） */
    data class Unsupported(val reason: UnsupReason, val arg: String = "") : PreviewState

    data object Missing : PreviewState

    data class Ready(
        val body: String,

        /** 文件行数；[directory] = true 时是条目数 */
        val lines: Int,

        /** 正文字符数（目录为 0，不展示） */
        val chars: Int,
        val truncated: Boolean,

        /** 产物是目录（list_files / search_files 常指向目录） */
        val directory: Boolean,
    ) : PreviewState
}

/** 媒体与二进制扩展名：文本预览毫无意义（解出来是乱码），如实说"这里不预览" */
private val MEDIA_EXT = setOf(
    "mp4", "webm", "mkv", "mov", "3gp", "avi",
    "mp3", "wav", "m4a", "aac", "ogg", "flac", "opus",
    "png", "jpg", "jpeg", "gif", "webp", "bmp", "heic",
    "zip", "apk", "aix", "so", "ttf", "otf", "woff", "woff2", "pdf",
)

/**
 * 读文件预览（**纯逻辑**，可单测；不含 Android 依赖）。
 *
 * ⚠️ 读**整个**文件再截行：与 [ChatFileCard] 同一个取舍。
 * 换来的好处是能给出"共 N 行、已显示前 400 行"这种**如实**的说明；
 * 而按字节截断会切在多字节字符中间（Compose 渲染成方块）。
 * 文件大小超过 [PREVIEW_MAX_BYTES] 时直接判为不可预览 —— 那说明它本来就不该用文本看。
 */
internal fun readPreview(path: String): PreviewState {
    val f = File(path)
    if (!f.exists()) return PreviewState.Missing
    // 目录（list_files / search_files 的产物常是目录）：列条目，而不是报"文件已不在"
    if (f.isDirectory) {
        val kids = runCatching { f.list()?.sorted() }.getOrNull()
            ?: return PreviewState.Unsupported(PreviewState.UnsupReason.DIR)
        return PreviewState.Ready(
            body = kids.joinToString("\n"),
            lines = kids.size,
            chars = 0,
            truncated = kids.size > PREVIEW_MAX_LINES,
            directory = true,
        )
    }
    if (!f.isFile) return PreviewState.Missing
    val ext = path.substringAfterLast('.', "").lowercase()
    if (ext in MEDIA_EXT) {
        return PreviewState.Unsupported(PreviewState.UnsupReason.MEDIA, ext)
    }
    if (f.length() > PREVIEW_MAX_BYTES) {
        return PreviewState.Unsupported(
            PreviewState.UnsupReason.TOO_LARGE,
            (f.length() / 1024).toString(),
        )
    }
    val text = runCatching { f.readText() }.getOrElse {
        return PreviewState.Unsupported(PreviewState.UnsupReason.NOT_TEXT)
    }
    val allLines = text.lines()
    val shown = allLines.take(PREVIEW_MAX_LINES)
    return PreviewState.Ready(
        body = shown.joinToString("\n"),
        lines = allLines.size,
        chars = text.length,
        truncated = allLines.size > PREVIEW_MAX_LINES,
        directory = false,
    )
}

/** 预览的体积上限：超过这个体量它就不是"看一眼"的对象了 */
private const val PREVIEW_MAX_BYTES = 2L * 1024 * 1024