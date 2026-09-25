package com.rokidlab.phone.store

import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.rokidlab.phone.R
import com.rokidlab.phone.design.BrewBg
import com.rokidlab.phone.design.BrewBorder
import com.rokidlab.phone.design.BrewChat
import com.rokidlab.phone.design.BrewMuted
import com.rokidlab.phone.design.BrewPanel
import com.rokidlab.phone.design.BrewRed
import com.rokidlab.phone.design.BrewTextBright
import com.rokidlab.phone.platform.WebPreviewManager

/**
 * 本机网页预览的聊天侧 UI（条 + 全屏预览弹层）。
 *
 * 数据源是 [WebPreviewManager.previews]（StateFlow）：容器里有常驻网页服务就显示预览条，
 * 没有就整体不渲染 —— 预览是**持续状态**，与 LabFileOutputs 那种"一次性产出推送"不同，
 * 不需要 sink 注册，离开聊天页再回来状态照常恢复。
 *
 * 明文 http 的合规性：`network_security_config` 已把 127.0.0.1 白名单（明文只放回环），
 * 预览地址恰好在白名单内；WebView 加载外部 http 站点依然被系统拦 —— 边界没变宽。
 */
@Composable
internal fun WebPreviewBar() {
    val previews by WebPreviewManager.previews.collectAsState()
    if (previews.isEmpty()) return

    var openInfo by remember { mutableStateOf<WebPreviewManager.PreviewInfo?>(null) }

    Column(modifier = Modifier.fillMaxWidth()) {
        previews.forEach { info ->
            Row(
                modifier = Modifier
                    // ⚠️ 不能写 `padding(horizontal = …, top = …)` —— 没有这个重载
                    .padding(start = 12.dp, end = 12.dp, top = 6.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(BrewPanel)
                    .border(1.dp, BrewBorder, RoundedCornerShape(10.dp))
                    .clickable { openInfo = info }
                    .padding(start = 12.dp, end = 4.dp, top = 7.dp, bottom = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Filled.Language,
                    contentDescription = null,
                    tint = BrewChat,
                    modifier = Modifier.size(15.dp),
                )
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.chat_preview_bar_label),
                        color = BrewTextBright,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text = info.url,
                        color = BrewMuted,
                        fontSize = 11.sp,
                        maxLines = 1,
                    )
                }
                TextButton(onClick = { openInfo = info }) {
                    Text(
                        text = stringResource(R.string.chat_preview_bar_open),
                        color = BrewChat,
                        fontSize = 12.sp,
                    )
                }
            }
        }
    }

    openInfo?.let { info ->
        WebPreviewDialog(info = info, onDismiss = { openInfo = null })
    }
}

@Composable
private fun WebPreviewDialog(info: WebPreviewManager.PreviewInfo, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(modifier = Modifier.fillMaxSize().background(BrewBg)) {
            // 顶栏：地址 + 关闭
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.chat_preview_bar_label),
                        color = BrewTextBright,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(text = info.url, color = BrewMuted, fontSize = 11.sp, maxLines = 1)
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = null,
                        tint = BrewMuted,
                    )
                }
            }

            // 页面本体：127.0.0.1 已在明文白名单（network_security_config），可以直接加载
            AndroidView(
                factory = { viewCtx ->
                    WebView(viewCtx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = true
                        webViewClient = WebViewClient()
                        loadUrl(info.url)
                    }
                },
                onRelease = { webView ->
                    runCatching {
                        webView.stopLoading()
                        webView.destroy()
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            )

            // 底部：停止服务（停完整个预览条都会随 StateFlow 消失）
            TextButton(
                onClick = {
                    WebPreviewManager.stop(ctx, info.id)
                    Toast.makeText(ctx, ctx.getString(R.string.chat_preview_stop_done), Toast.LENGTH_SHORT).show()
                    onDismiss()
                },
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(bottom = 12.dp),
            ) {
                Text(
                    text = stringResource(R.string.chat_preview_stop),
                    color = BrewRed,
                    fontSize = 13.sp,
                )
            }
        }
    }
}
