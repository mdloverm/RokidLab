package com.rokidlab.phone.mirror

import com.rokidlab.phone.app.*
import com.rokidlab.phone.adb.*
import com.rokidlab.phone.connection.ConnectionRoute
import com.rokidlab.phone.design.*
import com.rokidlab.phone.R
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
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

class ScreenMirrorActivity : ComponentActivity() {
    companion object {
        fun createIntent(context: android.content.Context): android.content.Intent =
            android.content.Intent(context, ScreenMirrorActivity::class.java)
    }

    /**
     * L5 镜像会话状态（Phase 5：连接/串流状态机、Surface 兜底与生命周期从本 Activity 迁出）。
     * Compose 直接读其快照状态，本 Activity 只保留页面编排。
     */
    internal val mirrorSession by lazy { com.rokidlab.phone.feature.ScreenMirrorStateHolder(this) }

    /** L3 通道协调器（Phase 3：路由解析 + 蓝牙租约上收到 domain/MirrorCoordinator），供状态机使用 */
    internal val mirrorCoordinator by lazy { com.rokidlab.phone.domain.MirrorCoordinator(application as LabApplication) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 不在此处一次性读取眼镜 IP：可能晚于本 Activity 创建到达，
        // 在 onCreate 缓存会拿到默认值导致首次连接必败（改在真正连接时读最新值）。
        mirrorSession.init()

        setContent {
            RokidLabTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    if (mirrorSession.connectionFailed) {
                        ConnectionFailedUI(
                            status = mirrorSession.connectionStatus,
                            onRetry = { mirrorSession.retry() },
                            onBack = { mirrorSession.disconnectAndFinish() }
                        )
                    } else {
                        ScreenMirrorUI(
                            status = mirrorSession.connectionStatus,
                            isStreaming = mirrorSession.isStreaming,
                            startupPhase = mirrorSession.startupPhase,
                            scale = mirrorSession.scale,
                            offsetX = mirrorSession.offsetX,
                            offsetY = mirrorSession.offsetY,
                            adbClient = mirrorSession.adbClient,
                            glassesWidth = mirrorSession.glassesWidth,
                            glassesHeight = mirrorSession.glassesHeight,
                            onBack = { mirrorSession.disconnectAndFinish() },
                            onScaleChange = { s, x, y -> mirrorSession.applyScale(s, x, y) },
                            onSurfaceReady = { s -> mirrorSession.onSurfaceReady(s) }
                        )
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        mirrorSession.onActivityDestroy()
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
            text = stringResource(R.string.mirror_connection_failed),
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
            text = stringResource(R.string.mirror_check_list),
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
            Text(stringResource(R.string.reconnect))
        }
        TextButton(
            onClick = onBack,
            modifier = Modifier.padding(top = 8.dp)
        ) {
            Text(stringResource(R.string.back_btn), color = BrewTextBright)
        }
    }
}

@Composable
private fun ScreenMirrorUI(
    status: String,
    isStreaming: Boolean,
    startupPhase: Boolean,
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
    var localGlassesWidth by remember { mutableIntStateOf(480) }
    var localGlassesHeight by remember { mutableIntStateOf(640) }

    // pointerInput 的闭包不会因 adbClient 变化而重建，用 rememberUpdatedState 保证触摸回调拿到最新引用
    val currentAdbClient by rememberUpdatedState(adbClient)

    // 同步 glasses 尺寸到本地
    LaunchedEffect(glassesWidth, glassesHeight) {
        if (glassesWidth > 0 && glassesHeight > 0) {
            localGlassesWidth = glassesWidth
            localGlassesHeight = glassesHeight
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { size ->
                containerWidth = size.width
                containerHeight = size.height
            },
        contentAlignment = Alignment.Center,
    ) {
        // 使用 aspectRatio 让 TextureView 按视频比例缩放，自动居中
        // 避免手动 setTransform 被 graphicsLayer 干扰的问题
        AndroidView(
            factory = { ctx ->
                TextureView(ctx).apply {
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(
                            surfaceTexture: SurfaceTexture,
                            width: Int,
                            height: Int
                        ) {
                            Log.i("ScreenMirror", "surface available: ${width}x${height}")
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
                .aspectRatio(
                    // 如果本地尺寸无效，默认 480/640 = 0.75
                    if (localGlassesWidth > 0 && localGlassesHeight > 0)
                        localGlassesWidth.toFloat() / localGlassesHeight.toFloat()
                    else 0.75f
                )
                .graphicsLayer(
                    scaleX = scale,
                    scaleY = scale,
                    translationX = offsetX,
                    translationY = offsetY
                )
        )

        // 加载中或画面未就绪时显示状态文字。
        // startupPhase 覆盖「页面已打开但还没连上」的整段（启动宽限 / 路由解析 / 建链），
        // 这段时间 surface 其实已就绪，若不显式包含就会黑屏。
        if (startupPhase || !surfaceReady || status.contains("Connecting") || status.contains("scrcpy") || status.contains("tunnel")) {
            Text(
                text = status,
                color = BrewTextBright,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        // 触摸事件
        Log.i("ScreenMirror", "UI recompose: surfaceReady=$surfaceReady status=$status")
        if (surfaceReady) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(glassesWidth, glassesHeight, containerWidth, containerHeight, scale, offsetX, offsetY) {
                        Log.i("ScreenMirror", "pointerInput started: g=${glassesWidth}x${glassesHeight} c=${containerWidth}x${containerHeight}")
                        awaitEachGesture {
                            Log.i("ScreenMirror", "gesture started")
                            val down = awaitFirstDown(requireUnconsumed = false)
                            Log.i("ScreenMirror", "down at ${down.position}")
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
                                            currentAdbClient?.sendKeyEvent("KEYCODE_BACK")
                                        } else {
                                            lastTapTime = now
                                            val gx = mapToGlassesX(upPos.x, containerWidth, containerHeight, glassesWidth, glassesHeight, scale, offsetX, offsetY)
                                            val gy = mapToGlassesY(upPos.y, containerWidth, containerHeight, glassesWidth, glassesHeight, scale, offsetX, offsetY)
                                            Log.i("ScreenMirror", "finger up: pos=${upPos.x.toInt()},${upPos.y.toInt()} -> glasses=$gx,$gy")
                                            if (gx in 0 until glassesWidth && gy in 0 until glassesHeight) {
                                                currentAdbClient?.sendTap(gx, gy)
                                            }
                                        }
                                    } else {
                                        val phoneDx = upPos.x - startPos.x
                                        val phoneDy = upPos.y - startPos.y
                                        if (kotlin.math.abs(phoneDx) > kotlin.math.abs(phoneDy) * 1.5f) {
                                            if (phoneDx > 0) currentAdbClient?.sendKeyEvent("KEYCODE_DPAD_RIGHT")
                                            else currentAdbClient?.sendKeyEvent("KEYCODE_DPAD_LEFT")
                                        } else if (kotlin.math.abs(phoneDy) > kotlin.math.abs(phoneDx) * 1.5f) {
                                            if (phoneDy > 0) currentAdbClient?.sendKeyEvent("KEYCODE_DPAD_DOWN")
                                            else currentAdbClient?.sendKeyEvent("KEYCODE_DPAD_UP")
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
