package com.rokidlab.phone.music

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.KeyEvent
import com.rokidlab.phone.platform.AvrcpLyricBridge

/**
 * 媒体按键接收器（L0 之外的应用侧声明，配合 [AvrcpLyricBridge] 的 MediaSession）。
 *
 * 为什么需要它：系统的 AVRCP Target 通过
 * `MediaSessionManager.getActiveSessions(mediaButtonReceiver组件)` 挑选要上报的播放器，
 * **会话没有关联 `mediaButtonReceiver` 时会被漏掉** —— 实测表现为
 * `AvrcpTargetJni.getCurrentPlayStatus` 恒返回 position=0/duration=0/state=0，
 * 眼镜端拿到 `title: Not Provided`（系统歌词页空白）。
 * 因此这里声明一个 MEDIA_BUTTON 接收器并在会话上 `setMediaButtonReceiver(...)` 关联它。
 */
class LabMediaButtonReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MEDIA_BUTTON) return
        @Suppress("DEPRECATION")
        val event = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
        if (event == null) {
            Log.w(TAG, "MEDIA_BUTTON without KeyEvent extra")
            return
        }
        Log.i(TAG, "MEDIA_BUTTON key=${event.keyCode} action=${event.action} repeat=${event.repeatCount}")
        AvrcpLyricBridge.dispatchMediaButton(event)
    }

    private companion object {
        const val TAG = "LabMediaButton"
    }
}
