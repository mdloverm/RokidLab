package com.rokidlab.phone.glasses

import com.rokidlab.phone.R
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
                    broadcastStatus(context, context.getString(R.string.install_confirm_page_opened))
                } else {
                    broadcastStatus(context, context.getString(R.string.install_confirm_no_intent))
                }
            }

            PackageInstaller.STATUS_SUCCESS -> {
                broadcastStatus(context, context.getString(R.string.phone_install_success))
            }

            else -> {
                broadcastStatus(context, context.getString(R.string.phone_install_failed, message ?: "status $status"))
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
        const val ACTION_PHONE_INSTALL_STATUS = "com.rokidlab.phone.INSTALL_STATUS"
        const val EXTRA_MESSAGE = "message"
    }
}
