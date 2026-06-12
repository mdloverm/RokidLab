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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.launch
import java.util.Locale
// AppIcon is in com.rokidlab.phone.store — accessible via wildcard import

private const val COLLAPSED_APP_COUNT = 5

// ===== 导航页面枚举 =====
enum class NavPage {
    STORE,
    SCREEN_MIRROR,
    PHONE_MIRROR,
    FILE_MANAGER,
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
    val onScreenMirrorInstallScreenStream: () -> Unit,
    val onScreenMirrorOpenScreenStream: () -> Unit,
    val onScreenMirrorRetry: () -> Unit,
    val onScreenMirrorBack: () -> Unit,
    // 手机投屏
    val onPhoneMirrorIpChange: (String) -> Unit,
    val onPhoneMirrorPortChange: (String) -> Unit,
    val onPhoneMirrorConnect: () -> Unit,
    val onPhoneMirrorStart: () -> Unit,
    val onPhoneMirrorStop: () -> Unit,
    val onPhoneMirrorInstallScreenStream: () -> Unit,
    val onPhoneMirrorOpenScreenStream: () -> Unit,
    val onPhoneMirrorRetry: () -> Unit,
    val onPhoneMirrorBack: () -> Unit,
    // 文件管理
    val onFileManagerIpChange: (String) -> Unit,
    val onFileManagerConnect: () -> Unit,
    val onFileManagerDisconnect: () -> Unit,
    val onFileManagerStop: () -> Unit,
    val onFileManagerInstallScreenStream: () -> Unit,
    val onFileManagerOpenScreenStream: () -> Unit,
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
    val onSettingsReinstallScreenStream: () -> Unit,
)

// ===== 应用入口 =====
@Composable
internal fun BrewPhoneApp(
    state: StoreUiState,
    actions: StoreActions,
    iconLoader: IconLoader,
    mediaLoader: MediaLoader,
    app: BrewApplication,
) {
    var currentPage by rememberSaveable { mutableStateOf(NavPage.STORE) }
    var selectedApp by remember { mutableStateOf<BrewApp?>(null) }
    var query by remember { mutableStateOf("") }
    var categoryFilter by remember { mutableStateOf<String?>(null) }
    var showingFeaturedList by remember { mutableStateOf(false) }
    var appListExpanded by remember { mutableStateOf(false) }
    var updateSheetVisible by remember { mutableStateOf(false) }
    
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
                        text = "退出应用",
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
                        text = "确定要退出吗？",
                        color = BrewText,
                        fontSize = 14.sp,
                        letterSpacing = 1.sp,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "退出后所有投屏连接将断开。",
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
                        .background(BrewBg)
                        .border(width = 1.dp, color = BrewRed, shape = RoundedCornerShape(12.dp))
                        .clickable {
                            showExitDialog = false
                            actions.onExitApp()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = "退出", color = BrewRed, fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                }
            },
            dismissButton = {
                Box(
                    modifier = Modifier
                        .height(44.dp)
                        .width(120.dp)
                        .background(BrewBg)
                        .border(width = 1.dp, color = BrewBorder, shape = RoundedCornerShape(12.dp))
                        .clickable { showExitDialog = false },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = "取消", color = BrewText, fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
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
    app: BrewApplication,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        ModuleHeader(title = "屏幕镜像", subtitle = "眼镜屏幕实时同步到手机", color = BrewCyan)
        Spacer(modifier = Modifier.height(24.dp))
        
        IpAddressInputCard(
            label = "眼镜 IP 地址",
            value = app.screenMirrorIp,
            onValueChange = actions.onScreenMirrorIpChange,
            color = BrewCyan,
        )
        Spacer(modifier = Modifier.height(16.dp))
        
        ScreenStreamStatusCard(
            installed = state.screenMirrorState.screenStreamInstalled == true,
            installing = state.screenMirrorState.isInstallingScreenStream,
            running = state.screenMirrorState.screenStreamRunning,
            onInstall = actions.onScreenMirrorInstallScreenStream,
            onOpen = actions.onScreenMirrorOpenScreenStream,
            onStop = actions.onScreenMirrorStop,
        )
        Spacer(modifier = Modifier.height(16.dp))
        
        BrutalButton(
            label = "▶ 开始镜像",
            color = BrewCyan,
            onClick = actions.onScreenMirrorStart,
        )
        Spacer(modifier = Modifier.height(24.dp))
        
        UsageInstructionsCard(
            color = BrewCyan,
            instructions = listOf(
                "1. 确保眼镜已连接 WiFi 并开启 ADB 网络调试（端口 5555）",
                "2. 在眼镜上安装并启动 ScreenStream 应用",
                "3. 输入眼镜的 IP 地址（默认 192.168.1.168）",
                "4. 点击「开始镜像」按钮",
                "5. 眼镜屏幕将实时显示在手机上",
            )
        )
    }
}

// ===== 手机投屏模块 =====
@Composable
private fun PhoneMirrorModule(
    state: StoreUiState,
    actions: StoreActions,
    app: BrewApplication,
) {
    val isMirroring = state.phoneMirrorState.isMirroring
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        ModuleHeader(title = "手机投屏", subtitle = "手机屏幕投射到眼镜", color = BrewPurple)
        Spacer(modifier = Modifier.height(24.dp))
        
        if (isMirroring) {
            // 投屏中状态
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "投屏中",
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
                    label = "■ 停止投屏",
                    color = BrewRed,
                    onClick = actions.onPhoneMirrorStart,
                )
            }
        } else {
            // 配置状态
            IpAddressInputCard(
                label = "眼镜 IP 地址",
                value = app.phoneMirrorIp,
                onValueChange = actions.onPhoneMirrorIpChange,
                color = BrewPurple,
            )
            Spacer(modifier = Modifier.height(16.dp))
            
            ScreenStreamStatusCard(
                installed = state.phoneMirrorState.screenStreamInstalled == true,
                installing = state.phoneMirrorState.isInstallingScreenStream,
                running = state.phoneMirrorState.screenStreamRunning,
                onInstall = actions.onPhoneMirrorInstallScreenStream,
                onOpen = actions.onPhoneMirrorOpenScreenStream,
                onStop = actions.onPhoneMirrorStop,
            )
            Spacer(modifier = Modifier.height(16.dp))
            
            BrutalButton(
                label = "▶ 开始投屏",
                color = BrewPurple,
                onClick = actions.onPhoneMirrorStart,
            )
            Spacer(modifier = Modifier.height(24.dp))
            
            UsageInstructionsCard(
                color = BrewPurple,
                instructions = listOf(
                    "1. 确保眼镜已连接 WiFi 并开启 ADB 网络调试",
                    "2. 输入眼镜的 IP 地址（默认 192.168.1.168）",
                    "3. 点击「开始投屏」，眼镜端将自动启动接收",
                    "4. 手机屏幕将实时显示在眼镜上",
                )
            )
        }
    }
}

// ===== 文件管理模块 =====
@Composable
private fun FileManagerModule(
    state: StoreUiState,
    actions: StoreActions,
    app: BrewApplication,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        ModuleHeader(title = "文件管理", subtitle = "管理眼镜中的文件", color = BrewAmber)
        Spacer(modifier = Modifier.height(24.dp))
        
        IpAddressInputCard(
            label = "眼镜 IP 地址",
            value = app.fileManagerIp,
            onValueChange = actions.onFileManagerIpChange,
            color = BrewAmber,
        )
        Spacer(modifier = Modifier.height(16.dp))
        
        ScreenStreamStatusCard(
            installed = state.fileManagerState.screenStreamInstalled == true,
            installing = state.fileManagerState.isInstallingScreenStream,
            running = state.fileManagerState.screenStreamRunning,
            onInstall = actions.onFileManagerInstallScreenStream,
            onOpen = actions.onFileManagerOpenScreenStream,
            onStop = actions.onFileManagerStop,
        )
        Spacer(modifier = Modifier.height(16.dp))
        
        BrutalButton(
            label = "▶ 打开文件管理器",
            color = BrewAmber,
            onClick = actions.onFileManagerConnect,
        )
        Spacer(modifier = Modifier.height(12.dp))
        
        BrutalButton(
            label = "安装本地 APK",
            color = BrewInfo,
            onClick = actions.onInstallApk,
        )
        Spacer(modifier = Modifier.height(24.dp))
        
        UsageInstructionsCard(
            color = BrewAmber,
            instructions = listOf(
                "1. 确保眼镜已连接 WiFi 并开启 ADB 网络调试（端口 5555）",
                "2. 在眼镜上安装并启动 ScreenStream 应用",
                "3. 输入眼镜的 IP 地址（默认 192.168.1.168）",
                "4. 点击「打开文件管理器」按钮",
                "5. 即可浏览和管理眼镜中的文件",
            )
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
                value = value,
                onValueChange = onValueChange,
                placeholder = { Text("192.168.1.168", color = BrewMuted) },
                textStyle = TextStyle(color = BrewTextBright),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
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
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(BrewPanel, RoundedCornerShape(12.dp))
            .border(width = 1.dp, color = BrewBorder, shape = RoundedCornerShape(12.dp))
            .padding(16.dp),
    ) {
        Column {
            Text(text = "使用说明", color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, modifier = Modifier.padding(bottom = 12.dp))
            instructions.forEach { instruction ->
                Text(text = instruction, color = BrewMuted, fontSize = 12.sp, lineHeight = 20.sp, modifier = Modifier.padding(bottom = 4.dp))
            }
        }
    }
}

// ===== Neo Brutalist 组件 =====

@Composable
private fun ScreenStreamStatusCard(installed: Boolean, installing: Boolean, running: Boolean = false, onInstall: () -> Unit, onOpen: () -> Unit, onStop: (() -> Unit)? = null) {
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
        running -> "运行中"
        installing -> "安装中"
        installed -> "已安装"
        else -> "未安装"
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
                        text = "SCREENSTREAM $statusText",
                        color = BrewBg,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp,
                    )
                    Text(
                        text = "投屏和文件管理功能必需",
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
                        Text(text = "安装中...", color = BrewCyan, fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
                    }
                }
            } else if (running) {
                BrutalButton(
                    label = "● 停止 ScreenStream",
                    color = BrewRed,
                    onClick = onStop ?: {},
                )
            } else if (!installed) {
                BrutalButton(
                    label = "● 安装 ScreenStream",
                    color = BrewAmber,
                    onClick = onInstall,
                )
            } else {
                BrutalButton(
                    label = "▶ 启动 ScreenStream",
                    color = BrewSuccess,
                    onClick = onOpen,
                )
            }
        }
    }
}

@Composable
private fun BrutalButton(label: String, color: Color, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.98f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "btn-press",
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .graphicsLayer { scaleX = pressScale; scaleY = pressScale; alpha = if (isPressed) 0.85f else 1f }
            .clip(RoundedCornerShape(12.dp))
            .background(color.copy(alpha = 0.12f))
            .border(width = 1.dp, color = color.copy(alpha = 0.5f), shape = RoundedCornerShape(12.dp))
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = color,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp,
        )
    }
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
    
    itemsIndexed(apps.take(displayCount)) { _, app ->
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
    app: BrewApplication,
) {
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
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp)
            .background(BrewPanel)
            .border(width = 1.dp, color = BrewBorder, shape = RoundedCornerShape(12.dp)),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val navItems = listOf(
            NavPage.STORE to "应用商店",
            NavPage.SCREEN_MIRROR to "屏幕镜像",
            NavPage.PHONE_MIRROR to "手机投屏",
            NavPage.FILE_MANAGER to "文件管理",
            NavPage.SETTINGS to "设置",
        )
        
        navItems.forEach { (page, label) ->
            val isSelected = currentPage == page
            val color = when (page) {
                NavPage.STORE -> BrewGreen
                NavPage.SCREEN_MIRROR -> BrewCyan
                NavPage.PHONE_MIRROR -> BrewPurple
                NavPage.FILE_MANAGER -> BrewAmber
                NavPage.SETTINGS -> BrewMagenta
            }
            
            Box(
                modifier = Modifier
                    .weight(1f)
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
                            text = "精选应用",
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
                                Text("搜索应用...", color = BrewDim, fontSize = 14.sp)
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
                            text = "精选",
                            color = BrewTextBright,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 2.sp,
                        )
                        Box(
                            modifier = Modifier
                                .height(32.dp)
                                .background(BrewPanel)
                                .border(width = 1.dp, color = BrewBorder, shape = RoundedCornerShape(12.dp))
                                .padding(horizontal = 16.dp)
                                .clickable { onShowFeaturedList() },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "查看全部",
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
                        text = "分类",
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
                                .background(if (categoryFilter == null) BrewGreen else BrewPanel)
                                .border(width = 2.dp, color = if (categoryFilter == null) BrewGreen else BrewBorder, shape = RoundedCornerShape(12.dp))
                                .padding(horizontal = 16.dp)
                                .clickable { onCategoryFilter(null) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "全部",
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
