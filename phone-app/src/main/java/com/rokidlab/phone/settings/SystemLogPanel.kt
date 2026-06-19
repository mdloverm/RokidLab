package com.rokidlab.phone.settings

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
import androidx.compose.ui.platform.LocalContext
import com.rokidlab.phone.R
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
internal fun SystemLogPanel(
    statusLines: List<String>,
    expanded: Boolean,
    busy: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val logScrollState = rememberScrollState()
    val latestLine = statusLines.lastOrNull() ?: ctx.getString(R.string.ready)
    LaunchedEffect(expanded, statusLines.size) {
        if (expanded) logScrollState.animateScrollTo(logScrollState.maxValue)
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(BrewShapeLarge)
            .background(BrewPanel.copy(alpha = 0.94f))
            .border(1.dp, BrewBorder.copy(alpha = 0.52f), BrewShapeLarge)
            .clickable(onClick = onToggle)
            .animateContentSize(),
    ) {
        Box(
            modifier = Modifier
                .padding(top = 7.dp)
                .width(36.dp)
                .height(3.dp)
                .clip(BrewShapeSmall)
                .background(BrewMuted.copy(alpha = 0.50f))
                .align(Alignment.CenterHorizontally),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 7.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TerminalBadge(size = 30.dp, active = false)
            Spacer(Modifier.width(12.dp))
            Text(ctx.getString(R.string.system_log), color = BrewTextBright, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, fontFamily = BrewFont)
            Spacer(Modifier.weight(1f))
            LogStatusPill(busy)
            Spacer(Modifier.width(10.dp))
            Icon(
                if (expanded) Icons.Outlined.KeyboardArrowDown else Icons.Outlined.KeyboardArrowUp,
                null,
                tint = BrewTextBright,
                modifier = Modifier.size(21.dp),
            )
        }
        if (expanded) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 76.dp, max = 172.dp)
                    .padding(start = 18.dp, end = 18.dp, bottom = 13.dp)
                    .verticalScroll(logScrollState),
            ) {
                Text(
                    statusLines.joinToString("\n"),
                    color = BrewMuted,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    fontFamily = BrewFont,
                )
            }
        } else {
            Text(
                latestLine,
                color = BrewMuted,
                fontSize = 11.sp,
                lineHeight = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 18.dp, end = 18.dp, bottom = 10.dp),
                fontFamily = BrewFont,
            )
        }
    }
}
@Composable
internal fun TerminalBadge(size: androidx.compose.ui.unit.Dp, active: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(size)
            .clip(BrewShapeMedium)
            .background(BrewBg)
            .border(1.dp, if (active) BrewGreenDim else BrewBorder, BrewShapeMedium),
        contentAlignment = Alignment.Center,
    ) {
        Text(">_", color = if (active) BrewCoral else BrewTextBright, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, fontFamily = BrewFont)
    }
}
@Composable
internal fun LogStatusPill(busy: Boolean) {
    val ctx = LocalContext.current
    Box(
        modifier = Modifier
            .clip(BrewShapeSmall)
            .background(if (busy) BrewAmber.copy(alpha = 0.14f) else BrewCoral.copy(alpha = 0.13f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            if (busy) ctx.getString(R.string.running) else ctx.getString(R.string.ready),
            color = if (busy) BrewAmber else BrewCoral,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
@Composable
internal fun StatusDock(
    statusLines: List<String>,
    expanded: Boolean,
    busy: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    SystemLogPanel(
        statusLines = statusLines,
        expanded = expanded,
        busy = busy,
        onToggle = onToggle,
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 10.dp, end = 10.dp, bottom = 8.dp),
    )
}
