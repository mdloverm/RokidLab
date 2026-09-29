// Rokid SDK 的 sendCustomCmd 自带「注意 Caps 体积」的废弃标记，
// 但这是当前唯一的下行通道，短期内不可能替换，故整文件抑制该告警。
@file:Suppress("DEPRECATION")

package com.rokidlab.phone.glasses

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.rokid.cxr.Caps
import com.rokid.cxr.link.CXRLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.TreeMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 环境音监听协调器（手机端）—— 双模式会话管理：
 *
 *  - **一次性**（[listenOnce]，AI 工具入口）：听一段 → 转写文字 → 自动关麦。
 *    只有用户明确说「听听周围」才开启，听完即止，默认不开启、不持久、不自动恢复。
 *  - **持续**（[startContinuous] / [stopContinuous]，聊天顶栏耳朵按钮入口）：
 *    开启后眼镜远场麦听到的话每句直接作为新的用户消息进入 AI 对话（显示对方的话 →
 *    AI 正常回答），直到用户再点一次关闭。会话状态**不持久化**：App 重启 / 断线后
 *    一律回到关闭态（用户明确要求默认关）。
 *
 * ## 链路全景
 *
 * ```
 * 本类 --sendCustomCmd(TOPIC_AMBIENT_CTRL, "start")--> 眼镜端 AmbientListenController
 *   ⚠️ 不先拉官方 AI 助手：它会占住语音租约并在 10s 静默后连带掐掉远场拾音（见 startContinuous 文档）
 *   --sendMessage("Accessibility", "Accessibility_start")--> cxr-service 开远场麦
 *   --> 降噪/opus --> 云端 ASR --> 字幕 JSON 从 "Accessibility" 频道广播回来
 *   --> 眼镜端订阅命中（只取 final 帧）--AsrPushServer.push(MARKER_AMBIENT_TEXT + JSON)--> 本类 onAmbientFrame()
 *   --> 一次性：按句子编号收集，最后一句后 [FINAL_TAIL_MS] 静默即 stop，listenOnce 返回全文
 *   --> 持续：[onFinalSentence] --> AiConversationService.dispatchGlassesAsrText（与正常语音同一入口）
 * ```
 *
 * 停麦后双方各有收尾窗口（眼镜端 DRAIN_MS / 本类 [DRAIN_MS]）：远场字幕的最后一句 final
 * 常在关麦之后才广播回来，不留窗口会丢掉「刚说完就关」的那句。
 *
 * 防自激双保险：眼镜端 TTS 播报期硬闸丢弃字幕帧（主防线）+ 手机端
 * dispatchGlassesAsrText 按「与最近回复内容重合」兜底丢弃。
 */
internal class AmbientListenCoordinator(
    private val appScope: CoroutineScope,
    private val linkProvider: () -> CXRLink?,
    private val linkAlive: () -> Boolean,
    /** 与下行主链路共用的按条串行锁（aiCmdLock） */
    private val cmdLock: Any,
    /** final 句子回调（仅持续模式）：直连 AI 对话分发（asrDeliver 通路） */
    private val onFinalSentence: (String) -> Unit,
) {
    companion object {
        private const val TAG = "AmbientListen"

        /** 一次性模式：最后一句 final 后再静默这么久就收尾返回（说完一句就够） */
        private const val FINAL_TAIL_MS = 2_500L
        /** 一次性模式：在时长上限基础上多留的收尾余量（下发 stop 与链路抖动） */
        private const val STOP_MARGIN_MS = 1_000L
        /**
         * 持续模式句子聚合尾窗：官方远场 ASR 会把一段连续发言切成多个 final（number 递增），
         * 逐句立即分发会让 AI 收到碎片（实测「劳动合同」三个字单独成问，模型只能反问）。
         * 收到 final 后不立即分发，尾窗内到达的相邻 final 合并为一条消息；到点整体分发。
         * 2026-09-29 用户定稿 2s：停顿不超过 2 秒视同一句话；代价是说完后等 2s 才开始回答。
         */
        private const val CONTINUOUS_TAIL_MS = 2_000L
        /**
         * 单句聚合上限（2026-09-29 用户定稿 60s）：从本段第一个 final 入缓冲起跑，
         * 到点强制截断、把已聚合内容整体分发 —— 防止有人不停说话导致 AI 永远没机会回答。
         */
        private const val UTTERANCE_CAP_MS = 60_000L
        /**
         * 闭麦恢复兜底：回答期间真闭麦（远场字幕链路 stop），正常由眼镜端播完标记
         * （MARKER_AMBIENT_TTS_DONE → [onAiRoundDone]）重开麦；本兜底覆盖标记丢失
         * （旧版眼镜端 / TTS 异常）的场景，到点强制恢复接收，不会永久聋掉。
         */
        private const val RESUME_FALLBACK_MS = 150_000L
        /**
         * 停麦后仍接收上行的收尾窗口（略大于眼镜端 DRAIN_MS）：远场字幕的**最后一句 final
         * 往往在关麦之后才广播回来**（实测差 50ms），不留窗口会把「刚说完就点关」的那句话丢掉。
         */
        private const val DRAIN_MS = 3_500L
    }

    private enum class Mode { IDLE, ONCE, CONTINUOUS }

    /** 会话是否活跃（onAmbientFrame 丢弃非活跃期的迟到帧） */
    @Volatile
    var active: Boolean = false
        private set

    /** 当前模式（active=true 时有意义） */
    @Volatile
    private var mode = Mode.IDLE

    private val stateLock = Any()

    // ── 一次性模式状态 ──
    /** 收集的句子（number → 文本），TreeMap 保证按序拼接 */
    private val sentences = TreeMap<Int, String>()

    /** 最近一次收到 final 的时刻（0=还没有；listenOnce 据此算尾窗截止） */
    @Volatile
    private var lastFinalAtMs = 0L

    // ── 持续模式状态 ──
    /** 已上行的最后一个 final 句子编号（官方可能重发同一句，按 number 去重） */
    private var lastFinalNumber = -1

    /** 聚合缓冲（[stateLock] 保护）：尾窗内待合并的 final 句子 */
    private val pendingSentences = ArrayList<String>()

    /** 句子聚合尾窗定时（每收到 final 刷新；到点 flushPendingSentence 整体分发） */
    private var sentenceFlushJob: Job? = null

    /** 单句聚合上限定时（本段第一个 final 入缓冲时起跑；到点强制 flush，见 [UTTERANCE_CAP_MS]） */
    private var utteranceCapJob: Job? = null

    /** 闭麦恢复兜底定时（回答开始时起跑；播完标记到达即取消，见 [RESUME_FALLBACK_MS]） */
    private var resumeFallbackJob: Job? = null

    /**
     * 回答期间闭麦中（2026-09-29 用户定稿）：flush 分发后远场字幕链路已 stop，
     * 期间到达的帧（收尾窗口残留）一律丢弃；眼镜端播完标记到达后恢复接收。
     */
    @Volatile
    private var suspended = false

    /** UI 可观察的持续模式状态（耳朵图标/副标题据此点亮与熄灭） */
    var uiActive by mutableStateOf(false)
        private set

    /** 收尾窗口截止时刻（停麦后仍接收上行的时长，见 [DRAIN_MS]） */
    @Volatile
    private var acceptUntilMs = 0L

    /** 停麦前的模式：收尾窗口内按它继续处理迟到帧（否则最后一句被吞） */
    @Volatile
    private var drainMode = Mode.IDLE

    /** 一次性模式阻塞等待用的锁/条件（listenOnce 在工具的后台线程调用） */
    private val waitLock = ReentrantLock()
    private val waitCond = waitLock.newCondition()

    /**
     * 开场代次：startContinuous 开头取号，拉起助手（0.5~3s 阻塞）之后校验。
     * 期间用户又点了关闭（stopContinuous）则作废本次开场 —— 否则关掉之后
     * 这条 start 仍会把远场麦打开（"显示已关、麦还开着"）。
     */
    private val genLock = Any()
    private var gen = 0

    // ──────────────────────────────────────────────
    //  一次性模式（AI 工具）
    // ──────────────────────────────────────────────

    /**
     * 听一段周围的声音并返回转写文字（阻塞，工具在后台线程调用）。
     *
     * 流程：下发 start → 收集 final 句子 → 最后一句后 [FINAL_TAIL_MS] 静默即提前收尾
     * （或到时长上限）→ 下发 stop → 返回全文。
     *
     * @return null = 链路失败没听成；"" = 听了但没听清人话；其余 = 转写文本
     */
    fun listenOnce(durationMs: Long): String? {
        if (active) return null
        if (!sendStart("listen once")) return null
        mode = Mode.ONCE
        active = true
        lastFinalAtMs = 0L
        val deadline = System.currentTimeMillis() + durationMs + STOP_MARGIN_MS
        // 阻塞等：每收到 final 或尾窗到期会被 signalAll 唤醒重算截止时刻
        waitLock.withLock {
            while (active) {
                val now = System.currentTimeMillis()
                val tail = lastFinalAtMs
                val waitUntil = if (tail > 0) minOf(deadline, tail + FINAL_TAIL_MS) else deadline
                if (now >= waitUntil) break
                waitCond.await(waitUntil - now, TimeUnit.MILLISECONDS)
            }
        }
        val result: String
        synchronized(stateLock) {
            result = if (sentences.isEmpty()) "" else sentences.values.joinToString("。")
            sentences.clear()
        }
        sendStop("listen once done")
        return result
    }

    // ──────────────────────────────────────────────
    //  持续模式（聊天顶栏耳朵按钮）
    // ──────────────────────────────────────────────

    /**
     * 开启持续录入（幂等：已活跃且已是持续模式直接成功）。
     *
     * ⚠️ 不拉官方 AI 助手：实测拉起后其 `AIUI_SpeechLease` 占住语音租约，10s 无唤醒词就
     * `round=closed` 并连带 `stopRecord(subtitle)` 把远场拾音掐掉（2026-09-28 12:50:47 实证），
     * 且官方助手界面弹窗期间手机端也等不到场景上报。点耳朵只开拾音，助手由用户自己按眼镜键唤起。
     *
     * @return true = 已开启（或本就开着）；false = 下发失败（链路不可用/被一次性占用/中途被关闭）
     */
    fun startContinuous(reason: String): Boolean {
        if (active && mode == Mode.CONTINUOUS) return true
        if (active) {
            Log.w(TAG, "startContinuous($reason): busy in $mode")
            return false
        }
        uiActive = true
        val myGen = synchronized(genLock) { ++gen }
        if (!sendStart("continuous")) {
            uiActive = false
            return false
        }
        synchronized(genLock) {
            // 期间用户点了关闭：不要把它重新打开
            if (gen != myGen) {
                Log.i(TAG, "startContinuous($reason): superseded by stop, abort")
                sendStop("superseded")
                return false
            }
        }
        mode = Mode.CONTINUOUS
        active = true
        suspended = false
        synchronized(stateLock) {
            lastFinalNumber = -1
            pendingSentences.clear()  // 开新会话：丢弃上次会话可能残留的未分发句子
        }
        Log.i(TAG, "ambient continuous ON ($reason)")
        return true
    }

    /** 关闭持续录入（幂等；一次性会话进行中则忽略）。 */
    fun stopContinuous(reason: String) {
        if (active && mode != Mode.CONTINUOUS) {
            Log.w(TAG, "stopContinuous($reason): busy in $mode, ignore")
            return
        }
        // 作废进行中的 startContinuous（其助手拉起尚未返回时，别让它随后把麦打开）
        synchronized(genLock) { gen++ }
        cancelUtteranceCap()
        resumeFallbackJob?.cancel()
        resumeFallbackJob = null
        suspended = false
        sendStop(reason)
    }

    /**
     * 回答期间闭麦（2026-09-29 用户定稿）：ambient 问答分发前把远场字幕链路 stop ——
     * AI 生成 + 播报期间真闭麦（不再靠 TTS 硬闸丢帧模拟），不拾旁人插话、更省电；
     * 只发命令不改会话状态（uiActive 保持点亮：用户视角聆听仍在进行）。
     * 正常路径由眼镜端播完标记 → [onAiRoundDone] 恢复接收；标记丢失由
     * [RESUME_FALLBACK_MS] 兜底强制恢复，不会永久聋掉。
     */
    fun onAiRoundStart() {
        if (!active || mode != Mode.CONTINUOUS) return
        suspended = true
        // ⚠️ 必须发 "pause" 而非 "stop"：stop 会把眼镜端 sessionActive 清掉，
        // 回复播完时 onLabReplyPlaybackFinished 判定 ambient 不活跃、不推播完标记，
        // 手机端要等 150s 兜底才重开麦 →「第二轮听不到」（2026-09-29 12:59 实证）。
        // pause 只关麦、会话保持，resume 随播完标记到达。
        sendCtrl("pause", "paused for reply")
        resumeFallbackJob?.cancel()
        resumeFallbackJob = appScope.launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(RESUME_FALLBACK_MS)
            Log.w(TAG, "tts-done marker lost, force resume after ${RESUME_FALLBACK_MS / 1000}s")
            resumeAfterReply()
        }
    }

    /** 眼镜端回复播报完成（MARKER_AMBIENT_TTS_DONE）或回答失败：恢复远场接收，进入下一轮。 */
    fun onAiRoundDone() {
        if (!suspended) return
        resumeFallbackJob?.cancel()
        resumeFallbackJob = null
        resumeAfterReply()
    }

    private fun resumeAfterReply() {
        suspended = false
        if (active && mode == Mode.CONTINUOUS) {
            sendCtrl("resume", "resume listening")
            // 官方字幕管道随 resume 重开，number 可能重新计数 —— 重置去重游标，
            // 避免新一轮首个 final 与上一轮尾号相同被误判为重发丢弃
            synchronized(stateLock) { lastFinalNumber = -1 }
            Log.i(TAG, "ambient resume listening after reply")
        }
    }

    private fun cancelUtteranceCap() {
        utteranceCapJob?.cancel()
        utteranceCapJob = null
    }

    // ──────────────────────────────────────────────
    //  会话底层
    // ──────────────────────────────────────────────

    /**
     * 下发环境音控制命令（"start"/"stop"），返回发送是否成功。
     * 会话开关（sendStart/sendStop）与回答期闭麦/恢复（onAiRoundStart/onAiRoundDone）共用底层。
     */
    private fun sendCtrl(cmd: String, reason: String): Boolean {
        val link = linkProvider() ?: run {
            Log.w(TAG, "$cmd($reason): no CXR link")
            return false
        }
        if (!linkAlive()) {
            Log.w(TAG, "$cmd($reason): link not alive")
            return false
        }
        val caps = Caps().also { it.write(cmd) }
        val r = runCatching {
            synchronized(cmdLock) { link.sendCustomCmd(LinkProtocol.TOPIC_AMBIENT_CTRL, caps) }
        }.getOrDefault(-3)
        if (r != 0) Log.w(TAG, "$cmd($reason): sendCustomCmd -> $r")
        return r == 0
    }

    private fun sendStart(reason: String): Boolean = sendCtrl("start", reason)

    private fun sendStop(reason: String) {
        val wasActive = active
        drainMode = if (wasActive) mode else Mode.IDLE
        acceptUntilMs = System.currentTimeMillis() + DRAIN_MS
        mode = Mode.IDLE
        active = false
        uiActive = false
        if (!wasActive) {
            Log.i(TAG, "stop($reason): not active, stop cmd still sent for safety")
        }
        // 一次性等待者可能还在阻塞：唤醒让它带着结果退出
        waitLock.withLock { waitCond.signalAll() }
        if (linkProvider() != null && linkAlive()) {
            val ok = sendCtrl("stop", reason)
            Log.i(TAG, "ambient listen OFF($reason) -> ${if (ok) 0 else "send failed"}")
        } else {
            Log.w(TAG, "ambient listen OFF($reason): link gone, glasses watchdog will close mic")
        }
    }

    /** 链路断开/会话清理：回到关闭态（无持久化，不自动恢复）。 */
    fun markLinkDown() {
        cancelUtteranceCap()
        resumeFallbackJob?.cancel()
        resumeFallbackJob = null
        suspended = false
        sentenceFlushJob?.cancel()
        sentenceFlushJob = null
        synchronized(stateLock) { pendingSentences.clear() }
        if (active) {
            mode = Mode.IDLE
            active = false
            uiActive = false
            waitLock.withLock { waitCond.signalAll() }
            Log.i(TAG, "ambient listen off (link down)")
        }
    }

    // ──────────────────────────────────────────────
    //  字幕帧入口
    // ──────────────────────────────────────────────

    /**
     * 眼镜端上来的字幕帧（[LinkProtocol.MARKER_AMBIENT_TEXT] 前缀后的 JSON）。
     * 由 AsrBridgeCoordinator 在后台线程回调；非活跃期丢弃迟到帧（收尾窗口内例外，见 [DRAIN_MS]）。
     * 只处理 final 帧：partial 流式更新对两种模式都无意义。
     */
    fun onAmbientFrame(json: String) {
        // 回答期间闭麦：远场字幕链路已 stop，收尾窗口内的残留帧一律丢弃（说了也听不见）
        if (suspended) return
        if (!active && System.currentTimeMillis() > acceptUntilMs) return
        val obj = runCatching { JSONObject(json) }.getOrNull() ?: run {
            Log.w(TAG, "ambient frame not JSON: ${json.take(80)}")
            return
        }
        if (!obj.optBoolean("final", false)) return
        val content = obj.optString("content").trim()
        if (content.isEmpty()) return
        val number = obj.optInt("number", -1)
        // 停麦后的收尾窗口：按停麦前的模式继续处理（否则最后一句会被 IDLE 分支吞掉）
        val effMode = when {
            active -> mode
            System.currentTimeMillis() <= acceptUntilMs -> drainMode
            else -> return
        }
        when (effMode) {
            Mode.ONCE -> {
                // 一次性模式已返回结果就不再收集（避免脏句串到下一次会话）
                if (!active) return
                val isNew = synchronized(stateLock) {
                    if (number >= 0 && sentences.containsKey(number)) {
                        false  // 官方重发去重
                    } else {
                        val key = if (number >= 0) number else (sentences.keys.lastOrNull()?.plus(1) ?: 0)
                        sentences[key] = content
                        lastFinalAtMs = System.currentTimeMillis()
                        true
                    }
                }
                if (!isNew) return
                Log.i(TAG, "ambient once (#$number): $content")
                // 唤醒 listenOnce 重算尾窗截止（最后一句 + FINAL_TAIL_MS）
                waitLock.withLock { waitCond.signalAll() }
            }
            Mode.CONTINUOUS -> {
                synchronized(stateLock) {
                    if (number >= 0) {
                        if (number == lastFinalNumber) return  // 官方重发去重
                        lastFinalNumber = number
                    }
                    if (pendingSentences.isEmpty()) {
                        // 本段第一句：起跑 60s 聚合上限（防不停说话导致 AI 永远插不上话）
                        cancelUtteranceCap()
                        utteranceCapJob = appScope.launch(Dispatchers.IO) {
                            kotlinx.coroutines.delay(UTTERANCE_CAP_MS)
                            Log.i(TAG, "utterance cap ${UTTERANCE_CAP_MS / 1000}s reached, force flush")
                            flushPendingSentence()
                        }
                    }
                    pendingSentences.add(content)
                }
                val late = if (active) "" else " (drained after stop)"
                Log.i(TAG, "ambient final (#$number)$late: $content")
                // 重跑尾窗：官方把一段连续发言切成多个 final，停顿 ≤2s 视同一句话，
                // 尾窗内合并成一条消息整体分发（逐句分发会让 AI 收到碎片，见 CONTINUOUS_TAIL_MS）
                armSentenceFlush()
            }
            Mode.IDLE -> {}
        }
    }

    /**
     * 句子聚合尾窗：最后一句 final 后 [CONTINUOUS_TAIL_MS] 内没有新句子就整体分发。
     * 每收到 final 重跑；stopContinuous **不取消**本定时 —— 用户手动关闭后，
     * 收尾窗口（DRAIN_MS）内迟到的最后一句也要照常得到回答。
     */
    private fun armSentenceFlush() {
        sentenceFlushJob?.cancel()
        sentenceFlushJob = appScope.launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(CONTINUOUS_TAIL_MS)
            flushPendingSentence()
        }
    }

    /** 把聚合缓冲里的句子合并成一条消息分发（幂等：并发/重复触发时缓冲为空直接返回）。 */
    private fun flushPendingSentence() {
        val merged = synchronized(stateLock) {
            if (pendingSentences.isEmpty()) return
            val text = pendingSentences.joinToString("").trim()
            pendingSentences.clear()
            text
        }
        cancelUtteranceCap()  // 本段已截断发送，60s 聚合上限随之结束（下一段重新起跑）
        if (merged.isEmpty()) return
        Log.i(TAG, "ambient flush (${merged.length} chars): $merged")
        runCatching { onFinalSentence(merged) }
            .onFailure { Log.e(TAG, "onFinalSentence failed", it) }
    }
}
