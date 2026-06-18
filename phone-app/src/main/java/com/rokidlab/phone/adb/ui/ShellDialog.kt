package com.rokidlab.phone.adb.ui

import com.rokidlab.phone.adb.AdbShellClient
import com.rokidlab.phone.design.*
import com.rokidlab.phone.R
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
    AdbDialogContent(ctx.getString(R.string.shell_command), BrewMagenta, client, connected, scope, getOrConnect, onDismiss,
        titleContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(ctx.getString(R.string.shell_command), color = BrewMagenta, fontSize = 18.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            }
        }
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
                    withContext(Dispatchers.Main) {
                        entries = entries + ShellEntry("result", output, ts())
                        loading = false
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        entries = entries + ShellEntry("error", "${ctx.getString(R.string.error_label)}: ${e.message}", ts())
                        loading = false
                    }
                }
            }
        }
        
        Column(Modifier.fillMaxWidth().height(460.dp)) {
            Box(
                Modifier.fillMaxWidth().weight(1f)
                    .clip(RoundedCornerShape(6.dp)).background(BrewPanel)
                    .padding(10.dp).verticalScroll(listScrollState),
            ) {
                Column {
                    entries.forEach { entry ->
                        when (entry.type) {
                            "cmd" -> Row {
                                Text(entry.time, color = BrewSuccess.copy(alpha = 0.7f), fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                                Spacer(Modifier.width(4.dp))
                                Text(entry.text, color = BrewInfo, fontSize = 12.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, fontFamily = FontFamily.Monospace)
                            }
                            "result" -> Text(entry.text, color = BrewTextBright, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                            "error" -> Text(entry.text, color = BrewRed, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                            "info" -> Text(entry.text, color = BrewSuccess.copy(alpha = 0.7f), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                }
            }
            
            Spacer(Modifier.height(6.dp))
            
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                    .background(BrewBg).padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Shell>", color = BrewRed, fontSize = 14.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.width(6.dp))
                BasicTextField(
                    value = cmd,
                    onValueChange = { cmd = it },
                    modifier = Modifier.weight(1f).heightIn(min = 38.dp),
                    singleLine = true,
                    textStyle = TextStyle(color = BrewText, fontSize = 14.sp, fontFamily = FontFamily.Monospace),
                     cursorBrush = SolidColor(BrewRed),
                     keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { execute(cmd.trim()) }),
                    decorationBox = { innerTextField ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (cmd.isEmpty()) Text(ctx.getString(R.string.enter_command), color = BrewMuted.copy(alpha = 0.4f), fontSize = 14.sp, fontFamily = FontFamily.Monospace)
                            innerTextField()
                        }
                    },
                )
            }
        }
    }
}