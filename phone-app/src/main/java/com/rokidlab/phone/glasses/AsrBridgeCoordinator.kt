// Rokid SDK 的 sendCustomCmd 自带「注意 Caps 体积」的废弃标记，
// 但这是当前唯一的下行通道，短期内不可能替换，故整文件抑制该告警。
@file:Suppress("DEPRECATION")

package com.rokidlab.phone.glasses

import android.content.Context
import android.util.Log
import com.rokid.cxr.Caps
import com.rokid.cxr.link.CXRLink
import com.rokidlab.phone.ai.MusicPlayerController
import com.rokidlab.phone.util.LogCollector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/**
 * ASR 桥接协调器（从 CxrLHiRokidSession 拆出）。
 *
 * 职责：
 *  - ASR 文字双通道接收：AsrPushClient（第二 RFCOMM 长连接，主通道）+ ADB 文件轮询（兜底）；
 *  - 双通道去重（3s 内相同文字只处理一次）；
 *  - RFCOMM 控制标记分发（中止 AI / 停音乐 / 拍照答题 / ASR 就绪）；
 *  - 下行存活探测 ping（驱动眼镜端 RokidLink 断线路由自愈）。
 *
 * 生命周期：连接建立时 start()，断开/清理时 stop()，由会话编排层驱动。
 */
internal class AsrBridgeCoordinator(
    private val appContext: Context,
    private val appScope: CoroutineScope,
    private val linkProvider: () -> CXRLink?,
    /** CXR-L 链路是否已连接（下行 ping 仅在存活期发送） */
    private val linkAlive: () -> Boolean,
    /** 与下行主链路（KeyDown/open/ASR/TTS）共用的按条串行锁 */
    private val cmdLock: Any,
    /** 去重后的 ASR 文字回调（切到会话的 AI 对话分发核心）。
     *  注意：名字不得与下方 [onAsrText] 方法同名，否则方法体内 this 引用会解析为递归调用自身导致栈溢出。 */
    private val asrDeliver: (String) -> Unit,
    /** 收到「用户关闭助手」中止标记：停眼镜端播报 + 取消运行中的 Lab 模型请求 */
    private val onAbortAi: () -> Unit,
    /** 收到「拍照答题」标记：启动拍照问答流程 */
    private val onPhotoAsk: () -> Unit,
    /** 收到 AIUI 页面工具调用（TOOL_CALL_PREFIX + JSON 载荷）：交 ToolGateway 执行并回传结果 */
    private val onToolCall: (String) -> Unit,
    /**
     * 全 App 共享的 ADB 会话提供者（= `app.cxrL::getAdbShellClient`）。
     *
     * 兜底轮询**必须**复用它，绝不能自建会话：手机侧蓝牙栈对「同一设备 + 同一 SCN」
     * 只允许一条客户端 RFCOMM 通道，自建的第二条会把用户正在用的 ADB 工具 / 屏幕镜像 /
     * 手机投屏会话挤断（实测：自建会话成功建链后 1.5s 内，对端 Tunnel connection closed）。
     */
    private val adbClientProvider: () -> com.rokidlab.phone.adb.AdbShellClient? = { null },
) {
    companion object {
        private const val TAG = "AsrBridgeCoordinator"

        /** AI 文字轮询间隔：主通道为 RFCOMM 推送（毫秒级），文件轮询仅作推送断开时的兜底。
         *  蓝牙隧道（RFCOMM）仅支持单串行连接，高频打隧道会令 5556 等本地端口接收积压溢出被拒，
         *  因此推送通道健康时跳过轮询，断开时才以兜底间隔读取。 */
        private const val AI_ASR_POLL_INTERVAL_MS = 5000L
        /** 隧道异常退避上限：轮询连接失败时指数退避，避免推送断开期间持续打隧道导致 5556 报错 */
        private const val AI_ASR_BACKOFF_MAX_MS = 30_000L
        /** 下行存活探测周期：连接存活期间每 60s 下发一条 ping（AiChannel.TOPIC_PING）。
         *  眼镜端 RokidLink 断线重连后 cxr-service 分发路由可能 stale（订阅返回 0 但实际不投递，
         *  ai_config/tts/show_main 全部静默丢失，仅进程重启可恢复）——RokidLink 以「重连后
         *  150s 内是否收到过任意下行（含本 ping）」判定路由失效并自杀重启。 */
        private const val DOWNLINK_PING_INTERVAL_MS = 60_000L
        /** ASR 文字文件通道：眼镜端把 ASR_TEXT 追加写入该文件，手机端轮询 tail 读取。
         *  logcat 缓冲会被眼镜高频系统日志数秒内冲掉，文件通道保证可靠读到 */
        private const val GLASSES_ASR_FILE = "/sdcard/Android/data/com.rokidlab.rokidlink/files/ai_asr.log"
        private const val KEY_LAST_ASR_TS = "ai_asr_last_ts"

        /** 眼镜端「双击退出对话窗口」时经 RFCOMM 推送通道上行到手机的音乐停止标记 */
        private val MUSIC_STOP_MARKER = LinkProtocol.MARKER_MUSIC_STOP
        /**
         * 眼镜端「用户关闭助手/退出对话」时经 RFCOMM 推送通道上行到手机的中止标记：
         * 手机收到后停止音乐、下发 tts_stop 停眼镜端播报，并取消正在运行的 Lab 模型请求
         * （bump aiGenSeq 让 deepSeekThread 在检查点自弃），避免退出后模型继续生成、
         * 跑完又下行 tts_play 造成"关了助手语音还复活"。
         */
        private val ABORT_AI_MARKER = LinkProtocol.MARKER_ABORT_AI
        /**
         * 眼镜端「按键拍照答题」控制指令：经 RFCOMM 推送通道（AsrPushServer）上行，
         * 独立于 AI App 网关（custom cmd 的 rokidlab_photo_ask 可能被网关过滤收不到），
         * 且与 Sys_App_Resume_Change 不同——仅按键才发送，可严格区分「拍照意图」。
         */
        private val PHOTO_ASK_MARKER = LinkProtocol.MARKER_PHOTO_ASK
        /** ASR 识别完成信号（眼镜端 AsrPushServer.CTRL_ASR_READY，经 RFCOMM 通道推送） */
        /** AIUI 页面工具调用上行：本前缀 + JSON 载荷 {cbId,name,args}。
         *  与其他标记不同，它是【前缀】而非整条相等 —— 后面跟着工具参数。 */
        private val TOOL_CALL_PREFIX = LinkProtocol.MARKER_TOOL_CALL
        private val ASR_READY_MARKER = LinkProtocol.MARKER_ASR_READY

        /**
         * 相同文字的「回声抑制」窗口（毫秒）。
         *
         * 只用于抑制双通道（RFCOMM 推送 + ADB 文件轮询）对【同一次识别】的重复上报，
         * 不再作为丢弃用户重复指令的依据：用户连说两次「停止播放」应当执行两次。
         * 真正的「上一条还没处理完」判定交由 [asrHandlingSince]（见 [onAsrText]）。
         */
        private const val ASR_DUP_ECHO_MS = 1500L

        /**
         * 「上一条 ASR 仍在处理中」的最长判定时间（毫秒）。
         * 处理中又来了完全相同的文字才丢弃（避免并发触发两次 AI 下行加剧链路竞态）；
         * 加超时上限是防止异常路径下标志卡死，导致该指令被永久吞掉。
         */
        private const val ASR_HANDLING_MAX_MS = 90_000L

        /** start() 重入防抖窗口：连接建立期 onConnected 与显式调用会连着调两次 start()，
         *  两次都会新建 AsrPushClient 抢同一条 RFCOMM 通道，反而把通道搞断。 */
        private const val START_DEBOUNCE_MS = 1500L

        /** 兜底轮询退避期间的分片唤醒粒度（毫秒）：推送恢复时最多等这么久就补读一次 */
        private const val POLL_WAKE_STEP_MS = 500L
    }

    private var pollJob: Job? = null
    private var pushClient: AsrPushClient? = null
    private var pingJob: Job? = null
    private var lastAsrText = ""
    private var lastAsrTextAt = 0L

    /**
     * ASR 文字单消费者串行队列 + 去重判定锁。
     *
     * 三路入口（RFCOMM 推送 / ADB 文件补读 / CXR 全局指令）原先各自 `Thread{}.start()`
     * 或 `appScope.launch{}` 裸投递：既无排序（后发先至），又让「检查 lastAsrText → 写入
     * lastAsrText」的 check-then-act 在多线程下竞态（同一句话可触发两次 AI 下行）。
     * 现在全部经 [onAsrText] 入队到本单线程，严格 FIFO 且去重判定天然串行。
     */
    private val asrExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "asr-text-consumer").apply { isDaemon = true }
    }
    private val dedupeLock = Any()

    /** 是否处于可接收状态（start→true / stop→false）：stop 后丢弃队列中未处理的陈旧文字 */
    @Volatile private var accepting = false

    /** 上一条 ASR 是否仍在由会话层处理（AI 下行耗时数秒）：处理中又来同文才吞 */
    @Volatile private var asrHandling = false
    @Volatile private var asrHandlingSince = 0L

    /** start() 防抖：记录上次真正执行 start 的时刻 */
    private var lastStartAtMs = 0L

    /** 推送通道上一次是否处于断连态：用于检测「断连→恢复」以触发兜底补读 */
    @Volatile private var pushWasDown = false

    /** 推送恢复时置位，打破兜底轮询退避，立刻补读一次积压文件 */
    @Volatile private var catchUpRequested = false

    /**
     * ASR 文字入口（三路共用：push 推送 / 文件轮询 / CXR 全局指令监听）。
     *
     * 仅做入队（非阻塞）：三路可能来自 RFCOMM 读线程、轮询协程、SDK binder 线程，
     * 统一投递到 [asrExecutor] 单消费者串行执行 —— 保证严格 FIFO（不再后发先至），
     * 并让去重判定与下游触发在同一线程串行（消除 check-then-act 竞态）。
     */
    fun onAsrText(text: String) {
        if (!accepting) {
            Log.i(TAG, "onAsrText: dropped (bridge not accepting)")
            return
        }
        asrExecutor.execute { processAsrText(text) }
    }

    /**
     * 单消费者线程内执行：去重判定 + 交下游。
     *
     * 丢弃判定（与原 3s 硬窗口语义不同）：仅当「文字与上次相同 **且** 上一条仍在处理中」
     * 才丢弃——防止同一句话被双通道（push+文件）或链路回声重复触发两次 AI 下行、加剧链路竞态。
     * 上一条已处理完（或已超时保护）时，用户连说两遍同一指令（如「停止播放」「停止播放」）
     * 应当执行两遍，不再被当成重复指令吞掉。
     */
    private fun processAsrText(text: String) {
        try {
            if (!accepting) return
            synchronized(dedupeLock) {
                val now = System.currentTimeMillis()
                val stillHandling = asrHandling && (now - asrHandlingSince) < ASR_HANDLING_MAX_MS
                if (text == lastAsrText && stillHandling) {
                    Log.i(TAG, "onAsrText: same text while previous still handling, skip (last=${lastAsrTextAt})")
                    return
                }
                lastAsrText = text
                lastAsrTextAt = now
            }
            // 交给真正的下游处理器（会话的 AI 对话分发核心）。切勿写成 onAsrText(text)——
            // 那会递归调用本方法自身导致 StackOverflow。
            asrDeliver(text)
        } catch (e: Exception) {
            Log.e(TAG, "onAsrText error", e)
            LogCollector.e(TAG, "ASR 文字分发失败（下游异常，该条已丢弃）: ${text.take(60)}", e)
        }
    }

    /**
     * 由会话层在处理开始/结束时调用，标记当前 ASR 是否仍在处理。
     * 据此决定后续相同文字是否丢弃（见 [onAsrText]）。
     */
    fun markAsrHandling(busy: Boolean) {
        asrHandling = busy
        if (busy) asrHandlingSince = System.currentTimeMillis()
    }

    /**
     * 启动 ASR 桥接（文件轮询兜底 + RFCOMM 推送主通道 + 下行存活 ping）。
     *
     * 重入防抖：连接建立期 [onConnected] 回调与显式调用可能连着两次进入（间隔 <
     * [START_DEBOUNCE_MS]），第二次直接忽略——避免重复创建 [AsrPushClient] 抢同一条
     * RFCOMM 通道把通道搞断（实测 21:28:46 两个 push client 争相连接致 read failed）。
     * 注意：cleanup/stop 会把 lastStartAtMs 归零，因此「stop 后重新 start」不受防抖影响。
     */
    fun start() {
        val now = System.currentTimeMillis()
        if (now - lastStartAtMs < START_DEBOUNCE_MS) {
            Log.w(TAG, "start: ignored re-entrant call within ${START_DEBOUNCE_MS}ms (last ${now - lastStartAtMs}ms ago)")
            return
        }
        lastStartAtMs = now
        accepting = true
        pollJob?.cancel()
        pollJob = appScope.launch(Dispatchers.IO) {
            val prefs = appContext.getSharedPreferences("adb_prefs", 0)
            var lastTs = prefs.getLong(KEY_LAST_ASR_TS, 0L)
            // 隧道异常退避：连接失败时逐步拉大间隔（5s→10s→…→30s），
            // 推送断开期间不再固定 5s 打一次隧道，避免 5556 本地端口积压溢出
            var backoffMs = AI_ASR_POLL_INTERVAL_MS
            // 分片退避：catchUpRequested 置位（推送恢复）时立刻跳出，不等满退避
            suspend fun delayInterruptible(ms: Long) {
                var left = ms
                while (left > 0 && isActive && !catchUpRequested) {
                    val step = POLL_WAKE_STEP_MS.coerceAtMost(left)
                    delay(step)
                    left -= step
                }
            }
            while (isActive) {
                try {
                    val pushUp = pushClient?.isConnected == true
                    // 断连→恢复：断连期间眼镜端写入文件的 ASR 文字积压在文件里无人读，
                    // 恢复后必须立刻补读一次，否则这些文字永久丢失（眼镜已显示提问却等不到回复）。
                    if (!pushUp || catchUpRequested) {
                        catchUpRequested = false
                        val r = readAiAsrBridgeTextOnce(lastTs)
                        if (r != null) {
                            if (r.tunnelOk) {
                                backoffMs = AI_ASR_POLL_INTERVAL_MS
                            } else {
                                backoffMs = (backoffMs * 2).coerceAtMost(AI_ASR_BACKOFF_MAX_MS)
                                Log.w(TAG, "ASR tunnel unavailable, backoff to ${backoffMs}ms")
                            }
                            r.texts.forEach { (ts, text) ->
                                lastTs = ts
                                prefs.edit().putLong(KEY_LAST_ASR_TS, ts).apply()
                                Log.i(TAG, "ASR via ADB bridge (fallback${if (!pushUp) "/catch-up" else ""}): $text")
                                // 入队到 ASR 单消费者串行队列（严格 FIFO）：下游含下行 sleep + DeepSeek join
                                // 耗时数秒，放队列既避免阻塞轮询线程，又保证积压多条按序处理
                                onAsrText(text)
                            }
                        }
                    } else {
                        // 推送健康：复位退避
                        backoffMs = AI_ASR_POLL_INTERVAL_MS
                    }
                    // 刷新断连态游标（供下一次循环判断恢复）
                    pushWasDown = !pushUp
                } catch (e: Exception) {
                    Log.e(TAG, "aiAsrBridge poll error", e)
                    LogCollector.e(TAG, "ASR 文件兜底轮询异常", e)
                    backoffMs = (backoffMs * 2).coerceAtMost(AI_ASR_BACKOFF_MAX_MS)
                }
                delayInterruptible(backoffMs)
            }
        }
        // 推送通道：第二 RFCOMM 长连接，毫秒级实时接收眼镜端推送（正常主通道）。
        // 眼镜端推送成功时不会写文件，轮询自然无新数据；推送失败才写文件由轮询兜底。
        // onConnected 在每次建链/重连成功时回调：置 catchUpRequested 让兜底轮询立刻补读
        // 断连期间积压的文件文字（替代旧实现「推送恢复就放心不再读文件」导致的丢字）。
        pushClient?.stop()
        pushClient = AsrPushClient(appContext, { text ->
            try {
                // AIUI 页面工具调用（前缀 + JSON 载荷）：不是 ASR 文字，必须最先分流，
                // 否则会被当成用户提问送进对话链路。
                if (text.startsWith(TOOL_CALL_PREFIX)) {
                    val payload = text.substring(TOOL_CALL_PREFIX.length)
                    Log.i(TAG, "AIUI tool call via push channel: ${payload.take(160)}")
                    // 工具可能走外网耗时数秒，切后台线程，避免阻塞 RFCOMM 读线程
                    Thread { onToolCall(payload) }.apply { isDaemon = true; start() }
                    return@AsrPushClient
                }
                // 用户关闭助手标记（眼镜端双击退出对话窗口时推送，新版）：
                // 停止音乐 + 停眼镜端播报 + 取消运行中的 Lab 模型请求
                if (text == ABORT_AI_MARKER) {
                    Log.i(TAG, "Abort-AI marker received from glasses (assistant closed by user)")
                    MusicPlayerController.stop()
                    onAbortAi()
                    return@AsrPushClient
                }
                // 音乐停止标记：眼镜端双击退出对话窗口时推送，收到后停止手机端音乐播放
                if (text == MUSIC_STOP_MARKER) {
                    Log.i(TAG, "Music stop marker received from glasses (conversation exited)")
                    MusicPlayerController.stop()
                    return@AsrPushClient
                }
                // 拍照答题标记：眼镜端镜腿按键时经 RFCOMM 通道推送（可靠区分按键意图，
                // 不依赖 AI App 网关，也不会被真实 resume 事件误触发）
                if (text == PHOTO_ASK_MARKER) {
                    Log.i(TAG, "Photo-ask marker received via RFCOMM push channel")
                    // 拍照+OCR+AI 全流程耗时数秒，切后台线程执行，避免阻塞 RFCOMM 读线程
                    Thread { onPhotoAsk() }.start()
                    return@AsrPushClient
                }
                // ASR 识别完成信号：眼镜端收到官方 ASR_End 后推送，此刻官方识别已完成。
                // 眼镜端 KeyButtonService 已同步本地接管（interruptOfficialLocally + openAiSession +
                // showAiUserText 显示提问），此处只记录不再重复打断——若再发 Ai/open（startNewTalk）
                // 会与眼镜端本地 open 竞态，重置官方会话导致后续 TTS_Result 回复文字不显示
                // （语音正常但文字丢失，实测 12:03 双 open 竞态）。
                if (text == ASR_READY_MARKER) {
                    Log.i(TAG, "ASR_READY received via RFCOMM push (glasses already took over, skip interrupt)")
                    return@AsrPushClient
                }
                // 更新去重游标：推送文字无真实时间戳，用接收时刻作为游标，
                // 防止轮询兜底读到同一条文字重复处理（眼镜端推送失败写文件的场景）。
                val prefs = appContext.getSharedPreferences("adb_prefs", 0)
                prefs.edit().putLong(KEY_LAST_ASR_TS, System.currentTimeMillis()).apply()
                Log.i(TAG, "ASR via push channel: $text")
                onAsrText(text)
            } catch (e: Exception) {
                Log.e(TAG, "asr push handle error", e)
                LogCollector.e(TAG, "ASR 推送通道消息处理异常", e)
            }
        }) {
            // 推送建链/重连成功：唤醒兜底轮询补读积压文字
            catchUpRequested = true
        }
        pushClient?.start()
        Log.i(TAG, "start: started (push + file fallback)")
        // 连接期启动下行存活探测（链接断开/清理时随 stop() 一起停止）
        startDownlinkPing()
    }

    fun stop() {
        accepting = false
        pollJob?.cancel()
        pollJob = null
        pushClient?.stop()
        pushClient = null
        pushWasDown = false
        catchUpRequested = false
        asrHandling = false
        lastStartAtMs = 0L
        stopDownlinkPing()
    }

    // ──────────────────────────────────────────────
    //  下行存活探测（ping）：驱动眼镜端 RokidLink 断线自愈
    // ──────────────────────────────────────────────

    /** 启动下行存活探测：连接存活期间每 DOWNLINK_PING_INTERVAL_MS（60s）下发一条
     *  AiChannel.TOPIC_PING 空消息。眼镜端 RokidLink 收到即刷新下行活性；
     *  断线重连后若分发路由 stale（收不到任何下行）则在观察窗口到期后自杀重启。 */
    private fun startDownlinkPing() {
        pingJob?.cancel()
        pingJob = appScope.launch(Dispatchers.IO) {
            while (isActive) {
                try {
                    val link = linkProvider()
                    if (link != null && linkAlive()) {
                        // 按条加锁与下行主链路（KeyDown/open/ASR/TTS）串行，避免插入指令序列中间
                        val caps = Caps().also { it.write("ping") }
                        val r = synchronized(cmdLock) { link.sendCustomCmd(AiChannel.TOPIC_PING, caps) }
                        if (r == 0) {
                            Log.d(TAG, "downlink ping sent (${AiChannel.TOPIC_PING})")
                        } else {
                            Log.w(TAG, "downlink ping sendCustomCmd(${AiChannel.TOPIC_PING}) -> $r")
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "downlink ping error", e)
                    LogCollector.w(TAG, "下行存活 ping 失败", e)
                }
                delay(DOWNLINK_PING_INTERVAL_MS)
            }
        }
    }

    private fun stopDownlinkPing() {
        pingJob?.cancel()
        pingJob = null
    }

    // ──────────────────────────────────────────────
    //  ADB 文件通道（兜底读取）
    // ──────────────────────────────────────────────

    /**
     * 取得全 App 共享的 ADB 会话（由 [adbClientProvider] 提供）。
     *
     * 历史教训：这里原先会 `AdbShellClient(...)` 自建一条「一次性短连接」。设计意图是
     * "避免后台轮询长连接独占蓝牙隧道"，但实际效果相反 —— 每次自建都等于在同一条
     * RFCOMM SCN 上抢一条客户端通道，**把用户正在用的会话挤断**，于是出现
     * 「打开镜像正常，但 ADB 工具 / 投屏用着用着就不行了，切走再回来又好了」。
     *
     * 现在只复用共享会话；拿不到就返回 null，由调用方退避，等下一拍再试。
     * 必须在后台线程调用（同步阻塞握手）。
     */
    private fun sharedAdbClient(): com.rokidlab.phone.adb.AdbShellClient? =
        // catch-ok: 拿不到共享会话属正常退避路径（轮询每拍都调），落日志会刷屏；
        // 真因由 readAiAsrBridgeTextOnce 的 catch 落面板，此处不再重复
        runCatching { adbClientProvider() }.getOrNull()

    /** 文件通道轮询单次结果：texts=按时间升序的新文本（可能为空）；tunnelOk=隧道连接是否成功 */
    private data class AsrBridgeRead(val texts: List<Pair<Long, String>>, val tunnelOk: Boolean)

    /**
     * 通过 ADB 读取眼镜端 ai_asr.log 中时间戳大于 lastTs 的**全部**新 ASR_TEXT，按行序（时间升序）返回。
     * 返回 AsrBridgeRead：tunnelOk=false 表示隧道不可用（调用方应退避，避免持续打隧道），
     * texts 为空表示隧道正常但无新文本。
     *
     * 为什么返回全部而不是最后一条：推送断开 → 退避最长 30s，期间眼镜端写入的多条文字会积压，
     * 若只取最后一条并把游标直推最新，前面的积压将被永久跳过（用户已提问却永远等不到回复）。
     *
     * 注意：共享会话由 [com.rokidlab.phone.glasses.CxrLHiRokidSession.getAdbShellClient]
     * 统一管理生命周期，本次读取**不得** disconnect（否则会掐断其它消费者正在用的会话）。
     */
    private fun readAiAsrBridgeTextOnce(lastTs: Long): AsrBridgeRead? {
        val client = sharedAdbClient() ?: return AsrBridgeRead(emptyList(), false)
        return try {
            val out = runCatching {
                client.executeShellCommand("tail -n 20 $GLASSES_ASR_FILE 2>/dev/null", 10_000)
            }.getOrNull()
            if (out == null) return AsrBridgeRead(emptyList(), false)
            // 文件为追加写，行序即时间序；首行可能是 tail 截断的半行（正则不匹配）自然被丢弃
            val fresh = out.lineSequence()
                .mapNotNull { line ->
                    // 每行格式：[epochMs] text；解析失败（半行/脏数据）则忽略
                    val m = Regex("""\[(\d+)\] (.*)""").matchEntire(line.trim()) ?: return@mapNotNull null
                    val ts = m.groupValues[1].toLongOrNull() ?: return@mapNotNull null
                    val text = m.groupValues[2].trim()
                    if (text.isEmpty()) null else ts to text
                }
                .filter { it.first > lastTs }
                .distinctBy { it.first }
                .toList()
            // 隧道连接成功；fresh 可能为空（无新数据）
            AsrBridgeRead(fresh, true)
        } catch (e: Exception) {
            // 原先此处完全静默：ASR 丢字时日志里只剩上游一句「tunnel unavailable」，
            // 看不到真因（读取超时 / 会话已死 / 命令被拒）。补落 App 内日志面板。
            LogCollector.w(TAG, "读取眼镜端 ASR 文件失败（隧道不可用语义）", e)
            AsrBridgeRead(emptyList(), false)
        }
    }
}
