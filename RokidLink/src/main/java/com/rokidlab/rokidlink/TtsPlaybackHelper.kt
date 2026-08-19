package com.rokidlab.rokidlink

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.util.Log
import java.util.UUID

/**
 * 眼镜端本地 TTS 播放器。
 *
 * 绑定系统 TtsService（action = com.rokid.os.sprite.tts.TTS_SERVICE），
 * 通过 ITtsServer.playTtsMsg() 让眼镜本地 TTS 引擎合成并播放语音，
 * 无需手机端流式传输音频。
 *
 * 说明：不依赖系统私有接口类，直接手动构造 Parcel 调用 Binder transaction 1
 * （playTtsMsg），AIDL 描述符为 com.rokid.os.sprite.tts.ITtsServer。
 *
 * 加固说明（解决偶发不播报）：
 *  - bindService 返回 false → 延迟重试（最多 [MAX_BIND_RETRIES] 次）
 *  - onServiceDisconnected → 自动延迟重绑，服务掉线后恢复自愈
 *  - playTtsMsg 调用异常 → 自动重试 1 次
 *  - 多文本用队列缓存，onServiceConnected 后逐条播放（替代原单槽 pendingText）
 *  - 对外暴露 ensureBound()，供 KeyButtonService 启动时预热绑定
 */
object TtsPlaybackHelper {

    private const val TAG = "TtsPlaybackHelper"

    /** TtsService 的 Intent action（来自系统 assistserver） */
    private const val TTS_SERVICE_ACTION = "com.rokid.os.sprite.tts.TTS_SERVICE"

    /** TtsService 所在应用 */
    private const val TTS_SERVICE_PACKAGE = "com.rokid.os.sprite.assistserver"

    /** TtsService 组件类名 */
    private const val TTS_SERVICE_CLASS = "com.rokid.os.sprite.tts.TtsService"

    /** ITtsServer AIDL 接口描述符 */
    private const val TTS_INTERFACE_DESCRIPTOR = "com.rokid.os.sprite.tts.ITtsServer"

    /** bind 失败最大重试次数 */
    private const val MAX_BIND_RETRIES = 3

    /** 绑定/重绑失败的重试间隔（ms） */
    private const val BIND_RETRY_DELAY_MS = 2_000L

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var bound = false
    @Volatile
    private var ttsServer: IBinder? = null
    private var bindRetryCount = 0
    /** bind 进行中标志：防止 play/ensureBound/disconnect 重绑并发触发多次 bindService */
    @Volatile
    private var bindPending = false

    /** 待播文本队列（服务未就绪时缓存，连接后逐条消费） */
    private val pendingQueue = ArrayDeque<String>()

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.i(TAG, "TtsService connected")
            var snapshot: List<String>? = null
            synchronized(this@TtsPlaybackHelper) {
                bound = true
                ttsServer = service
                bindRetryCount = 0
                bindPending = false
                // 快照出队当前全部待播文本，避免在主线程 while(true) 消费 + transact/sleep 造成 ANR
                snapshot = ArrayList(pendingQueue)
                pendingQueue.clear()
            }
            if (snapshot != null && snapshot!!.isNotEmpty()) {
                val consumer = Thread { snapshot!!.forEach { invokePlayTtsMsg(service, it) } }
                consumer.name = "tts-pending-consumer"
                consumer.start()
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.i(TAG, "TtsService disconnected, scheduling rebind")
            synchronized(this@TtsPlaybackHelper) {
                bound = false
                ttsServer = null
                bindPending = false
            }
            // 服务异常断开 → 自动重绑（该回调必然在主线程，postDelayed 安全）
            mainHandler.postDelayed({ bindAppContext?.let { bind(it) } }, BIND_RETRY_DELAY_MS)
        }
    }

    /** 最近一次用于 bind 的 Context（进程级），重绑时复用 */
    @Volatile
    private var bindAppContext: Context? = null

    /**
     * 播放指定文字的本地 TTS 语音。
     * 若服务尚未绑定，先入队并绑定（连接后自动逐条播放）。
     */
    fun play(context: Context, text: String) {
        if (text.isBlank()) {
            Log.w(TAG, "play: text is blank, ignored")
            return
        }
        val server = ttsServer
        if (bound && server != null) {
            invokePlayTtsMsg(server, text)
        } else {
            Log.i(TAG, "TtsService not bound yet, queueing text")
            synchronized(this) { pendingQueue.addLast(text) }
            bind(context)
        }
    }

    /**
     * 预热绑定：确保 TtsService 已连接，播放时无需等待绑定。
     * KeyButtonService 启动时调用；已绑定或已发起绑定时直接跳过。
     */
    fun ensureBound(context: Context) {
        if (bound && ttsServer != null) return
        bind(context)
    }

    /** 绑定（bindService 失败时自动重试 [MAX_BIND_RETRIES] 次） */
    private fun bind(context: Context) {
        if (bound && ttsServer != null) return
        if (bindPending) return
        bindPending = true
        bindAppContext = context.applicationContext
        try {
            val intent = Intent(TTS_SERVICE_ACTION).apply {
                setPackage(TTS_SERVICE_PACKAGE)
                component = ComponentName(TTS_SERVICE_PACKAGE, TTS_SERVICE_CLASS)
            }
            val ok = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            Log.i(TAG, "bindService(TTS_SERVICE) -> $ok (retry=${bindRetryCount})")
            if (!ok) {
                bindPending = false
                synchronized(this) { bindRetryCount++ }
                if (bindRetryCount < MAX_BIND_RETRIES) {
                    mainHandler.postDelayed({ bind(context) }, BIND_RETRY_DELAY_MS)
                } else {
                    Log.e(TAG, "bindService failed after $MAX_BIND_RETRIES attempts")
                    synchronized(this) { bindRetryCount = 0 }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "bindService failed", e)
            bindPending = false
            synchronized(this) { bindRetryCount++ }
            if (bindRetryCount < MAX_BIND_RETRIES) {
                mainHandler.postDelayed({ bind(context) }, BIND_RETRY_DELAY_MS)
            }
        }
    }

    fun unbind(context: Context) {
        if (bound) {
            runCatching { context.unbindService(connection) }
            synchronized(this) {
                bound = false
                ttsServer = null
                bindPending = false
                pendingQueue.clear()
            }
            bindAppContext = null
        }
    }

    /**
     * 调用 ITtsServer.playTtsMsg(String msg, String uuid, ITtsListener listener)。
     * transaction = 1，listener 传 null 即可（无需回调）。
     * 调用异常时自动重试 1 次（不 sleep：该函数可能被主线程调用，sleep 会导致 ANR）。
     */
    private fun invokePlayTtsMsg(binder: IBinder?, text: String) {
        if (binder == null) {
            Log.w(TAG, "invokePlayTtsMsg: binder is null")
            return
        }
        var success = false
        repeat(2) { attempt ->
            if (success) return
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(TTS_INTERFACE_DESCRIPTOR)
                data.writeString(text)
                data.writeString(UUID.randomUUID().toString())
                data.writeStrongBinder(null)
                binder.transact(1, data, reply, 0)
                reply.readException()
                success = true
                Log.i(TAG, "playTtsMsg invoked: \"${text.take(40)}...\"")
            } catch (e: Exception) {
                Log.e(TAG, "playTtsMsg failed (attempt=${attempt + 1})", e)
            } finally {
                reply.recycle()
                data.recycle()
            }
        }
        if (!success) {
            // 失败不回插队首：否则连接后主循环会反复消费同一文本，形成死循环占用线程
            Log.e(TAG, "playTtsMsg failed after retry, dropping: \"${text.take(40)}...\"")
        }
    }
}
