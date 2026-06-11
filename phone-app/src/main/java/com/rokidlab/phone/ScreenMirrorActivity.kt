package com.rokidlab.phone

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import java.io.File
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class ScreenMirrorActivity : ComponentActivity() {
    companion object {
        private const val TAG = "ScreenMirror"
        private const val GLASSES_ADB_PORT = 5555

        fun createIntent(context: android.content.Context): android.content.Intent =
            android.content.Intent(context, ScreenMirrorActivity::class.java)
    }

    private var ipAddress by mutableStateOf("192.168.1.168")
    private var isConfigured by mutableStateOf(false)
    private var isStreaming by mutableStateOf(false)
    private var connectionStatus by mutableStateOf("")
    private var currentBitmap by mutableStateOf<Bitmap?>(null)
    private var connectionFailed by mutableStateOf(false)
    private var scale by mutableStateOf(1f)
    private var offsetX by mutableStateOf(0f)
    private var offsetY by mutableStateOf(0f)
    private var adbClient: AdbScreenMirrorClient? = null
    private var adbConnected by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 从 BrewApplication 读取 IP 地址
        val app = application as BrewApplication
        ipAddress = app.screenMirrorIp
        
        // 启动时自动开始连接
        isStreaming = true
        connectionStatus = "正在连接眼镜..."
        connectToGlasses()

        setContent {
            RokidLabTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                if (connectionFailed) {
                    ConnectionFailedUI(
                        status = connectionStatus,
                        onRetry = {
                            connectionFailed = false
                            connectionStatus = "正在连接眼镜..."
                            isStreaming = true
                            connectToGlasses()
                        },
                        onBack = {
                            disconnectAndFinish()
                        }
                    )
                } else {
                    val bmp = currentBitmap
                    val displayInfo = if (bmp != null) {
                        "${bmp.width}x${bmp.height}"
                    } else {
                        "无画面"
                    }
                    val fullStatus = "$connectionStatus [$displayInfo]"

                    ScreenMirrorUI(
                        bitmap = bmp,
                        status = fullStatus,
                        isStreaming = isStreaming,
                        scale = scale,
                        offsetX = offsetX,
                        offsetY = offsetY,
                        adbClient = adbClient,
                        glassesWidth = bmp?.width ?: 480,
                        glassesHeight = bmp?.height ?: 640,
                        onBack = { disconnectAndFinish() },
                        onScaleChange = { s, x, y ->
                            scale = s.coerceIn(0.5f, 4f)
                            offsetX = x
                            offsetY = y
                        },
                        onResetScale = {
                            scale = 1f
                            offsetX = 0f
                            offsetY = 0f
                        }
                    )
                }
                }
            }
        }
    }

    private fun connectToGlasses() {
        connectionStatus = "正在连接眼镜..."
        isStreaming = true

        Thread {
            val client = AdbScreenMirrorClient(this, ipAddress, GLASSES_ADB_PORT)
            adbClient = client

            val connected = client.connect { status ->
                runOnUiThread { connectionStatus = status }
            }

            if (connected) {
                runOnUiThread {
                    adbConnected = true
                    connectionStatus = "已连接，接收画面中..."
                }

                client.startStreaming(
                    onFrame = { bitmap ->
                        runOnUiThread {
                            currentBitmap = bitmap
                        }
                    },
                    onStatus = { status ->
                        runOnUiThread { connectionStatus = status }
                    }
                )
            } else {
                runOnUiThread {
                    if (!connectionStatus.contains("失败")) {
                        connectionStatus = "连接失败"
                    }
                    connectionFailed = true
                    isStreaming = false
                }
            }
        }.start()
    }

    override fun onResume() {
        super.onResume()
    }

    private fun disconnectAndFinish() {
        isStreaming = false
        adbClient?.disconnect()
        adbClient = null
        finish()
    }

    override fun onDestroy() {
        disconnectAndFinish()
        super.onDestroy()
    }
}

@Composable
private fun ConnectionFailedUI(status: String, onRetry: () -> Unit, onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "连接失败",
            color = Color(0xFFFF5722),
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 16.dp)
        )
        Text(
            text = status,
            color = BrewTextBright,
            fontSize = 16.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(bottom = 16.dp)
        )
        Text(
            text = "请检查：\n1. 眼镜已连接到同一 WiFi\n2. 眼镜 ADB 网络调试已开启（指示灯绿色）\n3. IP 地址输入正确",
            color = BrewText,
            fontSize = 14.sp,
            modifier = Modifier.padding(bottom = 32.dp)
        )
        Button(
            onClick = onRetry,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = BrewCoral)
        ) {
            Text("重试连接", color = BrewBg)
        }
        TextButton(
            onClick = onBack,
            modifier = Modifier.padding(top = 8.dp)
        ) {
            Text("返回设置", color = BrewTextBright)
        }
    }
}

@Composable
private fun ConfigUI(
    ipAddress: String,
    isInstallingScreenStream: Boolean,
    onIpChange: (String) -> Unit,
    onConnect: () -> Unit,
    onInstallScreenStream: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "连接眼镜屏幕",
            color = BrewTextBright,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 32.dp)
        )

        // 安装按钮（始终显示，去掉状态判断）
        if (!isInstallingScreenStream) {
            Button(
                onClick = onInstallScreenStream,
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = BrewCoral)
            ) {
                Text("安装 ScreenStream 到眼镜", color = BrewBg)
            }
        }

        // 安装中状态
        if (isInstallingScreenStream) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = BrewCoral,
                    strokeWidth = 2.dp
                )
                Text(
                    text = "正在安装 ScreenStream...",
                    color = BrewText,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(start = 12.dp)
                )
            }
        }

        OutlinedTextField(
            value = ipAddress,
            onValueChange = onIpChange,
            label = { Text("眼镜 IP 地址", color = BrewText) },
            textStyle = TextStyle(color = BrewTextBright),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            onClick = onConnect,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp),
            colors = ButtonDefaults.buttonColors(containerColor = BrewCoral)
        ) {
            Text("连接", color = BrewBg)
        }
    }
}

@Composable
private fun ScreenMirrorUI(
    bitmap: Bitmap?,
    status: String,
    isStreaming: Boolean,
    scale: Float,
    offsetX: Float,
    offsetY: Float,
    adbClient: AdbScreenMirrorClient?,
    glassesWidth: Int,
    glassesHeight: Int,
    onBack: () -> Unit,
    onScaleChange: (Float, Float, Float) -> Unit,
    onResetScale: () -> Unit
) {
    var containerWidth by remember { mutableIntStateOf(0) }
    var containerHeight by remember { mutableIntStateOf(0) }
    var lastTapTime by remember { mutableLongStateOf(0L) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { size ->
                containerWidth = size.width
                containerHeight = size.height
            }
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "眼镜屏幕",
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offsetX,
                        translationY = offsetY
                    ),
                contentScale = ContentScale.Fit
            )

            Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(glassesWidth, glassesHeight, containerWidth, containerHeight, scale, offsetX, offsetY) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val pointerId = down.id
                                val startPos = down.position
                                val thresholdPx = 24f * density
                                val doubleTapMs = 350L
                                var lastPos = startPos
                                var dragged = false
                                var multiFinger = false

                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Main)
                                    val changes = event.changes
                                    val anyPressed = changes.any { it.pressed }

                                    if (changes.size >= 2 && !multiFinger) {
                                        multiFinger = true
                                    }

                                    if (multiFinger) {
                                        if (changes.size >= 2) {
                                            val prevCentroid = changes.fold(Offset.Zero) { acc, c -> acc + c.previousPosition } / changes.size.toFloat()
                                            val centroid = changes.fold(Offset.Zero) { acc, c -> acc + c.position } / changes.size.toFloat()
                                            var zoom = 1f
                                            if (prevCentroid != Offset.Zero) {
                                                val prevDist = (changes[0].previousPosition - changes[1].previousPosition).getDistance()
                                                val curDist = (changes[0].position - changes[1].position).getDistance()
                                                if (prevDist > 0f) zoom = curDist / prevDist
                                            }
                                            val pan = centroid - prevCentroid
                                            val newScale = (scale * zoom).coerceIn(0.5f, 4f)
                                            onScaleChange(newScale, offsetX + pan.x, offsetY + pan.y)
                                            changes.forEach { it.consume() }
                                        } else if (!anyPressed) {
                                            break
                                        } else {
                                            changes.forEach { it.consume() }
                                        }
                                        continue
                                    }

                                    if (!anyPressed) {
                                        val upPos = changes.firstOrNull()?.position ?: lastPos
                                        if (!dragged) {
                                            val now = System.nanoTime()
                                            val sinceLast = if (lastTapTime > 0) (now - lastTapTime) / 1_000_000 else Long.MAX_VALUE
                                            if (sinceLast < doubleTapMs) {
                                                lastTapTime = 0L
                                                adbClient?.sendKeyEvent("KEYCODE_BACK")
                                            } else {
                                                lastTapTime = now
                                                val gx = mapToGlassesX(upPos.x, containerWidth, containerHeight, glassesWidth, glassesHeight, scale, offsetX, offsetY)
                                                val gy = mapToGlassesY(upPos.y, containerWidth, containerHeight, glassesWidth, glassesHeight, scale, offsetX, offsetY)
                                                if (gx in 0 until glassesWidth && gy in 0 until glassesHeight) {
                                                    adbClient?.sendTap(gx, gy)
                                                }
                                            }
                                        } else {
                                            val phoneDx = upPos.x - startPos.x
                                            val phoneDy = upPos.y - startPos.y
                                            if (kotlin.math.abs(phoneDx) > kotlin.math.abs(phoneDy) * 1.5f) {
                                                if (phoneDx > 0) {
                                                    adbClient?.sendKeyEvent("KEYCODE_DPAD_RIGHT")
                                                } else {
                                                    adbClient?.sendKeyEvent("KEYCODE_DPAD_LEFT")
                                                }
                                            } else if (kotlin.math.abs(phoneDy) > kotlin.math.abs(phoneDx) * 1.5f) {
                                                if (phoneDy > 0) {
                                                    adbClient?.sendKeyEvent("KEYCODE_DPAD_DOWN")
                                                } else {
                                                    adbClient?.sendKeyEvent("KEYCODE_DPAD_UP")
                                                }
                                            }
                                        }
                                        break
                                    }

                                    if (changes.size == 1) {
                                        val c = changes.first()
                                        if (c.id == pointerId) {
                                            lastPos = c.position
                                            if ((lastPos - startPos).getDistance() > thresholdPx) {
                                                dragged = true
                                            }
                                        }
                                    }
                                }
                            }
                        }
                )

        } else {
            Text(
                text = status,
                color = BrewTextBright,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .background(BrewPanel.copy(alpha = 0.8f), CircleShape)
                ) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回", tint = BrewTextBright)
                }
                Text(
                    text = status,
                    color = BrewTextBright,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 16.dp)
                )
                TextButton(
                    onClick = onResetScale,
                    modifier = Modifier.background(BrewPanel.copy(alpha = 0.8f), CircleShape)
                ) {
                    Text("重置", color = BrewTextBright)
                }
            }

        }
    }
}

private fun mapToGlassesX(
    phoneX: Float,
    containerWidth: Int,
    containerHeight: Int,
    glassesWidth: Int,
    glassesHeight: Int,
    scale: Float,
    offsetX: Float,
    offsetY: Float
): Int {
    if (glassesWidth <= 0 || glassesHeight <= 0 || containerWidth <= 0 || containerHeight <= 0) return -1
    val imageAspect = glassesWidth.toFloat() / glassesHeight.toFloat()
    val containerAspect = containerWidth.toFloat() / containerHeight.toFloat()
    val (drawW, drawH) = if (containerAspect > imageAspect) {
        (containerHeight * imageAspect) to containerHeight.toFloat()
    } else {
        containerWidth.toFloat() to (containerWidth / imageAspect)
    }
    val offsetXOnScreen = (containerWidth - drawW) / 2f
    val offsetYOnScreen = (containerHeight - drawH) / 2f
    val xOnImage = (phoneX - offsetXOnScreen - offsetX) / scale
    return (xOnImage / drawW * glassesWidth).toInt().coerceIn(0, glassesWidth - 1)
}

private fun mapToGlassesY(
    phoneY: Float,
    containerWidth: Int,
    containerHeight: Int,
    glassesWidth: Int,
    glassesHeight: Int,
    scale: Float,
    offsetX: Float,
    offsetY: Float
): Int {
    if (glassesWidth <= 0 || glassesHeight <= 0 || containerWidth <= 0 || containerHeight <= 0) return -1
    val imageAspect = glassesWidth.toFloat() / glassesHeight.toFloat()
    val containerAspect = containerWidth.toFloat() / containerHeight.toFloat()
    val (drawW, drawH) = if (containerAspect > imageAspect) {
        (containerHeight * imageAspect) to containerHeight.toFloat()
    } else {
        containerWidth.toFloat() to (containerWidth / imageAspect)
    }
    val offsetXOnScreen = (containerWidth - drawW) / 2f
    val offsetYOnScreen = (containerHeight - drawH) / 2f
    val yOnImage = (phoneY - offsetYOnScreen - offsetY) / scale
    return (yOnImage / drawH * glassesHeight).toInt().coerceIn(0, glassesHeight - 1)
}
