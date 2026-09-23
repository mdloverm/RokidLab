package com.rokidlab.phone.aiui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.rokidlab.phone.R
import com.rokidlab.phone.design.BrewBorder
import com.rokidlab.phone.design.BrewCyan
import com.rokidlab.phone.design.BrewMuted
import com.rokidlab.phone.design.BrewPanelHi
import com.rokidlab.phone.design.BrewText
import com.rokidlab.phone.design.BrewTextBright
import java.io.File
import java.lang.ref.WeakReference

/**
 * 手机端 AIUI **全屏操作页**。
 *
 * 为什么要有独立 Activity（而不是把对话里的浮层放大）：浮层是"瞥一眼长什么样"，
 * 全屏是"真的拿手操作一遍"。操作需要空间 —— 画布按 480×640 等比铺满 + 底部一排按键，
 * 而且可以横屏、可以长时间停留。两者共用同一个宿主实现（[AiuiWebHost]）与同一套手势层
 * （[AiuiGestureLayer]），所以看到的渲染与操作序列完全一致，只是尺寸不同。
 *
 * 操作方式（两种等价，都是合成眼镜上的那一下按键）：
 *  - 手势：画布上滑动 = 方向键，点击 = 回车（双击 = 两次回车）
 *  - 按键：底部 ↑↓←→ ⏎ ⌫ 直接点
 */
class AiuiDemoActivity : ComponentActivity() {

    companion object {
        private const val EXTRA_AIX_PATH = "aix_path"
        private const val EXTRA_LAUNCH_PARAMS = "launch_params"
        private const val EXTRA_APP_NAME = "app_name"

        /** 由对话浮层的「全屏」按钮调用 */
        internal fun intent(ctx: Context, session: AiuiDemoController.Session): Intent =
            Intent(ctx, AiuiDemoActivity::class.java)
                .putExtra(EXTRA_AIX_PATH, session.aix.absolutePath)
                .putExtra(EXTRA_APP_NAME, session.appName)
                .apply { session.launchParams?.let { putExtra(EXTRA_LAUNCH_PARAMS, it) } }

        /** 当前在屏的全屏演示页（弱引用；供 [AiuiDemoController.dismissAll] 关掉它） */
        private var live: WeakReference<AiuiDemoActivity>? = null

        /** 若全屏演示页在屏则关掉它（无页时静默返回） */
        internal fun finishIfRunning() {
            val a = live?.get() ?: return
            a.runOnUiThread { if (!a.isFinishing) a.finish() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        live = WeakReference(this)
        // 演示期间不让息屏：这是"边看边操作"的场景，中途黑屏会打断（退出页面即失效）
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val aixPath = intent.getStringExtra(EXTRA_AIX_PATH)
        val appName = intent.getStringExtra(EXTRA_APP_NAME).orEmpty()
        val launchParams = intent.getStringExtra(EXTRA_LAUNCH_PARAMS)
        val aix = aixPath?.let { File(it) }
        if (aix == null || !aix.isFile) {
            finish()
            return
        }
        setContent {
            AiuiDemoScreen(
                ctx = this,
                aix = aix,
                appName = appName,
                launchParams = launchParams,
                onClose = { finish() },
            )
        }
    }

    override fun onDestroy() {
        // 只清本实例登记的那一个，避免旧实例销毁时误清新实例的引用
        if (live?.get() === this) live = null
        super.onDestroy()
    }
}

@Composable
private fun AiuiDemoScreen(
    ctx: Context,
    aix: File,
    appName: String,
    launchParams: String?,
    onClose: () -> Unit,
) {
    // 每个 .aix 一个宿主实例；换包时重建（remember key）
    val host = remember(aix.absolutePath) {
        AiuiWebHost(
            ctx = ctx,
            aixFile = aix,
            launchParams = launchParams,
            onCloseRequested = onClose,
        )
    }
    DisposableEffect(host) {
        onDispose { host.destroy() }
    }
    // WebView 同样要跟生命周期走：退到后台时停掉渲染线程（wasm 渲染很吃 CPU）
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> host.onResume()
                Lifecycle.Event.ON_PAUSE -> host.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun tap(code: String) {
        host.injectKey(code, "down")
        host.injectKey(code, "up")
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        // 顶栏：返回 + 应用名（兼作"当前在演示哪个包"的唯一标识）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.aiui_demo_exit),
                color = BrewCyan,
                fontSize = 13.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onClose() }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = appName.ifBlank { stringResource(R.string.aiui_demo_title) },
                color = BrewTextBright,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
        }

        // 画布：严格 480:640（ink 画布 CSS 是 width/height 100%，容器不是 3:4 就会被拉伸变形）
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .aspectRatio(AiuiWebHost.DESIGN_WIDTH.toFloat() / AiuiWebHost.DESIGN_HEIGHT)
                    .border(1.dp, BrewBorder),
            ) {
                AndroidView(
                    factory = { host.view },
                    modifier = Modifier.fillMaxSize(),
                )
                // 手势层盖在画布上（它**吞掉**触摸，不转发给 WebView：两路都发会一次点击出两个动作）
                AndroidView(
                    factory = { AiuiGestureLayer(ctx) { code, action -> host.injectKey(code, action) } },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        Text(
            text = stringResource(R.string.aiui_demo_hint),
            color = BrewMuted,
            fontSize = 11.sp,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 4.dp),
        )

        // 按键行：与手势等价的物理键入口（对应眼镜上的方向键 / 回车 / 返回）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AiuiKeyButton("↑", Modifier.weight(1f)) { tap(AiuiKeys.UP) }
            AiuiKeyButton("↓", Modifier.weight(1f)) { tap(AiuiKeys.DOWN) }
            AiuiKeyButton("←", Modifier.weight(1f)) { tap(AiuiKeys.LEFT) }
            AiuiKeyButton("→", Modifier.weight(1f)) { tap(AiuiKeys.RIGHT) }
            AiuiKeyButton("⏎", Modifier.weight(1f)) { tap(AiuiKeys.ENTER) }
            AiuiKeyButton("⌫", Modifier.weight(1f)) { tap(AiuiKeys.BACK) }
        }
    }
}

@Composable
private fun AiuiKeyButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(BrewPanelHi)
            .border(1.dp, BrewBorder, RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, color = BrewText, fontSize = 16.sp)
    }
}
