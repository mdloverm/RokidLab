package com.rokidlab.phone.settings

import com.rokidlab.phone.design.*
import com.rokidlab.phone.R
import android.content.Context
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

private const val TAG = "DeveloperScreen"
private const val GITEE_OWNER = "dlover1314"
private const val GITEE_REPO = "RokidBrew-Registry"
private const val GITEE_API_BASE = "https://gitee.com/api/v5"
private const val APPS_JSON_PATH = "dist/apps.v1.json"

/**
 * !!! 重要: 打包前请将下面的 Token 替换为你自己的 Gitee Personal Access Token !!!
 * 生成方式: Gitee → 设置 → 私人令牌 → 生成新令牌（勾选 projects 权限）
 */
private const val GITEE_TOKEN = "49bba993ecf39b735883064a78a917aa"

// ── 表单持久化 ──
private const val PREFS_NAME = "developer_form"
private const val PREFS_APP_ID = "appId"
private const val PREFS_APP_NAME = "appName"
private const val PREFS_APP_TYPE = "appType"
private const val PREFS_APP_CATEGORY = "appCategory"
private const val PREFS_APP_VERSION = "appVersion"
private const val PREFS_APP_SUMMARY = "appSummary"
private const val PREFS_APP_DESC = "appDesc"
private const val PREFS_APP_AUTHOR = "appAuthor"
private const val PREFS_APP_SOURCE_URL = "appSourceUrl"
private const val PREFS_APP_ICON_URL = "appIconUrl"
private const val PREFS_APP_ICON_ASSET = "appIconAsset"
private const val PREFS_APP_PHONE_REQUIRED = "appPhoneRequired"
private const val PREFS_SCREENSHOT_URLS = "screenshotUrls"
private const val PREFS_SCREENSHOT_ASSETS = "screenshotAssets"
private const val PREFS_LISTING_ABOUT = "listingAbout"
private const val PREFS_LISTING_DESC = "listingDesc"
private const val PREFS_RELEASE_VERSION = "releaseVersion"
private const val PREFS_RELEASE_DATE = "releaseDate"
private const val PREFS_RELEASE_URL = "releaseUrl"
private const val PREFS_RELEASE_NOTES = "releaseNotes"
private const val PREFS_RELEASE_CHANGES = "releaseChanges"
private const val PREFS_ARTIFACT_TARGET1 = "artifactTarget1"
private const val PREFS_ARTIFACT_URL1 = "artifactUrl1"
private const val PREFS_ARTIFACT_PKG1 = "artifactPkg1"
private const val PREFS_ARTIFACT_VC1 = "artifactVc1"
private const val PREFS_ARTIFACT_SHA1 = "artifactSha1"
private const val PREFS_ARTIFACT_SIZE1 = "artifactSize1"
private const val PREFS_ARTIFACT_TARGET2 = "artifactTarget2"
private const val PREFS_ARTIFACT_URL2 = "artifactUrl2"
private const val PREFS_ARTIFACT_PKG2 = "artifactPkg2"
private const val PREFS_ARTIFACT_VC2 = "artifactVc2"
private const val PREFS_ARTIFACT_SHA2 = "artifactSha2"
private const val PREFS_ARTIFACT_SIZE2 = "artifactSize2"
private const val PREFS_SHOW_SECOND = "showSecond"

/**
 * 开发者提交流程（无需用户输入 Token，已编译进 App）
 * 1. 开发者填写应用信息
 * 2. 点击提交 → 直接通过 Gitee API 修改 apps.v1.json 追加应用
 * 3. 重启 App 后自动刷新，新应用出现在商店中
 *
 * 字段说明：参照 BrewApp / BrewArtifact / BrewListing / BrewRelease 解析逻辑
 */

@Composable
internal fun DeveloperScreen(
    onBack: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    // ── 核心字段 ──
    var appId by remember { mutableStateOf("") }
    var appName by remember { mutableStateOf("") }
    var appType by remember { mutableStateOf("glasses") }
    var appCategory by remember { mutableStateOf("Utility") }
    var appVersion by remember { mutableStateOf("1.0.0") }
    var appSummary by remember { mutableStateOf("") }
    var appDescription by remember { mutableStateOf("") }
    var appAuthor by remember { mutableStateOf("") }
    var appSourceUrl by remember { mutableStateOf("") }
    var appIconUrl by remember { mutableStateOf("") }
    var appIconAsset by remember { mutableStateOf("") }
    var appPhoneRequired by remember { mutableStateOf(false) }

    // ── 截屏（逗号分隔，解析为数组） ──
    var appScreenshotUrls by remember { mutableStateOf("") }
    var appScreenshotAssets by remember { mutableStateOf("") }

    // ── listing ──
    var listingAbout by remember { mutableStateOf("") }
    var listingDescriptionMarkdown by remember { mutableStateOf("") }

    // ── artifacts：支持多 target ──
    var artifactTarget1 by remember { mutableStateOf("glasses") }
    var artifactUrl1 by remember { mutableStateOf("") }
    var artifactPackage1 by remember { mutableStateOf("") }
    var artifactVersionCode1 by remember { mutableStateOf("1") }
    var artifactSha256_1 by remember { mutableStateOf("") }
    var artifactSize1 by remember { mutableStateOf("") }

    var showSecondArtifact by remember { mutableStateOf(false) }
    var artifactTarget2 by remember { mutableStateOf("phone") }
    var artifactUrl2 by remember { mutableStateOf("") }
    var artifactPackage2 by remember { mutableStateOf("") }
    var artifactVersionCode2 by remember { mutableStateOf("1") }
    var artifactSha256_2 by remember { mutableStateOf("") }
    var artifactSize2 by remember { mutableStateOf("") }

    // ── release（可选，折叠） ──
    var releaseVersion by remember { mutableStateOf("") }
    var releaseDate by remember { mutableStateOf("") }
    var releaseSourceUrl by remember { mutableStateOf("") }
    var releaseNotes by remember { mutableStateOf("") }
    var releaseChanges by remember { mutableStateOf("") } // 每行一条

    // ── 提交状态 ──
    var submitting by remember { mutableStateOf(false) }
    var submitResult by remember { mutableStateOf<String?>(null) }
    var isSuccess by remember { mutableStateOf(false) }
    var showJsonPreview by remember { mutableStateOf(false) }
    var jsonEditorText by remember { mutableStateOf("") }
    var jsonEditorError by remember { mutableStateOf<String?>(null) }

    // ── 折叠控制 ──
    var showOptionalSection by remember { mutableStateOf(false) }
    var showReleaseSection by remember { mutableStateOf(false) }

    // ── 验证 ──
    var validationErrors by remember { mutableStateOf<List<String>>(emptyList()) }

    val types = listOf("glasses", "phone", "combo")
    val categories = listOf("Utility", "Game", "Social", "Media", "Tool", "Education", "Productivity", "Other")

    // ── 系统返回键 ──
    BackHandler(onBack = onBack)

    // ── 持久化存储 ──
    val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    LaunchedEffect(Unit) {
        loadDeveloperFormState(prefs,
            setAppId = { appId = it },
            setAppName = { appName = it },
            setAppType = { appType = it },
            setAppCategory = { appCategory = it },
            setAppVersion = { appVersion = it },
            setAppSummary = { appSummary = it },
            setAppDescription = { appDescription = it },
            setAppAuthor = { appAuthor = it },
            setAppSourceUrl = { appSourceUrl = it },
            setAppIconUrl = { appIconUrl = it },
            setAppIconAsset = { appIconAsset = it },
            setAppPhoneRequired = { appPhoneRequired = it },
            setAppScreenshotUrls = { appScreenshotUrls = it },
            setAppScreenshotAssets = { appScreenshotAssets = it },
            setListingAbout = { listingAbout = it },
            setListingDescriptionMarkdown = { listingDescriptionMarkdown = it },
            setReleaseVersion = { releaseVersion = it },
            setReleaseDate = { releaseDate = it },
            setReleaseSourceUrl = { releaseSourceUrl = it },
            setReleaseNotes = { releaseNotes = it },
            setReleaseChanges = { releaseChanges = it },
            setArtifactTarget1 = { artifactTarget1 = it },
            setArtifactUrl1 = { artifactUrl1 = it },
            setArtifactPackage1 = { artifactPackage1 = it },
            setArtifactVersionCode1 = { artifactVersionCode1 = it },
            setArtifactSha256_1 = { artifactSha256_1 = it },
            setArtifactSize1 = { artifactSize1 = it },
            setArtifactTarget2 = { artifactTarget2 = it },
            setArtifactUrl2 = { artifactUrl2 = it },
            setArtifactPackage2 = { artifactPackage2 = it },
            setArtifactVersionCode2 = { artifactVersionCode2 = it },
            setArtifactSha256_2 = { artifactSha256_2 = it },
            setArtifactSize2 = { artifactSize2 = it },
            setShowSecondArtifact = { showSecondArtifact = it },
        )
    }
    // 离开页面时保存
    DisposableEffect(Unit) {
        onDispose {
            saveDeveloperFormState(prefs.edit(),
                appId = appId, appName = appName, appType = appType,
                appCategory = appCategory, appVersion = appVersion,
                appSummary = appSummary, appDescription = appDescription,
                appAuthor = appAuthor, appSourceUrl = appSourceUrl,
                appIconUrl = appIconUrl, appIconAsset = appIconAsset,
                appPhoneRequired = appPhoneRequired,
                appScreenshotUrls = appScreenshotUrls,
                appScreenshotAssets = appScreenshotAssets,
                listingAbout = listingAbout,
                listingDescriptionMarkdown = listingDescriptionMarkdown,
                releaseVersion = releaseVersion, releaseDate = releaseDate,
                releaseSourceUrl = releaseSourceUrl,
                releaseNotes = releaseNotes, releaseChanges = releaseChanges,
                artifactTarget1 = artifactTarget1, artifactUrl1 = artifactUrl1,
                artifactPackage1 = artifactPackage1,
                artifactVersionCode1 = artifactVersionCode1,
                artifactSha256_1 = artifactSha256_1, artifactSize1 = artifactSize1,
                showSecondArtifact = showSecondArtifact,
                artifactTarget2 = artifactTarget2, artifactUrl2 = artifactUrl2,
                artifactPackage2 = artifactPackage2,
                artifactVersionCode2 = artifactVersionCode2,
                artifactSha256_2 = artifactSha256_2, artifactSize2 = artifactSize2,
            ).apply()
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(BrewBg).padding(16.dp),
    ) {
        // ── 顶栏（仅标题，无返回按钮） ──
        Text(text = ctx.getString(R.string.developer_title), color = BrewCoral, fontSize = 28.sp, fontWeight = FontWeight.Black, letterSpacing = 3.sp)

        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 8.dp),
        ) {
            Text(text = ctx.getString(R.string.developer_subtitle), color = BrewDim, fontSize = 12.sp)

            // ── 结果提示 ──
            if (submitResult != null) {
                Spacer(modifier = Modifier.height(12.dp))
                val resultColor = if (isSuccess) BrewSuccess else BrewWarning
                Box(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                        .background(resultColor.copy(alpha = 0.1f))
                        .border(1.dp, resultColor.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                        .padding(12.dp),
                ) {
                    Text(text = submitResult!!, color = resultColor, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }

            // ── 验证错误 ──
            if (validationErrors.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Box(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                        .background(BrewWarning.copy(alpha = 0.1f))
                        .border(1.dp, BrewWarning.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                        .padding(12.dp),
                ) {
                    Column { validationErrors.forEach { Text(text = "• $it", color = BrewWarning, fontSize = 11.sp) } }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            // ══════════════════════════════════════════
            // 必填信息 ★
            // ══════════════════════════════════════════
            Box(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                    .background(BrewWarning.copy(alpha = 0.06f))
                    .border(1.dp, BrewWarning.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
                    .padding(12.dp),
            ) {
                SectionLabel(ctx.getString(R.string.developer_required), BrewWarning)
            }
            Spacer(modifier = Modifier.height(8.dp))

            // ── 应用名称 + 标识 ──
            FieldLabel(ctx.getString(R.string.developer_app_name), required = true)
            FormField(appName, { appName = it }, ctx.getString(R.string.developer_app_name))
            Spacer(modifier = Modifier.height(8.dp))
            FieldLabel(ctx.getString(R.string.developer_app_id), required = true)
            FormField(appId, { appId = it }, ctx.getString(R.string.developer_app_id))

            // ── 版本 + 作者 ──
            Spacer(modifier = Modifier.height(8.dp))
            Row {
                Column(Modifier.weight(1f)) {
                    FieldLabel(ctx.getString(R.string.developer_version), required = true)
                    FormField(appVersion, { appVersion = it }, ctx.getString(R.string.developer_version))
                }
                Spacer(modifier = Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    FieldLabel(ctx.getString(R.string.developer_author))
                    FormField(appAuthor, { appAuthor = it }, ctx.getString(R.string.developer_author))
                }
            }

            // ── Type + Category ──
            Spacer(modifier = Modifier.height(8.dp))
            Row {
                Column(Modifier.weight(1f)) {
                    FieldLabel(ctx.getString(R.string.developer_type))
                    DropdownSelector("", appType, types, { appType = it })
                }
                Spacer(modifier = Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    FieldLabel(ctx.getString(R.string.developer_category))
                    DropdownSelector("", appCategory, categories, { appCategory = it })
                }
            }

            // ── 简介 ──
            Spacer(modifier = Modifier.height(8.dp))
            FieldLabel(ctx.getString(R.string.developer_summary), required = true)
            FormField(appSummary, { appSummary = it }, ctx.getString(R.string.developer_summary), singleLine = false, maxLines = 2)

            // ── APK 构件 ──
            Spacer(modifier = Modifier.height(16.dp))
            SectionLabel(ctx.getString(R.string.developer_artifact), BrewWarning)
            Spacer(modifier = Modifier.height(8.dp))
            Row {
                Column(Modifier.weight(0.32f)) {
                    FieldLabel(ctx.getString(R.string.developer_artifact_target))
                    DropdownSelector("", artifactTarget1, types.take(2), { artifactTarget1 = it })
                }
                Spacer(modifier = Modifier.width(8.dp))
                Column(Modifier.weight(0.68f)) {
                    FieldLabel(ctx.getString(R.string.developer_package_name), required = true)
                    FormField(artifactPackage1, { artifactPackage1 = it }, ctx.getString(R.string.developer_package_name))
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            FieldLabel(ctx.getString(R.string.developer_apk_url), required = true)
            FormField(artifactUrl1, { artifactUrl1 = it }, ctx.getString(R.string.developer_apk_url))
            Spacer(modifier = Modifier.height(8.dp))
            Row {
                Column(Modifier.weight(1f)) {
                    FieldLabel(ctx.getString(R.string.developer_version_code))
                    FormField(artifactVersionCode1, { artifactVersionCode1 = it }, ctx.getString(R.string.developer_version_code), keyboardType = KeyboardType.Number)
                }
                Spacer(modifier = Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    FieldLabel("SHA-256")
                    FormField(artifactSha256_1, { artifactSha256_1 = it }, "SHA-256")
                }
                Spacer(modifier = Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    FieldLabel(ctx.getString(R.string.developer_size_bytes))
                    FormField(artifactSize1, { artifactSize1 = it }, ctx.getString(R.string.developer_size_bytes), keyboardType = KeyboardType.Number)
                }
            }

            // ── 第二构件 ──
            Spacer(modifier = Modifier.height(8.dp))
            if (!showSecondArtifact) {
                BrutalButton(
                    label = ctx.getString(R.string.developer_add_artifact),
                    color = BrewCyan,
                    enabled = true,
                    onClick = { showSecondArtifact = true },
                )
            } else {
                Box(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                        .background(BrewPanel).border(1.dp, BrewBorder, RoundedCornerShape(8.dp))
                        .padding(10.dp),
                ) {
                    Column {
                        Text(text = ctx.getString(R.string.developer_artifact2), color = BrewDim, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(6.dp))
                        Row {
                            DropdownSelector("", artifactTarget2, listOf("phone", "glasses"), { artifactTarget2 = it }, Modifier.weight(0.32f))
                            Spacer(modifier = Modifier.width(8.dp))
                            FormField(artifactPackage2, { artifactPackage2 = it }, ctx.getString(R.string.developer_package_name), modifier = Modifier.weight(0.68f))
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        FormField(artifactUrl2, { artifactUrl2 = it }, ctx.getString(R.string.developer_apk_url))
                        Spacer(modifier = Modifier.height(6.dp))
                        Row {
                            FormField(artifactVersionCode2, { artifactVersionCode2 = it }, ctx.getString(R.string.developer_version_code), modifier = Modifier.weight(1f), keyboardType = KeyboardType.Number)
                            Spacer(modifier = Modifier.width(8.dp))
                            FormField(artifactSha256_2, { artifactSha256_2 = it }, "SHA-256", modifier = Modifier.weight(1f))
                            Spacer(modifier = Modifier.width(8.dp))
                            FormField(artifactSize2, { artifactSize2 = it }, ctx.getString(R.string.developer_size_bytes), modifier = Modifier.weight(1f), keyboardType = KeyboardType.Number)
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        BrutalButton(label = ctx.getString(R.string.developer_remove_artifact), color = BrewWarning, enabled = true, onClick = { showSecondArtifact = false })
                    }
                }
            }

            // ══════════════════════════════════════════
            // 选填信息（可折叠）
            // ══════════════════════════════════════════
            Spacer(modifier = Modifier.height(20.dp))
            Box(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                    .background(BrewDim.copy(alpha = 0.06f))
                    .border(1.dp, BrewDim.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
                    .clickable { showOptionalSection = !showOptionalSection }
                    .padding(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SectionLabel(ctx.getString(R.string.developer_optional), BrewDim)
                    Text(text = if (showOptionalSection) "▲" else "▼", color = BrewDim, fontSize = 12.sp)
                }
            }
            if (showOptionalSection) {
                Spacer(modifier = Modifier.height(8.dp))

            // ── 描述 + 源码 ──
            FieldLabel(ctx.getString(R.string.developer_description))
            FormField(appDescription, { appDescription = it }, ctx.getString(R.string.developer_description), singleLine = false, maxLines = 4)
            Spacer(modifier = Modifier.height(8.dp))
            FieldLabel(ctx.getString(R.string.developer_source_url))
            FormField(appSourceUrl, { appSourceUrl = it }, ctx.getString(R.string.developer_source_url))

            // ── 图标 ──
            Spacer(modifier = Modifier.height(8.dp))
            Row {
                Column(Modifier.weight(1f)) {
                    FieldLabel(ctx.getString(R.string.developer_icon_url))
                    FormField(appIconUrl, { appIconUrl = it }, ctx.getString(R.string.developer_icon_url))
                }
                Spacer(modifier = Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    FieldLabel(ctx.getString(R.string.developer_icon_asset))
                    FormField(appIconAsset, { appIconAsset = it }, ctx.getString(R.string.developer_icon_asset))
                }
            }

            // ── 截屏 ──
            Spacer(modifier = Modifier.height(8.dp))
            FieldLabel(ctx.getString(R.string.developer_screenshot_urls))
            FormField(appScreenshotUrls, { appScreenshotUrls = it }, ctx.getString(R.string.developer_screenshot_urls), singleLine = false, maxLines = 2)
            Spacer(modifier = Modifier.height(8.dp))
            FieldLabel(ctx.getString(R.string.developer_screenshot_assets))
            FormField(appScreenshotAssets, { appScreenshotAssets = it }, ctx.getString(R.string.developer_screenshot_assets), singleLine = false, maxLines = 2)

            // ── Phone Required ──
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.Checkbox(
                    checked = appPhoneRequired,
                    onCheckedChange = { appPhoneRequired = it },
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(text = ctx.getString(R.string.developer_phone_required), color = BrewDim, fontSize = 12.sp)
            }

            // ── Listing（商品详情） ──
            Spacer(modifier = Modifier.height(16.dp))
            SectionLabel(ctx.getString(R.string.developer_listing_title), BrewCyan)
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = ctx.getString(R.string.developer_listing_hint), color = BrewDim, fontSize = 10.sp)
            Spacer(modifier = Modifier.height(8.dp))
            FieldLabel(ctx.getString(R.string.developer_listing_about))
            FormField(listingAbout, { listingAbout = it }, ctx.getString(R.string.developer_listing_about), singleLine = false, maxLines = 3)
            Spacer(modifier = Modifier.height(8.dp))
            FieldLabel(ctx.getString(R.string.developer_listing_description))
            FormField(listingDescriptionMarkdown, { listingDescriptionMarkdown = it }, ctx.getString(R.string.developer_listing_description), singleLine = false, maxLines = 6)

            // ── Release ──
            Spacer(modifier = Modifier.height(16.dp))
            Box(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                    .background(BrewPanel).border(1.dp, BrewBorder, RoundedCornerShape(8.dp))
                    .clickable { showReleaseSection = !showReleaseSection }
                    .padding(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(text = ctx.getString(R.string.developer_release).uppercase(), color = BrewDim, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                    Text(text = if (showReleaseSection) "▲" else "▼", color = BrewDim, fontSize = 12.sp)
                }
            }
            if (showReleaseSection) {
                Spacer(modifier = Modifier.height(8.dp))
                Row {
                    Column(Modifier.weight(1f)) {
                        FieldLabel(ctx.getString(R.string.developer_version))
                        FormField(releaseVersion, { releaseVersion = it }, ctx.getString(R.string.developer_version))
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        FieldLabel(ctx.getString(R.string.developer_release_date))
                        FormField(releaseDate, { releaseDate = it }, ctx.getString(R.string.developer_release_date))
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                FieldLabel(ctx.getString(R.string.developer_release_url))
                FormField(releaseSourceUrl, { releaseSourceUrl = it }, ctx.getString(R.string.developer_release_url))
                Spacer(modifier = Modifier.height(8.dp))
                FieldLabel(ctx.getString(R.string.developer_release_notes))
                FormField(releaseNotes, { releaseNotes = it }, ctx.getString(R.string.developer_release_notes), singleLine = false, maxLines = 3)
                Spacer(modifier = Modifier.height(8.dp))
                FieldLabel(ctx.getString(R.string.developer_release_changes))
                FormField(releaseChanges, { releaseChanges = it }, ctx.getString(R.string.developer_release_changes), singleLine = false, maxLines = 4)
            }
            } // ← 选填信息折叠结束

            // ══════════════════════════════════════════
            // 操作按钮
            // ══════════════════════════════════════════
            Spacer(modifier = Modifier.height(24.dp))
            BrutalButton(
                label = ctx.getString(R.string.developer_preview_json),
                color = BrewCyan,
                enabled = !submitting,
                onClick = { showJsonPreview = !showJsonPreview },
            )
            Spacer(modifier = Modifier.height(8.dp))
            BrutalButton(
                label = if (submitting) ctx.getString(R.string.developer_submitting) else ctx.getString(R.string.developer_submit),
                color = BrewWarning,
                enabled = !submitting,
                onClick = {
                    val errors = validateForm(
                        appId = appId, appName = appName, appVersion = appVersion,
                        appSummary = appSummary, artifactPackage1 = artifactPackage1,
                        artifactUrl1 = artifactUrl1, ctx = ctx,
                    )
                    validationErrors = errors
                    if (errors.isEmpty()) {
                        scope.launch {
                            submitAppToRegistry(
                                ctx = ctx,
                                appId = appId, appName = appName, appType = appType,
                                appCategory = appCategory, appVersion = appVersion,
                                appSummary = appSummary, appDescription = appDescription,
                                appAuthor = appAuthor, appSourceUrl = appSourceUrl,
                                appIconUrl = appIconUrl, appIconAsset = appIconAsset,
                                appPhoneRequired = appPhoneRequired,
                                appScreenshotUrls = appScreenshotUrls,
                                appScreenshotAssets = appScreenshotAssets,
                                listingAbout = listingAbout,
                                listingDescriptionMarkdown = listingDescriptionMarkdown,
                                releaseVersion = releaseVersion, releaseDate = releaseDate,
                                releaseSourceUrl = releaseSourceUrl,
                                releaseNotes = releaseNotes, releaseChanges = releaseChanges,
                                artifactTarget1 = artifactTarget1, artifactUrl1 = artifactUrl1,
                                artifactPackage1 = artifactPackage1,
                                artifactVersionCode1 = artifactVersionCode1,
                                artifactSha256_1 = artifactSha256_1, artifactSize1 = artifactSize1,
                                useSecondArtifact = showSecondArtifact,
                                artifactTarget2 = artifactTarget2, artifactUrl2 = artifactUrl2,
                                artifactPackage2 = artifactPackage2,
                                artifactVersionCode2 = artifactVersionCode2,
                                artifactSha256_2 = artifactSha256_2, artifactSize2 = artifactSize2,
                                onSubmitting = { submitting = it },
                                onResult = { msg, success ->
                                    submitResult = msg; isSuccess = success
                                    // 提交成功后保存表单状态
                                    saveDeveloperFormState(prefs.edit(),
                                        appId = appId, appName = appName, appType = appType,
                                        appCategory = appCategory, appVersion = appVersion,
                                        appSummary = appSummary, appDescription = appDescription,
                                        appAuthor = appAuthor, appSourceUrl = appSourceUrl,
                                        appIconUrl = appIconUrl, appIconAsset = appIconAsset,
                                        appPhoneRequired = appPhoneRequired,
                                        appScreenshotUrls = appScreenshotUrls,
                                        appScreenshotAssets = appScreenshotAssets,
                                        listingAbout = listingAbout,
                                        listingDescriptionMarkdown = listingDescriptionMarkdown,
                                        releaseVersion = releaseVersion, releaseDate = releaseDate,
                                        releaseSourceUrl = releaseSourceUrl,
                                        releaseNotes = releaseNotes, releaseChanges = releaseChanges,
                                        artifactTarget1 = artifactTarget1, artifactUrl1 = artifactUrl1,
                                        artifactPackage1 = artifactPackage1,
                                        artifactVersionCode1 = artifactVersionCode1,
                                        artifactSha256_1 = artifactSha256_1, artifactSize1 = artifactSize1,
                                        showSecondArtifact = showSecondArtifact,
                                        artifactTarget2 = artifactTarget2, artifactUrl2 = artifactUrl2,
                                        artifactPackage2 = artifactPackage2,
                                        artifactVersionCode2 = artifactVersionCode2,
                                        artifactSha256_2 = artifactSha256_2, artifactSize2 = artifactSize2,
                                    ).apply()
                                },
                            )
                        }
                    }
                },
            )

            // ── JSON 预览/编辑器 ──
            if (showJsonPreview) {
                Spacer(modifier = Modifier.height(12.dp))

                // 初始化编辑器内容
                LaunchedEffect(showJsonPreview) {
                    if (showJsonPreview && jsonEditorText.isBlank()) {
                        jsonEditorText = buildFullAppJson(
                            appId, appName, appType, appCategory, appVersion,
                            appSummary, appDescription, appAuthor, appSourceUrl,
                            appIconUrl, appIconAsset, appPhoneRequired, appScreenshotUrls, appScreenshotAssets,
                            listingAbout, listingDescriptionMarkdown,
                            releaseVersion, releaseDate, releaseSourceUrl, releaseNotes, releaseChanges,
                            artifactTarget1, artifactUrl1, artifactPackage1, artifactVersionCode1, artifactSha256_1, artifactSize1,
                            showSecondArtifact, artifactTarget2, artifactUrl2, artifactPackage2, artifactVersionCode2, artifactSha256_2, artifactSize2,
                        )
                    }
                }

                Box(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                        .background(BrewPanel).border(1.dp, BrewBorder, RoundedCornerShape(8.dp))
                        .padding(10.dp),
                ) {
                    androidx.compose.foundation.text.BasicTextField(
                        value = jsonEditorText,
                        onValueChange = { newText ->
                            jsonEditorText = newText
                            jsonEditorError = try {
                                JSONObject(newText)
                                null
                            } catch (e: Exception) {
                                "JSON 格式错误: ${e.message}"
                            }
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 150.dp),
                        singleLine = false,
                        maxLines = 30,
                        textStyle = androidx.compose.ui.text.TextStyle(color = BrewCyan, fontSize = 10.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, lineHeight = 15.sp),
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                if (jsonEditorError != null) {
                    Text(jsonEditorError!!, color = BrewWarning, fontSize = 11.sp, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
                }

                BrutalButton(
                    label = ctx.getString(R.string.developer_apply_json),
                    color = BrewSuccess,
                    enabled = jsonEditorText.isNotBlank() && jsonEditorError == null,
                    onClick = {
                        applyJsonToForm(
                            jsonStr = jsonEditorText,
                            ctx = ctx,
                            setAppId = { appId = it },
                            setAppName = { appName = it },
                            setAppType = { appType = it },
                            setAppCategory = { appCategory = it },
                            setAppVersion = { appVersion = it },
                            setAppSummary = { appSummary = it },
                            setAppDescription = { appDescription = it },
                            setAppAuthor = { appAuthor = it },
                            setAppSourceUrl = { appSourceUrl = it },
                            setAppIconUrl = { appIconUrl = it },
                            setAppIconAsset = { appIconAsset = it },
                            setAppPhoneRequired = { appPhoneRequired = it },
                            setAppScreenshotUrls = { appScreenshotUrls = it },
                            setAppScreenshotAssets = { appScreenshotAssets = it },
                            setListingAbout = { listingAbout = it },
                            setListingDescriptionMarkdown = { listingDescriptionMarkdown = it },
                            setReleaseVersion = { releaseVersion = it },
                            setReleaseDate = { releaseDate = it },
                            setReleaseSourceUrl = { releaseSourceUrl = it },
                            setReleaseNotes = { releaseNotes = it },
                            setReleaseChanges = { releaseChanges = it },
                            setArtifactTarget1 = { artifactTarget1 = it },
                            setArtifactUrl1 = { artifactUrl1 = it },
                            setArtifactPackage1 = { artifactPackage1 = it },
                            setArtifactVersionCode1 = { artifactVersionCode1 = it },
                            setArtifactSha256_1 = { artifactSha256_1 = it },
                            setArtifactSize1 = { artifactSize1 = it },
                            setArtifactTarget2 = { artifactTarget2 = it },
                            setArtifactUrl2 = { artifactUrl2 = it },
                            setArtifactPackage2 = { artifactPackage2 = it },
                            setArtifactVersionCode2 = { artifactVersionCode2 = it },
                            setArtifactSha256_2 = { artifactSha256_2 = it },
                            setArtifactSize2 = { artifactSize2 = it },
                            setShowSecondArtifact = { showSecondArtifact = it },
                            onResult = { msg -> submitResult = msg; isSuccess = false },
                        )
                    },
                )
            }

            Spacer(modifier = Modifier.height(48.dp))
        }
    }
}

// ============================================================
// 辅助组件
// ============================================================

@Composable
private fun SectionLabel(text: String, color: Color) {
    Text(text = text.uppercase(), color = color.copy(alpha = 0.8f), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
}

@Composable
private fun FieldLabel(label: String, required: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text = label, color = BrewDim, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        if (required) {
            Spacer(modifier = Modifier.width(2.dp))
            Text(text = "*", color = BrewWarning, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }
    Spacer(modifier = Modifier.height(4.dp))
}

@Composable
private fun FormField(
    value: String, onValueChange: (String) -> Unit, placeholder: String,
    modifier: Modifier = Modifier, singleLine: Boolean = true, maxLines: Int = 1,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    Box(
        modifier = modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
            .background(BrewPanel).border(1.dp, BrewBorder, RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = if (singleLine) 0.dp else 8.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        androidx.compose.foundation.text.BasicTextField(
            value = value, onValueChange = onValueChange,
            singleLine = singleLine, maxLines = maxLines,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            textStyle = androidx.compose.ui.text.TextStyle(color = BrewText, fontSize = 14.sp),
            decorationBox = { inner ->
                Box { if (value.isEmpty()) Text(text = placeholder, color = BrewDim, fontSize = 14.sp); inner() }
            },
        )
    }
}

@Composable
private fun DropdownSelector(label: String, selected: String, options: List<String>, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = modifier) {
        Box(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                .background(BrewPanel).border(1.dp, BrewBorder, RoundedCornerShape(8.dp))
                .clickable { expanded = !expanded }
                .padding(horizontal = 12.dp, vertical = 14.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(text = selected, color = BrewText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(text = if (expanded) "▲" else "▼", color = BrewDim, fontSize = 10.sp)
            }
        }
        if (expanded) {
            Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(BrewPanel).border(1.dp, BrewBorder, RoundedCornerShape(8.dp)).padding(4.dp)) {
                Column {
                    options.forEach { opt ->
                        val isSel = opt == selected
                        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                            .background(if (isSel) BrewCoral.copy(alpha = 0.2f) else Color.Transparent)
                            .clickable { onSelect(opt); expanded = false }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        ) {
                            Text(text = opt, color = if (isSel) BrewCoral else BrewText, fontSize = 13.sp, fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal)
                        }
                    }
                }
            }
        }
    }
}

// ============================================================
// 表单持久化
// ============================================================

private fun saveDeveloperFormState(
    editor: android.content.SharedPreferences.Editor,
    appId: String, appName: String, appType: String, appCategory: String, appVersion: String,
    appSummary: String, appDescription: String, appAuthor: String, appSourceUrl: String,
    appIconUrl: String, appIconAsset: String, appPhoneRequired: Boolean,
    appScreenshotUrls: String, appScreenshotAssets: String,
    listingAbout: String, listingDescriptionMarkdown: String,
    releaseVersion: String, releaseDate: String, releaseSourceUrl: String, releaseNotes: String, releaseChanges: String,
    artifactTarget1: String, artifactUrl1: String, artifactPackage1: String, artifactVersionCode1: String, artifactSha256_1: String, artifactSize1: String,
    showSecondArtifact: Boolean,
    artifactTarget2: String, artifactUrl2: String, artifactPackage2: String, artifactVersionCode2: String, artifactSha256_2: String, artifactSize2: String,
): android.content.SharedPreferences.Editor {
    editor.putString(PREFS_APP_ID, appId)
        .putString(PREFS_APP_NAME, appName)
        .putString(PREFS_APP_TYPE, appType)
        .putString(PREFS_APP_CATEGORY, appCategory)
        .putString(PREFS_APP_VERSION, appVersion)
        .putString(PREFS_APP_SUMMARY, appSummary)
        .putString(PREFS_APP_DESC, appDescription)
        .putString(PREFS_APP_AUTHOR, appAuthor)
        .putString(PREFS_APP_SOURCE_URL, appSourceUrl)
        .putString(PREFS_APP_ICON_URL, appIconUrl)
        .putString(PREFS_APP_ICON_ASSET, appIconAsset)
        .putBoolean(PREFS_APP_PHONE_REQUIRED, appPhoneRequired)
        .putString(PREFS_SCREENSHOT_URLS, appScreenshotUrls)
        .putString(PREFS_SCREENSHOT_ASSETS, appScreenshotAssets)
        .putString(PREFS_LISTING_ABOUT, listingAbout)
        .putString(PREFS_LISTING_DESC, listingDescriptionMarkdown)
        .putString(PREFS_RELEASE_VERSION, releaseVersion)
        .putString(PREFS_RELEASE_DATE, releaseDate)
        .putString(PREFS_RELEASE_URL, releaseSourceUrl)
        .putString(PREFS_RELEASE_NOTES, releaseNotes)
        .putString(PREFS_RELEASE_CHANGES, releaseChanges)
        .putString(PREFS_ARTIFACT_TARGET1, artifactTarget1)
        .putString(PREFS_ARTIFACT_URL1, artifactUrl1)
        .putString(PREFS_ARTIFACT_PKG1, artifactPackage1)
        .putString(PREFS_ARTIFACT_VC1, artifactVersionCode1)
        .putString(PREFS_ARTIFACT_SHA1, artifactSha256_1)
        .putString(PREFS_ARTIFACT_SIZE1, artifactSize1)
        .putBoolean(PREFS_SHOW_SECOND, showSecondArtifact)
        .putString(PREFS_ARTIFACT_TARGET2, artifactTarget2)
        .putString(PREFS_ARTIFACT_URL2, artifactUrl2)
        .putString(PREFS_ARTIFACT_PKG2, artifactPackage2)
        .putString(PREFS_ARTIFACT_VC2, artifactVersionCode2)
        .putString(PREFS_ARTIFACT_SHA2, artifactSha256_2)
        .putString(PREFS_ARTIFACT_SIZE2, artifactSize2)
    return editor
}

private fun loadDeveloperFormState(
    prefs: android.content.SharedPreferences,
    setAppId: (String) -> Unit,
    setAppName: (String) -> Unit,
    setAppType: (String) -> Unit,
    setAppCategory: (String) -> Unit,
    setAppVersion: (String) -> Unit,
    setAppSummary: (String) -> Unit,
    setAppDescription: (String) -> Unit,
    setAppAuthor: (String) -> Unit,
    setAppSourceUrl: (String) -> Unit,
    setAppIconUrl: (String) -> Unit,
    setAppIconAsset: (String) -> Unit,
    setAppPhoneRequired: (Boolean) -> Unit,
    setAppScreenshotUrls: (String) -> Unit,
    setAppScreenshotAssets: (String) -> Unit,
    setListingAbout: (String) -> Unit,
    setListingDescriptionMarkdown: (String) -> Unit,
    setReleaseVersion: (String) -> Unit,
    setReleaseDate: (String) -> Unit,
    setReleaseSourceUrl: (String) -> Unit,
    setReleaseNotes: (String) -> Unit,
    setReleaseChanges: (String) -> Unit,
    setArtifactTarget1: (String) -> Unit,
    setArtifactUrl1: (String) -> Unit,
    setArtifactPackage1: (String) -> Unit,
    setArtifactVersionCode1: (String) -> Unit,
    setArtifactSha256_1: (String) -> Unit,
    setArtifactSize1: (String) -> Unit,
    setArtifactTarget2: (String) -> Unit,
    setArtifactUrl2: (String) -> Unit,
    setArtifactPackage2: (String) -> Unit,
    setArtifactVersionCode2: (String) -> Unit,
    setArtifactSha256_2: (String) -> Unit,
    setArtifactSize2: (String) -> Unit,
    setShowSecondArtifact: (Boolean) -> Unit,
) {
    setAppId(prefs.getString(PREFS_APP_ID, "") ?: "")
    setAppName(prefs.getString(PREFS_APP_NAME, "") ?: "")
    setAppType(prefs.getString(PREFS_APP_TYPE, "glasses") ?: "glasses")
    setAppCategory(prefs.getString(PREFS_APP_CATEGORY, "Utility") ?: "Utility")
    setAppVersion(prefs.getString(PREFS_APP_VERSION, "1.0.0") ?: "1.0.0")
    setAppSummary(prefs.getString(PREFS_APP_SUMMARY, "") ?: "")
    setAppDescription(prefs.getString(PREFS_APP_DESC, "") ?: "")
    setAppAuthor(prefs.getString(PREFS_APP_AUTHOR, "") ?: "")
    setAppSourceUrl(prefs.getString(PREFS_APP_SOURCE_URL, "") ?: "")
    setAppIconUrl(prefs.getString(PREFS_APP_ICON_URL, "") ?: "")
    setAppIconAsset(prefs.getString(PREFS_APP_ICON_ASSET, "") ?: "")
    setAppPhoneRequired(prefs.getBoolean(PREFS_APP_PHONE_REQUIRED, false))
    setAppScreenshotUrls(prefs.getString(PREFS_SCREENSHOT_URLS, "") ?: "")
    setAppScreenshotAssets(prefs.getString(PREFS_SCREENSHOT_ASSETS, "") ?: "")
    setListingAbout(prefs.getString(PREFS_LISTING_ABOUT, "") ?: "")
    setListingDescriptionMarkdown(prefs.getString(PREFS_LISTING_DESC, "") ?: "")
    setReleaseVersion(prefs.getString(PREFS_RELEASE_VERSION, "") ?: "")
    setReleaseDate(prefs.getString(PREFS_RELEASE_DATE, "") ?: "")
    setReleaseSourceUrl(prefs.getString(PREFS_RELEASE_URL, "") ?: "")
    setReleaseNotes(prefs.getString(PREFS_RELEASE_NOTES, "") ?: "")
    setReleaseChanges(prefs.getString(PREFS_RELEASE_CHANGES, "") ?: "")
    setArtifactTarget1(prefs.getString(PREFS_ARTIFACT_TARGET1, "glasses") ?: "glasses")
    setArtifactUrl1(prefs.getString(PREFS_ARTIFACT_URL1, "") ?: "")
    setArtifactPackage1(prefs.getString(PREFS_ARTIFACT_PKG1, "") ?: "")
    setArtifactVersionCode1(prefs.getString(PREFS_ARTIFACT_VC1, "1") ?: "1")
    setArtifactSha256_1(prefs.getString(PREFS_ARTIFACT_SHA1, "") ?: "")
    setArtifactSize1(prefs.getString(PREFS_ARTIFACT_SIZE1, "") ?: "")
    setShowSecondArtifact(prefs.getBoolean(PREFS_SHOW_SECOND, false))
    setArtifactTarget2(prefs.getString(PREFS_ARTIFACT_TARGET2, "phone") ?: "phone")
    setArtifactUrl2(prefs.getString(PREFS_ARTIFACT_URL2, "") ?: "")
    setArtifactPackage2(prefs.getString(PREFS_ARTIFACT_PKG2, "") ?: "")
    setArtifactVersionCode2(prefs.getString(PREFS_ARTIFACT_VC2, "1") ?: "1")
    setArtifactSha256_2(prefs.getString(PREFS_ARTIFACT_SHA2, "") ?: "")
    setArtifactSize2(prefs.getString(PREFS_ARTIFACT_SIZE2, "") ?: "")
}

// ============================================================
// 逻辑函数
// ============================================================

private fun validateForm(
    appId: String, appName: String, appVersion: String, appSummary: String,
    artifactPackage1: String, artifactUrl1: String, ctx: Context,
): List<String> {
    val errors = mutableListOf<String>()
    if (appId.isBlank()) errors.add(ctx.getString(R.string.developer_error_app_id))
    if (appName.isBlank()) errors.add(ctx.getString(R.string.developer_error_app_name))
    if (appVersion.isBlank()) errors.add(ctx.getString(R.string.developer_error_version))
    if (appSummary.isBlank()) errors.add(ctx.getString(R.string.developer_error_summary))
    if (artifactPackage1.isBlank()) errors.add(ctx.getString(R.string.developer_error_package_name))
    if (artifactUrl1.isBlank()) errors.add(ctx.getString(R.string.developer_error_apk_url))
    if (!artifactUrl1.startsWith("http")) errors.add(ctx.getString(R.string.developer_error_url_format))
    return errors
}

/**
 * 生成完整的应用 JSON，匹配 BrewIndex.parse() 的所有解析字段
 */
private fun buildFullAppJson(
    appId: String, appName: String, appType: String, appCategory: String, appVersion: String,
    appSummary: String, appDescription: String, appAuthor: String, appSourceUrl: String,
    appIconUrl: String, appIconAsset: String, appPhoneRequired: Boolean,
    appScreenshotUrls: String, appScreenshotAssets: String,
    listingAbout: String, listingDescriptionMarkdown: String,
    releaseVersion: String, releaseDate: String, releaseSourceUrl: String, releaseNotes: String, releaseChanges: String,
    artifactTarget1: String, artifactUrl1: String, artifactPackage1: String, artifactVersionCode1: String, artifactSha256_1: String, artifactSize1: String,
    useSecondArtifact: Boolean, artifactTarget2: String, artifactUrl2: String, artifactPackage2: String, artifactVersionCode2: String, artifactSha256_2: String, artifactSize2: String,
): String {
    val app = JSONObject()

    // ── 核心字段 ──
    app.put("id", appId.ifBlank { "unknown" })
    app.put("name", appName.ifBlank { "Unnamed App" })
    app.put("type", appType)
    app.put("category", appCategory)
    app.put("version", appVersion.ifBlank { "1.0.0" })
    val summary = appSummary.ifBlank { "No description" }
    app.put("summary", summary)
    if (appDescription.isNotBlank() && appDescription != summary) app.put("description", appDescription)
    if (appAuthor.isNotBlank()) app.put("author", appAuthor)
    if (appSourceUrl.isNotBlank()) app.put("sourceUrl", appSourceUrl)

    // ── 图标 ──
    if (appIconUrl.isNotBlank()) app.put("iconUrl", appIconUrl)
    if (appIconAsset.isNotBlank()) app.put("iconAsset", appIconAsset)

    // ── 截屏 ──
    val screenshotAssets = appScreenshotAssets.split(",").map { it.trim() }.filter { it.isNotBlank() }
    if (screenshotAssets.isNotEmpty()) app.put("screenshotAssets", JSONArray(screenshotAssets))
    val screenshots = appScreenshotUrls.split(",").map { it.trim() }.filter { it.startsWith("http") }
    if (screenshots.isNotEmpty()) app.put("screenshotUrls", JSONArray(screenshots))

    // ── 标记 ──
    app.put("featured", false)
    app.put("phoneRequired", appPhoneRequired)
    // 自动填入发布日期，让应用显示为新应用
    java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US).apply {
        timeZone = java.util.TimeZone.getTimeZone("UTC")
        app.put("publishedAt", format(java.util.Date()))
    }

    // ── artifacts ──
    val artifacts = JSONArray()
    fun makeArtifact(target: String, url: String, pkg: String, vc: String, sha256: String, size: String) {
        val a = JSONObject()
        a.put("target", target)
        a.put("url", url)
        if (pkg.isNotBlank()) a.put("packageName", pkg)
        a.put("versionCode", vc.toIntOrNull() ?: 1)
        if (sha256.isNotBlank()) a.put("sha256", sha256)
        val sz = size.toLongOrNull()
        if (sz != null && sz > 0) a.put("sizeBytes", sz)
        artifacts.put(a)
    }
    makeArtifact(artifactTarget1, artifactUrl1, artifactPackage1, artifactVersionCode1, artifactSha256_1, artifactSize1)
    if (useSecondArtifact && artifactUrl2.isNotBlank()) {
        makeArtifact(artifactTarget2, artifactUrl2, artifactPackage2, artifactVersionCode2, artifactSha256_2, artifactSize2)
    }
    app.put("artifacts", artifacts)

    // ── listing ──
    if (listingAbout.isNotBlank() || listingDescriptionMarkdown.isNotBlank()) {
        val listing = JSONObject()
        if (listingAbout.isNotBlank()) listing.put("about", listingAbout)
        if (listingDescriptionMarkdown.isNotBlank()) listing.put("descriptionMarkdown", listingDescriptionMarkdown)
        app.put("listing", listing)
    }

    // ── releases ──
    if (releaseVersion.isNotBlank() || releaseDate.isNotBlank() || releaseNotes.isNotBlank()) {
        val rel = JSONObject()
        if (releaseVersion.isNotBlank()) rel.put("version", releaseVersion)
        if (releaseDate.isNotBlank()) rel.put("date", releaseDate)
        if (releaseSourceUrl.isNotBlank()) rel.put("sourceReleaseUrl", releaseSourceUrl)
        if (releaseNotes.isNotBlank()) rel.put("notes", releaseNotes)
        if (releaseChanges.isNotBlank()) {
            val changes = releaseChanges.lines().map { it.trim() }.filter { it.isNotBlank() }
            rel.put("changes", JSONArray(changes))
        }
        app.put("releases", JSONArray().put(rel))
    }

    return app.toString(2)
}

/**
 * 解析 JSON 并填充到表单的各个字段
 */
private fun applyJsonToForm(
    jsonStr: String,
    ctx: Context,
    setAppId: (String) -> Unit,
    setAppName: (String) -> Unit,
    setAppType: (String) -> Unit,
    setAppCategory: (String) -> Unit,
    setAppVersion: (String) -> Unit,
    setAppSummary: (String) -> Unit,
    setAppDescription: (String) -> Unit,
    setAppAuthor: (String) -> Unit,
    setAppSourceUrl: (String) -> Unit,
    setAppIconUrl: (String) -> Unit,
    setAppIconAsset: (String) -> Unit,
    setAppPhoneRequired: (Boolean) -> Unit,
    setAppScreenshotUrls: (String) -> Unit,
    setAppScreenshotAssets: (String) -> Unit,
    setListingAbout: (String) -> Unit,
    setListingDescriptionMarkdown: (String) -> Unit,
    setReleaseVersion: (String) -> Unit,
    setReleaseDate: (String) -> Unit,
    setReleaseSourceUrl: (String) -> Unit,
    setReleaseNotes: (String) -> Unit,
    setReleaseChanges: (String) -> Unit,
    setArtifactTarget1: (String) -> Unit,
    setArtifactUrl1: (String) -> Unit,
    setArtifactPackage1: (String) -> Unit,
    setArtifactVersionCode1: (String) -> Unit,
    setArtifactSha256_1: (String) -> Unit,
    setArtifactSize1: (String) -> Unit,
    setArtifactTarget2: (String) -> Unit,
    setArtifactUrl2: (String) -> Unit,
    setArtifactPackage2: (String) -> Unit,
    setArtifactVersionCode2: (String) -> Unit,
    setArtifactSha256_2: (String) -> Unit,
    setArtifactSize2: (String) -> Unit,
    setShowSecondArtifact: (Boolean) -> Unit,
    onResult: (String) -> Unit,
) {
    try {
        val app = JSONObject(jsonStr)

        // 如果是完整的 index JSON（包含 apps 数组），取第一个 app
        val target = if (app.has("apps")) {
            app.getJSONArray("apps").getJSONObject(0)
        } else {
            app
        }

        setAppId(target.optString("id", ""))
        setAppName(target.optString("name", ""))
        setAppType(target.optString("type", "glasses"))
        setAppCategory(target.optString("category", "Utility"))
        setAppVersion(target.optString("version", ""))
        setAppSummary(target.optString("summary", ""))
        setAppDescription(target.optString("description", ""))
        setAppAuthor(target.optString("author", ""))
        setAppSourceUrl(target.optString("sourceUrl", ""))
        setAppIconUrl(target.optString("iconUrl", ""))
        setAppIconAsset(target.optString("iconAsset", ""))
        setAppPhoneRequired(target.optBoolean("phoneRequired", false))

        // 截屏
        val su = target.optJSONArray("screenshotUrls")
        if (su != null) {
            val list = buildList { for (i in 0 until su.length()) su.optString(i).takeIf { it.isNotBlank() }?.let(::add) }
            setAppScreenshotUrls(list.joinToString(", "))
        }
        val sa = target.optJSONArray("screenshotAssets")
        if (sa != null) {
            val list = buildList { for (i in 0 until sa.length()) sa.optString(i).takeIf { it.isNotBlank() }?.let(::add) }
            setAppScreenshotAssets(list.joinToString(", "))
        }

        // listing
        val listing = target.optJSONObject("listing")
        if (listing != null) {
            setListingAbout(listing.optString("about", ""))
            setListingDescriptionMarkdown(listing.optString("descriptionMarkdown", ""))
        }

        // releases
        val rels = target.optJSONArray("releases")
        if (rels != null && rels.length() > 0) {
            val r = rels.getJSONObject(0)
            setReleaseVersion(r.optString("version", ""))
            setReleaseDate(r.optString("date", ""))
            setReleaseSourceUrl(r.optString("sourceReleaseUrl", ""))
            setReleaseNotes(r.optString("notes", ""))
            val changes = r.optJSONArray("changes")
            if (changes != null) {
                setReleaseChanges(buildList { for (i in 0 until changes.length()) changes.optString(i).takeIf { it.isNotBlank() }?.let(::add) }.joinToString("\n"))
            }
        }

        // artifacts
        val arts = target.optJSONArray("artifacts")
        if (arts != null && arts.length() > 0) {
            val a1 = arts.getJSONObject(0)
            setArtifactTarget1(a1.optString("target", "glasses"))
            setArtifactUrl1(a1.optString("url", ""))
            setArtifactPackage1(a1.optString("packageName", ""))
            setArtifactVersionCode1(a1.optLong("versionCode", 1).toString())
            setArtifactSha256_1(a1.optString("sha256", ""))
            setArtifactSize1(a1.optLong("sizeBytes", 0).let { if (it > 0) it.toString() else "" })

            if (arts.length() > 1) {
                val a2 = arts.getJSONObject(1)
                setShowSecondArtifact(true)
                setArtifactTarget2(a2.optString("target", "phone"))
                setArtifactUrl2(a2.optString("url", ""))
                setArtifactPackage2(a2.optString("packageName", ""))
                setArtifactVersionCode2(a2.optLong("versionCode", 1).toString())
                setArtifactSha256_2(a2.optString("sha256", ""))
                setArtifactSize2(a2.optLong("sizeBytes", 0).let { if (it > 0) it.toString() else "" })
            }
        }

        onResult(ctx.getString(R.string.developer_apply_json_ok))
    } catch (e: Exception) {
        onResult(ctx.getString(R.string.developer_error_invalid_json) + e.message)
    }
}

/**
 * 通过 Gitee API 直接修改 apps.v1.json 文件，追加新的应用条目
 */
private suspend fun submitAppToRegistry(
    ctx: Context,
    appId: String, appName: String, appType: String, appCategory: String, appVersion: String,
    appSummary: String, appDescription: String, appAuthor: String, appSourceUrl: String,
    appIconUrl: String, appIconAsset: String, appPhoneRequired: Boolean,
    appScreenshotUrls: String, appScreenshotAssets: String,
    listingAbout: String, listingDescriptionMarkdown: String,
    releaseVersion: String, releaseDate: String, releaseSourceUrl: String, releaseNotes: String, releaseChanges: String,
    artifactTarget1: String, artifactUrl1: String, artifactPackage1: String, artifactVersionCode1: String, artifactSha256_1: String, artifactSize1: String,
    useSecondArtifact: Boolean, artifactTarget2: String, artifactUrl2: String, artifactPackage2: String, artifactVersionCode2: String, artifactSha256_2: String, artifactSize2: String,
    onSubmitting: (Boolean) -> Unit, onResult: (String, Boolean) -> Unit,
) {
    if (GITEE_TOKEN == "YOUR_GITEE_TOKEN_HERE") {
        onResult(ctx.getString(R.string.developer_error_no_token), false)
        return
    }
    onSubmitting(true)
    withContext(Dispatchers.IO) {
        try {
            val fileUrl = "$GITEE_API_BASE/repos/$GITEE_OWNER/$GITEE_REPO/contents/$APPS_JSON_PATH?access_token=$GITEE_TOKEN"
            Log.i(TAG, "Fetching apps.v1.json from Gitee...")

            // Step 1: GET 当前文件
            val getConn = URL(fileUrl).openConnection() as HttpURLConnection
            getConn.requestMethod = "GET"
            getConn.connectTimeout = 15_000; getConn.readTimeout = 15_000
            if (getConn.responseCode !in 200..299) {
                val err = getConn.errorStream?.bufferedReader()?.readText() ?: "Unknown"
                getConn.disconnect()
                withContext(Dispatchers.Main) { onResult(ctx.getString(R.string.developer_submit_failed, getConn.responseCode, err), false) }
                return@withContext
            }
            val getBody = getConn.inputStream.bufferedReader().readText()
            getConn.disconnect()
            val getJson = JSONObject(getBody)
            val sha = getJson.getString("sha")
            val base64Content = getJson.getString("content").replace("\n", "").replace("\r", "")

            // Step 2: 解码，追加新应用
            val rawJson = Base64.getDecoder().decode(base64Content).toString(Charsets.UTF_8)
            val root = JSONObject(rawJson)
            val apps = root.getJSONArray("apps")
            for (i in 0 until apps.length()) {
                if (apps.getJSONObject(i).getString("id") == appId) {
                    withContext(Dispatchers.Main) { onResult(ctx.getString(R.string.developer_error_duplicate_id, appId), false) }
                    return@withContext
                }
            }
            val newAppStr = buildFullAppJson(
                appId, appName, appType, appCategory, appVersion,
                appSummary, appDescription, appAuthor, appSourceUrl,
                appIconUrl, appIconAsset, appPhoneRequired, appScreenshotUrls, appScreenshotAssets,
                listingAbout, listingDescriptionMarkdown,
                releaseVersion, releaseDate, releaseSourceUrl, releaseNotes, releaseChanges,
                artifactTarget1, artifactUrl1, artifactPackage1, artifactVersionCode1, artifactSha256_1, artifactSize1,
                useSecondArtifact, artifactTarget2, artifactUrl2, artifactPackage2, artifactVersionCode2, artifactSha256_2, artifactSize2,
            )
            apps.put(JSONObject(newAppStr))

            // Step 3: PUT 更新
            val newBase64 = Base64.getEncoder().encodeToString(root.toString(2).toByteArray(Charsets.UTF_8))
            val putBody = JSONObject().apply {
                put("message", "[Auto] Add app: $appName ($appId) v$appVersion")
                put("content", newBase64)
                put("sha", sha)
            }
            val putConn = URL(fileUrl).openConnection() as HttpURLConnection
            putConn.requestMethod = "PUT"
            putConn.doOutput = true
            putConn.setRequestProperty("Content-Type", "application/json;charset=UTF-8")
            putConn.connectTimeout = 15_000; putConn.readTimeout = 15_000
            OutputStreamWriter(putConn.outputStream).use { it.write(putBody.toString()) }
            val putCode = putConn.responseCode
            val putRes = if (putCode in 200..299) putConn.inputStream.bufferedReader().readText()
            else putConn.errorStream?.bufferedReader()?.readText() ?: "Unknown"
            putConn.disconnect()

            if (putCode in 200..299) {
                Log.i(TAG, "App submitted: $appName v$appVersion")
                withContext(Dispatchers.Main) { onResult(ctx.getString(R.string.developer_submit_success), true) }
            } else {
                Log.e(TAG, "PUT failed: $putCode - $putRes")
                withContext(Dispatchers.Main) { onResult(ctx.getString(R.string.developer_submit_failed, putCode, putRes), false) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Submit failed", e)
            withContext(Dispatchers.Main) { onResult(ctx.getString(R.string.developer_submit_error, e.message ?: e.javaClass.simpleName), false) }
        }
    }
    onSubmitting(false)
}

// ── BrutalButton（设置页同款，DeveloperScreen 专用）──
@Composable
internal fun BrutalButton(label: String, color: Color, enabled: Boolean = true, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.97f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "btn-press",
    )
    val dimAlpha = if (enabled) 1f else 0.45f
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .graphicsLayer { scaleX = pressScale; scaleY = pressScale; alpha = dimAlpha }
            .clip(RoundedCornerShape(12.dp))
            .background(color.copy(alpha = if (isPressed) 0.20f else if (enabled) 0.12f else 0.05f))
            .border(width = 1.dp, color = color.copy(alpha = if (enabled) 0.5f else 0.15f), shape = RoundedCornerShape(12.dp))
            .clickable(interactionSource = interactionSource, indication = null, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = color.copy(alpha = dimAlpha),
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp,
        )
    }
}
