package com.rokidlab.phone.permission

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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
 *  2. 悬浮窗/所有文件/精确闹钟/无障碍这种"只能去设置页"的权限 → 直接跳到设置页（标准页优先，厂商页兜底）；
 *     ⚠️ 其中**无障碍**是唯一"跳过去之后还得用户自己找那一行"的：系统没有直达本应用的公开入口，
 *     也禁止 App 自开（见 [AppPermission.ACCESSIBILITY] 的注释）。所以引导文案只能说
 *     "已为你打开设置页"，**不能**说"已为你开启"。
 *  3. 系统弹窗被拒/被 ROM 静默拒绝（`shouldShowRequestPermissionRationale` 为 false）
 *     → **直接跳到该权限对应的设置页**，而不是丢一句"请自行去设置里开"。
 */
class PermissionRequestActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "PermissionRequestActivity"
        private const val EXTRA_PERMISSIONS = "permissions"
        private const val EXTRA_REASON = "reason"
        private const val RC_PERMISSIONS = 0x9001

        private const val SETTINGS_PACKAGE = "com.android.settings"

        /**
         * 「通知使用权」**列表页**的候选组件名（按 ROM 排）。
         *
         * 为什么需要硬编码：标准动作 `ACTION_NOTIFICATION_LISTENER_SETTINGS` 在部分 ROM
         * （实测小米 SDK 36）**没有处理器**，但设置包里的这个组件**仍然存在且可用**
         * （`am start -n …` 实测能打开）。用**存在性检查 + try** 探，打不通就往下走，
         * 所以跨 ROM 只是"少一级候选"，不会出错。
         *
         * ⚠️ 这些是 AOSP `Settings` 的嵌套类全名（`$` 是内部类分隔符，不是笔误）。
         */
        private val NOTIFICATION_ACCESS_LIST_ACTIVITIES = listOf(
            "com.android.settings.Settings\$NotificationAccessSettingsActivity",
        )

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
        // 防叠加：同一时刻只留一个授权页（模型重试/多工具并发时会把弹窗叠起来，
        // 后一个会把前一个的结果回调顶掉）。
        // ⚠️ 但**必须排除"上一个已销毁"的情况**（2026-10-11 加的判定）：
        // 静态字段只能靠 onDestroy 清空，而进程被回收/Activity 异常结束时它可能残留，
        // 一旦残留，**此后每次拉起都会被静默 finish**，表现为"点开关毫无反应" ——
        // 一个持续性的、用户完全看不出原因的故障。所以只在对方**确实还活着**时才让位。
        val previous = active
        if (previous != null && previous !== this &&
            !previous.isFinishing && !previous.isDestroyed
        ) {
            Log.i(TAG, "another permission screen is active, skip this launch")
            finish()
            return
        }
        if (previous != null && previous !== this) {
            Log.w(TAG, "stale permission screen reference reused, taking over")
        }
        active = this

        requested = parsePermissions(intent)
        reasonText = intent.getStringExtra(EXTRA_REASON).orEmpty()

        val missing = AppPermission.missing(this, requested)
        Log.i(TAG, "onCreate requested=$requested missing=$missing reason=$reasonText")
        if (missing.isEmpty()) {
            Log.i(TAG, "nothing missing, finish immediately")
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
     *
     * ⚠️ **必须按权限类型分文案**（2026-10-11 修的真 bug）：
     * [AppPermission.runtimeRequestable] = false 的那几项（悬浮窗 / 所有文件 / 精确闹钟 /
     * 无障碍 / **通知使用权**）**根本不会弹系统权限框** —— 系统没有这条路径，
     * 只能跳设置页由用户手动开。旧文案却统一写着「点『立即开启』后，在系统弹窗里选『允许』即可」，
     * 于是用户点完等一个**永远不会出现的系统弹窗**，最后反馈"点了没弹窗"。
     * 这不是文案瑕疵：它让用户以为功能坏了，而实际是引导文案在描述一个不存在的行为。
     */
    private fun showExplainDialog(missing: List<AppPermission>) {
        val labels = AppPermission.labels(this, missing)
        // 只要**有任意一项**能走运行时申请，就还可能出现系统框 → 用原有文案；
        // 全是"设置页开关"型才换文案（混合场景下系统框确实会弹，不能反过来误导）
        val anyRuntimeRequestable = missing.any { it.runtimeRequestable }
        val message = if (anyRuntimeRequestable) {
            // 文案里的两个占位符是「用途」+「缺哪些权限」，用途由调用方（工具/启动期）按场景传入
            getString(R.string.permission_dialog_message, reasonText, labels)
        } else {
            getString(R.string.permission_dialog_message_settings, reasonText, labels)
        }
        val positiveLabel = if (anyRuntimeRequestable) {
            R.string.permission_dialog_allow
        } else {
            // 不说"立即开启"：系统页里还得用户自己拨开关，说"开启"就是把功劳揽在前面了
            R.string.permission_dialog_go_settings
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.permission_dialog_title))
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton(getString(positiveLabel)) { _, _ -> requestNow(missing) }
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
        openSettingsFor(stillMissing.firstOrNull(), afterDenial = true)
    }

    // ═══════════════════════════ 设置页 ═══════════════════════════

    /**
     * 跳到该权限对应的系统设置页。
     * 悬浮窗走 [ManufacturerUtils.openOverlaySettings]（标准页优先 + 厂商页兜底 + 应用详情页收尾），
     * 其余权限走 [ManufacturerUtils.openAppPermissionSettings] —— 两条链都是"AOSP 契约优先、
     * 厂商私有页只是备选"，所以任何品牌都能落到一个可用页面，差别只是用户少点几下。
     *
     * ⚠️ [afterDenial] 决定给用户看哪句提示（2026-10-11 修的真 bug）：
     *  - `true`（用户刚在系统框里拒绝了权限、我们升级去设置页）→ "权限未开启…" 成立；
     *  - `false`（[AppPermission.runtimeRequestable] = false 的权限，**压根没有系统框**，
     *    跳设置页就是**正常流程**）→ 必须用中性文案。
     * 之前两种情况共用"权限未开启，正在打开系统设置页"，于是**正常流程也弹出一句听着像报错的话**，
     * 用户点完「去开启」看到"权限未开启"，直接判定功能坏了（实测反馈）。
     */
    private fun openSettingsFor(permission: AppPermission?, afterDenial: Boolean = false) {
        jumpedToSettings = true
        val tip = if (afterDenial) {
            R.string.permission_dialog_denied_guide
        } else {
            R.string.permission_opening_settings
        }
        Toast.makeText(this, getString(tip), Toast.LENGTH_LONG).show()
        val handled = when (permission) {
            null -> false
            AppPermission.OVERLAY -> ManufacturerUtils.openOverlaySettings(this)
            AppPermission.ALL_FILES -> ManufacturerUtils.openAllFilesSettings(this)
            AppPermission.EXACT_ALARM -> openExactAlarmSettings()
            // 无障碍是"系统设置里手动开"的开关，没有系统授权框可弹 —— 只能把人送到那一页
            AppPermission.ACCESSIBILITY -> ManufacturerUtils.openAccessibilitySettings(this)
            // 通知使用权同属"设置页开关"，但系统给了**直达本应用**的公开入口，
            // 不用退到"应用列表 → 找到 RokidLab → 通知使用权"那三步
            AppPermission.NOTIFICATION_LISTEN -> openNotificationListenerSettings()
            else -> ManufacturerUtils.openAppPermissionSettings(this, permission.manifestName)
        }
        if (!handled) {
            // 候选链连"应用详情页"都没拉起来（ROM 极简/无 Settings 组件）：
            // 不能再假装成功，如实提示用户手动找路径。
            Toast.makeText(this, getString(R.string.permission_manual_path_hint), Toast.LENGTH_LONG).show()
        }
    }

    // ═══════════════════════════ 生命周期 ═══════════════════════════

    /**
     * 精确闹钟授权框（Android 12+）：`ACTION_REQUEST_SCHEDULE_EXACT_ALARM` 直接弹
     * 系统的「允许发送提醒？」对话框（不是设置页），带本包名 data 才会弹框而非跳列表。
     * ROM 裁剪掉该 Activity 时返回 false，由调用方兜底到应用详情页。
     */
    private fun openExactAlarmSettings(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return runCatching {
            startActivity(
                Intent(
                    android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    android.net.Uri.parse("package:$packageName"),
                ),
            )
            true
        }.getOrElse {
            Log.w(TAG, "exact alarm request screen unavailable: ${it.message}")
            ManufacturerUtils.openAppPermissionSettings(this, AppPermission.EXACT_ALARM.manifestName)
        }
    }

    /**
     * 通知使用权设置页。**三级候选链**（ROM 差异极大，实测结论见下）。
     *
     * ## ⚠️ 走过的弯路（2026-10-11 真机实测，别重走）
     * 起初用 **① `ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS` + `EXTRA_..._COMPONENT_NAME`**
     * （AOSP 文档写法）想直达本应用那一行 —— 在小米 SDK 36 上**必崩**，抓到的堆栈：
     * ```
     * RuntimeException: Unable to start activity …Settings$NotificationAccessDetailsActivity:
     *   NullPointerException: ComponentName.getPackageName() on a null object reference
     *     at …notificationaccess.NotificationAccessDetails.onAttach
     * … Force finishing activity …NotificationAccessDetailsActivity
     * ```
     * 即 **ROM 侧拿不到那个 extra 就 NPE**，页面闪一下就退 —— 用户看到的是"点了没反应"。
     * 而 `cmd package query-activities` 又证明 **② `ACTION_NOTIFICATION_LISTENER_SETTINGS`
     * 在这台机"没有处理器"**（标准动作未导出）。
     * ⇒ 两个"标准"入口一个崩、一个不存在，**都不能用**。
     *
     * ## 现在的候选链（按"真实可用"排序）
     * ① **标准列表动作** `ACTION_NOTIFICATION_LISTENER_SETTINGS` —— AOSP/多数 ROM 的正路，
     *    先 `queryIntentActivities` **确认有处理器**再跳，没有就安静往下走（不靠 catch 试错）。
     * ② **显式拉起设置包里的列表页组件** —— 给"动作没导出但组件还在"的 ROM（小米就是这类，
     *    实测 `am start -n com.android.settings/.Settings$NotificationAccessSettingsActivity`
     *    可以直接打开且是 topActivity）。⚠️ 只在**存在性检查通过**时才跳，跨 ROM 打不通只是
     *    落到下一级，不会报错。
     * ③ **应用详情页** `ACTION_APPLICATION_DETAILS_SETTINGS` —— 所有 ROM 都有的最终兜底。
     *
     * ⚠️ **刻意不再使用 DETAIL 页**：它在本机是"崩"而不是"打不开"，而崩溃发生在**系统设置进程**里，
     * 我们既 catch 不到也无法回退。宁可少一步便捷，也不要让用户的设置页闪退。
     *
     * ⚠️ 无论走到哪一级，用户都必须**自己拨那个开关**（系统没提供免授权路径），
     * 所以调用方文案只能是"已为你打开设置页"，**不能**说"已为你开启"。
     */
    private fun openNotificationListenerSettings(): Boolean {
        // ① 标准列表动作：先确认有处理器（没有就静默跳过，不用异常做流程控制）
        val standard = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        if (packageManager.queryIntentActivities(standard, 0).isNotEmpty()) {
            if (runCatching { startActivity(standard); true }.getOrDefault(false)) {
                Log.i(TAG, "opened notification access via standard action")
                return true
            }
        } else {
            Log.i(TAG, "standard NOTIFICATION_LISTENER_SETTINGS has no handler, trying ROM component")
        }

        // ② ROM 私有列表页组件（存在性 + 导出性都用 try 探，打不通继续往下）
        for (cls in NOTIFICATION_ACCESS_LIST_ACTIVITIES) {
            val cn = android.content.ComponentName(SETTINGS_PACKAGE, cls)
            val ok = runCatching {
                packageManager.getActivityInfo(cn, 0)
                startActivity(Intent().setComponent(cn))
                true
            }.getOrDefault(false)
            Log.i(TAG, "rom list activity $cls -> $ok")
            if (ok) return true
        }

        // ③ 最终兜底：应用详情页（所有 ROM 都有）—— 宁可让用户多点两下，也不要"毫无反应"
        return runCatching {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = android.net.Uri.parse("package:$packageName")
                },
            )
            true
        }.getOrElse {
            Log.w(TAG, "app details screen unavailable either: ${it.message}")
            false
        }
    }

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
