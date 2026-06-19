package com.rokidlab.phone.adb

import com.rokidlab.phone.util.AppConfig
import com.rokidlab.phone.R
import android.content.Context
import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.text.SimpleDateFormat
import java.util.*

class AdbFileManagerClient(
    private val context: Context,
    private val ipAddress: String,
    private val port: Int = 5555,
) {
    private var socket: Socket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    private var keyPair: KeyPair? = null
    private var localId = AtomicInteger(1)
    private val lock = Any() // 同步锁，防止并发操作

    companion object {
        private const val TAG = "AdbFileManager"

        private const val CMD_CNXN = 0x4e584e43
        private const val CMD_AUTH = 0x48545541
        private const val CMD_OPEN = 0x4e45504f
        private const val CMD_OKAY = 0x59414b4f
        private const val CMD_CLSE = 0x45534c43
        private const val CMD_WRTE = 0x45545257

        private const val AUTH_TOKEN = 1
        private const val AUTH_SIGNATURE = 2
        private const val AUTH_RSA_PUBLIC = 3

        private const val CONNECT_VERSION = 0x01000000
        private const val HEADER_LENGTH = 24

        // 解析 ls -la 的时间格式
        // 格式1: "2024-01-15" "10:30" -> 年-月-日 时:分
        // 格式2: "Jun" "7" -> 月 日 (当年)
        fun parseDateTime(dateStr: String, timeStr: String): Long {
            val currentYear = Calendar.getInstance().get(Calendar.YEAR)
            
            // 格式1: 年-月-日 格式 (2024-01-15)
            if (dateStr.contains("-") && dateStr.length == 10) {
                val format = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                return try {
                    format.parse("$dateStr $timeStr")?.time ?: 0L
                } catch (e: Exception) {
                    0L
                }
            }
            
            // 月份名称映射
            val monthMap = mapOf(
                "Jan" to 0, "Feb" to 1, "Mar" to 2, "Apr" to 3,
                "May" to 4, "Jun" to 5, "Jul" to 6, "Aug" to 7,
                "Sep" to 8, "Oct" to 9, "Nov" to 10, "Dec" to 11
            )
            
            val month = monthMap[dateStr] ?: return 0L
            val day = timeStr.toIntOrNull() ?: return 0L
            
            val cal = Calendar.getInstance()
            cal.set(Calendar.YEAR, currentYear)
            cal.set(Calendar.MONTH, month)
            cal.set(Calendar.DAY_OF_MONTH, day)
            cal.set(Calendar.HOUR_OF_DAY, 0)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            
            return cal.timeInMillis
        }
    }

    fun connect(onStatus: (String) -> Unit): Boolean {
        return try {
            onStatus(context.getString(R.string.file_manager_connecting, ipAddress, port))
            Log.i(TAG, "Connecting to $ipAddress:$port")

            socket = Socket()
            socket?.tcpNoDelay = true
            socket?.soTimeout = 10000 // 10秒超时
            socket?.connect(java.net.InetSocketAddress(ipAddress, port), 10000) // 10秒连接超时
            inputStream = socket?.getInputStream()
            outputStream = socket?.getOutputStream()
            Log.i(TAG, "TCP connection established")
            onStatus(context.getString(R.string.file_manager_auth))

            keyPair = AdbKeyManager.getOrCreateKeyPair(context.filesDir.absolutePath)
            doHandshake()
            Log.i(TAG, "ADB connection successful")
            onStatus(context.getString(R.string.file_manager_connected))
            true
        } catch (e: Exception) {
            Log.e(TAG, "Connection failed: ${e.message}", e)
            onStatus(context.getString(R.string.file_manager_connection_failed, e.message))
            disconnect()
            false
        }
    }

    @Throws(Exception::class)
    private fun doHandshake() {
        val kp = keyPair ?: throw IllegalStateException("keyPair not initialized")
        var sentSignature = false
        val cnxnPayload = "host::\u0000".toByteArray(Charsets.UTF_8)
        sendPacket(CMD_CNXN, CONNECT_VERSION, AppConfig.ADB_MAX_PAYLOAD, cnxnPayload)

        while (true) {
            val msg = readPacket()
            when (msg.command) {
                CMD_CNXN -> return
                CMD_AUTH -> {
                    if (msg.arg0 == AUTH_TOKEN) {
                        if (!sentSignature) {
                            val sig = Signature.getInstance("SHA1withRSA")
                            sig.initSign(kp.private)
                            sig.update(msg.payload)
                            sendPacket(CMD_AUTH, AUTH_SIGNATURE, 0, sig.sign())
                            sentSignature = true
                        } else {
                            val pubKeyPayload = getAdbPublicKeyPayload()
                            sendPacket(CMD_AUTH, AUTH_RSA_PUBLIC, 0, pubKeyPayload)
                        }
                    }
                }
            }
        }
    }

    @Throws(Exception::class)
    private fun getAdbPublicKeyPayload(): ByteArray {
        val kp = keyPair ?: throw IllegalStateException("keyPair not initialized")
        val pubKey = kp.public as java.security.interfaces.RSAPublicKey
        val buf = ByteBuffer.allocate(524)
        buf.order(ByteOrder.LITTLE_ENDIAN)

        val modulus = pubKey.modulus.toByteArray()
        val modulusPadded = ByteArray(256)
        System.arraycopy(modulus, if (modulus.size > 256) 1 else 0, modulusPadded, 0, minOf(modulus.size, 256))
        buf.put(modulusPadded)
        buf.putInt(pubKey.publicExponent.toInt())

        val keyBytes = buf.array()
        val b64 = Base64.encodeToString(keyBytes, Base64.NO_WRAP)
        val keyStr = "$b64 unknown@adb\u0000"
        return keyStr.toByteArray(Charsets.UTF_8)
    }

    fun listFiles(path: String): List<FileItem> {
        synchronized(lock) {
            val result = mutableListOf<FileItem>()

            try {
                Log.d(TAG, "listFiles started, path: $path")

                // 检查连接状态
                if (!isConnected()) {
                    Log.e(TAG, "listFiles: ADB not connected")
                    return result
                }

                val inputPath = path.replace("//", "/").trimEnd('/').ifEmpty { "/" }

                // 使用 ls -la 列出目录内容
                val sh = """ls -la "$inputPath/" """
                val cmd = "shell:$sh\u0000"

                val sid = localId.getAndIncrement()
                Log.d(TAG, "listFiles: using stream ID=$sid, cmd=$cmd")

                sendPacket(CMD_OPEN, sid, 0, cmd.toByteArray(Charsets.UTF_8))

                var remoteId = 0
                val output = StringBuilder()

                while (true) {
                    val msg = readPacket()
                    Log.d(TAG, "listFiles: received packet cmd=${msg.command.toString(16)}, arg0=${msg.arg0}, arg1=${msg.arg1}")

                    when (msg.command) {
                        CMD_OKAY -> {
                            // 只处理针对当前 stream 的 OKAY
                            if (msg.arg1 == sid) {
                                remoteId = msg.arg0
                                Log.d(TAG, "listFiles: CMD_OKAY for stream $sid, remoteId=$remoteId")
                            } else {
                                Log.w(TAG, "listFiles: ignoring other stream OKAY (arg1=${msg.arg1})")
                            }
                        }
                        CMD_WRTE -> {
                            // 只处理针对当前 stream 的 WRTE
                            if (msg.arg1 == sid) {
                                val chunk = String(msg.payload, Charsets.UTF_8)
                                output.append(chunk)
                                Log.d(TAG, "listFiles: CMD_WRTE, length=${chunk.length}")
                                sendPacket(CMD_OKAY, sid, msg.arg0, null)
                            } else {
                                Log.d(TAG, "listFiles: ignoring other stream WRTE (arg1=${msg.arg1})")
                                sendPacket(CMD_OKAY, msg.arg1, msg.arg0, null)
                            }
                        }
                        CMD_CLSE -> {
                            // 只处理针对当前 stream 的 CLSE
                            if (msg.arg1 == sid) {
                                Log.d(TAG, "listFiles: CMD_CLSE for stream $sid, closing")
                                sendPacket(CMD_OKAY, sid, msg.arg0, null)
                                sendPacket(CMD_CLSE, sid, remoteId, null)
                                break
                            } else {
                                // 其他 stream 的关闭，回复 OKAY 并继续
                                Log.d(TAG, "listFiles: other stream closing (arg1=${msg.arg1}), continue waiting")
                                sendPacket(CMD_OKAY, msg.arg1, msg.arg0, null)
                            }
                        }
                        else -> {
                            Log.w(TAG, "listFiles: unknown command ${msg.command.toString(16)}")
                        }
                    }
                }

                val outputStr = output.toString()
                Log.d(TAG, "listFiles: output length=${outputStr.length}")
                Log.d(TAG, "listFiles output:\n$outputStr")

                // 解析 ls -la 输出
                // 标准格式: 权限 链接数 所有者 组 大小 月 日 时间 文件名
                // 示例: -rw-rw-rw- 1 root root 12345 Jun  7 10:30 filename.txt
                // 或者: drwxrwxrwx 2 root root 4096 Jun  7 10:30 dirname

                for (line in outputStr.split("\n")) {
                    val trimmed = line.trim()
                    if (trimmed.isEmpty() || trimmed.startsWith("total ")) continue

                    val parts = trimmed.split(Regex("\\s+"))
                    if (parts.size < 8) continue

                    val permissions = parts[0]

                    // 判断是否是目录：权限字段以 d 开头
                    val isDir = permissions.startsWith("d")

                    // 文件大小在第5个位置（索引4）
                    val size = if (parts.size > 4) parts[4].toLongOrNull() ?: 0L else 0L

                    // 解析时间：parts[5] 和 parts[6] 是日期和时间
                    val lastModified = try {
                        val dateStr = parts[5]
                        val timeStr = parts[6]
                        parseDateTime(dateStr, timeStr)
                    } catch (e: Exception) {
                        0L
                    }

                    // 文件名从第7个字段开始（索引7），可能包含空格
                    val name = parts.subList(7, parts.size).joinToString(" ")

                    // 跳过 . 和 ..
                    if (name == "." || name == "..") continue

                    val fullPath = if (inputPath == "/") "/$name" else "$inputPath/$name"

                    result.add(FileItem(
                        name = name,
                        path = fullPath,
                        isDirectory = isDir,
                        size = if (isDir) 0L else size,
                        lastModified = lastModified
                    ))
                }

                Log.d(TAG, "listFiles completed, found ${result.size} files")

            } catch (e: Exception) {
                Log.e(TAG, "listFiles error: ${e.message}", e)
            }

            return result.sortedWith(compareByDescending<FileItem> { it.isDirectory }.thenBy { it.name.lowercase() })
        }
    }

    fun downloadFile(remotePath: String, localPath: String): Boolean {
        synchronized(lock) {
            return try {
                Log.d(TAG, "Download file: $remotePath -> $localPath")
                Log.d(TAG, "Connection state: socket=${socket != null}, isConnected=${socket?.isConnected}, isClosed=${socket?.isClosed}")

                val sid = localId.getAndIncrement()
                val cmd = "sync:\u0000"
                Log.d(TAG, "Sending sync open command, sid=$sid")
                sendPacket(CMD_OPEN, sid, 0, cmd.toByteArray(Charsets.UTF_8))

                var remoteId = 0

                // 等待 sync 服务打开
                var retryCount = 0
                while (retryCount < 10) {
                    Log.d(TAG, "Waiting for sync response, retry=$retryCount")
                    val msg = readPacket()
                    Log.d(TAG, "Received response: cmd=${msg.command.toString(16)}, arg0=${msg.arg0}, arg1=${msg.arg1}, payloadLen=${msg.payloadLength}")
                    
                    when (msg.command) {
                        CMD_OKAY -> {
                            remoteId = msg.arg0
                            Log.d(TAG, "Sync service opened, remoteId=$remoteId")
                            break
                        }
                        CMD_CLSE -> {
                            Log.e(TAG, "Sync service open failed - CLSE received, falling back to shell")
                            // 检查是否有错误信息
                            if (msg.payload.isNotEmpty()) {
                                val error = String(msg.payload, Charsets.UTF_8)
                                Log.e(TAG, "Error: $error")
                            }
                            return downloadFileShell(remotePath, localPath)
                        }
                        else -> {
                            Log.w(TAG, "Received unknown response: ${msg.command.toString(16)}")
                            retryCount++
                        }
                    }
                }

                if (remoteId == 0) {
                    Log.e(TAG, "Sync service open timed out, falling back to shell")
                    return downloadFileShell(remotePath, localPath)
                }

                // RECV 命令格式: "RECV" + 4字节路径长度(little endian) + 路径
                val pathBytes = remotePath.toByteArray(Charsets.UTF_8)
                val recvCmd = ByteArray(8 + pathBytes.size)
                System.arraycopy("RECV".toByteArray(Charsets.UTF_8), 0, recvCmd, 0, 4)
                recvCmd[4] = (pathBytes.size and 0xFF).toByte()
                recvCmd[5] = ((pathBytes.size shr 8) and 0xFF).toByte()
                recvCmd[6] = ((pathBytes.size shr 16) and 0xFF).toByte()
                recvCmd[7] = ((pathBytes.size shr 24) and 0xFF).toByte()
                System.arraycopy(pathBytes, 0, recvCmd, 8, pathBytes.size)
                sendPacket(CMD_WRTE, sid, remoteId, recvCmd)

                // 等待 OKAY
                var waitForOkay = true
                while (waitForOkay) {
                    val msg = readPacket()
                    when (msg.command) {
                        CMD_OKAY -> waitForOkay = false
                        CMD_CLSE -> {
                            Log.e(TAG, "RECV command failed")
                            throw Exception("RECV command failed")
                        }
                    }
                }

                var totalReceived = 0L
                var downloadSuccess = false
                try {
                    FileOutputStream(localPath).use { fos ->
                        while (true) {
                            val msg = readPacket()
                            when (msg.command) {
                                CMD_OKAY -> {}
                                CMD_WRTE -> {
                                    val payload = msg.payload
                                    if (payload.size >= 8) {
                                        val cmdStr = String(payload.copyOfRange(0, 4), Charsets.UTF_8)
                                        when (cmdStr) {
                                            "FAIL" -> {
                                                val errLen = ((payload[4].toInt() and 0xFF) or
                                                              ((payload[5].toInt() and 0xFF) shl 8) or
                                                              ((payload[6].toInt() and 0xFF) shl 16) or
                                                              ((payload[7].toInt() and 0xFF) shl 24))
                                                val errMsg = if (payload.size >= 8 + errLen) {
                                                    String(payload.copyOfRange(8, 8 + errLen), Charsets.UTF_8)
                                                } else {
                                                    "Unknown error"
                                                }
                                                Log.e(TAG, "Download failed: $errMsg")
                                                throw Exception("Download failed: $errMsg")
                                            }
                                            "DATA" -> {
                                                val dataLen = ((payload[4].toInt() and 0xFF) or
                                                               ((payload[5].toInt() and 0xFF) shl 8) or
                                                               ((payload[6].toInt() and 0xFF) shl 16) or
                                                               ((payload[7].toInt() and 0xFF) shl 24))
                                                if (payload.size >= 8 + dataLen) {
                                                    fos.write(payload, 8, dataLen)
                                                    totalReceived += dataLen
                                                }
                                                if (totalReceived % (1024 * 1024) == 0L) {
                                                    Log.d(TAG, "Download progress: $totalReceived bytes")
                                                }
                                            }
                                            "DONE" -> {
                                                Log.d(TAG, "Download complete: $totalReceived bytes")
                                                sendPacket(CMD_OKAY, sid, msg.arg0, null)
                                                sendPacket(CMD_CLSE, sid, remoteId, null)
                                                // 消费服务端的 CLSE 响应（仅消费当前 stream 的）
                                                try {
                                                    while (true) {
                                                        val clseMsg = readPacket()
                                                        if (clseMsg.command == CMD_CLSE && clseMsg.arg1 == sid) break
                                                    }
                                                } catch (_: Exception) {}
                                                downloadSuccess = true
                                                break
                                            }
                                        }
                                    }
                                    sendPacket(CMD_OKAY, sid, msg.arg0, null)
                                }
                                CMD_CLSE -> {
                                    sendPacket(CMD_CLSE, sid, remoteId, null)
                                    break
                                }
                            }
                        }
                    }
                    downloadSuccess
                } catch (e: Exception) {
                    Log.e(TAG, "Download failed: ${e.message}", e)
                    // 关闭 sync 流，防止泄漏
                    try { sendPacket(CMD_CLSE, sid, remoteId, ByteArray(0)) } catch (_: Exception) {}
                    false
                }
            } catch (e: Exception) {
                Log.e(TAG, "File operation failed: ${e.message}", e)
                false
            }
        }
    }

    private fun downloadFileShell(remotePath: String, localPath: String): Boolean {
        return try {
            Log.d(TAG, "Downloading via shell: $remotePath -> $localPath")

            // 使用 base64 编码下载文件
            val cmd = "shell:cat \"$remotePath\" | base64\u0000"
            val base64Output = executeShellCommandInternal(cmd)

            if (base64Output.isEmpty()) {
                Log.e(TAG, "Shell download failed: empty output")
                return false
            }

            // 解码 base64 并写入文件
            val decoded = android.util.Base64.decode(base64Output.trim(), android.util.Base64.DEFAULT)
            File(localPath).writeBytes(decoded)

            Log.d(TAG, "Shell download success: $localPath, size: ${decoded.size}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Shell download failed: ${e.message}", e)
            false
        }
    }

    fun deleteFile(path: String): Boolean {
        synchronized(lock) {
            return try {
                val sid = localId.getAndIncrement()
                val cmd = "shell:rm -rf ${shellEscape(path)}\u0000"
                sendPacket(CMD_OPEN, sid, 0, cmd.toByteArray(Charsets.UTF_8))

                var remoteId = 0
                while (true) {
                    val msg = readPacket()
                    when (msg.command) {
                        CMD_OKAY -> {
                            if (msg.arg1 == sid) {
                                remoteId = msg.arg0
                            }
                        }
                        CMD_WRTE -> {
                            if (msg.arg1 == sid) {
                                sendPacket(CMD_OKAY, sid, msg.arg0, null)
                            } else {
                                sendPacket(CMD_OKAY, msg.arg1, msg.arg0, null)
                            }
                        }
                        CMD_CLSE -> {
                            if (msg.arg1 == sid) {
                                sendPacket(CMD_OKAY, sid, msg.arg0, null)
                                sendPacket(CMD_CLSE, sid, remoteId, null)
                                break
                            } else {
                                sendPacket(CMD_OKAY, msg.arg1, msg.arg0, null)
                            }
                        }
                    }
                }
                true
            } catch (e: Exception) {
                Log.e(TAG, "Delete failed: ${e.message}", e)
                false
            }
        }
    }

    fun copyFile(sourcePath: String, destPath: String): Boolean {
        synchronized(lock) {
            return try {
                val sid = localId.getAndIncrement()
                val cmd = "shell:cp -rf \"$sourcePath\" \"$destPath\"\u0000"
                sendPacket(CMD_OPEN, sid, 0, cmd.toByteArray(Charsets.UTF_8))

                var remoteId = 0
                while (true) {
                    val msg = readPacket()
                    when (msg.command) {
                    CMD_OKAY -> {
                        if (msg.arg1 == sid) {
                            remoteId = msg.arg0
                        }
                    }
                    CMD_WRTE -> {
                        if (msg.arg1 == sid) {
                            sendPacket(CMD_OKAY, sid, msg.arg0, null)
                        } else {
                            sendPacket(CMD_OKAY, msg.arg1, msg.arg0, null)
                        }
                    }
                    CMD_CLSE -> {
                        if (msg.arg1 == sid) {
                            sendPacket(CMD_OKAY, sid, msg.arg0, null)
                            sendPacket(CMD_CLSE, sid, remoteId, null)
                            break
                        } else {
                            sendPacket(CMD_OKAY, msg.arg1, msg.arg0, null)
                        }
                    }
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Copy failed: ${e.message}", e)
            false
        }
        }
    }

    fun renameFile(oldPath: String, newPath: String): Boolean {
        synchronized(lock) {
            return try {
                val sid = localId.getAndIncrement()
                val cmd = "shell:mv ${shellEscape(oldPath)} ${shellEscape(newPath)}\u0000"
                sendPacket(CMD_OPEN, sid, 0, cmd.toByteArray(Charsets.UTF_8))

                var remoteId = 0
                while (true) {
                    val msg = readPacket()
                    when (msg.command) {
                    CMD_OKAY -> {
                        if (msg.arg1 == sid) {
                            remoteId = msg.arg0
                        }
                    }
                    CMD_WRTE -> {
                        if (msg.arg1 == sid) {
                            sendPacket(CMD_OKAY, sid, msg.arg0, null)
                        } else {
                            sendPacket(CMD_OKAY, msg.arg1, msg.arg0, null)
                        }
                    }
                    CMD_CLSE -> {
                        if (msg.arg1 == sid) {
                            sendPacket(CMD_OKAY, sid, msg.arg0, null)
                            sendPacket(CMD_CLSE, sid, remoteId, null)
                            break
                        } else {
                            sendPacket(CMD_OKAY, msg.arg1, msg.arg0, null)
                        }
                    }
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Rename failed: ${e.message}", e)
            false
        }
        }
    }

    fun createFolder(path: String): Boolean {
        synchronized(lock) {
            return try {
                val sid = localId.getAndIncrement()
                val cmd = "shell:mkdir -p \"$path\"\u0000"
                sendPacket(CMD_OPEN, sid, 0, cmd.toByteArray(Charsets.UTF_8))

                var remoteId = 0
                while (true) {
                    val msg = readPacket()
                    when (msg.command) {
                        CMD_OKAY -> {
                            if (msg.arg1 == sid) {
                                remoteId = msg.arg0
                            }
                        }
                        CMD_WRTE -> {
                            if (msg.arg1 == sid) {
                                sendPacket(CMD_OKAY, sid, msg.arg0, null)
                            } else {
                                sendPacket(CMD_OKAY, msg.arg1, msg.arg0, null)
                            }
                        }
                        CMD_CLSE -> {
                            if (msg.arg1 == sid) {
                                sendPacket(CMD_OKAY, sid, msg.arg0, null)
                                sendPacket(CMD_CLSE, sid, remoteId, null)
                                break
                            } else {
                                sendPacket(CMD_OKAY, msg.arg1, msg.arg0, null)
                            }
                        }
                    }
                }
                true
            } catch (e: Exception) {
                Log.e(TAG, "Create folder failed: ${e.message}", e)
                false
            }
        }
    }
    
    /** 获取存储空间信息 */
    fun getStorageInfo(path: String): StorageInfo? {
        synchronized(lock) {
            return try {
                // 直接使用 df 获取 /storage/emulated 存储信息
                return getStorageInfoByDeviceInternal("/storage/emulated")
            } catch (e: Exception) {
                Log.e(TAG, "Get storage info failed: ${e.message}", e)
                return null
            }
        }
    }

    private fun getStorageInfoByDeviceInternal(device: String): StorageInfo? {
        return try {
            val sid = localId.getAndIncrement()
            // 使用 df 获取存储信息
            val cmd = "shell:df $device\u0000"
            sendPacket(CMD_OPEN, sid, 0, cmd.toByteArray(Charsets.UTF_8))

            var remoteId = 0
            val output = StringBuilder()

            Log.d(TAG, "getStorageInfoByDeviceInternal: cmd=$cmd")
            
            while (true) {
                val msg = readPacket()
                when (msg.command) {
                    CMD_OKAY -> {
                        if (msg.arg1 == sid) {
                            remoteId = msg.arg0
                            Log.d(TAG, "getStorageInfoByDeviceInternal: CMD_OKAY, remoteId=$remoteId")
                        }
                    }
                    CMD_WRTE -> {
                        if (msg.arg1 == sid) {
                            val content = String(msg.payload, Charsets.UTF_8)
                            output.append(content)
                            Log.d(TAG, "getStorageInfoByDeviceInternal: CMD_WRTE, content length=${content.length}")
                            sendPacket(CMD_OKAY, sid, msg.arg0, null)
                        } else {
                            sendPacket(CMD_OKAY, msg.arg1, msg.arg0, null)
                        }
                    }
                    CMD_CLSE -> {
                        if (msg.arg1 == sid) {
                            Log.d(TAG, "getStorageInfoByDeviceInternal: CMD_CLSE")
                            sendPacket(CMD_OKAY, sid, msg.arg0, null)
                            sendPacket(CMD_CLSE, sid, remoteId, null)
                            break
                        } else {
                            sendPacket(CMD_OKAY, msg.arg1, msg.arg0, null)
                        }
                    }
                }
            }

            Log.d(TAG, "df full output: [$output]")
            
            // 解析 df 输出
            val lines = output.toString().trim().split("\n")
            Log.d(TAG, "df output lines: ${lines.size}")
            for (line in lines) {
                Log.d(TAG, "df line: $line")
                if (line.contains(device) || line.contains("/sdcard")) {
                    val parts = line.trim().split(Regex("\\s+"))
                    Log.d(TAG, "df line parse: parts.size=${parts.size}, parts=${parts.joinToString(",")}")
                    if (parts.size >= 5) {
                        try {
                            // 解析带单位的值 (如 15G, 2.3G, 100M, 50K)
                            val total = parseSize(parts[1])
                            val used = parseSize(parts[2])
                            val free = parseSize(parts[3])
                            Log.d(TAG, "Parse result: total=$total, used=$used, free=$free")
                            if (total > 0) {
                                return StorageInfo(total, used, free)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Parse storage info failed: ${e.message}")
                        }
                    }
                }
            }
            Log.d(TAG, "Could not parse storage info")
            return null
        } catch (e: Exception) {
            Log.e(TAG, "Get storage info from device failed: ${e.message}", e)
            return null
        }
    }
    
    /**
     * 解析带单位的文件大小 (如 15G, 2.3G, 100M, 50K)
     * df 命令输出的是 1K-blocks，所以纯数字直接返回（已经是 KB）
     */
    private fun parseSize(sizeStr: String): Long {
        val trimmed = sizeStr.trim()
        if (trimmed.isEmpty()) return 0L
        
        // 尝试直接解析数字（df 输出的是 1K-blocks）
        trimmed.toLongOrNull()?.let { return it * 1024 }
        
        // 解析带单位的值
        val regex = Regex("([0-9.]+)([KMGTP]?)", RegexOption.IGNORE_CASE)
        val match = regex.find(trimmed)
        if (match != null) {
            val value = match.groupValues[1].toDoubleOrNull() ?: return 0L
            val unit = match.groupValues[2].uppercase()
            
            val multiplier = when (unit) {
                "K" -> 1024L
                "M" -> 1024L * 1024
                "G" -> 1024L * 1024 * 1024
                "T" -> 1024L * 1024 * 1024 * 1024
                "P" -> 1024L * 1024 * 1024 * 1024 * 1024
                else -> 1024L // 默认按 KB 处理
            }
            
            return (value * multiplier).toLong()
        }
        
        return 0L
    }
    
    data class StorageInfo(
        val totalBytes: Long,
        val usedBytes: Long,
        val freeBytes: Long
    ) {
        val usedPercent: Int get() = if (totalBytes > 0) (usedBytes * 100 / totalBytes).toInt() else 0
        fun formatSize(bytes: Long): String {
            if (bytes < 1024) return "$bytes B"
            if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0)
            if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024))
            return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024))
        }
    }

    fun uploadFile(localPath: String, remotePath: String): Boolean {
        synchronized(lock) {
            return try {
                val file = File(localPath)
                if (!file.exists()) {
                    Log.e(TAG, "Local file not found: $localPath")
                    return false
                }

                Log.d(TAG, "Upload file: $localPath -> $remotePath, file size: ${file.length()}")

                // 先确保目录存在
                val remoteDir = remotePath.substringBeforeLast('/', "/")
                if (remoteDir.isNotEmpty() && remoteDir != "/") {
                    val mkdirCmd = "shell:mkdir -p ${shellEscape(remoteDir)}\u0000"
                    executeShellCommandInternal(mkdirCmd)
                    Log.d(TAG, "Creating directory: $remoteDir")
                }

                // 使用 sync 服务上传
                val sid = localId.getAndIncrement()
                val cmd = "sync:\u0000"
                sendPacket(CMD_OPEN, sid, 0, cmd.toByteArray(Charsets.UTF_8))

            var remoteId = 0

            // 等待 sync 服务打开
            while (true) {
                val msg = readPacket()
                when (msg.command) {
                    CMD_OKAY -> {
                        remoteId = msg.arg0
                        Log.d(TAG, "Sync service opened, remoteId=$remoteId")
                        break
                    }
                    CMD_CLSE -> {
                        Log.e(TAG, "Sync service open failed, falling back to shell")
                        return uploadFileShell(file, remotePath)
                    }
                }
            }

            // SEND 命令: "SEND" + 4字节长度 + 路径 + ",mode"
            // mode: 0644 = 33188 (普通文件权限)
            val sendPath = "$remotePath,33188"
            val sendPathBytes = sendPath.toByteArray(Charsets.UTF_8)
            val sendCmd = ByteArray(8 + sendPathBytes.size)
            System.arraycopy("SEND".toByteArray(Charsets.UTF_8), 0, sendCmd, 0, 4)
            sendCmd[4] = (sendPathBytes.size and 0xFF).toByte()
            sendCmd[5] = ((sendPathBytes.size shr 8) and 0xFF).toByte()
            sendCmd[6] = ((sendPathBytes.size shr 16) and 0xFF).toByte()
            sendCmd[7] = ((sendPathBytes.size shr 24) and 0xFF).toByte()
            System.arraycopy(sendPathBytes, 0, sendCmd, 8, sendPathBytes.size)
            
            sendPacket(CMD_WRTE, sid, remoteId, sendCmd)

            // 等待 OKAY
            var waitForOkay = true
            while (waitForOkay) {
                val msg = readPacket()
                when (msg.command) {
                    CMD_OKAY -> waitForOkay = false
                    CMD_CLSE -> {
                        Log.e(TAG, "SEND command failed")
                        return uploadFileShell(file, remotePath)
                    }
                }
            }

            // 分块发送数据，每块最大 64KB
            val chunkSize = 64 * 1024
            var totalSent = 0L
            
            file.inputStream().use { fis ->
                val buffer = ByteArray(chunkSize)
                var bytesRead: Int
                while (fis.read(buffer).also { bytesRead = it } > 0) {
                    // DATA 命令: "DATA" + 4字节数据长度 + 数据
                    val dataCmd = ByteArray(8 + bytesRead)
                    System.arraycopy("DATA".toByteArray(Charsets.UTF_8), 0, dataCmd, 0, 4)
                    dataCmd[4] = (bytesRead and 0xFF).toByte()
                    dataCmd[5] = ((bytesRead shr 8) and 0xFF).toByte()
                    dataCmd[6] = ((bytesRead shr 16) and 0xFF).toByte()
                    dataCmd[7] = ((bytesRead shr 24) and 0xFF).toByte()
                    System.arraycopy(buffer, 0, dataCmd, 8, bytesRead)
                    
                    sendPacket(CMD_WRTE, sid, remoteId, dataCmd)
                    totalSent += bytesRead
                    
                    // 等待 OKAY
                    var waitData = true
                    while (waitData) {
                        val msg = readPacket()
                        when (msg.command) {
                            CMD_OKAY -> waitData = false
                            CMD_CLSE -> {
                                Log.e(TAG, "DATA command failed")
                                return false
                            }
                        }
                    }
                    
                    if (totalSent % (1024 * 1024) == 0L) {
                        Log.d(TAG, "Upload progress: $totalSent / ${file.length()}")
                    }
                }
            }

            // DONE 命令: "DONE" + 4字节时间戳
            val doneCmd = ByteArray(8)
            System.arraycopy("DONE".toByteArray(Charsets.UTF_8), 0, doneCmd, 0, 4)
            val timestamp = (System.currentTimeMillis() / 1000).toInt()
            doneCmd[4] = (timestamp and 0xFF).toByte()
            doneCmd[5] = ((timestamp shr 8) and 0xFF).toByte()
            doneCmd[6] = ((timestamp shr 16) and 0xFF).toByte()
            doneCmd[7] = ((timestamp shr 24) and 0xFF).toByte()
            
            sendPacket(CMD_WRTE, sid, remoteId, doneCmd)

            // 等待响应（超时由 socket.soTimeout 保护）
            var success = false
            while (true) {
                val msg = readPacket()
                when (msg.command) {
                    CMD_OKAY -> { }
                    CMD_WRTE -> {
                        val payload = msg.payload
                        if (payload.size >= 4) {
                            val cmdStr = String(payload.copyOfRange(0, 4), Charsets.UTF_8)
                            if (cmdStr == "OKAY") {
                                success = true
                                Log.d(TAG, "Upload success")
                            } else if (cmdStr == "FAIL") {
                                Log.e(TAG, "Upload failed: FAIL response")
                            }
                        }
                        sendPacket(CMD_OKAY, sid, msg.arg0, null)
                    }
                    CMD_CLSE -> {
                        // 只处理当前 stream 的 CLSE，其他继续等待
                        if (msg.arg1 == sid) {
                            sendPacket(CMD_OKAY, sid, remoteId, null)
                            break
                        }
                    }
                }
            }

            success
        } catch (e: Exception) {
            Log.e(TAG, "Upload failed: ${e.message}", e)
            false
        }
        }
    }
    
    private fun uploadFileShell(file: File, remotePath: String): Boolean {
        Log.d(TAG, "Using shell to upload: ${file.name}")
        
        // shell 路径转义：用单引号包裹，路径内含单引号时用 '\'' 模式
        fun shellEscape(s: String): String = "'${s.replace("'", "'\\''")}'"
        val escapedRemotePath = shellEscape(remotePath)
        
        // 使用分块追加方式上传
        val chunkSize = 64 * 1024
        var offset = 0L
        var isFirst = true
        
        file.inputStream().use { fis ->
            val buffer = ByteArray(chunkSize)
            var bytesRead: Int
            while (fis.read(buffer).also { bytesRead = it } > 0) {
                val chunk = buffer.copyOf(bytesRead)
                val base64 = android.util.Base64.encodeToString(chunk, android.util.Base64.NO_WRAP)
                
                val redirect = if (isFirst) ">" else ">>"
                // base64 在单引号内，路径也在单引号内，无 shell 注入风险
                val cmd = "shell:echo '$base64' | base64 -d $redirect $escapedRemotePath\u0000"
                
                executeShellCommandInternal(cmd)
                
                offset += bytesRead
                isFirst = false
            }
        }

        // 验证文件大小
        val checkCmd = "shell:stat -c %s $escapedRemotePath 2>/dev/null || echo 0\u0000"
        val checkResult = executeShellCommandInternal(checkCmd).trim()
        val remoteSize = checkResult.toLongOrNull() ?: 0
        val success = remoteSize == file.length()
        
        Log.d(TAG, "Shell upload ${if (success) "success" else "failed"}, local size: ${file.length()}, remote size: $remoteSize")
        return success
    }
    
    private fun executeShellCommandInternal(cmd: String): String {
        val sid = localId.getAndIncrement()
        sendPacket(CMD_OPEN, sid, 0, cmd.toByteArray(Charsets.UTF_8))

        var remoteId = 0
        val output = StringBuilder()

        while (true) {
            val msg = readPacket()
            when (msg.command) {
                CMD_OKAY -> {
                    if (msg.arg1 == sid) {
                        remoteId = msg.arg0
                    }
                }
                CMD_WRTE -> {
                    if (msg.arg1 == sid) {
                        output.append(String(msg.payload, Charsets.UTF_8))
                        sendPacket(CMD_OKAY, sid, msg.arg0, null)
                    } else {
                        sendPacket(CMD_OKAY, msg.arg1, msg.arg0, null)
                    }
                }
                CMD_CLSE -> {
                    if (msg.arg1 == sid) {
                        sendPacket(CMD_OKAY, sid, msg.arg0, null)
                        sendPacket(CMD_CLSE, sid, remoteId, null)
                        // 消费服务端的 CLSE 响应（仅消费当前 stream 的）
                        try {
                            while (true) {
                                val clseMsg = readPacket()
                                if (clseMsg.command == CMD_CLSE && clseMsg.arg1 == sid) break
                            }
                        } catch (_: Exception) {}
                        break
                    } else {
                        sendPacket(CMD_OKAY, msg.arg1, msg.arg0, null)
                    }
                }
            }
        }
        
        return output.toString()
    }
    
    fun executeShellCommand(cmd: String): String {
        synchronized(lock) {
            return executeShellCommandInternal("shell:$cmd\u0000")
        }
    }

    fun isAppInstalled(packageName: String): Boolean {
        return try {
            val sid = localId.getAndIncrement()
            val cmd = "shell:pm list packages $packageName\u0000"
            sendPacket(CMD_OPEN, sid, 0, cmd.toByteArray(Charsets.UTF_8))

            var remoteId = 0
            var installed = false
            while (true) {
                val msg = readPacket()
                when (msg.command) {
                    CMD_OKAY -> {
                        remoteId = msg.arg0
                    }
                    CMD_WRTE -> {
                        val response = String(msg.payload ?: ByteArray(0), Charsets.UTF_8)
                        if (response.contains("package:$packageName")) {
                            installed = true
                        }
                        sendPacket(CMD_OKAY, sid, msg.arg0, null)
                    }
                    CMD_CLSE -> {
                        sendPacket(CMD_OKAY, sid, remoteId, null)
                        break
                    }
                }
            }
            installed
        } catch (e: Exception) {
            Log.e(TAG, "Check app install status failed: ${e.message}", e)
            false
        }
    }

    fun launchApp(packageName: String, activityClass: String = ""): Boolean {
        return try {
            val sid = localId.getAndIncrement()
            val activity = if (activityClass.isNotEmpty()) activityClass else ".MainActivity"
            // shell 转义：单引号包裹防注入
            val escapedPkg = shellEscape(packageName)
            val escapedActivity = shellEscape(activity)
            val cmd = "shell:am start -n $escapedPkg/$escapedActivity\u0000"
            sendPacket(CMD_OPEN, sid, 0, cmd.toByteArray(Charsets.UTF_8))

            var remoteId = 0
            while (true) {
                val msg = readPacket()
                when (msg.command) {
                    CMD_OKAY -> {
                        remoteId = msg.arg0
                    }
                    CMD_WRTE -> {
                        sendPacket(CMD_OKAY, sid, msg.arg0, null)
                    }
                    CMD_CLSE -> {
                        sendPacket(CMD_OKAY, sid, remoteId, null)
                        break
                    }
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Launch app failed: ${e.message}", e)
            false
        }
    }

    @Throws(Exception::class)
    private fun sendPacket(command: Int, arg0: Int, arg1: Int, payload: ByteArray?) {
        val payloadLen = payload?.size ?: 0
        val totalLen = HEADER_LENGTH + payloadLen
        val buf = ByteBuffer.allocate(totalLen)
        buf.order(ByteOrder.LITTLE_ENDIAN)

        buf.putInt(command)
        buf.putInt(arg0)
        buf.putInt(arg1)
        buf.putInt(payloadLen)
        buf.putInt(checksum(payload))
        buf.putInt(command.inv())

        if (payload != null) {
            buf.put(payload)
        }

        outputStream?.write(buf.array())
        outputStream?.flush()
    }

    @Throws(Exception::class)
    private fun readPacket(): AdbMessage {
        val headerBuf = ByteArray(HEADER_LENGTH)
        readFully(headerBuf)

        val buf = ByteBuffer.wrap(headerBuf)
        buf.order(ByteOrder.LITTLE_ENDIAN)

        val msg = AdbMessage()
        msg.command = buf.getInt()
        msg.arg0 = buf.getInt()
        msg.arg1 = buf.getInt()
        msg.payloadLength = buf.getInt()
        msg.checksum = buf.getInt()
        msg.magic = buf.getInt()

        if (msg.payloadLength > 0) {
            msg.payload = ByteArray(msg.payloadLength)
            readFully(msg.payload)
        }

        return msg
    }

    @Throws(Exception::class)
    private fun readFully(buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val read = inputStream?.read(buffer, offset, buffer.size - offset) ?: -1
            if (read < 0) throw java.io.IOException("Stream closed")
            offset += read
        }
    }

    /** Shell 路径转义：用单引号包裹，路径内含单引号时用 '\'' 模式 */
    private fun shellEscape(s: String): String = "'${s.replace("'", "'\\''")}'"

    private fun checksum(data: ByteArray?): Int {
        if (data == null) return 0
        var sum = 0
        for (b in data) sum += b.toInt() and 0xFF
        return sum
    }

    fun isConnected(): Boolean {
        val connected = socket?.isConnected == true && socket?.isClosed == false
        Log.d(TAG, "isConnected: $connected (socket=${socket != null}, isConnected=${socket?.isConnected}, isClosed=${socket?.isClosed})")
        return connected
    }

    fun disconnect() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        inputStream = null
        outputStream = null
    }
    
    /** 读取文件内容（用于预览） */
    fun readFileContent(path: String, maxSize: Int = 1024 * 1024): String? {
        synchronized(lock) {
            return try {
                readFileContentInternal(path, maxSize)
            } catch (e: Exception) {
                Log.e(TAG, "Read file content failed: ${e.message}", e)
                null
            }
        }
    }
    
    private fun readFileContentInternal(path: String, maxSize: Int): String {
        val sid = localId.getAndIncrement()
        val cmd = "sync:\u0000"
        sendPacket(CMD_OPEN, sid, 0, cmd.toByteArray(Charsets.UTF_8))

        var remoteId = 0

        while (true) {
            val msg = readPacket()
            when (msg.command) {
                CMD_OKAY -> {
                    remoteId = msg.arg0
                    break
                }
                CMD_CLSE -> {
                    throw Exception("Failed to open sync service")
                }
                else -> {
                    throw Exception("Unknown response: ${msg.command}")
                }
            }
        }

        // 发送 RECV 命令
        val pathBytes = path.toByteArray(Charsets.UTF_8)
        val recvCmd = ByteArray(8 + pathBytes.size)
        System.arraycopy("RECV".toByteArray(Charsets.UTF_8), 0, recvCmd, 0, 4)
        recvCmd[4] = (pathBytes.size and 0xFF).toByte()
        recvCmd[5] = ((pathBytes.size shr 8) and 0xFF).toByte()
        recvCmd[6] = ((pathBytes.size shr 16) and 0xFF).toByte()
        recvCmd[7] = ((pathBytes.size shr 24) and 0xFF).toByte()
        System.arraycopy(pathBytes, 0, recvCmd, 8, pathBytes.size)
        sendPacket(CMD_WRTE, sid, remoteId, recvCmd)

        // 等待 RECV 被确认
        var waitForOkay = true
        while (waitForOkay) {
            val msg = readPacket()
            when (msg.command) {
                CMD_OKAY -> waitForOkay = false
                CMD_CLSE -> throw Exception("RECV command rejected")
            }
        }

        val output = ByteArrayOutputStream()
        var totalRead = 0

        while (true) {
            val msg = readPacket()
            when (msg.command) {
                CMD_OKAY -> {
                    // 继续等待数据
                }
                CMD_WRTE -> {
                    val payload = msg.payload
                    if (payload.size >= 8) {
                        val cmdStr = String(payload, 0, 4, Charsets.UTF_8)
                        when (cmdStr) {
                            "DATA" -> {
                                val size = ((payload[4].toInt() and 0xFF)) or
                                          ((payload[5].toInt() and 0xFF) shl 8) or
                                          ((payload[6].toInt() and 0xFF) shl 16) or
                                          ((payload[7].toInt() and 0xFF) shl 24)
                                if (payload.size >= 8 + size) {
                                    output.write(payload, 8, size)
                                    totalRead += size
                                }
                            }
                            "DONE" -> {
                                sendPacket(CMD_OKAY, sid, msg.arg0, null)
                                sendPacket(CMD_CLSE, sid, remoteId, null)
                                // 消费服务端的 CLSE 响应包（仅消费当前 stream 的）
                                try {
                                    while (true) {
                                        val clseMsg = readPacket()
                                        if (clseMsg.command == CMD_CLSE && clseMsg.arg1 == sid) break
                                    }
                                } catch (_: Exception) {}
                                return output.toString(Charsets.UTF_8.name())
                            }
                            "FAIL" -> {
                                val errorSize = ((payload[4].toInt() and 0xFF)) or
                                              ((payload[5].toInt() and 0xFF) shl 8) or
                                              ((payload[6].toInt() and 0xFF) shl 16) or
                                              ((payload[7].toInt() and 0xFF) shl 24)
                                val error = if (payload.size >= 8 + errorSize)
                                    String(payload, 8, errorSize, Charsets.UTF_8)
                                else "Unknown error"
                                Log.e(TAG, "Read file failed: $error")
                                throw Exception("Read file failed: $error")
                            }
                        }
                    }
                    sendPacket(CMD_OKAY, sid, msg.arg0, null)
                }
                CMD_CLSE -> {
                    sendPacket(CMD_CLSE, sid, remoteId, null)
                    return output.toString(Charsets.UTF_8.name())
                }
            }

            if (totalRead >= maxSize) {
                sendPacket(CMD_CLSE, sid, remoteId, null)
                return output.toString(Charsets.UTF_8.name())
            }
        }
    }

    private data class AdbMessage(
        var command: Int = 0,
        var arg0: Int = 0,
        var arg1: Int = 0,
        var payloadLength: Int = 0,
        var checksum: Int = 0,
        var magic: Int = 0,
        var payload: ByteArray = ByteArray(0),
    )
}

/**
 * 文件项数据类
 */
data class FileItem(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long
)
