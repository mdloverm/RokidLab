package com.rokidlab.phone.store

import com.rokidlab.phone.settings.SettingsScreen
import androidx.compose.runtime.Composable

// ===== 设置模块 =====
@Composable
internal fun SettingsModule(
    state: StoreUiState,
    actions: StoreActions,
) {
    SettingsScreen(state = state, actions = actions)
}
