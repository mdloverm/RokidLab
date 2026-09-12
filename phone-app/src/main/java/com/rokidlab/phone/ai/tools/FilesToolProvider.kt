package com.rokidlab.phone.ai.tools

import android.content.Context
import android.util.Log
import com.rokidlab.phone.ai.AiuiAppRegistry
import com.rokidlab.phone.ai.AiuiProject
import com.rokidlab.phone.ai.Calculator
import com.rokidlab.phone.ai.KnowledgeBase
import com.rokidlab.phone.ai.KuwoMusicApi
import com.rokidlab.phone.ai.LocationTools
import com.rokidlab.phone.ai.MusicPlayerController
import com.rokidlab.phone.ai.PhoneTools
import com.rokidlab.phone.ai.ToolRegistry
import com.rokidlab.phone.ai.WeatherTools
import com.rokidlab.phone.ai.WebTools
import com.rokidlab.phone.app.LabApplication
import com.rokidlab.phone.adb.ui.TimerAction
import com.rokidlab.phone.adb.ui.TimerSchedule
import com.rokidlab.phone.adb.ui.TimerTask
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * FilesToolProvider —— 文件产出域（总结 txt / AIUI 代码落盘与回读）。
 * Phase 4 从 `ToolRegistry.execute` 迁出的该域工具执行分支（逐字搬运，行为不变）。
 */
internal object FilesToolProvider : ToolProvider {
    private const val TAG = "FilesToolProvider"

    override val toolNames = setOf(
        "save_summary_txt",
        "save_code_file",
        "read_code_file",
    )

    override fun execute(context: Context, name: String, args: JSONObject): String {
        return when (name) {
            "save_summary_txt" -> WebTools.saveSummary(
                context,
                title = args.optString("title"),
                content = args.optString("content"),
            )

            ToolRegistry.TOOL_CODE_FILE -> WebTools.writeProjectFile(
                context,
                project = args.optString("project"),
                filePath = args.optString("file"),
                content = args.optString("content"),
            )

            ToolRegistry.TOOL_READ_CODE_FILE -> WebTools.readProjectFile(
                context,
                project = args.optString("project"),
                filePath = args.optString("file"),
            )

        else -> throw IllegalArgumentException("未知工具: $name")
        }
    }
}
