package com.rokidlab.phone.glasses

/**
 * 眼镜端连接状态快照（CxrLHiRokidSession 对外连接状态模型）。
 * 原定义于 CxrLHiRokidSession.kt 底部，纯机械外移至本文件，未做逻辑改动。
 */
data class CxrConnectionState(
    val authorized: Boolean = false,
    val cxrlConnected: Boolean = false,
    val glassBtConnected: Boolean = false,
) {
    val connected: Boolean
        get() = cxrlConnected && glassBtConnected

    val connecting: Boolean
        get() = authorized && (cxrlConnected || glassBtConnected) && !connected
}
