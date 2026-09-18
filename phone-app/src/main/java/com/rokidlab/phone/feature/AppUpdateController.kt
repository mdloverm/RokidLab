package com.rokidlab.phone.feature

import android.net.Uri
import android.util.Log
import androidx.lifecycle.lifecycleScope
import com.rokidlab.phone.R
import com.rokidlab.phone.app.MainActivity
import com.rokidlab.phone.mirror.PhonePackageInstallHelper
import com.rokidlab.phone.util.namedThread
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 应用更新域（Phase 5：本地 APK 安装 + RokidLab 自助更新从 MainActivity 逐字迁出）。
 *
 * activity-handle 模式：Activity 保留同名内部方法一行门面，状态字段（selfUpdateState /
 * isInstallingLocalApk / localApkInstallProgress / localApkInstallStatus / downloadProgress /
 * downloadCancelJobs）仍由 Activity 持有（Compose 直接读），本类只执行更新流程。
 */
internal class AppUpdateController(private val activity: MainActivity) {

    internal fun installLocalApkToGlasses(uri: Uri) {
        if (activity.busy || activity.isInstallingLocalApk) return
        activity.runWithPrerequisites {
            activity.lifecycleScope.launch {
                activity.isInstallingLocalApk = true
                activity.localApkInstallProgress = 0
                activity.localApkInstallStatus = activity.getString(R.string.preparing_install)
                activity.updateBusy(true)
                
                runCatching {
                    val input = activity.contentResolver.openInputStream(uri)
                        ?: throw IllegalStateException("Cannot open APK file")
                    
                    activity.localApkInstallStatus = activity.getString(R.string.downloading_apk)
                    activity.localApkInstallProgress = 20
                    
                    val tempFile = File(activity.cacheDir, "local_install_${System.currentTimeMillis()}.apk")
                    FileOutputStream(tempFile).use { output ->
                        input.copyTo(output)
                    }
                    input.close()
                    
                    activity.localApkInstallStatus = activity.getString(R.string.installing_apk)
                    activity.localApkInstallProgress = 50
                    
                    activity.log(activity.getString(R.string.installing_apk))
                    activity.cxrL.installApk(tempFile) { installed ->
                        activity.localApkInstallProgress = 90
                        if (installed) {
                            activity.log(activity.getString(R.string.install_completed))
                            activity.localApkInstallStatus = activity.getString(R.string.install_completed)
                            activity.localApkInstallProgress = 100
                        } else {
                            activity.log(activity.getString(R.string.install_failed_simple, "Unknown"))
                            activity.localApkInstallStatus = activity.getString(R.string.install_failed_simple, "Unknown")
                        }
                        tempFile.delete()
                        activity.updateBusy(false)
                        
                        scheduleClearLocalApkInstallState()
                    }
                }.onFailure { error ->
                    activity.log(activity.getString(R.string.apk_install_failed, error.message ?: error.javaClass.simpleName))
                    activity.localApkInstallStatus = activity.getString(R.string.apk_install_failed, error.message ?: error.javaClass.simpleName)
                    activity.updateBusy(false)
                    activity.isInstallingLocalApk = false
                }
            }
        }
    }

    /** 3 秒后清除本地 APK 安装状态（复位进度与状态文本） */
    internal fun scheduleClearLocalApkInstallState() {
        namedThread("local-apk-state-clear", start = true) {
            Thread.sleep(3000)
            activity.runOnUiThread {
                activity.isInstallingLocalApk = false
                activity.localApkInstallProgress = 0
                activity.localApkInstallStatus = ""
            }
        }
    }

    internal fun performSelfUpdate() {
        if (activity.selfUpdateState.downloading) return
        val url = activity.selfUpdateState.apkUrl
        if (url.isBlank()) {
            activity.log(activity.getString(R.string.log_update_failed_no_url))
            return
        }
        // 清理旧更新 APK
        runCatching { File(activity.cacheDir, "RokidLab-update.apk").delete() }
        activity.selfUpdateState = activity.selfUpdateState.copy(downloading = true, downloadPercent = 0)
        activity.downloadProgress["brew-self-update"] = 0
        val version = activity.selfUpdateState.latestVersion.ifBlank { "latest" }
        var job: Job? = null
        job = activity.lifecycleScope.launch {
            runCatching {
                activity.log(activity.getString(R.string.log_downloading_rokidlab, version))
                val file = activity.downloader.download(url, "RokidLab-update.apk", isCancelled = { job?.isActive == false }, onProgress = { percent ->
                    activity.runOnUiThread {
                        activity.downloadProgress["brew-self-update"] = percent
                        activity.selfUpdateState = activity.selfUpdateState.copy(downloadPercent = percent)
                    }
                })
                activity.downloadProgress["brew-self-update"] = 100
                activity.selfUpdateState = activity.selfUpdateState.copy(downloading = false, downloadPercent = 100)
                activity.log(activity.getString(R.string.log_downloaded_bytes, file.length()))
                val ok = withContext(Dispatchers.IO) {
                    PhonePackageInstallHelper.requestInstall(activity, file, activity::log)
                }
                if (!ok) {
                    activity.selfUpdateState = activity.selfUpdateState.copy(downloading = false)
                }
                activity.downloadProgress.remove("brew-self-update")
                activity.downloadCancelJobs.remove("brew-self-update")
            }.onFailure { error ->
                if (error is CancellationException) {
                    // 用户主动取消：静默清理，不弹错误
                    activity.downloadProgress.remove("brew-self-update")
                    activity.downloadCancelJobs.remove("brew-self-update")
                    activity.selfUpdateState = activity.selfUpdateState.copy(downloading = false)
                } else {
                    activity.log(activity.getString(R.string.log_update_failed, error.message ?: error.javaClass.simpleName))
                    activity.downloadProgress.remove("brew-self-update")
                    activity.downloadCancelJobs.remove("brew-self-update")
                    activity.selfUpdateState = activity.selfUpdateState.copy(downloading = false)
                }
            }
        }
        activity.downloadCancelJobs["brew-self-update"] = job
    }
}
