package com.rokidlab.phone.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.rokidlab.phone.app.LabApplication
import com.rokidlab.phone.glasses.GlassAudioSpike

/**
 * M0 音频流 spike 的调试广播入口（仅 debug 构建存在，见 src/debug/AndroidManifest.xml）。
 *
 * ```
 * adb shell am broadcast -a com.rokidlab.phone.debug.AUDIO_SPIKE --es action start
 * adb shell am broadcast -a com.rokidlab.phone.debug.AUDIO_SPIKE --es action stop
 * ```
 */
class AudioSpikeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.getStringExtra(EXTRA_ACTION) ?: run {
            Log.w(TAG, "missing '$EXTRA_ACTION' extra (start|stop)")
            return
        }
        val app = context.applicationContext as? LabApplication ?: run {
            Log.w(TAG, "application is not LabApplication")
            return
        }
        val session = runCatching { app.cxrL }.getOrNull() ?: run {
            Log.w(TAG, "cxrL session not initialized yet")
            return
        }
        when (action) {
            "start" -> GlassAudioSpike.start(session)
            "stop" -> GlassAudioSpike.stop(session)
            else -> Log.w(TAG, "unknown action: $action")
        }
    }

    private companion object {
        const val TAG = "GlassAudioSpike"
        const val EXTRA_ACTION = "action"
    }
}
