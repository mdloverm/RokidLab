package com.rokidlab.phone.glasses

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
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay

/**
 * 引导界面 - 指导用户完成前置条件
 */
@Composable
internal fun GuideScreen(
    currentStep: GuideStep,
    selectedHostApp: RokidHostApp?,
    authorized: Boolean,
    hostAppInstalled: Boolean,
    rokidLinkInstalled: Boolean,
    onSelectHostApp: (RokidHostApp) -> Unit,
    onSelectMirrorSource: () -> Unit,
    onAuthorize: () -> Unit,
    onInstallLink: ((onComplete: (Boolean) -> Unit) -> Unit)? = null,
    onSendWifiConfig: ((String, String, (Boolean, String?) -> Unit) -> Unit)? = null,
    onSkip: (() -> Unit)? = null,
) {
    val ctx = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BrewBg)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = ctx.getString(R.string.guide_welcome),
            color = BrewCoral,
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp,
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "by DLOVER",
            color = BrewMuted,
            fontSize = 14.sp,
        )

        Spacer(modifier = Modifier.height(48.dp))

        GuideProgressIndicator(currentStep = currentStep)

        Spacer(modifier = Modifier.height(48.dp))

        when (currentStep) {
            GuideStep.SELECT_HOST_APP -> SelectHostAppStep(
                selectedHostApp = selectedHostApp,
                onSelectHostApp = onSelectHostApp,
                ctx = ctx,
            )
            GuideStep.SELECT_MIRROR_SOURCE -> SelectMirrorSourceStep(
                onSelectMirrorSource = onSelectMirrorSource,
                ctx = ctx,
            )
            GuideStep.AUTHORIZE -> AuthorizeStep(
                authorized = authorized,
                hostAppInstalled = hostAppInstalled,
                selectedHostApp = selectedHostApp,
                onAuthorize = onAuthorize,
                ctx = ctx,
            )
            GuideStep.INSTALL_LINK -> InstallLinkStep(
                onInstallLink = onInstallLink,
                onSkip = onSkip,
                installed = rokidLinkInstalled,
                ctx = ctx,
            )
            GuideStep.CONFIGURE_WIFI -> ConfigureWifiStep(
                onSendWifiConfig = onSendWifiConfig,
                onSkip = onSkip,
                ctx = ctx,
            )
            GuideStep.READY -> { }
        }

        Spacer(modifier = Modifier.height(32.dp))

        StepInstructions(currentStep = currentStep, ctx = ctx)
    }
}

@Composable
private fun GuideProgressIndicator(currentStep: GuideStep) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        GuideStep.values().forEachIndexed { index, step ->
            val isCompleted = index < currentStep.ordinal
            val isCurrent = index == currentStep.ordinal
            val dotColor = when {
                isCompleted -> BrewSuccess
                isCurrent -> when (step) {
                    GuideStep.SELECT_HOST_APP -> BrewCoral
                    GuideStep.SELECT_MIRROR_SOURCE -> BrewCyan
                    GuideStep.AUTHORIZE -> BrewMagenta
                    GuideStep.INSTALL_LINK -> BrewPurple
                    GuideStep.CONFIGURE_WIFI -> BrewInfo
                    GuideStep.READY -> BrewSuccess
                }
                else -> BrewPanel
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(4.dp)
                    .background(dotColor, RoundedCornerShape(2.dp)),
            )
        }
    }
}

@Composable
private fun SelectHostAppStep(
    selectedHostApp: RokidHostApp?,
    onSelectHostApp: (RokidHostApp) -> Unit,
    ctx: android.content.Context,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = ctx.getString(R.string.guide_step1_title_local),
            color = BrewText,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = ctx.getString(R.string.guide_step1_desc),
            color = BrewMuted,
            fontSize = 14.sp,
        )
        
        Spacer(modifier = Modifier.height(32.dp))
        
        RokidHostApp.values().forEach { hostApp ->
            val isSelected = selectedHostApp == hostApp
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        color = if (isSelected) BrewCoral else BrewPanel,
                        shape = RoundedCornerShape(12.dp),
                    )
                    .border(
                        width = 1.dp,
                        color = if (isSelected) BrewCoral else BrewBorder,
                        shape = RoundedCornerShape(12.dp),
                    )
                    .clickable { onSelectHostApp(hostApp) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = hostApp.displayName,
                    color = if (isSelected) BrewBg else BrewText,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
        }
    }
}

@Composable
private fun SelectMirrorSourceStep(
    onSelectMirrorSource: () -> Unit,
    ctx: android.content.Context,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = ctx.getString(R.string.guide_step2_title_local),
            color = BrewText,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = ctx.getString(R.string.guide_step2_desc),
            color = BrewMuted,
            fontSize = 14.sp,
        )
        
        Spacer(modifier = Modifier.height(32.dp))
        
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .clip(BrewShapeStandard)
                .background(BrewCoral, shape = BrewShapeStandard)
                .clickable { onSelectMirrorSource() },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = ctx.getString(R.string.select_mirror_source),
                color = BrewBg,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun AuthorizeStep(
    authorized: Boolean,
    hostAppInstalled: Boolean,
    selectedHostApp: RokidHostApp?,
    onAuthorize: () -> Unit,
    ctx: android.content.Context,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = ctx.getString(R.string.guide_step3_title_local),
            color = BrewText,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = ctx.getString(R.string.guide_step3_desc),
            color = BrewMuted,
            fontSize = 14.sp,
        )
        
        Spacer(modifier = Modifier.height(32.dp))
        
        if (!hostAppInstalled && selectedHostApp != null) {
            // Host 应用未安装 — 显示警告引导用户先安装
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .background(BrewWarning.copy(alpha = 0.2f), shape = RoundedCornerShape(12.dp))
                    .border(1.dp, BrewWarning, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = ctx.getString(R.string.host_app_not_installed_warning, selectedHostApp.displayName),
                    color = BrewWarning,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        } else if (authorized) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .background(BrewSuccess, shape = RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = ctx.getString(R.string.authorized) + " ✓",
                    color = BrewBg,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .clip(BrewShapeStandard)
                    .background(BrewCoral, shape = BrewShapeStandard)
                    .clickable { onAuthorize() },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = ctx.getString(R.string.authorize_btn),
                    color = BrewBg,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            text = ctx.getString(R.string.guide_step3_desc),
            color = BrewMuted,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun InstallLinkStep(
    onInstallLink: ((onComplete: (Boolean) -> Unit) -> Unit)?,
    onSkip: (() -> Unit)?,
    installed: Boolean,
    ctx: android.content.Context,
) {
    var isInstalling by remember { mutableStateOf(false) }
    var installFailed by remember { mutableStateOf(false) }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = ctx.getString(R.string.guide_install_link_title),
            color = BrewText,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = ctx.getString(R.string.guide_install_link_desc),
            color = BrewMuted,
            fontSize = 14.sp,
        )

        Spacer(modifier = Modifier.height(24.dp))

        when {
            installFailed -> {
                Box(
                    modifier = Modifier.fillMaxWidth().height(56.dp)
                        .background(BrewRed, shape = RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        ctx.getString(R.string.guide_install_link_fail),
                        color = BrewBg, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    ctx.getString(R.string.guide_install_link_fail_hint),
                    color = BrewRed, fontSize = 13.sp,
                )
                Spacer(modifier = Modifier.height(16.dp))
                // 重试按钮
                Box(
                    modifier = Modifier.fillMaxWidth().height(56.dp)
                        .clip(BrewShapeStandard)
                        .background(BrewPurple, BrewShapeStandard)
                        .then(
                            if (onInstallLink != null) {
                                Modifier.clickable {
                                    installFailed = false
                                    isInstalling = true
                                    onInstallLink { success ->
                                        isInstalling = false
                                        if (!success) {
                                            installFailed = true
                                        }
                                    }
                                }
                            } else Modifier
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        ctx.getString(R.string.guide_retry_btn),
                        color = BrewBg, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                // 跳过按钮
                Box(
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                        .clip(BrewShapeStandard)
                        .background(BrewPanel, BrewShapeStandard)
                        .then(
                            if (onSkip != null) {
                                Modifier.clickable { onSkip() }
                            } else Modifier
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        ctx.getString(R.string.guide_skip_btn),
                        color = BrewText, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                    )
                }
            }
            else -> {
                if (installed) {
                    Box(
                        modifier = Modifier.fillMaxWidth().height(56.dp)
                            .background(BrewSuccess, shape = RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            ctx.getString(R.string.guide_install_link_success) + " ✓",
                            color = BrewBg, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                } else {
                    Text(
                        ctx.getString(R.string.guide_install_link_hint),
                        color = BrewWarning, fontSize = 13.sp,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                }

                Box(
                    modifier = Modifier.fillMaxWidth().height(56.dp)
                        .clip(BrewShapeStandard)
                        .background(if (isInstalling) BrewPanel else BrewPurple, BrewShapeStandard)
                        .then(
                            if (!isInstalling && onInstallLink != null) {
                                Modifier.clickable {
                                    isInstalling = true
                                    installFailed = false
                                    onInstallLink { success ->
                                        isInstalling = false
                                        if (!success) {
                                            installFailed = true
                                        }
                                    }
                                }
                            } else Modifier
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        if (isInstalling) ctx.getString(R.string.guide_install_link_progress)
                        else if (installed) ctx.getString(R.string.guide_reinstall_link_btn)
                        else ctx.getString(R.string.guide_install_link_btn),
                        color = if (isInstalling) BrewMuted else BrewBg,
                        fontSize = 16.sp, fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                // 跳过按钮
                Box(
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                        .clip(BrewShapeStandard)
                        .background(BrewPanel, BrewShapeStandard)
                        .then(
                            if (onSkip != null) {
                                Modifier.clickable { onSkip() }
                            } else Modifier
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        ctx.getString(R.string.guide_skip_btn),
                        color = BrewText, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}

/**
 * WiFi 扫描超时兜底：超过该时长仍未收到扫描结果广播，就强制退出「正在扫描」态。
 * 手机「定位服务」总开关关闭、或扫描被系统节流时，系统不会发结果广播。
 */
private const val WIFI_SCAN_TIMEOUT_MS = 8_000L

@Suppress("DEPRECATION")
@Composable
private fun ConfigureWifiStep(
    onSendWifiConfig: ((String, String, (Boolean, String?) -> Unit) -> Unit)?,
    onSkip: (() -> Unit)?,
    ctx: android.content.Context,
) {
    val wifiManager = remember {
        ctx.applicationContext.getSystemService(android.content.Context.WIFI_SERVICE) as? WifiManager
    }

    // 权限状态
    var hasLocationPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.ACCESS_FINE_LOCATION)
                == android.content.pm.PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasLocationPermission = granted }

    // WiFi 扫描状态
    var selectedSsid by remember { mutableStateOf<String?>(null) }
    var manualSsid by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var configured by remember { mutableStateOf(false) }

    // 已连接过的 WiFi 密码按 SSID 加密持久化（Keystore AES-256-GCM，复用 SecretStore），
    // 下次选中同一网络自动回填，不用每次重输。
    val wifiPrefs = remember {
        ctx.applicationContext.getSharedPreferences("guide_wifi_prefs", android.content.Context.MODE_PRIVATE)
    }
    fun savedWifiPassword(ssid: String): String? =
        ssid.takeIf { it.isNotBlank() }?.let { SecretStore.get(wifiPrefs, "wifi_pwd_$it") }
    fun saveWifiPassword(ssid: String, pwd: String) {
        // 开放网络无密码，不存空值（否则会误显「已自动填入保存的密码」）
        if (ssid.isNotBlank() && pwd.isNotEmpty()) SecretStore.put(wifiPrefs, "wifi_pwd_$ssid", pwd)
    }
    // 当前回填的密码是否来自存储（用于「已自动填入保存的密码」提示）
    var passwordPreFilled by remember { mutableStateOf(false) }
    var scanResults by remember { mutableStateOf<List<ScanResult>>(emptyList()) }
    var isScanning by remember { mutableStateOf(false) }
    var scanTimedOut by remember { mutableStateOf(false) }
    // 自增即重新扫描；Wi-Fi 打开 / 拿到定位权限时也会自动重扫
    var scanAttempt by remember { mutableStateOf(0) }
    var wifiEnabled by remember { mutableStateOf(wifiManager?.isWifiEnabled == true) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // 请求定位权限
    LaunchedEffect(Unit) {
        if (!hasLocationPermission && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            permissionLauncher.launch(android.Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    // 从扫描列表选中网络：有保存的密码就回填，没有则清空（防止沿用上一个网络的密码）
    LaunchedEffect(selectedSsid) {
        val ssid = selectedSsid
        if (!ssid.isNullOrEmpty()) {
            val saved = savedWifiPassword(ssid)
            password = saved.orEmpty()
            passwordPreFilled = saved != null
        } else {
            passwordPreFilled = false
        }
    }
    // 手动输入 SSID：输入完整 SSID 恰好匹配已保存网络时回填；未匹配不动用户已输入的密码
    LaunchedEffect(manualSsid) {
        if (selectedSsid == "" && manualSsid.isNotBlank()) {
            val saved = savedWifiPassword(manualSsid)
            if (saved != null) {
                password = saved
                passwordPreFilled = true
            }
        }
    }

    // Wi-Fi 开关状态：**必须单独监听**，不能顺带在扫描广播里读。
    // Wi-Fi 关闭时系统根本不会发扫描结果广播，于是 `wifiEnabled` 永远停在 false ——
    // 用户点「打开系统设置」去开 Wi-Fi、回到本页，界面依旧显示「WiFi 已关闭」，
    // 看到的就是"卡住"。
    DisposableEffect(Unit) {
        wifiEnabled = wifiManager?.isWifiEnabled == true
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: android.content.Context, intent: Intent) {
                wifiEnabled = wifiManager?.isWifiEnabled == true
            }
        }
        val filter = IntentFilter(WifiManager.WIFI_STATE_CHANGED_ACTION)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            ctx.registerReceiver(receiver, filter, android.content.Context.RECEIVER_NOT_EXPORTED)
        } else {
            ctx.registerReceiver(receiver, filter)
        }
        onDispose { ctx.unregisterReceiver(receiver) }
    }

    // WiFi 扫描：key 带上 wifiEnabled / scanAttempt，Wi-Fi 打开或用户点「重新扫描」都会重来一次
    DisposableEffect(hasLocationPermission, wifiEnabled, scanAttempt) {
        if (!hasLocationPermission || wifiManager == null || !wifiEnabled) {
            isScanning = false
            return@DisposableEffect onDispose {}
        }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: android.content.Context, intent: Intent) {
                isScanning = false
                scanTimedOut = false
                // scanResults 在定位权限被撤销等边界会抛 SecurityException（系统边界），
                // 不能让广播回调炸出去，否则 isScanning 永远停在 true
                val raw = runCatching { wifiManager.scanResults }.getOrDefault(emptyList())
                // 去重：同 SSID 保留信号最强的
                scanResults = raw
                    .filter { it.SSID.isNotBlank() }
                    .groupBy { it.SSID }
                    .mapNotNull { (_, results) -> results.maxByOrNull { it.level } }
                    .sortedByDescending { it.level }
            }
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ctx.registerReceiver(receiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION), android.content.Context.RECEIVER_EXPORTED)
        } else {
            ctx.registerReceiver(receiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))
        }
        isScanning = true
        scanTimedOut = false
        // 不再"收到广播就立刻 startScan()"：那样形成自续扫描，很快撞上系统扫描节流
        // （节流后既没有广播也不会产生结果，isScanning 永久停在 true），改为用户手动重扫。
        runCatching { wifiManager.startScan() }

        onDispose { ctx.unregisterReceiver(receiver) }
    }

    // 扫描超时兜底：手机「定位服务」总开关关闭、或扫描被系统节流时，系统不会发扫描结果广播，
    // isScanning 会永久停在 true → 页面一直显示「正在扫描…」。到点强制退出扫描态。
    LaunchedEffect(hasLocationPermission, wifiEnabled, scanAttempt) {
        if (!hasLocationPermission || !wifiEnabled) return@LaunchedEffect
        delay(WIFI_SCAN_TIMEOUT_MS)
        if (isScanning) {
            isScanning = false
            scanTimedOut = true
        }
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = ctx.getString(R.string.guide_step4_title_local),
            color = BrewText,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = ctx.getString(R.string.guide_step4_desc),
            color = BrewMuted,
            fontSize = 14.sp,
        )
        Spacer(modifier = Modifier.height(16.dp))

        if (configured) {
            // === 发送成功 ===
            Box(
                modifier = Modifier.fillMaxWidth().height(56.dp)
                    .background(BrewSuccess, shape = RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    ctx.getString(R.string.wifi_configured_success) + " ✓",
                    color = BrewBg, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                )
            }
        } else if (selectedSsid == null) {
            // === WiFi 网络列表 ===
            if (!wifiEnabled) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            ctx.getString(R.string.wifi_disabled_prompt),
                            color = BrewMuted, fontSize = 14.sp,
                        )
                        Spacer(Modifier.height(12.dp))
                        Box(
                            modifier = Modifier.clip(BrewShapeStandard)
                                .background(BrewInfo, BrewShapeStandard)
                                .clickable {
                                    ctx.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    })
                                }
                                .padding(horizontal = 24.dp, vertical = 12.dp),
                        ) {
                            Text(
                                ctx.getString(R.string.wifi_open_settings),
                                color = BrewBg, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                            )
                        }
                        Spacer(Modifier.height(16.dp))
                        // 跳过按钮
                        Box(
                            modifier = Modifier.fillMaxWidth().height(48.dp)
                                .clip(BrewShapeStandard)
                                .background(BrewPanel, BrewShapeStandard)
                                .then(
                                    if (onSkip != null) {
                                        Modifier.clickable { onSkip() }
                                    } else Modifier
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                ctx.getString(R.string.guide_skip_btn),
                                color = BrewText, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                }
            } else if (!hasLocationPermission) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            ctx.getString(R.string.wifi_location_permission_hint),
                            color = BrewMuted, fontSize = 14.sp,
                        )
                        Spacer(Modifier.height(12.dp))
                        Box(
                            modifier = Modifier.clip(BrewShapeStandard)
                                .background(BrewInfo, BrewShapeStandard)
                                .clickable {
                                    permissionLauncher.launch(android.Manifest.permission.ACCESS_FINE_LOCATION)
                                }
                                .padding(horizontal = 24.dp, vertical = 12.dp),
                        ) {
                            Text(
                                ctx.getString(R.string.wifi_grant_permission),
                                color = BrewBg, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            } else {
                if (isScanning && scanResults.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            ctx.getString(R.string.wifi_scanning),
                            color = BrewMuted, fontSize = 14.sp,
                        )
                    }
                } else if (scanResults.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            ctx.getString(
                                if (scanTimedOut) R.string.wifi_scan_timeout else R.string.wifi_no_networks
                            ),
                            color = BrewMuted, fontSize = 14.sp, textAlign = TextAlign.Center,
                        )
                    }
                } else {
                    // 网络列表
                    LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f, fill = false)) {
                        items(scanResults, key = { it.SSID }) { network ->
                            WifiNetworkItem(
                                ssid = network.SSID,
                                level = network.level,
                                capabilities = network.capabilities,
                                onClick = { selectedSsid = network.SSID },
                            )
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                // 出口行：**必须有**。原来「正在扫描…」「未发现网络」两个分支里既没有手动输入、
                // 也没有跳过按钮，一旦扫描卡住用户就彻底出不去这一步（这正是"卡住"的表现）。
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (!isScanning) {
                        Box(
                            modifier = Modifier.clickable { scanAttempt += 1 }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            Text(
                                ctx.getString(R.string.wifi_rescan),
                                color = BrewInfo, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                    Box(
                        modifier = Modifier.clickable { selectedSsid = "" }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Text(
                            ctx.getString(R.string.wifi_manual_input),
                            color = BrewInfo, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                        )
                    }
                }

                Spacer(Modifier.height(8.dp))

                // 跳过按钮
                Box(
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                        .clip(BrewShapeStandard)
                        .background(BrewPanel, BrewShapeStandard)
                        .then(
                            if (onSkip != null) {
                                Modifier.clickable { onSkip() }
                            } else Modifier
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        ctx.getString(R.string.guide_skip_btn),
                        color = BrewText, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                    )
                }
            }
        } else {
            // === 已选网络，输入密码 ===
            val ssid = selectedSsid!!

            if (ssid.isNotEmpty()) {
                // 来自扫描列表
                Box(
                    modifier = Modifier.fillMaxWidth()
                        .clip(BrewShapeStandard).background(BrewPanel, BrewShapeStandard)
                        .padding(12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            ssid, color = BrewText, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f),
                        )
                        Box(
                            modifier = Modifier.clip(CircleShape).background(BrewBorder)
                                .clickable { selectedSsid = null }
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                        ) {
                            Text(
                                ctx.getString(R.string.wifi_change_network),
                                color = BrewText, fontSize = 12.sp,
                            )
                        }
                    }
                }
            } else {
                // 手动输入 SSID
                OutlinedTextField(
                    value = manualSsid,
                    onValueChange = { manualSsid = it; errorMessage = null },
                    label = { Text(ctx.getString(R.string.wifi_ssid_label)) },
                    singleLine = true,
                    enabled = !sending,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    colors = standardFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.height(12.dp))

            // 密码输入
            OutlinedTextField(
                value = password,
                onValueChange = {
                    password = it
                    errorMessage = null
                    passwordPreFilled = false
                },
                label = { Text(ctx.getString(R.string.wifi_password_label)) },
                singleLine = true,
                enabled = !sending,
                visualTransformation = if (showPassword) VisualTransformation.None
                    else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    val targetSsid = if (ssid.isNotEmpty()) ssid else manualSsid
                    if (targetSsid.isNotBlank() && onSendWifiConfig != null) {
                        errorMessage = null
                        sending = true
                        onSendWifiConfig(targetSsid, password) { success, errMsg ->
                            sending = false
                            if (success) {
                                configured = true
                                // 连接成功才记住密码，下次自动回填
                                saveWifiPassword(targetSsid, password)
                            } else {
                                errorMessage = errMsg ?: "连接失败"
                            }
                        }
                    }
                }),
                colors = standardFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(4.dp))

            // 显示密码开关
            Box(
                modifier = Modifier.fillMaxWidth().clickable { showPassword = !showPassword }
                    .padding(vertical = 4.dp),
            ) {
                Text(
                    if (showPassword) ctx.getString(R.string.hide_password)
                    else ctx.getString(R.string.show_password),
                    color = BrewInfo, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                )
            }

            // 自动填入保存密码的提示
            if (passwordPreFilled) {
                Text(
                    ctx.getString(R.string.wifi_password_prefilled),
                    color = BrewMuted, fontSize = 12.sp,
                )
            }

            Spacer(Modifier.height(8.dp))

            // 错误提示
            if (errorMessage != null) {
                Box(
                    modifier = Modifier.fillMaxWidth()
                        .background(BrewRed.copy(alpha = 0.1f), shape = RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "\u26A0",
                            color = BrewRed,
                            fontSize = 14.sp,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                        Text(
                            errorMessage!!,
                            color = BrewRed,
                            fontSize = 13.sp,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            Spacer(Modifier.height(8.dp))

            // 发送按钮
            Box(
                modifier = Modifier.fillMaxWidth().height(56.dp)
                        .clip(BrewShapeStandard)
                        .background(if (sending) BrewPanel else BrewInfo, BrewShapeStandard)
                        .then(
                            if (!sending && onSendWifiConfig != null) {
                                Modifier.clickable {
                                    val targetSsid = if (ssid.isNotEmpty()) ssid else manualSsid
                                    if (targetSsid.isBlank()) {
                                        errorMessage = "请输入网络名称"
                                        return@clickable
                                    }
                                    errorMessage = null
                                    sending = true
                                    onSendWifiConfig(targetSsid, password) { success, errMsg ->
                                        sending = false
                                        if (success) {
                                            configured = true
                                            // 连接成功才记住密码，下次自动回填
                                            saveWifiPassword(targetSsid, password)
                                        } else {
                                            errorMessage = errMsg ?: "连接失败"
                                        }
                                    }
                                }
                            } else Modifier
                        ),
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (sending) {
                        Box(
                            modifier = Modifier.size(20.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            androidx.compose.animation.AnimatedVisibility(
                                visible = sending,
                                enter = androidx.compose.animation.fadeIn(),
                                exit = androidx.compose.animation.fadeOut(),
                            ) {
                                androidx.compose.material3.CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    color = BrewMuted,
                                    strokeWidth = 2.dp,
                                )
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        if (sending) ctx.getString(R.string.wifi_sending)
                        else ctx.getString(R.string.wifi_send_to_glasses),
                        color = if (sending) BrewMuted else BrewBg,
                        fontSize = 16.sp, fontWeight = FontWeight.Bold,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            // 手动输入 SSID
            Box(
                modifier = Modifier.fillMaxWidth().clickable { selectedSsid = ""; errorMessage = null; password = ""; manualSsid = "" }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    ctx.getString(R.string.wifi_manual_input),
                    color = BrewInfo, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                )
            }

            Spacer(Modifier.height(8.dp))

            // 跳过按钮
            Box(
                modifier = Modifier.fillMaxWidth().height(48.dp)
                    .clip(BrewShapeStandard)
                    .background(BrewPanel, BrewShapeStandard)
                    .then(
                        if (onSkip != null) {
                            Modifier.clickable { onSkip() }
                        } else Modifier
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    ctx.getString(R.string.guide_skip_btn),
                    color = BrewText, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun WifiNetworkItem(
    ssid: String,
    level: Int,
    capabilities: String,
    onClick: () -> Unit,
) {
    val isEncrypted = capabilities.contains("WPA", ignoreCase = true) ||
            capabilities.contains("WEP", ignoreCase = true)

    val signalBars = when {
        level > -50 -> 4
        level > -65 -> 3
        level > -80 -> 2
        level > -90 -> 1
        else -> 0
    }

    Row(
        modifier = Modifier.fillMaxWidth()
            .clip(BrewShapeStandard)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 信号强度指示
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.width(24.dp)) {
            repeat(4) { i ->
                Box(
                    modifier = Modifier.width(4.dp)
                        .height((4 + i * 4).dp)
                        .padding(end = 2.dp)
                        .background(
                            if (i < signalBars) BrewInfo else BrewPanel,
                            RoundedCornerShape(2.dp),
                        ),
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        // SSID
        Text(
            ssid, color = BrewText, fontSize = 15.sp,
            modifier = Modifier.weight(1f),
        )

        // 加密锁图标（用文字替代）
        if (isEncrypted) {
            Box(
                modifier = Modifier.width(20.dp).height(16.dp)
                    .border(1.dp, BrewMuted, RoundedCornerShape(3.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "\uD83D\uDD12", fontSize = 10.sp,
                )
            }
            Spacer(Modifier.width(8.dp))
        }

        // 展开箭头
        Text("\u276F", color = BrewMuted, fontSize = 12.sp)
    }

    // 分割线
    Box(
        modifier = Modifier.fillMaxWidth().height(0.5.dp).padding(start = 48.dp)
            .background(BrewBorder),
    )
}

@Composable
private fun standardFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = BrewText,
    unfocusedTextColor = BrewText,
    focusedLabelColor = BrewInfo,
    unfocusedLabelColor = BrewMuted,
    focusedBorderColor = BrewInfo,
    unfocusedBorderColor = BrewBorder,
    cursorColor = BrewText,
)

@Composable
private fun StepInstructions(currentStep: GuideStep, ctx: android.content.Context) {
    val instructions = when (currentStep) {
        GuideStep.SELECT_HOST_APP -> ctx.getString(R.string.guide_step1_desc)
        GuideStep.SELECT_MIRROR_SOURCE -> ctx.getString(R.string.guide_step2_desc)
        GuideStep.AUTHORIZE -> ctx.getString(R.string.guide_step3_desc)
        GuideStep.INSTALL_LINK -> ctx.getString(R.string.guide_install_link_desc)
        GuideStep.CONFIGURE_WIFI -> ctx.getString(R.string.guide_step4_desc)
        GuideStep.READY -> ""
    }
    
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = instructions,
            color = BrewMuted,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
        )
    }
}
