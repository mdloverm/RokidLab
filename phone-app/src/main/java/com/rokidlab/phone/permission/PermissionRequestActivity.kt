package com.rokidlab.phone.permission

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.rokidlab.phone.R
import com.rokidlab.phone.util.LogCollector
import com.rokidlab.phone.util.ManufacturerUtils

/**
 * 统一的系统授权页（透明、无自有界面，只有一个说明弹窗 + 系统授权框）。
 *
 * 为什么是一个 Activity 而不是"在工具里直接 requestPermissions"：
 * 工具在**后台线程**执行、且没有 Activity 上下文，而 `requestPermissions` 只在有前台
 * Activity 时才会真正弹窗。把它做成一个独立 Activity 后，"从后台拉起授权"就退化成
 * 一个 `startActivity` —— 于是能否成功只取决于 BAL 豁免（悬浮窗），判定逻辑收口在
 * [PermissionBridge.canLaunchUi]，全品牌用同一套。
 *
 * 交互取值：**能自动做的不让用户手动找**
 *  1. 运行时权限 → 直接调 AOSP `requestPermissions`（全 ROM 都实现，不挑品牌）；
 *  2. 悬浮窗这种"只能去设置页"的权限 → 直接跳到设置页（标准页优先，厂商页兜底）；
 *  3. 系统弹窗被拒/被 ROM 静默拒绝（`shouldShowRequestPermissionRationale` 为 false）
 *     → **直接跳到该权限对应的设置页**，而不是丢一句"请自行去设置里开"。
 */
class PermissionRequestActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "PermissionRequestActivity"
        private const val EXTRA_PERMISSIONS = "permissions"
        private const val EXTRA_REASON = "reason"
        private const val RC_PERMISSIONS = 0x9001

        /**
         * 同时只允许存在一个授权页：模型重试 / 多个工具并发时，
         * 叠加的授权页会让用户连点多个弹窗，且后一个弹窗会把前一个的结果回调顶掉。
         */
        @Volatile
        private var active: PermissionRequestActivity? = null

        /** 构造拉起本页的 Intent（[PermissionBridge] 与启动期自检都用它，保证入参格式唯一） */
        fun createIntent(context: Context, permissions: List<AppPermission>, reason: String): Intent =
            Intent(context, PermissionRequestActivity::class.java)
                .putExtra(EXTRA_PERMISSIONS, permissions.map { it.name }.toTypedArray())
                .putExtra(EXTRA_REASON, reason)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP,
                )
    }

    private var requested: List<AppPermission> = emptyList()
    private var reasonText: String = ""

    /** 已经跳去设置页：回到本页即说明用户处理完了（或放弃了），直接结束，不再弹一遍 */
    private var jumpedToSettings = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val previous = active
        if (previous != null && previous !== this) {
            Log.i(TAG, "another permission screen is active, skip this launch")
            finish()
            return
        }
        active = this

        requested = parsePermissions(intent)
        reasonText = intent.getStringExtra(EXTRA_REASON).orEmpty()

        val missing = AppPermission.missing(this, requested)
        if (missing.isEmpty()) {
            finish()
            return
        }
        showExplainDialog(missing)
    }

    // ═══════════════════════════ 说明弹窗 ═══════════════════════════

    /**
     * 先给一个"为什么"再弹系统框。这层不是为了好看：
     * 部分 ROM（尤其国产）在无任何前置说明时直接弹权限框，用户不理解用途、习惯性点拒绝，
     * 之后该权限就进入"永久拒绝"，只能去设置页手动开 —— 一次拒绝的代价远大于多一次说明。
     */
    private fun showExplainDialog(missing: List<AppPermission>) {
        val labels = AppPermission.labels(this, missing)
        // 文案里的两个占位符是「用途」+「缺哪些权限」，用途由调用方（工具/启动期）按场景传入
        val message = getString(R.string.permission_dialog_message, reasonText, labels)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.permission_dialog_title))
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton(getString(R.string.permission_dialog_allow)) { _, _ -> requestNow(missing) }
            .setNeutralButton(getString(R.string.permission_dialog_open_settings)) { _, _ ->
                openSettingsFor(missing.firstOrNull())
            }
            .setNegativeButton(getString(R.string.permission_dialog_later)) { _, _ -> finish() }
            .show()
    }

    // ═══════════════════════════ 申请 ═══════════════════════════

    /** 立即开启：运行时权限走系统框；只有悬浮窗这种"申请不了"的直接跳设置页 */
    private fun requestNow(missing: List<AppPermission>) {
        val runtimeNames = AppPermission.runtimeNames(missing)
        if (runtimeNames.isEmpty()) {
            openSettingsFor(missing.firstOrNull())
            return
        }
        runCatching {
            ActivityCompat.requestPermissions(this, runtimeNames, RC_PERMISSIONS)
        }.onFailure {
            // 极少数 ROM 的 requestPermissions 会直接抛异常（自定义权限管理器）→ 退到设置页
            LogCollector.w(TAG, "requestPermissions 调用失败，改为跳转设置页: ${it.message}", it)
            openSettingsFor(missing.firstOrNull())
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != RC_PERMISSIONS) return

        val stillMissing = AppPermission.missing(this, requested)
        if (stillMissing.isEmpty()) {
            Toast.makeText(this, getString(R.string.permission_dialog_granted), Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // 还能再弹（用户只是这一次点了拒绝）→ 到此为止，等下次真正用到时再问，
        // 避免在无更高层说明的情况下反复骚扰。
        val canAskAgain = stillMissing.any {
            it.runtimeRequestable &&
                ActivityCompat.shouldShowRequestPermissionRationale(this, it.manifestName)
        }
        if (canAskAgain) {
            Log.i(TAG, "permission denied but re-askable: $stillMissing")
            finish()
            return
        }

        // 已被永久拒绝（含 ROM 静默拒绝导致"弹窗从未出现"的情况）→ 直接带去设置页
        Log.i(TAG, "permission permanently denied, jumping to settings: $stillMissing")
        openSettingsFor(stillMissing.firstOrNull())
    }

    // ═══════════════════════════ 设置页 ═══════════════════════════

    /**
     * 跳到该权限对应的系统设置页。
     * 悬浮窗走 [ManufacturerUtils.openOverlaySettings]（标准页优先 + 厂商页兜底 + 应用详情页收尾），
     * 其余权限走 [ManufacturerUtils.openAppPermissionSettings] —— 两条链都是"AOSP 契约优先、
     * 厂商私有页只是备选"，所以任何品牌都能落到一个可用页面，差别只是用户少点几下。
     */
    private fun openSettingsFor(permission: AppPermission?) {
        jumpedToSettings = true
        Toast.makeText(this, getString(R.string.permission_dialog_denied_guide), Toast.LENGTH_LONG).show()
        val handled = when (permission) {
            null -> false
            AppPermission.OVERLAY -> ManufacturerUtils.openOverlaySettings(this)
            else -> ManufacturerUtils.openAppPermissionSettings(this, permission.manifestName)
        }
        if (!handled) {
            // 候选链连"应用详情页"都没拉起来（ROM 极简/无 Settings 组件）：
            // 不能再假装成功，如实提示用户手动找路径。
            Toast.makeText(this, getString(R.string.permission_manual_path_hint), Toast.LENGTH_LONG).show()
        }
    }

    // ═══════════════════════════ 生命周期 ═══════════════════════════

    override fun onStop() {
        super.onStop()
        // 跳去设置页后本页仍在返回栈里；用户若从桌面绕回来会看到"又弹一次说明"，
        // 且此时授权其实已经处理完了。直接结束，下一次真缺权限会重新拉起。
        if (jumpedToSettings) finish()
    }

    override fun onDestroy() {
        if (active === this) active = null
        super.onDestroy()
    }

    // ═══════════════════════════ 入参解析 ═══════════════════════════

    private fun parsePermissions(intent: Intent): List<AppPermission> {
        val names = intent.getStringArrayExtra(EXTRA_PERMISSIONS).orEmpty()
        // 用 valueOf 而不是尝试匹配 manifestName：跨越 Intent 传递的是**枚举名**，
        // 与清单权限名解耦（同一权限名将来可能对应多个语义，如日历读写共用 READ/WRITE）。
        return names.mapNotNull { raw ->
            runCatching { AppPermission.valueOf(raw) }.getOrElse {
                Log.w(TAG, "unknown permission enum from intent: $raw")
                null
            }
        }
    }
}
