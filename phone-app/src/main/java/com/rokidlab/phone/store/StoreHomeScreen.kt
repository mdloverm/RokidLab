package com.rokidlab.phone.store

import com.rokidlab.phone.app.*
import com.rokidlab.phone.adb.AdbShellClient
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
import androidx.compose.ui.res.stringResource
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
    // 本地APK安装状态
    val isInstallingLocalApk: Boolean = false,
    val localApkInstallProgress: Int = 0,
    val localApkInstallStatus: String = "",
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
                hostAppInstalled = state.hostAppInstalled,
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
        BrewDialog(
            onDismiss = { showExitDialog = false },
            title = ctx.getString(R.string.exit_app),
            color = BrewRed,
        ) {
            BrewDialogContent {
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
            BrewDialogActions(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                BrewDialogButton(text = ctx.getString(R.string.cancel), onClick = { showExitDialog = false })
                BrewDialogButton(text = ctx.getString(R.string.exit), onClick = {
                    showExitDialog = false
                    actions.onExitApp()
                }, color = BrewRed)
            }
        }
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
            moduleColor = BrewCyan,
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
            label = ctx.getString(R.string.start_mirror),
            color = BrewCyan,
            onClick = actions.onScreenMirrorStart,
        )
        Spacer(modifier = Modifier.height(24.dp))
        
        UsageInstructionsCard(
            color = BrewCyan,
            instructions = listOf(
                "1. ${ctx.getString(R.string.connecting_adb_hint)}",
                "2. ${ctx.getString(R.string.usage_mirror_step2)}（192.168.1.168）",
                "3. ${ctx.getString(R.string.usage_mirror_step3)}",
                "4. ${ctx.getString(R.string.usage_mirror_step4)}",
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
                    color = BrewCoral,
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
                moduleColor = BrewPurple,
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
                label = ctx.getString(R.string.start_cast),
                color = BrewPurple,
                onClick = actions.onPhoneMirrorStart,
            )
            Spacer(modifier = Modifier.height(24.dp))
            
            UsageInstructionsCard(
                color = BrewPurple,
                instructions = listOf(
                    "1. ${ctx.getString(R.string.connecting_adb_hint)}",
                    "2. ${ctx.getString(R.string.ip_address_label)}（192.168.1.168）",
                    "3. ${ctx.getString(R.string.start_cast)}",
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
            moduleColor = BrewAmber,
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
            label = ctx.getString(R.string.open_file_manager),
            color = BrewAmber,
            onClick = actions.onFileManagerConnect,
        )
        Spacer(modifier = Modifier.height(12.dp))
        
        BrutalButton(
            label = if (state.isInstallingLocalApk) {
                when {
                    state.localApkInstallProgress > 0 -> ctx.getString(R.string.installing_with_progress, state.localApkInstallProgress)
                    state.localApkInstallStatus.isNotEmpty() -> state.localApkInstallStatus
                    else -> ctx.getString(R.string.installing_apk)
                }
            } else {
                ctx.getString(R.string.install_local_apk)
            },
            color = if (state.isInstallingLocalApk) BrewCoral else BrewInfo,
            onClick = actions.onInstallApk,
            enabled = !state.isInstallingLocalApk
        )
        Spacer(modifier = Modifier.height(24.dp))
        
        UsageInstructionsCard(
            color = BrewAmber,
            instructions = listOf(
                "1. ${ctx.getString(R.string.connecting_adb_hint)}（5555）",
                "2. ${ctx.getString(R.string.usage_filemanager_step2)}",
                "3. ${ctx.getString(R.string.ip_address_label)}（192.168.1.168）",
                "4. ${ctx.getString(R.string.open_file_manager)}",
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
            .verticalScroll(rememberScrollState()),
    ) {
        com.rokidlab.phone.adb.ui.AdbToolsScreen(
            client = client,
            connected = connected,
            scope = scope,
            getOrConnect = { cb -> getOrConnect(cb) },
            onDisconnect = { disconnectClient() },
            // RokidLink 状态 — 复用 FileManagerModule 的同一套状态
            rokidLinkInstalled = state.fileManagerState.rokidLinkInstalled == true,
            rokidLinkInstalling = state.fileManagerState.isInstallingRokidLink,
            rokidLinkRunning = state.fileManagerState.rokidLinkRunning,
            onInstallRokidLink = actions.onFileManagerInstallRokidLink,
            onOpenRokidLink = actions.onFileManagerOpenRokidLink,
            onStopRokidLink = actions.onFileManagerStop,
        )
    }
    
    // cleanup connection when leaving ADB module
    DisposableEffect(Unit) {
        onDispose { disconnectClient() }
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
internal fun ModuleHeader(title: String, subtitle: String, color: Color) {
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
internal fun IpAddressInputCard(
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
                .background(BrewPanel, BrewShapeStandard)
                .border(width = 1.dp, color = color, shape = BrewShapeStandard)
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
internal fun UsageInstructionsCard(
    color: Color = BrewAmber,
    instructions: List<String>,
    ctx: android.content.Context,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(BrewPanel, BrewShapeStandard)
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
internal fun RokidLinkStatusCard(installed: Boolean, installing: Boolean, running: Boolean = false, onInstall: () -> Unit, onOpen: () -> Unit, onStop: (() -> Unit)? = null, moduleColor: Color = BrewCoral, ctx: android.content.Context) {
    // 状态颜色：已安装/运行中 → 各自导航栏色，未安装/安装中 → 商店色
    val statusColor = when {
        running -> moduleColor
        installing -> BrewCoral
        installed -> moduleColor
        else -> BrewCoral
    }
    val statusBg = statusColor
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
            .clip(BrewShapeStandard)
            .background(BrewPanel)
            .border(width = 1.dp, color = statusColor.copy(alpha = 0.3f), shape = BrewShapeStandard),
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
                        .background(BrewPanelAlt, BrewShapeStandard)
                        .border(width = 1.dp, color = BrewCoral.copy(alpha = 0.3f), shape = BrewShapeStandard),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = "⟳", color = BrewCoral, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 12.dp))
                        Text(text = ctx.getString(R.string.installing), color = BrewCoral, fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
                    }
                }
            } else if (running) {
                // 停止按钮 → 商店色
                BrutalButton(
                    label = ctx.getString(R.string.stop_rokid_link),
                    color = BrewCoral,
                    onClick = onStop ?: {},
                )
            } else if (!installed) {
                // 安装按钮 → 商店色
                BrutalButton(
                    label = ctx.getString(R.string.install_rokid_link),
                    color = BrewCoral,
                    onClick = onInstall,
                )
            } else {
                // 启动按钮 → 各自导航栏色
                BrutalButton(
                    label = ctx.getString(R.string.launch_rokid_link),
                    color = moduleColor,
                    onClick = onOpen,
                )
            }
        }
    }
}

@Composable
internal fun BrutalButton(
    label: String, 
    color: Color, 
    onClick: () -> Unit, 
    compact: Boolean = false,
    enabled: Boolean = true
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.97f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "btn-press",
    )
    val height = if (compact) 32.dp else 52.dp
    val btnShape = if (compact) BrewShapeMedium else BrewShapeStandard
    
    val effectiveColor = if (enabled) color else BrewMuted
    val effectiveAlpha = if (enabled) {
        if (isPressed) 0.20f else 0.12f
    } else {
        0.08f
    }
    val effectiveBorderAlpha = if (enabled) {
        if (isPressed) 0.7f else 0.5f
    } else {
        0.3f
    }
    
    Box(
        modifier = Modifier
            .then(if (compact) Modifier.wrapContentWidth() else Modifier.fillMaxWidth())
            .height(height)
            .graphicsLayer { scaleX = pressScale; scaleY = pressScale }
            .clip(btnShape)
            .background(effectiveColor.copy(alpha = effectiveAlpha))
            .border(width = 1.dp, color = effectiveColor.copy(alpha = effectiveBorderAlpha), shape = btnShape)
            .then(if (enabled) {
                Modifier.clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            } else {
                Modifier
            })
            .padding(horizontal = if (compact) 12.dp else 0.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = effectiveColor,
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
                    color = BrewCoral,
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
                                        MainActivity.InstallState.INSTALLED -> BrewCoral
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
                                        MainActivity.InstallState.INSTALLED -> BrewCoral
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
                NavPage.STORE to stringResource(R.string.nav_store),
                NavPage.SCREEN_MIRROR to stringResource(R.string.nav_screen_mirror),
                NavPage.PHONE_MIRROR to stringResource(R.string.nav_phone_mirror),
                NavPage.FILE_MANAGER to stringResource(R.string.nav_file_manager),
                NavPage.ADB_TOOLS to stringResource(R.string.nav_adb_tools),
                NavPage.HID_GAMEPAD to stringResource(R.string.nav_hid_gamepad),
                NavPage.SETTINGS to stringResource(R.string.nav_settings),
            )
            
            navItems.forEach { (page, label) ->
                val isSelected = currentPage == page
                val color = when (page) {
                    NavPage.STORE -> BrewCoral
                    NavPage.SCREEN_MIRROR -> BrewCyan
                    NavPage.PHONE_MIRROR -> BrewPurple
                    NavPage.FILE_MANAGER -> BrewAmber
                    NavPage.ADB_TOOLS -> BrewTeal
                    NavPage.HID_GAMEPAD -> BrewPink
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
                            color = BrewCoral,
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
                            color = BrewCoral,
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
                        .background(BrewPanel, BrewShapeStandard)
                        .border(width = 1.dp, color = BrewBorder, shape = BrewShapeStandard)
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
                                .clip(BrewShapeStandard)
                                .background(if (categoryFilter == null) BrewCoral else BrewPanel)
                                .border(width = 2.dp, color = if (categoryFilter == null) BrewCoral else BrewBorder, shape = BrewShapeStandard)
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
                                    .clip(BrewShapeStandard)
                                    .background(if (isSelected) BrewCoral else BrewPanel)
                                    .border(width = 2.dp, color = if (isSelected) BrewCoral else BrewBorder, shape = BrewShapeStandard)
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