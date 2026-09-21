package com.rokidlab.phone.ai.tools

import android.content.Context
import android.util.Log
import com.rokidlab.phone.R
import com.rokidlab.phone.ai.ToolRegistry
import com.rokidlab.phone.ai.ToolRisk
import com.rokidlab.phone.app.LabApplication
import com.rokidlab.phone.glasses.AiChannel
import com.rokidlab.phone.glasses.MotionBuffer
import com.rokidlab.phone.glasses.MotionPatternDetector
import com.rokidlab.phone.glasses.MotionPatterns
import com.rokidlab.phone.glasses.MotionRule
import com.rokidlab.phone.glasses.MotionRuleEngine
import org.json.JSONObject
import java.util.Locale
import kotlin.math.sqrt

/**
 * MotionToolProvider —— 「头动感知」域：把眼镜 IMU 变成模型可查询的能力（v1 查询层）。
 *
 * 数据链路：眼镜端 HeadImuService（~20Hz 采样，500ms 批量）→ CXR 通道 →
 * [MotionBuffer] 环形缓冲（60s）→ 本 provider 查询输出。
 *
 * 为什么是「查询」而不是「事件」：v1 让 AI 自己看数据下判断（点头/摇头/静止），
 * 规则化的动作→行为映射留给 v2（MotionRuleEngine + set_motion_rule）。
 * 角度序列以 ~5Hz 降采样输出，50 点/10s 的体积让模型能直接看出趋势，又不会撑爆上下文。
 */
internal object MotionToolProvider : ToolProvider {
    private const val TAG = "MotionToolProvider"

    const val TOOL_GET_HEAD_POSE = "get_head_pose"
    const val TOOL_GET_MOTION_HISTORY = "get_motion_history"
    const val TOOL_SET_MOTION_RULE = "set_motion_rule"
    const val TOOL_REMOVE_MOTION_RULE = "remove_motion_rule"

    /** 缓冲数据陈旧判定阈值：超过则重发 imu_start 并短暂等待（懒重启兜底） */
    private const val STALE_MS = 5_000L
    /** 懒重启后等待新鲜数据的最长时间（眼镜端 500ms 一批，3s 足够首批回流） */
    private const val REARM_WAIT_MS = 3_000L
    /** 历史序列每方向最多输出点数（防 60s 窗口输出膨胀） */
    private const val MAX_SERIES_POINTS = 120

    override val toolNames = setOf(
        TOOL_GET_HEAD_POSE, TOOL_GET_MOTION_HISTORY, TOOL_SET_MOTION_RULE, TOOL_REMOVE_MOTION_RULE,
    )

    override fun tools(): List<ToolEntry> = listOf(
        ToolEntry(
            name = TOOL_GET_HEAD_POSE,
            group = ToolRegistry.DOMAIN_GLASSES,
            displayNameRes = R.string.ai_tool_get_head_pose_name,
            descriptionRes = R.string.ai_tool_get_head_pose_desc,
            category = ToolRegistry.ToolCategory.GLASSES,
            risk = ToolRisk.READ_ONLY,
            requiresGlasses = true,
            statusText = "正在读取头部姿态…",
            schema = toolSchema(
                name = TOOL_GET_HEAD_POSE,
                description = "获取用户**当前的头部姿态**（数据来自眼镜 IMU：俯仰/偏航/横滚欧拉角、角速度、加速度、四元数）。" +
                    "当用户问「我抬头了吗」「我头现在什么姿势」「我刚才往哪边转了」，或 AI 需要结合用户头部朝向/动作做判断时调用本工具。" +
                    "⚠️ 角度是相对变化（游戏旋转向量不含地磁，**没有绝对罗盘朝向**，只能表达相对初始/上一时刻的变化）；" +
                    "返回「数据不可用」时请如实告知用户眼镜不在线或版本过旧，**绝对不要编造角度数值**。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to emptyMap<String, Any>(),
                    "required" to emptyList<String>(),
                ),
            ),
        ),
        ToolEntry(
            name = TOOL_GET_MOTION_HISTORY,
            group = ToolRegistry.DOMAIN_GLASSES,
            displayNameRes = R.string.ai_tool_get_motion_history_name,
            descriptionRes = R.string.ai_tool_get_motion_history_desc,
            category = ToolRegistry.ToolCategory.GLASSES,
            risk = ToolRisk.READ_ONLY,
            requiresGlasses = true,
            statusText = "正在读取动作历史…",
            schema = toolSchema(
                name = TOOL_GET_MOTION_HISTORY,
                description = "获取用户**最近一段时间的头部动作历史**（俯仰/偏航/横滚角度时间序列，约 5Hz + 角速度/加速度峰值统计），" +
                    "用于判断用户刚才做了什么动作：点头、摇头、抬头/低头、转头、保持静止等。" +
                    "当用户说「我刚才是不是点头了」「我摇了几下头」「我刚才动没动」，或 AI 需要根据近期头部动作调整回复时调用。" +
                    "看序列趋势下判断：往复起伏=连续动作（点头/摇头），长期平稳=静止，单次突变=单次动作。" +
                    "数据不足或不可用时如实告知用户，**绝对不要编造动作判定**。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "window_seconds" to mapOf(
                            "type" to "integer",
                            "description" to "回看时间窗（秒），默认 10，范围 1~60。判断「刚才一个动作」用默认值即可",
                        ),
                    ),
                    "required" to emptyList<String>(),
                ),
            ),
        ),
        ToolEntry(
            name = TOOL_SET_MOTION_RULE,
            group = ToolRegistry.DOMAIN_GLASSES,
            displayNameRes = R.string.ai_tool_set_motion_rule_name,
            descriptionRes = R.string.ai_tool_set_motion_rule_desc,
            category = ToolRegistry.ToolCategory.GLASSES,
            risk = ToolRisk.LOCAL_SIDE_EFFECT,
            requiresGlasses = true,
            statusText = "正在设置头动规则…",
            schema = toolSchema(
                name = TOOL_SET_MOTION_RULE,
                description = "创建或更新**头动规则**：用户做出指定头部动作时自动执行行为（无需说话）。" +
                    "pattern 可选：nod=点头（pitch 往复）、shake=摇头（yaw 往复）、still=保持静止、pitch_over=持续抬头/低头；" +
                    "tool = 触发时要调用的**工具名**（手机端任意工具，含 MCP 工具，取工具列表里的精确名字），" +
                    "args = 传给该工具的入参对象（如 tool=control_music、args={\"action\":\"stop\"} 表示停止播放；" +
                    "无参数的工具可省略或传 {}）。" +
                    "当用户说「点头就暂停音乐」「我摇头的时候查一下天气」「保持抬头别打扰我」这类把动作绑定到行为的意图时调用。" +
                    "相同 pattern+tool+方向 重复设置会**替换**已有规则；最多保留 ${MotionRuleEngine.MAX_RULES} 条。" +
                    "工具名必须真实存在，不要编造；外部副作用类工具（拨号/装机等）在触发时仍需用户确认。" +
                    "规则判定基于相对角度变化，建议沿用默认幅度阈值（threshold_deg=15°），" +
                    "误触发时调大、没反应时调小。审批确认弹窗期间点头/摇头固定为确认/取消，与规则无关。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "pattern" to mapOf(
                            "type" to "string",
                            "enum" to listOf("nod", "shake", "still", "pitch_over"),
                            "description" to "动作模式：nod=点头、shake=摇头、still=保持静止、pitch_over=持续抬头/低头",
                        ),
                        "tool" to mapOf(
                            "type" to "string",
                            "description" to "触发时调用的工具名（手机端任意工具，含 MCP 工具），必须是工具列表里的精确名字，如 get_current_time、get_weather、control_music",
                        ),
                        "args" to mapOf(
                            "type" to "object",
                            "description" to "传给 tool 的入参对象，如 {\"action\":\"stop\"}；无参数的工具省略或传 {}",
                        ),
                        "name" to mapOf("type" to "string", "description" to "可选，规则名（给用户看），如「点头停音乐」"),
                        "count" to mapOf("type" to "integer", "description" to "nod/shake 需要的动作次数，1~5，默认 1（点头一次就触发）"),
                        "window_ms" to mapOf("type" to "integer", "description" to "nod/shake 判定窗口毫秒，1500~10000，默认 3000（窗口内完成 count 次即触发）"),
                        "threshold_deg" to mapOf("type" to "number", "description" to "动作幅度阈值度数，8~45，默认 15；误触发调大、没反应调小"),
                        "duration_ms" to mapOf("type" to "integer", "description" to "still/pitch_over 持续时长毫秒，1000~10000，默认 1500"),
                        "direction" to mapOf("type" to "string", "enum" to listOf("up", "down"), "description" to "仅 pitch_over：up=持续抬头（默认），down=持续低头"),
                    ),
                    "required" to listOf("pattern", "tool"),
                ),
            ),
        ),
        ToolEntry(
            name = TOOL_REMOVE_MOTION_RULE,
            group = ToolRegistry.DOMAIN_GLASSES,
            displayNameRes = R.string.ai_tool_remove_motion_rule_name,
            descriptionRes = R.string.ai_tool_remove_motion_rule_desc,
            category = ToolRegistry.ToolCategory.GLASSES,
            risk = ToolRisk.LOCAL_SIDE_EFFECT,
            requiresGlasses = true,
            statusText = "正在删除头动规则…",
            schema = toolSchema(
                name = TOOL_REMOVE_MOTION_RULE,
                description = "删除**头动规则**。当用户说「不要点头停音乐了」「把刚才那条规则删掉」时调用。" +
                    "不带参数调用会**返回当前全部规则**（含 rule_id），供用户选择删除哪条；" +
                    "带 rule_id 删除指定规则；all=true 清空全部。",
                parameters = mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "rule_id" to mapOf("type" to "string", "description" to "要删除的规则 ID（set_motion_rule 的返回结果里有）"),
                        "all" to mapOf("type" to "boolean", "description" to "true=清空全部头动规则"),
                    ),
                    "required" to emptyList<String>(),
                ),
            ),
        ),
    )

    override fun execute(context: Context, name: String, args: JSONObject): String {
        return when (name) {
            TOOL_GET_HEAD_POSE -> execHeadPose(context)
            TOOL_GET_MOTION_HISTORY -> execMotionHistory(context, args)
            TOOL_SET_MOTION_RULE -> execSetMotionRule(context, args)
            TOOL_REMOVE_MOTION_RULE -> execRemoveMotionRule(args)
            else -> throw IllegalArgumentException("未知工具: $name")
        }
    }

    // ──────────────────────────────────────────────
    //  v2 规则工具实现
    // ──────────────────────────────────────────────

    /** 创建/更新头动规则（同 pattern+工具+方向 替换） */
    private fun execSetMotionRule(context: Context, args: JSONObject): String {
        MotionRuleEngine.init(context)
        val rule = MotionRule.build(
            pattern = args.optString("pattern").trim().lowercase(),
            tool = args.optString("tool").trim(),
            // args 可以是对象（schema 声明的形态），个别模型会退化成字符串，两种都收
            args = args.optJSONObject("args")?.toString() ?: args.optString("args"),
            name = args.optString("name").trim().ifBlank { null },
            count = args.optInt("count", 1),
            windowMs = args.optLong("window_ms", 3000L),
            thresholdDeg = args.optDouble("threshold_deg", 15.0).toFloat(),
            durationMs = args.optLong("duration_ms", 1500L),
            direction = args.optString("direction").trim().lowercase().ifBlank { null },
        ) ?: return ("pattern 只支持 nod/shake/still/pitch_over；tool 不能为空、不能是 set_motion_rule/" +
            "remove_motion_rule 本身；args 必须是 JSON 对象。请修正参数后重试，不要编造其它取值。")
        // 工具存在性在**落库时**校验：规则只有真正命中时才会执行，否则一条指向不存在工具名的规则
        // 会安静地躺在那里，直到用户做出动作才失败（用户完全无从判断是自己动作没做对还是规则是坏的）
        if (rule.tool !in ToolRegistry.allToolNames()) {
            return "没有名为「${rule.tool}」的工具，无法建立规则。请从工具列表里选一个真实存在的工具名，不要编造。"
        }
        MotionRuleEngine.upsert(rule)?.let { return "规则设置失败：$it" }
        return "已保存头动规则「${rule.name}」：${describeRule(rule)}\n当前规则（${MotionRuleEngine.rules().size} 条）：\n" +
            MotionRuleEngine.rules().joinToString("\n") { "・${it.name}（rule_id=${it.id}）${describeRule(it)}" }
    }

    /** 删除头动规则：无参=列出全部；rule_id=删指定；all=清空 */
    private fun execRemoveMotionRule(args: JSONObject): String {
        val current = MotionRuleEngine.rules()
        if (args.optBoolean("all")) {
            MotionRuleEngine.removeAll()
            return "已清空全部头动规则"
        }
        val id = args.optString("rule_id").trim()
        if (id.isBlank()) {
            if (current.isEmpty()) return "当前没有已设置的头动规则"
            return "当前头动规则（${current.size} 条）——告诉我要删除哪条：\n" +
                current.joinToString("\n") { "・${it.name}（rule_id=${it.id}）${describeRule(it)}" }
        }
        val target = current.firstOrNull { it.id == id || it.name == id }
            ?: return "没有找到 rule_id 为「$id」的规则。当前规则：\n" +
                current.joinToString("\n") { "・${it.name}（rule_id=${it.id}）${describeRule(it)}" }
        MotionRuleEngine.remove(target.id)
        return "已删除头动规则「${target.name}」"
    }

    /** 规则的人类可读描述（工具返回文本用；规则名之外的参数细节） */
    private fun describeRule(r: MotionRule): String {
        val act = if (r.args == "{}") r.tool else "${r.tool} ${r.args}"
        val cond = when (r.pattern) {
            MotionPatterns.NOD, MotionPatterns.SHAKE ->
                "${if (r.pattern == MotionPatterns.NOD) "点头" else "摇头"}${if (r.count > 1) "×${r.count}" else ""}" +
                    "（幅度 ${r.thresholdDeg.toInt()}°，窗口 ${r.windowMs / 1000.0}s）"
            MotionPatterns.STILL -> "保持静止 ${r.durationMs / 1000.0}s（幅度 ${r.thresholdDeg.toInt()}° 内）"
            else -> "持续${if (r.direction == "down") "低头" else "抬头"} ${r.durationMs / 1000.0}s（${r.thresholdDeg.toInt()}° 以上）"
        }
        return "：$cond → $act"
    }

    // ──────────────────────────────────────────────
    //  工具实现
    // ──────────────────────────────────────────────

    /** 当前头部姿态 */
    private fun execHeadPose(context: Context): String {
        ensureStream(context)?.let { return it }
        val s = MotionBuffer.latest()
            ?: return "暂无头部动作数据。请如实告知用户暂时读不到眼镜姿态。"
        val e = eulerDeg(s)
        val age = System.currentTimeMillis() - s.t
        return buildString {
            append("头部姿态（数据延迟 ").append(age).append("ms，来自眼镜 IMU）：\n")
            append(String.format(Locale.US, "pitch(俯仰)=%.1f° yaw(偏航)=%.1f° roll(横滚)=%.1f°%n", e[0], e[1], e[2]))
            append(String.format(Locale.US, "四元数(w,x,y,z)=%.3f,%.3f,%.3f,%.3f%n", s.qw, s.qx, s.qy, s.qz))
            append(String.format(Locale.US, "角速度(rad/s) gx=%.3f gy=%.3f gz=%.3f%n", s.gx, s.gy, s.gz))
            append(String.format(Locale.US, "加速度(m/s²) ax=%.2f ay=%.2f az=%.2f（az≈9.8 表示眼镜接近水平）", s.ax, s.ay, s.az))
        }
    }

    /** 最近一段时间的动作历史序列 */
    private fun execMotionHistory(context: Context, args: JSONObject): String {
        ensureStream(context)?.let { return it }
        val windowSec = args.optInt("window_seconds", 10).coerceIn(1, 60)
        val samples = MotionBuffer.recent(windowSec * 1000L)
        if (samples.size < 5) {
            return "最近 $windowSec 秒内动作数据不足（仅 ${samples.size} 个样本，眼镜端数据流可能刚启动）。" +
                "请如实告知用户稍等片刻再问，不要编造。"
        }
        // 降采样到 ≤[MAX_SERIES_POINTS] 点：step 为取样间隔（样本≈20Hz，step=4 → 5Hz）
        val step = maxOf(4, samples.size / MAX_SERIES_POINTS)
        val pitchSb = StringBuilder()
        val yawSb = StringBuilder()
        val rollSb = StringBuilder()
        var gyroPeak = 0f
        var accelPeak = 0f
        var points = 0
        for ((idx, s) in samples.withIndex()) {
            val gm = sqrt(s.gx * s.gx + s.gy * s.gy + s.gz * s.gz)
            val am = sqrt(s.ax * s.ax + s.ay * s.ay + s.az * s.az)
            if (gm > gyroPeak) gyroPeak = gm
            if (am > accelPeak) accelPeak = am
            if (idx % step != 0) continue
            val e = eulerDeg(s)
            if (points > 0) {
                pitchSb.append(','); yawSb.append(','); rollSb.append(',')
            }
            pitchSb.append(String.format(Locale.US, "%.1f", e[0]))
            yawSb.append(String.format(Locale.US, "%.1f", e[1]))
            rollSb.append(String.format(Locale.US, "%.1f", e[2]))
            points++
        }
        val intervalMs = step * 50L
        val last = samples.last()
        val le = eulerDeg(last)
        return buildString {
            append("最近 $windowSec 秒动作历史（").append(samples.size).append(" 个样本，序列点间隔约 ")
                .append(intervalMs).append("ms）：\n")
            append(String.format(Locale.US, "当前姿态: pitch=%.1f° yaw=%.1f° roll=%.1f°%n", le[0], le[1], le[2]))
            append("pitch序列: [").append(pitchSb).append("]\n")
            append("yaw序列: [").append(yawSb).append("]\n")
            append("roll序列: [").append(rollSb).append("]\n")
            append(String.format(Locale.US, "角速度峰值: %.3f rad/s | 加速度峰值: %.2f m/s²", gyroPeak, accelPeak))
        }
    }

    // ──────────────────────────────────────────────
    //  数据流保障与几何换算
    // ──────────────────────────────────────────────

    /**
     * 确保 IMU 数据流活着：缓冲新鲜直接通过（null）；
     * 陈旧则重发 imu_start 并等首批数据回流（覆盖「眼镜重启/会话重建后流未恢复」的场景）。
     * @return null = 数据可用；非 null = 给模型的失败说明文案
     */
    private fun ensureStream(context: Context): String? {
        if (MotionBuffer.newestAgeMs() <= STALE_MS) return null
        val app = context.applicationContext as? LabApplication
            ?: return "应用上下文不可用，读不到头部动作数据"
        if (!app.hasCxrL()) {
            return "眼镜链路尚未初始化，读不到头部动作数据。请如实告诉用户「需要连接眼镜我才能感知头部动作」。"
        }
        val session = runCatching { app.cxrL }.getOrNull()
            ?: return "当前没有连接到眼镜，读不到头部动作数据。请如实告诉用户「需要先连接眼镜」。"
        runCatching { session.deviceControl.sendImuControl(true) }
            .onFailure { Log.w(TAG, "imu_start resend failed", it) }
        val deadline = System.currentTimeMillis() + REARM_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            if (MotionBuffer.newestAgeMs() <= STALE_MS) return null
            Thread.sleep(250)
        }
        return "眼镜头部动作数据不可用（眼镜可能不在线或眼镜端版本过旧）。请如实告知用户，不要编造数据。"
    }

    /**
     * 四元数 → ZYX 欧拉角（度）—— 委托给 [MotionPatternDetector]（v2 起判定核心与查询层共用同一实现）。
     * @return [pitch(绕Y), yaw(绕Z), roll(绕X)]，坐标系为眼镜传感器系
     */
    private fun eulerDeg(s: AiChannel.ImuSample): FloatArray = MotionPatternDetector.eulerDeg(s)
}
