package com.rokidlab.phone.store

import com.rokidlab.phone.app.*
import com.rokidlab.phone.adb.*
import com.rokidlab.phone.design.*
import com.rokidlab.phone.filemanager.*
import com.rokidlab.phone.glasses.*
import com.rokidlab.phone.mirror.*
import com.rokidlab.phone.model.*
import com.rokidlab.phone.network.*
import com.rokidlab.phone.settings.*
import com.rokidlab.phone.store.*
import com.rokidlab.phone.util.*
import com.rokidlab.phone.R
import androidx.compose.ui.platform.LocalContext
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccessibilityNew
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.ElectricScooter
import androidx.compose.material.icons.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Navigation
import androidx.compose.material.icons.outlined.PlayCircle
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.SystemUpdateAlt
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.InstallDesktop
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun Header(
    refreshing: Boolean,
    searchActive: Boolean,
    updateAvailable: Boolean,
    onSearchToggle: () -> Unit,
    onUpdateOpen: () -> Unit,
    onRefresh: () -> Unit,
    onReset: () -> Unit,
    onSwitchMirror: () -> Unit,
    onInstallApk: () -> Unit,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
        modifier = Modifier
            .clip(BrewShapeMedium)
            .clickable(onClick = onReset)
            .padding(end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        ) {
            BrandTitle(fontSize = 24)
        }
        Spacer(Modifier.weight(1f))
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(BrewShapeStandard)
                .clickable(onClick = onSearchToggle),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.Search,
                null,
                tint = if (searchActive) BrewGreen else BrewTextBright,
                modifier = Modifier.size(24.dp),
            )
        }
        Spacer(Modifier.width(6.dp))
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(BrewShapeStandard)
                .clickable(onClick = onUpdateOpen),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Outlined.SystemUpdateAlt,
                null,
                tint = if (updateAvailable) BrewWarning else BrewTextBright,
                modifier = Modifier.size(24.dp),
            )
            if (updateAvailable) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 5.dp, end = 5.dp)
                        .size(10.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(BrewRed)
                        .border(1.dp, BrewWarning, RoundedCornerShape(5.dp)),
                )
            }
        }
        Spacer(Modifier.width(6.dp))
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(12.dp))
                .clickable(enabled = !refreshing, onClick = onRefresh),
            contentAlignment = Alignment.Center,
        ) {
            val rotation by animateFloatAsState(
                targetValue = if (refreshing) 360f else 0f,
                animationSpec = if (refreshing) infiniteRepeatable(
                    animation = tween(800, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart,
                ) else tween(300),
                label = "refresh-spin",
            )
            Icon(
                Icons.Outlined.Refresh,
                null,
                tint = if (refreshing) BrewGreen else BrewTextBright,
                modifier = Modifier.size(25.dp).graphicsLayer { rotationZ = rotation },
            )
        }
        Spacer(Modifier.width(6.dp))
        Box {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(BrewShapeStandard)
                    .clickable(onClick = { menuExpanded = true }),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.MoreVert,
                    null,
                    tint = BrewTextBright,
                    modifier = Modifier.size(24.dp),
                )
            }
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
            ) {
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.SwapHoriz, null, tint = BrewTextBright, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(12.dp))
                            Text(ctx.getString(R.string.switch_source), color = BrewTextBright, fontSize = 15.sp)
                        }
                    },
                    onClick = {
                        menuExpanded = false
                        onSwitchMirror()
                    },
                )
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.InstallDesktop, null, tint = BrewTextBright, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(12.dp))
                            Text(ctx.getString(R.string.install_apk_to_glasses), color = BrewTextBright, fontSize = 15.sp)
                        }
                    },
                    onClick = {
                        menuExpanded = false
                        onInstallApk()
                    },
                )
            }
        }
    }
}
@Composable
internal fun BrandTitle(fontSize: Int, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            "Rokid",
            color = BrewGreen,
            fontSize = fontSize.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
        Text(
            " Lab",
            color = BrewCyan,
            fontSize = fontSize.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}
@Composable
internal fun SearchBar(query: String, onQueryChange: (String) -> Unit) {
    val ctx = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 14.dp)
            .height(46.dp)
            .clip(BrewShapeLarge)
            .background(BrewPanel.copy(alpha = 0.88f))
            .border(1.dp, BrewBorder.copy(alpha = 0.42f), BrewShapeLarge)
            .padding(horizontal = 14.dp),
          verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.Search, null, tint = BrewMuted, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            textStyle = TextStyle(color = BrewTextBright, fontSize = 15.sp),
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                if (query.isBlank()) {
                    Text(
                        ctx.getString(R.string.search_label),
                        color = BrewMuted.copy(alpha = 0.75f),
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                inner()
            },
        )
        if (query.isNotBlank()) {
            Text(
                text = "x",
                color = BrewMuted,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(BrewShapeMedium)
                    .clickable { onQueryChange("") }
                    .padding(8.dp),
            )
        }
    }
}
@Composable
internal fun CategoryChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val fontScale = LocalDensity.current.fontScale
    fun fixedSp(value: Float) = (value / fontScale.coerceAtLeast(1f)).sp
    val chipScale by animateFloatAsState(
        targetValue = if (selected) 1.04f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "chip-scale",
    )
    Box(
        modifier = modifier
            .height(34.dp)
            .widthIn(min = 64.dp)
            .graphicsLayer { scaleX = chipScale; scaleY = chipScale }
            .clip(BrewShapeStandard)
            .background(if (selected) BrewGreen else BrewPanelAlt.copy(alpha = 0.86f))
            .border(1.dp, if (selected) BrewGreen else BrewBorder.copy(alpha = 0.44f), BrewShapeStandard)
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            Text(
                label,
                color = if (selected) BrewBg else BrewTextBright,
                fontSize = fixedSp(13f),
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
@Composable
internal fun CategoryIcon(label: String, color: Color, size: androidx.compose.ui.unit.Dp = 15.dp) {
    val icon = when (label.lowercase()) {
        "more" -> Icons.Outlined.Apps
        "all" -> Icons.Outlined.Apps
        "new" -> Icons.Outlined.Star
        "ai" -> Icons.Outlined.Psychology
        "accessibility" -> Icons.Outlined.AccessibilityNew
        "browser" -> Icons.Outlined.Public
        "media" -> Icons.Outlined.PlayCircle
        "mobility" -> Icons.Outlined.ElectricScooter
        "navigation" -> Icons.Outlined.Navigation
        else -> Icons.Outlined.Apps
    }
    Icon(icon, null, tint = color, modifier = Modifier.size(size))
}
@Composable
internal fun SectionHeader(title: String, action: String? = null, onAction: (() -> Unit)? = null, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = BrewTextBright, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.weight(1f))
        if (action != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable(enabled = onAction != null) { onAction?.invoke() },
            ) {
                Text(action, color = BrewGreen, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(6.dp))
                Icon(Icons.Outlined.KeyboardArrowRight, null, tint = BrewGreen, modifier = Modifier.size(18.dp))
            }
        }
    }
}
@Composable
internal fun StoreActionButton(
    label: String,
    primary: Boolean,
    enabled: Boolean,
    destructive: Boolean = false,
    icon: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val accent = if (destructive) BrewRed else BrewGreen
    val border = accent
    val background = if (primary) accent else Color.Transparent
    val contentColor = if (primary) BrewBg else accent
    Row(
        modifier = modifier
            .clip(BrewShapeMedium)
            .background(if (enabled) background else BrewPanelHi.copy(alpha = 0.45f))
            .border(1.dp, if (enabled) border else BrewBorder, BrewShapeMedium)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides if (enabled) contentColor else BrewMuted) {
            if (icon != null) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(17.dp)) {
                    icon()
                }
                Spacer(Modifier.width(6.dp))
            }
            Text(
                label,
                color = if (enabled) contentColor else BrewMuted,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
@Composable
internal fun EmptyState(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(132.dp)
            .clip(BrewShapeMedium)
            .background(BrewPanel)
            .border(1.dp, BrewBorder, BrewShapeMedium),
        contentAlignment = Alignment.Center,
    ) {
        Text(ctx.getString(R.string.no_apps_found), color = BrewMuted, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}
