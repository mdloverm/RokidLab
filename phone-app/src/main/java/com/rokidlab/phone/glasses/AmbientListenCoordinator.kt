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
         * 持续模式静默自动停：开启后（或最后一句 final 后）持续这么久没听到新句子
         * 就自动关麦回关闭态 —— 对话结束不该让远场麦空转（2026-09-28 用户定稿 10s）。
         * 眼镜端 15min 看门狗仅作手机崩溃/失联的隐私兜底。
         */
        private const val SILENCE_STOP_MS = 10_000L
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

    /** 静默自动停定时：开启时起跑，每收到新 final 刷新；到点自动关麦（stop/断线时取消） */
    private var silenceJob: Job? = null

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
        synchronized(stateLock) { lastFinalNumber = -1 }
        armSilenceStop()
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
        cancelSilenceStop()
        sendStop(reason)
    }

    /**
     * 静默自动停：从现在起 [SILENCE_STOP_MS] 内没有新 final 句子就自动关麦回关闭态。
     * 开启时起跑；每收到一句 final 由 onAmbientFrame 刷新。对话结束后远场麦不空转。
     */
    private fun armSilenceStop() {
        cancelSilenceStop()
        silenceJob = appScope.launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(SILENCE_STOP_MS)
            if (active && mode == Mode.CONTINUOUS) {
                Log.i(TAG, "ambient continuous auto-stop after ${SILENCE_STOP_MS / 1000}s silence")
                stopContinuous("silence timeout")
            }
        }
    }

    private fun cancelSilenceStop() {
        silenceJob?.cancel()
        silenceJob = null
    }

    // ──────────────────────────────────────────────
    //  会话底层
    // ──────────────────────────────────────────────

    private fun sendStart(reason: String): Boolean {
        val link = linkProvider() ?: run {
            Log.w(TAG, "start($reason): no CXR link")
            return false
        }
        if (!linkAlive()) {
            Log.w(TAG, "start($reason): link not alive")
            return false
        }
        val caps = Caps().also { it.write("start") }
        val r = runCatching {
            synchronized(cmdLock) { link.sendCustomCmd(LinkProtocol.TOPIC_AMBIENT_CTRL, caps) }
        }.getOrDefault(-3)
        if (r != 0) {
            Log.w(TAG, "start($reason): sendCustomCmd -> $r")
            return false
        }
        return true
    }

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
        val link = linkProvider()
        if (link != null && linkAlive()) {
            val caps = Caps().also { it.write("stop") }
            val r = runCatching {
                synchronized(cmdLock) { link.sendCustomCmd(LinkProtocol.TOPIC_AMBIENT_CTRL, caps) }
            }.getOrDefault(-3)
            Log.i(TAG, "ambient listen OFF($reason) -> $r")
        } else {
            Log.w(TAG, "ambient listen OFF($reason): link gone, glasses watchdog will close mic")
        }
    }

    /** 链路断开/会话清理：回到关闭态（无持久化，不自动恢复）。 */
    fun markLinkDown() {
        cancelSilenceStop()
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
                }
                val late = if (active) "" else " (drained after stop)"
                Log.i(TAG, "ambient final (#$number)$late: $content")
                // 有人说话 → 静默计时重跑（说完 10s 没下一句才自动停）
                if (active && mode == Mode.CONTINUOUS) armSilenceStop()
                runCatching { onFinalSentence(content) }
                    .onFailure { Log.e(TAG, "onFinalSentence failed", it) }
            }
            Mode.IDLE -> {}
        }
    }
}
