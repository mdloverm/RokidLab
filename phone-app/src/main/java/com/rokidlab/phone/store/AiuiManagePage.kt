package com.rokidlab.phone.store

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.rokidlab.phone.R
import com.rokidlab.phone.ai.AiuiAppRegistry
import com.rokidlab.phone.ai.AiuiProject
import com.rokidlab.phone.app.LabApplication
import com.rokidlab.phone.design.BrewBg
import com.rokidlab.phone.design.BrewBorder
import com.rokidlab.phone.design.BrewChat
import com.rokidlab.phone.design.BrewMuted
import com.rokidlab.phone.design.BrewPanel
import com.rokidlab.phone.design.BrewPanelHi
import com.rokidlab.phone.design.BrewTextBright
import com.rokidlab.phone.glasses.AiuiFrontendController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ===== AIUI 应用管理子页面 =====
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AiuiManagePage(
    app: LabApplication,
    onBack: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var records by remember { mutableStateOf(AiuiAppRegistry.list(ctx)) }
    var busy by remember { mutableStateOf<String?>(null) }
    // 删除确认弹窗状态：目标记录 + 是否连带删除源文件 / 眼镜端文件
    var pendingDelete by remember { mutableStateOf<AiuiAppRegistry.AiuiAppRecord?>(null) }
    var delSource by remember { mutableStateOf(false) }
    var delGlasses by remember { mutableStateOf(false) }
    val df = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

    fun toast(msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()

    // 本地 .aix 选择上传（OpenDocument；.aix 为 zip，mime 不固定故用 */* 再按扩展名过滤）
    val uploadLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                busy = ctx.getString(R.string.aiui_busy)
                val msg = withContext(Dispatchers.IO) { uploadLocalAix(ctx, uri) }
                busy = null
                records = AiuiAppRegistry.list(ctx)
                toast(msg)
            }
        }
    }

    fun runOp(block: suspend () -> String) {
        scope.launch {
            busy = ctx.getString(R.string.aiui_busy)
            val msg = withContext(Dispatchers.IO) { block() }
            busy = null
            records = AiuiAppRegistry.list(ctx)
            toast(msg)
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
            // 顶栏：返回 + 标题
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.aiui_manage_title),
                    color = BrewTextBright,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onBack) {
                    Text(stringResource(R.string.done), color = BrewChat, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(12.dp))

            // 上传本地 .aix
            Button(
                onClick = { uploadLauncher.launch(arrayOf("*/*")) },
                enabled = busy == null,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = BrewChat, contentColor = BrewBg),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.aiui_manage_upload_btn), fontWeight = FontWeight.Bold)
            }
            busy?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, color = BrewMuted, fontSize = 12.sp)
            }
            Spacer(Modifier.height(12.dp))

            if (records.isEmpty()) {
                Text(
                    text = stringResource(R.string.aiui_manage_empty),
                    color = BrewMuted,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 40.dp),
                )
            } else {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                ) {
                    records.forEach { r ->
                        val canReinstall = r.origin == AiuiAppRegistry.ORIGIN_GENERATED &&
                            !r.project.isNullOrEmpty() && r.sourceProjectDir?.let { File(it).isDirectory } == true
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(BrewPanel)
                                .border(1.dp, BrewBorder, RoundedCornerShape(12.dp))
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                        ) {
                            Text(
                                text = r.appName,
                                color = BrewTextBright,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                            )
                            Spacer(Modifier.height(3.dp))
                            val originLabel = when (r.origin) {
                                AiuiAppRegistry.ORIGIN_UPLOADED -> stringResource(R.string.aiui_origin_uploaded)
                                AiuiAppRegistry.ORIGIN_GENERATED -> stringResource(R.string.aiui_origin_generated)
                                else -> stringResource(R.string.aiui_origin_legacy)
                            }
                            val statusLabel = if (r.aixOnGlasses) {
                                stringResource(R.string.aiui_status_on_glasses)
                            } else {
                                stringResource(R.string.aiui_status_local)
                            }
                            val time = if (r.updatedAt > 0) df.format(Date(r.updatedAt)) else "—"
                            Text(
                                text = "$originLabel · $statusLabel · $time" + (r.project?.let { " · $it" } ?: ""),
                                color = BrewMuted,
                                fontSize = 11.sp,
                            )
                            Spacer(Modifier.height(8.dp))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                ActionText(stringResource(R.string.aiui_action_open), busy == null) {
                                    runOp {
                                        // 本机有 .aix（对话生成/本地上传）→ 自托管宿主演示（Lab 手柄可控）；
                                        // 无本地包（内置/历史）→ 旧 Sys_AIUI_Start 直启
                                        val pkg = AiuiProject.packageFile(ctx, r.agentId)
                                        if (pkg.isFile) {
                                            val ack = app.cxrL.pushAixToRokidLinkHost(pkg)
                                            when (ack?.trim()) {
                                                // 落盘 + 拉升指令已下发 ≠ 一定已渲染，故不再说「已打开」
                                                "OK" -> ctx.getString(R.string.aiui_open_pushed, r.appName)
                                                // 落盘成功但宿主没拉起：不能再显示「打开成功」，也不是"眼镜没连"
                                                AiuiFrontendController.PUSHED_OPEN_FAILED ->
                                                    ctx.getString(R.string.aiui_open_pushed_open_failed, r.appName)
                                                else -> ctx.getString(R.string.aiui_open_fail, -1)
                                            }
                                        } else {
                                            val code = app.cxrL.startAiuiPackage(r.agentId)
                                            if (code == 0) ctx.getString(R.string.aiui_open_ok, r.appName)
                                            else ctx.getString(R.string.aiui_open_fail, code)
                                        }
                                    }
                                }
                                if (canReinstall) {
                                    ActionText(stringResource(R.string.aiui_action_reinstall), busy == null) {
                                        runOp { reinstallGenerated(ctx, r) }
                                    }
                                }
                                ActionText(stringResource(R.string.aiui_action_close), busy == null) {
                                    runOp {
                                        // 与「打开」路由一致：宿主演示链路用 close 关宿主，否则关官方渲染层
                                        val pkg = AiuiProject.packageFile(ctx, r.agentId)
                                        val code = if (pkg.isFile) {
                                            app.cxrL.closeAiuiHost()
                                        } else {
                                            app.cxrL.stopAiuiPackage(r.agentId)
                                        }
                                        if (code == 0) ctx.getString(R.string.aiui_close_ok)
                                        else ctx.getString(R.string.aiui_close_fail, code)
                                    }
                                }
                                ActionText(stringResource(R.string.aiui_action_delete), busy == null) {
                                    // 弹确认框：询问是否连带删除项目源文件 / 眼镜端文件
                                    delSource = canReinstall
                                    delGlasses = r.aixOnGlasses
                                    pendingDelete = r
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // 删除确认弹窗：询问是否连带删除项目源文件 / 眼镜端 .aix 文件
    pendingDelete?.let { target ->
        val hasSource = target.sourceProjectDir?.let { File(it).isDirectory } == true
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            containerColor = BrewPanel,
            title = {
                Text(
                    stringResource(R.string.aiui_delete_confirm_title),
                    color = BrewTextBright,
                    fontWeight = FontWeight.Bold,
                )
            },
            text = {
                Column {
                    Text(
                        stringResource(R.string.aiui_delete_confirm_msg, target.appName),
                        color = BrewMuted,
                        fontSize = 14.sp,
                    )
                    if (hasSource) {
                        CheckRow(stringResource(R.string.aiui_delete_opt_source), delSource) { delSource = it }
                    }
                    if (target.aixOnGlasses) {
                        CheckRow(stringResource(R.string.aiui_delete_opt_glasses), delGlasses) { delGlasses = it }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val r = target
                    pendingDelete = null
                    runOp { deleteRecord(ctx, r, delSource && hasSource, delGlasses && target.aixOnGlasses) }
                }) {
                    Text(
                        stringResource(R.string.aiui_action_delete),
                        color = BrewChat,
                        fontWeight = FontWeight.Bold,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text(stringResource(R.string.chat_key_dialog_cancel), color = BrewMuted)
                }
            },
        )
    }
}

@Composable
private fun CheckRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = BrewTextBright, fontSize = 13.sp, modifier = Modifier.weight(1f))
        Checkbox(checked = checked, onCheckedChange = onChange, colors = CheckboxDefaults.colors(checkedColor = BrewChat))
    }
}

@Composable
private fun ActionText(label: String, enabled: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
        Text(label, color = if (enabled) BrewChat else BrewMuted, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

/** 重新打包并上传对话生成的项目（同名覆盖；VERSION 内容指纹驱动眼镜重解压） */
private suspend fun reinstallGenerated(
    ctx: android.content.Context,
    r: AiuiAppRegistry.AiuiAppRecord,
): String = withContext(Dispatchers.IO) {
    val project = r.project ?: return@withContext ctx.getString(R.string.aiui_reinstall_fail, "no project")
    val err = AiuiProject.validateAiuiProject(ctx, project)
    if (err != null) return@withContext ctx.getString(R.string.aiui_reinstall_fail, err)
    val aix = AiuiProject.buildAix(ctx, project, r.appName)
        ?: return@withContext ctx.getString(R.string.aiui_reinstall_fail, "build failed")
    val up = AiuiProject.uploadAixToGlasses(ctx, aix.aixFile)
    if (up != null) return@withContext ctx.getString(R.string.aiui_reinstall_fail, up)
    AiuiAppRegistry.upsert(
        ctx,
        r.copy(
            agentId = aix.agentId,
            pageName = aix.pageName,
            sourceProjectDir = AiuiProject.projectDir(ctx, project).absolutePath,
            origin = AiuiAppRegistry.ORIGIN_GENERATED,
            aixOnGlasses = true,
        ),
    )
    ctx.getString(R.string.aiui_reinstall_ok, r.appName)
}

/**
 * 删除记录 + 手机端 .aix 包；源码目录/眼镜端文件按弹窗选项连带删除。
 * 眼镜端删除失败不回滚本地记录（本地已删干净，眼镜残留靠超量自动清理）。
 */
private suspend fun deleteRecord(
    ctx: android.content.Context,
    r: AiuiAppRegistry.AiuiAppRecord,
    deleteSource: Boolean,
    deleteOnGlasses: Boolean,
): String = withContext(Dispatchers.IO) {
    AiuiAppRegistry.remove(ctx, r.agentId)
    if (deleteSource) {
        runCatching { r.sourceProjectDir?.let { File(it).deleteRecursively() } }
    }
    runCatching { AiuiProject.packageFile(ctx, r.agentId).delete() }
    if (deleteOnGlasses) {
        val err = AiuiProject.deleteAixOnGlasses(ctx, r.agentId)
        if (err != null) return@withContext ctx.getString(R.string.aiui_delete_glasses_fail, err)
        return@withContext ctx.getString(R.string.aiui_delete_glasses_ok, r.appName)
    }
    ctx.getString(R.string.aiui_delete_ok, r.appName)
}

/** 本地 .aix 复制到私有包目录 → 直传眼镜 cxr → 登记 origin=uploaded。返回提示文案 */
private suspend fun uploadLocalAix(
    ctx: android.content.Context,
    uri: android.net.Uri,
): String = withContext(Dispatchers.IO) {
    val displayName = runCatching {
        ctx.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull() ?: "upload.aix"
    if (!displayName.lowercase().endsWith(".aix")) {
        return@withContext ctx.getString(R.string.aiui_upload_fail, "not an .aix file")
    }
    val appName = displayName.substringBeforeLast('.', displayName)
    val agentId = AiuiProject.agentIdFromFileName(displayName)
    val target = AiuiProject.packageFile(ctx, agentId)
    runCatching {
        ctx.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { out -> input.copyTo(out) }
        } ?: return@withContext ctx.getString(R.string.aiui_upload_fail, "cannot read file")
    }.getOrElse { return@withContext ctx.getString(R.string.aiui_upload_fail, it.message ?: "copy failed") }

    val up = AiuiProject.uploadAixToGlasses(ctx, target)
    if (up != null) return@withContext ctx.getString(R.string.aiui_upload_fail, up)
    AiuiAppRegistry.upsert(
        ctx,
        AiuiAppRegistry.AiuiAppRecord(
            appName = appName,
            agentId = agentId,
            project = null,
            sourceProjectDir = null,
            origin = AiuiAppRegistry.ORIGIN_UPLOADED,
            aixOnGlasses = true,
        ),
    )
    ctx.getString(R.string.aiui_upload_ok, appName)
}
