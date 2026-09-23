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
 * 眼镜在线时**不会**弹本页（[ApprovalGate.activeChannel] 眼镜优先）——
 * 用户戴着眼镜时悬浮层确认 + TTS 是他熟悉的方式，本页只在眼镜不可用时接管。
 */
class ToolConfirmActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "ToolConfirmActivity"
        private const val EXTRA_REQUEST_ID = "request_id"
        private const val EXTRA_TOOL = "tool"
        private const val EXTRA_PROMPT = "prompt"

        /**
         * 同时只允许存在一个确认页：模型重试 / 并发工具时叠加的弹窗会让用户连点两个，
         * 且后一个会把前一个的结果回调顶掉（同 [com.rokidlab.phone.permission.PermissionRequestActivity]）。
         */
        @Volatile
        private var active: ToolConfirmActivity? = null

        /** 构造拉起本页的 Intent（[PhoneToolConfirmChannel] 是唯一调用方，保证入参格式唯一） */
        fun createIntent(context: Context, requestId: String, toolName: String, prompt: String): Intent =
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val previous = active
        if (previous != null && previous !== this) {
            Log.i(TAG, "another confirm screen is active, skip this launch")
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
        PhoneToolConfirmChannel.onUserAnswer(requestId, allowed, cancelled)
        finish()
    }

    override fun onDestroy() {
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
