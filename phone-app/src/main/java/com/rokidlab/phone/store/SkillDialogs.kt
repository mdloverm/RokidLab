package com.rokidlab.phone.store

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.rokidlab.phone.R
import com.rokidlab.phone.ai.SkillFetcher
import com.rokidlab.phone.ai.SkillMarkdown
import com.rokidlab.phone.ai.SkillRegistry
import com.rokidlab.phone.design.BrewBg
import com.rokidlab.phone.design.BrewBorder
import com.rokidlab.phone.design.BrewChat
import com.rokidlab.phone.design.BrewMuted
import com.rokidlab.phone.design.BrewPanel
import com.rokidlab.phone.design.BrewTextBright
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ===== 技能手动填写/编辑对话框（三入口①：手动填写）=====
@Composable
internal fun SkillEditDialog(
    initialName: String = "",
    initialDescription: String = "",
    initialBody: String = "",
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    val ctx = LocalContext.current
    var name by remember { mutableStateOf(initialName) }
    var description by remember { mutableStateOf(initialDescription) }
    var body by remember { mutableStateOf(initialBody) }
    var busy by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState())
                .clip(RoundedCornerShape(20.dp))
                .background(BrewPanel)
                .border(1.dp, BrewBorder, RoundedCornerShape(20.dp))
                .padding(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.skills_editor_title),
                    color = BrewTextBright,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.back_btn), color = BrewChat, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(14.dp))

            OutlinedTextField(
                value = name,
                onValueChange = { name = it.lowercase().filter { ch -> ch.isLowerCase() || ch.isDigit() || ch == '-' } },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.skills_editor_name), color = BrewMuted, fontSize = 13.sp) },
                placeholder = { Text("weather-advice", color = BrewMuted, fontSize = 14.sp) },
                supportingText = { Text(stringResource(R.string.skills_editor_name_hint), color = BrewMuted, fontSize = 11.sp) },
                singleLine = true,
                enabled = !busy,
                textStyle = TextStyle(color = BrewTextBright, fontSize = 14.sp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = BrewChat,
                    unfocusedBorderColor = BrewBorder,
                    focusedTextColor = BrewTextBright,
                    unfocusedTextColor = BrewTextBright,
                    cursorColor = BrewChat,
                    disabledTextColor = BrewMuted,
                ),
            )
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = description,
                onValueChange = { description = it.take(300) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.skills_editor_desc), color = BrewMuted, fontSize = 13.sp) },
                placeholder = { Text(stringResource(R.string.skills_editor_desc_placeholder), color = BrewMuted, fontSize = 14.sp) },
                singleLine = true,
                enabled = !busy,
                textStyle = TextStyle(color = BrewTextBright, fontSize = 14.sp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = BrewChat,
                    unfocusedBorderColor = BrewBorder,
                    focusedTextColor = BrewTextBright,
                    unfocusedTextColor = BrewTextBright,
                    cursorColor = BrewChat,
                    disabledTextColor = BrewMuted,
                ),
            )
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = body,
                onValueChange = { body = it.take(SkillMarkdownMaxBody) },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 180.dp),
                label = { Text(stringResource(R.string.skills_editor_body), color = BrewMuted, fontSize = 13.sp) },
                placeholder = { Text(stringResource(R.string.skills_editor_body_placeholder), color = BrewMuted, fontSize = 13.sp) },
                singleLine = false,
                enabled = !busy,
                textStyle = TextStyle(color = BrewTextBright, fontSize = 13.sp, lineHeight = 18.sp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = BrewChat,
                    unfocusedBorderColor = BrewBorder,
                    focusedTextColor = BrewTextBright,
                    unfocusedTextColor = BrewTextBright,
                    cursorColor = BrewChat,
                    disabledTextColor = BrewMuted,
                ),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.skills_editor_body_hint, SkillMarkdownMaxBody),
                color = BrewMuted,
                fontSize = 11.sp,
            )

            Spacer(Modifier.height(18.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                Button(
                    onClick = {
                        busy = true
                        val markdown = com.rokidlab.phone.ai.SkillMarkdown.build(name, description, body)
                        val result = SkillRegistry.installFromMarkdown(ctx, markdown)
                        busy = false
                        if (result.success) {
                            Toast.makeText(ctx, ctx.getString(R.string.skills_editor_saved, result.skillName.orEmpty()), Toast.LENGTH_SHORT).show()
                            onSaved()
                            onDismiss()
                        } else {
                            Toast.makeText(ctx, result.message, Toast.LENGTH_LONG).show()
                        }
                    },
                    enabled = !busy && name.isNotBlank(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = BrewChat, contentColor = BrewBg),
                ) {
                    Text(stringResource(R.string.skills_editor_save_btn), fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/**
 * 技能正文长度上限（编辑器直接在此约束输入，与 SkillMarkdown.MAX_BODY_CHARS 同一常量，
 * 避免两处定义漂移）。
 */
private val SkillMarkdownMaxBody: Int get() = SkillMarkdown.MAX_BODY_CHARS

// ===== URL 下载技能对话框（三入口③：填写下载链接）=====
@Composable
internal fun SkillUrlImportDialog(
    onDismiss: () -> Unit,
    onInstalled: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var resultText by remember { mutableStateOf<String?>(null) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(BrewPanel)
                .border(1.dp, BrewBorder, RoundedCornerShape(20.dp))
                .padding(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.skills_url_title),
                    color = BrewTextBright,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.back_btn), color = BrewChat, fontWeight = FontWeight.Bold)
                }
            }
            Text(
                text = stringResource(R.string.skills_url_hint),
                color = BrewMuted,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(14.dp))

            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("https://github.com/xxx/skills…", color = BrewMuted, fontSize = 13.sp) },
                singleLine = false,
                minLines = 1,
                maxLines = 3,
                enabled = !busy,
                textStyle = TextStyle(color = BrewTextBright, fontSize = 13.sp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = BrewChat,
                    unfocusedBorderColor = BrewBorder,
                    focusedTextColor = BrewTextBright,
                    unfocusedTextColor = BrewTextBright,
                    cursorColor = BrewChat,
                    disabledTextColor = BrewMuted,
                ),
            )

            if (resultText != null) {
                Spacer(Modifier.height(12.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(BrewPanel.copy(alpha = 0.5f))
                        .padding(12.dp),
                ) {
                    Text(text = resultText.orEmpty(), color = BrewTextBright, fontSize = 12.sp, lineHeight = 17.sp)
                }
            }

            Spacer(Modifier.height(18.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                Button(
                    onClick = {
                        val input = url.trim()
                        if (input.isEmpty() || busy) return@Button
                        busy = true
                        resultText = ctx.getString(R.string.skills_url_fetching)
                        scope.launch {
                            val summary = withContext(Dispatchers.IO) {
                                fetchAndInstall(ctx, input)
                            }
                            busy = false
                            resultText = summary
                            // fetchAndInstall 内部已捕获异常并转成文案；任何返回都代表本次流程结束，刷新列表即可
                            onInstalled()
                        }
                    },
                    enabled = !busy && url.isNotBlank(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = BrewChat, contentColor = BrewBg),
                ) {
                    Text(
                        text = stringResource(if (busy) R.string.skills_url_fetching_short else R.string.skills_url_fetch_btn),
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

/**
 * 解析 URL 并下载安装技能（IO 线程）。
 * 返回安装汇总文案，供对话框展示。
 */
private fun fetchAndInstall(ctx: android.content.Context, rawUrl: String): String {
    return try {
        when (SkillFetcher.classify(rawUrl)) {
            SkillFetcher.Kind.ZIP, SkillFetcher.Kind.MD_FILE -> {
                val dl = SkillFetcher.download(rawUrl)
                when (dl) {
                    is SkillFetcher.DownloadResult.Zip -> {
                        val results = SkillRegistry.installFromZip(ctx, dl.bytes)
                        summarize(ctx, results)
                    }
                    is SkillFetcher.DownloadResult.Markdown -> {
                        val r = SkillRegistry.installFromMarkdown(ctx, dl.text)
                        if (r.success) ctx.getString(R.string.skills_installed_one, r.skillName.orEmpty())
                        else r.message
                    }
                }
            }
            SkillFetcher.Kind.REPO_PAGE -> {
                val candidates = SkillFetcher.resolveRepoPage(rawUrl)
                if (candidates.isEmpty()) {
                    ctx.getString(R.string.skills_url_none)
                } else {
                    // 仓库页按序下载每个候选技能并安装（失败跳过，继续下一个）
                    val all = candidates.mapNotNull { cand ->
                        runCatching {
                            val dl = SkillFetcher.download(cand.rawUrl)
                            if (dl is SkillFetcher.DownloadResult.Markdown) {
                                SkillRegistry.installFromMarkdown(ctx, dl.text)
                            } else null
                        }.getOrNull()
                    }
                    summarize(ctx, all)
                }
            }
            SkillFetcher.Kind.UNKNOWN -> ctx.getString(R.string.skills_url_invalid)
        }
    } catch (e: Exception) {
        android.util.Log.e("SkillUrlImport", "fetch failed", e)
        ctx.getString(R.string.skills_url_failed, e.message ?: "")
    }
}

private fun summarize(ctx: android.content.Context, results: List<SkillRegistry.InstallResult>): String {
    if (results.isEmpty()) return ctx.getString(R.string.skills_url_none)
    val ok = results.count { it.success }
    val fail = results.filter { !it.success }
    val sb = StringBuilder(ctx.getString(R.string.skills_url_result_count, ok, results.size)).append('\n')
    fail.forEach { sb.append("· ").append(it.message).append('\n') }
    return sb.toString().trimEnd()
}
