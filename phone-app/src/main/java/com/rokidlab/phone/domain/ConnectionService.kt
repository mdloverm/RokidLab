package com.rokidlab.phone.domain

import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.util.Log
import com.rokid.cxr.Caps
import com.rokid.cxr.link.CXRLink
import com.rokid.cxr.link.callbacks.IGlassAppCbk
import com.rokid.cxr.link.utils.CxrDefs
import com.rokidlab.phone.R
import com.rokidlab.phone.design.RokidHostApp
import com.rokidlab.phone.glasses.CxrLHiRokidSession.Companion.AUTH_PACKAGE_EXTRA
import com.rokidlab.phone.glasses.CxrLHiRokidSession.Companion.AUTH_TOKEN_EXTRA
import com.rokidlab.phone.glasses.CxrLHiRokidSession.Companion.MEDIA_SERVICE_ACTION
import com.rokidlab.phone.glasses.FullCXRLinkCallback
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 一次 CUSTOMAPP 会话操作的描述（原 `CxrLHiRokidSession` 私有嵌套类，Phase 3 迁至 domain 顶层）。
 */
internal data class CxrAppOperation(
    val packageName: String,
    val timeoutMillis: Long,
    val timeoutMessage: String,
    val configureFailureMessage: String,
    val bindFailureMessage: String,
    val bindMessage: String? = null,
    val showConnectionStatus: Boolean = false,
    val onReady: (CXRLink) -> Unit,
    val onFailure: () -> Unit,
    val onBindFailure: () -> Unit = onFailure,
)

/**
 * L3 domain/ConnectionService —— Phase 3 拆 `CxrLHiRokidSession` 的连接编排域服务。
 *
 * 职责：connectAnd* 应用操作家族（安装/启动/停止/卸载/查询/自定义指令）、
 * pending 操作调度与超时、Rokid 主机 App 服务绑定（bind/unbind 反射）、
 * cmd 黑名单绕过。
 *
 * 依赖：L2 会话状态（cxrLink / 连接标志 / 操作状态字段经 session 句柄访问，
 * 字段本体保留在 Session 以维持 cleanup 的单一清理入口）；
 * 指令监听注册（registerGlobalCmdListener）仍由 Session 持有。
 */
class ConnectionService(private val session: com.rokidlab.phone.glasses.CxrLHiRokidSession) {
    private companion object { const val TAG = "ConnectionService" }

    /** 缓存反射获取的 ServiceConnection 字段，避免每次操作都反射遍历 */
    @Volatile
    private var cachedServiceConnectionField: java.lang.reflect.Field? = null

    /** 已 bind 的 Rokid 主机 App ServiceConnection（cleanup 时统一 unbind，防泄漏） */
    private val boundConnections = java.util.concurrent.CopyOnWriteArrayList<ServiceConnection>()

    internal fun connectAndUpload(
        authToken: String,
        targetHostApp: RokidHostApp,
        packageName: String,
        apkFile: File,
        onInstallResult: ((Boolean) -> Unit)?,
    ) {
        connectAndRunCustomAppOperation(
            authToken = authToken,
            targetHostApp = targetHostApp,
            operation = CxrAppOperation(
                packageName = packageName,
                timeoutMillis = 90_000,
                timeoutMessage = session.appContext.getString(R.string.waiting_install_result, targetHostApp.displayName),
                bindMessage = session.appContext.getString(R.string.binding_service, targetHostApp.displayName),
                configureFailureMessage = session.appContext.getString(R.string.cxrl_config_failed),
                bindFailureMessage = session.appContext.getString(R.string.service_bind_failed, targetHostApp.displayName, targetHostApp.displayName),
                showConnectionStatus = true,
                onReady = { link ->
                    android.util.Log.i("CxrLInstall", "connectAndUpload: onReady! Starting appUploadAndInstall...")
                    session.onStatus(session.appContext.getString(R.string.cxrl_ready_installing))
                    link.appUploadAndInstall(apkFile.absolutePath, glassAppCallback(
                        onInstall = { success ->
                            completeActiveOperation()
                            session.onStatus(if (success) session.appContext.getString(R.string.glasses_install_success) else session.appContext.getString(R.string.glasses_install_failed))
                            session.onBusyChanged(false)
                            onInstallResult?.invoke(success)
                        },
                    ))
                },
                onFailure = {
                    session.cleanup()
                    session.onBusyChanged(false)
                    onInstallResult?.invoke(false)
                },
            ),
        )
    }

    internal fun queryNext(authToken: String, targetHostApp: RokidHostApp) {
        val packageName = session.queryQueue.pollFirst()
        if (packageName == null) {
            finishQueries()
            return
        }
        connectAndQuery(authToken, targetHostApp, packageName)
    }

    internal fun connectAndQuery(authToken: String, targetHostApp: RokidHostApp, packageName: String) {
        connectAndRunCustomAppOperation(
            authToken = authToken,
            targetHostApp = targetHostApp,
            operation = CxrAppOperation(
                packageName = packageName,
                timeoutMillis = 30_000,
                timeoutMessage = session.appContext.getString(R.string.query_timeout, packageName),
                configureFailureMessage = session.appContext.getString(R.string.query_config_failed, packageName),
                bindFailureMessage = session.appContext.getString(R.string.service_bind_failed, targetHostApp.displayName, targetHostApp.displayName),
                onReady = { link ->
                    link.appIsInstalled(glassAppCallback(
                        onQuery = { installed ->
                            completeActiveOperation()
                            session.onQueryResult?.invoke(packageName, installed)
                            queryNext(authToken, targetHostApp)
                        },
                    ))
                },
                onFailure = {
                    // 查询失败 ≠ 未安装：上报 null（状态未知），由调用方决定保守策略。
                    // 绝不能伪造成 false —— 否则眼镜端残留的旧包（如 debug 签名旧版）会被误判为
                    // "未安装"而跳过卸载，直接覆盖安装时眼镜端 pm install 报
                    // INSTALL_FAILED_UPDATE_INCOMPATIBLE（用户可见的"系统报错"）。
                    session.onQueryResult?.invoke(packageName, null)
                    queryNext(authToken, targetHostApp)
                },
                onBindFailure = {
                    finishQueries()
                },
            ),
        )
    }

    internal fun connectAndUninstall(
        authToken: String,
        targetHostApp: RokidHostApp,
        packageName: String,
        onUninstallResult: ((Boolean) -> Unit)?,
    ) {
        connectAndRunCustomAppOperation(
            authToken = authToken,
            targetHostApp = targetHostApp,
            operation = CxrAppOperation(
                packageName = packageName,
                timeoutMillis = 60_000,
                timeoutMessage = session.appContext.getString(R.string.uninstall_timeout, packageName),
                bindMessage = session.appContext.getString(R.string.binding_service, targetHostApp.displayName),
                configureFailureMessage = session.appContext.getString(R.string.uninstall_config_failed, packageName),
                bindFailureMessage = session.appContext.getString(R.string.service_bind_failed, targetHostApp.displayName, targetHostApp.displayName),
                showConnectionStatus = true,
                onReady = { link ->
                    session.onStatus(session.appContext.getString(R.string.cxrl_ready_uninstalling, packageName))
                    link.appUninstall(glassAppCallback(
                        onUninstall = { success ->
                            completeActiveOperation()
                            session.onStatus(if (success) session.appContext.getString(R.string.glasses_uninstall_success) else session.appContext.getString(R.string.glasses_uninstall_failed))
                            session.onBusyChanged(false)
                            onUninstallResult?.invoke(success)
                        },
                    ))
                },
                onFailure = {
                    session.cleanup()
                    session.onBusyChanged(false)
                    onUninstallResult?.invoke(false)
                },
            ),
        )
    }

    internal fun connectAndLaunch(
        authToken: String,
        targetHostApp: RokidHostApp,
        packageName: String,
        activityClass: String,
        sendCmdAfterLaunch: String?,
        onLaunchResult: ((Boolean) -> Unit)?,
    ) {
        connectAndRunCustomAppOperation(
            authToken = authToken,
            targetHostApp = targetHostApp,
            operation = CxrAppOperation(
                packageName = packageName,
                timeoutMillis = 30_000,
                timeoutMessage = session.appContext.getString(R.string.launch_timeout, packageName),
                bindMessage = session.appContext.getString(R.string.binding_service, targetHostApp.displayName),
                configureFailureMessage = session.appContext.getString(R.string.launch_config_failed, packageName),
                bindFailureMessage = session.appContext.getString(R.string.service_bind_failed, targetHostApp.displayName, targetHostApp.displayName),
                showConnectionStatus = true,
                onReady = { link ->
                    session.onStatus(session.appContext.getString(R.string.cxrl_ready_launching, packageName))
                    // 文档要求 appStart 使用 "${packageName}${activityClassName}" 格式
                    val entryUri = "$packageName$activityClass"
                    link.appStart(entryUri, object : IGlassAppCbk {
                        override fun onInstallAppResult(success: Boolean) = Unit
                        override fun onUnInstallAppResult(success: Boolean) = Unit
                        override fun onOpenAppResult(success: Boolean) {
                            if (success) {
                                // 置一次性冷却标志：appStart 后眼镜端 RokidLink 会真实 resume，
                                // 触发 Sys_App_Resume_Change 上行，消费该 resume 避免误触发拍照答题
                                session.quizResumeCooling = true
                                session.appScope.launch {
                                    delay(3000)
                                    session.quizResumeCooling = false
                                }
                                // SDK 的 appStart 内部会用传入 cbk 覆盖 setCXRGlassAppCbk，
                                // 重新注册以恢复 onGlassAppResume 回调（按键答题 + ASR 打断信号都依赖它）
                                session.registerKeyQuizResumeListener(link)
                                if (sendCmdAfterLaunch != null) {
                                    // 眼镜端已启动，发送自定义命令触发自动操作
                                    val cmdResult = link.sendCustomCmd(sendCmdAfterLaunch, Caps())
                                    session.onStatus(session.appContext.getString(R.string.cmd_result, sendCmdAfterLaunch, cmdResult))
                                }
                            }
                            completeActiveOperation()
                            session.onStatus(if (success) session.appContext.getString(R.string.glasses_launch_success, packageName) else session.appContext.getString(R.string.glasses_launch_failed, packageName))
                            session.onBusyChanged(false)
                            onLaunchResult?.invoke(success)
                        }
                        override fun onStopAppResult(success: Boolean) = Unit
                        override fun onGlassAppResume(resumed: Boolean) = Unit
                        override fun onQueryAppResult(installed: Boolean) = Unit
                    })
                },
                onFailure = {
                    session.cleanup()
                    session.onBusyChanged(false)
                    onLaunchResult?.invoke(false)
                },
            ),
        )
    }

    internal fun connectAndStop(
        authToken: String,
        targetHostApp: RokidHostApp,
        packageName: String,
        onStopResult: ((Boolean) -> Unit)?,
    ) {
        connectAndRunCustomAppOperation(
            authToken = authToken,
            targetHostApp = targetHostApp,
            operation = CxrAppOperation(
                packageName = packageName,
                timeoutMillis = 30_000,
                timeoutMessage = session.appContext.getString(R.string.stop_timeout, packageName),
                bindMessage = session.appContext.getString(R.string.binding_service, targetHostApp.displayName),
                configureFailureMessage = session.appContext.getString(R.string.stop_config_failed, packageName),
                bindFailureMessage = session.appContext.getString(R.string.service_bind_failed, targetHostApp.displayName, targetHostApp.displayName),
                showConnectionStatus = true,
                onReady = { link ->
                    session.onStatus(session.appContext.getString(R.string.cxrl_ready_stopping, packageName))
                    link.appStop(object : IGlassAppCbk {
                        override fun onInstallAppResult(success: Boolean) = Unit
                        override fun onUnInstallAppResult(success: Boolean) = Unit
                        override fun onOpenAppResult(success: Boolean) = Unit
                        override fun onStopAppResult(success: Boolean) {
                            completeActiveOperation()
                            session.onStatus(if (success) session.appContext.getString(R.string.glasses_stop_success, packageName) else session.appContext.getString(R.string.glasses_stop_failed, packageName))
                            session.onBusyChanged(false)
                            onStopResult?.invoke(success)
                        }
                        override fun onGlassAppResume(resumed: Boolean) = Unit
                        override fun onQueryAppResult(installed: Boolean) = Unit
                    })
                },
                onFailure = {
                    session.cleanup()
                    session.onBusyChanged(false)
                    onStopResult?.invoke(false)
                },
            ),
        )
    }

    internal fun connectAndRunCustomAppOperation(
        authToken: String,
        targetHostApp: RokidHostApp,
        operation: CxrAppOperation,
    ) {
        // abortInFlightAi=false：这里拆旧会话是为「建立新会话」服务，发起方自己的请求（如 AI 慢速路径
        // 的 sendAiTextMessage）必须存活到 onReady；抢占代际会把它的代际顶掉导致入口校验失败。
        // 真正需要打断旧请求的场景由调用方（新请求入口）自行抢占代际，或被替换掉的 link 身份兜底。
        session.cleanup(abortInFlightAi = false)
        val link = CXRLink(session.appContext).also { newLink ->
            // 反射绕过 CXR-L SDK 的 cmd 黑名单（一次到位：连接建立时清理，
            // 避免过去在 sendAiTextViaLink 每条 AI 消息下行都重复反射一次的开销）
            bypassCmdBlacklist(newLink)
            newLink.setCXRLinkCbk(FullCXRLinkCallback(
                onConnected = { connected ->
                    session.mainHandler.post {
                        session.cxrlConnected = connected
                        // ★ 登记确认通道：本方法开头会调 session.cleanup()（其中 unbind 摘掉会话），
                        //   而 bind 只在构造器 init 里跑过一次 → 重连后登记表永久为空，
                        //   导致 liveSession() 返回 null（现象：capSupport=true 但 session=false，
                        //   图片下发被当成"眼镜未连接"丢弃）。故每次 connected=true 都重新登记。
                        if (connected) com.rokidlab.phone.ai.GlassToolConfirmChannel.bind(session)
                        if (operation.showConnectionStatus) session.onStatus(session.appContext.getString(R.string.cxrl_service_connected, connected.toString()))
                        if (connected) session.asrBridge.start() else session.asrBridge.stop()
                        session.notifyConnectionChanged()
                        maybeRunPendingOperation()
                    }
                },
                onBtConnected = { connected ->
                    session.mainHandler.post {
                        session.glassBtConnected = connected
                        if (operation.showConnectionStatus) session.onStatus(session.appContext.getString(R.string.bluetooth_connected_status, connected.toString()))
                        session.notifyConnectionChanged()
                        maybeRunPendingOperation()
                    }
                },
                // 眼镜 AI 助手场景开关（Ai_SceneStatus 上行）：下行文字链路据此保证
                // ASR_Result 不早于场景打开发送（冷启动首条丢文字竞态，详见 waitAiSceneOpen）。
                // setAiSceneOpen 内部自带同步，可在 binder 回调线程直接调用。
                onAiAssistStart = {
                    android.util.Log.i(TAG, "onGlassAiAssistStart: ai_assist scene opened")
                    session.setAiSceneOpen(true)
                },
                onAiAssistStop = {
                    android.util.Log.i(TAG, "onGlassAiAssistStop: ai_assist scene closed")
                    session.setAiSceneOpen(false)
                }
            ))
            session.cxrLink = newLink
            newLink
        }

        session.pendingOperation = operation
        session.cxrlConnected = false
        session.glassBtConnected = false
        session.operationStarted = false
        session.operationCompleted = false
        session.timeoutJob = session.appScope.launch {
            delay(operation.timeoutMillis)
            // 使用同步锁检查操作是否已完成，防止竞态条件
            synchronized(session.operationLock) {
                if (session.pendingOperation === operation && !session.operationCompleted) {
                    android.util.Log.e("CxrLInstall", "connectAndRun: TIMEOUT after ${operation.timeoutMillis}ms, session.cxrlConnected=${session.cxrlConnected}, session.glassBtConnected=${session.glassBtConnected}")
                    session.pendingOperation = null
                    session.operationStarted = false
                    session.onStatus(operation.timeoutMessage)
                    operation.onFailure()
                }
            }
        }

        val configured = link.configCXRSession(
            CxrDefs.CXRSession(CxrDefs.CXRSessionType.CUSTOMAPP, operation.packageName),
        )
        if (!configured) {
            android.util.Log.e("CxrLInstall", "connectAndRun: configCXRSession FAILED (package=${operation.packageName})")
            session.pendingOperation = null
            session.operationStarted = false
            session.operationCompleted = true
            session.onStatus(operation.configureFailureMessage)
            operation.onFailure()
            session.cleanup()
            return
        }

        operation.bindMessage?.let(session.onStatus)
        if (!bindRokidHostService(link, targetHostApp, authToken)) {
            android.util.Log.e("CxrLInstall", "connectAndRun: bindRokidHostService FAILED (session.hostApp=$targetHostApp)")
            session.pendingOperation = null
            session.operationStarted = false
            session.onStatus(operation.bindFailureMessage)
            operation.onBindFailure()
            session.cleanup()
        }
        android.util.Log.i("CxrLInstall", "connectAndRun: bindRokidHostService OK, waiting for connected+btConnected...")
        // 直接启动 AI 文字轮询（不依赖 onCXRLConnected：实测该回调在部分会话中不触发）
        session.asrBridge.start()
        // 连接建立后补发一次 AI 配置到眼镜端（setAiConfig 时可能尚未连接）
        session.aiConfig.pushAiConfigToGlass(session.getAiConfig())
    }

    internal fun maybeRunPendingOperation() {
        synchronized(session.operationLock) {
            val operation = session.pendingOperation ?: return
            if (session.operationStarted || !session.cxrlConnected || !session.glassBtConnected) return
            val link = session.cxrLink ?: return
            session.operationStarted = true
            session.operationCompleted = false
            // 注册统一指令监听（眼镜按键拍照问AI / WiFi 连接状态等），再执行具体操作
            session.registerGlobalCmdListener(link)
            operation.onReady(link)
        }
    }

    internal fun completeActiveOperation() {
        android.util.Log.i("CxrLInstall", "completeActiveOperation() called")
        synchronized(session.operationLock) {
            session.operationCompleted = true
            session.timeoutJob?.cancel()
            session.timeoutJob = null
            session.pendingOperation = null
            session.operationStarted = false
        }
    }

    internal fun glassAppCallback(
        onInstall: (Boolean) -> Unit = {},
        onUninstall: (Boolean) -> Unit = {},
        onQuery: (Boolean) -> Unit = {},
        onStart: (Boolean) -> Unit = {},
    ): IGlassAppCbk = object : IGlassAppCbk {
        override fun onInstallAppResult(success: Boolean) {
            android.util.Log.i("CxrLInstall", "glassAppCallback: onInstallAppResult(success=$success)")
            session.mainHandler.post { onInstall(success) }
        }

        override fun onUnInstallAppResult(success: Boolean) {
            session.mainHandler.post { onUninstall(success) }
        }

        override fun onOpenAppResult(success: Boolean) {
            session.mainHandler.post { onStart(success) }
        }
        override fun onStopAppResult(success: Boolean) = Unit
        override fun onGlassAppResume(resumed: Boolean) = Unit

        override fun onQueryAppResult(installed: Boolean) {
            session.mainHandler.post { onQuery(installed) }
        }
    }

    internal fun finishQueries() {
        val complete = session.onQueryComplete
        session.cleanup()
        session.queryQueue.clear()
        session.onQueryResult = null
        session.onQueryComplete = null
        session.onBusyChanged(false)
        complete?.invoke()
    }

    internal fun bindRokidHostService(link: CXRLink, targetHostApp: RokidHostApp, authToken: String): Boolean {
        val conn = findServiceConnection(link) ?: return false
        val ok = runCatching {
            val intent = Intent(MEDIA_SERVICE_ACTION)
                .setPackage(targetHostApp.packageName)
                .putExtra(AUTH_TOKEN_EXTRA, authToken)
                .putExtra(AUTH_PACKAGE_EXTRA, session.appContext.packageName)
            // 注意：applicationContext 绑定的连接不会随 Activity 销毁自动解绑，
            // 必须登记并在 session.cleanup() 统一 unbind，否则 ServiceConnection 泄漏
            session.appContext.bindService(intent, conn, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
        if (ok) boundConnections.add(conn)
        return ok
    }

    /**
     * 反射清空 CXR-L SDK 的 cmd 黑名单字段（混淆名 "d"，String[] 类型）。
     * 仅在 CXRLink 创建时调用一次；SDK 升级导致字段名变化时静默失败并记日志，
     * 不影响主链路（与旧实现每条消息重试的行为等价——字段名变了旧代码同样每条失败）。
     */
    internal fun bypassCmdBlacklist(link: CXRLink) {
        try {
            val field = link.javaClass.superclass.getDeclaredField("d")
            field.isAccessible = true
            field.set(link, arrayOf<String>())
            Log.i(TAG, "CXR-L cmd blacklist bypassed (cleared at link creation)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bypass CXR-L cmd blacklist", e)
        }
    }

    /** 统一解除所有已登记的服务绑定 */
    internal fun unbindAllHostServices() {
        if (boundConnections.isEmpty()) return
        // SDK 若已自行解绑，重复 unbind 会抛 IllegalArgumentException，逐个容错
        boundConnections.forEach { conn ->
            runCatching { session.appContext.unbindService(conn) }
                .onFailure { Log.w(TAG, "unbindService failed (may already be unbound): ${it.message}") }
        }
        boundConnections.clear()
    }

    internal fun findServiceConnection(link: CXRLink): ServiceConnection? {
        // 优先使用缓存的字段
        cachedServiceConnectionField?.let { field ->
            try {
                field.isAccessible = true
                return (field.get(link) as ServiceConnection?)
            } catch (e: Exception) {
                Log.w(TAG, "Cached ServiceConnection field access failed, re-scanning: ${e.message}")
                cachedServiceConnectionField = null
            }
        }
        // 缓存未命中，遍历查找
        var type: Class<*>? = link.javaClass
        while (type != null) {
            try {
                val field = type.declaredFields.firstOrNull { ServiceConnection::class.java.isAssignableFrom(it.type) }
                if (field != null) {
                    field.isAccessible = true
                    cachedServiceConnectionField = field
                    return field.get(link) as ServiceConnection
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to find ServiceConnection in ${type?.name}: ${e.message}")
            }
            type = type.superclass
        }
        Log.e(TAG, "CXR-L ServiceConnection field not found, CXR-L SDK version may be incompatible")
        return null
    }
}
