package com.rokidlab.phone.store

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.rokidlab.phone.app.LabApplication
import com.rokidlab.phone.design.BrewBg
import com.rokidlab.phone.design.BrewBorder
import com.rokidlab.phone.design.BrewChat
import com.rokidlab.phone.design.BrewMuted
import com.rokidlab.phone.design.BrewPanel
import com.rokidlab.phone.design.BrewPanelHi
import com.rokidlab.phone.design.BrewTextBright

// ===== Agent 会话记忆子页面 =====
@Composable
internal fun AgentSectionPage(
    app: LabApplication,
    onBack: () -> Unit,
) {
    val ctx = LocalContext.current
    var memoryEnabled by remember {
        mutableStateOf(com.rokidlab.phone.ai.AgentSessionManager.isEnabled(ctx))
    }
    var longTermEnabled by remember {
        mutableStateOf(com.rokidlab.phone.ai.LongTermMemoryManager.isEnabled(ctx))
    }
    var longTermCount by remember {
        mutableIntStateOf(com.rokidlab.phone.ai.LongTermMemoryManager.count(ctx))
    }
    var showClearConfirm by remember { mutableStateOf(false) }
    var showLongClearConfirm by remember { mutableStateOf(false) }
    var showMemoryManage by remember { mutableStateOf(false) }
    Dialog(
        onDismissRequest = onBack,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(BrewBg)
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.agent_section_title),
                    color = BrewTextBright,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onBack) {
                    Text(stringResource(R.string.back_btn), color = BrewChat, fontWeight = FontWeight.Bold)
                }
            }
            Text(
                text = stringResource(R.string.agent_section_subtitle),
                color = BrewMuted,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(16.dp))

            // 会话记忆开关
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(BrewPanel)
                    .border(1.dp, BrewBorder, RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.agent_memory_switch),
                        color = BrewTextBright,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.agent_memory_switch_hint),
                        color = BrewMuted,
                        fontSize = 11.sp,
                    )
                }
                Switch(
                    checked = memoryEnabled,
                    onCheckedChange = {
                        memoryEnabled = it
                        com.rokidlab.phone.ai.AgentSessionManager.setEnabled(ctx, it)
                    },
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = BrewChat,
                        uncheckedTrackColor = BrewPanelHi,
                        checkedThumbColor = BrewBg,
                        uncheckedThumbColor = BrewMuted,
                    ),
                )
            }

            Spacer(Modifier.height(16.dp))
            // 会话信息
            Text(
                text = stringResource(R.string.agent_memory_info),
                color = BrewMuted,
                fontSize = 12.sp,
            )

            Spacer(Modifier.height(20.dp))
            // 清空会话记忆按钮
            Button(
                onClick = { showClearConfirm = true },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = BrewPanel,
                    contentColor = BrewChat,
                ),
            ) {
                Icon(
                    imageVector = Icons.Filled.DeleteSweep,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.agent_memory_clear), fontWeight = FontWeight.Medium)
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = BrewBorder)
            Spacer(Modifier.height(16.dp))

            // 长期记忆开关（跨会话记住用户偏好）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(BrewPanel)
                    .border(1.dp, BrewBorder, RoundedCornerShape(12.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.agent_longterm_switch),
                        color = BrewTextBright,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.agent_longterm_switch_hint),
                        color = BrewMuted,
                        fontSize = 11.sp,
                    )
                }
                Switch(
                    checked = longTermEnabled,
                    onCheckedChange = {
                        longTermEnabled = it
                        com.rokidlab.phone.ai.LongTermMemoryManager.setEnabled(ctx, it)
                    },
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = BrewChat,
                        uncheckedTrackColor = BrewPanelHi,
                        checkedThumbColor = BrewBg,
                        uncheckedThumbColor = BrewMuted,
                    ),
                )
            }

            Spacer(Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.agent_longterm_info, longTermCount),
                color = BrewMuted,
                fontSize = 12.sp,
            )

            Spacer(Modifier.height(20.dp))
            // 查看与编辑（方案 §4.3.3a）：记忆此前用户**看不见、改不了、删不掉** ——
            // 一个"替你记住这些事情"的功能，用户无法核对和纠正，是隐私功能上的硬伤。
            Button(
                onClick = { showMemoryManage = true },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = BrewChat,
                    contentColor = BrewBg,
                ),
            ) {
                Icon(
                    imageVector = Icons.Filled.Edit,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.agent_longterm_manage), fontWeight = FontWeight.Medium)
            }

            Spacer(Modifier.height(10.dp))
            // 清空长期记忆按钮
            Button(
                onClick = { showLongClearConfirm = true },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = BrewPanel,
                    contentColor = BrewChat,
                ),
            ) {
                Icon(
                    imageVector = Icons.Filled.DeleteSweep,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.agent_longterm_clear), fontWeight = FontWeight.Medium)
            }
        }
    }

    if (showMemoryManage) {
        MemoryManageDialog(
            onDismiss = { showMemoryManage = false },
            onChanged = {
                longTermCount = com.rokidlab.phone.ai.LongTermMemoryManager.count(ctx)
            },
        )
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text(stringResource(R.string.agent_memory_clear), color = BrewTextBright, fontWeight = FontWeight.Bold) },
            text = { Text(stringResource(R.string.agent_memory_clear_confirm), color = BrewMuted, fontSize = 14.sp) },
            confirmButton = {
                TextButton(onClick = {
                    com.rokidlab.phone.ai.AgentSessionManager.clear()
                    showClearConfirm = false
                    Toast.makeText(ctx, ctx.getString(R.string.agent_memory_cleared), Toast.LENGTH_SHORT).show()
                }) {
                    Text(stringResource(R.string.confirm), color = BrewChat, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) {
                    Text(stringResource(R.string.chat_key_dialog_cancel), color = BrewMuted)
                }
            },
            containerColor = BrewPanel,
        )
    }

    if (showLongClearConfirm) {
        AlertDialog(
            onDismissRequest = { showLongClearConfirm = false },
            title = { Text(stringResource(R.string.agent_longterm_clear), color = BrewTextBright, fontWeight = FontWeight.Bold) },
            text = { Text(stringResource(R.string.agent_longterm_clear_confirm), color = BrewMuted, fontSize = 14.sp) },
            confirmButton = {
                TextButton(onClick = {
                    com.rokidlab.phone.ai.LongTermMemoryManager.clear(ctx)
                    longTermCount = 0
                    showLongClearConfirm = false
                    Toast.makeText(ctx, ctx.getString(R.string.agent_longterm_cleared), Toast.LENGTH_SHORT).show()
                }) {
                    Text(stringResource(R.string.confirm), color = BrewChat, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showLongClearConfirm = false }) {
                    Text(stringResource(R.string.chat_key_dialog_cancel), color = BrewMuted)
                }
            },
            containerColor = BrewPanel,
        )
    }
}
