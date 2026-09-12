package com.rokidlab.phone.store

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
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.rokidlab.phone.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun ProgressLine(progress: Int, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(ctx.getString(R.string.downloading_text), color = BrewCoral, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text("$progress%", color = BrewCoral, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 5.dp)
                .height(5.dp)
                .clip(BrewShapeSmall)
                .background(BrewBorder),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth((progress.coerceIn(0, 100) / 100f).coerceAtLeast(0.02f))
                    .height(5.dp)
                    .background(BrewCoral),
            )
        }
    }
}

internal fun Context.installStateFor(artifact: BrewArtifact?): MainActivity.InstallState {
    val packageName = artifact?.packageName?.takeIf { it.isNotBlank() } ?: return MainActivity.InstallState.UNKNOWN
    val info = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getPackageInfo(packageName, 0)
        }
    }.getOrNull() ?: return MainActivity.InstallState.NOT_INSTALLED
    val installedVersion = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        info.longVersionCode
    } else {
        @Suppress("DEPRECATION")
        info.versionCode.toLong()
    }
    val registryVersion = artifact.versionCode ?: return MainActivity.InstallState.INSTALLED
    return if (installedVersion < registryVersion) {
        MainActivity.InstallState.UPDATE_AVAILABLE
    } else {
        MainActivity.InstallState.INSTALLED
    }
}

@Composable
internal fun rememberGlassesInstallState(
    app: BrewApp,
    glassesInstallStates: Map<String, MainActivity.InstallState>,
): MainActivity.InstallState {
    val packageName = app.artifactFor("glasses")?.packageName?.takeIf { it.isNotBlank() }
    return packageName?.let { glassesInstallStates[it] } ?: MainActivity.InstallState.UNKNOWN
}

internal fun phoneInstallStateFor(
    app: BrewApp,
    phoneInstallStates: Map<String, MainActivity.InstallState>,
): MainActivity.InstallState {
    val packageName = app.artifactFor("phone")?.packageName?.takeIf { it.isNotBlank() }
    return packageName?.let { phoneInstallStates[it] } ?: MainActivity.InstallState.UNKNOWN
}

internal fun installButtonLabel(ctx: android.content.Context, target: String, state: MainActivity.InstallState): String {
    return when (state) {
        MainActivity.InstallState.INSTALLED -> ctx.getString(R.string.installed_label)
        MainActivity.InstallState.INSTALLED_UNKNOWN_VERSION -> if (target == "glasses") ctx.getString(R.string.install_latest) else ctx.getString(R.string.install_target, target)
        MainActivity.InstallState.UPDATE_AVAILABLE -> ctx.getString(R.string.update_target, target)
        else -> ctx.getString(R.string.install_target, target)
    }
}

internal fun canUninstall(state: MainActivity.InstallState): Boolean {
    return state == MainActivity.InstallState.INSTALLED ||
        state == MainActivity.InstallState.INSTALLED_UNKNOWN_VERSION ||
        state == MainActivity.InstallState.UPDATE_AVAILABLE
}
