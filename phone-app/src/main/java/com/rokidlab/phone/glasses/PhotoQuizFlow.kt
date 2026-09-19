package com.rokidlab.phone.glasses

import android.content.Context
import android.os.Handler
import android.util.Base64
import android.util.Log
import com.rokidlab.phone.R
import com.rokidlab.phone.ai.KnowledgeBase
import com.rokidlab.phone.ai.LocalOcr
import kotlinx.coroutines.CoroutineScope

/**
 * 「拍照问 AI」流程编排器（从 CxrLHiRokidSession 拆出）。
 *
 * 职责：拍照 → **图像理解（多模态）或本地 OCR** → 知识库检索（RAG）→ OpenAI 兼容模型
 * 生成答案 → 答案经会话层发回眼镜显示 + tts_play 播报；维护进行中标志与默认 UI 回调。
 *
 * **两条识别路径**（2026-09-19 新增，由 [imageInputDecisionProvider] 决定）：
 * - 走 OCR（默认，也是视觉路失败时的回退）：本地 OCR（PP-OCRv4）把图转成文字再交给模型。
 *   不依赖模型能力，但只留下文字 —— 版式、图形、颜色全部丢失。
 * - 走视觉：JPEG 以 `data:image/jpeg;base64,…` 直接作为 user 消息的 image_url 分片
 *   发给模型，保留完整视觉信息。**能否生效取决于当前模型是否支持图像输入** ——
 *   服务端拒绝时自动回退 OCR 重试，不让用户卡在失败态。
 *
 * ⚠️ 2026-09-19 `llm` 接缝化：原来这里只拿到一个 `Boolean`（"用户开没开图像理解开关"），
 * 于是「用户没开」和「开了但模型根本不支持」被压成同一件事 —— 后者静默走 OCR，
 * 用户明明开了开关却不知道功能其实用不了。现在改为三态决策
 * （[com.rokidlab.phone.ai.llm.ImageInputDecision]），"模型不支持"这一支会把原因显示出来。
 *
 * 传输（拍照/AI 下行/连接）由会话层注入，本类只编排流程与阶段状态。
 */
internal class PhotoQuizFlow(
    private val appContext: Context,
    private val appScope: CoroutineScope,
    private val mainHandler: Handler,
    /** 会话层拍照入口（width, height, quality, onPhoto, onError） */
    private val takePhoto: (Int, Int, Int, (ByteArray) -> Unit, (String) -> Unit) -> Unit,
    /** 会话层 AI 问答发送（question, contextText, instruction, imageBase64, onResult, onReply），
     *  固定 skipTtsAudioFinished=true + recordHistory=false（一次性答题语义） */
    private val sendAiQuestion: (
        question: String,
        contextText: String?,
        instruction: String?,
        imageBase64: String?,
        onResult: (Boolean, String?) -> Unit,
        onReply: (String) -> Unit,
    ) -> Unit,
    /** 设置页填写的答题指令（如「只显示答案」），空串视为无 */
    private val quizInstructionProvider: () -> String,
    /**
     * 本次拍照该走哪条识别路径（每次拍照现读，切换即生效）：
     * 由会话层根据「用户开关 + 当前模型能力」在 [com.rokidlab.phone.ai.llm.LlmRegistry] 里问出来。
     */
    private val imageInputDecisionProvider: () -> com.rokidlab.phone.ai.llm.ImageInputDecision =
        { com.rokidlab.phone.ai.llm.ImageInputDecision.SwitchOff },
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
    private var stageTextCb: (String) -> Unit = {}
    @Volatile
    private var textCb: (String) -> Unit = {}
    @Volatile
    private var replyCb: (String) -> Unit = {}

    fun setUiCallbacks(
        onStage: (Int) -> Unit,
        onText: (String) -> Unit,
        onReply: (String) -> Unit,
        onStageText: (String) -> Unit = {},
    ) {
        stageCb = onStage
        textCb = onText
        replyCb = onReply
        stageTextCb = onStageText
    }

    /**
     * 「拍照问 AI」全流程（镜腿按键 / 手机端按钮共用入口）：
     * 眼镜拍照 → 识别（多模态看图 或 本地 OCR）→ 知识库检索（RAG）→ AI 生成答案
     * → 答案经 Ai 通道发回眼镜显示 + tts_play 语音播报。
     *
     * @param onStage 阶段状态回调（参数为 strings.xml 资源 id，UI 层可展示流程气泡）
     * @param onStageText 动态文本阶段回调（如 OCR 模型下载百分比），UI 层原地更新同一条状态气泡
     * @param onReply 最终答案回调（同时已发送到眼镜显示+播报）
     */
    fun start(
        onStage: (Int) -> Unit = stageCb,
        onText: (String) -> Unit = textCb,
        onReply: (String) -> Unit = replyCb,
        onStageText: (String) -> Unit = stageTextCb,
    ) {
        if (inProgress) {
            Log.i(TAG, "start: already in progress, skip")
            return
        }
        inProgress = true
        // 全链路起点：记录开始时间，各阶段打印相对耗时（拍照/识别/KB/AI），定位「出答案慢」
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
                        handlePhoto(jpeg, askStartMs, onStage, onText, onReply, onStageText)
                    }.apply { name = "photo-quiz"; isDaemon = true }.start()
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

    /**
     * 拿到照片后的分岔：按 [imageInputDecisionProvider] 的结论决定走视觉还是走本地 OCR。
     *
     * 必须捕 Throwable：本线程是未捕获异常的终点（从 takePhoto 回调起的裸 Thread），
     * Error 逃逸即崩进程；且必须复位 [inProgress]，否则拍照答题入口永久失效。
     */
    private fun handlePhoto(
        jpeg: ByteArray,
        askStartMs: Long,
        onStage: (Int) -> Unit,
        onText: (String) -> Unit,
        onReply: (String) -> Unit,
        onStageText: (String) -> Unit,
    ) {
        try {
            when (val decision = imageInputDecisionProvider()) {
                is com.rokidlab.phone.ai.llm.ImageInputDecision.Allow ->
                    askWithImage(jpeg, askStartMs, onStage, onText, onReply, onStageText)

                com.rokidlab.phone.ai.llm.ImageInputDecision.SwitchOff ->
                    // 用户自己没开这个功能，走 OCR 不需要解释
                    askWithOcr(jpeg, askStartMs, onStage, onText, onReply, onStageText)

                is com.rokidlab.phone.ai.llm.ImageInputDecision.Unsupported -> {
                    // 用户开了开关，但能力已确认不支持 —— 必须说出来，否则他以为功能坏了。
                    // 先发出这条状态文案再走 OCR（同一位置原地更新，不额外占一条气泡）。
                    Log.i(
                        TAG,
                        "photoAsk: image input unsupported by model " +
                            "(known=${decision.capabilities.imageKnown}), falling back to OCR",
                    )
                    mainHandler.post {
                        onStageText(appContext.getString(R.string.chat_photo_vision_unsupported))
                    }
                    askWithOcr(jpeg, askStartMs, onStage, onText, onReply, onStageText)
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "photoAsk failed", e)
            inProgress = false
            mainHandler.post { onStage(R.string.chat_photo_failed) }
        }
    }

    /**
     * 多模态路径：图片直接进模型（跳过 OCR）。
     *
     * 失败时**自动回退 OCR**。回退而不是报错，是因为这里最常见的失败原因是
     * "当前配置的模型不支持图像输入"，而那属于配置问题不是任务失败 ——
     * 回退后用户照样能拿到答案，只是精度退化为纯文字。
     */
    private fun askWithImage(
        jpeg: ByteArray,
        askStartMs: Long,
        onStage: (Int) -> Unit,
        onText: (String) -> Unit,
        onReply: (String) -> Unit,
        onStageText: (String) -> Unit,
    ) {
        Log.i(TAG, "photoAsk: vision path, jpeg=${jpeg.size}B")
        val base64 = runCatching { Base64.encodeToString(jpeg, Base64.NO_WRAP) }.getOrNull()
        if (base64.isNullOrBlank()) {
            // 编码失败极少见，静默退 OCR：此时再弹一次「图像理解失败」对用户没有信息量
            Log.w(TAG, "photoAsk: base64 encode failed, fallback to OCR")
            askWithOcr(jpeg, askStartMs, onStage, onText, onReply, onStageText)
            return
        }
        val instruction = quizInstructionProvider().ifBlank { null }
        val question = appContext.getString(R.string.chat_photo_vision_question)
        mainHandler.post { onText(question) }
        mainHandler.post { onStage(R.string.chat_ai_status) }
        val tAi = System.currentTimeMillis()
        sendAiQuestion(
            question,
            null,
            instruction,
            base64,
            { success, err ->
                if (success) {
                    inProgress = false
                    Log.i(
                        TAG,
                        "photoAsk: vision send done after ${System.currentTimeMillis() - tAi}ms " +
                            "(total ${System.currentTimeMillis() - askStartMs}ms)"
                    )
                } else {
                    Log.w(TAG, "photoAsk: vision failed ($err), falling back to OCR")
                    mainHandler.post {
                        onStageText(appContext.getString(R.string.chat_photo_vision_fallback))
                    }
                    // 回退路径仍在进行中：inProgress 保持 true，由 OCR 那条路负责复位
                    askWithOcr(jpeg, System.currentTimeMillis(), onStage, onText, onReply, onStageText)
                }
            },
            { reply ->
                Log.i(TAG, "photoAsk: vision reply (${reply.length} chars)")
                onReply(reply)
            },
        )
    }

    /** 本地 OCR 路径（默认路径，也是多模态失败时的回退路径） */
    private fun askWithOcr(
        jpeg: ByteArray,
        askStartMs: Long,
        onStage: (Int) -> Unit,
        onText: (String) -> Unit,
        onReply: (String) -> Unit,
        onStageText: (String) -> Unit,
    ) {
        Log.i(TAG, "photoAsk: OCR path, jpeg=${jpeg.size}B")
        mainHandler.post { onStage(R.string.chat_ocr_status) }
        // 本地 OCR 识别题目文字（模型不内置 APK，首次使用先从 Gitee 下载约 15.4MB）
        val tOcr = System.currentTimeMillis()
        val text = runCatching {
            val bmp = android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
                ?: return@runCatching ""
            try {
                // 模型缺失时 onProgress 才会回调（模型已存在则静默继续）。
                // 下载不再静默：按 10% 档位把进度推到聊天窗口，原地更新同一条状态气泡，
                // 让用户明确知道「正在下载模型」而不是以为 App 卡死。
                val downloadBucket = java.util.concurrent.atomic.AtomicInteger(-1)
                LocalOcr.ensureInit(appContext) { pct ->
                    val bucket = (pct / 10) * 10
                    if (downloadBucket.getAndSet(bucket) != bucket) {
                        Log.i(TAG, "photoAsk: OCR model downloading $bucket%")
                        mainHandler.post {
                            onStageText(appContext.getString(R.string.chat_ocr_downloading, bucket))
                        }
                    }
                }
                // 发生过下载：上面的「正在识别…」状态气泡已被下载进度覆盖，
                // 下载完成后补回一条识别阶段提示，再开始识别。
                if (downloadBucket.get() >= 0) {
                    mainHandler.post { onStage(R.string.chat_ocr_status) }
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
            Log.w(
                TAG,
                "photoAsk: OCR result empty, abort (nativeUnavailable=${LocalOcr.nativeUnavailable}, " +
                    "modelsUnavailable=${LocalOcr.modelsUnavailable})"
            )
            inProgress = false
            mainHandler.post { onStage(stageRes) }
            return
        }
        // 把识别出的文字回调给 UI（作为「用户消息」气泡展示）
        mainHandler.post { onText(text) }
        // 知识库检索相关资料（RAG）
        mainHandler.post { onStage(R.string.chat_kb_status) }
        val tKb = System.currentTimeMillis()
        val kbText = KnowledgeBase
            .searchHits(appContext, text, topK = 3)
            .joinToString("\n\n") { hit ->
                // 来源标注：让答案可溯源（出自哪份文档的哪一块）
                "（《${hit.docName}》第${hit.chunkIdx + 1}块）${hit.text}"
            }
        Log.i(TAG, "photoAsk: KB search done in ${System.currentTimeMillis() - tKb}ms, hits=${kbText.length} chars")
        // 生成答案并发送到眼镜（显示 + 播报）
        mainHandler.post { onStage(R.string.chat_ai_status) }
        val tAi = System.currentTimeMillis()
        sendAiQuestion(
            text,
            kbText.ifBlank { null },
            quizInstructionProvider().ifBlank { null },
            null,
            { success, err ->
                inProgress = false
                Log.i(
                    TAG,
                    "photoAsk: AI send onResult success=$success err=$err after " +
                        "${System.currentTimeMillis() - tAi}ms (total ${System.currentTimeMillis() - askStartMs}ms)"
                )
                if (!success) {
                    // 失败且无 onReply：通知 UI 复位 photoAsking（否则拍照问 AI 入口永久失效）
                    mainHandler.post { onStage(R.string.chat_photo_failed) }
                }
            },
            { reply ->
                Log.i(TAG, "photoAsk: AI reply received (${reply.length} chars) after " +
                    "${System.currentTimeMillis() - askStartMs}ms total")
                onReply(reply)
            },
        )
    }
}
