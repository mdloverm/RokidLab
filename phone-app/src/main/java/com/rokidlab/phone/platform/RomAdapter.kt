package com.rokidlab.phone.platform

import android.os.Build
import android.view.View
import android.view.WindowManager

/**
 * ROM / 设备能力适配：把散落在各处的反射收口到这一个文件（L0 `platform/`）。
 *
 * 当前收口：
 *  - [systemProperty] / [systemPropertyCapability] 取代
 *    `RomFingerprint.getProp` / `ManufacturerUtils.getSystemProperty` / `BtHidCompat.prop`
 *    三处重复的 `Class.forName("android.os.SystemProperties")` 反射。
 *  - [setFrameRatePowerSavingsBalanced] / [setRequestedFrameRate] / [requestedFrameRateCategoryHigh]
 *    取代 `MainActivity` 里两处强制高刷的隐藏 API 反射。
 *
 * 这是全仓除 [SdkFieldMap] 外另一处允许反射的地方（见架构文档 §3.1）。任何失败都以
 * [Capability] 显式表达，调用方据此降级而不是静默 `catch (e: Exception) { }` 吞掉。
 */
object RomAdapter {

    /**
     * 读取系统属性（隐藏 API `android.os.SystemProperties.get`）。
     * 失败或属性为空时返回 `null`，与原三处 `getProp` / `getSystemProperty` 语义一致
     * （ROM 指纹本就设计为「读不到就退化到 Build 字段」）。
     */
    fun systemProperty(name: String): String? =
        when (val r = systemPropertyCapability(name)) {
            is Capability.Available -> r.value.takeIf { it.isNotBlank() }
            is Capability.Unavailable -> null
        }

    /** 带失败原因的版本，便于诊断面板展示「为什么读不到某属性」。 */
    fun systemPropertyCapability(name: String): Capability<String> =
        runCatching {
            val clazz = Class.forName("android.os.SystemProperties")
            val method = clazz.getMethod("get", String::class.java)
            val value = method.invoke(null, name) as? String ?: ""
            Capability.Available(value)
        }.getOrElse { e ->
            Capability.Unavailable("SystemProperties.get($name) 失败: ${e.message}")
        }

    /**
     * 强制高刷省电平衡开关（隐藏 API，API 35+）。
     * 调用方传入 [WindowManager.LayoutParams]（即 `window.attributes`）。
     */
    fun setFrameRatePowerSavingsBalanced(attrs: WindowManager.LayoutParams, balanced: Boolean): Capability<Unit> {
        if (Build.VERSION.SDK_INT < 35) {
            return Capability.Unavailable("setFrameRatePowerSavingsBalanced 需要 API 35+（当前 ${Build.VERSION.SDK_INT}）")
        }
        return runCatching {
            val wlpClass = Class.forName("android.view.WindowLayoutParams")
            val method = wlpClass.getMethod("setFrameRatePowerSavingsBalanced", Boolean::class.java)
            method.invoke(attrs, balanced)
            Capability.Available(Unit)
        }.getOrElse { e ->
            Capability.Unavailable("setFrameRatePowerSavingsBalanced 失败: ${e.message}")
        }
    }

    /** 为指定 View 设置请求帧率档位（隐藏 API，API 35+）。[category] 取 [requestedFrameRateCategoryHigh] 等。 */
    fun setRequestedFrameRate(view: View, category: Int): Capability<Unit> {
        if (Build.VERSION.SDK_INT < 35) {
            return Capability.Unavailable("setRequestedFrameRate 需要 API 35+（当前 ${Build.VERSION.SDK_INT}）")
        }
        return runCatching {
            val setMethod = View::class.java.getMethod("setRequestedFrameRate", Int::class.java)
            setMethod.invoke(view, category)
            Capability.Available(Unit)
        }.getOrElse { e ->
            Capability.Unavailable("setRequestedFrameRate 失败: ${e.message}")
        }
    }

    /** 取 [View.REQUESTED_FRAME_RATE_CATEGORY_HIGH] 等隐藏常量（API 35+ 才存在）。 */
    fun requestedFrameRateCategoryHigh(): Capability<Int> {
        if (Build.VERSION.SDK_INT < 35) {
            return Capability.Unavailable("REQUESTED_FRAME_RATE_CATEGORY_HIGH 需要 API 35+（当前 ${Build.VERSION.SDK_INT}）")
        }
        return runCatching {
            val field = View::class.java.getField("REQUESTED_FRAME_RATE_CATEGORY_HIGH")
            Capability.Available(field.getInt(null))
        }.getOrElse { e ->
            Capability.Unavailable("REQUESTED_FRAME_RATE_CATEGORY_HIGH 读取失败: ${e.message}")
        }
    }
}
