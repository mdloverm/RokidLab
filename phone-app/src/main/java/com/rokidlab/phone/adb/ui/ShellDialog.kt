package com.rokidlab.phone.adb.ui

import com.rokidlab.phone.adb.AdbShellClient
import com.rokidlab.phone.design.*
import com.rokidlab.phone.R
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

@Composable
fun ShellDialog(
    client: AdbShellClient?,
    connected: Boolean,
    scope: kotlinx.coroutines.CoroutineScope,
    getOrConnect: ((AdbShellClient?) -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    AdbDialogContent(
        ctx.getString(R.string.shell_command), BrewAmber, client, connected, scope, getOrConnect, onDismiss,
        icon = null,
        subtitle = ctx.getString(R.string.shell_command_subtitle),
    ) { c ->
        data class ShellEntry(val type: String, val text: String, val time: String)

        var entries by remember { mutableStateOf(listOf(ShellEntry("info", ctx.getString(R.string.shell_ready), ""))) }
        var cmd by remember { mutableStateOf("") }
        var loading by remember { mutableStateOf(false) }
        val listScrollState = rememberScrollState()
        val ts = { java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date()) }

        fun execute(input: String) {
            if (input.isBlank() || loading) return
            cmd = ""
            loading = true
            entries = entries + ShellEntry("cmd", "Shell> $input", ts())
            scope.launch(Dispatchers.IO) {
                try {
                    val r = c.executeShellCommand(input)
                    val output = r.trim().ifEmpty { ctx.getString(R.string.shell_empty_output) }
                    withContext(Dispatchers.Main) { entries = entries + ShellEntry("result", output, ts()); loading = false }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) { entries = entries + ShellEntry("error", "${ctx.getString(R.string.error_label)}: ${e.message}", ts()); loading = false }
                }
            }
        }

        Column(Modifier.fillMaxWidth()) {
            // ── 输出区 ──
            Box(
                Modifier.fillMaxWidth().weight(1f)
                    .clip(RoundedCornerShape(8.dp)).background(BrewBg)
                    .border(1.dp, BrewBorder, RoundedCornerShape(8.dp))
                    .padding(12.dp).verticalScroll(listScrollState),
            ) {
                Column {
                    entries.forEachIndexed { idx, entry ->
                        if (idx > 0) { Box(Modifier.fillMaxWidth().height(1.dp).background(BrewBorder.copy(alpha = 0.3f))); Spacer(Modifier.height(6.dp)) }
                        when (entry.type) {
                            "cmd" -> Row(verticalAlignment = Alignment.CenterVertically) { Text(entry.time, color = BrewSuccess.copy(alpha = 0.6f), fontSize = 11.sp, fontFamily = FontFamily.Monospace); Spacer(Modifier.width(6.dp)); Text(entry.text, color = BrewInfo, fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace) }
                            "result" -> Text(entry.text, color = BrewTextBright, fontSize = 14.sp, fontFamily = FontFamily.Monospace, lineHeight = 22.sp)
                            "error" -> Text(entry.text, color = BrewRed, fontSize = 14.sp, fontFamily = FontFamily.Monospace, lineHeight = 22.sp)
                            "info" -> Text(entry.text, color = BrewSuccess.copy(alpha = 0.7f), fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                }
            }
            Spacer(Modifier.height(8.dp))

            // ── 输入区 ──
            Row(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp)).background(BrewBg)
                    .border(1.dp, BrewBorder, RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Shell>", color = BrewTeal, fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.width(8.dp))
                BasicTextField(
                    value = cmd, onValueChange = { cmd = it },
                    modifier = Modifier.weight(1f).heightIn(min = 40.dp), singleLine = true,
                    textStyle = TextStyle(color = BrewText, fontSize = 14.sp, fontFamily = FontFamily.Monospace),
                    cursorBrush = SolidColor(BrewTeal),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { execute(cmd.trim()) }),
                    decorationBox = { innerTextField -> Box(contentAlignment = Alignment.CenterStart) { if (cmd.isEmpty()) Text(ctx.getString(R.string.enter_command), color = BrewMuted.copy(alpha = 0.4f), fontSize = 14.sp, fontFamily = FontFamily.Monospace); innerTextField() } },
                )
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier.height(36.dp).clip(BrewShapeSmall).background(BrewCoral.copy(alpha = 0.2f))
                        .border(1.dp, BrewCoral.copy(alpha = 0.6f), BrewShapeSmall)
                        .clickable(enabled = !loading && cmd.isNotBlank()) { execute(cmd.trim()) }
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center,
                ) { Text("↵", color = if (loading) BrewMuted else BrewCoral, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
            }
        }
    }
}
