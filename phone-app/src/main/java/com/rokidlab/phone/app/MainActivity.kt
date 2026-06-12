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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
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
    private var isCheckingScreenStream = false
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
    private var showMirrorDialog by mutableStateOf(false)
    private var mirrorSourceSelected by mutableStateOf(false)
    private var prerequisitesState by mutableStateOf(PrerequisitesState())
    private var screenMirrorState by mutableStateOf(ScreenMirrorState())
    private var phoneMirrorState by mutableStateOf(PhoneMirrorState())
    private var fileManagerState by mutableStateOf(FileManagerState())
    private var currentMirrorIndex by mutableStateOf(0)

    private val permissions: Array<String>
        get() = buildList {
            add(Manifest.permission.BLUETOOTH)
            add(Manifest.permission.BLUETOOTH_ADMIN)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(Manifest.permission.BLUETOOTH_CONNECT)
        }.toTypedArray()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (permissions.all(::hasPermission)) consumePendingAction() else log("蓝牙权限被拒绝。")
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
    private val phoneMirrorProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data ?: run {
            log("MediaProjection 权限被拒绝")
            phoneMirrorState = phoneMirrorState.copy(
                isMirroring = false,
                connectionStatus = "权限被拒绝"
            )
            return@registerForActivityResult
        }
        if (result.resultCode == Activity.RESULT_OK) {
            log("MediaProjection 权限已授予，启动投屏服务")
            val app = application as LabApplication
            phoneMirrorState = phoneMirrorState.copy(
                isMirroring = true,
                connectionStatus = "投屏中..."
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
                connectionStatus = "权限被拒绝"
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        preferHighRefreshRate()

        BrewIndex.initMirror(this)
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
                    checkScreenStreamInstallation()
                }
            },
            initialHostApp = selectedHostApp,
        )
        (application as LabApplication).setCxrL(cxrL)
        // 如果之前已授权，同步前置条件状态
        if (cxrL.hasAuthorization()) {
            prerequisitesState = prerequisitesState.copy(authorized = true)
        }
        apps = BrewIndex.loadInitial(this)
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
                        onScreenMirrorStop = { stopScreenStreamOnGlasses() },
                        onScreenMirrorInstallScreenStream = { installScreenStreamToGlasses() },
                        onScreenMirrorOpenScreenStream = { openScreenStreamOnGlasses() },
                        onScreenMirrorRetry = { },
                        onScreenMirrorBack = { },
                        onPhoneMirrorIpChange = { (application as LabApplication).setPhoneMirrorIp(it) },
                        onPhoneMirrorPortChange = { (application as LabApplication).setPhoneMirrorPort(it) },
                        onPhoneMirrorConnect = { startPhoneMirror() },
                        onPhoneMirrorStart = { 
                            if (phoneMirrorState.isMirroring) stopPhoneMirror() else startPhoneMirror()
                        },
                        onPhoneMirrorStop = { stopPhoneMirror() },
                        onPhoneMirrorInstallScreenStream = { installScreenStreamToGlasses() },
                        onPhoneMirrorOpenScreenStream = { openScreenStreamOnGlasses() },
                        onPhoneMirrorRetry = { },
                        onPhoneMirrorBack = { },
                        onFileManagerIpChange = { (application as LabApplication).setFileManagerIp(it) },
                        onFileManagerConnect = { startFileManager() },
                        onFileManagerDisconnect = { },
                        onFileManagerStop = { stopScreenStreamOnGlasses() },
                        onFileManagerInstallScreenStream = { installScreenStreamToGlasses() },
                        onFileManagerOpenScreenStream = { openScreenStreamOnGlasses() },
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
                        onSettingsReinstallScreenStream = { reinstallScreenStreamOnGlasses() },
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
        // 重置 ScreenStream 运行状态，让用户重新点击开始按钮
        // 这样可以确保每次进入时按钮文字正确
        screenMirrorState = screenMirrorState.copy(screenStreamRunning = false)
        phoneMirrorState = phoneMirrorState.copy(screenStreamRunning = false)
        fileManagerState = fileManagerState.copy(screenStreamRunning = false)
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
            attributes.setFrameRatePowerSavingsBalanced(false)
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
            }
            val elapsed = System.currentTimeMillis() - started
            if (elapsed < 800) delay(800 - elapsed)
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

        cxrL.queryInstalledApps(
            packageNames = packageNames,
            onResult = { packageName, installed ->
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
                log("眼镜安装状态已刷新。")
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
            Toast.makeText(this, "无法获取 ${app.name} 的包名", Toast.LENGTH_SHORT).show()
            return
        }

        if (target == "glasses") {
            runWithPrerequisites {
                cxrL.launchApp(packageName) { launched ->
                    if (launched) {
                        log("已在眼镜上启动 ${app.name}")
                    } else {
                        Toast.makeText(this, "无法在眼镜上启动 ${app.name}", Toast.LENGTH_SHORT).show()
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
                    Toast.makeText(this, "${app.name} 未安装在手机上", Toast.LENGTH_SHORT).show()
                }
            }.onFailure { error ->
                Toast.makeText(this, "启动 ${app.name} 失败：${error.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun installArtifact(app: BrewApp, target: String) {
        if (busy) return
        val artifact = app.artifactFor(target)
        if (artifact == null) {
            Toast.makeText(this, "没有 ${app.name} 的 $target 构件", Toast.LENGTH_SHORT).show()
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
            Toast.makeText(this, "没有 ${app.name} 的 $target 包", Toast.LENGTH_SHORT).show()
            return
        }

        if (target == "glasses") {
            cxrL.uninstallApp(packageName) { uninstalled ->
                if (uninstalled) {
                    installCache.removeGlasses(packageName)
                    setGlassesInstallState(packageName, InstallState.NOT_INSTALLED, InstallStateSource.VERIFIED)
                    installCheckTick += 1
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

    private fun checkScreenStreamInstallation() {
        val app = application as LabApplication
        // 跳过持久化缓存兜底——每次都等 SDK 查询结果，避免卸载后仍显示"已安装"
        
        // 运行时缓存：有结果直接用
        if (app.screenStreamInstalled != null) {
            screenMirrorState = screenMirrorState.copy(screenStreamInstalled = app.screenStreamInstalled)
            phoneMirrorState = phoneMirrorState.copy(screenStreamInstalled = app.screenStreamInstalled)
            fileManagerState = fileManagerState.copy(screenStreamInstalled = app.screenStreamInstalled)
            return
        }
        
        // SDK 查询
        if (!cxrL.hasAuthorization()) return
        
        if (isCheckingScreenStream) return
        isCheckingScreenStream = true
        
        lifecycleScope.launch {
            cxrL.queryInstalledApps(
                packageNames = listOf("com.rokidlab.screenservice"),
                onResult = { packageName, installed ->
                    app.setScreenStreamInstalled(installed)
                    screenMirrorState = screenMirrorState.copy(screenStreamInstalled = installed)
                    phoneMirrorState = phoneMirrorState.copy(screenStreamInstalled = installed)
                    fileManagerState = fileManagerState.copy(screenStreamInstalled = installed)
                },
                onComplete = {
                    isCheckingScreenStream = false
                }
            )
        }
    }

    private fun installScreenStreamToGlasses() {
        runWithPrerequisites {
            lifecycleScope.launch {
                updateBusy(true)
                log("正在安装 ScreenStream 到眼镜...")
                screenMirrorState = screenMirrorState.copy(isInstallingScreenStream = true)
                phoneMirrorState = phoneMirrorState.copy(isInstallingScreenStream = true)
                fileManagerState = fileManagerState.copy(isInstallingScreenStream = true)
                
                runCatching {
                    val apkInputStream = assets.open("glasses-screen-service.apk")
                    val tempFile = File(cacheDir, "glasses-screen-service.apk")
                    apkInputStream.use { input ->
                        tempFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    cxrL.installApk(tempFile) { installed ->
                        runOnUiThread {
                            screenMirrorState = screenMirrorState.copy(
                                screenStreamInstalled = installed,
                                isInstallingScreenStream = false
                            )
                            phoneMirrorState = phoneMirrorState.copy(
                                screenStreamInstalled = installed,
                                isInstallingScreenStream = false
                            )
                            fileManagerState = fileManagerState.copy(
                                screenStreamInstalled = installed,
                                isInstallingScreenStream = false
                            )
                            (application as LabApplication).setScreenStreamInstalled(installed)
                            updateBusy(false)
                            log(if (installed) "ScreenStream 安装成功" else "ScreenStream 安装失败")
                        }
                        tempFile.delete()
                    }
                }.onFailure { e ->
                    log("安装 ScreenStream 失败: ${e.message}")
                    screenMirrorState = screenMirrorState.copy(isInstallingScreenStream = false)
                    phoneMirrorState = phoneMirrorState.copy(isInstallingScreenStream = false)
                    fileManagerState = fileManagerState.copy(isInstallingScreenStream = false)
                    updateBusy(false)
                }
            }
        }
    }

    private fun openScreenStreamOnGlasses() {
        runWithPrerequisites {
            log("正在打开眼镜上的 ScreenStream...")
            cxrL.launchApp(
                packageName = "com.rokidlab.screenservice",
                activityClass = ".MainActivity",
                onLaunchResult = { success ->
                    if (success) {
                        log("ScreenStream 启动成功")
                        screenMirrorState = screenMirrorState.copy(screenStreamRunning = true)
                        phoneMirrorState = phoneMirrorState.copy(screenStreamRunning = true)
                        fileManagerState = fileManagerState.copy(screenStreamRunning = true)
                    } else {
                        log("ScreenStream 启动失败")
                    }
                }
            )
        }
    }

    private fun stopScreenStreamOnGlasses() {
        runWithPrerequisites {
            log("正在关闭眼镜上的 ScreenStream...")
            cxrL.stopApp(
                packageName = "com.rokidlab.screenservice",
                onStopResult = { success ->
                    if (success) {
                        log("ScreenStream 已关闭")
                    } else {
                        log("ScreenStream 关闭失败")
                    }
                    screenMirrorState = screenMirrorState.copy(screenStreamRunning = false)
                    phoneMirrorState = phoneMirrorState.copy(screenStreamRunning = false)
                    fileManagerState = fileManagerState.copy(screenStreamRunning = false)
                }
            )
        }
    }

    private fun reinstallScreenStreamOnGlasses() {
        runWithPrerequisites {
            lifecycleScope.launch {
                // 先停止运行中的 ScreenStream
                log("正在关闭眼镜上的 ScreenStream...")
                cxrL.stopApp(
                    packageName = "com.rokidlab.screenservice",
                    onStopResult = { success ->
                        log(if (success) "ScreenStream 已关闭" else "ScreenStream 关闭失败")
                        screenMirrorState = screenMirrorState.copy(screenStreamRunning = false)
                        phoneMirrorState = phoneMirrorState.copy(screenStreamRunning = false)
                        fileManagerState = fileManagerState.copy(screenStreamRunning = false)
                    },
                )
                // 等待停止生效
                delay(800)
                // 重新安装
                installScreenStreamToGlasses()
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
    }

    private fun log(message: String) {
        android.util.Log.d("RokidLab", message)
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
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun isBluetoothEnabled(): Boolean {
        val manager = getSystemService(BLUETOOTH_SERVICE) as? BluetoothManager ?: return false
        return manager.adapter?.isEnabled == true
    }

    private fun startScreenMirror() {
        startActivity(ScreenMirrorActivity.createIntent(this))
    }

    private fun startPhoneMirror() {
        log("启动手机投屏")
        // 1. 通过 CXR-L 启动眼镜端投屏接收 Activity
        phoneMirrorState = phoneMirrorState.copy(connectionStatus = "正在启动眼镜端...")
        cxrL.launchApp("com.rokidlab.screenservice", sendCmdAfterLaunch = "phone_mirror_launch") { launched ->
            if (launched) {
                log("眼镜端已启动并发送投屏命令，请求屏幕录制权限")
                // 2. 请求屏幕录制权限
                val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                phoneMirrorProjectionLauncher.launch(projectionManager.createScreenCaptureIntent())
            } else {
                log("眼镜端启动失败")
                phoneMirrorState = phoneMirrorState.copy(connectionStatus = "启动眼镜端失败")
            }
        }
    }

    private fun stopPhoneMirror() {
        log("停止手机投屏")
        // 1. 停止投屏服务
        stopService(Intent(this, PhoneMirrorService::class.java))
        // 2. 通过 CXR-L 关闭眼镜端投屏
        cxrL.stopApp("com.rokidlab.screenservice")
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
                "切换源",
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
                    Text("取消", color = BrewMuted, fontSize = 15.sp)
                }
            }
        }
    }
}
