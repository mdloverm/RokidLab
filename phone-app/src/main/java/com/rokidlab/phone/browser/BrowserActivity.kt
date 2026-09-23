package com.rokidlab.phone.browser

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.rokidlab.phone.R
import com.rokidlab.phone.design.BrewBorder
import com.rokidlab.phone.design.BrewCyan
import com.rokidlab.phone.design.BrewMuted
import com.rokidlab.phone.design.BrewPanelHi
import com.rokidlab.phone.design.BrewText
import com.rokidlab.phone.design.BrewTextBright

/**
 * 手机端**真实浏览器**页面（WebView 前台可见）。
 *
 * ## 为什么必须是"看得见的浏览器"
 * 这条能力的卖点是"替用户在真实网站上做成一件事"，而真实网站必然有登录、验证码、
 * 二次确认。可见 + 可手动接管是它和"偷偷发请求"的本质区别：
 *  - 登录态：用户在这个页面上自己登录一次，Cookie 由 `CookieManager` 保存在 App 私有目录
 *    （跨次启动有效），后续 agent 的浏览都带着这份登录态；
 *  - 每一步操作都发生在用户眼前，出问题随时能自己接手。
 *
 * ## 与对话的关系
 * 页面本身不含任何"AI 操作面板"——操作入口只有对话里的 `browser_*` 工具，
 * 避免同一动作两套入口（两套入口必然分叉）。顶栏只放**人**需要的东西：返回、刷新、地址。
 */
class BrowserActivity : ComponentActivity() {

    companion object {
        private const val TAG = "BrowserActivity"
        private const val EXTRA_URL = "url"

        internal fun intent(ctx: Context, url: String): Intent =
            Intent(ctx, BrowserActivity::class.java).putExtra(EXTRA_URL, url)
    }

    /** 页面宿主（[BrowserSession] 经它执行 JS 与导航） */
    internal lateinit var webView: WebView
        private set

    /**
     * 页面标题。
     *
     * ⚠️ 刻意不叫 `title`：`Activity` 自带同名属性（`getTitle()`），子类再声明一个 `title`
     * 会被编译器解析到父类那个（表现为"val 不能重新赋值"的一串怪错）。同理不与 [BrowserScreen]
     * 的形参同名混淆 —— 这里的名字就是唯一的。
     */
    private var pageTitle by mutableStateOf("")
    private var address by mutableStateOf("")
    private var loading by mutableStateOf(false)

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 浏览期间不让息屏：用户要盯着 agent 操作，黑屏就等于看不见它在干什么
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        webView = WebView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            settings.apply {
                javaScriptEnabled = true
                // 大量站点（含各类论坛/后台）依赖 localStorage 存登录态
                domStorageEnabled = true
                databaseEnabled = true
                // 只允许 http(s)：file:// 能读到本机文件，不该由"打开一个网址"顺带获得
                allowFileAccess = false
                allowContentAccess = false
                javaScriptCanOpenWindowsAutomatically = false
                setSupportMultipleWindows(false)
                cacheMode = WebSettings.LOAD_DEFAULT
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest,
                ): Boolean = blockNonHttp(request.url.toString())

                override fun onPageFinished(view: WebView, url: String) {
                    super.onPageFinished(view, url)
                    loading = false
                    address = url
                    if (pageTitle.isBlank()) pageTitle = view.title.orEmpty()
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    loading = newProgress < 100
                }

                override fun onReceivedTitle(view: WebView, t: String?) {
                    if (!t.isNullOrBlank()) pageTitle = t
                }
            }
        }
        BrowserSession.attach(this)
        BrowserSession.markWebViewReady()
        // 返回键优先退网页（这是浏览器，不是普通页面）
        onBackPressedDispatcher.addCallback(this) {
            if (webView.canGoBack()) webView.goBack() else finish()
        }
        intent.getStringExtra(EXTRA_URL)?.takeIf { it.isNotBlank() }?.let { loadUrl(it) }
        setContent {
            BrowserScreen(
                webView = webView,
                pageTitle = pageTitle,
                address = address,
                loading = loading,
                onExit = { finish() },
            )
        }
    }

    override fun onDestroy() {
        BrowserSession.detach(this)
        runCatching {
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.stopLoading()
            webView.destroy()
        }.onFailure { Log.w(TAG, "销毁 WebView 失败: ${it.message}") }
        super.onDestroy()
    }

    internal fun loadUrl(url: String) {
        Log.i(TAG, "loadUrl: $url")
        address = url
        webView.loadUrl(url)
    }

    internal fun currentUrl(): String? =
        runCatching { webView.url }.getOrNull() ?: address.takeIf { it.isNotBlank() }

    internal fun canGoBack(): Boolean = runCatching { webView.canGoBack() }.getOrDefault(false)

    internal fun goBack() {
        webView.goBack()
    }

    /**
     * 导航闸门：只允许 http/about 留在本 WebView 里。
     *
     * 其余 scheme（`intent://`、`market://`、`weixin://`、`tel:` …）**不放行**：
     * 它们会跳出 App 去拉起别的应用，而 agent 此时以为"页面已经跳转"，
     * 后续快照读到的还是旧页面，表现为"点了没反应"却不报错。
     * 返回 true = 由我们消费掉这次导航（WebView 不加载）。
     */
    private fun blockNonHttp(url: String): Boolean {
        val scheme = runCatching { Uri.parse(url).scheme?.lowercase() }.getOrNull()
        if (scheme == null || scheme == "http" || scheme == "https" || scheme == "about") return false
        Log.i(TAG, "拦截非 http(s) 导航: $url")
        return true
    }
}

/**
 * 浏览器界面：顶栏（返回 / 标题 / 网址 / 刷新）+ WebView + 一行常驻说明。
 *
 * 说明那行是**给用户看的唯一操作提示**（登录要自己来），必须常驻而不是弹一次：
 * 用户可能中途才拿起手机，看不到任何弹窗。
 */
@Composable
private fun BrowserScreen(
    webView: WebView,
    pageTitle: String,
    address: String,
    loading: Boolean,
    onExit: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(BrowserBarBg)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.browser_exit),
                color = BrewCyan,
                fontSize = 13.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onExit() }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = pageTitle.ifBlank { stringResource(R.string.browser_title) },
                    color = BrewTextBright,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (address.isNotBlank()) {
                    Text(
                        text = address,
                        color = BrewMuted,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Text(
                text = stringResource(R.string.browser_refresh),
                color = BrewText,
                fontSize = 13.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(BrewPanelHi)
                    .border(1.dp, BrewBorder, RoundedCornerShape(8.dp))
                    .clickable { webView.reload() }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
        if (loading) {
            Text(
                text = stringResource(R.string.browser_loading),
                color = BrewMuted,
                fontSize = 11.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, top = 4.dp),
            )
        }
        AndroidView(
            factory = { webView },
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        )
        Text(
            text = stringResource(R.string.browser_hint),
            color = BrewMuted,
            fontSize = 11.sp,
            modifier = Modifier
                .fillMaxWidth()
                .background(BrowserBarBg)
                .padding(horizontal = 14.dp, vertical = 6.dp),
        )
    }
}

/** 顶栏/底栏底色（与乐奇深色面板同色系，但不是主题色变量 —— 这里只是浏览器外壳） */
private val BrowserBarBg = Color(0xFF12161F)
