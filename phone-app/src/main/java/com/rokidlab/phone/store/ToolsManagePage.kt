package com.rokidlab.phone.store

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.rokidlab.phone.R
import com.rokidlab.phone.ai.ToolRegistry
import com.rokidlab.phone.app.LabApplication
import com.rokidlab.phone.design.BrewBg
import com.rokidlab.phone.design.BrewBorder
import com.rokidlab.phone.design.BrewChat
import com.rokidlab.phone.design.BrewMuted
import com.rokidlab.phone.design.BrewPanel
import com.rokidlab.phone.design.BrewPanelHi
import com.rokidlab.phone.design.BrewTextBright

// ===== AI 工具管理子页面 =====
@Composable
internal fun ToolsManagePage(
    app: LabApplication,
    onBack: () -> Unit,
) {
    val ctx = LocalContext.current
    var toolStates by remember {
        mutableStateOf(ToolRegistry.toolList.associate { it.name to ToolRegistry.isEnabled(ctx, it.name) })
    }
    Dialog(
        onDismissRequest = onBack,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(BrewBg)
                .padding(20.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.chat_settings_tools),
                    color = BrewTextBright,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    onClick = {
                        toolStates.forEach { (name, enabled) -> ToolRegistry.setEnabled(ctx, name, enabled) }
                        onBack()
                    },
                ) {
                    Text(stringResource(R.string.done), color = BrewChat, fontWeight = FontWeight.Bold)
                }
            }
            Text(
                text = stringResource(R.string.chat_settings_tools_hint),
                color = BrewMuted,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(12.dp))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            ) {
                ToolRegistry.toolList.forEach { tool ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(BrewPanel)
                            .border(1.dp, BrewBorder, RoundedCornerShape(12.dp))
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(tool.displayNameRes),
                                color = BrewTextBright,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = stringResource(tool.descriptionRes),
                                color = BrewMuted,
                                fontSize = 11.sp,
                            )
                        }
                        Switch(
                            checked = toolStates[tool.name] ?: true,
                            onCheckedChange = { toolStates = toolStates + (tool.name to it) },
                            colors = SwitchDefaults.colors(
                                checkedTrackColor = BrewChat,
                                uncheckedTrackColor = BrewPanelHi,
                                checkedThumbColor = BrewBg,
                                uncheckedThumbColor = BrewMuted,
                            ),
                        )
                    }
                }
            }
        }
    }
}
