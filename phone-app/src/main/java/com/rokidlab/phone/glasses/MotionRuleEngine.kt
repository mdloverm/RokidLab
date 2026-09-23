package com.rokidlab.phone.glasses

import android.content.Context
import android.util.Log
import com.rokidlab.phone.ai.GlassToolConfirmChannel
import com.rokidlab.phone.ai.ToolRegistry
import com.rokidlab.phone.ai.approval.ApprovalGate
import com.rokidlab.phone.ai.approval.ToolDecision
import com.rokidlab.phone.ai.approval.ToolSource
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * v2 规则编程层 —— 头动规则引擎（v2a/v2b/v2c 汇聚点）。
 *
 * 职责：
 *  - **规则管理**：[rules] / [upsert] / [remove]，SharedPreferences JSON 持久化（上限 [MAX_RULES] 条）；
 *  - **调度**：单线程 daemon 定时器每 [TICK_MS] 拉一次 [MotionBuffer] 增量窗口做判定
 *    （刻意不在 CXR binder 线程判定 —— 阻塞它会卡死整条自定义指令通道）；
 *  - **执行**：命中规则后把 [MotionRule.tool] + [MotionRule.args] 当一次**普通工具调用**发出
 *    —— 手机端任意工具（含 MCP）都能当规则目标，安全边界不在规则层：
 *    执行前一律过 [ApprovalGate]（未知名拒绝、限流、外部副作用弹眼镜端确认），
 *    与对话路径、AIUI 页面路径共用同一条闸门；
 *  - **边沿触发**：状态型动作（still/pitch_over）只在「刚进入命中」的那一次执行（[MotionEdgeGate]），
 *    否则「保持静止就播报时间」会每 [COOLDOWN_MS] 播一次，用户根本停不下来；
 *  - **v2c 审批手势**：眼镜端确认弹窗等待期间（[GlassToolConfirmChannel.approvalPending]），
 *    点头=确认、摇头=取消（阈值 [APPROVAL_GESTURE_THRESHOLD_DEG] 高于默认动作阈值，降低误触）。
 *    审批期间**跳过**用户规则评估 —— 同一动作不能既执行规则又解除审批。
 *
 * 生命周期：会话就绪（onOpenAppResult）时 [start]、cleanup/断连时 [stop]；
 * 数据流断（缓冲陈旧 > [DATA_STALE_MS]）时 tick 直接空转，不产生任何动作。
 */
object MotionRuleEngine {
    private const val TAG = "MotionRuleEngine"
    private const val PREFS = "motion_rule_prefs"
    private const val KEY_RULES = "rules_v1"

    /** 规则上限：头动场景高频规则没有意义，也防 AI 生成失控 */
    const val MAX_RULES = 5

    private const val TICK_MS = 120L
    /** 同一规则触发冷却：一次往复动作在窗口滑动期间会被连续判中，靠冷却收敛成单次 */
    private const val COOLDOWN_MS = 3_000L
    /** 每次评估拉取的缓冲窗口（覆盖规则允许的最大 windowMs/durationMs） */
    private const val MAX_EVAL_WINDOW_MS = 12_000L
    /** 数据流陈旧判定：超过即认为眼镜端流断，空转等待 */
    private const val DATA_STALE_MS = 2_000L

    // ── v2c 审批手势参数（刻意比用户动作默认阈值更严，防误确认）──
    private const val APPROVAL_GESTURE_WINDOW_MS = 2_500L
    private const val APPROVAL_GESTURE_THRESHOLD_DEG = 18f

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var cachedRules: List<MotionRule>? = null

    private val lastFired = ConcurrentHashMap<String, Long>()

    /** 边沿触发闸门（见类注释：状态型动作只在"刚进入命中"时执行一次） */
    private val edgeGate = MotionEdgeGate()

    /**
     * 规则动作执行线程。
     *
     * 单线程池（蓝牙/CXR 下行命令本就该串行）+ **必须另起线程**：
     * 审批闸门最多阻塞 ~40s 等用户确认（眼镜通道 35s / 手机通道 40s），工具本身还可能访问网络/ADB —— 放在 120ms 的
     * 判定线程上会直接把头动识别停掉（判定停 → 审批手势也一起失效，用户点了头也没反应）。
     */
    private val worker: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "motion-rule-exec").apply { isDaemon = true }
    }

    /**
     * 审批手势的生效起点（眼镜端采样时间轴，由触发时最新样本的 `t` 得到）。
     *
     * 为什么要挡住：弹窗出现时，**触发这条规则的那次点头/摇头往往还落在
     * [APPROVAL_GESTURE_WINDOW_MS] 的手势窗口内**（判定与弹窗只差一两帧）。
     * 不设起点的话，「点头 → 给谁打电话」会被同一次点头直接确认 —— 规则自己把自己的
     * 安全确认给点了。设起点后只认弹窗之后的**新手势**。
     * 用样本时间轴而不是手机时钟：两侧设备时钟不保证对齐，只有同一条时间轴上的比较才有意义。
     */
    @Volatile
    private var gestureArmedAfterT = 0L

    @Volatile
    private var scheduler: java.util.concurrent.ScheduledExecutorService? = null

    // ═══════════════════ 规则管理 ═══════════════════

    /** 引擎可用的 Context（幂等；工具层与会话生命周期都会传入） */
    fun init(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    /** 当前生效规则（内存缓存优先，冷启动读 prefs） */
    fun rules(): List<MotionRule> {
        cachedRules?.let { return it }
        val ctx = appContext ?: return emptyList()
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_RULES, null)
            ?: return emptyList()
        val list = runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { MotionRule.fromJson(arr.optJSONObject(it)) }
        }.onFailure { Log.w(TAG, "parse rules failed", it) }.getOrDefault(emptyList())
        cachedRules = list
        return list
    }

    /**
     * 新增或更新规则（按 [MotionRule.matchKey] 去重：同模式+同工具+同方向=替换）。
     * @return null = 成功；非 null = 给模型看的失败原因
     */
    fun upsert(rule: MotionRule): String? {
        val current = rules().toMutableList()
        val idx = current.indexOfFirst { it.matchKey == rule.matchKey }
        if (idx >= 0) current[idx] = rule else current.add(rule)
        val err = persist(current)
        if (err == null) {
            Log.i(TAG, "rule upserted: ${rule.name} (${rule.matchKey})")
            start() // 存在规则即确保引擎在跑（无眼镜时空转，代价可忽略）
        }
        return err
    }

    /** 按 ID 删除规则；@return 是否删除了规则 */
    fun remove(id: String): Boolean {
        val current = rules()
        val next = current.filterNot { it.id == id }
        if (next.size == current.size) return false
        return persist(next) == null
    }

    /** 清空全部规则；@return 成功与否 */
    fun removeAll(): Boolean = persist(emptyList()) == null

    private fun persist(list: List<MotionRule>): String? {
        val ctx = appContext ?: return "应用尚未初始化，无法保存规则"
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        val ok = runCatching {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_RULES, arr.toString()).commit()
        }.onFailure { Log.w(TAG, "persist rules failed", it) }.getOrDefault(false)
        if (!ok) return "规则保存失败，请稍后重试"
        cachedRules = list
        return null
    }

    // ═══════════════════ 调度 ═══════════════════

    /** 启动判定循环（幂等）。会话就绪 / 规则落盘时调用。 */
    fun start() {
        synchronized(this) {
            if (scheduler != null) return
            val ex = Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "motion-rule-engine").apply { isDaemon = true }
            }
            ex.scheduleWithFixedDelay(::tick, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS)
            scheduler = ex
            Log.i(TAG, "engine started")
        }
    }

    /** 停止判定循环并清空冷却/边沿状态。cleanup/断连时调用。 */
    fun stop() {
        synchronized(this) {
            scheduler?.shutdownNow()
            scheduler = null
        }
        lastFired.clear()
        // 边沿状态必须一起清：断连重连后如果沿用旧状态，"保持静止"这类规则会因为
        // 上一段会话里已经处于命中态而永远等不到新的边沿（表现为规则静默失效）
        edgeGate.clear()
        gestureArmedAfterT = 0L
    }

    /** 单条规则在当前采样序列上是否命中（internal 供单测直测判定分发） */
    internal fun evaluate(rule: MotionRule, samples: List<AiChannel.ImuSample>): Boolean =
        when (rule.pattern) {
            MotionPatterns.NOD -> MotionPatternDetector.countOscillations(
                samples, rule.windowMs, MotionAxes.PITCH, rule.thresholdDeg,
            ) >= rule.count
            MotionPatterns.SHAKE -> MotionPatternDetector.countOscillations(
                samples, rule.windowMs, MotionAxes.YAW, rule.thresholdDeg,
            ) >= rule.count
            MotionPatterns.STILL ->
                MotionPatternDetector.isStill(samples, rule.durationMs, rule.thresholdDeg)
            MotionPatterns.PITCH_OVER -> MotionPatternDetector.isPitchOver(
                samples, rule.durationMs, rule.thresholdDeg, rule.direction != "down",
            )
            else -> false
        }

    private fun tick() {
        try {
            if (MotionBuffer.newestAgeMs() > DATA_STALE_MS) return
            val samples = MotionBuffer.recent(MAX_EVAL_WINDOW_MS)
            if (samples.size < 8) return

            // v2c：审批等待期间手势优先接管（点头=确认、摇头=取消），用户规则暂停评估防误触
            val confirm = GlassToolConfirmChannel.global
            if (confirm.approvalPending()) {
                // 只认「触发规则之后」的新手势：触发本规则的那次点头/摇头此刻还可能在
                // 手势窗口内，不挡掉就等于规则自己把安全确认点了（见 gestureArmedAfterT）
                val gestureSamples = if (gestureArmedAfterT > 0L) {
                    samples.filter { it.t > gestureArmedAfterT }
                } else {
                    samples
                }
                val nod = MotionPatternDetector.countOscillations(
                    gestureSamples, APPROVAL_GESTURE_WINDOW_MS, MotionAxes.PITCH, APPROVAL_GESTURE_THRESHOLD_DEG,
                )
                val shake = MotionPatternDetector.countOscillations(
                    gestureSamples, APPROVAL_GESTURE_WINDOW_MS, MotionAxes.YAW, APPROVAL_GESTURE_THRESHOLD_DEG,
                )
                when {
                    nod >= 1 && nod > shake -> {
                        Log.i(TAG, "approval gesture: nod -> confirm")
                        confirm.resolveByMotion(true)
                    }
                    shake >= 1 && shake > nod -> {
                        Log.i(TAG, "approval gesture: shake -> deny")
                        confirm.resolveByMotion(false)
                    }
                }
                return
            }

            for (rule in rules()) {
                // 边沿触发：still / pitch_over 是**状态型**判定，用户不动、头还抬着时每一帧都命中，
                // 按"命中就执行"会让「保持静止就报时间」每 3 秒报一次（换成会出声的动作就是无限播报）。
                // 逐帧喂给闸门以维持状态，只有「未命中 → 命中」的那一次才继续往下走。
                if (!edgeGate.allow(rule.id, evaluate(rule, samples))) continue
                val last = lastFired[rule.id] ?: 0L
                if (System.currentTimeMillis() - last < COOLDOWN_MS) continue
                fire(rule)
            }
        } catch (t: Throwable) {
            // tick 线程绝不允许带异常退出（scheduleWithFixedDelay 的任务抛异常会静默终止整个调度）
            Log.w(TAG, "tick failed", t)
        }
    }

    // ═══════════════════ 动作执行 ═══════════════════

    /**
     * 执行规则动作：把 [MotionRule.tool] + [MotionRule.args] 当作一次**普通工具调用**发出。
     *
     * 与对话路径共用同一条审批闸门（见类注释）—— 这里不维护任何动作白名单，
     * 手机端全部工具（含 MCP）都能作为规则目标。
     *
     * 全部执行都在 [worker] 上做：审批可能阻塞 ~40s，工具可能访问网络/ADB。
     */
    private fun fire(rule: MotionRule) {
        lastFired[rule.id] = System.currentTimeMillis()
        // 记录"审批手势从这个采样点之后才算数"（挡住触发本次规则的这次手势）
        MotionBuffer.latest()?.let { gestureArmedAfterT = it.t }
        Log.i(TAG, "rule fired: ${rule.name} pattern=${rule.pattern} tool=${rule.tool} args=${rule.args}")
        val ctx = appContext
        if (ctx == null) {
            Log.w(TAG, "rule ${rule.name} fired but app context missing, skip")
            return
        }
        notifyGlass("已检测到${rule.name}：正在执行…")
        worker.execute {
            try {
                val args = runCatching { JSONObject(rule.args) }.getOrElse { JSONObject() }
                val decision = ApprovalGate.preExecute(
                    ToolSource.MOTION_RULE, rule.tool, args, context = ctx,
                )
                if (decision is ToolDecision.Deny) {
                    Log.w(TAG, "rule ${rule.name} denied: [${decision.origin}] ${decision.reason}")
                    notifyGlass("「${rule.name}」未执行：${decision.reason}")
                    return@execute
                }
                val result = runCatching { ToolRegistry.execute(ctx, rule.tool, rule.args) }
                    .onFailure { Log.w(TAG, "rule ${rule.name} tool=${rule.tool} failed", it) }
                    .getOrElse { "执行失败：${it.message ?: it.javaClass.simpleName}" }
                notifyGlass("「${rule.name}」：${shorten(result)}")
            } catch (t: Throwable) {
                // 单线程池的线程一旦带异常退出，后面排队的任务会永远等不到执行
                Log.w(TAG, "rule action failed: ${rule.name}", t)
            }
        }
    }

    /** 工具结果回显收敛：单行化 + 截断（网页/文件内容级别的长结果不能糊满眼镜端显示） */
    private fun shorten(result: String): String =
        result.trim().replace('\n', ' ').take(200).ifBlank { "已执行" }

    /** 规则触发反馈：复用工具进度通道在眼镜端显示文字（仅更新显示不触发语音，失败静默） */
    private fun notifyGlass(text: String) {
        val s = GlassToolConfirmChannel.global.liveSession() ?: return
        val link = s.cxrLink ?: return
        runCatching { s.aiConversation.sendGlassesProgress(link, text) }
            .onFailure { Log.w(TAG, "notifyGlass failed", it) }
    }
}
