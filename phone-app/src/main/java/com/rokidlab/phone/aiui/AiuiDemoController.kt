package com.rokidlab.phone.aiui

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File

/**
 * 手机端 AIUI 演示的全局状态：**当前正在手机上演示的那个 .aix** + 它的渲染宿主。
 *
 * 为什么要有它：`open_aiui_app` 是工具（跑在 worker 线程），而"在对话里放一张演示卡片"
 * 是 UI（跑在 Compose 快照线程）。两者之间不能靠工具返回值传状态 —— 返回值是给**模型**看的
 * 文本（模型会照着它组织回复），往里塞 UI 协议会把两件事搅在一起。所以 UI 副作用走这里：
 * 工具调用 [show]，[com.rokidlab.phone.store.ChatScreen] 观察 [current] 并渲染卡片。
 *
 * 与 `show_image` 的 `ChatStateHolder.addImage` 是同一模式（工具触发 UI 副作用），
 * 差别只在于演示卡片**不写会话历史** —— 它是一次性的操作入口，重进聊天页不该复活。
 */
internal object AiuiDemoController {

    /**
     * 一次手机端演示会话。
     *
     * @param appName 展示用名字（卡片标题 / 全屏页标题）
     * @param agentId 智能体 id（关闭时用来判断"关的是不是当前这个"）
     * @param aix 本机 .aix 包（手机宿主直接读文件，不走网络）
     * @param launchParams 启动参数（JSON 对象字符串，可为 null）
     */
    internal data class Session(
        val appName: String,
        val agentId: String,
        val aix: File,
        val launchParams: String?,
    )

    /** 当前演示会话；null = 没有在演示 */
    var current by mutableStateOf<Session?>(null)
        private set

    /** 页面是否已出首帧（host.js `notify('ready')`）。false 期间卡片显示占位而不是黑屏。 */
    var rendered by mutableStateOf(false)
        private set

    /**
     * 渲染失败原因（host.js boot 抛错时回传）。非 null 时卡片用文字替代黑屏。
     *
     * 没有它的话，任何启动失败（wasm 没跑起来、bundle 装配异常…）表现都是**一块没有任何
     * 解释的纯黑**，用户和排查的人都只能猜 —— 这正是"手机端为什么是黑的"当初的来源。
     */
    var lastError by mutableStateOf<String?>(null)
        private set

    /**
     * 演示宿主（WebView）。**由本 object 持有，而不是让卡片 `remember`**：
     * 卡片现在是消息列表里的一项，用户上下滚动会把它回收重组 —— 随组合销毁的话，
     * 滚回来要重新加载 20MB wasm（十几秒），演示直接没法用。
     */
    private var host: AiuiWebHost? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 开始（或替换）手机端演示。任意线程可调。 */
    fun show(session: Session) {
        runOnMain {
            host?.destroy()
            host = null
            rendered = false
            lastError = null
            current = session
        }
    }

    /**
     * 取当前会话的渲染宿主，没有会话时返回 null。**必须在主线程调用**（WebView 只能在
     * 创建它的线程上访问，而卡片组合发生在主线程）。
     *
     * 幂等：同一个会话反复调用只建一次。
     */
    fun hostFor(ctx: Context): AiuiWebHost? {
        val session = current ?: return null
        host?.let { return it }
        return AiuiWebHost(
            ctx = ctx,
            aixFile = session.aix,
            launchParams = session.launchParams,
            onReady = { rendered = true },
            onLoadError = { lastError = it },
        ).also { host = it }
    }

    /** 结束手机端演示：收掉对话里的卡片并销毁宿主。任意线程可调。 */
    fun dismiss() {
        runOnMain {
            host?.destroy()
            host = null
            current = null
            rendered = false
            lastError = null
        }
    }

    /**
     * 结束手机端演示的**全部入口**：卡片 + 全屏操作页。
     *
     * `stop_aiui_app` 走这条：用户说"关掉手机上的演示"时，可能人正在全屏操作页里，
     * 只清卡片状态会让全屏页留在屏幕上 —— 用户看到的是"说了关掉却什么都没关"。
     *
     * ⚠️ 与 [dismiss] 的区别是有意的：卡片自己点"关闭"时不能连带关掉全屏页，
     * 那时用户只是想把预览收起来，全屏页还在他手上操作。
     */
    fun dismissAll() {
        AiuiDemoActivity.finishIfRunning()
        dismiss()
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }
}
