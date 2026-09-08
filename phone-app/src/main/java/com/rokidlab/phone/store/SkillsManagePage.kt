package com.rokidlab.phone.store

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.rokidlab.phone.R
import com.rokidlab.phone.ai.SkillMarkdown
import com.rokidlab.phone.ai.SkillRegistry
import com.rokidlab.phone.app.LabApplication
import com.rokidlab.phone.design.BrewBg
import com.rokidlab.phone.design.BrewBorder
import com.rokidlab.phone.design.BrewChat
import com.rokidlab.phone.design.BrewMuted
import com.rokidlab.phone.design.BrewPanel
import com.rokidlab.phone.design.BrewPanelHi
import com.rokidlab.phone.design.BrewTextBright
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ===== AI 技能管理子页面 =====
// 用户自定义技能（SKILL.md 说明书）：列表 + 开关 + 删除，
// 三入口：手动填写 / 导入文件 / 填写 URL 下载（统一走 SkillRegistry 安装管线）
@Composable
internal fun SkillsManagePage(
    app: LabApplication,
    onBack: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var totalEnabled by remember {
        mutableStateOf(SkillRegistry.isEnabled(ctx))
    }
    var skills by remember { mutableStateOf(SkillRegistry.listSkills(ctx)) }
    fun refresh() {
        skills = SkillRegistry.listSkills(ctx)
    }

    // 三入口：手动填写
    var showEditor by remember { mutableStateOf(false) }
    // 编辑已有技能：记录选中的技能内容（null = 新建）
    var editTarget by remember { mutableStateOf<SkillMarkdown.ParsedSkill?>(null) }
    // 三入口：填写 URL 下载
    var showUrlImport by remember { mutableStateOf(false) }
    // 删除确认
    var deleteTarget by remember { mutableStateOf<String?>(null) }
    // 内置技能官方同步进行中
    var syncingOfficial by remember { mutableStateOf(false) }

    // 三入口：导入本地文件（zip 技能包 / SKILL.md / md）
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val summary = withContext(Dispatchers.IO) {
                importFromUri(ctx, uri)
            }
            refresh()
            Toast.makeText(ctx, summary, Toast.LENGTH_LONG).show()
        }
    }

    Dialog(
        onDismissRequest = onBack,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(BrewBg)
                .padding(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.skills_manage_title),
                    color = BrewTextBright,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onBack) {
                    Text(stringResource(R.string.back_btn), color = BrewChat, fontWeight = FontWeight.Bold)
                }
            }
            Text(
                text = stringResource(R.string.skills_manage_hint),
                color = BrewMuted,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(12.dp))

            // 总开关
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(BrewPanel)
                    .border(1.dp, BrewBorder, RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.skills_enabled_switch),
                        color = BrewTextBright,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.skills_enabled_switch_hint),
                        color = BrewMuted,
                        fontSize = 11.sp,
                    )
                }
                Switch(
                    checked = totalEnabled,
                    onCheckedChange = {
                        totalEnabled = it
                        SkillRegistry.setEnabled(ctx, it)
                    },
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = BrewChat,
                        uncheckedTrackColor = BrewPanelHi,
                        checkedThumbColor = BrewBg,
                        uncheckedThumbColor = BrewMuted,
                    ),
                )
            }

            Spacer(Modifier.height(12.dp))
            // 三入口按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
            ) {
                EntryButton(
                    text = stringResource(R.string.skills_add_create),
                    onClick = { editTarget = null; showEditor = true },
                )
                EntryButton(
                    text = stringResource(R.string.skills_add_import),
                    onClick = {
                        importLauncher.launch(
                            arrayOf(
                                "application/zip",
                                "application/octet-stream",
                                "text/markdown",
                                "text/plain",
                            )
                        )
                    },
                )
                EntryButton(
                    text = stringResource(R.string.skills_add_url),
                    onClick = { showUrlImport = true },
                )
            }

            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.skills_count, skills.size),
                color = BrewMuted,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(8.dp))

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (skills.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 40.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.skills_empty),
                            color = BrewMuted,
                            fontSize = 13.sp,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
                skills.forEach { skill ->
                    SkillRow(
                        name = skill.name,
                        description = skill.description,
                        enabled = skill.enabled,
                        onToggle = { enabled ->
                            SkillRegistry.setSkillEnabled(ctx, skill.name, enabled)
                            refresh()
                        },
                        onEdit = {
                            val doc = SkillRegistry.loadSkill(ctx, skill.name)
                            if (doc != null) {
                                editTarget = doc
                                showEditor = true
                            }
                        },
                        onDelete = { deleteTarget = skill.name },
                        onSync = if (skill.name == SkillRegistry.BUNDLED_SKILL) {
                            {
                                if (!syncingOfficial) {
                                    syncingOfficial = true
                                    scope.launch {
                                        val result = withContext(Dispatchers.IO) {
                                            SkillRegistry.syncBundledOfficial(ctx)
                                        }
                                        syncingOfficial = false
                                        refresh()
                                        Toast.makeText(
                                            ctx,
                                            result.message,
                                            Toast.LENGTH_LONG,
                                        ).show()
                                    }
                                }
                            }
                        } else null,
                    )
                }
            }
        }
    }

    // 手动填写/编辑对话框
    if (showEditor) {
        SkillEditDialog(
            initialName = editTarget?.name.orEmpty(),
            initialDescription = editTarget?.description.orEmpty(),
            initialBody = editTarget?.body.orEmpty(),
            onDismiss = { showEditor = false },
            onSaved = { refresh() },
        )
    }

    // URL 下载对话框
    if (showUrlImport) {
        SkillUrlImportDialog(
            onDismiss = { showUrlImport = false },
            onInstalled = { refresh() },
        )
    }

    // 删除确认
    val target = deleteTarget
    if (target != null) {
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.skills_delete_confirm_title), color = BrewTextBright, fontWeight = FontWeight.Bold) },
            text = { Text(stringResource(R.string.skills_delete_confirm, target), color = BrewMuted, fontSize = 14.sp) },
            confirmButton = {
                TextButton(onClick = {
                    SkillRegistry.delete(ctx, target)
                    deleteTarget = null
                    refresh()
                    Toast.makeText(ctx, ctx.getString(R.string.skills_deleted), Toast.LENGTH_SHORT).show()
                }) {
                    Text(stringResource(R.string.confirm), color = BrewChat, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text(stringResource(R.string.chat_key_dialog_cancel), color = BrewMuted)
                }
            },
            containerColor = BrewPanel,
        )
    }
}

/** 入口按钮（三入口统一风格；声明为 RowScope 扩展以使用 weight 均分） */
@Composable
private fun RowScope.EntryButton(
    text: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .weight(1f)
            .clip(RoundedCornerShape(12.dp))
            .background(BrewPanel)
            .border(1.dp, BrewBorder, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = BrewChat,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

/** 单个技能行 */
@Composable
private fun SkillRow(
    name: String,
    description: String,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onSync: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(BrewPanel)
            .border(1.dp, BrewBorder, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onEdit),
        ) {
            Text(
                text = name,
                color = BrewTextBright,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = description,
                color = BrewMuted,
                fontSize = 11.sp,
                maxLines = 2,
            )
            Spacer(Modifier.height(4.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = stringResource(R.string.skills_edit),
                    color = BrewChat,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(onClick = onEdit)
                        .padding(4.dp),
                )
                Text(
                    text = stringResource(R.string.skills_delete),
                    color = BrewMuted,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(onClick = onDelete)
                        .padding(4.dp),
                )
                if (onSync != null) {
                    Text(
                        text = stringResource(R.string.skills_sync_official),
                        color = BrewChat,
                        fontSize = 11.sp,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable(onClick = onSync)
                            .padding(4.dp),
                    )
                }
            }
        }
        Switch(
            checked = enabled,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(
                checkedTrackColor = BrewChat,
                uncheckedTrackColor = BrewPanelHi,
                checkedThumbColor = BrewBg,
                uncheckedThumbColor = BrewMuted,
            ),
        )
    }
}

/**
 * 从 SAF Uri 导入技能（zip 包或 md 文件，IO 线程）。
 * 先读前 4 字节判断 zip 魔数 PK\x03\x04，再分流安装。
 */
private fun importFromUri(ctx: android.content.Context, uri: android.net.Uri): String {
    return try {
        val bytes = ctx.contentResolver.openInputStream(uri)?.use { input ->
            readLimitedBytes(input, 512L * 1024)
        }
        if (bytes == null) return ctx.getString(R.string.skills_import_failed_large)
        val isZip = bytes.size >= 4 &&
            bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
        if (isZip) {
            val results = SkillRegistry.installFromZip(ctx, bytes)
            summarizeImport(ctx, results)
        } else {
            val text = String(bytes, Charsets.UTF_8)
            val r = SkillRegistry.installFromMarkdown(ctx, text)
            if (r.success) ctx.getString(R.string.skills_imported_one, r.skillName.orEmpty()) else r.message
        }
    } catch (e: Exception) {
        ctx.getString(R.string.skills_import_failed, e.message ?: "")
    }
}

private fun summarizeImport(ctx: android.content.Context, results: List<SkillRegistry.InstallResult>): String {
    if (results.isEmpty()) return ctx.getString(R.string.skills_import_none)
    val ok = results.count { it.success }
    val sb = StringBuilder(ctx.getString(R.string.skills_import_summary, ok, results.size))
    results.filter { !it.success }.forEach { sb.append("\n· ").append(it.message) }
    return sb.toString()
}

/** 限量读取流字节（超限返回 null 表示文件过大） */
private fun readLimitedBytes(input: java.io.InputStream, maxBytes: Long): ByteArray? {
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(8192)
    var total = 0L
    while (true) {
        val n = input.read(buf)
        if (n < 0) break
        total += n
        if (total > maxBytes) return null
        out.write(buf, 0, n)
    }
    return out.toByteArray()
}
