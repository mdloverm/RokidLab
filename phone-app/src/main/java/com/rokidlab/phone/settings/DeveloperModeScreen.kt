package com.rokidlab.phone.settings

import com.rokidlab.phone.R
import com.rokidlab.phone.app.LabApplication
import com.rokidlab.phone.design.*
import com.rokidlab.phone.store.StoreActions
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 开发者模式：收纳仅开发/排障用的入口（主设置页保持简洁）。
 *
 * 当前条目：查看眼镜状态、当前线路（WiFi/蓝牙隧道探测）、提交应用到商店
 * （进入 [DeveloperScreen] 表单）、导出日志、导出兼容性诊断。
 *
 * ⚠️ 「本机执行环境」曾经长在这里（当时它没有正式入口，而"下载→解压→跑通 python"
 * 这条链必须在真机上验证过才算数）。链路验证通过后已迁到 [LocalExecScreen]，
 * 由 `SettingsScreen` 的「更多」区直达 —— **不要再往这里加用户可见的功能入口**：
 * 这一页的心智是"排障工具"，混进产品功能会让用户以为它是排障功能。
 */
@Composable
internal fun DeveloperModeScreen(
    onBack: () -> Unit,
    actions: StoreActions,
) {
    val ctx = LocalContext.current

    // 二级页：商店应用提交流程（原 DeveloperScreen 表单）
    var showSubmitScreen by remember { mutableStateOf(false) }
    if (showSubmitScreen) {
        DeveloperScreen(onBack = { showSubmitScreen = false })
        return
    }

    BackHandler(onBack = onBack)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BrewBg)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        ModuleHeader(
            title = ctx.getString(R.string.settings_developer_mode),
            subtitle = ctx.getString(R.string.settings_developer_mode_subtitle),
            color = BrewInfo,
        )
        Spacer(modifier = Modifier.height(24.dp))

        // 查看眼镜状态（在眼镜上打开 IP/ADB 状态页）
        SettingsCard(
            title = ctx.getString(R.string.view_glasses_status),
            content = ctx.getString(R.string.view_glasses_status_desc),
            color = BrewTeal,
            onClick = actions.onSettingsOpenGlassesStatus,
        )
        Spacer(modifier = Modifier.height(12.dp))

        // 当前线路（WiFi / 蓝牙隧道），点击强制重探
        val labApp = ctx.applicationContext as LabApplication
        val routeScope = rememberCoroutineScope()
        var routeText by remember {
            mutableStateOf(labApp.routeManager.lastRoute?.toString() ?: ctx.getString(R.string.route_unknown))
        }
        var routeProbing by remember { mutableStateOf(false) }
        SettingsCard(
            title = ctx.getString(R.string.route_current),
            content = if (routeProbing) ctx.getString(R.string.route_probing) else routeText,
            color = if (routeText.startsWith("WiFi")) BrewSuccess else BrewTeal,
            enabled = !routeProbing,
            onClick = {
                routeProbing = true
                routeScope.launch {
                    val r = withContext(Dispatchers.IO) {
                        labApp.routeManager.clearRouteCache()
                        labApp.routeManager.resolve(labApp.glassesIp, 5555)
                    }
                    routeText = r.toString()
                    routeProbing = false
                }
            },
        )
        Spacer(modifier = Modifier.height(12.dp))

        // 提交应用到商店注册表（表单页）
        SettingsCard(
            title = ctx.getString(R.string.developer_app_submit),
            content = ctx.getString(R.string.developer_subtitle),
            color = BrewCoral,
            onClick = { showSubmitScreen = true },
        )
        Spacer(modifier = Modifier.height(12.dp))

        // 导出日志
        SettingsCard(
            title = ctx.getString(R.string.export_log),
            content = ctx.getString(R.string.export_log_desc),
            color = BrewMagenta,
            onClick = actions.onExportLog,
        )
        Spacer(modifier = Modifier.height(12.dp))

        // 导出兼容性诊断
        SettingsCard(
            title = ctx.getString(R.string.export_compat_diag),
            content = ctx.getString(R.string.export_compat_diag_desc),
            color = BrewInfo,
            onClick = actions.onExportCompatDiagnostics,
        )
        Spacer(modifier = Modifier.height(48.dp))
    }
}
