package com.rokidlab.phone.domain

import android.util.Log
import com.rokid.cxr.link.CXRLink
import com.rokid.cxr.link.callbacks.IImageStreamCbk
import com.rokidlab.phone.glasses.CxrLHiRokidSession
import com.rokidlab.phone.glasses.PhotoQuizFlow
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 「拍照问 AI」域服务（Phase 3 三轮：从 CxrLHiRokidSession 逐字迁出，session-handle 门面模式）。
 *
 * 持有 PhotoQuizFlow 编排器与拍照请求超时任务；Session 保留 public 门面
 * （setPhotoAskUiCallbacks / takeGlassesPhoto / startPhotoAsk），调用方零改动。
 * TAG 沿用 CxrLHiRokidSession 以保持既有 logcat 过滤不变。
 */
internal class PhotoQuizService(private val session: CxrLHiRokidSession) {
    companion object {
        private const val TAG = "CxrLHiRokidSession"
    }

    private var photoRequestTimeoutJob: Job? = null

    /** 拍照超时任务取消（Session.cleanup 统一清理入口调用） */
    fun cancelPhotoRequestTimeout() {
        photoRequestTimeoutJob?.cancel()
        photoRequestTimeoutJob = null
    }

    /** 「拍照问 AI」流程编排器（拍照→OCR→RAG→AI 答题，从本类拆出） */
    private val photoQuiz = PhotoQuizFlow(
        appContext = session.appContext,
        appScope = session.appScope,
        mainHandler = session.mainHandler,
        takePhoto = { w, h, q, onPhoto, onError -> takeGlassesPhoto(w, h, q, onPhoto, onError) },
        sendAiQuestion = { question, contextText, instruction, onResult, onReply ->
            session.sendAiTextMessage(
                question,
                contextText = contextText,
                // 答题完成后保留眼镜端回复显示：skipTtsAudioFinished=true 不发送
                // TTS_AudioFinished（该消息会触发官方会话 startNewTalk 重置，清掉刚显示的答案）
                skipTtsAudioFinished = true,
                // 注入设置页填写的答题指令（如「只显示答案」「给出解题步骤」）
                instruction = instruction,
                // 一次性问答且带答题指令，不记录到 Agent 会话记忆（避免污染闲聊上下文）
                recordHistory = false,
                onResult = onResult,
                onReply = onReply,
            )
        },
        quizInstructionProvider = { session.getAiConfig().quizInstruction },
    )

    /** 注册「拍照问 AI」流程的 UI 回调（乐奇聊天界面进入时调用，按键触发时复用展示） */
    fun setPhotoAskUiCallbacks(
        onStage: (Int) -> Unit,
        onText: (String) -> Unit,
        onReply: (String) -> Unit,
        onStageText: (String) -> Unit = {},
    ) = photoQuiz.setUiCallbacks(onStage, onText, onReply, onStageText)

    /**
     * 远程控制眼镜拍照，通过 IImageStreamCbk 回调获取 JPEG 图片字节。
     * 用于「拍照问 AI」：拍照 → 本地 OCR 识别 → 知识库检索 → DeepSeek 生成答案。
     *
     * @param width/height/quality 拍照参数（推荐 1024/768/80）
     * @param onPhoto 拍照成功，返回 JPEG 字节
     * @param onError 拍照失败原因
     */
    fun takeGlassesPhoto(
        width: Int = 1024,
        height: Int = 768,
        quality: Int = 80,
        onPhoto: (ByteArray) -> Unit,
        onError: (String) -> Unit,
    ) {
        Log.i(TAG, "takeGlassesPhoto($width,$height,$quality) called. session.cxrlConnected=${session.cxrlConnected}, session.glassBtConnected=${session.glassBtConnected}, session.cxrLink=${session.cxrLink != null}")

        // 快速路径: 已有连接直接拍照，拍完保持连接（乐奇聊天可继续使用）
        val link = session.cxrLink
        if (session.cxrlConnected && session.glassBtConnected && link != null) {
            Log.i(TAG, "takeGlassesPhoto: fast path, using existing CXRLink")
            requestPhotoFromLink(link, width, height, quality, onPhoto, onError, cleanupOnDone = false)
            return
        }

        // 慢速路径: 先建立连接再拍照
        Log.i(TAG, "takeGlassesPhoto: no active link, falling back to connectAndRun path")
        val targetHostApp = session.hostApp
        if (!session.hasGlassesOperationPrerequisites(targetHostApp, requestAuthorizationIfMissing = true)) {
            Log.w(TAG, "takeGlassesPhoto: missing prerequisites")
            onError("missing prerequisites")
            return
        }
        val authToken = session.token.orEmpty()

        session.onBusyChanged(true)
        session.connection.connectAndRunCustomAppOperation(
            authToken = authToken,
            targetHostApp = targetHostApp,
            operation = CxrAppOperation(
                packageName = "com.rokidlab.rokidlink",
                timeoutMillis = 20_000,
                timeoutMessage = "photo request timeout",
                bindMessage = "Taking photo",
                configureFailureMessage = "Configure CXR-L CUSTOMAPP session failed",
                bindFailureMessage = "Bind host service failed",
                showConnectionStatus = false,
                onReady = { l ->
                    // cleanupOnDone=false + resetBusyOnDone=true：拍照完成后保持 CXR 链路复用
                    // （避免每次按键重建连接 5-10s，导致「按键后出答案慢」），同时复位 busy 状态；
                    // 后续按键/聊天直接走 fast path
                    requestPhotoFromLink(l, width, height, quality, onPhoto, onError, cleanupOnDone = false, resetBusyOnDone = true)
                },
                onFailure = {
                    session.cleanup()
                    session.onBusyChanged(false)
                    onError("connection failed")
                },
            ),
        )
    }

    /** 「拍照问 AI」全流程入口（无显式回调，用聊天界面注册的默认回调；编排见 PhotoQuizFlow.start） */
    fun startPhotoAsk() = photoQuiz.start()

    /** 「拍照问 AI」全流程入口（显式回调；编排见 PhotoQuizFlow.start） */
    fun startPhotoAsk(
        onStage: (Int) -> Unit,
        onText: (String) -> Unit,
        onReply: (String) -> Unit,
        onStageText: (String) -> Unit = {},
    ) = photoQuiz.start(onStage, onText, onReply, onStageText)

    private fun requestPhotoFromLink(
        link: CXRLink,
        width: Int,
        height: Int,
        quality: Int,
        onPhoto: (ByteArray) -> Unit,
        onError: (String) -> Unit,
        cleanupOnDone: Boolean,
        /**
         * 完成后是否复位 busy（fast path 未设 busy 时为 false，避免多余 UI 刷新；
         * slow path 保持连接时需显式复位 busy）
         */
        resetBusyOnDone: Boolean = cleanupOnDone,
    ) {
        var done = false
        fun finish(onResult: () -> Unit) {
            if (done) return
            done = true
            photoRequestTimeoutJob?.cancel()
            photoRequestTimeoutJob = null
            session.connection.completeActiveOperation()
            if (cleanupOnDone) {
                session.cleanup()
                session.onBusyChanged(false)
            } else if (resetBusyOnDone) {
                // 保持连接复用（避免每次按键重建 CXR 链路）：复位 busy 但不断开 session.cxrLink
                session.onBusyChanged(false)
            }
            onResult()
        }

        // 超时兜底：takePhoto 返回成功但 onImageReceived/onImageError 永不到达
        // （SDK 静默失败/眼镜端场景被关闭）时，复位标志并回调错误，避免后续拍照被永久跳过
        photoRequestTimeoutJob?.cancel()
        photoRequestTimeoutJob = session.appScope.launch {
            delay(15_000)
            session.mainHandler.post {
                if (!done) {
                    Log.w(TAG, "requestPhotoFromLink: no image callback within 15s, forcing error")
                    finish { onError("photo timeout") }
                }
            }
        }

        link.setCXRImageCbk(object : IImageStreamCbk {
            override fun onImageReceived(data: ByteArray) {
                Log.i(TAG, "onImageReceived: ${data.size} bytes")
                session.mainHandler.post { finish { onPhoto(data) } }
            }

            override fun onImageError(code: Int, message: String) {
                Log.e(TAG, "onImageError($code): $message")
                session.mainHandler.post { finish { onError("photo error($code): $message") } }
            }
        })

        val ok = link.takePhoto(width, height, quality)
        Log.i(TAG, "takePhoto -> $ok")
        if (!ok) {
            session.mainHandler.post { finish { onError("takePhoto failed") } }
        }
    }
}
