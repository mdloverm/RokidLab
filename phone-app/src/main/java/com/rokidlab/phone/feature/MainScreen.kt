package com.rokidlab.phone.feature

import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rokidlab.phone.R
import com.rokidlab.phone.app.*
import com.rokidlab.phone.design.*
import com.rokidlab.phone.mirror.PhoneMirrorService
import com.rokidlab.phone.mirror.ScreenMirrorActivity
import com.rokidlab.phone.model.*
import com.rokidlab.phone.settings.*
import com.rokidlab.phone.store.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.mutableStateOf

/**
 * 主页 Compose 编排（Phase 5：从 MainActivity.onCreate 的 setContent 块逐字迁出）。
 *
 * 以 MainActivity 扩展函数形式保留对宿主成员的裸引用（接收者即 Activity），
 * 业务方法与状态仍由宿主/各 feature 状态机提供，本文件只负责页面装配。
 */
@Composable
internal fun MainActivity.MainScreen() {
            RokidLabTheme {
                val hostAppInstalled by produceState(false, selectedHostApp, installCheckTick) {
                    value = withContext(Dispatchers.IO) { cxrL.isHostAppInstalled(selectedHostApp) }
                }
                BrewPhoneApp(
                    state = StoreUiState(
                        apps = apps,
                        busy = busy,
                        refreshing = refreshing,
                        selectedHostApp = selectedHostApp,
                        hostAppInstalled = hostAppInstalled,
                        cxrConnection = cxrConnection,
                        downloadProgress = downloadProgress,
                        phoneInstallStates = phoneInstallStates,
                        glassesInstallStates = glassesInstallStates,
                        selfUpdateState = selfUpdateState,
                        prerequisites = prerequisitesState,
                        screenMirrorState = screenMirrorState,
                        phoneMirrorState = phoneMirrorState,
                        fileManagerState = fileManagerState,
                        showMirrorDialog = showMirrorDialog,
                        currentMirrorIndex = BrewIndex.getMirrorIndex(this@MainScreen),
                        currentLocale = com.rokidlab.phone.util.LocalizationManager.getCurrentLocaleCode(),
                        isInstallingLocalApk = isInstallingLocalApk,
                        localApkInstallProgress = localApkInstallProgress,
                        localApkInstallStatus = localApkInstallStatus,
                    ),
                    actions = buildStoreActions(),
                    iconLoader = iconLoader,
                    mediaLoader = mediaLoader,
                    app = application as LabApplication,
                )
                if (showUpdatePrompt && selfUpdateState.available) {
                    UpdateDialog(
                        version = selfUpdateState.latestVersion.ifBlank { "latest" },
                        downloading = selfUpdateState.downloading,
                        downloadPercent = selfUpdateState.downloadPercent,
                        onUpdate = { performSelfUpdate() },
                        onDismiss = { showUpdatePrompt = false },
                        onCancelDownload = { cancelDownload("brew-self-update") },
                    )
                }
                if (showRefreshDialog) {
                    BrewDialog(
                        onDismiss = { showRefreshDialog = false },
                        title = if (refreshDialogSuccess) this@MainScreen.getString(R.string.refresh_success) else this@MainScreen.getString(R.string.refresh_failed),
                        color = if (refreshDialogSuccess) BrewSuccess else BrewWarning,
                    ) {
                        BrewDialogContent {
                            Text(refreshDialogMessage, color = BrewText, fontSize = 13.sp)
                        }
                    }
                }
                if (showErrorLogDialog) {
                    BrewDialog(
                        onDismiss = { showErrorLogDialog = false; errorLogSaved = false },
                        title = this@MainScreen.getString(R.string.error_log_title),
                        color = BrewWarning,
                    ) {
                        BrewDialogContent {
                            Text(this@MainScreen.getString(R.string.error_log_hint), color = BrewMuted, fontSize = 12.sp)
                            Spacer(Modifier.height(12.dp))
                            Column(
                                modifier = Modifier.verticalScroll(rememberScrollState()).weight(1f, fill = false).fillMaxWidth()
                            ) {
                                Text(errorLogContent, color = BrewText.copy(alpha = 0.7f), fontSize = 11.sp)
                            }
                            Spacer(Modifier.height(16.dp))
                            BrewButton(
                                text = if (errorLogSaved) this@MainScreen.getString(R.string.save_log_done) else this@MainScreen.getString(R.string.save_log),
                                onClick = {
                                    saveErrorLog()
                                },
                                modifier = Modifier.fillMaxWidth().height(44.dp),
                            )
                        }
                    }
                }
                if (showMirrorDialog) {
                    MirrorSourceDialog(
                        currentIndex = BrewIndex.getMirrorIndex(this@MainScreen),
                        onSelect = { index ->
                            switchMirror(index)
                            showMirrorDialog = false
                        },
                        onDismiss = { showMirrorDialog = false },
                    )
                }
            }
}
