package com.rokidlab.phone.glasses

import android.util.Log

/**
 * L2 session/GlassesHandshake —— 眼镜端能力握手状态（插播 B · LinkProtocol v2）。
 *
 * 眼镜端服务就绪时主动上报 [LinkProtocol.TOPIC_HELLO]（caps = [version, capsBitmask, linkVersion]）；
 * 手机端连接建立后也会下发 [LinkProtocol.TOPIC_HELLO_REQ] 主动询问（覆盖「眼镜端后启动」场景）。
 *
 * **三态语义**（重要）：[supports] 返回 `null` 表示「尚未握手，能力未知」，
 * 调用方应走**乐观**路径（按支持处理，失败再兜底）；返回 `false` 才是「已确认不支持」，
 * 调用方应走**快速降级**路径（立即拒绝，不再空等超时）。
 *
 * 旧版眼镜端（v1）不会应答握手；手机端在发出请求后 [LEGACY_DETECT_DELAY_MS] 内
 * 仍未收到通告即调用 [markLegacy] 标记为旧版，避免每次工具确认都空等 35s。
 *
 * 线程安全：字段均 @Volatile，读多写少（仅握手/断开时写）。
 */
object GlassesHandshake {
    private const val TAG = "GlassesHandshake"

    /** 发出 HELLO_REQ 后等待应答的时长；超时未见通告即判为旧版（v1，无能力协商） */
    const val LEGACY_DETECT_DELAY_MS = 4_000L

    /** 眼镜端协议版本；0 = 尚未握手 */
    @Volatile
    var version: Int = 0
        private set

    /** 眼镜端能力位掩码（见 [LinkProtocol.Cap]） */
    @Volatile
    var caps: Int = 0
        private set

    /** 是否已完成握手（收到过通告） */
    @Volatile
    var known: Boolean = false
        private set

    /** 是否已判定为旧版（v1：无能力协商） */
    @Volatile
    var legacy: Boolean = false
        private set

    /** 收到眼镜端握手通告。 */
    fun onHello(ver: Int, capsBitmask: Int, linkVersion: String?) {
        version = ver
        caps = capsBitmask
        known = true
        legacy = false
        Log.i(TAG, "hello: version=$ver caps=0x${capsBitmask.toString(16)} linkVersion=${linkVersion ?: "-"}")
    }

    /** 请求已发出但超时未收到通告 → 判定为旧版眼镜端（v1）。 */
    fun markLegacy() {
        if (known) return
        legacy = true
        version = LinkProtocol.PROTOCOL_VERSION_V1
        caps = LinkProtocol.Cap.NONE
        Log.w(TAG, "no hello within ${LEGACY_DETECT_DELAY_MS}ms -> legacy glasses (v1, no capability negotiation)")
    }

    /**
     * 能力查询。返回 `null` = 未知（尚未握手，走乐观路径）；`true`/`false` = 已知。
     */
    fun supports(bit: Int): Boolean? = when {
        known -> (caps and bit) != 0
        legacy -> false
        else -> null
    }

    /** 断开/销毁时复位，下次连接重新握手。 */
    fun reset() {
        version = 0
        caps = 0
        known = false
        legacy = false
    }

    /** 供日志/诊断面板展示。 */
    fun describe(): String = when {
        known -> "v$version caps=0x${caps.toString(16)}"
        legacy -> "v1(legacy, no caps)"
        else -> "unknown"
    }
}
