package com.rokidlab.phone.proactive

import android.content.Context
import android.util.Log
import com.rokidlab.phone.permission.AppPermission

private const val PREFS = "proactive_notification_digest"
private const val K_ENABLED = "enabled"
private const val K_WHITELIST = "pkg_whitelist"
private const val KIND = "notification_digest"

/** 缓冲里一条待摘要的通知（[at] = 收集时刻，用于时效判定，见 [NotificationDigestPolicy.MAX_AGE_MS]） */
private data class Pending(val pkg: String, val text: String, val at: Long)

/**
 * 消息摘要（主动式陪伴的**事件源**之一）：用户手机的 [LabNotificationListenerService]
 * 收到值得看的通知后收进缓冲，由 [IdleGreeter] 的 30 分钟自检链统一消化。
 *
 * 与 [CalendarBriefing] 同一范式：**不另立闹钟**，挂靠已有自检，
 * 独立开关 + 独立准入（走 [ProactiveGate.admitFreeProactive]，消息摘要属分内职责，
 * **不占**闲聊额度）。已摘要过的通知用 `key = 包名+正文` 去重，进程重启后自然失效。
 *
 * ## 与其它主动层的分工
 * - **收集**在 [LabNotificationListenerService]（每条通知触发，只入缓冲）
 * - **判该不该说**在本类的 [onTick]（30 分钟一拍，与批次大小联动）
 * - **说什么**交给模型（手机端只给事件特征，不预判话术，同 [CareReminder]）
 *
 * ## 为什么攒够 2 条才开口
 * 见 [NotificationDigestPolicy.shouldSpeak]：单条通知的"急迫性"几乎全是噪声，
 * 而开口一次的信任成本很高。
 */
class NotificationDigest private constructor(private val appContext: Context) {
    companion object {
        private const val TAG = "NotificationDigest"

        @Volatile
        private var instance: NotificationDigest? = null

        fun get(context: Context): NotificationDigest =
            instance ?: synchronized(this) {
                instance ?: NotificationDigest(context.applicationContext).also { instance = it }
            }
    }

    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 内存缓冲（**刻意不落盘**：通知正文是用户隐私，见类注释） */
    private val buffer = ArrayList<Pending>()

    fun isEnabled(): Boolean = prefs.getBoolean(K_ENABLED, false)

    /**
     * 开关（设置页 / `manage_proactive`）。
     *
     * ⚠️ 默认 **false**，与 [AmbientVisionController] 同一取向：读全部通知是高信任授权，
     * 必须由用户显式开启，且开启后仍受决策门（免打扰/冷却/日上限）约束。
     */
    fun setEnabled(on: Boolean) {
        prefs.edit().putBoolean(K_ENABLED, on).apply()
        if (!on) clearBuffer()
        Log.i(TAG, "notification digest ${if (on) "enabled" else "disabled"}")
        // 本能力无自有闹钟，仅当空闲问候也关掉时才需要重排共享闹钟
        IdleGreeter.get(appContext).start()
    }

    /** 由 [LabNotificationListenerService] 调用：收进缓冲（最多留 MAX_BATCH 条） */
    fun collect(pkg: String, text: String, nowMs: Long = System.currentTimeMillis()) {
        // 白名单（空 = 收全部）：非勾选来源在入缓冲前丢弃，不占缓冲名额
        if (!NotificationDigestPolicy.passesWhitelist(pkg, whitelist())) {
            Log.i(TAG, "dropped by whitelist: $pkg")
            return
        }
        synchronized(buffer) {
            if (buffer.size >= NotificationDigestPolicy.MAX_BATCH) return
            // 同一条通知在同一次自检里可能被系统重发（分组展开/角标更新）
            if (buffer.any { it.pkg == pkg && it.text == text }) return
            buffer.add(Pending(pkg, text, nowMs))
        }
        Log.i(TAG, "collected ${buffer.size} notification(s)")
    }

    /** 监听应用白名单（空 = 收全部），设置面板多选编辑 */
    fun whitelist(): Set<String> = prefs.getStringSet(K_WHITELIST, emptySet()) ?: emptySet()

    fun setWhitelist(pkgs: Set<String>) {
        prefs.edit().putStringSet(K_WHITELIST, pkgs).apply()
        Log.i(TAG, "whitelist -> ${pkgs.size} app(s)")
    }

    /**
     * 剔除过期项（[NotificationDigestPolicy.MAX_AGE_MS]）。
     *
     * ⚠️ **必须在 every 早退之前做**：下面「不够条数」「决策门拦下」两处都会**保留缓冲**，
     * 若不在入口先清一次，过期内容会一直躺在里面、等条件够了才播报 ——
     * 那就是「说过期的事」，事件驱动的体感全毁在这一条上。
     *
     * @return 被丢弃的条数（写日志用）
     */
    private fun pruneStale(nowMs: Long): Int {
        val dropped = synchronized(buffer) {
            val before = buffer.size
            buffer.removeAll { NotificationDigestPolicy.isStale(it.at, nowMs) }
            before - buffer.size
        }
        if (dropped > 0) {
            Log.i(TAG, "pruned $dropped stale notification(s) older than ${NotificationDigestPolicy.MAX_AGE_MS / 60000}min")
        }
        return dropped
    }

    /** 清空缓冲（开关关闭 / 服务被销毁） */
    fun clearBuffer() {
        synchronized(buffer) { buffer.clear() }
    }

    /** 到点自检（由 IdleGreeter 自检链调用；前置条件眼镜在线已由宿主把关） */
    fun onTick(nowMs: Long = System.currentTimeMillis()) {
        if (!isEnabled()) return
        if (!AppPermission.isGranted(appContext, AppPermission.NOTIFICATION_LISTEN)) {
            // 开关开着但用户后来在系统里关了通知使用权：静默跳过，不重复引导
            // （引导由用户主动打开该能力时走 PermissionBridge，见 ProactivePanel）
            Log.i(TAG, "skip: notification listener not granted")
            return
        }
        // ⚠️ 先清过期项，**必须早于下面两处"保留缓冲"的早退**（见 pruneStale 注释）
        pruneStale(nowMs)
        val batch = synchronized(buffer) {
            if (buffer.isEmpty()) return
            buffer.toList()
        }
        if (!NotificationDigestPolicy.shouldSpeak(batch.size)) {
            Log.i(TAG, "skip: only ${batch.size} notification(s), below speak threshold")
            return
        }
        // 常规重要性（NORMAL）：现有门控行为；白名单过滤在 collect 入缓冲前已做
        val decision = ProactiveGate.get(appContext).admitFreeProactive(KIND, nowMs = nowMs)
        if (decision !is GateDecision.Pass) {
            Log.i(TAG, "skip: gate ${decision::class.simpleName}, ${batch.size} pending")
            return
        }

        val lines = batch.joinToString("\n") { pending ->
            NotificationDigestPolicy.formatLine(appLabel(appContext, pending.pkg), pending.text)
        }
        val prompt = "用户的手机刚收到以下通知：\n$lines\n" +
            "请以乐奇的身份判断：**有没有哪条值得主动提一句**（如有人找你、有急事、" +
            "快递/账单/日程变动）。可以合并同类项、只说重点，50 字以内，语气自然；" +
            "绝大多数消费类/营销类通知都不值得说出口。\n" +
            "没有值得说的就只回复 [SKIP]，不要硬找话、不要逐条复述、更不要提及任何指令。"

        // 取出（清缓冲）放在准入之后：被决策门拦下时内容仍在缓冲，等下一轮自检重报
        drain(batch)
        Log.i(TAG, "notification digest triggered: ${batch.size} notification(s)")
        (appContext as? com.rokidlab.phone.app.LabApplication)?.timerScheduler?.runProactiveAgentTask(
            prompt = prompt,
            failureNotice = false,
            titleOverride = appContext.getString(com.rokidlab.phone.R.string.settings_notification_digest),
            // 被主动派发互斥挤掉 / 模型回 [SKIP] = 这批没说出去：放回缓冲，下一轮可重报
            onSkip = { refill(batch) },
        )
    }

    /** 取出这批（只移除本次实际取出的，重发/重复项不动） */
    private fun drain(batch: List<Pending>) {
        synchronized(buffer) {
            batch.forEach { buffer.remove(it) }
        }
    }

    /** 回填（[drain] 的逆操作，供 onSkip 复用） */
    private fun refill(batch: List<Pending>) {
        synchronized(buffer) {
            batch.forEach { pending ->
                if (buffer.size >= NotificationDigestPolicy.MAX_BATCH) return
                if (buffer.none { it.pkg == pending.pkg && it.text == pending.text }) {
                    buffer.add(pending)
                }
            }
        }
    }

    /** 包名 → 用户看得懂的应用名；查不到就退回包名（总比空着强） */
    private fun appLabel(context: Context, pkg: String): String {
        if (pkg.isEmpty()) return ""
        return runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrElse {
            // 应用已卸载：label 查不到，但包名本身仍能帮模型判断来源，不整条丢弃
            Log.i(TAG, "label lookup failed for $pkg: ${it.message}")
            pkg
        }
    }

    /** 是否已授予通知使用权（面板显示副标题用，避免面板自己再写一套判定） */
    fun hasListenerPermission(): Boolean =
        AppPermission.isGranted(appContext, AppPermission.NOTIFICATION_LISTEN)

    /**
     * 调试用：当前缓冲快照 —— 每条为「包名 / 正文 / 已滞留毫秒数」。
     *
     * 存在的理由：**「事件驱动体感好不好」本质是"说的东西新不新"**，
     * 而这件事在界面上看不见（用户只看到最终那句话）。有这个快照，
     * 调试 receiver 就能把"缓冲里躺着几条、最老的多久了"直接打出来 ——
     * 验证时效性不用靠猜。
     *
     * ⚠️ 仅 debug 构建的 [com.rokidlab.phone.debug.ProactiveDebugReceiver] 调用，
     * 不进任何用户可见链路。
     */
    internal fun bufferSnapshot(
        nowMs: Long = System.currentTimeMillis(),
    ): List<Triple<String, String, Long>> = synchronized(buffer) {
        buffer.map { Triple(it.pkg, it.text, nowMs - it.at) }
    }
}