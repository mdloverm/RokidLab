package com.rokidlab.phone.store

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rokidlab.phone.R
import com.rokidlab.phone.design.BrewChat
import com.rokidlab.phone.design.BrewMuted
import com.rokidlab.phone.design.BrewPanel

// ===== 顶部标题栏 =====
@Composable
internal fun ChatHeader(
    onPhotoAsk: () -> Unit,
    onOpenKb: () -> Unit,
    onOpenSettings: () -> Unit,
    onClearChat: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(BrewPanel)
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.chat_title),
                color = BrewChat,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = stringResource(R.string.chat_subtitle),
                color = BrewMuted,
                fontSize = 11.sp,
            )
        }
        IconButton(onClick = onPhotoAsk) {
            Icon(
                imageVector = Icons.Filled.PhotoCamera,
                contentDescription = stringResource(R.string.chat_photo_ask),
                tint = BrewChat,
            )
        }
        IconButton(onClick = onOpenKb) {
            Icon(
                imageVector = Icons.Filled.Folder,
                contentDescription = stringResource(R.string.chat_kb),
                tint = BrewChat,
            )
        }
        IconButton(onClick = onClearChat) {
            Icon(
                imageVector = Icons.Filled.DeleteSweep,
                contentDescription = stringResource(R.string.chat_clear),
                tint = BrewChat,
            )
        }
        IconButton(onClick = onOpenSettings) {
            Icon(
                imageVector = Icons.Filled.Settings,
                contentDescription = stringResource(R.string.chat_settings),
                tint = BrewChat,
            )
        }
    }
}
