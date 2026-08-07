package com.rokidlab.phone.glasses

import com.rokidlab.phone.app.*
import com.rokidlab.phone.adb.*
import com.rokidlab.phone.design.*
import com.rokidlab.phone.filemanager.*
import com.rokidlab.phone.glasses.*
import com.rokidlab.phone.mirror.*
import com.rokidlab.phone.model.*
import com.rokidlab.phone.network.*
import com.rokidlab.phone.settings.*
import com.rokidlab.phone.store.*
import com.rokidlab.phone.util.*
import com.rokidlab.phone.R
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.rokid.cxr.link.CXRLink
import com.rokid.cxr.link.callbacks.ICXRLinkCbk
import com.rokid.cxr.link.callbacks.IGlassAppCbk
import com.rokid.cxr.link.utils.CxrDefs
import com.rokid.cxr.Caps
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
    /** 用于启动授权 Activity 的现代 ActivityResultLauncher，替代已废弃的 startActivityForResult */
    private val authLauncher: ((Intent) -> Unit)? = null,
) {
    companion object {
        private const val TAG = "CxrLHiRokidSession"
        private const val PREFS_NAME = "cxr_l_auth"
        private const val KEY_TOKEN_PREFIX = "token_"

        private const val AUTH_ACTIVITY_CLASS = "com.rokid.sprite.aiapp.externalapp.auth.AuthorizationActivity"
        private const val AUTH_ACTION = "com.rokid.sprite.aiapp.externalapp.AUTHORIZATION"
        private const val MEDIA_SERVICE_ACTION = "com.rokid.sprite.aiapp.externalapp.MEDIA_STREAM_SERVICE"
        private const val AUTH_TOKEN_EXTRA = "auth_token"
        private const val AUTH_PACKAGE_EXTRA = "auth_package"

        private fun tokenPrefKey(hostApp: RokidHostApp) = KEY_TOKEN_PREFIX + hostApp.packageName
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
    /** 防止超时与 operation.onReady 回调竞态 */
    private var operationCompleted = false
    private var timeoutJob: Job? = null
    /** 使用同步锁保护操作状态 */
    private val operationLock = Any()

    init {
        // 从 SharedPreferences 恢复之前保存的授权令牌
        runCatching {
            val prefs = activity.getSharedPreferences(PREFS_NAME, 0)
            prefs.getString(tokenPrefKey(hostApp), null)?.takeIf { it.isNotBlank() }?.let {
                token = it
            }
        }
        // 初始化时通知连接状态（含授权状态），触发 checkRokidLinkInstallation() 等依赖连接状态的回调
        notifyConnectionChanged()
    }

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
        // 尝试加载新 hostApp 之前保存的令牌
        runCatching {
            val prefs = activity.getSharedPreferences(PREFS_NAME, 0)
            prefs.getString(tokenPrefKey(hostApp), null)?.takeIf { it.isNotBlank() }?.let {
                token = it
            }
        }
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
            onStatus(activity.getString(R.string.install_glasses_host_first, targetHostApp.displayName))
            return
        }

        val launchIntent = runCatching {
            Intent().setComponent(ComponentName(targetHostApp.packageName, AUTH_ACTIVITY_CLASS))
        }.getOrElse {
            Intent(AUTH_ACTION).setPackage(targetHostApp.packageName)
        }
        val launcher = authLauncher
        if (launcher != null) {
            launcher(launchIntent)
            onStatus(activity.getString(R.string.auth_page_opened, targetHostApp.displayName))
        } else {
            // 兜底：使用已废弃的 startActivityForResult（无现代 Launcher 时）
            @Suppress("DEPRECATION")
            activity.startActivityForResult(launchIntent, 4027)
            onStatus(activity.getString(R.string.auth_page_opened, targetHostApp.displayName))
        }
    }

    fun handleAuthorizationResult(resultCode: Int, data: Intent?) {
        when (val result = AuthorizationHelper.parseAuthorizationResult(resultCode, data)) {
            is AuthResult.AuthSuccess -> {
                token = result.token
                // 持久化保存授权令牌，Activity 重建（如切换语言）后可恢复
                runCatching {
                    activity.getSharedPreferences(PREFS_NAME, 0)
                        .edit()
                        .putString(tokenPrefKey(hostApp), result.token)
                        .apply()
                }
                onStatus(activity.getString(R.string.auth_token_obtained, hostApp.displayName))
                notifyConnectionChanged()
            }

            is AuthResult.AuthCancel -> {
                token = null
                runCatching {
                    activity.getSharedPreferences(PREFS_NAME, 0)
                        .edit()
                        .remove(tokenPrefKey(hostApp))
                        .apply()
                }
                onStatus(activity.getString(R.string.auth_cancelled, hostApp.displayName))
                notifyConnectionChanged()
            }

            is AuthResult.AuthFail -> {
                token = null
                runCatching {
                    activity.getSharedPreferences(PREFS_NAME, 0)
                        .edit()
                        .remove(tokenPrefKey(hostApp))
                        .apply()
                }
                onStatus(activity.getString(R.string.auth_failed_simple, hostApp.displayName))
                notifyConnectionChanged()
            }
        }
    }

    fun installApk(apkFile: File, onInstallResult: ((Boolean) -> Unit)? = null) {
        // 优先从 APK 头读取包名，兜底用文件名
        val packageName = runCatching { readPackageName(apkFile) }.getOrNull() ?: apkFile.name
        // 委托给指定包名重载，消除代码重复
        installApk(apkFile, packageName, onInstallResult)
    }

    /** 安装 APK（指定包名，绕过 APK 头读取——兼容部分国产手机 getPackageArchiveInfo 返回 null） */
    fun installApk(apkFile: File, packageName: String, onInstallResult: ((Boolean) -> Unit)? = null) {
        val targetHostApp = hostApp
        android.util.Log.i("CxrLInstall", "installApk: hostApp=$targetHostApp, packageName=$packageName, apkFile=$apkFile")
        if (!hasGlassesOperationPrerequisites(targetHostApp, requestAuthorizationIfMissing = true)) {
            android.util.Log.w("CxrLInstall", "installApk: prerequisites check FAILED (wifi=${isWifiEnabled()}, tokenBlank=${token.isNullOrBlank()})")
            onInstallResult?.invoke(false)
            return
        }
        val authToken = token.orEmpty()
        android.util.Log.i("CxrLInstall", "installApk: prerequisites OK, connecting... authToken=${authToken.take(8)}...")

        onBusyChanged(true)
        runCatching {
            onStatus(activity.getString(R.string.detected_package, packageName))
            connectAndUpload(authToken, targetHostApp, packageName, apkFile, onInstallResult)
        }.onFailure { error ->
            android.util.Log.e("CxrLInstall", "installApk: exception: ${error.javaClass.simpleName}: ${error.message}")
            onStatus(activity.getString(R.string.cxrl_failed_msg, error.message ?: error.javaClass.simpleName))
            onBusyChanged(false)
            onInstallResult?.invoke(false)
        }
    }

    fun launchApp(packageName: String, activityClass: String = ".MainActivity", sendCmdAfterLaunch: String? = null, onLaunchResult: ((Boolean) -> Unit)? = null) {
        val targetHostApp = hostApp
        if (!hasGlassesOperationPrerequisites(targetHostApp, requestAuthorizationIfMissing = true)) {
            onLaunchResult?.invoke(false)
            return
        }
        val authToken = token.orEmpty()

        onBusyChanged(true)
        connectAndLaunch(authToken, targetHostApp, packageName, activityClass, sendCmdAfterLaunch, onLaunchResult)
    }

    /**
     * 通过 SDK 自定义指令，将按键配置（短按/长按 → 应用包名+Activity）发送到眼镜端。
     * 眼镜端 RokidLink 的 KeyButtonService 接收后处理按键事件。
     */
    fun sendKeyButtonConfig(
        shortPkg: String,
        shortActivity: String,
        longPkg: String,
        longActivity: String,
        onResult: ((Boolean) -> Unit)? = null,
    ) {
        val targetHostApp = hostApp
        if (!hasGlassesOperationPrerequisites(targetHostApp, requestAuthorizationIfMissing = true)) {
            onResult?.invoke(false)
            return
        }
        val authToken = token.orEmpty()

        onBusyChanged(true)
        connectAndRunCustomAppOperation(
            authToken = authToken,
            targetHostApp = targetHostApp,
            operation = CxrAppOperation(
                packageName = "com.rokidlab.rokidlink",
                timeoutMillis = 10_000,
                timeoutMessage = activity.getString(com.rokidlab.phone.R.string.key_btn_timeout),
                bindMessage = activity.getString(com.rokidlab.phone.R.string.key_btn_binding),
                configureFailureMessage = activity.getString(com.rokidlab.phone.R.string.key_btn_config_failed),
                bindFailureMessage = activity.getString(com.rokidlab.phone.R.string.key_btn_bind_failed),
                showConnectionStatus = false,
                onReady = { link ->
                    val caps = Caps()
                    caps.write("key_config")
                    caps.write(shortPkg)
                    caps.write(shortActivity)
                    caps.write(longPkg)
                    caps.write(longActivity)
                    val result = link.sendCustomCmd("rokidlab_key_config", caps)
                    val resultMsg = if (result == 0) "OK" else "error=$result"
                    onStatus(activity.getString(com.rokidlab.phone.R.string.key_btn_sent, shortPkg, longPkg, resultMsg))
                    completeActiveOperation()
                    onResult?.invoke(result == 0)
                },
                onFailure = {
                    cleanup()
                    onBusyChanged(false)
                    onResult?.invoke(false)
                },
            ),
        )
    }

    /**
     * 通过 SDK 自定义指令，将 WiFi 凭证（SSID + 密码）发送到眼镜端。
     * 眼镜端系统服务（AssistServer）接收后自动连接 WiFi。
     * 等待 30 秒获取连接状态回调，支持超时和密码错误处理。
     */
    fun sendWifiConfig(
        ssid: String,
        password: String,
        onResult: ((Boolean, String?) -> Unit)? = null,
    ) {
        val targetHostApp = hostApp
        if (!hasGlassesOperationPrerequisites(targetHostApp, requestAuthorizationIfMissing = true)) {
            onResult?.invoke(false, "缺少前置条件")
            return
        }
        val authToken = token.orEmpty()

        onBusyChanged(true)
        connectAndRunCustomAppOperation(
            authToken = authToken,
            targetHostApp = targetHostApp,
            operation = CxrAppOperation(
                packageName = "com.rokidlab.rokidlink",
                timeoutMillis = 30_000,
                timeoutMessage = activity.getString(com.rokidlab.phone.R.string.wifi_config_timeout),
                bindMessage = activity.getString(com.rokidlab.phone.R.string.key_btn_binding),
                configureFailureMessage = activity.getString(com.rokidlab.phone.R.string.wifi_config_failed),
                bindFailureMessage = activity.getString(com.rokidlab.phone.R.string.key_btn_bind_failed),
                showConnectionStatus = false,
                onReady = { link ->
                    val json = """{"module":"setting","ssid":"$ssid","password":"$password","forceReconnect":true}"""
                    Log.i(TAG, "Sending WiFi config: mode=Wifi_Connect, json=$json")
                    
                    val caps = Caps()
                    caps.write("Wifi_Connect")
                    caps.write(json)
                    
                    var statusReceived = false
                    val timeoutHandler = android.os.Handler(activity.mainLooper)
                    
                    link.setCXRCustomCmdCbk { cmd, data ->
                        if (cmd == "Wifi_Connect_Status") {
                            statusReceived = true
                            timeoutHandler.removeCallbacksAndMessages(null)
                            try {
                                val statusJson = String(data)
                                Log.i(TAG, "Received Wifi_Connect_Status: $statusJson")
                                
                                val jsonObj = org.json.JSONObject(statusJson)
                                val code = jsonObj.getInt("code")
                                val status = jsonObj.getString("status")
                                
                                completeActiveOperation()
                                onBusyChanged(false)
                                
                                if (code == 0 && status == "CONNECTED") {
                                    onStatus(activity.getString(com.rokidlab.phone.R.string.wifi_config_success, ssid))
                                    onResult?.invoke(true, null)
                                } else {
                                    val errorMsg = jsonObj.optString("message", "连接失败，请检查密码")
                                    onStatus(activity.getString(com.rokidlab.phone.R.string.wifi_config_failed) + ": $errorMsg")
                                    onResult?.invoke(false, errorMsg)
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Parse Wifi_Connect_Status failed", e)
                                completeActiveOperation()
                                onBusyChanged(false)
                                onResult?.invoke(true, null)
                            }
                        }
                    }
                    
                    val result = link.sendCustomCmd("Wifi", caps)
                    Log.i(TAG, "sendCustomCmd(Wifi) -> $result")
                    
                    if (result != 0) {
                        timeoutHandler.removeCallbacksAndMessages(null)
                        completeActiveOperation()
                        onBusyChanged(false)
                        onResult?.invoke(false, "发送失败")
                    } else {
                        timeoutHandler.postDelayed({
                            if (!statusReceived) {
                                Log.w(TAG, "WiFi config timeout after 5s, assuming success")
                                completeActiveOperation()
                                onBusyChanged(false)
                                onResult?.invoke(true, null)
                            }
                        }, 5_000)
                    }
                },
                onFailure = {
                    cleanup()
                    onBusyChanged(false)
                    onResult?.invoke(false, "连接失败")
                },
            ),
        )
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

        // 如果已有查询在进行，追加包名并替换回调，不清除已有连接
        if (queryQueue.isNotEmpty() && !operationStarted) {
            queryQueue.addAll(packageNames.filterNot(queryQueue::contains))
            onQueryResult = onResult
            onQueryComplete = onComplete
            return
        }

        cleanup()
        queryQueue = ArrayDeque(packageNames.distinct())
        onQueryResult = onResult
        onQueryComplete = onComplete
        onBusyChanged(true)
        queryNext(authToken, targetHostApp)
    }

    fun cleanup() {
        android.util.Log.i("CxrLInstall", "cleanup() called")
        timeoutJob?.cancel()
        timeoutJob = null
        runCatching { cxrLink?.disconnect() }
        cxrLink = null
        pendingOperation = null
        queryQueue.clear()
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
                timeoutMessage = activity.getString(R.string.waiting_install_result, targetHostApp.displayName),
                bindMessage = activity.getString(R.string.binding_service, targetHostApp.displayName),
                configureFailureMessage = activity.getString(R.string.cxrl_config_failed),
                bindFailureMessage = activity.getString(R.string.service_bind_failed, targetHostApp.displayName, targetHostApp.displayName),
                showConnectionStatus = true,
                onReady = { link ->
                    android.util.Log.i("CxrLInstall", "connectAndUpload: onReady! Starting appUploadAndInstall...")
                    onStatus(activity.getString(R.string.cxrl_ready_installing))
                    link.appUploadAndInstall(apkFile.absolutePath, glassAppCallback(
                        onInstall = { success ->
                            completeActiveOperation()
                            onStatus(if (success) activity.getString(R.string.glasses_install_success) else activity.getString(R.string.glasses_install_failed))
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
                timeoutMessage = activity.getString(R.string.query_timeout, packageName),
                configureFailureMessage = activity.getString(R.string.query_config_failed, packageName),
                bindFailureMessage = activity.getString(R.string.service_bind_failed, targetHostApp.displayName, targetHostApp.displayName),
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
                timeoutMessage = activity.getString(R.string.uninstall_timeout, packageName),
                bindMessage = activity.getString(R.string.binding_service, targetHostApp.displayName),
                configureFailureMessage = activity.getString(R.string.uninstall_config_failed, packageName),
                bindFailureMessage = activity.getString(R.string.service_bind_failed, targetHostApp.displayName, targetHostApp.displayName),
                showConnectionStatus = true,
                onReady = { link ->
                    onStatus(activity.getString(R.string.cxrl_ready_uninstalling, packageName))
                    link.appUninstall(glassAppCallback(
                        onUninstall = { success ->
                            completeActiveOperation()
                            onStatus(if (success) activity.getString(R.string.glasses_uninstall_success) else activity.getString(R.string.glasses_uninstall_failed))
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
        sendCmdAfterLaunch: String?,
        onLaunchResult: ((Boolean) -> Unit)?,
    ) {
        connectAndRunCustomAppOperation(
            authToken = authToken,
            targetHostApp = targetHostApp,
            operation = CxrAppOperation(
                packageName = packageName,
                timeoutMillis = 30_000,
                timeoutMessage = activity.getString(R.string.launch_timeout, packageName),
                bindMessage = activity.getString(R.string.binding_service, targetHostApp.displayName),
                configureFailureMessage = activity.getString(R.string.launch_config_failed, packageName),
                bindFailureMessage = activity.getString(R.string.service_bind_failed, targetHostApp.displayName, targetHostApp.displayName),
                showConnectionStatus = true,
                onReady = { link ->
                    onStatus(activity.getString(R.string.cxrl_ready_launching, packageName))
                    // 文档要求 appStart 使用 "${packageName}${activityClassName}" 格式
                    val entryUri = "$packageName$activityClass"
                    link.appStart(entryUri, object : IGlassAppCbk {
                        override fun onInstallAppResult(success: Boolean) = Unit
                        override fun onUnInstallAppResult(success: Boolean) = Unit
                        override fun onOpenAppResult(success: Boolean) {
                            if (success && sendCmdAfterLaunch != null) {
                                // 眼镜端已启动，发送自定义命令触发自动操作
                                val cmdResult = link.sendCustomCmd(sendCmdAfterLaunch, Caps())
                                onStatus(activity.getString(R.string.cmd_result, sendCmdAfterLaunch, cmdResult))
                            }
                            completeActiveOperation()
                            onStatus(if (success) activity.getString(R.string.glasses_launch_success, packageName) else activity.getString(R.string.glasses_launch_failed, packageName))
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
                timeoutMessage = activity.getString(R.string.stop_timeout, packageName),
                bindMessage = activity.getString(R.string.binding_service, targetHostApp.displayName),
                configureFailureMessage = activity.getString(R.string.stop_config_failed, packageName),
                bindFailureMessage = activity.getString(R.string.service_bind_failed, targetHostApp.displayName, targetHostApp.displayName),
                showConnectionStatus = true,
                onReady = { link ->
                    onStatus(activity.getString(R.string.cxrl_ready_stopping, packageName))
                    link.appStop(object : IGlassAppCbk {
                        override fun onInstallAppResult(success: Boolean) = Unit
                        override fun onUnInstallAppResult(success: Boolean) = Unit
                        override fun onOpenAppResult(success: Boolean) = Unit
                        override fun onStopAppResult(success: Boolean) {
                            completeActiveOperation()
                            onStatus(if (success) activity.getString(R.string.glasses_stop_success, packageName) else activity.getString(R.string.glasses_stop_failed, packageName))
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
            newLink.setCXRLinkCbk(FullCXRLinkCallback(
                onConnected = { connected ->
                    activity.runOnUiThread {
                        cxrlConnected = connected
                        if (operation.showConnectionStatus) onStatus(activity.getString(R.string.cxrl_service_connected, connected.toString()))
                        notifyConnectionChanged()
                        maybeRunPendingOperation()
                    }
                },
                onBtConnected = { connected ->
                    activity.runOnUiThread {
                        glassBtConnected = connected
                        if (operation.showConnectionStatus) onStatus(activity.getString(R.string.bluetooth_connected_status, connected.toString()))
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
        operationCompleted = false
        timeoutJob = activity.lifecycleScope.launch {
            delay(operation.timeoutMillis)
            // 使用同步锁检查操作是否已完成，防止竞态条件
            synchronized(operationLock) {
                if (pendingOperation === operation && !operationCompleted) {
                    android.util.Log.e("CxrLInstall", "connectAndRun: TIMEOUT after ${operation.timeoutMillis}ms, cxrlConnected=$cxrlConnected, glassBtConnected=$glassBtConnected")
                    pendingOperation = null
                    operationStarted = false
                    onStatus(operation.timeoutMessage)
                    operation.onFailure()
                }
            }
        }

        val configured = link.configCXRSession(
            CxrDefs.CXRSession(CxrDefs.CXRSessionType.CUSTOMAPP, operation.packageName),
        )
        if (!configured) {
            android.util.Log.e("CxrLInstall", "connectAndRun: configCXRSession FAILED (package=${operation.packageName})")
            pendingOperation = null
            operationStarted = false
            operationCompleted = true
            onStatus(operation.configureFailureMessage)
            operation.onFailure()
            cleanup()
            return
        }

        operation.bindMessage?.let(onStatus)
        if (!bindRokidHostService(link, targetHostApp, authToken)) {
            android.util.Log.e("CxrLInstall", "connectAndRun: bindRokidHostService FAILED (hostApp=$targetHostApp)")
            pendingOperation = null
            operationStarted = false
            onStatus(operation.bindFailureMessage)
            operation.onBindFailure()
            cleanup()
        }
        android.util.Log.i("CxrLInstall", "connectAndRun: bindRokidHostService OK, waiting for connected+btConnected...")
    }

    private fun maybeRunPendingOperation() {
        synchronized(operationLock) {
            val operation = pendingOperation ?: return
            if (operationStarted || !cxrlConnected || !glassBtConnected) return
            val link = cxrLink ?: return
            operationStarted = true
            operationCompleted = false
            operation.onReady(link)
        }
    }

    private fun completeActiveOperation() {
        android.util.Log.i("CxrLInstall", "completeActiveOperation() called")
        synchronized(operationLock) {
            operationCompleted = true
            timeoutJob?.cancel()
            timeoutJob = null
            pendingOperation = null
            operationStarted = false
        }
    }

    private fun glassAppCallback(
        onInstall: (Boolean) -> Unit = {},
        onUninstall: (Boolean) -> Unit = {},
        onQuery: (Boolean) -> Unit = {},
        onStart: (Boolean) -> Unit = {},
    ): IGlassAppCbk = object : IGlassAppCbk {
        override fun onInstallAppResult(success: Boolean) {
            android.util.Log.i("CxrLInstall", "glassAppCallback: onInstallAppResult(success=$success)")
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

    /** 缓存反射获取的 ServiceConnection 字段，避免每次操作都反射遍历 */
    @Volatile
    private var cachedServiceConnectionField: java.lang.reflect.Field? = null

    private fun bindRokidHostService(link: CXRLink, targetHostApp: RokidHostApp, authToken: String): Boolean {
        val conn = findServiceConnection(link) ?: return false
        return runCatching {
            val intent = Intent(MEDIA_SERVICE_ACTION)
                .setPackage(targetHostApp.packageName)
                .putExtra(AUTH_TOKEN_EXTRA, authToken)
                .putExtra(AUTH_PACKAGE_EXTRA, activity.packageName)
            activity.applicationContext.bindService(intent, conn, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
    }

    private fun findServiceConnection(link: CXRLink): ServiceConnection? {
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

    private fun isWifiEnabled(): Boolean {
        val wifiManager = activity.applicationContext.getSystemService(WifiManager::class.java)
        return wifiManager?.isWifiEnabled == true
    }

    private fun hasGlassesOperationPrerequisites(
        targetHostApp: RokidHostApp,
        requestAuthorizationIfMissing: Boolean,
    ): Boolean {
        if (!isHostAppInstalled(targetHostApp)) {
            onStatus(activity.getString(R.string.install_host_first, targetHostApp.displayName))
            return false
        }
        if (token.isNullOrBlank()) {
            onStatus(activity.getString(R.string.authorize_in_host, targetHostApp.displayName))
            if (requestAuthorizationIfMissing) requestAuthorization()
            return false
        }
        return true
    }

    /** 已知 APK 文件名到包名的映射表（兜底 readPackageName 使用） */
    private val KNOWN_APK_PACKAGES = mapOf(
        "RokidLink" to "com.rokidlab.rokidlink",
    )

    private fun readPackageName(apkFile: File): String {
        // 先尝试从 APK 读取（部分国产手机 getPackageArchiveInfo 可能返回 null）
        @Suppress("DEPRECATION")
        val info = runCatching {
            activity.packageManager.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.GET_ACTIVITIES)
        }.getOrNull()
        val fromApk = info?.packageName?.takeIf { it.isNotBlank() }
        if (fromApk != null) return fromApk
        // 兜底：从文件名推断（已知应用直接查映射表，未知用文件名自身）
        val name = apkFile.nameWithoutExtension
        val mapped = KNOWN_APK_PACKAGES[name] ?: name
        Log.w(TAG, "readPackageName: getPackageArchiveInfo failed, falling back: name=$name → pkg=$mapped")
        return mapped
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
