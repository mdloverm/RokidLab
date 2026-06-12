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
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec

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
    private var localId = 1
    private var sentSignature = false

    private val touchQueue = java.util.concurrent.ConcurrentLinkedQueue<Runnable>()
    private var continuousStreamId = 0
    private var continuousStreamRemoteId = 0
    private var streamBuffer = ByteArrayOutputStream()

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
    }

    fun connect(onStatus: (String) -> Unit): Boolean {
        return try {
            onStatus("正在连接眼镜 ($ipAddress:$port)...")
            Log.i(TAG, "正在连接 $ipAddress:$port")

            socket = Socket(ipAddress, port)
            socket?.tcpNoDelay = true
            inputStream = socket?.getInputStream()
            outputStream = socket?.getOutputStream()
            Log.i(TAG, "TCP 连接已建立")
            onStatus("正在完成 ADB 认证...")

            loadOrCreateKeys()
            doHandshake()
            Log.i(TAG, "ADB 连接成功")
            onStatus("连接成功")
            true
        } catch (e: Exception) {
            Log.e(TAG, "连接失败: ${e.message}", e)
            onStatus("连接失败: ${e.message}")
            disconnect()
            false
        }
    }

    private fun loadOrCreateKeys() {
        val privKeyFile = File(context.filesDir, "adbkey")
        val pubKeyFile = File(context.filesDir, "adbkey.pub")

        if (privKeyFile.exists() && pubKeyFile.exists()) {
            try {
                val privBytes = FileInputStream(privKeyFile).use { it.readBytes() }
                val pubBytes = FileInputStream(pubKeyFile).use { it.readBytes() }
                val keyFactory = KeyFactory.getInstance("RSA")
                val privKey = keyFactory.generatePrivate(PKCS8EncodedKeySpec(privBytes))
                val pubKey = keyFactory.generatePublic(X509EncodedKeySpec(pubBytes))
                keyPair = KeyPair(pubKey, privKey)
                return
            } catch (e: Exception) {
                Log.w(TAG, "加载密钥失败，重新生成: ${e.message}")
            }
        }

        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        keyPair = kpg.genKeyPair()
        val kp = keyPair ?: return
        FileOutputStream(privKeyFile).use { it.write(kp.private.encoded) }
        FileOutputStream(pubKeyFile).use { it.write(kp.public.encoded) }
    }

    @Throws(Exception::class)
    private fun doHandshake() {
        val kp = keyPair ?: throw IllegalStateException("keyPair not initialized")
        sentSignature = false
        val cnxnPayload = "host::\u0000".toByteArray(Charsets.UTF_8)
        sendPacket(CMD_CNXN, CONNECT_VERSION, 256 * 1024, cnxnPayload)
        Log.i(TAG, "CNXN 已发送")

        while (true) {
            val msg = readPacket()
            when (msg.command) {
                CMD_CNXN -> {
                    Log.i(TAG, "CNXN 收到")
                    return
                }
                CMD_AUTH -> {
                    if (msg.arg0 == AUTH_TOKEN) {
                        if (!sentSignature) {
                            Log.i(TAG, "AUTH TOKEN -> 发送签名")
                            val sig = Signature.getInstance("SHA1withRSA")
                            sig.initSign(kp.private)
                            sig.update(msg.payload)
                            sendPacket(CMD_AUTH, AUTH_SIGNATURE, 0, sig.sign())
                            sentSignature = true
                        } else {
                            Log.i(TAG, "AUTH TOKEN -> 发送公钥，请查看眼镜点击允许")
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

    fun startStreaming(
        onFrame: (Bitmap) -> Unit,
        onStatus: (String) -> Unit,
    ) {
        isRunning = true
        Thread {
            var frameCount = 0
            var totalMs = 0L

            try {
                continuousStreamId = localId++
                val dest = "shell:while true; do screencap; done\u0000"
                sendPacket(CMD_OPEN, continuousStreamId, 0, dest.toByteArray(Charsets.UTF_8))
                Log.i(TAG, "连续流已打开，streamId=$continuousStreamId")

                var streamOpened = false
                streamBuffer = ByteArrayOutputStream()

                while (isRunning) {
                    try {
                        while (true) {
                            val cmd = touchQueue.poll() ?: break
                            cmd.run()
                        }

                        val msg = readPacket()
                        if (msg.arg1 == continuousStreamId) {
                            when (msg.command) {
                                CMD_OKAY -> {
                                    if (!streamOpened) {
                                        streamOpened = true
                                        continuousStreamRemoteId = msg.arg0
                                        Log.i(TAG, "连续流 OKAY")
                                    }
                                }
                                CMD_WRTE -> {
                                    if (!streamOpened) {
                                        streamOpened = true
                                        continuousStreamRemoteId = msg.arg0
                                    }
                                    streamBuffer.write(msg.payload)
                                    sendPacket(CMD_OKAY, continuousStreamId, msg.arg0, null)

                                    val buf = streamBuffer.toByteArray()
                                    val headerSize = 12
                                    if (buf.size >= headerSize) {
                                        val hdr = ByteBuffer.wrap(buf, 0, headerSize).order(ByteOrder.LITTLE_ENDIAN)
                                        val w = hdr.getInt()
                                        val h = hdr.getInt()
                                        val fmt = hdr.getInt()

                                        if (w > 0 && w <= 2000 && h > 0 && h <= 2000 && fmt in listOf(1, 2, 4)) {
                                            val bpp = if (fmt == 4) 2 else 4
                                            val frameSize = headerSize + w * h * bpp

                                            if (buf.size >= frameSize) {
                                                val pixelData = buf.copyOfRange(headerSize, frameSize)
                                                streamBuffer.reset()
                                                val remaining = buf.size - frameSize
                                                if (remaining > 0) {
                                                    streamBuffer.write(buf, frameSize, remaining)
                                                }

                                                val t0 = System.nanoTime()
                                                val pixels = IntArray(w * h)
                                                if (fmt == 1 || fmt == 2) {
                                                    for (i in 0 until w * h) {
                                                        val off = i * 4
                                                        val r = pixelData[off].toInt() and 0xFF
                                                        val g = pixelData[off + 1].toInt() and 0xFF
                                                        val b = pixelData[off + 2].toInt() and 0xFF
                                                        val a = pixelData[off + 3].toInt() and 0xFF
                                                        pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
                                                    }
                                                } else {
                                                    for (i in 0 until w * h) {
                                                        val off = i * 2
                                                        val p = (pixelData[off].toInt() and 0xFF) or ((pixelData[off + 1].toInt() and 0xFF) shl 8)
                                                        val r5 = (p shr 11) and 0x1F
                                                        val g6 = (p shr 5) and 0x3F
                                                        val b5 = p and 0x1F
                                                        pixels[i] = (0xFF shl 24) or (r5 shl 19) or (g6 shl 10) or (b5 shl 3)
                                                    }
                                                }
                                                val bitmap = Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
                                                val elapsedMs = (System.nanoTime() - t0) / 1_000_000

                                                frameCount++
                                                totalMs += elapsedMs
                                                onFrame(bitmap)

                                                if (frameCount % 5 == 0) {
                                                    val avg = if (frameCount > 1) totalMs / frameCount else elapsedMs
                                                    val fps = frameCount * 1000f / totalMs
                                                    onStatus("${w}x${h} ${elapsedMs}ms ${fps.toInt()}fps")
                                                    Log.i(TAG, "帧 #$frameCount: ${w}x${h} fmt=$fmt 耗时=${elapsedMs}ms 平均=${avg}ms")
                                                }
                                            }
                                        } else {
                                            Log.w(TAG, "跳过无效header: w=$w, h=$h, fmt=$fmt, bufSize=${buf.size}")
                                            streamBuffer.reset()
                                        }
                                    }
                                }
                                CMD_CLSE -> {
                                    Log.w(TAG, "连续流被关闭")
                                    break
                                }
                            }
                        } else {
                            when (msg.command) {
                                CMD_WRTE -> {
                                    sendPacket(CMD_OKAY, msg.arg1, msg.arg0, null)
                                    sendPacket(CMD_CLSE, msg.arg1, msg.arg0, null)
                                }
                                CMD_CLSE -> {}
                                CMD_OKAY -> {
                                    sendPacket(CMD_CLSE, msg.arg1, msg.arg0, null)
                                }
                                else -> {}
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "流读取错误: ${e.message}")
                        Thread.sleep(500)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "连续流启动失败: ${e.message}")
            }
        }.start()
    }

    fun sendTap(x: Int, y: Int) {
        touchQueue.add(Runnable {
            val sid = localId++
            sendPacket(CMD_OPEN, sid, 0, "shell:input tap $x $y\u0000".toByteArray(Charsets.UTF_8))
        })
    }

    fun sendSwipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int = 300) {
        touchQueue.add(Runnable {
            val sid = localId++
            sendPacket(CMD_OPEN, sid, 0, "shell:input swipe $x1 $y1 $x2 $y2 $durationMs\u0000".toByteArray(Charsets.UTF_8))
        })
    }

    fun sendKeyEvent(key: String) {
        touchQueue.add(Runnable {
            val sid = localId++
            sendPacket(CMD_OPEN, sid, 0, "shell:input keyevent $key\u0000".toByteArray(Charsets.UTF_8))
        })
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
            if (read < 0) throw java.io.IOException("流已关闭")
            offset += read
        }
    }

    private fun checksum(data: ByteArray?): Int {
        if (data == null) return 0
        var sum = 0
        for (b in data) sum += b.toInt() and 0xFF
        return sum
    }

    fun disconnect() {
        isRunning = false
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        inputStream = null
        outputStream = null
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
