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
import com.rokidlab.phone.design.BrewAmber
import com.rokidlab.phone.design.BrewBg
import com.rokidlab.phone.design.BrewBorder
import com.rokidlab.phone.design.BrewChat
import com.rokidlab.phone.design.BrewMuted
import com.rokidlab.phone.design.BrewPanel
import com.rokidlab.phone.design.BrewPanelHi
import com.rokidlab.phone.design.BrewTextBright

// ===== AI 工具管理子页面 =====
//
// 只展示 [ToolRegistry.visibleTools] —— 系统性/内部工具不出现在这里（见 ToolMeta.hidden：
// 自检 / 读日志 / 放弃任务记录 / 给 AIUI 页面取封面）。它们是模型判断该用时才用的内部机制，
// 不是让用户逐项开关的「能力」，摆出来只会让人困惑「这个关了会怎样」。
// ⚠️ **隐藏只影响本页展示**：这些工具照常下发给模型、照常可被调用，开关也保持默认开启。
//
// 按 [ToolRegistry.ToolCategory] 分组（用户视角分类，与内部 `group` 域不是一回事：
// 域管装配、分类管阅读）。顺序 = 枚举声明顺序；组内保持 toolList 声明顺序，不重排。
@Composable
internal fun ToolsManagePage(
    app: LabApplication,
    onBack: () -> Unit,
) {
    val ctx = LocalContext.current
    // 注意：这里收集的仍是**全部**工具（含本页不展示的系统性工具）。
    // 保存时整体写回 ⇒ 未展示项保持其原值，不会被误改成默认值。
    var toolStates by remember {
        mutableStateOf(ToolRegistry.toolList.associate { it.name to ToolRegistry.isEnabled(ctx, it.name) })
    }
    val grouped = remember { ToolRegistry.visibleTools.groupBy { it.category } }
    // 本机模式开启时，给「需眼镜」的工具换个颜色提示 —— 否则用户会以为开关坏了
    val phoneOnly = app.chatLocalOnlyEnabled
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
            if (phoneOnly) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = stringResource(R.string.chat_local_only_active_hint),
                    color = BrewAmber,
                    fontSize = 11.sp,
                )
            }
            Spacer(Modifier.height(12.dp))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            ) {
                ToolRegistry.ToolCategory.entries.forEach { category ->
                    val tools = grouped[category] ?: return@forEach
                    Text(
                        text = stringResource(category.labelRes),
                        color = BrewChat,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 4.dp, top = 10.dp, bottom = 6.dp),
                    )
                    tools.forEach { tool ->
                        val needsGlasses = tool.name in ToolRegistry.GLASSES_REQUIRED_TOOLS
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(BrewPanel)
                                .border(1.dp, BrewBorder, RoundedCornerShape(12.dp))
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = stringResource(tool.displayNameRes),
                                        color = BrewTextBright,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium,
                                    )
                                    // 「需眼镜」标记：本机模式下这些工具已从模型可见清单里摘掉
                                    if (needsGlasses) {
                                        Spacer(Modifier.padding(start = 6.dp))
                                        Text(
                                            text = stringResource(R.string.ai_tool_needs_glasses_tag),
                                            color = if (phoneOnly) BrewMuted else BrewAmber,
                                            fontSize = 10.sp,
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(BrewPanelHi)
                                                .padding(horizontal = 5.dp, vertical = 1.dp),
                                        )
                                    }
                                }
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
}
