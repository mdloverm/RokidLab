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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.rokidlab.phone.R
import com.rokidlab.phone.ai.KbDocText
import com.rokidlab.phone.ai.KnowledgeBase
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

// ===== 知识库管理对话框 =====
@Composable
internal fun KbManageDialog(
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var docs by remember { mutableStateOf(KnowledgeBase.listDocs(ctx)) }
    var importing by remember { mutableStateOf(false) }
    // 内容查看/编辑弹窗当前打开的文档（null = 未打开）
    var viewingId by remember { mutableStateOf<Long?>(null) }
    var viewingName by remember { mutableStateOf("") }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            importing = true
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    val displayName = runCatching {
                        ctx.contentResolver.query(
                            uri,
                            arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                            null, null, null,
                        )?.use { c ->
                            if (c.moveToFirst()) c.getString(0) else null
                        }
                    }.getOrNull()
                    KnowledgeBase.importUri(ctx, uri, displayName)
                }
                importing = false
                if (result == null) {
                    Toast.makeText(ctx, ctx.getString(R.string.chat_kb_import_failed), Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(ctx, ctx.getString(R.string.chat_kb_imported, result.name), Toast.LENGTH_SHORT).show()
                    docs = KnowledgeBase.listDocs(ctx)
                    // 语义检索已开启且端点就绪：新文档导入后静默增量补索引（失败不影响导入结果）
                    val snap = com.rokidlab.phone.ai.embedding.EmbeddingSettings.load(ctx)
                    if (snap.ready && KnowledgeBase.vectorStatus(ctx).let { it.embeddedChunks < it.totalChunks }) {
                        scope.launch {
                            runCatching {
                                withContext(Dispatchers.IO) {
                                    KnowledgeBase.backfillEmbeddings(
                                        ctx,
                                        snap.baseUrl,
                                        com.rokidlab.phone.ai.embedding.EmbeddingSettings.apiKey(ctx),
                                        snap.model,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 28.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(BrewPanel)
                .border(1.dp, BrewBorder, RoundedCornerShape(20.dp))
                .padding(20.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.chat_kb_title),
                        color = BrewTextBright,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = stringResource(R.string.chat_kb_subtitle),
                        color = BrewMuted,
                        fontSize = 12.sp,
                    )
                }
                TextButton(
                    onClick = {
                        importLauncher.launch(arrayOf("text/plain"))
                    },
                    enabled = !importing,
                ) {
                    Text(
                        text = if (importing) stringResource(R.string.installing) else stringResource(R.string.chat_kb_import),
                        color = BrewChat,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))

            // ── 语义向量检索（v3：BM25 + 向量 RRF 混合召回的开关与索引管理）──
            SemanticIndexSection(
                onDocsChanged = { docs = KnowledgeBase.listDocs(ctx) },
            )

            if (docs.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.chat_kb_empty),
                        color = BrewMuted,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                Column(modifier = Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    docs.forEach { doc ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                // 点整行进「查看/编辑」弹窗（正文可能在千字以上，列表里放不下）
                                .clickable {
                                    viewingId = doc.id
                                    viewingName = doc.name
                                }
                                .padding(vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = doc.name,
                                    color = BrewTextBright,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                )
                                Text(
                                    text = ctx.getString(
                                        R.string.chat_kb_doc_meta,
                                        formatDocSize(doc.size),
                                        doc.chunkCount,
                                    ),
                                    color = BrewMuted,
                                    fontSize = 11.sp,
                                )
                            }
                            IconButton(
                                onClick = {
                                    scope.launch {
                                        withContext(Dispatchers.IO) { KnowledgeBase.deleteDoc(ctx, doc.id) }
                                        if (viewingId == doc.id) viewingId = null
                                        docs = KnowledgeBase.listDocs(ctx)
                                    }
                                },
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Delete,
                                    contentDescription = stringResource(R.string.chat_kb_delete),
                                    tint = BrewMuted,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.chat_key_dialog_cancel), color = BrewMuted)
                }
            }
        }

        // 查看/编辑：单独一个窗口叠在列表之上。为什么不在列表里就地展开 ——
        // 正文动辄上千字，就地展开既没有滚动容器、也没有编辑空间（用户实测：看不全、改不了）。
        val vid = viewingId
        if (vid != null) {
            KbDocViewerDialog(
                docId = vid,
                docName = viewingName,
                onDismiss = { viewingId = null },
                onSaved = {
                    docs = KnowledgeBase.listDocs(ctx)
                    // 编辑保存 = 旧块删除、新块 embedding 为 NULL；语义检索开启时静默增量补齐，
                    // 否则向量索引停留在旧文本（关键词通道不受影响，手动「立即索引」也可补）
                    val snap = com.rokidlab.phone.ai.embedding.EmbeddingSettings.load(ctx)
                    if (snap.ready && KnowledgeBase.vectorStatus(ctx).let { it.embeddedChunks < it.totalChunks }) {
                        scope.launch {
                            runCatching {
                                withContext(Dispatchers.IO) {
                                    KnowledgeBase.backfillEmbeddings(
                                        ctx,
                                        snap.baseUrl,
                                        com.rokidlab.phone.ai.embedding.EmbeddingSettings.apiKey(ctx),
                                        snap.model,
                                    )
                                }
                            }
                        }
                    }
                },
            )
        }
    }
}

/**
 * 单个知识库文档的内容查看 / 编辑弹窗。
 *
 * - **只读态**：正文放在可滚动容器里（这是"看不全"的修复）。
 * - **编辑态**：换成多行输入框，保存即**重新分块入库**（`KnowledgeBase.updateText`）。
 * - ⚠️ 正文超长被截断时（`KbDocText.truncated`）**不给编辑入口**并说明原因：
 *   拿截断后的文本保存会把后半篇直接删掉，那属于不可恢复的数据丢失。
 */
@Composable
internal fun KbDocViewerDialog(
    docId: Long,
    docName: String,
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var body by remember(docId) { mutableStateOf<KbDocText?>(null) }
    var loaded by remember(docId) { mutableStateOf(false) }
    var editing by remember(docId) { mutableStateOf(false) }
    var draft by remember(docId) { mutableStateOf("") }
    var saving by remember(docId) { mutableStateOf(false) }

    val reload: () -> Unit = {
        scope.launch {
            body = withContext(Dispatchers.IO) { KnowledgeBase.fullText(ctx, docId) }
            loaded = true
        }
    }
    LaunchedEffect(docId) { reload() }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(BrewPanel)
                .border(1.dp, BrewBorder, RoundedCornerShape(20.dp))
                .padding(18.dp),
        ) {
            Text(
                text = docName,
                color = BrewTextBright,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(10.dp))

            val content = body
            when {
                !loaded -> Text(
                    text = stringResource(R.string.chat_kb_view_loading),
                    color = BrewMuted,
                    fontSize = 12.sp,
                )

                content == null -> Text(
                    text = stringResource(R.string.chat_kb_view_no_content),
                    color = BrewMuted,
                    fontSize = 12.sp,
                )

                editing -> OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 200.dp, max = 320.dp),
                    singleLine = false,
                    textStyle = TextStyle(color = BrewTextBright, fontSize = 13.sp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = BrewChat,
                        unfocusedBorderColor = BrewBorder,
                        focusedTextColor = BrewTextBright,
                        unfocusedTextColor = BrewTextBright,
                        cursorColor = BrewChat,
                    ),
                )

                else -> Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 380.dp)
                        .verticalScroll(rememberScrollState())
                        .clip(RoundedCornerShape(8.dp))
                        .border(1.dp, BrewBorder, RoundedCornerShape(8.dp))
                        .padding(10.dp),
                ) {
                    Text(
                        text = content.text,
                        color = BrewTextBright,
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                    )
                }
            }

            if (content?.truncated == true) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.chat_kb_view_too_long, content.text.length),
                    color = BrewAmber,
                    fontSize = 11.sp,
                )
            }

            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                if (editing) {
                    TextButton(onClick = { editing = false }, enabled = !saving) {
                        Text(stringResource(R.string.chat_key_dialog_cancel), color = BrewMuted)
                    }
                    Spacer(Modifier.width(4.dp))
                    TextButton(
                        onClick = {
                            saving = true
                            scope.launch {
                                val text = draft
                                val ok = withContext(Dispatchers.IO) {
                                    KnowledgeBase.updateText(ctx, docId, text)
                                }
                                saving = false
                                Toast.makeText(
                                    ctx,
                                    ctx.getString(
                                        if (ok) R.string.chat_kb_view_saved else R.string.chat_kb_view_save_failed
                                    ),
                                    Toast.LENGTH_SHORT,
                                ).show()
                                if (ok) {
                                    editing = false
                                    loaded = false
                                    reload()
                                    onSaved()
                                }
                            }
                        },
                        enabled = !saving,
                    ) {
                        Text(
                            text = stringResource(
                                if (saving) R.string.chat_kb_view_saving else R.string.chat_kb_view_save
                            ),
                            color = BrewChat,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                } else {
                    // 截断的文档不给编辑入口（见函数头注释）
                    if (content != null && !content.truncated) {
                        TextButton(onClick = {
                            draft = content.text
                            editing = true
                        }) {
                            Text(
                                text = stringResource(R.string.chat_kb_view_edit),
                                color = BrewChat,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                    Spacer(Modifier.width(4.dp))
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.close), color = BrewMuted)
                    }
                }
            }
        }
    }
}

/**
 * 语义向量检索控制面板（知识库弹窗内）。
 *
 * 一条完整的自助启用链路，不依赖额外设置页：
 *  开开关 → 快照当前聊天模型端点 → 模型留空则自动探测（8 个候选名）→
 *  探测成功立即全量回填索引；失败保留开关、提示手填模型名。
 * 索引与检索的任何失败都不影响关键词通道（见 [KnowledgeBase.searchHits] 的降级语义）。
 */
@Composable
private fun SemanticIndexSection(onDocsChanged: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var snap by remember { mutableStateOf(com.rokidlab.phone.ai.embedding.EmbeddingSettings.load(ctx)) }
    var modelInput by remember { mutableStateOf(snap.model) }
    var status by remember { mutableStateOf(KnowledgeBase.vectorStatus(ctx)) }
    var busy by remember { mutableStateOf(false) }
    var progressText by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var noticeOk by remember { mutableStateOf(false) }

    fun refresh() {
        snap = com.rokidlab.phone.ai.embedding.EmbeddingSettings.load(ctx)
        status = KnowledgeBase.vectorStatus(ctx)
        onDocsChanged()
    }

    /** embeddings 默认复用聊天模型的服务商（OpenAI 兼容端点），返回 baseUrl→apiKey */
    fun endpoint(): Pair<String, String>? {
        val app = ctx.applicationContext as? com.rokidlab.phone.app.LabApplication
        val cfg = app?.takeIf { it.hasCxrL() }?.cxrL?.getAiConfig() ?: return null
        return cfg.baseUrl.trim() to cfg.apiKey.trim()
    }

    suspend fun indexAll(reembedAll: Boolean) {
        val s = com.rokidlab.phone.ai.embedding.EmbeddingSettings.load(ctx)
        if (!s.ready) return
        busy = true
        progressText = ctx.getString(R.string.chat_kb_semantic_indexing, 0, status.totalChunks)
        notice = null
        try {
            val n = withContext(Dispatchers.IO) {
                KnowledgeBase.backfillEmbeddings(
                    ctx, s.baseUrl,
                    com.rokidlab.phone.ai.embedding.EmbeddingSettings.apiKey(ctx),
                    s.model,
                    reembedAll = reembedAll,
                ) { done, total ->
                    progressText = ctx.getString(R.string.chat_kb_semantic_indexing, done, total)
                }
            }
            notice = ctx.getString(R.string.chat_kb_semantic_index_done, n)
            noticeOk = true
        } catch (e: Exception) {
            notice = ctx.getString(R.string.chat_kb_semantic_index_failed, e.message ?: "")
            noticeOk = false
        } finally {
            busy = false
            progressText = null
            refresh()
        }
    }

    suspend fun detectAndEnable() {
        val ep = endpoint()
        if (ep == null) {
            notice = ctx.getString(R.string.chat_kb_semantic_no_config)
            noticeOk = false
            return
        }
        busy = true
        notice = null
        try {
            var model = modelInput.trim()
            if (model.isEmpty()) {
                model = withContext(Dispatchers.IO) {
                    com.rokidlab.phone.ai.embedding.EmbeddingClient.probeModel(ep.first, ep.second)
                }.orEmpty()
            }
            if (model.isEmpty()) {
                // 记不住 key 也保存开关与端点（下次手填模型名可直接重建），但 ready=false，检索仍走 BM25
                com.rokidlab.phone.ai.embedding.EmbeddingSettings.save(
                    ctx, enabled = true, model = "", baseUrl = ep.first, apiKey = ep.second,
                )
                notice = ctx.getString(R.string.chat_kb_semantic_detect_failed)
                noticeOk = false
            } else {
                modelInput = model
                com.rokidlab.phone.ai.embedding.EmbeddingSettings.save(
                    ctx, enabled = true, model = model, baseUrl = ep.first, apiKey = ep.second,
                )
                indexAll(reembedAll = true)
                return
            }
        } catch (e: Exception) {
            notice = ctx.getString(R.string.chat_kb_semantic_index_failed, e.message ?: "")
            noticeOk = false
        } finally {
            busy = false
            refresh()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(BrewPanel)
            .border(1.dp, BrewBorder, RoundedCornerShape(10.dp))
            .padding(10.dp),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.chat_kb_semantic_title),
                        color = BrewTextBright,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = stringResource(R.string.chat_kb_semantic_hint),
                        color = BrewMuted,
                        fontSize = 11.sp,
                        lineHeight = 14.sp,
                    )
                }
                Spacer(Modifier.width(8.dp))
                androidx.compose.material3.Switch(
                    checked = snap.enabled,
                    enabled = !busy,
                    // 开关配色与 AI 服务设置页同款（BrewChat 轨道 / BrewBg 圆点），保持全 App 一致
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = BrewChat,
                        uncheckedTrackColor = BrewPanelHi,
                        checkedThumbColor = BrewBg,
                        uncheckedThumbColor = BrewMuted,
                    ),
                    onCheckedChange = { on ->
                        scope.launch {
                            if (on) {
                                detectAndEnable()
                            } else {
                                com.rokidlab.phone.ai.embedding.EmbeddingSettings.save(
                                    ctx, enabled = false,
                                    model = com.rokidlab.phone.ai.embedding.EmbeddingSettings.load(ctx).model,
                                    baseUrl = com.rokidlab.phone.ai.embedding.EmbeddingSettings.load(ctx).baseUrl,
                                )
                                notice = null
                                refresh()
                            }
                        }
                    },
                )
            }

            if (snap.enabled) {
                Spacer(Modifier.height(6.dp))
                val statusText = progressText ?: when {
                    status.totalChunks == 0 -> ctx.getString(R.string.chat_kb_empty)
                    status.embeddedChunks == 0 ->
                        ctx.getString(R.string.chat_kb_semantic_status_pending, 0, status.totalChunks)
                    status.embeddedChunks < status.totalChunks ->
                        ctx.getString(R.string.chat_kb_semantic_status_pending, status.embeddedChunks, status.totalChunks)
                    else -> ctx.getString(
                        R.string.chat_kb_semantic_status_ready,
                        status.embeddedChunks, status.totalChunks, status.model,
                    )
                }
                Text(text = statusText, color = BrewMuted, fontSize = 11.sp)

                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = modelInput,
                    onValueChange = { modelInput = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !busy,
                    textStyle = TextStyle(color = BrewTextBright, fontSize = 12.sp),
                    label = {
                        Text(
                            text = stringResource(R.string.chat_kb_semantic_model),
                            color = BrewMuted, fontSize = 11.sp,
                        )
                    },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = BrewChat,
                        unfocusedBorderColor = BrewBorder,
                        focusedTextColor = BrewTextBright,
                        unfocusedTextColor = BrewTextBright,
                        cursorColor = BrewChat,
                    ),
                )

                Spacer(Modifier.height(8.dp))
                Row {
                    TextButton(
                        onClick = { scope.launch { detectAndEnable() } },
                        enabled = !busy,
                    ) {
                        Text(
                            text = stringResource(R.string.chat_kb_semantic_detect),
                            color = BrewChat,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    val pending = status.totalChunks - status.embeddedChunks
                    TextButton(
                        onClick = { scope.launch { indexAll(reembedAll = pending <= 0) } },
                        enabled = !busy || progressText != null,
                    ) {
                        Text(
                            text = stringResource(
                                if (pending > 0) R.string.chat_kb_semantic_index_now
                                else R.string.chat_kb_semantic_rebuild,
                            ),
                            color = BrewChat,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }

                notice?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(text = it, color = if (noticeOk) BrewChat else BrewAmber, fontSize = 11.sp)
                }
            } else {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.chat_kb_semantic_status_off),
                    color = BrewMuted, fontSize = 10.sp,
                )
            }
        }
    }
}
