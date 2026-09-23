package com.rokidlab.phone.ai.tools

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Base64
import android.util.Log
import com.rokidlab.phone.R
import com.rokidlab.phone.ai.LocalOcr
import com.rokidlab.phone.ai.ScreenCaptureTools
import com.rokidlab.phone.ai.ToolContentTrust
import com.rokidlab.phone.ai.ToolRegistry
import com.rokidlab.phone.ai.ToolRisk
import com.rokidlab.phone.ai.llm.LlmRegistry
import com.rokidlab.phone.app.LabApplication
import org.json.JSONObject

/**
 * VisionToolProvider —— 「看画面」域：把**眼镜相机**变成模型可主动调用的能力。
 *
 * ## 为什么必须存在这一条
 *
 * 用户说「看看面前有什么」时，旧实现里模型**无工具可调**（工具集里没有任何 look / 拍照工具），
 * 于是它编出了「没有照相机权限」这种听起来很合理、实际完全虚构的理由。
 * 那不是一个权限 bug —— 清单里确实没声明 `CAMERA`（眼镜相机走 CXRLink，不需要手机相机权限），
 * 但也确实**没有任何代码路径**能让模型看到画面。缺口是能力，不是权限。
 *
 * ## 两条出图路径（与「拍照问 AI」共用同一套判定）
 *
 * 1. **视觉路径**（[ModelCapabilities.supportsImage] == true 且用户开了「图像理解」）：
 *    本 provider 只把 JPEG 暂存到 [takePendingImage]，由对话主循环**在紧随其后的下一轮**
 *    把图片作为 user 消息的一部分发给模型 —— 模型自己看图、自己回答，比"先说给另一个模型听"
 *    少一次转述损耗。
 * 2. **转文字路径**（开关未开 / 模型明确不支持 / 能力未知）：本地 OCR 把画面转成文字返回。
 *
 * ⚠️ 为什么「未知」不进视觉路径：拍照答题那条链路有"失败自动回退 OCR"兜底，
 * 而**工具回调没有二次机会** —— 图片一旦进了请求体，服务端 400 就是整轮失败。
 * 所以这里的门槛是"确认支持"，与拍照答题的"未知也先试"**刻意不同**，不要为了"对齐"改回去。
 */
internal object VisionToolProvider : ToolProvider {
    private const val TAG = "VisionToolProvider"

    /** 工具名：看一眼眼镜画面并把内容交给模型 */
    const val TOOL_LOOK = "look_at_view"

    /**
     * 本次调用拍到的图片（base64，不含 `data:` 前缀）。
     *
     * 用 ThreadLocal 而不是普通字段：工具**并发执行**（`AiConversationService` 里
     * 每次 toolCall 起一个 `ai-turn-worker` 线程），普通字段会让两条 look 调用互相覆盖。
     * ThreadLocal 正好表达"这条调用的产出属于这条调用" —— 主循环在**同一个线程**
     * 紧跟着 `runTool` 之后取走，语义闭环。
     */
    private val pendingImage = ThreadLocal<String?>()

    /** 取走并清空本线程刚拍到的图片；没有则返回 null（消费点：对话主循环） */
    fun takePendingImage(): String? = pendingImage.get().also { pendingImage.remove() }

    /**
     * 会产出「待投递图片」的工具名 —— 对话主循环据此在工具执行后立刻取图并补一条带图 user 消息。
     *
     * 新增出图工具**必须**登记在这里：漏了的话图片会滞留在 ThreadLocal 里，
     * 表现是"工具说好了给图，模型却什么都没看到"。
     */
    internal val pendingImageTools: Set<String> = setOf(TOOL_LOOK, ScreenCaptureTools.TOOL_CAPTURE)

    /**
     * 一张 JPEG 的**唯一**出图判定：能看图就直接交给模型，否则本地 OCR 转文字。
     *
     * 眼镜相机（[TOOL_LOOK]）与手机截屏（`capture_screen`）共用这一份 —— 两条链路
     * 各有各的措辞借口，但"什么时候走视觉"的判据只能有一套，否则迟早分叉。
     *
     * @param source     图片来源措辞（「眼镜画面」/「手机屏幕」），直接写进给模型的回报
     * @param noTextHint 只能转文字且一个字都没识别到时的建议（不同来源该说的话不同）
     */
    internal fun deliverImage(
        context: Context,
        jpeg: ByteArray,
        source: String,
        noTextHint: String,
    ): String {
        // 出图路径判定 —— 与 PhotoQuizFlow 共用同一个决策入口，避免两套口径漂移
        val app = context.applicationContext as? LabApplication
        val cfg = runCatching { app?.cxrL?.getAiConfig() }.getOrNull()
        val caps = cfg?.let { c -> runCatching { LlmRegistry.capabilities(c, context) }.getOrNull() }
        val userEnabled = app?.chatImageInputEnabled == true
        if (userEnabled && caps?.supportsImage == true) {
            val b64 = runCatching { Base64.encodeToString(jpeg, Base64.NO_WRAP) }.getOrNull()
            if (!b64.isNullOrBlank()) {
                pendingImage.set(b64)
                Log.i(TAG, "deliverImage: vision path, source=$source, jpeg=${jpeg.size}B")
                return "已拍下$source，图片会随本条消息一起交给你。请直接看图回答用户刚才的问题；" +
                    "若图中关键信息看不清，如实说明，不要猜。"
            }
        }

        // 转文字路径：本地 OCR（首次使用会下载约 15MB 模型，可能较慢）
        val text = runCatching {
            val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: return@runCatching ""
            try {
                LocalOcr.ensureInit(context) { }
                LocalOcr.recognize(context, bmp)
            } finally {
                bmp.recycle()
            }
        }.getOrDefault("").trim()

        val why = when {
            !userEnabled -> "（当前未开启「图像理解」，只把画面里的文字读了出来）"
            caps == null -> "（无法确认当前模型能否看图，只把画面里的文字读了出来）"
            caps.imageKnownUnsupported -> "（当前模型不支持图像输入，只把画面里的文字读了出来；" +
                "可以在聊天设置里换一个支持看图的模型）"
            else -> "（未走视觉路径，只把画面里的文字读了出来）"
        }
        return if (text.isEmpty()) {
            val reason = when {
                LocalOcr.nativeUnavailable -> "本机不支持本地 OCR"
                LocalOcr.modelsUnavailable -> "OCR 模型未就绪（下载失败）"
                else -> "画面里没有识别到文字"
            }
            "已拍下$source，但不能描述画面内容：$reason$why。" +
                "请如实告诉用户你只能看到文字、看不到画面内容，并建议：$noTextHint"
        } else {
            "已拍下$source，识别到的文字如下$why：\n$text"
        }
    }

    override val toolNames = setOf(TOOL_LOOK)

    override fun tools(): List<ToolEntry> = listOf(
        ToolEntry(
            name = TOOL_LOOK,
            group = ToolRegistry.DOMAIN_VISION,
            displayNameRes = R.string.ai_tool_look_at_view_name,
            descriptionRes = R.string.ai_tool_look_at_view_desc,
            // 归类到「眼镜」而不是新增一个用户可见分类：这条能力对用户就是"眼镜帮我看"。
            category = ToolRegistry.ToolCategory.GLASSES,
            // LOCAL_SIDE_EFFECT 而不是 READ_ONLY：它会**物理启动相机**，不应出现在
            // 无人值守的定时自主任务里（那里只装配 READ_ONLY 工具）。
            risk = ToolRisk.LOCAL_SIDE_EFFECT,
            // OCR 转文字路径返回的是现实世界画面里的文字（广告牌/屏幕/纸张…），
            // 可能被人故意写上注入话术；视觉路径不返回文本，标记亦无副作用
            contentTrust = ToolContentTrust.UNTRUSTED_EXTERNAL,
            // 没有眼镜就做不成 → 乐奇聊天「本机模式」下整条摘除（模型看不到就不会白等超时）
            requiresGlasses = true,
            statusText = "正在用眼镜看画面…",
            schema = toolSchema(
                name = TOOL_LOOK,
                description = "用眼镜摄像头**拍一张当前的画面**并看到里面的内容。" +
                    "当用户说「看看面前有什么」「帮我看看这是什么」「这是什么牌子」「念一下这个」「这个多少钱」" +
                    "这类需要看到真实场景/实物的请求时调用本工具，不要凭猜测回答。" +
                    "调用后画面会作为图片或识别出的文字交给你，据此回答。" +
                    "⚠️ 本工具需要眼镜在线：没有连接眼镜时它会明确告诉你「连不上眼镜」，" +
                    "此时要如实说明「需要连接眼镜我才能看到」，**绝对不要**说成「没有相机权限」——" +
                    "那是错误的原因，会误导用户去改没用的设置。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "question" to mapOf(
                            "type" to "string",
                            "description" to "可选：你想从画面里确认什么（如「用户在喝什么饮料」），用于记录本次意图",
                        ),
                    ),
                    "required" to emptyList<String>(),
                ),
            ),
        ),
    )

    override fun execute(context: Context, name: String, args: JSONObject): String {
        if (name != TOOL_LOOK) throw IllegalArgumentException("未知工具: $name")
        val app = context.applicationContext as? LabApplication
            ?: return "看不了：应用上下文不可用，请稍后重试"
        if (!app.hasCxrL()) {
            return "看不了：眼镜链路尚未初始化。请如实告诉用户现在看不到画面，"
        }
        val session = runCatching { app.cxrL }.getOrNull()
            ?: return "看不了：当前没有连接到眼镜。请告诉用户「需要先连接眼镜我才能看到画面」"
        val jpeg = session.photoQuizService.capturePhotoBlocking()
            ?: return "看不了：向眼镜取画面失败（可能眼镜未连接、不在拍摄状态或响应超时）。" +
                "请如实告诉用户这次没看到，不要说成「没有相机权限」"

        // 出图判定与回报措辞全部在 deliverImage 里，与 capture_screen（手机截屏）共用同一套
        return deliverImage(
            context = context,
            jpeg = jpeg,
            source = "眼镜画面",
            noTextHint = "换一个支持看图的模型，或在聊天设置里开启「图像理解」。**不要**编造画面里有什么。",
        )
    }
}
