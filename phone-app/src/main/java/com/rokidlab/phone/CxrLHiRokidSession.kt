package com.rokidlab.phone

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.rokid.cxr.link.CXRLink
import com.rokid.cxr.link.callbacks.ICXRLinkCbk
import com.rokid.cxr.link.callbacks.IGlassAppCbk
import com.rokid.cxr.link.utils.CxrDefs
import com.rokid.sprite.aiapp.externalapp.auth.AuthResult
import com.rokid.sprite.aiapp.externalapp.auth.AuthorizationHelper
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.ArrayDeque

class CxrLHiRokidSession(
    private val activity: AppCompatActivity,
    private val onStatus: (String) -> Unit,
    private val onBusyChanged: (Boolean) -> Unit,
    private val onConnectionChanged: (CxrConnectionState) -> Unit,
    initialHostApp: RokidHostApp = RokidHostApp.DEFAULT,
) {
    companion object {
        const val AUTH_REQUEST_CODE = 4027

        private const val AUTH_ACTIVITY_CLASS = "com.rokid.sprite.aiapp.externalapp.auth.AuthorizationActivity"
        private const val AUTH_ACTION = "com.rokid.sprite.aiapp.externalapp.AUTHORIZATION"
        private const val MEDIA_SERVICE_ACTION = "com.rokid.sprite.aiapp.externalapp.MEDIA_STREAM_SERVICE"
        private const val AUTH_TOKEN_EXTRA = "auth_token"
        private const val AUTH_PACKAGE_EXTRA = "auth_package"
    }

    private var hostApp: RokidHostApp = initialHostApp
    private var token: String? = null
    private var cxrLink: CXRLink? = null
    private var pendingOperation: CxrAppOperation? = null
    private var queryQueue: ArrayDeque<String> = ArrayDeque()
    private var onQueryResult: ((String, Boolean) -> Unit)? = null
    private var onQueryComplete: (() -> Unit)? = null
    private var cxrlConnected = false
    private var glassBtConnected = false
    private var operationStarted = false
    private var timeoutJob: Job? = null

    fun hasAuthorization(): Boolean = !token.isNullOrBlank()
    
    fun getToken(): String? = token

    fun ensureGlassesOperationReady(): Boolean {
        return hasGlassesOperationPrerequisites(hostApp, requestAuthorizationIfMissing = true)
    }

    fun selectHostApp(nextHostApp: RokidHostApp) {
        if (hostApp == nextHostApp) return
        cleanup()
        token = null
        hostApp = nextHostApp
        notifyConnectionChanged()
    }

    fun isHostAppInstalled(targetHostApp: RokidHostApp = hostApp): Boolean {
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                activity.packageManager.getPackageInfo(targetHostApp.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                activity.packageManager.getPackageInfo(targetHostApp.packageName, 0)
            }
        }.isSuccess
    }

    fun requestAuthorization() {
        val targetHostApp = hostApp
        if (!isHostAppInstalled(targetHostApp)) {
            onStatus("请先安装 ${targetHostApp.displayName}。")
            return
        }

        runCatching {
            val intent = Intent().setComponent(ComponentName(targetHostApp.packageName, AUTH_ACTIVITY_CLASS))
            activity.startActivityForResult(intent, AUTH_REQUEST_CODE)
        }.recoverCatching {
            val fallback = Intent(AUTH_ACTION).setPackage(targetHostApp.packageName)
            activity.startActivityForResult(fallback, AUTH_REQUEST_CODE)
        }.onSuccess {
            onStatus("已在 ${targetHostApp.displayName} 中打开授权页面。")
        }.onFailure { error ->
            onStatus("打开 ${targetHostApp.displayName} 授权失败：${error.message ?: error.javaClass.simpleName}")
        }
    }

    fun handleAuthorizationResult(resultCode: Int, data: Intent?) {
        when (val result = AuthorizationHelper.parseAuthorizationResult(resultCode, data)) {
            is AuthResult.AuthSuccess -> {
                token = result.token
                onStatus("已获取 ${hostApp.displayName} 授权令牌。")
                notifyConnectionChanged()
            }

            is AuthResult.AuthCancel -> {
                token = null
                onStatus("${hostApp.displayName} 授权已取消。")
                notifyConnectionChanged()
            }

            is AuthResult.AuthFail -> {
                token = null
                onStatus("${hostApp.displayName} 授权失败。")
                notifyConnectionChanged()
            }
        }
    }

    fun installApk(apkFile: File, onInstallResult: ((Boolean) -> Unit)? = null) {
        val targetHostApp = hostApp
        if (!hasGlassesOperationPrerequisites(targetHostApp, requestAuthorizationIfMissing = true)) {
            onInstallResult?.invoke(false)
            return
        }
        val authToken = token.orEmpty()

        onBusyChanged(true)
        runCatching {
            val packageName = readPackageName(apkFile)
            onStatus("检测到包：$packageName")
            connectAndUpload(authToken, targetHostApp, packageName, apkFile, onInstallResult)
        }.onFailure { error ->
            onStatus("CXR-L 失败：${error.message ?: error.javaClass.simpleName}")
            onBusyChanged(false)
            onInstallResult?.invoke(false)
        }
    }

    fun launchApp(packageName: String, activityClass: String = ".MainActivity", onLaunchResult: ((Boolean) -> Unit)? = null) {
        val targetHostApp = hostApp
        if (!hasGlassesOperationPrerequisites(targetHostApp, requestAuthorizationIfMissing = true)) {
            onLaunchResult?.invoke(false)
            return
        }
        val authToken = token.orEmpty()

        onBusyChanged(true)
        connectAndLaunch(authToken, targetHostApp, packageName, activityClass, onLaunchResult)
    }

    fun stopApp(packageName: String, onStopResult: ((Boolean) -> Unit)? = null) {
        val targetHostApp = hostApp
        if (!hasGlassesOperationPrerequisites(targetHostApp, requestAuthorizationIfMissing = true)) {
            onStopResult?.invoke(false)
            return
        }
        val authToken = token.orEmpty()

        onBusyChanged(true)
        connectAndStop(authToken, targetHostApp, packageName, onStopResult)
    }

    fun uninstallApp(packageName: String, onUninstallResult: ((Boolean) -> Unit)? = null) {
        val targetHostApp = hostApp
        if (!hasGlassesOperationPrerequisites(targetHostApp, requestAuthorizationIfMissing = true)) {
            onUninstallResult?.invoke(false)
            return
        }
        val authToken = token.orEmpty()

        onBusyChanged(true)
        connectAndUninstall(authToken, targetHostApp, packageName, onUninstallResult)
    }

    fun queryInstalledApps(
        packageNames: List<String>,
        onResult: (String, Boolean) -> Unit,
        onComplete: () -> Unit,
    ) {
        val targetHostApp = hostApp
        if (packageNames.isEmpty()) {
            onComplete()
            return
        }
        if (!hasGlassesOperationPrerequisites(targetHostApp, requestAuthorizationIfMissing = true)) {
            onComplete()
            return
        }
        val authToken = token.orEmpty()

        cleanup()
        queryQueue = ArrayDeque(packageNames.distinct())
        onQueryResult = onResult
        onQueryComplete = onComplete
        onBusyChanged(true)
        queryNext(authToken, targetHostApp)
    }

    fun cleanup() {
        timeoutJob?.cancel()
        timeoutJob = null
        runCatching { cxrLink?.disconnect() }
        cxrLink = null
        pendingOperation = null
        cxrlConnected = false
        glassBtConnected = false
        operationStarted = false
        notifyConnectionChanged()
    }

    private fun connectAndUpload(
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
                timeoutMessage = "等待 ${targetHostApp.displayName} 安装结果超时。",
                bindMessage = "正在绑定到 ${targetHostApp.displayName} 服务...",
                configureFailureMessage = "配置 CXR-L CUSTOMAPP 会话失败。",
                bindFailureMessage = "${targetHostApp.displayName} 服务绑定失败。请先打开 ${targetHostApp.displayName}，然后重试。",
                showConnectionStatus = true,
                onReady = { link ->
                    onStatus("CXR-L 就绪。正在上传并安装到眼镜...")
                    link.appUploadAndInstall(apkFile.absolutePath, glassAppCallback(
                        onInstall = { success ->
                            completeActiveOperation()
                            onStatus(if (success) "眼镜安装成功。" else "眼镜安装失败。")
                            onBusyChanged(false)
                            onInstallResult?.invoke(success)
                        },
                    ))
                },
                onFailure = {
                    cleanup()
                    onBusyChanged(false)
                    onInstallResult?.invoke(false)
                },
            ),
        )
    }

    private fun queryNext(authToken: String, targetHostApp: RokidHostApp) {
        val packageName = queryQueue.pollFirst()
        if (packageName == null) {
            finishQueries()
            return
        }
        connectAndQuery(authToken, targetHostApp, packageName)
    }

    private fun connectAndQuery(authToken: String, targetHostApp: RokidHostApp, packageName: String) {
        connectAndRunCustomAppOperation(
            authToken = authToken,
            targetHostApp = targetHostApp,
            operation = CxrAppOperation(
                packageName = packageName,
                timeoutMillis = 30_000,
                timeoutMessage = "查询 $packageName 超时。",
                configureFailureMessage = "配置 $packageName 的查询失败。",
                bindFailureMessage = "${targetHostApp.displayName} 服务绑定失败。请先打开 ${targetHostApp.displayName}，然后重试。",
                onReady = { link ->
                    link.appIsInstalled(glassAppCallback(
                        onQuery = { installed ->
                            completeActiveOperation()
                            onQueryResult?.invoke(packageName, installed)
                            queryNext(authToken, targetHostApp)
                        },
                    ))
                },
                onFailure = {
                    onQueryResult?.invoke(packageName, false)
                    queryNext(authToken, targetHostApp)
                },
                onBindFailure = {
                    finishQueries()
                },
            ),
        )
    }

    private fun connectAndUninstall(
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
                timeoutMessage = "从眼镜卸载 $packageName 超时。",
                bindMessage = "正在绑定到 ${targetHostApp.displayName} 服务...",
                configureFailureMessage = "配置 $packageName 的卸载失败。",
                bindFailureMessage = "${targetHostApp.displayName} 服务绑定失败。请先打开 ${targetHostApp.displayName}，然后重试。",
                showConnectionStatus = true,
                onReady = { link ->
                    onStatus("CXR-L 就绪。正在从眼镜卸载 $packageName...")
                    link.appUninstall(glassAppCallback(
                        onUninstall = { success ->
                            completeActiveOperation()
                            onStatus(if (success) "眼镜卸载成功。" else "眼镜卸载失败。")
                            onBusyChanged(false)
                            onUninstallResult?.invoke(success)
                        },
                    ))
                },
                onFailure = {
                    cleanup()
                    onBusyChanged(false)
                    onUninstallResult?.invoke(false)
                },
            ),
        )
    }

    private fun connectAndLaunch(
        authToken: String,
        targetHostApp: RokidHostApp,
        packageName: String,
        activityClass: String,
        onLaunchResult: ((Boolean) -> Unit)?,
    ) {
        connectAndRunCustomAppOperation(
            authToken = authToken,
            targetHostApp = targetHostApp,
            operation = CxrAppOperation(
                packageName = packageName,
                timeoutMillis = 30_000,
                timeoutMessage = "启动 $packageName 超时。",
                bindMessage = "正在绑定到 ${targetHostApp.displayName} 服务...",
                configureFailureMessage = "配置 $packageName 的启动失败。",
                bindFailureMessage = "${targetHostApp.displayName} 服务绑定失败。请先打开 ${targetHostApp.displayName}，然后重试。",
                showConnectionStatus = true,
                onReady = { link ->
                    onStatus("CXR-L 就绪。正在启动 $packageName...")
                    // 文档要求 appStart 使用 "${packageName}${activityClassName}" 格式
                    val entryUri = "$packageName$activityClass"
                    link.appStart(entryUri, object : IGlassAppCbk {
                        override fun onInstallAppResult(success: Boolean) = Unit
                        override fun onUnInstallAppResult(success: Boolean) = Unit
                        override fun onOpenAppResult(success: Boolean) {
                            completeActiveOperation()
                            onStatus(if (success) "已在眼镜启动 $packageName。" else "启动 $packageName 失败。")
                            onBusyChanged(false)
                            onLaunchResult?.invoke(success)
                        }
                        override fun onStopAppResult(success: Boolean) = Unit
                        override fun onGlassAppResume(resumed: Boolean) = Unit
                        override fun onQueryAppResult(installed: Boolean) = Unit
                    })
                },
                onFailure = {
                    cleanup()
                    onBusyChanged(false)
                    onLaunchResult?.invoke(false)
                },
            ),
        )
    }

    private fun connectAndStop(
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
                timeoutMessage = "停止 $packageName 超时。",
                bindMessage = "正在绑定到 ${targetHostApp.displayName} 服务...",
                configureFailureMessage = "配置 $packageName 的停止失败。",
                bindFailureMessage = "${targetHostApp.displayName} 服务绑定失败。请先打开 ${targetHostApp.displayName}，然后重试。",
                showConnectionStatus = true,
                onReady = { link ->
                    onStatus("CXR-L 就绪。正在停止 $packageName...")
                    link.appStop(object : IGlassAppCbk {
                        override fun onInstallAppResult(success: Boolean) = Unit
                        override fun onUnInstallAppResult(success: Boolean) = Unit
                        override fun onOpenAppResult(success: Boolean) = Unit
                        override fun onStopAppResult(success: Boolean) {
                            completeActiveOperation()
                            onStatus(if (success) "已停止 $packageName。" else "停止 $packageName 失败。")
                            onBusyChanged(false)
                            onStopResult?.invoke(success)
                        }
                        override fun onGlassAppResume(resumed: Boolean) = Unit
                        override fun onQueryAppResult(installed: Boolean) = Unit
                    })
                },
                onFailure = {
                    cleanup()
                    onBusyChanged(false)
                    onStopResult?.invoke(false)
                },
            ),
        )
    }

    private fun connectAndRunCustomAppOperation(
        authToken: String,
        targetHostApp: RokidHostApp,
        operation: CxrAppOperation,
    ) {
        cleanup()
        val link = CXRLink(activity.applicationContext).also { newLink ->
            newLink.setCXRLinkCbk(FullCXRLinkCbk(
                onConnected = { connected ->
                    activity.runOnUiThread {
                        cxrlConnected = connected
                        if (operation.showConnectionStatus) onStatus("CXR-L 服务已连接：$connected")
                        notifyConnectionChanged()
                        maybeRunPendingOperation()
                    }
                },
                onBtConnected = { connected ->
                    activity.runOnUiThread {
                        glassBtConnected = connected
                        if (operation.showConnectionStatus) onStatus("眼镜蓝牙已连接：$connected")
                        notifyConnectionChanged()
                        maybeRunPendingOperation()
                    }
                }
            ))
            cxrLink = newLink
            newLink
        }

        pendingOperation = operation
        cxrlConnected = false
        glassBtConnected = false
        operationStarted = false
        timeoutJob = activity.lifecycleScope.launch {
            delay(operation.timeoutMillis)
            if (pendingOperation === operation) {
                pendingOperation = null
                operationStarted = false
                onStatus(operation.timeoutMessage)
                operation.onFailure()
            }
        }

        val configured = link.configCXRSession(
            CxrDefs.CXRSession(CxrDefs.CXRSessionType.CUSTOMAPP, operation.packageName),
        )
        if (!configured) {
            pendingOperation = null
            operationStarted = false
            onStatus(operation.configureFailureMessage)
            operation.onFailure()
            return
        }

        operation.bindMessage?.let(onStatus)
        if (!bindRokidHostService(link, targetHostApp, authToken)) {
            pendingOperation = null
            operationStarted = false
            onStatus(operation.bindFailureMessage)
            operation.onBindFailure()
        }
    }

    private fun maybeRunPendingOperation() {
        val operation = pendingOperation ?: return
        if (operationStarted || !cxrlConnected || !glassBtConnected) return
        val link = cxrLink ?: return
        operationStarted = true
        operation.onReady(link)
    }

    private fun completeActiveOperation() {
        timeoutJob?.cancel()
        timeoutJob = null
        pendingOperation = null
        operationStarted = false
    }

    private fun glassAppCallback(
        onInstall: (Boolean) -> Unit = {},
        onUninstall: (Boolean) -> Unit = {},
        onQuery: (Boolean) -> Unit = {},
        onStart: (Boolean) -> Unit = {},
    ): IGlassAppCbk = object : IGlassAppCbk {
        override fun onInstallAppResult(success: Boolean) {
            activity.runOnUiThread { onInstall(success) }
        }

        override fun onUnInstallAppResult(success: Boolean) {
            activity.runOnUiThread { onUninstall(success) }
        }

        override fun onOpenAppResult(success: Boolean) {
            activity.runOnUiThread { onStart(success) }
        }
        override fun onStopAppResult(success: Boolean) = Unit
        override fun onGlassAppResume(resumed: Boolean) = Unit

        override fun onQueryAppResult(installed: Boolean) {
            activity.runOnUiThread { onQuery(installed) }
        }
    }

    private data class CxrAppOperation(
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

    private fun finishQueries() {
        val complete = onQueryComplete
        cleanup()
        queryQueue.clear()
        onQueryResult = null
        onQueryComplete = null
        onBusyChanged(false)
        complete?.invoke()
    }

    private fun bindRokidHostService(link: CXRLink, targetHostApp: RokidHostApp, authToken: String): Boolean {
        return runCatching {
            val intent = Intent(MEDIA_SERVICE_ACTION)
                .setPackage(targetHostApp.packageName)
                .putExtra(AUTH_TOKEN_EXTRA, authToken)
                .putExtra(AUTH_PACKAGE_EXTRA, activity.packageName)
            activity.applicationContext.bindService(intent, findServiceConnection(link), Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
    }

    private fun findServiceConnection(link: CXRLink): ServiceConnection {
        var type: Class<*>? = link.javaClass
        while (type != null) {
            val field = type.declaredFields.firstOrNull { ServiceConnection::class.java.isAssignableFrom(it.type) }
            if (field != null) {
                field.isAccessible = true
                return field.get(link) as ServiceConnection
            }
            type = type.superclass
        }
        error("CXR-L ServiceConnection field not found")
    }

    private fun isWifiEnabled(): Boolean {
        val wifiManager = activity.applicationContext.getSystemService(WifiManager::class.java)
        return wifiManager?.isWifiEnabled == true
    }

    private fun hasGlassesOperationPrerequisites(
        targetHostApp: RokidHostApp,
        requestAuthorizationIfMissing: Boolean,
    ): Boolean {
        if (!isHostAppInstalled(targetHostApp)) {
            onStatus("请先在手机上安装 ${targetHostApp.displayName}。")
            return false
        }
        if (!isWifiEnabled()) {
            onStatus("请先打开手机 Wi-Fi。${targetHostApp.displayName} 需要通过它连接眼镜热点。")
            return false
        }
        if (token.isNullOrBlank()) {
            onStatus("请先在 ${targetHostApp.displayName} 中点击授权。")
            if (requestAuthorizationIfMissing) requestAuthorization()
            return false
        }
        return true
    }

    private fun readPackageName(apkFile: File): String {
        @Suppress("DEPRECATION")
        val info = activity.packageManager.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.GET_ACTIVITIES)
        return info?.packageName?.takeIf { it.isNotBlank() } ?: error("Cannot read APK package name")
    }

    private fun notifyConnectionChanged() {
        onConnectionChanged(
            CxrConnectionState(
                authorized = hasAuthorization(),
                cxrlConnected = cxrlConnected,
                glassBtConnected = glassBtConnected,
            ),
        )
    }
}

data class CxrConnectionState(
    val authorized: Boolean = false,
    val cxrlConnected: Boolean = false,
    val glassBtConnected: Boolean = false,
) {
    val connected: Boolean
        get() = cxrlConnected && glassBtConnected

    val connecting: Boolean
        get() = authorized && (cxrlConnected || glassBtConnected) && !connected
}
