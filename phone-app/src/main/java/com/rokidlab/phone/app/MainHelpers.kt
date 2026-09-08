package com.rokidlab.phone.app

import android.app.ActivityManager
import android.content.Context
import android.util.Log

/**
 * 将版本号规范化为带 v 前缀的显示标签（空/空白版本号返回 "latest"）。
 */
internal fun updateVersionLabel(version: String?): String {
    val clean = version?.trim().orEmpty()
    if (clean.isBlank()) return "latest"
    return if (clean.startsWith("v", ignoreCase = true)) clean else "v$clean"
}

/**
 * 检查指定 Service 是否在运行。
 */
internal fun isServiceRunning(context: Context, serviceClass: Class<*>): Boolean {
    return try {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        manager.getRunningServices(Integer.MAX_VALUE).any { service ->
            serviceClass.name == service.service.className
        }
    } catch (e: Exception) {
        Log.e("MainActivity", "isServiceRunning failed: ${e.message}")
        false
    }
}
