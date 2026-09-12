package com.rokidlab.phone.hid

import android.content.Context
import android.os.Build

/**
 * 蓝牙 HID 设备（手机作为 HID Device，眼镜作为 HID Host）的跨品牌兼容策略。
 *
 * ── 为什么要这个文件 ──
 * 原实现里 QTI 判定依赖两个系统属性：
 *   bluetooth.host.stacks / vendor.bluetooth.host.stacks
 * 这两个属性在绝大多数量产机上并不存在（它们是 AOSP 调试/部分老 ROM 的遗留），
 * 因此对所有非 vivo 设备都返回 false —— 小米 15（Snapdragon，实际就是 QTI 栈）
 * 也被当成 AOSP 栈处理。这正是"某些品牌能用、某些品牌不行"的直接来源。
 *
 * ── 这里的做法 ──
 * 1. 用**芯片/平台指纹**判定蓝牙栈（ro.soc.model / ro.board.platform / ro.hardware …），
 *    品牌只作为兜底信号；判定结果只影响"优先尝试哪种发送方式"，不影响流程能否走下去。
 * 2. 每种栈给出一份**有序的发送方式候选**；成功即停止，并把结果记下来，
 *    下次同栈直通上次成功的策略。人工也可强制覆盖（用于现场排查）。
 * 3. 报告长度按**当前已注册的描述符**归一化：统计量不足补齐、超出则截断、
 *    未声明的 Report ID 直接丢弃 —— 严格的蓝牙栈会拒绝长度不匹配的报告。
 *
 * 注意：本文件所有失败路径都退化为 UNKNOWN / 默认策略，绝不因为判定失败中断主流程。
 */
object BtHidCompat {

    // ========================================================================
    //  模型
    // ========================================================================

    /** 蓝牙协议栈归属 */
    enum class Stack(val label: String) {
        QTI("QTI/Qualcomm"),
        MEDIATEK("MediaTek"),
        BROADCOM("Broadcom"),
        GOOGLE("Google/Gabeldorsche"),
        SAMSUNG("Samsung"),
        UNKNOWN("unknown"),
    }

    /**
     * HID 报告的三种发送方式。
     * STANDARD : sendReport(dev, reportId, data)，系统自动在报文前插入 Report ID
     * PREFIXED : sendReport(dev, 0, [id] + data)，手动把 Report ID 拼进数据
     * SET_REPORT: 走控制通道（隐藏 API setReport），仅作最后兜底
     */
    enum class SendMode(val label: String) {
        STANDARD("standard"),
        PREFIXED("prefixed"),
        SET_REPORT("setReport"),
    }

    /** 当前注册的描述符种类，决定报告长度与合法的 Report ID */
    enum class DescriptorKind(val label: String) {
        FULL("full"),
        QTI("qti"),

        /**
         * QTI 精简描述符 + Mouse（Report ID 3）。
         *
         * 修复：原 QTI 描述符只含 Consumer + Keyboard，QTI/Qualcomm 栈（如小米 SM8750）
         * 下鼠标报告被 [normalize] 判为「未声明」直接丢弃 → 手柄鼠标页完全无反应。
         * 现优先注册带 Mouse 的版本；若该栈拒绝（描述符变长），[BluetoothHidManager]
         * 会自动回退到无 Mouse 的 [QTI]，保证不回归。
         */
        QTI_FULL("qti-full"),
        GAMEPAD("gamepad"),
        QTI_GAMEPAD("qti-gamepad"),
    }

    private const val PREF_NAME = "bthid_compat"
    private const val PREF_KEY_MODE_PREFIX = "mode_"

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var cachedStack: Stack? = null

    /** 判定依据，用于诊断报告 */
    @Volatile
    private var cachedEvidence: String = ""

    /** 人工强制指定的发送方式（诊断/现场排障用），null 表示自动 */
    @Volatile
    private var manualMode: SendMode? = null

    fun bind(context: Context) {
        appContext = context.applicationContext
    }

    // ========================================================================
    //  栈判定
    // ========================================================================

    fun detectStack(): Stack {
        cachedStack?.let { return it }
        val (stack, evidence) = probe()
        cachedStack = stack
        cachedEvidence = evidence
        return stack
    }

    fun evidence(): String = cachedEvidence

    private fun probe(): Pair<Stack, String> {
        val reasons = mutableListOf<String>()

        // 1) 显式声明的宿主栈属性（个别 ROM 会写，优先级最高）
        val stackProps = listOf(
            "vendor.bluetooth.host.stacks",
            "bluetooth.host.stacks",
            "persist.vendor.bluetooth.stack",
        )
        for (p in stackProps) {
            val v = prop(p)
            if (v.isBlank()) continue
            val s = v.lowercase()
            reasons.add("$p=$v")
            when {
                KEYWORDS_QTI.any { s.contains(it) } -> return Stack.QTI to reasons.joinToString(", ")
                KEYWORDS_MTK.any { s.contains(it) } -> return Stack.MEDIATEK to reasons.joinToString(", ")
                KEYWORDS_BCM.any { s.contains(it) } -> return Stack.BROADCOM to reasons.joinToString(", ")
            }
        }

        // 2) MediaTek 专有属性（比平台名更可靠）
        val mtkProp = prop("ro.mediatek.platform")
        if (mtkProp.isNotBlank()) {
            reasons.add("ro.mediatek.platform=$mtkProp")
            return Stack.MEDIATEK to reasons.joinToString(", ")
        }

        // 3) 芯片平台指纹 —— 真实反映 SoC，也就反映了蓝牙栈归属
        val soc = listOf(
            prop("ro.soc.model"),
            prop("ro.soc.manufacturer"),
            prop("ro.board.platform"),
            prop("ro.hardware"),
            prop("ro.chipname"),
            prop("ro.product.board"),
        ).filter { it.isNotBlank() }
        soc.forEach { reasons.add("soc=$it") }

        for (raw in soc) {
            val s = raw.lowercase()
            if (KEYWORDS_QTI.any { s.contains(it) } || PLATFORMS_QTI.any { s.startsWith(it) }) {
                return Stack.QTI to reasons.joinToString(", ")
            }
            if (KEYWORDS_MTK.any { s.contains(it) } || s.startsWith("mt")) {
                return Stack.MEDIATEK to reasons.joinToString(", ")
            }
            if (s.contains("exynos")) return Stack.SAMSUNG to reasons.joinToString(", ")
            if (s.contains("gs101") || s.contains("gs201") || s.contains("zuma") || s.contains("tensor")) {
                return Stack.GOOGLE to reasons.joinToString(", ")
            }
            if (KEYWORDS_BCM.any { s.contains(it) }) return Stack.BROADCOM to reasons.joinToString(", ")
        }

        // 4) 品牌兜底：只知道品牌时按主力平台估计
        val manufacturer = Build.MANUFACTURER?.lowercase().orEmpty()
        val brand = Build.BRAND?.lowercase().orEmpty()
        reasons.add("brand=$manufacturer/$brand")
        val stack = when {
            matchesBrand("google") -> Stack.GOOGLE
            matchesBrand("samsung") -> Stack.SAMSUNG
            matchesBrand("vivo") || matchesBrand("iqoo") ||
                matchesBrand("oppo") || matchesBrand("oneplus") ||
                matchesBrand("realme") || matchesBrand("xiaomi") ||
                matchesBrand("redmi") || matchesBrand("poco") -> Stack.QTI
            else -> Stack.UNKNOWN
        }
        return stack to reasons.joinToString(", ")
    }

    private fun matchesBrand(key: String): Boolean =
        Build.MANUFACTURER?.lowercase()?.contains(key) == true ||
            Build.BRAND?.lowercase()?.contains(key) == true

    private val KEYWORDS_QTI = listOf("qti", "qualcomm", "qcom", "snapdragon")
    private val KEYWORDS_MTK = listOf("mediatek", "mtk")
    private val KEYWORDS_BCM = listOf("broadcom", "bcm")

    /** Qualcomm 平台代号：msm / sdm / apq 系列前缀 + 近几代内部代号 */
    private val PLATFORMS_QTI = listOf(
        "msm", "apq", "sdm", "sm",
        "kona", "lahaina", "shima", "taro", "kalama", "pineapple", "cliffs", "parrot",
        "sun", "niobe", "ukee", "anorak", "le", "blair", "conda", "crow", "ravel", "tuna",
        "msmnile", "trinket", "bengal", "scuba", "khaje", "holi", "monaco", "gen3auto",
    )

    // ========================================================================
    //  发送策略
    // ========================================================================

    /** 默认的候选顺序：按栈给出先验，谁成功谁留下 */
    private fun defaultOrder(stack: Stack): List<SendMode> = when (stack) {
        // QTI 栈部分机型 standard 返回 true 但实际未投递，故优先手动拼 Report ID
        Stack.QTI -> listOf(SendMode.PREFIXED, SendMode.SET_REPORT, SendMode.STANDARD)
        Stack.MEDIATEK,
        Stack.BROADCOM,
        Stack.GOOGLE,
        Stack.SAMSUNG,
        Stack.UNKNOWN -> listOf(SendMode.STANDARD, SendMode.PREFIXED, SendMode.SET_REPORT)
    }

    /**
     * 当前应当采用的候选顺序：人工覆盖优先，其次是上次成功记录，最后是栈的先验顺序。
     */
    fun modeOrder(stack: Stack): List<SendMode> {
        val forced = manualMode
        if (forced != null) return listOf(forced)
        val learned = readLearned(stack)
            ?: return defaultOrder(stack)
        // 已验证的方式排到最前，其余保持默认相对顺序作为兜底
        return listOf(learned) + defaultOrder(stack).filter { it != learned }
    }

    /** 记录本次成功的发送方式，供下次直通 */
    fun rememberWorked(stack: Stack, mode: SendMode) {
        val prefs = appContext?.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE) ?: return
        if (prefs.getString(PREF_KEY_MODE_PREFIX + stack.name, null) == mode.name) return
        prefs.edit().putString(PREF_KEY_MODE_PREFIX + stack.name, mode.name).apply()
    }

    private fun readLearned(stack: Stack): SendMode? {
        val prefs = appContext?.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE) ?: return null
        val name = prefs.getString(PREF_KEY_MODE_PREFIX + stack.name, null) ?: return null
        return runCatching { SendMode.valueOf(name) }.getOrNull()
    }

    /** 人工强制指定策略（null 恢复自动） */
    fun setManualMode(mode: SendMode?) {
        manualMode = mode
    }

    fun currentManualMode(): SendMode? = manualMode

    /** 同一设备清除已学习的策略（ROM 升级后可用于重新标定） */
    fun resetLearned() {
        val prefs = appContext?.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE) ?: return
        prefs.edit().clear().apply()
    }

    // ========================================================================
    //  时序参数
    // ========================================================================

    /** 报告之间的最小间隔：QTI 栈连续写入容易丢包，需要节流 */
    fun minReportIntervalMs(stack: Stack): Long = when (stack) {
        Stack.QTI -> 8L
        Stack.MEDIATEK -> 4L
        else -> 0L
    }

    /** 连接建立后到中断通道真正可用之间的等待 */
    fun channelWarmupMs(stack: Stack): Long = when (stack) {
        Stack.QTI -> 500L
        Stack.SAMSUNG -> 400L
        Stack.MEDIATEK -> 400L
        else -> 300L
    }

    /** SDP 描述符体积上限：超过则使用裁剪版描述符 */
    fun maxDescriptorBytes(stack: Stack): Int = if (stack == Stack.QTI) 64 else Int.MAX_VALUE

    // ========================================================================
    //  报告与描述符的一致性
    // ========================================================================

    /** 指定描述符中某 Report ID 声明的数据长度（不含 ID 本身）；未声明返回 -1 */
    fun declaredLength(kind: DescriptorKind, reportId: Int): Int = when (kind) {
        DescriptorKind.FULL -> when (reportId) {
            1 -> 2   // modifier(1) + keycode(1)
            2 -> 2   // consumer usage(2)
            3 -> 4   // buttons(1) + x + y + wheel(1)
            else -> -1
        }
        DescriptorKind.QTI -> when (reportId) {
            1 -> 2
            2 -> 2
            else -> -1  // 精简描述符（无鼠标）
        }
        DescriptorKind.QTI_FULL -> when (reportId) {
            1 -> 2
            2 -> 2
            3 -> 4   // buttons(1) + x + y + wheel(1)
            else -> -1
        }
        DescriptorKind.GAMEPAD, DescriptorKind.QTI_GAMEPAD ->
            if (reportId == 4) 4 else -1  // buttons(2) + hat(1) + reserved(1)
    }

    /** 当前描述符里声明了哪些 Report ID（通道预热按顺序用它们唤醒） */
    fun declaredReportIds(kind: DescriptorKind): List<Int> = when (kind) {
        DescriptorKind.FULL -> listOf(1, 2, 3)
        DescriptorKind.QTI -> listOf(1, 2)
        DescriptorKind.QTI_FULL -> listOf(1, 2, 3)
        DescriptorKind.GAMEPAD, DescriptorKind.QTI_GAMEPAD -> listOf(4)
    }

    /**
     * 把待发送数据归一化到描述符声明的长度。
     * 长于声明 → 截断（多余字节是描述符里没有的，严格栈会整包拒绝）；
     * 短于声明 → 补零（部分栈要求报文长度完全一致）。
     * 未声明的 Report ID 返回 null，调用方应直接丢弃而不是硬发。
     */
    fun normalize(reportId: Int, data: ByteArray, kind: DescriptorKind): ByteArray? {
        val declared = declaredLength(kind, reportId)
        if (declared < 0) return null
        return when {
            data.size == declared -> data
            data.size > declared -> data.copyOf(declared)
            else -> ByteArray(declared).also { dst -> data.copyInto(dst) }
        }
    }

    // ========================================================================
    //  工具
    // ========================================================================

    fun diagnostics(): String = buildString {
        val stack = detectStack()
        append("btStack=").append(stack.label).append('\n')
        append("  evidence: ").append(cachedEvidence).append('\n')
        append("  sendOrder: ").append(modeOrder(stack).joinToString(" -> ") { it.label }).append('\n')
        append("  reportGap=").append(minReportIntervalMs(stack)).append("ms")
        append(", warmup=").append(channelWarmupMs(stack)).append("ms").append('\n')
        append("  maxDescriptor=").append(
            if (maxDescriptorBytes(stack) == Int.MAX_VALUE) "无限制" else "${maxDescriptorBytes(stack)}B"
        )
    }

    private fun prop(name: String): String =
        com.rokidlab.phone.platform.RomAdapter.systemProperty(name) ?: ""
}
