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
object PhoneToolConfirmChannel : ApprovalGate.ConfirmResolver {

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
     * 上一次确认是否被用户**显式取消**（与眼镜通道同语义：取消 ⇒ 拒绝，超时 ⇒ 按声明分流）。
     *
     * 每次 [confirm] 开头复位，避免"上一次用户点了取消"污染这一次的超时判定。
     */
    @Volatile
    private var lastCancelled = false

    override fun wasCancelled(): Boolean = lastCancelled

    private val seq = AtomicLong(0)
    private val pending = ConcurrentHashMap<String, Waiter>()

    private class Waiter {
        val latch = CountDownLatch(1)
        @Volatile var allowed = false
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
    override fun confirm(toolName: String, prompt: String): Boolean {
        val ctx = appContext ?: return false
        if (!isAvailable()) return false
        lastCancelled = false
        val id = "pc-${System.currentTimeMillis()}-${seq.incrementAndGet()}"
        val waiter = Waiter()
        pending[id] = waiter
        return try {
            ctx.startActivity(ToolConfirmActivity.createIntent(ctx, id, toolName, prompt))
            if (!waiter.latch.await(CONFIRM_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                Log.w(TAG, "phone confirm timeout (id=$id tool=$toolName)")
                false
            } else {
                waiter.allowed
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        } catch (e: Exception) {
            // BAL 拦截是**静默丢弃**（既不抛异常也不返回失败），能走到这里的只有
            // "Activity 未注册 / 系统拒绝启动"这类硬错误 —— 如实返回 false，让闸门按"没问到"处理。
            Log.w(TAG, "phone confirm launch failed (id=$id): ${e.javaClass.simpleName}: ${e.message}")
            false
        } finally {
            pending.remove(id)
        }
    }

    /**
     * 用户答复回执（由 [ToolConfirmActivity] 调用，主线程）。
     *
     * @param cancelled true = 用户**显式取消**（⇒ 拒绝）；false = 没问到 / 页面被回收
     *   （⇒ 由闸门按工具声明的 `ToolConfirmPolicy` 分流）。
     *   ⚠️ 这个区分必须由界面如实传，不能在这里猜 —— 它决定"用户说不"与"没问到"两条语义。
     */
    fun onUserAnswer(requestId: String, allowed: Boolean, cancelled: Boolean) {
        val w = pending.remove(requestId) ?: run {
            Log.w(TAG, "confirm result for unknown/expired id=$requestId")
            return
        }
        w.allowed = allowed
        if (cancelled) lastCancelled = true
        w.latch.countDown()
        Log.i(TAG, "phone confirm result (id=$requestId allowed=$allowed cancelled=$cancelled)")
    }

    /**
     * 清空全部等待中的确认（App 被切到后台且拉不起界面、连接流程重启等场景），
     * 避免对话线程白挂满 40s。与 [GlassToolConfirmChannel.abortAll] 对称。
     */
    fun abortAll() {
        val n = pending.size
        pending.values.forEach { it.latch.countDown() }
        pending.clear()
        if (n > 0) Log.w(TAG, "aborted $n pending phone confirmations")
    }
}
