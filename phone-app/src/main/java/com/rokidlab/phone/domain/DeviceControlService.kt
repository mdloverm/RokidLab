package com.rokidlab.phone.domain

import android.content.pm.PackageManager
import android.util.Log
import com.rokid.cxr.Caps
import com.rokidlab.phone.R
import com.rokidlab.phone.design.RokidHostApp
import com.rokidlab.phone.glasses.AiChannel
import com.rokidlab.phone.glasses.CxrLHiRokidSession.Companion.AI_PREFS
import com.rokidlab.phone.glasses.CxrLHiRokidSession.Companion.KEY_KEY_QUIZ_ENABLED
import com.rokidlab.phone.glasses.LinkProtocol
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * L3 domain/DeviceControlService —— Phase 3 拆 `CxrLHiRokidSession` 的设备控制域服务。
 *
 * 职责：眼镜端 TTS/歌词/投屏停止等下行指令、APK 安装/启动/停止/卸载/查询、
 * 按键配置与「按键答题」开关下发、WiFi 凭证下发。
 *
 * 依赖：L3 ConnectionService（connectAnd* 编排，经 session.connection）、
 * L2 会话状态（cxrLink / 授权状态经 session 句柄）。
 * Session 保留同名 public 门面（调用方零改动）。
 */
class DeviceControlService(private val session: com.rokidlab.phone.glasses.CxrLHiRokidSession) {
    private companion object {
        const val TAG = "DeviceControlService"

        /** 等待 CXR 会话恢复的上限：宿主服务崩溃后系统约 21s 重启，留 4s 余量 */
        const val LINK_RECOVER_WAIT_MS = 25_000L
    }

    /** 已知 APK 文件名到包名的映射表（兜底 readPackageName 使用） */
    private val KNOWN_APK_PACKAGES = mapOf(
        "RokidLink" to "com.rokidlab.rokidlink",
    )

    /**
     * 发送文本到眼镜端本地 TTS 语音播报（tts_play 下行通道）。
     * 供定时任务 / AI 工具到点时语音提醒使用（App 退后台后链路仍可用）。
     * @return 发送结果码（0=成功，非 0=失败）
     */
    fun sendTtsToGlass(text: String): Int {
        if (text.isBlank()) return -1
        val link = session.cxrLink ?: return -2
        return try {
            fun send(): Int? {
                val caps = Caps()
                AiChannel.encodeTtsPlay(text).forEach { caps.write(it) }
                return link.sendCustomCmd(AiChannel.TOPIC_TTS_PLAY, caps)
            }
            var result: Int? = send()
            Log.i(TAG, "sendTtsToGlass(\"${text.take(40)}...\") -> $result")
            if (result != null && result != 0) {
                Thread.sleep(500)
                result = send()
                Log.w(TAG, "retry sendTtsToGlass -> $result")
            }
            result ?: -3
        } catch (e: Exception) {
            Log.e(TAG, "sendTtsToGlass failed", e)
            -1
        }
    }

    /**
     * 通知眼镜端立即停止本地 TTS 播报（tts_stop 下行通道）。
     * 对话退出/打断播报时调用：眼镜端 RokidLink 订阅 tts_stop 后调用
     * TtsPlaybackHelper.stop() 作废排队分块并释放正在等待的分块。
     * @return 发送结果码（0=成功，非 0=失败）
     */
    fun stopTtsOnGlass(): Int {
        val link = session.cxrLink ?: return -2
        return try {
            val caps = Caps()
            caps.write(AiChannel.CMD_TTS_STOP)
            // 与下行主链路串行（session.aiCmdLock），避免打断指令与 TTS_Result/tts_play 序列交错
            val result = synchronized(session.aiCmdLock) {
                link.sendCustomCmd(AiChannel.TOPIC_TTS_STOP, caps)
            }
            Log.i(TAG, "sendCustomCmd(${AiChannel.TOPIC_TTS_STOP}) -> $result")
            result ?: -3
        } catch (e: Exception) {
            Log.e(TAG, "stopTtsOnGlass failed", e)
            -1
        }
    }

    /**
     * 通知眼镜端关闭手机投屏接收页（stop_phone_mirror 下行通道）。
     *
     * 眼镜端 PhoneMirrorActivity 设计为 socket 断开后保持前台等待重连
     * （若退后台，重连成功后画面更新在后台不可见 → 表现为"投屏中但眼镜无画面"），
     * 因此停止投屏必须显式下发关闭指令，否则最后一帧画面会一直残留在眼镜上。
     *
     * 不能用 stopApp 整包杀 RokidLink：前台自动保活会在下一次 onResume 立刻重新拉起，
     * 用户体感就是"停止投屏要按两次"。
     * @return 发送结果码（0=成功，非 0=失败）
     */
    fun stopPhoneMirrorOnGlasses(): Int {
        val link = session.cxrLink ?: return -2
        return try {
            val caps = Caps()
            caps.write(AiChannel.CMD_STOP_PHONE_MIRROR)
            val result = synchronized(session.aiCmdLock) {
                link.sendCustomCmd(AiChannel.TOPIC_STOP_PHONE_MIRROR, caps)
            }
            Log.i(TAG, "sendCustomCmd(${AiChannel.TOPIC_STOP_PHONE_MIRROR}) -> $result")
            result ?: -3
        } catch (e: Exception) {
            Log.e(TAG, "stopPhoneMirrorOnGlasses failed", e)
            -1
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
        // CXR 宿主服务（com.rokid.sprite.aiapp）断开后，系统约 21s 才重启它（实测
        // `ActivityManager: Scheduling restart of crashed service ... in 21000ms`）。
        // 若此刻立刻发起安装，会在未就绪的会话上必然超时失败 —— 眼镜端连安装指令都收不到
        // （表现为「RokidLink 安装失败」，且眼镜 /sdcard/Download 里仍是旧包）。
        // 因此先等会话恢复；等待放后台线程，避免调用方（主线程）ANR。
        if (!session.cxrlConnected) {
            Thread({
                if (!awaitCxrSession()) {
                    session.onStatus("眼镜 CXR 服务未就绪（约需 20 秒重启），请稍后重试")
                    onInstallResult?.invoke(false)
                    return@Thread
                }
                installApk(apkFile, packageName, onInstallResult)
            }, "install-apk-wait-link").apply { isDaemon = true }.start()
            return
        }
        val targetHostApp = session.hostApp
        android.util.Log.i("CxrLInstall", "installApk: session.hostApp=$targetHostApp, packageName=$packageName, apkFile=$apkFile")
        if (!session.hasGlassesOperationPrerequisites(targetHostApp, requestAuthorizationIfMissing = true)) {
            android.util.Log.w("CxrLInstall", "installApk: prerequisites check FAILED (wifi=${session.isWifiEnabled()}, tokenBlank=${session.token.isNullOrBlank()})")
            onInstallResult?.invoke(false)
            return
        }
        val authToken = session.token.orEmpty()
        android.util.Log.i("CxrLInstall", "installApk: prerequisites OK, connecting... authToken=${authToken.take(8)}...")

        session.onBusyChanged(true)
        runCatching {
            session.onStatus(session.appContext.getString(R.string.detected_package, packageName))
            session.connection.connectAndUpload(authToken, targetHostApp, packageName, apkFile, onInstallResult)
        }.onFailure { error ->
            android.util.Log.e("CxrLInstall", "installApk: exception: ${error.javaClass.simpleName}: ${error.message}")
            session.onStatus(session.appContext.getString(R.string.cxrl_failed_msg, error.message ?: error.javaClass.simpleName))
            session.onBusyChanged(false)
            onInstallResult?.invoke(false)
        }
    }

    fun launchApp(packageName: String, activityClass: String = ".MainActivity", sendCmdAfterLaunch: String? = null, onLaunchResult: ((Boolean) -> Unit)? = null) {
        val targetHostApp = session.hostApp
        if (!session.hasGlassesOperationPrerequisites(targetHostApp, requestAuthorizationIfMissing = true)) {
            onLaunchResult?.invoke(false)
            return
        }
        val authToken = session.token.orEmpty()

        session.onBusyChanged(true)
        session.connection.connectAndLaunch(authToken, targetHostApp, packageName, activityClass, sendCmdAfterLaunch, onLaunchResult)
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
        val targetHostApp = session.hostApp
        if (!session.hasGlassesOperationPrerequisites(targetHostApp, requestAuthorizationIfMissing = true)) {
            onResult?.invoke(false)
            return
        }
        val authToken = session.token.orEmpty()

        session.onBusyChanged(true)
        session.connection.connectAndRunCustomAppOperation(
            authToken = authToken,
            targetHostApp = targetHostApp,
            operation = CxrAppOperation(
                packageName = "com.rokidlab.rokidlink",
                timeoutMillis = 10_000,
                timeoutMessage = session.appContext.getString(com.rokidlab.phone.R.string.key_btn_timeout),
                bindMessage = session.appContext.getString(com.rokidlab.phone.R.string.key_btn_binding),
                configureFailureMessage = session.appContext.getString(com.rokidlab.phone.R.string.key_btn_config_failed),
                bindFailureMessage = session.appContext.getString(com.rokidlab.phone.R.string.key_btn_bind_failed),
                showConnectionStatus = false,
                onReady = { link ->
                    // CUSTOMAPP 场景构建完成后 cxr-service 才会把自定义指令路由给眼镜端：
                    // 必须先 appStart 并等待 onOpenAppResult 成功，再 sendCustomCmd（与 sendKeyQuizConfig 一致）
                    val entryUri = "com.rokidlab.rokidlink.MainActivity"
                    link.appStart(entryUri, session.connection.glassAppCallback(
                        onStart = { success ->
                            if (success) {
                                val caps = Caps()
                                AiChannel.encodeKeyConfig(shortPkg, shortActivity, longPkg, longActivity)
                                    .forEach { caps.write(it) }
                                val result = link.sendCustomCmd(AiChannel.TOPIC_KEY_CONFIG, caps)
                                val resultMsg = if (result == 0) "OK" else "error=$result"
                                session.onStatus(session.appContext.getString(com.rokidlab.phone.R.string.key_btn_sent, shortPkg, longPkg, resultMsg))
                                onResult?.invoke(result == 0)
                            } else {
                                Log.w(TAG, "appStart failed, cannot send key config")
                                session.onStatus(session.appContext.getString(com.rokidlab.phone.R.string.key_btn_send_failed))
                                onResult?.invoke(false)
                            }
                            session.connection.completeActiveOperation()
                            session.onBusyChanged(false)
                        }
                    ))
                },
                onFailure = {
                    session.cleanup()
                    session.onBusyChanged(false)
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
        val targetHostApp = session.hostApp
        if (!session.hasGlassesOperationPrerequisites(targetHostApp, requestAuthorizationIfMissing = true)) {
            onResult?.invoke(false, "缺少前置条件")
            return
        }
        val authToken = session.token.orEmpty()

        session.onBusyChanged(true)
        session.connection.connectAndRunCustomAppOperation(
            authToken = authToken,
            targetHostApp = targetHostApp,
            operation = CxrAppOperation(
                packageName = "com.rokidlab.rokidlink",
                timeoutMillis = 30_000,
                timeoutMessage = session.appContext.getString(com.rokidlab.phone.R.string.wifi_config_timeout),
                bindMessage = session.appContext.getString(com.rokidlab.phone.R.string.key_btn_binding),
                configureFailureMessage = session.appContext.getString(com.rokidlab.phone.R.string.wifi_config_failed),
                bindFailureMessage = session.appContext.getString(com.rokidlab.phone.R.string.key_btn_bind_failed),
                showConnectionStatus = false,
                onReady = { link ->
                    val json = """{"module":"setting","ssid":"$ssid","password":"$password","forceReconnect":true}"""
                    Log.i(TAG, "Sending WiFi config: mode=Wifi_Connect, json=$json")
                    
                    val caps = Caps()
                    caps.write("Wifi_Connect")
                    caps.write(json)
                    
                    var statusReceived = false
                    val timeoutHandler = android.os.Handler(android.os.Looper.getMainLooper())
                    
                    // WiFi 状态回执由统一指令监听（session.registerGlobalCmdListener）转发到此处
                    session.wifiStatusCallback = { statusJson ->
                        statusReceived = true
                        timeoutHandler.removeCallbacksAndMessages(null)
                        try {
                            Log.i(TAG, "Received Wifi_Connect_Status: $statusJson")
                            
                            val jsonObj = org.json.JSONObject(statusJson)
                            val code = jsonObj.getInt("code")
                            val status = jsonObj.getString("status")
                            
                            session.connection.completeActiveOperation()
                            session.onBusyChanged(false)
                            
                            if (code == 0 && status == "CONNECTED") {
                                session.onStatus(session.appContext.getString(com.rokidlab.phone.R.string.wifi_config_success, ssid))
                                onResult?.invoke(true, null)
                            } else {
                                val errorMsg = jsonObj.optString("message", "连接失败，请检查密码")
                                session.onStatus(session.appContext.getString(com.rokidlab.phone.R.string.wifi_config_failed) + ": $errorMsg")
                                onResult?.invoke(false, errorMsg)
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "Parse Wifi_Connect_Status failed", e)
                            session.connection.completeActiveOperation()
                            session.onBusyChanged(false)
                            onResult?.invoke(true, null)
                        }
                    }
                    
                    val result = link.sendCustomCmd(LinkProtocol.CXR_CHANNEL_WIFI, caps)
                    Log.i(TAG, "sendCustomCmd(Wifi) -> $result")
                    
                    if (result != 0) {
                        timeoutHandler.removeCallbacksAndMessages(null)
                        session.connection.completeActiveOperation()
                        session.onBusyChanged(false)
                        onResult?.invoke(false, "发送失败")
                    } else {
                        timeoutHandler.postDelayed({
                            if (!statusReceived) {
                                Log.w(TAG, "WiFi config timeout after 5s, assuming success")
                                session.connection.completeActiveOperation()
                                session.onBusyChanged(false)
                                onResult?.invoke(true, null)
                            }
                        }, 5_000)
                    }
                },
                onFailure = {
                    session.cleanup()
                    session.onBusyChanged(false)
                    onResult?.invoke(false, "连接失败")
                },
            ),
        )
    }

    /**
     * 下发「按键答题」开关到眼镜端。
     * 开关打开后：短按镜腿按键 = 拍照问AI（覆盖原自定义按键短按），长按不受影响。
     */
    fun sendKeyQuizConfig(enabled: Boolean, onResult: ((Boolean) -> Unit)? = null) {
        // onResult 防重：超时 onFailure 与迟到的 appStart 回调都可能触发，保证只通知一次
        var resultDelivered = false
        fun deliver(success: Boolean) {
            if (resultDelivered) return
            resultDelivered = true
            onResult?.invoke(success)
        }
        runCatching {
            session.appContext.getSharedPreferences(AI_PREFS, 0).edit()
                .putBoolean(KEY_KEY_QUIZ_ENABLED, enabled)
                .apply()
        }
        val targetHostApp = session.hostApp
        if (!session.hasGlassesOperationPrerequisites(targetHostApp, requestAuthorizationIfMissing = true)) {
            deliver(false)
            return
        }
        val authToken = session.token.orEmpty()

        // 轻量配置下发优化：链路已就绪时复用现有连接直接下发自定义指令，
        // 不再走 session.connection.connectAndRunCustomAppOperation 的 session.cleanup() 全链路重建——
        // 重建期间（3~5s）ASR 推送通道与 ADB 隧道均不可用，保存设置后紧接着说话的
        // 第一条语音必然丢失（实测 21:28:46：保存设置→session.cleanup→start 连调两次→通道断开→丢字）。
        val existingLink = session.cxrLink
        if (existingLink != null && session.cxrlConnected && session.glassBtConnected) {
            val caps = Caps()
            AiChannel.encodeQuizConfig(enabled).forEach { caps.write(it) }
            val r = synchronized(session.aiCmdLock) { existingLink.sendCustomCmd(AiChannel.TOPIC_KEY_QUIZ, caps) }
            Log.i(TAG, "sendKeyQuizConfig: reuse existing link, sendCustomCmd(${AiChannel.TOPIC_KEY_QUIZ}, enabled=$enabled) -> $r")
            if (r == 0) {
                deliver(true)
                session.onBusyChanged(false)
                return
            }
            // 复用失败（如 CUSTOMAPP 会话尚未 appStart，自定义指令还路由不到眼镜端）：
            // 退回完整流程重建链路后下发。
            Log.w(TAG, "sendKeyQuizConfig: reuse failed (r=$r), fall back to full connect")
        }

        session.onBusyChanged(true)
        session.connection.connectAndRunCustomAppOperation(
            authToken = authToken,
            targetHostApp = targetHostApp,
            operation = CxrAppOperation(
                packageName = "com.rokidlab.rokidlink",
                timeoutMillis = 10_000,
                timeoutMessage = "quiz config timeout",
                bindMessage = "Sending quiz config",
                configureFailureMessage = "Configure CXR-L CUSTOMAPP session failed",
                bindFailureMessage = "Bind host service failed",
                showConnectionStatus = false,
                onReady = { link ->
                    // CUSTOMAPP 场景构建完成后 cxr-service 才会把自定义指令路由给眼镜端：
                    // 必须先 appStart 并等待 onOpenAppResult 成功，再 sendCustomCmd（与 launchApp 一致）
                    val entryUri = "com.rokidlab.rokidlink.MainActivity"
                    link.appStart(entryUri, session.connection.glassAppCallback(
                        onStart = { success ->
                            if (success) {
                                // 置一次性冷却标志：appStart 后眼镜端 RokidLink 会真实 resume，
                                // 触发 Sys_App_Resume_Change 上行，需消费该 resume 避免误触发拍照答题。
                                // 用一次性标志而非 3s 时间窗口，避免「开启后 3s 内的第一次按键」被误过滤。
                                session.quizResumeCooling = true
                                session.appScope.launch {
                                    delay(3000)
                                    session.quizResumeCooling = false
                                }
                                // SDK 的 appStart 内部会用传入 cbk 覆盖 setCXRGlassAppCbk，
                                // 这里重新注册「按键答题」的 resume 监听，恢复短按触发拍照答题
                                session.registerKeyQuizResumeListener(link)
                                val caps = Caps()
                                AiChannel.encodeQuizConfig(enabled).forEach { caps.write(it) }
                                val result = link.sendCustomCmd(AiChannel.TOPIC_KEY_QUIZ, caps)
                                Log.i(TAG, "sendCustomCmd(${AiChannel.TOPIC_KEY_QUIZ}, enabled=$enabled) -> $result")
                                deliver(result == 0)
                            } else {
                                Log.w(TAG, "appStart failed, cannot send quiz config")
                                deliver(false)
                            }
                            session.connection.completeActiveOperation()
                            session.onBusyChanged(false)
                        }
                    ))
                },
                onFailure = {
                    session.cleanup()
                    session.onBusyChanged(false)
                    deliver(false)
                },
            ),
        )
    }

    fun stopApp(packageName: String, onStopResult: ((Boolean) -> Unit)? = null) {
        val targetHostApp = session.hostApp
        if (!session.hasGlassesOperationPrerequisites(targetHostApp, requestAuthorizationIfMissing = true)) {
            onStopResult?.invoke(false)
            return
        }
        val authToken = session.token.orEmpty()

        session.onBusyChanged(true)
        session.connection.connectAndStop(authToken, targetHostApp, packageName, onStopResult)
    }

    /**
     * 等待 CXR 会话恢复（最多 [LINK_RECOVER_WAIT_MS]）。
     * @return true = 已连接；false = 超时仍未恢复
     */
    private fun awaitCxrSession(): Boolean {
        android.util.Log.w("CxrLInstall", "CXR 会话未连接，等待恢复（最多 ${LINK_RECOVER_WAIT_MS}ms）…")
        val deadline = System.currentTimeMillis() + LINK_RECOVER_WAIT_MS
        while (!session.cxrlConnected && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(500L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
        android.util.Log.i("CxrLInstall", "等待结束：cxrlConnected=${session.cxrlConnected}")
        return session.cxrlConnected
    }

    fun uninstallApp(packageName: String, onUninstallResult: ((Boolean) -> Unit)? = null) {
        // 同 installApk：会话未就绪时先等恢复，否则卸载必然 30s 超时
        if (!session.cxrlConnected) {
            Thread({
                if (!awaitCxrSession()) {
                    session.onStatus("眼镜 CXR 服务未就绪（约需 20 秒重启），请稍后重试")
                    onUninstallResult?.invoke(false)
                    return@Thread
                }
                uninstallApp(packageName, onUninstallResult)
            }, "uninstall-app-wait-link").apply { isDaemon = true }.start()
            return
        }
        val targetHostApp = session.hostApp
        if (!session.hasGlassesOperationPrerequisites(targetHostApp, requestAuthorizationIfMissing = true)) {
            onUninstallResult?.invoke(false)
            return
        }
        val authToken = session.token.orEmpty()

        session.onBusyChanged(true)
        session.connection.connectAndUninstall(authToken, targetHostApp, packageName, onUninstallResult)
    }

    fun queryInstalledApps(
        packageNames: List<String>,
        onResult: (String, Boolean?) -> Unit,
        onComplete: () -> Unit,
    ) {
        val targetHostApp = session.hostApp
        if (packageNames.isEmpty()) {
            onComplete()
            return
        }
        if (!session.hasGlassesOperationPrerequisites(targetHostApp, requestAuthorizationIfMissing = true)) {
            onComplete()
            return
        }
        val authToken = session.token.orEmpty()

        // 如果已有查询在进行，追加包名并替换回调，不清除已有连接
        if (session.queryQueue.isNotEmpty() && !session.operationStarted) {
            session.queryQueue.addAll(packageNames.filterNot(session.queryQueue::contains))
            session.onQueryResult = onResult
            session.onQueryComplete = onComplete
            return
        }

        session.cleanup()
        session.queryQueue = java.util.ArrayDeque(packageNames.distinct())
        session.onQueryResult = onResult
        session.onQueryComplete = onComplete
        session.onBusyChanged(true)
        session.connection.queryNext(authToken, targetHostApp)
    }

    internal fun readPackageName(apkFile: File): String {
        // 先尝试从 APK 读取（部分国产手机 getPackageArchiveInfo 可能返回 null）
        @Suppress("DEPRECATION")
        val info = runCatching {
            session.appContext.packageManager.getPackageArchiveInfo(apkFile.absolutePath, PackageManager.GET_ACTIVITIES)
        }.getOrNull()
        val fromApk = info?.packageName?.takeIf { it.isNotBlank() }
        if (fromApk != null) return fromApk
        // 兜底：从文件名推断（已知应用直接查映射表，未知用文件名自身）
        val name = apkFile.nameWithoutExtension
        val mapped = KNOWN_APK_PACKAGES[name] ?: name
        Log.w(TAG, "readPackageName: getPackageArchiveInfo failed, falling back: name=$name → pkg=$mapped")
        return mapped
    }
}
