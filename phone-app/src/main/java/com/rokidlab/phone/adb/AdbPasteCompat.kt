package com.rokidlab.phone.adb

import android.util.Log
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPair
import java.util.concurrent.atomic.AtomicInteger

/**
 * ADB Shell 粘贴兼容层
 *
 * 通过原始 ADB 协议连接眼镜 ADB TCP 端口 5555，
 * 以 shell 身份执行 `input keyevent KEYCODE_PASTE`（拥有 INJECT_EVENTS 权限）。
 *
 * 眼镜头像端已由 RokidLink 启用 ADB TCP（port 5555）。
 */
object AdbPasteCompat {
    private const val TAG = "AdbPasteCompat"

    private const val CMD_CNXN = 0x4e584e43
    private const val CMD_AUTH = 0x48545541
    private const val CMD_OPEN = 0x4e45504f
    private const val CMD_OKAY = 0x59414b4f
    private const val CMD_CLSE = 0x45534c43
    private const val CMD_WRTE = 0x45545257

    private const val AUTH_TOKEN = 1
    private const val AUTH_SIGNATURE = 2
    private const val AUTH_RSA_PUBLIC = 3

    private const val HEADER_LENGTH = 24
    private const val MAX_ADB_PAYLOAD = 1024 * 1024

    /**
     * 连接眼镜 ADB 并执行 `input keyevent KEYCODE_PASTE`。
     *
     * @param ip       眼镜 IP 地址
     * @param keyPair  RSA 密钥对（来自 AdbKeyManager）
     * @param port     ADB 端口（默认 5555）
     * @return true 表示粘贴命令已成功执行
     */
    fun execPaste(ip: String, keyPair: KeyPair, port: Int = 5555): Boolean {
        var socket: Socket? = null
        val localId = AtomicInteger(1)

        try {
            socket = Socket().apply {
                tcpNoDelay = true
                soTimeout = 5000
                connect(InetSocketAddress(ip, port), 5000)
            }

            val input = socket.getInputStream()
            val output = socket.getOutputStream()

            // 1. ADB 握手
            doHandshake(input, output, keyPair)
            Log.i(TAG, "ADB handshake ok")

            // 2. 打开 shell 流执行粘贴
            val streamId = localId.getAndIncrement()
            val shellCmd = "shell:input keyevent KEYCODE_PASTE\u0000"
            sendPacket(output, CMD_OPEN, streamId, 0, shellCmd.toByteArray(Charsets.UTF_8))
            Log.i(TAG, "OPEN shell:input keyevent KEYCODE_PASTE sent")

            // 3. 读取响应
            var remoteId = -1
            var done = false
            val outputBuf = StringBuilder()
            val deadline = System.currentTimeMillis() + 5000

            while (!done && System.currentTimeMillis() < deadline) {
                val msg = readPacket(input)
                when (msg.cmd) {
                    CMD_OKAY -> {
                        if (msg.arg1 == streamId) remoteId = msg.arg0
                    }
                    CMD_WRTE -> {
                        if (msg.arg1 == streamId) {
                            sendPacket(output, CMD_OKAY, streamId, msg.arg0, null)
                            outputBuf.append(if (msg.data.isNotEmpty()) String(msg.data, Charsets.UTF_8) else "")
                        } else {
                            feedOthers(output, msg)
                        }
                    }
                    CMD_CLSE -> {
                        if (msg.arg1 == streamId) done = true
                        else sendPacket(output, CMD_CLSE, msg.arg1, msg.arg0, null)
                    }
                }
            }

            if (remoteId >= 0) sendPacket(output, CMD_CLSE, streamId, remoteId, null)

            val result = outputBuf.toString().trim()
            Log.i(TAG, "Paste result: $result")
            return true

        } catch (e: Exception) {
            Log.w(TAG, "ADB paste failed: ${e.message}")
            return false
        } finally {
            try { socket?.close() } catch (_: Exception) {}
        }
    }

    @Throws(Exception::class)
    private fun doHandshake(input: InputStream, output: OutputStream, keyPair: KeyPair) {
        sendPacket(output, CMD_CNXN, 0x01000000, 256 * 1024, "host::\u0000".toByteArray(Charsets.UTF_8))

        var sentSignature = false
        var authAttempts = 0

        while (authAttempts < 5) {
            val msg = readPacket(input)
            when (msg.cmd) {
                CMD_CNXN -> return
                CMD_AUTH -> {
                    if (msg.arg0 == AUTH_TOKEN) {
                        authAttempts++
                        if (!sentSignature) {
                            val sig = AdbKeyManager.getSignature()
                            sig.initSign(keyPair.private)
                            sig.update(msg.data)
                            sendPacket(output, CMD_AUTH, AUTH_SIGNATURE, 0, sig.sign())
                            sentSignature = true
                        } else {
                            sendPacket(output, CMD_AUTH, AUTH_RSA_PUBLIC, 0, getPubKeyPayload(keyPair))
                        }
                    }
                }
            }
        }
        throw RuntimeException("ADB auth failed after $authAttempts attempts")
    }

    @Throws(Exception::class)
    private fun getPubKeyPayload(keyPair: KeyPair): ByteArray {
        val pubKey = keyPair.public as java.security.interfaces.RSAPublicKey
        val buf = ByteBuffer.allocate(524)
        buf.order(ByteOrder.LITTLE_ENDIAN)
        val modulus = pubKey.modulus.toByteArray()
        val padded = ByteArray(256)
        System.arraycopy(modulus, if (modulus.size > 256) 1 else 0, padded, 0, minOf(modulus.size, 256))
        buf.put(padded)
        buf.putInt(pubKey.publicExponent.toInt())
        val b64 = android.util.Base64.encodeToString(buf.array(), android.util.Base64.NO_WRAP)
        return "$b64 paste@adb\u0000".toByteArray(Charsets.UTF_8)
    }

    @Throws(Exception::class)
    private fun sendPacket(output: OutputStream, cmd: Int, arg0: Int, arg1: Int, payload: ByteArray?) {
        val len = payload?.size ?: 0
        val buf = ByteBuffer.allocate(HEADER_LENGTH + len).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(cmd); buf.putInt(arg0); buf.putInt(arg1)
        buf.putInt(len); buf.putInt(checksum(payload)); buf.putInt(cmd.inv())
        if (payload != null) buf.put(payload)
        output.write(buf.array()); output.flush()
    }

    @Throws(Exception::class)
    private fun readPacket(input: InputStream): Packet {
        val header = ByteArray(HEADER_LENGTH)
        readFully(input, header)
        val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val p = Packet(buf.getInt(), buf.getInt(), buf.getInt(), buf.getInt(), buf.getInt(), buf.getInt())
        if (p.len < 0 || p.len > MAX_ADB_PAYLOAD) throw java.io.IOException("bad len: ${p.len}")
        if (p.len > 0) { p.data = ByteArray(p.len); readFully(input, p.data) }
        return p
    }

    @Throws(Exception::class)
    private fun readFully(input: InputStream, buf: ByteArray) {
        var off = 0
        while (off < buf.size) { val r = input.read(buf, off, buf.size - off); if (r < 0) throw java.io.IOException("closed"); off += r }
    }

    private fun feedOthers(output: OutputStream, msg: Packet) {
        when (msg.cmd) {
            CMD_WRTE -> sendPacket(output, CMD_OKAY, msg.arg1, msg.arg0, null)
            CMD_CLSE -> sendPacket(output, CMD_CLSE, msg.arg1, msg.arg0, null)
        }
    }

    private fun checksum(d: ByteArray?): Int {
        if (d == null) return 0; var s = 0; for (b in d) s += b.toInt() and 0xFF; return s
    }

    private data class Packet(val cmd: Int, val arg0: Int, val arg1: Int, val len: Int, val csum: Int, val magic: Int, var data: ByteArray = ByteArray(0))
}
