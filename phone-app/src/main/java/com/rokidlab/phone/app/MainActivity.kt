package com.rokidlab.phone.app

import com.rokidlab.phone.app.*
import com.rokidlab.phone.adb.*
import com.rokidlab.phone.design.theme.BrewThemeManager
import com.rokidlab.phone.design.*
import com.rokidlab.phone.filemanager.*
import com.rokidlab.phone.glasses.*
import com.rokidlab.phone.mirror.*
import com.rokidlab.phone.model.*
import com.rokidlab.phone.network.*
import com.rokidlab.phone.settings.*
import com.rokidlab.phone.store.*
import com.rokidlab.phone.util.*
import com.rokidlab.phone.BuildConfig
import com.rokidlab.phone.R
import com.rokidlab.phone.feature.MainScreen
import com.rokidlab.phone.feature.buildStoreActions
import com.rokidlab.phone.platform.onAvailable
import com.rokidlab.phone.platform.onUnavailable
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.Manifest
import android.app.Activity
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val PREFS_NAME = "rokidbrew_preferences"
private const val PREF_ROKID_HOST_APP = "rokid_host_app"
private const val PREF_COMPAT_GUIDE_DISMISSED = "compat_guide_dismissed"
private const val PREF_OVERLAY_GUIDE_DISMISSED = "overlay_guide_dismissed"

class MainActivity : AppCompatActivity() {
    private companion object {
        private const val TAG = "MainActivity"
    }

    enum class InstallState { UNKNOWN, NOT_INSTALLED, INSTALLED, INSTALLED_UNKNOWN_VERSION, UPDATE_AVAILABLE }
    internal enum class InstallStateSource { CACHED, VERIFIED }

    internal lateinit var cxrL: CxrLHiRokidSession
    internal lateinit var downloader: ApkDownloader
    internal lateinit var iconLoader: IconLoader
    internal lateinit var mediaLoader: MediaLoader
    internal lateinit var installCache: UserInstallCache

    internal var apps by mutableStateOf(emptyList<BrewApp>())
    internal var busy by mutableStateOf(false)
    internal var refreshing by mutableStateOf(false)
    private var pendingAction: (() -> Unit)? = null
    /** 用户是否在本会话中通过启动按键启动过 RokidLink */
    /** 自动启动链路标记：ensureRokidLinkRunning 成功拉起后置 true，避免每次 onResume 重复 launch */
    /** 是否已对本会话做过 ADB 连通性检测（仅新鲜启动时一次） */
    internal var selfUpdateState by mutableStateOf(
        BrewSelfUpdateState(
            currentVersion = BuildConfig.VERSION_NAME,
            currentVersionCode = BuildConfig.VERSION_CODE.toLong(),
        ),
    )
    internal var showUpdatePrompt by mutableStateOf(false)
    internal var selectedHostApp by mutableStateOf(RokidHostApp.DEFAULT)
    internal var cxrConnection by mutableStateOf(CxrConnectionState())
    internal var showMirrorDialog by mutableStateOf(false)
    internal var showRefreshDialog by mutableStateOf(false)
    internal var refreshDialogSuccess by mutableStateOf(false)
    internal var refreshDialogMessage by mutableStateOf("")
    internal var prerequisitesState by mutableStateOf(PrerequisitesState())
    internal var screenMirrorState by mutableStateOf(ScreenMirrorState())
    internal var phoneMirrorState by mutableStateOf(PhoneMirrorState())
    internal var fileManagerState by mutableStateOf(FileManagerState())
    internal var settingsReinstallError: String? = null
    // 日志列表，用于 UI 实时显示（最多保留 100 条）
    private val logMessages = mutableStateListOf<String>()
    // 错误日志导出
    internal var showErrorLogDialog by mutableStateOf(false)
    internal var errorLogContent by mutableStateOf("")
    internal var errorLogSaved by mutableStateOf(false)

    // 本地APK安装状态
    internal var isInstallingLocalApk by mutableStateOf(false)
    internal var localApkInstallProgress by mutableStateOf(0)
    internal var localApkInstallStatus by mutableStateOf("")

    private val permissions: Array<String>
        get() = buildList {
            add(Manifest.permission.BLUETOOTH)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Android 12+ 需要运行时请求 BLUETOOTH_SCAN（发现设备）和 BLUETOOTH_CONNECT（连接设备）
                // BLUETOOTH_ADMIN 在 API 31+ 已弃用，不再需要
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                @Suppress("DEPRECATION")
                add(Manifest.permission.BLUETOOTH_ADMIN)
            }
        }.toTypedArray()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (permissions.all(::hasPermission)) {
                // 权限已就绪，重新评估蓝牙是否也已就绪（避免只过了权限就直接执行）
                proceedIfPrerequisitesReady()
            } else {
                log(getString(R.string.log_bluetooth_permission_denied))
                Toast.makeText(this, this.getString(R.string.bluetooth_permission_denied), Toast.LENGTH_LONG).show()
                // 关键修复(A1)：权限被拒后必须清空 pendingAction，否则首行
                // `if (pendingAction != null) return` 会永久拦截后续所有核心操作
                pendingAction = null
            }
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            if (it) {
                log(getString(R.string.log_notification_permission_granted))
            } else {
                log(getString(R.string.log_notification_permission_denied))
            }
        }

    private val enableBluetoothLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (isBluetoothEnabled()) {
                proceedIfPrerequisitesReady() // 此时权限已就绪，直接执行挂起动作
            } else {
                log(getString(R.string.log_bluetooth_not_enabled))
                // 关键修复(A1)：蓝牙未开启时清空 pendingAction，避免永久死锁
                pendingAction = null
            }
        }

    /** 现代授权 Launcher，替代已废弃的 startActivityForResult + onActivityResult */
    private val authLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            cxrL.handleAuthorizationResult(result.resultCode, result.data)
            if (cxrL.hasAuthorization()) {
                prerequisitesState = prerequisitesState.copy(authorized = true)
            }
        }

    private val phoneInstallStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            intent.getStringExtra(PhoneInstallResultReceiver.EXTRA_MESSAGE)?.let(::log)
            installCheckTick += 1
            refreshPhoneInstallStates()
        }
    }

    internal val apkPickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { installLocalApkToGlasses(it) }
    }

    // 手机投屏 - MediaProjection 权限请求
    @Volatile
    private var isStartingPhoneMirror = false
    private val phoneMirrorProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data ?: run {
            log(getString(R.string.log_mediaprojection_denied))
            phoneMirrorState = phoneMirrorState.copy(
                isMirroring = false,
                connectionStatus = this@MainActivity.getString(R.string.permission_denied)
            )
            // 眼镜端 RokidLink 已启动，需关闭
            cxrL.stopApp("com.rokidlab.rokidlink")
            phoneMirrorState = phoneMirrorState.copy(rokidLinkRunning = false)
            isStartingPhoneMirror = false
            return@registerForActivityResult
        }
        isStartingPhoneMirror = false
        if (result.resultCode == Activity.RESULT_OK) {
            log(getString(R.string.log_mediaprojection_granted))
            val app = application as LabApplication
            phoneMirrorState = phoneMirrorState.copy(
                isMirroring = true,
                connectionStatus = this@MainActivity.getString(R.string.screen_projection)
            )
            // 异步路由判断：WiFi 可达则直连，否则走蓝牙隧道
            val wifiPort = app.phoneMirrorPort.toIntOrNull() ?: 7654
            Thread {
                // 首启时眼镜端 IP 上行常晚于本回调：等就绪再探测，否则必然回落到蓝牙隧道
                val wifiIp = app.awaitGlassesIp(2_000L)
                val route = kotlinx.coroutines.runBlocking {
                    app.routeManager.resolve(wifiIp, wifiPort)
                }
                val (targetIp, targetPort, isBT) = when (route) {
                    is com.rokidlab.phone.connection.ConnectionRoute.Wifi -> Triple(route.ip, route.port, false)
                    is com.rokidlab.phone.connection.ConnectionRoute.Bluetooth -> Triple(route.ip, route.localPort, true)
                    is com.rokidlab.phone.connection.ConnectionRoute.None -> {
                        runOnUiThread {
                            log("No route to glasses (WiFi and BT both unavailable)")
                            phoneMirrorState = phoneMirrorState.copy(
                                isMirroring = false,
                                connectionStatus = getString(R.string.mirror_connection_failed)
                            )
                        }
                        return@Thread
                    }
                }
                Log.i("MainActivity", "PhoneMirror route: $route → $targetIp:$targetPort (BT=$isBT)")
                PhoneMirrorService.startService(
                    this@MainActivity,
                    targetIp,
                    targetPort,
                    result.resultCode,
                    data,
                    isBluetooth = isBT
                )
            }.start()
        } else {
            log(getString(R.string.log_mediaprojection_denied))
            phoneMirrorState = phoneMirrorState.copy(
                isMirroring = false,
                connectionStatus = this@MainActivity.getString(R.string.permission_denied)
            )
            // 眼镜端 RokidLink 已启动，需关闭
            cxrL.stopApp("com.rokidlab.rokidlink")
            phoneMirrorState = phoneMirrorState.copy(rokidLinkRunning = false)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BrewThemeManager.init(this)
        preferHighRefreshRate()
        // 初始化日志收集器（含全局崩溃处理器）
        LogCollector.init(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE.toLong())

        BrewIndex.initMirror(this)
        try {
            (application as LabApplication).hidManager.initialize()
        } catch (_: Exception) {
            // 设备不支持蓝牙 HID 时忽略
        }
        downloader = ApkDownloader(this)
        iconLoader = IconLoader(this)
        mediaLoader = MediaLoader(this)
        installCache = UserInstallCache(this)
        selectedHostApp = loadSelectedHostApp()
        // 判断是否为首次启动（SharedPreferences 中无保存的 hostApp id）
        val isFirstRun = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getString(PREF_ROKID_HOST_APP, null) == null
        // 初始化前置条件状态：首次用户从步骤1开始，老用户恢复进度
        prerequisitesState = PrerequisitesState(
            hostApp = if (isFirstRun) null else selectedHostApp,
            mirrorSourceSelected = !isFirstRun,
        )
        
        // 检查是否有从FileManagerActivity传来的APK安装请求
        handleIncomingApkInstall(intent = getIntent())
        val app = application as LabApplication
        // 保活残留的旧会话（上次退出未 cleanup）：先释放连接，新会话干净重连。
        // 蓝牙 HID 等系统级能力不受影响（HID 注册与 Activity 无关）。
        if (app.hasCxrL()) {
            app.cxrL.cleanup()
        }
        cxrL = CxrLHiRokidSession(
            activity = this,
            onStatus = ::log,
            onBusyChanged = ::updateBusy,
            onConnectionChanged = { conn ->
                cxrConnection = conn
                // 确保 cxrL 已完成初始化后再调用依赖它的方法
                if (conn.authorized && ::cxrL.isInitialized) {
                    checkRokidLinkInstallation()
                }
                // CXR-L 连接状态变化不影响 running 状态
                // ADB 连通性检测在 onResume 中触发，按键启动后本会话保持已启动
            },
            initialHostApp = selectedHostApp,
            authLauncher = authLauncher::launch,
            // 长驻任务作用域：保活场景下 ASR 推送/轮询不随 Activity 销毁取消
            appScope = (application as LabApplication).appScope,
        )
        (application as LabApplication).setCxrL(cxrL)
        // 保活开启时启动前台保活服务（通知栏常驻，防系统回收后台能力）
        (application as LabApplication).startKeepAliveService()
        // 如果之前已授权，同步前置条件状态
        if (cxrL.hasAuthorization()) {
            prerequisitesState = prerequisitesState.copy(authorized = true)
        }
        // 启动时同步 RokidLink 安装状态（含缓存命中 + SDK 查询两种路径）
        checkRokidLinkInstallation()
        apps = runCatching { BrewIndex.loadInitial(this) }.getOrDefault(emptyList())
        log(getString(R.string.log_loaded_apps, apps.size))
        refreshCachedGlassesInstallStates(apps)
        refreshPhoneInstallStates(apps)

        // 检查 PhoneMirrorService 是否正在运行（Activity 重建后恢复状态）
        if (isServiceRunning(this, PhoneMirrorService::class.java)) {
            isStartingPhoneMirror = false
            phoneMirrorState = phoneMirrorState.copy(
                isMirroring = true,
                connectionStatus = getString(R.string.screen_projection)
            )
        }

        // 【安全修复】已移除 TEST_AI_TEXT 广播接收器：
        // 原实现用 RECEIVER_EXPORTED 注册，任意 App 可远程触发 AI 发消息（安全漏洞），
        // 且属于"临时测试"调试残留。AI 链路测试请走应用内对话或 adb 调试通道。

        setContent {
            MainScreen()
        }
        // 仅在非引导模式下启动时自动刷新（引导模式由 LaunchedEffect 在引导完成后处理）
        if (prerequisitesState.canInstallApps) {
            refreshStoreIndex(manual = false)
        }
        // 独立检查 RokidLab 自身更新，不依赖刷新结果或引导状态
        lifecycleScope.launch {
            checkRokidLabUpdate()
        }
        log(getString(R.string.log_ready_authorize, selectedHostApp.displayName))

        // Android 13+ 请求通知权限（用于定时消息推送到眼镜）
        requestNotificationPermission()

        // AI 工具权限（通讯录/日历，缺失时对应工具会返回引导文本，不阻塞启动）
        requestAiToolPermissions()

        // 检查国产手机兼容性设置（电池优化白名单、自启动权限等）
        checkCompatibilitySettings()
    }

    // ════════════════════════════════════════════════════════════════
    //  通知权限请求与系统设置
    // ════════════════════════════════════════════════════════════════
    // ════════════════════════════════════════════════════════════════
    //  AI 工具运行时权限（通讯录/日历/拨号，用于 Agent 的联系人查找、日程与打电话工具）
    // ════════════════════════════════════════════════════════════════
    /** 独立于蓝牙权限 launcher：拒绝只记日志不弹提示（缺失时对应 AI 工具会返回引导文本） */
    private val aiToolPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    private fun requestAiToolPermissions() {
        val needed = listOf(
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_CALENDAR,
            Manifest.permission.WRITE_CALENDAR,
            // 拨号：首次进入（或未申请过）时随其他权限一起弹窗，否则打电话会一直没权限
            Manifest.permission.CALL_PHONE,
        ).filter { !hasPermission(it) }
        if (needed.isEmpty()) return
        lifecycleScope.launch {
            // 延后 4s：先让蓝牙/通知等核心权限弹窗走完，避免一次性轰炸用户
            delay(4000)
            if (needed.any { !hasPermission(it) }) {
                aiToolPermissionLauncher.launch(needed.toTypedArray())
            }
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (hasPermission(Manifest.permission.POST_NOTIFICATIONS)) return
        log(getString(R.string.log_requesting_notification_permission))
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        // MIUI 可能静默拒绝，启动后再次尝试打开设置页引导用户
        lifecycleScope.launch {
            delay(2000)
            if (!hasPermission(Manifest.permission.POST_NOTIFICATIONS)) {
                log(getString(R.string.log_notification_permission_guide))
                openAppNotificationSettings()
            }
        }
    }

    private fun openAppNotificationSettings() {
        try {
            val intent = Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = android.net.Uri.fromParts("package", packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            log(getString(R.string.log_cannot_open_settings, e.message))
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(PhoneInstallResultReceiver.ACTION_PHONE_INSTALL_STATUS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(phoneInstallStatusReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(phoneInstallStatusReceiver, filter)
        }
    }

    override fun onStop() {
        runCatching { unregisterReceiver(phoneInstallStatusReceiver) }
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        preferHighRefreshRate()
        installCheckTick += 1
        refreshPhoneInstallStates()
        // 不重置 RokidLink 运行状态 - 按键启动后整个会话内保持已启动
        // 每次回到前台尝试触发 ADB 连通性检测（内部保证每会话仅执行一次）
        testAdbOnFreshLaunch()
    }

    private fun preferHighRefreshRate() {
        val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay
        } ?: return
        val currentMode = display.mode
        val matchingModes = display.supportedModes
            .filter { mode ->
                mode.physicalWidth == currentMode.physicalWidth &&
                    mode.physicalHeight == currentMode.physicalHeight
            }
        val fastestMode = matchingModes.maxByOrNull { it.refreshRate } ?: return
        val preferredMode = if (fastestMode.refreshRate > currentMode.refreshRate) fastestMode else currentMode

        val attributes = window.attributes
        if (
            attributes.preferredDisplayModeId != preferredMode.modeId ||
            attributes.preferredRefreshRate != fastestMode.refreshRate
        ) {
            attributes.preferredDisplayModeId = preferredMode.modeId
            attributes.preferredRefreshRate = fastestMode.refreshRate
            if (Build.VERSION.SDK_INT >= 35) {
                com.rokidlab.phone.platform.RomAdapter.setFrameRatePowerSavingsBalanced(attributes, false)
                    .onUnavailable { Log.w(TAG, "setFrameRatePowerSavingsBalanced: $it") }
            }
            window.attributes = attributes
        }
        if (Build.VERSION.SDK_INT >= 35) {
            com.rokidlab.phone.platform.RomAdapter.requestedFrameRateCategoryHigh()
                .onAvailable { cat ->
                    com.rokidlab.phone.platform.RomAdapter.setRequestedFrameRate(window.decorView, cat)
                        .onUnavailable { Log.w(TAG, "setRequestedFrameRate: $it") }
                }
                .onUnavailable { Log.w(TAG, "setRequestedFrameRate: $it") }
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  商店刷新 / 镜像源切换 / 本地 APK 安装 / 自更新
    // ════════════════════════════════════════════════════════════════
    internal fun refreshStoreIndex(manual: Boolean) {
        if (refreshing) return
        lifecycleScope.launch {
            refreshing = true
            val started = System.currentTimeMillis()
            if (manual) log(getString(R.string.log_refreshing_registry))
            runCatching {
                BrewIndex.refresh(this@MainActivity)
            }.onSuccess { refresh ->
                apps = refresh.apps
                refreshCachedGlassesInstallStates(refresh.apps)
                refreshPhoneInstallStates(refresh.apps)
                installCheckTick += 1
                log(getString(R.string.log_registry_updated, refresh.apps.size))
                if (manual) {
                    showRefreshDialog = true
                    refreshDialogSuccess = true
                    refreshDialogMessage = getString(R.string.refresh_result_ok, refresh.apps.size)
                }
            }.onFailure { error ->
                log(getString(R.string.log_registry_unavailable, error.message ?: error.javaClass.simpleName))
                // 启动自动刷新失败不弹窗，仅后台静默重试；手动刷新失败才弹窗提示用户
                if (manual) {
                    showRefreshDialog = true
                    refreshDialogSuccess = false
                    refreshDialogMessage = getString(R.string.refresh_result_fail, error.message ?: error.javaClass.simpleName)
                }
            }
            val elapsed = System.currentTimeMillis() - started
            if (elapsed < 300) delay(300 - elapsed)
            refreshing = false
        }
    }

    /**
     * 检查 RokidLab 自身更新（固定从 Gitee 获取）
     */
    private suspend fun checkRokidLabUpdate() {
        runCatching {
            BrewIndex.checkSelfUpdate()
        }.onSuccess { update ->
            if (update != null && update.versionCode > BuildConfig.VERSION_CODE && update.apkUrl.isNotBlank()) {
                val wasUpdateAvailable = selfUpdateState.available
                selfUpdateState = selfUpdateState.copy(
                    currentVersion = BuildConfig.VERSION_NAME,
                    currentVersionCode = BuildConfig.VERSION_CODE.toLong(),
                    latestVersion = update.version,
                    latestVersionCode = update.versionCode,
                    apkUrl = update.apkUrl,
                    releaseUrl = update.releaseUrl,
                    notes = update.notes,
                    changes = update.changes,
                    available = true,
                )
                if (!wasUpdateAvailable) showUpdatePrompt = true
                log(getString(R.string.log_update_available, updateVersionLabel(update.version)))
            } else {
                selfUpdateState = selfUpdateState.copy(available = false)
            }
        }.onFailure { error ->
            log(getString(R.string.log_update_check_failed, error.message ?: error.javaClass.simpleName))
        }
    }

    internal fun switchMirror(index: Int) {
        BrewIndex.setMirror(this, index)
        prerequisitesState = prerequisitesState.copy(mirrorSourceSelected = true)
        log(getString(R.string.log_switched_mirror, BrewIndex.getCurrentMirror().name))
        // 只在非引导模式下立即刷新（引导中的刷新由 LaunchedEffect 在引导完成后统一处理）
        if (prerequisitesState.canInstallApps) {
            refreshStoreIndex(manual = true)
        }
    }

    /**
     * L5 应用更新域（Phase 5：本地 APK 安装 + 自助更新迁至 feature/AppUpdateController）。
     */
    internal val updates by lazy { com.rokidlab.phone.feature.AppUpdateController(this) }

    private fun installLocalApkToGlasses(uri: Uri) = updates.installLocalApkToGlasses(uri)
    private fun scheduleClearLocalApkInstallState() = updates.scheduleClearLocalApkInstallState()
    internal fun performSelfUpdate() = updates.performSelfUpdate()


    override fun onDestroy() {
        // 退出时清理日志，避免下次打开看到旧日志
        LogCollector.clear()
        if ((application as LabApplication).keepAliveEnabled) {
            // 保活开启：保留 CxrL 蓝牙链路 + 蓝牙 HID 注册，后台能力持续运行。
            // 重开 App 时 onCreate 会对残留旧会话先 cleanup 再建新会话，此处无需断开。
            // 解除会话对本 Activity 的回调引用（防 Activity 被 Application 单例钉住泄漏），
            // 后台链路不受影响，UI 回调降级为纯日志。
            cxrL.detachUiCallbacks()
            Log.i(TAG, "keep-alive enabled: preserving background links on destroy")
        } else {
            // 保活关闭：彻底清理（现状行为）
            cxrL.cleanup()
            (application as LabApplication).hidManager.destroy()
        }
        // 进程真正退出时关闭眼镜端 RokidLink（isFinishing=false 时为旋转/配置变更重建，跳过）
        if (isFinishing) {
            runCatching { stopRokidLinkNow() }
        }
        super.onDestroy()
    }

    // ── 授权结果由 authLauncher (registerForActivityResult) 处理，无需 onActivityResult ──

    // ════════════════════════════════════════════════════════════════
    //  应用安装状态同步（眼镜 / 手机）—— Phase 5：迁至 feature/StoreInstallStateHolder
    // ════════════════════════════════════════════════════════════════
    internal val storeInstallStates by lazy { com.rokidlab.phone.feature.StoreInstallStateHolder(this) }

    internal var installCheckTick: Int
        get() = storeInstallStates.installCheckTick
        set(value) { storeInstallStates.installCheckTick = value }
    internal val downloadProgress get() = storeInstallStates.downloadProgress
    internal val downloadCancelJobs get() = storeInstallStates.downloadCancelJobs
    internal val phoneInstallStates get() = storeInstallStates.phoneInstallStates
    internal val glassesInstallStates get() = storeInstallStates.glassesInstallStates
    private val glassesInstallStateSources get() = storeInstallStates.glassesInstallStateSources

    internal fun checkGlassesInstallStateIfNeeded(app: BrewApp) = storeInstallStates.checkGlassesInstallStateIfNeeded(app)
    private fun refreshCachedGlassesInstallStates(targetApps: List<BrewApp> = apps) = storeInstallStates.refreshCachedGlassesInstallStates(targetApps)
    private fun refreshGlassesInstallStates(targetApps: List<BrewApp> = apps) = storeInstallStates.refreshGlassesInstallStates(targetApps)
    private fun refreshPhoneInstallStates(targetApps: List<BrewApp> = apps) = storeInstallStates.refreshPhoneInstallStates(targetApps)
    internal fun launchApp(app: BrewApp, target: String) = storeInstallStates.launchApp(app, target)
    internal fun installArtifact(app: BrewApp, target: String) = storeInstallStates.installArtifact(app, target)
    internal fun uninstallArtifact(app: BrewApp, target: String) = storeInstallStates.uninstallArtifact(app, target)
    internal fun cancelDownload(key: String) = storeInstallStates.cancelDownload(key)

    internal fun updateBusy(value: Boolean) = storeInstallStates.updateBusy(value)









    // ════════════════════════════════════════════════════════════════
    //  主机应用与引导 / 日志导出与保活
    // ════════════════════════════════════════════════════════════════
    private fun loadSelectedHostApp(): RokidHostApp {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        return RokidHostApp.fromId(prefs.getString(PREF_ROKID_HOST_APP, null))
    }

    internal fun selectRokidHostApp(hostApp: RokidHostApp) {
        if (selectedHostApp == hostApp) return
        selectedHostApp = hostApp
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putString(PREF_ROKID_HOST_APP, hostApp.id)
            .apply()
        cxrL.selectHostApp(hostApp)
        glassesInstallStates.clear()
        glassesInstallStateSources.clear()
        installCheckTick += 1
        prerequisitesState = prerequisitesState.copy(hostApp = hostApp, authorized = false)
        log(getString(R.string.log_host_set, hostApp.displayName))
    }

    internal fun goToGuideStep1() {
        // 重置前置条件状态，让用户重新完成引导流程
        // 将 hostApp 和 selectedHostApp 都重置，这样会显示第一步（选择主机应用）
        selectedHostApp = RokidHostApp.DEFAULT
        // 同时清除持久化存储，确保重启后生效
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .remove(PREF_ROKID_HOST_APP)
            .apply()
        prerequisitesState = prerequisitesState.copy(
            hostApp = null,
            mirrorSourceSelected = false,
            authorized = false,
            wifiConfigured = false,
        )
        log(getString(R.string.log_guide_step))
        Toast.makeText(this@MainActivity, this@MainActivity.getString(R.string.reset_guide), Toast.LENGTH_SHORT).show()
    }

    // ════════════════════════════════════════════════════════════════
    // Phase 5 · L5 feature 控制器（RokidLink 生命周期 → feature/RokidLinkController）
    // 门面签名不变，调用点零改动
    // ════════════════════════════════════════════════════════════════
    private val rokidLinkController by lazy { com.rokidlab.phone.feature.RokidLinkController(this) }

    private fun checkRokidLinkInstallation() = rokidLinkController.checkRokidLinkInstallation()
    private fun testAdbOnFreshLaunch() = rokidLinkController.testAdbOnFreshLaunch()
    private fun installRokidLinkToGlasses(onResult: ((Boolean) -> Unit)? = null) =
        rokidLinkController.installRokidLinkToGlasses(onResult)
    internal fun installRokidLinkForGuide(onResult: (Boolean) -> Unit) =
        rokidLinkController.installRokidLinkForGuide(onResult)
    internal fun openRokidLinkOnGlasses() = rokidLinkController.openRokidLinkOnGlasses()
    internal fun stopRokidLinkOnGlasses() = rokidLinkController.stopRokidLinkOnGlasses()
    internal fun reinstallRokidLinkOnGlasses(onDone: ((Boolean) -> Unit)? = null) =
        rokidLinkController.reinstallRokidLinkOnGlasses(onDone)
    internal fun autoStartRokidLink() = rokidLinkController.autoStartRokidLink()
    private fun ensureRokidLinkRunning() = rokidLinkController.ensureRokidLinkRunning()
    private fun stopRokidLinkNow() = rokidLinkController.stopRokidLinkNow()

    internal fun log(message: String) {
        android.util.Log.d("RokidLab", message)
        LogCollector.i("RokidLab", message)
        runOnUiThread {
            logMessages.add(message)
            if (logMessages.size > 100) logMessages.removeFirst()
        }
    }




    /** 打开错误日志导出对话框 */
    internal fun showExportLogDialog() {
        runOnUiThread {
            errorLogContent = LogCollector.getErrorLogText()
            errorLogSaved = false
            showErrorLogDialog = true
        }
    }

    /** 切换后台保活开关：开启启动前台保活服务（通知栏常驻），关闭停止 */
    internal fun toggleKeepAlive() {
        val app = application as LabApplication
        app.setKeepAliveEnabled(!app.keepAliveEnabled)
        val msg = if (app.keepAliveEnabled) getString(R.string.keep_alive_on) else getString(R.string.keep_alive_off)
        log("${getString(R.string.keep_alive_enabled)}: $msg")
    }

    /** 保存错误日志到文件并分享 */
    internal fun saveErrorLog() {
        runCatching {
            val intent = LogCollector.createShareIntent(this@MainActivity, errorsOnly = true)
            if (intent != null) {
                startActivity(Intent.createChooser(intent, getString(R.string.save_log)))
                errorLogSaved = true
                Toast.makeText(this@MainActivity, getString(R.string.save_log_done), Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@MainActivity, getString(R.string.save_log_failed), Toast.LENGTH_SHORT).show()
            }
        }.onFailure {
            Toast.makeText(this@MainActivity, getString(R.string.save_log_failed), Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 导出兼容性诊断报告并分享。
     *
     * 与「导出日志」的区别：日志回答「刚才发生了什么」，诊断报告回答「这台机器是什么配置、
     * 走到了哪一档」。投屏黑屏 / 手柄无响应这类问题，后者才是定位所需的输入，
     * 也是把「用户说不行」变成可复现线索的最短路径。
     */
    internal fun exportCompatDiagnostics() {
        runCatching {
            val report = com.rokidlab.phone.util.RomFingerprint.compatReport(this@MainActivity)
            val intent = LogCollector.createTextShareIntent(this@MainActivity, "RokidLab_Compat", report)
            if (intent != null) {
                startActivity(Intent.createChooser(intent, getString(R.string.export_compat_diag)))
                Toast.makeText(this@MainActivity, getString(R.string.save_compat_done), Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@MainActivity, getString(R.string.save_compat_failed), Toast.LENGTH_SHORT).show()
            }
        }.onFailure {
            LogCollector.e("RokidLab", "exportCompatDiagnostics failed", it)
            Toast.makeText(this@MainActivity, getString(R.string.save_compat_failed), Toast.LENGTH_SHORT).show()
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  运行前置条件（权限 / 蓝牙 / 待执行操作）
    // ════════════════════════════════════════════════════════════════
    internal fun runWithPrerequisites(action: () -> Unit) {
        if (pendingAction != null) return  // 已有待执行操作，避免竞态覆盖
        pendingAction = action
        proceedIfPrerequisitesReady()
    }

    /**
     * 在权限/蓝牙任一就绪后重新评估前置条件：
     * - 仍缺权限 -> 重新拉起权限请求
     * - 仍缺蓝牙 -> 重新拉起蓝牙开启
     * - 都就绪   -> 执行挂起动作
     * 任一前置被用户拒绝都会清空 pendingAction，避免永久死锁(A1)。
     */
    private fun proceedIfPrerequisitesReady() {
        when {
            !permissions.all(::hasPermission) -> permissionLauncher.launch(permissions)
            !isBluetoothEnabled() -> enableBluetoothLauncher.launch(Intent(android.bluetooth.BluetoothAdapter.ACTION_REQUEST_ENABLE))
            else -> consumePendingAction()
        }
    }

    private fun consumePendingAction() {
        val action = pendingAction ?: return
        pendingAction = null
        action()
        // 蓝牙权限就绪后重试 HID 注册
        try {
            (application as LabApplication).hidManager.retryRegisterApp()
        } catch (_: Exception) { }
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun isBluetoothEnabled(): Boolean {
        val manager = getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager ?: return false
        return manager.adapter?.isEnabled == true
    }

    // ════════════════════════════════════════════════════════════════
    //  屏幕镜像 / 手机投屏
    // ════════════════════════════════════════════════════════════════
    internal fun startScreenMirror() {
        // 先通过 CXR-L 启动眼镜端的 ScreenMirrorIntentActivity（触发 MediaProjection 权限）
        log(getString(R.string.log_starting_glasses_mirror))
        screenMirrorState = screenMirrorState.copy(connectionStatus = this@MainActivity.getString(R.string.starting_glasses))
        cxrL.launchApp("com.rokidlab.rokidlink", activityClass = ".ScreenMirrorIntentActivity") { launched ->
            if (launched) {
                log(getString(R.string.log_glasses_mirror_started))
                // 立刻进入镜像页：原先这里 `postDelayed(..., 2000)` 写死等 2 秒
                // 「等 CXR-L 蓝牙通信完成」，但此期间本页没有任何反馈，看起来像卡死。
                // 现把「等眼镜端接收页拉起」的宽限挪进 ScreenMirrorActivity ——
                // 那边已打开、界面会显示「正在启动眼镜端...」，并且能与路由解析并行。
                startActivity(ScreenMirrorActivity.createIntent(this@MainActivity))
            } else {
                log(getString(R.string.log_glasses_start_failed))
                screenMirrorState = screenMirrorState.copy(connectionStatus = this@MainActivity.getString(R.string.starting_glasses_failed))
            }
        }
    }

    internal fun startPhoneMirror() {
        if (isStartingPhoneMirror) return
        isStartingPhoneMirror = true
        log(getString(R.string.log_starting_phone_mirror))
        phoneMirrorState = phoneMirrorState.copy(connectionStatus = this@MainActivity.getString(R.string.starting_glasses))
        // 先提示用户权限用途
        Toast.makeText(this@MainActivity, this@MainActivity.getString(R.string.screen_record_permission), Toast.LENGTH_LONG).show()
        
        // Android 13+ 先检查通知权限（前台服务需要通知）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (!hasPermission(Manifest.permission.POST_NOTIFICATIONS)) {
                log(getString(R.string.log_requesting_notification_permission))
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        
        // 检查悬浮窗权限（OPPO/vivo 需要额外授权）
        if (!ManufacturerUtils.canDrawOverlays(this)) {
            log(getString(R.string.log_overlay_permission_guide))
            if (!checkOverlayPermissionForMirror()) {
                isStartingPhoneMirror = false
                return
            }
        }
        
        // 通过 CXR-L 启动眼镜端投屏接收页
        cxrL.launchApp("com.rokidlab.rokidlink", activityClass = ".PhoneMirrorActivity") { launched ->
            if (launched) {
                log(getString(R.string.log_glasses_started_request_permission))
                phoneMirrorState = phoneMirrorState.copy(rokidLinkRunning = true)
                // 请求 MediaProjection 权限，授权后 PhoneMirrorService 直接 Socket 连接
                val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                phoneMirrorProjectionLauncher.launch(projectionManager.createScreenCaptureIntent())
            } else {
                isStartingPhoneMirror = false
                log(getString(R.string.log_glasses_start_failed))
                phoneMirrorState = phoneMirrorState.copy(connectionStatus = this@MainActivity.getString(R.string.starting_glasses_failed))
            }
        }
    }

    internal fun stopPhoneMirror() {
        isStartingPhoneMirror = false
        log(getString(R.string.log_stopping_phone_mirror))
        // 停止手机端投屏服务（断 Socket 连接）
        stopService(Intent(this, PhoneMirrorService::class.java))
        phoneMirrorState = phoneMirrorState.copy(
            isMirroring = false,
            connectionStatus = ""
        )
        // 下发 stop_phone_mirror 让眼镜端关闭 PhoneMirrorActivity：
        //   该页设计为 socket 断开后保持前台等重连，不主动关闭则最后一帧画面会残留在眼镜上。
        //   不用 stopApp 整包杀 RokidLink：会被前台自动保活（ensureRokidLinkRunning）立刻重新拉起，
        //   体感就是"按了一次 Stop 却又要按第二次"。
        cxrL.stopPhoneMirrorOnGlasses()
    }

    // ════════════════════════════════════════════════════════════════
    //  本地 APK 安装（FileManager 入口）
    // ════════════════════════════════════════════════════════════════
    // 处理从FileManagerActivity传来的APK安装请求
    private fun handleIncomingApkInstall(intent: Intent?) {
        if (intent?.action == "com.rokidlab.phone.ACTION_INSTALL_APK") {
            val apkFilePath = intent.getStringExtra("apk_file_path")
            if (apkFilePath != null) {
                val apkFile = File(apkFilePath)
                if (apkFile.exists()) {
                    installApkFromFileManager(apkFile)
                }
            }
        }
    }
    
    // 从文件管理器安装APK
    private fun installApkFromFileManager(apkFile: File) {
        lifecycleScope.launch {
            isInstallingLocalApk = true
            localApkInstallProgress = 0
            localApkInstallStatus = getString(R.string.preparing_install)
            updateBusy(true)
            
            runCatching {
                localApkInstallStatus = getString(R.string.installing_apk)
                localApkInstallProgress = 20
                
                cxrL.installApk(apkFile) { installed ->
                    localApkInstallProgress = 90
                    if (installed) {
                        log(getString(R.string.install_completed))
                        localApkInstallStatus = getString(R.string.install_completed)
                        localApkInstallProgress = 100
                        localApkInstallStatus = getString(R.string.apk_install_completed, apkFile.name)
                    } else {
                        log(getString(R.string.install_failed_simple, "Installation failed"))
                        localApkInstallStatus = getString(R.string.install_failed_simple, "Installation failed")
                    }
                    updateBusy(false)
                    
                    scheduleClearLocalApkInstallState()
                }
            }.onFailure { error ->
                log(getString(R.string.apk_install_failed, error.message ?: error.javaClass.simpleName))
                localApkInstallStatus = getString(R.string.apk_install_failed, error.message ?: error.javaClass.simpleName)
                updateBusy(false)
                isInstallingLocalApk = false
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingApkInstall(intent)
    }

    internal fun startFileManager() {
        startActivity(FileManagerActivity.createIntent(this, useRealInstall = true))
    }

    // ════════════════════════════════════════════════════════════════
    //  国产手机兼容性设置
    // ════════════════════════════════════════════════════════════════

    /**
     * 检查并引导用户完成国产手机兼容性设置
     * - 电池优化白名单（后台保活）
     * - 自启动权限（广播接收器可靠触发）
     */
    private fun checkCompatibilitySettings() {
        // 兼容性引导已关闭，不再重复提示
        if (getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean(PREF_COMPAT_GUIDE_DISMISSED, false)) {
            Log.i(TAG, "Compatibility guide already dismissed, skipping")
            return
        }

        if (!ManufacturerUtils.isChineseRom()) {
            // 三星设备特有检测
            if (ManufacturerUtils.hasSamsungDeepSleepIssue()) {
                Log.i(TAG, "Samsung device with Deep Sleep issue detected, logging only")
                log(getString(R.string.log_samsung_deep_sleep))
            }
            Log.i(TAG, "Not a Chinese ROM, skipping compatibility checks")
            return
        }
        val manufacturer = ManufacturerUtils.getManufacturerDisplayName()
        Log.i(TAG, "Chinese ROM detected: $manufacturer")

        // 检查电池优化白名单
        if (!ManufacturerUtils.isIgnoringBatteryOptimizations(this)) {
            log(getString(R.string.log_battery_optimization_guide))
            showBatteryOptimizationDialog()
            // 同时也弹出自动启动引导（延迟执行，避免同时弹多个对话框）
            lifecycleScope.launch {
                delay(5000)
                showAutoStartDialog()
            }
        } else {
            log(getString(R.string.log_battery_optimization_granted))
            // 已在白名单中，弹出自动启动引导
            lifecycleScope.launch {
                delay(2000)
                showAutoStartDialog()
            }
        }
        
        // vivo 特有：提示 10 分钟后台限制
        if (ManufacturerUtils.hasVivoBackgroundHardLimit()) {
            lifecycleScope.launch {
                delay(8000)
                showVivoBackgroundLimitDialog()
            }
        }
    }

    /**
     * 弹出自动启动权限引导对话框（使用标准 AlertDialog，避免 Compose 上下文限制）
     */
    private var autoStartDialogShown = false
    
    private fun showAutoStartDialog() {
        if (autoStartDialogShown) return
        autoStartDialogShown = true
        runOnUiThread {
            android.app.AlertDialog.Builder(this@MainActivity)
                .setTitle(getString(R.string.auto_start_title))
                .setMessage(getString(R.string.auto_start_desc))
                .setCancelable(false)
                .setPositiveButton(getString(R.string.auto_start_guide)) { _, _ ->
                    ManufacturerUtils.openAutoStartSettings(this@MainActivity)
                    autoStartDialogShown = false
                    dismissCompatibilityGuide()
                }
                .setNegativeButton(getString(R.string.auto_start_skip)) { _, _ ->
                    log(getString(R.string.log_auto_start_skipped))
                    autoStartDialogShown = false
                    dismissCompatibilityGuide()
                }
                .show()
        }
    }
    
    /**
     * vivo/iQOO 10分钟后台限制引导（使用标准 AlertDialog）
     */
    private var vivoLimitDialogShown = false
    
    private fun showVivoBackgroundLimitDialog() {
        if (vivoLimitDialogShown) return
        vivoLimitDialogShown = true
        runOnUiThread {
            android.app.AlertDialog.Builder(this@MainActivity)
                .setTitle(getString(R.string.vivo_limit_title))
                .setMessage(getString(R.string.vivo_limit_desc))
                .setCancelable(false)
                .setPositiveButton(getString(R.string.dialog_got_it)) { _, _ ->
                    ManufacturerUtils.openPowerSavingSettings(this@MainActivity)
                    vivoLimitDialogShown = false
                    dismissCompatibilityGuide()
                }
                .show()
        }
    }

    /** 标记兼容性引导已完成，后续启动不再提示 */
    private fun dismissCompatibilityGuide() {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putBoolean(PREF_COMPAT_GUIDE_DISMISSED, true)
            .apply()
        Log.i(TAG, "Compatibility guide dismissed permanently")
    }

    /**
     * 弹出电池优化白名单引导对话框
     * 解决华为/小米等手机后台服务被强杀的问题
     */
    private var batteryOptimizationDialogShown = false

    private fun showBatteryOptimizationDialog() {
        if (batteryOptimizationDialogShown) return
        batteryOptimizationDialogShown = true
        lifecycleScope.launch {
            delay(2000) // 延迟弹出，避免干扰首次启动流程

            val manufacturer = ManufacturerUtils.getManufacturerDisplayName()
            val message = getString(R.string.compatibility_settings_desc, manufacturer)
            val desc = message + "\n\n" + getString(R.string.compatibility_settings_battery)

            runOnUiThread {
                android.app.AlertDialog.Builder(this@MainActivity)
                    .setTitle(getString(R.string.compatibility_settings_title))
                    .setMessage(desc)
                    .setCancelable(false)
                    .setPositiveButton(getString(R.string.battery_optimization_guide)) { _, _ ->
                        ManufacturerUtils.requestIgnoreBatteryOptimizations(this@MainActivity)
                        log(getString(R.string.log_battery_optimization_guide))
                        lifecycleScope.launch {
                            delay(1000)
                            showAutoStartDialog()
                        }
                    }
                    .setNegativeButton(getString(R.string.battery_optimization_skip)) { _, _ ->
                        log(getString(R.string.log_battery_optimization_skipped))
                        showAutoStartDialog()
                    }
                    .show()
            }
        }
    }

    /**
     * 检查悬浮窗权限（OPPO/vivo 投屏兼容）
     * 在启动手机投屏前检查
     */
    private fun checkOverlayPermissionForMirror(): Boolean {
        if (!ManufacturerUtils.isChineseRom()) return true
        if (ManufacturerUtils.canDrawOverlays(this)) return true
        if (getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean(PREF_OVERLAY_GUIDE_DISMISSED, false)) {
            Log.i(TAG, "Overlay guide already dismissed, skipping")
            return true
        }

        log(getString(R.string.log_overlay_permission_guide))
        runOnUiThread {
            android.app.AlertDialog.Builder(this@MainActivity)
                .setTitle(getString(R.string.overlay_permission_title))
                .setMessage(getString(R.string.overlay_permission_desc))
                .setCancelable(false)
                .setPositiveButton(getString(R.string.overlay_permission_guide)) { _, _ ->
                    ManufacturerUtils.openOverlaySettings(this@MainActivity)
                    log(getString(R.string.log_overlay_permission_guide))
                }
                .setNegativeButton(getString(R.string.overlay_permission_skip)) { _, _ ->
                    getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                        .edit()
                        .putBoolean(PREF_OVERLAY_GUIDE_DISMISSED, true)
                        .apply()
                    isStartingPhoneMirror = false
                    Log.i(TAG, "Overlay guide dismissed permanently")
                }
                .show()
        }
        return false
    }
}
