package com.rokidlab.phone.store

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Functions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.rokidlab.phone.R
import com.rokidlab.phone.design.BrewBg
import com.rokidlab.phone.design.BrewBorder
import com.rokidlab.phone.design.BrewChat
import com.rokidlab.phone.design.BrewMuted
import com.rokidlab.phone.design.BrewPanel
import com.rokidlab.phone.design.BrewTextBright

/** 需要 WebView 渲染的两类内容（都是"本地资源 + 离线渲染"，不联网） */
internal enum class WebRenderKind { FORMULA, MERMAID }

/**
 * 公式 / Mermaid 图卡：**本地** KaTeX 与 mermaid.js 在 WebView 里离线渲染。
 *
 * ## 为什么走 WebView（而不是引库）
 *  - 公式排版只有 KaTeX/MathJax 这一条成熟路线，纯 Compose 没有可用实现；
 *    引 Android 侧的 latex 库（jlatexmath 等）要自己处理字体与排版，效果远不如 KaTeX。
 *  - Mermaid 同理：它是 JS 生态的图渲染器，Android 端没有等价物。
 *  - 资源**随包内置**（`assets/chat_render/`，KaTeX 272K + 20 个字体 304K + mermaid 3.5M），
 *    浏览器离线可用 —— 眼镜/手机上联网加载 CDN 既慢又可能直接失败（这台设备经常没网）。
 *
 * ## 几个必要的约束
 *  - **高度由 JS 回报**：WebView 自身没有"按内容自适应高度"的能力，渲染完把
 *    `document.body.scrollHeight` 通过 [HeightBridge] 回传，再设置固定高度并封顶
 *    [MAX_CARD_HEIGHT]（超长图内部滚动，不让一张图把对话列表撑爆）。
 *  - **换内容必须整个重建 WebView**：`loadDataWithBaseURL` 有缓存与历史栈，
 *    流式过程中同一张卡片的内容会变（公式还没打完），重建最省心（`key(source)`）。
 *  - **JS 默认关闭、只放行这一处**：`javaScriptEnabled = true` 仅用于跑本地脚本，
 *    桥接口只暴露一个 `postHeight` 方法（没有文件/网络能力）。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun WebRenderCard(kind: WebRenderKind, source: String) {
    val ctx = LocalContext.current
    // 高度：0 = 还没量出来（显示转圈）；>0 = 实测高度
    var heightPx by remember(source) { mutableStateOf(0) }
    var failed by remember(source) { mutableStateOf(false) }

    val label = stringResource(
        if (kind == WebRenderKind.FORMULA) R.string.chat_formula_label else R.string.chat_mermaid_label,
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(BrewPanel)
            .border(1.dp, BrewBorder, RoundedCornerShape(10.dp)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(30.dp)
                .background(BrewChat.copy(alpha = 0.12f))
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (kind == WebRenderKind.FORMULA) {
                    Icons.Outlined.Functions
                } else {
                    Icons.Outlined.AccountTree
                },
                contentDescription = null,
                tint = BrewChat,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = label,
                color = BrewChat,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.8.sp,
            )
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(BrewBorder),
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .background(BrewBg),
            contentAlignment = Alignment.Center,
        ) {
            when {
                failed -> Text(
                    text = stringResource(R.string.chat_render_failed),
                    color = BrewMuted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(10.dp),
                )

                heightPx == 0 -> CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 1.8.dp,
                    color = BrewChat,
                )

                else -> AndroidView(
                    // ⚠️ key = source：流式刷新时内容会变，复用同一个 WebView 会带着旧渲染/历史栈
                    factory = { c ->
                        WebView(c).apply {
                            setBackgroundColor(AndroidColor.TRANSPARENT)
                            settings.javaScriptEnabled = true
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            settings.domStorageEnabled = false
                            isHorizontalScrollBarEnabled = true
                            webViewClient = WebViewClient()
                        }
                    },
                    update = { view ->
                        if (view.tag != source) {
                            view.tag = source
                            view.addJavascriptInterface(HeightBridge { px ->
                                view.post { heightPx = px }
                            }, "androidBridge")
                            view.loadDataWithBaseURL(
                                "file:///android_asset/chat_render/",
                                htmlFor(kind, source, textColorHex = BrewTextBright.toArgb()),
                                "text/html",
                                "utf-8",
                                null,
                            )
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(
                            // 封顶：一张超长 Mermaid 图不该把对话列表撑成几千像素
                            (heightPx / ctx.resources.displayMetrics.density)
                                .coerceAtMost(MAX_CARD_HEIGHT.value)
                                .dp,
                        ),
                )
            }
        }
    }
}

/** 超长图的显示上限（超出内部滚动） */
private val MAX_CARD_HEIGHT = 420.dp

/** 只暴露一个回传高度的方法；没有文件、网络、命令能力 */
private class HeightBridge(private val onHeight: (Int) -> Unit) {
    @JavascriptInterface
    fun postHeight(px: Int) = onHeight(px)
}

/**
 * 卡片网页内容。
 *
 * 页面背景透明、文字色用聊天气泡的亮色（由调用方传 currentColor 的 ARGB）——
 * 深色主题下若写死黑字，公式会看不见（这正是眼镜端 ink 页面踩过的同一类坑）。
 */
private fun htmlFor(kind: WebRenderKind, source: String, textColorHex: Int): String {
    val color = String.format("#%06X", 0xFFFFFF and textColorHex)
    val escaped = source
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
    val body = when (kind) {
        WebRenderKind.FORMULA -> """
            <div id="target" style="color:$color;font-size:18px;padding:10px;overflow-x:auto;">$escaped</div>
        """.trimIndent()

        WebRenderKind.MERMAID -> """
            <div id="target" style="padding:8px;">$escaped</div>
        """.trimIndent()
    }
    val script = when (kind) {
        WebRenderKind.FORMULA -> """
            try {
              katex.render(document.getElementById('target').textContent, document.getElementById('target'), {
                throwOnError: false, displayMode: true
              });
            } catch (e) { document.getElementById('target').textContent = '公式渲染失败'; }
        """.trimIndent()

        WebRenderKind.MERMAID -> """
            mermaid.initialize({ startOnLoad: false, theme: 'base', themeVariables: {
              background: 'transparent', primaryColor: '#2b6cb0', primaryTextColor: '$color',
              lineColor: '$color', fontSize: '14px'
            }});
            try { await mermaid.run({ nodes: [document.getElementById('target')] }); }
            catch (e) { document.getElementById('target').textContent = document.getElementById('target').textContent; }
        """.trimIndent()
    }
    val head = when (kind) {
        WebRenderKind.FORMULA -> """
            <link rel="stylesheet" href="katex/katex.min.css">
            <script src="katex/katex.min.js"></script>
        """.trimIndent()

        WebRenderKind.MERMAID -> """<script src="mermaid.min.js"></script>"""
    }
    return """
        <!DOCTYPE html><html><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1.0">
        $head
        <style>
          html,body{margin:0;padding:0;background:transparent;color:$color;
            font-family:sans-serif;overflow-x:auto;overflow-y:hidden;}
        </style>
        </head><body>
        $body
        <script>
          (async () => {
            $script
            // 渲染完把内容高度回报给原生侧（WebView 自己不会随内容变高）
            setTimeout(() => {
              const h = Math.max(document.body.scrollHeight, document.documentElement.scrollHeight);
              if (window.androidBridge) window.androidBridge.postHeight(h);
            }, 60);
          })();
        </script>
        </body></html>
    """.trimIndent()
}
