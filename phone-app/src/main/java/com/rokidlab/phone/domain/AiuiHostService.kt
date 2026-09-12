package com.rokidlab.phone.domain

import com.rokidlab.phone.app.LabApplication
import com.rokidlab.phone.glasses.AiuiFrontendController
import java.io.File

/**
 * L3 domain/AiuiHostService —— Phase 3 拆 `CxrLHiRokidSession` 的 AIUI 宿主域服务。
 *
 * 职责：AIUI 微前端全链路（AgentStore / 直启 / 自托管宿主）的装配与委派。
 * 原本 `CxrLHiRokidSession` 里 12 个一行委派方法 + `AiuiFrontendController` 构造，
 * 全部收口到这里；Session 只保留同名门面方法（调用方零改动）。
 *
 * 依赖：L2 会话状态（cxrLink / aiCmdLock / rawSendCustomCmd / getAdbShellClient 经 session 句柄），
 * 不直接碰 L0 反射。
 */
class AiuiHostService(private val session: com.rokidlab.phone.glasses.CxrLHiRokidSession) {

    /** AIUI 微前端控制器（AgentStore/直启/自托管宿主全链路的真正实现） */
    internal val controller = AiuiFrontendController(
        appContext = session.appContext,
        appScope = session.appScope,
        routeManager = (session.appContext as LabApplication).routeManager,
        linkProvider = { session.cxrLink },
        cmdLock = session.aiCmdLock,
        rawSendCmd = { link, cmd, caps -> session.rawSendCustomCmd(link, cmd, caps) },
        adbClientProvider = { session.getAdbShellClient() },
    )

    /** 在眼镜上打开一个 AIUI agent（.aix），协议与参数见 AiuiFrontendController */
    fun openAiuiAgent(
        agentId: String,
        agentName: String,
        nativeVersion: String = "0.0.74",
        pageName: String = "pages/index/index",
    ): Int = controller.openAiuiAgent(agentId, agentName, nativeVersion, pageName)

    /** 直启眼镜 cxr 目录已存在的 .aix（Sys_AIUI_Start） */
    fun startAiuiPackage(packageName: String): Int = controller.startAiuiPackage(packageName)

    /** 关闭眼镜上正在渲染的 .aix（Sys_AIUI_Stop） */
    fun stopAiuiPackage(packageName: String): Int = controller.stopAiuiPackage(packageName)

    /** 打开自托管宿主渲染本地已推送的 .aix */
    fun openAiuiHost(fileName: String? = null): Int = controller.openAiuiHost(fileName)

    /** 关闭正在渲染的宿主 */
    fun closeAiuiHost(): Int = controller.closeAiuiHost()

    /** 以 onMessage 协议向宿主页面注入消息 */
    fun sendAiuiHostMessage(json: String): Int = controller.sendAiuiHostMessage(json)

    /** 把本地 .aix 推到 RokidLink aiui_host 目录并自动拉起宿主渲染 */
    fun pushAixToRokidLinkHost(
        aixFile: File,
        openAfter: Boolean = true,
        launchParams: String? = null,
    ): String? = controller.pushAixToRokidLinkHost(aixFile, openAfter, launchParams)

    /** 安装 AIUI agent 到眼镜（Jsai_AddNativeAgent） */
    fun installAiuiAgent(
        agentId: String,
        agentName: String,
        url: String,
        fileMd5: String,
        nativeVersion: String = "0.0.74",
        agentDesc: String = "",
    ): Int = controller.installAiuiAgent(agentId, agentName, url, fileMd5, nativeVersion, agentDesc)

    /** 直装 .aix 并自动打开一次 */
    fun installAndOpenAiuiAgentOnce(
        agentId: String,
        agentName: String,
        url: String,
        fileMd5: String,
        openDelayMs: Long = 3000L,
        openRetryMs: Long = 2000L,
        openTimeoutMs: Long = 25_000L,
    ): Int = controller.installAndOpenAiuiAgentOnce(agentId, agentName, url, fileMd5, openDelayMs, openRetryMs, openTimeoutMs)

    /** 向眼镜下发 native agent 目录地址（Jsai_GetRequestInfo） */
    fun pushAiuiAgentListUrl(agentListUrl: String): Int = controller.pushAiuiAgentListUrl(agentListUrl)

    /** 在时间窗口内周期下发目录配置 */
    fun startAgentListPushWindow(
        catalogUrl: String,
        durationMs: Long = 60_000L,
        intervalMs: Long = 1_500L,
    ) = controller.startAgentListPushWindow(catalogUrl, durationMs, intervalMs)

    fun stopAgentListPushWindow() = controller.stopAgentListPushWindow()

    /** 通知眼镜立即重新拉取 native agent 目录（Jsai_NotifyGlassGetList） */
    fun notifyGlassGetAgentList(): Int = controller.notifyGlassGetAgentList()
}
