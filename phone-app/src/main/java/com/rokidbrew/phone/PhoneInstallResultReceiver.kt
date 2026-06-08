package com.rokidbrew.phone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller

class PhoneInstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmIntent = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirmIntent != null) {
                    confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(confirmIntent)
                    broadcastStatus(context, "手机安装确认页面已打开。")
                } else {
                    broadcastStatus(context, "手机安装需要确认，但 Android 未返回安装器 Intent。")
                }
            }

            PackageInstaller.STATUS_SUCCESS -> {
                broadcastStatus(context, "手机安装成功。")
            }

            else -> {
                broadcastStatus(context, "手机安装失败：${message ?: "状态 $status"}")
            }
        }
    }

    private fun broadcastStatus(context: Context, message: String) {
        context.sendBroadcast(
            Intent(ACTION_PHONE_INSTALL_STATUS)
                .setPackage(context.packageName)
                .putExtra(EXTRA_MESSAGE, message),
        )
    }

    companion object {
        const val ACTION_PHONE_INSTALL_STATUS = "com.rokidbrew.phone.INSTALL_STATUS"
        const val EXTRA_MESSAGE = "message"
    }
}
