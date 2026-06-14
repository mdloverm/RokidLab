package com.rokidlab.phone.hid

import com.rokidlab.phone.design.*
import android.bluetooth.BluetoothDevice
import android.content.Intent
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ===== HID 设备选择页面（手动连接） =====
@Composable
internal fun HidGamepadModule(
    hidManager: BluetoothHidManager,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var connectionState by remember { mutableIntStateOf(hidManager.connectionState) }
    var connectedDevice by remember { mutableStateOf(hidManager.connectedDevice) }
    var bondedDevices by remember { mutableStateOf(hidManager.getPairedDevices()) }
    var isConnecting by remember { mutableStateOf(false) }

    // 进入页面时确保 HID 已初始化
    LaunchedEffect(Unit) {
        hidManager.retryRegisterApp()
        hidManager.connectionEvents.collect { state ->
            connectionState = state
            connectedDevice = hidManager.connectedDevice
            if (state == BluetoothHidManager.STATE_DISCONNECTED && isConnecting) {
                isConnecting = false
            }
            if (state == BluetoothHidManager.STATE_RETRY_FAILED) {
                isConnecting = false
            }
            if (state == BluetoothHidManager.STATE_CONNECTED) {
                isConnecting = false
                bondedDevices = hidManager.getPairedDevices()
            }
        }
    }

    Column(
        modifier = modifier.fillMaxSize().statusBarsPadding().padding(16.dp),
    ) {
        // ── 标题 ──
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("蓝牙手柄", color = BrewGreen, fontSize = 32.sp, fontWeight = FontWeight.Black, letterSpacing = 4.sp)
                Spacer(Modifier.height(4.dp))
                Text("选择已配对的眼镜设备连接", color = BrewMuted, fontSize = 14.sp)
            }
            // 进入手柄控制按钮
            Box(
                Modifier.clip(RoundedCornerShape(8.dp))
                    .background(if (connectedDevice != null) BrewGreen.copy(alpha = 0.2f) else BrewPanel)
                    .border(1.dp, if (connectedDevice != null) BrewGreen.copy(alpha = 0.4f) else BrewBorder, RoundedCornerShape(8.dp))
                    .clickable(enabled = connectedDevice != null) {
                        context.startActivity(Intent(context, GamepadActivity::class.java))
                    }.padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(if (connectedDevice != null) "进入控制" else "未连接",
                    color = if (connectedDevice != null) BrewGreen else BrewMuted,
                    fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(12.dp))

        // ── 连接状态 ──
        DeviceConnectionCard(connectionState, connectedDevice, isConnecting)
        Spacer(Modifier.height(12.dp))

        // ── 已配对设备列表 ──
        Text("已配对的设备", color = BrewDim, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        if (bondedDevices.isEmpty()) {
            Text("无已配对的蓝牙设备", color = BrewMuted, fontSize = 12.sp)
        } else {
            bondedDevices.forEach { device ->
                val name = device.name ?: device.address ?: "未知"
                val isThisConnected = connectionState == BluetoothHidManager.STATE_CONNECTED &&
                    hidManager.connectedDevice?.address == device.address
                DeviceRow(name, device.address ?: "", isThisConnected, isConnecting) {
                    if (isThisConnected) {
                        hidManager.disconnect(device)
                    } else if (connectionState != BluetoothHidManager.STATE_CONNECTING) {
                        isConnecting = true
                        hidManager.connect(device)
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        // ── 底部进入手柄页面 ──
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                .background(if (connectedDevice != null) BrewGreen.copy(alpha = 0.2f) else BrewPanel)
                .border(1.dp, if (connectedDevice != null) BrewGreen.copy(alpha = 0.3f) else BrewBorder, RoundedCornerShape(12.dp))
                .clickable(enabled = connectedDevice != null) {
                    context.startActivity(Intent(context, GamepadActivity::class.java))
                }.padding(vertical = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(if (connectedDevice != null) "进入手柄控制" else "请先连接设备",
                color = if (connectedDevice != null) BrewGreen else BrewMuted,
                fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
    }
}

// ===== 子组件 =====

@Composable
private fun DeviceConnectionCard(state: Int, device: BluetoothDevice?, connecting: Boolean) {
    val (label, color) = when {
        connecting -> "正在连接..." to BrewWarning
        state == BluetoothHidManager.STATE_CONNECTED -> "已连接" to BrewSuccess
        state == BluetoothHidManager.STATE_CONNECTING -> "连接中..." to BrewWarning
        state == BluetoothHidManager.STATE_RETRY_FAILED -> "重连失败，请重启眼镜蓝牙" to BrewRed
        else -> "未连接" to BrewMuted
    }
    val devName = device?.name ?: device?.address ?: "—"
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(BrewPanel)
            .border(1.dp, BrewBorder, RoundedCornerShape(12.dp)).padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text("连接状态", color = BrewDim, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Text(devName, color = BrewTextBright, fontSize = 14.sp)
        }
        Box(Modifier.clip(RoundedCornerShape(6.dp)).background(color.copy(alpha = 0.2f)).padding(horizontal = 10.dp, vertical = 4.dp)) {
            Text(label, color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun DeviceRow(name: String, address: String, isConnected: Boolean, connecting: Boolean, onClick: () -> Unit) {
    val actionColor = when {
        isConnected -> BrewGreen
        connecting -> BrewWarning.copy(alpha = 0.5f)
        else -> BrewCyan
    }
    val actionText = when {
        isConnected -> "已连"
        connecting -> "..."
        else -> "连接"
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
            .background(if (isConnected) BrewGreen.copy(alpha = 0.08f) else Color.Transparent)
            .clickable(enabled = !connecting) { onClick() }
            .padding(vertical = 10.dp, horizontal = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(name, color = BrewText, fontSize = 13.sp)
            Text(address, color = BrewDim, fontSize = 9.sp)
        }
        if (isConnected || !connecting) {
            Box(Modifier.clip(RoundedCornerShape(4.dp)).background(actionColor.copy(alpha = 0.15f)).padding(horizontal = 10.dp, vertical = 4.dp)) {
                Text(actionText, color = actionColor, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
