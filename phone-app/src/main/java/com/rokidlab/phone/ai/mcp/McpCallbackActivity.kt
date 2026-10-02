package com.rokidlab.phone.ai.mcp

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlin.concurrent.thread

/**
 * MCP OAuth 回调落地页（透明、即用即走）。
 *
 * 浏览器完成授权后重定向到 `rokidlab://mcp/callback?code=…&state=…`，
 * 系统据此拉起本页；本页把 code 交给 [McpOAuth.handleCallback] 换 token，
 * 成功后自动重连对应 server，然后立即 finish（用户感知只是"浏览器闪一下回到 App"）。
 *
 * ⚠️ Manifest 的 intent-filter（scheme/host/pathPrefix）必须与 [McpOAuth.REDIRECT_URI]
 * 同步改 —— 两处只改一处 = 浏览器回调永远到不了 App，且没有任何报错。
 */
internal class McpCallbackActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handle(intent?.data?.toString())
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handle(intent?.data?.toString())
    }

    private fun handle(callbackUrl: String?) {
        if (callbackUrl.isNullOrBlank()) {
            finish()
            return
        }
        // 换 token 是网络请求（秒级）：放后台，先 finish 再异步收尾会导致 Toast 没有附着窗口，
        // 因此等结果出来（后台线程 + 主线程回调）再 finish
        thread(name = "mcp-oauth-callback") {
            val ctx = applicationContext
            val error = try {
                McpOAuth.handleCallback(ctx, callbackUrl)
            } catch (e: Exception) {
                Log.w(TAG, "oauth callback failed: ${e.message}")
                "授权异常：${e.message}"
            }
            runOnUiThread {
                if (error == null) {
                    Toast.makeText(this, "授权成功", Toast.LENGTH_SHORT).show()
                    reconnectAuthorizedServer()
                } else {
                    Toast.makeText(this, error, Toast.LENGTH_LONG).show()
                }
                finish()
            }
        }
    }

    /** 授权成功后立即按最新配置重连该 server（新 token 随下一个连接生效） */
    private fun reconnectAuthorizedServer() {
        val serverId = McpOAuth.lastAuthorizedServerId ?: return
        val ctx = applicationContext
        thread(name = "mcp-oauth-reconnect") {
            val cfg = McpServerStore.load(ctx).firstOrNull { it.id == serverId } ?: return@thread
            runCatching { McpRegistry.reload(ctx, cfg) }
                .onFailure { Log.w(TAG, "reconnect after oauth failed: ${it.message}") }
        }
    }

    private companion object {
        const val TAG = "McpCallback"
    }
}
