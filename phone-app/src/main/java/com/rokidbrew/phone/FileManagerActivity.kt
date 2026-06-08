package com.rokidbrew.phone

import android.content.Intent
import android.content.SharedPreferences
import android.content.ActivityNotFoundException
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
import androidx.compose.ui.text.font.FontWeight
import androidx.core.content.FileProvider
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

// ========== 数据类 ==========
enum class SortType { NAME, SIZE, DATE }
enum class ClipboardAction { COPY, CUT }

// ========== 主 Activity ==========
class FileManagerActivity : ComponentActivity() {
    
    companion object {
        private const val TAG = "FileManager"
        private const val ADB_PORT = 5555
        
        fun createIntent(context: android.content.Context) = Intent(context, FileManagerActivity::class.java)
    }
    
    // ========== 状态变量 ==========
    private var ipAddress by mutableStateOf("192.168.1.168")
    private var isConnected by mutableStateOf(false)
    private var isConnecting by mutableStateOf(false)
    private var connectionError by mutableStateOf<String?>(null)
    private var isInstallingScreenStream by mutableStateOf(false)
    
    private var currentPath by mutableStateOf("/sdcard/")
    private var files by mutableStateOf<List<FileItem>>(emptyList())
    private var isLoading by mutableStateOf(false)
    private var statusMessage by mutableStateOf("")
    
    private var selectedFiles by mutableStateOf<Set<String>>(emptySet())
    private var clipboard by mutableStateOf<Pair<ClipboardAction, String>?>(null)
    
    private var showNewFolderDialog by mutableStateOf(false)
    private var showRenameDialog by mutableStateOf(false)
    private var showDeleteDialog by mutableStateOf(false)
    private var showDetailsDialog by mutableStateOf(false)
    private var showPreviewDialog by mutableStateOf(false)
    private var previewContent by mutableStateOf<String?>(null)
    private var previewLoading by mutableStateOf(false)
    private var targetFile by mutableStateOf<FileItem?>(null)
    private var renameText by mutableStateOf("")
    private var newFolderName by mutableStateOf("")
    
    // 新增功能状态
    private var searchQuery by mutableStateOf("")
    private var sortOrder by mutableStateOf(SortOrder.NAME_ASC)
    private var showHiddenFiles by mutableStateOf(false)
    private var storageInfo by mutableStateOf<AdbFileManagerClient.StorageInfo?>(null)
    
    // 排序枚举
    enum class SortOrder {
        NAME_ASC, NAME_DESC, SIZE_ASC, SIZE_DESC, DATE_ASC, DATE_DESC, TYPE_ASC, TYPE_DESC
    }
    
    private var adbClient: AdbFileManagerClient? = null
    private lateinit var prefs: SharedPreferences
    
    private val filePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { uploadFile(it) }
    }
    
    // 下载文件选择器
    private var pendingDownloadFile: FileItem? = null
    private val saveFileLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        uri?.let { downloadUri ->
            pendingDownloadFile?.let { file ->
                downloadFileToUri(file, downloadUri)
            }
        }
        pendingDownloadFile = null
    }
    
    // ========== 生命周期 ==========
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("rokidbrew", MODE_PRIVATE)
        
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = BrewBg) {
                    when {
                        !isConnected && !isConnecting && connectionError == null -> {
                            ConnectionScreen(
                                ipAddress = ipAddress,
                                isInstalling = isInstallingScreenStream,
                                onIpChange = { ipAddress = it },
                                onConnect = { connect() },
                                onInstall = { installScreenStream() }
                            )
                        }
                        isConnecting -> {
                            LoadingScreen(message = "正在连接眼镜...")
                        }
                        connectionError != null -> {
                            ErrorScreen(
                                error = connectionError!!,
                                onRetry = { connect() },
                                onBack = { finish() }
                            )
                        }
                        else -> {
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
                                onBack = { finish() },
                                onNavigateUp = { navigateUp() },
                                onRefresh = { loadFiles() },
                                onFileClick = { file -> onFileClick(file) },
                                onFileLongClick = { file -> toggleSelection(file) },
                                onNewFolder = { showNewFolderDialog = true },
                                onUpload = { filePicker.launch("*/*") },
                                onPaste = { pasteFiles() },
                                onCopy = { copySelected() },
                                onCut = { cutSelected() },
                                onDelete = { deleteSelected() },
                                onSelectAll = { selectAll() },
                                onClearSelection = { selectedFiles = emptySet() },
                                onFileMenu = { file, action -> onFileMenu(file, action) },
                                onSearchQueryChange = { searchQuery = it },
                                onSortOrderChange = { sortOrder = it },
                                onShowHiddenFilesChange = { showHiddenFiles = it },
                                onBatchDownload = { batchDownload() }
                            )
                        }
                    }
                    
                    // 对话框
                    if (showNewFolderDialog) {
                        NewFolderDialog(
                            name = newFolderName,
                            onNameChange = { newFolderName = it },
                            onConfirm = { createFolder() },
                            onCancel = { showNewFolderDialog = false; newFolderName = "" }
                        )
                    }
                    
                    if (showRenameDialog && targetFile != null) {
                        RenameDialog(
                            name = renameText,
                            onNameChange = { renameText = it },
                            onConfirm = { renameFile() },
                            onCancel = { showRenameDialog = false; targetFile = null; renameText = "" }
                        )
                    }
                    
                    if (showDeleteDialog) {
                        DeleteConfirmDialog(
                            count = selectedFiles.size,
                            onConfirm = { performDelete() },
                            onCancel = { showDeleteDialog = false }
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
    
    // ========== 连接相关 ==========
    private fun connect() {
        isConnecting = true
        connectionError = null
        
        Thread {
            val client = AdbFileManagerClient(ipAddress, ADB_PORT)
            val success = client.connect { status ->
                runOnUiThread { statusMessage = status }
            }
            
            runOnUiThread {
                isConnecting = false
                if (success) {
                    isConnected = true
                    adbClient = client
                    loadFiles()
                } else {
                    connectionError = "连接失败，请检查 IP 地址和网络"
                }
            }
        }.start()
    }
    
    private fun installScreenStream() {
        val brewApp = application as BrewApplication
        val cxrL = try { brewApp.cxrL } catch (e: Exception) { null }
        
        if (cxrL == null || !cxrL.hasAuthorization()) {
            statusMessage = "未授权，请前往主页授权"
            return
        }
        
        isInstallingScreenStream = true
        
        try {
            val apkInputStream = assets.open("glasses-screen-service.apk")
            val tempFile = File(cacheDir, "glasses-screen-service.apk")
            apkInputStream.use { it.copyTo(tempFile.outputStream()) }
            
            cxrL.installApk(tempFile) { installed ->
                runOnUiThread {
                    isInstallingScreenStream = false
                    statusMessage = if (installed) "ScreenStream 安装成功" else "ScreenStream 安装失败"
                }
                tempFile.delete()
            }
        } catch (e: Exception) {
            isInstallingScreenStream = false
            statusMessage = "安装失败: ${e.message}"
        }
    }
    
    // ========== 文件操作 ==========
    private var loadFilesCounter = 0  // 用于跟踪最新的加载请求
    
    private fun loadFiles() {
        isLoading = true
        statusMessage = ""
        val targetPath = currentPath
        val loadId = ++loadFilesCounter
        Log.d("FileManager", "loadFiles: 开始加载 $targetPath (loadId=$loadId)")
        
        Thread {
            try {
                val result = adbClient?.listFiles(targetPath) ?: emptyList()
                Log.d("FileManager", "loadFiles: 获取到 ${result.size} 个文件 (loadId=$loadId)")
                result.forEach { Log.d("FILE_LIST", "${it.name} -> ${it.path}") }
                
                runOnUiThread {
                    // 只接受最新的加载结果
                    if (loadId == loadFilesCounter) {
                        files = result
                        isLoading = false
                        selectedFiles = emptySet()
                        Log.d("FileManager", "loadFiles: UI 已更新")
                        // 获取存储空间
                        loadStorageInfo()
                    } else {
                        Log.d("FileManager", "loadFiles: 已过期的加载结果 (loadId=$loadId, current=${loadFilesCounter})")
                    }
                }
            } catch (e: Exception) {
                Log.e("FileManager", "loadFiles 异常: ${e.message}", e)
                runOnUiThread {
                    if (loadId == loadFilesCounter) {
                        isLoading = false
                        statusMessage = "加载失败: ${e.message}"
                    }
                }
            }
        }.start()
    }
    
    /** 获取存储空间信息 */
    private fun loadStorageInfo() {
        Thread {
            try {
                val info = adbClient?.getStorageInfo(currentPath)
                runOnUiThread {
                    storageInfo = info
                }
            } catch (e: Exception) {
                Log.e("FileManager", "获取存储信息失败: ${e.message}")
            }
        }.start()
    }
    
    /** 过滤和排序后的文件列表 */
    private fun getFilteredAndSortedFiles(): List<FileItem> {
        var result = files
        
        // 过滤隐藏文件
        if (!showHiddenFiles) {
            result = result.filter { !it.name.startsWith(".") }
        }
        
        // 搜索过滤
        if (searchQuery.isNotEmpty()) {
            result = result.filter { it.name.contains(searchQuery, ignoreCase = true) }
        }
        
        // 排序
        result = when (sortOrder) {
            SortOrder.NAME_ASC -> result.sortedWith(compareByDescending<FileItem> { it.isDirectory }.thenBy { it.name.lowercase() })
            SortOrder.NAME_DESC -> result.sortedWith(compareByDescending<FileItem> { it.isDirectory }.thenByDescending { it.name.lowercase() })
            SortOrder.SIZE_ASC -> result.sortedWith(compareByDescending<FileItem> { it.isDirectory }.thenBy { it.size })
            SortOrder.SIZE_DESC -> result.sortedWith(compareByDescending<FileItem> { it.isDirectory }.thenByDescending { it.size })
            SortOrder.DATE_ASC -> result.sortedWith(compareByDescending<FileItem> { it.isDirectory }.thenBy { it.lastModified })
            SortOrder.DATE_DESC -> result.sortedWith(compareByDescending<FileItem> { it.isDirectory }.thenByDescending { it.lastModified })
            SortOrder.TYPE_ASC -> result.sortedWith(compareByDescending<FileItem> { it.isDirectory }.thenBy { if (it.isDirectory) "" else it.name.substringAfterLast(".", "") })
            SortOrder.TYPE_DESC -> result.sortedWith(compareByDescending<FileItem> { it.isDirectory }.thenByDescending { if (it.isDirectory) "" else it.name.substringAfterLast(".", "") })
        }
        
        return result
    }
    
    /** 批量下载 */
    private fun batchDownload() {
        if (selectedFiles.isEmpty()) return
        
        isLoading = true
        statusMessage = "正在下载 ${selectedFiles.size} 个文件..."
        
        Thread {
            var successCount = 0
            var failCount = 0
            
            for (path in selectedFiles) {
                val file = files.find { it.path == path }
                if (file != null && !file.isDirectory) {
                    val destFile = File(getExternalFilesDir(null), file.name)
                    val success = adbClient?.downloadFile(path, destFile.absolutePath) ?: false
                    if (success) successCount++ else failCount++
                }
            }
            
            runOnUiThread {
                isLoading = false
                statusMessage = "下载完成: 成功 $successCount, 失败 $failCount"
                selectedFiles = emptySet()
            }
        }.start()
    }
    
    /** 规范化路径：确保路径格式一致（不以 / 结尾，除了根目录） */
    private fun normalizePath(path: String): String {
        val normalized = path.replace("//", "/").trimEnd('/')
        return if (normalized.isEmpty()) "/" else normalized
    }
    
    /** 安全拼接路径 */
    private fun buildPath(parent: String, child: String): String {
        val parentClean = normalizePath(parent)
        // 过滤掉 child 中的特殊字符
        val safeChild = child.trim().removeSuffix("/").let { 
            it.substringAfterLast("/") // 只取最后一部分，防止路径穿越
        }
        return if (parentClean == "/") "/$safeChild" else "$parentClean/$safeChild"
    }
    
    private fun navigateUp() {
        Log.d("FileManager", "navigateUp: 当前路径 $currentPath")
        // 初始目录按返回键退出连接
        if (currentPath == "/sdcard/" || currentPath == "/sdcard" || currentPath == "/") {
            Log.d("FileManager", "navigateUp: 已在初始目录，退出连接")
            disconnect()
            return
        }
        currentPath = normalizePath(currentPath).substringBeforeLast('/', "/")
        if (currentPath.isBlank()) currentPath = "/sdcard/"
        Log.d("FileManager", "navigateUp: 新路径 $currentPath")
        loadFiles()
    }

    private fun onFileClick(file: FileItem) {
        Log.d("FileManager", "onFileClick: ${file.name}, isDir=${file.isDirectory}, path=${file.path}")
        if (selectedFiles.isNotEmpty()) {
            toggleSelection(file)
        } else if (file.isDirectory) {
            // 规范化路径，确保格式一致
            currentPath = normalizePath(file.path)
            Log.d("FileManager", "进入目录: $currentPath")
            loadFiles()
        } else {
            downloadFile(file)
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
        selectedFiles = if (selectedFiles.size == files.size) {
            emptySet()
        } else {
            files.map { it.path }.toSet()
        }
    }
    
    private fun onFileMenu(file: FileItem, action: String) {
        targetFile = file
        when (action) {
            "rename" -> { renameText = file.name; showRenameDialog = true }
            "delete" -> { selectedFiles = setOf(file.path); showDeleteDialog = true }
            "details" -> { showDetailsDialog = true }
            "download" -> { downloadFile(file) }
        }
    }
    
    private fun downloadFile(file: FileItem) {
        if (file.isDirectory) return
        
        // 保存待下载文件，启动保存文件选择器
        pendingDownloadFile = file
        saveFileLauncher.launch(file.name)
    }
    
    private fun downloadFileToUri(file: FileItem, uri: Uri) {
        isLoading = true
        statusMessage = "正在下载 ${file.name}..."
        
        Thread {
            try {
                // 先下载到临时文件
                val tempFile = File(cacheDir, file.name)
                val success = adbClient?.downloadFile(file.path, tempFile.absolutePath) ?: false
                
                if (success) {
                    // 复制到用户选择的位置
                    contentResolver.openOutputStream(uri)?.use { output ->
                        tempFile.inputStream().use { input ->
                            input.copyTo(output)
                        }
                    }
                    tempFile.delete()
                    
                    runOnUiThread {
                        isLoading = false
                        statusMessage = "下载成功"
                    }
                } else {
                    runOnUiThread {
                        isLoading = false
                        statusMessage = "下载失败"
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    isLoading = false
                    statusMessage = "下载失败: ${e.message}"
                }
            }
        }.start()
    }
    
    private fun uploadFile(uri: Uri) {
        isLoading = true
        statusMessage = "正在上传..."
        
        Thread {
            try {
                // 获取文件名
                var fileName = "unknown"
                contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (cursor.moveToFirst() && nameIndex >= 0) {
                        fileName = cursor.getString(nameIndex)
                    }
                }
                
                // 复制到临时文件
                val tempFile = File(cacheDir, fileName)
                contentResolver.openInputStream(uri)?.use { input ->
                    tempFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                
                // 上传
                val remotePath = buildPath(currentPath, fileName)
                val success = adbClient?.uploadFile(tempFile.absolutePath, remotePath) ?: false
                tempFile.delete()
                
                runOnUiThread {
                    isLoading = false
                    statusMessage = if (success) "上传成功" else "上传失败"
                    if (success) loadFiles()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    isLoading = false
                    statusMessage = "上传失败: ${e.message}"
                }
            }
        }.start()
    }
    
    private fun createFolder() {
        if (newFolderName.isBlank()) {
            statusMessage = "文件夹名称不能为空"
            return
        }
        
        showNewFolderDialog = false
        isLoading = true
        
        Thread {
            val path = buildPath(currentPath, newFolderName)
            val success = adbClient?.createFolder(path) ?: false
            
            runOnUiThread {
                isLoading = false
                statusMessage = if (success) "文件夹创建成功" else "创建失败"
                newFolderName = ""
                if (success) loadFiles()
            }
        }.start()
    }
    
    private fun renameFile() {
        if (renameText.isBlank() || targetFile == null) {
            statusMessage = "文件名不能为空"
            return
        }
        
        showRenameDialog = false
        isLoading = true
        
        Thread {
            val newPath = buildPath(currentPath, renameText)
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
        clipboard = Pair(ClipboardAction.COPY, selectedFiles.first())
        statusMessage = "已复制 ${selectedFiles.size} 项"
    }
    
    private fun cutSelected() {
        if (selectedFiles.isEmpty()) return
        clipboard = Pair(ClipboardAction.CUT, selectedFiles.first())
        statusMessage = "已剪切 ${selectedFiles.size} 项"
    }
    
    private fun pasteFiles() {
        val (action, sourcePath) = clipboard ?: return
        
        isLoading = true
        statusMessage = "正在粘贴..."
        
        Thread {
            val fileName = sourcePath.substringAfterLast('/')
            val destPath = buildPath(currentPath, fileName)
            
            val success = when (action) {
                ClipboardAction.COPY -> adbClient?.copyFile(sourcePath, destPath) ?: false
                ClipboardAction.CUT -> {
                    val copyOk = adbClient?.copyFile(sourcePath, destPath) ?: false
                    if (copyOk) {
                        adbClient?.deleteFile(sourcePath) ?: false
                    } else false
                }
            }
            
            runOnUiThread {
                isLoading = false
                statusMessage = if (success) "粘贴成功" else "粘贴失败"
                // 无论成功与否，剪切操作后都清空剪贴板
                if (action == ClipboardAction.CUT) clipboard = null
                if (success) loadFiles()
            }
        }.start()
    }
    
    private fun deleteSelected() {
        if (selectedFiles.isEmpty()) return
        showDeleteDialog = true
    }
    
    private fun performDelete() {
        showDeleteDialog = false
        
        if (selectedFiles.isEmpty()) return
        
        isLoading = true
        statusMessage = "正在删除..."
        
        Thread {
            var successCount = 0
            selectedFiles.forEach { path ->
                if (adbClient?.deleteFile(path) == true) {
                    successCount++
                }
            }
            
            runOnUiThread {
                isLoading = false
                statusMessage = if (successCount == selectedFiles.size) {
                    "已删除 ${successCount} 项"
                } else {
                    "删除了 $successCount/${selectedFiles.size} 项"
                }
                selectedFiles = emptySet()
                loadFiles()
            }
        }.start()
    }
}

// ========== UI 组件 ==========

@Composable
fun ConnectionScreen(
    ipAddress: String,
    isInstalling: Boolean,
    onIpChange: (String) -> Unit,
    onConnect: () -> Unit,
    onInstall: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "连接眼镜文件管理",
            color = BrewTextBright,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold
        )
        
        Spacer(modifier = Modifier.height(32.dp))
        
        // 安装按钮
        if (!isInstalling) {
            Button(
                onClick = onInstall,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = BrewGreen)
            ) {
                Text("安装 ScreenStream 到眼镜", color = BrewBg)
            }
            Spacer(modifier = Modifier.height(16.dp))
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = BrewGreen,
                    strokeWidth = 2.dp
                )
                Text(
                    text = "正在安装...",
                    color = BrewText,
                    modifier = Modifier.padding(start = 12.dp)
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
        
        // IP 输入
        OutlinedTextField(
            value = ipAddress,
            onValueChange = onIpChange,
            label = { Text("眼镜 IP 地址", color = BrewText) },
            textStyle = androidx.compose.ui.text.TextStyle(color = BrewTextBright),
            modifier = Modifier.fillMaxWidth()
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Button(
            onClick = onConnect,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = BrewGreen)
        ) {
            Text("连接", color = BrewBg)
        }
    }
}

@Composable
fun LoadingScreen(message: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = BrewGreen)
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
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Outlined.Error,
                contentDescription = null,
                tint = BrewRed,
                modifier = Modifier.size(64.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(error, color = BrewTextBright, fontSize = 16.sp)
            Spacer(modifier = Modifier.height(24.dp))
            Button(onClick = onRetry, colors = ButtonDefaults.buttonColors(containerColor = BrewGreen)) {
                Text("重试", color = BrewBg)
            }
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(onClick = onBack) {
                Text("返回", color = BrewText)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
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
    onPaste: () -> Unit,
    onCopy: () -> Unit,
    onCut: () -> Unit,
    onDelete: () -> Unit,
    onSelectAll: () -> Unit,
    onClearSelection: () -> Unit,
    onFileMenu: (FileItem, String) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onSortOrderChange: (FileManagerActivity.SortOrder) -> Unit,
    onShowHiddenFilesChange: (Boolean) -> Unit,
    onBatchDownload: () -> Unit
) {
    var showSortMenu by remember { mutableStateOf(false) }
    var showMoreMenu by remember { mutableStateOf(false) }
    
    // 监听系统返回键：不是根目录时返回上级，否则退出
    BackHandler {
        onNavigateUp()
    }
    
    Column(modifier = Modifier.fillMaxSize()) {
        // 顶部栏
        TopAppBar(
            title = {
                Text(
                    text = currentPath,
                    color = BrewTextBright,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回", tint = BrewTextBright)
                }
            },
            actions = {
                IconButton(onClick = onRefresh, enabled = !isLoading) {
                    Icon(Icons.Outlined.Refresh, "刷新", tint = BrewTextBright)
                }
                // 排序按钮
                IconButton(onClick = { showSortMenu = true }) {
                    Icon(Icons.Outlined.Sort, "排序", tint = BrewTextBright)
                }
                DropdownMenu(
                    expanded = showSortMenu,
                    onDismissRequest = { showSortMenu = false }
                ) {
                    DropdownMenuItem(text = { Text("名称 A-Z") }, onClick = { onSortOrderChange(FileManagerActivity.SortOrder.NAME_ASC); showSortMenu = false })
                    DropdownMenuItem(text = { Text("名称 Z-A") }, onClick = { onSortOrderChange(FileManagerActivity.SortOrder.NAME_DESC); showSortMenu = false })
                    DropdownMenuItem(text = { Text("大小 ↑") }, onClick = { onSortOrderChange(FileManagerActivity.SortOrder.SIZE_ASC); showSortMenu = false })
                    DropdownMenuItem(text = { Text("大小 ↓") }, onClick = { onSortOrderChange(FileManagerActivity.SortOrder.SIZE_DESC); showSortMenu = false })
                    DropdownMenuItem(text = { Text("日期 ↑") }, onClick = { onSortOrderChange(FileManagerActivity.SortOrder.DATE_ASC); showSortMenu = false })
                    DropdownMenuItem(text = { Text("日期 ↓") }, onClick = { onSortOrderChange(FileManagerActivity.SortOrder.DATE_DESC); showSortMenu = false })
                    DropdownMenuItem(text = { Text("类型 A-Z") }, onClick = { onSortOrderChange(FileManagerActivity.SortOrder.TYPE_ASC); showSortMenu = false })
                    DropdownMenuItem(text = { Text("类型 Z-A") }, onClick = { onSortOrderChange(FileManagerActivity.SortOrder.TYPE_DESC); showSortMenu = false })
                }
                // 更多选项
                IconButton(onClick = { showMoreMenu = true }) {
                    Icon(Icons.Outlined.MoreVert, "更多", tint = BrewTextBright)
                }
                DropdownMenu(
                    expanded = showMoreMenu,
                    onDismissRequest = { showMoreMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(if (showHiddenFiles) "隐藏隐藏文件" else "显示隐藏文件") },
                        onClick = { onShowHiddenFilesChange(!showHiddenFiles); showMoreMenu = false }
                    )
                    if (selectedFiles.isNotEmpty()) {
                        DropdownMenuItem(
                            text = { Text("批量下载 (${selectedFiles.size})") },
                            onClick = { onBatchDownload(); showMoreMenu = false }
                        )
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = BrewPanel)
        )
        
        // 搜索栏
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchQueryChange,
            placeholder = { Text("搜索文件...", color = BrewText) },
            leadingIcon = { Icon(Icons.Outlined.Search, null, tint = BrewText) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { onSearchQueryChange("") }) {
                        Icon(Icons.Outlined.Close, "清除", tint = BrewText)
                    }
                }
            },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = BrewGreen,
                unfocusedBorderColor = BrewPanelAlt,
                focusedTextColor = BrewTextBright,
                unfocusedTextColor = BrewTextBright
            )
        )
        
        // 返回上级已改用系统返回键
        
        // 工具栏
        if (selectedFiles.isNotEmpty()) {
            SelectionToolbar(
                count = selectedFiles.size,
                onCopy = onCopy,
                onCut = onCut,
                onDelete = onDelete,
                onSelectAll = onSelectAll,
                onClear = onClearSelection
            )
        } else {
            MainToolbar(
                hasClipboard = hasClipboard,
                onNewFolder = onNewFolder,
                onUpload = onUpload,
                onPaste = onPaste
            )
        }
        
        // 状态消息
        if (statusMessage.isNotEmpty()) {
            Text(
                text = statusMessage,
                color = BrewText,
                fontSize = 12.sp,
                modifier = Modifier.padding(8.dp)
            )
        }
        
        // 文件列表
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            if (isLoading) {
                // 加载动画居中显示
                CircularProgressIndicator(
                    color = BrewGreen,
                    modifier = Modifier.align(Alignment.Center)
                )
            } else if (files.isEmpty()) {
                // 空目录提示
                Text(
                    text = if (searchQuery.isNotEmpty()) "未找到匹配的文件" else "目录为空",
                    color = BrewText,
                    modifier = Modifier.align(Alignment.Center)
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(files) { file ->
                        FileRow(
                            file = file,
                            isSelected = selectedFiles.contains(file.path),
                            onClick = { onFileClick(file) },
                            onLongClick = { onFileLongClick(file) },
                            onMenuAction = { action -> onFileMenu(file, action) }
                        )
                    }
                }
            }
        }
        
        // 底部状态栏
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = BrewPanel,
            tonalElevation = 2.dp
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                // 路径和文件数
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Folder,
                        contentDescription = null,
                        tint = BrewText,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = currentPath,
                        color = BrewText,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "${files.size} 项",
                        color = BrewText,
                        fontSize = 12.sp
                    )
                }
                // 存储空间
                storageInfo?.let { info ->
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Storage,
                            contentDescription = null,
                            tint = BrewText,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "${info.formatSize(info.usedBytes)} / ${info.formatSize(info.totalBytes)}",
                            color = BrewText,
                            fontSize = 11.sp,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "${info.usedPercent}%",
                            color = if (info.usedPercent > 90) BrewRed else BrewText,
                            fontSize = 11.sp
                        )
                    }
                    // 进度条
                    LinearProgressIndicator(
                        progress = { info.usedPercent / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .padding(top = 4.dp),
                        color = if (info.usedPercent > 90) BrewRed else BrewGreen,
                        trackColor = BrewPanelAlt
                    )
                }
            }
        }
        
        // 取消选择提示
        if (selectedFiles.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(BrewPanel)
                    .clickable { onClearSelection() }
                    .padding(12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("点击取消选择", color = BrewText, fontSize = 12.sp)
            }
        }
    }
}

@Composable
fun MainToolbar(
    hasClipboard: Boolean,
    onNewFolder: () -> Unit,
    onUpload: () -> Unit,
    onPaste: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(BrewPanel)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onNewFolder) {
            Icon(Icons.Outlined.CreateNewFolder, "新建文件夹", tint = BrewTextBright)
        }
        IconButton(onClick = onUpload) {
            Icon(Icons.Outlined.Upload, "上传", tint = BrewTextBright)
        }
        if (hasClipboard) {
            IconButton(onClick = onPaste) {
                Icon(Icons.Outlined.ContentPaste, "粘贴", tint = BrewTextBright)
            }
        }
    }
}

@Composable
fun SelectionToolbar(
    count: Int,
    onCopy: () -> Unit,
    onCut: () -> Unit,
    onDelete: () -> Unit,
    onSelectAll: () -> Unit,
    onClear: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(BrewPanelAlt)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("已选 $count 项", color = BrewTextBright, modifier = Modifier.padding(end = 12.dp))
        IconButton(onClick = onCopy) {
            Icon(Icons.Outlined.ContentCopy, "复制", tint = BrewTextBright)
        }
        IconButton(onClick = onCut) {
            Icon(Icons.Outlined.ContentCut, "剪切", tint = BrewTextBright)
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Outlined.Delete, "删除", tint = BrewRed)
        }
        Spacer(modifier = Modifier.weight(1f))
        TextButton(onClick = onSelectAll) {
            Text("全选", color = BrewTextBright)
        }
        TextButton(onClick = onClear) {
            Text("取消", color = BrewTextBright)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun FileRow(
    file: FileItem,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMenuAction: (String) -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isSelected) BrewPanelAlt else BrewBg)
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
                onLongClick = onLongClick
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 图标
        Icon(
            imageVector = if (file.isDirectory) Icons.Outlined.Folder else Icons.Outlined.InsertDriveFile,
            contentDescription = null,
            tint = if (file.isDirectory) BrewAmber else BrewTextBright,
            modifier = Modifier.size(24.dp)
        )
        
        // 文件信息
        Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
            Text(
                text = file.name,
                color = BrewTextBright,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!file.isDirectory) {
                Text(
                    text = formatFileSize(file.size),
                    color = BrewText,
                    fontSize = 12.sp
                )
            }
        }
        
        // 菜单按钮
        Box {
            IconButton(onClick = { showMenu = true }) {
                Icon(Icons.Outlined.MoreVert, "更多", tint = BrewText)
            }
            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false }
            ) {
                if (!file.isDirectory) {
                    DropdownMenuItem(
                        text = { Text("下载") },
                        onClick = { showMenu = false; onMenuAction("download") }
                    )
                }
                DropdownMenuItem(
                    text = { Text("重命名") },
                    onClick = { showMenu = false; onMenuAction("rename") }
                )
                DropdownMenuItem(
                    text = { Text("详情") },
                    onClick = { showMenu = false; onMenuAction("details") }
                )
                DropdownMenuItem(
                    text = { Text("删除") },
                    onClick = { showMenu = false; onMenuAction("delete") }
                )
            }
        }
    }
}

// ========== 对话框 ==========

@Composable
fun NewFolderDialog(
    name: String,
    onNameChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("新建文件夹", color = BrewTextBright) },
        containerColor = BrewPanel,
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = onNameChange,
                label = { Text("文件夹名称", color = BrewText) },
                textStyle = androidx.compose.ui.text.TextStyle(color = BrewTextBright),
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(onClick = onConfirm, colors = ButtonDefaults.buttonColors(containerColor = BrewGreen)) {
                Text("创建", color = BrewBg)
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text("取消", color = BrewTextBright)
            }
        }
    )
}

@Composable
fun RenameDialog(
    name: String,
    onNameChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("重命名", color = BrewTextBright) },
        containerColor = BrewPanel,
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = onNameChange,
                label = { Text("新名称", color = BrewText) },
                textStyle = androidx.compose.ui.text.TextStyle(color = BrewTextBright),
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(onClick = onConfirm, colors = ButtonDefaults.buttonColors(containerColor = BrewGreen)) {
                Text("确定", color = BrewBg)
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text("取消", color = BrewTextBright)
            }
        }
    )
}

@Composable
fun DeleteConfirmDialog(
    count: Int,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("确认删除", color = BrewTextBright) },
        text = { Text("确定要删除选中的 $count 个项目吗？", color = BrewText) },
        containerColor = BrewPanel,
        confirmButton = {
            Button(onClick = onConfirm, colors = ButtonDefaults.buttonColors(containerColor = BrewRed)) {
                Text("删除", color = BrewBg)
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text("取消", color = BrewTextBright)
            }
        }
    )
}

@Composable
fun DetailsDialog(
    file: FileItem,
    onDismiss: () -> Unit
) {
    val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("文件详情", color = BrewTextBright) },
        containerColor = BrewPanel,
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (file.isDirectory) Icons.Outlined.Folder else Icons.Outlined.InsertDriveFile,
                        contentDescription = null,
                        tint = if (file.isDirectory) BrewAmber else BrewTextBright,
                        modifier = Modifier.size(48.dp)
                    )
                    Column(modifier = Modifier.padding(start = 12.dp)) {
                        Text(file.name, color = BrewTextBright, fontSize = 16.sp)
                        Text(if (file.isDirectory) "文件夹" else "文件", color = BrewText, fontSize = 12.sp)
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                if (!file.isDirectory) {
                    Text("大小: ${formatFileSize(file.size)}", color = BrewText)
                }
                Text("修改时间: ${dateFormat.format(Date(file.lastModified))}", color = BrewText)
                Text("路径: ${file.path}", color = BrewText, fontSize = 12.sp)
            }
        },
        confirmButton = {
            Button(onClick = onDismiss, colors = ButtonDefaults.buttonColors(containerColor = BrewGreen)) {
                Text("确定", color = BrewBg)
            }
        }
    )
}

@Composable
fun PreviewDialog(
    fileName: String,
    content: String?,
    isLoading: Boolean,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { 
            Text(
                text = "预览: $fileName",
                color = BrewTextBright,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        containerColor = BrewPanel,
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(400.dp)
            ) {
                if (isLoading) {
                    CircularProgressIndicator(
                        color = BrewGreen,
                        modifier = Modifier.align(Alignment.Center)
                    )
                } else {
                    val scrollState = rememberScrollState()
                    Text(
                        text = content ?: "无法读取内容",
                        color = BrewTextBright,
                        fontSize = 12.sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(scrollState)
                            .padding(4.dp)
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss, colors = ButtonDefaults.buttonColors(containerColor = BrewGreen)) {
                Text("关闭", color = BrewBg)
            }
        }
    )
}

// ========== 工具函数 ==========

fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        bytes < 1024 * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
        else -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
    }
}
