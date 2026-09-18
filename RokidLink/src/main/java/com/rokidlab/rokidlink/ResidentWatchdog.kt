package com.rokidlab.rokidlink

import android.app.ActivityManager
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 常驻服务看护（眼镜端）：用 **系统级 Alarm** 把「进程没了 → 服务回来」这条链路接上。
 *
 * ## 为什么不能再用 Handler.postDelayed
 *
 * 眼镜端原有的两处自愈（`KeepAliveManager` 与 `BtTunnelService` 各自的 retryRestartSelf）
 * 都建立在 `mainHandler.postDelayed` 上 —— 而 Handler 的延时任务**活在被杀的那个进程里**：
 * 进程一死，队列随之蒸发，那个「5 秒后检查服务是否回来」的回调永远不会执行。
 * 它只在「服务被 stop 但进程还活着」这种极窄场景下有效（两处已在本类落地后删除）。
 *
 * [CxrBridgeCoordinator.selfHealRestart] 的注释早已点明这一条（「Handler.postDelayed 在进程自杀后
 * 不存活，必须用系统级 Alarm（RTC_WAKEUP）确保广播可投递」），但那个正确范式当时只用在
 * 「路由 stale 自杀重启」这一条路径上。本类把它提炼出来，供全体常驻服务共用。
 *
 * ## 本类提供两条链
 *
 * 1. **[armHeartbeat] 周期心跳**：每 [HEARTBEAT_INTERVAL_MS] 响一次，收到即
 *    [ensureResidentServices]（缺谁补谁），并**重挂下一次**。因为重挂动作发生在
 *    **接收器**里（即新拉起的进程内），所以这条链能在「进程被 LMK 回收」「app idle 停服务」
 *    「SwipeUpClean」之后自我延续。
 *
 * 2. **[scheduleRetry] 自愈重试**：服务 onDestroy 时排一次；每次触发若服务仍未回来就
 *    用 attempt+1 再排一次，最多 [MAX_RETRY_ATTEMPTS] 次。同样不依赖进程存活。
 *
 * ## ⚠️ 能力边界：force-stop 救不了（必须靠手机端外部拉起）
 *
 * Android 的 `force-stop` 语义就是「把这个包彻底停掉」：置 `stopped=true`、
 * **清除该包所有已注册的 alarm**，且此后不再向其投递任何广播。
 * 眼镜端被 AssistServer 场景抢占强杀走的正是这条路（真机日志
 * `Force stopping com.rokidlab.rokidlink ... from pid 2013`，由 `phone_call` 场景置位触发）。
 *
 * 因此在 force-stop 之后：
 *  - 本类的两条链**都会失效**（alarm 被系统清空，且无广播可投递）；
 *  - [BootReceiver]、START_STICKY 也一律失效（stopped=true 的包不接收系统广播、不被粘性重启）。
 *
 * 唯一合法突破点是**外部拉起**：`stopped=true` 的包允许被 ADB shell 的
 * `am start-foreground-service` 启动。故 force-stop 场景的恢复由手机端承担 ——
 * 见 phone-app `ai/ToolRegistry.ensureGlassesLinkRunning` 与
 * `CxrLHiRokidSession` 的常驻服务存活探针。
 *
 * 本类与手机端的分工因此是：**眼镜端负责「自己还能醒」的场景，手机端负责「自己醒不来」的场景。**
 */
internal object ResidentWatchdog {

    private const val TAG = "ResidentWatchdog"

    /** 周期心跳 action（仅本应用内部广播，接收器保持 exported=false） */
    internal const val ACTION_HEARTBEAT = "com.rokidlab.rokidlink.WATCHDOG_HEARTBEAT"

    /** 自愈重试次数的 Intent extra 键（由接收器读回并以 +1 再排） */
    internal const val EXTRA_RETRY_ATTEMPT = "watchdog_retry_attempt"

    /** 心跳与重试各用一个 requestCode，互不覆盖（与 CxrBridgeCoordinator 的 0/1 亦不冲突） */
    private const val HEARTBEAT_REQUEST_CODE = 0x7A01
    private const val RETRY_REQUEST_CODE = 0x7A02

    /**
     * 心跳间隔。
     *
     * 取 5 分钟：这是「服务掉线」与「无谓唤醒」之间的折中 —— 常驻服务是本应用的全部价值
     * （CXR 订阅、按键、蓝牙隧道都在它里面），掉线后 5 分钟内必被修复是可接受的；
     * 而更短会显著增加待机唤醒次数（眼镜是穿戴设备，续航敏感）。
     *
     * ⚠️ [AlarmManager.setAndAllowWhileIdle] 在 Doze 下被系统限制为**每应用每 15 分钟最多一次**，
     * 因此设备深度休眠时实际间隔可能拉长到 15 分钟。这是系统行为，不需要（也无法）规避：
     * 真正需要「立刻回来」的路径由手机端外部拉起负责。
     */
    private const val HEARTBEAT_INTERVAL_MS = 5 * 60_000L

    /** 自愈重试间隔（服务 onDestroy 后）。后台 FGS 启动可能被 ROM 拒绝，故需多次重试等待放行。 */
    private const val RETRY_DELAY_MS = 5_000L

    /** 自愈重试上限：避免「启动即崩」时形成无限重启循环 */
    private const val MAX_RETRY_ATTEMPTS = 10

    /**
     * 挂上下一次心跳（幂等：同 requestCode + FLAG_UPDATE_CURRENT，重复调用只更新时间）。
     *
     * 用 [AlarmManager.setAndAllowWhileIdle] 而非 `setRepeating`：
     *  - `setRepeating` 在 API 19+ 被系统降级为非精确，Doze 下更不可靠；
     *  - 「一次性 + 接收器重挂」把调度权握在自己手里 —— 任一环出问题都能从日志看出断在哪，
     *    而 repeating 一旦被系统清掉就是静默失效。
     *
     * `RTC_WAKEUP`：唤醒设备投递。本服务本就长期持有 PARTIAL_WAKE_LOCK，
     * 用非唤醒型 alarm 反而会出现「锁已随进程消失、alarm 却在睡眠中不触发」的组合。
     */
    fun armHeartbeat(context: Context) {
        try {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = PendingIntent.getBroadcast(
                context,
                HEARTBEAT_REQUEST_CODE,
                Intent(context, SelfRestartReceiver::class.java).setAction(ACTION_HEARTBEAT),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            am.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                System.currentTimeMillis() + HEARTBEAT_INTERVAL_MS,
                pi,
            )
            Log.i(TAG, "heartbeat armed in ${HEARTBEAT_INTERVAL_MS}ms")
        } catch (e: Exception) {
            // 不做 runCatching{} 静默吞：arm 失败意味着整条自主恢复链断开，必须可见
            Log.e(TAG, "armHeartbeat failed", e)
        }
    }

    /** 取消心跳（当前无调用方：KeyButtonService 没有「主动停止」路径，
     *  保留它是为了将来出现正常停止入口时能把链停干净，避免无谓唤醒）。 */
    fun cancelHeartbeat(context: Context) {
        try {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = PendingIntent.getBroadcast(
                context,
                HEARTBEAT_REQUEST_CODE,
                Intent(context, SelfRestartReceiver::class.java).setAction(ACTION_HEARTBEAT),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
            )
            if (pi != null) {
                am.cancel(pi)
                pi.cancel()
                Log.i(TAG, "heartbeat cancelled")
            }
        } catch (e: Exception) {
            Log.w(TAG, "cancelHeartbeat failed: ${e.message}")
        }
    }

    /**
     * 排一次自愈重试（服务 onDestroy 时调用）。
     *
     * 与 [armHeartbeat] 的区别：这是**短周期、有上限**的补拉，用于服务刚被销毁、
     * 系统可能因「后台 FGS 启动受限」而拒绝立即重启的窗口期。
     */
    fun scheduleRetry(context: Context, attempt: Int) {
        if (attempt >= MAX_RETRY_ATTEMPTS) {
            Log.e(TAG, "give up restarting after $attempt attempts")
            return
        }
        try {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = PendingIntent.getBroadcast(
                context,
                RETRY_REQUEST_CODE,
                Intent(context, SelfRestartReceiver::class.java)
                    .setAction(KeyButtonService.ACTION_SELF_HEAL_BOOTSTRAP)
                    .putExtra(EXTRA_RETRY_ATTEMPT, attempt + 1),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + RETRY_DELAY_MS, pi)
            Log.w(TAG, "restart retry #${attempt + 1} scheduled in ${RETRY_DELAY_MS}ms")
        } catch (e: Exception) {
            Log.e(TAG, "scheduleRetry failed", e)
        }
    }

    /**
     * 补齐两个常驻服务（缺谁补谁）。
     *
     * 只补缺失的那个，而不是无条件都发一次 start：心跳每 5 分钟一次，
     * 无条件 start 会让 [BtTunnelService] 反复走 onCreate 之外的重入路径
     * （它幂等，但会刷日志并打断隧道重建判断）。
     *
     * @return true = 两个服务当前都在运行
     */
    fun ensureResidentServices(context: Context): Boolean {
        val keyRunning = isServiceRunning(context, KeyButtonService::class.java)
        val tunnelRunning = isServiceRunning(context, BtTunnelService::class.java)
        if (!keyRunning) {
            Log.w(TAG, "KeyButtonService missing -> starting")
            startResidentService(context, KeyButtonService::class.java) { KeyButtonService.start(context) }
        }
        if (!tunnelRunning) {
            Log.w(TAG, "BtTunnelService missing -> starting")
            startResidentService(context, BtTunnelService::class.java) { BtTunnelService.start(context) }
        }
        if (keyRunning && tunnelRunning) {
            Log.d(TAG, "resident services healthy")
        }
        return keyRunning && tunnelRunning
    }

    /**
     * 启动一个常驻服务，带「前台服务被拒 → 降级普通 startService」回退。
     *
     * 为什么必须有回退：本方法的主要调用方是 **Alarm 触发的广播接收器**。Android 12+ 对
     * 「从后台启动前台服务」有硬限制（`ForegroundServiceStartNotAllowedException`），
     * 而 `startService` 不受同一条限制 —— 服务以普通后台服务形态先活下来，
     * 其 `onStartCommand` 里的 `startForegroundSafe` 会在系统放行后重试转前台。
     * 眼镜端 `BtTunnelService` 原本就靠这招绕过限制，这里把它收口成通用逻辑。
     *
     * 两条都失败才算真失败：记 error 日志，让「服务起不来」在 logcat 里一眼可见。
     */
    private fun startResidentService(
        context: Context,
        clazz: Class<*>,
        foregroundStart: () -> Unit,
    ) {
        val fgErr = runCatching { foregroundStart() }.exceptionOrNull()
        if (fgErr == null) return
        Log.w(TAG, "startForegroundService(${clazz.simpleName}) rejected: ${fgErr.message}, fallback to startService")
        runCatching { context.startService(Intent(context, clazz)) }
            .onFailure { Log.e(TAG, "startService(${clazz.simpleName}) also failed", it) }
    }

    /**
     * 本应用某服务是否在运行。
     *
     * `getRunningServices` 已被标记弃用，但在 API 26+ 它**只返回调用方自己的服务** ——
     * 正是这里需要的语义（不关心别人的服务），且不需要 `QUERY_ALL_PACKAGES` / UsageStats 权限。
     * 眼镜端 [KeepAliveManager] / [BtTunnelService] / [MainActivity] 已各有同名实现，
     * 本类统一收口后新代码只应调用这里。
     */
    fun isServiceRunning(context: Context, clazz: Class<*>): Boolean = try {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        am.getRunningServices(100).any {
            it.service.packageName == context.packageName && it.service.className == clazz.name
        }
    } catch (e: Exception) {
        Log.w(TAG, "isServiceRunning(${clazz.simpleName}) failed", e)
        false
    }
}
