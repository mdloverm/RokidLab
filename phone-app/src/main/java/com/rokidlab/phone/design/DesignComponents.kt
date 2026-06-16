package com.rokidlab.phone.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rokidlab.phone.R
import androidx.compose.ui.platform.LocalContext

/**
 * 全局错误卡片 — 统一错误提示样式
 * 用于所有模块的 inline 错误展示，带重试按钮
 */
@Composable
internal fun BrewErrorCard(
    message: String,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(BrewRed.copy(alpha = 0.08f))
            .border(1.dp, BrewRed.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
            .padding(12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(ctx.getString(R.string.error_label), color = BrewRed, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(2.dp))
                Text(message, color = BrewRed.copy(alpha = 0.9f), fontSize = 12.sp)
            }
            if (onRetry != null) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(BrewRed.copy(alpha = 0.15f))
                        .clickable { onRetry() }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(ctx.getString(R.string.retry), color = BrewRed, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
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
    val ctx = LocalContext.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(BrewWarning.copy(alpha = 0.08f))
            .border(1.dp, BrewWarning.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
            .padding(12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(ctx.getString(R.string.caution), color = BrewWarning, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(2.dp))
                Text(message, color = BrewWarning.copy(alpha = 0.9f), fontSize = 12.sp)
            }
            if (actionLabel != null && onAction != null) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(BrewWarning.copy(alpha = 0.15f))
                        .clickable { onAction() }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(actionLabel, color = BrewWarning, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
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
            .clip(RoundedCornerShape(8.dp))
            .background(BrewPanel)
            .border(1.dp, BrewBorder, RoundedCornerShape(8.dp))
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
    val ctx = LocalContext.current
    val color = if (success) BrewSuccess else BrewRed
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.08f))
            .border(1.dp, color.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
            .padding(12.dp),
    ) {
        Column {
            Text(
                if (success) ctx.getString(R.string.success) else ctx.getString(R.string.failure),
                color = color,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(2.dp))
            Text(message, color = color.copy(alpha = 0.9f), fontSize = 12.sp)
            if (detail.isNotBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(detail, color = BrewMuted, fontSize = 10.sp)
            }
        }
    }
}
