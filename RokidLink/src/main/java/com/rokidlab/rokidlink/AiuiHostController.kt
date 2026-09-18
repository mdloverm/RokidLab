package com.rokidlab.rokidlink

import android.util.Log
import com.rokid.cxr.Caps
import java.io.File

/**
 * KeyButtonService 的 AIUI 自托管宿主控制器（v3.9 拆分自 KeyButtonService）。
 *
 * 职责：
 *  1. 常驻 .aix 接收服务（AiuiPackageServer，端口 7658）：手机端推送 .aix → 落盘 → 拉起宿主
 *  2. 处理 AIUI_HOST_TOPIC 指令：open [fileName?] [launchParams?] / close / msg [json]
 */
internal class AiuiHostController(
    private val service: KeyButtonService,
    private val core: KeyServiceCore,
) {
    companion object {
        private const val TAG = KeyButtonService.TAG
        private const val CMD_AIUI_OPEN = "open"
        private const val CMD_AIUI_CLOSE = "close"
        private const val CMD_AIUI_MSG = "msg"
    }

    /** AIUI .aix 接收服务（7658）：手机端推送 .aix → 落盘 → 拉起自托管宿主 */
    private var aiuiPkgServer: AiuiPackageServer? = null

    /** 最近成功落盘的 .aix（供宿主打开，可被 AIUI_HOST_TOPIC 命令覆盖打开） */
    private var aiuiLastFile: File? = null

    /** 启动 .aix 接收服务：收到完整包后自动拉起 AiuiLinkActivity 渲染 */
    fun startPackageServer() {
        if (aiuiPkgServer?.isRunning == true) return
        val server = AiuiPackageServer(listener = object : AiuiPackageServer.Listener {
            override fun onPackageReceived(file: File) {
                aiuiLastFile = file
                Log.i(TAG, "AIUI package received: ${file.name} -> open host")
                core.mainHandler.post { openAiuiHost(file) }
            }
        })
        aiuiPkgServer = server
        if (!server.start(service)) {
            Log.e(TAG, "AIUI package server failed to start on 7658")
        }
    }

    /** AIUI_HOST_TOPIC 指令：open [fileName?] / close / msg [json] */
    fun handleAiuiHost(args: Caps) {
        try {
            val f = capsToStrings(args)
            if (f.isEmpty() || f[0].isNullOrBlank()) return
            when (f[0]) {
                CMD_AIUI_OPEN -> {
                    val name = f.getOrNull(1)?.takeIf { it.isNotBlank() }
                    // caps[2] = 启动参数（JSON 对象字符串），随 open 一起下发：
                    // 必须走 open 而不是 open 之后补一条 msg —— 页面此刻尚未解包渲染，
                    // 任何 hostMessage 都会被 host.js 的 `if (!view) return` 静默丢弃。
                    val launchParams = f.getOrNull(2)?.takeIf { it.isNotBlank() }
                    val file = if (name != null) File(service.filesDir, "aiui_host/$name") else aiuiLastFile
                    if (file == null || !file.isFile) {
                        Log.w(TAG, "aiui open: no file to open ($name / last=${aiuiLastFile?.name})")
                        return
                    }
                    aiuiLastFile = file
                    core.mainHandler.post { openAiuiHost(file, launchParams) }
                }
                CMD_AIUI_CLOSE -> core.mainHandler.post { AiuiLinkActivity.closeActive() }
                CMD_AIUI_MSG -> {
                    val json = f.getOrNull(1)
                    // 诊断：toolResult 回不到页面时，先看这条 —— len=0 表示 caps[1] 没传过来
                    Log.i(TAG, "aiui msg: argc=${f.size} len=${json?.length ?: 0} head=${json?.take(120)}")
                    if (!json.isNullOrBlank()) {
                        AiuiLinkActivity.dispatchMessageToActive(json)
                    }
                }
                else -> Log.w(TAG, "aiui host unknown cmd: ${f[0]}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "handleAiuiHost error", e)
        }
    }

    private fun openAiuiHost(file: File, launchParams: String? = null) {
        try {
            AiuiLinkActivity.open(service, file.absolutePath, launchParams)
        } catch (e: Exception) {
            Log.e(TAG, "open AiuiLinkActivity failed", e)
        }
    }

    /** 服务销毁：停 AIUI 接收服务（自愈重启后会在新实例 onCreate 重新拉起） */
    fun onDestroy() {
        runCatching { aiuiPkgServer?.stop() }
        aiuiPkgServer = null
    }
}
