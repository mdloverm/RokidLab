package com.rokidlab.phone.glasses

import com.rokidlab.phone.ai.ToolRegistry
import com.rokidlab.phone.glasses.AiChannel.ImuSample
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2 头动规则层单测：判定核心（[MotionPatternDetector]）+ 规则数据（[MotionRule]）。
 *
 * 合成数据方式：由欧拉角构造四元数（ZYX 内旋标准公式），经 [MotionPatternDetector.eulerDeg]
 * 反解回欧拉角喂给判定器 —— 与真机数据走完全相同的换算路径，换算误差一并被测试覆盖。
 * 真机轴向有固定偏置（平视 pitch≈-1° yaw≈144° roll≈78°），本套测试刻意把「静止」用例
 * 放在大偏置姿态下验证，锁死「判定只用相对变化量」这条约束。
 */
class MotionRulesTest {

    /** 采样间隔：与真机一致 ~20Hz（50ms） */
    private val stepMs = 50L

    private var clock = 1_000_000L

    private fun resetClock() {
        clock = 1_000_000L
    }

    /** 欧拉角（度）→ 四元数（ZYX 内旋，与 eulerDeg 反解互逆） */
    private fun quat(pitchDeg: Double, yawDeg: Double, rollDeg: Double): FloatArray {
        val cy = Math.cos(Math.toRadians(yawDeg) / 2); val sy = Math.sin(Math.toRadians(yawDeg) / 2)
        val cp = Math.cos(Math.toRadians(pitchDeg) / 2); val sp = Math.sin(Math.toRadians(pitchDeg) / 2)
        val cr = Math.cos(Math.toRadians(rollDeg) / 2); val sr = Math.sin(Math.toRadians(rollDeg) / 2)
        return floatArrayOf(
            (cy * cp * cr + sy * sp * sr).toFloat(),
            (cy * cp * sr - sy * sp * cr).toFloat(),
            (cy * sp * cr + sy * cp * sr).toFloat(),
            (sy * cp * cr - cy * sp * sr).toFloat(),
        )
    }

    /** 追加一个采样点：姿态由欧拉角给定，角速度/加速度填零（判定器只消费姿态） */
    private fun step(pitch: Double = 0.0, yaw: Double = 0.0, roll: Double = 0.0): ImuSample {
        val q = quat(pitch, yaw, roll)
        val s = ImuSample(
            t = clock, ax = 0f, ay = 0f, az = 9.8f, gx = 0f, gy = 0f, gz = 0f,
            qw = q[0], qx = q[1], qy = q[2], qz = q[3],
        )
        clock += stepMs
        return s
    }

    /** 生成「低头型」往复序列：每个周期一次明显低头（-amp）并回中，自然点头节奏 */
    private fun dips(axis: Int, amp: Double, periodMs: Double, totalMs: Double): List<ImuSample> {
        val out = mutableListOf<ImuSample>()
        var t = 0.0
        while (t < totalMs) {
            val v = -amp * maxOf(0.0, Math.sin(2 * Math.PI * t / periodMs))
            out.add(
                when (axis) {
                    MotionAxes.PITCH -> step(pitch = v)
                    MotionAxes.YAW -> step(yaw = v)
                    else -> step(roll = v)
                },
            )
            t += stepMs
        }
        return out
    }

    // ═══════════════════ 换算 ═══════════════════

    @Test
    fun `欧拉角往返一致`() {
        val s = step(pitch = 20.0, yaw = 30.0, roll = 10.0)
        val e = MotionPatternDetector.eulerDeg(s)
        assertEquals(20.0, e[0].toDouble(), 0.01)
        assertEquals(30.0, e[1].toDouble(), 0.01)
        assertEquals(10.0, e[2].toDouble(), 0.01)
    }

    // ═══════════════════ 点头 / 摇头（往复计数） ═══════════════════

    @Test
    fun `点头两次计两次`() {
        resetClock()
        // 两次完整低头-回中（1.6s/次）；窗口 4s 覆盖全序列且满足 60% 跨度闸门
        val samples = dips(MotionAxes.PITCH, amp = 25.0, periodMs = 1600.0, totalMs = 3300.0)
        assertEquals(
            2,
            MotionPatternDetector.countOscillations(samples, windowMs = 4_000, axis = MotionAxes.PITCH, thresholdDeg = 15f),
        )
    }

    @Test
    fun `幅度不足不计入往复`() {
        resetClock()
        val samples = dips(MotionAxes.PITCH, amp = 10.0, periodMs = 1600.0, totalMs = 3300.0)
        assertEquals(
            0,
            MotionPatternDetector.countOscillations(samples, windowMs = 4_000, axis = MotionAxes.PITCH, thresholdDeg = 15f),
        )
    }

    @Test
    fun `点头回中不足仍计次（迟滞回归）`() {
        resetClock()
        // 自然点头往往只回到中立位附近、不会反向越过正阈值 —— 迟滞模型必须照样计次
        val samples = mutableListOf<ImuSample>()
        samples.add(step(pitch = 0.0))
        repeat(2) {
            repeat(8) { i -> samples.add(step(pitch = 0.0 - 25.0 * (i + 1) / 8)) }   // 低头到 -25°
            repeat(8) { i -> samples.add(step(pitch = -25.0 + 20.0 * (i + 1) / 8)) } // 只回到 -5°（不越过正阈）
        }
        assertEquals(
            2,
            MotionPatternDetector.countOscillations(samples, windowMs = 2_000, axis = MotionAxes.PITCH, thresholdDeg = 15f),
        )
    }

    @Test
    fun `摇头只计入yaw轴不误判点头`() {
        resetClock()
        val samples = dips(MotionAxes.YAW, amp = 25.0, periodMs = 1600.0, totalMs = 3300.0)
        assertEquals(
            0,
            MotionPatternDetector.countOscillations(samples, windowMs = 4_000, axis = MotionAxes.PITCH, thresholdDeg = 15f),
        )
        assertEquals(
            2,
            MotionPatternDetector.countOscillations(samples, windowMs = 4_000, axis = MotionAxes.YAW, thresholdDeg = 15f),
        )
    }

    @Test
    fun `点头规则按次数判定命中与不命中`() {
        resetClock()
        val samples = dips(MotionAxes.PITCH, amp = 25.0, periodMs = 1600.0, totalMs = 3300.0)
        val once = MotionRule.build("nod", "control_music", null, null, 1, 4_000L, 15f, 1500L, null)!!
        val thrice = MotionRule.build("nod", "control_music", null, null, 3, 4_000L, 15f, 1500L, null)!!
        assertTrue(MotionRuleEngine.evaluate(once, samples))
        assertFalse(MotionRuleEngine.evaluate(thrice, samples))
    }

    @Test
    fun `偏置姿态下的往复仍可判定（相对变化量约束）`() {
        resetClock()
        // 真机姿态：平视 pitch≈-1° yaw≈144° roll≈78°，在该基线上做两次明显偏头
        val samples = mutableListOf<ImuSample>()
        var t = 0.0
        while (t < 3300.0) {
            samples.add(step(pitch = -1.0, yaw = 144.0 - 25.0 * maxOf(0.0, Math.sin(2 * Math.PI * t / 1600.0)), roll = 78.0))
            t += stepMs
        }
        assertEquals(
            2,
            MotionPatternDetector.countOscillations(samples, windowMs = 4_000, axis = MotionAxes.YAW, thresholdDeg = 15f),
        )
    }

    // ═══════════════════ 静止 ═══════════════════

    @Test
    fun `大偏置姿态下保持静止判定为真`() {
        resetClock()
        val samples = mutableListOf<ImuSample>()
        repeat(44) { samples.add(step(pitch = -1.0, yaw = 144.0, roll = 78.0)) } // 2.2s
        assertTrue(MotionPatternDetector.isStill(samples, durationMs = 1500, thresholdDeg = 5f))
    }

    @Test
    fun `窗口内出现大幅动作则不判静止`() {
        resetClock()
        val samples = mutableListOf<ImuSample>()
        repeat(14) { samples.add(step(pitch = -1.0, yaw = 144.0, roll = 78.0)) } // 0.65s 平稳
        repeat(6) { samples.add(step(pitch = 20.0, yaw = 144.0, roll = 78.0)) }  // 0.3s 抬头（落在尾部 1.5s 窗口内）
        repeat(24) { samples.add(step(pitch = -1.0, yaw = 144.0, roll = 78.0)) } // 1.2s 回位
        assertFalse(MotionPatternDetector.isStill(samples, durationMs = 1500, thresholdDeg = 5f))
    }

    // ═══════════════════ 持续抬头 / 低头 ═══════════════════

    @Test
    fun `持续抬头达到时长即命中`() {
        resetClock()
        val samples = mutableListOf<ImuSample>()
        repeat(20) { samples.add(step(pitch = -1.0)) }                          // 1s 平视
        repeat(40) { samples.add(step(pitch = 25.0)) }                          // 2s 抬头保持
        assertTrue(MotionPatternDetector.isPitchOver(samples, durationMs = 1500, thresholdDeg = 15f, up = true))
        assertFalse(MotionPatternDetector.isPitchOver(samples, durationMs = 1500, thresholdDeg = 15f, up = false))
    }

    @Test
    fun `持续时长不足不命中`() {
        resetClock()
        val samples = mutableListOf<ImuSample>()
        repeat(30) { samples.add(step(pitch = -1.0)) }                          // 1.5s 平视（撑起 lookback 跨度闸门）
        repeat(20) { samples.add(step(pitch = 25.0)) }                          // 0.95s 抬头（<1.5s）
        assertFalse(MotionPatternDetector.isPitchOver(samples, durationMs = 1500, thresholdDeg = 15f, up = true))
    }

    @Test
    fun `中途回落重新计时`() {
        resetClock()
        val samples = mutableListOf<ImuSample>()
        repeat(20) { samples.add(step(pitch = -1.0)) }                          // 1s 平视
        repeat(14) { samples.add(step(pitch = 25.0)) }                          // 0.7s 抬头
        repeat(10) { samples.add(step(pitch = -1.0)) }                          // 0.5s 回落（重置计时）
        repeat(28) { samples.add(step(pitch = 25.0)) }                          // 1.4s 再抬头
        // 第二次保持仅 1.4s < 1.5s：不许把第一段的时长累计进来
        assertFalse(MotionPatternDetector.isPitchOver(samples, durationMs = 1500, thresholdDeg = 15f, up = true))
        samples.add(step(pitch = 25.0)) // +50ms → 1.45s 仍不足
        assertFalse(MotionPatternDetector.isPitchOver(samples, durationMs = 1500, thresholdDeg = 15f, up = true))
        repeat(2) { samples.add(step(pitch = 25.0)) } // → 1.55s 达标
        assertTrue(MotionPatternDetector.isPitchOver(samples, durationMs = 1500, thresholdDeg = 15f, up = true))
    }

    // ═══════════════════ 规则数据与序列化 ═══════════════════

    @Test
    fun `规则JSON往返一致`() {
        val r = MotionRule.build(
            "nod", "control_music", """{"action":"stop"}""", "点头停音乐", 2, 4000L, 18f, 1500L, null,
        )!!
        val back = MotionRule.fromJson(r.toJson())
        assertNotNull(back)
        assertEquals(r, back)
    }

    @Test
    fun `旧动作规则迁移到真实存在的工具`() {
        // v2 早期存的是 action 字符串。迁移目标必须是**真实存在**的工具，
        // 否则老用户升级后旧规则会变成「一命中就报未知工具」的死规则。
        val legacy = JSONObject()
            .put("id", "abcd1234").put("name", "摇头闭嘴").put("pattern", "shake")
            .put("count", 1).put("windowMs", 3000L).put("thresholdDeg", 15.0)
            .put("durationMs", 1500L).put("direction", "")
        val known = ToolRegistry.allToolNames()

        val m = MotionRule.fromJson(JSONObject(legacy.toString()).put("action", "tts.stop"))!!
        assertEquals("stop_tts", m.tool)
        assertEquals("{}", m.args)
        assertTrue("迁移目标 stop_tts 必须真实存在", "stop_tts" in known)

        val t = MotionRule.fromJson(JSONObject(legacy.toString()).put("action", "music.stop"))!!
        assertEquals("control_music", t.tool)
        assertEquals("""{"action":"stop"}""", t.args)
        assertTrue("迁移目标 control_music 必须真实存在", "control_music" in known)

        // 认不出的旧动作 → 丢弃（宁可少一条，也不留指向空工具的规则）
        assertNull(MotionRule.fromJson(JSONObject(legacy.toString()).put("action", "reboot.device")))
    }

    @Test
    fun `非法pattern自指工具与坏args被拒收`() {
        val good = MotionRule.build("still", "get_current_time", null, null, null, null, null, null, null)!!
        assertNull(MotionRule.fromJson(good.toJson().put("pattern", "hack")))
        assertNull(MotionRule.fromJson(good.toJson().put("tool", "remove_motion_rule")))
        assertNull(MotionRule.fromJson(null))
        // 非法 pattern / 空工具 / 自指工具 的 build 同样拒收
        assertNull(MotionRule.build("jump", "get_current_time", null, null, null, null, null, null, null))
        assertNull(MotionRule.build("nod", "   ", null, null, null, null, null, null, null))
        assertNull(MotionRule.build("nod", "set_motion_rule", null, null, null, null, null, null, null))
        // args 必须是 JSON **对象**：数组 / 字符串字面量 / 坏串都拒收
        assertNull(MotionRule.build("nod", "control_music", "[1,2]", null, null, null, null, null, null))
        assertNull(MotionRule.build("nod", "control_music", "\"stop\"", null, null, null, null, null, null))
        assertNull(MotionRule.build("nod", "control_music", "{bad json", null, null, null, null, null, null))
        // 空 args → 归一成 "{}"
        assertEquals("{}", MotionRule.build("still", "get_current_time", "", null, null, null, null, null, null)!!.args)
        // 工具是否**存在**刻意不在这一层判（纯函数不查注册表，见 build 注释）：
        // 未知名照样能落成规则，由工具层落库时校验、执行时再由闸门兜底
        assertEquals("format_disk", MotionRule.build("nod", "format_disk", null, null, null, null, null, null, null)!!.tool)
    }

    @Test
    fun `参数越界收敛而非报错`() {
        val r = MotionRule.build(
            "nod", "control_music", null, "  ", count = 99, windowMs = 1L,
            thresholdDeg = 100f, durationMs = 1L, direction = "left",
        )!!
        assertEquals(5, r.count)
        assertEquals(1500L, r.windowMs)
        assertEquals(45f, r.thresholdDeg)
        assertEquals(1000L, r.durationMs)
        assertEquals("", r.direction) // 非 pitch_over 不带方向
        // 名字空白 → 自动命名（带工具名，便于区分"同动作绑不同工具"的多条规则；
        // 次数用**收敛后**的值，避免规则名叫「点头×99」而实际只认 5 次）
        assertEquals("点头×5→control_music", r.name)
        // pitch_over 方向默认 up
        assertEquals("up", MotionRule.build("pitch_over", "get_current_time", null, null, null, null, null, null, null)!!.direction)
        assertEquals("down", MotionRule.build("pitch_over", "get_current_time", null, null, null, null, null, null, "down")!!.direction)
    }

    @Test
    fun `同模式同工具同方向共享去重键`() {
        val a = MotionRule.build("nod", "control_music", null, null, 1, null, null, null, null)!!
        val b = MotionRule.build("nod", "control_music", null, null, 3, null, null, null, null)!!
        val c = MotionRule.build("shake", "control_music", null, null, 1, null, null, null, null)!!
        val d = MotionRule.build("nod", "get_weather", null, null, 1, null, null, null, null)!!
        assertEquals(a.matchKey, b.matchKey)
        assertNotEquals(a.matchKey, c.matchKey)
        // 去重键含工具：同一动作绑不同工具是两条独立规则（换工具才该替换）
        assertNotEquals(a.matchKey, d.matchKey)
    }

    // ═══════════════════ 边沿触发闸门 ═══════════════════

    @Test
    fun `边沿闸门只在状态进入时放行一次`() {
        val gate = MotionEdgeGate()
        assertTrue(gate.allow("r1", matched = true))    // 未命中 → 命中：放行
        assertFalse(gate.allow("r1", matched = true))   // 状态持续：不再放行（否则每帧都执行）
        assertFalse(gate.allow("r1", matched = true))
        assertFalse(gate.allow("r1", matched = false))  // 状态消失：本身不放行，但重新武装
        assertTrue(gate.allow("r1", matched = true))    // 再次进入命中：放行
        // 规则之间互不影响
        assertTrue(gate.allow("r2", matched = true))
        assertFalse(gate.allow("r1", matched = true))
        // 停止引擎后清空：下次启动从「未命中」重新开始
        gate.clear()
        assertTrue(gate.allow("r1", matched = true))
    }

    @Test
    fun `持续静止只执行一次（状态型动作回归）`() {
        resetClock()
        val still = MotionRule.build("still", "get_current_time", null, null, null, null, null, 3000L, null)!!
        val gate = MotionEdgeGate()
        var fired = 0
        // 模拟 6s 持续静止：判定器每帧都成立，但闸门只应放行一次
        for (n in 1..120) {
            val window = (1..n).map { step(pitch = -1.0, yaw = 144.0, roll = 78.0) }
            if (gate.allow(still.id, MotionRuleEngine.evaluate(still, window))) fired++
        }
        assertEquals(1, fired)
    }
}
