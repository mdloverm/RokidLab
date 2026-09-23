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
import com.rokidlab.phone.permission.AppPermission
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

    /**
     * 连接眼镜所需的蓝牙权限名 —— 直接取自 [AppPermission] 总表，不再手写一份名单。
     *
     * Android 12 以下 `BLUETOOTH`/`BLUETOOTH_ADMIN` 是 normal 级别、安装即授予，
     * `runtimeNames` 会按 minSdk 过滤成空数组，`all {}` 恒为 true（申请一个不存在的权限
     * 会被系统把整批请求拒掉，所以必须过滤）。
     */
    private val permissions: Array<String>
        get() = AppPermission.runtimeNames(listOf(AppPermission.BLUETOOTH))

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

    /**
     * 「截屏」工具的 MediaProjection 授权框。
     *
     * 与投屏那份授权**各是各的**：投屏的 token 归 `PhoneMirrorService` 长期使用，
     * 这里拿到的一次性 token 交给 `ScreenCaptureService` 抓一帧就 `stop()`。
     * 之所以要两块，是因为 targetSdk 34 起一个 MediaProjection 只允许
     * `createVirtualDisplay` 一次（详见 `ScreenCaptureService` 的类注释）。
     */
    private val screenCaptureConsentLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        ScreenCaptureBroker.deliverConsent(result.resultCode, result.data)
    }
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
            namedThread("main-permission-io", start = true) {
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
                        return@namedThread
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
            }
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
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val isFirstRun = prefs.getString(PREF_ROKID_HOST_APP, null) == null
        // 初始化前置条件状态：首次用户从步骤1开始，老用户恢复进度
        prerequisitesState = PrerequisitesState(
            hostApp = if (isFirstRun) null else selectedHostApp,
            mirrorSourceSelected = !isFirstRun,
            // 权限步实测：全部已开 → 这一步不出现（老用户不会被引导拦住）；
            // 只要有缺项，就在「安装眼镜端应用」之前先过这一步（AppPermission 是唯一事实来源）
            permissionsReady = AppPermission.missing(this, AppPermission.onboardingPermissions()).isEmpty(),
            // 「安装眼镜端应用」/「配置眼镜 WiFi」两步每次启动都各出现一次，刻意不落盘：
            //  · 装：两端没有版本比对（眼镜端是旧版只会静默降级，表现为"某些功能不工作"），
            //    手机端更新后必须重装眼镜端，忘了没人提醒；
            //  · 网：用户可能就是想换一个 WiFi。
            rokidLinkInstalled = false,
            wifiConfigured = false,
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
            context = this,
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

        // 说明：手机侧权限（蓝牙/通讯录/电话/日历/定位/通知/悬浮窗）与兼容性设置（电池优化/自启动）
        // 一律不在启动期弹窗 —— 全部收敛到引导流程「第四步：开启全部权限」（逐项自动检测 + 点击拉起，
        // 见 GuideScreen.PermissionsStep）。启动期弹窗的历史问题是"同一个权限在多处各弹一次、
        // 且弹完还自动跳设置页"，现在的边界：引导步负责"一次性开齐"，运行期
        // [com.rokidlab.phone.permission.PermissionBridge] 只在工具真的因缺权限失败时兜底拉起 ——
        // 两者都不会重复打扰。
    }

    // ════════════════════════════════════════════════════════════════
    //  生命周期
    // ════════════════════════════════════════════════════════════════
    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(PhoneInstallResultReceiver.ACTION_PHONE_INSTALL_STATUS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(phoneInstallStatusReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(phoneInstallStatusReceiver, filter)
        }
        // 「截屏」工具的授权框只能由 Activity 弹：挂载窗口 = 界面能弹框的窗口，
        // 挂上之后 AI 工具线程才可能请求到一次系统截屏授权（见 ScreenCaptureBroker）
        ScreenCaptureBroker.attach {
            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            screenCaptureConsentLauncher.launch(projectionManager.createScreenCaptureIntent())
        }
    }

    override fun onStop() {
        ScreenCaptureBroker.detach()
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
        // 同时清除持久化的 host 选择，确保重启后真的从第一步重来
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .remove(PREF_ROKID_HOST_APP)
            .apply()
        prerequisitesState = prerequisitesState.copy(
            hostApp = null,
            mirrorSourceSelected = false,
            authorized = false,
            permissionsReady = AppPermission.missing(this, AppPermission.onboardingPermissions()).isEmpty(),
            rokidLinkInstalled = false,
            wifiConfigured = false,
        )
        log(getString(R.string.log_guide_step))
        Toast.makeText(this@MainActivity, this@MainActivity.getString(R.string.reset_guide), Toast.LENGTH_SHORT).show()
    }

    /**
     * 眼镜端应用：安装成功 或 用户主动跳过 → 让引导往下走。
     *
     * 刻意**不落盘**：这一步是每次启动的提醒 —— 两端没有版本比对（眼镜端是旧版时只会静默降级，
     * 表现为"某些功能不工作"），手机端更新后需重装眼镜端，忘了装没人提醒，所以冷启动一律从这一步开始。
     */
    internal fun markRokidLinkInstalled() {
        prerequisitesState = prerequisitesState.copy(rokidLinkInstalled = true)
    }

    /**
     * 眼镜 WiFi：配置下发成功 或 用户主动跳过 → 让引导往下走。
     *
     * 同样**不落盘**：这一步每次启动都出现，用户可能就是想换一个网络；
     * 记住进度反而会挡掉"换 WiFi"这个正当需求（该步自带扫描列表 + 手动输入，随时可换）。
     */
    internal fun markWifiConfigured() {
        prerequisitesState = prerequisitesState.copy(wifiConfigured = true)
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
        
        // 权限不再在这里单开一套弹窗 —— 通知/悬浮窗都已收进引导流程「开启全部权限」步。
        // 这里只做一次只读检查：缺悬浮窗（后台拉起界面的豁免）就直接拦下并说明去哪开，
        // 缺通知只记日志（Android 13+ 前台服务通知没授予也能照常投屏，只是状态栏不显示卡片）。
        val missingMirrorPerms = AppPermission.missing(this, listOf(AppPermission.OVERLAY))
        if (missingMirrorPerms.isNotEmpty()) {
            val labels = AppPermission.labels(this, missingMirrorPerms)
            log(getString(R.string.log_mirror_permission_missing, labels))
            Toast.makeText(this, getString(R.string.permission_need_manual_open, labels), Toast.LENGTH_LONG).show()
            isStartingPhoneMirror = false
            return
        }
        if (!AppPermission.isGranted(this, AppPermission.NOTIFICATION)) {
            log(getString(R.string.log_mirror_notification_missing))
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

    // 说明：原先这里有一套「国产手机兼容性设置」启动期引导（电池优化白名单 / 自启动 / vivo 后台限制，
    // 分别在启动后 2s / 5s / 8s 弹 AlertDialog），现已整体移入引导流程「第四步：开启全部权限」——
    // 与权限项同页逐项列出、点哪项拉哪项，不再有多组延迟弹窗。见 GuideScreen.PermissionsStep。
}
