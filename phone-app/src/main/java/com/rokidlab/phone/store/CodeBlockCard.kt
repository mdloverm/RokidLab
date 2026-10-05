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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DataObject
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
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
    // ── 交互式运行：寿命归 InteractiveRunManager（全局）──
    // 卡片滚出列表被回收**不再杀进程**（此前 DisposableEffect stop 的限制已废除）；
    // 输出在聊天流里同步有一张"运行卡片"（addRunCard），聊天输入可经程序模式直发 stdin。
    var runId by remember { mutableStateOf<String?>(null) }
    val runsState = com.rokidlab.phone.platform.InteractiveRunManager.runs.collectAsState()
    val liveRun = runId?.let { id -> runsState.value.firstOrNull { it.id == id } }
    val liveOutput = liveRun?.output?.collectAsState()?.value ?: ""

    fun startRun(script: String, label: String) {
        running = true
        result = null
        var rid: String? = null
        scope.launch(Dispatchers.IO) {
            val rs = com.rokidlab.phone.platform.InteractiveRunManager.start(
                ctx,
                script,
                label,
            ) { r, finishedRun ->
                scope.launch(Dispatchers.Main) {
                    running = false
                    result = r
                    // 运行卡片定格：把最终输出写进消息（Run 已从管理器移除，UI 读不到流了）
                    rid?.let { id ->
                        val body = finishedRun?.output?.value ?: ""
                        val tail = r.failure ?: "退出码 ${r.code}"
                        ChatStateHolder.finishRunCard(id, body, tail)
                    }
                }
            }
            rid = rs?.id
            withContext(Dispatchers.Main) {
                if (rs != null) {
                    ChatStateHolder.addRunCard(rs.id, label)
                    runId = rs.id
                } else {
                    running = false
                }
            }
        }
    }

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
                    val script = buildWebAppScript(ctx, runnable, code)
                    when (decideRunPlan(lang, code)) {
                        CodeRunPlan.BLOCKED_GUI -> {
                            running = false
                            result = ExecResult(-1, "", ctx.getString(R.string.chat_code_gui_unsupported))
                        }
                        CodeRunPlan.WEB_PREVIEW ->
                            startAsWebPreview(ctx, scope, script) { running = false; result = it }
                        CodeRunPlan.NORMAL -> startRun(script, label)
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

        // ── 运行结果（就地展开；输出同时有聊天流运行卡片，输入走聊天框"程序模式"） ──
        if (running || result != null) {
            CodeResultPanel(
                running = running,
                result = result,
                liveOutput = liveOutput,
                onStop = { liveRun?.stop() },
                onClear = { result = null },
            )
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
private fun CodeResultPanel(
    running: Boolean,
    result: ExecResult?,
    liveOutput: String,
    onStop: () -> Unit,
    onClear: () -> Unit,
) {
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
                Spacer(Modifier.weight(1f))
                CardIconButton(
                    icon = Icons.Outlined.Stop,
                    description = stringResource(R.string.chat_code_stop),
                    tint = BrewRed,
                    onClick = onStop,
                )
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

        // ── 输出区：运行中显示实时流，结束后显示汇总 ──
        if (running) {
            val outScroll = rememberScrollState()
            // 新输出到达时滚到底部（跟随最新行）
            androidx.compose.runtime.LaunchedEffect(liveOutput) {
                outScroll.animateScrollTo(outScroll.maxValue)
            }
            Box(
                modifier = Modifier
                    .padding(top = 6.dp)
                    .fillMaxWidth()
                    .heightIn(max = 150.dp)
                    .verticalScroll(outScroll),
            ) {
                Text(
                    text = liveOutput.ifBlank { stringResource(R.string.chat_code_no_output) },
                    color = BrewTextBright,
                    fontSize = 11.5.sp,
                    lineHeight = 16.sp,
                    fontFamily = FontFamily.Monospace,
                    softWrap = true,
                )
            }
            // 输入走聊天框的"程序模式"（InteractiveRunManager 联动），卡片内不再有独立输入行
        } else {
            val r = result
            if (r != null && r.launched) {
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

/**
 * 生成 Java 运行脚本：探测 javac → 落盘 → 找 public 类名（用它命名文件，否则 javac 拒编）→
 * 编译 → 运行。
 *
 * 与 [interpreterScript] 同样的理由走 base64 管道（代码里的引号 / `${'$'}` 是注入面）。
 * 类名提取走 grep 启发式：优先 `public (final|abstract)? class`，退而取第一个 class 定义 ——
 * 覆盖模型生成的常规单文件程序足够；找不到类名时如实报错而不是猜。
 * JDK 未预装（可选组件）：缺 javac 时引导去「设置 → 本机执行环境」。
 */
private fun javaScript(ctx: Context, code: String): String {
    val missing = ctx.getString(R.string.chat_code_java_missing).replace("'", "").replace("\n", " ")
    val noClass = ctx.getString(R.string.chat_code_java_noclass).replace("'", "").replace("\n", " ")
    val hint = ctx.getString(R.string.chat_code_java_interactive).replace("'", "").replace("\n", " ")
    val b64 = Base64.encodeToString(code.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    val d = "${'$'}D"
    val cls = "${'$'}CLS"
    val rc = "${'$'}RC"
    return """
        command -v javac >/dev/null 2>&1 || { echo '$missing'; exit 91; }
        D=${'$'}(mktemp -d) || exit 92
        echo '$b64' | base64 -d > $d/Code.java
        cd $d || exit 92
        CLS=${'$'}(grep -oE 'public[[:space:]]+(final[[:space:]]+|abstract[[:space:]]+)?class[[:space:]]+[A-Za-z0-9_]+' Code.java | head -1 | grep -oE '[A-Za-z0-9_]+' | tail -1)
        [ -n "$cls" ] || CLS=${'$'}(grep -oE '(^|[[:space:]])class[[:space:]]+[A-Za-z0-9_]+' Code.java | head -1 | grep -oE '[A-Za-z0-9_]+' | tail -1)
        [ -n "$cls" ] || { echo '$noClass'; exit 93; }
        mv Code.java "$cls.java" 2>/dev/null || true
        javac "$cls.java" || exit 94
        java -cp "$d" "$cls"
        RC=${'$'}?
        [ "$rc" -ne 0 ] && echo '$hint'
        exit "$rc"
    """.trimIndent() + "\n"
}

/**
 * 按语言把代码包成容器可执行脚本 —— 代码卡「运行键」与聊天「说运行」共用这一份封装，
 * 保证两条入口跑出来的行为完全一致（探测缺失组件 / base64 防注入 / Java 类名提取）。
 */
/**
 * 运行分流决策（代码卡与文件卡共用）：同一份代码从哪张卡点运行，走的链路必须一致。
 */
internal enum class CodeRunPlan { BLOCKED_GUI, WEB_PREVIEW, NORMAL }

internal fun decideRunPlan(lang: String, code: String): CodeRunPlan = when {
    // 桌面窗口库（tkinter/pygame/…）：容器无显示服务器，直接拦截给引导
    looksLikeDesktopGui(code) && lang != "html" && lang != "htm" -> CodeRunPlan.BLOCKED_GUI
    // 常驻网页服务（remi/flask/…）与 HTML 页面：普通运行必被看狗杀掉，走预览链路
    looksLikeWebApp(code) || lang == "html" || lang == "htm" -> CodeRunPlan.WEB_PREVIEW
    else -> CodeRunPlan.NORMAL
}

internal fun buildRunScript(ctx: Context, runnable: CodeLanguage.Runnable, code: String): String = when (runnable) {
    CodeLanguage.Runnable.SHELL -> code + "\n"
    CodeLanguage.Runnable.PYTHON ->
        interpreterScript(ctx, "python3", R.string.chat_code_python_missing, code)
    CodeLanguage.Runnable.NODE ->
        interpreterScript(ctx, "node", R.string.chat_code_node_missing, code)
    CodeLanguage.Runnable.JAVA -> javaScript(ctx, code)
    CodeLanguage.Runnable.HTML -> htmlScript(ctx, code)
}

/**
 * HTML 网页脚本：把代码落盘成 index.html，起 python3 静态服务（端口用管理器注入的
 * $PREVIEW_PORT）——浏览器/WebView 对 file:// 私有目录有访问限制，静态服务最稳。
 * 单文件小游戏（2048/贪吃蛇）与静态页都走这条。
 */
private fun htmlScript(ctx: Context, code: String): String {
    val missing = ctx.getString(R.string.chat_code_python_missing).replace("'", "").replace("\n", " ")
    val b64 = Base64.encodeToString(code.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    return """
        command -v python3 >/dev/null 2>&1 || { echo '$missing'; exit 91; }
        mkdir -p /tmp/rl_html_app && echo '$b64' | base64 -d > /tmp/rl_html_app/index.html || exit 92
        exec python3 -m http.server "${'$'}{PREVIEW_PORT:-8080}" --bind 127.0.0.1 --directory /tmp/rl_html_app
    """.trimIndent() + "\n"
}

// ═══════════════════ 网页应用（remi / flask / streamlit…）═══════════════════

/**
 * 嗅探「常驻网页服务型」脚本：这类脚本（remi/flask/streamlit 等）起 HTTP 服务后**永不退出**，
 * 走普通运行会被超时/空闲看狗杀掉，窗口也无处可画——必须经 [WebPreviewManager] 常驻启动、
 * 由聊天页预览条（WebView）打开。关键词取主流 Python web 框架 + 显式端口约定。
 */
internal fun looksLikeWebApp(code: String): Boolean = code.length < 100_000 && WEB_APP_HINT.containsMatchIn(code)

private val WEB_APP_HINT = Regex(
    "(?i)(\\bremi\\b|\\bstreamlit\\b|\\bgradio\\b|\\bflask\\b|\\bfastapi\\b|\\buvicorn\\b" +
        "|\\bbottle\\b|\\btornado\\b|\\baiohttp\\b|\\bdjango\\b|http\\.server|serve_forever|PREVIEW_PORT)",
)

/**
 * 兜底嗅探「桌面窗口库」脚本（tkinter/pygame/PyQt…）：容器没有显示服务器，这些库**必然
 * 开不出窗口**——普通运行只会挂到超时或抛 Display/Video 错。拦截下来直接给引导文案，
 * 提示改用 remi 或 HTML5（AI 侧工具描述已同步此约定）。
 */
internal fun looksLikeDesktopGui(code: String): Boolean =
    code.length < 100_000 && DESKTOP_GUI_HINT.containsMatchIn(code)

private val DESKTOP_GUI_HINT = Regex(
    "(?i)(\\btkinter\\b|\\bpygame\\b|\\bPyQt[456]?\\b|\\bPySide[26]?\\b|\\bkivy\\b|\\bPySimpleGUI\\b|\\bfreegames\\b)",
)

/**
 * 网页应用启动脚本：普通脚本原样透传；**remi 场景注入端口桥接**——用户从网上抄的 demo
 * 几乎都写死 `start(MyApp, port=8081)`，而 [com.rokidlab.phone.platform.WebPreviewManager]
 * 分配的是动态端口并注入 `PREVIEW_PORT` 环境变量，不桥接必端口错位。
 *
 * 桥接方式：先把用户代码落盘，再用一段 patch 器 `exec` 它——把 `remi.start` 的 port/address
 * 强制改为 `$PREVIEW_PORT`/127.0.0.1（用户显式写的 port 被覆盖，没写的补默认）。
 * remi 未安装时给中文引导（而不是裸 ModuleNotFoundError）。
 */
internal fun buildWebAppScript(ctx: Context, runnable: CodeLanguage.Runnable, code: String): String {
    val base = buildRunScript(ctx, runnable, code)
    val isRemi = runnable == CodeLanguage.Runnable.PYTHON && Regex("(?i)\\bremi\\b").containsMatchIn(code)
    if (!isRemi) return base
    val missing = ctx.getString(R.string.chat_code_python_missing).replace("'", "").replace("\n", " ")
    val b64 = Base64.encodeToString(code.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
    return """
        command -v python3 >/dev/null 2>&1 || { echo '$missing'; exit 91; }
        echo '$b64' | base64 -d > /tmp/rl_webapp.py || exit 92
        python3 - /tmp/rl_webapp.py <<'RLPYEOF'
        import os, sys
        try:
            import remi
        except ImportError:
            sys.stderr.write('容器里还没装 remi：先 pip install remi（可让 AI 帮你装，装完再运行）\n')
            sys.exit(91)
        _orig_start = remi.start
        def _patched_start(app, *a, **kw):
            kw['port'] = int(os.environ.get('PREVIEW_PORT') or kw.get('port') or 8081)
            kw.setdefault('address', '127.0.0.1')
            return _orig_start(app, *a, **kw)
        remi.start = _patched_start
        g = {'__name__': '__main__', '__file__': sys.argv[1]}
        exec(compile(open(sys.argv[1], encoding='utf-8').read(), sys.argv[1], 'exec'), g)
        RLPYEOF
    """.trimIndent() + "\n"
}

/**
 * 以「常驻网页服务」模式运行代码：[WebPreviewManager.start] 拉起长驻进程并自动分配端口，
 * 聊天页的预览条（[com.rokidlab.phone.store.WebPreviewBar]）随即出现，点开即 WebView 渲染 GUI。
 *
 * 结果就地写回代码卡的运行结果区（[onResult]），与普通运行同一块展示位；成功额外 Toast
 * 引导点预览条。阻塞调用，须在 IO 线程。
 */
internal fun startAsWebPreview(
    ctx: Context,
    scope: kotlinx.coroutines.CoroutineScope,
    script: String,
    onResult: (ExecResult) -> Unit,
) {
    if (!ProotInstaller.isInstalled(ctx)) {
        Toast.makeText(ctx, R.string.chat_code_env_missing, Toast.LENGTH_LONG).show()
        return
    }
    scope.launch(Dispatchers.IO) {
        val res = when (val r = com.rokidlab.phone.platform.WebPreviewManager.start(ctx, script, cwd = "")) {
            is com.rokidlab.phone.platform.WebPreviewManager.StartResult.Started -> {
                val url = r.info.url
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        ctx,
                        ctx.getString(R.string.chat_code_web_started, url),
                        Toast.LENGTH_LONG,
                    ).show()
                }
                ExecResult(0, "网页应用运行中：$url" + (r.note?.let { "\n$it" } ?: ""), "")
            }
            is com.rokidlab.phone.platform.WebPreviewManager.StartResult.Failed ->
                ExecResult(-1, "", r.message)
        }
        withContext(Dispatchers.Main) { onResult(res) }
    }
}

/**
 * 会话里「最近一个可运行的代码块」：聊天输入"运行"时免点运行键的直接执行目标。
 * 从最新消息往回扫（状态行 / 运行卡片跳过），命中第一个语言可运行的围栏块即返回；
 * 用户自己贴的代码（用户气泡）同样算数。
 */
internal fun findLastRunnableCode(messages: List<ChatMsg>): MdBlock.Code? {
    for (msg in messages.asReversed()) {
        if (msg.isStatus || msg.runId != null) continue
        for (block in parseChatMarkdown(msg.content).asReversed()) {
            if (block is MdBlock.Code && CodeLanguage.support.containsKey(block.lang)) return block
        }
    }
    return null
}

/**
 * 聊天输入是不是「就跑一下」的短指令（说"运行"免点运行键）。
 * 必须**整条**匹配 —— 带别的意思的句子（"帮我写个运行的代码"）照常发给 AI，不劫持正常对话。
 */
private val RUN_INTENT_ZH = Regex(
    "^(帮我|给我|再|再次|直接|现在|就)*(运行|执行|跑)(一下|起来|吧|它|这个|这段|代码|程序|这段代码|这个代码)?$",
)
private val RUN_INTENT_EN = setOf("run", "runit", "runcode", "runthis", "runthecode", "runagain")

internal fun isRunIntent(text: String): Boolean {
    val t = text.replace(Regex("\\s+"), "").lowercase()
    return RUN_INTENT_ZH.matches(t) || t in RUN_INTENT_EN
}

/** 语言 → 能力（可否运行）与落盘文件名（扩展名/MIME）映射。 */
internal object CodeLanguage {
    enum class Runnable { SHELL, PYTHON, NODE, JAVA, HTML }

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
        "java" to Runnable.JAVA,
        // 网页（HTML5 小游戏/静态页）：运行 = 落盘起静态服务 → 聊天预览条打开
        "html" to Runnable.HTML,
        "htm" to Runnable.HTML,
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
