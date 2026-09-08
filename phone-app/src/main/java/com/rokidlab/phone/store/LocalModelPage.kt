package com.rokidlab.phone.store

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.rokidlab.phone.R
import com.rokidlab.phone.ai.LocalOllamaManager
import com.rokidlab.phone.ai.LocalOllamaManager.OllamaModel
import com.rokidlab.phone.app.LabApplication
import com.rokidlab.phone.design.BrewBg
import com.rokidlab.phone.design.BrewBorder
import com.rokidlab.phone.design.BrewChat
import com.rokidlab.phone.design.BrewMuted
import com.rokidlab.phone.design.BrewPanel
import com.rokidlab.phone.design.BrewPanelHi
import com.rokidlab.phone.design.BrewRed
import com.rokidlab.phone.design.BrewSuccess
import com.rokidlab.phone.design.BrewTextBright
import com.rokidlab.phone.design.BrewWarning
import com.rokidlab.phone.glasses.AiChannel
import com.rokidlab.phone.glasses.CxrLHiRokidSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

// ===== 本地模型管理子页面（Termux + Ollama）=====

/** 启动失败后的引导类型 */
private enum class StartGuidance { NONE, PERMISSION, DENIED, TIMEOUT }

/** 页面内可拉的推荐模型（按手机内存从小到大） */
private val SUGGESTED_MODELS = listOf(
    "qwen2.5:0.5b",
    "qwen2.5:1.5b",
    "llama3.2:1b",
    "deepseek-r1:1.5b",
    "qwen2.5:3b",
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LocalModelPage(
    app: LabApplication,
    onBack: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val session = runCatching { app.cxrL }.getOrNull()

    // —— 环境与设备 ——
    var termuxOk by remember { mutableStateOf(LocalOllamaManager.termuxInstalled(ctx)) }

    // —— 服务状态 ——
    var checking by remember { mutableStateOf(true) }
    var serverUp by remember { mutableStateOf(false) }
    var serverVer by remember { mutableStateOf("") }
    var starting by remember { mutableStateOf(false) }
    var stopping by remember { mutableStateOf(false) }
    var guidance by remember { mutableStateOf(StartGuidance.NONE) }

    // —— 模型与对话配置 ——
    var models by remember { mutableStateOf(listOf<OllamaModel>()) }
    var loadingModels by remember { mutableStateOf(false) }
    var chatModel by remember { mutableStateOf("") }   // 当前眼镜对话使用的模型
    var chatLocal by remember { mutableStateOf(false) } // 对话服务是否已指向本机 Ollama
    // 本地对话调参快捷项（-1 / -1f = 不设置，请求时省略对应字段）
    var selPredict by remember { mutableStateOf(-1) }   // options.num_predict：回复长度上限
    var selCtx by remember { mutableStateOf(-1) }       // options.num_ctx：上下文长度
    var selTemp by remember { mutableStateOf(-1f) }     // temperature：温度
    var pendingDelete by remember { mutableStateOf<OllamaModel?>(null) }

    // —— 拉取模型 ——
    var showPull by remember { mutableStateOf(false) }
    var pullName by remember { mutableStateOf("") }
    var pulling by remember { mutableStateOf(false) }
    var pullStatus by remember { mutableStateOf("") }
    var pullPercent by remember { mutableStateOf(-1) }

    fun toast(msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()

    /** 解析已保存的请求参数 JSON 回填快捷项（兼容旧结构，未知字段忽略） */
    fun loadChatParams(json: String) {
        selPredict = -1
        selCtx = -1
        selTemp = -1f
        val raw = json.trim()
        if (raw.isEmpty()) return
        val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return
        obj.optJSONObject("options")?.let { o ->
            selPredict = o.optInt("num_predict", -1)
            selCtx = o.optInt("num_ctx", -1)
        }
        val t = obj.optDouble("temperature", -1.0)
        selTemp = if (t >= 0) t.toFloat() else -1f
    }

    /** 由快捷项生成请求参数 JSON（未设置的字段省略；全未设置=空串）并即时持久化 */
    fun applyChatParams() {
        val s = session ?: return
        val opts = JSONObject()
        if (selPredict > 0) opts.put("num_predict", selPredict)
        if (selCtx > 0) opts.put("num_ctx", selCtx)
        val obj = JSONObject()
        if (opts.length() > 0) obj.put("options", opts)
        if (selTemp >= 0f) obj.put("temperature", selTemp.toDouble())
        s.setLocalChatParams(if (obj.length() == 0) "" else obj.toString())
    }

    fun refreshChatConfig() {
        val s = session ?: return
        chatLocal = s.isLocalChatActive()
        chatModel = if (chatLocal) s.localChatModel() else ""
        loadChatParams(s.localChatParams())
    }

    fun checkServer() {
        scope.launch {
            val v = withContext(Dispatchers.IO) { LocalOllamaManager.serverVersion() }
            checking = false
            serverUp = v != null
            serverVer = v ?: ""
            if (v != null) {
                refreshChatConfig()
                scope.launch {
                    loadingModels = true
                    models = withContext(Dispatchers.IO) {
                        runCatching { LocalOllamaManager.listModels() }.getOrDefault(emptyList())
                    }
                    loadingModels = false
                }
            } else {
                loadingModels = false
                models = emptyList()
            }
        }
    }

    fun refreshAll() {
        scope.launch {
            termuxOk = LocalOllamaManager.termuxInstalled(ctx)
            refreshChatConfig()
            checkServer()
        }
    }

    LaunchedEffect(Unit) { refreshAll() }

    // —— RUN_COMMAND 运行时权限（Termux 0.119+ 将 RUN_COMMAND 从 signature 改为
    //    dangerous，必须经系统弹窗授予；卸载/重装后失效需重新申请）——
    var grantTick by remember { mutableStateOf(0) }
    var grantGranted by remember { mutableStateOf(false) }
    val runCmdLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            grantGranted = true
            grantTick++   // 仅授权成功才推进续跑；拒绝只落回引导，避免弹框死循环
        } else {
            guidance = StartGuidance.PERMISSION
        }
    }

    // 启动服务
    fun start() {
        if (starting) return
        scope.launch {
            starting = true
            guidance = StartGuidance.NONE
            // 预检：缺 RUN_COMMAND 权限 → 直接弹系统授权框（授权成功后经 grantTick 自动续跑）
            if (termuxOk && !LocalOllamaManager.runCommandGranted(ctx)) {
                starting = false
                runCmdLauncher.launch(LocalOllamaManager.RUN_CMD_PERMISSION)
                return@launch
            }
            val r = withContext(Dispatchers.IO) { LocalOllamaManager.startServer(ctx) }
            starting = false
            when (r) {
                LocalOllamaManager.StartResult.Started -> {
                    toast(ctx.getString(R.string.local_model_started_ok))
                    checkServer()
                }
                LocalOllamaManager.StartResult.AlreadyRunning -> {
                    toast(ctx.getString(R.string.local_model_started_already))
                    checkServer()
                }
                LocalOllamaManager.StartResult.TermuxMissing -> termuxOk = false
                LocalOllamaManager.StartResult.LaunchDenied -> {
                    guidance = if (LocalOllamaManager.runCommandGranted(ctx)) {
                        StartGuidance.DENIED
                    } else {
                        StartGuidance.PERMISSION
                    }
                }
                LocalOllamaManager.StartResult.Timeout -> guidance = StartGuidance.TIMEOUT
            }
        }
    }

    // 授权成功 → 自动继续启动（无需用户再点一次）
    LaunchedEffect(grantTick) {
        if (grantTick > 0 && grantGranted) start()
    }

    // 停止服务
    fun stop() {
        if (stopping) return
        scope.launch {
            stopping = true
            withContext(Dispatchers.IO) { LocalOllamaManager.stopServer(ctx) }
            stopping = false
            toast(ctx.getString(R.string.local_model_stopped_ok))
            checkServer()
        }
    }

    // 把模型设为眼镜对话模型：开启本地模型模式（仅写本地槽位，不触碰在线槽位，
    // 避免覆盖用户已配置的自定义服务地址/密钥/模型）
    fun useAsChat(m: OllamaModel) {
        val s = session ?: return
        runCatching {
            s.setLocalChatModel(m.name)
        }.onSuccess {
            refreshChatConfig()
            toast(ctx.getString(R.string.local_model_set_chat_ok, m.name))
        }.onFailure { e ->
            toast(ctx.getString(R.string.local_model_set_chat_fail, e.message ?: "error"))
        }
    }

    // 拉取模型（流式进度实时回填）
    fun pull() {
        val name = pullName.trim()
        if (name.isEmpty()) return
        if (pulling) return
        scope.launch {
            pulling = true
            pullStatus = ""
            pullPercent = -1
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    LocalOllamaManager.pullModel(name) { status, percent ->
                        pullStatus = status
                        if (percent >= 0) pullPercent = percent
                    }
                }
            }
            pulling = false
            result.onSuccess {
                toast(ctx.getString(R.string.local_model_pull_done, name))
                pullName = ""
                pullPercent = -1
                checkServer()
            }.onFailure { e ->
                toast(ctx.getString(R.string.local_model_pull_fail, e.message ?: "error"))
                pullPercent = -1
            }
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
            // 顶栏：标题 + 完成
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.local_model_title),
                    color = BrewTextBright,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onBack) {
                    Text(stringResource(R.string.done), color = BrewChat, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.local_model_subtitle),
                color = BrewMuted,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(12.dp))

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            ) {
                // ── 服务状态卡 ──
                PageCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(status = if (checking) 0 else if (serverUp) 1 else 2)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.local_model_service),
                            color = BrewTextBright,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = when {
                                checking -> stringResource(R.string.local_model_status_checking)
                                serverUp -> stringResource(R.string.local_model_status_running, serverVer)
                                stopping -> stringResource(R.string.local_model_stopping)
                                starting -> stringResource(R.string.local_model_status_starting)
                                else -> stringResource(R.string.local_model_status_stopped)
                            },
                            color = if (serverUp) BrewSuccess else BrewMuted,
                            fontSize = 12.sp,
                        )
                    }

                    Spacer(Modifier.height(8.dp))
                    if (!termuxOk) {
                        // Termux 未安装引导
                        Text(
                            text = stringResource(R.string.local_model_termux_missing),
                            color = BrewWarning,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = stringResource(R.string.local_model_termux_missing_hint),
                            color = BrewMuted,
                            fontSize = 12.sp,
                        )
                        Spacer(Modifier.height(10.dp))
                        Button(
                            onClick = {
                                runCatching {
                                    val i = android.content.Intent(
                                        android.content.Intent.ACTION_VIEW,
                                        android.net.Uri.parse("https://f-droid.org/packages/com.termux/"),
                                    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                    ctx.startActivity(i)
                                }.onFailure { toast(ctx.getString(R.string.local_model_open_fail)) }
                            },
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = BrewChat, contentColor = BrewBg),
                        ) {
                            Text(stringResource(R.string.local_model_btn_install_termux), fontWeight = FontWeight.Bold)
                        }
                    } else {
                        Text(
                            text = if (serverUp) {
                                stringResource(R.string.local_model_running_hint)
                            } else {
                                stringResource(R.string.local_model_stopped_hint)
                            },
                            color = BrewMuted,
                            fontSize = 12.sp,
                        )
                        // 停止状态（无失败引导时）提示保活要点：Ollama 由 Termux 承载，
                        // 上滑清理后台/省电策略会连带杀掉 ollama（曾因 SwipeUpClean 停服）
                        if (!serverUp && guidance == StartGuidance.NONE) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = stringResource(R.string.local_model_keep_alive_tip),
                                color = BrewMuted,
                                fontSize = 11.sp,
                            )
                        }

                        // 启动失败引导（权限 / 拒绝 / 超时）
                        if (guidance == StartGuidance.PERMISSION) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = stringResource(R.string.local_model_launch_perm),
                                color = BrewWarning,
                                fontSize = 13.sp,
                            )
                        } else if (guidance == StartGuidance.DENIED) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = stringResource(R.string.local_model_launch_denied),
                                color = BrewWarning,
                                fontSize = 13.sp,
                            )
                        } else if (guidance == StartGuidance.TIMEOUT) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = stringResource(R.string.local_model_start_timeout),
                                color = BrewWarning,
                                fontSize = 13.sp,
                            )
                        }

                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (!serverUp) {
                                Button(
                                    onClick = { start() },
                                    enabled = !starting,
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = BrewChat, contentColor = BrewBg),
                                ) {
                                    Text(
                                        text = stringResource(
                                            if (starting) R.string.local_model_starting else R.string.local_model_btn_start
                                        ),
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                            } else {
                                Button(
                                    onClick = { stop() },
                                    enabled = !stopping,
                                    shape = RoundedCornerShape(12.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = BrewPanelHi,
                                        contentColor = BrewTextBright,
                                    ),
                                ) {
                                    Text(
                                        text = stringResource(
                                            if (stopping) R.string.local_model_stopping else R.string.local_model_btn_stop
                                        ),
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                            }
                            if (guidance != StartGuidance.NONE) {
                                if (guidance == StartGuidance.PERMISSION) {
                                    Button(
                                        onClick = { LocalOllamaManager.openAppPermissionSettings(ctx) },
                                        shape = RoundedCornerShape(12.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = BrewChat,
                                            contentColor = BrewBg,
                                        ),
                                    ) {
                                        Text(
                                            stringResource(R.string.local_model_btn_grant),
                                            fontWeight = FontWeight.Bold,
                                        )
                                    }
                                }
                                TextButton(onClick = {
                                    val opened = LocalOllamaManager.openTermuxSettings(ctx)
                                    if (!opened) {
                                        runCatching {
                                            val i = ctx.packageManager.getLaunchIntentForPackage(
                                                LocalOllamaManager.TERMUX_PKG
                                            )?.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                            if (i != null) ctx.startActivity(i)
                                        }
                                    }
                                }) {
                                    Text(
                                        stringResource(R.string.local_model_btn_termux_settings),
                                        color = BrewChat,
                                        fontSize = 13.sp,
                                    )
                                }
                                if (guidance != StartGuidance.PERMISSION) {
                                    TextButton(onClick = {
                                        val cmd = "pkg update && pkg install -y ollama"
                                        val clip = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                            as android.content.ClipboardManager
                                        clip.setPrimaryClip(
                                            android.content.ClipData.newPlainText("ollama install", cmd)
                                        )
                                        toast(ctx.getString(R.string.local_model_cmd_copied))
                                    }) {
                                        Text(
                                            stringResource(R.string.local_model_copy_cmd),
                                            color = BrewChat,
                                            fontSize = 13.sp,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                // ── 对话接入卡 ──
                PageCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(R.string.local_model_chat_title),
                            color = BrewTextBright,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f),
                        )
                        if (chatModel.isNotEmpty()) {
                            Text(
                                text = stringResource(R.string.local_model_use_chat),
                                color = BrewChat,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = if (chatModel.isNotEmpty()) {
                            stringResource(R.string.local_model_chat_local_hint, chatModel)
                        } else {
                            stringResource(R.string.local_model_chat_empty_hint)
                        },
                        color = BrewMuted,
                        fontSize = 12.sp,
                    )
                    // 本地对话调参快捷项（即时生效，逐字段合并进每次本地对话请求）
                    if (chatModel.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Spacer(
                            Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(BrewBorder)
                        )
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.local_model_params_title),
                                    color = BrewTextBright,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    text = stringResource(R.string.local_model_params_subtitle),
                                    color = BrewMuted,
                                    fontSize = 11.sp,
                                )
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        ParamChipRow(
                            title = stringResource(R.string.local_model_params_predict_title),
                            hint = stringResource(R.string.local_model_params_predict_hint),
                            items = listOf(
                                -1 to stringResource(R.string.local_model_params_option_unlimited),
                                128 to "128",
                                256 to "256",
                                512 to "512",
                                1024 to "1024",
                            ),
                            selected = selPredict,
                            onSelect = { selPredict = it; applyChatParams() },
                        )
                        Spacer(Modifier.height(14.dp))
                        ParamChipRow(
                            title = stringResource(R.string.local_model_params_ctx_title),
                            hint = stringResource(R.string.local_model_params_ctx_hint),
                            items = listOf(
                                -1 to stringResource(R.string.local_model_params_option_default),
                                2048 to "2048",
                                4096 to "4096",
                                8192 to "8192",
                            ),
                            selected = selCtx,
                            onSelect = { selCtx = it; applyChatParams() },
                        )
                        Spacer(Modifier.height(14.dp))
                        ParamChipRow(
                            title = stringResource(R.string.local_model_params_temp_title),
                            hint = stringResource(R.string.local_model_params_temp_hint),
                            items = listOf(
                                -1f to stringResource(R.string.local_model_params_option_default),
                                0.2f to "0.2",
                                0.7f to "0.7",
                                1.0f to "1.0",
                            ),
                            selected = selTemp,
                            onSelect = { selTemp = it; applyChatParams() },
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                // ── 已安装模型 ──
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.local_model_installed_title, models.size),
                        color = BrewTextBright,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    if (serverUp) {
                        TextButton(onClick = { showPull = !showPull }) {
                            Text(
                                text = stringResource(
                                    if (showPull) R.string.local_model_btn_collapse_pull
                                    else R.string.local_model_btn_pull
                                ),
                                color = BrewChat,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }

                // 拉取模型展开区
                if (showPull && serverUp) {
                    PullSection(
                        value = pullName,
                        onValueChange = { pullName = it },
                        pulling = pulling,
                        percent = pullPercent,
                        status = pullStatus,
                        onPull = { pull() },
                        enabled = !pulling,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.local_model_pull_suggest_title),
                        color = BrewMuted,
                        fontSize = 11.sp,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        SUGGESTED_MODELS.forEach { name ->
                            SuggestChip(name, selected = name == pullName) {
                                pullName = name
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = stringResource(R.string.local_model_pull_memory_note),
                        color = BrewMuted,
                        fontSize = 11.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                }

                if (loadingModels) {
                    Spacer(Modifier.height(16.dp))
                    Text(stringResource(R.string.local_model_loading), color = BrewMuted, fontSize = 13.sp)
                } else if (models.isEmpty() && serverUp) {
                    Text(
                        text = stringResource(R.string.local_model_list_empty),
                        color = BrewMuted,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                } else if (models.isNotEmpty()) {
                    models.forEach { m ->
                        ModelRow(
                            model = m,
                            isCurrent = m.name == chatModel && chatLocal,
                            enabled = !pulling,
                            onUse = { useAsChat(m) },
                            onDelete = { pendingDelete = m },
                        )
                    }
                }
            }
        }
    }

    // 删除模型确认弹窗
    pendingDelete?.let { m ->
        val sizeLabel = LocalOllamaManager.humanSize(m.size)
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            containerColor = BrewPanel,
            title = {
                Text(
                    stringResource(R.string.local_model_delete_title),
                    color = BrewTextBright,
                    fontWeight = FontWeight.Bold,
                )
            },
            text = {
                Text(
                    stringResource(
                        R.string.local_model_delete_msg,
                        m.name,
                        sizeLabel.ifBlank { "?" },
                    ),
                    color = BrewMuted,
                    fontSize = 14.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val target = m
                    pendingDelete = null
                    scope.launch {
                        val msg = withContext(Dispatchers.IO) {
                            runCatching { LocalOllamaManager.deleteModel(target.name) }
                                .fold(
                                    onSuccess = { ctx.getString(R.string.local_model_delete_ok, target.name) },
                                    onFailure = { e ->
                                        ctx.getString(R.string.local_model_delete_fail, e.message ?: "error")
                                    },
                                )
                        }
                        toast(msg)
                        checkServer()
                    }
                }) {
                    Text(
                        stringResource(R.string.local_model_delete),
                        color = BrewRed,
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

// ═══════════════ 内部小组件 ═══════════════

/** 统一圆角卡片容器 */
@Composable
private fun PageCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(BrewPanel)
            .border(1.dp, BrewBorder, RoundedCornerShape(16.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        content = content,
    )
}

/** 状态圆点：0=检测中(灰) 1=运行中(绿) 2=已停止(红) */
@Composable
private fun StatusDot(status: Int) {
    val color = when (status) {
        1 -> BrewSuccess
        2 -> BrewRed
        else -> BrewMuted
    }
    Box(
        Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(color),
    )
}

/** 已安装模型行：名称/参数信息 + 设为对话模型/删除 */
@Composable
private fun ModelRow(
    model: OllamaModel,
    isCurrent: Boolean,
    enabled: Boolean,
    onUse: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(BrewPanel)
            .border(1.dp, BrewBorder, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = model.name,
                color = BrewTextBright,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            if (isCurrent) {
                Text(
                    text = stringResource(R.string.local_model_use_chat),
                    color = BrewChat,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                )
            } else {
                TextButton(onClick = onUse, enabled = enabled, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                    Text(
                        stringResource(R.string.local_model_btn_use_chat),
                        color = if (enabled) BrewChat else BrewMuted,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
        val detail = listOf(
            model.family,
            model.parameterSize,
            model.quantization,
            LocalOllamaManager.humanSize(model.size),
        ).filter { it.isNotBlank() }.joinToString(" · ")
        Text(
            text = detail.ifBlank { "—" },
            color = BrewMuted,
            fontSize = 12.sp,
            maxLines = 1,
        )
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onDelete, enabled = enabled, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                Text(
                    stringResource(R.string.local_model_delete),
                    color = if (enabled) BrewRed else BrewMuted,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

/** 调参快捷项：标题 + 说明 + 选项 chips（点击即时生效） */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ParamChipRow(
    title: String,
    hint: String,
    items: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(title, color = BrewTextBright, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Text(hint, color = BrewMuted, fontSize = 11.sp)
        Spacer(Modifier.height(6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items.forEach { (v, label) ->
                SuggestChip(label, selected = v == selected) { onSelect(v) }
            }
        }
    }
}

/** 拉取模型输入区 + 进度条 */
@Composable
private fun PullSection(
    value: String,
    onValueChange: (String) -> Unit,
    pulling: Boolean,
    percent: Int,
    status: String,
    onPull: () -> Unit,
    enabled: Boolean,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(BrewPanelHi.copy(alpha = 0.5f))
            .border(1.dp, BrewBorder, RoundedCornerShape(12.dp))
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text(stringResource(R.string.local_model_pull_hint), color = BrewMuted, fontSize = 13.sp) },
                singleLine = true,
                textStyle = TextStyle(color = BrewTextBright, fontSize = 14.sp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { onPull() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = BrewChat,
                    unfocusedBorderColor = BrewBorder,
                    focusedTextColor = BrewTextBright,
                    unfocusedTextColor = BrewTextBright,
                    cursorColor = BrewChat,
                ),
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = onPull,
                enabled = enabled && value.isNotBlank(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = BrewChat, contentColor = BrewBg),
            ) {
                Text(stringResource(R.string.local_model_pull_start), fontWeight = FontWeight.Bold)
            }
        }
        if (pulling) {
            Spacer(Modifier.height(10.dp))
            // 自定义进度条（LinearProgressIndicator 样式与 Brew 主题不搭）
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(BrewPanelHi),
            ) {
                val p = if (percent >= 0) percent.coerceIn(0, 100) else 0
                Box(
                    Modifier
                        .fillMaxWidth(p / 100f)
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(BrewChat),
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = pullStatusLabel(status, percent),
                color = BrewMuted,
                fontSize = 12.sp,
            )
        }
    }
}

/** 拉取状态本地化（非下载阶段 percent=-1 只显示阶段文案） */
@Composable
private fun pullStatusLabel(status: String, percent: Int): String {
    val label = when {
        status.startsWith("downloading") -> stringResource(R.string.local_model_pull_status_download)
        status.startsWith("pulling") || status.startsWith("looking") -> stringResource(R.string.local_model_pull_status_manifest)
        status.startsWith("verifying") -> stringResource(R.string.local_model_pull_status_verify)
        status.startsWith("writing") -> stringResource(R.string.local_model_pull_status_write)
        else -> status
    }
    return if (percent >= 0) "$label $percent%" else label
}

/** 推荐模型小标签 */
@Composable
private fun SuggestChip(name: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = name,
        color = if (selected) BrewBg else BrewChat,
        fontSize = 12.sp,
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) BrewChat else BrewPanel)
            .border(1.dp, if (selected) BrewChat else BrewBorder, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}
