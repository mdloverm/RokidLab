package com.rokidlab.phone.hid

import com.rokidlab.phone.app.LabApplication
import com.rokidlab.phone.design.RokidLabTheme
import com.rokidlab.phone.design.BrewBg
import com.rokidlab.phone.design.BrewCoral
import com.rokidlab.phone.design.BrewWarning
import com.rokidlab.phone.design.BrewPurple
import com.rokidlab.phone.design.BrewCyan
import com.rokidlab.phone.design.BrewPanel
import com.rokidlab.phone.design.BrewPanelHi
import com.rokidlab.phone.design.BrewGreen
import com.rokidlab.phone.design.BrewSuccess
import com.rokidlab.phone.design.BrewMuted
import com.rokidlab.phone.design.BrewDim
import com.rokidlab.phone.design.BrewText
import com.rokidlab.phone.design.BrewBorder
import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

// ===== Main Composable =====
@Composable
private fun GamepadMain(hidManager: BluetoothHidManager, prefs: SharedPreferences, onExit: () -> Unit) {
    var pressedKeys by remember { mutableStateOf(setOf<Int>()) }
    var editMode by remember { mutableStateOf(false) }
    val connectedDevice = hidManager.connectedDevice
    var activeTab by remember { mutableIntStateOf(TAB_GAMEPAD) }

    // 加载保存的位置 (归一化坐标 0~1)
    val savedPos = remember { mutableStateMapOf<String, Offset>() }
    LaunchedEffect(Unit) {
        ALL_BTNS.forEach { b ->
            val k = PREF_PREFIX + b.label
            val x = prefs.getFloat("${k}_x", -1f)
            val y = prefs.getFloat("${k}_y", -1f)
            if (x >= 0 && y >= 0) savedPos[b.label] = Offset(x, y)
        }
    }

    Box(Modifier.fillMaxSize().background(BrewBg)) {
        // ── 顶部栏 ──
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).align(Alignment.TopCenter),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
        ) {
            // 返回按钮
            Text("← 返回", color = BrewMuted, fontSize = 12.sp,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(BrewPanel)
                    .clickable { onExit() }.padding(horizontal = 10.dp, vertical = 6.dp))

            Text("${connectedDevice?.name ?: "手柄"} 已连接", color = BrewSuccess, fontSize = 12.sp, fontWeight = FontWeight.Bold)

            // 编辑/自定义按钮 (仅手柄模式)
            if (activeTab == TAB_GAMEPAD) {
                Text(if (editMode) "✓ 完成" else "自定义",
                    color = if (editMode) BrewGreen else BrewCyan, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp))
                        .background(if (editMode) BrewGreen.copy(alpha = 0.15f) else BrewPanel)
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
            TabChip("🎮 手柄", TAB_GAMEPAD, activeTab) { activeTab = TAB_GAMEPAD; editMode = false }
            TabChip("🖱 鼠标", TAB_MOUSE, activeTab) { activeTab = TAB_MOUSE; editMode = false }
        }

        // ── 内容区域 ──
        when (activeTab) {
            TAB_GAMEPAD -> {
                // 原手柄按键布局
                GamepadButtons(ALL_BTNS, savedPos, editMode, pressedKeys,
                    onDown = { bitIndex ->
                        pressedKeys = pressedKeys.plus(bitIndex)
                        hidManager.sendButtons(null, pressedKeys)
                    },
                    onUp = { bitIndex ->
                        pressedKeys = pressedKeys.minus(bitIndex)
                        if (pressedKeys.isEmpty()) hidManager.sendRelease(null)
                    },
                    onPositionSave = { label, norm ->
                        savedPos[label] = norm
                        val k = PREF_PREFIX + label
                        prefs.edit().putFloat("${k}_x", norm.x).putFloat("${k}_y", norm.y).apply()
                    },
                )

                // 编辑模式底部面板
                if (editMode) {
                    Column(
                        Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text("拖拽按键以调整位置", color = BrewCyan, fontSize = 11.sp)
                    }
                }
            }

            TAB_MOUSE -> {
                // 鼠标模式 — 触控板
                MouseTouchpad(hidManager)
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
        Modifier.clip(RoundedCornerShape(10.dp)).background(bg)
            .border(if (isActive) 1.dp else 0.dp, BrewBorder, RoundedCornerShape(10.dp))
            .clickable { onClick() }.padding(horizontal = 18.dp, vertical = 7.dp),
    ) {
        Text(label, color = tc, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
    Spacer(Modifier.width(6.dp))
}

// ===== 鼠标触控板 =====
@Composable
private fun MouseTouchpad(hidManager: BluetoothHidManager) {
    val sensitivity = 2.5f  // 灵敏度: 每像素移动数

    Box(Modifier.fillMaxSize()) {
        // ── 触控区域 ──
        Box(
            Modifier.fillMaxSize().padding(top = 80.dp, bottom = 80.dp, start = 20.dp, end = 20.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier.fillMaxSize()
                    .clip(RoundedCornerShape(24.dp))
                    .background(BrewPanel)
                    .border(1.dp, BrewBorder, RoundedCornerShape(24.dp))
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { },
                            onDragEnd = { },
                            onDragCancel = { },
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
                    Text("触控板区域", color = BrewDim, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text("滑动移动光标 · 点击左键 · 双击双击", color = BrewMuted, fontSize = 11.sp)
                }
            }
        }

        // ── 底部操作按钮 ──
        Row(
            Modifier.fillMaxWidth().padding(bottom = 16.dp).align(Alignment.BottomCenter),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            MouseActionBtn("左键", BrewCyan) {
                hidManager.sendMouseClick(null, button = 1)
            }
            MouseActionBtn("右键", BrewWarning) {
                hidManager.sendMouseClick(null, button = 2)
            }
            MouseActionBtn("中键", BrewPurple) {
                hidManager.sendMouseClick(null, button = 3)
            }
        }
    }
}

@Composable
private fun MouseActionBtn(label: String, color: Color, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(10.dp)).background(color.copy(alpha = 0.15f))
            .border(1.dp, color.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
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
    val shape = if (isDpad) RoundedCornerShape(8.dp) else CircleShape
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.80f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessHigh), label = "s",
    )
    val bgA = if (pressed) 0.9f else if (editMode) 0.35f else 0.20f
    val bdA = if (pressed) 1f else if (editMode) 0.7f else 0.35f
    val tc = if (pressed) Color.White else color

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
        if (editMode) Text("↕", color = Color.White.copy(alpha = 0.4f), fontSize = 8.sp, modifier = Modifier.align(Alignment.TopEnd).padding(2.dp))
    }
}
