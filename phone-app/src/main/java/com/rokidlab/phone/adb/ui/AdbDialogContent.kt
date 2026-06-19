package com.rokidlab.phone.adb.ui

import com.rokidlab.phone.adb.AdbShellClient
import com.rokidlab.phone.design.*
import com.rokidlab.phone.R
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * ADB 弹窗统一容器 — RokidLink 卡片样式
 * 自动包裹彩色标题条 + 装饰分隔线 + 内容区
 */
@Composable
fun AdbDialogContent(
    title: String,
    color: Color,
    client: AdbShellClient?,
    connected: Boolean,
    scope: kotlinx.coroutines.CoroutineScope,
    getOrConnect: ((AdbShellClient?) -> Unit) -> Unit,
    onDismiss: () -> Unit,
    icon: String? = null,
    subtitle: String = "",
    height: Modifier = Modifier.heightIn(max = 600.dp),
    modifier: Modifier = Modifier,
    content: @Composable (AdbShellClient) -> Unit,
) {
    val ctx = LocalContext.current
    var status by remember { mutableStateOf(if (connected && client != null) "ready" else "connecting") }
    var errorMsg by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        if (connected && client != null) {
            status = "ready"
        } else {
            status = "connecting"
            getOrConnect { c ->
                if (c != null) {
                    status = "ready"
                } else {
                    status = "error"
                    errorMsg = ctx.getString(R.string.connection_failed_adb)
                }
            }
        }
    }

    BrewDialog(
        onDismiss = onDismiss,
        title = title,
        icon = icon,
        subtitle = subtitle.takeIf { it.isNotEmpty() },
        color = color,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        when (status) {
            "connecting" -> {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(ctx.getString(R.string.connecting_adb), color = color, fontSize = 14.sp)
                        Spacer(Modifier.height(8.dp))
                        Text(ctx.getString(R.string.connecting_adb_hint), color = BrewMuted, fontSize = 11.sp)
                    }
                }
            }
            "error" -> {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(ctx.getString(R.string.connection_failed_adb), color = BrewRed, fontSize = 14.sp)
                        Spacer(Modifier.height(4.dp))
                        Text(errorMsg, color = BrewMuted, fontSize = 11.sp)
                        Spacer(Modifier.height(12.dp))
                        BrewCompactButton(text = ctx.getString(R.string.retry), color = color, onClick = {
                            status = "connecting"
                            getOrConnect { c ->
                                if (c != null) { status = "ready" }
                                else { status = "error"; errorMsg = ctx.getString(R.string.connection_failed_adb) }
                            }
                        })
                    }
                }
            }
            "ready" -> {
                val c = client ?: return@BrewDialog
                Box(
                    modifier = modifier
                        .fillMaxWidth()
                        .then(height)
                        .padding(16.dp),
                ) {
                    content(c)
                }
            }
        }
    }
}
