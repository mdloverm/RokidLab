package com.rokidlab.phone.glasses

import android.util.Log
import com.rokid.cxr.Caps

/**
 * 解析眼镜端轮询响应中的 ASR 文字（眼镜端 reply.end(Caps[text])）。
 * 原为 CxrLHiRokidSession 私有方法，纯机械外移为同包 internal 顶层函数，
 * 类内调用点（registerGlobalCmdListener）未改动即可直接解析到本函数。
 */
internal fun parseAiAsrPollText(data: ByteArray?): String? {
    try {
        if (data == null || data.isEmpty()) return null
        val caps = Caps.fromBytes(data)
        if (caps == null || caps.size() < 1 || caps.at(0) == null) return null
        val text = caps.at(0).getString()?.trim().orEmpty()
        return text.ifEmpty { null }
    } catch (e: Exception) {
        Log.e("CxrLHiRokidSession", "parseAiAsrPollText error", e)
        return null
    }
}
