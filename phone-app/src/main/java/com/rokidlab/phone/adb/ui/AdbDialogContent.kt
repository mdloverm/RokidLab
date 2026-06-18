package com.rokidlab.phone.adb.ui

import com.rokidlab.phone.adb.AdbShellClient
import com.rokidlab.phone.design.*
import com.rokidlab.phone.R
import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun AdbDialogContent(
    title: String,
    color: Color,
    client: AdbShellClient?,
    connected: Boolean,
    scope: kotlinx.coroutines.CoroutineScope,
    getOrConnect: ((AdbShellClient?) -> Unit) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    titleContent: (@Composable () -> Unit)? = null,
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
        titleColor = color,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        androidx.compose.foundation.layout.Column {
            when (status) {
                "connecting" -> {
                    androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            androidx.compose.material3.Text(ctx.getString(R.string.connecting_adb), color = color, fontSize = 14.sp)
                            androidx.compose.foundation.layout.Spacer(Modifier.height(8.dp))
                            androidx.compose.material3.Text(ctx.getString(R.string.connecting_adb_hint), color = BrewMuted, fontSize = 11.sp)
                        }
                    }
                }
                "error" -> {
                    androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            androidx.compose.material3.Text(ctx.getString(R.string.connection_failed_adb), color = BrewRed, fontSize = 14.sp)
                            androidx.compose.foundation.layout.Spacer(Modifier.height(4.dp))
                            androidx.compose.material3.Text(errorMsg, color = BrewMuted, fontSize = 11.sp)
                            androidx.compose.foundation.layout.Spacer(Modifier.height(12.dp))
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
                    val c = client ?: return@Column
                    content(c)
                }
            }
        }
    }
}