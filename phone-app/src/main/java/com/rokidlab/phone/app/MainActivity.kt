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
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.delay
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
    private enum class InstallStateSource { CACHED, VERIFIED }

    private lateinit var cxrL: CxrLHiRokidSession
    private lateinit var downloader: ApkDownloader
    private lateinit var iconLoader: IconLoader
    private lateinit var mediaLoader: MediaLoader
    private lateinit var installCache: UserInstallCache

    private var apps by mutableStateOf(emptyList<BrewApp>())
    private var busy by mutableStateOf(false)
    private var refreshing by mutableStateOf(false)
    private var installCheckTick by mutableStateOf(0)
    private val downloadProgress = mutableStateMapOf<String, Int>()
    private val downloadCancelJobs = mutableMapOf<String, Job>()
    private val phoneInstallStates = mutableStateMapOf<String, InstallState>()
    private val glassesInstallStates = mutableStateMapOf<String, InstallState>()
    private val glassesInstallStateSources = mutableMapOf<String, InstallStateSource>()
    private var pendingAction: (() -> Unit)? = null
    private var isCheckingRokidLink = false
    /** 用户是否在本会话中通过启动按键启动过 RokidLink */
    private var rokidLinkUserStarted = false
    /** 是否已对本会话做过 ADB 连通性检测（仅新鲜启动时一次） */
    private var rokidLinkAdbTested = false
    private var selfUpdateState by mutableStateOf(
        BrewSelfUpdateState(
            currentVersion = BuildConfig.VERSION_NAME,
            currentVersionCode = BuildConfig.VERSION_CODE.toLong(),
        ),
    )
    private var showUpdatePrompt by mutableStateOf(false)
    private var selectedHostApp by mutableStateOf(RokidHostApp.DEFAULT)
    private var cxrConnection by mutableStateOf(CxrConnectionState())
    private var phoneInstallRefreshGeneration = 0
    private var glassesInstallRefreshGeneration = 0
    private var showMirrorDialog by mutableStateOf(false)
    private var showRefreshDialog by mutableStateOf(false)
    private var refreshDialogSuccess by mutableStateOf(false)
    private var refreshDialogMessage by mutableStateOf("")
    private var mirrorSourceSelected by mutableStateOf(false)
    private var prerequisitesState by mutableStateOf(PrerequisitesState())
    private var screenMirrorState by mutableStateOf(ScreenMirrorState())
    private var phoneMirrorState by mutableStateOf(PhoneMirrorState())
    private var fileManagerState by mutableStateOf(FileManagerState())
    private var currentMirrorIndex by mutableStateOf(0)
    private var settingsReinstallError: String? = null
    // 日志列表，用于 UI 实时显示（最多保留 100 条）
    private val logMessages = mutableStateListOf<String>()
    // 错误日志导出
    private var showErrorLogDialog by mutableStateOf(false)
    private var errorLogContent by mutableStateOf("")
    private var errorLogSaved by mutableStateOf(false)
    
    // 兼容性引导
    private var showCompatibilityDialog by mutableStateOf(false)
    private val compatibilityPrefsName = "compatibility_dialog"
    private val EXTRA_HAS_SEEN_COMPAT_GUIDE = "has_seen_compatibility_guide"
    
    // 本地APK安装状态
    private var isInstallingLocalApk by mutableStateOf(false)
    private var localApkInstallProgress by mutableStateOf(0)
    private var localApkInstallStatus by mutableStateOf("")

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
                consumePendingAction()
            } else {
                log(getString(R.string.log_bluetooth_permission_denied))
                Toast.makeText(this, this.getString(R.string.bluetooth_permission_denied), Toast.LENGTH_LONG).show()
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
            if (isBluetoothEnabled()) consumePendingAction() else log(getString(R.string.log_bluetooth_not_enabled))
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

    private val apkPickerLauncher = registerForActivityResult(
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
            val wifiIp = app.phoneMirrorIp
            val wifiPort = app.phoneMirrorPort.toIntOrNull() ?: 7654
            Thread {
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
        )
        (application as LabApplication).setCxrL(cxrL)
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
        if (isServiceRunning(PhoneMirrorService::class.java)) {
            isStartingPhoneMirror = false
            phoneMirrorState = phoneMirrorState.copy(
                isMirroring = true,
                connectionStatus = getString(R.string.screen_projection)
            )
        }

        setContent {
            RokidLabTheme {
                val hostAppInstalled by produceState(false, selectedHostApp, installCheckTick) {
                    value = withContext(Dispatchers.IO) { cxrL.isHostAppInstalled(selectedHostApp) }
                }
                BrewPhoneApp(
                    state = StoreUiState(
                        apps = apps,
                        busy = busy,
                        refreshing = refreshing,
                        selectedHostApp = selectedHostApp,
                        hostAppInstalled = hostAppInstalled,
                        cxrConnection = cxrConnection,
                        downloadProgress = downloadProgress,
                        phoneInstallStates = phoneInstallStates,
                        glassesInstallStates = glassesInstallStates,
                        selfUpdateState = selfUpdateState,
                        prerequisites = prerequisitesState,
                        screenMirrorState = screenMirrorState,
                        phoneMirrorState = phoneMirrorState,
                        fileManagerState = fileManagerState,
                        showMirrorDialog = showMirrorDialog,
                        currentMirrorIndex = BrewIndex.getMirrorIndex(this@MainActivity),
                        currentLocale = com.rokidlab.phone.util.LocalizationManager.getCurrentLocaleCode(),
                        isInstallingLocalApk = isInstallingLocalApk,
                        localApkInstallProgress = localApkInstallProgress,
                        localApkInstallStatus = localApkInstallStatus,
                    ),
                    actions = StoreActions(
                        onRefresh = { refreshStoreIndex(manual = true) },
                        onHostAppSelected = ::selectRokidHostApp,
                        onGoToGuideStep1 = { goToGuideStep1() },
                        onAuthorize = { runWithPrerequisites { cxrL.requestAuthorization() } },
                        onInstall = { app, target ->
                            if (target == "glasses") {
                                runWithPrerequisites { installArtifact(app, target) }
                            } else {
                                installArtifact(app, target)
                            }
                        },
                        onCheckGlassesInstall = ::checkGlassesInstallStateIfNeeded,
                        onUninstall = { app, target ->
                            if (target == "glasses") {
                                runWithPrerequisites { uninstallArtifact(app, target) }
                            } else {
                                uninstallArtifact(app, target)
                            }
                        },
                        onSelfUpdate = { performSelfUpdate() },
                        onSwitchMirror = { showMirrorDialog = true },
                        onInstallApk = { apkPickerLauncher.launch("application/vnd.android.package-archive") },
                        onLaunch = { app, target -> launchApp(app, target) },
                        onSelectMirrorSource = { showMirrorDialog = true },
                        onMirrorSelected = { index ->
                            switchMirror(index)
                            showMirrorDialog = false
                        },
                        onDismissMirrorDialog = { showMirrorDialog = false },
                        onScreenMirrorIpChange = { (application as LabApplication).setScreenMirrorIp(it) },
                        onScreenMirrorConnect = { startScreenMirror() },
                        onScreenMirrorStart = { startScreenMirror() },
                        onScreenMirrorStop = { stopRokidLinkOnGlasses() },
                        onScreenMirrorInstallRokidLink = { installRokidLinkToGlasses() },
                        onScreenMirrorOpenRokidLink = { openRokidLinkOnGlasses() },
                        onScreenMirrorRetry = { },
                        onScreenMirrorBack = { },
                        onPhoneMirrorIpChange = { (application as LabApplication).setPhoneMirrorIp(it) },
                        onPhoneMirrorPortChange = { (application as LabApplication).setPhoneMirrorPort(it) },
                        onPhoneMirrorConnect = { startPhoneMirror() },
                        onPhoneMirrorStart = { 
                            if (phoneMirrorState.isMirroring) stopPhoneMirror() else startPhoneMirror()
                        },
                        onPhoneMirrorStop = { stopRokidLinkOnGlasses() },
                        onPhoneMirrorInstallRokidLink = { installRokidLinkToGlasses() },
                        onPhoneMirrorOpenRokidLink = { openRokidLinkOnGlasses() },
                        onPhoneMirrorRetry = { },
                        onPhoneMirrorBack = { },
                        onFileManagerIpChange = { (application as LabApplication).setFileManagerIp(it) },
                        onFileManagerConnect = { startFileManager() },
                        onFileManagerDisconnect = { },
                        onFileManagerStop = { stopRokidLinkOnGlasses() },
                        onFileManagerInstallRokidLink = { installRokidLinkToGlasses() },
                        onFileManagerOpenRokidLink = { openRokidLinkOnGlasses() },
                        onFileManagerRetry = { },
                        onFileManagerBack = { },
                        onFileManagerNavigateTo = { },
                        onFileManagerRefresh = { },
                        onFileManagerUploadFile = { },
                        onFileManagerDownloadFile = { },
                        onFileManagerDeleteFile = { },
                        onFileManagerCreateFolder = { },
                        onFileManagerRenameFile = { _, _ -> },
                        onCancelDownload = { key -> cancelDownload(key) },
                        onExitApp = { finishAndRemoveTask() },
                        onSettingsReinstallRokidLink = { reinstallRokidLinkOnGlasses() },
                        onExportLog = { showExportLogDialog() },
                        onLaunchGlassAppViaSdk = { pkg, activity ->
                            cxrL.launchApp(
                                packageName = pkg,
                                activityClass = activity,
                                onLaunchResult = { success ->
                                    runOnUiThread {
                                        val msg = if (success) "$pkg 启动成功" else "$pkg 启动失败"
                                        log(msg)
                                    }
                                }
                            )
                        },
                        onSendKeyButtonConfig = { shortPkg, shortActivity, longPkg, longActivity, onDone ->
                            cxrL.sendKeyButtonConfig(
                                shortPkg = shortPkg,
                                shortActivity = shortActivity,
                                longPkg = longPkg,
                                longActivity = longActivity,
                                onResult = { success ->
                                    runOnUiThread {
                                        val msg = if (success) "按键配置已发送到眼镜" else "按键配置发送失败"
                                        log(msg)
                                        onDone(success)
                                    }
                                },
                            )
                        },
                        onSwitchLanguage = { code ->
                            com.rokidlab.phone.util.LocalizationManager.setLocale(this@MainActivity, code)
                            // Recreate activity to apply language
                            recreate()
                        },
                        onSendWifiConfig = { ssid, password, onDone ->
                            cxrL.sendWifiConfig(ssid, password) { success, errorMsg ->
                                if (success) {
                                    prerequisitesState = prerequisitesState.copy(wifiConfigured = true)
                                    autoStartRokidLink()
                                }
                                onDone(success, errorMsg)
                            }
                        },
                        onInstallLink = { onDone ->
                            installRokidLinkForGuide { success ->
                                if (success) {
                                    prerequisitesState = prerequisitesState.copy(rokidLinkInstalled = true)
                                }
                                onDone(success)
                            }
                        },
                        onSkipGuideStep = {
                            when (prerequisitesState.currentGuideStep) {
                                GuideStep.INSTALL_LINK -> {
                                    prerequisitesState = prerequisitesState.copy(rokidLinkInstalled = true)
                                }
                                GuideStep.CONFIGURE_WIFI -> {
                                    prerequisitesState = prerequisitesState.copy(wifiConfigured = true)
                                    autoStartRokidLink()
                                }
                                else -> {
                                    // 其他步骤不支持跳过
                                }
                            }
                        },
                    ),
                    iconLoader = iconLoader,
                    mediaLoader = mediaLoader,
                    app = application as LabApplication,
                )
                if (showUpdatePrompt && selfUpdateState.available) {
                    UpdateDialog(
                        version = selfUpdateState.latestVersion.ifBlank { "latest" },
                        downloading = selfUpdateState.downloading,
                        downloadPercent = selfUpdateState.downloadPercent,
                        onUpdate = { performSelfUpdate() },
                        onDismiss = { showUpdatePrompt = false },
                        onCancelDownload = { cancelDownload("brew-self-update") },
                    )
                }
                if (showRefreshDialog) {
                    BrewDialog(
                        onDismiss = { showRefreshDialog = false },
                        title = if (refreshDialogSuccess) this@MainActivity.getString(R.string.refresh_success) else this@MainActivity.getString(R.string.refresh_failed),
                        color = if (refreshDialogSuccess) BrewSuccess else BrewWarning,
                    ) {
                        BrewDialogContent {
                            Text(refreshDialogMessage, color = BrewText, fontSize = 13.sp)
                        }
                    }
                }
                if (showErrorLogDialog) {
                    BrewDialog(
                        onDismiss = { showErrorLogDialog = false; errorLogSaved = false },
                        title = this@MainActivity.getString(R.string.error_log_title),
                        color = BrewWarning,
                    ) {
                        BrewDialogContent {
                            Text(this@MainActivity.getString(R.string.error_log_hint), color = BrewMuted, fontSize = 12.sp)
                            Spacer(Modifier.height(12.dp))
                            Column(
                                modifier = Modifier.verticalScroll(rememberScrollState()).weight(1f, fill = false).fillMaxWidth()
                            ) {
                                Text(errorLogContent, color = BrewText.copy(alpha = 0.7f), fontSize = 11.sp)
                            }
                            Spacer(Modifier.height(16.dp))
                            BrewButton(
                                text = if (errorLogSaved) this@MainActivity.getString(R.string.save_log_done) else this@MainActivity.getString(R.string.save_log),
                                onClick = {
                                    saveErrorLog()
                                },
                                modifier = Modifier.fillMaxWidth().height(44.dp),
                            )
                        }
                    }
                }
                if (showMirrorDialog) {
                    MirrorSourceDialog(
                        currentIndex = BrewIndex.getMirrorIndex(this@MainActivity),
                        onSelect = { index ->
                            switchMirror(index)
                            showMirrorDialog = false
                        },
                        onDismiss = { showMirrorDialog = false },
                    )
                }
            }
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

        // 检查国产手机兼容性设置（电池优化白名单、自启动权限等）
        checkCompatibilitySettings()
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
                try {
                    val wlpClass = Class.forName("android.view.WindowLayoutParams")
                    val setBalanced = wlpClass.getMethod("setFrameRatePowerSavingsBalanced", Boolean::class.java)
                    setBalanced.invoke(attributes, false)
                } catch (_: Exception) { }
            }
            window.attributes = attributes
        }
        if (Build.VERSION.SDK_INT >= 35) {
            try {
                val setFrameRate = View::class.java.getMethod("setRequestedFrameRate", Int::class.java)
                val categoryHigh = View::class.java.getField("REQUESTED_FRAME_RATE_CATEGORY_HIGH").getInt(null)
                setFrameRate.invoke(window.decorView, categoryHigh)
            } catch (_: Exception) { }
        }
    }

    private fun refreshStoreIndex(manual: Boolean) {
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

    private fun switchMirror(index: Int) {
        BrewIndex.setMirror(this, index)
        prerequisitesState = prerequisitesState.copy(mirrorSourceSelected = true)
        log(getString(R.string.log_switched_mirror, BrewIndex.getCurrentMirror().name))
        // 只在非引导模式下立即刷新（引导中的刷新由 LaunchedEffect 在引导完成后统一处理）
        if (prerequisitesState.canInstallApps) {
            refreshStoreIndex(manual = true)
        }
    }

    private fun installLocalApkToGlasses(uri: Uri) {
        if (busy || isInstallingLocalApk) return
        runWithPrerequisites {
            lifecycleScope.launch {
                isInstallingLocalApk = true
                localApkInstallProgress = 0
                localApkInstallStatus = getString(R.string.preparing_install)
                updateBusy(true)
                
                runCatching {
                    val input = contentResolver.openInputStream(uri)
                        ?: throw IllegalStateException("Cannot open APK file")
                    
                    localApkInstallStatus = getString(R.string.downloading_apk)
                    localApkInstallProgress = 20
                    
                    val tempFile = File(cacheDir, "local_install_${System.currentTimeMillis()}.apk")
                    FileOutputStream(tempFile).use { output ->
                        input.copyTo(output)
                    }
                    input.close()
                    
                    localApkInstallStatus = getString(R.string.installing_apk)
                    localApkInstallProgress = 50
                    
                    log(getString(R.string.installing_apk))
                    cxrL.installApk(tempFile) { installed ->
                        localApkInstallProgress = 90
                        if (installed) {
                            log(getString(R.string.install_completed))
                            localApkInstallStatus = getString(R.string.install_completed)
                            localApkInstallProgress = 100
                        } else {
                            log(getString(R.string.install_failed_simple, "Unknown"))
                            localApkInstallStatus = getString(R.string.install_failed_simple, "Unknown")
                        }
                        tempFile.delete()
                        updateBusy(false)
                        
                        // 3秒后清除状态
                        Thread {
                            Thread.sleep(3000)
                            runOnUiThread {
                                isInstallingLocalApk = false
                                localApkInstallProgress = 0
                                localApkInstallStatus = ""
                            }
                        }.start()
                    }
                }.onFailure { error ->
                    log(getString(R.string.apk_install_failed, error.message ?: error.javaClass.simpleName))
                    localApkInstallStatus = getString(R.string.apk_install_failed, error.message ?: error.javaClass.simpleName)
                    updateBusy(false)
                    isInstallingLocalApk = false
                }
            }
        }
    }

    private fun performSelfUpdate() {
        if (selfUpdateState.downloading) return
        val url = selfUpdateState.apkUrl
        if (url.isBlank()) {
            log(getString(R.string.log_update_failed_no_url))
            return
        }
        // 清理旧更新 APK
        runCatching { File(cacheDir, "RokidLab-update.apk").delete() }
        selfUpdateState = selfUpdateState.copy(downloading = true, downloadPercent = 0)
        downloadProgress["brew-self-update"] = 0
        val version = selfUpdateState.latestVersion.ifBlank { "latest" }
        val job = lifecycleScope.launch {
            runCatching {
                log(getString(R.string.log_downloading_rokidlab, version))
                val file = downloader.download(url, "RokidLab-update.apk") { percent ->
                    runOnUiThread {
                        downloadProgress["brew-self-update"] = percent
                        selfUpdateState = selfUpdateState.copy(downloadPercent = percent)
                    }
                }
                downloadProgress["brew-self-update"] = 100
                selfUpdateState = selfUpdateState.copy(downloading = false, downloadPercent = 100)
                log(getString(R.string.log_downloaded_bytes, file.length()))
                val ok = withContext(Dispatchers.IO) {
                    PhonePackageInstallHelper.requestInstall(this@MainActivity, file, ::log)
                }
                if (!ok) {
                    selfUpdateState = selfUpdateState.copy(downloading = false)
                }
                downloadProgress.remove("brew-self-update")
                downloadCancelJobs.remove("brew-self-update")
            }.onFailure { error ->
                log(getString(R.string.log_update_failed, error.message ?: error.javaClass.simpleName))
                downloadProgress.remove("brew-self-update")
                downloadCancelJobs.remove("brew-self-update")
                selfUpdateState = selfUpdateState.copy(downloading = false)
            }
        }
        downloadCancelJobs["brew-self-update"] = job
    }

    override fun onDestroy() {
        // 退出时清理日志，避免下次打开看到旧日志
        LogCollector.clear()
        cxrL.cleanup()
        (application as LabApplication).hidManager.destroy()
        super.onDestroy()
    }

    // ── 授权结果由 authLauncher (registerForActivityResult) 处理，无需 onActivityResult ──

    private fun checkGlassesInstallStateIfNeeded(app: BrewApp) {
        val artifact = app.artifactFor("glasses") ?: return
        val packageName = artifact.packageName?.takeIf { it.isNotBlank() } ?: return
        if (glassesInstallStateSources[packageName] == InstallStateSource.VERIFIED) return

        cachedGlassesInstallState(app, artifact)?.let { cachedState ->
            setGlassesInstallState(packageName, cachedState, InstallStateSource.CACHED)
        }

        if (busy || !cxrL.hasAuthorization()) return
        refreshGlassesInstallStates(listOf(app))
    }

    private fun refreshCachedGlassesInstallStates(targetApps: List<BrewApp> = apps) {
        val knownPackages = targetApps
            .mapNotNull { it.artifactFor("glasses")?.packageName?.takeIf(String::isNotBlank) }
            .toSet()
        glassesInstallStates.keys
            .filterNot(knownPackages::contains)
            .forEach(::removeGlassesInstallState)
        glassesInstallStateSources.keys
            .filterNot(knownPackages::contains)
            .forEach(glassesInstallStateSources::remove)

        targetApps.forEach { app ->
            val artifact = app.artifactFor("glasses") ?: return@forEach
            val packageName = artifact.packageName?.takeIf { it.isNotBlank() } ?: return@forEach
            if (glassesInstallStateSources[packageName] == InstallStateSource.VERIFIED) return@forEach
            cachedGlassesInstallState(app, artifact)?.let { state ->
                setGlassesInstallState(packageName, state, InstallStateSource.CACHED)
            }
        }
    }

    private fun cachedGlassesInstallState(app: BrewApp, artifact: BrewArtifact): InstallState? {
        val packageName = artifact.packageName?.takeIf { it.isNotBlank() } ?: return null
        val record = installCache.getGlasses(packageName) ?: return null
        if (!record.versionKnown) return InstallState.INSTALLED_UNKNOWN_VERSION
        val registryVersionCode = artifact.versionCode
        if (registryVersionCode != null && record.versionCode != null) {
            return if (record.versionCode < registryVersionCode) InstallState.UPDATE_AVAILABLE else InstallState.INSTALLED
        }
        val cachedVersionName = record.versionName?.takeIf { it.isNotBlank() }
        return if (cachedVersionName != null && cachedVersionName != app.version) {
            InstallState.UPDATE_AVAILABLE
        } else {
            InstallState.INSTALLED
        }
    }

    private fun setGlassesInstallState(
        packageName: String,
        state: InstallState,
        source: InstallStateSource,
    ) {
        glassesInstallStates[packageName] = state
        glassesInstallStateSources[packageName] = source
    }

    private fun removeGlassesInstallState(packageName: String) {
        glassesInstallStates.remove(packageName)
        glassesInstallStateSources.remove(packageName)
    }

    private fun refreshGlassesInstallStates(targetApps: List<BrewApp> = apps) {
        val appsByPackage = targetApps
            .mapNotNull { app ->
                val artifact = app.artifactFor("glasses") ?: return@mapNotNull null
                val packageName = artifact.packageName?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                packageName to (app to artifact)
            }
            .toMap()
        val packageNames = appsByPackage.keys.toList()
        if (packageNames.isEmpty() || !cxrL.hasAuthorization()) return

        val generation = ++glassesInstallRefreshGeneration

        cxrL.queryInstalledApps(
            packageNames = packageNames,
            onResult = { packageName, installed ->
                if (generation != glassesInstallRefreshGeneration) return@queryInstalledApps
                val appAndArtifact = appsByPackage[packageName]
                if (installed && appAndArtifact != null) {
                    val (app, artifact) = appAndArtifact
                    if (installCache.getGlasses(packageName) == null) {
                        installCache.recordGlassesDiscovered(app, artifact)
                    }
                    setGlassesInstallState(
                        packageName,
                        cachedGlassesInstallState(app, artifact) ?: InstallState.INSTALLED_UNKNOWN_VERSION,
                        InstallStateSource.VERIFIED,
                    )
                } else {
                    installCache.removeGlasses(packageName)
                    setGlassesInstallState(packageName, InstallState.NOT_INSTALLED, InstallStateSource.VERIFIED)
                }
                installCheckTick += 1
            },
            onComplete = {
                if (generation == glassesInstallRefreshGeneration) {
                    log(getString(R.string.log_glasses_install_refreshed))
                }
            },
        )
    }

    private fun refreshPhoneInstallStates(targetApps: List<BrewApp> = apps) {
        val artifacts = targetApps
            .mapNotNull { it.artifactFor("phone") }
            .filter { !it.packageName.isNullOrBlank() }
            .distinctBy { it.packageName }
        val generation = ++phoneInstallRefreshGeneration
        if (artifacts.isEmpty()) {
            phoneInstallStates.clear()
            return
        }

        lifecycleScope.launch {
            val states = withContext(Dispatchers.IO) {
                artifacts.associate { artifact ->
                    artifact.packageName.orEmpty() to installStateFor(artifact)
                }
            }
            if (generation != phoneInstallRefreshGeneration) return@launch
            val stalePackages = phoneInstallStates.keys.filterNot(states::containsKey)
            stalePackages.forEach(phoneInstallStates::remove)
            states.forEach { (packageName, state) ->
                if (phoneInstallStates[packageName] != state) {
                    phoneInstallStates[packageName] = state
                }
            }
        }
    }

    private fun launchApp(app: BrewApp, target: String) {
        val artifact = app.artifactFor(target)
        val packageName = artifact?.packageName?.takeIf { it.isNotBlank() }
        if (packageName == null) {
            Toast.makeText(this@MainActivity, this@MainActivity.getString(R.string.cannot_get_package, app.name), Toast.LENGTH_SHORT).show()
            return
        }

        if (target == "glasses") {
            runWithPrerequisites {
                cxrL.launchApp(packageName) { launched ->
                    if (launched) {
                        log(getString(R.string.log_launched_glasses, app.name))
                    } else {
                        Toast.makeText(this@MainActivity, this@MainActivity.getString(R.string.cannot_launch_glasses, app.name), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        } else {
            runCatching {
                val intent = packageManager.getLaunchIntentForPackage(packageName)
                if (intent != null) {
                    startActivity(intent)
                    log(getString(R.string.log_launched_phone, app.name))
                } else {
                    Toast.makeText(this@MainActivity, this@MainActivity.getString(R.string.app_not_installed_on_phone, app.name), Toast.LENGTH_SHORT).show()
                }
            }.onFailure { error ->
                Toast.makeText(this@MainActivity, this@MainActivity.getString(R.string.launch_failed, app.name, error.message), Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun installArtifact(app: BrewApp, target: String) {
        if (busy) return
        val artifact = app.artifactFor(target)
        if (artifact == null) {
            Toast.makeText(this@MainActivity, this@MainActivity.getString(R.string.no_build_for_target, app.name, target), Toast.LENGTH_SHORT).show()
            return
        }
        if (target == "glasses" && !cxrL.ensureGlassesOperationReady()) return

        val progressKey = "${app.id}:$target"
        val job = lifecycleScope.launch {
            updateBusy(true)
            downloadProgress[progressKey] = 0
            runCatching {
                val fileName = "${app.id}-${target}-${app.version}.apk"
                log(getString(R.string.log_downloading_apk, target, app.name))
                val file = downloader.download(artifact.url, fileName, artifact.sha256) { progress ->
                    downloadProgress[progressKey] = progress
                    if (progress % 25 == 0) log(getString(R.string.log_download_progress, target, progress))
                }
                downloadProgress[progressKey] = 100
                log(getString(R.string.log_downloaded_kb, file.name, file.length() / 1024))
                if (target == "glasses") {
                    // 传包名绕过 APK 头读取（兼容部分国产手机 getPackageArchiveInfo 返回 null）
                    val pkg = artifact.packageName?.takeIf { it.isNotBlank() }
                    if (pkg != null) {
                        cxrL.installApk(file, pkg) { installed ->
                            if (installed) {
                                installCache.recordGlassesInstall(app, artifact)
                                setGlassesInstallState(
                                    pkg,
                                    cachedGlassesInstallState(app, artifact) ?: InstallState.INSTALLED,
                                    InstallStateSource.VERIFIED,
                                )
                                Toast.makeText(this@MainActivity, this@MainActivity.getString(R.string.install_success_toast, app.name), Toast.LENGTH_SHORT).show()
                            } else {
                                installCache.removeGlasses(pkg)
                                setGlassesInstallState(pkg, InstallState.NOT_INSTALLED, InstallStateSource.VERIFIED)
                            }
                            installCheckTick += 1
                            downloadProgress.remove(progressKey)
                            downloadCancelJobs.remove(progressKey)
                            updateBusy(false)
                        }
                    } else {
                        // 没有包名信息时降级到旧方式（从 APK 头读取）
                        cxrL.installApk(file) { installed ->
                            if (installed) {
                                Toast.makeText(this@MainActivity, this@MainActivity.getString(R.string.install_success_toast, app.name), Toast.LENGTH_SHORT).show()
                            }
                            downloadProgress.remove(progressKey)
                            downloadCancelJobs.remove(progressKey)
                            updateBusy(false)
                        }
                    }
                } else {
                    updateBusy(false)
                    downloadProgress.remove(progressKey)
                    downloadCancelJobs.remove(progressKey)
                    PhonePackageInstallHelper.requestInstall(this@MainActivity, file, ::log)
                }
            }.onFailure { error ->
                log(getString(R.string.log_install_failed, error.message ?: error.javaClass.simpleName))
                LogCollector.e("Install", getString(R.string.log_install_failed, error.message ?: error.javaClass.simpleName), error)
                Toast.makeText(this@MainActivity, this@MainActivity.getString(R.string.install_failed, app.name, error.message ?: error.javaClass.simpleName), Toast.LENGTH_LONG).show()
                downloadProgress.remove(progressKey)
                downloadCancelJobs.remove(progressKey)
                updateBusy(false)
                // 安装异常时自动弹出错误报告
                showExportLogDialog()
            }
        }
        downloadCancelJobs[progressKey] = job
    }

    private fun uninstallArtifact(app: BrewApp, target: String) {
        if (busy) return
        val artifact = app.artifactFor(target)
        val packageName = artifact?.packageName?.takeIf { it.isNotBlank() }
        if (artifact == null || packageName == null) {
            Toast.makeText(this@MainActivity, this@MainActivity.getString(R.string.no_package_for_target, app.name, target), Toast.LENGTH_SHORT).show()
            return
        }

        if (target == "glasses") {
            cxrL.uninstallApp(packageName) { uninstalled ->
                if (uninstalled) {
                    installCache.removeGlasses(packageName)
                    setGlassesInstallState(packageName, InstallState.NOT_INSTALLED, InstallStateSource.VERIFIED)
                    installCheckTick += 1
                    runOnUiThread { Toast.makeText(this@MainActivity, this@MainActivity.getString(R.string.uninstall_success_toast, app.name), Toast.LENGTH_SHORT).show() }
                } else {
                    runOnUiThread { Toast.makeText(this@MainActivity, this@MainActivity.getString(R.string.uninstall_failed_toast, app.name), Toast.LENGTH_SHORT).show() }
                }
            }
        } else {
            PhonePackageInstallHelper.requestUninstall(this, packageName, app.name, ::log)
        }
    }

    private fun updateBusy(value: Boolean) {
        runOnUiThread {
            busy = value
            if (!value) downloadProgress.clear()
        }
    }

    private fun cancelDownload(key: String) {
        downloadCancelJobs[key]?.cancel()
        downloadCancelJobs.remove(key)
        downloadProgress.remove(key)
        if (key == "brew-self-update") {
            selfUpdateState = selfUpdateState.copy(downloading = false)
        }
        log(getString(R.string.log_download_cancelled, key))
    }

    private fun checkRokidLinkInstallation() {
        val app = application as LabApplication
        // 跳过持久化缓存兜底——每次都等 SDK 查询结果，避免卸载后仍显示"已安装"
        
        // 运行时缓存：有结果直接用
        if (app.rokidLinkInstalled != null) {
            screenMirrorState = screenMirrorState.copy(rokidLinkInstalled = app.rokidLinkInstalled)
            phoneMirrorState = phoneMirrorState.copy(rokidLinkInstalled = app.rokidLinkInstalled)
            fileManagerState = fileManagerState.copy(rokidLinkInstalled = app.rokidLinkInstalled)
            return
        }
        
        // SDK 查询
        if (!cxrL.hasAuthorization()) return
        
        if (isCheckingRokidLink) return
        isCheckingRokidLink = true
        
        lifecycleScope.launch {
            cxrL.queryInstalledApps(
                packageNames = listOf("com.rokidlab.rokidlink"),
                onResult = { packageName, installed ->
                    app.setRokidLinkInstalled(installed)
                    screenMirrorState = screenMirrorState.copy(rokidLinkInstalled = installed)
                    phoneMirrorState = phoneMirrorState.copy(rokidLinkInstalled = installed)
                    fileManagerState = fileManagerState.copy(rokidLinkInstalled = installed)
                    if (!installed) {
                        // 未安装时重置 running 状态
                        screenMirrorState = screenMirrorState.copy(rokidLinkRunning = false)
                        phoneMirrorState = phoneMirrorState.copy(rokidLinkRunning = false)
                        fileManagerState = fileManagerState.copy(rokidLinkRunning = false)
                    }
                },
                onComplete = {
                    isCheckingRokidLink = false
                }
            )
        }
    }

    /**
     * 测试本机能否通过 ADB (TCP 5555) 连通眼镜。
     * 仅在新启动会话中调用一次，连通则视为 RokidLink 已在眼镜端运行。
     */
    private fun testAdbOnFreshLaunch() {
        if (rokidLinkAdbTested || rokidLinkUserStarted) return
        rokidLinkAdbTested = true
        val app = application as LabApplication
        // 使用任一模块配置的眼镜 IP（默认 192.168.1.168）
        val ip = app.phoneMirrorIp.ifBlank {
            app.fileManagerIp.ifBlank {
                app.screenMirrorIp
            }
        }
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val socket = java.net.Socket()
                socket.connect(java.net.InetSocketAddress(ip, 5555), 3000)
                socket.close()
                // ADB 连通 → 认为眼镜端 RokidLink 已在运行
                withContext(Dispatchers.Main) {
                    log(getString(R.string.log_rokidlink_launched))
                    screenMirrorState = screenMirrorState.copy(rokidLinkRunning = true)
                    phoneMirrorState = phoneMirrorState.copy(rokidLinkRunning = true)
                    fileManagerState = fileManagerState.copy(rokidLinkRunning = true)
                }
            } catch (_: Exception) {
                // ADB 不通 → 眼镜不在线，保持未运行状态
            }
        }
    }

    private fun installRokidLinkToGlasses() {
        runWithPrerequisites {
            lifecycleScope.launch {
                updateBusy(true)
                log(getString(R.string.log_installing_rokidlink))
                screenMirrorState = screenMirrorState.copy(isInstallingRokidLink = true)
                phoneMirrorState = phoneMirrorState.copy(isInstallingRokidLink = true)
                fileManagerState = fileManagerState.copy(isInstallingRokidLink = true)

                runCatching {
                    val apkInputStream = assets.open("RokidLink.apk")
                    val tempFile = File(cacheDir, "RokidLink.apk")
                    apkInputStream.use { input ->
                        tempFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    cxrL.installApk(tempFile) { installed ->
                        runOnUiThread {
                            screenMirrorState = screenMirrorState.copy(
                                rokidLinkInstalled = installed,
                                isInstallingRokidLink = false
                            )
                            phoneMirrorState = phoneMirrorState.copy(
                                rokidLinkInstalled = installed,
                                isInstallingRokidLink = false
                            )
                            fileManagerState = fileManagerState.copy(
                                rokidLinkInstalled = installed,
                                isInstallingRokidLink = false
                            )
                            (application as LabApplication).setRokidLinkInstalled(installed)
                            updateBusy(false)
                            if (installed) {
                                settingsReinstallError = null
                                log(getString(R.string.log_rokidlink_installed))
                                Toast.makeText(this@MainActivity, getString(R.string.toast_rokidlink_installed), Toast.LENGTH_SHORT).show()
                            } else {
                                settingsReinstallError = getString(R.string.log_rokidlink_install_failed)
                                log(settingsReinstallError!!)
                                Toast.makeText(this@MainActivity, getString(R.string.toast_rokidlink_install_failed), Toast.LENGTH_SHORT).show()
                            }
                        }
                        tempFile.delete()
                    }
                }.onFailure { e ->
                    settingsReinstallError = this@MainActivity.getString(R.string.install_failed_simple, e.message)
                    log(settingsReinstallError!!)
                    Toast.makeText(this@MainActivity, this@MainActivity.getString(R.string.install_failed_simple, e.message), Toast.LENGTH_LONG).show()
                    screenMirrorState = screenMirrorState.copy(isInstallingRokidLink = false)
                    phoneMirrorState = phoneMirrorState.copy(isInstallingRokidLink = false)
                    fileManagerState = fileManagerState.copy(isInstallingRokidLink = false)
                    updateBusy(false)
                    // 安装异常时自动弹出错误报告
                    showExportLogDialog()
                }
            }
        }
    }

    /** 引导流程中安装 RokidLink 到眼镜（带结果回调） */
    private fun installRokidLinkForGuide(onResult: (Boolean) -> Unit) {
        lifecycleScope.launch {
            updateBusy(true)
            android.util.Log.i("RokidLinkInstall", "=== 开始安装 RokidLink 到眼镜 ===")
            log(getString(R.string.log_installing_rokidlink))
            runCatching {
                android.util.Log.i("RokidLinkInstall", "正在从 assets 读取 RokidLink.apk")
                val apkInputStream = assets.open("RokidLink.apk")
                val tempFile = File(cacheDir, "RokidLink.apk")
                val size = apkInputStream.available()
                android.util.Log.i("RokidLinkInstall", "APK 大小: ${size / 1024} KB")
                apkInputStream.use { input ->
                    tempFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                android.util.Log.i("RokidLinkInstall", "临时文件已准备: ${tempFile.absolutePath}")
                android.util.Log.i("RokidLinkInstall", "调用 cxrL.installApk()...")
                cxrL.installApk(tempFile) { installed ->
                    if (installed) {
                        android.util.Log.i("RokidLinkInstall", "=== 安装成功 ===")
                        log(getString(R.string.log_rokidlink_installed))
                        (application as LabApplication).setRokidLinkInstalled(true)
                        // 安装后自动启动 RokidLink，使其 KeyButtonService 开始运行并 subscribe 消息
                        android.util.Log.i("RokidLinkInstall", "正在启动 RokidLink...")
                        cxrL.launchApp("com.rokidlab.rokidlink", onLaunchResult = { launched ->
                            runOnUiThread {
                                updateBusy(false)
                                if (launched) {
                                    android.util.Log.i("RokidLinkInstall", "RokidLink 启动成功")
                                } else {
                                    android.util.Log.w("RokidLinkInstall", "RokidLink 启动失败，WiFi 配置可能不可用")
                                }
                                onResult(true)
                            }
                        })
                    } else {
                        runOnUiThread {
                            updateBusy(false)
                            android.util.Log.w("RokidLinkInstall", "=== 安装失败 ===")
                            log(getString(R.string.log_rokidlink_install_failed))
                            onResult(false)
                        }
                    }
                    tempFile.delete()
                }
            }.onFailure { e ->
                updateBusy(false)
                android.util.Log.e("RokidLinkInstall", "安装异常: ${e.javaClass.simpleName}: ${e.message}")
                log(getString(R.string.install_failed_simple, e.message))
                onResult(false)
            }
        }
    }

    private var isOpeningRokidLink = false

    /**
     * 启动 RokidLink：用户按键启动后整个会话期间都视为已启动，
     * 不再受 CXR-L 连接状态影响。停止键是唯一清除途径。
     */
    private fun openRokidLinkOnGlasses() {
        if (isOpeningRokidLink) return
        isOpeningRokidLink = true
        // 标记用户已在本会话中启动，之后保持运行状态
        rokidLinkUserStarted = true
        screenMirrorState = screenMirrorState.copy(rokidLinkRunning = true)
        phoneMirrorState = phoneMirrorState.copy(rokidLinkRunning = true)
        fileManagerState = fileManagerState.copy(rokidLinkRunning = true)
        log(getString(R.string.log_opening_rokidlink))
        // 仍然尝试启动眼镜端应用，但无论成功失败都不影响 running 状态
        cxrL.launchApp(
            packageName = "com.rokidlab.rokidlink",
            activityClass = ".MainActivity",
            onLaunchResult = { success ->
                isOpeningRokidLink = false
                if (success) {
                    log(getString(R.string.log_rokidlink_launched))
                } else {
                    log(getString(R.string.log_rokidlink_launch_failed))
                }
            }
        )
    }

    /**
     * 停止 RokidLink：直接停止眼镜端应用并清除运行状态。
     * 停止后需用户再次点击启动按键才会重新标记为运行中。
     */
    private fun stopRokidLinkOnGlasses() {
        rokidLinkUserStarted = false
        runWithPrerequisites {
            log(getString(R.string.log_closing_rokidlink))
            cxrL.stopApp(
                packageName = "com.rokidlab.rokidlink",
                onStopResult = { success ->
                    if (success) {
                        log(getString(R.string.log_rokidlink_closed))
                    } else {
                        log(getString(R.string.log_rokidlink_close_failed))
                    }
                    screenMirrorState = screenMirrorState.copy(rokidLinkRunning = false)
                    phoneMirrorState = phoneMirrorState.copy(rokidLinkRunning = false)
                    fileManagerState = fileManagerState.copy(rokidLinkRunning = false)
                }
            )
        }
    }

    private fun reinstallRokidLinkOnGlasses() {
        settingsReinstallError = null
        rokidLinkUserStarted = false
        runWithPrerequisites {
            lifecycleScope.launch {
                // 先停止运行中的 RokidLink
                log(getString(R.string.log_closing_rokidlink))
                cxrL.stopApp(
                    packageName = "com.rokidlab.rokidlink",
                    onStopResult = { success ->
                        log(if (success) getString(R.string.log_rokidlink_closed) else getString(R.string.log_rokidlink_close_failed))
                    },
                )

                // 无论 stop 成功与否，都先卸载旧版本再重新安装
                delay(300)
                log(getString(R.string.log_uninstalling_rokidlink))
                cxrL.uninstallApp(
                    packageName = "com.rokidlab.rokidlink",
                    onUninstallResult = { uninstalled ->
                        log(if (uninstalled) getString(R.string.log_rokidlink_uninstalled) else getString(R.string.log_rokidlink_uninstall_failed))
                        screenMirrorState = screenMirrorState.copy(rokidLinkRunning = false)
                        phoneMirrorState = phoneMirrorState.copy(rokidLinkRunning = false)
                        fileManagerState = fileManagerState.copy(rokidLinkRunning = false)
                        // 卸载完成后再重新安装（无论卸载成功与否都尝试安装）
                        lifecycleScope.launch {
                            delay(500)
                            installRokidLinkToGlasses()
                        }
                    },
                )
            }
        }
    }

    private fun loadSelectedHostApp(): RokidHostApp {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        return RokidHostApp.fromId(prefs.getString(PREF_ROKID_HOST_APP, null))
    }

    private fun selectRokidHostApp(hostApp: RokidHostApp) {
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

    private fun goToGuideStep1() {
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

    private fun log(message: String) {
        android.util.Log.d("RokidLab", message)
        LogCollector.i("RokidLab", message)
        runOnUiThread {
            logMessages.add(message)
            if (logMessages.size > 100) logMessages.removeFirst()
        }
    }

    /** 引导完成后自动启动眼镜端 RokidLink 服务 */
    private fun autoStartRokidLink() {
        if (prerequisitesState.currentGuideStep != GuideStep.READY) return
        android.util.Log.i("RokidLab", "Guide completed, auto-starting RokidLink on glasses...")
        cxrL.launchApp("com.rokidlab.rokidlink", onLaunchResult = { launched ->
            runOnUiThread {
                if (launched) {
                    log(getString(R.string.log_rokidlink_autostarted))
                    android.util.Log.i("RokidLab", "RokidLink auto-started on glasses")
                } else {
                    android.util.Log.w("RokidLab", "RokidLink auto-start failed")
                }
            }
        })
    }

    /** 打开错误日志导出对话框 */
    private fun showExportLogDialog() {
        runOnUiThread {
            errorLogContent = LogCollector.getErrorLogText()
            errorLogSaved = false
            showErrorLogDialog = true
        }
    }

    /** 保存错误日志到文件并分享 */
    private fun saveErrorLog() {
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

    private fun updateVersionLabel(version: String?): String {
        val clean = version?.trim().orEmpty()
        if (clean.isBlank()) return "latest"
        return if (clean.startsWith("v", ignoreCase = true)) clean else "v$clean"
    }

    private fun runWithPrerequisites(action: () -> Unit) {
        if (pendingAction != null) return  // 已有待执行操作，避免竞态覆盖
        pendingAction = action
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

    private fun startScreenMirror() {
        // 先通过 CXR-L 启动眼镜端的 ScreenMirrorIntentActivity（触发 MediaProjection 权限）
        log(getString(R.string.log_starting_glasses_mirror))
        screenMirrorState = screenMirrorState.copy(connectionStatus = this@MainActivity.getString(R.string.starting_glasses))
        cxrL.launchApp("com.rokidlab.rokidlink", activityClass = ".ScreenMirrorIntentActivity") { launched ->
            if (launched) {
                log(getString(R.string.log_glasses_mirror_started))
                // 延迟 2 秒等待 CXR-L 蓝牙通信完成，避免与 BT 隧道冲突
                Handler(Looper.getMainLooper()).postDelayed({
                    startActivity(ScreenMirrorActivity.createIntent(this))
                }, 2000)
            } else {
                log(getString(R.string.log_glasses_start_failed))
                screenMirrorState = screenMirrorState.copy(connectionStatus = this@MainActivity.getString(R.string.starting_glasses_failed))
            }
        }
    }

    private fun startPhoneMirror() {
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

    private fun stopPhoneMirror() {
        isStartingPhoneMirror = false
        log(getString(R.string.log_stopping_phone_mirror))
        // 停止手机端投屏服务（断 Socket 连接）
        stopService(Intent(this, PhoneMirrorService::class.java))
        phoneMirrorState = phoneMirrorState.copy(
            isMirroring = false,
            connectionStatus = ""
        )
        // 通过 CXR-L 关闭眼镜端的 PhoneMirrorActivity，避免它退到后台残留
        // 下次启动投屏时重新由 startPhoneMirror 启动
        log(getString(R.string.log_stopping_glasses_mirror))
        runWithPrerequisites {
            cxrL.stopApp(
                packageName = "com.rokidlab.rokidlink",
                onStopResult = { success ->
                    if (success) {
                        log(getString(R.string.log_glasses_mirror_stopped))
                    } else {
                        log(getString(R.string.log_glasses_mirror_stop_failed))
                    }
                }
            )
        }
    }

    /**
     * 检查指定 Service 是否在运行
     */
    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        return try {
            val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            manager.getRunningServices(Integer.MAX_VALUE).any { service ->
                serviceClass.name == service.service.className
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "isServiceRunning failed: ${e.message}")
            false
        }
    }

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
                    
                    // 3秒后清除状态
                    Thread {
                        Thread.sleep(3000)
                        runOnUiThread {
                            isInstallingLocalApk = false
                            localApkInstallProgress = 0
                            localApkInstallStatus = ""
                        }
                    }.start()
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

    private fun startFileManager() {
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

@Composable
internal fun MirrorSourceDialog(
    currentIndex: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    BrewDialog(onDismiss = onDismiss, title = ctx.getString(R.string.switch_source_btn), color = BrewCoral) {
        BrewDialogContent {
            BrewIndex.MIRRORS.forEachIndexed { index, mirror ->
                val isSelected = currentIndex == index
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isSelected) BrewCoral.copy(alpha = 0.12f) else Color.Transparent)
                        .border(
                            if (isSelected) 1.dp else 0.dp,
                            if (isSelected) BrewGreenDim else Color.Transparent,
                            RoundedCornerShape(12.dp),
                        )
                        .clickable { onSelect(index) }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 源图标
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(BrewShapeSmall)
                            .background(BrewTextBright.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (mirror.iconRes != null) {
                            Icon(
                                painter = painterResource(mirror.iconRes),
                                contentDescription = mirror.name,
                                tint = Color.Unspecified,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            mirror.name,
                            color = if (isSelected) BrewCoral else BrewTextBright,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            ctx.getString(mirror.descriptionRes),
                            color = BrewMuted,
                            fontSize = 13.sp,
                        )
                    }
                    if (isSelected) {
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            Icons.Outlined.CheckCircle,
                            null,
                            tint = BrewCoral,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                if (index < BrewIndex.MIRRORS.size - 1) {
                    Spacer(Modifier.height(8.dp))
                }
            }
            Spacer(Modifier.height(20.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) {
                    Text(ctx.getString(R.string.cancel_btn), color = BrewMuted, fontSize = 15.sp)
                }
            }
        }
    }
}
