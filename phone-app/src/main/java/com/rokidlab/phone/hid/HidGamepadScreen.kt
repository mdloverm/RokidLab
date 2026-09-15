package com.rokidlab.phone.hid

import com.rokidlab.phone.design.*
import com.rokidlab.phone.R
import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 读取蓝牙设备名：Android 12+ 未持有 BLUETOOTH_CONNECT 时 [BluetoothDevice.getName]
 * 会抛 SecurityException，任何 UI 调用点都必须走这里，缺权限时退化为 MAC 地址。
 */
internal fun BluetoothDevice.safeName(): String? =
    runCatching { name }.getOrNull()

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

    // ── 蓝牙运行时权限（Android 12+）──────────────────────────────
    // debug 重装 / 新装后运行时权限会被清空，此时进入本页若直接调 getBondedDevices()
    // 会在 Composable 初始化阶段抛 SecurityException 整页崩溃（实测）。
    val btRuntimePerms = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else emptyArray()
    }
    fun hasBtPermission(): Boolean = btRuntimePerms.isEmpty() || btRuntimePerms.all {
        context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }
    var btPermissionGranted by remember { mutableStateOf(hasBtPermission()) }
    val btPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        // result 为空（用户直接关闭系统框）时 all{} 会误判为 true，必须先判非空
        btPermissionGranted = result.isNotEmpty() && result.values.all { it }
        if (btPermissionGranted) {
            bondedDevices = hidManager.getPairedDevices()
            hidManager.retryRegisterApp()
        }
    }

    // 进入页面时：缺权限则一次性弹系统授权框；同时确保 HID 已初始化
    LaunchedEffect(Unit) {
        if (!btPermissionGranted) {
            btPermissionLauncher.launch(btRuntimePerms)
        }
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
            Column(Modifier.weight(1f)) {
                Text(context.getString(R.string.nav_hid_gamepad), color = BrewPink, fontSize = 28.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp,
                    maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Text(context.getString(R.string.select_paired_device), color = BrewMuted, fontSize = 14.sp,
                    maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(8.dp))
            // 进入手柄控制按钮
            Box(
                Modifier.clip(BrewShapeMedium)
                    .background(if (connectedDevice != null) BrewCoral.copy(alpha = 0.2f) else BrewPanel)
                    .border(1.dp, if (connectedDevice != null) BrewCoral.copy(alpha = 0.4f) else BrewBorder, BrewShapeMedium)
                    .clickable(enabled = connectedDevice != null) {
                        context.startActivity(Intent(context, GamepadActivity::class.java))
                    }.padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(if (connectedDevice != null) context.getString(R.string.enter_control) else context.getString(R.string.not_connected),
                    color = if (connectedDevice != null) BrewCoral else BrewMuted,
                    fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
        }
        Spacer(Modifier.height(12.dp))

        // ── 连接状态 ──
        DeviceConnectionCard(connectionState, connectedDevice, isConnecting)
        Spacer(Modifier.height(12.dp))

        // ── 已配对设备列表 ──
        Text(context.getString(R.string.paired_devices), color = BrewDim, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        if (!btPermissionGranted) {
            // 缺蓝牙权限：给出说明 + 手动授权按钮（不弹第二次系统框，避免反复打扰）
            Column(
                Modifier.fillMaxWidth().clip(BrewShapeStandard)
                    .background(BrewWarning.copy(alpha = 0.08f))
                    .border(1.dp, BrewWarning.copy(alpha = 0.3f), BrewShapeStandard)
                    .padding(12.dp),
            ) {
                Text(context.getString(R.string.hid_permission_required),
                    color = BrewWarning, fontSize = 12.sp, lineHeight = 17.sp)
                Spacer(Modifier.height(10.dp))
                Box(
                    Modifier.clip(BrewShapeSmall).background(BrewWarning.copy(alpha = 0.18f))
                        .clickable { btPermissionLauncher.launch(btRuntimePerms) }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text(context.getString(R.string.authorize_btn),
                        color = BrewWarning, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        } else if (bondedDevices.isEmpty()) {
            Text(context.getString(R.string.no_paired_devices), color = BrewMuted, fontSize = 12.sp)
        } else {
            bondedDevices.forEach { device ->
                val name = device.safeName() ?: device.address ?: context.getString(R.string.unknown)
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
            Modifier.fillMaxWidth().clip(BrewShapeStandard)
                .background(if (connectedDevice != null) BrewCoral.copy(alpha = 0.2f) else BrewPanel)
                .border(1.dp, if (connectedDevice != null) BrewCoral.copy(alpha = 0.3f) else BrewBorder, BrewShapeStandard)
                .clickable(enabled = connectedDevice != null) {
                    context.startActivity(Intent(context, GamepadActivity::class.java))
                }.padding(vertical = 14.dp, horizontal = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(if (connectedDevice != null) context.getString(R.string.enter_gamepad_control) else context.getString(R.string.please_connect_first),
                color = if (connectedDevice != null) BrewCoral else BrewMuted,
                fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 2, textAlign = TextAlign.Center)
        }
    }
}

// ===== 子组件 =====

@Composable
private fun DeviceConnectionCard(state: Int, device: BluetoothDevice?, connecting: Boolean) {
    val ctx = LocalContext.current
    val (label, color) = when {
        connecting -> ctx.getString(R.string.connecting_status) to BrewWarning
        state == BluetoothHidManager.STATE_CONNECTED -> ctx.getString(R.string.connected) to BrewSuccess
        state == BluetoothHidManager.STATE_CONNECTING -> ctx.getString(R.string.connecting_dots) to BrewWarning
        state == BluetoothHidManager.STATE_RETRY_FAILED -> ctx.getString(R.string.reconnect_failed_restart) to BrewRed
        else -> ctx.getString(R.string.not_connected) to BrewMuted
    }
    val devName = device?.safeName() ?: device?.address ?: "—"
    Row(
        Modifier.fillMaxWidth().clip(BrewShapeStandard).background(BrewPanel)
            .border(1.dp, BrewBorder, BrewShapeStandard).padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(ctx.getString(R.string.connection_status_label), color = BrewDim, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(2.dp))
            Text(devName, color = BrewTextBright, fontSize = 14.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        Row(Modifier.clip(BrewShapeSmall).background(color.copy(alpha = 0.2f)).padding(horizontal = 10.dp, vertical = 4.dp)) {
            Text(label, color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun DeviceRow(name: String, address: String, isConnected: Boolean, connecting: Boolean, onClick: () -> Unit) {
    val ctx = LocalContext.current
    val actionColor = when {
        isConnected -> BrewCoral
        connecting -> BrewWarning.copy(alpha = 0.5f)
        else -> BrewCyan
    }
    val actionText = when {
        isConnected -> ctx.getString(R.string.connected_short)
        connecting -> "..."
        else -> ctx.getString(R.string.connect_short)
    }
    Row(
        Modifier.fillMaxWidth().clip(BrewShapeMedium)
            .background(if (isConnected) BrewCoral.copy(alpha = 0.08f) else Color.Transparent)
            .clickable(enabled = !connecting) { onClick() }
            .padding(vertical = 10.dp, horizontal = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(name, color = BrewText, fontSize = 13.sp)
            Text(address, color = BrewDim, fontSize = 9.sp)
        }
        if (isConnected || !connecting) {
            Box(Modifier.clip(BrewShapeSmall).background(actionColor.copy(alpha = 0.15f)).padding(horizontal = 10.dp, vertical = 4.dp)) {
                Text(actionText, color = actionColor, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
