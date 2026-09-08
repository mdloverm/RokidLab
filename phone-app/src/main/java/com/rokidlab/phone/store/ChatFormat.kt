package com.rokidlab.phone.store

import java.util.Locale

/** 文档大小格式化显示 */
internal fun formatDocSize(bytes: Int): String = when {
    bytes >= 1024 * 1024 -> String.format(Locale.getDefault(), "%.1f MB", bytes / 1024f / 1024f)
    bytes >= 1024 -> String.format(Locale.getDefault(), "%.1f KB", bytes / 1024f)
    else -> "$bytes B"
}
