package com.rokidlab.phone.store

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.rokidlab.phone.R
import com.rokidlab.phone.design.BrewChat
import com.rokidlab.phone.design.BrewMuted
import com.rokidlab.phone.design.BrewPanel
import com.rokidlab.phone.design.BrewTextBright

// ===== 清空对话确认弹窗 =====
@Composable
internal fun ConfirmClearChatDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_clear), color = BrewTextBright, fontWeight = FontWeight.Bold) },
        text = { Text(stringResource(R.string.chat_clear_confirm), color = BrewMuted, fontSize = 14.sp) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.chat_clear), color = BrewChat, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.chat_key_dialog_cancel), color = BrewMuted)
            }
        },
        containerColor = BrewPanel,
    )
}
