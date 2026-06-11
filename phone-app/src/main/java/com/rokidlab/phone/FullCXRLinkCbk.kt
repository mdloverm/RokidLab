package com.rokidlab.phone

import com.rokid.cxr.link.callbacks.ICXRLinkCbk

/**
 * 完整的 CXR-L 回调接口实现类
 * 实现了 SDK 1.0.3 中所有必需的回调方法
 */
class FullCXRLinkCbk(
    private val onConnected: (Boolean) -> Unit,
    private val onBtConnected: (Boolean) -> Unit,
    private val onDeviceInfo: (com.rokid.cxr.link.utils.GlassInfo) -> Unit = {},
    private val onWearingStatus: (Boolean) -> Unit = {},
    private val onAiAssistStart: () -> Unit = {},
    private val onAiAssistStop: () -> Unit = {},
    private val onAiInterrupt: (Boolean) -> Unit = {}
) : ICXRLinkCbk {
    
    override fun onCXRLConnected(connected: Boolean) {
        onConnected(connected)
    }

    override fun onGlassBtConnected(connected: Boolean) {
        onBtConnected(connected)
    }

    override fun onGlassDeviceInfo(info: com.rokid.cxr.link.utils.GlassInfo) {
        onDeviceInfo(info)
    }

    override fun onGlassWearingStatus(wearing: Boolean) {
        onWearingStatus(wearing)
    }

    override fun onGlassAiAssistStart() {
        onAiAssistStart()
    }

    override fun onGlassAiAssistStop() {
        onAiAssistStop()
    }

    override fun onGlassAiInterrupt(interrupt: Boolean) {
        onAiInterrupt(interrupt)
    }
}
