package com.rokidlab.phone.ai

import org.json.JSONObject

/**
 * 「原地打转」检测：同一份调用反复发生后，给模型一句**框架自产的**提醒。
 *
 * ## 为什么需要（2026-10-09，gps-live 事故 P0-6）
 *
 * 现有自省只有一条，判据是**"工具抛异常且瞬时重试耗尽"**（[LessonText.retryGuidance]）。
 * 而真机上发生的是**另一种失效模式**：工具**全部成功返回**，但毫无进展 ——
 * 用户说"修"和"重写"，6 轮里 37 次工具调用、**0 次写操作**，
 * `read_code_file` 读的是同一份文件（`index.ink` **5 次**、`app.json`/`app.js` 各 4 次），
 * 甚至同一个**不存在**的 `index.html` 被读了 2 次。
 *
 * 这一切**没有任何异常**，所以所有守卫都看不见它。
 * 判据必须换：**"这一轮相比上一轮前进了没有"**，而不是"有没有报错"。
 *
 * ## 判据为什么放在"调用指纹"上
 *
 * "有没有进展"无法直接计算（框架不知道工具语义），但**重复本身是它的可靠代理**：
 * 同一个 `工具名 + 归一化参数` 再次出现，而模型又没拿到新信息时，这个动作就不会推进任务。
 * 提示里因此给了两条出路（换动作 / 说清卡在哪），而不是简单禁止 ——
 * 因为"重读一个可能已变化的文件"在少数场景下是正当的。
 *
 * ## 几个刻意的设计取舍
 *
 *  - **滑动窗口**（[WINDOW]）而不是全程计数：长会话里同一个调用天然会重复（如查时间），
 *    全程计数会把正常行为报成打转。只看"最近若干次调用里重复了几次"。
 *  - **按会话隔离**：跨会话互不影响（判据同"新对话不该记着别的会话"那条）。
 *  - **只提示、不拦截**：拦截（拒绝执行）会在正当场景下造成能力缺失；
 *    本轮只做"让模型看见自己在重复"，强度可后续再调（见报告 §8 的待决策项）。
 *  - **提示文案由框架产出**、不含任何工具输出 ⇒ 不会成为注入载体（同 [LessonText] 的纪律）。
 */
internal object ToolRepetitionTracker {

    /** 滑动窗口大小：只看最近这么多次调用。40 ≈ 3~5 个工具轮，够覆盖"同一任务内重复"。 */
    private const val WINDOW = 40

    /** 会话数上限（超出后丢最旧的一个，防长跑进程里 session 表无限增长） */
    private const val MAX_SESSIONS = 24

    /** sessionId → 最近调用的指纹队列（顺序 = 发生顺序） */
    private val history = LinkedHashMap<String, ArrayDeque<String>>()

    /** 计数从第几次开始提醒：第 2 次就提（第 1 次是正常调用） */
    const val WARN_FROM_OCCURRENCE = 2

    /** 参数里**易变**的字段名（每次调用都不同，参与指纹会让"同一动作"看起来不同 ⇒ 等于不检测） */
    private val VOLATILE_KEYS = setOf(
        "timestamp", "ts", "time", "requestId", "request_id", "cbId", "cb_id", "callId", "call_id", "nonce",
    )

    /**
     * 计算调用指纹：`工具名 + 归一化参数`。
     *
     * 归一化三步（缺一不可，判据同"归一化必须排除易变字段，否则等于不检测"）：
     *  1. 解析 JSON、按 key **排序**（同一组参数换个书写顺序仍是同一个动作）；
     *  2. 丢掉 [VOLATILE_KEYS] 里的易变字段；
     *  3. 值做**宽松化**：把项目名等路径里的引号/空白压掉，避免仅因措辞差异而误判为不同。
     *
     * 解析失败（参数不是合法 JSON）时退回原文前 200 字符 —— 宁可少判，不要抛异常。
     */
    fun fingerprint(name: String, argsJson: String?): String {
        val raw = argsJson?.trim().orEmpty()
        if (raw.isEmpty() || raw == "{}") return "$name()"
        return runCatching {
            val o = JSONObject(raw)
            val keys = o.keys().asSequence().filter { it !in VOLATILE_KEYS }.sorted().toList()
            val body = keys.joinToString(",") { k ->
                val v = o.opt(k)?.toString().orEmpty().replace(Regex("\\s+"), " ").trim()
                "$k=$v"
            }
            if (body.isEmpty()) "$name()" else "$name($body)"
        }.getOrElse { "$name(${raw.take(200)})" }
    }

    /**
     * 记一次调用并返回**它是窗口内的第几次**（第 1 次 = 首次）。
     *
     * 返回 1 表示这是新动作，调用方不必提示；>= [WARN_FROM_OCCURRENCE] 才值得提醒。
     */
    fun noteAndCount(sessionId: String, fingerprint: String): Int = synchronized(history) {
        val dq = history.getOrPut(sessionId) { ArrayDeque() }
        val seen = dq.count { it == fingerprint } + 1
        dq.addLast(fingerprint)
        while (dq.size > WINDOW) dq.removeFirst()
        if (history.size > MAX_SESSIONS) {
            // 丢最旧的一个会话（LinkedHashMap 的插入序 = 首次出现序）
            history.keys.firstOrNull()?.let { history.remove(it) }
        }
        seen
    }

    /** 会话结束时清理（防长跑进程里残留；调用方按需接）。 */
    fun reset(sessionId: String) {
        synchronized(history) { history.remove(sessionId) }
    }

    /**
     * 重复调用的提醒文案（**框架自产，不含任何工具输出**）。
     *
     * 措辞要点：① 说清"第 N 次"（让它意识到是重复而非新动作）；
     * ② 给出**两条**出路（换动作 / 说清卡在哪）而不是单纯禁止 ——
     * 少数场景下重读确实正当（文件可能已变），一刀切会让模型不敢重试真正需要重试的动作。
     */
    fun reminder(toolName: String, occurrence: Int): String =
        "⚠️ 提醒：你在本次对话里已经**第 $occurrence 次**做完全相同的调用（`$toolName`，参数一致）。" +
            "如果你这次没有拿到**新的**信息，说明这个动作不会推进任务 —— " +
            "请换一个动作（动手写/改、换参数、换工具），或者直接告诉用户你卡在哪一步、缺什么。"
}
