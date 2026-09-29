package com.rokidlab.rokidlink

import android.util.Log
import com.rokid.cxr.Caps
import java.util.concurrent.TimeUnit

/**
 * 环境音监听控制器 —— 白嫖官方字幕链路（远场环境音 ASR）。
 *
 * ## 为什么走官方字幕而不是自己开麦
 *
 * Lab 语音输入只能近场（hook 官方 AI 的 ASR_End，拾音距离很近）。要「听清周围的人说什么」
 * 需要远场拾音 + 降噪 + 云端 ASR，整条链路官方 AssistServer 的字幕模式（Accessibility）现成就有：
 * cxr-service 收到 `Accessibility_start` 后自己开远场麦（`openAudioRecord2: intent subtitle,
 * denoiseMode 2`）→ 降噪 → opus → 云端 ASR → 结果从同一频道广播回来。真机 logcat 已实证：
 * Lab 作为普通 CXR 客户端**不参与音频搬运**，只订阅结果即可，零音频权限/编解码负担。
 *
 * ## 协议（真机 logcat 实证，见 _official_dump/）
 *
 *  - 开启：`sendMessage("Accessibility", caps("Accessibility_start"))`
 *  - 结果：同频道广播 JSON `{audio_path, content, final, number, role}`（流式 partial，
 *    `final=true` 表示一句结束；`number` 为句子编号）
 *  - 关闭：`stopAudioStream`（关 cxr-service 录音）+ `sendMessage("Accessibility",
 *    caps("Accessibility_end"))` —— 两个都要发（日志显示二者与 `closeAudioRecord` 成对，
 *    只发 end 可能不关麦）
 *
 * ## 职责
 *
 *  1. 响应手机端控制（[LinkProtocol.TOPIC_AMBIENT_CTRL]：start / stop）
 *  2. 活跃会话期间把字幕 JSON 经 [AsrPushServer] 上行手机端
 *     （[LinkProtocol.MARKER_AMBIENT_TEXT] 前缀 + 原始 JSON）
 *  3. 看门狗：手机端崩溃/失联没来得及 stop 时，最长 [WATCHDOG_MS] 自动关麦（隐私兜底）
 *
 * 副作用说明：官方字幕开启期间眼镜屏幕会显示字幕 UI（官方 Unity 渲染），属预期行为。
 */
internal class AmbientListenController(
    private val core: KeyServiceCore,
) {
    companion object {
        private const val TAG = KeyButtonService.TAG
        /** 官方字幕开/关指令载荷（与 AssistServer 同款字面量） */
        private const val OFFICIAL_START = "Accessibility_start"
        private const val OFFICIAL_END = "Accessibility_end"
        /**
         * 自动关麦看门狗：远场麦是持续隐私敏感面，手机端若崩溃/失联没发 stop，
         * 字幕模式会一直开着。空闲常开模式下（2026-09-29 定稿：不手动关就一直听），
         * 手机端每 60s 下行 ping（rokidlab_ping）续期一次；只有连续 [WATCHDOG_MS]
         * 收不到任何下行（手机真崩溃/失联）才强制关麦 —— 语义仍是失联隐私兜底。
         */
        private const val WATCHDOG_MS = 15 * 60_000L
        /** 官方字幕模式开麦的 intent（logcat：`openAudioRecord2: codec 1, mode 6, intent subtitle`；
         *  关闭时 SDK 签名为 `stopAudioStream(String)`，日志对应 `AudioCapture.stopRecord(subtitle)`） */
        private const val SUBTITLE_INTENT = "subtitle"

        /**
         * 停麦后的收尾窗口：字幕管线的**最后一句 final 往往在 closeAudioRecord 之后才广播**
         * （实测 12:50:49.578 关麦 → 49.628 才收到 `{"content":"哈喽。","final":true}`，差 50ms），
         * 窗口内继续上行，否则「刚说完就点关」会丢掉这句话。
         */
        private const val DRAIN_MS = 3_000L

        /**
         * TTS 播放中（进程级硬闸，AsrPushServer 同款静态标志）：播报期间远场麦必然拾到
         * 自己的声音，字幕帧一律丢弃 —— 手机端的内容重合判定只是兜底，这里是主防线
         * （眼镜端唯一拿得到真实播放起止时刻，置位/复位点见 AiTakeoverCoordinator）。
         */
        @Volatile
        internal var ttsPlaying = false

        /**
         * 环境音监听会话活跃（进程级镜像，与实例 [listening] 同步置位/复位）：
         * 供 AiTakeoverCoordinator 等外部模块判断（实例由 CxrBridgeCoordinator 私有持有，
         * 外部拿不到引用）。Lab 回复播完回调据此区分「聆听模式的回复」（上行播完标记、
         * 跳过官方续听）与普通回复。
         */
        @Volatile
        internal var sessionActive = false
    }

    /** 是否处于监听会话中（控制上行推送与看门狗） */
    @Volatile
    private var listening = false

    /**
     * 有 stop 指令在途（在指令到达时置位，见 [handleControl]）。
     * start 在耗时约 2s 的助手拉起之后校验：若期间来了 stop，则放弃开麦 ——
     * 否则会出现「已显示关闭、远场麦却被这条 start 重新打开」的隐私缺口。
     */
    @Volatile
    private var stopRequested = false

    /**
     * 回答期间闭麦中（2026-09-29 定稿）：手机端 AI 问答分发前下发 pause，播完下发 resume。
     * 与 stop 的本质区别：**listening / sessionActive 保持 true** —— 播完标记的判定
     * （AiTakeoverCoordinator.onLabReplyPlaybackFinished 检查 sessionActive）依赖它；
     * 若复用 stop 会话状态被清，播完 marker 不再上行，手机端要等 150s 兜底才重开麦，
     * 表现为「第二轮听不到」（2026-09-29 12:59 实证）。
     */
    @Volatile
    private var paused = false

    /** 收尾窗口截止时刻（停麦后仍放行上行的时长，见 [DRAIN_MS]） */
    @Volatile
    private var drainUntilMs = 0L

    /** 已上行的最后一个 final 句子编号（官方重发去重，start 时重置） */
    private var lastFinalNumber = -1

    private var watchdog: Runnable? = null

    /**
     * 控制序列专用单线程执行器：handleControl 来自 CXR 订阅回调线程、watchdog 来自主线程，
     * 而 start/stop 内部的 sendMessage/stopAudioStream 在蓝牙半开时可能阻塞数秒 ——
     * 绝不能在调用线程同步执行（卡死回调链/主线程）。所有控制序列统一投递到本执行器。
     */
    private val ctlExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "ambient-ctl").apply { isDaemon = true }
    }

    /** 手机端控制指令（[LinkProtocol.TOPIC_AMBIENT_CTRL]）：caps[0] = "start" / "stop"。
     *  非阻塞：解析后立即投递控制序列，回调线程不被 sendMessage 阻塞。 */
    fun handleControl(args: Caps?) {
        when (val cmd = firstString(args)) {
            "start" -> {
                stopRequested = false
                ctlExecutor.execute { start() }
            }
            "stop" -> {
                // 在**指令到达时**（而非队列执行时）置位：start 内含 ~2s 的助手拉起，
                // 期间到达的 stop 会排在队尾，仅靠队内检查发现不了（会先开麦再关）。
                stopRequested = true
                ctlExecutor.execute { stop("phone requested") }
            }
            "pause" -> ctlExecutor.execute { pause() }
            "resume" -> ctlExecutor.execute { resume() }
            else -> Log.w(TAG, "ambient ctrl unknown cmd: $cmd")
        }
    }

    /**
     * 官方字幕频道（[LinkProtocol.CXR_CHANNEL_ACCESSIBILITY]）广播回调：
     * 活跃会话期间把识别结果 JSON 原样经推送通道上行手机端。
     * 只上行 final 帧：partial 流式更新对持续应答无意义，还占推送通道带宽。
     * TTS 播报期硬闸丢弃（远场麦拾到自己的播报，绝不能上行，防 AI 自问自答）。
     * 非活跃期直接丢弃（该频道还承载开/关指令回声等非 ASR 帧，由手机端 JSON 解析二次过滤）。
     */
    fun handleAccessibility(args: Caps?, value: ByteArray?) {
        // 收尾窗口内（刚停麦）仍放行：最后一句 final 常在 closeAudioRecord 之后才广播
        if (!listening && System.currentTimeMillis() > drainUntilMs) return
        if (paused) return  // 回答期间闭麦：残帧一律丢弃（说了也听不见）
        if (ttsPlaying) return
        val json = extractJson(args, value) ?: return
        val obj = runCatching { org.json.JSONObject(json) }.getOrNull() ?: return
        if (!obj.optBoolean("final", false)) return
        // 官方可能重发同一句 final：按 number 去重（number<0 的帧不判定，交手机端兜底）
        val number = obj.optInt("number", -1)
        if (number >= 0) {
            synchronized(this) {
                if (number == lastFinalNumber) return
                lastFinalNumber = number
            }
        }
        val sent = AsrPushServer.push(LinkProtocol.MARKER_AMBIENT_TEXT + json)
        Log.d(TAG, "ambient asr final(#$number) -> push=$sent: ${json.take(80)}")
    }

    /** 会话是否活跃（日志/诊断用） */
    val active: Boolean get() = listening

    private fun start() {
        if (listening) {
            Log.i(TAG, "ambient start: already listening, ignore")
            return
        }
        val b = core.bridge ?: run {
            Log.w(TAG, "ambient start: no bridge")
            return
        }
        // 本方法只负责开/关远场拾音。⚠️ 不要顺手拉起官方助手：实测其 AIUI_SpeechLease
        // 会占住语音租约，10s 无唤醒词即 round=closed 并连带 stopRecord(subtitle)，
        // 把远场拾音一起掐掉（2026-09-28 12:50:47 日志实证），同时手机端也等不到场景上报。
        // 因此手机端点耳朵只开拾音；官方助手由用户自己按眼镜键唤起。
        if (stopRequested) {
            Log.i(TAG, "ambient start: aborted — stop already requested")
            return
        }
        // sendMessage 必须走超时保护包装：蓝牙半开时可能无限阻塞（见 sendCxrWithTimeout 注释）
        val caps = Caps().also { it.write(OFFICIAL_START) }
        val r = core.sendCxrWithTimeout(b, LinkProtocol.CXR_CHANNEL_ACCESSIBILITY, caps)
        Log.i(TAG, "ambient start: sendMessage($OFFICIAL_START) -> $r")
        if (r != 0) return
        listening = true
        sessionActive = true
        synchronized(this) { lastFinalNumber = -1 }
        armWatchdog()
    }

    private fun stop(reason: String) {
        // 不因 listening=false 提前返回：开麦指令与关闭指令走不同通道，cxr-service 可能
        // 先处理关闭、后处理开麦（实测竞态），关闭序列一律下发兜底，避免残留开麦。
        listening = false
        sessionActive = false
        paused = false
        // 开收尾窗口：关麦后官方才广播最后一句 final（实测差 50ms），窗口内继续上行
        drainUntilMs = System.currentTimeMillis() + DRAIN_MS
        cancelWatchdog()
        val b = core.bridge ?: run {
            Log.w(TAG, "ambient stop($reason): bridge gone, cannot send end")
            return
        }
        // 官方同款顺序：先 stopAudioStream 关 cxr-service 录音，再发 Accessibility_end。
        // 只发 end 可能不关麦（日志显示 stopAudioStream 与 closeAudioRecord 成对出现）。
        // stopAudioStream 同样可能阻塞：提交到 aiSendExecutor + 超时保护，不在调用线程裸调。
        val streamStopped = runCatching {
            core.aiSendExecutor.submit<Int> {
                runCatching { b.stopAudioStream(SUBTITLE_INTENT) }
                    .onFailure { Log.w(TAG, "ambient stop: stopAudioStream failed: ${it.message}") }
                0
            }.get(2, TimeUnit.SECONDS)
        }.onFailure { Log.w(TAG, "ambient stop: stopAudioStream timeout: ${it.message}") }
            .getOrDefault(-1)
        val caps = Caps().also { it.write(OFFICIAL_END) }
        val r = core.sendCxrWithTimeout(b, LinkProtocol.CXR_CHANNEL_ACCESSIBILITY, caps)
        Log.i(TAG, "ambient stop($reason): stopAudioStream=$streamStopped end -> $r")
    }

    /**
     * 回答期间闭麦（手机端 AI 问答分发前下发）：只执行关麦序列，**不清会话状态** ——
     * listening / sessionActive 保持 true（播完标记判定依赖，见 [paused] 注释），
     * 收尾窗口不开（paused 期间 handleAccessibility 直接丢帧，无需 drain）。
     * 看门狗不动：pause 只覆盖一轮回答（秒级~2 分钟），手机端 ping 保活照常续期。
     */
    private fun pause() {
        if (paused || !listening) {
            Log.i(TAG, "ambient pause: skipped (paused=$paused listening=$listening)")
            return
        }
        paused = true
        val b = core.bridge ?: run {
            Log.w(TAG, "ambient pause: bridge gone")
            return
        }
        val streamStopped = runCatching {
            core.aiSendExecutor.submit<Int> {
                runCatching { b.stopAudioStream(SUBTITLE_INTENT) }
                    .onFailure { Log.w(TAG, "ambient pause: stopAudioStream failed: ${it.message}") }
                0
            }.get(2, TimeUnit.SECONDS)
        }.onFailure { Log.w(TAG, "ambient pause: stopAudioStream timeout: ${it.message}") }
            .getOrDefault(-1)
        val caps = Caps().also { it.write(OFFICIAL_END) }
        val r = core.sendCxrWithTimeout(b, LinkProtocol.CXR_CHANNEL_ACCESSIBILITY, caps)
        Log.i(TAG, "ambient pause: stopAudioStream=$streamStopped end -> $r (session kept alive)")
    }

    /**
     * 回答播完恢复接收（手机端收到 MARKER_AMBIENT_TTS_DONE 后下发）：重发 Accessibility_start
     * 重开远场拾音。listening 已是 true，不走 [start]（其 stopRequested/幂等检查不适用）；
     * 重置 lastFinalNumber —— 官方字幕管道重开后 number 可能重新计数，与上一轮尾号
     * 相同时会把新句子误判为重发丢弃。
     */
    private fun resume() {
        if (!paused) {
            Log.i(TAG, "ambient resume: not paused, ignore")
            return
        }
        paused = false
        val b = core.bridge ?: run {
            Log.w(TAG, "ambient resume: bridge gone, mic stays closed")
            return
        }
        val caps = Caps().also { it.write(OFFICIAL_START) }
        val r = core.sendCxrWithTimeout(b, LinkProtocol.CXR_CHANNEL_ACCESSIBILITY, caps)
        Log.i(TAG, "ambient resume: sendMessage($OFFICIAL_START) -> $r")
        if (r != 0) return
        synchronized(this) { lastFinalNumber = -1 }
    }

    private fun armWatchdog() {
        cancelWatchdog()
        val task = Runnable {
            watchdog = null
            if (listening) ctlExecutor.execute { stop("watchdog timeout") }
        }
        watchdog = task
        core.mainHandler.postDelayed(task, WATCHDOG_MS)
    }

    /**
     * 手机端存活保活（空闲常开模式）：收到手机端任意下行（60s 周期 ping）时重置看门狗。
     * 手机崩溃/失联后 ping 停止，看门狗在 [WATCHDOG_MS] 内自然到期关麦（隐私兜底）。
     * 未在监听时调用无副作用（看门狗本就不在跑）。
     */
    fun onPhoneAlive() {
        if (listening) armWatchdog()
    }

    private fun cancelWatchdog() {
        watchdog?.let { core.mainHandler.removeCallbacks(it) }
        watchdog = null
    }

    // ── 帧解析 ──

    /** 取 caps 里第一个非空字符串（控制指令只有 1 个元素，容忍脏序） */
    private fun firstString(args: Caps?): String? {
        if (args == null) return null
        for (i in 0 until args.size()) {
            val s = runCatching { args.at(i).getString() }.getOrNull() ?: continue
            if (!s.isNullOrEmpty()) return s
        }
        return null
    }

    /**
     * 从字幕帧提取 ASR JSON。官方结果帧可能把 JSON 放在 caps 字符串元素或二进制 value 里
     * （两处都探测）；找不到形如 `{...}` 的内容返回 null。
     */
    private fun extractJson(args: Caps?, value: ByteArray?): String? {
        if (value != null && value.isNotEmpty()) {
            val s = String(value, Charsets.UTF_8).trim()
            if (s.startsWith("{") && s.endsWith("}")) return s
        }
        if (args != null) {
            for (i in 0 until args.size()) {
                val s = runCatching { args.at(i).getString() }.getOrNull()?.trim() ?: continue
                if (s.startsWith("{")) return s
            }
        }
        return null
    }
}
