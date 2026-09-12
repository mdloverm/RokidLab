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

    /**
     * 记录 warn 级异常（含完整堆栈）。
     *
     * 与 [e] 的区别只在级别：链路断开、退避重连这类「预期内的高频失败」用 W，
     * 不会污染「仅错误日志」导出；但堆栈同样要留 —— 只有 message 时无法区分
     * 「对端未启动」和「RFCOMM 被栈拒绝」。
     */
    fun w(tag: String, message: String, throwable: Throwable?) =
        record("W", tag, withStack(message, throwable))

    /** 快速记录 error 级日志 */
    fun e(tag: String, message: String) = record("E", tag, message)

    /** 记录异常（含完整堆栈） */
    fun e(tag: String, message: String, throwable: Throwable?) =
        record("E", tag, withStack(message, throwable))

    /**
     * 拼接异常堆栈。
     *
     * 必须对 `Log.getStackTraceString` 的返回值做兜底：JVM 单测下 android.jar 是桩实现
     * （`unitTests.isReturnDefaultValues = true`）会返回 null，直接 `isNotBlank()` 就是 NPE ——
     * 那等于让「落日志」本身成为新的故障点（ADB sync 单测会走 catch 分支触发它）。
     */
    private fun withStack(message: String, throwable: Throwable?): String {
        val stack = throwable?.let { runCatching { Log.getStackTraceString(it) }.getOrNull() }.orEmpty()
        return if (stack.isBlank()) message else "$message\n$stack"
    }

    /** 获取当前所有日志文本 */
    fun getLogText(): String {
        return buildLogText(false)
    }

    /** 获取仅错误级别日志（ERROR/FATAL） */
    fun getErrorLogText(): String {
        return buildLogText(true)
    }

    private fun buildLogText(errorsOnly: Boolean): String {
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
            val entries = if (errorsOnly) logList.filter { it.level == "E" || it.level == "FATAL" } else logList
            if (entries.isEmpty()) {
                sb.appendLine("  (${if (errorsOnly) "无错误日志" else "无日志"})")
            } else {
                for (entry in entries) {
                    sb.appendLine("${entry.time} [${entry.level}] [${entry.tag}] ${entry.message}")
                }
                if (errorsOnly) sb.appendLine("\n  (共 ${logList.size} 条日志，已过滤显示 ${entries.size} 条错误)")
            }
        }
        return sb.toString()
    }

    /** 将日志保存到文件并返回 Uri（可通过 Intent 分享/保存） */
    fun saveToFile(context: Context, errorsOnly: Boolean = false): File? {
        return try {
            val time = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA).format(Date())
            val dir = File(context.cacheDir, "logs")
            dir.mkdirs()
            val suffix = if (errorsOnly) "_errors" else ""
            val file = File(dir, "RokidLab${suffix}_$time.txt")
            val text = if (errorsOnly) getErrorLogText() else getLogText()
            FileWriter(file).use { it.write(text) }
            file
        } catch (e: Exception) {
            Log.e(TAG, "saveToFile failed", e)
            null
        }
    }

    /** 创建分享 Intent（把日志文件发出去，如保存到 TXT） */
    fun createShareIntent(context: Context, errorsOnly: Boolean = false): Intent? {
        val file = saveToFile(context, errorsOnly) ?: return null
        return shareIntentFor(context, file)
    }

    /**
     * 把任意诊断文本落成文件并返回分享 Intent。
     *
     * 与日志共用同一条 FileProvider 导出通道，让「兼容性诊断报告」这类非日志内容
     * 也能一键发给开发者，不必为每种导出各写一套落盘 + 分享。
     */
    fun createTextShareIntent(context: Context, filePrefix: String, content: String): Intent? {
        val file = try {
            val time = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA).format(Date())
            val dir = File(context.cacheDir, "logs")
            dir.mkdirs()
            File(dir, "${filePrefix}_$time.txt").also { FileWriter(it).use { w -> w.write(content) } }
        } catch (e: Exception) {
            Log.e(TAG, "createTextShareIntent failed", e)
            return null
        }
        return shareIntentFor(context, file)
    }

    private fun shareIntentFor(context: Context, file: File): Intent {
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
