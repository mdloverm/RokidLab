package com.rokidlab.phone.filemanager

import com.rokidlab.phone.app.*
import com.rokidlab.phone.adb.*
import com.rokidlab.phone.connection.ConnectionRoute
import com.rokidlab.phone.design.*
import com.rokidlab.phone.filemanager.*
import com.rokidlab.phone.glasses.*
import com.rokidlab.phone.mirror.*
import com.rokidlab.phone.model.*
import com.rokidlab.phone.network.*
import com.rokidlab.phone.settings.*
import com.rokidlab.phone.store.*
import com.rokidlab.phone.util.AppConfig
import com.rokidlab.phone.util.LogCollector
import com.rokidlab.phone.R
import android.content.Intent
import android.content.ActivityNotFoundException
import android.content.SharedPreferences
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import androidx.compose.animation.core.animateFloatAsState
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors

fun formatSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format("%.1f KB", bytes / 1024.0)
        bytes < 1024 * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024))
        else -> String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024))
    }
}

fun formatDate(timestamp: Long): String {
    return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))
}

enum class SortType { NAME, SIZE, DATE }
enum class ClipboardAction { COPY, CUT }

@Composable
internal fun NewFolderDialog(onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var name by remember { mutableStateOf("") }
    
    BrewDialog(onDismiss = onDismiss, title = ctx.getString(R.string.new_folder), color = BrewCoral) {
        BrewDialogContent {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = { Text(ctx.getString(R.string.folder_name_hint)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = BrewCoral,
                    unfocusedBorderColor = BrewPanelHi
                )
            )
        }
        BrewDialogActions {
            BrewDialogButton(text = ctx.getString(R.string.cancel), onClick = onDismiss)
            Spacer(Modifier.width(8.dp))
            BrewDialogButton(text = ctx.getString(R.string.confirm), onClick = { if (name.isNotBlank()) onConfirm(name) },
                enabled = name.isNotBlank(), color = BrewCoral)
        }
    }
}

@Composable
fun RenameDialog(fileName: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var newName by remember { mutableStateOf(fileName) }
    
    BrewDialog(onDismiss = onDismiss, title = ctx.getString(R.string.rename), color = BrewCoral) {
        BrewDialogContent {
            OutlinedTextField(
                value = newName,
                onValueChange = { newName = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = BrewCoral,
                    unfocusedBorderColor = BrewPanelHi
                )
            )
        }
        BrewDialogActions {
            BrewDialogButton(text = ctx.getString(R.string.cancel), onClick = onDismiss)
            Spacer(Modifier.width(8.dp))
            BrewDialogButton(text = ctx.getString(R.string.confirm), onClick = { if (newName.isNotBlank()) onConfirm(newName) },
                enabled = newName.isNotBlank(), color = BrewCoral)
        }
    }
}

@Composable
fun DeleteConfirmDialog(count: Int, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    BrewDialog(onDismiss = onDismiss, color = BrewRed) {
        BrewDialogContent {
            Icon(Icons.Outlined.Warning, contentDescription = "Warning", tint = BrewWarning, modifier = Modifier.size(48.dp))
            Spacer(modifier = Modifier.height(16.dp))
            Text(ctx.getString(R.string.confirm_delete_files, count), fontWeight = FontWeight.Bold, color = BrewTextBright)
            Text(ctx.getString(R.string.cannot_undo), color = BrewMuted)
        }
        BrewDialogActions {
            BrewDialogButton(text = ctx.getString(R.string.cancel), onClick = onDismiss)
            Spacer(Modifier.width(8.dp))
            BrewDialogButton(text = ctx.getString(R.string.delete), onClick = onConfirm, color = BrewRed)
        }
    }
}

@Composable
internal fun DetailsDialog(file: FileItem, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    BrewDialog(onDismiss = onDismiss, title = ctx.getString(R.string.file_details), color = BrewInfo) {
        BrewDialogContent {
            DetailRow(label = ctx.getString(R.string.name_label), value = file.name)
            DetailRow(label = ctx.getString(R.string.path_label), value = file.path)
            DetailRow(label = ctx.getString(R.string.type_label), value = if (file.isDirectory) ctx.getString(R.string.folder) else ctx.getString(R.string.file))
            DetailRow(label = ctx.getString(R.string.size_label), value = if (file.isDirectory) "-" else formatSize(file.size))
            DetailRow(label = ctx.getString(R.string.modified_time), value = formatDate(file.lastModified))
        }
        BrewDialogActions {
            BrewDialogButton(text = ctx.getString(R.string.confirm), onClick = onDismiss, color = BrewCoral)
        }
    }
}

@Composable
fun DetailRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = BrewMuted, modifier = Modifier.width(80.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text(value, color = BrewTextBright, modifier = Modifier.weight(1f))
    }
}

@Composable
fun PreviewDialog(fileName: String, content: String?, isLoading: Boolean, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    BrewDialog(onDismiss = onDismiss, color = BrewInfo) {
        Column(modifier = Modifier.defaultMinSize(minHeight = 200.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(fileName, fontWeight = FontWeight.Bold, color = BrewTextBright)
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Outlined.Close, contentDescription = "Close")
                }
            }
            
            Box(modifier = Modifier.height(400.dp)) {
                if (isLoading) {
                    LoadingScreen(message = ctx.getString(R.string.loading))
                } else if (content != null) {
                    val imageExtensions = setOf(".jpg", ".jpeg", ".png", ".gif", ".bmp", ".webp")
                    val isImage = imageExtensions.any { content.lowercase().endsWith(it) }
                    
                    if (isImage) {
                        ImagePreview(content)
                    } else {
                        ScrollableColumn(content = content)
                    }
                }
            }
        }
    }
}

@Composable
internal fun ImagePreview(imagePath: String) {
    val ctx = LocalContext.current
    val bitmap = remember {
        BitmapFactory.decodeFile(imagePath)
    }
    
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "Preview Image",
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            contentScale = ContentScale.Fit
        )
    } else {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(Icons.Outlined.BrokenImage, contentDescription = "Broken Image", tint = BrewMuted)
            Text(ctx.getString(R.string.image_load_failed), color = BrewMuted)
        }
    }
}

@Composable
fun ScrollableColumn(content: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(content, color = BrewText, fontSize = 14.sp, lineHeight = 20.sp)
    }
}

@Composable
internal fun FileManagerScreen(
    currentPath: String,
    files: List<FileItem>,
    isLoading: Boolean,
    statusMessage: String,
    selectedFiles: Set<String>,
    hasClipboard: Boolean,
    searchQuery: String,
    sortOrder: FileManagerActivity.SortOrder,
    showHiddenFiles: Boolean,
    storageInfo: AdbFileManagerClient.StorageInfo?,
    installingApkPath: String?,
    apkInstallProgress: Int,
    apkInstallStatus: String?,
    apkInstallSuccess: Boolean,
    onBack: () -> Unit,
    onNavigateUp: () -> Unit,
    onRefresh: () -> Unit,
    onFileClick: (FileItem) -> Unit,
    onFileLongClick: (FileItem) -> Unit,
    onNewFolder: () -> Unit,
    onUpload: () -> Unit,
    onSearchChange: (String) -> Unit,
    onSortChange: (FileManagerActivity.SortOrder) -> Unit,
    onToggleHidden: () -> Unit,
    onCopy: () -> Unit,
    onCut: () -> Unit,
    onPaste: () -> Unit,
    onDelete: () -> Unit,
    onRename: () -> Unit,
    onDownload: (FileItem) -> Unit,
    onPreview: (FileItem) -> Unit,
    onDetails: (FileItem) -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onClearClipboard: () -> Unit,
    onRenameFile: (FileItem) -> Unit,
    onCopyFile: (FileItem) -> Unit,
    onCutFile: (FileItem) -> Unit,
    onDeleteFile: (FileItem) -> Unit,
    onInstallApk: ((FileItem) -> Unit)? = null,
) {
    val ctx = LocalContext.current
    var isRefreshing by remember { mutableStateOf(false) }
    var showSortMenu by remember { mutableStateOf(false) }
    val rotationAngle by animateFloatAsState(if (isRefreshing) 360f else 0f, label = "refresh")

    // 监听 isLoading 变化来控制动画
    LaunchedEffect(isLoading) {
        if (!isLoading) {
            isRefreshing = false
        }
    }

    // 处理刷新动画
    fun handleRefresh() {
        isRefreshing = true
        onRefresh()
    }

    // 处理排序变化
    fun handleSortChange(newOrder: FileManagerActivity.SortOrder) {
        showSortMenu = false
        onSortChange(newOrder)
    }

    // 处理系统返回键
    BackHandler {
        onBack()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                    Text(currentPath, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            },
            actions = {
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = BrewPanel)
        )

        if (statusMessage.isNotEmpty()) {
            Snackbar(
                modifier = Modifier.padding(8.dp),
                content = { Text(statusMessage) },
                action = { /* no action */ }
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { onSearchChange(it) },
                placeholder = { Text(ctx.getString(R.string.search_files)) },
                modifier = Modifier.weight(1f),
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = "Search") },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = BrewCoral,
                    unfocusedBorderColor = BrewPanelHi
                )
            )
            
            Spacer(modifier = Modifier.width(8.dp))
            
            Box {
                IconButton(onClick = { showSortMenu = true }) {
                    Icon(Icons.AutoMirrored.Outlined.Sort, contentDescription = ctx.getString(R.string.sort))
                }
                DropdownMenu(
                    expanded = showSortMenu,
                    onDismissRequest = { showSortMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(ctx.getString(R.string.sort_by_name) + if (sortOrder == FileManagerActivity.SortOrder.NAME_ASC) " ↑" else if (sortOrder == FileManagerActivity.SortOrder.NAME_DESC) " ↓" else "") },
                        onClick = { handleSortChange(if (sortOrder == FileManagerActivity.SortOrder.NAME_ASC) FileManagerActivity.SortOrder.NAME_DESC else FileManagerActivity.SortOrder.NAME_ASC) }
                    )
                    DropdownMenuItem(
                        text = { Text(ctx.getString(R.string.sort_by_size) + if (sortOrder == FileManagerActivity.SortOrder.SIZE_ASC) " ↑" else if (sortOrder == FileManagerActivity.SortOrder.SIZE_DESC) " ↓" else "") },
                        onClick = { handleSortChange(if (sortOrder == FileManagerActivity.SortOrder.SIZE_ASC) FileManagerActivity.SortOrder.SIZE_DESC else FileManagerActivity.SortOrder.SIZE_ASC) }
                    )
                    DropdownMenuItem(
                        text = { Text(ctx.getString(R.string.sort_by_date) + if (sortOrder == FileManagerActivity.SortOrder.DATE_ASC) " ↑" else if (sortOrder == FileManagerActivity.SortOrder.DATE_DESC) " ↓" else "") },
                        onClick = { handleSortChange(if (sortOrder == FileManagerActivity.SortOrder.DATE_ASC) FileManagerActivity.SortOrder.DATE_DESC else FileManagerActivity.SortOrder.DATE_ASC) }
                    )
                }
            }
        }

        if (selectedFiles.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(BrewPanelAlt)
                    .padding(8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(ctx.getString(R.string.items_selected, selectedFiles.size), color = BrewText)
                    Spacer(modifier = Modifier.width(8.dp))
                    TextButton(onClick = onSelectAll) { Text(ctx.getString(R.string.select_all)) }
                    TextButton(onClick = onClearSelection) { Text(ctx.getString(R.string.clear_selection)) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(onClick = onCopy, enabled = !hasClipboard) {
                        Icon(Icons.Outlined.FileCopy, contentDescription = "Copy")
                    }
                    IconButton(onClick = onCut, enabled = !hasClipboard) {
                        Icon(Icons.Outlined.ContentCut, contentDescription = "Cut")
                    }
                    IconButton(onClick = onPaste, enabled = hasClipboard) {
                        Icon(Icons.Outlined.ContentPaste, contentDescription = "Paste")
                    }
                    IconButton(onClick = onRename, enabled = selectedFiles.size == 1) {
                        Icon(Icons.Outlined.DriveFileRenameOutline, contentDescription = "Rename")
                    }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Outlined.Delete, contentDescription = "Delete", tint = BrewRed)
                    }
                }
            }
        }

        // 快捷操作栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(BrewPanel)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 当前目录显示
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.FolderOpen, contentDescription = "Current Path", tint = BrewMuted, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text(currentPath, color = BrewText, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            
            // 快捷操作按钮
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButton(onClick = onNewFolder) {
                    Icon(Icons.Outlined.CreateNewFolder, contentDescription = ctx.getString(R.string.new_folder), tint = BrewCoral)
                }
                IconButton(onClick = onUpload) {
                    Icon(Icons.Outlined.Upload, contentDescription = ctx.getString(R.string.upload_status), tint = BrewCoral)
                }
                IconButton(onClick = { handleRefresh() }) {
                    Icon(Icons.Outlined.Refresh, contentDescription = ctx.getString(R.string.refresh), tint = BrewCoral, modifier = Modifier.rotate(rotationAngle))
                }
            }
        }

        // 粘贴栏 — 剪贴板有内容时显示
        if (hasClipboard) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(BrewAmber.copy(alpha = 0.12f))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.ContentPaste, contentDescription = "Paste", tint = BrewAmber, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(ctx.getString(R.string.clipboard_has_items), color = BrewAmber, fontSize = 14.sp)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onPaste) {
                        Text(ctx.getString(R.string.paste_action), color = BrewAmber, fontWeight = FontWeight.Bold)
                    }
                    TextButton(onClick = onClearClipboard) {
                        Text(ctx.getString(R.string.cancel), color = BrewMuted)
                    }
                }
            }
        }

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(files) { file ->
                FileItemRow(
                    file = file,
                    isSelected = selectedFiles.contains(file.path),
                    onClick = { onFileClick(file) },
                    onLongClick = { onFileLongClick(file) },
                    onDetails = { onDetails(file) },
                    onDownload = { onDownload(file) },
                    onPreview = { onPreview(file) },
                    onRename = { onRenameFile(file) },
                    onCopy = { onCopyFile(file) },
                    onCut = { onCutFile(file) },
                    onDelete = { onDeleteFile(file) },
                    onInstallApk = if (file.name.lowercase().endsWith(".apk")) {
                        onInstallApk?.let { { it(file) } }
                    } else null,
                    selectedCount = selectedFiles.size,
                    installingApkPath = installingApkPath,
                    apkInstallProgress = apkInstallProgress,
                    apkInstallStatus = apkInstallStatus,
                    apkInstallSuccess = apkInstallSuccess
                )
            }
        }

        if (storageInfo != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(BrewPanel)
                    .padding(8.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(ctx.getString(R.string.storage_info, formatSize(storageInfo.usedBytes), formatSize(storageInfo.totalBytes)), color = BrewMuted)
                Text(ctx.getString(R.string.file_count, files.size), color = BrewMuted)
            }
        }
    }
}

class FileManagerActivity : ComponentActivity() {

    companion object {
        private const val TAG = "FileManager"
        internal const val EXTRA_USE_REAL_INSTALL = "use_real_install"

        fun createIntent(context: android.content.Context, useRealInstall: Boolean = false) = Intent(context, FileManagerActivity::class.java).apply {
            putExtra(EXTRA_USE_REAL_INSTALL, useRealInstall)
        }
    }


    internal var ipAddress = "192.168.1.168"
    internal var isConnected by mutableStateOf(false)
    internal var isConnecting by mutableStateOf(false)
    internal var connectionError: String? by mutableStateOf(null)
    internal var useRealInstall: Boolean = false

    internal var currentPath = "/sdcard/"
    internal var files: List<FileItem> by mutableStateOf(emptyList())
    internal var isLoading by mutableStateOf(false)
    internal var statusMessage by mutableStateOf("")

    internal var selectedFiles: Set<String> by mutableStateOf(emptySet())
    internal var clipboard: Pair<ClipboardAction, Set<String>>? by mutableStateOf(null)

    internal var showNewFolderDialog by mutableStateOf(false)
    internal var showRenameDialog by mutableStateOf(false)
    internal var showDeleteDialog by mutableStateOf(false)
    internal var showDetailsDialog by mutableStateOf(false)
    internal var showPreviewDialog by mutableStateOf(false)
    internal var previewContent: String? by mutableStateOf(null)
    internal var previewLoading by mutableStateOf(false)
    internal var targetFile: FileItem? by mutableStateOf(null)
    internal var renameText by mutableStateOf("")
    internal var newFolderName by mutableStateOf("")

    internal var searchQuery by mutableStateOf("")
    internal var sortOrder by mutableStateOf(SortOrder.NAME_ASC)
    internal var showHiddenFiles by mutableStateOf(false)
    internal var storageInfo: AdbFileManagerClient.StorageInfo? by mutableStateOf(null)

    // APK安装状态跟踪
    internal var installingApkPath: String? by mutableStateOf(null)
    internal var apkInstallProgress: Int by mutableStateOf(0)
    internal var apkInstallStatus: String? by mutableStateOf(null)
    internal var apkInstallSuccess: Boolean by mutableStateOf(false)

    internal enum class SortOrder {
        NAME_ASC, NAME_DESC, SIZE_ASC, SIZE_DESC, DATE_ASC, DATE_DESC, TYPE_ASC, TYPE_DESC
    }

    /**
     * L5 文件管理状态机（Phase 5：连接生命周期 + 文件 IO 全链路迁至 feature/FileManagerStateHolder）。
     * UI 状态留在本 Activity（Compose 直接读），业务逻辑经下列一行门面委派。
     */
    internal val fm by lazy { com.rokidlab.phone.feature.FileManagerStateHolder(this) }

    // ActivityResult 启动器必须留在 Activity（需在 STARTED 前注册），回调转交状态机
    private val filePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { fm.uploadFile(it) }
    }

    private var pendingDownloadFile: FileItem? = null
    private val saveFileLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        uri?.let { downloadUri ->
            pendingDownloadFile?.let { file -> fm.downloadFileToUri(file, downloadUri) }
        }
        pendingDownloadFile = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        fm.init()

        setContent {
            val ctx = LocalContext.current
            RokidLabTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val connError = connectionError
                    when {
                        isConnecting || (!isConnected && connectionError == null) -> {
                            LoadingScreen(message = ctx.getString(R.string.connecting_glasses))
                        }
                        connError != null -> {
                            ErrorScreen(
                                error = connError,
                                onRetry = { 
                                    connectionError = null
                                    isConnecting = true
                                    connect()
                                },
                                onBack = { finish() }
                            )
                        }
                        isConnected -> {
                            FileManagerScreen(
                                currentPath = currentPath,
                                files = getFilteredAndSortedFiles(),
                                isLoading = isLoading,
                                statusMessage = statusMessage,
                                selectedFiles = selectedFiles,
                                hasClipboard = clipboard != null,
                                searchQuery = searchQuery,
                                sortOrder = sortOrder,
                                showHiddenFiles = showHiddenFiles,
                                storageInfo = storageInfo,
                                installingApkPath = installingApkPath,
                                apkInstallProgress = apkInstallProgress,
                                apkInstallStatus = apkInstallStatus,
                                apkInstallSuccess = apkInstallSuccess,
                                onBack = { 
                                    Log.i(TAG, "onBack called, currentPath=$currentPath")
                                    if (currentPath == "/sdcard/") {
                                        Log.i(TAG, "At root directory, exiting app")
                                        finish()
                                    } else {
                                        Log.i(TAG, "Not at root, navigating up")
                                        navigateUp()
                                    }
                                },
                                onNavigateUp = { navigateUp() },
                                onRefresh = { loadFiles() },
                                onFileClick = { file -> onFileClick(file) },
                                onFileLongClick = { file -> toggleSelection(file) },
                                onNewFolder = { showNewFolderDialog = true },
                                onUpload = { filePicker.launch("*/*") },
                                onSearchChange = { searchQuery = it },
                                onSortChange = { sortOrder = it },
                                onToggleHidden = { showHiddenFiles = !showHiddenFiles },
                                onCopy = { copySelected() },
                                onCut = { cutSelected() },
                                onPaste = { pasteFiles() },
                                onDelete = { deleteSelected() },
                                onRename = { renameSelected() },
                                onDownload = { file -> downloadFile(file) },
                                onPreview = { file -> previewFile(file) },
                                onDetails = { file -> showFileDetails(file) },
                                onSelectAll = { selectAll() },
                                onClearSelection = { clearSelection() },
                                onClearClipboard = { clearClipboard() },
                                onRenameFile = { file -> 
                                    selectedFiles = setOf(file.path)
                                    showRenameDialog = true
                                    targetFile = file
                                },
                                onCopyFile = { file ->
                                    selectedFiles = setOf(file.path)
                                    copySelected()
                                },
                                onCutFile = { file ->
                                    selectedFiles = setOf(file.path)
                                    cutSelected()
                                },
                                onDeleteFile = { file ->
                                    selectedFiles = setOf(file.path)
                                    showDeleteDialog = true
                                },
                                onInstallApk = { file -> installApk(file) },
                            )

                            if (showNewFolderDialog) {
                                NewFolderDialog(
                                    onConfirm = { name -> createNewFolder(name) },
                                    onDismiss = { showNewFolderDialog = false }
                                )
                            }

                            val renameTarget = targetFile
                            if (showRenameDialog && renameTarget != null) {
                                RenameDialog(
                                    fileName = renameTarget.name,
                                    onConfirm = { name -> renameFile(name) },
                                    onDismiss = { showRenameDialog = false; targetFile = null }
                                )
                            }

                            if (showDeleteDialog) {
                                DeleteConfirmDialog(
                                    count = selectedFiles.size,
                                    onConfirm = { deleteSelectedFiles() },
                                    onDismiss = { showDeleteDialog = false }
                                )
                            }

                            val detailsTarget = targetFile
                            if (showDetailsDialog && detailsTarget != null) {
                                DetailsDialog(
                                    file = detailsTarget,
                                    onDismiss = { showDetailsDialog = false; targetFile = null }
                                )
                            }

                            val previewTarget = targetFile
                            if (showPreviewDialog && previewTarget != null) {
                                PreviewDialog(
                                    fileName = previewTarget.name,
                                    content = previewContent,
                                    isLoading = previewLoading,
                                    onDismiss = { showPreviewDialog = false; targetFile = null; previewContent = null }
                                )
                            }
                        }
                    }
                }
            }
        }
        }


    // ── 一行门面（Phase 5：调用点零改动） ──
    private fun connect() = fm.connect()
    private fun disconnect() = fm.disconnect()
    private fun loadFiles() = fm.loadFiles()
    private fun loadStorageInfoSync() = fm.loadStorageInfoSync()
    private fun navigateUp() = fm.navigateUp()
    private fun onFileClick(file: FileItem) = fm.onFileClick(file)
    private fun toggleSelection(file: FileItem) = fm.toggleSelection(file)
    private fun selectAll() = fm.selectAll()
    private fun clearSelection() = fm.clearSelection()
    private fun clearClipboard() = fm.clearClipboard()
    private fun openFile(file: FileItem) = fm.openFile(file)
    private fun previewFile(file: FileItem) = fm.previewFile(file)
    private fun uploadFile(uri: Uri) = fm.uploadFile(uri)
    private fun createNewFolder(name: String) = fm.createNewFolder(name)
    private fun renameSelected() = fm.renameSelected()
    private fun renameFile(newName: String) = fm.renameFile(newName)
    private fun copySelected() = fm.copySelected()
    private fun cutSelected() = fm.cutSelected()
    private fun pasteFiles() = fm.pasteFiles()
    private fun deleteSelected() = fm.deleteSelected()
    private fun deleteSelectedFiles() = fm.deleteSelectedFiles()
    private fun installApk(file: FileItem) = fm.installApk(file)
    private fun showFileDetails(file: FileItem) = fm.showFileDetails(file)
    private fun getFilteredAndSortedFiles(): List<FileItem> = fm.getFilteredAndSortedFiles()

    /** 下载入口：弹系统保存对话框（启动器必须留在 Activity），落盘由状态机执行 */
    internal fun downloadFile(file: FileItem) {
        pendingDownloadFile = file
        saveFileLauncher.launch(file.name)
    }

    override fun onDestroy() {
        fm.onDestroy()
        super.onDestroy()
    }
}

@Composable
fun LoadingScreen(message: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = BrewCoral)
            Spacer(modifier = Modifier.height(16.dp))
            Text(message, color = BrewTextBright)
        }
    }
}

@Composable
fun ErrorScreen(error: String, onRetry: () -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(32.dp)
        ) {
            Icon(Icons.Outlined.Error, contentDescription = "Error", tint = BrewRed, modifier = Modifier.size(64.dp))
            Spacer(modifier = Modifier.height(16.dp))
            Text(error, color = BrewText, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Spacer(modifier = Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Button(
                    onClick = onRetry,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = BrewCoral,
                        contentColor = BrewTextBright
                    )
                ) {
                    Text(ctx.getString(R.string.retry_action))
                }
                Button(
                    onClick = onBack,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = BrewPanelHi,
                        contentColor = BrewTextBright
                    )
                ) {
                    Text(ctx.getString(R.string.back_action))
                }
            }
        }
    }
}

@Composable
fun FileItemRow(
    file: FileItem,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDownload: () -> Unit,
    onPreview: () -> Unit,
    onRename: () -> Unit,
    onCopy: () -> Unit,
    onCut: () -> Unit,
    onDelete: () -> Unit,
    onDetails: () -> Unit,
    onInstallApk: (() -> Unit)? = null,
    selectedCount: Int,
    installingApkPath: String? = null,
    apkInstallProgress: Int = 0,
    apkInstallStatus: String? = null,
    apkInstallSuccess: Boolean = false
) {
    val ctx = LocalContext.current
    val interactionSource = remember { MutableInteractionSource() }
    var showMenu by remember { mutableStateOf(false) }
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick
            )
            .background(if (isSelected) BrewPanelHi else Color.Transparent)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (selectedCount > 0) {
            Checkbox(
                checked = isSelected,
                onCheckedChange = null,
                modifier = Modifier.padding(end = 8.dp)
            )
        }
        
        Icon(
            imageVector = if (file.isDirectory) Icons.Outlined.Folder else Icons.AutoMirrored.Outlined.InsertDriveFile,
            contentDescription = if (file.isDirectory) "Folder" else "File",
            tint = if (file.isDirectory) BrewCyan else BrewMuted,
            modifier = Modifier.size(24.dp)
        )
        
        Spacer(modifier = Modifier.width(12.dp))
        
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(file.name, color = BrewTextBright, maxLines = 1, overflow = TextOverflow.Ellipsis)
                
                // 显示APK安装状态
                if (installingApkPath == file.path) {
                    Spacer(modifier = Modifier.width(8.dp))
                    if (apkInstallProgress > 0 && apkInstallProgress < 100) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(12.dp),
                                strokeWidth = 2.dp,
                                color = BrewCoral
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("$apkInstallProgress%", color = BrewCoral, fontSize = 10.sp)
                        }
                    } else if (apkInstallStatus != null) {
                        // 外层已保证 apkInstallStatus != null，此处该分支不可达（编译器已指出）
                        Text(
                            " [$apkInstallStatus]",
                            color = if (apkInstallSuccess) BrewSuccess else BrewRed,
                            fontSize = 10.sp
                        )
                    }
                }
            }
            Row {
                Text(
                    if (file.isDirectory) ctx.getString(R.string.folder) else formatSize(file.size),
                    color = BrewMuted,
                    fontSize = 12.sp
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(formatDate(file.lastModified), color = BrewMuted, fontSize = 12.sp)
            }
        }
        
        // 文件功能菜单
        Box {
            IconButton(onClick = { showMenu = true }) {
                Icon(Icons.Outlined.MoreVert, contentDescription = ctx.getString(R.string.function_menu), modifier = Modifier.size(20.dp))
            }
            
            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false }
            ) {
                // 文件特有功能
                if (!file.isDirectory) {
                    // 如果是APK文件且提供了安装回调，显示安装选项
                    if (file.name.lowercase().endsWith(".apk") && onInstallApk != null) {
                        val isInstalling = installingApkPath == file.path
                        DropdownMenuItem(
                            text = { 
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(if (isInstalling) {
                                        when {
                                            apkInstallProgress > 0 -> ctx.getString(R.string.installing_with_progress, apkInstallProgress)
                                            apkInstallStatus != null -> apkInstallStatus ?: ctx.getString(R.string.installing_apk)
                                            else -> ctx.getString(R.string.installing_apk)
                                        }
                                    } else {
                                        ctx.getString(R.string.install_apk_menu)
                                    })
                                    if (isInstalling) {
                                        Spacer(modifier = Modifier.width(8.dp))
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp
                                        )
                                    }
                                }
                            },
                            onClick = { 
                                showMenu = false
                                if (!isInstalling) {
                                    onInstallApk()
                                }
                            },
                            leadingIcon = { 
                                Icon(
                                    if (isInstalling) Icons.Outlined.CloudSync else Icons.Outlined.Android,
                                    null,
                                    tint = if (isInstalling) BrewCoral else BrewInfo
                                )
                            },
                            enabled = !isInstalling
                        )
                        HorizontalDivider()
                    }
                    
                    DropdownMenuItem(
                        text = { Text(ctx.getString(R.string.preview_action)) },
                        onClick = { showMenu = false; onPreview() },
                        leadingIcon = { Icon(Icons.Outlined.RemoveRedEye, null) }
                    )
                    DropdownMenuItem(
                        text = { Text(ctx.getString(R.string.download_action)) },
                        onClick = { showMenu = false; onDownload() },
                        leadingIcon = { Icon(Icons.Outlined.Download, null) }
                    )
                    HorizontalDivider()
                }
                
                // 通用功能
                DropdownMenuItem(
                    text = { Text(ctx.getString(R.string.rename)) },
                    onClick = { showMenu = false; onRename() },
                    leadingIcon = { Icon(Icons.Outlined.DriveFileRenameOutline, null) }
                )
                DropdownMenuItem(
                    text = { Text(ctx.getString(R.string.copy_action)) },
                    onClick = { showMenu = false; onCopy() },
                    leadingIcon = { Icon(Icons.Outlined.FileCopy, null) }
                )
                DropdownMenuItem(
                    text = { Text(ctx.getString(R.string.cut_action)) },
                    onClick = { showMenu = false; onCut() },
                    leadingIcon = { Icon(Icons.Outlined.ContentCut, null) }
                )
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text(ctx.getString(R.string.delete)) },
                    onClick = { showMenu = false; onDelete() },
                    leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = BrewRed) }
                )
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text(ctx.getString(R.string.details_action)) },
                    onClick = { showMenu = false; onDetails() },
                    leadingIcon = { Icon(Icons.Outlined.Info, null) }
                )
            }
        }
    }
}
