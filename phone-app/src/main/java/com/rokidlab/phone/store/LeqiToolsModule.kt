package com.rokidlab.phone.store

import com.rokidlab.phone.R
import com.rokidlab.phone.adb.AdbShellClient
import com.rokidlab.phone.app.LabApplication
import com.rokidlab.phone.design.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 乐奇工具模块
 *
 * 把原「双向投屏」「文件管理」「ADB 工具」三个独立页面合并为一页，减少底部导航项：
 *   - 投屏：屏幕镜像（眼镜 → 手机）/ 手机投屏（手机 → 眼镜，切换为"停止投屏"）
 *   - 文件：打开文件管理器 / 安装本地 APK
 *   - ADB：系统信息 / 应用管理 / 定时 / 按键 / Shell（内嵌 AdbToolsScreen）
 *
 * IP 输入框一律不显示：眼镜 IP 由 RokidLink 连 WiFi 后经 CXR 自动上报，
 * 写入 LabApplication 单一数据源，各工具内部按需自行读取。
 *
 * ADB 会话一律复用全 App 共享会话（`app.cxrL.getAdbShellClient()`），本页不自建：
 * 手机侧蓝牙栈对「同一设备 + 同一 SCN」只允许一条客户端 RFCOMM 通道，
 * 多开会互相挤断（详见 [getOrConnect] 注释）。
 */
@Composable
internal fun LeqiToolsModule(
    state: StoreUiState,
    actions: StoreActions,
    app: LabApplication,
) {
    val ctx = LocalContext.current
    val isCasting = state.phoneMirrorState.isMirroring
    val scope = rememberCoroutineScope()

    /**
     * 取得 ADB 会话 —— 复用全 App 唯一共享会话（`app.cxrL.getAdbShellClient()`）。
     *
     * ⚠️ 本页**不再自建会话，也不再缓存 client**，原因有二：
     *  1) 手机侧蓝牙栈对「同一设备 + 同一 SCN」只允许一条客户端 RFCOMM 通道。
     *     屏幕镜像 / 文件管理 / 手机投屏 / ASR 兜底轮询 / 定时任务全都挤在这条 SCN 上，
     *     谁后开谁把先开的挤断（实测：新会话建链成功后 1.5s 内，对端 Tunnel connection
     *     closed）。本页自建会话既会挤断它们，也会被它们挤断 —— 这正是
     *     「打开镜像正常，但 ADB 工具 / 投屏用着用着就不行了」的成因。
     *  2) 旧实现只在 `remember` 里存了 `client` + `connected` 布尔，用的时候从不校验存活。
     *     会话被挤死后，弹窗仍判定"已连接"直接进入就绪态，于是所有命令静默失效；
     *     只有离开本页（DisposableEffect 丢弃缓存）再回来才会重新建链 ——
     *     也就是「切到其他页面再退回来就好了」。
     *     共享会话自带 `isConnected()` 校验与失效自动重建，这两个问题一并消失。
     */
    fun getOrConnect(onConnected: (AdbShellClient?) -> Unit) {
        // getAdbShellClient() 内含同步阻塞握手，必须放后台线程
        scope.launch(Dispatchers.IO) {
            val c = runCatching {
                if (app.hasCxrL()) app.cxrL.getAdbShellClient() else null
            }.getOrNull()
            withContext(Dispatchers.Main) { onConnected(c) }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        ModuleHeader(
            title = ctx.getString(R.string.leqi_tools_title),
            subtitle = ctx.getString(R.string.leqi_tools_subtitle),
            color = BrewTeal,
        )
        Spacer(modifier = Modifier.height(24.dp))

        // ────────── 投屏 ──────────
        SectionLabel(ctx.getString(R.string.section_mirror), BrewCyan)

        // 屏幕镜像：进入子 Activity 全屏，按 ← / 返回键退出；子 Activity 期间该模块不可见
        BrutalButton(
            label = ctx.getString(R.string.start_mirror),
            color = BrewCyan,
            onClick = actions.onScreenMirrorStart,
        )
        Spacer(modifier = Modifier.height(8.dp))

        // 手机投屏：onPhoneMirrorStart 已是 toggle（点 1 次开始 / 再点 1 次停止）
        BrutalButton(
            label = if (isCasting) "■ ${ctx.getString(R.string.stop_mirror)}"
                    else ctx.getString(R.string.start_cast),
            color = if (isCasting) BrewRed else BrewPurple,
            onClick = actions.onPhoneMirrorStart,
        )
        if (isCasting && state.phoneMirrorState.connectionStatus.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = state.phoneMirrorState.connectionStatus,
                color = BrewMuted,
                fontSize = 12.sp,
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // ────────── 文件 ──────────
        SectionLabel(ctx.getString(R.string.section_file), BrewAmber)

        BrutalButton(
            label = ctx.getString(R.string.open_file_manager),
            color = BrewAmber,
            onClick = actions.onFileManagerConnect,
        )
        Spacer(modifier = Modifier.height(8.dp))

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
            enabled = !state.isInstallingLocalApk,
        )

        Spacer(modifier = Modifier.height(24.dp))

        // ────────── ADB 工具 ──────────
        SectionLabel(ctx.getString(R.string.section_adb), BrewTeal)

        com.rokidlab.phone.adb.ui.AdbToolsScreen(
            // 不在本页缓存 client：每次打开弹窗都走共享会话（存活时是本地即时校验，无额外耗时）
            client = null,
            connected = false,
            scope = scope,
            getOrConnect = { cb -> getOrConnect(cb) },
            onDisconnect = { },
            onLaunchAppViaSdk = actions.onLaunchGlassAppViaSdk,
            onSendKeyButtonConfig = actions.onSendKeyButtonConfig,
        )

        Spacer(modifier = Modifier.height(24.dp))
    }
}

/** 分组小标题（投屏 / 文件 / ADB 工具） */
@Composable
private fun SectionLabel(text: String, color: Color) {
    Text(
        text = text,
        color = color,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(bottom = 10.dp),
    )
}
