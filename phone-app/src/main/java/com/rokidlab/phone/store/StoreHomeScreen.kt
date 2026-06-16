package com.rokidlab.phone.store

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
import android.util.Log
import androidx.compose.ui.platform.LocalContext
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale
// AppIcon is in com.rokidlab.phone.store — accessible via wildcard import

private const val COLLAPSED_APP_COUNT = 5

// ===== 导航页面枚举 =====
enum class NavPage {
    STORE,
    SCREEN_MIRROR,
    PHONE_MIRROR,
    FILE_MANAGER,
    ADB_TOOLS,
    HID_GAMEPAD,
    SETTINGS
}

// ===== UI 状态 =====
internal data class StoreUiState(
    val apps: List<BrewApp>,
    val busy: Boolean,
    val refreshing: Boolean,
    val selectedHostApp: RokidHostApp,
    val hostAppInstalled: Boolean,
    val cxrConnection: CxrConnectionState,
    val downloadProgress: Map<String, Int>,
    val phoneInstallStates: Map<String, MainActivity.InstallState>,
    val glassesInstallStates: Map<String, MainActivity.InstallState>,
    val selfUpdateState: BrewSelfUpdateState,
    val prerequisites: PrerequisitesState = PrerequisitesState(),
    val screenMirrorState: ScreenMirrorState = ScreenMirrorState(),
    val phoneMirrorState: PhoneMirrorState = PhoneMirrorState(),
    val fileManagerState: FileManagerState = FileManagerState(),
    val showMirrorDialog: Boolean = false,
    val currentMirrorIndex: Int = 0,
    val currentLocale: String = "en",
)

// ===== UI 操作回调 =====
internal data class StoreActions(
    val onRefresh: () -> Unit,
    val onHostAppSelected: (RokidHostApp) -> Unit,
    val onGoToGuideStep1: () -> Unit,
    val onAuthorize: () -> Unit,
    val onInstall: (BrewApp, String) -> Unit,
    val onCheckGlassesInstall: (BrewApp) -> Unit,
    val onUninstall: (BrewApp, String) -> Unit,
    val onSelfUpdate: () -> Unit,
    val onSwitchMirror: () -> Unit,
    val onInstallApk: () -> Unit,
    val onLaunch: (BrewApp, String) -> Unit,
    // 前置条件
    val onSelectMirrorSource: () -> Unit,
    val onMirrorSelected: (Int) -> Unit,
    val onDismissMirrorDialog: () -> Unit,
    // 屏幕镜像
    val onScreenMirrorIpChange: (String) -> Unit,
    val onScreenMirrorConnect: () -> Unit,
    val onScreenMirrorStart: () -> Unit,
    val onScreenMirrorStop: () -> Unit,
    val onScreenMirrorInstallRokidLink: () -> Unit,
    val onScreenMirrorOpenRokidLink: () -> Unit,
    val onScreenMirrorRetry: () -> Unit,
    val onScreenMirrorBack: () -> Unit,
    // 手机投屏
    val onPhoneMirrorIpChange: (String) -> Unit,
    val onPhoneMirrorPortChange: (String) -> Unit,
    val onPhoneMirrorConnect: () -> Unit,
    val onPhoneMirrorStart: () -> Unit,
    val onPhoneMirrorStop: () -> Unit,
    val onPhoneMirrorInstallRokidLink: () -> Unit,
    val onPhoneMirrorOpenRokidLink: () -> Unit,
    val onPhoneMirrorRetry: () -> Unit,
    val onPhoneMirrorBack: () -> Unit,
    // 文件管理
    val onFileManagerIpChange: (String) -> Unit,
    val onFileManagerConnect: () -> Unit,
    val onFileManagerDisconnect: () -> Unit,
    val onFileManagerStop: () -> Unit,
    val onFileManagerInstallRokidLink: () -> Unit,
    val onFileManagerOpenRokidLink: () -> Unit,
    val onFileManagerRetry: () -> Unit,
    val onFileManagerBack: () -> Unit,
    val onFileManagerNavigateTo: (String) -> Unit,
    val onFileManagerRefresh: () -> Unit,
    val onFileManagerUploadFile: () -> Unit,
    val onFileManagerDownloadFile: (FileItem) -> Unit,
    val onFileManagerDeleteFile: (FileItem) -> Unit,
    val onFileManagerCreateFolder: (String) -> Unit,
    val onFileManagerRenameFile: (String, String) -> Unit,
    val onExitApp: () -> Unit,
    val onCancelDownload: (String) -> Unit,
    // 设置页 — 眼镜端服务
    val onSettingsReinstallRokidLink: () -> Unit,
    // 设置页 — 语言切换
    val onSwitchLanguage: (String) -> Unit,
)

// ===== 应用入口 =====
@Composable
internal fun BrewPhoneApp(
    state: StoreUiState,
    actions: StoreActions,
    iconLoader: IconLoader,
    mediaLoader: MediaLoader,
    app: LabApplication,
) {
    var currentPage by rememberSaveable { mutableStateOf(NavPage.STORE) }
    var selectedApp by remember { mutableStateOf<BrewApp?>(null) }
    var query by remember { mutableStateOf("") }
    var categoryFilter by remember { mutableStateOf<String?>(null) }
    var showingFeaturedList by remember { mutableStateOf(false) }
    var appListExpanded by remember { mutableStateOf(false) }
    var updateSheetVisible by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    
    val showGuide = !state.prerequisites.canInstallApps

    Box(modifier = Modifier.fillMaxSize().background(BrewBg)) {
        if (showGuide) {
            GuideScreen(
                currentStep = state.prerequisites.currentGuideStep,
                selectedHostApp = state.selectedHostApp,
                authorized = state.prerequisites.authorized,
                onSelectHostApp = actions.onHostAppSelected,
                onSelectMirrorSource = actions.onSelectMirrorSource,
                onAuthorize = actions.onAuthorize,
            )
        } else {
            MainInterface(
                state = state,
                actions = actions,
                iconLoader = iconLoader,
                mediaLoader = mediaLoader,
                currentPage = currentPage,
                onPageChange = { currentPage = it },
                selectedApp = selectedApp,
                onSelectedAppChange = { selectedApp = it },
                query = query,
                onQueryChange = { query = it },
                categoryFilter = categoryFilter,
                onCategoryFilterChange = { categoryFilter = it },
                showingFeaturedList = showingFeaturedList,
                onShowingFeaturedListChange = { showingFeaturedList = it },
                appListExpanded = appListExpanded,
                onAppListExpandedChange = { appListExpanded = it },
                updateSheetVisible = updateSheetVisible,
                onUpdateSheetVisibleChange = { updateSheetVisible = it },
                app = app,
            )
        }
        
    var showExitDialog by remember { mutableStateOf(false) }
    
    // 退出确认对话框
    if (!showGuide && showExitDialog) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showExitDialog = false },
            containerColor = BrewPanel,
            titleContentColor = BrewTextBright,
            textContentColor = BrewText,
            shape = RoundedCornerShape(12.dp),
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = ctx.getString(R.string.exit_app),
                        color = BrewRed,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp,
                    )
                }
            },
            text = {
                Column {
                    Box(
                        modifier = Modifier
                            .width(48.dp)
                            .height(4.dp)
                            .background(BrewRed)
                            .padding(bottom = 12.dp),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = ctx.getString(R.string.exit_confirm),
                        color = BrewText,
                        fontSize = 14.sp,
                        letterSpacing = 1.sp,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = ctx.getString(R.string.exit_confirm_desc),
                        color = BrewMuted,
                        fontSize = 12.sp,
                    )
                }
            },
            confirmButton = {
                Box(
                    modifier = Modifier
                        .height(44.dp)
                        .width(120.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(BrewBg)
                        .border(width = 1.dp, color = BrewRed, shape = RoundedCornerShape(12.dp))
                        .clickable {
                            showExitDialog = false
                            actions.onExitApp()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = ctx.getString(R.string.exit), color = BrewRed, fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                }
            },
            dismissButton = {
                Box(
                    modifier = Modifier
                        .height(44.dp)
                        .width(120.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(BrewBg)
                        .border(width = 1.dp, color = BrewBorder, shape = RoundedCornerShape(12.dp))
                        .clickable { showExitDialog = false },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = ctx.getString(R.string.cancel), color = BrewText, fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                }
            },
        )
    }
    
    if (!showGuide) {
        BackHandler(enabled = true) {
            if (currentPage != NavPage.STORE || selectedApp != null || showingFeaturedList || updateSheetVisible) {
                currentPage = NavPage.STORE
                selectedApp = null
                showingFeaturedList = false
                updateSheetVisible = false
            } else {
                showExitDialog = true
            }
        }
    }
    
    if (state.showMirrorDialog) {
            MirrorSourceDialog(
                currentIndex = state.currentMirrorIndex,
                onSelect = actions.onMirrorSelected,
                onDismiss = actions.onDismissMirrorDialog,
            )
        }
     }
 }

// ===== 屏幕镜像模块 =====
@Composable
private fun ScreenMirrorModule(
    state: StoreUiState,
    actions: StoreActions,
    app: LabApplication,
) {
    val ctx = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        ModuleHeader(title = ctx.getString(R.string.screen_mirror_title), subtitle = ctx.getString(R.string.screen_mirror_subtitle), color = BrewCyan)
        Spacer(modifier = Modifier.height(24.dp))
        
        RokidLinkStatusCard(
            installed = state.screenMirrorState.rokidLinkInstalled == true,
            installing = state.screenMirrorState.isInstallingRokidLink,
            running = state.screenMirrorState.rokidLinkRunning,
            onInstall = actions.onScreenMirrorInstallRokidLink,
            onOpen = actions.onScreenMirrorOpenRokidLink,
            onStop = actions.onScreenMirrorStop,
            ctx = ctx,
        )
        Spacer(modifier = Modifier.height(16.dp))
        
        IpAddressInputCard(
            label = ctx.getString(R.string.ip_address_label),
            value = app.screenMirrorIp,
            onValueChange = actions.onScreenMirrorIpChange,
            color = BrewCyan,
        )
        Spacer(modifier = Modifier.height(16.dp))
        
        BrutalButton(
            label = "▶ ${ctx.getString(R.string.start_mirror)}",
            color = BrewCyan,
            onClick = actions.onScreenMirrorStart,
        )
        Spacer(modifier = Modifier.height(24.dp))
        
        UsageInstructionsCard(
            color = BrewCyan,
            instructions = listOf(
                "1. ${ctx.getString(R.string.connecting_adb_hint)}",
                "2. ${String.format(ctx.getString(R.string.notification_content_hint), "RokidLink")}",
                "3. ${ctx.getString(R.string.ip_address_label)}（192.168.1.168）",
                "4. ▶ ${ctx.getString(R.string.start_mirror)}",
                "5. ${ctx.getString(R.string.screen_mirror_subtitle)}",
            ),
            ctx = ctx,
        )
    }
}

// ===== 手机投屏模块 =====
@Composable
private fun PhoneMirrorModule(
    state: StoreUiState,
    actions: StoreActions,
    app: LabApplication,
) {
    val ctx = LocalContext.current
    val isMirroring = state.phoneMirrorState.isMirroring
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        ModuleHeader(title = ctx.getString(R.string.phone_mirror_title), subtitle = ctx.getString(R.string.phone_mirror_subtitle), color = BrewPurple)
        Spacer(modifier = Modifier.height(24.dp))
        
        if (isMirroring) {
            // 投屏中状态
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    ctx.getString(R.string.mirroring),
                    color = BrewGreen,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    state.phoneMirrorState.connectionStatus,
                    color = BrewMuted,
                    fontSize = 14.sp
                )
                Spacer(modifier = Modifier.height(24.dp))
                BrutalButton(
                    label = "■ ${ctx.getString(R.string.stop_mirror)}",
                    color = BrewRed,
                    onClick = actions.onPhoneMirrorStart,
                )
            }
        } else {
            RokidLinkStatusCard(
                installed = state.phoneMirrorState.rokidLinkInstalled == true,
                installing = state.phoneMirrorState.isInstallingRokidLink,
                running = state.phoneMirrorState.rokidLinkRunning,
                onInstall = actions.onPhoneMirrorInstallRokidLink,
                onOpen = actions.onPhoneMirrorOpenRokidLink,
                onStop = actions.onPhoneMirrorStop,
                ctx = ctx,
            )
            Spacer(modifier = Modifier.height(16.dp))
            
            IpAddressInputCard(
                label = ctx.getString(R.string.ip_address_label),
                value = app.phoneMirrorIp,
                onValueChange = actions.onPhoneMirrorIpChange,
                color = BrewPurple,
            )
            Spacer(modifier = Modifier.height(16.dp))
            
            BrutalButton(
                label = "▶ ${ctx.getString(R.string.start_cast)}",
                color = BrewPurple,
                onClick = actions.onPhoneMirrorStart,
            )
            Spacer(modifier = Modifier.height(24.dp))
            
            UsageInstructionsCard(
                color = BrewPurple,
                instructions = listOf(
                    "1. ${ctx.getString(R.string.connecting_adb_hint)}",
                    "2. ${ctx.getString(R.string.ip_address_label)}（192.168.1.168）",
                    "3. ▶ ${ctx.getString(R.string.start_cast)}",
                    "4. ${ctx.getString(R.string.phone_mirror_subtitle)}",
                ),
                ctx = ctx,
            )
        }
    }
}

// ===== 文件管理模块 =====
@Composable
private fun FileManagerModule(
    state: StoreUiState,
    actions: StoreActions,
    app: LabApplication,
) {
    val ctx = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        ModuleHeader(title = ctx.getString(R.string.file_manager_title), subtitle = ctx.getString(R.string.file_manager_subtitle), color = BrewAmber)
        Spacer(modifier = Modifier.height(24.dp))
        
        RokidLinkStatusCard(
            installed = state.fileManagerState.rokidLinkInstalled == true,
            installing = state.fileManagerState.isInstallingRokidLink,
            running = state.fileManagerState.rokidLinkRunning,
            onInstall = actions.onFileManagerInstallRokidLink,
            onOpen = actions.onFileManagerOpenRokidLink,
            onStop = actions.onFileManagerStop,
            ctx = ctx,
        )
        Spacer(modifier = Modifier.height(16.dp))
        
        IpAddressInputCard(
            label = ctx.getString(R.string.ip_address_label),
            value = app.fileManagerIp,
            onValueChange = actions.onFileManagerIpChange,
            color = BrewAmber,
        )
        Spacer(modifier = Modifier.height(16.dp))
        
        BrutalButton(
            label = "▶ ${ctx.getString(R.string.open_file_manager)}",
            color = BrewAmber,
            onClick = actions.onFileManagerConnect,
        )
        Spacer(modifier = Modifier.height(12.dp))
        
        BrutalButton(
            label = ctx.getString(R.string.install_local_apk),
            color = BrewInfo,
            onClick = actions.onInstallApk,
        )
        Spacer(modifier = Modifier.height(24.dp))
        
        UsageInstructionsCard(
            color = BrewAmber,
            instructions = listOf(
                "1. ${ctx.getString(R.string.connecting_adb_hint)}（5555）",
                "2. RokidLink ${ctx.getString(R.string.install)}",
                "3. ${ctx.getString(R.string.ip_address_label)}（192.168.1.168）",
                "4. ▶ ${ctx.getString(R.string.open_file_manager)}",
                "5. ${ctx.getString(R.string.file_manager_subtitle)}",
            ),
            ctx = ctx,
        )
    }
}

// ===== 设置模块 =====
@Composable
private fun SettingsModule(
    state: StoreUiState,
    actions: StoreActions,
) {
    SettingsScreen(state = state, actions = actions)
}

// ===== ADB 工具模块 =====
@Composable
private fun AdbToolsModule(
    state: StoreUiState,
    actions: StoreActions,
    app: LabApplication,
) {
    val ctx = LocalContext.current
    var connected by remember { mutableStateOf(false) }
    var client by remember { mutableStateOf<AdbShellClient?>(null) }
    val scope = rememberCoroutineScope()
    
    // 各功能弹窗状态
    var showSysInfo by remember { mutableStateOf(false) }
    var showAppMgr by remember { mutableStateOf(false) }
    var showTimer by remember { mutableStateOf(false) }
    var showShell by remember { mutableStateOf(false) }
    
    fun disconnectClient() {
        client?.disconnect()
        client = null
        connected = false
    }
    
    fun getOrConnect(onConnected: (AdbShellClient?) -> Unit) {
        val existing = client
        if (existing != null && connected) {
            onConnected(existing)
            return
        }
        val ip = app.fileManagerIp.ifBlank { "192.168.1.168" }
        scope.launch(Dispatchers.IO) {
            try {
                val c = AdbShellClient(app, ip)
                val ok = c.connect()
                withContext(Dispatchers.Main) {
                    if (ok) {
                        client = c
                        connected = true
                        onConnected(c)
                    } else {
                        onConnected(null)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { onConnected(null) }
            }
        }
    }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        ModuleHeader(title = ctx.getString(R.string.adb_tools_title), subtitle = ctx.getString(R.string.adb_tools_subtitle), color = BrewInfo)
        Spacer(modifier = Modifier.height(24.dp))
        
        RokidLinkStatusCard(
            installed = state.fileManagerState.rokidLinkInstalled == true,
            installing = state.fileManagerState.isInstallingRokidLink,
            running = state.fileManagerState.rokidLinkRunning,
            onInstall = actions.onFileManagerInstallRokidLink,
            onOpen = actions.onFileManagerOpenRokidLink,
            onStop = actions.onFileManagerStop,
            ctx = ctx,
        )
        Spacer(modifier = Modifier.height(16.dp))
        
        IpAddressInputCard(
            label = ctx.getString(R.string.ip_address_label),
            value = app.fileManagerIp,
            onValueChange = actions.onFileManagerIpChange,
            color = BrewInfo,
        )
        Spacer(modifier = Modifier.height(24.dp))
        
        // 功能按钮
        BrutalButton(label = ctx.getString(R.string.system_info), color = BrewInfo, onClick = { showSysInfo = true })
        Spacer(modifier = Modifier.height(12.dp))
        BrutalButton(label = ctx.getString(R.string.app_manager), color = BrewGreen, onClick = { showAppMgr = true })
        Spacer(modifier = Modifier.height(12.dp))
        BrutalButton(label = ctx.getString(R.string.timer_func), color = BrewWarning, onClick = { showTimer = true })
        Spacer(modifier = Modifier.height(12.dp))
        BrutalButton(label = "Shell", color = BrewMagenta, onClick = { showShell = true })
        Spacer(modifier = Modifier.height(24.dp))
        
        UsageInstructionsCard(
            color = BrewInfo,
            instructions = listOf(
                "1. ${ctx.getString(R.string.connecting_adb_hint)}（5555）",
                "2. ${ctx.getString(R.string.ip_address_label)}",
                "3. ${ctx.getString(R.string.system_info)}/${ctx.getString(R.string.app_manager)}/${ctx.getString(R.string.timer_func)}",
                "4. ${ctx.getString(R.string.adb_tools_subtitle)}",
            ),
            ctx = ctx,
        )
    }
    
    // ── 弹窗 ──
    if (showSysInfo) SysInfoDialog(client, connected, scope, { cb -> getOrConnect(cb) }) { showSysInfo = false }
    if (showAppMgr) AppMgrDialog(client, connected, scope, { cb -> getOrConnect(cb) }) { showAppMgr = false }
    if (showTimer) TimerDialog(client, connected, scope, { cb -> getOrConnect(cb) }) { showTimer = false }
    if (showShell) ShellDialog(client, connected, scope, { cb -> getOrConnect(cb) }) { showShell = false }
}

// ── 弹窗通用连接组件 ──
@Composable
private fun AdbDialogContent(
    title: String,
    color: Color,
    client: AdbShellClient?,
    connected: Boolean,
    scope: CoroutineScope,
    getOrConnect: ((AdbShellClient?) -> Unit) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    titleContent: (@Composable () -> Unit)? = null,
    content: @Composable (AdbShellClient) -> Unit,
) {
    val ctx = LocalContext.current
    var status by remember { mutableStateOf(if (connected && client != null) "ready" else "connecting") }
    var errorMsg by remember { mutableStateOf("") }
    
    // 自动连接
    LaunchedEffect(Unit) {
        if (connected && client != null) {
            status = "ready"
        } else {
            status = "connecting"
            getOrConnect { c ->
                if (c != null) {
                    status = "ready"
                } else {
                    status = "error"
                    errorMsg = ctx.getString(R.string.connection_failed_adb)
                }
            }
        }
    }
    
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier.fillMaxWidth().padding(horizontal = 24.dp)
                .clip(RoundedCornerShape(12.dp)).background(BrewPanel)
                .border(1.dp, BrewBorder, RoundedCornerShape(12.dp))
                .padding(16.dp),
        ) {
            Column {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    if (titleContent != null) {
                        titleContent()
                    } else {
                        Text(title, color = color, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                    TextButton(onClick = onDismiss) { Text(ctx.getString(R.string.close), color = BrewMuted) }
                }
                Spacer(Modifier.height(8.dp))
                
                when (status) {
                    "connecting" -> {
                        Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(ctx.getString(R.string.connecting_adb), color = color, fontSize = 14.sp)
                                Spacer(Modifier.height(8.dp))
                                Text(ctx.getString(R.string.connecting_adb_hint), color = BrewMuted, fontSize = 11.sp)
                            }
                        }
                    }
                    "error" -> {
                        Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(ctx.getString(R.string.connection_failed_adb), color = BrewRed, fontSize = 14.sp)
                                Spacer(Modifier.height(4.dp))
                                Text(errorMsg, color = BrewMuted, fontSize = 11.sp)
                                Spacer(Modifier.height(12.dp))
                                BrutalButton(label = ctx.getString(R.string.retry), color = color, onClick = {
                                     status = "connecting"
                                     getOrConnect { c ->
                                         if (c != null) { status = "ready" }
                                         else { status = "error"; errorMsg = ctx.getString(R.string.connection_failed_adb) }
                                     }
                                 }, compact = true)
                            }
                        }
                    }
                    "ready" -> {
                        val c = client ?: return@Column
                        content(c)
                    }
                }
            
            }
        }
    }
}

// ── 系统信息弹窗 ──
@Composable
private fun SysInfoDialog(client: AdbShellClient?, connected: Boolean, scope: CoroutineScope, getOrConnect: ((AdbShellClient?) -> Unit) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    AdbDialogContent(ctx.getString(R.string.system_info), BrewInfo, client, connected, scope, getOrConnect, onDismiss) { c ->
        var loading by remember { mutableStateOf(true) }
        var content by remember { mutableStateOf("") }
        
        LaunchedEffect(Unit) {
            loading = true
            scope.launch(Dispatchers.IO) {
                try {
                    val cmd = """getprop ro.product.manufacturer;getprop ro.product.model;getprop ro.build.version.release;getprop ro.build.version.sdk;getprop ro.serialno;getprop ro.product.board;getprop ro.build.display.id;dumpsys battery 2>/dev/null | grep -E "level:|AC powered:|health:";cat /proc/meminfo 2>/dev/null | grep MemTotal;df /data 2>/dev/null | tail -1"""
                    val raw = c.executeShellCommand(cmd)
                    val lines = raw.lines().map { it.trim() }.filter { it.isNotBlank() }
                    
                    val manufacturer = lines.getOrNull(0)?.takeIf { it.isNotEmpty() && !it.contains(":") } ?: "?"
                    val model = lines.getOrNull(1)?.takeIf { it.isNotEmpty() && !it.contains(":") } ?: "?"
                    val release = lines.getOrNull(2)?.takeIf { it.isNotEmpty() } ?: "?"
                    val sdk = lines.getOrNull(3)?.takeIf { it.isNotEmpty() } ?: "?"
                    val serial = lines.getOrNull(4)?.takeIf { it.isNotEmpty() } ?: "?"
                    val board = lines.getOrNull(5)?.takeIf { it.isNotEmpty() } ?: "?"
                    val build = lines.getOrNull(6)?.takeIf { it.isNotEmpty() } ?: "?"
                    
                    val level = lines.firstOrNull { it.contains("level:") }?.substringAfter(":")?.trim()?.let { "${it}%" } ?: "?"
                    val charging = lines.firstOrNull { it.contains("AC powered:") }?.let {
                        if (it.contains("true")) ctx.getString(R.string.charging) else ctx.getString(R.string.not_charging)
                    } ?: "?"
                    val health = lines.firstOrNull { it.contains("health:") }?.substringAfter(":")?.trim() ?: "?"
                    val memTotal = lines.firstOrNull { it.contains("MemTotal") }?.substringAfter(":")?.trim() ?: "?"
                    val storageParts = lines.firstOrNull { it.contains("/data") || it.startsWith("/dev") }?.split("\\s+".toRegex())
                    val stTotal = storageParts?.getOrNull(1)?.let { try { "${it.toLong() / 1024 / 1024}GB" } catch (_:Exception) { it } } ?: "?"
                    val stUsed = storageParts?.getOrNull(2)?.let { try { "${it.toLong() / 1024 / 1024}GB" } catch (_:Exception) { it } } ?: "?"
                    
                    withContext(Dispatchers.Main) {
                        content = buildString {
                            appendLine("━━━ Device Info ━━━")
                            appendLine("  Manufacturer: $manufacturer")
                            appendLine("  Model: $model")
                            appendLine("  OS: Android $release (API $sdk)")
                            appendLine("  Processor: $board")
                            appendLine("  Serial: $serial")
                            appendLine("  Build: $build")
                            appendLine("")
                            appendLine("━━━ Storage ━━━")
                            appendLine("  RAM: $memTotal")
                            if (stTotal != "?") appendLine("  Data: $stTotal total / $stUsed used")
                            appendLine("")
                            appendLine("━━━ Battery ━━━")
                            appendLine("  Level: $level")
                            appendLine("  Power: $charging")
                            appendLine("  Health: $health")
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) { content = "${ctx.getString(R.string.fetch_failed)}: ${e.message}" }
                }
                withContext(Dispatchers.Main) { loading = false }
            }
        }
        
        Box(
             Modifier.fillMaxWidth().heightIn(max = 500.dp)
                 .clip(RoundedCornerShape(6.dp)).background(BrewBg)
                 .padding(10.dp).verticalScroll(rememberScrollState()),
        ) {
            if (loading) {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(ctx.getString(R.string.fetching_sysinfo), color = BrewMuted, fontSize = 13.sp)
                }
            } else {
                Text(content, color = BrewText, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

// ── 应用管理弹窗 ──
@Composable
private fun AppMgrDialog(client: AdbShellClient?, connected: Boolean, scope: CoroutineScope, getOrConnect: ((AdbShellClient?) -> Unit) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    AdbDialogContent(ctx.getString(R.string.app_manager), BrewGreen, client, connected, scope, getOrConnect, onDismiss) { c ->
        var packages by remember { mutableStateOf(emptyList<String>()) }
        var disabledPkgs by remember { mutableStateOf(emptySet<String>()) }
        var showSystem by remember { mutableStateOf(false) }
        var search by remember { mutableStateOf("") }
        var loading by remember { mutableStateOf(true) }
        var selectedPkg by remember { mutableStateOf("") }
        var statusMsg by remember { mutableStateOf("") }
        
        val filtered = if (search.isBlank()) packages else packages.filter { it.contains(search, ignoreCase = true) }
        val isSelectedFrozen = selectedPkg.isNotEmpty() && (selectedPkg in disabledPkgs)
        
        LaunchedEffect(showSystem) {
            Log.w("AppMgr", "LaunchedEffect start showSystem=$showSystem")
            loading = true
            selectedPkg = ""
            try {
                val pkgs = withContext(Dispatchers.IO) { c.listPackages(showSystem) }
                packages = pkgs
            } catch (e: Exception) { Log.w("AppMgr", "listPackages FAILED: ${e.message}") }
            try {
                val disabled = withContext(Dispatchers.IO) { c.listDisabledPackages() }
                disabledPkgs = disabled
            } catch (e: Exception) { Log.w("AppMgr", "listDisabled FAILED: ${e.message}") }
            loading = false
        }

        fun refreshAll(from: String = "unknown") {
            scope.launch {
                loading = true
                selectedPkg = ""
                try {
                    val pkgs = withContext(Dispatchers.IO) { c.listPackages(showSystem) }
                    packages = pkgs
                } catch (e: Exception) { Log.w("AppMgr", "refreshAll listPackages FAILED: ${e.message}") }
                try {
                    val disabled = withContext(Dispatchers.IO) { c.listDisabledPackages() }
                    disabledPkgs = disabled
                } catch (e: Exception) { Log.w("AppMgr", "refreshAll listDisabled FAILED: ${e.message}") }
                loading = false
            }
        }
        
        fun doAction(action: suspend (String) -> String, pkg: String, shouldRefresh: Boolean = true) {
            scope.launch(Dispatchers.IO) {
                try {
                    val result = action(pkg)
                    withContext(Dispatchers.Main) {
                        statusMsg = result.lines().firstOrNull { it.isNotBlank() } ?: ctx.getString(R.string.done_label)
                        if (shouldRefresh) refreshAll("after_action")
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) { statusMsg = "${ctx.getString(R.string.error_label)}: ${e.message}" }
                }
            }
        }
        
        Column(modifier = Modifier.fillMaxWidth().heightIn(max = 580.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BrutalTextField(
                    value = search, onValueChange = { search = it },
                    placeholder = ctx.getString(R.string.search_apps_placeholder), color = BrewGreen,
                    modifier = Modifier.weight(1f), singleLine = true,
                )
                Spacer(Modifier.width(6.dp))
                Box(
                    Modifier.height(42.dp).clip(RoundedCornerShape(8.dp))
                        .background(if (showSystem) BrewGreen.copy(alpha = 0.2f) else Color.Transparent)
                        .border(1.dp, if (showSystem) BrewGreen else BrewBorder, RoundedCornerShape(8.dp))
                        .clickable { showSystem = !showSystem; selectedPkg = "" }
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(if (showSystem) ctx.getString(R.string.filter_all) else ctx.getString(R.string.filter_third_party), color = if (showSystem) BrewGreen else BrewMuted, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.width(6.dp))
                val refreshTransition = rememberInfiniteTransition(label = "refreshSpin")
                val refreshAngle by refreshTransition.animateFloat(
                    initialValue = 0f, targetValue = 360f,
                    animationSpec = infiniteRepeatable(tween(800, easing = LinearEasing)),
                    label = "refreshAngle",
                )
                Box(
                    Modifier.size(42.dp).clip(RoundedCornerShape(8.dp))
                        .background(BrewGreen.copy(alpha = 0.12f))
                        .border(1.dp, BrewGreen.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                        .clickable { refreshAll("refresh_icon") },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Outlined.Refresh, contentDescription = ctx.getString(R.string.refresh),
                        tint = BrewGreen, modifier = Modifier.size(24.dp)
                            .graphicsLayer { rotationZ = if (loading) refreshAngle else 0f },
                    )
                }
            }
            
            Spacer(Modifier.height(8.dp))
            
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                    .background(if (selectedPkg.isNotEmpty()) BrewGreen.copy(alpha = 0.08f) else Color.Transparent)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            ) {
                if (selectedPkg.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        ActionButton(ctx.getString(R.string.launch), BrewSuccess) { doAction({ c.launchApp(it) }, selectedPkg) }
                        ActionButton(ctx.getString(R.string.uninstall), BrewRed) { doAction({ c.uninstallApp(it) }, selectedPkg) }
                        ActionButton(
                             if (isSelectedFrozen) ctx.getString(R.string.unfreeze) else ctx.getString(R.string.freeze),
                             if (isSelectedFrozen) BrewSuccess else BrewWarning,
                         ) {
                             if (isSelectedFrozen) {
                                 doAction({ c.enableApp(it) }, selectedPkg)
                             } else {
                                 doAction({ c.disableApp(it) }, selectedPkg)
                             }
                         }
                        ActionButton(ctx.getString(R.string.extract), BrewMagenta) {
                            val pkg = selectedPkg
                            scope.launch(Dispatchers.IO) {
                                if (pkg.isEmpty()) return@launch
                                val r = c.extractApkToDownloads(pkg)
                                Log.i("AppMgr", "extract result: $r")
                                withContext(Dispatchers.Main) { statusMsg = r }
                            }
                        }
                    }
                } else {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(ctx.getString(R.string.select_app_below), color = BrewMuted.copy(alpha = 0.5f), fontSize = 13.sp)
                    }
                }
            }
            
            if (statusMsg.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text("  $statusMsg", color = if (statusMsg.startsWith(ctx.getString(R.string.error_label))) BrewRed else BrewGreen, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(String.format(ctx.getString(R.string.app_count_fmt), filtered.size), color = BrewMuted, fontSize = 12.sp)
                if (loading) { Spacer(Modifier.width(8.dp)); Text(ctx.getString(R.string.updating_dots), color = BrewGreen.copy(alpha = 0.6f), fontSize = 11.sp) }
            }
            Spacer(Modifier.height(2.dp))
            
            Box(Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                if (loading && packages.isEmpty()) {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text(ctx.getString(R.string.loading), color = BrewMuted, fontSize = 14.sp)
                    }
                } else {
                    Column {
                        filtered.forEach { pkg ->
                            val isSelected = pkg == selectedPkg
                            val isFrozen = pkg in disabledPkgs
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                                    .background(if (isSelected) BrewGreen.copy(alpha = 0.12f) else BrewBg)
                                    .border(if (isSelected) 1.dp else 0.dp, BrewGreen.copy(alpha = 0.3f), RoundedCornerShape(6.dp))
                                    .clickable { selectedPkg = if (isSelected) "" else pkg }
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(pkg, color = if (isSelected) BrewGreen else BrewText, fontSize = 11.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (isFrozen) {
                                    Text(" ❄️", color = Color(0xFF4FC3F7), fontSize = 11.sp)
                                    Spacer(Modifier.width(4.dp))
                                }
                                if (isSelected) {
                                    Text("  ✓", color = BrewGreen, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                            Spacer(Modifier.height(2.dp))
                        }
                    }
                }
            }
        }
    }
}

// ── 定时功能弹窗（80% 界面，可滑动） ──
@Composable
private fun TimerDialog(client: AdbShellClient?, connected: Boolean, scope: CoroutineScope, getOrConnect: ((AdbShellClient?) -> Unit) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AdbDialogContent(context.getString(R.string.timer_func), BrewWarning, client, connected, scope, getOrConnect, onDismiss) { c ->
        // ── 定时消息状态 ──
        var msgContent by remember { mutableStateOf("") }
        var msgInterval by remember { mutableStateOf("5") }
        var msgCount by remember { mutableStateOf("5") }
        var msgRunning by remember { mutableStateOf(false) }
        var msgSentCount by remember { mutableStateOf(0) }
        
        // ── 定时打开应用状态 ──
        var appPackages by remember { mutableStateOf(emptyList<String>()) }
        var selectedApp by remember { mutableStateOf("") }
        var appInterval by remember { mutableStateOf("10") }
        var appCount by remember { mutableStateOf("5") }
        var appRunning by remember { mutableStateOf(false) }
        var appLaunchCount by remember { mutableStateOf(0) }
        var appLoading by remember { mutableStateOf(true) }
        
        // ── 加载应用列表 ──
        LaunchedEffect(Unit) {
            appLoading = true
            val pkgs = withContext(Dispatchers.IO) { c.listPackages(false) }
            appPackages = pkgs
            appLoading = false
        }
        
        // ── 本地通知发送 ──
        fun postLocalNotification(text: String) {
            try {
                val nm = context.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    val channel = android.app.NotificationChannel(
                        "timer_notify", "定时消息",
                        android.app.NotificationManager.IMPORTANCE_HIGH
                    ).apply { description = "来自 ADB 工具的定时消息" }
                    nm.createNotificationChannel(channel)
                }
                val notification = android.app.Notification.Builder(context, "timer_notify")
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle("Rokid")
                    .setContentText(text)
                    .setAutoCancel(true)
                    .setPriority(android.app.Notification.PRIORITY_HIGH)
                    .apply {
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                            setChannelId("timer_notify")
                        }
                    }
                @Suppress("DEPRECATION")
                if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) {
                    notification.setPriority(android.app.Notification.PRIORITY_HIGH)
                }
                nm.notify(System.currentTimeMillis().toInt(), notification.build())
            } catch (e: Exception) {
                Log.w("AppMgr", "本地通知失败: ${e.message}")
            }
        }
        
        // 可滚动内容 - 占总高约80%
        Column(
            modifier = Modifier.fillMaxWidth()
                .heightIn(max = 600.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ========================
            // 模块1: 定时消息
            // ========================
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                    .background(BrewWarning.copy(alpha = 0.06f))
                    .border(1.dp, BrewWarning.copy(alpha = 0.2f), RoundedCornerShape(10.dp))
                    .padding(12.dp),
            ) {
                Column {
                    Text("定时消息", color = BrewWarning, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    
                    Text("通知内容", color = BrewMuted, fontSize = 12.sp)
                    Spacer(Modifier.height(4.dp))
                    BrutalTextField(
                        value = msgContent, onValueChange = { msgContent = it },
                        placeholder = "输入要在眼镜上显示的消息...", color = BrewWarning,
                        modifier = Modifier.fillMaxWidth(), singleLine = false,
                    )
                    
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("间隔", color = BrewMuted, fontSize = 12.sp)
                        Spacer(Modifier.width(6.dp))
                        BrutalTextField(
                            value = msgInterval, onValueChange = { msgInterval = it },
                            placeholder = "5", color = BrewWarning,
                            modifier = Modifier.width(70.dp), singleLine = true,
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("秒 ×", color = BrewMuted, fontSize = 12.sp)
                        Spacer(Modifier.width(6.dp))
                        BrutalTextField(
                            value = msgCount, onValueChange = { msgCount = it },
                            placeholder = "5", color = BrewWarning,
                            modifier = Modifier.width(60.dp), singleLine = true,
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("次", color = BrewMuted, fontSize = 12.sp)
                    }
                    
                    Spacer(Modifier.height(8.dp))
                    // 消息启动/停止按钮
                    Box(
                        Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(10.dp))
                            .background(if (msgRunning) BrewRed.copy(alpha = 0.15f) else BrewWarning.copy(alpha = 0.15f))
                            .border(1.dp, if (msgRunning) BrewRed else BrewWarning, RoundedCornerShape(10.dp))
                            .clickable {
                                if (msgRunning) { msgRunning = false }
                                else {
                                    if (msgContent.isBlank()) return@clickable
                                    msgRunning = true
                                    msgSentCount = 0
                                    val maxCount = msgCount.toIntOrNull() ?: Int.MAX_VALUE
                                    scope.launch {
                                        while (msgRunning && msgSentCount < maxCount) {
                                            postLocalNotification(msgContent)
                                            try {
                                                withContext(Dispatchers.IO) { c.sendNotification("Rokid", msgContent) }
                                            } catch (_: Exception) { }
                                            msgSentCount++
                                            if (msgSentCount >= maxCount) { msgRunning = false; break }
                                            delay((msgInterval.toLongOrNull() ?: 5) * 1000)
                                        }
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (msgRunning) {
                                Box(Modifier.size(8.dp).clip(CircleShape).background(BrewRed))
                                Spacer(Modifier.width(6.dp))
                                Text("■ 停止 ($msgSentCount)", color = BrewRed, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            } else {
                                Box(Modifier.size(8.dp).clip(CircleShape).background(BrewWarning))
                                Spacer(Modifier.width(6.dp))
                                Text("▶ 开始", color = BrewWarning, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
            
            // ========================
            // 模块2: 定时打开应用
            // ========================
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                    .background(BrewInfo.copy(alpha = 0.06f))
                    .border(1.dp, BrewInfo.copy(alpha = 0.2f), RoundedCornerShape(10.dp))
                    .padding(12.dp),
            ) {
                Column {
                    Text("定时打开应用", color = BrewInfo, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    
                    // 应用选择
                    Text("选择应用", color = BrewMuted, fontSize = 12.sp)
                    Spacer(Modifier.height(4.dp))
                    if (appLoading) {
                        Text("加载应用中...", color = BrewMuted, fontSize = 12.sp)
                    } else {
                        var showAppPicker by remember { mutableStateOf(false) }
                        
                        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                            .background(BrewBg).border(1.dp, BrewBorder, RoundedCornerShape(8.dp))
                            .clickable { showAppPicker = true }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                if (selectedApp.isEmpty()) "点击选择应用..." else selectedApp,
                                color = if (selectedApp.isEmpty()) BrewMuted else BrewText,
                                fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("▼", color = BrewInfo, fontSize = 11.sp)
                        }
                        
                        // 应用选择列表
                        if (showAppPicker) {
                            Box(
                                Modifier.fillMaxWidth().heightIn(max = 250.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(BrewPanel)
                                    .border(1.dp, BrewBorder, RoundedCornerShape(8.dp))
                                    .verticalScroll(rememberScrollState()),
                            ) {
                                Column {
                                    appPackages.forEach { pkg ->
                                        val isSel = pkg == selectedApp
                                        Row(
                                            Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp))
                                                .background(if (isSel) BrewInfo.copy(alpha = 0.1f) else Color.Transparent)
                                                .clickable { selectedApp = pkg; showAppPicker = false }
                                                .padding(horizontal = 10.dp, vertical = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text(pkg, color = if (isSel) BrewInfo else BrewText, fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            if (isSel) Text("✓", color = BrewInfo, fontSize = 12.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("间隔", color = BrewMuted, fontSize = 12.sp)
                        Spacer(Modifier.width(6.dp))
                        BrutalTextField(
                            value = appInterval, onValueChange = { appInterval = it },
                            placeholder = "10", color = BrewInfo,
                            modifier = Modifier.width(70.dp), singleLine = true,
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("秒 ×", color = BrewMuted, fontSize = 12.sp)
                        Spacer(Modifier.width(6.dp))
                        BrutalTextField(
                            value = appCount, onValueChange = { appCount = it },
                            placeholder = "5", color = BrewInfo,
                            modifier = Modifier.width(60.dp), singleLine = true,
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("次", color = BrewMuted, fontSize = 12.sp)
                    }
                    
                    Spacer(Modifier.height(8.dp))
                    // 应用启动/停止按钮
                    Box(
                        Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(10.dp))
                            .background(if (appRunning) BrewRed.copy(alpha = 0.15f) else BrewInfo.copy(alpha = 0.15f))
                            .border(1.dp, if (appRunning) BrewRed else BrewInfo, RoundedCornerShape(10.dp))
                            .clickable {
                                if (appRunning) { appRunning = false }
                                else {
                                    if (selectedApp.isEmpty()) return@clickable
                                    appRunning = true
                                    appLaunchCount = 0
                                    val maxCount = appCount.toIntOrNull() ?: Int.MAX_VALUE
                                    scope.launch {
                                        while (appRunning && appLaunchCount < maxCount) {
                                            try {
                                                withContext(Dispatchers.IO) { c.launchApp(selectedApp) }
                                            } catch (_: Exception) { }
                                            appLaunchCount++
                                            if (appLaunchCount >= maxCount) { appRunning = false; break }
                                            delay((appInterval.toLongOrNull() ?: 10) * 1000)
                                        }
                                    }
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (appRunning) {
                                Box(Modifier.size(8.dp).clip(CircleShape).background(BrewRed))
                                Spacer(Modifier.width(6.dp))
                                Text("■ 停止 ($appLaunchCount)", color = BrewRed, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            } else {
                                Box(Modifier.size(8.dp).clip(CircleShape).background(BrewInfo))
                                Spacer(Modifier.width(6.dp))
                                Text("▶ 开始定时启动", color = BrewInfo, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
            
            // 底部留白
            Spacer(Modifier.height(8.dp))
        }
    }
}

// ── Shell命令弹窗（终端风格） ──
@Composable
private fun ShellDialog(client: AdbShellClient?, connected: Boolean, scope: CoroutineScope, getOrConnect: ((AdbShellClient?) -> Unit) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    AdbDialogContent(ctx.getString(R.string.shell_command), BrewMagenta, client, connected, scope, getOrConnect, onDismiss,
        titleContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Shell", color = BrewMagenta, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("命令", color = BrewInfo, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }
    ) { c ->
        data class ShellEntry(val type: String, val text: String, val time: String)
        
        var entries by remember { mutableStateOf(listOf(ShellEntry("info", "Shell 已就绪，输入命令后按 Enter 执行", ""))) }
        var cmd by remember { mutableStateOf("") }
        var loading by remember { mutableStateOf(false) }
        var history by remember { mutableStateOf(listOf<String>()) }
        val listScrollState = rememberScrollState()
        val status = if (connected) "已连接" else "未连接"
        val ts = { java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date()) }
        
        fun execute(input: String) {
            if (input.isBlank() || loading) return
            cmd = ""
            loading = true
            history = (history + input).takeLast(50)
            entries = entries + ShellEntry("cmd", "Shell> $input", ts())
            scope.launch(Dispatchers.IO) {
                try {
                    val r = c.executeShellCommand(input)
                    val output = r.trim().ifEmpty { "(空)" }
                    withContext(Dispatchers.Main) {
                        entries = entries + ShellEntry("result", output, ts())
                        loading = false
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        entries = entries + ShellEntry("error", "错误: ${e.message}", ts())
                        loading = false
                    }
                }
            }
        }
        
        // 整体固定大布局
        Column(Modifier.fillMaxWidth().height(460.dp)) {
            // ═══ 输出区（占大部分空间）═══
            Box(
                Modifier.fillMaxWidth().weight(1f)
                    .clip(RoundedCornerShape(6.dp)).background(Color(0xFF1E1E1E))
                    .padding(10.dp).verticalScroll(listScrollState),
            ) {
                Column {
                    entries.forEach { entry ->
                        when (entry.type) {
                            "cmd" -> Row {
                                Text(entry.time, color = Color(0xFF6A9955), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                                Spacer(Modifier.width(4.dp))
                                Text(entry.text, color = Color(0xFF569CD6), fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                            }
                            "result" -> Text(entry.text, color = Color(0xFFD4D4D4), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                            "error" -> Text(entry.text, color = Color(0xFFF44747), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                            "info" -> Text(entry.text, color = Color(0xFF6A9955), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                }
            }
            
            Spacer(Modifier.height(6.dp))
            
            // ═══ 输入区（底部）═══
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                    .background(BrewBg).padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Shell>", color = Color(0xFFE53935), fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.width(6.dp))
                BasicTextField(
                    value = cmd,
                    onValueChange = { cmd = it },
                    modifier = Modifier.weight(1f).heightIn(min = 38.dp),
                    singleLine = true,
                    textStyle = TextStyle(color = BrewText, fontSize = 14.sp, fontFamily = FontFamily.Monospace),
                     cursorBrush = SolidColor(Color(0xFFE53935)),
                     keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { execute(cmd.trim()) }),
                    decorationBox = { innerTextField ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (cmd.isEmpty()) Text("输入命令...", color = BrewMuted.copy(alpha = 0.4f), fontSize = 14.sp, fontFamily = FontFamily.Monospace)
                            innerTextField()
                        }
                    },
                )
                Spacer(Modifier.width(6.dp))
                Box(
                    Modifier.height(34.dp).clip(RoundedCornerShape(6.dp))
                        .background(if (loading) BrewMuted.copy(alpha = 0.2f) else BrewMagenta.copy(alpha = 0.2f))
                        .border(1.dp, if (loading) BrewMuted.copy(alpha = 0.3f) else BrewMagenta.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                        .clickable(enabled = !loading) { execute(cmd.trim()) }
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(if (loading) "..." else "执行", color = if (loading) BrewMuted else BrewInfo, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                }
            }
            
            // ═══ 历史标签 ═══
            if (history.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    history.takeLast(12).reversed().forEach { h ->
                        Box(
                            Modifier.clip(RoundedCornerShape(4.dp)).background(BrewMagenta.copy(alpha = 0.1f))
                                .clickable { cmd = h }.padding(horizontal = 6.dp, vertical = 2.dp),
                        ) { Text(h, color = BrewMagenta.copy(alpha = 0.7f), fontSize = 9.sp) }
                        Spacer(Modifier.width(4.dp))
                    }
                }
            }
            
            // ═══ 状态栏 ═══
            Spacer(Modifier.height(4.dp))
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(BrewBg).padding(horizontal = 8.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(if (connected) Color(0xFF4CAF50) else Color(0xFFE53935)))
                Spacer(Modifier.width(6.dp))
                Text(status, color = BrewMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.weight(1f))
                Text("${entries.size - 1} 条记录", color = BrewMuted.copy(alpha = 0.6f), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
private fun ActionButton(label: String, color: Color, onClick: () -> Unit) {
    Box(
        Modifier.height(36.dp).clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.18f))
            .border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = color, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

// ===== 小工具函数 =====
@Composable
private fun MiniButton(label: String, color: Color, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.15f))
            .clickable { onClick() }
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(label, color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

// ===== 模块通用组件 =====
@Composable
private fun ModuleHeader(title: String, subtitle: String, color: Color) {
    Column {
        Text(text = title, color = color, fontSize = 32.sp, fontWeight = FontWeight.Black, letterSpacing = 4.sp)
        Spacer(modifier = Modifier.height(8.dp))
        Text(text = subtitle, color = BrewMuted, fontSize = 14.sp)
    }
}

@Composable
private fun FeaturedAppItem(app: BrewApp, iconLoader: IconLoader, mediaLoader: MediaLoader) {
    Box(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        AppIcon(
            app = app,
            iconLoader = iconLoader,
            mediaLoader = mediaLoader,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun IpAddressInputCard(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    color: Color = BrewCyan,
) {
    var text by remember { mutableStateOf(value) }

    Box(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(BrewPanel, RoundedCornerShape(12.dp))
                .border(width = 1.dp, color = color, shape = RoundedCornerShape(12.dp))
                .padding(16.dp),
        ) {
            Text(text = label, color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, modifier = Modifier.padding(bottom = 8.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { newVal ->
                    text = newVal
                    onValueChange(newVal)
                },
                placeholder = { Text("192.168.1.168", color = BrewMuted) },
                textStyle = TextStyle(color = BrewTextBright),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = color, unfocusedBorderColor = BrewBorder),
            )
        }
    }
}

@Composable
private fun UsageInstructionsCard(
    color: Color = BrewAmber,
    instructions: List<String>,
    ctx: android.content.Context,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(BrewPanel, RoundedCornerShape(12.dp))
            .border(width = 1.dp, color = BrewBorder, shape = RoundedCornerShape(12.dp))
            .padding(16.dp),
    ) {
        Column {
            Text(text = ctx.getString(R.string.usage_instructions), color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, modifier = Modifier.padding(bottom = 12.dp))
            instructions.forEach { instruction ->
                Text(text = instruction, color = BrewMuted, fontSize = 12.sp, lineHeight = 20.sp, modifier = Modifier.padding(bottom = 4.dp))
            }
        }
    }
}

// ===== Neo Brutalist 组件 =====

@Composable
private fun RokidLinkStatusCard(installed: Boolean, installing: Boolean, running: Boolean = false, onInstall: () -> Unit, onOpen: () -> Unit, onStop: (() -> Unit)? = null, ctx: android.content.Context) {
    val statusColor = when {
        running -> BrewWarning
        installing -> BrewCyan
        installed -> BrewSuccess
        else -> BrewRed
    }
    val statusBg = when {
        running -> BrewWarning
        installing -> BrewCyan
        installed -> BrewSuccess
        else -> BrewRed
    }
    val statusText = when {
        running -> ctx.getString(R.string.running)
        installing -> ctx.getString(R.string.installing)
        installed -> ctx.getString(R.string.installed)
        else -> ctx.getString(R.string.not_installed)
    }
    val statusIcon = when {
        running -> "▶"
        installing -> "►"
        installed -> "✔"
        else -> "✘"
    }
    
    // 安装中脉冲动画 — 偏移装饰线呼吸效果
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseOffset by infiniteTransition.animateFloat(
        initialValue = 0f, targetValue = 8f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse-offset",
    )
    
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(BrewPanel)
            .border(width = 1.dp, color = statusColor.copy(alpha = 0.3f), shape = RoundedCornerShape(12.dp)),
    ) {
        // 顶部状态条
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(statusBg)
                .padding(horizontal = 20.dp, vertical = 14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = statusIcon,
                    color = BrewBg,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(end = 12.dp),
                )
                Column {
                    Text(
                        text = "ROKIDLINK $statusText",
                        color = BrewBg,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp,
                    )
                    Text(
                        text = ctx.getString(R.string.glasses_services),
                        color = BrewBg.copy(alpha = 0.7f),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 1.sp,
                    )
                }
            }
        }
        
        // 偏移装饰线（安装中呼吸脉冲）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .background(BrewBorder)
                .offset(x = if (installing) pulseOffset.dp else 8.dp),
        )
        
        // 底部操作区
        Box(modifier = Modifier.padding(16.dp)) {
            if (installing) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .background(BrewPanelAlt, RoundedCornerShape(12.dp))
                        .border(width = 1.dp, color = BrewCyan.copy(alpha = 0.3f), shape = RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = "⟳", color = BrewCyan, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 12.dp))
                        Text(text = ctx.getString(R.string.installing), color = BrewCyan, fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
                    }
                }
            } else if (running) {
                BrutalButton(
                    label = "● ${ctx.getString(R.string.stop_mirror)} RokidLink",
                    color = BrewRed,
                    onClick = onStop ?: {},
                )
            } else if (!installed) {
                BrutalButton(
                    label = "● ${ctx.getString(R.string.install)} RokidLink",
                    color = BrewAmber,
                    onClick = onInstall,
                )
            } else {
                BrutalButton(
                    label = "▶ ${ctx.getString(R.string.launch)} RokidLink",
                    color = BrewSuccess,
                    onClick = onOpen,
                )
            }
        }
    }
}

@Composable
private fun BrutalButton(label: String, color: Color, onClick: () -> Unit, compact: Boolean = false) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.98f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "btn-press",
    )
    val height = if (compact) 32.dp else 52.dp
    val shape = RoundedCornerShape(if (compact) 8.dp else 12.dp)
    Box(
        modifier = Modifier
            .then(if (compact) Modifier.wrapContentWidth() else Modifier.fillMaxWidth())
            .height(height)
            .graphicsLayer { scaleX = pressScale; scaleY = pressScale; alpha = if (isPressed) 0.85f else 1f }
            .clip(shape)
            .background(color.copy(alpha = 0.12f))
            .border(width = 1.dp, color = color.copy(alpha = 0.5f), shape = shape)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(horizontal = if (compact) 12.dp else 0.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = color,
            fontSize = if (compact) 12.sp else 14.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = if (compact) 0.sp else 1.sp,
        )
    }
}

@Composable
private fun BrutalTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    color: Color = BrewInfo,
    enabled: Boolean = true,
    singleLine: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.heightIn(min = if (singleLine) 48.dp else 56.dp),
        placeholder = { Text(placeholder, color = color.copy(alpha = 0.4f), fontSize = 13.sp) },
        textStyle = TextStyle(color = color, fontSize = 13.sp, fontFamily = FontFamily.Monospace),
        singleLine = singleLine,
        enabled = enabled,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = color.copy(alpha = 0.6f),
            unfocusedBorderColor = color.copy(alpha = 0.2f),
            disabledBorderColor = BrewBorder,
            disabledTextColor = BrewMuted,
            cursorColor = color,
        ),
        shape = RoundedCornerShape(8.dp),
    )
}

// ===== 应用列表项 =====
private fun LazyListScope.appListItems(
    apps: List<BrewApp>,
    expanded: Boolean,
    showToggle: Boolean,
    iconLoader: IconLoader,
    mediaLoader: MediaLoader,
    busy: Boolean,
    progress: Map<String, Int>,
    phoneInstallStates: Map<String, MainActivity.InstallState>,
    glassesInstallStates: Map<String, MainActivity.InstallState>,
    onExpandedChange: (Boolean) -> Unit,
    onOpen: (BrewApp) -> Unit,
    onInstall: (BrewApp, String) -> Unit,
    onCancelDownload: (String) -> Unit,
    topPadding: Int,
) {
    val displayCount = if (expanded) apps.size else minOf(apps.size, COLLAPSED_APP_COUNT)
    
    itemsIndexed(apps.take(displayCount), key = { _, app -> app.id }) { _, app ->
        AppListItem(
            app = app,
            iconLoader = iconLoader,
            mediaLoader = mediaLoader,
            busy = busy,
            progress = progress,
            phoneInstallState = phoneInstallStates[app.artifactFor("phone")?.packageName.orEmpty()],
            glassesInstallState = glassesInstallStates[app.artifactFor("glasses")?.packageName.orEmpty()],
            onOpen = { onOpen(app) },
            onInstall = { target -> onInstall(app, target) },
            onCancelDownload = onCancelDownload,
        )
    }
    
    if (showToggle && apps.size > COLLAPSED_APP_COUNT) {
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(BrewPanel)
                    .border(width = 1.dp, color = BrewBorder, shape = RoundedCornerShape(12.dp))
                    .clickable { onExpandedChange(!expanded) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (expanded) "SHOW LESS" else "SHOW ALL (${apps.size})",
                    color = BrewGreen,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp,
                )
            }
        }
    }
}

@Composable
private fun AppListItem(
    app: BrewApp,
    iconLoader: IconLoader,
    mediaLoader: MediaLoader,
    busy: Boolean,
    progress: Map<String, Int>,
    phoneInstallState: MainActivity.InstallState?,
    glassesInstallState: MainActivity.InstallState?,
    onOpen: () -> Unit,
    onInstall: (String) -> Unit,
    onCancelDownload: (String) -> Unit,
) {
    val flashAlpha = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    // 检测安装完成，触发绿色闪动
    LaunchedEffect(phoneInstallState, glassesInstallState) {
        val justInstalled = phoneInstallState == MainActivity.InstallState.INSTALLED ||
            glassesInstallState == MainActivity.InstallState.INSTALLED
        if (justInstalled) {
            flashAlpha.snapTo(0.25f)
            flashAlpha.animateTo(0f, animationSpec = tween(600))
        }
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(BrewPanel)
            .border(width = 1.dp, color = BrewBorder, shape = RoundedCornerShape(12.dp))
            .clickable { onOpen() },
    ) {
        // 安装完成闪动层
        if (flashAlpha.value > 0.01f) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(BrewSuccess.copy(alpha = flashAlpha.value)),
            )
        }
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            AppIcon(
                app = app,
                iconLoader = iconLoader,
                mediaLoader = mediaLoader,
                modifier = Modifier.size(64.dp),
            )
            
            Spacer(modifier = Modifier.width(12.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(text = app.name, color = BrewTextBright, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = app.description, color = BrewMuted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(modifier = Modifier.height(8.dp))
                
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (app.artifactFor("phone") != null) {
                        val phoneKey = "${app.id}:phone"
                        val phoneProgress = progress[phoneKey]
                        
                        Box(
                            modifier = Modifier
                                .height(32.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(BrewPanelAlt)
                                .border(width = 1.dp, color = BrewBorder, shape = RoundedCornerShape(12.dp))
                                .padding(horizontal = 12.dp)
                                .clickable { if (!busy) onInstall("phone") },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (phoneProgress != null) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    LinearProgressIndicator(progress = { phoneProgress / 100f }, modifier = Modifier.weight(1f).height(4.dp), color = BrewCoral, trackColor = BrewBorder)
                                    Spacer(Modifier.width(6.dp))
                                    Text(text = "✕", color = BrewCoral, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { onCancelDownload("${app.id}:phone") }.padding(4.dp))
                                }
                            } else {
                                Text(
                                    text = when (phoneInstallState) {
                                        MainActivity.InstallState.INSTALLED -> "INSTALLED"
                                        MainActivity.InstallState.UPDATE_AVAILABLE -> "UPDATE"
                                        else -> "PHONE"
                                    },
                                    color = when (phoneInstallState) {
                                        MainActivity.InstallState.INSTALLED -> BrewGreen
                                        MainActivity.InstallState.UPDATE_AVAILABLE -> BrewWarning
                                        else -> BrewText
                                    },
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
                    
                    if (app.artifactFor("glasses") != null) {
                        val glassesKey = "${app.id}:glasses"
                        val glassesProgress = progress[glassesKey]
                        
                        Box(
                            modifier = Modifier
                                .height(32.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(BrewPanelAlt)
                                .border(width = 1.dp, color = BrewBorder, shape = RoundedCornerShape(12.dp))
                                .padding(horizontal = 12.dp)
                                .clickable { if (!busy) onInstall("glasses") },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (glassesProgress != null) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    LinearProgressIndicator(progress = { glassesProgress / 100f }, modifier = Modifier.weight(1f).height(4.dp), color = BrewCoral, trackColor = BrewBorder)
                                    Spacer(Modifier.width(6.dp))
                                    Text(text = "✕", color = BrewCoral, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.clickable { onCancelDownload("${app.id}:glasses") }.padding(4.dp))
                                }
                            } else {
                                Text(
                                    text = when (glassesInstallState) {
                                        MainActivity.InstallState.INSTALLED -> "INSTALLED"
                                        MainActivity.InstallState.UPDATE_AVAILABLE -> "UPDATE"
                                        else -> "GLASSES"
                                    },
                                    color = when (glassesInstallState) {
                                        MainActivity.InstallState.INSTALLED -> BrewGreen
                                        MainActivity.InstallState.UPDATE_AVAILABLE -> BrewWarning
                                        else -> BrewText
                                    },
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ===== 主界面（含底部导航） =====
@Composable
private fun MainInterface(
    state: StoreUiState,
    actions: StoreActions,
    iconLoader: IconLoader,
    mediaLoader: MediaLoader,
    currentPage: NavPage,
    onPageChange: (NavPage) -> Unit,
    selectedApp: BrewApp?,
    onSelectedAppChange: (BrewApp?) -> Unit,
    query: String,
    onQueryChange: (String) -> Unit,
    categoryFilter: String?,
    onCategoryFilterChange: (String?) -> Unit,
    showingFeaturedList: Boolean,
    onShowingFeaturedListChange: (Boolean) -> Unit,
    appListExpanded: Boolean,
    onAppListExpandedChange: (Boolean) -> Unit,
    updateSheetVisible: Boolean,
    onUpdateSheetVisibleChange: (Boolean) -> Unit,
    app: LabApplication,
) {
    val ctx = LocalContext.current
    val lists = remember(state.apps, query, categoryFilter) {
        val visibleApps = state.apps.filter { a ->
            val matchesQuery = query.isBlank() ||
                a.name.lowercase(Locale.getDefault()).contains(query.lowercase(Locale.getDefault())) ||
                a.description.lowercase(Locale.getDefault()).contains(query.lowercase(Locale.getDefault()))
            val matchesCategory = categoryFilter.isNullOrBlank() ||
                a.category.equals(categoryFilter, ignoreCase = true)
            matchesQuery && matchesCategory
        }
        val categories = state.apps.map { it.category }.distinct().sorted()
        StoreHomeLists(
            featuredApps = state.apps.filter { it.featured }.sortedByDescending { it.featuredRank ?: 0 },
            visibleApps = visibleApps,
            categories = categories,
        )
    }
    
    val listState = rememberLazyListState()
    
    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = 64.dp),
        ) {
            AnimatedContent(
                targetState = currentPage,
                transitionSpec = {
                    (fadeIn(animationSpec = tween(200)) + slideInHorizontally(animationSpec = tween(200)) { it / 4 }) togetherWith
                    (fadeOut(animationSpec = tween(200)) + slideOutHorizontally(animationSpec = tween(200)) { -it / 4 })
                },
                label = "page-transition",
            ) { page ->
                when (page) {
                    NavPage.STORE -> StoreModule(
                    listState = listState,
                    state = state,
                    actions = actions,
                    query = query,
                    categoryFilter = categoryFilter,
                    showingFeaturedList = showingFeaturedList,
                    appListExpanded = appListExpanded,
                    lists = lists,
                    iconLoader = iconLoader,
                    mediaLoader = mediaLoader,
                    onQueryChange = onQueryChange,
                    onCategoryFilter = { onCategoryFilterChange(it); onAppListExpandedChange(false) },
                    onShowFeaturedList = { onShowingFeaturedListChange(true) },
                    onHideFeaturedList = { onShowingFeaturedListChange(false) },
                    onAppListExpandedChange = onAppListExpandedChange,
                    onSelectApp = onSelectedAppChange,
                    onUpdateOpen = { onUpdateSheetVisibleChange(true) },
                )
                NavPage.SCREEN_MIRROR -> ScreenMirrorModule(
                    state = state,
                    actions = actions,
                    app = app,
                )
                NavPage.PHONE_MIRROR -> PhoneMirrorModule(
                    state = state,
                    actions = actions,
                    app = app,
                )
                NavPage.FILE_MANAGER -> FileManagerModule(
                    state = state,
                    actions = actions,
                    app = app,
                )
                NavPage.ADB_TOOLS -> AdbToolsModule(
                    state = state,
                    actions = actions,
                    app = app,
                )
                NavPage.HID_GAMEPAD -> com.rokidlab.phone.hid.HidGamepadModule(
                    hidManager = (app as com.rokidlab.phone.app.LabApplication).hidManager,
                )
                NavPage.SETTINGS -> SettingsModule(
                    state = state,
                    actions = actions,
                )
                }
            }
        }
        
        BottomNavigationBar(
            currentPage = currentPage,
            onNavigate = onPageChange,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
        
        AnimatedVisibility(
            visible = selectedApp != null,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            DetailSheet(
                app = selectedApp,
                busy = state.busy,
                progress = state.downloadProgress,
                iconLoader = iconLoader,
                mediaLoader = mediaLoader,
                phoneInstallStates = state.phoneInstallStates,
                glassesInstallStates = state.glassesInstallStates,
                onDismiss = { onSelectedAppChange(null) },
                onInstall = actions.onInstall,
                onUninstall = actions.onUninstall,
                onLaunch = actions.onLaunch,
            )
        }
        
        AnimatedVisibility(
            visible = updateSheetVisible,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            UpdateSheet(
                state = state.selfUpdateState,
                onDismiss = { onUpdateSheetVisibleChange(false) },
                onUpdate = actions.onSelfUpdate,
                onCancelDownload = { actions.onCancelDownload("brew-self-update") },
            )
        }
    }
}

private data class StoreHomeLists(
    val featuredApps: List<BrewApp>,
    val visibleApps: List<BrewApp>,
    val categories: List<String>,
)

// ===== 底部导航栏 =====
@Composable
private fun BottomNavigationBar(
    currentPage: NavPage,
    onNavigate: (NavPage) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(BrewPanel)
            .border(width = 1.dp, color = BrewBorder, shape = RoundedCornerShape(12.dp)),
    ) {
        val scrollState = rememberScrollState()
        Row(
            modifier = Modifier
                .fillMaxHeight()
                .horizontalScroll(scrollState),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val navItems = listOf(
                NavPage.STORE to ctx.getString(R.string.nav_store),
                NavPage.SCREEN_MIRROR to ctx.getString(R.string.nav_screen_mirror),
                NavPage.PHONE_MIRROR to ctx.getString(R.string.nav_phone_mirror),
                NavPage.FILE_MANAGER to ctx.getString(R.string.nav_file_manager),
                NavPage.ADB_TOOLS to ctx.getString(R.string.nav_adb_tools),
                NavPage.HID_GAMEPAD to ctx.getString(R.string.nav_hid_gamepad),
                NavPage.SETTINGS to ctx.getString(R.string.nav_settings),
            )
            
            navItems.forEach { (page, label) ->
                val isSelected = currentPage == page
                val color = when (page) {
                    NavPage.STORE -> BrewGreen
                    NavPage.SCREEN_MIRROR -> BrewCyan
                    NavPage.PHONE_MIRROR -> BrewPurple
                    NavPage.FILE_MANAGER -> BrewAmber
                    NavPage.ADB_TOOLS -> BrewInfo
                    NavPage.HID_GAMEPAD -> BrewSuccess
                    NavPage.SETTINGS -> BrewMagenta
                }
                
                Box(
                    modifier = Modifier
                        .width(80.dp)
                        .height(64.dp)
                        .background(if (isSelected) color else BrewPanel)
                        .clickable { onNavigate(page) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label,
                        color = if (isSelected) {
                            if (color == BrewAmber) BrewBg else BrewTextBright
                        } else BrewMuted,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                    )
                }
            }
        }
    }
}

// ===== StoreModule - 应用商店列表页 =====
@Composable
private fun StoreModule(
    listState: LazyListState,
    state: StoreUiState,
    actions: StoreActions,
    query: String,
    categoryFilter: String?,
    showingFeaturedList: Boolean,
    appListExpanded: Boolean,
    lists: StoreHomeLists,
    iconLoader: IconLoader,
    mediaLoader: MediaLoader,
    onQueryChange: (String) -> Unit,
    onCategoryFilter: (String?) -> Unit,
    onShowFeaturedList: () -> Unit,
    onHideFeaturedList: () -> Unit,
    onAppListExpandedChange: (Boolean) -> Unit,
    onSelectApp: (BrewApp) -> Unit,
    onUpdateOpen: () -> Unit,
) {
    val ctx = LocalContext.current
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
    ) {
        if (showingFeaturedList) {
            item(key = "search-header") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = ctx.getString(R.string.featured_apps),
                            color = BrewGreen,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 2.sp,
                        )
                        Spacer(Modifier.weight(1f))
                        Box(
                            modifier = Modifier
                                .width(40.dp)
                                .height(40.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(BrewPanel)
                                .border(width = 1.dp, color = BrewBorder, shape = RoundedCornerShape(12.dp))
                                .clickable { onHideFeaturedList() },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(text = "X", color = BrewText, fontSize = 18.sp)
                        }
                    }
                }
            appListItems(
                apps = lists.featuredApps,
                expanded = true,
                showToggle = false,
                iconLoader = iconLoader,
                mediaLoader = mediaLoader,
                busy = state.busy,
                progress = state.downloadProgress,
                phoneInstallStates = state.phoneInstallStates,
                glassesInstallStates = state.glassesInstallStates,
                onExpandedChange = {},
                onOpen = onSelectApp,
                onInstall = actions.onInstall,
                onCancelDownload = actions.onCancelDownload,
                topPadding = 0,
            )
        } else {
            item(key = "header") {
                Column(modifier = Modifier.padding(bottom = 24.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Rokid",
                            color = BrewGreen,
                            fontSize = 36.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 4.sp,
                        )
                        Text(
                            text = " Lab",
                            color = BrewCyan,
                            fontSize = 36.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 4.sp,
                        )
                    }
                    Text(
                        text = "by DLOVER",
                        color = BrewMuted,
                        fontSize = 12.sp,
                    )
                }
            }
            
            item(key = "search") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp)
                        .background(BrewPanel, RoundedCornerShape(12.dp))
                        .border(width = 1.dp, color = BrewBorder, shape = RoundedCornerShape(12.dp))
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Search, null, tint = BrewMuted, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    BasicTextField(
                        value = query,
                        onValueChange = onQueryChange,
                        singleLine = true,
                        textStyle = TextStyle(color = BrewTextBright, fontSize = 14.sp),
                        modifier = Modifier.weight(1f),
                        decorationBox = { inner ->
                            if (query.isBlank()) {
                                Text(ctx.getString(R.string.search_apps_placeholder), color = BrewDim, fontSize = 14.sp)
                            }
                            inner()
                        },
                    )
                    if (query.isNotBlank()) {
                        Text(
                            "×",
                            color = BrewMuted,
                            fontSize = 18.sp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onQueryChange("") }
                                .padding(8.dp),
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
            
            if (lists.featuredApps.isNotEmpty()) {
                item(key = "featured-section-header") {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = ctx.getString(R.string.featured),
                            color = BrewTextBright,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 2.sp,
                        )
                        Box(
                            modifier = Modifier
                                .height(32.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(BrewPanel)
                                .border(width = 1.dp, color = BrewBorder, shape = RoundedCornerShape(12.dp))
                                .padding(horizontal = 16.dp)
                                .clickable { onShowFeaturedList() },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = ctx.getString(R.string.view_all),
                                color = BrewText,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
                
                item(key = "featured-grid") {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        lists.featuredApps.take(2).forEach { app ->
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .aspectRatio(1f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(BrewPanel)
                                    .border(width = 1.dp, color = BrewBorder, shape = RoundedCornerShape(12.dp))
                                    .clickable { onSelectApp(app) },
                            ) {
                                FeaturedAppItem(app = app, iconLoader = iconLoader, mediaLoader = mediaLoader)
                            }
                        }
                    }
                }
            }
            
            if (lists.categories.isNotEmpty()) {
                item(key = "categories-header") {
                    Text(
                        text = ctx.getString(R.string.category),
                        color = BrewMuted,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp,
                        modifier = Modifier.padding(top = 24.dp, bottom = 12.dp),
                    )
                }
                
                item(key = "categories") {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                    ) {
                        Box(
                            modifier = Modifier
                                .height(36.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (categoryFilter == null) BrewGreen else BrewPanel)
                                .border(width = 2.dp, color = if (categoryFilter == null) BrewGreen else BrewBorder, shape = RoundedCornerShape(12.dp))
                                .padding(horizontal = 16.dp)
                                .clickable { onCategoryFilter(null) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = ctx.getString(R.string.all),
                                color = if (categoryFilter == null) BrewBg else BrewText,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                        lists.categories.forEach { category ->
                            val isSelected = categoryFilter == category
                            Box(
                                modifier = Modifier
                                    .height(36.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (isSelected) BrewGreen else BrewPanel)
                                    .border(width = 2.dp, color = if (isSelected) BrewGreen else BrewBorder, shape = RoundedCornerShape(12.dp))
                                    .padding(horizontal = 16.dp)
                                    .clickable { onCategoryFilter(category) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = category.uppercase(),
                                    color = if (isSelected) BrewBg else BrewText,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
                }
            }
            
            appListItems(
                apps = lists.visibleApps,
                expanded = appListExpanded,
                showToggle = lists.visibleApps.size > COLLAPSED_APP_COUNT,
                iconLoader = iconLoader,
                mediaLoader = mediaLoader,
                busy = state.busy,
                progress = state.downloadProgress,
                phoneInstallStates = state.phoneInstallStates,
                glassesInstallStates = state.glassesInstallStates,
                onExpandedChange = onAppListExpandedChange,
                onOpen = onSelectApp,
                onInstall = actions.onInstall,
                onCancelDownload = actions.onCancelDownload,
                topPadding = 24,
            )
        }
    }
}
