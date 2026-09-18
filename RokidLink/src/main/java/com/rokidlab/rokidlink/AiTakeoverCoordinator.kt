package com.rokidlab.rokidlink

import android.util.Log
import com.rokid.cxr.Caps
import java.io.File

/**
 * KeyButtonService 的 Ai 频道协调器（v3.9 拆分自 KeyButtonService）。
 *
 * 职责：
 *  1. 处理 "Ai" 频道消息（官方 AI 链路）：ASR 流式文字累积、ASR_End 拦截与本地接管、
 *     TTS_Result 来源区分（LinkProtocol.AI_REPLY_MARK）、下行过滤窗口
 *  2. 打断次数限制（滑动窗口，连续对话用更宽配额）
 *  3. ASR 文字双通道暴露（logcat + RFCOMM 推送 + 文件兜底）
 *  4. 连续对话（多轮免唤醒）：TTS 播完 → 请手机端下发 TTS_AudioFinished → 补回上一轮回复
 */
internal class AiTakeoverCoordinator(
    private val service: KeyButtonService,
    private val core: KeyServiceCore,
) {
    companion object {
        private const val TAG = KeyButtonService.TAG
        /** 下行过滤窗口：收到 Lab 下行标志后，窗口内 ASR 视为重发忽略。
         *  仅 KeyDown_Client 触发（Lab 完整下行序列 KeyDown_Client→open→ASR_Result→ASR_End 约 1s）。
         *  手机端打断官方用的「Ai/open」（interruptOfficialAi）不再触发过滤，避免误吞用户真实提问。 */
        private const val DOWNLINK_FILTER_MS = 2_000L
        /** 打断次数限制滑动窗口与上限 */
        private const val INTERRUPT_WINDOW_MS = 20_000L
        private const val INTERRUPT_MAX = 3
        /**
         * 连续对话（多轮免唤醒）模式下的打断限流：原 20s / 3 次是「异常自反馈循环」的保险，
         * 但连续对话时每一轮用户说话都要消耗一次额度（这才是正常用法），实测说到第 4 句
         * （间隔均 < 20s）就会被拒绝，表现为「前三句正常、之后喊了没反应」。
         * 连续对话开启时改用 60s / 12 次：仍然是硬上限（异常循环最多多跑 9 轮就自停），
         * 但不影响正常的多轮对话节奏。
         */
        private const val INTERRUPT_WINDOW_CONTINUOUS_MS = 60_000L
        private const val INTERRUPT_MAX_CONTINUOUS = 12
        /**
         * 本地打断官方后的诊断时间戳窗口。
         * **已不参与来源判定** —— 来源区分改用 [LinkProtocol.AI_REPLY_MARK]。
         */
        private const val OFFICIAL_ECHO_WINDOW_MS = 800L
        /**
         * 自动续听延时：Lab 回复本地 TTS 播完 → 重开官方 AI 会话的等待时间。
         * 需要缓冲是因为停播瞬间扬声器仍有尾音，立即开麦会把尾音喂成一次误识别；
         * 400ms 与 [TtsPlaybackHelper] 内部的块间稳定延时同量级。
         */
        private const val CONTINUE_DIALOG_DELAY_MS = 400L
        /**
         * 官方 startNewTalk 生效延时：眼镜端推续听标记后，要经
         * 手机端（RFCOMM 上行 → CXR 下行）再交官方 `AudioFinishedHandler →
         * aiAudioFinishWake → startNewTalk` 才真正重开拾音，期间 `showAudioFinishUI`
         * 会更新会话列表（追加一条新气泡）。等它走完再把 Lab 回复补回界面。
         *
         * 比本机直发多一个来回（眼镜→手机→眼镜），故取 700ms：真机 trace 里官方
         * 从收帧到 `startNewTalk` 仅 15ms，余量留给 RFCOMM/CXR 往返与线程调度。
         */
        private const val CONTINUE_LISTEN_SETTLE_MS = 700L
    }

    /** 唤醒词+语音的 ASR 流式文字（覆盖式累积，ASR_End 时取最后一条） */
    private var pendingAiText: String? = null

    /** 打断次数限制：滑动窗口内打断/写文件次数达到上限后暂停拦截，让官方自然完成回复 */
    @Volatile
    private var lastInterruptMs = 0L
    @Volatile
    private var interruptCount = 0

    /**
     * 最近一次下发的 Lab 回复正文。
     * 自动续听时会重开官方会话（界面被重置），用它把刚展示过的回复补回官方界面，
     * 让用户既能接着说下一句、又能看着上一轮的答案。仅内存持有，不落盘。
     */
    @Volatile
    private var lastLabReply: String? = null

    /** 待执行的「自动续听」延时任务；null = 当前没有排队中的续听 */
    @Volatile
    private var pendingContinueDialog: Runnable? = null

    /**
     * 处理 "Ai" 频道消息（官方 AI 链路，手机端 → 眼镜端）。
     *
     * 流程：
     *   ASR_Result（流式文字）→ 覆盖式累积
     *   ASR_End → 上行 Exit 关闭官方会话（停止乐奇显示/播报）+ 上行文字给手机 Lab 回复
     */
    // args 声明为可空：Caps 来自 Java 层，理论上可能被传 null，
    // 写成非空类型时 `args == null` 恒假（编译器已指出），防御分支永远不会走到。
    fun handleAiChannel(args: Caps?) {
        try {
            if (args == null || args.size() < 1 || args.at(0) == null) return
            val cmd = args.at(0).getString() ?: return
            when (cmd) {
                "ASR_Result" -> {
                    // 下行过滤：Lab 回复下行序列中的 ASR_Result 不做拦截逻辑（避免自反馈），
                    // 但必须**本机重放**给官方界面 —— 手机下行的 Ai 只到我们，官方收不到（见 relayAiToOfficial）
                    if (System.currentTimeMillis() < core.downlinkUntilMs) {
                        val t = args.at(1)
                            ?.takeIf { it.type() == Caps.Value.TYPE_STRING }?.getString()
                        if (!t.isNullOrBlank()) relayAiToOfficial("ASR_Result", t)
                        Log.d(TAG, "AI ASR_Result (downlink) -> relayed: ${t?.take(30)}")
                        return
                    }
                    if (args.size() > 1 && args.at(1) != null &&
                        args.at(1).type() == Caps.Value.TYPE_STRING
                    ) {
                        pendingAiText = args.at(1).getString()
                        Log.i(TAG, "AI ASR stream: ${pendingAiText?.take(40)}")
                    }
                }
                "ASR_End" -> {
                    // 下行过滤：Lab 回复下行序列中的 ASR_End 不做拦截逻辑，但本机重放给官方界面
                    if (System.currentTimeMillis() < core.downlinkUntilMs) {
                        relayAiToOfficial("ASR_End")
                        Log.d(TAG, "AI ASR_End (downlink) -> relayed")
                        return
                    }
                    // 官方模式：放行官方乐奇，不拦截不写文件
                    if (!core.isCustomAiMode()) {
                        Log.d(TAG, "AI ASR_End pass-through (official mode)")
                        return
                    }
                    val finalText = pendingAiText?.trim().orEmpty()
                    pendingAiText = null
                    // 空文本不消耗打断次数（先把提取/判空前置）
                    if (finalText.isBlank()) {
                        Log.w(TAG, "AI ASR_End with empty text")
                        return
                    }
                    Log.i(TAG, "AI ASR complete: $finalText")
                    // 用户已开口（能走到这里说明不是 Lab 下行重发——那些已被 downlinkUntilMs 拦掉）：
                    // 撤掉排队中的自动续听任务，避免它稍后插进来把这新一轮抢掉
                    cancelPendingContinueDialog()

                    // 所有命令统一接管（含歌词命令）：
                    // 之前对"歌词"命令放行官方（期望官方打开 music_word 歌词场景），
                    // 实测官方 AI 处理"歌词"命令会打开歌词场景并 Force stop RokidLink
                    // （third_app 场景清理），进程死亡后隧道/ASR/按键全断（15:47、16:01
                    // 两次实测均强杀）。歌词显示不依赖官方 music_word 场景——手机端把歌词
                    // 写入 MediaSession 后经蓝牙 AVRCP 直接到眼镜系统 MusicPageActivity。
                    // 因此歌词命令也必须打断官方，让官方 AI 不进入命令处理逻辑，才不会被强杀。
                    // 打断次数限制：窗口内达到上限则暂停拦截，让官方自然完成回复（打破循环）
                    if (!allowInterrupt()) {
                        Log.w(TAG, "AI ASR_End ignored (interrupt limit reached)")
                        return
                    }
                    // 官方 ASR_End 后约 1 秒内即开始 TTS 播报（离线问候语），
                    // 手机端 ADB 极速轮询也需 ~0.5s+。先尝试眼镜端本地立即打断（实测返回码）。
                    interruptOfficialLocally()
                    // 本地接管显示：官方会话被打断后界面会残留"思考中"等待（约 3s 直到手机端轮询+下行重开会话）。
                    // 这里立即本地重开会话并显示提问，官方界面立刻切到 Lab 会话等待（第二次思考中，可接受），
                    // 手机端读到文字后只需下行 DeepSeek 回复（TTS_Result），不再重发会话序列。
                    // 串行执行器：连续提问时避免多个接管任务并发导致指令交错
                    // execute 前必须查 isShutdown：onDestroy 已 shutdownNow，此后 CXR 回调
                    // 仍在途中时 execute 会抛 RejectedExecutionException —— 它在 execute 调用处
                    // （不在传入的 lambda 里）抛出，执行线程是 CXR 回调线程，未捕获即崩进程。
                    if (core.takeoverExecutor.isShutdown) {
                        Log.w(TAG, "local takeover skipped: executor already shutdown")
                    } else {
                        runCatching {
                            core.takeoverExecutor.execute {
                                try {
                                    openAiSession()
                                    showAiUserText(finalText)
                                } catch (e: Exception) {
                                    Log.e(TAG, "local takeover error", e)
                                }
                            }
                        }.onFailure { Log.w(TAG, "local takeover rejected: ${it.message}") }
                    }
                    // ASR 文字双通道暴露：
                    //   1) logcat（AiAsrBridge tag）——诊断用
                    //   2) RFCOMM 推送通道（AsrPushServer 长连接，毫秒级）——主通道
                    //   3) 文件（app 私有外部目录）——推送失败时的兜底（手机端轮询读取）
                    Log.i(KeyButtonService.AI_ASR_BRIDGE_TAG, "ASR_TEXT:$finalText")
                    // 先推送「ASR 识别完成」信号：官方已识别完并下发 ASR_End（此刻打断官方安全），
                    // 手机端收到该信号才打断官方 AI（替代 onGlassAppResume 固定 800ms 的提前打断，
                    // 消除官方识别未完成就被掐断导致 ASR_End 永不产生的竞态）。
                    // 再推文字（主通道）+ 文件兜底。两帧顺序：信号在前、文字在后，手机端按序消费。
                    AsrPushServer.pushControl(AsrPushServer.CTRL_ASR_READY)
                    if (!AsrPushServer.push(finalText)) {
                        Log.w(TAG, "ASR push channel unavailable, fallback to file")
                        appendAiAsrToFile(finalText)
                    }
                }
                "TTS_Result" -> {
                    // Lab/AI 回复正文：本机注入回**官方对话界面**（v3.0 `c2484b2` 的既有做法）。
                    // 手机下行的这帧只到本应用、官方 AssistServer 收不到 —— 这就是「有声音没文字」的根因。
                    //
                    // ⚠️ 该频道是**广播式**的：官方 App 自己的回复正文也从这里经过
                    //（我们订阅 `Ai` 本就是为拦截官方 ASR，见 CxrBridgeCoordinator）。
                    // ⇒ 必须区分来源：官方在 `Exit` 后仍会回流它自己的正文，若被我们当作 Lab
                    //   回复注入官方界面，就会渲染成「官方的字」。
                    //
                    // 判据 = caps[2] 是否等于 [LinkProtocol.AI_REPLY_MARK]（手机端下发 Lab 回复时写入）。
                    // ⚠️ 不要退回「时间窗口」方案：官方 TTS 回流发生在 ASR_End 之后约 1 秒，
                    //    而窗口只有 800ms，**首轮经常落在窗口外**，于是首条回复就漏出官方的字
                    //    （用户实测反馈：「第一次回消息又把官方的信息显示了」）。
                    val t = args.at(1)
                        ?.takeIf { it.type() == Caps.Value.TYPE_STRING }?.getString()
                    val fromLab = runCatching {
                        args.at(2)?.takeIf { it.type() == Caps.Value.TYPE_STRING }?.getString()
                    }.getOrNull() == LinkProtocol.AI_REPLY_MARK
                    if (t.isNullOrBlank()) {
                        Log.d(TAG, "TTS_Result without text payload")
                    } else if (fromLab) {
                        showAiReply(t)
                    } else {
                        // 官方回声（只带 2 个元素）。echoWindowActive 仅作诊断：恒 false 说明
                        // 官方回流本就落在窗口外 —— 这正是必须改用显式标记的原因。
                        Log.w(
                            TAG,
                            "TTS_Result dropped (not from Lab, argc=${args.size()} " +
                                "echoWindowActive=${System.currentTimeMillis() < core.officialEchoUntilMs}): ${t.take(40)}"
                        )
                    }
                }
                "TTS_AudioFinished", "Ai_Heartbeat" -> {
                    // 官方乐奇收尾/心跳：忽略（界面即将被 Exit 关闭）
                }
                "KeyDown_Client" -> {
                    // Lab 回复完整下行序列（KeyDown_Client→open→ASR_Result→ASR_End）的标志：
                    // 开启下行过滤窗口，过滤其中的重发 ASR_Result/ASR_End。
                    // 注意：单独的「Ai/open」（手机端 interruptOfficialAi 打断官方）不触发过滤，
                    // 否则会误吞用户紧随其后的真实提问（open 后 1~2s 官方 ASR_End 到达）。
                    core.downlinkUntilMs = System.currentTimeMillis() + DOWNLINK_FILTER_MS
                    Log.d(TAG, "Downlink flag: $cmd, filter until ${core.downlinkUntilMs}")
                    // 本机重放：官方对话界面的打开依赖本机注入（手机下行到不了官方）
                    relayAiToOfficial("KeyDown_Client", "{\"privacy_level\":2}")
                }
                // 官方 AI 会话打开：手机端 interruptOfficialAi / 下行序列中的 open。
                // 本机重放 open：官方对话界面由此打开（本应用已无常驻 Activity，不存在 third_app 强杀问题）。
                "open" -> {
                    Log.i(TAG, "AI channel open — relay to official")
                    relayAiToOfficial("open")
                }
                // 官方 AI 会话结束（手机端下行 Exit 关闭官方会话）：无需处理。
                "Exit" -> {
                    Log.d(TAG, "AI channel Exit")
                }
                else -> Log.d(TAG, "AI channel ignored: $cmd")
            }
        } catch (e: Exception) {
            Log.e(TAG, "handleAiChannel error", e)
        }
    }

    /**
     * 打断次数控制：滑动窗口（INTERRUPT_WINDOW_MS）内拦截/打断次数达到 INTERRUPT_MAX 后
     * 拒绝继续拦截（让官方自然完成回复，打破任何异常循环）；窗口滚动后自动清零恢复。
     */
    private fun allowInterrupt(): Boolean {
        val now = System.currentTimeMillis()
        // 连续对话时每一轮用户说话都要消耗一次打断额度（这是正常用法，不是异常循环）：
        // 沿用 20s/3 会让第 4 句起被静默拒绝（现象：「前三句正常，之后喊了没反应」），
        // 故开启时换用更宽的 60s/12。它仍是硬上限 —— 异常自反馈循环最多多跑 9 轮即自停。
        val continuous = KeyButtonService.isContinueDialogEnabled(service)
        val windowMs = if (continuous) INTERRUPT_WINDOW_CONTINUOUS_MS else INTERRUPT_WINDOW_MS
        val maxCount = if (continuous) INTERRUPT_MAX_CONTINUOUS else INTERRUPT_MAX
        if (now - lastInterruptMs > windowMs) {
            interruptCount = 0
        }
        lastInterruptMs = now
        interruptCount++
        val allowed = interruptCount <= maxCount
        if (!allowed) {
            Log.w(TAG, "allowInterrupt denied: $interruptCount > $maxCount in ${windowMs}ms (continuous=$continuous)")
        }
        return allowed
    }

    /**
     * 将 ASR 文字追加写入 app 私有外部目录文件（ai_asr.log），
     * 供手机端经 ADB（蓝牙隧道）轮询读取。logcat 缓冲会被高频系统日志数秒内冲掉，
     * 必须落盘才能保证手机端可靠读到。每行格式：[epochMs] text
     * 在后台线程执行：该函数由 CXR 订阅回调线程触发，全量读写文件会阻塞回调链路。
     */
    private fun appendAiAsrToFile(text: String) {
        Thread {
            try {
                val dir = service.getExternalFilesDir(null) ?: return@Thread
                val f = File(dir, "ai_asr.log")
                // 截断防膨胀：超过 64KB 时只保留最近 5 行（蓝牙传输带宽有限，避免每次 cat 过慢）
                if (f.exists() && f.length() > 64 * 1024) {
                    val last = f.readLines().takeLast(5)
                    f.writeText("")
                    if (last.isNotEmpty()) f.appendText(last.joinToString("\n") + "\n")
                }
                f.appendText("[${System.currentTimeMillis()}] $text\n")
            } catch (e: Exception) {
                Log.e(TAG, "appendAiAsrToFile error", e)
            }
        }.apply { name = "ai-asr-file"; start() }
    }

    /**
     * 眼镜端本地打断官方 AI 的**尽力而为**尝试。
     *
     * ⚠️ 2026-09-17 真机取证：这两条**都不能真正打断官方**——
     * 1) `sendAi("Exit")` = `bridge.sendMessage(AI_TOPIC, …)`，是 **CXR 上行帧**（眼镜→手机）；
     *    官方 AssistServer 的 AI 分发器只处理**入站（手机→眼镜）帧**，所以官方收不到。
     * 2) `am broadcast ACTION_SPRITE_BUTTON_DOUBLE_CLICK` 是 protected 广播，第三方 uid 被拒。
     *
     * ⚠️ 同日二次取证：**手机下发的入站 `Exit` 也不清空官方内容** —— 官方只走
     * `AIExitHandler.handle`，全程没有 `AiDialogManager dismissAiDialog` / `AiAdapter clearData`
     * （直到 25s 后官方 `RokidAIController.exit` 自身超时才清）。而且它会把官方置为「已退出」态，
     * 导致随后 Lab 的 `TTS_Result` 只出声、不再进 UI（`TtsResultHandler` 有日志但无
     * `showUpdateTTSUI`）⇒ 用户「有声音没文字」。**故该帧已从眼镜语音链路移除。**
     * 想在显示前清掉官方自己的答案，必须另找手段（例如自绘悬浮层），别再用 Exit。
     */
    private fun interruptOfficialLocally() {
        // 只记录诊断时间戳：真正的来源区分已由 [LinkProtocol.AI_REPLY_MARK] 承担
        // （官方的 TTS_Result 只带 2 个元素，在 TTS_Result 分支被直接丢弃，不再依赖时间窗口）。
        core.officialEchoUntilMs = System.currentTimeMillis() + OFFICIAL_ECHO_WINDOW_MS
        // 1) 尝试 CXR 上行 Ai/Exit（0ms 起，300ms 重试一次）
        val r1 = core.sendAi("Exit")
        Log.i(TAG, "interruptOfficialLocally: sendAi(Exit) -> $r1")
        if (r1 != 0) {
            core.mainHandler.postDelayed({
                val r2 = core.sendAi("Exit")
                Log.i(TAG, "interruptOfficialLocally: sendAi(Exit) retry -> $r2")
            }, 300)
        }
        // 2) 尝试系统双击广播：独立线程 + 超时销毁。
        //    不能同步执行：am broadcast 在 ASR_End 回调线程偶发挂起（readText 等待子进程 EOF），
        //    会卡死后继的 push/文件兜底，导致 ASR 文字整条丢失（实测"显示歌词"卡死于此）。
        Thread {
            try {
                // shell 内用 2>&1 合并 stderr 到 stdout，只读单流：先读 stdout 再读 stderr 可能因管道缓冲占满而死锁
                val p = Runtime.getRuntime().exec(arrayOf(
                    "sh", "-c",
                    "am broadcast -a com.android.action.ACTION_SPRITE_BUTTON_DOUBLE_CLICK 2>&1"
                ))
                if (p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
                    val out = p.inputStream.bufferedReader().use { it.readText() }
                    Log.i(TAG, "interruptOfficialLocally: am broadcast -> ${out.trim().take(120)}")
                } else {
                    Log.w(TAG, "interruptOfficialLocally: am broadcast timeout, destroying")
                    p.destroy()
                }
            } catch (e: Exception) {
                Log.e(TAG, "interruptOfficialLocally am error", e)
            }
        }.apply { name = "am-broadcast-dblclick"; isDaemon = true; start() }
    }

    /** 打开 AI 对话界面：KeyDown_Client(privacy_level=2) → open（手机端已验证该序列可打开 ai_assist 场景） */
    private fun openAiSession() {
        val r1 = core.sendAi("KeyDown_Client", "{\"privacy_level\":2}")
        Log.i(TAG, "openAiSession KeyDown_Client -> $r1")
        Thread.sleep(1200)
        val r2 = core.sendAi("open")
        Log.i(TAG, "openAiSession open -> $r2")
        Thread.sleep(800)
    }

    /** 在官方聊天界面显示用户识别出的问题（ASR_Result + ASR_End） */
    private fun showAiUserText(text: String) {
        val r1 = core.sendAi("ASR_Result", text)
        val r2 = core.sendAi("ASR_End")
        Log.i(TAG, "showAiUserText: ASR_Result=$r1 ASR_End=$r2 text=$text")
    }

    /**
     * 在**官方聊天界面**显示 Lab 回复正文（`TTS_Result` 本机注入）。
     *
     * 这是 v3.0 (`c2484b2`) 的既有做法；v3.1 移除后改成"手机回 `TTS_Result`"，
     * 但手机下行的 `Ai` 消息在眼镜上只投递到本应用（拦截订阅），官方 AssistServer 收不到，
     * 于是退化成「有声音、没文字」。这里恢复本机注入：与 [showAiUserText] 同一机制即可显示。
     *
     * 注意：**不在这里播 TTS** —— 播报由手机端下发的 `tts_play` 负责，避免重复播报。
     */
    private fun showAiReply(reply: String) {
        val r = core.sendAi("TTS_Result", reply)
        Log.i(TAG, "showAiReply: TTS_Result=$r text=${reply.take(40)}")
    }

    /**
     * 把手机端下行的官方协议指令**本机重放**给官方对话界面。
     *
     * 手机 → 眼镜的 CXR `Ai` 消息只投递到本应用（我们订阅它是为了拦截官方 ASR），
     * 官方 AssistServer 收不到；而眼镜本机 `sendAi()` 能进官方链路（v3.0 已验证）。
     * 所以要显示在官方界面的内容，都必须由我们本机再发一次。
     */
    private fun relayAiToOfficial(cmd: String, vararg values: String): Int {
        val r = core.sendAi(cmd, *values)
        Log.i(TAG, "relay($cmd) -> $r${values.firstOrNull()?.let { " (${it.take(24)})" } ?: ""}")
        return r
    }

    /** 服务销毁：撤销排队中的续听任务 */
    fun onDestroy() {
        pendingContinueDialog = null
    }

    // ──────────────────────────────────────────────
    //  连续对话（多轮免唤醒）
    // ──────────────────────────────────────────────

    /** 收到手机端文字消息后，调用眼镜本地 TTS 播放语音（AiChannel v1 编解码，兼容 v0） */
    fun handleTtsPlay(args: Caps) {
        try {
            val text = AiChannel.decodeTtsPlay(capsToStrings(args)) ?: run {
                Log.w(TAG, "handleTtsPlay: rejected invalid/unsupported payload (size=${args.size()})")
                return
            }
            Log.i(TAG, "Received tts_play: ${text.take(40)}...")
            if (text.isNotBlank()) {
                // 新一轮播报覆盖上一轮：先撤掉上一轮遗留的续听任务（本轮播完会重新调度）
                cancelPendingContinueDialog()
                lastLabReply = text
                // 播完回调 = 连续对话（多轮免唤醒）的触发点，见 [onLabReplyPlaybackFinished]
                TtsPlaybackHelper.play(service, text) { onLabReplyPlaybackFinished() }
            }
        } catch (e: Exception) {
            Log.e(TAG, "handleTtsPlay error", e)
        }
    }

    /**
     * Lab 回复的本地 TTS「真正播完」回调（TtsPlaybackHelper 在最后一块收到 ITtsListener.onTtsStop
     * 之后触发，不是按时长估算）。
     *
     * 这是「连续对话（多轮免唤醒）」的触发点：播完即代表本轮双端文字都已显示、语音已播放完毕，
     * 此刻请手机端让官方重开拾音，用户不用再喊唤醒词，直接说下一句即可。
     *
     * 决策放在眼镜端（只有它拿得到真实播放结束时刻；手机端下发 `tts_play` 后没有播放进度），
     * 但**执行**必须由手机端完成 —— 详见 [requestOfficialContinueListening]。
     *
     * 为什么还要延时 [CONTINUE_DIALOG_DELAY_MS]：停播瞬间扬声器仍有尾音，
     * 立刻开麦会被自己的尾音喂进一次误识别。
     */
    private fun onLabReplyPlaybackFinished() {
        if (!core.isCustomAiMode()) {
            Log.i(TAG, "continue dialog: skipped (official ai mode)")
            return
        }
        if (!KeyButtonService.isContinueDialogEnabled(service)) {
            Log.i(TAG, "continue dialog: skipped (switch off)")
            return
        }
        cancelPendingContinueDialog()
        val task = Runnable { runContinueDialog() }
        pendingContinueDialog = task
        core.mainHandler.postDelayed(task, CONTINUE_DIALOG_DELAY_MS)
        Log.i(TAG, "continue dialog: scheduled in ${CONTINUE_DIALOG_DELAY_MS}ms")
    }

    /** 撤销尚未执行的自动续听（用户已开口 / 新一轮播报覆盖 / 开关关闭 / 服务销毁） */
    fun cancelPendingContinueDialog() {
        pendingContinueDialog?.let { core.mainHandler.removeCallbacks(it) }
        pendingContinueDialog = null
    }

    /**
     * 让官方重新开始拾音 —— 连续对话能成立的**关键一步**。由**手机端**代发（见下）。
     *
     * ⚠️ 只重开界面是不够的：`openAiSession()`（KeyDown_Client + open）只把 ai_assist
     * 场景/对话界面拉起来，**麦克风并不会开始拾音**。用户实测「等 lab 显示并播放完
     * 我在说话 没反应」，日志里重开之后再没出现过任何 `AI ASR stream` —— 人说了，没人听。
     *
     * 官方自己的续听链路是（`_g_proto_trace.md` 真机实测）：
     *   `TTS_AudioFinished` → AssistServer `AudioFinishedHandler` → `aiAudioFinishWake`
     *   → `AIModeManager.startNewTalk`（重新开始拾音）
     * 而手机端为了让刚显示的 Lab 回复不被清屏，一直传 `skipTtsAudioFinished=true`
     * **主动放弃了**它（见 `AiConversationService.sendAiTextViaLink` 注释）。
     *
     * ⚠️⚠️ **这一帧绝不能在眼镜端本机 `sendAi` 发出**（v1 试过，实测 21:07:32 官方毫无反应）：
     * 真机 trace 显示 `AudioFinishedHandler` 只被 `[wire] recv cmd=Ai`（**入站**：手机→眼镜）
     * 触发；眼镜本机 `sendAi` 是**出站**帧，走 `[wire] cmd=Ai caps=...`，官方自己的分发器
     * 收不到（同理 `KeyDown_Client`/`open` 等本机 sendAi 也不会进官方链路）。
     * 所以本方法改为：**推 RFCOMM 控制标记上行给手机**，由手机经 CXR `Ai` 频道下发
     * `TTS_AudioFinished`（对眼镜而言是入站 → 官方必然走 `AudioFinishedHandler`）。
     *
     * 为什么仍由眼镜端决定「何时」：只有它拿得到 TTS 真实播放结束时刻
     *（`TtsPlaybackHelper` 的 `onFinished` ← `ITtsListener.onTtsStop`），
     * 手机端下发 `tts_play` 后只有「已发出」，没有播放进度。
     */
    private fun requestOfficialContinueListening() {
        val ok = AsrPushServer.pushControl(LinkProtocol.MARKER_CONTINUE_DIALOG)
        Log.i(TAG, "continue dialog: continue-dialog marker -> $ok (phone will send TTS_AudioFinished)")
    }

    /**
     * 执行自动续听：请手机端下发 `TTS_AudioFinished` 让官方重开麦，并把上一轮回复文字补回界面。
     *
     * 执行前重查一遍开关与链路状态——排队期间用户可能已手动唤醒并开始说话
     *（那条路径的 ASR_End 拦截会 cancel 本任务），这里是双保险。
     */
    private fun runContinueDialog() {
        pendingContinueDialog = null
        if (!core.isCustomAiMode() || !KeyButtonService.isContinueDialogEnabled(service)) return
        // 手机端下行序列（KeyDown_Client→open→…→TTS_Result）仍在途中时不要抢链路，
        // 让本轮下行自然走完（下一次播完还会再调度）。
        if (System.currentTimeMillis() < core.downlinkUntilMs) {
            Log.i(TAG, "continue dialog: postponed (downlink in progress)")
            return
        }
        if (core.takeoverExecutor.isShutdown) {
            Log.w(TAG, "continue dialog skipped: executor already shutdown")
            return
        }
        runCatching {
            // 复用本地接管的串行执行器：下面含 sleep，
            // 与 ASR_End 的本地接管互斥排队，避免两条会话序列指令交错。
            core.takeoverExecutor.execute {
                try {
                    Log.i(TAG, "continue dialog: asking phone to reopen mic for next turn")
                    // 关键：让**手机端**下发 TTS_AudioFinished，触发官方续听链路（重开拾音）。
                    // 本机 sendAi 发这帧官方收不到（出站帧），实测无效，见方法注释。
                    requestOfficialContinueListening()
                    // 手机端要经 RFCOMM 上行 + CXR 下行一个来回，官方才走完
                    // startNewTalk/showAudioFinishUI，等它落定再补文字（见常量注释）。
                    Thread.sleep(CONTINUE_LISTEN_SETTLE_MS)
                    // 安全：TTS_Result 在官方侧只负责显示，播报由手机端 tts_play 驱动，不会二次发声。
                    lastLabReply?.takeIf { it.isNotBlank() }?.let { showAiReply(it) }
                    Log.i(TAG, "continue dialog: reopen requested via phone, waiting for user speech")
                } catch (e: Exception) {
                    Log.e(TAG, "continue dialog error", e)
                }
            }
        }.onFailure { Log.w(TAG, "continue dialog rejected: ${it.message}") }
    }
}
