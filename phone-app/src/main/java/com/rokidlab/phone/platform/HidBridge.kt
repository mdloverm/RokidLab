package com.rokidlab.phone.platform

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.util.Log

/**
 * L0 platform/HidBridge —— HID 隐藏 API 的唯一反射边界。
 *
 * `BluetoothHidDevice.setReport` 是隐藏 API，作为 BtHidCompat 三级发送兜底
 * （STANDARD → PREFIXED → SET_REPORT）的第三级：QTI 蓝牙栈存在"控制通道可用而
 * 中断通道不可用"的场景，此时只有 setReport 能把报告送达。
 *
 * 收口到 `platform/` 后，ROM/蓝牙栈升级只需改这一处；失败显式打日志（不再是裸 catch）。
 */
object HidBridge {
    private const val TAG = "HidBridge"

    /**
     * 反射调用隐藏 API `BluetoothHidDevice.setReport`。
     *
     * @param outputReportId report 类型（3 = OUTPUT_REPORT）
     * @return true 表示调用成功且系统返回 true；API 缺失 / 调用失败返回 false 并打日志
     */
    fun trySetReport(hid: BluetoothHidDevice, dev: BluetoothDevice, outputReportId: Int, payload: ByteArray): Boolean =
        runCatching {
            val method = BluetoothHidDevice::class.java.getMethod(
                "setReport", BluetoothDevice::class.java, Int::class.java, ByteArray::class.java
            )
            method.invoke(hid, dev, outputReportId, payload) as? Boolean ?: false
        }.onFailure { Log.w(TAG, "setReport 不可用: ${it.message}") }
            .getOrDefault(false)
}
