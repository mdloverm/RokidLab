package com.rokidlab.rokidlink

import android.content.Context
import android.util.Log
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * AIUI .aix 推送接收服务（端口 7658）：
 * 手机端把生成的 .aix 字节流推过来，落盘到 filesDir/aiui_host/<name>.aix，
 * 完成后回调 [Listener.onPackageReceived]，由外部拉起 [AiuiLinkActivity]（自托管 Web 宿主）。
 *
 * 协议（一次连接，单客户端）：
 *   [2B big-endian fileNameLen][fileName utf8]
 *   [4B big-endian fileLen][file bytes]
 *   ack: 'OK'
 */
class AiuiPackageServer(
    private val port: Int = 7658,
    private val listener: Listener? = null,
) {
    interface Listener {
        fun onPackageReceived(file: File)
    }

    companion object {
        private const val TAG = "AiuiPackageServer"
        private const val MAX_NAME = 512
        private const val MAX_FILE = 64 * 1024 * 1024 // 64MB 上限
        const val DIR_NAME = "aiui_host"
    }

    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private val running = AtomicBoolean(false)
    private var acceptThread: Thread? = null
    private val lock = Any()
    @Volatile
    private var hostDir: File? = null

    val isRunning: Boolean get() = running.get()

    /** 最近一次收到的 .aix（供 "open" 命令不带名时使用） */
    @Volatile
    private var lastPackage: File? = null

    fun lastReceived(): File? = lastPackage

    fun start(context: Context): Boolean {
        if (!running.compareAndSet(false, true)) return false
        hostDir = File(context.filesDir, DIR_NAME).apply { mkdirs() }
        return try {
            val server = ServerSocket()
            server.reuseAddress = true
            server.bind(InetSocketAddress(port))
            serverSocket = server
            Log.i(TAG, "listening :$port dir=${hostDir?.absolutePath}")
            acceptThread = Thread { waitForClient() }
                .apply { name = "aiui-pkg-accept"; start() }
            true
        } catch (e: Exception) {
            Log.e(TAG, "bind fail", e)
            running.set(false)
            false
        }
    }

    private fun waitForClient() {
        while (running.get()) {
            try {
                val sock = serverSocket?.accept() ?: break
                synchronized(lock) {
                    if (!running.get()) {
                        runCatching { sock.close() }
                        return
                    }
                    clientSocket = sock
                }
                sock.tcpNoDelay = true
                handleClient(sock)
            } catch (e: Exception) {
                if (running.get()) Log.w(TAG, "accept err: ${e.message}")
            }
        }
    }

    private fun handleClient(sock: Socket) {
        val dir = hostDir
        if (dir == null) {
            runCatching { sock.close() }
            return
        }
        runCatching { dir.mkdirs() }
        try {
            val input = BufferedInputStream(sock.getInputStream(), 32 * 1024)
            val output = BufferedOutputStream(sock.getOutputStream())
            // fileName
            val nameLen = readExact(input, 2)
            val nameLenInt = ((nameLen[0].toInt() and 0xFF) shl 8) or (nameLen[1].toInt() and 0xFF)
            if (nameLenInt <= 0 || nameLenInt > MAX_NAME) throw IllegalStateException("bad name len $nameLenInt")
            val nameBytes = readExact(input, nameLenInt)
            var name = String(nameBytes, Charsets.UTF_8)
            if (!name.endsWith(".aix")) name = "$name.aix"
            val target = File(dir, name.replace(Regex("[^A-Za-z0-9._-]"), "_"))
            // file bytes
            val lenBytes = readExact(input, 4)
            val len = ((lenBytes[0].toInt() and 0xFF) shl 24) or ((lenBytes[1].toInt() and 0xFF) shl 16) or
                ((lenBytes[2].toInt() and 0xFF) shl 8) or (lenBytes[3].toInt() and 0xFF)
            if (len <= 0 || len > MAX_FILE) throw IllegalStateException("bad file len $len")
            val tmp = File(dir, name + ".tmp")
            BufferedOutputStream(tmp.outputStream(), 32 * 1024).use { out ->
                var remaining = len
                val buf = ByteArray(64 * 1024)
                while (remaining > 0) {
                    val r = input.read(buf, 0, minOf(buf.size, remaining))
                    if (r == -1) throw IllegalStateException("conn closed")
                    out.write(buf, 0, r)
                    remaining -= r
                }
            }
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            output.write("OK".toByteArray())
            output.flush()
            lastPackage = target
            Log.i(TAG, "saved ${target.name} ${len}B -> ${target.absolutePath}")
            listener?.onPackageReceived(target)
        } catch (e: Exception) {
            Log.e(TAG, "receive fail: ${e.message}")
        } finally {
            synchronized(lock) { clientSocket = null }
            runCatching { sock.close() }
        }
    }

    private fun readExact(input: BufferedInputStream, n: Int): ByteArray {
        val out = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(out, off, n - off)
            if (r == -1) throw IllegalStateException("conn closed")
            off += r
        }
        return out
    }

    fun stop() {
        running.set(false)
        runCatching { serverSocket?.close() }
        synchronized(lock) { runCatching { clientSocket?.close() } }
        serverSocket = null
        acceptThread = null
    }
}
