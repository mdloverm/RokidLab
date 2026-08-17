package com.rokidlab.phone.adb

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
import com.rokidlab.phone.util.AppConfig
import com.rokidlab.phone.R
import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPair

class AdbScreenMirrorClient(
    private val context: Context,
    private val ipAddress: String,
    private val port: Int = 5555,
) {
    private var socket: Socket? = null
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    private var keyPair: KeyPair? = null
    private var isRunning = false
    private var localId = java.util.concurrent.atomic.AtomicInteger(1)
    private var sentSignature = false

    private val touchQueue = java.util.concurrent.ConcurrentLinkedQueue<Runnable>()
    /** 独立消费 touchQueue 的线程：视频帧稀疏/静止时触摸命令仍能及时发送 */
    private var touchThread: Thread? = null
    private val streamLock = Any()
    @Volatile private var continuousStreamId = 0
    @Volatile private var continuousStreamRemoteId = 0
    private var streamBuffer = ByteArrayOutputStream()
    /** 复用Bitmap避免每帧GC */
    private var reusableBitmap: Bitmap? = null
    /** scrcpy 视频流 ID */
    @Volatile
    private var videoStreamId = -1
    @Volatile
    private var videoStreamRemoteId = -1

    companion object {
        private const val TAG = "AdbScreenMirror"

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
        private const val MAX_STREAM_BUFFER_SIZE = 10 * 1024 * 1024 // 10MB 上限
        private const val MAX_ADB_PAYLOAD = 1024 * 1024 // 1MB：ADB 单个包最大负载，超过视为损坏
    }

    fun connect(onStatus: (String) -> Unit): Boolean {
        return try {
            onStatus(context.getString(R.string.mirror_connecting_glasses, ipAddress, port))
            Log.i(TAG, "Connecting $ipAddress:$port")

            socket = Socket()
            socket?.tcpNoDelay = true
            socket?.soTimeout = AppConfig.ADB_SOCKET_TIMEOUT_MS
            socket?.connect(java.net.InetSocketAddress(ipAddress, port), AppConfig.ADB_CONNECT_TIMEOUT_MS)
            inputStream = socket?.getInputStream()
            outputStream = socket?.getOutputStream()
            Log.i(TAG, "TCP connected")
            onStatus(context.getString(R.string.mirror_adb_auth))

            loadOrCreateKeys()
            doHandshake()
            Log.i(TAG, "ADB connected")
            onStatus(context.getString(R.string.mirror_connected_short))
            true
        } catch (e: Exception) {
            Log.e(TAG, "Connection failed: ${e.message}", e)
            onStatus(context.getString(R.string.mirror_connection_failed_msg, e.message))
            disconnect()
            false
        }
    }

    private fun loadOrCreateKeys() {
        keyPair = AdbKeyManager.getOrCreateKeyPair(context.filesDir.absolutePath)
    }

    @Throws(Exception::class)
    private fun doHandshake() {
        val kp = keyPair ?: throw IllegalStateException("keyPair not initialized")
        sentSignature = false
        val cnxnPayload = "host::\u0000".toByteArray(Charsets.UTF_8)
        sendPacket(CMD_CNXN, CONNECT_VERSION, 256 * 1024, cnxnPayload)
        Log.i(TAG, "CNXN sent")

        var authAttempts = 0
        while (authAttempts < 5) {
            val msg = readPacket()
            when (msg.command) {
                CMD_CNXN -> {
                    Log.i(TAG, "CNXN received")
                    return
                }
                CMD_AUTH -> {
                    if (msg.arg0 == AUTH_TOKEN) {
                        authAttempts++
                        if (!sentSignature) {
                            Log.i(TAG, "AUTH TOKEN -> sending signature attempt=$authAttempts")
                            val sig = AdbKeyManager.getSignature()
                            sig.initSign(kp.private)
                            sig.update(msg.payload)
                            sendPacket(CMD_AUTH, AUTH_SIGNATURE, 0, sig.sign())
                            sentSignature = true
                        } else {
                            Log.i(TAG, "AUTH TOKEN -> sending public key, check glasses to allow")
                            val pubKeyPayload = getAdbPublicKeyPayload()
                            sendPacket(CMD_AUTH, AUTH_RSA_PUBLIC, 0, pubKeyPayload)
                        }
                    }
                }
            }
        }
        throw RuntimeException("ADB authentication failed ($authAttempts attempts)")
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

    /**
     * 通过 screenrecord + FIFO 管道启动 H.264 视频流。
     * 注：大部分设备 screenrecord 不支持 FIFO 管道（需要 seek），
     * 此方法为实验性，若不工作请降级到 startStreaming。
     */
    /**
     * 高帧率模式：使用 scrcpy-server (tunnel_forward=true) 输出 H.264 流。
     * 通过 ADB CMD_OPEN localabstract:scrcpy 隧道连接 scrcpy LocalServerSocket，
     * 视频数据通过 WRTE 包返回。
     *
     * @param decoder 视频解码器
     * @param onStatus 状态回调
     * @param isBluetooth 是否蓝牙通道（影响 scrcpy 参数）
     */
    fun startH264Streaming(
        decoder: ScreenStreamDecoder?,
        onStatus: (String) -> Unit,
        isBluetooth: Boolean = false,
    ) {
        if (decoder == null) {
            Log.w(TAG, "Decoder not ready, falling back to screencap")
            startStreaming(onFrame = {}, onStatus = onStatus)
            return
        }

        isRunning = true
        startTouchThread()

        Thread {
            while (isRunning) {
                var streamId = -1
                var streamRemoteId = -1

                try {
                    // Step 0: 检查 scrcpy-server.jar 是否存在，不存在则推送
                    try {
                        val checkId = localId.getAndIncrement()
                        sendPacket(CMD_OPEN, checkId, 0, "shell:test -f /data/local/tmp/scrcpy-server.jar && echo EXISTS\u0000".toByteArray(Charsets.UTF_8))
                        val checkDeadline = System.currentTimeMillis() + 5_000L
                        var checkResult = ""
                        var checkDone = false
                        while (isRunning && !checkDone && System.currentTimeMillis() < checkDeadline) {
                            val msg = try { readPacket() } catch (e: java.net.SocketTimeoutException) { continue }
                            if (msg.arg1 == checkId) {
                                when (msg.command) {
                                    CMD_OKAY -> {}
                                    CMD_WRTE -> {
                                        sendPacket(CMD_OKAY, checkId, msg.arg0, null)
                                        checkResult += msg.payload?.let { String(it, Charsets.UTF_8) } ?: ""
                                    }
                                    CMD_CLSE -> checkDone = true
                                }
                            } else {
                                when (msg.command) {
                                    CMD_WRTE -> sendPacket(CMD_OKAY, msg.arg1, msg.arg0, null)
                                    CMD_CLSE -> sendPacket(CMD_CLSE, msg.arg1, msg.arg0, null)
                                    else -> {}
                                }
                            }
                        }
                        if (checkResult.contains("EXISTS")) {
                            Log.i(TAG, "scrcpy-server.jar exists, skip push")
                        } else {
                             // 不存在，推送
                             val pushId = localId.getAndIncrement()
                             sendPacket(CMD_OPEN, pushId, 0, "shell:cat - > /data/local/tmp/scrcpy-server.jar\u0000".toByteArray(Charsets.UTF_8))
                             val pushDeadline = System.currentTimeMillis() + 10_000L
                             var pushRemoteId = -1
                             var jarSent = false
                             var pushDone = false
                             while (isRunning && !pushDone && System.currentTimeMillis() < pushDeadline) {
                                 val msg = readPacket()
                                 if (msg.arg1 == pushId) {
                                     when (msg.command) {
                                         CMD_OKAY -> {
                                             pushRemoteId = msg.arg0
                                             if (!jarSent) {
                                                 jarSent = true
                                                 val jarBytes = context.assets.open("scrcpy-server.jar").use { it.readBytes() }
                                                 sendPacket(CMD_WRTE, pushId, pushRemoteId, jarBytes)
                                                 Log.i(TAG, "jar pushed, size=${jarBytes.size}")
                                             } else {
                                                 // 数据已确认，关闭流
                                                 sendPacket(CMD_CLSE, pushId, pushRemoteId, null)
                                                 pushDone = true
                                             }
                                         }
                                         CMD_WRTE -> {
                                             sendPacket(CMD_OKAY, pushId, msg.arg0, null)
                                             if (jarSent) {
                                                 // shell 输出了提示信息，关闭流
                                                 sendPacket(CMD_CLSE, pushId, pushRemoteId, null)
                                                 pushDone = true
                                             }
                                         }
                                         CMD_CLSE -> pushDone = true
                                     }
                                 } else {
                                    when (msg.command) {
                                        CMD_WRTE -> sendPacket(CMD_OKAY, msg.arg1, msg.arg0, null)
                                        CMD_CLSE -> sendPacket(CMD_CLSE, msg.arg1, msg.arg0, null)
                                        else -> {}
                                    }
                                }
                            }
                            if (pushRemoteId >= 0) sendPacket(CMD_CLSE, pushId, pushRemoteId, null)
                            Thread.sleep(300)
                            Log.i(TAG, "jar push done")
                        }
                    } catch (e: Exception) {
                            Log.e(TAG, "Failed to check/push scrcpy-server.jar", e)
                        }

                    // Step 1: 先结束可能残留的旧 scrcpy-server 进程
                    onStatus(context.getString(R.string.mirror_starting_server))
                    runCatching {
                        val killId = localId.getAndIncrement()
                        sendPacket(CMD_OPEN, killId, 0, "shell:pkill -f scrcpy.Server 2>/dev/null; sleep 1; echo killed\u0000".toByteArray(Charsets.UTF_8))
                        val killDeadline = System.currentTimeMillis() + 3_000L
                        var killDone = false
                        while (isRunning && !killDone && System.currentTimeMillis() < killDeadline) {
                            val msg = try { readPacket() } catch (_: java.net.SocketTimeoutException) { continue }
                            if (msg.arg1 == killId) {
                                when (msg.command) {
                                    CMD_CLSE -> killDone = true
                                    CMD_WRTE -> { sendPacket(CMD_OKAY, killId, msg.arg0, null) }
                                    else -> {}
                                }
                            } else {
                                when (msg.command) {
                                    CMD_WRTE -> sendPacket(CMD_OKAY, msg.arg1, msg.arg0, null)
                                    CMD_CLSE -> sendPacket(CMD_CLSE, msg.arg1, msg.arg0, null)
                                    else -> {}
                                }
                            }
                        }
                    }
                    Log.i(TAG, "old scrcpy-server killed (if any)")

                    // Step 2: 启动新 server（nohup 保护进程不被 shell 退出杀死）
                    val shellId = localId.getAndIncrement()
                    // 兼容性适配：检测是否需要指定编码器
                    val encoderArg = ManufacturerUtils.getRecommendedEncoder()?.let {
                        "video_encoder=$it "
                    } ?: ""
                    // 根据连接类型选择 scrcpy 参数
                    val bitrate = if (isBluetooth) AppConfig.SCRCPY_BT_BITRATE else AppConfig.SCRCPY_WIFI_BITRATE
                    val maxSize = if (isBluetooth) AppConfig.SCRCPY_BT_MAX_SIZE else AppConfig.SCRCPY_WIFI_MAX_SIZE
                    Log.i(TAG, "scrcpy config: bitrate=$bitrate, max_size=$maxSize (BT=$isBluetooth)")
                    // 无线连接下 scrcpy 的 stay_awake 不生效，显式把屏幕超时拉满，防止镜像期间眼镜息屏导致视频冻结
                    val keepAwakeCmd = "settings put system screen_off_timeout 2147483647 && "
                    val shellCmd = ("shell:${keepAwakeCmd}nohup app_process -Djava.class.path=/data/local/tmp/scrcpy-server.jar " +
                            "/ com.genymobile.scrcpy.Server 3.3.4 " +
                            "stay_awake=true " +
                            "tunnel_forward=true video_bit_rate=$bitrate " +
                            "max_size=$maxSize " +
                            "${encoderArg}" +
                            "video=true audio=false control=false cleanup=false " +
                            "> /dev/null 2>&1 &\nsleep 3\necho ok\n\u0000")
                    sendPacket(CMD_OPEN, shellId, 0, shellCmd.toByteArray(Charsets.UTF_8))
                    Log.i(TAG, "Starting shell, shellId=$shellId")

                    // 等待 shell 结束
                    val shellDeadline = System.currentTimeMillis() + 15_000L
                    var shellDone = false
                    while (isRunning && !shellDone && System.currentTimeMillis() < shellDeadline) {
                        val msg = try { readPacket() } catch (e: java.net.SocketTimeoutException) { continue }
                        if (msg.arg1 == shellId) {
                            when (msg.command) {
                                CMD_OKAY -> {}
                                CMD_WRTE -> {
                                    sendPacket(CMD_OKAY, shellId, msg.arg0, null)
                                    val text = msg.payload?.let { String(it, Charsets.UTF_8) } ?: ""
                                    Log.d(TAG, "shell output: $text")
                                }
                                CMD_CLSE -> shellDone = true
                            }
                        } else {
                            when (msg.command) {
                                CMD_WRTE -> sendPacket(CMD_OKAY, msg.arg1, msg.arg0, null)
                                CMD_CLSE -> sendPacket(CMD_CLSE, msg.arg1, msg.arg0, null)
                                CMD_OKAY -> {}
                                else -> {}
                            }
                        }
                    }
                    if (!shellDone) {
                        Log.w(TAG, "shell did not complete within 15s, continuing")
                    }

                    // Step 3: 轮询等待 scrcpy-server 就绪，而非固定 sleep
                    onStatus(context.getString(R.string.mirror_connecting_tunnel))
                    var connected = false
                    val serverReadyDeadline = System.currentTimeMillis() + 15_000L
                    var localAbstractAttempts = 0
                    while (isRunning && !connected && System.currentTimeMillis() < serverReadyDeadline) {
                        if (localAbstractAttempts > 0) {
                            // 指数退避：1s, 2s, 3s, ...
                            val waitMs = minOf(1000L * localAbstractAttempts, 5000L)
                            Log.i(TAG, "localabstract retry #$localAbstractAttempts, waiting ${waitMs}ms")
                            Thread.sleep(waitMs)
                        }
                        localAbstractAttempts++

                        streamId = localId.getAndIncrement()
                        val connectCmd = "localabstract:scrcpy\u0000"
                        sendPacket(CMD_OPEN, streamId, 0, connectCmd.toByteArray(Charsets.UTF_8))
                        Log.i(TAG, "Requesting connection to localabstract:scrcpy (attempt #$localAbstractAttempts), streamId=$streamId")

                        // 等待 OKAY 确认（每轮 3s）
                        val connectDeadline = System.currentTimeMillis() + 3_000L
                        while (isRunning && !connected && System.currentTimeMillis() < connectDeadline) {
                            val msg = try { readPacket() } catch (e: java.net.SocketTimeoutException) { continue }
                            if (msg.arg1 == streamId) {
                                when (msg.command) {
                                    CMD_OKAY -> {
                                        streamRemoteId = msg.arg0
                                        connected = true
                                        Log.i(TAG, "LocalSocket video connected")
                                    }
                                    CMD_CLSE -> {
                                        Log.w(TAG, "LocalSocket rejected, will retry")
                                        break
                                    }
                                    CMD_WRTE -> {
                                        streamRemoteId = msg.arg0
                                        connected = true
                                        sendPacket(CMD_OKAY, streamId, msg.arg0, null)
                                        Log.i(TAG, "data arrived, LocalSocket video connected")
                                        decoder.feedData(msg.payload)
                                    }
                                }
                            } else {
                                when (msg.command) {
                                    CMD_WRTE -> sendPacket(CMD_OKAY, msg.arg1, msg.arg0, null)
                                    CMD_CLSE -> sendPacket(CMD_CLSE, msg.arg1, msg.arg0, null)
                                    else -> {}
                                }
                            }
                        }
                        if (!connected) {
                            sendPacket(CMD_CLSE, streamId, 0, null)
                        }
                    }
                    if (!connected) {
                        Log.w(TAG, "cannot connect LocalSocket after $localAbstractAttempts attempts, restarting server...")
                        continue
                    }

                    videoStreamId = streamId
                    videoStreamRemoteId = streamRemoteId

                    // 设置 socket 超时以便及时处理触控
                    val streamTimeout = if (isBluetooth) AppConfig.SCRCPY_BT_TIMEOUT_MS else AppConfig.SCRCPY_WIFI_TIMEOUT_MS
                    try { socket?.soTimeout = streamTimeout } catch (_: Exception) {}

                    onStatus(context.getString(R.string.mirror_streaming))
                    Log.i(TAG, "Starting H.264 stream reading...")

                    // Step 5: 循环读取 WRTE 包，喂给解码器
                    var totalBytes = 0L
                    var lastDataTime = System.currentTimeMillis()
                    val idleTimeout = 180_000L // 3 分钟无数据则重连

                    while (isRunning) {
                        // 每轮最多处理 1 个触摸命令，防止 ADB Shell 响应洪流阻塞视频流
                        val touchCmd = touchQueue.poll()
                        if (touchCmd != null) {
                            touchCmd.run()
                        }

                        val msg: AdbMessage
                        try {
                            msg = readPacket()
                        } catch (e: java.net.SocketTimeoutException) {
                            // 超时是正常的，用于及时处理触控
                            continue
                        }
                        if (msg.arg1 == streamId) {
                            when (msg.command) {
                                CMD_WRTE -> {
                                    sendPacket(CMD_OKAY, streamId, msg.arg0, null)
                                    lastDataTime = System.currentTimeMillis()
                                    totalBytes += msg.payload.size
                                    decoder.feedData(msg.payload)
                                }
                                CMD_CLSE -> {
                                    Log.w(TAG, "stream closed by peer")
                                    break
                                }
                                else -> {}
                            }
                        } else {
                            when (msg.command) {
                                CMD_WRTE -> sendPacket(CMD_OKAY, msg.arg1, msg.arg0, null)
                                CMD_CLSE -> sendPacket(CMD_CLSE, msg.arg1, msg.arg0, null)
                                else -> {}
                            }
                        }

                        // 检查空闲超时
                        if (System.currentTimeMillis() - lastDataTime > idleTimeout) {
                            Log.w(TAG, "stream idle timeout")
                            break
                        }
                    }
                    Log.i(TAG, "stream ended, total $totalBytes bytes")
                } catch (e: Exception) {
                    if (e !is java.net.SocketTimeoutException) {
                        if (e.message?.let { it.contains("Socket closed") || it.contains("Broken pipe") } == true) {
                        Log.i(TAG, "Connection closed (${e.message})")
                        } else {
                        Log.e(TAG, "Stream error: ${e.message}")
                        }
                    }
                } finally {
                    videoStreamId = -1
                    videoStreamRemoteId = -1
                    if (streamId >= 0) {
                        try { sendPacket(CMD_CLSE, streamId, streamRemoteId, null) } catch (_: Exception) {}
                    }
                }

                if (isRunning) {
                    decoder.stop()
                    decoder.start()
                    onStatus(context.getString(R.string.mirror_reconnecting))
                    // 重新建立 ADB 连接，最多重试 3 轮（每轮 3 次快速重试 + 3s 等待）
                    var reconnected = false
                    for (round in 1..3) {
                        if (!isRunning) break
                        for (retry in 1..3) {
                            if (!isRunning) break
                            if (retry > 1) Thread.sleep(2000)
                            try {
                                socket?.close()
                                socket = Socket()
                                socket?.tcpNoDelay = true
                                socket?.soTimeout = AppConfig.ADB_SOCKET_TIMEOUT_MS
                                socket?.connect(java.net.InetSocketAddress(ipAddress, port), AppConfig.ADB_CONNECT_TIMEOUT_MS)
                                inputStream = socket?.getInputStream()
                                outputStream = socket?.getOutputStream()
                                doHandshake()
                                Log.i(TAG, "reconnect ok (round $round, retry $retry)")
                                reconnected = true
                                break
                            } catch (e: Exception) {
                                Log.w(TAG, "Reconnect failed round $round retry #$retry: ${e.message}")
                            }
                        }
                        if (reconnected || !isRunning) break
                        Thread.sleep(3000)
                    }
                    if (!reconnected) {
                        Log.w(TAG, "reconnect failed after 3 rounds, stopping")
                        break
                    }
                }
            }
        }.apply { name = "scrcpy-stream" }.start()
    }

    /** 低画质模式：使用 screencap 逐帧获取原始像素（约 2FPS） */
    fun startStreaming(
        onFrame: (Bitmap) -> Unit,
        onStatus: (String) -> Unit,
    ) {
        isRunning = true
        startTouchThread()
        Thread {
            try {
                continuousStreamId = localId.getAndIncrement()
                val dest = "shell:while true; do screencap; done\u0000"
                sendPacket(CMD_OPEN, continuousStreamId, 0, dest.toByteArray(Charsets.UTF_8))
        Log.i(TAG, "Continuous stream opened, streamId=$continuousStreamId")

                var streamOpened = false
                streamBuffer = ByteArrayOutputStream()
                var frameCount = 0
                var totalMs = 0L

                while (isRunning) {
                    try {
                        // 每轮最多处理 1 个触摸命令
                        val touchCmd = touchQueue.poll()
                        if (touchCmd != null) {
                            touchCmd.run()
                        }

                        val msg = readPacket()
                        if (msg.arg1 == continuousStreamId) {
                            when (msg.command) {
                                CMD_OKAY -> {
                                    if (!streamOpened) {
                                        streamOpened = true
                                        continuousStreamRemoteId = msg.arg0
                    Log.i(TAG, "Continuous stream OKAY")
                                    }
                                }
                                CMD_WRTE -> {
                                    if (!streamOpened) {
                                        streamOpened = true
                                        continuousStreamRemoteId = msg.arg0
                                    }
                                    sendPacket(CMD_OKAY, continuousStreamId, msg.arg0, null)

                                    // 累积数据
                                    streamBuffer.write(msg.payload)
                                    val buf = streamBuffer.toByteArray()

                                    // 扫描整个缓冲区找有效帧头
                                    var frameFound = false
                                    var scanOff = 0
                                    while (scanOff + 12 <= buf.size) {
                                        val w = (buf[scanOff].toInt() and 0xFF) or
                                            ((buf[scanOff + 1].toInt() and 0xFF) shl 8) or
                                            ((buf[scanOff + 2].toInt() and 0xFF) shl 16) or
                                            ((buf[scanOff + 3].toInt() and 0xFF) shl 24)
                                        val h = (buf[scanOff + 4].toInt() and 0xFF) or
                                            ((buf[scanOff + 5].toInt() and 0xFF) shl 8) or
                                            ((buf[scanOff + 6].toInt() and 0xFF) shl 16) or
                                            ((buf[scanOff + 7].toInt() and 0xFF) shl 24)
                                        val fmt = (buf[scanOff + 8].toInt() and 0xFF) or
                                            ((buf[scanOff + 9].toInt() and 0xFF) shl 8) or
                                            ((buf[scanOff + 10].toInt() and 0xFF) shl 16) or
                                            ((buf[scanOff + 11].toInt() and 0xFF) shl 24)

                                        if (w == 480 && h == 640 && fmt == 1) {
                                            val frameSize = 12 + w * h * 4
                                            if (buf.size >= scanOff + frameSize) {
                                                val pixelData = buf.copyOfRange(scanOff + 12, scanOff + frameSize)
                                                streamBuffer.reset()
                                                val remaining = buf.size - (scanOff + frameSize)
                                                if (remaining > 0) {
                                                    streamBuffer.write(buf, scanOff + frameSize, remaining)
                                                }

                                                val t0 = System.nanoTime()
                                                val pixels = IntArray(w * h)
                                                var src = 0
                                                for (i in 0 until w * h) {
                                                    pixels[i] = (0xFF shl 24) or
                                                        ((pixelData[src].toInt() and 0xFF) shl 16) or
                                                        ((pixelData[src + 1].toInt() and 0xFF) shl 8) or
                                                        (pixelData[src + 2].toInt() and 0xFF)
                                                    src += 4
                                                }
                                                if (reusableBitmap?.width != w || reusableBitmap?.height != h) {
                                                    reusableBitmap?.recycle()
                                                    reusableBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                                                }
                                                reusableBitmap!!.setPixels(pixels, 0, w, 0, 0, w, h)
                                                val elapsedMs = (System.nanoTime() - t0) / 1_000_000
                                                onFrame(reusableBitmap!!)

                                                frameCount++
                                                totalMs += elapsedMs
                                                if (frameCount % 5 == 0) {
                                                    val fps = frameCount * 1000f / totalMs
                                                    Log.i(TAG, "frame #$frameCount: 480x640 ${elapsedMs}ms ${fps.toInt()}fps")
                                                }
                                                frameFound = true
                                                break  // 处理了一帧，等下一个 WRTE
                                            } else {
                                                break  // 等更多数据
                                            }
                                        }
                                        scanOff++
                                    }

                                    // 找不到且缓冲区过大，丢弃旧数据
                                    if (!frameFound && buf.size > 3 * 1024 * 1024) {
                                        streamBuffer.reset()
                                        streamBuffer.write(buf, buf.size - 2 * 1024 * 1024, 2 * 1024 * 1024)
                                    }
                                }
                                CMD_CLSE -> {
                    Log.i(TAG, "Connected stream closed")
                                    sendPacket(CMD_CLSE, continuousStreamId, continuousStreamRemoteId, null)
                                    break
                                }
                            }
                        } else {
                            when (msg.command) {
                                CMD_WRTE -> {
                                    sendPacket(CMD_OKAY, msg.arg1, msg.arg0, null)
                                }
                                CMD_CLSE -> {
                                    sendPacket(CMD_CLSE, msg.arg1, msg.arg0, null)
                                }
                                CMD_OKAY -> {}
                                else -> {}
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "stream read error: ${e.message}")
                        // attempt socket reconnect
                        if (isRunning) {
                            try {
                                socket?.close()
                                socket = Socket()
                                socket?.tcpNoDelay = true
                                socket?.soTimeout = AppConfig.ADB_SOCKET_TIMEOUT_MS
                                socket?.connect(java.net.InetSocketAddress(ipAddress, port), AppConfig.ADB_CONNECT_TIMEOUT_MS)
                                inputStream = socket?.getInputStream()
                                outputStream = socket?.getOutputStream()
                                doHandshake()
                                Log.i(TAG, "continuous stream socket reconnected")
                                // reopen the screencap stream
                                continuousStreamId = localId.getAndIncrement()
                                val dest = "shell:while true; do screencap; done\u0000"
                                sendPacket(CMD_OPEN, continuousStreamId, 0, dest.toByteArray(Charsets.UTF_8))
                                streamOpened = false
                                streamBuffer.reset()
                            } catch (re: Exception) {
                                Log.w(TAG, "Reconnect failed: ${re.message}")
                                Thread.sleep(2000)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Continuous stream start failed: ${e.message}")
            }
        }.start()
    }

    fun sendTap(x: Int, y: Int) {
        Log.i(TAG, "sendTap queued: $x,$y")
        touchQueue.add {
            Log.i(TAG, "sendTap executed: $x,$y")
            try {
                val sid = localId.getAndIncrement()
                sendPacket(CMD_OPEN, sid, 0, "shell:input tap $x $y\u0000".toByteArray(Charsets.UTF_8))
                Log.i(TAG, "sendTap sent via shell:input tap $x $y")
            } catch (e: Exception) {
                Log.e(TAG, "sendTap failed: ${e.message}")
            }
        }
    }

    fun sendSwipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int = 300) {
        Log.i(TAG, "sendSwipe queued: ($x1,$y1)->($x2,$y2)")
        touchQueue.add {
            Log.i(TAG, "sendSwipe executed: ($x1,$y1)->($x2,$y2)")
            try {
                val sid = localId.getAndIncrement()
                sendPacket(CMD_OPEN, sid, 0, "shell:input swipe $x1 $y1 $x2 $y2 $durationMs\u0000".toByteArray(Charsets.UTF_8))
            } catch (e: Exception) {
                Log.e(TAG, "sendSwipe failed: ${e.message}")
            }
        }
    }

    fun sendKeyEvent(key: String) {
        Log.i(TAG, "sendKeyEvent queued: $key")
        touchQueue.add {
            Log.i(TAG, "sendKeyEvent executed: $key")
            try {
                val sid = localId.getAndIncrement()
                sendPacket(CMD_OPEN, sid, 0, "shell:input keyevent $key\u0000".toByteArray(Charsets.UTF_8))
            } catch (e: Exception) {
                Log.e(TAG, "sendKeyEvent failed: ${e.message}")
            }
        }
    }

    /** 独立消费 touchQueue 的线程：不依赖视频流读取循环，视频帧静止时触摸仍即时发送 */
    private fun startTouchThread() {
        stopTouchThread()
        touchThread = Thread {
            while (isRunning) {
                val cmd = touchQueue.poll()
                if (cmd == null) {
                    try { Thread.sleep(15) } catch (_: InterruptedException) { break }
                    continue
                }
                try {
                    cmd.run()
                } catch (e: Exception) {
                    Log.e(TAG, "touch command failed: ${e.message}")
                }
            }
        }.apply {
            name = "scrcpy-touch"
            isDaemon = true
        }
        touchThread?.start()
        Log.i(TAG, "touch thread started")
    }

    private fun stopTouchThread() {
        touchThread?.interrupt()
        touchThread = null
    }

    @Throws(Exception::class)
    @Synchronized
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

        if (msg.payloadLength < 0 || msg.payloadLength > MAX_ADB_PAYLOAD) {
            throw java.io.IOException("Invalid payload size: ${msg.payloadLength}")
        }

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
            if (read < 0) throw java.io.IOException("stream closed")
            offset += read
        }
    }

    private fun checksum(data: ByteArray?): Int {
        if (data == null) return 0
        var sum = 0
        for (b in data) sum += b.toInt() and 0xFF
        return sum
    }

    /** 检查 /data/local/tmp/scrcpy-server.jar 是否存在，不存在则从 assets 推送 */
    fun disconnect() {
        // 先发送断开命令（保持流线程仍可处理 ADB 响应），再停止流线程
        restoreScreenTimeout()
        killServer()
        goHome()
        // 短暂等待命令发送完成
        try { Thread.sleep(100) } catch (_: Exception) {}
        isRunning = false
        stopTouchThread()
        touchQueue.clear()
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        inputStream = null
        outputStream = null
        reusableBitmap?.recycle()
        reusableBitmap = null
    }

    /** 恢复眼镜端屏幕超时（退出镜像后恢复 30 秒默认值） */
    private fun restoreScreenTimeout() {
        try {
            val sid = localId.getAndIncrement()
            sendPacket(CMD_OPEN, sid, 0, "shell:settings put system screen_off_timeout 30000\necho ok\u0000".toByteArray(Charsets.UTF_8))
        } catch (_: Exception) {}
    }

    /** 发送命令杀死眼镜端的 scrcpy-server */
    private fun killServer() {
        try {
            val sid = localId.getAndIncrement()
            val cmd = "shell:pkill -9 -f scrcpy.Server 2>/dev/null\necho killed\u0000"
            sendPacket(CMD_OPEN, sid, 0, cmd.toByteArray(Charsets.UTF_8))
        } catch (_: Exception) {}
    }

    /** 发送命令让眼镜返回桌面（关闭 RokidLink 显示层） */
    private fun goHome() {
        try {
            Thread.sleep(200)
            val sid = localId.getAndIncrement()
            val cmd = "shell:am start -a android.intent.action.MAIN -c android.intent.category.HOME 2>/dev/null\u0000"
            sendPacket(CMD_OPEN, sid, 0, cmd.toByteArray(Charsets.UTF_8))
        } catch (_: Exception) {}
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
