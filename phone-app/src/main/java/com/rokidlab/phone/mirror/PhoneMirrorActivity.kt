package com.rokidlab.phone.mirror

import com.rokidlab.phone.app.*
import com.rokidlab.phone.adb.*
import com.rokidlab.phone.connection.ConnectionRoute
import com.rokidlab.phone.design.*
import com.rokidlab.phone.filemanager.*
import com.rokidlab.phone.glasses.*
import com.rokidlab.phone.mirror.*
import com.rokidlab.phone.model.*
import com.rokidlab.phone.network.*
import com.rokidlab.phone.settings.*
import com.rokidlab.phone.store.*
import com.rokidlab.phone.util.*
import com.rokidlab.phone.R
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File

class PhoneMirrorActivity : ComponentActivity() {
    companion object {
        private const val TAG = "PhoneMirrorActivity"
        private const val DEFAULT_PORT = 7654
        private const val DEFAULT_WIDTH = 480
        private const val DEFAULT_HEIGHT = 640

        fun createIntent(context: Context): Intent =
            Intent(context, PhoneMirrorActivity::class.java)
    }

    private var ipAddress by mutableStateOf("192.168.1.168")
    private var port by mutableStateOf(DEFAULT_PORT.toString())
    private var isConnecting by mutableStateOf(false)
    private var isStreaming by mutableStateOf(false)
    private var connectionStatus by mutableStateOf("")
    private var connectionFailed by mutableStateOf(false)

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        Log.i(TAG, "mediaProjection result: resultCode=${result.resultCode}, data=${result.data != null}")
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            Log.i(TAG, "permission granted, starting foreground service")
            isConnecting = false
            isStreaming = true
            connectionStatus = getString(R.string.project_text_in_progress)
            PhoneMirrorService.startService(
                this,
                ipAddress,
                port.toIntOrNull() ?: DEFAULT_PORT,
                result.resultCode,
                result.data!!
            )
        } else {
            Log.i(TAG, "permission denied, resultCode=${result.resultCode}")
            isConnecting = false
            connectionFailed = true
            connectionStatus = getString(R.string.screen_permission_denied)
        }
    }

    /** 悬浮窗权限请求 launcher */
    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // 返回后重新尝试连接
        if (isConnecting) {
            startConnection()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            RokidLabTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                if (isStreaming) {
                    StreamingUI(
                        status = connectionStatus,
                        onStop = { stopStreaming() }
                    )
                } else if (connectionFailed) {
                    ConnectionFailedUI(
                        status = connectionStatus,
                        ipAddress = ipAddress,
                        port = port,
                        onRetry = {
                            connectionFailed = false
                            isConnecting = true
                            connectionStatus = getString(R.string.connecting_glasses)
                            startConnection()
                        },
                        onBack = { finish() }
                    )
                } else {
                    ConnectingUI(status = connectionStatus)
                }
                }
            }
        }

        // 异步路由判断：WiFi 可达则直连，否则走蓝牙隧道
        isConnecting = true
        connectionStatus = getString(R.string.connecting_glasses)
        val app = application as LabApplication
        val wifiIp = app.phoneMirrorIp
        val wifiPort = (app.phoneMirrorPort.toIntOrNull() ?: DEFAULT_PORT)
        Thread {
            val route = kotlinx.coroutines.runBlocking {
                app.routeManager.resolve(wifiIp, wifiPort)
            }
            when (route) {
                is ConnectionRoute.Wifi -> {
                    ipAddress = route.ip
                    port = route.port.toString()
                }
                is ConnectionRoute.Bluetooth -> {
                    ipAddress = route.ip
                    port = route.localPort.toString()
                }
                is ConnectionRoute.None -> {
                    runOnUiThread {
                        connectionFailed = true
                        connectionStatus = getString(R.string.mirror_connection_failed)
                        isConnecting = false
                    }
                    return@Thread
                }
            }
            runOnUiThread { startConnection() }
        }.start()
    }

    @Composable
    private fun ConnectingUI(status: String) {
        val ctx = LocalContext.current
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            CircularProgressIndicator(color = BrewCoral)

            Spacer(Modifier.height(24.dp))

            Text(
                status,
                color = BrewTextBright,
                fontSize = 16.sp
            )

            Spacer(Modifier.height(8.dp))

            Text(
                ctx.getString(R.string.requesting_screen_permission),
                color = BrewMuted,
                fontSize = 14.sp
            )
        }
    }

    @Composable
    private fun StreamingUI(
        status: String,
        onStop: () -> Unit
    ) {
        val ctx = LocalContext.current
        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawBehind {
                    // 蒙德里安品牌边框 — 四角色块
                    val barW = 6.dp.toPx()
                    // 左上红
                    drawRect(BrewCoral.copy(alpha = 0.6f), topLeft = Offset(0f, 0f), size = Size(barW, 48.dp.toPx()))
                    drawRect(BrewCoral.copy(alpha = 0.6f), topLeft = Offset(0f, 0f), size = Size(48.dp.toPx(), barW))
                    // 右上蓝
                    drawRect(BrewCyan.copy(alpha = 0.5f), topLeft = Offset(size.width - barW, 0f), size = Size(barW, 36.dp.toPx()))
                    drawRect(BrewCyan.copy(alpha = 0.5f), topLeft = Offset(size.width - 36.dp.toPx(), 0f), size = Size(36.dp.toPx(), barW))
                    // 左下黄
                    drawRect(BrewPurple.copy(alpha = 0.5f), topLeft = Offset(0f, size.height - 36.dp.toPx()), size = Size(36.dp.toPx(), barW))
                    drawRect(BrewPurple.copy(alpha = 0.5f), topLeft = Offset(0f, size.height - 48.dp.toPx()), size = Size(barW, 48.dp.toPx()))
                    // 右下白
                    drawRect(BrewTextBright.copy(alpha = 0.3f), topLeft = Offset(size.width - barW, size.height - 48.dp.toPx()), size = Size(barW, 48.dp.toPx()))
                    drawRect(BrewTextBright.copy(alpha = 0.3f), topLeft = Offset(size.width - 48.dp.toPx(), size.height - barW), size = Size(48.dp.toPx(), barW))
                },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    ctx.getString(R.string.projecting_text),
                    color = BrewCoral,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(Modifier.height(16.dp))

                Text(
                    status,
                    color = BrewTextBright,
                    fontSize = 16.sp,
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(32.dp))

                Button(
                    onClick = onStop,
                    shape = BrewShapeStandard,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = BrewCoral,
                        contentColor = BrewTextBright
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                ) {
                    Text(ctx.getString(R.string.stop_projection_btn), fontSize = 16.sp)
                }
            }
        }
    }

    @Composable
    private fun ConnectionFailedUI(
        status: String,
        ipAddress: String,
        port: String,
        onRetry: () -> Unit,
        onBack: () -> Unit
    ) {
        val ctx = LocalContext.current
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                ctx.getString(R.string.mirror_connection_failed),
                color = BrewRed,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(Modifier.height(16.dp))

            Text(
                status,
                color = BrewMuted,
                fontSize = 14.sp,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(32.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                OutlinedButton(
                    onClick = onBack,
                    modifier = Modifier.weight(1f),
                    shape = BrewShapeStandard,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = BrewTextBright
                    )
                ) {
                    Text(ctx.getString(R.string.back_btn))
                }

                Button(
                    onClick = onRetry,
                    modifier = Modifier.weight(1f),
                    shape = BrewShapeStandard,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = BrewCoral,
                        contentColor = BrewTextBright
                    )
                ) {
                    Text(ctx.getString(R.string.retry_btn))
                }
            }
        }
    }

    private fun startConnection() {
        if (isConnecting || isStreaming) return
        Log.i(TAG, "startConnection: starting connection")
        isConnecting = true
        connectionStatus = getString(R.string.requesting_screen_permission)

        // 检查悬浮窗权限（OPPO/vivo 需要额外授权）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !ManufacturerUtils.canDrawOverlays(this)) {
            Log.w(TAG, "Overlay permission not granted, requesting...")
            connectionStatus = getString(R.string.overlay_permission_title)
            val intent = ManufacturerUtils.getOverlaySettingsIntent(this)
                ?: Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                    data = Uri.parse("package:${packageName}")
                }
            overlayPermissionLauncher.launch(intent)
            return
        }

        // 首先请求屏幕录制权限
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        Log.i(TAG, "startConnection: requesting screen recording permission")
        mediaProjectionLauncher.launch(projectionManager.createScreenCaptureIntent())
    }

    private fun stopStreaming() {
        isStreaming = false
        val serviceIntent = Intent(this, PhoneMirrorService::class.java)
        stopService(serviceIntent)
        // 延迟 200ms 让 Service 清理完成后再 finish，减少异步竞态
        Handler(Looper.getMainLooper()).postDelayed({
            if (!isFinishing) finish()
        }, 200)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && isStreaming) {
            stopStreaming()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        if (isStreaming) {
            stopStreaming()
        }
        super.onDestroy()
    }
}
