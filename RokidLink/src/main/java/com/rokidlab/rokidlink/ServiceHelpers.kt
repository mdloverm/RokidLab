package com.rokidlab.rokidlink

import com.rokid.cxr.Caps

/** Caps → List<String?>：字符串值取出、非字符串/空槽保留 null（协议解析见 AiChannel） */
internal fun capsToStrings(args: Caps): List<String?> {
    if (args == null || args.size() == 0) return emptyList()
    return List(args.size()) { i ->
        val v = args.at(i) ?: return@List null
        if (v.type() == Caps.Value.TYPE_STRING) v.getString() else null
    }
}
