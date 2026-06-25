package com.rokidlab.phone.util

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 日志收集器 - 单例
 * 收集运行日志和崩溃信息，支持导出为 TXT 文件
 */
object LogCollector {

    private const val TAG = "LogCollector"
    private const val MAX_LOG_COUNT = 500

    private val logList = mutableListOf<LogEntry>()
    private var appVersion = ""
    private var appVersionCode = 0L
    private var deviceInfo = ""

    data class LogEntry(
        val time: String,
        val level: String,
        val tag: String,
        val message: String,
    )

    /** 初始化：安装全局崩溃处理器 */
    fun init(version: String, versionCode: Long) {
        appVersion = version
        appVersionCode = versionCode
        deviceInfo = buildDeviceInfo()

        // 安装全局未捕获异常处理器
        val prevHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val stack = Log.getStackTraceString(throwable)
            // 先记录
            record("FATAL", "CRASH", "${thread.name}: $stack")
            // 再交给上一个 handler（默认会弹"应用已停止"）
            prevHandler?.uncaughtException(thread, throwable)
        }
    }

    /** 记录一条日志 */
    fun record(level: String, tag: String, message: String) {
        synchronized(logList) {
            val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            logList.add(LogEntry(time, level, tag, message))
            if (logList.size > MAX_LOG_COUNT) {
                logList.removeAt(0)
            }
        }
    }

    /** 快速记录 info 级日志 */
    fun i(tag: String, message: String) = record("I", tag, message)

    /** 快速记录 warn 级日志 */
    fun w(tag: String, message: String) = record("W", tag, message)

    /** 快速记录 error 级日志 */
    fun e(tag: String, message: String) = record("E", tag, message)

    /** 记录异常（含完整堆栈） */
    fun e(tag: String, message: String, throwable: Throwable?) {
        val stack = if (throwable != null) Log.getStackTraceString(throwable) else ""
        record("E", tag, if (stack.isNotBlank()) "$message\n$stack" else message)
    }

    /** 获取当前所有日志文本 */
    fun getLogText(): String {
        val sb = StringBuilder()
        sb.appendLine("========================================")
        sb.appendLine("  RokidLab 错误报告")
        sb.appendLine("========================================")
        sb.appendLine("  应用版本 : v$appVersion (code $appVersionCode)")
        sb.appendLine("  设备信息 : $deviceInfo")
        sb.appendLine("  导出时间 : ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date())}")
        sb.appendLine("========================================")
        sb.appendLine()
        sb.appendLine("--- 日志 ---")
        synchronized(logList) {
            if (logList.isEmpty()) {
                sb.appendLine("  (无日志)")
            } else {
                for (entry in logList) {
                    sb.appendLine("${entry.time} [${entry.level}] [${entry.tag}] ${entry.message}")
                }
            }
        }
        return sb.toString()
    }

    /** 将日志保存到文件并返回 Uri（可通过 Intent 分享/保存） */
    fun saveToFile(context: Context): File? {
        return try {
            val time = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA).format(Date())
            val dir = File(context.cacheDir, "logs")
            dir.mkdirs()
            val file = File(dir, "RokidLab_error_$time.txt")
            FileWriter(file).use { it.write(getLogText()) }
            file
        } catch (e: Exception) {
            Log.e(TAG, "saveToFile failed", e)
            null
        }
    }

    /** 创建分享 Intent（把日志文件发出去，如保存到 TXT） */
    fun createShareIntent(context: Context): Intent? {
        val file = saveToFile(context) ?: return null
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun buildDeviceInfo(): String {
        return "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.BRAND}"
    }

    /** 清空日志 */
    fun clear() {
        synchronized(logList) {
            logList.clear()
        }
    }
}
