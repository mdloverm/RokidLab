package com.rokidlab.phone.store

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 音色偏好（Compose 实时状态 + SharedPreferences 持久化）。
 *
 * - [voiceId]："" = 默认（眼镜端离线 TTS，自动播报链路零改动）；非空 = 云萌音色 id（点喇叭手机播）
 * - [rate]：云萌合成语速（1-9）
 *
 * 读取方：AiConversationService（自动播报分流）、ChatScreen（喇叭点播）、VoicePickPage。
 * 注意 [ensure] 必须在首次读取前调用（LabApplication.onCreate 已接）。
 */
object VoicePrefs {
    private const val PREFS_NAME = "voice_prefs"
    private const val KEY_VOICE = "voice_id"
    private const val KEY_RATE = "voice_rate"

    @Volatile
    private var prefs: SharedPreferences? = null

    var voiceId by mutableStateOf("")
        private set

    /** 云萌合成语速（1-9，默认 5）；默认音色（眼镜端离线 TTS）不使用 */
    var rate by mutableIntStateOf(5)
        private set

    /** 是否启用云萌音色（false = 眼镜端离线 TTS，默认行为） */
    val cloudEnabled: Boolean get() = voiceId.isNotBlank()

    /** 幂等初始化：重复调用无副作用 */
    fun ensure(context: Context) {
        if (prefs != null) return
        synchronized(this) {
            if (prefs == null) {
                val p = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                prefs = p
                voiceId = p.getString(KEY_VOICE, "") ?: ""
                rate = p.getInt(KEY_RATE, 5)
            }
        }
    }

    /** 选择音色（"" = 回到默认眼镜端语音），立即生效并持久化 */
    fun updateVoice(id: String) {
        voiceId = id
        prefs?.edit()?.putString(KEY_VOICE, id)?.apply()
    }

    /** 调整云萌合成语速（1-9） */
    fun updateRate(value: Int) {
        rate = value.coerceIn(1, 9)
        prefs?.edit()?.putInt(KEY_RATE, rate)?.apply()
    }
}
