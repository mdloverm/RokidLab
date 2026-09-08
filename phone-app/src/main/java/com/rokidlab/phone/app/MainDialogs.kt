package com.rokidlab.phone.app

import com.rokidlab.phone.R
import com.rokidlab.phone.design.*
import com.rokidlab.phone.model.BrewIndex
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 镜像源选择对话框（Store 主页 / 引导流程共用）。
 */
@Composable
internal fun MirrorSourceDialog(
    currentIndex: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    BrewDialog(onDismiss = onDismiss, title = ctx.getString(R.string.switch_source_btn), color = BrewCoral) {
        BrewDialogContent {
            BrewIndex.MIRRORS.forEachIndexed { index, mirror ->
                val isSelected = currentIndex == index
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isSelected) BrewCoral.copy(alpha = 0.12f) else Color.Transparent)
                        .border(
                            if (isSelected) 1.dp else 0.dp,
                            if (isSelected) BrewGreenDim else Color.Transparent,
                            RoundedCornerShape(12.dp),
                        )
                        .clickable { onSelect(index) }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 源图标
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(BrewShapeSmall)
                            .background(BrewTextBright.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (mirror.iconRes != null) {
                            Icon(
                                painter = painterResource(mirror.iconRes),
                                contentDescription = mirror.name,
                                tint = Color.Unspecified,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            mirror.name,
                            color = if (isSelected) BrewCoral else BrewTextBright,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            ctx.getString(mirror.descriptionRes),
                            color = BrewMuted,
                            fontSize = 13.sp,
                        )
                    }
                    if (isSelected) {
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            Icons.Outlined.CheckCircle,
                            null,
                            tint = BrewCoral,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                if (index < BrewIndex.MIRRORS.size - 1) {
                    Spacer(Modifier.height(8.dp))
                }
            }
            Spacer(Modifier.height(20.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) {
                    Text(ctx.getString(R.string.cancel_btn), color = BrewMuted, fontSize = 15.sp)
                }
            }
        }
    }
}
