package com.rokidlab.phone.app

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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.shape.RoundedCornerShape
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

class MainActivity : AppCompatActivity() {
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
    private var rokidLinkHealthJob: Job? = null
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
    private var mirrorSourceSelected by mutableStateOf(false)
    private var prerequisitesState by mutableStateOf(PrerequisitesState())
    private var screenMirrorState by mutableStateOf(ScreenMirrorState())
    private var phoneMirrorState by mutableStateOf(PhoneMirrorState())
    private var fileManagerState by mutableStateOf(FileManagerState())
    private var currentMirrorIndex by mutableStateOf(0)
    private var settingsReinstallError: String? = null
    // 日志列表，用于 UI 实时显示（最多保留 100 条）
    private val logMessages = mutableStateListOf<String>()

    private val permissions: Array<String>
        get() = buildList {
            add(Manifest.permission.BLUETOOTH)
            add(Manifest.permission.BLUETOOTH_ADMIN)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(Manifest.permission.BLUETOOTH_CONNECT)
        }.toTypedArray()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (permissions.all(::hasPermission)) {
                consumePendingAction()
            } else {
                log("蓝牙权限被拒绝。")
                Toast.makeText(this, this.getString(R.string.bluetooth_permission_denied), Toast.LENGTH_LONG).show()
            }
        }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            if (it) {
                log("通知权限已授予")
                // 授予后强制设置 appops 为 allow
                runCatching {
                    val pm = packageManager
                    // Nothing extra needed - system handles it
                }
            } else {
                log("通知权限被拒绝，可在系统设置中手动开启")
            }
        }

    private val enableBluetoothLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (isBluetoothEnabled()) consumePendingAction() else log("蓝牙仍未启用。")
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
            log("MediaProjection 权限被拒绝")
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
            log("MediaProjection 权限已授予，启动投屏服务")
            val app = application as LabApplication
            phoneMirrorState = phoneMirrorState.copy(
                isMirroring = true,
                connectionStatus = this@MainActivity.getString(R.string.screen_projection)
            )
            PhoneMirrorService.startService(
                this,
                app.phoneMirrorIp,
                app.phoneMirrorPort.toIntOrNull() ?: 7654,
                result.resultCode,
                data
            )
        } else {
            log("MediaProjection 权限被拒绝")
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
        preferHighRefreshRate()

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
        cxrL = CxrLHiRokidSession(
            activity = this,
            onStatus = ::log,
            onBusyChanged = ::updateBusy,
            onConnectionChanged = { conn ->
                cxrConnection = conn
                if (conn.authorized) {
                    checkRokidLinkInstallation()
                }
                // CXR-L 断开时 RokidLink 必然不在运行
                if (!conn.cxrlConnected || !conn.glassBtConnected) {
                    screenMirrorState = screenMirrorState.copy(rokidLinkRunning = false)
                    phoneMirrorState = phoneMirrorState.copy(rokidLinkRunning = false)
                    fileManagerState = fileManagerState.copy(rokidLinkRunning = false)
                    rokidLinkHealthJob?.cancel()
                    rokidLinkHealthJob = null
                }
            },
            initialHostApp = selectedHostApp,
        )
        (application as LabApplication).setCxrL(cxrL)
        // 如果之前已授权，同步前置条件状态
        if (cxrL.hasAuthorization()) {
            prerequisitesState = prerequisitesState.copy(authorized = true)
        }
        apps = runCatching { BrewIndex.loadInitial(this) }.getOrDefault(emptyList())
        log("已加载 ${apps.size} 个应用（含离线缓存）")
        refreshCachedGlassesInstallStates(apps)
        refreshPhoneInstallStates(apps)

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
                        onPhoneMirrorStop = { stopPhoneMirror() },
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
                        onSwitchLanguage = { code ->
                            com.rokidlab.phone.util.LocalizationManager.setLocale(this@MainActivity, code)
                            // Recreate activity to apply language
                            recreate()
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
        refreshStoreIndex(manual = false)
        log("就绪。安装眼镜 APK 前请先授权 ${selectedHostApp.displayName}。")
        
        // Android 13+ 请求通知权限（用于定时消息推送到眼镜）
        requestNotificationPermission()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (hasPermission(Manifest.permission.POST_NOTIFICATIONS)) return
        log("请求通知权限...")
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        // MIUI 可能静默拒绝，启动后再次尝试打开设置页引导用户
        lifecycleScope.launch {
            delay(2000)
            if (!hasPermission(Manifest.permission.POST_NOTIFICATIONS)) {
                log("通知权限未授予，引导用户手动开启")
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
            log("无法打开通知设置: ${e.message}")
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
        // 重置 RokidLink 运行状态，让用户重新点击开始按钮
        // 这样可以确保每次进入时按钮文字正确
        screenMirrorState = screenMirrorState.copy(rokidLinkRunning = false)
        phoneMirrorState = phoneMirrorState.copy(rokidLinkRunning = false)
        fileManagerState = fileManagerState.copy(rokidLinkRunning = false)
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
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                attributes.setFrameRatePowerSavingsBalanced(false)
            }
            window.attributes = attributes
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            window.decorView.setRequestedFrameRate(View.REQUESTED_FRAME_RATE_CATEGORY_HIGH)
        }
    }

    private fun refreshStoreIndex(manual: Boolean) {
        if (refreshing) return
        lifecycleScope.launch {
            refreshing = true
            val started = System.currentTimeMillis()
            if (manual) log("正在刷新商店注册表...")
            runCatching {
                BrewIndex.refresh(this@MainActivity)
            }.onSuccess { refresh ->
                apps = refresh.apps
                refreshCachedGlassesInstallStates(refresh.apps)
                refreshPhoneInstallStates(refresh.apps)
                installCheckTick += 1
                // 单独检查 RokidLab 自身更新（固定从 Gitee 获取）
                checkRokidLabUpdate()
                log("商店注册表已更新（${refresh.apps.size} 个应用）。")
            }.onFailure { error ->
                log("远程注册表不可用：${error.message ?: error.javaClass.simpleName}")
                Toast.makeText(this@MainActivity, this@MainActivity.getString(R.string.registry_unavailable, error.message ?: error.javaClass.simpleName), Toast.LENGTH_LONG).show()
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
                log("有可用更新：RokidLab ${updateVersionLabel(update.version)}。")
            } else {
                selfUpdateState = selfUpdateState.copy(available = false)
            }
        }.onFailure { error ->
            log("检查自身更新失败：${error.message ?: error.javaClass.simpleName}")
        }
    }

    private fun switchMirror(index: Int) {
        BrewIndex.setMirror(this, index)
        prerequisitesState = prerequisitesState.copy(mirrorSourceSelected = true)
        log("已切换到 ${BrewIndex.getCurrentMirror().name}")
        refreshStoreIndex(manual = true)
    }

    private fun installLocalApkToGlasses(uri: Uri) {
        if (busy) return
        runWithPrerequisites {
            lifecycleScope.launch {
                updateBusy(true)
                runCatching {
                    val input = contentResolver.openInputStream(uri)
                        ?: throw IllegalStateException("无法打开 APK 文件")
                    val tempFile = File(cacheDir, "local_install_${System.currentTimeMillis()}.apk")
                    FileOutputStream(tempFile).use { output ->
                        input.copyTo(output)
                    }
                    input.close()
                    log("正在安装本地 APK 到眼镜...")
                    cxrL.installApk(tempFile) { installed ->
                        if (installed) {
                            log("APK 已成功安装到眼镜。")
                        } else {
                            log("APK 安装失败。")
                        }
                        tempFile.delete()
                        updateBusy(false)
                    }
                }.onFailure { error ->
                    log("本地安装失败：${error.message ?: error.javaClass.simpleName}")
                    updateBusy(false)
                }
            }
        }
    }

    private fun performSelfUpdate() {
        if (selfUpdateState.downloading) return
        val url = selfUpdateState.apkUrl
        if (url.isBlank()) {
            log("更新失败：缺少 RokidLab APK 下载地址。")
            return
        }
        // 清理旧更新 APK
        runCatching { File(cacheDir, "RokidLab-update.apk").delete() }
        selfUpdateState = selfUpdateState.copy(downloading = true, downloadPercent = 0)
        downloadProgress["brew-self-update"] = 0
        val version = selfUpdateState.latestVersion.ifBlank { "latest" }
        val job = lifecycleScope.launch {
            runCatching {
                log("正在下载 RokidLab $version...")
                val file = downloader.download(url, "RokidLab-update.apk") { percent ->
                    runOnUiThread {
                        downloadProgress["brew-self-update"] = percent
                        selfUpdateState = selfUpdateState.copy(downloadPercent = percent)
                    }
                }
                downloadProgress["brew-self-update"] = 100
                selfUpdateState = selfUpdateState.copy(downloading = false, downloadPercent = 100)
                log("已下载 ${file.length()} 字节。")
                val ok = PhonePackageInstallHelper.requestInstall(this@MainActivity, file, ::log)
                if (!ok) {
                    selfUpdateState = selfUpdateState.copy(downloading = false)
                }
                downloadProgress.remove("brew-self-update")
                downloadCancelJobs.remove("brew-self-update")
            }.onFailure { error ->
                log("更新失败：${error.message ?: error.javaClass.simpleName}")
                downloadProgress.remove("brew-self-update")
                downloadCancelJobs.remove("brew-self-update")
                selfUpdateState = selfUpdateState.copy(downloading = false)
            }
        }
        downloadCancelJobs["brew-self-update"] = job
    }

    override fun onDestroy() {
        cxrL.cleanup()
        (application as LabApplication).hidManager.destroy()
        super.onDestroy()
    }

    @Deprecated("CXR-L SDK still uses startActivityForResult for authorization.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == CxrLHiRokidSession.AUTH_REQUEST_CODE) {
            cxrL.handleAuthorizationResult(resultCode, data)
            // 授权完成后更新前置条件状态
            if (cxrL.hasAuthorization()) {
                prerequisitesState = prerequisitesState.copy(authorized = true)
            }
        }
    }

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
                    log("眼镜安装状态已刷新。")
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
                        log("已在眼镜上启动 ${app.name}")
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
                    log("已在手机上启动 ${app.name}")
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
                log("正在下载 ${app.name} 的 $target APK...")
                val file = downloader.download(artifact.url, fileName, artifact.sha256) { progress ->
                    downloadProgress[progressKey] = progress
                    if (progress % 25 == 0) log("下载 $target：$progress%")
                }
                downloadProgress[progressKey] = 100
                log("已下载 ${file.name}（${file.length() / 1024} KB）。")
                if (target == "glasses") {
                    cxrL.installApk(file) { installed ->
                        artifact.packageName?.takeIf { it.isNotBlank() }?.let { packageName ->
                            if (installed) {
                                installCache.recordGlassesInstall(app, artifact)
                                setGlassesInstallState(
                                    packageName,
                                    cachedGlassesInstallState(app, artifact) ?: InstallState.INSTALLED,
                                    InstallStateSource.VERIFIED,
                                )
                                Toast.makeText(this@MainActivity, this@MainActivity.getString(R.string.install_success_toast, app.name), Toast.LENGTH_SHORT).show()
                            } else {
                                installCache.removeGlasses(packageName)
                                setGlassesInstallState(packageName, InstallState.NOT_INSTALLED, InstallStateSource.VERIFIED)
                            }
                            installCheckTick += 1
                        }
                        downloadProgress.remove(progressKey)
                        downloadCancelJobs.remove(progressKey)
                        updateBusy(false)
                    }
                } else {
                    updateBusy(false)
                    downloadProgress.remove(progressKey)
                    downloadCancelJobs.remove(progressKey)
                    PhonePackageInstallHelper.requestInstall(this@MainActivity, file, ::log)
                }
            }.onFailure { error ->
                log("安装失败：${error.message ?: error.javaClass.simpleName}")
                Toast.makeText(this@MainActivity, this@MainActivity.getString(R.string.install_failed, app.name, error.message ?: error.javaClass.simpleName), Toast.LENGTH_LONG).show()
                downloadProgress.remove(progressKey)
                downloadCancelJobs.remove(progressKey)
                updateBusy(false)
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
        log("已取消下载：$key")
    }

    private fun checkRokidLinkInstallation() {
        val app = application as LabApplication
        // 跳过持久化缓存兜底——每次都等 SDK 查询结果，避免卸载后仍显示"已安装"
        
        // 运行时缓存：有结果直接用
        if (app.rokidLinkInstalled != null) {
            screenMirrorState = screenMirrorState.copy(rokidLinkInstalled = app.rokidLinkInstalled)
            phoneMirrorState = phoneMirrorState.copy(rokidLinkInstalled = app.rokidLinkInstalled)
            fileManagerState = fileManagerState.copy(rokidLinkInstalled = app.rokidLinkInstalled)
            // 乐观标记：已安装 + 连接正常 → 默认为运行中
            if (app.rokidLinkInstalled == true &&
                cxrConnection.cxrlConnected && cxrConnection.glassBtConnected &&
                !screenMirrorState.rokidLinkRunning) {
                screenMirrorState = screenMirrorState.copy(rokidLinkRunning = true)
                phoneMirrorState = phoneMirrorState.copy(rokidLinkRunning = true)
                fileManagerState = fileManagerState.copy(rokidLinkRunning = true)
                startRokidLinkHealthCheck()
            }
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
                    // SDK 确认已安装 → 乐观标记为运行中
                    if (installed &&
                        cxrConnection.cxrlConnected && cxrConnection.glassBtConnected &&
                        !screenMirrorState.rokidLinkRunning) {
                        screenMirrorState = screenMirrorState.copy(rokidLinkRunning = true)
                        phoneMirrorState = phoneMirrorState.copy(rokidLinkRunning = true)
                        fileManagerState = fileManagerState.copy(rokidLinkRunning = true)
                        startRokidLinkHealthCheck()
                    }
                },
                onComplete = {
                    isCheckingRokidLink = false
                }
            )
        }
    }

    /**
     * 定期心跳检测 RokidLink 是否仍在运行
     * 每 15 秒通过 CXR-L 连接状态判断，连接断开则自动标记为未运行
     */
    private fun startRokidLinkHealthCheck() {
        rokidLinkHealthJob?.cancel()
        rokidLinkHealthJob = lifecycleScope.launch {
            while (true) {
                delay(15_000)
                if (!cxrConnection.cxrlConnected || !cxrConnection.glassBtConnected) {
                    android.util.Log.w("MainActivity", "RokidLink 心跳检测：CXR-L 连接已断开，标记为未运行")
                    screenMirrorState = screenMirrorState.copy(rokidLinkRunning = false)
                    phoneMirrorState = phoneMirrorState.copy(rokidLinkRunning = false)
                    fileManagerState = fileManagerState.copy(rokidLinkRunning = false)
                    break
                }
                android.util.Log.d("MainActivity", "RokidLink 心跳检测：连接正常")
            }
        }
    }

    private fun installRokidLinkToGlasses() {
        runWithPrerequisites {
            lifecycleScope.launch {
                updateBusy(true)
                log("正在安装 RokidLink 到眼镜...")
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
                                log("RokidLink 安装成功")
                                Toast.makeText(this@MainActivity, this@MainActivity.getString(R.string.rokidlink_install_success), Toast.LENGTH_SHORT).show()
                            } else {
                                settingsReinstallError = this@MainActivity.getString(R.string.rokidlink_install_error)
                                log(settingsReinstallError!!)
                                Toast.makeText(this@MainActivity, this@MainActivity.getString(R.string.rokidlink_install_failed), Toast.LENGTH_SHORT).show()
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
                }
            }
        }
    }

    private var isOpeningRokidLink = false

    private fun openRokidLinkOnGlasses() {
        if (isOpeningRokidLink) return
        isOpeningRokidLink = true
        runWithPrerequisites {
            log("正在打开眼镜上的 RokidLink...")
            cxrL.launchApp(
                packageName = "com.rokidlab.rokidlink",
                activityClass = ".MainActivity",
                onLaunchResult = { success ->
                    isOpeningRokidLink = false
                    if (success) {
                        log("RokidLink 启动成功")
                        screenMirrorState = screenMirrorState.copy(rokidLinkRunning = true)
                        phoneMirrorState = phoneMirrorState.copy(rokidLinkRunning = true)
                        fileManagerState = fileManagerState.copy(rokidLinkRunning = true)
                        startRokidLinkHealthCheck()
                    } else {
                        log("RokidLink 启动失败")
                    }
                }
            )
        }
    }

    private fun stopRokidLinkOnGlasses() {
        runWithPrerequisites {
            log("正在关闭眼镜上的 RokidLink...")
            rokidLinkHealthJob?.cancel()
            rokidLinkHealthJob = null
            cxrL.stopApp(
                packageName = "com.rokidlab.rokidlink",
                onStopResult = { success ->
                    if (success) {
                        log("RokidLink 已关闭")
                    } else {
                        log("RokidLink 关闭失败")
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
        runWithPrerequisites {
            lifecycleScope.launch {
                // 先停止运行中的 RokidLink
                log("正在关闭眼镜上的 RokidLink...")
                cxrL.stopApp(
                    packageName = "com.rokidlab.rokidlink",
                    onStopResult = { success ->
                        log(if (success) "RokidLink 已关闭" else "RokidLink 关闭失败")
                        screenMirrorState = screenMirrorState.copy(rokidLinkRunning = false)
                        phoneMirrorState = phoneMirrorState.copy(rokidLinkRunning = false)
                        fileManagerState = fileManagerState.copy(rokidLinkRunning = false)
                    },
                )
                // 等待停止生效
                delay(800)
                // 重新安装
                installRokidLinkToGlasses()
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
        log("CXR-L 主机已设为 ${hostApp.displayName}。安装眼镜应用前请重新授权。")
    }

    private fun goToGuideStep1() {
        // 重置前置条件状态，让用户重新完成引导流程
        // 将 hostApp 和 selectedHostApp 都重置，这样会显示第一步（选择主机应用）
        selectedHostApp = RokidHostApp.DEFAULT
        prerequisitesState = prerequisitesState.copy(
            hostApp = null,
            mirrorSourceSelected = false,
            authorized = false
        )
        log("用户跳转到引导界面第一步")
        Toast.makeText(this@MainActivity, this@MainActivity.getString(R.string.reset_guide), Toast.LENGTH_SHORT).show()
    }

    private fun log(message: String) {
        android.util.Log.d("RokidLab", message)
        runOnUiThread {
            logMessages.add(message)
            if (logMessages.size > 100) logMessages.removeFirst()
        }
    }

    private fun updateVersionLabel(version: String?): String {
        val clean = version?.trim().orEmpty()
        if (clean.isBlank()) return "latest"
        return if (clean.startsWith("v", ignoreCase = true)) clean else "v$clean"
    }

    private fun runWithPrerequisites(action: () -> Unit) {
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
        log("正在启动眼镜端屏幕镜像服务...")
        screenMirrorState = screenMirrorState.copy(connectionStatus = this@MainActivity.getString(R.string.starting_glasses))
        cxrL.launchApp("com.rokidlab.rokidlink", activityClass = ".ScreenMirrorIntentActivity") { launched ->
            if (launched) {
                log("眼镜端屏幕镜像服务已启动")
                // 打开手机端 ScreenMirrorActivity，它会连接眼镜端口 6556 接收画面
                startActivity(ScreenMirrorActivity.createIntent(this))
            } else {
                log("眼镜端启动失败")
                screenMirrorState = screenMirrorState.copy(connectionStatus = this@MainActivity.getString(R.string.starting_glasses_failed))
            }
        }
    }

    private fun startPhoneMirror() {
        if (isStartingPhoneMirror) return
        isStartingPhoneMirror = true
        log("启动手机投屏")
        phoneMirrorState = phoneMirrorState.copy(connectionStatus = this@MainActivity.getString(R.string.starting_glasses))
        // 先提示用户权限用途
        Toast.makeText(this@MainActivity, this@MainActivity.getString(R.string.screen_record_permission), Toast.LENGTH_LONG).show()
        // 通过 CXR-L 直接启动眼镜端投屏接收页（PhoneMirrorActivity 启动后自动监听 7654 端口）
        cxrL.launchApp("com.rokidlab.rokidlink", activityClass = ".PhoneMirrorActivity") { launched ->
            if (launched) {
                log("眼镜端已启动，请求屏幕录制权限")
                phoneMirrorState = phoneMirrorState.copy(rokidLinkRunning = true)
                // 眼镜端 PhoneMirrorServer 已自动启动监听 7654 端口
                // 用户授权后 PhoneMirrorService 会直接 Socket 连接
                val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                phoneMirrorProjectionLauncher.launch(projectionManager.createScreenCaptureIntent())
            } else {
                isStartingPhoneMirror = false
                log("眼镜端启动失败")
                phoneMirrorState = phoneMirrorState.copy(connectionStatus = this@MainActivity.getString(R.string.starting_glasses_failed))
            }
        }
    }

    private fun stopPhoneMirror() {
        isStartingPhoneMirror = false
        log("停止手机投屏")
        // 1. 停止投屏服务
        stopService(Intent(this, PhoneMirrorService::class.java))
        // 2. 通过 CXR-L 关闭眼镜端投屏
        cxrL.stopApp("com.rokidlab.rokidlink")
        phoneMirrorState = phoneMirrorState.copy(
            isMirroring = false,
            connectionStatus = ""
        )
    }

    private fun startFileManager() {
        startActivity(FileManagerActivity.createIntent(this))
    }

}

@Composable
internal fun MirrorSourceDialog(
    currentIndex: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(),
    ) {
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(BrewBg)
                .padding(24.dp),
        ) {
            Text(
                ctx.getString(R.string.switch_source_btn),
                color = BrewTextBright,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(16.dp))
            BrewIndex.MIRRORS.forEachIndexed { index, mirror ->
                val isSelected = currentIndex == index
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isSelected) BrewGreen.copy(alpha = 0.12f) else Color.Transparent)
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
                            .clip(RoundedCornerShape(6.dp))
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
                            color = if (isSelected) BrewGreen else BrewTextBright,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            mirror.description,
                            color = BrewMuted,
                            fontSize = 13.sp,
                        )
                    }
                    if (isSelected) {
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            Icons.Outlined.CheckCircle,
                            null,
                            tint = BrewGreen,
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
