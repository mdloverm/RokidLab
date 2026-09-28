package com.rokidlab.phone.proactive

import android.content.Context
import android.util.Log
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 决策门判定结果。
 *
 * - [Pass]：放行执行；
 * - [Defer]：顺延到指定时刻再触发（调用方负责重新登记闹钟，**不推进**执行计数 —— 任务没真正跑过）；
 * - [Suppress]：本次直接跳过（调用方应照常推进调度链，避免 nextTriggerAt 卡在过去的补跑循环）。
 */
sealed class GateDecision {
    object Pass : GateDecision()
    data class Defer(val deferUntil: Long) : GateDecision()
    data class Suppress(val reason: String) : GateDecision()
}

/**
 * 决策门纯逻辑（无 Android 依赖，可 JVM 单测）。
 *
 * 「主动式陪伴」的统一准入策略，对应市面产品的标准三件套 + 冷却期：
 * 免打扰时段 / 每日上限 / 同类最小间隔 / 用户明说「别烦我」后的冷却。
 * 触发层（空闲检测、记忆回访等后续主动触发）一律先过本门再说话。
 */
object ProactiveGatePolicy {
    /** 免打扰时段默认值（分钟粒度，可被用户配置覆盖）：23:00（含）~ 08:00（不含），跨午夜窗口 */
    const val QUIET_START_MIN_DEFAULT = 23 * 60
    const val QUIET_END_MIN_DEFAULT = 8 * 60

    /** 系统主动消息每日上限（按 kind 计数） */
    const val DAILY_CAP = 3

    /** 「别烦我」冷却时长（毫秒） */
    const val COOLDOWN_MS = 24 * 60 * 60 * 1000L

    /**
     * 「别烦我」关键词（v1 窄表，宁漏勿误：只收第二人称祈使句式，
     * 不收「安静点/闭嘴」等与 stop_tts（打断当前播报）语义冲突的说法）
     */
    val SILENCE_REGEX = Regex("别烦我|别再?主动|不要主动|别再?打扰")

    /** 是否处于免打扰时段（分钟粒度；start 含起点、end 不含终点；支持跨午夜与同日窗口） */
    fun inQuietHours(
        hourOfDay: Int,
        minute: Int,
        startMin: Int = QUIET_START_MIN_DEFAULT,
        endMin: Int = QUIET_END_MIN_DEFAULT,
    ): Boolean {
        val m = hourOfDay * 60 + minute
        return if (startMin <= endMin) m >= startMin && m < endMin else m >= startMin || m < endMin
    }

    /**
     * 下一个免打扰结束时刻。
     * 23:30 问 → 明天 08:00；03:00 问 → 今天 08:00（不在免打扰内时返回的是「下一个」结束点，调用方只在免打扰时使用）。
     */
    fun nextQuietEnd(nowMs: Long, zone: ZoneId, endMin: Int = QUIET_END_MIN_DEFAULT): Long {
        val now = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMs), zone).toLocalDateTime()
        var end = now.toLocalDate().atTime(endMin / 60, endMin % 60)
        if (!end.isAfter(now)) end = end.plusDays(1)
        return end.atZone(zone).toInstant().toEpochMilli()
    }

    /**
     * 下一个免打扰开始时刻（今天已过则为明天）。
     * 用于算「今天还能说多久」——额度头卡与闲聊节奏都以清醒窗口为界。
     */
    fun nextQuietStart(nowMs: Long, zone: ZoneId, startMin: Int = QUIET_START_MIN_DEFAULT): Long {
        val now = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMs), zone).toLocalDateTime()
        var start = now.toLocalDate().atTime(startMin / 60, startMin % 60)
        if (!start.isAfter(now)) start = start.plusDays(1)
        return start.atZone(zone).toInstant().toEpochMilli()
    }

    /** 本日剩余清醒时长（毫秒）：已在免打扰内返回 0，否则到下一个免打扰开始为止 */
    fun awakeRemainingMs(
        nowMs: Long,
        zone: ZoneId,
        startMin: Int = QUIET_START_MIN_DEFAULT,
        endMin: Int = QUIET_END_MIN_DEFAULT,
    ): Long {
        val t = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMs), zone)
        if (inQuietHours(t.hour, t.minute, startMin, endMin)) return 0L
        return (nextQuietStart(nowMs, zone, startMin) - nowMs).coerceAtLeast(0L)
    }

    /**
     * 系统主动消息的完整准入链：冷却期 → 免打扰 → 每日上限 → 最小间隔。
     * 优先级即排列顺序：冷却期是用户明说，压过一切；免打扰只顺延不丢弃。
     */
    fun admitSystemProactive(
        nowMs: Long,
        cooldownUntil: Long,
        todayKey: String,
        sentCountToday: Int,
        lastSentAt: Long,
        zone: ZoneId,
        dailyCap: Int = DAILY_CAP,
        minIntervalMs: Long = MIN_INTERVAL_MS,
        quietStartMin: Int = QUIET_START_MIN_DEFAULT,
        quietEndMin: Int = QUIET_END_MIN_DEFAULT,
    ): GateDecision {
        if (cooldownUntil > nowMs) return GateDecision.Suppress("cooldown")
        val t = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMs), zone)
        if (inQuietHours(t.hour, t.minute, quietStartMin, quietEndMin)) {
            return GateDecision.Defer(nextQuietEnd(nowMs, zone, quietEndMin))
        }
        if (sentCountToday >= dailyCap) return GateDecision.Suppress("daily-cap")
        if (lastSentAt > 0 && nowMs - lastSentAt < minIntervalMs) return GateDecision.Suppress("min-interval")
        return GateDecision.Pass
    }

    /** 「别烦我」关键词命中判定 */
    fun matchesSilence(text: String): Boolean = SILENCE_REGEX.containsMatchIn(text)

    /**
     * 主动能力开关（场景预设的组合单位，id 与各能力宿主一一对应）。
     * 场景格子选中的是「一组能力 + 闲聊频率」，单个能力仍可在面板里手动微调。
     */
    enum class ProactiveFeature(val id: String) {
        /** 早晚安：按作息在免打扰结束/开始处各说一句（仪式层，不占闲聊额度） */
        RITUAL("ritual"),

        /** 日程简报：2 小时内的日程提前告知 */
        BRIEFING("briefing"),

        /** 关怀提醒：久走/久坐/低头的事件驱动关心 */
        CARE("care"),

        /** 主动回访：聊到的约定到点问进展 */
        FOLLOWUP("followup"),

        /** 空闲闲聊：安静久了主动搭话（唯一受闲聊频率档控制的层） */
        IDLE_CHAT("idle_chat"),

        /** 走走拍拍：行走中偶尔看一眼前方并解说 */
        VISION("vision");

        companion object {
            fun of(id: String?): ProactiveFeature? = entries.firstOrNull { it.id == id }
        }
    }

    /**
     * 闲聊频率档（只管「空闲闲聊」这一层）：仪式/记忆/关怀三层不占额度。
     * 每日闲聊额度 → 间隔 = 清醒窗口 / 额度（[greetingIntervalMs]），再除以响应率因子。
     */
    enum class ChatFrequency(val id: String, val dailyCap: Int) {
        SILENT("silent", 0),
        MODERATE("moderate", 3),
        OFTEN("often", 6),
        CONSTANT("constant", 12);

        companion object {
            fun of(id: String?): ChatFrequency = entries.firstOrNull { it.id == id } ?: SILENT
        }
    }

    /**
     * 陪伴场景（聊天输入栏「主动性」面板的格子单选，5 档）：
     * 每格是「一组能力 + 闲聊频率」的预设，副标题即对用户的体感承诺。
     * 用户手动改开关后回写到**当前场景**的记忆里，切走再切回自动恢复。
     * 用户显式创建的定时任务/提醒不受场景影响（闹钟语义，「设了不响」最伤信任）。
     *
     * @param features 该场景默认开启的能力
     * @param chatFreq 该场景默认的闲聊频率档
     * @param maxNudges 会话内沉默追击上限；0 = 不追
     */
    enum class Scene(
        val id: String,
        val features: Set<ProactiveFeature>,
        val chatFreq: ChatFrequency,
        val maxNudges: Int,
    ) {
        /** 勿扰：全关，不主动开口 */
        MUTE("mute", emptySet(), ChatFrequency.SILENT, 0),

        /** 日程助手：早安 + 日程简报 + 晚安，只办正事 */
        SCHEDULE(
            "schedule",
            setOf(ProactiveFeature.RITUAL, ProactiveFeature.BRIEFING),
            ChatFrequency.SILENT,
            0,
        ),

        /** 轻陪伴：再加关怀提醒与主动回访，关心作息与状态 */
        LIGHT(
            "light",
            setOf(
                ProactiveFeature.RITUAL,
                ProactiveFeature.BRIEFING,
                ProactiveFeature.CARE,
                ProactiveFeature.FOLLOWUP,
            ),
            ChatFrequency.SILENT,
            1,
        ),

        /** 朋友：再加空闲闲聊，有感而发地说两句 */
        FRIEND(
            "friend",
            setOf(
                ProactiveFeature.RITUAL,
                ProactiveFeature.BRIEFING,
                ProactiveFeature.CARE,
                ProactiveFeature.FOLLOWUP,
                ProactiveFeature.IDLE_CHAT,
            ),
            ChatFrequency.MODERATE,
            2,
        ),

        /** 全陪：闲聊更勤 + 走走拍拍，形影不离 */
        FULL(
            "full",
            ProactiveFeature.entries.toSet(),
            ChatFrequency.OFTEN,
            3,
        );

        companion object {
            fun of(id: String?): Scene = entries.firstOrNull { it.id == id } ?: LIGHT
        }
    }

    /** 同类主动消息最小间隔（毫秒，防同一功能连发） */
    const val MIN_INTERVAL_MS = 2 * 60 * 60 * 1000L

    /** 走走拍拍节奏（毫秒）：行走中每 15 分钟看一眼 */
    const val VISION_INTERVAL_MS = 15 * 60 * 1000L

    /** 关怀提醒阈值缩放（纯函数）：scale<=0 视为关闭（返回永不满阈值） */
    fun careLimitMs(baseMs: Long, scale: Float): Long =
        if (scale <= 0f) Long.MAX_VALUE else (baseMs * scale).toLong()

    /** 问候最小间隔（毫秒）：上限调得再高也不短于此，防轰炸 */
    const val MIN_GREETING_GAP_MS = 30 * 60 * 1000L

    /**
     * 空闲问候间隔 = 清醒窗口（免打扰结束 → 免打扰开始）/ 每日上限。
     * 这是「上限即频率」的核心换算：cap=1（窗口15h）→ 约每 15h 一次；
     * cap=3 → 约 5h；cap=8 → 约 1.9h；cap=20 → 45min（触底 30min clamp）。
     */
    fun greetingIntervalMs(cap: Int, quietStartMin: Int, quietEndMin: Int): Long {
        if (cap <= 0) return Long.MAX_VALUE
        val awakeMin = ((quietStartMin - quietEndMin) + 1440) % 1440
        val awakeMs = awakeMin * 60_000L
        return (awakeMs / cap).coerceIn(MIN_GREETING_GAP_MS, awakeMs)
    }

    /** 空闲问候阈值：超过该时长无用户输入才算「空闲」 */
    const val IDLE_THRESHOLD_MS = 4 * 60 * 60 * 1000L

    /** 空闲判定：有过真实交互且距今超过阈值 */
    fun idleDue(nowMs: Long, lastInteractionMs: Long, thresholdMs: Long = IDLE_THRESHOLD_MS): Boolean =
        lastInteractionMs > 0 && nowMs - lastInteractionMs >= thresholdMs

    /**
     * 亲密度 streak 推进：lastActiveDay 为空 → 1；等于今天 → 维持；恰好是昨天 → +1；断档 → 重开。
     * 日期键均为 yyyy-MM-dd 字符串（LocalDate.toString()）。
     */
    fun nextStreak(todayKey: String, lastActiveDayKey: String?, currentStreak: Int): Int = when {
        lastActiveDayKey == null -> 1
        lastActiveDayKey == todayKey -> currentStreak.coerceAtLeast(1)
        isConsecutiveDays(lastActiveDayKey, todayKey) -> currentStreak + 1
        else -> 1
    }

    /** lastDayKey 的下一天是否恰好是 todayKey（昨天→今天连续互动） */
    fun isConsecutiveDays(lastDayKey: String, todayKey: String): Boolean =
        runCatching { java.time.LocalDate.parse(lastDayKey).plusDays(1).toString() == todayKey }
            .getOrDefault(false)

    // ── 响应率自适应（B）：主动消息发出后用户的回应情况反向调节频率 ──

    /** 响应记录窗口：只看最近 N 条主动消息有没有得到回应 */
    const val RESPONSE_WINDOW = 8

    /** 主动消息发出后等待用户回应的超时：窗口内开口 = 有回应，超时未回应 = 无回应 */
    const val RESPONSE_WAIT_MS = 10 * 60 * 1000L

    /** 连续未响应惩罚时长：连着 3 条主动消息石沉大海 → 追加独立冷却（非「别烦我」语义） */
    const val RESPONSE_PENALTY_MS = 2 * 60 * 60 * 1000L

    /**
     * 响应率自适应因子：r = 最近响应率，factor = 0.5 + 1.5r。
     * 全响应（r=1）→ 2.0（间隔÷2，跟得紧）；全忽略（r=0）→ 0.5（间隔×2，学会收敛）；
     * 无记录 → 1.0（保持档位基线）。应用：问候间隔 ÷ factor。
     */
    fun responseFactor(recent: List<Boolean>): Double {
        if (recent.isEmpty()) return 1.0
        val r = recent.count { it }.toDouble() / recent.size
        return 0.5 + 1.5 * r
    }

    /**
     * 连续未响应惩罚：最近 3 条主动消息全部无回应 → 追加 [RESPONSE_PENALTY_MS] 冷却。
     * 中间有任何一条得到回应就不罚（响应率因子已拉长间隔，双罚过度）。
     */
    fun responsePenaltyMs(recent: List<Boolean>): Long {
        if (recent.size < 3) return 0L
        return if (recent.takeLast(3).none { it }) RESPONSE_PENALTY_MS else 0L
    }

    /**
     * 时机内容绑定（C）的跳过信号：模型判定「这个时刻没值得说的」时只回 [SKIP]
     * （容忍大小写、首尾空白与句读），此时调用方静默收回、不播报不计数。
     */
    fun isSkipReply(reply: String): Boolean =
        reply.replace(Regex("[\\s。.!！～~]"), "").equals("[SKIP]", ignoreCase = true)

    /**
     * 移动判定（走走拍拍门控）：窗口内加速度模长围绕均值的 RMS 波动 ≥ threshold 判为行走/游览中。
     * 静止时模长恒在 ~9.8（RMS < 0.3），行走时抖动明显（RMS 1~3）；样本不足一律判静止（宁可不拍）。
     */
    fun isMoving(accelMagnitudes: List<Float>, threshold: Float = 0.6f): Boolean {
        if (accelMagnitudes.size < 5) return false
        val mean = accelMagnitudes.map { it.toDouble() }.average().toFloat()
        val rms = kotlin.math.sqrt(
            accelMagnitudes.fold(0f) { s, v -> val d = v - mean; s + d * d } / accelMagnitudes.size,
        )
        return rms >= threshold
    }
}

/**
 * 亲密度快照：注入 system prompt 的关系背景数据（陪伴天数 streak + 当日互动次数）。
 */
data class RelationSnapshot(val streakDays: Int, val interactionsToday: Int)

/**
 * 决策门（Application 级单例，[get] 惰性创建）。
 *
 * Android 壳：prefs 持久化 + 时区取值，判定全部委托 [ProactiveGatePolicy]。
 *
 * 现有接入点：
 *  - [onScheduledAgentFire]：TimerScheduler.fireTask 中自主任务（AgentPrompt）到点时——
 *    免打扰时段**顺延**到时段结束再跑（内容不丢，只是不半夜发声）；冷却期内**跳过**本次但照常推进链路；
 *    用户自己定的普通提醒（TtsSpeak/闹钟语义）**不过门**——「以为设了提醒其实没响」最伤信任；
 *  - [onUserText]：对话链路入口的关键词钩子，用户说「别烦我/别主动」→ 写入 24h 冷却；
 *  - [admitProactive]：后续系统主动触发（空闲问候、记忆回访、每日简报）的统一入口，过链后自动计数。
 */
class ProactiveGate private constructor(context: Context) {
    companion object {
        private const val TAG = "ProactiveGate"
        private const val PREFS = "proactive_gate"
        private const val K_COOLDOWN = "cooldown_until"
        private const val K_LAST_INTERACT = "last_interaction"
        private const val K_FOLLOWUP = "followup_enabled"
        private const val K_REL_LAST_DAY = "relation_last_day"
        private const val K_REL_STREAK = "relation_streak"
        private const val K_REL_COUNT_DATE = "relation_count_date"
        private const val K_REL_COUNT = "relation_count"
        private const val K_LEVEL = "proactivity_level" // 旧档位 id，仅用于迁移
        private const val K_CAP_OVERRIDE = "daily_cap_override" // 旧自定义上限，仅用于迁移
        private const val K_SCENE = "scene_id"
        private const val K_SCENE_FEAT = "scene_features_"
        private const val K_SCENE_FREQ = "scene_freq_"
        private const val K_QUIET_START = "quiet_start_min"
        private const val K_QUIET_END = "quiet_end_min"
        private const val K_SENT_ALL_DATE = "sent_all_date"
        private const val K_SENT_ALL_COUNT = "sent_all_count"
        private const val K_LAST_SENT = "sent_last_"
        private const val K_SENT_DATE = "sent_date_"
        private const val K_SENT_COUNT = "sent_count_"
        private const val KIND_AGENT_TASK = "agent_task"
        private const val K_CHAT_COMPANION = "chat_companion_enabled"
        private const val K_NUDGE_COUNT = "companion_nudge_count"
        private const val K_DRY_STREAK = "companion_dry_streak"
        private const val K_RESP_PENDING = "resp_pending_at"
        private const val K_RESP_RECENT = "resp_recent"
        private const val K_RESP_PENALTY = "resp_penalty_until"
        private const val K_SENT_PREV = "sent_prev_"
        private const val K_INTERACT_PREV = "interact_prev"

        @Volatile
        private var instance: ProactiveGate? = null

        /**
         * 「主动回访」准则开关的静态镜像：system prompt 装配（OpenAiService）拿不到
         * Context，构造时从 prefs 初始化、[setFollowupEnabled] 时同步更新。
         * App 启动即构造本单例（LabApplication.onCreate → IdleGreeter.start），时序安全。
         */
        @Volatile
        private var followupEnabledCache: Boolean = true

        fun isFollowupEnabled(): Boolean = followupEnabledCache

        /** 「话题枯竭」提示的静态镜像（OpenAiService 无 Context 读取）；消费型，取走即清零 */
        @Volatile
        private var dryStreakHintCache: Boolean = false

        fun takeDryStreakHint(): Boolean {
            if (!dryStreakHintCache) return false
            dryStreakHintCache = false
            return true
        }

        /** 亲密度快照静态镜像（OpenAiService 无 Context 可读；null = 尚无任何真实交互记录） */
        @Volatile
        private var relationCache: RelationSnapshot? = null

        fun relationSnapshot(): RelationSnapshot? = relationCache

        fun get(context: Context): ProactiveGate =
            instance ?: synchronized(this) {
                instance ?: ProactiveGate(context.applicationContext).also { instance = it }
            }
    }

    private val appContext: Context = context.applicationContext
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val zone: ZoneId = ZoneId.systemDefault()
    private val dateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US)

    init {
        migrateLegacyLevel()
        followupEnabledCache = prefs.getBoolean(K_FOLLOWUP, true)
        val lastDay = prefs.getString(K_REL_LAST_DAY, null)
        if (lastDay != null) {
            relationCache = RelationSnapshot(
                streakDays = prefs.getInt(K_REL_STREAK, 0),
                interactionsToday = todayInteractionCount(),
            )
        }
    }

    /** 「主动回访」开关（设置页）：关闭后 system prompt 不再注入回访准则（软控制，工具本身仍可用） */
    fun setFollowupEnabled(on: Boolean) {
        prefs.edit().putBoolean(K_FOLLOWUP, on).apply()
        followupEnabledCache = on
        Log.i(TAG, "followup guideline ${if (on) "enabled" else "disabled"}")
    }

    // ── 陪伴场景（5 格单选）与闲聊频率档 ──

    /** 当前陪伴场景，默认「轻陪伴」 */
    fun scene(): ProactiveGatePolicy.Scene =
        ProactiveGatePolicy.Scene.of(prefs.getString(K_SCENE, null))

    /** 是否处于「勿扰」场景（面板与运行时共用的总闸判断） */
    fun isMuted(): Boolean = scene() == ProactiveGatePolicy.Scene.MUTE

    /**
     * 某场景记住的能力组合：用户手动微调过就是那份微调，否则用场景预设。
     * 每场景各存一份 —— 切走再切回自动恢复，不被别的场景的调整污染。
     */
    fun sceneFeatures(sceneId: String): Set<ProactiveGatePolicy.ProactiveFeature> {
        val preset = ProactiveGatePolicy.Scene.of(sceneId).features
        val raw = prefs.getStringSet(K_SCENE_FEAT + sceneId, null) ?: return preset
        return raw.mapNotNull { ProactiveGatePolicy.ProactiveFeature.of(it) }.toSet()
    }

    /** 某场景记住的闲聊频率档（没存过就用该场景预设） */
    fun sceneChatFreq(sceneId: String): ProactiveGatePolicy.ChatFrequency {
        val key = K_SCENE_FREQ + sceneId
        if (!prefs.contains(key)) return ProactiveGatePolicy.Scene.of(sceneId).chatFreq
        return ProactiveGatePolicy.ChatFrequency.of(prefs.getString(key, null))
    }

    /** 当前场景的闲聊频率档 */
    fun chatFreq(): ProactiveGatePolicy.ChatFrequency = sceneChatFreq(scene().id)

    /** 切换闲聊频率档（写当前场景的记忆，不动其他场景） */
    fun setChatFreq(freqId: String) {
        val id = scene().id
        prefs.edit().putString(K_SCENE_FREQ + id, freqId).apply()
        Log.i(TAG, "chat freq -> $freqId (scene=$id)")
    }

    /** 手动微调某个能力：写宿主开关 + 回写当前场景记忆 */
    fun setFeature(feature: ProactiveGatePolicy.ProactiveFeature, on: Boolean) {
        val id = scene().id
        val next = sceneFeatures(id).toMutableSet().apply { if (on) add(feature) else remove(feature) }
        prefs.edit().putStringSet(K_SCENE_FEAT + id, next.map { it.id }.toSet()).apply()
        applyFeature(feature, on)
        Log.i(TAG, "feature ${feature.id} -> $on (scene=$id)")
    }

    /**
     * 切场景：记下当前场景，并把该场景记住的能力组合铺到各宿主开关上。
     * 不碰闲聊频率以外的任何额度：仪式/记忆/关怀三层照旧不占闲聊额度。
     */
    fun applyScene(scene: ProactiveGatePolicy.Scene = scene()) {
        prefs.edit().putString(K_SCENE, scene.id).apply()
        val feats = sceneFeatures(scene.id)
        ProactiveGatePolicy.ProactiveFeature.entries.forEach { applyFeature(it, it in feats) }
        Log.i(TAG, "scene -> ${scene.id}, features=${feats.map { it.id }}")
    }

    /** 能力当前是否开启（读宿主开关，唯一事实来源） */
    fun isFeatureOn(feature: ProactiveGatePolicy.ProactiveFeature): Boolean = when (feature) {
        ProactiveGatePolicy.ProactiveFeature.RITUAL -> RitualGreeting.get(appContext).isEnabled()
        ProactiveGatePolicy.ProactiveFeature.BRIEFING -> CalendarBriefing.get(appContext).isEnabled()
        ProactiveGatePolicy.ProactiveFeature.CARE -> CareReminder.get(appContext).isEnabled()
        ProactiveGatePolicy.ProactiveFeature.FOLLOWUP -> isFollowupEnabled()
        ProactiveGatePolicy.ProactiveFeature.IDLE_CHAT -> IdleGreeter.get(appContext).isEnabled()
        ProactiveGatePolicy.ProactiveFeature.VISION -> AmbientVisionController.get(appContext).isEnabled()
    }

    /** 一个能力 → 它的宿主开关 */
    private fun applyFeature(feature: ProactiveGatePolicy.ProactiveFeature, on: Boolean) {
        when (feature) {
            ProactiveGatePolicy.ProactiveFeature.RITUAL -> RitualGreeting.get(appContext).setEnabled(on)
            ProactiveGatePolicy.ProactiveFeature.BRIEFING -> CalendarBriefing.get(appContext).setEnabled(on)
            ProactiveGatePolicy.ProactiveFeature.CARE -> CareReminder.get(appContext).setEnabled(on)
            ProactiveGatePolicy.ProactiveFeature.FOLLOWUP -> setFollowupEnabled(on)
            ProactiveGatePolicy.ProactiveFeature.IDLE_CHAT -> IdleGreeter.get(appContext).setEnabled(on)
            ProactiveGatePolicy.ProactiveFeature.VISION -> AmbientVisionController.get(appContext).setEnabled(on)
        }
    }

    /** 会话内沉默追击上限（随场景） */
    fun maxNudges(): Int = scene().maxNudges

    /** 空闲闲聊间隔：清醒窗口 / 闲聊额度 ÷ 响应率因子，受 30 分钟下限保护 */
    fun greetingIntervalMs(): Long {
        val cap = chatFreq().dailyCap
        if (cap <= 0) return Long.MAX_VALUE
        val base = ProactiveGatePolicy.greetingIntervalMs(cap, quietStartMin(), quietEndMin())
        return (base / responseFactor()).toLong().coerceAtLeast(ProactiveGatePolicy.MIN_GREETING_GAP_MS)
    }

    /**
     * 今天还能主动开口几条 = min(闲聊剩余额度, 剩余清醒时间 ÷ 当前闲聊间隔)。
     * 剩余清醒时间不够就顺延明早 —— 头卡说「还可发 X 条」必须是真能发出去的数。
     */
    fun remainingToday(nowMs: Long = System.currentTimeMillis()): Int {
        val left = (chatFreq().dailyCap - sentTodayCount()).coerceAtLeast(0)
        if (left == 0) return 0
        val iv = greetingIntervalMs()
        if (iv <= 0L || iv == Long.MAX_VALUE) return 0
        val awake = ProactiveGatePolicy.awakeRemainingMs(nowMs, zone, quietStartMin(), quietEndMin())
        val byTime = ((awake + iv - 1) / iv).toInt()
        return minOf(left, byTime)
    }

    /** 旧 6 档 → 新场景的一次性迁移（旧自定义上限折算成闲聊频率档） */
    private fun migrateLegacyLevel() {
        if (prefs.contains(K_SCENE)) return
        val legacy = prefs.getString(K_LEVEL, null)
        val scene = when (legacy) {
            "off" -> ProactiveGatePolicy.Scene.MUTE
            "active" -> ProactiveGatePolicy.Scene.FRIEND
            "eager" -> ProactiveGatePolicy.Scene.FULL
            else -> ProactiveGatePolicy.Scene.LIGHT
        }
        val editor = prefs.edit().putString(K_SCENE, scene.id)
        val legacyCap = prefs.getInt(K_CAP_OVERRIDE, -1)
        if (legacyCap > 0) {
            editor.putString(
                K_SCENE_FREQ + scene.id,
                when {
                    legacyCap <= 4 -> ProactiveGatePolicy.ChatFrequency.MODERATE.id
                    legacyCap <= 8 -> ProactiveGatePolicy.ChatFrequency.OFTEN.id
                    else -> ProactiveGatePolicy.ChatFrequency.CONSTANT.id
                },
            )
        }
        editor.apply()
        Log.i(TAG, "migrated legacy level=$legacy -> scene=${scene.id}")
    }

    /** 今天已发送的主动消息总数（全局共享池，跨日归零），供面板预览行显示 */
    fun sentTodayCount(): Int =
        if (prefs.getString(K_SENT_ALL_DATE, null) == LocalDate.now(zone).format(dateFmt)) {
            prefs.getInt(K_SENT_ALL_COUNT, 0)
        } else {
            0
        }

    // ── 免打扰时段（分钟粒度，跨午夜支持） ──

    fun quietStartMin(): Int = prefs.getInt(K_QUIET_START, ProactiveGatePolicy.QUIET_START_MIN_DEFAULT)

    fun quietEndMin(): Int = prefs.getInt(K_QUIET_END, ProactiveGatePolicy.QUIET_END_MIN_DEFAULT)

    fun setQuietHours(startMin: Int, endMin: Int) {
        prefs.edit()
            .putInt(K_QUIET_START, startMin.coerceIn(0, 23 * 60 + 59))
            .putInt(K_QUIET_END, endMin.coerceIn(0, 24 * 60))
            .apply()
        Log.i(TAG, "quiet hours -> %02d:%02d-%02d:%02d".format(startMin / 60, startMin % 60, endMin / 60, endMin % 60))
    }

    /**
     * 自主任务（用户创建的 AgentPrompt）到点判定：只看冷却期 + 免打扰。
     * 刻意**不做**每日上限/最小间隔——那是防系统骚扰的，套在用户自己定的任务上等于「设了不响」。
     */
    fun onScheduledAgentFire(nowMs: Long = System.currentTimeMillis()): GateDecision {
        if (prefs.getLong(K_COOLDOWN, 0L) > nowMs) {
            return GateDecision.Suppress("cooldown")
        }
        val t = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMs), zone)
        if (ProactiveGatePolicy.inQuietHours(t.hour, t.minute, quietStartMin(), quietEndMin())) {
            return GateDecision.Defer(ProactiveGatePolicy.nextQuietEnd(nowMs, zone, quietEndMin()))
        }
        return GateDecision.Pass
    }

    /**
     * **闲聊层**准入（唯一计入闲聊额度的主动消息，目前只有空闲搭话）。
     * 判定为 Pass 时当场记录发送时刻与当日计数（admit = 通过并记账，调用方无需再调 record）。
     *
     * @param kind 主动消息类别（如 "idle_greeting"），同类最小间隔按 kind 隔离
     */
    fun admitProactive(kind: String, nowMs: Long = System.currentTimeMillis()): GateDecision {
        if (isMuted()) {
            Log.i(TAG, "admitProactive kind=$kind -> Suppress (scene mute)")
            return GateDecision.Suppress("scene-mute")
        }
        if (prefs.getLong(K_RESP_PENALTY, 0L) > nowMs) {
            Log.i(TAG, "admitProactive kind=$kind -> Suppress (response-penalty)")
            return GateDecision.Suppress("response-penalty")
        }
        val today = LocalDate.now(zone).format(dateFmt)
        // 额度只算闲聊层：仪式/记忆/关怀三层各有自己的触发逻辑，不挤占闲聊名额。
        val allCount = if (prefs.getString(K_SENT_ALL_DATE, null) == today) {
            prefs.getInt(K_SENT_ALL_COUNT, 0)
        } else {
            0
        }
        val decision = ProactiveGatePolicy.admitSystemProactive(
            nowMs = nowMs,
            cooldownUntil = prefs.getLong(K_COOLDOWN, 0L),
            todayKey = today,
            sentCountToday = allCount,
            lastSentAt = prefs.getLong(K_LAST_SENT + kind, 0L),
            zone = zone,
            dailyCap = chatFreq().dailyCap,
            minIntervalMs = ProactiveGatePolicy.MIN_INTERVAL_MS,
            quietStartMin = quietStartMin(),
            quietEndMin = quietEndMin(),
        )
        if (decision is GateDecision.Pass) {
            recordSent(kind, nowMs, today)
            armResponsePending(nowMs)
        }
        Log.i(TAG, "admitProactive kind=$kind -> ${decision::class.simpleName}")
        return decision
    }

    /**
     * **不计入闲聊额度**的主动消息准入（日程简报等）：只查冷却/响应收敛/免打扰/同类最小间隔，
     * 通过后只刷新该类别的发送时刻，不占每日额度、不进响应率窗口。
     */
    fun admitFreeProactive(kind: String, nowMs: Long = System.currentTimeMillis()): GateDecision {
        if (isMuted()) return GateDecision.Suppress("scene-mute")
        if (prefs.getLong(K_COOLDOWN, 0L) > nowMs) return GateDecision.Suppress("cooldown")
        if (prefs.getLong(K_RESP_PENALTY, 0L) > nowMs) return GateDecision.Suppress("response-penalty")
        val t = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMs), zone)
        if (ProactiveGatePolicy.inQuietHours(t.hour, t.minute, quietStartMin(), quietEndMin())) {
            return GateDecision.Defer(ProactiveGatePolicy.nextQuietEnd(nowMs, zone, quietEndMin()))
        }
        val last = prefs.getLong(K_LAST_SENT + kind, 0L)
        if (last > 0L && nowMs - last < ProactiveGatePolicy.MIN_INTERVAL_MS) {
            return GateDecision.Suppress("min-interval")
        }
        prefs.edit().putLong(K_LAST_SENT + kind, nowMs).apply()
        Log.i(TAG, "admitFreeProactive kind=$kind -> Pass")
        return GateDecision.Pass
    }

    /**
     * 对话链路钩子：用户文本命中「别烦我」句式 → 设置 24h 冷却期。
     * 冷却期间自主任务跳过、系统主动消息压制；只写 prefs 与日志，不打扰对话本身。
     */
    fun onUserText(text: String, nowMs: Long = System.currentTimeMillis()) {
        // 响应率结算：窗口内的 pending 主动消息记「有回应」，窗口外的惰性记「无回应」
        val pendingAt = prefs.getLong(K_RESP_PENDING, 0L)
        if (pendingAt > 0L) {
            settleResponse(responded = nowMs - pendingAt <= ProactiveGatePolicy.RESPONSE_WAIT_MS, nowMs)
        }
        // 每次真实用户输入都刷新活跃时刻（空闲检测时间基准；调用方已滤掉 Agent 自发轮次）
        prefs.edit().putLong(K_LAST_INTERACT, nowMs).apply()
        // 用户重新开口 → 追击名额重置；敷衍回复累计话题枯竭计数
        if (prefs.getInt(K_NUDGE_COUNT, 0) > 0) prefs.edit().putInt(K_NUDGE_COUNT, 0).apply()
        recordDryness(text)
        // 亲密度：streak + 当日互动计数（同步维护静态镜像供 prompt 装配读取）
        recordRelation(nowMs)
        // 沉默追击检查点：用户每次开口后挂一次性精确闹钟（T+3min）；
        // 冷却中会命中 scheduleCheck 的预判而不挂，连续开口经同 requestCode 自动重排
        CompanionNudge.scheduleCheck(appContext, nowMs)
        if (!ProactiveGatePolicy.matchesSilence(text)) return
        val until = nowMs + ProactiveGatePolicy.COOLDOWN_MS
        prefs.edit().putLong(K_COOLDOWN, until).apply()
        Log.i(TAG, "silence requested, cooldown until $until")
    }

    /** 冷却剩余毫秒（0 = 无冷却），供设置页/调试侧查询 */
    fun cooldownRemaining(nowMs: Long = System.currentTimeMillis()): Long =
        (prefs.getLong(K_COOLDOWN, 0L) - nowMs).coerceAtLeast(0L)

    /** 最近一次真实用户交互时刻（0 = 尚无记录），空闲检测的时间基准 */
    fun lastInteraction(): Long = prefs.getLong(K_LAST_INTERACT, 0L)

    // ---------------- 对话内陪伴（主动性 #A）----------------

    @Volatile
    private var chatCompanionCache: Boolean = prefs.getBoolean(K_CHAT_COMPANION, true)

    fun isChatCompanionEnabled(): Boolean = chatCompanionCache

    fun setChatCompanionEnabled(on: Boolean) {
        prefs.edit().putBoolean(K_CHAT_COMPANION, on).apply()
        chatCompanionCache = on
        Log.i(TAG, "chat companion ${if (on) "enabled" else "disabled"}")
    }

    /** 本轮会话已追击次数（用户重新开口即清零） */
    fun nudgeCount(): Int = prefs.getInt(K_NUDGE_COUNT, 0)

    /** 占用一个追击名额 */
    fun consumeNudgeSlot(nowMs: Long) {
        prefs.edit().putInt(K_NUDGE_COUNT, nudgeCount() + 1).putLong(K_LAST_INTERACT, nowMs).apply()
    }

    /**
     * 话题枯竭计数：在 [onUserText] 里按敷衍回复累计；生成侧经
     * `ProactiveGate.takeDryStreakHint()`（companion 静态镜像）消费——命中时模型这轮
     * 主动换话题/追问，下一轮恢复正常语气。
     */
    fun recordDryness(text: String) {
        val dry = CompanionNudge.dryReply(text)
        if (dry != true) {
            if (prefs.getInt(K_DRY_STREAK, 0) > 0) prefs.edit().putInt(K_DRY_STREAK, 0).apply()
            dryStreakHintCache = false
            return
        }
        val streak = prefs.getInt(K_DRY_STREAK, 0) + 1
        prefs.edit().putInt(K_DRY_STREAK, streak).apply()
        if (streak >= CompanionNudge.DRY_STREAK_THRESHOLD) {
            dryStreakHintCache = true
            Log.i(TAG, "topic dry (streak=$streak), hint armed")
        }
    }

    /**
     * 「走走拍拍」准入：只查静默冷却与免打扰——频次由会话自身 15 分钟节流管，
     * **不占** [admitProactive] 的闲聊额度。
     */
    fun ambientVisionAllowed(nowMs: Long = System.currentTimeMillis()): Boolean {
        if (isMuted()) return false
        if (prefs.getLong(K_COOLDOWN, 0) > nowMs) return false
        val t = java.time.Instant.ofEpochMilli(nowMs).atZone(zone)
        return !ProactiveGatePolicy.inQuietHours(t.hour, t.minute, quietStartMin(), quietEndMin())
    }

    /**
     * 「仪式层」（早晚安）准入：只查总闸与冷却——早安/晚安是作息锚点，**不占**闲聊额度。
     */
    fun ritualAllowed(nowMs: Long = System.currentTimeMillis()): Boolean =
        !isMuted() &&
            prefs.getLong(K_COOLDOWN, 0L) <= nowMs &&
            prefs.getLong(K_RESP_PENALTY, 0L) <= nowMs

    private fun recordSent(kind: String, nowMs: Long, today: String) {
        prefs.edit()
            .putLong(K_SENT_PREV + kind, prefs.getLong(K_LAST_SENT + kind, 0L))
            .putLong(K_LAST_SENT + kind, nowMs)
            .putString(K_SENT_DATE + kind, today)
            .putInt(K_SENT_COUNT + kind, prefs.getInt(K_SENT_COUNT + kind, 0) + 1)
            // 全局池：档位 cap 的唯一事实来源
            .putString(K_SENT_ALL_DATE, today)
            .putInt(K_SENT_ALL_COUNT, prefs.getInt(K_SENT_ALL_COUNT, 0) + 1)
            // 主动接触也刷新活跃基准：问候发出后，下一条问候的沉默计时从这条起算
            // （否则 30 分钟后下个检查点仍满足旧阈值，会连着问候，全靠 min-interval 兜底）
            .putLong(K_INTERACT_PREV, prefs.getLong(K_LAST_INTERACT, 0L))
            .putLong(K_LAST_INTERACT, nowMs)
            .apply()
    }

    // ── 响应率自适应（B）──

    /**
     * 主动消息实际发出后登记「待回应」pending：用户在
     * [ProactiveGatePolicy.RESPONSE_WAIT_MS] 内开口即算有回应。
     * 主动消息本身被 min-interval 隔开，同一时刻至多一条 pending。
     */
    private fun armResponsePending(nowMs: Long) {
        prefs.edit().putLong(K_RESP_PENDING, nowMs).apply()
    }

    /**
     * 结算 pending 并滚动写入响应记录（最近 N 条 0/1）。
     * 结算为「无回应」时，若最近 3 条全没回应 → 写入 2h 惩罚冷却（独立于「别烦我」）。
     */
    private fun settleResponse(responded: Boolean, nowMs: Long) {
        if (prefs.getLong(K_RESP_PENDING, 0L) <= 0L) return
        prefs.edit().putLong(K_RESP_PENDING, 0L).apply()
        val recent = responseRecent() + responded
        val kept = recent.takeLast(ProactiveGatePolicy.RESPONSE_WINDOW)
        prefs.edit().putString(K_RESP_RECENT, kept.joinToString("") { if (it) "1" else "0" }).apply()
        if (!responded) {
            val penalty = ProactiveGatePolicy.responsePenaltyMs(kept)
            if (penalty > 0L) {
                prefs.edit().putLong(K_RESP_PENALTY, nowMs + penalty).apply()
                Log.i(TAG, "3 consecutive missed responses, penalty until ${nowMs + penalty}")
            }
        }
        Log.i(TAG, "response settled: responded=$responded recent=${kept.joinToString("") { if (it) "1" else "0" }}")
    }

    /**
     * 自检链惰性结算：窗口已过的 pending 记「无回应」；窗口内的保持 pending，
     * 等用户在窗口内开口（[onUserText]）再结算。
     */
    fun settleExpiredResponse(nowMs: Long = System.currentTimeMillis()) {
        val pendingAt = prefs.getLong(K_RESP_PENDING, 0L)
        if (pendingAt > 0L && nowMs - pendingAt > ProactiveGatePolicy.RESPONSE_WAIT_MS) {
            settleResponse(responded = false, nowMs)
        }
    }

    /** 最近响应记录（0/1，旧的在前） */
    fun responseRecent(): List<Boolean> =
        (prefs.getString(K_RESP_RECENT, null) ?: "").map { it == '1' }

    /** 响应率自适应因子（1.0 = 无记录，保持档位基线） */
    fun responseFactor(): Double = ProactiveGatePolicy.responseFactor(responseRecent())

    /** 响应惩罚冷却剩余（0 = 无惩罚），独立于「别烦我」冷却的系统自我收敛 */
    fun responsePenaltyRemaining(nowMs: Long = System.currentTimeMillis()): Long =
        (prefs.getLong(K_RESP_PENALTY, 0L) - nowMs).coerceAtLeast(0L)

    /**
     * 主动消息「说了但收回」的记账回退：模型判定这个时机不值得开口（[SKIP]），
     * 把 admitProactive 已记的发送计数/间隔基准/活跃基准回退，pending 一并撤销——
     * 时机不对不该消耗每日上限，不该挤占响应率窗口，也让下一轮自检能重新尝试。
     */
    fun refundProactive(kind: String) {
        val today = LocalDate.now(zone).format(dateFmt)
        prefs.edit().apply {
            if (prefs.getString(K_SENT_ALL_DATE, null) == today) {
                putInt(K_SENT_ALL_COUNT, (prefs.getInt(K_SENT_ALL_COUNT, 0) - 1).coerceAtLeast(0))
            }
            if (prefs.getString(K_SENT_DATE + kind, null) == today) {
                putInt(K_SENT_COUNT + kind, (prefs.getInt(K_SENT_COUNT + kind, 0) - 1).coerceAtLeast(0))
            }
            putLong(K_LAST_SENT + kind, prefs.getLong(K_SENT_PREV + kind, 0L))
            putLong(K_LAST_INTERACT, prefs.getLong(K_INTERACT_PREV, 0L))
            putLong(K_RESP_PENDING, 0L)
        }.apply()
        Log.i(TAG, "refundProactive kind=$kind (skip)")
    }

    // ── 亲密度（streak + 当日互动计数） ──

    private fun recordRelation(nowMs: Long) {
        val today = LocalDate.now(zone).format(dateFmt)
        val streak = ProactiveGatePolicy.nextStreak(
            todayKey = today,
            lastActiveDayKey = prefs.getString(K_REL_LAST_DAY, null),
            currentStreak = prefs.getInt(K_REL_STREAK, 0),
        )
        val countToday = todayInteractionCount() + 1
        prefs.edit()
            .putString(K_REL_LAST_DAY, today)
            .putInt(K_REL_STREAK, streak)
            .putString(K_REL_COUNT_DATE, today)
            .putInt(K_REL_COUNT, countToday)
            .apply()
        relationCache = RelationSnapshot(streak, countToday)
    }

    private fun todayInteractionCount(): Int =
        if (prefs.getString(K_REL_COUNT_DATE, null) == LocalDate.now(zone).format(dateFmt)) {
            prefs.getInt(K_REL_COUNT, 0)
        } else {
            0
        }
}
