package com.rokidlab.phone.store

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.addCallback
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.rokidlab.phone.R
import com.rokidlab.phone.design.BrewBg
import com.rokidlab.phone.design.BrewMuted
import com.rokidlab.phone.design.BrewRed
import com.rokidlab.phone.design.BrewTextBright
import com.rokidlab.phone.platform.WebPreviewManager
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.launch

/**
 * 本机网页预览的**独立全屏 Activity**（与 [com.rokidlab.phone.browser.BrowserActivity] 同款架构）。
 *
 * ## 为什么必须是独立 Activity（2026-10-13 排查实录，别改回去）
 * WebView 挂在聊天页 Compose 树的 interop 容器里（无论 `Dialog` 还是 `AndroidView`）
 * 在真机上进不了渲染管线：视图树 VISIBLE 但 PFLAG_DRAWN 不置位、Blink 视口高度卡死 0
 * （html/body computed height=0px，`overflow:hidden` 把内容全裁光）、compositor 停止产帧
 * ⇒ 用户只见一片背景色。WebView 在**独立轻量 Activity 的 onCreate 里创建**则一切正常
 * （BrowserActivity 长期验证）。宿主演进史详见 `WebPreviewUi.kt` 文件头。
 *
 * ## 生命周期与 Manager 的关系
 * - 预览服务归 [WebPreviewManager] 管（常驻进程，Activity 只是「看它的窗口」）；
 * - Activity 监听 [WebPreviewManager.previews]：自己这个 id 消失（服务停止/进程自退）就
 *   自动 finish，不留一个指着死服务的黑窗口；
 * - 关闭/返回键只是 finish，**不停服务**——预览是持续状态，关了还能从预览条再打开。
 */
class WebPreviewActivity : ComponentActivity() {

    companion object {
        private const val TAG = "WebPreviewActivity"
        private const val EXTRA_ID = "preview_id"
        private const val EXTRA_URL = "preview_url"

        internal fun intent(ctx: Context, info: WebPreviewManager.PreviewInfo): Intent =
            Intent(ctx, WebPreviewActivity::class.java)
                .putExtra(EXTRA_ID, info.id)
                .putExtra(EXTRA_URL, info.url)
    }

    private var webView: WebView? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val previewId = intent.getStringExtra(EXTRA_ID).orEmpty()
        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        if (previewId.isBlank() || url.isBlank()) {
            Log.w(TAG, "缺少预览参数，直接退出")
            finish()
            return
        }

        setContentView(buildPreviewHost(previewId, url))

        // 服务没了（停止/进程自退/stopAll）⇒ 窗口自动关，别挂在死服务上
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                WebPreviewManager.previews.collect { list ->
                    if (list.none { it.id == previewId }) finish()
                }
            }
        }

        // 返回键直接退层（本地页面没有历史栈可回）
        onBackPressedDispatcher.addCallback(this) { finish() }
    }

    override fun onDestroy() {
        runCatching {
            webView?.stopLoading()
            webView?.destroy()
            webView = null
        }.onFailure { Log.w(TAG, "销毁 WebView 失败: ${it.message}") }
        super.onDestroy()
    }

    /**
     * 经典 View 拼装的预览层：顶栏（标题/地址/✕）+ WebView + 底部停止键。
     * ⚠️ 保持纯 View 宿主——不要把 WebView 放回 Compose 树（见类注释）。
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun buildPreviewHost(previewId: String, url: String): LinearLayout {
        val bg = BrewBg.toArgb()
        val titleColor = BrewTextBright.toArgb()
        val muted = BrewMuted.toArgb()
        val red = BrewRed.toArgb()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }

        // ── 顶栏：标题 + 地址 + 关闭 ──
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(8), dp(4))
        }
        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleCol.addView(TextView(this).apply {
            text = getString(R.string.chat_preview_bar_label)
            setTextColor(titleColor)
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
        })
        titleCol.addView(TextView(this).apply {
            text = url
            setTextColor(muted)
            textSize = 11f
            maxLines = 1
        })
        header.addView(titleCol)
        header.addView(TextView(this).apply {
            text = "✕"
            setTextColor(muted)
            textSize = 20f
            setPadding(dp(16), dp(8), dp(16), dp(8))
            setOnClickListener { finish() }
        })
        root.addView(
            header,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )

        // ── 页面本体：纯 View 树里的 WebView（缓存口径见 WebPreviewUi 文件头）──
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            webViewClient = WebViewClient()
            loadUrl(url)
        }
        root.addView(
            webView,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f),
        )

        // ── 底部：停止服务（真停进程；Manager 摘除后 previews 流变化会自动 finish）──
        val stopBtn = TextView(this).apply {
            text = getString(R.string.chat_preview_stop)
            setTextColor(red)
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(12))
            setOnClickListener {
                WebPreviewManager.stop(this@WebPreviewActivity, previewId)
                Toast.makeText(this@WebPreviewActivity, getString(R.string.chat_preview_stop_done), Toast.LENGTH_SHORT).show()
                finish()
            }
        }
        root.addView(
            stopBtn,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )

        return root
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
