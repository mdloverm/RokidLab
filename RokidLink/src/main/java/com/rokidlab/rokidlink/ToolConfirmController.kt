package com.rokidlab.rokidlink

import android.util.Log
import com.rokid.cxr.Caps

/**
 * KeyButtonService 的工具确认窗口控制器（v3.9 拆分自 KeyButtonService）。
 *
 * 手机端副作用工具（call_phone 等）执行前的用户确认交互：
 * 悬浮层显示操作摘要 + TTS 播报，短按 = 允许，双击/长按 = 取消，
 * 30s 无操作超时视为取消。结果经 [LinkProtocol.TOPIC_TOOL_CONFIRM_RESULT] 上行回手机端。
 */
internal class ToolConfirmController(
    private val service: KeyButtonService,
    private val core: KeyServiceCore,
    private val overlays: GlassesOverlayController,
) {
    companion object {
        private const val TAG = KeyButtonService.TAG
        /** 工具确认窗口时长：超时未应答视为取消 */
        private const val TOOL_CONFIRM_WINDOW_MS = 30_000L
        /** 确认应答后的按键吞没窗口（UP/CLICK 连发去重） */
        private const val KEY_SUPPRESS_AFTER_CONFIRM_MS = 1_500L
    }

    /** 进行中的确认请求 id（null = 无等待中的确认） */
    @Volatile
    private var pendingToolConfirmId: String? = null

    /** 确认超时任务（30s 无操作视为取消） */
    private var toolConfirmTimeoutRunnable: Runnable? = null

    /** 应答后的按键吞没窗口截止时间（UP/CLICK 连发时避免误触发启动目标）。
     *  KeyRouteCoordinator 在按键分发前读取。 */
    @Volatile
    var suppressKeyUntilMs = 0L
        private set

    /** 是否有等待中的工具确认（KeyRouteCoordinator 判断按键语义用） */
    val hasPending: Boolean get() = pendingToolConfirmId != null

    /** 接收手机端下发的工具确认请求：悬浮层显示摘要 + TTS 播报 + 超时定时器 */
    fun handleRequest(args: Caps?) {
        try {
            val f = capsToStrings(args)
            val id = f.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return
            val tool = f.getOrNull(1) ?: ""
            val summary = f.getOrNull(2) ?: ""
            core.mainHandler.post {
                clear()
                pendingToolConfirmId = id
                overlays.showLyricOverlay("⚠ $summary\n[短按]允许  [双击]取消")
                if (summary.isNotBlank()) {
                    runCatching { TtsPlaybackHelper.play(service, "是否$summary？短按确认，双击取消") }
                }
                toolConfirmTimeoutRunnable = Runnable {
                    Log.i(TAG, "tool confirm timeout (id=$id) -> deny")
                    respond(false, timeout = true)
                }.also { core.mainHandler.postDelayed(it, TOOL_CONFIRM_WINDOW_MS) }
                Log.i(TAG, "tool confirm pending: id=$id tool=$tool")
            }
        } catch (e: Exception) {
            Log.e(TAG, "handleToolConfirm error", e)
        }
    }

    /** 应答确认结果并清理窗口（confirm/deny/timeout 共用）。 */
    fun respond(allowed: Boolean, timeout: Boolean = false) {
        val id = pendingToolConfirmId ?: return
        clear()
        // UP/CLICK 连发吞没窗口：应答后 1.5s 内的按键广播全部忽略，
        // 避免同一次按压的第二条广播落到「启动配置目标」上
        suppressKeyUntilMs = System.currentTimeMillis() + KEY_SUPPRESS_AFTER_CONFIRM_MS
        val b = core.bridge
        runCatching {
            if (b != null) {
                val caps = Caps()
                caps.write(id)
                caps.write(if (allowed) "yes" else "no")
                val r = b.sendMessage(LinkProtocol.TOPIC_TOOL_CONFIRM_RESULT, caps)
                Log.i(TAG, "toolConfirm respond(id=$id, allowed=$allowed, timeout=$timeout) -> $r")
            } else {
                Log.w(TAG, "toolConfirm respond(id=$id) dropped: no bridge")
            }
        }.onFailure { Log.e(TAG, "toolConfirm respond error", it) }
    }

    /** 仅清理窗口状态与 UI（不清 suppress 窗口）。 */
    fun clear() {
        toolConfirmTimeoutRunnable?.let { core.mainHandler.removeCallbacks(it) }
        toolConfirmTimeoutRunnable = null
        pendingToolConfirmId = null
        overlays.hideLyricOverlay()
    }
}
