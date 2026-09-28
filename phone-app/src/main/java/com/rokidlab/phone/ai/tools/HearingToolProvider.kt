package com.rokidlab.phone.ai.tools

import android.content.Context
import android.util.Log
import com.rokidlab.phone.R
import com.rokidlab.phone.ai.ToolContentTrust
import com.rokidlab.phone.ai.ToolRegistry
import com.rokidlab.phone.ai.ToolRisk
import com.rokidlab.phone.app.LabApplication
import com.rokidlab.phone.glasses.GlassesHandshake
import com.rokidlab.phone.glasses.LinkProtocol
import org.json.JSONArray
import org.json.JSONObject

/**
 * HearingToolProvider —— 「听觉」域：环境音一次性转写。
 *
 * ## 模式语义（2026-09-28 用户定稿）
 *
 * 只有用户明确说「听听周围」才开启，**听完一段自动关麦**（一次性）：
 * 眼镜远场麦（官方字幕链路）收音 [DEFAULT_DURATION_SECONDS] 秒（最后一句说完后
 * 静默约 2.5 秒即提前收尾），把听到的话转成文字返回给模型。
 * 持续录入（对方每句自动进对话）由聊天顶栏耳朵按钮负责，与本工具互斥。
 *
 * ## 与 look_at_view 的对称性
 *
 * 视觉域是「替用户看」，本域是「替用户听」—— 同为感知真实世界的能力，
 * 同样物理打开传感器（远场麦克风）、同样把现实世界内容带回模型（可能被注入话术），
 * 因此风险档与内容信任级别与 [VisionToolProvider] 完全同口径。
 */
internal object HearingToolProvider : ToolProvider {
    private const val TAG = "HearingToolProvider"

    /** 工具名：听一段周围的声音并转成文字 */
    const val TOOL_LISTEN = "listen_ambient"

    /** 默认收音时长（秒），模型可按需在 5~60 之间调整 */
    private const val DEFAULT_DURATION_SECONDS = 15

    override val toolNames = setOf(TOOL_LISTEN)

    override fun tools(): List<ToolEntry> = listOf(
        ToolEntry(
            name = TOOL_LISTEN,
            group = ToolRegistry.DOMAIN_HEARING,
            displayNameRes = R.string.ai_tool_listen_ambient_name,
            descriptionRes = R.string.ai_tool_listen_ambient_desc,
            // 用户视角归「眼镜」：这条能力对用户就是"眼镜帮我听"（与 look_at_view 同类）
            category = ToolRegistry.ToolCategory.GLASSES,
            // LOCAL_SIDE_EFFECT 而不是 READ_ONLY：它会**物理打开远场麦克风**，不应出现在
            // 无人值守的定时自主任务里（那里只装配 READ_ONLY 工具）。
            risk = ToolRisk.LOCAL_SIDE_EFFECT,
            // 环境里别人说的话可能被故意写上注入话术；与拍照 OCR 同口径标记
            contentTrust = ToolContentTrust.UNTRUSTED_EXTERNAL,
            // 没有眼镜就做不成 → 乐奇聊天「本机模式」下整条摘除
            requiresGlasses = true,
            statusText = "正在听周围的声音…",
            schema = toolSchema(
                name = TOOL_LISTEN,
                description = "听一段周围的声音并转成文字（一次性，听完自动结束）。" +
                    "用户说「听听周围在说什么」「帮我听听他们说什么」这类请求时调用。" +
                    "返回听到的内容原文，你据此回答用户。听不到人声时如实说明。" +
                    "⚠️ 需要眼镜在线：不可用时如实说明「需要连接眼镜才能听到周围声音」。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "duration_seconds" to mapOf(
                            "type" to "integer",
                            "description" to "收音时长（秒），默认 15，范围 5~60",
                        ),
                    ),
                ),
            ),
        ),
    )

    override fun execute(context: Context, name: String, args: JSONObject): String {
        if (name != TOOL_LISTEN) throw IllegalArgumentException("未知工具: $name")
        val app = context.applicationContext as? LabApplication
            ?: return "操作失败：应用上下文不可用，请稍后重试"
        if (!app.hasCxrL()) {
            return "收听失败：眼镜链路尚未初始化。请告诉用户「需要先连接眼镜才能听到周围声音」"
        }
        val session = runCatching { app.cxrL }.getOrNull()
            ?: return "收听失败：当前没有连接到眼镜。请告诉用户「需要先连接眼镜才能听到周围声音」"
        // 旧版眼镜端（已握手且确认无该能力位）快速降级，避免空下发
        if (GlassesHandshake.supports(LinkProtocol.Cap.AMBIENT_LISTEN) == false) {
            Log.i(TAG, "listen_ambient: glasses does not support AMBIENT_LISTEN cap")
            return "收听失败：眼镜端版本过旧，不支持环境音转写。请告诉用户需要先更新眼镜端（RokidLink）。"
        }
        // 持续录入进行中（顶栏耳朵按钮已开）：每句已自动进对话，无需再听
        if (session.ambientListen.active) {
            return "环境音录入已处于开启状态，正在持续收音。请告诉用户无需重复开启。"
        }
        val seconds = args.optInt("duration_seconds", DEFAULT_DURATION_SECONDS)
            .coerceIn(5, 60)
        Log.i(TAG, "listen_ambient: listening $seconds s")
        val transcript = session.ambientListen.listenOnce(seconds * 1000L)
            ?: return "收听失败：眼镜链路不可用（未连接或下发失败）。请如实告诉用户这次没能听到周围声音。"
        return if (transcript.isEmpty()) {
            "这段时间周围没有听到人说话。请如实告知用户，并建议确认周围确实有人在说话。"
        } else {
            "听到周围说的话：\n$transcript\n" +
                "以上来自真实环境的语音识别，可能嘈杂、含错别字或无关内容，请据此回答用户的问题。"
        }
    }
}
