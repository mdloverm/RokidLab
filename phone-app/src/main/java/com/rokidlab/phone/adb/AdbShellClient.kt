package com.rokidlab.phone.adb

import com.rokidlab.phone.util.AppConfig
import com.rokidlab.phone.util.LogCollector
import com.rokidlab.phone.R
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
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class AdbShellClient(
    /**
     * 应用 Context。**生产路径必传**；仅 JVM 单测驱动纯流协议（sync 帧编解码 / `pullFile` 分支）
     * 时允许为 null —— 这条路径完全不触 Context，为它引入 Context mock 得不偿失。
     * 所有使用处一律经 [requireContext] 取值，缺失时立刻显式报错，不会静默退化成 NPE。
     */
    private val context: Context?,
    private val ipAddress: String,
    private val port: Int = 5555,
) {
    /**
     * 仅供单测：注入内存流直接驱动 ADB 流协议（不建链、不握手，`socket` 保持 null）。
     *
     * 存在的意义：`pullFile` 的 FAIL 分支（远端无权限/文件不存在）历史上曾静默产出 0 字节文件
     * 并被当成成功，只能靠真机复现。有了这个注入口，该分支可以在 JVM 里用脚本化对端锁死。
     */
    internal fun attachStreamsForTest(input: InputStream, output: OutputStream) {
        inputStream = input
        outputStream = output
    }

    private fun requireContext(): Context =
        context ?: error("AdbShellClient 该路径需要 Context（单测构造为 null 时不可调用）")

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

        /** 分片拉取的单片字节数：实测这条蓝牙隧道上 adbd 一次只稳定送出 1 个 64KB 分片。 */
        private const val SHARD_BYTES = 64 * 1024

        /** 单片失败后的重试次数（对端偶发提前收流时，重开一次 sync 流往往就能拿到）。 */
        private const val SHARD_MAX_ATTEMPTS = 3

        /** 分片临时文件所在目录（与提取临时文件同盘，避免跨挂载点）。 */
        private const val SHARD_DIR = "/sdcard/Download"

        /** `split` 收尾哨兵：用于区分「命令跑完了」与「命令没跑成但也没报错」。 */
        private const val SPLIT_MARK = "__SPLIT_DONE__"

        /** sync 数据阶段读超时：整文件直传时对端可能攒够一批才吐一次，比命令期 3s 宽松。 */
        private const val SYNC_READ_TIMEOUT_MS = 30_000

        /** 单个 sync DATA 消息的负载上限（adbd 侧 SYNC_DATA_MAX = 64KB，留一档冗余用于校验畸形值）。 */
        private const val MAX_SYNC_PAYLOAD = 1024 * 1024
    }
    private var socket: Socket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    private var keyPair: KeyPair? = null
    // incrementing localId, increments per open, reused on close
    private var nextLocalId = 0
    private val lock = ReentrantLock()

    /**
     * 正在执行的命令/传输计数（**心跳不计入**）。
     *
     * 用途：线路升级（蓝牙隧道 → WiFi 直连）要在后台拆掉本会话重建，若此刻正有一条
     * 命令或一次文件传输在本会话上跑着，强制 `disconnect()` 会把传输拦腰截断。
     * 升级方据此先判断 [isBusy]，忙则本次不升级、留到下一次取用。
     */
    private val busyOps = AtomicInteger(0)

    /** 是否有命令/传输正在执行（心跳不算）。 */
    fun isBusy(): Boolean = busyOps.get() > 0

    /** 计一次「忙」并保证退出时归还（异常/非局部返回都不会漏掉 decrement）。 */
    private inline fun <T> withBusy(block: () -> T): T {
        busyOps.incrementAndGet()
        try {
            return block()
        } finally {
            busyOps.decrementAndGet()
        }
    }

    /** tracks whether ADB session is alive beyond TCP level */
    @Volatile
    private var adbSessionAlive = false

    // ── 心跳保活（防止国产手机后台 Socket 超时断开） ──
    @Volatile
    private var heartbeatRunning = false
    private var heartbeatThread: Thread? = null

    private class HeartbeatCommand(private val client: AdbShellClient) : Runnable {
        override fun run() {
            while (client.heartbeatRunning && client.adbSessionAlive) {
                try {
                    Thread.sleep(AppConfig.ADB_HEARTBEAT_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    break
                }
                if (!client.heartbeatRunning || !client.adbSessionAlive) break
                // 通过 executeShellCommand 执行心跳，自动使用 lock 避免与 shell 命令竞态。
                // 心跳不计入 busyOps：它是周期性空转，若计入会让「会话是否正忙」长期为真，
                // 线路升级永远等不到空闲窗口。
                client.executeShellCommandInternal("echo 1", AppConfig.ADB_HEARTBEAT_TIMEOUT_MS.toInt(), countBusy = false)
            }
        }
    }

    private fun startHeartbeat() {
        if (heartbeatRunning) return
        heartbeatRunning = true
        heartbeatThread = Thread(HeartbeatCommand(this), "adb-heartbeat").also { it.isDaemon = true; it.start() }
        Log.d(TAG, "Heartbeat started (interval=${AppConfig.ADB_HEARTBEAT_INTERVAL_MS}ms)")
    }

    private fun stopHeartbeat() {
        heartbeatRunning = false
        heartbeatThread?.interrupt()
        heartbeatThread = null
    }

    private data class AdbMessage(val command: Int, val arg0: Int, val arg1: Int, val payload: ByteArray)

    fun connect(): Boolean = try {
        Log.i(TAG, "Connecting to $ipAddress:$port...")
        socket = Socket()
        socket?.tcpNoDelay = true
        // 握手期使用独立超时：蓝牙隧道场景 CNXN 写入本地 5556 后，数据要等 RFCOMM
        // 建连（兜底 8s）才被转发到眼镜 adbd，命令期 3s 超时会在此期间误报 Read timed out。
        socket?.soTimeout = AppConfig.ADB_HANDSHAKE_TIMEOUT_MS
        socket?.connect(java.net.InetSocketAddress(ipAddress, port), AppConfig.ADB_CONNECT_TIMEOUT_MS)
        Log.i(TAG, "TCP connection established")
        inputStream = socket?.getInputStream()
        outputStream = socket?.getOutputStream()
        keyPair = AdbKeyManager.getOrCreateKeyPair(requireContext().filesDir.absolutePath)
        nextLocalId = 0
        doHandshake()
        // 握手完成：恢复命令期读超时（单包级，避免后续命令卡死时拖长等待）
        socket?.soTimeout = AppConfig.ADB_SOCKET_TIMEOUT_MS
        adbSessionAlive = true
        startHeartbeat()
        Log.i(TAG, "ADB connection successful")
        LogCollector.i(TAG, "ADB connection successful to $ipAddress:$port")
        true
    } catch (e: Exception) {
        Log.e(TAG, "Connection failed: ${e.message}", e)
        LogCollector.e(TAG, "ADB connection failed to $ipAddress:$port", e)
        disconnect()
        false
    }

    // -- Auth handshake --
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
                            val sig = AdbKeyManager.getSignature()
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
        LogCollector.e(TAG, "ADB authentication failed after $authAttempts attempts")
        throw RuntimeException("ADB authentication failed ($authAttempts attempts)")
    }

    // -- Public key encoding --
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
        stopHeartbeat()
        adbSessionAlive = false
        try { inputStream?.close() } catch (_: Exception) {}
        try { outputStream?.close() } catch (_: Exception) {}
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        inputStream = null
        outputStream = null
    }

    fun isConnected(): Boolean = adbSessionAlive && socket?.isConnected == true && socket?.isClosed == false

    // -- Public API --

    fun executeShellCommand(command: String, timeoutMs: Int = 15000): String =
        executeShellCommandInternal(command, timeoutMs, countBusy = true)

    /**
     * @param countBusy 是否计入 [busyOps]；仅心跳传 false（见 [HeartbeatCommand]）。
     */
    private fun executeShellCommandInternal(command: String, timeoutMs: Int, countBusy: Boolean): String {
        if (!countBusy) {
            return lock.withLock { executeShellCommandLocked(command, timeoutMs) }
        }
        return withBusy { lock.withLock { executeShellCommandLocked(command, timeoutMs) } }
    }

    private fun executeShellCommandLocked(command: String, timeoutMs: Int): String {
        Log.i(TAG, "execute start cmd=[$command] timeout=$timeoutMs")
        return try {
            drainStaleMessages()
            val result = openAndExchange("shell:$command", timeoutMs)
            Log.i(TAG, "execute end cmd=[$command] result_len=${result.length}")
            result
        } catch (e: Exception) {
            Log.w(TAG, "executeShellCommand failed, marking session dead: ${e.message}")
            LogCollector.e(TAG, "Shell command failed: [${command.take(100)}]", e)
            adbSessionAlive = false
            ""
        }
    }

    // 包名缓存，避免每次打开设置都重新查询
    private var cachedPackages: List<String>? = null
    private var cachedPackagesTime: Long = 0L
    private val packageCacheTtlMs = 60_000L // 缓存 60 秒

    fun listPackages(includeSystem: Boolean = false): List<String> {
        // 使用缓存加速
        val now = System.currentTimeMillis()
        val cached = cachedPackages
        if (!includeSystem && cached != null && (now - cachedPackagesTime) < packageCacheTtlMs) {
            Log.i(TAG, "listPackages: returning cached ${cached.size} packages")
            return cached
        }

        val cmd = if (includeSystem) "pm list packages" else "pm list packages -3"
        val result = executeShellCommand(cmd)
        val lines = result.lines()
            .filter { it.startsWith("package:") }
            .map { it.removePrefix("package:").trim() }
            .filter { it.isNotBlank() }
            .sorted()
        Log.i(TAG, "listPackages(includeSystem=$includeSystem) lines=${lines.size}")

        if (!includeSystem) {
            cachedPackages = lines
            cachedPackagesTime = now
        }
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
        // monkey is deprecated on Android 12+ but still works across all versions
        val result = executeShellCommand("monkey -p $packageName 1 2>&1")
        return if (result.contains("Error") || result.contains("Exception") || result.contains("crash")) "Failed: $packageName" else "Launched $packageName"
    }

    fun uninstallApp(packageName: String): String {
        // Android 12+ system apps need --user 0
        val result = executeShellCommand("pm uninstall --user 0 $packageName 2>&1")
        if (result.contains("Exception") || result.contains("Error") || result.contains("Failure")) {
            val fallback = executeShellCommand("pm uninstall $packageName 2>&1")
            return if (fallback.contains("Exception") || fallback.contains("Error") || fallback.contains("Failure")) "Uninstall failed: $packageName" else "Uninstalled $packageName"
        }
        return "Uninstalled $packageName"
    }

    fun disableApp(packageName: String): String {
        // try pm disable-user first (compatible with Android 12+ / glasses), fallback to pm disable
        val cmds = listOf(
            "pm disable-user --user 0 $packageName 2>&1",
            "pm disable $packageName 2>&1",
        )
        for (cmd in cmds) {
            val result = executeShellCommand(cmd)
            Log.i(TAG, "disableApp cmd=[$cmd] result_len=${result.length} result=${result.take(200)}")
            if (!result.contains("Exception") && !result.contains("Error") && !result.contains("Killed")) {
                return "Disabled $packageName"
            }
        }
        return "Error: Cannot disable this app"
    }

    fun enableApp(packageName: String): String {
        val result = executeShellCommand("pm enable --user 0 $packageName 2>&1")
        Log.i(TAG, "enableApp cmd=[pm enable --user 0 $packageName] result_len=${result.length} result=${result.take(200)}")
        if (result.contains("Exception") || result.contains("Error")) {
            val fallback = executeShellCommand("pm enable $packageName 2>&1")
            if (fallback.contains("Exception") || fallback.contains("Error")) return "Error: Cannot enable this app"
            return "Enabled $packageName"
        }
        return "Enabled $packageName"
    }

    fun getApkPath(packageName: String): String {
        val result = executeShellCommand("pm path $packageName")
        return result.lines().firstOrNull { it.startsWith("package:") }?.removePrefix("package:")?.trim() ?: ""
    }

    /**
     * 远端文件字节数；`stat` 取不到（不存在 / 无权限 / 输出非法）返回 -1。
     *
     * 存在的意义：sync 流的 `DONE` 不足以证明拉全了 —— 蓝牙隧道下 adbd 会在送出 1~2 个
     * 64KB 分片后直接把流收掉，此时既没有 `DONE` 也没有 `FAIL`。只有拿远端 `stat` 对账
     * 才能发现「只落了前 64KB」的残包。
     */
    fun getRemoteFileSize(remotePath: String): Long {
        val out = executeShellCommand("stat -c %s $remotePath 2>/dev/null")
        return out.lines().firstOrNull { it.isNotBlank() }?.trim()?.toLongOrNull() ?: -1L
    }

    fun extractApkToDownloads(packageName: String): String {
        val path = getApkPath(packageName)
        if (path.isEmpty()) return requireContext().getString(R.string.extract_path_not_found, packageName)
        // copy to device Download directory (remote glasses)
        val remoteDest = "/sdcard/Download/${packageName}.apk"
        val cpResult = executeShellCommand("cp $path $remoteDest 2>&1 && echo OK")
        if (!cpResult.trim().endsWith("OK")) return requireContext().getString(R.string.extract_copy_failed)
        // pull from glasses to phone app-private directory (兼容鸿蒙作用域存储，无需存储权限)
        val localDir = File(requireContext().filesDir, "Download")
        if (!localDir.exists()) localDir.mkdirs()
        val localFile = File(localDir, "${packageName}.apk")
        val pullOk = pullWholeFile(remoteDest, localFile.absolutePath, getRemoteFileSize(remoteDest))
        return if (pullOk) requireContext().getString(R.string.extract_downloaded, packageName) else requireContext().getString(R.string.extract_pull_failed)
    }

    /**
     * 将指定应用的 APK 从眼镜拉取到手机缓存目录。
     * 仅负责"取出到手机"，落盘到哪个目录由 UI 层（用户通过 SAF 选择的目录）决定。
     * @param onProgress 进度回调（已拉字节数, 总字节数），在调用线程上回调
     * @return 缓存文件（成功且非空），失败返回 null
     */
    fun pullApkToCache(packageName: String, onProgress: ((Long, Long) -> Unit)? = null): File? = withBusy {
        val path = getApkPath(packageName)
        if (path.isEmpty()) return@withBusy null
        // 先把远端大小拿到手：拉取后要逐字节对账，否则半截包会被当成成功
        val remoteSize = getRemoteFileSize(path)
        if (remoteSize < 0) return@withBusy null
        val remoteTmp = "/sdcard/Download/_extract_${packageName}_${System.currentTimeMillis()}.apk"
        val cpResult = executeShellCommand("cp $path $remoteTmp 2>&1 && echo OK")
        if (!cpResult.trim().endsWith("OK")) return@withBusy null
        val cacheFile = File(requireContext().cacheDir, "extract_${packageName}.apk")
        if (cacheFile.exists()) cacheFile.delete()
        val pullOk = pullWholeFile(remoteTmp, cacheFile.absolutePath, remoteSize, onProgress)
        executeShellCommand("rm -f $remoteTmp")
        if (pullOk && cacheFile.exists() && cacheFile.length() > 0) cacheFile else null
    }

    fun sendNotification(title: String, content: String): String {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        val logLine = "[$timestamp] $title: $content"
        
        // method 1: post notification on device
        executeShellCommand("cmd notification set_dnd off 2>/dev/null")
        val escapedContent = escapeShell(content)
        val escapedTitle = escapeShell(title)
        val result = executeShellCommand("cmd notification post -t $escapedTitle timer_msg $escapedContent 2>&1")
        Log.i(TAG, "sendNotification cmd_notification result_len=${result.length}")
        
        // method 2: show toast via intent
        executeShellCommand("am start -a android.intent.action.MAIN --es toast_text ${escapeShell(content)} 2>/dev/null")
        
        // method 3: write to file (most reliable)
        executeShellCommand("echo ${escapeShell(logLine)} >> /sdcard/Download/timer_messages.txt 2>/dev/null")
        
        return "Notification sent"
    }

    fun getSystemProperties(): String {
        return executeShellCommand("getprop")
    }

    fun getBatteryInfo(): String {
        return executeShellCommand("dumpsys battery")
    }

    fun getDeviceInfo(): String {
        // 5 条 getprop 合并为单条 shell 命令：避免串行 5 次 RTT（默认 15s 超时，最坏 75s 阻塞 AI 对话）
        val props = listOf(
            "ro.product.model",
            "ro.product.manufacturer",
            "ro.build.version.release",
            "ro.build.version.sdk",
            "ro.serialno",
        )
        val raw = executeShellCommand(props.joinToString("; ") { "getprop $it" })
        val values = raw.lines()
        // 保持原输出格式（"getprop xxx: value"），便于 AI 识别字段
        return props.mapIndexed { i, p -> "getprop $p: ${values.getOrNull(i)?.trim()}" }.joinToString("\n")
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

    /**
     * 经 adb "tcp:<port>" 服务直连眼镜本机 TCP 端口并发送完整字节流，随后读回应答文本。
     * 用途：把 .aix 推到 RokidLink 的 AiuiPackageServer（7658），返回其 "OK" 应答。
     * 写阶段采用 WRTE + 等 OKAY 的流控节奏（adbd 会向远端 socket 写完后回 OKAY）；
     * 读阶段镜像 readAllWrites（收到 CLSE 即结束）。
     * @return 眼镜端回读文本（"OK" 表示成功）；空串表示失败/无应答
     */
    fun sendTcpStream(port: Int, payload: ByteArray, timeoutMs: Int = 60_000): String {
        if (!adbSessionAlive) return ""
        return withBusy {
            lock.withLock {
            try {
                val result = open("tcp:$port") ?: run {
                    Log.w(TAG, "sendTcpStream open tcp:$port failed")
                    drainStaleMessages()
                    return@withLock ""
                }
                val (localId, remoteId, _) = result
                // ── 写阶段：分片 WRTE，每片等 OKAY 流控（防远端 socket 缓冲满丢包） ──
                var off = 0
                while (off < payload.size) {
                    val end = minOf(off + MAX_PAYLOAD, payload.size)
                    val chunk = payload.copyOfRange(off, end)
                    writeMessage(CMD_WRTE, localId, remoteId, chunk)
                    // OKAY(arg0=localId, arg1=remoteId) 表示 adbd 已消费该 WRTE
                    val ok = readMessage()
                    if (ok.command != CMD_OKAY) {
                        Log.w(TAG, "sendTcpStream write no OKAY: cmd=0x${ok.command.toString(16)}")
                        break
                    }
                    off = end
                }
                // ── 读阶段：收取眼镜端应答，直到 CLSE ──
                val baos = ByteArrayOutputStream()
                socket?.soTimeout = timeoutMs
                val deadline = System.currentTimeMillis() + timeoutMs
                while (System.currentTimeMillis() < deadline) {
                    val msg = try { readMessage() } catch (_: Exception) { break }
                    when {
                        msg.command == CMD_WRTE && msg.arg0 == remoteId -> {
                            baos.write(msg.payload)
                            writeMessage(CMD_OKAY, localId, remoteId, ByteArray(0))
                            socket?.soTimeout = (deadline - System.currentTimeMillis()).toInt().coerceAtLeast(2000)
                        }
                        msg.command == CMD_CLSE && msg.arg0 == remoteId -> break
                    }
                }
                close(localId, remoteId)
                baos.toString(Charsets.UTF_8)
            } catch (e: Exception) {
                Log.e(TAG, "sendTcpStream fail: ${e.message}", e)
                ""
            }
            }
        }
    }

    // ──────────────────────────────────────────────────
    // -- ADB stream protocol: open/close localId must be paired --────────────────────────────────────────────────

    /**
     * 提取用的一站式拉取：先按 sync 协议整文件直传，被对端提前收流时退到分片重试。
     *
     * @return true 表示 [localPath] 已是逐字节完整的副本
     */
    private fun pullWholeFile(
        remotePath: String,
        localPath: String,
        expectedSize: Long,
        onProgress: ((Long, Long) -> Unit)? = null,
    ): Boolean {
        if (expectedSize <= 0) {
            LogCollector.w(TAG, "拉取缺少远端大小，无法对账: $remotePath")
            return false
        }
        if (pullFile(remotePath, localPath, expectedSize, onProgress)) return true
        // 整文件一次 RECV 被对端提前收掉（历史 C7 事故形态）时，改按 64KB 分片逐片对账重试
        LogCollector.w(TAG, "整文件拉取未通过，退化为分片重试: $remotePath")
        onProgress?.invoke(0L, expectedSize)
        return pullFileSharded(remotePath, localPath, expectedSize)
    }

    /**
     * 通过 ADB sync RECV 协议从远程设备拉取整个文件到本地 —— 与 `adb pull` / 文件管理器
     * （`AdbFileManagerClient.downloadFile`）同一条通路，是目前最快的取文件方式。
     *
     * **为什么不能用 shell + base64 代替**：2026-09-13 同一台眼镜、同一个 8,753,886 字节的 APK 实测，
     * `adb pull`（sync 二进制直传）0.30s 完成、约 34MB/s；`cat <file> | base64` 走 shell 文本通道
     * 要 38s，且送出 6,091,168 字节后被截断。文本通道既有 33% 的 base64 膨胀，又要为每一块付一次
     * shell 往返与 adbd 侧 `cat`/`base64` 的进程开销 —— 提取一律走本方法。
     *
     * **完整性校验是必须的，不是加固**：蓝牙隧道下 adbd 的 sync 流可能不会把大文件送完 ——
     * 2026-09-13 实测 8,753,886 字节的 APK 只落盘 65536 / 131072 字节（恒为 64KB 整数倍，
     * 且是源文件的前缀，md5 对得上），过程既没有 `FAIL` 也没有 `DONE`。旧实现把「收到 CLSE」
     * 和「非预期帧」都当成正常结束返回 true，于是残包被一路当成成功，UI 还提示「已保存」。
     *
     * @param expectedSize 远端文件大小（[getRemoteFileSize] 取得）。<0 表示未知，此时只要求收到 `DONE`；
     *  ≥0 时以「收到字节数 == expectedSize」为唯一收尾凭证（对端可能省掉 `DONE`，见 [pullFileSharded]）。
     * @param onProgress 进度回调（已拉字节数, 总字节数），在调用线程上回调
     */
    fun pullFile(
        remotePath: String,
        localPath: String,
        expectedSize: Long = -1L,
        onProgress: ((Long, Long) -> Unit)? = null,
    ): Boolean = withBusy { pullFileInternal(remotePath, localPath, expectedSize, onProgress) }

    private fun pullFileInternal(
        remotePath: String,
        localPath: String,
        expectedSize: Long,
        onProgress: ((Long, Long) -> Unit)?,
    ): Boolean {
        lock.withLock {
            var closeLocalId = 0
            var closeRemoteId = 0
            val localFile = File(localPath)
            try {
                // 必须经 open() 开流：它按 arg1 过滤陌生流帧（上一条流迟到的 CLSE/OKAY）。
                // 旧实现直接 readMessage() 取第一帧，于是「OPEN sync: 读到上一条流的 CLSE」被误判成
                // sync 打不开 —— 这正是提取功能曾弃用 sync、改走 274 次 shell 分块（极慢）的根因。
                val opened = open("sync:\u0000") ?: run {
                    // 走 LogCollector 的 E 级：提取失败是必须能在 App 内日志面板里看到的链路故障
                    LogCollector.e(TAG, "sync 流打开失败，提取无法开始: $remotePath")
                    return false
                }
                val (localId, remoteId, _) = opened
                closeLocalId = localId
                closeRemoteId = remoteId

                val pathBytes = remotePath.toByteArray(Charsets.UTF_8)
                val recvBuf = ByteBuffer.allocate(8 + pathBytes.size)
                recvBuf.order(ByteOrder.LITTLE_ENDIAN)
                recvBuf.put("RECV".toByteArray(Charsets.UTF_8))
                recvBuf.putInt(pathBytes.size)
                recvBuf.put(pathBytes)
                writeMessage(CMD_WRTE, localId, remoteId, recvBuf.array())

                localFile.parentFile?.mkdirs()
                var remoteFailed = false
                var failReason = ""
                var protocolError = ""
                var sawDone = false
                var received = 0L
                FileOutputStream(localFile).use { fos ->
                    socket?.soTimeout = SYNC_READ_TIMEOUT_MS
                    // sync 消息重组缓冲：**ADB 流是字节流，WRTE 只是传输层分片，不等于 sync 消息边界**。
                    // 2026-09-13 真机坐实：adbd 的 sync 服务把 DATA 的 8 字节包头与文件负载分两次 write，
                    // 传输层各成一个 WRTE（大负载还会再被拆）。旧实现「一个 WRTE = 一条 sync 消息」，
                    // 于是把裸文件字节当成包头，报 `非预期 sync 帧：PK`（PK 就是 APK 的 ZIP 魔数）、
                    // 或把只剩 8 字节包头的帧当整条消息收下而丢掉全部负载（对端等不到后续 ack，
                    // 表现为 30s 读超时）。正确做法：每个 WRTE 立即回传输层 OKAY，把负载追加进缓冲，
                    // 再从缓冲里按「4 字节 id + 4 字节长度」切出完整 sync 消息。
                    var acc = ByteArray(MAX_SYNC_PAYLOAD)
                    var accLen = 0
                    sync@ while (true) {
                        val msg = readMessage()
                        // 同 open()：只认回给本条流的帧，陌生流帧回 CLSE 收干净，否则会打乱帧对齐
                        if (msg.arg1 != localId) {
                            runCatching { writeMessage(CMD_CLSE, msg.arg1, msg.arg0, ByteArray(0)) }
                            continue
                        }
                        if (msg.command == CMD_CLSE) {
                            // 对端没发 DONE/FAIL 就收流：可能是「大文件没送完」，也可能是「分片已送完
                            // 但省掉了 DONE」（分片拉取下这是常态）。先不下结论，最终由字节数对账裁决。
                            break
                        }
                        if (msg.command != CMD_WRTE) continue
                        // 传输层流控必须先 ack：与 sync 消息是否完整无关，且必须在落盘前回
                        // （蓝牙隧道单程数百毫秒，落盘再回会把磁盘延迟叠加进流控往返）。
                        writeMessage(CMD_OKAY, localId, remoteId, ByteArray(0))
                        if (accLen + msg.payload.size > acc.size) {
                            acc = acc.copyOf(maxOf(acc.size * 2, accLen + msg.payload.size))
                        }
                        System.arraycopy(msg.payload, 0, acc, accLen, msg.payload.size)
                        accLen += msg.payload.size
                        // 从缓冲里尽量切出完整的 sync 消息（不足则退出内层循环等下一片）
                        var pos = 0
                        while (true) {
                            if (accLen - pos < 8) break
                            val syncId = String(acc, pos, 4, Charsets.UTF_8)
                            val len = ByteBuffer.wrap(acc, pos + 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt()
                            when (syncId) {
                                "DATA" -> {
                                    if (len < 0 || len > MAX_SYNC_PAYLOAD) {
                                        protocolError = "DATA 长度非法：$len"
                                        break@sync
                                    }
                                    if (accLen - pos - 8 < len) break // 负载未到齐，等下一片
                                    if (len > 0) {
                                        fos.write(acc, pos + 8, len)
                                        received += len
                                        if (expectedSize > 0) onProgress?.invoke(received, expectedSize)
                                    }
                                    pos += 8 + len
                                }
                                "DONE" -> {
                                    sawDone = true
                                    break@sync
                                }
                                "FAIL" -> {
                                    // 远端文件不存在/无权限：adbd 回 FAIL，原逻辑静默当成功 -> 产出 0 字节文件(A4)
                                    if (accLen - pos - 8 < len) break // 报错文案未到齐，等下一片
                                    failReason = if (len > 0) String(acc, pos + 8, len, Charsets.UTF_8) else ""
                                    Log.w(TAG, "pullFile FAIL from device: $failReason")
                                    remoteFailed = true
                                    break@sync
                                }
                                else -> {
                                    protocolError = "非预期 sync 帧：$syncId"
                                    break@sync
                                }
                            }
                        }
                        // 压缩已消费部分，保留半条消息等待续传
                        if (pos > 0) {
                            System.arraycopy(acc, pos, acc, 0, accLen - pos)
                            accLen -= pos
                        }
                    }
                }

                // 无论成功失败都清空 socket 残留：CLSE 收流后对端可能还欠几个字节（实测会污染
                // 下一个分片的 sync 流，读到"非预期 sync 帧"），不清理会把残留喂给下一片。
                close(localId, remoteId, drain = true)
                socket?.soTimeout = 5000
                val reason = when {
                    remoteFailed -> failReason.ifEmpty { "远端 FAIL" }
                    protocolError.isNotEmpty() -> protocolError
                    // 远端大小已知时，字节数是唯一可信的收尾凭证：对端可能省掉 DONE（分片拉取常态），
                    // 也可能发了 DONE 却提前放弃（A4/C7 事故形态）。协议层异常与 FAIL 已在上面拦下。
                    expectedSize >= 0 ->
                        if (localFile.length() != expectedSize) "字节数不符：收到 ${localFile.length()} / 远端 $expectedSize" else ""
                    !sawDone -> "sync 流被对端提前收掉（未收到 DONE）"
                    else -> ""
                }
                if (reason.isNotEmpty()) {
                    // 删除半成品：下游只认返回值，但残留文件会被「文件存在即成功」的调用点误判。
                    runCatching { localFile.delete() }
                    Log.w(TAG, "pullFile failed: $reason ($remotePath)")
                    LogCollector.w(TAG, "拉取文件失败（$reason）: $remotePath")
                    return false
                }
                Log.i(TAG, "pullFile success: $remotePath → $localPath ($received bytes)")
                LogCollector.i(TAG, "pullFile success: $remotePath → $localPath ($received bytes)")
                return true
            } catch (e: Exception) {
                Log.e(TAG, "pullFile failed: ${e.message}", e)
                LogCollector.e(TAG, "pullFile failed: $remotePath", e)
                // 读流中途抛异常（如对端静默 30s 超时）同样会留下半成品，必须一并删除
                runCatching { localFile.delete() }
                // cleanup sync stream on exception to prevent leaks
                if (closeLocalId > 0 && closeRemoteId > 0) {
                    try { close(closeLocalId, closeRemoteId) } catch (_: Exception) {}
                }
                socket?.soTimeout = 5000
                return false
            }
        }
    }

    /**
     * 分片拉取：把大文件在眼镜侧切成 ≤[SHARD_BYTES] 的分片，逐片 sync RECV 到本机再按序拼接。
     *
     * 提取（[pullWholeFile]）的第一选择是 [pullFile] 整文件直传；只有整文件被对端提前收流时
     * 才退到本方法，用「每片 64KB」把单次传输压到对端能送完的窗口内。
     *
     * 为什么不能只靠整文件一次 RECV：2026-09-13 实测，这条蓝牙隧道上 adbd 的 sync 流在送出
     * 1~2 个 64KB 分片后就既不送数据也不发 `DONE`/`FAIL`，整文件拉取的结果必然是 64KB 整数倍
     * 的前缀截断（8,753,886 字节的 APK 只落 65536 / 131072）。每片再按「收到字节数 == 该片
     * 应有字节数」对账，拿不全就重试。
     *
     * @param expectedSize 远端文件大小，必须已知（分片对账依赖它）。
     * @return true 表示 [localPath] 已是逐字节完整的副本；任何一片拿不全都会删除半成品并返回 false。
     */
    fun pullFileSharded(remotePath: String, localPath: String, expectedSize: Long): Boolean =
        withBusy { pullFileShardedInternal(remotePath, localPath, expectedSize) }

    private fun pullFileShardedInternal(remotePath: String, localPath: String, expectedSize: Long): Boolean {
        if (expectedSize < 0) {
            LogCollector.w(TAG, "分片拉取缺少远端大小，无法逐片对账: $remotePath")
            return false
        }
        // 单片装得下：直接走普通 RECV，省掉一次 split 往返（蓝牙隧道下单程就要数百毫秒）
        if (expectedSize <= SHARD_BYTES) return pullFile(remotePath, localPath, expectedSize)

        return lock.withLock {
            val target = File(localPath)
            target.parentFile?.mkdirs()
            val prefix = "$SHARD_DIR/_shard_${System.currentTimeMillis()}_"
            val shardLocal = File(target.parentFile, ".${target.name}.part")
            var success = false
            try {
                val splitOut = executeShellCommand("split -b $SHARD_BYTES $remotePath $prefix 2>&1; echo $SPLIT_MARK")
                if (!splitOut.trim().endsWith(SPLIT_MARK)) {
                    LogCollector.w(TAG, "分片失败（split 未跑完）: $remotePath")
                    return@withLock false
                }
                val shards = executeShellCommand("ls -1 ${prefix}*")
                    .lines()
                    .map { it.trim() }
                    .filter { it.startsWith(prefix) }
                    .sorted()
                if (shards.isEmpty()) {
                    LogCollector.w(TAG, "分片结果为空: $remotePath")
                    return@withLock false
                }
                LogCollector.i(TAG, "分片拉取开始: $remotePath（$expectedSize 字节 / ${shards.size} 片）")
                FileOutputStream(target).use { out ->
                    var remaining = expectedSize
                    var idx = 0
                    while (remaining > 0 && idx < shards.size) {
                        val want = minOf(SHARD_BYTES.toLong(), remaining)
                        if (!pullShardWithRetry(shards[idx], shardLocal, want)) {
                            LogCollector.w(TAG, "分片拉取失败: ${shards[idx]}（第 ${idx + 1}/${shards.size} 片）")
                            return@withLock false
                        }
                        out.write(shardLocal.readBytes())
                        remaining -= want
                        idx++
                    }
                    if (remaining != 0L) {
                        LogCollector.w(TAG, "分片不足以覆盖整个文件，仍缺 $remaining 字节: $remotePath")
                        return@withLock false
                    }
                }
                success = true
                LogCollector.i(TAG, "分片拉取成功: $remotePath → $localPath（$expectedSize 字节 / ${shards.size} 片）")
                true
            } catch (e: Exception) {
                Log.e(TAG, "pullFileSharded failed: ${e.message}", e)
                LogCollector.e(TAG, "分片拉取异常: $remotePath", e)
                false
            } finally {
                if (!success) runCatching { target.delete() }
                runCatching { shardLocal.delete() }
                executeShellCommand("rm -f ${prefix}*")
            }
        }
    }

    /** 单片拉取，失败自动重开 sync 流重试（对端偶发提前收流时，重开一次往往就能拿全）。 */
    private fun pullShardWithRetry(remoteShard: String, localShard: File, want: Long): Boolean {
        for (attempt in 1..SHARD_MAX_ATTEMPTS) {
            if (pullFile(remoteShard, localShard.absolutePath, want)) return true
            Log.w(TAG, "分片第 $attempt/$SHARD_MAX_ATTEMPTS 次失败: $remoteShard（want=$want）")
            if (attempt < SHARD_MAX_ATTEMPTS) {
                // 蓝牙隧道下复用同一 socket 重试，adbd 侧可能仍停留在上一流的尾巴状态
                // （实测表现为 30s 读超时），断开重连才能拿到干净流
                runCatching { disconnect() }
                if (!connect()) return false
            }
        }
        return false
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
        val deadline = System.currentTimeMillis() + AppConfig.ADB_SOCKET_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val resp = try {
                readMessage()
            } catch (e: Exception) {
                Log.w(TAG, "open($service) 等应答失败: ${e.message}")
                return null
            }
            // ADB 协议里 arg1 恒为「接收方自己的 localId」：只认回给本条流的帧。
            // 不校验 arg1 是这条链路上最贵的 bug —— 上一条流迟到的 CLSE 会被当成
            // 「本次 open 失败」，于是每次命令都要先白等一个 30s 读超时再重试。
            if (resp.arg1 == localId) {
                if (resp.command == CMD_OKAY) {
                    // OKAY: arg0=remoteId assigned by server, arg1=our localId(confirmation)
                    return Triple(localId, resp.arg0, resp)
                }
                Log.w(TAG, "open($service) failed: cmd=0x${resp.command.toString(16)}")
                return null
            }
            // 陌生流帧（多为上一条流迟到的 OKAY/CLSE）：回 CLSE 把它收干净，否则它会
            // 一直留在缓冲里把后续帧的对齐打乱。同 AdbFileManagerClient.listFiles 的处理。
            Log.w(TAG, "open($service): 丢弃陌生流帧 cmd=0x${resp.command.toString(16)} arg0=${resp.arg0} arg1=${resp.arg1}")
            runCatching { writeMessage(CMD_CLSE, resp.arg1, resp.arg0, ByteArray(0)) }
        }
        Log.w(TAG, "open($service) 等应答超时")
        return null
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
                // 只认回给本条流的帧（arg1 == localId）。陌生流帧若不回 CLSE 收掉，
                // 会残留在缓冲里把下一条流的第一帧对齐打乱 —— 见 open() 注释。
                msg.arg1 != localId -> {
                    runCatching { writeMessage(CMD_CLSE, msg.arg1, msg.arg0, ByteArray(0)) }
                }
                // WRTE(arg0=remoteId, arg1=localId, data) -- server sends data
                msg.command == CMD_WRTE && msg.arg0 == remoteId -> {
                    baos.write(msg.payload)
                    // OKAY(localId, remoteId) confirm receipt
                    writeMessage(CMD_OKAY, localId, remoteId, ByteArray(0))
                    socket?.soTimeout = (deadline - System.currentTimeMillis()).toInt().coerceAtLeast(2000)
                }
                // CLSE(arg0=remoteId) -- server closes stream
                msg.command == CMD_CLSE && msg.arg0 == remoteId -> break
            }
        }
        return baos.toString(Charsets.UTF_8)
    }

    /**
     * Close stream using localId from open().
     * Send CLSE(localId, remoteId) and drain remaining messages.
     * NOTE: Server may or may not respond with its own CLSE (depends on who initiated close).
     * Do NOT wait for a specific CLSE - just drain briefly to avoid 3-second timeout.
     *
     * @param drain 对端若已主动发过 CLSE（分片拉取的常态），它不会再回应我们的 CLSE，
     *  此时排空只会白等读超时。传 false 跳过排空 —— 分片拉取会调用上百次，每次省 500ms。
     */
    private fun close(localId: Int, remoteId: Int, drain: Boolean = true) {
        writeMessage(CMD_CLSE, localId, remoteId, ByteArray(0))
        // Drain remaining messages briefly. If readAllWrites already consumed the server's
        // CLSE, server won't send another one. If close is initiated by us, server may send
        // CLSE back. Either way, 500ms drain is sufficient.
        if (drain) drainStaleMessages()
    }

    /** Drain stale messages from socket buffer */
    private fun drainStaleMessages() {
        try {
            socket?.soTimeout = 500
            while (true) {
                // 非阻塞探测：无残留数据立即返回，避免分片拉取下每片都空等 500ms
                if ((inputStream?.available() ?: 0) <= 0) break
                readMessage()
            }
        } catch (_: Exception) { }
        // 恢复命令期读超时：若残留 500ms，下次 open() 等 OKAY 延迟稍长即被误判超时
        socket?.soTimeout = AppConfig.ADB_SOCKET_TIMEOUT_MS
    }

    // -- Message read/write --

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
        if (payloadLength < 0 || payloadLength > com.rokidlab.phone.util.AppConfig.ADB_MAX_PAYLOAD) {
            throw java.io.IOException("bad payload length: $payloadLength")
        }
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

    private fun escapeShell(text: String): String {
        return text.replace("'", "'\\''").let { "'$it'" }
    }
}
