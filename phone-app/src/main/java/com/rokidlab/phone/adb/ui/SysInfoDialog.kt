package com.rokidlab.phone.adb.ui

import com.rokidlab.phone.adb.AdbShellClient
import com.rokidlab.phone.design.*
import com.rokidlab.phone.R
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

@Composable
fun SysInfoDialog(
    client: AdbShellClient?,
    connected: Boolean,
    scope: kotlinx.coroutines.CoroutineScope,
    getOrConnect: ((AdbShellClient?) -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    AdbDialogContent(ctx.getString(R.string.system_info), BrewInfo, client, connected, scope, getOrConnect, onDismiss) { c ->
        var loading by remember { mutableStateOf(true) }
        var content by remember { mutableStateOf("") }
        
        LaunchedEffect(Unit) {
            loading = true
            scope.launch(Dispatchers.IO) {
                try {
                    val cmd = """getprop ro.product.manufacturer;getprop ro.product.model;getprop ro.build.version.release;getprop ro.build.version.sdk;getprop ro.serialno;getprop ro.product.board;getprop ro.build.display.id;dumpsys battery 2>/dev/null | grep -E "level:|AC powered:|health:";cat /proc/meminfo 2>/dev/null | grep MemTotal;df /data 2>/dev/null | tail -1"""
                    val raw = c.executeShellCommand(cmd)
                    val lines = raw.lines().map { it.trim() }.filter { it.isNotBlank() }
                    
                    val manufacturer = lines.getOrNull(0)?.takeIf { it.isNotEmpty() && !it.contains(":") } ?: "?"
                    val model = lines.getOrNull(1)?.takeIf { it.isNotEmpty() && !it.contains(":") } ?: "?"
                    val release = lines.getOrNull(2)?.takeIf { it.isNotEmpty() } ?: "?"
                    val sdk = lines.getOrNull(3)?.takeIf { it.isNotEmpty() } ?: "?"
                    val serial = lines.getOrNull(4)?.takeIf { it.isNotEmpty() } ?: "?"
                    val board = lines.getOrNull(5)?.takeIf { it.isNotEmpty() } ?: "?"
                    val build = lines.getOrNull(6)?.takeIf { it.isNotEmpty() } ?: "?"
                    
                    val level = lines.firstOrNull { it.contains("level:") }?.substringAfter(":")?.trim()?.let { "${it}%" } ?: "?"
                    val charging = lines.firstOrNull { it.contains("AC powered:") }?.let {
                        if (it.contains("true")) ctx.getString(R.string.charging) else ctx.getString(R.string.not_charging)
                    } ?: "?"
                    val health = lines.firstOrNull { it.contains("health:") }?.substringAfter(":")?.trim() ?: "?"
                    val memTotal = lines.firstOrNull { it.contains("MemTotal") }?.substringAfter(":")?.trim() ?: "?"
                    val storageParts = lines.firstOrNull { it.contains("/data") || it.startsWith("/dev") }?.split("\\s+".toRegex())
                    val stTotal = storageParts?.getOrNull(1)?.let { try { "${it.toLong() / 1024 / 1024}GB" } catch (_:Exception) { it } } ?: "?"
                    val stUsed = storageParts?.getOrNull(2)?.let { try { "${it.toLong() / 1024 / 1024}GB" } catch (_:Exception) { it } } ?: "?"
                    
                    withContext(Dispatchers.Main) {
                        content = buildString {
                            appendLine("━━━ Device Info ━━━")
                            appendLine("  Manufacturer: $manufacturer")
                            appendLine("  Model: $model")
                            appendLine("  OS: Android $release (API $sdk)")
                            appendLine("  Processor: $board")
                            appendLine("  Serial: $serial")
                            appendLine("  Build: $build")
                            appendLine("")
                            appendLine("━━━ Storage ━━━")
                            appendLine("  RAM: $memTotal")
                            if (stTotal != "?") appendLine("  Data: $stTotal total / $stUsed used")
                            appendLine("")
                            appendLine("━━━ Battery ━━━")
                            appendLine("  Level: $level")
                            appendLine("  Power: $charging")
                            appendLine("  Health: $health")
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) { content = "${ctx.getString(R.string.fetch_failed)}: ${e.message}" }
                }
                withContext(Dispatchers.Main) { loading = false }
            }
        }
        
        Box(
             Modifier.fillMaxWidth().heightIn(max = 500.dp)
                 .clip(BrewShapeSmall).background(BrewBg)
                 .padding(10.dp).verticalScroll(rememberScrollState()),
        ) {
            if (loading) {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(ctx.getString(R.string.fetching_sysinfo), color = BrewMuted, fontSize = 13.sp)
                }
            } else {
                Text(content, color = BrewText, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
        }
    }
}