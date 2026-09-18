package com.rokidlab.rokidlink

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 开机 / 覆盖安装自启接收器（眼镜端）。
 *
 * ## 为什么需要它
 *
 * RokidLink 是眼镜侧的「常驻服务宿主」：所有 CXR 自定义指令（图片下发 / 拉起页面 / 工具确认 /
 * AIUI 宿主控制）只有 [KeyButtonService] 订阅着才收得到，而它原本**没有任何自启动入口** ——
 * 直接后果有两个：
 *  - 眼镜重启后必须有人手动打开一次 RokidLink，否则整套能力静默失效；
 *  - `adb install -r` 之后包状态是 `stopped=true`，同样不会自启。
 *
 * ## 与 [SelfRestartReceiver] 的分工（重要）
 *
 * 二者都是「把常驻服务拉起来」，但触发源与暴露面完全不同，故拆成两个 receiver：
 *  - 本类只接**系统广播**，必须 `exported=true`（系统才能投递）；
 *  - [SelfRestartReceiver] 只接**本应用内部 action**（其中一个 action 会主动杀进程），
 *    保持 `exported=false` —— 否则任何第三方应用发一条广播就能让本进程自杀。
 *
 * ## ⚠️ 能力边界（2026-09-18 真机实测，别高估本类）
 *
 * 本类只覆盖 **`BOOT_COMPLETED`（设备重启）**。另一条 `MY_PACKAGE_REPLACED` 实测**打不进来**：
 *
 * ```
 * 19:32:10.897 I/ActivityManager: Force stopping com.rokidlab.rokidlink appid=10115 user=-1: installPackageLI
 * 19:32:11.011 I/ActivityManager: Force stopping com.rokidlab.rokidlink appid=10115 user=0: pkg removed
 * ```
 *
 * 即 **`adb install -r` 自己会先 `force-stop` 目标包**（`installPackageLI`），装完包处于
 * `stopped=true` —— 而 stopped 的包收不到广播，接收器一行日志都没有（实测确认）。
 * 保留该 action 是因为它在「应用正在运行时被更新」等其他路径上仍可能投递，且无副作用；
 * 但**不要指望它**：覆盖安装后仍需要外部拉起。
 *
 * 同理，`force-stop`（AssistServer 场景抢占走的就是这条路，日志特征
 * `Force stopping ... from pid 2013`）之后：置 `stopped=true`、**清除该包全部 alarm 与 job**，
 * 此后 START_STICKY、BOOT_COMPLETED、Alarm 心跳**全部失效**。
 * 唯一合法突破点是**外部拉起** —— `stopped=true` 的包允许被 ADB shell 的
 * `am start-foreground-service` 启动（真机验证：`stopped=true` 状态下该命令仍能让进程起来）。
 * 因此强杀场景的恢复由手机端承担，见 phone-app `ai/ToolRegistry.ensureGlassesLinkRunning`
 * 与 `CxrLHiRokidSession.startGlassesServiceProbe`。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            // ⚠️ 有意不处理 LOCKED_BOOT_COMPLETED：两个常驻服务都要读 SharedPreferences
            //    （凭据加密存储，解锁前不可用），在解锁前拉起只会失败。见 manifest 注释。
            //
            // MY_PACKAGE_REPLACED 实测打不进来（覆盖安装会先把包 force-stop 成 stopped=true），
            // 保留只为覆盖「运行中被更新」等其他路径 —— 详见类注释，别把它当成覆盖安装的兜底。
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            -> {
                Log.i(TAG, "self-start on ${intent.action}: starting resident services")
                // Receiver 的生命周期约 10s，只下发启动请求，重活全部由服务自己完成
                // （与手机端 keepalive/BootReceiver 同策略）。
                //
                // 顺序：先 BtTunnelService（前台服务要求 5s 内 startForeground，先启避免被
                // KeyButtonService 的主线程初始化拖慢而崩溃），再 KeyButtonService。
                runCatching { BtTunnelService.start(context) }
                    .onFailure { Log.e(TAG, "start BtTunnelService failed on ${intent.action}", it) }
                runCatching { KeyButtonService.start(context) }
                    .onFailure { Log.e(TAG, "start KeyButtonService failed on ${intent.action}", it) }
                // 兜底挂心跳：正常情况下 KeyButtonService.onCreate 会挂，
                // 但若本次 FGS 因后台限制被拒（服务实际没起来），这里挂上的心跳会在 5min 后重试拉起。
                // 设备刚开机时 AlarmManager 已就绪，arm 本身不会失败。
                ResidentWatchdog.armHeartbeat(context)
            }
            else -> Log.d(TAG, "ignored action: ${intent.action}")
        }
    }

    private companion object {
        private const val TAG = "BootReceiver"
    }
}
