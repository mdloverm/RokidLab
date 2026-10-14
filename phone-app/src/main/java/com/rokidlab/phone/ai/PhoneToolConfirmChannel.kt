package com.rokidlab.phone.ai

import android.content.Context
import android.util.Log
import com.rokidlab.phone.ai.approval.ApprovalGate
import com.rokidlab.phone.ai.approval.ToolConfirmActivity
import com.rokidlab.phone.permission.PermissionBridge
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * L4 agent/PhoneToolConfirmChannel —— 副作用工具的**手机端**确认通道。
 *
 * 上行：`startActivity` 拉起 [ToolConfirmActivity]（透明页 + AlertDialog），
 *   用户点「允许」/「取消」→ [onUserAnswer] 回报；40s 无答复 = 超时（**不是**取消）。
 *
 * ## 为什么需要它（这是闸门改造的另一半）
 * [GlassToolConfirmChannel] 要求眼镜在线，而「本机模式」（`chatLocalOnlyEnabled`）
 * 的设计目标恰恰是"不连眼镜也能用" —— 两者在**定义上**互斥，不是配置问题。
 * 于是本机模式下 `ApprovalGate` 找不到通道，需要确认的工具全部落到"问不到用户"分支。
 * 改造前那条分支是**一律放行**，所以 `send_sms` 会在没人点头的情况下真的把短信发出去。
 *
 * 现在：手机通道补上"问一次"的能力，`ApprovalGate.activeChannel` 在眼镜通道不可用时，
 * 用它接管 ⇒ 本机模式与"眼镜在线但用户在看手机"两种情况都有了确认落点。
 *
 * ## 可用性判据
 * [isAvailable] = 此刻能把界面拉起来（App 在前台，或后台但已授悬浮窗 = BAL 豁免），
 * 直接复用 [PermissionBridge.canLaunchUi] —— 那是全项目唯一的"能否从后台拉起界面"判定，
 * 不另起一套。拉不起来时如实返回 false，闸门按"没问到"处理，
 * **不假装问过**（"AI 说在手机上问过你了"而用户什么都没看到，是最坏的一种假成功）。
 *
 * ⚠️ 与眼镜通道一样，[confirm] 是**阻塞**调用（等用户点按钮），
 * 调用方必须是非主线程 —— 对话的工具循环满足。
 */
internal object PhoneToolConfirmChannel : ApprovalGate.ConfirmResolver {

    private const val TAG = "ToolConfirm"

    /**
     * 手机端等待上限。
     *
     * 比眼镜端（35s，见 [GlassToolConfirmChannel]）宽一点：弹窗出现在手机上时用户可能在做别的事，
     * "注意到弹窗并点一下"比"戴着眼镜短按"多花时间。但也不能更长 ——
     * 这段等待发生在对话的工具循环里，整轮回复都被它挡住（用户看到的是转圈）。
     */
    private const val CONFIRM_TIMEOUT_MS = 40_000L

    /** 审计标识：日志里区分"这次问的是眼镜还是手机" */
    override val channelId: String = "phone"

    @Volatile
    private var appContext: Context? = null

    /**
     * 与 [GlassToolConfirmChannel] 同语义：上一次确认是否被用户**显式取消**。
     *
     * ⚠️ 保留此方法只为兼容旧调用；闸门**只调[confirmOutcome]**（读返回值）。
     * 它读不到"是哪一次调用被取消" —— 并发时不可作为判定依据。
     */
    @Deprecated("并发下无法区分是哪一次调用被取消；请用 confirmOutcome 的返回值")
    override fun wasCancelled(): Boolean = false

    private val seq = AtomicLong(0)
    private val pending = ConcurrentHashMap<String, Waiter>()

    /**
     * 一次确认的等待者。**取消标志挂在 waiter 上而不是通道上**——
     * 并发多个确认时，通道级标志会被兄弟调用的 [confirmOutcome] 复位，
     * 导致"用户已取消"被读成"没问到" ⇒ 副作用按 PROCEED 静默放行。
     */
    private class Waiter {
        val latch = CountDownLatch(1)
        @Volatile var allowed = false

        /** 用户显式点了「取消」（区别于没问到 / 页面被回收） */
        @Volatile var cancelled = false

        /**
         * 等待被**中止**：用户叫停当前任务 / 生成被新消息抢占（[abortAll] 带 aborted=true）。
         * 与"没问到"分开 —— 中止必须拒绝，不得按 PROCEED 静默放行（见 [ApprovalGate.ConfirmOutcome.aborted]）。
         */
        @Volatile var aborted = false
    }

    /**
     * 注入（`LabApplication.onCreate` 调用）：持有 applicationContext，并把本通道挂到闸门上。
     *
     * ★ 挂载也放在这里、只此一处：少一个"加了通道忘了注册"的接线点
     * （`ApprovalGate.phoneConfirmationResolver` 若为 null，本机模式下的确认能力就整体失效，
     * 而且**不报错** —— 与 tool 漏登记的失效方式同形）。
     */
    fun init(context: Context) {
        appContext = context.applicationContext
        ApprovalGate.phoneConfirmationResolver = this
        Log.i(TAG, "phone confirm channel registered")
    }

    override fun isAvailable(): Boolean {
        val ctx = appContext ?: return false
        return PermissionBridge.canLaunchUi(ctx)
    }

    /**
     * 阻塞等待用户在手机上确认。返回 true = 用户点了「允许」。
     *
     * @param prompt 给用户看的操作摘要，由 [ApprovalGate] 传入（产地是
     *   [com.rokidlab.phone.ai.tools.ToolEntry.summarize]，与工具定义同源）——
     *   本类只负责把它送到界面上，不再自己从 args 拼。
     */
    override fun confirm(toolName: String, prompt: String): Boolean =
        confirm(toolName, prompt, CONFIRM_TIMEOUT_MS)

    /**
     * 带等待上限的版本（闸门按来源收紧窗口，如 AIUI 页面路径 18s，见 ApprovalGate R7 注释）。
     * 上限只**收紧**不放宽：取 `min(maxWaitMs, 本通道 40s)`。
     */
    override fun confirm(toolName: String, prompt: String, maxWaitMs: Long): Boolean =
        confirmOutcome(toolName, prompt, maxWaitMs).allowed

    /**
     * ★ 闸门唯一调用的入口：一次确认的**结果 + 是否被显式取消**打包返回。
     *
     * 取消标志从**本次**的 [Waiter] 上读，而不是通道的实例字段 ——
     * 与 [GlassToolConfirmChannel.confirmOutcome] 同口径。
     */
    override fun confirmOutcome(
        toolName: String,
        prompt: String,
        maxWaitMs: Long,
    ): ApprovalGate.ConfirmOutcome {
        val ctx = appContext ?: return ApprovalGate.ConfirmOutcome.NOT_ANSWERED
        if (!isAvailable()) return ApprovalGate.ConfirmOutcome.NOT_ANSWERED
        val id = "pc-${System.currentTimeMillis()}-${seq.incrementAndGet()}"
        val waiter = Waiter()
        pending[id] = waiter
        val wait = minOf(maxWaitMs, CONFIRM_TIMEOUT_MS)
        return try {
            ctx.startActivity(ToolConfirmActivity.createIntent(ctx, id, toolName, prompt, wait))
            val answered = waiter.latch.await(wait, TimeUnit.MILLISECONDS)
            when {
                // 中止优先判定：abortAll(aborted=true) 会 countDown，await 返回 true
                waiter.aborted -> ApprovalGate.ConfirmOutcome.ABORTED
                !answered -> {
                    Log.w(TAG, "phone confirm timeout (id=$id tool=$toolName)")
                    ApprovalGate.ConfirmOutcome.NOT_ANSWERED
                }

                else -> ApprovalGate.ConfirmOutcome(waiter.allowed, waiter.cancelled)
            }
        } catch (e: InterruptedException) {
            // ★ 2026-10-13 审计修 H1：中断 = 用户叫停 / 代际被抢占（runInterruptible 把协程取消
            //   翻译成线程中断）。改造前这里返回 NOT_ANSWERED，闸门于是对 PROCEED 工具**静默放行**
            //   —— 用户已叫停，副作用照样执行。现在如实报"被中止"，闸门一律拒绝。
            Thread.currentThread().interrupt()
            ApprovalGate.ConfirmOutcome.ABORTED
        } catch (e: Exception) {
            // BAL 拦截是**静默丢弃**（既不抛异常也不返回失败），能走到这里的只有
            // "Activity 未注册 / 系统拒绝启动"这类硬错误 —— 如实报"没问到"，让闸门按声明分流。
            Log.w(TAG, "phone confirm launch failed (id=$id): ${e.javaClass.simpleName}: ${e.message}")
            ApprovalGate.ConfirmOutcome.NOT_ANSWERED
        } finally {
            pending.remove(id)
        }
    }

    /**
     * 用户答复回执（由 [ToolConfirmActivity] 调用，主线程）。
     *
     * @return true = 本次回执被认领（对应等待者还在）；false = 已超时/已被中止/是别的调用的 id。
     *   ★ 返回值的用途（2026-10-13 审计修 M2）：界面据此给"本次确认已过期"的反馈 ——
     *   改造前这里只落一行日志就返回，用户点的是**已经不作数的**弹窗却看不到任何提示，
     *   误以为是自己批准了（而副作用可能早已按超时策略发生或已放弃）。
     * @param cancelled true = 用户**显式取消**（⇒ 拒绝）；false = 没问到 / 页面被回收
     *   （⇒ 由闸门按工具声明的 `ToolConfirmPolicy` 分流）。
     *   ⚠️ 这个区分必须由界面如实传，不能在这里猜 —— 它决定"用户说不"与"没问到"两条语义。
     */
    fun onUserAnswer(requestId: String, allowed: Boolean, cancelled: Boolean): Boolean {
        val w = pending.remove(requestId) ?: run {
            Log.w(TAG, "confirm result for unknown/expired id=$requestId")
            return false
        }
        w.allowed = allowed
        w.cancelled = cancelled
        w.latch.countDown()
        Log.i(TAG, "phone confirm result (id=$requestId allowed=$allowed cancelled=$cancelled)")
        return true
    }

    /**
     * 清空全部等待中的确认，避免对话线程白挂满 40s。
     *
     * @param aborted true = **用户叫停/任务被抢占**（[com.rokidlab.phone.domain.AiConversationService.abortCurrentAi]、
     *   新代际抢占）—— 等待方拿到"被中止"，闸门一律拒绝执行；
     *   false（默认）= 通道消失（内存压力、连接流程重启）—— 等待方拿到"没问到"，按工具声明分流。
     *   ⚠️ 这个区分是 2026-10-13 审计修的主轴：叫停之后不能再有副作用发生。
     */
    fun abortAll(aborted: Boolean = false) {
        val n = pending.size
        pending.values.forEach {
            it.cancelled = false
            it.aborted = aborted
            it.latch.countDown()
        }
        pending.clear()
        if (n > 0) Log.w(TAG, "aborted $n pending phone confirmations (aborted=$aborted)")
    }
}
