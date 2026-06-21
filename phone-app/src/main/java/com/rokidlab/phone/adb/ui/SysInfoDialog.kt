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
            val cpu: String = "?",
            val build: String = "?",
            val level: String = "?",
            val charging: String = "?",
            val health: String = "?",
            val memDisplay: String = "?",
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
                    val cmd = """getprop ro.product.manufacturer;getprop ro.product.model;getprop ro.build.version.release;getprop ro.build.version.sdk;getprop ro.serialno;getprop ro.soc.manufacturer;getprop ro.board.platform;cat /proc/cpuinfo 2>/dev/null | grep -iE "Hardware|Processor|model name" | head -1;cat /proc/cpuinfo 2>/dev/null | grep "CPU part" | head -1;getprop ro.build.version.incremental;dumpsys battery 2>/dev/null | grep -E "level:|AC powered:|USB powered:|Wireless powered:|health:";cat /proc/meminfo 2>/dev/null | grep -E "MemTotal|MemAvailable";df /data 2>/dev/null | tail -1"""
                    val raw = c.executeShellCommand(cmd)
                    val lines = raw.lines().map { it.trim() }.filter { it.isNotBlank() }
                    // first 7 lines are always the getprop results
                    val manufacturer = lines.getOrNull(0)?.takeIf { it.isNotEmpty() && !it.contains(":") } ?: "?"
                    val model = lines.getOrNull(1)?.takeIf { it.isNotEmpty() && !it.contains(":") } ?: "?"
                    val release = lines.getOrNull(2)?.takeIf { it.isNotEmpty() } ?: "?"
                    val sdk = lines.getOrNull(3)?.takeIf { it.isNotEmpty() } ?: "?"
                    val serial = lines.getOrNull(4)?.takeIf { it.isNotEmpty() } ?: "?"
                    val socMfrRaw = lines.getOrNull(5)?.takeIf { it.isNotEmpty() && !it.contains(":") } ?: ""
                    val socMfr = normalizeSocManufacturer(socMfrRaw)
                    val platform = lines.getOrNull(6)?.takeIf { it.isNotEmpty() && !it.contains(":") } ?: ""
                    // CPU: prefer Hardware/Processor/model_name from /proc/cpuinfo, fallback to CPU part decoding
                    val cpuPart = lines.firstOrNull { l -> l.contains("Hardware") || l.contains("Processor") || l.contains("model name") }
                        ?.substringAfter(":")?.trim()
                        ?: lines.firstOrNull { it.contains("CPU part") }
                            ?.substringAfter(":")?.trim()
                            ?.let { decodeArmCpuPart(it) }
                        ?: ""
                    val cpu = if (socMfr.isNotEmpty() || platform.isNotEmpty() || cpuPart.isNotEmpty()) {
                        listOfNotNull(socMfr.ifEmpty { null }, listOfNotNull(platform.ifEmpty { null }, cpuPart.ifEmpty { null }).joinToString(" / ").ifEmpty { null })
                            .joinToString(" ") { if (it.contains(" / ")) "($it)" else it }
                    } else "?"
                    // Build: first line after basic props that doesn't look like a cpuinfo or battery/mem/disk line
                    val build = lines.drop(7).firstOrNull { l ->
                        !l.contains(":") && !l.startsWith("processor") && !l.startsWith("Bogo") &&
                        !l.contains("Features") && !l.startsWith("CPU") && !l.startsWith("Hardware") &&
                        !l.startsWith("model name") && !l.contains("MemTotal") &&
                        !l.startsWith("/dev") && !l.contains("/data")
                    } ?: "?"
                    val level = lines.firstOrNull { it.contains("level:") }?.substringAfter(":")?.trim()?.let { "${it}%" } ?: "?"
                    val charging = lines.firstOrNull { it.contains(" powered: true") }?.let {
                        ctx.getString(R.string.charging)
                    } ?: ctx.getString(R.string.not_charging)
                    val health = decodeBatteryHealth(lines.firstOrNull { it.contains("health:") }?.substringAfter(":")?.trim(), ctx)
                    val memTotalKb = lines.firstOrNull { it.contains("MemTotal") }?.substringAfter(":")?.trim()?.let { parseKb(it) } ?: 0L
                    val memAvailKb = lines.firstOrNull { it.contains("MemAvailable") }?.substringAfter(":")?.trim()?.let { parseKb(it) } ?: 0L
                    val memUsedKb = if (memTotalKb >= memAvailKb) memTotalKb - memAvailKb else 0L
                    val memDisplay = if (memTotalKb > 0) String.format(ctx.getString(R.string.storage_ram_format), formatSizeFromKb(memUsedKb), formatSizeFromKb(memTotalKb)) else "?"
                    val storageParts = lines.firstOrNull { it.contains("/data") || it.startsWith("/dev") }?.split("\\s+".toRegex())
                    val stTotal = storageParts?.getOrNull(1)?.let { formatStorage(it) } ?: "?"
                    val stUsed = storageParts?.getOrNull(2)?.let { formatStorage(it) } ?: "?"
                    withContext(Dispatchers.Main) {
                        data = SysData(manufacturer, model, release, sdk, serial, cpu, build, level, charging, health, memDisplay, stTotal, stUsed)
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
                        InfoRow(ctx.getString(R.string.label_processor), data.cpu)
                        InfoRow(ctx.getString(R.string.label_serial), data.serial)
                        InfoRow(ctx.getString(R.string.label_build), data.build)
                    }
                    InfoSection(ctx.getString(R.string.section_storage), BrewCyan) {
                        InfoRow(ctx.getString(R.string.label_ram), data.memDisplay)
                        if (data.stTotal != "?") InfoRow(ctx.getString(R.string.label_data), String.format(ctx.getString(R.string.storage_data_format), data.stUsed, data.stTotal))
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

/**
 * Decode battery health code from dumpsys battery into a localized string.
 * dumpsys battery health codes: 1=Unknown, 2=Good, 3=Overheat, 4=Dead,
 * 5=Over voltage, 6=Unspecified failure, 7=Cold
 */
private fun decodeBatteryHealth(raw: String?, ctx: android.content.Context): String {
    if (raw == null) return "?"
    return when (raw.trim()) {
        "1" -> ctx.getString(R.string.battery_health_unknown)
        "2" -> ctx.getString(R.string.battery_health_good)
        "3" -> ctx.getString(R.string.battery_health_overheat)
        "4" -> ctx.getString(R.string.battery_health_dead)
        "5" -> ctx.getString(R.string.battery_health_overvoltage)
        "6" -> ctx.getString(R.string.battery_health_failure)
        "7" -> ctx.getString(R.string.battery_health_cold)
        else -> raw.trim()
    }
}

/**
 * Parse a kB string from /proc/meminfo (e.g. "1816596 kB") to a Long.
 */
private fun parseKb(raw: String): Long = try { raw.replace("kB", "").replace("KB", "").trim().toLong() } catch (_: Exception) { 0L }

/**
 * Format a size in kB to human-readable (e.g. 1816596 → "1.7 GB").
 */
private fun formatSizeFromKb(kb: Long): String = formatRam("${kb} kB")

/**
 * Format RAM value from /proc/meminfo (e.g. "1816596 kB") to human-readable (e.g. "1.7 GB").
 */
private fun formatRam(raw: String): String {
    val kb = try { raw.replace("kB", "").replace("KB", "").trim().toLong() } catch (_: Exception) { return raw }
    return if (kb >= 1048576) "%.1f GB".format(kb.toDouble() / 1048576.0)
    else if (kb >= 1024) "%.1f MB".format(kb.toDouble() / 1024.0)
    else "${kb} kB"
}

/**
 * Format storage size from df output (1K-blocks) to human-readable (e.g. "7.4 GB").
 */
private fun formatStorage(raw: String): String {
    val kb = try { raw.trim().toLong() } catch (_: Exception) { return raw }
    return if (kb >= 1048576) "%.1f GB".format(kb.toDouble() / 1048576.0)
    else if (kb >= 1024) "%.1f MB".format(kb.toDouble() / 1024.0)
    else "${kb} kB"
}

/**
 * Map SOC manufacturer codes to common names.
 */
private fun normalizeSocManufacturer(raw: String): String = when (raw) {
    "QTI" -> "Qualcomm"
    "qcom" -> "Qualcomm"
    "MTK" -> "MediaTek"
    "MediaTek" -> "MediaTek"
    "Samsung" -> "Samsung"
    "Xiaomi" -> "Xiaomi"
    else -> raw
}

/**
 * Decode ARM CPU part number to human-readable name.
 * Reference: ARM Cortex/AArch64 MIDR_EL1 part numbers.
 */
private fun decodeArmCpuPart(hex: String): String {
    val part = try { hex.trim().removePrefix("0x").toInt(16) } catch (_: Exception) { return hex }
    return when (part) {
        0xd03 -> "Cortex-A53"
        0xd04 -> "Cortex-A35"
        0xd05 -> "Cortex-A55"
        0xd07 -> "Cortex-A57"
        0xd08 -> "Cortex-A72"
        0xd09 -> "Cortex-A73"
        0xd0a -> "Cortex-A75"
        0xd0b -> "Cortex-A76"
        0xd0c -> "Neoverse N1"
        0xd0d -> "Cortex-A77"
        0xd0e -> "Cortex-A78"
        0xd0f -> "Cortex-A55"
        0xd41 -> "Cortex-A78"
        0xd44 -> "Cortex-X1"
        0xd46 -> "Cortex-A510"
        0xd47 -> "Cortex-A710"
        0xd48 -> "Cortex-X2"
        0xd4d -> "Cortex-A715"
        0xd4e -> "Cortex-X3"
        0xd4f -> "Cortex-A520"
        0xd81 -> "Cortex-A720"
        0xd82 -> "Cortex-X4"
        else -> hex // unknown part, return raw hex
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
