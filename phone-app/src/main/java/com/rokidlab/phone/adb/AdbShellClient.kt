package com.rokidlab.phone.adb

import android.content.Context
import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class AdbShellClient(
    private val context: Context,
    private val ipAddress: String,
    private val port: Int = 5555,
) {
    companion object {
        private const val TAG = "AdbShellClient"
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
        private const val MAX_PAYLOAD = 256 * 1024
    }
    private var socket: Socket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    private var keyPair: KeyPair? = null
    // 简单的递增 localId，每次 open 自增，close 复用同一个
    private var nextLocalId = 0
    private val lock = ReentrantLock()

    private data class AdbMessage(val command: Int, val arg0: Int, val arg1: Int, val payload: ByteArray)

    fun connect(): Boolean = try {
        Log.i(TAG, "Connecting to $ipAddress:$port...")
        socket = Socket()
        socket?.tcpNoDelay = true
        socket?.soTimeout = 10000
        socket?.connect(java.net.InetSocketAddress(ipAddress, port), 10000)
        Log.i(TAG, "TCP connection established")
        inputStream = socket?.getInputStream()
        outputStream = socket?.getOutputStream()
        keyPair = AdbKeyManager.getOrCreateKeyPair(context.filesDir.absolutePath)
        nextLocalId = 0
        doHandshake()
        Log.i(TAG, "ADB connection successful")
        true
    } catch (e: Exception) {
        Log.e(TAG, "Connection failed: ${e.message}", e)
        disconnect()
        false
    }

    // ── 认证握手 ──
    private fun doHandshake() {
        val kp = keyPair ?: error("keyPair not initialized")
        val cnxnPayload = "host::\u0000".toByteArray(Charsets.UTF_8)
        Log.i(TAG, "Sending CNXN...")
        writeMessage(CMD_CNXN, CONNECT_VERSION, MAX_PAYLOAD, cnxnPayload)
        var authAttempts = 0

        while (authAttempts < 5) {
            val msg = readMessage()
            Log.i(TAG, "Received command: 0x${msg.command.toString(16)}, arg0=${msg.arg0}, arg1=${msg.arg1}")
            when (msg.command) {
                CMD_CNXN -> { Log.i(TAG, "Received CNXN, handshake complete"); return }
                CMD_AUTH -> {
                    if (msg.arg0 == AUTH_TOKEN) {
                        authAttempts++
                        Log.i(TAG, "AUTH_TOKEN attempt=$authAttempts")
                        if (authAttempts == 1) {
                            val sig = Signature.getInstance("SHA1withRSA")
                            sig.initSign(kp.private)
                            sig.update(msg.payload)
                            writeMessage(CMD_AUTH, AUTH_SIGNATURE, 0, sig.sign())
                            Log.i(TAG, "Sending AUTH_SIGNATURE")
                        } else {
                            val pub = getAdbPublicKeyPayload()
                            writeMessage(CMD_AUTH, AUTH_RSA_PUBLIC, 0, pub)
                            Log.i(TAG, "Sending AUTH_RSA_PUBLIC (${pub.size} bytes)")
                        }
                    }
                }
                else -> Log.w(TAG, "Unknown command: 0x${msg.command.toString(16)}")
            }
        }
        throw RuntimeException("ADB authentication failed ($authAttempts attempts)")
    }

    // ── 公钥编码 ──
    private fun getAdbPublicKeyPayload(): ByteArray {
        val kp = keyPair ?: error("keyPair not initialized")
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

    fun disconnect() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        inputStream = null
        outputStream = null
    }

    fun isConnected(): Boolean = socket?.isConnected == true && socket?.isClosed == false

    // ── 公开 API ──

    fun executeShellCommand(command: String, timeoutMs: Int = 15000): String {
        lock.withLock {
            Log.i(TAG, "execute start cmd=[$command] timeout=$timeoutMs")
            drainStaleMessages()
            val result = openAndExchange("shell:$command", timeoutMs)
            Log.i(TAG, "execute end cmd=[$command] result_len=${result.length}")
            return result
        }
    }

    fun listPackages(includeSystem: Boolean = false): List<String> {
        val cmd = if (includeSystem) "pm list packages" else "pm list packages -3"
        val result = executeShellCommand(cmd)
        val lines = result.lines()
            .filter { it.startsWith("package:") }
            .map { it.removePrefix("package:").trim() }
            .filter { it.isNotBlank() }
            .sorted()
        Log.i(TAG, "listPackages(includeSystem=$includeSystem) lines=${lines.size}")
        return lines
    }

    fun listDisabledPackages(): Set<String> {
        val result = executeShellCommand("pm list packages -d --user 0 2>&1")
        val pkgs = result.lines()
            .filter { it.startsWith("package:") }
            .map { it.removePrefix("package:").trim() }
            .filter { it.isNotBlank() }
            .toSet()
        Log.i(TAG, "listDisabledPackages count=${pkgs.size}")
        return pkgs
    }

    fun getPackageInfo(packageName: String): String {
        return executeShellCommand("dumpsys package $packageName 2>/dev/null | grep -E 'versionName|versionCode|firstInstall|lastUpdate' | head -10")
    }

    fun launchApp(packageName: String): String {
        // monkey 在 Android 12 标记废弃但仍可用（兼容所有版本）
        val result = executeShellCommand("monkey -p $packageName 1 2>&1")
        return if (result.contains("Error") || result.contains("Exception") || result.contains("crash")) "启动失败: $packageName" else "已启动 $packageName"
    }

    fun uninstallApp(packageName: String): String {
        // Android 12+ 系统应用需 --user 0
        val result = executeShellCommand("pm uninstall --user 0 $packageName 2>&1")
        if (result.contains("Exception") || result.contains("Error") || result.contains("Failure")) {
            val fallback = executeShellCommand("pm uninstall $packageName 2>&1")
            return if (fallback.contains("Exception") || fallback.contains("Error") || fallback.contains("Failure")) "卸载失败: $packageName" else "已卸载 $packageName"
        }
        return "已卸载 $packageName"
    }

    fun disableApp(packageName: String): String {
        // 先尝试 pm disable-user（兼容 Android 12+ / 眼镜系统），失败再试 pm disable
        val cmds = listOf(
            "pm disable-user --user 0 $packageName 2>&1",
            "pm disable $packageName 2>&1",
        )
        for (cmd in cmds) {
            val result = executeShellCommand(cmd)
            Log.i(TAG, "disableApp cmd=[$cmd] result_len=${result.length} result=${result.take(200)}")
            if (!result.contains("Exception") && !result.contains("Error") && !result.contains("Killed")) {
                return "已冻结 $packageName"
            }
        }
        return "错误: 冻结失败，可能不允许冻结此应用"
    }

    fun enableApp(packageName: String): String {
        val result = executeShellCommand("pm enable --user 0 $packageName 2>&1")
        Log.i(TAG, "enableApp cmd=[pm enable --user 0 $packageName] result_len=${result.length} result=${result.take(200)}")
        if (result.contains("Exception") || result.contains("Error")) {
            val fallback = executeShellCommand("pm enable $packageName 2>&1")
            if (fallback.contains("Exception") || fallback.contains("Error")) return "错误: 解冻失败"
            return "已解冻 $packageName"
        }
        return "已解冻 $packageName"
    }

    fun getApkPath(packageName: String): String {
        val result = executeShellCommand("pm path $packageName")
        return result.lines().firstOrNull { it.startsWith("package:") }?.removePrefix("package:")?.trim() ?: ""
    }

    fun extractApkToDownloads(packageName: String): String {
        val path = getApkPath(packageName)
        if (path.isEmpty()) return "未找到 $packageName 的 APK 路径"
        // 先复制到眼镜的 Download 目录
        val remoteDest = "/sdcard/Download/${packageName}.apk"
        val cpResult = executeShellCommand("cp $path $remoteDest 2>&1 && echo OK")
        if (!cpResult.trim().endsWith("OK")) return "复制到眼镜失败: $cpResult"
        // 再通过 ADB sync 协议拉取到手机 Download 目录
        val localDir = File("/sdcard/Download")
        if (!localDir.exists()) localDir.mkdirs()
        val localFile = File(localDir, "${packageName}.apk")
        val pullOk = pullFile(remoteDest, localFile.absolutePath)
        return if (pullOk) "已下载到手机 Download/${packageName}.apk" else "拉取到手机失败"
    }

    fun openDownloads() {
        // 尝试多种方式打开下载目录，全部 2>/dev/null 避免异常
        executeShellCommand("am start -a android.settings.INTERNAL_STORAGE_SETTINGS 2>/dev/null")
        executeShellCommand("am start -a android.intent.action.VIEW -d content://com.android.externalstorage.documents/document/primary%3ADownload 2>/dev/null")
        executeShellCommand("am start -a android.intent.action.MAIN -d content://com.android.externalstorage.documents/document/primary%3ADownload 2>/dev/null")
        executeShellCommand("am start -a android.intent.action.VIEW -d file:///sdcard/Download 2>/dev/null")
    }

    fun sendNotification(title: String, content: String): String {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        val logLine = "[$timestamp] $title: $content"
        
        // 方式1: 尝试在眼镜上发通知
        executeShellCommand("cmd notification set_dnd off 2>/dev/null")
        val escapedContent = escapeShell(content)
        val escapedTitle = escapeShell(title)
        val result = executeShellCommand("cmd notification post -t $escapedTitle timer_msg $escapedContent 2>&1")
        Log.i(TAG, "sendNotification cmd_notification result_len=${result.length}")
        
        // 方式2: 尝试在眼镜上弹 Toast
        executeShellCommand("am start -a android.intent.action.MAIN --es toast_text ${escapeShell(content)} 2>/dev/null")
        
        // 方式3: 写入文件（最可靠）
        executeShellCommand("echo ${escapeShell(logLine)} >> /sdcard/Download/timer_messages.txt 2>/dev/null")
        
        return "通知已发送"
    }

    fun getSystemProperties(): String {
        return executeShellCommand("getprop")
    }

    fun getBatteryInfo(): String {
        return executeShellCommand("dumpsys battery")
    }

    fun getDeviceInfo(): String {
        val cmds = listOf(
            "getprop ro.product.model",
            "getprop ro.product.manufacturer",
            "getprop ro.build.version.release",
            "getprop ro.build.version.sdk",
            "getprop ro.serialno",
        )
        return cmds.joinToString("\n") { "$it: ${executeShellCommand(it).trim()}" }
    }

    fun sendText(text: String): String {
        return executeShellCommand("input text ${escapeShell(text)}")
    }

    fun sendKeyEvent(keyCode: Int): String {
        return executeShellCommand("input keyevent $keyCode")
    }

    fun tap(x: Int, y: Int): String {
        return executeShellCommand("input tap $x $y")
    }

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int = 300): String {
        return executeShellCommand("input swipe $x1 $y1 $x2 $y2 $durationMs")
    }

    // ──────────────────────────────────────────────────
    // ADB 流协议：open/close localId 必须配对使用
    // ──────────────────────────────────────────────────

    /** 通过 ADB sync RECV 协议从远程设备拉取文件到本地 */
    fun pullFile(remotePath: String, localPath: String): Boolean {
        lock.withLock {
            var closeLocalId = 0
            var closeRemoteId = 0
            try {
                val localId = ++nextLocalId
                closeLocalId = localId
                val syncPayload = "sync:\u0000".toByteArray(Charsets.UTF_8)
                writeMessage(CMD_OPEN, localId, 0, syncPayload)
                var resp = readMessage()
                if (resp.command != CMD_OKAY) {
                    Log.w(TAG, "pullFile open sync failed: cmd=0x${resp.command.toString(16)}")
                    return false
                }
                val remoteId = resp.arg0
                closeRemoteId = remoteId

                val pathBytes = remotePath.toByteArray(Charsets.UTF_8)
                val recvBuf = ByteBuffer.allocate(8 + pathBytes.size)
                recvBuf.order(ByteOrder.LITTLE_ENDIAN)
                recvBuf.put("RECV".toByteArray(Charsets.UTF_8))
                recvBuf.putInt(pathBytes.size)
                recvBuf.put(pathBytes)
                writeMessage(CMD_WRTE, localId, remoteId, recvBuf.array())

                var okay2 = readMessage()
                if (okay2.command != CMD_OKAY) {
                    Log.w(TAG, "pullFile no OKAY after RECV: cmd=0x${okay2.command.toString(16)}")
                    close(localId, remoteId)
                    return false
                }

                val localFile = File(localPath)
                localFile.parentFile?.mkdirs()
                FileOutputStream(localFile).use { fos ->
                    socket?.soTimeout = 30000
                    while (true) {
                        val msg = readMessage()
                        if (msg.command == CMD_WRTE && msg.arg0 == remoteId) {
                            if (msg.payload.size < 8) break
                            val syncId = String(msg.payload, 0, 4, Charsets.UTF_8)
                            when (syncId) {
                                "DATA" -> {
                                    val len = ByteBuffer.wrap(msg.payload, 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt()
                                    if (len > 0 && 8 + len <= msg.payload.size) {
                                        fos.write(msg.payload, 8, len)
                                    }
                                    writeMessage(CMD_OKAY, localId, remoteId, ByteArray(0))
                                }
                                "DONE" -> {
                                    writeMessage(CMD_OKAY, localId, remoteId, ByteArray(0))
                                    break
                                }
                                else -> break
                            }
                        } else if (msg.command == CMD_CLSE && msg.arg0 == remoteId) {
                            break
                        }
                    }
                }

                close(localId, remoteId)
                socket?.soTimeout = 5000
                Log.i(TAG, "pullFile success: $remotePath → $localPath (${localFile.length()} bytes)")
                return true
            } catch (e: Exception) {
                Log.e(TAG, "pullFile failed: ${e.message}", e)
                // 异常时清理 sync 流，防止泄漏
                if (closeLocalId > 0 && closeRemoteId > 0) {
                    try { close(closeLocalId, closeRemoteId) } catch (_: Exception) {}
                }
                socket?.soTimeout = 5000
                return false
            }
        }
    }

    private fun openAndExchange(service: String, timeoutMs: Int): String {
        val result = open(service)
        if (result == null) {
            drainStaleMessages()
            val retry = open(service)
            if (retry == null) return ""
            val (localId, remoteId, _) = retry
            val data = readAllWrites(localId, remoteId, timeoutMs)
            close(localId, remoteId)
            return data
        }
        val (localId, remoteId, _) = result
        val data = readAllWrites(localId, remoteId, timeoutMs)
        close(localId, remoteId)
        return data
    }

    /**
     * 打开 ADB 服务流。
     * @return Triple(localId, remoteId, respMessage) 或 null 失败
     */
    private fun open(service: String): Triple<Int, Int, AdbMessage>? {
        nextLocalId++
        val localId = nextLocalId
        val payload = service.toByteArray(Charsets.UTF_8)
        writeMessage(CMD_OPEN, localId, 0, payload)
        val resp = readMessage()
        return if (resp.command == CMD_OKAY) {
            // OKAY: arg0=服务器分配的远程ID, arg1=我们的localId(确认)
            Triple(localId, resp.arg0, resp)
        } else {
            Log.w(TAG, "open($service) failed: cmd=0x${resp.command.toString(16)}")
            null
        }
    }

    /**
     * 读取所有 WRTE 数据，直到收到 CLSE。
     * @param localId 此流使用的本地ID（来自 open）
     * @param remoteId 服务器分配的远程ID（来自 OKAY 的 arg0）
     */
    private fun readAllWrites(localId: Int, remoteId: Int, timeoutMs: Int): String {
        val baos = ByteArrayOutputStream()
        socket?.soTimeout = timeoutMs
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val msg = try { readMessage() } catch (_: Exception) { break }
            when {
                // WRTE(arg0=remoteId, arg1=localId, data) — 服务器发数据
                msg.command == CMD_WRTE && msg.arg0 == remoteId -> {
                    baos.write(msg.payload)
                    // OKAY(localId, remoteId) 确认收到
                    writeMessage(CMD_OKAY, localId, remoteId, ByteArray(0))
                    socket?.soTimeout = (deadline - System.currentTimeMillis()).toInt().coerceAtLeast(2000)
                }
                // CLSE(arg0=remoteId) — 服务器关闭流
                msg.command == CMD_CLSE && msg.arg0 == remoteId -> break
            }
        }
        return baos.toString(Charsets.UTF_8)
    }

    /**
     * 关闭流，复用 open 时分配的 localId。
     * 发送 CLSE(localId, remoteId) 并消费所有残留消息。
     */
    private fun close(localId: Int, remoteId: Int) {
        writeMessage(CMD_CLSE, localId, remoteId, ByteArray(0))
        try {
            socket?.soTimeout = 3000
            while (true) {
                val msg = readMessage()
                // 只退出匹配当前 stream 的 CLSE，其他继续消费
                if (msg.command == CMD_CLSE && msg.arg1 == localId) break
            }
        } catch (_: Exception) { }
    }

    /** 清空 socket 中堆积的残留消息 */
    private fun drainStaleMessages() {
        try {
            socket?.soTimeout = 500
            while (true) { readMessage() }
        } catch (_: Exception) { }
    }

    // ── 消息读写 ──

    private fun writeMessage(command: Int, arg0: Int, arg1: Int, payload: ByteArray) {
        val totalLen = HEADER_LENGTH + payload.size
        val buf = ByteBuffer.allocate(totalLen)
        buf.order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(command)
        buf.putInt(arg0)
        buf.putInt(arg1)
        buf.putInt(payload.size)
        buf.putInt(checksum(payload))
        buf.putInt(command.inv())
        if (payload.isNotEmpty()) buf.put(payload)
        outputStream?.write(buf.array())
        outputStream?.flush()
    }

    private fun checksum(data: ByteArray): Int {
        var sum = 0
        for (b in data) sum += b.toInt() and 0xFF
        return sum
    }

    private fun readMessage(): AdbMessage {
        val header = ByteArray(HEADER_LENGTH)
        var offset = 0
        while (offset < HEADER_LENGTH) {
            val n = inputStream?.read(header, offset, HEADER_LENGTH - offset) ?: -1
            if (n < 0) throw java.io.EOFException("Connection closed")
            offset += n
        }
        val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val command = buf.getInt()
        val arg0 = buf.getInt()
        val arg1 = buf.getInt()
        val payloadLength = buf.getInt()
        buf.getInt(); buf.getInt() // skip checksum, magic
        val payload = if (payloadLength > 0) {
            val data = ByteArray(payloadLength)
            var pos = 0
            while (pos < payloadLength) {
                val n = inputStream?.read(data, pos, payloadLength - pos) ?: -1
                if (n < 0) throw java.io.EOFException("Connection closed")
                pos += n
            }
            data
        } else ByteArray(0)
        return AdbMessage(command, arg0, arg1, payload)
    }

    private fun generateRsaKeyPair(): KeyPair {
        val gen = KeyPairGenerator.getInstance("RSA")
        gen.initialize(2048)
        return gen.generateKeyPair()
    }

    private fun escapeShell(text: String): String {
        return text.replace("'", "'\\''").let { "'$it'" }
    }
}
