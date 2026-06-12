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
import com.rokidlab.phone.util.*
import android.content.Intent
import android.content.SharedPreferences
import android.content.ActivityNotFoundException
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
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.layout.ContentScale
import androidx.core.content.FileProvider
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
fun NewFolderDialog(onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            color = BrewPanel
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("新建文件夹", fontWeight = FontWeight.Bold, color = BrewTextBright)
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = { Text("输入文件夹名称") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = BrewCoral,
                        unfocusedBorderColor = BrewPanelHi
                    )
                )
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                ) {
                    Button(onClick = { onDismiss() }, colors = ButtonDefaults.buttonColors(containerColor = BrewPanelHi)) {
                        Text("取消")
                    }
                    Button(
                        onClick = { if (name.isNotBlank()) onConfirm(name) },
                        enabled = name.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = BrewCoral)
                    ) {
                        Text("确定")
                    }
                }
            }
        }
    }
}

@Composable
fun RenameDialog(fileName: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var newName by remember { mutableStateOf(fileName) }
    
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            color = BrewPanel
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("重命名", fontWeight = FontWeight.Bold, color = BrewTextBright)
                Spacer(modifier = Modifier.height(16.dp))
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
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                ) {
                    Button(onClick = { onDismiss() }, colors = ButtonDefaults.buttonColors(containerColor = BrewPanelHi)) {
                        Text("取消")
                    }
                    Button(
                        onClick = { if (newName.isNotBlank()) onConfirm(newName) },
                        enabled = newName.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = BrewCoral)
                    ) {
                        Text("确定")
                    }
                }
            }
        }
    }
}

@Composable
fun DeleteConfirmDialog(count: Int, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            color = BrewPanel
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Icon(Icons.Outlined.Warning, contentDescription = "Warning", tint = BrewOrange, modifier = Modifier.size(48.dp))
                Spacer(modifier = Modifier.height(16.dp))
                Text("确定要删除 ${count} 个文件吗？", fontWeight = FontWeight.Bold, color = BrewTextBright)
                Text("此操作无法撤销。", color = BrewMuted)
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                ) {
                    Button(onClick = { onDismiss() }, colors = ButtonDefaults.buttonColors(containerColor = BrewPanelHi)) {
                        Text("取消")
                    }
                    Button(
                        onClick = { onConfirm() },
                        colors = ButtonDefaults.buttonColors(containerColor = BrewRed)
                    ) {
                        Text("删除")
                    }
                }
            }
        }
    }
}

@Composable
fun DetailsDialog(file: FileItem, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            color = BrewPanel
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("文件详情", fontWeight = FontWeight.Bold, color = BrewTextBright)
                Spacer(modifier = Modifier.height(16.dp))
                
                DetailRow(label = "名称", value = file.name)
                DetailRow(label = "路径", value = file.path)
                DetailRow(label = "类型", value = if (file.isDirectory) "文件夹" else "文件")
                DetailRow(label = "大小", value = if (file.isDirectory) "-" else formatSize(file.size))
                DetailRow(label = "修改时间", value = formatDate(file.lastModified))
                
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Button(onClick = onDismiss, colors = ButtonDefaults.buttonColors(containerColor = BrewCoral)) {
                        Text("确定")
                    }
                }
            }
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
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .padding(16.dp)
                .height(500.dp),
            shape = RoundedCornerShape(16.dp),
            color = BrewPanel
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
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
                
                Box(modifier = Modifier.weight(1f)) {
                    if (isLoading) {
                        LoadingScreen(message = "加载中...")
                    } else if (content != null) {
                        // 判断是否为图片路径
                        val imageExtensions = setOf(".jpg", ".jpeg", ".png", ".gif", ".bmp", ".webp")
                        val isImage = imageExtensions.any { content.lowercase().endsWith(it) }
                        
                        if (isImage) {
                            // 图片预览
                            ImagePreview(content)
                        } else {
                            // 文本预览
                            ScrollableColumn(content = content)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ImagePreview(imagePath: String) {
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
            Text("图片加载失败", color = BrewMuted)
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
fun FileManagerScreen(
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
) {
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
                IconButton(onClick = onRefresh) {
                    Icon(Icons.Outlined.Refresh, contentDescription = "Refresh")
                }
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
                placeholder = { Text("搜索文件...") },
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
                IconButton(onClick = { /* show sort menu */ }) {
                    Icon(Icons.Outlined.Sort, contentDescription = "Sort")
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
                    Text("已选择 ${selectedFiles.size} 项", color = BrewText)
                    Spacer(modifier = Modifier.width(8.dp))
                    TextButton(onClick = onSelectAll) { Text("全选") }
                    TextButton(onClick = onClearSelection) { Text("取消") }
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
                    Icon(Icons.Outlined.CreateNewFolder, contentDescription = "新建文件夹", tint = BrewCoral)
                }
                IconButton(onClick = onUpload) {
                    Icon(Icons.Outlined.Upload, contentDescription = "上传文件", tint = BrewCoral)
                }
                IconButton(onClick = onRefresh) {
                    Icon(Icons.Outlined.Refresh, contentDescription = "刷新", tint = BrewCoral)
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
                    selectedCount = selectedFiles.size
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
                Text("存储: ${formatSize(storageInfo.usedBytes)} / ${formatSize(storageInfo.totalBytes)}", color = BrewMuted)
                Text("文件数: ${files.size}", color = BrewMuted)
            }
        }
    }
}

class FileManagerActivity : ComponentActivity() {

    companion object {
        private const val TAG = "FileManager"
        private const val ADB_PORT = 5555

        fun createIntent(context: android.content.Context) = Intent(context, FileManagerActivity::class.java)
    }

    private var ipAddress = "192.168.1.168"
    private var isConnected by mutableStateOf(false)
    private var isConnecting by mutableStateOf(false)
    private var connectionError: String? by mutableStateOf(null)

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
        
        val app = application as LabApplication
        ipAddress = app.fileManagerIp
        
        isConnecting = true
        connect()

        setContent {
            RokidLabTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    when {
                        isConnecting || (!isConnected && connectionError == null) -> {
                            LoadingScreen(message = "正在连接眼镜...")
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
                                onBack = { 
                                    Log.i(TAG, "onBack 被调用, currentPath=$currentPath")
                                    if (currentPath == "/sdcard/") {
                                        Log.i(TAG, "当前是根目录，退出应用")
                                        finish()
                                    } else {
                                        Log.i(TAG, "当前不是根目录，返回上级")
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
        Log.i(TAG, "开始连接: $ipAddress:$ADB_PORT")
        
        Thread {
            try {
                Log.i(TAG, "创建 AdbFileManagerClient")
                val client = AdbFileManagerClient(ipAddress, ADB_PORT)
                Log.i(TAG, "调用 connect 方法")
                val success = client.connect { status ->
                    Log.i(TAG, "连接状态: $status")
                    runOnUiThread { statusMessage = status }
                }
                
                Log.i(TAG, "连接结果: $success")
                runOnUiThread {
                    isConnecting = false
                    if (success) {
                        isConnected = true
                        adbClient = client
                        loadFiles()
                    } else {
                        connectionError = "连接失败，请检查IP地址和网络"
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "连接异常: ${e.message}", e)
                runOnUiThread {
                    isConnecting = false
                    connectionError = "连接异常: ${e.message}"
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
                        statusMessage = "加载文件夹失败: ${e.message}"
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
        Log.i(TAG, "navigateUp 被调用, 当前路径: $currentPath")
        
        // 移除末尾的斜杠进行处理
        val normalizedPath = currentPath.trimEnd('/')
        Log.i(TAG, "标准化路径: $normalizedPath")
        
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
        
        Log.i(TAG, "上级路径: $parentPath")
        
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
        Log.i(TAG, "预览文件: ${file.path}")
        
        Thread {
            try {
                // 通过文件扩展名判断文件类型
                val textExtensions = setOf("txt", "log", "md", "json", "xml", "html", "js", "css", "java", "kt", "py", "cpp", "h", "c", "sh", "bat")
                val imageExtensions = setOf("jpg", "jpeg", "png", "gif", "bmp", "webp")
                
                val extension = file.name.substringAfterLast('.', "").lowercase()
                Log.i(TAG, "文件扩展名: $extension")
                
                if (imageExtensions.contains(extension)) {
                    // 图片文件 - 下载到本地后预览
                    Log.i(TAG, "图片文件，开始下载")
                    val localPath = cacheDir.absolutePath + "/" + file.name
                    val success = adbClient?.downloadFile(file.path, localPath) ?: false
                    
                    runOnUiThread {
                        previewLoading = false
                        if (success) {
                            previewContent = localPath // 存储本地路径用于图片预览
                            Log.i(TAG, "图片下载成功: $localPath")
                        } else {
                            previewContent = "图片下载失败"
                            Log.e(TAG, "图片下载失败")
                        }
                    }
                } else if (textExtensions.contains(extension)) {
                    // 文本文件可以预览
                    val content = adbClient?.executeShellCommand("cat \"${file.path}\"")
                    Log.i(TAG, "文本内容预览成功, 长度: ${content?.length}")
                    
                    runOnUiThread {
                        previewLoading = false
                        previewContent = content ?: "无法读取文件内容"
                    }
                } else {
                    // 其他文件
                    runOnUiThread {
                        previewLoading = false
                        previewContent = "该文件类型不支持预览\n\n文件名: ${file.name}\n大小: ${formatSize(file.size)}"
                    }
                    Log.i(TAG, "不支持的文件类型")
                }
            } catch (e: Exception) {
                Log.e(TAG, "预览失败: ${e.message}", e)
                runOnUiThread {
                    previewLoading = false
                    previewContent = "预览失败: ${e.message}"
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
        statusMessage = "正在下载..."
        
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
                    Log.e(TAG, "澶嶅埗鏂囦欢澶辫触: ${e.message}")
                }
                File(localPath).delete()
            }
            
            runOnUiThread {
                isLoading = false
                statusMessage = if (success) "涓嬭浇鎴愬姛" else "涓嬭浇澶辫触"
            }
        }.start()
    }

    private fun uploadFile(uri: Uri) {
        isLoading = true
        statusMessage = "姝ｅ湪涓婁紶..."
        
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
                Log.e(TAG, "澶嶅埗鏂囦欢澶辫触: ${e.message}")
            }
            
            val success = adbClient?.uploadFile(localPath, targetPath) ?: false
            File(localPath).delete()
            
            runOnUiThread {
                isLoading = false
                if (success) {
                    statusMessage = "涓婁紶鎴愬姛"
                    loadFiles()
                } else {
                    statusMessage = "涓婁紶澶辫触"
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
                statusMessage = if (success) "文件夹创建成功" else "文件夹创建失败"
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
            statusMessage = "文件名不能为空"
            return
        }
        
        showRenameDialog = false
        isLoading = true
        
        Thread {
            val newPath = buildPath(currentPath, newName)
            val success = adbClient?.renameFile(targetFile!!.path, newPath) ?: false
            
            runOnUiThread {
                isLoading = false
                statusMessage = if (success) "重命名成功" else "重命名失败"
                targetFile = null
                renameText = ""
                if (success) loadFiles()
            }
        }.start()
    }

    private fun copySelected() {
        if (selectedFiles.isEmpty()) return
        clipboard = Pair(ClipboardAction.COPY, selectedFiles)
        statusMessage = "已复制 ${selectedFiles.size} 项"
        selectedFiles = emptySet()
    }

    private fun cutSelected() {
        if (selectedFiles.isEmpty()) return
        clipboard = Pair(ClipboardAction.CUT, selectedFiles)
        statusMessage = "已剪切 ${selectedFiles.size} 项"
        selectedFiles = emptySet()
    }

    private fun pasteFiles() {
        clipboard?.let { (action, paths) ->
            isLoading = true
            statusMessage = "姝ｅ湪绮樿创..."
            
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
                        "已粘贴 ${successCount} 项"
                    } else {
                        "粘贴成功 $successCount/${paths.size} 项"
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
        statusMessage = "正在删除..."
        
        Thread {
            var successCount = 0
            selectedFiles.forEach { path ->
                if (adbClient?.deleteFile(path) == true) successCount++
            }
            
            runOnUiThread {
                isLoading = false
                statusMessage = if (successCount == selectedFiles.size) {
                    "已删除 ${successCount} 项"
                } else {
                    "删除成功 $successCount/${selectedFiles.size} 项"
                }
                selectedFiles = emptySet()
                loadFiles()
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
        
        result = result.sortedWith(compareBy(
            { !it.isDirectory },
            { 
                when (sortOrder) {
                    SortOrder.NAME_ASC -> it.name.lowercase()
                    SortOrder.NAME_DESC -> it.name.lowercase().reversed()
                    SortOrder.SIZE_ASC -> it.size
                    SortOrder.SIZE_DESC -> -it.size
                    SortOrder.DATE_ASC -> it.lastModified
                    SortOrder.DATE_DESC -> -it.lastModified
                    SortOrder.TYPE_ASC -> it.name.substringAfterLast('.', "").lowercase()
                    SortOrder.TYPE_DESC -> it.name.substringAfterLast('.', "").lowercase().reversed()
                }
            }
        ))
        
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
                Button(onClick = onRetry, colors = ButtonDefaults.buttonColors(containerColor = BrewCoral)) {
                    Text("閲嶈瘯")
                }
                Button(onClick = onBack, colors = ButtonDefaults.buttonColors(containerColor = BrewPanelHi)) {
                    Text("返回")
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
    selectedCount: Int
) {
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
            Text(file.name, color = BrewTextBright, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row {
                Text(
                    if (file.isDirectory) "文件夹" else formatSize(file.size),
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
                Icon(Icons.Outlined.MoreVert, contentDescription = "功能菜单", modifier = Modifier.size(20.dp))
            }
            
            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false }
            ) {
                // 文件特有功能
                if (!file.isDirectory) {
                    DropdownMenuItem(
                        text = { Text("预览") },
                        onClick = { showMenu = false; onPreview() },
                        leadingIcon = { Icon(Icons.Outlined.RemoveRedEye, null) }
                    )
                    DropdownMenuItem(
                        text = { Text("下载") },
                        onClick = { showMenu = false; onDownload() },
                        leadingIcon = { Icon(Icons.Outlined.Download, null) }
                    )
                    HorizontalDivider()
                }
                
                // 通用功能
                DropdownMenuItem(
                    text = { Text("重命名") },
                    onClick = { showMenu = false; onRename() },
                    leadingIcon = { Icon(Icons.Outlined.DriveFileRenameOutline, null) }
                )
                DropdownMenuItem(
                    text = { Text("复制") },
                    onClick = { showMenu = false; onCopy() },
                    leadingIcon = { Icon(Icons.Outlined.FileCopy, null) }
                )
                DropdownMenuItem(
                    text = { Text("剪切") },
                    onClick = { showMenu = false; onCut() },
                    leadingIcon = { Icon(Icons.Outlined.ContentCut, null) }
                )
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("删除") },
                    onClick = { showMenu = false; onDelete() },
                    leadingIcon = { Icon(Icons.Outlined.Delete, null, tint = BrewRed) }
                )
                HorizontalDivider()
                DropdownMenuItem(
                    text = { Text("详情") },
                    onClick = { showMenu = false; onDetails() },
                    leadingIcon = { Icon(Icons.Outlined.Info, null) }
                )
            }
        }
    }
}
