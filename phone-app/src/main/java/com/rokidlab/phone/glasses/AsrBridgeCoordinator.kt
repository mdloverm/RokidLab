package com.rokidlab.phone.glasses

import android.content.Context
import android.util.Log
import com.rokid.cxr.Caps
import com.rokid.cxr.link.CXRLink
import com.rokidlab.phone.ai.MusicPlayerController
import com.rokidlab.phone.app.LabApplication
import com.rokidlab.phone.connection.ConnectionRoute
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

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
    /** 去重后的 ASR 文字回调（切到会话的 AI 对话分发核心） */
    private val onAsrText: (String) -> Unit,
    /** 收到「用户关闭助手」中止标记：停眼镜端播报 + 取消运行中的 Lab 模型请求 */
    private val onAbortAi: () -> Unit,
    /** 收到「拍照答题」标记：启动拍照问答流程 */
    private val onPhotoAsk: () -> Unit,
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
        private const val MUSIC_STOP_MARKER = "__LAB_MUSIC_STOP__"
        /**
         * 眼镜端「用户关闭助手/退出对话」时经 RFCOMM 推送通道上行到手机的中止标记：
         * 手机收到后停止音乐、下发 tts_stop 停眼镜端播报，并取消正在运行的 Lab 模型请求
         * （bump aiGenSeq 让 deepSeekThread 在检查点自弃），避免退出后模型继续生成、
         * 跑完又下行 tts_play 造成"关了助手语音还复活"。
         */
        private const val ABORT_AI_MARKER = "__LAB_ABORT_AI__"
        /**
         * 眼镜端「按键拍照答题」控制指令：经 RFCOMM 推送通道（AsrPushServer）上行，
         * 独立于 AI App 网关（custom cmd 的 rokidlab_photo_ask 可能被网关过滤收不到），
         * 且与 Sys_App_Resume_Change 不同——仅按键才发送，可严格区分「拍照意图」。
         */
        private const val PHOTO_ASK_MARKER = "__LAB_PHOTO_ASK__"
        /** ASR 识别完成信号（眼镜端 AsrPushServer.CTRL_ASR_READY，经 RFCOMM 通道推送） */
        private const val ASR_READY_MARKER = "__LAB_ASR_READY__"
    }

    private var pollJob: Job? = null
    private var pushClient: AsrPushClient? = null
    private var pingJob: Job? = null
    private var lastAsrText = ""
    private var lastAsrTextAt = 0L

    /**
     * ASR 文字入口（三路共用：push 推送 / 文件轮询 / CXR 全局指令监听）。
     * 双通道去重：3s 内相同文字只处理一次（重复处理会并发触发 AI 下行，加剧 link 竞态）。
     */
    fun onAsrText(text: String) {
        try {
            val now = System.currentTimeMillis()
            if (text == lastAsrText && now - lastAsrTextAt < 3000) {
                Log.i(TAG, "onAsrText: duplicate ASR text within 3s, skip")
                return
            }
            lastAsrText = text
            lastAsrTextAt = now
            onAsrText(text)
        } catch (e: Exception) {
            Log.e(TAG, "onAsrText error", e)
        }
    }

    /**
     * 启动 ASR 桥接（文件轮询兜底 + RFCOMM 推送主通道 + 下行存活 ping）。
     * 幂等：重复调用会先停旧任务再拉新任务。
     */
    fun start() {
        pollJob?.cancel()
        pollJob = appScope.launch(Dispatchers.IO) {
            val prefs = appContext.getSharedPreferences("adb_prefs", 0)
            var lastTs = prefs.getLong(KEY_LAST_ASR_TS, 0L)
            // 隧道异常退避：连接失败时逐步拉大间隔（5s→10s→…→30s），
            // 推送断开期间不再固定 5s 打一次隧道，避免 5556 本地端口积压溢出
            var backoffMs = AI_ASR_POLL_INTERVAL_MS
            while (isActive) {
                try {
                    // 主通道推送健康时跳过文件轮询：蓝牙隧道（RFCOMM）单串行连接，
                    // 每轮 ADB 连接都会占用隧道，推送正常时打隧道会造成
                    // 5556 本地端口接收积压溢出 → "connection refused"。
                    // 推送通道断开时才启用文件兜底轮询。
                    if (pushClient?.isConnected != true) {
                        val r = readAiAsrBridgeTextOnce(lastTs)
                        if (r != null) {
                            if (r.tunnelOk) {
                                backoffMs = AI_ASR_POLL_INTERVAL_MS
                            } else {
                                backoffMs = (backoffMs * 2).coerceAtMost(AI_ASR_BACKOFF_MAX_MS)
                                Log.w(TAG, "ASR tunnel unavailable, backoff to ${backoffMs}ms")
                            }
                            r.text?.let { (ts, text) ->
                                lastTs = ts
                                prefs.edit().putLong(KEY_LAST_ASR_TS, ts).apply()
                                Log.i(TAG, "ASR via ADB bridge (fallback): $text")
                                // 打断已由眼镜端本地完成（KeyButtonService interruptOfficialLocally 发 Ai/Exit），
                                // 处理链路放后台线程执行（下行 sleep + DeepSeek join 耗时数秒），避免阻塞主线程。
                                Thread { onAsrText(text) }.start()
                            }
                        }
                    } else {
                        // 推送恢复：复位退避
                        backoffMs = AI_ASR_POLL_INTERVAL_MS
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "aiAsrBridge poll error", e)
                    backoffMs = (backoffMs * 2).coerceAtMost(AI_ASR_BACKOFF_MAX_MS)
                }
                delay(backoffMs)
            }
        }
        // 推送通道：第二 RFCOMM 长连接，毫秒级实时接收眼镜端推送（正常主通道）。
        // 眼镜端推送成功时不会写文件，轮询自然无新数据；推送失败才写文件由轮询兜底。
        pushClient?.stop()
        pushClient = AsrPushClient(appContext) { text ->
            try {
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
                Thread { onAsrText(text) }.start()
            } catch (e: Exception) {
                Log.e(TAG, "asr push handle error", e)
            }
        }
        pushClient?.start()
        Log.i(TAG, "start: started (push + file fallback)")
        // 连接期启动下行存活探测（链接断开/清理时随 stop() 一起停止）
        startDownlinkPing()
    }

    fun stop() {
        pollJob?.cancel()
        pollJob = null
        pushClient?.stop()
        pushClient = null
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
     * 创建一次性 ADB 短连接（用后即断），避免后台轮询长连接独占蓝牙隧道。
     * 必须在后台线程调用（同步阻塞连接握手）。连接失败返回 null。
     */
    private fun createShortAdbClient(): com.rokidlab.phone.adb.AdbShellClient? {
        return runCatching {
            val app = appContext as LabApplication
            val prefs = appContext.getSharedPreferences("adb_prefs", 0)
            val wifiIp = prefs.getString("ip", "192.168.1.168") ?: "192.168.1.168"
            val route = runBlocking { app.routeManager.resolve(wifiIp, 5555) }
            val (targetIp, targetPort) = when (route) {
                is ConnectionRoute.Wifi -> route.ip to route.port
                is ConnectionRoute.Bluetooth -> route.ip to route.localPort
                is ConnectionRoute.None -> return null
            }
            val client = com.rokidlab.phone.adb.AdbShellClient(appContext, targetIp, targetPort)
            if (client.connect()) client else {
                runCatching { client.disconnect() }
                // 连接失败：清除线路缓存，下轮重新探测重建隧道（蓝牙隧道可能已断开）
                runCatching { app.routeManager.clearRouteCache() }
                null
            }
        }.getOrNull()
    }

    /** 文件通道轮询单次结果：text=读到的新文本（可能 null）；tunnelOk=隧道连接是否成功 */
    private data class AsrBridgeRead(val text: Pair<Long, String>?, val tunnelOk: Boolean)

    /**
     * 通过 ADB 读取眼镜端 ai_asr.log 中时间戳大于 lastTs 的最新 ASR_TEXT（短连接，用后即断）。
     * 返回 AsrBridgeRead：tunnelOk=false 表示隧道不可用（调用方应退避，避免持续打隧道），
     * text 为 null 表示隧道正常但无新文本。
     */
    private fun readAiAsrBridgeTextOnce(lastTs: Long): AsrBridgeRead? {
        val client = createShortAdbClient() ?: return AsrBridgeRead(null, false)
        return try {
            val out = runCatching {
                client.executeShellCommand("tail -n 20 $GLASSES_ASR_FILE 2>/dev/null", 10_000)
            }.getOrNull()
            if (out == null) return AsrBridgeRead(null, false)
            val latest = out.lineSequence()
                .mapNotNull { line ->
                    // 每行格式：[epochMs] text；解析失败（半行/脏数据）则忽略
                    val m = Regex("""\[(\d+)\] (.*)""").matchEntire(line.trim()) ?: return@mapNotNull null
                    val ts = m.groupValues[1].toLongOrNull() ?: return@mapNotNull null
                    val text = m.groupValues[2].trim()
                    if (text.isEmpty()) null else ts to text
                }
                .lastOrNull()
            // 隧道连接成功；latest 可能为 null（无新数据），也可能时间戳不新
            AsrBridgeRead(if (latest != null && latest.first > lastTs) latest else null, true)
        } finally {
            runCatching { client.disconnect() }
        }
    }
}
