package com.rokidbrew.phone

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.automirrored.outlined.ScreenShare
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

private const val COLLAPSED_APP_COUNT = 5

internal data class StoreUiState(
    val apps: List<BrewApp>,
    val busy: Boolean,
    val refreshing: Boolean,
    val selectedHostApp: RokidHostApp,
    val hostAppInstalled: Boolean,
    val cxrConnection: CxrConnectionState,
    val statusLines: List<String>,
    val statusExpanded: Boolean,
    val downloadProgress: Map<String, Int>,
    val phoneInstallStates: Map<String, MainActivity.InstallState>,
    val glassesInstallStates: Map<String, MainActivity.InstallState>,
    val selfUpdateState: BrewSelfUpdateState,
)

internal data class StoreActions(
    val onToggleStatus: () -> Unit,
    val onRefresh: () -> Unit,
    val onHostAppSelected: (RokidHostApp) -> Unit,
    val onAuthorize: () -> Unit,
    val onInstall: (BrewApp, String) -> Unit,
    val onCheckGlassesInstall: (BrewApp) -> Unit,
    val onUninstall: (BrewApp, String) -> Unit,
    val onSelfUpdate: () -> Unit,
    val onScreenMirror: () -> Unit,
    val onPhoneMirror: () -> Unit,
    val onFileManager: () -> Unit,
    val onSwitchMirror: () -> Unit,
    val onInstallApk: () -> Unit,
    val onLaunch: (BrewApp, String) -> Unit,
)

private data class StoreHomeViewState(
    val categoryFilter: String?,
    val query: String,
    val searchVisible: Boolean,
    val appListExpanded: Boolean,
    val showingFeaturedList: Boolean,
    val updateSheetVisible: Boolean,
    val selectedApp: BrewApp?,
)

private data class StoreHomeViewActions(
    val setCategoryFilter: (String?) -> Unit,
    val setQuery: (String) -> Unit,
    val setAppListExpanded: (Boolean) -> Unit,
    val setShowingFeaturedList: (Boolean) -> Unit,
    val setUpdateSheetVisible: (Boolean) -> Unit,
    val setSelectedApp: (BrewApp?) -> Unit,
    val toggleSearch: () -> Unit,
    val resetFilters: () -> Unit,
    val showFeaturedList: () -> Unit,
)

private data class StoreHomeLists(
    val visibleApps: List<BrewApp>,
    val categories: List<String>,
    val featuredApps: List<BrewApp>,
)

@Composable
internal fun BrewPhoneApp(
    state: StoreUiState,
    actions: StoreActions,
    iconLoader: IconLoader,
    mediaLoader: MediaLoader,
) {
    var categoryFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var searchVisible by rememberSaveable { mutableStateOf(false) }
    var appListExpanded by rememberSaveable { mutableStateOf(false) }
    var showingFeaturedList by rememberSaveable { mutableStateOf(false) }
    var updateSheetVisible by rememberSaveable { mutableStateOf(false) }
    var selectedApp by remember { mutableStateOf<BrewApp?>(null) }
    val listState = rememberLazyListState()
    val viewState = StoreHomeViewState(
        categoryFilter = categoryFilter,
        query = query,
        searchVisible = searchVisible,
        appListExpanded = appListExpanded,
        showingFeaturedList = showingFeaturedList,
        updateSheetVisible = updateSheetVisible,
        selectedApp = selectedApp,
    )
    val viewActions = StoreHomeViewActions(
        setCategoryFilter = { categoryFilter = it },
        setQuery = { query = it },
        setAppListExpanded = { appListExpanded = it },
        setShowingFeaturedList = { showingFeaturedList = it },
        setUpdateSheetVisible = { updateSheetVisible = it },
        setSelectedApp = { selectedApp = it },
        toggleSearch = {
            val nextVisible = !searchVisible
            searchVisible = nextVisible
            if (!nextVisible) {
                query = ""
                appListExpanded = false
            }
        },
        resetFilters = {
            categoryFilter = null
            query = ""
            searchVisible = false
            appListExpanded = false
        },
        showFeaturedList = {
            showingFeaturedList = true
            categoryFilter = null
            query = ""
            searchVisible = false
            appListExpanded = false
        },
    )

    BackHandler(enabled = viewState.updateSheetVisible || viewState.selectedApp != null || viewState.showingFeaturedList) {
        if (viewState.updateSheetVisible) {
            viewActions.setUpdateSheetVisible(false)
        } else if (viewState.selectedApp != null) {
            viewActions.setSelectedApp(null)
        } else {
            viewActions.setShowingFeaturedList(false)
        }
    }

    val visibleApps = remember(state.apps, viewState.categoryFilter, viewState.query) {
        state.apps.filter { app ->
            val categoryOk = viewState.categoryFilter == null ||
                (viewState.categoryFilter == NEW_CATEGORY && app.isNew) ||
                app.category.equals(viewState.categoryFilter, ignoreCase = true)
            val searchOk = viewState.query.isBlank() ||
                app.name.contains(viewState.query, ignoreCase = true) ||
                app.author.contains(viewState.query, ignoreCase = true) ||
                app.category.contains(viewState.query, ignoreCase = true) ||
                app.summary.contains(viewState.query, ignoreCase = true) ||
                app.description.contains(viewState.query, ignoreCase = true)
            categoryOk && searchOk
        }
    }
    val categories = remember(state.apps) {
        buildList {
            if (state.apps.any { it.isNew }) add(NEW_CATEGORY)
            addAll(state.apps.map { it.category }.distinct().sorted())
        }
    }
    val featuredApps = remember(state.apps) {
        state.apps.curatedHeroApps().ifEmpty { state.apps.take(6) }
    }
    val lists = StoreHomeLists(
        visibleApps = visibleApps,
        categories = categories,
        featuredApps = featuredApps,
    )

    LaunchedEffect(viewState.showingFeaturedList) {
        listState.scrollToItem(0)
    }

    LaunchedEffect(viewState.selectedApp?.id, state.cxrConnection.authorized) {
        viewState.selectedApp
            ?.takeIf { it.hasTarget("glasses") }
            ?.let(actions.onCheckGlassesInstall)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BrewBg)
            .background(
                Brush.radialGradient(
                    colors = listOf(BrewPanelHi.copy(alpha = 0.38f), Color.Transparent),
                    center = Offset(145f, 115f),
                    radius = 440f,
                ),
            ),
    ) {
        HomeListContent(
            listState = listState,
            state = state,
            actions = actions,
            viewState = viewState,
            viewActions = viewActions,
            lists = lists,
            iconLoader = iconLoader,
            mediaLoader = mediaLoader,
        )
        StoreOverlays(
            state = state,
            actions = actions,
            viewState = viewState,
            viewActions = viewActions,
            iconLoader = iconLoader,
            mediaLoader = mediaLoader,
        )
    }
}

@Composable
private fun HomeListContent(
    listState: LazyListState,
    state: StoreUiState,
    actions: StoreActions,
    viewState: StoreHomeViewState,
    viewActions: StoreHomeViewActions,
    lists: StoreHomeLists,
    iconLoader: IconLoader,
    mediaLoader: MediaLoader,
) {
    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 18.dp),
        contentPadding = PaddingValues(top = 14.dp, bottom = 132.dp),
    ) {
        if (viewState.showingFeaturedList) {
            item(key = "featured-page-header") {
                FeaturedListTopBar(
                    count = lists.featuredApps.size,
                    onBack = { viewActions.setShowingFeaturedList(false) },
                )
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
                onOpen = viewActions.setSelectedApp,
                onInstall = actions.onInstall,
                topPadding = 14,
            )
        } else {
            item(key = "header") {
                Header(
                    refreshing = state.refreshing,
                    searchActive = viewState.searchVisible || viewState.query.isNotBlank(),
                    onSearchToggle = viewActions.toggleSearch,
                    updateAvailable = state.selfUpdateState.available,
                    onUpdateOpen = { viewActions.setUpdateSheetVisible(true) },
                    onRefresh = actions.onRefresh,
                    onReset = viewActions.resetFilters,
                    onSwitchMirror = actions.onSwitchMirror,
                    onInstallApk = actions.onInstallApk,
                )
            }
            item(key = "search") {
                AnimatedVisibility(
                    visible = viewState.searchVisible || viewState.query.isNotBlank(),
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    SearchBar(
                        query = viewState.query,
                        onQueryChange = {
                            viewActions.setQuery(it)
                            viewActions.setCategoryFilter(null)
                            viewActions.setAppListExpanded(false)
                        },
                    )
                }
            }
            item(key = "connection") {
                ConnectionPanel(
                    selectedHostApp = state.selectedHostApp,
                    hostAppInstalled = state.hostAppInstalled,
                    cxrConnection = state.cxrConnection,
                    busy = state.busy,
                    onHostAppSelected = actions.onHostAppSelected,
                    onAuthorize = actions.onAuthorize,
                )
            }
            item(key = "screen-mirror") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(BrewPanel.copy(alpha = 0.88f))
                        .border(1.dp, BrewBorderHi.copy(alpha = 0.42f), RoundedCornerShape(14.dp))
                        .clickable(onClick = actions.onScreenMirror)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.AutoMirrored.Outlined.ScreenShare,
                        null,
                        tint = BrewGreen,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "屏幕镜像",
                        color = BrewTextBright,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.weight(1f))
                    Icon(
                        Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                        null,
                        tint = BrewMuted,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            item(key = "phone-mirror") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(BrewPanel.copy(alpha = 0.88f))
                        .border(1.dp, BrewBorderHi.copy(alpha = 0.42f), RoundedCornerShape(14.dp))
                        .clickable(onClick = actions.onPhoneMirror)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Outlined.CheckCircle,
                        null,
                        tint = BrewGreen,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "手机投屏",
                        color = BrewTextBright,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.weight(1f))
                    Icon(
                        Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                        null,
                        tint = BrewMuted,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            item(key = "file-manager") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(BrewPanel.copy(alpha = 0.88f))
                        .border(1.dp, BrewBorderHi.copy(alpha = 0.42f), RoundedCornerShape(14.dp))
                        .clickable(onClick = actions.onFileManager)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Outlined.FolderOpen,
                        null,
                        tint = BrewGreen,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "文件管理",
                        color = BrewTextBright,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.weight(1f))
                    Icon(
                        Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                        null,
                        tint = BrewMuted,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            if (lists.featuredApps.isNotEmpty()) {
                item(key = "featured") {
                    FeaturedShelf(
                        apps = lists.featuredApps.take(8),
                        iconLoader = iconLoader,
                        mediaLoader = mediaLoader,
                        onOpen = viewActions.setSelectedApp,
                        onViewAll = viewActions.showFeaturedList,
                    )
                }
            }
            item(key = "categories") {
                CategoryStrip(
                    categories = lists.categories,
                    selected = viewState.categoryFilter,
                    onSelect = {
                        viewActions.setCategoryFilter(it)
                        viewActions.setAppListExpanded(false)
                    },
                )
            }
            appListItems(
                apps = lists.visibleApps,
                expanded = viewState.appListExpanded,
                showToggle = true,
                iconLoader = iconLoader,
                mediaLoader = mediaLoader,
                busy = state.busy,
                progress = state.downloadProgress,
                phoneInstallStates = state.phoneInstallStates,
                glassesInstallStates = state.glassesInstallStates,
                onExpandedChange = viewActions.setAppListExpanded,
                onOpen = viewActions.setSelectedApp,
                onInstall = actions.onInstall,
                topPadding = 14,
            )
        }
    }
}

@Composable
private fun BoxScope.StoreOverlays(
    state: StoreUiState,
    actions: StoreActions,
    viewState: StoreHomeViewState,
    viewActions: StoreHomeViewActions,
    iconLoader: IconLoader,
    mediaLoader: MediaLoader,
) {
    StatusDock(
        statusLines = state.statusLines,
        expanded = state.statusExpanded,
        busy = state.busy,
        onToggle = actions.onToggleStatus,
        modifier = Modifier.align(Alignment.BottomCenter),
    )

    AnimatedVisibility(
        visible = viewState.selectedApp != null,
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        DetailSheet(
            app = viewState.selectedApp,
            busy = state.busy,
            progress = state.downloadProgress,
            iconLoader = iconLoader,
            mediaLoader = mediaLoader,
            phoneInstallStates = state.phoneInstallStates,
            glassesInstallStates = state.glassesInstallStates,
            statusLines = state.statusLines,
            statusExpanded = state.statusExpanded,
            onToggleStatus = actions.onToggleStatus,
            onDismiss = { viewActions.setSelectedApp(null) },
            onInstall = actions.onInstall,
            onUninstall = actions.onUninstall,
            onLaunch = actions.onLaunch,
        )
    }

    AnimatedVisibility(
        visible = viewState.updateSheetVisible,
        enter = fadeIn(),
        exit = fadeOut(),
    ) {
        UpdateSheet(
            state = state.selfUpdateState,
            statusLines = state.statusLines,
            statusExpanded = state.statusExpanded,
            onToggleStatus = actions.onToggleStatus,
            onDismiss = { viewActions.setUpdateSheetVisible(false) },
            onUpdate = actions.onSelfUpdate,
        )
    }
}

@Composable
internal fun FeaturedListTopBar(count: Int, onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(46.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.AutoMirrored.Outlined.ArrowBack,
                null,
                tint = BrewTextBright,
                modifier = Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(onClick = onBack)
                    .padding(3.dp),
            )
            Spacer(Modifier.width(14.dp))
            RokidBrewLogo(Modifier.size(34.dp))
            Spacer(Modifier.width(9.dp))
            BrandTitle(fontSize = 21)
            Spacer(Modifier.weight(1f))
            Text(
                "$count apps",
                color = BrewMuted,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
        Text(
            "精选",
            color = BrewTextBright,
            fontSize = 24.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 16.dp),
        )
    }
}

@Composable
internal fun CategoryStrip(categories: List<String>, selected: String?, onSelect: (String?) -> Unit) {
    if (categories.isEmpty()) return
    val sorted = remember(categories) { prioritizedCategories(categories) }
    val labels = remember(sorted) { listOf("全部") + sorted.displayCategories() }
    val originalMap = remember(sorted) { sorted.associateBy(::categoryDisplayName) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 14.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        labels.forEach { label ->
            val original = if (label == "全部") null else originalMap[label]
            val isSelected = if (label == "全部") selected == null else selected == original
            CategoryChip(label, isSelected) {
                onSelect(original)
            }
        }
    }
}
internal val CATEGORY_LABELS = mapOf(
    // 英文分类 -> 中文
    "AI" to "AI助手",
    "Navigation" to "导航",
    "Media" to "媒体",
    "Games" to "游戏",
    "Utilities" to "工具",
    "Utility" to "工具",
    "Productivity" to "效率",
    "Browser" to "浏览器",
    "Mobility" to "出行",
    "Communication" to "通讯",
    "Education" to "教育",
    "Entertainment" to "娱乐",
    "Health" to "健康",
    "Finance" to "财务",
    "Lifestyle" to "生活",
    "Shopping" to "购物",
    "Social" to "社交",
    "Sports" to "运动",
    "Travel" to "旅行",
    "Weather" to "天气",
    "Reference" to "参考",
    "Developer" to "开发",
    "System" to "系统",
    // 中文分类保持不变
    "工具" to "工具",
    "导航" to "导航",
    "媒体" to "媒体",
    "游戏" to "游戏",
    "效率" to "效率",
    "浏览器" to "浏览器",
    "出行" to "出行",
    "通讯" to "通讯",
    "教育" to "教育",
    "娱乐" to "娱乐",
    "健康" to "健康",
    "财务" to "财务",
    "生活" to "生活",
    "购物" to "购物",
    "社交" to "社交",
    "运动" to "运动",
    "旅行" to "旅行",
    "天气" to "天气",
    "参考" to "参考",
    "开发" to "开发",
    "系统" to "系统",
    "AI助手" to "AI助手",
    // 特殊分类
    NEW_CATEGORY to "新应用",
)
internal val CATEGORY_REVERSE = CATEGORY_LABELS.entries.associate { (k, v) -> v to k }
internal fun categoryDisplayName(category: String): String {
    return CATEGORY_LABELS[category] ?: category
}
internal fun prioritizedCategories(categories: List<String>): List<String> {
    val priority = listOf(
        NEW_CATEGORY,
        // AI 相关
        "AI", "AI助手",
        // 常用分类
        "Navigation", "导航",
        "Media", "媒体",
        "Games", "游戏",
        "Utilities", "Utility", "工具",
        "Productivity", "效率",
        "Browser", "浏览器",
        "Communication", "通讯",
        "Entertainment", "娱乐",
        "Education", "教育",
        "Health", "健康",
        "Lifestyle", "生活",
        "Mobility", "出行",
        "Travel", "旅行",
        "Finance", "财务",
        "Shopping", "购物",
        "Social", "社交",
        "Sports", "运动",
        "Weather", "天气",
        "Reference", "参考",
        "Developer", "开发",
        "System", "系统",
    )
    return (priority.filter { wanted -> categories.any { it.equals(wanted, ignoreCase = true) } }
        .map { wanted -> categories.first { it.equals(wanted, ignoreCase = true) } } +
        categories.filterNot { category -> priority.any { it.equals(category, ignoreCase = true) } })
        .distinct()
}
internal fun List<String>.displayCategories(): List<String> = map(::categoryDisplayName)
internal fun List<BrewApp>.curatedHeroApps(): List<BrewApp> {
    val newApps = filter { it.isNew }
        .sortedWith(compareByDescending<BrewApp> { it.publishedAt.orEmpty() }.thenBy { it.name.lowercase(Locale.US) })
    val featuredApps = filter { !it.isNew && it.isFeatured() }
        .sortedWith(compareBy<BrewApp> { it.featuredRank ?: Int.MAX_VALUE }.thenBy { it.name.lowercase(Locale.US) })
    return (newApps + featuredApps).distinctBy { it.id }
}
@Composable
internal fun FeaturedShelf(
    apps: List<BrewApp>,
    iconLoader: IconLoader,
    mediaLoader: MediaLoader,
    onOpen: (BrewApp) -> Unit,
    onViewAll: () -> Unit,
) {
    SectionHeader(
        title = "精选",
        action = "查看全部",
        onAction = onViewAll,
        modifier = Modifier.padding(top = 22.dp),
    )
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        itemsIndexed(
            items = apps,
            key = { _, app -> "featured-card:${app.id}" },
        ) { _, app ->
            FeaturedAppCard(
                app = app,
                iconLoader = iconLoader,
                mediaLoader = mediaLoader,
                onClick = { onOpen(app) },
            )
        }
    }
}
@Composable
internal fun FeaturedAppCard(
    app: BrewApp,
    iconLoader: IconLoader,
    mediaLoader: MediaLoader,
    onClick: () -> Unit,
) {
    val painter = rememberAppPainter(app = app, iconLoader = iconLoader, mediaLoader = mediaLoader, preferScreenshot = false)
    Column(
        modifier = Modifier
            .width(102.dp)
            .height(122.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(BrewPanel.copy(alpha = 0.82f))
            .border(1.dp, BrewBorderHi.copy(alpha = 0.38f), RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 9.dp),
    ) {
        Box(
            modifier = Modifier
                .size(50.dp)
                .align(Alignment.CenterHorizontally)
                .clip(RoundedCornerShape(12.dp))
                .background(BrewBg)
                .border(1.dp, BrewBorderHi.copy(alpha = 0.72f), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (painter != null) {
                Image(painter, app.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                FallbackVisual(app.name, Modifier.fillMaxSize())
            }
        }
        Text(
            app.name,
            color = BrewTextBright,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp),
        )
        Text(
            app.category,
            color = BrewMuted,
            fontSize = 11.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}
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
    topPadding: Int,
) {
    if (apps.isEmpty()) {
        item(key = "app-list-empty") {
            EmptyState(modifier = Modifier.padding(top = topPadding.dp))
        }
        return
    }
    val canExpand = showToggle && apps.size > COLLAPSED_APP_COUNT
    val visibleApps = if (canExpand && !expanded) apps.take(COLLAPSED_APP_COUNT) else apps
    itemsIndexed(
        items = visibleApps,
        key = { _, app -> "app-row:${app.id}" },
    ) { index, app ->
        AppListRowShell(
            index = index,
            itemCount = visibleApps.size,
            hasFooter = canExpand,
            topPadding = topPadding,
        ) {
                StoreAppRow(
                    app = app,
                    iconLoader = iconLoader,
                    mediaLoader = mediaLoader,
                    busy = busy,
                    progress = appProgress(app, progress),
                    phoneInstallStates = phoneInstallStates,
                    glassesInstallStates = glassesInstallStates,
                    onOpen = { onOpen(app) },
                    onInstall = onInstall,
                )
        }
    }
    if (canExpand) {
        item(key = "app-list-toggle") {
            AppListToggleFooter(
                expanded = expanded,
                onClick = { onExpandedChange(!expanded) },
            )
        }
    }
}

@Composable
private fun AppListRowShell(
    index: Int,
    itemCount: Int,
    hasFooter: Boolean,
    topPadding: Int,
    content: @Composable () -> Unit,
) {
    val shape = appListRowShape(index, itemCount, hasFooter)
    val roundedShape = index == 0 || (index == itemCount - 1 && !hasFooter)
    val surfaceModifier = if (roundedShape) {
        Modifier
            .clip(shape)
            .background(BrewPanel.copy(alpha = 0.80f))
            .border(1.dp, BrewBorderHi.copy(alpha = 0.38f), shape)
    } else {
        Modifier.background(BrewPanel.copy(alpha = 0.80f))
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = if (index == 0) topPadding.dp else 0.dp)
            .then(surfaceModifier)
            .padding(horizontal = 10.dp),
    ) {
        content()
        if (index != itemCount - 1 || hasFooter) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(BrewBorderHi.copy(alpha = 0.22f)),
            )
        }
    }
}

@Composable
private fun AppListToggleFooter(expanded: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(bottomStart = 14.dp, bottomEnd = 14.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(shape)
            .background(BrewPanel.copy(alpha = 0.80f))
            .border(1.dp, BrewBorderHi.copy(alpha = 0.38f), shape)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            if (expanded) "收起" else "展开更多",
            color = BrewGreen,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.width(6.dp))
        Icon(
            if (expanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
            null,
            tint = BrewGreen,
            modifier = Modifier.size(18.dp),
        )
    }
}

private fun appListRowShape(index: Int, itemCount: Int, hasFooter: Boolean): RoundedCornerShape {
    val top = if (index == 0) 14.dp else 0.dp
    val bottom = if (index == itemCount - 1 && !hasFooter) 14.dp else 0.dp
    return RoundedCornerShape(
        topStart = top,
        topEnd = top,
        bottomEnd = bottom,
        bottomStart = bottom,
    )
}

@Composable
internal fun StoreAppRow(
    app: BrewApp,
    iconLoader: IconLoader,
    mediaLoader: MediaLoader,
    busy: Boolean,
    progress: Int?,
    phoneInstallStates: Map<String, MainActivity.InstallState>,
    glassesInstallStates: Map<String, MainActivity.InstallState>,
    onOpen: () -> Unit,
    onInstall: (BrewApp, String) -> Unit,
) {
    val target = primaryInstallTarget(app)
    val phoneInstallState = phoneInstallStateFor(app, phoneInstallStates)
    val glassesInstallState = rememberGlassesInstallState(app, glassesInstallStates)
    val targetState = when (target) {
        "phone" -> phoneInstallState
        "glasses" -> glassesInstallState
        else -> MainActivity.InstallState.UNKNOWN
    }
    val artifact = target?.let(app::artifactFor)
    val actionLabel = when (targetState) {
        MainActivity.InstallState.INSTALLED -> "已安装"
        MainActivity.InstallState.INSTALLED_UNKNOWN_VERSION -> "最新版"
        MainActivity.InstallState.UPDATE_AVAILABLE -> "更新"
        else -> "安装"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(94.dp)
            .clickable(onClick = onOpen)
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(app, iconLoader, mediaLoader, Modifier.size(44.dp))
        Column(
            modifier = Modifier
                .padding(start = 11.dp)
                .weight(1f),
        ) {
            Text(
                app.name,
                color = BrewTextBright,
                fontSize = 13.2f.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                app.summary,
                color = BrewMuted,
                fontSize = 10.5f.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
            AppTargetTags(app, modifier = Modifier.padding(top = 4.dp))
        }
        Column(
            modifier = Modifier.width(58.dp),
            horizontalAlignment = Alignment.End,
        ) {
            Text(
                "v${app.version}",
                color = BrewText,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                formatBytes(artifact?.sizeBytes),
                color = BrewMuted,
                fontSize = 10.sp,
                maxLines = 1,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Spacer(Modifier.width(9.dp))
        if (progress != null) {
            CompactProgressLine(progress, Modifier.width(88.dp))
        } else if (targetState == MainActivity.InstallState.INSTALLED) {
            Row(
                modifier = Modifier.width(88.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Icon(Icons.Outlined.CheckCircle, null, tint = BrewGreen, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(5.dp))
                Text("已安装", color = BrewGreen, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        } else {
            StoreActionButton(
                label = actionLabel,
                primary = false,
                enabled = !busy && target != null,
                icon = {
                    Icon(
                        if (targetState == MainActivity.InstallState.UPDATE_AVAILABLE) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.Download,
                        null,
                        modifier = Modifier.size(16.dp),
                    )
                },
                onClick = { target?.let { onInstall(app, it) } },
                modifier = Modifier
                    .width(88.dp)
                    .height(34.dp),
            )
        }
    }
}
