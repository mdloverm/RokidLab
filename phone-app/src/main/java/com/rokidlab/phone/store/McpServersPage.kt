package com.rokidlab.phone.store

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.rokidlab.phone.ai.ToolRegistry
import com.rokidlab.phone.ai.mcp.McpRegistry
import com.rokidlab.phone.ai.mcp.McpServerConfig
import com.rokidlab.phone.ai.mcp.McpServerStore
import com.rokidlab.phone.app.LabApplication
import com.rokidlab.phone.design.BrewAmber
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
import org.json.JSONObject
import java.util.UUID

// ===== MCP 服务器管理子页面 =====
//
// 这是「连外部 MCP」的**唯一入口**：用户在这里填地址、看连接状态、逐工具开关。
//
// 为什么必须单独一页而不是塞进「AI 工具」页：工具页是按 `ToolCategory` 平铺"能力开关"的，
// 而 MCP 多了一层**服务器生命周期**（地址 / 鉴权 / 连接状态 / 失败原因 / 被拒工具）——
// 那层信息属于 server 而不是单个工具，塞进工具页会让两种粒度混在一起。
//
// 权限与安全（方案 §4.4）：因为 ApprovalGate 是 fail-open（无确认通道即降级放行），
// 第三方工具的安全落在**准入**上 —— 地址必须 https、工具默认关、信任标记由用户显式给出。

@Composable
internal fun McpServersPage(
    app: LabApplication,
    onBack: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var servers by remember { mutableStateOf(McpServerStore.load(ctx)) }
    var states by remember { mutableStateOf(McpRegistry.allStates()) }
    var busy by remember { mutableStateOf(false) }
    // 展开查看工具的 server（默认收起：工具列表可能很长）
    var expanded by remember { mutableStateOf(emptySet<String>()) }
    // 编辑/新增对话框的目标：null = 不显示；空配置 = 新增
    var editing by remember { mutableStateOf<McpServerConfig?>(null) }
    var deleting by remember { mutableStateOf<McpServerConfig?>(null) }

    // 逐工具开关：显示值与持久化值都跟着变
    // ⚠️ 这里**即时写盘**（`setEnabled`），不做"关页时才批量提交" ——
    // 本页可以点背景关闭（`onDismissRequest = onBack`），批量提交的写法会让"点背景"静默丢改动。
    var toolStates by remember {
        mutableStateOf(
            ToolRegistry.toolList.associate { it.name to ToolRegistry.isEnabled(ctx, it.name) },
        )
    }

    fun reloadStates() {
        states = McpRegistry.allStates()
    }

    // 进入本页自动连一次：用户刚从别处复制了地址就想看到结果，不该再点一次
    LaunchedEffect(Unit) {
        busy = true
        withContext(Dispatchers.IO) { McpRegistry.syncAll(ctx) }
        reloadStates()
        busy = false
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
                    text = stringResource(R.string.chat_settings_mcp),
                    color = BrewTextBright,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onBack) {
                    Text(stringResource(R.string.done), color = BrewChat, fontWeight = FontWeight.Bold)
                }
            }
            Text(
                text = stringResource(R.string.chat_settings_mcp_hint),
                color = BrewMuted,
                fontSize = 12.sp,
            )
            if (busy) {
                Spacer(Modifier.height(6.dp))
                Text(stringResource(R.string.mcp_connecting), color = BrewAmber, fontSize = 11.sp)
            }
            Spacer(Modifier.height(12.dp))

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            ) {
                // 添加 / 刷新
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = {
                            editing = McpServerConfig(
                                id = "mcp-" + UUID.randomUUID().toString().take(8),
                                name = "",
                                url = "",
                                addedAt = System.currentTimeMillis(),
                            )
                        },
                    ) {
                        Text("+ " + stringResource(R.string.mcp_add), color = BrewChat, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.weight(1f))
                    if (servers.isNotEmpty()) {
                        TextButton(
                            onClick = {
                                scope.launch {
                                    busy = true
                                    withContext(Dispatchers.IO) { McpRegistry.syncAll(ctx) }
                                    reloadStates()
                                    busy = false
                                }
                            },
                        ) {
                            Text(stringResource(R.string.mcp_reconnect_all), color = BrewMuted, fontSize = 13.sp)
                        }
                    }
                }

                if (servers.isEmpty()) {
                    Text(
                        text = stringResource(R.string.mcp_empty),
                        color = BrewMuted,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(start = 4.dp, top = 8.dp),
                    )
                }

                servers.forEach { cfg ->
                    val st = states.firstOrNull { it.config.id == cfg.id }
                    ServerCard(
                        cfg = cfg,
                        state = st,
                        expanded = cfg.id in expanded,
                        toolStates = toolStates,
                        onToggleExpand = {
                            expanded = if (cfg.id in expanded) expanded - cfg.id else expanded + cfg.id
                        },
                        onToggleEnabled = { on ->
                            val next = cfg.copy(enabled = on)
                            servers = McpServerStore.upsert(ctx, next)
                            scope.launch {
                                busy = true
                                withContext(Dispatchers.IO) { McpRegistry.reload(ctx, next) }
                                reloadStates()
                                busy = false
                            }
                        },
                        onToggleTool = { name, on ->
                            toolStates = toolStates + (name to on)
                            ToolRegistry.setEnabled(ctx, name, on)
                        },
                        onReconnect = {
                            scope.launch {
                                busy = true
                                withContext(Dispatchers.IO) { McpRegistry.reload(ctx, cfg) }
                                reloadStates()
                                busy = false
                            }
                        },
                        onEdit = { editing = cfg },
                        onDelete = { deleting = cfg },
                    )
                }
            }
        }
    }

    editing?.let { target ->
        ServerEditDialog(
            initial = target,
            isNew = servers.none { it.id == target.id },
            onDismiss = { editing = null },
            onSave = { saved ->
                servers = McpServerStore.upsert(ctx, saved)
                editing = null
                scope.launch {
                    busy = true
                    withContext(Dispatchers.IO) { McpRegistry.reload(ctx, saved) }
                    reloadStates()
                    busy = false
                }
            },
        )
    }

    deleting?.let { target ->
        ConfirmDeleteDialog(
            name = target.name.ifBlank { target.stdioCommand.ifBlank { target.url } },
            onDismiss = { deleting = null },
            onConfirm = {
                servers = McpServerStore.remove(ctx, target.id)
                McpRegistry.disconnect(ctx, target.id)
                reloadStates()
                deleting = null
            },
        )
    }
}

/** 单个 server 的卡片：状态 + 开关 + 展开的工具开关 + 操作 */
@Composable
private fun ServerCard(
    cfg: McpServerConfig,
    state: McpRegistry.ServerState?,
    expanded: Boolean,
    toolStates: Map<String, Boolean>,
    onToggleExpand: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onToggleTool: (String, Boolean) -> Unit,
    onReconnect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val connected = state?.usable == true
    val failed = state?.error != null
    val dotColor = when {
        !cfg.enabled -> BrewMuted
        connected -> BrewChat
        failed -> BrewAmber
        else -> BrewMuted
    }
    val stateText = when {
        !cfg.enabled -> stringResource(R.string.mcp_state_disabled)
        connected -> stringResource(R.string.mcp_state_connected)
        failed -> state?.error.orEmpty()
        else -> stringResource(R.string.mcp_state_unknown)
    }
    val toolCount = state?.tools?.size ?: 0

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(BrewPanel)
            .border(1.dp, BrewBorder, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(dotColor),
            )
            Spacer(Modifier.size(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = cfg.name.ifBlank { cfg.stdioCommand.ifBlank { cfg.url } },
                    color = BrewTextBright,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
                // stdio server 没有 url（端口动态分配），副标题展示它的启动命令
                Text(
                    text = cfg.stdioCommand.ifBlank { cfg.url },
                    color = BrewMuted,
                    fontSize = 11.sp,
                    maxLines = 2,
                )
            }
            Switch(
                checked = cfg.enabled,
                onCheckedChange = onToggleEnabled,
                colors = SwitchDefaults.colors(
                    checkedTrackColor = BrewChat,
                    uncheckedTrackColor = BrewPanelHi,
                    checkedThumbColor = BrewBg,
                    uncheckedThumbColor = BrewMuted,
                ),
            )
        }

        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stateText,
                color = if (failed && cfg.enabled) BrewAmber else BrewMuted,
                fontSize = 11.sp,
                modifier = Modifier.weight(1f),
            )
            if (toolCount > 0) {
                Text(
                    text = stringResource(R.string.mcp_tools_count, toolCount),
                    color = BrewMuted,
                    fontSize = 11.sp,
                )
            }
        }

        // 被丢弃的工具：必须如实展示，否则用户会以为"对端只有这几个工具"
        val rejected = state?.rejected.orEmpty()
        if (rejected.isNotEmpty()) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = stringResource(R.string.mcp_ignored, rejected.size) + "：" + rejected.joinToString("；"),
                color = BrewAmber,
                fontSize = 11.sp,
            )
        }

        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (expanded) {
                    stringResource(R.string.mcp_tools_collapse, toolCount)
                } else {
                    stringResource(R.string.mcp_tools_expand, toolCount)
                },
                color = if (toolCount > 0) BrewChat else BrewMuted,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = toolCount > 0) { onToggleExpand() }
                    .padding(horizontal = 6.dp, vertical = 3.dp),
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onReconnect) {
                Text(stringResource(R.string.mcp_reconnect), color = BrewMuted, fontSize = 12.sp)
            }
            TextButton(onClick = onEdit) {
                Text(stringResource(R.string.mcp_edit), color = BrewMuted, fontSize = 12.sp)
            }
            TextButton(onClick = onDelete) {
                Text(stringResource(R.string.mcp_delete), color = BrewAmber, fontSize = 12.sp)
            }
        }

        if (expanded) {
            state?.tools?.forEach { tool ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(tool.originalName, color = BrewTextBright, fontSize = 13.sp)
                        if (tool.description.isNotBlank()) {
                            Text(tool.description, color = BrewMuted, fontSize = 11.sp, maxLines = 2)
                        }
                    }
                    Switch(
                        checked = toolStates[tool.wireName] ?: false,
                        onCheckedChange = { on -> onToggleTool(tool.wireName, on) },
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = BrewChat,
                            uncheckedTrackColor = BrewPanelHi,
                            checkedThumbColor = BrewBg,
                            uncheckedThumbColor = BrewMuted,
                        ),
                    )
                }
            }
        }
    }
}

/** 新增 / 编辑 server 的对话框 */
@Composable
private fun ServerEditDialog(
    initial: McpServerConfig,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onSave: (McpServerConfig) -> Unit,
) {
    var name by remember { mutableStateOf(initial.name) }
    var url by remember { mutableStateOf(initial.url) }
    // 本地 stdio 命令（可选）：填了就按容器内进程连接，url 留空
    var stdio by remember { mutableStateOf(initial.stdioCommand) }
    // 令牌只支持单一 Authorization 头（第一版不做任意 header 表，多出来的复杂度换不来什么）
    var token by remember { mutableStateOf(initial.headers["Authorization"].orEmpty()) }
    var trusted by remember { mutableStateOf(initial.trusted) }

    // 判定与后端**共用** `McpRegistry.isUrlAllowed`：两边各写一份 startsWith 迟早分叉。
    // 它允许 https 与「回环 http」（自建本地 MCP 走这条），非回环 http 一律拒。
    // stdio 形态不需要 url：命令非空即可（端口由桥动态分配）。
    val isStdio = stdio.isNotBlank()
    val urlOk = isStdio || url.isBlank() || McpRegistry.isUrlAllowed(url)
    val canSave = urlOk && (isStdio || url.isNotBlank())

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(BrewPanel)
                .border(1.dp, BrewBorder, RoundedCornerShape(16.dp))
                .padding(18.dp),
        ) {
            Text(
                text = stringResource(
                    if (isNew) R.string.mcp_dialog_add_title else R.string.mcp_dialog_edit_title,
                ),
                color = BrewTextBright,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.mcp_field_name), color = BrewMuted, fontSize = 13.sp) },
                singleLine = true,
                textStyle = TextStyle(color = BrewTextBright, fontSize = 14.sp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = BrewChat,
                    unfocusedBorderColor = BrewBorder,
                    focusedTextColor = BrewTextBright,
                    unfocusedTextColor = BrewTextBright,
                    cursorColor = BrewChat,
                ),
            )
            Spacer(Modifier.height(10.dp))

            OutlinedTextField(
                value = url,
                onValueChange = { url = it.trim() },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.mcp_field_url), color = BrewMuted, fontSize = 13.sp) },
                placeholder = {
                    Text("https://example.com/mcp", color = BrewMuted, fontSize = 13.sp)
                },
                // 只支持 https：release 包禁止明文流量，http 地址一定连不上，当场提示比事后报错好
                supportingText = {
                    Text(
                        text = if (url.isNotEmpty() && !urlOk) {
                            stringResource(R.string.mcp_url_must_be_https)
                        } else {
                            stringResource(R.string.mcp_field_url_hint)
                        },
                        color = if (url.isNotEmpty() && !urlOk) BrewAmber else BrewMuted,
                        fontSize = 11.sp,
                    )
                },
                singleLine = true,
                isError = url.isNotEmpty() && !urlOk,
                textStyle = TextStyle(color = BrewTextBright, fontSize = 14.sp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = BrewChat,
                    unfocusedBorderColor = BrewBorder,
                    focusedTextColor = BrewTextBright,
                    unfocusedTextColor = BrewTextBright,
                    cursorColor = BrewChat,
                ),
            )
            Spacer(Modifier.height(10.dp))

            OutlinedTextField(
                value = stdio,
                onValueChange = { stdio = it.trim() },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.mcp_field_stdio), color = BrewMuted, fontSize = 13.sp) },
                placeholder = {
                    Text("npx -y @modelcontextprotocol/server-xxx", color = BrewMuted, fontSize = 13.sp)
                },
                supportingText = {
                    Text(
                        text = stringResource(R.string.mcp_field_stdio_hint),
                        color = BrewMuted,
                        fontSize = 11.sp,
                    )
                },
                singleLine = true,
                textStyle = TextStyle(color = BrewTextBright, fontSize = 14.sp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = BrewChat,
                    unfocusedBorderColor = BrewBorder,
                    focusedTextColor = BrewTextBright,
                    unfocusedTextColor = BrewTextBright,
                    cursorColor = BrewChat,
                ),
            )
            Spacer(Modifier.height(10.dp))

            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.mcp_field_token), color = BrewMuted, fontSize = 13.sp) },
                supportingText = {
                    Text(stringResource(R.string.mcp_field_token_hint), color = BrewMuted, fontSize = 11.sp)
                },
                singleLine = true,
                textStyle = TextStyle(color = BrewTextBright, fontSize = 14.sp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = BrewChat,
                    unfocusedBorderColor = BrewBorder,
                    focusedTextColor = BrewTextBright,
                    unfocusedTextColor = BrewTextBright,
                    cursorColor = BrewChat,
                ),
            )
            Spacer(Modifier.height(10.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.mcp_trusted_label),
                        color = BrewTextBright,
                        fontSize = 13.sp,
                    )
                    Text(
                        text = stringResource(R.string.mcp_trusted_hint),
                        color = BrewMuted,
                        fontSize = 11.sp,
                    )
                }
                Switch(
                    checked = trusted,
                    onCheckedChange = { trusted = it },
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = BrewChat,
                        uncheckedTrackColor = BrewPanelHi,
                        checkedThumbColor = BrewBg,
                        uncheckedThumbColor = BrewMuted,
                    ),
                )
            }

            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.mcp_cancel), color = BrewMuted)
                }
                TextButton(
                    enabled = canSave,
                    onClick = {
                        val headers = if (token.isBlank()) {
                            emptyMap()
                        } else {
                            mapOf("Authorization" to token)
                        }
                        val stdioInput = stdio.trim()
                        // 粘贴的是标准 MCP JSON 配置（{"mcpServers":{...}} / {"command":...,"args":[...]}) 时
                        // 自动拍平成命令行；普通命令行原样透传
                        val (jsonName, stdioCmd) = parseMcpJsonConfig(stdioInput)
                            ?: (null to stdioInput)
                        onSave(
                            initial.copy(
                                name = name.trim().ifBlank { jsonName ?: url.trim().ifBlank { stdioCmd } },
                                url = if (isStdio) "" else url.trim(),
                                stdioCommand = stdioCmd,
                                headers = headers,
                                trusted = trusted,
                            ),
                        )
                    },
                ) {
                    Text(
                        text = stringResource(R.string.mcp_save),
                        color = if (canSave) BrewChat else BrewMuted,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

/**
 * 把用户粘贴的**标准 MCP JSON 配置**拍平成容器内命令行；不是 JSON / 没有 command ⇒ null（按普通命令行处理）。
 *
 * 兼容两种形态（Claude Desktop / Cline 同款格式）：
 *  - `{"mcpServers": {"uno": {"command": "uvx", "args": [...], "env": {...}}}}` —— 多 server 时取**第一个**；
 *    返回 server 键名供默认命名；
 *  - `{"command": "uvx", "args": [...]}` —— 单 server 直写。
 *
 * env 以 `KEY=value` 前缀并入命令行（命令经容器内 sh 执行，天然支持）；含空格/特殊字符的词单引号包裹。
 */
private fun parseMcpJsonConfig(input: String): Pair<String?, String>? {
    val t = input.trim()
    if (!t.startsWith("{") || !t.endsWith("}")) return null
    val root = runCatching { JSONObject(t) }.getOrNull() ?: return null
    val servers = root.optJSONObject("mcpServers")
    val entry: JSONObject = if (servers != null) {
        var first: JSONObject? = null
        val keys = servers.keys()
        while (keys.hasNext() && first == null) {
            first = servers.optJSONObject(keys.next())
        }
        first ?: return null
    } else {
        root
    }
    val cmd = entry.optString("command").trim()
    if (cmd.isBlank()) return null
    val sb = StringBuilder()
    entry.optJSONObject("env")?.let { env ->
        env.keys().forEach { k ->
            val v = env.optString(k)
            if (k.isNotBlank() && v.isNotBlank()) sb.append(shellWord("$k=$v")).append(' ')
        }
    }
    sb.append(shellWord(cmd))
    entry.optJSONArray("args")?.let { arr ->
        for (i in 0 until arr.length()) {
            val a = arr.optString(i).trim()
            if (a.isNotEmpty()) sb.append(' ').append(shellWord(a))
        }
    }
    // server 键名（如 "uno"）——多 server JSON 只接第一个，键名留作默认显示名
    var jsonName: String? = null
    if (servers != null) {
        val keys = servers.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            if (servers.optJSONObject(k) != null) { jsonName = k; break }
        }
    }
    return jsonName to sb.toString()
}

/** sh 词法引述：安全字符集直接裸写，其余单引号包裹（内部单引号按 `'\''` 转义） */
private fun shellWord(w: String): String =
    if (w.matches(Regex("[A-Za-z0-9_@%+=:,./-]+"))) w
    else "'" + w.replace("'", "'\\''") + "'"

/** 删除确认：删掉 server 等于把它的工具从模型可见清单里摘掉，值得一次确认 */
@Composable
private fun ConfirmDeleteDialog(
    name: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(BrewPanel)
                .border(1.dp, BrewBorder, RoundedCornerShape(16.dp))
                .padding(18.dp),
        ) {
            Text(
                text = stringResource(R.string.mcp_delete_confirm_title),
                color = BrewTextBright,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.mcp_delete_confirm_body, name),
                color = BrewMuted,
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.mcp_cancel), color = BrewMuted)
                }
                TextButton(onClick = onConfirm) {
                    Text(stringResource(R.string.mcp_delete), color = BrewAmber, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
