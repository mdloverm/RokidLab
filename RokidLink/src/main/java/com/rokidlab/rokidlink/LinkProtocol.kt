package com.rokidlab.rokidlink

/**
 * Lab 双端通信协议常量收敛（双端同源副本，必须同步修改；由 checkProtocolSynced 守护）。
 *
 * 把散落在各业务文件里的「裸 CXR 频道名」与「__LAB_* 控制帧标记」集中到这里，
 * 避免改一端漏一端，且构建期能被 [checkProtocolSynced] 守护（与 [AiChannel] 同一机制）。
 *
 * 历史问题：此前这些字面量直接写在 CxrLHiRokidSession / AiuiFrontendController /
 * AsrBridgeCoordinator（手机端）与 KeyButtonService / AsrPushServer（眼镜端）里，
 * 改一端漏一端时构建不会拦（checkProtocolSynced 只守护 AiChannel.kt），
 * 造成跨端信令错位。本文件把这类「双端都要一致」的常量统一收口。
 */
object LinkProtocol {
    // ── CXR 自定义指令频道名（sendCustomCmd 第一参数）──
    const val CXR_CHANNEL_AI = "Ai"
    const val CXR_CHANNEL_SYS = "Sys"
    const val CXR_CHANNEL_WIFI = "Wifi"
    const val CXR_CHANNEL_JSAI = "Jsai"
    const val CXR_CHANNEL_AI_RENDER = "Ai_RenderPayload"

    // ── __LAB_* 控制帧标记（ASR 推送 / 工具调用桥 等跨端控制信令）──
    const val MARKER_MUSIC_STOP = "__LAB_MUSIC_STOP__"
    const val MARKER_ABORT_AI = "__LAB_ABORT_AI__"
    const val MARKER_PHOTO_ASK = "__LAB_PHOTO_ASK__"
    const val MARKER_TOOL_CALL = "__LAB_TOOL__"
    const val MARKER_ASR_READY = "__LAB_ASR_READY__"

    // ── ASR 推送通道 WiFi 握手（防同网段主机挤占）──
    /**
     * ASR 推送通道（眼镜端 [AsrPushServer] 的 7660 TCP 监听）WiFi 建链握手令牌。
     *
     * 为什么需要：眼镜端该监听绑 `0.0.0.0`，且接入语义是「替换式单客户端」——同网段任意
     * 主机连上 7660 就能挤掉真手机的推送连接（表现为眼镜上 AI 回答文字停更），
     * 并可读取本通道下发的 ASR 文本。手机端建链后立即写入本令牌，眼镜端校验通过才接入；
     * 超时或内容不符则直接关闭连接，不再成为「当前客户端」。
     *
     * RFCOMM 通道不做校验：蓝牙 SPP 建链本身已由配对链路鉴权，无同网段暴露面。
     *
     * 兼容性：旧版眼镜端会把这几个字节当「上行垃圾数据」丢弃（本通道原本就只下行），
     * 因此「新手机端 + 旧眼镜端」不受影响；反向组合需两端同步升级（双端版本本就要求一致）。
     */
    val ASR_PUSH_HANDSHAKE: ByteArray = "ROKIDPUSH1".toByteArray(Charsets.UTF_8)

    // ── 工具确认通道（Phase 4：call_phone 等副作用工具执行前的眼镜端用户确认）──
    /** 确认请求下行（手机端 → 眼镜端）：caps = [requestId, 工具名, 摘要文案] */
    const val TOPIC_TOOL_CONFIRM = "rokidlab_tool_confirm"
    /** 确认结果上行（眼镜端 → 手机端）：caps = [requestId, "yes"/"no"] */
    const val TOPIC_TOOL_CONFIRM_RESULT = "rokidlab_tool_confirm_result"

    // ── 协议版本与能力握手（插播 B · LinkProtocol v2）──
    /** 当前协议版本。手机端据此判断眼镜端能力，不再靠 try-and-catch 探测。 */
    const val PROTOCOL_VERSION = 2
    /** v1：仅基础频道 + __LAB_* 标记，无能力协商（旧眼镜端） */
    const val PROTOCOL_VERSION_V1 = 1

    /** 眼镜端能力位（bit flags，随握手上报）。手机端用 [Cap] 判断某特性是否可用。 */
    object Cap {
        const val NONE = 0
        /** 支持工具确认窗口（[TOPIC_TOOL_CONFIRM] / [TOPIC_TOOL_CONFIRM_RESULT]） */
        const val TOOL_CONFIRM = 1 shl 0
        /** 支持眼镜端悬浮歌词层（Link 通道歌词） */
        const val LYRIC_OVERLAY = 1 shl 1
        /** 支持 AIUI 自托管宿主（open / close / msg） */
        const val AIUI_HOST = 1 shl 2
        /** 支持下行存活探测 ping（断线重连路由 stale 自愈） */
        const val SELF_HEAL_PING = 1 shl 3
        /** 支持在眼镜端显示手机端下发的图片（[AiChannel.TOPIC_SHOW_IMAGE]） */
        const val SHOW_IMAGE = 1 shl 4
        /** 支持按手机端指令拉起眼镜端页面（[AiChannel.TOPIC_OPEN_APP]，如系统音乐页/歌词页） */
        const val OPEN_APP = 1 shl 5
        /** 当前版本眼镜端默认能力全集 */
        const val ALL = TOOL_CONFIRM or LYRIC_OVERLAY or AIUI_HOST or SELF_HEAL_PING or SHOW_IMAGE or OPEN_APP
    }

    /**
     * 握手通告（眼镜端 → 手机端）。caps = [version, capsBitmask, linkVersion]。
     * 眼镜端服务就绪时主动上报一次，并对 [TOPIC_HELLO_REQ] 再应答。
     */
    const val TOPIC_HELLO = "rokidlab_hello"

    /** 握手请求（手机端 → 眼镜端）。手机端连接建立后主动询问，覆盖「眼镜先启动」场景。 */
    const val TOPIC_HELLO_REQ = "rokidlab_hello_req"
}
