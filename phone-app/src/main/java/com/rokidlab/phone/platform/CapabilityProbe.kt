package com.rokidlab.phone.platform

import android.content.Context
import android.os.Build
import android.provider.Settings

/**
 * 启动期一次性能力探测：把分散在各处的环境假设收敛到一处可查询的结果。
 *
 * 取代「用时直接反射 + catch 吞掉」的散弹模式；某个能力缺失时返回明确的
 * [Capability.Unavailable.reason]，UI/日志即可据此展示「为什么这个功能用不了」。
 *
 * 后续 Phase 2 会把 12 处 hook 全部改为读这里的探测结果 + 经 [SdkBridge] 执行。
 */
object CapabilityProbe {
    /** 唯一的 SDK 私有 API 执行边界（懒加载，避免无 Context 时提前构造）。 */
    val sdkBridge: SdkBridge by lazy { DefaultSdkBridge() }

    val androidSdk: Int = Build.VERSION.SDK_INT
    val rom: String = "${Build.MANUFACTURER} ${Build.MODEL}"

    /** SYSTEM_ALERT_WINDOW 是否已授予（眼镜端 RokidLink 悬浮层依赖）。 */
    var sawGranted: Boolean = false
        private set

    /** 眼镜端 run-as 是否可用（写 .aix 到私有目录的依赖）。 */
    var runAsAvailable: Boolean = false
        private set

    /** 在 Application/会话初始化时调用一次，刷新运行环境探测结果。 */
    fun refresh(context: Context) {
        sawGranted = runCatching { Settings.canDrawOverlays(context) }.getOrDefault(false)
    }
}
