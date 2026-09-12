package com.rokidlab.phone.feature

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.rokidlab.phone.app.LabApplication
import com.rokidlab.phone.app.MainActivity
import com.rokidlab.phone.model.BrewIndex
import com.rokidlab.phone.model.GuideStep
import com.rokidlab.phone.store.StoreActions
import com.rokidlab.phone.util.LocalizationManager

/**
 * 商店页动作装配（Phase 5 三轮：从 MainActivity 逐字迁出）。
 *
 * 以扩展函数形式保留对宿主成员的裸引用（接收者即 MainActivity），
 * 与迁移前逻辑逐字一致；MainActivity 仅保留 `actions = buildStoreActions()`。
 * 需要宿主成员可见性为 internal（见 MainActivity 中同批翻转）。
 */
internal fun MainActivity.buildStoreActions(): StoreActions = StoreActions(
        onRefresh = { refreshStoreIndex(manual = true) },
        onHostAppSelected = ::selectRokidHostApp,
        onGoToGuideStep1 = { goToGuideStep1() },
        onAuthorize = { runWithPrerequisites { cxrL.requestAuthorization() } },
        onInstall = { app, target ->
            if (target == "glasses") {
                runWithPrerequisites { installArtifact(app, target) }
            } else {
                installArtifact(app, target)
            }
        },
        onCheckGlassesInstall = ::checkGlassesInstallStateIfNeeded,
        onUninstall = { app, target ->
            if (target == "glasses") {
                runWithPrerequisites { uninstallArtifact(app, target) }
            } else {
                uninstallArtifact(app, target)
            }
        },
        onSelfUpdate = { performSelfUpdate() },
        onSwitchMirror = { showMirrorDialog = true },
        onInstallApk = { apkPickerLauncher.launch("application/vnd.android.package-archive") },
        onLaunch = { app, target -> launchApp(app, target) },
        onSelectMirrorSource = { showMirrorDialog = true },
        onMirrorSelected = { index ->
            switchMirror(index)
            showMirrorDialog = false
        },
        onDismissMirrorDialog = { showMirrorDialog = false },
        onScreenMirrorIpChange = { (application as LabApplication).screenMirrorIp = it },
        onScreenMirrorConnect = { startScreenMirror() },
        onScreenMirrorStart = { startScreenMirror() },
        onScreenMirrorStop = { stopRokidLinkOnGlasses() },
        onScreenMirrorInstallRokidLink = { reinstallRokidLinkOnGlasses() },
        onScreenMirrorOpenRokidLink = { openRokidLinkOnGlasses() },
        onScreenMirrorRetry = { },
        onScreenMirrorBack = { },
        onPhoneMirrorIpChange = { (application as LabApplication).phoneMirrorIp = it },
        onPhoneMirrorPortChange = { (application as LabApplication).setPhoneMirrorPort(it) },
        onPhoneMirrorConnect = { startPhoneMirror() },
        onPhoneMirrorStart = { 
            if (phoneMirrorState.isMirroring) stopPhoneMirror() else startPhoneMirror()
        },
        onPhoneMirrorStop = { stopRokidLinkOnGlasses() },
        onPhoneMirrorInstallRokidLink = { reinstallRokidLinkOnGlasses() },
        onPhoneMirrorOpenRokidLink = { openRokidLinkOnGlasses() },
        onPhoneMirrorRetry = { },
        onPhoneMirrorBack = { },
        onFileManagerIpChange = { (application as LabApplication).fileManagerIp = it },
        onFileManagerConnect = { startFileManager() },
        onFileManagerDisconnect = { },
        onFileManagerStop = { stopRokidLinkOnGlasses() },
        onFileManagerInstallRokidLink = { reinstallRokidLinkOnGlasses() },
        onFileManagerOpenRokidLink = { openRokidLinkOnGlasses() },
        onFileManagerRetry = { },
        onFileManagerBack = { },
        onFileManagerNavigateTo = { },
        onFileManagerRefresh = { },
        onFileManagerUploadFile = { },
        onFileManagerDownloadFile = { },
        onFileManagerDeleteFile = { },
        onFileManagerCreateFolder = { },
        onFileManagerRenameFile = { _, _ -> },
        onCancelDownload = { key -> cancelDownload(key) },
        onExitApp = { finishAndRemoveTask() },
        onSettingsReinstallRokidLink = { reinstallRokidLinkOnGlasses() },
        // 排障入口：主动拉眼镜端状态页（WiFi IP / ADB）。常规流程不弹。
        onSettingsOpenGlassesStatus = { openRokidLinkOnGlasses() },
        onExportLog = { showExportLogDialog() },
        onExportCompatDiagnostics = { exportCompatDiagnostics() },
        onToggleKeepAlive = { toggleKeepAlive() },
        onLaunchGlassAppViaSdk = { pkg, activity ->
            cxrL.launchApp(
                packageName = pkg,
                activityClass = activity,
                onLaunchResult = { success ->
                    runOnUiThread {
                        val msg = if (success) "$pkg 启动成功" else "$pkg 启动失败"
                        log(msg)
                    }
                }
            )
        },
        onSendKeyButtonConfig = { shortPkg, shortActivity, longPkg, longActivity, onDone ->
            cxrL.sendKeyButtonConfig(
                shortPkg = shortPkg,
                shortActivity = shortActivity,
                longPkg = longPkg,
                longActivity = longActivity,
                onResult = { success ->
                    runOnUiThread {
                        val msg = if (success) "按键配置已发送到眼镜" else "按键配置发送失败"
                        log(msg)
                        onDone(success)
                    }
                },
            )
        },
        onSwitchLanguage = { code ->
            com.rokidlab.phone.util.LocalizationManager.setLocale(this, code)
            // Recreate activity to apply language
            recreate()
        },
        onSendWifiConfig = { ssid, password, onDone ->
            cxrL.sendWifiConfig(ssid, password) { success, errorMsg ->
                if (success) {
                    prerequisitesState = prerequisitesState.copy(wifiConfigured = true)
                    autoStartRokidLink()
                }
                onDone(success, errorMsg)
            }
        },
        onInstallLink = { onDone ->
            installRokidLinkForGuide { success ->
                if (success) {
                    prerequisitesState = prerequisitesState.copy(rokidLinkInstalled = true)
                }
                onDone(success)
            }
        },
        onSkipGuideStep = {
            when (prerequisitesState.currentGuideStep) {
                GuideStep.INSTALL_LINK -> {
                    prerequisitesState = prerequisitesState.copy(rokidLinkInstalled = true)
                }
                GuideStep.CONFIGURE_WIFI -> {
                    prerequisitesState = prerequisitesState.copy(wifiConfigured = true)
                    autoStartRokidLink()
                }
                else -> {
                    // 其他步骤不支持跳过
                }
            }
        },
)
