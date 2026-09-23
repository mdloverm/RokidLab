package com.rokidlab.phone.store

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Base64
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DataObject
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rokidlab.phone.R
import com.rokidlab.phone.design.BrewBorder
import com.rokidlab.phone.design.BrewChat
import com.rokidlab.phone.design.BrewDim
import com.rokidlab.phone.design.BrewMuted
import com.rokidlab.phone.design.BrewPanel
import com.rokidlab.phone.design.BrewPanelAlt
import com.rokidlab.phone.design.BrewRed
import com.rokidlab.phone.design.BrewTextBright
import com.rokidlab.phone.platform.ExecResult
import com.rokidlab.phone.platform.ProotInstaller
import com.rokidlab.phone.platform.ProotShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 代码块卡片：统一的标题栏 + 等宽代码区 + 内嵌运行结果。
 *
 * ## 结构（与图片卡同一套语言）
 * - 标题栏左侧：代码图标 + 语言标签（`PYTHON` / `BASH` …，围栏未写语言则 `TEXT`）；
 * - 标题栏右侧：**纯图标**功能键 —— 运行（仅可运行语言）/ 下载 / 复制；
 * - 下方代码区：不自动换行（横向可滚）+ 整块纵向限高滚动，等宽字体；
 * - 点「运行」后结果**就地展开在卡片内**（不弹 Dialog）：退出码 + stdout/stderr，
 *   同卡内闭环，用户视线不用跳走。
 *
 * ## 「运行」的边界
 * 跑在 [ProotShell] 的 Ubuntu 容器里，所以**未装本机执行环境时运行键直接给引导**，
 * 不触发下载；bash 直接执行，python3/node 经 base64 管道喂解释器
 * （不拼引号、不怕代码里出现 heredoc 分隔符，杜绝脚本注入）。
 */
@Composable
internal fun CodeBlockCard(langRaw: String, code: String) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val lang = langRaw.trim().substringBefore(' ').lowercase()
    val label = lang.ifBlank { stringResource(R.string.chat_code_lang_default) }.uppercase(Locale.US)
    val runnable = CodeLanguage.support[lang]
    val fileKind = remember(lang) { CodeLanguage.fileKind(lang) }

    var running by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<ExecResult?>(null) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(BrewPanel)
            .border(1.dp, BrewBorder, RoundedCornerShape(10.dp)),
    ) {
        // ── 标题栏（聊天主题色低透明底，不再用硬编码黑）──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(34.dp)
                .background(BrewChat.copy(alpha = 0.12f))
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.DataObject,
                contentDescription = null,
                tint = BrewChat,
                modifier = Modifier.size(15.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = label,
                color = BrewChat,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.8.sp,
            )
            Spacer(Modifier.weight(1f))
            if (runnable != null) {
                CardIconButton(
                    icon = Icons.Outlined.PlayArrow,
                    description = stringResource(R.string.chat_code_run),
                    tint = BrewChat,
                    enabled = !running,
                ) {
                    if (!ProotInstaller.isInstalled(ctx)) {
                        Toast.makeText(ctx, R.string.chat_code_env_missing, Toast.LENGTH_LONG).show()
                        return@CardIconButton
                    }
                    running = true
                    result = null
                    scope.launch(Dispatchers.IO) {
                        val r = when (runnable) {
                            CodeLanguage.Runnable.SHELL ->
                                ProotShell.runCommand(ctx, code, timeoutSec = 60L)

                            CodeLanguage.Runnable.PYTHON ->
                                ProotShell.runScript(
                                    ctx,
                                    interpreterScript(ctx, "python3", R.string.chat_code_python_missing, code),
                                    timeoutSec = 120L,
                                )

                            CodeLanguage.Runnable.NODE ->
                                ProotShell.runScript(
                                    ctx,
                                    interpreterScript(ctx, "node", R.string.chat_code_node_missing, code),
                                    timeoutSec = 120L,
                                )
                        }
                        withContext(Dispatchers.Main) {
                            running = false
                            result = r
                        }
                    }
                }
            }
            CardIconButton(
                icon = Icons.Outlined.Download,
                description = stringResource(R.string.chat_code_download),
                tint = BrewMuted,
            ) {
                saveCodeFile(ctx, scope, fileKind, code)
            }
            CardIconButton(
                icon = Icons.Outlined.ContentCopy,
                description = stringResource(R.string.chat_code_copy),
                tint = BrewMuted,
            ) {
                copyToClipboard(ctx, code)
            }
        }

        // 顶栏与代码区之间的分隔线
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(BrewBorder),
        )

        // ── 代码区：横向不换行 + 纵向限高 ──
        val hScroll = rememberScrollState()
        val vScroll = rememberScrollState()
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 240.dp)
                .horizontalScroll(hScroll)
                .verticalScroll(vScroll)
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Text(
                text = code,
                color = BrewTextBright,
                fontSize = 12.5.sp,
                lineHeight = 18.sp,
                fontFamily = FontFamily.Monospace,
                softWrap = false,
            )
        }

        // ── 运行结果（就地展开） ──
        if (running || result != null) {
            CodeResultPanel(running = running, result = result, onClear = { result = null })
        }
    }
}

/** 标题栏上的 30dp 图标按钮 */
@Composable
private fun CardIconButton(
    icon: ImageVector,
    description: String,
    tint: Color,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .padding(start = 2.dp)
            .size(30.dp)
            .clip(RoundedCornerShape(8.dp))
            .let { m -> if (enabled) m.clickable(onClick = onClick) else m },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = if (enabled) tint else tint.copy(alpha = 0.35f),
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun CodeResultPanel(running: Boolean, result: ExecResult?, onClear: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(BrewPanelAlt)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (running) {
                CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp, color = BrewChat)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.chat_code_running), color = BrewMuted, fontSize = 11.sp)
            } else {
                val r = result!!
                val ok = r.launched && r.code == 0
                Icon(
                    imageVector = if (ok) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline,
                    contentDescription = null,
                    tint = if (ok) BrewChat else BrewRed,
                    modifier = Modifier.size(13.dp),
                )
                Spacer(Modifier.width(5.dp))
                Text(
                    text = if (r.launched) {
                        stringResource(R.string.chat_code_exit, r.code)
                    } else {
                        stringResource(R.string.chat_code_launch_failed, r.failure.orEmpty())
                    },
                    color = BrewMuted,
                    fontSize = 11.sp,
                    maxLines = 2,
                    modifier = Modifier.weight(1f),
                )
                CardIconButton(
                    icon = Icons.Outlined.Close,
                    description = stringResource(R.string.chat_code_clear),
                    tint = BrewDim,
                    onClick = onClear,
                )
            }
        }

        val r = result
        if (!running && r != null && r.launched) {
            val outScroll = rememberScrollState()
            Box(
                modifier = Modifier
                    .padding(top = 6.dp)
                    .fillMaxWidth()
                    .heightIn(max = 150.dp)
                    .verticalScroll(outScroll),
            ) {
                Column {
                    if (r.stdout.isNotBlank()) {
                        ResultStreamLabel(stringResource(R.string.chat_code_stdout))
                        Text(
                            text = r.stdout,
                            color = BrewTextBright,
                            fontSize = 11.5.sp,
                            lineHeight = 16.sp,
                            fontFamily = FontFamily.Monospace,
                            softWrap = true,
                        )
                    }
                    if (r.stderr.isNotBlank()) {
                        ResultStreamLabel(stringResource(R.string.chat_code_stderr))
                        Text(
                            text = r.stderr,
                            color = BrewRed.copy(alpha = 0.9f),
                            fontSize = 11.5.sp,
                            lineHeight = 16.sp,
                            fontFamily = FontFamily.Monospace,
                            softWrap = true,
                        )
                    }
                    if (r.stdout.isBlank() && r.stderr.isBlank()) {
                        Text(
                            text = stringResource(R.string.chat_code_no_output),
                            color = BrewDim,
                            fontSize = 11.sp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultStreamLabel(text: String) {
    Text(
        text = text,
        color = BrewDim,
        fontSize = 10.sp,
        letterSpacing = 0.6.sp,
        modifier = Modifier.padding(bottom = 2.dp),
    )
}

// ═══════════════════ 动作：复制 / 下载 / 运行 ═══════════════════

internal fun copyToClipboard(ctx: Context, text: String) {
    runCatching {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText("leqi-code", text))
        Toast.makeText(ctx, R.string.chat_msg_copied, Toast.LENGTH_SHORT).show()
    }
}

private fun saveCodeFile(ctx: Context, scope: kotlinx.coroutines.CoroutineScope, kind: CodeLanguage.FileKind, code: String) {
    scope.launch(Dispatchers.IO) {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val fileName = "leqi-${kind.namePrefix}-$stamp${kind.ext}"
        val outcome = runCatching {
            ChatMediaSaver.saveTextToDownloads(ctx, fileName, kind.mime, code)
        }.fold(
            onSuccess = { ctx.getString(R.string.chat_code_saved, it) },
            onFailure = { ctx.getString(R.string.chat_code_save_failed, it.message ?: it.javaClass.simpleName) },
        )
        withContext(Dispatchers.Main) { Toast.makeText(ctx, outcome, Toast.LENGTH_SHORT).show() }
    }
}

/**
 * 生成解释器脚本：先探测解释器在不在，再用 base64 管道把代码喂进去。
 *
 * 为什么不用 `python3 -c '...'` / heredoc：代码本身可能含引号、`$`、heredoc 分隔符，
 * 拼进 shell 脚本就是注入面；base64 后载荷是纯安全字符，容器里 base64 属于 coreutils 必有。
 * 脚本经 [ProotShell.runScript] 先落盘再以 bash 执行，CRLF 已在那一层统一清理。
 */
private fun interpreterScript(ctx: Context, interpreter: String, missingMsgRes: Int, code: String): String {
    val missing = ctx.getString(missingMsgRes).replace("'", "").replace("\n", " ")
    val b64 = Base64.encodeToString(code.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    return """
        command -v $interpreter >/dev/null 2>&1 || { echo '$missing'; exit 91; }
        echo '$b64' | base64 -d | $interpreter
    """.trimIndent() + "\n"
}

/** 语言 → 能力（可否运行）与落盘文件名（扩展名/MIME）映射。 */
internal object CodeLanguage {
    enum class Runnable { SHELL, PYTHON, NODE }

    data class FileKind(val namePrefix: String, val ext: String, val mime: String)

    val support: Map<String, Runnable> = mapOf(
        "bash" to Runnable.SHELL,
        "sh" to Runnable.SHELL,
        "shell" to Runnable.SHELL,
        "zsh" to Runnable.SHELL,
        "python" to Runnable.PYTHON,
        "python3" to Runnable.PYTHON,
        "py" to Runnable.PYTHON,
        "javascript" to Runnable.NODE,
        "js" to Runnable.NODE,
        "node" to Runnable.NODE,
    )

    fun fileKind(lang: String): FileKind = when (lang) {
        "python", "python3", "py" -> FileKind("python", ".py", "text/x-python")
        "bash", "sh", "shell", "zsh" -> FileKind("shell", ".sh", "application/x-sh")
        "javascript", "js", "node" -> FileKind("javascript", ".js", "text/javascript")
        "json" -> FileKind("json", ".json", "application/json")
        "kotlin", "kt" -> FileKind("kotlin", ".kt", "text/plain")
        "java" -> FileKind("java", ".java", "text/plain")
        "c" -> FileKind("c", ".c", "text/plain")
        "cpp", "c++" -> FileKind("cpp", ".cpp", "text/plain")
        "go" -> FileKind("go", ".go", "text/plain")
        "rust", "rs" -> FileKind("rust", ".rs", "text/plain")
        "sql" -> FileKind("sql", ".sql", "text/plain")
        "xml" -> FileKind("xml", ".xml", "text/xml")
        "html" -> FileKind("html", ".html", "text/html")
        "css" -> FileKind("css", ".css", "text/css")
        "yaml", "yml" -> FileKind("yaml", ".yml", "text/yaml")
        "markdown", "md" -> FileKind("markdown", ".md", "text/markdown")
        else -> FileKind("text", ".txt", "text/plain")
    }
}
