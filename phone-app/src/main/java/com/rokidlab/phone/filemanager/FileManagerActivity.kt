package com.rokidlab.phone.filemanager

import com.rokidlab.phone.app.*
import com.rokidlab.phone.adb.*
import com.rokidlab.phone.design.*
import com.rokidlab.phone.filemanager.*
import com.rokidlab.phone.glasses.*
import com.rokidlab.phone.mirror.*
import com.rokidlab.phone.model.*
import com.rokidlab.phone.network.*
import com.rokidlab.phone.settings.*
import com.rokidlab.phone.store.*
import com.rokidlab.phone.util.AppConfig
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
            Icon(Icons.Outlined.Warning, contentDescription = "Warning", tint = BrewOrange, modifier = Modifier.size(48.dp))
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
                    Icon(Icons.Outlined.Sort, contentDescription = ctx.getString(R.string.sort))
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
                    apkInstallStatus = apkInstallStatus
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
        private const val EXTRA_USE_REAL_INSTALL = "use_real_install"

        fun createIntent(context: android.content.Context, useRealInstall: Boolean = false) = Intent(context, FileManagerActivity::class.java).apply {
            putExtra(EXTRA_USE_REAL_INSTALL, useRealInstall)
        }
    }

    private var ipAddress = "192.168.1.168"
    private var isConnected by mutableStateOf(false)
    private var isConnecting by mutableStateOf(false)
    private var connectionError: String? by mutableStateOf(null)
    private var useRealInstall: Boolean = false

    private var currentPath = "/sdcard/"
    private var files: List<FileItem> by mutableStateOf(emptyList())
    private var isLoading by mutableStateOf(false)
    private var statusMessage by mutableStateOf("")

    private var selectedFiles: Set<String> by mutableStateOf(emptySet())
    private var clipboard: Pair<ClipboardAction, Set<String>>? by mutableStateOf(null)

    private var showNewFolderDialog by mutableStateOf(false)
    private var showRenameDialog by mutableStateOf(false)
    private var showDeleteDialog by mutableStateOf(false)
    private var showDetailsDialog by mutableStateOf(false)
    private var showPreviewDialog by mutableStateOf(false)
    private var previewContent: String? by mutableStateOf(null)
    private var previewLoading by mutableStateOf(false)
    private var targetFile: FileItem? by mutableStateOf(null)
    private var renameText by mutableStateOf("")
    private var newFolderName by mutableStateOf("")

    private var searchQuery by mutableStateOf("")
    private var sortOrder by mutableStateOf(SortOrder.NAME_ASC)
    private var showHiddenFiles by mutableStateOf(false)
    private var storageInfo: AdbFileManagerClient.StorageInfo? by mutableStateOf(null)

    // APK安装状态跟踪
    private var installingApkPath: String? by mutableStateOf(null)
    private var apkInstallProgress: Int by mutableStateOf(0)
    private var apkInstallStatus: String? by mutableStateOf(null)

    enum class SortOrder {
        NAME_ASC, NAME_DESC, SIZE_ASC, SIZE_DESC, DATE_ASC, DATE_DESC, TYPE_ASC, TYPE_DESC
    }

    private var adbClient: AdbFileManagerClient? = null
    private lateinit var prefs: SharedPreferences

    private val filePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { uploadFile(it) }
    }

    private var pendingDownloadFile: FileItem? = null
    private val saveFileLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        uri?.let { downloadUri ->
            pendingDownloadFile?.let { file ->
                downloadFileToUri(file, downloadUri)
            }
        }
        pendingDownloadFile = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("rokidlab", MODE_PRIVATE)
        
        // 读取是否使用真正的安装功能
        useRealInstall = intent.getBooleanExtra(EXTRA_USE_REAL_INSTALL, false)
        
        val app = application as LabApplication
        ipAddress = app.fileManagerIp
        
        isConnecting = true
        connect()

        setContent {
            val ctx = LocalContext.current
            RokidLabTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    when {
                        isConnecting || (!isConnected && connectionError == null) -> {
                            LoadingScreen(message = ctx.getString(R.string.connecting_glasses))
                        }
                        connectionError != null -> {
                            ErrorScreen(
                                error = connectionError!!,
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

                            if (showRenameDialog && targetFile != null) {
                                RenameDialog(
                                    fileName = targetFile!!.name,
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

                            if (showDetailsDialog && targetFile != null) {
                                DetailsDialog(
                                    file = targetFile!!,
                                    onDismiss = { showDetailsDialog = false; targetFile = null }
                                )
                            }

                            if (showPreviewDialog && targetFile != null) {
                                PreviewDialog(
                                    fileName = targetFile!!.name,
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

    private fun disconnect() {
        adbClient?.disconnect()
        adbClient = null
        isConnected = false
        isConnecting = false
        connectionError = null
        currentPath = "/sdcard/"
        files = emptyList()
        selectedFiles = emptySet()
    }

    override fun onDestroy() {
        super.onDestroy()
        adbClient?.disconnect()
    }

    private fun connect() {
        isConnecting = true
        connectionError = null
        Log.i(TAG, "Starting connection: $ipAddress:${AppConfig.DEFAULT_ADB_PORT}")
        
        Thread {
            try {
                Log.i(TAG, "Creating AdbFileManagerClient")
                val client = AdbFileManagerClient(this@FileManagerActivity, ipAddress, AppConfig.DEFAULT_ADB_PORT)
                Log.i(TAG, "Calling connect method")
                val success = client.connect { status ->
                    Log.i(TAG, "Connection status: $status")
                    runOnUiThread { statusMessage = status }
                }
                
                Log.i(TAG, "Connection result: $success")
                runOnUiThread {
                    isConnecting = false
                    if (success) {
                        isConnected = true
                        adbClient = client
                        loadFiles()
                    } else {
                        connectionError = getString(R.string.connection_failed_check_ip)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Connection error: ${e.message}", e)
                runOnUiThread {
                    isConnecting = false
                    connectionError = getString(R.string.connection_error_format, e.message)
                }
            }
        }.start()
    }

    private var loadFilesCounter = 0
    
    private fun loadFiles() {
        isLoading = true
        statusMessage = ""
        val targetPath = currentPath
        val loadId = ++loadFilesCounter
        
        Thread {
            try {
                val result = adbClient?.listFiles(targetPath) ?: emptyList()
                
                runOnUiThread {
                    if (loadId == loadFilesCounter) {
                        files = result
                        isLoading = false
                        loadStorageInfo()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (loadId == loadFilesCounter) {
                        isLoading = false
                        statusMessage = getString(R.string.load_folder_failed, e.message)
                    }
                }
            }
        }.start()
    }

    private fun loadStorageInfo() {
        Thread {
            val info = adbClient?.getStorageInfo(currentPath)
            runOnUiThread {
                storageInfo = info
            }
        }.start()
    }

    private fun navigateUp() {
        Log.i(TAG, "navigateUp called, current path: $currentPath")
        
        // 移除末尾的斜杠进行处理
        val normalizedPath = currentPath.trimEnd('/')
        Log.i(TAG, "Normalized path: $normalizedPath")
        
        val parentPath = if (normalizedPath == "/sdcard") {
            "/sdcard/"
        } else {
            val lastSlashIndex = normalizedPath.lastIndexOf('/')
            if (lastSlashIndex <= 1) {
                "/sdcard/"
            } else {
                normalizedPath.substring(0, lastSlashIndex) + "/"
            }
        }
        
        Log.i(TAG, "Parent path: $parentPath")
        
        if (parentPath != currentPath) {
            currentPath = parentPath
            loadFiles()
        }
    }

    private fun onFileClick(file: FileItem) {
        if (selectedFiles.isNotEmpty()) {
            toggleSelection(file)
            return
        }
        
        if (file.isDirectory) {
            currentPath = file.path
            loadFiles()
        } else {
            openFile(file)
        }
    }

    private fun toggleSelection(file: FileItem) {
        selectedFiles = if (selectedFiles.contains(file.path)) {
            selectedFiles - file.path
        } else {
            selectedFiles + file.path
        }
    }

    private fun selectAll() {
        selectedFiles = files.map { it.path }.toSet()
    }

    private fun clearSelection() {
        selectedFiles = emptySet()
    }

    private fun openFile(file: FileItem) {
        val ext = file.name.substringAfterLast('.', "").lowercase()
        if (ext in listOf("txt", "md", "json", "xml", "log")) {
            previewFile(file)
        } else {
            downloadFile(file)
        }
    }

    private fun previewFile(file: FileItem) {
        targetFile = file
        showPreviewDialog = true
        previewLoading = true
        Log.i(TAG, "Previewing file: ${file.path}")
        
        Thread {
            try {
                // 通过文件扩展名判断文件类型
                val textExtensions = setOf("txt", "log", "md", "json", "xml", "html", "js", "css", "java", "kt", "py", "cpp", "h", "c", "sh", "bat")
                val imageExtensions = setOf("jpg", "jpeg", "png", "gif", "bmp", "webp")
                
                val extension = file.name.substringAfterLast('.', "").lowercase()
                Log.i(TAG, "File extension: $extension")
                
                if (imageExtensions.contains(extension)) {
                    // 图片文件 - 下载到本地后预览
                    Log.i(TAG, "Image file, starting download")
                    val localPath = cacheDir.absolutePath + "/" + file.name
                    val success = adbClient?.downloadFile(file.path, localPath) ?: false
                    
                    runOnUiThread {
                        previewLoading = false
                        if (success) {
                            previewContent = localPath // 存储本地路径用于图片预览
                            Log.i(TAG, "Image download successful: $localPath")
                        } else {
                            previewContent = getString(R.string.image_download_failed)
                            Log.e(TAG, "Image download failed")
                        }
                    }
                } else if (textExtensions.contains(extension)) {
                    // 文本文件可以预览 - 使用 shell cat 命令读取（sync 协议不支持此设备）
                    Log.i(TAG, "Text file, reading content")
                    val content = if (adbClient != null) {
                        try {
                            adbClient!!.executeShellCommand("cat \"${file.path}\"")
                        } catch (e: Exception) {
                            Log.e(TAG, "shell cat failed: ${e.message}")
                            null
                        }
                    } else null
                    Log.i(TAG, "Text content preview result: ${if (content != null && content.isNotEmpty()) "success, length: ${content.length}" else "failed"}")
                    
                    runOnUiThread {
                        previewLoading = false
                        previewContent = content ?: getString(R.string.cannot_read_content)
                    }
                } else {
                    // 其他文件
                    runOnUiThread {
                        previewLoading = false
                        previewContent = getString(R.string.preview_not_supported) + "\n\n" + getString(R.string.name_label) + ": ${file.name}\n" + getString(R.string.size_label) + ": ${formatSize(file.size)}"
                    }
                    Log.i(TAG, "Unsupported file type")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Preview failed: ${e.message}", e)
                runOnUiThread {
                    previewLoading = false
                    previewContent = getString(R.string.preview_failed_format, e.message)
                }
            }
        }.start()
    }

    private fun downloadFile(file: FileItem) {
        pendingDownloadFile = file
        saveFileLauncher.launch(file.name)
    }

    private fun downloadFileToUri(file: FileItem, uri: Uri) {
        isLoading = true
        statusMessage = getString(R.string.download_status)
        
        Thread {
            val localPath = cacheDir.absolutePath + "/" + file.name
            val success = adbClient?.downloadFile(file.path, localPath) ?: false
            
            if (success) {
                try {
                    contentResolver.openOutputStream(uri)?.use { outputStream ->
                        File(localPath).inputStream().use { inputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Copy file failed: ${e.message}")
                }
                File(localPath).delete()
            }
            
            runOnUiThread {
                isLoading = false
                statusMessage = if (success) getString(R.string.download_success) else getString(R.string.download_failed)
            }
        }.start()
    }

    private fun uploadFile(uri: Uri) {
        isLoading = true
        statusMessage = getString(R.string.upload_status)
        
        Thread {
            val fileName = uri.getFileName() ?: "unknown"
            val targetPath = buildPath(currentPath, fileName)
            
            val localPath = cacheDir.absolutePath + "/" + fileName
            try {
                contentResolver.openInputStream(uri)?.use { inputStream ->
                    File(localPath).outputStream().use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Copy file failed: ${e.message}")
            }
            
            val success = adbClient?.uploadFile(localPath, targetPath) ?: false
            File(localPath).delete()
            
            runOnUiThread {
                isLoading = false
                if (success) {
                    statusMessage = getString(R.string.file_upload_success)
                    loadFiles()
                } else {
                    statusMessage = getString(R.string.file_upload_failed)
                }
            }
        }.start()
    }

    private fun createNewFolder(name: String) {
        showNewFolderDialog = false
        isLoading = true
        
        Thread {
            val newPath = buildPath(currentPath, name)
            val success = adbClient?.createFolder(newPath) ?: false
            
            runOnUiThread {
                isLoading = false
                statusMessage = if (success) getString(R.string.folder_create_success) else getString(R.string.folder_create_failed)
                if (success) loadFiles()
            }
        }.start()
    }

    private fun renameSelected() {
        val filePath = selectedFiles.firstOrNull() ?: return
        files.find { it.path == filePath }?.let {
            targetFile = it
            renameText = it.name
            showRenameDialog = true
        }
    }

    private fun renameFile(newName: String) {
        if (newName.isBlank() || targetFile == null) {
            statusMessage = getString(R.string.filename_empty)
            return
        }
        
        showRenameDialog = false
        isLoading = true
        
        Thread {
            val newPath = buildPath(currentPath, newName)
            val success = adbClient?.renameFile(targetFile!!.path, newPath) ?: false
            
            runOnUiThread {
                isLoading = false
                statusMessage = if (success) getString(R.string.rename_success) else getString(R.string.rename_failed)
                targetFile = null
                renameText = ""
                if (success) loadFiles()
            }
        }.start()
    }

    private fun copySelected() {
        if (selectedFiles.isEmpty()) return
        clipboard = Pair(ClipboardAction.COPY, selectedFiles)
        statusMessage = getString(R.string.copied_items, selectedFiles.size)
        selectedFiles = emptySet()
    }

    private fun cutSelected() {
        if (selectedFiles.isEmpty()) return
        clipboard = Pair(ClipboardAction.CUT, selectedFiles)
        statusMessage = getString(R.string.cut_items, selectedFiles.size)
        selectedFiles = emptySet()
    }

    private fun pasteFiles() {
        clipboard?.let { (action, paths) ->
            isLoading = true
            statusMessage = getString(R.string.pasting_status)
            
            Thread {
                var successCount = 0
                paths.forEach { path ->
                    val fileName = path.substringAfterLast('/')
                    val newPath = buildPath(currentPath, fileName)
                    if (action == ClipboardAction.COPY) {
                        if (adbClient?.copyFile(path, newPath) == true) successCount++
                    } else {
                        // Move = copy + delete
                        if (adbClient?.copyFile(path, newPath) == true) {
                            adbClient?.deleteFile(path)
                            successCount++
                        }
                    }
                }
                
                runOnUiThread {
                    isLoading = false
                    statusMessage = if (successCount == paths.size) {
                        getString(R.string.pasted_items, successCount)
                    } else {
                        getString(R.string.pasted_items, successCount) + "/${paths.size}"
                    }
                    if (action == ClipboardAction.CUT) {
                        clipboard = null
                    }
                    loadFiles()
                }
            }.start()
        }
    }

    private fun deleteSelected() {
        if (selectedFiles.isEmpty()) return
        showDeleteDialog = true
    }

    private fun deleteSelectedFiles() {
        showDeleteDialog = false
        isLoading = true
        statusMessage = getString(R.string.deleting_status)
        
        Thread {
            var successCount = 0
            selectedFiles.forEach { path ->
                if (adbClient?.deleteFile(path) == true) successCount++
            }
            
            runOnUiThread {
                isLoading = false
                statusMessage = if (successCount == selectedFiles.size) {
                        getString(R.string.deleted_items, successCount)
                    } else {
                        getString(R.string.deleted_items, successCount) + "/${selectedFiles.size}"
                    }
                selectedFiles = emptySet()
                loadFiles()
            }
        }.start()
    }

    private fun installApk(file: FileItem) {
        if (!file.name.lowercase().endsWith(".apk")) {
            statusMessage = getString(R.string.not_apk_file)
            return
        }
        
        installingApkPath = file.path
        apkInstallProgress = 0
        apkInstallStatus = getString(R.string.preparing_install)
        
        Thread {
            try {
                apkInstallStatus = getString(R.string.installing_apk)
                apkInstallProgress = 50
                
                // 直接在眼镜端通过 ADB shell 执行 pm install，无需下载到手机再上传
                val result = adbClient?.executeShellCommand("pm install -r \"${file.path}\"") ?: ""
                val isSuccess = !result.contains("Failure", ignoreCase = true) &&
                    (result.contains("Success", ignoreCase = true) || result.isBlank())
                
                runOnUiThread {
                    if (isSuccess) {
                        apkInstallStatus = getString(R.string.install_completed)
                        apkInstallProgress = 100
                        statusMessage = getString(R.string.apk_install_completed, file.name)
                    } else {
                        val errorLine = result.lines().firstOrNull { it.isNotBlank() } ?: getString(R.string.unknown_error)
                        apkInstallStatus = getString(R.string.apk_install_failed, errorLine)
                    }
                    
                    // 3秒后清除状态
                    Thread {
                        Thread.sleep(3000)
                        runOnUiThread {
                            installingApkPath = null
                            apkInstallProgress = 0
                            apkInstallStatus = null
                        }
                    }.start()
                }
            } catch (e: Exception) {
                Log.e(TAG, "APK安装失败: ${e.message}", e)
                runOnUiThread {
                    apkInstallStatus = getString(R.string.apk_install_failed, e.message ?: "Unknown")
                    installingApkPath = null
                }
            }
        }.start()
    }

    private fun showFileDetails(file: FileItem) {
        targetFile = file
        showDetailsDialog = true
    }

    private fun buildPath(parent: String, name: String): String {
        return if (parent == "/") "/$name" else "$parent/$name"
    }

    private fun Uri.getFileName(): String? {
        return contentResolver.query(this, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
            } else {
                null
            }
        }
    }

    private fun getFilteredAndSortedFiles(): List<FileItem> {
        var result = files
        
        if (searchQuery.isNotBlank()) {
            result = result.filter { it.name.contains(searchQuery, ignoreCase = true) }
        }
        
        if (!showHiddenFiles) {
            result = result.filter { !it.name.startsWith('.') }
        }
        
        result = result.sortedWith(Comparator<FileItem> { a, b ->
            when {
                a.isDirectory != b.isDirectory -> if (a.isDirectory) -1 else 1
                else -> when (sortOrder) {
                    SortOrder.NAME_ASC -> a.name.lowercase().compareTo(b.name.lowercase())
                    SortOrder.NAME_DESC -> b.name.lowercase().compareTo(a.name.lowercase())
                    SortOrder.SIZE_ASC -> a.size.compareTo(b.size)
                    SortOrder.SIZE_DESC -> b.size.compareTo(a.size)
                    SortOrder.DATE_ASC -> a.lastModified.compareTo(b.lastModified)
                    SortOrder.DATE_DESC -> b.lastModified.compareTo(a.lastModified)
                    SortOrder.TYPE_ASC -> a.name.substringAfterLast('.', "").lowercase()
                        .compareTo(b.name.substringAfterLast('.', "").lowercase())
                    SortOrder.TYPE_DESC -> b.name.substringAfterLast('.', "").lowercase()
                        .compareTo(a.name.substringAfterLast('.', "").lowercase())
                }
            }
        })
        
        return result
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
    apkInstallStatus: String? = null
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
            imageVector = if (file.isDirectory) Icons.Outlined.Folder else Icons.Outlined.InsertDriveFile,
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
                        Text(
                            " [$apkInstallStatus]",
                            color = when {
                                apkInstallStatus.contains("完成") -> BrewSuccess
                                apkInstallStatus.contains("失败") -> BrewRed
                                else -> BrewCoral
                            },
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
                                            apkInstallStatus != null -> apkInstallStatus!!
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
