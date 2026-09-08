package com.rokidlab.phone.store

import com.rokidlab.phone.R
import com.rokidlab.phone.app.MainActivity
import com.rokidlab.phone.design.*
import com.rokidlab.phone.model.BrewApp
import com.rokidlab.phone.network.IconLoader
import com.rokidlab.phone.network.MediaLoader
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

private const val COLLAPSED_APP_COUNT = 5

internal data class StoreHomeLists(
    val featuredApps: List<BrewApp>,
    val visibleApps: List<BrewApp>,
    val categories: List<String>,
)

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
                    text = if (expanded) stringResource(R.string.app_list_show_less) else stringResource(R.string.app_list_show_all, apps.size),
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

// ===== StoreModule - 应用商店列表页 =====
@OptIn(ExperimentalMaterialApi::class)
@Composable
internal fun StoreModule(
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
    val isRefreshing = state.refreshing
    val pullRefreshState = rememberPullRefreshState(
        refreshing = isRefreshing,
        onRefresh = { actions.onRefresh() },
    )
    
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pullRefresh(pullRefreshState),
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
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                    ) {
                        lists.featuredApps.forEach { app ->
                            Box(
                                modifier = Modifier
                                    .width(132.dp)
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
                                val resId = when (category.lowercase(Locale.ROOT)) {
                                    "accessibility" -> R.string.category_accessibility
                                    "ai" -> R.string.category_ai
                                    "browser" -> R.string.category_browser
                                    "camera" -> R.string.category_camera
                                    "developer" -> R.string.category_developer
                                    "education" -> R.string.category_education
                                    "experiment" -> R.string.category_experiment
                                    "fitness" -> R.string.category_fitness
                                    "game" -> R.string.category_game
                                    "games" -> R.string.category_games
                                    "launcher" -> R.string.category_launcher
                                    "learning" -> R.string.category_learning
                                    "media" -> R.string.category_media
                                    "mobility" -> R.string.category_mobility
                                    "music" -> R.string.category_music
                                    "navigation" -> R.string.category_navigation
                                    "productivity" -> R.string.category_productivity
                                    "reader" -> R.string.category_reader
                                    "shopping" -> R.string.category_shopping
                                    "tool" -> R.string.category_tool
                                    "translation" -> R.string.category_translation
                                    "utility" -> R.string.category_utility
                                    else -> null
                                }
                                Text(
                                    text = if (resId != null) ctx.getString(resId) else category.uppercase(Locale.ROOT),
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
        
        PullRefreshIndicator(
            refreshing = isRefreshing,
            state = pullRefreshState,
            modifier = Modifier.align(Alignment.TopCenter),
        )
    }
}
