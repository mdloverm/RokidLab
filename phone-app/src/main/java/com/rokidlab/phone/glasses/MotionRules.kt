package com.rokidlab.phone.glasses

import org.json.JSONObject
import java.util.UUID
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2

/**
 * v2 规则编程层 —— **纯判定核心**（无 Android 依赖，可单测）。
 *
 * 产品定位：用户在对话里告诉 AI「点头就停音乐」这类意图，AI 通过 set_motion_rule 工具
 * 生成**参数化规则 JSON**（数据，不是代码），本文件负责三件事：
 *  1. [MotionRule]：规则的数据结构与 JSON 序列化（SharedPreferences 持久化的载体）；
 *  2. [MotionPatternDetector]：从 IMU 采样序列里识别动作（纯函数）；
 *  3. [MotionEdgeGate]：边沿触发闸门（状态型动作不得反复触发，见该类注释）。
 *
 * ★ 规则的动作 = **任意工具调用**（[MotionRule.tool] + [MotionRule.args]）：
 *   手机端全部工具（含 MCP 工具）都能作为规则目标，本文件不维护第二份动作白名单 ——
 *   这类"两份名单必然漂移"的坑在本仓已经踩过多次（工具表接缝化就是为此）。
 *   安全边界也不在这里：外部副作用工具（拨号/装机…）触发时照旧走审批闸门，
 *   由 `ApprovalGate` 按风险档决定要不要在眼镜上要确认。
 *   唯二例外是 [MotionRule.DENY_TOOL_TARGETS]（规则工具自身，避免"规则触发又去改规则"的自指）。
 *
 * ★ 判定一律用**相对变化量**（窗口差分/去基线），禁用绝对角度阈值 ——
 *   眼镜传感器系非正交水平安装（2026-09-21 真机实测：平视时 pitch≈-1°、yaw≈144°、roll≈78°），
 *   绝对阈值会把日常姿态误判成动作。
 *
 * 本文件被 [MotionRuleEngine]（调度/持久化/执行）与 MotionToolProvider（工具层）共同消费。
 */

/** 规则可用的动作模式 */
object MotionPatterns {
    const val NOD = "nod"              // 点头：pitch 轴往复起伏
    const val SHAKE = "shake"          // 摇头：yaw 轴往复起伏
    const val STILL = "still"          // 保持静止：三轴窗口内偏移都在阈值内
    const val PITCH_OVER = "pitch_over" // 持续抬头/低头并保持

    val ALL = listOf(NOD, SHAKE, STILL, PITCH_OVER)
}

/** 欧拉轴索引（[MotionPatternDetector.eulerDeg] 返回顺序） */
object MotionAxes {
    const val PITCH = 0
    const val YAW = 1
    const val ROLL = 2
}

/**
 * 一条头动规则。
 *
 * @param id          短 ID（8 位随机），remove_motion_rule 用它定位
 * @param name        规则名（给用户/模型看，如「点头停音乐」）
 * @param pattern     动作模式，取值见 [MotionPatterns.ALL]
 * @param count       nod/shake 需要的往复次数（1~5）
 * @param windowMs    nod/shake 的判定窗口（1500~10000ms）：在窗口内完成 count 次往复即命中
 * @param thresholdDeg 动作幅度阈值（8~45°）：往复/偏移超过该角度才计入
 * @param durationMs  still/pitch_over 的持续时间（1000~10000ms）
 * @param direction   pitch_over 专用：up=持续抬头 / down=持续低头；其它模式为空串
 * @param tool        触发时调用的工具名（手机端任意工具，含 MCP 工具；查 ToolRegistry.toolList）
 * @param args        传给 [tool] 的入参（**JSON 对象字符串**，恒为合法 JSON 对象，无参时为 "{}"）
 */
data class MotionRule(
    val id: String,
    val name: String,
    val pattern: String,
    val count: Int,
    val windowMs: Long,
    val thresholdDeg: Float,
    val durationMs: Long,
    val direction: String,
    val tool: String,
    val args: String,
) {
    /**
     * 去重键：同一（动作模式 + 目标工具 + 方向）只允许一条规则，重复设置 = 更新。
     *
     * 刻意**不含 args**：同一次点头配「停止播放」与「播放另一首」两条规则没有意义，
     * 用户改主意时应当替换而不是叠加。
     */
    val matchKey: String
        get() = "$pattern|$tool|$direction"

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("pattern", pattern)
        .put("count", count)
        .put("windowMs", windowMs)
        .put("thresholdDeg", thresholdDeg.toDouble())
        .put("durationMs", durationMs)
        .put("direction", direction)
        .put("tool", tool)
        .put("args", args)

    companion object {
        /**
         * 不能作为规则目标的工具：规则工具**自身**。
         *
         * 一条「点头 → set_motion_rule」会让一次点头静默改写全部规则（此后行为再也解释不清），
         * 属于自指带来的语义混乱 —— 与页面侧禁掉 `open_aiui_app`（自指递归）是同一类问题。
         * ⚠️ 这是**准入**边界，不是安全边界：安全仍由审批闸门按风险档负责。
         */
        val DENY_TOOL_TARGETS: Set<String> = setOf("set_motion_rule", "remove_motion_rule")

        /**
         * v2 早期（动作白名单版本，未发布）的两个内置动作 → 等价的工具调用。
         *
         * 只在**读取**旧数据时使用：老规则里存的是 `action` 而非 `tool`/`args`，
         * 能落成等价工具调用的就不该让用户已经配好的规则凭空消失。新规则一律直接写 `tool`/`args`。
         *
         *  - `music.stop` → [com.rokidlab.phone.ai.tools.MediaToolProvider] 的 `control_music`；
         *  - `tts.stop`   → 同域的 `stop_tts`（专门为它补的工具：旧实现是直调 SDK
         *    `DeviceControlService.stopTtsOnGlass`，手机端当时没有对应工具）。
         *
         * ⚠️ 这两条映射只在读取时生效；两个目标工具若哪天被删掉，这里必须一起删 ——
         * 否则会留下一批指向不存在工具名的死规则（执行时被 UnknownToolGuard 拒掉）。
         */
        private val LEGACY_ACTIONS: Map<String, Pair<String, String>> = mapOf(
            "music.stop" to ("control_music" to """{"action":"stop"}"""),
            "tts.stop" to ("stop_tts" to "{}"),
        )

        fun fromJson(o: JSONObject?): MotionRule? {
            if (o == null) return null
            val id = o.optString("id").takeIf { it.isNotBlank() } ?: return null
            val pattern = o.optString("pattern").takeIf { it in MotionPatterns.ALL } ?: return null
            val legacy = LEGACY_ACTIONS[o.optString("action")]
            val tool = o.optString("tool").trim().ifBlank { legacy?.first ?: return null }
            if (tool in DENY_TOOL_TARGETS) return null
            val args = normalizeArgs(o.optString("args").ifBlank { legacy?.second ?: "{}" })
                ?: return null
            return MotionRule(
                id = id,
                name = o.optString("name").ifBlank { pattern },
                pattern = pattern,
                count = o.optInt("count", 1).coerceIn(1, 5),
                windowMs = o.optLong("windowMs", 3000L).coerceIn(1500L, 10_000L),
                thresholdDeg = o.optDouble("thresholdDeg", 15.0).toFloat().coerceIn(8f, 45f),
                durationMs = o.optLong("durationMs", 1500L).coerceIn(1000L, 10_000L),
                direction = o.optString("direction").takeIf { it == "up" || it == "down" } ?: "",
                tool = tool,
                args = args,
            )
        }

        /**
         * 校验并归一化工具入参（越界收敛而非报错，让 AI 的轻微越界也能落成可用规则）。
         *
         * 动作 = 工具名 + 入参 JSON 对象：
         *  - [tool] 为空、或落在 [DENY_TOOL_TARGETS] → 拒收；
         *  - [args] 空 → `"{}"`；不是合法 **JSON 对象** → 拒收（数组/字面量/坏串都算非法）。
         *
         * 工具**是否存在**刻意不在这里判：本文件是纯函数（不查注册表），而且 MCP 工具
         * 可能是"现在没连上、待会儿才连上"。存在性由工具层落库时校验，
         * 执行时再由审批闸门兜底（未知名被 UnknownToolGuard 拒掉，不会静默跑空）。
         *
         * @return null = 参数非法（调用方应把错误说明回给模型）
         */
        fun build(
            pattern: String,
            tool: String,
            args: String?,
            name: String?,
            count: Int?,
            windowMs: Long?,
            thresholdDeg: Float?,
            durationMs: Long?,
            direction: String?,
        ): MotionRule? {
            if (pattern !in MotionPatterns.ALL) return null
            val t = tool.trim()
            if (t.isEmpty() || t in DENY_TOOL_TARGETS) return null
            val a = normalizeArgs(args) ?: return null
            val dir = when (pattern) {
                MotionPatterns.PITCH_OVER -> if (direction == "down") "down" else "up"
                else -> ""
            }
            // 收敛后的次数：自动命名必须用**同一个值**，否则规则名写成「点头×99」而实际只认 5 次
            val n = (count ?: 1).coerceIn(1, 5)
            return MotionRule(
                id = UUID.randomUUID().toString().replace("-", "").take(8),
                name = name?.trim()?.takeIf { it.isNotBlank() } ?: defaultName(pattern, n, dir, t),
                pattern = pattern,
                count = n,
                windowMs = (windowMs ?: 3000L).coerceIn(1500L, 10_000L),
                thresholdDeg = (thresholdDeg ?: 15f).coerceIn(8f, 45f),
                durationMs = (durationMs ?: 1500L).coerceIn(1000L, 10_000L),
                direction = dir,
                tool = t,
                args = a,
            )
        }

        /** 入参归一化：空 → `"{}"`；非 JSON 对象 → null（数组、字面量、坏串都拒收） */
        private fun normalizeArgs(raw: String?): String? {
            val s = raw?.trim().orEmpty()
            if (s.isEmpty()) return "{}"
            return runCatching { JSONObject(s).toString() }.getOrNull()
        }

        private fun defaultName(pattern: String, count: Int?, dir: String, tool: String): String {
            val gesture = when (pattern) {
                MotionPatterns.NOD -> "点头" + (if ((count ?: 1) > 1) "×${count}" else "")
                MotionPatterns.SHAKE -> "摇头" + (if ((count ?: 1) > 1) "×${count}" else "")
                MotionPatterns.STILL -> "保持静止"
                else -> if (dir == "down") "持续低头" else "持续抬头"
            }
            // 默认名带上工具：一个动作可以绑定多个工具，列表里只看到「点头」分不清哪条是哪条
            return "$gesture→$tool"
        }
    }
}

/**
 * 边沿触发闸门：只在「未命中 → 命中」的那一次放行（纯状态机，可单测）。
 *
 * 为什么必须有（v2 放开动作白名单后才暴露）：`still`（保持静止）与 `pitch_over`（持续抬头）
 * 是**状态型**判定 —— 只要用户还坐在那儿、头还抬着，之后每个判定窗口都成立。
 * 若按「命中就触发 + 冷却收敛」，一条「保持静止就停止音乐」会每 3 秒执行一次；
 * 换成「点头问时间」这类**会出声**的动作，就变成无限重复播报（用户根本停不下来）。
 *
 * 改为边沿触发后：一次持续状态只触发一次，状态消失（用户动了 / 放下头）后重新武装。
 * 往复型（nod/shake）天然是边沿 —— 动作结束后窗口滑过、计数归零，随即重新武装。
 */
class MotionEdgeGate {
    private val active = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /**
     * @param matched 本条规则在当前采样窗口上是否成立
     * @return true = 本次属于「刚进入命中状态」，调用方应当触发；false = 持续命中中的后续帧
     */
    fun allow(ruleId: String, matched: Boolean): Boolean {
        val was = active.put(ruleId, matched) ?: false
        return matched && !was
    }

    /** 清空全部状态（引擎停止时调用：下次启动从「未命中」重新开始） */
    fun clear() = active.clear()
}

/**
 * IMU 动作判定器（纯函数）。
 *
 * 输入为时间升序的采样序列（~20Hz，来自 [MotionBuffer]），全部基于**相对窗口起点**的
 * 角度差分判定；yaw 存在 ±180° 环绕，差分统一经 [angularDelta] 归一。
 */
object MotionPatternDetector {

    /** 判定所需最少样本数：窗口至少 ~200ms（20Hz×4）才谈得上「动作」 */
    private const val MIN_SAMPLES = 5

    /** 窗口时间覆盖率闸门：样本跨度不足窗口 60% 视为「数据流刚启动/断流」，不做判定 */
    private const val MIN_WINDOW_COVERAGE = 0.6f

    /**
     * 四元数 → ZYX 欧拉角（度），坐标系为眼镜传感器系。
     * @return [pitch(绕Y), yaw(绕Z), roll(绕X)]
     */
    fun eulerDeg(s: AiChannel.ImuSample): FloatArray {
        val qw = s.qw.toDouble(); val qx = s.qx.toDouble()
        val qy = s.qy.toDouble(); val qz = s.qz.toDouble()
        val roll = Math.toDegrees(atan2(2.0 * (qw * qx + qy * qz), 1.0 - 2.0 * (qx * qx + qy * qy)))
        val sinP = (-2.0 * (qx * qz - qw * qy)).coerceIn(-1.0, 1.0)
        val pitch = Math.toDegrees(asin(sinP))
        val yaw = Math.toDegrees(atan2(2.0 * (qw * qz + qx * qy), 1.0 - 2.0 * (qy * qy + qz * qz)))
        return floatArrayOf(pitch.toFloat(), yaw.toFloat(), roll.toFloat())
    }

    /**
     * 往复动作计数（nod 用 pitch 轴 / shake 用 yaw 轴），**偏离-回中迟滞模型**：
     *  - 偏离：相对基线越过 +thresholdDeg（进入正区）或低于 -thresholdDeg（进入负区）；
     *  - 回中：从阈值区回到半阈值（迟滞带内）即计 1 次并回到中性态。
     *
     * 为什么用迟滞而不是「正负双向越阈」：自然点头回到中立位时往往**不会**反向越过
     * +thresholdDeg，双向模型会漏计；迟滞模型只要求「明显偏出去、再明显收回来」，
     * 与人的动作节奏一致。左右/上下两个方向的偏离都计入（摇头本就是左右交替）。
     */
    fun countOscillations(
        samples: List<AiChannel.ImuSample>,
        windowMs: Long,
        axis: Int,
        thresholdDeg: Float,
    ): Int {
        val window = tailWindow(samples, windowMs) ?: return 0
        val base = eulerDeg(window.first())[axis]
        val hysteresis = thresholdDeg / 2f
        var state = 0 // 0=中性 +1=正区 -1=负区
        var counts = 0
        for (s in window) {
            val d = angularDelta(eulerDeg(s)[axis], base)
            when {
                state == 0 && d > thresholdDeg -> state = 1
                state == 0 && d < -thresholdDeg -> state = -1
                state == 1 && d < hysteresis -> { counts++; state = 0 }
                state == -1 && d > -hysteresis -> { counts++; state = 0 }
            }
        }
        return counts
    }

    /** STILL：最近 [durationMs] 内三轴相对窗口起点的最大偏移都 < [thresholdDeg] */
    fun isStill(samples: List<AiChannel.ImuSample>, durationMs: Long, thresholdDeg: Float): Boolean {
        val window = tailWindow(samples, durationMs) ?: return false
        val base = eulerDeg(window.first())
        for (s in window) {
            val e = eulerDeg(s)
            for (axis in 0..2) {
                if (abs(angularDelta(e[axis], base[axis])) >= thresholdDeg) return false
            }
        }
        return true
    }

    /**
     * PITCH_OVER：pitch 相对姿态基线持续高于（up）/低于（down）[thresholdDeg] 达 [durationMs]。
     *
     * 「持续」的计时起点 = 最近一次**连续保持在阈值侧**的起始时刻：在 lookback
     * （durationMs + 2s）范围内扫描，中途出界即重新计时；持续时长达到 durationMs 即命中。
     * 一次持续保持只触发一次（头一直抬着不会按冷却反复触发，低头回位后再次抬头才重新计时）。
     */
    fun isPitchOver(
        samples: List<AiChannel.ImuSample>,
        durationMs: Long,
        thresholdDeg: Float,
        up: Boolean,
    ): Boolean {
        val lookback = durationMs + 2_000L
        val window = tailWindow(samples, lookback) ?: return false
        val base = eulerDeg(window.first())[MotionAxes.PITCH]
        var crossingStart: Long? = null
        for (s in window) {
            val d = angularDelta(eulerDeg(s)[MotionAxes.PITCH], base)
            val inZone = if (up) d > thresholdDeg else d < -thresholdDeg
            if (inZone) {
                if (crossingStart == null) crossingStart = s.t
            } else {
                crossingStart = null // 出界即重置：只认「连续保持在阈值侧」的时段
            }
        }
        val start = crossingStart ?: return false
        return window.last().t - start >= durationMs
    }

    /** 取尾部时间窗；样本不足或跨度不够（覆盖率闸门）返回 null */
    private fun tailWindow(samples: List<AiChannel.ImuSample>, windowMs: Long): List<AiChannel.ImuSample>? {
        if (samples.size < MIN_SAMPLES) return null
        val end = samples.last().t
        val window = samples.filter { it.t >= end - windowMs }
        if (window.size < MIN_SAMPLES) return null
        val span = window.last().t - window.first().t
        // 往复类窗口起点即基线，跨度不达标说明流刚启动，样本不足以代表整窗
        if (windowMs >= 1000 && span < windowMs * MIN_WINDOW_COVERAGE) return null
        return window
    }

    /** 角度差分并归一到 (-180, 180]，消除 yaw 环绕跳变 */
    private fun angularDelta(cur: Float, base: Float): Float {
        var d = cur - base
        if (d > 180f) d -= 360f
        if (d < -180f) d += 360f
        return d
    }
}
