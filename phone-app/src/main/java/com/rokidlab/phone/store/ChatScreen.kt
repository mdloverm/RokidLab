package com.rokidlab.phone.store

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.PsychologyAlt
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.rokidlab.phone.R
import com.rokidlab.phone.ai.AgentSessionManager
import com.rokidlab.phone.ai.ContextUsage
import com.rokidlab.phone.aiui.AiuiDemoCard
import com.rokidlab.phone.aiui.AiuiDemoController
import com.rokidlab.phone.app.LabApplication
import com.rokidlab.phone.design.BrewBg
import com.rokidlab.phone.design.BrewBorder
import com.rokidlab.phone.design.BrewChat
import com.rokidlab.phone.design.BrewMuted
import com.rokidlab.phone.design.BrewPanel
import com.rokidlab.phone.design.BrewPanelHi
import com.rokidlab.phone.design.BrewRed
import com.rokidlab.phone.design.BrewShapeLarge
import com.rokidlab.phone.design.BrewTextBright
import com.rokidlab.phone.glasses.CxrLHiRokidSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "ChatScreen"
private const val CHAT_PREFS = "chat_prefs"
private const val KEY_DEEPSEEK = "deepseek_key"
/** 与 CxrLHiRokidSession.KEY_AI_THINKING 同键（shared 存储 chat_prefs），UI 直接持久化，无需等会话初始化 */
private const val KEY_AI_THINKING = "ai_thinking"
/** 思考深度（auto | deep | off）：auto=跟随既有安全默认（推理模型照常思考、普通模型显式关）；
 *  deep=开启长思考（放开大预算）；off=尽可能关闭。写时与旧布尔 ai_thinking 两键同步，
 *  于是 isThinkingEnabled() 的整条消费链路（AiConversationService → LlmRegistry.Options）零改动。 */
private const val KEY_AI_THINK_MODE = "ai_think_mode"

/** 读思考深度：优先 mode 串；老版本只有布尔 → true 迁移为 deep、false 迁移为 auto（行为不变） */
private fun readThinkMode(prefs: android.content.SharedPreferences): String {
    val mode = prefs.getString(KEY_AI_THINK_MODE, null)
    return when (mode) {
        "auto", "deep", "off" -> mode
        else -> if (prefs.getBoolean(KEY_AI_THINKING, false)) "deep" else "auto"
    }
}
/** 是否已在乐奇页就通知权限做过首次提示（只主动弹一次，拒绝后不再打扰） */
private const val KEY_NOTIF_PERM_PROMPTED = "notif_perm_prompted_for_lab"

/** 聊天消息 */
internal data class ChatMsg(
    val id: Long,
    val isUser: Boolean,
    val content: String,
    val time: String,
    val isStatus: Boolean = false,
    /** 非空表示这是一条图片消息：content 作 caption 渲染，imageUrl 由 [ChatBubble] 异步加载并显示 */
    val imageUrl: String? = null,
    /**
     * AI 回答的**过程**（思考 / 工具调用），渲染在正文之上的「过程」区块里。
     *
     * 为什么挂在同一条消息上而不是单独发一条：工具调用发生在正文之前（正文是工具循环跑完
     * 才流式生成的），若单独成条会插在正文前把「回答」和「问题」隔开，视觉上割裂；
     * 挂在消息上则天然随该轮回答一起滚动、一起落盘、一起被 [ChatStateHolder.finalizeLastAi] 收尾。
     *
     * 用户消息恒为空。空列表 = 该条没有过程可展示（普通闲聊、旧历史记录）。
     */
    val trace: List<com.rokidlab.phone.ai.AgentStep> = emptyList(),
    /**
     * 这一轮的 token 成本，展示在「过程」区块右下角（见 `TraceBlock`）。
     *
     * ★ 为什么挂在消息上而不是"要用时再查事件流"：过程区块是**逐条消息**渲染的，
     *   而"最近一轮的用量"只有一个 —— 拿它去填每条历史消息会显示**错误的数字**
     *   （旧消息被贴上新消息的成本）。成本必须跟着产生它的那一条走。
     *
     * ⚠️ 可空，含义是"**服务端没返回用量**"或"这条是旧记录"，不是 0。
     */
    val usage: MsgUsage? = null,
    /**
     * 这条消息属于**事件流的第几轮**（`null` = 旧记录，或这一轮没进事件流）。
     *
     * ★ 为什么需要它：会话记录图（节点连接图）是**事件流**的视图，而"编辑重发 / 删除"
     *   作用在**UI 消息**上。两边的条数**不天然相等** —— 拍照答题与定时自主任务传
     *   `recordHistory = false`，UI 有消息但事件流里没有对应轮次。
     *   ⇒ 按"第几条"去对齐会**错位改错条**（用户改一条、动的是另一条，静默且破坏性强）。
     *   带上轮号，映射就是精确的；找不到对应轮号时视图只给「复制」，不给编辑/删除。
     */
    val turn: Int? = null,
    /**
     * 本地文本附件（用户上传的文件、或 AI 用 `run_shell` 写出来的文件）：绝对路径。
     *
     * ★ 为什么存**路径**而不是正文：正文可能有十几万字，塞进历史 JSONL 会让聊天记录文件
     *   涨到几十 MB，而每次落盘/读取都要整条过一遍。正文留在磁盘上，卡片按需读。
     *   ⇒ 文件卡是"只读预览"，也刻意不做"在气泡里就地编辑"。
     *
     * ⚠️ 路径指向应用目录 / 共享目录（`Download/Lab`）。被用户手动清掉后卡片会显示"文件已不在"。
     */
    val filePath: String? = null,
    /** 附件显示名（带扩展名，卡片标题用） */
    val fileName: String? = null,
    /** 附件字符数（文件卡显示"约 N 字"；null = 未知，例如二进制或还没读过） */
    val fileChars: Int? = null,
)

/**
 * 一轮的 token 成本（面板展示用）。
 *
 * 三个字段**都可空**，含义一律是"不知道"：
 * - 服务端不支持 `stream_options.include_usage` 时不返回 token 数；
 * - 调用次数是我们自己的事实，通常有值。
 *
 * ⇒ 展示侧必须据此换口径（说"用量未知"），**不能兜成 0** —— 面板上写"输入 0 / 输出 0"
 *   是错误信息，比不显示更糟。
 */
internal data class MsgUsage(
    val inputTokens: Int?,
    /** 本轮**所有**模型调用的输入 token 合计（工具循环 = 多次请求，输入每轮都要重发，它才是大头） */
    val outputTokens: Int?,
    /** 本轮模型调用次数（>1 = 走了工具循环） */
    val modelCalls: Int?,
    val elapsedMs: Long?,
)

/**
 * 刚收尾那一轮的身份与成本：轮号 + 用量，一起落到那条 AI 消息上。
 *
 * 两者必须同源（同一次 `lastTurn` 快照）：分开取两次会在"取完轮号又结束了一轮"时错位，
 * 而错位的后果是**编辑/删除作用到别的消息上** —— 比数字错更严重。
 */
internal data class TurnStamp(val turn: Int, val usage: MsgUsage)

/**
 * 乐奇聊天 — 文字对话界面
 * 上方对话列表 + 下方输入框/发送按钮；进入时先弹出 DeepSeek API Key 设置对话框。
 */
@Composable
internal fun ChatModule(app: LabApplication) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences(CHAT_PREFS, Context.MODE_PRIVATE) }
    val savedKey = remember { prefs.getString(KEY_DEEPSEEK, "").orEmpty() }

    // 聊天消息状态由全局 ChatStateHolder 持有，切到其他页面再切回不会清空
    val messages = ChatStateHolder.messages
    // 会话列表与当前会话标题同源（ChatStateHolder.sessions + currentSessionId 都是可观察状态）：
    // 切会话、首条消息派生标题、重命名都会让标题栏自动重组
    val sessions = ChatStateHolder.sessions
    val currentSessionTitle = sessions
        .firstOrNull { it.id == ChatStateHolder.currentSessionId }
        ?.title
        ?: stringResource(R.string.chat_title)
    var input by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    // 「思考深度」：auto（默认）/ deep（深度思考）/ off —— 见 ThinkPickPanel 与 KEY_AI_THINK_MODE。
    // 默认 auto：既有安全行为（思考吞输出预算导致工具调用空轮的教训固化在默认档里）。
    var thinkMode by remember { mutableStateOf(readThinkMode(prefs)) }
    // 「拍照问 AI」流程进行中
    var photoAsking by remember { mutableStateOf(false) }
    // 知识库管理弹窗
    var showKbDialog by remember { mutableStateOf(false) }
    // AI 设置弹窗（AI 服务地址/密钥/模型 + 按键答题开关）
    var showSettings by remember { mutableStateOf(false) }
    // 「过程」区块默认展开（设置页里的「展开过程」，默认开）。
    // 设成状态而不是每次渲染都读 prefs：进入聊天页读一次，设置页关闭后再读一次即可。
    // ★ 它传下去的是**默认值**（不是快照）：卡片里"没被用户单独折过"的那些会实时跟随它，
    //   用户手动折过的那几张保持原样 —— 见 ChatBubble/TraceBlock 的两层状态。
    //   早先 TraceBlock 把这值当初始快照，导致"设置页改了、聊天窗口纹丝不动"（两处控件各说各的）。
    var expandTrace by remember { mutableStateOf(app.chatExpandTraceEnabled) }
    // 清空对话确认弹窗
    var showClearConfirm by remember { mutableStateOf(false) }
    // 会话列表弹窗（多会话：新建 / 切换 / 重命名 / 删除）
    var showSessions by remember { mutableStateOf(false) }
    // 对话记录图的目标会话（从会话列表进入；null = 未打开）
    var recordsTarget by remember { mutableStateOf<ChatSessionMeta?>(null) }
    // 正在编辑提示词的会话（从会话列表进入）
    var promptTarget by remember { mutableStateOf<ChatSessionMeta?>(null) }
    // 消息级操作弹层（点气泡内「···」触发：复制 / 编辑重发 / 重新生成 / 删除）
    var actionTarget by remember { mutableStateOf<ChatMsg?>(null) }
    // 正在编辑重发的消息（null = 未进入编辑态）。
    // 编辑**就地发生在底部输入框**里，不再弹独立窗口 —— 见 startEdit()
    var editingTarget by remember { mutableStateOf<ChatMsg?>(null) }
    // 进入编辑前的输入框草稿：取消编辑时原样还回去（用户可能正打到一半去点了气泡）
    var draftBeforeEdit by remember { mutableStateOf("") }
    // 进入编辑态时把焦点给输入框（否则键盘不弹，用户还得再点一下输入框）
    val inputFocus = remember { FocusRequester() }

    // ── 供应商 / 思考深度弹出面板 + 输入栏供应商图标 ──────────────
    // 面板互斥（同时只开一块），都以普通卡片形式插在输入栏上方（见 ChatBottomPanels）。
    var showProviderPanel by remember { mutableStateOf(false) }
    var showThinkPanel by remember { mutableStateOf(false) }
    // 从输入栏面板的「管理供应商」进入设置页时直落供应商子页
    var settingsOpenProvider by remember { mutableStateOf(false) }
    // 输入栏「发送给大模型」上拉菜单
    var showAttachSheet by remember { mutableStateOf(false) }
    // 待发送附件（选完先挂在这里，等用户写完问题一起发）
    var pendingFile by remember { mutableStateOf<PickedText?>(null) }
    var pendingImage by remember { mutableStateOf<PickedImage?>(null) }
    var pendingImageThumb by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var readingAttach by remember { mutableStateOf(false) }
    // 上一次真正发出去的附件：失败时不用重新去相册/文件里找一遍（发送失败在 Agent 场景里不罕见）
    var lastFile by remember { mutableStateOf<PickedText?>(null) }
    var lastImage by remember { mutableStateOf<PickedImage?>(null) }
    // 引用：把某条历史消息挂到下一轮当上下文（比"复制再粘贴"少一步，也不会丢原文）
    var quoted by remember { mutableStateOf<ChatMsg?>(null) }
    // 输入栏供应商图标显示谁：**已配置（在线槽位有密钥）时显示该品牌图标，否则默认云图标**。
    // null = 未配置 / 官方档 / 本地模型档 ⇒ 画默认图标。
    var activeProviderId by remember { mutableStateOf<String?>(null) }

    // ⚠️ 声明必须早于用到它的 LaunchedEffect（Kotlin 局部函数不能前向引用）
    fun refreshActiveProvider() {
        val sess = try { app.cxrL } catch (e: Exception) { null }
        val online = sess?.getOnlineAiConfig()
        activeProviderId = online
            ?.takeIf { it.apiKey.isNotBlank() }
            ?.let { com.rokidlab.phone.ai.llm.ProviderCatalog.matchBaseUrl(it.baseUrl)?.id }
    }

    // 放在 LaunchedEffect 里而不是 startEdit 里：requestFocus 要等这一帧重组完成才生效
    LaunchedEffect(editingTarget) {
        if (editingTarget != null) runCatching { inputFocus.requestFocus() }
    }
    // 上下文占用详情弹窗
    var showContextInfo by remember { mutableStateOf(false) }
    /** 执行轨迹视图（§4.3.5）：从上下文面板进入 */
    var showTrace by remember { mutableStateOf(false) }
    // 「本机模式」开关（不连眼镜也能聊）：状态源是 LabApplication（每次发送时读取），
    // 这里只做 UI 镜像，因此首次组合时取当前值即可。
    var localOnly by remember { mutableStateOf(app.chatLocalOnlyEnabled) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // ── AI 产出文件 → 聊天里的文件卡 ─────────────────────────────────
    // 容器写完盘由 ShellToolProvider 做「执行前后目录 diff」并推过来（见 LabFileOutputs）。
    // 注册在聊天页：没人看聊天时 sink 为空、静默丢弃 —— 这不是"丢数据"，
    // 文件本来就在磁盘上，卡片只是**这次会话里的可见性**。
    // ⚠️ 必须声明在 scope 之后（局部变量不能前向引用）。
    DisposableEffect(Unit) {
        com.rokidlab.phone.ai.LabFileOutputs.setSink { files ->
            // 回调来自工具线程：写 Compose 状态列表必须切主线程
            scope.launch {
                files.forEach { f ->
                    when (f.kind) {
                        // 图片：复用已有的图片卡（全屏预览 + 下载都是现成的）
                        com.rokidlab.phone.ai.OutputKind.IMAGE -> ChatStateHolder.addImage(
                            isUser = false,
                            imageUrl = "file://" + f.path,
                            caption = f.name,
                            turn = AgentSessionManager.currentTurn(),
                        )

                        // 文本 / 音视频：文件卡（音视频由卡片内部自动分流到播放器）
                        else -> ChatStateHolder.addFile(
                            isUser = false,
                            path = f.path,
                            name = f.name,
                            chars = f.chars,
                            caption = ctx.getString(R.string.chat_file_produced_caption),
                            turn = AgentSessionManager.currentTurn(),
                        )
                    }
                }
            }
        }
        onDispose { com.rokidlab.phone.ai.LabFileOutputs.setSink(null) }
    }

    // ── 附件（给大模型文件 / 图片）──────────────────────────────────
    // 选完不立刻发：挂成「待发送附件」，等用户写完问题一起发（见 PendingAttachBar）。
    // ⚠️ 必须声明在 scope 之后（Kotlin 局部变量/函数不能前向引用）。
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        // 类型闸门放在**读之前**：模型只能读文本，PDF/Word/压缩包解出来是乱码，
        // 发过去只会得到一段莫名其妙回答（用户会以为 AI 坏了）。当场说清楚"这类读不了"。
        val name = ChatAttachments.displayName(ctx, uri)
        val mime = runCatching { ctx.contentResolver.getType(uri) }.getOrNull()
        if (!ChatAttachments.isTextLike(name, mime)) {
            val ext = ChatAttachments.extOf(name).uppercase().ifBlank { name }
            Toast.makeText(
                ctx,
                ctx.getString(R.string.chat_attach_file_unsupported, ext),
                Toast.LENGTH_LONG,
            ).show()
            return@rememberLauncherForActivityResult
        }
        readingAttach = true
        scope.launch {
            val picked = withContext(Dispatchers.IO) {
                runCatching { ChatAttachments.readText(ctx, uri) }.getOrNull()
            }
            readingAttach = false
            if (picked == null) {
                Toast.makeText(ctx, ctx.getString(R.string.chat_attach_read_failed), Toast.LENGTH_SHORT).show()
            } else {
                pendingFile = picked
            }
        }
    }
    // 拍照：自己给一个可写 Uri（缺省 EXTRA_OUTPUT 时相机只回一张缩略图，发给模型等于没给）
    var cameraUri by remember { mutableStateOf<android.net.Uri?>(null) }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = cameraUri
        cameraUri = null
        if (!ok || uri == null) return@rememberLauncherForActivityResult
        readingAttach = true
        scope.launch {
            val picked = withContext(Dispatchers.IO) {
                runCatching { ChatAttachments.readImage(ctx, uri) }.getOrNull()
            }
            readingAttach = false
            if (picked == null) {
                Toast.makeText(ctx, ctx.getString(R.string.chat_attach_image_failed), Toast.LENGTH_SHORT).show()
            } else {
                pendingImage = picked
                pendingImageThumb = withContext(Dispatchers.IO) {
                    runCatching {
                        android.graphics.BitmapFactory.decodeFile(picked.fileUrl.removePrefix("file://"))
                    }.getOrNull()
                }
            }
        }
    }
    fun launchCamera() {
        val uri = ChatAttachments.newCameraOutput(ctx) ?: run {
            Toast.makeText(ctx, ctx.getString(R.string.chat_attach_image_failed), Toast.LENGTH_SHORT).show()
            return
        }
        cameraUri = uri
        runCatching { cameraLauncher.launch(uri) }
    }
    // 图片选择也走 OpenDocument（而不是 GetContent）：只有它支持**多 MIME 数组**，
    // 这样才能把可选范围收窄到"真能解码的格式"；GetContent 只吃单个 MIME 字符串，
    // 只能传 `image/*`，会把 heic/svg 这类解不出来的也放进来。
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        readingAttach = true
        scope.launch {
            val picked = withContext(Dispatchers.IO) {
                runCatching { ChatAttachments.readImage(ctx, uri) }.getOrNull()
            }
            readingAttach = false
            if (picked == null) {
                Toast.makeText(ctx, ctx.getString(R.string.chat_attach_image_failed), Toast.LENGTH_SHORT).show()
            } else {
                pendingImage = picked
                pendingImageThumb = withContext(Dispatchers.IO) {
                    runCatching {
                        android.graphics.BitmapFactory.decodeFile(picked.fileUrl.removePrefix("file://"))
                    }.getOrNull()
                }
            }
        }
    }
    // 上下文占用：消息列表变化或「本轮回答结束」时重算 —— recordTurn 发生在回答结束之后，
    // 所以 sending 由 true 变回 false 也算一个刷新触发点，否则进度条会慢一整轮。
    // 位置必须在 sending 声明之后（这里），放在消息列表旁边会读不到它。
    // contextRefresh：上下文面板里点「立即压缩」后手动触发重算 —— 压缩**不改消息列表**，
    // 前两个 key 都不会变，不补一个计数器的话面板会停在压缩前的数字上。
    var contextRefresh by remember { mutableStateOf(0) }
    val contextUsage = remember(messages.size, sending, contextRefresh) {
        runCatching { AgentSessionManager.contextUsage(ctx) }.getOrNull()
    }
    // 设置页关闭后重读一次：
    //  ①「展开过程」是设置页里的开关，改完回来要立刻生效；
    //  ② 设置页里的「清空全部会话记忆」**不改消息列表** —— 上面 remember 的三个 key 一个都不变，
    //    不补这次重算，进度条会停在清空前的数字上（用户会以为没清干净）。
    // ⚠️ 必须写在 contextRefresh 声明之后：Kotlin 局部变量不能前向引用。
    LaunchedEffect(showSettings) {
        if (!showSettings) {
            expandTrace = app.chatExpandTraceEnabled
            contextRefresh++
            // ③ 设置页里可能换了供应商，输入栏那个供应商图标跟着换
            refreshActiveProvider()
        }
    }

    // 同步 AI 配置到会话（兼容旧版 deepseek_key 迁移：只回填在线槽位 Key，不影响本地模型模式）
    LaunchedEffect(Unit) {
        try {
            val session = app.cxrL
            val online = session.getOnlineAiConfig()
            if (online.apiKey.isBlank() && savedKey.isNotBlank()) {
                session.backfillOnlineApiKey(savedKey)
            }
        } catch (e: Exception) {
            Log.e(TAG, "cxrL not ready", e)
        }
    }

    // 离开乐奇聊天页（切换页签 / 系统返回）＝ 退出助手：停眼镜端播报 + 取消在跑的 Lab 请求。
    //
    // 为什么补这一处：本页是助手的唯一入口，而页面由 StoreHomeScreen 的
    // AnimatedContent(targetState = currentPage) 承载 —— 切走即销毁本组合。
    // 此前只有「停止按钮 / 眼镜端双击 / 会话 cleanup」三条路会停播报，
    // 从本页退出（页签切换、系统返回）**一条都不走**：2026-09-21 实测用户在播报中途退出，
    // 眼镜侧既无 `Received tts_stop` 也无 ABORT 标记，语音一路播到自然结束。
    //
    // 与「停止按钮」同语义：abortCurrentAi() = bump 代际（在跑的请求自弃）+ 下发 tts_stop。
    // 必须放后台线程：sendCustomCmd 是跨进程调用，主线程里做会卡帧。
    DisposableEffect(Unit) {
        onDispose {
            runCatching {
                val sess = app.cxrL
                scope.launch(Dispatchers.IO) { sess.abortCurrentAi() }
            }.onFailure { Log.w(TAG, "onDispose stop glasses speech failed", it) }
        }
    }

    // ── 首次进入乐奇：检测通知权限（状态栏音乐播放器 / 眼镜歌词 / 定时提醒依赖它）──
    // App 启动时已统一请求过一次（无上下文，易被拒）；这里是带用途说明的二次机会，只主动弹一次。
    var showNotifPermDialog by remember { mutableStateOf(false) }
    // 系统权限弹窗已拒过一次：确认按钮从「去开启」切换为「去系统设置开启」
    // （POST_NOTIFICATIONS 被拒过后再次请求，多数 ROM 直接回调拒绝不再弹系统框）
    var notifPermNeedSettings by remember { mutableStateOf(false) }
    fun hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    fun openAppNotificationSettings() {
        runCatching {
            ctx.startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure { Log.w(TAG, "open notification settings failed", it) }
    }

    val notifPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            showNotifPermDialog = false
            prefs.edit().putBoolean(KEY_NOTIF_PERM_PROMPTED, true).apply()
        } else {
            // 留在说明框，确认按钮切换为跳系统设置
            notifPermNeedSettings = true
        }
    }

    LaunchedEffect(Unit) {
        if (!hasNotificationPermission() && !prefs.getBoolean(KEY_NOTIF_PERM_PROMPTED, false)) {
            showNotifPermDialog = true
        }
    }

    fun appendMsg(isUser: Boolean, content: String, isStatus: Boolean = false, turn: Int? = null) {
        ChatStateHolder.add(isUser, content, isStatus, turn = turn)
        scope.launch {
            try {
                listState.animateScrollToItem((ChatStateHolder.messages.size - 1).coerceAtLeast(0))
            } catch (_: Exception) {
            }
        }
    }

    /** 原地更新末尾状态气泡（OCR 模型下载百分比等进度），不新增消息 */
    fun updateStatus(content: String) {
        ChatStateHolder.updateLastStatus(content)
    }

    /** 切思考深度：mode 串 + 旧布尔两键同步写，isThinkingEnabled() 的消费方零改动 */
    fun setThinkMode(mode: String) {
        thinkMode = mode
        prefs.edit()
            .putString(KEY_AI_THINK_MODE, mode)
            .putBoolean(KEY_AI_THINKING, mode == "deep")
            .apply()
        Log.i(TAG, "AI thinking mode = $mode")
    }

    // ── 供应商 / 思考深度弹出面板 + 输入栏供应商图标 ──────────────
    // 面板互斥（同时只开一块），都以普通卡片形式插在输入栏上方（见 ChatBottomPanels）。
    // 状态与 refreshActiveProvider 声明在本文件靠前处（局部函数不能前向引用）。
    LaunchedEffect(Unit) { refreshActiveProvider() }

    /**
     * 刚收尾那一轮的**轮号 + token 成本**，一起落到那条 AI 消息上。
     *
     * ★ 为什么必须用 [turnBefore] 闸门，而不是直接读"最近一轮"：
     *   不是每一轮都会进事件流 —— 拍照答题与定时自主任务传 `recordHistory = false`
     *   （一次性问答，不该挤占主对话上下文），它们**没有轮号**。此时"最近一轮"仍然是
     *   **上一轮**，直接读到就会把上一轮的东西贴到这条回复上：
     *   用量变成错误信息（写"输入 0"比不写更糟），**轮号更危险** —— 它会让人在会话记录图里
     *   点"编辑/删除"时改到**另一条消息**上（用户改一条、动的是别的，静默且不可逆）。
     *   ⇒ 轮号没涨 = 这一轮不在事件流里 ⇒ 返回 null，视图显示"用量未知"且只给复制。
     *   闸门的方向是**宁可说不知道，也不给错数字/错映射**。
     *
     * 读的是事件流的**缓存投影**（同上方的 `contextUsage`），主线程调用没问题；
     * 打成聊天记录文件的那次读盘只发生在投影失效后的第一次。
     *
     * ⚠️ 声明位置必须在 [dispatchAi] **之前**：Kotlin 的局部函数不能前向引用。
     */
    fun currentTurnStamp(turnBefore: Int): TurnStamp? {
        val snap = runCatching { AgentSessionManager.contextUsage(ctx).lastTurn }.getOrNull() ?: return null
        if (snap.turn <= turnBefore) return null
        return TurnStamp(
            turn = snap.turn,
            usage = MsgUsage(
                inputTokens = snap.inputTokens,
                outputTokens = snap.outputTokens,
                modelCalls = snap.modelCalls,
                elapsedMs = snap.elapsedMs,
            ),
        )
    }

    /**
     * 实际发起一轮 AI 请求（用户气泡与会话记忆的预处理由调用方负责）。
     *
     * 从 [send] 里拆出来是因为「重新生成」「编辑重发」要复用整条请求链路，
     * 但各自的预处理不同：前者不动气泡，后者要先用新内容替换掉旧的那条。
     *
     * @param turnBefore 发起**之前**的轮号（调用方在加用户气泡之前取，见 [currentTurnStamp]）。
     *   由调用方传入而不是在这里取：调用方添加用户消息时需要知道"这一轮会是几号"，
     *   而 `beginTurn` 是在服务层异步执行的 —— 轮号是 `turnBefore + 1`，只能在派发前定下。
     */
    fun dispatchAi(
        text: String,
        turnBefore: Int,
        /** 随本轮一起注入的额外上下文（当前用于「用户上传的文件」正文） */
        contextText: String? = null,
        /** 多模态图片（JPEG base64，不带 data: 前缀） */
        imageBase64: String? = null,
    ) {
        if (sending) return
        sending = true
        val session = try {
            app.cxrL
        } catch (e: Exception) {
            Log.e(TAG, "cxrL not initialized", e)
            null
        }
        if (session == null) {
            sending = false
            appendMsg(false, ctx.getString(R.string.chat_send_failed))
            return
        }
        // sendAiTextMessage 内含多次 Thread.sleep + join（最长可阻塞 30s），
        // 必须在后台线程执行，否则阻塞主线程导致 ANR/闪退
        scope.launch(Dispatchers.IO) {
            try {
                session.sendAiTextMessage(
                    text,
                    contextText = contextText,
                    imageBase64 = imageBase64,
                    // 保留回复文字在眼镜上的显示：不发 TTS_AudioFinished（避免官方会话
                    // 在长语音播完前被 AudioFinishedHandler→startNewTalk 重置清屏）
                    skipTtsAudioFinished = true,
                    onResult = { success, err ->
                        // onResult 由会话层 runOnUiThread 回调，已在主线程
                        sending = false
                        // 用户停止/被新请求抢占时 success=false 且 err=null：静默，不显示失败气泡
                        if (!success && !err.isNullOrBlank()) {
                            appendMsg(false, ctx.getString(R.string.chat_reply_failed) + ": $err")
                        }
                        // 「过程」卡片的收尾已由会话层统一负责（sendAiTextMessage 入口包装 onResult +
                        // 抢占/中止两处兜底），此处不再重复 —— 见 AiConversationService.setAgentTraceSink
                    },
                    onReply = { reply ->
                        // 流式 onDelta 已边生成边显示，此处用完整回复修正最后一条 AI 消息并落盘；
                        // 若流式未触发（如兜底路径）则 finalizeLastAi 内部会新增一条。
                        // 用量与轮号一起落在这条消息上（onReply 在子线程，读事件流缓存投影即可）
                        val stamp = currentTurnStamp(turnBefore)
                        scope.launch { ChatStateHolder.finalizeLastAi(reply, stamp?.usage, stamp?.turn) }
                    },
                    onDelta = { delta ->
                        // 流式增量：边生成边显示（切主线程，SnapshotStateList 写入需 Compose 快照线程）
                        scope.launch { ChatStateHolder.appendAiDelta(delta) }
                    },
                    // 不传 onTrace：「过程」（思考中 / 调用了哪个工具 / 结果如何）走 App 级全局
                    // 汇聚点（LabApplication.setCxrL 里注册），与眼镜语音/拍照答题共用同一条通路。
                    // 曾按调用逐条传参，结果漏掉眼镜语音那条入口 —— 过程通道不该靠"记得传"。
                    // 本会话的附加提示词：每轮现读（用户在设置里改了/切了会话，下一句就生效）
                    sessionPrompt = ChatStateHolder.currentSessionPrompt(),
                )
            } catch (e: Exception) {
                // 异常路径必须复位 sending，否则发送按钮永久卡死
                Log.e(TAG, "sendAiTextMessage crashed", e)
                scope.launch {
                    sending = false
                    ChatStateHolder.finishTrace(failed = true)
                    appendMsg(false, ctx.getString(R.string.chat_reply_failed) + ": ${e.message}")
                }
            }
        }
    }

    /**
     * 编辑重发：用新内容替换那条用户消息，并清掉它之后的所有内容（回退重来）。
     *
     * 截断后必须 [AgentSessionManager.dropLastTurn]，让记忆与被截断的 UI 历史保持一致 ——
     * 否则接下来这一轮会把已经不存在的旧对话带给模型。
     *
     * ⚠️ 声明位置必须在 [send] **之前**：Kotlin 的局部函数不能前向引用
     * （同文件里 `send` 要调它、它又要调 `dispatchAi`，所以只能排在 `dispatchAi` 和 `send` 之间）。
     */
    fun resendEdited(target: ChatMsg, newText: String) {
        if (sending) return
        val text = newText.trim()
        if (text.isBlank()) return
        // 轮号同样要在做任何改动**之前**取（它必须反映"派发前的状态"）
        val turnBefore = AgentSessionManager.currentTurn()
        ChatStateHolder.truncateFrom(target.id)
        AgentSessionManager.dropLastTurn()
        appendMsg(true, text, turn = turnBefore + 1)
        dispatchAi(text, turnBefore)
    }

    /**
     * 带附件发送：图片走多模态分片、文件正文注入上下文（两者可同时带）。
     *
     * 几个刻意的取舍：
     *  - **图 + 文**：用户只选了图没写字时补一句默认提问，否则模型收到一张图却没有指令
     *    （「这是什么」这种默认语在多数模型上比空文本稳）。
     *  - **文件不塞进气泡正文**：几万字往气泡里一放，聊天列表就没法看了；正文只进上下文，
     *    气泡里显示"📄 文件名"级别的说明（用户仍能从附件条看到自己发了什么）。
     *  - **发完即清附件状态**，但把这一份记到 [lastFile]/[lastImage] —— 失败时面板里有
     *    「重发上次附件」一键还原，不用再翻一次相册/文件管理器。
     *
     * ⚠️ 必须声明在 [send] **之前**（Kotlin 局部函数不能前向引用）。
     */
    fun sendWithAttachments() {
        if (sending) return
        val img = pendingImage
        val file = pendingFile
        val quote = quoted
        val typed = input.trim()
        val text = typed.ifBlank {
            when {
                img != null -> ctx.getString(R.string.chat_attach_image_default_ask)
                file != null -> ctx.getString(R.string.chat_attach_file_default_ask)
                quote != null -> ctx.getString(R.string.chat_quote_only_send)
                else -> ""
            }
        }
        if (text.isBlank()) return
        val turnBefore = AgentSessionManager.currentTurn()
        // 气泡：图片消息用图片卡片（caption = 用户文字，**带轮号** ⇒ 也能编辑重发）；
        // 文件消息用文件卡（只读，正文在磁盘上）；引用消息用文字气泡
        when {
            img != null -> ChatStateHolder.addImage(true, img.fileUrl, text, turn = turnBefore + 1)
            file != null && file.savedPath != null -> ChatStateHolder.addFile(
                isUser = true,
                path = file.savedPath,
                name = file.name,
                chars = file.text.length,
                caption = text,
                turn = turnBefore + 1,
            )

            else -> appendMsg(true, text, turn = turnBefore + 1)
        }
        // 上下文：文件正文 + 引用原文（都走同一个「额外上下文」槽位，服务层会与知识库结果合并）
        val context = buildString {
            file?.let { append(ctx.getString(R.string.chat_attach_context_header, it.name) + "\n" + it.text) }
            quote?.let {
                if (isNotEmpty()) append("\n\n")
                append(ctx.getString(R.string.chat_quote_context_header))
                append("\n")
                append(it.content)
            }
        }.ifBlank { null }
        lastFile = file
        lastImage = img
        pendingImage = null
        pendingImageThumb = null
        pendingFile = null
        quoted = null
        input = ""
        dispatchAi(text, turnBefore, contextText = context, imageBase64 = img?.base64Jpeg)
    }

    /** 发送输入框内容（回车 / 发送按钮）。编辑态下发送 = 编辑重发 */
    fun send() {
        val target = editingTarget
        // 编辑重发不带附件/引用（改的是历史里那条文字）；附件只在"新发一条"时生效
        if (target == null && (pendingFile != null || pendingImage != null || quoted != null)) {
            sendWithAttachments()
            return
        }
        val text = input.trim()
        if (text.isEmpty() || sending) return
        if (target != null) {
            editingTarget = null
            draftBeforeEdit = ""
            input = ""
            resendEdited(target, text)
            return
        }
        // 轮号必须在加气泡**之前**取：这一轮会是 turnBefore + 1（beginTurn 在服务层异步执行）
        val turnBefore = AgentSessionManager.currentTurn()
        appendMsg(true, text, turn = turnBefore + 1)
        input = ""
        dispatchAi(text, turnBefore)
    }

    /**
     * 重新生成：删掉这条 AI 回复，用它上面的那条用户提问重新问一次。
     *
     * 同时丢掉会话记忆里的最后一轮 —— 否则模型会在上下文里看到自己**上一版答案**，
     * 结果要么照抄要么刻意绕开，都不是"重新生成"该有的行为。
     */
    fun regenerate(msg: ChatMsg) {
        if (sending) return
        val list = ChatStateHolder.messages
        val idx = list.indexOfFirst { it.id == msg.id }
        if (idx < 0) return
        val ask = list.take(idx).lastOrNull { it.isUser && !it.isStatus } ?: return
        val turnBefore = AgentSessionManager.currentTurn()
        ChatStateHolder.deleteMessage(msg.id)
        AgentSessionManager.dropLastTurn()
        // 复用原来那条用户消息 ⇒ 把它的轮号改成新的一轮：不然记录图里这一轮的用户消息
        // 永远找不到（它的旧轮号已作废），"编辑/删除"会退化到只给复制。
        ChatStateHolder.setMessageTurn(ask.id, turnBefore + 1)
        dispatchAi(ask.content, turnBefore)
    }

    /**
     * 进入编辑态：把这条用户消息放进**底部输入框**改。
     *
     * ★ 为什么不再弹独立对话框（用户明确要求）：编辑重发与"重新说一遍"是**同一次输入动作**，
     *   另开一个窗口意味着用户要在两个文本框之间切；更糟的是弹窗会盖住上下文 ——
     *   而用户恰恰是**看着上下文**才决定要改哪一句的。
     *
     * 进入前会暂存当前草稿（[draftBeforeEdit]），取消时原样还回去：用户可能正打到一半
     * 才去点了气泡，直接清空输入框等于把没发出去的内容弄丢了。
     */
    fun startEdit(target: ChatMsg) {
        if (sending) return
        if (editingTarget == null) draftBeforeEdit = input
        editingTarget = target
        input = target.content
    }

    /** 退出编辑态并恢复进入前的草稿 */
    fun cancelEdit() {
        editingTarget = null
        input = draftBeforeEdit
        draftBeforeEdit = ""
    }

    /** 复制消息正文到剪贴板 */
    fun copyText(text: String) {
        if (text.isBlank()) return
        runCatching {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            cm?.setPrimaryClip(ClipData.newPlainText("leqi", text))
            Toast.makeText(ctx, ctx.getString(R.string.chat_msg_copied), Toast.LENGTH_SHORT).show()
        }.onFailure { Log.w(TAG, "copy failed", it) }
    }

    /** 这条是不是「最后一条 AI 消息」—— 只有它才允许重新生成 */
    fun isLastAiMessage(msg: ChatMsg): Boolean {
        val last = ChatStateHolder.messages.lastOrNull { !it.isStatus }
        return last != null && last.id == msg.id && !msg.isUser
    }

    /**
     * 拍照问 AI：眼镜拍照 → 本地 OCR 识别题目文字 → 知识库检索（RAG）→ AI 生成答案。
     * 复用会话层 startPhotoAsk 公共流程（手机按钮 / 镜腿按键共用入口）。
     */
    fun askPhotoAi() {
        if (photoAsking) return
        val session = try {
            app.cxrL
        } catch (e: Exception) {
            Log.e(TAG, "cxrL not initialized", e)
            null
        }
        if (session == null) {
            appendMsg(false, ctx.getString(R.string.chat_photo_failed), isStatus = true)
            return
        }
        photoAsking = true
        session.startPhotoAsk(
            onStage = { resId ->
                appendMsg(false, ctx.getString(resId), isStatus = true)
                // 失败 / 未识别出文字等终态时复位
                if (resId == R.string.chat_photo_failed || resId == R.string.chat_ocr_empty) {
                    photoAsking = false
                }
            },
            onText = { text -> appendMsg(true, text) },
            onReply = { reply ->
                // startPhotoAsk 内部在后台线程回调，需切回主线程更新 Compose 状态
                scope.launch {
                    photoAsking = false
                    // 与眼镜语音同理：本轮若产生了「过程」就合并到同一条消息，否则新起一条
                    ChatStateHolder.finalizeTraceReply(reply)
                }
            },
            onStageText = { text -> updateStatus(text) },
        )
    }

    // 注册「拍照问 AI」默认 UI 回调：镜腿按键触发的 startPhotoAsk（无显式回调）
    // 也展示流程气泡、识别文字与最终答案到聊天界面。
    LaunchedEffect(Unit) {
        try {
            app.cxrL.setPhotoAskUiCallbacks(
                onStage = { resId ->
                    appendMsg(false, ctx.getString(resId), isStatus = true)
                    if (resId == R.string.chat_photo_failed || resId == R.string.chat_ocr_empty) {
                        photoAsking = false
                    }
                },
                onText = { text -> appendMsg(true, text) },
                onReply = { reply ->
                    scope.launch {
                        photoAsking = false
                        ChatStateHolder.finalizeTraceReply(reply)
                    }
                },
                onStageText = { text -> updateStatus(text) },
            )
        } catch (e: Exception) {
            Log.e(TAG, "cxrL not ready", e)
        }
    }

    /**
     * 眼镜语音这一轮的「派发前轮号」。
     *
     * 眼镜语音不是界面发起的，拿不到 [dispatchAi] 里那样的局部变量，所以用一个可变的记住值：
     * ASR 文字回调（`onText`）发生在 `sendAiTextMessage` **之前**，也就是轮次开始之前
     * （`AiConversationService.dispatchGlassesAsrText` 的顺序保证了这一点），在那里记下正好。
     */
    val glassesTurnMark = remember { java.util.concurrent.atomic.AtomicInteger(0) }

    // 注册眼镜端语音对话（唤醒词 ASR）默认 UI 回调：把眼镜上的提问与 Lab 回复同步到聊天窗口。
    LaunchedEffect(Unit) {
        try {
            app.cxrL.setGlassesAiUiCallbacks(
                onText = { text ->
                    glassesTurnMark.set(AgentSessionManager.currentTurn())
                    appendMsg(true, text)
                },
                // 合并进本轮那条带「过程」卡片的 AI 消息（而不是另起一条）：眼镜语音的过程
                // 与回答因此落在同一个气泡组里，和打字路径的观感一致
                onReply = { reply ->
                    val stamp = currentTurnStamp(glassesTurnMark.get())
                    ChatStateHolder.finalizeTraceReply(reply, stamp?.usage, stamp?.turn)
                },
            )
        } catch (e: Exception) {
            Log.e(TAG, "cxrL not ready", e)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BrewBg)
            .imePadding()
            .navigationBarsPadding(),
    ) {
        ChatHeader(
            onOpenSettings = { showSettings = true },
            onToggleLocalOnly = {
                // 立即落盘 + 更新内存标志：开关按「每次发送时读取」实现，
                // 因此下一步发消息就生效，不用重连、也不会打断正在进行的对话。
                val next = !localOnly
                localOnly = next
                app.setChatLocalOnlyEnabled(next)
            },
            onClearChat = {
                if (ChatStateHolder.messages.isNotEmpty()) showClearConfirm = true
            },
            localOnly = localOnly,
            sessionTitle = currentSessionTitle,
            onOpenSessions = { showSessions = true },
        )

        // 手机端 AIUI 演示卡片是否在场（`open_aiui_app(target=phone)` 触发的 UI 副作用）。
        // 在这里读一次快照状态，让整个对话页随它重组 —— 卡片本身是消息列表里的一项，
        // 不在这里读的话 LazyColumn 的 item 集合不会跟着变。
        val aiuiDemoShowing = AiuiDemoController.current != null

        // 卡片排在最后一条消息下方，出现时很可能落在屏幕外 —— 滚到底把它露出来，
        // 否则用户的观感是"说了演示，屏幕上什么都没有"。
        LaunchedEffect(aiuiDemoShowing) {
            if (aiuiDemoShowing) {
                runCatching { listState.animateScrollToItem(messages.size) }
            }
        }

        if (messages.isEmpty() && !aiuiDemoShowing) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.chat_empty_hint),
                    color = BrewMuted,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(36.dp),
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                // 水平 16dp：与顶栏、输入区统一（UI-DESIGN.md §1.4「页面水平 padding 16dp」），
                // 三处各写一个数字是这类"看起来差一点"的根源
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(messages, key = { it.id }) { msg ->
                    ChatBubble(
                        msg,
                        onActions = { actionTarget = msg },
                        // 用户发言**点一下就是编辑重发**（以前要先点「···」再选「编辑重发」）：
                        // 用户对一条自己说过的话最常做的动作就是"改一下再说一遍"。
                        // 发送中不给点 —— 编辑重发会截断历史，与在飞的请求冲突。
                        // 正文在 SelectionContainer 里（长按走"选字"），所以这里只挂单击，不挂长按。
                        onClickUser = if (msg.isUser && !msg.isStatus && !sending) {
                            { startEdit(msg) }
                        } else {
                            null
                        },
                        expandTrace = expandTrace,
                    )
                }
                // AIUI 演示卡片：排在消息流末尾 = AI 那条回复的正下方。它是工具触发的
                // UI 副作用（见 AiuiDemoController），**不进消息历史**，不在演示时零节点。
                if (aiuiDemoShowing) {
                    item(key = "aiui-demo") { AiuiDemoCard() }
                }
            }
        }

        // 上下文占用条（常驻细条）：把原本完全静默的上下文裁剪变成可归因的可见状态。
        // 会话记忆关闭时组件内部自行不渲染（见 ChatContextBar）
        contextUsage?.let { usage ->
            ChatContextBar(usage = usage, onClick = { showContextInfo = true })
        }

        // 编辑态横幅：说明"正在编辑哪一条、其后的对话会被清掉"，并给一个退出口。
        // ★ 它替代了原来的独立编辑弹窗 —— 编辑就在下面这个输入框里发生（见 startEdit）。
        editingTarget?.let {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // ⚠️ 不能写 `padding(horizontal = …, top = …)` —— 没有这个重载
                    .padding(start = 12.dp, end = 12.dp, top = 8.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(BrewPanel)
                    .border(1.dp, BrewBorder, RoundedCornerShape(10.dp))
                    .padding(start = 10.dp, end = 4.dp, top = 5.dp, bottom = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.Edit,
                    contentDescription = null,
                    tint = BrewChat,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(7.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.chat_msg_edit_title),
                        color = BrewTextBright,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = stringResource(R.string.chat_msg_edit_hint),
                        color = BrewMuted,
                        fontSize = 11.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                TextButton(onClick = { cancelEdit() }) {
                    Text(
                        text = stringResource(R.string.chat_common_cancel),
                        color = BrewMuted,
                        fontSize = 12.sp,
                    )
                }
            }
        }

        // 本机网页预览条：容器里有常驻网页服务时显示（点击打开内嵌 WebView 预览）。
        // 数据源是 WebPreviewManager.previews（StateFlow），无服务时整体不渲染。
        WebPreviewBar()

        // 供应商 / 思考深度弹出面板：改为 ModalBottomSheet 上拉层（2026-09-25，与附件菜单同款
        // 交互——点遮罩关闭、从底部滑入），不再以卡片形式插在输入栏上方。两者互斥，同时只开一块。
        if (showProviderPanel) {
            ProviderPickPanel(
                app = app,
                onSwitched = { name, model ->
                    showProviderPanel = false
                    refreshActiveProvider()
                    Toast.makeText(
                        ctx,
                        ctx.getString(R.string.chat_provider_switched, "$name · $model"),
                        Toast.LENGTH_SHORT,
                    ).show()
                },
                onManage = {
                    showProviderPanel = false
                    settingsOpenProvider = true
                    showSettings = true
                },
                onDismiss = { showProviderPanel = false },
            )
        }
        if (showThinkPanel) {
            ThinkPickPanel(
                current = thinkMode,
                onSelect = { mode ->
                    setThinkMode(mode)
                    showThinkPanel = false
                },
                onDismiss = { showThinkPanel = false },
            )
        }

        // 引用条（把某条历史消息挂到下一轮当上下文）
        quoted?.let { q ->
            PendingAttachBar(
                fileName = null,
                fileTruncated = false,
                imageThumb = null,
                imageName = null,
                quoteText = q.content,
                quoteFromUser = q.isUser,
                onRemoveFile = {},
                onRemoveImage = {},
                onRemoveQuote = { quoted = null },
            )
        }

        // 待发送附件提示条（选了文件/图片但还没发）
        if (pendingFile != null || pendingImageThumb != null) {
            // 上下文占比：文件正文字数 / 当前模型的上下文窗口（同一份能力结论驱动压缩阈值，
            // 这里只是把它提前显示给用户 —— 免得"发完才发现被截断"）
            val ctxWindow = com.rokidlab.phone.ai.llm.LlmRegistry
                .capabilities(
                    try { app.cxrL.getAiConfig() } catch (e: Exception) {
                        com.rokidlab.phone.domain.AiConfig()
                    },
                    ctx,
                )
                .contextWindow
            PendingAttachBar(
                fileName = pendingFile?.name,
                fileTruncated = pendingFile?.truncated == true,
                fileChars = pendingFile?.text?.length,
                contextWindow = ctxWindow,
                imageThumb = pendingImageThumb,
                imageName = pendingImage?.let { ctx.getString(R.string.chat_attach_image) },
                quoteText = null,
                quoteFromUser = false,
                onRemoveFile = { pendingFile = null },
                onRemoveImage = {
                    pendingImage = null
                    pendingImageThumb = null
                },
                onRemoveQuote = {},
            )
        }

        // 底部输入区：上层 = 文字输入；下层 = 工具行（供应商 / 思考 / 拍照 / 知识库 / 附件 / 发送）
        //
        // ⚠️ 尺寸必须**对齐 UI-DESIGN.md §1.4 的全局规范**，别再自己拍数字：
        //    · 页面水平 padding **16dp**（原先输入区 14 / 消息列表 14 / 顶栏 16 三套并存 ⇒ 已统一）
        //    · 圆角走刻度 `BrewShapeLarge(16dp)`、按钮 `BrewShapeStandard(12dp)`（别硬编码数字，
        //      刻度一改全站跟着走；上一版手写的 39dp 是刻度外的野值）
        //    · 边框**全站统一 1dp**（1.5x 那版写了 1.5dp，违反规范）
        //    · 内边距 16dp
        // ⚠️ 2026-09-24 两轮才收敛的教训：字号 + 圆角 + 按钮尺寸**别同时放大**，
        //    22sp + 39dp + 45/57dp 叠起来就是"膨胀的胶囊"（用户原话"又大了"）。
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 16.dp)
                // 顺序很重要：clip 必须在 background/border **之前**。
                // 旧顺序先画了方形背景再 clip，背景不被裁剪 → 圆角外露出灰色方角。
                .clip(BrewShapeLarge)
                .background(BrewPanel)
                .border(1.dp, BrewBorder, BrewShapeLarge)
                .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        ) {
            // 上层：文字输入占满整行
            // 字号 18sp（2026-09-25 用户指定）：输入区是当前焦点，明显大于正文（14sp）是
            // 刻意的层级表达；行高按 1.4 倍给 25sp，多行时不会挤。
            // 最小高度 38dp（2026-09-25 用户反馈"输入窗口也提高一点"）：单行时只有 ~25sp 高，
            // 比下面 38dp 的工具行薄一截，视觉上不协调 ⇒ 与工具行按钮同高起底，单行垂直居中
            BasicTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 38.dp)
                    // 进入编辑态时自动聚焦（否则键盘不弹，用户还得再点一下输入框）
                    .focusRequester(inputFocus),
                textStyle = TextStyle(color = BrewTextBright, fontSize = 18.sp, lineHeight = 25.sp),
                cursorBrush = SolidColor(BrewChat),
                singleLine = false,
                maxLines = 4,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
                decorationBox = { inner ->
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        // 单行（≤最小高度）时居中；多行后内容撑满，对齐方式不再起作用
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        if (input.isEmpty()) {
                            Text(
                                text = stringResource(R.string.chat_input_hint),
                                color = BrewMuted,
                                fontSize = 18.sp,
                            )
                        }
                        inner()
                    }
                },
            )
            Spacer(Modifier.height(8.dp))
            // 下层工具行：左＝供应商图标（已配置则画该品牌图标，未配置默认云图标）+ 思考深度（默认自动）
            // + 原顶部标题栏的四个功能图标（本机模式 / 拍照 / 知识库 / 清空，风格与左边两个统一）；
            // 右＝附件（上拉菜单：文件 / 图片）+ 发送/停止（纯图标圆钮）
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 供应商图标：已配置在线槽位（有密钥）时显示品牌图标，否则默认云图标
                val providerRes = activeProviderId?.let { ProviderIcons.resOf(it) } ?: 0
                val providerAccent =
                    activeProviderId?.let { providerAccentOf(it) } ?: BrewMuted
                ToolBarIcon(
                    selected = showProviderPanel,
                    accent = providerAccent,
                    description = stringResource(R.string.chat_provider_pick_title),
                    onClick = {
                        showProviderPanel = !showProviderPanel
                        showThinkPanel = false
                    },
                ) {
                    if (providerRes != 0) {
                        Icon(
                            painter = painterResource(providerRes),
                            contentDescription = null,
                            tint = providerAccent,
                            modifier = Modifier.size(18.dp),
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Filled.Cloud,
                            contentDescription = null,
                            tint = if (showProviderPanel) providerAccent else BrewMuted,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                // 思考深度：图标色与拍照/知识库统一（BrewChat，2026-09-25 用户要求"跟左侧另外三个同色"），
                // 选中态只靠背景高亮表达，不再用琥珀色单独立异
                ToolBarIcon(
                    selected = thinkMode == "deep",
                    accent = BrewChat,
                    description = stringResource(R.string.chat_think_mode_title),
                    onClick = {
                        showThinkPanel = !showThinkPanel
                        showProviderPanel = false
                    },
                ) {
                    Icon(
                        imageVector = when (thinkMode) {
                            "deep" -> Icons.Filled.Psychology
                            "off" -> Icons.Filled.PsychologyAlt
                            else -> Icons.Outlined.Psychology
                        },
                        contentDescription = null,
                        tint = BrewChat,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Spacer(Modifier.width(8.dp))
                // 拍照 / 知识库留在输入栏（发消息前的取材动作）；本机模式与清空已挪回顶栏
                ToolBarIcon(
                    selected = false,
                    accent = BrewChat,
                    description = stringResource(R.string.chat_photo_ask),
                    onClick = { askPhotoAi() },
                ) {
                    Icon(
                        imageVector = Icons.Filled.PhotoCamera,
                        contentDescription = null,
                        tint = BrewChat,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Spacer(Modifier.width(8.dp))
                ToolBarIcon(
                    selected = false,
                    accent = BrewChat,
                    description = stringResource(R.string.chat_kb),
                    onClick = { showKbDialog = true },
                ) {
                    Icon(
                        imageVector = Icons.Filled.Folder,
                        contentDescription = null,
                        tint = BrewChat,
                        modifier = Modifier.size(20.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                // 附件键：与发送键**同尺寸同形状**（都是圆形 38dp）——
                // 它俩是同一类动作（对"这条消息"的操作），尺寸一致才读成一个组；
                // 左侧 4 个工具钮是方形小圆角（另一组：设置类动作）。用户明确要求"大小一致"。
                // 激活色 = BrewRed（2026-09-25 用户要求与发送键"停止"态同色）：红色在本栏的
                // 语义被统一为"这条消息的进行时/待决动作"，紫色退场后全栏只剩 3 个功能色
                ToolBarCircleButton(
                    selected = showAttachSheet,
                    accent = BrewRed,
                    description = stringResource(R.string.chat_attach_title),
                    enabled = !sending && !readingAttach,
                    onClick = { showAttachSheet = true },
                ) {
                    if (readingAttach) {
                        androidx.compose.material3.CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = BrewRed,
                        )
                    } else {
                        // 图标用「加号」而不是回形针：这一排按钮读的是"下一步动作"，
                        // ＋ 的语义（添加素材）比回形针（表示"这是个附件"）更直接
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = null,
                            tint = if (showAttachSheet) BrewRed else BrewMuted,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                // 发送 / 停止：右下角圆形图标钮（**与附件键同尺寸 38dp**，只图标无文字）
                val canSend = sending || input.isNotBlank() || pendingFile != null || pendingImage != null
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(19.dp))
                        .background(
                            when {
                                sending -> BrewRed
                                canSend -> BrewChat
                                else -> BrewPanelHi
                            }
                        )
                        .clickable(enabled = canSend) {
                            if (sending) {
                                // 停止当前回复：取消模型生成 + 停眼镜端播报（abortCurrentAi 内含
                                // tts_stop 下行，放后台线程避免阻塞主线程）
                                sending = false
                                val sess = try { app.cxrL } catch (e: Exception) { null }
                                if (sess != null) {
                                    scope.launch(Dispatchers.IO) {
                                        sess.abortCurrentAi()
                                    }
                                }
                            } else {
                                send()
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    val active = canSend
                    if (sending) {
                        Icon(
                            imageVector = Icons.Filled.Clear,
                            contentDescription = stringResource(R.string.chat_stop),
                            tint = BrewBg,
                            modifier = Modifier.size(18.dp),
                        )
                    } else {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = stringResource(R.string.chat_send),
                            tint = if (active) BrewBg else BrewMuted,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
    }

    // 上拉菜单：发送给大模型（文件 / 图片 / 拍照，可选重发上次附件）
    if (showAttachSheet) {
        ChatAttachSheet(
            // 选择器只放行**能识别的格式**（与读取侧白名单同源，见 ChatAttachments.pickerMimes）：
            // 放行再拒绝会让用户觉得"能选却发不出去"，比一开始看不到更困惑
            onPickFile = {
                showAttachSheet = false
                runCatching { filePicker.launch(ChatAttachments.pickerMimes) }
            },
            onPickImage = {
                showAttachSheet = false
                runCatching { imagePicker.launch(ChatAttachments.pickerImageMimes) }
            },
            onTakePhoto = {
                showAttachSheet = false
                launchCamera()
            },
            onRetryLast = if (lastFile != null || lastImage != null) {
                {
                    showAttachSheet = false
                    pendingFile = lastFile
                    pendingImage = lastImage
                    if (lastImage != null) {
                        scope.launch {
                            pendingImageThumb = withContext(Dispatchers.IO) {
                                runCatching {
                                    android.graphics.BitmapFactory.decodeFile(
                                        lastImage!!.fileUrl.removePrefix("file://"),
                                    )
                                }.getOrNull()
                            }
                        }
                    }
                }
            } else {
                null
            },
            onDismiss = { showAttachSheet = false },
        )
    }

    if (showSettings) {
        ChatSettingsDialog(
            app = app,
            onDismiss = {
                showSettings = false
                // 下次从齿轮进入时回到设置页首页
                settingsOpenProvider = false
            },
            openProviderPage = settingsOpenProvider,
        )
    }

    if (showClearConfirm) {
        ConfirmClearChatDialog(
            onConfirm = {
                ChatStateHolder.clear()
                try {
                    app.cxrL.clearAgentHistory()
                } catch (_: Exception) {
                }
                showClearConfirm = false
            },
            onDismiss = { showClearConfirm = false },
        )
    }

    if (showKbDialog) {
        KbManageDialog(onDismiss = { showKbDialog = false })
    }

    if (showSessions) {
        ChatSessionsDialog(
            onDismiss = { showSessions = false },
            onOpenRecords = { recordsTarget = it },
            onEditPrompt = { promptTarget = it },
        )
    }

    // 对话记录图（节点连接图 + 搜索 + 导出 + 逐条复制/编辑/删除）。
    // 由本页 host 而不是由会话列表 host：里面的「编辑重发」要落到**下面的输入框**上，
    // 那是本 Composable 的状态（`startEdit`）；会话列表拿不到它。
    recordsTarget?.let { target ->
        SessionRecordsDialog(
            meta = target,
            isCurrent = target.id == ChatStateHolder.currentSessionId,
            onCopy = { copyText(it) },
            onEditMessage = { msg ->
                // 关掉两层弹层，让用户直接看到输入框里预填好的那条
                recordsTarget = null
                showSessions = false
                startEdit(msg)
            },
            onDeleteMessage = { msg ->
                ChatStateHolder.deleteMessage(msg.id)
                contextRefresh++
            },
            onDismiss = { recordsTarget = null },
        )
    }

    // 本会话提示词（见 ChatSessionMeta.systemPrompt）
    promptTarget?.let { target ->
        SessionPromptDialog(
            initial = ChatStateHolder.sessionPrompt(target.id),
            onConfirm = { text ->
                ChatStateHolder.setSessionPrompt(target.id, text)
                promptTarget = null
            },
            onDismiss = { promptTarget = null },
        )
    }

    // 消息级操作：引用 / 复制 / 编辑重发 / 重新生成 / 删除
    actionTarget?.let { target ->
        ChatMessageActionsDialog(
            msg = target,
            canRegenerate = isLastAiMessage(target),
            onQuote = {
                // 引用 = 把这条原文挂到下一轮上下文里。比"复制 → 粘到输入框"少一步，
                // 而且不会把原文混进用户自己写的问题里（模型能分清"引用的材料"与"我的指令"）。
                quoted = target
                actionTarget = null
            },
            onCopy = {
                copyText(target.content)
                actionTarget = null
            },
            onEdit = {
                startEdit(target)
                actionTarget = null
            },
            onRegenerate = {
                regenerate(target)
                actionTarget = null
            },
            onDelete = {
                ChatStateHolder.deleteMessage(target.id)
                actionTarget = null
            },
            onDismiss = { actionTarget = null },
        )
    }

    if (showContextInfo) {
        contextUsage?.let { usage ->
            ContextUsageDialog(
                usage = usage,
                onCompact = {
                    val r = runCatching { AgentSessionManager.compactNow() }.getOrNull()
                    contextRefresh++
                    r != null
                },
                onNewSession = {
                    ChatStateHolder.newSession()
                    showContextInfo = false
                },
                onOpenTrace = { showTrace = true },
                onDismiss = { showContextInfo = false },
            )
        }
    }

    if (showTrace) {
        SessionTraceDialog(onDismiss = { showTrace = false })
    }

    // 首次进入乐奇的通知权限说明框
    if (showNotifPermDialog) {
        AlertDialog(
            onDismissRequest = {
                showNotifPermDialog = false
                prefs.edit().putBoolean(KEY_NOTIF_PERM_PROMPTED, true).apply()
            },
            title = { Text(stringResource(R.string.notif_perm_title)) },
            text = { Text(stringResource(R.string.notif_perm_message)) },
            confirmButton = {
                Button(onClick = {
                    if (notifPermNeedSettings) {
                        openAppNotificationSettings()
                        showNotifPermDialog = false
                        prefs.edit().putBoolean(KEY_NOTIF_PERM_PROMPTED, true).apply()
                    } else {
                        notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }) {
                    Text(
                        stringResource(
                            if (notifPermNeedSettings) R.string.notif_perm_open_settings
                            else R.string.notif_perm_enable
                        )
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showNotifPermDialog = false
                    prefs.edit().putBoolean(KEY_NOTIF_PERM_PROMPTED, true).apply()
                }) {
                    Text(stringResource(R.string.notif_perm_later))
                }
            },
        )
    }
}
