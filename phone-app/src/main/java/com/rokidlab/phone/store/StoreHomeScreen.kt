package com.rokidlab.phone.store

import com.rokidlab.phone.R
import com.rokidlab.phone.adb.*
import com.rokidlab.phone.app.*
import com.rokidlab.phone.design.*
import com.rokidlab.phone.glasses.*
import com.rokidlab.phone.model.*
import com.rokidlab.phone.network.*
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

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
    // 设置页 — 打开眼镜端状态页（WiFi IP / ADB 状态），仅排障用；
    // 常规流程不再主动弹出（IP 已由 RokidLink 自动上报到手机端）。
    val onSettingsOpenGlassesStatus: () -> Unit = {},
    // 设置页 — 语言切换
    val onSwitchLanguage: (String) -> Unit,
    // 设置页 — 导出日志
    val onExportLog: () -> Unit,
    // 设置页 — 导出兼容性诊断（ROM 指纹 / 蓝牙栈 / 投屏降级档位）
    val onExportCompatDiagnostics: () -> Unit = {},
    // 设置页 — 后台保活开关
    val onToggleKeepAlive: () -> Unit = {},
    // ADB 工具 — 通过 SDK 启动眼镜端应用（替代 ADB shell）
    val onLaunchGlassAppViaSdk: (String, String) -> Unit = { _, _ -> },
    // ADB 工具 — 通过 SDK 自定义指令发送按键配置到眼镜端
    val onSendKeyButtonConfig: (String, String, String, String, (Boolean) -> Unit) -> Unit = { _, _, _, _, _ -> },
    // 引导 — 发送 WiFi 配置到眼镜
    val onSendWifiConfig: ((String, String, (Boolean, String?) -> Unit) -> Unit)? = null,
    // 引导 — 安装 RokidLink 到眼镜
    val onInstallLink: ((onComplete: (Boolean) -> Unit) -> Unit)? = null,
    // 引导 — 跳过当前步骤
    val onSkipGuideStep: (() -> Unit)? = null,
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

    // 进入主界面（非引导）时自动刷新商店列表
    val enteredMain = remember { mutableStateOf(false) }
    LaunchedEffect(showGuide) {
        if (!showGuide && !enteredMain.value) {
            enteredMain.value = true
            actions.onRefresh()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(BrewBg)) {
        if (showGuide) {
            GuideScreen(
                currentStep = state.prerequisites.currentGuideStep,
                selectedHostApp = state.selectedHostApp,
                authorized = state.prerequisites.authorized,
                hostAppInstalled = state.hostAppInstalled,
                rokidLinkInstalled = state.prerequisites.rokidLinkInstalled,
                onSelectHostApp = actions.onHostAppSelected,
                onSelectMirrorSource = actions.onSelectMirrorSource,
                onAuthorize = actions.onAuthorize,
                onInstallLink = actions.onInstallLink,
                onSendWifiConfig = actions.onSendWifiConfig,
                // 权限步的「下一步」与「跳过」是同一个动作：离开本步（逐项拉起已在步内自闭环）
                onPermissionsDone = actions.onSkipGuideStep,
                onSkip = actions.onSkipGuideStep,
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
                    NavPage.CHAT -> ChatModule(app = app)
                    NavPage.LEQI_TOOLS -> LeqiToolsModule(
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
                NavPage.CHAT to stringResource(R.string.nav_chat),
                NavPage.LEQI_TOOLS to stringResource(R.string.nav_leqi_tools),
                NavPage.HID_GAMEPAD to stringResource(R.string.nav_hid_gamepad),
                NavPage.SETTINGS to stringResource(R.string.nav_settings),
            )

            navItems.forEach { (page, label) ->
                val isSelected = currentPage == page
                val color = when (page) {
                    NavPage.STORE -> BrewCoral
                    NavPage.CHAT -> BrewChat
                    NavPage.LEQI_TOOLS -> BrewTeal
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
