package com.rokidlab.phone.feature

import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.rokidlab.phone.R
import com.rokidlab.phone.app.MainActivity
import com.rokidlab.phone.app.MainActivity.InstallState
import com.rokidlab.phone.app.MainActivity.InstallStateSource
import com.rokidlab.phone.mirror.PhonePackageInstallHelper
import com.rokidlab.phone.model.BrewApp
import com.rokidlab.phone.model.BrewArtifact
import com.rokidlab.phone.store.installStateFor
import com.rokidlab.phone.util.LogCollector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 商店安装状态域（Phase 5 二轮：从 MainActivity 逐字迁出，activity-handle 门面模式）。
 *
 * 持有眼镜/手机两端安装状态的 Compose 快照状态与刷新/安装/卸载/下载取消全链路；
 * MainActivity 保留同名属性访问器与方法门面，UI 与调用方零改动。
 * 依赖 MainActivity 的 apps/busy/cxrL/installCache/downloader 等宿主状态（internal）。
 */
internal class StoreInstallStateHolder(private val activity: MainActivity) {
    internal var installCheckTick by mutableStateOf(0)
    internal val downloadProgress = mutableStateMapOf<String, Int>()
    internal val downloadCancelJobs = mutableMapOf<String, Job>()
    internal val phoneInstallStates = mutableStateMapOf<String, InstallState>()
    internal val glassesInstallStates = mutableStateMapOf<String, InstallState>()
    internal val glassesInstallStateSources = mutableMapOf<String, InstallStateSource>()
    private var phoneInstallRefreshGeneration = 0
    private var glassesInstallRefreshGeneration = 0

    // ════════════════════════════════════════════════════════════════
    //  应用安装状态同步（眼镜 / 手机）
    // ════════════════════════════════════════════════════════════════
    internal fun checkGlassesInstallStateIfNeeded(app: BrewApp) {
        val artifact = app.artifactFor("glasses") ?: return
        val packageName = artifact.packageName?.takeIf { it.isNotBlank() } ?: return
        if (glassesInstallStateSources[packageName] == InstallStateSource.VERIFIED) return

        cachedGlassesInstallState(app, artifact)?.let { cachedState ->
            setGlassesInstallState(packageName, cachedState, InstallStateSource.CACHED)
        }

        if (activity.busy || !activity.cxrL.hasAuthorization()) return
        refreshGlassesInstallStates(listOf(app))
    }

    internal fun refreshCachedGlassesInstallStates(targetApps: List<BrewApp> = activity.apps) {
        val knownPackages = targetApps
            .mapNotNull { it.artifactFor("glasses")?.packageName?.takeIf(String::isNotBlank) }
            .toSet()
        glassesInstallStates.keys
            .filterNot(knownPackages::contains)
            .forEach(::removeGlassesInstallState)
        glassesInstallStateSources.keys
            .filterNot(knownPackages::contains)
            .forEach(glassesInstallStateSources::remove)

        targetApps.forEach { app ->
            val artifact = app.artifactFor("glasses") ?: return@forEach
            val packageName = artifact.packageName?.takeIf { it.isNotBlank() } ?: return@forEach
            if (glassesInstallStateSources[packageName] == InstallStateSource.VERIFIED) return@forEach
            cachedGlassesInstallState(app, artifact)?.let { state ->
                setGlassesInstallState(packageName, state, InstallStateSource.CACHED)
            }
        }
    }

    internal fun cachedGlassesInstallState(app: BrewApp, artifact: BrewArtifact): InstallState? {
        val packageName = artifact.packageName?.takeIf { it.isNotBlank() } ?: return null
        val record = activity.installCache.getGlasses(packageName) ?: return null
        if (!record.versionKnown) return InstallState.INSTALLED_UNKNOWN_VERSION
        val registryVersionCode = artifact.versionCode
        if (registryVersionCode != null && record.versionCode != null) {
            return if (record.versionCode < registryVersionCode) InstallState.UPDATE_AVAILABLE else InstallState.INSTALLED
        }
        val cachedVersionName = record.versionName?.takeIf { it.isNotBlank() }
        return if (cachedVersionName != null && cachedVersionName != app.version) {
            InstallState.UPDATE_AVAILABLE
        } else {
            InstallState.INSTALLED
        }
    }

    internal fun setGlassesInstallState(
        packageName: String,
        state: InstallState,
        source: InstallStateSource,
    ) {
        glassesInstallStates[packageName] = state
        glassesInstallStateSources[packageName] = source
    }

    internal fun removeGlassesInstallState(packageName: String) {
        glassesInstallStates.remove(packageName)
        glassesInstallStateSources.remove(packageName)
    }

    internal fun refreshGlassesInstallStates(targetApps: List<BrewApp> = activity.apps) {
        val appsByPackage = targetApps
            .mapNotNull { app ->
                val artifact = app.artifactFor("glasses") ?: return@mapNotNull null
                val packageName = artifact.packageName?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                packageName to (app to artifact)
            }
            .toMap()
        val packageNames = appsByPackage.keys.toList()
        if (packageNames.isEmpty() || !activity.cxrL.hasAuthorization()) return

        val generation = ++glassesInstallRefreshGeneration

        // 必须放 IO：queryInstalledApps 首段是 session.cleanup()（拆 ADB 共享会话 / 断 CXR 链路，
        // 全是阻塞 IO），而本方法由商店每个应用卡片的 checkGlassesInstallStateIfNeeded 在主线程
        // 触发 —— 眼镜离线时建链要等满 TCP + 握手超时，主线程 5s 内无法响应触摸 → 系统 ANR。
        activity.lifecycleScope.launch(Dispatchers.IO) {
            activity.cxrL.queryInstalledApps(
                packageNames = packageNames,
                onResult = { packageName, installed ->
                    if (generation != glassesInstallRefreshGeneration) return@queryInstalledApps
                    if (installed == null) {
                        // 查询失败：状态未知，保持既有判定，不误标为"未安装"（否则会诱导重复安装）
                        return@queryInstalledApps
                    }
                    val appAndArtifact = appsByPackage[packageName]
                    if (installed && appAndArtifact != null) {
                        val (app, artifact) = appAndArtifact
                        if (activity.installCache.getGlasses(packageName) == null) {
                            activity.installCache.recordGlassesDiscovered(app, artifact)
                        }
                        setGlassesInstallState(
                            packageName,
                            cachedGlassesInstallState(app, artifact) ?: InstallState.INSTALLED_UNKNOWN_VERSION,
                            InstallStateSource.VERIFIED,
                        )
                    } else {
                        activity.installCache.removeGlasses(packageName)
                        setGlassesInstallState(packageName, InstallState.NOT_INSTALLED, InstallStateSource.VERIFIED)
                    }
                    activity.runOnUiThread { installCheckTick += 1 }
                },
                onComplete = {
                    if (generation == glassesInstallRefreshGeneration) {
                        activity.log(activity.getString(R.string.log_glasses_install_refreshed))
                    }
                },
            )
        }
    }

    internal fun refreshPhoneInstallStates(targetApps: List<BrewApp> = activity.apps) {
        val artifacts = targetApps
            .mapNotNull { it.artifactFor("phone") }
            .filter { !it.packageName.isNullOrBlank() }
            .distinctBy { it.packageName }
        val generation = ++phoneInstallRefreshGeneration
        if (artifacts.isEmpty()) {
            phoneInstallStates.clear()
            return
        }

        activity.lifecycleScope.launch {
            val states = withContext(Dispatchers.IO) {
                artifacts.associate { artifact ->
                    artifact.packageName.orEmpty() to activity.installStateFor(artifact)
                }
            }
            if (generation != phoneInstallRefreshGeneration) return@launch
            val stalePackages = phoneInstallStates.keys.filterNot(states::containsKey)
            stalePackages.forEach(phoneInstallStates::remove)
            states.forEach { (packageName, state) ->
                if (phoneInstallStates[packageName] != state) {
                    phoneInstallStates[packageName] = state
                }
            }
        }
    }

    // ════════════════════════════════════════════════════════════════
    //  应用下载 / 启动 / 安装 / 卸载
    // ════════════════════════════════════════════════════════════════
    internal fun launchApp(app: BrewApp, target: String) {
        val artifact = app.artifactFor(target)
        val packageName = artifact?.packageName?.takeIf { it.isNotBlank() }
        if (packageName == null) {
            Toast.makeText(activity, activity.getString(R.string.cannot_get_package, app.name), Toast.LENGTH_SHORT).show()
            return
        }

        if (target == "glasses") {
            activity.runWithPrerequisites {
                // 前置检查留在主线程（可能拉起授权页）；重链路调用放 IO ——
                // launchApp 首段是 session.cleanup()（阻塞 IO），主线程同步调用会 ANR
                activity.lifecycleScope.launch(Dispatchers.IO) {
                    activity.cxrL.launchApp(packageName) { launched ->
                        if (launched) {
                            activity.log(activity.getString(R.string.log_launched_glasses, app.name))
                        } else {
                            activity.runOnUiThread {
                                Toast.makeText(activity, activity.getString(R.string.cannot_launch_glasses, app.name), Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            }
        } else {
            runCatching {
                val intent = activity.packageManager.getLaunchIntentForPackage(packageName)
                if (intent != null) {
                    activity.startActivity(intent)
                    activity.log(activity.getString(R.string.log_launched_phone, app.name))
                } else {
                    Toast.makeText(activity, activity.getString(R.string.app_not_installed_on_phone, app.name), Toast.LENGTH_SHORT).show()
                }
            }.onFailure { error ->
                Toast.makeText(activity, activity.getString(R.string.launch_failed, app.name, error.message), Toast.LENGTH_SHORT).show()
            }
        }
    }

    internal fun installArtifact(app: BrewApp, target: String) {
        val progressKey = "${app.id}:$target"
        // 同一应用重复点击：给反馈而非静默
        downloadCancelJobs[progressKey]?.let { existing ->
            if (existing.isActive) {
                Toast.makeText(activity, activity.getString(R.string.download_already_running, app.name), Toast.LENGTH_SHORT).show()
                return
            }
        }
        if (activity.busy) {
            // 全局有其他任务（如自检/推送）：提示等待，不再静默吞掉点击
            Toast.makeText(activity, activity.getString(R.string.task_in_progress), Toast.LENGTH_SHORT).show()
            return
        }
        val artifact = app.artifactFor(target)
        if (artifact == null) {
            Toast.makeText(activity, activity.getString(R.string.no_build_for_target, app.name, target), Toast.LENGTH_SHORT).show()
            return
        }
        if (target == "glasses" && !activity.cxrL.ensureGlassesOperationReady()) return

        var job: Job? = null
        // 必须放 IO：下载 APK（网络 + 落盘）与 cxrL.installApk（首段 session.cleanup() + APK 上传流）
        // 都是重阻塞操作，跑在 Main 派发器上会直接卡死主线程（下载期间整页无响应）。
        // 所有 UI 副作用（Toast / 弹窗 / 进度状态）均已回主线程。
        job = activity.lifecycleScope.launch(Dispatchers.IO) {
            activity.updateBusy(true)
            activity.runOnUiThread { downloadProgress[progressKey] = 0 }
            runCatching {
                val fileName = "${app.id}-${target}-${app.version}.apk"
                activity.log(activity.getString(R.string.log_downloading_apk, target, app.name))
                val file = activity.downloader.download(
                    artifact.url, fileName, artifact.sha256,
                    isCancelled = { job?.isActive == false },
                    onProgress = { progress ->
                        activity.runOnUiThread { downloadProgress[progressKey] = progress }
                        if (progress % 25 == 0) activity.log(activity.getString(R.string.log_download_progress, target, progress))
                    },
                )
                activity.runOnUiThread { downloadProgress[progressKey] = 100 }
                activity.log(activity.getString(R.string.log_downloaded_kb, file.name, file.length() / 1024))
                if (target == "glasses") {
                    // 传包名绕过 APK 头读取（兼容部分国产手机 getPackageArchiveInfo 返回 null）
                    val pkg = artifact.packageName?.takeIf { it.isNotBlank() }
                    if (pkg != null) {
                        activity.cxrL.installApk(file, pkg) { installed ->
                            if (installed) {
                                activity.installCache.recordGlassesInstall(app, artifact)
                                setGlassesInstallState(
                                    pkg,
                                    cachedGlassesInstallState(app, artifact) ?: InstallState.INSTALLED,
                                    InstallStateSource.VERIFIED,
                                )
                                activity.runOnUiThread {
                                    Toast.makeText(activity, activity.getString(R.string.install_success_toast, app.name), Toast.LENGTH_SHORT).show()
                                }
                            } else {
                                activity.installCache.removeGlasses(pkg)
                                setGlassesInstallState(pkg, InstallState.NOT_INSTALLED, InstallStateSource.VERIFIED)
                            }
                            activity.runOnUiThread { installCheckTick += 1 }
                            finishInstallTask(progressKey)
                        }
                    } else {
                        // 没有包名信息时降级到旧方式（从 APK 头读取）
                        activity.cxrL.installApk(file) { installed ->
                            if (installed) {
                                activity.runOnUiThread {
                                    Toast.makeText(activity, activity.getString(R.string.install_success_toast, app.name), Toast.LENGTH_SHORT).show()
                                }
                            }
                            finishInstallTask(progressKey)
                        }
                    }
                } else {
                    finishInstallTask(progressKey)
                    // 安装手机端 APK 必须回主线程（FileProvider + startActivity）
                    activity.runOnUiThread {
                        PhonePackageInstallHelper.requestInstall(activity, file, activity::log)
                    }
                }
            }.onFailure { error ->
                finishInstallTask(progressKey)
                if (error is CancellationException) {
                    // 用户主动取消：静默清理，不弹错误不触发日志导出
                    return@onFailure
                }
                activity.log(activity.getString(R.string.log_install_failed, error.message ?: error.javaClass.simpleName))
                LogCollector.e("Install", activity.getString(R.string.log_install_failed, error.message ?: error.javaClass.simpleName), error)
                activity.runOnUiThread {
                    Toast.makeText(activity, activity.getString(R.string.install_failed, app.name, error.message ?: error.javaClass.simpleName), Toast.LENGTH_LONG).show()
                    // 安装异常时自动弹出错误报告
                    activity.showExportLogDialog()
                }
            }
        }
        downloadCancelJobs[progressKey] = job
    }

    /**
     * 安装任务收尾：清下载进度、解除取消句柄、复位 busy。
     *
     * 三个收尾点（眼镜安装回调 / 手机分支 / 失败分支）共用；[downloadCancelJobs] 是普通
     * HashMap（非并发容器），统一在主线程改，避免与点击侧并发写入。
     */
    private fun finishInstallTask(progressKey: String) {
        activity.runOnUiThread {
            downloadProgress.remove(progressKey)
            downloadCancelJobs.remove(progressKey)
            activity.updateBusy(false)
        }
    }

    internal fun uninstallArtifact(app: BrewApp, target: String) {
        if (activity.busy) {
            Toast.makeText(activity, activity.getString(R.string.task_in_progress), Toast.LENGTH_SHORT).show()
            return
        }
        val artifact = app.artifactFor(target)
        val packageName = artifact?.packageName?.takeIf { it.isNotBlank() }
        if (artifact == null || packageName == null) {
            Toast.makeText(activity, activity.getString(R.string.no_package_for_target, app.name, target), Toast.LENGTH_SHORT).show()
            return
        }

        if (target == "glasses") {
            // 放 IO：uninstallApp 首段是 session.cleanup()（拆 ADB 会话 / 断 CXR 链路，阻塞 IO），
            // 主线程同步调用时眼镜离线会卡在建链上 → ANR
            activity.lifecycleScope.launch(Dispatchers.IO) {
                activity.cxrL.uninstallApp(packageName) { uninstalled ->
                    if (uninstalled) {
                        activity.installCache.removeGlasses(packageName)
                        setGlassesInstallState(packageName, InstallState.NOT_INSTALLED, InstallStateSource.VERIFIED)
                        activity.runOnUiThread {
                            installCheckTick += 1
                            Toast.makeText(activity, activity.getString(R.string.uninstall_success_toast, app.name), Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        activity.runOnUiThread { Toast.makeText(activity, activity.getString(R.string.uninstall_failed_toast, app.name), Toast.LENGTH_SHORT).show() }
                    }
                }
            }
        } else {
            PhonePackageInstallHelper.requestUninstall(activity, packageName, app.name, activity::log)
        }
    }

    internal fun updateBusy(value: Boolean) {
        activity.runOnUiThread {
            activity.busy = value
            if (!value) downloadProgress.clear()
        }
    }

    internal fun cancelDownload(key: String) {
        downloadCancelJobs[key]?.cancel()
        downloadCancelJobs.remove(key)
        downloadProgress.remove(key)
        if (key == "brew-self-update") {
            activity.selfUpdateState = activity.selfUpdateState.copy(downloading = false)
        }
        activity.log(activity.getString(R.string.log_download_cancelled, key))
    }
}
