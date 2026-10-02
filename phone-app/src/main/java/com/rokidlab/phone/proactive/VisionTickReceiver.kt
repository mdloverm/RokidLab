package com.rokidlab.phone.proactive

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 「走走拍拍」闹钟到点入口（Manifest 注册，见 AndroidManifest.xml）。
 *
 * 为什么必须走 Manifest 而不是动态 receiver：RTC_WAKEUP 闹钟到点时如果进程已死，
 * 系统只会把广播投递给 Manifest 注册的接收器并借此拉起进程；动态 receiver 随进程
 * 一起消失，广播落空 → 该轮拍照静默丢失（开关还显示「开」但链条已断）。
 * 与 [com.rokidlab.phone.adb.TimerAlarmReceiver] 同一生命周期保证。
 *
 * 冷启动时序：系统先跑 Application.onCreate（LabApplication 会 [AmbientVisionController.start]
 * 重排闹钟 + 恢复会话镜像），再进本 receiver 的 [onTick]，链路自续。
 */
class VisionTickReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AmbientVisionController.ACTION_VISION_TICK) return
        Log.i(TAG, "vision tick alarm delivered")
        AmbientVisionController.get(context).onTick()
    }

    companion object {
        private const val TAG = "VisionTickReceiver"
    }
}
