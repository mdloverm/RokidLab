package com.rokidlab.phone.glasses

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
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.painterResource
import com.rokidlab.phone.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun ConnectionPanel(
    selectedHostApp: RokidHostApp,
    hostAppInstalled: Boolean,
    cxrConnection: CxrConnectionState,
    busy: Boolean,
    onHostAppSelected: (RokidHostApp) -> Unit,
    onAuthorize: () -> Unit,
) {
    val hostVersion = rememberHostAppVersion(selectedHostApp)
    val connectionStatus = connectionStatus(hostAppInstalled, cxrConnection, busy)
    // 已连接庆祝脉冲
    val celebrateScale by animateFloatAsState(
        targetValue = if (cxrConnection.connected) 1.6f else 1f,
        animationSpec = tween(400),
        label = "celebrate-pulse",
    )
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = BrewPanel.copy(alpha = 0.78f)),
        border = BorderStroke(1.dp, BrewBorder.copy(alpha = 0.46f)),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 11.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Visibility, null, tint = BrewGreen, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text("眼镜连接", color = BrewTextBright, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .graphicsLayer { scaleX = celebrateScale; scaleY = celebrateScale; alpha = if (cxrConnection.connected) 0.7f + (celebrateScale - 1f) * 0.5f else 1f }
                        .clip(RoundedCornerShape(4.dp))
                        .background(connectionStatus.color),
                )
                Spacer(Modifier.width(6.dp))
                Text("CXR-L 链路", color = BrewMuted, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                Text(" / ", color = BrewMuted, fontSize = 11.sp)
                Text(
                    connectionStatus.label,
                    color = connectionStatus.color,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                )
            }
            HostAppPicker(
                selectedHostApp = selectedHostApp,
                enabled = !busy,
                onSelect = onHostAppSelected,
                modifier = Modifier.padding(top = 12.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HostAppBadge(selectedHostApp, Modifier.size(45.dp))
                Column(Modifier.padding(start = 11.dp).weight(1f)) {
                    Text(
                        selectedHostApp.label,
                        color = BrewTextBright,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                    Text(
                        selectedHostApp.packageName,
                        color = BrewMuted,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                    Row(
                        modifier = Modifier.padding(top = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Outlined.CheckCircle,
                            null,
                            tint = if (hostAppInstalled) BrewGreen else BrewWarning,
                            modifier = Modifier.size(15.dp),
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(
                            if (hostAppInstalled) "已安装" else "未安装",
                            color = if (hostAppInstalled) BrewGreen else BrewWarning,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        hostVersion?.let {
                            Text("  $it", color = BrewMuted, fontSize = 11.sp, maxLines = 1)
                        }
                    }
                }
                StoreActionButton(
                    label = "授权",
                    primary = false,
                    enabled = !busy,
                    icon = { Icon(Icons.Outlined.Lock, null, modifier = Modifier.size(16.dp)) },
                    onClick = onAuthorize,
                    modifier = Modifier
                        .width(122.dp)
                        .height(36.dp),
                )
            }
        }
    }
}
internal data class ConnectionStatus(val label: String, val color: Color)
internal fun connectionStatus(
    hostAppInstalled: Boolean,
    connection: CxrConnectionState,
    busy: Boolean,
): ConnectionStatus = when {
    !hostAppInstalled -> ConnectionStatus("未安装", BrewWarning)
    connection.connected -> ConnectionStatus("已连接", BrewCyan)
    busy && connection.authorized -> ConnectionStatus("连接中", BrewCyan)
    connection.connecting -> ConnectionStatus("连接中", BrewCyan)
    connection.authorized -> ConnectionStatus("已授权", BrewGreen)
    else -> ConnectionStatus("需要授权", BrewMuted)
}
@Composable
internal fun rememberHostAppVersion(hostApp: RokidHostApp): String? {
    val context = LocalContext.current
    val version by produceState<String?>(initialValue = null, hostApp) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.packageManager.getPackageInfo(hostApp.packageName, PackageManager.PackageInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    context.packageManager.getPackageInfo(hostApp.packageName, 0)
                }
                info.versionName?.let { "v$it" }
            }.getOrNull()
        }
    }
    return version
}
@Composable
internal fun rememberHostAppIcon(hostApp: RokidHostApp): Drawable? {
    val context = LocalContext.current
    val icon by produceState<Drawable?>(initialValue = null, hostApp) {
        value = withContext(Dispatchers.IO) {
            // 先尝试获取当前应用的图标
            val currentIcon = runCatching { context.packageManager.getApplicationIcon(hostApp.packageName) }.getOrNull()
            if (currentIcon != null) {
                currentIcon
            } else {
                // 如果当前应用未安装，尝试获取另一个应用的图标（两个应用图标相同）
                val otherPackage = if (hostApp == RokidHostApp.GLOBAL) {
                    RokidHostApp.CHINA.packageName
                } else {
                    RokidHostApp.GLOBAL.packageName
                }
                runCatching { context.packageManager.getApplicationIcon(otherPackage) }.getOrNull()
            }
        }
    }
    return icon
}
@Composable
internal fun HostAppBadge(hostApp: RokidHostApp, modifier: Modifier = Modifier) {
    val hostIcon = rememberDrawablePainter(rememberHostAppIcon(hostApp))
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(13.dp))
            .background(BrewPanel)
            .border(1.dp, BrewBorder.copy(alpha = 0.72f), RoundedCornerShape(13.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (hostIcon != null) {
            Image(
                painter = hostIcon,
                contentDescription = "${hostApp.label} icon",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(5.dp),
            )
        } else {
            Image(
                painter = painterResource(R.drawable.ic_rokid_default),
                contentDescription = "${hostApp.label} icon",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(5.dp),
            )
        }
    }
}
@Composable
internal fun HostAppPicker(
    selectedHostApp: RokidHostApp,
    enabled: Boolean,
    onSelect: (RokidHostApp) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(43.dp),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        RokidHostApp.values().forEach { hostApp ->
            HostAppSegment(
                hostApp = hostApp,
                selected = hostApp == selectedHostApp,
                enabled = enabled,
                modifier = Modifier.weight(1f),
                onClick = { onSelect(hostApp) },
            )
        }
    }
}
@Composable
internal fun HostAppSegment(
    hostApp: RokidHostApp,
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val hostIcon = rememberDrawablePainter(rememberHostAppIcon(hostApp))
    val color = when {
        selected -> BrewTextBright
        enabled -> BrewText
        else -> BrewDim
    }
    Row(
        modifier = modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(11.dp))
            .background(if (selected) BrewGreen.copy(alpha = 0.10f) else BrewPanelAlt.copy(alpha = 0.72f))
            .border(1.dp, if (selected) BrewGreen else BrewBorder.copy(alpha = 0.40f), RoundedCornerShape(11.dp))
            .clickable(enabled = enabled && !selected, onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Image(
            painter = hostIcon ?: painterResource(R.drawable.ic_rokid_default),
            contentDescription = "${hostApp.label} icon",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .size(20.dp)
                .clip(RoundedCornerShape(5.dp)),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            hostApp.label,
            color = color,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
