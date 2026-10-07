package com.rokidlab.phone.store

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Cast
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Functions
import androidx.compose.material.icons.outlined.OpenInFull
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.rokidlab.phone.R
import com.rokidlab.phone.design.BrewBg
import com.rokidlab.phone.design.BrewBorder
import com.rokidlab.phone.design.BrewChat
import com.rokidlab.phone.design.BrewDim
import com.rokidlab.phone.design.BrewMuted
import com.rokidlab.phone.design.BrewPanel
import com.rokidlab.phone.design.BrewTextBright
import com.rokidlab.phone.glasses.ChartGlassPush

/** 需要 WebView 渲染的三类内容（都是"本地资源 + 离线渲染"，不联网） */
internal enum class WebRenderKind { FORMULA, MERMAID, CHART }

private const val TAG = "WebRenderCard"

/**
 * 渲染多久还没出结果就判定失败（毫秒）。
 *
 * ## 为什么必须有这个上限（2026-10-06 审计修）
 * `katex.min.js`（272K）与 `mermaid.min.js`（**3.5MB**）都要从 assets 解析，
 * 老机型上慢解析 + WebView 渲染进程异常都可能让脚本**根本没跑起来** ——
 * 此时 JS 侧的上报永远不来，而原生侧除了 `postHeight` 没有别的信号。
 * 没有这个上限，用户看到的就是**一个永远转圈的圈**，与"App 卡死了"无法区分。
 *
 * 取 8s：正常渲染在 1s 内完成（本地资源），8s 足够覆盖 3.5MB 的解析开销，
 * 又不会让真失败的场景让用户干等（无上限转圈最伤）。
 */
private const val RENDER_TIMEOUT_MS = 8_000L

/**
 * 公式 / Mermaid 图 / ECharts 数据图表卡：**本地** KaTeX、mermaid.js 与 echarts.js 在 WebView 里离线渲染。
 *
 * ## 为什么走 WebView（而不是引库）
 *  - 公式排版只有 KaTeX/MathJax 这一条成熟路线，纯 Compose 没有可用实现；
 *    引 Android 侧的 latex 库（jlatexmath 等）要自己处理字体与排版，效果远不如 KaTeX。
 *  - Mermaid 与 ECharts 同理：它们是 JS 生态的图渲染器，Android 端没有等价物。
 *  - 资源**随包内置**（`assets/chat_render/`，KaTeX 272K + 20 个字体 304K + mermaid 3.5M + echarts ~1M），
 *    浏览器离线可用 —— 眼镜/手机上联网加载 CDN 既慢又可能直接失败（这台设备经常没网）。
 *
 * ## 图表卡（CHART）与另外两类的关键差异
 *  - **高度模型相反**：公式/Mermaid 是"渲染完内容撑高"，图表是"容器先有尺寸才能画"
 *    （canvas 按 clientWidth/Height 起稿）—— 固定 300px 高度容器，渲染后上报同一量纲；
 *  - **流式半截 JSON 必失败**：解析侧（[ChatMarkdown]）只在围栏**闭合后**才送 Chart 块，
 *    生成期间按代码卡显示源码（mermaid 半截可渲染，无此问题）；
 *  - **深色适配**：option 没写颜色时 JS 侧兜底透明背景 + 容器亮色文字，
 *    避免 echarts 默认白底黑字在深色气泡上看不清。
 *
 * ## 几个必要的约束
 *  - **高度由 JS 回报**：WebView自身没有"按内容自适应高度"的能力，渲染完把
 *    `document.body.scrollHeight` 通过 [HeightBridge] 回传，再设置固定高度并封顶
 *    [MAX_CARD_HEIGHT]（超长图内部滚动，不让一张图把对话列表撑爆）。
 *  - ★ **WebView 必须始终存在，不能等`heightPx > 0` 才创建**（2026-10-06 审计修）：
 *    高度是 WebView **自己**报回来的，若把它的创建条件写成"高度已知"，
 *    就成了死锁 —— WebView 永不创建 ⇒ 永不上报 ⇒ 高度永远未知 ⇒ 卡片永远转圈。
 *    现在 WebView 常驻底层，状态（转圈/失败/源码兜底）作为**上层覆盖**。
 *  - **失败必须能显示出来**：JS 侧`catch` 调 [HeightBridge.postFail] 回报，
 *    叠加 [RENDER_TIMEOUT_MS] 超时兜底 —— 两者任一触发都显示可读文案而非裸转圈。
 *  - **换内容必须整个重建 WebView**：`loadDataWithBaseURL` 有缓存与历史栈，
 *    流式过程中同一张卡片的内容会变（公式还没打完），重建最省心（`key(source)`）。
 *  - **JS 默认关闭、只放行这一处**：`javaScriptEnabled = true` 仅用于跑本地脚本，
 *    桥接口只暴露 `postHeight` / `postFail` 两个方法（没有文件/网络能力）。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
internal fun WebRenderCard(kind: WebRenderKind, source: String) {
    val ctx = LocalContext.current
    // 高度：0 = 还没量出来（显示转圈）；>0 = 实测高度
    var heightPx by remember(source) { mutableStateOf(0) }
    //失败原因（null = 未失败）。**由 JS 侧 catch 或超时兜底写入** —— 修复前它从无置 true 的地方，
    // 于是 `failed ->` 分支是死代码，失败只会表现为永远转圈。
    var failReason by remember(source) { mutableStateOf<String?>(null) }

    // ★ 超时兜底：脚本没跑起来（3.5MB 解析失败 / 渲染进程异常）时 JS 永远不上报，
    //   这里到点就判失败。放在 LaunchedEffect 里而不是 composition 里裸写，
    //   是为了能随 source 变化重新计时，且不会在重组时反复起定时器。
    LaunchedEffect(source) {
        kotlinx.coroutines.delay(RENDER_TIMEOUT_MS)
        if (heightPx == 0 && failReason == null) {
            failReason = ctx.getString(R.string.chat_render_timeout)
        }
    }

    val label = stringResource(
        when (kind) {
            WebRenderKind.FORMULA -> R.string.chat_formula_label
            WebRenderKind.MERMAID -> R.string.chat_mermaid_label
            WebRenderKind.CHART -> R.string.chat_chart_label
        },
    )
    // WebView 尚未回报高度时的占位高度（44dp 与原先的 heightIn(min) 一致）。
    // 不能是 0 —— WebView 高度为 0 时布局/测量都可能不跑，脚本更不会执行。
    val bodyHeight = if (heightPx > 0) {
        (heightPx / ctx.resources.displayMetrics.density).coerceAtMost(MAX_CARD_HEIGHT.value).dp
    } else {
        MIN_RENDER_HEIGHT
    }
    // 全屏查看（仅图表卡有入口）：气泡宽度/高度封顶会裁掉大图，全屏 WebView 独立实例
    // 按 整屏尺寸重新起稿，看的就是完整版（点右上角 ✕ 或返回键关闭）
    var fullscreen by remember(source) { mutableStateOf(false) }

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
                imageVector = when (kind) {
                    WebRenderKind.FORMULA -> Icons.Outlined.Functions
                    WebRenderKind.MERMAID -> Icons.Outlined.AccountTree
                    WebRenderKind.CHART -> Icons.Outlined.BarChart
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
            if (kind == WebRenderKind.CHART) {
                Spacer(Modifier.weight(1f))
                // 渲染成功（高度已上报）才给全屏入口，失败/加载中点了也白点
                val canFullscreen = heightPx > 0 && failReason == null
                // 推眼镜：把当前图表离屏重渲染成 1024×768 快照下发眼镜全屏查看器
                //（见 ChartGlassPush）。与全屏共用「渲染成功才可点」的前提 —— 失败图推过去也是白图。
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .let { m ->
                            if (canFullscreen) {
                                m.clickable {
                                    ChartGlassPush.push(ctx, source, label) { msg ->
                                        android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                }
                            } else m
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Cast,
                        contentDescription = stringResource(R.string.chat_chart_push),
                        tint = if (canFullscreen) BrewMuted else BrewMuted.copy(alpha = 0.35f),
                        modifier = Modifier.size(14.dp),
                    )
                }
                Spacer(Modifier.width(10.dp))
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .let { m -> if (canFullscreen) m.clickable { fullscreen = true } else m },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.OpenInFull,
                        contentDescription = stringResource(R.string.chat_chart_fullscreen),
                        tint = if (canFullscreen) BrewMuted else BrewMuted.copy(alpha = 0.35f),
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
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
            // ★ 底层常驻 WebView（高度未知时也创建，见类注释的死锁说明）
            AndroidView(
                // ⚠️ key = source：流式刷新时内容会变，复用同一个 WebView 会带着旧渲染/历史栈
                factory = ::newRenderWebView,
                update = { view ->
                    if (view.tag != source) {
                        view.tag = source
                        view.addJavascriptInterface(
                            HeightBridge(
                                kind = kind,
                                onHeight = { px -> view.post { heightPx = px } },
                                onFail = { reason -> view.post { failReason = reason } },
                            ),
                            "androidBridge",
                        )
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
                    .height(bodyHeight),
            )

            // 上层状态覆盖：失败 > 加载中。（互斥，由 JS 侧保证"要么上报高度、要么上报失败"）
            val reason = failReason
            if (reason != null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(10.dp),
                ) {
                    Text(
                        text = stringResource(R.string.chat_render_failed) + "（" + reason + "）",
                        color = BrewMuted,
                        fontSize = 12.sp,
                    )
                    //★ 同时把原文显示出来（模型写错的公式/图源码，用户要能看到才能修）
                    //  —— 修复前 mermaid 的 catch 是空操作，用户只看到一堆原始源码且不知发生了什么。
                    if (kind == WebRenderKind.MERMAID || source.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = source.take(MAX_SOURCE_CHARS),
                            color = BrewDim,
                            fontSize = 10.sp,
                            lineHeight = 14.sp,
                        )
                    }
                }
            } else if (heightPx == 0) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 1.8.dp,
                    color = BrewChat,
                )
            }
        }
    }

    // 全屏查看：原始 Dialog（全屏覆盖层例外，与图片查看器同一待遇）。
    // 独立 WebView 实例按整屏重新起稿（fill=true → 容器 100vh），不与气泡内实例共享状态。
    if (fullscreen) {
        Dialog(
            onDismissRequest = { fullscreen = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Box(modifier = Modifier.fillMaxSize().background(BrewBg)) {
                AndroidView(
                    factory = ::newRenderWebView,
                    update = { view ->
                        if (view.tag != source) {
                            view.tag = source
                            // 全屏实例的高度/失败回报无消费方，给空回调即可（桥仍需注册，
                            // 否则 JS 里 window.androidBridge 为 undefined 会直接抛错）
                            view.addJavascriptInterface(
                                HeightBridge(kind = kind, onHeight = {}, onFail = {}),
                                "androidBridge",
                            )
                            view.loadDataWithBaseURL(
                                "file:///android_asset/chat_render/",
                                htmlFor(kind, source, textColorHex = BrewTextBright.toArgb(), fill = true),
                                "text/html",
                                "utf-8",
                                null,
                            )
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = stringResource(android.R.string.cancel),
                    tint = BrewTextBright,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .statusBarsPadding()
                        .padding(top = 12.dp, end = 14.dp)
                        .size(26.dp)
                        .clickable { fullscreen = false },
                )
            }
        }
    }
}

/** 未拿到高度前的占位高度；**不可为 0**（WebView 高度为 0 时布局/测量可能不跑） */
private val MIN_RENDER_HEIGHT = 44.dp

/** 气泡内与全屏共用的 WebView 构建：透明背景 + JS + 沙箱（禁文件/存储）+ console 转发 logcat */
internal fun newRenderWebView(context: android.content.Context): WebView = WebView(context).apply {
    setBackgroundColor(AndroidColor.TRANSPARENT)
    settings.javaScriptEnabled = true
    settings.allowFileAccess = false
    settings.allowContentAccess = false
    settings.domStorageEnabled = false
    isHorizontalScrollBarEnabled = true
    webViewClient = WebViewClient()
    // console 转发到 logcat：postFail 只带 e.message，脚本加载失败 /
    // 资源没找到这类信息只有 console error 里有（失败排查的唯一窗口）
    webChromeClient = object : android.webkit.WebChromeClient() {
        override fun onConsoleMessage(msg: android.webkit.ConsoleMessage?): Boolean {
            Log.w(TAG, "console[${msg?.messageLevel()}]: ${msg?.message()}")
            return true
        }
    }
}

/** 失败时展示的原文上限：够看出模型写错在哪，又不至于把卡片撑爆 */
private const val MAX_SOURCE_CHARS = 400

/** 超长图的显示上限（超出内部滚动） */
private val MAX_CARD_HEIGHT = 420.dp

/**
 * 桥接口：**只暴露回报高度与失败**两个方法；没有文件、网络、命令能力。
 *
 * ★ [postFail] 是 2026-10-06 新增：原先只有 `postHeight`，于是 JS 侧渲染失败时
 * 原生侧**收不到任何信号** —— 失败与"还在加载"无法区分，只能永远转圈。
 */
private class HeightBridge(
    private val kind: WebRenderKind,
    private val onHeight: (Int) -> Unit,
    private val onFail: (String) -> Unit,
) {
    @JavascriptInterface
    fun postHeight(px: Int) {
        Log.i(TAG, "postHeight[$kind]: $px")
        onHeight(px)
    }

    /** @param reason 失败原因（JS 侧 `e.message`，可能为空串） */
    @JavascriptInterface
    fun postFail(reason: String?) {
        Log.w(TAG, "postFail[$kind]: ${reason?.take(200)}")
        onFail(reason?.take(120).orEmpty())
    }
}

/**
 * 卡片网页内容。
 *
 * 页面背景透明、文字色用聊天气泡的亮色（由调用方传 currentColor 的 ARGB）——
 * 深色主题下若写死黑字，公式会看不见（这正是眼镜端 ink 页面踩过的同一类坑）。
 */
internal fun htmlFor(
    kind: WebRenderKind,
    source: String,
    textColorHex: Int,
    /** true = 全屏模式：图表容器撑满视口（气泡内是固定 300px，画布按容器尺寸起稿） */
    fill: Boolean = false,
    /**
     * true = 快照模式（推眼镜离屏渲染专用，见 ChartGlassPush）：容器固定 480×640 尺寸
     * （眼镜面板原生分辨率），
     * 渲染完直接 `echarts.getDataURL()` 导出 PNG 并经 postSnapshot 回传。
     * ★ 关键差异：离屏 WebView 未 attach 到窗口 → 不泵 vsync → `requestAnimationFrame`
     * 永不回调，rAF 兜底与高度回报全部失效 —— 必须换 `setTimeout`（timer 线程驱动，
     * 与帧管线无关），并把高度回报替换成快照回传，否则 JS 抛错被外层 catch 吞成 postFail。
     */
    snapshot: Boolean = false,
): String {
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

        // 图表容器必须先有确定尺寸 echarts 才能画（canvas 按容器 clientWidth/Height 起稿）——
        // 与公式/Mermaid 的"内容撑高"模型相反，气泡内固定高度、由容器上报同一量纲。
        // option JSON 塞进 div 文本（HTML 转义），JS 侧用 textContent 取回解析。
        // 全屏高度不写 CSS 100vh：WebView 首次布局前 vh 可能算成 0 → echarts 起稿 0 尺寸
        // 白画布（不抛错、纯白屏）——改为 JS 里用 window.innerHeight 显式设高，时序无关。
        WebRenderKind.CHART -> {
            val h = when {
                snapshot -> "640px"   // 快照：WebView 已手动 layout 成 480×640（眼镜原生），固定高即可
                fill -> "1px"
                else -> "420px"
            }
            // 快照模式必须显式定宽：未 attach 的 WebView 没有 viewport 概念，width:100%
            // 会回退成 ~315px 的默认视口 → 画布窄条。480 与 ChartGlassPush.SNAPSHOT_W
            // 一致（唯一快照调用方）。
            val w = if (snapshot) "480px" else "100%"
            """<div id="target" style="width:$w;height:$h;">$escaped</div>"""
        }
    }
    val chartBootstrap = if (snapshot) {
        """
        var target = document.getElementById('target');
        var chart = echarts.init(target, null, { renderer: 'canvas' });
        // 快照必须在入场动画结束前截取（300ms 时标签还在滑入/淡入中途，字形会互相
        // 重叠、labelLine 呈虚线块，看起来像乱码）——直接禁用动画，定格最终帧。
        opt.animation = false;
        // 480px 画布上 echarts 默认 12px 轴标签隔着波导光学很难读 —— 快照模式统一把
        // fontSize/lineHeight 放大 1.25 档（JSON.parse 后只有纯数据，可安全深遍历）。
        (function (o) {
          function walk(x) {
            if (x && typeof x === 'object') {
              for (var k in x) {
                var v = x[k];
                if (k === 'fontSize' && typeof v === 'number') { x[k] = Math.round(v * 1.25); }
                else if (k === 'lineHeight' && typeof v === 'number') { x[k] = v * 1.25; }
                else { walk(v); }
              }
            }
          }
          walk(o);
        })(opt);
        chart.setOption(opt);
        // 离屏 WebView 不泵 vsync，rAF 永不触发 —— 用 setTimeout 兜 resize，并直接
        // 导出整张画布（dataURL 前缀 "data:image/png;base64," 剥掉只传裸 base64）。
        setTimeout(function () {
          try {
            chart.resize();
            var url = chart.getDataURL({ type: 'png', pixelRatio: 1, backgroundColor: '#000000' });
            var i = url.indexOf(',');
            if (!window.androidBridge || !window.androidBridge.postSnapshot) {
              throw new Error('no postSnapshot bridge');
            }
            window.androidBridge.postSnapshot(i >= 0 ? url.substring(i + 1) : url);
          } catch (e3) {
            if (window.androidBridge) window.androidBridge.postFail('snapshot: ' + e3);
          }
        }, 300);
        """.trimIndent()
    } else {
        """
        var target = document.getElementById('target');
        ${if (fill) "target.style.height = window.innerHeight + 'px';" else ""}
        var chart = echarts.init(target, null, { renderer: 'canvas' });
        chart.setOption(opt);
        // resize 兜底：init 时容器可能还没完成布局（0 尺寸 → 白画布），下一帧重读容器尺寸
        requestAnimationFrame(function () {
          try { chart.resize(); }
          catch (e2) { if (window.androidBridge) window.androidBridge.postFail('resize: ' + e2); }
          if (window.androidBridge) window.androidBridge.postHeight(
            Math.max(document.body.scrollHeight, document.documentElement.scrollHeight));
        });
        """.trimIndent()
    }
    val script = when (kind) {
        WebRenderKind.FORMULA -> """
            try {
              if (typeof katex === 'undefined') throw new Error('katex.js 未加载');
              katex.render(document.getElementById('target').textContent, document.getElementById('target'), {
                throwOnError: false, displayMode: true
              });
              if (window.androidBridge) window.androidBridge.postHeight(
                Math.max(document.body.scrollHeight, document.documentElement.scrollHeight));
            } catch (e) {
              // ★ 上报失败，而不是往 target 里塞一句硬编码中文（2026-10-06 审计修）：
              // 原生侧只有 postHeight 一个信号，收不到失败 ⇒ 只能永远转圈。
              if (window.androidBridge) window.androidBridge.postFail(String(e && e.message || e));
            }
        """.trimIndent()

        WebRenderKind.MERMAID -> """
            try {
              if (typeof mermaid === 'undefined') throw new Error('mermaid.js 未加载');
              mermaid.initialize({ startOnLoad: false, theme: 'base', themeVariables: {
                background: 'transparent', primaryColor: '#2b6cb0', primaryTextColor: '$color',
                lineColor: '$color', fontSize: '14px'
              }});
              await mermaid.run({ nodes: [document.getElementById('target')] });
              if (window.androidBridge) window.androidBridge.postHeight(
                Math.max(document.body.scrollHeight, document.documentElement.scrollHeight));
            } catch (e) {
              // ★ 原这里是**空操作**（textContent = textContent），改回原样等于什么都不做：
              // 语法错时用户只看到一堆原始 mermaid 源码，且完全不知道发生了什么。
              // 现在上报失败，原生侧会显示「无法渲染 + 原因」并把原文一起展示。
              if (window.androidBridge) window.androidBridge.postFail(String(e && e.message || e));
            }
        """.trimIndent()

        WebRenderKind.CHART -> """
            try {
              if (typeof echarts === 'undefined') throw new Error('echarts.js 未加载');
              var raw = document.getElementById('target').textContent;
              console.log('chart src head: ' + raw.substring(0, 400));
              var opt = JSON.parse(raw);
              // 类型摘要：源码截断看不到 series 深处的 type / candlestick 数据顺序，
              // 直接把解析结果打成一行 —— K 线画错时第一时间看出模型给了什么类型
              try {
                console.log('chart types: ' + (opt.series || []).map(function (s) { return s.type || '?'; }).join(','));
                var cs = (opt.series || []).filter(function (s) { return s.type === 'candlestick'; })[0];
                if (cs && cs.data && cs.data[0]) console.log('candle sample: ' + JSON.stringify(cs.data[0]));
              } catch (eX) { console.log('meta log fail: ' + eX); }
              // 深色适配兜底：模型没显式给背景/文字色时，用卡片亮色而不是 echarts 默认的
              // 白底黑字 —— 深色气泡上后者直接看不清（与 KaTeX 传 currentColor 同一个坑）。
              if (!opt.backgroundColor) opt.backgroundColor = 'transparent';
              opt.textStyle = opt.textStyle || {};
              if (!opt.textStyle.color) opt.textStyle.color = '$color';
              $chartBootstrap
            } catch (e) {
              // JSON 写错 / option 非法：上报原因（原生侧展示原文并打日志）
              if (window.androidBridge) window.androidBridge.postFail(String(e && e.message || e));
            }
        """.trimIndent()
    }
    val head = when (kind) {
        WebRenderKind.FORMULA -> """
            <link rel="stylesheet" href="katex/katex.min.css">
            <script src="katex/katex.min.js"></script>
        """.trimIndent()

        WebRenderKind.MERMAID -> """<script src="mermaid.min.js"></script>"""

        WebRenderKind.CHART -> """<script src="echarts.min.js"></script>"""
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
            // 📌 高度回报已移进各分支的 try 内部（成功时立刻报，不用等 setTimeout）：
            // 原实现在脚本末尾用 setTimeout(60ms) 无条件上报，于是
            // ① 渲染失败时也会上报一个"看起来成功"的高度（原生侧收到高度就不显示失败态）；
            // ② 慢解析时多等一拍。现在成功报高度、失败报失败，两条路互斥。
          })();
        </script>
        </body></html>
    """.trimIndent()
}
