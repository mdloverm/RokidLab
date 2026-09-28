package com.rokidlab.phone.settings

import com.rokidlab.phone.R
import com.rokidlab.phone.app.LabApplication
import com.rokidlab.phone.design.*
import com.rokidlab.phone.design.theme.BrewTheme
import com.rokidlab.phone.design.theme.BrewThemeManager
import com.rokidlab.phone.store.*
import com.rokidlab.phone.util.LocalizationManager
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

@Composable
internal fun SettingsScreen(
    state: StoreUiState,
    actions: StoreActions,
) {
    val ctx = LocalContext.current
    var showLangDialog by remember { mutableStateOf(false) }
    var showThemeDialog by remember { mutableStateOf(false) }
    var showSponsorDialog by remember { mutableStateOf(false) }
    var showContributorsDialog by remember { mutableStateOf(false) }
    val currentTheme = BrewThemeManager.currentTheme

    // ── 二级页入口 ──
    var showDeveloperMode by remember { mutableStateOf(false) }
    var showLocalExec by remember { mutableStateOf(false) }

    when {
        showLocalExec -> LocalExecScreen(onBack = { showLocalExec = false })
        showDeveloperMode -> DeveloperModeScreen(onBack = { showDeveloperMode = false }, actions = actions)
        else -> Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        ModuleHeader(title = ctx.getString(R.string.nav_settings), subtitle = ctx.getString(R.string.settings_subtitle), color = BrewMagenta)
        Spacer(modifier = Modifier.height(24.dp))

        // ── 基本信息 ──
        SettingsCard(title = ctx.getString(R.string.app_version), content = state.selfUpdateState.currentVersion, color = BrewCoral)
        Spacer(modifier = Modifier.height(12.dp))

        SettingsCard(
            title = ctx.getString(R.string.host_app),
            content = state.selectedHostApp.displayName,
            color = BrewCyan,
            onClick = { actions.onGoToGuideStep1() },
        )
        Spacer(modifier = Modifier.height(12.dp))

        // Language
        val currentLangName = LocalizationManager.AppLocale.entries
            .find { it.code == state.currentLocale }
            ?.let { "${it.displayName}" } ?: ctx.getString(R.string.language_simple_chinese)
        SettingsCard(
            title = ctx.getString(R.string.language),
            content = currentLangName,
            color = BrewPurple,
            onClick = { showLangDialog = true },
        )
        Spacer(modifier = Modifier.height(12.dp))

        // Theme
        SettingsCard(
            title = ctx.getString(R.string.theme),
            content = ctx.getString(currentTheme.displayNameResId),
            color = BrewAmber,
            onClick = { showThemeDialog = true },
        )
        Spacer(modifier = Modifier.height(12.dp))

        // Update status
        if (state.selfUpdateState.available) {
            SettingsCard(
                title = ctx.getString(R.string.update_status),
                content = ctx.getString(R.string.update_available),
                color = BrewCoral,
                onClick = actions.onSelfUpdate,
            )
        } else {
            SettingsCard(
                title = ctx.getString(R.string.update_status),
                content = ctx.getString(R.string.no_update),
                color = BrewMuted,
            )
        }
        Spacer(modifier = Modifier.height(12.dp))

        // Switch source
        SettingsCard(
            title = ctx.getString(R.string.switch_source),
            content = ctx.getString(R.string.switch_source_desc),
            color = BrewCyan,
            onClick = actions.onSwitchMirror,
        )
        Spacer(modifier = Modifier.height(12.dp))

        // 后台保活开关
        val labApp = ctx.applicationContext as LabApplication
        var keepAliveEnabled by remember { mutableStateOf(labApp.keepAliveEnabled) }
        SettingsCard(
            title = ctx.getString(R.string.keep_alive_enabled),
            content = if (keepAliveEnabled) ctx.getString(R.string.keep_alive_on) else ctx.getString(R.string.keep_alive_off),
            color = if (keepAliveEnabled) BrewSuccess else BrewMuted,
            onClick = {
                actions.onToggleKeepAlive()
                keepAliveEnabled = labApp.keepAliveEnabled
            },
        )
        Spacer(modifier = Modifier.height(12.dp))

        // 主动式功能开关已收编进聊天输入栏「主动性」下弹面板（档位 + 功能开关唯一入口）；
        // 陪伴默契统计也已挪为该面板副标题（2026-09-28 用户要求），设置页不再展示。
        Spacer(modifier = Modifier.height(24.dp))

        // ── 眼镜端服务 ──
        SectionTitle(ctx.getString(R.string.glasses_services), BrewMagenta)
        Spacer(modifier = Modifier.height(12.dp))

        SettingsCard(
            title = "RokidLink",
            content = if (state.screenMirrorState.rokidLinkInstalled == true) ctx.getString(R.string.installed) else ctx.getString(R.string.not_installed),
            color = if (state.screenMirrorState.rokidLinkInstalled == true) BrewCoral else BrewWarning,
        )
        Spacer(modifier = Modifier.height(12.dp))
        SettingsCard(
            title = ctx.getString(R.string.reinstall_glasses),
            content = if (state.screenMirrorState.isInstallingRokidLink) ctx.getString(R.string.installing_rokid_link) else ctx.getString(R.string.reinstall_glasses_desc),
            color = BrewWarning,
            enabled = !state.screenMirrorState.isInstallingRokidLink,
            onClick = actions.onSettingsReinstallRokidLink,
        )
        Spacer(modifier = Modifier.height(12.dp))

        // ── 更多 ──
        SectionTitle(ctx.getString(R.string.more_section), BrewCoral)
        Spacer(modifier = Modifier.height(12.dp))

        // Developer info
        SettingsCard(
            title = ctx.getString(R.string.developer),
            content = "DLOVER",
            color = BrewCoral,
            onClick = {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://gitee.com/dlover1314/RokidLab"))
                ctx.startActivity(intent)
            },
        )
        Spacer(modifier = Modifier.height(12.dp))

        // Sponsor
        SettingsCard(
            title = ctx.getString(R.string.sponsor),
            content = ctx.getString(R.string.sponsor_desc),
            color = BrewAmber,
            onClick = { showSponsorDialog = true },
        )
        Spacer(modifier = Modifier.height(12.dp))

        // Contributors
        SettingsCard(
            title = ctx.getString(R.string.contributors),
            content = ctx.getString(R.string.contributors_desc),
            color = BrewSuccess,
            onClick = { showContributorsDialog = true },
        )
        Spacer(modifier = Modifier.height(12.dp))

        // 本机执行环境（自持 proot）：可选能力 —— 不装不影响任何其它功能，装了 AI 才能在手机上跑命令
        SettingsCard(
            title = ctx.getString(R.string.settings_local_exec),
            content = ctx.getString(R.string.settings_local_exec_subtitle),
            color = BrewTeal,
            onClick = { showLocalExec = true },
        )
        Spacer(modifier = Modifier.height(12.dp))

        // 开发者模式（开发/排障工具二级页：眼镜状态、线路探测、提交应用、日志/诊断导出）
        SettingsCard(
            title = ctx.getString(R.string.settings_developer_mode),
            content = ctx.getString(R.string.settings_developer_mode_subtitle),
            color = BrewInfo,
            onClick = { showDeveloperMode = true },
        )
        Spacer(modifier = Modifier.height(48.dp))
    }
    }

    // Language selection dialog
    if (showLangDialog) {
        LanguageDialog(
            currentCode = state.currentLocale,
            onSelect = { code ->
                actions.onSwitchLanguage(code)
                showLangDialog = false
            },
            onDismiss = { showLangDialog = false },
        )
    }

    if (showThemeDialog) {
        ThemeDialog(
            currentTheme = currentTheme,
            onSelect = { BrewThemeManager.switchTheme(it) },
            onDismiss = { showThemeDialog = false },
        )
    }

    // Sponsor dialog
    if (showSponsorDialog) {
        SponsorDialog(onDismiss = { showSponsorDialog = false })
    }

    // Contributors dialog
    if (showContributorsDialog) {
        ContributorsDialog(onDismiss = { showContributorsDialog = false })
    }
}

/** 社区贡献者条目：作者名（昵称，不翻译）+ 贡献内容（走多语言） */
private data class Contributor(val name: String, val contribution: String)

@Composable
private fun ContributorsDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val contributors = remember {
        listOf(
            Contributor("ft", ctx.getString(R.string.contributors_ft_desc)),
        )
    }

    BrewDialog(
        onDismiss = onDismiss,
        title = ctx.getString(R.string.contributors),
        color = BrewSuccess,
    ) {
        BrewDialogContent {
            contributors.forEach { contributor ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(BrewSuccess.copy(alpha = 0.12f))
                        .border(1.dp, BrewSuccess.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 首字母圆形头像（无网络头像资源时的轻量展示）
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(BrewSuccess),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            contributor.name.firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            contributor.name,
                            color = BrewText,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            contributor.contribution,
                            color = BrewMuted,
                            fontSize = 12.sp,
                        )
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
            }
        }
    }
}

@Composable
private fun SponsorDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var sponsors by remember { mutableStateOf<List<SponsorUser>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var errorMsg by remember { mutableStateOf<String?>(null) }

    // 打开弹窗时获取赞助数据
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            try {
                sponsors = fetchSponsors()
            } catch (e: Exception) {
                errorMsg = e.message
            }
            loading = false
        }
    }

    BrewDialog(
        onDismiss = onDismiss,
        title = ctx.getString(R.string.sponsor),
        color = BrewAmber,
    ) {
        BrewDialogContent {
            // ── 赞助方式 ──
            Text(
                text = ctx.getString(R.string.sponsor_methods),
                color = BrewText,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(12.dp))

            // AI Fa Dian
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(BrewAmber.copy(alpha = 0.12f))
                    .border(1.dp, BrewAmber.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                    .clickable {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://ifdian.net/a/rokidlab"))
                        ctx.startActivity(intent)
                    }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(BrewAmber),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("⚡", fontSize = 18.sp)
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(ctx.getString(R.string.sponsor_afdian), color = BrewAmber, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Text(ctx.getString(R.string.sponsor_afdian_desc), color = BrewMuted, fontSize = 11.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ── 赞助用户 ──
            Text(
                text = ctx.getString(R.string.sponsor_list),
                color = BrewText,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(12.dp))

            val errorMsgText = errorMsg
            if (loading) {
                Text(
                    text = "加载中...",
                    color = BrewDim,
                    fontSize = 13.sp,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
            } else if (errorMsgText != null) {
                Text(
                    text = errorMsgText,
                    color = BrewWarning,
                    fontSize = 12.sp,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
            } else if (sponsors.isEmpty()) {
                Text(
                    text = ctx.getString(R.string.sponsor_empty),
                    color = BrewDim,
                    fontSize = 13.sp,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
            } else {
                val rows = sponsors.chunked(4)
                rows.forEach { rowSponsors ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        rowSponsors.forEach { sponsor ->
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.padding(vertical = 8.dp),
                            ) {
                                SponsorAvatar(
                                    avatarUrl = sponsor.avatar,
                                    name = sponsor.name,
                                    size = 48.dp,
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    sponsor.name,
                                    color = BrewMuted,
                                    fontSize = 10.sp,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            if (sponsors.isNotEmpty()) {
                Text(
                    text = ctx.getString(R.string.sponsor_more_coming),
                    color = BrewDim,
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun LanguageDialog(
    currentCode: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    BrewDialog(onDismiss = onDismiss, title = ctx.getString(R.string.select_language), color = BrewPurple) {
        BrewDialogContent {
            LocalizationManager.AppLocale.entries.forEach { locale ->
                val selected = locale.code == currentCode
                BrewDialogSelectItem(
                    primaryText = locale.displayName,
                    secondaryText = locale.displayNameEnglish,
                    isSelected = selected,
                    onClick = { onSelect(locale.code) },
                    selectedColor = BrewPurple,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun ThemeDialog(
    currentTheme: BrewTheme,
    onSelect: (BrewTheme) -> Unit,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    BrewDialog(onDismiss = onDismiss, title = ctx.getString(R.string.select_theme), color = BrewAmber) {
        BrewDialogContent {
            BrewThemeManager.getAllThemes().forEach { theme ->
                val selected = theme == currentTheme
                BrewDialogSelectItem(
                    primaryText = ctx.getString(theme.displayNameResId),
                    secondaryText = ctx.getString(theme.descriptionResId),
                    isSelected = selected,
                    onClick = { onSelect(theme) },
                    selectedColor = BrewAmber,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
        }
    }
}

@Composable
internal fun ModuleHeader(title: String, subtitle: String, color: Color) {
    Column {
        Text(text = title, color = color, fontSize = 32.sp, fontWeight = FontWeight.Black, letterSpacing = 4.sp)
        Spacer(modifier = Modifier.height(8.dp))
        Text(text = subtitle, color = BrewMuted, fontSize = 14.sp)
    }
}

@Composable
private fun SectionTitle(text: String, color: Color) {
    Text(
        text = text.uppercase(),
        color = color.copy(alpha = 0.8f),
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 3.sp,
    )
}

@Composable
internal fun SettingsCard(
    title: String,
    content: String,
    color: Color,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.98f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "card-press",
    )
    val dimAlpha = if (enabled) 1f else 0.45f

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { scaleX = pressScale; scaleY = pressScale; alpha = dimAlpha }
            .clip(RoundedCornerShape(12.dp))
            .background(BrewPanel, RoundedCornerShape(12.dp))
            .border(width = 1.dp, color = BrewBorder, shape = RoundedCornerShape(12.dp))
            .then(
                if (onClick != null && enabled) {
                    Modifier.clickable(interactionSource = interactionSource, indication = null) { onClick() }
                } else Modifier
            )
            .padding(16.dp),
    ) {
        Column {
            Text(
                text = title.uppercase(),
                color = if (enabled) BrewMuted else BrewMuted.copy(alpha = 0.5f),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 3.sp,
                modifier = Modifier.padding(bottom = 4.dp),
            )
            Box(
                modifier = Modifier
                    .width(32.dp)
                    .height(3.dp)
                    .background(color.copy(alpha = if (enabled) 1f else 0.4f))
                    .padding(bottom = 8.dp),
            )
            Text(
                text = content,
                color = if (enabled) color else color.copy(alpha = 0.5f),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp,
            )
        }
    }
}

// ── 爱发电 API ──

private data class SponsorUser(
    val name: String,
    val avatar: String,
)

private const val IFDIAN_USER_ID = "76a44eda67c011f1854952540025c377"
private const val IFDIAN_TOKEN = "Vd7nJXymva9s5xDujfHGCqc4bQW6YEUM"

private fun fetchSponsors(): List<SponsorUser> {
    val params = """{"page":1}"""
    val ts = System.currentTimeMillis() / 1000L
    val signStr = IFDIAN_TOKEN + "params" + params + "ts" + ts + "user_id" + IFDIAN_USER_ID
    val sign = MessageDigest.getInstance("MD5").digest(signStr.toByteArray())
        .joinToString("") { "%02x".format(it) }

    val body = JSONObject().apply {
        put("user_id", IFDIAN_USER_ID)
        put("params", params)
        put("ts", ts.toString())
        put("sign", sign)
    }

    val conn = URL("https://ifdian.net/api/open/query-sponsor").openConnection() as HttpURLConnection
    conn.requestMethod = "POST"
    conn.doOutput = true
    conn.setRequestProperty("Content-Type", "application/json;charset=UTF-8")
    conn.connectTimeout = 10000
    conn.readTimeout = 10000
    try {
        conn.outputStream.use { os ->
            os.write(body.toString().toByteArray(Charsets.UTF_8))
        }
        val code = conn.responseCode
        if (code != 200) throw RuntimeException("HTTP $code")
        val resp = conn.inputStream.bufferedReader().readText()
        val json = JSONObject(resp)
        val ec = json.optInt("ec")
        if (ec != 200) throw RuntimeException("API error: $ec ${json.optString("em")}")
        val data = json.optJSONObject("data") ?: return emptyList()
        val list = data.optJSONArray("list") ?: return emptyList()
        return buildList {
            for (i in 0 until list.length()) {
                val item = list.optJSONObject(i) ?: continue
                val user = item.optJSONObject("user") ?: continue
                val name = user.optString("name").takeIf { it.isNotBlank() } ?: continue
                val avatar = user.optString("avatar").takeIf { it.isNotBlank() } ?: ""
                add(SponsorUser(name, avatar))
            }
        }
    } finally {
        conn.disconnect()
    }
}

// ── 赞助商头像加载 ──

@Composable
private fun SponsorAvatar(
    avatarUrl: String,
    name: String,
    size: androidx.compose.ui.unit.Dp,
) {
    var bitmap by remember { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(avatarUrl) {
        if (avatarUrl.isBlank()) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            try {
                val conn = URL(avatarUrl).openConnection() as HttpURLConnection
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.instanceFollowRedirects = true
                try {
                    conn.connect()
                    val bytes = conn.inputStream.use { input ->
                        val baos = ByteArrayOutputStream()
                        input.copyTo(baos)
                        baos.toByteArray()
                    }
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bmp != null) bitmap = bmp.asImageBitmap()
                } finally {
                    conn.disconnect()
                }
            } catch (_: Exception) {
                // 加载失败就不显示头像
            }
        }
    }

    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(if (bitmap != null) Color.Transparent else BrewAmber.copy(alpha = 0.2f))
            .border(
                width = if (bitmap != null) 0.dp else 2.dp,
                color = if (bitmap != null) Color.Transparent else BrewAmber.copy(alpha = 0.5f),
                shape = CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        val resolvedBitmap = bitmap
        if (resolvedBitmap != null) {
            Image(
                bitmap = resolvedBitmap,
                contentDescription = name,
                modifier = Modifier.fillMaxSize().clip(CircleShape),
                contentScale = ContentScale.Crop,
            )
        } else {
            Text(
                name.take(1),
                color = BrewAmber,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}
