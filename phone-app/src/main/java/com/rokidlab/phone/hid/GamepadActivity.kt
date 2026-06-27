package com.rokidlab.phone.hid

import com.rokidlab.phone.app.LabApplication
import com.rokidlab.phone.design.*
import com.rokidlab.phone.R
import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.*
import kotlin.math.*
import kotlin.math.roundToInt

private const val PREFS_NAME = "gamepad_layout"
private const val PREF_PREFIX = "btn_"

// ===== Colors =====
private val C_AB   = BrewCoral
private val C_C    = BrewWarning
private val C_XYZ  = BrewPurple
private val C_LR   = BrewCyan
private val C_DPAD = BrewPanelHi
private val C_SYS  = BrewMuted

// ===== Key Data =====
private data class GBtn(val label: String, val bitIndex: Int, val color: Color, val dx: Float, val dy: Float)

private val ALL_BTNS = listOf(
    GBtn("L", BluetoothHidManager.KEY_L,   C_LR,    0.15f, 0.12f),
    GBtn("R", BluetoothHidManager.KEY_R,   C_LR,    0.85f, 0.12f),
    GBtn("↑", BluetoothHidManager.KEY_UP,   C_DPAD, 0.18f, 0.40f),
    GBtn("↓", BluetoothHidManager.KEY_DOWN, C_DPAD, 0.18f, 0.58f),
    GBtn("←", BluetoothHidManager.KEY_LEFT, C_DPAD, 0.10f, 0.49f),
    GBtn("→", BluetoothHidManager.KEY_RIGHT,C_DPAD, 0.26f, 0.49f),
    GBtn("A", BluetoothHidManager.KEY_A,   C_AB,    0.70f, 0.40f),
    GBtn("B", BluetoothHidManager.KEY_B,   C_AB,    0.78f, 0.49f),
    GBtn("C", BluetoothHidManager.KEY_C,   C_C,     0.62f, 0.49f),
    GBtn("X", BluetoothHidManager.KEY_X,   C_XYZ,   0.70f, 0.58f),
    GBtn("Y", BluetoothHidManager.KEY_Y,   C_XYZ,   0.78f, 0.67f),
    GBtn("Z", BluetoothHidManager.KEY_Z,   C_XYZ,   0.62f, 0.67f),
    GBtn("Select", BluetoothHidManager.KEY_SELECT, C_SYS, 0.40f, 0.52f),
    GBtn("Start",  BluetoothHidManager.KEY_START,  C_SYS, 0.52f, 0.52f),
)

/** 功能键列表（不含方向键），用于摇杆模式 */
private val ALL_BTNS_FUNC_ONLY = listOf(
    GBtn("L", BluetoothHidManager.KEY_L,   C_LR,    0.15f, 0.12f),
    GBtn("R", BluetoothHidManager.KEY_R,   C_LR,    0.85f, 0.12f),
    GBtn("A", BluetoothHidManager.KEY_A,   C_AB,    0.70f, 0.40f),
    GBtn("B", BluetoothHidManager.KEY_B,   C_AB,    0.78f, 0.49f),
    GBtn("C", BluetoothHidManager.KEY_C,   C_C,     0.62f, 0.49f),
    GBtn("X", BluetoothHidManager.KEY_X,   C_XYZ,   0.70f, 0.58f),
    GBtn("Y", BluetoothHidManager.KEY_Y,   C_XYZ,   0.78f, 0.67f),
    GBtn("Z", BluetoothHidManager.KEY_Z,   C_XYZ,   0.62f, 0.67f),
    GBtn("Select", BluetoothHidManager.KEY_SELECT, C_SYS, 0.40f, 0.52f),
    GBtn("Start",  BluetoothHidManager.KEY_START,  C_SYS, 0.52f, 0.52f),
)

// ===== Activity =====
@SuppressLint("MissingPermission")
class GamepadActivity : ComponentActivity() {
    private lateinit var prefs: SharedPreferences
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        setContent { RokidLabTheme { GamepadMain((application as LabApplication).hidManager, prefs, { finish() }) } }
    }
    override fun onDestroy() { super.onDestroy(); window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
}

// ===== 模式枚举 =====
private const val TAB_GAMEPAD = 0
private const val TAB_MOUSE   = 1

/** 控制模式: D-Pad 方向键 / 360° 摇杆 */
private enum class ControlMode { DPAD, JOYSTICK }

// ===== Main Composable =====
@Composable
private fun GamepadMain(hidManager: BluetoothHidManager, prefs: SharedPreferences, onExit: () -> Unit) {
    val ctx = LocalContext.current
    var pressedKeys by remember { mutableStateOf(setOf<Int>()) }
    var editMode by remember { mutableStateOf(false) }
    val connectedDevice = hidManager.connectedDevice
    var activeTab by remember { mutableIntStateOf(TAB_GAMEPAD) }

    // 加载保存的位置 (归一化坐标 0~1)
    val savedPos = remember { mutableStateMapOf<String, Offset>() }
    // 摇杆位置单独管理
    val joystickPosKey = "JOYSTICK"
    LaunchedEffect(Unit) {
        // 加载所有按钮位置
        ALL_BTNS.forEach { b ->
            val k = PREF_PREFIX + b.label
            val x = prefs.getFloat("${k}_x", -1f)
            val y = prefs.getFloat("${k}_y", -1f)
            if (x >= 0 && y >= 0) savedPos[b.label] = Offset(x, y)
        }
        // 加载摇杆位置（默认 D-Pad 中心 ~0.18, 0.49）
        val jx = prefs.getFloat("${PREF_PREFIX}${joystickPosKey}_x", -1f)
        val jy = prefs.getFloat("${PREF_PREFIX}${joystickPosKey}_y", -1f)
        savedPos[joystickPosKey] = if (jx >= 0 && jy >= 0) Offset(jx, jy) else Offset(0.18f, 0.49f)
    }

    /** 保存位置到 SharedPreferences */
    fun savePos(label: String, norm: Offset) {
        savedPos[label] = norm
        val k = PREF_PREFIX + label
        prefs.edit().putFloat("${k}_x", norm.x).putFloat("${k}_y", norm.y).apply()
    }

    Box(Modifier.fillMaxSize().background(BrewBg)) {
        // ── 顶部栏（可横向滚动） ──
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).align(Alignment.TopCenter).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            // 返回按钮
            Text(ctx.getString(R.string.back_label), color = BrewMuted, fontSize = 12.sp,
                modifier = Modifier.clip(BrewShapeSmall).background(BrewPanel)
                    .clickable { onExit() }.padding(horizontal = 10.dp, vertical = 6.dp))

            Text(ctx.getString(R.string.controller_connected, connectedDevice?.name ?: ctx.getString(R.string.gamepad_tab)), color = BrewSuccess, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp), maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)

            // 编辑/自定义按钮 (仅手柄模式)
            if (activeTab == TAB_GAMEPAD) {
                Text(if (editMode) ctx.getString(R.string.done_edit) else ctx.getString(R.string.customize),
                    color = if (editMode) BrewCoral else BrewCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(BrewShapeSmall)
                        .background(if (editMode) BrewCoral.copy(alpha = 0.15f) else BrewPanel)
                        .clickable { editMode = !editMode }.padding(horizontal = 10.dp, vertical = 6.dp))

            } else {
                Spacer(Modifier.width(1.dp)) // 占位
            }
        }

        // ── 模式切换 Tab ──
        Row(
            Modifier.fillMaxWidth().padding(top = 40.dp).align(Alignment.TopCenter),
            horizontalArrangement = Arrangement.Center,
        ) {
            TabChip(ctx.getString(R.string.gamepad_tab), TAB_GAMEPAD, activeTab) { activeTab = TAB_GAMEPAD; editMode = false }
            TabChip(ctx.getString(R.string.mouse_tab), TAB_MOUSE, activeTab) { activeTab = TAB_MOUSE; editMode = false }
        }

        // ── 控制模式（仅在 Gamepad Tab 中生效） ──
        var controlMode by remember { mutableStateOf(ControlMode.DPAD) }
        // 当前方向键按下状态（用于 D-Pad 模式）
        var pressedKeys by remember { mutableStateOf(setOf<Int>()) }
        // 摇杆当前方向键（用于 Joystick 模式，独立管理）
        var joystickKeys by remember { mutableStateOf(setOf<Int>()) }

        // ── 内容区域 ──
        when (activeTab) {
            TAB_GAMEPAD -> {
                if (controlMode == ControlMode.DPAD) {
                    // 原手柄按键布局（含 D-Pad 方向键）
                    GamepadButtons(ALL_BTNS, savedPos, editMode, pressedKeys,
                        onDown = { bitIndex ->
                            pressedKeys = pressedKeys.plus(bitIndex)
                            hidManager.sendButtons(null, pressedKeys + joystickKeys)
                        },
                        onUp = { bitIndex ->
                            pressedKeys = pressedKeys.minus(bitIndex)
                            val allNow = pressedKeys + joystickKeys
                            if (allNow.isEmpty()) hidManager.sendRelease(null)
                            else hidManager.sendButtons(null, allNow)
                        },
                        onPositionSave = { label, norm -> savePos(label, norm) },
                    )
                } else {
                    // 摇杆模式：只显示功能键（隐藏方向键）
                    GamepadButtons(ALL_BTNS_FUNC_ONLY, savedPos, editMode, pressedKeys,
                        onDown = { bitIndex ->
                            pressedKeys = pressedKeys.plus(bitIndex)
                            hidManager.sendButtons(null, pressedKeys + joystickKeys)
                        },
                        onUp = { bitIndex ->
                            pressedKeys = pressedKeys.minus(bitIndex)
                            val allNow = pressedKeys + joystickKeys
                            if (allNow.isEmpty()) hidManager.sendRelease(null)
                            else hidManager.sendButtons(null, allNow)
                        },
                        onPositionSave = { label, norm -> savePos(label, norm) },
                    )
                }

                // ── 控制模式切换按钮（位于 D-Pad / 摇杆下方） ──
                ModeToggleButton(
                    controlMode = controlMode,
                    onToggle = {
                        controlMode = if (controlMode == ControlMode.DPAD) ControlMode.JOYSTICK else ControlMode.DPAD
                        pressedKeys = emptySet()
                        joystickKeys = emptySet()
                        hidManager.sendRelease(null)
                    },
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 20.dp)
                )

                // ── 摇杆（仅在 JOYSTICK 模式显示，在方向键位置） ──
                if (controlMode == ControlMode.JOYSTICK) {
                    JoystickArea(
                        hidManager = hidManager,
                        savedPos = savedPos,
                        posKey = joystickPosKey,
                        editMode = editMode,
                        actionKeys = pressedKeys,  // 传入当前按下的功能键
                        onJoystickKeys = { keys -> joystickKeys = keys },
                        onPositionSave = { label, norm -> savePos(label, norm) },
                    )
                }

                // 编辑模式底部面板
                if (editMode) {
                    Column(
                        Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(ctx.getString(R.string.drag_to_reposition), color = BrewCyan, fontSize = 11.sp)
                    }
                }
            }

            TAB_MOUSE -> {
                // 鼠标模式 — 触控板
                MouseTouchpad(hidManager, prefs, ctx)
            }
        }
    }
}

// ===== 模式切换 Tab =====
@Composable
private fun TabChip(label: String, tab: Int, activeTab: Int, onClick: () -> Unit) {
    val isActive = tab == activeTab
    val bg = if (isActive) BrewPanel else Color.Transparent
    val tc = if (isActive) BrewText else BrewMuted
    Box(
        Modifier.clip(BrewShapeMedium).background(bg)
            .border(if (isActive) 1.dp else 0.dp, BrewBorder, BrewShapeMedium)
            .clickable { onClick() }.padding(horizontal = 18.dp, vertical = 7.dp),
    ) {
        Text(label, color = tc, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
    Spacer(Modifier.width(6.dp))
}

// ===== 鼠标触控板 =====
@Composable
private fun MouseTouchpad(hidManager: BluetoothHidManager, prefs: SharedPreferences, ctx: Context) {
    val sensitivity = 1.0f  // 灵敏度: 每像素移动数
    var showKeyboardDialog by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        // ── 触控区域 ──
        Box(
            Modifier.fillMaxSize().padding(top = 80.dp, bottom = 100.dp, start = 20.dp, end = 20.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier.fillMaxSize()
                    .clip(BrewShapeXLarge)
                    .background(BrewPanel)
                    .border(1.dp, BrewBorder, BrewShapeXLarge)
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = {
                                // 按下左键，仅按下不释放
                                hidManager.sendMouseButton(null, button = 1, pressed = true)
                            },
                            onDragEnd = {
                                // 释放左键
                                hidManager.sendMouseButton(null, button = 1, pressed = false)
                            },
                            onDragCancel = {
                                hidManager.sendMouseButton(null, button = 1, pressed = false)
                            },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                val dx = (dragAmount.x * sensitivity).roundToInt()
                                val dy = (dragAmount.y * sensitivity).roundToInt()
                                if (dx != 0 || dy != 0) {
                                    hidManager.sendMouseMove(null, dx, dy)
                                }
                            },
                        )
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = {
                                hidManager.sendMouseClick(null, button = 1)
                            },
                            onDoubleTap = {
                                hidManager.sendMouseClick(null, button = 1)
                            },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("🖱", fontSize = 36.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(ctx.getString(R.string.touchpad_area), color = BrewDim, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text(ctx.getString(R.string.touchpad_instructions), color = BrewMuted, fontSize = 11.sp)
                }
            }
        }

        // ── 底部操作按钮 ──
        Row(
            Modifier.fillMaxWidth().padding(bottom = 16.dp).align(Alignment.BottomCenter),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            MouseActionBtn(ctx.getString(R.string.left_button), BrewCyan) {
                hidManager.sendMouseClick(null, button = 1)
            }
            MouseActionBtn(ctx.getString(R.string.right_button), BrewWarning) {
                hidManager.sendMouseClick(null, button = 2)
            }
            MouseActionBtn(ctx.getString(R.string.middle_button), BrewPurple) {
                hidManager.sendMouseClick(null, button = 3)
            }
            MouseActionBtn(ctx.getString(R.string.keyboard_btn), BrewCoral) {
                showKeyboardDialog = true
            }
        }
    }

    // ── 键盘输入弹窗 ──
    if (showKeyboardDialog) {
        KeyboardInputDialog(hidManager, ctx) { showKeyboardDialog = false }
    }
}

// ===== 键盘输入弹窗 =====
@Composable
private fun KeyboardInputDialog(
    hidManager: BluetoothHidManager,
    ctx: Context,
    onDismiss: () -> Unit,
) {
    val app = ctx.applicationContext as com.rokidlab.phone.app.LabApplication
    var ip by remember { mutableStateOf(app.phoneMirrorIp.ifEmpty { app.screenMirrorIp }) }
    var text by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .clip(BrewShapeXLarge)
                .background(BrewBg)
                .border(1.dp, BrewBorder, BrewShapeXLarge)
                .padding(20.dp)
        ) {
            Text(ctx.getString(R.string.keyboard_dialog_title), color = BrewText, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))

            Spacer(Modifier.height(8.dp))

            // 文字输入
            Box(
                Modifier.fillMaxWidth()
                    .clip(BrewShapeMedium)
                    .background(BrewPanel)
                    .border(1.dp, BrewBorder, BrewShapeMedium)
                    .padding(horizontal = 12.dp, vertical = 2.dp)
            ) {
                androidx.compose.material3.TextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text(ctx.getString(R.string.keyboard_text_hint), color = BrewMuted, fontSize = 12.sp) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp),
                    colors = androidx.compose.material3.TextFieldDefaults.colors(
                        focusedTextColor = BrewText,
                        unfocusedTextColor = BrewText,
                        cursorColor = BrewCoral,
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                    ),
                    textStyle = TextStyle(color = BrewText, fontSize = 14.sp),
                )
            }
            Spacer(Modifier.height(4.dp))

            // 状态提示
            if (status.isNotEmpty()) {
                Text(status, color = if (status.startsWith("✅")) BrewSuccess else BrewWarning, fontSize = 12.sp)
                Spacer(Modifier.height(4.dp))
            }

            // 按钮
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Text(
                    ctx.getString(R.string.cancel),
                    color = BrewMuted, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(BrewShapeSmall).clickable { onDismiss() }.padding(horizontal = 16.dp, vertical = 8.dp)
                )
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier.clip(BrewShapeSmall).background(if (sending) BrewMuted.copy(alpha = 0.15f) else BrewCoral.copy(alpha = 0.15f))
                        .clickable(enabled = !sending) {
                            if (ip.isBlank()) { status = ctx.getString(R.string.keyboard_no_ip); return@clickable }
                            sending = true; status = ""
                            scope.launch(Dispatchers.IO) {
                                try {
                                    // 1. TCP 发送文字到眼镜（设剪贴板）
                                    val socket = java.net.Socket()
                                    socket.connect(java.net.InetSocketAddress(ip.trim(), 7656), 3000)
                                    socket.soTimeout = 5000
                                    socket.getOutputStream().write((text + "\n").toByteArray(Charsets.UTF_8))
                                    socket.getOutputStream().flush()
                                    val reader = java.io.BufferedReader(java.io.InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                                    reader.readLine()
                                    socket.close()
                                    Log.i("GamepadActivity", "Clipboard text sent via TCP ok")

                                    // 2. ADB Shell 执行粘贴（作为 shell 用户有 INJECT_EVENTS 权限）
                                    var adbOk = false
                                    try {
                                        val keyPair = com.rokidlab.phone.adb.AdbKeyManager
                                            .getOrCreateKeyPair(ctx.filesDir.absolutePath)
                                        adbOk = com.rokidlab.phone.adb.AdbPasteCompat
                                            .execPaste(ip.trim(), keyPair)
                                    } catch (e: Exception) {
                                        Log.w("GamepadActivity", "ADB paste failed: ${e.message}")
                                    }

                                    // 3. HID Ctrl+V 兜底（ADB 失败时）
                                    if (!adbOk) {
                                        Log.i("GamepadActivity", "Falling back to HID Ctrl+V")
                                        hidManager.sendCtrlV(null)
                                    }

                                    withContext(Dispatchers.Main) {
                                        status = ctx.getString(R.string.keyboard_sent_ok)
                                        text = ""
                                    }
                                } catch (e: Exception) {
                                    Log.e("GamepadActivity", "TCP send failed: ${e.message}", e)
                                    withContext(Dispatchers.Main) {
                                        status = ctx.getString(R.string.keyboard_send_failed, e.message ?: "unknown")
                                    }
                                } finally { sending = false }
                            }
                        }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Text(
                        if (sending) "..." else ctx.getString(R.string.keyboard_send),
                        color = if (sending) BrewMuted else BrewCoral,
                        fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

@Composable
private fun MouseActionBtn(label: String, color: Color, onClick: () -> Unit) {
    Box(
        Modifier.clip(BrewShapeMedium).background(color.copy(alpha = 0.15f))
            .border(1.dp, color.copy(alpha = 0.3f), BrewShapeMedium)
            .clickable { onClick() }
            .padding(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Text(label, color = color, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

// ===== 下方原手柄模式代码不变 =====

// ===== 按键容器 =====
@Composable
private fun GamepadButtons(
    buttons: List<GBtn>, savedPos: MutableMap<String, Offset>, editMode: Boolean, pressedKeys: Set<Int>,
    onDown: (Int) -> Unit, onUp: (Int) -> Unit, onPositionSave: (String, Offset) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val sw = with(density) { maxWidth.toPx() }
        val sh = with(density) { maxHeight.toPx() }
        val btnSz = (0.075f * sw)
        val btnDp = with(density) { btnSz.toDp() }

        buttons.forEach { b ->
            val norm = savedPos[b.label] ?: Offset(b.dx, b.dy)
            val cx = norm.x * sw; val cy = norm.y * sh
            val px = (cx - btnSz / 2).roundToInt(); val py = (cy - btnSz / 2).roundToInt()

            SingleButton(
                label = b.label, color = b.color, sizeDp = btnDp,
                pressed = b.bitIndex in pressedKeys, editMode = editMode,
                offsetX = px, offsetY = py,
                onDown = { onDown(b.bitIndex) }, onUp = { onUp(b.bitIndex) },
                onDrag = { totalDx, totalDy ->
                    val nx = (cx + totalDx) / sw
                    val ny = (cy + totalDy) / sh
                    onPositionSave(b.label, Offset(nx.coerceIn(0.02f, 0.98f), ny.coerceIn(0.02f, 0.98f)))
                },
            )
        }
    }
}

// ===== 单个按键 =====
@Composable
private fun SingleButton(
    label: String, color: Color, sizeDp: Dp, pressed: Boolean, editMode: Boolean,
    offsetX: Int, offsetY: Int, onDown: () -> Unit, onUp: () -> Unit, onDrag: (Float, Float) -> Unit,
) {
    val isDpad = label in "↑↓←→"
    val shape = if (isDpad) BrewShapeMedium else CircleShape
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.80f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessHigh), label = "s",
    )
    val bgA = if (pressed) 0.9f else if (editMode) 0.35f else 0.20f
    val bdA = if (pressed) 1f else if (editMode) 0.7f else 0.35f
    val tc = if (pressed) BrewTextBright else color

    val interactionSource = remember { MutableInteractionSource() }
    val ip by interactionSource.collectIsPressedAsState()
    LaunchedEffect(ip) { if (ip) onDown() else onUp() }

    Box(
        Modifier.offset { IntOffset(offsetX, offsetY) }.size(sizeDp).scale(scale)
            .clip(shape).background(color.copy(alpha = bgA), shape)
            .border(1.5.dp, color.copy(alpha = bdA), shape)
            .then(
                if (editMode) Modifier.pointerInput(label) {
                    var accX = 0f; var accY = 0f
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        accX += dragAmount.x; accY += dragAmount.y
                        onDrag(accX, accY)
                    }
                } else Modifier.clickable(interactionSource, null) { }
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = tc, fontSize = if (isDpad) 16.sp else 12.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        if (editMode) Text("↕", color = BrewTextBright.copy(alpha = 0.4f), fontSize = 8.sp, modifier = Modifier.align(Alignment.TopEnd).padding(2.dp))
    }
}

// ===== 控制模式切换按钮 =====
@Composable
private fun ModeToggleButton(controlMode: ControlMode, onToggle: () -> Unit, modifier: Modifier) {
    val ctx = LocalContext.current
    val dpadLabel = ctx.getString(R.string.dpad_mode)
    val joystickLabel = ctx.getString(R.string.joystick_mode)
    val label = when (controlMode) {
        ControlMode.DPAD -> "✓ $dpadLabel  |  $joystickLabel"
        ControlMode.JOYSTICK -> "$dpadLabel  |  $joystickLabel ✓"
    }
    val bgColor = if (controlMode == ControlMode.DPAD) BrewPanel else BrewCoral.copy(alpha = 0.15f)
    val txtColor = if (controlMode == ControlMode.DPAD) BrewCyan else BrewCoral
    Box(
        modifier.clip(BrewShapeMedium).background(bgColor)
            .border(1.dp, BrewBorder, BrewShapeMedium)
            .clickable { onToggle() }
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(label, color = txtColor, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

// ===== 360° 摇杆（可定位、可自定义移动位置） =====
@Composable
private fun JoystickArea(
    hidManager: BluetoothHidManager,
    savedPos: Map<String, Offset>,
    posKey: String,
    editMode: Boolean,
    actionKeys: Set<Int>,
    onJoystickKeys: (Set<Int>) -> Unit,
    onPositionSave: (String, Offset) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val sw = with(density) { maxWidth.toPx() }
        val sh = with(density) { maxHeight.toPx() }

        // 摇杆尺寸
        val joystickSize = 160.dp
        val innerSize = 60.dp
        val joystickPx = with(density) { joystickSize.toPx() }
        val innerPx = with(density) { innerSize.toPx() }

        // 摇杆位置（归一化 → 像素）
        val norm = savedPos[posKey] ?: Offset(0.18f, 0.49f)
        val cx = norm.x * sw; val cy = norm.y * sh

        // 摇杆偏移量（归一化 -1~1）
        var stickOffset by remember { mutableStateOf(Offset.Zero) }
        var angle by remember { mutableStateOf(0f) }
        var magnitude by remember { mutableStateOf(0f) }
        var isTouching by remember { mutableStateOf(false) }
        // 始终使用最新的 actionKeys，不让 LaunchedEffect 重启
        val currentActionKeys by rememberUpdatedState(actionKeys)

        // 摇杆协程——约 30fps 持续发送（方向键 + 功能键合并发送）
        LaunchedEffect(isTouching, angle, magnitude) {
            if (!isTouching) {
                hidManager.sendRelease(null)
                onJoystickKeys(emptySet())
                return@LaunchedEffect
            }
            while (isActive) {
                // 计算当前方向键
                val dirKeys = if (magnitude < 0.15f) emptySet()
                else hidManager.joystickAngleToKeys(angle)
                onJoystickKeys(dirKeys)
                // 合并方向键 + 当前按下的功能键一起发送
                hidManager.sendButtons(null, dirKeys + currentActionKeys)
                delay(33)
            }
        }

        // 摇杆容器的像素偏移
        val offsetX = (cx - joystickPx / 2f).roundToInt()
        val offsetY = (cy - joystickPx / 2f).roundToInt()

        Box(
            Modifier.offset { IntOffset(offsetX, offsetY) }.size(joystickSize),
            contentAlignment = Alignment.Center
        ) {
            // 外圈
            Canvas(Modifier.size(joystickSize)) {
                drawCircle(
                    color = BrewMuted.copy(alpha = 0.3f),
                    radius = size.minDimension / 2f,
                    style = Stroke(width = 2.dp.toPx()),
                )
                // 十字准线（45° 步进）
                val centerX = size.width / 2f; val centerY = size.height / 2f
                val r = size.minDimension / 2f - 4.dp.toPx()
                for (deg in 0 until 360 step 45) {
                    val rad = Math.toRadians(deg.toDouble())
                    val ex = centerX + (r * cos(rad)).toFloat()
                    val ey = centerY + (r * sin(rad)).toFloat()
                    drawLine(
                        color = BrewMuted.copy(alpha = 0.15f),
                        start = Offset(centerX, centerY),
                        end = Offset(ex, ey),
                        strokeWidth = 1.dp.toPx(),
                    )
                }
            }
            // 内圈（跟随手指位置）
            Box(
                Modifier
                    .offset {
                        val maxR = (joystickPx - innerPx) / 2f
                        IntOffset(
                            (stickOffset.x * maxR).roundToInt(),
                            (stickOffset.y * maxR).roundToInt(),
                        )
                    }
                    .size(innerSize)
                    .clip(CircleShape)
                    .background(
                        if (isTouching) BrewCoral.copy(alpha = 0.5f)
                        else BrewPanel
                    )
                    .border(1.5.dp, if (isTouching) BrewCoral else BrewBorder, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                if (!isTouching) {
                    Text("●", color = BrewDim.copy(alpha = 0.4f), fontSize = 20.sp)
                }
            }
            // 触摸/拖拽检测
            Box(
                Modifier
                    .size(joystickSize)
                    .pointerInput(editMode) {
                        if (editMode) {
                            // 编辑模式：拖拽移动位置
                            var accumX = 0f; var accumY = 0f
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                accumX += dragAmount.x; accumY += dragAmount.y
                                val newNorm = Offset(
                                    ((cx + accumX) / sw).coerceIn(0.02f, 0.98f),
                                    ((cy + accumY) / sh).coerceIn(0.02f, 0.98f),
                                )
                                onPositionSave(posKey, newNorm)
                            }
                        } else {
                            // 游戏模式：摇杆方向控制
                            detectDragGestures(
                                onDragStart = { offset ->
                                    isTouching = true
                                    val localCx = size.width / 2f; val localCy = size.height / 2f
                                    val dx = offset.x - localCx; val dy = offset.y - localCy
                                    val maxR = minOf(size.width, size.height) / 2f
                                    val rawDist = sqrt(dx * dx + dy * dy)
                                    val clampedDist = rawDist.coerceAtMost(maxR)
                                    // 按比例缩放，确保 stickOffset 不超出 [-1, 1]
                                    val scale = if (rawDist > 0f) clampedDist / rawDist else 0f
                                    stickOffset = Offset(dx * scale / maxR, dy * scale / maxR)
                                    angle = (Math.toDegrees(atan2(-dy.toDouble(), dx.toDouble()))).toFloat()
                                    magnitude = clampedDist / maxR
                                },
                                onDrag = { change, _ ->
                                    change.consume()
                                    val localCx = size.width / 2f; val localCy = size.height / 2f
                                    val dx = change.position.x - localCx; val dy = change.position.y - localCy
                                    val maxR = minOf(size.width, size.height) / 2f
                                    val rawDist = sqrt(dx * dx + dy * dy)
                                    val clampedDist = rawDist.coerceAtMost(maxR)
                                    val scale = if (rawDist > 0f) clampedDist / rawDist else 0f
                                    stickOffset = Offset(dx * scale / maxR, dy * scale / maxR)
                                    angle = (Math.toDegrees(atan2(-dy.toDouble(), dx.toDouble()))).toFloat()
                                    magnitude = clampedDist / maxR
                                },
                                onDragEnd = {
                                    isTouching = false; stickOffset = Offset.Zero
                                    angle = 0f; magnitude = 0f
                                    onJoystickKeys(emptySet()); hidManager.sendRelease(null)
                                },
                                onDragCancel = {
                                    isTouching = false; stickOffset = Offset.Zero
                                    angle = 0f; magnitude = 0f
                                    onJoystickKeys(emptySet()); hidManager.sendRelease(null)
                                },
                            )
                        }
                    },
            )
            // 编辑模式标记
            if (editMode) {
                Text("↕", color = BrewTextBright.copy(alpha = 0.4f), fontSize = 8.sp,
                    modifier = Modifier.align(Alignment.TopEnd).padding(2.dp))
            }
        }
    }
}
