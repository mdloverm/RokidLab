package com.rokidlab.phone.adb

import com.rokidlab.phone.util.AppConfig
import com.rokidlab.phone.util.LogCollector
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
import java.security.KeyPair
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.locks.ReentrantLock

class AdbFileManagerClient(
    /**
     * 应用 Context。**生产路径必传**；仅 JVM 单测驱动纯流协议（sync 帧编解码 / `downloadFile`
     * 分支）时允许为 null —— 这条路径完全不触 Context。使用处一律经 [requireContext] 取值。
     */
    private val context: Context?,
    private val ipAddress: String,
    private val port: Int = 5555,
) {
    /**
     * 仅供单测：注入内存流直接驱动 ADB 流协议（不建链、不握手，`socket` 保持 null）。
     * 用于把 `drainStalePackets`（历史「陈旧 CLSE 被误读成本次响应」）与 `downloadFile`
     * 的 FAIL 分支写成回归测试。
     */
    internal fun attachStreamsForTest(input: InputStream, output: OutputStream) {
        inputStream = input
        outputStream = output
    }

    private fun requireContext(): Context =
        context ?: error("AdbFileManagerClient 该路径需要 Context（单测构造为 null 时不可调用）")

    private var socket: Socket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    private var keyPair: KeyPair? = null
    private var localId = AtomicInteger(1)
    private val lock = ReentrantLock()

    /** 跟踪 ADB 会话是否真正存活（超过 TCP 层面） */
    @Volatile
    private var adbSessionAlive = false

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
        // 取 Context 放在 try 之外：单测构造（context=null）误调 connect 时立刻显式失败，
        // 而不是被 catch 吞成一句「连接失败」。
        val ctx = requireContext()
        return try {
            onStatus(ctx.getString(R.string.file_manager_connecting, ipAddress, port))
            Log.i(TAG, "Connecting to $ipAddress:$port")

            socket = Socket()
            socket?.tcpNoDelay = true
            // 握手期使用独立超时：蓝牙隧道场景下 CNXN 需等 RFCOMM 建连（兜底 8s）才被转发，
            // 命令期 3s 超时会在此阶段误报 Read timed out（见 AdbShellClient.connect 注释）。
            socket?.soTimeout = AppConfig.ADB_HANDSHAKE_TIMEOUT_MS
            socket?.keepAlive = true
            socket?.connect(java.net.InetSocketAddress(ipAddress, port), AppConfig.ADB_CONNECT_TIMEOUT_MS)
            inputStream = socket?.getInputStream()
            outputStream = socket?.getOutputStream()
            Log.i(TAG, "TCP connection established")
            onStatus(ctx.getString(R.string.file_manager_auth))

            keyPair = AdbKeyManager.getOrCreateKeyPair(ctx.filesDir.absolutePath)
            localId.set(1)
            doHandshake()
            // 握手完成：恢复命令期读超时
            socket?.soTimeout = AppConfig.ADB_SOCKET_TIMEOUT_MS
            adbSessionAlive = true
            Log.i(TAG, "ADB connection successful")
            onStatus(ctx.getString(R.string.file_manager_connected))
            true
        } catch (e: Exception) {
            Log.e(TAG, "Connection failed: ${e.message}", e)
            LogCollector.e(TAG, "ADB connection failed to $ipAddress:$port: ${e.message}", e)
            onStatus(ctx.getString(R.string.file_manager_connection_failed, e.message))
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
                            val sig = AdbKeyManager.getSignature()
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

    private fun drainStalePackets() {
        try {
            socket?.soTimeout = 100
            while (true) {
                val msg = readPacket()
                if (msg.command == CMD_CLSE) {
                    sendPacket(CMD_CLSE, msg.arg1, msg.arg0, null)
                }
            }
        } catch (_: Exception) {} // catch-ok: 排空陈旧包，读超时即结束（正常退出），无信息量
        socket?.soTimeout = AppConfig.ADB_SOCKET_TIMEOUT_MS
    }

    fun listFiles(path: String): List<FileItem> {
        lock.lock()
        val result = try {
            val items = mutableListOf<FileItem>()

            try {
                drainStalePackets()
                Log.d(TAG, "listFiles started, path: $path")

                // 检查连接状态
                if (!isConnected()) {
                    Log.e(TAG, "listFiles: ADB not connected")
                    return items
                }

                val inputPath = path.replace("//", "/").trimEnd('/').ifEmpty { "/" }

                // 使用 ls -la 列出目录内容；路径经 shellEscape 转义，避免空格/引号/$(...) 导致命令断裂（C5）
                val sh = "ls -la ${shellEscape(inputPath + "/")}"
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
                                // 其他 stream 的关闭，回复 CLSE 完全关闭流
                                Log.d(TAG, "listFiles: other stream closing (arg1=${msg.arg1}), sending CLSE")
                                sendPacket(CMD_CLSE, msg.arg1, msg.arg0, null)
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

                    items.add(FileItem(
                        name = name,
                        path = fullPath,
                        isDirectory = isDir,
                        size = if (isDir) 0L else size,
                        lastModified = lastModified
                    ))
                }

                Log.d(TAG, "listFiles completed, found ${items.size} files")

            } catch (e: Exception) {
                Log.e(TAG, "listFiles error: ${e.message}", e)
                LogCollector.e(TAG, "listFiles error: ${e.message}", e)
                adbSessionAlive = false
            }

            items.sortedWith(compareByDescending<FileItem> { it.isDirectory }.thenBy { it.name.lowercase() })
        } finally {
            lock.unlock()
        }
        return result
    }

    /**
     * 下载远端文件到本地。
     *
     * @param expectedSize 远端文件大小（字节）。>= 0 时落盘后按字节数对账，不符即判失败并删除
     *   半成品 —— 与 [AdbShellClient.pullFile] 同一契约。调用方从 FileItem.size 传入；
     *   拿不到大小时传 -1（跳过对账，保持旧行为）。
     */
    fun downloadFile(remotePath: String, localPath: String, expectedSize: Long = -1L): Boolean {
        lock.lock()
        return try {
            drainStalePackets()
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
                        if (msg.arg1 == sid) {
                            Log.e(TAG, "Sync service open failed - CLSE received, falling back to shell")
                            if (msg.payload.isNotEmpty()) {
                                val error = String(msg.payload, Charsets.UTF_8)
                                Log.e(TAG, "Error: $error")
                            }
                            return downloadFileShell(remotePath, localPath, expectedSize)
                        }
                        // 其他流的 CLSE，发送 CLSE 完全关闭流
                        try { sendPacket(CMD_CLSE, msg.arg1, msg.arg0, null) } catch (_: Exception) {} // catch-ok: 清理其它流的收尾包，失败无补救
                        retryCount++
                    }
                    else -> {
                        Log.w(TAG, "Received unknown response: ${msg.command.toString(16)}")
                        retryCount++
                    }
                }
            }

            if (remoteId == 0) {
                Log.e(TAG, "Sync service open timed out, falling back to shell")
                return downloadFileShell(remotePath, localPath, expectedSize)
            }

            // RECV 命令格式: "RECV" + 4字节路径长度(little endian) + 路径
            sendPacket(CMD_WRTE, sid, remoteId, AdbProtocol.buildRecvRequest(remotePath))

            // 等待 OKAY
            var waitForOkay = true
            while (waitForOkay) {
                val msg = readPacket()
                when (msg.command) {
                    CMD_OKAY -> waitForOkay = false
                    CMD_CLSE -> {
                        if (msg.arg1 == sid) {
                            Log.e(TAG, "RECV command failed")
                            throw Exception("RECV command failed")
                        }
                        sendPacket(CMD_CLSE, msg.arg1, msg.arg0, null)
                    }
                }
            }

            var totalReceived = 0L
            var downloadSuccess = false
            try {
                // sync 消息重组：ADB 流是**字节流**，WRTE 只是传输层分片，不等于 sync 消息边界。
                // adbd 把 DATA 的 8 字节包头与文件负载分两次 write，传输层各成一个 WRTE（大负载还会再拆）。
                // 旧实现「一个 WRTE = 一条完整消息」→ 负载被**静默丢弃**，产出的文件恒为
                // 64KB 整数倍截断（且不报错）。组帧语义现抽至 AdbProtocol.SyncFrameAssembler（可单测）。
                val assembler = AdbProtocol.SyncFrameAssembler()
                FileOutputStream(localPath).use { fos ->
                    download@ while (true) {
                        val msg = readPacket()
                        when (msg.command) {
                            CMD_OKAY -> {}
                            CMD_WRTE -> {
                                // 传输层 ack 必须先回：它只针对本 WRTE，与 sync 消息是否完整无关
                                sendPacket(CMD_OKAY, sid, msg.arg0, null)
                                for (frame in assembler.feed(msg.payload)) {
                                    when (frame) {
                                        is AdbProtocol.SyncFrame.Data -> {
                                            if (frame.bytes.isNotEmpty()) {
                                                fos.write(frame.bytes)
                                                totalReceived += frame.bytes.size
                                            }
                                            if (totalReceived % (1024 * 1024) < 64 * 1024) {
                                                Log.d(TAG, "Download progress: $totalReceived bytes")
                                            }
                                        }
                                        is AdbProtocol.SyncFrame.Fail -> {
                                            Log.e(TAG, "Download failed: ${frame.message}")
                                            throw Exception("Download failed: ${frame.message}")
                                        }
                                        AdbProtocol.SyncFrame.Done -> {
                                            Log.d(TAG, "Download complete: $totalReceived bytes")
                                            sendPacket(CMD_OKAY, sid, msg.arg0, null)
                                            sendPacket(CMD_CLSE, sid, remoteId, null)
                                            try {
                                                socket?.soTimeout = 500
                                                val clseMsg = readPacket()
                                                if (clseMsg.command == CMD_CLSE && clseMsg.arg1 == sid) {
                                                    Log.d(TAG, "Download CLSE acknowledged")
                                                }
                                            } catch (_: Exception) {} // catch-ok: 消费收尾 CLSE 包，读不到即继续，非失败
                                            socket?.soTimeout = AppConfig.ADB_SOCKET_TIMEOUT_MS
                                            downloadSuccess = true
                                            break@download
                                        }
                                    }
                                }
                            }
                            CMD_CLSE -> {
                                sendPacket(CMD_CLSE, sid, remoteId, null)
                                break
                            }
                        }
                    }
                }
                if (downloadSuccess &&
                    !reconcileDownloadedSize(localPath, totalReceived, expectedSize, remotePath)
                ) {
                    downloadSuccess = false
                }
                downloadSuccess
            } catch (e: Exception) {
                Log.e(TAG, "Download failed: ${e.message}", e)
                LogCollector.e(TAG, "Download failed: ${e.message}", e)
                // 失败即删半成品：旧实现只置 false，截断的文件会留在 cache 里被后续预览/分享当完整文件用
                runCatching { File(localPath).delete() }
                // 关闭 sync 流，防止泄漏
                try { sendPacket(CMD_CLSE, sid, remoteId, ByteArray(0)) } catch (_: Exception) {} // catch-ok: 善后清理，失败无补救
                false
            }
        } finally {
            lock.unlock()
        }
    }

    /**
     * 落盘后按远端字节数对账；不符则判为截断/损坏，删除半成品并返回 false。
     *
     * [expectedSize] < 0 = 调用方未提供大小，跳过对账（保持旧行为）。
     */
    private fun reconcileDownloadedSize(
        localPath: String,
        actualSize: Long,
        expectedSize: Long,
        remotePath: String,
    ): Boolean {
        if (expectedSize < 0 || actualSize == expectedSize) return true
        Log.e(TAG, "Download truncated: $remotePath got $actualSize bytes, expected $expectedSize, 删除半成品")
        // 结果必须落 App 内日志面板：静默截断此前完全不可见
        LogCollector.e(TAG, "下载大小不符（截断）：$remotePath 实际 $actualSize / 期望 $expectedSize，已删除半成品")
        runCatching { File(localPath).delete() }
        return false
    }

    private fun downloadFileShell(remotePath: String, localPath: String, expectedSize: Long = -1L): Boolean {
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

            if (!reconcileDownloadedSize(localPath, decoded.size.toLong(), expectedSize, remotePath)) {
                return false
            }

            Log.d(TAG, "Shell download success: $localPath, size: ${decoded.size}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Shell download failed: ${e.message}", e)
            // 失败即删半成品（同 sync 路径契约）
            runCatching { File(localPath).delete() }
            // sync 流被拒后的 shell 降级路径：此前失败在 App 内日志面板完全不可见
            LogCollector.e(TAG, "Shell 降级下载失败: $remotePath", e)
            false
        }
    }

    fun deleteFile(path: String): Boolean {
        lock.lock()
        return try {
            drainStalePackets()
            val sid = localId.getAndIncrement()
            val cmd = "shell:rm -rf ${shellEscape(path)} 2>/dev/null; echo \$?\u0000"
            sendPacket(CMD_OPEN, sid, 0, cmd.toByteArray(Charsets.UTF_8))

            var remoteId = 0
            val allOutput = StringBuilder()
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
                            allOutput.append(String(msg.payload, Charsets.UTF_8))
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
                            sendPacket(CMD_CLSE, msg.arg1, msg.arg0, null)
                        }
                    }
                }
            }
            
            val exitCode = allOutput.toString()
                .split('\n')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .lastOrNull()
                ?.toIntOrNull() ?: -1
            
            Log.d(TAG, "Delete command exit code: $exitCode, path: $path")
            val success = exitCode == 0
            if (!success) {
                Log.e(TAG, "Delete failed with exit code: $exitCode, path: $path")
            }
            success
        } catch (e: Exception) {
            Log.e(TAG, "Delete failed: ${e.message}", e)
            false
        } finally {
            lock.unlock()
        }
    }

    fun copyFile(sourcePath: String, destPath: String): Boolean {
        lock.lock()
        return try {
            drainStalePackets()
            val sid = localId.getAndIncrement()
            // 路径经 shellEscape 防注入；echo $? 回读退出码，杜绝 copy 失败仍报成功（CUT 分支依赖此结果决定是否删源）
            val cmd = "shell:cp -rf ${shellEscape(sourcePath)} ${shellEscape(destPath)} 2>/dev/null; echo \$?\u0000"
            sendPacket(CMD_OPEN, sid, 0, cmd.toByteArray(Charsets.UTF_8))

            var remoteId = 0
            val allOutput = StringBuilder()
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
                            allOutput.append(String(msg.payload, Charsets.UTF_8))
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
                            sendPacket(CMD_CLSE, msg.arg1, msg.arg0, null)
                        }
                    }
                }
            }

            val exitCode = allOutput.toString()
                .split('\n')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .lastOrNull()
                ?.toIntOrNull() ?: -1
            Log.d(TAG, "Copy command exit code: $exitCode, $sourcePath -> $destPath")
            exitCode == 0
        } catch (e: Exception) {
            Log.e(TAG, "Copy failed: ${e.message}", e)
            false
        } finally {
            lock.unlock()
        }
    }

    fun renameFile(oldPath: String, newPath: String): Boolean {
        lock.lock()
        return try {
            drainStalePackets()
            val sid = localId.getAndIncrement()
            val cmd = "shell:mv ${shellEscape(oldPath)} ${shellEscape(newPath)} 2>/dev/null; echo \$?\u0000"
            sendPacket(CMD_OPEN, sid, 0, cmd.toByteArray(Charsets.UTF_8))

            var remoteId = 0
            val allOutput = StringBuilder()
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
                            allOutput.append(String(msg.payload, Charsets.UTF_8))
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
                            sendPacket(CMD_CLSE, msg.arg1, msg.arg0, null)
                        }
                    }
                }
            }

            val exitCode = allOutput.toString()
                .split('\n')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .lastOrNull()
                ?.toIntOrNull() ?: -1
            Log.d(TAG, "Rename command exit code: $exitCode, $oldPath -> $newPath")
            exitCode == 0
        } catch (e: Exception) {
            Log.e(TAG, "Rename failed: ${e.message}", e)
            false
        } finally {
            lock.unlock()
        }
    }

    fun createFolder(path: String): Boolean {
        lock.lock()
        return try {
            drainStalePackets()
            val sid = localId.getAndIncrement()
            val cmd = "shell:mkdir -p ${shellEscape(path)} 2>/dev/null; echo \$?\u0000"
            sendPacket(CMD_OPEN, sid, 0, cmd.toByteArray(Charsets.UTF_8))

            var remoteId = 0
            val allOutput = StringBuilder()
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
                            allOutput.append(String(msg.payload, Charsets.UTF_8))
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
                            sendPacket(CMD_CLSE, msg.arg1, msg.arg0, null)
                        }
                    }
                }
            }

            val exitCode = allOutput.toString()
                .split('\n')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .lastOrNull()
                ?.toIntOrNull() ?: -1
            Log.d(TAG, "Create folder exit code: $exitCode, path: $path")
            exitCode == 0
        } catch (e: Exception) {
            Log.e(TAG, "Create folder failed: ${e.message}", e)
            false
        } finally {
            lock.unlock()
        }
    }
    
    /** 获取存储空间信息 */
    fun getStorageInfo(path: String): StorageInfo? {
        lock.lock()
        return try {
            getStorageInfoByDeviceInternal("/storage/emulated")
        } catch (e: Exception) {
            Log.e(TAG, "Get storage info failed: ${e.message}", e)
            null
        } finally {
            lock.unlock()
        }
    }

    private fun getStorageInfoByDeviceInternal(device: String): StorageInfo? {
        return try {
            if (!isConnected()) {
                Log.w(TAG, "getStorageInfoByDeviceInternal: not connected, skipping")
                return null
            }
            val sid = localId.getAndIncrement()
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
                            sendPacket(CMD_CLSE, msg.arg1, msg.arg0, null)
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
                            val total = AdbProtocol.parseStorageSize(parts[1])
                            val used = AdbProtocol.parseStorageSize(parts[2])
                            val free = AdbProtocol.parseStorageSize(parts[3])
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
            null
        } catch (e: Exception) {
            Log.e(TAG, "Get storage info from device failed: ${e.message}", e)
            null
        }
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

    fun uploadFile(localPath: String, remotePath: String, retryCount: Int = 0): Boolean {
        lock.lock()
        return try {
            var uploadOk = false
            drainStalePackets()
            if (!isConnected()) {
                Log.i(TAG, "Connection not alive before upload, reconnecting...")
                // 先释放锁再重连，避免 connect() 阻塞锁
                lock.unlock()
                try {
                    disconnect()
                    Thread.sleep(300)
                    if (!connect { }) {
                        Log.e(TAG, "Reconnect failed")
                        LogCollector.w(TAG, "上传前重连失败，本次上传放弃")
                        return false
                    }
                } finally {
                    lock.lock()
                }
            }

            val file = File(localPath)
            if (!file.exists()) {
                Log.e(TAG, "Local file not found: $localPath")
                return false
            }

            Log.d(TAG, "Upload file: $localPath -> $remotePath, file size: ${file.length()}")

            val remoteDir = remotePath.substringBeforeLast('/', "/")
            if (remoteDir.isNotEmpty() && remoteDir != "/") {
                val mkdirCmd = "shell:mkdir -p ${shellEscape(remoteDir)}\u0000"
                executeShellCommandInternal(mkdirCmd)
                Log.d(TAG, "Creating directory: $remoteDir")
            }

            val sid = localId.getAndIncrement()
            val cmd = "sync:\u0000"
            sendPacket(CMD_OPEN, sid, 0, cmd.toByteArray(Charsets.UTF_8))

            var remoteId = 0
            while (true) {
                val msg = readPacket()
                when (msg.command) {
                    CMD_OKAY -> {
                        remoteId = msg.arg0
                        Log.d(TAG, "Sync service opened, remoteId=$remoteId")
                        break
                    }
                    CMD_CLSE -> {
                        if (msg.arg1 == sid) {
                            Log.e(TAG, "Sync service open failed")
                            return uploadFileShell(file, remotePath)
                        }
                        // 其他流的 CLSE，发送 CLSE 完全关闭流
                        sendPacket(CMD_CLSE, msg.arg1, msg.arg0, null)
                    }
                }
            }

            sendPacket(CMD_WRTE, sid, remoteId, AdbProtocol.buildSendRequest(remotePath))

            var waitForOkay = true
            while (waitForOkay) {
                val msg = readPacket()
                when (msg.command) {
                    CMD_OKAY -> waitForOkay = false
                    CMD_CLSE -> {
                        if (msg.arg1 == sid) {
                            Log.e(TAG, "SEND command failed")
                            return uploadFileShell(file, remotePath)
                        }
                        // 其他流的 CLSE，发送 CLSE 完全关闭流
                        sendPacket(CMD_CLSE, msg.arg1, msg.arg0, null)
                    }
                }
            }

            val chunkSize = 256 * 1024
            val pipelineDepth = 4
            var totalSent = 0L
            var pendingAcks = 0

            file.inputStream().use { fis ->
                val buffer = ByteArray(chunkSize)
                var bytesRead: Int
                while (fis.read(buffer).also { bytesRead = it } > 0) {
                    sendPacket(CMD_WRTE, sid, remoteId, AdbProtocol.buildDataChunk(buffer, bytesRead))
                    totalSent += bytesRead
                    pendingAcks++

                    if (pendingAcks >= pipelineDepth) {
                        while (pendingAcks > 0) {
                            val msg = readPacket()
                            when (msg.command) {
                                CMD_OKAY -> pendingAcks--
                                CMD_CLSE -> {
                                    if (msg.arg1 == sid) {
                                        Log.e(TAG, "DATA command failed")
                                        return false
                                    }
                                    sendPacket(CMD_CLSE, msg.arg1, msg.arg0, null)
                                }
                            }
                        }
                    }

                    if (totalSent % (1024 * 1024) == 0L) {
                        Log.d(TAG, "Upload progress: $totalSent / ${file.length()}")
                    }
                }
            }

            while (pendingAcks > 0) {
                val msg = readPacket()
                when (msg.command) {
                    CMD_OKAY -> pendingAcks--
                    CMD_CLSE -> {
                        if (msg.arg1 == sid) {
                            Log.e(TAG, "DATA command failed")
                            return false
                        }
                        sendPacket(CMD_CLSE, msg.arg1, msg.arg0, null)
                    }
                }
            }

            // DONE 帧负载为 4 字节 LE 时间戳（秒）；adbd 不校验该值，但按协议带上
            sendPacket(CMD_WRTE, sid, remoteId, AdbProtocol.buildDoneCommand((System.currentTimeMillis() / 1000).toInt()))

            var gotFinalClse = false
            var clseRetryCount = 0
            while (!gotFinalClse) {
                val msg = readPacket()
                when (msg.command) {
                    CMD_OKAY -> { }
                    CMD_WRTE -> {
                        val payload = msg.payload
                        if (payload.size >= 4) {
                            val cmdStr = String(payload.copyOfRange(0, 4), Charsets.UTF_8)
                            if (cmdStr == "OKAY") {
                                sendPacket(CMD_OKAY, sid, msg.arg0, null)
                                Log.d(TAG, "Upload success")
                                uploadOk = true
                                sendPacket(CMD_CLSE, sid, remoteId, null)
                                socket?.soTimeout = 500
                                try {
                                    val closeAck = readPacket()
                                    if (closeAck.command == CMD_OKAY) {
                                        Log.d(TAG, "CLSE acknowledged")
                                    }
                                } catch (_: Exception) {} // catch-ok: 消费收尾 CLSE 包，读不到即继续，非失败
                                socket?.soTimeout = AppConfig.ADB_SOCKET_TIMEOUT_MS
                                return true
                            } else if (cmdStr == "FAIL") {
                                val errLen = ((payload[4].toInt() and 0xFF) or
                                              ((payload[5].toInt() and 0xFF) shl 8) or
                                              ((payload[6].toInt() and 0xFF) shl 16) or
                                              ((payload[7].toInt() and 0xFF) shl 24))
                                val errMsg = if (payload.size >= 8 + errLen) String(payload, 8, errLen, Charsets.UTF_8) else "?"
                                Log.e(TAG, "Upload failed: FAIL response: $errMsg")
                                sendPacket(CMD_OKAY, sid, msg.arg0, null)
                                uploadOk = false
                                sendPacket(CMD_CLSE, sid, remoteId, null)
                                socket?.soTimeout = 500
                                try {
                                    val closeAck = readPacket()
                                    if (closeAck.command == CMD_OKAY) {
                                        Log.d(TAG, "CLSE acknowledged after FAIL")
                                    }
                                } catch (_: Exception) {} // catch-ok: 消费收尾 CLSE 包，读不到即继续，非失败
                                socket?.soTimeout = AppConfig.ADB_SOCKET_TIMEOUT_MS
                                return false
                            }
                        }
                    }
                    CMD_CLSE -> {
                        if (msg.arg1 == sid) {
                            sendPacket(CMD_OKAY, sid, remoteId, null)
                            gotFinalClse = true
                        } else {
                            sendPacket(CMD_CLSE, msg.arg1, msg.arg0, null)
                            clseRetryCount++
                            if (clseRetryCount > 3) {
                                Log.w(TAG, "Received too many unrelated CLSE packets, breaking loop")
                                gotFinalClse = true
                            }
                        }
                    }
                }
            }

            // Ensure sync stream is closed even if while loop exits without OKAY/FAIL
            sendPacket(CMD_CLSE, sid, remoteId, null)
            try {
                socket?.soTimeout = 500
                val closeAck = readPacket()
                if (closeAck.command == CMD_OKAY) {
                    Log.d(TAG, "CLSE acknowledged after fallthrough")
                }
                if (closeAck.command == CMD_CLSE && closeAck.arg1 == sid) {
                    Log.d(TAG, "CLSE from daemon after fallthrough")
                }
            } catch (_: Exception) {} // catch-ok: 消费收尾 CLSE 包，读不到即继续，非失败
            socket?.soTimeout = AppConfig.ADB_SOCKET_TIMEOUT_MS

            uploadOk
        } catch (e: Exception) {
            Log.e(TAG, "Upload failed: ${e.message}", e)
            LogCollector.e(TAG, "Upload failed: ${e.message}", e)
            if (retryCount < 1 && (e is java.net.SocketException || e is java.io.IOException)) {
                try {
                    Log.i(TAG, "Connection lost, attempting reconnect...")
                    disconnect()
                    Thread.sleep(500)
                    if (connect { }) {
                        Log.i(TAG, "Reconnected, retrying upload (attempt ${retryCount + 1})...")
                        return uploadFile(localPath, remotePath, retryCount + 1)
                    }
                } catch (e: Exception) {
                    LogCollector.w(TAG, "上传重连链路异常，重试放弃", e)
                }
            }
            false
        } finally {
            lock.unlock()
        }
    }
    
    private fun uploadFileShell(file: File, remotePath: String): Boolean {
        Log.d(TAG, "Using shell to upload: ${file.name}")
        
        fun shellEscape(s: String): String = "'${s.replace("'", "'\\''")}'"
        val escapedRemotePath = shellEscape(remotePath)
        
        val chunkSize = 64 * 1024
        var isFirst = true
        var totalSent = 0L
        val startTime = System.currentTimeMillis()
        
        var allSuccess = true
        file.inputStream().use { fis ->
            val buffer = ByteArray(chunkSize)
            var bytesRead: Int
            while (fis.read(buffer).also { bytesRead = it } > 0) {
                val chunk = buffer.copyOf(bytesRead)
                val base64 = android.util.Base64.encodeToString(chunk, android.util.Base64.NO_WRAP)
                
                val redirect = if (isFirst) ">" else ">>"
                val cmd = "shell:echo '$base64' | base64 -d $redirect $escapedRemotePath\u0000"
                
                try {
                    executeShellCommandInternal(cmd)
                    totalSent += bytesRead
                    if (totalSent % (1024 * 1024) == 0L) {
                        val elapsed = System.currentTimeMillis() - startTime
                        val speed = (totalSent / 1024.0) / (elapsed / 1000.0)
                        Log.d(TAG, "Shell upload progress: $totalSent / ${file.length()}, speed: ${String.format("%.1f", speed)} KB/s")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Shell upload chunk failed: ${e.message}")
                    allSuccess = false
                    break
                }
                
                isFirst = false
            }
        }

        if (!allSuccess) return false

        val elapsed = System.currentTimeMillis() - startTime
        val speed = (totalSent / 1024.0) / (elapsed / 1000.0)
        Log.d(TAG, "Shell upload completed in ${elapsed}ms, speed: ${String.format("%.1f", speed)} KB/s")

        val checkCmd = "shell:wc -c < $escapedRemotePath 2>/dev/null || echo 0\u0000"
        val checkResult = try {
            executeShellCommandInternal(checkCmd).trim()
        } catch (e: Exception) {
            Log.e(TAG, "Shell upload verify failed: ${e.message}")
            "0"
        }
        val remoteSize = checkResult.toLongOrNull() ?: 0
        val success = remoteSize == file.length()
        
        Log.d(TAG, "Shell upload ${if (success) "success" else "failed"}, local size: ${file.length()}, remote size: $remoteSize")
        return success
    }
    
    private fun executeShellCommandInternal(cmd: String, retryOnFail: Boolean = true): String {
        lock.lock()
        try {
            val sid = localId.getAndIncrement()
            try {
                sendPacket(CMD_OPEN, sid, 0, cmd.toByteArray(Charsets.UTF_8))
            } catch (e: Exception) {
                adbSessionAlive = false
                if (retryOnFail && (e is java.net.SocketException || e is java.io.IOException)) {
                    Log.i(TAG, "Connection lost in shell cmd, reconnecting...")
                    try { socket?.close() } catch (_: Exception) {} // catch-ok: 重连前主动断开，关闭失败无补救
                    socket = null; inputStream = null; outputStream = null
                    Thread.sleep(500)
                    if (connect { }) {
                        Log.i(TAG, "Reconnected, retrying shell cmd...")
                        return executeShellCommandInternal(cmd, false)
                    }
                }
                throw e
            }

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
                            try {
                                socket?.soTimeout = 500
                                val clseMsg = readPacket()
                                if (clseMsg.command == CMD_CLSE && clseMsg.arg1 == sid) {
                                    Log.d(TAG, "executeShellCommandInternal: CLSE acknowledged")
                                }
                            } catch (_: Exception) {} // catch-ok: 消费收尾 CLSE 包，读不到即继续，非失败
                            socket?.soTimeout = AppConfig.ADB_SOCKET_TIMEOUT_MS
                            break
                        } else {
                            sendPacket(CMD_CLSE, msg.arg1, msg.arg0, null)
                        }
                    }
                }
            }
            
            return output.toString()
        } finally {
            lock.unlock()
        }
    }
    
    fun executeShellCommand(cmd: String): String {
        lock.lock()
        try {
            return executeShellCommandInternal("shell:$cmd\u0000")
        } finally {
            lock.unlock()
        }
    }

    fun isAppInstalled(packageName: String): Boolean {
        lock.lock()
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
        } finally {
            lock.unlock()
        }
    }

    fun launchApp(packageName: String, activityClass: String = ""): Boolean {
        lock.lock()
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
        } finally {
            lock.unlock()
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
        buf.putInt(AdbProtocol.adbChecksum(payload))
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

        if (msg.payloadLength < 0) throw java.io.IOException("bad payload length: ${msg.payloadLength}")
        if (msg.payloadLength > AppConfig.ADB_MAX_PAYLOAD) throw java.io.IOException("payload too large: ${msg.payloadLength}")
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

    fun isConnected(): Boolean {
        return socket?.isConnected == true && socket?.isClosed == false && adbSessionAlive
    }

    fun disconnect() {
        adbSessionAlive = false
        try { socket?.close() } catch (_: Exception) {} // catch-ok: disconnect 收尾，关闭失败无补救
        socket = null
        inputStream = null
        outputStream = null
    }
    
    /** 读取文件内容（用于预览） */
    fun readFileContent(path: String, maxSize: Int = 1024 * 1024): String? {
        lock.lock()
        return try {
            readFileContentInternal(path, maxSize)
        } catch (e: Exception) {
            Log.e(TAG, "Read file content failed: ${e.message}", e)
            null
        } finally {
            lock.unlock()
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
        // 与 downloadFile 同源修复：ADB 流是**字节流**，WRTE 只是传输层分片，不等于 sync 消息边界。
        // adbd 把 DATA 的 8 字节包头与内容分两次 write，传输层各成一个 WRTE。旧实现「一个 WRTE =
        // 一条 sync 消息」→ `payload.size >= 8 + size` 不成立时**静默丢弃**整块内容（文件内容被
        // 截断且不报错），或把裸内容字节当包头而抛出 "Unexpected sync frame"。
        var acc = ByteArray(64 * 1024 + 64)
        var accLen = 0
        var sawDone = false
        var finished = false

        while (!finished) {
            val msg = readPacket()
            when (msg.command) {
                CMD_OKAY -> {
                    // 继续等待数据
                }
                CMD_WRTE -> {
                    // 传输层 ack 必须先回：它只针对本 WRTE，与 sync 消息是否完整无关
                    sendPacket(CMD_OKAY, sid, msg.arg0, null)
                    if (accLen + msg.payload.size > acc.size) {
                        acc = acc.copyOf(maxOf(acc.size * 2, accLen + msg.payload.size))
                    }
                    System.arraycopy(msg.payload, 0, acc, accLen, msg.payload.size)
                    accLen += msg.payload.size
                    var pos = 0
                    parse@ while (true) {
                        if (accLen - pos < 8) break@parse
                        val cmdStr = String(acc, pos, 4, Charsets.UTF_8)
                        val size = ((acc[pos + 4].toInt() and 0xFF)) or
                                   ((acc[pos + 5].toInt() and 0xFF) shl 8) or
                                   ((acc[pos + 6].toInt() and 0xFF) shl 16) or
                                   ((acc[pos + 7].toInt() and 0xFF) shl 24)
                        when (cmdStr) {
                            "DATA" -> {
                                if (size < 0 || size > acc.size) {
                                    throw Exception("Read file failed: 非法 DATA 长度 $size")
                                }
                                if (accLen - pos - 8 < size) break@parse // 内容未到齐，等下一片
                                if (size > 0) {
                                    output.write(acc, pos + 8, size)
                                    totalRead += size
                                }
                                pos += 8 + size
                            }
                            "DONE" -> {
                                sawDone = true
                                finished = true
                                break@parse
                            }
                            "FAIL" -> {
                                if (accLen - pos - 8 < size) break@parse // 报错文案未到齐，等下一片
                                val error = if (size > 0) String(acc, pos + 8, size, Charsets.UTF_8)
                                else "Unknown error"
                                Log.e(TAG, "Read file failed: $error")
                                throw Exception("Read file failed: $error")
                            }
                            else -> throw Exception("Read file failed: Unexpected sync frame: $cmdStr")
                        }
                    }
                    if (pos > 0) {
                        System.arraycopy(acc, pos, acc, 0, accLen - pos)
                        accLen -= pos
                    }
                }
                CMD_CLSE -> {
                    finished = true
                }
            }

            if (totalRead >= maxSize) finished = true
        }

        sendPacket(CMD_CLSE, sid, remoteId, null)
        if (sawDone) {
            // 消费服务端的 CLSE 响应包（仅消费当前 stream 的）
            try {
                while (true) {
                    val clseMsg = readPacket()
                    if (clseMsg.command == CMD_CLSE && clseMsg.arg1 == sid) break
                }
            } catch (_: Exception) {} // catch-ok: 消费收尾 CLSE 包，读不到即继续，非失败
        }
        return output.toString(Charsets.UTF_8.name())
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
