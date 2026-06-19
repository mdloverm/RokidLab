package com.rokidlab.phone.design

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rokidlab.phone.R
import androidx.compose.ui.platform.LocalContext

// ═══════════════════════════════════════════════════
// 标准按钮系统 — 统一交互反馈
// 所有按钮遵循：按下时 scale(0.95) + alpha 微降
// ═══════════════════════════════════════════════════

/**
 * 主要操作按钮 — 填充色 + 白色文字
 * 用于确认、提交、主要跳转等场景
 */
@Composable
internal fun BrewButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = BrewCoral,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "btnScale",
    )
    val dimAlpha = if (enabled) 1f else 0.45f

    Box(
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale; alpha = dimAlpha }
            .clip(BrewShapeStandard)
            .background(if (isPressed) color.copy(alpha = 0.8f) else color)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .padding(horizontal = 20.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (loading) "..." else text,
            color = BrewTextBright,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

/**
 * 次要操作按钮 — 边框样式
 * 用于取消、次要操作等
 */
@Composable
internal fun BrewOutlineButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = BrewText,
    enabled: Boolean = true,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "btnScale",
    )
    val dimAlpha = if (enabled) 1f else 0.4f

    Box(
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale; alpha = dimAlpha }
            .clip(BrewShapeStandard)
            .background(if (isPressed) color.copy(alpha = 0.12f) else Color.Transparent)
            .border(1.dp, color.copy(alpha = if (isPressed) 1f else 0.6f), BrewShapeStandard)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .padding(horizontal = 20.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = color, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

/**
 * 紧凑操作按钮 — 用于列表行内操作
 * 比标准按钮更小，用于"删除"、"安装"等行内操作
 */
@Composable
internal fun BrewCompactButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = BrewTextBright,
    enabled: Boolean = true,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    Box(
        modifier = modifier
            .graphicsLayer { scaleX = if (isPressed) 0.93f else 1f; scaleY = if (isPressed) 0.93f else 1f }
            .clip(BrewShapeMedium)
            .background(if (isPressed) color.copy(alpha = 0.25f) else color.copy(alpha = 0.15f))
            .border(1.dp, color.copy(alpha = if (isPressed) 0.7f else 0.4f), BrewShapeMedium)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = color.copy(alpha = if (enabled) 1f else 0.4f), fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

/**
 * 图标操作按钮 — 圆形/方形图标按钮
 * 用于工具栏图标、关闭按钮等
 */
@Composable
internal fun BrewIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    bgColor: Color = BrewPanel,
    content: @Composable () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    Box(
        modifier = modifier
            .graphicsLayer { scaleX = if (isPressed) 0.90f else 1f; scaleY = if (isPressed) 0.90f else 1f }
            .clip(BrewShapeStandard)
            .background(if (isPressed) bgColor.copy(alpha = 0.7f) else bgColor)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(7.dp),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

// ═══════════════════════════════════════════════════
// 标准对话框系统 — RokidLink 卡片样式
// ═══════════════════════════════════════════════════

/**
 * 标准对话框容器 — 统一所有弹窗的 RokidLink 卡片外观
 *
 * @param title 可选标题
 * @param icon 可选标题图标（emoji 或符号）
 * @param subtitle 可选副标题
 * @param color 卡片主题色（决定标题条背景和边框颜色）
 */
@Composable
internal fun BrewDialog(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    icon: String? = null,
    subtitle: String? = null,
    color: Color = BrewInfo,
    properties: androidx.compose.ui.window.DialogProperties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = true),
    content: @Composable () -> Unit,
) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = properties,
    ) {
        Surface(
            modifier = modifier,
            shape = BrewShapeStandard,
            color = BrewPanel,
            border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.25f)),
        ) {
            Column {
                if (title != null) {
                    // 彩色标题条
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .background(color)
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (icon != null) {
                                Text(
                                    text = icon,
                                    color = Color.White.copy(alpha = 0.85f),
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(end = 8.dp),
                                )
                            }
                            Column {
                                Text(
                                    text = title,
                                    color = Color.White,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp,
                                )
                                if (subtitle != null) {
                                    Text(
                                        text = subtitle,
                                        color = Color.White.copy(alpha = 0.7f),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                    )
                                }
                            }
                        }
                    }
                    // 装饰分隔线
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(4.dp)
                            .background(BrewBorder)
                            .offset(x = 8.dp),
                    )
                }
                content()
            }
        }
    }
}

/**
 * 标准对话框内容区域
 */
@Composable
internal fun BrewDialogContent(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier.padding(horizontal = 24.dp, vertical = 16.dp),
        content = content,
    )
}

/**
 * 标准对话框操作按钮行
 */
@Composable
internal fun BrewDialogActions(
    modifier: Modifier = Modifier,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.End,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 16.dp),
        horizontalArrangement = horizontalArrangement,
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/**
 * 标准对话框操作按钮（text button 样式，同 BrewOutlineButton 风格）
 */
@Composable
internal fun BrewDialogButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = BrewText,
    enabled: Boolean = true,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "dlgBtnScale",
    )
    Box(
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(BrewShapeMedium)
            .background(if (isPressed) color.copy(alpha = 0.12f) else Color.Transparent)
            .border(1.dp, color.copy(alpha = if (isPressed) 1f else 0.5f), BrewShapeMedium)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = color.copy(alpha = if (enabled) 1f else 0.4f),
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

/**
 * 标准对话框可选中的列表项 — 用于语言选择、源选择等
 */
@Composable
internal fun BrewDialogSelectItem(
    primaryText: String,
    secondaryText: String? = null,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selectedColor: Color = BrewCoral,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(BrewShapeMedium)
            .background(if (isSelected) selectedColor.copy(alpha = 0.12f) else Color.Transparent)
            .border(
                if (isSelected) 1.dp else 0.dp,
                if (isSelected) selectedColor.copy(alpha = 0.5f) else Color.Transparent,
                BrewShapeMedium,
            )
            .clickable { onClick() }
            .padding(16.dp),
    ) {
        if (secondaryText != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(primaryText, color = if (isSelected) selectedColor else BrewText, fontSize = 16.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
                    Spacer(Modifier.height(2.dp))
                    Text(secondaryText, color = BrewMuted, fontSize = 12.sp)
                }
                if (isSelected) {
                    Text("✓", color = selectedColor, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            }
        } else {
            Text(primaryText, color = if (isSelected) selectedColor else BrewText, fontSize = 16.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
        }
    }
}

// ═══════════════════════════════════════════════════
// 标准状态指示器
// ═══════════════════════════════════════════════════

/**
 * 状态圆点 — 通用在线/离线指示器
 */
@Composable
internal fun BrewStatusDot(
    active: Boolean,
    modifier: Modifier = Modifier,
    activeColor: Color = BrewSuccess,
    inactiveColor: Color = BrewRed,
    size: Dp = 8.dp,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(if (active) activeColor else inactiveColor),
    )
}

/**
 * 状态标签 — 用于模块状态、连接状态等
 * 带背景色的圆角标签 + 状态文字
 */
@Composable
internal fun BrewStatusPill(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = BrewTextBright,
    bgAlpha: Float = 0.15f,
) {
    Box(
        modifier = modifier
            .clip(BrewShapeSmall)
            .background(color.copy(alpha = bgAlpha))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(text, color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

/**
 * 状态卡片 — 用于显示操作结果、提示信息
 * 成功/错误/警告/信息四种样式
 */
@Composable
internal fun BrewStateCard(
    type: StateCardType,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val color = when (type) {
        StateCardType.SUCCESS -> BrewSuccess
        StateCardType.ERROR -> BrewRed
        StateCardType.WARNING -> BrewWarning
        StateCardType.INFO -> BrewInfo
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(BrewShapeMedium)
            .background(color.copy(alpha = 0.08f))
            .border(1.dp, color.copy(alpha = 0.25f), BrewShapeMedium)
            .padding(12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Spacer(Modifier.height(3.dp))
                Text(message, color = color.copy(alpha = 0.9f), fontSize = 12.sp)
            }
            if (actionLabel != null && onAction != null) {
                Spacer(Modifier.width(8.dp))
                BrewCompactButton(actionLabel, onAction, color = color)
            }
        }
    }
}

internal enum class StateCardType { SUCCESS, ERROR, WARNING, INFO }

// ═══════════════════════════════════════════════════
// 后续：模块色板补色对（拆分明暗层次）
// ═══════════════════════════════════════════════════

// ── 商店模块：珊瑚红 ↔ 静谧蓝 (互补) ──
//   BrewCoral(已定义为0xFFE85D3F) + 补色 BrewInfo(0xFF5B8FB9)

// ── 屏幕镜像：静谧蓝为主 ── 
//   BrewCyan(0xFF5B8FB9) — 冷调，对应"镜像"的冷静感

// ── 手机投屏：金色为主 ──
//   BrewPurple(原0xFFD4A85C) — 实际是金色，重命名不合理但保持兼容

// ── 文件管理：紫色为主 ──
//   BrewAmber(原0xFFA78BFA) — 实际是紫色

// ═══════════════════════════════════════════════════
// 保留原有组件兼容
// ═══════════════════════════════════════════════════

/**
 * 全局错误卡片 — 统一错误提示样式
 */
@Composable
internal fun BrewErrorCard(
    message: String,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    BrewStateCard(
        type = StateCardType.ERROR,
        title = LocalContext.current.getString(R.string.error_label),
        message = message,
        modifier = modifier,
        actionLabel = if (onRetry != null) LocalContext.current.getString(R.string.retry) else null,
        onAction = onRetry,
    )
}

/**
 * 全局警告卡片
 */
@Composable
fun BrewWarningCard(
    message: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    BrewStateCard(
        type = StateCardType.WARNING,
        title = LocalContext.current.getString(R.string.caution),
        message = message,
        modifier = modifier,
        actionLabel = actionLabel,
        onAction = onAction,
    )
}

/**
 * 全局加载卡片
 */
@Composable
internal fun BrewLoadingCard(
    message: String? = null,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val text = message ?: ctx.getString(R.string.loading)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(BrewShapeStandard)
            .background(BrewPanel)
            .border(1.dp, BrewBorder, BrewShapeStandard)
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = BrewMuted, fontSize = 13.sp)
    }
}

/**
 * 全局操作结果提示卡 — 成功/失败通用
 */
@Composable
fun BrewResultCard(
    success: Boolean,
    message: String,
    detail: String = "",
    modifier: Modifier = Modifier,
) {
    BrewStateCard(
        type = if (success) StateCardType.SUCCESS else StateCardType.ERROR,
        title = if (success) LocalContext.current.getString(R.string.success)
                else LocalContext.current.getString(R.string.failure),
        message = if (detail.isNotBlank()) "$message\n$detail" else message,
        modifier = modifier,
    )
}

@Composable
fun BrutalTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    color: Color = BrewInfo,
    enabled: Boolean = true,
    singleLine: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.heightIn(min = if (singleLine) 48.dp else 56.dp),
        placeholder = { Text(placeholder, color = color.copy(alpha = 0.4f), fontSize = 13.sp) },
        textStyle = TextStyle(color = color, fontSize = 13.sp, fontFamily = FontFamily.Monospace),
        singleLine = singleLine,
        enabled = enabled,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = color.copy(alpha = 0.6f),
            unfocusedBorderColor = color.copy(alpha = 0.2f),
            disabledBorderColor = BrewBorder,
            disabledTextColor = BrewMuted,
            cursorColor = color,
        ),
        shape = BrewShapeSmall,
    )
}
