package com.rokidlab.phone.ai.approval

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.rokidlab.phone.R
import com.rokidlab.phone.ai.PhoneToolConfirmChannel

/**
 * 工具确认页（手机端确认通道的**界面部分**）。
 *
 * ## 它解决什么
 * 需要用户点头的工具（发短信 / 删文件 / 装包 / 第三方 MCP 工具）原先只有**眼镜端**确认通道，
 * 而眼镜通道要求眼镜在线 —— 「本机模式」按定义就是"不连眼镜"，于是该模式下所有确认都落空，
 * 闸门只能静默放行（`send_sms` 会在没人点头的情况下真的发出去）。
 * 本页是那条缺失的"问一次"在手机上的落点。
 *
 * ## 为什么是一个透明 Activity（照抄 [com.rokidlab.phone.permission.PermissionRequestActivity]）
 * 工具在**后台线程**执行、且没有 Activity 上下文，而 `AlertDialog` 需要 Activity。
 * 做成独立 Activity 后，"从后台拉起确认框"退化成一次 `startActivity` ——
 * 能否成功只取决于 BAL 豁免，判定收口在 [com.rokidlab.phone.permission.PermissionBridge.canLaunchUi]。
 * 拉不起来时本通道 `isAvailable()` 直接为 false，闸门走"问不到用户"那条路
 * （按工具声明的 `ToolConfirmPolicy` 分流），**不会**在这里假装问过。
 *
 * ## 与眼镜通道的关系
 * [ApprovalGate.activeChannel] 的取值规则（2026-10-12 起）：**App 在前台时本页优先**
 * （用户正看着手机，弹在这里他才看得到）；不在前台时才走眼镜通道，
 * 眼镜也不可用时再由本页兜底。
 */
class ToolConfirmActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ToolConfirmActivity"
        private const val EXTRA_REQUEST_ID = "request_id"
        private const val EXTRA_TOOL = "tool"
        private const val EXTRA_PROMPT = "prompt"
        private const val EXTRA_TIMEOUT = "timeout_ms"

        /**
         * 自关兜底相对通道超时的**宽限**（2026-10-13 审计修 M2）。
         *
         * 通道自己的 `latch.await` 超时是权威判定；本页只在**略晚**于它时才自我收尾，
         * 保证"界面还开着但请求已作废"这个窗口尽量短（改造前是永久残留）。
         */
        private const val EXPIRE_GRACE_MS = 400L

        /**
         * 同时只允许存在一个确认页：模型重试 / 并发工具时叠加的弹窗会让用户连点两个，
         * 且后一个会把前一个的结果回调顶掉（同 [com.rokidlab.phone.permission.PermissionRequestActivity]）。
         */
        @Volatile
        private var active: ToolConfirmActivity? = null

        /** 构造拉起本页的 Intent（[PhoneToolConfirmChannel] 是唯一调用方，保证入参格式唯一） */
        fun createIntent(
            context: Context,
            requestId: String,
            toolName: String,
            prompt: String,
            timeoutMs: Long,
        ): Intent =
            Intent(context, ToolConfirmActivity::class.java)
                .putExtra(EXTRA_REQUEST_ID, requestId)
                .putExtra(EXTRA_TOOL, toolName)
                .putExtra(EXTRA_PROMPT, prompt)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP,
                )
    }

    private var requestId: String = ""

    /** 是否已经回过执：保证"回执恰好一次"（[onDestroy] 与按钮两条路都可能走到） */
    private var answered = false

    /** 自关兜底（超时后本页要自己消失，见 [scheduleExpiry]） */
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val previous = active
        if (previous != null && previous !== this) {
            Log.i(TAG, "another confirm screen is active, skip this launch")
            // ★ 2026-10-13 审计修 M1：跳过启动**也必须回执**。
            //   改造前这里直接 finish()，而 requestId 还没赋值（仍是空串），
            //   onDestroy 于是回报 id="" —— 没人认领，对应的等待者只能白挂满 40s，
            //   期间那个动作按"超时"语义被静默处理（PROCEED 工具甚至会被放行）。
            //   先读出本次的 id 并按"没问到"回报，等待方立刻得到确定结论。
            val skippedId = intent.getStringExtra(EXTRA_REQUEST_ID).orEmpty()
            answered = true
            PhoneToolConfirmChannel.onUserAnswer(skippedId, allowed = false, cancelled = false)
            finish()
            return
        }
        active = this

        requestId = intent.getStringExtra(EXTRA_REQUEST_ID).orEmpty()
        val toolName = intent.getStringExtra(EXTRA_TOOL).orEmpty()
        // 摘要的产地是工具自己的 ToolEntry.summarize（由闸门随 confirm 传入）。
        // 只有它为空时才退到工具名 —— 绝不在这里自己拼参数。
        val detail = intent.getStringExtra(EXTRA_PROMPT)?.takeIf { it.isNotBlank() } ?: toolName

        showDialog(detail)
        scheduleExpiry(intent.getLongExtra(EXTRA_TIMEOUT, 0L))
    }

    /**
     * 自关兜底（2026-10-13 审计修 M2）。
     *
     * 通道超时后 agent 侧已有结论（拒绝/按声明分流），而本页**原先原样留着**：
     * 用户几分钟后点「允许」只会落进 "unknown/expired id"，界面毫无反馈 ——
     * 最刺的是 PROCEED 工具超时后已按策略执行过，用户却以为是自己批准的。
     * 现在与通道超时对齐自我收尾，并明确告诉用户"这次已经过期、没执行"。
     *
     * @param timeoutMs 通道本次的等待上限；≤0 = 调用方未提供（不启用自关，保持旧行为）
     */
    private fun scheduleExpiry(timeoutMs: Long) {
        if (timeoutMs <= 0L) return
        handler.postDelayed({
            if (!answered) {
                Log.w(TAG, "confirm dialog expired (id=$requestId) -> auto close")
                answered = true
                // 如实回执"没问到"：让闸门立刻拿到确定结论（通道若已超时，这次回执只是幂等落空）
                PhoneToolConfirmChannel.onUserAnswer(requestId, allowed = false, cancelled = false)
                android.widget.Toast.makeText(
                    this,
                    getString(R.string.tool_confirm_expired),
                    android.widget.Toast.LENGTH_SHORT,
                ).show()
                finish()
            }
        }, timeoutMs + EXPIRE_GRACE_MS)
    }

    /**
     * 只问一句"要不要做"，不提供"去设置"之类的分支 ——
     * 用户此刻的选择只有两个：允许（AI 继续）或取消（AI 改口）。
     */
    private fun showDialog(detail: String) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.tool_confirm_title))
            .setMessage(getString(R.string.tool_confirm_message, detail))
            .setCancelable(false)
            .setPositiveButton(getString(R.string.tool_confirm_allow)) { _, _ ->
                answer(allowed = true, cancelled = false)
            }
            .setNegativeButton(getString(R.string.tool_confirm_deny)) { _, _ ->
                answer(allowed = false, cancelled = true)
            }
            .show()
    }

    private fun answer(allowed: Boolean, cancelled: Boolean) {
        if (answered) return
        answered = true
        val accepted = PhoneToolConfirmChannel.onUserAnswer(requestId, allowed, cancelled)
        // ★ 2026-10-13 审计修 M2：本次回执没被认领 ⇒ 这次确认早已作废（超时/被中止），
        //   必须如实告诉用户，不能让他以为"是我点的允许/取消"起了作用。
        if (!accepted) {
            android.widget.Toast.makeText(
                this,
                getString(R.string.tool_confirm_expired),
                android.widget.Toast.LENGTH_SHORT,
            ).show()
        }
        finish()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (active === this) active = null
        // 页面被系统回收 / 进程内异常退出而**没有**答复时：按"没问到"回报，而不是"用户取消"。
        // ⚠️ 这个区分是安全的支点：cancelled=true 会让 BLOCK 类工具被永久判死（用户没做任何事），
        // 而 cancelled=false 走的是"问不到"那条路 —— 用户下一次还能正常触发确认。
        if (!answered) {
            answered = true
            PhoneToolConfirmChannel.onUserAnswer(requestId, allowed = false, cancelled = false)
        }
        super.onDestroy()
    }
}
