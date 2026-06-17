package com.rokidlab.phone.mirror

import com.rokidlab.phone.app.*
import com.rokidlab.phone.adb.*
import com.rokidlab.phone.design.*
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.util.Log
import android.view.Surface
import android.view.TextureView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

class ScreenMirrorActivity : ComponentActivity() {
    companion object {
        private const val TAG = "ScreenMirror"
        private const val GLASSES_ADB_PORT = 5555

        fun createIntent(context: android.content.Context): android.content.Intent =
            android.content.Intent(context, ScreenMirrorActivity::class.java)
    }

    private var ipAddress by mutableStateOf("192.168.1.168")
    private var isStreaming by mutableStateOf(false)
    private var connectionStatus by mutableStateOf("")
    private var connectionFailed by mutableStateOf(false)
    private var scale by mutableStateOf(1f)
    private var offsetX by mutableStateOf(0f)
    private var offsetY by mutableStateOf(0f)
    private var adbClient by mutableStateOf<AdbScreenMirrorClient?>(null)
    private var streamDecoder: ScreenStreamDecoder? = null
    private var surface: Surface? = null
    private var glassesWidth by mutableIntStateOf(480)
    private var glassesHeight by mutableIntStateOf(640)
    @Volatile
    private var isDestroyed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val app = application as LabApplication
        ipAddress = app.screenMirrorIp

        isStreaming = true
        connectionStatus = "Starting mirror..."

        setContent {
            RokidLabTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    if (connectionFailed) {
                        ConnectionFailedUI(
                            status = connectionStatus,
                            onRetry = {
                                // Just reset flags and wait for TextureView to recreate → onSurfaceReady
                                connectionFailed = false
                                connectionStatus = "Starting mirror..."
                                isStreaming = true
                                // connectToGlasses() will be called by onSurfaceReady
                                // when the new TextureView's Surface is available
                            },
                            onBack = { disconnectAndFinish() }
                        )
                    } else {
                        ScreenMirrorUI(
                            status = connectionStatus,
                            isStreaming = isStreaming,
                            scale = scale,
                            offsetX = offsetX,
                            offsetY = offsetY,
                            adbClient = adbClient,
                            glassesWidth = glassesWidth,
                            glassesHeight = glassesHeight,
                            onBack = { disconnectAndFinish() },
                            onScaleChange = { s, x, y ->
                                scale = s.coerceIn(0.5f, 4f)
                                offsetX = x
                                offsetY = y
                            },
                            onSurfaceReady = { s ->
                                surface = s
                                connectToGlasses(s)
                            }
                        )
                    }
                }
            }
        }
    }

    private var isConnecting = false

    private fun connectToGlasses(surfaceOverride: Surface? = null) {
        if (isConnecting) return
        isConnecting = true
        val useSurface = surfaceOverride ?: surface
        connectionStatus = "Starting mirror..."
        isStreaming = true

        Thread {
            val client = AdbScreenMirrorClient(this, ipAddress, GLASSES_ADB_PORT)
            adbClient = client
            if (isDestroyed) { isConnecting = false; return@Thread }

            val connected = client.connect { status ->
                runOnUiThread { connectionStatus = status }
            }
            if (isDestroyed || !connected) {
                if (!connected) {
                    runOnUiThread {
                        connectionStatus = "Connect failed"
                        connectionFailed = true
                        isStreaming = false
                    }
                }
                isConnecting = false
                return@Thread
            }

            runOnUiThread { connectionStatus = "Connected, receiving stream..." }

            if (useSurface != null) {
                // H.264 硬件解码模式 - 使用 scrcpy-server
                val decoder = ScreenStreamDecoder(useSurface).also {
                    it.onVideoSizeChanged = { w, h ->
                        runOnUiThread {
                            glassesWidth = w
                            glassesHeight = h
                            Log.i(TAG, "video size: ${w}x${h}")
                        }
                    }
                }
                streamDecoder = decoder
                decoder.start()
                Log.i(TAG, "H.264 decoder created")

                // 连接后的 ADB 端 shell 命令错误等重置
                connectionStatus = "Waiting for H.264 stream..."

                client.startH264Streaming(decoder) { status ->
                    runOnUiThread {
                        connectionStatus = status
                        if (status.contains("fail") || status.contains("interrupt")) {
                            connectionFailed = true
                            isStreaming = false
                        }
                    }
                }
            } else {
                // 降级：screencap 原始像素模式
                client.startStreaming(
                    onFrame = { bitmap ->
                        runOnUiThread { /* bitmap 被忽略 - TextureView 直接显示 */ }
                    },
                    onStatus = { status ->
                        runOnUiThread {
                            connectionStatus = status
                            if (status.contains("fail") || status.contains("interrupt")) {
                                connectionFailed = true
                                isStreaming = false
                            }
                        }
                    }
                )
            }
            isConnecting = false
        }.apply {
            name = "mirror-connector"
            start()
        }
    }

    override fun onDestroy() {
        isDestroyed = true
        isStreaming = false
        streamDecoder?.stop()
        streamDecoder = null
        adbClient?.disconnect()
        adbClient = null
        super.onDestroy()
    }

    private fun disconnectAndFinish() {
        isStreaming = false
        streamDecoder?.stop()
        streamDecoder = null
        adbClient?.disconnect()
        adbClient = null
        finish()
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
            text = "Connect failed",
            color = BrewRed,
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
            text = "Check:\n1. Glasses connected to same WiFi\n2. ADB over WiFi enabled (green LED)\n3. IP address correct\n4. Screen recording permission granted on glasses",
            color = BrewText,
            fontSize = 14.sp,
            modifier = Modifier.padding(bottom = 32.dp)
        )
        Button(
            onClick = onRetry,
            modifier = Modifier.fillMaxWidth(),
            shape = BrewShapeStandard,
            colors = ButtonDefaults.buttonColors(
                containerColor = BrewCoral,
                contentColor = BrewTextBright
            )
        ) {
            Text("Reconnect")
        }
        TextButton(
            onClick = onBack,
            modifier = Modifier.padding(top = 8.dp)
        ) {
            Text("Back", color = BrewTextBright)
        }
    }
}

@Composable
private fun ScreenMirrorUI(
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
    onSurfaceReady: (Surface) -> Unit,
) {
    var containerWidth by remember { mutableIntStateOf(0) }
    var containerHeight by remember { mutableIntStateOf(0) }
    var lastTapTime by remember { mutableLongStateOf(0L) }
    var surfaceReady by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { size ->
                containerWidth = size.width
                containerHeight = size.height
            }
    ) {
        // TextureView 用于 MediaCodec 零拷贝渲染
        AndroidView(
            factory = { ctx ->
                TextureView(ctx).apply {
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(
                            surfaceTexture: SurfaceTexture,
                            width: Int,
                            height: Int
                        ) {
                            val surface = Surface(surfaceTexture)
                            surfaceReady = true
                            onSurfaceReady(surface)
                        }

                        override fun onSurfaceTextureSizeChanged(
                            st: SurfaceTexture,
                            w: Int,
                            h: Int
                        ) = Unit

                        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                            surfaceReady = false
                            return true
                        }

                        override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
                    }
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offsetX,
                    translationY = offsetY
                )
        )

        // 加载中或画面未就绪时显示状态文字
        if (!surfaceReady || status.contains("Connecting") || status.contains("scrcpy") || status.contains("tunnel")) {
            Text(
                text = status,
                color = BrewTextBright,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        // 触摸事件
        if (surfaceReady) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(glassesWidth, glassesHeight, containerWidth, containerHeight, scale, offsetX, offsetY) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
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
                                        val c0 = changes[0]
                                        val c1 = changes[1]
                                        val prevCentroid = changes.fold(Offset.Zero) { acc, c -> acc + c.previousPosition } / changes.size.toFloat()
                                        val centroid = changes.fold(Offset.Zero) { acc, c -> acc + c.position } / changes.size.toFloat()
                                        var zoom = 1f
                                        if (prevCentroid != Offset.Zero) {
                                            val prevDist = (c0.previousPosition - c1.previousPosition).getDistance()
                                            val curDist = (c0.position - c1.position).getDistance()
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
                                            Log.i("ScreenMirror", "double tap back")
                                            adbClient?.sendKeyEvent("KEYCODE_BACK")
                                        } else {
                                            lastTapTime = now
                                            val gx = mapToGlassesX(upPos.x, containerWidth, containerHeight, glassesWidth, glassesHeight, scale, offsetX, offsetY)
                                            val gy = mapToGlassesY(upPos.y, containerWidth, containerHeight, glassesWidth, glassesHeight, scale, offsetX, offsetY)
                                            Log.i("ScreenMirror", "finger up: pos=${upPos.x.toInt()},${upPos.y.toInt()} -> glasses=$gx,$gy")
                                            if (gx in 0 until glassesWidth && gy in 0 until glassesHeight) {
                                                adbClient?.sendTap(gx, gy)
                                            }
                                        }
                                    } else {
                                        val phoneDx = upPos.x - startPos.x
                                        val phoneDy = upPos.y - startPos.y
                                        if (kotlin.math.abs(phoneDx) > kotlin.math.abs(phoneDy) * 1.5f) {
                                            if (phoneDx > 0) adbClient?.sendKeyEvent("KEYCODE_DPAD_RIGHT")
                                            else adbClient?.sendKeyEvent("KEYCODE_DPAD_LEFT")
                                        } else if (kotlin.math.abs(phoneDy) > kotlin.math.abs(phoneDx) * 1.5f) {
                                            if (phoneDy > 0) adbClient?.sendKeyEvent("KEYCODE_DPAD_DOWN")
                                            else adbClient?.sendKeyEvent("KEYCODE_DPAD_UP")
                                        }
                                    }
                                    break
                                }
                                if (changes.size == 1) {
                                    val c = changes.first()
                                    if (c.id == down.id) {
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
        }

        // 顶部工具栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .align(Alignment.TopStart),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .background(BrewPanel.copy(alpha = 0.8f), CircleShape)
            ) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", tint = BrewTextBright)
            }
            Text(
                text = status,
                color = BrewTextBright,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 16.dp)
            )
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
