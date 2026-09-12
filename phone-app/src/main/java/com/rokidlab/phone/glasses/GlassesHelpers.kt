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

/**
 * 将 CXR 自定义指令的 data（Caps 字节）反序列化为 List<String?>，供 AiChannel 版本化编解码解析。
 * 眼镜端 glasses_ip / ai_config / key_config 等载荷统一以 [cmd, version, ...] 顺序写入 Caps，
 * 手机端据此解出字段列表后交给 AiChannel.decodeXxx 校验 cmd+版本。
 */
internal fun capsToFieldList(data: ByteArray?): List<String?> {
    try {
        if (data == null || data.isEmpty()) return emptyList()
        val caps = Caps.fromBytes(data) ?: return emptyList()
        if (caps.size() < 1) return emptyList()
        val out = ArrayList<String?>(caps.size())
        for (i in 0 until caps.size()) {
            val v = caps.at(i)
            out.add(if (v == null) null else v.getString())
        }
        return out
    } catch (e: Exception) {
        Log.e("CxrLHiRokidSession", "capsToFieldList error", e)
        return emptyList()
    }
}
