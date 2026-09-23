package com.rokidlab.phone.aiui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.rokidlab.phone.R
import com.rokidlab.phone.design.BrewBorder
import com.rokidlab.phone.design.BrewChat
import com.rokidlab.phone.design.BrewCyan
import com.rokidlab.phone.design.BrewDim
import com.rokidlab.phone.design.BrewPanel
import com.rokidlab.phone.design.BrewPanelHi
import com.rokidlab.phone.design.BrewTextBright

/** 演示画布宽度：比屏幕窄一截，够看清布局又不至于把整屏占满 */
private val CANVAS_WIDTH = 240.dp

/**
 * 对话里的 AIUI **演示卡片**：`open_aiui_app(target=phone)` 之后，在消息流末尾
 * （AI 那条回复的正下方）多出一张卡片，实时渲染这个 .aix 的样子。
 *
 * 定位（用户拍板）：**仅演示** —— 目的是"生成完先看一眼长什么样，别急着推眼镜"。
 * 所以这里的小画布不带手势层。要真的动手操作（滑动/点击模拟方向键与回车）就点「全屏操作」，
 * 那才是操作入口([AiuiDemoActivity])。
 *
 * 它是**消息列表里的一项**，跟着对话一起滚，不再是浮在外面的独立窗口
 * （用户要求：小窗口挪进对话卡片里）。渲染宿主（WebView）由 [AiuiDemoController] 持有
 * 而不是 `remember` —— 卡片滚出屏幕会被回收，随组合销毁的话滚回来要重新加载 20MB wasm。
 *
 * 画布上的两种覆盖文字：[AiuiDemoController.lastError]（渲染失败给原因）与
 * [AiuiDemoController.rendered]（未出首帧给占位）。**都不能省** —— 少了它们，任何失败
 * 都表现为一块没有任何解释的纯黑，用户只能猜"为什么手机里是黑的"。
 */
@Composable
internal fun AiuiDemoCard(modifier: Modifier = Modifier) {
    val session = AiuiDemoController.current ?: return
    val ctx = LocalContext.current
    val host = remember(session) { AiuiDemoController.hostFor(ctx) } ?: return
    val rendered = AiuiDemoController.rendered
    val error = AiuiDemoController.lastError

    // 卡片所在页面被压到后台（例如点了「全屏操作」）时停渲染，回来再续上
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

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(BrewPanel)
            .border(1.dp, BrewBorder, RoundedCornerShape(14.dp))
            .padding(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.aiui_demo_badge),
                color = BrewChat,
                fontSize = 11.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(BrewPanelHi)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = session.appName,
                color = BrewTextBright,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(R.string.aiui_demo_close),
                color = BrewDim,
                fontSize = 12.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { AiuiDemoController.dismiss() }
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .width(CANVAS_WIDTH)
                    .aspectRatio(AiuiWebHost.DESIGN_WIDTH.toFloat() / AiuiWebHost.DESIGN_HEIGHT)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.Black)
                    .border(1.dp, BrewBorder, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) {
                AndroidView(
                    factory = { host.view },
                    modifier = Modifier.fillMaxSize(),
                )
                if (error != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(BrewPanel)
                            .padding(12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.aiui_demo_failed) + "\n" + error,
                            color = BrewDim,
                            fontSize = 11.sp,
                            textAlign = TextAlign.Center,
                        )
                    }
                } else if (!rendered) {
                    // wasm 有 20MB，首帧之前画布本来就是黑的 —— 给个占位说明"在加载"而不是"坏了"
                    Text(
                        text = stringResource(R.string.aiui_demo_rendering),
                        color = BrewDim,
                        fontSize = 11.sp,
                    )
                }
            }
        }

        Text(
            text = stringResource(R.string.aiui_demo_note),
            color = BrewDim,
            fontSize = 10.sp,
            modifier = Modifier.padding(top = 6.dp),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(9.dp))
                    .background(BrewCyan)
                    .clickable { ctx.startActivity(AiuiDemoActivity.intent(ctx, session)) }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.aiui_demo_fullscreen),
                    color = Color.Black,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}
