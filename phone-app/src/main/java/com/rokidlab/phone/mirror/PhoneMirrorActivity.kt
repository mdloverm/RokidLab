package com.rokidlab.phone.mirror

import com.rokidlab.phone.app.*
import com.rokidlab.phone.adb.*
import com.rokidlab.phone.design.*
import com.rokidlab.phone.filemanager.*
import com.rokidlab.phone.glasses.*
import com.rokidlab.phone.mirror.*
import com.rokidlab.phone.model.*
import com.rokidlab.phone.network.*
import com.rokidlab.phone.settings.*
import com.rokidlab.phone.store.*
import com.rokidlab.phone.util.*
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.ui.text.input.KeyboardType
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

        const val REQUEST_MEDIA_PROJECTION = 100
    }

    private var ipAddress by mutableStateOf("192.168.1.168")
    private var port by mutableStateOf(DEFAULT_PORT.toString())
    private var isConnecting by mutableStateOf(false)
    private var isStreaming by mutableStateOf(false)
    private var connectionStatus by mutableStateOf("")
    private var connectionFailed by mutableStateOf(false)
    private var mediaProjection: MediaProjection? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        // 从 BrewApplication 读取 IP 和端口
        val app = application as BrewApplication
        ipAddress = app.phoneMirrorIp
        port = app.phoneMirrorPort
        
        // 启动时自动开始连接
        isConnecting = true
        connectionStatus = "正在连接眼镜..."
        startConnection()

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
                            connectionStatus = "正在连接眼镜..."
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
    }
    
    @Composable
    private fun ConfigUI(
        ipAddress: String,
        port: String,
        connectionStatus: String,
        onIpChange: (String) -> Unit,
        onPortChange: (String) -> Unit,
        onConnect: () -> Unit
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                "手机投屏",
                color = BrewTextBright,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 32.dp)
            )

            OutlinedTextField(
                value = ipAddress,
                onValueChange = onIpChange,
                label = { Text("眼镜 IP 地址", color = BrewText) },
                textStyle = androidx.compose.ui.text.TextStyle(color = BrewTextBright),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(24.dp))

            Button(
                onClick = onConnect,
                modifier = Modifier
                    .fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = BrewCoral)
            ) {
                Text("开始投屏", color = BrewBg)
            }
            
            // 显示状态消息
            if (connectionStatus.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = connectionStatus,
                    color = if (connectionStatus.contains("失败") || connectionStatus.contains("未授权")) 
                        BrewRed else BrewText,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }

    @Composable
    private fun ConnectingUI(status: String) {
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
                "正在请求屏幕录制权限...",
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
                    "投屏中",
                    color = BrewGreen,
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
                    colors = ButtonDefaults.buttonColors(
                        containerColor = BrewCoral
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                ) {
                    Text("停止投屏", fontSize = 16.sp, color = BrewBg)
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                "连接失败",
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
                    modifier = Modifier.weight(1f)
                ) {
                    Text("返回")
                }

                Button(
                    onClick = onRetry,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("重试")
                }
            }
        }
    }

    private fun startConnection() {
        Log.i(TAG, "startConnection: 开始连接流程")
        isConnecting = true
        connectionStatus = "正在请求屏幕录制权限..."

        // 首先请求屏幕录制权限
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        Log.i(TAG, "startConnection: 启动屏幕录制权限请求")
        startActivityForResult(projectionManager.createScreenCaptureIntent(), REQUEST_MEDIA_PROJECTION)
    }

    private fun retryConnection() {
        connectionFailed = false
        startConnection()
    }

    

    private fun stopStreaming() {
        isStreaming = false
        mediaProjection?.stop()
        mediaProjection = null
        finish()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        Log.i(TAG, "onActivityResult: requestCode=$requestCode, resultCode=$resultCode, data=${data != null}")
        if (requestCode == REQUEST_MEDIA_PROJECTION) {
            if (resultCode == Activity.RESULT_OK && data != null) {
                Log.i(TAG, "onActivityResult: 权限获取成功，启动前台服务")
                isConnecting = false
                isStreaming = true
                connectionStatus = "投屏中..."
                
                // 启动前台服务进行屏幕录制（Android 12+ 要求必须在前台服务中）
                PhoneMirrorService.startService(
                    this,
                    ipAddress,
                    port.toIntOrNull() ?: DEFAULT_PORT,
                    resultCode,
                    data
                )
            } else {
                Log.i(TAG, "onActivityResult: 权限获取失败，resultCode=$resultCode")
                isConnecting = false
                connectionFailed = true
                connectionStatus = "屏幕录制权限被拒绝"
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && isStreaming) {
            stopStreaming()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        stopStreaming()
        super.onDestroy()
    }
}