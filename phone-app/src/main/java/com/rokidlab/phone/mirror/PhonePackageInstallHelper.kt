package com.rokidlab.phone.mirror

import com.rokidlab.phone.app.*
import com.rokidlab.phone.adb.*
import com.rokidlab.phone.design.*
import com.rokidlab.phone.filemanager.*
import com.rokidlab.phone.glasses.*
import com.rokidlab.phone.mirror.*
import com.rokidlab.phone.model.*
import com.rokidlab.phone.network.*
import com.rokidlab.phone.settings.*
import com.rokidlab.phone.store.*
import com.rokidlab.phone.util.*
import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import java.io.File

object PhonePackageInstallHelper {
    fun requestInstall(activity: Activity, apkFile: File, onStatus: (String) -> Unit): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !activity.packageManager.canRequestPackageInstalls()
        ) {
            onStatus("请允许从 RokidLab 安装应用，然后再次点击安装。")
            activity.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${activity.packageName}"),
                ),
            )
            return false
        }
        return startInstaller(activity, apkFile, onStatus)
    }

    fun requestUninstall(activity: Activity, packageName: String, appName: String, onStatus: (String) -> Unit): Boolean {
        return runCatching {
            activity.startActivity(
                Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName")),
            )
            onStatus("手机卸载页面已打开：$appName。")
        }.onFailure { error ->
            onStatus("手机卸载失败：${error.message ?: error.javaClass.simpleName}")
        }.isSuccess
    }

    private fun startInstaller(context: Context, apkFile: File, onStatus: (String) -> Unit): Boolean {
        return runCatching {
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
                .apply { setSize(apkFile.length()) }
            val sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                apkFile.inputStream().use { input ->
                    session.openWrite("package.apk", 0, apkFile.length()).use { output ->
                        input.copyTo(output)
                        session.fsync(output)
                    }
                }
                val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }
                val intent = PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    Intent(context, PhoneInstallResultReceiver::class.java),
                    flags,
                )
                session.commit(intent.intentSender)
            }
            onStatus("手机安装会话已提交。等待确认...")
        }.onFailure { error ->
            onStatus("手机 PackageInstaller 失败：${error.message ?: error.javaClass.simpleName}")
        }.isSuccess
    }
}
