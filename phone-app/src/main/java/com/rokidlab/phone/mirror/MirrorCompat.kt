package com.rokidlab.phone.mirror

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.display.DisplayManager
import android.util.Log
import com.rokidlab.phone.util.RomFingerprint

/**
 * 手机投屏兼容性：运行时探测 + 自适应降级阶梯。
 *
 * ── 为什么不再用静态黑名单 ──────────────────────────────────────
 * 旧方案（ManufacturerUtils.isMediaProjectionBlacklisted + getHwcDisableProps）
 * 在实际设备上是不生效的：
 *  1. `debug.sf.enable_hwc_vds` / `debug.sf.hw` 属于 SurfaceFlinger 系统属性，
 *     第三方 App 无 root/system 签名写入必失败，且失败是**静默**的（exit 0）。
 *  2. 即使真写成功，这些属性也只影响**下次** SurfaceFlinger 重建，
 *     对本次已经建立的 VirtualDisplay 完全无效。
 *  3. 判定条件按 `sdk >= 33` 一刀切，把大量其实没问题的设备也判成黑名单。
 *
 * 于是形成了一个尴尬局面：检测到问题 → 执行必定失败的修复 → 从不验证 → 没有下一步。
 *
 * ── 本方案的思路 ─────────────────────────────────────────────
 * 不再猜设备，改为**在应用层直接测量**：
 *   创建虚拟屏 → 采样前若干帧 → 持续全黑就升一档重建 → 直到出画面。
 * 成功档位持久化，下次同一 ROM 直接命中；ROM 升级后签名变化，自动重新探测。
 * 全程不需要 root，不涉及任何系统属性写入。
 */
object MirrorCompat {
    private const val TAG = "MirrorCompat"

    // ── 判定参数 ────────────────────────────────────────────────

    /**
     * 单帧判定「黑」的最高灰度。取 12 而非 0，是为了覆盖 MTK 部分机型返回的
     * 近零脏数据；同时搭配 [UNIFORM_DELTA] 要求画面「均匀」，避免把本项目的
     * 丝绒炭黑主题（#0B0B0E，灰度约 11）误判成黑屏 —— 那类画面有文字，明暗有差异。
     */
    private const val BLACK_MAX = 12

    /** 明暗差异上限；低于此值说明画面没有内容，而不是「恰好很暗」 */
    private const val UNIFORM_DELTA = 4

    /** 采样步长：每 Step 个像素取一个点，兼顾判定准确度与开销 */
    private const val SAMPLE_STEP = 8

    // ── 降级阶梯 ────────────────────────────────────────────────

    /**
     * 降级档位。**顺序即探测顺序**（代价由小到大）。
     *
     * @param scale      相对基准分辨率的缩放
     * @param buffers    ImageReader 并发缓冲数
     * @param warmupMs   虚拟屏创建后的预热等待；部分 ROM 首帧晚于此处到
     * @param autoMirror 是否加 VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR
     */
    enum class Tier(
        val scale: Float,
        val buffers: Int,
        val warmupMs: Long,
        val autoMirror: Boolean,
        val note: String,
    ) {
        BASELINE(scale = 1.0f, buffers = 2, warmupMs = 500L, autoMirror = false,
            note = "baseline, no change"),
        AUTO_MIRROR(scale = 1.0f, buffers = 2, warmupMs = 500L, autoMirror = true,
            note = "add VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR"),
        MID_RES(scale = 0.62f, buffers = 3, warmupMs = 800L, autoMirror = true,
            note = "auto-mirror + lower resolution"),
        LOW_RES(scale = 0.45f, buffers = 4, warmupMs = 1400L, autoMirror = true,
            note = "auto-mirror + much lower resolution, longer warmup"),
        MIN_RES(scale = 0.32f, buffers = 4, warmupMs = 2200L, autoMirror = true,
            note = "lowest acceptable resolution, longest warmup"),
        ;

        /** 下一档；已是最后一档返回 null */
        fun next(): Tier? = entries.getOrNull(ordinal + 1)

        companion object {
            fun fromOrdinal(v: Int): Tier = entries.getOrElse(v) { BASELINE }
        }
    }

    /** 按当前档位 + 基准尺寸，算出该次实际使用的投屏参数 */
    fun paramsFor(tier: Tier, baseWidth: Int, baseHeight: Int): MirrorParams {
        val w = roundToEven((baseWidth * tier.scale).toInt().coerceAtLeast(MIN_WIDTH))
        val h = roundToEven((baseHeight * tier.scale).toInt().coerceAtLeast(MIN_HEIGHT))
        val flags = if (tier.autoMirror) DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR else 0
        return MirrorParams(w, h, flags, tier.buffers, tier.warmupMs)
    }

    private const val MIN_WIDTH = 120
    private const val MIN_HEIGHT = 160

    /** 灰度协议对尺寸无对齐要求，但偶数尺寸在部分编码器上更稳，统一向上取偶 */
    private fun roundToEven(v: Int): Int = if (v <= 0) MIN_WIDTH else if (v % 2 == 0) v else v - 1

    data class MirrorParams(
        val width: Int,
        val height: Int,
        val flags: Int,
        val buffers: Int,
        val warmupMs: Long,
    )

    // ── 黑帧检测 ────────────────────────────────────────────────

    /**
     * 判定灰度帧是否为「无内容的黑帧」。
     * 同时要求 ①整体够暗 ②明暗基本无差异，避免把深色主题的合法画面误判为黑屏。
     *
     * @param gray 灰度数据（行优先，长度 >= width*height）
     */
    fun isBlackFrame(gray: ByteArray, width: Int, height: Int): Boolean {
        if (width <= 0 || height <= 0) return true
        var min = 255
        var max = 0
        var y = 0
        while (y < height) {
            val row = y * width
            var x = 0
            while (x < width) {
                val v = gray[row + x].toInt() and 0xFF
                if (v < min) min = v
                if (v > max) max = v
                x += SAMPLE_STEP
            }
            y += SAMPLE_STEP
        }
        return max <= BLACK_MAX && (max - min) <= UNIFORM_DELTA
    }

    /**
     * 连续性黑帧探测器。
     *
     * 单帧发黑是正常的（虚拟屏首帧通常晚到），只有「连续 N 帧且持续超过 T 毫秒」才算失败。
     * 每重建一次 VirtualDisplay 必须调用 [reset]，否则会把旧窗口的计数带过来。
     */
    class BlackFrameProbe(
        private val minFrames: Int = 12,
        private val minDurationMs: Long = 1200L,
        private val onPersistentBlack: (frames: Int) -> Unit,
        private val onHealthy: () -> Unit,
    ) {
        private var startMs = 0L
        private var blackFrames = 0
        @Volatile
        private var settled = false

        fun reset() {
            startMs = System.currentTimeMillis()
            blackFrames = 0
            settled = false
        }

        /** 每次收到一帧调用一次 */
        fun submit(black: Boolean) {
            if (settled) return
            if (!black) {
                settled = true
                Log.i(TAG, "Frame content detected healthy after $blackFrames black frame(s)")
                onHealthy()
                return
            }
            blackFrames++
            val elapsed = System.currentTimeMillis() - startMs
            if (blackFrames >= minFrames && elapsed >= minDurationMs) {
                settled = true
                Log.w(TAG, "Persistent black frame: $blackFrames frames in ${elapsed}ms")
                onPersistentBlack(blackFrames)
            }
        }
    }

    // ── 档位持久化 ──────────────────────────────────────────────

    private const val PREFS = "rokidlab_mirror_compat"
    private const val KEY_TIER = "tier"
    private const val KEY_SIG = "signature"
    private const val KEY_FAIL_REPORTED = "fail_reported"

    /**
     * 取上次成功的档位。仅当设备签名一致时复用；
     * ROM 升级 / 系统大版本更新后签名变化，会自动回落到 BASELINE 重新探测。
     */
    fun learnedTier(context: Context): Tier? {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val sig = RomFingerprint.signature()
        if (sp.getString(KEY_SIG, "") != sig) {
            Log.i(TAG, "Signature changed, discarding learned tier (sig=$sig)")
            return null
        }
        return Tier.fromOrdinal(sp.getInt(KEY_TIER, -1)).takeIf { sp.getInt(KEY_TIER, -1) >= 0 }
    }

    /** 记录成功档位（设备签名一并写入，用于失效判断） */
    fun rememberSuccess(context: Context, tier: Tier) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_TIER, tier.ordinal)
            .putString(KEY_SIG, RomFingerprint.signature())
            .putBoolean(KEY_FAIL_REPORTED, false)
            .apply()
        Log.i(TAG, "Learned working tier: ${tier.name} (${tier.note})")
    }

    /**
     * 全部档位都失败的兜底记录。返回 true 表示本次是「首次失败」，
     * 调用方据此决定是否提示用户导出诊断报告（避免每次投屏都弹）。
     */
    @SuppressLint("ApplySharedPref")
    fun markExhausted(context: Context): Boolean {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val already = sp.getBoolean(KEY_FAIL_REPORTED, false)
        sp.edit()
            .putBoolean(KEY_FAIL_REPORTED, true)
            .putString(KEY_SIG, RomFingerprint.signature())
            .apply()
        return !already
    }

    /** 供设置页「重置兼容性学习」按钮调用 */
    fun resetLearning(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        Log.i(TAG, "Compat learning reset")
    }

    /** 供设置页展示当前状态 */
    fun currentStatus(context: Context): String =
        learnedTier(context)?.let { "${it.name} (${it.note})" } ?: "not learned yet"
}
