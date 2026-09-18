package com.rokidlab.phone.glasses

import android.content.Context
import android.os.Handler
import android.util.Log
import com.rokidlab.phone.R
import com.rokidlab.phone.ai.KnowledgeBase
import com.rokidlab.phone.ai.LocalOcr
import kotlinx.coroutines.CoroutineScope

/**
 * 「拍照问 AI」流程编排器（从 CxrLHiRokidSession 拆出）。
 *
 * 职责：拍照 → 本地 OCR 识别题目 → 知识库检索（RAG）→ OpenAI 兼容模型生成答案
 * → 答案经会话层发回眼镜显示 + tts_play 播报；维护进行中标志与默认 UI 回调。
 *
 * 传输（拍照/AI 下行/连接）由会话层注入，本类只编排流程与阶段状态。
 */
internal class PhotoQuizFlow(
    private val appContext: Context,
    private val appScope: CoroutineScope,
    private val mainHandler: Handler,
    /** 会话层拍照入口（width, height, quality, onPhoto, onError） */
    private val takePhoto: (Int, Int, Int, (ByteArray) -> Unit, (String) -> Unit) -> Unit,
    /** 会话层 AI 问答发送（question, contextText, instruction, onResult, onReply），
     *  固定 skipTtsAudioFinished=true + recordHistory=false（一次性答题语义） */
    private val sendAiQuestion: (
        question: String,
        contextText: String?,
        instruction: String?,
        onResult: (Boolean, String?) -> Unit,
        onReply: (String) -> Unit,
    ) -> Unit,
    /** 设置页填写的答题指令（如「只显示答案」），空串视为无 */
    private val quizInstructionProvider: () -> String,
) {
    companion object {
        private const val TAG = "PhotoQuizFlow"
    }

    /** 流程进行中标志（防止按键/按钮重复触发） */
    @Volatile
    private var inProgress = false

    /**
     * 「拍照问 AI」默认 UI 回调（乐奇聊天界面注册）。
     * 镜腿按键 / 自定义指令触发的 start() 不带显式回调，使用此处注册的回调
     * 在聊天界面展示流程气泡、识别文字与最终答案。
     */
    @Volatile
    private var stageCb: (Int) -> Unit = {}
    @Volatile
    private var textCb: (String) -> Unit = {}
    @Volatile
    private var replyCb: (String) -> Unit = {}

    fun setUiCallbacks(
        onStage: (Int) -> Unit,
        onText: (String) -> Unit,
        onReply: (String) -> Unit,
    ) {
        stageCb = onStage
        textCb = onText
        replyCb = onReply
    }

    /**
     * 「拍照问 AI」全流程（镜腿按键 / 手机端按钮共用入口）：
     * 眼镜拍照 → 本地 OCR 识别题目文字 → 知识库检索（RAG）→ OpenAI 兼容 AI 生成答案
     * → 答案经 Ai 通道发回眼镜显示 + tts_play 语音播报。
     *
     * @param onStage 阶段状态回调（参数为 strings.xml 资源 id，UI 层可展示流程气泡）
     * @param onReply 最终答案回调（同时已发送到眼镜显示+播报）
     */
    fun start(
        onStage: (Int) -> Unit = stageCb,
        onText: (String) -> Unit = textCb,
        onReply: (String) -> Unit = replyCb,
    ) {
        if (inProgress) {
            Log.i(TAG, "start: already in progress, skip")
            return
        }
        inProgress = true
        // 全链路起点：记录开始时间，各阶段打印相对耗时（拍照/OCR/KB/AI），定位「出答案慢」
        val askStartMs = System.currentTimeMillis()
        Log.i(TAG, "start: BEGIN, trigger=photo ask")
        onStage(R.string.chat_photo_status)

        // takePhoto 必须在 try 内：它内部会走 PhotoQuizService → CXRLink.setCXRImageCbk /
        // takePhoto（SDK 状态非法时同步抛异常，那条路径没有任何 try/catch）。
        // 原先它在 try 之外，异常会沿 start() 逃逸到调用线程（AsrBridgeCoordinator 的
        // Thread{ onPhotoAsk() } 或 appScope.launch），成为未捕获异常直接崩进程。
        try {
            takePhoto(
                1024,
                768,
                80,
                { jpeg ->
                    Thread {
                        try {
                            Log.i(TAG, "photoAsk: photo received (${jpeg.size}B) after ${System.currentTimeMillis() - askStartMs}ms, starting OCR")
                            mainHandler.post { onStage(R.string.chat_ocr_status) }
                            // 2) 本地 OCR 识别题目文字（模型不内置 APK，首次使用先从 Gitee 下载约 15.4MB）
                            val tOcr = System.currentTimeMillis()
                            val text = runCatching {
                                val bmp = android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
                                    ?: return@runCatching ""
                                try {
                                    LocalOcr.ensureInit(appContext) { pct ->
                                        if (pct % 20 == 0) Log.i(TAG, "photoAsk: OCR model downloading $pct%")
                                    }
                                    LocalOcr.recognize(appContext, bmp)
                                } finally {
                                    bmp.recycle()
                                }
                            }.getOrDefault("").trim()
                            Log.i(TAG, "photoAsk: OCR done in ${System.currentTimeMillis() - tOcr}ms -> ${text.take(50)}")
                            if (text.isEmpty()) {
                                // 区分降级原因：设备不支持 native / 模型未就绪（下载失败）/ 确实没识别到文字
                                val stageRes = when {
                                    LocalOcr.nativeUnavailable -> R.string.chat_ocr_native_unavailable
                                    LocalOcr.modelsUnavailable -> R.string.chat_ocr_model_unavailable
                                    else -> R.string.chat_ocr_empty
                                }
                                Log.w(TAG, "photoAsk: OCR result empty, abort (nativeUnavailable=${LocalOcr.nativeUnavailable}, modelsUnavailable=${LocalOcr.modelsUnavailable})")
                                inProgress = false
                                mainHandler.post { onStage(stageRes) }
                                return@Thread
                            }
                            // 3) 把识别出的文字回调给 UI（作为「用户消息」气泡展示）
                            mainHandler.post { onText(text) }
                            // 4) 知识库检索相关资料（RAG）
                            mainHandler.post { onStage(R.string.chat_kb_status) }
                            val tKb = System.currentTimeMillis()
                            val kbText = KnowledgeBase
                                .searchHits(appContext, text, topK = 3)
                                .joinToString("\n\n") { hit ->
                                    // 来源标注：让答案可溯源（出自哪份文档的哪一块）
                                    "（《${hit.docName}》第${hit.chunkIdx + 1}块）${hit.text}"
                                }
                            Log.i(TAG, "photoAsk: KB search done in ${System.currentTimeMillis() - tKb}ms, hits=${kbText.length} chars")
                            // 4) 生成答案并发送到眼镜（显示 + 播报）
                            mainHandler.post { onStage(R.string.chat_ai_status) }
                            val tAi = System.currentTimeMillis()
                            sendAiQuestion(
                                text,
                                kbText.ifBlank { null },
                                quizInstructionProvider().ifBlank { null },
                                { success, err ->
                                    inProgress = false
                                    Log.i(TAG, "photoAsk: AI send onResult success=$success err=$err after ${System.currentTimeMillis() - tAi}ms (total ${System.currentTimeMillis() - askStartMs}ms)")
                                    if (!success) {
                                        // 失败且无 onReply：通知 UI 复位 photoAsking（否则拍照问 AI 入口永久失效）
                                        mainHandler.post { onStage(R.string.chat_photo_failed) }
                                    }
                                },
                                { reply ->
                                    Log.i(TAG, "photoAsk: AI reply received (${reply.length} chars) after ${System.currentTimeMillis() - askStartMs}ms total")
                                    onReply(reply)
                                },
                            )
                        } catch (e: Throwable) {
                            // 必须捕 Throwable：本线程是未捕获异常的终点，Error 逃逸即崩进程；
                            // 且必须复位 inProgress，否则拍照答题入口永久失效。
                            Log.e(TAG, "startPhotoAsk failed", e)
                            inProgress = false
                            mainHandler.post { onStage(R.string.chat_photo_failed) }
                        }
                    }.apply { name = "photo-quiz-ocr"; isDaemon = true }.start()
                },
                { err ->
                    Log.e(TAG, "photoAsk photo error: $err")
                    inProgress = false
                    mainHandler.post { onStage(R.string.chat_photo_failed) }
                },
            )
        } catch (e: Throwable) {
            Log.e(TAG, "startPhotoAsk: takePhoto threw synchronously", e)
            inProgress = false
            mainHandler.post { onStage(R.string.chat_photo_failed) }
        }
    }
}
