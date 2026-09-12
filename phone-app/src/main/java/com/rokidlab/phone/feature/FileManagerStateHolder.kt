package com.rokidlab.phone.feature

import android.content.Context
import android.content.ActivityNotFoundException
import android.content.SharedPreferences
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.content.FileProvider
import com.rokidlab.phone.R
import com.rokidlab.phone.adb.*
import com.rokidlab.phone.app.LabApplication
import com.rokidlab.phone.connection.ConnectionRoute
import com.rokidlab.phone.domain.FileTransferService
import com.rokidlab.phone.filemanager.*
import com.rokidlab.phone.model.*
import com.rokidlab.phone.util.AppConfig
import com.rokidlab.phone.util.LogCollector
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors

/**
 * 文件管理状态机（Phase 5：连接生命周期 + 文件 IO 全链路从 FileManagerActivity 逐字迁出）。
 *
 * 设计：**UI 状态留在 Activity**（Compose 直接读，零改动）；本类持有 ADB 客户端与 IO 线程池，
 * 承担 connect / 列目录 / 导航 / 选择 / 剪贴板 / 预览 / 重命名 / 新建目录 / 删除 / 上传 / 下载落盘 /
 * APK 安装 / 存储信息等业务逻辑。Activity 保留同名私有一行门面 + ActivityResult 启动器。
 */
internal class FileManagerStateHolder(private val activity: FileManagerActivity) {
    private companion object {
        const val TAG = "FileManager"
    }

    /** onCreate 逻辑：读偏好、解析入参、发起连接（Compose 编排仍留在 Activity）。 */
    /** 下载入口委托：Activity 持有系统保存对话框启动器（ActivityResult 必须在 Activity 注册）。 */
    internal fun requestDownload(file: FileItem) = activity.downloadFile(file)

    /** Activity 销毁：停线程池、断链、释放蓝牙租约（幂等）。 */
    internal fun onDestroy() {
        ioExecutor.shutdownNow()
        adbClient?.disconnect()
        // 释放蓝牙通道租约（幂等）：让 BACKGROUND 兜底轮询恢复使用共享 ADB 会话
        fileTransfer.releaseLease()
    }

    internal fun init() {
        prefs = activity.getSharedPreferences("rokidlab", Context.MODE_PRIVATE)
        activity.useRealInstall =
            activity.intent.getBooleanExtra(FileManagerActivity.EXTRA_USE_REAL_INSTALL, false)
        val app = activity.application as LabApplication
        activity.ipAddress = app.fileManagerIp
        activity.isConnecting = true
        connect()
    }

    internal var adbClient: AdbFileManagerClient? = null
    /** L3 文件传输连接服务（Phase 3：路由 + 蓝牙租约上收到 domain/FileTransferService） */
    internal val fileTransfer by lazy { com.rokidlab.phone.domain.FileTransferService(activity.application as LabApplication) }
    internal lateinit var prefs: SharedPreferences
    internal val ioExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "fm-io").also { it.isDaemon = true } }

    internal fun disconnect() {
        adbClient?.disconnect()
        adbClient = null
        activity.isConnected = false
        activity.isConnecting = false
        activity.connectionError = null
        activity.currentPath = "/sdcard/"
        activity.files = emptyList()
        activity.selectedFiles = emptySet()
    }

    internal fun connect() {
        // 先清理旧连接
        adbClient?.disconnect()
        adbClient = null
        activity.isConnected = false
        activity.isConnecting = true
        activity.connectionError = null
        Log.i(TAG, "Starting connection: ${activity.ipAddress}:${AppConfig.DEFAULT_ADB_PORT}")

        Thread {
            try {
                val route = fileTransfer.resolveRoute(activity.ipAddress, AppConfig.DEFAULT_ADB_PORT)
                val (targetIp, targetPort) = when (route) {
                    is ConnectionRoute.Wifi -> route.ip to route.port
                    is ConnectionRoute.Bluetooth -> route.ip to route.localPort
                    is ConnectionRoute.None -> {
                        activity.runOnUiThread {
                            activity.isConnecting = false
                            activity.connectionError = "No route to glasses (WiFi and BT both unavailable)"
                        }
                        return@Thread
                    }
                }
                Log.i(TAG, "Route: $route, connecting to $targetIp:$targetPort")
                // 长连接上场：先让全 App 共享 ADB 会话腾出 RFCOMM 通道，再按 LONG_LIVED
                // 优先级占用蓝牙通道。手机侧蓝牙栈对「同一设备 + 同一 SCN」只允许一条
                // 客户端 RFCOMM 通道，共享会话还占着的话这里会建链被拒（界面「连接失败」）。
                // 仅蓝牙线路需要；WiFi 各走各的 TCP，无 SCN 争抢。
                if (route is ConnectionRoute.Bluetooth) {
                    fileTransfer.acquireBluetoothLease()
                }
                val client = AdbFileManagerClient(activity, targetIp, targetPort)
                Log.i(TAG, "Calling connect method")
                val success = client.connect { status ->
                    Log.i(TAG, "Connection status: $status")
                    activity.runOnUiThread { activity.statusMessage = status }
                }
                
                Log.i(TAG, "Connection result: $success")
                activity.runOnUiThread {
                    activity.isConnecting = false
                    if (success) {
                        activity.isConnected = true
                        adbClient = client
                        try {
                            loadFiles()
                        } catch (e: java.util.concurrent.RejectedExecutionException) {
                            Log.w(TAG, "Executor shut down, skipping loadFiles")
                        }
                    } else {
                        activity.connectionError = activity.getString(R.string.connection_failed_check_ip)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Connection error: ${e.message}", e)
                activity.runOnUiThread {
                    activity.isConnecting = false
                    activity.connectionError = activity.getString(R.string.connection_error_format, e.message)
                }
            }
        }.start()
    }

    internal var loadFilesCounter = 0
    
    internal fun loadFiles() {
        activity.isLoading = true
        activity.statusMessage = ""
        val targetPath = activity.currentPath
        val loadId = ++loadFilesCounter
        
        ioExecutor.execute {
            try {
                val client = adbClient
                if (client?.isConnected() != true) {
                    activity.runOnUiThread {
                        if (loadId == loadFilesCounter) {
                            activity.isLoading = false
                            activity.isConnected = false
                            activity.connectionError = activity.getString(R.string.connection_lost_reconnect)
                        }
                    }
                    return@execute
                }
                
                val result = client.listFiles(targetPath)
                
                activity.runOnUiThread {
                    if (loadId == loadFilesCounter) {
                        activity.files = result
                        activity.isLoading = false
                        // 同步加载存储信息（不额外开线程）
                        loadStorageInfoSync()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "loadFiles error: ${e.message}", e)
                activity.runOnUiThread {
                    if (loadId == loadFilesCounter) {
                        activity.isLoading = false
                        activity.isConnected = false
                        activity.connectionError = activity.getString(R.string.connection_lost_reconnect)
                    }
                }
            }
        }
    }

    internal fun loadStorageInfoSync() {
        ioExecutor.execute {
            try {
                val client = adbClient
                if (client?.isConnected() != true) return@execute
                val info = client.getStorageInfo(activity.currentPath)
                activity.runOnUiThread { activity.storageInfo = info }
            } catch (e: Exception) {
                Log.e(TAG, "loadStorageInfo error: ${e.message}", e)
            }
        }
    }

    internal fun navigateUp() {
        Log.i(TAG, "navigateUp called, current path: ${activity.currentPath}")
        
        // 移除末尾的斜杠进行处理
        val normalizedPath = activity.currentPath.trimEnd('/')
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
        
        if (parentPath != activity.currentPath) {
            activity.currentPath = parentPath
            loadFiles()
        }
    }

    internal fun onFileClick(file: FileItem) {
        if (activity.selectedFiles.isNotEmpty()) {
            toggleSelection(file)
            return
        }
        
        if (file.isDirectory) {
            activity.currentPath = file.path
            loadFiles()
        } else {
            openFile(file)
        }
    }

    internal fun toggleSelection(file: FileItem) {
        activity.selectedFiles = if (activity.selectedFiles.contains(file.path)) {
            activity.selectedFiles - file.path
        } else {
            activity.selectedFiles + file.path
        }
    }

    internal fun selectAll() {
        activity.selectedFiles = activity.files.map { it.path }.toSet()
    }

    internal fun clearSelection() {
        activity.selectedFiles = emptySet()
    }

    internal fun clearClipboard() {
        activity.clipboard = null
        activity.statusMessage = activity.getString(R.string.clipboard_cleared)
    }

    internal fun openFile(file: FileItem) {
        val ext = file.name.substringAfterLast('.', "").lowercase()
        if (ext in listOf("txt", "md", "json", "xml", "log")) {
            previewFile(file)
        } else {
            requestDownload(file)
        }
    }

    internal fun previewFile(file: FileItem) {
        activity.targetFile = file
        activity.showPreviewDialog = true
        activity.previewLoading = true
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
                    val localPath = activity.cacheDir.absolutePath + "/" + file.name
                    val success = adbClient?.downloadFile(file.path, localPath) ?: false
                    
                    activity.runOnUiThread {
                        activity.previewLoading = false
                        if (success) {
                            activity.previewContent = localPath // 存储本地路径用于图片预览
                            Log.i(TAG, "Image download successful: $localPath")
                        } else {
                            activity.previewContent = activity.getString(R.string.image_download_failed)
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
                    
                    activity.runOnUiThread {
                        activity.previewLoading = false
                        activity.previewContent = content ?: activity.getString(R.string.cannot_read_content)
                    }
                } else {
                    // 其他文件
                    activity.runOnUiThread {
                        activity.previewLoading = false
                        activity.previewContent = activity.getString(R.string.preview_not_supported) + "\n\n" + activity.getString(R.string.name_label) + ": ${file.name}\n" + activity.getString(R.string.size_label) + ": ${formatSize(file.size)}"
                    }
                    Log.i(TAG, "Unsupported file type")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Preview failed: ${e.message}", e)
                activity.runOnUiThread {
                    activity.previewLoading = false
                    activity.previewContent = activity.getString(R.string.preview_failed_format, e.message)
                }
            }
        }.start()
    }

    internal fun downloadFileToUri(file: FileItem, uri: Uri) {
        activity.isLoading = true
        activity.statusMessage = activity.getString(R.string.download_status)
        
        ioExecutor.execute {
            val client = adbClient
            if (client?.isConnected() != true) {
                activity.runOnUiThread { activity.isLoading = false; activity.isConnected = false }
                return@execute
            }
            val localPath = activity.cacheDir.absolutePath + "/" + file.name
            val success = client.downloadFile(file.path, localPath)
            
            if (!success) {
                LogCollector.e(TAG, "Download failed: ${file.path}")
            }
            
            if (success) {
                try {
                    activity.contentResolver.openOutputStream(uri)?.use { outputStream ->
                        File(localPath).inputStream().use { inputStream ->
                            inputStream.copyTo(outputStream)
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Copy file failed: ${e.message}")
                }
                File(localPath).delete()
            }
            
            activity.runOnUiThread {
                activity.isLoading = false
                activity.statusMessage = if (success) activity.getString(R.string.download_success) else activity.getString(R.string.download_failed)
            }
        }
    }

    internal fun uploadFile(uri: Uri) {
        activity.isLoading = true
        activity.statusMessage = activity.getString(R.string.upload_status)
        
        ioExecutor.execute {
            val fileName = uri.getFileName() ?: "unknown"
            val targetPath = buildPath(activity.currentPath, fileName)
            
            val client = adbClient
            if (client?.isConnected() != true) {
                activity.runOnUiThread {
                    activity.isLoading = false
                    activity.statusMessage = activity.getString(R.string.connection_lost_reconnect)
                    activity.isConnected = false
                }
                return@execute
            }
            
            val localPath = activity.cacheDir.absolutePath + "/" + fileName
            try {
                activity.contentResolver.openInputStream(uri)?.use { inputStream ->
                    File(localPath).outputStream().use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Copy file failed: ${e.message}", e)
            }
            
            val success = client.uploadFile(localPath, targetPath)
            File(localPath).delete()
            
            if (!success) {
                LogCollector.e(TAG, "Upload failed: $fileName -> $targetPath")
            }
            
            activity.runOnUiThread {
                activity.isLoading = false
                if (success) {
                    activity.statusMessage = activity.getString(R.string.file_upload_success)
                    loadFiles()
                } else {
                    activity.statusMessage = activity.getString(R.string.file_upload_failed)
                }
            }
        }
    }

    internal fun createNewFolder(name: String) {
        activity.showNewFolderDialog = false
        activity.isLoading = true
        
        ioExecutor.execute {
            val client = adbClient
            if (client?.isConnected() != true) {
                activity.runOnUiThread { activity.isLoading = false; activity.isConnected = false }
                return@execute
            }
            val newPath = buildPath(activity.currentPath, name)
            val success = client.createFolder(newPath)
            
            if (!success) {
                LogCollector.e(TAG, "Create folder failed: $newPath")
            }
            
            activity.runOnUiThread {
                activity.isLoading = false
                activity.statusMessage = if (success) activity.getString(R.string.folder_create_success) else activity.getString(R.string.folder_create_failed)
                if (success) loadFiles()
            }
        }
    }

    internal fun renameSelected() {
        val filePath = activity.selectedFiles.firstOrNull() ?: return
        activity.files.find { it.path == filePath }?.let {
            activity.targetFile = it
            activity.renameText = it.name
            activity.showRenameDialog = true
        }
    }

    internal fun renameFile(newName: String) {
        if (newName.isBlank() || activity.targetFile == null) {
            activity.statusMessage = activity.getString(R.string.filename_empty)
            return
        }
        
        activity.showRenameDialog = false
        activity.isLoading = true
        
        ioExecutor.execute {
            val client = adbClient
            if (client?.isConnected() != true) {
                activity.runOnUiThread { activity.isLoading = false; activity.isConnected = false }
                return@execute
            }
            val newPath = buildPath(activity.currentPath, newName)
            val success = client.renameFile(activity.targetFile!!.path, newPath)
            
            if (!success) {
                LogCollector.e(TAG, "Rename failed: ${activity.targetFile!!.path} -> $newPath")
            }
            
            activity.runOnUiThread {
                activity.isLoading = false
                activity.statusMessage = if (success) activity.getString(R.string.rename_success) else activity.getString(R.string.rename_failed)
                activity.targetFile = null
                activity.renameText = ""
                if (success) loadFiles()
            }
        }
    }

    internal fun copySelected() {
        if (activity.selectedFiles.isEmpty()) return
        activity.clipboard = Pair(ClipboardAction.COPY, activity.selectedFiles)
        activity.statusMessage = activity.getString(R.string.copied_items, activity.selectedFiles.size)
        activity.selectedFiles = emptySet()
    }

    internal fun cutSelected() {
        if (activity.selectedFiles.isEmpty()) return
        activity.clipboard = Pair(ClipboardAction.CUT, activity.selectedFiles)
        activity.statusMessage = activity.getString(R.string.cut_items, activity.selectedFiles.size)
        activity.selectedFiles = emptySet()
    }

    internal fun pasteFiles() {
        activity.clipboard?.let { (action, paths) ->
            activity.isLoading = true
            activity.statusMessage = activity.getString(R.string.pasting_status)
            
            ioExecutor.execute {
                val client = adbClient
                if (client?.isConnected() != true) {
                    activity.runOnUiThread { activity.isLoading = false; activity.isConnected = false }
                    return@execute
                }
                var successCount = 0
                val failedPaths = mutableListOf<String>()
                paths.forEach { path ->
                    val fileName = path.substringAfterLast('/')
                    val newPath = buildPath(activity.currentPath, fileName)
                    if (action == ClipboardAction.COPY) {
                        if (client.copyFile(path, newPath)) successCount++
                        else failedPaths.add(path)
                    } else {
                        // Move = copy + delete（仅 copy 确认成功后才删源，防数据丢失）
                        if (client.copyFile(path, newPath)) {
                            if (client.deleteFile(path)) {
                                successCount++
                            } else {
                                // copy 成功但删源失败：目标已就位，只提示源未清理
                                successCount++
                                LogCollector.w(TAG, "Move: copied but failed to remove source: $path")
                            }
                        } else {
                            failedPaths.add(path)
                        }
                    }
                }
                
                if (failedPaths.isNotEmpty()) {
                    LogCollector.e(TAG, "Paste failed for ${failedPaths.size}/${paths.size} items: ${failedPaths.take(3)}")
                }
                
                activity.runOnUiThread {
                    activity.isLoading = false
                    activity.statusMessage = if (successCount == paths.size) {
                        activity.getString(R.string.pasted_items, successCount)
                    } else {
                        activity.getString(R.string.pasted_items, successCount) + "/${paths.size}"
                    }
                    if (action == ClipboardAction.CUT) {
                        activity.clipboard = null
                    }
                    loadFiles()
                }
            }
        }
    }

    internal fun deleteSelected() {
        if (activity.selectedFiles.isEmpty()) return
        activity.showDeleteDialog = true
    }

    internal fun deleteSelectedFiles() {
        activity.showDeleteDialog = false
        activity.isLoading = true
        activity.statusMessage = activity.getString(R.string.deleting_status)
        
        ioExecutor.execute {
            val client = adbClient
            if (client?.isConnected() != true) {
                activity.runOnUiThread { activity.isLoading = false; activity.isConnected = false }
                return@execute
            }
            var successCount = 0
            val failedPaths = mutableListOf<String>()
            activity.selectedFiles.forEach { path ->
                if (client.deleteFile(path)) successCount++
                else failedPaths.add(path)
            }
            
            if (failedPaths.isNotEmpty()) {
                LogCollector.e(TAG, "Delete failed for ${failedPaths.size}/${activity.selectedFiles.size} items: ${failedPaths.take(3)}")
            }
            
            activity.runOnUiThread {
                activity.isLoading = false
                activity.statusMessage = if (successCount == activity.selectedFiles.size) {
                        activity.getString(R.string.deleted_items, successCount)
                    } else {
                        activity.getString(R.string.deleted_items, successCount) + "/${activity.selectedFiles.size}"
                    }
                activity.selectedFiles = emptySet()
                loadFiles()
            }
        }
    }

    internal fun installApk(file: FileItem) {
        if (!file.name.lowercase().endsWith(".apk")) {
            activity.statusMessage = activity.getString(R.string.not_apk_file)
            return
        }
        
        activity.installingApkPath = file.path
        activity.apkInstallProgress = 0
        activity.apkInstallStatus = activity.getString(R.string.preparing_install)
        
        Thread {
            try {
                activity.apkInstallStatus = activity.getString(R.string.installing_apk)
                activity.apkInstallProgress = 50
                
                // 直接在眼镜端通过 ADB shell 执行 pm install，无需下载到手机再上传
                val result = adbClient?.executeShellCommand("pm install -r \"${file.path}\"") ?: ""
                val isSuccess = !result.contains("Failure", ignoreCase = true) &&
                    (result.contains("Success", ignoreCase = true) || result.isBlank())
                
                activity.runOnUiThread {
                    if (isSuccess) {
                        activity.apkInstallSuccess = true
                        activity.apkInstallStatus = activity.getString(R.string.install_completed)
                        activity.apkInstallProgress = 100
                        activity.statusMessage = activity.getString(R.string.apk_install_completed, file.name)
                    } else {
                        activity.apkInstallSuccess = false
                        val errorLine = result.lines().firstOrNull { it.isNotBlank() } ?: activity.getString(R.string.unknown_error)
                        LogCollector.e(TAG, "APK install failed: ${file.path} — $errorLine")
                        activity.apkInstallStatus = activity.getString(R.string.apk_install_failed, errorLine)
                    }
                    
                    // 3秒后清除状态
                    Thread {
                        Thread.sleep(3000)
                        activity.runOnUiThread {
                            activity.installingApkPath = null
                            activity.apkInstallProgress = 0
                            activity.apkInstallStatus = null
                            activity.apkInstallSuccess = false
                        }
                    }.start()
                }
            } catch (e: Exception) {
                Log.e(TAG, "APK安装失败: ${e.message}", e)
                activity.runOnUiThread {
                    activity.apkInstallSuccess = false
                    activity.apkInstallStatus = activity.getString(R.string.apk_install_failed, e.message ?: "Unknown")
                    activity.installingApkPath = null
                }
            }
        }.start()
    }

    internal fun showFileDetails(file: FileItem) {
        activity.targetFile = file
        activity.showDetailsDialog = true
    }

    internal fun buildPath(parent: String, name: String): String {
        return if (parent == "/") "/$name" else "$parent/$name"
    }

    internal fun Uri.getFileName(): String? {
        return activity.contentResolver.query(this, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
            } else {
                null
            }
        }
    }

    internal fun getFilteredAndSortedFiles(): List<FileItem> {
        var result = activity.files
        
        if (activity.searchQuery.isNotBlank()) {
            result = result.filter { it.name.contains(activity.searchQuery, ignoreCase = true) }
        }
        
        if (!activity.showHiddenFiles) {
            result = result.filter { !it.name.startsWith('.') }
        }
        
        result = result.sortedWith(Comparator<FileItem> { a, b ->
            when {
                a.isDirectory != b.isDirectory -> if (a.isDirectory) -1 else 1
                else -> when (activity.sortOrder) {
                    FileManagerActivity.SortOrder.NAME_ASC -> a.name.lowercase().compareTo(b.name.lowercase())
                    FileManagerActivity.SortOrder.NAME_DESC -> b.name.lowercase().compareTo(a.name.lowercase())
                    FileManagerActivity.SortOrder.SIZE_ASC -> a.size.compareTo(b.size)
                    FileManagerActivity.SortOrder.SIZE_DESC -> b.size.compareTo(a.size)
                    FileManagerActivity.SortOrder.DATE_ASC -> a.lastModified.compareTo(b.lastModified)
                    FileManagerActivity.SortOrder.DATE_DESC -> b.lastModified.compareTo(a.lastModified)
                    FileManagerActivity.SortOrder.TYPE_ASC -> a.name.substringAfterLast('.', "").lowercase()
                        .compareTo(b.name.substringAfterLast('.', "").lowercase())
                    FileManagerActivity.SortOrder.TYPE_DESC -> b.name.substringAfterLast('.', "").lowercase()
                        .compareTo(a.name.substringAfterLast('.', "").lowercase())
                }
            }
        })
        
        return result
    }
}
