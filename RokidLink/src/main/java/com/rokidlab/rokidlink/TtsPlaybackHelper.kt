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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
 *  - 分块串行播放：单线程 playExecutor 逐块 invoke，每块携带 ITtsListener 并
 *    阻塞等待 onTtsStop（音频播放结束）回调后再发下一块——无需估算延时、块间
 *    由真实播放进度自然衔接，同时规避 TtsService 对"正在准备/刚播完"状态下
 *    的新消息 stop 当前流导致的丢块
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

    /** ITtsListener AIDL 接口描述符（播放完成回调） */
    private const val TTS_LISTENER_DESCRIPTOR = "com.rokid.os.sprite.tts.ITtsListener"

    /** bind 失败最大重试次数 */
    private const val MAX_BIND_RETRIES = 3

    /** 绑定/重绑失败的重试间隔（ms） */
    private const val BIND_RETRY_DELAY_MS = 2_000L

    /**
     * 绑定超时看护（ms）：bindService 返回 true 后若 TtsService 长期不回调
     * onServiceConnected（服务崩溃/被系统限时），bindPending 会卡死导致后续
     * 播放全部入队不执行。超时后重置 bindPending 并重新发起绑定。
     */
    private const val BIND_TIMEOUT_MS = 5_000L

    /**
     * 单次合成文本上限（字符数）。眼镜本地 ONNX TTS 引擎对超长/含多段换行的文本会报
     * "ONNX Expand node p2o.Expand.2 invalid expand shape" → acoustic_output 为空 → 静音
     * （实测 230 字多段答案失败、70 字单段正常），播放前按标点/换行拆块规避。
     */
    private const val TTS_CHUNK_MAX_CHARS = 80

    /**
     * 单块播放超时（ms）：invoke playTtsMsg 后若长时间收不到 onTtsStop 完成回调
     * （listener 回调丢失/TtsService 异常），超时后继续播下一块，防止串行播放卡死。
     * 正常 80 字块约 16s 播完，30s 余量充足。
     */
    private const val TTS_PLAY_TIMEOUT_MS = 30_000L

    /**
     * 块间状态同步缓冲（ms）：onTtsStop 回调时音频虽已播完，但 TtsService 的
     * playTask 线程仍在收尾（playFinish=true/playFuture=null 尚未完成）。若此时
     * 立即 invoke 下一块会落入"正在播放"分支触发 stop。等待固定 300ms（句间自然
     * 停顿量级）确保状态就绪后再发下一块，避免额外丢块。
     */
    private const val STABLE_DELAY_MS = 300L

    /** 串行播放执行器：保证 TTS 块逐个播放，杜绝连续 invoke 导致 TtsService 丢块 */
    private val playExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "tts-play") }

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 播放代际号：play()/stop() 每次递增。排队中的旧任务每播一块前比对代际，
     * 不一致立即退出——实现「新播报打断旧播报」与「退出对话同步停 TTS」，
     * 且无需从单线程执行器里抢删任务（FIFO 队列中的陈旧任务会自动快速自停）。
     */
    @Volatile
    private var playEpoch = 0L

    /** 当前分块正在等待播放完成回调（onTtsStop）的闩锁，stop() 释放它以提前结束等待 */
    @Volatile
    private var activeLatch: CountDownLatch? = null

    /**
     * **已经 invoke 进 TtsService、正在发声的那一块**的 uuid（没有则为 null）。
     *
     * 这是 [stop] 能真正静音的唯一凭据：`playEpoch`/`activeLatch` 只作用于
     * "还没进 TtsService 的分块"与"正在等待回调的分块"，已 invoke 出去的音频
     * 仍会在 `:tts` 进程里播到自然结束（80 字块约 16s）——用户按"停止"还要听完
     * 当前块，就是这么来的。只有 `ITtsServer.stopTtsPlay(本 uuid)` 能停它。
     *
     * 仅 playExecutor 线程写入（见 [invokePlayTtsMsg]），故 volatile 读写足够。
     */
    @Volatile
    private var playingUuid: String? = null

    @Volatile
    private var bound = false
    @Volatile
    private var ttsServer: IBinder? = null
    private var bindRetryCount = 0
    /** bind 进行中标志：防止 play/ensureBound/disconnect 重绑并发触发多次 bindService */
    @Volatile
    private var bindPending = false

    /** 最近一次用于 bind 的 Context（进程级），重绑时复用 */
    @Volatile
    private var bindAppContext: Context? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.i(TAG, "TtsService connected")
            synchronized(this@TtsPlaybackHelper) {
                bound = true
                ttsServer = service
                bindRetryCount = 0
                bindPending = false
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

    /**
     * 播放指定文字的本地 TTS 语音。
     *
     * 串行播放 + 完成回调：所有分块提交到单线程 playExecutor 逐个播放。
     * 每块 invoke 时携带 ITtsListener，**等待 onTtsStop（音频播放结束）回调后再发下一块**，
     * 块间由真实播放进度自然衔接，无需估算延时，也不会因提前下发触发 TtsService 的
     * stop 丢块。本方法自身不 sleep（可能在桥接回调线程被调用），实际播放逻辑全部
     * 在 playExecutor 线程。
     */
    fun play(context: Context, text: String, onFinished: (() -> Unit)? = null) {
        if (text.isBlank()) {
            Log.w(TAG, "play: text is blank, ignored")
            return
        }
        // 新播报抢占新代际：旧播报的排队分块/正在等待的分块立即自停（多轮连续回复不叠声）
        val epoch = synchronized(this) { ++playEpoch }
        activeLatch?.countDown()
        // 分块播放：规避 ONNX 引擎对超长文本的 Expand 形状错误（见 TTS_CHUNK_MAX_CHARS 注释）
        val chunks = splitForTts(text)
        if (chunks.size > 1) Log.i(TAG, "play: split ${text.length} chars into ${chunks.size} chunks")
        playExecutor.execute {
            for ((i, chunk) in chunks.withIndex()) {
                if (epoch != playEpoch) {
                    Log.i(TAG, "play superseded before chunk ${i + 1} (epoch $epoch != $playEpoch), stop queue")
                    return@execute
                }
                waitUntilBound(context)
                if (epoch != playEpoch) {
                    Log.i(TAG, "play superseded during bind wait, stop queue")
                    return@execute
                }
                val server = ttsServer
                if (server == null) {
                    Log.w(TAG, "TtsService not bound within ${BIND_TIMEOUT_MS}ms, dropping: \"${chunk.take(20)}...\"")
                    break
                }
                Log.i(TAG, "chunk ${i + 1}/${chunks.size} playing (${chunk.length}字)")
                invokePlayTtsMsg(server, chunk)
            }
            // 所有分块播完 —— 这是「本轮语音真正播完」的唯一可靠时刻（由 ITtsListener.onTtsStop
            // 驱动，无需估算时长）。代际一致才回调：被新播报抢占或用户 stop() 主动停播时，
            // 循环内已 return@execute，本行不会执行；此处再比一次是防并发窗口的双保险。
            if (epoch == playEpoch) {
                Log.i(TAG, "play finished: ${chunks.size} chunk(s), notify onFinished")
                runCatching { onFinished?.invoke() }
                    .onFailure { Log.e(TAG, "onFinished callback error", it) }
            } else {
                Log.i(TAG, "play finished but superseded (epoch $epoch != $playEpoch), skip onFinished")
            }
        }
    }

    /**
     * 立即停止播放（用户停止 / 退出对话窗口 / 眼镜端「关闭助手」）。
     *
     * 两步都要，缺一不可：
     *  1. `playEpoch++` + 释放闩锁 —— 作废"排队中/正在等待的分块"，让播放线程立刻退出循环；
     *  2. `ITtsServer.stopTtsPlay(playingUuid)` —— **真正静音已经在响的那一块**。
     *     第 1 步只管我们自己的状态，对已经 invoke 进 TtsService 的音频无效（它会照播到底）。
     *
     * 第 2 步提交到 [playExecutor] 执行：与 `invokePlayTtsMsg` 天然串行、不会与新的 play
     * 交错；且此刻闩锁刚被释放、播放循环马上返回，轮到它几乎是立即的。
     */
    fun stop() {
        synchronized(this) { playEpoch++ }
        activeLatch?.countDown()
        val uuid = playingUuid
        Log.i(TAG, "stop requested (epoch=$playEpoch, playingUuid=$uuid)")
        if (uuid != null) {
            runCatching { playExecutor.execute { stopTtsPlay(uuid) } }
                .onFailure { Log.w(TAG, "stop: submit stopTtsPlay failed: ${it.message}") }
        }
    }

    /**
     * 阻塞等待 TtsService 绑定就绪（最多 [BIND_TIMEOUT_MS]）。
     * 仅在 playExecutor 线程调用（此时 sleep 无 ANR 风险）。
     */
    private fun waitUntilBound(context: Context) {
        var waited = 0L
        while (!bound || ttsServer == null) {
            if (waited == 0L) {
                bindAppContext?.let { bind(it) } ?: bind(context)
            }
            if (waited >= BIND_TIMEOUT_MS) return
            try {
                Thread.sleep(200)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
            waited += 200
        }
    }

    /**
     * 将待播文本拆分为 TTS 引擎安全的分块（每块 ≤ [TTS_CHUNK_MAX_CHARS]）。
     * 先按句末标点（。！？；）与换行切分，超长句再按逗号/顿号切分，保证语义尽量完整。
     */
    private fun splitForTts(text: String): List<String> {
        val out = mutableListOf<String>()
        val sentences = text.split(Regex("(?<=[。！？；\\n])"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        for (s in sentences) {
            if (s.length <= TTS_CHUNK_MAX_CHARS) {
                out.add(s)
            } else {
                val cur = StringBuilder()
                for (ch in s) {
                    cur.append(ch)
                    if (cur.length >= TTS_CHUNK_MAX_CHARS || ch == '，' || ch == '、') {
                        val piece = cur.toString().trim()
                        if (piece.isNotEmpty()) out.add(piece)
                        cur.setLength(0)
                    }
                }
                if (cur.isNotEmpty()) out.add(cur.toString().trim())
            }
        }
        return out.ifEmpty { listOf(text) }
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
            } else {
                // 绑定超时看护：bindService 成功但 onServiceConnected 长时间未回调
                //（TtsService 崩溃/被系统限时）时解除 bindPending 卡死，重新发起绑定
                mainHandler.postDelayed({
                    if (bindPending && !bound) {
                        Log.w(TAG, "bind timeout (${BIND_TIMEOUT_MS}ms), rebinding")
                        bindPending = false
                        bind(context)
                    }
                }, BIND_TIMEOUT_MS)
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
            }
            bindAppContext = null
        }
    }

    /**
     * ITtsListener 回调实现（Binder 直通，不依赖系统私有接口类）：
     * 手动解析 TtsService 对 listener 的跨进程回调。接口定义（逆向自 assistserver）：
     *  - transaction 1 = onTtsStart(String uuid) — 音频开始播放
     *  - transaction 2 = onTtsStop(String uuid)  — 音频播放结束
     */
    private class TtsListenerBinder : android.os.Binder() {
        /** onTtsStop 回调（音频播放结束，携带 uuid） */
        @Volatile
        var onStop: ((String?) -> Unit)? = null

        @Volatile
        var onStart: ((String?) -> Unit)? = null

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            try {
                data.enforceInterface(TTS_LISTENER_DESCRIPTOR)
                when (code) {
                    1 -> { // onTtsStart(String uuid)
                        val uuid = data.readString()
                        onStart?.invoke(uuid)
                        reply?.writeNoException()
                        return true
                    }
                    2 -> { // onTtsStop(String uuid)
                        val uuid = data.readString()
                        onStop?.invoke(uuid)
                        reply?.writeNoException()
                        return true
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "ITtsListener transact error: code=$code", e)
            }
            return super.onTransact(code, data, reply, flags)
        }
    }

    /**
     * 调 `ITtsServer.stopTtsPlay(String uuid)`（transaction 2）**真正停止音频播放**。
     *
     * 为什么需要它：`playEpoch`/`activeLatch` 只是本进程的状态，作用范围是"还没进
     * TtsService 的分块"与"正在等待回调的分块"。已经 invoke 出去的那一块音频在 `:tts`
     * 进程里继续发声 —— 用户按"停止"仍要听完当前块（80 字块 ≈ 16s），根因就在这里。
     *
     * 安全性：`TtsService.stopTtsPlay` 的实现是
     * `if (ttsData == null || uuid 为空) return;` → `if (!ttsData.uuid.equals(arg)) return;`
     * → `audioPlayer.stop(false)` ⇒ **只传我们自己刚发出的 uuid**：即便该条已被新播放顶掉，
     * 服务端也只会安全返回，不可能误停别人的音频（官方各调用方也是"自己记自己那份 uuid"）。
     */
    private fun stopTtsPlay(uuid: String) {
        val binder = ttsServer
        if (binder == null) {
            Log.w(TAG, "stopTtsPlay($uuid): binder is null, skipped")
            return
        }
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(TTS_INTERFACE_DESCRIPTOR)
            data.writeString(uuid)
            binder.transact(2, data, reply, 0)
            reply.readException()
            Log.i(TAG, "stopTtsPlay(uuid=$uuid) invoked")
        } catch (e: Exception) {
            Log.e(TAG, "stopTtsPlay($uuid) failed", e)
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    /**
     * 调用 ITtsServer.playTtsMsg(String msg, String uuid, ITtsListener listener)，
     * 携带 listener 后**阻塞等待 onTtsStop 完成回调**（真实播放结束），再返回让
     * playExecutor 继续播下一块——块间零估算延时、自然连贯。
     * 仅在 playExecutor 线程调用（阻塞等待无 ANR 风险）；超时 [TTS_PLAY_TIMEOUT_MS]
     * 兜底，防止 listener 回调丢失时卡死。调用异常时自动重试 1 次。
     */
    private fun invokePlayTtsMsg(binder: IBinder?, text: String) {
        if (binder == null) {
            Log.w(TAG, "invokePlayTtsMsg: binder is null")
            return
        }
        val uuid = UUID.randomUUID().toString()
        val latch = CountDownLatch(1)
        val listener = TtsListenerBinder().apply {
            onStart = { cbUuid ->
                Log.d(TAG, "onTtsStart: $cbUuid")
            }
            onStop = { cbUuid ->
                if (cbUuid == uuid) {
                    Log.d(TAG, "onTtsStop: $cbUuid")
                    latch.countDown()
                }
            }
        }
        var success = false
        repeat(2) { attempt ->
            // 注意：必须用 return@repeat（局部返回 lambda），不能写 return——
            // 否则第二次循环会非局部返回整个函数，跳过下方 latch.await（块间串行等待失效）
            if (success) return@repeat
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(TTS_INTERFACE_DESCRIPTOR)
                data.writeString(text)
                data.writeString(uuid)
                data.writeStrongBinder(listener)
                binder.transact(1, data, reply, 0)
                reply.readException()
                success = true
                // 记下"正在发声的那一块"，供 stop() 调 stopTtsPlay 真正静音（见字段注释）
                playingUuid = uuid
                Log.i(TAG, "playTtsMsg invoked (uuid=$uuid): \"${text.take(30)}...\" waiting onTtsStop")
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
            return
        }
        // 等待真实播放结束（onTtsStop）；超时兜底继续下一块。
        // 注册 activeLatch：stop() 会 countDown 让等待提前结束（退出对话同步停播）。
        activeLatch = latch
        val finished = latch.await(TTS_PLAY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        activeLatch = null
        // 这一块已经结束（自然播完 / 被 stop 静音 / 超时兜底）⇒ 别再把陈旧 uuid 留给下一次 stop
        if (playingUuid == uuid) playingUuid = null
        if (!finished) {
            Log.w(TAG, "onTtsStop timeout after ${TTS_PLAY_TIMEOUT_MS}ms: \"${text.take(30)}...\"")
        }
        // 状态同步：等 TtsService 的 playTask 收尾完成（playFinish/playFuture 归位），
        // 避免下一块 invoke 落入"正在播放"分支触发 stop 丢块
        try {
            Thread.sleep(STABLE_DELAY_MS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
