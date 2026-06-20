package com.rokidlab.phone.adb.ui

import com.rokidlab.phone.adb.AdbShellClient
import com.rokidlab.phone.design.*
import com.rokidlab.phone.R
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
    AdbDialogContent(
        ctx.getString(R.string.system_info), BrewCoral, client, connected, scope, getOrConnect, onDismiss,
        icon = null,
        subtitle = ctx.getString(R.string.system_info_subtitle),
        height = Modifier.heightIn(max = 520.dp),
    ) { c ->
        data class SysData(
            val manufacturer: String = "?",
            val model: String = "?",
            val release: String = "?",
            val sdk: String = "?",
            val serial: String = "?",
            val board: String = "?",
            val build: String = "?",
            val level: String = "?",
            val charging: String = "?",
            val health: String = "?",
            val memTotal: String = "?",
            val stTotal: String = "?",
            val stUsed: String = "?",
        )
        var data by remember { mutableStateOf(SysData()) }
        var loading by remember { mutableStateOf(true) }
        var errorMsg by remember { mutableStateOf("") }

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
                        data = SysData(manufacturer, model, release, sdk, serial, board, build, level, charging, health, memTotal, stTotal, stUsed)
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) { errorMsg = "${ctx.getString(R.string.fetch_failed)}: ${e.message}" }
                }
                withContext(Dispatchers.Main) { loading = false }
            }
        }

        Box(Modifier.fillMaxWidth().fillMaxHeight().verticalScroll(rememberScrollState())) {
            if (loading) {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(ctx.getString(R.string.fetching_sysinfo), color = BrewMuted, fontSize = 13.sp)
                }
            } else if (errorMsg.isNotEmpty()) {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(errorMsg, color = BrewRed, fontSize = 13.sp)
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    InfoSection(ctx.getString(R.string.section_device_info), BrewTeal) {
                        InfoRow(ctx.getString(R.string.label_manufacturer), data.manufacturer)
                        InfoRow(ctx.getString(R.string.label_model), data.model)
                        InfoRow(ctx.getString(R.string.label_os), "Android ${data.release} (API ${data.sdk})")
                        InfoRow(ctx.getString(R.string.label_processor), data.board)
                        InfoRow(ctx.getString(R.string.label_serial), data.serial)
                        InfoRow(ctx.getString(R.string.label_build), data.build)
                    }
                    InfoSection(ctx.getString(R.string.section_storage), BrewCyan) {
                        InfoRow(ctx.getString(R.string.label_ram), data.memTotal)
                        if (data.stTotal != "?") InfoRow(ctx.getString(R.string.label_data), "${data.stTotal} total / ${data.stUsed} used")
                    }
                    InfoSection(ctx.getString(R.string.section_battery), BrewWarning) {
                        InfoRow(ctx.getString(R.string.label_level), data.level)
                        InfoRow(ctx.getString(R.string.label_power), data.charging)
                        InfoRow(ctx.getString(R.string.label_health), data.health)
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoSection(title: String, color: Color, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(color.copy(alpha = 0.06f))
            .border(1.dp, color.copy(alpha = 0.15f), RoundedCornerShape(10.dp))
            .padding(14.dp),
    ) {
        Text(title, color = color, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
        content()
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = BrewMuted, fontSize = 12.sp)
        Text(value, color = BrewTextBright, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}
