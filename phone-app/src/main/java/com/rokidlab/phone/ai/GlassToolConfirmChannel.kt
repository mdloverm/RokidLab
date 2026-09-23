package com.rokidlab.phone.ai

import android.util.Log
import com.rokid.cxr.Caps
import com.rokidlab.phone.ai.approval.ApprovalGate
import com.rokidlab.phone.glasses.CxrLHiRokidSession
import com.rokidlab.phone.glasses.GlassesHandshake
import com.rokidlab.phone.glasses.LinkProtocol
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * L4 agent/GlassToolConfirmChannel —— 副作用工具的眼镜端确认通道（Phase 4）。
 *
 * 下行：[LinkProtocol.TOPIC_TOOL_CONFIRM]（caps = [requestId, 工具名, 摘要]），
 *   眼镜端悬浮层显示摘要 + TTS 播报，短按 = 允许 / 双击·长按 = 取消，30s 无回执 = 超时（非取消）。
 * 上行：[LinkProtocol.TOPIC_TOOL_CONFIRM_RESULT]（caps = [requestId, "yes"/"no"]），
 *   由 CxrLHiRokidSession.registerGlobalCmdListener 回调 [onResult]。
 *
 * 未连接 / 眼镜端旧版（未订阅确认通道）时 → [isAvailable] 为 false，由 [ApprovalGate]
 * 改问**手机端通道**（[PhoneToolConfirmChannel]，见 `ApprovalGate.activeChannel`）；
 * 两条通道都不可用时才按工具声明的 `ToolConfirmPolicy` 分流（PROCEED 放行 / BLOCK 拒绝）。
 * 已发送但 30s 无回执 → [confirm] 返回 false 且 [wasCancelled] 为 false，
 * 闸门同样按"没问到"处理（仅眼镜端显式回 "no" 才算用户取消 → 拒绝）。
 * 本通道**常驻**（见 [global]），会话上线时 [bind]、下线时 [unbind]，只要求眼镜在线即可用。
 *
 * ★ 本类只负责**传输**：给用户看的操作摘要在调用时由闸门传入（[confirm] 的 `prompt` 参数），
 *   不再自己从 args 拼 —— 摘要的唯一产地是工具自己的 `ToolEntry.summarize`。
 */
class GlassToolConfirmChannel : ApprovalGate.ConfirmResolver {

    companion object {
        private const val TAG = "ToolConfirm"
        /** 手机端等待上限：略大于眼镜端 30s 窗口，让「超时取消」回执先到达 */
        private const val CONFIRM_TIMEOUT_MS = 35_000L

        /**
         * 全局唯一通道（常驻）：**动态绑定当前会话**。
         *
         * 旧实现 = 会话实例 + Session.init 注入 / cleanup 置 null。实测踩坑：
         * `ApprovalGate: audit: source=conversation tool=call_phone risk=EXTERNAL_SIDE_EFFECT -> ALLOW`
         * （改造前日志 TAG 为 `ToolPolicy`；21:04:42，用户已授予电话权限却只能退回拨号盘）——会话生命周期抖动（重连/重建/cleanup）后
         * 通道就变成了 null。现在通道常驻，只要眼镜会话在线就可用。
         */
        val global: GlassToolConfirmChannel by lazy { GlassToolConfirmChannel() }

        /**
         * 活着的会话（按登记先后）。**不能只记一个**：实测存在「探针会话建立→3ms 后释放」的抖动
         * （21:22:48.154 bind、.157 unbind），单变量会被它清空，而真正在用的会话就被漏掉 →
         * `call_phone -> DENY (no confirmation channel)`。
         */
        private val liveSessions = java.util.concurrent.CopyOnWriteArrayList<CxrLHiRokidSession>()

        /** 会话建立：登记（重复登记时移到队尾 = 最新优先） */
        fun bind(session: CxrLHiRokidSession) {
            liveSessions.remove(session)
            liveSessions.add(session)
            Log.i(TAG, "confirm channel bound (live=${liveSessions.size})")
        }

        /** 会话释放：只摘掉自己，其它活会话仍可用 */
        fun unbind(session: CxrLHiRokidSession) {
            if (liveSessions.remove(session)) {
                Log.i(TAG, "confirm channel unbound (live=${liveSessions.size})")
            }
        }

        /**
         * 应用级会话兜底（由 [com.rokidlab.phone.app.LabApplication.setCxrL] 注入）。
         *
         * ★ 为什么必须有：登记表 [liveSessions] 是**生命周期登记**，会被 `cleanup()` 摘掉；
         * 而 `cleanup()` 在「重连 / 重授权」流程里**每一轮都会调一次**
         * （`ConnectionService.connectAndRunCustomAppOperation()` 第一行就是 `session.cleanup()`），
         * 清理完又用**同一个实例**重连，而 `bind()` 只在构造器的 `init` 里跑过一次
         * → 登记表被清空且**永不再填**。
         * 实测症状（2026-09-12 12:07:13）：`after ensureGlassesLinkRunning: capSupport=true session=false`
         * —— 眼镜端握手都收到了（眼镜在线、RokidLink 在跑），却因登记表为空被判「眼镜端未连接」，
         * 图片下发被直接丢弃。应用单例持有的是**当前真正在用**的会话，用它兜底可一次性消除这类"登记漂移"。
         *
         * 返回 null / 已断开的旧会话都安全：取值处仍有 `cxrlConnected && cxrLink != null` 校验。
         */
        @Volatile
        var appSessionProvider: (() -> CxrLHiRokidSession?)? = null
    }

    /** 当前会话：优先最新的「已连接且 CXR link 可用」者 → 最新登记项 → 应用单例兜底 */
    private val session: CxrLHiRokidSession?
        get() = liveSessions.lastOrNull { it.cxrlConnected && it.cxrLink != null }
            ?: liveSessions.lastOrNull()
            ?: appSessionProvider?.invoke()

    /**
     * 供其它下行通道（如 `show_image` 图片下发）复用的当前可用会话。
     * 仅返回「已连接且 CXR link 可用」者；无在线会话时返回 null（调用方静默跳过）。
     */
    fun liveSession(): CxrLHiRokidSession? =
        session?.takeIf { it.cxrlConnected && it.cxrLink != null }

    /** 审计标识：日志里区分"这次问的是眼镜还是手机"（手机通道是 `phone`） */
    override val channelId: String = "glass"

    /** 上一次确认是否被用户**显式取消**（区分「取消」=拒绝 与「超时」=没问到） */
    @Volatile
    private var lastCancelled = false

    override fun wasCancelled(): Boolean = lastCancelled

    private val seq = AtomicLong(0)
    private val pending = ConcurrentHashMap<String, ConfirmWaiter>()

    private class ConfirmWaiter {
        val createdAt = System.currentTimeMillis()
        val latch = CountDownLatch(1)
        @Volatile var allowed = false
    }

    override fun isAvailable(): Boolean {
        val s = session ?: return false
        // 确认指令走 CXR 通道：只要求会话在线 + CXR link 可用。
        // （旧实现额外要求 glassBtConnected，蓝牙链路抖动时会误判「无确认通道」→ 打电话被策略挡）
        if (!(s.cxrlConnected && s.cxrLink != null)) return false
        // 插播 B：眼镜端已明确不支持确认通道（旧版 v1，握手超时判定）时直接判不可用，
        // 让 ApprovalGate 立刻按「无通道」降级放行，而不是白等 35s 超时。
        // 尚未握手（null）时走乐观路径——依赖眼镜端 30s 窗口兜底。
        return GlassesHandshake.supports(LinkProtocol.Cap.TOOL_CONFIRM) != false
    }

    /**
     * 阻塞等待眼镜端用户确认（调用方均为后台线程：对话 runTool / ToolGateway）。
     * 返回 true = 用户短按允许；false = 双击/长按取消、超时或链路不可用。
     *
     * @param prompt 给用户看的操作摘要，由 [ApprovalGate] 传入（其产地是
     *   [com.rokidlab.phone.ai.tools.ToolEntry.summarize]，与工具定义同源）——
     *   本类只负责传输，不再自己从 args 拼。
     */
    override fun confirm(toolName: String, prompt: String): Boolean {
        lastCancelled = false
        val s = session
        val link = s?.cxrLink
        if (s == null || link == null || !isAvailable()) {
            Log.w(TAG, "confirm request rejected: link not ready")
            return false
        }
        val id = "tc-${System.currentTimeMillis()}-${seq.incrementAndGet()}"
        val waiter = ConfirmWaiter()
        pending[id] = waiter
        return try {
            val caps = Caps()
            caps.write(id)
            caps.write(toolName)
            caps.write(prompt)
            val r = s.rawSendCustomCmd(link, LinkProtocol.TOPIC_TOOL_CONFIRM, caps)
            Log.i(TAG, "confirm request sent (id=$id tool=$toolName) -> $r")
            if (r < 0) {
                Log.w(TAG, "confirm request send failed -> deny")
                return false
            }
            if (!waiter.latch.await(CONFIRM_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                Log.w(TAG, "confirm timeout (id=$id) -> deny")
                false
            } else {
                waiter.allowed
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        } finally {
            pending.remove(id)
        }
    }

    /** 眼镜端确认结果回执（由 Session 全局指令监听回调，binder 线程安全）。 */
    fun onResult(id: String, allowed: Boolean) {
        val w = pending.remove(id) ?: run {
            Log.w(TAG, "confirm result for unknown/expired id=$id")
            return
        }
        w.allowed = allowed
        lastCancelled = !allowed   // 眼镜端回执「no」= 用户显式取消
        w.latch.countDown()
        Log.i(TAG, "confirm result (id=$id allowed=$allowed)")
    }

    /**
     * 是否有等待中的眼镜端确认（v2c 审批手势的生效条件）：
     * MotionRuleEngine 只在「审批弹窗确实在等用户答复」期间才把手势映射为确认/取消，
     * 平时的点头摇头绝不触碰审批 —— 防误触的边界就在这一查。
     */
    fun approvalPending(): Boolean = pending.isNotEmpty()

    /**
     * v2c 审批手势解除：MotionRuleEngine 检测到点头（=允许）/摇头（=取消）时调用。
     * 只解除**最早**挂起的确认（正常时刻至多一个；并发多个时按排队先后）。
     *
     * @return true = 成功解除（有 pending 且已按手势回执）；false = 无等待中的确认，手势无效果
     */
    fun resolveByMotion(allowed: Boolean): Boolean {
        val earliest = pending.entries.minByOrNull { it.value.createdAt }?.key ?: return false
        Log.i(TAG, "resolve by motion (allowed=$allowed id=$earliest)")
        onResult(earliest, allowed)
        return true
    }

    /** 清空全部等待中的确认（连接断开 / Session.cleanup 时调用，避免线程挂满 35s）。 */
    fun abortAll() {
        val n = pending.size
        pending.values.forEach { it.latch.countDown() }
        pending.clear()
        if (n > 0) Log.w(TAG, "aborted $n pending confirmations")
    }

    // 操作摘要不再由本类生成：闸门把 ToolEntry.summarize 的产物随 confirm(prompt) 传进来，
    // 「摘要产地唯一」这条约束由 ApprovalGate 保证（见类注释）。
}
